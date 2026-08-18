---
type: prd
status: draft
tags: [prd, agent, context, render]
---

# Two agents' contexts, concretely — the bytes we intend to generate

*TARGET artifact for owner markup, 2026-08-17. This is what the
generated `/ai` context should literally look like for (1) a normal
agent and (2) root — so we can judge believability before building.
Names use the post-ruling-43 renames (`seon.message/*` for the stored
family; adjust in markup). The one rule enforced throughout: an entry
is `(render-fn (query …))` — the printed result IS the rendered view;
raw data never prints beside it; digging = the agent runs the inner
query alone.*

## How the pairs are found — one query, no magic

The opening's first system move (after the ns form) is ONE call whose
result is the work list — data groups paired with their most-specific
render function:

```clojure
(seon.render/plan db 'my.agents.k3f9)
;; ⟹ [[:seon.message/_to      my.message/render-inbox]
;;    [:seon.agent/run        my.run/render-run]
;;    [:seon.agent/plan       my.plan/render-plan]]
```

`seon.render/plan` is: the three index scans (own attrs, refs-in
grouped by attribute, refs-out grouped by attribute — measured ~1 ms
total) joined against the face-candidates map (cached per schema×code
generation, four specificity levels). Groups with no face are simply
absent from the plan — reachable by query, unrendered (owner rule).
The generator then EXECUTES each pair as one entry. That is the whole
algorithm.

---

## 1 · A normal agent — `my.agents.k3f9`

```clojure
my.agents.k3f9=> (dir 'my.agents.k3f9)
(ns my.agents.k3f9
  (:require [my.message] [my.run] [my.plan] [seon.db]))
;; Your namespace. The requires above are your toolkit — added for you
;; at creation; grow them with ordinary (require …).

my.agents.k3f9=> (dir 'my.message)
my.message/send      [to content] → message        ; address another agent
my.message/render-inbox [messages] → string        ; the inbox view
my.message/read      [id db] → message             ; one full message
;; (dir 'my.run) and (dir 'my.plan) follow the same way…

;; Your world: data on your entity, pointing at you, pointed to from
;; you — each paired with its most specific render function:
my.agents.k3f9=> (seon.render/plan db 'my.agents.k3f9)
[[:seon.message/_to my.message/render-inbox]
 [:seon.agent/run   my.run/render-run]]

;; Executing each pair — the result IS the view; run the inner query
;; alone anytime to see the raw data:
my.agents.k3f9=> (my.message/render-inbox
                   (seon.db/refs-in db 'my.agents.k3f9 :seon.message/to))
2 messages, newest first (basis t 536871204)
  [1] sean · 15:04 · "can you graph my sleep data? it's in
      :acme.sleep/reading entities" · id "chat-3f9-1"
  [2] root · 15:06 · "welcome — your namespace is yours; define
      contracted functions to keep them" · id "welcome-3f9"

my.agents.k3f9=> (my.run/render-run
                   (seon.db/ref-out db 'my.agents.k3f9 :seon.agent/run))
run open · turn 1 of 96 · opened 15:06 · because of message "chat-3f9-1"
end with (my.run/complete "…") or (my.run/wait)
```

Six entries, ~40 lines, nothing raw, nothing narrated, every view one
`(render-fn (query))` the agent can decompose. The plan group was
absent because the plan is empty — empty queries render nothing.

**k3f9's HTML page** (`/ns/my.agents.k3f9`): the namespace entity's
`/html` face resolves at level 1 — the schema-declared default
`seon.render.page/render-namespace-html` — which lays out: transcript
primary (it changes most), the same two rendered groups as side
panels. Nothing was configured.

---

## 2 · Root — `my.agent.root`

Root's DIFFERENCE is only its requires and one function it owns.

```clojure
my.agent.root=> (dir 'my.agent.root)
(ns my.agent.root
  (:require [my.message] [my.run] [my.agents] [seon.db]))

my.agent.root=> (dir 'my.agents)
my.agents/create        [namespace-name] → agent   ; new agent = new namespace
my.agents/render-fleet  [agents] → string          ; every agent, one line each
my.agents/assign        [agent-id ns-name] → agent ; move an agent to a real ns

my.agent.root=> (seon.render/plan db 'my.agent.root)
[[:seon.agent/_cluster my.agents/render-fleet]     ; ← reachable only because
 [:seon.message/_to    my.message/render-inbox]    ;   root REQUIRES my.agents
 [:seon.error/_agent   seon.error/render-errors]]

my.agent.root=> (my.agents/render-fleet
                  (seon.db/refs-in db 'seon.cluster :seon.agent/cluster))
3 agents (basis t 536871210)
  my.agents.k3f9   · run open, turn 1  · last active 15:06 · 2 msgs in
  my.agents.b7c2   · waiting           · last active 14:51
  notes-keeper     · run open, turn 12 · maintains my.note

my.agent.root=> (my.message/render-inbox
                  (seon.db/refs-in db 'my.agent.root :seon.message/to))
1 message, newest first (basis t 536871210)
  [1] my.agents.k3f9 · 15:07 · "what data do I have access to?" · id "q-3f9-2"

my.agent.root=> (seon.error/render-errors
                  (seon.db/refs-in db 'my.agent.root :seon.error/agent))
2 undisposed error groups (57 occurrences coalesced by signature)
  [1] :seon.operator/process-census-incomplete · ×31, hourly since 08-14
      · dispose with (seon.error/dispose "b61a…") after fixing
  [2] :seon.instrument/contract-violated seon.fs/delete-recursively!
      · ×26 · same disposal
```

**Root's HTML page** (`/`): the SAME namespace-entity face slot,
resolved at level 3 — root's own namespace defines
`my.agent.root/render-page-html`, which OVERRIDES the level-1 default:
fleet cards primary, transcript beside. That is the entire "root sees
all agents" story — one function root owns, beating a default, through
the same resolution every value uses. A normal agent that never
defines a page face gets the default forever.

---

## The names, where they live, and the two kinds of require

| Family (stored keys) | AI face | HTML face | Lives in | Reaches the agent by |
|---|---|---|---|---|
| `seon.message/*` | `my.message/render-inbox` (collection), `render-message` (one) | `render-inbox-html`, `render-message-html` | `my.message` — the family-owning ns | toolkit require (creation-seeded, agent-growable) |
| `seon.run/*` | `my.run/render-run` | `render-run-html` | `my.run` | toolkit require |
| `my.plan/*` | `my.plan/render-plan` | `render-plan-html` | `my.plan` | toolkit require |
| agents (fleet) | `my.agents/render-fleet` | `render-fleet-html` | `my.agents` | ROOT's require only — which is why only root's plan contains the fleet group |
| errors | `seon.error/render-errors` (coalesced by signature) | `render-errors-html` | `seon.error` | always loaded (system) — level 1 default |
| the namespace entity (THE PAGE) | — | default `seon.render.page/render-namespace-html`; overridable per agent by defining `render-page-html` in the own namespace | default in core; override in the agent's ns | level 1 vs level 3 |

**The two kinds of require, distinguished:** a library namespace's
`(:require …)` is code dependency — what its functions need to
compile. An AGENT namespace's requires are its TOOLKIT — which
families it can see rendered (the fleet group exists for root only
because root requires `my.agents`) and which verbs `dir` teaches it.
Same mechanism, and the second kind is entirely data: creation seeds
it, the agent grows it, rebirth replays it.

## What makes this believable (the checklist for markup)

1. Every printed view is decomposable — inner query runs alone.
2. Bases printed on every collection → diffs composable by anyone.
3. Errors coalesce by signature with disposal taught inline — 57
   datoms became 5 lines (the live capture's spam, killed by a face).
4. Requires explain capability differences ENTIRELY — root has no
   role, flag, or special route; it has one require and one function.
5. The `;;` narration is system-authored teaching, present in the
   OPENING only — later turns are replay + arrivals, not re-narration.
