// daily-triage's Zulip tab on Elf Desk, and its dashboard card: the last day of Zulip by topic, with a digest of
// each. Topics that involve you come first (mentions, DMs, followed topics), then those with unread messages, then
// the most recently active. Done marks the topic read in Zulip, through the desk.
import {
    LitElement, html, css, shared, cardStyles, markdown, markdownStyles, spinner, ago, runTime, took, count, joinParts,
    isToday, settingsButton, Ticking, elf,
} from '/elf-kit/1/kit.js';
import { runs, run, done } from './runs.js';

const SETTINGS = ['ZULIP_SERVER_1', 'ZULIP_EMAIL_1', 'ZULIP_API_KEY_1', 'ZULIP_WATCH_1'];

/** The id at the end of a message's link (…/near/631450774), or null. */
function messageId(url) {
    const m = /\/near\/(\d+)$/.exec(url ?? '');
    return m ? Number(m[1]) : null;
}

/** Messages grouped by "stream > topic", in the order described above. {@code hiding}: Done clicked, not answered. */
function topics(data, marks, hiding = new Set()) {
    const byKey = new Map();
    for (const m of data?.zulip?.messages ?? []) {
        const key = `${m.stream} > ${m.topic}`;
        if (!byKey.has(key)) byKey.set(key, { key, stream: m.stream, topic: m.topic, messages: [] });
        byKey.get(key).messages.push(m);
    }
    const all = [...byKey.values()].map(t => {
        t.messages.sort((a, b) => a.time - b.time);
        t.last = t.messages[t.messages.length - 1];
        t.mention = t.messages.some(m => m.mention);
        t.you = t.mention || t.messages.some(m => m.involvesMe);
        t.unread = t.messages.filter(m => m.unread).length;
        t.digest = data.zulip?.digests?.[t.key];
        // Done until a newer message arrives; or clicked, and Zulip hasn't answered yet.
        const at = marks[t.last.server]?.[t.key];
        t.done = (!!at && Date.parse(at) >= t.last.time * 1000) || hiding.has(t.key);
        return t;
    });
    const rank = t => (t.you ? 2 : 0) + (t.unread ? 1 : 0);
    return all.sort((a, b) => rank(b) - rank(a) || b.last.time - a.last.time);
}

/** What Done sends: the topic, and for direct messages (one "topic" here) the ids of the ones listed. */
function doneValues(t) {
    return { server: t.last.server, stream: t.stream, topic: t.topic,
        messages: t.stream === 'DM' ? t.messages.map(m => messageId(m.url)).filter(Boolean) : [] };
}

class TriageZulip extends LitElement {

    static properties = {
        _runs: { state: true },
        _run: { state: true },
        _marks: { state: true },
        _picked: { state: true },
        _doing: { state: true },
        _hiding: { state: true },
        _all: { state: true },
        _loading: { state: true },
        _error: { state: true },
    };

    static styles = [shared, markdownStyles, css`
        .page { display: grid; gap: 26px; }
        h1 { font-size: 34px; line-height: 1.15; font-weight: 600; letter-spacing: -0.022em; }
        .sub { margin: 6px 0 0; color: var(--muted); font-size: 14px; }
        .topics { display: grid; gap: 12px; }
        article { padding: 16px 18px; display: grid; gap: 8px; min-width: 0; }
        article.you { box-shadow: inset 3px 0 0 var(--accent); }
        .where { display: flex; flex-wrap: wrap; gap: 6px 10px; align-items: baseline; }
        .where .sm { margin-left: auto; align-self: center; }
        details.done > summary { padding: 4px 0; }
        details.done[open] > .topics { margin-top: 12px; }
        .stream { font-size: 13px; color: var(--muted); }
        .topic { font-weight: 600; font-size: 15.5px; overflow-wrap: anywhere; color: var(--ink); text-decoration: none; }
        a.topic:hover { text-decoration: underline; }
        .digest { margin: 0; max-width: 46rem; }
        details > summary { cursor: pointer; font-size: 13.5px; color: var(--muted); width: fit-content; }
        ol.msgs { list-style: none; margin: 10px 0 0; padding: 0; display: grid; gap: 12px; }
        .msg { display: grid; gap: 2px; min-width: 0; padding-left: 12px; border-left: 2px solid var(--line); }
        .msg.unread { border-left-color: var(--accent); }
        .who { font-size: 13.5px; font-weight: 600; }
        .who .muted { font-weight: 400; }
        .md { font-size: 14px; overflow-wrap: anywhere; }
        .md :is(p, ul, ol, pre, blockquote) { margin: 4px 0; }
        .empty { color: var(--muted); padding: 18px; margin: 0; }
        .skeleton { height: 92px; }
        .all { display: flex; flex-wrap: wrap; gap: 10px; align-items: center; font-size: 14px; }
    `];

    constructor() {
        super();
        new Ticking(this);
        this._picked = null;
        this._loading = true;
        this._hiding = new Set();
        this._queue = Promise.resolve();
        this._marks = {};
    }

    connectedCallback() {
        super.connectedCallback();
        this._load();
    }

    async _load() {
        this._loading = true;
        this._error = null;
        try {
            this._runs = await runs('zulip');
            this._run = await run(this._runs, this._picked);
            this._marks = await done('zulip');
        } catch (e) {
            this._error = e.message;
        } finally {
            this._loading = false;
        }
    }

    /**
     * Done: the topic folds away straight away, so the next one can be read, and Zulip marks it read in the
     * background; it comes back when it has newer messages. The clicks go one at a time.
     */
    _done(t) {
        const key = t.key;
        this._hiding = new Set(this._hiding).add(key);
        this._error = null;
        this._queue = this._queue.then(async () => {
            try {
                await elf.act('zulip-done', doneValues(t));
                this._marks = await done('zulip');
            } catch (e) {
                this._error = `${t.topic || t.stream}: ${e.message}`;
            } finally {
                const hiding = new Set(this._hiding);
                hiding.delete(key);
                this._hiding = hiding;
            }
        });
    }

    /** Done all: each open topic in turn, as if you clicked its Done. One that fails is left for you; the rest go on. */
    async _doneAll(todo) {
        const failed = [];
        this._error = null;
        for (let i = 0; i < todo.length; i++) {
            const t = todo[i];
            this._all = { at: i + 1, of: todo.length };
            this._doing = t.key;
            try {
                await elf.act('zulip-done', doneValues(t));
            } catch (e) {
                failed.push(`${t.topic || t.stream}: ${e.message}`);
            }
        }
        this._marks = await done('zulip');
        this._doing = null;
        this._all = null;
        if (failed.length) this._error = 'Not marked read: ' + failed.join('; ');
    }

    _allBar(open) {
        const n = open.length;
        if (this._picked || (!n && !this._all)) return '';
        if (this._all?.of) return html`<div class="all" role="status">${spinner} Marking ${this._all.at} of ${this._all.of} read…</div>`;
        if (this._all === 'confirm') return html`<div class="all" role="group" aria-label="Confirm Done all">
            Mark all ${n} ${n === 1 ? 'topic' : 'topics'} read in Zulip?
            <button class="sm primary" @click=${() => this._doneAll(open)}>Mark all read</button>
            <button class="sm quiet" @click=${() => { this._all = null; }}>Cancel</button></div>`;
        return html`<div class="all"><button class="sm" ?disabled=${this._doing || this._hiding.size}
            @click=${() => { this._all = 'confirm'; }}>Done all (${n})</button></div>`;
    }

    _time(seconds) {
        return new Date(seconds * 1000).toLocaleTimeString(undefined, { hour: '2-digit', minute: '2-digit' });
    }

    _topic(t) {
        return html`
            <article class="panel ${t.you ? 'you' : ''}">
                <div class="where">
                    <span class="stream">${t.stream === 'DM' ? 'Direct message' : `#${t.stream}`}</span>
                    <a class="topic" href=${t.last.url} target="_blank" rel="noopener">${t.topic || '(no topic)'}</a>
                    ${t.done ? '' : html`<button class="sm" ?disabled=${this._all?.of}
                        title=${t.stream === 'DM' ? 'Marks these direct messages read in Zulip' : 'Marks the topic read in Zulip'}
                        @click=${() => this._done(t)}>${this._doing === t.key ? spinner : ''}Done</button>`}
                </div>
                <div class="chips">
                    ${t.mention ? html`<span class="chip run">Mentions you</span>`
                        : t.you ? html`<span class="chip run">Involves you</span>` : ''}
                    ${t.unread ? html`<span class="chip warn">${t.unread} unread</span>` : ''}
                    <span class="chip">${t.messages.length} ${t.messages.length === 1 ? 'message' : 'messages'}</span>
                    <span class="chip">Last at ${this._time(t.last.time)} by ${t.last.sender}</span>
                </div>
                ${t.digest ? html`<p class="digest">${t.digest}</p>` : ''}
                <details>
                    <summary>${t.digest ? 'Read the messages' : 'Messages'}</summary>
                    <ol class="msgs">
                        ${t.messages.map(m => html`
                            <li class="msg ${m.unread ? 'unread' : ''}">
                                <span class="who">${m.sender} <span class="muted">${this._time(m.time)}</span></span>
                                <div class="md">${markdown(m.content)}</div>
                            </li>`)}
                    </ol>
                </details>
            </article>`;
    }

    render() {
        const r = this._run;
        const all = r ? topics(r.data, this._marks, this._hiding) : [];
        const open = all.filter(t => !t.done);
        const doneList = all.filter(t => t.done);
        return html`
            <div class="page">
                <div class="page-top"><div>
                    <h1>Zulip</h1>
                    <p class="sub">
                        ${r ? html`The ${r.data?.zulipHours ?? 24} hours up to ${runTime(r.at)}: ${all.length}
                                ${all.length === 1 ? 'topic' : 'topics'}, ${r.data?.zulip?.messages?.length ?? 0} messages${r.data?.durationMs ? `, took ${took(r.data.durationMs)}` : ''}`
                            : this._loading ? '' : 'Not checked yet.'}
                    </p>
                </div>${settingsButton(SETTINGS, 'Zulip settings')}</div>
                <elf-run-bar command="zulip" label="Check Zulip now" .runs=${this._runs ?? []} .picked=${this._picked}
                    note="Reads the last 24 hours of your streams and DMs, plus any older unread mentions, and digests each topic. Marks nothing read."
                    @run-picked=${e => { this._picked = e.detail; this._load(); }}
                    @run-finished=${() => { this._picked = null; this._load(); }}></elf-run-bar>
                ${this._error ? html`<p class="error" role="alert">${this._error}</p>` : ''}
                ${this._loading && !r ? html`<div class="topics">${[1, 2, 3].map(() => html`<div class="skeleton"></div>`)}</div>` : ''}
                ${r ? html`
                    ${this._allBar(open)}
                    <div class="topics">
                        ${open.length ? open.map(t => this._topic(t)) : html`<p class="panel empty">${doneList.length
                            ? 'All done.' : 'Nothing happened on Zulip in that time.'}</p>`}
                    </div>
                    ${doneList.length ? html`<details class="done">
                        <summary>Done (${doneList.length})</summary>
                        <div class="topics">${doneList.map(t => this._topic(t))}</div>
                    </details>` : ''}` : ''}
            </div>
        `;
    }
}

/** The dashboard card: the topics that mention or involve you, out of the active ones. */
class TriageZulipCard extends LitElement {

    static properties = { _run: { state: true }, _topics: { state: true }, _state: { state: true }, _loaded: { state: true } };
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
            this._run = await run(await runs('zulip'), null);
            this._topics = this._run ? topics(this._run.data, await done('zulip')).filter(t => !t.done) : [];
            this._state = await elf.state();
        } catch {
            this._run = null;
        }
        this._loaded = true;
        elf.card({ attention: (this._topics ?? []).some(t => t.you || t.unread) });
    }

    render() {
        if (!this._loaded) return html`<div class="skeleton say-sk"></div>`;
        const r = this._run;
        const running = this._state?.running?.zulip;
        const failed = this._state?.failed?.zulip;
        let say;
        if (!r) say = 'Not checked yet. Check from the Zulip tab.';
        else {
            const all = this._topics;
            const mentions = all.filter(t => t.mention).length;
            const you = all.filter(t => t.you && !t.mention).length;
            const unread = all.filter(t => t.unread).length;
            const parts = [];
            if (mentions) parts.push(html`<a href="#zulip">${count(mentions, 'topic')}</a> ${mentions === 1 ? 'mentions' : 'mention'} you`);
            if (you) parts.push(html`<a href="#zulip">${count(you, 'topic')}</a> ${you === 1 ? 'involves' : 'involve'} you`);
            if (unread) parts.push(html`${count(unread, 'topic')} ${unread === 1 ? 'has' : 'have'} unread messages`);
            say = !all.length ? 'Nothing happened on Zulip.'
                : parts.length ? html`${joinParts(parts)}, out of ${count(all.length, 'active topic')}.`
                : `Nothing involves you; ${count(all.length, 'topic')} ${all.length === 1 ? 'was' : 'were'} active.`;
        }
        return html`<p class="say">${say}</p>
            <div class="meta">${r ? html`<span>The ${r.data?.zulipHours ?? 24} hours up to ${runTime(r.at)}${isToday(r.at) ? '' : ', not checked today yet'}</span>` : ''}${running
                ? html`<span class="running" role="status">${spinner}Running, started ${ago(running)}</span>`
                : failed ? html`<span class="error">The last run failed</span>` : ''}</div>`;
    }
}

customElements.define('triage-zulip', TriageZulip);
customElements.define('triage-zulip-card', TriageZulipCard);
document.body.append(document.createElement(elf.view === 'card' ? 'triage-zulip-card' : 'triage-zulip'));
