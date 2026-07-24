---
type: issue
status: resolved
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

## U4 temporary containment seam — 2026-07-23

U4 moved trusted prompt orchestration into the pod (`488f3dd5e`) but, by owner
ruling, authored stored blocks, whole-prompt symbols, derived render functions,
and canvas/AI twins still use `seon.execution.host/invoke-plans!`. This keeps
the existing child reload/containment behavior above intact while avoiding a
second prompt driver. The code comment in `seon.agent.turn/render-prompt`
names U7 as the closing unit: U7 must route these authored render symbols
through the U1 guarded door and remove this temporary child dependency.

## U7 render-boundary progress — 2026-07-23

Commit `8bd19774e` removes `seon.eval/lookup-value` from the portable render
walker. Resolution is now structural: core handler/default symbols exist only
in the immutable `seon.render.core/renderers` table, while an
`seon.error/agent-authored-sym?` symbol can execute only through the injected
`:seon.render/invoke-authored!` door. A JVM regression proves a hostile
core-looking stored symbol cannot fall through to SCI, and an authored
infinite renderer returns the U1 guard's `:budget` steering value in its render
slot while another host future completes.

This closes the unbounded fallback at entity/custom-render resolution (guarded
door boundary item 5), but it does **not** close this issue. The remaining
temporary dependency is exact and protected: `seon.agent.turn/render-prompt`
still supplies `seon.agent.ctx.driver/render-prompt!` with
`invoke-prompt-calls!`; its authored arm constructs
`seon.execution/invocation-plan` values and calls
`seon.execution.host/invoke-plans!`. Stored blocks, whole prompts, derived
render functions, and canvas twins (boundary items 1–4) therefore still use
option A. Replacing that callback requires a change in the protected turn/host
spine, so U7 stopped at the consumer contract instead of adding another host
surface or editing the protected owner.

## Resolution — guarded pod render door complete, 2026-07-23

Commit `8bd19774e` established the render-owned
`:seon.render/invoke-authored!` contract. The claim-driver follow-up now binds
that seam at `seon.agent.turn/render-prompt` and sends each authored call
through the single guarded host invocation entry. The temporary
`invoke-prompt-calls! → execution.host/invoke-plans!` arm, its authored-symbol
database classification, and its option-A comment are deleted.

Trusted core prompt functions remain direct compiled calls. Structurally
authored symbols—including stored blocks, whole-prompt functions, derived
render functions, and canvas twins—cross the render seam and the U1
`:authored-render` guard. A guard steering value is returned as the render
slot value, preserving its kind and governing config key.

Focused regressions prove a normal authored symbol renders through the
single-call door, a hostile authored symbol retains its `:budget` steering
value in the slot, and the restart-stable default-context byte oracle remains
identical. The option-A window is closed; the pod render containment path is
door-complete.

The closing implementation is `cd7d3ebf8` on top of `8bd19774e`.
`src/seon/agent/turn.cljs:393-411` now sends authored render calls through the
single host invocation, while `src/seon/agent/turn.cljs:413-447` keeps trusted
compiled calls direct and routes authored symbols through
`:seon.render/invoke-authored!`. The seam itself is
`src/seon/render.cljc:43,67-68`.
