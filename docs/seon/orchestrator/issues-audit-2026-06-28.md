---
type: issue
status: active
tags: [issue, orchestrator]
---

# Open-issues backlog audit — 2026-06-28

Grounded validity triage of all 80 files in `docs/seon/orchestrator/issues/`.
Each issue was verified against the CURRENT tree (`feature/agent-fsm`) — grep
the symbol, read the file:line, check `git log`/`-S` for a fix — NOT trusted on
its own age or self-reported status. Verification was fanned out across four
parallel agents (parser/eval/agent, context/render/ui, schema/db/dup,
coupling/naming/flow); the owner-named `recovery-anchor-*` was also confirmed by
the orchestrator directly against the live parser.

## The one structural fact that drives most verdicts

The **active runtime is the CLJS pod** (`src/seon/*.cljs`); the **JVM main-app
track** (`src/seon/*.clj`: integrant, core.async/flow, embedded datahike,
`web/*.clj`, `render.clj`, `ns/*`, `graph/*`, `flow/*`) is **PAUSED**. The vast
majority of "coupling", "overlap-three", "naming", "dup", and "no-X" issues
describe the JVM track. Those `.clj` files mostly still exist on disk but are not
reachable from the active pod, and the active pod already has the single unified
mechanism each "overlap"/"coupling" issue asked for (one `render.cljs`, one
`web/datastar.cljs` SSE morph, one `seon.agent.ctx` context producer, DB-as-bus
reactive-context). They are therefore **STALE/SUPERSEDED for the active pod** —
real-but-dormant smells, not actionable now. They are NOT deleted blindly; if the
JVM track resumes they re-activate.

## Triage table (all 80)

| issue file | fm status | VERDICT | evidence | recommended action |
|---|---|---|---|---|
| recovery-anchor-leaks-inner-form-from-broken-form.md | open | **VALID — LIVE** | `find-recovery-point` (internal.cljc:245-273) still `#"\n[;\(]"`, called `(text offset)` only (l.583), no `:error-kind` threading; live `parse-forms "(defn foo []\n;; x\n  (bar)"` → emits a SEPARATE executing `:form "(bar)"`. PRONG 1/2 + classifier hardening never touched this path | **FIX (Core, correctness/safety)** — `:eof`-aware recovery: suppress interior `;` anchor for unclosed forms |
| parse-forms-entry-schema-and-bare-keys.md | open | **VALID** | live entries use bare `:kind`/`:source`/`:error-kind`; `parse-forms`/`strip-code-fences` lack `:malli/schema` | DECIDE (Core, architectural) entry contract; `strip-code-fences` schema = free win |
| eval-memory-safety.md | open | **VALID (partial)** | store caps DONE (eval.cljs:1997-2024 + memory_safety_test); in-heap/whole-DB-scan guard still OPEN | Follow-up (Core): row-cap `seon.db/query`, reject unconstrained `[?e ?a ?v]` |
| eval-scratch-conn-no-commit.md | open | NEEDS-INVESTIGATION (→VALID) | `db/*conn*` root-bound, no CLJS binding across awaits (eval.cljs:731-736); silent-ok claim needs live repro | Reproduce scratch transact→read-back; loud-error if confirmed (Core) |
| selfhost-cljs-test-is-thunk-resolution.md | active | VALID (narrow) | resolve-test-fn/ensure-analyzer-ns present; residual `cljs.test/is` self-host gap, first-run only, self-heals | Low (Core): preload `cljs.test` analysis into bootstrap |
| acme-no-sci-eval-seam.md | open | **VALID** | no `/eval` in web/*.cljs; acme `out-acme` unwatched, no shadow `:repl` → MCP can't attach | Implement (Core/infra) isolated acme `/eval` seam, store-scoped |
| llm-retry-only-transport.md | open | **VALID** | `agent/turn.cljs:299 call-llm!` retries only `transport-error?`; 429/503/timeout excluded. File refs stale (agent.cljs→agent/turn.cljs) | DECIDE policy (Core): retryable-HTTP predicate honoring Retry-After |
| supervisor-startup-race-audit-2026-06-25.md | active | **VALID (partial)** | #1 confirm-dead loop FIXED (bin/seon:646-660); #2 store rm-rf race + #3 coarse lock still open | Follow-up (infra): stack lock for cluster reset/nuke; store-wipe fence |
| hook-callgraph-review-context.md | open | VALID | `callees-of` exists (dev/analysis.clj:305) but unwired in dev/review.clj | Optional (dev-tooling): wire callees into review context |
| hook-error-hints.md | open | VALID (low) | dev/hook.clj active; pure message-copy improvement | Low cleanup: apply archived message spec |
| als-unify-tx-meta.md | open | **VALID (active)** | two ALS stores still distinct: internal.cljs:47/56/77/1010 + db.cljs:378 `with-tx-context` | Refactor (Core): unify ALS; rename `with-tx-context`→`with-tx-meta` |
| dead-schema-required-count.md | open | **VALID (Core cleanup)** | `schema-required-count`+`*schema-required-counts` atom schema.cljc:299/356; sole consumer a test; violates derive-don't-store | Delete fn+atom, drop test |
| embedding-boot-entity-missing-2026-06-25.md | active | **VALID (reproduces)** | `SEON_EMBED` default `1` (bin/seon:130); `current-hash-for` (embed.clj:968) raw `d/pull` on lookup-ref; neither fix applied | Apply fix (b) for clean boot; fold (a) into embeddings resume |
| node-test-untestable-context-system.md | open | NEEDS-INVESTIGATION | db_test core.async crash GONE (now `.then`); `lookup-value` still walks globalThis (eval.cljs:373); `agent_context_test.cljs.disabled` still off | Re-verify context path testability; partially addressed |
| test-suite-audit-2026-06-25.md | active | NEEDS-INVESTIGATION (partial) | agent_loop_test re-enabled; agent_retry/turns/agent_context still `.disabled` | Re-scope to still-disabled port-or-delete + coverage gaps |
| dead-agent-helpers.md | open | VALID (cleanup) | `agent/helpers.clj` exists, 3 not-migrated throws, zero external callers | Delete file (Core) |
| dead-scratch-files.md | open | VALID (partial) | `hook_test_scratch.clj` deleted (5e8e40c0); `dev/hook_test_ns.clj` remains | Delete remaining scratch file |
| stale-reference-docs.md | open | VALID (docs) | `separate-jvm-exploration.md` + `durable-ctx-design.md` have stale XTDB/Datalevin refs + broken link | Doc-scrub (docs lane) |
| test-coverage-audit-stale.md | open | VALID (doc housekeeping) | `test-coverage-audit/findings.md` references deleted ml-options files; targets paused JVM health/ | Mark PRD superseded in prds.md |
| dead-web-namespace-viewer.md | open | VALID (cleanup, paused) | `web/namespace.clj` + `ui/viewer.clj` both dead on disk | Delete both (or close as paused) |
| example-keywords-in-render-code.md | open | VALID (cosmetic, paused) | `:seon.foo/x` at render/code.clj:19/81, JVM-paused | Trivial cleanup or close-paused |
| deprecated-sse-send.md | open | VALID (cleanup, paused) | `web/sse.clj:362 send!` deprecated, no callers | Trivial delete or close-paused |
| dead-repl-graduate.md | open | VALID (cleanup, JVM) | `repl/graduate.clj` + test, no prod callers | Delete file+test (low) |
| dup-db-name-schema.md | open | VALID (paused) | 18 `::db-name` register! sites, all .clj paused | Dedupe into seon.schema when JVM resumes; re-tag paused |
| dup-namespace-schema.md | open | VALID (paused) | 34 `register! ::namespace` sites, .clj paused | Dedupe; re-tag paused |
| dup-kondo-analysis.md | open | VALID (paused) | graph/extract+analyzer+dev/analysis all JVM-paused | Defer; re-tag paused |
| graph-missing-schema-index.md | open | VALID (paused) | graph/query.clj has only `functions-with-output-key` (l.324); JVM-track | Defer/re-scope; "blocking" severity stale |
| maybe-in-session-schemas.md | open | VALID (paused, path wrong) | real file `src/seon/session.clj` (issue cites deleted `orchestrator/session.clj`); 5 `[:maybe]` l.201-271 + docstring hex/Base62 bug l.307 | Fix path ref; `[:maybe]`→`{:optional true}` when JVM resumes |
| sse-keyword-namespace-mismatch.md | open | VALID (paused, cosmetic) | `:seon.sse/*` in web/sse/flow.clj, no src/seon/sse.clj; JVM SSE | Defer; cosmetic |
| dup-parse-form-body.md | open | STALE/paused | both copies (web/handlers.clj:47, ns/routes.clj:649) JVM-paused | Defer; mark `deferred` |
| client-paren-balancer-vs-parse-forms.md | verified | **RESOLVED** | b5287550 rewrite-clj literal-aware extraction; hand-rolled scanner deleted | Close |
| acme-cluster-reset-process-namespace.md | verified | **RESOLVED** | bin/seon:837 store_dir gate (c6d7c440); live-proven | Close |
| seon-port-non-namespaced.md | verified | **RESOLVED** | bin/seon:118-119 `SEON_PORT_FILE` exported (e2ef2f96); acme override | Close |
| agent-api-discoverability.md | active | **RESOLVED/SUPERSEDED** | capabilities-section shipped then rebuilt into block model (agent/ctx/ blocks); no "What you can do" string | Close — superseded by context-block model |
| any-in-wire-protocol.md | resolved | RESOLVED (correct) | flow/msg.clj uses `:seon.flow/dynamic`, `:any` gone | Keep closed |
| db-ops-any-returns.md | completed | RESOLVED (correct) | sanctioned `:any`-at-boundary rule; db/query|pull `:any` intentional | Keep closed |
| dup-get-conn-runtime.md | open | **RESOLVED** | get-conn DELETED M-1; render.clj:55 + routes.clj tombstones | Close (frontmatter says open — correct it) |
| dup-connection-error.md | archived | RESOLVED (correct) | db/datalevin/conn.clj gone | Keep archived |
| map-in-map-out-compliance.md | closed | RESOLVED (correct) | reversed by 2026-06-08 positional-allowed rule | Keep closed |
| raw-datalevin-conn.md | archived | RESOLVED (correct) | datalevin gone | Keep archived |
| routes-conn-vs-dbname.md | archived | RESOLVED (correct) | routes.clj:482 M-1 tombstone | Keep archived |
| instrumentation-collect-clean-build-empty.md | completed | RESOLVED (correct) | `instrument-from-db!` instrument.cljc:316 supersedes compile-time collect | Keep closed |
| context-derived-not-stored.md | resolved | RESOLVED | self-resolved 5f2a564 + keystone single-producer 5144707c | Archive |
| context-loop-regression-sweep-2026-06-25.md | active | RESOLVED/SUPERSEDED | your-entity section removed (37c47f27); single byte-identical `render-context` producer; effective-cap gone | Archive |
| warnings-misfire-core-schemas.md | active | RESOLVED | warn.cljs:514 core-kinds remove + `:dev-only?` drops from prompt | Mark resolved |
| live-tile-nil-entity-render-failed.md | completed | RESOLVED | live-tile resolves entity from db; agent/ctx/live_tile.cljs + guard test | Archive |
| dead-render-example.md | open | RESOLVED | `render/example.clj` no longer exists | Archive |
| nippy-transitive-dep.md | open | **RESOLVED** | `com.taoensso/nippy 3.4.2` explicit deps.edn:37 | Mark resolved |
| agents-als-tests-fail-under-mcp.md | open | **RESOLVED/STALE** | `seon.agents` ns + test deleted (248f2193); ALS now in seon.agent.* | Close |
| flow-pool-integrant-surgical-2026-06-09.md | open | RESOLVED (per body) | body "APPLIED"; 4 dead keys removed; JVM-paused | Flip to resolved/archive |
| coupling-render-db.md | archived | RESOLVED (correct) | datalevin migration removed db.datalevin.conn | Leave archived |
| naming-health.md | open | RESOLVED/STALE | `domains/health/` deleted; only JVM health.clj remains, no collision | Close |
| eval-form-namespace-mismatch.md | open | STALE/SUPERSEDED | refs repl.clj + flow/pool nREPL + datalevin (scrubbed 0b6e9d12); pod uses self-host cljs.js | Close |
| scanner-missing-as-alias.md | open | STALE/SUPERSEDED | graph/scanner.clj JVM-paused; pod resolves `::` via client.cljs analyzer | Close |
| agent-pool-sigkill-cycle.md | active | STALE/SUPERSEDED | flow/pool.clj JVM nREPL pool, disabled; pod agents are CLJS runtimes | Close |
| no-agent-stuck-detection.md | open | STALE/SUPERSEDED | refs ai/claude.clj + web/agents.clj (missing); pod has run-FSM derived-state | Close; reframe as new derived section if wanted |
| launch-agent-blocks-nrepl.md | open | STALE/SUPERSEDED | ai/claude.clj launch-agent!! nREPL blocking, JVM-paused | Close |
| missing-malli-schema.md | open | STALE/SUPERSEDED | hotspots (health.*, trading.*) dead domains; always-on `instrument-from-db!` instruments every schema'd fn | Close/re-scope; "blocking" stale |
| orphan-keyword-namespaces.md | open | SUPERSEDED | code-as-data legitimizes `seon.fn`/`seon.ns`/`seon.spec` entity prefixes (active pod) | Close — superseded by code-as-data/no-kinds |
| context-budget-fn-head-lean.md | active | STALE/SUPERSEDED | all refs `ctx.cljs` (→agent/ctx/namespaces.cljs); reworked by lean-context (a39709db, 47d91fb7) | Close; re-measure on agent.ctx if needed |
| any-in-render-html.md | open | STALE/SUPERSEDED | `:any` in ns/view.clj:55/71/76 — paused JVM; active renderer is render.cljs | Close-paused |
| overlap-three-rendering.md | open | SUPERSEDED | render.clj/ns/view.clj/ui/viewer.clj all paused; pod has ONE render.cljs | Close |
| overlap-three-sse-push.md | open | SUPERSEDED | ctx.clj/web/sse paused; pod = single web/datastar.cljs morph | Close |
| overlap-three-status-badges.md | open | STALE/SUPERSEDED | web/agents.clj gone; html.clj/components.clj paused; active badges in ui.* | Close |
| overlap-three-ai-context.md | open | SUPERSEDED | render/code.clj/repl/context.clj/graph/context.clj paused; pod single render-context | Close |
| observatory-sse-streaming.md | open | STALE/SUPERSEDED | refs web/agents.clj (gone); active observatory = /world Datastar SSE | Close |
| no-broadcast-signals.md | open | SUPERSEDED | JVM ctx atom-watches + flow signals; superseded by DB-as-bus reactive-context | Close |
| no-live-subscriptions.md | open | SUPERSEDED | graph/query.clj pull-only paused; active = datahike listen!/triggers + derive-at-render | Close |
| coupling-circular-deps.md | open | STALE/SUPERSEDED | requiring-resolve across ~13 .clj, all JVM-paused | Close/re-scope to JVM |
| coupling-ns-routes-reactive.md | open | STALE/SUPERSEDED | ns/routes.clj uses web.reactive.* (l.89-90), JVM-paused | Close-paused |
| naming-context.md | open | STALE/SUPERSEDED | 4 JVM .clj context files; active = seon.agent.ctx.cljs (Phase-1 rename landed) | Close/re-scope |
| naming-status.md | open | STALE/SUPERSEDED | health.clj/runtime.clj/flow/topology.clj JVM-paused | Close/re-scope |
| no-custom-namespace-behavior.md | open | STALE/SUPERSEDED | per-ns ctx-atom+harness JVM premise; pod block/role model | Close |
| no-unified-namespace-model.md | open | STALE/SUPERSEDED | same harness+ctx split, JVM-only; pod has unified seon.agent.ctx | Close |
| lifecycle-coupling-bottleneck.md | open | STALE/SUPERSEDED | ns/lifecycle.clj JVM-paused; no equivalent in pod | Close/re-scope |
| atom-watches-bypass-flow.md | open | STALE/SUPERSEDED | ctx.clj:285-355 watch→flow; pod is core.async-free (native ^:async) | Close |
| state-three-mechanisms.md | open | STALE/SUPERSEDED | ctx atom + flow/harness + flow/topology, all JVM; pod single-source-of-truth DB | Close |
| lint-hook-jvm-oom-2026-06-09.md | open | STALE (JVM-era) | dev JVM -Xmx2g + in-process clj-kondo; active hook path is the pod | Close; revisit if JVM resumes |
| coupling-graph-render.md | resolved | **FRONTMATTER WRONG → NEEDS-INVESTIGATION** | marked resolved but graph/ingest.clj:34 still `[seon.render :as render]` (dep returned); JVM-paused, low priority | Correct frontmatter; defer (JVM) |
| datahike-migration-history.md | archived | VALID archive (accurate breadcrumb) | historical datalevin-removal + datahike narrative correct | Keep as archive |

## (a) VALID — the actionable backlog, clustered by theme + severity

### Parser / eval correctness & safety (Core) — highest weight
- **recovery-anchor-leaks-inner-form-from-broken-form** — *correctness/safety, friction-in-practice but elevate*. LIVE-confirmed: an unclosed form with a column-0 interior `;;` splits at the `;` and emits the inner `(call)` as an EXECUTING top-level form — silent partial execution of broken code. Fix: thread `:error-kind` into `find-recovery-point`; for `:eof` suppress the interior `;` anchor (recover only at column-0 `(` or EOF); keep `;` for non-`:eof` localized failures. Well-scoped to `seon.repl.internal`; keep `recovery-cases` + `narration-attaches-to-failure-not-next-good` green.
- **eval-memory-safety** (in-heap half) — guard the DB surface: row-cap in `seon.db/query`/`pull`, reject unconstrained whole-DB `[?e ?a ?v]` scans. Store caps already landed.
- **eval-scratch-conn-no-commit** — NEEDS-INVESTIGATION (reproduce scratch-transact silent-ok); loud error if confirmed.
- **parse-forms-entry-schema-and-bare-keys** — *architectural, owner decision*. The parse-entry maps still use bare keys and `parse-forms`/`strip-code-fences` are unspecced. Quick win: `strip-code-fences` schema; then decide entry-union contract (bare vs namespaced).
- **selfhost-cljs-test-is-thunk-resolution** — low; preload `cljs.test` analysis into bootstrap (first-run only, self-heals).

### Agent runtime / transport (Core)
- **llm-retry-only-transport** — *friction*. `call-llm!` retries only transport errors; add retryable-HTTP predicate (429/503, honor Retry-After), update the "HTTP never retries" tests in the same patch, fix stale file refs.
- **als-unify-tx-meta** — *friction*. Two distinct ALS stores; unify + rename `with-tx-context`→`with-tx-meta`.

### Infra / harness (Core/infra)
- **acme-no-sci-eval-seam** — add an isolated `/eval` seam on the acme pod (7980), store-scoped, so fixes can be exercised programmatically.
- **supervisor-startup-race-audit** (partial) — #1 done; add the coarse stack lock for `cluster reset`/`nuke`/`restart all` (#3) then the store-wipe fence (#2).
- **embedding-boot-entity-missing** — still reproduces (`SEON_EMBED` default 1 + raw `d/pull` lookup-ref in `current-hash-for`). Apply the clean-boot fix (b) now; fold (a) into the embeddings-resume work.

### Dev-tooling (low)
- **hook-callgraph-review-context** — wire `callees-of` into `dev/review.clj`.
- **hook-error-hints** — apply the archived message-copy spec.

### NEEDS-INVESTIGATION (don't guess)
- **node-test-untestable-context-system** — db_test crash fixed; `lookup-value` globalThis fragility + disabled context test remain. Re-verify testability.
- **test-suite-audit-2026-06-25** — partially actioned; re-scope to the still-`.disabled` tests (agent_retry/turns/agent_context) port-or-delete + coverage gaps.

### Cleanup / dead code (Core, low-risk deletes)
- **dead-agent-helpers** (delete `agent/helpers.clj`), **dead-scratch-files** (delete remaining `dev/hook_test_ns.clj`), **dead-schema-required-count** (delete fn+atom — violates derive-don't-store, active `.cljc`).

### Docs housekeeping
- **stale-reference-docs** (scrub XTDB/Datalevin refs + broken link), **test-coverage-audit-stale** (mark PRD superseded).

### VALID but JVM-track-paused — defer + re-tag `paused` (NOT active-pod work)
`dup-db-name-schema`, `dup-namespace-schema`, `dup-kondo-analysis`,
`graph-missing-schema-index`, `maybe-in-session-schemas` (also fix its wrong path
→ `src/seon/session.clj`), `sse-keyword-namespace-mismatch`, `dup-parse-form-body`,
`dead-web-namespace-viewer`, `example-keywords-in-render-code`,
`deprecated-sse-send`, `dead-repl-graduate`. Real smells, dormant track —
resolve only if/when the JVM core-systems integration resumes.

## (b) CLOSE-LIST — resolved + stale, bulk-closeable (with proof)

**RESOLVED — fixed in code (close/archive):**
- client-paren-balancer-vs-parse-forms — b5287550 (rewrite-clj extraction)
- acme-cluster-reset-process-namespace — c6d7c440 (store_dir gate)
- seon-port-non-namespaced — e2ef2f96 (SEON_PORT_FILE)
- agent-api-discoverability — superseded by context-block model
- dup-get-conn-runtime — get-conn deleted M-1 (frontmatter still "open" — fix it)
- nippy-transitive-dep — explicit deps.edn:37
- agents-als-tests-fail-under-mcp — ns+test deleted 248f2193
- flow-pool-integrant-surgical — body "APPLIED"
- naming-health — `domains/health/` deleted
- context-derived-not-stored, context-loop-regression-sweep, warnings-misfire-core-schemas, live-tile-nil-entity-render-failed, dead-render-example — all fixed/superseded by the agent-ctx keystone + your-entity removal
- Already correctly closed (leave as-is): any-in-wire-protocol, db-ops-any-returns, dup-connection-error, map-in-map-out-compliance, raw-datalevin-conn, routes-conn-vs-dbname, instrumentation-collect-clean-build-empty, coupling-render-db, datahike-migration-history

**STALE/SUPERSEDED — JVM-paused lane or design moved (close/re-scope):**
- eval-form-namespace-mismatch, scanner-missing-as-alias, agent-pool-sigkill-cycle, no-agent-stuck-detection, launch-agent-blocks-nrepl, missing-malli-schema, orphan-keyword-namespaces, context-budget-fn-head-lean
- All "overlap-three-*" (rendering/sse-push/status-badges/ai-context) + observatory-sse-streaming + no-broadcast-signals + no-live-subscriptions + any-in-render-html
- All "coupling-*" (circular-deps/ns-routes-reactive) + naming-context + naming-status + no-custom-namespace-behavior + no-unified-namespace-model + lifecycle-coupling-bottleneck + atom-watches-bypass-flow + state-three-mechanisms + lint-hook-jvm-oom

**Frontmatter corrections (not closures):**
- `dup-get-conn-runtime` is `open` but RESOLVED.
- `coupling-graph-render` is `resolved` but the dep returned (`graph/ingest.clj:34`) — JVM-paused, low priority.
- `maybe-in-session-schemas` cites a deleted path; the real file is `src/seon/session.clj`.

## (c) Top 5 highest-priority VALID issues to address next

1. **recovery-anchor-leaks-inner-form-from-broken-form** (Core, correctness/safety) — the only issue with a silent-execution-of-broken-code edge; live-confirmed, unbuilt, well-scoped.
2. **eval-memory-safety** in-heap/DB-scan guard (Core) — an unconstrained agent query can OOM the pod; store caps landed, the query surface is still open.
3. **llm-retry-only-transport** (Core) — 429/503/timeout currently fail the turn with no retry; cheap, high agent-reliability payoff.
4. **acme-no-sci-eval-seam** (Core/infra) — without a programmatic eval seam on acme, downstream fix→verify loops are manual; blocks the standing "verify in acme" workflow.
5. **supervisor-startup-race-audit** #2/#3 (infra) — the store-wipe / coarse-lock races can corrupt a cluster store under concurrent `cluster reset`; #1 already fixed, finish the fence.

## Verdict counts (of 80)

- VALID (active pod, actionable): ~16 — incl. 2 NEEDS-INVESTIGATION.
- VALID but JVM-paused (defer/re-tag): ~11.
- RESOLVED (close/archive): ~24.
- STALE/SUPERSEDED (close/re-scope): ~28.
- Frontmatter-only corrections: 3.

The actionable active-pod backlog is small and concentrated in the
parser/eval/agent-runtime/infra cluster; the bulk of the 80 are JVM-track
artifacts the active pod has already superseded.
