---
type: prd
status: active
tags: [prd, agent, architecture]
---

# Context blocks — contract package (2026-07-28)

The seal-ready contract for the context-blocks rung. It implements
[../research/context-blocks-plan-2026-07-28.md](../research/context-blocks-plan-2026-07-28.md)
under the owner rulings of 2026-07-28 (plan/README.md, "Rulings 2026-07-28"),
composes with the landed router (`src/seon/render.clj`) and block family
(`src/seon/render/block.clj`), and follows the presence doctrine
(`../research/state-without-kinds-2026-07-28.md`). Where the plan's
sealed-suite sketch and the rulings conflict, the rulings win; every such
supersession is listed in §8. This document sequences nothing —
`plan/README.md` remains the only ordering.

The contract to close, restated from the plan:

```text
one immutable database value + one agent + one effective cap set
  + declared trusted runtime inputs
  → one ordered block derivation
  → one router request per declared output kind
  → :seon.render/ai contributions folded into the prompt
  → :seon.render/html contributions placed on the root or agent page
```

The prompt formatter is a render-unit application, never a parallel system
(simplification catalog group 1.1). `seon.cluster.prompt` keeps only block
selection, AI-contribution validation, ordered reduction, and the returned
rendered-context value; every prose piece it owns today becomes a named block.

## 1. The four rulings as contract law

1. **Omission = nil-punning.** nil and absent unify under `get`. A projection
   with nothing to say returns nil (or omits the key on a map result);
   consumers ALWAYS read render keys with `get`, never `contains?`.
   `[:maybe]` is allowed in in-memory function RETURN contracts; stored
   attributes stay nil-free (the bridge forces absence there). An omitted
   block keeps its identified wrapper element so its morph target survives.
2. **ONE computed projection-invocation seam in the one router.** Compiled
   core Var or N5-acquired SCI Var, computed from program-graph provenance,
   same result union, same admission owner. The seam's N5 half is a NAMED
   DEPENDENCY sealed jointly with the N5 evaluator owner (§10); this contract
   fixes only the seam's router-side shape.
3. **Installed/derived name collisions REFUSE loudly naming both sources.**
   No precedence, no silent merge.
4. **Exact-prompt capture commits BEFORE the provider call.** Prompt blob +
   rendered database basis + ordered contribution records + trusted-input
   snapshot, in one turn-owned pre-provider transaction; large text
   blob-backed (see §9 for the blob-archive dependency).

One distinction ruling 1 does NOT dissolve: the landed
"presence decides placement" filter in `seon.render.block/surfaces` tests
whether a block can render a kind at all — a property of the stored block,
not a render result. Nil-punning governs reading render RESULTS
(`:seon.render/output`, contribution text), never declaration presence.
**Seal correction (orchestrator, 2026-07-28):** that placement test is
`(render/declaration? (get block kind))`, NOT `contains?` — the router's
one rule applied at the selector. On a pulled durable entity the two are
equivalent (the bridge forces absence), but in-memory-constructed units can
carry nil, and `contains?` there is the exact defect filed in
`a-nil-render-key-paints-an-error-card-instead-of-omitting.md` (the
render-nil-unification lane is landing the `declaration?` form). The
carve-out's own worry — nobody may make a stored nil meaningful — is
enforced by `declaration?` too, since nil fails it.

## 2. Schema additions — exact EDN

Three files under `src/seon/schema/`: one new (`context.edn`), two revised in
place (`prompt.edn` wholesale, `block.edn` accretively). The loader is one
global population; no key is registered twice.

### 2.1 `src/seon/schema/context.edn` — NEW

```edn
{; ---------------------------------------------------------------------------
 ; CONTEXT CAPTURE — what was sent, durable BEFORE the provider call.
 ;
 ; Owner ruling 2026-07-28 (Decision 4, Option A): the turn owner commits
 ; the exact prompt text, the rendered database basis, the ordered
 ; contribution records, and the trusted-input snapshot in ONE
 ; transaction before the unobservable remote call. Writer ordering then
 ; guarantees: no capture → the prompt was never derived; capture with no
 ; attempt row → the call may never have fired — the receipt-before-
 ; dispatch pattern applied to context evidence.
 ; ---------------------------------------------------------------------------

 ; Identity is DERIVED from provenance, never allocated:
 ; "<run-id>-context-<basis-t>". Re-deriving the same prompt at the same
 ; basis upserts the same capture; a released run re-entering :call at a
 ; new basis creates a new capture, which is the honest record — the
 ; prompt genuinely differs.
 :seon.context.capture/id [:string {:min 1 :seon.db/identity true}]
 :seon.context.capture/run :seon.db/ref

 ; The rendered database basis transaction (:t of the database value
 ; every projection read). The branch is the cluster's own and the
 ; prompt path never renders as-of/history values, so :t identifies the
 ; value exactly (the exact-database-identity oracle still generates the
 ; other axes to prove no cross-identity reuse — in memory, not here).
 :seon.context.capture/basis-t [:int {:min 0}]

 ; THE BYTE GROUND TRUTH — the exact string handed to :seon.ai/prompt.
 ; Ruled blob-backed; the blob archive is quarry order Q1 and does not
 ; exist in the fresh tree yet, so this ships as a string attribute with
 ; the blob-ref cutover named as an accretion at that rung (§9 of the
 ; owning contract package). Never admitted/capped: capping the capture
 ; would make the evidence lie about what was sent.
 :seon.context.capture/prompt [:string {:min 1}]

 ; The one honest non-database input, recorded so the projection is
 ; reproducible. Present exactly when the membership required it —
 ; absence means no block needed liveness, never "assumed alive".
 :seon.cluster.run/live-processes [:set :seon.cluster.run/process]

 ; Ordered by each child's own position (L13: order is never a
 ; collection-type property; component children carry ordinals).
 :seon.context.capture/contributions [:set {:seon.db/component true} :seon.db/ref]

 :seon.context.capture/capture
 [:map {:seon.db/entity true}
  [:seon.context.capture/id :seon.context.capture/id]
  [:seon.context.capture/run :seon.context.capture/run]
  [:seon.context.capture/basis-t :seon.context.capture/basis-t]
  [:seon.context.capture/prompt :seon.context.capture/prompt]
  [:seon.context.capture/contributions :seon.context.capture/contributions]
  [:seon.cluster.run/live-processes {:optional true}
   :seon.cluster.run/live-processes]]

 ; ---------------------------------------------------------------------------
 ; ONE CONTRIBUTION ROW — evidence, not content.
 ;
 ; The prompt blob is the byte ground truth; rows explain how it was
 ; built: name, hash, tokens, position, band, projection. NO stored
 ; :seon.render/kind — the 2026-07-28 presence ruling exempts kind as
 ; "a request argument, never stored", and on a prompt capture it is the
 ; constant :seon.render/ai anyway (derivable, so storing it would be a
 ; stored derivation). NO stored text — hash + position + the blob
 ; reconstruct it. A FAILED contribution is presence of the error keys.
 ; An OMITTED block writes no row: its omission is re-derivable from the
 ; captured basis and membership (derive, don't store).
 ; ---------------------------------------------------------------------------

 ; "<capture-id>-<position>" — provenance-derived, upsert-idempotent.
 :seon.context.contribution/id [:string {:min 1 :seon.db/identity true}]
 :seon.context.contribution/position [:int {:min 0}]
 ; SHA-256 hex of the contribution's exact UTF-8 text (schema/sha-256 —
 ; the one digest owner; the queued schema/digest helper lands here).
 :seon.context.contribution/hash [:string {:min 1}]
 ; ESTIMATED tokens (house rule: human-visible sizes are estimated
 ; tokens, never raw characters). The estimator is a named adopt (§9).
 :seon.context.contribution/tokens [:int {:min 0}]
 ; The semantic cache band the ordering acted on. Fixed set, bands never
 ; cross; within a band, priority is a prior and name breaks ties.
 :seon.context.contribution/band
 [:enum :anchor :program :authored :continuity :dynamic]
 ; The bounded message of the flat error value, when the block failed.
 ; The full flat value stays in memory on the rendered-context record;
 ; the durable row keeps the open :seon.error/kind (already registered,
 ; error.edn) plus this bounded message — never a second error shape.
 :seon.context.contribution/error [:string {:min 1}]

 :seon.context.contribution/contribution
 [:map {:seon.db/entity true}
  [:seon.context.contribution/id :seon.context.contribution/id]
  [:seon.context.contribution/position :seon.context.contribution/position]
  [:seon.block/name :seon.block/name]
  [:seon.context.contribution/hash :seon.context.contribution/hash]
  [:seon.context.contribution/tokens :seon.context.contribution/tokens]
  [:seon.context.contribution/band :seon.context.contribution/band]
  [:seon.render/projection {:optional true} :seon.render/projection]
  [:seon.error/kind {:optional true} :seon.error/kind]
  [:seon.context.contribution/error {:optional true}
   :seon.context.contribution/error]]

 ; ---------------------------------------------------------------------------
 ; IN-MEMORY SHAPES — never transacted, so [:maybe]/nil are legal here.
 ; ---------------------------------------------------------------------------

 ; One in-memory contribution record. Text is ALWAYS present — a failed
 ; block contributes a bounded, block-named error statement rather than
 ; silence (silent omission is confabulation fuel). :seon.error/value
 ; presence IS "failed"; :seon.render/kind rides here (request-argument
 ; territory) even though the durable row drops it.
 :seon.context/contribution
 [:map {:closed true}
  [:seon.block/name :seon.block/name]
  [:seon.render/kind :seon.render/kind]
  [:seon.context.contribution/position :seon.context.contribution/position]
  [:seon.context.contribution/text [:string {:min 1}]]
  [:seon.context.contribution/hash :seon.context.contribution/hash]
  [:seon.context.contribution/tokens :seon.context.contribution/tokens]
  [:seon.context.contribution/band :seon.context.contribution/band]
  [:seon.render/projection {:optional true} :seon.render/projection]
  [:seon.error/value {:optional true} :seon.error/value]]

 :seon.context/contributions [:vector :seon.context/contribution]

 ; What capture-tx receives: the rendered context plus the run it is
 ; evidence for. Pure in, tx-data out; the LOOP commits.
 :seon.context/capture-request
 [:map {:closed true}
  [:seon.cluster.run/id :seon.cluster.run/id]
  [:seon.cluster.prompt/rendered-context :seon.cluster.prompt/rendered-context]
  [:seon.cluster.run/live-processes {:optional true}
   :seon.cluster.run/live-processes]]}
```

### 2.2 `src/seon/schema/prompt.edn` — REVISED (whole file)

```edn
{; The derived prompt: a pure function of a database value, assembled
 ; from the agent's blocks through the ONE router. The request names the
 ; HELD RUN — never a message id: the run's creating transaction records
 ; its exact trigger as :seon.db/trigger tx-meta, and
 ; seon.cluster.message/trigger reads it back, so a later queued message
 ; can never replace the recorded cause (review finding 6, repaired).
 :seon.cluster.prompt/request
 [:map {:closed true}
  [:seon.cluster.run/id :seon.cluster.run/id]
  [:seon.cluster.agent/id :seon.cluster.agent/id]
  [:seon.sci.admit/caps :seon.sci.admit/caps]
  [:seon.cluster.run/live-processes {:optional true}
   :seon.cluster.run/live-processes]]

 :seon.cluster.prompt/text [:string {:min 1}]

 ; The rendered context: the exact text the loop sends, the ordered
 ; contribution records every other consumer (capture, debug, token
 ; accounting, cache measurement) reads WITHOUT rerunning a projection,
 ; and the exact database value it was derived at. In-memory only.
 :seon.cluster.prompt/rendered-context
 [:map {:closed true}
  [:seon.cluster.prompt/text :seon.cluster.prompt/text]
  [:seon.context/contributions :seon.context/contributions]
  [:seon.db/db :any]]}
```

### 2.3 `src/seon/schema/block.edn` — ACCRETIONS (three keys, two entity slots)

```edn
 ; The semantic cache band, AUTHORED like priority: an installer's
 ; ordering decision, not a derived classification (no name→band table
 ; anywhere — R34). Absent = :dynamic, the free tail, so most blocks
 ; declare nothing. Ordering is (band ordinal, priority, name); the band
 ; order is the enum's own order and bands never cross.
 :seon.block/band [:enum :anchor :program :authored :continuity :dynamic]

 ; DECLARED trusted runtime inputs — the request keys this block's
 ; projections read beyond the database value. The interface expresses
 ; its dependency (the timeouts-ruling corollary): the request builder
 ; refuses AT CONSTRUCTION when a membership needs an absent input,
 ; before any projection runs, instead of every consumer growing its own
 ; fence card. Post-N5 this becomes derivable from the program graph;
 ; the attribute stays as the declaration the derivation must match.
 :seon.context/inputs [:set :qualified-keyword]
```

and `:seon.block/block` gains two optional entries:

```edn
  [:seon.block/band {:optional true} :seon.block/band]
  [:seon.context/inputs {:optional true} :seon.context/inputs]
```

Also revised in `block.edn` (seal revisions to the landed package-1 shapes,
each named in §8):

```edn
 ; caps + trusted inputs now ride the request — the ONE builder threads
 ; them into every projection (review finding 3).
 :seon.render/surfaces-request
 [:map {:closed true}
  [:seon.cluster.agent/id :seon.cluster.agent/id]
  [:seon.render/kind :seon.render/kind]
  [:seon.sci.admit/caps :seon.sci.admit/caps]
  [:seon.cluster.run/live-processes {:optional true}
   :seon.cluster.run/live-processes]]

 :seon.render/page-request
 [:map {:closed true}
  [:seon.cluster.agent/id :seon.cluster.agent/id]
  [:seon.sci.admit/caps :seon.sci.admit/caps]
  [:seon.cluster.run/live-processes {:optional true}
   :seon.cluster.run/live-processes]]

 ; the unit builder's input — one shape for prompt, page, debug, capture
 :seon.render/unit-request
 [:map {:closed true}
  [:seon.db/db :any]
  [:seon.cluster.agent/id :seon.cluster.agent/id]
  [:seon.sci.admit/caps :seon.sci.admit/caps]
  [:seon.cluster.run/live-processes {:optional true}
   :seon.cluster.run/live-processes]]
```

`:seon.render/surface` (the union of two closed maps) is UNCHANGED in shape:
`:seon.render/output` is `:any`, so a nil output already validates — omission
needs no third arm (§8, supersession 1). `render.edn` is untouched.

## 3. Function contracts

### 3.1 `seon.render.block/unit` — the ONE request/unit builder (revised)

```clojure
(defn unit
  "The unit seon.render/render receives for one block: the block's own
  map plus the request's database value, agent id, caps, and — when
  supplied — the live-process snapshot. ONE builder for prompt, page,
  debug and capture, so every projection, AI admission, generic panel
  and bounded expansion sees the SAME caps and the SAME snapshot."
  {:malli/schema [:=> [:cat :seon.render/unit-request :seon.block/block]
                  :seon.render/unit]}
  [request block])
```

Supersedes the landed two-input `unit` (db + agent-id only); one signature,
no compatibility arity.

### 3.2 `seon.render.block/membership` — the census/selector (new)

```clojure
(defn membership
  "The agent's ordered candidate blocks at db: installed component
  children plus, post-N5, derived current-namespace renderers (one
  ordinary block shape per render-capable :seon.fn row; derived name =
  the qualified keyword of the function symbol). THE ONLY JOIN. A
  derived name colliding with an installed name REFUSES loudly naming
  both sources (ruling 3) — never precedence, never a merge. Order is
  (band ordinal, priority, name); absent band sorts :dynamic. Pre-N5
  the derived side is empty by construction and this equals blocks."
  {:malli/schema [:=> [:cat :any :seon.cluster.agent/id]
                  [:vector :seon.block/block]]}
  [db agent-id])
```

Refusal shape (the house `refuse!` idiom, deepest non-empty `ex-data`):

```clojure
{:seon.error/kind :seon.render.block/refused
 :seon.render.block/rule :seon.render.block/name-collision
 :seon.block/name <the colliding name>
 :seon.render.block/installed <the installed block's projection symbols>
 :seon.render.block/derived <the derived function symbol>}
```

A membership census transacts nothing: derived candidates are values, never
stored descriptors (the suite proves this with a datom census).

### 3.3 `seon.render.block/required-inputs` + construction refusal (new)

```clojure
(defn required-inputs
  "The union of :seon.context/inputs over a membership. What the request
  must carry before any projection runs."
  {:malli/schema [:=> [:cat [:vector :seon.block/block]]
                  [:set :qualified-keyword]]}
  [membership])
```

`surfaces`, `page`, and `prompt` refuse at request construction — rule
`:seon.render.block/missing-input`, naming the block(s) and the absent
key(s) — when `required-inputs` names a key the request omits. Never `#{}`,
never "assume alive". The landed per-consumer fence cards (`data-panel`'s
caps card, `seon.problems/block`'s liveness card) remain as last-resort
fences only.

### 3.4 `seon.cluster.prompt/prompt` — revised (the N3 seal revision)

```clojure
(defn prompt
  "The rendered context for the agent holding run-id, derived from db.
  Selection → one router request per AI-declaring block in membership
  order → validation (string | nil | flat error) → ordered reduction.

  - The trigger is the HELD RUN's recorded cause: message/trigger reads
    the run's creating transaction's :seon.db/trigger. Refuses
    ::no-trigger when that transaction names none — a prompt with
    nothing to answer is a caller bug, not an agent outcome.
  - A string contribution is admitted against the request's caps (the
    one bound; a projection cannot flood the prompt).
  - nil contributes no text and no record (ruling 1).
  - A flat error value contributes a bounded, block-named statement —
    the agent is told its context is incomplete — and the record
    carries the flat value.
  Returns {text, contributions, db}. The text is EXACTLY the reduction
  of the contribution texts joined by \"\\n\\n\"; no consumer reruns a
  projection to reconstruct metadata."
  {:malli/schema [:=> [:cat :any :seon.cluster.prompt/request]
                  :seon.cluster.prompt/rendered-context]}
  [db request])
```

Owns NO situation prose. The refusal keeps today's shape:
`{:seon.error/kind :seon.cluster.prompt/refused
:seon.cluster.prompt/rule :seon.cluster.prompt/no-trigger}` in the deepest
non-empty `ex-data`, database unchanged.

### 3.5 `seon.context` — the compiled core projections (new namespace)

One namespace for the pre-N5 core AI projections; each is a pure function of
its unit, returns `[:maybe :string]` (in-memory return, ruling 1), and owns
both the facts-query and the guidance (the colocation rule). The stored block
symbol is the authority; a projection may later move beside its facts owner
(`seon.problems` already models that) with only a data edit.

```clojure
(defn identity-ai   {:malli/schema [:=> [:cat :seon.render/unit] [:maybe :string]]} [unit])
(defn peers-ai      {:malli/schema [:=> [:cat :seon.render/unit] [:maybe :string]]} [unit])
(defn trigger-ai    {:malli/schema [:=> [:cat :seon.render/unit] [:maybe :string]]} [unit])
(defn interruption-ai {:malli/schema [:=> [:cat :seon.render/unit] [:maybe :string]]} [unit])
(defn continuity-ai {:malli/schema [:=> [:cat :seon.render/unit] [:maybe :string]]} [unit])
(defn execution-ai  {:malli/schema [:=> [:cat :seon.render/unit] [:maybe :string]]} [unit])
```

Content contracts (each moves the corresponding `seon.cluster.prompt` helper;
no invariant is lost):

| block | facts read | present exactly when | source moved |
|---|---|---|---|
| `:identity` | agent id (+ `sci.eval/agent-namespace` derivation) | always | prompt.cljc:247-249 |
| `:peers` | agent rows minus self | another agent exists; nil when alone | prompt.cljc:264-274 |
| `:trigger` | held run → `message/trigger` → content + sender at the same db value; unit carries `:seon.cluster.run/id` | always (the refusal fired earlier if none) | prompt.cljc:73-95, 277-279 |
| `:interruption` | previous run (excluding the held run), forms, receipts, run error — PRESENCE reads: `result-edn`/`error` presence, `interrupted-at` (the sealed N3 status collapse, a named dependency §10) | a prior run was cut or failed; nil when clean | prompt.cljc:112-191 |
| `:continuity` | previous run's terminal `result-edn` as a `my.run/wait` value | the agent paused itself; nil otherwise | prompt.cljc:193-219 |
| `:execution` | reply-evaluation config + available dispositions | always (only the applicable grammar) | prompt.cljc:280-285 |

`:problems` keeps its landed owner (`seon.problems/block` + projections) and
enters core membership only when the N4 process owner supplies the captured
live-process seam (named dependency §10); `:fleet` is root-installed data over
the same mechanism. The unit for `:trigger` must carry the held run id: the
prompt path assocs `:seon.cluster.run/id` onto the unit-request — one more
qualified key on the open unit map, read by `get`.

Intended seed membership (owned by the seed-and-membership package, recorded
here so bands/priorities are contract, not folklore):

| name | band | priority | ai | html |
|---|---|---|---|---|
| `:identity` | `:anchor` | 0 | `seon.context/identity-ai` | — |
| `:execution` | `:anchor` | 10 | `seon.context/execution-ai` | — |
| `:peers` | `:anchor` | 20 | `seon.context/peers-ai` | — |
| `:interruption` | `:continuity` | 30 | `seon.context/interruption-ai` | — |
| `:continuity` | `:continuity` | 40 | `seon.context/continuity-ai` | — |
| `:trigger` | `:dynamic` | 90 | `seon.context/trigger-ai` | — |

Root additionally installs `:problems` and `:fleet`; an ordinary agent does
not. HTML twins accrete per block with zero mechanism change (presence decides
placement).

### 3.6 `seon.context/capture-tx` — the pre-provider capture (new)

```clojure
(defn capture-tx
  "Transaction data for one context capture. PURE — the loop commits.
  Derives the capture id from (run-id, basis-t of the rendered db
  value), one component contribution row per in-memory record carrying
  position/name/hash/tokens/band/projection (+ error kind and bounded
  message when failed), the exact prompt text, and the sorted
  live-process snapshot when the request carried one. Idempotent by
  derived identity: re-deriving the same prompt at the same basis
  upserts, never double-writes."
  {:malli/schema [:=> [:cat :seon.context/capture-request] [:vector :any]]}
  [request])
```

### 3.7 `seon.cluster.loop` — the `:call` branch revision

Three edits, one owner, atomic with the prompt seal:

1. **Trigger repair** (confirmed defect, plan review finding 6): delete the
   `work/unanswered-triggers` re-ask at `loop.cljc:649-654`; the prompt
   request names the held `:seon.cluster.run/id` and nothing else selects a
   trigger.
2. **Capture before the provider**: after `prompt` returns and BEFORE
   `ai/complete`, the loop commits `(capture-tx …)` through
   `store/transact!`. A refused capture ends the turn as an error value
   exactly as a refused plan freeze does — no provider call without its
   durable evidence.
3. **Exact-text handoff**: the loop extracts `:seon.cluster.prompt/text`
   from the rendered context and alone places that string in
   `:seon.ai/prompt`. Failover/backoff attempts inside the same pass reuse
   the ONE capture (same prompt bytes; the backup's system segment is the
   notice's `:seon.render/ai` over the already-committed primary error fact —
   re-derivable, not re-captured).

### 3.8 `seon.render.block/surface` — the omission revision

nil output is OMISSION, not `::not-hiccup` (supersedes the landed nil branch
at `block.clj:249-262`): a surface whose projection returned nil carries
name/id/kind with `:seon.render/output nil`, read by `get`. Its rendered form
is the identified wrapper element only — the same
`[:div {:id (surface-id name) :data-slot <name>} ""]` shape `slot` emits — so
the morph target survives and a later non-nil render morphs in place.
`::not-hiccup` still fires on every non-nil non-hiccup value.

### 3.9 The invocation seam — router-side shape only

`seon.render/render`'s symbol arm becomes one computed resolution:
program-graph provenance decides compiled-core (`requiring-resolve`, today's
behavior and the pre-N5 total behavior) versus N5-acquired SCI Var (invoked
through the guarded evaluator with the request's caps). Both return the same
result union (`:seon.render/rendered` | `:seon.error/value`); both pass the
one admission owner; a symbol neither side resolves is the existing
`::unresolvable`. The SCI half's contract — acquisition basis, ctx, interrupt,
admission placement — is authored WITH the N5 evaluator owner and is not
designed here.

## 4. Omission and error behavior — the properties

1. **Nil-punning omission.** For every projection P and unit U: P(U) = nil ⇒
   the block contributes no prompt text, no contribution record, and (HTML)
   exactly its identified empty wrapper element. `(get result k)` and key
   absence are indistinguishable to every consumer; no consumer calls
   `contains?` on a render result.
2. **Wrapper survival.** An omitted block's `surface-id` element is present
   in the page bytes; a subsequent non-nil render at a later basis morphs
   that element in place with no page-level change.
3. **Loud failure, never silent.** A failed AI block contributes a bounded
   statement naming the block; the prompt is never silently shorter because
   a system component broke. The nested error equals the closed
   `:seon.error/value` exactly — a contribution names its block but adds no
   key to that shape.
4. **Isolation.** A failed/unresolved/throwing block changes no neighbouring
   contribution's bytes and no neighbouring surface.
5. **Refusals are legible and write nothing.** `::no-trigger`,
   `::name-collision`, `::missing-input`: deepest non-empty `ex-data` names
   the rule and both sides; before/after sorted datoms are identical.
6. **Capture precedes the call.** No `:seon.ai/attempt` row exists whose run
   lacks a capture at the prompt's basis. A kill between capture and attempt
   leaves a capture with no attempt — evidence the call may never have
   fired, never a refire.
7. **Determinism.** Same database value + agent + caps + trusted-input
   snapshot ⇒ byte-identical text and an equal contribution vector.
8. **One admission.** Every AI contribution and every HTML expansion is
   bounded by the request's one cap set; no context-specific cap, second
   visited rule, or different elision order exists.
9. **Structural falsifier.** Replacing one installed block's projection
   symbol changes the next prompt contribution AND the next page surface
   through `seon.render/render`, with no edit to `seon.cluster.prompt`, a
   route, or a page consumer.

## 5. The pre-provider capture transaction — exact shape

One transaction, committed by the loop (turn-owned), before `ai/complete`:

```clojure
[{:seon.context.capture/id "run-7f21-context-4231"
  :seon.context.capture/run [:seon.cluster.run/id "run-7f21"]
  :seon.context.capture/basis-t 4231
  :seon.context.capture/prompt "<the exact bytes sent>"
  :seon.context.capture/live-processes #{"pid@start" …} ; only when supplied
  :seon.context.capture/contributions
  [{:seon.context.contribution/id "run-7f21-context-4231-0"
    :seon.context.contribution/position 0
    :seon.block/name :identity
    :seon.context.contribution/hash "…sha256…"
    :seon.context.contribution/tokens 23
    :seon.context.contribution/band :anchor
    :seon.render/projection seon.context/identity-ai}
   …
   ;; a failed block's row — presence of the error keys IS "failed"
   {:seon.context.contribution/id "run-7f21-context-4231-3"
    :seon.context.contribution/position 3
    :seon.block/name :problems
    :seon.context.contribution/hash "…"
    :seon.context.contribution/tokens 11
    :seon.context.contribution/band :dynamic
    :seon.render/projection seon.problems/ai
    :seon.error/kind :seon.render/projection-failed
    :seon.context.contribution/error "The seon.problems/ai projection threw: …"}]}]
```

Transaction provenance stays minimal (`:seon.db/user` + `:seon.db/process`);
the capture entity's ref to its run is the causation — no new tx-meta.

Crash walk: (a) kill before the capture commits — no capture, no attempt, the
run is a plain interrupted run; (b) kill between capture and the attempt row —
capture present, attempt absent: the call may never have fired, the derived
interruption warning says so, nothing refires; (c) kill after the attempt —
today's attempt-row story, unchanged. Nothing here re-executes anything.

## 6. Consumers of the one derivation

Prompt (`:seon.render/ai` pull), page (`:seon.render/html` push via N4),
debug, and capture all consume the same `membership` → `unit` → router
derivation at one database value, one cap set, one snapshot. The route (`/`,
`/agent/{id}`) chooses an agent; it never chooses a renderer; root is agent
`"root"` with different block data. No consumer calls a projection function
directly (graduation census).

## 7. The sealed suite

Every proof is a recurring `bin/test`-discovered JVM `deftest`. Fixed seeds;
all identities (database names, agent/message/run ids, instants, UUIDs,
ports, paths) derive from `(seed, trial-index)` through the shared helper; no
wall clock, `random-uuid`, or unordered iteration participates in an oracle.
Failure output prints seed, trial index, size, generated value, schema
explanation, derived identities, and the complete shrunk check. Oracles are
independent ledgers derived from planted facts — never the producer compared
with itself.

### 7.1 `test/seon/context_test.clj` — owned by THIS package

| deftest | seed | oracle |
|---|---|---|
| `context-determinism-property` | 2026072801 | same db value/agent/caps/snapshot twice → byte-identical text, equal contribution vectors; a planted fact change at a later basis changes exactly the relevant block's contribution |
| `placement-and-omission-property` | 2026072802 | generated AI-only/HTML-only/twin/clean-conditional declaration combinations; expected invocations derive from declaration-key presence on planted blocks; omission asserted as nil-under-`get` plus the surviving identified wrapper element (supersedes the sketch's union-arm assertion, §8) |
| `error-isolation-test` | 2026072803 | planted literal neighbours around unresolved/throwing/returned-flat-error projections; neighbour bytes equal planted literals; failed contribution names its block; nested error equals the closed `:seon.error/value` exactly |
| `prompt-reduction-ledger-property` | 2026072804 | a test ledger independently derives expected ordered names from planted membership/conditions and records every projection invocation/result; returned contributions AND exact text are compared with the ledger, never with each other |
| `scope-property` | 2026072806 | distinct tagged rows for agents A/B plus tagged shared rows; the oracle enumerates exact datom ids each projection may read — A omits B's, B omits A's, shared included; byte equality alone never passes |
| `membership-collision-property` | 2026072807 | installed rows plus generated derived-renderer rows; result is the exact expected sorted vector or the one ruled `::name-collision` refusal naming both sources; a datom census proves no derived descriptor was transacted |
| `caps-and-expansion-property` | 2026072808 | generated slot/ref graphs under the four explicit caps; a test-only depth-first left-to-right counter computes admitted positions and elision/cycle paths; results match the landed single-map `expand`, with the SAME caps bounding AI admission and generic panels |
| `held-run-trigger-test` | 2026072809 | run A opens on message A; message B arrives before A's `:call` pass; `message/trigger` independently establishes A; the ledger reads A/sender A, omits B; the captured prompt contains A's planted content |
| `missing-trigger-refusal-test` | 2026072809 | held run whose creating transaction has no trigger: deepest non-empty `ex-data` equals `:seon.cluster.prompt/no-trigger`; no partial rendered context; before/after sorted datoms identical |
| `missing-input-refusal-test` | 2026072809 | membership containing a block declaring `:seon.context/inputs`; request without the input refuses `::missing-input` before ANY projection runs (invocation ledger empty); with the input, results reproduce and the capture records the sorted snapshot |
| `capture-before-provider-test` | 2026072805 | a stub provider records call order; the capture transaction's datoms exist before the recorded call; capture id/contribution ids equal the derived `(run, basis-t, position)` identities; rows carry hash/tokens/position/band and NO stored kind and NO stored text; a failed row is error-key presence |
| `exact-database-identity-test` | 2026072801 | values differing separately in basis `:t`, `as-of`, `since`, `history`: the invocation ledger and returned `:seon.db/db` name the requested value; no result produced for one identity satisfies another |
| `colocation-test` | 2026072802 | empty then non-empty plan-style facts with no acknowledgement write; expected teaching/content alternative derives from planted state; sorted datom diff shows only domain facts changed |
| `root-agent-symmetry-test` | 2026072807 | root and ordinary-agent memberships with uniquely tagged rows route through the same functions; exact expected names derive from each planted membership, so equal omission cannot masquerade as symmetry |

Migration invariants carried into the revised `prompt_test.clj` +
`turn_test.clj` (revised atomically with the schema/function/loop seal, per
plan review finding 5) — none may be lost: identity sentence; peers omitted
when alone; peers include the exact send grammar with a real id; interrupted
warning planted/absent; before-plan interruption wording; run-error wording;
wait-continuity note; sender-aware vs sender-less trigger framing; execution
grammar; `no-trigger` refusal; the loop sends exactly the returned text.

### 7.2 Later-package suites (seeds reserved, named here so nothing drifts)

| deftest | seed | owner package |
|---|---|---|
| `coordination-property` (invalidate/begin/join/settle/newest-pending; one active evaluation per kind, independent AI/HTML slots, joins only; settled reuse measured, never assumed) | 2026072805 | N4 live composition |
| `completeness-scorer-property` (replay claims → test-owned claim-to-evidence ledger; the pure scorer returns it exactly) | 2026072810 | completeness audit |
| `test/seon/context_live_test.clj` (child JVM from the current `java.class.path`, project-local `tmp/context-live/<seed>/<trial>/`, `seon.cluster/start!` in the child, parent owns/stops in `finally`; current-ancestor boot → real HTTP/gzip SSE on `/` and `/agent/{id}` → 32-tab fan-out + one simultaneous AI request → kill mid-render → replace → reconnect repaint equals fresh derivation → post-N5 authored-twin step; event-driven readiness, clocks only as loud foreign-process backstops) | 2026072801 | N4 live composition + program-graph context |

## 8. Supersessions — where the rulings overrode the plan's sketch

1. **Omission is nil-punning, not a third router arm.** The plan recommended
   Decision 1 Option C (a closed omitted-success union alternative); the
   2026-07-28 ruling replaces it. `:seon.render/rendered` and
   `:seon.render/surface` change NOT AT ALL (`:seon.render/output` is `:any`;
   nil validates); the sketch's "omitted success is asserted as its one ruled
   union arm" becomes "asserted as nil under `get` plus the surviving
   identified wrapper element". `[:maybe]` in in-memory returns is settled
   ALLOWED (the plan had left it hanging on Option C).
2. **`surface`'s nil→`::not-hiccup` branch is revised** (landed package-1
   behavior, `block.clj:249-262`): nil is omission; only non-nil non-hiccup
   refuses. This is a deliberate seal revision of `fa4cb38f4`'s contract, and
   the placement/omission property replaces the old nil-refusal assertion.
3. **The durable contribution row stores no `:seon.render/kind`.** Decision
   4's field list names kind; presence ruling 2 states kind is "a request
   argument, never stored". The reconciliation: the in-memory record carries
   kind; the durable row omits it (constant `:seon.render/ai` on a prompt
   capture — a stored derivation). If capture ever covers a second kind, kind
   returns to the row as a then-genuine fact.
4. **Interruption/continuity projections are contracted against the presence
   model** (`result-edn`/`error` presence, `interrupted-at`), per ruling 2's
   sealed N3 revision deleting `:seon.cluster.eval/status` — not against the
   status enum the plan's citations (and today's `prompt.cljc`) still read.
   The status-collapse implementation is a named dependency (§10), and the
   projections land against whichever shape is current, with the presence
   reads as the sealed contract.
5. **`unit`, `surfaces-request`, `page-request` signatures revised** to carry
   caps + trusted inputs (the plan's own review finding 3, now contract).

## 9. Named adopts and deferred backings

- **Digest**: `schema/sha-256` is the one owner; the queued `schema/digest`
  (value → hex) helper lands with the capture implementation (simplification
  catalog 6.1).
- **Token estimator**: the fresh tree has none (`:seon.ai/tokens` is
  provider-reported completion tokens). Adopt/quarry one pure estimator
  (quarry: `src-old/seon/ai/` token estimate) as `seon.ai/estimate-tokens`;
  contribution rows use it. Small adopt, named in the core package.
- **Blob backing for the prompt text**: ruled, but the blob archive (quarry
  order Q1) does not exist in fresh `src/`. `:seon.context.capture/prompt`
  ships as a string attribute — measured acceptable (no hard datom size
  limit; ~2.2× index amplification, `laws.md`) — and the blob-ref cutover is
  an accretion at the archive rung. Flagged as review point 2.
- **`:seon.cluster.run/live-processes` registration** moves to one global
  key (context.edn); `problems.edn`'s inline `[:set :seon.cluster.run/process]`
  in `:seon.problems/request` is reconciled to reference it in the same
  commit.

## 10. Orchestrator review points

Judgment calls the seal should double-check:

1. **Kind dropped from the durable contribution row** (§8.3) — my
   reconciliation of ruling 2's "never stored" against Decision 4's field
   list. If the owner meant the durable row to carry kind literally, one
   optional entry restores it.
2. **Prompt text as a string attribute pending the blob archive** (§9) — the
   one place implementation precedes its ruled final form ("large text
   blob-backed"). Alternative: make the blob archive a blocking dependency of
   the capture package.
3. **`:seon.block/band` as AUTHORED block data** (absent = `:dynamic`) rather
   than a derived classification — chosen to avoid a name→band hand list
   (R34) while satisfying the ruled fixed-band capture field. It changes the
   landed `blocks` sort key from (priority, name) to (band, priority, name).
4. **`:seon.context/inputs` as a declared-requirements attribute** — the
   data-driven construction-time refusal (§3.3). Post-N5 the program graph
   can derive it; the attribute then becomes the declaration the derivation
   is checked against. Alternative rejected: a hand list in the builder.
5. **Capture identity `(run-id, basis-t)` and failover reuse** (§3.7, §5) —
   one capture per rendered context; the backup call reuses it and its system
   segment is re-derivable from the committed primary fact, not captured.
   Verify this matches the owner's reading of "before the provider call"
   (my reading: before the FIRST call of the pass; the pass's later calls
   send the same bytes plus a derivable segment).
6. **Omitted blocks write no contribution row** — omission is re-derivable
   from the captured basis + membership (derive-don't-store). If debug wants
   omission rows without re-derivation, that is a stored-derived trade the
   owner should make explicitly.
7. **The `contains?` carve-out for declaration presence** (§1, last
   paragraph) — placement selection on stored declaration keys stays
   `contains?`; nil-punning governs render results. Stated so nobody
   "fixes" `surfaces` into reading declarations with `get` and thereby makes
   a stored nil meaningful.
8. **Trigger unit carries the held run id** (§3.5) — one more qualified key
   on the open unit map rather than a trigger-only builder. Check against
   "the unit does not carry a page route/renderer result" intent.

Named N5 dependency edges (this contract does not design them):

1. **The invocation seam's SCI half** — acquisition at a basis, ctx/fork,
   `:interrupt-fn`, admission placement for N5-acquired projection Vars;
   sealed jointly with the N5 evaluator owner (§3.9).
2. **Derived current-namespace membership** — render-capable discovery over
   committed `:seon.fn`/`:seon.ns` facts feeding `membership`'s derived side
   (pre-N5: empty by construction).
3. **Advertised-symbol resolution census** — every projection symbol the
   program graph advertises resolves through the one seam (open issue:
   `program-graph-render-declarations-name-absent-functions`).
4. **Post-N5 proofs** — live-oracle step 5 (authored twin, restart,
   cross-agent visibility), the program-graph context suite, and the
   completeness audit (seed 2026072810).

Non-N5 named dependencies: the N4 process owner's captured live-process seam
(gates `:problems` in core membership); the N4 registration/SSE pipeline
(gates `context_live_test` steps 2–4 and seed 2026072805); the sealed N3
status-collapse implementation (§8.4); message lane `722adb18e` (landed).
