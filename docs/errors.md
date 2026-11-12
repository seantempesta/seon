# Errors and problems

An error is data: one error map, recorded once per cause on main, and read with every other recorded problem through one report, so health is the absence of open problem facts.

Every failure is the one error map (`seon.contract/error`: `:seon.error/type`, `:seon.error/operation`, `:seon.error/message`, and its data), whether a contract refused a call, an edge caught the outside world ([failure points](failure-points.md)) or an entrant boundary caught a defect. The requester gets it as its answer, a task holds it as its error, and an observer records it. Nothing here files tasks or resolves errors; root and the owner mine them into tasks.

## Errors

A recorded error is one entity per signature on main, with its count, the agents it hit and its first example: `seon.error`.

The signature is the digest of `seon.error/cause` (type, operation, member, Malli problem paths and schemas, class, exit or HTTP status, and the message with digit runs normalised by `seon.error/digits->n`), so one cause is one signature however often it repeats. An observer builds an occurrence (`seon.error/occurrence`, class `:platform`, `:eval`, `:agent` or `:effect`) and folds it into its state (`seon.error/fold`, one hash). `seon.error/flush-tx` writes only the signatures that changed, one write in flight, so a storm of identical errors costs one update per write. `seon.error/record` reads one back.

The observers:
- root's api proc: Flow's error channel, an agent's death or stall, an agent's failed effect; `seon.system/errors` answers its fold.
- root code that asked an effect records its failure with its own fact of the outcome in one transaction (`seon.error/asked-tx`); the problem names that fact's attribute, so the next good outcome closes it. Callers: the reconciler's `:tests` (`:seon.task/deferred-results`) and `:git` (`:seon.task/commit`), `my.root/build!` (`:seon.system/generation`), `my.root/first-moves!` (`:seon.judge/digest`), `seon.vector/vectors-proc` (`:seon.vector/model`).
- an agent folds its own eval errors (`seon.error/note`) and sends them with its next commit; root records them as `:eval` (`seon.error/sent->occurrence`).

`seon.system/wiki-baseline`'s failed `:history` writes no problem: each section's `:seon.doc/baseline-unread` holds it, and health shows a derived `:stale-section`. The wiki load (`seon.system/wiki-tx`) never refuses: a link to no section is kept and shows as a dead citation ([health](health.md)).

### An error keeps data

An error that another process keeps past its moment is data, never a Throwable or a live object (an exception's ex-data holding live values once grew root's `*e` past 1 GB).

What an asker gets back, and what an agent's own eval, test or reply carries, is the error with its full data ([no cuts on agents](decisions.md)): `seon.contract/error`, `seon.agent/error-map`, `seon.server/respond` and `seon.proofs` cut nothing.

Where root keeps other processes' errors for its life, `seon.contract/kept` bounds the data: a small [plain](storage.md#plain-data) value (`seon.contract/plain?`) within `seon.print/budgets` `:part` is kept as it is; a larger string is the printer's cut (`seon.print/plain`, which says what it omitted); any other value is its print under that budget (`seon.print/code`). A db value, conn, atom, Throwable or lazy seq is always its print. `seon.contract/bounded` keeps every member of a map. The root-kept site is `seon.system/flow-error`; a new place root keeps another process's error calls it too. Proofs: `seon.contract-test/an-error-keeps-bytes-that-do-not-grow-with-its-value`, `an-agents-own-error-keeps-its-full-data`.

### An eval error's diagnostics

An exception from an evaluated form is the `:seon.agent/eval-error` map (`seon.agent/error-map`). Every `:jolt.error/` and `:clojure.error/` ex-data key stays a key (schema in `seon.problems.edn`); any other ex-data is `:data`. Jolt's "did you mean" suggestions are `:seon.error/fix`, and an unresolved symbol's message is worded as `jolt.nrepl/err->msg` words it, so `(prinltn 1)` reads `Unable to resolve symbol: prinltn in this context`, `fix: did you mean print, printf, println`.

`seon.repl/located` adds the position in the form's own text (1-based, as `(source v)` prints it): `:seon.error/at {:line :column}`, and for a runtime error `:seon.error/in`, the fn it was in. A runtime error reads `at line 3 of ns/f`, a compile error `at line 3:6`. A throw in an anonymous fn (`ns/var$fn`) is placed in the var around it. The render prints each fact once (`seon.render.ui/error-hidden`). Proofs in `seon.repl-test`: `an-eval-error-says-where-in-its-forms-own-text`, `an-eval-errors-data-is-printed-once`, `an-ordinary-exception-is-its-error-map`, `an-error-in-a-fn-a-core-fn-calls-is-in-the-fn-around-it`.

A macro's syntax error also carries `:seon.error/given`: the forms the failing call was given, each printed whole, as the reader grouped them (`seon.repl/given`), so a form one paren short shows what it swallowed.

Deliberately unused:
- `JOLT_DIAG`: read only by the CLI's uncaught handler; the data is already in ex-data.
- `JOLT_CHECK`: warnings go to stderr; type findings as eval warnings are task `seon.repl/type-findings-are-an-evals-warnings`.
- `JOLT_DEBUG`: builder traces, not an eval's concern.
- `jolt.nrepl` as a server: a second request path beside `seon.server`'s `:eval`; the error's one render is its map (`seon.render.ui/error-box`).
- `clj-kondo`: it does not know `seon.contract/defn`, and the compiler already reports unresolved symbols with suggestions.

## Nothing crashes and nothing hangs

Every wait ends, with its value or a typed error saying why it never will, and a failure comes back to whoever asked as an error value, never as a crash of the asker (owner, 2026-10-28: "I want our system to be robust and to have it not crash or hang ever."). A wait that does not end is a defect at the producer that owed the answer, which says why as a typed error; it is never fixed by a timeout or a watchdog ([clocks](clocks.md#if-nothing-has-changed-we-do-nothing)). "Never came" is itself a fact (blocked, stalled, dead), written by whoever saw it. The agent fixes the failure, or messages root when it needs another process. The edges where the outside world fails: [failure points](failure-points.md).

## Unknown is never a default

An error is never swallowed, an answer never ignored, and a missing value never replaced by a made-up one: unknown is not `{}`, `0`, `nil` or `:ok`, and a proc answers every request it took. A fallback hides the cause from whoever could fix it, so the failure is an error ([above](#errors)).

## Problems

Every recorded problem is read by `seon.problems/report`: main's db in, one report out, by class. `seon.problems/health` adds the classes the program's facts make true now (dead citations, unnamed sites, stale pins and sections, orphan pages, sections with no lead, uncited tasks).

Its classes are the recorded errors by signature (`seon.problems/counted-classes`), what a task or its agent holds (`seon.problems/ended-classes`: red assertions, cost verdicts, merge conflicts, refused answers, a death, an error that ended the loop, overrun, task conflicts, `:unrun`), each root cause with what it blocks ([root](root.md#root-causes-at-quiescence)), and a task marked failed while held ([tasks](tasks.md#a-refused-run-is-held-for-root)). Each problem carries its producer, owning namespace, evidence and reproducing proofs, and the tasks whose `:seon.task/evidence` answers it (`seon.problems/answers`). Each class is read through an index that holds only problems, so its cost follows the problems, not the program.

`my.root/problems` is `seon.problems/health` for root with the api's fold; root's context and the dashboard's problems banner read it ([render](render.md#health-is-what-flows)).

## Learn

An agent's overrun is charged to the producer of what it was shown (`seon.learn/waste`). At its end the agent folds its turns as its branch recorded them (```seon.learn/turns-of```: each model call's evals, their code, error, value and output, whole across a restart), and ```my.root/run-losses``` answers the same rows for any task's run: an error first seen is `:error`, charged to what answered it; a look-up (`grep`, `lines`, `search`, `source`, ...) that answered nothing and printed nothing is `:empty`, charged to that look-up; a form that looks for code instead of changing it is `:re-read` (charged to the render that already showed it) or `:missing`/`:search` (charged to the template); an error repeating an earlier signature is `:repeats-error`, charged to `seon.ctx.status/status`. `seon.learn/summary` counts by producer. It is stored as `:seon.agent/waste`, carried to `:seon.task/waste` by the judge, and reported as the `:waste` class.
