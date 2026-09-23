---
type: plan
status: implementation specification; APIs below are proposed
created: 2026-09-23
revised: 2026-09-23 (orchestrator ruling: no new request schemas, no reason enum, one test request)
lane: namespace-agent-slice-4
---

# Compose preparation, gate and named accept

Implements [first-loop slice 4](lane-namespace-agents-first-loop.md), [D1 §2c](lane-d1-isolation-merge-writeback.md#2c-explicit-merge-through-the-tested-head) and [lifecycle rows 11–12](lane-realities-one-lifecycle.md); README §4/§7 governs order. Two functions in `seon.cluster.source` accept an isolated candidate branch's database definitions into an ordinary cluster. Default remains the files. No export, settlement, new entity, schema declaration, error taxonomy, runner, selection, cache or lifecycle.

Grounding: committed HEAD `e9e66028d`, Datahike gitlink `131ca6360`. Line refs are that HEAD. **Every `cluster.clj` reference: re-verify after opus-publication lands** — that lane is changing the save gate so deletions select reaching tests from the BEFORE program, rows and adoption record land in one transaction, and the gate calls `seon.test/run` for selection instead of `reaching-run`'s intersection ([Sol audit](../../../research/agent-platform/sol-audit-landings-2026-09-23.md) bugs 1 and 3, bloat 1–2).

## Requests: open maps of existing keys

No schema is added to `seon.cluster.edn` or elsewhere. Arguments are positional values of already declared schemas; results are open maps of already declared keys.

```clojure
;; prepare-merge! [source held-store candidate issue]
[:=> [:cat :seon.agent/context-source :seon.store/store :seon.store/branch :seon.issue/id]
 [:or [:map [:seon.source/candidate :seon.store/branch]          ; S, the tested branch
            [:seon.test.run/id :seon.test.run/id]                  ; the one run
            [:seon.test/passed? :boolean] [:seon.source/tally :string]
            [:datahike/expected-basis-t :int]                      ; H's basis-t
            [:parents [:set :seon.source/commit-id]]]              ; #{C}
      :seon.program/three-way                                      ; nonempty conflict
      :seon.program/digest-map-refusal :seon.test/selection-error
      :seon.db/transaction-refused-error]]
;; accept-merge! [source held-store proposal]  — proposal is prepare's map
[:=> [:cat :seon.agent/context-source :seon.store/store <prepare's map>]
 [:or :seon.db/transaction-report :seon.db/transaction-refused-error
  :seon.source/test-evidence-error :seon.program/digest-map-refusal]]
```

Reused keys and why each is exact: `:seon.source/candidate` is already the gate's name for the branch it tested; `:seon.test.run/id` names the run; `:datahike/expected-basis-t` and `:parents` are the writer request's own members (`db.clj:4489`), so the proposal is literally the accept transaction minus `:tx-data` and E. H needs no commit id: Datahike's `merge-writer!` adds the destination branch head itself as a parent (`writing.cljc:883–891`), and the expected basis pins it. B needs no input: it is derived from C and H.

A refusal is the flat `:seon.error` value of the owner that refused, returned whole: lineage and comparison (`:seon.program/digest-map-refusal`, `:missing-evidence :seon.program/history` or `:read-bound`), the test owner's selection (`:seon.test/coverage-unknown`, `:identity-unresolved`, acquisition refusals — `test.clj:1296`), the writer (`:transaction/stale-basis`, `writing.cljc:872`), and accept's evidence check (`:seon.source/test-evidence-error`, already "the test request whose admission or completion the source authority refused", `seon.source.edn:3–7`). A conflict is not an error: prepare returns the three-way value itself, the data the agent repairs from. Unexpected exceptions rethrow with their whole cause.

**Authority:** owner-invoked JVM request only, after inspecting the proposal. No `my.*` accept wrapper, no worker-completion call, no claim that any key authenticates; a root-agent accept needs D1's request boundary first.

## Algorithm and cost

D = candidate-changed identities, R = required tests, L = commits walked to the merge base.

| Step | Seam at HEAD | Cost |
|---|---|---|
| Capture | `source.clj:280` `commit-database` for C (candidate branch head, `registry.clj:134`) and H (source connection head); release materialized values in `finally` | O(index pages read) |
| Merge base B | New `seon.program/merge-base [c h bound]`: alternate parent walks from C and H over `parent-commit-ids` (`versioning.cljc:467`), shaped like `branch-history` (`:191`) but reading stored meta only and stopping at the first commit reached from both sides. Each visited commit counts against `:seon.program/max-datoms`; exhaustion or a missing commit returns `digest-map-refusal` | O(L), never whole history; never compares basis-t across lineages |
| Compare | `program.cljc:578` `changed-identities B C` and `B H`; `:449` `digest-map` scoped to D; `:620` `three-way` | O(D) compare; the history reader is O(retained digest history since B), an existing limit of that owner |
| Delta | `fn.clj:3312` `published-index-rows C taken`; `program.cljc:242` `shapes-in` once; `:1335` `exact-replacement-tx-in` against S's current rows | O(D + owned components) |
| Gate | The gate owner's branch → write → one `seon.test/run` path (today `cluster.clj:2667` `candidate-gate!`; re-verify). Prepare supplies a fresh `seon.id` branch, a `write!` that transacts the delta and answers D, and an advance that does nothing. The test request carries `:seon.test/policy :named`, `:seon.test/changed` D and `:seon.test/identities` the issue's `:seon.issue/tests` | Whatever that one request costs (Sol audit: 10.7 s whole-program digest per new commit — the test-overhead lane's defect, not hidden here) |
| Accept evidence | E = S's head now. `runner.clj:1838` `run-results E run-id`; every member in `test.clj:537` `green-members` (made public, one token); `:seon.test.run/tested-branch` = S; `changed-identities` from the run's `:seon.test.run/basis-t` to E empty (same lineage, so comparable; REPL-verify how to hand the comparison that basis) | O(members + program changes on S since the run), normally zero datoms |
| Accept write | Delta = `changed-identities H E` rows read from E — exactly what was tested — regenerated as exact replacements against the current destination; `db.clj:4489` `transact!` with `:datahike/expected-basis-t` and `:parents #{C E}`, dispatched to `d/merge-db!` (`db.clj:4260`) | O(D + affected validation) |

The run is never repeated at accept. A later green run cannot stand in for the named one; `latest-results`/`verified?` are not substitutes. If H moved, the writer refuses before any datom lands (`writing.cljc:872`); a write-bound result means outcome unknown — observe it, never retry. S and C stay on the roster for review; no unlink or GC before D1's retained-evidence proof. Since accept derives its delta from E rather than C, a C movement after prepare is irrelevant, and a gate that branched from a newer head than the captured H is caught by the stale basis.

## Restriction and budget

First cut: existing non-host-bound function/test replacements only (`changed-identities` already refuses other families, `program.cljc:578`; `exact-replacement-tx-in` needs a current row), no additions or deletions, no host-bound change (unknown refuses), one issue with existing tests. Empty net delta refuses; nothing is manufactured.

Budget, physical added src lines: `merge-base` 15, `prepare-merge!` 35, `accept-merge!` 30, `green-members` public 0–1 — **≈ 80, schema 0**, tests ≤ 100. If the gate after opus-publication cannot be called without advancing, the change belongs to the gate owner in `cluster.clj`; report it, never copy the path. File holds needed: `src/seon/cluster/source.clj`, `src/seon/program.cljc`, `src/seon/test.clj` (the `defn-` only), `test/seon/cluster/merge_test.clj`; `cluster.clj` only if that gate change is needed.

## Regressions and proof

One regression per class in `seon.cluster.merge-test`, canonical fixtures, real SCI, armed contracts, no Var replacement: (1) replacement with colliding branch eids, indirect SCI call, parents #{H C E}, C moving after prepare; (2) conflict returns three-way, equal revert is unchanged; (3) coverage refusal from the test owner; (4) red, unfinished, other-run and later-program-write evidence refuse with `test-evidence-error`; (5) stale H, zero partial datoms. Existing save-gate regressions keep passing.

After adoption is positively verified, ONE request on default's JVM: `bin/test-check default --ns seon.cluster.merge-test`. Record B/C/H/S/E/run, selected/executed/reused counts, refusals, clocks, memory; every result over 1 s names its proportionality. This document was verified by source and Datahike inspection only; no JVM, test or publication ran.
