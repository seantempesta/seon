---
type: issue
status: open
severity: blocker
tags: [issue, render, test, wave/render-test]
---

# Debug feed completion waits fail only in a pooled worker

On 2026-09-08, record-render's path-isolated gate over
`src/seon/render/web.clj` and `test/seon/render/web_test.clj` completed
59 tests / 317 assertions with four errors. The errors were the canonical
`seon.test-support/await-event!` completion backstop in:

- `seon.render.web-test/reconnect-is-repaint`;
- `seon.render.web-test/reconnect-is-repaint-wire-test`;
- `seon.render.web-test/reconnect-mid-stream-is-a-fact-only-repaint`;
- `seon.render.web-test/render-proc-one-derivation-many-tabs-test`.

All four passed the runner's isolated confirmation. Its worker-global-state
comparison named no changed state before those tasks. The same source passed
the armed fast loop: 59 tests / 356 assertions, no failures or errors. These
observations do not establish a cause, and the pooled gate is not green.

The HTTP handler and render pass now supply their existing SCI projection
for the whole operation. Default separately completed ten GETs with status
200 at the owner's URL. The completion-wait issue remains after those fixes.

Owner: the render web fixture and its real flow/SSE completion path. Verify
the event that is missing under the pooled gate; do not replace the real
pipeline, weaken the assertions, or merely increase the backstop.

Evidence and concurrent platform boundary:
[record-render landing](../../prds/context-generation/research/record-render-landing-2026-09-08.md).
