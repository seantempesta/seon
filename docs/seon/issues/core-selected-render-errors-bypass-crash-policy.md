---
type: issue
status: open
tags: [issue, agent, web, cljs, health]
severity: friction
---

# Core selected render errors bypass crash policy

## Evidence

On 2026-07-18, an exact recursively read-only production package returned this
failed selected call from its execution child:

```text
The selected function is not loaded in the execution child.

```

The result was correctly classified `:seon.error/kind :core-bug`, but
`seon.execution.runtime/html-value` converted it directly into a canvas error
card. No exception catch remained outside that data boundary, so
`seon.error/record!` never persisted the fault or applied the configured
`:seon.config/on-core-error :crash` policy. The child and pod stayed alive,
hiding a core runtime invariant failure during development.

## Expected owner

`seon.execution/call-selected!` owns the boundary where every selected function
becomes either a value or an error. It records a failed core function exactly
there through the existing `seon.error/record!` mechanism, before protocol data
can be converted into Hiccup, prompt text, or an interactive-call response. An
agent-authored `my.*` failure remains an ordinary error value and never
escalates. There is no second crash switch, renderer, or error registry.

## Acceptance criteria

- A selected `:core-bug` is persisted as a `:core` fault before it becomes an
  error card, and development `:crash` terminates the affected execution child.
- A selected agent-authored failure remains an error value and does not invoke
  the core-fault policy.
- Prompt-block and canvas-render selection share the same classification
  boundary.
- The supervising host reports the process exit and can start a fresh child;
  no persisted bad program can remove the normal repair door.
- Audit the remaining web UI conversions from execution errors to rendered
  values so a core fault cannot be silently downgraded at another boundary.

## Implementation evidence

`seon.execution.runtime/record-selected-core-error!` now routes selected
`:core-bug` results into `seon.error/record!` before either prompt-block text or
canvas Hiccup is produced. Focused execution-runtime proof passes 14 tests and
75 assertions, including the explicit agent/core classification split.

The first exact package then falsified an async-scope assumption: the fault was
recorded, but canvas Hiccup conversion happened after the selected-call Promise
left `error/with-configuration`, so `record!` saw the default `:gate` policy
rather than the operation's database configuration. The conversion now runs
inside that existing operation scope. Focused proof passes 15 tests and 77
assertions and explicitly observes `:crash` at the recording boundary. A second
exact-package crash, host replacement, and the remaining web-boundary audit are
still required before closing this issue.

The second exact package terminated the execution child, but the expected fault
datom was absent. Source audit found that the remote-authority refactor removed
the only call to `seon.error/set-db-hooks!` while its documentation continued to
claim `seon.db` installed it. The dying child therefore buffered the projection
only in process-local memory and lost it on exit. `seon.db` now installs one
late-bound hook over its ordinary authoritative `transact!` path and adapts the
current transaction-report/error result to the error owner's acknowledgment.
Focused remote-database proof passes 18 tests and 84 assertions. Exact package
proof must now show both the child exit and the durable core-fault datom.

The exact package then proved that ordering twice. Each feed invocation spawned
a fresh child, persisted `:seon.error/fault :core` with the exact missing
function message at transactions `536871417` and `536871418`, and exited; no
task child survived either invocation. Restoring the qualified authored canvas
renderer and restarting normally rendered `Canvas control matrix` without an
error, and the recursively read-only package digest remained `ff1ea1fc…`.

The final boundary audit moved recording one step closer to the producer:
`seon.execution/call-selected!` now classifies and records every selected call,
covering canvas, prompt blocks, and interactive calls with one mechanism. The
renderer-specific recording code and tests are deleted. Focused selected-call
proof passes 28 tests and 108 assertions; execution-runtime proof returns to 13
tests and 71 assertions. One exact-package repetition on this final seam and
the parent host-error conversion audit remain before closure.

Final exact package `e131a442…` closes the selected-call seam. An impossible
core renderer persisted the fault at transaction `536871421` and the task child
was absent afterward while the pod remained ready. Restoring
`my.agent.red-apes-reply/control-matrix` and restarting normally produced the
complete canvas without an error. The recursively read-only package tree stayed
exactly `9d5b083c…`, and normal `down` reaped writer and pod cleanly.

The remaining audit is narrower than selected calls: failures thrown by the
top-level compiled composition function itself do not pass through
`call-selected!`. Those must be separated from an expected supervised child
exit before deciding whether the child, pod, or neither process should crash.
This issue remains active until that distinction has an explicit regression;
selected canvas, prompt-block, and interactive-call failures are closed.

That distinction now lives at the child boundary. A top-level authored `my.*`
failure stays an agent error. A top-level compiled `seon.*` failure performs an
error-only read of the current database configuration, records through the same
authoritative transaction hook, and applies its crash policy; successful calls
pay no configuration hop. The parent host continues to treat the resulting
process exit as supervised evidence and does not crash the pod a second time.
Focused execution proof passes 29 tests and 114 assertions, including the
agent/core split and observation of the database's `:crash` policy at the exact
recording boundary.

The subsequent authored-canvas load exposed one final misclassification in
that boundary. `call-selected!` constructed its missing-runtime-function error
as `:seon.error/kind :core-bug`, then `selected-call-error` discarded that fact
and inferred the fault solely from the selected `my.*` symbol. A valid authored
function that Seon's loader failed to publish was therefore reported as an
agent mistake. The missing-function branch now explicitly records `:core`;
ordinary exceptions thrown by an invoked `my.*` function remain agent faults.
Focused execution proof passes 30 tests and 118 assertions. Exact child-exit,
durable-datom, and recovery proof remains required before this issue closes.

The 2026-07-19 browser journey exposed the inverse case. An authored canvas
still selected `my.graduation.browser/renderer` after an agent successfully
removed that function from the current database program. That stale authored
reference is an application error, not evidence that Seon's loader lost a
published function. The old unconditional missing-runtime branch repeatedly
recorded `:core` faults and retired each replacement child, obstructing the
agent's repair door.

`call-selected!` now uses the exact current program already retained by the
child. A function present in that program but absent from the runtime remains a
core loader failure. An absent authored function follows the ordinary symbol
fault classification and returns an agent error. Focused selected-call proof
passes 1 test and 7 assertions across invoked authored failure, stale authored
reference, published-but-unloaded authored function, and missing compiled core
function. A fresh live child rendered the stale canvas as a safe error while
the Bun pod and JVM writer remained available; the child was then reclaimed.
The canvas context names the exact missing function and tells the agent to
define it or select an existing function before removing the old definition.

The follow-up live drive found that the warning still tested
`seon.eval/lookup-value` in the pod, even though authored functions intentionally
execute in children. It now derives authored existence from the current
`:seon.fn/sym` rows and reserves runtime lookup for compiled core functions.
`call-selected!` also emits different values for "absent from the current
database program" and "present but not loaded," preserving the authored/core
split at the owner that knows it. The canvas error card and prompt context turn
only the former into Hiccup-specific repair guidance. Focused execution,
canvas-context, and warning proof passes 49 tests/215 assertions. A persisted
missing selection rendered safely in the live web UI while pod and writer
remained ready; exact rebuilt guidance plus successful repair/clear is the
remaining live proof for this inverse case.

The exact rebuild exposed the independent HTML consumer: the web view invokes
the selected canvas function directly and projected the safe error without the
prompt canvas formatter. Its one existing `html-value` owner now recognizes the
same absent-program message for a canvas surface and adds the qualified
renderer, Malli/Hiccup contract, `my.canvas/view`, and `my.canvas/show!` repair.
Other selected blocks and core loader failures retain their original messages.
Focused execution-runtime proof passes 14 tests/74 assertions.

The exact rebuilt feed now displays that complete instruction for
`my.agents.canvas-recovery/mistyped` in both the primary canvas and rail preview
while watcher, writer, and pod remain ready. `my.canvas/clear!` then retracted
the stale selection; the next complete feed contained no absent-program error.
The inverse stale-authored-reference case is therefore closed without a child
crash, stored acknowledgement, or second warning path.

The human-facing diagnostic was itself incorrect. Canvas failures already own
one deliberate split in `seon.render.canvas/error-response`: the human sees a
calm "Updating this canvas…" placeholder, while the agent twin and error
envelope retain the cause. `seon.execution.runtime/html-value` now routes failed
canvas selections through that response instead of the generic red error card;
other failed surfaces still use the generic card. The urgent derived warning
continues to carry the qualified function and exact Hiccup repair.
