---
type: prd
status: active
tags: [prd, agent]
---

# Agent-FSM — Consolidated Next Steps (2026-06-25)

Context hand-off PRD. Durably records the full state + prioritized plan after
the 2026-06-25 PM session. Supersedes scattered chat state. Primary source:
[[project_agent_fsm_checkpoint_2026_06_25_pm]]. Spec docs cross-linked inline;
this is the working plan, not a restatement of them.

Branch: `feature/agent-fsm`. Ledger: tasks #1-24 (live task list).

## TL;DR

The agent FSM is carved, validated, and green; the gym is re-enabled against
it with a trustworthy DeepSeek judge and an honest first baseline (cond-A
1/3). The embeddings stack is built and benchmarked on random vectors (reads
scale great to six figures; writes are the HNSW-insert bottleneck, one-time +
cache-mitigated), and Vertex/Gemini access is provisioned + verified end to
end. The next unit is the **embeddings build wave** (Vertex switch →
cache/archive → capacity raise → multimodal → augment-tx fix → eager schema
install), gated on an owner go for the Vertex switch (it restarts the live
wire-server). In parallel runs the **context-tuning thread** off the gym's
first real finding (DeepSeek "announces intent then echoes its transcript
instead of emitting a real `(seon.db/query …)`" → never retrieves). WASM is
**deferred, not killed** — a later roadmap item gated on runtime stability +
a proven gym.

## Shipped this session (committed)

### agent-fsm core
- **P4 message↔todo premature-park cure** — render half / safety net (`8af8883`).
- **3b** — delete dormant handler registry + activity-log derivation +
  completed-grid re-key (`0922060`).
- **db/pull time-travel fix** — installed-schema resolves on AsOfDB/SinceDB
  via `dbi/-schema` (`7ed10e2`); temporal db-value wrappers as-of/history/since
  (`05c867a`).
- **Test-stability sweep** — de-fragiled suite (mechanism checks over pinned
  prose, `; namespace` glyph de-pin), memoized index-core → suite ~62s
  (`6fba70e`, `83ef852`, `e52f502`, `c0e319d`, plus the `c11e1ce`…`bd9b353`
  single-`;` clip series).
- **Comment-level convention documented** — prose `;` / code-block `;;` /
  structure `;;;` ([[feedback_test_behavior_not_exact_strings]] +
  `conventions.md` "Comment levels"; `bbf5d16`, `885c197`).
- Earlier in branch: ctx whitelist pruned to 6 verbs + domain-only inventory
  (`e808288`, `45e69e8`); file-section loader folded into `seon.ctx`,
  `my.soul` deleted (`a2481be`); auto-render shared instructions +
  `my.kb.system → my.kb.shared` rename (`d5a4e7f`).

### embeddings infra + Vertex
- **embed-API hardening** — token-budget batching + bounded parallelism +
  429/5xx backoff (`011a9d0`). NOTE: the multi-text batching is **wrong for
  Vertex** (see Open issues #E1) — reworked in the build wave.
- **All embedding specs committed** (`348d38b`): vertex-usage-reference,
  multimodal-design, batched-cache-archive-design, db-scalability-benchmark,
  state-and-activation.
- **GCP/Vertex provisioned + verified** (see Key facts).

### gym
- **Re-enabled vs FSM + judge calibrated + cross-session baseline** (`f9dd92f`,
  `4c65028`, status doc `c29a058`). Driver drives `seon.agent.turn/run-turn!`
  + `seon.agent.loop/run-loop!`, mints a wake per drive, boots via
  `client/bootstrap-turn!`. Suite green.

### DB-scale
- **Benchmark on random vectors** (no embeddings spend) —
  `docs/prds/embeddings/db-scalability-benchmark-2026-06-25.md`. Point/ref-join
  sub-ms warm; KNN ~5ms @100k/28k vectors; heap 144MB@100k; writes
  1040→324 ent/s (HNSW-insert-dominated). ~9.5KB/entity, ~14.5KB/vector@1536.
  Verdict: holds for a six-figure personal corpus.

### docs
- README updated for the CLJS-pod track + measured scale (`45b3120`).

## Open issues + findings

Severity: **P0** blocks the build wave / governance · **P1** correctness ·
**P2** cleanup / latent.

### Embeddings (the build wave)

- **#E1 (P0, #18) Vertex multi-text batching break.** `seon.embed/embed-batch!`
  (`src/seon/embed.clj:770-798`) passes a multi-element `java.util.ArrayList`
  to `.embedContent`. Vertex's `embedContent` takes **ONE content per request**
  — the multi-text call THROWS "only supports one content at a time", and
  `:batchEmbedContents` 404s on Vertex. So `plan-batches` / `max-batch-texts`
  (`:682`) / `embed-batch!`'s whole multi-text path is invalid on Vertex.
  **Fix:** one-text-per-request + parallel fan-out (measured 248 req/s @ conc
  150, 0 429s; raise `max-embed-concurrency` 6→24 at `:687`). Async
  Batch-Prediction jobs are the cheap tier but text-only/gemini-embedding-001/
  minutes — skip until 100k+. Spec:
  [[docs/prds/embeddings/vertex-usage-reference-2026-06-25]].
- **#E2 (P1, #21) augment-tx anchor-loss.** `augment-tx-with-embeddings`
  (`src/seon/embed.clj:1013-1018`) emits a partial assertion
  `{:db/id id-ref :seon/embedding v :seon.embed/source-hash hash}`. Flagged:
  a partial update can lose the `:seon.fn/sym` anchor on the entity. **Fix
  direction:** merge the stored entity first (or assert only the two new
  attrs against a resolved eid that already carries the anchor). Confirm the
  upsert semantics against datahike before changing.
- **#E3 (P1, #20) capacity 10k cap.** `src/seon/embed.clj:129 (def ^:const
  capacity 10000)` is the ONLY self-imposed cap (Proximum default is 10M).
  **Fix:** raise + rebuild-from-archive (free once the cache/archive lands).
- **#E4 (P2, #19) multimodal ingest not wired.** Vertex model is natively
  multimodal (text/image/audio/video/PDF → one unified space, cross-modal
  retrieval proven) but ingest is text-only. Needs a modality field, a
  file→part encoder, SHA-256(file bytes). Spec:
  [[docs/prds/embeddings/multimodal-design-2026-06-25]].
- **#E5 (P2) cache/archive not built.** SHA-256(bytes)+model+task → 3072
  vector in durable data files (`data/embeddings/`), lookup-before-embed,
  free index rebuild. Generalizes `:seon.embed/source-hash`. Spec:
  [[docs/prds/embeddings/batched-cache-archive-design-2026-06-25]].

### FSM / DB / boot

- **#17 (P1) eager schema install.** Lazy schema install means lookup-refs
  throw pre-seed. `src/seon/db.cljs:839,868` document the lazy-install trap
  guard but the root fix is to batch-install registered attrs at boot + on
  `register!`. Confirm the exact throw path before changing.
- **#F1 (P2, NEW) four disabled CLJS agent unit tests pin the pre-FSM shape.**
  Disabled in `1699f46` (WAVE A — turn/wake refactor), never ported:
  `test/seon/agent_retry_test.cljs.disabled` (old `ask-and-eval!` transport
  retry), `test/seon/agent_loop_test.cljs.disabled` (old
  `unanswered-live-inbound?` stop-policy), `test/seon/agent_context_test.cljs.disabled`
  (v4 composer invariants), `test/seon/agent/turns_test.cljs.disabled`
  (`<turns>` countdown). **Action:** port the still-valid invariants to the
  FSM shape or delete — leaving them as `.disabled` rots, and the dev hook
  re-enables `.disabled` tests inside worktrees (memory gotcha). The gym
  driver/paid tests are the only ones already re-enabled.
- **#D1 (P2, NEW) seed-core enforcement TODO.** `src/seon/db/internal.cljs:1051,1066`
  — a pending enforcement that `seed-core!` runs OUTSIDE `with-agent`.
  Documented but not enforced.

### Gym / context-tuning

- **#G1 (P1, the gym working) announce-then-echo.** DeepSeek B "announces
  intent then echoes its own transcript instead of emitting a real
  `(seon.db/query …)`" → never retrieves; caught by the discovery leg +
  judge. cond-A baseline x12 PASS / x1 + x3 FAIL = **1/3** (honest). This is
  a **context-tuning target**, not a harness bug.
- **#G2 (P1, design question) `:shared-instructions` blank on reset.**
  **Verified live** (`my.kb.shared/instructions` → `[]`; the `:my.kb.shared`
  singleton holds only `:my.kb.shared/id "shared"`). This is a reactive
  section working **correctly** — empty data → empty section
  (`src/my/kb/shared.cljs:96-120`), NOT a render bug. The seed
  (`seed-tx-data`, `:62-69`) intentionally seeds the EMPTY zero state (the
  four behavioral teachings live in the system prompt, not here). **Open
  decision:** should a fresh reset seed a default "consult/search the store
  first" instruction? #G1 (B doesn't reliably emit a query) argues **yes** —
  capture as a context-tuning decision, not a bug fix.
- **#G3 (P2) gym §3 catalog incomplete.** X2/X4–X11 unwritten;
  condition-B (embeddings-lift) scaffold and §6 loader lints pending. X4 hits
  format-limitation #4 (no "extend-ok-fork-not" predicate). Spec:
  the retired `docs/prds/gym-v2/` design files remain available in Git history.

### Smaller / latent

- **#14 (P2) latent binding-across-`.then` test.** CLJS dynamic-`binding`
  does not propagate across a `.then` continuation (AsyncLocalStorage is the
  real carrier). Write the guard test for the latent case so a future async
  edit can't silently drop `*conn*`/agent binding mid-continuation.
- **#W1 (P1, doc) README WASM framing is stale.** `README.md:47` frames WASM
  containment as an active in-flight primitive. Per owner: WASM is
  **DEFERRED, not killed** — "we can do wasm once it's stable and the agents
  are running well and our harness is proven to be working without problems."
  Soften to a deferred-roadmap item gated on runtime stability + proven gym.
- **#S1 (P2, JVM-track, paused) `:seon.ai` datahike port FIXMEs.** ~9 FIXMEs
  in `src/seon/ai.clj:342,368,392,407,422,440,456` and
  `src/seon/ai/claude.clj:545,1357,1385` ("port to :seon.ai datahike ns via
  seon.db"). JVM track is paused — do not action now; recorded so they're not
  lost.
- **#S2 (P2) misc TODOs:** `src/seon/server/store.clj:40` (unsupported-store
  clear error), `src/seon/web/brotli.clj:76` (buffer size tuning),
  `src/seon/ai/claude.clj:850` (verify Anthropic default behavior). Low
  priority.
- **Uncommitted working-tree state:** the `SEON_EMBED` persistent edit lives
  in `bin/seon` (working tree, uncommitted) — fold into #18.

## Prioritized remaining plan

### Build wave — embeddings (serialize; #18 needs owner go)

1. **#18 Vertex switch (FOUNDATION, governance-critical).** `bin/seon` env
   (`GOOGLE_GENAI_USE_VERTEXAI=true`, `GOOGLE_CLOUD_PROJECT=<GCP_PROJECT>`,
   `GOOGLE_CLOUD_LOCATION=global`, `GOOGLE_APPLICATION_CREDENTIALS=`the SA key;
   UNSET `GEMINI_API_KEY`) + `embed.clj` Vertex-mode client + model
   `gemini-embedding-2` + `embedContent` + **one-text-per-request rework**
   (#E1). **Restarts the wire-server** → CONFIRM with owner before launching
   (touches their GCP + the live writer); serialize against the gym (both
   want the pod/wire-server). Spec: vertex-usage-reference.
2. **#E5 cache/archive** (batched-cache-archive-design). SHA-256(bytes)+model+
   task → 3072 vector in `data/embeddings/`, lookup-before-embed, free index
   rebuild. Generalizes `:seon.embed/source-hash`. Unblocks free rebuilds for
   #E3.
3. **#20 capacity raise** (#E3) — raise `embed.clj:129` + rebuild-from-archive
   (free, thanks to step 2).
4. **#19 multimodal ingest** (#E4, multimodal-design) — modality field,
   file→part encoder, SHA-256(file bytes), ONE unified index.
5. **#21 augment-tx anchor-loss** (#E2) — merge stored entity first.
6. **#17 eager schema install** — batch-install registered attrs at boot + on
   `register!` (fixes lookup-refs-throw-pre-seed).

### Context-tuning thread (parallel; serialize live runs vs the wire-server)

7. Decide #G2 (seed a default "search the store first" instruction?) → tune
   context so the agent reliably emits a `(seon.db/query …)` (#G1
   announce-then-echo). After any context change: re-align all agent-facing
   guidance + read the actual agent-facing output ([[feedback_align_context_with_runtime]]).
8. Finish the gym §3 catalog (#G3) + condition-B embeddings-lift scaffold +
   §6 loader lints.

### Smaller items

9. #F1 port-or-delete the 4 disabled CLJS agent unit tests against the FSM.
10. #14 write the binding-across-`.then` guard test.
11. #W1 soften the README WASM framing to deferred-roadmap (owner confirm).

## Deferred / roadmap

- **WASM containment — DEFERRED, not killed.** A later roadmap item gated on:
  runtime stability + agents running well + a proven, problem-free gym. Per
  owner. The eval surface moving into a `wasm32-wasip2` component (WIT-typed
  capability surface) remains the target ([[docs/prds/agent-runtime/platform]]),
  just not now.
- **Condition-B embeddings-lift** in the gym — demonstrate retrieval lift from
  embeddings vs the cond-A baseline; needs the Vertex switch + multimodal
  ingest landed first.
- **Rest of the gym §3 scenario catalog** (X2/X4–X11) beyond the
  context-tuning minimum.
- **Async Batch-Prediction tier** for embeddings — text-only,
  gemini-embedding-001, minute-latency; skip until 100k+ corpus.

## Key facts to not lose

### Vertex / GCP (provisioned + verified)
- Project **`<GCP_PROJECT>`** (display name "seon"); billing working
  (paid 200); Vertex API enabled.
- SA **`<vertex-sa>@<GCP_PROJECT>.iam.gserviceaccount.com`**
  (`roles/aiplatform.user`); key **`~/.config/gcloud/<vertex-sa-key>.json`**
  (chmod 600).
- Model **`gemini-embedding-2`** on Vertex **GLOBAL** endpoint +
  **`:embedContent`** method (us-central1 / `:predict` → 404).
- 3072-dim default, **Matryoshka exact** → store 3072, downscale to 1536
  FREE via local truncate + renormalize (`l2-normalize`, `embed.clj:634`).
- Natively **multimodal** → one unified space, cross-modal retrieval proven.
- §17 governed: inputs NOT used for training.
- Auth = ADC via google-auth-library-oauth2-http 1.33.0 (transitive in
  google-genai 1.59.0) — no token code.
- env for Vertex mode: `GOOGLE_GENAI_USE_VERTEXAI=true`,
  `GOOGLE_CLOUD_PROJECT=<GCP_PROJECT>`, `GOOGLE_CLOUD_LOCATION=global`,
  `GOOGLE_APPLICATION_CREDENTIALS=`the SA key path; UNSET `GEMINI_API_KEY`.

### Constants / locations
- Capacity cap (#E3): `src/seon/embed.clj:129` `(def ^:const capacity 10000)`.
- Concurrency (#E1): `src/seon/embed.clj:687` `max-embed-concurrency` 6 → 24.
- Multi-text break (#E1): `src/seon/embed.clj:770-798` (`embed-batch!`),
  `:682` (`max-batch-texts`), `:721` (`plan-batches`).
- augment-tx (#E2): `src/seon/embed.clj:976-1019`, assertion at `:1013-1018`.
- Shared-instructions section (#G2): `src/my/kb/shared.cljs:96-120`; seed
  `:62-69`; composer wiring `src/seon/ctx.cljs:1651`.
- Embed dim 1536 / HNSW cosine: `embed.clj:19,293-303`.

### Reproduce commands
- Gym (judge calibration + baseline):
  `SEON_AI_PROVIDER=deepseek bin/gym --paid=calib,x1,x3,x12`.
- Full CLJS suite: `bin/test-cljs` (fresh JVM, ~62s; never overlap runs).
- Live verify shared singleton: `(my.kb.shared/instructions @seon.db/*conn*)`
  → `[]` (blank by design).
- Cluster reset (fresh world, re-seeds core): `bin/seon cluster reset default`.

### Gotchas (session)
- One live cluster → **serialize** wire-server restarts (Vertex switch + gym
  both contend).
- Don't trust a stale agent output-file mtime as "hung" — verify build health
  + live state before intervening.
- Concurrent `bin/test-cljs` collide on `out/test/` — one consolidating run.
- Dev hook RE-ENABLES `.disabled` tests in worktrees (not on main).
- Verification/research agents MUST be Explore/Plan (overstep → chaos).
- Owner: judge subjective > scalars unless questions sharp; HONESTY >
  impressiveness.
