---
type: research
status: active
tags: [research, debug, web, prompt]
---

# Debug session product — 2026-09-14

## Problems panel

Review screenshots: [desktop problems](../../../../tmp/debug-product/panel-final-1440-problems.png),
[mobile problems](../../../../tmp/debug-product/panel-final-700-problems.png),
[desktop selected turn](../../../../tmp/debug-product/panel-final-1440-selected.png),
[mobile selected turn](../../../../tmp/debug-product/panel-final-700-selected.png).

The initial live panel reports **30/30 turns used**, **4/7 steps complete**,
**21 evaluation errors**, **8 delivered fault notifications**, and **71
repeated system reads**. Reported usage totals are **954,877 prompt / 890,752
hit / 64,125 miss / 7,259 completion tokens**; the current rates on file give
**$0.01350**. Twenty-five provider turns had no current plan step selected at
their opening; five belonged to `juniper/read`. These are observed facts,
not an inferred assignment of the later replies to plan steps.

The nine checks report: fabricated responses **9**, error evaluations **21**,
repeated system reads **71**, repeated provider forms with identical results
**2**, replies with zero evaluations **1**, incomplete directory results **1**
(**7 unavailable** because the saved output cannot be compared), delivered
fault notifications **8**, fault-triggered turns **2**, prefix changes **0**.
The passing prefix line reads **“Prefix stable on 29/29 attempts”** (the
first attempt has no preceding prompt to compare).
Unavailable observations are not counted as passing checks. Costs explicitly
use the rates currently on file, not a claim about historical invoices.

Fast regression: **27 tests / 236 assertions**, zero failures/errors. The
canonical real-loop fixture adds stored rows to exercise all nine rule counts,
missing rates, exact usage totals, configured cost, and absent prefix evidence.
The first seeded version was refused because its fault and model facts were
incomplete; the fixture now uses the error normalizer and the canonical
provider reference.

Screenshot log (`tmp/debug-product/`):

- `panel-1-{1440,700}-top.png`: inspected; budget and problem rows read clearly.
  The strip only marked evaluation errors; extended its dots and hover text
  to all matched problem rules.
- `panel-2-{1440,700}-problems.png`: inspected; fabricated responses expand
  to four offending turn links without exposing raw metadata. Both widths
  retain readable labels, wrapping, and ordinary document scroll.
- `panel-2-agent-{1440,700}.png`: inspected; main-page blocks remain full width
  with no nested scroll boxes. Runtime fault prose still repeats its message;
  [recorded separately](../../../seon/issues/runtime-html-repeats-the-trigger-message.md)
  because this is inside the concurrently assigned runtime block.
- `panel-final-{1440,700}-{selected,problems}.png`: inspected; all problem
  dots are live and the selected heading remains below the sticky header.
  `panel-links` also verifies that a fabricated-response link selects its
  owning turn through the existing route at both widths.

Browser checks at both widths pass: all nine rules, 61 cards/cells, route
selection, **375 reply bytes**, and **177,576 prompt bytes**. Latest measured
initial GET before the final strip-mark adoption: **HTTP 200 / 0.323641 s**.
With all problem marks live: **HTTP 200 / 0.079370 s**.
Later read-only sample: initial ledger **200 / 0.266706 s**; full context
expansion for turn 40 (`a51f8821e5be`, `context=true`, Datastar request)
**200 / 1.557847 s**. The larger context remains an on-demand request.
The first isolated gate passed **87 tests / 637 assertions**. A subsequent
gate uses current HEAD after `0dca8534e` landed the previously excluded
runtime-AI hunk; that landed change is preserved.
That current-HEAD pass exposed one stale fixture assumption: its origin check
depended on runtime turn-history churn. Adding an order did not cause this
virtual-submission fixture to append a changed-read emission. The origin
assertion now runs against the two explicit generated turns already seeded
for the panel regression, through the real prompt fold and session renderer.
It verifies stored re-read provenance without requiring the retired churn.
The platform gate on the first isolated snapshot passed **84 tests / 505
assertions**. Default's adopted source advanced to
`6aa8c04a-d965-5ac5-99a9-871245d3ea1c`; the final screenshots visibly contain
the new problem dots. A newer concurrent publication was already at
`6aa8c137-5c18-584f-901e-0cd152fdd9de` on the subsequent read, so this is
evidence of the panel's live adoption, not whole-tree convergence.
The final current-HEAD platform snapshot also passed **84 tests / 505
assertions**, zero failures/errors. The corrected web-debug fast pass is
**11 tests / 137 assertions**, zero failures/errors.
Final current-HEAD isolated gate: **87 tests / 635 assertions**, zero
failures/errors. The earlier failed snapshot is superseded by this green
run; only the stale origin assertion moved, with its coverage retained.

A read-only MCP probe of the rule result timed out at **30,000 ms** while
HTTP returned the new panel with **200 / 0.114422 s**. This re-observes the
[existing MCP timeout issue](../../../seon/issues/default-component-probe-times-out-after-adoption.md),
whose file has another lane's in-flight edits. No alternate REPL transport
or lifecycle operation was used. The HTTP observation proves page rendering,
not Flow health or the timed-out probe's completion.

## Turn strip

The strip keeps all **61 turns** chronological. Provider cells are filled;
generated and virtual cells are hollow. Width is proportional to exact bytes
added, with a 24 px minimum. Error turns are red and marked; open or empty
provider results are amber. The selected cell has a signal underline at both
widths. Hover text exposes id, kind, time, bytes, and provider token facts;
click selects the turn through the existing Datastar route and updates the URL.

Screenshot log (under `tmp/debug-product/`):

- `strip-1-700-selected.png`: the sticky header covered the selected heading.
  Replaced a fixed scroll offset with the measured sticky-header height.
- `strip-2-{1440,700}-selected.png`: headings were visible; the result count
  still read “1 evaluations”. Corrected the singular label.
- `strip-final-1440-selected.png`, `strip-final-700-selected.png`, and
  `strip-final-700-navigation.png`: inspected; selected headings, underline,
  opening disclosure, and the three author-labelled sections read clearly.
- `strip-final-agent-{1440,700}.png`: inspected; shared shell and full-width
  blocks retain their layout, wrapping, and normal document scroll.

Default GET: **HTTP 200 / 0.161424 s**. Browser checks at both widths confirm
**61 cards/cells**, navigation to turn 2, **375 exact reply bytes**, and
**177,576 exact context bytes** for turn 60. The canonical fixture regression
checks that all cell byte counts sum to current context exactly once.
Fast gate: **27 tests / 212 assertions**, zero failures/errors.
The broader gate exposed an old web-test expectation that a completed system
turn's opening contains its later results. That fixture now opens the next
turn before checking current completed history; the assertion remains exact.
Development adoption completed at `6aa8b8f8-4a61-5616-a2bd-0b4b82f7f3b6`.
The subsequent `strip-adopted-*` browser checks pass at both widths; the
700 px selected view and 1440 px overview were inspected again.
Final isolated gate: **87 tests / 613 assertions**, zero failures/errors.
The updated web namespace also passed its fast gate: **58 tests / 384
assertions**. The foreign `render-runtime-ai` hunk remains excluded from
the commit and gate snapshot.

## Context now versus a named turn's opening

The `63ac0608a` regression was mine: the loop proof and two research helpers
used the latest turn id to request current context. Acquisition now documents
and enforces two meanings: no id folds all stored evaluations; an id folds
that turn's opening and excludes its own reply evaluations, including source
rows admitted with a virtual turn's opening transaction. The same history
walk still owns rendering; no second prompt formatter was added.

Default read-only proof: current context is **178,089 bytes**, ending with
`my.agent/done` and its saved “Session complete.” result. The last provider
attempt still saw **177,576 bytes**. All **30/30** run-2 provider prompts
remain byte-identical to their captures; estimator tolerance remains **13/30**
with maximum residual **957 tokens**. The existing committed
`debug_prompt_proof_2026_09_14.clj` reproduces those comparisons.

Fast gate: **15 tests / 278 assertions**, zero failures/errors. The added
assertion compares the next virtual turn's historical prompt with current
context captured before that reply. An initial version checking every
submission passed the prompt assertions but encountered two wake-refresh
failures; the final regression adds the requested single boundary assertion.
Concurrent context-renders hunks in the loop proof and help-trial helper
were excluded using a HEAD-plus-owned-hunks worktree.
Isolated gate: **15 tests / 282 assertions**, zero failures/errors. The
updated Juniper helper was loaded and called read-only on default and returned
**178,089 bytes**, including the latest result.

Default GET: **HTTP 200 / 0.218008 s**. `context-now-{1440,700}-selected.png`
was inspected: the three labelled ledger sections and exact raw reply remain
clear, with the selected heading visible below the sticky strip. Browser
checks verify **61 cards**, **375 reply bytes**, and **177,576 prompt bytes**
at both widths. The pending strip is visible in these shared-tree captures
and belongs to its separate slice. No layout was changed by this fix.
The main-page captures `context-now-agent-{1440,700}.png` were also inspected:
full-width blocks remain readable without inner scrolling. Adopted and
published source converged at `6aa8b667-340d-5504-8a30-53684ef05b5d`.

Live verification also exposed a stale environment projection in the
long-lived cluster handle, despite the database's updated schema. Supplying
the current projection made the no-id call succeed; both research helpers
now carry it explicitly. The remaining adoption boundary is recorded in
[the issue](../../../seon/issues/cluster-handle-retains-old-environment-projection-after-adoption.md).

## Ledger review: make the collapsed list tell the story

Provider headers now include the reply's first comment line (a presentation
summary limited to 90 characters), counts of values/errors/out, and short
transaction effect labels. A successful evaluated `my.agent/done` adds
“done”. System headers name the re-read concerns; the opening reports its
actual emission count. Durations stay in seconds below two minutes.

Emission labels use stored renderer identities and the structured read
form's declared attributes, with the original first line as fallback.
No regex or form execution is used. The first provider turn groups its
**9 opening emissions** in one disclosure, followed by settings and runtime
re-reads. Its **11 emissions / 10,422 bytes** still match the captured prompt.

Screenshot review: `story-1-{1440,700}-top.png` shows the session's progression
in the collapsed headers; `story-1-700-selected.png` retains all three labelled
sections. `story-early-{1440,700}-selected.png` shows the compact opening group
and two named re-reads. The previous first-token labels and eleven unrelated
opening disclosures were the defects these captures resolve. Default GET
after the change: **HTTP 200 / 0.166157 s**. Browser byte checks remain
**177,576 bytes** at turn 60 and **10,422 bytes** at turn 2.
Fast gate: **27 tests / 209 assertions**. Isolated gate: **87 tests / 607
assertions**, zero failures or errors. Adopted and published source both
`6aa8b26e-d855-56d8-a9ab-7c1483926d9c`; the subsequent
`story-adopted-*` captures of both pages at both widths were inspected and
retain the approved wrapping and section hierarchy.

## Turn ledger (owner replacement of author-interleaved view)

The default now has 61 chronological cards for run 2. Only the selected
turn and last three cards render bodies initially. Provider cards separate
WE SENT, AGENT REPLIED, and RESULTS; generated turns have only WE GENERATED.
Raw replies retain fences and fabricated responses exactly. Results use
the existing REPL response grammar and lexer, with a six-line disclosure
for long results. Full context opens the existing faithful transcript inside
the card; `?prompt=true` remains the uncoloured byte view.

Screenshot log (files in `tmp/debug-product/`):

| Capture | Observed defect | Change / verification |
|---|---|---|
| `ledger-1-*` | Expanded generated forms pushed reply and results far below the first screen. | Generated emissions now have individual native disclosures and change summaries. Results show the response without repeating the readline/input. |
| `ledger-3-{1440,700}-selected.png` | All three sections readable together; preceding system card remains above the selected card. | Keep normal document scrolling and shared section edges. |
| `ledger-early-{1440,700}-selected.png` | First provider turn clearly separates generated opening, reply and result. | 346 reply bytes, 1 evaluation, 10,422 exact prompt bytes. |
| `ledger-final-{1440,700}.png` | A derived change summary exposed a timestamp map. | Summary now names the changed key; numeric changes retain before/after values. Exact stored text remains inside the disclosure. |
| `ledger-3-agent-{1440,700}.png` | Shared header and full-width blocks remain readable at both widths. | Keep the approved main-page layout. |

The first broad evaluation pull took **1,100.277 ms** by itself and caused
a **1.173724 s** initial GET. Querying evaluation ids and pulling only the
stored rendering fields reduced the complete armed default GET to
**HTTP 200 / 0.035198 s**. Query refusals are displayed, not interpreted as
zero evaluations. This replaces the older 4.9 ms measurement, which measured
the previous deferred-transcript shell. The original 17 s failed render
remains a performance defect found during this work, not an acceptable bound.

All **30/30** run-2 provider prompts again match their stored capture bytes.
When a newer renderer derives extra changed-since annotations, acquisition
first checks its result against the stored capture, then uses the same REPL
grammar on the saved evaluation rows without that new derived annotation.
It refuses a remaining mismatch rather than calling new text historical.
The token estimator still meets the requested tolerance on only **13/30**
attempts; byte identity is the stronger proof and no estimates are falsified.

Live verification used hot-reloaded Vars, explicitly re-armed with the
running projection. Concurrent publication first hit an invalid in-flight
`resources/seon/schemas/my.agent.edn`, then reported source movement during
adoption. The isolated gate uses HEAD plus this lane's files, excluding the
concurrent `render-runtime-ai` hunk in transcript.clj and all foreign schema
and REPL edits. No other lane's process or files were operated.

Effects identify message senders and plan transaction authors. Definition
events currently say “installed during this turn”: their transaction window
is known, but these stored rows do not attribute their author. The later
effects pass must preserve that distinction under concurrent agent work.

Final isolated ledger gate: **91 tests / 636 assertions, zero failures or
errors**. Fast iteration: **27 tests / 207 assertions, green**. The later
convergence probe, with the running projection handed explicitly, completed
in 4 ms: adopted and published source both
`6aa8adfd-abc4-5344-afe1-62cd8641e9f9`. A preceding unscoped status query hit
its 10-second bound; the MCP liveness probe and the explicitly scoped query
both answered. The page remained **HTTP 200 / 0.047897 s** during that probe.
Post-adoption `ledger-adopted-{1440,700}-selected.png` and
`ledger-adopted-agent-{1440,700}.png` were inspected at both widths: the
three labelled sections remain visible together, and the main-page layout
is unchanged. `ledger-verified-*` also verifies opening a previously unloaded
card and returning from raw prompt to ledger through the actual browser links.
Platform gate: **84 tests / 505 assertions, zero failures or errors**.

## Historical prompt owner correction

`render/acquire-context!` now chooses the named provider turn's opening
transaction through `seon.turn/opening-db`, then folds stored shown text.
This is the same immutable basis the loop uses before capture; the turn's
own reply and all later evaluations are excluded. Completed generated system
turns use their close transaction to include their generated evaluations.
The debug renderer no longer duplicates this temporal selection.

Read-only default proof: all **30/30 provider attempts** reproduce their
stored capture exactly, with matching opening/capture transaction bases.
Turn 2 is **10,422 bytes**, turn 40 **111,975 bytes**, and turn 60
**177,576 bytes**. The previous direct owner call returned the current
178,089-byte context regardless of the requested turn.

The requested estimated-token tolerance is not a valid exactness check for
the existing character-ratio estimator: **13/30** estimates fall within
64 plus completion tokens; the largest residual is **957 tokens**, despite
all captured strings being identical. The page shows both values honestly:
turn 2 `rebuilt ≈3,682 tokens · billed 3,222`; turn 60
`rebuilt ≈62,690 tokens · billed 63,514`. No per-attempt calibration was fitted
to manufacture agreement. Reproducible proof and all observations:
[script](debug_prompt_proof_2026_09_14.clj),
[results](debug_prompt_proof_2026_09_14.edn).

Screenshot log: `tmp/debug-product/basis-2-{1440,700}.png` and their `-top`
views show the historical opening, visible readline and top-aligned gutter.
The prior gutter alignment placed labels at the bottom of tall entries;
explicit flex start alignment fixes it. Both widths pass exact DOM text/raw
prompt identity and overflow assertions. Runtime elapsed text now expresses
days/hours rather than thousands of minutes. Initial default debug GET:
**HTTP 200, 0.004903 seconds**.

Full-page capture review found a screenshot artifact: capturing while the
document was scrolled to its end painted the sticky header midway down the
full PNG and left its original space blank. The browser script now captures
the end viewport first, then scrolls to the top for the full-page PNG. This
changes the evidence capture, not the page's initial end-scroll behavior.

The broader check exposed existing prompt fixture failures, reproduced in a
HEAD-only snapshot and recorded in
[the fixture issue](../../../seon/issues/prompt-tests-retain-incompatible-turn-fixtures.md).
The inbox pair also omits message content in the canonical page fixture;
[that block defect](../../../seon/issues/inbox-block-omits-message-content.md)
is outside this lane's shell ownership. Cache tests now target the ordinary
page and keep their cache assertions; the session test asserts deferred
acquisition rather than the retired eager debug comparison.

## Main-page layout

Removed the recency primary/rail grid and its 52rem/10rem inner scroll
constraints. Both DOM and visual order now come from the walked unit's
declared relationship or identity: plan, runtime, inbox, notes, settings,
identity, faults, namespace. Unknown concerns follow those known concerns;
walk membership and stable morph targets are unchanged. The existing runtime
turn table is a native disclosure and wraps when expanded. The show-everything
checkbox and its dead CSS are removed.

| Images in `tmp/debug-product/` | Observed defect | Change |
|---|---|---|
| `layout-1-agent-{1440,700}.png` | Full width and concern order fixed; first capture still had the cached expanded runtime projection before adoption completed. | Verify source convergence and capture again; no ad hoc cache reset. |
| `layout-2-agent-{1440,700}.png` | Plan progress and all pending/blocked steps readable; runtime is a summary with turn disclosure. All blocks full width. | Keep one column at both widths: it reads consistently and makes task progress the first content. |
| `layout-2-{1440,700}-top.png`, `layout-2-{1440,700}-end.png` | Debug stays byte-faithful and wrapped; selected underline stays visible. | No further debug layout change in this slice. |

Adopted and published source both read
`6aa89eed-5b00-57bd-a674-de8dce871ddf`. After adoption the browser asserted
plan/runtime/inbox order, absence of show-everything, no inner scroll
containers, and exact document width at **1440 and 700 px**. Expanding the
runtime table also preserves document width. Main GET measured
**200 / 0.437193 s**; debug GET **200 / 0.003072 s**.
Fast gate: **27 tests / 184 assertions, green**. Isolated gate:
**87 tests / 583 assertions, zero failures or errors**.

## Shared-header review follow-up

The orchestrator's review widened the shell pass to both namespace pages.
The ordinary page now uses the same agent title, namespace, runtime-derived
idle/running time, cluster, navigation and collapsed message toolbar. Debug
turn metadata uses the ordinal and local time rather than a hash in prose;
token counts are grouped and include cache misses. Re-read folds use native
details/summary disclosures, retaining each emission's exact position.
The transcript uses normal document scrolling. Header, selected-turn facts,
turn links and byte total stay sticky; the last emission scrolls into view.

Screenshots `shared-shell-1-{1440,700}.png` are full documents; accompanying
`-top.png` and `-end.png` captures make the long document readable in review.
Both end captures retain the selected-turn underline. The browser found
no horizontal overflow and verified the same 177,576 bytes across both
renderings. Document scroll offsets were 16,709 and 26,301 px, respectively;
the transcript no longer owns an inner scroll position.

`shared-shell-1-agent-{1440,700}.png` confirms the new header on the ordinary
page and the separate layout defect: recency places empty Faults first,
the desktop rail clips its contents, and the narrow runtime table overflows.
The next isolated layout commit removes those constraints. These screenshots
are not a claim that the main-page layout is already fixed.

Adoption subsequently converged at
`6aa89d37-e2fa-52dc-8b25-1bb051571764`. The
`shared-shell-adopted-*` captures repeat both pages at both widths after
that convergence; all debug byte and layout assertions passed. Initial GET
was **200 / 0.003636 s**. Fast gate: **27 tests / 184 assertions, green**.
The first isolated gate had one failure in the grammar test: the newly
landed help pair adds `seon-help-instructions` to its existing CSS class.
The assertion required the entire old class attribute, despite all 2,554
help bytes remaining unchanged. This lane updated that test-only selector;
no foreign renderer was changed. Failed root: `tmp/test-runs/run.WVdXcX`.
The corrected isolated gate passed **87 tests / 583 assertions** with zero
failures or errors (`run.L15a8Y`, automatically removed). The targeted
grammar fast gate passed **2 tests / 17 assertions**.

### Row probes for the upcoming problems panel

On the adopted default database, `seon.eval/of-agent` and runtime-owned turn
refs give **30 provider turns**, **21 error evaluations** (9 fabricated
responses, 5 unreadable replies, 6 evaluation failures, 1 invalid read),
**2 repeated provider evaluations with identical source and shown text**,
and **1 provider reply with no evaluations**. Attempt usage totals are
**954,877 prompt / 890,752 hit / 64,125 miss / 7,259 completion tokens**.
The stored plan has **4 of 7 steps completed**. These are read-only probes;
the panel implementation remains a later part after the main-page layout.

## Header and shell checkpoint

Replaced the duplicated agent/debug links and always-open message bar with
one compact agent header: identity, namespace, stored objective, idle/running
state with local time, and cluster. The toolbar includes the existing three
actions, raw prompt toggle, Latest, and a collapsed Message disclosure.
Record now loads the existing agent blocks below the session on demand.

| Images in `tmp/debug-product/` | Observed defect | Change |
|---|---|---|
| `shell-1-1440.png`, `shell-1-700.png` | Header hierarchy and wrapping correct; cluster label absent with a dangling separator. | Carry the cluster name from the scoped service into the render request. |
| `shell-2-1440.png`, `shell-2-700.png` | Cluster now visible; controls aligned at both widths. Numeric turn navigation and missing problem summary remain the next specified parts. | Accepted shell layout; Record changed to an in-page disclosure. |
| `shell-final-1440.png`, `shell-final-700.png` | No horizontal overflow; objective immediately visible; message closed at rest. | Verified both PNGs, raw/colourised byte identity, typing an unsent message, and loading Record in the browser. |

Initial GET: **200 / 0.010458 s / 3,597 bytes**. Record fragment:
**200 / 0.345467 s / 33,686 bytes**. Selected turn remains
`1fb7c9d46552`, with 136 emissions and 177,576 exact UTF-8 bytes at both
widths. The first disclosure probe used an incorrect `.seon-unit` selector;
the actual existing wrapper is `.seon-walk-unit`. The corrected browser
probe passed without JavaScript errors. No message was submitted.

Fast gate: **27 tests / 184 assertions, zero failures or errors**.
Isolated gate: **87 tests / 583 assertions, zero failures or errors**;
successful root `tmp/test-runs/run.KDC2xs` removed by the gate.
Live proof used explicit hot reload and contract re-arming; adoption had
advanced to `6aa89a64-5417-5429-bb8b-37673e3d0bf6`, while publication was
`6aa89c04-03d8-5bb8-a9f2-b6ebf608888e`. These were not converged, so the
screenshots prove loaded behavior, not completed development adoption.

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
