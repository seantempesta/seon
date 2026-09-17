---
type: research
date: 2026-09-17
tags: [instrument, sci, error, contract, bounded-execution]
---

# A bound firing reports itself, and a violation keeps the value that broke it

Landing note for two bounded repairs in `src/seon/instrument.clj` and
`src/seon/error.clj`. Both are members of one class: a seam answering with
SOMETHING ELSE than what actually happened — a deadline reported as a contract
sentence, and a contract refusal that names a path with no value at it.

## Item 1 — `violation` re-raises the kernel's interrupt

### What it was

`seon.instrument/violation` wrapped its whole composition in
`(catch Throwable …)` and turned ANY throwable into `minimal-violation`'s
sentence:

```text
Wrong number of args (0) passed to: seon.db/as-of
```

The composition is not inert: `m/explain` re-runs the offended schema's own
predicates on the value that failed, so an armed evaluation's deadline can
close while the reporter is mid-word. The interrupt SCI raises is
`(ex-info msg {:sci.impl/interrupt …})`
(`reference-code/sci/src/sci/interrupt.cljc`), which carries no
`:seon.error/kind` — `seon.sci.kernel/failure-value` therefore classifies it as
the bound. Once `throwing-report` re-threw it as
`:seon.instrument/contract-violated`, the kernel kept THAT kind instead
(`src/seon/sci/kernel.clj:486`, "a throwable that already carries a refusal …
keeps its own `:seon.error/kind`"), and a 2000 ms bound firing around a 10 ms
refusal was read as an arity mistake. Measured in
[the arity parity note](sci-arity-message-parity-2026-09-17.md) (batch 119 vs
120) and recorded as the one open observation on
[the superseded deadline issue](../../../seon/issues/the-evaluation-deadline-latches-around-a-ten-millisecond-refusal.md).

### What it is now

`violation`'s catch re-raises `seon.sci.kernel/interrupted?` throwables
unchanged before it composes anything. AGENTS.md §2.3: a bound firing is
itself a bug report naming what never arrived — never a silent retry, and
never a different report.

`seon.instrument` now requires `seon.sci.kernel` for that one predicate rather
than growing a second copy of it; the predicate walks the cause chain over
`sci.impl.utils/interrupt-ex?` (`src/seon/sci/kernel.clj:31`). No cycle: the
kernel requires `seon.db`, `seon.error`, `seon.schema`, `seon.sci.admit`,
`seon.error.refusal` and `seon.call-preparation`, none of which requires
`seon.instrument`. Confirmed live by the worker's own arming line,
`registered= 1161 instrumented= 1161`.

### The regression

`seon.instrument-test/a-deadline-firing-inside-an-instrumented-call-is-reported-as-the-bound`,
under the canonical `with-database` fixture with a real armed function
(`deadline-probe`, armed by `instrument/apply!` at `:panic`):

- a real kernel arm (`seon.sci.kernel/arm` on a real `sci/init` context
  carrying the process guard, `time-limit-ms 1`) closes its own latch;
- the contract predicate enters the armed `:interrupt-fn` on the reporter's
  re-check, exactly where SCI enters it at an interpreted fn body;
- the test asserts the reporter re-checked (`> 1` predicate calls), that the
  escaping throwable is `kernel/interrupted?`, that its kind is NOT
  `:seon.instrument/contract-violated`, and that `kernel/failure-value`
  classifies it `:seon.sci.eval/time-limit`.

## Item 2 — a contract-violation fault keeps the value that broke it

### What it was

A `:seon.instrument/contract-violated` fault recorded `:seon.instrument/args`
from `:seon.error/diagnostic-offending`, which is what the ARM checked: the
caller's whole argument vector, or the whole returned value. For any function
whose argument or result carries an SCI context that is megabytes, so the
field became the over-bound marker and the fault named the violation's PATH
with NO copy of the value at it — no `:seon.instrument/actual` datom, and the
attribute was not installed. Live fault `7710efbc…` on `default`
(`:seon.error/data-size 2219064`, `:seon.instrument/args
"#:seon.sci.admit{:reason :over-bound, :bytes 3996}"`), recorded as the second
finding of
[the over-bound evaluation contract note](over-bound-evaluation-contract-2026-09-17.md).
That fix was reached by reading four constructions and reproducing the
diagnostic, not by reading the value.

### What it is now

`seon.error/offending-entry` reads the FIRST problem's leaf —
`[:seon.error/problems 0 :seon.error/offending]`, the value at the violation's
own path — and the fact carries it as `:seon.instrument/actual`, bounded by the
existing `bounded-text` / `bounded-admission` mechanism under the fault
family's declared `:seon.config.error/max-evidence-bytes`, plus
`:seon.instrument/actual-size`, that value's OWN size measured under the same
admission (the marker's `:seon.sci.admit/bytes` when it does not fit). The
whole request is never printed to produce either.

Three details the code states and the tests hold:

- it reads the SOURCE, never its bounded projection: the leaf sits four levels
  down and a depth cap would drop exactly the evidence being recorded;
- it is a map ENTRY, not a value, so an offending `nil` or `false` is still
  recorded and absence means the violation carried no problems — two answers,
  not one;
- `:seon.instrument/args` is unchanged. Both are kept: the arm's value says
  what was checked, `actual` says what broke.

`projected-instrument-data` is now `contract-violation-data`, used for both the
projected fields and the source-side leaf, so one function answers "is this a
contract violation, and what is its data" for every source shape.

### Schema

New attributes, declared once and installed through the entity schemas that
already carry `:seon.instrument/args`:

- `resources/seon/schemas/seon.instrument.edn`: `:actual :string`,
  `:actual-size [:int {:min 0}]`;
- `resources/seon/schemas/seon.error.edn` `:seon.error/fact` and
  `resources/seon/schemas/seon.error.occurrence.edn` `:seon.error.occurrence/occurrence`:
  both keys, optional;
- `seon.error`'s two occurrence `select-keys` lists carry them, so the
  occurrence writer and the merge-back read them like every other field.

RESET NEEDED for `default`: these are new installed attributes and an existing
cluster predates them. Database data is disposable by ruling; the orchestrator
batches the refork.

### The regression

`seon.error-test/a-contract-violations-fault-keeps-the-value-that-broke-it`,
under the canonical fixture with a real armed function
(`wide-return-narrow-violation`, `instrument/apply!` at `:panic`), reproduces
the live fault's exact shape: a violated OUTPUT contract whose returned value
is wide and whose offending leaf is a two-element vector. It asserts that
`:seon.instrument/actual` carries the leaf and NOT the wide payload, that
`:seon.instrument/actual-size` is recorded and far below
`:seon.error/data-size`, and that the whole fact stays within the declared
evidence bound.

Exact bytes from that reproduction, measured 2026-09-17 under
`bin/test-fast` with contracts armed:

| field | value |
|---|---|
| `:seon.instrument/actual` | `[:seon.ns/name user]` — 20 UTF-8 bytes |
| `:seon.instrument/actual-size` | 162 — admitted-projection bytes, the `:seon.error/data-size` measure |
| `:seon.instrument/args` | absent |

Two things that measurement settles. The stored TEXT and the recorded SIZE are
different numbers by construction: the text is the printer's emission of the
bounded print node, the size is the admitted projection's own bytes, which is
exactly what `:seon.error/data-size` counts — so the two are comparable, and
the attribute's docstring says which measure it is. And `:seon.instrument/args`
is ABSENT here: for a raw `Throwable` source the PROJECTED instrument data
yields no `:seon.error/data` at all, so before this change the fault carried
the violation's kind and function and NOTHING about the value. That is a
stronger statement of the finding than the live fault's over-bound marker
was.

## Verification boundary

`bin/test-fast seon.instrument-test seon.error-test seon.sci.eval-test`
(working tree, contracts armed: `registered= 1161 instrumented= 1161`):

**140 tests, 725 assertions, 1 failure, 0 errors.** Both new regressions pass.

The one failure is
`seon.sci.eval-test/schema-and-contract-declarations-have-bounded-allocation`
— 224,191,456 bytes against the declared 67,108,864 — which is
[an already-open issue](../../../seon/issues/guarded-schema-declarations-still-exceed-the-allocation-regression-bound.md)
and fails identically without this change (223,466,344 and 223,408,128 bytes in
two earlier lanes' notes on the same bound). It is not this slice's.

The run immediately before, with the same source and the two regressions in
their first form, was **140 tests, 726 assertions, 5 failures, 1 error** — four
of those were the new tests naming what the code did not yet do, which is how
each was falsified before it was made green.

These are lane iteration results, not the isolated gate's proof. The cold proof
still owed to the orchestrator is

```
bin/test --paths src/seon/instrument.clj src/seon/error.clj \
  resources/seon/schemas/seon.instrument.edn resources/seon/schemas/seon.error.edn \
  resources/seon/schemas/seon.error.occurrence.edn \
  test/seon/instrument_test.clj test/seon/error_test.clj \
  -- seon.instrument-test seon.error-test seon.sci.eval-test
```

plus `bin/test --platform`.

Boundaries met, not crossed:

- `bin/test-fast --paths <my files>` refused with `Incomplete --paths overlay;
  add changed caller files: src/seon/fn.clj src/seon/sci/eval.clj
  src/seon/test.clj src/seon/test/runner.clj test/seon/test_runner_test.clj` —
  every one of those is a concurrently edited stage-2 file this lane does not
  own. Iteration therefore used the working tree (`bin/test-fast
  <namespaces>`), which includes stage 2's uncommitted edits; a red inside
  those files is theirs, not this slice's.
- `default` (pid 94566) was never started, stopped, reset, reforked or
  adopted. The source edits were written from the shell, so the edit hook did
  not publish them; `bin/seon init --dev default --changed …` is adoption and
  belongs to the orchestrator.
