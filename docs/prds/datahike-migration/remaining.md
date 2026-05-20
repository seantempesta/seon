---
type: prd
status: active
tags: [prd, database, schema]
---

# Datahike Migration — Remaining Cleanup Punch-List

Operational follow-up to `prd.md` / `decisions.md` / `phase-3-harness-migration.md`. The migration to datahike landed on `main` (boot uses `:seon.db/flow` with 5 namespaces — `:seon.session`, `:seon.repl`, `:seon.flow`, `:seon.orchestrator`, `:seon.phase2.demo` — per `resources/system.edn`). `seon.db` keeps a legacy datalevin fall-through for db-names not in the flow, but the Integrant keys that wire datalevin up (`:seon.db.datalevin/server`, `:seon.db.datalevin/connections`, `:seon/runtime-db`) are commented out of `system.edn`. So the legacy datalevin path exists at the source-code level but is never actually invoked at boot.

This punch-list enumerates the 32 source files in `seon/src/` that still reference `datalevin`, classifies every reference, and groups the work into clusters that can be migrated by focused agents. The cluster recommendations track the steps in `prd.md` §"Deliverables → Deletions" and the deferred work in `phase-3-harness-migration.md` §"Out of scope for Phase 3".

## Classification scheme

Each reference is one of:

- **`:live`** — invoked on the current boot path. Boot succeeded 2026-05-14 so by definition these don't block boot, but they are reachable from MCP tools, HTTP handlers, the agent loop, or test entry points.
- **`:dead-require`** — a `(:require [seon.db.datalevin.x])` whose alias is either unused, or used only in code branches that the current Integrant config can never reach (because `:seon.db.datalevin/connections` is absent from `system.edn`).
- **`:doc-only`** — string mentions in docstrings, comments, error messages. No runtime coupling.
- **`:bridge-rename`** — call to `seon.db.schema/malli-map->datalevin-schema`. The datahike-side replacement `seon.db.datahike.schema/malli-map->datahike-schema` exists at `seon/db/datahike/schema.clj:217`. Bridge callers need to switch namespace alias + fn name. The `def datalevin-schema` bindings that aggregate these calls go away with the callers.
- **`:dead-ns`** — the entire namespace file is dead code on the current boot path (no requirer reaches it from the boot tree). Listed here so a single agent can rm the file rather than walk hits inside.
- **`:other`** — anything else (described inline).

## Summary

| Class | File count | Hit count (approx) | Disposition |
|---|---|---|---|
| `:live` | 4 | ~40 | Migrate to datahike fall-through or delete the feature |
| `:dead-require` | 11 | ~25 | Delete `(:require ...)` + any unreachable branch |
| `:doc-only` | 5 | ~20 | Batch fix (low priority) |
| `:bridge-rename` | 7 | 17 callsites | One bridge-rename pass + delete derived `datalevin-schema` defs |
| `:dead-ns` | 6 | ~140 (most of the count) | Delete whole files (`src/seon/db/datalevin/`, `src/seon/ai/datalevin.clj`) once callers are gone |
| `:other` | ~4 | small | Schema-key rename / config-shape cleanup |

Total ~302 hits, but only ~50 are runtime-coupled — the rest are doc strings, dead-require alias declarations, and code inside dead-ns files. Most of the volume goes away when `src/seon/db/datalevin/**` and `src/seon/ai/datalevin.clj` are deleted; the actual migration work is on a few dozen call sites.

## Recommended cluster order (highest leverage first)

1. **Bridge-deletion cluster (revised from "rename")** (7 callers, 17 callsites). Originally framed as a rename of `db-schema/malli-map->datalevin-schema` to a datahike-equivalent name; **revised 2026-05-14 after a REPL probe showed the legacy and datahike bridge fns produce different output shapes** (datalevin map-keyed-by-attr for `d/update-schema` vs datahike vector-of-ident-entity-maps for `d/transact`). They are not interchangeable. The datahike-side fn already exists at `seon.db.datahike.schema/malli-map->datahike-schema`; renaming the legacy fn to the same name would either lie or collide. Per no-legacy-coexistence (no backward-compat shims): the legacy fn and its `def datalevin-schema` aggregator vars across the 7 caller files get **deleted with clusters 2 + 3**, not renamed in isolation. All 17 callsites feed aggregators that the audit already classified as feeding dead consumers — they go together.
2. **Dead-require + dead-branch cleanup in the active-on-boot files** (`seon.system`, `seon.render`, `seon.ns.routes`, `seon.orchestrator.session`, `seon.flow.topology`, `seon.flow.pool`, `seon.health`, `seon.dev.test`, `seon.system.config`). These namespaces load at boot, so dead requires here represent the largest concentration of "lies in our load graph." Deleting them lets `seon/db/datalevin/` itself be ripped out without ripple.
3. **`seon.db.clj` legacy fall-through removal**. The `transact!` / `read!` / `write!` paths still route through `seon.flow/writer` + `seon.flow/reader` (datalevin) when a db-name isn't in `:seon.db/flow`. Today this fires only if a caller passes an unregistered db-name, in which case the flow raises "Infrastructure flow not running." Once cluster 2 lands, this can collapse to just the datahike + relay branches.
4. **Delete dead namespaces**: `src/seon/db/datalevin/` (5 files), `src/seon/ai/datalevin.clj`, `src/seon/web/agents.clj` (depends on `seon.ai.datalevin`), the three `test/bootstrap*.clj` POCs. Drop `datalevin/datalevin` from `deps.edn`. Drop the `reference-code/datalevin` submodule entry once nothing pins it.
5. **Doc-only sweep**: rewrite remaining strings in CLAUDE.md, docs/, and surviving namespace docstrings. Easiest batch; do last so the prose matches the new reality.

## File-by-file detail

### Live on the current boot path

#### `src/seon/db.clj`

`seon.db` is the central API. Loads at boot (required from `seon.system`). Currently keeps both datahike and datalevin paths; datalevin is reachable only via the `:else` branch.

| Line | Reference | Class | Action |
|------|-----------|-------|--------|
| 13, 20, 243, 255, 391, 436 | docstring / comment mentions "legacy datalevin path" | `:doc-only` | Delete prose after the datalevin branches are removed. |
| 34 | `[datalevin.core :as d]` | `:live` | Used at line 156 (`d/schema`), 177 (`d/update-schema`), 371 (`d/transact!`) inside `:else` legacy branch. Remove alias and the wrapping `ensure-schema!` + direct-mode datalevin call when cluster 3 collapses the `:else` branch. |
| 36 | `[seon.db.datalevin.conn :as conn]` | `:live` | Used by `db-name->conn-args`, `resolve-conn`, `*direct-mode*` legacy path. Goes away with cluster 3. |
| 37 | `[seon.db.datalevin.reader :as reader]` | `:dead-require` | Alias `reader` only appears in `read!` building a flow message; `seon.flow/reader` isn't even built without a connection-manager (`flow/topology.clj:631`). Delete with cluster 3. |
| 164 | `db-schema/malli-map->datalevin-schema` | `:bridge-rename` | Inside legacy `ensure-schema!` path. Goes away with cluster 3, no separate rename needed if cluster 3 lands first. |
| 272, 274 | `:seon.db.datalevin/connections` lookup | `:live` (but unreachable) | `get-conn-manager` will always throw "Connection manager not available" on current boot because the Integrant key isn't in the system. Delete with cluster 3. |
| 359-360 | `:seon.db.datalevin.writer/tx-data` `:seon.db.datalevin.writer/db-name` keys in writer payload | `:live` (but routes to disabled writer) | `flow.in/request` to `:seon.flow/writer` which isn't built. Will fail with `flow-request!` timeout once it ever fires. Delete with cluster 3. |

Cluster: **complex / cross-file**. Touched by cluster 3.

#### `src/seon/render.clj`

Required transitively from `seon.web.handlers`, `seon.system`, etc. Loads at boot.

| Line | Reference | Class | Action |
|------|-----------|-------|--------|
| 31 | `[seon.db.datalevin.conn :as dl-conn]` | `:live` | Used by `get-conn` to fetch a `:seon.runtime` conn for renderer-lookup. With `:seon.db.datalevin/connections` absent, `get-conn` returns `nil`, so `resolve-renderer-from-datalevin` always returns `::no-renderer` and `try-render` always returns `nil`. **Renderer auto-resolution is silently disabled on current boot.** Migrate to query `:seon.runtime` via `seon.graph.query/functions-with-output-key`, which already takes a db-name keyword (line 163) — make `find-renderer` use that directly instead of stashing a conn. |
| 61 | `(:seon.db.datalevin/connections integrant.repl.state/system)` | `:live` (returns nil) | Delete with the dl-conn require. |
| 63-65 | `(dl-conn/get-conn! {::dl-conn/manager mgr ::dl-conn/db :seon.runtime ::dl-conn/schema (runtime/runtime-merged-schema)})` | `:live` (unreachable) | Delete. The `:seon.runtime/runtime-merged-schema` call is part of cluster 4 cleanup. |
| 261-313, 330, 359-364, 585, 641 | `resolve-renderer-from-datalevin`, `call-datalevin-renderer` fn names + bodies | `:live` (silently no-op) | Rename to `resolve-renderer` / `call-renderer`; switch the underlying query to db-name-based `seon.graph.query` (no conn). The cache stays. |

Cluster: **one-file medium**. Touch with cluster 2; renderer dispatch is real product surface and the silent-disable is a code smell (next section).

#### `src/seon/orchestrator/session.clj`

Required at boot (in `seon.system`'s `(:require [seon.orchestrator.session])`).

| Line | Reference | Class | Action |
|------|-----------|-------|--------|
| 24 | `[seon.db.datalevin.conn :as conn]` | `:live` (dead branch) | Used at line 310-312 only when `datalevin-manager` is non-nil in the request. The `:seon.orchestrator/sessions` Integrant init no longer passes a connection-manager (`system.edn:73-77`), so all callers see `datalevin-manager = nil`. Delete the require and the surrounding `(when datalevin-manager ...)` block. |
| 84-87 | `::datalevin-manager` Malli schema | `:other` | Schema reg + an `:optional true` field in `::start-agent-session-request`. Delete the schema reg + the optional field + the destructure at line 300. |
| 98 | `[::datalevin-manager {:optional true} ::datalevin-manager]` | `:other` | Same as above. |
| 300 | `datalevin-manager` destructure | `:other` | Delete. |
| 308-316 | `(when datalevin-manager (try (conn/get-conn! ...)))` | `:dead-require` (unreachable branch) | Delete the entire `(let [dl-conn ...] ...)` block. |
| 317, 395-400 | `ns-db-name` and the `(when-let [mgr (:seon.db.datalevin/connections ...)])` in stop-agent | `:dead-require` (unreachable) | Delete; `ns-db-name` is always nil now. |

Cluster: **one-file medium**.

#### `src/seon/flow/topology.clj`

Required at boot. Builds the infrastructure flow.

| Line | Reference | Class | Action |
|------|-----------|-------|--------|
| 15-16 | `[seon.db.datalevin.reader :as reader]` + `[seon.db.datalevin.writer :as writer]` | `:live` (gated dead branch) | Aliases used at lines 638, 641 inside the `(when dl? ...)` branch that only fires if a connection-manager is supplied. With the Integrant key absent, `dl?` is always false, but the requires resolve at namespace load. Delete the requires + the entire `(cond-> ... dl? (assoc :seon.flow/writer ...))` and `(cond-> ... dl? (into ...))` blocks. |
| 620, 624 | `::connection-manager` docstring + destructure | `:dead-require` (unreachable arg) | Drop from the schema + the input map. Callers (the `:seon.flow/infrastructure` Integrant key in `system.clj`) need a matching cleanup. |
| 626-628 | comment about disabled datalevin writer | `:doc-only` | Delete with the branch. |

Cluster: **one-file medium**. Cleanly separable.

#### `src/seon/flow/pool.clj`

Required at boot (built by `:seon.flow/pool` Integrant key). Today, no `:datalevin-port` is passed (`system.edn:62-69`).

| Line | Reference | Class | Action |
|------|-----------|-------|--------|
| 25 | docstring "Depends on `:seon.db.datalevin/server`" | `:doc-only` | Already stale — the dep is gone. Rewrite. |
| 115 | `(schema/register! ::datalevin-uri ...)` | `:other` | Plumbed into agent JVM args via `spawn-agent-jvm!`. With no datalevin server, `:datalevin-uri` is always nil. Drop the schema + `& {:keys [datalevin-uri]}` arg + the conditional `["--datalevin-uri" uri]` arg construction (line 158). |
| 150, 154, 158, 192 | `datalevin-uri` parameter + entry shape | `:other` | Delete with above. The `::datalevin-uri` key drops out of the pool entry shape — check `flow.status` and tests for downstream readers. |
| 252-259 | `build-datalevin-uri` fn | `:dead-require` | Used only on line 318 inside `spawn-and-enqueue!` when `datalevin-port` is non-nil. Always nil now. Delete fn + caller. |
| 308, 318-319, 361, 375 | `datalevin-port` / `(build-datalevin-uri ...)` plumbing | `:dead-require` | Delete branch. |
| 560, 570, 574, 583, 594 | `::datalevin-port` config option | `:other` | Public option of `create-pool!`. Delete. |
| 837 | `::datalevin-uri` in `:pool-status` projection | `:other` | Drop from the select-keys. |
| 864, 869, 871, 874 | `:datalevin-server` arg + `(when datalevin-server (:port datalevin-server))` | `:dead-require` | Init-key body — `system.edn` no longer passes it. Delete. |
| 927 | comment example `::datalevin-port 8898` | `:doc-only` | Update. |

Cluster: **one-file medium / cross-file** (callers in tests + status may consume `::datalevin-uri`).

#### `src/seon/ai/claude.clj`

Loaded by `:seon.ai.claude/sdk` Integrant key + HTTP handlers.

| Line | Reference | Class | Action |
|------|-----------|-------|--------|
| 546-548 | `(require 'seon.ai.datalevin) ... (resolve 'seon.ai.datalevin/save-message!)` | `:live` (writes to dead store) | These actually fire when a message is processed. `seon.ai.datalevin/save-message!` reads a private datalevin connection via `seon.ai.datalevin/conn`, which uses the disabled connection-manager. Either (a) port `seon.ai.datalevin/*` storage to datahike (cluster 4 work), or (b) drop the call. Current state: silently no-ops if connection isn't available, but the connection lookup throws. Verify whether claude HTTP path actually completes today. |
| 812-814 | `(:seon.db.datalevin/connections state/system)` lookup for ctx-association | `:live` (returns nil) | Conditionally attaches `::session/datalevin-manager` to the start-session request. Always nil now → the `(when datalevin-manager ...)` branch in `orchestrator.session/start-agent-session!` never fires. Delete with cluster 2. |
| 1364, 1377-78, 1408, 1414-15 | `(requiring-resolve 'seon.ai.datalevin/dl-*)` lookups for session/message reads | `:live` | These run on `/agents` and other status endpoints. They read message history. Port to datahike (`:seon.ai` namespace would migrate into `:seon.db/flow`) or drop the feature. Same disposition as cluster 4 `seon.web.agents`. |

Cluster: **complex / cross-file** — couples to `seon.ai.datalevin` deletion.

#### `src/seon/ai.clj`

Loaded by `seon.ai.claude` and `seon.web.agents`.

| Line | Reference | Class | Action |
|------|-----------|-------|--------|
| 38-48 | `datalevin-write!` fn — dispatches to `seon.ai.datalevin/save-session!` etc. | `:live` | Fires whenever the `seon.ai` API is exercised. Port the writes to a `:seon.ai` datahike conn-process, or drop the persistence path. Pair with cluster 4. |
| 369, 396, 404, 437, 452, 466, 484, 502 | per-op `(datalevin-write! :save-session …)` / `(requiring-resolve 'seon.ai.datalevin/dl-*)` | `:live` | Same disposition. |

Cluster: **complex / cross-file**.

#### `src/seon/dev/test.clj`

Loaded by `(user/test-affected ...)` and test infra.

| Line | Reference | Class | Action |
|------|-----------|-------|--------|
| 318 | `(some? (some-> @... :seon.db.datalevin/connections))` to gate `test-affected` behavior | `:live` (returns false on current boot) | Without datalevin, `has-db?` is false and `test-affected` falls back to just running the ns's own test. Replace with a check against `:seon.db/flow` running. |

Cluster: **one-line trivial**.

#### `src/seon/system/config.clj`

Loaded by `seon.system`'s `assert-key` for `:seon/component`.

| Line | Reference | Class | Action |
|------|-----------|-------|--------|
| 12-22 | `:seon.db.datalevin/server` + `:seon.db.datalevin/connections` Malli config schemas | `:doc-only` (Integrant keys removed from system.edn) | Schemas describe Integrant keys not present in `system.edn`. Delete the entries. |
| 25-27 | `:seon/runtime-db` config schema | `:doc-only` | Same — key not in `system.edn`. Delete. |
| 66 | `[:datalevin-server {:optional true} :any]` in pool config | `:doc-only` | Drop with cluster 2 `flow/pool` cleanup. |

Cluster: **one-file trivial**.

### Dead requires / dead branches in otherwise-live namespaces

#### `src/seon/system.clj`

Required by every boot entry point.

| Line | Reference | Class | Action |
|------|-----------|-------|--------|
| 5 | `:seon.db.datalevin/server - Datalevin server for all data storage` in docstring | `:doc-only` | Stale. |
| 12 | `Datalevin is the sole database.` in docstring | `:doc-only` | Outright wrong now. Rewrite to describe datahike flow + namespace conn-processes. |
| 18 | `[seon.db.datalevin.conn :as dl-conn]` | `:dead-require` | Only used in `runtime-db-conn` + `ig/init-key :seon/runtime-db` (lines 198-214). Those `defmethod`s register for a key that's no longer in `system.edn`, so they never run on boot. Delete the require + the entire `:seon/runtime-db` init/halt/suspend/resume cluster (lines 184-252). |

Cluster: **one-file medium**. The `:seon/runtime-db` deletion takes `runtime-db-conn` with it.

#### `src/seon/ns/routes.clj`

Required by HTTP handlers.

| Line | Reference | Class | Action |
|------|-----------|-------|--------|
| 81 | `[seon.db.datalevin.conn :as dl-conn]` | `:dead-require` (unreachable branch) | Only used at line 127-129 inside `(when-let [mgr (:seon.db.datalevin/connections state/system)] ...)`. Always nil on current boot. Delete the require + the `(when-let [mgr ...] ...)` block. |
| 125 | `(:seon.db.datalevin/connections state/system)` | `:dead-require` | Delete. |

Cluster: **one-file trivial**.

#### `src/seon/graph/ingest.clj`

Required by the graph-scan path. `seon.graph/scanner` Integrant key is also absent from `system.edn` (per the top-of-file comment), so this whole namespace's live invocation is gated.

| Line | Reference | Class | Action |
|------|-----------|-------|--------|
| 32 | `[seon.db.datalevin.conn :as dl-conn]` | `:dead-require` | Used at lines 365 and 421 inside `connection-error?` checks for retry logic. Replace with a non-datalevin error-class check, or drop the retry entirely (the datahike conn-process handles its own errors). |
| 218-225 | `(merge (db-schema/malli-map->datalevin-schema ns-entity-schema) ...)` (8 calls) building `datalevin-schema` | `:bridge-rename` | The 8 `malli-map->datalevin-schema` calls compose the seon.graph schema bundle. Same `:bridge-rename` disposition — convert to `dh-schema/malli-map->datahike-schema` OR delete the `def datalevin-schema` once `runtime.clj`'s `runtime-merged-schema` aggregator goes away (it's the only consumer). |

Cluster: **one-file medium**. Hits part of cluster 1 (bridge-rename) and part of cluster 2.

#### `src/seon/runtime.clj`

Loaded at boot.

| Line | Reference | Class | Action |
|------|-----------|-------|--------|
| 271-273 | 3x `(db-schema/malli-map->datalevin-schema ...)` building `runtime-schema` | `:bridge-rename` | Aggregated into `runtime-merged-schema`, used only by `runtime-db-conn` / `:seon/runtime-db` init-key — both dead per system.clj cleanup. Either delete the `def runtime-schema` + `runtime-merged-schema` entirely, or convert to the datahike bridge if anyone still wants it for introspection. **Strongly recommend deletion** — schema-installation for datahike is owned by `seon.db.datahike.conn-process` via Malli registry, no manual schema merge needed. |
| 280, 288-290 | `runtime-merged-schema`'s lazy-resolution of `seon.graph.ingest/datalevin-schema`, `seon.ctx/datalevin-schema`, `seon.flow.trace/datalevin-schema` | `:dead-require` (chain dies with #1 above) | Delete `runtime-merged-schema`. |
| 542 | `(defn- datalevin->cache ...)` | `:doc-only` (name) | Function converts `:seon.runtime/*` Datalog entity keys to `::namespace`-prefixed cache keys. Logic is db-agnostic. Rename to `entity->cache` or `runtime-entity->cache`. |
| 623 | `(datalevin->cache entity)` call | `:doc-only` | Updates with the rename. |

Cluster: **one-file medium**. Has bridge-rename hits (cluster 1) + dead-code (cluster 2 / 4).

#### `src/seon/ctx.clj`

Loaded by orchestrator/session etc.

| Line | Reference | Class | Action |
|------|-----------|-------|--------|
| 7, 11 | docstring | `:doc-only` | Rewrite "Soft deps: datalevin.core..." once persistence is on datahike. |
| 192-195 | `def datalevin-schema (db-schema/malli-map->datalevin-schema ctx-entity-schema)` | `:bridge-rename` | Same disposition as `runtime.clj`'s schemas — `runtime-merged-schema` was the only consumer, both go away together. |
| 262-263 | `(require 'datalevin.conn) (resolve 'datalevin.conn/closed?)` in `do-persist!` | `:live` (dead branch) | Soft-require to test conn liveness before persist. With `resolve-conn` throwing in the absence of `:seon.db.datalevin/connections`, `conn-usable?` will be false on every call → persistence silently no-ops. **Code smell:** ctx persistence is silently broken on current boot. Either port `do-persist!` to call `(seon.db/transact! :seon.ctx [...])` against a registered datahike namespace, or strip the persistence layer entirely and persist via the orchestrator-session ctx blob (per `phase-3-harness-migration.md` Decision 27 / Open Q1). |

Cluster: **complex** — touches the in-pod state-projection design from spec-01 Decision 27.

#### `src/seon/agent/env.clj`

| Line | Reference | Class | Action |
|------|-----------|-------|--------|
| 61-62 | `(def datalevin-schema "Alias for seon.ctx/datalevin-schema" ctx/datalevin-schema)` | `:bridge-rename` | Re-export of `seon.ctx/datalevin-schema`. Goes away with `seon.ctx`'s cleanup. Verify no agent-side code reaches for this; if so, those references migrate too. |

Cluster: **one-line trivial**, but depends on `seon.ctx` cleanup.

#### `src/seon/flow/trace.clj`

| Line | Reference | Class | Action |
|------|-----------|-------|--------|
| 82-86 | `(def datalevin-schema (merge (dbs/malli-map->datalevin-schema entity-schema) tx/datalevin-schema))` | `:bridge-rename` | `seon.flow.trace` IS in `:seon.db/flow` (`system.edn:115`), so writes go through the datahike conn-process which derives its own schema from Malli. **The `def datalevin-schema` is dead** — nobody on the boot path reads it. Delete. Knock-on: `seon.runtime/runtime-merged-schema` references this — also dead per `seon.runtime` cleanup. |

Cluster: **one-line trivial**.

#### `src/seon/db/tx.clj`

| Line | Reference | Class | Action |
|------|-----------|-------|--------|
| 48-51 | `(def datalevin-schema (dbs/malli-map->datalevin-schema entity-schema))` | `:bridge-rename` | Tx-metadata schema. Consumers: `seon.flow.trace/datalevin-schema` (dead per above), `seon.ai.datalevin/datalevin-schema` (dead-ns per below). After those go, this def has no callers and can be deleted. |

Cluster: **one-line trivial**.

#### `src/seon/db/schema.clj`

Owns the bridge fn itself.

| Line | Reference | Class | Action |
|------|-----------|-------|--------|
| 7-8 | docstring | `:doc-only` | Rewrite to point at the datahike bridge in `seon.db.datahike.schema`. |
| 57 | `malli-type->datalevin-type` fn | `:dead-require` (after cluster 1) | After all bridge-callers migrate, this whole file's purpose is gone — `seon.db.datahike.schema` is the new home. Decide: delete the file outright, or keep as a shim that re-exports the datahike fns under the legacy names for a deprecation window. Recommend delete. |
| 87, 98, 107, 112, 116, 126, 138, 147, 164, 175, 372 | various private helpers + the public `malli-map->datalevin-schema` | `:dead-require` (after cluster 1) | All go with the file. |

Cluster: **one-file medium**. Don't delete until cluster 1 is done.

#### `src/seon/db/datahike/schema.clj`

The datahike-side bridge. Already in active use by `seon.db.datahike.conn-process/install-schema!` (line 106).

| Line | Reference | Class | Action |
|------|-----------|-------|--------|
| 25 | docstring | n/a | Healthy. |
| 61 | docstring reference `seon.db.schema/malli-type->datalevin-type` | `:doc-only` | Rewrite once `seon.db.schema` is deleted. |
| 244 | docstring `the shape of seon.db.schema/malli-map->datalevin-schema` | `:doc-only` | Rewrite once `seon.db.schema` is deleted. |

Cluster: **one-line trivial doc fix**.

### Dead namespaces (delete after cluster 2 lands)

#### `src/seon/db/datalevin/` directory (5 files)

`backup.clj`, `conn.clj`, `reader.clj`, `server.clj`, `writer.clj`. Every reference inside is `:dead-ns`. Reachable today only from:

- `seon.db` `(:require [seon.db.datalevin.conn] [seon.db.datalevin.reader])` — cluster 3.
- `seon.flow.topology` `(:require [seon.db.datalevin.reader] [seon.db.datalevin.writer])` — cluster 2.
- `seon.system` `(:require [seon.db.datalevin.conn :as dl-conn])` — cluster 2.
- `seon.render`, `seon.ns.routes`, `seon.graph.ingest`, `seon.orchestrator.session` — cluster 2.
- `seon.health` — cluster 2 (see below).

Once all those requires are deleted, `rm -r src/seon/db/datalevin/`. Knock-on: drop `[datalevin/datalevin {:local/root "reference-code/datalevin"}]` from `deps.edn` `:dev` / `:test` / `:agent` aliases (per spec-01 §"Direction shift" item 6 — this is the wart that requires `git submodule update --init reference-code/datalevin` + `clojure -T:build compile-java` on a fresh clone).

Cluster: **cross-file complex** but each individual deletion is a single `rm` once references are clear.

#### `src/seon/ai/datalevin.clj`

| Class | Action |
|-------|--------|
| `:dead-ns` (when callers migrate) | The file is the AI message/session storage layer. ~25 datalevin hits inside the file describing entity types, queries, transformation. Currently exercised through `seon.ai.clj` `datalevin-write!` and `seon.ai.claude.clj` `requiring-resolve` calls — both currently route to it. **Delete the file after porting the AI session/message storage to a `:seon.ai` datahike namespace (or dropping it from the boot path).** Cluster 4. |

Cluster: **cross-file complex**.

#### `src/seon/web/agents.clj`

| Class | Action |
|-------|--------|
| `:dead-ns` (depends on `seon.ai.datalevin`) | Whole file consumes `seon.ai.datalevin/dl-*` queries to render an agent observatory. Goes with cluster 4. Verify the `/agents` HTTP route is registered before deciding: if it's already broken (404), delete; if it's wired into `seon.web.handlers`, migrate the data source. |

Cluster: **complex / cross-file**.

#### `src/seon/health.clj`

| Line | Reference | Class | Action |
|------|-----------|-------|--------|
| 129, 139, 146, 148, 150-160 | `datalevin-process-info` + `check-datalevin` fns | `:live` (dead store probed) | These health checks probe `:seon.db.datalevin/server` + `:seon.db.datalevin/connections`. Both absent on current boot, so `check-datalevin` will hit the fallthrough returning some unhealthy status. The `(user/status)` summary still includes a `:datalevin` key. Either (a) delete both fns + remove `:datalevin` from the health checks map (line 325), or (b) repurpose as `check-datahike` reading from the running `:seon.db/flow` component. |
| 274 | docstring `datalevin or flow down` | `:doc-only` | Rewrite. |
| 277, 315, 325 | `critical-keys [:datalevin]` + `:datalevin datalevin-check` | `:live` | Critical-key listing — currently flags the system as unhealthy. **Code smell: anyone running `(user/status)` today gets a misleading unhealthy reading.** Delete `:datalevin` from `critical-keys` + the `:datalevin datalevin-check` entry, or replace with the datahike-flow check. |

Cluster: **one-file medium**. Move to cluster 2.

#### `src/seon/test/bootstrap.clj` + `bootstrap_v1_inmemory.clj`

| Class | Action |
|-------|--------|
| `:dead-ns` (POC) | Both declare `(ns seon.test.bootstrap)` — they shadow each other. `_v1_inmemory.clj` is the canonical name now; `bootstrap.clj` is an older draft. Both directly use `datalevin.core :as d` (line 17). Neither is required from any production code path; they're standalone POCs documented as Phase 2/3 exploration in their own docstrings. Delete `bootstrap.clj`. Decide whether `bootstrap_v1_inmemory.clj` and `bootstrap_v2.clj` serve as live test fixtures (run via `(user/run-tests ...)`) before deciding. |

Cluster: **one-file trivial**.

#### `src/seon/test/bootstrap_v2.clj` (27 datalevin hits)

| Class | Action |
|-------|--------|
| `:dead-ns` (POC) | Standalone POC of "embedded datalevin per test." Body declares `defn- with-embedded-datalevin` and ~17 fixture-style call sites. Not part of any reachable test entry. Same disposition as `bootstrap.clj` — confirm no caller, then delete or port to datahike `:memory`. The latter is more useful long-term (datahike `:memory` is the documented test backend per `prd.md` §"Configuration"). |

Cluster: **one-file medium** (if porting), **one-file trivial** (if deleting).

#### `src/seon/flow/agent_runner.clj`

| Line | Reference | Class | Action |
|------|-----------|-------|--------|
| 24, 34, 39, 44, 67, 71, 95 | `--datalevin-uri` CLI arg + `try-connect-datalevin` + connection result | `:dead-require` (unreachable) | Agent JVMs receive `--datalevin-uri` only when `flow/pool` constructs one (line 318) — guarded by `datalevin-port`, always nil on current boot. With cluster 2 cleanup of `flow/pool`, this code path is unreachable. Delete the option-parsing + the `try-connect-datalevin` fn + the `:datalevin (if db-conn :connected :unavailable)` field in the status map. |

Cluster: **one-file medium**, pair with `flow/pool` cleanup.

### Doc-only sweep

Once the code clusters land, scrub remaining strings:

- `src/seon/db.clj` lines 13, 20, 243, 255, 391, 436 — narrative about "legacy datalevin path."
- `src/seon/db/datahike/schema.clj` lines 61, 244 — docstring back-refs to the datalevin bridge.
- `src/seon/system.clj` line 5 — Integrant key listing.
- `src/seon/system.clj` line 12 — "Datalevin is the sole database."
- `src/seon/flow/pool.clj` line 25, 927 — depends-on comment + example pool config.
- `src/seon/ctx.clj` lines 7, 11 — soft-dep description.
- `src/seon/health.clj` line 274 — `datalevin or flow down`.

Plus `seon/CLAUDE.md` and `docs/` prose, which is out of scope for this audit but should be batched at the same time.

## Code smells observed

Per the seon CLAUDE.md "Report Code Smells" rule. None of these are blocking boot; they're inconsistencies surfaced by walking the codebase.

1. **`seon.render`'s `call-datalevin-renderer` silently returns nil on every call.** With `:seon.db.datalevin/connections` absent from `system.edn`, `get-conn` returns nil → `resolve-renderer-from-datalevin` short-circuits to `::no-renderer`. Any HTTP handler that calls `seon.render/try-render` gets nil and falls back to default rendering. This means the "render functions discovered via `:seon.render/html` / `:seon.render/ai` schema annotations" feature documented in `phase-3-harness-migration.md` §"Rendered dynamic context" is **non-functional on the current boot**. Anyone testing rendering today would see fallback output and not know auto-discovery was broken. **Likely-correct fix:** route `find-renderer` through `seon.db/query` against `:seon.runtime` (db-name, not conn), but `:seon.runtime` itself isn't in `:seon.db/flow` yet (per `system.edn:111` — adding it regresses ~111 tests). So this is blocked behind `:seon.runtime` migration.

2. **`seon.health/check-datalevin` flags the system as unhealthy.** `:datalevin` is in `critical-keys` (line 277). Without the Integrant components, the check fails. So `(user/status)` reports the system as unhealthy on every boot, while boot is in fact succeeding cleanly per spec-01's 2026-05-14 audit. This is misleading to operators (and to the spec, which calls out "post-start health check passed at 30s" — it likely passed because the check is misconfigured to be soft, or because the spec captured a moment before health was wired into the boot path; either way, the truth and the reading don't match).

3. **`seon.ctx/do-persist!` silently no-ops ctx persistence.** Same root cause as #1 — `resolve-conn` throws "Connection manager not available," `conn-usable?` is false, persistence skips. The agent-orchestrator-session flow depends on ctx-checkpointing for resume semantics (per `phase-3-harness-migration.md` Demo Target step 4). Until the ctx blob is checkpointed to `:seon.session/ctx` via the datahike route, resume returns an empty `*ctx*` atom. Verify whether the demo target (which the PRD says is shipped, `9455f3f`) actually exercises the persistence-resume cycle or just the in-memory portion.

4. **`seon.test.bootstrap` namespace double-declared.** Both `src/seon/test/bootstrap.clj` and `src/seon/test/bootstrap_v1_inmemory.clj` start with `(ns seon.test.bootstrap)`. Whichever Clojure compiler hits second wins; the other is silently shadowed. The naming convention `_v1_inmemory` suggests the second is canonical; `bootstrap.clj` was probably forgotten. Pick one.

5. **`seon.db.tx/datalevin-schema` only consumers are themselves dead.** `seon.flow.trace/datalevin-schema` (dead — see cluster 1 disposition), `seon.ai.datalevin/datalevin-schema` (dead-ns). The `def datalevin-schema` in `seon.db.tx` is an orphan once those go.

6. **`seon.runtime/runtime-merged-schema` is consumed only by dead code.** Callers: `system.clj` `runtime-db-conn` + `:seon/runtime-db` init-key (Integrant key removed from system.edn), `render.clj` `get-conn` (returns nil), `ns/routes.clj` `(when-let [mgr ...] ...)` (mgr always nil). Three callers all on a dead branch — the schema-merge code that aggregates `graph.ingest/datalevin-schema`, `ctx/datalevin-schema`, `flow.trace/datalevin-schema`, and `runtime-schema` is unreachable.

7. **`orchestrator/session.clj` registers `::datalevin-manager` as a Malli schema with `:gen/fmap` throwing.** This was a hack to mark the field as non-generative for property tests. The field is now never populated. Schema reg + optional-field declaration + destructure all reference a dead concept; cleaning them up makes the API surface honest.

8. **`seon.db/transact!` legacy branch (`:else`) throws on every call to an unregistered db-name.** With cluster 3 deletions, the `:else` branch can be replaced with an explicit `(throw (ex-info "No conn-process for db-name; not registered in :seon.db/flow" {...}))` — better operator UX than the current "Connection manager not available -- is the system running?" message that misleads about root cause.

9. **`seon.flow.pool` carries datalevin-uri plumbing in its public API surface (`::datalevin-port`, `::datalevin-uri` in pool status, `:datalevin-server` Integrant arg).** External callers building pools could conceivably pass these. Verify nothing in `test/` or `bin/` constructs a pool with these keys before deletion; otherwise the API break needs a deprecation note.

## Code smells surfaced during Stage 2.1 test migration (2026-05-14)

Surfaced by the focused agents that migrated `seon.orchestrator.session-test`, `seon.health.workout-test`, and `seon.db.pipeline-test` to the new datahike `:memory` fixture. Each is independent of the migration's correctness — the tests are green — but represents a latent design gap that will recur as more callers move to the datahike side.

10. ~~**Malli→datahike bridge can't express intra-DB lookup-refs cleanly.**~~ **Resolved 2026-05-14.** The `:seon.db/local-ref` proposal is moot. `:seon.db/ref` now means intra-DB `:db.type/ref` directly (see Decision 10 in `decisions.md` and `ref-model-research.md`). The bridge maps `:seon.db/ref → :db.type/ref`, and the `:or {:seon.db/value-type :db.type/ref}` workaround in `workout-test` was reverted. Cross-DB handles use plain `:uuid` with `:seon.db/ref-to` metadata — they are never labeled `:seon.db/ref`.

### Still open

11. **`seon.graph.extract` emits row counts as `java.lang.Integer`; the datahike bridge is strict.** `extract-graph-from-file` populates `:seon.fn/row`, `:seon.var/row`, `:seon.call/row` (and possibly more) as Integers. Malli `:int` validates either Integer or Long. Datalevin tolerated Integer for `:db.type/long` attrs; datahike does not — `d/transact` throws on Integer values into a Long attribute. Worked around in `workout-test` with a local `coerce-ints->longs` helper. **Real fix candidates:** (a) emit `(long ...)` at the source in `seon.graph.extract`; (b) Long-coerce on transact inside `seon.db.datahike.conn-process`'s tx-data handler; (c) widen the Malli schema reg to `[:int {:db.type :db.type/long}]` with bridge coercion. Option (a) is cleanest but doesn't catch the next caller; option (b) is centralizing. Worth one decision before more callers paper over the same issue.

12. **Datahike `:or` schemas need an explicit `:seon.db/value-type` property.** `seon.orchestrator.session-test`'s schema-widening required `[:or {:seon.db/value-type :db.type/string} [:string {:min 1}] :symbol]` for the datahike bridge to install the `::namespace` attribute. Without the property, the bridge has no way to pick a `:db/valueType` from a polymorphic `:or`. The behavior is correct but undocumented — any other Malli `:or` schema landing in a datahike-flow namespace will need the same annotation. Worth a doc paragraph in `seon.db.datahike.schema` and an explicit error message when the property is absent (currently the failure mode is a confusing install-time throw at conn-process `:init`).

13. ~~**No canonical `tu/transact-full-graph!` helper.**~~ **Resolved 2026-05-14.** `seon.test-utils/transact-full-graph!` now encapsulates the dependency order (namespaces → specs → shape stubs → entries → full shapes → functions → vars → call-edges → ns-deps), the shape↔entry cycle break (stub-then-fill), and the Integer→Long coercion of `:seon.fn/row` / `:seon.var/row` / `:seon.call/row` from smell #11. `workout-test`'s inline `coerce-ints->longs` helper deleted; integration test now calls `(tu/transact-full-graph! {::tu/db-name :seon.runtime ::tu/graph graph})`. Future tests use the helper rather than re-deriving the order. (Smell #11 itself stays open — the coercion really belongs in `seon.graph.extract` or the datahike bridge, not in test infra.)

14. **`session-entity-schema` has three sources of truth.** Top-level `def` in `seon.orchestrator.session` + `db-schema/register-entity-schema!` call + the fixture's `::schemas` map re-passing the same schema. Three places define what `:seon.orchestrator` persists; drift between any two creates a silent install/validate mismatch. Consolidate so the source of truth is the Malli registry alone, and the fixture (and live boot) read from the registry by db-name rather than re-passing the schema literal.

15. **`recover-sessions!` doesn't touch the orchestrator DB.** Only the runtime registry. `recover-sessions-test` passes in `session-test` even when the live `:seon.orchestrator` DB is being contaminated by other tests in the suite — the fixture binding for `:seon.orchestrator` was never load-bearing for that single test, only for the surrounding 13 that DO write to the DB. Not a bug; the fixture migration is still correct (the contamination was real). Worth noting in case anyone audits the fixture's per-test coverage.

16. **`render/set-conn!` is dead for any datahike-routed caller.** Pre-migration `workout-test` called `(render/set-conn! *conn*)` before `try-render`/`has-renderer?`. That sets `*conn-override*` for the deprecated `get-conn` path in `render.clj`, but `find-renderer` / `try-render` / `has-renderer?` already resolve through `seon.db/query` via `gq/functions-with-output-key`. The override never participates in those calls. Reflects the broader `render.clj` cluster-2 connection-manager rot (smell #1 above). The `set-conn!` API can be deleted once cluster 2 lands; flagging here so the cluster-2 agent knows to remove it.

17. **`seon.runtime/register!` log-spams `WARN ... "Connection manager not available -- is the system running?"` from inside every session-test.** The runtime registry tries to persist via a live datalevin connection-manager that doesn't exist in test mode; the in-memory fallback works, but the warn-spam pollutes test output and obscures real warnings. `seon.runtime` should either tolerate the missing manager silently when no datahike-flow path is wired up, or get its own datahike-fixture pathway. Same root cause as smell #1/#3/#6.

18. ~~**`:seon.db/ref` Malli predicate is datalevin-shaped, not datahike-shaped — ref roundtripping through `seon.db/transact!` is broken.**~~ **Resolved 2026-05-14.** `:seon.db/ref` is now intra-DB `:db.type/ref` (Decision 10). The registered Malli predicate accepts `:int` (pos-int eid OR neg-int tempid), `:string` (string tempid), and `[:tuple :keyword :seon.db/lookup-ref-value]` (lookup-ref). UUIDs are NOT valid `:seon.db/ref` values — cross-DB handles use plain `:uuid`. The five dropped pipeline-test ref-roundtrip cases (cross-namespace, agent-run, fn-spec, fn-shape, call) are restored.

19. ~~**`seon.flow.trace.clj:35-37` has a malformed `:enum` declaration that datahike correctly rejects but datalevin silently swallowed.**~~ **Resolved 2026-05-14.** Properties map moved before the enum values on both `::event` (line 35-37) and `::status` (line 51-53) — same bug, both sites. `pipeline-test`'s `trace-entity-pipeline-test` restored.

20. **Entity schemas declare attrs with plain leaf types while the corresponding registered schemas are stricter.** E.g. an entity schema has `[:seon.fn/row :int]` but `(schema/register! ::row [:int {:min 0}])`. The Malli generator runs against the entity schema's loose form and produces values (negative ints, empty strings) that `seon.db/transact!`'s `validate-values!` then rejects against the registered stricter form. `pipeline-test` works around this in test with an `align-with-registered-schemas` helper that rewrites map entries to the registered attr keyword. **Real fix:** entity schemas should reference registered attr keywords directly (`[:seon.fn/row :seon.fn/row]` or just `[:seon.fn/row]` if Malli's map-entry-shorthand allows). The result: one source of truth per attr, no generator drift, no helper needed. Cross-cuts every entity schema in the codebase.

## Forward decisions (recorded 2026-05-14)

Direction captured from Sean before cluster 2 dispatch — recorded here so the guidance survives the conversation and informs the agents doing the work.

### Renderer auto-resolution: deferred

`seon.render`'s `find-renderer` / `try-render` / `has-renderer?` are silently broken on the current boot (smell #1) because they depend on a datalevin connection-manager that's not in `system.edn`. Sean's call: **defer the fix.** For the consumer (and anything else consuming this), use **explicit rendering** until further notice — callers pass the render fn directly rather than relying on auto-discovery via `:seon.render/html` / `:seon.render/ai` schema annotations.

Cluster 2 implication: don't try to rewire `find-renderer` through `seon.db/query` as part of cluster 2 cleanup. **Just delete the silently-dead datalevin-connection-manager paths and the `set-conn!` API (smell #16); leave the rest of `seon.render` alone for now.** A future pass restores auto-resolution if/when the registry surface gets redesigned. Consumers work around it via explicit render fns.

### `:seon.runtime` and `:seon.ai` migration to datahike: in scope, separate clusters

Both namespaces currently write to dead datalevin stores (smells #3, #17 and the `seon.ai.claude` `requiring-resolve` calls in remaining.md). The fix is to register each as a datahike-flow namespace and convert callers to `seon.db/transact!` / `seon.db/query`.

Sean's note on `seon.ai`: the LLM code paths (`seon.ai.claude.clj`, `seon.ai.clj`, related) predate the litellm abstraction — they're hand-rolled provider-specific calls. When migrating storage, **flag** the call sites that would benefit from routing through a litellm-style "one API, many providers" abstraction. **Don't** fix the abstraction itself in the same pass; just leave breadcrumbs (e.g. `;; FIXME(litellm): provider-specific Claude SDK call — abstract through litellm post-migration`). The litellm refactor is its own focused work.

These are **separate clusters from cluster 2/3/4** (which are about killing datalevin substrate). Schedule after cluster 4 lands.

### `*ctx*` redesign: atom semantics + auto-persist with warn-on-unserializable

Sean's design for `seon.ctx/*ctx*` (replaces the current `do-persist!` silent no-op, smell #3):

> "I want the `*ctx*` atom to be an easy to use atom that works like atoms work and then directly writes updates to the datahike entity when changes occur and if parts can't make the write transition because they are any objects or just aren't serializable then those just return warnings."

Contract:
- **In-memory atom is the source of truth.** Use stock `clojure.core` atom semantics — `swap!`, `reset!`, `deref`, `add-watch`. Callers see no difference from a normal atom.
- **Writes flow through to a datahike entity automatically.** On change (likely via `add-watch` internally), the new value is transacted into `:seon.session/ctx` (or wherever Sean's session-scoped entity model lands).
- **Unserializable values warn, don't fail.** If part of the atom holds something datahike can't store (raw Java objects, channels, function values, connections), the persist attempt logs a warning naming the unserializable key+path; the in-memory atom keeps working with that value. The persisted form simply omits or marks-as-skipped those keys.
- **Resume reads from datahike on session restart.** The agent's previous ctx state rematerializes when the session is recovered.

Implementation lands AFTER cluster 4 (so the datahike flow is the only persistence surface) and AFTER `:seon.runtime` / `:seon.ai` migrations land (so the patterns for "register a namespace with the flow + read schema from registry + bind a per-session entity" are settled).

This is the load-bearing piece for session-resume semantics in spec-01 (Decision 27 / "Demo Target step 4"). Worth getting right.

## Forward decisions (recorded 2026-05-15)

A deeper architecture direction than the cluster-cleanup forward decisions above. Captured because the renderer redesign turned out to be one piece of a larger agent-runtime model.

### Renderer redesign — Path D resolved (full details in `renderer-redesign-proposal.md`)

After the initial proposal (commit f80cd6e), Sean's follow-up direction and the Malli-defaults research (`malli-defaults-research.md`, commit 0bb5936) converged on a clean answer. Full details + revised phasing in commit 1cece5a of the proposal. Headline decisions:

- **`schema/register!` stays single-arity.** No new API.
- **Path D: polymorphic value-types** for the render keys. `:seon.render/ai` is `[:or :string :symbol]`; `:seon.render/html` is `[:or :seon.render/hiccup :symbol]`. Map-key surface only (no metadata path). Schema's stock Malli `:default` is a symbol pointing at the render fn.
- **Boundary algorithm** (stock Malli, no seon-side transformer): `(m/decode schema entity (mt/default-value-transformer))` fills missing keys with the default symbol; the boundary inspects the value — string/hiccup → use directly; symbol → `requiring-resolve` + call with the entity.
- **Inline shorthand for agent → user messages**: agent transacts a message map with a symbol at `:seon.render/html` pointing at the renderer of their choice. The transaction listener (event-sourcing model below) picks it up, the boundary resolves, the user sees the HTML fragment.
- **`:seon.render/hiccup` is a global registration** of the existing schema at `seon.web.reactive.transform:33-40`. Local def becomes a re-export. One source of truth for Datastar-safe hiccup.
- **HUD = single `:seon.session/hud-renderer` symbol** + `:seon.session/widgets` vector. Default ships; agent overrides via `(seon.session/set-hud-renderer!)`. Same machinery serves the agent's REPL AND the user's browser view.
- **Suggest-on-nil always-on with per-session toggle** to silence. Matches "default harness should be surfacing relevant fns."
- **Resolver**: `requiring-resolve` (CLJ); CLJS substrate will bundle the CLJS compiler into the agent pool (precompiled into the pool image to amortize startup). No sci.
- **R0 cluster-2 scope shrunk dramatically.** Only delete actively-broken datalevin-dependent paths in `seon.render` (~40–80 lines). Auto-discovery / specificity-sort / namespace-proximity stay dormant. Sean: "let's prune at the end once we have a working system." Original 250-line deletion deferred to a post-MVP prune pass.
- **REPL eval auto-persist** (proposal §K): every result transacted to datahike with warn-on-unserializable. Enables time-travel debug, session resume from any timestamp, long-lived specialized agents (archival, maintenance). Rides on the event-sourcing architecture below; lands after cluster 4 + `:seon.runtime`/`:seon.ai` migrations.

### Everything-through-the-database + transaction-listener-driven reactions

> "I want everything that affects the system to be committed to the database and for transaction listeners to explicitly show the reactions. So an ai agent receiving a message from the user it's going to be committed as a message that's spec'ed as a user message and we want to have an efficient listener to the transaction log that's looking for that and then engaging the agent loop to respond to it. Everything should be built around adding/updating/deleting data from the database and the reactions that follow for emergent behaviors."

This is an event-sourcing model with datahike as the event log:

- **User input → datahike entity** (`:seon.user-message` or similar, conforming to a registered schema).
- **Transaction listener detects new entity** → fires the agent loop.
- **Agent emits response → datahike entity** (`:seon.agent-message` or similar).
- **Another listener detects → emits update to user** (SSE, MCP, whatever the surface is).
- **All reactions are explicit listeners**, registered by name. The agent can read the listener fn definitions and understand what's happening in their flow.

`seon.db.datahike.flow/tx-bus` (already exists per `prd.md` §Topology) is the substrate. The new work is the convention layer: which entity schemas trigger which agent-loop behavior, and how listeners register declaratively.

Implication: the renderer's job extends — when an agent-message hits the boundary heading *to the user*, the same render mechanism (`:seon.render/html` fragments via SSE) is how the user sees the agent's output. Same machinery, two surfaces (agent's REPL, user's browser).

Implementation phasing: lands AFTER the datalevin removal (cluster 2/3/4) and the `:seon.runtime` / `:seon.ai` migrations, because it depends on every relevant namespace being on the datahike flow.

### Auto-saved REPL results — the queryable agent log

> "I like how every value that comes back is auto saved and the agent can query it. I think that's a feature of a real repl... One example is when you run tests the test report is huge and if you grep for something and it's not there you end up running it again and again which is wasteful. I want to save everything for the agent to easily reference and they can modify the render functions for the data and recall it and it'll be rendered exactly as they want."

The existing `:r-NNNN` keys in `user/repl-orchestrator` (auto-saved every eval) are the seed of this feature. Direction for extension:

- **Persist across sessions** — saved results live in the agent's `:seon.session` (or `:seon.session.repl`) entity, not just an in-memory atom that dies on JVM restart.
- **Indexed for query** — agent can ask "find me the result from test-run that mentioned X" without re-running.
- **Renderable on recall** — the agent can attach a render fn to a saved result; subsequent `(deref :r-NNNN)` displays it that way.
- **Lifecycle controls** — agent decides what to keep, what to GC. (Default: ring buffer of last N? Compress on write? Open question.)

This is part of the broader "ctx is the agent's customizable workspace" model. Auto-saved results aren't separate from ctx; they're ctx entries the agent can elevate to widgets.

### HUD = render fn over the database

> "The hud is just a render function on the database that the agent can customize by overwriting the default one we setup for it."

Per-agent HUD model (replaces the prior `:seon.session/render-overrides` map proposal):

- Each agent has a `:seon.session/hud-renderer` attr: a qualified-symbol pointer to a render fn.
- Default ships with seon; agent overrides by `(seon.session/set-hud-renderer! 'my.ns/my-hud)`.
- The HUD renderer takes the agent's session/ctx + the world DB and produces whatever the agent wants to see — typically a hiccup structure that becomes the HTML fragment the user sees AND/OR an `:ai`-rendered string the agent's own REPL displays.
- "Attaching widgets to the HUD" = the agent stores fn pointers in their ctx; the HUD render fn pulls them and includes their output. Fns with declared args (per seon's existing schema reg) become first-class data tools the agent can call from anywhere.

### Agent context = datahike entity; specialized agents; multi-agent

> "Different agent sessions would just be different entity's with different kv pairs of attributes and values — therefore different views into the system. You can have specialized agents for anything and we want a way for the agents to launch other agents and to get data back in terms of listening to the transaction log for their updates."

The substrate model from spec-01 sharpened:

- An "agent" is a `:seon.session` (or `:seon.agent`) entity. Identity is the entity-id; behavior is determined by which attrs/values are populated.
- Specialized agents are just session entities with specialized ctx attrs (e.g., `:seon.session/role :test-runner` or whatever the convention becomes).
- Parent-child agent spawning: a parent agent transacts a new `:seon.session` entity → a "session-spawned" transaction-listener boots that agent's runtime. The parent listens to the child's transaction log entries (the child's writes are all in the same database; the parent's listener filters by session-id).
- Data flows from child to parent via the transaction log, not via direct return values. Every "result" is a transacted entity the parent can listen for.

This is the agent fleet model. The renderer + ctx + transaction-listener pieces all serve it.

### Open architecture questions (next round)

- **Listener registration convention.** Where does "this listener handles `:seon.user-message`" live? In a registry? As metadata on the entity schema? Per-session subscriptions? Need to design before transaction-listener-driven reactions can be implemented.
- **Result auto-save semantics.** Every result, or opt-in (some are huge)? Compression? GC? Cross-session-id retention?
- **HUD rendering frequency.** On every change, on agent-explicit refresh, on a debounce? Wire to SSE for browser updates.
- **Agent-spawning API.** Datahike transact creates the entity; what bootstraps the child's runtime? Integrant component-per-session? A pool?

These are all post-cluster-4 work but worth recording now so the design space stays visible.

## M-2b test ports — deferred to M-3 (2026-05-15)

M-2 deleted 26 test files in `b26fcc9`. M-2b restored 13 of them as
ports to the canonical `tu/with-test-db-fixture` against datahike
`:memory`. The 13 ported across 6 commits: `fce007f` (db_test +
validation_test), `69ccadb` (runtime + ctx + agent.env tests),
`f5428b4` (repl trio), `1d50b78` (render pair), `90a9a70` (ns.lifecycle),
`279d832` (flow.trace), `61070cd` (ns.lifecycle with-redefs fix for
ensure-instance Malli instrumentation under full-suite).

Suite at end of M-2b: **655 tests / 2803 pass / 0 fail / 0 err /
`:success true`** (was 501 / 2388 at end of M-2; +154 tests / +415
assertions from the ports).

### Stays deleted permanently (5 files)

Their subject is gone with the substrate; the test's premise no
longer exists.

- `test/seon/ai/datalevin_test.clj` — `seon.ai.datalevin` namespace removed in M-2.
- `test/seon/db/datalevin/backup_test.clj` — subject removed.
- `test/seon/db/datalevin/writer_test.clj` — subject removed.
- `test/seon/db/schema_test.clj` — tested the deleted Malli→datalevin bridge fn.
- `test/seon/db/schema_roundtrip_test.clj` — same.
- `test/seon/flow/infrastructure_test.clj` — tested `seon.db.datalevin.writer/infra-writer-step` which was deleted in M-2's `3285546`.

### Deferred to M-3 — 6 files (need working `seon.graph.ingest`)

All six rely on `seon.graph.ingest/ingest-analysis!` or
`ingest-incremental!` to populate the fixture's graph. Both ingest
fns assume `[:seon.fn/qualified-name "..."]`-style lookup-refs
resolve against entities transacted earlier in the same call.
Datalevin tolerated forward references; datahike throws
`:entity-id/missing`. The fix lives in `seon.graph.ingest` (either
upsert-first then ref, or rewrite same-batch refs to datahike tempid
strings via the same pattern `tu/transact-full-graph!` uses for the
shape↔entry cycle). M-3 owns this.

Once ingest works:

- `test/seon/graph/context_test.clj` — analyzer + ingest of `src/seon/graph/`, then exercises `seon.graph.context/build` + `build-for-namespace`.
- `test/seon/graph/ingest_test.clj` — tests `ingest-analysis!` itself end-to-end.
- `test/seon/graph/query_test.clj` — populated-graph queries: call-graph, functions-with-output-key, transitive-dependents-of.
- `test/seon/graph/shape_test.clj` — uses `seon.test.bootstrap` (deleted in M-2) — port needs a `with-test-bootstrap` replacement; the shape-walker + ingest + discovery surface.
- `test/seon/graph/shape_generative_test.clj` — property-based; also uses `seon.test.bootstrap`.
- `test/seon/dev/test_select_test.clj` — `affected-namespaces` + `run-affected-tests!` against a populated graph.

### Partial ports flagging M-3 / M-4 work

- `seon.repl-test/code-index-updated-test` — dropped pending M-3 (`eval-form!`'s `update-code-index!` calls `ingest-incremental!`; same lookup-ref bug as above).
- `seon.ctx-test` persist-load round-trip tests — dropped pending M-4 (`ctx/persist!` calls into the deprecated `seon.db/resolve-conn` shim; M-4 redesigns *ctx* with atom-semantics + auto-persist).
- `seon.ns.lifecycle-test/instance-resume-round-trip-test` + `backup-all-instances-test` — same M-4 disposition; both exercise `ctx/persist!`.
- `seon.ns.lifecycle-test` ensure-instance-* tests — `with-redefs inject-vars!` because the prod fn assoc's `::db-name nil` into the downstream call and Malli instrumentation rejects it. M-3/M-4 cleanup of the *conn* injection path should let the stub go away.

## Resolved during Stage 2.1 test migration (2026-05-14)

Tracked here so the history of what's been fixed stays visible alongside what remains. Each "Resolved" entry quotes the original smell text and links to its fix commit.
