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
- JVM: `(user/run-tests)` **2544 pass / 1 fail / 0 errors** — the 1 is the
  known `collect-flow-status` `[:maybe]` violation (ledger #4, JVM lane).

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
- **JVM clj-reload hook-loss** (V2 server lane): reloading
  `seon.server.registry` empties `!on-ensure-db-hooks` while defonce guards
  block re-registration until JVM restart; failures swallowed by
  `catch Throwable _`. Source fix = `^:clj-reload/keep` (or key-based
  idempotent registration) + remove the silent catch. Live JVM repaired via
  REPL; details in the test-stability audit.
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
- **`[:maybe ::flow-status]`** in `src/seon/flow/status.clj:393` (JVM lane):
  bump in place to `::flow-status` + explicit not-registered error.

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

1. **#26 finding-salience + instruction-clarity rewrite** — PLUS (user, 2026-06-10, observed live): `message!`/`reply!` must return a CONCISE registered response (`{:seon.message/ok? :seon.message/id :seon.message/hops}`; error envelope on failure) — today they return the raw transact envelope (full tx-report = ~1.5k transcript chars per reply + a misdirected "narrow your query" hint). The A3 principle applied. Also: consult-before-
   research (query `:finding` rows for the topic BEFORE searching),
   fully-qualified-keyword teaching (the worked example models real
   namespaces and explains the rule), store-proactively. Then **run 8**;
   pass bar = agent #2's first move is consulting stored knowledge (§1).
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
12. **AGENT-GYM scenario harness (the testing methodology — behind #26):** scenarios as EDN data ({questions, fixtures, PASS-PREDICATES as datalog queries against the post-run store + transcript}); a driver that boots fresh agents on a SCRATCH cluster DB, sends via `message!`, awaits idle, evaluates predicates mechanically, emits a scorecard keyed (scenario × git sha) — section-by-section context iteration becomes QUANTIFIED. Rubric axes: sees-question · searches-first · models-work-directed · reuses-schemas · consults-findings · reuses-FUNCTIONS · writes-tests · replies-honestly · terminates · stores-proactively. Budget tiers: stub-llm for plumbing scenarios, deepseek (2-4 calls) for behavioral. Every defect from runs 3-7 becomes a permanent regression predicate; run 8 = the first scenario. Known prompt-thin spots the gym must cover: test-writing (thinnest teaching), function-reuse (never yet observed), store-proactively.
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

## 10. HANDOFF (2026-06-10, orchestrator context rollover)

State at handoff: **THE FLIP IS DONE (0c9c76e)** — one unified CLJ+CLJS
system; pod runs on the durable cluster store (`data/clusters/default/store`),
JVM sole writer, cross-process reactivity proven, fail-loud no-fallback boot.
All four processes green (`bin/seon status`). Suite 285/1065/0 exit-0.

**IN FLIGHT: none — the cluster-reset + UI-front-door unit LANDED (see this commit).** Was — builds
`bin/seon cluster reset` (and performs the user-approved one-time wipe of the
test debris as its live proof) and diagnoses/fixes the human chat path in the
browser (user couldn't trigger agents from the UI; evidence suggests messages
LAND but the boot agent's llm-fn or the visible-response link is broken —
reproduce as a human first). When it reports: verify its live proofs → commit
with explicit-path staging → then proceed down §7.

**Next in queue (in order):** #26 finding-salience + instruction-clarity
(teach MULTI-SEGMENT keyword namespaces, e.g. `:kb.finding/*` — single-segment
is the violation; the agent followed our own bad example) → run 8 with the
USER driving from the UI (pass bar: agent #2's first eval queries findings) →
the agent-gym harness (§7 item 12) → demo examples Thursday on the durable
store. The demo script draft + open question (tile-rewrite closer in/out)
is in the 2026-06-10 conversation; re-confirm with the user.

**Orchestrator protocol that worked (keep):** one unit per agent (≤7 files) →
seon-verifier (or live-proof set for probe/harness lanes) → explicit-path
commit (`git reset HEAD -- . && git add <files> && git diff --cached --stat`
— eyeball — then commit; NEVER bare add+commit, concurrent agents stage);
fence lanes by file list in every prompt; live system is the proof; loss-audit
before any doc rewrite. Memory chain: `MEMORY.md` → `project_mvp_demo_status`
→ this PRD.
