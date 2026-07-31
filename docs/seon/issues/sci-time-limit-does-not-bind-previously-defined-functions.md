---
type: issue
status: open
severity: blocker
tags: [issue, sci, eval, runtime]
---

# The sci time limit does not bind previously defined functions

## Problem

Sci closes `:interrupt-fn` over a function object at CREATION time, not
at call time: `interrupt-fn# (:interrupt-fn ~'ctx)` is bound in a `let`
outside the returned `fn`
(`reference-code/sci/src/sci/impl/fns.cljc:40`, `:64`, `:152`), and the
per-entrance check `(when-not (nil? interrupt-fn#) (interrupt-fn#))`
(`:52`, `:77`, `:166`) reads that closed-over value.

`seon.sci.eval/evaluate` installs a fresh interrupt-fn by
`(assoc ctx :interrupt-fn …)` (`src/seon/sci/eval.clj:763-764`), which
therefore guards only functions created DURING that evaluation. Two live
paths escape the limit entirely:

1. **The ordinary run fold.** One ctx is forked per run
   (`src/seon/cluster/loop.cljc:967`) and every form evaluates on it by
   contract (`src/seon/sci/eval.clj:72-81`). Form 1's `(defn f …)` closes
   over form 1's interrupt-fn, whose scheduled task `stop!` cancelled in
   `finally` (`src/seon/sci/eval.clj:272`, `:935`), so its `reached?`
   volatile can never become true again. Form 2 calling `(f)` spins with
   no limit.
2. **Acquisition.** `sci.eval/acquire!` runs on a bare
   `(sci/fork (sci.eval/base))` with no `:interrupt-fn`
   (`src/seon/cluster/loop.cljc:967-970`) and re-evaluates every
   agent-authored `defn`/`deftest` source through `sci/eval-form`
   (`src/seon/sci/eval.clj:513`, `:527`, `:548`). Every agent function
   carried over from a previous run is re-created permanently unguarded.
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

Full analysis, measurements, and ranked options:
`docs/prds/sci-execution-runtime/research/sci-door-ctx-sharing-2026-07-31.md`
§0, §1, §5 (R1, R2).

## Owner

`src/seon/sci/eval.clj` (`arm`, `evaluate`, `acquire!`,
`install-program-row!`) and `src/seon/cluster/loop.cljc:960-972`.

## Acceptance criteria

- A `deftest` under `test/seon/sci/` in which a function defined by one
  evaluation and called by a later evaluation on the same ctx returns
  `:seon.cluster.eval/interrupted-at` within ~1× the configured
  `:seon.config.eval/time-limit-ms`, instead of hanging.
- A test in which an agent function committed in a previous run, whose
  body spins, is cut by the limit after `acquire!` reinstalls it.
- A test in which a spinning `defn` attr-map causes `acquire!` to return
  a flat `:seon.error` value within the limit rather than wedging.
- The fix strengthens the ONE existing mechanism (`arm` +
  `:interrupt-fn`); no second limit, no per-form re-acquisition (that
  would reintroduce the deleted "form 2 cannot see form 1's defs"
  defect).
