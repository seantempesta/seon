---
type: plan
status: design 2026-09-22 (orchestrator, from the owner's rulings and the four data packs); astra review requested before commit 2
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
| **save-time gate** (a file edit by a filesystem agent) | `index!` the changed declarations into the candidate branch of default; acquire on it | the reaching tests, as above | green → advance default's rows, `require :reload` the changed namespaces + dependents; red → the failures back to the editor | the candidate branch persists (it is default's staging) |
| **merge** (isolated → cluster) | three-way diff by digest; non-conflicting rows transacted onto an intermediate branch; acquire on it | the reaching tests, as above | green + named accept → `force-branch!` with the expected commit (lineage via `merge!`) | unlink the intermediate and the source branch |

One acquire, one evaluate, one release. The callers differ only in which commit they
start from and what they do with the result.

## 2. The functions — composed, refactored, new

| step | function | status | what changes |
|---|---|---|---|
| custody | `seon.db/call-with-custody` (`db.clj:370`) | **refactor** (P1) | the ONE binding scope; `seon.sci.eval/evaluate` (`eval.clj:2826`) calls it instead of open-coding the bindings; the runner already does (`runner.clj:687`) |
| branch | `seon.cluster.registry/branch!` (`:178`) → `seon.cluster.store/open-branch!` (`:534`) | compose | off a captured commit (test, merge) or the cluster head (isolated agent); no new wrapper |
| context | `seon.sci.eval/fork-cluster-ctx` (`eval.clj:2293`) | **refactor** (P2) | becomes the ONE production fork onto another connection (today only the fixture calls it); the fixture's base-lease wrapper deleted; environment derivation passed in |
| acquisition | `install-row!` (`:894`), `acquire-program!` (`:1709`) | **refactor** (M1–M3, commit 1) | overridden = digest ≠ the JVM's loaded commit; affected = `seon.fn/gate-sets` (`fn.clj:1505`) over the overridden seed; both interpreted through `install-function-from-database!` (`:709`); `:jvm-fallback` (`:909`) → named refusal; host-bound (by `:seon.fn/defined-by`) refuses an override by name |
| entrance | `seon.cluster.agent/acquire-context!` | **new** (commit 2) | the one composition: (parent handle, commit, request) → branch! → open-branch! → fork-cluster-ctx → acquisition; returns the handle D1 §2a already requires; used by isolated agents, tests, the gate and merge; NOT by live turns (they hold the cluster connection) |
| mode | `:seon.agent/branch` | **new attribute** (commit 2) | the agent's custody target; live = the cluster branch; task start sets it; `my.*` branch/merge functions write it |
| evaluate | `seon.sci.eval/evaluate` (`:2700`), `run-test` (`:3208`) → `test.runner/run-vars!` (`:656`) | compose | unchanged bodies; `bounded-result` (`test.clj:141`) already dispatches to `run-test` when a ctx is handed |
| select | `seon.test/select` (`test.clj:840`), `seon.fn/gate-sets` | compose | the reaching set from the changed identities; unchanged |
| request | `seon.test/run` (`test.clj:537`) | **refactor** (P4, commit 4) | `[execution-value recording-connection selection bound]`; internally `acquire-context!` + select + `run-test` + record; `run-owned`/`check*`/workers/slots deleted (B4 c2/c3) |
| index | `seon.fn/index!` (`fn.clj:3305`) | compose | already takes any connection; the gate indexes the changed declarations of a saved file into the candidate branch |
| publish | `seon.cluster.source/publish!` (`source.clj:354`) | **refactor** (M5, 1.2b) | destination branch a request member (today `current-branch` hard-coded, `:29`); the envelope (manifest, seal, snapshot) deleted |
| reload | `seon.cluster/refresh-source!` reload span (`cluster.clj:1980-1984`, `reload-order` `:1925`, `development-namespaces` `:2006`) | **refactor** (M6, 1.2b) | the reload is its own request after a green gate, never implied by publication; `instrument/apply!` receives the changed identities (1.3) |
| diff | `seon.program/definition-digest` (`program.cljc`), `source/changed-identities`, `test/changed-since-green` (`test.clj:61-99`) | **new** one pure function (merge pack §8) | `(three-way base a b) → {unchanged changed-a changed-b conflict added retracted}` over three `{identity → digest}` maps; everything else composes |
| merge | `source/publish!`'s scratch-branch → reconcile → validate → `force-branch!` `:expected-current-commit` (`source.clj:461-479`) | compose | the same guarded advance, fed by the three-way result; Datahike `merge!` (`versioning.cljc:734`) records lineage |
| accept | the cluster pointer advance | **new** one function | named accepter (root or owner) recorded in tx provenance; green is necessary, not sufficient |
| release | `registry/retire-branch!` (`:327`) | compose | unlink; `collect!` (`:503`) reclaims under the declared window |
| partition | `:seon.program/partition` on entity schemas | **new fact** (program pack §3) | `program-attributes` derived from the compiled registry; merge, GC and the gate read the same query |
| host-bound | `:seon.fn/host-bound?` | **new fact** (host-bound pack §3) | indexer-derived: refused form head ∨ unresolvable host reference in the row's own body |

Nothing else. No runner engine, no candidate cluster, no second fixture, no cache.

## 3. Commits, mapped to the waves in README §4

1. acquisition correctness (M1–M3, P1, host-bound by form head) — **running**
2. `acquire-context!` + `:seon.agent/branch` + a branch member on the MCP eval tool; `fork-cluster-ctx` as the production fork (P2); `my.*` branch/merge requests
3. publish to a branch, reload as its own request (1.2b, **running**); the hook as one prepl request (1.5)
4. `seon.test/run` on the entrance (P3/P4/M8); B4 machinery deleted
5. the two facts in the indexer (partition, host-bound body half)
6. three-way diff + merge + accept; write-back as a separate spec (spans, not regeneration)

## 4. Proofs that decide it

- commit 1: the six-part two-branch proof (lane spec).
- commit 2: an isolated agent and a live agent on one cluster: the isolated `defn` is invisible to the live agent until merge; the live `defn` is visible to every live agent at their next evaluation; acquisition cost by commit id (unchanged head → 0 work).
- commit 4: a `deftest` on a branch runs through `acquire-context!`; the branch is unlinked after; the fixture never copies a store.
- gate: a saved file with one failing test never advances default; a green save reloads only the changed namespaces + dependents, timed.
- merge: two agents change disjoint functions → both land; the same function → the conflict stays on the intermediate branch and the merge report names it.
