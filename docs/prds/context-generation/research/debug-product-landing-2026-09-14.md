---
type: research
status: active
tags: [research, debug, web, prompt]
---

# Debug session product — 2026-09-14

## Grounding and scope

Read end to end: AGENTS.md; datastar-web-ui SKILL.md and its
references/design-principles.md; UI architecture; seon.repl; the run-2
landing; seon.render.transcript. Read the requested acquisition seam and
turn PRD §§14, 15, 18d. Earlier Turns-list work is superseded by the owner's
session-product redirect. The running default cluster was never stopped,
reforked, reseeded, or messaged by this lane.

Dependency ledger: the existing Datastar HTML response handling
(`resources/public/js/datastar.js`) supports replacement fragments;
`seon.render.block/surface-id` supplies their stable IDs. The existing
`render/acquire-context!` → `web/derive-context!` → `walk/history` fold owns
membership, order and bytes. The acquisition result now carries the same
fold entries beside its segments, allowing the reader to follow their
evaluation identities without a second history query. Datahike temporal
reads come through `turn/opening-db` and `seon.db`; saved values are never
re-evaluated. The Clojure lexer consumes characters and never calls a code
reader; EDN reading is restricted to derived re-read summaries.

## Part A — colourised, faithful selected-turn context

The initial response selects the latest turn and loads just that turn's
context through the existing debug route. The source of prompt bytes is
the acquisition fold, not the HTML renderer. The new emission pair wraps
its `text` substrings in syntax spans; concatenation preserves whitespace,
fences, malformed source and Unicode. Browser pre elements put separator
newlines inside spans so HTML's leading-newline parsing rule cannot delete
a byte. The raw toggle uses the acquired prompt directly.

Repeated system reads are collapsed **in place**. The controls summarize
their count and first/last shown-text difference; expansion reveals each
entry at its original chronological position, never rearranging the prompt.
Metadata uses local times and logical turn identities. Saved prompt text
retains its original datoms and `#inst` literals where the model saw them;
rewriting those would violate byte fidelity.

### Screenshot iteration log

| Images in `tmp/debug-product/` | Observed defect | Change |
|---|---|---|
| `a-1-1440.png`, `a-1-700.png` | End position dominated by repeated runtime pulls; old top navigation and full message bar waste space. No horizontal overflow at either width. | Repeated reads folded in place; header is the next mandated slice. |
| `a-2-1440.png`, `a-2-700.png` | Re-read summaries for multiple pulls were indistinguishable; a whole set was printed as a change with an empty path. | Added the source form's first qualified attribute and made whole-value changes report byte counts. |
| `a-4-1440.png`, `a-4-700.png` | Emission colours, gutter alignment, error tint and wrapping are correct; browser-default unchecked boxes were white. Header remains the next slice. | Gave disclosure checkboxes explicit maintained palette colours. |
| `a-final-1440.png`, `a-final-700.png` | Colours and wrapping remain correct at both widths; checkboxes now use the maintained palette. The old navigation is still visibly poor. | Part A accepted for its reader; header/navigation is the immediately following commit. |

The committed browser probe is
`docs/prds/context-generation/research/debug_product_browser_2026_09_14.cjs`.
Its `a-4` run verified 136 emissions = **177,576 UTF-8 bytes**, identical to
the raw toggle, at both widths. All overflow checks and JavaScript-error
checks passed. A bound MCP probe separately verified lexer identity for
**all 61 stored replies** (including blob-backed replies), in 18 ms.
The later initial GET was **200 / 0.003628 s / 2,583 response bytes**.

Part A isolated gate: **87 tests / 583 assertions, zero failures and errors**,
including `seon.repl-test`, `seon.render.web-debug-test`,
`seon.render.runtime-test`, `seon.render.web-test`, and
`seon.repl-grammar-test`. The gate used HEAD plus this lane's explicit paths
and removed its successful root `tmp/test-runs/run.msn0U9`.

The paths-only fast gate passed **27 tests / 181 assertions**, zero failures
or errors. The first socket gate also exposed assertions about the retired
would-be-system-turn layout. Those tests now request the new fragment over
real HTTP and verify ordered colourised emissions and raw bytes after a
wake, using runtime-owned turn refs as the history authority.

Initial HTTP GET measured **200 / 0.006479 s** after loading the new reader.
The first selected-turn fragment measured **200 / 2.110616 s**. First
Playwright screenshots completed in 2.581 s (1440 px) and 2.052 s (700 px),
including navigation, acquisition, rendering and capture. Both had 136
emissions, no JavaScript errors, document width equal to viewport width,
and positive scrollTop at the end. These timings distinguish a fast initial
response from the asynchronous prompt load; they do not claim the full
prompt appears in 6 ms.

### Earlier outage, retained as evidence

The owner observed HTTP 500 after 17 seconds during the superseded Turns
implementation. Its invalid Hiccup came from calling `surface-id` with
vectors instead of its declared keyword input. Correcting those calls
restored 200; the first restored render still took 10.629483 seconds.
Batching header reads and removing eager prospective-system-turn previews
reduced the initial header response to 0.050692 seconds. The new selected
session replaces that UI. This was a lane defect, not a foreign failure.

### Verification boundary

The first screenshots exercised hot-reloaded Vars in default, with JVM
contracts re-armed. Development source adoption convergence and the final
gates are recorded below when complete. The independent dir-own-fns lane
owns `src/seon/sci/eval.clj` and its tests; paths-only gates exclude its
uncommitted changes. The inherited MCP runtime-status timeout is recorded
in `docs/seon/issues/default-component-probe-times-out-after-adoption.md`.

At the Part A checkpoint full development adoption was rejected by static
analysis at **test/seon/html_views_test.clj:55, unresolved plan/current!**,
outside this lane. No foreign file was edited to bypass it. The final
screenshots exercised explicit hot reload of transcript/web in default and
re-arming of the current JVM contracts. The source marker was not claimed
converged. The page returned 200 and the complete browser byte-identity and
overflow probe passed after that reload. CSS was rebuilt only by `bin/css`;
the generated output includes the shared stylesheet's current block rules,
while `blocks.css` and the corresponding entity renderers remain outside
this lane's commit.
