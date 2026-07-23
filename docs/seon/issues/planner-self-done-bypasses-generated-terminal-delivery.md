---
type: issue
status: open
severity: friction
tags: [issue, agent, flow]
---

# Planner self-done bypasses generated terminal delivery

## Problem

The planning agent's `:plan` block presents the generated root as its
active assignment, and the agent-facing `my.plan` transition surface lets
the planner close that root directly. Both live green drives on the fresh
gencode cluster (2026-07-21) ended with the planner transacting the
ordinary `:active`/`:done` transition on its own generated root before
evidence-derived completion settled. `commit-generated-terminal!` then
found the root already terminal and took its idempotent already-terminal
branch, which returns `{ok? true}` WITHOUT committing the compact
addressed result envelope. The caller received only the planner's prose
message; `:my.plan/progress` still showed open namespace steps
(1/2 and 2/3) beneath a `:done` root.

## Evidence

Fresh gencode cluster, 2026-07-21: root `gns77gfqdm8c` status history
`:open → :active (tx 536871697) → :done (tx 536871700)` and root
`h3ikyzpafl9u` `:open → :done (tx 536871803)`, both with NO message whose
content contains the root id (the terminal envelope pr-str always embeds
it), while the `:blocked` failure path in the same session delivered its
envelope correctly (root `jb86u5r7pb4c`, rendered into caller turn
`xaguzbcuh9fs`). Generated functions and passing behavioral tests were
real (`my.pressure.convert/kpa->psi` et al. with
`:seon.fn/source-fingerprint`, four `:seon.test/last-passed-at` datoms),
so the loss is delivery/consistency, not generation.

## Acceptance

One owner closes a generated root. Either the ordinary agent-facing plan
transitions refuse a generated root (it carries `:my.plan/from` plus its
planning claim; the transition error teaches that generated completion is
evidence-derived), or `commit-generated-terminal!`'s already-terminal
branch verifies the terminal message exists and commits the missing
addressed envelope idempotently. The `:plan` block teaching for planning
agents must match whichever contract is chosen. No second scheduler,
registry, or delivery path.

## Ruling

Choose the one-owner refusal contract. A root carrying both its caller
connection (`:my.plan/from`) and the generated-planning claim is closed only by
the evidence-derived generated-terminal operation. Ordinary agent-facing plan
transitions refuse a requested terminal status for that root and return a
corrective error explaining which operation owns completion.

Do not add a stored delivered flag or result-message connection, search message
content for the printed root ID, or attempt a terminal-status-to-same-status
CAS. Content search is not structural identity, and a same-value CAS permits
concurrent duplicate messages. Historical roots already closed without an
envelope remain live evidence for this issue; the next fresh generated root
proves the forward contract.

Triage 2026-07-23 — **DISSOLVES into P4 loop migration**; generated-terminal ownership and delivery become claimed run-state terminal-transition acceptance.
