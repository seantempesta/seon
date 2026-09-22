---
type: review
status: changes requested
created: 2026-09-23
---

# Three-way comparison — independent diff review

Reviewed `b51a24055`, `e4cd4ee97` and landing `05d33a144` with `git show`.
Unless stated otherwise, source/test/schema citations refer to `e4cd4ee97`, not the moving working tree.
Authority: `docs/prds/agent-platform/plan/lane-realities-one-lifecycle.md:56` (row 11), and the two requested data packs.

## Findings, ranked

1. **P1 — A missing family digest declaration silently removes its rows.**
   `src/seon/program.cljc:405-412` filters OUT a family before checking completeness; `:421-427` validates only survivors, and `:435-442` detects missing digests only for their identities.
   If a program family loses its digest member on the branch, existing rows disappear from that map and `three-way` reports retractions; if all three projections omit it, its changes are invisible.
   File/lint recomputation is justified by `docs/research/agent-platform/program-rows-data-pack-2026-09-22.md:194-195`; absence of a digest declaration is not evidence that every other family is recomputable.
   **Smallest fix:** validate each program declaration family's comparison eligibility before filtering; allow exclusion only with positive declared recomputation evidence, otherwise return a named typed unknown. Add a missing-family-member regression.

2. **P1 — Family discovery uses the loaded files' world, not the supplied branch.**
   This is computed, not a literal hand list: `identity-attributes` derives schema links at namespace load (`src/seon/program.cljc:17-27`), and `program-attributes` derives partition membership (`:29-53`).
   But `digest-map` intersects the branch registry with that process-wide packaged set (`:401-412`). A branch-only identity/schema with a valid digest never enters the scan, despite belonging to the program partition.
   This extends the existing authority-mismatch class in `docs/seon/issues/live-resources-outrun-the-loaded-program-identity-list.md:22-30`.
   **Smallest fix:** derive identity/schema links from the supplied projection on each immutable basis, through the existing owner; test a branch-only declared family.

3. **P2 — Missing evidence refuses, but has no declared typed outcome.**
   `digest-map` promises only `:seon.program/digest-map` (`src/seon/program.cljc:398-399`); read failures and missing identities/digests throw ad hoc `ex-info` (`:416-442`). Missing projection is also passed unchecked to `program-attributes` (`:401-402`; nullable supplier at `src/seon/db.clj:1212-1217`).
   These prevent some silent omissions, but do not meet the requested typed-unknown boundary; thrown maps omit the complete standard refusal shape and declared output union.
   **Smallest fix:** use the existing typed refusal owner for projection, read and missing-digest evidence and declare those outcomes in the reader contract; assert their exact shapes.

4. **P2 — Regressions prove classification/disjoint edits, not map completeness.**
   The property asserts the entire result (`test/seon/program_test.clj:33-83`), but its generator varies only symbol suffixes. It omits head-only add/delete and branch-edit/head-delete cases.
   The branch regression chooses its subjects FROM the reader result (`:89-91`) and asserts only two changed sets and no conflict (`:116-120`). A reader retaining just those two identities could pass; exclusion of file/lint rows (`:98-99`) is vacuous without positive fixture-presence evidence.
   It meaningfully detects swapped branches or unchanged reads once reached, but neither checks a same-identity conflict nor proves canonical digest production: it writes invented digests directly (`:107-112`).
   **Smallest fix:** retain this narrow reader test, assert complete maps against independently established fixture identities, and add missing-evidence plus symmetric absence/conflict cases. Restore the canonical published base through its owner before claiming integration proof.

5. **P2 — “No :any” is true textually for the diff, false transitively.**
   Both new named functions have Malli contracts (`src/seon/program.cljc:398-399,450-453`); no changed named production function lacks one and no new literal `:any` was added.
   However the new maps reuse `:seon.program/identity`, whose second tuple member is `:seon.schema/value` (`resources/seon/schemas/seon.program.edn:78-79`), an exempted `:any` (`resources/seon/schemas/seon.schema.edn:44-47`). It admits e.g. `[:seon.fn/sym nil]`; it does not type the value against the identity attribute.
   **Smallest fix:** tighten the shared identity owner to the declared identity-value shapes, or explicitly acknowledge this inherited exemption instead of claiming fully typed identities/no `:any`.

6. **P2 — The retained-basis reader has no admission bound.**
   `src/seon/program.cljc:428-437` realizes every selected identity and digest; `src/seon/db.clj:2349-2350` eagerly realizes the dependency scan without a work/result limit. A 16 ms observation does not satisfy row 11’s bounded-read contract.
   **Smallest fix:** admit an explicit work/result bound through the existing index-read owner and refuse exhaustion; never return a partial digest map.

## Verified behavior and cost boundary

- **Absence algorithm passes.** `src/seon/program.cljc:455-491` partitions the union of identities correctly: equal branch/head → keep; branch=base → keep head; head=base → take branch (including add/delete); otherwise conflict with all three states.
  Head-only add/delete is `changed-on-head`; both deleted is `unchanged` relative to head. All absent yields no identity. A final-state comparator cannot distinguish an add-then-delete from no change, nor is that required.
  Diagnostic execution of the exact committed body over `{absent,a,b,c}³`: **64 cases, zero mismatches**, including conflict payloads (Babashka, unarmed, 0.02 s).
  The exact property body returns true; always-unchanged and swapped-branch/head mutants return false (0.02 s). This is counterfactual diagnostic evidence, not a canonical recorded mutation run.
- **AEVT joins are correct for complete snapshot DBs.** Datahike pin `41c79c1a70f108cf969b8c5ec6d3ba81c8835eb8`: `reference-code/datahike/src/datahike/db/utils.cljc:205-210` binds the first AEVT component to attribute; `db.cljc:246-254` slices that exact range.
  `src/seon/db.clj:2338-2350` eagerly realizes the complete datoms; `program.cljc:428-437` joins by entity ID within the SAME immutable database. No AVET/indexed-attribute assumption or pull cap is involved.
  “Two scans” means two phases: **one digest range plus one range per selected identity attribute** (five ranges for four families), O(digests + identities) time/memory, rerun per call; no admission bound is installed here. Add an explicit refusal bound before claiming row 11's bounded retained-basis reader.
  A carried projection must belong to that exact snapshot. At the reviewed commit its supplier reads metadata (`src/seon/db.clj:1212-1217`), not the landing's claimed commit memoization (`docs/prds/agent-platform/landing/lane-three-way-comparison-2026-09-22.md:37`).
  The landing's 15.8–19.7 ms figures are unarmed scratch observations (`docs/prds/agent-platform/landing/lane-three-way-comparison-2026-09-22.md:104-106`), not reproduced end-to-end reader timings here.

## Fresh evidence, foreign boundary and timings

`bin/seon status`: 0.08 s; MCP status reached default PID 51528, with 14 errored receipts. Read-only JVM custody probe: basis 536871170, both reviewed Vars absent, 2 ms envelope; no reload or mutation.
Read-only native AEVT probe at basis 536871174: 10,201 digests, 4,436 function identities, zero wrong-attribute rows or missing function digests; 1.720 ms body / 3 ms envelope. This verifies current snapshot inputs, not the uninstalled reader.
`bin/test-fast --paths src/seon/program.cljc --paths test/seon/program_test.clj --paths resources/seon/schemas/seon.program.edn -- seon.program-test`: snapshot HEAD `20f319608`, no overlay differences; run `0d21a7cab6ed`, 31 executed, 203 assertions, 0 failures, 11 errors.
Foreign boundary: published graph `d73e0a6c…`, 61 commits behind, refuses `:my.note/note` partition during fixture setup. The pure property passed; the two-branch body never ran. See `docs/seon/issues/test-fast-runs-on-a-published-base-older-than-heads-schema-validator.md:10-28`; no incremental schema/branch proof is claimed.
**Only operation over 1 s: focused runner 29.90 s, a >10 s defect sighting** of `docs/seon/issues/a-focused-test-jvm-spends-thirty-seconds-before-its-first-test.md`: snapshot 3 s; projection-ready→armed 1.96 s; first→last test 7.72 s; remaining 17.22 s is startup/loading/recording/exit, not separately instrumented.
Raw evidence retained in `tmp/review-three-way-20260923/` (committed comparator extraction, test extraction, runner log); no owned process remains. No source/test/resource edits, cold gate, reset, or other lane/session changes. Only this review is committed.
