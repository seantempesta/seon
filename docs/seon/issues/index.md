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
| 1 | **PRESSING** | `refusal-hotloop-fix` | [Terminalize a receipt when its terminal transaction is refused](refused-terminal-transaction-leaves-a-running-receipt-hot-loop.md) | A refused terminal transaction still leaves a selectable running receipt and repeatedly commits `receipt-exists` errors; `seon.cluster.loop` terminal settlement. |
| 2 | **PRESSING** | `contracts-quality-batch` | [Give every fresh public function a complete Malli contract](fresh-public-functions-lack-complete-malli-contracts.md) | The reader inventory still finds public fresh functions without complete contracts; schema/instrumentation quality rung. |
| 3 | **REAL-BUT-QUEUED** | `contracts-quality-batch` | [Replace bare flow callback predicates with honest contracts](flow-callback-schemas-are-not-generatively-constructible.md) | `schema/flow.edn` still uses bare callback predicates outside the generative contract gate; Flow contract owner. |
| 4 | **REAL-BUT-QUEUED** | `contracts-quality-batch` | [Mixed-union Datahike declaration lacks the fresh EDN codec](mixed-union-datahike-declaration-lacks-fresh-edn-codec.md) | The bridge still maps heterogeneous unions to strings without the matching fresh encode/decode boundary; schema-EDN transaction rung. |
| 5 | **REAL-BUT-QUEUED** | `contracts-quality-batch` | [Name database-value and transaction-data contracts](database-and-transaction-boundaries-use-anonymous-any-contracts.md) | Database values and transaction data still cross many `:any` contracts despite existing dependency predicates and a transaction-data owner; database contract rung. |
| 6 | **REAL-BUT-QUEUED** | `contracts-quality-batch` | [Derive repeated state vocabularies from their owning schemas](derived-state-contracts-repeat-hand-maintained-enums.md) | Render bands and settled form states still have hand-maintained duplicate enums; derive them at the schema/work owners. |
| 7 | **REAL-BUT-QUEUED** | `skills-update` | [Re-ground schema skills in the fresh EDN and instrumentation system](schema-skills-teach-the-retired-registration-model.md) | The skills still teach retired direct registration and stale instrumentation/omission rules; skill authority update. |
| 8 | **REAL-BUT-QUEUED** | `skills-update` | [The review hook's rubric lags the omission ruling](review-rubric-lags-the-omission-ruling.md) | The hook still reports valid in-memory `[:maybe]` and valid Datahike transaction forms as defects; review-rubric owner. |
| 9 | **REAL-BUT-QUEUED** | `small-correctness-batch` | [Stop fallback kills innocent shared-JVM clusters](stop-fallback-kills-innocent-shared-jvm-clusters.md) | A failed PREPL stop can still SIGTERM a process hosting sibling clusters; fresh operator stop boundary. |
| 10 | **REAL-BUT-QUEUED** | `small-correctness-batch` | [stop! may leave the prepl server name registered](stop-may-leave-the-prepl-server-name-registered.md) | Current `cluster/stop!` closes the socket directly while start/failure cleanup use `clojure.core.server`'s named registry; cluster lifecycle owner. |
| 11 | **REAL-BUT-QUEUED** | `small-correctness-batch` | [A failed ephemeral bind NPEs instead of saying what happened](web-start-npe-when-an-ephemeral-bind-fails.md) | `render.web/start!` still binds `server` to nil after a port-0 `BindException`, losing the cause in an NPE; web start boundary. |
| 12 | **REAL-BUT-QUEUED** | `small-correctness-batch` | [Give (pid, start-instant) liveness one owner](process-liveness-check-has-no-single-owner.md) | Process identity production/checking remains private in `cluster.clj` while ancestor recovery needs the same rule; process-liveness owner. |
| 13 | **REAL-BUT-QUEUED** | `small-correctness-batch` | [Prevent a detached load drive from restarting after success](detached-load-launch-restarts-after-success.md) | Retained evidence shows the launch job restarted after the accepted drive completed; load-harness lifecycle owner. |
| 14 | **REAL-BUT-QUEUED** | `small-correctness-batch` | [Flow monitor test preselects an unreserved port](flow-monitor-test-preselects-an-unreserved-port.md) | The test still closes `ServerSocket(0)` before the monitor binds the selected port; Flow-monitor test seam. |
| 15 | **REAL-BUT-QUEUED** | `small-correctness-batch` | [A slow-tab proof can count a late initial derivation](a-slow-tab-proof-can-count-a-late-initial-derivation.md) | The proof still samples before initial render settlement and can count one late pass; render Flow proof. |
| 16 | **REAL-BUT-QUEUED** | `small-correctness-batch` | [Refresh instrumentation before the fresh operator calls start](fresh-operator-start-enters-stale-instrumentation-before-refresh.md) | The operator still calls `cluster/start!` before refreshing the live wrapper, so an old contract can reject current input; instrumentation/start seam. |
| 17 | **REAL-BUT-QUEUED** | `small-correctness-batch` | [An agent can be assigned its own red form, and the loop delivers it](an-agent-can-be-assigned-its-own-red-form.md) | Terminal assembly still lacks the self-owner omission rule and can emit a no-information message to the author; problem-routing owner. |
| 18 | **REAL-BUT-QUEUED** | `lane-tooling-fix` | [Lane discipline: unverified stops and cross-lane steering](lane-discipline-stop-verification-and-cross-lane-steering.md) | The harness can resume a still-live session and the lane template lacks the cross-lane stop rule; `bin/codex-agent` discipline. |
| 19 | **REAL-BUT-QUEUED** | `hygiene-batch` | [Remove the stale program-graph owner rename](architecture-program-graph-owner-rename-is-stale.md) | Architecture docs still promise `seon.code.*` after the owner retained top-level `:seon.fn`, `:seon.ns`, and `:seon.schema`; architecture vocabulary. |
| 20 | **REAL-BUT-QUEUED** | `hygiene-batch` | [Block attribute vocabulary splits across architecture docs](block-attribute-vocabulary-splits-across-architecture-docs.md) | Architecture still mixes the settled block vocabulary with stale seed-copy and imperative install/remove language; UI architecture owner. |
| 21 | **REAL-BUT-QUEUED** | `hygiene-batch` | [Route ordinary stderr presentations through log renders](stderr-presentations-bypass-the-log-render-kind.md) | Cluster, export, and instrumentation still carry separate ordinary stderr formatters beside the existing log render; error/log projection owner. |
| 22 | **REAL-BUT-QUEUED** | `hygiene-batch` | [The design language's font is redistributed without its license, and only at one weight](the-bundled-font-has-no-license-and-only-one-weight.md) | The release evidence still has one unlicensed JetBrains Mono weight and no tracked font/license pair; asset packaging owner. |
| 23 | **REAL-BUT-QUEUED** | `hygiene-batch` | [Keep the old source tree off Babashka's default classpath](babashka-default-classpath-exposes-src-old.md) | `bb.edn` still exposes `src-old` by default while `deps.edn` correctly gates it behind quarry aliases; Babashka project config. |

## Named future waves

| Rank | Classification | Named wave | Issue | Why queued; owner/rung |
|---:|---|---|---|---|
| 24 | **PRESSING** | `parser-merge wave` | [Cold resume loses the defs and aliases the plan prefix established](cold-resume-loses-the-defs-and-aliases-the-plan-prefix-established.md) | Durable plan forms still retain source but not the namespace effects a resumed suffix needs; parser/reader merge boundary. |
| 25 | **DRAFT-SURFACE** | `render implementation wave` | [Make program graph render declarations resolvable](program-graph-render-declarations-name-absent-functions.md) | **UNBLOCKED:** program-graph facts exist; six advertised projection symbols remain unresolvable until the real render walk supplies them. |
| 26 | **DRAFT-SURFACE** | `render implementation wave` | [Unify the nested-data walk shared by admission and rendering](value-admission-render-walk-overlap.md) | Admission and value rendering still implement overlapping bounded descent with different required semantics; settle during the real render walk. |
| 27 | **REAL-BUT-QUEUED** | `test-dissolution waves` | [Publish graph transitions instead of polling them in tests](observable-graph-transitions-are-polled-in-tests.md) | Tests still infer observable Flow/database transitions with polling and sleeps; dissolve them as production owners publish completion/report events. |
| 28 | **REAL-BUT-QUEUED** | `deps/vendor review` | [Three smaller defects in the vendored Datahike, found beside the card-many scan bug](datahike-planner-and-caches-carry-three-smaller-defects.md) | Alpha-renaming can change plans, a cache dial is unread, and the CLJ card-many path runs both branches; vendored Datahike review. |
| 29 | **REAL-BUT-QUEUED** | `deps/vendor review` | [Align vendored Malli source with the pinned dependency](malli-vendor-is-ahead-of-pinned-dependency.md) | `deps.edn` remains on Malli 0.20.0 while `reference-code/malli` contains later unreleased source; dependency ledger review. |
| 30 | **REAL-BUT-QUEUED** | `config follow-up` | [Give Flow configuration dials one registration owner](flow-config-dials-have-two-registration-owners.md) | `68a5de51f` derived one effective manifest, but quarry config still redeclares both fresh Flow dials on the explicit dual-tree writer classpath. |

## Completed named lane result

`renderer-config-fact-fix` has no open row: commit `102a75038` resolved and
archived
[Value renderer caches file-derived effective config](archive/value-renderer-caches-file-derived-effective-config.md).
