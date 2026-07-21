---
type: research
status: active
tags: [research, agent, web, database]
---

# Retrieval-augmented denoising experiment plan — Capability #3 (RAG inside the generation loop)

> **STATUS (2026-06-29): the SEON-side RETRIEVAL LEG is BUILT + OFFLINE-PROVEN.**
> `seon.diffusion.retrieval` (`src/seon/diffusion/retrieval.cljs`, tests
> `test/seon/diffusion/retrieval_test.cljs`) implements the three steps end to
> end with NO GPU and NO embeddings: (1) DETECT unresolved/hallucinated symbols
> in a canvas string (`unresolved-references` — free-reference extraction over
> `seon.repl.internal/parse-forms`, minus locals/specials/core, then program-
> graph membership), (2) RETRIEVE the real `:seon.fn` candidates
> (`retrieve-candidates` — exact + near-name Levenshtein over `:seon.fn/sym`;
> SEMANTIC `seon.embed/search-pull` as the `SEON_EMBED` enhancement via
> `retrieve-for-canvas+semantic`), (3) EMIT the injection descriptor
> (`build-injection` / `to-wire`) in the worker's `{op,…}` clamp shape — the
> real symbol + signature/spec + char-span. **Offline proof** (6 tests / 37
> assertions, full cljs suite green 814/3708): a seeded graph + a canvas
> referencing `transct!` / `db/transct!` →
> `transct! [94 102]` → `seon.db/transact!` (edit-distance 1, full signature) →
> `{:op :clamp :span [94 102] :replacement "transact!" :spec_text "…"}`. The
> AUROC-0.471 split holds: `reduce-kv` (a REAL core fn) is NOT graph-flagged
> (that residual is eval's job, not retrieval's). **End-to-end AWAITS GPU** —
> Part 2 (encoder-KV injection, W1–W3) is unchanged confirm-on-deploy; the
> descriptor's char-span is exactly what the worker maps to renoise token
> positions via `offset_map`. The `spec-for-span` SKETCH below is SUPERSEDED by
> the built `seon.diffusion.retrieval/retrieve-for-canvas` (graph-first, with
> the embed path folded in as the enhancement, not the primary).

> Ready-to-run plan + worker stub so the MOMENT DiffusionGemma deploys we can run
> the capability that pays for itself precisely where entropy is blind: when the
> model commits a **plausible-but-wrong fn/API name**, embed the partial canvas,
> hit Seon's **Vertex + Proximum/HNSW program graph**, and inject the right
> fn-spec into the encoder for the next denoise steps. Companion to [[index]]
> (the 4 capabilities + the `accept_canvas` seam),
> [[infill-experiment-plan-2026-06-28]] (Capability #1 — shares its
> `introspect` findings, do NOT re-derive U1–U4),
> [[eval-renoise-experiment-plan-2026-06-28]] (Capability #2 — shares its
> **stateless round-trip** architecture + the **char-span → canvas-position
> offset_map**, do NOT re-derive), and
> [[parser-as-generation-oracle-2026-06-28]] (the FLAG class this retrieval
> oracle serves). Gated by **`SEON_EMBED`**.

## TL;DR

- **The win:** the program graph is the only oracle that catches a **confidently
  wrong name**. The Transformer Lab paper measured commit-entropy on
  DiffusionGemma at **AUROC 0.749 on code-shape uncertainty but 0.471 (≈ random)
  on factual recall** — the model self-detects "I'm unsure about the *shape*" but
  is **blind to "I wrote `reduce-kv` when I meant `reduce`"**. Parser + eval (the
  Capability-#2 oracles) catch ~93.5% of meaning-altering corruptions but leave a
  residual that is, by their own measurement, the **dead-data / wrong-name class**
  — "only an intent-level (factual/retrieval) oracle can" close it
  ([[parser-as-generation-oracle-2026-06-28]] "Combined parser + eval"). **This
  capability IS that oracle**: embed the partial canvas, KNN the program graph,
  inject the nearest real fn-spec so the next denoise steps re-commit the symbol
  toward a name that *exists*.
- **Two triggers, not one** (the honest reconciliation of the AUROC split):
  - **Trigger A — entropy-gated, mid-denoise (cheap, speculative).** A symbol-
    region commits with **high** per-position entropy → the model *knows* it is
    unsure → fire retrieval. Catches the AUROC-0.749 shape-uncertainty band.
  - **Trigger B — graph-membership-gated, post-canvas (the gap-closer).** A
    committed symbol that **does not resolve in Seon's program graph** (an
    `:invalid-token` / unresolved-var FLAG, or a symbol absent from
    `:seon.fn/sym`) → fire retrieval **regardless of entropy**. This is the band
    entropy CANNOT see (AUROC 0.471): the model committed the wrong name
    *confidently*, so only an external membership check surfaces it. **Trigger B
    is what earns the capability its keep**; Trigger A is a cheaper complement for
    the cases the model self-flags.
- **The Seon retrieval seam is REAL, shipping infra — grounded here exactly.** The
  pod already owns `seon.embed/search-pull` (NL query → KNN over the Proximum HNSW
  program-graph index → entities pulled from the local db), the wire transport
  `seon.store.internal.wire-node/knn-search`, the query-builder
  `seon.agent.ctx/retrieval-query`, and the Vertex/Gemini embed +
  L2-normalized 1536-dim cosine HNSW on the JVM wire-server. **Part 1 below is a
  concrete spec against these exact fns** — no new retrieval mechanism, only a
  new caller (a span→spec oracle) and a fn-scoped `:where`.
- **The GPU-side injection is CONFIRM-ON-DEPLOY.** How to **append the retrieved
  spec to the encoder KV cache and continue denoising the SAME canvas without a
  full re-prefill** is the cross-attention seam J/L flagged — uncertain, resolved
  by `gpu_worker_retrieval.py mode="introspect"` (the new unknowns W1–W3). The
  always-correct fallback (re-prefill `prompt + spec`) is named; the worker
  **withholds the guessed inject call** until introspect resolves the seam,
  exactly like J/L.

## Why entropy needs retrieval — the AUROC-0.471 gap, made precise

From [[index]] / the Transformer Lab paper (arXiv:2606.14620) and the three-tier
boundary measured in [[parser-as-generation-oracle-2026-06-28]]:

| tier | catches | provable blind spot |
|---|---|---|
| **commit-entropy** (model-internal) | code-*shape* uncertainty (AUROC 0.749) | **wrong fn/API name (AUROC 0.471 ≈ random)** — the model is confidently wrong |
| **parser** (`parse-forms` `:span`/`:error-kind`) | 92.7% of corruptions; SAFE auto-fix vs FLAG | `:invalid-token`/unresolved-name is FLAGGED but **not fixable** without a name source |
| **eval** (SCI cage) | +91.5% of masked-divergent (62.5% reference-free) | **dead-data + wrong-name on the live path** — "only an intent-level oracle can" |
| **retrieval (THIS capability)** | the wrong-name residual — supplies the *correct existing name* | bounded by index coverage (a name absent from the graph can't be retrieved) |

The key insight: **entropy and the program graph are complementary, not
redundant.** Entropy fires when the model is unsure (Trigger A); the graph fires
when the model is *sure and wrong* (Trigger B). A design that triggered retrieval
on entropy *alone* would systematically miss the exact class the paper says
retrieval exists to catch. So Trigger B (membership) is primary; Trigger A is the
cheap speculative complement.

## Part 1 — the Seon-side retrieval oracle (GROUNDED — real infra)

This half is **concrete** because every piece already ships in the pod. The
oracle is one new fn that composes existing verbs; no new retrieval mechanism.

### What already exists (file:line-grounded)

1. **Query builder — `seon.agent.ctx/retrieval-query`**
   (`src/seon/agent/ctx.cljs:1531`). `{:seon.db/db db :seon.agent/id id} →
   :string`. Derives the embed text from the turn's latest live inbound. For
   Capability #3 the "query" is **the partial canvas text** (the committed tokens
   around the high-entropy / unresolved span), not the inbound — so the oracle
   builds its own query string from the canvas, but reuses the SAME downstream
   `search-pull`. (`retrieval-query` is the precedent: it does NOT add the
   retrieval-instruction prefix — "the wire-server's `knn-search` adds it".)

2. **Pod search verb — `seon.embed/search-pull`** (`src/seon/embed.cljs:173`),
   `^:async`, schema `[:=> [:cat ::search-pull-request] :any]`:

   ```clojure
   ;; request shape (seon.embed)
   {:seon.embed/query        "<partial canvas / span context text>"  ; required
    :seon.embed/k            5                                       ; optional, default 10
    :seon.embed/where        [[?e :seon.fn/source]]                  ; optional type-scope
    :seon.embed/db           <local db value>}                       ; optional (else *conn*)
   ;; resolves to
   {:seon.embed/hits [{:seon.embed/eid e
                       :seon.embed/distance d            ; cosine, ascending
                       :seon.embed/entity {…pulled attrs…}} …]}
   ```

   The pod **never embeds** — it sends query TEXT over the wire; the wire-server
   embeds (Gemini, retrieval prefix) + runs KNN. `:where` is resolved to an eid
   set on the LOCAL db (`where->eids`, `src/seon/embed.cljs:124`) and the
   wire-server restricts KNN to those eids (a Proximum entity-filter).

3. **Wire transport — `seon.store.internal.wire-node/knn-search`**
   (`src/seon/store/internal/wire_node.cljs:186`). RPC op `"knn-search"` over the
   UDS: `{:seon.store.wire/query q :seon.store.wire/k k :seon.store.wire/eids
   [...]}` → `[{:seon.embed/eid e :seon.embed/distance d} …]`.

4. **JVM embed + index** (`src/seon/embed.clj`): model `gemini-embedding-2`,
   `outputDimensionality 1536`, **L2-normalized** client-side, no taskType (the
   retrieval instruction is prepended to the QUERY text only). Index is a
   datahike `:proximum` secondary index — **HNSW, cosine, dim 1536, capacity
   10000** (`src/seon/embed.clj:18`). The **whole feature is OFF unless
   `SEON_EMBED`** (`embed-feature-enabled?`, `src/seon/embed.clj:152`;
   pod mirror `seon.agent.turn/embed-retrieval-on?`, `:137`).

5. **What is indexed = the program graph.** The ONLY default embeddable is any
   entity carrying **`:seon.fn/source`** (`register-embeddable!`,
   `src/seon/embed.clj` — `:seon.embed/trigger-attr :seon.fn/source`). The
   embedded document is `compose-fn-doc` → `"<sym>\n<doc>\n<source>"` where
   `:seon.fn/sym` is the FQ `"<ns>/<name>"` identity. So **the index already maps
   "code that does X" → the real fn that does X** — exactly the substrate this
   capability needs.

6. **The spec text to inject** comes from the `:seon.fn` entity attrs
   (`src/seon/agent.cljs:198`): `:seon.fn/sym` (FQ name, `:db.unique/identity`),
   `:seon.fn/arglists`, `:seon.fn/doc`, `:seon.fn/spec` (the Malli `:malli/schema`
   string), `:seon.fn/source`. A compact injectable spec is the first four —
   **name + arglist + docstring + malli schema** — without the full body.

### The oracle — `spec-for-span` (the one new fn; NOT yet in src/)

A pure composition of the above. Proposed seam (drop into a new pod ns, e.g.
`seon.diffusion.retrieval`, when Capability #3 is wired — **not added to src/ by
this prep**):

```clojure
;; PROPOSED — grounded against seon.embed/search-pull; not yet wired.
(schema/register! ::span-context [:string {:min 1}])   ; partial-canvas text around the span
(schema/register! ::spec-text    :string)              ; injectable fn-spec (may be "")
(schema/register! ::fn-scope [:vector :any])           ; the :where for fn entities

(def fn-scope
  "Type-scope KNN to program-graph FUNCTIONS only (attribute-presence — the
   attribute IS the kind; no :seon/kind). Excludes any non-fn embeddable a
   consumer registers (kb rows, …)."
  '[[?e :seon.fn/source]])

(defn ^:async spec-for-span
  "Capability-#3 retrieval oracle. Embed the partial-canvas span context, KNN the
   program-graph fn index, and return the nearest real fn-specs as injectable
   text. PURE reader; fail-soft (no hits / SEON_EMBED off → \"\")."
  {:malli/schema [:=> [:cat [:map [::span-context ::span-context]
                                  [::k {:optional true} :seon.embed/k]]]
                  [:map [::spec-text ::spec-text]
                        [:seon.embed/hits :seon.embed/pull-hits]]]}
  [{::keys [span-context] :keys [] :as req}]
  (let [k (or (:seon.embed/k req) 5)
        {:seon.embed/keys [hits]}
        (await (embed/search-pull
                 {:seon.embed/query        span-context
                  :seon.embed/k            k
                  :seon.embed/where        fn-scope
                  ;; narrow the pull to the compact spec (not the whole body):
                  :seon.embed/pull-pattern '[:seon.fn/sym :seon.fn/arglists
                                             :seon.fn/doc :seon.fn/spec]}))]
    {::spec-text (->> hits
                      (map (fn [{e :seon.embed/entity}]
                             (str (:seon.fn/sym e)
                                  (when (:seon.fn/arglists e) (str " " (:seon.fn/arglists e)))
                                  (when (seq (:seon.fn/doc e))  (str "\n; " (:seon.fn/doc e)))
                                  (when (seq (:seon.fn/spec e)) (str "\n" (:seon.fn/spec e))))))
                      (clojure.string/join "\n\n"))
     :seon.embed/hits hits}))
```

Notes that make this honest, not hand-wavy:

- **`::spec-text` may be `""`** — `SEON_EMBED` off, empty index, or fail-soft nil
  (the pod's `run-turn!` already treats embed failures as fail-soft → no hits;
  `src/seon/agent/turn.cljs:170`). The injection step then no-ops; the denoise is
  byte-identical to no-retrieval. Same default-OFF discipline as
  `seon.agent.ctx.relevant`.
- **The query text** is the partial-canvas context, NOT the whole canvas: take the
  committed text in a window around the flagged span (the surrounding form gives
  the embedder the *intent* — "a fn that reduces xs to a sum" — even though the
  committed name is wrong). This mirrors `compose-fn-doc`'s "name+doc+source"
  document shape on the document side.
- **`fn-scope` excludes the wrong name itself** only incidentally — the index is
  fn entities, and the wrong committed name (e.g. `reduce-kv` used on a vector) IS
  a real fn, so KNN may return it; the *ranking* + the surrounding-form context is
  what surfaces the better match. The membership check (Trigger B) is separate
  (below): "does this committed symbol resolve at all?" is a local-db lookup, not
  a KNN.

### Trigger B membership check (local, no model, no KNN)

"Does the committed symbol exist in the program graph?" is a one-clause local
query — the same `db/query` surface `seon.embed/where->eids` uses:

```clojure
(defn symbol-resolves?
  "True iff `sym` (an FQ \"<ns>/<name>\" or a bare name) is a known program-graph
   fn. A FALSE here on a committed symbol is the Trigger-B retrieval signal — the
   confidently-wrong-name class entropy cannot see."
  [db sym]
  (boolean (seq (db/query {:seon.db/db db
                           :seon.db/query '[:find ?e :in $ ?s :where [?e :seon.fn/sym ?s]]
                           :seon.db/args [sym]}))))
```

For a bare (unqualified) name the parser/eval tier already names it: SCI's
"Unable to resolve symbol: foo" (the `:seon/error` message —
[[eval-renoise-experiment-plan-2026-06-28]]) IS the membership failure. So
**Trigger B reuses the Capability-#2 eval oracle for free** — an unresolved-var
error is exactly "this name is not in the graph," and its char span is the
retrieval target.

## Part 2 — GPU-side injection (CONFIRM-ON-DEPLOY)

This half is **uncertain** and marked as such. The goal: append the retrieved
`spec_text` to the **encoder context** so the decoder cross-attends it on the
NEXT denoise steps over the SAME canvas, **without re-prefilling the whole
prompt**. Reference J's introspect for the shared seams (U1 canvas-seed, U2
sampler attach, U3 `accept_canvas` return/mutate, U4 mask id/layout) — this plan
does NOT re-derive them.

### The mechanism (what we KNOW from the model card + bd3lms)

The encoder is an AR pass over the prompt → a KV cache, cross-attended every
denoise step ([[index]] "Encoder/decoder"). Injection means **extending that KV
cache** with the spec tokens. Two routes:

- **Route INCREMENTAL (the goal — cheaper).** Run the encoder forward on JUST the
  `spec_text` tokens with `past_key_values =` the existing prompt cache, appending
  the new K/V — then resume the decode loop over the in-flight canvas. The decoder
  now cross-attends `[prompt ++ spec]`. No re-encode of the prompt. Whether the
  model's encoder exposes an incremental, position-correct KV append (and whether
  the canvas's bidirectional cross-attention mask must be widened to cover the new
  positions) is **W1 — confirm-on-deploy**.
- **Route RE-PREFILL (the always-correct fallback).** Re-encode `prompt ++
  "\n;; relevant fns:\n" ++ spec_text` from scratch → fresh KV cache → re-seed the
  canvas with its already-committed tokens (the Capability-#2 V1 re-seed seam) and
  continue denoising. Correct but pays a full prefill. Used to **prove the idea
  works** before optimizing to INCREMENTAL.

### New unknowns — MUST CONFIRM ON FIRST DEPLOY (`mode="introspect"`)

J resolves U1–U4; L resolves V1–V3 (canvas re-seed, offset-map fidelity, in-place
convergence). This plan adds **W1–W3**, resolved in one A100 call. **Do not issue
a guessed inject call before these resolve.**

- **W1 — encoder KV extensibility.** Does the model expose an encoder forward that
  accepts `past_key_values` and returns an **extended** cache (so spec tokens
  append without re-encoding the prompt)? Introspect reflects the encoder
  forward's signature (`past_key_values` / `use_cache` / `cache_position` kwargs)
  and the cross-attention mask construction. If absent → use Route RE-PREFILL.
- **W2 — cross-attention mask widening.** When the encoder cache grows from
  `Lp` to `Lp+Ls`, does the decoder's cross-attention mask auto-cover the new
  positions, or must it be rebuilt? (A stale mask would make the spec invisible —
  a silent no-op, the worst failure.) Introspect dumps the decode-step
  cross-attn mask shape before/after a synthetic cache extension.
- **W3 — mid-denoise inject point.** Can the encoder cache be extended **between**
  decode steps mid-`generate()` (so the same canvas continues with the spec in
  context), or only at a `generate()` boundary (→ the round-trip: stop, inject,
  re-`generate` from the re-seeded canvas)? This decides per-step inline
  (Trigger A in `accept_canvas`) vs round-trip (Trigger B between canvases).
  Introspect checks whether `accept_canvas` (or the decode loop) can reach + mutate
  the encoder `past_key_values`.

### The control seam (shared)

`EntropyBoundSampler.accept_canvas(current_canvas, denoiser_canvas, logits,
cur_step)`, logits `[1, 256, 262144]`, open `transformers` 5.11.0, class
`DiffusionGemmaForBlockDiffusion`, no `trust_remote_code`. For Capability #3 the
override additionally (a) reads per-position entropy from `logits` (Trigger A —
the SAME entropy computation J's infill override already does), and (b) on a
mid-denoise inject (if W3 permits) splices the spec KV into the encoder cache the
cross-attention reads. If W3 forbids mid-denoise, injection is a `generate()`
boundary op (round-trip) and `accept_canvas` only supplies the entropy signal.

## The architecture — Seon-driven round-trip (mirrors Capability #2)

**Who calls whom — decided, with a reachability reason.** The pod's HTTP is
**loopback `127.0.0.1:7890`**, NOT internet-reachable from a RunPod worker. So the
clean, deployable design is **Seon drives the loop** (worker stays a stateless
denoiser — the same shape L chose), NOT the worker calling back into the pod:

```
            ┌──────────────────── Seon pod (embed/search-pull + program graph) ───────────────┐
            │                                                                                  │
  (1) generate_canvas {prompt}                                                                 │
   ─────────────────────────────►  GPU worker (A100, DiffusionGemma)                           │
            │                         denoise; record per-position entropy                     │
            │                       ◄─── {text, canvas_tokens, offset_map,                      │
            │                              entropy[256], committed_symbols:[{span,sym}]}        │
            │                                                                                   │
            │  (2) flag spans needing retrieval:                                                │
            │      Trigger A: entropy[span] high (AUROC-0.749 band)                             │
            │      Trigger B: sym not in :seon.fn/sym  OR  eval :seon/error "unresolved" (gap)  │
            │  (3) for each flagged span:                                                       │
            │      span_context = canvas text window around the span                            │
            │      {spec_text} = (await (spec-for-span {::span-context span_context}))          │
            │         · embed (Vertex gemini-embedding-2, 1536, L2-norm) on wire-server         │
            │         · KNN Proximum HNSW, :where [[?e :seon.fn/source]]                         │
            │         · pull :seon.fn/{sym,arglists,doc,spec} from LOCAL db                      │
            │  (4) map span → renoise_positions via offset_map (the L linchpin)                 │
            │                                                                                   │
  (5) inject {canvas_tokens, renoise_positions, spec_text}                                      │
   ─────────────────────────────►  GPU worker                                                  │
            │                         extend encoder KV with spec_text (W1/Route)               │
            │                         re-mask renoise_positions, clamp the rest (L's clamp)     │
            │                         re-denoise IN PLACE cross-attending the spec               │
            │                       ◄─── {text, canvas_tokens, offset_map, entropy}             │
            │                                                                                   │
            │  (6) goto (2) until no flagged span OR round-cap (e.g. 3)                         │
            └─────────────────────────────────────────────────────────────────────────────────┘
```

- **Transport is JSON over HTTPS** to RunPod `/run` + poll `/status`, identical to
  the proven `gpu_worker.py` and to L's round-trip. **No tensors cross the wire** —
  `canvas_tokens` (≤256 ints), `offset_map`, `entropy` (256 floats), and
  `spec_text` (a short string) are all tiny.
- **The worker is stateless across the round-trip** (matches Flash scale-to-zero).
  Seon holds the canvas + offset_map between calls. The ONLY new GPU state is the
  per-call encoder-cache extension, which is rebuilt from the passed `spec_text`
  each `inject` call — no cross-request GPU memory.
- **Reuse, not re-derivation:** the `offset_map` (char↔token) and the
  re-mask/clamp are **L's** mechanism verbatim; the entropy read is **J's**; the
  only genuinely new GPU piece is the encoder-cache extension (W1–W3). Seon's
  retrieval (Part 1) is entirely existing infra.

### Inline (per-step) variant — confirm-on-deploy, not preferred

If W3 shows the encoder cache CAN be extended mid-`generate()` AND the pod is made
reachable from RunPod (an SSH/ngrok tunnel, or running the worker against a public
Seon endpoint — explicitly out of scope for the loopback pod), the
`accept_canvas` override could call the oracle synchronously the instant a
symbol-span commits high-entropy, injecting before the next step. This is the
literal "RAG inside the loop." It adds a network RTT per inject inside the GPU
forward path and needs a publicly reachable Seon — so the **round-trip is
primary**; inline is a later optimization gated on W3 + a reachability solution.

## Worker-mode design (`gpu_worker_retrieval.py`)

Standalone `@Endpoint` `diffgemma-retrieval` (same A100 / NetworkVolume / env as
`gpu_worker.py`; the proven generate path and J/L's stubs are untouched). Modes:

| mode | input | output | purpose |
|---|---|---|---|
| `env` | — | import/health info | cheap liveness |
| `introspect` | — | W1–W3 answers (encoder KV-extend signature, cross-attn mask before/after a synthetic extend, mid-denoise inject reachability) + reuses J/L U/V findings | **the first-deploy oracle for capability #3** |
| `generate_canvas` | `{prompt, max_new_tokens}` | `{text, canvas_tokens, offset_map, entropy, committed_symbols}` | round-trip leg 1 — denoise + surface the entropy + committed symbols (STUB on generate until J's U1–U3 confirmed) |
| `inject` | `{canvas_tokens, renoise_positions, spec_text}` | `{text, canvas_tokens, offset_map, entropy}` | round-trip leg 5 — extend encoder KV with `spec_text`, re-mask + clamp, re-denoise (STUB until W1/W3 + L's V1 confirmed) |

`generate_canvas`/`inject` build the layout + the `accept_canvas` override + the
entropy read, and report `retrieval_status: STUB` **without** issuing a guessed
generate/inject — wired to the real sampler + encoder-cache extension only after
introspect resolves the seam. The **entropy read** and the **committed-symbol
span extraction** (decode the canvas, locate symbol tokens via `offset_map`) are
pure functions and ship LIVE — exercised in `introspect` against the real
tokenizer the moment it loads. Deliberate, same discipline as J/L: ship the
plumbing, not a fabricated result.

## The concrete first test case — a plausible-but-wrong fn name

The canonical Trigger-B case: the canvas denoises to a form that **parses and runs
the wrong fn**, where entropy is LOW (confidently wrong) and only the program
graph catches it.

```clojure
;; what the canvas denoises to (leg 1) — wrong reducer name, confidently committed:
(defn sum [xs] (reduce-kv + 0 xs))
```

- **Why entropy misses it:** `reduce-kv` is a real, common Clojure fn — the model
  commits it with high confidence (low entropy). The AUROC-0.471 band exactly.
- **Trigger B fires:** evaluate in the SCI cage → `reduce-kv` on a vector throws
  (or `symbol-resolves?` / the eval `:seon/error` flags it). The span is
  `reduce-kv`'s char range → `offset_map` → its canvas token positions.
- **Retrieval (Part 1, real infra):** `span_context` = `"(defn sum [xs] (… + 0
  xs))"` (the surrounding intent: "reduce a seq of numbers to a sum"). `spec-for-
  span` embeds it (Vertex), KNNs the fn index `:where [[?e :seon.fn/source]]`,
  returns the nearest real fn-spec — `clojure.core/reduce` (or a Seon `sum`
  helper if one is indexed): `reduce ([f coll] [f val coll]) ; …malli schema`.
- **Inject + re-denoise:** extend the encoder KV with that spec, re-mask the
  `reduce-kv` positions, clamp the rest, re-denoise. The decoder now cross-attends
  the real `reduce` signature and re-commits the divisor name toward `reduce`.
- **Expected in-place fix:**

```clojure
(defn sum [xs] (reduce + 0 xs))   ; reduce-kv → reduce, one span, conditioned on the retrieved spec
```

- **Eval again →** clean: `(sum [1 2 3 4]) ;=> 10`. Loop terminates.
- **AR / no-retrieval contrast:** without the program graph, NEITHER entropy
  (confident) NOR re-noise alone (the model re-commits the same confident wrong
  name from the same context) fixes it — the spec injection is what changes the
  conditioning. Run the same canvas through (a) re-noise WITHOUT injection
  (expect: re-commits `reduce-kv` or another plausible-wrong name) vs (b) re-noise
  WITH the injected spec (expect: `reduce`). The delta IS the capability's value.

### Secondary case (Trigger A — entropy-gated, no graph miss yet)

A genuinely uncertain symbol region (the model hesitates between two plausible
verbs) — high entropy, no eval error yet. Confirms the entropy trigger surfaces a
span that retrieval can *pre-empt* before it commits wrong. This case proves
Trigger A end-to-end; the primary case proves Trigger B (the gap-closer).

## Run order on first deploy

1. `python gpu_worker_retrieval.py introspect` → resolve W1–W3 off the live A100;
   confirm J's U1–U4 and L's V1–V3 carry over. Read the cross-attn mask
   before/after a synthetic encoder-cache extend (W2 — the silent-no-op guard).
2. **Prove Part 1 alone, no GPU:** with `SEON_EMBED` set on the pod, call the
   proposed `spec-for-span` (or inline `embed/search-pull` with `:where [[?e
   :seon.fn/source]]`) on a canned `span_context` → confirm it returns real
   `:seon.fn/sym` specs, distance-ascending. **This is verifiable TODAY** (the
   retrieval seam is live infra) — do it before the GPU is even up.
3. Wire `inject` to whichever encoder-extend route W1 revealed (Route INCREMENTAL
   if exposed, else Route RE-PREFILL) at the marked CONFIRM-ON-DEPLOY spots.
4. `generate_canvas` the primary prompt → if it denoises clean, inject the
   `reduce-kv` corruption to exercise the loop deterministically. Confirm the
   `entropy[256]` + `committed_symbols` come back.
5. Eval in the SCI cage (pod side) → flag `reduce-kv` (Trigger B) → `spec-for-
   span` → `inject` → confirm the span re-commits to `reduce` and all other
   positions are byte-identical (L's clamp held).
6. Eval again → clean. Record rounds-to-clean. Run the no-injection re-noise
   contrast (step "AR / no-retrieval contrast"). Run the Trigger-A secondary case.
7. Decision gate: if injection does NOT change the re-committed name vs re-noise
   without injection on the wrong-name case, the cross-attention conditioning is
   too weak to steer commits — reassess (per the T-ladder gates in
   [[../../agent-fsm/research/diffusion-llm-test-plan-2026-06-27]]). If it DOES,
   Capability #3 closes the AUROC-0.471 gap empirically.

## Honesty / limits

- **Part 1 (the Seon retrieval seam) is real, live infra** — `embed/search-pull`,
  `knn-search`, the Proximum HNSW fn index, and the Vertex embed all exist and are
  exercised in production today (gated by `SEON_EMBED`). The ONLY new Seon code is
  `spec-for-span` (a thin composition) + the fn-scoped `:where` + the
  membership check — all grounded against exact existing fns above. **This half is
  verifiable now, with no GPU** (run order step 2).
- **Part 2 (the GPU encoder-KV injection) is confirm-on-deploy.** W1–W3 (KV
  extensibility, cross-attn mask widening, mid-denoise inject reachability) are
  reflected, not invented; the worker withholds the guessed inject call and ships
  Route RE-PREFILL as the always-correct fallback. No DiffusionGemma output has
  been produced yet (blocked on the custom torch image — see [[index]]).
- **The two-trigger design is the honest reading of the AUROC split**, not a
  hedge: entropy (AUROC 0.749) and program-graph membership (the AUROC-0.471 band)
  are complementary by the paper's own measurement. A single entropy trigger would
  miss the wrong-name class the capability exists to catch — so Trigger B
  (membership) is primary.
- **Retrieval is bounded by index coverage.** A correct name absent from the
  program graph (`:seon.fn/sym`) cannot be retrieved — the oracle can only steer
  toward names that EXIST. This is the right bound for Seon (the agent should use
  fns that exist), but it means a fresh/empty store retrieves nothing (fail-soft →
  `spec_text ""` → no-op). The `my.kb` template shows a consumer can widen the
  index to non-fn kinds, but the default scope is functions.
- **Reachability:** the pod is loopback, so the **round-trip (Seon drives)** is the
  deployable architecture; the literal per-step inline call needs a public Seon
  endpoint and is a later optimization (gated on W3 + a tunnel), explicitly out of
  scope here.
- **Cases are small, single-form, single-wrong-symbol** — chosen because that is
  exactly where the program graph is unambiguously the only oracle that helps.
  They are a clean proxy for retrieval-augmented repair, not a general benchmark.
- This plan is to EXECUTE fast once the GPU is live, not a result.
</content>
</invoke>
