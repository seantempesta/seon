---
type: issue
status: open
severity: friction
tags: [issue, agent, context, render, wave/agent-context]
---

# Separate lab observations from hypotheses the lab should test

## Evidence — 2026-09-05

Read the [design-lab PRD](../../prds/context-generation/plan/design-lab-prd-2026-09-05.md)
and [agent-centric design](../../prds/context-generation/plan/agent-centric-design-2026-09-04.md)
end to end during the owner's brainstorming orientation. These are draft
consistency findings, not permission to impose a final architecture.

- Lab §3 and §4.2 display zero, one, or several matching entity schemas;
  wave 1A instead requires a refusal for several entity schemas. Observing
  several matches and choosing a renderer are different decisions.
- Lab ruling 6 and §5 put the first agent in `my.note`; §11 still gives it
  a separate home namespace and stewardship. The latter contradicts the
  expressly simplified first world.
- Lab §4.2 equates required-attribute presence with schema satisfaction.
  Presence alone does not establish that the values meet those contracts.
- The agent-centric design §3.2/§3.3 wants data usable by all projections,
  but §3.4–§3.5 describes wrapped query results as already rendered text.
  The experiment must expose the raw query value separately from its rendered
  output, and establish which value a result handle denotes. This is an open
  design question, not a proposed storage requirement.

## Questions to explore with the owner

Contract compatibility establishes what a function can accept. What determines
the wanted grouping, detail, and selection for a particular consumer? Try one
note, a collection of notes, and an agent view containing that collection.
Observe what a replacement at each level changes. Keep automatic discovery,
explicit composition by a parent function, and their combination available as
hypotheses until the owner has seen the behavior.

SCI bindings, prompt text, and HTML may share facts and composition without
requiring identical intermediate entries. The lab should test the useful shared
boundary rather than make a shared entry representation an unexamined premise.

## Live orientation evidence

At checkout `f612c572ac9a8d445570fcb9377d486f297b6d4a` with pre-existing
source/test edits, `bin/seon status` reported `ctxprobe`, pid 69165, alive.
MCP default status selected absent `default` and returned no clusters; explicit
`runtime_status` for `ctxprobe` returned observations. Explicit JVM-mode
`eval_clj` of `(+ 1 2)` returned 3 in 2 ms. Status reported 3 error signatures,
6 errored receipts, 1 failed run, and 7 stale Vars. Connectivity is proven;
current-source correctness and the design projections are not. No cluster
was reset, no agent turn executed, and no base-system repair attempted here.

## Owner and acceptance

The design-lab PRD owns these clarifications. Resolve its contradictory first
world and schema-observation behavior; distinguish candidate schema matches
from validated matches; demonstrate raw value versus projection and result
handle meaning. Record the owner's composition choice only after exploring
concrete examples. This note does not introduce another implementation schedule.
