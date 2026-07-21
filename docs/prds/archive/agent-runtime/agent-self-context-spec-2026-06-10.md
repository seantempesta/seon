---
type: prd
status: active
tags: [prd, agent]
---

# Agent self-context — unified spec (2026-06-10)

Agents develop into specialists by modifying their own persistent context —
instructions, custom views, and the code behind them — using the SAME
mechanisms the substrate uses for everything else. One render system, one
attr, one section shape. Nothing new is stored except datoms and code.

## The model in one paragraph

Every agent IS an entity in the cluster store. That entity carries the
agent's own context sections under the EXISTING `:seon.agent/ctx` attr — a
vector of section maps MERGED with the substrate's default sections by one
priority sort. A section's `:seon.render/ai` slot holds either a literal string
(static doctrine) or a symbol pointing at a function — usually one the agent
wrote in its own `my.*` namespace, which already persists and replays via
the program graph. Pull one entity and you see the whole specialist; edit
it (agent or human) by transacting; restart changes nothing.

## Storage shapes

```clojure
;; ON THE AGENT ENTITY — the one self-context attr (existing, semantics
;; updated): the agent's OWN sections, merged with defaults, NOT a
;; replacement (the old stored-ctx-overrides behavior dies — it froze
;; substrate layout improvements into per-agent copies).
:seon.agent/ctx
[{:seon.ctx/name     :doctrine
  :seon.ctx/priority 15                      ; same scale as defaults
  :seon.render/ai    "Always reconcile against my.finance.ledger
                      before answering balance questions."}  ; string = verbatim

 {:seon.ctx/name     :my-positions
  :seon.ctx/priority 46
  :seon.render/ai    'my.finance/positions-section   ; agent-authored fn
  :seon.render/html  'my.finance/positions-html}]    ; optional UI twin
```

Section map contract (registered schema, validated at `add-section!` AND
at `transact!` like everything else):

- `:seon.ctx/name` — keyword, unique within the agent's own vector
  (re-adding a name replaces that entry — upsert-by-name, so iterating on
  a section doesn't accumulate copies).
- `:seon.ctx/priority` — int, the ONE ordering shared with substrate
  defaults (system 10 … capabilities 20 … exemplars 22 … catalog 25 …
  warnings 40 … open-todos 45 … transcript 50 … prompt 99). An agent CAN
  interleave anywhere; the renderer sorts the union.
- `:seon.render/ai` — **string OR qualified symbol** (slot spec relaxes
  from symbol-only to `[:or :string :symbol]`): a string renders
  verbatim (doctrine, notes-to-self — content as source, not cached
  output, so the original symbol-only guard's intent survives); a
  symbol resolves LATE via the existing materializer to a fn called at
  every render. Optional `:seon.render/html` twin: **hiccup literal OR
  a symbol of a fn returning hiccup** — static badge or live inspector
  view, same slot. (If the malli→datahike bridge can't store
  hiccup-shaped EDN, fix the bridge — house rule.)

What dies: `:seon.ctx/fn` (redundant twin of `:seon.render/ai` — one slot
attr everywhere); `:seon.ctx/text` (never built — the relaxed slot
carries strings directly); the planned-but-rejected
`:seon.agent/instructions` / `:seon.agent/ctx-additions` attrs; the
stored-ctx-REPLACES-defaults semantics.

## Render semantics (in `seon.ctx`, the V3-C engine)

```
sections = sort-by priority (substrate-default-sections ∪ agent's :seon.agent/ctx)
input    = {:seon.db/db db, :seon.agent/entity (pull db [*] my-ref)}  ; pulled ONCE
render   = for each: string → verbatim | symbol → ((materialize slot) input)
```

**Section fns receive ONE map — the db value + the agent's own pulled
entity** (the smart default: every section is a pure fn of (the world,
myself); fns that don't need the self ignore it free; none re-pull).
Substrate default sections migrate to the same signature in P5.

- **Merge, never replace.** Substrate evolution always flows through;
  agent customization layers on top. Name collisions with substrate
  sections are allowed and mean override-by-name (an agent CAN replace a
  default section deliberately — that's the escape hatch, visible as data).
- **Guard:** a section whose fn is missing/throws renders as a one-line
  error string inside the section ("[my-positions] render failed: …") —
  never breaks assembly, surfaces loudly, self-heals when fixed.
- **Budget:** agent-authored sections share a per-agent char budget
  (start: 8k). Over budget → lowest-priority agent sections truncate with
  a loud marker line. Substrate sections are not charged to it.
- Agent text/computed sections are DYNAMIC content — they render after
  the byte-stable static prefix (cache untouched).

## The verb (toolbelt, on `seon.agent`)

```clojure
(seon.agent/add-section!
  {:seon.ctx/name :doctrine :seon.ctx/priority 15 :seon.render/ai "…"})
;; => {:seon.agent/ok? true :seon.ctx/name :doctrine}     ; envelope, like todo
(seon.agent/remove-section! {:seon.ctx/name :doctrine})
```

Same envelope discipline as `seon.agent.todo` (errors are values; blank
text refused with a guiding message; unknown name on remove → error
naming `list` of current names). Default scope = the calling agent;
explicit `:seon.agent/id` allowed (the human or another agent can
configure an agent — it's all just transacts anyway, the verb is the
validated path).

## The :purpose section — the seeded example IS the launch directive (user, 2026-06-10)

Every agent is seeded at create! with a `:purpose` section, priority ~12
(after SOUL/system, BEFORE capabilities — purpose frames everything):

- Created with a stated purpose (the web create form gains a purpose
  field; future spawner agents supply it): text = "Your human created
  you for: <their words>." Durable, rendered EVERY turn, restart-proof
  — the launch directive can't scroll away into transcript history.
- Created without one: the seed text directs the agent to acquire it —
  "Derive your purpose from your human's first messages, then update
  this section (add-section! :purpose) so you keep your direction."
  The placeholder teaches the mechanism by demanding its use.
- The agent (or the human, by transact) refines it over time —
  upsert-by-name, one datom, visible in the inspector. Purpose
  fulfilled → complete!. ANTI-DRIFT: user messages push turn by turn;
  the purpose section pulls back every render — a constant the
  transcript cannot dilute.
- NO special mechanism: `:purpose` is just a `:seon.ctx/name` value.
  The sugar verb IS the lesson — its full source is visibly a
  one-liner, teaching that purpose (and anything worth pinning) is
  just a high-priority section:

  ```clojure
  (defn set-purpose!
    "Pin or update why you exist — sugar over add-section!."
    [{text :seon.render/ai}]
    (add-section! {:seon.ctx/name     :purpose
                   :seon.ctx/priority 12
                   :seon.render/ai    text}))
  ```

## Show don't tell — how agents LEARN this

1. **The seeded :purpose section** (above) is the worked example —
   real config the agent has a live reason to read and update, not a
   demo. A second tiny computed section ships beside it as the
   fn-shaped copyable.
2. **The turn-0 demonstrated eval** (V3-E): a really-executed
   `(seon.db/pull db [:seon.agent/ctx …] my-ref)` whose RESULT shows the
   populated vector. The agent reads its own self every turn.
3. **The verbs' full source** renders once `seon.agent`'s face is shown
   (P6) — code as documentation, beside reply!/complete!.
4. **Feedback loop:** `seon.agent.inspect/ctx-preview` — write a section,
   preview your own context, iterate. REPL-driven self-development.

## Boundaries (what lives where — no overlap)

| Concern | Home | Why |
|---|---|---|
| Shared knowledge + cluster guidelines | `my.kb.*` entities (P4; `my.kb.instruction` is the worked domain) | everyone consults; user-editable doctrine for ALL agents |
| THIS agent's self-context (doctrine + custom views) | `:seon.agent/ctx` on its entity | one pull = the specialist; components die with `complete!` |
| The code sections point at | `my.agent.<id>` / `my.<domain>` fns | already persists + replays via program graph |
| The render machinery | `seon.ctx` (substrate, hidden) | one materializer for sections/kinds/tiles/context |

Interaction with lifecycle (P3.5): self-context lives on the durable
agent entity, so it survives restarts by construction; `complete!`
retires the whole specialist — config, doctrine, sections — as one
entity; un-complete restores all of it.

## Namespace honesty (P6 dependency)

`:seon.agent.message/*` etc. keyword namespaces get real code homes in
the P6 split (`seon.agent.message` owns message!/reply! + its schemas;
session/turn schemas go with the loop into `seon.agent.internal`).
`:seon.ctx/*` keywords ↔ the new `seon.ctx` ns (P5). `:seon.agent.*`
entity attrs ↔ `seon.agent` (the face). No invented prefixes remain.

## Proof (the specialist gym scenario, lands with P8)

Agent is asked to specialize ("you're my finance agent — always check
the ledger first") → it transacts a doctrine section + writes/adds a
computed section → `bin/seon restart pod` → SAME agent resumes (P3.5),
turn-0 renders its doctrine + its view → a probe question shows the
measurably different behavior. Predicates: section rows present with
correct shapes; post-restart render contains them; the probe answer
consults the ledger first.

## Unit mapping

- **P5 / V3-C (`seon.ctx`)**: merge semantics, section schema +
  validation, `add-section!`/`remove-section!`, render guard, budget,
  seeds at create!, `:seon.ctx/fn` deletion, substrate defaults
  normalized to the same shape.
- **V3-E**: the turn-0 demo pull of the agent's own entity.
- **P6**: verbs' source becomes visible; keyword/code-ns honesty.
- **P8**: the specialist scenario.
