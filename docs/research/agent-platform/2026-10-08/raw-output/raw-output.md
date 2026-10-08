---
type: research
status: measured; design proposed
date: 2026-10-08
lane: raw-output
---

# Raw output: producers, measurement and full-value access

Seon already retains a form's actual result in its agent's SCI context, selects a render, and prints through the value printer. Trim unnecessary data at its producer; add bounded output observations to the existing armed profile cell. Do not build another printer, result store, or clipping layer.

Owner, 2026-10-08: “None of our core functions should ever be returning that kind of data even without render functions.” The full version must contain useful data, and remain available independently of its summary.

## Findings and limits

1. **Digging re-renders.** Both default and the disposable HEAD root retain all 90 integers in the live result, but `(:nested result/e…)` replaces their map's shown value with its inline summary. No payload loss was observed; plain inspection is unreliable. The exact reproduction is below.
2. **The largest ordinary diagnostic read sampled was 123,216 tokens / 394,294 bytes**, `my.program/breaks` for `seon.db/pull`. Repeated caller identities, spans and target arities are a concrete producer defect. No measured raw return approached 100 MB. That is a result for this corpus, **not a proof for every possible core function/input**.
3. `dir seon.render` returns 11,347 raw tokens. Its schema appendix alone is about 5,536 tokens; neither directory face reads it. Remove it at `directory-value`, not in another renderer.
4. `seon.health/report` grew from 73,133 to 80,774 raw tokens during the audit. It returns complete owner units and embeds matching rows again in findings. The health renderer needs clues, not a second whole match.
5. **Full-size printing is too expensive for profiling.** Measuring the 67,022-token caller report took 637 ms, while producing it took 348 ms. The measurement itself caused the slow findings on the 1,105 ms health and 1,040 ms breakage probes. Those are not timings of their producers alone.
6. Default was already `:faulted`: six open faults, 36 errored receipts, and six over-bound warnings at entry. Its loaded source was `9c88ae5b8152e951d67606824c3ac315fa340d89`, PID 72173, start 02:27:20.846 UTC. Default was not restarted or redefined.
7. The installed profile counts calls and time, **not output size**. This lane sampled 24 read shapes, a real test check, one printer projection and the suite result; it did not intercept every return during tests. No Var was replaced to manufacture such instrumentation. Size × call-frequency products below are explicitly exposure estimates, not accumulated measured bytes.

Read authorities: CLAUDE Presentation and “Every stored fact names its consumer”; [render audit](../printer-rules/render-audit.md); [message census](../agent-messages/agent-message-audit-messages.md); [Opus message audit](../agent-messages/messages-opus-messages.md). Applied skills: REPL, data-oriented Clojure, testing, Datahike and data modeling. This document is evidence and a proposed design, not an installed API.

## Reproduction and measurement

The committed [probe](probe.clj) uses PREPL only as transport to the installed `seon.mcp/call`, explicitly selecting SCI, cluster default and agent `raw-output`. The suite operation calls the canonical `seon.test/run` with the disposable execution/recording connection; the benchmark is a local `letfn`, never an installed Var.

Commands from the checkout:

```sh
mkdir -p tmp/raw-output/root
bin/seon --root tmp/raw-output/root start
bb --classpath script:src:resources docs/research/agent-platform/2026-10-08/raw-output/probe.clj tmp/raw-output/root suites
bb --classpath script:src:resources docs/research/agent-platform/2026-10-08/raw-output/probe.clj tmp/raw-output/root sizes
bb --classpath script:src:resources docs/research/agent-platform/2026-10-08/raw-output/probe.clj tmp/raw-output/root full
bb --classpath script:src:resources docs/research/agent-platform/2026-10-08/raw-output/probe.clj tmp/raw-output/root bench
bb --classpath script:src:resources docs/research/agent-platform/2026-10-08/raw-output/probe.clj . sizes
bin/seon --root tmp/raw-output/root down
```

The disposable JVM ran committed `e9e3b3253e0e52df122263a321dd1478f88e93b5`, PID 92430, start 02:56:54.553 UTC. Its web port was 63484. Boot readiness was 64,346 ms; heap used/live/max at the first status was 2,997,848,424 / 724,535,656 / 10,997,465,088 bytes. This is process memory, not result size. The explicitly requested throwaway boot is not an ordinary sub-second operation; its large cost remains a finding under the existing operator rehearsal-cost tasks.

Each size is the UTF-8 byte length and `seon.ai.tokens/estimate` of **the existing value printer's complete structural text**: `prepare` with `:seon.render.value/structural? true`, a supplied block id, and `seon.print/full`. Every tabulated size probe reported zero elisions. The estimate is floor(UTF-16 length / 3.2); it is not a model tokenizer. Whitespace is included. It measures printable data, **not retained heap, transitive object graphs, database contents behind a handle, or wire JSON**. Opaque objects remain opaque.

Default calls used the lane's fork of default's facts; health explicitly observed the running default instance. There were no default program writes; task recording and automatic evaluation bookkeeping were on the lane branch. Empty task/open and proposal responses describe that snapshot, not the absence of all stored tasks. `my.test/run-owned` used an empty namespace on default; the real test check ran only in the disposable root.

All current sizes below are measured, not desired budgets. The final default scan is the stable comparison row; changing health/cache/profile values are observations at that time.

## Top 15 raw outputs

Rank is by raw size in a corpus containing one invocation of each displayed shape (frequency 1, so size × frequency = tokens). D = default; T = disposable test root. Further frequency evidence follows.

| Rank | Output / input | Tokens | UTF-8 bytes | Garbage or duplication; proposed lean shape |
|---|---|---:|---:|---|
| 1 | D `my.program/breaks`, subject `seon.db/pull` | 123,216 | 394,294 | Target declared arities and caller spans repeated per observed arity; callers repeated as a set and rows. One target descriptor + one row per caller, with arity set; preserve gating/references. |
| 2 | D `seon.health/report`, default | 80,774 | 258,479 | Whole matching rows repeated under findings beside the owner units. Keep each unit once; findings carry subject, schema, score, tasks and selected clues, with an address into the unit where needed. |
| 3 | D `my.program/callers`, `seon.db/pull` | 67,022 | 214,473 | Same sites duplication as #1. Target arities once; caller symbol/span once; observed arities a set. |
| 4 | D `seon.db/q`, all function symbols (5,633 rows) | 63,828 | 204,252 | **No proven garbage**: deliberately broad query requested these symbols. Keep exact query semantics; ordinary callers should query the needed namespace/subject. Candidate intentional-bulk declaration. |
| 5 | T `seon.test/run`, 83-member suite response | 19,955 | 63,857 | Per-member proof bookkeeping in the public answer; useful to evidence reuse, not ordinary test interaction. Public result: run id, counts, failures; complete evidence remains the canonical run/member read. |
| 6 | D `seon.profile/top`, limit 15 | 13,401 | 42,886 | Same observation maps under by-total, by-max and over-second. One observation table + rank vectors of identities. |
| 7 | D `dir seon.render` | 11,347 | 36,317 | Unused `:schemas` appendix; input/output contracts unused by both directory faces. Keep sym, arglists, doc summary; details through doc/schema read. |
| 8 | D `my.program/source 'seon.test/run` | 8,890 | 28,448 | Large requested function body is useful. `:doc` repeats source docstring; `:contract` is used by tests/agents and is not proven garbage. Keep source/location/contract, consider removing redundant doc with caller migration. |
| 9 | D `my.program/source 'seon.sci.eval/evaluate` | 7,149 | 22,903 | Same distinction as #8; do not truncate requested source. Long-function decomposition is separate work. |
| 10 | T `seon.render.value/prepare`, vector 0–89 | 2,832 | 9,068 | Tree, text and HTML coexist. They have real consumers; unnecessary materialization occurs when the caller needs only one face. One selected tree, emit the requested sink; keep the same printer. |
| 11 | D `seon.cache/status` | 2,430 | 7,776 | Per-kind counters have actual operator/health consumers; no unused key proven. Summary caller requests aggregate; explicit full status remains complete. |
| 12 | D `seon.db/pull '[*]`, namespace seon.render | 1,618 | 5,178 | Source, imports, aliases, digest and doc were explicitly requested. No DB-owner trimming. A caller wanting identity uses an explicit selector. |
| 13 | D `my.agent/branches` | 647 | 2,073 | Complete roster is this API's purpose; no unused key proven. One own-branch plus roster, unchanged. |
| 14 | T `my.test/check`, one contracts test | 511 | 1,638 | Three program/run identity/basis fields and timings accompany one result. Preserve them in evidence; public answer can be run id, pass/counts/failures. No actual failing-row duplication measured in this green check. |
| 15 | D caught contract refusal, `render-ai-data nil` | 467 | 1,497 | Wrapper bookkeeping and repeated expectation/arity metadata. Type, operation, one message, native problems, producer identity needed for recording; drop only after its consumers move. |

Other measurements: source of render-form 426 tokens / 1,365 bytes; program/search for :seon.error/message 397 / 1,272; program/sizes seon.db 230 / 738; doc render-form 184 / 589; unassigned task/steps 183 / 587; tests-reaching render-form 172 / 551; agent/identity 21 / 68; agent/branch 16 / 54. Empty task/open, settings, proposals and empty test run were 0 estimated tokens / 2 bytes each.

`runtime_status` measured **878 tokens / 2,812 UTF-8 bytes** at the tool text boundary. It deliberately renders `health/report` before returning text (`src/seon/mcp.clj:337`); calling that number a raw health value would be wrong. Unqualified `(source seon.render/render-form)` failed with “Unable to resolve symbol: source”; existing task `seon.sci.eval/an-agent-cannot-read-the-private-code-it-must-change` is the same discovery family. `my.program/source` was measured successfully.

Disposable comparisons: breaks 123,532 / 395,303; callers 67,297 / 215,351; all-symbol query 63,875 / 204,402; health 19,958 / 63,866; profile/top 10,397 / 33,273; roster 50 / 163. Program/read shapes otherwise matched default. Health differences are population and profile differences, not byte-for-byte regressions.

### Frequency and ranking without inventing traffic

The final default profile snapshot summed all live cells by symbol: db/pull 28,795; db/q 12,303; prepare 285; health/report 13; cache/status 15; directory-value 7; documentation-value 8; my.program/source 6; callers 2; breaks 3; search 1; my.agent/branches 5; my.test/check 5; test/run 6; profile/top 3. These are process-lifetime calls, include probes, and are not a clean workload window.

Multiplying each representative size by those calls gives a **triage proxy**, not actual total output:

| Producer | Representative tokens × observed calls | Proxy token exposure |
|---|---:|---:|
| db/q | 63,828 × 12,303 | 785,275,884 |
| db/pull | 1,618 × 28,795 | 46,590,310 |
| health/report | 80,774 × 13 | 1,050,062 |
| prepare | 2,832 × 285 | 807,120 |
| breaks | 123,216 × 3 | 369,648 |
| callers | 67,022 × 2 | 134,044 |
| test/run, using 83-member response | 19,955 × 6 | 119,730 |
| directory-value | 11,347 × 7 | 79,429 |
| source, using test/run body | 8,890 × 6 | 53,340 |
| profile/top | 13,401 × 3 | 40,203 |
| cache/status | 2,430 × 15 | 36,450 |
| agent/branches | 647 × 5 | 3,235 |
| my.test/check | 511 × 5 | 2,555 |
| documentation-value | 184 × 8 | 1,472 |
| program/search | 397 × 1 | 397 |

The query rows demonstrate why **ranking by one big example times every call is misleading**: most reads are small. Do not claim hundreds of MB of actual output from this multiplication. Installed per-return samples are required for a defensible total. Among proven duplication, fix directory payload and caller rows first, then health/profile/test public reports.

Suite-window observations from the second run included q 109, pull 40, callers 1, breaks 2, tests-reaching 1, test/run 1. This was a reuse run, not a fresh all-body workload. It is not legitimate to multiply all q calls by the all-symbol query's size. The script's legacy frequency row is a per-symbol delta and may undercount multiple cells for a symbol; the separately summed default snapshot above avoids that ambiguity.

## Producer and consumer evidence

File lines below refer to the read source around the pinned audit archive; later working-tree changes can shift them. Searches covered src and test, including quoted operations. “Unused” means no first-party data consumer found, not proof that an external agent has never inspected a key.

| Producer, file:line | Existing consumers of the relevant keys | Consequence |
|---|---|---|
| directory-value, `src/seon/sci/eval.clj:2003` | `src/seon/repl.clj:37–56`: AI reads functions/sym,arglists,doc; HTML calls AI. Contracts and :schemas are not read by either. | Drop the appendix and per-row contracts at the producer. Keep schema/doc discovery separate; no stored schema deletion. |
| caller-data, `src/my/program.clj:106–155` | breaks :308 counts missing call-arity; deletion-report :654–666 consumes callers and call-sites; `test/my/program_test.clj:30–35` checks caller set, row symbols and spans. | Do not delete callers blindly. Group sites, carry declared target arities once, migrate deletion and arity consumers together. |
| health/findings, `src/seon/health.clj:291–321`; report :552–607 | render-ai :661 and finding-item :682 read the embedded :value only to derive declared clues; HTML unit sections also show owner units. | Derive clues once; a report-local path can preserve access to the unique owner row. Requery for stored matches that are not already in a unit. |
| profile/top, `src/seon/profile.clj:419–437` | summary :623 reads all three ranks; tests :159ff check ranks/values. | One observation map keyed by existing callable identity, rank vectors referring to it. Never omit definition identity needed to distinguish versions. |
| my.test/check, `src/my/test.clj:6–29`; run-owned :31–55 | test/run and runner own execution/reuse; `src/seon/test/runner.clj:1706–1896` uses reach/program digests; `src/seon/render/test.clj:50,97,142` consumes counts, failures, identity. | Digests are **not globally garbage**. Separate the small public answer from already stored proof evidence; do not delete the evidence or add a second result store. |
| source, `src/my/program.clj:830–847` | `test/my/program_read_test.clj:7–19` checks :kind/:file/:line/:contract/:source. Help offers source to agents. No first-party :doc consumer found for this answer. | Source is intentional detail. Removing :doc is a small behavior change, retaining :contract. |
| prepare/project, `src/seon/render/value.clj:1568–1735` | shown-result selects text; HTML paths select html; printer tests inspect tree and diagnostics. | No blanket key deletion. Avoid eager construction of an unused sink within the same owner. |
| violation, `src/seon/instrument.clj:450–594`; problem-shown, `src/seon/error.clj:1889–1910` | Recorder/signature and error paths consume producer identity, digest, location; reply projection consumes problems. | Coordinate with errors cut. Generated examples and dereferenced schema expansions are created by **problem-shown**, after the raw refusal; they were not in the 467-token raw sample. |
| DB q/pull, `src/seon/db.clj:2450,2661` | Caller owns its query/selector; deletion, analysis and proof use full requested facts. | Narrow the caller, not the DB result. Database objects/whole transactions have essential internal consumers and must remain references at error/presentation boundaries. |

Measured key partitions, each independently printed inside a one-element vector (so widths do not sum exactly): dir :schemas 5,536 tokens, :functions 5,147; callers :callers 12,097, :call-sites 51,498. Health at the partition probe: :profile 28,152; :findings 19,390; :problems 18,964; :cache 2,515; :regressions 2,040; :status 554; :readiness 336. The partition probe cost 1,975 ms because it printed all those values.

`:seon.related/data` is appended by `render.value/declared` :1032 after render selection. It is a presentation-stage duplication, not present in the raw namespace pull above. Likewise frames/whole refused transactions in the message audit are existing error-evidence defects, not observed megabyte raw results in this sample.

## Enforcement design (TARGET)

Separate four concerns: raw returned value, bounded size observation, finding policy, and printer budget. The value always returns unchanged. The smallest alternative—counting only the final shown text—misses raw garbage hidden by renders. Full structural printing per return is rejected by the measurements above.

### Location and exact proposed keys

All new keys below were absent from the searched schema population. They are proposals.

| Owner | Proposed members / declaration | Consumer and lifetime |
|---|---|---|
| `resources/seon/schemas/seon.config.eval.edn`, applied cluster config | `:seon.config.eval/output-sample-every` positive int, default 64; `:seon.config.eval/output-sample-nodes` positive int, default 128; `:seon.config.eval/output-token-bound` positive int, default 4096 | Arm-time profile plan reads once from the evaluation's world. Config reapplication/rearming changes the plan. No per-call DB lookup. |
| Function's existing Malli contract property map | `:seon.profile/output-bulk` map with `:seon.profile/reason` nonempty string and `:seon.profile/output-token-bound` positive int | Intentional bulk declaration belongs to the declaration/digest, e.g. explicit export/query. No namespace exemptions. Named schema still describes returned data. |
| Existing `seon.profile/cell` and its schema | `:seon.profile/output-samples`, `:seon.profile/output-token-sum`, `:seon.profile/output-token-max`, `:seon.profile/output-byte-sum`, `:seon.profile/output-byte-max`, `:seon.profile/output-over-bound`, `:seon.profile/output-incomplete`, `:seon.profile/output-unknown` | JDK counters owned by that wrapper, same roster/scope/lifetime as timing. These key names expose scalar observations; backing counter members can use the existing counter naming convention. Do not retain sampled values. |
| Observation map, `seon.profile/observation` | Same scalar keys, plus `:seon.profile/output-coverage` (:complete/:lower-bound/:unknown), `:seon.profile/output-token-bound`, optional bulk declaration | Returned data derived from cells. Byte/token measurements state whether exact/estimated/lower-bound; no false exact size for opaque/lazy values. |
| Existing `seon.profile.measurement` entity/schema | Optional sampled count, totals/maxima, incomplete/unknown counts, declared bound, under existing definition digest | Existing significant measurement/settlement records scalar deltas. No transaction per sample, no separate event stream. Stored facts' consumers are health/history and regression comparison. |
| Existing health schema family | `:seon.health/large-output`, candidates from `[:seon.health/profile]`; subject profile/sym; clues sampled/max/bound/coverage; impact `:seon.profile/output-excess-tokens` | Derived finding, not a stored copy of the value. Include output candidates even when cumulative time is below one second. Current profile-unit's timing filter must change. |

`:seon.profile/output-excess-tokens` is derived as estimated completed-call count × mean sampled excess above the applicable bound. Store sample denominators so unsampled/incomplete is visible. Rank primarily by measured estimated excess volume, then max, with the existing health severity/task/caller presentation. Keep intentional bulk in a separate visible ranking; its larger declared bound changes finding policy, not collection or printer limits. An unmeasured producer is unknown, never healthy.

An ordinary map/list/string result is not bulk merely because its current producer is verbose. A declaration names the intended consumer and why the full data is required. DB query/export can declare intentional bulk; help, errors, task/agent status and routine diagnostic summaries cannot use a generic exemption.

### Cost and implementation seam

At `src/seon/profile.clj:310`, `timed` already holds the returned value between the body and `exited`; `src/seon/instrument.clj:999–1021` constructs the wrapper once. Add sampling there, outside validation and outside the measured function body's time; keep a separate sampling-time observation if necessary. The original result and original thrown exception remain identical. Sampling failure increments unknown and contributes a diagnostic at the normal boundary; it must not become a thrown function failure.

Pinned Malli `43c6dca8d2d8df3162b13725939954807be4a4ed`, `reference-code/malli/src/malli/core.cljc:2210–2240`, `-instrument-f`, validates input/output and returns the original value. It provides no output-size telemetry seam; Seon's existing timing wrapper is the correct owner. Read contract properties with Malli's `properties` on the compiled contract at arm time; keep compiled schema/validator reuse. No replacement instrumenter.

Unsampled path: one cheap per-thread/cell counter increment and mask/comparison, O(1), no allocation or clock. Sample first completion, then approximately 1/64 with a per-cell phase to reduce periodic aliasing. Fixed-rate samples do not guarantee catching a single giant result: cheap root string length and counted-collection lower bounds should be checked every call; periodic sampling inspects nested contents. A hidden one-off large tail can remain unknown; do not sell this as a hard memory guard.

Sampled path: a bounded structural fold, O(min(reached nodes, 128)); O(128) temporary space, released at return. Use the printer's existing exact-class/laziness policy; never force a LazySeq, dereference a place, traverse a DB, call arbitrary user count/toString, or inspect a host context. Strings supply a constant-time length lower bound; exact escaped UTF-8 requires scanning and is not the cheap path. Collections contribute known count lower bounds; a capped walk reports incomplete. Do not extrapolate a homogeneous prefix into an asserted exact size. A complete finite sample can use calibrated structural token estimates; both estimates remain estimates.

The benchmark in probe.clj is **only a feasibility prototype**, not the final shared counter implementation or full armed overhead. It walks bounded maps/vectors/sets, counts string/name characters, and adds to a LongAdder every 64th iteration. Five warmed 20,000-call trials:

| Case | Median ns/call | Increment over baseline |
|---|---:|---:|
| branch-only baseline | 11.931 | — |
| five-node map, sampled 1/64 | 32.510 | 20.579 ns |
| capped 128-node walk in 10,000-row vector, sampled 1/64 | 237.510 | 225.579 ns |

Approximate sampled traversal increment is 1.32 µs small / 14.44 µs capped. No content is printed or saved. Prototype counts were 30 payload characters for the small map and at least 755 for the capped walk; the large observation correctly says incomplete. The prototype does not yet implement exact-class admission, UTF-8 accounting, root-count bounds, concurrency, coverage counters, or arm-time configuration. **Installed wrapper overhead remains unmeasured**; require parent/change proof before landing (target <1 µs amortized, and the repo's 20%/50 ms regression rule).

The expensive alternative used for this audit measured complete structural values at 0.35–749 ms on examples. Reuse existing printer accounting only when printing already happens; do not call prepare from every wrapper.

## Full-version path

Rule: **a render applies only to a form's top-level value**. Its output and all nested members then print plainly through the one value printer. A form inspecting a live result is additionally an inspection: its extracted value must print structurally, even when that value could independently select a renderer.

HEAD source evidence: `src/seon/sci/eval.clj:579–598`, bind-result!, saves the actual object in the context's result-objects map and interns it in result; shown-result binds before prepare. SCI pin `ce0aa53b9bf35029e0bc27a962d799c196a37874`, `reference-code/sci/src/sci/core.cljc:260`, intern, supplies the binding. `src/seon/render/value.clj:1048` offers only node zero to declared selection. A new form's extracted map is node zero again: that explains the observed failure.

Live disposable HEAD MCP SCI transcript, agent raw-output:

```clojure
{:nested {:seon.render/ai {:summary :rendered}
          :payload (vec (range 90))}}
;; result/e17d69d58c97a, 2 ms; nested map printed as data, payload 0..31,
;; then …58 (drop 32 (get-in result/e17d69d58c97a [:nested :payload]))

(:nested result/e17d69d58c97a)
;; result/e366b3738cc3f, 0 ms, shown {:summary :rendered} — FAIL: re-rendered

(= (vec (range 90)) (get-in result/e17d69d58c97a [:nested :payload]))
;; result/ea26489d6ce18, 0 ms, true — full raw value retained

(drop 32 (get-in result/e17d69d58c97a [:nested :payload]))
;; result/ead42d86029df, 0 ms; 32..63, then …26 (drop 32 result/ead42d86029df)
```

Default reproduced the same behavior: original e6ccb73bfc465; dig e895e4f5cd87c shows only summary; count e30068f9981c0 is 90. The ordinary continuation works and returns the exact suffix. Neither test proves persistence over restart: live objects are deliberately lost then. A gone handle must remain a typed unknown with saved text still available.

Smallest reliable design: carry an inspection flag in the existing evaluation request/stage value when the existing reader/evaluator observes access to a live result binding. Proposed `:seon.sci.eval/inspection?` lives in that request, consumed by shown-result to set the **already installed** `:seon.render.value/structural?` printer option. Use the same parsed form/evaluation observation; no second reader, source regex, global mode or metadata rewrite of the user's value. A form deriving from a result remains structural; explicit render invocation is still available when wanted. Verify macro-expanded result access and aliases before claiming this seam is implemented. This is a proposed design requiring evaluator-owner review, not a verified existing observation hook.

Do not use `seon.print/full` as the repair: it disables all bounds and does not express “bypass selection”; it also cannot mark strings/numbers. Digging should retain ordinary elisions and budgets.

## Ordered slices, about fifteen minutes each

All proof names here are **proposed**, not installed. Each uses the production MCP SCI entrance on an owned agent; assert returned/stored data, then estimate the complete MCP text under a fixed profile. A shown-token bound alone never proves a lean raw value: producer slices also measure structural raw tokens. Preserve current meaningful tests while changing their retired shape assumptions.

| Order | Slice and ownership | Data proof and MCP bound |
|---|---|---|
| 1 | Trim directory appendix in sci/eval.clj + repl schema; directory tests | `a-directory-contains-only-discovery-data`: 30 public rows preserve symbols/arglists/docs, no :schemas or per-row input/output copies; raw ≤2,000, MCP ≤1,600 tokens. |
| 2 | Group caller sites in my/program.clj + program schema/callers tests | `caller-sites-carry-one-row-per-caller`: two callers × three arities preserve all arities/spans, target arities once; raw ≤500, MCP ≤550. Convert deletion-report and unknown-arity reader in same slice. |
| 3 | Deduplicate profile/top in profile.clj + profile schema/tests | `rankings-reference-one-observation-per-definition`: overlapping ranks share one data row, digest/scope preserved; seven-function raw ≤1,000, MCP ≤1,100. |
| 4 | Trim health finding payload in health.clj + health schema/tests | `findings-do-not-copy-owner-values`: clues and full owner data agree, stored-match query remains executable; three-finding raw ≤1,500, MCP ≤500. Do not truncate arbitrary owner units. |
| 5 | Remove redundant source :doc in my/program.clj + program-read tests | `source-retains-code-contract-and-location-once`: exact source and contract unchanged; short fixture raw ≤400, MCP ≤450. Large requested source remains legal. |
| 6 | Compact public test-check answer in my/test.clj + test response schema/tests | `a-test-check-returns-counts-and-one-failure`: canonical fixture produces one failure, no echoed copies, stored proof digests remain readable by run id; raw ≤250, MCP ≤350. Coordinate errors/test evidence owner; do not delete cache validity facts. |
| 7 | Remove generated example/schema expansion in error.clj, with errors cut owner | `contract-problems-keep-named-expectations`: invalid input prevented, type/operation/path/name kept, no generated example, no copied large expectation; raw ≤250, MCP ≤300. This trims the render producer; wrapper bookkeeping has its own errors-cut dependency. |
| 8 | Add arm-time bulk/config declaration in instrument/profile/schema owners | `bulk-output-policy-is-read-from-the-armed-contract`: omitted/default/explicit bulk cases, invalid reason/bound refuses declaration, valid call unchanged; MCP policy observation ≤150. Publish/adopt resources with consumers. |
| 9 | Add bounded sample counters in existing profile cell/wrapper | `output-observation-preserves-value-and-laziness`: identical return and exception, unforced lazy/opaque marked unknown, one huge string exceeds bound, finite sample totals right; MCP observation ≤200. Benchmark real parent/change wrappers before landing. |
| 10 | Record sampled scalar deltas through profile.measurement | `output-samples-settle-once-with-the-definition`: one settlement, repeat reads add no measurement, old digest never credited; MCP result ≤200. No sampled value stored. |
| 11 | Add health output finding/rank to existing health path | `large-output-ranks-by-sampled-excess`: fast verbose function appears despite <1s total time, unknown stays unknown, bulk policy remains visible; three-row MCP ≤400. |
| 12 | Make result inspection bypass selection in eval/shown-result + existing request schema | `a-live-result-dig-bypasses-render-selection`: exact 90-vector retained, dig shows raw map, render called only for original top level; MCP ≤300 with executable continuation. Host-bound change proven in candidate JVM. |
| 13 | Make full-version continuation reliable across nested paths in existing printer/evaluator tests | `every-result-continuation-recovers-the-exact-suffix`: maps, vector, string, false/nil, macro-expanded handle; compare recovered values as data; each MCP continuation ≤profile budget +100. Gone handle ≤150 and explicitly unknown. |

Sink materialization (#10 in the raw table) is a separate follow-up only after measuring text-only vs both-face allocation; both faces/tree have actual consumers, so deletion is not yet specified. Broad q/pull results get deliberate bulk contracts and narrower first-party queries where their consumers prove the narrower selector sufficient; no blanket data deletion.

## Findings recorded and landing evidence

New tasks were recorded on the owned SCI branch (no issue-index edit):
- `seon.render.value/digging-a-live-result-selects-its-render-again`, handle f136c15ee836.
- `seon.sci.eval/directory-returns-unused-schema-expansions`, handle b14de7e57932.
- `my.program/caller-sites-repeat-callee-arities-and-caller-identity`, handle 6beeeefe198c.

Existing evidence/tasks cover `seon.instrument/contract-refusal-carries-wrapper-bookkeeping`, `seon.error/error-messages-print-growing-values`, test report verbosity, and operator check rehearsal cost. The proposed enforcement is not a claim that any task was implemented.

Disposable suites: my.program-test, my.agent-test, seon.profile-test, seon.render.value-test and the exact required `seon.contracts-compile-test/a-check-of-no-sources-has-no-contract-findings`. First suite execution completed before the measurement expression failed because the JVM-mode prepare request omitted its explicit database. No test outcome was fabricated from that failed envelope. Corrected run `54017c6c64e4`: **83 members, executed 2, reused 81, pass 604 / fail 0 / error 0, 6,328 ms**. Named proof is among the returned members with two passing assertions. Reused evidence is reported as reused, not new execution. No pre-existing red appeared in this selected suite set. Other namespaces were not run.

A separate real `my.test/check` for the required var through disposable MCP took 1,324.875 ms to produce its result, 2.593 ms to measure it; 42,178 armed calls, including 18,601 projection-cache-value calls. This slow check is existing test plumbing cost, not output serialization alone. Existing test-cost issues own it.

Required working-tree lint: `clj-kondo --lint src script test --cache false`, **7,415 ms, 0 errors, 1,461 warnings**. Warnings are not a zero-findings pass. Probe's transport JSON/code quoting is outside Seon's product output; raw values themselves were measured solely through the existing value printer.

Foreign boundary at entry included repl/eval/turn/transcript changes, reply/batch schema changes and branch/evaluation tests, plus unrelated research. Nothing foreign was edited, restored, staged or loaded. Source citations are pinned observations, not evidence that working-tree bytes run on default. HEAD advanced during the audit; the disposable evidence remains tied to e9e3b3253, while the lane gate validates its own later HEAD plus these two paths.

Only raw-output.md and probe.clj are lane deliverables. Net production source/test lines: **0/0**. No provider call, worktree, amend, push or PR. The owned root exited through bin/seon down with verified PID/start identity and process-exit true; default stayed available. The final commit uses the required lane gate and Co-Authored-By footer; the command's test receipt is the landing gate evidence, not proof that this design is installed.
