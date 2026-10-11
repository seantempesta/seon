---
title: "Hybrid: the one path, starting an agent's Flow from a point in time"
type: research
status: hybrid of plan-start-a-flow-opus.md (d6b63dcdc) and plan-start-a-flow-sol.md (7bfcdc75c) over dig-start-a-flow.md and start-a-flow-benchmark.md; phases filed as tasks; design only
basis: HEAD 7bfcdc75c read by symbol; Astra's uncommitted fork-head fix (lane astra-cache-1-2, `seon.cluster.registry/fork!` remembering the fork-entity commit, proof `seon.test.runner-test/a-reopened-fork-reuses-its-programs-proof-reach`) read as a diff; one read-only `seon.cache/status` probe on default (2026-10-11)
---

# The one path, hybrid

## 1. The design in one paragraph

`db<->flow` (R1) writes the attempt and the agent in **one main transaction** (task, agent id, branch name, intent,
fork-point commit id), then calls **one function, `seon.flow/start-flow!`**, with that row. It composes five owned parts
and renders nothing: (1) **branch** = `seon.cluster.registry/fork!` from the fork-point commit (today: pointer + fork
entity; last phase: pointer only), `created? false` on a repeat; (2) **world** = `seon.turn.world/world` over the branch
connection; (3) **environment** = one `sci/fork` of the content-keyed program source `seon.sci.eval/base-ctx` finds,
with the agent's private layer applied to that same fork; (4) **graph** = `flow/create-flow` + `flow/start` on its own
executor, `flow/resume` only under intent `:running` (Flow starts paused), registered in the roster; (5) nothing else:
the **task drives the context in the first turn**, `seon.context/current-state` reading the task from main (its
intent, Sol's "task control basis") and the program from the branch. A restart is the same call with `created? false`;
a test member is the same call with `graph? false`; root's and problem tasks are the same call on main. **The one fix
underneath everything is identity**: a reopened commit must know which commits its program's attributes equal. Today
Datahike stores no revision map in a commit, so `connect` and `commit-as-db` open every fork head as the conservative
revision and every content-keyed cache (overlay plan, overlay context, reach) misses on every fresh fork. The hybrid
fixes it at the producer, in our Datahike fork: **the revision map rides the commit**, so no Seon-side remembering,
no second identity and nothing lost at eviction or restart. Until that lands, Astra's `remember-revisions!` after the
fork write is the bridge.

## 2. Where the plans agree, where they differ, and the choice

| Point | Opus | Sol | Choice and reason |
|---|---|---|---|
| One assembly, no second runtime; attempt written first on main; branch + world + SCI fork + paused graph + resume by intent; test member = same call without graph; `arm!`/`arm-graph!`/`host!`/the branch armer graph fold into `start-flow!` | yes | yes | **agreed** |
| Root cause: revision identity lost across fork/reopen | yes, measured (171 attributes differ) | yes, "fix at the producer, not every cache" | **agreed**; verified: `datahike.versioning/attached-cache-context` and `datahike.connector/connect` drop `attribute-revisions` for a commit the connection did not write; `writing.cljc` stores none. Default's cache probe: `::commit-revisions` 87 hits/730 misses, `::overlay-plan` 2/76, `::overlay-context` 19/78 (71 evictions) |
| Where to fix identity | `release-branch!` remembers each released head in `seon.cache` | investigate Datahike `db->stored`/`stored->db`; no parallel Seon index | **Sol's, done**: persist in the stored commit the attributes it modified and the prior revision map (both known before `create-commit-id`, so no self-reference; the commit's own id is the modified attributes' revision on load). Then delete `remember-revisions!`, `::commit-revisions`, the chain walk in `seon.db/content-revision`, and `seon.sci.eval/program-identity`'s LRU. One mechanism, library-owned, survives eviction and restart. Astra's `fork!` fix is the bridge (H1), not the design |
| Second identity copy `seon.sci.eval/program-identity` (core.cache LRU) | delete; key `base-ctx`/`overlay-plan` by `seon.db/content-revisions` | reuse under corrected identity | **Opus**: one identity (`seon.db`'s); also retires task `seon.sci.eval/program-identity-caches-live-outside-seon-cache` |
| Duplicate overlay entries: an empty plan still weighs ~60 MB per fork | empty plan → the base is the source; non-empty keyed by plan digest | not addressed | **Opus**: verified key `[base-key program-basis dials]` makes one entry per fork; 314 MB of the 512 MB budget (Opus), 71 of 78 misses evicted (probe) |
| Reach key (CACHE-1) | key may already be right | must keep graph facts (calls, references) | **both right**: `seon.test.runner/reach-revision-key` already includes `:seon.fn/calls`, `/references`, `/keywords`, `/spec`, `:seon.test/subject`; the miss was identity. CACHE-1 as a card is superseded by H1/H2; CACHE-2 (contract references) stays Astra's with Sol's caution (a form digest alone does not identify Malli's registry scoping) |
| Context at start | first turn renders; start renders nothing (R1) | prepare context before start as readiness evidence | **Opus**: a render is a read of the task and program; doing it for an agent that may stay paused is work that may never be shown. An inspector renders a paused agent's context on demand (read-only, cached by what it read). **Sol's basis split is kept**: the task is read from main, the program from the branch; `start-flow!` never reads a task from a historical commit |
| SCI forks on the start path | one fork: delete `fork-cluster-ctx`/`acquire!` from the path, `fork-for-turn`'s second fork, the five history queries for a fresh agent | reuse equal-program sources | **both**: identity first (H2/H3), then one fork (H5) |
| Fork entity / branch transaction | delete: the attempt carries the fork point, merge base from Datahike lineage | keep one fork transaction | **Opus, last**: `fork-of`, `advanced-base-t`, `branch-fork-entity`, `seon.cluster.source/compare-branch` read `:seon.branch/base-t`; cross-owner, ~25 ms of the floor; land after the attempt carries the fork point (H8) |
| Flow start | paused, resume by intent, own executor | same, plus positive `ping` of every proc | **agreed**; Astra measured 1.13 ms / 0.63 MB warm |
| Members: branch per member or `d/with` | unsure | fork the requester's environment at the captured commit, no attempt per member | owner question Q1 |
| Agent row on the branch | main only; the fork inherits it when forked from main's head | — | **main is the owner**; H7 measures who reads `:seon.agent/*` from a branch value (`close-admissions!`, `retire!`) and converts them |

## 3. The parts: data, home, key, measured now, floor

| Part | Data needed, where it lives | Cache key | Measured now (warm, fresh fork) | Floor and why |
|---|---|---|---|---|
| main write: attempt + agent | task, roster facts; Datahike main | none (a write) | raw `d/transact` 12–14 ms; `seon.db/transact!` 15–25 ms | ~13 ms: one durable commit; the writer's validation is the rest |
| branch | fork-point commit id; Datahike roster | branch name derived from the attempt | `fork!` 40–53 ms (Sol/Opus; `d/branch!` 17 ms + fork-entity tx ~25 ms); Astra 13.7 ms with the writer excluded | 17 ms pointer (H8 removes the transaction) |
| connection | branch head | — | `open-branch!` 1.3 ms | ~1 ms |
| commit → content revisions | revision map; today process memory (`seon.cache` `::commit-revisions`) | commit id | fresh fork: every acquisition attribute names the fork head | **0**: the value carries it (H2) |
| world | identity, custody, environment; values in hand | none | 0.11–0.19 ms, <0.02 MB | same |
| SCI environment | cluster base + overlay source; `seon.cache` `::base-context` `[store loaded-commit]`, `::overlay-context` | H3/H4: `[base-key (digest plan) dials]`; empty plan → base | `base-ctx` 24–34 ms / 28–44 MB, then `fork-cluster-ctx` 7 ms, `fork-for-turn` 13–17 ms / 10–13 MB ≈ 50–60 ms, ~60 MB, three forks | **1–2 ms, <1 MB**: one `sci/fork` (0.01 ms raw) + custody; `base-ctx` on main 1.0 ms |
| overlay cache weight | — | as above | 314 MB held, 9 misses / 1 hit (Opus); 19/78 (probe) | a fork with no program change holds **no** entry |
| graph | blueprint, executor; live object in the roster | none | 1.13–2.3 ms / 0.6 MB paused (Astra, Sol); `arm!` 14 ms / 15 MB with resume | 1–5 ms, <1 MB; one platform thread per proc (two after D2) |
| first context (first turn) | task (main), proofs' reach, references; Datahike + `::reach-graph` `[content-revisions tests]`, `::reach-attribute` | unchanged | 58–124 ms / 98–138 MB; **9.8 ms / 4 MB with the head remembered** (Opus) | 2–10 ms / ≤5 MB on an unchanged program; a true miss proportional to the reached graph |
| **attempt row → first context** | | | **≈ 250 ms, ≈ 210 MB** | **≈ 50–65 ms, < 15 MB**: two durable writes (~30 ms) + ~5 ms of construction + one context read |

### 3b. The cold base build (owner, after Astra's benchmark landed, 585852259)

After `seon.cache` is emptied the SCI leg costs **1,056 ms / 1,787 MB / 81,241 calls**, 3,604 of them uncached
`seon.schema/contract-references` (~260 ms); warm it is 29 ms / 31 MB. The base (`seon.sci.eval/derive-base-ctx`:
`load-core-namespaces!`, `build-base-ctx`, `acquire-program!`) is a copy of the JVM's already-compiled program: its
necessary work is one projection lookup (`::projection`, content-keyed), one Var-root copy per declaration
(`copy-host-var`), one `m/-instrument` per contracted function (`seon.instrument/instrumented`, reusing the registry's
compiled contract when the projection holds it: `retained`), and nothing proportional to source text. **Floor:
proportional to declarations, about 100–200 ms and under 100 MB for ~1,200 functions, once per loaded commit.** The
3,604 reference walks are 3 roles × ~1,200 contracts: `schema/contract-product` → `arity-references` derives at install
what the projection already derived at build (`function-dependencies`, line of `projection-build`), the same content in
the same registry world, so it is read, not recomputed (Sol's caution holds: same registry, no form-digest key). The
remaining ~800 ms are measured in H9 by `:seon.profile/called` attribution before any fix.

### 3c. Shared derivations: computed once per content, shared by every agent and fork (owner rule)

| Derivation | Cached today | Content key | Miss / hit | Shared or rebuilt per fork |
|---|---|---|---|---|
| schema projection (`seon.schema/projection-from-database`) | `seon.cache` `::projection`; `db/carried-projection` by commit | `[forms function-contracts options]` | build O(declarations) / lookup | shared: one object per content |
| compiled Malli schemas of the projection registry | inside the projection (`mr/fast-registry`) | the projection's | with the projection | shared |
| `m/validator`, `m/explainer`, parsers per schema | Malli's own per-schema cache (`malli.core/-cached`, `reference-code/malli/src/malli/core.cljc` `-create-cache`/`-lookup-or-update-cache`) | schema object identity | first call derives / later calls read | shared while the schema object is shared (the projection's) |
| compiled function contract (`schema/contract-product` `:armed`) | the projection registry holds the shape; the armed compile is retained when equal (`seon.instrument/instrumented` `retained`), else recompiled per arming | contract form in the projection | compile per contract / `mr/schema` lookup | shared through the base source; a fork copies the Var, not the wrapper's schema |
| armed wrapper (`m/-instrument`) | the base fork source holds every wrapper (`::base-context`); `::installed-functions` is an atom copied per fork | `[store loaded-commit]` | ~1,200 wrappers per base derivation / `sci/fork` | shared: forks share Var roots; the atom copy is a map copy |
| `seon.schema/contract-references` | projection `function-dependencies`, `schema-dependencies` (content-keyed with the projection); **re-derived** at install by `arity-references` | the projection's | 3,604 walks cold (~260 ms) / 0 when read from the projection | **rebuilt per base derivation: H10 reads it** |
| canonical keys, predicate symbols, population targets | `::canonical-keys`, `::population-predicate-symbols`, `seon.fn` `::population-targets` | forms | lookup | shared |
| loaded digests, function rows | `::loaded-digests`, `::loaded-function-rows` | `[store commit]` | one read / lookup | shared |
| base context, overlay source | `::base-context`, `::overlay-context` | `[store commit]`; H3/H4's | 1 s cold (H9) / 1 ms | shared source; one fork per agent (H5) |
| content revisions | `::commit-revisions` (bridge) → the commit itself (H2) | commit id | 0 | shared |
| reach graph, reach attributes | `::reach-graph`, `::reach-attribute` | `[content-revisions tests]` | 1.3 s / 2.7 GB cold (one proof, after eviction) / 2 ms | shared once identity holds (H1/H2) |
| task basis, validity | `::task-basis`; validity new in R1 | `[forms content names]`; basis digest | 1 ms / lookup | shared |

Rule applied: every row is read-through `seon.cache/lookup` or Malli's own cache; the only derivation computed more than
once per content today is the contract-reference walk (H10). The reach entries' 288 MB (task
`seon.test.runner/reach-entries-fill-half-the-cache-at-half-hit-rate`) and the overlay duplicates (H4) are weight, not
recomputation.

## 4. Reused, changed, deleted (by symbol)

**Reused as they are.** `datahike.api/branch!`, `commit-as-db`, `release-materialized-db`, `delete-branch!`;
`seon.cluster.store/open-branch!`, `release-branch!`; `seon.turn.world/world`; `seon.sci.eval/base-ctx` (lookups),
`copy-base-ctx`; `seon.cache/lookup`; `seon.db/content-revisions`; `seon.context/current-state`;
`seon.cluster.agent/graph-definition` (trimmed), `mailbox-step`, `turn-step`; `seon.flow/graph-executor`,
`join-graph!`; `clojure.core.async.flow` `create-flow`/`start`/`resume`/`pause`/`stop`/`ping`; `seon.task/frontier`,
`seon.task/attempt-tx` (S3); `seon.test.runner/reach-revision-key` (its inputs are already complete).

**Changed.** Datahike fork: `datahike.writing/db->stored` stores the commit's modified attributes and the prior
revision map; `datahike.writing/stored->db`, `datahike.versioning/attached-cache-context`,
`datahike.connector/connect` rebuild the cache context from them. `seon.db/content-revision`: a lookup, no chain
walk. `seon.sci.eval/base-ctx`, `overlay-plan`: keyed by `seon.db/content-revisions` over `acquisition-attributes`;
an empty plan answers the base as the source; a non-empty plan keys by `[base-key (digest plan) dials]`.
`seon.sci.eval/fork-for-turn`: a fresh agent's print session from one pull of the agent entity. `seon.flow/start-graph!`:
takes the intent, resumes only for `:running`. `seon.cluster.agent/graph-definition`, `:seon.agent/blueprint-request`:
the world, agent id and channels, not a merged 25-key handle. `seon.task/attempt-tx`: carries
`:seon.task.attempt/fork-point` (H8).

**Deleted.** `seon.db/remember-revisions!`, `recorded-revisions`, kind `::commit-revisions` (H2);
`seon.sci.eval/program-identity`, `program-identity-cache`, `remember-program-identity!`, `inherited-basis`,
`revision-basis`, `watch-program-identity!`, `program-cache-policy` (H3; `acquired-database?`, `acquired-basis` compare
content revisions); on the start path `seon.sci.eval/fork-cluster-ctx`, `acquire!`, `fork-for-turn`'s second fork
(H5); `seon.cluster.agent/arm!`, `arm-graph!`, `acquire-context!`'s create path, `seon.cluster.host/host!`, `start!`,
the per-branch armer graph, `seon.test-support`'s launch helpers (H7, as R1 decided); `registry/fork!`'s fork-entity
write and `:seon.branch/base-t` readers (H8).

## 5. Phases (about fifteen minutes each, one owner, red first)

**H1 — the fork head is remembered (bridge; landing, lane astra-cache-1-2).** `fork!` calls `seon.db/remember-revisions!`
on the fork-entity commit. Proof `seon.test.runner-test/a-reopened-fork-reuses-its-programs-proof-reach`: ten tests'
`reach-digests` on a reopened fork equal main's, zero `reached-graph` calls, under 5 MB. Files: `src/seon/cluster/registry.clj`,
`test/seon/test/runner_test.clj`. Unblocks every later hit on a fresh fork; makes H3's and H5's reds measurable.

**H2 — revisions ride the commit (owner question Q2 first).** H2a, Datahike fork: `db->stored` stores `:modified-attrs`
and the prior `:attribute-revisions`/`:conservative-revision`; `stored->db`, `attached-cache-context`, `connect` rebuild
the context (prior + modified → this commit id). An old commit without the field reads conservative, as today: no
migration. H2b, Seon: delete `remember-revisions!`, `recorded-revisions`, `::commit-revisions`, the loop in
`content-revision`; `fork!` and `branch!` stop remembering. Red proof `seon.db-test/a-reopened-commit-carries-its-content-revisions`:
fork from main, write one fork entity, release, reopen through `commit-as-db` on a raw store (no connection memory);
`content-revisions` over `seon.program/program-attributes` equals main's and the written attribute names the fork head;
`seon.profile/called` of `seon.db/recorded-revisions` is zero (deleted). Files: `reference-code/datahike` (fork bump),
`src/seon/db.clj`, `src/seon/cluster/registry.clj`, `test/seon/db_test.clj`. Unblocks: identity after restart and
eviction; H3 keys on a value that is always right.

**H3 — one program identity.** Delete `seon.sci.eval/program-identity` and its LRU; `overlay-plan` and `base-ctx` key by
`seon.db/content-revisions`. Red proof `seon.sci.eval-test/a-fresh-fork-of-an-unchanged-program-acquires-from-the-base`:
`base-ctx` on a fresh fork calls neither `derive-overlay-plan` nor `derive-overlay-ctx` and costs under 3 ms / 3 MB
(measured 24–34 ms / 28–44 MB); `an-acquired-context-retains-bases-and-reads-its-connection` stays green. Retires task
`seon.sci.eval/program-identity-caches-live-outside-seon-cache` (its refusal-cache half moves to its own row). Files:
`src/seon/sci/eval.clj`, `test/seon/sci/eval_test.clj`. Opus lane (`acquired-database?` sits on every turn).

**H4 — no duplicate programs in the cache.** Empty plan → the base is the source; a changed program keys by the plan's
digest. Red proof `seon.sci.eval-test/branches-without-program-changes-add-no-overlay-entry`: acquire three fresh forks
with no program write; `::overlay-context` weighted bytes unchanged (today +~60 MB per fork). Unblocks ~300 MB of budget
for reach and context entries. Files: `src/seon/sci/eval.clj`, `test/seon/sci/eval_test.clj`.

**H5 — one fork per agent.** The environment is `base-ctx`'s fork with the agent's private layer applied to it (namespace,
print session from one pull; no settled handles for a fresh agent). Red proof `seon.cluster.agent-test/a-fresh-agents-environment-costs-one-fork`:
after H3/H4, environment under 5 ms / 5 MB (measured ≈50–60 ms / ~60 MB), `sci.core/fork` called once, custody is the
branch connection. Files: `src/seon/sci/eval.clj` (`fork-for-turn`), `src/seon/cluster/agent.clj` (acquisition),
`test/seon/cluster/agent_test.clj`.

H5 landed as **option A** (root, 2026-10-11): two forks, the branch's program copy (`seon.sci.eval/branch-ctx`)
and the agent's fork (`fork-for-turn`), with `fork-cluster-ctx`'s re-acquisition and `acquire!` off the fresh path and
no history reads for an agent that never ran; proof `seon.cluster.agent-test/a-fresh-agents-environment-costs-two-forks`.
One fork is not reachable without a REPL-path design change: the per-branch base is a mutable context that `acquire!`
re-acquires in place and that each turn forks to rebuild the agent's private layer, so the agent's context cannot be the
base itself. **Option B, held for the owner:** the base becomes the shared content-keyed source (never written), and a
re-acquisition builds a new base with `base-ctx` and regenerates the agent from it instead of `acquire!` in place
(touches `acquire!`, `acquire-context!`, `fork-for-turn` and `seon.test`'s request world; several hours).

**H6 — the intent decides resume (R2's `:paused` row).** `start-graph!` takes `:seon.task/execution`; `:paused` leaves
Flow paused. Red proof `seon.cluster.agent-test/a-paused-intent-starts-a-pingable-graph-that-runs-nothing`: every proc
pings `:paused`, an offered wake runs no turn, `flow/resume` runs it; graph start under 15 ms / 5 MB with three procs
(10 ms after D2). Files: `src/seon/flow.clj`, `src/seon/cluster/agent.clj`, `test/seon/cluster/agent_test.clj`.

**H7 — `seon.flow/start-flow!`, the one path.** Request `{task handle, agent id, branch, fork-point, intent, graph?}`:
`fork!` (`created? false` on a repeat) → `open-branch!` → `world` → environment (H5) → graph (H6) under
`:seon.agent/started`, route registered, wake primed; `graph? false` answers the world alone. Folds in `arm!`,
`arm-graph!`, `host!`/`start!`, the test-support launch helpers; R1's `db<->flow` calls it after `attempt-tx`. Measure
first who reads `:seon.agent/*` from a branch value and convert them to main. Red proofs: R1's
`seon.cluster.agent-test/starting-one-agent-on-a-task-costs-under-a-second-and-300-mb` **tightened** to attempt row →
first context under 150 ms and 40 MB warm (floor ~55 ms / <15 MB), legs as profile evidence so a slower run than main's
is red; `seon.cluster.agent-test/a-restart-starts-the-same-attempt-on-its-existing-branch` (`created? false`, no second
branch, same agent id); `seon.test-test/a-member-world-comes-from-start-flow-without-a-graph`. Files: `src/seon/flow.clj`,
`src/seon/cluster/agent.clj`, `src/seon/cluster/host.clj`, `src/seon/test.clj`, `test/seon/cluster/agent_test.clj`,
`test/seon/test_test.clj`.

**H8 — the branch is a pointer.** The attempt carries `:seon.task.attempt/fork-point`; the merge base is that commit
(Datahike lineage, never `t`); `fork!` writes nothing on the branch. Measure first who reads `:seon.branch/base-t`
(`target-advanced-base-t`, `seon.cluster.source/compare-branch`); more than one owner's afternoon keeps the entity. Red
proofs `seon.cluster.registry-test/a-fork-writes-no-transaction-on-its-branch` (head commit = fork point; under 25 ms)
and `seon.cluster.source-test/the-merge-base-of-an-attempt-is-its-fork-point-commit` (same diff as today's since
`base-t` on the two-leaf fixture). Files: `src/seon/cluster/registry.clj`, `src/seon/cluster/source.clj`,
`src/seon/task.clj`, tests.

**H9 — the cold base build is proportional to declarations.** Attribute the 1,056 ms / 1,787 MB with
`:seon.profile/called` on an emptied cache in the check JVM (Astra's `seon.start-flow-benchmark-test/commit-to-paused-flow-costs`
cold sample), then remove each piece of program-size work that is not a Var copy, a wrapper or a projection lookup
(candidates: a per-function database read in `acquire-program!`, `behavior-changed?` over unchanged functions,
`loaded-digests` recomputed). Red proof `seon.sci.acquisition-cost-test/a-cold-base-derivation-is-proportional-to-declarations`:
`derive-base-ctx` after `seon.cache` is emptied under 300 ms and 300 MB for the loaded program (measured 1,056 ms /
1,787 MB), `contract-references` called zero times (H10) and `install-function-contract!` once per contracted function.
Files: `src/seon/sci/eval.clj`, `test/seon/sci/acquisition_cost_test.clj`. Owner: `seon.sci.eval`.

**H10 — contract references are read from the projection (CACHE-2, Astra's lane holds `src/seon/schema.clj`).**
`seon.schema/contract-product` answers `:seon.schema/references` from the projection's `function-dependencies` when the
compiled contract is the projection's (`retained`), deriving only for a contract the projection does not hold; no form-digest
cache kind. Red proof `seon.instrument-test/arming-a-projections-contract-walks-no-references`: arming every function of a
fixture projection calls `seon.schema/contract-references` zero times; a contract differing from the projection's calls it
once per role. Files: `src/seon/schema.clj`, `src/seon/instrument.clj`, `test/seon/instrument_test.clj`.

**Order.** H1 (landing) → H2 (if Q2 is yes; else H3 keys on the remembered map) → H3 → H4 → H5; H6 any time, with D2;
H7 after H5, H6 and S3; H9 and H10 any time (H10 inside Astra's CACHE-2 lane); H8 last, measured first. Lanes: H2a is a fork change (platform, `--test`); H3/H4 one owner,
`seon.sci.eval`; H5–H7 `seon.cluster.agent`/`seon.flow`.

## 6. Risks

1. The cold reach cliff (one proof 1.3 s / 2.7 GB after eviction) is not this path's; H4 returns the budget it needs.
2. H2's stored map grows with attributes revised since the last conservative advance (schema writes reset it); measure
   its size on default's busiest branch before choosing to store the whole map or the delta.
3. `fork-for-turn`'s 12 ms of history queries for an agent with no history is unverified (Opus); H5 measures it.
4. The lightness proof measures start → first context through the real first turn; S3's attempt facts must be wired.

## 7. Questions only the owner can answer

- **Q1 Test members:** a branch per member (harness rule: "branches writes from a captured commit") or one run branch
  with `d/with` per member (~1 ms a member, no durable writes)? Recommended: `d/with` inside one run attempt.
- **Q2 Change the Datahike fork now (H2) or keep the in-memory bridge (H1)?** Recommended: H2, one mechanism the
  library owns, correct after restart and eviction; price: a fork bump plus two Seon deletions, about one lane-hour.
- **Q3** May the agent row live on main only (the cluster owns its agents), with branch readers converted in H7?
