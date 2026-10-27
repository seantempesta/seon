# UI work 2026-10-20..24: accounting (read-only, at main 8517a97d6)

## 1. UI merges (src delta from git log --numstat, paths render*/web*; approx, merges excluded)
| commit/branch | what | src +/- | owner uses it? |
|---|---|---|---|
| 08348cb89, fc2938dbc (10-20) | W2 live dashboard + agent zoom as renders on two procs | +1942, +252/-75 | the dashboard, yes |
| 5fd79b143, bc33b3a84, ddd478d71 (10-21/22) | no beat; no clipping fns; tiles as data | -69, +120, -27 | invisible |
| e1ffe67e0, f61364b38, 28149280f, 73056d989 | declared AI renders (schema-declared render, one printer) | +185, +215, +13 net | no (AI face; nothing reads it per audit) |
| 8edb35a6d..daddf93e3 (10-23, jolt/agent-view) | chat-style agent window, 5 tester rounds | +393, +138, +78, +37, +12, +8 (~+670/-370) | superseded by page 2 days later |
| aeb4b5dd1..7283c63a0 | the walk (render/walk, data, rendered, /walk/{eid}) | ~+450 | no: /walk duplicates /agent |
| f18321f87, 3212350b7, 5666aebb3 (jolt/page) | one page fn for root/agent/any entity, Tailwind, /more scroll | +407/-1117 (net -338 with later fixes) | yes: this is today's page |
| later 10-23 fixes (ee39c56a5, 05f6898c0, ...) | page paints whole, root boot etc. | ~+70 | marginal |
Net: render/ui/proc/ai/web/html = 2284 lines today (render 414, ai 329, proc 498, ui 596, web 334, html 113).
Nothing merged since 10-20 is a /debug transcript. 4 days of merges went to: renames, the walk, the page-as-entity, chat window that was then replaced.

## 2. What a human sees today (live, root 7777, boot 960a9eb2)
- GET / : 200, 71,732 bytes of HTML, 9,898 chars of text: "Seon on Jolt . root", a raw EDN-ish map (#:seon.root{:id :root :boot ... :quiescent map 2 items, depth 2 ...}), problems list (137 stale-section rows, "...+144 chars", "(subs (:message (get-in result/h4 ...)))" REPL snippets), evaluations as rows "an outside agent, through root's server / (count (my.root/problems sys)) => 2 / 16,316,485 ticks . 62.4 ms . 78 MB alloc", a "nil nth not supported" error. It is a REPL eval log with cost numbers. No model messages.
- Agents: `{:op :agents}` shows :agents {} (no live agent), so no /agent/<id> to fetch. /agent/x/debug returns 200 (965 bytes) "no agent x/debug in root's boot ... seon.render/no-location": the route prefix /agent/ passes "id/debug" to view-of, which makes a keyword. There is NO /debug route (web.clj:139).
- A context read shows as one evaluation row cut to its first lines (handoff says so).

## 3. Gap to v1 and what is recorded
v1 (~/src/seon, web.clj:3209 debug-response + render/sent.clj, 115 lines): "Model calls: what each was sent", a row per provider attempt (:seon.ai.attempt/basis-t, finish-reason, prompt/cached/completion tokens), each opening to sections with estimated tokens and exchanges. Notably v1 ALSO stored no prompt: sent.clj re-derives the context from the db at the call's basis (call-database / context/model-context).
seon-next today (src/seon/turn.clj:200-220): per model turn a :seon.turn/* record: id, n, origin, :basis (task, run, turn, observed, last-evals, commit), :calls [{id code thinking}], :reply, :reasoning, :finish, :model, usage (tokens, cache-hit-tokens, cache-miss-tokens), :ms, split. The full sent message list is NOT stored; it is remade by seon.context/render from basis on the basis' commit (context.clj:413 `remade` -> {:basis :block :messages}; :454 `request-text`). Tool results = the evaluations recorded on the run. So everything the transcript needs is derivable; the pieces (remade, request-text, sections) already exist and are only used for the cut row.
Caveat: scrollback messages of earlier turns are rebuilt each time; if render's basis semantics change (acts-by-second-move changes the format, 18 commits, +887/-106 incl. turn.clj +103), old turns re-render in the new format, not as sent. Store what was sent only if that matters (the record would be large; zstd is already applied to stored values).

## 4. Smallest path to /agent/{id}/debug
- Route: in web.clj handler add a `(str/ends-with? uri "/debug")` case giving view `{:debug id}`; ~5 lines.
- proc: new page value = for each model turn of the run, `context/remade` (db, turn eid) -> messages, plus turn usage facts; ~40 lines in render/proc.clj.
- ui: one function laying out messages as chat bubbles (role, whole content in <pre>, tool-calls code, tool answer, tokens via select/estimate, per-turn cache-hit/finish/model/cost); ~60-80 lines in render/ui.clj. Whole text, no printer cut (the printer is used only inside the context text).
- Reuse: context/remade, request-text, sections, turn-record; seon.render/page layout + css; the prototype branch jolt/agent-view-prototype (4 commits, never merged, src +221/-50 in ui, +101/-41 in proc, context +40, model +18) already did "each turn a row with the context remade from its basis" with fixes from a live run: mine it, don't redo. Estimate: 120-160 src lines total, plus one test asserting the page contains the whole first user message and a tool result.
- Delete (audit item 1, ~-40 src and more): seon.render/shown AI face and its recorded render, render/data, render/rendered (test-only), /walk route + view (duplicate of /agent; web.clj:140), and then the page's cut context row (replaced by a link to debug). seon.render.ai (329 lines) only exists for the AI face: check what survives (model's context printing uses it? verify) before deleting; potentially -300.
Branches: jolt/viewer 0 commits ahead (worktree .worktrees/viewer does not exist; ls .worktrees has none named viewer). jolt/evaluations 15 ahead (parked, superseded by standard format). jolt/acts-by-second-move 18 ahead (turn format, agent-loop's). jolt/agent-view-prototype 4 ahead (the debug view prototype, closest thing to the target).

## 5. Why it stalled (judgment)
1. The target (v1's debug transcript) was in the owner's head, but the plan step 3 was only written 10-24; for 3 days the "UI" task was framed as "views are data rendered by the schema's render through one printer", so effort went into the walk/data/rendered/AI-face generality (+~900 lines) instead of a page nobody had to be clever about.
2. The page shows evaluations (what the agent ran), not model calls (what the model saw): wrong object. The chat window of 10-23 was a first correct shape but was replaced by the entity page two commits later (-1117).
3. The context itself is in flux (acts-by-second-move), and the viewer was blocked on "it will show that format when it lands": a dependency that is real but not a reason to not render today's remade messages.
4. The built prototype (agent-view-prototype) was left unmerged after the page rewrite; work was repeatedly reverted/re-merged (a-page-is-an-entity merged, reverted 8a9acf07a, reapplied 52fa16c91), and review rounds by subagent screenshots, not by the owner opening /debug.
5. Nobody ran the owner's check: open /agent/<id>/debug. Today it 404s into "no agent".
