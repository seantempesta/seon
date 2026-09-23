---
type: plan
status: design 2026-09-22 (orchestrator, from the owner's rulings and the four data packs); Astra-reviewed 2026-09-22; commits 1, 2, 4 landed; README 1.3d's (5) machinery deletion running as realities-commit-5
owner: track 1.3d (README §4), composing A2, B1, B2, B4, D1 seams
---

# One lifecycle — the same functions for a turn, a branch, a test, a save and a merge

The owner's constraint: **compose the concepts we have; never a duplicate; refactor a
seam to be adaptive when it cannot serve every caller as it stands.** This page is the
composition. Every function named below exists at the cited line unless marked
**refactor** (an existing function gains an argument or loses an assumption) or
**new** (one function with no existing owner). Facts come from the four data packs of
2026-09-22 ([tests as agents](../../../research/agent-platform/tests-as-agents-data-pack-2026-09-22.md),
[program rows](../../../research/agent-platform/program-rows-data-pack-2026-09-22.md),
[host-bound rows](../../../research/agent-platform/host-bound-rows-data-pack-2026-09-22.md),
[merge and write-back](../../../research/agent-platform/merge-and-write-back-data-pack-2026-09-22.md),
[reload](../../../research/agent-platform/reload-into-the-repl-data-pack-2026-09-22.md)).

## 1. The five callers and the one path

| caller | acquire | evaluate | settle | release |
|---|---|---|---|---|
| **live turn** (agent on the cluster's branch) | context from the cluster head by commit id | forms under custody of the cluster connection | its writes ARE the head | nothing (the branch is the cluster) |
| **isolated agent** (branch off the head) | branch! → open-branch! → fork-cluster-ctx | same | merge request | unlink after accept or abandon |
| **test** (one body) | same three, off a captured commit | `run-test` under custody | record member facts on the recording connection | unlink |
| **file-only change** (host-bound, schema, new namespace — D1 §2e; owner 2026-09-23: lanes work on their own named branches) | publish the named paths onto the lane's OWN branch; acquire on it | the reaching tests, as above | merges like any branch: root's named accept, then write-back; default advances only through that path | the lane's branch, unlinked after accept or abandon |
| **merge** (isolated → cluster) | three-way diff by digest; non-conflicting rows transacted onto an intermediate branch; acquire on it | the reaching tests, as above | green + named accept → `force-branch!` with the expected commit (lineage via `merge!`) | unlink the intermediate and the source branch |

One acquire, one evaluate, one release. The callers differ only in which commit they
start from and what they do with the result.

## 2. The functions — corrected by the [astra review](../../../research/agent-platform/one-lifecycle-astra-review-2026-09-22.md) (2026-09-22)

The review's verdict: the owners are mostly right; the first draft's composition was not
executable. Corrections taken in full: `seon.cluster.agent/acquire-context!` already
EXISTS and is refactored, never re-created; `gate-sets` stays the test projection of a
reverse walk that now also exposes the executable closure (one walk, one more member);
`force-branch!` cannot advance an open live connection, so acceptance goes through the
prepared database writer with a destination-basis guard; the save-time gate is NOT
installed today (the hook adopts first, checks after, both switches off); the three-way
comparison is the one genuinely missing piece. Contracts below are proposed, not installed.

| # | owner / operation | smallest contract serving §1 |
|---|---|---|
| 1 | `program` / indexer schema facts | program ownership derived from declared shapes (`:seon.program/partition`); canonical digest and host-bound evidence produced in canonical analysis; other writers respected |
| 2 | `registry/branch!`, `store/open-branch!` | allocate ONLY on an isolation request, from the exact retained commit, fresh owned name; otherwise borrow the held live/staging/intermediate connection |
| 3 | existing `agent/acquire-context!` | **refactor**: consumes/produces D1's execution handle (store/branch ownership, connection, captured db/commit/projection, ctx/environment, owned resource scopes) while its live callers convert; no inherited graph start; task start selects custody |
| 4 | `fork-cluster-ctx`, `fork-for-turn` | repoint receiving custody/environment once; retain the per-agent private ctx at later boundaries; never substitute a db value for a connection |
| 5 | `sci.eval/acquire!` and the installers | acquire matching selected/loaded program identities; interpret the override/affected closure; re-arm changed contracts; refuse unsupported rows (commit 1) |
| 6 | existing `fn` reverse walk | expose the full declared executable closure for acquisition; `gate-sets` remains its conservative test projection (commit 1) |
| 7 | `db/call-with-custody`, evaluator/kernel | one explicit connection/read-basis scope and admitted bound for form and test invocation; actual completion/exit evidence returned |
| 8 | `test/select` | execution program and recording evidence read separately; conservative obligations, per-member reuse, host classification, named missing coverage |
| 9 | `test/run`, SCI test invocation, recorder | one request; member children from the captured commit; canonical fixture semantics; durable completion/tally; the exceptional platform host retained |
| 10 | `fn/index!` + `source/publish!` preparation | target a held candidate; exact admitted inputs and prior db; canonical analysis/reconciliation before visibility; preparation reusable without an early live advance |
| 11 | `program` three-way comparison | **new**: complete typed digest maps at base/branch/head → take/keep/conflict including absence; canonical readers; bounded retained-basis reads (the merge pack's ~130 ms `since` floor); the combined derived closure recomputed |
| 12 | D1 accept over the prepared writer | named request + tested branch/head/run; atomically validate/install the program delta and settlement on the destination connection; the merge writer adapted for guarded lineage |
| 13 | existing reload + operator/hook | shared evaluation boundary; exact tested bytes; ordered dependent reload/unmap/re-arm; a terminal red/degraded/accepted result reaches the caller |
| 14 | existing lifecycle/store/registry | after actual exit and durable evidence: stop owned resources, release the owned connection, unlink when repair/retention no longer needs it; borrowed branches persist; GC separate |

Guarantees named by the review, at their existing owners: one world per evaluation;
fresh isolation and complete release; tested-head acceptance without lost data; save
feedback before mutation; change-proportional work (a raw SCI fork is one env atom, not
the cost of acquisition — measure closure, analysis, retained-history reads, heap and
compile time separately).

## 3. Commits, mapped to the waves in README §4

1. acquisition correctness (rows 5, 6, 7) — LANDED `39a337013`
2. LANDED `1ada78050` — **acquisition only** (review option 1, taken 2026-09-22): live/isolated handle acquisition through the refactored `acquire-context!` (row 3), branch custody (row 2), `:seon.agent/branch`, a branch member on the MCP eval tool; working branches available; NO merge-facing `my.*` request yet — its proof is isolation and visibility, not merge
3. publish to a held candidate, reload as its own request (1.2b; reload per declaration B/C/D landed `2bd568c08`, `da703089b`, `af5eea72f`); the hook as one prepl request returning the terminal result (1.5, row 13; landed `324d41507`)
4. `seon.test/run` on the handle (rows 8, 9) — LANDED `678009fcd`; B4 machinery deletion is README 1.3d's (5), running as realities-commit-5
5. the two facts in the indexer (row 1) — host binding LANDED `25f315779`
6. three-way comparison (row 11) LANDED `b51a24055`, `e4cd4ee97`, `d8734f1e7`; accept over the prepared writer (row 12), `my.*` merge request, release (row 14) open; write-back as a separate spec

## 4. Proofs that decide it

- commit 1: the six-part two-branch proof (lane spec).
- commit 2: an isolated agent and a live agent on one cluster: the isolated `defn` is invisible to the live agent until merge; the live `defn` is visible to every live agent at their next evaluation; acquisition cost by commit id (unchanged head → 0 work).
- commit 4: a `deftest` on a branch runs through `acquire-context!`; the branch is unlinked after; the fixture never copies a store.
- gate: a saved file with one failing test never advances default; a green save reloads only the changed namespaces + dependents, timed.
- merge: two agents change disjoint functions → both land; the same function → the conflict stays on the intermediate branch and the merge report names it.
