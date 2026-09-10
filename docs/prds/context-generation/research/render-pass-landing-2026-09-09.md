---
type: research
status: complete
tags: [render, context, plan, testing]
---

# Render pass — 2026-09-09

The plan comment is one line. `(doc my.plan)` returns the namespace's
stored documentation and its three executable examples: identity upsert,
completion by one `:db/add` with `"datomic.tx"`, and `retractEntity`.
ID-only plan dependencies show vectors of ids; richer pulls retain their
attributes and live results are unchanged. Effective settings show model,
no-provider, evaluation deadline, turn budget, retry, and remaining turns.
`:seon.config.agent/show-all-settings true` requests all agent dials.
The Turns concern shows opened, trigger, evaluation count, and reply
status (`Recorded`/`None`), without embedding the reply's evaluation sources.

Read AGENTS.md's copied §10 lane rules, the issue end to end, and chart
§§2, 3, 9.4 end to end. Chart §14a was absent at HEAD; read its complete
text from `7ec3a5bbc` and restored the current ruling. Read the roadmap
entry and working edge, and the Clojure, REPL, testing, Datastar, schema,
database, and configuration skills. No delegated work or paid trial.

## Exact bytes and observations

| Surface | Before | After | Reduction |
|---|---:|---:|---:|
| Stored opening, 9 evaluations | 9,416 | 8,114 | 1,302 |
| Complete provider prompt, 11 evaluations | 11,376 | 9,724 | 1,652 (14.5%) |
| Default Turns concern, extracted text | 2,091 | 328 | 1,763 |

The before history is default's existing fixture; the after history is the
same canonical order scenario freshly seeded on `tmp/render-pass-root`,
cluster `render-pass`. Both entire prompts were read. These are UTF-8
bytes, including actual result handles and measured durations, without
normalizing identities or timings. The opening measure joins stored
`seon.repl/text` entries with one newline; the provider measure comes
directly from `seon.render/acquire-context!` through the canonical fixture.

Exact captures: [before prompt](render-pass-before-2026-09-09-context.txt),
[after prompt](render-pass-after-2026-09-09-context.txt),
[before opening](render-pass-before-2026-09-09-opening.txt),
[after opening](render-pass-after-2026-09-09-opening.txt),
[before metadata](render-pass-before-2026-09-09.edn),
[after metadata](render-pass-after-2026-09-09.edn),
[before Turns](render-pass-before-2026-09-09-turns.txt), and
[after Turns](render-pass-after-2026-09-09-turns.txt).

The initial default URL was read before source inspection. Default's
served HTML was read again after in-place adoption: three headers,
evaluation counts 0/2/9, three recorded replies, and no evaluation source
in the Turns section. Its saved Context now remains historical; adoption
does not rewrite old shown text. The scratch page returned HTTP 200 and
showed the new opening. Scratch provider attempts: **0**. Default's
pre-existing attempt count stayed **1**.

Default retained PID **83040**. Its adopted and published source commits
both equalled `6aa2187e-9fa6-5461-8dc3-c10a1668e1ce`. No default stop,
restart, refork, reseed, or compaction was performed.

## Verification

`SEON_TEST_WORKERS=3` for every test command. Fast iteration:
29 tests / 179 assertions, green. The isolated path gate:
29 tests / 183 assertions, green. Separate platform gate:
84 tests / 505 assertions, green. No `--all` or `--full` run.

The selected namespaces were `seon.render.page-settings-test`,
`seon.render.page-review-test`, and `seon.render.value-test`. The gate
used `bin/test --paths` with the seven production/schema paths below,
the three test paths, and `context_page_probe_2026_09_09.clj`, followed by
`--` and those namespaces; the platform command used the same paths and
`--platform`. Tests use canonical database population, real SCI, and armed
contracts. They execute the documented writes, verify completion's
transaction instant and removal, exercise the full-settings dial, and
put actual source in a stored reply to verify the header cannot repeat it.
Default SCI evaluation also returned the complete `(doc my.plan)` map.

Reproduce the capture by loading
[render_pass_probe_2026_09_09.clj](render_pass_probe_2026_09_09.clj) and
calling `capture!` with the selected cluster and a label. It loads the
canonical fixture helpers but does not seed or call a provider. For the
HTML evidence, save the debug response and run
[render_pass_page_2026_09_09.py](render_pass_page_2026_09_09.py)
with the HTML path and output-text path.

## Dependencies, owners, and boundaries

Datahike gitlink `cdcb5792db8bd599487f099437265d18a31164a5`:
`reference-code/datahike/src/datahike/db/transaction.cljc:738` expands
identity-addressed maps; `:1059` dispatches `:db/add` and `retractEntity`.
SCI gitlink `fcbd8862800e638dc0f8f5521111f999279cbcd2`:
`reference-code/sci/src/sci/core.cljc:260` owns interning; the existing
`program-doc-var` macro in `src/seon/sci/eval.clj:1142` now falls back to
an ordinary namespace-doc pull, preserving read evidence.

Production files touched: `src/my/plan.clj`, `src/seon/plan.clj:1160`,
`src/seon/agent.clj:75`, `src/seon/render/value.clj:225`,
`src/seon/render/transcript.clj:858`, `src/seon/sci/eval.clj:1142`, and
`resources/seon/schemas/seon.config.agent.edn`. Tests touched:
`test/seon/render/page_settings_test.clj`,
`test/seon/render/page_review_test.clj`, and
`test/seon/render/value_test.clj`. Supporting paths are the capture
artifacts/scripts linked above, the existing cookbook probe, the chart
PRD, this landing, the archived issue, and the two existing issue updates.

No foreign in-flight edit blocked verification. The known
[fixture readmission defect](../../../seon/issues/fixture-schema-readmission-after-adoption-refuses-environment.md)
recurred after scratch adoption: unresolved `:example/order-row` and the
`seon.env/advance-projection!` contract refusal. A fresh scratch fork of
the same publication seeded successfully. This is the reset-boundary
proof; in-place fixture re-admission is not claimed fixed.

[Browser observation remains unavailable](../../../seon/issues/browser-ui-observation-has-no-accessible-window.md):
CUA returned no browsers and `cgWindowNotFound` for native Chrome and
Safari. Served HTML and exact prompt bytes were verified; browser paint
was not. The two existing issues carry this pass's evidence.

Inherited `build/`, `config/virtual-turns.edn`, and `workers/` were
preserved. The scratch root and owned shells are cleaned after capture;
successful isolated gate roots were removed by their runner.
