---
title: "Hybrid: a branch runs its own HEAD; root merges forward (Fable, from the Opus and Astra plans and the owner's git amendment)"
type: research
status: hybrid (for the narrow check; tasks are filed only after every §6 form reproduces on default)
created: 2026-10-15
---

# own-delta hybrid

Inputs: [branch-model-rulings](branch-model-rulings.md) with its 10-15 late-morning amendment (authority),
[own-delta-plan-opus](own-delta-plan-opus.md) (d26760406), [own-delta-plan-astra](own-delta-plan-astra.md)
(6edcae39b). Both plans were written to ruling 1 as first stated (own writes over the loaded program); the amendment
asks for option G (a branch runs exactly its own HEAD). Decision 0 compares them; the rest designs for what holds.

## 0. Option G vs own-delta: recommend G plus an explicit forward merge by root (G+)

**G (owner):** every reader (doc, source, references, must-reads, reach digests, recorder) reads the branch's own
database value B. The one cross-pointer computation is the interpret set I(B, L) = {d : digest_B(d) ≠ digest_L(d)} ∪
reverse call closure, a pure function of two immutable commit ids, cached by the pair and derivable from the parent
commit's pair plus that commit's `seon.test/request-changes`. Exception (a): host-bound platform code always runs as
loaded. Config is the branch's own row; root applies to a branch explicitly. The merge reconciles drift once; a merged
result whose reached rows differ reruns, like CI after a merge.

**own-delta (both plans):** readers read B only for what B wrote since its base, L for the rest; interpret set =
own-delta ∩ (digest ≠ L) ∪ callers.

| Axis | G | own-delta |
|---|---|---|
| S5 failures | 5c (doc of stale `seon.db/q`), run-6 recorder mismatch, 127 reruns: all gone, because doc, digest and what runs are the same row by definition. **5b is G**: 576009291's commit text says the overlay was "its head against the loaded program"; a function main deleted read as the branch's addition, its reverse closure reinterpreted the branch's stale `seon.db/q`, and analysis failed on the missing callee. G must install an absent-on-L callee from B (the overlay's job; the 5b gap is an install-order defect, not the model), and then the agent runs a stale hub interpreted. | All gone, by reading L for untouched rows. Reverses 576009291's spec-gap decision. |
| Cost | I(B, L) grows with main's drift, not the agent's work: on the S5 chain today it is 22 own + the ~125 functions main moved past that L already holds, plus their reverse closure; `seon.db/q` alone reaches most of the program. Narrow check 1 measures it. A chain's whole context then runs in SCI, and stale interpreted code meets changed compiled platform code at every host-bound seam: the mixed-version hazard git never has. | Proportional to the agent's writes (24 identities, 68 ms walk, cached). |
| Result identity for the merge | Digest over B's rows. Equal to the candidate's iff main did not change the reached rows since the fork; otherwise rerun. More reruns the longer the branch lives. | Equal iff main HEAD = L on the reached set (normal after a restart batch). |
| Lines | Deletes the whole three-way set and needs no `declaration-database`, no reader rule: `derive-overlay-plan`'s 2-arity is already I(B, L). About −165 source. | Deletes the same set but adds `own-delta`, `declaration-database`, `target-head`: about −75 net. |

G is the right model: one immutable value per branch, no per-declaration rule, no read of a second program.
Its one defect is that exception (a) makes it impure: a branch cannot run its own copy of the platform, so every
compiled change since the fork turns the branch's inherited copies into interpreted stale code, and a hub drift
interprets everything. The git answer to drift is not a rule in the readers; it is an explicit merge **in the other
direction**: `git merge main` into the branch. **G+ = G, plus root merges main into a chain branch** at each restart
batch (the moment L moves is the moment drift becomes interpreted), through the existing three-way
(`seon.program/three-way` over the digest maps of base, branch and main HEAD; `seon.merge` carries the rows):
`changed-on-head` rows move to the branch, `changed-on-branch` stay, conflicts are listed for root and stay as the
branch holds them. Program entities only; config and agent data never cross (ruling 5), in either direction. After it,
I(B, L) is again the branch's own writes plus conflicts, readers still read only B, nothing is rebased (the branch's
history keeps its fork-time rows; a merge commit adds main's), and the merge back has less to reconcile. Decision:
**G+**. own-delta survives only as the merge's candidate set (`seon.program/changed-identities` since the base, which
exists), never as a read rule.

## 1. "The loaded program" under G+

L is the loaded commit (`seon.sci.eval/loaded-identity`), never main's HEAD rows; ruling 1 said so in its parenthetical
and both plans chose it. Under G+ no reader consults L at all; it enters only I(B, L), where the compiled Var's digest
is the only honest comparand (main HEAD's row for a Var running L's body would be a lie). A chain agent sees a main
change it did not write only when root merges main forward into its branch; a declaration main changed but did not
reload is, after that forward merge, on B and differs from L, so it is interpreted, as it is in root's own context.
That is "sees a change only after merge and reload" with the merge made explicit.

## 2. Cost: I(B, L) incrementally from the parent commit; no Datahike change

I(fork, L) = I(main-at-fork, L), main's unreloaded writes (live: 10). Each commit's entry = parent's entry updated by
that commit's `request-changes` (added, changed or retracted digests), which the recording writer already holds: a
derivation the writer owns, not a stamp (nothing is written to the database; the cache entry is keyed by the two
commit ids and rebuilt by the existing whole-program comparison, Opus's `loaded-digests` vs B's digests, after
eviction or a restart). The reverse closure is `seon.fn/reverse-closure` over L's stored `:seon.fn/calls` with B's own
edges replacing them. Astra's transaction-leading Datahike index is not needed here: nothing walks history on the read
path; the merge's `changed-identities` walk (68 ms per back or forward merge) stays as it is and is filed as the
Datahike-fork dig it already is ([since-scan issue](../../../seon/issues/system-plan-scans-since-views-for-read-evidence-changes-on-every-open.md)).
The P1 proof measures the turn's first eval request parent vs change and asserts zero derivations on a second request
and on a result-only commit.

## 3. Agent junk: nothing filters

B's `my.attempt-*` namespaces, `user`, `:tmp/probe` are B's rows: interpreted, documented, digested as B holds them
(empty reverse closure). The merge's reach closure (ruling 5) is the only filter, by entity schema. No phase below
needs one; adding one would be a fence.

## 4. Config (amendment)

A branch reads its own config row, like everything else; `seon.config/effective` and `agent-cluster-dial` change
nothing. Root changes a branch's config by `seon.config/apply!` with that branch's connection
(`seon.db/branch-connection`; the request already carries `:seon.db/connection`). `bin/seon config apply [NAME] PATH
--branch B` and `--chains` (every chain branch named on main's fork entities with a running graph) are a thin entrance
over it in `script/seon/operator.clj`. `seon.turn/max-episode-runs` reads per turn, so the next dial read sees it with
no graph restart. Run 8's frozen cap is then the documented behaviour until root applies; the forward merge does not
carry config.

## 5. Phases

HEAD boots after each; a deletion lands with its last caller; the red is recorded on HEAD first. All host-bound
(`bin/lane commit --test`) except P2, an ordinary-reader deletion.

| # | Change (files) | Red proof: deftest and data assertion | Commit |
|---|---|---|---|
| P1 | Overlay: I(B, L) from B's HEAD vs `loaded-digests` plus reverse closure; an absent-on-L callee is installed from B before its callers (the 5b gap); a B-retracted declaration unmaps the Var; cache kind `::interpret-set` keyed `[store, B commit, L commit]`, filled from the parent entry + `request-changes`. Deletes the 3-arity `derive-overlay-plan`, `moved` filtering, `sides-key`. (`sci/eval.clj`, `seon.cache` kinds) | New `seon.sci.eval-test/a-work-branch-interprets-exactly-its-head-against-the-loaded-commit`: main deletes `k` and changes `g` after the fork and reloads; branch `defn f`, retracts `m`: `(::interpreted plan)` = `#{f g k}` ∪ reverse closure, `m` unbound in the fork, a call of `g`'s B-caller returns B's value. Reword `a-work-branch-interprets-only-what-main-has-not-moved-past` to this expectation (its 5b rows: the missing-callee install). New `seon.sci.acquisition-cost-test/the-interpret-set-is-derived-once-per-commit-pair`: `seon.profile/measure` on the first eval request, parent vs change; second request and a result-only commit derive zero times. Red: HEAD filters `g` out. | Host-bound (evaluator) |
| P2 | `seon.sci.lookup/function-documentation`, `my.program` `declaration-source`, `seon.ref/brief`, `resolve-name` read `database`; delete `seen-database`. (`lookup.clj`, `my/program.clj`, `ref.clj`, `registry.clj`) | Reword `a-work-branch-documents-what-main-moved-past-as-main-holds-it` → `…-as-the-branch-holds-it`: after main changes `g` post-fork, `(:in (function-documentation branch 'g))` and the source equal B's row. Red: HEAD answers main's. | REPL-first `--branch-evidence`; `my/program.clj` critical-path: claim if free (no live claims now), else patch |
| P3 | Reach and recorder over B alone: delete `moved-reach`, `moved-changed`, `seen-digests`, `::moved`; registry `moved-past`, `merge-sides`, `side-commits`, `moved-identities`, `commit-digests`, `main-changed`, both cache kinds; tests `…-once-per-commit-pair`, `…-by-index-probe`. Keep `fork-of`, `identity-digests`, base retention, `three-way`. (`test/runner.clj`, `registry.clj`) | New `seon.test.reach-test/a-branch-result-identity-is-a-function-of-its-own-head`: T's reach digest on B equals T's on an independently built `(d/with …)` of the same rows; recorded digest nonempty; second execution count 0; after main changes a row T reaches, the branch's result stays current on B and the merge candidate's digest differs. Convert `…-is-not-current-after-main-changes-what-it-reaches` (inverted) and `…-ran-what-main-moved-past-is-recorded-at-its-identity`. Red: HEAD digests main's row. | Host-bound (runner) |
| P4 | Forward merge: `my.root/merge-main!` [chain-branch] → `seon.program/three-way` of base, B, main HEAD digest maps; `seon.merge` moves `changed-on-head` program rows to B, lists conflicts; root runs it over chains at each restart batch (`bin/seon start --head` names it). (`my/root.clj`, `merge.clj`) | New `seon.merge-test/root-merges-main-forward-into-a-chain-branch`: after main changes `g` (B untouched) and `h` (B changed), merge-main! gives B's `g` row = main's, `h` = B's and listed in `:seon.program/conflicts`, `(::interpreted plan)` no longer contains `g`, no task or config datom moved. Red: Var absent. | Host-bound (merge) |
| P5 | `config apply --branch B` / `--chains` over `apply!` with `branch-connection`. (`operator.clj`, `operator_cli_test.clj`) | New `seon.config-test/root-applies-config-to-a-chain-branch-and-the-running-agent-reads-it`: apply cap 100 to the chain's connection; the agent's next `turns-left` reflects 100 with no graph restart; main's row still 40. Red: entrance absent. | Host-bound (bin/script, config) |
| P6 | Done (owner ruling 2026-10-15: each branch's projection is its own value; admission resolves the predicate by name against the branch's program, never by a callable threaded through transact). `seon.schema/unloaded-predicate-functions` (was `deleted-predicate-functions`) binds a predicate no loaded Var defines, deleted from a loaded namespace or contracted by the population itself (a branch's own function), to a callable keyed by its name alone that refuses when data is validated against it; a context that defines it binds its own Var at arming (`seon.sci.kernel/context-bound-projection`). Equal declarations on two branches derive one projection (`seon.db/carried-projection` is keyed by declaration content). No bindings plumbing: the Sonnet patch is superseded. Remaining gap: a predicate the branch defines without a contract still refuses `:seon.schema/unresolved-predicate` (the done rule refuses it anyway). | `seon.turn.batch-test/a-contract-naming-a-predicate-defined-on-the-branch-is-admitted-and-armed` (red run 3e3649e7270c on 83dbc9a9e), `seon.turn.batch-test/branches-declaring-one-predicate-contract-share-one-projection`, `seon.schema-test/a-declaration-naming-an-unloaded-predicate-stays-readable`. Retired task `seon.schema/a-contract-naming-a-branch-predicate-is-refused-at-the-writer`. | Host-bound (`schema.clj` only) |

Order: P1 → P2 → P3 serial (`registry.clj` is claimed by P2 then P3 for one commit window each; P1 removes the
evaluator's calls so P3 can delete). P4, P5, P6 are file-disjoint from them and each other and run beside; P4 after
P1 so its proof can assert the interpret set. Root restarts once after P3 and once after P4, forward-merges the S5
chain, and reruns it as the live probe: a first turn with no analysis or doc failure, keyed green. Net: about −165
source for P1–P3, +60 for P4–P5; tests about +40. The phase-status line lives under the amendment in
branch-model-rulings.md and is updated per commit. Next round (rulings 5, 6) relies on: a result's digest is a
function of B's rows alone; the candidate set is `changed-identities` since the base; reuse is by digest equality
with the candidate and anything different reruns.

## 6. Narrow check: forms root runs on default (JVM mode, `(seon.cluster.boot/connection "default")`)

With `main (seon.db/db conn)`, `chain (seon.db/branch-database main :chain-f1f6dc8e6652)` (or the current S5 chain),
`loaded (seon.sci.eval/loaded-database …)`:

1. **G's interpret set today**: `(count (::interpreted (seon.sci.eval/derive-overlay-plan chain loaded)))` (the
   2-arity, which is I(B, L)) and `(count (seon.fn/reverse-closure <L call graph> #{'seon.db/q}))`. Expectation: the
   first is in the hundreds and the second most of the program; this is the number that justifies P4.
2. **own-delta size**, for the candidate set: `(seon.profile/measure (seon.program/changed-identities (:seon.branch/base-t
   (seon.cluster.registry/fork-of chain :chain-f1f6dc8e6652)) chain {:seon.program/max-datoms 10000}))` → 24, ~68 ms.
3. **L vs main HEAD**: `[@seon.sci.eval/loaded-identity (seon.db/pull main [:seon.source/commit-id] [:seon.cluster/name "default"])]`
   equal; declarations whose digest differs between `main` and `loaded` → 10.
4. **5b reproduction under G**: on a scratch branch forked before a main deletion of a function `k` with a caller `c`,
   the 2-arity plan interprets `c` and installs `k`; today the first call of `c` fails analysis on `k` (576009291's
   message). This is P1's red row.
5. **Writer**: `bin/seon check --at HEAD --test seon.turn.batch-test/a-contract-naming-a-predicate-defined-on-the-branch-is-admitted-and-armed`
   red with `:seon.schema/unresolved-predicate` (check run `fbaa9623ae7c` on 90346a992); the failing compile is `seon.schema/compilable-form` under `seon.db/validate-write-report`'s projection (`seon.db/speculative-report-projection`), reached from `seon.turn/settle-batch!`'s `seon.db/transact!`; `row-tx` is not on the path.
6. **Config to a branch**: `(seon.db/branch-connection main :chain-f1f6dc8e6652)` non-nil for a chain with a running
   graph; `(seon.config/agent-cluster-dial chain <chain agent> :seon.config.run/max-episode-runs)` → 40, on `main` → 100.
7. **Three-way exists for the forward merge**: `(seon.program/three-way base-digests branch-digests main-digests)` on the
   S5 chain answers `changed-on-head` ≈ 125, `changed-on-branch` 22, conflicts 0 or named.
8. **Callers of the deleted set**: `rg "seen-database|moved-past|merge-sides|commit-digests|main-changed" src test` →
   `seon.sci.lookup` 405, `my.program` 748, `seon.ref` 445/552, `seon.sci.eval` 2969/2994/3022, `seon.test.runner`
   1238/1243/2371/2373, and the five eval/reach tests.

A form that does not reproduce stops its phase from being filed.
