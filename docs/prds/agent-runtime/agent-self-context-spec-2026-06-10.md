---
type: prd
status: draft
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
priority sort. A section is either literal text (static doctrine) or a
`:seon.render/ai` symbol pointing at a function — usually one the agent
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
  :seon.ctx/text     "Always reconcile against my.finance.ledger
                      before answering balance questions."}

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
- EXACTLY ONE of:
  - `:seon.ctx/text` — string, rendered verbatim. The "just a string"
    case: doctrine, notes-to-self, standing reminders.
  - `:seon.render/ai` — qualified symbol (symbol-only at storage, the
    existing render-slot spec), resolved LATE by the existing
    materializer, called as a pure fn of the db (+ agent ref) at every
    render. Optional `:seon.render/html` twin gives the same section an
    inspector/tile view through the same dispatch.

What dies: `:seon.ctx/fn` (redundant twin of `:seon.render/ai` — one slot
attr everywhere); the planned-but-rejected `:seon.agent/instructions` /
`:seon.agent/ctx-additions` attrs (instructions are just low-priority
text sections); the stored-ctx-REPLACES-defaults semantics.

## Render semantics (in `seon.ctx`, the V3-C engine)

```
sections = sort-by priority (substrate-default-sections ∪ agent's :seon.agent/ctx)
render   = for each: text → verbatim | slot → (materialize :seon.render/ai) db agent-ref
```

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
  {:seon.ctx/name :doctrine :seon.ctx/priority 15 :seon.ctx/text "…"})
;; => {:seon.agent/ok? true :seon.ctx/name :doctrine}     ; envelope, like todo
(seon.agent/remove-section! {:seon.ctx/name :doctrine})
```

Same envelope discipline as `seon.agent.todo` (errors are values; blank
text refused with a guiding message; unknown name on remove → error
naming `list` of current names). Default scope = the calling agent;
explicit `:seon.agent/id` allowed (the human or another agent can
configure an agent — it's all just transacts anyway, the verb is the
validated path).

## Show don't tell — how agents LEARN this

1. **Seeded worked examples** (at `create!`): every new agent's entity
   ships with one text section whose text IS the customization hint
   ("This is your doctrine section. add-section! adds more; sections
   with :seon.render/ai point at functions you write.") and one tiny
   working computed section. The examples ARE config — copyable shapes,
   not prose about shapes.
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
