---
type: research
date: 2026-09-17
tags: [sci, evaluation, contract, instrument, error]
---

# The evaluation named its failure with the value's own shape (fault 7710efbc…)

Landing note for the fix of
[the over-bound evaluation path issue](../../../seon/issues/the-over-bound-evaluation-path-returns-a-lookup-ref-where-its-contract-promises-a-string.md).

## The stated cause is refuted by the recorded evidence

The issue and the assignment both named the admission-refusal arm of
`seon.sci.eval/evaluate` — "reason `:over-bound`" — as the producer. The fault
entity says otherwise.

Read read-only from `default` (entity `49680`, occurrence `49681`):

- `:seon.error/kind :seon.instrument/contract-violated`
- `:seon.instrument/fn "seon.sci.eval/evaluate"`, `:seon.instrument/arm "output"`
- `:seon.instrument/expected ":seon.sci.eval/evaluation"`
- message: `seon.sci.eval/evaluate refused return value at
  [:seon.cluster.eval/error]: expected a string, got a lookup-ref vector. Fix:
  Pass a string id at [:seon.cluster.eval/error]. Contract:
  :seon.sci.eval/evaluation.`
- `:seon.instrument/args "#:seon.sci.admit{:reason :over-bound, :bytes 3996}"`
- `:seon.error/data-size 2219064`, `:seon.error/capped? true`
- `:seon.error/process "66052-1789618917102"`, first/last at
  `2026-09-17T04:22:54Z`, count 1.

`:seon.sci.admit/reason :over-bound` here is the **bounded-evidence placeholder
for the recorded arguments**, not an admission outcome of the evaluation.
`evaluate`'s single argument is its request map, which carries the SCI context;
its projection does not fit the fault's evidence bound, so
`seon.sci.admit` substitutes the marker (`src/seon/sci/admit.clj:737-738`). The
evaluation itself was never refused for size. No admission-refusal arm builds
`:seon.cluster.eval/error` at all: the only four constructions of that key in
`src/seon/sci/eval.clj` are `shown-result`, `success-evaluation` (copying
`shown-result`'s), `failed-evaluation` and `unrun-evaluation`.

## The actual root cause

`:seon.cluster.eval/error` is declared `:string`
(`resources/seon/schemas/seon.cluster.eval.edn:3`:
`[:and {:seon.wake/context-inert true} :string]`). Before this change
`shown-result` read it straight off the evaluated value:

```clojure
(:seon.error/kind value)
(assoc :seon.cluster.eval/error
       (or (:seon.error/message value) (str (:seon.error/kind value))))
```

That value is **arbitrary**: it is whatever the agent's form returned. Any form
returning a map that carries `:seon.error/kind` and a non-string
`:seon.error/message` — a two-element vector whose first element is a keyword
reads to the instrument's diagnostic as a lookup-ref vector
(`src/seon/instrument.clj:273-293`) — made `evaluate` fail its own output
contract. `failed-evaluation` and `unrun-evaluation` read the same key the same
way.

A pre-read of a foreign value was handed to the contract that promises the
string. The text is now DERIVED at the one seam that declares it
(`seon.sci.eval/failure-text`), and the three arms call it. A non-string message
is printed rather than dropped: `:seon.sci.admit/value` still retains the value
itself, and `:seon.eval/shown` still holds the rendered text.

## Reproduction, byte-exact

`seon.sci.eval-test/a-returned-values-non-string-error-message-is-still-the-declared-string`
(canonical `test-support/with-database` fixture, `test-support/fork-cluster-ctx`
real cluster SCI context, armed contracts) evaluates

```clojure
{:seon.error/kind :probe/refused :seon.error/message [:seon.ns/name (quote user)]}
```

Before the fix it raised, from `instrument.clj:446`, the message recorded on
`default` character for character:

```
seon.sci.eval/evaluate refused return value at [:seon.cluster.eval/error]:
expected a string, got a lookup-ref vector. Fix: Pass a string id at
[:seon.cluster.eval/error]. Contract: :seon.sci.eval/evaluation.
```

After the fix the evaluation carries a string and satisfies
`:seon.sci.eval/evaluation` under `seon.schema/explain-candidate-value`.

## The schema is not the one that is wrong

The other three arms already supply a string (`shown-result`'s
`(str (:seon.error/kind value))` fallback; `kernel/failure-value` explicitly
replaces a non-string `:seon.error/message` on its classified branch,
`src/seon/sci/kernel.clj:494-495`). Nothing in the codebase stores a reference
in that key: `seon.turn`, `seon.problems`, `seon.repl` and
`seon.render.transcript` all read it as text. The contract was not widened and
no `[:or …]` was added.

## Second finding — the 2,219,064 bytes were measured, not carried

The issue reads `:seon.error/data-size` as evidence the fault carried 2.2 MB.
It did not. `seon.error` records "the SOURCE's size, not the substitute's"
(`src/seon/error.clj:557-566`): `data-size` is `(utf8-size full-edn)` of the
full meaningful projection, while what is stored is the capped `data-edn` — here
the placeholder `#:seon.print{...:reason :over-bound, :bytes 3955}`, a few
hundred bytes. So the durable fact is small.

What is real, and is a DIFFERENT seam from the refusal arm fixed here:

1. The full 2.2 MB EDN projection is printed on every such fault purely to
   measure it. For `seon.sci.eval/evaluate` that projection is dominated by the
   SCI context inside the request map, so it will recur for every future fault
   on this function.
2. Because the arguments always exceed the bound, the fault names the offending
   PATH but retains no usable argument evidence, and the occurrence carries no
   copy of the offending return value at all (no `:seon.instrument/actual`
   datom; `:seon.instrument/path` is not an installed attribute either). The
   offending value could not be recovered from the database — this fix was
   reached by reading the four constructions and reproducing the diagnostic,
   not by reading the value.

Both are recorded as a second finding on the issue; neither is the seam of this
fix.

## Verification boundary

- `bin/test-fast --paths src/seon/sci/eval.clj test/seon/sci/eval_test.clj --
  seon.sci.eval-test`: **73 tests, 381 assertions, 1 failure, 0 errors**. The one
  failure is `schema-and-contract-declarations-have-bounded-allocation`
  (223,408,128 bytes against the 67,108,864 bound), which is
  [an already-open issue](../../../seon/issues/guarded-schema-declarations-still-exceed-the-allocation-regression-bound.md)
  and failed identically on the same snapshot before this change
  (223,519,720 bytes), so it is not this slice's.
- `bin/test-fast --paths … -- seon.sci.eval-instrumentation-test
  seon.sci.shown-text-test`: 2 tests, 15 assertions, 0 failures, 0 errors.
- These are iteration results. The cold proof still owed to the orchestrator is
  `bin/test --paths src/seon/sci/eval.clj test/seon/sci/eval_test.clj --
  seon.sci.eval-test` plus `bin/test --platform`.
- `default` was read read-only (three pulls) and was never stopped, reset,
  reforked or adopted. The live fault predates this change; it is not re-observed
  here.
