---
type: landing
status: verified; shared-schema boundary cleared; landing
created: 2026-09-23
---

# Entrance supplier — track 1.3d commit 4 prerequisite

Read first: commit 4's stop (`2cf063b75`,
`lane-realities-commit-4-2026-09-23.md`), then commit 2's handle and proof
(`1ada78050`, `lane-realities-commit-2-2026-09-22.md`).

The existing acquisition entrance carries its held store and context-state into
its base environment before the agent fork. The existing supplied-context
producer forwards those exact objects, the executing connection, cluster name and
base context. Supplied contexts can therefore serve as acquisition sources.
The supplied context's existing three required members remain compatible; the
new members are optional for existing explicit host callers. Environment and
context-source members are declared in their owning schema resources.

No test runner, database custody, SCI evaluator, cluster, source publication or
instrumentation owner is edited. The entrance retains commit 2's existing two-
and three-argument calls; the supplier has one arity. A single-request migration
would require converting excluded callers and was raised for clarification.

## Starting runtime and dependency evidence

`bin/seon status` and MCP `runtime_status`, explicit repository root and cluster
`default`: PID 51528, start 2026-09-22T18:46:42.624Z, no missing readiness layers,
14 errored receipts. Default was not written, reloaded, stopped or reset.
No MCP tool was missing. Read-only JVM probe, 2 ms:

```clojure
(let [connection (seon.cluster.boot/connection "default")
      database (seon.db/db connection)]
  {:commit (seon.db/commit-id database)
   :entrance (:arglists (meta #'seon.cluster.agent/acquire-context!))
   :context-schema
   (get-in (seon.db/carried-projection database)
           [:seon.schema.projection/forms :my.program/context])})
```

Returned commit `6ab2daa6-29a9-5eac-bb98-5018e7684d69`, old entrance
`([handle agent-id])`, and the three-member context schema. This predates commit
2 adoption and is starting evidence only.

Datahike gitlink `cc2b2bc7dbe774ea1bcc7487e8225a0d420d0e17`,
`reference-code/datahike/src/datahike/versioning.cljc:212`: branch creation takes
the held connection, retained commit and fresh branch name under the roster
permit; secondary indices use copy-on-write. SCI gitlink
`fcbd8862800e638dc0f8f5521111f999279cbcd2`,
`reference-code/sci/src/sci/core.cljc:345`: fork replaces its environment atom
with a fresh generation. First-party composition remains `acquire-context!`.
This change carries two references per acquisition/supply; it adds no scan,
lookup, branch owner, cache or context constructor. Fork/rearming costs remain
those of the existing entrance. Dependency working trees were not edited.

## Focused iteration

Command:

```sh
bin/test-fast --paths src/my/program.clj src/seon/cluster/agent.clj \
  resources/seon/schemas/my.program.edn resources/seon/schemas/seon.env.edn \
  resources/seon/schemas/seon.agent.edn test/seon/cluster/entrance_supplier_test.clj \
  -- seon.cluster.entrance-supplier-test seon.cluster.acquisition-test
```

HEAD `ad72fa0941cc8af419f5480b9fa9c44ef0309384`, snapshot `run.QGm1YT`;
1,702 contracts armed, 1,689 program-armable; recorded run `eb8f9edac9de`,
2 executed, 0 reused, 0 failures, 2 errors. Both tests refused their first agent
fixture write because the exported base lacks installed `:seon.agent/branch`.
No acquisition behavior passed. This is the same stale exported-schema boundary
observed by commit 2; the writer was not bypassed. No recording refusal occurred.

The launcher explicitly used HEAD bytes for foreign cluster/db/program callers.
Its named agent schema also contained the pre-existing foreign partition
annotation. The later frozen source excludes that foreign hunk. Other lanes'
files and sessions were not edited, resumed or contacted.

## Cold schema proof and remaining producer boundary

A frozen `git archive` of the above HEAD plus only owned edits is under
`tmp/entrance-supplier-source`, with linked `reference-code`. The required
from-zero boot uses `tmp/entrance-supplier-root`; exact log:
`tmp/orchestrator/entrance-supplier-boot.log`. Its status, final proof and cleanup
are recorded below when complete.

Source observation: `src/seon/cluster/boot.clj:63` constructs the initial live
environment without the held store; `src/seon/cluster.clj:2774` constructs
context-state on the loop handle. The entrance can carry context-state from that
handle, but cannot invent the absent store. The initial boot producer requires
adding the already-held `(:seon.store/store instance)` to its environment.
At the first stop, permission for that additional producer path was pending; no global lookup or
test-side store substitution was added to conceal this boundary.

## Completed evidence

From-zero boot: PID 85262, start `2026-09-22T19:48:38.292Z`, source commit
`6ab2dc32-59ce-5f25-91ae-e57e9043d128`, readiness **143,278 ms**, no missing
layers. The process was absent after its launching shell ended; no later proof
is attributed to that process. Restart in retained shell: PID 86318, start
`2026-09-22T19:52:17.513Z`, readiness 11,297 ms. Two final changed files were
adopted on the scratch root; final source commit
`6ab2dcd1-072a-5426-a8d4-bcea7fb40126`. The schema was installed by the cold boot,
not a warm registration bypass. `entrance-supplier-cold-schema.json` records
installed branch/context schemas and the supplier's single arity (2 ms).

The scratch store was exported by `seon.cluster.export/export!` to
`tmp/entrance-supplier-fixture/data/store` (1,000 ms). The canonical fixture used
that actual exported population. No runner or shared base cache was changed.

The first recorded scratch run, `eb26686b1d5b`, stopped before assertions because
the new test retained the recording cluster's custody while writing its fixture
branch. The test was corrected to enter the fixture's connection through the
existing `db/call-with-custody`, exactly as the commit-2 regression does. This
was a test setup error, not an entrance change.

| Final recorded request | Assertions | Result | Timing |
| --- | --- | --- | --- |
| Supplier regression `7025d8ba352a` | 5 pass | 0 fail, 0 error; captured commit matches, branch positively rostered, isolated connection, carried object identities, evaluation returns 42, branch absent after synchronous exit/release | 1,814.983 ms supplied caller + isolated acquisition/evaluation/release; MCP envelope 7,171 ms including fixture, arming and recording |
| Unmodified commit-2 regression `063924725b51` | 24 pass | 0 fail, 0 error; proof (a)'s live/isolated visibility unchanged; setup unwind and MCP branch cases also pass | acquisition 1,411.904 ms; unchanged reacquisition 12.491 ms, 0 rows; visibility/MCP 9,113.000 ms; release 10.495 ms; unwind 29.149 ms; MCP envelope 12,656 ms |

Both were fresh executions through `seon.test/run` on the scratch JVM, with
1,825 armed contracts and 1,689 program-armable functions. Program digest
`ce0336012c9b6711e26d97f9d593a241cee47714ccd7c443f392f188d608a0bf`, input digest
`fb536d51ed7af4b49ca2f7512300838db03d29fcde99a5654f6ab8ce9652a6a9`.
Completed transactions 536870983 and 536870985. No recording refusal occurred.
Full tool envelopes, exact forms and returned tallies:
`tmp/orchestrator/entrance-supplier-proof-1.json`, `entrance-supplier-proof-2.json`,
`entrance-supplier-live-proof.json`. The supplied-default call uses real SCI's
call-preparation hook, returns to its host caller, then the isolated evaluation
uses `sci.eval/evaluate`; this is not a nested evaluation under a different arm.

Observed acquisition heap delta in the unchanged commit-2 regression:
+906,859,776 bytes. Scratch JVM RSS after both proofs: 5,511,680 KiB. These are
point-in-time observations, not retained-memory bounds. The change itself carries
two existing references; no new context/cache owner was introduced.

No platform/cold gate or integration suite ran. No browser-paint or whole-cluster
health guarantee follows. Repository default has not adopted these edits.

## Commit boundary

The final inspected `git diff -- src/seon/cluster/agent.clj` contains only this
lane's three acquisition-member hunks. The owning agent schema still also holds
a foreign in-flight `:seon.program/partition :seon.data` annotation. Root
instructions permit a shared file commit only when its hunks are exclusively
owned, so this coherent schema/source slice is not committed while that hunk
remains. The foreign hunk was neither removed nor included in the frozen proof.
At the first stop, the additional boot producer permission remained unanswered. The proof establishes
supplied acquisition when the entrance receives the held store; it does not claim
that the initial live boot now supplies that store.

Cleanup: `bin/seon --root tmp/entrance-supplier-root down --force` verified
PID 86318/start identity and returned `:seon.operator/process-exit? true`.
The retained shell exited 0. Both scratch PIDs are absent. `lsof +D` returned
empty holder evidence for the owned root, fixture export and frozen source;
all three were removed without following symlinks. Frozen HEAD and exact owned
patch remain in `tmp/orchestrator/entrance-supplier-source-head.txt` and
`entrance-supplier-owned.patch`. All owned exec sessions have exited.

Source/schema delta: +29/−7 across five files; regression 89 lines. The raw
owned patch is authoritative for exact sizes. `git diff --check` passed. No implementation commit was claimed at that first stop. The following authorized
completion closes its live boot producer boundary.

## Authorized boot producer completion

The owner authorized the already-held instance store on 2026-09-23. The current
constructor is `src/seon/cluster/boot.clj:63`, not `cluster.clj:2774` (the latter
is the loop handle and has no `instance` binding). Added exactly one environment
member: `:seon.store/store (:seon.store/store instance)`. No cluster registry
lookup and no `cluster.clj` change. Its foreign refresh-result hunks remain
untouched. The earlier pending-permission boundary is resolved by this ruling.

The new from-zero root is `tmp/entrance-supplier-live-root`, frozen source
`tmp/entrance-supplier-live-source`, HEAD in
`tmp/orchestrator/entrance-supplier-live-source-head.txt`. It contains HEAD plus
only the seven owned source/schema/test paths. Foreign schema and cluster hunks
are excluded. This is the missing live producer proof, not another integration
gate. Boot and probe evidence follows.

Final cold boot: **PID 88935**, start `2026-09-22T19:58:11.765Z`, readiness
**136,799 ms**, no missing layers; source commit
`6ab2de5e-9034-551c-adfa-205274874c87`. Frozen source HEAD
`6c578954d`. Its bootstrap turn was positively closed at transaction 536870943.
Read-only initial inspection (3 ms) returned true for initial execution present,
boot environment's store identical to the instance store, and initial execution's
store identical to the instance store. No manual acquisition source was built.

The booted root agent's existing ctx then executed the ordinary supplied-default
caller through SCI. That returned context alone acquired an isolated handle from
the captured commit, evaluated `(+ 20 22)` to **42**, and released it. Captured
commit equality, distinct branch connection, store/context-state/connection
identity, positive roster membership and subsequent unlink all returned **true**.
Elapsed span **1,061.808 ms** (MCP 1,064 ms). The first probe's additional
live-commit comparison returned false; it does not prove the live head stayed
unchanged, and no cause is attributed from that comparison alone.

A second exact probe captured before/after database values: both had commit
`6ab2debf-e756-5c7e-a8e0-27c165b45d95` and basis **536870978**; the since-view
had **zero changed attributes**. All isolated proof fields again returned true,
result 42; MCP elapsed **1,130 ms**. This proves the read evaluation left live
facts unchanged on that attempt. Branch allocation/release necessarily mutates
roster bookkeeping, so its MCP request was honestly not labelled `read_only`;
the separate initial inspection was. No program definitions, tests, schemas or
live data were written by the probe.

Exact source and full envelopes are retained in
`tmp/orchestrator/entrance-supplier-live-probe.clj`,
`entrance-supplier-live-initial.json`, `entrance-supplier-live-boot-proof.json`,
and `entrance-supplier-live-boot-repeat.json`. The exact final owned source patch
is `entrance-supplier-live-owned.patch`. These supplement the previously recorded
29 passing assertions; no additional suite was needed for the one-line producer.

Final scratch RSS observation: **4,654,864 KiB**, not a retained-memory bound.
`bin/seon ... down --force` verified the exact PID/start and reported actual
process exit. The retained shell exited 0; PID 88935 is absent. Empty `lsof +D`
evidence for the owned source/root preceded their removal without following
symlinks. No owned JVM, shell or scratch store remains. Repository default was
not written, reloaded or stopped. Platform and integration remain orchestrator-owned.

The only remaining landing dependency is the foreign agent-schema partition
hunk. The owner's bounded poll checks at most once per minute for up to 25
minutes; commit/expiry outcome is recorded below.


## Landing after the bounded wait

The partition property landed in **`8a069b5e4`** at the minute-23 check. The
following host-bound and reload-classification commits do not belong to this
slice. Final `git diff -- resources/seon/schemas/seon.agent.edn` contains only
the owned context-source members. Final `git diff -- src/seon/cluster/agent.clj`
contains only the three owned acquisition-member hunks. The actual one-line
producer file, `src/seon/cluster/boot.clj`, contains only that owned line.

The requested `git diff -- src/seon/cluster.clj` check shows unrelated in-flight
publication-monitor deletion hunks. This file was not edited by this lane and
is excluded from the commit; the environment constructor is in `cluster/boot.clj`.
No foreign hunks are staged. The bounded poll has ended. Implementation/source
size is +30/−7 across six files, plus the 89-line regression. Evidence reflects
the exact frozen HEADs named above, not later unrelated concurrent changes.

## Post-landing live proof at committed HEAD (successor lane, 2026-09-22)

Subject: committed bytes only. Frozen `git archive` of HEAD
`a614fb898b43db2506ba00655f8ceb4975d2b2c4` (descendant of landing commit
`7f6718507`; the seven owned paths are unchanged between them) into
`tmp/es2-source`, `reference-code` symlinked; no working-tree hunk of any lane
included. Fresh empty root `tmp/es2-root`.

From-zero boot: `tmp/es2-source/bin/seon --root tmp/es2-root start supplier`,
exit 0, wall 160.44 s; PID **8371**, start `2026-09-22T20:39:45.556Z`, readiness
**145,701 ms**, `:seon.boot/missing-layers []`, problems `{}`, source commit
`6ab2e81f-519f-5161-a508-0474d701783c`. The JVM remained alive after the launching
shell exited. Log: `tmp/orchestrator/entrance-supplier-es2-boot.log`.

Live probe (MCP `eval_clj`, JVM mode, root `tmp/es2-root`, cluster `supplier`):

```clojure
(let [instance (get @seon.operator.runtime/running-instances "supplier")
      cluster (:seon.turn.loop/cluster instance)
      connection (seon.cluster.boot/connection "supplier")
      branch (seon.cluster.registry/cluster-branch "supplier")
      execution (get @(:seon.agent/context-state cluster) [branch "root"])
      ctx (:seon.sci.eval/ctx execution)
      before (seon.db/db connection)
      started (System/nanoTime)
      context (sci.core/eval-string* ctx "(seon.cluster.entrance-supplier-test/caller-context)")
      result (seon.cluster.entrance-supplier-test/supplied-acquisition-proof context)
      elapsed (/ (- (System/nanoTime) started) 1e6)
      after (seon.db/db connection)]
  {:initial-acquisition-present? (some? execution)
   :boot-environment-store? (identical? (:seon.store/store instance) (:seon.store/store (seon.env/of (:seon.sci.eval/ctx instance))))
   :initial-acquisition-store? (identical? (:seon.store/store instance) (:seon.store/store execution))
   :supplied-store? (identical? (:seon.store/store instance) (:seon.store/store context))
   :supplied-context-state? (identical? (:seon.agent/context-state cluster) (:seon.agent/context-state context))
   :supplied-connection? (identical? connection (:seon.db/connection context))
   :commit-before (seon.db/commit-id before) :commit-after (seon.db/commit-id after)
   :basis-before (seon.db/basis-t before) :basis-after (seon.db/basis-t after)
   :changed-attributes (frequencies (map :a (seon.db/datoms (seon.db/since after (seon.db/basis-t before)) :eavt)))
   :isolated-proof result
   :elapsed-ms elapsed})
```

Returned (MCP 2,344 ms; span 2,143.097 ms): initial acquisition present, boot
environment store, initial acquisition store, supplied store, supplied
context-state and supplied connection all **true**; `:isolated-proof`
`{:captured? true :carried? true :isolated? true :rostered? true :unlinked? true
:value 42}`. No manual acquisition source was built: the booted root agent's own
SCI ctx produced the supplied context, which alone acquired, evaluated and
released the isolated branch.

The live head advanced one transaction (basis 536870932 -> 536870933, commit
`6ab2e841-...` -> `6ab2e842-...`): one `seon.cluster.eval` row. A read-only
follow-up listing `:seon.cluster.eval/source` in that since-view returned the
root agent's own run forms `(seon.agent/settings)` and its `seon.db/pull` of
`:seon.message/_to`, not the probe's form; that write is the concurrent root
loop's, and the probe wrote no program or live facts. (Unlike the earlier
repeat, this attempt did not capture a quiescent live head.)

Point-in-time RSS after the probe: 3,586,464 KiB (not a bound).
Cleanup: `bin/seon --root tmp/es2-root down --force` exit 0,
`:seon.operator/process-exit? true` for PID 8371 / exact start; PID absent;
`lsof +D` empty for root and source; both removed without following symlinks.
The predecessor's unrecorded committed-HEAD roots
(`tmp/entrance-supplier-committed-{root,source}`, PID 99598 already down,
empty holders) were also removed; their envelopes stay in
`tmp/orchestrator/entrance-supplier-committed-*.json`.

Boundary: one live scratch cluster at committed HEAD; no platform or integration
tier ran; repository default (PID 51528) was not touched and has not adopted
this slice. RESET NEEDED: not by this lane.
