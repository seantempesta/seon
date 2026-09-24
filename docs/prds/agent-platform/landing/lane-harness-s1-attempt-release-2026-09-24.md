---
type: landing
status: stopped before production edits (caller conversion needs held files)
lane: harness-s1-attempt-release
created: 2026-09-24
issue: docs/seon/issues/an-agent-graph-steps-after-its-test-branch-store-closes.md
design: docs/research/agent-platform/one-test-harness/design-2026-09-24.md §1 row "graphs"/"cleanup", §9c S1
---

# Lane harness-s1-attempt-release — landing (2026-09-24)

What exists: `acquire-context!` returns an execution handle with no routing; `arm!` takes an
independent routing atom from its request; `release-context!` knows nothing of graphs armed over
its connection. So a fixture branch's release closes the store under a graph a test armed with its
own `(agent/routing)`. The target moves routing onto the handle so release can stop and join it.

## Broken things first

- **Stopped by the assignment's boundary, with no source edits.** S1a's caller conversion needs
  three files that harness-s12-deletions holds. All three surviving tests stay under
  turn-suite-subsumption (KEEP):
  - `test/seon/turn_test.clj:485`: `virtual-turn-fixture`, which arms at :558/:658 and disarms at :720/:757.
  - `test/seon/turn_continue_test.clj:44`: `prove-session`, which arms at :119 and disarms at :64/:137.
  - `test/seon/loop_proof_test.clj:76, :251, :355`: P64, P237 and P327, which arm and disarm at 12 sites.
  An unconverted caller keeps passing its own atom. Its `disarm!` and `armed` then read a
  different routing from the one `arm!` used, so enforcing ownership breaks these tests. Keeping
  a request-supplied routing alongside the handle's would leave two arming paths, and the defect
  would stay open for exactly these fixtures.
- Live default is degraded in a way this lane does not own. `runtime_status` shows state
  `unknown` and an NPE at `seon.problems/failed-tests` (problems.clj:386). That is Astra
  finding 5, which is harness-s0's slice.

## Evidence (read-only JVM probes, session `harness-s1`, pid 99265)

- The installed `:seon.agent/execution-handle` keys are name, context-state, base-ctx,
  connection, db, commit-id, projection, ctx, environment, branch, mode, owns-connection?,
  owns-branch? and store. There is **no routing**. The installed `:seon.agent/arm-request` keys
  are cluster, id and **routing** (required). Probe time: 4 ms.
- Installed Var lines match the checkout: `release-context!` :767, `arm!` :959.
- Construction sites of `(agent/routing)`: 25 in total.
  - One is production (`cluster.clj:3606`, the cluster's own routing).
  - 15 are in test files that are not held: agent_call_edges, cluster/agent_arming,
    cluster/agent, concurrency, help_trial, issue, namespace_agent_loop, oversight ×3, program,
    render/web_debug, rereads, run4_install.
  - 5 are in the held files above.
  - Pass-through callers: context_blocks_fixture :273/:341, concurrency :95, cluster/armed :285.

## Reviewed design, ready to run once the held files are free

Scope is agent.clj, seon.agent.edn and test_support, plus about 20 one-line caller edits.

1. **S1a ownership** (about 40 source lines plus about 20 caller lines).
   - `acquire-context!` puts `:seon.agent/routing` on every handle. An acquisition that owns
     its connection or branch gets a fresh `(routing)`. Otherwise it inherits the routing of
     `previous`, then the branch owner, then the source handle.
   - The cluster's `loop-handle` result carries the cluster's routing (cluster.clj:3606).
   - `arm!` takes routing from the execution it acquires. The request's `:seon.agent/routing`
     is deleted from `:arm-request`.
   - The returned `:armed` entry carries its routing, for disarm and readers.
   - `test_support/cluster-handle` defaults routing to the executing attempt's.
   - Each caller's `(agent/routing)` becomes `(:seon.agent/routing handle)`.
2. **S1b release** (about 25 source lines, plus a regression of about 70 lines).
   - Under `(locking routing …)`, `release-context!` marks the routing `:seon.agent/released`,
     and `arm!` refuses when it sees that mark.
   - Release then calls `disarm!` for every armed id, collecting every failure without
     stopping at the first.
   - If any join failed, release throws the first failure with the rest suppressed. It keeps
     the connection, branch, context-state entry and routing, and clears the mark so a later
     release can finish.
   - Only then does the existing connection → unlink → state list run.
3. **Regression** (`test/seon/cluster/release_context_test.clj`, free). It covers:
   - setup failure after the branch is created, which leaves no routing entry and no branch;
   - two graphs, one whose turn ignores interrupt: release refuses, the store stays open, and a
     second release after exit closes it;
   - concurrent arm/release, where no graph survives and there are no reads over a closed store;
   - nested acquisition, where a child's release does not stop the parent's graphs.

## Change

None. This note is the only file committed. No tests were run, and no before/after numbers
exist because nothing changed. No RESET NEEDED.

## Options for root

- (a) **Recommended.** Relaunch this lane after harness-s12-deletions releases turn_test,
  turn_continue_test and loop_proof_test. It is one lane over all 20 caller sites, in two
  commits.
- (b) Hand the 5 held one-line conversions to harness-s12 now, as a patch this lane writes, then
  launch S1a/S1b.
- (c) Land S1a/S1b now with a request routing that is still accepted. This is not recommended:
  it keeps two arming paths and leaves the defect open in the held fixtures.
