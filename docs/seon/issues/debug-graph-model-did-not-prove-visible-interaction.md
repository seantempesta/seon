---
type: issue
status: resolved
severity: blocker
tags: [render, web, debug, testing]
---

# A valid graph model did not prove a visible, interactive graph

Observed and fixed 2026-09-06. The live model contained 34 nodes and 33 edges,
but the browser screenshot was blank. Cytoscape inserts its own canvases into
the supplied container; a native canvas container hid those children as
fallback content. An ordinary div fixes the ownership mismatch.

Browser verification also exposed three related integration defects:

- Client initialization overwrote the server's count and partial/complete
  status. Persistent acquisition status now has its own element, separate
  from interaction/error detail.
- Namespaced `tap.seonGraph` listeners did not receive ordinary tap events.
  Cytoscape's emitter compares namespaces explicitly. Ordinary `tap` listeners
  with exact callback cleanup now handle native interaction.
- CSS targeted a graph directly under the outer grid, but the graph lived
  inside the experiment. Correct parent selectors place it beside the output.
  Initial custom column positions were unreadable when fitted; the native
  concentric layout replaces that initial arrangement.

Dependency evidence: `reference-code/cytoscape/src/extensions/renderer/canvas/index.js`,
`reference-code/cytoscape/src/emitter.js:199`, and
`reference-code/cytoscape/src/extensions/layout/concentric.js`.

Focused model/container regression: 9 assertions passed. The reproducible
browser probe is
`docs/prds/context-generation/research/debug_browser_probe_2026_09_06.cjs`.
It checks actual renderer canvases, model counts, edge interaction, persistent
acquisition status, retained instance/viewport/selection under a client-only
model update, a physical mouse click navigating to a referenced subject while
retaining the viewer, and responsive width after the native resize observer.
The successful integrated run reported no JavaScript errors, 34 nodes/33 edges,
and a 390 px document at a 390 px viewport. Screenshot inspection confirmed
visible concentric geometry. Model presence alone was insufficient evidence.

This does not prove delivery of a database transaction through SSE. That
separate end-to-end proof remains in the design-lab working edge.
