---
type: research
status: complete
tags: [research, issue, runtime]
---

# Slice C blocker triage — 2026-09-15

**20 resolved / 1 superseded / 2 confirmed / 2 unverifiable.**
All 25 assigned notes were read and handled in assignment order. Both confirmed
issues are now **friction**; there are **zero confirmed-open blockers** in this
slice. Two notes retain blocker severity with explicitly unverified boundaries.

## Authority and scope

Read end to end: [AGENTS.md](../../../../AGENTS.md),
[issue lifecycle](../../../seon/issues/README.md), the localized
[issue instructions](../../../seon/issues/AGENTS.md),
[steward-platform README](../../steward-platform/README.md), and the last five
dated sections of [unsettled.md](../plan/unsettled.md): 2026-09-15 14:45Z,
15:45Z, 16:30Z, 17:05Z and 17:25Z. Used the REPL, data-oriented Clojure and
canonical testing skills for the corresponding inspections.

Initial HEAD: `131fa2a562b1d81b800329cc9b785c42cf4e9167`.
Final source cross-check: `0d1f72cd0c38fde4e71110aa170752897bad28c2`. Each issue pins its own inspected HEAD,
fix commit or exact source pointer; line numbers refer to that recorded basis.
Source inspection used committed `git show HEAD:<path>` and `git log`, preserving
concurrent working-tree edits. Later landed changes to render/schema owners were
checked for overlap with the cited mechanisms; none restored the defects closed
here. No production or test source was changed by this assignment.

Closed notes moved into `archive/` under the issue lifecycle; their relative
Markdown links were adjusted for the extra directory level. The ranked
`docs/seon/issues/index.md` remains the orchestrator's authority and was not
edited. It needs reconciliation for these 21 closures and two severity changes;
this report is a dated triage result, not a second maintained schedule.

## Issue verdicts

| Issue | Verdict | Surface | Evidence pointer |
|---|---|---|---|
| 1. [plan-renderer-arity-change-blocks-development-publication](../../../seon/issues/archive/plan-renderer-arity-change-blocks-development-publication.md) | resolved | adoption-publication | [Evidence](../../../seon/issues/archive/plan-renderer-arity-change-blocks-development-publication.md#resolution-2026-09-15-triage): `test/my/plan_test.clj:77,228,262,469` |
| 2. [platform-worker-count-exceeds-prepared-checkouts](../../../seon/issues/archive/platform-worker-count-exceeds-prepared-checkouts.md) | resolved | runner-gate | [Evidence](../../../seon/issues/archive/platform-worker-count-exceeds-prepared-checkouts.md#resolution-2026-09-15-triage): `bin/test:665–666` |
| 3. [prose-renders-splice-unquoted-into-printed-data](../../../seon/issues/archive/prose-renders-splice-unquoted-into-printed-data.md) | resolved | context-generation | [Evidence](../../../seon/issues/archive/prose-renders-splice-unquoted-into-printed-data.md#resolution-2026-09-15-triage): `src/seon/print.cljc:743–747` |
| 4. [ranged-store-collection-can-delete-live-segments-via-branch-resurrection](../../../seon/issues/archive/ranged-store-collection-can-delete-live-segments-via-branch-resurrection.md) | resolved | store-process | [Evidence](../../../seon/issues/archive/ranged-store-collection-can-delete-live-segments-via-branch-resurrection.md#resolution-2026-09-15-triage): `src/datahike/versioning.cljc:221–277` |
| 5. [registered-render-producers-fall-through-to-generic-map-rendering](../../../seon/issues/archive/registered-render-producers-fall-through-to-generic-map-rendering.md) | resolved | context-generation | [Evidence](../../../seon/issues/archive/registered-render-producers-fall-through-to-generic-map-rendering.md#resolution-2026-09-15-triage): `src/seon/render.clj:489–515` |
| 6. [renamed-predicate-leaves-a-tombstone-that-refuses-boot](../../../seon/issues/archive/renamed-predicate-leaves-a-tombstone-that-refuses-boot.md) | resolved | adoption-publication | [Evidence](../../../seon/issues/archive/renamed-predicate-leaves-a-tombstone-that-refuses-boot.md#resolution-2026-09-15-triage): `test/seon/predicate_publication_test.clj:16–97` |
| 7. [render-history-serializes-unexecuted-form-projections](../../../seon/issues/archive/render-history-serializes-unexecuted-form-projections.md) | resolved | context-generation | [Evidence](../../../seon/issues/archive/render-history-serializes-unexecuted-form-projections.md#resolution-2026-09-15-triage): `src/seon/render/walk.clj:875–910` |
| 8. [render-runtime-revision-overwrites-its-atom](../../../seon/issues/archive/render-runtime-revision-overwrites-its-atom.md) | resolved | render-debug-page | [Evidence](../../../seon/issues/archive/render-runtime-revision-overwrites-its-atom.md#resolution-2026-09-15-triage): `src/seon/render/web.clj:2430` |
| 9. [runtime-block-generates-a-nil-agent-pull](../../../seon/issues/archive/runtime-block-generates-a-nil-agent-pull.md) | resolved | context-generation | [Evidence](../../../seon/issues/archive/runtime-block-generates-a-nil-agent-pull.md#resolution-2026-09-15-triage): `src/seon/render/transcript.clj:1058–1077` |
| 10. [schema-environment-is-ambient-not-explicit](../../../seon/issues/archive/schema-environment-is-ambient-not-explicit.md) | resolved | context-generation | [Evidence](../../../seon/issues/archive/schema-environment-is-ambient-not-explicit.md#resolution-2026-09-15-triage): `src/seon/instrument.clj:475–493` |
| 11. [sci-base-context-silently-hand-lists-special-callables](../../../seon/issues/sci-base-context-silently-hand-lists-special-callables.md) | confirmed (friction) | context-generation | [Evidence](../../../seon/issues/sci-base-context-silently-hand-lists-special-callables.md#re-verified-at-head-2026-09-15): `src/seon/sci/eval.clj:183–201` |
| 12. [seeded-opening-stores-no-read-evidence-so-the-first-wake-re-emits-everything](../../../seon/issues/archive/seeded-opening-stores-no-read-evidence-so-the-first-wake-re-emits-everything.md) | resolved | context-generation | [Evidence](../../../seon/issues/archive/seeded-opening-stores-no-read-evidence-so-the-first-wake-re-emits-everything.md#resolution-2026-09-15-triage): `src/seon/turn.clj:2080–2090` |
| 13. [seon-db-reads-rebuild-the-projection-per-call-when-none-is-handed](../../../seon/issues/seon-db-reads-rebuild-the-projection-per-call-when-none-is-handed.md) | confirmed (friction) | context-generation | [Evidence](../../../seon/issues/seon-db-reads-rebuild-the-projection-per-call-when-none-is-handed.md#re-verified-at-head-2026-09-15): `src/seon/db.clj:907–913` |
| 14. [source-population-refuses-a-schema-row-without-generatable](../../../seon/issues/archive/source-population-refuses-a-schema-row-without-generatable.md) | resolved | adoption-publication | [Evidence](../../../seon/issues/archive/source-population-refuses-a-schema-row-without-generatable.md#resolution-2026-09-15-triage): `resources/seon/schemas/seon.schema.edn:68` |
| 15. [stale-operator-jvm-refuses-every-changed-publication](../../../seon/issues/archive/stale-operator-jvm-refuses-every-changed-publication.md) | resolved | adoption-publication | [Evidence](../../../seon/issues/archive/stale-operator-jvm-refuses-every-changed-publication.md#resolution-2026-09-15-triage): `src/seon/cluster.clj:1625–1630` |
| 16. [store-grew-to-69-gigabytes-in-one-day-of-lanes](../../../seon/issues/store-grew-to-69-gigabytes-in-one-day-of-lanes.md) | unverifiable | store-process | [Evidence](../../../seon/issues/store-grew-to-69-gigabytes-in-one-day-of-lanes.md#re-verified-at-head-2026-09-15): `du -sk data/store` |
| 17. [test-runner-empty-snapshot-paths-refuses-the-default-gate](../../../seon/issues/archive/test-runner-empty-snapshot-paths-refuses-the-default-gate.md) | resolved | runner-gate | [Evidence](../../../seon/issues/archive/test-runner-empty-snapshot-paths-refuses-the-default-gate.md#resolution-2026-09-15-triage): `bin/test:504–509` |
| 18. [the-agent-page-shows-a-run-as-one-sentence-and-never-its-forms](../../../seon/issues/archive/the-agent-page-shows-a-run-as-one-sentence-and-never-its-forms.md) | superseded | render-debug-page | [Evidence](../../../seon/issues/archive/the-agent-page-shows-a-run-as-one-sentence-and-never-its-forms.md#resolution-2026-09-15-triage): `src/seon/render/web.clj:3038–3057` |
| 19. [transaction-refusal-wrapper-hides-agent-running-rule](../../../seon/issues/archive/transaction-refusal-wrapper-hides-agent-running-rule.md) | resolved | turn-loop | [Evidence](../../../seon/issues/archive/transaction-refusal-wrapper-hides-agent-running-rule.md#resolution-2026-09-15-triage): `src/seon/error/refusal.clj:4–25` |
| 20. [transcript-about-lookup-passes-a-set-to-pull-many](../../../seon/issues/archive/transcript-about-lookup-passes-a-set-to-pull-many.md) | resolved | render-debug-page | [Evidence](../../../seon/issues/archive/transcript-about-lookup-passes-a-set-to-pull-many.md#resolution-2026-09-15-triage): `src/seon/render/transcript.clj:210–229` |
| 21. [turn-fork-omits-an-empty-agent-namespace](../../../seon/issues/archive/turn-fork-omits-an-empty-agent-namespace.md) | resolved | context-generation | [Evidence](../../../seon/issues/archive/turn-fork-omits-an-empty-agent-namespace.md#resolution-2026-09-15-triage): `src/seon/sci/eval.clj:1682–1708` |
| 22. [uncontracted-live-function-blocks-development-acquisition](../../../seon/issues/archive/uncontracted-live-function-blocks-development-acquisition.md) | resolved | adoption-publication | [Evidence](../../../seon/issues/archive/uncontracted-live-function-blocks-development-acquisition.md#resolution-2026-09-15-triage): `src/seon/turn.clj:3093–3098` |
| 23. [value-admission-resolves-the-declaration-population-per-node](../../../seon/issues/archive/value-admission-resolves-the-declaration-population-per-node.md) | resolved | context-generation | [Evidence](../../../seon/issues/archive/value-admission-resolves-the-declaration-population-per-node.md#resolution-2026-09-15-triage): `src/seon/sci/admit.clj:697–708` |
| 24. [virtual-turn-control-loses-agent-routing](../../../seon/issues/virtual-turn-control-loses-agent-routing.md) | unverifiable | render-debug-page | [Evidence](../../../seon/issues/virtual-turn-control-loses-agent-routing.md#re-verified-at-head-2026-09-15): `src/seon/cluster.clj:2774–2783` |
| 25. [walk-units-render-their-hiccup-as-escaped-edn-text](../../../seon/issues/archive/walk-units-render-their-hiccup-as-escaped-edn-text.md) | resolved | render-debug-page | [Evidence](../../../seon/issues/archive/walk-units-render-their-hiccup-as-escaped-edn-text.md#resolution-2026-09-15-triage): `src/seon/render.clj:1121–1126` |

## Confirmed-open blockers, ranked

**None.** No current live-run/context-generation blocker was reproduced among
these 25 notes. That does not certify the entire runtime or the two unverified
boundaries below.

### Confirmed friction, ranked by effect on context work

1. [Unhanded DB decoding](../../../seon/issues/seon-db-reads-rebuild-the-projection-per-call-when-none-is-handed.md):
   two read-only default pulls of root's id took **17,442.284416 ms** and
   **1,989.76425 ms**. Fix sketch: carry the acquired projection into entry
   points and reuse it against the observed database basis. Owners:
   `src/seon/db.clj` (`read-declarations`) and `src/seon/schema.clj`
   (`projection-from-database`). These timings include machine load and do not
   attribute every millisecond to compilation or prove ordinary supplied turns fail.
2. [Special SCI bindings](../../../seon/issues/sci-base-context-silently-hand-lists-special-callables.md):
   the duplicate membership source is gone; schema/background bootstrap
   exceptions remain manually chosen and unexplained. Fix sketch: use normal
   acquisition for ordinary functions and document necessary interpreter
   exceptions at the single declaration. Owners: `src/seon/program.cljc`,
   `src/seon/sci/eval.clj`, `src/seon/bootstrap.clj`.

### Unverified blocker boundaries, ordered for follow-up

1. [Store growth](../../../seon/issues/store-grew-to-69-gigabytes-in-one-day-of-lanes.md):
   the current **42,224,574,464 allocated bytes** are a single census. A
   same-store day-long comparison with publication/turn accounting and
   current/history/unreachable attribution is needed. Fix depends on that
   attribution; the GC permit alone does not provide recurring reclamation.
   Owner: `src/seon/cluster/registry.clj` and the measured writer, once identified.
2. [Virtual-turn control](../../../seon/issues/virtual-turn-control-loses-agent-routing.md):
   source routing is corrected and the live instance and view both contain
   routing, but the captured HTTP service/POST boundary was not exercised.
   **UNVERIFIABLE-WITHOUT-GATE**: next permitted gate is
   `bin/test-fast seon.turn-test`, specifically
   `virtual-turns-use-the-proc-and-compaction-is-agent-scoped`; a separately
   authorized disposable POST is needed for default's captured service.
   Fix sketch if the historical state remains: reconstruct that service with
   the existing routing carrier; do not introduce a second routing atom.
   Owners: `src/seon/cluster.clj`, `src/seon/render/web.clj`.

## Verification boundary and exact observations

- `bin/seon status` initially reported default PID **23729** alive at
  `http://127.0.0.1:7994`, PREPL **54412**. MCP advertises start instant
  **2026-09-15T04:24:19Z**. No default stop/refork/restart occurred.
- MCP `runtime_status` returned health and Flow **unknown**, error
  **Read timed out**. JVM `(+ 1 1)` returned **2 in 2 ms**. The recurring
  observation is appended to the existing
  [MCP timeout issue](../../../seon/issues/default-component-probe-times-out-after-adoption.md).
  Arithmetic availability does not establish Flow health or source adoption.
- Every live probe used MCP JVM mode on root `/Users/sean/src/seon`, cluster
  `default`; all were read-only. Exact forms and outputs are retained in the
  relevant issue sections. Refusal-chain preservation took **3 ms**; reading
  the injection declaration took **1 ms**; routing-presence observation took
  **2 ms**. No paid provider call or virtual-turn POST was made.
- Before the owner's CPU correction, launched exactly this focused fast run:

  ```sh
  bin/test-fast --paths docs/seon/issues/archive/prose-renders-splice-unquoted-into-printed-data.md -- seon.render.value-test seon.predicate-publication-test
  ```

  It used snapshot HEAD `859c9258c2865206af32111a850a850174919b64`, root
  `tmp/test-runs/run.8DbR9q`, JVM PID **6148**. Reported **981 instrumented**
  functions. Output reached `END namespace seon.render.value-test`, then
  `BEGIN test seon.predicate-publication-test/a-published-predicate-rename-forks-and-boots-with-its-tombstone`.
  **No final tally or successful suite exit was obtained.** By the time the
  correction was processed, the JVM was absent and the tool session no longer
  existed. This is an incomplete verification attempt, not a green gate.
  Source-based resolutions do not depend on claiming that attempt passed.
- After the CPU correction, launched **zero JVMs**: no test, fast-test,
  platform, Clojure or Babashka execution. No replacement snapshot gate.
  No platform-green claim. The owner prohibition overrides the ordinary lane
  gate rule. A later optional confirmation of the source-based predicate
  closure is `seon.predicate-publication-test`; the render regression namespace
  is `seon.render.value-test`.
- No foreign source failure was reproduced or attributed. Source checks used
  committed bytes; concurrent editor state was excluded from the fast snapshot.
  The final boundaries were the unknown MCP health observation and the owner's
  stop on further JVM launches, not a presumed defect in another lane's edits.
- Confirmed no matching live JVM held the owned snapshot, then deleted only
  `tmp/test-runs/run.8DbR9q` with non-symlink-following cleanup. No owned
  background process remains; all unrelated working-tree residue was preserved.

## Commits

- `859c9258c` — issues 1–5
- `91d5547b5` — issues 6–10
- `968a02c26` — issues 11–15
- `f3517083e` — issues 16–20
- `7536e893f` — issues 21–25

This landing commit also records the existing MCP timeout recurrence and labels
the two confirmed friction verdicts explicitly. Documentation verification checks
all 25 statuses, dated sections, surfaces, archive locations and landing links;
no code gate is represented as passing.
