---
type: review
created: 2026-09-25
status: accept-after-fixes
---

# Custody and ingestion P1 review

**Verdict: ACCEPT-AFTER-FIXES**, numbered below. The original three ingestion P1 corrections compose the existing task writer and merge gate correctly. The broader custody class is not closed, and `program-row` still approximates a transaction's final value.

This is a static review against `bd9e0a9f66b3177aec6cb835da7cffd72dcb1663`. I read every requested diff: `9bfa6fff2`, `d83286cee`, `9bff631b2`, `b5c7d2765`, `c1e40b7e8`, `586c83c9f`, `43f484d62`, and `8e731ac68`. File:line references below use that baseline unless explicitly identified as dependency source. Other lanes advanced HEAD during review, including later `fn.clj`, `sci/eval.clj` and `seon.fn.edn` changes. Those changes are outside this verdict; citations remain pinned to the requested review baseline.

Authorities read first: `AGENTS.md`; the complete [earlier review at 5fb38b91f](s0-and-ingestion-fixes-diff-review-2026-09-25.md); the [ingestion landing](../../prds/agent-platform/landing/lane-fix-ingestion-p1s-2026-09-25.md); [acquisition refusal custody](../../seon/issues/acquisition-refusal-records-write-under-the-callers-custody.md); and [nested test cost](../../seon/issues/a-nested-test-run-costs-four-seconds.md). The [language requirements](language-requirements-2026-09-24.md), particularly 7.13, 7.16 and 8, supply the task and fault rules. This review applies the data-oriented Clojure, Datahike and testing skills; the REPL skill informs the evidence limits.

No source edits, tests, JVM evaluation, publication, adoption, stop/reset, worktrees, or other-lane interactions were performed. This review does not certify installation or a green runtime. Exactly this review file is the lane's write scope.

## Required fixes

### 1. P1 — put acquisition refusal custody at the recording owner

`src/seon/cluster/agent.clj:987` correctly protects its call to `sci.eval/acquire!`, but that is not every entrance to acquisition. The actual branch-writing producer is `src/seon/sci/eval.clj:2377`, `record-acquisition-refusals!`: it obtains the context's explicit connection at `:2390` and calls `db/transact!` at `:2426` without binding it.

A surviving bypass is lazy invocation: `src/seon/sci/kernel.clj:606` calls `ensure-function!` **before** the custody binding at `:607`. Its installation callback (`kernel.clj:170`, installed at `sci/eval.clj:3330`) reaches `acquire-function-from-database!` and `acquire!` at `sci/eval.clj:3288`. A lazy context for branch B invoked under A's custody can therefore still attempt B's refusal records under A. It need not pass through `cluster.agent/acquire-context!`. This is a source-derived conditional path, not a newly executed failure.

**Smallest fix:** move the acquisition-specific `call-with-custody` from `cluster.agent/acquire-context!` to the connection-bearing recording branch in `record-acquisition-refusals!`, around its transaction. Leave the connectionless supplied-fault-recorder path with its existing owner. This relocates one binding instead of adding wrappers to acquisition's callers. Keep the gate's separate work scope.

**Required proof:** acquire a branch with a real un-installable declaration under another branch's custody; positively read its refusal occurrence from the acquired branch and prove no foreign-connection refusal. Cover both the agent acquisition entrance and lazy invocation. The landing explicitly says its scratch world had no acquisition refusals. The new regression at `test/seon/cluster/source_test.clj:309` also creates ordinary installable rows, so it does not prove this path.

### 2. P1 — the remaining branch-owned write scopes still inherit caller custody

The writer's guard is correct: `src/seon/db.clj:457` compares the ambient and explicit connection identities; `:4822` applies that guard before transaction dispatch. Neither `store/open-branch!` nor returning an execution handle changes the caller's dynamic scope. The following owners still perform their own branch work without establishing its custody:

| Owner and acquired target | Write under inherited custody | Smallest fix |
|---|---|---|
| `source/publish!`, `src/seon/cluster/source.clj:632`, opens scratch at `:637` | Scratch schema `:653`, population `:657`, issue/task ingestion `:666`/`:667`, seal `:678` | Bind the scratch connection around the existing scratch-work body, covering all its writers together. |
| `source/record-results-at-head!`, `src/seon/cluster/source.clj:438`, opens scratch at `:443` | Completion recording `:460` or run admission `:465` | Bind the scratch connection around both branches of the existing recording body. |
| `registry/record-fork!`, `src/seon/cluster/registry.clj:245` | Initial cluster/config/source rows at `:255`; reached by `ensure-cluster!` at `:289` and `refork!` at `:326` | Bind the new cluster connection once in `record-fork!`. |
| `cluster.agent/release-context!`, `src/seon/cluster/agent.clj:818`, stops an acquired handle's owned graphs | `stop-owned-graphs!` `:798` → `stop-graph!` `:1349` → `record-interruption!` `:1261` writes the graph's connection when its turn remains open | Bind that connection at `record-interruption!`, covering direct disarm as well as release; preserve the existing join-before-record ordering. |

These are conditional failures when the caller holds a different custody, not claims that ordinary custody-free operator calls always fail. There are concrete in-process callers: `test/seon/cluster/source_test.clj:103` enters `with-database`, then its publication helper calls `publish!` at `:145`; `test/seon/test/publication_test.clj:37` calls `record-results!` inside a test body. `test/seon/cluster/registry_test.clj:139` calls `ensure-cluster!`, and `:146` separately transacts on the opened branch without the fixture writer. Host test bodies are bound too (`src/seon/test/runner.clj:388`), so they cannot be dismissed as custody-free.

For boot invoked inside another evaluation, the same condition also applies to `src/seon/cluster/boot.clj:223`/`:255` opening its connection, `:243` schema accretion, and `stand-cluster-runtime!` at `:61`, `:72`, `:74` recovering/seeding that connection. An ordinary external boot has no ambient custody. The boot owner should scope its branch work at these existing boundaries; no second acquisition API or writer exemption is warranted.

The gate binding ends before `release-context!` (`src/seon/cluster.clj:2777`) and before `advance!` (`:2780`). That is correct for advancing a different destination, but it makes cleanup's own write ownership necessary. An empty candidate graph does not exercise interrupted-turn recording.

**Required proof:** use the existing publication, registry and agent-release regressions under a different outer custody, then check the intended branch and unchanged parent. In the gate regression, make its candidate-write callback call the real `db/transact!` and assert `:db-after`: its current `test-support/transacted!` call (`source_test.clj:331`) independently binds custody (`test_support.clj:234`), masking a missing gate binding for that write. The nested run still exercises the gate binding; the candidate write currently does not.

### 3. P1 — distinguish request refusals from failure to record owned test work

The added alternative at `src/seon/test.clj:2090` is the correct schema **for the value currently returned**: `db/transact!` declares `:seon.db/transaction-refused-error` (`src/seon/db.clj:4802`), and `run` can pass its admission-write refusal through `test.clj:2205`, `:2207`, `:2341`. Leaving a reachable return undeclared merely adds an output-contract failure; reverting that line alone is not a fix.

Classification is a separate question. An explicitly invalid request, such as a caller deliberately naming an unauthorized recording connection, can be a returned agent mistake. The new negative example at `test/seon/cluster/source_test.clj:336`–`:343` exercises that condition; it does not establish that an internal recording failure should be an ordinary refusal. A valid candidate request whose core gate forgot custody, or whose core-produced completion cannot be stored, is a core fault.

Currently an admission refusal returns without the fault owner, while a completion refusal becomes `:seon.test/recording-refusal` (`src/seon/test.clj:2260`–`:2275`) and a refused stopped-run write is likewise only attached to the answer (`:2300`, `:2323`). `db/write-observation` (`src/seon/db.clj:4502`) attaches the request and basis to a value; it does **not** commit or deliver an error. Thus an accurately declared return shape does not satisfy “store and deliver core faults.”

**Smallest fix:** retain declared caller/admission mistakes, but route unexpected refusals of the runner's own admission/completion/stop writes through the existing `seon.fault/fault!` boundary (`src/seon/fault.clj:179`) with the recording world's environment and the full refusal. It already records, delivers and applies panic/record policy; storage failure panics (`:163`). Do not blanket-classify every transaction refusal as a core fault, invent another error union, or add a retry. Under record policy the request must still remain unsuccessful. Apply the same distinction to the existing watcher recorder at `test.clj:2232`, rather than maintaining a different policy for synchronous recording.

**Required proof:** distinguish a real caller mistake from an internal recording defect; for the latter, establish a durable occurrence, the configured delivery/visibility, false success, and panic behavior when recording itself fails. Preserve the original refusal and cause chain. No tests were run for this review.

### 4. P1 — `program-row` shares the digest function, but does not always supply the writer's final value

The namespace fixes are right: `586c83c9f` selects the stored namespace, and `43f484d62` reads it through `db/declaration-value`, avoiding pulled vectors where the namespace contract requires sets (`test/seon/test_support.clj:797`). `revised-row` calls `db/definition-digest-of` at `:769`; the writer calls exactly that function at `src/seon/db.clj:4256`. There is no new hash algorithm or second digest authority in these commits.

However, `8e731ac68` supplies a separately reconstructed “final” row: `test_support.clj:813` unions old **sets** and replaces every other supplied value. Datahike accumulates cardinality-many assertions regardless of the Clojure collection's spelling (`reference-code/datahike/src/datahike/db/transaction.cljc:719`, `:747`–`:764`). A vector of new component maps is not a replacement of the old components; anonymous nested maps allocate new entities (`:947`–`:973`).

Concrete counterexample: write a test with a nonempty `:seon.test/seed`, then upsert another `program-row` for that same test with a nonempty seed. Seed occurrences are vector-valued components (`resources/seon/schemas/seon.test.edn:45`), produced without component identities by `src/seon/fn.clj:527`. The helper hashes only the new vector. The actual upsert keeps old component links and adds new ones; `db/declaration-value` reads both. Seeds belong to the test definition (`seon.test.edn:122`) and are not excluded from its digest (`src/seon/program.cljc:375`). Even repeating one anonymous seed can produce this mismatch. Function arities, by contrast, are digest-excluded, so they are not evidence for this particular finding.

There is also a simpler normalization limit: a new optional `:seon.fn/must-read #{}` override is a legal set (`resources/seon/schemas/seon.fn.edn:68`), but it creates no datom. The helper's canonical row retains that empty key, whereas the writer's row lacks it (`src/seon/program.cljc:1177` only special-cases namespace collections). A namespace changed elsewhere in the same transaction also cannot be predicted from the helper's prior database argument.

**Smallest fix:** remove the new generic upsert simulation. For replacing authored source, submit the canonical desired row through existing `seon.fn/reconcile-tx` (`src/seon/fn.clj:3530`), whose existing exact-replacement mechanism retracts changed owned attributes/components (`src/seon/program.cljc:1332`, `:1352`). Keep `program-row` as the analyzed desired row; convert its replacement callers to the existing transaction producer. Use `revision` only for its deliberately partial edits that leave components alone. Normalize/refuse empty overrides at that existing producer boundary, and use the transaction's actual namespace row when it changes. Do not extend the set merge into a homemade Datahike evaluator. If generic upserts truly remain required, derive their resulting value through Datahike's speculative transaction mechanism and reuse `declaration-value`/`definition-digest-of`, with normal final admission retained at the real writer.

**Required proof:** a seeded test replaced twice; an optional empty set; and a namespace resolver change submitted with its declaration. Compare the persisted final value's writer digest, not two calls to the helper. These are static counterexamples, not runtime results reported as observed failures.

## Answers to the ownership and caller questions

**1. Two dynamic scopes are necessary with the current APIs.** Acquisition owns the writes performed before it returns; the gate owns its callback and nested request afterward. `db/call-with-custody` (`src/seon/db.clj:360`) is the one binding mechanism. It binds `*conn*` and clears the old read basis at `:381`. Clojure `binding` restores the caller in `finally` (`reference-code/clojure/src/clj/clojure/core.clj:1968`–`:1992`), so a returned handle cannot carry an active dynamic binding with it. Putting the gate's binding only in `acquire-context!` would leave its later work uncovered. Putting it in `transact!` would erase the foreign-branch guard. A new callback-style acquisition wrapper is unnecessary for these fixes. Finding 1 moves acquisition recording to its common producer; it does not eliminate the later work owner's scope.

**2. Caller inventory.** Searches covered `src`, `script` and `test` using `rg` for `call-with-custody`, `acquire-context!`, `branch-connection`, `open-branch!`, and branch-connection `transact!` sites; I followed the indirect writers. The uncovered owners are in findings 1–3. The other relevant callers are accounted for below, including already-protected ones, to avoid misidentifying every acquired handle as a bug.

| Caller | Acquisition / write references | Custody assessment |
|---|---|---|
| Candidate merge | `src/seon/cluster/source.clj:817` callback; `src/seon/cluster.clj:2763`, `:2769`, `:2773` | Fixed for the candidate write and nested run. |
| Test checkpoint | `src/seon/test.clj:1681`, `:1683`, `:1690` | Acquires its branch connection and binds its preparation writes. |
| Test request overlay | `src/seon/test.clj:1758`, `:1761`, `:1762` | Binds the new request branch's overlay write. |
| Test member / seeds | `src/seon/test.clj:1868`, `:1925`; seed writes at `:160`, `:162`, reacquisition at `:175` | Child custody is handed to the body; host runner binds it at `src/seon/test/runner.clj:388`, SCI evaluation at `src/seon/sci/eval.clj:3989`. Acquisition's own refusal recording remains finding 1. |
| Canonical database fixture | `test/seon/test_support.clj:659`, `:663`, `:605`, `:611` | Acquires, then scopes the entire fixture body and extra-schema write. |
| Fixture writer / config / seed | `test/seon/test_support.clj:234`, `:849`, `:865` | Explicitly binds the supplied connection; this can hide a missing caller scope in a regression. |
| Fault copies across branches | `src/seon/fault.clj:119`, `:128`, `:131`; home lookup at `:144` | The only production `db/branch-connection` write consumer found already binds each target correctly. |
| MCP named branch evaluation | `script/seon/dev/mcp.clj:532`, `:535` | Subsequent forms enter `sci.eval/evaluate`, which binds their context's connection at `sci/eval.clj:3989`. The active-branch lookup at `mcp.clj:641` only reads an error window. |
| Issue test settlement | `src/seon/plan.clj:714`, `:716`, `:718` | Acquires a handle but records on the supplied cluster connection, not blindly on the newly acquired handle. Ordinary same-branch settlement is not another child-write defect. If those branches differ, the request needs an explicit admission refusal, not automatic permission to write another branch. |
| System turn / arming | `src/seon/turn.clj:2110`, `:2116`; `src/seon/cluster/agent.clj:1113`, `:1119`, `:1129` | System-turn evaluation uses the acquired SCI context; arming passes the acquired connection into the graph's handle. Acquisition writes are finding 1; later release/interruption writes are finding 2. No claim that acquiring alone binds asynchronous work. |
| Merge fixture candidates | `test/seon/cluster/merge_test.clj:38` → `:46`, `:47`, `:51`, `:58`, `:67`; `:98` → `:101`; `:109` → `:110` | All use `write!` at `:19`, hence the protected `transacted!`. |
| Task merge fixture | `test/seon/cluster/source_test.clj:370` → `:377` | Uses the protected fixture writer at `:358`; distinct from the production callback. |
| Deliberate foreign run | `test/seon/cluster/source_test.clj:336` → `:338` | Intentionally runs under the outer custody and expects a refusal; finding 3 explains the classification limit. |
| Agent custody fixture | `test/seon/cluster/agent_custody_test.clj:37`, `:48`, `:52` | `created!` at `:11` uses `transacted!`; protected. |
| Candidate namespace worker | `test/seon/namespace_agent_loop_test.clj:26`, `:44`, `:62`, `:67` | Whole branch work explicitly scoped before both fixture writes and `issue/start!`. |
| Agent release/race fixtures | `test/seon/cluster/agent_test.clj:1022`, `:1033`; `:1077`, `:1091` | Setup writes are protected. The second fixture starts futures after its setup-only scope ends (`:1095`), reinforcing why teardown must own its recording scope. |
| Render cache fork | `test/seon/render_cache_test.clj:67`, `:74` | Fork message written through `transacted!`; protected. |
| Adoption fixture's source writes | `test/seon/issue/task_ingestion_test.clj:171` | `b5c7d2765` correctly rebinds source while the inner fixture owns destination. |
| MCP branch fixture | `test/seon/dev/mcp_bridge_test.clj:735`, `:742`, `:744` | Active connection explicitly scoped before fixture write. |
| Direct SCI branch fixture | `test/seon/sci/branch_execution_test.clj:46`, `:48`, `:52` | `on-branch` explicitly clears outer custody; declaration writes at `:38` use `transacted!`, and evaluations establish their context's custody. This fixture cannot prove that acquisition itself tolerates a foreign outer custody. |
| Store/export branch fixtures | `test/seon/cluster/store_test.clj:317`, `:320`; `test/seon/cluster/export_test.clj:78`, `:80` | Branch writes use `transacted!`. Registry fixture's direct write at `registry_test.clj:146` is the exception noted in finding 2. |

The remaining agent-acquisition hits in projection, release/retention, entrance-supplier, mode and acquisition tests either inspect/release a handle, use these same fixture writers, or evaluate through the SCI entrance. `render/acquire-context!` is a rendering operation, not branch acquisition. `cluster/export.clj:167` borrows a source reader; its target import (`:182`) uses Datahike migration on a separate store, not `seon.db/transact!`. `eval/drive.clj:347` creates a grading branch and returns its name without writing through an acquired connection. These are not additional instances of this guard failure.

**3. The new return alternative is structurally honest, but does not classify faults.** Caller mistakes may return it. Unexpected inability to record the runner's own work must be stored and delivered through the core fault owner, with configured panic policy, as finding 3 specifies.

**4. One digest owner, not universally identical input.** The hash computation is correctly shared through `definition-digest-of`. The new set-only merge is a second producer of the purported final declaration value; it is not equivalent to Datahike for all allowed rows. Finding 4 gives the concrete limits and existing replacement seam.

## Original ingestion P1s

| Earlier finding | Assessment of the requested fixes |
|---|---|
| P1-1 closed authored admission | **Resolved in source.** `src/seon/issue.clj:590` derives optional set members with Malli `children`/`deref-all`; only exact `#{}` at those members disappears. `:593` explains the complete remaining entry, and `:594` retains the authored value. Unknown empty values and wrong collection types no longer disappear. Malli owns validation, not a new key roster. |
| P1-2 adoption revision and deletion | **Resolved for the stated publication-history boundary.** `issue.clj:692` captures the held destination basis; `:696` passes it to `adopt-tasks-tx`, which uses the same `task-delta` at `:664`. Its writer compares against current declarations at `:630`. Source history at `:674`, selected at `:690`, distinguishes source-retracted handles from tasks the source never held. Publication supplies the previous published database's basis at `cluster/source.clj:540`–`:547`. This does not establish an editor's unrecorded file-read revision or per-destination import provenance; the landing correctly retains the optional-basis limitation. |
| P1-3 destination dangling tasks | **Resolved in source for the installed function/test replacement merge.** `issue.clj:766`–`:770` takes touched candidate edges and untouched destination edges, against destination task handles. `cluster/source.clj:864` computes this on the held head; `:877` refuses missing handles/holders; `:879` writes with the held expected basis. Tasks do not enter the program replacement. |
| `b5c7d2765` regressions | Source-write custody and asserted task admission are improvements. Comparing task state rather than assuming all refusals leave the global basis unchanged is appropriate. The merge fixture's own namespace is valid. Installation and a completed green run remain separate evidence. |

The changes reuse `task-delta`, Datahike history, and the existing guarded merge. There is no reason to redesign task reconciliation or reintroduce task rows into program merge. Adoption's history query walks the retained handle history; `candidate-dangling` reads the two populations' task edges and destination handles, approximately O(candidate edges + destination edges + tasks). These are not changed-row-only algorithms. Keep their measured costs visible rather than adding a cache without evidence.

## Evidence, cost and limits

Pinned dependencies read: Datahike `4157916b5a1b8d80d7f809c889bce1b098b1d75d`, Malli `befcd302a8e626a67d84b80aabee0cd1b38dadb6`, Clojure `3bc2b3e91fdff462620d84bbdfd40379d9c562e5`. Relevant seams are Malli `children` (`core.cljc:2606`), `explain` (`:2670`) and `deref-all` (`:2837`); Datahike map expansion/nested-entity upsert (`db/transaction.cljc:719`, `:740`, `:947`); and Clojure's restored thread binding (`core.clj:1968`). These source guarantees do not establish loaded revisions.

I inspected retained root output: `tmp/orchestrator/verify-1840.out.d/003.txt` reports the candidate write refusal, run `f00ec814f8fa`, 5,496 ms; `verify-1845.out.d/003.txt` and `004.txt` report the subsequent `seon.test/run` output-contract errors, runs `af3b18b9b1cc` and `2227fae42848`, 14,093 and 8,214 ms. They establish the earlier failures, not a post-fix pass. The landing reports scratch gate run `55cde0c70fa9`, one executed/pass member in 4,788 ms, total 5.6 s, and merge acceptance 1.8 s. I did not reproduce those measurements or independently observe their installed schema/arming.

The [nested-run issue](../../seon/issues/a-nested-test-run-costs-four-seconds.md) remains open. A 15,000 ms test bound (`source_test.clj:309`) describes allowance, not a performance fix; the 120,000 ms two-prepare bound (`:346`) is not a measurement of that regression. Root still owes focused installed proof and the existing performance follow-up. The acquisition-refusal path is explicitly unexercised in the landing. No parent/change timings or memory measurements were produced by this read-only review; memory evidence remains unavailable, not passing.

Actual requested diff sizes: the four ingestion commits total source +73/−31 (net +42), tests +130/−36 (net +94). `c1e40b7e8` itself is source +18/−14 (net +4), tests +37/−0; the landing's +32/−14 is not that commit's diff count. The three helper commits are test support +24/−9 (net +15). These are summed per-commit edits, including subsequent edits to earlier additions. This lane adds only this review: source/test delta zero.

Two skill claims need their owners' correction, outside this one-file grant: the testing skill says host bodies inherit no custody, contradicted by `runner.clj:388`; it says `run` is not an automatic arming operation without distinguishing its first-load arming at `test.clj:2030`–`:2059`, invoked at `:2139`. The REPL skill's older line references and installed/target claims were not used as proof of current behavior.

No foreign breakage blocked this static review. The pre-existing dirty `reference-code/http-kit` submodule and other lanes' arriving edits were left untouched. This file is released after its documentation-only commit; root owns follow-up assignments, the issue index and runtime verification.
