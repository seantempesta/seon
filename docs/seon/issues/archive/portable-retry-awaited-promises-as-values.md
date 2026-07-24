---
type: issue
status: resolved
severity: blocker
tags: [issue, runtime]
---

# Portable retry awaited Promises as values

The portable retry executor defined a CLJ identity macro named `await`.
Because that same-namespace macro was also resolved while compiling CLJS,
both async leaf calls returned Promises without awaiting them. Retry
predicates therefore inspected Promise objects and every retryable operation
stopped after its first attempt.

Resolved on 2026-07-23 by making the two platform edges explicit:
the JVM invokes the synchronous thunk and sleep directly, while CLJS uses its
native async `await` transform. The CLJS sleep Promise now explicitly resolves
`true`, matching the interruptible boolean contract instead of returning nil
after a successful timer. The retry and DiffusionGemma regressions plus the
complete CLJS checkpoint own the recurring proof.
