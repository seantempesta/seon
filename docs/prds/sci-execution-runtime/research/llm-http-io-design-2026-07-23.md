---
type: research
status: active
tags: [research, agent, runtime, architecture]
---

# LLM + HTTP-adjacent I/O design — stateless, sci-friendly, no-crash/no-lockup (2026-07-23)

Owner-directed design unit (ruling 20c as amended: LLM I/O gets its own
research; the HTTP-leaf implementation timing is an owner discussion, not
a scheduled unit). This document details the accepted loop design's §5d
(LLM I/O) and the render/HTTP story it deferred to ruling 20(d), for the
two failure-rich effect surfaces of the agent loop: (A) model calls and
(B) web fetches + hiccup/HTML rendering. It composes with — never
competes with — `research/loop-cljc-sci-design-2026-07-23.md` (claim on
the run entity, phase cursor §3, attempt receipts §4, optimality table
§8) and the landed seam (`research/p1-capability-seam-design-2026-07-22.md`:
pure `.cljc` core, one leaf per tier, entry-only conditionals, four
effect classes, op-id minted at entry). Binding constraints honored:
one mechanism, errors as values, no stored derived state, async ceremony
confined to leaves, metadata minimalism (ruling 19), context derived
never stored (ruling 21).

## 0. Grounded current state (fresh eyes, file:line)

### 0a. The LLM path today

- **Config is already stateless.** The provider/model/thinking/timeout
  vocabulary is one DB singleton + per-agent override attrs; env seeds
  once, the DB owns the row (`src/seon/ai.cljs:16-36`, `sync!`
  ai.cljs:951-1001). `resolved-config-from-rows` (ai.cljs:719-761)
  derives one frozen `::config-resolution` VALUE from maps pulled at one
  immutable database value — request opt → agent override → config row →
  `shipped-defaults` (ai.cljs:343-356) — including the per-attempt
  timeout and the fallback variant's whole secondary resolution. Nothing
  in resolution is process state; API keys alone are read from
  `process.env` at call time (openai_compat.cljs:124-145) and only their
  non-secret SOURCE is retained as evidence.
- **The adapters are one-attempt value machines.** Both SDK clients ship
  `maxRetries 0` (openai_compat.cljs:351-360, anthropic.cljs:307-315);
  every failure mode resolves to the `:seon.ai/error` envelope
  (ai.cljs:181-190) with a small closed failure vocabulary: `::timeout?`,
  `::transport?` (the ONE retryable transport class, ai.cljs:84-92),
  HTTP `::status`, parsed `::retry-after-ms` (ai.cljs:197-231). SDK
  error-class mapping is `error->envelope`
  (openai_compat.cljs:314-340) — branch order matters because the SDK's
  timeout/abort classes subclass its connection class.
- **Request build and response interpretation are already mostly pure.**
  `request-params` (openai_compat.cljs:153-201) is a CLJ map transform
  from the frozen resolution; `parse-completion`
  (openai_compat.cljs:233-303) maps the completion object to the
  response map with bounded identity evidence
  (`response-identity-result`, openai_compat.cljs:216-231). The genuinely
  platform-bound residue is enumerable: the SDK client construction +
  `.create`/`.stream` calls (openai_compat.cljs:351-360, 506-557),
  `AbortController`, `js/URL`/`js/Date` in the retry-after/endpoint
  helpers (ai.cljs:140-231), node-crypto digests (ai.cljs:654-661), and
  the stream consumer (below).
- **Retry is one authority with pure decisions.**
  `seon.agent.turn/call-llm!` (turn.cljs:968-1026) is the sole retry
  path; `llm-retryable?` (turn.cljs:781-796) and `llm-retry-strategy`
  (turn.cljs:798-814) are pure functions over the failure vocabulary;
  `seon.retry`'s combinators are portable `.cljc`
  (retry.cljc:54-131) while its `with-retry!` executor is
  `#?(:cljs …)`-only (retry.cljc:137-195, Promise + `setTimeout`).
  Bounds: per-attempt wall clock (`bounded-llm-attempt!`
  turn.cljs:921-966 races the request against the frozen
  `agent-attempt-timeout-ms` and aborts the provider signal), retry
  count (agent override, default 4, turn.cljs:779), per-wait clamp 20 s
  and total-backoff ceiling 60 s (turn.cljs:774-778,
  `max-duration` retry.cljc:120-131). A `call-llm!` can therefore never
  park a turn — every axis is bounded.
- **Streaming exists and is a leaf concern already.** repl-mode
  `:stream` consumes SDK deltas and aborts at the first complete
  top-level form (`stream-until-form!` openai_compat.cljs:371-417),
  losing the provider usage chunk and substituting a client-side
  estimate flagged `:seon.ai/estimated?` (openai_compat.cljs:419-431).
  `:batch` is one nonstreaming completion. The stream consumer NEVER
  rejects — mid-stream transport failure returns as a captured `::error`
  value re-raised into the one `error->envelope` site
  (openai_compat.cljs:394-417, 529-533).
- **The transport residue that is process state:** the attempt buffer
  `!attempts` atom (turn.cljs:981) — rows only persist at turn close
  (deleted by loop-design L1); the `AbortController` per attempt
  (fresh per retry, never reused — src/seon/ai/AGENTS.md contract); the
  llm-fn closure registry in the loop (`!loop-input`, a drive
  prerequisite the loop design demotes, loop-cljc-sci-design §0/§2c).
- **Attempt evidence schema is rich and durable-ready.** The
  `:seon.ai.attempt/*` component family (turn.cljs:161-219) already
  carries ordinal, provider/adapter/model, both timeouts, stream flag,
  extra-body digest, credential class, outcome enum
  `#{:success :provider-error :adapter-timeout :outer-timeout}`,
  error status, response identity, finish reason, truncation, usage.
  What is missing for crash honesty is exactly the loop design's L1:
  an `:open` state committed BEFORE dispatch.

### 0b. The render path today

- **User-authored render fns already execute in a killable child, as
  values.** The compiled prompt owner resolves each block's
  `:seon.render/ai` symbol through `invoke-selected!` invocations
  (runtime.cljs:129-165); a failed block becomes `block-error-text` in
  the block's slot (runtime.cljs:157-161) and the prompt still renders.
  The whole-prompt symbol resolves the same way
  (runtime.cljs:190-203). The web UI's agent view goes through the
  identical door (`seon.execution.runtime/render-agent-view!` invoked
  compiled, datastar.cljs:1074, 1093-1095); a failed html renderer
  becomes a canvas `error-card`/`error-response` hiccup value
  (runtime.cljs:106-127, canvas.cljs:544-570) — the page stays 200.
- **The invocation protocol is the render deadline today.** Every
  compiled invocation carries `::deadline-ms` = now + 10 min
  (`maximum-invocation-ms` execution.cljs:25-30, stamped at
  execution.cljs:577) and `::result-limit-bytes`
  (execution.cljs:31-34). The pod's `invoke!` arms a timer per
  invocation; expiry cancels and, when cancel cannot settle, SIGKILLs
  the child (execution/host.cljs:1195-1219, `kill-process!`
  host.cljs:182-183). Per-agent invocation tails serialize invocations
  for one agent and let different agents proceed concurrently
  (host.cljs:1221-1238, 1266-1274). So a render infinite loop today
  costs at most one child process and one deadline; the pod itself
  never blocks on it.
- **In-process render walking is guarded and bounded but NOT
  preemptible.** The one walker catches per node and routes through the
  strict dial (`strict-fail!` render.cljs:313-329; prod → graceful
  error card, render-entity-html render.cljs:331-371); output is
  bounded by per-block `:seon.agent.ctx/token-cap` clipping
  (ctx.cljs:1890-1898), eval/message/result-body render caps and the
  value-walker depth/keys/items/realized caps
  (config.cljs:569-746, render/value.cljc:124-156, 320-333). None of
  that bounds CPU: a `(while true)` inside a render fn running
  IN-PROCESS would hang that process. Today that cannot happen in the
  pod because user-authored render fns run in the child; it is exactly
  the property the W5 child deletion (ruling 20d) must not lose.
- **The JVM host currently refuses rendering** (invoke.clj:139-144:
  "The JVM host does not serve <sym>; prompt and view rendering stay on
  the pod") — a deliberate seam awaiting the ctx/render port
  (loop design §5c: ~8.5k lines, seon.eval re-seam needed).

### 0c. Containment ground truth per tier

- **JVM sci tier:** per-invocation scheduled watchdog →
  `Thread.interrupt` (invoke.clj:30-35, 96-103) + sci `:interrupt-fn`
  polling at fn entry/loop recurrence (host/context.clj:875-884) +
  interrupt-aware `clojure.core` overrides + an uncatchable interrupt
  marker (reference-code/sci/src/sci/interrupt.cljc:32-43, producers
  :45+). Interrupted forms surface as `:seon.eval/interrupted?` error
  values with receipt terminalization (host/eval.clj:123-138, 464-481).
  Deadline admission refuses an already-elapsed invocation
  (invoke.clj:175, 198). Fixed eval pool + 2-thread watchdog pool +
  thread-per-session (host.clj:276-278, 222).
- **Bun tier:** no in-thread preemption exists or can exist (one event
  loop). Bounds are awaiter-freeing races (`await-bounded`
  loop.cljs:289-311; `race-timeout` in `bounded-llm-attempt!`),
  CAS work-fences aborting late settlers' writes, child-process SIGKILL
  on invocation deadline (0b), and WP-S2 exact-generation TERM→KILL
  supervision for whole-process death (both kill modes drilled,
  program-synthesis "WP-S2 CLOSED").
- **Loud failure:** a non-empty executable batch with zero recorded
  attempts is a recorded `:core` fault + flat `:seon/error`, never a
  silent formless turn (turn.cljs:440-492). This invariant is the
  template this design extends to LLM attempts and renders.

## 1. LLM I/O as a capability family (design question 1)

### 1a. The split — portable core `seon.ai` (.cljc), one leaf per tier

Strengthen in place; no new namespace family. The port follows the P1c
seon.db exemplar order exactly.

**Portable core (promote to `.cljc`, zero platform code outside entry
fns):**

- Config resolution: everything from `resolved-config-from-rows` down
  is already pure over pulled maps (ai.cljs:679-761); the only residue
  is `parse-double*`/`parse-int*`/date/crypto helpers → reader-tag
  islands or leaf clock/digest services (same pattern as db.cljc's
  leaf-provided clock/uuid, seam ruling 4).
- Request builders: `request-params` (openai_compat.cljs:153-201) and
  the anthropic equivalent become pure core fns producing the
  provider's wire map (bare keys stay — third-party boundary,
  deliberately un-namespaced).
- Response interpreters: `parse-completion` minus `js->clj` — the leaf
  hands the core an already-data body (the JVM leaf parses JSON to CLJ
  maps; the pod leaf `js->clj`s once at its edge). Bounded identity
  evidence and the empty-content/reasoning diagnosis stay in the core.
- Failure vocabulary + classification: the `:seon.ai/error` schema and
  `parse-retry-after-ms` are already portable logic; the SDK
  `instance?` mapping in `error->envelope` is honest LEAF code (each
  leaf maps ITS transport's failures onto the one vocabulary; the
  vocabulary itself — timeout?/transport?/status/retry-after-ms — is
  the core contract both leaves fill).
- Retry decisions: `llm-retryable?`, `llm-retry-strategy`,
  `llm-fallback-eligible?`, and the tuning constants move from
  turn.cljs into the core (they are pure and the loop core port — loop
  design §5b — needs them portable anyway). `seon.retry`'s combinators
  are already `.cljc`; add the CLJ executor arm to `with-retry!` in the
  SAME fn (reader conditional at the sleep only: `Thread/sleep` under
  the invocation's interruptible thread on the JVM, `js/Promise` +
  `setTimeout` on the pod). One executor, one strategy vocabulary — no
  second retry path.
- Attempt tx builders: `attempt-row` (turn.cljs:831-908) plus the L1
  open/terminal builders (1c) — pure functions from the frozen
  resolution + response value to tx-data.
- Token accounting: `seon.ai.tokens` is already `.cljc`; the estimated
  usage builder (openai_compat.cljs:419-431) moves to the core.

**Pod leaf (existing machinery, unchanged behavior):** SDK client
construction with `maxRetries 0` + injected `*fetch*` test seam,
`.create`/`.stream`, `AbortController` lifetime, `stream-until-form!`,
the SDK error-class mapping. Stays `.cljs`.

**JVM leaf (the PROPOSAL — implementation timing is the deferred owner
discussion, ruling 20c):** `java.net.http.HttpClient` — no SDK. Shape:

- one process-shared `HttpClient` (connect timeout from config); per
  request a `HttpRequest` built from the core's wire map (JSON-encoded
  body, bearer header from the leaf's env read at call time — same
  key-resolution rules as openai_compat.cljs:124-145, shared as core
  logic returning the env-var NAME candidates, leaf does the lookup);
- synchronous `send` on the invocation's own worker thread with the
  request `.timeout` set from the resolution's adapter timeout;
  `HttpClient.send` is interruptible — `Thread.interrupt` from the
  existing invocation watchdog (invoke.clj:30-35) lands as
  `InterruptedException`, which the leaf maps to the envelope's
  `::timeout?`/interrupt class. This means the JVM leaf inherits the
  host's ONE deadline mechanism instead of adding a second timer; the
  per-attempt cap is enforced by the same watchdog that bounds evals.
- no streaming in the first cut: the JVM leaf serves repl-mode `:batch`
  only (one buffered completion). `:stream` remains a pod-leaf
  capability (1d).
- effect metadata: the entry fn (`complete` in the core) declares
  `:seon.capability/effect :external` — a model call mutates the world
  (provider billing/logging) without a durable provider-side receipt we
  can address; replay is never automatic (ruling 19b default is
  conservative anyway; declaring documents intent).

The census gains the `seon.ai` family rows through the same computed
mechanism; the dual-tier `.cljc` test exercises request build →
fake-leaf → interpret → attempt-row on both runners
(test/seon/db/portable_test.cljc placement precedent, wiki
"dual-tier tests below a namespace directory").

### 1b. What stays out of the core deliberately

- `generate-code!` and `seon.ai.dispatch`'s process-local provider
  registry: dispatch stays a pod concern until a cluster JVM actually
  executes the LLM phase; at that point registration is leaf
  installation through `seon.capability` (seam contract 5), not a
  second registry.
- The diffusion/typeahead providers: `:local-worker` locality
  (ai.cljs:254-266) is pod-only, dev-only (D12); the JVM leaf never
  grows a diffusion arm.

### 1c. Attempt lifecycle — open/terminal receipts (composes with L1)

Adopted from loop design §4 verbatim, detailed here:

- `attempt-open-tx`: commits `{ordinal, fallback-variant, frozen
  non-secret config projection (the existing attempt attrs),
  outer-timeout-ms, stream?, opened-at}` with outcome ABSENT (or a new
  `:seon.ai.attempt/state :open` — prefer extending the existing
  `outcome` enum with `:open` so there is one status word, exactly as
  the run reuses its heartbeat as the lease). Committed BEFORE the leaf
  dispatches; the tx leads with the run's pointer+epoch fence, so a
  displaced the process cannot open attempts.
- `attempt-terminal-tx`: CAS `outcome :open → <terminal>` + the
  response evidence fields + reply-blob link in the same tx (the eager
  reply-blob link at turn.cljs:700-710 folds in here). A CAS loser
  (already terminalized by a stealer) discards its late result; its
  writes never land — the flat CAS error value is the signal.
- The `!attempts` atom (turn.cljs:981) and close-time carriage
  (`:seon.agent.turn/llm-attempts` through ask-and-eval! →
  close-turn!) are DELETED in the same change; close-turn! stops
  merging attempt rows because they are already durable.
- Retry ordinals: the next ordinal is derived from the turn's attempt
  rows at the acquired db value, not from atom count — a takeover
  inherits the honest ordinal sequence and the retry budget
  (`agent-max-retries` counts ROWS, so crash-loops burn the same
  budget as transient errors; bounded by construction).

### 1d. Streaming — grounded answer

Is streaming needed? Today `:stream` exists for one purpose: abort at
the first complete top-level form so a `:stream`-mode turn evals one
form (openai_compat.cljs:371-417); `:batch` is the default for
planning/repair variants and the demo arcs ran `:batch`-shaped
multi-form turns. Proposal:

- **Streaming is leaf ceremony, not core contract.** The core's
  response value is the terminal `{text, usage, estimated?, …}` map;
  whether it was assembled from deltas is invisible above the leaf.
  The first-form abort predicate (`first-top-level-close` +
  `parse-forms` confirm) is ALREADY portable `seon.repl.parse` — the
  pod leaf keeps calling it per delta; a future JVM streaming leaf
  (`HttpClient` line-consumer over SSE) would call the same predicate.
  Nothing new to design; explicitly NOT built until a cluster JVM
  needs `:stream` mode.
- Consequence for scheduling (loop design §3): a run whose agent
  resolves repl-mode `:stream` is LLM-phase-eligible only on the pod
  until then. That is policy data on the existing phase-eligibility
  rule, not a mechanism.

### 1e. Idempotency, resume, and the run-holding process death answer

A model call is `:external`: no provider idempotency key is grounded
for DeepSeek/OpenAI-compat (loop design §4 finding stands — the
request-id in the response is evidence, not a replay address).
Therefore:

- op-id/`:seon.capability/op-id` does NOT apply to model calls. The
  attempt receipt is Seon-side identity only: `(turn-id, ordinal)`.
  There is nothing to replay; there is only honest accounting.
- **Process holding the run dies mid-call:** the attempt stays `:open`. The lease
  expires (heartbeat + stale-ms); a stealer wins the epoch CAS, reads
  the phase cursor at `:attempt-open`, terminalizes the open attempt
  via the receipt CAS to outcome `:crashed` (new terminal enum member —
  distinct from `:provider-error` because the truth is UNKNOWN: the
  provider may have billed and completed), and opens the NEXT ordinal
  if budget remains, else closes the turn `:error` through the normal
  path. Double-billing on kill-mid-request is possible and is recorded
  as two attempt rows — honest, visible, bounded by the retry budget.
- **Same-process restart (pod restart, single-run-holding process world):**
  identical mechanics via lease-aware `recover!` (loop design §2c) —
  recovery terminalizes `:open` attempts exactly like `:running` eval
  receipts (recovery.cljs receipt CAS precedent), never re-runs them.

### 1f. Rate limits and budget as database facts

- **Retry-After already crosses as data** (`::retry-after-ms` parsed at
  the leaf boundary, honored by the retry override,
  turn.cljs:1003-1007). With L1, the terminal attempt receipt carries
  it (`:seon.ai.attempt/retry-after-ms`, one new optional attr), so
  "the provider told us to wait until T" is a database fact any
  the process can read.
- **In-turn backoff stays in-process and bounded.** The sleep between
  attempts (≤20 s per wait, ≤60 s total) remains the executor's local
  wait — durable-parking every backoff as a schedule row would trade a
  bounded 60 s for tx churn and a second scheduling mechanism. The
  bound is what makes this safe: the process can hold its lease through
  the entire worst-case retry envelope (60 s backoff + 5×2 min
  attempts) only if the heartbeat continues; the loop's per-turn beat
  already covers it. If the owner later wants cross-process backoff
  continuation, the receipt's `retry-after-ms` + opened-at already
  contain the derivation — no schema change needed; explicitly out of
  scope now.
- **Spend accounting is derived, never stored as a counter.** Usage is
  on attempt receipts (provider-reported or flagged estimated);
  per-agent/per-day spend is a query over attempts (temporal indexes
  are free — loop design §1). If a budget CAP becomes policy, it is a
  config fact consulted by the pure retry/attempt-admission decision
  ("open attempt iff derived spend < cap") — same shape as
  turn-limit/deadline bounds on the run. No new mechanism: it is one
  more pure predicate over facts at the acquired value.

## 2. Render/HTTP containment (design question 2)

### 2a. Where user-authored render fns execute in the P4 world

Ruling 20(d) fixes the order: (a) in-pod move at W5 first, (b) full
ctx/render `.cljc` port as its own later unit. This design specifies
the containment consequences of each stage and the end state:

**Stage (a) — W5 in-pod move (interim, honest about Bun):** when the
per-agent Bun child dies, `render-prompt!`/`render-agent-view!` and the
per-block `invoke-selected!` calls relocate into the pod process.
What is KEPT: the invocation-shaped boundary (deadline-ms,
result-limit-bytes, errors-as-values per block) — the render entry
stays an invocation-plan execution even in-process, so the caps and the
per-block error slots survive verbatim. What is LOST: process
isolation — an in-pod `(while true)` in an agent-authored render fn
would hang the pod's event loop; `await-bounded` frees no awaiter
because the loop never yields. Compensating controls at stage (a):

- the strict truth that MOST render input is core-authored: stored
  blocks ship `:seon.render/ai` symbols pointing at core
  `seon.agent.ctx.*` fns (bounded by construction); the AGENT-authored
  surface is canvas renderers + authored converters + whole-prompt
  overrides — enumerable via `error/agent-authored-sym?`
  (runtime.cljs:96).
- route exactly that enumerable agent-authored subset to the JVM sci
  tier as soon as stage (b) bindings exist; until then WP-S2 pod
  respawn + the run deadline reaper are the honest physics for a
  pathological authored renderer (same posture as pod eval before
  ruling 18 routing — and pod evals of `seon.packages.js.*` batches
  retain exactly this exposure today; render adds no NEW class of Bun
  hazard, it widens an accepted one until (b) lands).
- this is the one place the design says plainly: **on Bun, a
  user-authored infinite render loop is not preemptible; it is
  supervised away (process kill + claim steal), not interrupted.**

**Stage (b) — render on the sci host (the end state):** the ctx/render
family port (loop design §5c) makes every block render a sci
invocation on the JVM host:

- the invoke.clj:139-144 refusal is REPLACED by serving
  `render-prompt!`/`render-agent-view!` through the same
  `eval-batch-result`-style lane: per-invocation admission, deadline
  watchdog → `Thread.interrupt` → sci uncatchable interrupt, receipts.
  One mechanism — the render invocation is not a new lane, it is one
  more compiled-function identity on the existing session surface.
- **per-render deadline:** the block render invocation carries the
  existing `::deadline-ms`; the sci `:interrupt-fn` preempts authored
  loops (`while`/`reduce`/`range` — the interrupt-aware core overrides
  cover the lazy producers, sci/interrupt.cljc:45+). A render that
  exceeds its deadline yields the interrupt error VALUE in that
  block's slot (`block-error-text` shape, runtime.cljs:157-161) — the
  prompt renders with one error block; the renderer never crashes; the
  turn proceeds and the agent SEES the failed block naming its own fn.
- **bounded output:** unchanged owners — per-block token-cap clip
  (ctx.cljs:1890-1898), the value-walker caps, result-limit-bytes on
  the invocation. A render output blowup is clipped at the same
  boundaries a huge eval result already is; the receipt records the
  honest pre-clip weight the same way value sampling does.
- **render purity (ruling 21):** renders are pure functions FROM the
  acquired database value; the `result/<id>` input-impurity defect
  (issue triage #1) is folded into this unit's spec — membership must
  derive from database facts so the same value renders identically
  after restart/eviction. Rendered artifacts never enter datoms
  (prompt-blob ref + basis t + numeric projections only — verified
  current state).
- malformed hiccup: already a value at two boundaries — non-vector from
  an html renderer → error-card (runtime.cljs:112-119); walker-level
  throw → strict dial / graceful card (render.cljs:313-371). The port
  keeps both; sci adds nothing to design here.

### 2b. Web fetches

`seon.agent.web` is ALREADY the seam exemplar for this class: portable
core + pod leaf landed (P2, `85780757`), `fetch` declared
`:seon.capability/effect :external`, grants `:read`
(web.cljc:169, 202-206); timeout-ms default 30 s, token-bounded preview
with full text in the blob tier, every failure a flat error value
(web.cljc:206-232). The JVM web leaf is a queued portfolio unit
(fs/shell/web/blob leaves — program synthesis, owner direction
2026-07-23 morning); its transport shape is the SAME `java.net.http`
proposal as 1a's LLM leaf — one HttpClient discipline, interruptible
sends under the invocation watchdog, redirect/private-range policy from
the portable core's existing pure checks. No new design is required
beyond this alignment ruling: **the LLM leaf and the web leaf share the
JVM HTTP transport discipline (client construction, timeout wiring,
interrupt mapping) but remain two family leaves with their own
vocabularies** — no umbrella "http capability" noun (vocabulary rule:
producer/consumer terms, no third noun).

## 3. The no-crash/no-lockup argument, surface by surface (question 3)

For every execution site this design touches: what bounds CPU, memory,
output, and blast radius. Honest gaps are named with their compensating
control.

| Surface | CPU bound | Memory bound | Output bound | Blast radius | Honest gap + compensation |
|---|---|---|---|---|---|
| LLM call, pod leaf | per-attempt cap races + aborts the signal (turn.cljs:921-966); adapter timeout; retry total ≤60 s backoff, ≤5 attempts | SDK buffers one completion; max-tokens output cap in the request (ai.cljs:424) | response identity/evidence caps (openai_compat.cljs:216-231); reply → blob tier | one Promise in the pod; error value on every failure path | none new — bounded today; a hung native fetch is freed by the race, late settler CAS-fenced |
| LLM call, JVM leaf (proposed) | invocation watchdog → Thread.interrupt; HttpClient send is interruptible; request `.timeout` | one buffered body per call on a pool worker thread | same core evidence caps + result-limit-bytes | one pool thread; error value | a non-interruptible DNS stall edge exists in theory → the watchdog's second line is the session deadline admission (invoke.clj:175-198) + WP-S2 host respawn |
| Retry loop | strategy is finite by construction (`max-retries` + `max-duration`, retry.cljc:112-131); infinite builders are lazy and never realized (schema `sequential?`, retry.cljc:33-38) | O(1) — last result only | n/a | in-process wait only | none: an unbounded strategy cannot be expressed through `llm-retry-strategy`; direct `with-retry!` misuse is core code, gated by review + schemas |
| Render fn, sci host (stage b) | per-invocation deadline → Thread.interrupt → sci uncatchable interrupt + interrupt-aware core producers | no per-context heap ceiling in sci (honest) → JVM heap ceiling + WP-S2 host respawn (q18 OOME drill precedent, loop design §7) | block token-cap clip + value caps + result-limit-bytes | one eval-pool thread; failed block renders as its error value | allocation bombs outrun the interrupt poll in pathological cases → supervisor physics, receipts terminalize `:interrupted` on respawn |
| Render fn, in-pod Bun (stage a interim) | NONE in-thread (single event loop) — deadline frees awaiters only | pod heap | same clips/caps | THE POD — worst surface in this design | stated plainly in 2a: agent-authored renders on Bun are supervised (WP-S2 kill + claim steal + lease-aware recovery), not preempted; shrink the window by routing authored renders to the host as soon as (b) lands |
| Render walker in web UI (pod, core-authored) | trusted core code; per-node catch; no authored code after (b) | value-walker realized-items/depth caps | value caps; SSE morphs are per-element | one request/feed; error banner value | acceptable: core renderer bugs are `:core` faults under the strict dial, not agent exposure |
| Web fetch, pod leaf | timeout-ms (30 s default); redirect cap | preview token cap; full text streams to blob | preview cap + honest metadata | one Promise | landed (P2) |
| Web fetch, JVM leaf (queued) | as LLM JVM leaf | as LLM JVM leaf | same core caps | one pool thread | same DNS-stall note |
| Parallel run-holding process (any) | its own tier's bounds above | its own process | n/a | its own process; writes epoch-fenced | run-holding process death anywhere → lease expiry → steal → receipts terminalized; nothing waits on a corpse |

The containment claim in one sentence: **every authored-code execution
site is either behind the sci interrupt (JVM) or behind a killable
process boundary (Bun child today, WP-S2 pod supervision at stage a),
every wait is finite by construction, every failure is a flat value
with a database trace, and every late effect of a displaced worker is
CAS-fenced — so no agent behavior can crash a process, and the one
surface where an agent can STALL a process (in-pod Bun render, stage a
interim) is explicitly supervised and explicitly temporary.**

## 4. Parallelism (question 4)

- **Across runs:** the loop design's claim model is the whole story —
  run-holding process + epoch CAS on the run entity, heartbeat lease, steal on
  expiry. N processes holding runs (pods, hosts) advance disjoint runs with zero
  new coordination: Datahike's single-writer thread serializes every
  claim/fence tx (writer.cljc:42-76 grounding in the loop design §1);
  losers get direct CAS error values.
- **Within a turn — what parallelizes:** pure derivation over the
  turn's ONE acquired database value: block renders are independent
  reads-at-a-value and MAY fan out (today `invoke-selected!` sends one
  batched invocation; on the host the blocks of one render invocation
  may run on the eval pool concurrently — a later optimization, not a
  requirement; correctness is value-purity, ruling 21). Reads at a db
  value need no locks by construction.
- **Within a turn — what serializes:** the phase cursor is a strict
  chain (rendered → attempt-open → reply-ready → evaling → evaled →
  published, each a CAS advance) — there is deliberately NO
  overlapping of LLM call with eval of the same turn; the turn IS a
  sequence. Concurrent LLM calls therefore only ever belong to
  DIFFERENT runs/agents, which the pod already does naturally
  (per-agent invocation tails, host.cljs:1221-1274) and the host pool
  does with its fixed thread count as the concurrency ceiling.
- **JVM host primitives today vs P4 need:** thread-per-session +
  fixed eval pool + scheduled watchdog (host.clj:222, 276-278) are
  sufficient; no virtual-thread migration is required by this design
  (the pool bounds concurrent authored work deliberately — a
  containment feature, not a limitation). The writer remains the one
  serialization point for ALL durable effects; receipts + epoch fences
  make duplicate scans/steals converge (loop design §6).
- **No shared mutable state:** the only process-local mutables that
  remain are honest caches/coordination (sci context cache keyed by
  generation+corpus basis, connection pools, invocation tails) — none
  is a correctness authority after L0-L2 delete the promise-registry/
  attempt-buffer/cursor authorities.

## 5. Failure taxonomy end to end (question 5)

Every row: nothing throws into any loop; every failure leaves a datom
trace; the agent-visible value is the flat steering error shape
(`:seon.error/message`/`kind` or the `:seon.ai/error` specialization at
the LLM boundary).

| Failure | Detection | Recorded fact | Agent-visible value | Recovery action (effect class) |
|---|---|---|---|---|
| LLM adapter timeout | SDK timeout class → envelope (openai_compat.cljs:322-327) | attempt terminal `:adapter-timeout` | turn `:error` string after budget; transcript shows attempts | `:external` — retry NEW ordinal within budget; never replay |
| LLM per-attempt cap (outer) | race wins, signal aborted (turn.cljs:951-961) | attempt terminal `:outer-timeout` | same | same; cap frozen per turn so attempts are comparable |
| 429 / 5xx | HTTP status in envelope + parsed Retry-After | attempt terminal `:provider-error` + status + retry-after-ms | retry log line; final error after budget | retryable class (turn.cljs:781-796); backoff honors Retry-After clamped 20 s |
| Transport reset / fetch throw | `::transport?` (the ONE retryable transport class, ai.cljs:84-92) | attempt terminal `:provider-error` + transport flag | same | retry within budget; fallback variant when eligible (turn.cljs:816-821) |
| Malformed/unparseable response body | interpreter yields envelope (no flags — never retried) | attempt terminal `:provider-error` + raw-body evidence (bounded) | turn `:error` with the message | non-retryable; surfaces immediately |
| Empty visible text, thinking spillover | parse-completion diagnosis (openai_compat.cljs:281-289) | attempt `:success` + usage; turn-outcome guard re-prompts | next-turn steering | `:pure` re-derivation — no effect at risk |
| Truncation at completion cap | finish_reason "length" (openai_compat.cljs:259, 292-296) | attempt truncated? + error when blank | error naming the completion limit | agent raises max-tokens via config facts |
| Process holding the run death mid-LLM-call | lease expiry (beat + stale-ms) observed by any run-holding process | attempt CAS `:open → :crashed`; steal epoch bump; phase cursor unchanged | recovery notice derived from history; run RESUMES | `:external` — new ordinal, never replay; double-billing possible, recorded as two rows |
| Render fn throws | per-block invocation error / walker catch (runtime.cljs:157-161, render.cljs:360-371) | eval-lane receipt (host) or invocation error; `:agent` fault datom via `error/record!` | the block renders AS its error value naming the fn | `:pure` — next turn re-derives; agent fixes its fn |
| Render infinite loop (JVM, stage b) | deadline watchdog → Thread.interrupt → sci interrupt | receipt terminal `:interrupted`, kind `:timeout` | error block naming the deadline | `:pure` — re-render next turn; repeated loops burn turn budget, run closes bounded |
| Render infinite loop (Bun child, today) | invocation timer → cancel → child SIGKILL (host.cljs:1204-1219) | canceled-error + child-retired evidence | error block / turn `:error` | child respawns; run continues |
| Render infinite loop (in-pod Bun, stage a) | run deadline reaper / WP-S2 supervision (pod stops beating) | run closed or claim stolen after lease expiry; recovery anchor | resumed run; recovery notice | supervised, not preempted (§3 gap row); window closed by stage b |
| Render output blowup | token-cap clip + value caps + result-limit-bytes | clipped block + honest cap markers | clipped text with cap notice | `:pure`; caps are config facts the agent can read |
| Malformed hiccup | non-vector html → error-card (runtime.cljs:112-119) | `:agent` fault datom | error card on the surface, page 200 | agent fixes renderer |
| Web fetch timeout/DNS/private-range | leaf maps onto family envelope (web.cljc) | capability receipt per effect metadata | flat error with steering | `:external` fetch — never auto-replayed; `:read` grants re-derivable |
| Process holding the run death mid-eval | existing receipt CAS on takeover (recovery precedent) | receipt `:interrupted` | steering in next prompt | absent effect ⇒ `:external`, never redispatch (wiki, landed regression) |
| Zero-attempt executable batch | loud write-back guard (turn.cljs:474-486) | `:core` fault + flat error | `:seon/error` value | core bug — fix the tier, never silent |

The loud-failure invariant extension: L1 adds the mirror guard for LLM
work — **a turn that reaches the LLM phase and records zero attempt
rows is a recorded `:core` fault**, exactly parallel to the
zero-attempt eval guard.

## 6. Phasing (question 6)

Rides the accepted L0-L4 spine; new work is confined to the units the
rulings already opened.

- **L1 (attempt receipts) — carries 1c wholesale.** Falsifier: kill
  the pod between dispatch and response; before: DB cannot say an
  attempt existed; after: `:open` row with ordinal + config digest
  survives. Adds the zero-attempt-LLM loud guard + the
  `retry-after-ms` receipt attr. Smallest first slice of THIS design.
- **L2 (phase cursor + lease-aware recovery) — carries 1e.** Falsifier:
  the five kill points incl. after-possible-provider-acceptance; the
  stolen run completes with ≤1 terminal attempt per ordinal and an
  honest `:crashed` attempt row where the kill landed.
- **L3 (portable cores) — carries 1a's core promotion** (retry
  decisions, builders/interpreters, `with-retry!` CLJ arm) WITHOUT the
  JVM transport leaf: the cluster JVM advances eval phases; LLM
  phases park for the pod (5d-i posture). Falsifier: dual-tier `.cljc`
  test builds+interprets the same request/response bytes on both
  runners; cluster JVM completes an eval phase while the pod is dead.
  **Break-and-replace (owner authorization, 2026-07-23):** the Bun
  loop side may BREAK during this port — the pod driver is rebound
  over the portable core in the same change and the superseded
  pod-local paths (`!attempts` carriage, turn.cljs-resident retry
  decisions, the CLJS-only executor arm as a separate body) are
  deleted, not kept running in parallel for compatibility. No
  dual-driver balancing: while the port lands, the cluster may have NO
  working Bun driver until the rebind is proven — the falsifier
  gates the rebind, not coexistence.
- **L4(b) (JVM LLM http leaf) — the deferred owner discussion**
  (ruling 20c). When ruled in: falsifier = kill the pod BEFORE the LLM
  phase; the host claims, calls the provider through `java.net.http`,
  and the turn completes with the pod down for the whole arc. This is
  what upgrades U12 from "pod restarts invisibly" to "pod is optional".
- **W5 render move (ruling 20d-a) — carries 2a stage (a)** with the
  invocation-shaped boundary preserved and the honest Bun gap recorded
  in the anchor. Falsifier: a throwing block and an oversized block
  render as error/clipped values in a live prompt after the child is
  gone; full suite + live drive unchanged.
- **Render port unit (ruling 20d-b) — carries 2a stage (b) + ruling
  21's result/<id> purity fix + lifting the invoke.clj refusal.**
  Falsifier: an authored `(while true)` canvas renderer on the host
  yields an `:interrupted` error block within its deadline while the
  host serves other sessions; same db value renders byte-identically
  before/after restart.
- **Web JVM leaf (portfolio, already queued)** — aligns to 2b's shared
  transport discipline when it lands.

## 7. Owner decisions needed

1. **JVM LLM http leaf timing (the ruling-20c morning discussion).**
   Recommendation: rule it an L4 unit — it is small once L3's core
   promotion lands (builders/interpreters are shared; the leaf is one
   HttpClient discipline shared with the queued web leaf), and it is
   the piece that makes the pod genuinely optional.
2. **`:crashed` attempt terminal.** Extending the attempt outcome enum
   with `:open` and `:crashed` (1c/1e) vs a separate state attr.
   Recommendation: one enum, two new members — one status word per the
   run-entity precedent.
3. **Authored-render routing at stage (a).** Accept the interim in-pod
   exposure for agent-authored renderers until stage (b) (recommended;
   it widens an existing accepted Bun exposure rather than adding a
   class), or gate authored renderers OFF (error-block placeholder) in
   the in-pod interim. Recommendation: accept + record in the anchor;
   gating would silently degrade live canvases.
4. **JVM streaming leaf.** Not designed, not scheduled (1d);
   `:stream`-mode agents stay pod-eligible for the LLM phase. Confirm
   or ask for the SSE consumer design now.
5. **Spend-cap policy fact.** 1f sketches derived spend + a config cap
   consulted by attempt admission. Build now or leave as the recorded
   derivation? Recommendation: leave derived-only until a real budget
   incident; the receipts already carry everything.
6. **Break-and-replace scope (owner authorization 2026-07-23: the Bun
   loop side may break during the port; no dual-driver balancing).**
   Where this design previously weighed keep-both-running
   compatibility, the cleaner break shape is now preferred and is
   recorded in §6/L3: the pod driver rebinds over the portable core in
   the same change, superseded pod-local paths are deleted rather than
   kept live, and the cluster may run driverless between rebind and
   proof. Two consequences to confirm the reach of the authorization:
   (a) it strengthens the one-mechanism deletions (the `!attempts`
   buffer, turn-resident retry decisions, and the separate CLJS
   executor body all die in the port change instead of after it);
   (b) it OPTIONALLY shortens the render interim — with breakage
   allowed, ruling 20d's stage (a) in-pod move can be a minimal
   throwaway (or, if the ctx/render port is close enough at W5,
   skipped in favor of going straight to host-served authored renders,
   eliminating the §3 in-pod-Bun gap row entirely). Recommendation:
   take (a) unconditionally; decide (b) by the ctx/render port's
   readiness at W5 — do not preserve a compatibility render path
   merely to avoid a dark window the authorization permits.

## Appendix — dependency ledger

- Vendored sci fork branch `seon` (`8fac6e8`):
  `reference-code/sci/src/sci/interrupt.cljc` (uncatchable interrupt
  32-43, interrupt-aware producers 45+, clojure-core override map).
- Vendored Datahike seon fork: CAS semantics
  transaction.cljc:963-985; single-writer LocalWriter writer.cljc:42-76
  (both via the accepted loop design §1 — not re-derived here).
- openai-node / anthropic-sdk-typescript (vendored): `maxRetries`,
  error-class hierarchy, `.stream`/`.finalChatCompletion` — grounded at
  openai_compat.cljs:314-360 call sites.
- `java.net.http.HttpClient` (JDK, proposal only): interruptible
  send + per-request timeout are the load-bearing properties (1a); to
  be re-verified against the JDK docs in the implementing unit's own
  ledger before code.
- First-party mechanisms strengthened: `:seon.ai.attempt/*`
  (turn.cljs:161-219), `seon.retry` (retry.cljc), the seam installer,
  the invocation protocol (execution.cljs:25-34, 577), the sci host
  invocation lane (invoke.clj/eval.clj), WP-S2 supervision.
