---
type: landing
status: landed
created: 2026-09-22
owner: one-lifecycle §2 row 11
---

# Three-way program comparison

## Scope and design

This slice adds only the pure three-way declaration comparison and its database-value
reader. It adds no branch lifecycle, merge, writer, acceptance, or derived-row
transfer. `seon.program/three-way` compares complete immutable
`{[identity-attribute value] -> definition-digest}` maps for base, branch, and head.
Absence is represented by key absence on input and by `:seon.program/absent` inside
conflict evidence. Equal branch/head states are unchanged even when both differ from
base. A branch-only addition or retraction is classified separately; branch/head
changes from the same base state with unequal final states conflict.

Function identity remains the whole `[:seon.fn/sym symbol]` declaration. Arity rows
are components and never become merge identities, so two edits to distinct arities of
one function conflict when their final definition digests differ. The comparison does
not carry calls, references, arity observations, lint, or other derived rows; row 12
must recompute them in the combined resolver world.

The reader compares the declaration families whose row schema declares
`:seon.program/definition-digest`. It derives them from `identity-attributes` and
each identity attribute's `:seon.program/row-schema` entries, so there is no hand
list. It then checks that each family belongs to the program partition through
`seon.program/program-attributes` (landed in `8a069b5e4`). On a from-zero HEAD
publication this selects `:seon.fn/sym`, `:seon.ns/name`, `:seon.schema/key` and
`:seon.test/sym`. File rows (`:seon.fn.file/relative-path`) and lint rows
(`:seon.lint/id`) are derived from bytes and analysis, as the
program-rows data pack says (`program-rows-data-pack-2026-09-22.md:39-40,194-195`).
They carry no digest and are recomputed, not compared. The reader takes the
database's carried projection (`seon.db/carried-projection`, memoized by commit)
and joins two `:aevt` index scans through `seon.db/datoms`. It never calls
`definition-digest`. A digested-family declaration without a stored digest
refuses; it is never treated as absent.

## Runtime observation and cost

Before editing, `bin/seon status` and MCP `runtime_status` observed default PID 51528,
start instant `2026-09-22T18:46:42.624Z`, health `:observed`, readiness with no missing
layers, and a reachable JVM prepl. The required JVM custody probe obtained
`(seon.cluster.boot/connection "default")` and basis `536871142` in 1 ms. All probes
were `mode jvm`, `read_only true`, root `/Users/sean/src/seon`, cluster `default`.

The reader's two narrowed queries, with the database projection explicitly carried,
observed 11,643 identity rows, including 4,436 function rows, and 10,201 stored
digests in 15.663 ms. The requested historical 4,462-function label did not match the
live default at this basis. Default also had 1,442 undigested identities (701 file rows
and 741 lint rows), so the complete reader would correctly refuse this pre-publication
population. This is a timing of map input acquisition on the live database, not a
claim that default adopted this slice.

## Commits

- `b51a24055` — `seon.program/three-way`, `seon.program/digest-map`, the seven
  value schemas in `resources/seon/schemas/seon.program.edn`, the fixed-seed
  property test and the two-branch fixture regression.
- `e4cd4ee97` — compare only the digested families; read from index datoms and
  the carried projection; rename the local that shadowed `identity`; assert that
  file and lint rows are excluded.

Changed paths: `src/seon/program.cljc`, `test/seon/program_test.clj`,
`resources/seon/schemas/seon.program.edn`, and this note. Issue notes:
`docs/seon/issues/publication-analysis-reads-a-stale-kondo-cache-entry-for-an-unindexed-caller-target.md`
(new), plus appended rows in `from-zero-boot-takes-minutes.md`,
`test-fast-runs-on-a-published-base-older-than-heads-schema-validator.md` and
`a-focused-test-jvm-spends-thirty-seconds-before-its-first-test.md`.

## Evidence

**From-zero boot of HEAD `b51a24055`.** This ran before the owner's 2026-09-23
ruling that a schema change is proven incrementally. The HEAD tree was extracted
with `git archive HEAD` (the same mechanism as `bin/test`'s snapshot) to
`tmp/three-way-head-1790109912`, with `reference-code` and `.git` symlinked. The
command was `bin/seon --root <that>/tmp/boot reset --force`: exit 0, 199.19 s
wall, `:seon.boot/ready-ms 179252`, no missing layers, source commit
`6ab2e991-283c-534b-8d63-d98a6342cab3`. Two earlier working-tree boots
(`tmp/three-way-boot-1790109730`) did not reach ready. The first stopped at
40.92 s on a stale clj-kondo cache entry (new issue above). The second stopped at
45.23 s: "the source scratch schema transaction was refused Bad entity attribute
:seon.schema/form". That second boot ran other lanes' uncommitted
`src/seon/schema*.clj` hunks; HEAD alone booted. Both roots were stopped with
`down` and deleted, with no live holder.

**Reader on the from-zero publication** (MCP `eval_clj`, JVM mode, that root,
basis `536870929`):

- `b51a24055` (the first reader) refused: "Program declarations are missing their
  stored definition digest". 1,459 offending rows: `:seon.fn.file/relative-path`
  708 and `:seon.lint/id` 751. 2,522 ms.
- Phase breakdown of that reader:
  - `projection-from-database`: 210 ms per call, with no memo hit. This is a
    finding for the projection owner. `seon.db/carried-projection` took 0.03 ms.
  - Program-entity query: 397 ms (36,547 entities).
  - Identity query: 351 ms.
  - Digest join: 906 ms.
- The same reads as index scans took 1.6 ms (identities) + 3.0 ms (digests) +
  3.6 ms (join).
- `e4cd4ee97`'s reader, after `load-file` of the committed source into the
  scratch JVM (unarmed): 19.7 / 15.8 / 15.9 ms over three calls. It returned
  10,225 identities: fn 4,432, schema 3,296, test 2,082, ns 415.
- `(three-way m m m)`: 5.8 ms, all 10,225 unchanged.
- Speculative branch and head values were built with `datahike.api/db-with` on
  that database value (not stored). The branch set digest `a…` on
  `[:seon.fn/sym my.agent/branch]` and `[:seon.fn/sym my.agent/identity]`. The
  head set digest `b…` on `[:seon.fn/sym my.agent/done]` and
  `[:seon.fn/sym my.agent/identity]`.
  - `digest-map` on the branch value: 16.0 ms.
  - `three-way`: 15.2 ms.
  - Result: `changed-on-branch #{[:seon.fn/sym my.agent/branch]}`,
    `changed-on-head #{[:seon.fn/sym my.agent/done]}`,
    `conflict #{[:seon.fn/sym my.agent/identity]}` with base/branch/head digests
    retained, and 10,222 unchanged.

**Focused tests.** `bin/test-fast --paths src/seon/program.cljc --paths
test/seon/program_test.clj -- seon.program-test`, run `e84538519dbc`: 46.11 s
wall, 31 tests, 203 assertions, 0 failures, 11 errors.
`three-way-classifies-complete-generated-digest-maps` passed with armed contracts
(100 trials, seed 22092026, 8 ms). All 11 errors are the same fixture-setup
refusal: `:my.note/note` "An identity-bearing entity schema must declare its
partition". The overlay graph `d73e0a6c…` was 52 commits behind HEAD. That is the
open issue `test-fast-runs-on-a-published-base-older-than-heads-schema-validator.md`.
Ten of the errors come from tests already at HEAD. The eleventh is this slice's
`digest-map-compares-two-fixture-branches-from-one-commit`, which has **not run
green**. Earlier runs `8e0595c0b640` and `e6688bfdc5b3` showed the same 11 errors.

**Schema change, incremental proof: UNAVAILABLE.** The owner ruled on 2026-09-23
that a schema change is proven by transacting its declaration on a branch of a
live store. That was not possible here:

- Every canonical fixture branch refuses at base construction (above).
- Default (PID 51528) predates `8a069b5e4`: `seon.program/program-attributes`
  is "No such var" there.
- The scratch store was already deleted.

The change adds seven value schemas and no attribute, and retires nothing, so no
1.3e retirement refusal applies. The from-zero publication above installed them.
That is not the incremental proof.

## Timings over 1 s

| operation | wall | phases |
|---|---|---|
| test-fast run `8e0595c0b640` | 32.5 s | not split |
| test-fast run `e6688bfdc5b3` | about 32 s | not split |
| test-fast run `e84538519dbc` | 46.11 s | snapshot 4 s · JVM start + load about 29 s · arm 2.4 s · bodies 10.2 s |
| working-tree boot 1 (refused) | 40.92 s | refused at static analysis |
| working-tree boot 2 (refused) | 45.23 s | refused at the source scratch schema transaction |
| HEAD from-zero boot | 199.19 s | ready-ms 179,252 |
| `digest-map` at `b51a24055` | 2.52 s cold · 1.47 s | projection 210 ms · entity query 397 ms · identity query 351 ms · digest join 906 ms (replaced by `e4cd4ee97`: 16 ms) |

The test-fast runs are sighted in `a-focused-test-jvm-spends-thirty-seconds-before-its-first-test.md`
and the boot in `from-zero-boot-takes-minutes.md`.

## Verification boundary

- HEAD loads: the from-zero boot loaded `b51a24055`. `e4cd4ee97` changes only
  `program.cljc` code and its test, and its bytes loaded in the scratch JVM. No
  armed load of `e4cd4ee97`'s `digest-map` was observed, because `load-file`
  bypasses arming.
- The fixture regression has not passed. It needs `bin/test --prepare-head-base`
  (orchestrator).
- RESET NEEDED: default predates `8a069b5e4` and this slice.
- Retained: `tmp/three-way-comparison-wt` (the predecessor's archive copy, with a
  `.git` file). Its owner is uncertain, so it was not deleted.

## Review follow-up (`d8734f1e7`)

The review is `docs/research/agent-platform/review-three-way-comparison-2026-09-23.md`.
Each finding was checked against `e4cd4ee97`:

1. **P1, a missing digest declaration drops a family.** Confirmed: the old filter
   dropped any family without a digest member, with no refusal. Fix: a new entity
   property, `:seon.program/recomputed`, is declared on `:seon.fn.file/file` and
   `:seon.lint/finding`. `seon.program/declaration-families` sorts every
   row-schema family into compared, recomputed or unclassified. A family that
   declares neither (or both) is unclassified, and `digest-map` then refuses with
   `:seon.program/missing-evidence :seon.program/family-classification`, naming
   the family. Regression: `a-family-declaring-neither-digest-nor-recomputation-is-unknown`.
2. **P1, families come from the loaded files, not the branch.** Confirmed:
   `identity-attributes` is read from packaged forms when the namespace loads.
   Fix: `declaration-families` reads the supplied projection's forms and registry.
   `digest-map` takes the database's carried projection. Regression:
   `declaration-families-come-from-the-supplied-projection` uses a family that
   exists only in the supplied projection and is absent from
   `program/identity-attributes`.
3. **P2, no declared typed outcome.** Confirmed. The contract is now
   `[:or :seon.program/digest-map :seon.program/digest-map-refusal]`. The refusal
   is `:seon.error/base` built through `seon.error.refusal/diagnostic`, plus a
   `:seon.program/missing-evidence` enum: projection, family-classification,
   read, read-bound or definition-digest. Tests validate the refusal shape against
   the projection registry.
4. **P2, map completeness.** Confirmed. New tests:
   - `digest-map-is-exactly-the-stored-declarations-or-a-typed-refusal`: 450 rows
     across three index pages, exact equality with the independently built map,
     plus a missing-digest refusal and an exhausted-bound refusal.
   - `three-way-classifies-every-base-branch-head-state`: all 64 states in
     `{absent,a,b,c}^3`, including head-only add/delete and branch-edit/head-delete.
     Each case lands in exactly one class, conflict digests appear iff the case
     conflicts, and all-absent appears nowhere.

   The fixture two-branch test still does not run (see the boundary below).
5. **P2, transitive `:any`.** Confirmed. Compared identities are now
   `:seon.program/declaration-identity`: an identity attribute plus a `:symbol`
   or `:keyword` value. The value is still not typed per attribute; the shared
   `:seon.program/identity` is unchanged.
6. **P2, no admission bound.** Confirmed. `digest-map` now takes
   `{:seon.program/max-datoms n}` and pages AEVT through `seon.db/index-page`,
   200 datoms per page. It refuses `:seon.program/read-bound` once it has read
   more than `n` datoms, never returning a partial map.

**Proof: REPL JVM with armed contracts, not the recorded gate.** The tree was
HEAD `1b21e03a7` (extracted with `git archive`) with my five files copied in. It
ran `clojure -M:test tmp/probe.clj`, which calls
`seon.test.arm/initialize-contracts!` (1,727 instrumented) and then
`clojure.test/test-vars` on the five pure tests. Result: 5 tests, 141 passes,
0 failures, 0 errors, 823 ms of test bodies. The earlier iteration's
`:malli.core/invalid-schema` errors came from `digest-map`'s armed wrapper, which
confirms the contract was checked. Timings in that JVM:

- `example-projection` (three `projection-with-schema` calls): 75–120 ms.
- `example-database`: 80 ms. Installing every storable attribute took 5,753 ms
  and was narrowed to the compared families' attributes.
- `digest-map`, near-empty value: 50–86 ms.
- `digest-map` on 10,000 rows (50 pages per range): 190 ms cold, 117 ms warm.
  The unpaged scan took 16 ms, so the admission bound costs about 100 ms.
- `three-way` on 10k: 16 ms.

**Schema change: incremental proof not claimed.** The new declarations compiled
incrementally through `seon.schema/projection-with-schema` on the handed
packaged projection. They were transacted only into an in-memory genesis store.
They were not transacted on a branch of a live store:

- Every canonical fixture branch refuses at base construction.
- `bin/test-fast` now refuses before running any test with "Test recording
  requires a published current-src" (runs at 21:13Z and at HEAD after
  `d8734f1e7`, 36.49 s).
- Default predates `8a069b5e4`.

The change retires no attribute, so no 1.3e writer refusal applies.

**Boundary.**

- No recorded test run exists for `d8734f1e7`.
- The fixture two-branch regression has never run.
- HEAD load: `seon.program` and `seon.program-test` loaded and armed in the REPL
  JVM from bytes identical to `d8734f1e7`'s paths, on HEAD `1b21e03a7`.
- RESET NEEDED stands: default predates this slice.

### Follow-up timings over 1 s

| operation | wall | phases |
|---|---|---|
| test-fast `28437dc2e31b` | 53.55 s | snapshot 4 s · load about 32 s · arm 3.0 s · bodies about 14 s; the 14 errors are 11 stale-base errors plus 3 invalid-schema errors from the first iteration |
| test-fast, admission refused (×3) | 29.4–36.5 s | refused at `record-snapshot!` before any test |
| REPL probe JVMs (×5) | 28.3–29.6 s | arm 6.0–9.5 s; the rest is JVM start and load |
| `example-database` installing every storable attribute (removed) | 5.75 s | armed `storable-attribute-in?` over every form |
