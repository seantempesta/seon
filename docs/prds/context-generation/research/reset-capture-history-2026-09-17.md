---
type: research
status: active
tags: [schema, capture, history, reset]
---

# Reset integration: capture prompt history

The first released group-5 seam removes `:seon.db/no-history? true` from
`:seon.context.capture/prompt`. The existing writer keeps the exact prompt;
temporal queries now retain it after capture retraction. No writer, identity,
prompt assembly or provider behavior changed. Previously discarded bytes cannot
be recovered by this declaration change.

The [reset plan](../../steward-platform/plan/reset-batch-2026-09-17.md) owns the
ordered integration, updated for the full high-effort design review, including
N1–N11. Living-ref enforcement and fn.ast deletion remain pending owner rulings.
G5 and digest seams remain bounded by held schema bridge/source files.

## Evidence

Read-only default probe before edit: 31 captures, installed prompt
`:db/noHistory true`, 7 ms. No default lifecycle operation was performed.

The new canonical regression constructs the cluster, agent, turn and capture
through their owners, with every write checked by `transacted!`. It checks
current prompt bytes and character count, retracts the capture, verifies its
current absence, and checks exact prompt bytes in as-of and both added/retracted
history datoms. The test file left untracked at the session interruption was
finished and retained deliberately.

| Iteration | Snapshot HEAD | Tests | Assertions | Failures | Errors |
|---|---|---:|---:|---:|---:|
| Before schema edit | `9e807c084329db4fa7918dbac72e1ead14c39888` | 1 | 6 | 2 | 0 |
| After schema edit | `8e74014d6b22f414861f62633ae0fda309e40f73` | 1 | 6 | 0 | 0 |

Red: as-of returned no prompt and history returned no prompt datoms. Green
completed 2026-09-16T23:34:36Z, exit 0, contracts armed in panic mode:

```sh
bin/test-fast --paths resources/seon/schemas/seon.context.capture.edn test/seon/context_capture_history_test.clj -- seon.context-capture-history-test
```

## Publication boundary

The edit hook's publication `e104fbd7-5510-463b-b435-9a2466dfeed5` refused with
`:seon.cluster.source/scratch-schema-refused` and unresolved predicate
`seon.search/handle?`: no admitted callable in the source projection.
`src/seon/search.clj` is assignment-held and was not edited. This names the
observed refusal without attributing its cause to another editor.

No live adoption, cold gate or reset proof is claimed. The
[issue](../../../seon/issues/captured-prompt-history-is-disabled.md) stays open
until the orchestrator's reset-boundary verification. No `bin/test` was run.
Foreign hunks, sessions and slot holders were left untouched; the fast runner
used the shared three-slot admission and cleaned its own snapshot on exit.
