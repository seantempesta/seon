---
type: landing
status: repair in progress; shared-file commits held
created: 2026-09-22
---

# Constructor review repair

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
| F9 | `(mapv #(seon.cluster.boot/diagnostic "probe" {} %) [:refused :boot-failed :client-failed :argv-failed])` showed old retained causes; the disk constructor discarded them. Add the owning closed dispositions to new `seon.cluster.boot.edn` and existing `seon.operator.edn`; the actual boot callers also include non-edn-response and operation-failed. The disk operator diagnostic now preserves refused/client-failed/argv-failed. Delete reply's unused first argument and convert all four calls; local `(refused {:seon.cluster.reply/no-forms true} "probe" {:seon.cluster.reply/text ""})` preserves the marker. Boot assertions cover its four actual dispositions and named schema. | Operator/reply group; boot held |
| F10 | `(count (filter #(clojure.string/includes? % ":seon.error/base)") (clojure.string/split-lines (slurp "test/seon/db_test.clj"))))` found three weakened assertions. Replace them with the requested `:seon.db/error-result` / `:seon.db.read/error` checks and retain specific member/cause checks. No production union was widened to satisfy these assertions. | Pending database group |
| F11 | `((disk-fn 'seon.await "src/seon/await.clj" 'diagnostic) {:seon.await/bound {:seon.await/config-attribute :seon.config.eval/time-limit-ms :seon.await/config-value 1} :seon.await/diagnostic {:seon.error/layer :runtime :seon.error/operation 'probe/await}} {:seon.await/elapsed-ms 1.0})` has elapsed time and no closed-operation. Restore explicit nil assertion for both future and promise expiry. | Boundary cleanup group |
| F12 | Read-only form counted one `seon.error.refusal` occurrence in each of the seven named files; source search found no uses. Remove exactly those requires. | Issue/turn in boundary cleanup; other five held |
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
