---
type: issue
status: resolved
severity: blocker
tags: [issue, wave/live-drive-render, wave/render-test]
---

# Render revision state overwrote its input atom

Observed 2026-09-05 after a clean main database rebuild, PID 65956. Opening
`/agent/root/debug` left observation, selection and output placeholders loading.
The render proc recorded `java.lang.Long cannot be cast to java.util.concurrent.Future`.

`src/seon/render/web.clj` initialized the render revision atom and the numeric
last-observed revision under the same namespaced keyword. The state update
replaced the atom; a subsequent step dereferenced the number.

The implementation now keeps numeric state under `::observed-runtime-revision`.
Verification must exercise actual proc initialization and repeated updates,
including a database wake replacing an evaluation wake. A process whose state
already lost the atom requires reconstruction. Live browser verification and
visible failure reporting remain pending; source load alone does not close this.

## Resolution (2026-09-15 triage)

surface: render-debug-page

At HEAD `859c9258c`, `git show HEAD:src/seon/render/web.clj | rg -n 'runtime-revision|observed-runtime-revision'` returns no matches. The old revision atom and numeric observed-state mechanism are absent. The current Flow owner at `src/seon/render/web.clj:2430` receives runtime-evaluation events through a channel; page revision numbers are separately derived from prior packages at `:1814–1822`. There is no remaining numeric write to the atom described in this note. This is deletion/current-state proof, not browser-health proof; default MCP health timed out independently during triage.
