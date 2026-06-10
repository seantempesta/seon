---
type: prd
status: active
tags: [prd, agent, database, web]
---

# Agent-runtime MVP — CLJS pod now, CLJ central store next (2026-06-09)

This is Seon's primary PRD. It describes the MVP goal, the two lanes, where
each stands TODAY, and the forward queue — not a work log. History lives in
git and in `research/e2e-demo-findings-2026-06-08.md`.

## 1. MVP goal + demo (Friday 2026-06-12)

**Clusters of DeepSeek agents answer arbitrary user questions over the seon
repo** (read-only fs access), storing well-schema'd findings that the NEXT
agent reuses instead of redoing the work. The user drives agents directly
through the web UI: sends messages, watches them think, sees knowledge
accumulate.

Demo bar:

- A fresh agent answers a real codebase question (search → read → verified
  answer) and persists findings with provenance (path, line, claim,
  confidence).
- **Agent #2, asked a related question, consults the stored knowledge as its
  FIRST move** — query `:finding` rows before searching the repo.
- The whole exchange is watchable live on `/agent/<id>` (chat-first page,
  thinking indicator, knowledge cards with file:line citations).

Runs on the CURRENT pod (one Node process = one cluster); no central-store
cutover blocking the demo — but the LANE-MERGE IS NOW (user, 2026-06-10: "I want that ASAP, not post demo"): flip first, then build demo examples ON the unified durable store (per-run pod stores wipe on restart; the cluster store persists — examples built pre-flip get lost).

## 2. Single-agent capability — where we are

Honest current state, anchored by run 7
(`research/e2e-demo-findings-2026-06-08.md`):

**Proven:**

- **Search→read recipe**: fresh agent grepped the right term at 16s
  (`seon.search/grep`, smartly searching error text), read exact file:line
  ranges, stored 3 findings per the taught convention, delivered the correct
  user-facing answer at 95s.
- **Finding storage**: the taught provenance shape (claim, source path,
  line, confidence, verified/inferred) is followed exactly — no schema fork.
- **REPL parity** (`#23`): `(ns …)` switches ns and the prompt follows;
  println/prn output captured next to results; terminal-style prompt
  (`<ns>=> ` with a metadata status block above); `(result :<id>)` replaces
  `*1`; errors are values in the transcript.
- **Envelope honesty**: `transact!` NEVER lies — every failure path resolves
  to `{:seon.db/ok? false :seon.db/error …}` with cryptic datahike errors
  translated into guiding messages. No false success claims observed in
  runs 6–7.
- **Cross-agent schema reuse** (run 6): a fresh agent reused a prior agent's
  attrs exactly, computed aggregates in-query, replied correctly.

**Remaining gaps (the §7 #26 unit):**

- **Finding-consultation salience**: stored knowledge is discoverable (attrs
  in the catalog) but not CONSULTED — run 7's agent #2 efficiently
  re-derived the answer (70s, flawless pipeline) without ever querying the
  `:finding` rows. Discoverable ≠ salient.
- **Instruction clarity**: the canonical finding example taught bare
  `:finding/*` keywords — and the agent obeyed exactly. Instructions must
  MODEL fully-qualified namespaces and explain the rule, because agents copy
  the example, not the prose.
- **Store-proactively behavior**: storing findings happens when asked;
  the default should be to persist any verified non-trivial result.

## 3. Context quality — the standing method

**Post-merge focus (user): section-by-section iteration.** For each section
ask "is this really the best way to explain this?" → live agent run →
observe the reaction → revise. The context is the product; the live run is
the test.

Current shape: cache-stable static prefix to ~char 14k (timestamp moved to
the tail), turn-0 total ~18.5k chars, transcript budget 24k chars, terminal
prompt. Catalogs after full-substrate indexing (`#23`): 102 fns, 267
schemas, 154 tests. Per-section verdicts (baseline audit:
`research/context-audit-2026-06-09.md`, updated for landed fixes):

| Section | Verdict |
|---|---|
| `:system` | Good — format contract + self-walk API; timestamp moved out, cache-stable. |
| `:capabilities` | Best section — register→transact→query worked examples, `:with ?e` gotcha, fs + search recipe taught; iterate on finding-consultation wording (#26). |
| `:schema-catalog` | Good mechanism — domain-attrs block IS the reuse surface; add finding-content salience (one-liners, not just attr names). |
| `:functions-catalog` | Corpus now full-substrate (102 fns incl. `seon.fs`/`seon.search`); signatures fixed; sound. |
| `:namespace-context` | Fine at scale; cosmetic turn-0 "(not in db)" mislabel for the empty home-ns. |
| `:warnings` | Right mechanism (clustered, explain-once, ns-scoped); turn-0 still carries other agents' debris as imperatives — scope the phrasing to ownership. |
| `:transcript` | Correct interleaved shape; 24k-char budget caps the old 90KB runaway. |
| `:prompt` | Good — clean `<ns>=> ` line, turn-pressure escalation where the model reads last. |

## 4. System stability

**Suites (current, flake-free):**

- CLJS: `bin/test-cljs` **288 tests / 1073 assertions / 0 failures, exit 0**
  — verified identical across 3 consecutive runs
  (`research/test-stability-audit-2026-06-10.md`).
- JVM: `(user/run-tests)` **2546 pass / 0 fail / 0 errors** (2026-06-10,
  post hook-loss + `collect-flow-status` fixes — ledger #4 cleared).
  Honest caveat (verifier, same day): in a heavily-used REPL the full
  suite showed 2545/3/0 — all 3 in `ingest-spec-entity-pipeline-test`
  (generative; passes 171/0/0 isolated twice). Order/state-sensitive
  flake, pre-existing; suite-contamination unit queued (likely shares a
  root with the dev-hook generative `ensure-db!` side-effects).

**The live-run iteration loop (the method, keep it):** drive a bounded,
observed live agent run; every failure becomes a named defect; fix it with a
targeted unit; verify the fix in the NEXT live run. Seven runs each
converted a failure class into a verified fix (transcript routing → silent
tee data-loss → envelope rejection → reuse salience → `:with` gotcha →
finding pipeline). Full per-run history:
`research/e2e-demo-findings-2026-06-08.md`.

**Known-open bugs, with owners:**

- **Stub self-wake** (pod lane): `stub-llm` ALWAYS emits two forms, so the
  loop's zero-forms stop never fires for stubs — a single wake burns turns
  to `turns-cap`. Fix: stub emits zero forms on its 2nd+ turn since inbound.
- ~~JVM clj-reload hook-loss~~ — FIXED 2026-06-10: hook registration is
  key-based idempotent (`{::hook-key ::hook-fn}` entries, re-registration
  replaces by key); wire/boot register at every ns load with NO defonce
  guard, so any reload of registry cascades into wire/boot and re-registers
  (self-healing even from an emptied vector). Hook failures are caught but
  LOGGED (`log/error`), never swallowed. Second loss vector also closed:
  `registry-routing-test`'s fixture used to reset hooks without restoring —
  `snapshot-registry`/`restore-registry!` now carry `:hooks`. Live proof:
  hooks present + firing (subscription schema seeded, both listeners
  installed) on a fresh `ensure-db!` after `(user/reload)`.
- ~~Inspector dead-agent SSE 404~~ — FIXED 2026-06-10: `handle!` guards
  the page AND SSE routes with `agent-exists?` — stale tabs/bookmarks
  get a clean 404 "not in this cluster store" page (was: page 500'd
  out of `snapshot`, SSE registered a connection that threw `push!`
  on every tx). The chat bar also surfaces non-ok POST responses in
  `#seon-chat-err` (was: silent swallow — the post-flip "UI doesn't
  trigger anything" report was humans typing into stale pre-flip tabs
  and getting zero feedback on the 422).
- **MCP cljs `default` session NPE** (`Compiler.currentNS()` null) — reset =
  drop + recreate the singleton session; 26 stale sessions accumulated
  (session GC wanted). See §7 MCP-health unit.
- ~~`[:maybe ::flow-status]`~~ — FIXED 2026-06-10: `collect-flow-status`
  returns `::flow-status` and throws ex-info
  (`::error :flow-not-registered`) for unknown flows; the previously-failing
  conventions-check test passes (live-proven against a running infra flow:
  happy path returns a valid status map, unknown id throws).
- **Agent-authored schema replay BROKEN** (found live 2026-06-10: every
  `:seon.workout/*` replay fails with a swallowed analyzer "ERROR" at new-
  agent boot; same window `/agents/new` 500'd `:malli.core/invalid-schema`
  on the `:seon.db/transact-response` function schema) — datoms→code resume
  is the MVP retrieval arc; under investigation (debug unit in flight).
- **Live-pod hot-require corrupts the malli registry** (gym incident
  2026-06-10): MCP-requiring a not-yet-loaded ns re-executes
  `malli.registry` (`registry*` is a plain `def`; `seon.schema`'s composite
  install is defonce-guarded) → every new `m/schema`/`register!` throws
  `:malli.core/invalid-schema` until pod restart. Root fix: defonce in the
  fork and/or re-runnable registry init.
- **`:seon.turn/error` not queryable**: a turn's failure detail lives only
  in the ⚠ chat message; persist it onto the turn entity (small unit; makes
  gym S-08 mechanical).

## 5. CLJ datahike / Track 2 — state + THE LANE-MERGE GOAL

**Goal (user): merge the lanes into a single CLJ+CLJS working system.**

**What is PROVEN (off-pod; live pod untouched per the two-lane rule):**

- **DIS replica works end-to-end** — probe 10/10 claims + peer harness
  14/14: pod-style Node reader follows the JVM writer over the shared
  `:file` store; writes forward over the existing UDS `transact` op
  (`:seon-wire` PWriter, ~60 lines); lazy LRU node fetch keeps reader
  memory ∝ working set; RYOW free (commit-before-ack + root re-read);
  two-peer fanout via `subscribe-tx` → `listen!` adapter. Readers MUST set
  konserve `:config {:lock-blob? false}` (sync readers race the root
  `.LOCK` otherwise). Architecture + numbers:
  `research/datahike-native-replica-2026-06-09.md`.
- **konserve header fix shipped both repos** — the CLJS 1-byte vs CLJ BE32
  meta-size divergence is fixed in the konserve fork with a legacy sniff;
  cross-platform blob reads verified by the probe.
- **kabel = the remote-replica option, later** — the fork IS upstream+3
  commits, the entire kabel stack is already in-tree (`:test`-gated).
  Memory model: full DB replicated per client → right for a FEW fat remote
  replicas, wrong for many thin agents; ships NO auth. Verdict:
  `research/datahike-upstream-kabel-2026-06-09.md`.

**Cluster model (settled):** the JVM hosts MANY databases. A **cluster** =
one orchestrator agent + N task agents sharing ONE database; from any
agent's POV there is exactly ONE database; full visibility within the
cluster (per-CLUSTER DBs, not per-agent — preserves cross-agent section
fns). Target isolation = one Node process per agent; ALL communication
flows through the cluster's DB. The current multi-agent-in-one-pod is
transitional/demo-only (interleaving risks:
`research/multi-agent-state-isolation-2026-06-09.md`).

**Pod flip checklist (unit 2.2e — DONE 2026-06-10; the pod runs on the
cluster store):**

1. ✅ `:seon-wire` writer + listen-adapter live in the `:client` build as
   `seon.store.wire` (socket `tmp/seon-cluster-default-req.sock`, store
   `data/clusters/default/store`, `:lock-blob? false`, store `:id`
   replicated from `seon.server.store/name->uuid` — verified equal to
   the live wire-server's `(:id (:store config))`).
2. ✅ Wire-server runs the sha-aligned `:writer` alias (fork `01ba3f18`
   plus the konserve `:local/root` fork on its live classpath); restarted
   during the flip — the pod adapter re-subscribed automatically
   (pump fail-loud → 2s backoff → re-subscribe).
3. ✅ Foreign txs fire the conn's NATIVE `d/listen` listeners (the
   adapter synthesizes the raw tx-report and fires
   `(:listeners (meta conn))`), so every `seon.db/listen!` handler
   (triggers, inspector SSE) rides one bus for own + foreign writes.
   `data/seon-pod/<run-id>` minting deleted; `open-agent-conn!` is now
   the tests' isolated `:memory` helper; the pod boots via
   `open-cluster-conn!` (ping-gated, FAIL-LOUD, no local fallback).
4. ✅ Regression pair green post-flip (`clj -M:replica-probe-jvm` 10/10,
   `clj -M:replica-peer-jvm` 14/14). Flip oracles: boot + dedup
   (substrate seeds once, Nth boot seeds []), stub E2E rows land in the
   cluster store (verified JVM-side), a JVM-side foreign message WAKES
   the pod agent, restart durability (prior agents/messages render
   post-restart), `bin/test-cljs` 285/1065/0 (baseline minus the
   deleted pod-disk-conn tests; cluster store untouched by the suite),
   live DeepSeek run answered + persisted in the cluster store, and the
   A4 register→transact gate holds OVER THE WIRE (unbridgeable type →
   envelope before the wire).

**FORWARD — what "merged" means:**

- Pod reads the cluster store via DIS; **the JVM is the sole writer**;
  datahike-cljs remains in the bundle ONLY as a local query engine over the
  shared store (sync db-values, `d/filter` closures keep working).
- **Timbre-unified logging**: timbre is cljc — candidate to replace
  `seon.log` so both runtimes log through one system.
- **ONE codebase serving both runtimes**: `.cljc` convergence where shapes
  already match; no parallel implementations of the same mechanism.

## 6. UI/UX — now a first-class lane

**Current state (browser-verified):**

- Mission control at `/agents` — live ticking stats per agent.
- Chat-first agent pages: chat bubbles, `● thinking` indicator, knowledge
  cards with file:line citations.
- Collapse-by-default static content (sticky/schema/seed cards;
  per-section `<details>` for the AI context), turn grouping with
  `── turn N ──` separators, pinned autoscroll.
- Renders at ~0.5s (was 14.2s — kind-table cache + render-cap 100 +
  datahike `:trace` flood killed).

**FORWARD (user-specified):**

1. **Agents update their OWN tiles** — the 1.4 mechanism (`:seon.render/html`
   slot on the agent entity) is live and capabilities-taught; iterate on
   agents actually using it.
2. **A THIRD PANEL for tiles** (expandable) — tiles get their own surface
   beside context + entity cards.
3. **Tiles become the PRIMARY surface for complex non-text data** — charts,
   tables, live derivations the agent renders for its human.

**Queued fix:** the css build output is an artifact, NOT committed —
`bin/seon` autobuilds it on start.

## 7. Forward work queue

Each item is launchable as one agent unit (≤7 files), implement → verify →
commit:

1. ~~#26 finding-salience + instruction-clarity + concise message!/reply!~~
   — DONE 2026-06-10 (44a42df, verifier-falsified, live-proven): consult-
   findings-first recipe, multi-segment `:kb.finding/*` teaching, store-
   proactively, concise `{:seon.message/ok? id hops}` envelope (error
   envelope on failure). Run 8 still pending — encoded as gym scenario
   `consults-findings-run8` (deepseek tier); pass bar = agent #2's first
   eval queries stored findings (§1). ALSO SHIPPED same-day (44a42df +
   0902cbc): DeepSeek thinking OFF by default (`set-thinking!` knob,
   ~1.5s replies), fail-loud LLM errors (turn closes `:error` + visible
   "⚠ LLM call failed" chat message — the silent `done [0 ok]` death is
   gone), UI create-agent + chat autoscroll + hops hidden + chat
   envelope fix.
2. **ALS unification + `seon.agent`/`seon.agents` MERGE** (user-approved) —
   one ns, ONE ALS carrying a single per-agent context map (today
   `seon.db/agent-id-als` and `seon.agents/substrate-ctx-als` are parallel);
   `run-as-agent` wraps `db/with-agent`.
3. ~~css autobuild in `bin/seon`~~ — DONE 2026-06-10 (pod start runs
   `npm run css:build` then `exec`s node; `output.css` is untracked).
4. **stub-llm zero-forms termination fix** (§4 self-wake bug).
5. **Test-litter** — fixtures minting disk store dirs → `:memory` (3.1 GB /
   2,295 run dirs accumulated under `data/seon-pod/`; add a prune policy).
6. **Hook-loss source fix** (V2 server lane) — `^:clj-reload/keep` +
   key-based hook registration + remove the silent catch (§4).
7. **Third tile panel** (§6 forward item 2).
8. **Lane-merge 2.2e — FLIPPED 2026-06-10** (the §5 checklist, all ✅).
   Remaining in this lane: Timbre unification + cljc convergence;
   plus the flip follow-ups — re-arm user triggers for PRIOR agents at
   boot (old idle agents persist in the durable store but only the
   newly-minted boot agent gets a trigger; `rearm-user-triggers!` is
   the mechanism, currently hot-reload-only) and stop minting a NEW
   agent per pod restart (agents now ACCUMULATE in the durable store —
   one per boot).
9. **REPL-DEBUGGABILITY EVERYWHERE (user, 2026-06-10): every process, agent, and cluster DB reachable from a REPL.** The access matrix to make true: (a) any AGENT RUNTIME — the cljs MCP eval already takes `agent_id` (resolves agent-id → shadow runtime, survives restart); under process-per-agent every agent process loads the dev `:client` build and registers with the ONE shadow watcher — verify the `agent_id` path end-to-end (`seon.dev.node-agent`) as processes split; (b) the WIRE-SERVER — add nREPL alongside the 7891 socket REPL and wire it into the seon MCP server so the central writer is MCP-reachable like the dev JVM; (c) any CLUSTER DB — read-only DIS reader conns from any REPL (`:lock-blob? false`) + the wire-server REPL; document the one-liner recipes; (d) remote agents (later) — the wire protocol + DB are the admin surface (shadow websocket is dev-only). Document the matrix in CLAUDE.md Process Architecture when it lands.
10. **CLUSTER RUNTIME (the topology step after the store flip — spec before building):** today = one pod process hosting N interleaved agents on the single "default" cluster; target = ONE NODE PROCESS PER AGENT. Pieces: (a) per-agent process launcher — slim boot (reader conn + wire-writer + one agent loop + dev shadow registration), e.g. `bin/seon agent start <cluster> <agent-id>`; (b) cluster lifecycle — create-cluster mints the DB via the existing JVM multi-DB registry, spawns the orchestrator agent process; (c) **spawn-agent capability** — a wire op + supervisor seam so an ORCHESTRATOR AGENT can request new task-agent processes (the swarm primitive); (d) the web UI becomes its OWN reader process over the cluster DB (today the pod serves it); (e) the in-process interleaving-risk list mostly dissolves per-process. Until this lands, multi-agent = interleaved-in-one-pod on cluster "default".
11. ~~`bin/seon cluster reset [name]`~~ — DONE 2026-06-10: stop pod → stop wire-server → wipe `data/clusters/<name>/store` (store dir ONLY) → start wire-server (waits for `[writer] ready`, mints fresh DB) → start pod (re-seeds idempotently). First real run wiped the Track-2 debris: fresh store verified via the 7891 socket REPL = 1 agent / 106 fns / 0 messages / 3154 datoms, mission control matched exactly, full human chat loop browser-verified with a live DeepSeek reply. Non-"default" names wipe only (no processes registered for them yet). Later grows `cluster create <name>`.
12. **AGENT-GYM — harness LANDED 2026-06-10 (24ade2f), catalog LANDED
    (0e66717: 19 scenarios, 5 families, demo-ordered).** Driver runs
    EDN scenarios on isolated `:memory` conns; mechanical datalog
    predicates + (user, 2026-06-10) **LLM-JUDGE predicates for semantic
    correctness** (rubric + reference facts → graded verdict; separate
    scorecard axis from behavior — judge runner NOT yet built). 3 stub
    regression scenarios green; deepseek tier guarded behind
    `allow-paid?`. REMAINING in this lane: judge runner; catalog §7
    driver-feature gaps (fixture fn seeding, multi-agent sequencing,
    per-scenario llm-fn injection, mid-scenario pod restart for the
    resume scenario, relative-date fixtures); encode catalog top-4
    (S-01 smoke, S-12 run-8, S-32 consult-isolated, S-21 log-workout);
    then run deepseek tiers (user approved spend 2026-06-10).
13. **MCP server health** — reset the broken `default` cljs session; GC the
   26 stale sessions.

## 8. Standing principles (don't relearn)

- **SOUL = identity, hardcoded inline in `deepseek/default-system-prompt`**
  (the single runtime source + live system message). `SOUL.md` is the
  human-readable doc only.
- **Work-directed**: model from the human's question; no "store whatever"
  index step.
- **Identity is OPTIONAL on entities** — never force/warn a natural key.
- **Feedback is SPECIFIC** — exact defect + location + concise fix example;
  cluster by kind, explain once, list the affected.
- **Errors are values** — every failure resolves to the
  `{:seon.db/ok? false …}` envelope; nothing rejects past a sync try;
  fail-loud, never swallow.
- **Reactive context, derived by default** — sections are functions of the
  DB at render time; fixed problem ⇒ empty query ⇒ surface vanishes.
- **Thin Node wrapper, NOT WASM** (swappable later); wasm guests, if ever,
  stay wire-only/core.async-free.
- **Two-lane discipline**: Track 1 = pod `:client` build; Track 2 = JVM
  `seon.server.*` + transport builds. Concurrent edits stay on DIFFERENT
  builds; the iteration lane is FROZEN during live runs; infra work stays
  off-pod until its flip is scheduled.
- **Commit protocol**: implement → verify against the live oracle (pod /
  wire-server REPL, not just tests) → commit with explicit-path staging
  (`git add <files>`, never `-A`).
- **Data rules**: fully-namespaced keywords everywhere; no `:any` in
  seon-authored data (third-party boundaries excepted); no `[:maybe]` —
  `{:optional true}`; shared shapes registered once and referenced.

## 9. Carried-forward register (restored after the rewrite's loss-audit)

Items the 2026-06-10 rewrite dropped that remain LIVE obligations or
operational knowledge. Each is one line + its source of detail.

### Open work (belongs in the §7 queue)

- **Atom kill-list (USER-APPROVED)** — eliminate/migrate the risky mutable
  state per the census top-5 (`!next-budget-ms` → fiber-local; agents
  `::state` → derive from DB; stash-prefix drift; `seon.repl/!conn` fold;
  unify the two agent-writable timeout knobs) + globalThis stash eviction.
  Detail: `research/multi-agent-state-isolation-2026-06-09.md` §Q3.
- **A3 / T12 — per-form emission polish (only UNFINISHED Track-A item)**:
  keep per-form emissions but make each CONCISE + VERY CLEAR; tighten the
  legible `:seon.eval/error` so every error is short + unambiguous; eval
  results stay per-eval REPL-style in the transcript.
- **B3 — checks as a wire op**: the warn-registry checks re-homed as a
  location-aware enhancement of `seon.dev.compliance`'s Malli walk (one fn,
  not a v2), exposed as a wire `handle-op` returning the clustered-warning
  shape the pod renders. The CLJS `seon.warn` registry is the stopgap.
- **B4 — session-browser UI** over the central store (sessions → turns →
  prompts/messages/evals) + wiring the reactive query-subscription layer
  into UI pushes (the guest `listen!` loop already targets it).
- **doc/source/apropos shims (REPL-parity successors)**: `(doc x)` →
  `:seon.fn/doc`, `(source x)` → `:seon.fn/source`, apropos/dir → fn-row
  queries — program-graph-backed, so they see agent-authored fns too.
  Boundary note: `(do *ns*)`-style wrapped forms are NOT intercepted.

### Design contracts that govern future work (not just shipped code)

- **Warnings architecture (A2)**: every NEW check is a separate,
  independently-tested fn returning
  `{:seon.warn/kind k :seon.warn/affected [{:sym s :where …}] :explain :example}`;
  `warnings-section` composes the registry; render CLUSTERED per kind (ONE
  explanation + ONE fix example + "Affecting: … (N)"); SPECIFIC defects only
  (never "one of these things"); optional ns-scope, default = agent's
  current ns; warnings render at context-assembly (whole-turn,
  self-healing). NO missing-identity check — identity is optional.
- **Message model (1.5)**: every `:seon.message` stores `from` (ref) +
  `to` (vector of refs, fan-out) + `content` + `at` + `hops`; role/agent
  attrs are RETIRED; "my conversation" is derived (`from=me OR to∋me`);
  wake = `to∋me ∧ from≠me`, hop-cap 4 enforced AT wake (refusal surfaces
  as a clustered warning); `reply!` targets the waking message's `from`
  via `:seon.turn/woken-by`; hops derive from the LATEST inbound; blank
  content refused at `message!` (the only entry point).
- **Sticky preamble** (`:seon.system-prompt`/`:seon.conventions`/
  `:seon.sticky/*`): planned DB-resident runtime-editable preamble —
  seeded, rendered by the inspector path only, NOT yet in the agent
  composer. Leave alone until deliberately wired.

### Operational gotchas (cost real time — do not re-learn)

- JVM MCP: isolated session clone is BROKEN (references the retired
  `seon.orchestrator.session`) — always use session `"orchestrator"`.
- CLJS MCP: the `default` singleton session wedges (`Compiler.currentNS()`
  NPE) and survives pod restarts — always `create_session` a fresh sid;
  sessions die when the pod event loop blocks or the watcher restarts.
- `(require … :reload)` in the CLJS REPL only recompiles as a TOP-LEVEL
  form (silently stale inside `(do …)`).
- Shadow runs in deps mode: `shadow-cljs.edn :source-paths` is INERT;
  the classpath comes from deps.edn aliases. Probe builds compile in a
  fresh JVM (`clj -M:cljs compile <build>`), never in cljs-watch; resource
  -only classpath changes need a `.shadow-cljs/builds/<build>` purge.
- Stub agents self-wake to the turn cap (the stub always emits forms, so
  zero-forms termination never fires) — drive ONE turn then read, or
  expect cap-runs.
- konserve readers must set `:lock-blob? false` (readers take the lock by
  default; two sync readers race and throw) — required in any pod-flip
  connect config.

## 10. HANDOFF (2026-06-10 EVENING, account switch — user resumes on new login)

State: **RUN-8 PASS BAR GREEN** (gym S-12: agent B's first eval is a verbatim
`:kb.finding` datalog query, 2 consecutive paid sweeps — commit 884a75d) plus
~16 verified commits this session: #26 + thinking-off + fail-loud LLM errors
(44a42df), UI basics (0902cbc), gym harness+catalog+judge tier (24ade2f,
0e66717, 884a75d), context E1+E2 full-source exemplars (bb51af8), context
iteration 1 (12faa70 — S-32 GREEN judge-100, S-12 mechanically GREEN), JVM
hook-loss + flow-status (69cfe77), dev-hook gen-check side-effect fix
(9723929), replay/registry-stomp fix (7ce6381), datahike fork engine fix
pushed + sha bump to 1ae35696 (156a53e — multi-group join corruption; JVM
affected too when planner on). Cluster store wiped clean 16:24 + legacy data
dirs deleted (data/ = 4.5MB: clusters/default/store + dev-hook only). Stack
green on the new sha. CLJS suite ~323/1241/0; JVM 2551/0/0.

**IN FLIGHT at handoff (review → verify → explicit-path commit when they
report):**

1. **V3-A** — `seon.db` → public face + `seon.db.internal` split (target
   ≤15k chars public). Oracles: suite, replica probes 10/10+14/14, live
   pod roundtrip.
2. **S-21 root causes** — warn.cljs `internal-attr-ns?` regex hides ALL
   `seon.*` domain attrs (production bug); `register!` single-segment gate;
   gym driver world-parity; de-vacuous fork predicate; paid S-21 re-run.

**UNCOMMITTED in tree (intentional, pending):** `src/seon/todo.cljs` +
`test/seon/todo_test.cljs` (built, suite-green, REVIEWED-no-objection;
wired into boot via client.cljs require + bootstrap attrs — commit with the
exemplar-root swap fs→todo), plus whatever the two in-flight agents leave.

**Tonight's USER DECISIONS (all firm):**

- **Demo closer = fresh-session store/retrieve competence**, NOT tiles.
- **Context v3 = code-first** (spec: `context-v3-code-first-2026-06-10.md`,
  1d8becd): full source for relevant nses; `*.internal` sub-namespace
  convention IS the filter (no lists/regex/stamping — classification
  derived at render time from ONE full-index query); datahike query API
  included (render from var docstrings); **NO recipes ns — real namespaces
  doing real work**; remaining teaching → docstrings of the public faces;
  prose survivors = SOUL + a few behavioral lines.
- **KB = `my.kb`, SCHEMA'D DATA NOT TEXT (user, 2026-06-10 late, twice
  refined):** no generic store!/consult, no text-claim rows (memory-file
  problem), NO RAG/embeddings. The base is **`my.kb`** —
  substrate-scaffolded with (a) GENERAL GUIDELINES all users get
  (ns-doc/docstrings: "create `my.kb.<domain>` sub-namespaces with REAL
  schemas for each kind of knowledge; do NOT build a general
  memory-markdown structure; storing large text is allowed when the user
  wants it but is never the default") and (b) the SHARED PROVENANCE shapes
  registered once (`:my.kb/source-path` `:my.kb/source-line`
  `:my.kb/verified-at` `:my.kb/confidence` — multi-segment by
  construction) for domain schemas to reference. Agents DESIGN a schema
  per knowledge kind (`my.kb.codebase.fn/*`, `my.kb.paper/*`, …) — same
  skill as user-data modeling; everyone's kb is different by construction.
  Consult = catalog + datalog (salience surface already built). Generic
  `:kb.finding/*` teaching replaced (store freshly wiped — no legacy rows);
  gym S-12/S-32 predicates update to "consults relevant my.kb.*/my.* attrs
  first". `seon.kb` as a namespace is DEAD — superseded by the my.kb
  scaffold.

**ORPHANED WORK IN TREE (session-limit killed 3 agents 2026-06-10 ~13:36;
resume actions):** (1) V3-A ~90% done — db.cljs 16.8k + db/internal.cljs
47.3k exist but caller re-points unfinished: SUITE IS COMPILE-FAIL at
test/seon/db_test.cljs:109 (`@#'db/system-attr?` moved). Fresh agent
finishes re-points → suite green → replica probes 10/10+14/14 + live
roundtrip → commit. (2) S-21 edits present (warn.cljs +59, schema.cljc
+44, new test/seon/schema_test.cljs, warn_test +62, gym driver+scenario)
but UNVERIFIED, no paid re-run — verifier falsifies vs the original S-21
unit spec, runs paid S-21, then commit. (3) spec-writing unit died before
writing — relaunch (incl. correcting V3-B to the my.kb design above).
Live pod unaffected (runs last-good hot-reload). seon.todo + tests +
client.cljs todo wiring remain intentionally uncommitted.
- **Agent code base ns = `my.*`** (user-confirmed): agents own it freely,
  everything under it auto-renders to ALL cluster agents (derived from tx
  provenance — agent-scoped txs vs `:substrate-seed`, no maintained list);
  `seon.*` stays substrate-only **BY CONVENTION, not enforcement** (user
  2026-06-10: "by convention they'll listen" — no write-blocking machinery;
  the register! single-segment gate is the only hard gate); `*.internal`
  hidden uniformly. Transition: temporary exclusion set for not-yet-split
  substrate plumbing nses — shrinks to empty, then deleted.
  DECIDED: agent HOME namespaces mint as **`my.agent.<id>`** (today
  `seon.agent.<id>` — substrate squatting). Rename unit = mint site
  (client.cljs), home-ns (agent.cljs), replay, inspector, tests asserting
  the old prefix; queued first in next session (files owned by in-flight
  lanes at handoff).
- DeepSeek spend unlimited; cluster resets free; errors ALWAYS surface at
  the user-facing layer.

**Queue (next session, in order):** review/commit the 2 in-flight lanes +
todo wiring & exemplar-root swap → V3-B `seon.kb` + docstring moves → V3-C
one-query/one-classifier unification (+ my.* exposure + agreement property
test) → V3-D datahike API block → V3-E delete superseded prose (gym trio =
oracle per unit) → `bin/seon start/restart all` (dependency-ordered:
cljs-watch → wire-server → pod; ready-gate on the SOCKET; auto-prep on sha
change; pod gets bounded ping retry ~10s) → LIVE RESUME TEST (user-ordered,
task #4: agent writes my.* schemas+fns+todos → restart pod → replay n-ok>0
→ fresh agent retrieves; + upsert-on-redefine probe: redefine fn 2× →
exactly ONE current row, latest source replays) → gym: flip back the
driver_test.cljs:233 engine-bug workaround (fix shipped in 1ae35696), S-06
restart scenario, judge-content red (agents think transact! throws —
covered by docstring moves, re-measure) → auto-run agent tests on fn
update (program graph links fns→tests; results as reactive section) →
remaining §7 backlog (ALS merge, stub zero-forms, :seon.turn/error attr,
MCP session GC, third tile panel) → `seon.mcp` (call user's MCP servers —
user-requested, post-demo OK) → Thursday demo examples on the durable
store. Upstream candidates: datahike join fix PR + the 2 pre-existing
planner stratum-bridge errors.

**Orchestrator protocol that worked (keep):** one unit per agent (≤7 files) →
seon-verifier (or live-proof set for probe/harness lanes) → explicit-path
commit (`git reset HEAD -- . && git add <files> && git diff --cached --stat`
— eyeball — then commit; NEVER bare add+commit, concurrent agents stage);
fence lanes by file list in every prompt; live system is the proof; loss-audit
before any doc rewrite; gym scorecards quantify every context change. Memory
chain: `MEMORY.md` → `project_mvp_demo_status` → this PRD.
