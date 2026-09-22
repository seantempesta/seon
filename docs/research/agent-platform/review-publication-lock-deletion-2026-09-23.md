---
type: review
status: complete
created: 2026-09-23
tags: [agent-platform, publication, datahike]
---

# Publication lock deletion — independent diff review, 2026-09-23

Reviewed `a102a8403`, `87c4228f7`, `20f319608`, `c4cadccd0` with `git show`; source citations below are **at `c4cadccd0`**, not the concurrently edited tree. Read AGENTS.md, the plan, audit rows 11/16 and landing note. Source/test/resources unchanged.
Dependency pins: Datahike `41c79c1a70f108cf969b8c5ec6d3ba81c8835eb8`; Konserve `8cd9144f4338c1fbdb5e531bc996189d71a86fc5`. Findings are source-traced schedules, not reproduced Seon races.

## Ranked findings and smallest fixes

1. **P1 — Analysis loses its expected head before publication.**
   `src/seon/cluster.clj:1678` captures H for analysis; `:1750-1763` passes its database and rows but omits `:seon.source/expected-commit-id`.
   `src/seon/cluster/source.clj:426,450` rereads the head and defaults the expectation to that newer value. A analyzes H, B publishes H→B, then A enters `publish!` and successfully publishes B→A using H-derived inputs. The guard never sees the stale analysis.
   **Fix:** pass the captured commit from `full-source-refresh!`; preserve explicit expected absence for initial creation. Test a pause after analysis, before `publish!`.

2. **P1 — Development adoption is no longer serialized or head-guarded.**
   `src/seon/cluster.clj:2181-2186` calls adoption after the publication guard has returned; `:2021-2028` captures a prior adoption, `:2044-2065` performs multiple writes, and `:2118-2126` unconditionally records the supplied commit.
   A can publish then pause; B publishes and adopts; A resumes and installs older rows/records A over B. Transaction serialization does not serialize this multi-transaction operation or its JVM reload.
   **Fix:** retain serialization around the existing adoption operation until its owner admits one complete adoption at an evaluation boundary. An expected head on only the final write cannot undo earlier writes/reloads.
   Existing issue class: `docs/seon/issues/development-adoption-can-mix-host-and-sci-generations.md:124`.

3. **P1 — A successful publication can return somebody else's commit with its own delta.**
   `src/seon/cluster/source.clj:515` finishes the guarded update; `:542-555` later rereads `current-src` and combines that commit with this request's digest and `outcome` identities.
   A publishes; B publishes from A before A's outer readback; A returns B's commit with A's identities. `changed-identities` trusts the supplied delta when its expected commit matches (`:200-203`), so adoption can omit B's changes.
   **Fix:** make the dependency's guarded operation return its installed commit and carry it into the publication result; never reacquire mutable current to identify this operation.

4. **P1 — Operator init converts the refusal into a valid success response.**
   `src/seon/cluster/boot.clj:435-438` selects only `:seon.source/commit-id` from `refresh-source!`; a publication error carries the winner's actual commit (`src/seon/cluster/source.clj:291-300`).
   The resulting map satisfies the success arm at `resources/seon/schemas/seon.operator.edn:209-217`; every error member disappears, including evidence that development adoption did not run.
   **Fix:** return the refusal before selecting success fields, and explicitly name the publication error in the operator response union (currently bare `:seon.error/base` at `:200`).

5. **P2 — `publication-base!` lost consistency between publication, export and metadata.**
   `src/seon/cluster.clj:2213-2217` discards the successful refresh result and rereads current; `:2229-2240` copies the mutable store and writes metadata from an earlier database value.
   Another publication between these steps can export B's head with A's manifest/provenance, or substitute B for the requested checkout. `src/seon/cluster/export.clj:363-365` copies files without a publication fence.
   **Fix:** retain the refresh result and export its retained immutable commit; until that export exists, retain serialization across this existing operation and publishers.

6. **P2 — The concurrency regression cannot prove the contested update or adoption.**
   `test/seon/cluster/publication_lock_test.clj:20-42` uses real futures and one store, but holds A before `force-branch!` until B has fully returned. A therefore fails the first precheck; the branch updates are deliberately sequenced.
   Its winner-only rows/head and scratch-cleanup assertions (`:45-58`) are useful, but it never invokes development adoption and does not cover findings 1–3. It would pass without the inner expected-head check.
   **Fix:** add a same-store test that has both requests admitted before the dependency permit, plus controlled schedules at analysis→publish and publish→adopt/readback; assert returned commit/delta consistency and the final adopted rows.

7. **P2 — Recorder obscures the new typed refusal; forwarding contracts are incomplete.**
   `src/seon/test/runner.clj:3389-3392` accepts vectors, provenance or `refused-test-run`; the new publication error has none. It throws and becomes a generic recording error at `:3393-3403`, losing the direct expected/actual-head surface.
   `full-source-refresh!` (`src/seon/cluster.clj:1675`) and `commit-persistent-results!` (`src/seon/test/runner.clj:3242`) have no declared output unions at all.
   **Fix:** preserve the publication refusal in `record-snapshot!` and declare the precise forwarding unions. No silent publication retry was found; this recorder still fails rather than reporting success.

## Answers to the remaining checks

**(1) Dependency guard verified, within its real scope.** `reference-code/datahike/src/datahike/versioning.cljc:372-377` checks before writes; `:426-438` checks inside `k/update`.
Crucially, `:358-364,453-455` holds the store-ID-scoped roster permit throughout write/readback; `reference-code/datahike/src/datahike/gc_guard.cljc:97-119` admits only one roster holder. Thus two Seon force calls using one expected head cannot both advance it. This is not a general Konserve CAS guarantee (`versioning.cljc:331-333`).
Both `publish!` (`src/seon/cluster/source.clj:515-516`) and `record-results!` (`:307,365-366`) supply expected heads; initial publication checks branch creation (`:519-528`). Neither the outer refresh nor export is protected as a whole (findings 1–5).

**(2) Local unions converted, all callers not converted.** `resources/seon/schemas/seon.source.edn:22,86-94` includes the refusal in recording/publication/refresh results. Refresh short-circuits at `src/seon/cluster.clj:2182`; export throws it at `:2214`. Findings 4/7 identify the downstream gaps. The hook recognizes refusal first (`bin/seon-hook:1511`).

**(4) Row 16 conversion verified.** Both arities declare database/report inputs, native-datom vectors and success/invalid-read outputs (`src/seon/fn.clj:3096-3104`); the report arity delegates and the body reads no fabricated metadata (`:3106-3118`).
The history callers pass explicit values (`src/seon/cluster/source.clj:213-216`; `test/seon/cluster/publication_delta_test.clj:39-45`). Remaining report callers use writer reports (`src/seon/fn.clj:2278,3400,3436`; `src/seon/cluster.clj:1476`; `src/seon/cluster/source.clj:535`). The new regression covers retractions and composed/history datoms (`test/seon/cluster/publication_report_test.clj:15-36`).
No fabricated report remains in this identity path. A literal repository-wide “none anywhere” claim is false: the unrelated rendering fixture still fabricates one at `test/seon/html_views_test.clj:186`; this slice did not introduce it.

**(5) No renamed Seon lock machinery found.** The four diffs remove the holder atom/token, phase writes and acquisition timeout; `src/seon/cluster.clj:90-105` retains the pre-existing progress callback/closed-observer refusal. Searches of src/test/resources/bin found no retired lock identifiers. Datahike's pre-existing roster gate is the serialization owner, not a newly introduced Seon substitute.

## Evidence limits and timing

Read-only diff/caller/dependency review; no Seon boot, reload, gate or focused test run, and no claim that the landing's test results were independently reproduced. Runtime-status/eval MCP tools were not exposed. One read command was hook-blocked by foreign `src/seon/oversight.clj:277,325` unmatched parentheses; subsequent reads succeeded. No foreign session/file was changed.
A disposable Babashka FileChannel probe opened A/B handles on E, locked A, atomically renamed A over E, closed A, then locked/read B and renamed B: outputs `:first-readback A`, `:second-guard-input E`, `:second-readback B` (0.02 s). It proves why Konserve handles alone are insufficient, **not** a Datahike race: the roster permit excludes that schedule. Temp files were removed. Three unsuccessful probe attempts took 0.02/0.01/0.06 s (parse/Babashka class exposure).
No executed shell operation reported over 1 s before commit; one read-tool batch took 1.1 s including orchestration (shell reported under 0.01 s). Initial note write plus automatic lint took 1.6 s; separate phase timings were unavailable. Lint's missing frontmatter was fixed; unrelated historical dependency-pin warnings remain outside this file. Commit timing is reported separately to the owner.
