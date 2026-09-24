# Issue audit part-03 — 2026-09-24

Audited all 109 assigned notes. This is a source-only candidate-task audit, not a runtime health verdict.
Read the three assigned design/toy/review documents and the issue README before classification.
Baseline observed: `d5a95e0bc347ec92d08fbf712fd9dffa596465c6`; shared checkout may advance.
Every note was read, including follow-up fixes/rulings; each target received a literal `rg -n -F` probe.
Additional `rg --files src test resources` and cross-owner searches distinguished renamed files from retired mechanisms.
No JVM, default lifecycle operation, test gate, provider call, worktree or foreign edit was used.

| Dimension | Counts |
|---|---|
| relevance | live: 28, obsolete: 23, unknown: 58 |
| trigger | dependency-change: 4, design-gap: 3, error: 29, measurement-over-bound: 15, owner-ruling: 5, query-finding: 7, review-finding: 17, staleness: 3, test-failure: 26 |
| work | design-decision: 2, docs: 23, fix: 10, investigate: 25, measure: 12, refactor: 9, test: 20, tooling: 7, upstream: 1 |
| exit | bound-test: 11, empty-query: 16, example-test: 49, measurement: 9, other: 5, property: 17, root-decision: 2 |
| size | large: 17, medium: 37, small: 55 |

`live` means source still supports a plausible concern, not a reproduced failure. `unknown` means a dated incident,
measurement, partial fix or installation obligation cannot be decided by file/symbol existence. `obsolete` means
the original mechanism was removed or a directly relevant source repair is present; this does not certify installation.
Obsolete rows remain in the vector for coverage and suggest documentation/evidence reconciliation, not restoration of deleted code.

Frontmatter is preserved independently: 104 open, 2 resolved, 2 closed, and 1 free-text status.
The wake-delivery free-text status is encoded as `:fix-implemented-recorded-integration-pending`; its exact prose is retained in notes.
Resolved/closed status is not assumed consistent with every paragraph: the namespace-row note retains an explicit residual that current source further addresses,
and the hook note ends with an outdated claim contradicted by its own resolution and current hook source.

The recurring patterns are:

- Historical incidents survive architecture cuts. Deleted search, cluster.loop, worker/coordinator and newest-base paths should not generate repair tasks.
- Refusal handling repeatedly hides the first error: an invalid fixture write becomes absent data, or a valid refusal becomes an outer contract violation.
  A task must carry the first complete refusal and distinguish setup failure, body assertion failure, and failed result recording.
- Many “performance bugs” mix fixture acquisition, operation work, publication, waiting and observation timeout.
  Generate measurement tasks first when phase attribution or a current baseline is absent; an expired observer does not prove execution stopped.
- Data shape changes repeatedly strand callers: symbols versus strings, transaction refs versus instants, stored entities versus pulled reader shapes.
  Strong exits test the producer/consumer relationship rather than rewriting an assertion to match whatever currently happens.
- Source fixes and installation proof are separate obligations. Many notes already document a repair but still await adoption, arming or durable results.
- Small-context tasks need explicit subjects and acceptance slices. The large umbrellas need child tasks, not a whole namespace tour.

The assigned sample is alphabetical (runtime through writer), not a random whole-system census.
Example tests dominate exits; measurement/bound exits are substantial. Error and test-failure triggers dominate the raw candidate set.
Query generation is strongest for current failing test evidence, missing provenance, declaration contradictions and missing graph edges.
Bound violations become queryable only if elapsed time, operation, bound, program/input identity and completion evidence are actually recorded.
Tool-registration failures, classpath/revision coherence, HTTP captured closures and process/cache mismatches need external observations.
The EDN query strings are sketches, not promises of installed functions or attributes; nil means no honest database-only detector was identified.

Nine instructive notes:

1. `schema-field-types-admit-contradictory-owning-values.md`: valid fields do not prove a valid entity; generated counterexamples give independent property exits.
2. `test-check-classifies-completed-errors-as-expiry.md`: failure and timeout are different outcomes; current source retires the old caller and exposes typed await outcomes.
3. `test-evidence-is-lineage-keyed-so-reset-cannot-keep-it.md`: acceptance needs content identity across branches, not copied transaction numbers or a green label.
4. `the-indexer-drops-clj-kondos-defined-by-so-generated-declarations-look-authored.md`: preserve a producer fact to improve detectors; now source-repaired, so avoid duplicate work.
5. `within-run-schema-key-refinement-needs-an-owner-ruling.md`: mutually incompatible requirements need a recorded root decision before tests can be the oracle.
6. `terminal-refusal-error-fact-fails-on-oversized-data.md`: later evidence falsifies an apparent cause; retain hypotheses separately from observations.
7. `the-live-juniper-fixture-wipes-turns-under-a-running-agent-loop.md`: lifecycle preconditions belong at the writer or terminal-event boundary, with a concrete concurrency falsifier.
8. `writer-rejection-prints-the-complete-program-projection.md`: diagnostic handling is itself a measurable failure surface; context must retain the original refusal without carrying the entire world.
9. `turn-writers-upsert-bare-namespace-rows-without-a-definition-digest.md`: a resolved note can retain an outdated residual; current source and status must both be checked before generating work.

The two MCP-absence notes are one explicit duplicate class (the later bounded-lane note points to the earlier recurrence note).
Storage-growth notes overlap but have different exits: writer attribution, missing retention policy, and an accounted growth bound.
Turn/transcript fixture umbrellas likewise overlap without being interchangeable. Do not merge them solely by namespace.

What the simple task sketch is missing:

- Reviewed acceptance obligations before implementation, including unresolved test names and when their actual digests become frozen.
- Evidence provenance and currentness: tested program/input identities, source versus installed state, and successful durable recording.
- Explicit schema keys and non-code outputs as deliverables; a dependency closure merely proposes additional merge scope.
- Root decisions, deferred work and operational authorization. A note saying RESET NEEDED does not authorize an audit or task worker to restart default.
- Multi-stage completion: investigation can finish with a falsified cause; an implementation can be complete while integration proof remains pending.
- Occurrence deduplication and residual scope: preserve multiple sightings without spawning repeated fixes or reopening settled rulings.
- Independent oracles. An empty query is only proof when the detector has positive subjects and a tested bad case; source presence is never a passing behavioral test.

These gaps fit tasks as data when represented explicitly; they do not justify another runner, scheduler or task-kind registry.
Use the existing test and merge owners. Root should accept the concrete deliverable set and the combined evidence, not the agent’s completion claim.

Verification used the Babashka EDN reader (no JVM), literal source searches and static checks: vector coverage, unique paths, native symbol/keyword subjects, enum values, large-task splits and summary totals checked locally.
Only part-03.edn and part-03.md are authored. Production source/test line delta: 0/0.
Runtime timings, memory, adoption and arming are deliberately unmeasured because this assignment forbids running the JVM.
