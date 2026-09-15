---
type: issue
status: open
severity: friction
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

## Re-verified at HEAD (2026-09-15)

UNVERIFIABLE-WITHOUT-GATE (`seon.render.web-test`, in the pooled isolated gate, not only test-fast). Audited HEAD `7e35df213:test/seon/render/web_test.clj` still contains reconnect-is-repaint (`:1264`), render-proc-one-derivation-many-tabs-test (`:1317`), wire reconnect (`:1476`), and mid-stream reconnect (`:1607`). The deterministic stale-pass regression at `:1424` names an older reconnect race, but that does not establish the cause of this note's four pooled-worker timeouts. Need the pooled namespace run and exact missing-event evidence; owner prohibits launching it. Downgraded to friction: historical isolated confirmations passed, and this triage has not reproduced an agent-run or context-generation blocker.

surface: runner-gate
