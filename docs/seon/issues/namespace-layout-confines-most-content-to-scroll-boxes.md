---
type: issue
status: open
severity: friction
tags: [issue, render, wave/agent-context]
---

# Give namespace and debug content a usable layout

## Problem

One primary unit gets most page width; all other content is placed in a narrow
column of separately scrolling boxes. Debug embeds those scroll constraints
inside another scrolling pane. This prevents reading connected data and output.

## Evidence

Browser inspection of default root and /agent/root/debug on 2026-09-05.
Fleet occupied the broad area; maintenance records filled the narrow column.
Debug had nested vertical and horizontal scrollbars and very long schema dumps.
src/seon/render/web.clj:244-249 and :395-403 assign rank;
resources/public/css/input.css:1115-1148 puts non-primary units in column 2
with max-height 10rem. The owner explicitly rejected this UI as design guidance.

## Owner

seon.render.web layout and resources/public/css/input.css, with content choices
belonging to the selected render functions.

## Acceptance

A representative wide and narrow browser view keeps data, candidate functions
and output readable. Exact content remains reachable; per-value scroll boxes
do not dominate. Subject/viewer selection remains stable across feed updates.

## Current debug observation — 2026-09-06

The coordinated debug layout and 390-pixel containment now pass the committed
`docs/prds/context-generation/research/debug_browser_probe_2026_09_06.cjs`.
A fresh run on default basis 536871062 drew 34 nodes and 33 refs, preserved
instance/viewport/selection, and navigated actual refs with no JavaScript errors.
Screenshot `tmp/debug-current-2026-09-06.png` still exposes a content-ordering
problem: the default namespace output spends its useful prefix on the full
namespace declaration and requires, before function summaries can appear.
`src/seon/render/ns.clj` `full-html-view` places `namespace-source` immediately
after the heading; the final fit then leaves predominantly source text.

This is a selected-render-function presentation issue, not a reason to replace
the graph or add another layout owner. Keep source inspectable behind disclosure
and prioritize the namespace description and useful function summaries. A
collapsed disclosure alone is insufficient if its entire source still consumes
the same fit budget before the summaries. Verify the fitted output, not only
unfitted Hiccup or the absence of horizontal overflow.

## Summary-first renderer falsifier and correction — 2026-09-06

The first summary-first change was still wrong. Live screenshot
`tmp/debug-summary-first-2026-09-06.png` showed the namespace heading and doc,
then an elision of 4,934 of 8,210 characters, with no callable summary. The
requires value remained ahead of functions and consumed the fitted prefix.

It also treated `:seon.ns/source` as the whole source file. The live
`seon.flow` value is 1,086 characters, ends at the `AtomicLong` import, and
contains no `defn`. This matches the indexing authority: `namespace-row`
stores `exact-source` for the analyzed namespace declaration at
`src/seon/fn.clj:214-221`; function declarations independently store their
own exact source at `src/seon/sci/reader.cljc:385-402`.

The corrected `full-html-view` orders the namespace doc and compact function
summaries before requires. It keeps namespace source and every member's stored
source in trailing disclosures, without assuming one duplicates the other.
The focused final-fit regression uses sixteen actual namespace refs, two
contracted functions, and a long namespace source under the shipped 1,024-token
profile. It passed 76 assertions with no failures or errors: both callable
summaries survive the final `seon.print/fit`, exact sources remain in collapsed
disclosures before fitting, and later detail is represented by the existing
HTML elision. Browser acceptance remains outstanding, so this issue stays open.
