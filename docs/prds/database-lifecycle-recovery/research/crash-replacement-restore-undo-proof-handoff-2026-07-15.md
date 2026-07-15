---
type: research
status: completed
tags: [research, database, flow, pod]
---

# Crash replacement, restore, and undo proof handoff — 2026-07-15

## Decision

After anchored process containment and clean quiescence graduate, the shortest
source-frozen default-cluster proof is one clean control plus two unexpected
crash arms. The control distinguishes planned drain from recovery. The first
crash arm proves that process replacement waits for a generation-matched
subtree terminal result. The second reuses that replacement boundary during a
real ordered multi-form turn and proves that database recovery preserves only
committed facts. More crash cuts belong in the focused process fixtures; adding
them to the destructive live gate would repeat evidence without closing a new
contract.

Restore and undo are the same guarded root transition with different selected
target coordinates. They do not move `:db` directly to the selected target
commit. Maintained Datahike writes a new main-branch commit from the selected
database value, records the selected commit as its parent, and reads the new
head back. Seon must retain the selected target coordinate, the newly forced
commit, and the later completion-transaction head as three distinct points.
Undo first reserves the current main as a redo branch, then runs that identical
transition toward the prior undo branch.

This report prepares proof only. It changes no production source or roadmap,
does not operate the default or ACME cluster, and does not relax the unsettled
containment or quiescence contracts.

## Reconciled dependency ledger

The checkout was read at `4eb5bc44`. Historical reports that cite Datahike
`6f90b339...` are stale for implementation planning; both root aliases now
select the maintained source below.

| Dependency or mechanism | Selected identity | Exact source read | Proof constraint |
|---|---|---|---|
| Datahike | `org.replikativ/datahike` SHA `417649383c65e13f15ea41d394fb1ed742477965` | `deps.edn`; `reference-code/datahike/src/datahike/versioning.cljc:153-370`, `connector.cljc:438-510`, `writer.cljc:1-165` | `branch!` accepts a retained commit UUID, not an arbitrary temporal cut. `force-branch!` requires exclusive write access, checks the expected current commit before and inside the final head update, creates a new commit, and verifies its read-back. Final connection release closes admission and joins accepted primary, secondary, and store work. |
| Konserve | `org.replikativ/konserve` SHA `df6818d43ea3363a808cd051c0d68917f1b987a9` | `deps.edn`; `reference-code/konserve/src/konserve/protocols.cljc:4-36`, `core.cljc:278-317` | One key update is atomic, and optional multi-key calls are all-or-nothing, but there is no CAS spanning a separate Datahike writer operation. Expected-head checking therefore never substitutes for writer quiescence. |
| Canonical coordinates | current `seon.db.coordinate` | `src/seon/db/coordinate.cljc`; `src/seon/db/registry.clj:541-809` | Every proof point is the closed `{database-id, branch, commit-id, t}` value. The current branch owner rejects a requested `t` that is not its containing commit's head and verifies primary datoms before publishing a target route. |
| Native branch lifecycle | protocol version 2, current registry/writer | `src/seon/db/protocol.cljc:175-275`; `src/seon/db/registry.clj:682-931`; `src/seon/db/writer.clj:802-860` | Typed create, release, and delete already route through the one source writer. Create/adopt, exact attachment/head fences, roster membership, release failure, and stale raw head behavior are reusable prerequisites. Promotion is not yet a typed owner. |
| Unexpected database recovery | current `seon.runtime.recovery` | `src/seon/runtime/recovery.cljs`; `test/seon/runtime/recovery_test.cljs:126-238`; `docs/seon/architecture/agent-runtime.md:550-588` | Cold boot performs one fenced transaction: retract owned current pointers, close open runs `:crashed`, interrupt only running turns/evals, and add one recovery anchor. It fabricates no message or eval and is idempotent. |
| Process replacement | unsettled anchored containment owner | `script/seon/dev/process.clj`, `detach.py`; [[dead-leader-process-subtree-containment-2026-07-15]] | Replacement may begin only after the exact containment generation publishes a drained terminal result and its owner exits. A dead workload PID, endpoint failure, or numeric PGID is not sufficient evidence. |
| Planned drain | unsettled quiescence owner | `src/seon/runtime/admission.cljs`; `src/seon/agent/{loop,run,turn}.cljs`; [[clean-planned-restart-quiescence-refresh-2026-07-15]] | `:quiescing` blocks new work, the complete admitted turn returns, its run closes `:quiesced`, and pod/JVM drain results carry full final coordinates. A forced stop never claims this evidence. |
| Restore completion facts | aspirational target schema | `docs/seon/architecture/data-model.md:673-699`; `agent-runtime.md:577-588` | The completion transaction records source, selected target, forced commit, undo and prepared-target branches, and only committed overlay digests. It is written after root move and reconstruction but before admission. |
| Branch-local blobs | partial prerequisite | `my.blob` owners and [[branch-local-blobs-forensic-runtime-audit-2026-07-14]] | Promotion must preflight every required hash and materialize the selected base/overlay view. Missing blob proof blocks force. Blob completion remains an implementation dependency, not something this crash harness can infer. |

ACME inherits the maintained Datahike/Konserve coordinates from the root
`:writer` and `:cljs` aliases. It is a downstream consumer of this proof and is
not part of the default destructive gate.

## Existing fixtures and commands to reuse

No new runner is needed.

- `test/seon/dev/process_test.clj` already owns real detached-process and
  operator-SIGINT fixtures. Extend that owner for containment cuts; keep unique
  project-local process directories, port files, sockets, and innocents.
- `test/seon/dev/branch_test.clj` already proves exact native inverse ordering,
  response-loss retry, converged-process non-ownership, and retained cleanup on
  failure.
- `test/seon/db/registry_test.clj` has real branch create/adopt/release/delete,
  stale-head, contained-cut, failed-initializer, roster, and stale-raw-head
  fixtures.
- `test/seon/db/writer_integration_test.clj` drives the complete typed branch
  lifecycle through one real UDS writer.
- `test/seon/db/transport_uds_test.clj` proves request admission closes while
  admitted handlers finish.
- `test/seon/runtime/recovery_test.cljs` owns exact recovery datoms,
  provenance, no-message behavior, and second-pass idempotence. Extend its eval
  count assertions rather than build another recovery fixture.
- `bin/seon branch open|restart|close|status NAME` is the current retained
  branch interface. `bin/seon up|status|restart|down` remains the one default
  supervisor interface. There is no restore/undo command yet.
- Focused gates remain:

```bash
bin/seon test operator \
  seon.dev.process-test \
  seon.dev.branch-test \
  seon.dev.cli-test

bin/test-writer \
  seon.db.registry-test \
  seon.db.writer-integration-test \
  seon.db.transport-uds-test

bin/test-cljs --test=seon.runtime.recovery-test
```

The eventual live gate uses cluster-qualified repository MCP eval for exact
CLJ writer and CLJS pod observations. Shell commands may invoke only the public
operator plus a fixture-owned request to kill the recorded pod workload. They
must not kill a numeric process group, writer, or containment owner directly.

## Source-frozen live crash-replacement matrix

### Freeze and common evidence

Pause all edits that affect the default artifact digest. Build once, record the
manifest/application/client/writer digests, default runtime advertisement,
watcher/writer/pod containment descriptor, and equal writer/replica coordinate
`B`. Every replacement must use those same bytes. Record before/after counts of
recovery anchors, messages, evals, open runs, running turns, and current-run
pointers through exact CLJ/CLJS queries.

The watcher and writer remain stable throughout the pod-only crash arms. The
recorded containment generation must change only when a new pod generation is
published. A successful HTTP endpoint or new workload PID cannot precede the
old generation's terminal result.

| Arm | Trigger and shortest falsifier | Required integrated proof | Cleanup |
|---|---|---|---|
| Clean control | Start a turn whose body is controllably blocked, invoke the ordinary restart, and observe whether the old pod exits before the body returns. | Restart waits. The real body result, eval, and turn-close commit precede one `:quiesced` run close. Pod and JVM terminal results carry full descendant coordinates. Reopen performs no unexpected repair, adds no recovery anchor, and admits a later message into a fresh run. | Release the body latch, await the public restart, and query from a fresh MCP session. No manual signal. |
| Idle hard crash with subtree | Admit a fixture child beneath the pod, make the child ignore TERM, then kill only the exact recorded workload through the containment fixture seam. | The live anchor issues TERM and final group KILL while its generation is pinned; owner reaps and publishes one matching drained result; child and old workload are absent; no innocent fixture is signalled. Only then may `bin/seon up` reconcile a new pod. Watcher/writer identities and database head remain valid; recovery is an idempotent no-op when no ownership was open. | The owner drains its subtree. Reconcile through `bin/seon up`; remove only fixture-owned files after terminal proof. Never call `kill(-pgid, ...)` from the operator/test. |
| Active ordered-form crash | Submit one real turn/batch where form `N` returns and its eval transaction is observed at coordinate `E`; form `N+1` starts and waits on a controllable unresolved Promise. Kill only the exact pod workload before `N+1` returns. | The containment result precedes replacement. After `bin/seon up`, committed eval rows/results are exactly the prefix through `N`; `N+1` and later have no result. The open turn/eval is `:interrupted`, run is `:crashed`, current pointer is absent, and exactly one recovery anchor shares their repair transaction. Message count is unchanged by recovery. A second config-free pod replacement writes no additional repair/eval fact. Canonical declarations committed through `N` reconstruct; scratch and unresolved effects do not replay. | Resolve/cancel only fixture-local latches if still addressable, reconcile through the supervisor, query the durable repair, then perform one public pod restart for idempotence. |

The focused containment suite—not the live matrix—must separately cover owner
death, anchor death, corrupt/mismatched result, stale numeric PID/PGID-shaped
innocents, startup publication cuts, and operator SIGINT. Any such uncertain
result fails the live precondition rather than authorizing replacement.

## Exact restore semantics and coordinates

### Coordinate names

Let:

- `H = {D, :db, h, th}` be the exact live main head frozen in confirmed
  intent;
- `T = {D, bt, t, tt}` be the selected target branch head, where `tt` is the
  maximum `t` in containing commit `t`;
- `U = {D, bu, h, th}` be the reserved undo branch created from `H`;
- `P = {D, bp, t, tt}` be the reserved prepared target branch created from
  `T` if `T` is not already the reserved branch;
- `F = {D, :db, f, tt}` be the head read back immediately after guarded force;
  and
- `C = {D, :db, c, tc}` be the descendant head after overlays (if selected),
  reconstruction, and the restore-completion transaction.

`D` is stable across every point. `F` is not `T` with a changed branch keyword:
`force-branch!` rewrites the selected database value for branch `:db`, sets its
parents to the resolved target commit, computes and stores a new commit id `f`,
updates the main head, and verifies `f` by read-back. Thus `f != t` in the
normal case, `F.branch = :db`, `F.t = T.t`, and `t` is a parent of `f`.
Subsequent completion work makes `C` a descendant of `F`; the completion entity
records `h/th`, `t/tt`, and forced commit `f` rather than conflating them.

### Fences and transition

1. Validate confirmed immutable intent and exact digest. Re-resolve `H` and
   `T`; require the same `D`, retained commits, exact branches and `t` values,
   branchable containing heads, ancestry policy, schema/program compatibility,
   overlay digests, every required blob hash, and artifact/protocol version.
2. Through the existing source writer create or exactly adopt `U` from `H` and
   `P` from `T`. Read back full coordinates, primary datoms, and every enabled
   secondary root. A differently positioned reserved branch is divergence.
3. Quiesce the pod and writer. Require the pod response plus generation-matched
   terminal results, successful UDS handler drain, every Datahike connection
   release, and ordinary writer absence. Failure preserves intent and sends no
   force request.
4. Start the no-listener admin invocation from the same writer artifact. Open
   only the selected retained database value, re-read main as exact `H`, and
   call `force-branch!` with `:expected-current-commit h`. The full-coordinate
   preflight plus exclusive drain closes the gaps that Datahike's commit-only
   option cannot close alone.
5. Read main back as `F`; require `D`, `:db`, `tt`, a new `f`, selected target
   parent `t`, primary equality, and every enabled secondary root. Release the
   admin connection successfully and exit without request/publish/REPL
   listeners.
6. Fresh-start the ordinary writer and pod. No old db value, feed cursor,
   replica, listener, or cache crosses the root move. Reconstruct canonical
   facts, materialize only frozen overlays/blobs, transact one
   `:seon.db.restore/*` completion entity recording `H`, `T`, `f`, `bu`, `bp`,
   and only committed overlay digests, then prove writer/replica head `C`.
7. Rearm autonomy last. After browser/feed and config-free restart proof,
   durably delete intent. Intent plus completion fact is a resumable state, not
   permission to repeat force.

`expected-current-commit` is checked once before immutable-node preparation and
again inside Datahike's final per-key main-head update. Immutable orphan nodes
may remain after a late rejection, but main remains unchanged. Because
Konserve has no cross-operation compare-and-set against another writer, any
missing exclusive terminal proof forbids step 4 even when `h` still matches.

## Undo is the identical transition

Suppose the current restored head before undo is
`H2 = {D, :db, h2, th2}` and `U` still resolves to the original `H` value.
Undo selects `T2 = U`; it does not compile reverse datoms.

1. Confirm a new immutable transition id and freeze exact `H2` and `T2`.
2. Reserve a new undo branch `U2` from `H2`. This is the redo point. Prepare a
   new target branch `P2` from exact `T2`.
3. Reuse the same quiesce, release, admin, and full-coordinate fences.
4. Guard force with expected current commit `h2`. Datahike creates and reads
   back `F2 = {D, :db, f2, T2.t}` with parent `T2.commit-id`.
5. Fresh reconstruction and a new completion transaction produce descendant
   `C2`, recording `H2`, `T2`, `f2`, redo branch `U2`, and target branch `P2`.

The proof must show original database facts/program/blob view at `C2`, retained
redo point `U2`, two distinct completion facts, config-free reopen, and no
special undo protocol or reverse mutation path.

## Restore/undo crash cuts and falsifiers

| Cut | Durable observation | Required resume action | Falsifier |
|---|---|---|---|
| Before confirmed intent | no intent | plan only | any branch/root mutation |
| After intent, before `U` | main exactly `H` | create/adopt exact `U` | main moves or a guessed current head is adopted |
| After `U`, before `P` | main `H`, exact `U` | create/adopt exact `P` | undo branch is deleted or recreated elsewhere |
| After `P`, before force | main `H`, exact `U/P` | revalidate every frozen input, then drain | force with a missing blob, stale target, or live writer |
| Release or terminal proof fails | main `H`, intent retained | maintenance; retry diagnosis only | admin starts or force request is sent |
| Expected head changes before/during force | main not proved `H` | typed stale-plan failure | retry using newly observed head |
| Admin dies during force | main is either exact `H` or a read-back-verifiable `F` descendant of target | derive old/prepared versus moved state from storage | infer success from process exit or repeat force from `H` when main already moved |
| `F` exists, no completion fact | main descends from selected target; intent retained | fresh reconstruction, frozen overlays, completion transaction | cross old cursor/cache or force again |
| Completion fact exists, intent remains | transition durable; readiness pending | re-prove `C`, runtime, blobs, browser/feed; then delete intent durably | repeat overlays/force or open admission early |
| Main matches neither `H` nor selected-target ancestry | divergence | retain maintenance and intent for diagnosis | choose the closest head |

Shortest storage falsifiers are: wrong database id; partial coordinate;
non-head `t` inside a containing commit; missing retained commit; mismatched
reserved branch; stale main before either expected-head check; active main or
target connection; release error; target primary datom mismatch; any enabled
secondary root mismatch; missing blob; forced-head read-back mismatch; and a
completion fact whose recorded forced commit is not in main ancestry. Each must
leave admission closed and preserve the exact external intent.

## Path ownership and dependency edges

Ordered owners:

1. `script/seon/dev/process.clj` plus `detach.py` graduates anchored subtree
   containment and typed terminal results.
2. `seon.runtime.admission`, `seon.agent.loop/run/turn`, the private web
   lifecycle action, and the operator compose clean quiescence. They consume
   containment terminal results but do not implement promotion.
3. The source-frozen crash matrix above graduates replacement and the existing
   `seon.runtime.recovery` transaction.
4. `my.blob`/launch descriptor owners finish promotion materialization and
   required-hash preflight.
5. `seon.db.registry` owns guarded native force/read-back and the no-listener
   admin entry; `seon.db.protocol`/writer add only the minimal closed operator
   capability if the external admin boundary requires it. Babashka never opens
   Konserve directly.
6. `seon.dev.state` owns fsynced immutable intent; `seon.dev.cli` owns public
   restore/undo orchestration under the existing lifecycle lock.
7. Database schema owners add the architecture-defined
   `:seon.db.restore/*` fact; runtime reconstruction writes it before admission.
8. Operator, JVM, CLJS, MCP, and browser evidence then graduate restore followed
   by undo on default. ACME verification waits for that integrated default gate.

Containment and quiescence may advance in parallel because their source owners
are distinct, but the live crash gate consumes both. Restore intent/admin work
may be source-grounded in parallel with blob materialization; destructive
promotion consumes containment, quiescence, exact branches, blob preflight,
and surfaced release success in that order.

## Live cleanup contract

- Use unique transition and branch names derived from the proof id. Never reuse
  `proof-1044` or another retained lifecycle name.
- Do not run cluster reset while confirmed intent exists. A failed transition
  retains intent, reserved branches, process evidence, and maintenance state.
- Do not delete the undo branch before undo proof or the redo branch before its
  read-back proof. Release every branch pod/registry connection before roster
  deletion; stale raw branch-head keys are not proof of roster membership.
- After successful undo and config-free restart, explicitly close any forensic
  pod, release target routes, delete only proof-owned prepared/debug branches,
  and verify their absence from Datahike's roster and operator inventory.
- Preserve the completion facts and transaction provenance as database history.
  Cleanup never retracts them to make counts look pristine.
- Remove external intent only after completion, admission, MCP coordinate,
  browser/feed, and restart evidence all agree. File and parent-directory sync
  are part of success.
- Keep source watcher/writer identities and unrelated branches/blobs intact.
  ACME is neither stopped nor inspected by this gate.

## Exit measures

Crash replacement exits when the clean control has no recovery anchor, both
hard-crash arms consume exact containment results before replacement, and the
active arm proves one idempotent no-fabrication recovery transaction from live
datoms.

Restore/undo exits when a source-frozen default run survives every tabled cut,
records distinct `H`, `T`, `F`, and completion heads, reconstructs facts,
program, blobs, feeds, and browser state without old handles, then uses the same
transition toward the retained undo branch while preserving a verified redo
point. Focused test counts or successful process exits alone are insufficient.
