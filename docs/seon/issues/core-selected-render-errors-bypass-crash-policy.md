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

`seon.execution.runtime` owns the boundary where selected-call protocol data
becomes rendered Hiccup. It records a `:core-bug` result exactly there through
the existing `seon.error/record!` mechanism. An agent-authored failure remains
an ordinary error value and never escalates. There is no second crash switch,
renderer, or error registry.

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
