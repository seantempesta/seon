---
type: research
status: complete
tags: [research, docs, architecture]
---

# `AGENTS.md` reorganization idea catalogue — 2026-08-13

## Scope and disposition rules

I read root [`AGENTS.md`](../../../../AGENTS.md) and the complete
[`AGENTS.md` verification audit](agents-md-verification-audit-2026-08-13.md)
end to end before making this catalogue. The unit is one idea that must either
survive once, move to a named owner, or die with evidence. Duplicate statements
share one stable id and list every source range.

The eight in-file destinations are the approved rewrite sections. A stale row
stays only when the audit established a sound surviving rule or mechanism and
the stale part is its example, scope, status, count, or citation; those rows say
**re-ground**. Unverifiable process rulings stay because repository state is not
the evidence source for an owner-directed workflow. Section 2 notes name the
organizing design law each contributing idea feeds.

## Catalogue

### 1-orientation

| id | kind | source lines | verdict | destination | treatment | note |
|---|---|---|---|---|---|---|
| `ptr/root-instruction-authority` | pointer | `AGENTS.md:3-6,229-245` | verified | 1-orientation | prose | Root authority, compatibility link, and localization model; harness-specific discovery is separately retained as a process ruling. |
| `workflow/subagent-executes-assignment` | workflow | `AGENTS.md:8-10` | verified | 1-orientation | prose | A delegated lane executes directly and asks the top level to rescope if necessary. |
| `workflow/top-level-integrates` | workflow | `AGENTS.md:12-18,182-191` | verified | 1-orientation | prose | Top level owns communication, cross-boundary judgment, integration, and review of lane claims. |
| `law/old-system-is-history` | law | `AGENTS.md:22-31,247-254,1061-1062` | verified | 1-orientation | prose | CLJ-only JVM system; deleted self-host/child and quarry paths remain Git archaeology. |
| `law/simplification-not-relocation` | law | `AGENTS.md:33-51` | verified | 1-orientation | prose+example | A conversion must simplify; do not port an old-model shape merely to a new owner. |
| `workflow/cut-wave-before-seam-repair` | workflow | `AGENTS.md:53-64` | verified | 1-orientation | prose | Finish a declared deletion wave, record independent seam defects, then repair against the completed cut. |
| `law/seon-core-boundary` | law | `AGENTS.md:410-411` | verified | 1-orientation | prose | Consumer UI, vendor integrations, and domain models remain downstream of Seon core. |
| `law/local-trusted-collaborators` | law | `AGENTS.md:1493-1504` | verified | 1-orientation | prose | Full local capability; restrictions require evidence of a real problem, while honest mistakes get bounded, explicit failures. |

### 2-design-laws

| id | kind | source lines | verdict | destination | treatment | note |
|---|---|---|---|---|---|---|
| `law/values-carry-world` | law | `AGENTS.md:589-623,672,939-975,1055-1058` | verified | 2-design-laws | prose+example | **values-carry-world** spine: environment, validation, custody, and durable meaning travel with the value or work they govern. |
| `law/facts-over-inference` | law | `AGENTS.md:781-824,939-975,1018-1032` | verified | 2-design-laws | prose+example | **facts-over-inference** spine: declare/query facts; hand lists, naming rules, and text inference signal a missing fact. |
| `law/events-with-loud-backstops` | law | `AGENTS.md:76-77,1004-1017,1204-1210` | verified | 2-design-laws | prose+example | **events-with-loud-backstops** spine: observable completion is event-driven; a clock is only a diagnostic backstop. |
| `law/total-honest-boundaries` | law | `AGENTS.md:329-338,589-623,1018-1025` | verified | 2-design-laws | prose+example | **total-honest-boundaries** spine: flat typed errors for agent mistakes, durable core faults, and no success-shaped fallback. |
| `law/one-mechanism-accreted` | law | `AGENTS.md:20-64,624-661,843-906` | verified | 2-design-laws | prose+example | **one-mechanism-accreted** spine: strengthen one owner, add without redefining existing keys, and delete superseded paths. |
| `law/three-effect-shapes` | law | `AGENTS.md:36-49,700-701` | stale | 2-design-laws | prose+example | **values-carry-world; one-mechanism-accreted. Re-ground:** values, capability requests, and durable facts survive; delete the false claim that all db writes enter `seon.effect/request!`. |
| `law/every-program-function-callable` | law | `AGENTS.md:40-43,701,1497-1502` | verified | 2-design-laws | prose | **one-mechanism-accreted:** rendering and effect boundaries never become per-agent call grants. |
| `law/durable-facts-losable-channels` | law | `AGENTS.md:314-328,704,958-975` | verified | 2-design-laws | prose+example | **values-carry-world:** recovery facts are durable; freely losable or superseded values ride buffers that encode loss semantics. |
| `law/nothing-reexecutes` | law | `AGENTS.md:324-329` | verified | 2-design-laws | prose | **values-carry-world:** recovery marks interrupted work and re-derives context; it does not replay effects. |
| `law/environment-rides-work` | law | `AGENTS.md:618-622,672` | verified | 2-design-laws | prose+example | **values-carry-world:** running code receives the per-cluster environment through explicit work/context values. |
| `law/fix-failure-class` | law | `AGENTS.md:593-603,1270-1292` | verified | 2-design-laws | prose+example | **total-honest-boundaries; one-mechanism-accreted:** restructure the choke point so the class cannot be written; keep one recurring regression. |
| `law/make-wrong-state-unconstructable` | law | `AGENTS.md:598-603` | verified | 2-design-laws | prose+example | **total-honest-boundaries:** prefer absence of a forgettable field or argument to repeated validation. |
| `law/loud-flat-refusals` | law | `AGENTS.md:604-610,835,1018-1025` | verified | 2-design-laws | prose+example | **total-honest-boundaries:** name the failed layer/member/key; no silent default. |
| `law/diagnostics-tell-truth` | law | `AGENTS.md:215-227,611-617` | verified | 2-design-laws | prose+example | **total-honest-boundaries:** verify attribution; virtual-thread-aware evidence is required for thread diagnosis. |
| `law/derived-state-rides-source-value` | law | `AGENTS.md:618-622` | verified | 2-design-laws | prose+example | **values-carry-world:** validator/projection, writer/connection, and index/database value remain structurally paired. |
| `workflow/three-option-design-gate` | workflow | `AGENTS.md:626-640` | verified | 2-design-laws | prose | **one-mechanism-accreted:** stop before cross-owner semantic complexity and request a ruling on exactly three costed options. |
| `ban/parallel-mechanism` | ban | `AGENTS.md:642-645,789-796` | unverifiable | 2-design-laws | prose | **one-mechanism-accreted; process ruling:** prohibition survives even though semantic absence of every duplicate cannot be tree-proved. |
| `ban/model-output-symptom-layers` | ban | `AGENTS.md:647-650,1041-1044` | verified | 2-design-laws | prose | **total-honest-boundaries:** fix context/code causes; do not rewrite, scold, mark, or post-process model output. |
| `workflow/record-discovered-defect` | workflow | `AGENTS.md:652-661` | verified | 2-design-laws | prose | **facts-over-inference:** record evidence, owner, and acceptance criteria in the issue authority; chat is not the record. |
| `ban/regex-requires-permission` | ban | `AGENTS.md:1033-1046` | verified | 2-design-laws | prose+example | **facts-over-inference:** production regex requires an owner ruling; use parsed forms, schemas, program facts, or Datalog. |
| `law/open-maps-accrete` | law | `AGENTS.md:826-906` | verified | 2-design-laws | prose+example | **one-mechanism-accreted:** required keys validate, extra undeclared keys are ignored, and `{:closed true}` is not used. |
| `law/key-meaning-never-drifts` | law | `AGENTS.md:879-901` | verified | 2-design-laws | prose+example | **one-mechanism-accreted; total-honest-boundaries:** new semantics require a new key; narrowing input or promising less is breakage. |

### 3-data-and-schema

| id | kind | source lines | verdict | destination | treatment | note |
|---|---|---|---|---|---|---|
| `workflow/schema-registry-first` | workflow | `AGENTS.md:797-803,836-837,912-915` | verified | 3-data-and-schema | prose+example | Query the merged global registry before declaring a key; fix the bridge rather than inline duplicate shapes. |
| `mech/entities-are-attributes` | mechanism | `AGENTS.md:679,908-910` | verified | 3-data-and-schema | prose+example | Entity identity and membership derive from attributes/connections, never a kind/type stamp. |
| `ban/data-shape-antipatterns` | ban | `AGENTS.md:826-841` | verified | 3-data-and-schema | prose | No stored nil, maybe-for-absence, bare keys, kind taxonomy, or unjustified `:any`. |
| `mech/public-malli-contracts` | mechanism | `AGENTS.md:391-399,834-840` | verified | 3-data-and-schema | prose | Durable definitions and public source functions have complete input/output contracts. |
| `mech/seon-db-one-namespace` | mechanism | `AGENTS.md:671,727,917-937` | stale | 3-data-and-schema | prose+example | **Re-ground:** dual Datahike interfaces, custody elision, flat failures, and `transact!` ownership survive; remove the false universal confinement roster for direct `datahike.api` reads. |
| `mech/database-value-identities` | mechanism | `AGENTS.md:734-753` | stale | 3-data-and-schema | prose+example | **Re-ground:** distinguish committed identity (`:db-name`, `:t`, commit ID) from temporal/speculative values instead of promising one universal field set. |
| `mech/connection-id` | mechanism | `AGENTS.md:741-743` | verified | 3-data-and-schema | one-line+link | Datahike connection ID is `[store-id branch]` for a self writer and includes backend for remote writers. |
| `mech/config-is-facts` | mechanism | `AGENTS.md:273-278,939-943` | verified | 3-data-and-schema | prose | Explicit manifests reconcile subsets; reopen can omit config; converged apply writes nothing; running reads database facts. |
| `mech/minimal-transaction-provenance` | mechanism | `AGENTS.md:945-947` | verified | 3-data-and-schema | prose | Use resolvable transaction metadata, not copied created-by/created-at domain fields. |
| `mech/program-graph-facts` | mechanism | `AGENTS.md:702,781-824,977-983` | verified | 3-data-and-schema | prose+example | Function, namespace, schema, test, call, privacy, workload, and sink information is one queryable graph. |
| `mech/tests-reaching` | mechanism | `AGENTS.md:814-823,1301-1303` | verified | 3-data-and-schema | prose+example | Test selection derives transitively from recorded calls, never filename or naming convention. |
| `mech/render-contract-properties` | mechanism | `AGENTS.md:707-709,949-956` | verified | 3-data-and-schema | prose+example | Schemas declare AI, HTML, and form render contracts; computed renders use qualified named functions. |
| `mech/identity-only-admission` | mechanism | `AGENTS.md:720` | verified | 3-data-and-schema | one-line+link | Registered identity-only predicates retain only their qualified identity projection; update drifted citations. |
| `mech/datahike-tuple` | mechanism | `AGENTS.md:705` | verified | 3-data-and-schema | one-line+link | One ordered tuple datom, whole-value replacement, homogeneous cap eight; cardinality-many remains a set. |
| `mech/provider-descriptor-row` | mechanism | `AGENTS.md:683,1439-1445` | verified | 3-data-and-schema | one-line+link | Hosted-provider configuration is database data under the config singleton. |
| `mech/output-path-facts` | mechanism | `AGENTS.md:719` | verified | 3-data-and-schema | one-line+link | External-sink and projection-boundary metadata support derived shortest-path classifications; update citations. |
| `mech/source-initialization-data` | mechanism | `AGENTS.md:695` | verified | 3-data-and-schema | prose+example | Static source rows are transaction data; a fresh opening derives generated entries from live facts and receipts. |

### 4-building

| id | kind | source lines | verdict | destination | treatment | note |
|---|---|---|---|---|---|---|
| `workflow/dependency-ledger-first` | workflow | `AGENTS.md:101-103,551-568` | unverifiable | 4-building | prose | Process ruling: name pins, vendored paths, first-party idioms, and the shortest falsifier before design. |
| `workflow/read-maintained-dependency-source` | workflow | `AGENTS.md:125-132,551-568,755-773,1158-1160` | verified | 4-building | prose | Read both sides of an interface and use dependency vocabulary; do not design from remembered APIs. |
| `workflow/data-oriented-clojure-first` | workflow | `AGENTS.md:572-578,826-841,1103-1113` | verified | 4-building | one-line+link | Load the skill before planning, writing, or reviewing Seon Clojure. |
| `workflow/repl-first-development` | workflow | `AGENTS.md:1134-1177` | unverifiable | 4-building | prose+example | Process ruling: start from a live, smallest falsifier; edit one owner; repeat the same probe; then persist proof. |
| `workflow/live-proof-after-tests` | workflow | `AGENTS.md:584-587,1169-1173,1286-1292` | verified | 4-building | prose | Verify a datom/page/feed/log/transition; fixture proof does not replace reset/reopen proof. |
| `mech/boot-tower` | mechanism | `AGENTS.md:249-290` | verified | 4-building | prose+example | One JVM; process → store → facts → Flow, each layer reading only below and publishing readiness. |
| `mech/process-root-store-fence` | mechanism | `AGENTS.md:264-272,405-408` | verified | 4-building | prose | One process root holds the Datahike store-wide `flock`; clusters are named branches with separate connections. |
| `scar/two-jvms-lost-commits` | scar | `AGENTS.md:268-269` | verified | 4-building | one-line+link | Link retained `f2-live-render-proof-2026-07-28.md`; do not retain incident narration inline. |
| `mech/current-src-publication` | mechanism | `AGENTS.md:273-278,300-312,1377-1398` | verified | 4-building | prose+example | Safe same-identity incremental publication; structural uncertainty selects complete publication; existing clusters stay sovereign. |
| `mech/per-agent-flow-graph` | mechanism | `AGENTS.md:279-290` | verified | 4-building | prose | One Flow graph per agent; no central dispatcher/scheduler. |
| `mech/agent-flow-three-procs` | mechanism | `AGENTS.md:279-284` | stale | 4-building | prose | **Re-ground:** replace “two procs” with current mailbox, turn, and schedule proc topology. |
| `mech/cluster-flow-plumbing` | mechanism | `AGENTS.md:283-290` | stale | 4-building | prose | **Re-ground:** include render, fault, armer/search, and work-launcher owners; do not preserve an exhaustive hand roster beyond source links. |
| `mech/proc-workload-tags` | mechanism | `AGENTS.md:287-290,339-351,692,1049-1053` | stale | 4-building | prose+example | **Re-ground:** explicit proc `:io`/`:compute` and eval/effect seams survive; delete nonexistent transitive function workload derivation. |
| `scar/parked-proc-measurement` | scar | `AGENTS.md:280-282` | verified | 4-building | one-line+link | Link the qualified one-proc ~8.5 KB baseline; do not present it as whole-agent cost. |
| `scar/flow-topology-rebuild` | scar | `AGENTS.md:292-298` | verified | 4-building | one-line+link | Link the conditioned 0.343 ms median measurement; retain the stop/create/start mechanism. |
| `mech/sci-time-output-contracts` | mechanism | `AGENTS.md:391-399,686-690,1004-1017` | verified | 4-building | prose+example | One `:interrupt-fn`, time limit and output caps; fn-entry counts are diagnostics, not fuel or step limits. |
| `mech/sci-context-forks` | mechanism | `AGENTS.md:685,691,714-715` | verified | 4-building | prose+example | Distinguish base `ctx`, fresh turn fork, rendered agent context, and copy-on-write candidate contexts; update citations. |
| `mech/schema-predicate-compilation` | mechanism | `AGENTS.md:400-402` | verified | 4-building | one-line+link | System-side writer resolves core predicates with `requiring-resolve`; it does not depend on SCI. |
| `mech/preprocess-apply-resume` | mechanism | `AGENTS.md:697` | stale | 4-building | prose | **Re-ground:** replace “until owners land” research-only status with current environment/source owners and exact operations. |
| `mech/generated-opening-run-loop` | mechanism | `AGENTS.md:698-699` | verified | 4-building | prose+example | The run loop advances generated opening entries or an authored reply's ordered forms through current tx owners. |
| `mech/session-curation-proof` | mechanism | `AGENTS.md:716` | verified | 4-building | one-line+link | Editor produces revision data; system re-executes proof on a fresh fork; clean proof adopts through `supersedes`. |
| `mech/agent-defs-and-history` | mechanism | `AGENTS.md:723-724,984-991` | verified | 4-building | prose | Defs/atoms settle through admission and rehydrate; history derives ordered form/printed-value entries. |
| `mech/instrumentation-from-program-facts` | mechanism | `AGENTS.md:1047-1049` | verified | 4-building | prose | Derive instrumentation from program facts and reapply it on hot reload. |
| `mech/edit-hook` | mechanism | `AGENTS.md:1238-1269` | verified | 4-building | prose+example | Pre/post analysis, Markdown/docstring checks, publication, fault checks, and coalesced async review; never tests. |

### 5-testing

| id | kind | source lines | verdict | destination | treatment | note |
|---|---|---|---|---|---|---|
| `mech/mcp-three-tool-surface` | mechanism | `AGENTS.md:1218-1237` | stale | 5-testing | prose | **Re-ground:** exact tools are `runtime_status`, `eval_clj` (`jvm`/`door`), and `get_value`; JVM only. |
| `mech/mcp-live-discovery` | mechanism | `AGENTS.md:1218-1232` | verified | 5-testing | prose | Refresh advertisements on every call and refuse ambiguous cluster selection. |
| `mech/mcp-session-state` | mechanism | `AGENTS.md:1221-1232` | verified | 5-testing | prose | Named sessions hold process-local REPL state only; restart never implies database loss. |
| `workflow/restart-client-after-mcp-change` | workflow | `AGENTS.md:1227-1237` | unverifiable | 5-testing | prose | Process ruling: resumed clients keep their initial MCP binding; start a fresh task after registration/schema changes. |
| `workflow/bin-test-one-gate` | workflow | `AGENTS.md:1256-1267,1294-1316` | verified | 5-testing | prose+example | Platform-first tiers; bare changed-since-green; exact `--all`, `--full`, `--platform`, `--changed`, and namespace semantics. |
| `mech/changed-test-shells-bin-test` | mechanism | `AGENTS.md:1264-1267` | verified | 5-testing | one-line+link | `run-changed!` shells the one gate and owns no selector. |
| `workflow/canonical-database-fixture` | workflow | `AGENTS.md:1187-1192` | verified | 5-testing | prose | Use complete `with-database` population plus synthetic `extra-schema`; never hand-roster schema. |
| `workflow/fixtures-carry-world` | workflow | `AGENTS.md:1193-1203` | verified | 5-testing | prose+example | Pass projection/environment and every declared proc input explicitly. |
| `workflow/fixed-render-profile-fixtures` | workflow | `AGENTS.md:1204-1207` | verified | 5-testing | prose+example | Supply the shipped profile; derive-per-call caused the retained 217 s versus 6.2 s scar. |
| `workflow/bounded-loud-awaits` | workflow | `AGENTS.md:1208-1213` | verified | 5-testing | prose | All event waits use the declared backstop and fail with a diagnostic. |
| `workflow/assert-current-ruled-behavior` | workflow | `AGENTS.md:1214-1217` | verified | 5-testing | prose | Typed refusals, terminal triggers, and bounded total renders replace stale lenient expectations. |
| `scar/test-fixture-repair-wave` | scar | `AGENTS.md:1182-1217` | verified | 5-testing | one-line+link | Keep one link to the fixture skill/working-edge evidence; remove twelve commit hashes from root prose. |
| `workflow/recurring-proof-only` | workflow | `AGENTS.md:1270-1292` | verified | 5-testing | prose | One regression per failure class, runner-discoverable; boot/acquisition/process changes also need live reset/reopen proof. |

### 6-operating

| id | kind | source lines | verdict | destination | treatment | note |
|---|---|---|---|---|---|---|
| `mech/bin-seon-inventory` | mechanism | `AGENTS.md:1369-1385` | stale | 6-operating | prose+example | **Re-ground:** add current `export` and `logs`; keep exact command/argument semantics sourced to the operator. |
| `mech/bin-seon-default-force-semantics` | mechanism | `AGENTS.md:1387-1406` | verified | 6-operating | prose+example | Default cluster rules, destructive refork/reset, all-root down, and force escalation remain exact. |
| `mech/process-identity-and-shutdown` | mechanism | `AGENTS.md:259-263,696,1395-1403` | verified | 6-operating | prose | Process record uses pid/start-instant/generation; stop/down exact-rematch before escalation. |
| `mech/run-custody-presence` | mechanism | `AGENTS.md:324-329,693` | verified | 6-operating | prose | `:seon.cluster.run/process` presence is custody; no claimant, epoch, lease, or heartbeat. |
| `mech/bin-acme-root-wrapper` | mechanism | `AGENTS.md:1429-1435` | verified | 6-operating | one-line+link | Fixed isolated root/cluster with only the documented fresh operator verbs. |
| `mech/default-ai-provider` | mechanism | `AGENTS.md:1437-1445` | verified | 6-operating | prose | DeepSeek through `seon.ai`; schema-derived cluster/per-agent settings; only credential variable name is data; attempts record effective settings/usage. |
| `mech/root-maintenance-portfolio` | mechanism | `AGENTS.md:721` | stale | 6-operating | prose | **Re-ground:** remove `[TARGET]`; five scheduled maintenance tasks, per-agent schedule proc, maintenance projection, and schemas are built. |
| `scar/fresh-cluster-fork-cost` | scar | `AGENTS.md:1140-1145` | verified | 6-operating | one-line+link | Link the qualified ~17 ms branch-fork measurement; do not use it as an unconditional SLA. |
| `workflow/churn-is-weather` | workflow | `AGENTS.md:1319-1350` | verified | 6-operating | prose+example | Re-derive state after process/tree churn; only a real implementation dependency blocks the unit. |
| `workflow/operator-root-isolation` | workflow | `AGENTS.md:1334-1338,1369-1371,1404-1406` | verified | 6-operating | prose | Destructive drills and independent deployments use `--root`; never another checkout or shared root. |
| `scar/symlink-recursive-deletion` | scar | `AGENTS.md:1352-1362` | verified | 6-operating | one-line+link | Link the 55-path incident and sentinel regression; retain only the no-follow/no-escape rule inline. |
| `ban/system-temp-work` | ban | `AGENTS.md:1408-1427` | verified | 6-operating | prose+example | Probes go in repository `tmp/`; reproducible evidence and recurring harnesses become committed research/tests. |
| `workflow/derive-current-operating-state` | workflow | `AGENTS.md:1364-1365` | verified | 6-operating | prose | Trust current records, advertisements, processes, database facts, and tree state rather than memory. |

### 7-collaborating

| id | kind | source lines | verdict | destination | treatment | note |
|---|---|---|---|---|---|---|
| `law/complete-program-ledger` | law | `AGENTS.md:66-86,88-199,539-546` | unverifiable | 7-collaborating | prose | Process ruling: one ordered dependency spine, visible complete outcome, parallel portfolio, and continual refill. |
| `workflow/coherent-lane-portfolio` | workflow | `AGENTS.md:96-108,182-191,579-583` | unverifiable | 7-collaborating | prose | Process ruling: one semantic question per lane, non-overlapping owners, bounded deliverable, and prompt review. |
| `workflow/program-reconciliation-clock` | workflow | `AGENTS.md:144-180` | unverifiable | 7-collaborating | prose | Process ruling: reconcile after returns/discoveries/commits; update evidence and next exits before rescheduling. |
| `workflow/coordinated-source-freeze` | workflow | `AGENTS.md:117-123` | verified | 7-collaborating | prose | Freeze build-input owners for a checkpoint, count it only if inputs stay stable, and use supervisor shutdown. |
| `workflow/upstream-delta-sweep` | workflow | `AGENTS.md:125-132` | unverifiable | 7-collaborating | prose | Process ruling: inspect both post-pin upstream and unevaluated pinned work; gate adoption with local falsifiers. |
| `workflow/independent-landing-audit` | workflow | `AGENTS.md:134-142,1115-1130` | unverifiable | 7-collaborating | prose | Process ruling: after major waves and skill changes, verify independently for known failure classes and calibrated good state. |
| `workflow/foreign-breakage-does-not-block-commit` | workflow | `AGENTS.md:201-213,1064-1099` | verified | 7-collaborating | prose | Foreign edits may block verification, never a coherent path-limited commit; name the exact boundary and stop. |
| `workflow/verify-before-attribution` | workflow | `AGENTS.md:215-227` | verified | 7-collaborating | prose | Treat cause as hypothesis until the shortest diagnostic probe confirms it. |
| `workflow/read-local-instructions` | workflow | `AGENTS.md:229-245` | unverifiable | 7-collaborating | prose | Process ruling: read closest `AGENTS.md`, never edit `CLAUDE.md`, and keep localized authorities tight and durable. |
| `mech/claude-codex-agent-wrapper` | mechanism | `AGENTS.md:473-536` | verified | 7-collaborating | one-line+link | Claude uses tracked `bin/codex-agent`; exact commands, summaries, logs, and resume behavior move behind the linked runbook. |
| `workflow/codex-native-lanes` | workflow | `AGENTS.md:469-492` | unverifiable | 7-collaborating | prose | Process ruling: Codex orchestrators use native collaboration tools and do not create substitute wrapper lanes. |
| `ban/lane-sandbox` | ban | `AGENTS.md:494-502` | unverifiable | 7-collaborating | prose | Process ruling: enforce audit scope through owned/protected paths and reviewed diffs, not an unwritable lane. |
| `workflow/path-limited-shared-tree-commit` | workflow | `AGENTS.md:201-213,494-502,1064-1099` | verified | 7-collaborating | prose+example | Preserve foreign work, stage explicit paths, and commit with `--only`; no broad staging or cleanup. |
| `ban/destructive-git-without-coordination` | ban | `AGENTS.md:1093-1099` | verified | 7-collaborating | prose | Branch switches, history edits, resets, and discards require user coordination. |
| `workflow/research-one-coherent-question` | workflow | `AGENTS.md:533-537,579-583` | verified | 7-collaborating | prose | One agent receives complete context for one question; independent source domains may proceed separately. |
| `workflow/report-linked-repository-paths` | workflow | `AGENTS.md:1471-1478` | verified | 7-collaborating | one-line+link | Every named document in an owner report is a full repository-relative Markdown link. |
| `workflow/read-named-authority-end-to-end` | workflow | `AGENTS.md:463-468,1480-1491` | verified | 7-collaborating | prose | Consume every named spec/ruling/report/plan in full; `rg` finds documents but does not replace reading them. |
| `workflow/neutral-lane-language` | workflow | `AGENTS.md:1462-1469` | unverifiable | 7-collaborating | one-line+link | Process ruling: use verify/falsify/probe language in lane specifications. |

### 8-pointers

| id | kind | source lines | verdict | destination | treatment | note |
|---|---|---|---|---|---|---|
| `ptr/architecture-authority` | pointer | `AGENTS.md:415-425,442-455,1060-1062,1449-1455` | verified | 8-pointers | one-line+link | Target architecture map and domain owners. |
| `ptr/active-program-ledger` | pointer | `AGENTS.md:426-440,1451-1454` | stale | 8-pointers | one-line+link | **Re-ground:** `plan/README.md` plus `unsettled.md` is the current ledger; do not repeat the false universal `roadmap.md` ownership claim. |
| `ptr/localized-runtime-runbook` | pointer | `AGENTS.md:435-440,1454` | verified | 8-pointers | one-line+link | Closest runtime-program instructions and research directory. |
| `ptr/transfer-prompt` | pointer | `AGENTS.md:354-367,1447-1450` | verified | 8-pointers | one-line+link | Newcomer orientation; read whole. |
| `ptr/conventions-and-skills` | pointer | `AGENTS.md:450-454,1103-1113,1455` | verified | 8-pointers | one-line+link | Code/schema conventions and task-specific skill owners. |
| `ptr/library-grounding` | pointer | `AGENTS.md:354-367,1060-1062,1456-1457` | verified | 8-pointers | one-line+link | Current first-party/dependency read map and vendored sources. |
| `workflow/markdown-and-link-checks` | workflow | `AGENTS.md:456-461` | verified | 8-pointers | one-line+link | Keep structural Markdown rules and resolvable links; delete the false universal claim that all existing docs already have frontmatter. |

### Moved out of root `AGENTS.md`

| id | kind | source lines | verdict | destination | treatment | note |
|---|---|---|---|---|---|---|
| `vocab/functions-schemas-tests` | vocabulary | `AGENTS.md:670` | verified | linked-doc:docs/seon/reference/retired-vocabulary.md | one-line+link | Move Say/Never spelling; mechanism remains under program-graph facts. |
| `vocab/database-db` | vocabulary | `AGENTS.md:671,727` | verified | linked-doc:docs/seon/reference/retired-vocabulary.md | one-line+link | Move retired wrapper/facade synonyms; `seon.db` contract stays in section 3. |
| `vocab/boot-environment-running` | vocabulary | `AGENTS.md:672` | verified | linked-doc:docs/seon/reference/retired-vocabulary.md | one-line+link | Move retired umbrella nouns; values-carry-world retains the triad. |
| `vocab/call-preparation` | vocabulary | `AGENTS.md:673` | verified | linked-doc:docs/seon/reference/retired-vocabulary.md | one-line+link | Update the drifted SCI citation when moved. |
| `vocab/surface-card` | vocabulary | `AGENTS.md:675` | verified | linked-doc:docs/seon/reference/retired-vocabulary.md | one-line+link | Pure canonical/retired spelling row. |
| `vocab/web-ui` | vocabulary | `AGENTS.md:676` | verified | linked-doc:docs/seon/reference/retired-vocabulary.md | one-line+link | Route mechanism remains source-linked elsewhere. |
| `vocab/subagents` | vocabulary | `AGENTS.md:677` | verified | linked-doc:docs/seon/reference/retired-vocabulary.md | one-line+link | Move term distinction; database-ref mechanism stays outside root detail. |
| `vocab/cluster` | vocabulary | `AGENTS.md:678` | stale | linked-doc:docs/seon/reference/retired-vocabulary.md | one-line+link | **Re-ground:** remove “pod” and “one database”; define a named database branch/root/agents from current source. |
| `vocab/attributes-connections` | vocabulary | `AGENTS.md:679` | verified | linked-doc:docs/seon/reference/retired-vocabulary.md | one-line+link | Data-model law stays in section 3. |
| `vocab/get-in-path` | vocabulary | `AGENTS.md:681` | verified | linked-doc:docs/seon/reference/retired-vocabulary.md | one-line+link | Move “drill” retirement and point at `get_value`. |
| `vocab/provider-descriptor` | vocabulary | `AGENTS.md:683` | verified | linked-doc:docs/seon/reference/retired-vocabulary.md | one-line+link | Provider data mechanism stays in section 3. |
| `vocab/sci-context-host-terms` | vocabulary | `AGENTS.md:685,691,714-715` | verified | linked-doc:docs/seon/reference/retired-vocabulary.md | one-line+link | Move retired sandbox/world spellings; update fork citations. |
| `vocab/interrupt-terms` | vocabulary | `AGENTS.md:686-690` | verified | linked-doc:docs/seon/reference/retired-vocabulary.md | one-line+link | Group the inseparable interrupt/time/fn-entry/safepoint vocabulary; mechanism stays in section 4. |
| `vocab/workload-terms` | vocabulary | `AGENTS.md:692` | verified | linked-doc:docs/seon/reference/retired-vocabulary.md | one-line+link | Update drifted core.async line reference. |
| `vocab/run-holder` | vocabulary | `AGENTS.md:693` | verified | linked-doc:docs/seon/reference/retired-vocabulary.md | one-line+link | Move “claimant” retirement; custody mechanism stays in section 6. |
| `vocab/accretion-breakage` | vocabulary | `AGENTS.md:694` | verified | linked-doc:docs/seon/reference/retired-vocabulary.md | one-line+link | Preserve refusal to attribute the phrase to Rich Hickey without evidence. |
| `vocab/process-record-identity` | vocabulary | `AGENTS.md:696` | verified | linked-doc:docs/seon/reference/retired-vocabulary.md | one-line+link | Process mechanism stays in section 6. |
| `vocab/generated-opening-and-run-loop` | vocabulary | `AGENTS.md:698-699` | verified | linked-doc:docs/seon/reference/retired-vocabulary.md | one-line+link | Move retired bootstrap/fold/driver spellings; mechanism stays in section 4. |
| `vocab/flow-terms` | vocabulary | `AGENTS.md:703-704` | verified | linked-doc:docs/seon/reference/retired-vocabulary.md | one-line+link | Use core.async names, including sliding-buffer tap. |
| `vocab/my-agent-namespace` | vocabulary | `AGENTS.md:706` | verified | linked-doc:docs/seon/reference/retired-vocabulary.md | one-line+link | Default temp namespace versus unique assigned namespace. |
| `vocab/wire-external-only` | vocabulary | `AGENTS.md:710` | verified | linked-doc:docs/seon/reference/retired-vocabulary.md | one-line+link | Reserve wire for process-external crossings. |
| `vocab/namespace-page` | vocabulary | `AGENTS.md:711,775-779` | verified | linked-doc:docs/seon/reference/retired-vocabulary.md | one-line+link | Route table remains the mechanism owner. |
| `vocab/block` | vocabulary | `AGENTS.md:712` | verified | linked-doc:docs/seon/reference/retired-vocabulary.md | one-line+link | Stable shared render unit; mechanism is source-linked from architecture. |
| `vocab/package-keyframe-delta` | vocabulary | `AGENTS.md:713` | verified | linked-doc:docs/seon/reference/retired-vocabulary.md | one-line+link | Move delivery spellings; render mechanism remains in architecture/source. |
| `vocab/render-profile-elision` | vocabulary | `AGENTS.md:717-718` | verified | linked-doc:docs/seon/reference/retired-vocabulary.md | one-line+link | Update both drifted `seon.print` ranges when moved. |
| `vocab/agent-defs-history` | vocabulary | `AGENTS.md:723-724` | verified | linked-doc:docs/seon/reference/retired-vocabulary.md | one-line+link | Move “desk/session unit” retirements; mechanism stays in section 4. |
| `vocab/my-branch-target` | vocabulary | `AGENTS.md:722` | verified | linked-doc:docs/prds/sci-execution-runtime/plan/agent-desk-and-checkout-prd-2026-08-05.md | one-line+link | Unbuilt `[TARGET]` belongs only in its owning PRD. |

### Dies in the rewrite

| id | kind | source lines | verdict | destination | treatment | note |
|---|---|---|---|---|---|---|
| `mech/portable-cljc-default` | mechanism | `AGENTS.md:374-377` | stale | dies:CLJ-only system | one-line+link | HEAD is CLJ-only; 78 `.clj` versus 8 `.cljc`; `deps.edn` and `bin/css` retire CLJS. |
| `mech/per-tier-capability-leaves` | mechanism | `AGENTS.md:378-389` | stale | dies:no live CLJS tier | one-line+link | Current capability owners are JVM `.clj` namespaces with no CLJS leaf tier. |
| `mech/multi-tier-sci-placement` | mechanism | `AGENTS.md:390-395` | stale | dies:deleted pod model | one-line+link | One JVM SCI path exists; analyzer excludes CLJS definitions. |
| `mech/plan-execution-placement` | mechanism | `AGENTS.md:392-395,682` | stale | dies:no implementation | one-line+link | No definition or call exists under `src`, `test`, or `resources`. |
| `mech/result-symbol-tier-crossing` | mechanism | `AGENTS.md:400-402` | stale | dies:no tier-crossing owner | one-line+link | Current admission uses ordinary values, blobs, and identity-only projections. |
| `ptr/conversion-wiki-portability` | pointer | `AGENTS.md:403-409` | verified | dies:portable section deleted | one-line+link | The file exists, but root should not direct new work into the deleted multi-tier model. |
| `law/two-documentation-layers` | law | `AGENTS.md:413-440` | stale | dies:self-contradictory count | one-line+link | The prose itself defines architecture, active program ledger, and bounded PRDs: three layers. |
| `law/every-doc-has-frontmatter` | law | `AGENTS.md:456-461` | stale | dies:contradicted by tracked files | one-line+link | Sixteen tracked runtime spec files lack frontmatter; retain only the authoring/checker workflow. |
| `vocab/canvas-current` | vocabulary | `AGENTS.md:674` | stale | dies:no declared attribute | one-line+link | No source or schema declares `:seon.render.canvas/content`. |
| `vocab/shadow-build-artifact` | vocabulary | `AGENTS.md:680` | stale | dies:CLJS build retired | one-line+link | `bin/css` says the Shadow-CLJS build is dead; current operator meaning must be sourced separately. |
| `vocab/package-roots-current` | vocabulary | `AGENTS.md:684` | stale | dies:unbuilt non-target row | one-line+link | No source/schema/operator owner; only architecture target prose mentions the path. |
| `mech/transitive-function-workload` | mechanism | `AGENTS.md:339-351` | stale | dies:no implementation | one-line+link | HEAD lifts direct metadata only; no reachability classifier exists. |
| `vocab/bare-docs-function` | vocabulary | `AGENTS.md:725` | stale | dies:no installed Var | one-line+link | Current installation provides only `doc` and `dir`, for public function rows. |
| `mech/acquired-render-candidates` | mechanism | `AGENTS.md:726` | stale | dies:no acquired index | one-line+link | Current `candidates` is a private per-render computation, not a publication-time index. |
| `mech/database-custody-counts` | mechanism | `AGENTS.md:804-813` | unverifiable | dies:unverifiable dated counts | one-line+link | No retained query, basis, commit ID, or result pins 9/42/4 at current HEAD. |
| `law/all-human-sizes-are-tokens` | law | `AGENTS.md:1059-1061` | stale | dies:untrue universal | one-line+link | User-visible character and byte counts remain in current AI/render paths. |
| `scar/unpushed-commit-count` | scar | `AGENTS.md:1090-1092` | unverifiable | dies:no retained census | one-line+link | No contemporaneous remote/census artifact proves exactly 4,665. |
| `mech/async-review-gemini-flash` | mechanism | `AGENTS.md:1244-1248` | unverifiable | dies:model not selected | one-line+link | Hook calls `agy -p` without a model selector; keep only coalescing/failure behavior. |

## Catalogue counts

The catalogue contains **167 ideas**: 8 in `1-orientation`, 22 in
`2-design-laws`, 17 in `3-data-and-schema`, 24 in `4-building`, 13 in
`5-testing`, 13 in `6-operating`, 18 in `7-collaborating`, and 7 in
`8-pointers`, plus 27 moves and 18 deaths.

## Dies and moves summary

### Deaths

| id | evidence from the audit |
|---|---|
| `mech/portable-cljc-default` | Audit stale line: HEAD is CLJ-only and file counts contradict a portable-default tier. |
| `mech/per-tier-capability-leaves` | Current capability namespaces are JVM `.clj`; no CLJS leaf exists. |
| `mech/multi-tier-sci-placement` | One JVM SCI path; analyzer excludes CLJS definitions. |
| `mech/plan-execution-placement` | No implementation or call in current source/test/resources. |
| `mech/result-symbol-tier-crossing` | No result-symbol/tier owner; ordinary admission/blobs/identity projection replaced it. |
| `ptr/conversion-wiki-portability` | Target exists but only serves the portable section that the audit directs the rewrite to delete. |
| `law/two-documentation-layers` | The same passage enumerates three layers. |
| `law/every-doc-has-frontmatter` | Sixteen named tracked specs lack frontmatter. |
| `vocab/canvas-current` | No source or schema declares the asserted attribute. |
| `vocab/shadow-build-artifact` | CLJS/Shadow build is explicitly dead. |
| `vocab/package-roots-current` | No current owner; unbuilt row was not marked target. |
| `mech/transitive-function-workload` | Current graph records direct metadata only. |
| `vocab/bare-docs-function` | No injected `docs`; only `doc` and `dir`. |
| `mech/acquired-render-candidates` | Candidate discovery occurs per render, not per publication. |
| `mech/database-custody-counts` | Missing query, database basis, commit ID, and retained result. |
| `law/all-human-sizes-are-tokens` | Current user-visible code exposes character and byte counts. |
| `scar/unpushed-commit-count` | Missing census/remote evidence for the exact historical number. |
| `mech/async-review-gemini-flash` | No model selector proves Flash. |

### Moves

All retired-spelling rows below move to the rewrite-created
`docs/seon/reference/retired-vocabulary.md`; their current mechanism summaries
remain once in the relevant in-file section.

| ids | target |
|---|---|
| `vocab/functions-schemas-tests`, `vocab/database-db`, `vocab/boot-environment-running`, `vocab/call-preparation` | `docs/seon/reference/retired-vocabulary.md` |
| `vocab/surface-card`, `vocab/web-ui`, `vocab/subagents`, `vocab/cluster`, `vocab/attributes-connections`, `vocab/get-in-path` | `docs/seon/reference/retired-vocabulary.md` |
| `vocab/provider-descriptor`, `vocab/sci-context-host-terms`, `vocab/interrupt-terms`, `vocab/workload-terms`, `vocab/run-holder` | `docs/seon/reference/retired-vocabulary.md` |
| `vocab/accretion-breakage`, `vocab/process-record-identity`, `vocab/generated-opening-and-run-loop`, `vocab/flow-terms`, `vocab/my-agent-namespace` | `docs/seon/reference/retired-vocabulary.md` |
| `vocab/wire-external-only`, `vocab/namespace-page`, `vocab/block`, `vocab/package-keyframe-delta`, `vocab/render-profile-elision`, `vocab/agent-defs-history` | `docs/seon/reference/retired-vocabulary.md` |
| `vocab/my-branch-target` | `docs/prds/sci-execution-runtime/plan/agent-desk-and-checkout-prd-2026-08-05.md` |

## Coverage assertion

Every one of the audit's 161 claim groups maps to at least one catalogue id.
The ids `A001`–`A161` follow the audit's per-section ledger order exactly.

| audit claim | catalogue id(s) |
|---|---|
| A001 | `ptr/root-instruction-authority` |
| A002 | `law/old-system-is-history` |
| A003 | `law/old-system-is-history` |
| A004 | `law/three-effect-shapes`, `law/every-program-function-callable` |
| A005 | `law/complete-program-ledger` |
| A006 | `workflow/foreign-breakage-does-not-block-commit` |
| A007 | `law/diagnostics-tell-truth`, `workflow/verify-before-attribution` |
| A008 | `workflow/read-local-instructions`, `ptr/root-instruction-authority` |
| A009 | `law/old-system-is-history` |
| A010 | `mech/boot-tower` |
| A011 | `mech/boot-tower` |
| A012 | `mech/process-identity-and-shutdown` |
| A013 | `mech/process-root-store-fence` |
| A014 | `mech/process-root-store-fence` |
| A015 | `scar/two-jvms-lost-commits` |
| A016 | `mech/config-is-facts` |
| A017 | `mech/current-src-publication` |
| A018 | `mech/current-src-publication` |
| A019 | `mech/per-agent-flow-graph` |
| A020 | `mech/agent-flow-three-procs` |
| A021 | `scar/parked-proc-measurement` |
| A022 | `scar/flow-topology-rebuild` |
| A023 | `mech/cluster-flow-plumbing` |
| A024 | `mech/proc-workload-tags` |
| A025 | `mech/transitive-function-workload` |
| A026 | `mech/proc-workload-tags` |
| A027 | `law/durable-facts-losable-channels` |
| A028 | `law/nothing-reexecutes` |
| A029 | `mech/run-custody-presence` |
| A030 | `law/total-honest-boundaries` |
| A031 | `mech/portable-cljc-default` |
| A032 | `mech/per-tier-capability-leaves` |
| A033 | `mech/multi-tier-sci-placement` |
| A034 | `mech/plan-execution-placement` |
| A035 | `mech/result-symbol-tier-crossing` |
| A036 | `mech/sci-time-output-contracts`, `mech/public-malli-contracts` |
| A037 | `mech/schema-predicate-compilation` |
| A038 | `ptr/conversion-wiki-portability` |
| A039 | `law/two-documentation-layers` |
| A040 | `ptr/architecture-authority` |
| A041 | `ptr/active-program-ledger` |
| A042 | `ptr/active-program-ledger` |
| A043 | `law/every-doc-has-frontmatter` |
| A044 | `workflow/markdown-and-link-checks` |
| A045 | `workflow/markdown-and-link-checks` |
| A046 | `mech/claude-codex-agent-wrapper` |
| A047 | `mech/claude-codex-agent-wrapper` |
| A048 | `workflow/restart-client-after-mcp-change` |
| A049 | `workflow/dependency-ledger-first` |
| A050 | `workflow/data-oriented-clojure-first` |
| A051 | `workflow/read-maintained-dependency-source` |
| A052 | `law/loud-flat-refusals` |
| A053 | `law/values-carry-world`, `law/environment-rides-work` |
| A054 | `ban/parallel-mechanism` |
| A055 | `ban/regex-requires-permission` |
| A056 | `vocab/functions-schemas-tests` |
| A057 | `mech/seon-db-one-namespace`, `vocab/database-db` |
| A058 | `law/environment-rides-work`, `vocab/boot-environment-running` |
| A059 | `vocab/call-preparation` |
| A060 | `vocab/canvas-current` |
| A061 | `vocab/surface-card` |
| A062 | `vocab/web-ui` |
| A063 | `vocab/subagents` |
| A064 | `vocab/cluster` |
| A065 | `mech/entities-are-attributes`, `vocab/attributes-connections` |
| A066 | `vocab/shadow-build-artifact` |
| A067 | `vocab/get-in-path` |
| A068 | `mech/plan-execution-placement` |
| A069 | `mech/provider-descriptor-row`, `vocab/provider-descriptor` |
| A070 | `vocab/package-roots-current` |
| A071 | `mech/sci-context-forks`, `vocab/sci-context-host-terms` |
| A072 | `mech/sci-time-output-contracts`, `vocab/interrupt-terms` |
| A073 | `mech/sci-time-output-contracts`, `vocab/interrupt-terms` |
| A074 | `mech/sci-time-output-contracts`, `vocab/interrupt-terms` |
| A075 | `mech/sci-time-output-contracts`, `vocab/interrupt-terms` |
| A076 | `mech/sci-time-output-contracts`, `vocab/interrupt-terms` |
| A077 | `mech/sci-context-forks`, `vocab/sci-context-host-terms` |
| A078 | `mech/proc-workload-tags`, `vocab/workload-terms` |
| A079 | `mech/run-custody-presence`, `vocab/run-holder` |
| A080 | `law/open-maps-accrete`, `law/key-meaning-never-drifts`, `vocab/accretion-breakage` |
| A081 | `mech/source-initialization-data` |
| A082 | `mech/process-identity-and-shutdown`, `vocab/process-record-identity` |
| A083 | `mech/preprocess-apply-resume` |
| A084 | `mech/generated-opening-run-loop`, `vocab/generated-opening-and-run-loop` |
| A085 | `mech/generated-opening-run-loop`, `vocab/generated-opening-and-run-loop` |
| A086 | `law/three-effect-shapes` |
| A087 | `law/every-program-function-callable` |
| A088 | `mech/program-graph-facts` |
| A089 | `vocab/flow-terms`, `mech/per-agent-flow-graph` |
| A090 | `law/durable-facts-losable-channels`, `vocab/flow-terms` |
| A091 | `mech/datahike-tuple` |
| A092 | `vocab/my-agent-namespace` |
| A093 | `mech/render-contract-properties` |
| A094 | `mech/render-contract-properties` |
| A095 | `mech/render-contract-properties` |
| A096 | `vocab/wire-external-only` |
| A097 | `vocab/namespace-page` |
| A098 | `vocab/block` |
| A099 | `vocab/package-keyframe-delta`, `law/durable-facts-losable-channels` |
| A100 | `mech/sci-context-forks` |
| A101 | `mech/sci-context-forks` |
| A102 | `mech/session-curation-proof` |
| A103 | `vocab/render-profile-elision` |
| A104 | `vocab/render-profile-elision` |
| A105 | `mech/output-path-facts` |
| A106 | `mech/identity-only-admission` |
| A107 | `mech/root-maintenance-portfolio` |
| A108 | `vocab/my-branch-target` |
| A109 | `mech/agent-defs-and-history`, `vocab/agent-defs-history` |
| A110 | `mech/agent-defs-and-history`, `vocab/agent-defs-history` |
| A111 | `vocab/bare-docs-function` |
| A112 | `mech/acquired-render-candidates` |
| A113 | `mech/seon-db-one-namespace` |
| A114 | `law/facts-over-inference`, `mech/program-graph-facts` |
| A115 | `mech/database-custody-counts` |
| A116 | `mech/tests-reaching` |
| A117 | `law/open-maps-accrete` |
| A118 | `mech/entities-are-attributes` |
| A119 | `mech/public-malli-contracts` |
| A120 | `mech/seon-db-one-namespace` |
| A121 | `mech/database-value-identities` |
| A122 | `mech/connection-id` |
| A123 | `mech/config-is-facts` |
| A124 | `mech/minimal-transaction-provenance` |
| A125 | `law/facts-over-inference` |
| A126 | `mech/render-contract-properties` |
| A127 | `law/durable-facts-losable-channels`, `law/values-carry-world` |
| A128 | `law/durable-facts-losable-channels`, `vocab/package-keyframe-delta` |
| A129 | `mech/program-graph-facts` |
| A130 | `mech/agent-defs-and-history` |
| A131 | `law/events-with-loud-backstops` |
| A132 | `law/total-honest-boundaries` |
| A133 | `law/facts-over-inference`, `mech/proc-workload-tags` |
| A134 | `mech/instrumentation-from-program-facts` |
| A135 | `law/all-human-sizes-are-tokens` |
| A136 | `workflow/path-limited-shared-tree-commit` |
| A137 | `scar/unpushed-commit-count` |
| A138 | `ptr/conventions-and-skills`, `workflow/data-oriented-clojure-first` |
| A139 | `scar/fresh-cluster-fork-cost` |
| A140 | `mech/mcp-three-tool-surface` |
| A141 | `mech/mcp-three-tool-surface` |
| A142 | `mech/mcp-live-discovery` |
| A143 | `mech/mcp-live-discovery` |
| A144 | `mech/mcp-session-state` |
| A145 | `mech/edit-hook` |
| A146 | `mech/async-review-gemini-flash` |
| A147 | `mech/edit-hook` |
| A148 | `workflow/bin-test-one-gate` |
| A149 | `mech/changed-test-shells-bin-test` |
| A150 | `workflow/canonical-database-fixture` |
| A151 | `workflow/bounded-loud-awaits` |
| A152 | `workflow/fixed-render-profile-fixtures` |
| A153 | `scar/test-fixture-repair-wave` |
| A154 | `scar/symlink-recursive-deletion` |
| A155 | `scar/symlink-recursive-deletion` |
| A156 | `mech/bin-seon-inventory` |
| A157 | `mech/bin-seon-default-force-semantics` |
| A158 | `mech/process-identity-and-shutdown` |
| A159 | `mech/bin-acme-root-wrapper` |
| A160 | `mech/default-ai-provider` |
| A161 | `mech/default-ai-provider` |

## Disposition uncertainties for owner ruling

- `law/all-human-sizes-are-tokens`: I marked the universal dead because the
  audit found current deliberate-looking character and byte displays, not just
  a rotten citation. If token-only display remains an aspirational owner law,
  keep it in section 2 and open an implementation gap instead.
- `vocab/package-roots-current`: I marked the unbuilt, non-`[TARGET]` row dead
  per the audit's rewrite guidance. The architecture still describes package
  roots as target behavior; the owner may prefer moving the idea to
  `docs/seon/architecture/toolkit.md` rather than treating root deletion as its
  complete disposition.
- `ptr/conversion-wiki-portability`: the target resolves, but keeping it in the
  root would continue directing work toward the deleted multi-tier model. I
  marked the root pointer dead, not the historical document.
