---
type: prd
status: active
tags: [prd, context, render]
---

# The whole-context budget — 2026-07-31

The one mechanism that bounds a whole agent context. It is the last unowned
piece of the context system: the walk visits everything, every unit renders,
and nothing anywhere decides that the assembled result is too big. This
document designs that decision, ready for owner review and then falsification.

Scope: the `:seon.render/ai` assembly produced by `seon.render/walk`. The HTML
projection is out of scope — a page scrolls, and ruling #8 already hides
floor-rendered units there by default.

## 1. The problem, measured

- The `seon.flow` owner's d2 walk renders 25 compact namespace cards totalling
  **17,696 estimated tokens** (71,302 UTF-8 bytes) with no raw member datoms
  left — `research/mvp-seams-notes-2026-07-31.md` finding 5 and its
  measurement table. The distance seam is repaired; the size is the absent
  budget.
- Distance is a binary dial. `research/context-walk-falsification-2026-07-31.md`
  §4 measured d1 = 2,102 tokens, d2 = 20,314, **d3 = 107,793** for a realistic
  agent. There is nothing in between, and no way to spend the unused half of a
  50k budget on anything useful.
- Historically no whole-context budget has ever existed in either generation.
  The one 50k target that was named was blown by 11%.
- The fresh-agent case is fine: birth d1 = 236 tokens, birth d2 = 1,805
  (`mvp-seams-notes` measurements). The namespace-owner case is the forcing
  function, and it is the case the distributed-ownership protocol makes normal.
- The two landed budget seams are private dials with no producer —
  `[[render-token-budgets-are-private-dials-no-producer-supplies]]`.
  `src/seon/render/ns.clj:322-324` reads `::token-budget` with `nil` meaning
  NO bound, so its entire bounded-assembly section is dead on every production
  path. `src/seon/render/transcript.clj:536` reads the same key with a default
  of `0`, so it would render nothing but its elision marker; the walk supplies
  the transcript's (`src/seon/render/walk.clj:480-483`) and nothing supplies
  the namespace renderer's.

## 2. Dependency ledger

Read for this design, with the facts each one settles.

| Source | What it establishes |
|---|---|
| `src/seon/render/walk.clj:424-625` | `neighborhood` renders EAGERLY during traversal; three existing bounds (hops, `max-nodes`, `max-collection`); elision is already an error-valued node (`:seon.error/kind ::elided`) |
| `src/seon/render/walk.clj:658-722` | `prose` flattens to units carrying `path`, `found-depth`, `branch`, `changed-at`, then sorts by `(juxt changed-at branch path)` — ascending, so stable prefix first, churn tail last |
| `src/seon/render.clj:143-218` | the one agent-facing entry; takes `{:root :depth :branch}`, resolves caps from config, and is called identically by prompt assembly |
| `src/seon/cluster/prompt.cljc:44-66` | the prompt IS one `seon.render/walk` call at the turn's basis, and it receives the UNCAPPED function result |
| `resources/seon/schema/admit.edn:5-20`, `config/default.edn:22-26` | the existing cap facts: `max-collection 64`, `max-string 4096`, `max-nodes 4096`, `max-depth` |
| `src/seon/ai/tokens.cljc:45-54` | `estimate`, `estimate-chars`, and the one `chars-per-token` ratio — the standing rule that human-visible sizes are estimated tokens |
| `resources/seon/schema/ai.edn:62-76`, `resources/seon/schema/config.edn:70-95` | the descriptor row today: endpoint, model, `max-tokens` (OUTPUT), key variable, timeout. **There is no context-window fact anywhere in `resources/`** |
| `research/agent-model-override-quarry-2026-07-31.md:255-334` | the landed direction: `:my/model` is a REF to one descriptor row; generation facts overlay it; the walk reaches the row so the agent sees what it runs on |
| `research/old-namespace-schema-lookup-quarry-2026-07-31.md:221-268, 291-330` | the old caps (`referenced-schema-cap` 40 with an honest cap line, 2048-key aggregate, 78-CHARACTER soft clip), and that full source was NEVER truncated — the only real budget shape was ordering for prompt cache |
| `resources/seon/schema/test.edn:1-10` | `:seon.test/sym` rows carry `:seon.test/ns` — a ref to the namespace HOLDING the test. This is the computed handle for "is this a test namespace" |
| `resources/seon/schema/program.edn:42` | `:seon.ns/requires` is already `[:set :seon.db/ref]` — the derived-edge workaround retired, so reverse fan-in is a real AVET slice |
| `plan/README.md:1478-1549` (rulings #12, #13 + clarifications) | detail inverse to distance; the uniform cluster-carried base set; context is ONE explicit visible walk eval, re-derived fresh each turn, and the agent can call it again with `(root, depth, branch)` |
| `plan/context-render-data-model-spec.md:126-176` | the cache is PER FUNCTION CALL — `(renderer-fn × explicit args) → bytes` |

Standing rulings this design is bound by: the walk visits EVERYTHING and a
budget may **elide loudly, never silently exclude** (P1, P6); ordering is dumb
last-changed with branch tie-clustering; no hand lists; no magic constants a
config fact could own; no second mechanism beside an existing one.

## 3. Where the budget lives

### 3.1 It is one number, resolved per invocation, passed on the request

The budget is not stored per agent and not stored per render. It is one
resolved scalar that rides `:seon.render.walk/request` and `prose`'s option
map, exactly as `:seon.sci.admit/caps` rides them today. Proposed name and
declaration, in `resources/seon/schema/walk.edn` beside the request it
belongs to:

```clojure
;; [TARGET]
:seon.render.walk/token-budget [:int {:min 1}]
```

Required on the request, never optional-with-nil-meaning-unbounded. The
current `nil` = unbounded convention in `ns.clj` is precisely how the dead
dial hid: an absent budget must be a refusal at the boundary, not a silent
licence to render 107,793 tokens.

### 3.2 Two invocation classes, two computed producers, no second dial

There are genuinely two callers, and each already holds the fact that bounds
it. Neither needs a new dial.

- **Prompt assembly** (`src/seon/cluster/prompt.cljc:44-66`) — the context
  ceiling, §3.3.
- **An agent's own `(seon.render/walk …)` eval** — its returned string faces
  `:seon.config.eval.result/max-string` at value admission anyway. The seams
  run observed exactly this: the eval result came back at 1,024 tokens with
  `:seon.sci.admit/capped? true`. So the producer here is
  `(quot max-string chars-per-token)` — the same derivation the walk already
  performs for the transcript at `walk.clj:480-483`. The gain is not size, it
  is honesty: the agent gets a loud elision marker and a drill handle instead
  of a string truncated mid-form.

This is one mechanism with two computed producers, which is the shape the
codebase already uses for caps. A `:seon.config.render/eval-walk-budget` dial
would be a second number to keep in step with `max-string` and would drift.

### 3.3 The context ceiling — three options

**(A) One config fact.** `:seon.config.render/context-budget-tokens` on the
config singleton, shipped in `config/default.edn`.

- Benefit: one declaration, live today, exact-reconciled like every other
  dial, and per-cluster overridable by the existing sparse overlay.
- Cost: it is a hand-tuned number, and it is wrong for every model whose
  window differs from the one the number was tuned against. A cluster running
  a 1M-window model pays the same 30k ceiling as one running an 8k model.

**(B) Derived from the model's context window.**
`budget = window − reserved-output − headroom`, where `window` is a new
`:seon.ai/context-window` attribute on the descriptor row and
`reserved-output` is the descriptor's existing `:seon.ai/max-tokens`.

- Benefit: the ceiling is a property of the thing that actually imposes it.
  It follows `:my/model` automatically, so an agent that switches models gets
  the right context with no second edit. It is derived, not declared, which
  is the house rule.
- Cost, stated honestly: **the window fact does not exist.** `ai.edn:62-76`
  carries endpoint, model, `max-tokens`, credential variable and timeout, and
  `max-tokens` is the OUTPUT budget (`config.edn:86-89` says so in as many
  words). Landing (B) means adding one attribute to the descriptor row, which
  is cheap, plus a value per shipped target, which is a small maintained
  table of externally-owned numbers — a hand list in all but name, and one
  that goes stale when a provider raises a window.
- Second cost: the prompt is not the whole request. Instructions, the reply
  grammar and the settled reply share the window, so the walk's share is
  `window` minus terms the walk does not own. That subtraction is real
  arithmetic, not a fudge factor, but it must be written down somewhere.

**(C) Both, as a `min`.** `budget = min(config dial, window-derived ceiling)`,
where the window term participates only when the descriptor carries the fact.

- Benefit: correct on both axes and degrades honestly. The dial is the
  operator's cap on cost and prompt size; the window term is physics. Absent
  facts nil-pun out of the `min` rather than defaulting to a lie.
- Cost: two sources for one number, which needs one function that owns the
  resolution and one docstring stating the precedence.

**RECOMMENDATION: (C), landing the (A) half now and the (B) term when
descriptor rows land.** Write the resolver as a pure function of `(db, agent)`
returning a single number, with the window term folded in by `min` when
present. Do not wait for the descriptor rows to bound the context — the
17,696-token walk is live today, and the dial alone fixes it. Do not ship the
dial alone permanently either: a fixed 30k against a 200k-window model wastes
the capability the model override was built to expose.

**Falsifier that changes this:** if measurement shows the walk's share of the
window is dominated by a term the walk does not own (a system prompt or reply
grammar that grows with the corpus), then the walk's budget is not a function
of the window at all but of the residue, and (C) collapses to (A) with the
residue computed by the prompt assembler and passed down. Probe: measure
assembled prompt bytes by contribution across five real turns.

## 4. How the budget distributes over the walk's branches

This is the interesting half. The walk produces a flat sequence of units (see
`prose`'s `units`, `walk.clj:661-683`), each carrying its `branch` (the
top-level path prefix it hangs from), `found-depth`, `changed-at` and rendered
`text`. Total cost is known only after rendering, because `neighborhood`
renders eagerly. So distribution is a **selection over already-rendered
units**, not a pre-allocation of quota to subtrees.

That ordering — render everything, then select — is what the ruling demands
anyway ("the walk visits EVERYTHING"), and the call-grain cache makes the
rendering work amortized rather than wasted: a unit rendered but not selected
this turn is a warm cache entry for the next.

### 4.1 The three candidate distributions

**(a) Fixed per-branch fractions.** Give the transcript 40%, the namespace
branch 40%, everything else 20%.

- Benefit: trivially implementable, and each branch is guaranteed a floor.
- Cost: it is four hand-tuned numbers with no derivation, which the standing
  rule forbids on sight. It is also wrong by construction — the fresh agent
  has no namespace worth 40% and the owner agent has no transcript worth 40%,
  and neither can borrow from the other.
- **Reject.**

**(b) The old grow-highest-value-first loop, generalized to units.** Score
every unit, sort by score, admit units one at a time until the budget is
spent, then stop. This is `budgeted-ai`'s proven shape
(`src/seon/render/ns.clj:451-465`) lifted from "one function at a time inside
one card" to "one unit at a time across the whole walk".

- Benefit: no per-branch numbers at all. Distribution is an OUTCOME of one
  ranking, so a fresh agent's budget flows to its instructions and an owner
  agent's flows to its namespace, with nothing configured. It degrades
  monotonically: a smaller budget yields a strict prefix of the same ranking,
  which makes the behaviour explainable in one sentence.
- Cost: a single global ranking can starve a whole branch. If every namespace
  card outranks every message, an owner agent sees no transcript at all —
  which is a worse failure than a truncated transcript.
- Cost: the ranking function is where the hand-tuning goes if you are not
  careful. Its terms must be derived, and there must be few of them.

**(c) Proportional with floors, derived from branch sizes.** Each branch gets
`budget × (its rendered size / total rendered size)`, clamped below by a
floor.

- Benefit: no branch starves; allocation follows real measured demand.
- Cost: it rewards bloat. The branch that renders most gets most, so the
  `seon.fn` family that produced 87.3% of the d2 tokens
  (falsification §4) would be handed 87.3% of the budget — exactly backwards.
  Proportionality is the wrong prior when the largest branch is the one whose
  size is the defect.
- Cost: "floor" is another hand-tuned number per branch.

**RECOMMENDATION: (b), with one structural repair for its starvation cost —
admit in ROUNDS.** Rank units, then admit by walking the ranking in passes,
taking at most one unit per branch per pass. A branch with many high-ranked
units still wins overall (it appears in every round), but no branch is empty
while another is still spending. Round-robin over branches is not a tuned
number; it is the statement "every branch that has anything to say says
something first", which is the same fairness floor P7 asserts for renderer
evals.

### 4.2 The ranking terms

Three derived terms, no weights to tune, applied as a lexicographic sort so
there is no scalar blend to calibrate:

1. **Proximity** — `found-depth` ascending. This is ruling #12(b) restated as
   a budget rule: detail is inverse to distance, so spend is inverse to
   distance. d1 before d2 before d3.
2. **Direction** — forward connections before reverse ones. What I depend on
   is what I need to write code; who depends on me is context I can ask for.
   Derived from the connection, which `refs` already knows (`walk.clj:344-351`
   emits forward refs first by construction).
3. **Recency** — `changed-at` descending, within the two terms above. This is
   the only term that moves turn to turn, and confining it to third position
   is deliberate; see §5.

Ties after all three fall back to `branch` then `path`, which is exactly
`prose`'s existing comparator, so the tie-break is already written and already
deterministic.

**Falsifier:** if a live drive shows an agent repeatedly calling
`(seon.render/walk {:branch …})` to retrieve something the ranking put below
the line, the ranking is wrong and the agent's own drill calls are the
measurement that says so. That signal is free — those calls are ordinary eval
receipts (ruling #13) and therefore queryable. This is the honest way to tune
a ranking: from observed re-fetches, never from taste.

## 5. Selection order is not display order

The task frames a choice — elide from the stable cached prefix, or from the
churn tail? Both are wrong, and the framing is the trap.

`prose` sorts ascending by `changed-at`, so today the stable units are the
prefix and the churning ones are the tail (`walk.clj:713-721`). That order
exists for prompt-cache economics: the old system deliberately put stable
`seon.*` requires first as a cache prefix and the churning body last
(`old-namespace-schema-lookup-quarry-2026-07-31.md:261-267`), and that
decision is ADOPT-rated but not yet built at walk level.

- **Eliding tail-first** drops the freshest, most relevant units — the
  messages just received and the code just changed. It preserves the cache
  prefix perfectly and destroys the context's usefulness.
- **Eliding prefix-first** preserves recency and busts the prompt cache on
  every turn where membership shifts, which is the cost the ordering was
  designed to avoid.
- **Eliding from the middle** breaks branch tie-clustering, which ruling
  #7(2) explicitly requires.

The resolution: **selection and display are separate passes.** Rank and admit
in §4's priority order; then place the admitted set in the existing
`(changed-at, branch, path)` display order. Elision is not positional at all.
A unit's rank decides whether it appears; its `changed-at` decides where.

This also settles the cache economics correctly. Prompt-prefix stability comes
from a stable admitted SET, not from a stable sort. That is why recency is the
third ranking term and not the first: proximity and direction are slow-moving
properties of the graph, so the admitted set changes only when the graph does,
while the display position of an admitted unit still moves with recency and
costs only the tail of the cached prefix. Ranking primarily by recency would
churn membership every turn and bust the prefix regardless of sort order —
that is the concrete argument against the obvious "recency + proximity" blend.

**Falsifier:** measure admitted-set Jaccard similarity across consecutive
turns of a real drive. If it is below ~0.9 for an agent doing ordinary work,
membership is churning and the prefix claim is false.

## 6. Interaction with the call-grain cache

The cache is `(renderer-fn × explicit args) → bytes`
(`plan/context-render-data-model-spec.md:126-136`). A budget passed INTO a
renderer is an explicit arg, so every distinct budget value is a distinct
cache key. `ns.clj`'s `budgeted-ai` loop is the worked example of the failure:
it calls `ai-text` once per included-function count, so rendering one
namespace at budget 5,000 produces up to N+1 distinct cached calls, and
rendering the same namespace at budget 5,001 produces N+1 more that share
nothing with the first set. A continuous parameter in a cache key is not a
cache.

**The law this design proposes: a renderer's arguments are DISCRETE. The
budget is never one of them.**

- Renderers take `:seon.render/distance` and nothing size-like. Distance
  already has a tiny domain (0, 1, 2), so the key space is
  `{fn × entity × tier}` — finite, and shared across every agent viewing the
  same entity, which is what makes P5 prefix sharing true by construction.
- The budget acts only on WHOLE UNITS, at the walk level: admit the unit, or
  demote it one tier and admit that (a d2 card becomes the d0 name line), or
  elide it with a marker. Demotion targets another already-cacheable call, so
  the mechanism costs no new keys.
- Consequence: **delete `::token-budget` and the `budgeted-ai` /
  `budgeted-html` loops from `src/seon/render/ns.clj`** rather than supplying
  them a producer. They are the wrong grain, they are dead in production
  today, and the issue's acceptance criterion is satisfied by removal.
  `render.ns` keeps its distance tiers, its schema-closure cap and its honest
  cap line.
- The transcript is the one genuine exception and should be examined, not
  grandfathered: a transcript is one unit whose internal length is its whole
  content, so it cannot be tiered by distance. Recommendation: give it a
  small fixed ladder of message counts (its own discrete tiers) rather than a
  continuous token budget, so it obeys the same law. That also removes the
  `candidate-limit` defect the issue records, where a token count is passed
  to Datalog as a row `:limit`.

**Falsifier:** if measured cache hit rate across turns is below ~0.8 for
unchanged entities under the tier scheme, the tier domain is larger than
claimed and this law is not buying what it promises.

## 7. How elision presents

Reuse the existing error-valued node,
`{:seon.error/kind ::elided, :seon.error/message …}`, as ordinary returned
data. The walk call is the form and its computed value contains the elision
fact; `prose` must not turn it into comment-framed output.

One marker per elided branch, not one per elided unit: 25 markers is itself a
budget problem. The marker carries the count, the reason, and the exact call
that retrieves what was dropped:

```clojure
{:seon.error/kind :seon.render.walk/elided
 :seon.error/message "17 namespace cards elided (≈12,400 tokens)."
 :seon.render.walk/path [:seon.render.walk/neighbours 3]
 :seon.render.walk/depth 2
 :seon.render.walk/provenance :seon.render.walk/elided}
```

Three properties this must hold, each already named in the spec's P6:

- the marker states a COUNT and a SIZE, so the agent can judge whether the
  drill is worth a turn;
- the path is a real `get-in` drill handle that `seon.render/walk` accepts
  today (`render.clj:195-217` validates `:branch` and refuses an unknown path
  with an error value); the reusable invocation belongs in `(help)` prose,
  not in a synthetic result comment;
- the size is estimated tokens through `seon.ai.tokens/estimate`, never
  characters. The 78-character `soft-clip` surviving in `ns.clj:363` violates
  this and should go in the same commit.

Teaching the agent to self-serve is the walk's whole ethos (ruling #13: "to
see more, call it again; the explanation of the system is the system"). The
marker supplies the facts and `(help)` supplies the prose; neither turns a
comment into displayed output.

## 8. The test-namespace pollution

The measurement's 25 cards include test namespaces. Source-tree evidence for
the measured case: 21 first-party files reference `seon.flow`, of which **16
are under `test/`**, while `seon.flow` itself forward-requires only two
first-party namespaces (`src/seon/flow.clj:16-17`). So the fan-in is roughly
8:1 reverse-to-forward, and roughly three quarters of the reverse fan-in is
tests. (This is a source count and should be confirmed against `:seon.ns`
rows before implementation; the ratio is what matters, not the exact number.)

**This is a requires-graph matter, not a budget matter.** A budget would make
the symptom smaller while leaving the walk structurally wrong. Three questions,
answered separately:

**Do reverse-require edges belong in context at all? Yes.** "Who depends on
me" is exactly what a namespace owner must know before changing a symbol, and
the distributed-ownership protocol (ruling #7(5)) makes it operational: you
message the owners of the namespaces you would break. Deleting reverse edges
would delete the data that protocol runs on. They rank below forward edges
(§4.2 term 2), which is the correct expression of "lower value, not zero
value".

**Should a consumer render as a full card? No.** A namespace that merely
requires me does not need its whole public surface in my context; it needs its
name. Recommendation: reverse `:seon.ns/requires` neighbours render at
distance 0 — the name line — collapsed into one aggregate unit per branch:

```clojure
[seon.cluster seon.cluster.agent seon.cluster.loop ...]
```

That is one unit of a few dozen tokens replacing 21 cards, and it is a
rendering decision inside `seon.render.ns`, made once, not a budget decision
remade every turn. Measured against the falsification's own numbers this is
the same class of win as the missing `:seon.fn` lens (15× there).

**Is "test namespace" a name rule? It must not be.** The old system's
`selected-ns?` excluded `*-test` by name (`namespaces.cljc:94-104`), which is
a hand list and is banned. The computed rule is available today:
`:seon.test/ns` is a ref from every indexed test row to the namespace holding
it (`resources/seon/schema/test.edn:3-10`), so **a namespace is a test
namespace exactly when a `:seon.test` row points at it.** No suffix matching,
no prefix rule, and it stays correct for a test namespace named anything at
all.

With that predicate, the honest presentation of a namespace's tests is that
they are part of ITS OWN card — "tested by 16 test namespaces", or better, the
count of `:seon.test` rows whose namespace requires this one — rather than
sixteen peer cards competing for budget with real dependencies. The old
system's `::with-tests` had the right instinct and the wrong mechanism.

**Falsifier:** if an agent doing owner work repeatedly drills into a test
namespace's full card, the collapse is too aggressive and test namespaces
deserve a tier above the name line. Again the drill receipts are the evidence.

## 9. Implementation shape

One owner, no new namespace.

1. `resources/seon/schema/walk.edn` — declare
   `:seon.render.walk/token-budget`, add it as a required key of
   `:seon.render.walk/request`, and declare the elision marker's data shape
   beside the existing `::elided` idiom.
2. `resources/seon/schema/config.edn` + `config/default.edn` — one dial,
   `:seon.config.render/context-budget-tokens`. One shipped value, chosen from
   the measured d2 numbers, recorded here when set.
3. `seon.render.walk` — the selection pass, between `units` and the display
   sort in `prose` (`walk.clj:713-721`). It is a pure function from
   `(units, budget)` to `(admitted-units, markers)`; everything above and
   below it is unchanged.
4. `seon.render` — resolve the budget for each of the two invocation classes
   (§3.2) and pass it through; the existing `caps` resolution at
   `render.clj:180-192` is the pattern to copy, including its refusal when the
   fact is missing.
5. `seon.render.ns` — delete `::token-budget`, `within-budget?`,
   `budgeted-ai`, `budgeted-html` and the `soft-clip` character measure;
   collapse reverse-require neighbours to the aggregate name line; keep the
   distance tiers and the schema-closure cap line.
6. `seon.render.transcript` — replace the continuous token budget with
   discrete count tiers, and derive `candidate-limit` from a per-entry token
   cost rather than the budget scalar (the issue's own acceptance criterion).

Recurring proof, as properties beside P1–P8 rather than point tests:

- **P6 (extended)** — for any generated graph and any budget above the
  minimum, assembled output is within budget AND every dropped unit is
  represented by exactly one marker naming a `:branch` that
  `seon.render/walk` accepts. Elision is loud, always.
- **P9 monotonicity** — for budgets `b1 < b2`, the admitted set at `b1` is a
  subset of the admitted set at `b2`. This is what makes the mechanism
  explainable, and it is the property option (a) and (c) both fail.
- **P10 fairness** — every branch with at least one renderable unit
  contributes at least one unit, for any budget at or above the minimum
  (§4.1's round-robin). The minimum itself is derived — one unit per branch
  plus markers — never a constant.
- **P4 (unchanged)** — display order is still the existing comparator, so the
  landed ordering property continues to hold over the admitted set.

## 10. Open for the owner

1. **The shipped dial value.** The measured d2 range is 20–22k for a realistic
   owner agent and 1.8k for a fresh agent. A 30k dial admits today's owner
   walk nearly whole and leaves headroom; a 12k dial forces the mechanism to
   work from day one and surfaces ranking defects immediately. Recommendation:
   ship **12k** deliberately, so the budget is exercised rather than dormant,
   and raise it on evidence. A dial that never binds is the dead
   `::token-budget` all over again.
2. **`:seon.ai/context-window` on the descriptor row** — approve the attribute
   now (cheap, and `:my/model` already routes to the row), or defer §3.3's
   (B) term until a model whose window actually binds is configured.
3. **Round-robin fairness** — confirm that a starved branch is worse than a
   thin one. The alternative is pure ranking with no rounds, which is simpler
   and admits "an owner agent sees no transcript this turn" as acceptable.
4. **Transcript tiers** — the transcript is the one unit whose content is its
   length. Confirm the discrete-ladder direction before its renderer is
   rewritten, since the alternative is admitting one continuous parameter into
   the cache key and documenting why it is the sole exception.
