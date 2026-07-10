---
type: architecture
status: active
tags: [architecture, agent]
---

# Context — functions applied to the db

> **Target design** (present tense). The block/render machinery lives in
> [[ui]]; turn replay + inspection in [[observability]]; the measured laws
> that constrain this in [[laws]]. We-are-here: [[roadmap]]. This doc keeps
> to Clojure primitives — `ns`, `defn`, `require`, var metadata, a db value
> — and reserves only the names backed by real code (`block` =
> `:seon.agent.ctx/block`, `render` = `:seon.render/*`, `db` = `seon.db`).

The prompt is nothing more than **functions applied to the db, in a stable
order**:

```clojure
context = (str/join (map #(% db) (render-fns-in-scope agent)))
```

Every turn re-derives the whole thing from one frozen db value. Nothing is
accumulated. Which functions are in scope, and in what order, is the entire
design — and whether that set is **complete** is what makes the agent feel
stateful.

## The projection must be complete — so the agent feels stateful

An agent carries nothing between turns; every turn is a cold start from one db
value. Yet it must *behave* as though it remembered — resume its work, act on
what just happened, notice what changed. It can, because a stateless process is
indistinguishable from a stateful one exactly when its rendered context is a
**complete and faithful projection of its situation**: everything a continuous
being would carry forward, re-derived each turn from the db, with no gap left
for the model to fill.

**Confabulation is the diagnostic.** When the render omits or garbles part of
the agent's situation, the model does not fail loudly — it patches the hole
from its training prior: it invents a restart that did not happen, a user
instruction never sent, a task already finished. Every such ungrounded
self-claim is the visible tell of an incomplete projection, and it names the
section to fix. The invariant a correct context satisfies: **an agent makes no
claim about its own state or history that the rendered datoms did not
contain.** This is measurable — replay the byte-exact prompt ([[observability]])
and ground every self-claim against it — and it is the standing acceptance
test for context, not a one-time check.

**The completeness model.** A continuous agent always knows, so the projection
always renders:

- **what just happened** — the event that opened this run (an inbound message,
  a child's outcome notice, a schedule), in the present tense. Absent this, the
  most salient standing frame in the prompt wins by default — so evergreen
  advice ("after a restart, resume your plan") is **conditional/derived**,
  rendered only when its condition actually holds, never planted every turn.
- **where I am** — the plan, rendered with unambiguous status. The plan is not
  a checklist; it is **externalized intent** — the one thing a stateless
  boundary cannot reconstruct unless it was written down as data. An open step
  never renders as a settled fact; a node whose only remaining action is
  verify-and-close renders as actionable, not invisible. (See [[data-model]].)
- **what I am waiting on** — delegated children and their live state (the
  multi-agent sections below), blocked items.
- **what I just did** — my last turn's actions and their outcomes, from the
  transcript.
- **what I learned** — accumulated knowledge (`my.kb`), and *only* knowledge:
  work-tracking that still carries a live lifecycle status is not a settled
  finding and never renders as one.
- **what changed since I last looked** — the **delta**, derived from the
  previous turn's `:seon.agent.turn/rendered-as-of` basis-t: the datoms
  transacted since I last saw the world (new messages, newly-completed
  children, newly-failed items). A series of independent snapshots becomes a
  felt continuity precisely because each turn can name what is new — the
  basis-t is already recorded per turn ([[observability]]); the delta is a
  query over it, not new state.

**Situation, never the answer.** The projection renders the agent's operational
situation and the operations available on it — "a child is idle at its
turn-limit; continue it or release it," "three plan items are open and
independent; any may be delegated" — because a continuous agent would know
these. It never renders the answer to the agent's task; that is the line
between context and coaching. Making the situation legible makes the right
action obvious without prescribing it.

## The transcript is the spine — the REPL narrative the rest attaches to

The completeness rows are not peers. One is the **spine**: the transcript —
the agent's own eval log rendered as a REPL session ("I evaluated X, got Y; a
message arrived; I evaluated Z"). A snapshot section (plan, findings,
subagents) is a photo of *now* with no story; a REPL narrative is inherently
stateful, because it is the ordered record of what the agent actually did and
what actually happened to it. The eval log is one view of the code corpus
(code-as-data); the transcript is its faithful render, and it is the agent's
primary memory. Everything else is **additive**.

**Precedence — the transcript is authoritative for "what happened."** A
derived section that implies something the transcript contradicts is the bug,
not the transcript. (The findings-renders-open-plan-as-fact defect was exactly
this: a snapshot claimed work the eval log never did.) So the
confabulation-audit grounds an agent's self-claims against the **transcript
first**; a section that fought it is the defect to fix.

**"Nail the REPL" = four faithfulness invariants.** The transcript renders a
byte-faithful REPL session:

1. every form the agent evaluated, in order, with its **actual** return — not
   truncated or summarized into something that reads differently;
2. errors rendered **as** the failed eval (errors are data — a throw shows as
   "I tried X and it threw", never silently absent);
3. events interleaved at the point they occurred and **attributable** — an
   inbound message is unmistakably distinct from the agent's own eval;
   mis-attribution is the fake-instruction confabulation;
4. async resolved to **values**, not dangling Promises — a form that returned
   a pending computation shows its resolved value (or a legible "value now at
   `result/<id>`"), never a Promise the agent can't tell finished.

**Additive, not optional.** The spine is bounded and blind, so two additive
roles are load-bearing: derived sections **crystallize what the transcript
will lose** as it decays (the plan is the durable form of intent, findings of
knowledge — what would otherwise scroll off), and they **surface what the spine
is blind to** — derived state the agent never eval'd (a child at its
turn-limit) and non-event changes between turns. Because the transcript already
carries event-deltas (a message that arrived is already a line), the delta
surface is only the *non-event* changes. Additive sections layer on the spine;
they never contradict it.

## The REPL mode is a datom — and it teaches its own grammar

The agent's turn resolves a form's result in one of two modes, selected per
cluster by the `:seon.config/repl-mode` datom (`:batch` default | `:stream`):

- **`:batch`** — one LLM call writes N forms. Every model-authored
  result-claim (a `⟹ …`/`=> …` a model types into the reply, pattern-completing
  the transcript's `form ⟹ value` grammar) is **stripped at the reply
  boundary**, before the reply is persisted or eval'd. The forms run; the next
  turn's transcript interleaves the *real* `⟹ <value> ⟸ result/<id>` rows in
  those positions. The fabrication never enters the record — a fix at the
  boundary, not a render-time rewrite. The detector
  (`seon.agent.ctx/first-result-claim`) skips any match inside a
  successfully-parsed form span, so a `(println "⟹")` literal or a `:=>` in a
  `:malli/schema` never fires.
- **`:stream`** — the SDK stream is consumed delta-by-delta and **aborted the
  instant one complete top-level form has streamed** (a cheap escape-aware
  delimiter-balance gate confirmed by `parse-forms`). One form per turn; its
  value is in the transcript when the agent continues. Aborting loses the
  provider's final usage chunk, so those turns carry client-side token
  estimates (`seon.ai.tokens/estimate`), flagged `:seon.agent.turn/usage-estimated?`.

**The mode teaches its own grammar (colocation).** The instruction that
describes the mode renders WITH the transcript block it governs — the masthead
carries `:batch`'s "a result you type is stripped" OR `:stream`'s "your turn
ends at your first complete form," gated by the same datom. The other mode's
text is *absent*, not contradicted. This is the general rule: an instruction
that could conflict is gated by DB-derived state, so it renders exactly when
the state it describes holds — the same reactive-context discipline as the
warning blocks. Every turn persists the cost (`prompt_cache_hit_tokens` /
`prompt_cache_miss_tokens`, forms, `:seon.agent.turn/results-stripped`) so the
two modes are comparable on identical tasks.

## A render fn is a block and a tile — the twins

A `defn` whose input accepts the db and whose output carries a render key is
a **renderer**, and the keys present decide where it goes:

- `{:seon.render/ai …}` → a **block**: its string joins the agent's prompt.
- `{:seon.render/html …}` → a **tile**: its own hiccup surface on the
  agent's page (each block has its own separate tile — not a merged vector).
- **both keys → twins**: one value, two projections — the agent's context
  and the human's screen showing the same thing.

The **canvas** is a distinct, focal tile. Default: it shows the
**last-updated tile** — a pure function of the db
(`seon.agent.ctx.render-fns/last-updated-tile`): among the agent's own
authored tile fns (its `:seon.fn` rows whose tx provenance names the agent
and whose output schema declares the hiccup twin), the one most recently
*touched* — redefined, or a write (or retraction — the history view) to any
attr in its declared read-set (the stored `:seon.fn/read-attrs` — the
qualified keyword literals the tee walked off the read form; a regex over
the source text only for pre-structural rows), read off the datoms'
tx column. So the human's focus follows what the agent is actively doing
with zero ceremony: author a plan tile, write plan data, and the plan tile
is the canvas. Override: the agent pins the canvas to a specific tile
(`:seon.render.live-tile/content`) to feature it regardless of recency;
retract the pin to fall back to derived; with neither, the core welcome.
Derive the default, store only the pin — the same rule as everywhere else.
(Honest bound: a tile that reaches attrs only dynamically — never naming
them — follows only its own redefinitions.)

This is the block's two renders (`:seon.render/ai` / `:seon.render/html`),
now emitted by any in-scope `defn`, not only by seeded blocks. Its args are
the db value (all data is reachable from it — [[think-in-clojure]]); it
`require`s only the *code* it calls. It is pure over the frozen db, so it
re-runs safely every turn, is bounded + errors-as-values through the exec
service (a throw becomes a `:seon/error` tile, never a crash), and replays
byte-identically at `as-of t` ([[observability]]).

## Shared view — the agent knows the human sees it

Because the *same* function feeds both the prompt and the tile vector, the
agent and the human look at one derived value. An agent working in `my.plan`
runs its plan-view `defn`: the `:ai` twin puts the full plan in its own
context, the `:html` twin puts the full plan on the human's page. The agent
can rely on "my human is seeing this" — it is structurally true, no
messaging required. Planning in full detail *is* showing the human the plan.

## What puts a fn in scope — writing it, or pinning it

Two ways, one mechanism (a render fn run over the db); they differ only in
what makes the fn visible:

- **Being in its namespace (derived, zero ceremony).** The render fns of the
  agent's current `ns` are in scope. Authoring context is just writing a
  `defn` in the namespace it belongs to; move to that `ns` (`in-ns`, plain
  REPL) and its renderers run. Nothing stored — pure derivation from
  code-in-the-graph + `*ns*`.
- **`install!` (explicit override).** Pins a render fn to run *regardless* of
  `*ns*`, at a chosen priority, in a chosen agent's scope. Storage is the
  exception, for the non-derivable: always-on blocks (the plan anchor,
  warnings, the transcript), a hand-set priority, seeding another scope.

Rule: **derive the derivable, store only the overrides.** Both paths resolve
into one ordered list of `(render-fn, position)` the renderer walks — never
two rendering systems.

## Explicit dependencies — injected at the eval boundary

A tool or render fn's dependencies (the db, the calling agent, the current
time) are **declared in its request schema and injected once at the eval
boundary** — never read from an ambient dynamic var deep in the body. The
contract:

- A map-in fn declares an injectable as an **optional** request key —
  `:seon.db/db`, `:seon.agent/id` ("me"), `:seon.render/at` (now/basis-t),
  and whatever else the registry grows to hold. It is `{:optional true}` to
  the *caller* (may omit) but the wrapper guarantees it *present in the body*.
- On an agent call, the eval boundary inspects the fn's request schema, and
  for every **injectable key the schema declares that the caller left
  absent**, fills the current value from the eval context. Declared-and-
  present is never overwritten (explicit args win — the agent, a test, or a
  forensic replay can pass a different db/agent).
- The injectable **registry** is a small explicit map `injectable-key → (fn
  [eval-ctx] value)`: `:seon.db/db` → the turn's frozen db, `:seon.agent/id`
  → whose turn is running, etc. Adding a dependency = add one registry entry
  + fns declare the key. One mechanism; no second wrapper.
- The injectable contract has **one named request shape**:
  `:seon.render/section-request` (registered in `seon.render`) — an OPEN map
  naming exactly the registry's keys, each `{:optional true}` and referencing
  its registered schema. Every block/section/converter fn the render engine
  calls declares `[:cat :seon.render/section-request]`, never a bare
  `[:cat :map]` — the contract is greppable, and a wrong-shaped injectable
  (e.g. a string `:seon.render/at`) rejects at the instrumented boundary
  naming the schema. Open on purpose: the engine composes extra per-call
  keys (`:seon.render/node`, `:seon.agent/entity`, …); a semantically
  richer request (e.g. `:seon.agent.inspect/request`) stays its own schema.

This rides the **one instrumentation layer** (every schema'd fn whose shape
takes a wrapper is Malli-instrumented off the program graph — at boot, on
`start-agent!`, and re-asserted after every hot reload; the structural
exception is `^:async` non-simple shapes like `transact!`/`eval`, which
validate in their own body — and coverage is itself a derived invariant:
the root world's `:instrumentation-gaps` section recomputes the census per
render) — inject-then-validate-input, so the filled map satisfies
the `:map`. The result: a fn's spec IS the honest statement of what it needs,
the eval log shows real data flowing, and the value is reproducible at
`as-of t`. This is the one boundary of "clear magic" that lets `with-agent`
/ ALS stay the core's internal *source* for the injection without leaking
into every fn body.

The **scope-by-signature** rule falls out: a fn that declares `:seon.agent/id`
reads/writes **per-agent** data (it stamps `:my.plan/agent me` and filters by
it); a fn that does not is **global** (`my.kb`). You know where data goes by
reading the arglist — not from an invisible binding.

## Auto-run — the current `ns`'s render fns become context

The current-`ns` render fns don't need the agent to call them: the render
pass **queries the program graph** for fns in the current namespace whose
output schema is a render type (`:seon.render/ai` / `:seon.render/html`) and
**runs each through the same injecting wrapper** (they're map-in fns declaring
`:seon.db/db` + `:seon.agent/id`), bounded + errors-as-values. Their outputs
are the block/tile twins, positioned right after the stable code they belong
to. So a `defn` in the agent's namespace becomes live context automatically —
authoring context is writing a specced render fn, and the injection makes it
run with no arguments the agent has to supply.

## Order = stability, so the cache holds

Position is sorted by **change-time** (a property of the var / the source),
so the prompt reads most-stable → most-dynamic and the provider prefix-cache
survives most turns. A fn busts cache only when *its own* code or *its own*
db-inputs changed — invalidation stays local to the fn that moved, because
order is deterministic:

1. **reference-code namespaces** — vendored source, effectively frozen.
2. **the agent's code namespaces** — current `ns` full source (+ its
   `require`s as compact cards, + the schemas those fns reference), sorted by
   last-modified so rarely-touched code sits earliest and edit churn sinks to
   this group's end.
3. **the current `ns`'s render fns** — the twins above; they *follow* the
   stable code they belong to. Their output moves with the db, so this is
   where the cache prefix ends.
4. **the transcript** — recent doing, windowed by age with per-band caps and
   eval-result decay; **aged clips render byte-identical forever** (re-flowing
   busts the cache — the cache-stability law). Only the leading edge moves.
   What must outlive the window goes to the DB (plan, kb, blobs), not
   transcript residue; a large inbound payload clips to a blob ref.
5. **predicted relevance, last** — the only recompute-every-step region, a
   capped token budget (config dial): fns whose *input* specs match the
   shapes the agent is holding (a graph query — [[think-in-clojure]] §1) and
   embedding neighbors for the current activity. Competes with nothing
   cached; vanishes when its queries return empty; every element earns its
   place in drives.

Code grows slowly against tokens spent running things, so groups 1–2 are the
compounding asset: as the agent persists schemas, fns, and tests, its own
code becomes the majority of its context — self-reinforcing, cheap, cached.

## Multi-agent sections — subagents + orphaned-agents

Two derived sections make the spawn tree visible without any registry or
notification state (both pure fns of the db, both vanish when their query is
empty — the reactive rule):

- **`:subagents`** (general agent-context, volatile tail near the transcript) —
  the **direct** children the rendering agent spawned (`:seon.agent/parent` =
  me; NOT the whole subtree). One compact line each: id · derived state · purpose
  · and, running → `turn i/limit` + last-beat age; idle with a completed latest
  run → the run's `:seon.agent.run/result` (+ a ref pointer); closed abnormally →
  the `closed-reason` (a parent MUST see a child that DIED, not just one that
  succeeded). A breaker-tripped child shows it. This is the parent's monitoring
  surface: completion is a **fact in the DB**, so a parent that was mid-turn or
  restarted still sees every child result — no acknowledgement, nothing to clear.
  Childless agents render empty → it costs them zero and rides the general
  manifest (root gets it too).
- **`:orphaned-agents`** (root-only, config-injected via
  `:seon.config/root-context` like `:core-faults`) — live agents whose
  `:seon.agent/parent` is **terminated**. One line each (id · state · purpose ·
  parent id). No action machinery — root (or the human) decides per case with the
  existing verbs (no cascade-terminate, no reparenting: observe first).

## Inspectability — the human twin of every position

The `:html` twin means every context position has a view the human can
inspect: the per-block prompt-text + hiccup panes with per-block token counts
(`/agent/{id}/debug`), the agent's page showing the same tiles, and — through
[[observability]] — the exact historical context of any turn (`inspect/turn`,
`turn-diff`, the prompt at `as-of t`, the prompt blob as byte ground truth).
The human debugging an agent and the forensic agent debugging it read the
same derived views.

## Configuration

Every dial is manifest data (`:seon.config/*`): which namespaces render full,
the presence-set pins, the transcript band schedule + decay, render caps, the
predicted-relevance token cap, per-agent overrides in agent scope. Absent
config = the default seed, byte-identical. No env-var side doors.

**Config-through-DB (the whole surface, not one dial).** The manifest is a
SEED FILE, not a runtime dependency. At boot `seon.config/resolve-config-singleton`
resolves EVERY knob to its effective value (env→manifest→default) and the
`#{:config}` `state/reconcile!` transacts them as ONE `:seon.config` singleton
entity (`[:seon.config/id "cluster"]`) — the SAME reconcile routes and skills
ride, so a removed key heals on the next boot and the singleton is
retract-protected by riding the desired set. From there EVERY runtime read is a
db query: the accessors (`config/eval-render-cap`, `config/on-core-error`,
`config/web-policy`, `config/namespaces-policy`, the dials …) keep their names
+ arities but read `config/config-view` — the seeded singleton datom (a
db-value-keyed memo collapses a turn's reads to one entity pull), falling back
to the boot manifest resolve only for the pre-conn sliver (the `on-core-error`
dial can fire during store-connect). `seon.config` cannot require `seon.db`
(the require dir is db→error→config), so `seon.db` INJECTS the reader — the
same seam pattern as `seon.error`'s db-hooks. Three collection knobs
(`:seon.config/always`, `:seon.config.repair/classes`,
`:seon.agent.web/allowed-domains`) ride the mixed-`:or` EDN-slot bridge (the
`home-requires` precedent) — one cardinality-one datom that upsert replaces.

Two payoffs this unlocks: a dial is now **replay-visible** (a `cluster fork`
at a basis-t BEFORE a dial change renders with the OLD value — config lives in
history) and **live-tunable** (a `db/transact` of the singleton changes the
next prompt with no file edit). `:seon.config/repl-mode` and the transcript's
tier/decay datoms are the precedents this generalizes to the whole config
surface.

## See also

- [[ui]] — the block, its two renders, the tile vector, `install!`/`remove!`,
  the live channel.
- [[data-model]] — `my.plan` (the worked example: its plan-view `defn` is the
  twin an agent sees and the human watches), the `my.*` schemas.
- [[observability]] — turn record, replay verbs, the blob store.
- [[laws]] — cache-stability, render-prominence, always-on-beats-skills.
- [[think-in-clojure]] — a fn's specced in/out is the query substrate for
  both rendering and running.
