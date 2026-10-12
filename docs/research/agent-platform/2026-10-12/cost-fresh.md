---
type: research
status: partial; one duplicate admission removed; full acquisition target open
---

# Fresh MCP acquisition, 2026-10-12

## Existing flow and change

`seon.mcp/execution` creates a work branch through `my.agent/create-branch!`
and `seon.cluster.registry/fork!`, then calls `seon.cluster.host/host!`.
The fork creates a working branch and a retained base branch, and writes
the fork entity. Hosting writes the agent admission on main and separately
on the working branch. Recording is a fourth transaction.

The change implements the admission ordering in
docs/seon/schedule/cards/TX-1.md: `seon.cluster.host/register!` writes the
admission before the fork. Datahike branching then inherits it. Hosting an
already admitted agent produces no admission write. The writer still
decides every new or conflicting admission through
`seon.cluster.host/home-call`; no new refusal, lock or retry is introduced.
An admission whose branch has not yet been created completes that creation
on its next MCP request. The regression exercises that intermediate state.

This removes one durable transaction per fresh request and both empty
admission transactions when rehosting an existing agent. It adds a bounded
entity pull per admission check. No new cache, registry or stored attribute.

Dependency seams: Datahike c9e57c6fb65365d5ce92b59854fb02ea59054fe7,
`datahike.versioning/branch!` inherits the selected committed database's
indexes and facts; `datahike.db.transaction/apply-db-op` evaluates the
admission transaction function against the writer's database. Malli
dd13ff81139159778edb0a67b491eccc0baa991d, `malli.core/validator` and
`malli.core/-instrument` validate the new function's named inputs and
existing result union. No dependency was changed.

## Quiet end-to-end evidence

The reusable script is `script/seon/cost_fresh.clj`. It uses the existing
operator candidate acquisition, publication, boot and shutdown functions,
runs no tests during measurement, and deletes only its own candidate root.
Each of seven successive requests uses a new agent id. The measured form is:

```clojure
(seon.profile/measure
 #(#'seon.mcp/eval-clj
   {:seon.mcp/code "(+ 1 2)"
    :seon.mcp/agent "<new id>"
    :seon.mcp/cluster "default"
    :seon.mcp/mode "sci"}))
```

The candidate is d099a4cfc8485c950be882cb74212431a6d913db, whose exact
parent is 3780e11072cb91d8941a379ea8a41065ecc10be1. Raw replies are
cost-fresh-before.txt and cost-fresh-after.txt in this directory.
Both use the operator's template store, not default's larger live store.
No claim is made about installed default performance.

| Measure | Parent | Change |
|---|---:|---:|
| First fresh request, complete `measure` span | 411.45 ms | 328.32 ms |
| Subsequent six fresh requests, complete span | 182.22–221.83 ms | 140.90–184.38 ms |
| Median of those six | 209.37 ms | 156.325 ms |
| Median `:custody` | 129.35 ms | 90.60 ms |
| Median calling-thread allocation | 27.144 MB | 28.034 MB |
| Median JVM allocation window | 108.223 MB | 102.518 MB |
| Checked transactions, including recording | 4 | 3 |
| Branch creations | 2 | 2 |

The complete-request median improves 25.3%. Calling-thread allocation
increases; it excludes execution and writer threads. JVM allocation windows
include all activity in that candidate and are not per-agent attribution.
The first request remains separately visible; priming does not erase it.
All fourteen requests return without an MCP error.

`seon.cost-fresh/assert-improvement!` asserts the end-to-end median comparison
from these saved `seon.profile/measure` replies; the assertion passes.
Transaction counts support the explanation, not the speedup claim.

An initial parent run used bde4bf86a. HEAD changed before candidate creation,
including a `seon.fn` edit. That run is preserved as
cost-fresh-initial-parent.txt but excluded from attribution. The exact parent
was measured after detecting the mismatch; no prior lanes' measurements
were repeated.

Reproduce with `bb --config /dev/null --classpath script:src:resources -m
seon.cost-fresh /Users/sean/src/seon COMMIT`, saving its printed replies,
then call `seon.cost-fresh/assert-improvement!` with the two paths.

## Proof and limits

The parent-only candidate ace4891e83a2bc87e07c0c505445a6ec25490fda ran
`seon.mcp-entrance-test/a-fresh-request-inherits-one-admission`: run
528b02e7576f, seven passing assertions and three failures, no errors,
3,885 ms. Its transaction assertions were red (four versus three).
Its 150 ms timing assertion already passed on the ordinary test fixture;
that test alone does not prove this performance change. The quiet measured
comparison above supplies that evidence.

Candidate cec01dc7e694e59b34428b2a4371036444a21979 passed five focused
tests, 21 assertions, no failures or errors, run d23f8f97a86f. Test bodies
took 5,006 ms; the whole check took 26,384 ms, including publication
1,644 ms, reload 537 ms and boot readiness 6,123 ms. It covers the fresh
request, rehosting without a transaction, conflicting simultaneous homes,
retirement racing submission, and retired-agent refusal. The final check
also includes the intermediate admission case and existing host and MCP
behavior tests. The rehosting assertion now runs through the MCP test.

The first commit check ran eleven tests: candidate
8ecbb20e331f0f722c61a02bfc70ef5964cfec01, run 989ada8850af, 46 passing
assertions, six failures, no errors, 10,415 ms. Three existing tests were
red. All three are also red on the exact committed parent 3780e1107:
run 0d7e8a9f28b9, four passing assertions, five failures, one error,
7,382 ms. Their behavior is not claimed fixed:

- `seon.cluster.host-test/a-message-to-a-stopped-admitted-agent-hosts-it-and-its-db<->flow-takes-it`:
  no message id and no resumed host on both parent and candidate.
- `seon.cluster.host-test/retirement-closes-admission-at-the-writer-and-races-submission`:
  candidate's raced reply matches none of its asserted outcomes; parent's
  channel closes before delivering the required event. It passed in the
  earlier five-test candidate, so it is not a stable passing proof.
- `seon.mcp-entrance-test/a-jvm-call-is-the-agents-recorded-run-and-its-value-digs-by-handle`:
  JVM and follow-up SCI replies report errors and the expected value is absent,
  on both parent and candidate.

The final commit names the other eight tests individually and excludes
these independently reproduced parent failures. This does not turn them
green or claim the broad suite passed.

Full working-tree lint with `clj-kondo --lint src script test --cache false`
reports zero errors, 1,590 warnings, 7,149 ms after the final test change.
This is not a claim that the existing warnings are fixed.

Default initially runs 5640aef92 and both status entrances answer with
existing errors and failed tests. The lane's first SCI form,
`(seon.context/current-state)`, returns only the turn allowance, with no
assigned task; complete request 194.6 ms. The source edit hook reports a
validation refusal, then acquisition signature
500ddeeeca094b0ccd26f74c3bd90c64c2775fa762f11344b7f8b9de5ec9aec1
and an unresolved new `register!` in `seon.cluster.host/start!`, signature
7c52d6ce256964e2e1bdc5b72c6eb450ac4bba47d1e27dcf3c47032b3643a91d.
Hook requests take 6–55 seconds. Neither signature pulls as a main entity
in a later explicit root read; that does not prove the branch-local errors
were unrecorded. SCI publication is unavailable evidence, not a pass.
Compiled publication, reload and execution are proven by candidate checks.

## Remaining target and ownership

The requested one branch and one acquisition transaction are NOT achieved.
The original task
`seon.mcp/a-fresh-agents-first-eval-request-acquires-its-branch-cheaply`
remains open; the partial admission test is deliberately not tagged as its
complete proof.

The retained base is consumed by `seon.cluster.source/merge-base`,
`seon.turn/fork-commit`, nested `seon.cluster.registry/fork!`, and collection.
Deleting it alone loses bases older than the collection window. The owning
hybrid-done-and-merge.md already assigns its deletion to M17, after M13
and M6: explicit Datahike GC roots from open assignments, conversion of
every base reader, and the zero-window collection proof
`seon.cluster.registry/a-merged-base-older-than-the-window-survives-collection`.
The remaining fork-entity transaction must be composed with that assignment
and branch creation, rather than suppressed independently.

Foreign ownership: m3 owns the agent and assignment changes and cluster
tests, r1r2 owns test selection and execution, g9 owns `my.program`, and r3
owns SCI evaluation. None was contacted. Concurrent edits appeared in the
claimed `test/seon/cluster/host_test.clj`, including a transient unresolved
`datahike.api` reference during a lint. The lane removed only its own new
test, moved the rehosting assertions into its MCP test, and released the
host-test file. The final candidate excludes that file and uses its committed
tests. No dependency bump,
worktree, default restart, or reset was performed. Root must use its
scheduled restart batch for M17's Datahike change.

**RESET NEEDED:** install the committed admission change through root's next
batched `start --head`, retaining the store. The quiet candidates were
stopped and their disposable roots removed by the operator.
