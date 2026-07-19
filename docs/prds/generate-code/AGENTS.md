---
type: orchestrator
status: active
tags: [orchestrator, prd, agent, flow]
---

# Goal-driven code generation — working context

This PRD owns the proposed `seon.ai/generate-code!` workflow: a small calling
agent states an existing `my.plan` goal, description, and expected behavior; a
specialized planning agent answers through the ordinary Seon REPL; namespace
requirements derive dependency order; and database-driven scheduling delegates
only dependency-ready repair work.

The current phase is research and design review. Do not implement the public
function, migrate namespace-name values, or add orchestration attributes until
the owner approves [[roadmap]]. Record executable probes and source evidence in
`research/`. Reuse `my.plan`, `seon.agent` messages/runs, `seon.repl.internal`,
`seon.eval/eval-batch!`, and `seon.reactive/observe!`; do not create a parallel
plan language, parser, task registry, event bus, or worker lifecycle.

Namespace names in the target model are complete symbols such as
`my.library.model` (Malli `:symbol`, not `:qualified-symbol`, which implies a
slash). The current database uses keyword values for `:seon.ns/name`; Datahike
cannot change that installed value type in place. The conversion is therefore
a coordinated foundational migration and database-rebuild boundary with its
own inventory and proof gate. Do not add keyword/symbol coercions.

The source-fenced projection is invocation-local ordinary data. Persist only
the existing model reply, eval/program facts, plan steps, messages, and runs.
Completion is derived from eval and behavioral-test evidence; never recognize
an agent's prose marker as completion.
