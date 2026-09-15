---
type: research
status: complete
tags: [research, render, web, wave/verification-audit]
---

# Consolidate-debug — 2026-09-15

## Result

Eight findings landed in the corrected order, with an additional performance
refinement before A07. Source, tests, CSS, and skill changes total **485 lines
added, 543 removed: net −58**. Documentation and evidence scripts are excluded
from these counts. Every issue note records its resolving commit.

| Finding | Commit | Added | Removed | Net |
|---|---|---:|---:|---:|
| A08 | `1d5edb65c` | 64 | 248 | −184 |
| A09 | `209a73fd2` | 129 | 104 | +25 |
| A02 | `0439448cb` | 49 | 34 | +15 |
| A01 | `f9ed564ef` | 43 | 28 | +15 |
| A04 | `cb967dd40` | 46 | 19 | +27 |
| Performance | `5613bbf17` | 96 | 40 | +56 |
| A07 | `53a9d5db5` | 30 | 46 | −16 |
| A13 | `d5a25d704` | 10 | 4 | +6 |
| A03 | `25bce52a0` | 18 | 20 | −2 |

Read AGENTS.md, [audit-1](audit-1-2026-09-15.md), all eight linked issue notes,
the active plan README and working edge end to end. Applied the
data-oriented-clojure, repl, clojure-testing, datastar-web-ui, datahike, and
llm-providers skills. No delegation, default stop/refork/reseed, message send,
foreign session operation, or `--all`/`--full` run occurred.

## Changes and verification

- **A08:** deleted Context-now assembly, feed targets, obsolete CSS/tests and
  the stale skill assertion. Entity inspection uses the session component.
  The two authorized `turn_test.clj` groups migrated from deleted helpers to
  `render-ledger-turn`: exact source/namespace, no clipping/read-evidence leak,
  unchanged database basis, and each generated source remain asserted.
  The commit message records before/after assertions. `bin/css` succeeded;
  `output.css` was not committed. Initial fast 39/580 and isolated 39/584
  runs exposed five evaluation/history failures retained for A09; the later
  render gates below passed after the owning provenance repair landed.
- **A09:** one `seon.eval/of-agent` membership/order/absence owner with an
  additive selector arity; one ledger summary for emissions, outcomes, bytes,
  and usage. Net growth is the owner contract and regressions, not a persisted
  summary. Fast 15/195, isolated 15/199 assertions passed.
- **A02:** one turn-kind derivation feeds cards, grouping, and strip. Completion
  comes from disposition; source-name scanning is deleted. Virtual turns retain
  authored replies and stop generated-context grouping. Net growth is regression
  coverage (production +1). Fast 10/146, isolated 10/150 passed.
- **A01:** directory observations follow saved read evidence and the directory
  owner's declared renderer. Removed duplicate public-function query and source
  operator recognition. Missing/elided/unrecognized observations are unknown.
  Net growth covers qualified-directory and unavailable regressions.
  Fast 10/151, isolated 10/155 passed.
- **A04:** compares captured history bytes after excluding the owner's turn
  frame. Removed billing tolerance and billing-derived stability. Totals say
  “Billing tokens.” Regression varies captures independently of counters and
  verifies missing observations. Fast 10/157, isolated 10/161 passed.
- **Performance:** history now carries saved rows alongside rendered bytes;
  the ledger's second query is deleted. History and per-attempt captured-prefix
  projections reuse the existing render-call cache. History uses existing
  candidate-call selection and one profile per acquisition. No new cache or
  expiry policy. Added a regression proving reuse and invalidation of changed
  saved bytes. The deferred-session test requires one current-history acquisition
  and no additional selected-prompt acquisition. Fast 15/225, isolated 15/229
  passed. Net +56 is retained-call wiring and regression evidence.
- **A07:** deleted renderer-symbol labels, source-token classification, and
  code-literal page ranks. Titles derive from relationship/schema declarations;
  page order preserves the walk. A renderer rename retains its declared title.
  Fast 15/227, isolated 15/231 passed. See the schema boundary below.
- **A13:** trigger/history links carry only the relationship/identity label;
  the message pair renders complete content once. Production −2; the exact-once
  regression accounts for net growth. Fast 11/188, isolated 11/192 passed.
- **A03:** reread shared `render.clj` immediately before its single patch; its
  diff was clean, and the other lane's landed frame comparison was preserved.
  Deleted the repull/reformat/rejoin retry and the session's separate row pull.
  A real saved-history regression requires a typed mismatch even when repulling
  the unchanged rows could repair altered canonical bytes. Corrected its missing
  connection fixture input. Fast 10/162, isolated 15/236 passed.

Counts above are tests/assertions. Iteration used `bin/test-fast --paths ... --`
and commits used `bin/test --paths ... --` with the affected render namespaces;
A08 additionally covered the explicitly authorized turn test migration.

Final platform gate:

```sh
bin/test --platform --paths src/seon/render.clj src/seon/render/transcript.clj test/seon/render/web_debug_test.clj --
```

**84 tests, 505 assertions, zero failures or errors.** HEAD-plus-paths excludes
foreign uncommitted edits. This is the platform tier, not a full-suite claim.

## Warm GET timing

Read-only default measurements, three samples each:

```sh
curl -s -o /dev/null -w '%{time_total}\n' http://127.0.0.1:7994/ns/my.agents.juniper/debug
```

| State | Sample 1 | Sample 2 | Sample 3 |
|---|---:|---:|---:|
| Before | 1.764572 s | 1.572908 s | 1.759266 s |
| Accepted warm result | 0.704460 s | 0.755098 s | 0.741225 s |

One warm-up GET preceded the accepted samples. The first GET after adoption
had taken 1.162832 s; the measured feature here is warm GET latency. Acceptance
followed convergence `6aa8de2b-2803-5019-82e6-a5c473b1bd29`, before Juniper's
live facts changed during the later A13 screenshots.

The [timing probe](consolidate_debug_timing_2026_09_15.clj) binds the same schema
projection as HTTP. Initial corrected measurements: rows 5.04 ms, evaluation
acquisition 1552.50 ms, summary 0.26 ms, prefix 370.33 ms, whole panel 673.89 ms,
ledger 2162.70 ms. The panel includes prefix cost. Unbound preliminary probes
timed out and are not representative HTTP evidence. The cache-only iteration
was rejected at 3.186407/3.023597/3.021544 s. One history acquisition, existing
candidate selection, and retained per-attempt prefixes produced the accepted
result; summary reduction was not the expensive mechanism.

## Screenshot log

The committed [Playwright script](consolidate_debug_browser_2026_09_15.cjs) uses
`NODE_PATH=/Users/sean/.npm/_npx/e41f203b7505f1fb/node_modules`. At each label,
both `/ns/my.agents.juniper/debug` and `/ns/my.agents.juniper` were captured at
1440 and 700 and LOOKed at. Every route returned 200 with zero horizontal
overflow. Inspection was also captured; A13 added dedicated Runtime captures.
Screenshot scratch is deleted after review, per the assignment.

| Label | Visual observation |
|---|---|
| before | Baseline ledger/header and full-width plan. |
| a08-authorized | Session replaces Context-now; inspection has one navigation/header. |
| a09 | Ledger, panel, plan intact after projection consolidation. |
| a02 | Authorship/disposition changes preserve layout. |
| a01 | Directory unavailable count is explicit; layout intact. |
| a04 | Billing label and captured-prefix check preserve panel/ledger. |
| perf | Four main/debug screenshots inspected; layout matches A04. |
| a07 | Schema-key labels and Identity-before-Plan visibly replace literal labels/order. |
| a13 | Four page plus two Runtime screenshots inspected: short trigger label and one complete message. |
| a03 | Six main/debug/inspection screenshots inspected: selected turn 36 shows 55 emissions and 44,187 history bytes without a capture-mismatch error. |

Juniper's live facts changed during A13 captures: a new opening at 00:02:45,
progressing plan/turn counts, and later turn 36. These are not presented as an
unchanged fixture comparison. The lane did not reseed or initiate those turns.
A03 definitions reloaded but adoption reported “Source changed during development
adoption” at `6aa8e1ce-5f70-56ae-99b2-56994ea7f558`; its browser observations
prove loaded behavior, not full publication convergence.

## Ownership and remaining declaration needs

**A07 schema boundary:** `:seon.agent/plan`, `:seon.agent/settings`,
`:seon.agent/runtime`, and note/inbox relationship declarations lack human
`:title` metadata. Their owners are `resources/seon/schemas/seon.agent.edn`,
`my.note.edn`, and `seon.message.edn`. Titles remain explicit schema keys.
The walk places the root Identity block before the declared children. Restoring
short prose titles and the old task-first placement requires authored title
and root-placement metadata at the declaration seam; no renderer roster or rank
was retained to conceal that missing fact. A07 is readable and overflow-free,
but is not claimed pixel-identical to the old order.

Protected source owners were preserved. No edit to `cluster/prompt.clj`,
`repl.clj`, `db.clj`, `turn.clj`, `cluster/**`, `print.cljc`, or schemas was
required. `render.clj` received only the A03 retry deletion. Later concurrent
CSS/source/test edits remain untouched; generated `output.css` is not committed.

At entry MCP runtime status threw MapEntry-to-IPersistentMap ClassCastException;
supported JVM evaluation worked. Recorded in the existing
[development MCP issue](../../../seon/issues/dev-mcp-envelopes-misdirect-errors-and-sprawl-status.md).
No raw-prepl workaround or foreign-session operation was used.

## Dependency ledger and cleanup

- Clojure prepl: `reference-code/clojure/src/clj/clojure/core/server.clj:228`.
- Existing retained calls and shared cache: `src/seon/render.clj:1236,1408`.
- One saved-history walk and acquisition: `src/seon/render/walk.clj:875`,
  `src/seon/render/web.clj:2400`; canonical database fixture and armed SCI.
- Ledger summary and captured prefix: `src/seon/render/transcript.clj:1483,1821`.
- Capture equality verifier: `src/seon/render.clj:1422`.

All owned commands are awaited. Failed lane roots were checked against live
processes before removal; already-reaped roots were left alone. Only lane-named
logs, images, patches, and scripts under `tmp/` are removed. No worktree or
scratch cluster was created. Foreign lanes and their disposable roots are not
swept. Reproducible browser/timing scripts and measured results remain committed.
