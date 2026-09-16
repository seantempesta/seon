---
type: landing-note
status: awaiting orchestrator review
created: 2026-09-17
tags: [steward, issue, detector, program-graph, call-graph, test-selection]
---

# First-task detectors: contract and reaching test

Owner ruling F7 (`docs/prds/steward-platform/plan/program-facts-are-the-runtime-prd-2026-09-17.md`
§1e): *"First agent tasks are the simple ones: functions without contracts or
without a reaching test, not render pairs. Detectors for both; the
without-test detector depends on the call-graph fidelity fix so it does not
lie."* This note records what landed, measured on cluster `default`
(pid 53320) and the exact boundary of the proof.

Read end to end before designing: the PRD §1b (T1–T4), §1c C1, §1e F7, §4b;
`owner-decisions-2026-09-17.md` decisions 4 and 5;
`research/issue-generator-2026-09-16.md`;
`research/call-graph-fidelity-2026-09-17.md`; `AGENTS.md` §0–§5 and §7;
`tmp/orchestrator/wave2/repl-rule.txt`. Source read at the seam:
`src/seon/issue/detect.clj`, `src/seon/issue.clj:420-573` (the generator's
subject/prose/identity path), `src/seon/fn.clj:1242-1410`
(`test-reach-rules`, `gate-set`, `tests-reaching`, `functions-without-tests`),
`resources/seon/schemas/seon.fn.edn`, `seon.fn.file.edn`, `seon.test.edn`.

## What landed

Two detectors in `src/seon/issue/detect.clj`, each in the shape of the
existing `public-without-doc`: two arities, the two-argument one scoped by
`{:seon.fn.file/relative-root "src"}` through the declared file-root fact
(decision 5, option 1), yielding the subject shape the generator already
writes. **`seon.issue/generate!` needs no change** — it resolves the detector
through its program identity and accepts any symbol
(`src/seon/issue.clj:460-476`).

- `public-without-contract` — public, source-bearing, first-party
  declarations with no `:seon.fn/spec`
  (`resources/seon/schemas/seon.fn.edn:114`). Excluded BY FACT: a declaration
  the indexer recorded as a macro (`:seon.fn/macro?`; measured: 6 macros in
  the population, **0** carrying `:seon.fn/spec`), and a declaration that does
  not own its defining form (several symbols sharing one
  `:seon.fn/form-span`, which is how a `defrecord`'s constructors are
  interned) — the same exclusion the docstring detector already applies.
- `public-without-reaching-test` — the same population, asked once per
  candidate through `seon.fn/tests-reaching` (the one stored-edge derivation
  `seon.fn/gate-set` uses to select a commit's gate, `src/seon/fn.clj:1293`).
  No name match, no second reach derivation. A candidate whose identity or
  whose file's references that walk cannot resolve is answered with EVERY
  test and is therefore **not** a subject: an unresolved reach is unknown, and
  reporting unknown as a finding is the absence-as-health class in the other
  direction.

Three private helpers were factored out and `public-without-doc` rewritten on
top of them, value-identically (`shared-form-symbols`, `carrying`,
`declarations`): one candidate query instead of four near-copies, so the three
standards now differ only by WHICH fact is absent.

### Honest evidence (P3)

The call graph under-reports today
(`research/call-graph-fidelity-2026-09-17.md` §3: an edge exists only for a
syntactic call or one of clj-kondo's twenty-five higher-order `clojure.core`
functions, so `apply`, `partial`, `comp`, a var quote, a protocol
implementation body and a `defmethod` body store none). Every
`public-without-reaching-test` subject's problem text therefore names the
basis `:t` the reach was derived at, names `seon.fn/tests-reaching` as the
derivation, and says in words that the graph may be incomplete and that the
finding means "no reach is recorded", never "no test exercises it". The
detector re-run resolves the issue when reach appears — whether a regression
was written or the missing edge became indexed — exactly as the docstring
detector resolves when a docstring appears.

## Measured on `default`, 2026-09-17

Database value at basis `:t` 536871793 (`seon.db/basis-t`), commit
`6aaaeabe-d99a-5eec-9e3e-9d254e71c9c6`. Population: 5030 declarations, of
which **1196** are public and source-bearing (**1091** under root `src`).

| detector | unscoped | `{:seon.fn.file/relative-root "src"}` | wall time (src / unscoped) |
|---|---|---|---|
| `public-without-contract` | **68** | **8** | 0.1 s / 0.1 s |
| `public-without-reaching-test` | **166** | **139** | 17 s / 18 s |
| `public-without-doc` (unchanged behaviour, re-measured) | 33 | 2 | 0.1 s / 0.1 s |

`public-without-contract`, all 8 `src` subjects (the whole list; there are
fewer than 15):

```
seon.flow/->CountedDroppingBuffer
seon.flow/->RefusingBuffer
seon.print/-close
seon.print/-fragment
seon.print/-open
seon.print/-token
seon.schema/*candidate-visit!*
seon.test.runner/destructive-owner-rows
```

`public-without-reaching-test`, first 15 `src` subjects:

```
my.agent/done          my.agent/identity     my.agent/settings
my.agent/settings!     my.background/await   my.background/background
my.background/poll     my.edit/lines!        my.fs/glob
my.fs/read             my.fs/stat            my.issue/add!
my.issue/status        my.issue/tests!       my.message/decline
```

First 15 unscoped `public-without-contract` subjects (all test helpers, which
is why the default run is scoped to `src`):

```
seon.adoption-contract-freshness-test/probe!   seon.agent-call-edges-test/exercise!
seon.cluster.agent-test/fixture-evaluate       seon.cluster.source-test/activation
seon.cluster.source-test/populate!             seon.cluster.source-test/populate-blocked!
seon.cluster.source-test/populate-fails!       seon.cluster.source-test/populate-from-data!
seon.cluster.store-transact-test/refusing-call seon.cluster.turn-test/fake-evaluate
seon.context-blocks-fixture/clear-history!     seon.context-blocks-fixture/declared!
seon.context-blocks-fixture/install!           seon.context-blocks-fixture/install-running!
seon.context-blocks-fixture/seed!
```

### Exact generated problem text, one subject each

`public-without-contract`, subject `seon.test.runner/destructive-owner-rows`,
title *"Public function seon.test.runner/destructive-owner-rows declares no
contract"*:

```
The public function seon.test.runner/destructive-owner-rows stores source but no
:seon.fn/spec, so nothing declares what it accepts and promises. Instrumentation has
no contract to arm, `doc` and `dir` answer with arglists alone, and a wrong argument
surfaces as an arbitrary failure inside the body instead of a typed refusal naming the
function and the offending value.

Done when the definition carries a complete Malli `:malli/schema` on its var metadata —
no :any, :some or [:maybe X] without a genuinely polymorphic boundary — published so
that (:seon.fn/spec (seon.db/pull db [:seon.fn/spec] [:seon.fn/sym
"seon.test.runner/destructive-owner-rows"])) is present.

This issue is generated by seon.issue.detect/public-without-contract: it resolves on the
run after the contract is published, and reopens if it is removed.
```

`public-without-reaching-test`, subject `my.agent/done`, title *"No test
reaches public function my.agent/done"*:

```
No indexed test reaches the public function my.agent/done. Reach was derived by
seon.fn/tests-reaching — the stored `:seon.fn/calls` walk seon.fn/gate-set uses to select
a commit's gate — over the database value at basis :t 536871793, and that walk named no
test.

Read this as "no reach is recorded", NEVER as "no test exercises it": the call graph is
currently incomplete. An edge is stored only for a syntactic call or a call through one
of clj-kondo's twenty-five higher-order clojure.core functions, so a function reached
only through apply, partial, comp, a var quote, a protocol implementation body or a
defmethod body carries no edge and is named here although a test does exercise it
(docs/prds/steward-platform/research/call-graph-fidelity-2026-09-17.md section 3,
measured 2026-09-17). Read the reach before writing a new regression.

Done when (seon.fn/tests-reaching (seon.db/db) "my.agent/done") names at least one test —
because a regression now reaches it, or because the edge that was missing is now indexed.

This issue is generated by seon.issue.detect/public-without-reaching-test: it resolves on
the run after reach appears, and reopens if it disappears.
```

`generate!` was NOT run for real on `default`: the orchestrator does that
after review.

## Regressions

`test/seon/issue/detect_test.clj` (new namespace, four deftests) on the
canonical fixture, `seon.test-support/with-database`, every seed through
`seon.db/transact!` with its report asserted. The fixture seeds one namespace
under a file carrying `:seon.fn.file/relative-root "src"` with three
declarations — contracted **and** reached by a seeded test, uncontracted,
untested — plus one declaration under a file carrying root `"test"`, and one
test entity reaching two of them through `:seon.fn/calls`. Assertions are
restricted to the seeded symbols: the canonical population carries its own
real subjects, and asserting on its totals would make the regression a census
of the tree.

1. `the-contract-standard-names-only-the-declaration-without-a-spec`
2. `the-reaching-test-standard-names-only-the-declaration-no-test-reaches`
3. `the-reaching-test-standard-reports-its-own-evidence` (the problem text
   names the basis `:t`, the derivation and the incompleteness)
4. `the-standards-exclude-a-declaration-that-does-not-own-its-form`

## Boundary of the proof — READ THIS

- The counts and the problem texts above are **live probes on `default`**
  against the new definitions, loaded into that JVM by `require :reload`.
- **The edit is NOT adopted.** `bin/seon init --dev default --changed
  src/seon/issue/detect.clj` was attempted eight times over roughly forty
  minutes and every attempt ended `Source changed while incremental
  publication was being analyzed` / `…while current-src was being analyzed;
  retry.` — nine concurrent `seon init` processes were observed in the
  process table. This is shared-tree churn, not a defect in this slice.
- **No in-process `seon.test/run` was possible.** Every run is refused with
  `:seon.error/kind :seon.test/unknown`: *"No function in this program
  declares :seon.fn/destroys, so an in-process run cannot tell whether a test
  deletes a filesystem path it did not create. Republish the program (bin/seon
  init --dev default)…"*. The attribute is installed in `default`'s schema but
  **0** declarations carry it, because no complete publication has converged
  since the destructive-owner lane landed. That refusal is correct and was NOT
  worked around; it blocks every lane's in-process run on `default` until one
  publication converges. The regressions are therefore **unverified in
  process**; the orchestrator's cold gate is the proof of record.
- `clj-kondo` is clean on both changed files (0 errors, 0 warnings).

## Findings out of scope (issues filed separately)

1. **`public-without-reaching-test` costs ~16 ms per candidate (≈18 s for the
   whole population).** `seon.fn/gate-set` re-runs its whole
   `declared-reference-edges` query on every call
   (`src/seon/fn.clj:1280-1292`, called at `:1311`); hoisting that population
   to the caller would make a whole-population run about one query plus the
   walks. `seon.fn.clj` is another lane's file, so this is filed, not fixed.
   For contrast, the recursive-Datalog equivalent
   `seon.fn/functions-without-tests` took **111 s** on the same database
   (measured; it returned 312 symbols over the whole public population, the
   number §3 of the fidelity note records).
2. **Six of the eight `src` contract subjects are declarations no human can
   put a contract on** — two `deftype` constructors (`seon.flow/->…`) and four
   `defprotocol` method vars (`seon.print/-…`) — and one is a dynamic var
   (`seon.schema/*candidate-visit!*`). The fact that would exclude them is
   clj-kondo's `:defined-by`, which the analyzer ALREADY keeps
   (`src/seon/fn/analyzer.clj:103-111`) and which no declaration row and no
   `:seon.fn/*` attribute stores — its only first-party reader is
   `src/seon/fn.clj:322`, spotting a `defmulti`. Storing it would let both
   standards exclude them by fact instead of over-reporting. Until then the over-report
   is honest, and the docstring detector already behaves the same way
   (decision 5 recorded the same two `seon.flow` constructors).
