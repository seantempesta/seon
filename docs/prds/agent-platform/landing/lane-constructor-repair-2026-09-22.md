---
type: landing
status: assigned repairs landed; canonical fixture and publication proof limits recorded
created: 2026-09-22
---

# Constructor review repair

The initial handoff below is historical. The resumed landing section records the
current ownership, commits, and verification after the shared lanes exited.

Scope: README §4 step 1.1, review F1/F2/F6–F12/F14, under §7's
explicit named-schema and retained-observation rulings. F3–F5/F13 remain
with the error-schema owner. No `:seon.error/source` write site was edited.

## Evidence boundary

`bin/seon status` and MCP `runtime_status` observed default PID 38968,
start 2026-09-22T12:04:10Z, all procs replying, no missing readiness layers,
15 errored receipts. Hook publication remains paused. No adoption, reload,
reset, or lifecycle action was performed. These repairs are NOT live on default.

The first JVM probe demonstrated that default still runs the pre-constructor
implementation: `diff-refusal` retains `diagnostic-cause`, and the digest-only
adoption refusal returns no phase. Therefore subsequent read-only JVM probes
compile the named disk body into a local anonymous function, without interning
or replacing any Var. For database constructors the local lexical `diagnostic`
adds the timestamp/layer; it does not invoke the obsolete live constructor.
Other calls use the existing live dependencies. This proves the edited body,
not publication, arming, or adoption of the edited program.

Exact full form and returned envelopes: `tmp/constructor-repair/repl-probe.clj`,
`repl-before.json`, `repl-after.json`. The before bundle took 155 ms, the after
bundle 188 ms. An initial direct disk-body probe failed at the obsolete live
`seon.db/diagnostic`; a subsequent probe draft had an unmatched delimiter.
Neither is counted as a pass. Explicit custody throughout is
`(seon.cluster.boot/connection "default")`, database `@connection`, projection
`(seon.schema/projection-from-database database)`, under
`seon.schema/call-with-projection`. Every MCP call used mode `jvm`,
`read_only true`, root `/Users/sean/src/seon`, cluster `default`.

Dependency seam: Malli `606083c5c5b388e84d169c7080af33ed3ec242ae`,
`reference-code/malli/src/malli/core.cljc:2626`, owns cached validators for
compiled schemas. The supplied projection/declared schema is the input;
compilation changes with that schema, validation follows the value. No cache
or classifier was added. Datahike `cc2b2bc7dbe774ea1bcc7487e8225a0d420d0e17`,
`reference-code/datahike/src/datahike/db/transaction.cljc:1206`, owns final-report
validation and refuses non-nil results. `seon.db/write-owned-values-error`
consumes before/after values and attempted/effective datoms, traversing the
changed owning graph under the existing validation bound. These repairs add
scalar observations without changing that work or allocating a new index.

## Findings and exact small forms

In the forms below `disk-fn` is the local source-body reader in the saved probe;
`projection` and `database` have the explicit custody described above.

| Finding | Reproducer, repair, assertion | Commit |
|---|---|---|
| F1 | `((disk-fn 'seon.db "src/seon/db.clj" 'diff-refusal) "probe" :seon.fn/sym :seon.fn/fn 'probe/f :seon.db/function-not-indexed {})` omitted the cause. Add `:seon.db/diff-refusal`, a nine-value closed enum on `:seon.db/diff-refused-error` in `seon.db.edn`; the private constructor now declares that input and output. Restore row-identity-absent, database-input-absent, external-sink-reachable assertions; add constructor preservation/closed-enum coverage. The review counts ten calls; current source has nine calls and nine distinct dispositions. | Pending shared schema resource |
| F2 | `((disk-fn 'seon.db "src/seon/db.clj" 'write-owned-values-error) (dissoc projection :seon.config.db/validation-node-limit) {:db-before database :db-after database :tx-data []} {} #{} (volatile! #{}))` retained the bound cause only in prose. Add `:seon.db/owned-value-refusal` on the owning transaction refusal in `seon.db.edn`, including the five component causes and the two existing bound causes. Restore the unowned-entity assertion in owned `db_test.clj`, with unchanged basis. A read-only report over a real provider row with no admitted identity attributes also returned `:seon.db/unowned-entity` after repair (162 ms bundle); it made no transaction. | Pending shared schema resource |
| F6 | `((disk-fn 'seon.schema "src/seon/schema.clj" 'render-contract-refusal!) {:seon.schema/key :seon.agent/id :seon.render/property :seon.render/ai :seon.render/function 'probe/render} {:seon.schema/render-contract nil :seon.schema/render-input nil :seon.schema/render-contract-cause :seon.schema/render-function-has-no-declared-contract})` said input nil did not accept the shape. Retain the closed two-value cause on the owning `:seon.schema/validation-refusal` in `seon.schema.edn`; branch the message. The owned cluster test checks both causes, messages, and the named schema. | Pending shared source/resource |
| F7 | For `failure {:seon.db/attribute :seon.agent/id :seon.schema/form :seon.error/unknown}`, `(not (get (:seon.schema.projection/forms projection) (:seon.db/attribute failure)))` is false but `(= :seon.error/unknown (:seon.schema/form failure))` is true. Use the declared form check. Restore the temporal uninstalled-declaration assertion and add a write regression using a canonical immutable database with `:seon.message/content` removed from its schema and a row containing id/content. The current body returns unknown plus candidate `:seon.message/to` (166 ms bundle). | Pending database group |
| F8 | `((disk-fn 'seon.cluster "src/seon/cluster.clj" 'source-change-phase) (ex-info "changed" {:seon.boot/offense {:seon.source/digest-before "c0"}}))` returns `:adoption`; the pre-constructor live Var returns nil. **The constructor cut changed behavior:** digest-change publication refusals now receive one retry. Add the missing `(is (= 2 @attempts))` after the adoption case. Phase-member producer edits are pending clarification of the explicitly restricted file/region ownership (analysis producer is `src/seon/fn.clj`, digest producer is outside `source-change-phase`). | Assertion pending cluster-test group |
| F9 | `(mapv #(seon.cluster.boot/diagnostic "probe" {} %) [:refused :boot-failed :client-failed :argv-failed])` showed old retained causes; the disk constructor discarded them. Add the owning closed dispositions to new `seon.cluster.boot.edn` and existing `seon.operator.edn`; the actual boot callers also include non-edn-response and operation-failed. The disk operator diagnostic now preserves refused/client-failed/argv-failed. Delete reply's unused first argument and convert all four calls; local `(refused {:seon.cluster.reply/no-forms true} "probe" {:seon.cluster.reply/text ""})` preserves the marker. Boot assertions cover its four actual dispositions and named schema. | `59e9642b1`; boot held |
| F10 | `(count (filter #(clojure.string/includes? % ":seon.error/base)") (clojure.string/split-lines (slurp "test/seon/db_test.clj"))))` found three weakened assertions. Replace them with the requested `:seon.db/error-result` / `:seon.db.read/error` checks and retain specific member/cause checks. No production union was widened to satisfy these assertions. | Pending database group |
| F11 | `((disk-fn 'seon.await "src/seon/await.clj" 'diagnostic) {:seon.await/bound {:seon.await/config-attribute :seon.config.eval/time-limit-ms :seon.await/config-value 1} :seon.await/diagnostic {:seon.error/layer :runtime :seon.error/operation 'probe/await}} {:seon.await/elapsed-ms 1.0})` has elapsed time and no closed-operation. Restore explicit nil assertion for both future and promise expiry. | `59e9642b1` |
| F12 | Read-only form counted one `seon.error.refusal` occurrence in each of the seven named files; source search found no uses. Remove exactly those requires. | Issue/turn: `59e9642b1`; other five held |
| F14 | `(= {:probe 1} (merge {:probe 1} {}))` is true. Remove the empty merge at the namespace-removal refusal. | Pending shared program source |

## Verification and foreign boundaries

- Initial working-tree prescribed four-namespace require exited 0:
  `tmp/constructor-repair/working-load.log`.
- `bin/test-fast --paths <owned repair files> -- seon.db-test seon.await-test seon.cluster-test`
  stopped at `cluster_test.clj:159`, an unchanged `error/properties` caller
  whose Var HEAD has removed. `test-fast-1.log`; no test verdict.
- Narrowed `bin/test-fast --paths src/seon/db.clj resources/seon/schemas/seon.db.edn test/seon/db_test.clj test/seon/await_test.clj -- seon.db-test seon.await-test`:
  run `4268c7f14f61`, 71 tests, 133 assertions, 13 failures, 61 errors,
  completion recording refused. The canonical fixture explicitly refused the
  stale `seon.error/facet-counts-agree?` predicate; the new write regressions
  never reached their bodies. No fixture rebuild was attempted. All five await
  tests completed without fail/error events. `test-fast-2.log`.
- A selected in-process diagnostic encountered concurrent
  `:seon.error/normalization-error` storage-admission failure. A later shared-tree
  load failed at `seon/effect.clj:558` (`error/declared-output-validators`).
  These are outside this repair. No foreign producer or schema was changed.
- Authorized fallback: detached `tmp/constructor-repair-wt` at `8595434d4`,
  linked existing dependencies, applied only `tmp/constructor-repair/owned.patch`.
  Isolated selected assertions are diagnostic evidence, not a recorded test green.
- No publication-path timing is claimed. The committed measurement script starts
  and adopts a separate operator root; cold/platform/publication proof remains
  with the orchestrator under this assignment. No phase producer has yet changed.

Shared-file rule: before any commit, inspect `git diff -- src/seon/cluster.clj
src/seon/cluster/boot.clj`. Both currently contain search-deletion hunks; neither
will be committed by this lane while those remain. Other shared files likewise
retain their foreign hunks. The two deliberately dirty documents are untouched.

## Landed cleanup and final diagnostic evidence

`59e9642b1` lands the operator disposition schema/caller, reply arity conversion,
F11 expiry assertion, and F12's issue/turn require removals, with this note:
7 files, 114 insertions, 10 deletions (including the initial note).
Its clean archived HEAD passed exactly
`clojure -M -e "(require 'seon.db 'seon.schema 'seon.cluster 'seon.cluster.boot)"`,
exit 0: `tmp/constructor-repair/head-59e9642b1-load.log`.

The isolated HEAD-plus-owned-hunks diagnostic completed with **5 tests,
53 passing assertions, 0 failures, 0 errors**. It initialized the ordinary
contract owner, then ran selected existing/new assertions for F1, F6, F8,
F9 boot, and F11. Only the owned cluster forms were evaluated, bypassing the
unrelated `error/properties` test caller; no fixture implementation was replaced.
This is in-process diagnostic evidence, not admission/recording success.
Exact driver: `tmp/constructor-repair/in-process.clj`; log:
`tmp/constructor-repair/in-process-isolated-2.log`. An earlier attempt accidentally
ran from the shared cwd and hit the foreign effect caller; only the run with
explicit workdir `tmp/constructor-repair-wt` is the isolated evidence.

Seven further assertions passed in 163 ms on the running JVM using the local
source bodies: F1 preserved cause, F2 unowned-entity, F7 unknown form and missing
key candidate, F6 missing-contract cause, F9 boot disposition and reply marker.
Exact form: `tmp/constructor-repair/repl-assertions.clj`; fuller observation
bundle: `tmp/constructor-repair/repl-detail.json`. They are read-only algorithm
probes over the real immutable database, not transaction/adoption proof.

Changed owned hunks pass `git diff --check`. Lint initially reported two
unresolved Vars: the dependency constructor `parser.type/->Variable` and the
unchanged cluster test's removed `error/properties`. Dependency cache refresh is
recorded separately; the removed error Var remains a foreign test-load boundary.

The remaining coherent groups cannot be committed while their schema/source
files contain foreign hunks. In particular the uncommitted database source and
assertions must land with the owned additions in `seon.db.edn`; that resource
also has the error-schema and search-deletion lanes' union changes. Similarly
`schema.clj` and `seon.schema.edn` have search-deletion hunks; `my/program.clj`
has an error-schema observation conversion. Boot still has search proc-removal
hunks. No such foreign hunk was staged or committed by this lane.

The isolated arming receipt reported 1,690 registered/instrumented Vars,
1,684 program-armable Vars, 108 program namespaces, mode `:panic`.
Dependency cache refresh completed with the publication classpath and exit 0;
`parser.type/->Variable` still receives the static warning, but the actual
`datalog.parser.type/->Variable` resolves and returns a Variable for `?probe`
at the JVM REPL (1 ms). An initial probe guessed the wrong namespace and failed;
the source require supplied the corrected namespace. No correct reference was
rewritten. `error/properties` is still the unrelated removed-Var caller.

Final ownership check still shows foreign hunks in both cluster files and the
shared domain resources. Under the assignment's stop boundary, the remaining
coherent groups stay uncommitted. F8's phase-producer edits have not been made:
the pending ownership question concerns `src/seon/fn.clj` and the digest producer
outside the assigned `source-change-phase` region. The retry assertion itself
is written and passed. Summary: `tmp/orchestrator/constructor-repair-summary.txt`.
All owned test/load/cache-refresh shells exited. The owned worktree and archived
HEAD copy are removed only after exact-path process-holder checks and after
unlinking their dependency symlinks; logs and the owned patch are retained.

## Resumed landing after shared files became free

The owner's F8 ruling now explicitly authorizes both producer sites and names
`:seon.cluster.source/phase`, a closed `[:enum :analysis :adoption]` in
`resources/seon/schemas/seon.cluster.source.edn`. No ownership wait remains.

Verified provenance corrects the incoming report: `434c01f4c` DID carry our
prepared `seon.db.edn` declarations, `seon.schema.edn` render cause,
`schema.clj` render fix/dead require, and `cluster/boot.clj` disposition plus
new `seon.cluster.boot.edn`. Its commit message enumerates those hunks. These
paths have no remaining diff, so they are not recommitted. The database bodies
and tests, cluster assertions, four remaining requires, and empty merge are the
only prepared dirty code. Unrelated dirty documents remain untouched.

Fresh status: same default PID 38968, no missing readiness layers, 15 errored
receipts and four stale Vars. Publication remains paused; no repair is claimed
live. A fresh read-only JVM form with explicit default connection/projection:

```clojure
{:adoption (#'seon.cluster/source-change-phase
             (ex-info "Source changed"
                      {:seon.boot/offense {:seon.source/digest-before "c0"}}))
 :phase-declared? (boolean
                   (get (:seon.schema.projection/forms projection)
                        :seon.cluster.source/phase))}
;; => {:adoption nil :phase-declared? false}, 274 ms
```

Database finding group F1/F2/F7/F10 lands the already-probed bodies and restored
assertions together. Its resource definitions were carried by `434c01f4c`.
F6 and F9 boot production fixes also belong to `434c01f4c`; their prepared
assertions land with the source-phase group in the one cluster test file.
No test infrastructure owner is edited.

`e2e3b7473` lands the database group (F1/F2/F7/F10), 3 files,
93 insertions / 8 deletions including this note. The prescribed archive load
is `tmp/constructor-repair/head-e2e3b7473-load.log`.

F8 writes the one declared phase at the analysis span producer and the digest
comparison producer. `refused!` carries that explicitly supplied member to the
flat error and removes it from the offense map, so it is not duplicated. The
retry reader reads only `:seon.cluster.source/phase`, and the final repeated
refusal uses the same member. The owning `seon.fn/index-refused-error` and
`seon.boot/refused-error` declare its optional presence; the enum belongs to
`seon.cluster.source.edn`. Digest presence alone no longer selects a retry.

The source-phase regression uses the real span refusal and the boot refusal
producer, checks both phase values, rejects an undeclared enum value, verifies
no nested duplicate, and asserts a digest-change refusal makes exactly two
attempts. The existing historical behavior change remains explicit: the
constructor cut enabled the previously dormant adoption retry; this repair
retains that behavior through an authored member.

Read-only JVM source-body form `tmp/constructor-repair/f8-resume-probe.clj`
passed six assertions in 154 ms: analysis/adoption phases, no digest heuristic,
convergence, two attempts, no nested duplicate. It compiled only local functions;
no live Var or cluster context was replaced. This is algorithm proof, not
publication timing or adoption proof.

The resumed canonical `bin/test-fast --paths ... -- seon.db-test seon.await-test
seon.cluster-test` completed **85 tests / 193 assertions / 13 failures / 71 errors**,
then refused result recording (`tmp/constructor-repair/resume-test-fast.log`).
The stale canonical base now names deleted `seon.search/ping-map-fn?`, not the
previous facet-count predicate. No base rebuild or fixture edit was attempted.
The pure diff-disposition, source-retry, render-contract and boot-disposition
regressions and all await tests have completion events without fail/error events.
The database fixture tests still did not reach their assertions. The repeated
upstream read failures are unchanged db_test.clj:633, outside this repair.

`320c73adc` lands F8 and the retained F6/F9 assertions: 7 files,
95 insertions / 25 deletions including this note. Its prescribed archive-load
log is `tmp/constructor-repair/head-320c73adc-load.log`.
The database archive load `e2e3b7473` exited 0.

The final cleanup group removes F12's remaining four unused require edges
(`render`, `test`, `config`, `sci/eval`) and F14's empty merge. The schema require
was already carried by `434c01f4c`; issue and turn were in `59e9642b1`.
All remaining source diffs were checked: these are only the prepared owned hunks.

F10 follow-up from the final contract inspection: the two `q` assertions must
name **`:seon.db/invalid-read-error`**, which is the actual explicit alternative
in `seon.db/q`'s arity at `src/seon/db.clj:1975`. The review's example
`:seon.db.read/error` describes a recorded read observation with stored target
and basis components, not this API refusal. The initial prepared assertions
used that example too literally. They are corrected to the actual arity schema;
the operation/member/invalid-request assertions remain. No output union changes.

`320c73adc` archive load exited 0. `252e6e5bd` lands F12/F14's remaining
cleanup, 6 files / 15 insertions / 6 deletions including the note, and its
archive load also exited 0 (`head-252e6e5bd-load.log`).

The final selected in-process proof now loads all three complete test namespaces
through the ordinary arming owner, with no selective namespace workaround:
**5 tests / 58 passes / 0 failures / 0 errors**. Arming reported 1,684 registered
and instrumented Vars, 1,678 program-armable Vars, 107 program namespaces,
mode `:panic`. Driver `tmp/constructor-repair/resume-in-process.clj`; log
`resume-in-process.log`. This is in-process assertion evidence, not recorded green.

The F10 read-only JVM query below returned declared-arity-error true and
recorded-read-error false, operation `seon.db/q`, in 140 ms; full envelope/form
is `tmp/constructor-repair/f10-resume-probe.json`:

```clojure
(let [refusal (seon.db/q database
                       '[:find ?entity :where [?entity :seon.agent/idd _]])]
  {:operation (:seon.error/operation refusal)
   :declared-arity-error?
   ((seon.schema/projection-validator projection :seon.db/invalid-read-error) refusal)
   :recorded-read-error?
   ((seon.schema/projection-validator projection :seon.db.read/error) refusal)})
```

The seven dead requires are absent. The protected test infrastructure files have
zero diff from the resume baseline `19a11478e`. No canonical base was rebuilt,
no cold/platform gate was run, and the running default program was not changed.

## Final finding-to-commit record

| Finding | Implementation / assertion commits |
|---|---|
| F1 | Declaration `434c01f4c`; body and restored cause/enum assertions `e2e3b7473` |
| F2 | Declaration `434c01f4c`; body and restored unowned assertion `e2e3b7473` |
| F6 | Body/declaration `434c01f4c`; both-cause assertions `320c73adc` |
| F7 | Declared unknown-form check and temporal/schema-drift assertions `e2e3b7473` |
| F8 | Both declared phase producers, consumer, enum, and retry/phase assertions `320c73adc` |
| F9 | Operator/reply `59e9642b1`; boot body/declaration `434c01f4c`; boot assertions `320c73adc` |
| F10 | Named output/union and distinguishing assertions `e2e3b7473`; actual query arity schema correction `a2aac9105` |
| F11 | Future/promise expiry distinction `59e9642b1` |
| F12 | Issue/turn `59e9642b1`; schema `434c01f4c`; render/test/config/sci.eval `252e6e5bd` |
| F14 | Empty merge removed `252e6e5bd` |

All assigned implementation work is landed; no ownership wait remains. The
canonical fixture failure and completion-recording refusal are explicit limits,
not a green test request. The selected armed in-process assertions and exact
read-only REPL probes supply the bounded evidence requested by the owner.
The current program remains unpublished on default; publication/adoption,
publication-path clock, platform and cold proof remain the orchestrator's work.
Unrelated edits and all protected test infrastructure files are preserved.

`a2aac9105`'s archived HEAD require exited 0:
`tmp/constructor-repair/head-a2aac9105-load.log`. Every resumed implementation
commit has now passed the prescribed require from its own archive. Archive
helpers await child exit, unlink the dependency symlink, then remove only their
owned archive. All test, diagnostic, and implementation-load shells have exited.
The final evidence-only commit receives the same archive check; its result and
commit id are written to `tmp/orchestrator/constructor-repair-summary.txt`.
