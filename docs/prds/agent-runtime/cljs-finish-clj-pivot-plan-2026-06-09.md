---
type: prd
status: active
tags: [prd, agent, flow, schema, database]
---

# CLJS-finish + CLJ-pivot plan (2026-06-09)

## Decision

The V0 CLJS pod proved the agent loop end-to-end (read a doc → register hard
nested schemas → store data — verified 2026-06-08). But single-process Node is a
poor host for heavy compute and a central store, and the **CLJ side already has
mature infra** we built: JVM datahike on-disk LMDB + the flow, code-graph indexing
(`graph/ingest`, `graph/query`), the richer Malli-walk compliance checker
(`seon.dev.compliance`), runtime instrumentation. So (user, 2026-06-09): **finish
the CLJS side to its best achievable state, then PIVOT the store + heavy analysis
to a centralized CLJ subsystem the pod talks to.** This is the provisional pivot
the architecture pre-authorized (memory `project_provisional_choices`) —
transition, don't keep bending CLJS to do what it wasn't designed for.

## Principles carried in from this design conversation (user)

- **Keep per-form emissions** (don't disable) — but make them CONCISE + VERY CLEAR.
- **Eval results stay per-eval, REPL-style** — even across many forms in one turn it
  reads like a real REPL: `(+ 1 1) ; 2` then the next form, one result per eval.
- **Warnings are where we EXPAND** — the rich teaching surface. CLUSTER by kind: ONE
  complete explanation + how-to-fix example per cluster, then "Affecting: a, b, c (N).
  Please correct before moving on." NEVER repeat the same explanation per-fn.
- **Warnings are SPECIFIC, never "one of these things."** Name exactly what is wrong
  and WHERE, with a concise concrete example. NOT "fn foo has an incomplete spec
  (could be :any/:maybe/missing)" — instead "fn foo's RETURN is `:any`" or "fn foo's
  arg `:bar` is `:any`". So each defect is its OWN check/kind (return-is-any,
  arg-is-any, uses-maybe, no-return-spec, no-malli-schema, …), and the cluster names
  the precise issue + a targeted fix example.
- **Warnings are COMPOSITIONAL** — each check is a separate, independently-testable
  function; `warnings-section` composes a registry of them.
- **Optional namespace scope** — a check takes an optional ns-scope arg; default to
  the CURRENT agent's ns so an agent isn't confused by warnings about other
  namespaces. Unscoped = whole-substrate overview.
- Warnings render at context-assembly (start of next turn), so they're naturally
  WHOLE-TURN and self-healing — a fn + its following-form test are both in the
  corpus by the time warnings render. No per-form "missing test" false alarm.

## Track A — finish the CLJS side (NOW)

### A1. Persist the pod DB — file/LMDB-backed, gitignored session dirs

- Flip the pod conn from `:backend :memory` (`client.cljs:184/210/374`) to a
  persistent datahike backend. File or LMDB (`reference-code/konserve-lmdb`) —
  whichever is easier; it's temporary (the CLJ central store supersedes it).
- Store under a gitignored base `data/seon-pod/<run-id>/` (one dir per pod-run /
  agent-conn = a "session directory"); persisted, NOT wiped, so past runs are
  reviewable. Add `data/seon-pod/` to `.gitignore` (do NOT fill the repo with DB
  files). Use a human-readable run-id (timestamp-based), not a bare random uuid.
- Ground in `reference-code/datahike` store config + `konserve-lmdb`.
- Verify: data survives `bin/seon restart pod` (a stored entity is still queryable),
  or at minimum the prior run's dir persists and is openable. Fixes "only one run".
- NOTE: the conn is per-pod (one shared conn), NOT per-`:seon.session`. True
  per-session logical partitioning → pinned to the CLJ central store (B1).

### A2. Compositional, clustered warnings

- Each check = a separate fn, e.g. `(check-<kind> {:seon.db/db db :seon.warn/ns <ns|nil>})
  → {:seon.warn/kind k :seon.warn/affected [{:sym s :where "return"} …]
     :seon.warn/explain <rich str> :seon.warn/example <concise fix>}` (nil/empty
  when clean). Independently unit-tested. The `:affected` entries carry the SPECIFIC
  location (which arg / the return) so the cluster can say exactly where.
- `warnings-section` composes a registry of checks and renders CLUSTERED per kind:
  the precise explanation + ONE targeted fix example, then the affected list (with
  locations) + "Please correct before moving on."
- ns-scope optional; default = current agent's ns.
- CLJS-doable checks (derive from the `:seon.fn`/`:seon.test`/`:seon.schema` corpus,
  which already carries `:seon.fn/spec` etc.). Each is SPECIFIC — names the exact
  defect + location, not a generic "incomplete":
  - `no-malli-schema` — public fn with no `:malli/schema` at all.
  - `return-is-any` — the fn's RETURN spec is `:any` (says so; "type the return, e.g.
    `::foo-response`"). (`:any` is allowed only at third-party boundaries.)
  - `arg-is-any` — a specific ARG is `:any` (names the arg).
  - `uses-maybe` — schema uses `[:maybe X]` (use `{:optional true}` / a concrete type).
  - `no-return-spec` / `no-input-spec` — the `:=>`/`:function` is missing its output
    or input.
  - `missing-test` — `:seon.fn` with no associated `:seon.test`.
  - `bad-ref` — a lookup-ref whose target attr isn't registered or lacks identity
    (names the attr + the real fix), where detectable in CLJS.
  - NO `missing-identity` check — identity is OPTIONAL. Entities do not need a natural
    key (bulk data, value-ish rows, and component children legitimately have none).
    Don't warn on it; don't force it.
- Keep the existing `failed-evals` warning; fold into the same clustered renderer.

### A3. Per-form emissions — concise + clear (keep)

- Keep per-form step emissions. Tighten the legible `:seon.eval/error` (T12) so each
  is short + unambiguous. Preserve REPL-style per-eval result display in the transcript.

### A4. Clear ref/schema feedback + envelope contract — DONE 2026-06-09

Shipped (Run-5 fix unit; scratch-conn verified live + 7 tests/33 assertions in
`test/seon/db/envelope_test.cljs`; JVM lane 57 tests/236 green with the shared
`schema.cljc` gate active):

- **Root cause closed:** `transact!`'s outer try was sync-only — a throw inside
  `^:async transact!*` (validate-attrs!/values!, ensure-datahike-attrs!) became a
  REJECTION that sailed past the catch (live: pod.log:3660). Fix: `(await
  (transact!* arg))` inside the try; EVERY failure path now RESOLVES to the
  `{:seon.db/ok? false :seon.db/error …}` envelope. Success shape unchanged
  (`{::ok? true ::tx-report …}`).
- **`:double`/`:float` bridge** (`:db.type/double`/`float`) — the Run-5 trigger
  type now installs + round-trips. `ensure-datahike-attrs!` no longer warn+skips
  unbridgeable attrs: it fails the transact with a `:user-input` error naming the
  attrs + the supported-type list (register! success ⇒ attr is transactable).
- **register! type gate** (`seon.schema/assert-compilable-schema!`, cljc): invalid
  Malli forms (`:number`) fail AT register! with the storable-type list.
- **Cryptic-error translation** in the envelope: "not defined in current schema" →
  "register it with (seon.schema/register! :x <type>) BEFORE transacting…";
  "Lookup ref attribute should be marked as :db/unique" → "add
  {:seon.db/identity true} to its register! call / transact the target first".
  Raw message preserved at `:seon.db/raw-error`; both retagged `:user-input`.
- **Eval semantics decision:** an eval whose form returns the ok?-false envelope
  records `:seon.eval/ok? true` — the FORM succeeded, returning an error VALUE
  ("errors are values"); visibility comes from `:seon.eval/result-edn` carrying
  the guiding envelope (run-5 log already shows this shape end-to-end).
- Callers converted off rejection-based control flow: `/clear` (serve.cljs)
  branches on the envelope; boot seed (client.cljs `seed!`) checks each envelope
  and throws so boot stays fail-loud.

Original spec: translate the cryptic datahike ref error; validation failures
return the envelope consistently (they used to REJECT past transact!'s sync
try); teach "errors are values."

## Track B — the CLJ pivot (mostly ALREADY BUILT — see scoping)

**Scoping 2026-06-09 (`clj-pivot-scoping-2026-06-09.md`) found Track B is far closer
than this section assumed — most of it EXISTS and WORKS:**

- **B1. Central store + wire — DONE + RUNNING.** The `wire-server` process (pid
  51224) is a file-backed on-disk JVM datahike conn answering
  ping/q/transact/pull/schema/entity-pull/batch over a Unix-domain socket
  (Transit-JSON), verified live (register→transact→query roundtrip vs
  `data/clusters/default/store`). The CLJS guest client mirroring `datahike.api`
  (`seon.client-runtime.db`) + Transit codec + multi-DB registry (agent-id→db-name)
  are all written. THIS is the pivot's central store; the main JVM `:seon.db/flow`
  is `:memory`/empty and is NOT it.
- **B2. Heavy analysis — DONE, JVM-resident.** `graph/analyzer`+`scanner`+`ingest`+
  `query` and `dev/compliance` (Malli-walk) work in-process; expose over the wire.
- **THE ONE MISSING PIECE — a plain-Node UDS transport.** The guest client routes
  through WASM-host (WIT) imports; there is no `node:net` UDS client, and the V0 pod
  is plain Node embedding datahike-cljs in-process. `wit.cljs` has a `js/require`
  fallback branch to hook.
- **B-FIRST-SLICE:** route ONE pod op (`transact!` then `q`) to the running
  wire-server over a Node UDS socket, single ambient conn (the back-compat path that
  already works). Oracle: same entity readable from the pod AND the wire-server REPL.
  This is the highest-leverage next step and the ONLY genuinely new code.
- **B3. Specific checks as a wire op.** The A2 checks (return-is-any/arg-is-any/
  uses-maybe/…) are a location-aware enhancement of `compliance.clj`'s existing
  parsed-schema walk (one fn, NOT a v2), exposed as a `handle-op` returning the
  clustered-warning shape the pod renders. So A2 (CLJS) is the stopgap; this is the
  real home.
- **B4. Session-browser UI** reading the central store (sessions → turns →
  prompt-text + messages + evals/errors). Blocked by: reactive engine is built but
  UNWIRED into the wire-server (no subscribe ops / `::reactive` hook), which the
  guest `listen!` loop already targets — wire it up.

Persistent-backend gotchas (from A1 + scoping) the central store must honor: mkdir
the base, branch on `database-exists?` (no create-or-connect), stable RFC-UUID `:id`.

## Sequence

Current ordered plan lives in **"RESUME HERE → Next steps"** below (it supersedes
this section as work lands). Track B's first slice (Node UDS transport) parallelizes
with Track A since most of B already exists. Methodology: implement (`seon-agent`) →
seon-verifier → commit; the live pod is the oracle.
`v2-context-render-prd-2026-06-08.md` is the demo source of truth; this doc owns the
finish-CLJS/pivot-CLJ plan.

---

## RESUME HERE — fresh-context handoff (2026-06-09)

**THIS doc is the source of truth.** Methodology: implement-agent → seon-verifier →
commit only when verified (`git add <specific files>`, never `-A`); the live pod is
the oracle; commit working code incrementally. Relevant memories:
`project_soul_and_two_track`, `feedback_prds_are_source_of_truth`,
`feedback_specific_actionable_feedback`, `feedback_agents_read_core_source`.

### Live system state

- **Pod** (`:client`, Node) PERSISTS to disk now (A1): konserve `:file`,
  `data/seon-pod/<run-id>/`. Restart via `bin/seon restart pod`.
- **Dev JVM** raised to `-Xmx4g` + heap-dump-on-OOM (was OOMing the in-process
  clj-kondo lint at 2g/99.9%). `bin/seon restart jvm`. Healthy (244/4096 used).
- **wire-server** (separate process, pid was 51224) = the CLJ central store
  (file-backed datahike over UDS/Transit at `data/clusters/default/store`) — Track 2's
  target. Verified working in scoping.
- **deepseek model** = `deepseek-v4-pro` (per user; if API rejects, a live call surfaces it).

### Gotchas (cost real time)

- MCP isolated JVM sessions are BROKEN (`mcp__seon__eval` clone fails) — the code
  references the retired `seon.orchestrator.session`. Use session `"orchestrator"`.
- The CLJS MCP `"default"` session wedges (`Compiler.currentNS()` null) and survives
  pod restart — use `mcp__seon_cljs__create_session` for a fresh sid.
- Dev-JVM Integrant logs `No such namespace: seon.flow` / `:seon.flow/pool` at boot
  (dangling retired-ns refs; system starts anyway) — see the surgical-fix doc below.

### Status (live)

- **ALL open agents DONE + committed; working tree clean.** (a) **second-boot fix** —
  `index-substrate!` idempotent across boots; a 2nd `start-agent-with-deepseek!`
  boots clean → **fresh-agent-same-conn works, two-scenario test UNBLOCKED**; live in
  the pod. (b) **flow/pool fix** — 4 dead keys removed from `system.edn`, **ACTIVE
  after the dev-JVM restart**. (c) `-Xmx` 2g→4g + heap-dump-on-OOM; runaway old JVM
  killed. (d) **Track 2** — thin Node UDS transport (`:wire-node` build, outside
  `:client`) + both sync layers wired into the wire-server (raw tx feed + reactive
  query-subscriptions), verified live; the `:client` db-path integration is the
  deferred handoff.
- **Instrumentation is now ON.** Post-restart the running system has 8 keys incl.
  `:seon.dev/instrumentation` + `:seon.db.schema/consistency-check` + `:seon.web/caddy`
  (Phase 2 had been aborting on the flow error, so these were OFF). Turned on with NO
  surfaced schema errors — existing fn schemas are consistent.
- **SOUL — REVERTED + RESOLVED 2026-06-09 (user decision).** The compile-time
  `soul.clj`/`soul-md` bake was REMOVED (deleted `src/seon/soul.clj` + the
  `:require-macros` in `deepseek.cljs` — both uncommitted scaffolding). Decision: KEEP
  the identity HARDCODED inline in `deepseek/default-system-prompt` as the SINGLE
  runtime source — it IS the live system message (`agent-adapter` passes no
  `:seon.ai/system-prompt`, so `body-json` falls back to it). `SOUL.md` stays as the
  human-readable doc; no macro, no seed, no pull. MVP-focused: no further SOUL work.

### WORK PLAN — two tracks, agent-runnable (2026-06-09, user-confirmed)

Each unit below is sized for ONE agent (≤7 files), has explicit done-criteria, and
names its lane. Methodology per unit: implement (`seon-agent`) → `seon-verifier` →
orchestrator reviews diff → commit (`git add <specific files>`). The live pod /
wire-server is the oracle for every unit — REPL-verify, don't just pass tests.
Parallelism rule: Track 1 units and Track 2 units run CONCURRENTLY (different
builds), EXCEPT anything touching the pod's `seon.db` — that seam serializes with
units 2.1/2.2 (they own `seon.db.cljs` while in flight).

#### Track 1 — CLJS pod + live DeepSeek (the MVP demo)

Goal: a fresh-context agent answers a real work question by DESIGNING schemas +
storing/computing; a second fresh agent REUSES the first's schemas + fns. Lane:
pod `:client` build (`src/seon/*.cljs`). Hot-reload via cljs-watch; never restart
the pod casually (live agents mid-session).

- **1.1 Drive the two-scenario test** — ORCHESTRATOR drives (live DeepSeek budget,
  bounded/observed; NOT delegated). Same persistent DB, back-to-back fresh agents:
  (1) work question → work-directed schema design + store (no index step);
  (2) similar question → fresh agent SEES + reuses stored schemas (schema-catalog)
  and fns (functions-catalog) instead of re-deriving.
  DONE: both scenarios observed; findings appended to
  `research/e2e-demo-findings-2026-06-08.md`. Output: the defect list that becomes 1.2.
- **1.2 Fix defects 1.1 surfaces** — A3 (concise per-form emissions, REPL-style
  per-eval results), A4 (cryptic ref error → guiding message; validation failures
  return the `{:seon.db/ok? false :seon.db/error …}` envelope, never reject past
  transact!'s sync try; teach "errors are values"). Scope per agent = the specific
  defects 1.1 logs, spec'd here before launch.
  DONE per defect: targeted fix + REPL-verified against the live pod.
- **1.3 A2 warnings + ctx-composer collapse — DONE 2026-06-09.** Shipped:
  `src/seon/warn.cljs` (11 checks: the 7 corpus checks + bad-ref/failed-evals/
  slow-evals/failing-tests folded into the same clustered renderer; registry =
  `seon.warn/checks`); `warnings-section` delegates to `seon.warn/render-warnings`,
  ns-scoped to the agent's current ns by default (`:seon.warn/ns :seon.warn/all` on
  the ctx entity = whole-substrate); strict-format block + war-story folded into
  `system-section`; derived turn-pressure (since-user vs live `turns-cap`) folded
  into `prompt-section`; `default/ctx` + its 11 dead helpers deleted (pretty-ai/
  pretty-html/view/read-helpers kept). 12 tests/28 assertions in
  `test/seon/warn_test.cljs`; live REPL-verified clustered render against the
  running pod; 0 build warnings. Original spec follows:
  compositional clustered checks per
  Track A §A2 (each check a separate unit-tested fn; `warnings-section` composes a
  registry; clustered render = ONE explanation + fix example + affected list;
  ns-scope optional, default current agent's ns; checks: `no-malli-schema`,
  `return-is-any`, `arg-is-any`, `uses-maybe`, `no-return-spec`/`no-input-spec`,
  `missing-test`, `bad-ref`; NO missing-identity). In the SAME unit: salvage from
  the dead `seon.render.default/ctx` composer (a) `how-you-respond`'s strict-format
  block + war-story → fold into live `system-section`, (b) `repl-state-header`'s
  turn-pressure escalation (5/10/17-turn nudges) → fold into live `prompt-section`
  (live path has NONE today); THEN delete `default/ctx` + its 8 dead helpers
  (`repl-state-header how-you-respond what-you-can-do conventions
  recent-conversation recent-evals-block recent-errors-block schema-reference` +
  orphaned `recent-evals`/`try-read-edn`/`all-entities`). KEEP `pretty-ai`,
  `pretty-html` (live fallbacks in `seon.render`), `all-running-agents`,
  `recent-messages`, `recent-errors`, `pulled-agent` (inspector/`view` deps), and
  `view`. DONE: checks unit-tested; warnings render clustered in a live agent's
  context; dead composer gone; 0 build warnings.
- **1.4 Agent-controlled live tile — DONE 2026-06-09.** Shipped: `:seon.agent`
  entity-kind schema (agent.cljs) carrying `:seon.render/html
  'seon.render.default/view` so the agent tile resolves through the same
  kind-lookup as every other kind; `seon.render/render-agent-tile` (map-in /
  map-out; per-entity `:seon.render/html` override → kind default → hardcoded
  `default-agent-tile-sym` floor for conns booted before the kind schema
  existed; never throws); inspector renders the tile ABOVE the per-entity
  cards inside the morphed html-pane fragment (live-updates via the existing
  per-tx SSE push); `view`'s retired `:seon.agent/turn-count` read fixed
  (derived `seon.render.default/agent-turn-count` = count of latest session's
  turns; also fixed in inspector header + /agents list); latent nil-kind
  TypeError in `entity-html-sym`/`entity-ai-sym` fixed; one "### Your live
  tile" teaching block appended to `capabilities-section`. 3 new tests in
  test/seon/render_test.cljs (ns: 17 tests / 35 assertions green via REPL
  run-block; bin/test-cljs still blocked by the pre-existing seon.db-test
  crash). Verified live: default tile renders on /agent/rLC-2606091459 (curl +
  REPL); override demonstrated on a fresh isolated disk conn. Original spec:
  wire `view` as the per-agent tile: add a `:seon.render/html` slot the agent
  can repoint (default `'seon.render.default/view`), so the agent dynamically
  rewrites its OWN single tile (user goal: not just last-eval cards).
  Inspector right pane shows it above the per-entity cards. DONE: an agent
  transacts its own tile renderer/content and the inspector reflects it live.
- **1.5 Messaging codified — from/to refs, message!/reply!, sender-agnostic
  wake (USER-APPROVED DESIGN 2026-06-09 ~22:00Z; launch after run 6).**
  The design conversation (user): presence of attributes IS the intent; the
  DB holds only FULLY-FORMED messages; defaulting is a `message!`-boundary
  liberty; ditch `role`; identity = the ref; UI stays purely reactive off
  the DB.
  - **Schema**: every `:seon.message` stores `:seon.message/from` (ref,
    REQUIRED), `:seon.message/to` (vector of refs, REQUIRED — fan-out),
    `:seon.message/content`, `:seon.message/at`, `:seon.message/id`.
    RETIRE `:seon.message/role` and `:seon.message/agent` (no-legacy):
    "my conversation" is DERIVED — `from = me OR to ∋ me`. Retire the
    `[:or :keyword :seon.db/ref]` shape on `from` — refs only.
  - **The user is a real entity**: seed ONE `:seon.user/id` entity at boot
    (identity upsert, idempotent — same pattern as agent entities). All
    refs uniform; later home for user prefs/memory. UI/HTTP stamps
    `from = [:seon.user/id …]`.
  - **`seon.agent/message!`** (bang-fn liberties, map-in/map-out, specced):
    `from` defaults to `(seon.db/current-agent-id)` from the ALS turn scope
    (the HTTP adapter passes the user explicitly); `to` defaults to THE
    user when unspecified. Stored message is always fully formed and passes
    the transact spec.
  - **`seon.agent/reply!`**: sugar — `to` = the `from` of the message that
    woke the current turn (substrate knows it). One-liner for the LLM in
    both user- and agent-conversations.
  - **Wake generalized + sender-agnostic**: trigger predicate becomes
    `to ∋ me AND from ≠ me` (replaces role=:user + agent=me). The LOOP is
    already sender-agnostic; only the wake predicate + transcript labels
    change. Agent→user messages wake no loop — the inspector's existing
    per-tx SSE listener re-renders (DB = single source of truth for UI).
  - **Transcript labels by ref kind**: resolve `from` — `:seon.user/id` →
    `user>`, own agent id → self/assistant line, other `:seon.agent/id` →
    `agent-<id>>`. Agents know other agents purely by id; own id is in the
    REPL-state header.
  - **Ping-pong guard**: `:seon.message/hops` (int) — 0 when from = user;
    agent-originated replies carry inbound-hops+1; wake REFUSES past N
    (default ~4) with a loud warnings-section surface, so two agents can't
    auto-bill an infinite chain. turns-since-user derivations generalize to
    turns-since-inbound.
  - **Adapters**: `/chat` handler parses the form and calls `message!` —
    the inline tx shape in web/serve.cljs dies. The system-prompt's
    "Speaking to your human" worked example becomes `reply!`/`message!`.
  - **Migration**: trigger (`user-msg-for-agent?`), `seon.agent/messages`,
    transcript labels, inspector/UI queries, prompt examples move to
    from/to. Old role/agent-keyed rows exist only in dead run-dirs — no
    data migration (fresh stores).
  - DONE-oracle: (1) UI → agent → `reply!` lands a fully-formed from/to
    message the inspector renders reactively; (2) agent A `message!`s agent
    B → B's loop wakes, B `reply!`s → A sees it in its next context, all
    labels correct; (3) hop guard stops a forced A↔B loop at the cap;
    (4) zero `:seon.message/role` / `:seon.message/agent` references left
    in src/.

#### Track 2 — CLJ central store + multiagent (robustness)

Goal: pod routes its DB path over the Node UDS transport to the central
wire-server; multiple agents share the robust central store; reactive pushes work.
Lane: JVM `seon.server.*` + `:wire-node` build + (for 2.1/2.2 only) the pod's
`seon.db` seam. Ground every unit in `reference-code/datahike` + the existing
wire/transport code (`seon.client-runtime.db`, wire-server handlers, `wit.cljs`
js/require branch). wire-server runs via `bin/seon` (store:
`data/clusters/default/store`).

- **2.1 First slice: ONE op over the wire** — route pod `transact!` then `q`
  through the Node UDS transport to the running wire-server; single ambient conn
  (the back-compat path that already works guest-side).
  DONE (oracle): an entity transacted FROM THE POD is readable from the
  wire-server REPL AND back from the pod. The ONLY genuinely new code is the
  thin plumbing from pod `seon.db` into the existing transport.
  **STATUS: DONE 2026-06-09 (oracle demonstrated both ways).** The UNCHANGED
  guest client (`seon.client-runtime.db` → `wit`) now runs on plain Node via a
  sync UDS bridge: `src/seon/dev/wire_sync.cljs` (synckit pattern — worker
  thread does async UDS I/O, main thread blocks on `Atomics.wait`; CBOR/Transit
  protocol stays in CLJS on the main thread; installs the WIT surface on
  `globalThis.__seon_client_runtime_db`) + `src/seon/dev/wire_sync_worker.js`
  (dumb byte-exchanger) + `:wire-sync-probe` build. Probe: connect →
  schema-guarded transact! marker → q back (exercises basis-t/as-of); marker
  cross-read from the wire-server JVM REPL (`#{[30 "pod-client-2.1-…"]}`); a
  second fresh client process sees the first run's entity. `:client` untouched;
  no process restarts. 2.2 NOTE: the pod's `seon.db` is `^:async`/promise-based,
  so the pod integration can route through the ASYNC transport
  (`seon.dev.wire-node/rpc`) under its existing awaits — the sync bridge exists
  for the unchanged sync guest-client surface (and matches WASM-host blocking
  semantics); pick deliberately in 2.2.
- **2.2 Route ALL ops** — `pull`, `entity`, schema register, batch; pod `seon.db`
  becomes a thin wire client (in-process datahike-cljs path retired on the pod —
  no dual-backend shims; per persistent-backend gotchas: mkdir base, branch on
  `database-exists?`, stable RFC-UUID `:id`).
  DONE: full pod agent loop (boot → index-substrate! → turn → transact/query)
  runs against the central store; the two-scenario test passes on it.
- **2.3 Multi-agent on the central store** — per-agent db-name via the existing
  multi-DB registry (agent-id→db-name); per-conn datahike serialization (NO
  core.async in guest paths). DONE: two pod agents run concurrently, writes
  interleave without corruption, each sees the shared substrate.
- **2.4 Reactive subscriptions** — wire the guest `listen!` loop to the
  wire-server's query-subscription layer (both sync layers already verified at
  the transport level). DONE: a tx on the central store pushes a live update to
  a subscribed pod agent/inspector pane.
- **2.5 (after 2.2) B3 checks as a wire op** — the 1.3 checks re-homed as a
  location-aware enhancement of `seon.dev.compliance`'s Malli-walk (one fn, not a
  v2), exposed as a `handle-op` returning the clustered-warning shape the pod
  renders. 1.3's CLJS impl is the stopgap until this lands.

#### Sequencing + steering

- NOW: 1.1 (orchestrator) ∥ 2.1 (agent). Then: 1.2 (from 1.1's defects) ∥ 2.2.
  Then 1.3, 1.4 ∥ 2.3, 2.4, 2.5.
- Orchestrator verifies each unit against its DONE-oracle before commit; PRD
  status updated as units land (this section is the dispatch board).

#### Board status (2026-06-09 ~19:30Z)

- **1.1 DONE** — two-scenario test complete (run 4): **S1 PASS** (rLC: saw the
  question in 23s, work-directed `:workout/*` schemas, error-as-value recovery,
  correct data, proper reply). **S2 FAIL on reuse** (ham: catalog WAS in context
  but unconsulted — parallel `duration-minutes` attr, fragmented swim entity,
  confidently-wrong "35 min" total). Findings:
  `research/e2e-demo-findings-2026-06-08.md` runs 3+4.
- **1.2 partials DONE:** trigger scoping (a0bdde9); hot-reload re-arm (e581908,
  verified 8/8); **transcript fix** — `messages` queries `:seon.message/agent`
  directly, THE run-3 blocker (in ac8cde3). **1.2-reuse DONE** (2026-06-09
  ~21:30Z, uncommitted): (a) prompt reuse-contract + verify-before-answer
  paragraph in `deepseek/default-system-prompt` (+673 chars, now 8,086 — verifier-corrected);
  (b) catalog salience — reuse-contract lead-in + new `domain data attrs`
  block (every agent-registered attr installed on the db, type + instance
  count, derived from `(:schema db)`); (c) `check-parallel-attr` in the warn
  registry (same ns + shared stem + unit-ish suffixes; flags the fork against
  the most-instantiated established attr) — verified firing on the REAL run-4
  store (`data/seon-pod/2026-06-09T16-03-27-405Z`: `:workout/duration-minutes`
  vs established `:workout/duration-seconds (2 entities)`); (d) worked
  `(sum ?v)` aggregate example in capabilities. warn-test 14/34 green via
  run-block. **DEEPER FINDING (corrects the run-4 analysis):** ham's context
  NEVER contained `:workout/*` — "contains workout" was the user's question
  text. Root cause: detect-and-tee `:seon.schema` entities lookup-ref
  `[:seon.ns/name <data-ns>]`, which doesn't exist for DATA namespaces like
  `:workout` → the whole record-eval! tx fails SILENTLY (console.warn only),
  losing the eval row AND the schema row (reproduced live). The catalog/warn
  fixes sidestep this by deriving from the installed datahike schema, but the
  eval.cljs tee bug needs its own unit. Then re-run S2 fresh.
- **tee/record-eval! fix DONE (2026-06-09 ~16:15Z, uncommitted)** — the run-4
  silent-data-loss unit. (a) `build-tee-entities` `:seon.schema/ns` is now the
  NESTED-MAP upsert `{:seon.ns/name <kw>}` (not a lookup-ref): creates a
  minimal `:seon.ns` entity for data namespaces (`:workout`), identity-upserts
  onto the existing one for substrate/`(ns …)` nses (no dup, source intact) —
  `handlers.ns`'s `[?s :seon.schema/ns ?n]` join stays coherent. (b)
  `record-eval!` NEVER silently loses the eval row: tx failure → console.error
  then RETRY without tee (eval row survives); bare-row failure → console.error
  DATA LOSS; conn captured at sync entry and passed explicitly to both
  transacts. New tests `test/seon/eval/record_eval_tee_test.cljs` (3 tests /
  8 assertions green via runner; in test-preload); memory-safety 12/36 green;
  scratch-conn REPL repro confirmed before/after; all three loud-log paths
  observed in `logs/pod.log`. **S2 re-run UNBLOCKED.**
- **1.3 DONE** (ac8cde3, verified) — warn.cljs 11-check registry, clustered
  render, ns-scoped; system-section strict-format; prompt-section turn-pressure;
  dead composer deleted (render/default 470→159).
- **1.4 IN FLIGHT** (agent) — wire `view` as the agent-controlled tile.
- **2.1 DONE** (6b0a254, verified 10/10) — sync UDS bridge; unchanged guest
  client on plain Node; oracle proven both ways. 2.2 next: pick ONE path for the
  pod seam (async `wire-node/rpc` under the pod's awaits vs the sync bridge);
  needs `bin/seon restart cljs-watch` (stale classpath lacks guest-cljs) —
  coordinate with Track-1 hot-reload before restarting.
- **2.2 STOPPED at the design seam (2026-06-09 ~19:50Z) — blocker cleared,
  cutover NOT landed; needs an orchestrator decision before code.**
  - **Blocker CLEARED:** cljs-watch restarted (pid 44902, fresh classpath now
    has `guest-cljs/src` — `seon.client-runtime.*` compiles); `:client` build
    green (365 files, 0 warnings); pod restarted (was required: the old loaded
    JS hit shadow's "Stale Output!" wall against the new watcher instance) and
    is healthy — `/agents` responds, fresh agent booted on a fresh
    `data/seon-pod/<run-id>` store, hot-reload re-verified live (reload #2 +
    trigger re-arm after a touch). The overlay landmine is DEAD CONFIG: shadow
    runs in deps mode, so `shadow-cljs.edn :source-paths` is ignored — proven
    by this very build (it listed the overlay first yet `:client` compiled the
    real `src/seon/db.cljs`). `shadow-cljs.edn` corrected to match reality
    (overlay removed from the inert vector; guest-agent build doc now says
    `clj -M:cljs:cljs-guest`).
  - **Transport decision (the 2.1 handoff question):** NEITHER per-op transport
    fits the pod seam as a drop-in. The 2.1 note's premise ("pod `seon.db` is
    promise-based") holds ONLY for `transact!`; `query`/`pull`/`entity` are
    SYNC over `@*conn*` and are called from sync contexts pod-wide, so the
    async `wire-node/rpc` cannot serve reads without an async-read rewrite of
    every consumer. The sync bridge CAN serve per-op reads, but per-op reads
    don't fix the real mismatch (next bullet). Latency is NOT a blocker:
    the 2.1 probe (connect + schema + transact + 2 q) completes in 0.27s
    INCLUDING node boot against the live wire-server.
  - **The real seam — the pod's READ MODEL is db-VALUES, not ops.** Consumers
    outside `seon.db` operate on immutable local datahike db values handed
    through listener inputs / `@*conn*`: `render.cljs` (`d/datoms :eavt/:aevt`,
    `d/q`, `d/pull`, `d/entity` on tx eids — context assembly itself),
    `agent_view.cljs` (`d/filter` with a CLJS CLOSURE predicate —
    unserializable over any wire), `web/inspector.cljs` (`d/entity` on tx
    eids), plus `handlers/{ns,fn}.cljs`, `handler.cljs`, `wake.cljs` (these
    last four are migratable to the `seon.db` surface; the first three are
    verifier-owned this cycle AND `d/filter`-closures have no wire
    equivalent). A "thin wire client" `seon.db` therefore breaks the agent
    loop at context assembly no matter which transport is picked.
  - **Recommended resolution (needs sign-off — it amends this unit's
    "datahike-cljs retired" framing):** the replica shape `seon.db`'s own
    docstring already promises ("writes route through this conn's writer …
    reads still resolve against the local replica and stay synchronous").
    Writes + control ops go over the ASYNC transport under `transact!`'s
    existing `^:async`; reads stay LOCAL + SYNC against a DERIVED db value
    materialized from wire tx events (`datahike.db/init-db` is cljc and takes
    raw datoms with explicit e/tx — datoms from the tx feed apply with eids +
    tx-ids preserved; `db`/`db-before` for listener inputs are consecutive
    materialized values); `listen!` rides the async tx-feed poll
    (subscribe-tx/next-tx-event, live since 46c6d3c). datahike-cljs then
    remains in the bundle as a QUERY ENGINE over wire-fed datoms — the
    STORE/writer is fully central. Needs one new wire op (initial full-datom
    snapshot at connect). Alternative (rejected): migrate every db-value
    consumer to wire-able ops — blocked on render*/inspector ownership and
    impossible for `d/filter` closures.
  - **Migration note:** existing `data/seon-pod/<run-id>` stores are NOT
    migrated (per unit scope) — fresh runs target the central store once the
    cutover lands.
  - **ORCHESTRATOR SIGN-OFF (2026-06-09 ~20:00Z): replica design APPROVED as
    re-scoped unit 2.2b** — it is the Datomic peer model (local immutable read
    values + central transactor), preserves the robustness goal (store/writer
    fully central, JVM datahike on disk), keeps `d/filter`/db-value consumers
    working, and matches `seon.db`'s own documented contract. Amends the
    "datahike-cljs retired on pod" framing: it stays as a LOCAL QUERY ENGINE
    over wire-fed datoms only. FLAGGED for user review (architecture note).
    2.2b scope = the agent's "What remains": snapshot wire op; remote-conn
    atom materializing the replica via `datahike.db/init-db`; transact!→async
    rpc; listen!→tx-feed; schema propagation; boot `ensure-db` against the
    central store; migrate the 4 direct-`datahike.api` callers
    (handler/wake/ns/fn) onto the `seon.db` surface; re-audit `entity` depth
    assumptions (kick-on-user-message navigates 2 levels).
  - **2.2b SUPERSEDED (user, 2026-06-09 ~22:30Z): NO snapshot shipping.**
    Research (`research/datahike-native-replica-2026-06-09.md`) found the
    fork NATIVELY supports the writer/reader split (Distributed Index Space:
    deref re-reads the branch root; index nodes lazily fetched + LRU-cached —
    memory ∝ working set; fressian-compatible JVM↔Node; Node lazy reads are
    SYNC; RYOW free because the JVM flushes the root before the transact
    ack). New plan = **2.2c**: pod reads the SAME store, thin `:seon-wire`
    PWriter (~40 lines) forwards transact over the existing UDS op,
    `subscribe-tx` = change notification. Probe (two-process falsification)
    in flight; results appended to the research file.
  - **DESIGN CONSTRAINT (user, 2026-06-09 ~22:45Z): agents are OPTIONALLY
    REMOTE — internet-reachable is the design; same-box is only the fast
    path.** DIS requires reaching the STORE, not the machine: the reader's
    only store dependency is konserve's fetch-blob-by-key. Remote path
    (unit 2.2d, after the 2.2c cutover): the wire-server grows a `get-blob`
    op (JVM = single store owner + single auth surface; NO external S3/Redis
    infra required), readers route konserve `restore` through the EXISTING
    2.1 synckit sync-bridge (Atomics.wait worker) so lazy reads stay sync
    over the network; UDS → TCP+TLS is a transport swap, zero replica-
    mechanics changes; LRU node cache keeps cache-miss RTTs ∝ working set.
    Isolation falls out: a remote agent's ONLY capability surface is the
    wire protocol (the hard-isolation goal, by process/machine boundary).
    NO unit may bake in same-filesystem assumptions outside the explicitly
    same-box fast path.
- SOUL reconciled (1baedc2): hardcoded prose restored, soul.clj deleted (the
  16b9f20 bake from another session was reverted per user decision).
- Pre-existing breakage logged by verifiers (NOT regressions): `bin/test-cljs`
  crashes in seon.db-test (core.async TypeError) before later suites run;
  boot.preconditions-test 4 fails; agent-context-test 13 fails in `:test` build.
  Worth a dedicated cleanup unit.

### Web UI status (2026-06-09 — FIXED + browser-verified)

- **page.cljc escaping bug FIXED:** root `/` console-forwarder `<script>` wasn't
  wrapped in `(seon.ui.html/raw …)` → `->string` escaped `&&` →
  `Uncaught SyntaxError: Unexpected token '&'`. Wrapped in `html/raw`. Chrome
  console confirms clean (`[client] console-forwarder armed`).
- **Root `/` redirect FIXED:** `/` was a dead stub ("loading…" — A-6 broadcast never
  built, no `broadcast.cljs`; placeholder `#agent-seon` ≠ live ids). `serve-root!`
  now 302-redirects to `/agents`. Browser-verified end-to-end: `/` → `/agents`
  picker → `/agent/<id>` two-pane inspector renders, console clean. `page.cljc`
  `root-html` is now dead on the pod path — retained as the future A-6 shell.
- **WORKING UI = the inspector:** `/agents` → `/agent/<id>` (left =
  `assemble-context` "what the LLM sees"; right = per-entity HTML cards via
  `handlers/*/render-html`) + `/agent/<id>/sse`.
- Sticky preamble (`:seon.system-prompt`/`:seon.conventions`/`:seon.sticky/*`) +
  the JVM web path (`src/seon/web/*.clj`) are PLANNED/destination — LEAVE ALONE.

### Standing principles (don't relearn)

- **SOUL = identity, HARDCODED inline in `deepseek/default-system-prompt`** (the single
  runtime source + live system message). `SOUL.md` is the human-readable doc only.
- **Work-directed**: model from the human's question, no "store whatever" index step.
- **Identity is OPTIONAL** on entities — never force/warn a natural key.
- **Feedback is SPECIFIC** (exact defect + location + concise example; cluster by kind).
- **Thin Node wrapper, NOT WASM** (swappable later).
- Two tracks by build: Track 1 = pod `:client`; Track 2 = JVM `seon.server.*` +
  guest/transport — keep concurrent edits on DIFFERENT builds.
