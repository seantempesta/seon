---
title: "own-delta: a branch's program is its own writes over the loaded program (independent plan, Opus)"
type: research
status: plan (one of two independent plans; for the Fable hybrid)
created: 2026-10-15
---

# own-delta plan (Opus)

What exists: `seon.program/changed-identities`, the history walk of `:seon.program/definition-digest` after a basis-t,
already answers "what did this branch write since t". The done rule (`seon.test/branch-delta`, `seon.step/changed`)
and the merge (`seon.merge`) read it. The overlay, the readers and the reach still compare the branch against main's
history (`seon.cluster.registry/seen-database` and its helpers). The plan points those readers at that one walk, read
against the **loaded program** (the commit the JVM's compiled Vars were loaded from). Then it deletes the three-way
machinery. This is the approach of rulings 1 and 2: a set lookup, not a per-declaration history comparison.

Measured live (SCI, agent `own-delta-plan-opus`, default 6ad01b2c, chain `:chain-f1f6dc8e6652` with base-t 536872008):

- `(program/changed-identities 536872008 chain {:seon.program/max-datoms 10000})` gives 24 identities in 68 ms /
  80 MB.
- 22 of them differ from the loaded commit. The other two are `seon.error/dbv` and `testing-dump`, which were added
  and then deleted, so they are absent on both sides and are no change.
- The branch's digests for the 24, read by index probe, cost 2.9 ms.
- By comparison, `moved-past` covered 125 functions on this branch (5898f977e).

## 1. `own-delta`

- **Definition.** `seon.cluster.registry/own-delta [database] → :seon.program/own-delta`, with the new schema
  `[:map-of :seon.program/identity :seon.program/digest-or-absence]`. It maps each declaration whose digest the branch
  asserted or retracted after its fork's `:seon.branch/base-t` to the digest the branch holds now, or
  `:seon.program/absent`.
- **The read.** `(program/changed-identities base-t database merge-bound)` lists the identities. The existing
  private `identity-digests` (two index probes per identity) reads their digests. No new Datahike seam is needed.
- **Base-t.** It comes from `(fork-of database branch)`, with the branch taken from `seon.db/schema-database`, so
  as-of, `with` and history views carry it (be9b26a09).
- **The cluster's own branch** has no fork entity. Its base is the commit of its adoption record (the loaded commit),
  as `seon.test/change-base` already rules. So main's own-delta is main's writes since the last reload: exactly what
  root's overlay interprets today.
- **Unmap and delete.** A retraction is an assertion-history entry, so a deleted declaration is in own-delta as
  `:seon.program/absent`. Add-then-delete appears as absent too (dbv, above).
- **A branch forked from a branch.** `branch-fork-entity` gives the child its parent's base-t toward the same target.
  So the child's own-delta includes the parent's writes since that base. That is right for test forks of an agent
  context: they see the agent's definitions.
- **The fork's base-t, not the advanced base ref.** After an intermediate merge, the merged writes stay in own-delta
  until a reload makes their digest equal to the loaded program's. Then they become compiled (§2 rule) with no
  regression window.
- **Owner.** `seon.cluster.registry`, which already owns `fork-of`, `base-commit-id` and `identity-digests`, and which
  every reader already requires.
- **Cost.** It is proportional to the digest attribute's history, not to the agent's work. Datahike indexes no
  transaction prefix (the docstring of `seon.program/declaration-changed-identities`; `SinceDB` is a filter
  predicate, `datahike.db/since-pred`). It is 68 ms per new commit of a branch.
- **Reuse.** One `seon.cache` kind, `::own-delta`, keyed by `[store-identity commit-id]`, plus the view's time-point
  for an as-of view. A committed value is immutable. So repeated blocks, eval requests, readers and test forks of one
  commit derive it once. Only a writing eval request makes a new commit.
- **Follow-up, only if profiled hot.** The writer already holds the recording transaction's changed identities
  (`seon.test/request-changes`). The entry of a new commit could be its parent's entry plus those identities,
  computed at the writer, which is the authority. That is not in this round: the simple derivation first, then
  measure.

## 2. One symbol resolves one way

The rule is one lookup per identity `d` in a context on branch B, with L the loaded program:

> `d` is the branch's iff `d ∈ own-delta(B)` and `own-delta(B)[d] ≠ digest_L(d)`.
>
> - Then it is interpreted from B's row, and readers read B.
> - Otherwise it binds the compiled Var, and readers read L's row.

`digest_L` is today's `seon.sci.eval/loaded-digests`, cached per loaded commit. The new reader is
`seon.cluster.registry/declaration-database [database declaration]`, which returns B or L. It replaces
`seen-database`, with a new name because the meaning changed.

| Reader today | Reads instead |
|---|---|
| `seon.sci.lookup/function-documentation` (doc, help, must-read `:doc` level) | `declaration-database` |
| `my.program` `declaration-source` (`source`, must-read `:code`) | `declaration-database` |
| `seon.ref/brief` and `resolve-name` (references, must-reads) | `declaration-database` |
| `seon.sci.eval/derive-overlay-plan`: candidates were branch rows vs loaded, then minus `moved-past` | The interpret set is the identities of own-delta whose digest differs from L's, plus the reverse closure. Callers outside own-delta are installed **from L's rows and L's stored `:seon.fn/calls`**, never from the branch's base copy. Callers inside own-delta come from B. A `:seon.program/absent` entry unmaps the Var in the fork. |
| `overlay-plan` cache key: `sides-key` (base commit and target revisions) | `[store, digest of the interpret set, loaded commit]` |
| `seon.test.runner/reach-entries` (`moved-reach`, `moved-changed`, `::moved` key) | The prior is the loaded commit's reach index, shared by every branch. The changed set is the interpret set. Only those nodes and their reverse closure are rederived, by the existing `changed-reach` mechanism. |
| `executed-roots` → `seen-digests` (recorder's stale check) | `digest_B` for interpreted identities, `digest_L` otherwise. This is the same rule as the reach, so what is recorded is what is read (D4). |

- **Must-reads.** `seon.context/must-reads` on `attempt-start`, an AsOfDB, reads `own-delta` of that view.
- **The current-state block.** It calls the readers above, so it inherits the rule without change of its own.

## 3. The loaded program

**Choice: L is the loaded commit's rows, the compiled Vars' digests. It is not main's HEAD rows.**

- L is the commit named by main HEAD's adoption record: `:seon.source/commit-id` on the `:seon.cluster/name`
  entity, read from the target's head record, as `seon.sci.eval/loaded-source` does through the live connection.
- Live: main's record is `6acfdaa1…`, equal to `@seon.sci.eval/loaded-identity`. The chain's own copy is stale
  (`6acf6f7d…`).
- **Why L.** The compiled Var is what runs. Showing, digesting or recording main HEAD's row for a Var that runs L's
  code would make "one symbol, one way" false again. It is a single cached lookup, with no per-main-commit rescan.
- **What an agent sees** for a declaration that main changed but has not yet reloaded (live: main HEAD differs from
  L in 10 declarations, among them `seon.edit/single-form`): the loaded row, in its doc, its source and the code that
  runs, until root's restart batch reloads it.
- **This reverses one decision.** 576009291's spec-gap decision kept the branch's copy of main's change made before
  the fork and not yet loaded. That case also binds L now.
- **The cost of that.** Root's context interprets main's own-delta, so it sees the change one restart earlier than a
  chain agent. That window is one restart batch, and it buys a single concept.

## 4. Config (ruling 4)

`seon.config/effective` and `seon.config/agent-cluster-dial` read the cluster row from main's HEAD.

- **Main's HEAD** is `seon.cluster.registry/target-head`, the target branch's head commit through `fork-of`
  `:seon.branch/target`. On the cluster's own branch it is the value itself.
- **What the branch wrote wins over it.** These are the branch's own datoms on `[:seon.config/cluster n]` with
  `tx > base-t`: one EAVT history read of one entity.
- **Live.** The chain wrote 0 config datoms. It reads cap 40 and main reads 100, which reproduces run 8.
- **Overlays.** Per-agent and per-task overlays (`seon.ai/agent-overlay`, `:seon.task/model`) already live on the
  branch's entities and still win on top.
- **Running agents follow `config apply`.** `seon.turn/max-episode-runs` → `agent-cluster-dial` reads per turn, so a
  running agent's next turn sees `bin/seon config apply`.
- **Cache.** The `effective` cache keys by `[main-head-commit, the branch's own config datoms, declaration digest]`.

## 5. The writer (ruling 3)

- **What already follows the branch.** `seon.turn.batch/write-entity` binds the context's predicates
  (`seon.sci.kernel/context-bound-projection`, e79736317).
- **The refusing site** is `seon.turn/row-tx`. It derives the checked projection from `(db/carried-projection db)`
  and calls `seon.schema/projection-with-function-contract`. `seon.schema/compilable-form` then finds neither a
  projection binding nor a loaded Var for the branch's predicate, and refuses `:seon.schema/unresolved-predicate`.
- **Fix.** `row-tx` takes the request's context-bound projection, the same bindings map `with-bound-predicates`
  keys, never a second projection.
- **Red proof.** The task `seon.schema/a-contract-naming-a-branch-predicate-is-refused-at-the-writer` exists in
  `resources/seon/tasks/seon.schema.edn`. Its deftest is
  `seon.turn.batch-test/a-contract-naming-a-predicate-defined-on-the-branch-is-admitted-and-armed`. It is red on HEAD
  (run ce355236cf6f, "Predicate f40.named-predicate/even-int? has no admitted callable in the corpus projection"),
  and the same message is live in today's agent errors (runtime_status, agent `own-delta-plan-astra`).
- **Scope.** It is file-disjoint from §1-§4.

## 6. Deleted

- **`seon.cluster.registry`:** `seen-database`, `moved-past`, `moved-identities`, `merge-sides`, `side-commits`,
  `main-changed`, `commit-digests`, and the `::commit-digests` and `::main-changed` cache kinds. `identity-digests`
  stays.
- **`seon.test.runner`:** `moved-reach`, `moved-changed`, `seen-digests`, the `::moved` cache-key and entry members,
  and the `moved` arm of `known` and `unknown`.
- **`seon.sci.eval`:** the 3-arity of `derive-overlay-plan`, the `moved` filtering, and `sides-key`.
- **Tests of deleted machinery:**
  - `a-work-branch-reads-what-main-moved-past-once-per-commit-pair`
  - `a-work-branch-as-of-view-reads-its-own-digest-by-index-probe`
- **Tests kept and reworded to the loaded-program expectation**, because their behaviour survives:
  - `a-work-branch-interprets-only-what-main-has-not-moved-past` (the 5b regression)
  - `a-work-branch-documents-what-main-moved-past-as-main-holds-it`
  - `every-view-of-a-work-branch-sees-what-main-moved-past`
  - `a-work-branch-result-is-not-current-after-main-changes-what-it-reaches`
  - `a-work-branch-result-that-ran-what-main-moved-past-is-recorded-at-its-identity`
- **Net estimate.** Source is about −165 / +90, so −75. Tests are about −110 / +150.

## 7. Phases

Every phase leaves HEAD booting. Each deletion lands in the commit that converts its last caller.

| # | Change | Red proof (assertion on data) | Commit |
|---|---|---|---|
| P1 | `own-delta`, `declaration-database`, `target-head`, `loaded-program` in the registry. Schema `:seon.program/own-delta`. Additive. | New `seon.cluster.registry-test/own-delta-is-the-branchs-own-writes-since-its-base`. Its table rows are listed below. Red: the Var is absent on HEAD. | REPL-first (`--branch-evidence`); the schema resource goes through `--test` |
| P2 | The doc, source and ref readers → `declaration-database`. Deletes `seen-database`. | Reworded `seon.sci.eval-test/a-work-branch-documents-a-declaration-it-did-not-write-as-loaded`: main changes `g` before the fork without reload. `(:in (function-documentation branch 'g))` equals L's. Red: HEAD answers the branch's inherited row. | Patch to the `my/program.clj` holder; REPL-first for `seon.ref` and `seon.sci.lookup` |
| P3 | Overlay interpret set from own-delta. Callers come from L's rows. Absent entries unmap. Deletes the eval three-way. | New `seon.sci.eval-test/a-work-branch-binds-the-loaded-program-for-every-declaration-it-did-not-write`. With a pre-fork unreloaded main change `h`, a post-fork main change `g` and a branch `defn f`, `(::interpreted plan)` is `#{f}` plus f's callers, and contains neither `g` nor `h`. A caller's installed source equals L's. Red: HEAD interprets `h`. | Host-bound: `bin/lane commit --test` (evaluator) |
| P4 | Reach and recorder over L plus own-delta. Deletes `moved-past`, `merge-sides`, `side-commits`, `main-changed`, `commit-digests`, `moved-*` and `seen-digests`. | New `seon.test.reach-test/a-branch-result-identity-equals-the-same-test-on-loaded-plus-its-delta`. The reach digest of T on the branch equals T's digest on `(d/with L <own-delta rows>)`. A pre-fork unreloaded main change reached by T keeps them equal. Red: HEAD digests the branch's inherited row. | Host-bound: `--test` (runner) |
| P5 | Config reads main HEAD ∪ the branch's own config datoms. | New `seon.config-test/a-branch-reads-mains-config-except-what-it-wrote`. After a fork, main sets `:seon.config.run/max-episode-runs` from 40 to 100, and the branch reads 100. After the branch writes `:seon.config.ai/model`, it reads its own model and main's cap. Red: HEAD reads 40. | Host-bound: `--test` (configuration) |
| P6 | `seon.turn/row-tx` validates with the context-bound projection. | `seon.turn.batch-test/a-contract-naming-a-predicate-defined-on-the-branch-is-admitted-and-armed`. Red on HEAD. Retires the task. | Host-bound: `--test` (writer); patch, because `seon/turn.clj` is a critical-path file |

The P1 table rows:

1. A fresh fork is `{}`.
2. After `defn f`, it is `{[:seon.fn/sym f] digest}`.
3. An unmap of a loaded function gives `:seon.program/absent`.
4. A main write after the fork is absent from it.
5. A nested fork includes its parent's writes.
6. Connection db, `branch-database`, as-of view and `with` all answer equal.

Order:

- P1 first.
- Then P2 → P3 → P4, one after another, because each claims `src/seon/cluster/registry.clj` for its deletion.
- P5 and P6 run in parallel with them.
- Root restarts once after P3 and once after P4, then reruns the S5 chain as the live probe. The pass condition is a
  first turn with no analysis or doc failure, and keyed green.

## 8. What the next round (rulings 5 and 6) can rely on

- **Identity equality.** A branch result's reach digest is the same function over L ⊕ own-delta that the merge
  computes over main-HEAD ⊕ own-delta.
  - They are equal whenever main HEAD equals L on the reached set. That is the normal case after a restart batch.
  - Otherwise the digests differ, and the merge runs that test. That is correct, because the branch never ran main's
    newer row.
  - P4's red proof pins this.
- **Candidate set.** own-delta (since the base ref, the same function with that basis) is the merge's candidate set.
- **Junk filtering is the merge's job.** Today the chain's own-delta also carries agent data: 10 `my.attempt-*`
  namespaces, `user`, and `:tmp/probe`. Filtering program entities by entity schema is the merge's business (ruling
  5), never own-delta's.

## 9. Verified vs assumed

**Verified at the REPL** (SCI, own agent, read-only):

- `changed-identities` from the fork's base-t gives 24 identities in 68 ms / 80 MB.
- 22 of them differ from L. Add-then-delete reads absent on both sides.
- The digest probe costs 2.9 ms.
- The main and chain adoption records, and the loaded-identity atom (§3).
- Main HEAD differs from L in 10 declarations.
- The branch wrote 0 config datoms; it reads cap 40 where main reads 100.
- The writer refusal is live.

**Read in source:**

- Every reader in §2.
- `branch-fork-entity` inheritance.
- `seon.test/change-base`.
- `seon.turn/row-tx` → `projection-with-function-contract` → `compilable-form`.
- Datahike `SinceDB` is a filter.

**Assumed** (each is checked first inside its phase):

- `changed-identities` answers correctly on an AsOfDB view (P1 row 6).
- The overlay does not yet unmap a branch-deleted function in a fresh fork (P3 adds this if it is absent).
- `row-tx` is the only remaining writer site that compiles the contract against the database's projection (P6).
- An SCI Var bound as a predicate in db-after's carried projection is callable by compiled readers on that branch.
- 68 ms per new commit is acceptable on the eval request path. This needs a P1 measurement of the turn's first
  request; if it is not acceptable, use the writer fold in §1.
