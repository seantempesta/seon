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
| the page at the first point | the page's hiccup | `@ctx` as it is (exactly what the agent gets when it evaluates `@ctx` at that point: same keys, joins, shape and printer; never a filtered copy), then the hiccup, both functions of the first call's basis; later points the hiccup's diff only | nothing; about 6k tokens once, cached |
| a join's shape | not said anywhere | the pattern's description and `(my.db/pulled @ctx)` say it: one entity, or a map of members by id | turns spent finding it (a kept run: turns 12-15, 31-32) |
| a referenced session | a copy keyed `example/<id>` loaded on main | a ref to an agent at a point, with how it is shown as data on the join; built with C | `my.root/load-example!`, the copy |

## The order it ships in

Each row is one tip, small enough to land alone, with who builds it, its proof and what the next live run should show.

| # | tip | builder | proof, red first | what the next live run should show |
|---|---|---|---|---|
| 1 | a cited schema drawn as what it is; the pattern's description and required parts | Data model | landed (batch 61) | no turn spent finding how a schema declares a render |
| 2 | the role named in a pattern; a join to a depth | landed, main b7c5f8f82b (`jolt/rolenames`). A read of a pattern costs what it did before: the step's cost bound is unchanged |
| 3 | the two boot writes and three pattern functions deleted; a check that no function writes `:seon.ctx/pattern` | Data model | the check, at zero | a join added to a role reaches root's session with no boot |
| 4 | descriptions on an eval's answer keys, a file's path, a task's proofs; a join's shape in the pattern's description | Data model | named tests; `(doc :seon.repl/answer)` answers each key | the system message can drop those sections |
| 5 | the system message cut to what is true for every agent | Context | each kept sentence checked against the code and a recorded request | the same task, old message against new |
| 6 | `@ctx` and the hiccup at the first point; the cite's pull shortened | the real `@ctx` at the first point landed (batch 69). The cite's pull is built and proved whole twice before the pins moved; its prove on the new pins waits for the machine (`jolt/citepull`): a cite pulls only what it names and its render reads the content from the db. On the demo agent its five cites go from 12,717 to 1,582 characters |
| 7 | a proof's four states; a red proof shows every failure and what it calls (its own source is not repeated: an owner's ruling of 2026-11-05 a test pins; showing it needs a new ruling) | Web, Context | one test a state (the physics audit's three high rows) | no repeated `(prove)` on a deferred or dead proof |
| 8 | move A | Web; Data model for the schema entries | the planted-world proof, the attribute half | a new attribute with a render appears with no code edit |
| 9 | move C, with the named sub-patterns | Context; Data model for the patterns | recorded requests byte for byte; the planted-world proof, the entity half | a new kind of member reaches the page and the request |
| 10 | move B | Web, Optimization; Data model for the view's schema | a REPL trial on root's page with its cost, then the planted proof | root's page with no hand-read state |

## Where each tip stands

Kept current by the Data model session; the newest landed state runs on the standing system, http://127.0.0.1:7777/. "Running to see" is that system showing a live agent's session with hiccup drawn as html, Markdown drawn, structured evals, proofs and diffs, `@ctx` and the page at the first point, and the short system message.

| # | tip | state (2026-11-11, after batch 80) |
|---|---|---|
| 1 | a cited schema drawn as what it is; the pattern's description and required parts | landed |
| 2 | the role named in a pattern; a join to a depth | landed, main b7c5f8f82b (`jolt/rolenames`) |
| 3 | the boot writes and pattern functions deleted, with the check | built and reworked after Context's review: the last level of a depth is the depth join's own `:below` option (`{[:j :below [:id]] 2}`), so adding a plain join always merges; red first and the equality with the old user pattern seen. Handed over on named runs (`jolt/rolesource`). The standing store is erased when it and the rename are on main (the owner's yes) |
| 4 | descriptions on the answer's keys, a file's path, a task's proofs | landed, main f56a85c416; the join-shape sentence not started |
| 5 | the short system message | landed, main 5f0bb3c8e7 (batch 69) |
| 6 | `@ctx` and the hiccup at the first point; the cite's pull shortened | the real `@ctx`, byte for byte, landed (batch 69). The cite's pull is handed over (`jolt/citepull`): a cite holds what it names, and its render reads the content from the db by the pattern its schema declares |
| 7 | a proof's four states; every failure and what it calls | landed, main f56a85c416 (`jolt/proofstates`) |
| 8 | move A, and with it text fields as Markdown by a `:seon.db/text` schema | not started. Its first commit is Web's: where the walk places an attribute, the render its schema declares draws its value (today only an entity is drawn by a schema lookup) |
| 9 | move C, with the named sub-patterns | not started; waits on 2 and 3 |
| 10 | move B | not started; a REPL trial with its cost comes first |
| - | a turn's fold names each step's intent | landed (`jolt/stepintents`) |
| - | a component's root carries `:render`, the full symbol, in place of a class | landed (`jolt/rendername`) |
| - | a render's output named by a `:seon.render/hiccup-value` schema, so a value that is hiccup is drawn as html | landed (`jolt/hiccupvalue`). The schema says the value's form only, never what a page may hold |
| - | a role's standing cites as data: the cites an agent starts with are read from its task first (`:seon.config.agent/cites`, which a task may hold) | landed (`jolt/rolecites`) |
| - | a stuck agent is stopped: a judgment on its own evals ends it with the outcome `:stuck`, read by whoever started it | not started (Context); what "near" compares is open: the functions an eval called include plumbing and fire on plain reading, so the calls' targets must be part of it |
| - | the turn budget shown once (a line in the turn part would be a diff every turn) | not started; Web's render of the agent's `max-turns` in the first page |
| - | a task whose plan names code its branch does not hold is flagged before it is released (a cite that resolves to nothing is already data: the cite's target is unresolved) | not started; a class of the problems report, Tests |
| - | a render may read the page as it stood at the previous stored point: its contract names `:seon.render/previous` and it is handed that page's hiccup, nil at the first point (the owner's ruling, 2026-11-11; never the tree of the walk in progress) | ruled and tried at a REPL on a real agent (one walk answers both pages; a render finds its own element by its id); lands with its first user, a render that says what changed since the last turn |
| - | the named values as schema entries (`:seon.render/page-edits`, `:seon.ctx/status`, `:seon.render/pulled`) and the ctx schema of a role derived from its pattern, which says for each join one entity or members by id | not started (Data model); after tip 3, for the batch after 63 |
| - | a task merges when green by data, not by its kind: no task on its branch needs it (read from `:seon.task/needs` and the branch, no new attribute) | landed, main 5e0c0b7fd8 (batch 72) |
| - | each state a proof is shown in carries its explanation as data: one enum, `:seon.ctx.status/proof-state`, its Markdown under `:seon.status/explain` per member, with a fifth computed state (not run: a red ended this run) | landed, main 5da7934e13 (batch 66) |
| - | equal values print the same bytes (Point's ruling): a map's keys in one order whatever its size or history; a join's members, keyed by id, are the case to check | not started (Context), after step 4 |
| - | what every render an agent reads owes its reader (the owner, 2026-11-11: "Don't display the functions in full ... Agents need to know that context exists and we need explanations and short instructions that the agent can use to pull in more context"): what the thing is in a line; the count of what is not shown; the form that brings more; a choice made from data the agent can change. A schema entry declares the key seon.render/more, pairs of words and a form with `id` standing for the entity, which the walk draws once for both readers; a render that leaves things out answers `[:more {:count n :of "functions"}]`; the law is `seon.render-test/a-part-says-what-it-left-out` over a new config key, a part's characters | approved by Point, not started (Context, on `jolt/citepull`; Web the person's side) |
| - | the namespace an agent stands in is a cite its task makes of its own namespace (a cite may already target a namespace; no new attribute). Its part shows the functions the task cites there, those they call and those the agent's branch changed, one line each, the rest as a count: 207 to 2,264 characters on eight real tasks, where every function's first line was 9,547 on `seon.render`. The dial is the cite's depth, said on the part | approved by Point, not started (Context, with the row above). An agent stays in its task's namespace on purpose (the owner's ruling) |
| - | an eval's answer says what happened to each name: the var whole (`#'ns/name`), what it is as one enum value from the recorder's account, its words under `:seon.status/explain`, and what became program before a later form failed | in build (Context, `jolt/diffwords`) |
| - | a fixture a test reads is a fact of the branch (`"test/seon/*.edn" :fixture` in `:seon.config.source/things`), read by `my.branch/file`, which answers any file the branch holds and a wiki page's text; nothing reads a checkout to decide a verdict (the owner: the workflow is online) | decided, Tests builds |
| - | what `@ctx` holds (measured on a real agent: 143,742 characters, 92.8% of it the content of its cites, 61% callers): the agent's own data and the identity of each thing it refers to. A render reads the content from the db by a pattern its schema declares. After the moves the same agent's is about 11,100 | the cite's part built (`jolt/citepull`); proofs, needs, examples and started agents follow, one tip, with the cost proof bounded by the printer's bound |
| - | `(describe v)`, `(describe v path)`, `(describe v path depth)` in `my.agent`: a vector of `{:path :holds}` maps with the schema and render of each part, its own schema the key my.agent/described, its instructions printed from that schema. The first point shows `(describe @ctx)` in place of the whole `@ctx` (the owner, on the two mocked forms: "oh I like yours better already") | ruled; Context builds and tries it on agents |
| - | every tool's answer says what it is, one way: its value carries the schema that names it and the printer shows it through that schema's `:seon.render/model`. Four of about 55 tools do so today; the law is `seon.main-test/every-tools-answer-says-what-it-is`. The default printer prints a plain value plainly (the owner: "(+ 1 1) ... I want the answer") | ruled; Context builds |
| - | the calls Seon makes for an agent at a turn's open are stored evals (existing attributes, written by `seon.turn`; `:seon.eval/by` the system's entity): the same data gives the same bytes after a wipe, a compaction or a change to a render's code | ruled; Context builds |
| - | the task is shown once: whole in `@ctx`, a line and what is derived from it on the model's page | ruled; Context builds |
| - | a refined plan is tried again from the start: two reconcile rules. The refine is done on the work's branch; its plan reaches main at the run's end by `seon.task/carry-tx` (no new attribute); the rerun starts fresh from main. A refine is known by its cite of the refine section and judged by the plan it leaves | ruled; Context builds. A merged task's rerun needs a start point as data: open |
| - | every attribute an entity schema names says what it is: about 130 given a `:description`, and a count in `seon.db-test` that may only fall | handed over (`jolt/descriptions`, on `jolt/citepull`). The count in a test was dropped as a second mechanism: the class that judges it is the entry seon.problems/a-schema-entry-that-says-nothing-is-a-problem |
| - | a pull holds what is the agent's own and what it may edit: a cited task is held with its plan, instructions, proofs and cause (the task content a run's end carries), so a task that curates another reads and writes that plan at the cite's path. A refine cites its work task and needs nothing (a need means finished before I start) | built (`jolt/ctxids`, on `jolt/citepull`); lands under Context's refine chain |
| - | a window says how many members its join has, as metadata on the join's value (`(-> @ctx :evals meta :seon.ctx/count)`), so what it left out is data and the value stays a map to sort and filter. An agent's own pull is never windowed: the count serves root's windows and a cited function's callers | built (`jolt/joincount`); lands with Web's page part |
| - | a task's judged reasons are in its pull under the done rule's own schema (`(-> @ctx :seon.agent/task :seon.task/reasons)`), no new attribute; the flattened strings stop being written when the page is off them | handed over (`jolt/taskreasons`), a rider of the erase |
| - | what makes the turns is read from the members: a history join whose members carry a turn number; every other history join is placed against their edges. No join is named in code and no schema is flagged | ruled; Web builds it on the relanding, with a law that every history join a pattern declares asks its link's transaction |
| - | a required join keeps the transaction its role asks of each member's link; a pattern that asks it without is refused | written and seen red then green; lands with the relanding (the erase first) |
| - | the task's kind is read at 20 sites for three questions, each with one answer in data: a merge step is a task that needs a task on its own branch; judged by the plan it leaves is the cite of one section, for a design and a refine both; who does a task is a ref, once a session is an agent | three entries filed as trials |
| - | the planted-world proof (a schema with both renders, joined in a pattern, appears with no code edit) | in build (`jolt/planted`, Web); tips 8 and 9 land with its two halves |

## Not decided

What "near" means for a stuck agent's repeats (the functions its evals called is the proposal; Context checks it on kept runs). Whether a view (move B) costs more at each draw than the components it replaces: not measured. A compaction's point is decided and not built: one attribute on the agent, written with the compaction, built with tip 6 (the page whole at that point is the same function as at the first). A model call's previous call is decided: read through the agent's calls by their number, no new ref. The calls Seon makes for the agent are stored evals (the table above). Each such call carries an intent and a message, read from the metadata of the function it calls (`:seon.eval/intent`, `:seon.model-call/reply`). Embeddings stay in the store (the owner); a render may be handed the previous stored point's page, never the walk in progress ([what a render is handed](render.md#what-a-render-is-handed)).
