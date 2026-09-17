---
type: issue
status: superseded
severity: friction
created: 2026-09-17
tags: [sci, evaluation, instrumentation, gate, cold-only]
---

# The cold-only arity red is a pre-arming SCI copy, not a deadline

Superseded by the pre-arming SCI acquisition work
(`3170a0060` "Trace cold message contract refusal to pre-arming SCI
acquisition", `7e04a0bb1` "cold workers acquire SCI before arming — root
cause of cold-only contract reds"), which owns the repair in
`src/seon/sci/eval.clj`. This note records the measured mechanism and the
one observation that remains open.

## What this note originally claimed, and why it was wrong

`seon.sci.eval-test/an-instrumented-multi-arity-miss-reads-like-clojure`
failed cold in batches 119, 120 and 123. Batch 120's shown text named
`:seon.sci.kernel/time-limit`, and this note proposed a stale or inherited
arm. That hypothesis is REFUTED as the general cause: batch 123's failure
carries no deadline at all, and its `:seon.error/message` is

```text
Wrong number of args (0) passed to: seon.db/as-of
```

which is Clojure's own `ArityException` text for a NON-variadic function —
not `seon.instrument`'s lookalike, whose value carries `:seon.instrument/arity`
and `:seon.instrument/arglists` (both nil in the gate). The evaluation
therefore called an UNINSTRUMENTED `seon.db/as-of`, which is why the kind was
`:seon.sci.eval/evaluation-failed` and not a contract violation.

## The mechanism, measured

`sci/copy-var*` builds the SCI var with `(new-var nm @clojure-var new-m)` —
it dereferences the Var exactly ONCE
(`reference-code/sci/src/sci/core.cljc:137`). `seon.sci.eval` installs core
admission through it (`src/seon/sci/eval.clj:1248`, `:1266`). So an SCI
context is a one-time SNAPSHOT of a root that `seon.instrument/apply!` will
re-decide: the owner law's mirror, exactly.

Probe under `bin/test-fast` (canonical fixture, real `fork-cluster-ctx`,
armed contracts):

- `jvm-root-instrumented?` true; the fork's binding is `identical?` to the
  JVM root and its class is `clojure.lang.AFunction$1` (malli's wrapper);
  the evaluation refuses correctly.
- After `alter-var-root` re-roots the Var underneath that same context:
  `bound-after-follows-new-root? false`, `bound-after-is-stale-copy? true`.
  The context keeps calling the old value.

A context acquired before arming — or inside an unarmed window left by
`preserving-instrumentation-state` / `seon.instrument/restore!` — therefore
calls the original for the JVM's whole life. That is a deterministic
cold-worker property, which is why the red reproduces every gate and never
in the fast loop.

It also explains the rest of batch 123's cluster with one cause:
`seon.transact-feedback-test/bad-value-type` recorded a write that SUCCEEDED
where the supplied projection should have refused it, and
`seon.sci.documentation-test/a-contract-mistake-carries-the-same-documentation-as-doc`
saw the refusal named by the system-side owner rather than the agent-facing
function.

## The check that now names it

`an-instrumented-multi-arity-miss-reads-like-clojure` asserts, before it
evaluates, that the fork's binding is `identical?` to the armed root. Without
it the four downstream assertions read as a message drift and cost a lane its
whole first half.

## Still open, separately

Batch 120's shown text really did name `:seon.sci.kernel/time-limit` around a
path measured at 0–2 ms direct, 8–12 ms through SCI, with
`:seon.eval/fn-entries 0, :seon.eval/duration-ms 5` against a 2000 ms bound.
Whether that was a second symptom of the same uninstrumented call or a
genuinely separate deadline event is unproven. Independently of it,
`seon.instrument/violation`'s `(catch Throwable …)` will convert a deadline
interrupt into `minimal-violation`'s contract-violation sentence; it should
re-raise `seon.sci.kernel/interrupted?` throwables so a bound firing is
always reported as itself.
