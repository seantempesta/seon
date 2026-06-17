---
type: orchestrator
tags: [index, database]
status: active
---
# Active Work

## Recovery

> Read this after context loss. It tells you where we are.

**Work:** Datahike Migration — Phase 3 (Agent Playground & Harness)
**Branch:** `feature/datahike-migration`
**Last updated:** 2026-04-25

## What's Going On

Seon runs two tracks. The active track is the CLJS pod (port 7890), backed by the `wire-server` central datahike writer (file-backed datahike at `data/clusters/default/store`); the pod forwards writes over a Unix socket to `wire-server` and reads are local lazy db values. `[JVM track — paused]` The paused JVM main-app track uses Datahike embedded (in-process LMDB) as its database. PRD set: `docs/prds/datahike-migration/{prd,decisions,notes,phase-3-harness-migration}.md`.

Phases shipped:

| Phase | Commit | What |
|---|---|---|
| 1 | `db40ce2` | Schema bridge + flow topology (`conn-process`, `tx-bus`, `:seon.db/flow` Integrant key) |
| 2 | `9455f3f` | `seon.db` dispatches per-namespace to datahike flow + auto-stamp `:seon.db/namespace` |
| 3 step 1 | `0bb16ac` | Five DBs in `:seon.db/flow` (`:seon.session :seon.repl :seon.flow :seon.orchestrator :seon.phase2.demo`) |
| 3 demo | `baa2b41` | `seon.session/launch!` / `checkpoint!` / `stop!` end-to-end |

Test baseline: **4054 pass / 0 fail / 2 errors**. The 2 errors are pre-existing (`seon.db.pipeline-test`, `seon.health.workout-test`); both depend on the legacy runtime DB and disappear in Phase 4.

## Boot Reality

`./bin/run` produces a clean degraded boot in ~3s:

- Phase 1 complete (nREPL :7888, HTTP :8080)
- Phase 2 reports "failed" — **expected**. `:seon/runtime-db` is intentionally absent. Don't try to "fix" this until Phase 5.
- Datahike flow up with 5 conn-processes
- Agent pool re-enabled in `:dev`/`:prod` (size 3). Idle JVMs survive health-check cycles. The previously-feared SIGKILL bug did not reproduce in the live REPL.

## Phase 3 Step Status (from `phase-3-harness-migration.md` §Phasing)

| Step | Status |
|------|--------|
| 1. Schemas + system.edn wiring | ✅ done |
| 2. `seon.flow.trace` migrated | ✅ implicit (free from step 1) |
| 3. `seon.session` ns + registry plumbing | ✅ done |
| 4. `seon.orchestrator.session` migrated | ✅ done — registry atom replaced by `:seon.orchestrator` rows + small live-state map |
| 5. `seon.flow.pool` atoms → flow state | ⚠️ partial — pool re-enabled (5a), atoms→flow migration deferred (5b) |
| 6. `seon.flow.status` atoms → flow state | ✅ done — `:seon.flow/status-collector` process owns prev-counts, errors, drains |
| 7. `seon.ns.routes` + `seon.render` atoms → flow state | ❌ not started |
| 8. `seon.ctx` checkpoint path | ✅ done — auto-debounce `*ctx*` watcher in `seon.session/launch!` |
| 9. `seon.db` agent-JVM dispatch (cross-JVM relay) | ✅ done — per-agent Nippy/TCP relay in `seon.db.relay`; agents call `seon.db/transact!`/`query`/`pull-by-name`/`pull-many-by-name` and they route through the orchestrator |
| 10. `seon.flow.harness` `:seon.harness/needs` DI | ❌ not started |
| 11. `orchestrator/launch.clj` API + integration test | ✅ done (lives in `seon.session`, not `seon.orchestrator.launch`) |
| 12. Convention meta-test + docs | ✅ done — `test/seon/dev/conventions_check_test.clj` asserts touched namespaces follow `:malli/schema`+map-in, schemas are namespaced, no new defonce atoms |

## File Touchpoints (next agent's planning)

- Step 4: `src/seon/orchestrator/session.clj` (`session-registry` defonce → flow state)
- Step 5: `src/seon/flow/pool.clj` (~930 LOC, big refactor; also fix the SIGKILL-cycle bug)
- Step 6: `src/seon/flow/status.clj`
- Step 7: `src/seon/ns/routes.clj`, `src/seon/render.clj`
- Step 8: extend `seon.session/launch!` with a `*ctx*` watcher calling `checkpoint!`
- Step 9: extend `src/seon/db.clj` agent-JVM branch — when no local datahike flow, route via `topology.clj/start-cross-ns-relay!`
- Step 10: `src/seon/flow/harness.clj` — read `:seon.harness/needs`, pre-resolve, merge into request map
- Step 12: new `test/seon/dev/conventions_check_test.clj`

## Verified Demo (`baa2b41`)

```clojure
(require '[seon.session :as ses] '[seon.db :as db])
(def res (ses/launch! {:seon.session/namespace 'seon.apps.demo}))
;; => {::ses/session-id "a5ba3e", ::ses/nrepl-port 7980, ::ses/pid 30739}

;; via mcp__seon__eval :session_id "a5ba3e":
;;   (swap! *ctx* assoc :scratch 42)
;;   (defn double-scratch [] (* 2 (:scratch @*ctx*)))
;;   (double-scratch)  ;; => 84

(ses/checkpoint! {::ses/session-id (::ses/session-id res)})
;; row at [:seon.session/agent "a5ba3e"] now has :seon.session/ctx "{:scratch 42}"

(ses/stop! {::ses/session-id (::ses/session-id res)})
;; status :stopped, :stopped-at populated

```

## Operational Gotchas

- **Aliased keywords don't work in `mcp__seon__eval`** — the eval'd form is read in a fresh ns context. Use literal `:seon.db.datahike.flow/pids` instead of `::f/pids`.
- **`integrant.repl.state/system` is a dynamic var.** Idiom: `(deref (resolve 'integrant.repl.state/system))`.
- **`s` alias collides** with `clojure.spec.alpha` in the orchestrator REPL. Use `:as ses` or `(ns-unalias *ns* 's)` first.
- **Inspect the running flow:** `(let [sys (deref (resolve 'integrant.repl.state/system))] (sort (keys (:seon.db.datahike.flow/pids (:seon.db/flow sys)))))` → `(:seon.flow :seon.orchestrator :seon.phase2.demo :seon.repl :seon.session)`.
- **Restart cleanly:** `lsof -ti :7888 :8080 2>/dev/null | xargs -r kill ; ps aux | grep -E "seon\.runner|agent-runner" | grep -v grep | awk '{print $2}' | xargs -r kill -9 ; ./bin/run`.

## Smells Flagged Across Phases

- `seon.repl/eval-form!` `::result [:any ...]` — wire-protocol; should be `:seon.flow/dynamic`.
- `seon.runtime/agent-run-entity-schema` uses `:seon.db/ref` but actual writes pass entity ids — will fail when `:seon.runtime` joins the flow.
- `seon.flow.pool/spawn-agent-jvm!` and `port-bound?` are private (`defn-`) but natural callers are outside the pool — should be public for Phase 3 work.
- `:seon.session/agent` (1+ chars) and `:seon.orchestrator.session/id` (4–6 chars) both represent session ids but with different validation — unify in Phase 3 cleanup.
- `mcp__seon__eval` evaluates in the cloned nREPL session's `user` ns, not the agent's intended ns. Workaround in `seon.session/launch!` interns `*ctx*` in both. Real fix: pass `:ns` per-message in the nREPL bridge.

## Out of Scope (Phase 4+)

- Domain namespace migration (`seon.health.*`, `seon.trading.*`, `seon.ai.*`)
- Datahike named branches for clone-and-merge
- Cross-process security filters (`d/filter` grants)
- Token-aware truncation in AI render output
- Full coverage of `:seon.render/ai` renderers

## Issue Notes

- [[orchestrator/issues/agent-pool-sigkill-cycle]] — pool's health-check SIGKILLs idle JVMs (deferred to Phase 3 step 5)
