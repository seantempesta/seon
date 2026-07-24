---
type: issue
status: resolved
severity: blocker
tags: [issue, runtime, persistence, ipc]
---

# Eval terminal envelope bypassed wire admission

## Evidence

`seon.host.eval/eval-form!` projected ordinary SCI outcomes through
`wire-safe-value`, but `eval-batch-result` could replace or extend that value
with a terminal preflight, read, timeout, repair, or recording outcome before
persistence. The public envelope was only stripped of host-local values before
`record-eval-terminal!`; it was not admitted again.

An unsupported nested function therefore reached the database client's bare
Transit encoder. Transit 1.0.333 has no default handler for arbitrary Clojure
functions and reports `Not supported: class clojure.core$_STAR_`. The database
session then entered its encode-failure recovery path instead of returning a
flat execution error.

## Owner and acceptance

The JVM execution host owns the final public eval envelope. Route that envelope
through the existing `wire-safe-value` projection after extracting host-local
values and before persistence. Prove that a terminal response embedding a
function is a flat `:agent` error, round-trips over Transit, and that a second
invocation succeeds over the same execution session.

## Resolution

Commit `34f0373e8` applies `wire-safe-value` to the final public envelope before
terminal persistence. The focused same-session regression passed 1 test / 7
assertions: a nested function becomes a flat `:agent` error, Transit
round-trips the response, and a second invocation on the same execution
session returns 42. A later rerun was blocked before this behavior by an
unrelated in-flight `seon.host.context` arity mismatch; after that lane landed,
another rerun stopped during concurrent dependency preparation. The original
green behavior log is retained, and the final coherent-tree rerun again passes
1 test / 7 assertions.
