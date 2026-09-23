---
type: landing
status: awaiting-orchestrator-proof
created: 2026-09-23
---

# Reviewed error constructor cut

The existing diagnostic preserves a supplied observation and derives whole-cause evidence only from its optional Throwable. The smallest composition adds a positional arity to that same Var, retaining literals outside the reviewed grammar; construction remains O(member count), with existing O(cause links + frames) Throwable work.

Authority: `error-constructor-cut-manifest-2026-09-23.md`, reviewed and folded at `0f0d9f619`; user launch and ownership release supersede the stale ledger rows. Admission now holds exactly current dirty paths plus `test/seon/render/value_test.clj`. In `tmp/error-constructor/census.clj`, `held` is that one explicit path and `held?` is `(or (held f) (dirty f))`. Each selected census records the current ledger digest and whole-file hashes. The unchanged preview checks them; application verifies every roster before/after hash before writing any file.

Malli seam: gitlink `8725a8cbd9d595f4a970ce53a2eefdbe7211b96d`, `reference-code/malli/src/malli/core.cljc:1210` open maps and `:2237` arity grouping. The dependency working tree is foreign and untouched. Contract compilation uses the existing `seon.contracts-compile-test/check`, absolute checkout paths, and the live carried projection only when its packaged forms match the checkout.

## Verification boundary

The user reserved adoption and proving tests for the orchestrator. No adoption, restart, test request, push, or additional JVM was run. The live JVM source root was `data/source/b66a654382d730debd05c40cb1d22558546c763f`, with publication off. Static contract checks read checkout bytes explicitly; they do not establish runtime adoption, constructor overhead, reaching coverage, or passing tests. Dirty stopped-lane source/test/dependency files remain foreign boundaries.

Parent JVM probe: with explicit root `/Users/sean/src/seon`, cluster `default`, private session `sol-error-constructor`, call `(seon.error.refusal/diagnostic {:seon.error/at at :seon.error/layer :x/y :seon.error/operation 'a/b})` for `(java.util.Date. 0)`. Returned the exact three-member value; `(identical? at (:seon.error/at returned))` was true. Parent contract findings `[]`; combined probe/check 56.998 ms (prepl 70 ms). This is parent behavior only.

E0 is split at existing owner boundaries because complete regression changes exceed the provisional 40-line allowance. E0a couples the helper to error-owner conversion and prose deletion; E0b carries blob conversion and its producer regression. Neither is a helper-only slice.

| Slice | Sites | Src + / − / net | Test + / − / net | Kondo | Packaged contracts | Proving namespaces |
|---|---:|---|---|---|---|---|
| E0a `bab838388` | 4 | 20 / 44 / −24 | 60 / 31 / +29 | 0 errors, 74 existing warnings; 150 ms | `[]`, 110.274 ms | `seon.error.refusal-test`, `seon.error-test`, `seon.schema-test`, `seon.refusal-grammar-test` |
| E0b `2efb85875` | 4 | 12 / 20 / −8 | 19 / 0 / +19 | 0 errors/warnings; 23 ms | `[]`, 27.582 ms | `seon.blob-error-test` (also retain `seon.blob-test` corruption coverage) |

E0a hand work: exact reviewed additive arity; extend both-entry whole-value/cause/domain tests, arm both entries under one projection in the producing-contract case, assert argument order/identity and original ex-info cause, and exercise actual `prepare` with overriding source evidence and its exact declared schema. The preview removed only `refusal-prose`, its obsolete test block, and converted its two renderer fixtures. Retained error-owner O/X literals preserve evaluation order and unsupported contexts. Existing cause-policy repairs are outside this mechanical cut. The +5 net E0a lines are regression evidence; source shrinks 24 lines. Later caller cuts repay this test growth before integration.

Initial E0 selected census: 365.874 ms; preview admitted eight sites and four files, net −50 before helper/regressions. The required pre-residue lint found eight four-argument calls before the arity was added; final E0a lint has zero errors. No operation initiated by this lane exceeded one second so far. Cache hit/miss counters are not exposed by these static tools; the checker reuses an equal packaged projection through its existing candidate input.


E0a commit `bab838388`. E0b re-censused the clean blob file (97.739 ms), applied only its four admitted sites, and added the actual stalled-input producer's whole-value assertion under its unchanged `:seon.blob/input-stalled-error` declaration. The unsupported `stage-file!` literal stays unchanged. Source shrinks eight lines; the 19 regression lines explain the temporary net growth. E0 total: source −32, tests +48; 111 src/test additions. New assertions are authored, not executed; orchestrator still owes armed runtime/cost/coverage and all five E0 proving namespaces.


## Sequential single-file slices

Every row has zero lint errors and packaged findings `[]`. All tests remain orchestrator proof obligations. No producer contract was changed in these slices. Script/status/roster/lint/contract evidence is in `tmp/error-constructor/<slice>-*`.

| Slice and path | Sites | Src + / − / net; test net | Kondo warnings / ms | Contract ms | Proof namespace | Hand residue |
|---|---:|---|---|---:|---|---|
| E1 `7582fff03`: `src/my/background.clj` | 1 | 3 / 5 / -2; 0 | 0 / 11 | 21.237083 | `my.background-test` | None |
| E2 `1f6295e60`: `src/my/program.clj` | 4 | 14 / 21 / -7; 0 | 1 / 27 | 27.987833 | `my.program-test` | X literal retained; existing read-result catch retains class/message/data but omits the cause chain (outside this cut) |
| E3 `9848f98f7`: `src/seon/agent.clj` | 1 | 4 / 5 / -1; 0 | 1 / 17 | 25.1875 | `seon.cluster.agent-identity-test` | Two O literals retained to preserve header evaluation order |
| E4 `c47da5ccc`: `src/seon/ai.clj` | 14 | 41 / 70 / -29; 0 | 0 / 42 | 27.304333 | `seon.ai-test` | Three C literals retained with attached comments; first also has interleaved header order. Existing extra-body catch keeps only its declared read-message |
| E5 `6314ef453`: `src/seon/await.clj` | 1 | 3 / 5 / -2; 0 | 0 / 13 | 22.220208 | `seon.await-test` | None; existing merge order retained |
| E6 `a59ebd7e3`: `src/seon/background.clj` | 2 | 6 / 10 / -4; 0 | 0 / 12 | 24.328125 | `seon.background-test` | None |
| E7 `1bb9a1e71`: `src/seon/bootstrap.clj` | 4 | 12 / 20 / -8; 0 | 0 / 28 | 23.072166 | `seon.bootstrap-test` | One X literal retained; outer ex-info unchanged |
| E8 `4a5cea540`: `src/seon/call_preparation.clj` | 5 | 15 / 25 / -10; 0 | 3 / 40 | 24.954542 | `seon.call-preparation-test` | One X literal retained; supply catch still carries its original Throwable as offending evidence |
| E9 `8acaaefeb`: `src/seon/cluster/agent.clj` | 2 | 8 / 11 / -3; 0 | 18 / 42 | 27.579083 | `seon.cluster.agent-test` | None |
| E10 `56513e23a`: `src/seon/cluster/process.clj` | 4 | 12 / 20 / -8; 0 | 2 / 16 | 21.845958 | `seon.cluster.boot-test` | None; platform loadability and affected integration remain orchestrator proof |
| E11 `d942eeb27`: `src/seon/cluster/prompt.clj` | 1 | 5 / 6 / -1; 0 | 0 / 19 | 23.590542 | `seon.cluster.prompt-test` | One O literal retained to preserve header evaluation order |
| E12 `d49b574be`: `src/seon/cluster/source.clj` | 4 | 14 / 19 / -5; 0 | 7 / 32 | 24.780792 | `seon.cluster.source-test` | One X literal retained; input-inventory ex-info preserves original cause. Publication clock and parent/child probe deferred to orchestrator |


## Stop and handoff

Stopped immediately after E12: fourteen source slices, 51 sites; cumulative commit additions 302 (248 src/test and 54 landing-note lines), removals 313. Source +169/−281 = **−112**; tests +79/−31 = **+48**; src/test **−64**. This documentation receipt is separate from the stopped production cut. E13 is `src/seon/cluster/status.clj`; no E13 or later source was edited. All owned paths were committed and released; unrelated dirty paths were preserved.

The source/proof paths are the two E0 groups in the manifest, plus `test/seon/blob_error_test.clj`, and each path in the table above. No manual residue conversion widened the admitted A set. Hand work was confined to E0's arity and regressions. Existing tests are proving requests, not positive reaching evidence; complete-value coverage of every converted branch and profiling both constructor entries are still required during integration. The publication-owner slice needs its committed measurement-script clock row and identical parent/child probe at that boundary. No RESET NEEDED was established.

Timings: all recorded lane commands and REPL checks were sub-second. E1–E12 maximum measured command was E4 census, **220.630 ms**, proportional only to that file's parsed bytes; maximum constructor-file packaged check was E0a **110.274 ms**. Per-command timings and raw contract envelopes, including exact forms, are retained in `tmp/error-constructor/`. The first status request briefly observed no cluster during external process replacement; subsequent `bin/seon status` and the private REPL session observed pid 42146 alive. No runtime restart was performed by this lane.

Existing swallowing/error-policy residue found in touched files is retained under the manifest's prohibition on widening into cause-policy repair: `my.program/read-result` keeps message/class/data but no chain; `seon.ai/extra-body` and `stream-fold` reduce caught failures to message evidence, the stream sink catch returns nil (`src/seon/ai.clj:868`), and `seon.cluster.process/process-start-instant` catches Throwable and returns nil (`src/seon/cluster/process.clj:85`). The converted source-inventory ex-info still carries its original Throwable cause. No new catch was introduced.

The tool-triggered repository Markdown audit reported 46 findings, with surfaced examples citing obsolete dependency pins in other landing notes; its full output was elided. That repository-wide audit is not a pass for this cut. The scoped citation check for this landing is recorded separately below.

Scoped landing citation check: `bb script/seon/dev/citations.clj docs/prds/agent-platform/landing/lane-sol-error-constructor-2026-09-23.md` — **1 document, 0 failures, 74 ms**.


## Continuation from E13

Authorized after the diagnosis at `6cea7a3e0` (`docs/research/agent-platform/error-constructor-reds-2026-09-23.md`). That diagnosis found no demonstrated E-slice regression: 25 of 27 reds have pre-existing causes; two remain unattributed. Constructor/error/schema proof members were still pending, and my.* proof namespaces were omitted from the earlier request. This continuation is not a claim that the first tranche is green.

The same diagnostic owns construction; each conversion preserves ordered value expressions and the complete remainder. Cost stays proportional to error members and existing Throwable evidence. No new runtime mechanism, schema, or error taxonomy is introduced. Holds remain current dirty paths plus the explicitly held value test. Each slice rechecks Git status and regenerates a selected census before preview/application.

Both status entrances observed default PID 44576, source archive `b1ff7ba6cf9be07701393a3038f7f48eb6f3bb3a`, publication off, and currently no replaced roots. The diagnosed unfinished-test/contract-restoration boundary remains orchestrator evidence to resolve; this lane inspected no foreign session and did not run tests or adopt. Packaged-contract checks read absolute checkout paths on that live JVM.

| Slice and path | Sites | Src + / − / net; test net | Kondo warnings / ms | Contract ms | Proof namespace | Hand residue |
|---|---:|---|---|---:|---|---|
| E13 (`8156322b6`): `src/seon/cluster/status.clj` | 1 | 5 / 6 / -1; +0 | 1 / 50 | 64.458709 | `seon.cluster.status-test` | None |
| E14 (`39218a345`): `src/seon/cluster/wake.clj` | 4 | 14 / 21 / -7; +0 | 2 / 20 | 29.618417 | `seon.cluster.wake-test` | None; listened/arming declarations and queries unchanged |
| E15 (`e36e71795`): `src/seon/config.clj` | 2 | 7 / 11 / -4; +9 | 8 / 46 | 34.138 | `seon.config-test` | Removed generated blank require line; strengthened both conditional-evidence cases to whole values, replacing stale message expectation; 16 X literals retained |
| E16 (`c07e89393`): `src/seon/context.clj` | 1 | 5 / 6 / -1; +0 | 1 / 20 | 26.494667 | `seon.context-test` | None; subsequent assoc remains outside construction |
| E17 (`7ef868d59`): `src/seon/db.clj` | 4 | 11 / 20 / -9; +0 | 17 / 129 | 64.850583 | `seon.db-test` | K/X literals and raw Throwable map retained; existing diagnostic flattened |
| E18 (`12ee273af`): `src/seon/edit.clj` | 10 | 28 / 50 / -22; +0 | 0 / 22 | 24.876458 | `seon.edit-test` | None; existing catches and evidence expressions preserved |
| E19 (`8eb40dfda`): `src/seon/effect.clj` | 14 | 41 / 70 / -29; +0 | 3 / 29 | 25.0675 | `seon.effect-test` | None; unsupported expression retained |
| E20 (`517b6e43f`): `src/seon/env.clj` | 10 | 30 / 50 / -20; +0 | 0 / 17 | 23.766875 | `seon.env-test` | None; complete member evidence and ex-info boundaries preserved |
| E21 (`0c63cd3f3`): `src/seon/flow.clj` | 4 | 14 / 21 / -7; +0 | 6 / 41 | 26.661375 | `seon.flow-test` | None; four unsupported expression contexts retained; no graph change |
| E22 (`8c1581b00`): `src/seon/issue/opening.clj` | 2 | 6 / 9 / -3; +0 | 0 / 15 | 26.119667 | `seon.issue-test` | None; dirty issue.clj untouched |
| E23 (`0ef0f9a60`): `src/seon/maintenance.clj` | 6 | 18 / 30 / -12; +0 | 5 / 30 | 28.147708 | `seon.maintenance-test` | None; raw Throwable and unsupported context retained; ex-info cause preserved |
| E24 (`d5a489534`): `src/seon/plan.clj` | 2 | 8 / 11 / -3; +0 | 0 / 50 | 29.055334 | `seon.plan-test` | None; 22 unsupported expression contexts retained |
| E25 (`dd24e4e81`): `src/seon/render.clj` | 5 | 16 / 26 / -10; +0 | 4 / 50 | 28.856417 | `seon.render-simplification-test` | Removed generated blank require line; O/X literals retained to preserve evaluation order |
| E26 (`c511ae0ff`): `src/seon/render/data.clj` | 1 | 5 / 6 / -1; +0 | 1 / 15 | 23.361084 | `seon.render.data-test` | None; surrounding assoc and unsupported context retained |
| E27 (`2070c7c75`): `src/seon/render/hiccup.clj` | 4 | 12 / 20 / -8; +0 | 3 / 17 | 24.18825 | `seon.render.hiccup-test` | None; all four admitted sites converted |
| E28 (`7802dfce7`): `src/seon/render/transcript.clj` | 3 | 9 / 15 / -6; +0 | 9 / 85 | 31.097458 | `seon.render.transcript-test` | None; complete selection and runtime evidence retained |
| E29 (`a0fb1d5b8`): `src/seon/render/value.clj` | 2 | 6 / 10 / -4; +0 | 2 / 29 | 24.629458 | `seon.render.value-test` | None; explicitly held value_test.clj untouched; existing window catch policy retained |
| E30 (`eece20496`): `src/seon/render/walk.clj` | 1 | 3 / 5 / -2; +0 | 2 / 31 | 24.987875 | `seon.render.walk-test` | None; two reordered headers and unsupported context retained |

### E13–E30 stopping receipt

Stopped after E30 at **289 added / 399 removed** lines across these eighteen slice commits (259 src/test additions and 30 landing-note additions). **76 sites** converted. Source +238/−387 = **−149**; tests +21/−12 = **+9**; combined src/test **−140**. This documentation receipt is separate from the stopped production cut. E31, `src/seon/render/web.clj`, is next; it was not edited. Every source path in the continuation table and `test/seon/config_test.clj` is clean and released. The held value test and all unrelated dirty files remain untouched.

All eighteen packaged-contract checks returned `:findings []`; all touched-file lint runs reported zero errors. These checks compile contracts from absolute checkout paths against the packaged projection; they do not load the edited owners, demonstrate adoption, or execute the proving namespaces in the table. No tests, adoption, process restart, cold gate, push, or foreign-session action was performed. The triage's two unattributed reds and unfinished-test boundary remain orchestrator proof obligations. Dirty cluster/fn/instrument/issue/test owners and dependency forks remain the foreign shared-tree boundary; no repair or runtime-proof claim is made for them.

Hand work: E15 removed the script-generated blank require line and strengthened two existing conditional-evidence cases in `test/seon/config_test.clj` to full-value assertions, replacing one stale message expectation. E25 removed the same generated blank require line. Retained O/X/K/T literals are listed per slice in the raw residue files; no unsupported site was manually admitted. Existing catch-policy defects remain outside this mechanical cut: edit parsing/lossless verification carries message-only cause evidence, and render.value/window reduces a caught failure to message evidence. No catch policy was changed.

All measured individual lane commands and REPL checks remained sub-second. The maximum recorded command was the E15 slice wrapper, **426.549 ms**, proportional to its selected file; the slowest packaged-contract check was E17, **64.851 ms**. Exact envelopes, file bases, generated rosters, residue and command timings remain under `tmp/error-constructor/E13-*` through `E30-*`. Runtime behavioral and performance proof belongs to the orchestrator's named namespaces above; static contract compilation is not that proof.

Continuation scoped landing citation check: **1 document, 0 failures, 77 ms**.

## Continuation from E31

Authorized after triage `7f0c47053`: 32 of 43 reds pre-existing; 11 duration-only cases remain unattributed. The existing diagnostic constructs the same flat value from ordered header expressions and unchanged members; the smallest change is the reviewed caller conversion. Work stays proportional to member count and existing cause evidence, with no new mechanism or state.

Both status entrances observed PID 48902, archive `ce73846828a5cc32798ef630b5a574f777646f30`, publication off, no replaced roots. All proof namespaces remain orchestrator obligations. No adoption or tests are authorized in this tranche. A separate throwaway-namespace parent/HEAD timing probe will isolate one constructor site without replacing default Vars.

| Slice and path | Sites | Src + / − / net; test net | Kondo warnings / ms | Contract ms | Proof namespace | Hand residue |
|---|---:|---|---|---:|---|---|
| E31: `src/seon/render/web.clj` | 6 | 18 / 30 / -12; +0 | 10 / 137 | 42.57925 | `seon.render.web-test` | None; five X contexts retained; dirty web test untouched |
| E32: `src/seon/run.clj` | 2 | 8 / 11 / -3; +0 | 0 / 11 | 22.805667 | `seon.run6-stall-test` | None |
