---
type: issue
status: resolved
severity: blocker
tags: [issue, runtime, agent]
---

# Graduated corpus source compiles with host eval, escaping the guarded door

## Problem

Graduated corpus source is compiled with host `clojure.core/eval` and
installed behind an SCI var — its body has native JVM reach and is
invisible to interpreter-step accounting.

## Evidence

The 2026-07-23 containment audit established the escape at Critical #1 and
High #3. The retired implementation compiled before its differential gate
completed and rebuilt matching `:graduated` rows natively without rerunning
admission.

## Owner

R48 selects proven-pure compilation: native compilation may reopen only after
P4/R33 proves the exact transitive call graph pure, capability-free, and
door-equivalent. Differential testing remains a sanity check, not admission.

## Resolution

Commit `3bb7c2d39` deletes the host-eval compilation path. `graduate!` now
returns a flat core error naming R48 and P4, performs no compilation or tier
transaction, and never silently reports success. `effective-tier` derives
`:nursery` for every row, so legacy matching `:graduated` facts rebuild as
interpreted corpus functions.

Focused proof covers a green historical trust gate followed by refusal, and a
legacy `:graduated` row rebuilt through `rebuild!` with zero graduated roots,
one nursery root, and a successful interpreted invocation.

## Acceptance

- The tests-pass gate cannot reach host eval: satisfied.
- Refusal is a flat R48/P4 error and does not transact: satisfied.
- Previously graduated rows execute interpreted: satisfied.
- Reopen only when P4/R33 admission exists: intentionally pending outside this
  resolved escape.
