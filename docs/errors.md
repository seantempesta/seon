# Errors and problems

An error is data: one error map, recorded once per cause on main, and read with every other recorded problem through one report, so health is the absence of open problem facts.

Every failure is the one error map (`seon.contract/error`: `:seon.error/type`, `:seon.error/operation`, `:seon.error/message`, and its data), whether a contract refused a call, an edge caught the outside world ([failure points](failure-points.md)) or an entrant boundary caught a defect. It flows to whoever can act on it: the requester gets it as its answer, an attempt holds it as its error, and an observer records it. Nothing here files tasks or resolves errors; root and the owner mine them into tasks.

## Errors

A recorded error is one entity per signature on main, with its count, the agents it hit and its first example: `seon.error`.

The signature is the digest of the error's cause (```seon.error/cause```: its type, operation, member, its Malli problems' paths and expected schemas, its class, an exit or HTTP status, and its message with digit runs normalised by `seon.error/digits->n`), so one cause is one signature however often it repeats. An observer builds an occurrence (`seon.error/occurrence`, of class `:platform`, `:eval`, `:agent` or `:effect`) and folds it into its own state (```seon.error/fold```), costing one hash. ```seon.error/flush-tx``` writes only the signatures that changed, and the observer keeps one such write in flight, so a storm of identical errors costs one update per write.

The observers: root's api proc (Flow's error channel, an agent's death or stall, an effect's error outcome; `seon.root/errors` answers its fold, with the true counts); an agent, which folds its own eval errors (`seon.error/note`) and sends them with its next commit, recorded at root as `:eval` (`seon.error/sent->occurrence`); and the source watcher (`my.root/watch!`), whose loader error or failure to start is written as a `:platform` error by `seon.root/platform-tx`. The wiki written in boot's fact (`seon.root/wiki-tx`) never refuses the load: a link to no section is kept and shows as a dead citation ([health](health.md)). `seon.error/record` reads one back.

## Problems

Every problem Seon recorded is read by one function, ```seon.problems/report```: main's db in, one report out, by class; ```seon.problems/health``` is that report with the classes the program's facts make true now (dead citations, unnamed sites, stale pins and sections, orphan pages) added.

Its classes are the recorded errors counted by signature (`seon.problems/counted-classes`) and what an attempt or task holds (`seon.problems/attempt-classes`: red assertions, cost verdicts, merge conflicts and refused answers, a death, an error that ended the loop, overrun, task conflicts, and `:unrun`: a merge that left its process tests unrun), plus each root cause with what it blocks ([root](root.md#root-causes-at-quiescence)) and a task marked failed while it is held ([tasks](tasks.md#a-refused-attempt-is-held-for-root)). Each row carries what a task needs (producer, owning namespace, evidence, the proofs that reproduce it) and the tasks whose `:seon.task/evidence` already answers it (`seon.problems/answers`). Its cost follows the problems, never the program: each class is read through an index that holds only problem rows.

`my.root/problems` is `seon.problems/health` for root, with the api's fold; root's context and the dashboard's problems banner read it rather than re-deriving problems from the live view ([render](render.md#health-is-what-flows)). `bin/check problems` provokes every class through the paths agents and root use and reads them back.

## Jolt's own diagnostics

An eval's compile error carries what Jolt's analyzer knows: `:jolt.error/{line,column,symbol,suggestions,kind}` in its ex-data and "(did you mean a, b?)" in its message, so a mistyped symbol shows its suggestion with no code of ours. Jolt's other diagnostic switches are deliberately not used:
- `JOLT_DIAG=edn` only changes the command line's uncaught-error report, and an eval catches every throwable before that report runs; the same data is already in the exception.
- `JOLT_CHECK` prints success-type findings to stderr, which an agent's reads see only after its death. The checker itself, `jolt.passes.types/check-form`, is public: showing its findings as an eval's data is task `seon.repl/type-findings-are-an-evals-warnings`.
- `JOLT_DEBUG` prints build and cache diagnostics (why an AOT cache missed): a builder's aid, not an eval's.
- Jolt's nREPL server would be a second request path beside `seon.server`, bypassing the recording, the tick budget and the contracts the one path gives ([server](server.md#ops), [processes](processes.md)); the REPL speaks nREPL's message model over the server instead, and `jolt.nrepl` is used as a library for its error message and frames.
- clj-kondo would flood on `seon.contract/defn`, which the compiler and `bin/check contracts` already check better.

## Learn

An attempt's overrun is charged to the producer of what the agent was shown: ```seon.learn/waste``` (stored as `:seon.attempt/waste`, reported as the `:waste` class).

At an attempt's end the agent folds its turns: a form that looks for code instead of changing it (`source`, `doc`, `ns-publics`, ...) is charged to whoever should have shown that code: `:re-read` to the render that already showed it, `:missing` or `:search` to the template; an error repeating an earlier turn's signature is `:repeats-error`, charged to `seon.context/current-state`, which delivered it. `seon.learn/summary` counts it by producer. It is stored as `:seon.end/waste` on the branch, carried to `:seon.attempt/waste` by the judge, and reported as the `:waste` class. What a producer is charged is a context to improve ([references](references.md), [render](render.md)).
