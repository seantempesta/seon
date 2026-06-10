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
- **1.5 Messaging codified — DONE 2026-06-09 (uncommitted; all 4 oracles
  demonstrated live).** Shipped: `:seon.message` = from(ref, REQ) + to(vec-of-
  refs, REQ) + content + at + id + hops; role/agent retired (ZERO refs in
  src/, grep-proven); `:seon.user/id "user"` seeded in `seed-substrate!`;
  `seon.agent/message!`/`reply!` (map-in/map-out, registered req/resp
  schemas, blank-content guard → error envelope — the run-3/6 empty-message
  class is dead); `:seon.turn/woken-by` recorded at wake-open, reply! derives
  its target from it; wake = `to ∋ me AND from ≠ me` on the
  `:seon.message/to` attr-index + per-agent scheduled-latch (Q2 #4
  double-schedule fix) + hop guard AT wake (`seon.warn/hop-cap` 4, loud
  console.error + clustered `check-hop-exhausted`); labels by ref kind
  (`user`/`assistant`/`agent-<id>`) in transcript + handlers.message +
  render.default; `/chat` calls message! with explicit user-ref from;
  deepseek prompt + stub-llm teach reply!; turns-since-user →
  turns-since-inbound; capabilities `(sum …)` example carries `:with ?e` +
  why-comment (run-6). **Two fixes found live:** (a) hops derive from the
  LATEST INBOUND message, not the loop's original woken-by (stub A↔B
  ping-ponged at hops 2↔3 forever otherwise), and hop-exhausted messages
  don't reset turns-since-inbound (two LIVE loops sustained each other — the
  wake guard only gates loop starts); (b) `warn/domain-attrs` threw `-lookup
  not supported on FilteredDB` (pre-existing 1.2-reuse bug, killed
  ctx-preview entirely) — read through `.-unfiltered-db`. Tests:
  message_test.cljs new (8 tests/29 assertions); warn-test 15/38
  (+hop-exhausted); agent-context 22/93 all green via run-block; targeted
  7-ns run 85 tests/272 assertions/0 fail; 0 build warnings. Live: chain
  stopped exactly at hops 4 (`WAKE REFUSED … dKe-2606091744 hops=4`).
  Original spec (USER-APPROVED DESIGN 2026-06-09 ~22:00Z):
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
  - **2.2c PROBE DONE (2026-06-09, uncommitted).** Harness:
    `probe/seon/probe/replica_jvm.clj` (`clj -M:replica-probe-jvm`, fork-sha
    alias, throwaway `tmp/replica-probe/store`) +
    `src/seon/dev/replica_probe.cljs` (`:replica-probe` build, stub
    non-streaming writer, counted `fs.openSync` blob reads). **One claim
    REFUTED as-shipped:** konserve 0.9.346 header meta-size encoding diverges
    — CLJ 4-byte BE int at bytes 4-7 vs CLJS ONE byte at offset 4
    (`storage_layout.cljc:29` vs `:40/:118`) → JVM blobs misparse on Node
    (meta read as value) and CLJS-written stores (`data/seon-pod/*`) are
    JVM-unreadable. NOT fressian, NOT architecture-killing: behind a
    probe-only header shim everything downstream CONFIRMED — fressian
    datom/PSS compat, sync lazy fetch (tiny lookup on a 5006-entity store =
    14/372 blobs, 1.8% of bytes; deref ~2.3 ms, q 7-13 ms), root-follow,
    RYOW flush-before-ack. Cutover prereq: fix the konserve fork
    (`/Users/sean/src/konserve` — CLJS writes BE32, parse sniffs the legacy
    1-byte pattern) then re-run the probe (phase 0 flips to CONFIRMED).
    Numbers: research file "Probe results 2026-06-09".
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
  - CLEANUP UNIT DONE (2026-06-09): `bin/test-cljs` now runs END-TO-END
    (267 tests / 968 assertions). Root causes + fixes: (1) `:test` build
    moved `release` → dev `compile` — Closure `:simple` flattens namespaces
    off `goog.global`, breaking BOTH `seon.eval/lookup-value` section
    resolution (the `<unresolved-section>` agent-context failures) and
    malli's CLJS instrument; (2) db_test ported core.async → Promise
    envelopes + a `seon.db`-scoped instrumentation fixture (the
    `[:seon.db/db]` explain-path assertions were CORRECT — the runner env
    just never installed instrumentation); (3) preconditions_test rewritten
    to `(async done …)` (its sync spin-loop on a Promise can never resolve
    in Node — failed since birth); (4) `seon.test.*-probes` intentional
    failures gated behind `armed?` (shadow registers every deftest ns in
    the build; unarmed direct runs pass vacuously, runner-driven runs arm
    them); (5) runner-test record tests bootstrap a conn (`*conn*` unbound
    in node-test killed the whole Node process via unhandled rejection).
    REMAINING REAL BUG (substrate, not tests):
    `seon.agents/substrate-ctx-als` is write-only — `.run` is called but
    nothing reads `.getStore`, so `*ctx*` does NOT survive `await`;
    `cross-await-binding-survives` and
    `multi-agent-interleaving-keeps-atoms-distinct` fail honestly until a
    getStore-backed read path lands in `seon.agents`.

### UNIT 2.2d — DIS same-box cutover (USER-APPROVED 2026-06-09 ~23:30Z)

User: same-box is fine for now; spec + implement. Architecture per
`research/datahike-native-replica-2026-06-09.md` (+ its "Probe results"
section — every claim CONFIRMED except the konserve header bug). The wire
work is NOT abandoned: `transact` op = the PWriter's transport;
`subscribe-tx` = change notification; reactive query-subs = UI/remote;
only per-op READS retire from the pod's path (kept server-side).

**Stage A — konserve header fix (GATES everything; cross-repo):**

- Repo: `/Users/sean/src/konserve` (the fork both classpaths resolve).
  `konserve/impl/storage_layout.cljc`: CLJS `create-header` writes meta-size
  as ONE byte at offset 4 (line ~40) vs CLJ 4-byte BE (line ~29). Fix CLJS
  to BE32 (write + parse); parse-side LEGACY SNIFF for old CLJS-written
  blobs: `byte4≠0 ∧ bytes5-7=0` ⇒ legacy 1-byte (no collision — that bit
  pattern as BE32 means meta ≥ 16MiB, never occurs). Same sniff on the CLJ
  parse side so the JVM can read existing `data/seon-pod/*` stores.
- Unit tests in the konserve repo for: new-format roundtrip both platforms,
  legacy-sniff reads, cross-platform JVM-write/CLJS-read + reverse.
- Discover how seon resolves the fork (deps.edn / package.json / gitlibs —
  sha-pinned git dep ⇒ commit + sha bump in seon; :local/root ⇒ simpler) and
  wire the fixed version into BOTH the pod build and the probe aliases.
- ORACLE: `clj -M:replica-probe-jvm` phase 0 flips CONFIRMED (the probe is
  the regression harness); existing pod stores still open (legacy sniff).

**Stage B — pod cutover (AFTER Stage A verified + 1.5 committed; pod lane):**

- `:seon-wire` PWriter (~40 lines CLJS, mirror `http/writer.clj`,
  `-streaming? false`): pod `transact!` forwards over the EXISTING UDS
  `transact` op to the wire-server. Reads stay local sync db-values via
  deref→root-re-read→lazy LRU fetch (zero changes to context assembly /
  agent-view / d/filter).
- Pod boot connects to the CLUSTER store (the wire-server's
  `data/clusters/default/store`) as reader; new runs stop minting
  `data/seon-pod/<run-id>` stores (legacy dirs stay readable via sniff).
- **JVM writer sha alignment**: wire-server moves off mvn 0.8.1671 onto the
  fork sha (fork IS upstream+3; kabel research confirmed upstream opens the
  existing store) — kills the version-skew flag.
- `listen!` adapter: `subscribe-tx` events → re-deref → fire registered
  handlers with the SAME handler contract (db/db-before as consecutive
  materialized values, attr-index from event tx-data) — triggers + inspector
  keep working unchanged.
- RYOW: transact ack carries basis-t; the PWriter resolves only after a
  deref shows ≥ that basis-t (flush-before-ack makes this immediate).
- ORACLE: full agent loop (boot → index-substrate! → stub-llm turn →
  transact/query) against the cluster store; a :user message transacted
  JVM-side wakes the pod trigger (cross-process reactivity); S1-style
  stub scenario lands data visible from the wire-server REPL.

### REPL-PARITY CONTRACT (user, 2026-06-10) — reflexive REPL actions must work

**Principle: the agent's context mimics a real Clojure REPL, so its
REFLEXIVE actions (trained on decades of REPL transcripts) work as
expected — or fail with a translation that teaches the substrate
equivalent.** A real REPL = eval + a bindings conveyor
(`clojure.main/repl` carries `*ns*`/`*1`/`*e` between forms); we replicate
the LOOP semantics and map the session vars to substrate equivalents.

Parity table (status 2026-06-10; gaps → unit #23 + successors):

- `(ns foo)` switches ns, prompt follows — WORKS (eval-str ending-ns
  threaded call-to-call; verified live).
- `(in-ns 'foo)` — NOT bootstrapped (cljs.js). Cover: teach `(ns …)` +
  legible error translation on in-ns.
- `*ns*` — evals to nil (CLJS namespaces aren't runtime objects; verified
  live via seon.eval). Cover: teach "the prompt line IS your ns truth;
  `(seon.agent/current-ns)` for it as data"; consider error-translating.
- `*1 *2 *3` — NOT maintained, NOT needed: `(result :<eval-id>)` is the
  substrate's richer replacement (every value durable + addressable).
  Cover: error-translate `*1` → "use (result :<id>) — ids in transcript".
- `*e` — errors are VALUES in the transcript (by design; A4 envelope).
- `(doc x)` / `(source x)` / `dir` / `apropos` — clojure.repl macros are
  NOT in the bootstrap. Cover (the substrate answer): shim them to read
  the PROGRAM-GRAPH — doc → `:seon.fn/doc`, source → `:seon.fn/source`,
  apropos/dir → fn-row queries. Code-as-data makes these BETTER than JVM
  versions (they see agent-authored fns too). Successor unit.
- defn/def persistence across turns — WORKS (taught, incl. bare-def-read
  gotcha → atoms).
- print output (`println`/`prn`) during eval — VERIFY where stdout lands;
  a REPL shows it next to the result. Base to cover in #23 verification.
- Partial failure (form N+1 runs after N fails) — WORKS.

**Prompt redesign (user direction): terminal feel — metadata ABOVE as a
status block, prompt line CLEAN** (no trailing `; turn N` comment):

```
;; ── turn 6 · 3 since-user (cap 20) · 2026-06-09T22:14Z ──
my.domain.thing=>
```

The status block carries turn, since-user/cap (+ pressure nudges when
escalating), timestamp (already moved to the tail for cache stability),
and any other per-turn metadata; the final line is EXACTLY a REPL prompt
(`<current-ns>=> `) — the agent completes "the next REPL input". One
preceding line states the contract: you are at a ClojureScript REPL;
reply ONLY with forms + `;;` comments.

### USER DECISIONS 2026-06-09 (late) — clusters, isolation, kabel, Friday demo

1. **Cluster model (settles 2.3):** the JVM datahike server hosts MANY
   databases. A **cluster** = one orchestrator agent + N task agents sharing
   ONE database (swarm: orchestrator launches agents to probe files/db and
   write findings or return answers; experiments build per-goal function
   libraries). From any CLJS agent's POV there is exactly ONE database. The
   wire layer's multi-DB registry (agent-id→db-name) is the mapping. NOT
   per-agent DBs; NOT one global DB — per-CLUSTER DBs, full visibility within.
2. **Isolation model:** TARGET = separate Node environment (process) per
   agent; ALL communication flows through the cluster's database. The current
   pod's multi-agent-in-one-process is TRANSITIONAL/demo-only — the
   state-isolation interleaving risks list applies to it, but the target
   architecture dissolves most of them (one agent per process; ALS
   near-trivial). 1.5's from/to message model is unchanged by this (messages
   live in the DB either way).
3. **Atom kills:** approved — do the risky ones / launch agents to migrate to
   ALS properly (queued after 1.5 lands).
4. **Replica direction — kabel PREFERRED for next week, NOT Friday:** user
   prefers upstream's proven KabelWriter/konserve-sync/TieredStore over a
   custom wire protocol ("fork" is a non-issue — it IS upstream main + 3
   commits; kabel stack already in-tree under src-kabel, :test-gated).
   **Security model**: with kabel, agents NEVER touch the disk store — the
   store lives entirely behind the JVM; agents speak websocket (localhost/UDS
   now; TLS + per-cluster token for remote — kabel ships NO auth, that layer
   is ours under any architecture). Per-cluster DBs soften kabel's
   full-replica-per-client memory cost (cluster DBs are small). GATES before
   adoption: (a) the konserve header bug (1-byte vs BE32 meta-size, found by
   the 2.2c probe) is on the critical path under EITHER architecture —
   konserve-sync copies JVM-written blobs the CLJS side parses — fix it
   first (/Users/sean/src/konserve, CLJS create-header/parse-header BE32 +
   legacy sniff); (b) kabel-on-Node smoke test (upstream tests are
   browser-only; needs `websocket` npm pkg). DIS findings + the probe harness
   remain the fallback + validation tooling.
5. **MVP DEMO (Friday 2026-06-12) — spec:** runs on the CURRENT pod (one
   process = one cluster; no central-store cutover needed). Agents get
   READ-ONLY `seon.fs` access to the seon repo. Users ask arbitrary
   codebase questions. SUCCESS = the answer **plus** a well-schema'd finding
   PERSISTED to the cluster DB such that the NEXT agent asked a related
   question provably REUSES it instead of redoing the search (S2's reuse
   mechanics, applied to code/docs findings). Priority for Wed/Thu (user):
   **nail the harness + dynamic context** — competent agents, schema reuse,
   adaptation — over infra plumbing. Demo-scenario iteration runs = the
   1.1-style orchestrator-driven loop, on codebase questions.
6. **Test cleanup:** agent launched (bin/test-cljs end-to-end + the three
   known-broken suites + stale-pattern sweep).

### Inspector right pane (2026-06-09 late — FIXED + humanized, browser-verified)

- **"no renderable entities" root cause:** boot-seed tx (`:seon.db/origin
  :substrate-seed` — the `:seon.schema` kind rows, sticky preamble, substrate
  index) run inside the booting agent's `with-agent` scope, so they arrive
  AGENT-STAMPED; re-boots fragmented the kind-row datoms across txs owned by
  DIFFERENT agents, so every agent's `agent-view` filter saw only a partial
  slice → `entity-primary-kind` never matched → zero render symbols → empty
  pane for ALL agents. Fixed in `agent_view.cljs`: the filter keeps
  `:substrate-seed`-origin tx, datoms on the agent's OWN entity (stub agents
  created by a peer couldn't see their own `:seon.agent/id` → ctx-preview
  threw), and `:seon.agent/id`/`:seon.user/id` datoms (identity = public;
  cross-agent transcript labels need them). Deeper fix (client.cljs, other
  lane): run the seed transacts OUTSIDE the agent ALS scope.
- **Agent tile was dead:** `render.default/view` emitted lazy-seq hiccup
  children; `valid-hiccup?` instrumentation rejected the output. Now `into`
  vectors.
- **Humanized:** message cards = direction line (`user → assistant`,
  resolved via ref re-pull — `d/pull '[*]` returns bare `{:db/id}` refs),
  `hh:mm:ss`, hops badge >0, content as markdown; unknown-kind fallback =
  styled k/v table card (no more pr-str/EDN blobs); markdown pipeline
  XSS-safe (pre-escape before `marked.parse`) and morph-proof (render keyed
  on JS-property source guard; observer fires on removal-only mutations —
  Datastar morphs used to blank every message body after the first SSE push).
- Verified in Chrome: all 6 agents render cards; live SSE morph appends new
  message cards without reload; hljs + marked fire post-morph; 0 console
  errors. `bin/test-cljs` 284 tests / 1058 assertions / 2 fails (verifier's
  corrected run 2026-06-09 — the earlier 276/1007/4 entry here was stale; the
  2 remaining fails are owned by the in-flight client.cljs lane).

### Inspector polish + hardening — unit #24 (2026-06-09 late, browser-verified)

- **Auto-collapse static content.** LEFT pane: the AI context renders as
  per-section `<details>` (`seon.inspect/ctx-preview` now returns
  `:seon.render/section-texts` — same layout + section fns as
  `assemble-context`, with a length-tolerance guard that falls back to one
  `:context` blob on divergence; byte-equality is impossible because
  `transcript-section` embeds the render timestamp). transcript + prompt
  open; system/capabilities/catalogs/ns-context collapsed with
  `name (N,NNN chars)` summaries. RIGHT pane: STATIC cards collapse to
  `kind name — gist` summaries. Discriminator: sticky preamble
  (`:seon.sticky/position`), `:seon.schema` kind rows (`:seon.schema/key`),
  and `:seon.fn`/`:seon.ns` whose CREATION tx origin is `:substrate-seed`.
- **Open-state survives SSE morphs** — empirically confirmed idiomorph DOES
  clobber `open` (guard disabled → user-opened section closed on morph).
  Guard: `window.seonOpen` map keyed by `data-seon-key`, recorded on summary
  click, reapplied in the MutationObserver pass (same class as `__mdSrc`).
- **Turn-grouped right pane**: `── turn N · hh:mm:ss ──` separators derived
  from `:seon.session/turns`→`:seon.turn/messages|evals`; non-turn cards keep
  tx-time position. Bottom-autoscroll pinned while at/near bottom (40px
  slack); scrolled-up reader never yanked (both verified live in Chrome).
- **Origin-forge guard (WARN-ONLY)** in `seon.db/transact!`: agent-scoped tx
  claiming `:seon.db/origin :substrate-seed` logs + counts
  (`!seed-origin-forge-count`); NOT overridden yet because the boot-seed
  still runs inside `with-agent` (live warns from the booting agent prove
  enforcement would break boots). Flip-to-enforce TODO sits next to the
  guard. 3 tests in `test/seon/db/origin_guard_test.cljs`.
- **Inspector on-tx fanout**: `:substrate-seed`-origin tx now push to ALL
  watching agents even when agent-stamped (verified live: another agent's
  seed tx pushed to a different watched agent's pane).
- **agent-view perf**: per-tx verdict memo in the `d/filter` pred —
  unmemoized, ONE inspector render on the A1 file-backed store issued
  millions of tx-meta reads and wedged the pod at 100% CPU.
- **hljs fix**: Clojure module added (core CDN build lacks it — 100+
  console warnings/page, zero highlighting; now highlights, console clean).
- Suite: `bin/test-cljs` 287/1066/2 — the 2 = the documented in-flight
  client-lane ALS fails (`seon.agents-test`).
- KNOWN GAP (other lane): `seon.render/renderable-entities` re-filters by
  tx-agent-id and does NOT admit `:substrate-seed` txs, so agents that did
  not run the seed see no sticky/schema cards (`render.cljs` `:when` clause,
  ~L302). Mirror agent-view's origin clause there.

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
