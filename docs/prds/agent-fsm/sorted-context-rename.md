---
type: prd
status: draft
tags: [prd, agent, web]
---

# #26 — Rename `:seon.agent/sections` + per-agent namespace show-list (the gym-quantified bloat fix)

/ Two coupled changes the owner asked for. (A) is a pure rename; (B) is the
/ real payoff — cut the `:namespaces` section from ~37k tokens (84 % of the
/ prompt) to a small, per-agent, runtime-configurable set. DRAFT — the
/ keyword NAME (A) is the owner's call; the DEFAULT show-list (B) is a gym
/ tuning decision. Everything else below is settled enough to build.

## TL;DR

- **A. Rename `:seon.agent/sections`.** The attr is misnamed. It is not
  "sections" (UI-layout jargon) — it is the agent's **sorted, renderable
  context items**: a vector of maps, each `{:seon.ctx/name, :seon.ctx/priority,
  :seon.render/ai <sym|str>, :seon.render/html <sym|str>, …data}`. It drives
  BOTH the AI prompt (`:seon.render/ai`) and the HTML UI (`:seon.render/html`)
  — it is the single source of "what the agent and the user both see." New
  name is the owner's pick (see **Open: the name**).
- **B. The bloat fix.** Today `seon.ctx.namespaces/namespaces-section` renders
  FULL source for a GLOBAL hardcoded `full-source-whitelist` (6 seon.* nses) +
  every `my.*`/`acme` ns + the agent's current ns. The gym measured this one
  section at **148 889 chars ≈ 37k tokens = 84 % of the prompt**. Make the
  show-list **per-agent data carried by the `:namespaces` context-item
  itself**, defaulting SMALL, mutable at runtime. The agent adds a ns to its
  own show-list when it switches to / wants to read another ns's source. The
  source stays indexed + grep-able regardless (`index everything`); the
  show-list only controls what gets DUMPED FULL into the prompt
  (`don't show everything`).

The two are one idea: the namespace config IS one of the sorted-context
items' data, and `namespaces-section` is its wired render fn. This is the
reactive-context pattern — the section is a pure fn of the agent's own stored
config; change the config, the section re-derives.

## Why now

Gym scorecards (commit `de6c769`, `:seon.gym.profile/section-chars`) on every
scenario: `[:namespaces 148889]` against a whole-prompt of ~177k chars. The
`:soul` block is 7 735, everything else is <2k. The namespaces section is the
entire budget. The wrong metric (or an un-tuned context) leads the gym off a
cliff — so this is the lever that matters before more live drives.

## A. The rename (mechanical, cross-lane)

`:seon.agent/sections` → `<owner's name>`. Touch sites (grep `:seon.agent/sections`):

| File | Role | Lane |
|------|------|------|
| `src/seon/agent.cljs:165` | `schema/register!` (the `:db.type/ref` vector) | R |
| `src/seon/agent.cljs` (~516–685) | the ctx-editing verbs (`update-ctx!`, retract-then-add) | R |
| `src/seon/ctx.cljs` (~853–864, 1707–1715, 1816) | `agent-sections`, the composer union | R |
| `src/seon/client.cljs:351` | seed/replay of the vector | R |
| `src/seon/web/**` | the HTML render of the vector (tiles) | **U** |

Cross-lane → coordinate with U before landing (the rename is a single atomic
literal-rename + fresh-suite verify, no shims). The `:seon.ctx/*` item keys
(`name`/`priority`/`:seon.render/ai`/`:seon.render/html`) do NOT change — only
the AGENT attr that holds the vector.

## B. The per-agent namespace show-list

Today (`seon.ctx.namespaces`):

```
render-full? nm cur-ns  :=  (= nm cur-ns)               ; current ns
                          || (full-source-ns? nm)        ; my.* + GLOBAL whitelist
                          || (third-party-ns? nm)        ; acme business code
full-source-whitelist   :=  #{:seon.agent.todo :seon.db :seon.agent.search
                              :seon.agent.fs :seon.agent.message
                              :seon.agent.lifecycle}     ; a hardcoded def

```

After: the seon.* whitelist becomes **per-agent data** on the `:namespaces`
context-item. The `:namespaces` core-default
(`seon.ctx/core-default-ctx`, priority 20) carries a small default
show-list slot; `namespaces-section` reads it from the section map the
composer already passes as render input (`{db, id, entity, section, model}`)
instead of the global def. The `my.*` / current-ns / third-party rules stay
(they are the human's world + the agent's working ns — always full).

```clojure
;; core default — small, explicit, the editable surface:
{:seon.ctx/name :namespaces :seon.ctx/priority 20
 :seon.render/ai 'seon.ctx.namespaces/namespaces-section
 :seon.ctx/show-source #{:seon.db}}            ; DEFAULT show-list (tune in gym)

;; namespaces-section reads (:seon.ctx/show-source section) ∪ {cur-ns}
;; ∪ my.* ∪ acme — the global full-source-whitelist def is DELETED.

```

The agent grows/shrinks its own show-list by overriding the `:namespaces`
item by name in its renamed sorted-context vector (the existing
override-by-name merge — `update-ctx!`). "Switch to a new ns / want its
source" = add the keyword to `:seon.ctx/show-source`. The framework tools it
is NOT currently using stay indexed + searchable (`seon.agent.search`,
`render-namespace`) — one grep away, just not dumped.

### Open: the DEFAULT show-list (gym tuning, not a blocker)

Candidates: (a) `#{:seon.db}` only (the one API every agent uses; everything
else on demand); (b) signatures-only for the 6 current tools + full only for
current-ns/`my.*`; (c) keep the 6 but as signatures. Decide by gym drive —
the metric is "does the agent still find/call the verbs it needs with the
small default?" Build the mechanism first with `#{:seon.db}`, then sweep.

## Open: the name (OWNER'S CALL — the one blocker)

Candidates floated: `:seon.agent/context`, `:seon.agent/tiles`, other. It is
the agent's sorted, dual-rendered (ai+html) context vector. Recommendation
below in the chat; not deciding it here.

## Sequencing

1. Owner picks the name → A (atomic rename, R, U-coordinated).
2. B mechanism with `#{:seon.db}` default (R) — delete `full-source-whitelist`,
   read the section slot, spec it.
3. Gym sweep to tune the default show-list + confirm agents still call verbs.
4. (B already makes the prompt ~37k smaller → cheaper, faster live drives.)
