---
type: orchestrator
status: active
tags: [orchestrator, issue, index, schedule]
---

# Open Issues — Ranked Schedule

This is the owner's execution schedule, verified against the 2026-07-29
checkout. Every open issue appears exactly once. Running lanes come first,
ordered by live-system impact; named future waves follow in dependency order.
Closed notes live in `archive/` and are not open schedule rows.

## Running lanes

| Rank | Classification | Named lane | Issue | Why now; owner/rung |
|---:|---|---|---|---|
| 1 | **PRESSING** | `contracts-quality-batch` | [Give every fresh public function a complete Malli contract](fresh-public-functions-lack-complete-malli-contracts.md) | The reader inventory still finds public fresh functions without complete contracts; schema/instrumentation quality rung. |
| 2 | **REAL-BUT-QUEUED** | `contracts-quality-batch` | [Name database-value and transaction-data contracts](database-and-transaction-boundaries-use-anonymous-any-contracts.md) | The unprotected conversions landed, but nine database-taking contracts remain in the refusal-hotloop lane's protected `seon.cluster.run`; database contract rung. |
| 3 | **REAL-BUT-QUEUED** | `lane-tooling-fix` | [The issues-index checker disagrees with the schedule convention](issues-index-checker-disagrees-with-the-schedule-convention.md) | `bin/issues-index --check` still demands its obsolete severity projection instead of validating schedule coverage; issue-tooling owner. |

## Named future waves

| Rank | Classification | Named wave | Issue | Why queued; owner/rung |
|---:|---|---|---|---|
| 4 | **PRESSING** | `parser-merge wave` | [Cold resume loses the defs and aliases the plan prefix established](cold-resume-loses-the-defs-and-aliases-the-plan-prefix-established.md) | Durable plan forms still retain source but not the namespace effects a resumed suffix needs; parser/reader merge boundary. |
| 5 | **DRAFT-SURFACE** | `render implementation wave` | [Make program graph render declarations resolvable](program-graph-render-declarations-name-absent-functions.md) | **UNBLOCKED:** program-graph facts exist; six advertised projection symbols remain unresolvable until the real render walk supplies them. |
| 6 | **DRAFT-SURFACE** | `render implementation wave` | [Unify the nested-data walk shared by admission and rendering](value-admission-render-walk-overlap.md) | Admission and value rendering still implement overlapping bounded descent with different required semantics; settle during the real render walk. |
| 7 | **REAL-BUT-QUEUED** | `test-dissolution waves` | [Publish graph transitions instead of polling them in tests](observable-graph-transitions-are-polled-in-tests.md) | Tests still infer observable Flow/database transitions with polling and sleeps; dissolve them as production owners publish completion/report events. |
| 8 | **REAL-BUT-QUEUED** | `deps/vendor review` | [Three smaller defects in the vendored Datahike, found beside the card-many scan bug](datahike-planner-and-caches-carry-three-smaller-defects.md) | Alpha-renaming can change plans, a cache dial is unread, and the CLJ card-many path runs both branches; vendored Datahike review. |
| 9 | **REAL-BUT-QUEUED** | `deps/vendor review` | [Align vendored Malli source with the pinned dependency](malli-vendor-is-ahead-of-pinned-dependency.md) | `deps.edn` remains on Malli 0.20.0 while `reference-code/malli` contains later unreleased source; dependency ledger review. |
| 10 | **REAL-BUT-QUEUED** | `config follow-up` | [Give Flow configuration dials one registration owner](flow-config-dials-have-two-registration-owners.md) | Quarry config still redeclares both fresh Flow dials on the explicit dual-tree writer classpath; configuration owner. |
