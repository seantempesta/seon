# The pull and its renders: the design being built

One design for what a page and a model's request are made of, with what exists on main today, what moves, and the order it ships in. It is a working page: each part leaves it for the section that owns it ([ctx](ctx.md), [render](render.md), [data modeling](data-modeling.md)) when it lands.

## The model

Every message in a model's request and every part of a page is the declared render of an entity or an attribute the pull holds. The pull is what the agent's pattern asks: its role's pattern, named in its own, plus what it added. The order is the data's: a conversation is its members in the order their transactions arrived, a page is its pattern's clauses in order. So adding something an agent or a person sees is a write of data (a schema's render, a clause, an entity), never an edit to a function that lists parts.

## What exists today, counted from the code

Counted on main 61a2d46eb0 by a form run as root (each schema's `:seon.render/hiccup`, `:seon.render/openai-compat` and `:seon.render/model`; each function of `seon.render.ui`, `seon.ctx.wire`, `seon.ctx.session` and `seon.ctx.status`).

| | count |
|---|---|
| renders a schema declares | 37: 28 for html, 5 for the model's messages, 6 printers of a value for the model |
| functions in those four namespaces | 210 |
| of them, declared by a schema | 31 |
| lists a page's frame places | 7 |
| components placed by hand, declared by nothing | 36 |
| helpers (words, layout, reading) | 136 |

The 36 hand-placed components are the work ([one rendering system](render.md#one-rendering-system-drawn-from-the-data)). Each has one of three causes, and the move that removes it:

| move | what it is | the components it covers | count |
|---|---|---|---|
| C | the conversation is one ordered join; each member is drawn by its schema's render for that output | `system-message`, `session-examples`, `example-session`, `chat-ask`, `chat-end`, `conversation-turn`, `proof-story`; in the request `example-msgs`, `example-session-msgs`, `model-parts`, `page-diff-msgs` (which stays a function of two stored points, called from the model call's render) | 11 |
| A | an attribute whose schema declares a render is placed by the walk, in pattern order | `turn`, `agent-state`, `status`, `status-hiccup`, `ctx-key`, `ctx-keys` | 6 |
| B | a judgment a page shows is an asked view: a small entity that names it, joined by the pattern, as a search already is | `own-problems`, `eval-tiles`, `eval-tile`, `eval-output`, `problems-tile`, `process-tile`, `root-evals`, `root-head`, `root-quiet`, `root-side`, `agent-head`, `sign-in`, `unresolved-render`, `system-source` (the top bar's identity line, a view of the system entity) | 14 |
| stays | an entity names its own render, or layout glue | `pull-view`, `pull-front`, `stage`, `stage-front`; `page-rules` | 5 |

After the three moves (an estimate until each is built): about 20 of the 31 become declared renders, about 11 are deleted, 5 stay.

## The data moves

Each move with the pull form before and after it, and what it deletes.

| move | before | after | deletes |
|---|---|---|---|
| the role named in the pattern | a whole pattern stored per entity, written by code at boot for root's session and the user (three copies of the user's that disagree) | `[:seon.db/agent-fact {:seon.agent/_agents :seon.db/user-fact}]`: the role's name, then its own clauses; a join "again, to depth n" with n a config key | `seon.task/user-pattern`, `agents-join`, `seon.root/root-session-pattern`, the two boot writes |
| required parts | any `swap!` of a pattern was written | a role's schema says what its pattern must keep (`:seon.ctx/required`); a `swap!` that drops one is refused naming it (on main) | silent loss of the task, the messages, the turns |
| A | `agent-parts` lists six parts by name | each is the render on its attribute's schema, placed where the pattern asks the attribute | the tail of `agent-parts`, `added-keys` |
| B | a render reads the db or a proc's state by hand | `{:seon.view/_of [...]}`: asked views, each drawn by the render of the value its function answers | the hand lists in `agent-parts` and `root`, `root-head`'s counts, the head's hand pull |
| C | `agent-msgs` and `conversation-list` each write out the sequence | the three conversation joins (those that ask their link's transaction) in transaction order; the page at a point is the model call's own render | `turn-entities`' order, the second producer of the conversation, the opening sentences called from two places |
| the page at the first point | the page's hiccup | `@ctx` whole (without the conversation's joins), then the hiccup, both functions of the first call's basis; later points the hiccup's diff only | nothing; about 6k tokens once, cached |
| a join's shape | not said anywhere | the pattern's description and `(my.db/pulled @ctx)` say it: one entity, or a map of members by id | turns spent finding it (a kept run: turns 12-15, 31-32) |
| a referenced session | a copy keyed `example/<id>` loaded on main | a ref to an agent at a point, with how it is shown as data on the join; built with C | `my.root/load-example!`, the copy |

## The order it ships in

Each row is one tip, small enough to land alone, with who builds it, its proof and what the next live run should show.

| # | tip | builder | proof, red first | what the next live run should show |
|---|---|---|---|---|
| 1 | a cited schema drawn as what it is; the pattern's description and required parts | Data model | landed (batch 61) | no turn spent finding how a schema declares a render |
| 2 | the role named in a pattern; a join to a depth | Data model (a helper builds, Context reviews) | a stored `[role, clause]` pulls the role's joins; depth 2 pulls two levels | nothing visible; it is what 3 stands on |
| 3 | the two boot writes and three pattern functions deleted; a check that no function writes `:seon.ctx/pattern` | Data model | the check, at zero | a join added to a role reaches root's session with no boot |
| 4 | descriptions on an eval's answer keys, a file's path, a task's proofs; a join's shape in the pattern's description | Data model | named tests; `(doc :seon.repl/answer)` answers each key | the system message can drop those sections |
| 5 | the system message cut to what is true for every agent | Context | each kept sentence checked against the code and a recorded request | the same task, old message against new |
| 6 | `@ctx` and the hiccup at the first point; a cite's pull shortened to what its render shows | Context; Data model for the cite | two requests around turn 1 equal up to the page | no `(my.db/pulled @ctx)` call by hand in the first turns |
| 7 | a proof's four states; a red proof shows every failure and what it calls (its own source is not repeated: an owner's ruling of 2026-11-05 a test pins; showing it needs a new ruling) | Web, Context | one test a state (the physics audit's three high rows) | no repeated `(prove)` on a deferred or dead proof |
| 8 | move A | Web; Data model for the schema entries | the planted-world proof, the attribute half | a new attribute with a render appears with no code edit |
| 9 | move C, with the named sub-patterns | Context; Data model for the patterns | recorded requests byte for byte; the planted-world proof, the entity half | a new kind of member reaches the page and the request |
| 10 | move B | Web, Optimization; Data model for the view's schema | a REPL trial on root's page with its cost, then the planted proof | root's page with no hand-read state |

## Where each tip stands

Kept current by the Data model session; the newest landed state runs on the standing system, http://127.0.0.1:7777/. "Running to see" is that system showing a live agent's session with hiccup drawn as html, Markdown drawn, structured evals, proofs and diffs, `@ctx` and the page at the first point, and the short system message.

| # | tip | state (2026-11-11 16:45) |
|---|---|---|
| 1 | a cited schema drawn as what it is; the pattern's description and required parts | landed, main 61a2d46eb0 |
| 2 | the role named in a pattern; a join to a depth | reviewed and approved by Context; two changes in build on `jolt/rolenames` (one join asked twice becomes one join; the form agents are told names the role) |
| 3 | the boot writes and pattern functions deleted, with the check | not started; follows 2 |
| 4 | descriptions on the answer's keys, a file's path, a task's proofs | proving in batch 62 (`jolt/descs`); the join-shape sentence not started |
| 5 | the short system message | proving in batch 62 (`jolt/prompt`, 2.5 KB) |
| 6 | `@ctx` and the hiccup at the first point; the cite's pull shortened | not started (Context; the cite's pull is Data model's) |
| 7 | a proof's four states; every failure and what it calls | proving in batch 62 (`jolt/proofstates`) |
| 8 | move A, and with it text fields as Markdown by a `:seon.db/text` schema | not started. Its first commit is Web's: where the walk places an attribute, the render its schema declares draws its value (today only an entity is drawn by a schema lookup) |
| 9 | move C, with the named sub-patterns | not started; waits on 2 and 3 |
| 10 | move B | not started; a REPL trial with its cost comes first |
| - | a turn's fold names each step's intent | proving in batch 62 (`jolt/stepintents`, Web) |
| - | a component's root carries `:render`, the full symbol, in place of a class | both halves built, not yet run together (`jolt/datarender`, Web; `jolt/rendername` on it, Context) |
| - | a render's output named by a `:seon.render/hiccup-value` schema, so a value that is hiccup is drawn as html | in build (`jolt/hiccupvalue`, Web's helper) |
| - | a role's standing cites as data: the cites an agent starts with are read from its task first (`:seon.config.agent/cites`, which a task may hold), so a worker's and a preparer's differ | built, named tests not yet run (`jolt/rolecites`, Context); the worker's "program is your branch's facts" section rides there |
| - | a stuck agent is stopped: a judgment on its own evals ends it with the outcome `:stuck`, read by whoever started it | not started (Context); what "near" compares is open: the functions an eval called include plumbing and fire on plain reading, so the calls' targets must be part of it |
| - | the turn budget shown once (a line in the turn part would be a diff every turn) | not started; Web's render of the agent's `max-turns` in the first page |
| - | a task whose plan names code its branch does not hold is flagged before it is released (a cite that resolves to nothing is already data: the cite's target is unresolved) | not started; a class of the problems report, Tests |
| - | the planted-world proof (a schema with both renders, joined in a pattern, appears with no code edit) | in build (`jolt/planted`, Web); tips 8 and 9 land with its two halves |

## Not decided

What "near" means for a stuck agent's repeats (the functions its evals called is the proposal; Context checks it on kept runs). Whether a view (move B) costs more at each draw than the components it replaces: not measured. A compaction's point is decided and not built: one attribute on the agent, written with the compaction, built with tip 6 (the page whole at that point is the same function as at the first). A model call's previous call is decided: read through the agent's calls by their number, no new ref. Whether embeddings stay in the store. (Decided since: a render is never handed other renders' output, [what a render is handed](render.md#what-a-render-is-handed).)
