---
type: research
status: complete
tags: [render, schema, test]
---

# Render the session prompt's contract refusal — 2026-09-16

Justification: a typed acquisition refusal must replace the loading panel with
its complete evidence, so the next contract defect remains visible.

`seon.render.transcript/render-session` catches only `ExceptionInfo` whose
`:seon.error/kind` is `:seon.instrument/contract-violated`. The existing error
branch renders both the error grammar and the complete diagnostic value with
the existing HTML renderers. Other exceptions are rethrown. Datastar receives
HTTP 200 and replaces the same session element; no second browser error
mechanism was added.

`seon.render.web-test/session-acquisition-failure-replaces-the-loading-panel`
passed under the armed canonical fixture in the 2026-09-16 21:47–21:50 UTC fast
run. It injects a contract refusal at acquisition, checks HTTP 200, the morph
target, function, member, expected schema and offending value, and absence of
the loading text. Both an unrelated runtime exception and unrelated typed
exception remain HTTP 500.

The four-namespace run completed 160 tests / 1,729 assertions, with 26 failures
and no errors. This new test passed. Five failures belong to the existing
`next-turn-context-keeps-runtime-history-through-an-empty-turn-and-a-wake`:
its fixture retracts required `:seon.turn/agent`, which whole-entity write
validation refuses. The schema repair's landing note records the remaining
boundaries; this presentation commit does not change them.

The assigned live prompt was observed in Chrome and through HTTP 200 at
21:40:50 UTC; exact bytes are in
[the reference repair note](pulled-ref-is-a-ref-2026-09-16.md). That successful
prompt verifies the reference repair, not failure injection in the browser.
The narrowed UI edit's hook publication reported exit 124; live adoption of
this particular UI change is not claimed. No restart or additional prepl
evaluation was used.
