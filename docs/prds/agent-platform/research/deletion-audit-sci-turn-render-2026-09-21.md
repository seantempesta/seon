---
type: research
status: draft
created: 2026-09-21
tags: [deletion-audit, sci, core-async-flow, datastar, hyperlith, seon.turn, seon.render, duplicate-cache, duplicate-mechanism, namespace-agents]
---

# Deletion audit — agent execution and presentation

Read-only audit of the SCI evaluation seam, the turn loop, and rendering /
printing, judged against the vendored dependency source. Every row was read on
both sides. No JVM was run; every number below is a line count or a value read
out of a file, never a measurement.

Sizes audited: `src/seon/turn.clj` 5,417 · `src/seon/render/web.clj` 3,694 ·
`src/seon/sci/eval.clj` 3,382 · `src/seon/render/transcript.clj` 2,443 ·
`src/seon/render.clj` 1,891 · `src/seon/plan.clj` 1,887 · `src/seon/ai.clj`
1,682 · `src/seon/issue.clj` 1,510 · `src/seon/print.cljc` 1,377 ·
`src/seon/flow.clj` 1,364 · `src/seon/cluster/agent.clj` 1,048 ·
`src/seon/render/walk.clj` 1,016 · `src/seon/sci/reader.cljc` 944 ·
`src/seon/render/ns.clj` 936 · `src/seon/sci/admit.clj` 904 ·
`src/seon/sci/kernel.clj` 750 · `src/seon/cluster/message.clj` 724 ·
`src/my/program.clj` 686 · `src/seon/render/value.clj` 680 ·
`src/seon/cluster/wake.clj` 577 · `src/seon/repl.clj` 517.

**Estimated net deletion: ~4,800 lines of `src/` and ~4,500 lines of `test/`
(~9,300 total).** The three largest single deletions are
`seon.render.transcript`'s hand-assembled history and its three extra views
(1,586 lines, deleted by PRD §15 in writing and never cut), `seon.flow`'s work
launcher (704 lines, which `flow/futurize` + `:compute-timeout-ms` already is),
and `seon.render.web`'s parallel debug derivations plus package revisioning
(~690 lines). Six of the mechanisms listed below have no test claiming them at
all.

The forks we own already carry the seams that make most of this deletable:
`sci` commit `72150fd4` ("Make forked Vars copy on write") stamps
`:sci/generation` on every Var a fork creates
(`reference-code/sci/src/sci/impl/utils.cljc:356-380`,
`generation-meta` / `bind-root!`), and `d84a0106` / `2217449d` / `1ed03a69`
expose `namespace-state`, `namespace-bindings`, `namespace-interns`,
`install-namespace-bindings!` (`reference-code/sci/src/sci/core.cljc:679-800`).
`core.async.flow` already owns bounded compute submission with a deadline
reported on `::flow/error`
(`reference-code/core.async/src/main/clojure/clojure/core/async/flow/impl.clj:245-260`,
`:313-318`) and per-workload executors
(`.../impl/dispatch.clj:82-116`). `hyperlith` delivers a whole Datastar view per
batch over one `(dropping-buffer 1)` tap in ~60 lines
(`reference-code/hyperlith/src/hyperlith/impl/datastar.clj:119-180`).

---

## 1. What re-implements SCI

| src span | lines | duplicates / seam | recommendation | what preserves the function | risk |
|---|---|---|---|---|---|
| `sci/eval.clj:2100-2183` `base-bindings`, `same-program-root?`, `regenerate-agent-context!` | 84 | Snapshots EVERY namespace binding in the ctx (`sci/namespace-state`, core.cljc:751) on every fork, then diffs roots to find the private layer. The fork already answers this: `:sci/generation` on the Var's meta (`sci/impl/utils.cljc:356-360`; `bind-root!` `:362-380` copies an inherited Var and restamps it). `regenerate-agent-context!:2126` ALREADY tests generation — the snapshot only exists to un-count contract wrappers arming wrote into the fork | delete the snapshot and the diff; keep generation equality | fork change: `seon.instrument` arms at the base ctx, or marks wrappers so `:sci/generation` alone discriminates | med — arming currently writes into the agent fork |
| `sci/eval.clj:2184-2217` `fork-for-turn` | 34 | `sci/fork` (core.cljc:345) already is "new/redefined Vars invisible to the original". The 5 atoms it re-wraps per turn (`::base-bindings`, `::result-objects`, `::kernel/installed-functions`, `::kernel/program-snapshot`, `::print-session`) are copies of ctx-carried state | shrink to `sci/fork` + `::result-objects` | PRD §14: "each agent keeps ONE live context … receives the diff" — the retained handle already does | low |
| `sci/kernel.clj:108-182` `cache-program!`, `cache-function!`, `program-function`, `program-namespace`, `public-functions-in`, `mark-installed!`, `ensure-function!` + `eval.clj:273-274, 2148-2154, 2197-2204, 2384-2386` | ~120 | Two atoms per ctx mirroring database program facts (`::program-snapshot`, `::installed-functions`), re-copied on every fork/regenerate. "Is this Var installed?" is `sci/resolve`; "what is this function's row?" is a Datalog pull over the ctx's own database value (§2.2 bans hand-maintained mirrors) | delete both atoms; `ensure-function!` keys on `sci/resolve` | the database value already rides the ctx (`base-ctx:2218`, `:seon.db/db` in the snapshot) | med — `render.clj:293,679` read the snapshot |
| `sci/eval.clj:1052-1099` `installation-covers-program-change?` | 48 | A whole-database `since`-diff + unbounded component-parent closure + `pull-many` + `canonical-row` comparison, run per install batch, solely to decide whether to mark the snapshot acquired instead of reacquiring. AGENTS "SECONDS, NOT MINUTES": "A no-change request is two commit ids compared: milliseconds." | delete; compare `db/committed-value-identity` as `acquired-database?:2252` already does | `acquired-database?` is the surviving answer | low |
| `sci/eval.clj:1771-2069` `acquire-program!` | 298 | Walks EVERY namespace, EVERY function row and EVERY test row of the program from the database on each acquisition, topologically sorts, and installs — the O(program) step the one-JVM redesign targets. Its `:core` branch only copies already-loaded JVM Var roots | keep the interpreted `:agent` path; delete the `:core` walk in favour of `sci/copy-ns`-style binding of loaded namespaces once at boot | agent-authored rows still interpret; core rows are JVM Vars that never changed | high — this is the acquisition seam |
| `sci/eval.clj:1399-1670` `program-documentation`, `documentation-*`, `directory-value`, `documentation-value`, `program-doc-var`, `program-dir-var`, `install-program-doc!` | 272 | `doc`/`dir` are declared to return DATA from program rows (vocabulary table; PRD §13). 272 lines assemble docstring parts, contract schemas and agent-specific contracts inside the SCI installer | collapse to one pull of `:seon.fn/{sym,arglists,doc,spec}` rendered by the declared pair | PRD §13 "`(dir ns)` returns DATA from program rows" | low |
| `sci/eval.clj:1173-1398` `classpath-locatable?`, `host-namespace!`, `load-core-namespaces!`, `install-first-party-namespaces!`, `install-host-namespace!`, `install-declared-classes!` | 226 | Re-implements namespace loading and class import that `sci/init` `:namespaces`/`:classes` and our fork's `install-namespace-bindings!` (core.cljc:771) already take as data | fold into one `install-namespace-bindings!` call per namespace row | our own fork seam, added for exactly this | med |
| `sci/eval.clj:2070-2099` `latest-print-fact`, `session-print-options` | 30 | Derives `*print-length*`/`*print-level*` by a history query over the agent's evaluations on every fork. SCI already keeps session bindings in the retained ctx; the profile is the one presentation authority (§2.4) | delete; the render profile carries it | §2.1 — the value carries its world | low |
| `sci/kernel.clj:31-97, 216-531` guard, arming, deadline, `interrupted?` | ~280 | Correct and NOT duplicated: `:interrupt-fn` + `sci.interrupt/interrupt!` is sci's own hook (`reference-code/sci/doc/interrupt.md`), and sci counts nothing | **keep** | — | — |
| `sci/eval.clj:3170-3381` `fork-candidate-ctx`, `install-candidate-function!`, `evaluate-for-install`, `accept-candidate!`, `refuse-install`, `run-tests`, `evaluate-candidate`, `auto-check-candidate` | 212 | A second install path beside `install-row!`/`install-evaluated-rows!` (§2.5: no second registry). `sci/fork` is the candidate ctx; the writer decides acceptance | delete `fork-candidate-ctx` + `accept-candidate!`; keep `evaluate-candidate` calling `sci/fork` directly | the transaction is the authority (§"no seam may act on a pre-read") | med |

Printing of results is **not** a SCI duplicate: `print.cljc` produces elision
values with requery forms and a Hiccup twin, which `clojure.pprint` cannot.
Keep it. `repl.clj:50-274` (`frame`, `response`, `text`) is the one REPL grammar
and stays.

---

## 2. core.async.flow, and turn.clj's mechanisms

### 2a. What fights flow

| src span | lines | duplicates / seam | recommendation | what preserves the function | risk |
|---|---|---|---|---|---|
| `flow.clj:241-944` the work launcher — `with-current-arm`, `submission-capacity-error`, `refuse-compute-submission!`, `acquire-admission!`, `RefusingBuffer`, `execute-work!`, `io-terminal!`, `execute-io-work!`, `work-launcher-step/proc/graph-definition`, `start/stop-work-launcher!`, `submit!`, `submit!!` | 704 | `flow/futurize` + `:workload :compute` + `:compute-timeout-ms` is exactly this: the transform is submitted to the compute executor and `.get`-ed under the deadline, timeouts reported on `::flow/error` (`flow/impl.clj:245-260, 313-318`); `:io-exec/:compute-exec/:mixed-exec` on `create-flow` supply our bounded executors (`flow.clj:96-107` of the dependency). Two callers only: `turn.clj:3358` and `effect.clj:991` | delete; both callers use `flow/futurize` with the cluster's `:compute` executor | the bound stays declared at the seam (§2.3) — it moves from our loop to flow's | med |
| `turn.clj:5053-5262` `turn-completion-backstop-failure`, `await-turn-permit!`, `offer-write-refusal-fault!`, `offer-turn-backstop-fault!`, `arm-turn-completion-backstop!` | 210 | A per-turn watchdog that pins an executor thread in an `alts!!` loop over cancel/parts/timeout channels plus a `backstop-state` atom — the same deadline flow enforces for a `:compute` proc. The turn proc is declared `:io` (`cluster/agent.clj:494`) precisely to avoid flow's timeout, then rebuilds it | delete; declare the turn transform `:compute` with `:compute-timeout-ms` | `turn-completion-error:5011` stays as the diagnostic constructor on `::flow/error` | med |
| `flow.clj:192-240` `capacity-facts`, `capacity-observer-step/proc` | 49 | `flow/ping` already returns per-proc status and `:ping-map-fn` state (`flow.clj:136-155` of the dependency); `core.async.flow-monitor` is vendored | delete | `ping` | low |
| `cluster/agent.clj:95-123` `CountedSlidingBuffer` + `wake-channel`; `:442-464` `mailbox-step`; `:508` the one conn | 55 | A proc that only relays: it converts a wake into a payload-free `:seon.agent/episode` and counts deliveries. The turn proc can take the wake channel as its own `::flow/in-port` — the same construct `mailbox-step:456` uses. `:dropped` is read by nothing but `toString` (`:113-116`) | delete the proc, the buffer type and the conn; `(async/sliding-buffer 1)` on the turn's in-port | the wake still coalesces by the same argument the docstring gives | low |
| `flow.clj:945-1130` `CountedDroppingBuffer`, fault committer | ~185 | The counted-dropping buffer for observation is ruled (vocabulary table) | **keep** | — | — |

### 2b. `turn.clj`, 5,417 lines, mechanism by mechanism

| mechanism | span | lines | verdict |
|---|---|---|---|
| `open?`/`terminal?`/`opening-db`/`interrupted-warning` | 215-288 | 74 | **essential derivation** — `open?` is "no `closed-tx`" |
| transitions `open-call`, `close-call`, `close-tx`, `open-run-tx-call`, `require-open-run` | 305-495 | 190 | **essential fact transitions** on the serial writer |
| `next-id`, `receipt-identity` | 525-551 | 27 | essential; `seon.id` is the one derivation |
| `source-rows`, `plan-call`, `open-tx` | 566-693 | 128 | essential |
| `system-run-call/tx`, `append-generated-call/tx`, `generated-run-tx` | 694-823 | 130 | essential (system turn 0, PRD §14) |
| receipt start/settle `receipt-start-tx/call`, `receipt-settle-*`, `record-evaluated-*` | 824-1000, 1369-1703 | 511 | essential, but carrying the retired `receipt` spelling (vocabulary table: legacy) |
| schema-change machinery `affected-schema-attributes`, `current-schema-data-attributes`, `assert-schema-data-unused!`, `schema-attribute-change-tx` | 1001-1065 | 65 | **misplaced** — belongs to `seon.schema`; the writer's final-report validator (`db.clj:3014`) is the authority |
| declaration shape helpers `cardinality-many?`…`declared-content` | 1066-1124 | 59 | **duplicate** of `seon.schema.datahike` / `seon.program/canonical-row` |
| `declaration-written-by-run?`, `declaration-diverged-since-open?` | 1125-1202 | 78 | **duplicate/pre-read** — hand-rolled optimistic concurrency by a history join back to the opening db, inside a transaction the serial writer already orders (AGENTS §1.2, §"no seam may act on a pre-read its authority will re-decide") |
| `relation-assertions`, `declaration-projection`, `row-tx` | 1203-1368 | 166 | mostly **duplicate** — it ends at `program/exact-replacement-tx` (`turn.clj:1366`), which is the declared owner |
| `system-turn` + `declared-sources`, `latest-evaluations`, `read-only-evaluation?`, `system-plan`, `generated-read-fault` | 1896-2285 | 390 | **essential** (PRD §14 since-diff) but should be one `db/since` over read evidence, not five helpers |
| `compact-call`, `compact!`, `virtual-turn!` | 2286-2341 | 56 | essential (PRD §16 controls) |
| routed-problem settlement `unbound-value?`…`plan-settlement` | 2397-2603 | 207 | **derivable** — issue/plan settlement belongs to the task family, not the turn loop |
| turn bound `outside-wake-t`, `episode-runs`, `turns-left`, `deferred-triggers` | 2614-2837 | 224 | **essential** — derived from `:t`, zero stored counters |
| `next-agent-work`, `more-agent-work?`, `latest-answering-turn-t`, `unanswered-wakes/triggers` | 2838-3079 | 242 | **essential** (PRD §14 wake answering) |
| reply delimiter repair `repairable-delimiter-error?`, `repaired-span`, `repair-source(s)`, `planned-sources` | 3084-3174 | 91 | **deletable** — repairing a model's unbalanced parens by span surgery; `seon.sci.reader` already returns a typed parse refusal the agent can see (§2.4: errors are values) |
| `committed-attributes`, `disposition`, `gate-function-install` | 3179-3329 | 151 | essential |
| proc plumbing `submit-evaluation!!`, `error-tx`, `delivery-rows`, `phase`, `settle-batch!`, `refusal-terminal-data`, `settle-batch-refusal!`, `settle!` | 3336-3808 | 473 | essential, minus `submit-evaluation!!` (→ `flow/futurize`) |
| attempts `attempt-*`, `provider-targets`, `record-attempt!` | 3809-4035 | 227 | essential (provider evidence) |
| `fold-*`, `evaluation-request`, `open-turn`, `call-turn` | 4036-4489 | 454 | essential but `call-turn` at 315 lines is one function doing open+prompt+attempt+evaluate+settle |
| `evaluate-sources`, `preview-sources` | 4519-4675 | 157 | essential; `preview-sources` correctly reuses `evaluate-sources` |
| `resume-turn`, `close-turn`, `generate-turn`, `turn` | 4676-5010 | 335 | **three siblings of one shape** (fork → evaluate → settle) that differ in where the source comes from; collapse to one |
| completion backstop | 5011-5262 | 252 | **duplicate of flow** (row above) |
| `step` | 5263-5417 | 155 | essential proc |

### 2c. `plan.clj` vs `issue.clj` vs the task family

The vocabulary table rules ONE family: *"`seon.task` — The one family for work an
agent does … legacy: issue (as the family name), `seon.issue`, `my.task`."*
Today there are two complete lifecycles.

| src span | lines | duplicates / seam | recommendation | what preserves the function | risk |
|---|---|---|---|---|---|
| `plan.clj:615-1197` `add-step-call`, `settle-call`, `complete-step-call`, `start-step-call`, `update-step-call` + `add!/complete!/start!/update!` | 583 | `issue.clj:1093-1470` `create-tx`, `start-tx`, `exhaust-tx`, `add-tx`, `guard-call`, `tests-tx`, `start!`, `add!`, `tests!` — the same create/start/complete/link lifecycle on a second entity family | collapse to one family's writers | one set of writers over `:seon.task` | high — schema cut |
| `plan.clj:787-900` `issue-done-query`, `stale-issue-tests`, `run-issue-tests!`, `done-query-result`, `query-satisfied?` | 114 | `issue.clj:1028-1072` `tests-done-query`, `done?`, `done-query` decide the same thing; `seon.test/stale` and `seon.test/verified?` already answer staleness (`plan.clj:811`, `issue.clj:1039`) | delete the plan-side copy | `seon.test/select`'s reuse evidence is the authority | med |
| `plan.clj:1198-1614` whole-tree reconciliation `input-entries`, `refuse-duplicate-*`, `refuse-dependency-cycle!`, `compile-tree`, `plan!` | 417 | A second reconciler beside `seon.reconcile` (450 lines) and the writer's own final-report validation | delete `compile-tree`/`plan!`; refuse cycles at the writer | `seon.db`'s validator refuses the bad final database | med |
| `issue.clj:35-340` `words`, `parse-note`, `citation-*`, `file-citations`, `replacement-tx`, `index-tx`, `citation-pattern` | 306 | Markdown note parsing with regexes (`issue.clj:929 citation-pattern`) — a REGEX IN PRODUCTION CODE, and issue notes are files, not agent execution | move out of `src/` to a `bin/` indexer, or delete | `bin/issues-index` already exists as the owner's tool | low |
| `plan.clj:1615-1886` nine `format-*`/`render-*-ai|html` | 272 | Three AI/HTML pairs for one plan entity (item, ready-items, plan) where §13 rules one pair per entity schema | keep `render-plan-ai/html`; delete the other two pairs | the plan's render function chooses forms from current data (vocabulary row) | low |

---

## 3. Rendering

**Render paths counted: 11.** (1) `render.clj` contract-fitting selection +
invocation; (2) `render/value.clj` the value renderer; (3) `print.cljc` the
sink/emit grammar; (4) `repl.clj/text` the REPL grammar; (5) `render/walk.clj`
neighbourhood acquisition + AI assembly; (6) `render/block.clj` the HTML morph
target; (7) `render/ns.clj` the namespace page's own AI+HTML with its own
budget ladder; (8) `render/transcript.clj` entries/history; (9)
`render/transcript.clj` session/outline/ledger (three more hand-assembled
views); (10) `render/web.clj` debug page value + experiment; (11)
`cluster/prompt.clj` prompt selection.

**Clipping / elision sites counted: 6, where the law allows 2.**

| site | file:line | verdict |
|---|---|---|
| value renderer window/pager | `render/value.clj:269-549` | **legal** — the one spot |
| `print.cljc` elision values, `render-elision-ai`, `requery-form` | `print.cljc:444-490`, 103 matches | **legal** — the grammar the value renderer emits |
| prompt token-budget selection + `dropped-elision` | `cluster/prompt.clj:214-308` | borderline: it drops WHOLE units, never clips inside one, and names the elision — keep, but it is a second bound |
| namespace page budget ladder, AI **and HTML** | `render/ns.clj:416-833` (`token-budget`, `within-budget?`, `omission-value`, `full/compact/minimal-ai-text`, `budgeted-ai`, `full/compact/minimal-html-view`, `html-within-budget?`, `budgeted-html`) | **illegal** — "HTML never clips" (AGENTS §2.4 / §"One clipping spot"); three-tier degradation is a second elision authority |
| walk distance/connection elision | `render/walk.clj:211-254, 669-703` | **illegal** — "never the walk" |
| history child-count elision | `render/transcript.clj:907-952` | **illegal** — §13 "ALL turns are shown by default" |

| src span | lines | duplicates / seam | recommendation | what preserves the function | risk |
|---|---|---|---|---|---|
| `render.clj:692-889` the invocation cache — `program-evidence-current?`, `render-program-evidence`, `call-cache-evidence`, `retained-program-current?`, `invocation-cache-key`, `reusable-invocation`, `retain-invocation!`, `same-invocation-evidence?`, `same-call-cache-evidence?` (a dead alias, `:833`), `refresh-read-evidence`, `cost-shape-key`, `render-cost-fact` | 198 | A hand-rolled memoizer with hand-rolled invalidation over program rows, projection forms, private callables and read evidence, plus a call-edge closure walk (`:709-726`). Datahike's read evidence already answers "did the facts move" (`db/read-evidence-current?`) and the commit id answers "did the program move" (`same-committed-database?:786`). Its consumers add ~150 more lines in `web.clj:436-554, 1409-1576, 2083-2511`, `walk.clj:442-497`, `transcript.clj:2080-2090`, `turn.clj:1910-1918` | delete the cache; keep `same-committed-database?` and `refresh-read-evidence` | the debug page re-renders from the live object; §"SECONDS, NOT MINUTES": two commit ids compared | med |
| `render/web.clj:1849-1916` `join-package`, `package-bytes`, `frame-bytes`, `next-package`, `package-patches` + the delta/keyframe fields of `:seon.render.package/*` | 68 | Revisioned keyframe/delta packages with gap-snapping. `hyperlith` sends the WHOLE view each batch and lets Datastar's morph diff on the client, brotli-streaming it (`reference-code/hyperlith/src/hyperlith/impl/datastar.clj:119-180`); `datastar-clojure`'s `->patch-elements-seq` is already used at `web.clj:1872` | delete revisioning; send the current view | the client's idiomorph is the diff | med |
| `render/web.clj:2689-2891` `register-tab!`, `deregister-tab!`, `write-package!`, `await-feed-package!`, `feed` | 203 | Per-tab registration counts, an http-kit write-state drain await and a package registry. Hyperlith's equivalent is a `(dropping-buffer 1)` tap on one refresh mult plus `hk/as-channel` on-open/on-close (`impl/datastar.clj:139-180`) | delete registration + drain await; keep the per-tab tap and the typed close | backpressure becomes the dropping buffer, which is the ruled loss semantics | med |
| `render/transcript.clj:31-675` `message-selector`, `receipt-selector`, `recent-*-rows`, `pinned-receipt-ids`, `candidate-entity-ids`, `message-entry`, `receipt-entry`, `entry-order`, `entry-root`, `history`, `floor-text`, `message-text`, `entry-handle`, `emission`, `evaluation-text`, `entry-name`, `projected-entry`, `html-entries`, `candidate-history`, `projection`, `render-ai` | 645 | PRD §15, verbatim: *"The transcript namespace's hand-assembled entries, entry kinds, and any history-specific formatting are deleted; nothing assembles the history but the walk."* Not done | delete | `render/walk.clj:979 history` + the evaluation schema's declared pair (`seon.eval.edn:8-9` → `seon.repl/render-ai|render-html`) | med |
| `render/transcript.clj:1502-2442` `outline-*`, `render-outline`, `ledger-*`, `render-ledger*`, `finding`, `*-problem`, `session-budget`, `session-problems`, `problems-html`, `ledger-strip`, `turn-story` | 941 | Three more hand-assembled views of the SAME evaluations (`render-session`, `render-outline`, `render-ledger`), each called directly from `web.clj`, none declared on a schema. §13: ONE pair per entity schema | collapse to the declared evaluation pair plus one HTML block | the block renderer (`render/block.clj:61`) is the morph target | med |
| `render/ns.clj:412-833` the AI and HTML budget ladders | 422 | Six renderings of one namespace (full/compact/minimal × AI/HTML) selected by a token budget; the HTML half violates "HTML never clips" | delete the HTML ladder outright; keep one AI text that emits an elision value if it must | `print/elision` names what was left out | low |
| `render/web.clj:558-1408` the per-agent debug value — `generic-entity`, `declared-entity-units`, `graph-model`, `debug-graph-html`, `debug-applicable-candidates`, `experiment-preview-html`, `debug-selected-renderer-details`, `applicable-renderers-html`, `debug-found-value(s)` | 851 | PRD §16 requires a debug page, but `debug-applicable-candidates` / `applicable-renderers-html` re-derive what `render/selection-inspection` (`render.clj:616`) already returns, and `generic-entity`/`declared-entity-units` re-derive what `walk/root-selector` and `walk/neighborhood` return | keep the four §16 sections; delete the parallel candidate and entity derivations (~500) | `selection-inspection` and the walk | med |
| `render.clj:278-311` `namespace-candidates` + `render.clj:342-587` the five selection stages | ~280 | Selection scans every public function in a namespace and asks Malli whether its contract fits, per render call — while `:seon.render/ai`/`/html` are declared as schema properties (e.g. `resources/seon/schemas/seon.eval.edn:8-9`) and `render.clj:342 schema-producers` already reads them. §2.2 bans the convoluted reconstruction when the fact exists | delete `namespace-candidates` and the namespace stage; keep the declared and explicit stages | the declared property datom | med |
| `render.clj:1719-1752` `*walk-context*` dynamic var, `call-with-walk-context`, `ambient-database-value`, `custody-cluster-name`, `repl-state` | 34 | §2.1, verbatim: never from a dynamic var | delete; the walk request carries its custody | §2.1 "values carry their world" | low |
| `render/lint.clj:101` `memoize` | 1 | a process-global cache keyed by nothing the value carries | delete | the projection carries it | low |
| `print.cljc` sinks/emit/table/elision | 1,377 | not duplicated by `clojure.pprint` — elision values, requery forms and the Hiccup tee have no equivalent | **keep** | — | — |

---

## 4. §2.1 fetch-at-call-time and duplicate caches in this area

| site | file:line | what it should be |
|---|---|---|
| `defonce`+`delay`+`requiring-resolve` load-cycle breakers ×20 | `turn.clj:51-62` (6), `render.clj:45-50` (3), `render/value.clj:22-27` (3), `issue.clj:22-33` (6), `repl.clj:25-28` (2), `web.clj:98`, `plan.clj:32`, `flow.clj:30`, `admit.clj:61` | each is evidence of a cycle: `turn ↔ cluster.agent ↔ cluster.prompt ↔ context ↔ problems`, `render ↔ render.web ↔ turn ↔ render.walk`, `issue ↔ turn ↔ cluster.agent`. Deleting the mechanisms above deletes most cycles; the rest is a namespace cut |
| `::kernel/program-snapshot`, `::kernel/installed-functions` | `eval.clj:273-274` and 4 re-copies | the ctx's database value |
| `::base-bindings` | `eval.clj:2148, 2197`, reset at `:1167` | `:sci/generation` |
| `::print-session` atom | `eval.clj:2204, 2878` | the render profile |
| render invocation cache atoms `::calls`, `::invocations`, `render/cache` | `render.clj:1639`, `web.clj:441-442, 1777, 2083, 2500` | read evidence + commit id |
| `*walk-context*` dynamic var | `render.clj:1719` | the request map |
| `render/lint.clj:101` `memoize` | — | the projection |
| `default-agent-profile` delay | `render.clj:88` | legitimate: `config/defaults` is the ruled program constant |
| `generator-server`, `generator-mult` defonce delays | `web.clj:110-132` | generator-only; harmless but process-global |
| `render/ns.clj:405` atom over schema rows | — | the projection already indexes by `:seon.schema/key` |
| 179 repeated `(when (and (map? x) (:seon.error/at x) (:seon.error/layer x) (:seon.error/operation x)) (throw …))` guards, marked `;; debt:` | `plan.clj` 38, `transcript.clj` 35, `my/program.clj` 27, `web.clj` 20, `eval.clj` 13, `call_preparation.clj` 12, `issue/detect.clj` 7, `render.clj` 6, `walk.clj` 5, `render/data.clj` 5 | ~700 lines total (~350 in this area). One `seon.error/value?` predicate and a `db/q` that declares its error union dissolves every one |

---

## 5. Tests

Area totals: `test/seon/cluster` 17,110 · `test/seon/render` 9,453 ·
`test/seon/sci` 4,605 · `test/my` 2,079.

**Six of the mechanisms recommended for deletion have NO test at all.**
`same-invocation-evidence?`, `call-cache-evidence`, `retained-program-current?`,
`RefusingBuffer`, `next-package` and `package-patches` appear nowhere under
`test/`. AGENTS: "Every proof must be claimed by a recurring surface." An
unclaimed mechanism is the cheapest thing in this audit to delete.

| test span | lines | verdict |
|---|---|---|
| `test/seon/cluster/turn_test.clj` 3,729 (59 deftests) + `turn_test.clj` 2,284 (34) + `turn_loop_test.clj` 1,684 (26) + `turn_work_test.clj` 803 + `turn_work_cost_test` + `turn_error_test` + `turn_continue_test` | ~9,000 | **seven namespaces for one loop.** Collapse to one per class: transitions, wake answering, continuation |
| invocation-cache tests — `render/call_test.clj:7` (whole 31-line file), `render/retained_test.clj:37,121`, `render_simplification_test.clj:323,349,699,792,858`, `render/web_context_test.clj:121,181` | ~400 | delete with `render.clj:692-889` |
| package keyframe/delta — only `render_simplification_test.clj:769 settled-package-is-reused-by-every-join` and one ref in `render/web_test.clj` | ~40 | delete with `web.clj:1849-1916` |
| work-launcher — `flow_test.clj:268,335,386,435,457,523,593,670` (8 of 21 deftests) | ~500 | delete with `flow.clj:241-944`; keep `:1427` |
| `program-snapshot`/`installed-functions` — `sci/eval_test.clj:515`, `sci/lazy_acquisition_test.clj` (whole 44-line file), `render/retained_test.clj`, `render/web_context_test.clj`, `sci/kernel_arm_carriage_test.clj` (233) | ~350 | delete with the atoms |
| `base-bindings`/`regenerate-agent-context!` — one site: `sci/eval_test.clj:159` (`:208-215`) | ~60 | rewrite against `:sci/generation`, not delete |
| `render/transcript_test.clj` 1,431 (18 deftests) + `history_test.clj` 307 + `transcript_run_test.clj` 100 + `episode_test.clj` 50 | ~1,900 | the entries/history half goes with `transcript.clj:31-675`; keep `:1172 one-reply-reads-identically-on-the-page-in-history-and-in-the-prompt` as the surviving class regression |
| `render/ns_test.clj` 546 (9 deftests) | — | the tier names (`budgeted-ai`, `compact`, `minimal`) appear NOWHERE in it: the three-tier ladder is unclaimed |
| `cluster/prompt_test.clj` 518 (14 deftests) | — | **keep** — it is the honest proof of the one legitimate second bound |
| `CountedSlidingBuffer` — one test site, `oversight_test.clj` | — | delete with the buffer |
| `my/*_test.clj` mirrors of `seon/*_test.clj` (agent_test, turn_test, message_test, plan_test, program_test, web_test, test_test, edit/fs/background) | 2,079 | expected thin-protocol mirrors; keep |
| `declaration_population_test.clj` in THREE homes — `test/seon/db/`, `test/seon/schema/`, `test/seon/sci/admit/` | — | one class, three copies; collapse |
| `test/seon/eval_test.clj` vs `test/seon/sci/eval_test.clj` | — | old owner vs current owner; delete the old |

Every `:seon.test/long` in this area:

| test | declared | classification |
|---|---|---|
| `print_test.clj:240` `p-total-generated-grammar-emits-and-readable-faces-round-trip` | "94.303 s pool: 200 generated grammar validation, text/Hiccup emission, and EDN read-back trials" | **reducible** — the class is proved by ~20 trials; 200 buys no class coverage |
| `print_test.clj:264` `p-tee-generated-grammar-cannot-disagree` | "93.195 s pool: 200 generated text/Hiccup lexical-equivalence trials" | **reducible**, same |
| `render/transcript_test.clj:968` `every-generated-history-is-ordered-and-total` | "66.666 s pool: 40 fresh-branch generated histories" | **algorithm defect** — 1.67 s per trial because `support/with-database` installs the complete canonical population per trial (`:977`); AGENTS: "A fork is a branch pointer: milliseconds." Also tests `transcript/render-ai|render-html`, which PRD §15 deletes |
| `sci/lazy_acquisition_test.clj:9` `cluster-program-is-acquired-on-first-use-and-reused-for-that-database` | 30,000 ms, "Two complete SCI acquisitions from the canonical published program" | **algorithm defect** — the O(program) `acquire-program!` walk is the subject; this test is that seam's honest bill |
| `sci/eval_instrumentation_test.clj:1` (ns-level) | "Published-root start, whole-image instrumentation, and one attempt-ready prompt" | **algorithm defect** — whole-image instrumentation is proportional to the program, not the change |
| `turn_test.clj:1322` `settlement-keeps-unresolved-call-and-require-names-as-values` | 60,000 ms, "…the two-analysis publication regression measured 20.54 s…" | **algorithm defect** — the declared reason names an O(program) analysis |
| `error_result_test.clj:99` `agent-contract-refusal-retains-its-live-offending-result` | 60,000 ms, no reason | **algorithm defect** — a contract refusal holding one result should not need a minute; no reason is declared, which the bound's own rule requires |
| `error_write_timing_test.clj:80` `one-error-write-has-a-measured-preparation-validation-and-commit` | 60,000 ms, "Acquire the complete canonical SCI program once, then profile one armed error write" | **algorithm defect** — the acquisition, not the write, is the cost |
| `ai_stream_fold_test.clj:373` `settled-reasoning-reuses-the-eval-result-inline-blob-split` | 90,000 ms, isolated file-backed store | **genuinely long** — real store acquisition |
| `cluster/lazy_agents_test.clj:10` | 10,000 ms, "…measured 5.208 s in isolation" | **algorithm defect** — canonical fixture acquisition dominating four small transactions |
| `my/test_test.clj:13` `an-agents-own-test-reaches-its-cluster-through-the-elided-arity` | 300,000 ms, "Acquire the agent's canonical SCI program before two owned requests" | **algorithm defect**, same acquisition |
| `flow_test.clj:1427` `forced-child-jvm-death-preserves-committed-facts` | "Forcibly terminates a child JVM" | **genuinely long** — a real process kill; keep |
| `oversight_test.clj:113` `a-booted-cluster-tells-its-live-fleet-story` | "Boots a real cluster and fetches its root page" | **genuinely long** — cold JVM boot is an authorized exception; keep |
| `config_application_test.clj:147` `applied-values-shape-the-running-system` | "Starts a real cluster" | **genuinely long**; keep |
| `cluster/armed_test.clj:1` (ns) | "48.642 s slowest pool member: every test boots a real armed cluster" | **genuinely long** per test, but the ns-level lift means one slow member taxes all; split |
| `cluster/program_restart_test.clj:1` (ns) | "67.758 s pool: real cluster stop/restart" | **genuinely long**; keep |
| `concurrency_independence_test.clj:1` (ns) | "136.721 s pool: one real cluster folds 5- and 10-agent plans concurrently and verifies every receipt" | **algorithm defect** — verifying every receipt of 15 agents' plans is proportional to the plan, and it renders through the machinery §5 deletes |

**Tooling defect found while inventorying:** the namespaced-map metadata form
`^#:seon.test{:long "…" :long-ms 10000}` (`test/seon/cluster/source_lineage_test.clj:20,149`)
is invisible to a `grep ':seon.test/long'`. Any selector matching that literal
silently misses those tests — the project's named failure class (a check that
reads absence of signal as health). File it.

## 6. What namespace agents genuinely need, and how each survives

| need | survives as |
|---|---|
| an agent has a SCI fork of the cluster's program | `sci/fork` (`core.cljc:345`) plus copy-on-write Vars (`72150fd4`); `fork-for-turn` shrinks to that call — `base-bindings`, the two kernel atoms and `installation-covers-program-change?` all go |
| its private layer survives across turns in memory | `:sci/generation` on the Var's meta (`sci/impl/utils.cljc:356-380`) is the discriminator; `regenerate-agent-context!` keeps only its generation-equality branch |
| it evaluates forms under a bound | `seon.sci.kernel`'s guard + `:interrupt-fn` + `sci.interrupt/interrupt!` — unchanged, and the ONE bound; `flow/futurize` with `:compute-timeout-ms` replaces the submission and turn backstops |
| its evaluations are stored facts with shown text | the `:seon.cluster.eval` entity and `:seon.eval/shown` (`resources/seon/schemas/seon.eval.edn`) — unchanged; the turn transitions (`turn.clj:305-495, 824-1000, 1369-1703`) are essential and stay |
| wakes derive from listened datoms and answer by `:t` | `cluster/wake.clj:92-320` and `turn.clj:2614-3079` — essential; only the relaying mailbox proc goes |
| its context renders from linked facts through schema pairs | the declared `:seon.render/ai`/`/html` properties plus `render/walk.clj neighborhood` and `render/block.clj` — which is exactly what deleting the transcript's hand assembly and the namespace-candidate scan leaves standing |
| a user reads it on a page | `render/block.clj` blocks over `datastar-clojure`'s patch-elements, delivered hyperlith-style: whole current view per batch, client-side morph |
| it works on its own Datahike branch and merges | already a branch pointer; nothing in this area blocks it once the ctx stops carrying program mirrors that a branch switch would invalidate |

## Fork changes needed

1. **`reference-code/sci`** — make the call-preparation/instrument arming stamp
   armed wrappers so `:sci/generation` alone answers "is this Var private",
   and expose a `fork` that does not require the caller to snapshot
   `namespace-state`. `fcbd8862` ("fact-safe SCI Var root installation") and
   `sci/bind-root!` (`core.cljc:276`) are the seams to extend.
2. **`reference-code/core.async`** — none required; `:compute-exec` and
   `:compute-timeout-ms` already exist (`flow.clj:264-266`,
   `flow/impl.clj:245-260`). If we want the compute deadline reported as a
   value rather than only on `::flow/error`, that is a small fork change.
3. **`reference-code/datastar-clojure`** — none required. The brotli output
   stream + SSE newline filter we would want for whole-view delivery is
   hyperlith's, not datastar-clojure's; vendor those ~60 lines rather than
   keep 270 lines of package revisioning.

## Not verified

- Whether `render.clj:293 public-functions-in` has any consumer that the
  declared schema properties cannot serve: read statically only.
- Whether `acquire-program!`'s `:core` branch can be replaced by
  `sci/copy-ns`-style binding without losing contract arming — needs a live
  probe, which this audit did not run.
- Any timing claim. Every duration quoted here is a string read out of a
  `:seon.test/long` declaration, not a measurement taken in this session.
