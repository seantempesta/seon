---
type: landing
lane: inv-lifecycle-storage-opus
date: 2026-09-26
---

# inv-lifecycle-storage-opus — landing (2026-09-26)

Lifecycle and storage investigation on default (pid 13262, booted 15:48:58Z at 61d207c3e; then pid 19859, 16:02Z).
Read-only JVM probes and MCP SCI on branch `:inv-lifecycle-storage-opus`. The branch is still in the roster: the
JVM died before I could retire it (below).

## Broken first

- **Default is DOWN. It died with an OutOfMemoryError: pid 19859 left `data/heap/java_pid19859.hprof` (17.7 GB,
  16:10Z).** One of my read-only probes was running when it died. It computed Datahike's per-branch reachable set
  for all 99 roster branches in turn (`#'datahike.gc/reachable-in-branch`). The same probe finished at pid 13262,
  18 s and no failure. I cannot tell whether it caused the OOM or only coincided with it: the heap dump has not been
  read, and inv-heap-stuck-members-opus owns OOM. Do not rerun that probe on default. **RESET NEEDED / restart
  default.**
- **Storage (defect 1).** Collection reclaims unlinked branches, but it cannot reclaim the branches of dead
  processes. 90 of 99 roster branches were orphans from an earlier JVM, and 36 % of the store's keys are reachable
  only from them. The mark has grown from 850 ms to 6.5 s. The collection's result is recorded nowhere.
- **Status is blind to hosted branches (defect 2).** A hosted agent's graph was stopped, but its roster entry still
  read as armed and no fault was recorded. Status reads only the root roster. New issue:
  `status-reads-only-the-root-roster-and-a-stopped-hosted-graph-stays-armed`.
- **The one open fault can never close (defect 6).** It names a function that 69229772b deleted. New issue:
  `a-fault-whose-function-is-deleted-never-resolves`.

## Defects

### 1. Storage collection: does it reclaim? Partly. Reproduced and measured. Options below.

- **Before and after one host-test request (in flight 15:52–15:56Z):**

  | | store size | files | konserve keys | roster branches |
  |---|---|---|---|---|
  | before | 1.2 GB | 7,563 | 7,781 | 112 |
  | during | 1.6 GB | 10,502 | 8,795 | 186 |
  | after | 1.2 GB | 7,379 | 7,352 | 99 |

  A dry run afterwards found 1 candidate. So the collection added in 7c7edcbc7 and fab0afaa1 works for released
  branches.
- **Left over: 90 dead-process branches.** 48 `agent-*` and 42 `host-test-*` branches were last written
  14:16–14:20Z by an earlier JVM, and none has a connection now. Marked separately, 2,624 of 7,352 keys are
  reachable only from them.
- **Cause.** An execution-owned branch is unlinked only by its owner's `release-context!` or `retire-branch!`. When
  the process dies first, no owner is left. The roster is the fact, so each such branch stays a GC root forever.
- **Mark cost.** 6,550 ms at 99 branches, of which `referenced-blobs` took 1,724 ms. `gc.cljc:22-81` keeps its
  `visited` set per branch, and the plain cutoff walks each branch's whole ancestry, so shared commits are marked
  once per branch.
- **Unrecorded result.** `test.clj:2485` hands `collect-released!`'s inventory to a `complete!` that discards it on
  success, so nothing records swept keys or mark time.

**Options for root: who retires a dead process's branches? This is a new decision; I made no production edits.**

1. **(recommended)** At boot, before any acquisition, the store owner retires every roster branch that no fact
   claims. The claims are: `:db`; each `:seon.cluster/name` row's cluster branch; checkpoint branches (test.clj
   already retires stale ones); and each agent row's `:seon.agent/branch` whose admission is open, on every cluster.
   - Guarantee: boot is the only moment when no execution is live, so nothing can race.
   - Cost: about 40 lines in `registry.clj` (a `retire-unclaimed!` function plus a regression) and one call in
     `boot.clj` (inv-boot-operator-opus's file). Boot pays about 100 ms.
   - Sacrifice: an MCP branch that was created but never hosted is lost at restart. An isolated agent's branch is
     claimed only by a row on that branch itself, so it is not reacquirable after restart today either.
2. Put the owner's process identity in the name of every execution-owned branch, as checkpoints already do, and
   retire branches of processes that are not live at the next collection.
   - Cost: about 20 lines across test.clj, agent.clj and the host test.
   - Sacrifice: ownership is parsed from a name. The data laws forbid that.
3. The fork stores owner metadata on the roster entry (`branch!` options) and GC skips the dead owner's roots.
   - Cost: a datahike fork change plus a re-pin, about a day.
   - Sacrifice: a second lifecycle owner inside the dependency.

Independent of the choice:

- Fork fix for the mark: share one `visited` set across branches in `gc-storage!`. This turns branches × ancestry
  into distinct commits.
- Record `collect-released!`'s inventory as a maintenance receipt. `test.clj` is inv-heap-stuck-members-opus's file.

**Evidence:** the issue `storage-gc-runs-without-a-cutoff-so-it-reclaims-almost-nothing.md` has a new re-measure
section with these numbers.

### 2. Adopting or retiring leaves a Flow graph running and unowned. Adoption not reproduced; a related defect observed.

- **Adoption.** Default had not adopted anything since its 15:48Z boot (`loaded-identity` = boot commit), so the
  adoption hypothesis in `adoption-leaves-live-objects-built-by-retired-code-running` could not be exercised. It
  stays a hypothesis. Adoption on default is root's.
- **Roster against rows** (16:01Z):
  - 30 agent rows on the cluster, 13 with open admission.
  - Roster: root armed (graph pings `[:turn :schedule :mailbox]`), the cluster graph `[:armer :render]`, and six
    hosted child rosters, each with an armer graph.
  - Child `:inv-run-stab-b`:
    - its armed graph answers "flow not running";
    - its report and error channels are closed;
    - its mailbox is open;
    - no error is recorded on the cluster or on the branch.
  - Status reported `:flow {:agents 1 :procs 3}`, i.e. root only.
- **Cause.** `oversight/flow-status` (`src/seon/oversight.clj:190`) passes only the root roster to `fleet-value`.
  Nothing removes an armed entry whose graph exited.
- **Status.** New issue filed. The fix is in oversight.clj, which is not my file.
- **Other rows.** Open-admission rows with no live host (dogfood-*, dre-custody-probe, fdao2-probe) are expected
  after a restart; `host!` re-hosts them on use.

### 3. Reload overlap. The issue's premise was wrong; the real gap is run-level. Design only.

- `evaluate` has detected a reload within one evaluation since e81cb51dc (`trusted-evaluation` →
  `call-detecting-program-change`). That code was already present at 07a03cdd4, where the issue was filed.
- What is undetected: a reload between the forms of one run, or between the last admission and settlement.
- Design: wrap the run's evaluate-and-settle in `call-detecting-program-change loaded-identity 'seon.turn/run since`,
  with `since` captured at admission. The `since` arity already exists for this case.
- The design is recorded in the issue. It lands in turn.clj, which is inv-agent-run-stability-opus's file.

### 4. Stale snapshot. Half fixed, verified. The issue stays open for the other half.

On my branch after defining `user/probe-basis`:

- `call-preparation/snapshot` at the head prepared it.
- At `as-of` the fork basis, and at the fork commit, it did not.
- So a44538acc fixed the older-value half.
- d62d32bf7 did not fix the rest: `hook` still takes its plan database from the connection
  (`call_preparation.clj:1145-1147`), not from the run's working value.
- Evidence added to the issue. Its status is left open for the hook half.

### 5. Roster risks from 30e6e8f75, plus Astra E2 and H2/H3

- **E2** (release does not retire descendants or pending claims): I wrote a patch and root applied it as 45833be3e.
  - `stop-owned-graphs!` now also settles in-flight acquisitions.
  - `release-context!` releases every owning handle in its child rosters, through itself, before it detaches.
    A failure throws while the subtree is still attached.
  - A released roster refuses reservations with `::roster-released`.
  - Regression: `releasing-an-execution-releases-the-executions-acquired-through-it`.
- **Empty-child race:** fixed by the same patch.
  - The failing acquisition decides emptiness inside the child's own `swap!`, which marks it released.
  - `child-roster!` replaces a released child, and the concurrent acquirer retries once.
  - The issue is now fixed-unproven: a deterministic race regression needs a seam that no Var redefinition may
    supply.
- **Listener survives a missed quiescence:** confirmed from source, not reproduced (it needs a wedged armer). A
  wedged proc cannot run its own stop transition, so this needs a ruling. Three options are in the issue; I
  recommend option 2, which queues `flow/stop` even on a miss (about 15 lines in `disarm-agents!`).
- **H2 and H3** (host.clj): commit **dba08a8ab**.
  - The home decision now runs inside each writer as `[:db.fn/call #'home-call …]`.
    - It refuses by name a closed admission or a different home branch.
    - A matching row is kept; an absent one gets `creation-tx`.
  - `host!` refuses a request whose agent id differs from the host already running on the branch.
  - Proved at the REPL on default's real rows with `d/with`:
    - same home → `[]`;
    - other home → `:seon.agent/branch` refusal;
    - closed admission (`mcp/dogfood-repl-opus-11`) → refusal;
    - fresh id → 39 datoms.
  - Contracts compiled and validated against the live projection registry.
  - `colliding-sibling-hosts…` has asserted twin hosting since fdd9926e8; source says it fails there (the cluster
    row pre-read refuses the second sibling). I did not run it. It is replaced by
    `one-identity-is-homed-on-one-branch-decided-by-the-writer` (concurrent) and
    `a-branch-answers-only-for-the-agent-it-hosts`.
  - Size: host.clj +68/−38, test +36/−13.
  - Each host start now writes one transaction per writer, even when the row already exists.

### 6. Open faults in `bin/seon status`

- One open fault: `seon.flow/release-admission!`, ClassCastException, last seen 03:25:27Z. It was recorded by a JVM
  before both boots.
- It is no longer produced: 69229772b deleted the function and the reload-redefined deftype that caused the cast.
- It stays open because `seon.error/resolution` needs a function row, and a deleted function has none.
- New issue filed. Root can resolve this one now with `seon.problems/resolve!` (the form is in the issue).
- During a JVM probe the MCP reply also carried a branch error: `seon.cluster.agent/turn-step`, "Symbol cannot be
  cast to Keyword", on branch `dogfood-maintainer-opus-1`. It belongs to another lane's branch; I did not
  investigate it.

## Other commit

**048956fc1** applied root's F3 patches (`cluster-F3.patch`, `mcp_test-F3.patch`) as given, and removed the
`seon.render.data` require they left unused.

## Root runs (after default restarts)

- `bin/test-check default --ns seon.cluster.host-test`
- `bin/test-check default --test seon.cluster.agent-test/releasing-an-execution-releases-the-executions-acquired-through-it`
- `bin/test-check default --ns seon.cluster.mcp-test`
- The static pre-commit checks, which I could not run on committed code because default runs an archive:
  - `seon.contracts-compile-test/every-declared-contract-compiles-against-the-packaged-projection`
  - `seon.schema.edn-test/every-packaged-schema-resource-reads-as-edn`

  My two new contracts were compiled and validated by hand against the live registry.
- Retire `:inv-lifecycle-storage-opus` (MCP `branch` retire) once default is up.

## Limits

- No test requests were run by me.
- The H2 concurrent regression and the E2 regression are unrun.
- Orphan retirement is not implemented (it needs a ruling).
- The mark timing comes from a dry run on a live store.
