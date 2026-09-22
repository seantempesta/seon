---
type: landing
status: landed
created: 2026-09-22
---

# Realities commit 2 — acquisition only

Scope: the existing agent acquisition entrance, explicit branch attribute,
connection/environment fork, owned release, MCP branch selection and one `my.*` read.
No merge, test-runner or publication implementation changes. Hook publication is
paused; this slice is not live on repository `default`.

## Starting evidence

Repository HEAD at first observation: `be4c7cafc`; commit 1 is `39a337013`.
Unrelated documentation changes existed at entry. Later HEAD and foreign
`test/seon/test/selection_test.clj` changes were preserved; the focused launcher
explicitly reported using HEAD bytes for that foreign file.

`bin/seon status` and MCP `runtime_status` selected root `/Users/sean/src/seon`,
cluster `default`, PID 51528, start 2026-09-22T18:46:42.624Z. Missing readiness
layers were empty and problem counts empty. Turn proc ping was `unknown`, not
healthy. Both tools answered; no missing MCP tool was worked around.

Read-only JVM form (2 ms):

```clojure
(let [connection (seon.cluster.boot/connection "default")
      database (seon.db/db connection)]
  {:commit (seon.db/commit-id database)
   :root (seon.db/pull database '[:seon.agent/id] [:seon.agent/id "root"])
   :branch-schema (get (seon.db/carried-projection database) :seon.agent/branch)
   :acquisition-arglists (:arglists (meta #'seon.cluster.agent/acquire-context!))
   :branch (get-in database [:config :branch])})
```

Returned commit `6ab2ce48-f774-5dad-abd5-014796dcc6e2`, root id `root`, no branch
schema, arity `[handle agent-id]`, branch `:cluster-default`. An earlier raw
`@connection` pull refused the missing carried projection (3 ms); replacing it
with the owning `seon.db/db` read corrected the probe. No default write or reload.

## Dependency guarantees

Datahike gitlink `cc2b2bc7dbe774ea1bcc7487e8225a0d420d0e17`:
`reference-code/datahike/src/datahike/versioning.cljc:212` branches from a keyword
or retained commit UUID under the roster reachability permit; immutable primary
index roots are reused and secondary indexes branch from the selected root.
Inputs are held store connection, captured commit and fresh destination keyword.
Creation does not construct an agent context. `connections.cljc:37` shares an
existing compatible connection through reference counting; Seon's
`store/open-branch!` rejects that accidental second owner. `registry/branch!`
reports `created? false` for collisions; acquisition must not treat that as
ownership. `registry/retire-branch!` requires connection release and only unlinks.

SCI gitlink `fcbd8862800e638dc0f8f5521111f999279cbcd2`:
`reference-code/sci/src/sci/core.cljc:345` allocates one environment atom and a
new generation. Connection custody and Seon's environment still need explicit
repointing in `fork-cluster-ctx`. Its contract rearming loop is proportional to
installed interpreted functions. Program acquisition is the existing commit-1
owner; unchanged reuse compares the committed database identity.

## Verification log

- Required four-namespace load completed twice, exit 0.
- First focused command:
  `bin/test-fast --paths src/seon/cluster/agent.clj src/seon/sci/eval.clj script/seon/dev/mcp.clj resources/seon/schemas/seon.agent.edn src/my/agent.clj src/seon/turn.clj test/seon/test_support.clj test/seon/cluster/acquisition_test.clj -- seon.cluster.acquisition-test`
  Snapshot `tmp/test-runs/run.j2CkFc`, base HEAD `79f4d5601`, 1,697 contracts
  instrumented, 1,688 program-armable. One execution, one error, no behavior pass.
  Test body elapsed 2,849 ms. Fixture write refused undeclared
  `:seon.agent/branch` on the old published base. This tests wanted behavior with
  a stale schema population; no fixture bypass or hand-rostered schema was added.
- Scratch reset first refused an absent operator directory. Created the requested
  directory and repeated the authorized command. PID 56799, start
  2026-09-22T18:58:50.642Z, PREPL 51045. Boot proof pending.

Six acceptance results, exact timings, final limits and commits follow after proof.

## Landed implementation

Implementation commit **`1ada78050`** (11 paths, +469/−69). Commit 1 remains
`39a337013`. The frozen proof checkout was HEAD
`d26af4977bcea6d05b578700a067c53ba7504303` plus these owned paths, made with
`git archive`, dependency symlinks and copied owned files; no worktree was created.
The final test-only edits were adopted on the scratch JVM before the final green
run. Final adoption returned source commit
`6ab2d6df-730c-5020-b799-dfc7c918177c`. The final MCP description edit is text only.

The execution handle populates cluster/name, context-state, db connection,
captured db, source commit, projection, ctx, base-ctx, environment, agent branch,
derived mode, owns-connection?, owns-branch?, and the held store when supplied.
An agent id is present for agent acquisitions. The handle itself satisfies the
entrance's context-source contract. Live handles borrow the held connection and
own no resource. Isolated creation requires the caller's held store; there is no
ambient store lookup. Explicit branch selection borrows an active connection or
owns the one it opens, without owning the existing branch. Release requires the
caller to have observed actual exit. Acquisition starts no graph.

Creation writes branch explicitly, including the existing root seed through
creation-tx. Mode is derived; branch is the one stored agent attribute. The
existing arm and preview callers consume the returned handle/ctx. Fork custody
and environment repointing live in sci.eval; the fixture supplies only its base
and cluster environment. MCP's optional string branch uses this entrance and
releases after synchronous evaluation exits. `my.agent/branch` reads branch and
mode. No merge requests, runner changes, or publication changes were added.

Changed paths:

- `resources/seon/schemas/seon.agent.edn`: +30/−1.
- `src/seon/cluster/agent.clj`: +169/−22.
- `src/seon/sci/eval.clj`: +15/−3, fork region only.
- `script/seon/dev/mcp.clj`: +34/−10.
- `src/my/agent.clj`: +17.
- `src/seon/turn.clj`: +2/−2, one acquisition caller only.
- `test/seon/test_support.clj`: +13/−25, fork wrapper only.
- `test/seon/cluster/acquisition_test.clj`: new acquisition regression.
- Three existing direct acquisition test callers: run4_install (+2/−2),
  rereads_panel (+1/−1), cluster/turn (+3/−3).

## Final six-proof results

The canonical operator-population fixture is necessary because acquisition needs
an actual held store. It uses the existing fixture export/population owner,
registry branch owner, real SCI, canonical entity writes and armed contracts.
The test supplies evaluation caps and a 15,000 ms declared bound, justified next
to its declaration by the measured 10,051 ms initial full operation.

Final recorded run **`87044335a408`**: **24 pass, 0 fail, 0 error**; 1,822
instrumented contracts, 1,688 program-armable functions. The MCP envelope took
13,111 ms. Basis 536871038, completed transaction 536871040. Program digest
`7951182f247f479bd9dc56c1d08426001511f05f8de63343f17fa04a7e5de1e5`, input digest
`d875dcaa688482d13d72d693d474a1e837406945c532e0de22e3de672adefc01`.

| Proof | Observed result and timing |
| --- | --- |
| (a) visibility | A's branch-local `cut?` returns true; live B returns false. Live B changes `failed?` from false to true; both B and C see true after next acquisition; A retains false. Combined visibility and MCP span: 8,443.871 ms. |
| (b) ownership/release | A names its branch and distinct owned connection; its environment holds that connection. Roster contains branch before release and lacks it afterward; B's store still answers, including after releasing B's borrowed handle. Acquisition 1,343.338 ms; release 8.998 ms. |
| (c) unchanged head | Reacquisition through the returned handle installs **0 rows**, 10.238 ms for the two unchanged entrance calls inside the measured form. |
| (d) setup unwind | Forced `store/open-branch!` failure after branch creation propagates; exact roster before/after equal. 28.899 ms. |
| (e) MCP branch | The tool's generated form defines `c2-tool-value` on A, returns 73 there, resolves absent live, and a declared read-only call leaves branch commit unchanged. Included in combined span above. Separate real socket transport also returned 73 on a captured branch in 517 ms; details below. |
| (f) cold seed/turn | Final from-zero PID 62548, start 19:20:37.679Z, PREPL 52439: readiness **105,826 ms**, no missing layers; root explicitly `:cluster-default`; bootstrap turn `12a2b18544e6` closed at tx 536870943. **Whole-cluster HEALTHY is not proved**: later observation had three errored agent receipts and unknown turn ping. |

Memory: the final acquisition's observed used-heap delta was −2,806,664,152 bytes
because GC occurred during it; this is not retained-memory evidence. A prior
attempt measured +583,620,848 bytes. Final process RSS was 6,797,216 KiB after
10:19 uptime. No claim of bounded retained heap follows from these observations.

Exact final JVM form, root `/Users/sean/src/seon/tmp/realities-c2-root`, cluster
`default`, mode `jvm`, read_only false, timeout 60,000:

```clojure
(do
  (require 'seon.cluster.acquisition-test :reload)
  (let [connection (seon.cluster.boot/connection "default")
        projection (seon.db/carried-projection (seon.db/db connection))
        arming (seon.test.arm/initialize-contracts!
                "realities-c2-final" '[seon.cluster.acquisition-test] projection)]
    (seon.schema/call-with-projection projection
      #(seon.test/run
         #'seon.cluster.acquisition-test/acquisition-owns-only-its-isolated-resources
         connection
         {:seon.boot/cluster-name "default"
          :seon.db/connection connection :seon.test/remaining-ms 60000
          :seon.test.run/provenance
          (seon.test.runner/provenance (seon.db/db connection))}))))
```

Before this form, the installed export owner produced
`tmp/realities-c2-final-fixture/data`; the scratch JVM property
`seon.test.published-base` pointed to its parent. The committed regression contains
all exact a–e forms and measurements; `tmp/orchestrator/realities-c2-final-proof.json`
retains the full final envelope. No old published-base result was treated as green.

Cold seed observation (8 ms including roster/store observations):

```clojure
(let [i (get @seon.operator.runtime/running-instances "default")
      d (seon.db/db (seon.cluster.boot/connection "default"))]
  {:tool-branch-retired
   (not (contains? (seon.cluster.registry/roster (:seon.store/store i)) :c2-tool-proof))
   :root (seon.db/pull d '[:seon.agent/id :seon.agent/branch] [:seon.agent/id "root"])
   :bootstrap (seon.db/pull d '[:seon.turn/id :seon.turn/closed-tx]
                           [:seon.turn/id (seon.bootstrap/run-id "root")])
   :live-store-answers (some? (seon.db/commit-id d))})
```

Returned retired=true, root branch cluster-default, closed bootstrap transaction
536870943 and store-answers=true.

## Limits and red-evidence classification

Second focused launcher, HEAD `d26af4977`, snapshot `run.I1Ik8s`, recorded run
`02f1696f504b`, again refused the old published fixture's missing branch schema.
The command included all eleven implementation/test paths and selected only
`seon.cluster.acquisition-test`. This was a wanted schema population boundary,
not permission to bypass fixture admission. The cold exported fixture then
provided the new schema for the recorded in-process proof. No lane gate or
platform suite was run.

Earlier scratch adoption refused concurrent changes to foreign
`test/seon/test/selection_test.clj`. The frozen checkout removed that moving
input; final adoption succeeded. Existing related authority:
[development-adoption-can-mix-host-and-sci-generations](../../../seon/issues/development-adoption-can-mix-host-and-sci-generations.md).
No foreign file/session was edited or resumed.

Fixture-development reds were fixed at their owners within the new test:
ordinary fixture lacks held-store ownership; retained exported commits carry old
store configuration, so the host fixture branches from its reidentified
`:current-src` branch; minimal fixture tool inputs must include caps and bounds.
The initial 5-second test bound failed after 20 passing assertions at 10,051 ms,
so the measured 15-second declaration replaced it. A trial override of a host
caller refused interpretation and was replaced by the simple declared function
used in the wanted visibility regression. No deleted-machinery test was retained.

Real MCP transport used `bb -cp script:src:resources` and the current
`seon.dev.mcp/execute-clj-eval`. A first `-cp script` attempt lacked
`seon.error.refusal` and failed transport; corrected classpath succeeded.
The acquired captured source commit was `6ab2d570-9f12-5cd4-9da3-0e99ebdbae44`
(1,148 ms). Source:
`(do (defn c2-transport-value [] 73) (c2-transport-value))`, branch
`"c2-tool-proof"`, namespace `my.agents.root`, returned 73. The tool's finally
released the owned handle; the final roster read proves unlink. Its live
comparison was explicitly refused, not passed: changing test helpers after boot
caused commit-1 interpretation to encounter `Runtime/getRuntime` and `io/file`
in those helpers. The canonical fixture's full tool visibility test passed.
Host-bound classification is a later declared target; no workaround was added.
The installed MCP connector descriptor requires restart to advertise the new
optional member; the actual new request builder/socket path was exercised.

The cold root's three receipt errors were two prose-only replies and unresolved
`my.note/?`, as returned by `seon.problems/errored-receipts` (2 ms). They are not
proof of a healthy whole cluster, nor a diagnosed acquisition failure. No
browser-paint observation or provider correctness claim is made. The scratch
bootstrap ran its configured provider automatically; no separate paid provider
experiment was initiated.

## Cleanup and publication boundary

`bin/seon --root tmp/realities-c2-root down --force` verified exact PID/start
identity and returned process-exit=true. The retained launcher exited. Root
`default` in the repository was never stopped/reset/reloaded. Hook publication
remains paused: **this commit is not live on repository default**.

Raw boot logs, frozen-input hashes, MCP probe source/output and final proof
remain under `tmp/orchestrator/realities-c2-*`. Owned disposable store roots are
removed only after holder checks; cleanup never follows the frozen checkout's
`.git` or `reference-code` symlinks. The final four-namespace HEAD-load and cleanup
results are recorded below.

Post-implementation namespace load: exact requested
`clojure -M -e "(require 'seon.cluster.agent 'seon.sci.eval 'seon.cluster.registry 'seon.cluster.store)"`
completed with exit 0 after `1ada78050`. Only the existing JAVA_HOME warning
was printed. `git diff --check` passed before implementation commit. Concurrent
work resumed in schema files after that commit (including the agent schema's
program partition annotation); those later changes are preserved and are not
part of this lane's evidence.

Before deletion, `lsof +D` returned no holders (status 1, empty stdout/stderr)
for the scratch root, both fixture exports, the frozen snapshot, and the two
failed fixture roots `368b60125941` and `2b31f346ac41`. Exact prior JVM PIDs
62548, 57265 and 56799 were absent from `ps`. Cleanup removed those six owned
roots without traversing symlinks. No uncertain or foreign root was removed.
