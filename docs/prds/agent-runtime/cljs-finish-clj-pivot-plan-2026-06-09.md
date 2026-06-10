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
cutover required for Friday. The lane-merge (§5) is the post-Friday epic.

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
- **Inspector dead-agent SSE 404** (pod lane): fix lives in
  `seon.web.inspector`'s `route?`/`handle!` pair (serve.cljs:366 dispatch;
  unclaimed routes fall to the 404 at :367).
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

**Pod flip checklist (post-Friday; from the DIS research file):**

1. Move the `:seon-wire` writer + listen-adapter into the `:client` build
   (live socket `tmp/seon-cluster-default-req.sock`, store
   `data/clusters/default/store`, `:lock-blob? false`, store `:id` via
   `seon.server.store/name->uuid`).
2. `bin/seon restart wire-server` once — picks up the already-prepared
   sha-aligned `:writer` alias (kills the mvn-0.8.1671 skew).
3. Wire the adapter into `seon.db/listen!`; stop minting
   `data/seon-pod/<run-id>` stores at boot.
4. Keep `clj -M:replica-peer-jvm` + `clj -M:replica-probe-jvm` as the
   regression pair; re-run both after the flip.

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

1. **#26 finding-salience + instruction-clarity rewrite** — consult-before-
   research (query `:finding` rows for the topic BEFORE searching),
   fully-qualified-keyword teaching (the worked example models real
   namespaces and explains the rule), store-proactively. Then **run 8**;
   pass bar = agent #2's first move is consulting stored knowledge (§1).
2. **ALS unification + `seon.agent`/`seon.agents` MERGE** (user-approved) —
   one ns, ONE ALS carrying a single per-agent context map (today
   `seon.db/agent-id-als` and `seon.agents/substrate-ctx-als` are parallel);
   `run-as-agent` wraps `db/with-agent`.
3. **css autobuild in `bin/seon`** + stop committing `output.css`.
4. **stub-llm zero-forms termination fix** (§4 self-wake bug).
5. **Test-litter** — fixtures minting disk store dirs → `:memory` (3.1 GB /
   2,295 run dirs accumulated under `data/seon-pod/`; add a prune policy).
6. **Hook-loss source fix** (V2 server lane) — `^:clj-reload/keep` +
   key-based hook registration + remove the silent catch (§4).
7. **Third tile panel** (§6 forward item 2).
8. **Lane-merge epic** — the §5 flip checklist + Timbre unification + cljc
   convergence.
9. **MCP server health** — reset the broken `default` cljs session; GC the
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
