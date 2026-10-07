# Batch B review — review-astra-batch-b

**Request changes.** Four tooling defects are reproduced in disposable Git repositories; two context defects are reproduced through their installed dependencies in an agent's SCI context. The context producers themselves are **not installed on default**, so their armed execution and publication are unverified. This is the independent Astra review, not a claim that both reviewers approved.

Reviewed commits: `1758624f4`, `90fd4919e`, `c362de9d7`, `8e42e0889`, `9a33f54f4`. Checkout HEAD at review: `c362de9d7e29cb1700aa0d95071917333175593f`; the reviewed owner files were clean. Authority: assignment's owner rulings, `docs/seon/specs/agent-loop.md` §3, lane-tool notes and context-examples README. The explicit owner ruling makes system message plus task-generated context the primary mechanisms; the README's suggestion that this is still an open decision is superseded. No provider call was made, no examples were sent to a model, and no test runner was started.

## Ranked findings

### 1. P1 — a docs-prefixed path can commit source with both gates skipped

**Locations:** `bin/lane:159`, `bin/lane:117-128`, `bin/lane:176-190`, `bin/lane:237-252`.

Classification uses the caller's string, with only one leading `./` removed. Git normalizes paths differently. In an independent repository, claim and commit `docs/../src/a.clj` after changing `src/a.clj`. Both the lint and publication checks are skipped. Git commits `src/a.clj`; only afterward does the path assertion exit 3. The unchecked code is already HEAD. This is also a spelling-based ownership bypass because claims use the same unnormalized string.

Observed: `checked=false`, `linted=false`, committed source `non-doc change`, exit **3**. The post-commit mismatch warning does not prevent the defect.

**Fix:** resolve requested paths to canonical repository-relative literal paths before ownership and classification; expand directories to the candidate's actual changed paths, including deletions. Derive gate inputs from that set and `seon.test.cache/input-roots` (`src/seon/test/cache.clj:129-140`). Refuse unsupported pathspec syntax before committing, or expand it once and use the resulting literal paths everywhere. Apply ownership to that same set. Regression: this spelling must either be refused before HEAD moves or receive the source checks.

### 2. P1 — the committed bytes need not be the candidate that passed

**Locations:** `bin/lane:91-96`, `bin/lane:176`, `bin/lane:190`, `bin/lane:206-209`, `bin/lane:218-240`.

Lint snapshots one tree; `bin/seon check` independently snapshots working files; the locked commit reads them again. Neither the candidate tree nor its base HEAD reaches `commit_locked`. Its parent check compares against HEAD acquired *after* the checks, and its byte check compares the commit with the *current* working tree. Consequently both assertions pass when unverified bytes replace the checked bytes, or another lane advances HEAD while the checks run.

Reproduced with the unchanged lane script and a check-boundary stand-in that changes the owned file after candidate creation: archive recorder reads `checked`, final commit reads `unchecked`, exit **0**. The stand-in deliberately does not claim to be publication or lint proof; it makes the race deterministic. This is a snapshot/commit defect independent of either checker.

**Fix:** capture one base and candidate tree, pass that exact candidate to both checks, and under the lock compare current HEAD and selected file identities with those proven inputs. If either changed, release and recheck before moving HEAD. Commit the proven tree/path contents. Keep the existing post-commit assertions as diagnostics, not as the authority establishing what was tested.

### 3. P1 — killing the lock holder releases exclusion before its commit child exits

**Locations:** `bin/lane:173`, `bin/lane:206-209`, `bin/lane:214-229`.

The Perl process owns the flock and waits through `system`; its child shell and Git do not keep that descriptor through exec. A TERM to that Perl PID ends the lock lifetime without terminating or joining the child. The outer shell then removes the shared scratch message while Git may still need it.

Reproduced in an owned scratch repository with Git paused in a pre-commit hook. After TERM to the verified Perl parent, a second process acquired `LOCK_EX|LOCK_NB` while the first Git PID remained alive. Outer exit **143**. Releasing the hook then produced `could not read log file .../message: No such file or directory`. This reopens exactly the concurrent commit interval the change intends to serialize.

**Fix:** make lock ownership survive for the full child lifetime (including signal death of the supervisor), and forward cancellation and await actual exit before deleting scratch resources. A caught-signal handler alone does not cover supervisor SIGKILL. Ensure cleanup of newly staged files belongs to the locked child's failure lifecycle too. Prove exclusion with a second process while the first child is held, then prove release after its actual exit. This review did not signal the live Seon JVM or any other lane.

### 4. P1 — context production bypasses the one printer and removes its bounds

**Locations:** `src/seon/agent/loop.clj:31-36`, `:53-55`, `:176-197`; `resources/seon/schemas/seon.agent.loop.edn:1-7`.

`unit-text` returns any string value directly at line 182. An arbitrarily large source string therefore never enters the value printer. Every other value is marked `print/full`. That is explicitly unbounded: `src/seon/print.cljc:244-260`; `src/seon/render/value.clj:1295-1301` replaces token, child, depth and string bounds with `Long/MAX_VALUE`. A lazy unbounded value is also admitted by the unit's generic value schema. Eager task queries and concatenation provide no independent output/work bound.

The form is printed by `repl/source-text`, which uses `pprint/write` with unlimited length/depth (`src/seon/repl.clj:103-109`). System-message tool symbols and namespace symbols are converted with `str` at lines 33 and 36. Finally, `render-ai` can return an error map (`src/seon/render/value.clj:1468-1473`), which the enclosing `str` would print as a value outside the printer instead of preserving a diagnostic.

**Fix:** preserve form/value/diagnostic units as data and pass every shown value, including strings and forms, through the existing value-printer face with an explicit profile and usable requery identity. Remove automatic `full`. Bound discovery work separately; select whole context units under the total context budget, and report mandatory-context overflow as data. Do not repair this with substring truncation or another printer. Integration, renderer-refusal behavior and large-value timing remain **unverified** because these functions are absent from the running archive; the direct bypass and removed bounds are established by source.

### 5. P2 — directory commits silently omit new files from the checked candidate

**Locations:** `bin/lane:91-96`, `bin/lane:221-225`, `bin/lane:239-240`.

Directory claims are supported. The temporary index's `git add -A -- docs/` includes an untracked `docs/new.md`, but the locked path pre-add asks whether *any* tracked file matches `docs/`. Once a tracked file exists, it skips the add. `git commit --only docs/` then omits the new file; the post-check's `git diff` also ignores untracked files.

Reproduced: change tracked `docs/a.md`, add `docs/new.md`, commit the owned `docs/` directory. Exit **0**; HEAD contains only `docs/a.md`; `docs/new.md` remains untracked. This invalidates the stated candidate equivalence even without concurrency.

**Fix:** expand to the complete candidate delta before the lock, register every new path under the lock, and compare candidate and landed path/blob sets. Use the same expansion in `staged_only`, which currently also rejects a staged child of a requested directory (`bin/lane:139-146`). Regression: mixed tracked/untracked directory changes plus a deletion.

### 6. P2 — generated related-task/error requeries evaluate symbols as functions

**Locations:** `src/seon/agent/loop.clj:128-141`, especially `:139` and `:141`.

The producer executes the query with a vector of symbol *values*, but inserts that vector into executable code without quoting it. A rendered `[seon.db/q]` evaluates to a function object, not the symbol stored in `:seon.task/must-read`. Both generated query forms have this bug.

Exact SCI probe on the review agent's disposable branch:

```clojure
(seon.db/q
 '[:find [(pull ?t [:seon.task/id :seon.task/name]) ...]
   :in $ [?f ...] ?h
   :where [?t :seon.task/must-read ?f] [?t :seon.task/id ?id]
          [(not= ?id ?h)]]
 (seon.db/db) [seon.db/q] "d6e3b775f474")
```

It returns an installed attribute-type refusal: the received value is an `AFunction`, while `:seon.task/must-read` requires `:db.type/symbol` (3 ms). The same call with `'[seon.db/q]` returns the three related tasks `b0251f09eba3`, `126f25106b63`, `2a6d6dd6c0c8` (2 ms).

**Fix:** quote `functions` in each generated form, just as `test-units` quotes the proof symbol. Test by evaluating every offered form in the same SCI world and comparing the returned data with its unit value; checking the form's appearance does not establish this property.

### 7. P2 — namespace subjects violate the context helpers' contracts

**Locations:** `src/seon/agent/loop.clj:72`, `:107`, `:125`, `:164-172`; `src/seon/task.clj:240-256`; `resources/seon/schemas/seon.task.edn:126-127`.

`task-edges` intentionally returns function **and namespace** subjects as `:symbol`. `task-context` merges them into `functions`, then calls `contract-keys` and `function-units`, whose contracts require qualified symbols. An actual SCI read, `(seon.task/task-edges (seon.db/db) "6a6e13871530")`, returned `{:seon.task/subjects #{my.edit}, :seon.task/proofs #{}, :seon.task/referrers #{}}` in 8 ms. `my.edit` is a namespace symbol, not a qualified function symbol. Armed execution must reject this call; unarmed execution instead looks up a nonexistent function/source.

**Fix:** preserve subject kind and dispatch namespace documentation/directory reads separately from function/test declaration reads. Do not narrow the legitimate upstream subject schema. Add a namespace-subject row to the context regression and prove the offered reads in SCI. The mismatch and real input are verified; the new producer's armed failure is **unverified** until publication/adoption. An attempted bare `malli.core/validate` in SCI was itself unresolved; it is not claimed as validation evidence.

### 8. P2 — the owner preview omits the changed protocol text

**Locations:** `docs/research/agent-platform/2026-10-07/context-examples/system-message.md:1-13`; `src/seon/bootstrap.clj:51`, `:56-57`, `:61`; context-examples `README.md:3-8`, `:24-27`.

The saved example still has the old help lines: it lacks the Markdown instruction, the qualified apropos/source discovery instruction, and the named-test example added in this batch. The later toolkit section does contain `my.test/check`, but only its older function-reaching example. The README explains the old live JVM and unarmed scratch load; loading `loop.clj` alone did not replace its `bootstrap/help-value` dependency. Thus this is not a preview of the complete committed change the owner is supposed to read before sending anything.

**Fix:** after root publishes/adopts the complete producer/schema/help slice, regenerate the examples from that exact program in disposable custody, record its program identity, and present those examples to the owner. No provider call is needed. Preserve the caveat until then; do not describe the present examples as installation proof. The help text change is directionally correct for the verified qualified discovery APIs. Execution of named tests was deliberately not attempted under the no-test-run instruction.

### 9. P2 — copying every documented example does not make it executable agent guidance

**Locations:** `src/seon/agent/loop.clj:26-36`; saved `system-message.md:109-110`, `:184-186`.

`tool-lines` promises the call an agent copies, but includes fixture-dependent and multi-step examples as standalone calls. `(my.web/fetch {:my.web/url example-url})` fails SCI analysis with `Unable to resolve symbol: example-url`; `(def accepted (my.program/accept-merge! proposal))` likewise fails on `proposal` (both 1 ms). No network or merge effect occurred. The public-example test creates an `example-url` fixture (`test/my/examples_test.clj:43-55`), so green there would not prove that these system-message examples work in a new agent's context.

**Fix:** make the owning documentation state prerequisites and runnable discovery/setup operations as data, and have the producer carry that structure. Do not invent replacement samples in a second hand-maintained tool catalog. Exclude fixture-only/internal helpers by declaration at their owner. Verification must execute the displayed operations in the actual intended SCI world, with any required external fixture explicitly supplied.

## Positive evidence and limits

- `/bin/bash` is **3.2.57**. Syntax checking passed and all scratch lane probes above used `/bin/bash`. No worktree was created. The archive path uses a temporary Git index and `git archive` as claimed.
- An explicit rename listing **both** old and new file names passed: the recorded lint archive and committed source tree each contained only `src/new.clj`. Ordinary deletion is represented by the same temporary-index `git add -A` path; this does not cure findings 2 or 5.
- A refusing pre-commit hook on a new file returned **2**, restored an empty staged diff, and allowed a subsequent nonblocking flock. Normal exit/refusal releases the OS lock. Signal safety is not established by that happy cleanup path. INT/HUP, process-group cancellation, staged-index refusal after waiting, and repeated concurrent commits were **not separately exercised**; the original notes' 45-commit trial is author evidence, not this review's reproduction.
- All new producer `defn`s, including private helpers, have contract metadata. `seon.contracts-compile-test/check` over archived `src/seon/agent/loop.clj`, `src/seon/bootstrap.clj` and `test/seon/agent/loop_test.clj`, against the archived packaged schemas and pre-change baselines, returned **[]**, **166.0 ms** warm. It invokes Malli's `function-schema`, whose pinned implementation is `reference-code/malli/src/malli/core.cljc:3130-3134`, revision `43c6dca8d2d8df3162b13725939954807be4a4ed`. Compilation does not prove caller/callee type compatibility (finding 7), finite values, or executable requeries.
- The producer call graph passes a held database value and contains no explicit writes, connection acquisition, provider call or evaluation of offered forms. It is read-only in this sense; it does not impose read-only *agents*. End-to-end read-evidence capture and purity under armed execution remain **unverified**.
- The task context follows the real `task-edges` owner and reads proof/source/schema facts, rather than inventing task handles. However a missing task can yield a nil task unit, and a test's latest recorded result alone is not current positive evidence for changed code. The proof-completes-task narrative in `test-units:94` needs the current evidence owner at integration; no completion API was added by this batch. The README already acknowledges green proofs that do not establish task intent and absent completion support.

## Live SCI verification ledger

`bin/seon status` and MCP `runtime_status` both answered. Default runs archived `a8e3bde5b475e54fead8ed107a86d67d47d10a13`, PID 30874, start instant `2026-10-06T11:15:24.710Z`; state **faulted**, 14 open faults, 105 failed tests and 43 unknown tests before the probes. Heap-live observation: **7,151,679,416 bytes**, max **10,997,465,088 bytes**. These are existing runtime observations, not defects attributed to this batch.

MCP used explicit `cluster="default"`, `agent="review-astra-batch-b"`, `mode="sci"`. Identity and branch calls confirmed private branch `:agent-84f493166ef2`. All three new producer symbols resolved **false**. No restart, shared reload or raw JVM substitution was represented as installed SCI proof.

| Claim | Observation |
|---|---|
| Qualified discovery works | `clojure.repl/apropos "steps"` returned five names, 6 ms; `my.program/source 'my.task/steps` returned `:sym`, `:kind`, `:source`, `:contract`, location, 1–2 ms; `doc my.task/plan!` returned summary/example, 7 ms; `dir my.task` returned seven public functions, 183 ms. |
| Task writes are available to an agent | `my.task/plan!` created/claimed task `6c545ce1b53d` on this branch, 100 ms; `my.task/done! {}` completed its step, 50 ms. Initial `steps` refusal correctly said to plan first, 3 ms. |
| Agent/session APIs | Identity, branch, branches, settings and inbox answered; `seon.turn/turns-left` returned 100, 9 ms. `my.turn/wait` answered wait data; `my.turn/complete` as the last form returned completed data, 0 ms. An earlier deliberately non-last complete probe refused; that is expected protocol behavior, not a batch defect. |
| File/shell APIs | Displayed `my.fs/stat`, `my.fs/glob`, bounded `my.fs/read` and `my.shell/run!` printf examples answered successfully, 277/232/92/440 ms respectively. File reads came from the running archive. No arbitrary shared file was changed. |
| Program reads | `my.program/diff` returned empty added/changed/removed vectors, 497 ms; `declaration-diff 'my.program/source` returned equal before/after source, 151 ms. |
| Proof result | `recorded-result` on the query-wrapper proof returned “No admitted execution is recorded for this test” on this fresh branch, 1–11 ms; default's older green cannot stand in for branch evidence. |
| Requeries | Unquoted-symbol query refused; quoted version returned three related task identities. Real namespace subject `my.edit` was observed. See findings 6–7. |
| Toolkit call availability | All 47 probed public operation symbols across background/edit/fs/message/program/shell/task/test/turn/web resolved in SCI, including the hidden tail read back through its result handle. This is **resolution only**, not execution proof. |
| Explicitly unverified executions | `my.test/check`, `run`, `run-owned` and merge/proposal paths that run tests: prohibited here by the central-test instruction. Message send/decline/ack: no messages to others were authorized. HTTP/search/provider effects, shared write-back and destructive namespace operations: not executed. File edits/background completion are not proven by the read/shell samples. |

No successful measured ordinary SCI probe exceeded one second. Expected bad-requery and unresolved-example envelopes were read, not treated as successful values. The unqualified Malli probe was also an error and gives no contract evidence. The schema compilation result above comes from the explicitly named JVM checker surface.

## Tests for root and verification gaps

The two new tests are insufficient for the failures above. `the-system-message-carries-help-and-every-toolkit-namespace` asserts string fragments (`test/seon/agent/loop_test.clj:24-32`); it is neither declared as a golden transcript nor proof that examples execute. `a-task-context-follows-the-tasks-refs` creates only a must-read function, checks its summary/source type and the presence of some schema (`:34-47`); it has no namespace subject, proof/failure result, related-query execution, bound, printer-refusal or missing-task case. There is no `context-text` behavior regression.

Root's exact existing focused list:

- `seon.agent.loop-test/the-system-message-carries-help-and-every-toolkit-namespace`
- `seon.agent.loop-test/a-task-context-follows-the-tasks-refs`
- `seon.help-test/an-agent-identity-offers-help-as-its-operation`
- `seon.help-test/help-evidence-tracks-its-code-version` — author notes already report a baseline red; classify its stored failure before attribution.
- `my.examples-test/public-docstring-examples-run-in-the-canonical-agent-context` — external-effects fixture, declared long bound; the `examples-world` lane owns in-flight changes to this file. A green does not establish standalone system-message examples without its bindings.

Required owner regressions (not existing tests claimed to be installed): offered-form/data equivalence in SCI; a namespace subject; current/missing/red proof evidence; a large string and a bounded-prefix lazy value through the printer; printer failure retained as data. Shell regressions should preserve the six scratch cases documented here, plus a two-process positive exclusion case and HEAD advancement between check and commit. Root runs these after the owners implement the fixes.

Archive lint: `clj-kondo --lint src script test --cache false --parallel`, **0 errors, 1,555 warnings**, **11,304 ms**, process exit 2 because warnings remain. This is not a zero-warning result. The required full-population lint is linear in the complete source/test tree; the existing lane-tool notes document its cross-lane-load cost. It is not an ordinary sub-second producer benchmark. No warning was suppressed. Candidate equivalence and final landing details are recorded below.

## Landing boundary

Only this review is a tracked change; source/test delta **0/0**. Foreign dirty owners include `script/seon/operator.clj`, `src/seon/{db,cluster,error,program,test,turn}.clj` and related schema/SCI/render files, plus `test/my/examples_test.clj`. They were not edited, resumed or messaged. The review used committed source in an archive for checks, the old installed program for SCI probes, and separate scratch Git repositories for shell effects. No foreign breakage stopped the work.

The REPL skill is stale: it describes a separate MCP `branch` tool and `eval_clj branch=...`, whereas the installed interface uses an agent argument and `my.agent/*` branch operations. This review used the installed interface. Skill repair is outside this file's ownership.

Pre-landing candidate check: a separate temporary index built the reviewed HEAD plus this report, tree `1b9c0587d39960fd4b7e9918a3b0e6376764f9c1`. Its only delta was this Markdown file; its source, test, script and packaged schema inputs equal the first archive. Lint of that archive returned **0 errors, 1,555 warnings in 11,066 ms**. During landing, HEAD advanced through another lane's `a420a03e3` reach-runner changes and documentation commits. The first review commit is `be2ed80e6`. A fresh full archive of that landed commit was therefore linted again: **0 errors, 1,555 warnings in 9,485 ms**. The reviewed producer/contract/schema files are unchanged across that boundary; the earlier contract check remains applicable. This verification-note-only follow-up changes none of the checked inputs.

Cleanup: `(my.agent/done)` returned `{:my.turn/disposition :wait, :my.turn/note "Session complete."}` in 0 ms. A subsequent own-agent retirement request was refused as `admission-closed`, so root's SCI entrance called the public retirement owner for this review's branch only. It returned `{:seon.store/branch :agent-84f493166ef2, :my.agent/retired? true}` in **64 ms**. No other agent was resumed or messaged. Probe scripts and logs remain under `tmp/review-astra-batch-b/` as supplementary local evidence; the concrete failures, inputs and results are preserved in this committed review.
