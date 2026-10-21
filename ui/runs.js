// The runs daily-triage saved (~/.config/daily-triage/runs, one JSON file each), as its Email and Zulip tabs read
// them through the desk, and what you marked Done (~/.config/daily-triage/done).
import { elf, runTime } from '/elf-kit/1/kit.js';

const ID = /^(\d{4})-(\d{2})-(\d{2})_(\d{2})-(\d{2})-(\d{2})\.json$/;

/** "email" for any run that triaged email (the old morning timer's too), "zulip", or null (a briefing-only run). */
export function kindOf(mode) {
    return mode === 'zulip' ? 'zulip' : mode === 'briefing-only' ? null : 'email';
}

function atOf(file) {
    const m = ID.exec(file);
    return m ? new Date(+m[1], m[2] - 1, +m[3], +m[4], +m[5], +m[6]) : null;
}

/** Runs of a kind, newest first: [{file, id, at, status, label}], up to 30. */
export async function runs(kind) {
    const files = (await elf.list('runs')).filter(f => ID.test(f.name)).slice(0, 80).map(f => f.name);
    if (!files.length) return [];
    const heads = await elf.read('runs', files, ['mode', 'status']);
    return files.filter(f => heads[f] && kindOf(heads[f].mode) === kind).slice(0, 30).map(f => {
        const status = heads[f].status ?? 'success';
        return { file: f, id: f.slice(0, -5), at: atOf(f), status,
            label: `${runTime(atOf(f))}${status !== 'success' ? ` (${status})` : ''}` };
    });
}

/** A run by id (null: the newest) of a kind, with its file's contents, or null. */
export async function run(list, id) {
    const r = (id && list.find(x => x.id === id)) || list[0];
    return r ? { ...r, data: await elf.readJson('runs', r.file) } : null;
}

/** What you marked Done: {group: {key: when}} from done/<kind>.json (email: account, Message-ID; zulip: server, topic). */
export async function done(kind) {
    try {
        return (await elf.readJson('done', `${kind}.json`)) ?? {};
    } catch {
        return {};
    }
}
