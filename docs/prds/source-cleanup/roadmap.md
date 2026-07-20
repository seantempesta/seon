---
type: prd
status: active
tags: [prd, architecture, database, agent, web]
---

# Source cleanup and vocabulary unification roadmap

## Outcome

Finish the runtime-reliability refactor's deletion promise across the working
tree: every remaining synchronous consumer of the asynchronous `seon.db`
facade fixed in place, one logging surface per process, one config/default
owner per fact, the retired "pod" vocabulary gone from active source and
living docs, and dead namespaces deleted. No stage adds a mechanism; every
stage removes or unifies one.

Evidence base (all dated 2026-07-20, committed):

- [[../database-authority-mesh/research/cleanup-audit-jvm-residue-2026-07-20]]
- [[../database-authority-mesh/research/cleanup-audit-duplicate-interfaces-2026-07-20]]
- [[../database-authority-mesh/research/cleanup-audit-logging-errors-2026-07-20]]
- [[../database-authority-mesh/research/cleanup-audit-vocabulary-2026-07-20]]
- [[../runtime-reliability/research/cleanup-audit-config-startup-2026-07-20]]
- [[../database-authority-mesh/research/pod-term-retirement-plan-2026-07-20]]

## Live bug ledger

Open correctness defects, ordered by risk. A bug leaves this table only with
a commit plus behavioral or live proof; intermittents leave only after a
clean loop of the owning gate.

| # | Bug | Owner file(s) | State |
|---|---|---|---|
| B1 | `later-run?` booleans a Promise (always true); whole ns reads async facade synchronously | `src/seon/runtime/recovery.cljs` | fix lane in flight |
| B2 | Agent-loop failure reports bypass `seon.log`; `seon.log/tail` blind to loop faults (16 sites + turn/run/schedule/db/ai clusters) | `src/seon/agent/loop.cljs` + report list | fix lane in flight |
| B3 | `eval.cljs` "record-eval! tx FAILED" may print without persisting a fault datom (contract check in flight); plus 7 sync-read clusters | `src/seon/eval.cljs` | verify in B2 lane; sync reads stage 1 |
| B4 | `seon.warn` repair guidance names removed `seon.db/*conn*`; ~15 sync facade reads across check registry | `src/seon/warn.cljs:1064` | issue filed; stage 1 |
| B5 | Remaining sync facade reads: `render.cljs:684`, `agent/testrun.cljs:192,205`, `agent/web/internal.cljs:528-536`, `handlers/message.cljs:43`, `my/skills.cljs:324-331`, `my/canvas.cljs:149-153` | listed files | stage 1 |
| B6 | Stray repo-root `locks/` from `cli_test` fixture running real `state/with-lock` with nil process-dir | `script/seon/dev/state.clj`, `test/seon/dev/cli_test.clj` | fix lane in flight |
| B7 | MCP/dev CLJS REPL cannot use `await`/`^:async`; Promises returned unresolved | `bin/mcp-server-cljs` path | fix lane in flight |
| B8 | Writer gate intermittents: `writer-integration` release path + `query-admission` injected-release (1 occurrence each, order-dependent) | `src/seon/db/writer.clj` tests | task chip filed |
| B9 | `test/seon/agent/ctx/canvas_test.cljs` calls `datahike.api` `create-database` directly (boundary violation) | that test | stage 5 |

Non-bugs recorded to prevent re-diagnosis: default Meta-compatible provider
returns HTTP 402 (external credential state, not a runtime regression);
`:seon.error/kind` / `:seon.repl/kind` are closed value enums, not entity
taxonomies; konserve "store" and cljs.test `:type` keys are correct seam
names.

## Stages

Dependency-ordered; each stage is one coherent commit series with its own
gate, and stages 2-5 are safe to interleave with other program lanes only at
the named boundaries. One stage in progress at a time at the top level.

### Stage 0 — land the in-flight lanes (now)

B1, B2+B3-verify, B6, B7 lanes return; review, integrate, close ledger rows.
Gate: three green suites (`bin/test-cljs`, `bin/test-writer`,
`bin/seon test operator`), no `locks/` reappearance, MCP `await` proof.

### Stage 1 — finish the async-facade migration (B3-B5)

Fix every remaining synchronous consumer from the duplicate-interfaces
report in place: `seon.warn` (collapse its dual acquisition path to the
pre-acquired `::data` branch while there), `seon.eval` clusters,
`seon.render:684`, `testrun`, `web.internal`, `handlers.message`,
`my.skills`, `my.canvas/pinned`. Rewrite the `warn.cljs:1064` guidance to
the current facade idiom. Gate: full CLJS suite plus one live cluster
proof that a warn check, a render, and an eval each round-trip through the
authority; the report's inventory rechecked to zero.

### Stage 2 — pod-term retirement (atomic rename)

Execute [[../database-authority-mesh/research/pod-term-retirement-plan-2026-07-20]]
steps 1-4 as one orchestrator-owned unit during a lane freeze: code
identities (`client`/`cluster` mapping, `pod.js` -> `client.js`,
`pod-events.log` -> `client-events.log`, `:seon.dev.process/pod` ->
`/client`), then `acme`/`src-inspect-ai`, then living docs and skills
(resync adapters), then the sweep. Gate: three suites, `bin/seon restart`
live proof, sweep returns only `pod-host/` and deliberate history.

### Stage 3 — one logging convention

From the logging report's remaining plan: adopt the `seon.log/console!`
line shape on the JVM writer via a timbre output-fn; route the residual
non-agent console sites; decide the two value->throw->value round-trips
(`turn.cljs:622,931`, `ctx/canvas.cljs:342`) with the errors-as-values
contract; prune stray bench/probe/`.eval` files from `logs/` and gitignore
their patterns. Gate: writer + CLJS suites; one log line from each process
shows the same shape; `seon.log/tail` shows a loop fault end-to-end.

### Stage 4 — config single-owner collapse

From the config report: collapse duplicated defaults (7890, port files,
cluster dir) to one declaration consumed by `config.clj`, `launch.cljc`,
and `db/server.clj`; migrate runtime env gates (`SEON_WEB`, `SEON_SHELL`,
`SEON_RENDER_STRICT`, `SEON_BRAND_*`, `my/blob.cljs:200`,
`db/transport/uds.cljs:28`) to database facts or the launch descriptor;
deduplicate the `SEON_EMBED` scrub with `bin/acme`; absolutize env dir
coordinates in `config/load!`. Gate: operator suite, `bin/seon up` from a
clean checkout, acme cluster boot, config-apply idempotence proof.

### Stage 5 — deletions and small unifications

Delete `src/seon/agent/ctx/usage.cljs` and `src/seon/ui/components.cljc`
(dead: no consumer, test, or config reference); fix B9 to go through
`seon.db`; extract the two namespace predicates `seon/dev/docstring.clj:193`
duplicates into the owning `.cljc`; rename test-only "tile"/"verbs" fixture
strings; owner-decides: `dev/storage-shootout.js`, `reference-code/integrant`
submodule removal, downstream `bin/acme` gym naming. Gate: three suites;
require-graph re-scan shows no orphan regressions.

## Graduation

All ledger rows closed with proof; the six evidence reports' fix plans each
either executed or explicitly moved to a successor PRD; three suites green
twice consecutively (intermittents B8 included); one live cluster session
demonstrating: a warn check, a recovery decision on a real interrupted run,
an MCP `await` round-trip, and same-shape log lines from both processes.
