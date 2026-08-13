---
type: issue
status: resolved
severity: friction
tags: [issue, agent, schema, database, wave/evolving-session-prd]
---

# Agent plan has no declared database relationship

## Problem

The evolving-session T2 exploration requires another database user to update
an agent's plan and for generation to derive that change and its transaction
provenance. The current registry has no agent plan attribute or referenced plan
shape, so that exact fact cannot be transacted or queried.

## Evidence

`resources/seon/schemas/seon.cluster.agent.edn` declares the agent's identity,
namespace, run, cluster, and instruction refs, but no plan relationship. A
registry/tree search found only `:seon.cluster.run/plan-digest`, which is the
frozen model-reply plan and has different semantics.

The exploration used the nearest existing durable relationship,
`:seon.cluster.agent/instructions`, to falsify the control law. A root-authored
transaction added one referenced instruction with tx metadata; the provenance
delta correctly derived `root` and `repl`, while
`seon.cluster.work/next-agent-work` stayed `nil` before and after. That proves
the passive-change law, but it does not supply the missing plan fact.

## Owner

The forthcoming evolving-session PRD must define whether "plan" is an
existing declared shape reached through another connection or a genuinely
missing attribute. Schema discovery must precede adding one.

## Scheduling note — 2026-08-12

Skipped by the evolving-session defect-clear wave because this note names a
design decision, not a settled source owner. The evolving-session PRD must
first rule whether the intended relationship reuses an existing declared
shape or adds a new attribute; production schema edits before that ruling
would silently choose semantics the issue leaves open.

## Acceptance

- The intended plan meaning is named and queryable through a declared
  attribute plus referenced shape.
- A transaction by another database user is discoverable from the changed
  datom's transaction metadata.
- Changing the plan alone derives no agent work; a separate message is the
  only model-turn wake.

## Resolution — 2026-08-13

Resolved by `d8a545a3e`. The fact-first plan landing declares authored plan
items as database entities connected to their agent through
`:my.plan.item/agent`, with an optional `:my.plan/anchor` ref on the agent
entity. `my.plan/add!`, `complete!`, and `plan!` transact those facts with
`:seon.db/user` transaction metadata, so another database writer can update
the same declared facts and provenance remains derivable from the changed
datom's transaction.

Source verification at committed HEAD found that `my.plan/plan` derives the
current authored plan from those refs, while
`seon.cluster.work/next-agent-work` still derives work only from held runs,
message triggers, and unanswered background results. No `my.plan` fact is a
wake input, so a plan-only transaction remains passive until a separate
message arrives.
