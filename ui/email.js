// daily-triage's Email tab on Elf Desk, and its dashboard card: per Gmail account, what needs you and what the triage
// marked read (and labelled). Done marks the thread read in Gmail, through the desk.
import {
    LitElement, html, css, shared, cardStyles, spinner, ago, runTime, took, count, isToday, settingsButton, Ticking, elf,
} from '/elf-kit/1/kit.js';
import { runs, run, done } from './runs.js';

const SETTINGS = ['GMAIL_ADDRESS', 'GMAIL_APP_PASSWORD', 'MAX_EMAILS', 'TRIAGE_LABEL'];

function gmail(account, d) {
    if (!d.messageId) return null;
    const id = d.messageId.replace(/^<|>$/g, '');
    return `https://mail.google.com/mail/?authuser=${encodeURIComponent(account)}#search/rfc822msgid:${encodeURIComponent(id)}`;
}

/** The run's accounts, each decision with when you marked it Done (doneAt). */
function withDone(data, marks) {
    return (data?.triage?.accounts ?? []).map(a => ({
        ...a,
        decisions: (a.decisions ?? []).map(d => ({ ...d, doneAt: marks[a.email]?.[d.messageId] ?? null })),
    }));
}

class TriageEmail extends LitElement {

    static properties = {
        _runs: { state: true },
        _run: { state: true },
        _accounts: { state: true },
        _picked: { state: true },
        _doing: { state: true },
        _hiding: { state: true },
        _all: { state: true },
        _loading: { state: true },
        _error: { state: true },
    };

    static styles = [shared, css`
        .page { display: grid; gap: 26px; }
        h1 { font-size: 34px; line-height: 1.15; font-weight: 600; letter-spacing: -0.022em; }
        .sub { margin: 6px 0 0; color: var(--muted); font-size: 14px; }
        section { display: grid; gap: 14px; }
        .account { font-weight: 600; font-size: 15px; display: flex; flex-wrap: wrap; gap: 8px; align-items: center; }
        ul { list-style: none; margin: 0; padding: 4px 0; }
        li { padding: 11px 18px; display: grid; grid-template-columns: minmax(0, 1fr) auto; gap: 3px 14px; align-items: center; }
        li > .text { display: grid; gap: 3px; min-width: 0; }
        li + li { border-top: 1px solid var(--line); }
        .subject { font-weight: 500; overflow-wrap: anywhere; color: var(--ink); text-decoration: none; }
        a.subject:hover { text-decoration: underline; }
        .from, .why { font-size: 13.5px; color: var(--muted); overflow-wrap: anywhere; }
        details > summary { cursor: pointer; padding: 11px 18px; font-size: 14px; color: var(--muted); }
        details[open] > summary { border-bottom: 1px solid var(--line); }
        .empty { color: var(--muted); padding: 14px 18px; margin: 0; font-size: 14px; }
        .skeleton.big { height: 40vh; }
        .all { display: flex; flex-wrap: wrap; gap: 10px; align-items: center; font-size: 14px; }
    `];

    constructor() {
        super();
        new Ticking(this);
        this._picked = null;
        this._loading = true;
        this._hiding = new Set();
        this._queue = Promise.resolve();
    }

    connectedCallback() {
        super.connectedCallback();
        this._load();
    }

    async _load() {
        this._loading = true;
        this._error = null;
        try {
            this._runs = await runs('email');
            this._run = await run(this._runs, this._picked);
            await this._marks();
        } catch (e) {
            this._error = e.message;
        } finally {
            this._loading = false;
        }
    }

    async _marks() {
        this._accounts = withDone(this._run?.data, await done('email'));
    }

    /** Done, or on its way there: clicked, and Gmail hasn't answered yet. */
    _isDone(d) {
        return !!d.doneAt || this._hiding.has(d.messageId);
    }

    /**
     * Done: the email moves to the done list straight away, so the next one can be read, and Gmail marks the thread
     * read in the background. The clicks go one at a time. One Gmail refuses comes back, with the error.
     */
    _done(account, d) {
        const id = d.messageId;
        this._hiding = new Set(this._hiding).add(id);
        this._error = null;
        this._queue = this._queue.then(async () => {
            try {
                await elf.act('email-done', { account, messageId: id });
                await this._marks();
            } catch (e) {
                this._error = `${d.subject || 'That email'}: ${e.message}`;
            } finally {
                const hiding = new Set(this._hiding);
                hiding.delete(id);
                this._hiding = hiding;
            }
        });
    }

    /** What still needs you in the newest run, across accounts: what Done all marks. */
    _open() {
        return (this._accounts ?? []).flatMap(a => (a.decisions ?? [])
            .filter(d => d.decision !== 'MARK_READ' && !this._isDone(d) && d.messageId).map(d => ({ account: a.email, d })));
    }

    /** Done all: each email in turn, as if you clicked its Done. One that fails is left for you; the rest go on. */
    async _doneAll() {
        const todo = this._open();
        const failed = [];
        this._error = null;
        for (let i = 0; i < todo.length; i++) {
            const { account, d } = todo[i];
            this._all = { at: i + 1, of: todo.length };
            this._doing = d.messageId;
            try {
                await elf.act('email-done', { account, messageId: d.messageId });
            } catch (e) {
                failed.push(`${d.subject || 'An email'}: ${e.message}`);
            }
        }
        await this._marks();
        this._doing = null;
        this._all = null;
        if (failed.length) this._error = 'Not marked read: ' + failed.join('; ');
    }

    _allBar() {
        const n = this._open().length;
        if (this._picked || (!n && !this._all)) return '';
        if (this._all?.of) return html`<div class="all" role="status">${spinner} Marking ${this._all.at} of ${this._all.of} read…</div>`;
        if (this._all === 'confirm') return html`<div class="all" role="group" aria-label="Confirm Done all">
            Mark all ${n} ${n === 1 ? 'email' : 'emails'} read in Gmail?
            <button class="sm primary" @click=${this._doneAll}>Mark all read</button>
            <button class="sm quiet" @click=${() => { this._all = null; }}>Cancel</button></div>`;
        return html`<div class="all"><button class="sm" ?disabled=${this._doing || this._hiding.size}
            @click=${() => { this._all = 'confirm'; }}>Done all (${n})</button></div>`;
    }

    _email(account, d, canDo) {
        const href = gmail(account, d);
        const subject = (d.subject || '(no subject)').replace(/\s+/g, ' ');
        return html`<li>
            <span class="text">
                ${href ? html`<a class="subject" href=${href} target="_blank" rel="noopener">${subject}</a>`
                    : html`<span class="subject">${subject}</span>`}
                <span class="from">${d.from}</span>
                <span class="why">${d.reason}</span>
            </span>
            ${canDo && d.messageId ? html`<button class="sm" ?disabled=${this._all?.of}
                title="Marks the thread read in Gmail" @click=${() => this._done(account, d)}>
                ${this._doing === d.messageId ? spinner : ''}Done</button>` : ''}
        </li>`;
    }

    _triage() {
        const accounts = this._accounts ?? [];
        if (!accounts.length) return html`<p class="muted">This run had no Gmail accounts to triage.</p>`;
        return html`
            <section aria-label="Email triage">
                ${accounts.map(a => {
                    const kept = a.decisions.filter(d => d.decision !== 'MARK_READ' && !this._isDone(d));
                    const doneList = a.decisions.filter(d => d.decision !== 'MARK_READ' && this._isDone(d));
                    const read = a.decisions.filter(d => d.decision === 'MARK_READ');
                    return html`
                        <div class="account">
                            ${a.email}
                            <span class="chip ${kept.length ? 'warn' : 'ok'}">${kept.length} need you</span>
                            <span class="chip">${read.length} marked read</span>
                        </div>
                        <div class="panel">
                            ${kept.length ? html`<ul>${kept.map(d => this._email(a.email, d, true))}</ul>`
                                : html`<p class="empty">${doneList.length ? 'All done.' : 'Nothing in this inbox needs you.'}</p>`}
                        </div>
                        ${doneList.length ? html`<details class="panel"><summary>Done (${doneList.length})</summary>
                            <ul>${doneList.map(d => this._email(a.email, d))}</ul></details>` : ''}
                        ${read.length ? html`<details class="panel"><summary>Marked read and labelled (${read.length})</summary>
                            <ul>${read.map(d => this._email(a.email, d))}</ul></details>` : ''}
                    `;
                })}
            </section>`;
    }

    render() {
        const r = this._run;
        return html`
            <div class="page">
                <div class="page-top"><div>
                    <h1>Email</h1>
                    <p class="sub">
                        ${r ? html`Triaged ${runTime(r.at)}${r.data?.durationMs ? `, took ${took(r.data.durationMs)}` : ''}${r.data?.emailed ? ', also emailed' : ''}`
                            : this._loading ? '' : 'Not triaged yet.'}
                    </p>
                </div>${settingsButton(SETTINGS, 'Email triage settings')}</div>
                <elf-run-bar command="email" label="Triage email now" .runs=${this._runs ?? []} .picked=${this._picked}
                    note="Goes through your unread Gmail: noise is marked read and labelled, the rest stays unread and is listed here with the reason. Nothing is emailed."
                    @run-picked=${e => { this._picked = e.detail; this._load(); }}
                    @run-finished=${() => { this._picked = null; this._load(); }}></elf-run-bar>
                ${this._error ? html`<p class="error" role="alert">${this._error}</p>` : ''}
                ${this._loading && !r ? html`<div class="skeleton big"></div>` : ''}
                ${r ? html`${this._allBar()}${this._triage()}` : ''}
            </div>
        `;
    }
}

/** The dashboard card: how many emails need you in the newest triage. */
class TriageEmailCard extends LitElement {

    static properties = { _run: { state: true }, _accounts: { state: true }, _state: { state: true }, _loaded: { state: true } };
    static styles = [shared, cardStyles];

    constructor() {
        super();
        new Ticking(this);
    }

    connectedCallback() {
        super.connectedCallback();
        this._load();
        this._off = elf.onChange(() => this._load());
    }

    disconnectedCallback() {
        this._off?.();
        super.disconnectedCallback();
    }

    async _load() {
        try {
            this._run = await run(await runs('email'), null);
            this._accounts = withDone(this._run?.data, await done('email'));
            this._state = await elf.state();
        } catch {
            this._run = null;
        }
        this._loaded = true;
        elf.card({ attention: this._need() > 0 });
    }

    _need() {
        return (this._accounts ?? []).flatMap(a => a.decisions).filter(d => d.decision !== 'MARK_READ' && !d.doneAt).length;
    }

    render() {
        if (!this._loaded) return html`<div class="skeleton say-sk"></div>`;
        const r = this._run;
        const running = this._state?.running?.email;
        const failed = this._state?.failed?.email;
        let say;
        if (!r) say = 'Not triaged yet. Run it from the Email tab.';
        else {
            const decisions = this._accounts.flatMap(a => a.decisions);
            const need = this._need();
            const read = decisions.filter(d => d.decision === 'MARK_READ').length;
            say = !decisions.length ? 'No new email in that run.'
                : need ? html`<a href="#email">${count(need, 'email')}</a> ${need === 1 ? 'needs' : 'need'} you${read ? html`, and ${read} ${read === 1 ? 'was' : 'were'} marked read` : ''}.`
                : `Nothing in your inbox needs you; ${count(read, 'email')} ${read === 1 ? 'was' : 'were'} marked read.`;
        }
        return html`<p class="say">${say}</p>
            <div class="meta">${r ? html`<span>Triaged ${runTime(r.at)}${isToday(r.at) ? '' : ', not today yet'}</span>` : ''}${running
                ? html`<span class="running" role="status">${spinner}Running, started ${ago(running)}</span>`
                : failed ? html`<span class="error">The last run failed</span>` : ''}</div>`;
    }
}

customElements.define('triage-email', TriageEmail);
customElements.define('triage-email-card', TriageEmailCard);
document.body.append(document.createElement(elf.view === 'card' ? 'triage-email-card' : 'triage-email'));
