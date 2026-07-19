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

The current phase is the approved namespace-symbol foundation in Stage 1 of
[[roadmap]]. Record executable probes and source evidence in `research/` before
each implementation boundary. Reuse `my.plan`, `seon.agent` messages/runs,
`seon.repl.internal`, `seon.eval/eval-batch!`, `seon.reactive/observe!`, and the
existing `:namespaces` context block; do not create a parallel plan language,
parser, task registry, event bus, worker lifecycle, or namespace renderer.

Namespace names are complete symbols such as
`my.library.model` (Malli `:symbol`, not `:qualified-symbol`, which implies a
slash). `:seon.ns/name`, `:seon.ns.require/target`, and `:seon.eval/ns` use
that representation directly. Datahike cannot change an installed value type
in place, so applying this source boundary requires a fresh database rebuild.
Do not add keyword/symbol coercions.

The source-fenced projection is invocation-local ordinary data. Persist only
the existing model reply, eval/program facts, plan steps, messages, and runs.
Completion is derived from eval and behavioral-test evidence; never recognize
an agent's prose marker as completion.

The specialized developer teaching is one progressive `:generate-code` block
that derives its view from assignment/eval/test facts and renders nothing when
inactive. Full code remains the responsibility of the existing `:namespaces`
block by reconciling its `:seon.agent.ctx.namespaces/full-source` set. Model
selection extends the existing database-owned provider resolution with named
profiles; never branch on provider at the `generate-code!` call site.
