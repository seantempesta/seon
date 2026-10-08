---
title: "Card CX-6 Delete the opening run and the demo seed"
type: reference
status: active
created: 2026-10-08
---

# CX-6 Delete the opening run and the demo seed

Ledger row CX-6 (group B, then M; replaces S4-02b2). Spec: agent-loop.md §3.4 (no opening eval request; help is the
prefix, the task and its references are current state). Tasks: `seon.cluster/new-agents-are-seeded-with-a-demo-task`
(closed by CX-6b). Lane: Sonnet. Two commits, each about 15 minutes.

## CX-6a Delete the generated opening (`src/seon/turn.clj`)

Delete, every caller converted: `declared-sources`, `generate-turn` and the `:generate` situation
(`resume-or-generate` keeps only `:resume`/`:close`), `generated-run-tx`, `append-generated-call`,
`append-generated-tx`, `system-run-plan`, `system-run-call`, `system-run-tx`, `system-run-if-admitted-call` (if their
last caller is gone), `opening-db` (the model call site reads `observed-db`) and `opening-deferred?`/
`deferred-triggers` once callerless. The `declared-sources` branch inside `system-turn` goes; `system-turn` itself
waits for CX-8. Net ≈ −400 source lines (measured function sizes at `6faffd0f7`). Tests asserting the opening
(`seon.turn-test/generated-system-runs-grow-only-after-their-settled-prefix` and kin) are deleted with it.

Proof: `seon.cluster.turn-test/a-new-agents-first-model-request-has-no-opening` (new): a new assigned agent's first
fixture-model request body has one system message (help) and a user message that is exactly the block; the agent has
no call row before it. D: no `seon.turn/generate-turn` row in `seon.pass-probe/run-model`.

## CX-6b Delete the demo seed (`src/seon/bootstrap.clj`, `src/seon/cluster.clj`)

Delete `seon.bootstrap/seed-tx`, `task-message`, `task-message-id`, `run-id` and the supervision helpers left without
callers; agent creation in `seon.cluster` transacts the agent alone. Net ≈ −120 source lines.

Proof: `seon.cluster-test/a-new-agent-has-no-message-and-no-run` (tagged `seon.cluster/new-agents-are-seeded-with-a-demo-task`):
`ensure-entity!` of a new agent leaves no `:seon.message/to` it and no call row.

## Cost

D. A new agent's first model pass costs what any pass costs.

## Dependencies and order

CX-1 (the model no longer needs the opening), CX-3 (same file). `src/seon/cluster.clj` may be held: claim at launch
or send the hunk to its owner. RESET NEEDED after CX-6a (stored opening runs become history no one writes).
