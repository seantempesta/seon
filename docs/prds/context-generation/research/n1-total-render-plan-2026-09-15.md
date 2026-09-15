---
type: research
status: draft
tags: [research, class/n1, render]
---

# N1 total render: bounded research result — 2026-09-15

**Confirmed: MCP can throw before entering the value renderer. Class closure is not established.** Stopped under the assignment's three-disagreeing-probes rule: three shape probes omitted the required root identity and returned refusals. No source/test edits, test JVMs, SCI evaluations, restarts, transactions, or JFR recordings were performed. HEAD inspected: `51e1998843d8063654f82e9aaf381674bdb9a4d4`; live default PID 69622, MCP health observed, source commit observation `6aa9d0b3-5a57-5862-af74-5f8717513752`. Loaded-source equality was not established; live results are not an isolated HEAD proof.

## Measured breakdown: hypothesis → probe → number → verdict

| Hypothesis / outward shape | Read-only JVM probe | Time / allocation | Actual output and verdict |
|---|---|---|---|
| MCP's arbitrary-map recognition still throws | `(let [connection (seon.operator/connection "default") database @connection] (sorted-map "a" 1 "b" 2))` | PREPL evaluation **5 ms**; projection time/allocation **unmeasured** | `ClassCastException`, `:phase :print-eval-result`, raw trace through `seon.cluster$mcp_project`, line 337. Confirmed. PREPL's 5 ms excludes output projection. |
| Qualified map face is readable | Render `{:seon.schedule.fire/id "example" :seon.schedule.fire/nominal-at #inst "2026-08-09"}` | **0.891375 ms / 513,648 B** | `:seon.render.value/missing-root-identity`; shape not verified. |
| Nested database becomes identity HTML | Render `{:n1/database database}` to HTML | **0.244792 ms / 676,824 B** | Same refusal; shape not verified. |
| A function row is replaced by a declared summary | Render `{:seon.fn/sym "seon.db/q" :seon.fn/private? false}` with live ctx | **1.217958 ms / 1,184,240 B** | Same refusal; shape not verified. This was a representative map, not a database pull. |

The last three measurements bracket each renderer call with `System/nanoTime` and `ThreadMXBean.getThreadAllocatedBytes` on the calling JVM thread. Their total is **2.354125 ms / 2,374,712 B**. This is measured failed-probe work, not page latency. Each supplied database, projection and profile, but omitted `:seon.render.value/root`; `src/seon/render/value.clj:81` requires that identity. Preparation traverses/emits before checking the resulting ID (`:411–459`), so these calls performed work whose result was discarded. Expected unnecessary work after validating the request first: **zero traversals/emissions**; wall-clock savings need a corrected probe. Every remaining millisecond—MCP serialization, transport, page rendering and individual rendering stages—is **unattributed**, not zero. No complete latency breakdown is claimed.

Probe construction: `connection` above; `database = (seon.db/db connection)`; `ctx = (:seon.sci.eval/ctx (#'seon.cluster/mcp-instance "default"))`; `projection = (:seon.schema/projection (seon.env/of ctx))`; `profile = (seon.render/agent-render-profile (#'seon.cluster/mcp-effective "default" {}))`. Call `seon.render.value/render-ai` or `render-html` with `:seon.render/value`, `:seon.db/db`, `:seon.schema/projection`, `:seon.render/profile`; only the third shape also received `:seon.sci.eval/ctx`. These exact missing-input results falsify the probes' premises; they do not establish renderer correctness. A separate custody query returned a loud missing-projection refusal for the agent census, so no agent population was inferred.

## Member disposition at the stop boundary

`bin/issues-index --class class/n1` failed on unrelated malformed/duplicate `contract-evidence-carries-the-offending-argument-twice.md`. Exact frontmatter tags derived **21 open member notes**, excluding the umbrella. Names below identify files under `docs/seon/issues/`. **U** means no valid live shape proof; it does not mean resolved. All member notes and the class note were read; the combined mining/roadmap read was truncated, so an end-to-end authority-read claim is not made. Mining's N1 row was observed: “One total rendered-value/omission/error construction; required producers, bounded terminal output, counted fallback.” Current AGENTS §2.4 supersedes its historical HTML clipping proposal.

| Member filename stem | HEAD/source finding; live verdict |
|---|---|
| mcp-projection-crashes-on-non-keyword-map-keys | **Confirmed**, above; unsafe lookup at `src/seon/cluster.clj:337`. |
| time-limit-face-exposes-interpreter-interrupt-marker | `src/seon/sci/kernel.clj:481` removes marker; deleting change `b5665971d`. U for actual timeout face. |
| instrumentation-headline-unbounded-when-caps-absent | Old whole-problem headline replaced by first-problem grammar at `src/seon/instrument.clj:279,373`; U, deleting commit not established. |
| expected-refusal-logs-raw-datom-error-twice | Dependency still has transaction `log/raise` sites; double logging unverified. No refused transaction induced. |
| boot-refusal-has-no-render-producer | Operator `fail!` still throws data (`script/seon/fresh_operator.clj:79`); final boot face U. |
| run-renderer-narrates-forms-and-receipts | Historical run owner removed/renamed in `78c8fc1d6`, `7296d173b`; current saved-value owner `src/seon/repl.clj:116`; U, renaming alone is not dissolution. |
| the-debug-ai-pane-never-wraps | CSS now contains `pre-wrap`/`anywhere` (`resources/public/css/input.css:1804`); actual matching element/layout U. |
| operator-status-dumps-every-absent-test-result | Observed status says `test evidence: UNKNOWN; not queried by descriptor-only status; use status --verbose`; flood absent on this invocation, exact absent population U. |
| database-values-render-as-opaque-host-objects-in-html | Identity schema still AI-only (`resources/seon/schemas/seon.db.edn:149`); failed probe above does not verify resulting HTML. |
| changed-test-report-is-one-enormous-line | Historical selector deletion appeared in log search; exact deleting change and replacement face U. No test run. |
| debug-left-pane-is-not-the-exact-prompt | Current debug response at `src/seon/render/web.clj:3050`; latest attempt/pane byte comparison U. |
| transcript-renderer-encodes-entries-as-comment-forms | Note records comment removal `c6a81988c`; current error grammar at `src/seon/repl.clj:122`; live failed evaluation display U. |
| effect-receipts-have-no-render-producers | Missing-pair mechanism removed by `d0c439e96`; pair at `resources/seon/schemas/seon.effect.edn:24`; actual walk U. |
| an-entity-pull-returns-a-sentence-instead-of-its-attributes | Declared-map selection remains `src/seon/render/value.clj:287`; failed representative probe above, historical substitution U. |
| agent-pages-overflow-a-phone-viewport | Viewport acceptance U; renderer totality cannot establish CSS geometry. |
| init-failure-dumps-entire-prepl-event-history | Note distinguishes fixed hook summary from outstanding direct operator face; U, publication not induced. |
| the-value-floors-map-face-is-not-readable-edn | Failed representative probe above; U. |
| my-background-poll-costs-290-tokens-per-polled-result | API still documents request/result payload return (`src/my/background.clj:48`); eight-ref payload cost U. |
| one-elision-has-two-representations-in-one-context | Separate prose tail remains at `src/seon/render/value.clj:462`; both limit paths U. |
| pre-rename-root-claims-are-unreadable-noise-on-every-status | Note's aggregation fix `a073f7b51`; observed status has no claim warnings. Reclamation/key diagnostics U. |
| debug-pages-receive-block-patches-for-elements-they-do-not-have | Current code distinguishes debug registration (`src/seon/render/web.clj:2004,2216`); actual SSE target correspondence U. |

## Simplest kill first; design, not implementation authorization

1. **Close the proven MCP bypass in its existing owner.** Arbitrary-map keyword recognition before projection (`src/seon/cluster.clj:337`, also `:281`) performs unnecessary shape guessing and produces stack serialization when a comparator rejects it. Pass arbitrary values directly to the existing total value preparation; carry evaluation recognition as explicit caller data rather than infer it from arbitrary result keys. Put the exception boundary around the whole outward preparation, including recognition/profile acquisition/emission, with a minimal semantic failure that cannot recursively invoke the failing producer. Expected: **0 escaped projection exceptions / 0 raw stack frames** for the sorted map; latency improvement not yet measured.
2. **Validate carried prerequisites before rendering.** `value/prepare:411` must decide its required root at entry, then carry it through the existing projection. Delete the late duplicate derivation, not add a cache. Expected invalid-root cost: **0 value-node visits / 0 emit calls**. Preserve total rendering for valid ordinary values; a malformed request is a distinct refusal.
3. **Class-wide candidate, pending decision:** existing `:seon.render.value/projection` is the value transported to text/HTML encoders; external text leaves consume its terminal text or HTML, never reclassify a raw value. Extend the existing `seon.fn/output-path-report` proof rather than build another registry. Count *failed declared rendering* in existing call evidence; an ordinary map's generic renderer is legitimate under current rules and must not fail merely for being generic. AI functions/value renderer own presentation bounds; HTML and saved shown text receive no new clipping. This cannot by itself fix duplicate dependency logs, obsolete claims, CSS overflow, or wrong SSE targets; those members need reclassification or separate owner work before closing N1.

**Owner decision for cross-owner scope (estimates, not measurements):** (A) **Recommended, 0.5–1 day:** close MCP bypass plus early validation; guarantees the confirmed shapes, gives up claiming whole-class closure. (B) **2–4 days:** require existing prepared projection at JVM text seams and prove positive sink coverage; gives up CLI/dependency-log and browser-layout closure. (C) **4–8 days plus dependency review:** include bootstrap/operator and dependency log encoding, with explicit pre-database rendering inputs; gives up a small change, still cannot guarantee viewport geometry. No option introduces another clipping location or a cache.

**One class regression:** extend `seon.mcp-test/outward-values-use-total-projection` (proposed name) on the canonical armed fixture and real MCP PREPL: assert a nonempty declared outward-sink population, no unresolved/bypass visible paths, and for `(sorted-map "a" 1 "b" 2)` exactly one successful terminal value equal to that map, with no `:print-eval-result` exception. Include invalid-root preparation asserting zero value traversal, and declared-producer failure asserting one counted semantic fallback. The sorted-map assertion fails on today's live observation; the complete proposed regression has not run. Ordinary missing producers remain valid structural rendering, not silent empty success. Future testing belongs to the orchestrator under the research-only rule.

**Ownership:** this lane owns only this note. Proposed first implementation owns `src/seon/cluster.clj`, `src/seon/render/value.clj`, the corresponding schema if required, and `test/seon/mcp_test.clj`/`test/seon/render/value_test.clj`. At final `git status`, protected modified paths were `src/seon/render.clj`, `src/seon/sci/eval.clj`, `src/seon/turn.clj`, `test/seon/schema/datahike_test.clj`, `test/seon/sci/eval_test.clj`; unrelated untracked `build/`, `workers/`, and `docs/seon/issues/incremental-publication-cannot-select-the-live-operator.md` were preserved. No scratch roots or processes were created. This note retains the failed-probe evidence and index failure; **do not close the umbrella or its unverified members from this partial audit**.
