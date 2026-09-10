---
type: research
status: active
tags: [research, render, agent]
---

# REPL display — 2026-09-10

## Rule and dependency ledger

Owner ruling: AI data has no width-driven line breaks. Keep authored source
and renderer-authored meaningful lines (help) intact. HTML formats for humans
at 100 columns and identifies the response renderer. The owner's follow-up
requires browser wrapping on every debug text surface (`pre-wrap`,
`overflow-wrap: anywhere`, `word-break: normal`), with no horizontal scroll.
This supersedes the initial scrolling direction; AI
presentation remains bounded by its profile; HTML never applies that profile's
presentation cuts. Saved AI observations remain unchanged.

The lane rules are the verbatim copy at the top of AGENTS.md from turn PRD
§10; the bounded projection law is AGENTS.md §2.4. Turn PRD §18d owns the
prompt/input/response grammar. No other lane was running. Untracked `build/`,
`workers/`, and `config/virtual-turns.edn` were inherited and preserved.

Dependencies read before editing:

- Clojure pprint `reference-code/clojure/src/clj/clojure/pprint/pprint_base.clj:197`
  supplies readable pretty output with `:right-margin` and unlimited length
  and level; `src/seon/repl.clj:22` already uses it for generated source.
- `src/seon/print.cljc:205` owns width-driven separators. Its existing width
  zero disables wrapping; `src/seon/render/value.clj:416` selects it for AI.
- `src/seon/sci/eval.clj:508` binds actual objects in the agent context;
  `src/seon/cluster/agent.clj:486` supplies the existing armed entry. HTML
  reads that context's result map; it never evaluates saved source again.
- `src/seon/render/value.clj:304` and `src/seon/render.clj:1070` already select
  declared HTML pairs. `src/seon/bootstrap.clj:70` already renders help as a
  list; the missing integration was the evaluation HTML entry.

## Before bytes

Read from default's live JVM using `seon.render.value/render-ai`, current
agent profile, and a retry settings value:

```clojure
[#:seon.config.ai.retry{:base-delay-ms 500, :jitter-fraction 0.25, :maximum-delay-ms
    4000, :maximum-retries 2, :maximum-total-delay-ms 3000, :multiplier 2.0}]
```

The live Juniper page also broke `:maximum-retries` from `2` and broke plan
items inside their maps. The ordinary debug URL had no Context now section;
it ended with an Inspect context algorithm link. Help's HTML list pair existed
but the evaluation entry emitted its AI lines inside a preformatted block.

## Verification

Matched settings probe after in-place loading: 162 → 158 UTF-8 bytes;
2 → 1 physical lines; both strings read as equal EDN. The before string
reproduced the initial live observation with the old width 80. Exact after:

```clojure
[#:seon.config.ai.retry{:base-delay-ms 500, :jitter-fraction 0.25, :maximum-delay-ms 4000, :maximum-retries 2, :maximum-total-delay-ms 3000, :multiplier 2.0}]
```

Rerunnable evidence: `repl_display_probe_2026_09_10.clj` in this directory,
loaded through MCP JVM mode on default, 994 ms. No stored evaluations were
rewritten. Native Chrome at 768 px showed Context now first, annotations,
wrapping text, and the collapsed comparison; final adoption checks follow.

The isolated HEAD `03ae6aa3c` baseline for `seon.render.web-context-test`
ran 4 tests / 33 assertions with 4 failures. Three expected the retired
context-generation cost writes; the remaining adoption assertion never
changed the source commit because its partial cluster-map write was refused
for missing `:seon.cluster/config`. The test now updates the existing fact
with `:db/add` and checks the write's report. The HTML-pair reuse check now
counts HTML invocations; the newly always-visible system preview separately
invokes AI source generation. No cache production change was needed.

Unrelated edits appeared during work in the help trial, AI test, config,
and MCP issue note. All remain outside this lane's snapshots and commits.
The isolated HEAD debug baseline ran 9 tests / 35 assertions with 9 failures
and 1 error. Its synthetic schemas omitted the required admission source;
the history fixture omitted evaluation time and its plan's owner identity;
the expected component list omitted the runtime component. These fixtures
now supply the canonical declarations. The HTTP history check verifies
ordered inputs and exact saved AI bytes in the comparison, allowing HTML
to format responses for people.

Native Chrome was resized from a full window to 768 × 897. Both
`/ns/my.agents.juniper/debug` and `/ns/my.agents.root/debug` showed Context
now first, wrapped help lists, and renderer annotations. Juniper's expanded
comparison preserved indentation and wrapped long lines at the container;
no horizontal scrollbar was visible. Root's ordinary `(dir my.edit)`,
`(dir my.agent)`, and `(dir my.plan)` entries used the live value printer.
The system-turn entries instead used saved text, even without restart:
the turn owner discards their preview result objects. That separate boundary
is recorded in `docs/seon/issues/system-turn-drops-live-results-after-saving-shown-text.md`.
No default restart, refork, stop, compaction, or reseed was performed by this lane.

MCP runtime status timed out; direct JVM evaluation answered. Recorded in
`docs/seon/issues/default-component-probe-times-out-after-adoption.md`.
Browser tab automation initially reported no browser; native app inspection
was available. `SEON_TEST_WORKERS=3 bin/test --platform` passed 84 tests /
505 assertions, zero failures/errors (104 seconds coordinator and tests).
`bin/css` rebuilt `input.css` into `output.css` in 99 ms; both are included
in this change, explicitly tracking the normally ignored generated file.

AGENTS.md, the named turn PRD, the plan README, and its unsettled working edge
were read end to end. The initial slice exceeded 30 minutes while correcting
the reproducible fixture failures and completing the required gates; no
failing gate was presented as green.

Incremental publication additionally refused a test row missing
`:seon.test/usage`; the exact boundary is recorded in
`docs/seon/issues/incremental-publication-refuses-test-usage.md`.
The existing complete-publication path is used for final adoption.

The final path-limited gate passed 115 tests / 652 assertions, zero
failures/errors. It selected `seon.repl-grammar-test`, `seon.repl-test`,
`seon.render.value-test`, `seon.render.web-context-test`,
`seon.render.web-debug-test`, and `seon.render.web-test`, overlaying only
the three production Clojure files, the two CSS files, and the five changed
test files listed below. Two isolated workers ran alongside one fast JVM;
this lane never exceeded three test workers and never ran `--all`/`--full`.

## Post-commit browser proof

Implementation commit: `6f339bc6e`. A separate Chrome context resized each
page from 1100 to 500 × 900 and opened the comparison. Juniper had 40
preformatted surfaces; root had 91. Every surface computed `pre-wrap`,
`overflow-wrap: anywhere`, and `word-break: normal`, with no horizontal
text overflow. Both document widths equalled the 500 px viewport; no
visible element extended beyond it. The primary REPL, help list, renderer
annotations, and expanded comparisons were inspected in screenshots.

The first resize observation ran before Cytoscape's own debounced resize.
Its existing `ResizeObserver` calls `cy.resize()` after 100 ms
(`reference-code/cytoscape/src/extensions/renderer/base/load-listeners.js:328`).
The browser probe now awaits the actual nonempty canvas dimensions under a
30-second backstop; no second observer or graph production change was added.

Rerun `repl_display_browser_2026_09_10.cjs` with Playwright on `NODE_PATH`.
It owns and closes its browser, verifies both pages, and writes disposable
screenshots under `tmp/repl-display-browser/`. Native Chrome's window
became unavailable after the earlier 768 px inspection; this independent
browser supplied the final post-commit proof.

Complete adoption subsequently refused source commit
`6aa31404-2e94-5684-aa9e-8614a871fc7c` because source changed during adoption.
A live comparison (186 ms) identified exactly
`test/seon/data_shapes_test.clj` as changed since the analyzed snapshot.
That is a foreign edit boundary; this lane did not edit that path, operate
its session, or restart default. The comparison was:

```clojure
(let [before (:seon.source/file-digests
              (:seon.source/snapshot @@#'seon.cluster/source-analysis-cache))
      after (:seon.source/file-digests (seon.cluster/source-snapshot))]
  (vec (filter #(not= (get before %) (get after %))
               (distinct (concat (keys before) (keys after))))))
```

## Final adoption and cleanup

Complete publication subsequently succeeded. MCP verified in 1790 ms that
default's adopted commit and `seon.cluster.source/current` both equal
`6aa31517-2ccf-50a2-a61a-9f0a9261b8e2`; publication digest
`ff61ab15259c07f1117ce96557b90fdc030822ae37a545724c824f444c1b380b`.
This was in-place development adoption, not a restart or a new fork.

The browser probe was rerun after convergence. Juniper then had 46
preformatted surfaces and root 91; both again measured 500 px viewport /
500 px document width, zero overflowing elements, and zero wrapping-rule
failures. Primary views and expanded comparisons were inspected. The live
scenario changed evaluation counts during the lane; the measurements are
dated observations, not fixed fixture counts.

The final evidence change contains only this note and the browser probe.
The Clojure and CSS bytes remain those of the green implementation commit.
Completed failed snapshots were removed after their runners exited; successful
gate and fast snapshots removed themselves. The probe closes its browser in
`finally`, and disposable logs and screenshots are removed before reporting.

## Changed paths — this checkpoint

```text
.agents/skills/datastar-web-ui/SKILL.md
docs/prds/context-generation/plan/agent-record-and-turn-loop-prd-2026-09-07.md
docs/prds/context-generation/research/repl-display-landing-2026-09-10.md
docs/prds/context-generation/research/repl_display_probe_2026_09_10.clj
docs/prds/context-generation/research/repl_display_browser_2026_09_10.cjs
docs/seon/architecture/ui.md
docs/seon/issues/default-component-probe-times-out-after-adoption.md
docs/seon/issues/system-turn-drops-live-results-after-saving-shown-text.md
resources/public/css/input.css
resources/public/css/output.css
src/seon/repl.clj
src/seon/render/value.clj
src/seon/render/web.clj
test/seon/repl_grammar_test.clj
test/seon/render/value_test.clj
test/seon/render/web_context_test.clj
test/seon/render/web_debug_test.clj
test/seon/render/web_test.clj
docs/seon/issues/incremental-publication-refuses-test-usage.md
```
