---
type: issue
status: open
severity: friction
tags: [issue, agent, component, database, research]
---

# Give Inspect live callers an ownership-fenced cluster lease

## Problem

Inspect's unsafe retired cluster operations are removed. Lease-dependent entry
points now fail before subprocess or model work, and static consumers require
explicit operator-derived coordinates. The operator still exposes no
ownership-fenced per-sample lease, so a green offline suite cannot prove that
isolated restart, cleanup, or multi-cluster evaluation can run safely.

This blocks live Inspect and paid/model acceptance work. It does not block the
offline Inspect suite or ordinary default-cluster development.

## Evidence

The original dependency/Shadow/MCP audit found:

- `src-inspect-ai/src/seon_inspect/cluster.py` invokes removed `bench-bundle`,
  `cluster create`, `cluster destroy`, and per-pod restart operations, and
  derives ephemeral port files outside the current target contract;
- `bench_common.py` connects directly to writer port `7891`;
- `typeahead_corpus.py` runs old `bin/acme start pod` / `restart pod` commands,
  defaults to web port `7980` and writer port `7981`, and reads the legacy
  cluster layout directly;
- associated docstrings and runbooks describe the same retired supervisor,
  frozen-bundle, registry, and port behavior.

Those bullets are historical evidence for the completed fail-closed migration,
not current call paths. `cluster.py` now rejects lease-dependent create, fork,
restart, release, and bundle preparation. `typeahead_corpus.py` requires
explicit coordinates and an injected fenced restart. `bench_common.py`'s
fixed port belongs to its container-internal SWE/terminal-bench topology, not
the host per-sample lease.

The 2026-07-14 lane-integration audit re-ran the complete offline Inspect
suite: 314 tests passed with eight expected environment-gated skips in 7.86
seconds. This sharpens the boundary rather than closing it: pure task/scorer
logic is healthy, while no operator-backed CLJ/CLJS/restart/cleanup journey was
exercised. The active PRD's older 293/eight count is stale documentation.

Current `bin/seon` exposes target-level `up`, `down`, `restart`, structured
`status`, one artifact manifest, and scoped reset—not the per-pod/create/
destroy/bench-bundle surface these callers assume. The remaining cluster
lifecycle, lease, and artifact-flavor contract is roadmap work; callers cannot
safely reconstruct it with subprocess strings, arbitrary writer eval, or
guessed ports.

The unified development MCP is now complete and proves dynamic CLJ and CLJS
discovery for an already-owned cluster. That removes the old transport gap but
does not create, lease, freeze, restart, or release Inspect sample clusters.
Live Inspect must consume the operator lifecycle/lease boundary rather than
shelling through MCP as a substitute supervisor.

The bounded caller migration on 2026-07-14 removed the unsafe fallbacks that
could be changed independently of that missing seam:

- `cluster.py` no longer invokes removed create, fork, per-pod restart,
  destroy, or `bench-bundle` operations. Lease-dependent paths raise
  `ClusterLeaseUnavailable` before any subprocess or model call and name the
  missing identity, artifact, endpoint, restart, and release fields.
- `typeahead_corpus.py` no longer starts or restarts ACME, defaults web/writer
  ports, or embeds the old ACME port pair in a generated sample. Static corpus
  capture requires explicit endpoints; a restart-bearing sample requires an
  injected ownership-fenced restart transition.
- the Inspect README now marks per-sample and live restart modes paused while
  retaining static explicitly provisioned URL mode and the offline scorer/
  choreography tests.

The first operator-owned half is now present. `bin/seon status --edn` projects
the configured cluster/database identity, canonical artifact flavor and
digests, exact owned process identities, and dynamically discovered web, CLJ,
and CLJS endpoints. Missing process records with live listeners surface as a
foreign ownership conflict, and the current semantic `up`, `restart`, and
`down` transitions remain target-scoped. This is sufficient for ACME's fixed
target to stop guessing ports and artifact identity.

The exact remaining blocker is the per-sample lease rather than endpoint
discovery. The operator still has no lease owner/token, isolated target
coordinate allocator, frozen artifact selection, or token-fenced
create/restart/release contract. Consequently Inspect cannot safely allocate
concurrent sample process/cache/database namespaces or prove that cleanup owns
the target it would stop. The read-only legacy frozen-bundle identity helper
remains solely for offline contamination fixtures; no live caller can build or
select that artifact through a removed command.

The source/evidence audit found two additional migration edges behind the same
owner. `typeahead_corpus.py` requires explicit endpoints now, but still sends
raw `datahike.api` forms through the writer REPL and reads
`data/clusters/<cluster>/blobs/<hash>` directly. That bypasses the typed
database/debug/blob surfaces and assumes a checkout-local cluster layout.
Also, selected run paths copy native Inspect `.eval` logs on a best-effort
basis and suppress copy errors. Lease finalization must make the complete raw
evidence bundle mandatory; a score without its log/coordinate bundle is not an
accepted run.

The frozen Qwen 3.5 2B BFCL baseline makes that loss concrete. Its native log
preserves ten sample ids, zero scores, pod agent ids, three-to-seven-turn
`:no-forms` closures, eval counts, and model configuration, but no Seon database
coordinate, prompt/reply blob refs, eval rows, or transcript. The later live
ACME database and its history contain no sampled `neat-rice-taste` identity, so
the exact model replies cannot be reconstructed. The run is scoreable but not
forensically adequate for deciding whether context, parser repair, or model
reasoning caused the failure.

The explicitly provisioned static-URL path now closes that evidence-loss edge:
`POST /agents/run` returns the final complete database coordinate and the
ordered exact prompt/reply/error bundle for every turn, and the Python solver
stores it unchanged in native sample metadata. A 2026-07-15 live ACME BFCL
smoke preserved four Qwen 3.5 2B turns that cluster cleanup would otherwise
have made inaccessible. The first wrong-identity JSON call and three empty
replies are directly visible in the `.eval`. This does not close the issue:
concurrent per-sample allocation, restart, and cleanup still require the
ownership-fenced lease described below.

The native-task admission gap is also closed at the execution boundary.
Seon-native milestone/tool tasks no longer need a direct `inspect eval` call
that can omit finalization or a fake upstream benchmark identity.
`catalog.run_native_task` admits source before task construction, retains the
task's existing dataset/solver/scorer, stamps the exact identity, keeps the
static pod serial, and routes through the same mandatory native-log read-back
as `run_bench`. A real native `.eval` proves the retained identity. Static
artifact/config binding and the per-sample operator lease remain open parts of
this issue.

Static artifact binding is now executable for native runs. Inspect consumes
the caller-selected semantic operator status command, requires `:ready` and an
exact URL match, retains the status EDN and digest in the `.eval`, and rejects
any before/after change before finalizing the log into admitted evidence. A
regression proves target drift invokes no finalizer. It does not derive
artifact paths, ports, or process ownership itself. A clean target rebuild
remains necessary before an accepted sample because a development watcher can
compile shared dirty source after the last manifest publication; the
per-sample lease remains the parallel/restart owner. The snapshot reader still
recognizes the ready status and URL by text inside retained EDN; replace that
with semantic operator data parsing before treating malformed or adversarial
status output as covered.

The admitted static path now also closes the process-local before/after
evidence gap. `run_native_task` reruns the complete selected-source admission
after Inspect returns, snapshots the target again, writes both end identities
into the original `.eval` through Inspect's public log-edit API, and only then
permits finalization. Source or target drift retains the terminal log as
rejected evidence before raising. `bb.edn` is admitted because `bin/seon`
executes Babashka through that task manifest. A remaining operator-owned gap
is that process records and status do not carry one canonical operator-source
digest spanning `bb.edn`, `bin/seon`, `script/`, `src/seon/launch.cljc`, and
the Babashka runtime identity. A target started by transient operator bytes
can therefore later look ready after the checkout converges. The operator
closure digest—not another Inspect-side path list—is the acceptance owner.
Likewise `.env.acme` is still sourced as executable shell; it must eventually
be parsed through the existing configuration-data boundary without admitting
credential bytes into evaluation evidence.

A real interrupted reachability row makes evaluator cancellation concrete.
Inspect recorded an interrupted native and retained log, but
`anyio.to_thread.run_sync` was waiting on the blocking `urllib` pod request.
The first operator interrupt therefore finalized the cancelled Inspect task
while Python continued joining the request thread; a second interrupt was
required to exit the process. The addressed root run continued from turn 10
through turn 20 after the evaluator had stopped and had to be closed through
the canonical fenced `seon.agent.run/close-run!` transition. This is evidence
for the existing addressable-cancellation acceptance requirement, not
permission to add a Python-only timeout or kill the pod.

A generated-workflow probe exposed one remaining static-path contradiction.
`planning.fetch_eval_rows` still sent a raw sentinel-printing form to the
writer socket, but the writer now speaks io-prepl event maps. The query reached
the writer while the Python parser failed as `JSONDecodeError`, leaving the
native milestone scorer without rows. The composition door now derives an
ordered `eval_evidence` projection from the same final database value and exact
request turn set already used for counts. It includes only eval id/time/source,
success, and present narration; results, stdout, and error stacks stay out.
Milestone Inspect code consumes this response and the obsolete raw writer
read-back is removed from that accepted path.

This is distinct from `acme-operator-migration-drift.md`. That issue owns the
ACME process/artifact/database migration itself; this issue owns Inspect's
live consumers after the current operator boundary exists.

The 2026-07-19 product-scenario slice makes the next consumer contract
executable without weakening this issue. Native good/bad scorers now cover
namespace-targeted residency, cross-agent function reuse and in-place repair,
execution-child recovery, and pod restart using one final database snapshot.
Its driver sends both work phases through `POST /agents/run` with the same root
agent. It deliberately requires injected restart and database-read operations;
an unowned restart fails before a second request. Twenty focused tests and the
expanded 24-arm offline proof pass. Live logs remain blocked on this issue's
lease and typed final database read-back.

## Owner

`seon.dev.config`, `seon.dev.state`, `seon.dev.process`, and `seon.dev.cli` own
the operator lease. `src-inspect-ai/src/seon_inspect/cluster.py` is its direct
lifecycle consumer. Planning consumes that cluster object; typeahead consumes
explicit lease coordinates and a fenced restart; `bench_common.py` retains its
separate container-internal topology.

## Acceptance

- The operator exposes one structured cluster lease with cluster/database
  identity, artifact digest/flavor, owned process identities, dynamically
  discovered web/CLJ/CLJS endpoints, and bounded create/restart/release
  transitions. Lease cleanup is idempotent and ownership-fenced.
- Every live Inspect caller and active runbook consumes that contract. No
  removed verb, `pod-<name>` convention, hard-coded writer/web port, direct
  registry mutation, arbitrary Clojure lifecycle form, or private port-file
  naming remains.
- Frozen/live artifact identity is pinned per sample through the operator's
  manifest. A restart either preserves the declared artifact/config lease or
  fails loudly; concurrent samples cannot share mutable Shadow/cache/database
  state accidentally.
- Timeout, failed boot, stale lease, foreign port owner, partial restart, and
  evaluator cancellation preserve evidence and release only owned resources;
  they never destroy another sample, ACME, or the default cluster.
- The existing offline Python suite stays green, then one operator-backed live
  smoke proves CLJ read-back, CLJS/pod execution, typeahead corpus generation,
  restart continuity, dynamic endpoint discovery, and complete lease cleanup.
- Typeahead/planning read-back consumes the lease's typed MCP/debug/blob and
  database boundaries. No arbitrary writer form or direct cluster-directory
  blob read remains in an accepted live path, and missing native Inspect logs
  fail evidence finalization rather than being silently ignored.

## Implemented boundary

The implementation commit connects Inspect to the maintained retained-branch
operator lifecycle and adds one loopback-only typed `seon.db/query` read over
one immutable database value. Focused Python proof passes 46 tests; focused
CLJS HTTP/router proof passes 30 tests with 113 assertions. Live model runs are
left to the coordinated source-frozen checkpoint. The issue remains open until
that live evidence and cleanup proof are retained.

The first native repair-row attempt failed before process creation because the
scenario name `reuse_repair` reached the public branch operation unchanged.
`seon.dev.branch/::name` permits lowercase letters, digits, and internal
hyphens, while Inspect's stale local validator also permitted underscores and
uppercase letters. Inspect now mirrors the operator schema and converts the
scenario separator to a hyphen before minting the branch name. The combined
cluster/product proof passes 51 tests. A corrected retry then reached the next
truthful prerequisite: the source watcher and writer were absent, so no branch
was opened and no product result is claimed.

The next exact run opened a real branch and later exposed a release race. The
first canonical `branch close` retained desired state `closed`, reaped the pod,
then received a failed writer lifecycle response. That exception masked the
earlier product failure in Inspect. A second identical `branch close` converged
and deleted the retained branch. The operator therefore has the right durable
intent and idempotent continuation shape, but one invocation does not yet own
the complete convergence guarantee. Preserve the writer's full failed response
on the next reproduction, then fix the release/delete owner rather than adding
an Inspect-only retry loop.

Inspect now retains the operator's complete stderr instead of truncating it to
the first line. If product execution and release both fail, the product failure
remains primary and the cleanup failure is attached as an exception note; a
cleanup-only failure still fails the task. This preserves both facts without a
private retry or false success. The combined cluster/product slice passes 53
tests.

The current-artifact namespace scenario reproduced two independent unstable
states on 2026-07-19. Its admitted Python request ended as
`RemoteDisconnected`, while the retained branch pod stayed ready and kept
opening root turns. A controlled 30-second request preserved the response and
made the behavior inspectable: five successful evals were `message/user`,
`my.plan/active!`, `plan/status`, `seon.agent/delegate!`, and another
`my.plan/active!`; the door timed out truthfully, but the delegated child kept
running. An independent representative run then consumed 37 turns in 211.126
seconds, including 27 successful `my.plan/active!` calls. The loop currently
resets its no-progress streak for every successful eval, so read-only status
polling is indistinguishable from task progress. The agent-loop issue owns that
semantic correction; lowering the 100-turn work budget or adding an Inspect
watchdog would only hide it.

The source correction now derives progress from the eval rows already returned
with the turn. A successful raw form records positive progress only when its
scoped execution commits a database transaction or accepts a new program/schema
declaration; compiled namespace-edge bookkeeping is explicitly excluded.
Repeated already-active `my.plan/active!`, `my.plan/status`, and pure arithmetic
therefore advance the existing no-progress streak, while `message/user` resets
it. The red loop characterization previously ran all five allowed turns and
closed at `:turn-limit`; the corrected focused matrix closes after the existing
three-turn bound with `:no-forms`. Live current-artifact Inspect proof remains
after rebuild, including the separate fact that `delegate!` returns a child id
rather than synchronously awaiting the child's report.

The rebuilt live default invalidated the first correction while proving its
boundedness. One request completed in 31,867 ms after an initial plan read, one
write, and three repeated status reads. A second prior-style namespace request
returned HTTP 200 in 24,274 ms after only three turns, but those evals were
three distinct useful reads: `plan/position`, the whole plan tree, then one
root subtree. Closing that run at `:no-forms` discarded newly acquired
knowledge. The replacement loop fold therefore compares the ordered stable
eval observations already persisted on each turn—source, status/ok, result,
output/error, and ending namespace. Durable writes reset the repetition state;
a distinct observation starts at one; only the identical observation increments
the bound. No source parsing, digest, watchdog, or work-budget change is added.
Commit `6e3b741d` closes that replacement contract in the live runtime. A fresh
agent emitted three identical `(my.plan/position {})` observations and closed
`:no-forms` after 18,244 ms, three turns, and three evals. The prior-style root
scenario emitted position, position, full-tree, active-plan status, and position
reads across six turns in 36,456 ms and timed out rather than closing at the
three-turn repetition bound. Distinct observations therefore remain useful
while identical polling stays bounded. The root still did not delegate because
its attention remained on a stale active plan; that is scenario/driver-context
evidence, not a defect in repetition accounting. The child-delivery scenario
also remains separate because `delegate!` resolves a child ID rather than
synchronously awaiting the child's report.

The same controlled branch also reproduced the one-call release race with the
writer's exact `database-in-use` response. Pod containment completed cleanly
and recorded root plus child as unhosted, but selector-owned UDS acquisition
cleanup had not reached the writer registry before `release-database`. The
first close left the durable record at desired state `closed` and phase
`stopping-pod`; a second close seconds later released and deleted it without
rerunning containment. The branch operator now waits for that already-proved
process shutdown to reach the writer by retrying only the exact transient
`database-in-use` release response for a bounded interval. Every other writer
failure remains terminal. A focused regression forces two such responses and
proves one invocation converges; live current-source proof remains after the
next coherent watcher build.
