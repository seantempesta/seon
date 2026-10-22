# Errors and problems

An error is data: one error map, recorded once per cause on main, and read with every other recorded problem through one report, so health is the absence of open problem facts.

Every failure is the one error map (`seon.contract/error`: `:seon.error/type`, `:seon.error/operation`, `:seon.error/message`, and its data), whether a contract refused a call, an edge caught the outside world ([failure points](failure-points.md)) or an entrant boundary caught a defect. It flows to whoever can act on it: the requester gets it as its answer, an attempt holds it as its error, and an observer records it. Nothing here files tasks or resolves errors; root and the owner mine them into tasks.

## Errors

A recorded error is one entity per signature on main, with its count, the agents it hit and its first example: `seon.error`.

The signature is the digest of the error's cause (```seon.error/cause```: its type, operation, member, its Malli problems' paths and expected schemas, its class, an exit or HTTP status, and its message with digit runs normalised by `seon.error/digits->n`), so one cause is one signature however often it repeats. An observer builds an occurrence (`seon.error/occurrence`, of class `:platform`, `:eval`, `:agent` or `:effect`) and folds it into its own state (```seon.error/fold```), costing one hash. ```seon.error/flush-tx``` writes only the signatures that changed, and the observer keeps one such write in flight, so a storm of identical errors costs one update per write.

The observers: root's api proc (Flow's error channel, an agent's death or stall, an effect's error outcome; `seon.root/errors` answers its fold, with the true counts); an agent, which folds its own eval errors (`seon.error/note`) and sends them with its next commit, recorded at root as `:eval` (`seon.error/sent->occurrence`); and boot, when the wiki refuses to load (`seon.root/wiki-tx`). `seon.error/record` reads one back.

## Problems

Every problem Seon recorded is read by one function, ```seon.problems/report```: main's db in, one report out, by class.

Its classes are the recorded errors counted by signature (`seon.problems/counted-classes`) and what an attempt or task holds (`seon.problems/attempt-classes`: red assertions, cost verdicts, merge conflicts and refusals, a death, an error that ended the loop, overrun, task conflicts), plus a stuck task with what it blocks ([root](root.md#stuck-and-blocked)) and a task marked failed while it is held ([tasks](tasks.md#a-failed-task-is-held-for-root)). Each row carries what a task needs (producer, owning namespace, evidence, the proofs that reproduce it) and the tasks whose `:seon.task/evidence` already answers it (`seon.problems/answers`). Its cost follows the problems, never the program: each class is read through an index that holds only problem rows.

`my.root/problems` is the report for root, with the api's fold; root's context and the dashboard's problems banner read it rather than re-deriving problems from the live view ([render](render.md#health-is-what-flows)). `bin/check problems` provokes every class through the paths agents and root use and reads them back.

## Learn

An attempt's overrun is charged to the producer of what the agent was shown: ```seon.learn/waste```.

At an attempt's end the agent folds its turns: a form that looks for code instead of changing it (`source`, `doc`, `ns-publics`, ...) is charged to whoever should have shown that code: `:re-read` to the render that already showed it, `:missing` or `:search` to the template; an error repeating an earlier turn's signature is `:repeats-error`, charged to `seon.context/current-state`, which delivered it. `seon.learn/summary` counts it by producer. It is stored as `:seon.end/waste` on the branch, carried to `:seon.attempt/waste` by the judge, and reported as the `:waste` class. What a producer is charged is a context to improve ([references](references.md), [render](render.md)).
