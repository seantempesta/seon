---
type: issue
status: resolved
severity: friction
tags: [issue, sci, test, wave/contract-generator]
created: 2026-09-14
---

# The defn auto-check reports ITS OWN contract violation to the agent as if the agent's schema were wrong

## Observed (run 2, turn 14; the model's account in `research/explain_probe_turn40_2026_09_14.edn`)

After `(defn largest-customer {:malli/schema [:=> [:cat [:vector :example/order-row]] [:map …]]} …)`
the evaluation's `:out` read:

```text
No example test gates
my.agents.juniper/largest-customer; add one to teach intended behavior.
Auto-check skipped: seon.sci.kernel/invoke violated its contract
(invalid-input): invalid type at [[:seon.sci.eval/args]]
```

The function was
installed and worked; the checker's own call into `seon.sci.kernel/invoke`
failed its input contract. The model read this as "my defn did NOT persist"
and spent roughly five turns re-verifying.

## Wanted

- A core contract violation inside the checker is a core fault (fault
  committer, provenance), never text in the agent's `:out`.
- When the auto-check genuinely finds a violation of the AGENT's schema, the
  message names the argument, the schema, and the generated value.
- The "no example test gates …" sentence is fine but should not precede a
  checker failure as if both were about the agent's code.

## Core-functions repair — 2026-09-14

The exact run-2 definition and stored output were reproduced read-only from
default. Malli generates a `clojure.lang.LazySeq` for `:cat`; the checker passed
that sequence to a kernel contract requiring a vector. The generator now
normalizes arguments to a vector. The same definition reaches actual checking
and fails for empty rows, which return nil despite its promised map result.
A valid vector-input function passes 25 cases with seed 424242.

Only generator construction can produce a non-generatable skip. Kernel faults
and exceptions caught by test.check now propagate. A canonical SCI regression
requires the original injected checker exception to leave that boundary, rather
than become skipped text. This is not yet a separate end-to-end observation of
the fault committer's stored transaction. Exact proof and gate boundaries are in
`docs/prds/context-generation/research/core-functions-landing-2026-09-14.md`.

## Existing regression boundary

The isolated broad gate also ran `seon.test.accretion-test`; three older tests
fail before they prove their subjects. This is independently reproduced with
`bin/test-fast --paths test/seon/test/accretion_test.clj -- seon.test.accretion-test`
in a checkout at `d71ec0852`, excluding every core-functions change:
**8 tests, 24 assertions, 2 failures, 2 errors**.

- `one-gate-set-query-includes-edges-subjects-and-pending-tests` gets empty
  gate sets after unchecked synthetic program transactions. Its redundant
  `extra-schema` entry is already in the canonical population.
- `candidate-tests-run-on-a-copy-on-write-turn-fork` reaches
  `seon.sci.eval/evaluate` with missing test source/namespace after setup.
- `auto-check-is-seeded-shrunk-and-derived-pure` reaches
  `seon.sci.eval/auto-check-candidate` with a refused program-row shape;
  the diagnostic includes `[:seon.program/row :seon.fn/calls]`.

These tests must assert admission and retain their original semantic subjects.
The ordinary test-row admission failure is also reproduced independently in
[the test usage issue](incremental-publication-refuses-test-usage.md). Do not
manufacture usage metadata or call edges to turn these tests green. The new
exact run-2 regression passes on the same armed canonical harness; it does not
claim the older failures repaired or fault-committer delivery observed.

## Authorized resolution — 2026-09-14

All three older tests are retained and green on the armed canonical fixture.
Fixture transactions now assert admission, declare program provenance and
namespace refs, and use sets for call edges. The usage render request's incorrect
database-entity marker was removed so ordinary tests can be admitted without
inventing usage metadata. No semantic assertion was weakened or test deleted.

The vector-input regression checks the exact run-2 definition and its meaningful
empty-input failure. A checker exception propagates unchanged instead of becoming
agent output. In the existing turn owner, the call to `gate-function-install`
is outside the agent-mistake `phase` wrapper; it therefore reaches the existing
Flow fault path. This repair proves that boundary and the platform gate proves
the common infrastructure; it does not claim a separately injected fault
transaction in default, which remained read-only for verification.
