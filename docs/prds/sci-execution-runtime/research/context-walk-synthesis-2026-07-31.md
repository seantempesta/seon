---
type: research
status: active
tags: [research, context, render]
---

# Context-walk synthesis — what already exists on the owner's new framing

## Why this document

The owner has framed agent context assembly as: a simple recursive walk over
the agent's entity; everything rendered through `:seon.render/ai` (and
`:seon.render/html` for the web); all refs followed at a configurable depth;
membership DERIVED by the walk rather than hand-built blocks; priority
overrides for pinned/system content; ordering chosen for provider prompt-cache
stability (stable first, churn last); the agent's namespace reachable because
it is a ref; history as a REPL transcript.

Substantial parts of that are already ruled, already built, or already
falsified by committed experiments. This document is the evidence-first
reconciliation so the design session starts from what is known rather than
re-deriving it. Every claim carries a `file:line` or a verbatim quote.

## 1. What S0, S1, and S2 actually are

### S0 — baseline corpus (done)

`docs/prds/sci-execution-runtime/research/context-walk/s0-baseline/README.md`
captured the exact block-derived prompt bytes for `helper` and `root` across
three triggers on an isolated cluster. Verbatim method claim
(`s0-baseline/README.md:24-28`):

> For every provider invocation, the script queried and found the identical
> `:seon.context.capture/prompt` bytes already committed. Each `.prompt.txt`
> file is written without an added newline, so its bytes are the capture fact's
> exact string.

Sizes (`s0-baseline/README.md:36-43`, corroborated by
`s0-baseline/metrics.edn`):

| Case | Bytes | Estimated tokens |
|---|---:|---:|
| helper chat | 1,333 | 331 |
| helper routed problem | 2,145 | 533 |
| helper error wake | 3,332 | 829 |
| root, ALL THREE triggers | 164 | 41 |

The single most important S0 finding is root's flat 164 bytes
(`s0-baseline/README.md:52-56`):

> Root's captured prompt is only the fleet-oversight sentence. It changes the
> run ids and episode counts but remains 164 bytes for all three triggers. It
> does not include the chat request, routed problem, error fact, root identity,
> namespace, or execution grammar.

That is verified structurally in the fresh tree: root's seed block set declares
exactly one `:seon.render/ai` block, `:fleet-oversight` → `oversight/block-ai`
(`src/seon/render/root.clj:234-260`). Every other root block is HTML-only, so
the prompt path filters them out at `src/seon/cluster/prompt.cljc:183-185`.
**Root's context blindness is not a walk problem; it is a missing block, today,
in production.**

### S1 — shadow render (done, awaiting owner read)

`s1-shadow/README.md` derived walk-context for the same agents from the same
immutable database value (`d/as-of` at each capture's basis-t —
`s1-shadow/README.md:11-16`), rendered side by side, with no model calls.

Sizes (`s1-shadow/README.md:35-42`):

| Case | Block tokens | Walk tokens | Δ |
|---|---:|---:|---:|
| helper chat | 331 | 214 | −117 |
| root chat | 41 | 211 | +170 |
| helper routed problem | 533 | 253 | −280 |
| root routed problem | 41 | 249 | +208 |
| helper error wake | 829 | 306 | −523 |
| root error wake | 41 | 308 | +267 |

S1 explicitly refuses to read those reductions as a win
(`s1-shadow/README.md:44-46`):

> The helper reductions are not an end-state efficiency claim: S1 has no
> transcript, while the block prompt includes accumulated history. Conversely,
> root grows because the shadow finally includes what woke it.

What the walk showed that blocks did not (`s1-shadow/README.md:50-61`): root
sees its actual trigger; both agents see identity, namespace, trigger shape and
mode as ordinary data; a routed problem preserves sender/recipient/problem
identity/content together; the namespace section is reader-valid Clojure.

The stage verdict is not a graduation (`s1-shadow/README.md:126-129`):

> S1 is promising but not ready for S2 on this evidence alone. The trigger
> render is materially clearer, especially for root, but the code-graph absence,
> root oversight loss, and missing response grammar are substantive gaps for the
> owner review.

### S2 — scoped, queued, gated on the owner's S1 read

`plan/context-walk-experiment-protocol.md:37-41`:

> **S2 — one guinea-pig agent live.** A scratch-cluster agent takes real
> Ollama turns on walk-derived context; its block-context twin runs the same
> triggers. Compare behavior (task completion, tool-call sanity, confusion
> markers in replies). Exit: walk-context agent is not worse; defect list
> drives renderer iteration, not framework rework.

The protocol's standing constraints (`:61-69`): one stage at a time, the next
lane launches only after the owner reviews the previous stage's outputs, every
stage commits its script and outputs, renderer iteration happens INSIDE a stage
while framework rework between stages needs a recorded ruling, scratch clusters
only until S5. The protocol also states the deletion discipline up front
(`:13-15`): "Blocks remain the production path until a stage graduates past
them; on graduation the block system DELETES in the same commit (never two
standing paths)."

Later stages are already scoped and matter to the new framing: S3 is the
transcript joining the walk (`:43-47`), S4 is churn ordering plus digests with
**measured** prompt-cache hit rates (`:49-53`), S5 is graduation with the
owner's one-sentence/one-defn agent eval (`:55-59`).

Current status: `plan/unsettled.md:203-205` lists s0/s1 as "**await owner
read**"; `plan/unsettled.md:723` queues "Context-walk S2 (the live guinea-pig)
— unfenced now that the code graph exists; needs the owner's S1 read first."

## 2. What exists in fresh `src/` today — file:line inventory

### The prompt path

- `src/seon/cluster/prompt.cljc:133-208` — `prompt`: trigger check →
  `block/membership` → `block/assert-inputs!` → one router request per
  AI-declaring block → validation → ordered reduction. Text is
  `(str/join "\n\n" …)` of contribution texts (`:205-207`).
- `src/seon/cluster/prompt.cljc:75-127` — the three-valued contribution rule:
  string admitted against caps, nil is omission, flat error becomes a bounded
  block-named statement. Caps go through the ONE admission codec
  (`:62-73`), not a second size dial.
- `src/seon/context.clj:63-258` — the five surviving AI projections:
  `identity-ai`, `peers-ai`, `settlement-ai`, `assignment-ai`, `trigger-ai`,
  `execution-ai`. Each is pure over its unit and returns `[:maybe :string]`.
- `src/seon/context.clj:287-312` — `capture-tx`: pure tx-data for the
  pre-provider capture; identity is `<run-id>-context-<basis-t>`, so
  re-deriving upserts. Contribution rows carry position/name/hash/tokens/band/
  projection and error keys (`:264-285`).
- `src/seon/context.clj:323-335` — the two evidence derivations:
  `contribution-hash` (SHA-256) and `contribution-tokens`
  (`seon.ai.tokens/estimate`).

### Membership — still hand-installed blocks

- `src/seon/render/block.clj:205-243` — `blocks`: the agent's INSTALLED set,
  one pull, no merge, ordered by (band ordinal, priority, name).
- `src/seon/render/block.clj:245-261` — `derived`: **returns `[]` by
  construction.** Its docstring is explicit that post-N5 this becomes
  render-capable discovery over `:seon.fn`/`:seon.ns` facts, and that pre-N5
  "the derived side is EMPTY BY CONSTRUCTION — `[]`, not a stub that pretends."
- `src/seon/render/block.clj:269-297` — `membership` = installed ∪ derived,
  with a loud refusal on a name collision.
- `src/seon/render/block.clj:184-203` — ordering: band ordinal (computed from
  the registered enum's own member order), then priority, then name.
- `resources/seon/schema/block.edn:43-48` — the band enum
  `[:anchor :program :authored :continuity :dynamic]`, and the schema comment
  states bands are **AUTHORED, not derived**: "an installer's ordering
  decision, not a derived classification (no name→band table…)."
- `src/seon/render/agent.clj:440-499` — an ordinary agent's seed set: eleven
  blocks, of which five carry `:seon.render/ai` (`:identity`, `:execution`,
  `:peers`, `:settlement`, `:assignments`, `:namespace`, `:trigger` — seven,
  counting the walk block and trigger) and the rest are HTML.
- `src/seon/render/root.clj:234-260` — root's seed set: ONE AI block.

### The walk — built, and it is one block among many

- `src/seon/render/walk.clj:328-423` — `neighborhood`: `{lookup, projection,
  output, distance, neighbours}`; distance spent per connection (root at the
  requested distance, each neighbour one hop cheaper, `:403-415`); three
  bounds — hop budget, the caps' node budget
  (`:seon.config.eval.result/max-nodes`, `:358`), and a per-path visited set;
  every failure a flat `:seon.error/value` node (`:366-380`).
- `src/seon/render/walk.clj:289-315` — `refs`: forward refs then reverse refs,
  attribute-name ordered, deduplicated by target, apparatus excluded.
- `src/seon/render/walk.clj:253-287` — `apparatus?`: presence-derived exclusion
  of the view's own blocks (`:seon.render.block/name`) and transaction entities
  (`:db/txInstant`). Both were found by deriving and reading, not reasoning —
  the block-as-neighbour case produced "You are agent ." with no id (`:266`).
- `src/seon/render/walk.clj:154-179` + `src/seon/render.clj:124-171` — the
  four-step resolution chain: slot redirect → viewer override → owning
  namespace/family default → floor (`seon.render.value/render-ai`).
- `src/seon/render/walk.clj:429-463` — `prose`: the ai assembly. Depth is
  indentation; the connection attribute names the line introducing its node;
  nil-punning omission; an error node contributes its message.
- `src/seon/render/agent.clj:362-407` — `namespace-ai`, the pilot block: it
  reads `:seon.render/distance` off the unit and calls the walk rooted at
  `[:seon.cluster.agent/id agent-id]` with floor `block/data-prose`.
- `src/seon/render/block.clj:168-178` — `distance`: the ONE default site,
  implied 1.
- `src/seon/cluster/prompt.cljc:160-177` — `:seon.render/distance` is threaded
  onto the unit request exactly as caps are.

### The agent's namespace IS a ref — and what the walk finds there

- `resources/seon/schema/agent.edn:1-6` —
  `:seon.cluster.agent/namespace [:and {:seon.db/unique true} :seon.db/ref]`,
  cardinality one, unique-value.
- `src/seon/cluster/agent.clj:94-102` — formal creation commits the `:seon.ns`
  row and the agent row in one transaction, the agent's ref pointing at the
  namespace tempid.
- `src/seon/sci/eval.clj:191-198` — `agent-namespace` is the ONE derivation of
  `my.agents.<id>`, shared by prompt and evaluator.

So the walk does reach the namespace entity at distance ≥ 1. What it finds
there is thin, for two independently verified reasons:

1. **No family default renderer exists for `:seon.ns` or `:seon.fn`.** A grep
   of `resources/seon/schema/` for `:seon.render/ai` finds declarations only on
   `message.edn:38`, `error.edn:77`, `run.edn:17,59,66,86`, and the
   `problems`/`block`/`render` schema shapes. `program.edn` declares **none**.
   A walked namespace node therefore falls through the resolution chain to the
   generic floor.
2. **`:seon.ns/requires` is a set of SYMBOLS, not refs**
   (`resources/seon/schema/program.edn:42`:
   `:seon.ns/requires [:set :symbol]`). A ref-following walk cannot hop
   namespace → required namespace at all. `:seon.fn/ns` (`program.edn:5`) and
   `:seon.fn/calls` (`program.edn:11`) ARE refs, so function→namespace and
   function→function hops exist; namespace→namespace does not.

### Transcript — HTML only; the AI prompt has no history

- `src/seon/render/agent.clj:227-250` — `transcript-html`, and the seed block
  `:transcript` at `agent.clj:484-487` declares **only** `:seon.render/html`.
- A repository-wide grep for `transcript` under `src/` returns hits only in
  `render/agent.clj`, `render/block.clj` (docstrings about morph cost),
  `render/root.clj` (a docstring), and `problems.clj:342` (a docstring). There
  is no AI transcript projection anywhere in fresh `src/`.

### Runtime publication gates what the corpus can ever show

`src/seon/sci/eval.clj:320-345` — `program-row` publishes a function row only
through `program/declaration-row event :contracted`; the docstring is explicit:
"A function without its complete contract is deliberately absent." An agent
writing an ordinary uncontracted `defn` therefore produces **no** `:seon.fn`
row, so a namespace-render walk sees nothing of it.

### Cache ordering — captured, but not acted on

The contribution row stores `:seon.context.contribution/band`
(`resources/seon/schema/context.edn:78-81`), and the prompt reduction orders by
(band, priority, name) — a static authored convention. There is **no** cache
boundary marker emitted, and **no** churn or change-timestamp ordering. The
prompt is one flat `"\n\n"` join (`prompt.cljc:205-207`).

## 3. Delta: the owner's new framing vs. what is ruled and built

### Where the new framing AGREES with existing rulings and code

| New framing point | Already ruled | Already built |
|---|---|---|
| Recursive walk over the agent's entity | README:556-571 ("Namespace and distance centric context for agents"), README:819-827 ruling 13(b) | `render/walk.clj:328-423`; used by `render/agent.clj:362-407` |
| Render everything via `:seon.render/ai` + `:seon.render/html` | README:210-217 (the two-projection render contract) | `src/seon/render.clj:173-222` (one router, late `requiring-resolve`) |
| Follow refs at configurable depth | README:536-541 (distance is a request parameter, never a new noun) | `block.clj:168-178`; spent per hop in `walk.clj:403-415` |
| Membership derived by the walk | README:522-535 (membership derives from the namespace) | Partially: `refs`+`apparatus?` derive the walk's own membership; the OUTER membership is still installed blocks |
| Priority pins for system content | `block.edn:43-48` (bands are authored ordering decisions) | `block.clj:184-203`; captured on every contribution |
| Cache-stable ordering, transcript last | README:700-706 (verbatim: "context can be rendered in parallel and then sorted by change timestamp… the parts that change more flow to the end") | **Not built** |
| History as a REPL transcript | README:693-699 ("The agent's context is mostly its transcript") | **Not built for AI** |
| Namespace is a ref, so the walk reaches it | README:632-641 (agent birth seeded with the distance namespace view) | Ref exists (`agent.edn:1-6`); the namespace's renderer does not |

### Where the new framing goes FURTHER than what exists

1. **Deleting block membership entirely.** Today the walk is a tenant of the
   block system: `namespace-ai` is one of eleven seeded blocks
   (`render/agent.clj:492-495`) and `block/derived` returns `[]`
   (`block.clj:245-261`). The owner's framing inverts that — the walk becomes
   the membership. The protocol already names the consequence
   (`context-walk-experiment-protocol.md:13-15`): on graduation the block
   system deletes in the same commit; scaffold blocks become walk roots.
2. **The namespace as a first-class rendered type.** The ruled default —
   "distance 1 = signatures+docstrings, deeper = bodies, 0 = the name"
   (README:580-586) — has **no implementation**. `program.edn` declares no
   `:seon.render/ai` for `:seon.ns` or `:seon.fn`, so namespaces render through
   the generic value floor. This is the single largest build gap under the new
   framing, and the quarry already characterizes exactly what to reimplement
   (`old-context-assembly-2026-07-29.md:364-448`: signature/doc/body gradient,
   "real source once, derived member cards only when source is absent", missing
   and empty namespaces stay visible, targeted member drill).
3. **Following requires as part of the walk.** `:seon.ns/requires` is a symbol
   set (`program.edn:42`), not a ref set — the walk structurally cannot hop it.
   Either the schema changes to refs or the namespace renderer resolves symbols
   itself. The quarry names this exact defect in the old system
   (`old-context-assembly-2026-07-29.md:444-448`): "The defect was that it
   parsed requires again from source and used mutable atoms for an
   invocation-local walk. The replacement should walk persisted code-graph
   edges with an immutable accumulator." The fresh tree has the persisted
   edges' *shape* but not their ref-ness.
4. **History as a walk product.** README ruling 13(c) sanctions the dream
   (README:828-831): "skip the block system entirely — everything derived from
   DEFAULT RENDER FUNCTIONS ATTACHED TO SCHEMAS AS METADATA, and even the
   TRANSCRIPT built from walking the agent's entity." Nothing of that
   transcript exists for AI. S3 owns it (`protocol:43-47`), and the aging
   policy is fully quarried but unbuilt
   (`transcript-aging-quarry-2026-07-29.md:84-140`).
5. **Ordering by change timestamp.** Ruled (README:700-706), captured as a
   band fact, but not derived anywhere. The old system had the same hole and it
   was filed as a defect: "static priorities required a human to predict future
   volatility, while no block changelog fed ordering"
   (`old-context-assembly-2026-07-29.md:697-700`, citing
   `docs/seon/issues/archive/context-block-order-is-static.md:12-35`).
6. **A whole-prompt budget.** Neither the old system nor the fresh one has one.
   Old: "There was no global total prompt cap in this owner"
   (`old-context-assembly-2026-07-29.md:196-198`). Fresh: caps are per
   contribution via the admission codec (`prompt.cljc:62-73`) and per walk-node
   count (`walk.clj:358`); the sum is emergent.

### Where prior research already FALSIFIED part of the framing

1. **"The walk replaces the blocks" is falsified as a same-day claim.** S1's
   own loss list (`s1-shadow/README.md:66-70`):

   > Helper loses prior paused-run notes, settlement, older messages, and error
   > neighbourhood facts. S3 is explicitly responsible for the transcript; S1
   > must not pretend this omission is a win.

   And (`s1-shadow/README.md:64-65`): "Helper loses the concrete peer-send
   grammar and the exact `my.message/decline` form. The routed facts are
   clearer, but facts alone do not teach the only valid response shape."
   **Derived facts do not replace grammar.** The scaffold is load-bearing.

2. **A pure walk creates fleet blindness for root** (`s1-shadow/README.md:72-74`):

   > Root loses its fleet-oversight sentence. A general agent walk needs a
   > root-scoped full-system neighbour or renderer; otherwise the new context
   > fixes root's trigger blindness by creating fleet blindness.

   This directly qualifies README ruling (4) at README:707-711 ("ROOT: same
   context system as everything, plus root-specific context").

3. **"Namespace at distance 2 is useful" is UNTESTED, not proven**
   (`s1-shadow/README.md:74-77`):

   > The agent namespaces contain no durable functions, schemas, source, or
   > require edges at any captured basis. Consequently these outputs exercise
   > the namespace statement fallback but cannot demonstrate whether distance-2
   > function bodies and dependency summaries are useful.

   Verified structurally today: `sci/eval.clj:320-345` publishes only fully
   contracted functions, and `program.edn:42` makes requires unwalkable.

4. **"Mode rides the trigger" is a hypothesis, not a fact**
   (`s1-shadow/README.md:89-92`): "`:context-walk/mode` is inferred in the
   experiment… No current mode fact rides the trigger, so this is a hypothesis
   made visible, not evidence that the mode contract exists." README ruling
   13(d) (README:831-832) states the target; nothing implements it.

5. **A small static scaffold is not free** (`s1-shadow/README.md:81-83`): "The
   static execution scaffold is 322 repeated characters in every shadow. It is
   valid scaffold, but it dominates the tiny root comparisons."

6. **Per-shape caps must not become a hand list** (`s1-shadow/README.md:122-124`):
   "Move the message-specific value-render options into the eventual
   schema-attached renderer. A production composer should not carry a hand list
   of per-shape caps." The fresh tree is currently clean here — one admission
   codec, one caps set.

7. **Budgeting after membership fails at scale.** A fresh-reset prompt measured
   ~55,362 tokens against a 50,000 target with namespaces alone at 47,290
   (~85%), and function heads duplicating contracts were ~65% of namespace cost
   (`old-context-assembly-2026-07-29.md:346-351`, citing
   `docs/seon/issues/archive/context-budget-fn-head-lean.md:8-69`). The quarry's
   conclusion (`:358-362`): "Distance is a better semantic budget than scattered
   character thresholds: it determines what representation is requested before
   bulk data is acquired." A walk that acquires and then clips repeats the
   failure.

8. **Silent omission is confabulation fuel.** The old `:your-entity` block
   returned blank on nil and vanished from the model prompt while the inspector
   still rendered it (`old-context-assembly-2026-07-29.md:733-743`). The fresh
   tree already applies the repair — a failed block contributes a bounded
   named statement (`prompt.cljc:106-114`) and a failed walk node contributes
   its message (`walk.clj:450-453`). Keep that under the new framing; a
   membership derived by a walk has MORE places to fail silently, not fewer.

9. **A terminal synthetic summary band conflicts with the fresh rules**
   (`transcript-aging-quarry-2026-07-29.md:179-191`): the measured prototype's
   `:summary` head is what made the old prefix append-only and cache-friendly,
   but "the current architecture says retained transcript events are never
   rewritten into summaries" (`docs/seon/architecture/context.md:479-486`). The
   quarry's verdict: "The reusable lesson is age-varying **projection**, stable
   bytes, and bounded expiry—not the specific synthetic one-line summary."

10. **The old copy-at-birth model is the named scaling failure, and the fresh
    tree still has its shape.** `old-context-assembly-2026-07-29.md:28-33`:
    "A human maintained a total ordered block list, root overlays, profiles,
    special-resident replacements, per-block prose, caps, and cache priorities.
    That list was copied into every agent at birth." Fresh
    `render/agent.clj:501-517` seeds by upsert (idempotent, so drift is much
    smaller than the old copy), but membership is still an installed set the
    agent owns forever.

### Verification of the quarry's key claims against `src-old`

Spot-checked at file:line, all confirmed:

- `src-old/seon/agent/ctx.cljc:1619-1633` — `agent-blocks` sorts the agent's
  own complete `:seon.agent/ctx` set by `(priority, name)`, "no merge, no
  separate default set: every block was seed-copied in at creation."
- `src-old/seon/agent/ctx.cljc` `default-cache-breakpoint` = **20**, with the
  docstring "blocks with priority ≤ this are the byte-stable cacheable PREFIX…
  the provider cache line falls at the transition to the volatile tail."
- `src-old/seon/agent/ctx.cljc:1747-1777` — assembly brackets each block and
  splits stable/volatile at that breakpoint with an inserted
  `stable-boundary`; a profile render skips the split entirely.
- `src-old/seon/agent/ctx/transcript.cljc:60-70` — the three-level result decay
  defaults are exactly `4096 @ offset 0`, `1024 @ offset 2`, `512 @ offset 5`;
  `::turns-retained 25`, `::turn-window-size 50`, `::turn-eviction-size 25`,
  `::settled-token-cap 8192`.
- `src-old/seon/agent/ctx/namespaces.cljc:917-999` — the local stability
  gradient is real: "the STABLE `seon.*` required nses render FIRST,
  name-sorted, as a cache PREFIX; then the agent's churning BODY (my.* /
  current ns) ordered by RECENCY (tx of the `:seon.ns/name` datom, name
  tie-break)". This is the ONLY change-time ordering the old system had, and it
  was local to one block — corroborating
  `old-context-assembly-2026-07-29.md:695-700`.

## 4. Open design questions for the owner

Each is a real question because the evidence underdetermines the answer.

1. **Does the walk own membership, or does a scaffold band survive above it?**
   Evidence both ways: S1 measured concrete losses from a pure walk (peer-send
   grammar, decline form, root's fleet sentence —
   `s1-shadow/README.md:64-74`), while README:693-699 already rules blocks
   survive "for static scaffold only (system message, REPL instructions,
   AGENTS.md loading)." The question is whether the surviving scaffold is a
   block SET (ordered, priority-bearing, installable) or a fixed prelude with
   no membership machinery at all. A block set that survives is a membership
   mechanism, and the loadout defect can regrow inside it.

2. **How does root get the fleet without a second context mechanism?**
   S1's own gap list demands this (`s1-shadow/README.md:114-116`): "Design
   root's full-system reach through the same walk; do not retain the fleet
   block as a hidden second context mechanism." Options are a root-scoped
   neighbour (the cluster as an entity the walk reaches), a viewer override on
   the agent family for root, or a root-only renderer. All three are consistent
   with the resolution chain (`walk.clj:22-41`); they differ in whether
   "root" becomes a name-based rule, which the standing no-hand-lists ruling
   would refuse.

3. **Do `:seon.ns/requires` become refs?** Today they are symbols
   (`program.edn:42`) and the walk cannot follow them, so "requires at
   distance 2" is unimplementable as a ref walk. Making them refs makes the
   walk uniform but couples namespace rows to the existence of the required
   namespace's row (external/`clojure.core` requires have no row). The
   alternative is a namespace renderer that resolves symbols itself — which
   reintroduces a renderer that knows about a second traversal, the thing
   `walk.clj:4-11` was created to prevent.

4. **What is the namespace's default renderer, and does distance or budget
   choose bodies?** The ruled gradient (README:580-586) says distance chooses.
   The quarry says the old system's equivalent was a fixed 240-character
   threshold and recommends replacing it with "the request's actual remaining
   render budget" (`old-context-assembly-2026-07-29.md:408-411`). Those are
   different mechanisms; distance is byte-stable across turns (good for cache),
   a remaining-budget rule is not.

5. **What does an agent's uncontracted `defn` contribute to its own context?**
   `sci/eval.clj:320-345` publishes only contracted functions, so an agent's
   ordinary scratch work is invisible to a corpus-based namespace render. Under
   "context is render(my namespace)", an agent could write code and then not
   see it. Either the walk reads SCI's live intern table (process-local, not a
   fact — conflicts with the one-database-value rule at README:819-821), or
   uncontracted defns publish something, or the agent genuinely does not see
   them and the transcript carries them instead.

6. **Is ordering derived from change timestamps, or from captured content
   digests?** README:700-706 says change timestamp. `protocol:49-53` (S4) says
   "Piece digests (registration-memory cache, derivable from scratch), two
   generational bands with hysteresis, canonical order within band" and demands
   MEASURED cache hit-rate improvement.
   `transcript-aging-quarry-2026-07-29.md:202-208` names the unresolved seam
   explicitly: the architecture still describes "semantic bands plus a Bayesian
   rendered-byte change estimator, frozen ordering epochs, and hysteresis"
   (`docs/seon/architecture/context.md:440-466`) while the newer ruling names
   parallel rendering and change-timestamp ordering. **Two documented
   algorithms, one owner decision outstanding.**

7. **Which pinning mechanism, given bands already exist?** `block.edn:43-48`
   deliberately makes bands AUTHORED, not derived. A new "priority override for
   pinned/system content" is either the same mechanism under a new name (fine —
   reuse it) or a second one (a duplicate ordering authority). Worth an explicit
   ruling so an implementation lane does not invent a third dial.

8. **Is there a whole-context budget, and who owns it?** Neither system has
   ever had one (`old-context-assembly-2026-07-29.md:196-198,344`), and the one
   historical measurement blew a 50,000-token target by 11%
   (`context-budget-fn-head-lean.md:8-69`). A walk with a node budget
   (`walk.clj:358`) plus per-contribution caps (`prompt.cljc:62-73`) still sums
   to an unbounded prompt. The quarry's recommendation is budget-before-
   acquisition via distance (`old-context-assembly-2026-07-29.md:358-362`).

9. **Does mode become a real fact on the trigger message?**
   S1 inferred it (`s1-shadow/README.md:89-92`); README:679-692 rules two modes
   (chat, goal-seeking) and README:831-832 says mode rides the trigger as
   ordinary data. Nothing implements it, and S2's behavioral comparison is
   materially different depending on whether mode is real or inferred.

10. **Does the transcript's terminal band freeze (append-only prefix) or
    simply omit?** The measured prototype's frozen summary head is what made
    the prefix byte-identical across turns
    (`transcript-aging-quarry-2026-07-29.md:63-72`), and that is precisely the
    cache property the new framing wants. The fresh no-rewrite rule currently
    forbids it (`architecture/context.md:479-486`). Changing that "requires an
    explicit ruling and model/cache evidence"
    (`transcript-aging-quarry-2026-07-29.md:361-365`).

## 5. Skill drift

Two skills were loaded for this lane: `data-oriented-clojure` and `repl`. No
drift was found in the claims this lane could check against current source:

- `data-oriented-clojure`'s schema-path claim (shipped schemas are EDN under
  `resources/seon/schema/`, loaded by `seon.schema.edn/load!`) matches the
  33 files under `resources/seon/schema/` and the `(schema.edn/load! {})` calls
  at `src/seon/context.clj:57`, `src/seon/cluster/prompt.cljc:51`,
  `src/seon/render/walk.clj:81`.
- `data-oriented-clojure`'s runtime-publication citation
  (`src/seon/sci/eval.clj:320-345`) lands on `program-row`, the contracted-only
  publication gate — accurate.
- `repl`'s reply-surface citations (`src/seon/cluster/reply.cljc`) name a file
  that exists with the described contract entry points.

One observation rather than drift, for the owner's judgment: **no loaded skill
describes the context/prompt assembly path at all.** An agent asked to change
context assembly gets `data-oriented-clojure` (general), `seon-flow-architecture`
(procs), `datastar-web-ui` (the HTML side) and `seon-context-config` (config —
its description explicitly warns off "context-block manifests"). Given the
standing ruling that skills are load-bearing context and a stale skill is a
high-priority defect (README:775-779), the context-assembly redesign should end
by either extending an existing skill or recording that no skill claims this
surface. Filing that as a defect is out of scope for this read-only lane, but it
is the kind of gap the skills-blast-radius ruling is aimed at.

## 6. The shortest honest summary

The walk exists and works. Distance exists and is spent correctly. The
resolution chain exists with all four steps. What does **not** exist is
everything that makes the walk sufficient: a namespace renderer, walkable
require edges, any AI transcript, any churn ordering, any whole-context budget,
and any mode fact. S1 proved the walk renders the TRIGGER far better than
blocks do and proved it renders HISTORY not at all. The owner's new framing is
consistent with every recorded ruling; its novelty is in deciding that
membership itself dies, and the evidence says that decision is safe only after
S3's transcript and a real namespace renderer exist.
