---
type: issue
status: active
tags: [issue, agent, web, cljs, health]
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
