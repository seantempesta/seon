---
type: research
status: active
tags: [prd, research, adversarial, datahike, indexing, sci]
---

# `current-src` destructive simulation

## Verdict

No production defect was proved. The hostile live drive preserved the important
invariants: exact-commit forks, sovereign cluster code and data, guarded source
publication, atomic failure before publication, runtime persistence across a
JVM reopen, per-cluster lint context, global schema identity, schema/data safety,
cross-namespace REPL deletion, history, and one-cluster reset isolation.

The strongest evidence came from the real agent path, not direct program-row
transactions. Deterministic model replies entered through
`message/inbound-tx`, the reply reader, per-form clj-kondo analysis, SCI eval,
receipts, and `receipt-settle-call`. Two clusters authored overlapping
`transform` and `transform-test` names plus the same global
`:hostile.shared/value` schema without crossing branches. Cluster B later
reopened in a new JVM and evaluated `(transform 4)` to `104` from acquired
database code.

One follow-up transitive/no-history trial was invalid: the probe registered
`:hostile.transitive/derived` as a plain reference to another schema rather than
as a stored attribute with `[:and {:seon.db/index true} ...]`. The schema row and
function contract committed correctly, but direct Datahike data insertion
rightly refused an uninstalled attribute. That is a probe defect and leaves the
runtime transitive/no-history sub-scenario uncovered here; the recurring
schema-usage-guard suite remains the evidence for that class. No issue was filed
for it.

## Dependency ledger

| Boundary | Maintained source | Property exercised |
|---|---|---|
| Datahike guarded publication | `reference-code/datahike/src/datahike/versioning.cljc:323-444` | immutable values precede the mutable head; expected head checked before and inside update; head read back |
| Datahike schema removal | `reference-code/datahike/src/datahike/db/transaction.cljc:276-315` | current AEVT data refuses physical attribute removal |
| Proximum force | `reference-code/proximum/src/proximum/versioning.clj:119-182` | exact expected destination commit and stale-destination refusal |
| Seon source publication | `src/seon/cluster/source.clj:117-249` | complete scratch publication and guarded scalar upsert |
| Seon exact fork/reset | `src/seon/cluster/registry.clj:235-315`; `src/seon/cluster.clj:1486-1510` | fork captured commit; reset only one named branch |
| Runtime program terminal | `src/seon/sci/eval.clj:793-885`; `src/seon/cluster/run.cljc:626-730,749-825` | schema/function/test rows and deletions commit with receipts |

Selected dependency state was the repository pins at parent commit
`995ccec92`; maintained clj-kondo metadata fixes were `0fc2f636` and
`57252e07`, and the maintained Datahike checkout contained the guarded
`force-branch!` implementation above.

## Reproduction

The disposable scripts and complete logs remain, gitignored, under
`tmp/current-src-destructive-simulation/`:

```bash
clojure -M:dev tmp/current-src-destructive-simulation/drive.clj \
  2>&1 | tee tmp/current-src-destructive-simulation/run-2.log

clojure -M:dev tmp/current-src-destructive-simulation/reopen.clj \
  2>&1 | tee tmp/current-src-destructive-simulation/reopen.log

clojure -M:dev tmp/current-src-destructive-simulation/inspect.clj \
  > tmp/current-src-destructive-simulation/inspect.log 2>&1
```

The successful root was
`tmp/current-src-destructive-simulation/operator-8d6c5b68-e48c-405e-8890-d34d9267c304`.
Its branches were `hostile-a-241e4140`, `hostile-b-f7d209b2`, and
`hostile-c-52a40d6b`. Sean's owner cluster and port 7994 were never addressed.

## Scenario observations

### Publication A and two exact forks

Complete publication produced current-source commit
`6a6b6868-daf2-526e-aa98-317b96ebacc2`, digest
`f06d7539060bd62d45e07e0aa0e355d8e4d85228fd9bf42c9c43b86df0323c0e`.
The exact forks had their own branch-head commits
`6a6b6869-f063-5632-86e5-e4a8334d32ce` and
`6a6b6869-d461-50d5-827f-f22cd8d334fe`, both at basis transaction
`536870924` with the same source digest. The roster contained only `:db`,
`:current-src`, and the two named cluster branches.

### Real agent registration and sovereignty

Agent A committed:

```clojure
(schema/register! :hostile.shared/value
                  [:int {:min 0 :seon.db/index true}])
(defn ^{:malli/schema [:=> [:cat :hostile.shared/value] :int]}
  transform [x] (+ x 10))
(deftest transform-test (is (= 12 (transform 2))))
```

Agent B committed the same schema identity and overlapping unqualified names,
but its transform added 100. Exact rows were
`my.agents.hostile-a/transform`, `my.agents.hostile-a/transform-test`,
`my.agents.hostile-b/transform`, and
`my.agents.hostile-b/transform-test`. The global schema row contained
`:seon.schema/key` and `:seon.schema/form` but no namespace owner.

After separate data transactions, A held value `7` and B held `9`. A redefined
its function to add 20 while B stayed at 100. B evaluated `(transform 3)` to
`103`; its attempt to evaluate `(my.agents.hostile-a/transform 3)` became a
single lint-refusal receipt with `:unresolved-namespace`. The other independent
forms continued. This directly proves runtime analysis read B's cluster-local
program graph rather than A's.

### Publication B while both agents were live

A complete public refresh advanced `current-src` to
`6a6b6875-abd6-5e98-a7d4-90c0ba26116a`. A stayed at basis `536870956` and B
at `536870954`; both branch commits and user data were byte-stable across the
publication. New cluster C forked the new source at branch commit
`6a6b6876-40e2-50ec-a929-c915cdb42156`. Neither C nor `current-src` contained
the hostile agent functions, schema, or data.

The drive used a complete refresh rather than a changed-file incremental edit,
so safe incremental artifact selection was not independently exercised here.
That is a coverage gap, not a counterexample.

### Stale and interrupted publishers

An upsert carrying stale expected commit A refused with exact ex-data:

```clojure
{:type :stale-branch-head
 :branch :current-src
 :expected-current-commit #uuid "6a6b6868-daf2-526e-aa98-317b96ebacc2"
 :current-commit #uuid "6a6b6875-abd6-5e98-a7d4-90c0ba26116a"}
```

The winning head remained B. A complete scratch publication whose population
function threw `{:probe :partial-publication}` also left the head exactly B and
left no `building-source-*` branch in the roster. This matches Datahike's
values-before-pointer implementation rather than relying on Seon prose.

### Schema safety, deletion, and history

With current `:hostile.shared/value` data and a function contract referring to
the schema, an attempted replacement logged and refused
`:seon.schema/current-data-blocks-change` with
`:seon.schema/data-attributes [:hostile.shared/value]`. The program schema row,
function, test, and value remained unchanged. The following independent form
still ran, demonstrating per-form failure rather than whole-reply rejection.

Agent A then deleted its test and function with `ns-unmap`, current data was
explicitly retracted, and `schema/unregister!` removed the now-unused global
schema. Current program rows and the physical Datahike attribute disappeared.
At the pre-retraction basis, `d/as-of` still returned value `7` and the exact
schema form `[:int {:min 0, :seon.db/index true}]`.

The function/test deletion happened through actual REPL `ns-unmap` semantics.
The later follow-up also acquired source in a new JVM. A fully successful
different-current-namespace deletion was not completed because the follow-up
probe stopped at its invalid stored-attribute declaration; this remains a
coverage gap. Existing runtime regression tests cover computed target
namespaces.

### Reopen and exact reset

Cluster B reopened in a new JVM with its function, test, global schema, and
value `9`; `(transform 4)` returned `104`. The exact receipt retained the reply
source and result. Its cross-cluster call remained the recorded lint refusal.

Resetting only A returned
`{:seon.store/branch :cluster-hostile-a-241e4140,
  :seon.cluster/created? true}`. Reopened A had no hostile program or data and
the published source digest. B remained exactly at commit
`6a6b6873-7ab7-584c-ba57-f7227a814d41`, basis `536870954`, with its function,
test, schema, and value `9`. `current-src` remained exactly
`6a6b6875-abd6-5e98-a7d4-90c0ba26116a` before and after reset.

## Ranked findings

1. **No S/P0/P1 production finding.** Every tested database boundary held.
2. **Coverage gap — safe incremental changed-file publication.** The hostile
   live drive advanced with complete publication. The recurring source tests
   cover guarded incremental publication, but this run did not modify a real
   first-party file because the lane owned no source paths.
3. **Coverage gap — runtime transitive and no-history lifecycle.** The follow-up
   authored a value-schema reference without a database facet. Datahike's
   refusal was correct. The recurring
   `test/seon/schema_usage_guard_test.clj:119-397` remains the applicable proof.
4. **Probe defect — first receipt query used a nonexistent
   `:seon.cluster.eval/source` attribute.** The corrected query joins receipt
   and form on run plus ordinal; it recovered exact reply source and the
   cluster-local lint refusal. This affected reporting only, not the agent run.

## Timing and resources

The successful full drive took roughly 40 seconds wall-clock from process load
through two cluster starts, eight agent turns, a second complete publication,
fork C, stale/failed publishers, history, and reset. The second-JVM reopen
reached the live cluster in roughly 8 seconds and evaluated the restart call
within the following few seconds. These are coarse log-timestamp observations,
not benchmarks. No unexplained JVM or branch-head growth was observed; branch
roster changes matched explicit forks and retained stopped experiment branches
by design.

All disposable cluster instances were stopped and no Java process referencing
the disposable root remained. The database directories and logs intentionally
remain under `tmp/current-src-destructive-simulation/` so the evidence can be
requeried; they contain no owner-cluster data and are gitignored.
