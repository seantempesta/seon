---
type: issue
status: open
severity: friction
tags: [sci, evaluation, contract, admission, platform]
created: 2026-09-17
---

# The over-bound evaluation path returns a lookup ref where its contract promises a string

## Observed on a fresh default (pid 66052, 2026-09-17 04:22:54Z, ~1 minute after boot)

Fault entity `7710efbc…`, kind `:seon.instrument/contract-violated`, function
`seon.sci.eval/evaluate`, arm `:output`:

```
seon.sci.eval/evaluate refused return value at [:seon.cluster.eval/error]:
expected a string, got a lookup-ref vector.
Fix: Pass a string id at [:seon.cluster.eval/error].
Contract: :seon.sci.eval/evaluation.
```

Arguments at the fault: `#:seon.sci.admit{:reason :over-bound, :bytes 3996}`
— the evaluation was refused by admission for exceeding its byte bound, and
the refusal path built the evaluation value with `:seon.cluster.eval/error`
as a lookup ref (`[:seon.error/id …]`-shaped) instead of the string id the
schema declares. Every over-bound evaluation therefore fails its own output
contract, and the fault carries 2,219,064 bytes of evidence
(`:seon.error/data-size`), which is its own ugliness.

## Where

`src/seon/sci/eval.clj`, the admission-refusal arm of `evaluate` (search for
`:over-bound` and the construction of the evaluation map's
`:seon.cluster.eval/error`); the contract is `:seon.sci.eval/evaluation`
(`resources/seon/schemas/seon.sci.eval.edn`). Decide at the schema which
grammar the key carries — the error entity's string id, per the contract —
and make the refusal arm supply exactly that; do not widen the contract.

## Regression

[Superseded by the resolution below: the byte bound is not this defect's
trigger.] An evaluation whose returned value carries `:seon.error/kind` with a
non-string `:seon.error/message` returns a value that satisfies
`:seon.sci.eval/evaluation` (armed contract), with `:seon.cluster.eval/error`
the declared string, and records no contract-violation fault.

## Resolved — 2026-09-17

Fixed at the derivation, not at the schema. Landing note with the exact bytes:
[over-bound-evaluation-contract-2026-09-17](../../prds/steward-platform/research/over-bound-evaluation-contract-2026-09-17.md).

**This note's stated cause was wrong, and the fault says so.** There is no
admission-refusal arm building `:seon.cluster.eval/error`. The recorded
`#:seon.sci.admit{:reason :over-bound, :bytes 3996}` is the bounded-evidence
placeholder for the fault's recorded ARGUMENTS (`evaluate`'s request map
carries the SCI context and does not fit the evidence bound,
`src/seon/sci/admit.clj:737-738`), not an outcome of the evaluation.

The root cause is that `seon.sci.eval/shown-result` read
`:seon.error/message` straight off the value the agent's form RETURNED and
put it in a key declared `:string`. Any returned map carrying
`:seon.error/kind` with a non-string message — a two-element keyword-first
vector reads as a lookup ref to `src/seon/instrument.clj:273-293` — made
`evaluate` fail its own output contract. `failed-evaluation` and
`unrun-evaluation` read the same key the same way. All three now derive the
declared string through `seon.sci.eval/failure-text`; the value itself is
still retained in `:seon.sci.admit/value`. The contract was not widened.

Regression:
`seon.sci.eval-test/a-returned-values-non-string-error-message-is-still-the-declared-string`
reproduces the recorded diagnostic character for character on the canonical
fixture with a real cluster SCI context and armed contracts.

### Second finding — the 2,219,064 bytes were measured, not carried

`:seon.error/data-size` is deliberately "the SOURCE's size, not the
substitute's" (`src/seon/error.clj:557-566`). The stored `data-edn` is the
capped placeholder, a few hundred bytes. Two things are real and belong to a
DIFFERENT seam from the arm fixed here:

1. the 2.2 MB projection is printed in full on every such fault only to be
   measured, and for `evaluate` that projection is dominated by the SCI
   context, so it recurs for every future fault on this function;
2. the fault names the offending PATH but retains no usable argument evidence
   and no copy of the offending return value (no `:seon.instrument/actual`
   datom; `:seon.instrument/path` is not an installed attribute). The value
   could not be recovered from the database at all.

## Reopened for retirement semantics — 2026-09-21

The original string derivation remains fixed. The kind-retirement sweep found
a decision about its recognition precondition, not a recurrence of the original
contract failure. `shown-result` at `src/seon/sci/eval.clj:2460` recognizes the
regression's arbitrary returned map solely by its retired stamp. The regression
at `test/seon/sci/eval_test.clj:2454` supplies a vector as the error message;
`:seon.error/base` requires a string when the message is present. Even adding
all three base members and a complete kernel observation leaves zero valid
facets. Replacing the message with valid prose yields `:seon.sci.kernel/error`.

The committed [probe](../../prds/steward-platform/research/sci-program-returned-error-recognition-2026-09-21.clj)
prints these observations and asserts them against the complete packaged
declarations. This is unarmed schema/source evidence, not a canonical test run.
No default or foreign lane was operated.

PRD §6 applies: a consumer needs to distinguish more than the facet members
express. Rule 1.3 permits a transitional base check only for a callee that
declares `:seon.error/value`; an arbitrary evaluation return is not such a
callee. Choosing to recognize only valid facets changes this regression's
explicit behavior. Choosing to recognize malformed claims needs a stated
boundary policy. No general predicate or replacement marker was added.

The [lane note](../../prds/steward-platform/research/kind-sweep-sci-program-2026-09-21.md)
records three priced options. Acceptance after ruling: preserve the actual
returned object, assert the selected evaluation-error behavior and concrete
evidence, and keep the evaluation's string contract satisfied under the
canonical real-SCI regression. The historical resolution above remains valid.

The orchestrator settled recognition under D12: only complete declared facets
mark returned values as errors. The sci-program implementation replaces this
legacy regression with complete-facet and malformed-value cases, preserving
the actual returned objects and the evaluation string contract. Canonical
execution remains owed because the fast recording authority refuses snapshot
admission; this note stays open until that regression executes.
