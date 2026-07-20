---
type: issue
status: resolved
severity: major
tags: [issue, pod, health]
---

# Pod failure reports bypassed seon.log

## Problem

About 30 pod call sites reported runtime failures with direct `js/console.*`
instead of the one logging owner `seon.log`. Those lines reached the
supervisor-tee'd `logs/pod.log` as bare unstructured text but never landed in
the rotated NDJSON-EDN `logs/pod-events.log`, so `seon.log/tail` — the
agent-readable surface — never saw them. The largest cluster was
`seon.agent.loop` (12 sites); the rest spread across `seon.agent.turn`,
`seon.agent.run`, `seon.agent.schedule`, the `seon.db` listen! handlers,
`seon.eval`, `seon.ai.typeahead`, `seon.ai.diffusiongemma`, one
`console.debug` (a sink `seon.log` does not even map) in
`seon.ai.openai-compat`, and `my.plan.internal`.

`seon.eval/record-eval!`'s tx-failure branch additionally only console-logged
a dropped eval record without recording a fault datom, against the
nothing-caught-without-becoming-data contract.

## Evidence

Audit inventory:
`docs/prds/database-authority-mesh/research/cleanup-audit-logging-errors-2026-07-20.md`
(residue table, 84-hit sweep on branch `codex/runtime-reliability-refactor`).

## Owner

`seon.log` (`src/seon/log.cljs`) is the one pod logging boundary; catch sites
that drop data additionally record through `seon.error/record!`.

## Resolution

Closed by the commit adding this note (branch
`codex/runtime-reliability-refactor`). Every residue site in
`src/seon/agent/loop.cljs` (12), `turn.cljs` (4), `run.cljs` (2),
`schedule.cljs` (1), `src/seon/db.cljs` (2), `src/seon/eval.cljs` (4),
`src/seon/ai/typeahead.cljs` (2), `ai/diffusiongemma.cljs` (2),
`ai/openai_compat.cljs` (1), and `src/my/plan/internal.cljs` (1) now calls
`seon.log/error!`/`warn!`/`debug!` with `:seon.log/source`, preserved message
content, `:seon.log/agent` where applicable, and structured payloads under
`:seon.log/data`. `record-eval!`'s tx-failure branch also records the failure
value through `seon.error/record!` (`:core` fault, buffered persist).

Documented last-resort consoles in `seon.log`/`seon.error`, worker
wire-protocol stdout, and eval print capture were left untouched per the
audit's correct list.

## Proof

- Live: `logs/pod-events.log` gained entries from the routed sources during
  the changed-test runs of 2026-07-20T14:08Z, e.g.
  `{:seon.log/source :seon.ai.openai-compat/empty-completion …}` and
  `{:seon.log/source :seon.eval/record-eval, :seon.log/message "tx FAILED:
  program row rejected — source: (+ 1 2)" …}` — the exact routed failure
  paths emitting into the file `seon.log/tail` reads.
- Tests: `bin/test-cljs --test=seon.log-test` 11 tests / 29 assertions,
  0 failures; per-file `bin/seon test changed` runs for all ten edited files
  0 failures / 0 errors; full `bin/test-cljs` green (counts in the commit).
