---
type: research
status: complete
tags: [research, context, render, web]
---

# Debug candidate evidence UI — 2026-09-06

This is a point-in-time implementation record for the first Design Lab
inspection slice. It is not a second roadmap.

## Evidence boundary

`seon.render/render-call` makes the selection once and captures the exact
decision, selected declaration row, and supplied invocation argument in
`:seon.render.call/static-evidence`
(`src/seon/render.clj:481-491`). The same captured entry carries the handed
schema projection, including its function-contract map
(`src/seon/render.clj:493-508`, `src/seon/render.clj:885-907`). The web debug
surface now reads that captured entry directly. It performs no second
candidate discovery, selection, invocation, or database query to explain the
call (`src/seon/render/web.clj:1001-1080`, `src/seon/render/web.clj:1295-1320`).

The selected renderer and every consulted candidate link to the existing
namespace debug route with subject `[:seon.fn/sym <qualified-name>]`. That
route preserves viewer, output, and observation bounds and clears the
snapshot-bound cursors when the subject changes
(`src/seon/render/web.clj:758-784`, `src/seon/render/web.clj:1001-1004`). The
link is the bounded source-navigation surface: candidate source bodies are not
duplicated into every collapsed row. Exact retained contracts expose their
Malli function arities in a disclosure. The retained supplied argument is a
separate disclosure; neither is described as a reconstructed or executed
form.

The header uses ordinary links for the existing HTML and AI outputs. The
selected link carries `aria-current="page"`; both links preserve the same
subject, viewer, and bounds through `debug-page-url`
(`src/seon/render/web.clj:830-878`).

## Verification

The focused renderer-layout regression proves the rendered result stays
first, the real retained description remains conditional, the actual
argument and contract are present, and definition navigation preserves the
viewer/output/bounds. It passed **7 assertions, 0 failures, 0 errors**. The
focused handed-program header regression proves both output links and the
selected state alongside the source digest and projection fingerprint. It
passed **77 assertions, 0 failures, 0 errors**
(`test/seon/render/web_test.clj:743-783`,
`test/seon/render/web_test.clj:839-943`).

A pre-edit JVM probe against `lab-browser-0906` reproduced the old helper:
the HTML contained only the selected symbol, one description, stage status,
and candidate compatibility. Root's post-edit browser probe then verified the
actual supplied item title and contract, AI-to-HTML round-trip, and definition
navigation with the subject and viewer preserved, with no browser JavaScript
errors. This proves the existing retained-call and route integration; it does
not claim a new producing-form mechanism.
