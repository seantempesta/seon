---
type: research
status: complete
created: 2026-09-24
scope: read-only part-01 issue-to-task audit
---

# Part 01: issue notes as verifiable task candidates

109 assigned paths yield **43 live, 48 unknown, 18 obsolete** candidates. These are source-relevance verdicts, not installed-system findings. The input contains 108 issue notes and one reference index. An open status is neither a fresh failure nor authorization to repeat the old repair.

Read the namespace-context draft, toy experiment, independent Astra review and issue README before the notes. Reviewed frontmatter, problem, acceptance/fix sections and later dispositions. Baseline: `d5a95e0bc347ec92d08fbf712fd9dffa596465c6`; working-tree source probes used `rg --files src test resources`, qualified-name/local-definition searches, literal `rg -n -F` probes and focused source reads. The EDN retains one current location or explicit absence per input. Supplemental reads checked retired launcher paths, active guidance and pinned dependency seams. Concurrent commits changed other audit deliverables; they are not this lane's work.

`live` means the owning seam remains and an unresolved obligation is plausibly current. `unknown` means changed implementation, pending proof, transient state or insufficient evidence prevents that judgement. `obsolete` means the specific filed mechanism/premise is removed or contradicted, including the non-issue index. It does not certify replacement behavior. Obsolete entries describe retirement/closure work, not repairs to recreate deleted machinery.

Frontmatter is preserved as keywords: 104 open, 2 closed, 1 fixed-pending-default-reload, 1 prose “fix implemented; fixture and live proof pending,” and 1 active reference. The prose status becomes `:fix-implemented-fixture-and-live-proof-pending`; its exact original text remains in audit notes. No issue status or index was edited.

| Dimension | Counts (each dimension totals 109) |
|---|---|
| Relevance | live 43; unknown 48; obsolete 18 |
| Trigger | error 32; review-finding 21; test-failure 16; measurement-over-bound 14; staleness 9; design-gap 7; query-finding 4; owner-ruling 3; dependency-change 2; other 1; user-request 0 |
| Work | investigate 26; test 18; delete 18; fix 13; design-decision 8; measure 8; refactor 8; tooling 5; upstream 3; docs 2 |
| Exit | example-test 43; empty-query 18; property 17; bound-test 12; root-decision 8; measurement 6; other 5 |
| Size | small 38; medium 54; large 17 |

70 entries carry detector/query sketches; 39 carry nil. These sketches describe possible triggers, **not installed detectors**. Graph topology, discarded-result analysis and external measurements need producer facts before a database query can see them. A query sketch on an obsolete row illustrates the general detection pattern; it is not a scheduling recommendation. No duplicate-of link is asserted: shared symptom or owner alone does not establish the same defect. Needs links are limited to explicit, extant prerequisites; no scheduling graph was inferred from proximity.

The dominant triggers are errors and review findings (53/109); the dominant exit is a concrete example test (43). Investigation plus verification work (44) exceeds direct fixes (13). This argues for generating a reproduction or proof task before generating an implementation task when evidence is stale. Seventeen large entries have explicit splits: notably cross-cluster isolation, coherent adoption, fault delivery, error-field retirement and accumulated publication performance work.

Recurring patterns:

- **Schema changes outrun producers and fixtures.** Missing branch/digest fields, alias uniqueness, refusal output shapes and identity upserts recur. Context needs the final owning schema, producer and complete refusal, not merely its message. Canonical constructors and checked fixture writes localize failures.
- **Published, loaded, armed and recorded are different facts.** Several notes report correct source with missing installed proof. Task evidence must name its program/input identity and acquisition basis. The rows-before-record adoption window remains visible at `src/seon/cluster.clj:2577` and the later record stage.
- **Timeout is not completion.** HTTP silence, test cancellation, provider unavailability and interrupted publication cannot imply rollback, cleanup or successful absence. Resource ownership needs a separately witnessed exit.
- **Old infrastructure leaves open notes behind.** The pooled confirmation runner and manifest-printing launcher retired; the finite-spin test became an event-controlled arm test. The uniqueness regression now uses evaluation identity. Generate staleness review instead of reimplementing those fixes.
- **Performance notes accumulate several causes.** Boot wall time is not ready-ms; publication wall time is not validator time; a JFR sample is not causal allocation share. A measurement task needs workload, phases, bound, baseline and an unchanged behavioral oracle.
- **Positive signals matter.** Empty detector output is only success after the detector completed on the correct basis; a missing subject, failed seed or missing test is incomplete. The issue renderer/start guard already distinguish detector-backed completion from tests.

Ten instructive notes for task design:

| Note | Lesson |
|---|---|
| [Generated issues cannot start](../../../seon/issues/generated-issues-carry-no-tests-so-start-refuses-them.md) | Current source accepts tests or a detector. Completion evidence needs an explicit alternative, not invented test rows. |
| [Generated opening promises tests](../../../seon/issues/generated-issue-opening-promises-tests-it-does-not-have.md) | Render the actual acceptance mechanism. The current check-form is repaired; remaining proof should not become another rewrite. |
| [Discarded fixture refusals](../../../seon/issues/fixtures-that-ignore-a-refused-transaction-read-absence-as-behaviour.md) | A recurring class can generate small tasks, but detecting discarded values requires structural analysis beyond call reach. |
| [Adoption rows before record](../../../seon/issues/development-adoption-writes-program-rows-seconds-before-its-record.md) | History can raise a precise task; transaction order alone cannot certify executable-generation coherence. |
| [Error-result retirement](../../../seon/issues/error-result-retirement-crosses-held-readers.md) | Deliverables must include schema keys and all coordinated readers/writers; function reach is not permission to expand scope. |
| [Test timeout before release](../../../seon/issues/in-process-test-timeout-precedes-fixture-release.md) | Execution verdict and actual resource release need separate witnesses. Cancellation is not an exit event. |
| [Cohosted heap isolation](../../../seon/issues/cohosted-clusters-share-one-unbounded-agent-heap.md) | A root guarantee decision precedes a bounded destructive proof; a successful OOM catch does not establish sibling availability. |
| [Identity-upsert policy](../../../seon/issues/identity-upserts-still-require-complete-entity-maps.md) | A failing example cannot decide whether the requirement itself is correct; preserve an owner-decision task. |
| [Storage cost per sample](../../../seon/issues/eval-samples-cost-42mb-of-store-each.md) | The note disproves its filename. Generate a current measurement, not a repair for obsolete arithmetic. |
| [Inline prose becomes evaluation](../../../seon/issues/inline-form-in-reply-prose-becomes-an-evaluation.md) | A retained real reply gives a small reproducible test with exact forms, source spans and ordering. |

What the simple task model still needs:

- **Evidence lifecycle:** proposed, missing, refused, failed, passed and unavailable checks all differ. Freeze reviewed checks when they exist; do not pretend a future test has a digest. Keep historical evidence immutable and currentness content-based.
- **Non-code outputs:** browser observations, measurements, dependency verdicts, external account readiness and owner decisions are legitimate deliverables. A function-symbol-only merge set cannot express them or schema retirement.
- **Explicit authority and environment:** model credits, destructive heap rigs and lifecycle proofs need supplied constraints/authorization. An error does not automatically authorize a paid trial or default restart.
- **Dependency versus coordination:** prerequisite acceptance, shared-file ownership and atomic cross-owner retirement are different relations. Reachability may suggest them but cannot establish task order or merge scope by itself.
- **Reliable detectors:** record detector identity, input basis, completion and failure. Otherwise an empty result can silently close work after a failed query or retired subject. Recurring detections also need deduplication and reopening semantics.
- **Independent oracles and scope review:** a task can pass weak tests while omitting contracts, documentation or schemas. Root reviews deliverables and affected checks; tests may not change their own specification without a recorded decision.
- **Catalog admission:** `index.md` is type reference/status active and must not become repair work. Notes with resolved subclaims need their remaining obligation extracted before scheduling.

Validation: Babashka EDN parsing (no JVM) confirms one vector, 109 unique paths in exact input order, all 16 requested fields, native symbols/qualified keywords, valid dependency paths and nonempty splits for every large item. Counts above are derived from the EDN. Focused literal probe/serialization pass: 0.352 s, Python peak RSS 21,037,056 bytes; these measure the audit script only, not Seon operations. Structural validation and owned-file whitespace checks are the only checks claimed.

Only `part-01.edn` and this report were authored; source/test delta is 0/0. No JVM, runtime probe, provider call, lifecycle operation, publication, browser action, gate, push or other lane operation was performed. Existing `reference-code/http-kit` dirt was untouched. Native-runtime adoption, test behavior, current account state and current performance remain unverified. Commit identity is reported in the final response; no self-referential commit id is embedded here.
