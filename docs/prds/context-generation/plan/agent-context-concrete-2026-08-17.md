---
type: prd
status: draft
tags: [prd, agent, context, render]
---

# The minimal opening, v0 — for live testing

*Rewritten minimal 2026-08-17 late (owner: too many inaccuracies to
follow; start minimal, live-test). LEGEND: everything in this file is
marked **[REAL]** (exists at HEAD, verified) or **[PROPOSED]** (to
build/decide). Nothing unmarked.*

## 1. The evergreen intro — the ONLY authored prose **[PROPOSED bytes]**

One instruction entity, generic, scenario-free:

```text
You output comments and Clojure forms. Comments are your thinking;
every form is executed, in order, in a live Clojure REPL, and you see
each actual result next turn. You are the owner of the namespace
<ns>: maintaining its functions, schemas, and tests, and doing what
the namespace is intended to do, is yours. A defn with :malli/schema
metadata becomes a permanent part of the system; anything else is
scratch. (help) describes your current world; (dir ns) lists any
namespace; (doc sym) explains anything. End every run with
(my.run/complete "…") or (my.run/wait).
```

Eight sentences. Everything else the agent ever reads is GENERATED
from docstrings, schemas, and live facts.

## 2. `(help)` v0 — assembled from docstrings only **[PROPOSED assembly; REAL parts marked]**

Help output = four generated sections, no authored text:

1. **Who**: agent id, namespace, goal (§4) — from the entity.
   [REAL: agent entity with `:seon.cluster.agent/id`, unique
   `/namespace` ref, `/run` ref.]
2. **Your namespace**: the ns docstring [REAL: `:seon.ns` rows carry
   source; docstrings indexed], then per public fn: name, one-line
   doc, in/out schema [REAL: `:seon.fn/doc`, `:seon.fn/spec` indexed —
   this is exactly today's dir face content, visible in the live
   capture].
3. **Your requires, one line each**: ns name + its docstring first
   line [REAL: requires are `:seon.ns/requires` refs]. Deeper = the
   agent runs `(dir ns)` itself.
4. **Your data, as commands** [PROPOSED — the affordance lines]:
   per attribute group on/around the entity: the group key, count,
   newest instant, and the call that reads it. Counts/newest are
   index-free-ish [REAL: measured — all ref-attribute AVET slices,
   735 µs; datoms carry :tx].

Rules: no eager content except the trigger message; results always
bare (ruling 45); every line derives from an indexed fact.

## 3. The live test **[PROPOSED — the next concrete step]**

Cheapest probe per standing rules: one temp agent, the v0 intro +
help, one small real task, DeepSeek, watch verbatim what it does.
GATED on the platform-tier repair (the fresh cluster's bootstrap run
died to the loop refusal — filed; a drive cannot settle turns until
that is fixed). The drive's question: does the agent, given ONLY §1 +
§2, discover its world and act correctly? Confabulations and stalls
name the missing line — we add generated lines, never scenario prose.

## 4. The goal — still open; options for the owner **[DECISION]**

The namespace's PURPOSE is evergreen and already has a home: the ns
docstring [REAL], rendered by §2.2. The agent's episodic GOAL:

- (a) **the root plan item** — creation authors one `my.plan` item
  (title = the goal) `:about` the namespace. Uses the existing
  "plan = externalized intent" machinery: renders with status,
  completes explicitly, survives rebirth, no new attribute.
  RECOMMENDED.
- (b) a plain `:seon.agent/goal` string attribute — simplest, but
  dead: no status, no completion, second home beside the plan.
- (c) an instruction-entity ref — prose home, but goals are work, not
  rules.

## 5. Many agents, one namespace **[DECISION + PROPOSED schema change]**

Owner direction: agents may work on the same namespace — SCI forks
isolate them [REAL: proven by tonight's fork-isolation regression];
the database must allow it. Change: DROP the uniqueness on the
agent→namespace ref [REAL today: unique — one agent per namespace];
an agent stays its own entity carrying whatever we attach.
Sub-decision for markup: does OWNERSHIP (who merges/commits the
namespace's canonical state) remain a distinguished single agent —
e.g. a separate unique `owner` ref on the NS entity — with other
agents as workers whose changes ride the candidate-branch/merge
gate from the isolation-and-merge design? RECOMMENDED: yes — many
workers, one owner, merge by gate.

## 6. What was cut from the previous draft

The invented render-function names, the fleet/page transcripts, and
the four-level worked examples — all ahead of the evidence. They
return only after the §3 live test tells us what the agent actually
needed.
