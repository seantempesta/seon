---
title: "Alignment hybrid: the commit is the point in time; everything else is a function of commits and digests"
type: plan
status: hybrid (lane align-hybrid, Fable, wrote neither plan); awaits root's narrow check (§5) before tasks are filed
created: 2026-10-15
basis: HEAD 38fbff4eb (datahike fork pinned 771a0c4b since f4b7aab32); default pid 4912 runs f4b7aab32
inputs: alignment-dig.md (58 rows), alignment-plan-opus.md (8116a003f), alignment-plan-astra.md (38fbff4eb)
---

# Alignment hybrid

Owner (2026-10-15): "I want immutable solutions that work with the system. We are always talking about aligning the
same things: SCI and db, points in time, random agent test envs. How can we best support this? How does it affect all
the branch work?" Root's thesis: a point in time is one Datahike commit id; every derivation is a pure function of
commit ids and content digests, cached by content and shared.

## 1. The model

**Both plans confirm the thesis for committed state and both bound it the same way.** Where they agree, the hybrid
takes it as settled:

- **A point in time is a commit id** (`seon.db/commit-database` materializes it). **A t is a coordinate inside one
  lineage**: Datahike counts tx ids per branch (one-merge-base: 774 ids name different transactions on main and
  `:chain-f1f6dc8e6652`). A t may be stored and read only where every reader is on the writing lineage. No t crosses
  a lineage; no stored t is a second record of what the commit graph already knows.
- **Two grades of content key.** Attribute revisions (`seon.db/content-revisions`) are the free lookup key: equal
  revisions prove equal content, unequal revisions prove nothing (Opus measured 170 of 170 program attributes at
  different revisions between main and a chain whose program differs by 24 identities). Declaration digests are the
  share key. The installed shape, revisions → one small `seon.cache` entry → digest key → the shared value
  (`seon.sci.eval/program-context` behind `::program-key`), is the model for every derivation; on a revision miss,
  `datahike.api/datoms-diff` (fork 771a0c4b, pinned) finds what changed.
- **`datoms-diff` of two commits is the one change primitive** (git's `diff-tree`): own change, the interpreted set,
  reach attribute maps, the test world's program overlay, change since a run, history scans. One range per attribute
  (Astra: a wide range walks unrelated data); normalize to identity and digest before calling a definition changed.
- **One place is physics: the JVM's compiled Vars**, named by the loaded commit L, held once in
  `seon.sci.eval/loaded-identity`. Every derivation takes L as a value input; nothing finds L through a branch's
  adoption record (a fork-time copy) or a live connection. Today three readers find it three ways (dig A1/A3/C3).
- **Overlays stay** (Datahike's writable value over a store never written; the fork batch made them share nodes:
  overlay-mem-fix +1 MB for four worlds versus +1,061 MB). What changes: an overlay starts from a commit named by
  content, and nothing is keyed by the overlay's place.
- **Live objects are a registry, never a point in time**: connections, SCI forks, graphs (AGENTS, one registry per
  scope). An agent's private interns are its compartment state and are never shared or rebuilt from facts.
- **Merge base and fork point stay two derivations** (program entities three-way from the merge base, cluster facts
  from the fork point; one-merge-base phase 2b, root's ruling). MERGE-STEP's reader question is closed: 7e84ba763
  installed `seon.task/branch-attempts`, one reader of an attempt where its row names (Opus Q4 option a).
- **The test world W = L's clean checkpoint ⊕ `datoms-diff(L, candidate)` over program attributes**: the candidate's
  entire program P(C), its delta taken against the fixture base's program L, never against the merge base (Astra:
  a merge-base delta silently runs main definitions the candidate never held; Opus P7 says the same diff).

**Where they differ, resolved:**

| Point | Opus | Astra | Hybrid and reason |
|---|---|---|---|
| Hot reload and `call-detecting-program-change` (A2) | stays: physics, detect-and-push-back | delete only under a fixed loaded program per JVM lifetime (its P7, 2-4 days) | **Stays; P7 is owner question Q1.** AGENTS "Reload" already mandates compare-and-push-back, the compare costs 0.8 μs, the dig found no bug on A1/A2, and process replacement measured 50.7 s readiness. Nothing here depends on Q1 |
| Answered basis | `:seon.agent/answered-t`, own lineage | `:seon.agent/answered-commit`, the captured input commit | **A t, written by the recording transaction itself** (Datahike resolves `"datomic.tx"`, dig B13). Reason: the writer may batch several transactions into one commit (Astra's own finding), so a commit is coarser than the answer; `d/since` on the own branch needs no translation; wakes are written on the agent's branch (TASK-CHAIN Messaging), so the lineage law holds by construction; and `since(t)` excludes the recording transaction, which is exactly the fix `resources/seon/tasks/seon.turn.edn`'s prove-loop task asks for. AGENTS already names it. Owner question Q4 |
| Own change on the request path | replace `entities-changed-after` by diff (its P2) | preserve slice 1's changed-input economy | **Keep slice 1's read; derive its start from the graph.** Measured: `entities-changed-after` 1.31 ms / 1.0 MB on an unchanged 80-transaction branch versus the diff primitive 2.8 ms / 9 MB nearby. The t it starts from is lawful (own lineage) but must not be stored: it is `basis-t` of the fork point commit (`seon.cluster.registry/fork-point`, cached by head commit), so `:seon.branch/base-t` still dies (H6) and the merge and compare paths (218-552 ms / 0.4-1 GB history scans) take the diff (H2) |
| Kernel atoms (A9), projection on connection meta (E4), `seon.env` atom (E8) | S / D later / S | delete two mirror cells and the metadata state (its P2b) | **Stay, not phased.** No observed bug, no measurement; AGENTS lists the SCI context as an owner of state. A9 goes on the justified-atom ledger. Reopen if a bug names them |
| Connection monitor and borrowing (E2, E3) | stay: one writer per branch is physics | delete after counted `acquire-branch!` | **Narrow check decides** (§5 item 8): if the fork's `connect` is already reference-counted, the monitor duplicates it and is a fence (AGENTS: file its root cause); else it stays. Not phased until checked |
| `destructive-key` on an as-of value (C9) | revisions only; open the commit instead | keep a654ad995 until replaced | **a654ad995 stays**: its key `[:seon.db/as-of origin-commit time-point]` is an immutable value (0.05 ms on repeat, from 767 ms / 1.24 GB). H5 makes a turn open its head commit, after which the as-of arm has no caller and goes with it |
| `basis-valid?` (D5) | installed derivation, keep | TASK-CHAIN retires it | **X, owned by TASK-CHAIN's obligation retirement**, not phased here |
| Phase granularity | 7 phases | 15 phases, incl. E9/E11 trivia | **8 phases.** E9 (`branch-heads` atom → fold) and E11 (namespace volatile) are folded into the phase that next touches their file |
| First benefit | P1 first | P1a (diff) first | **H0 first**: the live agent path. eval-cost measured the `db<->flow` pass at ~80 ms CPU and 180 MB per write (every admitted branch reopened), and a first request at 1,015 ms of which custody is 857 ms |

**Something both missed.** The `db<->flow` pass is the largest misalignment on the live path and neither plan
classifies it: `seon.cluster.agent/lifecycle-facts` → `taskless-agents` → `branch-admission-end` opens every admitted
agent's branch head per write (125 per pass; `seon.db/vector-query-call` 35 % of the thread), reading places when the
woken commit already names the one branch that changed. Under the model, each assignment's lifecycle facts are a
function of `[main's commit, that branch's head commit]`, cached by the pair; a pass recomputes only the woken
branch's entry and reads the others' heads (2.8 ms `d/connect`-free pointer reads). That is H0.

**The simplest alternative, rejected by both and here:** key everything by commit UUID and rebuild per head. A `d/with`
value aliases its parent's UUID (Astra reproduced it: `datahike.api/commit-id` on `db-after` returns the parent's),
bookkeeping commits change no program, and equal content on two branches would never share. The hybrid keeps the
installed content derivations and deletes the extra selectors beside them.

## 2. The 58 rows, reconciled

D = derivation from the named value, X = deleted (with its last caller), S = stays (physics reason). [H#] = phase.

| Rows | Verdict | From / reason |
|---|---|---|
| A1 `loaded-identity` | S | the JVM's Var roots; the only source of L, taken as a value input everywhere [H1] |
| A2 `call-detecting-program-change` | S | AGENTS "Reload": compare at start and end, push back; Q1 may delete it later |
| A3 `loaded-source`, `::loaded-connection` | X [H1] | L passed from A1; 23 + 13 uses convert |
| A4 adoption record | S | durable record of what was published (boot's input); no branch reader of "the loaded program" after H1 |
| A5 `adopt-cluster-declarations!` | X [H1, patch to step-merge] | acquisition writes no declarations onto a branch once the program context is derived from (L, HEAD) |
| A6 agent registry `[branch agent-id]` | S | live objects keyed by stable ids |
| A7 `held-base` place shortcut | X [H3] | the content key under it already answers |
| A8 `program-context` content key | D (installed) | fix its 1-arity `loaded` default (2.6-3.4 s on a chain) in H1; the vanishing private def (10-14 defect 2) is agent state: verify separately |
| A9 kernel atoms | S | SCI context state of one fork; ledger it |
| A10 `context-database` | D (installed) | the pattern: read the connection at use |
| B1 `basis-t` | S | a coordinate on the value it is read from |
| B2, B3 commit materialization | D | the point itself; pass a held value instead of reopening |
| B4 `merge-base` | D (installed) | by the sorted pair |
| B5 `:seon.branch/base-t` / `fork-point` | X base-t [H2, H6]; D fork-point | the start is the graph's; the own-lineage t is `basis-t` of the fork point when a since-window is wanted |
| B6 `changed-identities` `(or base-t merge-base)` | D [H2] | request path: fork-point t on own lineage; merge paths: `datoms-diff(merge-base, HEAD)` + digest three-way |
| B7, B8, B9 run basis, comparison, unchanged-through-t | D [H4] | a run records `:seon.test.run/commit`; change = diff(tested, HEAD); B9 deleted (equal revisions or empty diff) |
| B10 `d/since` window | X [H4] | diff of two values |
| B11 error `basis-t` | S | written and read on its own branch; verify no cross-branch reader (§5 item 9) |
| B12 context results, assignment `as-of` | D [H5] | results by reach digest; assignment read at the attempt's commit |
| B13 `"datomic.tx"` | S | Datahike's tempid for the recording transaction; the writer of B14 |
| B14 `:seon.agent/answered-t` | D [H5] | absent at HEAD (only a task plan and `seon.problems` name it); written by the recording transaction |
| B15 `call-reach-key`, B16 render `basis-transaction` | D [H5] | revisions, or origin commit + time point; t only for display |
| C1 `open-overlay!` | S | writes over an immutable store; node sharing landed |
| C2 `open-world!` projection | D (installed) | projection of W by content |
| C3 `world-base` ladder | X [H1] | W's base is L, passed in; "store never held it" is a named refusal |
| C4 `request-view!` | S/D | overlay stays; key `[commit tx-digest]` (D3's shape), never the live head unnamed |
| C5 `program-overlay-tx` | D [H7] | `datoms-diff(L, candidate)` once per `[L plan-digest]` |
| C6 `carried-results` | D (installed) [H4] | content identity; other branches' runs become a cache lookup, never a validity source |
| C7 `reach-owner`, C8 `reach-changed-entities` | X [H3] | reach-maps.patch: map at R from the held map at P by `datoms-diff(P, R)` per attribute |
| C9 `destructive-key` | D (installed, a654ad995) | as-of key is an immutable value; as-of arm retires with H5 |
| C10 world delays, effect counter | S | per-world memo and a justified counter |
| C11 `loaded-source-drift` | X [H1] | with L in every result identity, another L is another key |
| D1 `seon.cache/process` | S | the one cache |
| D2 render `branch-scope` atom | X [H5] | keyed `[connection-id generation]`; becomes revision-keyed kinds |
| D3 `request-view` key | D (installed) | drop `max-tx` (implied by the commit) |
| D4 `config/effective`, D6 result cache | D (installed) | content |
| D5 `basis-valid?` | X (TASK-CHAIN) | obligation path retired there |
| D7 `::replaced-roots` by `Var/rev` | S | a stamp of the one place, same class as A1 |
| D8 counters, D9 `launched-source-digests` | S | justified counters; `!fallback-counts` and `fault/folded` go on the ledger or die (outside this plan) |
| E1 `on-branch`, E5 registry, E7 config apply, E10 Flow state, E12 delays | S | one action's connection; the registry; root's explicit write (ruling 7); Flow owns execution; per-call memo |
| E2 borrowing, E3 monitor | S pending §5 item 8 | X if the fork's connect is counted |
| E4 projection on connection meta, E8 `seon.env` atom | S | not phased (no bug, no measurement) |
| E6 `fork!`/`record-fork!` | D [H6] | Datahike records the fork commit's parent; fork entity → `{name target}` |
| E9 `branch-heads` atom, E11 namespace volatile | X (folded) | a `reduce`; SCI's current namespace; in the phase that next touches the file |

Totals: D 27 (10 installed), X 12 (+2 pending the check), S 19. Every S is a live object, the writer, the JVM's Vars,
a justified counter or a per-call memo. None keeps a point in time by hand.

## 3. Phases, in the order the live agent path benefits

Each is one loadable commit (host-bound slices through `bin/lane commit … --test`); a "new" deftest is written red in
the phase's first commit; (m) measured, (d) derived. The fork bump that every diff phase needs is already pinned
(f4b7aab32) and default runs it, so no phase waits on a bump. **Dependencies on in-flight lanes:** H0, H1's A5 and H5's
B12 touch `src/seon/cluster/agent.clj`, held by lane step-merge (8 paths since e9e5834ed): those slices go to it as
patches. H3 is reach-maps' patch, unrun at its writing (the fork lacked `datoms-diff`); it must run before it lands.
H2 is one-merge-base phase 3's rest and H6 its phase 4; they are owned there and this plan only names L and the run
commit as the other diff inputs. The flow-pass work (eval-cost's proposed slice, same file) is H0.

| # | Change | Red proof | Deletes (last caller converted) | Files | Cost before → after |
|---|---|---|---|---|---|
| H0 | **The pass derives from the woken commit.** `lifecycle-facts` per assignment = `seon.cache` lookup by `[main commit, branch head commit]`; a pass recomputes only entries whose pair changed | new `seon.cluster.db-flow-test/a-pass-reopens-only-the-woken-branch`: `seon.profile/measure` on `run-pass!` after one branch's write asserts one `seon.db/branch-database` open | `taskless-agents`' per-agent `branch-admission-end` opens (callers: `lifecycle-facts`) | `src/seon/cluster/agent.clj` (patch to step-merge), `test/seon/cluster/db_flow_test.clj` | ~80 ms CPU, 180 MB per write for 125 agents (m, eval-cost) → proportional to the woken branch (d) |
| H1 | **L is one value.** One reader `seon.sci.eval/loaded-commit` of `loaded-identity`; `program-context` and `base-ctx` take L explicitly; `seon.test/clean-request-handle` uses it as W's base; `acquire!`/`branch-ctx` stop following `::loaded-connection`; `acquire-context!` stops writing declarations | new `seon.sci.eval-test/a-branch-program-context-is-built-on-the-loaded-commit-not-its-record`; convert `seon.test-test/a-branch-test-world-runs-the-jvm-loaded-program-not-its-fork-point-record` to assert the branch-held declaration stays | `loaded-source`, `loaded-program`'s record read for branches, `::loaded-connection` plumbing, `world-base` (9 uses) and its 3-way `cond`, `loaded-source-drift` (7 uses, 3 readers), `adopt-cluster-declarations!` (2 uses; patch) | `src/seon/sci/eval.clj`, `src/seon/test.clj`, `src/seon/cluster/agent.clj` (patch), `test/seon/sci/eval_test.clj`, `test/seon/test_test.clj` | 1-arity `program-context` on a chain 2.6-3.4 s / 2.0-2.6 GB (m, Opus) → 0.2 ms hit (m, main's warm path); first acquisition after restart stays one cold derivation (~540 ms per miss, m: 4,874 ms over 9 misses) |
| H2 | **Own change by diff on the merge paths** (one-merge-base phase 3 rest). `source/changed-identities`, `merge-accepted`, `task/changed-handles`, `changed-attributes`' t-arity read `datoms-diff(before, after)` per program attribute; the request path's `branch-changes` starts from `basis-t` of the fork point (graph), not `:seon.branch/base-t` | existing `seon.merge-forward-test/after-a-forward-merge-the-branch-changed-only-its-own-declarations` extended with equal-t siblings and a retract/re-add row; `seon.profile/measure` on `source/merge-accepted` parent vs change | `:seon.branch/base-t` reads in `changed-identities` (14 lines), `basis-revisions`, `commit-at-t` (5 uses), four `d/since`+`:eavt` scans | `src/seon/program.clj`, `src/seon/cluster/source.clj`, `src/seon/task.clj`, `src/seon/db.clj` (`datoms-diff` wrapper from reach-maps.patch), `test/seon/merge_forward_test.clj` | scans 218-552 ms / 0.4-1 GB each (m) → ≈ 15 ms / 20 MB (d, diff primitive 2.8 ms nearby, 100-130 ms / 250 MB long-diverged); request path unchanged 1.31 ms / 1.0 MB (m, slice 1) |
| H3 | **Reach maps and the interpreted set by diff.** Land reach-maps.patch (run it first); `seon.sci.eval/overlay-plan` candidates from `datoms-diff(L, HEAD)`; `::reach-head` by store | `seon.test.reach-test/a-new-branchs-first-attribute-map-costs-what-it-changed` (in the patch; add an interleaved equal-t pair); `seon.sci.eval-test/an-overlay-plan-reads-the-branch-digests-by-index-range` gains a `seon.profile/measure` row | `reach-owner`, `reach-changed-entities`, `retire-replaced-revision!` (30 lines), `held-base` shortcut | `src/seon/test/runner.clj`, `src/seon/sci/eval.clj`, `test/seon/test/reach_test.clj` | first reach on a new branch 470 MB (m, exp-c) → the delta's closure (m 247 MB at a 53 % closure; ≈ 0 on a one-change branch, d); plan 357 ms / 653 MB (m) → ≈ 40 ms / 50 MB (d) |
| H4 | **Change since a run by commit.** A run records `:seon.test.run/commit`; `changed-since-green`, `select`'s `changes-since`, `program-written-since?` read `datoms-diff(tested, HEAD)`; `carried-results` reads other branches only through `seon.cache` | new `seon.test-test/test-evidence-follows-content-not-transaction-ids`, two rows: equal t with different content shares nothing; equal content on another lineage reuses the green | `unchanged-through-t` (4 uses), `changed-attributes` t-arity, the branch-scan validity in `carried-results` | `src/seon/test.clj`, `src/seon/test/runner.clj`, `resources/seon/schema` (one attribute), `test/seon/test_test.clj` | test-step `since` 56-128 ms / 98-151 MB (m, pre-slice-1) → few ms (d); measure full `seon.test/run` with recording |
| H5 | **Renders and the answered basis at a commit.** A turn opens its head commit (`commit-as-db`, not `as-of`); `call-reach-key` keys revisions or origin+time; `branch-scope` → `seon.cache` kinds; amend TASK-CHAIN and `:seon.agent/answered-t` written by the recording transaction, `next-agent-work` derives from `since(answered-t)` | new `seon.render-cache-test/equal-inputs-render-once-across-branches`; new `seon.turn-test/recording-an-answer-does-not-answer-a-later-wake` (wake between capture and record; bookkeeping-only advance; crash before record) | `branch-scope` (8 uses, 27 lines), as-of arm of `call-reach-key`, the assignment `as-of` in `seon.context` | `src/seon/render.clj`, `src/seon/turn.clj`, `src/seon/context.clj`, `docs/seon/schedule/cards/TASK-CHAIN.md`, `resources/seon/schema`, tests named | warm `current-state` 221 ms / 309 MB (m, leaf-hot) → changed sections only (d); the prove-arm loop (one run per 4 min, a core hot) ends (seon.turn.edn task) |
| H6 | **Stored starts deleted** (one-merge-base phase 4). `fork-point` reads graph records with `:datahike/branch`; `retained-before` incremental; base refs, 125 `:base/*` branches, `:seon.branch/base-t`, fork entity → `{name target}` | ledger rows all converted; `seon.cluster.registry-test/an-idle-branch-keeps-the-commit-it-forked-from-through-collection`; new `seon.profile/measure` on `retained-before` after remeasuring (the 8.8 s figure predates ed9eb4f99) | `base-branch`, `base-commit-id`, `advance-base!`, base-t attribute, `record-fork!`'s base-t (25 uses) | `src/seon/cluster/registry.clj`, `src/seon/cluster/store.clj`, `resources/seon/schema`, `test/seon/cluster/registry_test.clj` | fork-point cold 1.6 s / 1.4 GB (m) → tens of ms (d); collection 8.8 s / 27 GB (m, stale) → remeasure, then ≈ 0.15 s (d) |
| H7 | **Test world by content.** W's program overlay = `datoms-diff(L, candidate)` → tx, once per `[L plan-digest]`; checkpoint key drops `process` if Q3 says so; `carry-world-projection!` goes if the projection of W is looked up by W's revisions (verify) | new `seon.test-test/two-requests-at-equal-content-build-one-world-overlay` (second request: zero overlay derivations, by cache misses) | `program-overlay-tx`'s `digest-differences` path (7 uses), `carry-world-projection!` (4 uses, verify) | `src/seon/test.clj`, `test/seon/test_test.clj` | per-request overlay derivation unmeasured: measure first (m required), then a hit (d) |

H0 and H1 need no diff and no restart beyond root's batch; H2-H4 reuse `seon.db/datoms-diff` from H2's wrapper;
H5-H7 are independent of each other after H4. Every deletion lands in the commit that converts its last caller; HEAD
boots after each. About 20 functions and ≈ 300 lines go (Opus's count; Astra's 13 named functions are a subset);
added: one reader of L, one `datoms-diff` wrapper, two attributes (`:seon.test.run/commit`, `:seon.agent/answered-t`).

## 4. Owner questions (merged, deduplicated; recommendation first)

**Q1. Does the JVM's loaded program stay hot-reloadable, or fix for the JVM's lifetime?**
- (a) **Recommended: keep hot reload with the installed compare** (A2). Guarantee: AGENTS' rule, 0.8 μs per call, no
  observed bug. Cost: none. Sacrifice: `advance-loaded-identity!` and the expectation stack stay (4 functions).
- (b) Fixed loaded program per JVM (Astra P7, 2-4 days with platform proofs): delete A2/A3/C11 and the agreement
  protocol; platform changes land only by batched replacement. Guarantee: no mutation of L ever. Sacrifice: every
  host-bound change costs a replacement (readiness measured 50.7 s) and private state.

**Q2. Where does a test world's commit live?**
- (a) **Recommended: per-request overlay, derivation shared by content** (H7). Nothing durable grows; each request
  still writes a small in-memory overlay tx. Sacrifice: no node sharing of W across requests.
- (b) One process-lived overlay store holding W branches by `[L plan-digest config-digest]`. W written once per
  content; cost: an overlay GC to free member writes (none exists). Sacrifice: a new lifecycle.
- (c) Durable W branches as checkpoints. Survives restart; cost: storage growth per content ("don't cause storage growth").

**Q3. Does a checkpoint's key keep `process`?** Today `checkpoint-<digest [base cluster effective process]>`.
- (a) **Recommended: drop it** if `seed-cluster-facts!` writes it only as provenance (one grep and a test): worlds
  outlive a restart. (b) Keep: every restart rebuilds checkpoints (≈ 100 ms each, unmeasured).

**Q4. The answered basis: t or commit?**
- (a) **Recommended: `:seon.agent/answered-t`, written by the recording transaction** (H5), with the law "a t is read
  only on its own lineage" added to AGENTS. Finer than a commit (the writer batches), direct `d/since`, no translation.
- (b) `:seon.agent/answered-commit`, the captured input commit. Comparable across branches (nothing needs that: root
  reads each branch on its own lineage); cost: a vocabulary change, a 0.7 ms record read per wake derivation, and a
  batched commit names several transactions at once. (c) Both. More data than any reader needs.

**Q5. Which program does a test world run?** Both plans answer the same and only the contract changes:
- (a) **Recommended: the candidate's entire program**, W = L ⊕ `datoms-diff(L, C)` (H1 base, H7 overlay), the old
  test converted (1-2 days). (b) The agent's full commit as the fixture base: simplest pointer sharing, but inherits
  live agents and messages. (c) Loaded program plus own delta against the merge base: no work; rejected (runs main
  definitions the candidate never held; branch ruling 1).

Closed, not asked: MERGE-STEP's end reader (7e84ba763 installed option a); the fork bump (pinned f4b7aab32).

## 5. Narrow check for root (read-only forms on default, JVM mode, root, cluster default)

Every claim the hybrid rests on, with the form that reproduces it. Each is read-only; none defines, transacts or evicts.

1. **The fork is pinned and loaded** (H2-H7 need no bump): `(resolve 'datahike.api/datoms-diff)` is a Var, and
   `(seon.db/commit-id (seon.db/db (seon.cluster.boot/connection "default")))` answers on pid 4912.
2. **The 1-arity program context of a chain keys on the branch's record, not L** (H1's before-cost):
   `(let [db (seon.db/db (seon.cluster.boot/connection "default")) b (first (filter #(clojure.string/starts-with? (name %) "chain-") (seon.cluster.registry/branches db)))] (select-keys (seon.profile/measure #(seon.sci.eval/program-context (seon.db/branch-database db b))) [:seon.profile/ms :seon.profile/alloc-bytes]))`
   — expect seconds and GB cold (Opus: 3,404 ms / 2,641 MB), then a sub-ms hit; the run allocates above 1.5 GB, so run
   once. Confirm `(seon.sci.eval/program-context db)` on main hits in < 1 ms.
3. **Revisions are not a share key**: `(let [db (seon.db/db (seon.cluster.boot/connection "default")) b <chain as above>] (count (remove (fn [[k v]] (= v (get (seon.db/content-revisions (seon.db/branch-database db b) (keys …)) k))) (seon.db/content-revisions db (seon.test.runner/program-revisions db)))))` — expect most of 170 differ while `(seon.program/own-changed-identities …)` names ≈ 24 (replace `…` with the attribute list `program-revisions` reads).
4. **L is found three ways today**: `(map (comp boolean resolve) '[seon.sci.eval/loaded-identity seon.sci.eval/loaded-source seon.test/world-base])` → `[true true true]`; `(:seon.source/commit-id @seon.sci.eval/loaded-identity)` equals the cluster entity's `:seon.source/commit-id` on main and differs from a chain's record (`(seon.db/q '[:find [?c ...] :where [?e :seon.cluster/name] [?e :seon.source/commit-id ?c]] (seon.db/branch-database db b))`).
5. **The pass reopens every admitted branch** (H0's before-cost): `(select-keys (seon.profile/measure #(seon.cluster.agent/lifecycle-facts <db> <registry>)) [:seon.profile/ms :seon.profile/alloc-bytes :seon.profile/read-entrances])` with the arguments `run-pass!` passes (read its body); or the cheaper census: `(count (seon.cluster.registry/branches db))` against eval-cost's 125 `branch-admission-end` calls per pass. Confirm `taskless-agents` opens one `branch-database` per agent by its `seon.profile` cell after one write.
6. **The request-path read is cheaper than the diff on an unchanged branch** (H2 keeps slice 1): on a chain branch value `v` with fork point `f`, compare `(seon.profile/measure #(seon.db/entities-changed-after v (seon.db/basis-t (seon.db/commit-database v f)) :seon.program/definition-digest))` with `(seon.profile/measure #(datahike.api/datoms-diff (seon.db/commit-database v f) v :aevt [:seon.program/definition-digest]))` — expect ≈ 1.3 ms / 1 MB versus ≈ 2.8 ms / 9 MB.
7. **`answered-t` is absent from the installed schema**: `(contains? (set (map :db/ident (seon.db/q '[:find [(pull ?e [:db/ident]) ...] :where [?e :db/ident]] db))) :seon.agent/answered-t)` → `false`; grep shows only a task plan and `seon.problems` name it.
8. **Is the fork's connect reference-counted?** (E2/E3 verdict) Read `reference-code/datahike/src/datahike/connector.cljc` at 771a0c4b for a connection count or `release` arity; then `(seon.profile/measure #(seon.cluster.registry/on-branch db b identity))` twice to see whether the second open hits the monitor's refusal path. Counted → file E3's monitor as a fence with root cause "two holders of one branch"; else S.
9. **No cross-branch reader of error `basis-t`** (B11 S): `rg -n "seon.error/basis-t|occurred-since" src` and read each caller's database argument: every one must be the branch's own value.
10. **a654ad995 is loaded and keys the as-of**: `(seon.db/as-of-key (seon.db/as-of db (seon.db/basis-t db)))` → `[:seon.db/as-of <commit> <t>]`; and `(seon.profile/measure #(seon.test/host (seon.db/as-of db (seon.db/basis-t db)) <a test sym>))` twice → second under 1 ms.
11. **The reach-maps patch applies and runs on HEAD**: `git apply --check docs/research/agent-platform/2026-10-15/reach-maps.patch` on a `git archive` of HEAD, then its deftest through `bin/seon check` on a disposable branch; it was never run (exp-c §8).
12. **Equal programs already share one program context across branches** (sharing is proven, so H1 is about the
    selector, not the cache): `seon.sci.eval-test/equal-programs-on-two-branches-share-one-program-context` green on
    HEAD (stored run, not rerun).

A claim that does not reproduce changes its row and phase before any task is filed; a phase whose red proof is already
green is deleted, not manufactured.

## Summary

1. Both plans confirm the thesis for committed state: a commit id is the point in time, a t a coordinate inside one lineage, digests the share key, revisions the free lookup key.
2. The one physics place is the JVM's loaded commit L, held once; every derivation takes it as a value (H1 deletes the three other ways of finding it).
3. `datoms-diff` (pinned 771a0c4b, loaded on default) is the one change primitive; no phase waits on a bump.
4. Resolved differences: hot reload stays (Q1), the answered basis is a t written by the recording transaction (Q4), slice 1's cheap request-path read stays with its start derived from the fork point, kernel/projection mirrors and the connection monitor are not phased until checked.
5. Both plans missed the largest live-path misalignment: the `db<->flow` pass reopens 125 branches per write (80 ms CPU, 180 MB); H0 derives it from the woken commit.
6. Of 58 rows: 27 derivations (10 installed), 12 deletions (+2 pending check 8), 19 stay as live objects, the writer, the JVM's Vars or memos.
7. Eight phases, H0 and H1 first (no diff needed), then H2-H4 (diff), H5 (renders, answered basis), H6 (stored starts), H7 (world by content); about 20 functions and 300 lines go.
8. In-flight dependencies: H0, A5 and B12 patch lane step-merge's `agent.clj`; H3 is reach-maps' unrun patch; H2/H6 are one-merge-base phases 3 and 4.
9. Five owner questions, each priced with a recommendation; MERGE-STEP's reader and the fork bump are closed.
10. Twelve narrow-check items with read-only forms; the 1-arity program-context probe allocates over 1.5 GB and runs once.
