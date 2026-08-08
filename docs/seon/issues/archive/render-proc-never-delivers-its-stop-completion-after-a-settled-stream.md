---
type: issue
status: resolved
severity: blocker
tags: [issue, render, web, flow]
---

# The render proc never delivers its stop completion after a settled stream

## Problem

**Cause found 2026-08-07.** The proc's stop path was never at fault. The
transform NEVER RETURNED, so flow never reached the `::flow/stop` transition
that puts `::stopped` on the proc's completion
(`src/seon/render/web.clj:875-881`), and `disarm-agents!` — which joins both
cluster-graph procs on that completion before releasing the branch connection —
would have waited forever on a real shutdown.

The transform did not return because **a declared producer that renders its own
value THROUGH another producer re-selected itself, forever.**
`seon.ai/attempt-html` hands the attempt, minus reasoning, to the value floor
`seon.render.value/render-html` (`src/seon/ai.clj:105-115`). The floor's
`prepare` projects that value through `seon.render/project-node`
(`src/seon/render/value.clj:240-246`), selection matches
`:seon.ai/attempt` again (`resources/seon/schemas/seon.ai.edn:4-8`), and the
chain is:

```text
project-node → attempt-html → prepare → project-node → attempt-html → …
```

`project-node`'s docstring claimed "a selected producer's output is terminal
projection data: it is never fed back into selection" — true of its OUTPUT,
and irrelevant to its INPUT, which is exactly what recursed.

## Evidence

The filer's next step was the right one. A virtual-thread-aware
`jcmd Thread.dump_to_file` taken INSIDE the 20 s window (a daemon thread
dumping every 4 s through `seon.test.runner/persist-virtual-thread-dump!`,
`tmp/render-stop-wedge.clj`) shows exactly one working thread in the JVM:

- an unnamed VIRTUAL thread (the `:io` proc), stack **truncated at the dump's
  1024-frame cap**, every frame a repetition of
  `seon.render$project_node → seon.sci.kernel$invoke →
  seon.render.value$render_html → seon.render.value$prepare →
  seon.render$project_node`;
- `main` parked in `with-server`'s `await-event!` on the completion
  (`test/seon/render/web_test.clj:199`);
- everything else parked.

A depth probe wrapping `seon.sci.kernel/invoke` (`tmp/render-recursion-probe.clj`)
named the producer and its argument at depth 13:

```text
producer: seon.ai/attempt-html
value keys: (:db/id :seon.ai.attempt/at :seon.ai.attempt/id
             :seon.ai.attempt/ordinal :seon.ai.attempt/run
             :seon.ai.attempt/settings-edn :seon.ai/endpoint :seon.ai/model)
```

That is why the failure was deterministic and confined to one test: it is the
only test in the namespace that commits an attempt fact into the walked page.

## Fix

`src/seon/render.clj`. `invoke-selected` records the producer it is running in
`:seon.render/rendering` (carried through `render-argument`'s context, so it
travels into the producer's own walk), and `project-node*` refuses to select a
producer already on that chain — the node falls through to its children, which
is what the delegating producer asked for. The cycle is unconstructable rather
than depth-capped, and mutual delegation is covered by the same set.

`:seon.render/rendering` is NOT yet declared in
`resources/seon/schemas/seon.render.edn`: the admission gate refuses every edit
to that file for five PRE-EXISTING `:any` declarations
(`:seon.render/call-request`, `candidate-request`, `output`, `unit`, `value`)
that lack the recorded polymorphic-boundary exemption. Maps are open, so the
key works undeclared, but the declaration is owed and that file's `:any` debt
is what blocks it.

## Resolution

Fixed in `8872311d1`, "The render walk cannot re-enter a producer that
delegates its own value" (`src/seon/render.clj`).

Proof, 2026-08-07:

- `bin/test seon.render-coverage-test`: 3 tests, 83 assertions, **0 failures,
  0 errors**, including the class regression
  `a-producer-that-delegates-its-own-value-is-never-re-entered`. The unguarded
  code does not fail it, it never returns, so its oracle is the shared loud
  backstop around the render.
- `bin/test seon.render.web-test`: **38 tests, 274 assertions, 0 failures,
  0 errors**, twice consecutively — the first zero-error run this namespace has
  had. It was 38/271 with 1 error before, and that error was deterministic
  (3 of 3 isolated `test-vars` runs). The isolated `test-vars` run of
  `thinking-stream-morphs-into-the-settled-session-transcript` also completes
  in ~30 s instead of hanging.

A third consecutive namespace run was prevented by unrelated foreign churn in
the shared tree — `src/seon/cluster.clj:443` began refusing every fixture with
`Initialization lookup refs do not resolve`, which also fails
`bin/test seon.cluster.agent-test`, so it is tree-wide and independent of this
change.

## Owner

`seon.render/project-node` and `seon.render/invoke-selected`
(`src/seon/render.clj`).

## Follow-up owed

`:seon.render/rendering` is not declared in
`resources/seon/schemas/seon.render.edn`: the admission gate refuses every edit
to that file for five PRE-EXISTING `:any` declarations
(`:seon.render/call-request`, `candidate-request`, `output`, `unit`, `value`)
that lack the recorded polymorphic-boundary exemption. Maps are open, so the
key works undeclared, but the declaration is owed once that `:any` debt is paid.
