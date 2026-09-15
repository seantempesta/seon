---
type: research
status: active
tags: [research, schema, database, architecture]
---

# P1: projection carriage and remaining producer boundaries

## Batch 2c repair handoff — 2026-09-15

Code and regressions: **`28e955327`**. No test JVM was started. Re-gate request
rewritten at `tmp/orchestrator/gate-requests/p1-ambient-state.txt`; the
orchestrator owns execution. The 392-test gate's 50 failures and 20 errors
are the entering evidence, not a verdict on this repair.

### One carriage seam for fixtures and fresh threads

`seon.db/carry-connection-projection-state!` attaches the existing state
pointer to connection metadata. `connection-projection-state` consumes that
before the existing operator lookup. `db/db` captures an immutable projection
on each acquired value; transaction admission and report carriage use the same
state. No global registry, database reconstruction, or per-test fallback was
added. The canonical `run-database-body` attaches it once before extra schema
or the body; both fresh-store and branched fixtures go through that seam.
`fork-cluster-ctx` reuses that fixture state instead of inventing a second
projection state for the same connection. Read births in the six affected
namespaces and shared fixture helpers now use `db/db`.

Dependency evidence: Datahike's `Connection` exposes metadata from its wrapped
atom (`reference-code/datahike/src/datahike/connector.cljc:38–46`). Its ordinary
non-streaming dereference reconstructs a database from store data (`:82–99`),
so attaching metadata only to the initial database snapshot failed a disposable
probe: all four carriage checks were false after one transaction. Connection
metadata survived the same probe; database acquisition and both report values
then carried the projection without a thread binding. This attachment happens
at acquisition, not inside the writer's transaction logic.

### Datom failure

The live Datahike database answers `map?` true while `(first (seq database))`
is a `datahike.datom.Datom`. `render.value/value-node*` sorted generic map
entries by `key` before identity admission, causing the Map.Entry cast. It now
uses the existing registry-declared `identity-only-projection-in` before
structural inspection. No Datahike-class roster or second renderer was added.
Default's real ctx rendered the nested value as:

```clojure
#:probe{:database "database :cluster-default at basis transaction 536871763 commit 6aa9c166-dfcf-58d9-9919-db02afe5413c"}
```

That public render probe completed in 125 ms. The existing nested-values
canonical regression remains the recurring proof; its transaction-report face
expectation is a separate gate boundary, not established by this observation.

### Missing effect events

Background settlement's request discarded the carried world, retaining only
connection/caps/policy. The request now captures its database once and carries
its database/ctx/environment/projection through settlement admission. Canonical
fixture connections also retain their state on a fresh thread. Effect tests
observe terminal facts through `db/db`, rather than reading raw listener-report
values. Terminal effect facts are monotonic, so the latest database is the
appropriate event predicate authority.

The disposable probe installs the complete canonical Datahike attribute schema,
seeds one effect identity, and invokes the real settlement owner on a new Java
thread. Result: **one listener event**, `#:probe{:value 7}` persisted, no
transaction error, and report `:db-after` carried its projection. The thread
was joined, listener removed, connection released and disposable database
deleted. Script: [p1-batch2c-probe-2026-09-15.clj](p1-batch2c-probe-2026-09-15.clj).

### Verification and exact boundaries

Default remained PID **69622**. The MCP sessions were lost twice; the first reconnect
verified the same PID, falsifying the tool's unobserved restart attribution.
Live source was loaded through `require :reload`, then 1056 contracts re-armed (49 ms; PID again 69622).
Default could not load `seon.test-support` from its classpath; the already open
`development-adoption-cannot-load-test-support.md` belongs to the reaching-tests
lane. No classpath alteration or test-runner workaround was attempted here.
The canonical fixture regression therefore awaits the orchestrator gate.

Clj-kondo: one pre-existing unresolved generated `parser.type/->Variable`
constructor, 17 warnings. The preceding handoff records both cache-refresh
attempts and its dependency definition. No new syntax/name errors were found.
The fixture class regression now asserts carriage and a real transaction from
an unbound Java thread, replacing its obsolete expectation that a canonical
fixture's transaction must refuse on a fresh thread.

While this lane worked, another lane edited MCP artifact lifecycle tests.
The commit used an owned-file snapshot containing only our constructor changes
and real-fixture config regression; their lifecycle changes remain untouched.
Other dirty render, cluster, hook, schedule and test-runner paths were preserved.
The temporary commit snapshot was deleted; no lane-owned background shell or
JVM remains. The retained gate root `tmp/test-runs/run.Vepjoy` was not modified.

The rest of batch 2c is not asserted green: it includes old `seon.run/complete`
expectations, removed result-EDN fields, scalar attribute renderer selection,
preview process provenance, a resolver trap, and declaration-codec expectations.
The re-gate must distinguish surviving semantic failures from consequences of
missing carriage. No contracts or assertions were weakened to conceal them.

## Outcome — final handoff, 2026-09-15

Implementation and canonical regressions: **`b80f78a7c`**. Read all named
authorities and every class member end to end before this work. Option 1 is
implemented at the existing projection seam: **a read or admission consumes
its carried/supplied projection, and missing projection never rebuilds from
the database; it returns the one counted `seon.db/projection-fallback` error.**
Database values capture immutable projection metadata, including both values
in transaction reports and the database passed into transaction callbacks.
The existing explicitly supplied thread projection remains a compatibility
input to database reads/config/transaction construction; this slice does not
claim to remove every ambient input or close all of P1.

Final default JVM probe, PID 69622: supplied versus carried pull-in-find,
scalar find, and tuple find all compare equal (`[true true true]`). Raw
`problems` with no projection returns `:seon.schema/missing-projection` and
exactly one warning. Runtime observation reports `observed`, two agents and
three error-signature groups. No MapEntry exception. Reloaded turn Vars and
re-armed 993 contracts; default was never stopped, restarted, or reforked.

Final HTTP probe: **200, 1.526276 seconds, zero new projection-fallback
warnings**, counting only log bytes appended during the request. Earlier
baseline was 21.406758 seconds; the owner's incident measurements were
28.5/20.6/22.7 seconds. A second probe after contract re-arming returned **200, 1.806096 seconds,
zero warnings**. Therefore the 1.6-second target is not consistently verified;
no independent cold-cache or latency-distribution claim is made.
Exact HTTP evidence: [p1-page-final-2026-09-15.json](p1-page-final-2026-09-15.json).

`turn.clj` became clean at HEAD before the final edit. Its database births
now use `db/db`; declaration and terminal admission use the carried projection.
Previously protected kernel, config, SCI acquisition and documentation changes
also landed before this lane edited them. The runner diff is only the two
owned cold-publication constructor lines. No foreign report, reader, fixture,
operator, or schema-resource edits are included.

### Verification boundary and gate request

At the owner's 21:05Z rule, lane testing stopped. **No new tests were run
after that instruction.** The earlier expanded gate was red (197 tests,
1309 assertions, 25 failures, 13 errors); its evidence is retained below.
Subsequent fixes include the partial-registry instrumentation candidate,
raw documentation fixtures, admission world propagation, transaction callback
carriage, and warming the shipped print grammar before the class probe's
resource counters. These corrected regressions await the orchestrator's gate.

Exact namespace request: `tmp/orchestrator/gate-requests/p1-ambient-state.txt`
(one namespace per line, plus `platform`). Do not infer a platform pass.
Clj-kondo: one existing unresolved generated constructor
`datalog.parser.type/->Variable` at db.clj:473, 130 warnings. Both publication
classpath and direct dependency cache refreshes were attempted. HEAD already
contains this call; `reference-code/datalog-parser/src/datalog/parser/type.cljc:40`
defines `Variable` through `deftrecord`. The final lint output is retained.

The explicit publication attempt ended with lock-hold-timeout after 900000ms;
there is no successful adoption claim for this complete slice. Live evidence
exercised hot-loaded Vars in the existing default JVM. The orchestrator must
verify complete source adoption along with the batched gate. All lane-owned
shells are ended; the disposable worktree is removed at handoff.

### Latest allocation observations

ThreadMXBean carried Juniper id read: **672,048 bytes / 0.947250 ms**.
Raw fallback before repair: **4,506,504,040 bytes / 982.175166 ms**.
Transaction-report after-value reads: **1,866,504 bytes / 2.787583 ms**, then
**596,000 / 0.919166 ms**, both with zero fallback warnings. The owner's
previous report-derived read rebuilt for 1086 ms.

Admission with explicit world: 20-map result **1,793,912 bytes / 3.468875 ms**,
repeat **1,793,200 / 2.417959 ms**; 100 maps **8,387,288 / 9.613041 ms**.
All returned the same value. Historical 20-map admission was 374.68–382.31 ms;
no historical allocation count was recorded, so none is invented here.
Real guarded schema candidates fell from about 4.65 GB / 907–919 ms to
107.85–108.69 MB / 44–45 ms; the independent 64 MiB whole-evaluation budget
remains open in its named residual issue.

The dated sections below preserve the investigation, member verdicts,
three priced out-of-scope options, and earlier verification boundaries.
Later observations above supersede temporary protected-file claims below.

### Implementation and measurements after the decision

The database read fallback no longer constructs declarations. Its single
`projection-fallback` seam emits one counted warning and returns a flat
`:seon.schema/missing-projection`. Missing input preserves that exact error at
the public read boundary. `schema/handed-projection` no longer constructs a
projection from packaged forms. Transaction admission no longer silently
builds either a database or a packaged projection.

`carry-projection-state` now also captures the immutable projection in
database metadata; an already carried snapshot wins over later environment
state. Transaction reports carry projection state and the projection on both
`:db-before` and `:db-after`. JVM instrumentation checks database argument
metadata before selecting its existing request/thread/host projections.

**Temporary compatibility boundary:** `read-declarations` still accepts an
already supplied projection binding. Removing that branch exposed raw inputs
in protected SCI acquisition and MCP configuration. The edit hook loaded the
strict reader and MCP reported missing configuration despite the facts being
present. Restoring this existing binding path and hot-reloading db restored
MCP. The rebuild fallback stays deleted. The subsequent explicit re-arm
reported 990 instrumented Vars. Do not describe the shared tree as having
eliminated all dynamic projection selection yet.

The strict candidate was tested separately in a disposable HEAD worktree with
the exact proposed acquisition edits, without touching the protected shared
file. Its canonical armed regression passed **1 test / 21 assertions / zero
failures or errors**, including a usable competing thread projection that
could not rescue an uncarried database. That proves the candidate read seam;
it does not prove complete producer migration or live adoption.
The exact producer patch is
[p1-protected-acquisition-2026-09-15.patch](p1-protected-acquisition-2026-09-15.patch).
Cold cluster construction remains the explicit projection constructor;
`acquire!` takes the result from its request, database or ctx and refuses
absence. It no longer implicitly rebuilds inside acquisition.

Live JVM proof after db hot reload and contract re-arming, default:
`(seon.db/transact! connection [])` committed an empty domain transaction.
Both returned database values had carried projections; db-after also had
projection state. Two root-id pulls from db-after measured **1,866,504 bytes /
2.787583 ms** and **596,000 bytes / 0.919166 ms**, with no warnings. The raw
uncarried comparison returned one missing-projection error and one warning
in **677,544 bytes / 1.422125 ms**. Before this lane, the same raw read cost
**4,506,504,040 bytes / 982.175166 ms**; the owner's transaction-report probe
had logged a **1,086 ms** rebuild. The operation and outcome changed for
missing input: this is refusal cost, not a faster successful read.

The initial strict fast run against unmodified producers failed **45 tests /
76 assertions / 40 errors** at SCI acquisition after missing-projection
reads. This is the measured migration dependency. The original hand-rostered
database declaration test is replaced in place with the canonical
fixture regression; no alternate fixture path is retained.

### Further live measurements and verification

The original two SCI registration forms were evaluated in a private context
acquired from default's carried database. The context received explicit read
custody for lazy program installation, created no listener, and returned
candidate rows; it did not mutate default's shared context or transact either
schema. A follow-up database query returned no rows for either schema key.
The warm arithmetic form returned 2.

| Guarded SCI declaration | Historical allocation / duration | September 15 allocation / duration |
|---|---:|---:|
| `:my.dogfood/score`, bounded integer | 4,652,159,248 bytes / 919 ms | 108,685,472 bytes / 45 ms |
| `:my.dogfood/label`, nonempty string | 4,652,146,872 bytes / 907 ms | 107,849,320 bytes / 44 ms |

The kernel record uses ThreadMXBean allocated bytes on the executing thread.
These are complete guarded evaluations, not the pure constructor numbers.
The pure candidate calls measured 819,984 bytes / 1.415750 ms (score) and
806,288 bytes / 0.980542 ms (label). `fba6bc4c1` had already removed the
full-population declaration compilation; this lane does not claim that
historical repair as new code. Full evaluation remains above the existing
64 MiB allocation assertion; its residual is recorded in
[guarded-schema-declarations-still-exceed-the-allocation-regression-bound.md](../../../seon/issues/guarded-schema-declarations-still-exceed-the-allocation-regression-bound.md).

The first connectionless probe lacked the custody lazy installation requires:
its warm form returned 2, but declarations returned a null-connection failure.
That failed probe is not included as a successful allocation result. Supplying
read custody made both declarations succeed. Config acquisition also required
its existing explicit binding; the protected config producer is named in the
remaining-carriage issue.

Reproducible after forms:
[p1-carriage-after-probes-2026-09-15.clj](p1-carriage-after-probes-2026-09-15.clj).

### Gate iterations

- Initial compatible fast run: 46 tests / 341 assertions, zero failures/errors.
  Ten carried queries: 27,440,626 ns versus raw 27,562,041 ns.
- First isolated gate stopped before tests: the runner's cold sealed-source
  constructor omitted its projection before provenance reads. The owned
  `src/seon/test/runner.clj` change attaches its once-acquired projection there.
- Second gate, `ff60a3cf7` plus the six owned paths: 112 tests / 781 assertions,
  13 failures / 3 errors. Temporal-carriage hit `NoSuchFileException` in the
  shared published fixture store and passed isolated confirmation. The landed
  fixture repair `bc3746037` is now included in the checkout. Seven render
  tests failed with missing proc input, stale face expectations, a resolver
  fixture trap, and a nested database Datom/Map.Entry exception. Those are
  exact protected render/test boundaries, not evidence that the direct
  database-identity producer still violates its contract.
- Third gate, `6dc70f30a` plus the owned paths: 91 tests / 673 assertions,
  12 failures / 3 errors, all in this lane's expanded class regression. The
  test had selected a retired attribute from its former fixture. It now uses
  the canonical namespace identity attribute; no synthetic schema roster.
  Existing database, schema, and instrumentation tests passed.

The fourth gate passed **91 tests / 676 assertions / zero failures or errors**,
but exited 1 because durable result recording requires a published current-src
at the launcher root. The disposable worktree had none. This was a test-root
setup omission, not a branch-head race or an assertion failure. The worktree's
own source publication is being initialized before rerunning the gate; no
cluster or default lifecycle operation is required.

### Latest live boundary

A later repeat of the empty-domain-transaction report probe timed out at
20,000 ms. Its transaction outcome is unknown; it was not retried. The
subsequent runtime-status tool selected the same default PID 69622 / PREPL
55914 but returned `ClassCastException` at `clojure.lang.RT/dissoc`: MapEntry
cannot be cast to IPersistentMap. This is an unavailable health observation,
not a proven cause of the preceding timeout or a contradiction of the earlier
completed report measurements. No alternate transport or default lifecycle
operation was used. The publication shell for the test edit was terminated
while still waiting for another publication's lifecycle lock; the edit hook
has the test change queued. No foreign publication was interrupted.

### Remaining integration boundary

The strict-carriage continuation is recorded in
[read-and-admission-producers-still-require-thread-projections.md](../../../seon/issues/read-and-admission-producers-still-require-thread-projections.md).
The separate nested-render residual is
[nested-database-rendering-treats-datoms-as-map-entries.md](../../../seon/issues/nested-database-rendering-treats-datoms-as-map-entries.md).
No protected admission, acquisition, config, render, report, or fixture source
was edited in the shared tree. Only the instrumentation argument-projection
selection hunk belongs to this lane; report-function edits are excluded from
its isolated checkout and commit.

### Exact admission edit for the protected owner

Apply after bisect releases the relevant admission section; its other changes
must be preserved. This patch uses the existing missing-result shape plus
the shared flat error, so no second diagnostic or schema family is needed.
The caller migration must hand projections in requests that currently rely on
thread state (notably error normalization) before enabling this refusal.

```diff
--- a/src/seon/sci/admit.clj
+++ b/src/seon/sci/admit.clj
@@
             [seon.id :as id]
+            [seon.db :as db]
+            [seon.env :as env]
@@
     supplied-projection :seon.schema/projection
-    on-core-error :seon.config/on-core-error}]
+    on-core-error :seon.config/on-core-error
+    :as request}]
@@
-    (admit-walk value interrupt-fn caps record unbounded?
-                supplied-projection on-core-error)))
+    (if-let [projection
+             (or supplied-projection
+                 (:seon.schema/projection (env/of request))
+                 (some-> (:seon.sci.eval/ctx request)
+                         env/of :seon.schema/projection)
+                 (when (db/database-value? value)
+                   (db/carried-projection value)))]
+      (admit-walk value interrupt-fn caps record unbounded?
+                  projection on-core-error)
+      (cond-> (assoc (db/projection-fallback 'seon.sci.admit/admit)
+                     :seon.sci.admit/reason :unserializable)
+        record (assoc ::record record)))))
@@
-  (let [projection (or supplied-projection (schema/handed-projection))
+  (let [projection supplied-projection
```

This is an unexecuted proposed diff, not a claimed tested admission repair.

### Original design boundary (before owner approval)

Design stop before production edits, as explicitly required by the assignment
and AGENTS.md §2.5. This is not a class closure or a failed test gate.

The class guarantee is: **a running operation receives its declaration
projection with its inputs; missing input refuses visibly and never triggers
a database or packaged-resource rebuild.**

The full assigned membership cannot acquire that guarantee at one schema
consumer: its producers include cluster boot/adoption, execution custody,
operator source acquisition, and lifecycle generators. Several are protected.
Removing only `fallback-read-projection` leaves a global acquisition lookup,
dynamic custody, bootstrap wrapper fallback, and mutable projection-state
selection. Completing those owners is hours of cross-owner work.

## Exactly three options

Estimates are engineering effort, excluding waiting for protected owners and
machine test slots; they are planning estimates, not measured durations.

1. **Require carried projections at running read/admission boundaries first
   (recommended), 1–2 days.** Retain explicit boot/publication construction.
   Change the existing fallback diagnostic into a counted missing-input
   refusal shared by the consumers; pass immutable projections from the
   existing environment at acquisition. Migrate the canonical fixture and
   actual callers before removing their fallback. Guarantee: these running
   paths never rebuild declarations when input is absent. Give up immediate
   closure of all P1 notes; custody, lifecycle and adoption remain separately
   named obligations. Requires a coordinated release of cluster, evaluation,
   and fixture producer seams, not permission to edit their current work.
2. **Complete projection generation and custody carriage together, 3–5 days.**
   Extend the existing environment acquisition/publication mechanism to hand
   immutable, basis-correct projections and explicit writing custody; remove
   the operator-table lookup and dynamic consumer fallbacks together. Include
   host instrumentation's declared boot-versus-running behavior in that
   contract. Guarantee: running schema consumers and write decisions select
   their world exclusively from admitted input values. Give up a bounded
   single-lane change; this spans protected cluster/evaluation/fixture owners
   and still does not establish atomic adoption for concurrent host calls.
3. **Close every assigned P1 member as one coordinated program, 10–20 days.**
   Include option 2 plus coherent adoption, immutable source-analysis input,
   isolated operator source authority, and lifecycle-scoped opaque generators.
   Guarantee: each listed operation uses its owned input generation throughout
   its lifetime, with a specific regression for each distinct observable.
   Give up immediate delivery and unrestricted concurrent live adoption while
   designing its completion boundary. The cost range matches the dated mining
   row; it is not a claim that all historical defects remain live.

## Authority and inherited state

Read AGENTS.md, docs/seon/issues/README.md, the named class record and every
explicitly assigned member note end to end, including the archived admission
and instrumentation records. Read the complete issue-class-mining report;
its P1 structural-kill column says the immutable environment/projection rides
the work or database value and APIs expose no dynamic/process fallback.
Skills used: data-oriented-clojure, repl, datahike, clojure-testing.

Source basis: `22893b71383cec23c8763df7da841524259a1774`, branch
`steward-platform`. `bin/seon status`: default PID 69622 alive, prepl 55914,
no orphan Seon JVMs. MCP runtime status answered; all three observed plumbing
procs replied, and the existing error count was 10. This is connectivity and
bounded observation, not proof of every agent's health.

`bin/issues-index --class class/p1` exited 1 before printing membership:
eight unrelated notes lack schedule rows. The index is owner-managed and was
not edited. Exact-tag search supplied the dated open membership, including
the extra work-launcher and database-read notes. The requested class record
is already in `docs/seon/issues/archive/`; its resolved status covers its
three original demonstrated mechanisms, not the entire P1 tag population.

Inherited changes: resources/seon/schemas/seon.sci.admit.edn,
src/seon/cluster.clj, src/seon/render/value.clj, and the four named bisect
test files. During this audit src/seon/sci/admit.clj also acquired an unrelated
8-line diff. All were preserved. No other lane was contacted or operated.

## Dependency ledger and owning seams at the initial audit

- Datahike gitlink `cdcb5792db8bd599487f099437265d18a31164a5`:
  reference-code/datahike/src/datahike/versioning.cljc:75 derives cache
  ownership from the supplied attached value. Seon's temporal schema origin
  traversal is src/seon/db.clj:901; read state comes from that origin at :950.
- reference-code/malli gitlink `3517a3cd9271b2083780ac7be1725493905bca2e`:
  the existing `m/-instrument` call receives explicit compile options in
  src/seon/instrument.clj:465–478 and :524–543. The wrapper cache rides the
  projection, but :550–575 retains a bootstrap wrapper for calls lacking a
  projection. :503–521 also accepts the dynamic projection carrier.
- reference-code/sci gitlink `fcbd8862800e638dc0f8f5521111f999279cbcd2`:
  src/seon/sci/eval.clj:158–163 still owns a delayed shared generator context.
  Its protected acquisition owner and forwarded host Vars are separate from
  schema compilation.
- src/seon/db.clj:126–139 searches operator `running-instances` by connection
  identity. :141–155 carries a mutable state reference as database metadata;
  :955 dereferences it when the read asks for its projection. This is not yet
  an immutable projection snapshot. No stale-basis failure was reproduced.
- src/seon/db.clj:939–948 rebuilds on missing input and prints the existing
  `projection-fallback` warning. It has no explicit occurrence counter there.
  :252–279 still bases foreign-write custody on `*conn*`.
- src/seon/schema.clj:2474–2568 already performs dependency-based incremental
  declaration admission; `git log -S` attributes it to `fba6bc4c1`.
- src/seon/schema/edn.clj:410–435 no longer reads `!source-files`, but reads
  the packaged population again to find refusal provenance. This is a
  remaining sideways read, not the historical accumulating atom.

## Per-member verdicts at the initial audit

These are dated verification verdicts, not new lifecycle statuses. No member
is falsely closed on source existence or a missing signal.

| Member note under docs/seon/issues/ | Verdict and evidence |
|---|---|
| archive/schema-environment-is-ambient-not-explicit.md | Keep its narrow resolved status. Its archived evidence names `16c6c7bc5`; current projection caches remain owned by projections. Dynamic carriers and the raw database fallback remain outside that closure. |
| schema-declaration-rebuilds-four-gigabytes-per-form.md | Original full-rebuild cause removed by `fba6bc4c1`; current pure candidate admission measured 907,104 bytes / 0.624375 ms. The exact two historical SCI registration probes and scaling regression were not rerun, so full acceptance closure is pending. |
| archive/value-admission-resolves-the-declaration-population-per-node.md | Keep narrow resolved status. HEAD admit-walk carries one projection, and open-node uses it; canonical declaration_population_test.clj clears runner carriers. Current supplied admissions measured below. Missing projection still uses an optional path, so this does not prove the new strict-input guarantee. |
| archive/instrumentation-compiles-under-one-clusters-projection.md | Keep narrow resolved status under its explicit shared-host-program ruling. Projection-local wrapper compilation exists; bootstrap fallback remains a deliberate separate contract decision. No cohost test rerun. |
| foreign-write-fence-reads-only-the-dynamic-var.md | Confirmed remaining shape at db.clj:252. Read-only live private guard with `*conn*` nil returned nil. No foreign transaction performed; this is not an ordinary SCI custody-loss demonstration. |
| schema-source-provenance-accumulates-in-a-global-atom.md | Historical atom removed (`656cea270`, git log -S); live ns-resolve confirms absence. Residual: refusal! reacquires packaged resource provenance rather than receiving the candidate population's map. Keep open for the full immutable-provenance acceptance. |
| opaque-contract-generators-share-live-process-objects.md | Remaining shared delayed SCI context and web server/mult are present at eval.clj:158–163 and web.clj:100–126. No server generator forced. Protected web owner; no lifecycle proof. |
| history-policy-refusal-test-is-load-flaky.md | root-store-holder still determines refusal at cluster.clj:767–805. The historical timing hypothesis is not confirmed by that presence. Required 20 loaded repetitions not run; protected cluster owner. |
| the-database-identity-face-cannot-satisfy-its-own-declared-input.md | Correct named input exists at render/value.clj:74. Direct live producer returned a database identity string, not refusal. Nested renderer regression remains unrun; protected renderer and its in-flight diff are not claimed as this lane's repair. |
| isolated-operator-init-requires-a-source-checkout.md | Read historical record completely; no isolated-root init performed before the design stop. Current behavior remains unverified. It cannot be closed from the projection-consumer seam. |
| source-analysis-can-slice-changing-files-with-stale-offsets.md | exact-source still uses unchecked offsets over separately captured text (fn.clj:125–143); stable-manifest checks changed snapshots after build (cluster.clj:1637–1665). Race not reproduced; fix must pass the same source bytes to analysis and extraction in the protected publication path. |
| a-hot-adopted-handle-shape-change-wedges-the-live-turn-proc-silently.md | agent.clj:641–644 still requires context-state; current boot constructs it at cluster.clj:2563. No old-handle incompatible-adoption drill. `5ffc491ae` does not establish this handle-compatibility gate. Keep open. |
| development-adoption-can-mix-host-and-sci-generations.md | `5ffc491ae` repairs authored wrapper freshness and retries once; its note explicitly retains concurrent-call atomicity. That bounded retry cannot close the broader generation guarantee. No concurrent adoption drill performed. |
| flow-work-launcher-graph-omits-its-root-io-executor.md | Extra current tagged member: HEAD flow.clj:602–640 still ends graph definition with only compute-exec. No placement proof or flow edit. |
| seon-db-reads-rebuild-the-projection-per-call-when-none-is-handed.md | Confirmed live after the earlier carriage repair; exact measured raw/carried comparison below. This is the first narrow implementation target in option 1. |

## Initial read-only measurements

Read-only MCP JVM session `p1-ambient-state`, default, timeout 20,000 ms.
Thread allocation uses `com.sun.management.ThreadMXBean` on the evaluating
thread immediately before/after each owner call; it excludes other threads
and MCP serialization. This is existing loaded behavior, not this lane's
hot-adoption proof. No production edits were made.

| Operation | Allocated bytes | Milliseconds |
|---|---:|---:|
| raw @connection pull [:seon.agent/id] for root | 4,506,504,040 | 982.175166 |
| same pull on seon.db/db connection, first | 583,712 | 0.407208 |
| same carried pull, repeat | 583,248 | 0.285417 |
| pure projection-with-schema candidate | 907,104 | 0.624375 |
| explicit-projection admission, 20 maps | 1,850,520 | 0.926666 |
| same admission, repeat | 1,850,088 | 0.728750 |
| explicit-projection admission, 100 maps | 8,680,280 | 3.171833 |

The schema population contained 2,561 forms. Raw database metadata had no
projection state; seon.db/db metadata did. Both reads returned root's id.
Exactly one warning was returned by the first probe:

```text
WARN seon.db/projection-fallback caller= seon.db/pull elapsed-ms= 981 Supply a database value carrying its projection state.
```

The candidate definition was `[:int {:min 0 :max 100}]` and was never
registered. Admission returned print-node and value keys in all three cases.
The direct identity producer returned:

```text
database :cluster-default at basis transaction 536871456 commit 6aa99da3-a457-5bce-8013-22d448397f75
```

These measurements preceded this lane's implementation. Exact executed forms are retained as data in
[p1-ambient-state-probes-2026-09-15.edn](p1-ambient-state-probes-2026-09-15.edn).

## Historical design-stop boundary

No test invocation: the assignment's design stop fired before implementation
or test edits. Neither the named-path gate nor --platform is claimed green.
Needed for option 1: one canonical armed regression proving missing carried
input causes one counted refusal and zero projection construction/resource
reads, plus the existing database/admission/instrumentation suites, then
serial named-path and platform gates. A current-only read test cannot prove
historical projection correctness; include a retained database value across
schema advancement at the producer seam.

No default lifecycle change, no source reload/adoption, no test JVM, background
shell, scratch root, or worktree was created. The index's missing schedule
rows are a recorded documentation boundary, not the reason for this stop.

## Live regressions, resumed after owner interruption

The JVM probe verifies that a projection-less `seon.db/q` returns the flat
`:seon.schema/missing-projection` error, not malformed decoded rows.
`seon.problems/error-signatures` iterated that error map, creating MapEntry
values. The same query on `(seon.db/db (seon.operator/connection "default"))`
returned three proper maps. `readiness` now acquires through `seon.db/db`, and
`problems` refuses missing input before iterating query results. A hot-reloaded
Var probe of `mcp-runtime-observation` returned health `observed`, three error
signature groups, and two agents in 90 ms. Default was never restarted.

The canonical class regression now probes collection pull-in-find, scalar find,
and tuple find with seed 20260915, comparing explicitly supplied projection to
immutable database metadata. Truly missing projection remains a flat refusal;
identical successful decoding there would violate the approved no-rebuild law.

Page measurements (`curl --max-time 60 -w '%{time_total}'`): before 21.406758 s;
after database producer carriage 39.036521 and 32.794528 s; after supplying the
whole projection at the decoder boundary 22.590403 s; after metadata-only
instrumentation selection 15.081215 s. These measurements DO NOT meet 1.6 s.

A JVM thread dump during the final page load verifies an independent remaining
rebuild: `seon.sci.eval/documentation-contract` at line 1155 calls
`schema/projection-from-database` per documented function, through
`directory-value`, on the debug-page thread. This function is inside a foreign
uncommitted documentation hunk. Exact required replacement in that hunk:

```diff
-    (let [projection (schema/projection-from-database database)
+    (let [projection (or (db/carried-projection database)
+                         (let [failure (db/projection-fallback
+                                        'seon.sci.eval/documentation-contract)]
+                           (throw (ex-info (:seon.error/message failure) failure))))
```

The proposed change must land at that read consumer, not as a hidden cache or
fallback in the projection constructor. The foreign hunk is preserved. This
verified boundary prevents claiming the requested complete page performance
proof. Read producers in `render.web` now use `db/db`; render request/profile
and walk acquisition prefer the request or database's projection.

Latest read-only ThreadMXBean probe on default, after decoder/instrumentation
changes: pulling Juniper's identity returned the proper map, allocated 672,048
bytes, and took 0.947250 ms. No projection-fallback event was emitted.

Additional exact protected admission producer edits: both `admit/admit-value`
request maps in `src/seon/sci/kernel.clj:589,609` need
`:seon.schema/projection (context-projection ctx)`. The existing ctx already
owns that projection; no new mechanism is needed. `src/seon/turn.clj:3345`
needs `(db/carried-projection database)` after its read constructor becomes
`(db/db (:seon.db/connection cluster))`. Both files have concurrent edits.
The now-free admission consumer cannot safely drop its thread input before
these request producers carry it; the residual issue remains open.

The bounded page/log observation returned HTTP 200 in **35.829011 s** with
**zero projection-fallback warnings** in the log bytes appended during the
request. This proves producer carriage for that request, not the latency goal.
Evidence: [page observation](p1-page-observation-2026-09-15.json) and
[page-thread stack](p1-page-stack-2026-09-15.txt). The stack was captured with
`jcmd 69622 Thread.dump_to_file -format=json tmp/p1-page-threads.json` during
a page request; only the request thread's stack is retained.

## Subsequent landing and continued carriage work

The documentation/kernel owner landed `a65985098`, freeing those files.
The exact documentation replacement above is now applied, and its compiled
contract description rides the existing projection-owned cache keyed by the
source spec. The first page after removing reconstruction took 4.103555 s;
repeats took 3.066094 and 3.265483 s. This narrows the cost but does not meet
1.6 s. A later compiled-contract-cache probe took 4.579336 s; no stronger
performance claim is made. A selection-input reduction experiment failed to
improve its measurement (5.133967 s) and was removed.

`acquire!` now takes its projection from its request/database/ctx and refuses
absence at the common seam. Both guarded-invocation admission requests now
carry their ctx projection. Admission no longer reads `handed-projection`;
it takes a projection from its request/environment or value metadata. The
context-free EDN codec still accepts ordinary scalars and collections without
a schema projection; identity projection uses the supplied value only. Each
nested value's own metadata wins over an enclosing operation's projection.
A read-only default probe admitted the real carried database with an empty
thread carrier and returned exactly `database-value-identity` (true).

The existing transaction-function codec wrapper now attaches its supplied
projection to the callback's database value. Dependency evidence:
`reference-code/datahike/src/datahike/db/transaction.cljc:1152` invokes the
function with its actual transaction database; the existing first-party
wrapper is `src/seon/schema/datahike.clj:478`. The canonical class regression
now includes a real transaction-function read with an empty thread carrier,
and admission of a carried database with no thread projection.

The effect admission check also rebuilt from the database at
`src/seon/effect.clj:179`. It now takes the carried/request projection, with
absence reported at `seon.db/projection-fallback`. Its one ordinary read
constructor uses `db/db`, and its codec request retains the supplied world.

Expanded gate on `a65985098`: **197 tests / 1309 assertions / 25 failures /
13 errors**, exit 1. Full attributed evidence is
[p1-expanded-gate-2026-09-15.txt](p1-expanded-gate-2026-09-15.txt).
One new instrumentation experiment incorrectly selected partial standalone
projections for host contract compilation; it caused the schema test errors
and was removed. The incremental schema live probe then returned true.
Documentation tests passed raw database values and now acquire them through
`db/db`. Other failures include the already recorded nested Datom rendering,
old scalar-plan rendering expectations, missing preview process input, stale
elision wording, a compiled-resolver trap, and the protected web tests'
completion/count assertions. This gate is not represented as green.
The new snapshot uses landed `f000669b0` plus only this lane's paths.
