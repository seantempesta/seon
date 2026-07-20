---
type: issue
status: open
tags: [issue, agent, web, cljs]
severity: friction
---

# Nested authored render hides child reload

## Evidence

On 2026-07-18, the exact read-only package let an ordinary agent publish a
canvas renderer and its action functions successfully. The next real agent
Datastar feed rendered an error instead of the canvas:

```text
Authored source changed; a fresh child is required.

```

The execution host already recognizes
`:seon.execution/reload-required?`, retires the stale child, and retries the
same invocation exactly once. That contract is covered for a top-level
authored invocation. The canvas path enters authored code through the compiled
renderer's `invoke-selected!` callback instead. `seon.execution/invoke-selected!`
caught the same program-change exception and converted it into an ordinary
per-call error value. The compiled renderer therefore returned successfully,
and the host never received the reload signal it owns.

## Expected owner

`seon.execution` preserves the program-change signal through nested authored
selection. `seon.execution.host` remains the one child retirement and bounded
retry owner. Do not add a canvas-specific refresh, program broadcast, second
renderer, or user-visible recovery instruction.

## Acceptance criteria

- A nested authored selection against a changed program rejects with the exact
  `:seon.execution/reload-required?` signal.
- The existing host retires the stale child and retries the complete render
  invocation once in a fresh child.
- The published canvas renders and its input, select, toggle, button, and form
  actions work in a real browser without an intermediate error surface.
- A second retry is impossible and ordinary authored compile/call errors remain
  ordinary render error values.

## 2026-07-18 follow-on trace

Commit `a803c26c` preserved the nested reload signal. The rebuilt read-only
package no longer displayed the stale-program error and replaced the child,
then exposed the next cold-load fault: `Could not parse ns form
my.agent.red-apes-reply`, followed by a selected-function-not-loaded fallback.

The persisted `:seon.ns/source` is a valid home namespace form, and its
`:seon.ns/require-edges` name the lifecycle `:refer` members. ClojureScript's
`cljs.js/ns-side-effects` wraps analyzer `check-uses` failures with the
misleading parse message. A fresh self-host compiler has no analyzer definitions
for host-compiled `wait`, `complete`, `pause`, `resume`, and `terminate`.
`setup-agent-ns!` already projected those live referred vars before analyzing
the same namespace form; `load-authored-program!` did not. The correction is to
derive the require specs from the existing persisted edges and seed the same
analyzer definitions before cold authored-program loading.

The second exact package retained one more failure because the accepted canvas
transaction itself stored the unqualified symbol `control-matrix`. A stale
child detects the database-wide program change before symbol selection, but a
fresh child correctly filters that unqualified name out of the program's
qualified `:seon.fn/sym` keys and then cannot invoke it. The function was
authored in `my.agent.red-apes-reply`; the eval boundary already knows that
current namespace for every form.

The owning interface is therefore `my.canvas/show!`: a bare renderer symbol is
resolved against the eval's `:seon.eval/ns` before transaction, while a
qualified symbol and literal hiccup remain unchanged. The existing eval
dependency-injection mechanism now carries that current namespace as
invocation-local data; no renderer fallback or database repair rule is added.
