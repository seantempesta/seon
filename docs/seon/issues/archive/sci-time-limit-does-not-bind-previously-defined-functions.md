---
type: issue
status: resolved
severity: blocker
tags: [issue, sci, eval, runtime]
---

# Previously defined functions retain a stale disarmed time limit

## Problem

Sci closes `:interrupt-fn` over a function object at CREATION time:
`interrupt-fn# (:interrupt-fn ~'ctx)` is bound in a `let` outside the returned
`fn`
(`reference-code/sci/src/sci/impl/fns.cljc:40`, `:64`, `:152`), and the
per-entrance check `(when-not (nil? interrupt-fn#) (interrupt-fn#))`
(`:52`, `:77`, `:166`) faithfully calls that closed-over hook.

`seon.sci.eval/evaluate` installs a fresh interrupt-fn by
`(assoc ctx :interrupt-fn …)` (`src/seon/sci/eval.clj:763-764`), which
therefore arms only functions created DURING that evaluation. Its `finally`
then calls `stop!`, which cancels the scheduled deadline task
(`src/seon/sci/eval.clj:272`, `:935`). Functions created in that evaluation
retain a hook that sci still calls on every entrance, but its flag can never
flip again: the guard is stale and affirmatively disarmed. Two live paths
therefore escape the effective limit:

1. **The ordinary run fold.** One ctx is forked per run
   (`src/seon/cluster/loop.cljc:967`) and every form evaluates on it by
   contract (`src/seon/sci/eval.clj:72-81`). Form 1's `(defn f …)` closes
   over form 1's interrupt-fn, whose scheduled task `stop!` cancelled in
   `finally` (`src/seon/sci/eval.clj:272`, `:935`), so its `reached?`
   volatile can never become true again. Form 2 calling `(f)` spins while
   repeatedly consulting that stale, permanently disarmed guard.
2. **Acquisition.** `sci.eval/acquire!` runs on a bare
   `(sci/fork (sci.eval/base))` with no `:interrupt-fn`
   (`src/seon/cluster/loop.cljc:967-970`) and re-evaluates every
   agent-authored `defn`/`deftest` source through `sci/eval-form`
   (`src/seon/sci/eval.clj:513`, `:527`, `:548`). Every agent function
   carried over from a previous run closes over `nil` before any evaluation
   installs a guard.
   Acquisition also evaluates `def` metadata maps
   (`reference-code/sci/src/sci/impl/analyzer.cljc:830-838` →
   `reference-code/sci/src/sci/impl/evaluator.cljc:28`), so agent code in
   a `defn` attr-map executes outside any armed boundary.

The only thing that fires is `submit-evaluation!!`'s `(* 2 limit)`
submission deadline (`src/seon/cluster/loop.cljc:304-319`), which marks
the submission `::wedged?` (`src/seon/flow.clj:509-518`) but does not
stop the thread. The spinning work permanently consumes one slot of the
bounded compute launcher.

This blocks the ruled design that agent-authored RENDERER functions
execute through the one guarded door: a renderer is defined once and
invoked on every re-render, which is exactly the escaping shape.

## Evidence

`tmp/sci-stale-interrupt-fn-probe.clj`, `clojure -M:dev`, 500 ms limit:

```
1 inline (loop [] (recur))          => interrupted, outcome :time
2a (defn spin [] (loop [] (recur))) => #'user/spin
2b (spin) in a LATER evaluate       => HUNG (no limit fired in 5 s)
3  fn created on an unarmed ctx,
   then called through evaluate     => HUNG (no limit fired in 5 s)
```

The independent ground-truth audit reproduced both symptoms and proved that
SCI's existing entrance hook is correct. A stable thread-scoped guard installed
on the run ctx before acquisition closes both paths with no fork change:
`docs/prds/sci-execution-runtime/research/sci-interrupt-ground-truth-2026-07-31.md`
§1-§5 and `tmp/sci_interrupt_fix_probe.clj` /
`tmp/sci_interrupt_threadlocal_probe.clj`. The earlier recommendation to add
entrance-time indirection inside the maintained SCI fork is retracted.

## Owner

`src/seon/sci/eval.clj` (`arm`, guarded ctx construction, `evaluate`) and
`src/seon/cluster/loop.cljc:960-972`.

## Acceptance criteria

- A `deftest` under `test/seon/sci/` in which a function defined by one
  evaluation and called by a later evaluation on the same ctx returns
  `:seon.cluster.eval/interrupted-at` within ~1× the configured
  `:seon.config.eval/time-limit-ms`, instead of hanging.
- A test in which an agent function committed in a previous run, whose
  body spins, is cut by the limit after `acquire!` reinstalls it.
- Concurrent evaluations of the same ctx's functions on different threads
  carry independent arming state, and disarming one evaluation leaves no stale
  flag for a later evaluation.
- The fix strengthens the ONE existing mechanism with a stable per-run,
  thread-scoped guard installed before `acquire!`; no SCI fork change, second
  limit, or per-form re-acquisition.

## Resolution

Resolved by `7ed006f18`. `seon.sci.eval/fork` installs one stable interrupt
hook before `acquire!`; `arm` attaches evaluation state to that guard only for
the executing thread, and `stop!` cancels the deadline, clears its flag, and
removes the thread state. Previously defined and acquired functions therefore
consult the current evaluation's deadline without sharing arming state across
concurrent threads. `reference-code/sci` was unchanged.

Recurring proof:

- `seon.sci.eval-test`: 14 tests / 53 assertions / 0 failures / 0 errors,
  including cross-eval definition, database-backed acquisition, same-ctx
  two-thread isolation, and exact disarm.
- Complete SCI owner selection: 34 tests / 233 assertions / 0 failures / 0
  errors.
- `seon.cluster.turn-test`: 40 tests / 239 assertions / 0 failures / 0 errors.

The original live falsifier, with only its raw fork replaced by the production
guarded fork, cut both the later cross-eval call and the acquire-shaped call at
approximately 505 ms. Both returned a flat time-limit value with
`:seon.eval/outcome :time` and `:seon.cluster.eval/interrupted-at`.

The same 50-million-call benchmark measured 8.8 ns/call before and 1.8-2.4
ns/call after warmup. A separate 10-million interpreted-entrance comparison
measured 1.5-2.9 ns of guarded-path overhead, preserving the nanosecond cost
class.
