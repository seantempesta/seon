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

## Changed paths — this checkpoint

```text
.agents/skills/datastar-web-ui/SKILL.md
docs/prds/context-generation/plan/agent-record-and-turn-loop-prd-2026-09-07.md
docs/prds/context-generation/research/repl-display-landing-2026-09-10.md
docs/prds/context-generation/research/repl_display_probe_2026_09_10.clj
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
