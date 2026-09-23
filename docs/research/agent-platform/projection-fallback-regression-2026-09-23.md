---
type: research
status: diagnosis complete; implementation not performed
created: 2026-09-23
tags: [agent-platform, projection, regression]
---

# Projection fallback regression: bootstrap read acquisition

The system already derives populated projections from immutable database values; bare store tests supply their construction projection at acquisition. The live form below shows the same persisted marker refusing through raw dereference and succeeding through `seon.db/db`.
The smallest composition is the existing database acquisition followed by the existing read; preserve release cleanup and do not add a cache, stamp, registry, or reopen reconstruction.

## Result and evidence boundary

**The suspected leak-fix cause is refuted for the nine projection-only red members.**
They read bootstrap stores with native Datahike attribute declarations but **no
Seon declaration rows**. Raw `@connection` bypasses Seon's cold acquisition
boundary. String decoding asks for the logical projection and correctly finds none.
This happens on the first open, before any release, as well as on reopen.

The smallest repair is a **caller conversion to the existing owner**:
`(db/q query (db/db connection))` in these cold-store tests, under their already
supplied construction projection. No production owner change is justified by this
evidence. This qualifies for the one-seam obvious-fix review exception if launched
with that exact scope. A redesign of cold projection transport does **not** qualify.

Evidence: `tmp/orchestrator/proof-1600.txt`, run `8a8a241e71e4`
(36 executed, 4 reused; 205 passing assertions, 37 failures, 3 errors; 120504 ms).
There are 13 red test members, not 13 identical defects.

At diagnosis, `bin/seon status` and MCP `runtime_status` both found pid
27531, started 2026-09-23T16:41:24.235Z, default alive with no missing layers.
The loaded source is the committed archive
`data/source/33957320955a9fff65db3fa067ba3d4d40555e85`, not the working tree;
hook publication is off. Checkout HEAD observed during reading was `84f12b6fb`.
The relevant db/store source matches the loaded commit. Unrelated dirty files,
including `test/seon/cluster/store_test.clj`, were neither adopted nor changed.

One JVM MCP reproduction used explicit root/cluster and a private session and
throwaway namespace. It opened and wrote only its own temporary store, released it,
reopened it, and released it again. Default's branch and Vars were untouched.
The request was not marked `read_only:true`: despite being read-only toward
default, it really writes the disposable store. MCP also reports a result blob;
this is not evidence that its output transport is side-effect-free.

## Causal chain and history

Source anchors below refer to the db/store bytes shared with loaded `339573209`;
test failure line numbers refer to that archive, not another lane's edited file.

1. `test/seon/cluster/store_test.clj:38-52` installs only the native marker
   and measurement schema and reads through raw dereference.
   `test/seon/cluster/export_test.clj:41-54` does the same for its marker.
2. `src/seon/cluster/store.clj:418-516` opens and verifies a Datahike
   connection; it does not publish a Seon schema population.
3. `src/seon/db.clj:1496-1511` creates the read's delayed projection;
   `:1575-1587` forces it for string storage, whose logical type could require
   EDN decoding. Installed `:db.type/string` alone cannot distinguish an
   ordinary string from Seon's encoded logical value.
4. `carried-projection` (`:1420-1441`) sees no declaration population and
   calls `construction-projection` (`:1407-1418`). That path requires the
   construction projection; it cannot derive declarations that were never stored.
5. `db/db` (`:2034-2053`) already delegates to `resolve-database-value`
   (`:301-313`), capturing the supplied construction projection for the read.
   The write path uses this acquisition too (`:4521`), explaining why the
   test writes succeed while subsequent raw reads refuse.

**Introducing change:** `git show 9b8c5b405 -- src/seon/db.clj` shows
`read-declarations` changing from carried projection OR
`schema/handed-projection` to `carried-projection` alone. The same change makes
that function derive populated values and refuse bare values without construction
input. The raw-store callers were not converted. This is a source-history
identification, not an executed parent/child bisect.
`ca9c40639` subsequently names the remaining cold path
`construction-projection`; its commit message explicitly retains that cold
exception pending explicit constructor inputs.

**36cfeb2d3:** no db.clj change. Its store change is solely
`release-branch!` (`src/seon/cluster/store.clj:572-582`), dropping the
projection-state metadata after final release. The main-store reopen test uses
`release-store!` (`:518-535`), which calls Datahike release directly.
The independent-store test fails without a preceding release. The live probe had
no connection projection-state metadata even before release. Do not revert the
leak fix or make released connections retain projections.

**f0c43bbe4:** changes only the as-of branch of `carried-projection` to
include the uncommitted origin's revision key and time point. These raw DB reads
take the no-declarations branch first. No evidence implicates this change.

**20d317e19:** introduces the separate injected-Error durability regression and
pins konserve/superv.async changes. It does not explain the projection refusal:
the probe commits its writes and reads the marker successfully with correct
acquisition. Its injected-Error/release behavior remains a distinct investigation.

## Dependency seam, costs, and retained guarantees

Loaded gitlinks: Datahike `c79cd03a44427ac1734d917c7484c3e529c77716`,
konserve `5b39fddd6ae58bae7d08880daecd1ad7242ea273`,
superv.async `501b42945693804fd3e57276cc2a07ed4fe1d602`.

Datahike's `Connection` delegates dereference to its own database and metadata
to its wrapped atom (`reference-code/datahike/src/datahike/connector.cljc:39-48`).
`connections.cljc:124-128` releases the value and removes the registry entry;
it does not supply Seon's logical projection. This is why Seon's final branch
release explicitly drops its metadata reference. These files are the pinned
fork's evidence; no claim is made that every fork acquisition behavior is upstream.

The populated-value owner is already memoized:
`src/seon/db.clj:1370-1390,1456-1493` composes content/revision/commit keys;
`src/seon/schema.clj:3331-3354` derives from stored declaration rows.
Datahike's `committed-value-identity` (`db.cljc:385-400`) and revision context
provide the existing identities; this named helper is fork code, not asserted to
be an upstream API. On reopening a populated store the value takes that derivation
path without recovering Seon connection metadata. The supplied proof does not
show a failure on that path.

Cost target: cold test acquisition is one connection dereference and an existing
projection reference capture, O(1) in store/program size; no declaration scan,
compilation, or new cache. The unchanged populated path reuses its revision/commit
entry; a miss reads declaration content and recompiles its changed dependency
closure. Query work remains proportional to the query/result, not a full program
rebuild per read. No recomputation event is added by the proposed conversion.

This removes the caller's dependence on an ambient projection being consulted
later by the decoder. It adds no ownership mechanism. The existing cold acquisition
still uses compatibility metadata internally; this proposal neither introduces nor
extends stamps. Removing that compatibility belongs to the reviewed 1.4 cold-owner
sweep, not this regression repair. README §7's memoized-function ruling cannot
manufacture absent logical declarations in a bootstrap-only store.

## Every red classified

“Retired assumption” below means convert setup/acquisition and retain the behavior
assertion. None of these 13 members is justified for deletion as deleted machinery.

| Namespace / test | Verdict under the three questions | Repair / projection-fix coverage |
|---|---|---|
| store / a-jvm-hosts-independent-stores | Retired raw-acquisition assumption; independence survives | Convert marker helper. Resolves both observed read failures. |
| store / branch-connections-inherit-the-root-history-representation | Retired raw-acquisition assumption; branch history configuration survives | Acquire once with db/db for the Seon read. Resolves observed current-read refusal. |
| store / creation-never-deletes-a-complete-store-or-an-undeclared-root | Retired raw-acquisition assumption; destructive admission survives | Marker helper conversion resolves observed read failure. |
| store / genesis-window-repairs-by-recreate | Retired raw-acquisition assumption; genesis recovery survives | Marker helper conversion resolves observed read failure. |
| store / open-write-release-reopen-preserves-data | Retired raw-acquisition assumption; durability survives | Marker helper conversion; live reproduction proves marker survives. |
| store / transact-normalizes-only-jdk-integers | Retired raw-acquisition assumption; integer normalization survives | Convert direct query at archive :350-356. The second failure inspects the error map's values, not stored integers. |
| store / an-error-inside-one-store-write-fails-the-commit-and-keeps-the-prior-head | Wanted surviving durability/release behavior, with an invalid panic setup assumption | Not resolved by projection conversion; detail below. |
| export / an-export-is-an-independent-openable-store | Retired raw-acquisition assumption; independent writable export survives | Convert export marker helper; covers source, exported main, branch, and post-export reads. |
| export / fallback-reuses-an-already-connected-branch | Retired raw-acquisition assumption; reuse survives | Same helper conversion resolves observed refusal. |
| export / reidentify-is-idempotent-on-a-store-that-already-fits-its-path | Retired raw-acquisition assumption; reidentification/branch seeding survives | Same helper conversion resolves both observed reads. |
| context-selection / compact-replaces-only-observed-evaluation-refs | Retired minimal namespace-fixture assumption; compaction survives | Seed a canonically analyzed namespace with required definition digest; projection conversion does not help. |
| context-selection / selection-references-terminal-evaluations-in-writer-decided-order | Retired minimal agent-fixture assumption; writer ordering survives | Use canonical agent-tx and transacted!; projection conversion does not help. |
| reconcile / apply-then-reapply-converges-over-generated-populations | Wanted surviving convergence behavior; deadline failure is not proof of obsolete machinery | Diagnose bounded execution/fixture cost; projection conversion has no established effect. |

Context-selection detail: archive `test/seon/context_selection_test.clj:33-35`
seeds only agent ids, while `resources/seon/schemas/seon.agent.edn:50`
requires branch. The seed assertion fails at :59; every subsequent no-such-agent
result and timestamp-sensitive error-map comparison is downstream of the absent
seed. The log does not print the seed refusal, so the exact first refused field is
source-supported, not independently replayed. Use `test-support/agent-tx`
(`test/seon/test_support.clj:697`) and surface every fixture refusal at its write.
Compaction's log explicitly identifies the namespace digest missing from
`{:seon.ns/name compact.context}` at archive :129, required by
`resources/seon/schemas/seon.ns.edn:17`. Do not invent a placeholder digest;
use the canonical analyzed declaration fixture.

Injected Error detail: archive store_test :146 expected a throw but observed none.
`panic-on-core-error?` (`src/seon/db.clj:2901-2906`) queries an installed
configuration fact and returns false when this bare store has none.
`transact-call` (`:4312-4345`) can therefore return the whole fault instead.
That explains the throw assertion from source, without proving its actual returned
envelope. A concurrent lane already has a dirty correction to this assertion;
it was inspected only, not adopted or edited.

The subsequent same-process flock refusal is **not proven to cascade from projection**.
The test accepts a failed release and immediately reopens; `release-store!`
explicitly retains the fence when Datahike release throws. Keep the fence guarantee.
Capture the complete injected-write and release outcomes and prove writer exit
before deciding cleanup. Never “fix” this by releasing a fence around a live writer.
The existing test's durability assertion remains necessary; no claim of a complete
durability fix or of intentional panic-default semantics is made here. The absence
of configured panic also exposes a mismatch with the stated default policy worth
the error-policy owner's review.

Reconcile detail: `test/seon/reconcile_test.clj:284-303` performs 60 generated
cases, each acquiring with-model-database (:25), seeding, and reconciling twice.
The run hit its remaining 95901 ms bound and retained live thread 550 / branch
`:agent-930b687370ae`. This establishes timeout without exit, not a deadlock,
a projection cause, or nonconvergence. Work scales with generated cases and their
fixture/write costs; the log has no phase profile to apportion it. Keep the test,
measure one case and the owning functions before changing execution or sample count.
Do not widen the timeout or repeat the known long run as diagnosis.

## Smallest fix and required proof

A mechanical conversion of the two marker helpers plus the two direct store-test
query inputs uses the existing `db/db` owner. Retain supplied construction
projection scope, all durability/export/history assertions, and the leak regression.
Read inputs should capture a value once per operation. No new production code or
source growth is needed.

Expected scope: **nine red members / all recorded projection-only failures**.
It does not resolve the injected-Error member, either context-selection member,
or reconcile timeout. That statement is a design prediction; only the independent
store before/after-reopen path has been replayed here.

After implementation, submit one focused `seon.test/run` request for the nine
members on the assigned branch through the installed test entrance; capture its
run id and full results. Keep the released-node-cache retention regression as
the leak guarantee. The orchestrator owns platform/cold verification. A full
store namespace run includes child-process drills and is not this probe.

An actual populated-store derivation defect would require different evidence:
persisted declaration rows, value identity and the failed derivation. Fix
`carried-projection`/its memo key in place only if that evidence exists.
A redesign beyond the caller conversion needs independent review.

## Exact live reproduction

MCP: root `/Users/sean/src/seon`, cluster `default`, mode `jvm`,
namespace `diagnosis.projection-fallback-20260923`, session
`projection-fallback-diagnosis`, timeout 10000 ms.

```clojure
(let [started (System/nanoTime)
      projection (seon.db/carried-projection @(seon.cluster.boot/connection "default"))
      dir (str "/Users/sean/src/seon/tmp/projection-fallback-diagnosis-" (random-uuid) "/store")
      query '[:find [?marker ...] :where [_ :seon.store.test/marker ?marker]]]
  (seon.schema/call-with-projection projection
    (fn []
      (let [opened (seon.cluster.store/open-store! {:seon.store/dir dir})
            connection (:seon.store/connection-object opened)
            first-read
            (try
              (let [schema-report (seon.db/transact! connection
                                    [{:db/ident :seon.store.test/marker :db/valueType :db.type/string
                                      :db/cardinality :db.cardinality/one :db/unique :db.unique/identity}])
                    report (seon.db/transact! connection [{:seon.store.test/marker "survives"}])]
                {:schema-write? (some? (:db-after schema-report))
                 :write? (some? (:db-after report))
                 :has-declaration-attribute? (contains? (:schema @connection) :seon.schema/key)
                 :connection-state? (some? (:seon.sci.eval/projection-state (meta connection)))
                 :raw-read (seon.db/q query @connection)
                 :acquired-read (seon.db/q query (seon.db/db connection))})
              (finally (seon.cluster.store/release-store! opened)))
            reopened (seon.cluster.store/open-store! {:seon.store/dir dir})]
        (try
          (let [connection (:seon.store/connection-object reopened)]
            {:dir dir :before-release first-read
             :after-reopen {:raw-read (seon.db/q query @connection)
                            :acquired-read (seon.db/q query (seon.db/db connection))}
             :elapsed-ms (/ (- (System/nanoTime) started) 1e6)})
          (finally (seon.cluster.store/release-store! reopened)))))))
```

Returned: schema-write? true; write? true; has-declaration-attribute? false;
connection-state? false. Before release and after reopen, acquired-read was
`["survives"]`. Both raw reads returned:

```clojure
{:seon.db/invalid-read true
 :seon.db/refused-read-operation seon.db/q
 :seon.error/at #inst "2026-09-23T16:47:30Z"
 :seon.error/layer :seon.schema/projection
 :seon.error/message "This operation requires a carried schema projection."
 :seon.error/operation seon.db/projection-fallback
 :seon.schema/expected-value :seon.schema/projection
 :seon.schema/refused-value #:seon.db{:operation seon.db/carried-projection}}
```

The complete MCP envelope had two matching warning events, a successful ret event
(117 ms), alive cluster state, elapsed-ms 111.304792, and result blob
`261e93976b0b76b4d813deeae9ed442af3e2eb23087a8faba8828b12ec177c2f`
(4247 bytes). No exception event. Both stores were released in finally.
The unique temporary directory was checked with lsof (no open handles) and removed
without following symlinks. No other test root or session was touched.

## Timing, limits, and landing

| Operation | Time | Interpretation |
|---|---:|---|
| bin/seon status plus bounded shell reads | 157 ms | Existing process only; no boot/adoption. |
| MCP reproduction body / prepl event | 111.305 / 117 ms | Includes independent open, two writes, read comparisons, release and reopen; no per-phase profile requested. |
| Existing proof run, not rerun here | 120504 ms | Defect: seon.test/run and runner exceeded the budget; case/fixture work is a cost hypothesis, not a justification. |
| Existing reconcile remaining deadline | 95901 ms | Defect: no exit; retain ownership until termination, profile the owner before continuing this test. |

The status profile attributes the existing long run to seon.test/run (120504 ms),
runner/run-var! (max 105252 ms), and db/call-with-custody (max 105199 ms),
all inclusive. These are prior-run observations, not new operations of this lane
and not proof of self time. Shared issue notes are orchestrator-owned; this row
extends the evidence for the existing test-cost schedule without editing its files.

No new test suite, code reload, publication, provider call, or secondary JVM.
No parent/child executable bisect, heap/collection measurement, export replay,
fault injection replay, or new reconcile profile. Cache hit/miss counts were not
exposed by this bounded probe; none are invented. The REPL skill's general checkout
hot-reload description does not describe this archive-backed JVM; installed status
is the authority, and no adoption was attempted.

Only this research note is changed. Net src lines: 0. Net test lines: 0.
The automatic Markdown lint reported 46 issues (45 errors), including stale
dependency pins in other landing notes; its feedback was truncated. Those files
are outside this assignment. This note's path-scoped diff whitespace check passed;
no repository-wide Markdown-clean claim is made.
Commit is path-limited; no push. This note extends the scheduled diagnosis in
`docs/research/agent-platform/fix-schedule-2026-09-23.md:267` and the cold-input
class in `docs/seon/issues/read-and-admission-producers-still-require-thread-projections.md`.
No reset is needed for this diagnosis or the proposed acquisition conversion.
