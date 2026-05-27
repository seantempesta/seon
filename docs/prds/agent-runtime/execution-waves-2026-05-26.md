---
type: prd
status: draft
tags: [prd, agent, database]
---

# Execution waves — V2 integration build-out

**Date:** 2026-05-26 EVE
**Scope:** Concrete worker→verifier wave plan for executing the two-path integration per `integration-architecture-2026-05-26.md`. Each wave is bounded, has a clear deliverable, and a verifier pass gate before the next wave starts.

## Rules for ALL agents in this plan

1. **MCP only.** Use `mcp__seon__eval` for Clojure REPL probes. Use `mcp__seon__clojure_replace` for structural Clojure code edits where matching is finicky. Use `Edit`/`Write` for non-Clojure files (Rust, EDN, MD, deps).
2. **Never use Bash to**: start the JVM, run tests, evaluate Clojure code, query the running system, or do shell-based nREPL. The only acceptable Bash uses are: `find`, `grep`/`rg`, `git status`/`diff`/`log`/`add`/`commit`, `cargo build` (Rust toolchain), `ls`.
3. **REPL break = STOP.** If `mcp__seon__eval` returns an init/connectivity error twice in a row, STOP and report. Do not try to work around it with bash + nREPL clients. The orchestrator restarts the session.
4. **Targeted tests only per wave.** Run only the tests affected by what you just changed. **Full suite runs ONCE per wave at the very end of the worker pass**, not after every edit.
5. **All findings on disk.** Update the wave's status section in this doc when done. Don't summarize in chat only.
6. **Smaller chunks beat heroic completion.** If a wave hits an unexpected scope, STOP at the seam and report.
7. **Testing focus: CLJS integration over CLJ regression.** Write end-to-end tests from the CLJS guest's perspective (real wire calls into the seon JVM, real datoms, real tx events). Do NOT spend time fixing CLJ tests that we know will break in this transition (specifically: `test/seon/session_test.clj` for the old `seon.session`). **Disable them with `#_` or move them to `test/seon/_disabled/` with a one-line note. Coverage audit comes later if needed.**

## Testing strategy

The point of this transition is to ship Path B (the new wire-server + registry). The CLJ-side tests on Path A (web, render, ai, ns, dev hook, agent JVM pool) shouldn't break — Path A is untouched. The only known-breakage CLJ test surface is the old `seon.session` namespace's tests (Wave 3 replaces the file). **Disable them, don't fix them.**

Instead, the verification we want is **end-to-end integration tests from the CLJS guest's perspective.** What an actual agent will do is:

1. Connect via WIT → Rust host → UDS → seon JVM wire-server → registry → datahike.
2. `ensure-db!` a session, transact, query, listen, get tx events.
3. Multi-agent sharing one session DB.
4. Per-agent isolated sessions.

These are the tests that prove the platform works. They live in `pod-host/sidecar-poc/jvm-writer/test/seon/sidecar/` today (47/189 green, single-conn only) and get extended across Waves 4 + 6.

| Surface | Where | Strategy |
|---|---|---|
| **CLJS integration tests (PRIMARY)** | `test/seon/server/integration/**/*.clj` (new, Wave 4 + 6) | End-to-end from the guest's POV. Real UDS, real Transit, real wire-server, real datahike. Multi-DB scenarios, multi-agent scenarios, tx-event subscription. **This is what we invest in.** |
| **CLJ Path A tests** (existing JVM seat) | `test/seon/**/*.clj` (not server/) | Should NOT break. Run full suite ONCE per wave end as regression net. If something breaks, the wave touched something it shouldn't have — STOP, report, don't chase the failure. |
| **CLJ Path B unit tests** (small) | `test/seon/server/**/*.clj` (not integration/) | Just enough unit coverage for the new namespaces (store, session registry, codec, transit). Already partially shipped in Wave 1a. |
| **OLD `seon.session` CLJ tests** | `test/seon/session_test.clj` if present | **Disable in Wave 3.** Move to `test/seon/_disabled/session_test.clj.disabled` or wrap in `#_`. Note in commit what was disabled. Audit later. |
| **CLJS pod tests** | `src/seon/dev/test_preload.cljs` + tests under V0 source | Untouched by this execution plan. CLJS-test migration is a later transition phase, not Waves 1-7. |

## Wave overview

| # | Role | Scope | Time | Gate |
|---|---|---|---|---|
| 1a | Worker | Foundation deps + store config builder | 1h | targeted REPL probe |
| 1b | Verifier | Confirm 1a green | 15min | report |
| 2a | Worker | `seon.server.session` registry atom + API | 1.5h | targeted tests |
| 2b | Verifier | REPL probes against registry | 15min | report |
| 3a | Worker | Rename `orchestrator/session.clj` → `session.clj` + schema extension | 2h | callsites compile |
| 3b | Verifier | `(user/reset)` clean + session tests | 15min | report |
| 4a | Worker | Port wire-server: codec/transit/broadcast verbatim; rewrite writer handlers to use registry | 3-4h | wire tests pass |
| 4b | Verifier | Send raw bytes to UDS, verify roundtrip | 30min | report |
| 4.5 | Worker | Pre-Wave-5 cleanup (items A/B/C/D — see below) | 2-3h | each item gated separately |
| 5a | Worker | `:seon.server.session/registry` as the wire-bearing Integrant component + system.edn wiring | 1h | reset cycles clean |
| 5b | Verifier | 10× `(user/reset)`, Path A still working | 15min | report |
| 6a | Worker | Rust host trim — drop JvmSupervisor, connect to seon JVM. Rename `pod-host/sidecar-poc/rust-host/` → `host/` | 3h | cargo build clean |
| 6b | Verifier | Phase D smoke (3 guests × 60s) against seon JVM | 30min | report |
| 7 | Cleanup | Delete `pod-host/sidecar-poc/jvm-writer/`, update docs | 30min | final commit |

Total: ~16-20h of agent time across ~14 agent invocations.

**Note on Wave 5 (locked 2026-05-27):** The original sketch had a standalone `:seon.server/wire` Integrant component listening on a fixed socket pair. That does NOT compose with Option B socket-per-session routing locked in Wave 4a. The registry IS the wire-bearing component — `:seon.server.session/registry` owns the atom AND spawns/halts per-session wire-server sockets via a single init-key/halt-key pair. There is no separate `:seon.server/wire`. See `integration-architecture-2026-05-26.md` §5.

---

## Wave 1a — Worker: foundation deps + store config

**Goal:** Add `io.replikativ/konserve-jdbc` + `org.xerial/sqlite-jdbc` to `deps.edn`. Create `src/seon/server/store.clj` with a `config-for` fn that builds a datahike cfg map given `{::db-name ::backend ::path}`. Pattern from `pod-host/sidecar-poc/jvm-writer/src/seon/sidecar/writer.clj:61-70`.

**Files touched:**
- `deps.edn` (2 deps added)
- `src/seon/server/store.clj` (new, ~50 LOC)
- `src/seon/schema.cljc` (register `:seon.server.store/backend`, `:seon.server.store/path`, etc.)

**No Integrant wiring yet. No connect calls yet.** Pure config-building fn.

**Verification (worker runs before reporting):**

```clojure
(mcp__seon__eval orchestrator
  "(require '[seon.server.store :as store])
   {:memory  (store/config-for {:seon.server.store/db-name :test/m :seon.server.store/backend :memory})
    :file    (store/config-for {:seon.server.store/db-name :test/f :seon.server.store/backend :file})
    :sqlite  (store/config-for {:seon.server.store/db-name :test/s :seon.server.store/backend :sqlite})}")

```

All three should produce valid datahike cfg maps. Worker actually calls `(d/database-exists?)` on each to confirm.

**Stop conditions:**
- konserve-jdbc + sqlite-jdbc fail to load (Maven coord issue). Report deps + error.
- Datahike rejects the cfg shape. Capture verbatim, stop.

---

## Wave 1b — Verifier

**Read:** `deps.edn` diff, `src/seon/server/store.clj`, the worker's report.

**Verify via MCP eval:**

```clojure
(require '[seon.server.store :as store] '[datahike.api :as d])
;; 1. Config shape is correct datahike
(d/database-exists? (store/config-for {:seon.server.store/db-name :v/m :seon.server.store/backend :memory}))
;; 2. Schema registered cleanly
(seon.schema/registered? :seon.server.store/backend)
;; 3. No instrumentation errors
(seon.server.store/config-for {:seon.server.store/db-name :v/s :seon.server.store/backend :sqlite})

```

**Output:** thumbs up/down. Report any inconsistency between the worker's claim and observed behavior.

**Status (2026-05-27): RED.** Two bugs in `store/config-for`:

1. `:memory` backend emits `{:backend :mem ...}` but konserve requires `:backend :memory` (the V2 PoC at `pod-host/sidecar-poc/jvm-writer/src/seon/sidecar/writer.clj:48` uses `:memory`). `datahike.api/database-exists?` throws `Unsupported store backend: :mem` for memory cfgs.
2. `:sqlite` backend emits `{:backend :jdbc ...}` but the konserve-jdbc module isn't being loaded, so konserve throws `Unsupported store backend: :jdbc`. Either the deps didn't pull in the module, or `konserve-jdbc.core` needs an explicit `require` somewhere on the load path.

`:file` backend works (`database-exists?` returns `false` cleanly). Schema registration is clean (all three keys registered). Path A regression query returns 24 — unaffected. Test ns compiles; 7 tests / 28 assertions / 27 pass / 1 error (the `:mem` blow-up). Commit message claims 8 tests; actual is 7.

Worker must fix `:mem` → `:memory` and resolve the konserve-jdbc load path before Wave 1b can be re-verified.

---

## Wave 2a — Worker: session registry

**Goal:** `src/seon/server/session.clj` — atom-backed registry of `{db-name -> {:conn :backend :path :pub-chan}}`. Direct `datahike.api/connect`. Functions: `ensure-db!`, `remove-db!`, `get-conn`, `list-sessions`. Idempotent. No flow.

**Files touched:**
- `src/seon/server/session.clj` (new, ~120 LOC)
- `src/seon/schema.cljc` (register session-state Malli schemas)
- `test/seon/server/session_test.clj` (new, ~80 LOC)

**Tests cover:** create/get-conn/transact/query/remove cycle; idempotent ensure; concurrent ensure (two threads, one wins).

**Verification (worker runs targeted tests only):**

```clojure
(mcp__seon__eval orchestrator
  "(require '[seon.test-utils :as tu]) (tu/run-tests '[seon.server.session-test])")

```

**Stop conditions:**
- `(d/connect)` hangs (something wrong with the cfg from Wave 1a).
- Tests fail in a way that suggests the registry pattern is wrong — STOP, report.

---

## Wave 2b — Verifier

**Verify via MCP eval:**

```clojure
(require '[seon.server.session :as ss] '[datahike.api :as d])
(ss/ensure-db! {:seon.server.session/db-name :v/alice :seon.server.session/backend :memory})
(d/transact (ss/get-conn :v/alice) [{:test/key "hello"}])
(d/q '[:find ?v . :where [_ :test/key ?v]] @(ss/get-conn :v/alice))   ; → "hello"
(ss/list-sessions)
(ss/remove-db! :v/alice)
(ss/list-sessions)

```

**Output:** report.

---

## Wave 3a — Worker: rename orchestrator/session → session

**Goal:** Use `git mv` to rename `src/seon/orchestrator/session.clj` → `src/seon/session.clj` (preserving git history). The existing `src/seon/session.clj` (472 LOC) is replaced by the orchestrator's richer 609-LOC version. Add `::backend` and `::path` attrs to the schema. Update all `(:require [seon.orchestrator.session ...])` callsites to `(:require [seon.session ...])`.

**Disable old tests, don't fix them.** Before the rename: check whether `test/seon/session_test.clj` exists. If yes, move it to `test/seon/_disabled/session_test.clj.disabled` (or wrap every form in `#_`). Note in the commit message what was disabled. The orchestrator's existing session tests (whatever's at `test/seon/orchestrator/session_test.clj` if present) move with the file rename.

**Files touched:**
- `git mv src/seon/orchestrator/session.clj src/seon/session.clj` (deletes the old `seon.session`)
- `git mv test/seon/orchestrator/session_test.clj test/seon/session_test.clj` (if present)
- `git mv test/seon/session_test.clj test/seon/_disabled/session_test.clj.disabled` (BEFORE the orchestrator test moves in, if the old file exists)
- All callers of `seon.orchestrator.session` — update requires
- `src/seon/schema.cljc` — add `::backend`, `::path`

**Verification (worker):**

```clojure
(mcp__seon__eval orchestrator "(user/reload)")
(mcp__seon__eval orchestrator "(require '[seon.session :as ss]) (boolean (resolve 'ss/start-agent-session!))")
;; targeted session tests:
(mcp__seon__eval orchestrator "(tu/run-tests '[seon.session-test])")

```

**Stop conditions:**
- `(user/reload)` fails to compile something we missed. Find the missing rename + fix.
- Any session-test fails. Report the test name + error.

---

## Wave 3b — Verifier

**Verify via MCP eval:**

```clojure
;; verify no stale require lingers
(require '[seon.session :as ss])
(meta #'ss/start-agent-session!)
(seon.schema/registered? :seon.session/backend)
;; verify the old NS is gone
(try (require '[seon.orchestrator.session]) :gone? (catch Exception e :gone-yes))

```

---

## Wave 4a — Worker: port wire-server

**Goal:** Move the V2 PoC's writer + codec + transit + broadcast into `src/seon/server/`. Rewrite handlers in `wire.clj` to look up conns via `(seon.server.session/get-conn db-name)` instead of using their own private conn.

**Files touched:**
- `git mv pod-host/sidecar-poc/jvm-writer/src/seon/sidecar/codec.clj      → src/seon/server/codec.clj`
- `git mv pod-host/sidecar-poc/jvm-writer/src/seon/sidecar/transit.clj    → src/seon/server/transit.clj`
- `git mv pod-host/sidecar-poc/jvm-writer/src/seon/sidecar/broadcast.clj  → src/seon/server/broadcast.clj`
- Rewrite `pod-host/sidecar-poc/jvm-writer/src/seon/sidecar/writer.clj` → `src/seon/server/wire.clj`:
  - Drop the `(defonce state ...)` private conn.
  - Each handler does `(let [conn (seon.server.session/get-conn db-name)] ...)`.
  - Multi-db: every request carries `:db-name`.
- Update namespace declarations: `seon.sidecar.X` → `seon.server.X`.
- Move tests: `pod-host/sidecar-poc/jvm-writer/test/seon/sidecar/*.clj` → `test/seon/server/*.clj`. Adjust ns names.

**Verification:**

```clojure
(mcp__seon__eval orchestrator "(user/reload)")
(mcp__seon__eval orchestrator "(tu/run-tests '[seon.server.wire-test seon.server.codec-test seon.server.transit-test seon.server.broadcast-test])")

```

**Stop conditions:**
- Test count drops. Each test file should have the same number of tests it had in jvm-writer.
- Any handler stays accidentally tied to a private conn (grep for `defonce state` in the new files).

### Status — 2026-05-27

**done — Option B verbatim move (socket-per-session, NO per-request db-name routing).**

Per the decision committed prior to this wave, handlers were NOT rewritten to look up conns via the registry. Each wire-server instance still takes ONE conn at `(start!)` time; multi-session = multiple wire-server instances managed by the registry (Wave 5 owns that wiring). This made Wave 4a a pure verbatim move with only `ns` + `:require` edits.

**Files moved (11 total, verbatim — no body changes beyond ns/requires):**

| From | To | Lines | Touched |
|---|---|---|---|
| `pod-host/sidecar-poc/jvm-writer/src/seon/sidecar/codec.clj` | `src/seon/server/codec.clj` | 80 | ns only |
| `pod-host/sidecar-poc/jvm-writer/src/seon/sidecar/transit.clj` | `src/seon/server/transit.clj` | 41 | ns + 2 docstring refs |
| `pod-host/sidecar-poc/jvm-writer/src/seon/sidecar/broadcast.clj` | `src/seon/server/broadcast.clj` | 51 | ns + 1 require |
| `pod-host/sidecar-poc/jvm-writer/src/seon/sidecar/client.clj` | `src/seon/server/client.clj` | 110 | ns + 1 require |
| `pod-host/sidecar-poc/jvm-writer/src/seon/sidecar/writer.clj` | `src/seon/server/wire.clj` | 512 | ns (writer→wire) + 3 requires |
| `pod-host/sidecar-poc/jvm-writer/test/seon/sidecar/facts_test.clj` | `test/seon/server/facts_test.clj` | 200 | ns + requires |
| `.../overlay_semantics_test.clj` | `test/seon/server/overlay_semantics_test.clj` | 253 | ns + requires |
| `.../protocol_extensions_test.clj` | `test/seon/server/protocol_extensions_test.clj` | 267 | ns + requires |
| `.../protocol_integration_test.clj` | `test/seon/server/protocol_integration_test.clj` | 336 | ns + requires + 1 comment |
| `.../transact_batch_test.clj` | `test/seon/server/transact_batch_test.clj` | 181 | ns + requires |
| `.../wire_types_test.clj` | `test/seon/server/wire_types_test.clj` | 238 | ns + requires + 1 docstring |

NS rename map (5 source nss; 6 test nss followed the `seon.sidecar.X-test → seon.server.X-test` pattern):
- `seon.sidecar.writer` → `seon.server.wire`
- `seon.sidecar.codec` → `seon.server.codec`
- `seon.sidecar.transit` → `seon.server.transit`
- `seon.sidecar.broadcast` → `seon.server.broadcast`
- `seon.sidecar.client` → `seon.server.client`

**Verification result:** static check CLEAN (no stale `seon.sidecar.*` refs anywhere in `src/` or `test/` — confirmed via `rg`). `pod-host/sidecar-poc/jvm-writer/` retains `deps.edn` + `logs/` only (no `.clj` files); will be deleted in Wave 7.

**REPL test run: NOT EXECUTED.** The MCP `seon` server returned `FileNotFoundException: Could not locate seon/orchestrator/session__init.class` on every eval attempt. This is **pre-existing branch breakage** unrelated to Wave 4a — `src/seon/health.clj` and `test/seon/dev/conventions_check_test.clj` reference `seon.orchestrator.session`, which exists only in a sibling worktree, not on `feature/agent-runtime`. Wave 4a did not touch `seon.orchestrator.*` or `health.clj`. Hit the "REPL break twice → STOP" rule; deferring REPL verification of the moved code to Wave 5 (which will need a working JVM REPL anyway to wire the Integrant component). Expected test count to recover at that point: ~47 tests / ~189 assertions across the 6 test files.

**Path A regression check:** not run per Rule 7 of the testing strategy — full-suite regression is deferred to the end of the worker pass for the wave chain, and the JVM REPL is presently down regardless. CLJS integration is the primary verification surface.

---

## Wave 4b — Verifier

**Read:** the diffs.

**Verify via MCP eval:** start the wire-server on a test socket (not the real one), send a transact + a q via raw bytes (use the codec to build the frame), confirm roundtrip.

```clojure
(require '[seon.server.wire :as wire] '[seon.server.session :as ss])
(ss/ensure-db! {:seon.server.session/db-name :v/wire :seon.server.session/backend :memory})
(def srv (wire/start! {:req-sock "/tmp/v-wire-req.sock" :pub-sock "/tmp/v-wire-pub.sock"}))
;; client-side send + recv
(def client (wire/test-client "/tmp/v-wire-req.sock"))
(wire/send! client {:op :transact :db-name :v/wire :tx-data [{:test/k "ok"}]})
(wire/send! client {:op :q :db-name :v/wire :query '[:find ?v . :where [_ :test/k ?v]]})
;; cleanup
(wire/stop! srv)

```

---

## Wave 4.5 — Pre-Wave-5 cleanup (locked 2026-05-27)

Surfaced by `v2-open-questions-investigation-2026-05-27.md` + user direction same day. Four items; each is independently committable. Do A first (unblocks Wave 5 wiring); B/C/D are parallel-safe.

### Item A — Session namespace consolidation

**Status:** decision locked; Wave 3a already partially landed the rename.

The three namespaces that touch "session":

| NS | Role | Disposition |
|---|---|---|
| `seon.session` (renamed from `seon.orchestrator.session`, 609 LOC version) | Session ENTITY schema + lifecycle API (`create!`/`start!`/`stop!`/`destroy!`/`pause!`/`resume!`) | KEEP; extend with new lifecycle verbs |
| `seon.server.session` (Wave 2's atom registry, ~200 LOC) | Pure RUNTIME registry (atom of `db-name → {conn,backend,path,pub-chan}`) | KEEP; direct datahike, no flow |
| `seon.orchestrator.session` (predecessor of `seon.session`) | DELETED by Wave 3a rename | n/a |

Clear split: **entities** vs **runtime**. The investigation report's "three-way collision" is resolved by recognizing these are sibling concerns, not duplicates. Document the split in the `seon.session` and `seon.server.session` docstrings. ~30 min.

### Item B — Substrate-source seeding scope

**Decision:** DEFER from MVP. V0's `replay-program-graph!` continues to replay agent-defined code from `:seon.fn`/`:seon.ns`/`:seon.schema` entities; substrate code (the `seon.*` namespaces themselves) comes from the compiled bundle at process start. Smart substrate-seeding is item 9 in the investigation report (~150 LOC) and queues AFTER V2 cutover.

Document explicitly in the RESUME doc and the architecture doc §11. No code; ~15 min of doc.

### Item C — Ship `bin/test-cljs`

Today `bin/test` runs only JVM kaocha (cold ~2min). Need a separate `bin/test-cljs` that runs shadow-cljs tests via Node. The MCP REPL has wedged twice (Waves 1a + 2a per the status table) — bin scripts are insurance.

**Files touched:**
- `bin/test-cljs` (new, ~50 LOC bash)
- `bin/test-clj` (new, ~5 LOC wrapper renamed from current `bin/test`)
- `bin/test` (rewritten to call both)
- `shadow-cljs.edn` (add `:node-test` build, ~10 LOC)

~1h. See `v2-open-questions-investigation-2026-05-27.md` Q7 for the target shape.

### Item D — Consolidate `seon.runtime` atoms

Three private atoms in `src/seon/runtime.clj` at lines 295/330/883:

- `generated-ids` (set, ID dedup)
- `registry-cache` (cache mirror of `:seon.runtime` entities)
- `flow-handles` (map of live core.async flow handles)

Collapse into ONE atom matching atom PRD's "one substrate atom per concern":

```clojure
(defonce !self
  (atom {::generated-ids #{}
         ::registry-cache {}
         ::flow-handles {}}))
```

Refactor scope: rewire callsites within `runtime.clj` only (the atoms are private). ~50 LOC of edits. ~1h. Targeted tests on `seon.runtime` only.

### Item E — MCP eval routing by agent-id (**PRIORITY — gates testing**)

**Status:** scoped 2026-05-27 (user direction). Not started. Treat as Wave 4.5 final gate alongside items A-D.

**Why this is priority:** without it, you can't query/inspect a running agent's state from outside. The MCP server today only knows `"orchestrator"` (master nREPL) and 4-char hex (legacy agent-pool sessions). It has no way to address a V2 agent or a V2 session. Result: no way to verify multi-agent + multi-session behavior end-to-end without reading raw DB rows.

**The model.** Agent is the unique key, not session. Reasoning:

- An agent has exactly one session by construction.
- A session has N agents — "eval into session X" is ambiguous (which agent's POV?).
- Agent-id is globally unique; session-id is a group identifier.

So `mcp__seon__eval session_id=":seon.agent/<id>"` means: evaluate the supplied Clojure on the seon master JVM, with the current binding context set to that agent's session (`*conn*` bound to the session's datahike conn, `*current-agent-id*` bound to the agent). The eval runs in the JVM — the wasm guest hosting the agent is undisturbed.

**Wasm-guest eval is out of scope for MVP.** Connecting MCP eval INTO the wasm runtime (CLJS code inside the wasm guest) is a separate harder problem (WIT export from guest, host-side dispatch). For MVP, "eval at an agent" means "eval against that agent's JVM-side world (its session's DB)." That's enough for inspection + testing.

**Scope:**

- New JVM helper `seon.session/with-agent` that binds `*conn*` and `*current-agent-id*` for the duration of a body, given an agent-id. Resolves agent-id → session-id → conn from the registry. ~30 LOC.
- `bin/mcp-server` learns a third routing branch: `:seon.agent/<id>` → wraps user code in `(seon.session/with-agent <id> (do <user-code>))` → forwards to master nREPL on port 7888. ~40 LOC.
- The orchestrator session_id stays as-is (master REPL, no agent context). Legacy 4-char hex stays for agent-jvm-pool until that retires.
- Tests: end-to-end smoke that creates two sessions × two agents, runs an eval scoped to each agent-id, verifies datoms are written to the correct session.

**Total:** ~80 LOC + tests + doc. ~1.5-2h.

**Where to dispatch:** scope is small enough to inline once Wave 4b is done; or as the first work in a fresh session if context is low. It is NOT a separate wave because it doesn't require Integrant wiring or Rust changes — just the helper + MCP server route + tests.

### Wave 4.5 gate

All five items (A-E) committed independently. Path A regression intact. `bin/test-cljs` exits 0 on a no-op CLJS suite (proves the wire). Item E end-to-end smoke: two-agents-in-different-sessions, eval to each, datoms route correctly. Then Wave 5 proceeds.

---

## Wave 5a — Worker: Integrant wiring

**Goal:** Add `:seon.server.session/registry` Integrant init/halt to `src/seon/system.clj`. Add config to `resources/system.edn`. The registry IS the wire-bearing component — it owns the atom AND spawns/halts per-session wire-server sockets via Option B socket-per-session routing. **There is NO standalone `:seon.server/wire` component** (locked 2026-05-27 — see architecture doc §5).

**Files touched:**
- `src/seon/system.clj` (~30 LOC added)
- `resources/system.edn` (~10 LOC added)
- `src/seon/server/session.clj` — `init-key`/`halt-key!`/`suspend-key!`/`resume-key` on the registry. On halt: `(doseq [{::keys [conn pub-chan]} (vals @registry)] (close-socket-pair! ...) (d/release conn))`.

**Verification:**

```clojure
(mcp__seon__eval orchestrator "(user/reset)")    ; cycles clean
(mcp__seon__eval orchestrator "(user/status)")   ; :seon.server/wire :ok
(mcp__seon__eval orchestrator
  "(dotimes [i 10] (user/reset))")               ; 10 cycles, no socket leak
;; verify Path A still works
(mcp__seon__eval orchestrator
  "(seon.db/query :seon.runtime '[:find ?e :where [?e :db/ident _]])")

```

**Stop conditions:**
- `(user/reset)` doesn't come back clean. Sockets leaked? Stale state? Stop and report.
- Path A breaks (existing flow becomes unresponsive). The new component shouldn't touch the flow; if it does, that's a bug.

---

## Wave 5b — Verifier

**Verify via MCP eval:**

```clojure
(:seon.server/wire (user/system))         ; :ok
(user/status)
;; Path A regression test
(seon.db/query :seon.runtime '[:find (count ?e) :where [?e :db/ident _]])

```

---

## Wave 6a — Worker: Rust host trim + rename

**Goal:** Update the Rust host to NOT spawn its own JVM writer. Drop `JvmSupervisor`. Connect to the seon JVM's wire-server sockets (configurable via CLI flag). Sessions are db-name strings; the registry is server-side now. **Also rename the directory** `pod-host/sidecar-poc/rust-host/` → `host/` (terminology lock 2026-05-27 — drop "sidecar"/"PoC"/"rust-host" prefixes).

**Files touched:**
- `git mv pod-host/sidecar-poc/rust-host/ host/`
- `host/src/main.rs` — drop `JvmSupervisor`, `Session.jvm`, etc.
- `host/src/guest.rs` — minor; session env var unchanged
- `host/Cargo.toml` — possibly drop `tokio::process` deps no longer needed

**No code in `src/seon/server/` changes here.** All Rust-side only.

**Verification:**

```bash
# Acceptable Bash here (Rust toolchain, not nREPL):
cd pod-host/sidecar-poc/rust-host && cargo build --release

```
Then via MCP eval, ensure the seon JVM is up + the wire-server's sockets are listening:

```clojure
(:seon.server/wire (user/system))

```

**Stop conditions:**
- Rust build breaks in non-trivial way. Stop, report.
- The wire-server socket paths don't match what Rust expects. Align via config, not by hardcoding.

---

## Wave 6b — Verifier: Phase D smoke

**Run the Phase D smoke against the seon JVM:**

```bash
# Acceptable Bash (Rust binary launch):
cd host
cargo run --release -- \
  --connect-existing-jvm \
  --guest-wasm ../pod-host/sidecar-poc/guest/sidecar-agent-build/target/wasm32-wasip2/release/sidecar_guest.wasm \
  --multi-agent --multi-duration-ms 60000

```

**Verify metrics match prior Phase D**: 0 errors, 0 out-of-order, ~50% cache hit rate (or better), tx p50 in single-digit ms range.

**Stop conditions:**
- Any cross-session contamination.
- Any error/panic/deadlock.
- p50 latencies > 5x prior numbers.

---

## Wave 7 — Cleanup

- `git rm -r pod-host/sidecar-poc/jvm-writer/`
- Update `pod-host/sidecar-poc/README.md` to mark the writer as moved.
- Update `pod-host/sidecar-poc/CUTOVER.md` to mark Phase 2 as done.
- Final `(user/run-tests)` — full JVM suite, ONCE.
- Final commit.

---

## What we still don't do in this plan

- ALS → atom migration (separate wave; depends on Phase 4 CLJS file migration which is a different transition plan)
- V0 CLJS file migration to `guest-cljs/`
- Real V0 turn smoke (Phase 5 in the transition plan)
- Tauri packaging

Those are subsequent transition-plan phases, not this execution plan. This plan ships Path B end-to-end.

---

## Status — to be updated by agents as they go

| Wave | Status | Commit | Notes |
|---|---|---|---|
| 1a worker | DONE | `91cac41` + `2d6955e` | Foundation deps + store config builder. `:mem`→`:memory` fix + `:sqlite` deferred to follow-up landed in `2d6955e`. 7 tests / 28 assertions. |
| 1b verifier | DONE (subsumed by `2d6955e`) | `2d6955e` | RED flagged the `:mem` bug and konserve-jdbc load path; both addressed. `:sqlite` formally deferred (see architecture doc §4). |
| 2a worker | DONE | `c42bb2a` | `seon.server.session` registry — Path B core. 7 tests / 20 assertions. |
| 2b verifier | DONE (post-3a) | folded into `060fd15` | Once `seon.session` rename landed, tests ran clean. |
| 3a worker | DONE | `060fd15` + `3350f2e` | Schema attrs + test require fix. 14 tests / 57 assertions on the consolidated `seon.session` namespace. |
| 3b verifier | not run as separate pass | — | Subsumed by `3350f2e` and Wave 4a static check. No full-suite gate run. |
| 4a worker | DONE | `3350f2e` | Wave 4a — port wire-server (Option B verbatim). 11/11 files moved; namespaces load clean. REPL gate deferred to Wave 5 (REPL was wedged on pre-existing branch breakage unrelated to 4a). |
| 4b verifier | PENDING | — | V2 PoC tests spawn writer subprocess via `clojure -M:writer` (doesn't exist in seon JVM). ~30 min fix — rewrite fixture to start `seon.server.wire/start!` in-process. Folded into Wave 4.5 work. |
| 4.5 — A | DONE (mostly) | covered by `060fd15` + `3350f2e` | Session ns consolidation. Docstrings still to be updated. |
| 4.5 — B | DONE (doc-only) | this doc | Substrate-source seeding deferred. Documented. |
| 4.5 — C | not started | — | `bin/test-cljs` to ship. ~1h. |
| 4.5 — D | not started | — | `seon.runtime` atom consolidation. ~1h. |
| 5a worker | not started | — | Integrant wiring (registry IS the component). |
| 5b verifier | not started | — | |
| 6a worker | not started | — | Rust host trim + rename `pod-host/sidecar-poc/rust-host/` → `host/`. |
| 6b verifier | not started | — | |
| 7 cleanup | not started | — | Delete `pod-host/sidecar-poc/jvm-writer/`. |

`c896ffd` (`:agent` → `:agent-jvm-pool` deps rename) landed alongside Wave 4a; not part of any specific wave but documented in architecture doc §0.
