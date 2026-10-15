---
title: "Alignment plan (Opus): the commit is the one point in time"
type: plan
status: independent plan (lane align-opus); a hybrid follows
created: 2026-10-15
basis: HEAD 2724ef32d; default JVM loaded commit 6ad07b08; fork pinned 1a149891 (datoms-diff 771a0c4b not bumped)
---

# The commit is the one point in time

Independent plan answering the owner (2026-10-15): "I want immutable solutions that work with the system. We are
always talking about aligning the same things: SCI and db, points in time, random agent test envs." It tests root's
thesis against the code and default (JVM-mode reads only), classifies the 58 rows of
[alignment-dig.md](alignment-dig.md), and phases the work.

## 0. Verdict on the thesis

**Confirmed, with three refinements that the measurements force.**

1. **A point in time is a commit id; a t is a coordinate inside one lineage.** Datahike counts tx ids per lineage
   ([datahike-internals.md](datahike-internals.md) finding 1: 738 ids on both main and the S5 chain). So a t is valid
   only against values of the lineage that wrote it, exactly as git's `HEAD~3` means something only on one branch.
   Every *stored* t that another branch reads is an alignment bug; a t used inside one lineage (an agent's own
   answered basis, `d/since` on the same value) is not. An *uncommitted* value (an eval request's facts, `d/with`)
   is a commit id plus the digest of its pending tx-data, git's index over HEAD; `seon.db`'s `request-view` key
   `[commit max-tx digest]` already has this shape.
2. **Two grades of content key, and they must not be confused.** Datahike's attribute revisions
   (`seon.db/content-revisions`: "the commit whose datoms of it equal the value's") are a cheap *lookup* key: free to
   read, shared along one lineage, but not equal for equal content written twice. Measured: main and
   `:chain-f1f6dc8e6652` hold all 170 program-acquisition attributes at different revisions, although the chain's
   program differs from main by 24 identities (the forward merge wrote main's rows afresh). Declaration digests
   (`:seon.program/definition-digest`, the plan's `::digest`) are the *share* key. The installed pattern is right and
   is the model for every derivation: revisions → (one small `seon.cache` entry) → content key → the shared value
   (`seon.sci.eval/program-content-key` behind `::program-key`).
3. **One place survives, and it is physics: the JVM's compiled Vars.** They are named by one commit id, the loaded
   commit L. L is process state (an atom is the right holder: Var roots are a place), but every derivation must take
   L as a *value input*, never read it from a branch's adoption record (a copy written at the branch's fork) or from a
   live connection. Today three readers find L three ways (§2, A1/A3/C3); that is the SCI-vs-db misalignment.

A fourth observation keeps the plan honest: **tests write**. A test world is a value (a commit), but members need a
writable connection over it. Datahike's overlay store is the immutable answer (writes in memory over a store that is
never written), so overlays stay; what changes is that each starts from a commit named by content and that nothing
is keyed by the overlay's place.

## 1. The model, in Datahike's, SCI's and git's words

| Word | Is | Derived from | Cached by |
|---|---|---|---|
| point in time | a Datahike commit id (git: a commit) | the writer (`datahike.writing/commit!`) | immutable; never cached as a copy |
| branch, HEAD | a Datahike branch pointer to its head commit (git: a ref) | Datahike | nothing; read at use (2.8 ms `d/connect`, 0.7 ms `commit-as-db`) |
| loaded program, L | the commit whose program the JVM's Vars hold (git: the build you installed) | `require :reload` of that commit's write-back | one process value (`seon.sci.eval/loaded-identity`) |
| check out | open the branch's head commit; an agent's world is its branch keyword | the head at each eval request | nothing |
| interpreted set | declarations whose digest differs between L and HEAD, plus their callers (ruling 2) | `datoms-diff(L, HEAD)` over program attribute ranges, then digest three-way and `seon.fn/reverse-closure` | `seon.cache` by `[L, plan digest]`, looked up by HEAD's program revisions |
| program context | SCI's frozen context for that set over L (`sci.core/freeze`) | `seon.sci.eval/program-context` | `[store L plan-digest projection-delta armed-dials]` (installed) |
| agent context | `sci.core/fork` of the program context plus the agent's private interns | fork at acquisition | not cached: the private interns are the agent's compartment state (AGENTS "An agent's context") |
| reacquire | compare HEAD's program revisions with the context's `::program-key`; equal → nothing; else look up the content key; a miss derives from the predecessor's entry | `seon.sci.eval/acquire!` (installed) | as above |
| test world | commit W = (L's checkpoint) ⊕ (candidate's program change over L); members write on branches of an overlay at W | `seon.test/clean-request-handle` | W's program context by content (installed); results by `[test reach-digest input-digest]` |
| what changed | `datahike.api/datoms-diff` of two values over attribute ranges (git: `diff-tree`) | fork `771a0c4b` | by the two commit ids |
| merge base | `datahike.api/merge-base` of two commits (git: `merge-base`) | the commit graph | by the sorted pair (installed, `seon.cluster.source/merge-base`) |
| fork point | first commit of a branch's first-parent lineage written on its target | `seon.cluster.registry/fork-point` | by head commit (installed) |
| reload | advancing L; nothing is invalidated, keys naming the old L simply stop being asked | `seon.cluster/development-source-refresh!` | — |
| render | a function of the value's content and the revisions it read | `seon.render` | by revisions, never by connection or generation |
| answered basis | the agent's own head commit it last answered (a t on its own lineage is equivalent) | the turn's recording transaction | nothing |

Rules this gives every design:
- **Read a point, never store one beside the graph.** Where a branch starts, where it last merged, what it changed:
  all are functions of commit ids. The only stored points are those the graph cannot know: a merge's
  `:seon.squash/source-commit` (history), a run's tested commit (evidence), L (process).
- **Key by what was read, at the cheapest grade that is still content.** Revisions to find, digests to share.
- **No t crosses a lineage.** A t may be stored only where every reader is on the writing lineage.
- **Live objects are a registry, not a point in time.** Connections, SCI forks and graphs live in the one registry
  per scope (AGENTS); they hold no notion of "which time" a value is at.

## 2. The 58 rows

Verdict: **D** becomes a derivation (from the named value), **X** deleted, **S** stays (reason). Phase in brackets.

### A. SCI vs db

| # | Verdict | From / reason |
|---|---|---|
| A1 `loaded-identity` | S | Physics: names the JVM's Var roots. Becomes the *only* source of L; validated process state [P1] |
| A2 `call-detecting-program-change` | S | Physics: a Var root can change under a running execution; AGENTS "Reload" mandates detect-and-push-back, not a lock. It is the one compare of two points that stays |
| A3 `loaded-source`, `::loaded-connection` | X [P1] | L is read from A1 and passed as a value; a live connection's adoption record is a third copy |
| A4 adoption record on the cluster entity | D/S [P1] | Stays on main as the durable record of what was published (boot and restart need it); it is no longer read as "the loaded program" by any branch reader. On a branch it is a fork-time copy: no reader |
| A5 `adopt-cluster-declarations!` | D [P1] | Program context of (L, HEAD); re-applying declarations per acquisition goes once L is an input (verify at P1) |
| A6 `acquire-context!` registry `[branch agent-id]` | S | Live objects (connection, SCI fork) keyed by stable ids: AGENTS' one registry per scope |
| A7 `held-base` reuse by branch | D [P3] | Already content-keyed under it; the place-keyed shortcut is redundant once the plan is cheap (P3) |
| A8 `program-context` content key | D (installed) | Keep. Fix the default `loaded` (branch record, measured below) [P1]. Defect 2 (private def vanishes) is agent state, not program: verify separately |
| A9 kernel atoms per context | S | SCI context state of one fork (AGENTS lists the SCI context as an owner of state); add to the justified list |
| A10 `context-database` | D (installed) | The pattern: read the connection at use |

### B. Points in time

| # | Verdict | From / reason |
|---|---|---|
| B1 `basis-t` | S | A coordinate on the value it is read from; legitimate within a lineage |
| B2 `commit-database` | D | The point itself |
| B3 `source/commit-database` | D | Same |
| B4 `merge-base` | D (installed) | Two commit ids, cached by the pair |
| B5 `:seon.branch/base-t`, `fork-point` | X base-t [P2,P6]; D fork-point | base-t is the last cross-lineage t's anchor; the walk it starts is replaced by the diff |
| B6 `changed-identities` `(or base-t merge-base)` | D [P2] | `datoms-diff(merge-base, HEAD)` + digest three-way |
| B7 `changed-since-green` | D [P4] | Run records its tested commit; change = diff(tested commit, HEAD) |
| B8 run `basis-t` / comparison | D [P4] | Same; t kept only as the run's own-lineage coordinate for display |
| B9 unchanged-through-t | D [P4] | Equal program revisions (lookup) or empty diff |
| B10 `d/since` change window | X [P4] | Diff of the two values (replay-impact: 56-128 ms → a few ms) |
| B11 error `basis-t` | S | Errors live on their own branch; read on that lineage. Verify no cross-branch reader [P4] |
| B12 context current results, assignment `as-of` | D [P5] | Results by reach digest; assignment read at the attempt's commit |
| B13 `"datomic.tx"` placeholder | S | Datahike's tempid for the current transaction, not a point stored by hand |
| B14 `:seon.agent/answered-t` | D [P5] | Implement as the agent's own head at answer (Q3) |
| B15 `call-reach-key` | D [P5] | Revisions only; an as-of of a committed point opens that commit instead |
| B16 render `basis-transaction` | D [P5] | The commit rendered from; t only for display |

### C. Test environments

| # | Verdict | From / reason |
|---|---|---|
| C1 `open-overlay!` | S | Physics of a writable world over an immutable store; memory fix landed in fork batch ae172ed0e |
| C2 `open-world!` projection | D | Projection of the world commit (installed, by content) |
| C3 `world-base` (3 sources) | X → D [P1] | W's base is L, passed in. The "store never held it" branch becomes a named refusal |
| C4 `request-view!` | S/D | The overlay stays (uncommitted facts need a writer); its key is `[commit tx-digest]` (D3 shape) |
| C5 `program-overlay-tx` | D [P6] | `datoms-diff(L, candidate)`; computed once per `[L, candidate plan digest]` |
| C6 `carried-results` | D (installed) | Identity is content `[sym reach-digest input-digest]`; reading other branches' runs becomes a `seon.cache` lookup only [P4] |
| C7 `reach-owner` | X [P3] | exp-c: keyed by branch and generation; prior by store, change by diff |
| C8 `reach-changed-entities` | X [P3] | Diff from the held map's commit (reach-maps.patch) |
| C9 `destructive-key` `[]` on as-of | D [P5] | Key the committed point (commit-as-db) |
| C10 world delays, effect counter | S | Per-world memo and a justified counter of one world's effects |
| C11 `loaded-source-drift` | X [P1] | With L a value in every result's identity, a result under another L is simply another key |

### D. Caches keyed by place or stamp

| # | Verdict | From / reason |
|---|---|---|
| D1 `seon.cache/process` | S | The one cache |
| D2 render `branch-scope` atom | X [P5] | Keys by `[connection-id generation]`; becomes revisions-keyed kinds in `seon.cache` |
| D3 `request-view` cache | D (installed) | `[commit max-tx digest]`; drop `max-tx` (implied by commit) |
| D4 `config/effective` | D (installed) | Commit + digest |
| D5 `basis-valid?` | D (installed) | Commit id + sym |
| D6 result cache | D (installed) | Content |
| D7 `::replaced-roots` by `Var/rev` | S | Physics: a stamp of the JVM's Var roots, the one place; same class as A1 |
| D8 counters | S | Justified counters; `!fallback-counts` and `fault/folded` go on the ledger or are deleted (outside this plan) |
| D9 `launched-source-digests` | S | Process record of what was launched; content digests |

### E. Connections, lifecycle, live objects

| # | Verdict | From / reason |
|---|---|---|
| E1 `on-branch` | S | One action's connection: live object |
| E2 borrowed connection | S | Registry |
| E3 one connection per branch per process | S | Datahike's one writer per branch: physics of the writer |
| E4 projection state on connection meta | D (later) | Projection is a function of the commit's schema revisions; it may be looked up by them instead of hung on the connection. Not phased here |
| E5 agent registry atom | S | AGENTS' registry |
| E6 `fork!`/`record-fork!` | D [P6] | Datahike `branch!` records the fork commit as parent; the fork entity shrinks to `{name target}` |
| E7 config apply per branch | S | Ruling 4: explicit write by root; config is branch data |
| E8 `seon.env` atom | S | Execution environment replaced at acquisition; live routing |
| E9 `branch-heads` local atom | X (trivial) | A `reduce`; outside the critical path |
| E10 flow proc state | S | Flow owns it |
| E11 namespace cursor volatile | S | REPL session state of one turn |
| E12 projection delays | S | Per-call memo of a pure function |

Totals: **D 26** (9 already installed), **X 9**, **S 23** (several rows are D/S; counted once by the main verdict).
Every **S** is either a live object, the writer, the JVM's Vars, or a per-call memo. None is a point in time kept by
hand.

## 3. Measured on default (JVM mode, read-only, 2026-10-15)

| Probe | ms | MB thread | Reading |
|---|---:|---:|---|
| `program-revisions` of main | 0.17 | 0 | lookup key is free |
| `program-context` main, first / again | 6.9 / 0.15 | 4 / 0 | content key `[store L=6ad07b08 plan-digest …]`, identical value |
| `program-context` (1-arity) of `:chain-f1f6dc8e6652` head, first / again | 3,404 / 0.19 | 2,641 / 0 | keyed on L = **6ad046be**, the chain's adoption-record copy, not the JVM's 6ad07b08: derives a base context for a program no Var holds |
| same, `:chain-e439df9798c6` | 2,597 / 0.17 | 1,961 / 0 | L = 6acf905a, likewise |
| program attributes at different revisions, main vs either chain | — | — | 170 of 170 |
| `program-changes` (the interpreted set) chain e439 vs the JVM's L, first / again | 357 / 0.19 | 653 / 0 | 253 held rows; request 1.3 GB |
| `program-changes` main vs L | 0.11 | 0 | 1 held row |
| `seon.cache` `::program-context` | load 4,874 ms over 9 misses | 43 MB held | ~540 ms per derivation |

Not run (they write or exceed 1.5 GB): an acquisition after a branch commit, a test world build. The SCI sharing
claim is already proven by `seon.sci.eval-test/equal-programs-on-two-branches-share-one-program-context`
(two branches with equal program, different eids: identical program context, no second miss). The agent path
supplies L from `::loaded-connection`, so the 3.4 s case is the 1-arity default only (test worlds call it on W,
whose record is L, so production pays it only where a record and L disagree). It is still the clearest proof that
L must be an explicit input.

## 4. Work in flight and landed

**Merge base and fork point.** Already the thesis: one reader (`seon.cluster.source/merge-base`, by the sorted
pair), one shared reader of graph records (ed9eb4f99). The two concepts stay distinct by design (phase 2b, root's
ruling): program entities three-way from the merge base, cluster facts from the fork point. Remaining:
`fork-point` reads full records (1.6 s / 1.4 GB cold for S5) and should read the graph record with `:datahike/branch`
that fork 1a149891 added; `retained-before` walks every branch per collection (8.8 s / 27 GB cold) and should use the
incremental rule (a non-merge child keeps its parent's base; replay-impact §6(b)), cached by head commit. P6.

**The merge step.** A task naming its branch (`:seon.task/branch`) is a checkout by data: correct under the model.
The kept merge branch is a branch whose head commit is the merge candidate; nothing about it is a point kept by hand.
MERGE-STEP's open question ("root reads the step's end where its agent writes it") resolves by the model's rule
*read a branch's facts on that branch*: option (1), one reader of a row's end on the branch its row names. Option (2)
(re-point `:seon.chain/branch`) moves a pointer by hand and is a second source of "which branch is the chain".
The kept merge branch's claim at boot (`cluster-claims`) must count branches named by `:seon.task/branch`: a
derivation from task facts, not a list.

**Phase 3 (index diff).** The central primitive of this plan. `datoms-diff` (771a0c4b) replaces: own change from a
t (B5/B6), the history scans (B10; `source/changed-identities`, `merge-accepted`, `task/changed-handles`: 0.22-0.55 s,
0.4-1 GB each), the interpreted set (P3 below), the reach attribute maps (C8) and the test world's overlay
(C5). It needs the fork bump in root's next restart batch. Replay-impact's verdict stands: no speed gain on
`own-changed-identities` (already 5-25 ms); the reason is deleting base-t.

**Incremental reach maps.** reach-maps.patch is the model applied correctly (attribute map at revision R derived
from the held map at P by `datoms-diff(P, R)`; `reach-owner`, `reach-changed-entities`, `retire-replaced-revision!`
deleted). Land it with the bump (P3). exp-c's `::reach-head` by store follows. merge-cost's larger finding
(behavior-digest misses, 3.7 of 5.3 GB cold) is a cache-weighing problem, not alignment; it stays with its owner.

**Overlays.** Stay (physics of writes over an immutable store). The fork batch made them share the backend's node
cache and stop copying reads. What changes: an overlay's starting point is always a commit named by content (W or a
request's `[commit tx-digest]`), never "the branch as it is now" (C4 opens the live branch head; fine because it
captures a commit, but name it).

**Adoption record and loaded program identity.** The adoption record stays on main as published history and boot's
input. `loaded-identity` stays as the one holder of L. Deleted: every other way to find L (`loaded-source`,
`::loaded-connection`, `world-base`'s fallbacks, `loaded-program`'s record read for a branch, `loaded-source-drift`).
A branch's own adoption record is a fork-time copy; ruling 2 already says the interpreted set is "a lookup between two
pointers": HEAD and L. P1.

**`:seon.agent/answered-t`.** Absent at HEAD (dig B14). The model says the answered basis is the agent's own
head commit at its answer; because the agent reads only its own branch, a t on that lineage is equivalent and
cheaper for `d/since`. Recommendation in Q3. It must never be compared with another branch's t: wakes are written by
the recipient on its own branch (TASK-CHAIN "Messaging"), so this holds by construction.

## 5. Phases

Each phase is one loadable commit (host-bound slices through `bin/lane commit … --test`). Each red proof is named; a
"new" deftest is written red in the phase's first commit. Costs: measured (m) or derived (d).

| # | Change | Red proof | Cost before → after | Deletes |
|---|---|---|---|---|
| P1 | **L is one value.** `seon.sci.eval/program-context` and `base-ctx` take L explicitly (`::loaded-database` required where the database is not L's own); one reader `seon.sci.eval/loaded-commit` of `loaded-identity`; `seon.test/clean-request-handle` uses it as W's base; `acquire!`/`branch-ctx` stop following `::loaded-connection` | new `seon.sci.eval-test/a-branch-program-context-is-built-on-the-loaded-commit-not-its-record` (a fork whose record names an older commit answers the content key with L, no second base derivation) | 1-arity on a chain: 2.6-3.4 s, 2.0-2.6 GB (m) → 0.2 ms hit (m, main's warm path) | `loaded-source`, `loaded-commit-id`'s record read for branches, `::loaded-connection` plumbing, `world-base`'s 3-way `cond`, `loaded-source-drift` and its 3 readers (≈ 70 lines, 4 fns) |
| P2 | **Own change by diff** (one-merge-base phase 3 rest): `seon.program/own-changed-identities` = `datoms-diff(merge-base, HEAD)` over program attribute ranges, identities read from entities, digest three-way | existing `seon.merge-forward-test/after-a-forward-merge-the-branch-changed-only-its-own-declarations` stays green; new `seon.program-test/own-change-reads-no-t` (a branch whose t range overlaps main's answers exactly its own identities) | 5-25 ms (m) → same (m, replay-impact §4); correctness: no t across lineages | `:seon.branch/base-t` reads in `changed-identities`, `entities-changed-after` (14 lines) |
| P3 | **Interpreted set and reach by diff.** `seon.sci.eval/overlay-plan` candidates from `datoms-diff(L, HEAD)`; land reach-maps.patch; `::reach-head` by store | `seon.sci.eval-test/an-overlay-plan-reads-the-branch-digests-by-index-range` extended with a `seon.profile/measure` row; `seon.test.reach-test/a-new-branchs-first-attribute-map-costs-what-it-changed` (in the patch) | plan: 357 ms / 653 MB (m) → ≈ 40 ms / 50 MB (d: claim 3's 20 ms / 27 MB diff + lookup); first reach on a new branch: 470 MB (m) → delta's closure (m 247 MB on a 53 % closure; ≈ 0 on a one-change branch, d) | `reach-owner`, `reach-changed-entities`, `retire-replaced-revision!` (30 lines); `held-base` place shortcut (A7) |
| P4 | **Change since a run by diff.** A run records the commit it tested (`:seon.test.run/commit`); `changed-since-green`, `select`'s `changes-since`, `program-written-since?` read `datoms-diff(tested, HEAD)`; `source/changed-identities`, `merge-accepted`, `task/changed-handles` diff their `before`/`after` | new `seon.test-test/a-run-on-a-branch-reuses-a-green-whose-commit-is-on-another-lineage` (green recorded on main, branch at equal content: unchanged, no t compared); `seon.profile/measure` on `source/merge-accepted` parent vs change | test-step `since`: 56-128 ms / 98-151 MB (m) → few ms (d); publish/merge scans 218-552 ms / 0.4-1 GB (m) → ≈ 15 ms / 20 MB (d) | `basis-revisions`, `commit-at-t`, `changed-attributes` t-arity (49 lines); `d/since`+`:eavt` scans (4 sites) |
| P5 | **Renders and the answered basis at a commit.** A turn opens at its head commit; history renders `commit-as-db` of it, not `as-of`; `call-reach-key` and `destructive-key` key revisions only; `branch-scope` atom becomes `seon.cache` kinds; `:seon.agent/answered-t` written by the recording transaction (Q3) | new `seon.render-test/two-branches-at-equal-content-render-once`; cold-block's `destructive-key` row as `seon.profile/measure` | `destructive-key` on as-of: 767-1,370 ms, 1.24 GB per call (m, cold-block) → cache hit (d) | `branch-scope` (27 lines), as-of arm of `call-reach-key`, `destructive-key`'s `[]` path |
| P6 | **Stored starts deleted** (one-merge-base phase 4 plus graph records): `fork-point` reads graph records with `:datahike/branch`; `retained-before` uses the incremental base; base refs, 125 `:base/*` branches, `:seon.task/merge-base`, fork entity → `{name target}` | ledger rows all converted; `seon.cluster.registry-test/an-idle-branch-keeps-the-commit-it-forked-from-through-collection` green; new `seon.profile/measure` on `retained-before` | fork-point cold 1.6 s / 1.4 GB (m) → tens of ms (d); collection pass 8.8 s / 27 GB (m) → ≈ 0.15 s (d) | `base-branch`, base-t attribute, `record-fork!`'s base-t, stored copies |
| P7 | **Test world by content.** W's program overlay = `datoms-diff(L, candidate)` → tx, computed once per `[L plan-digest]` in `seon.cache`; checkpoint key drops `process` if Q2 says so | new `seon.test-test/two-requests-at-equal-content-build-one-world-overlay` (second request: zero overlay derivations, by cache misses) | per request: program overlay read (unmeasured; measure first) → hit | `program-overlay-tx`'s `digest-differences` read path |

Order: P1 (no bump needed, removes the misaligned L), then the fork bump in root's restart batch, then P2+P3 (the
diff), P4, P5, P6, P7. P2 and P6 are the one-merge-base plan's phases 3 and 4; this plan changes their content only
by naming L and the run commit as the other two diff inputs. Every deletion lands in the commit converting its last
caller; HEAD boots after each.

## 6. The simplest alternative, and what disappears

**Considered: keep t, read it only on its own lineage.** Fix each cross-branch reader to translate a t through
`commit-at-t` on the right lineage. Rejected: it is per-reader patching (the 10-14 night pattern the owner refused),
each translation is a commit-record walk (0.55 ms and 1.2 MB per commit), and reach sharing and merge compare values
across branches by nature, so the translation never ends.

**Considered: revisions as the universal content key.** Free and already on every value. Rejected as the share key:
170 of 170 program revisions differ between main and a chain whose program differs by 24 identities. Kept as the
lookup key in front of digest keys, as `::program-key` does.

**Considered: content-addressed commits** (`:crypto-hash? true`). A commit id would hash content, but it also hashes
parents and meta, so equal content on two branches still gets two ids. Rejected: it buys tamper evidence, not sharing.

**Considered: one process overlay holding content-named world commits** (requests branch from W in it). Shares W's
nodes across requests, but its memory frontend never frees member writes without a GC of the overlay. Deferred to
Q1; P7 shares the derivation, not the store.

**What disappears** (from the sizes at HEAD): about 20 functions and ≈ 300 lines across `seon.db`,
`seon.sci.eval`, `seon.test`, `seon.test.runner`, `seon.render`, `seon.cluster.registry`; one stored attribute
(`:seon.branch/base-t`) plus `:seon.task/merge-base`; 125 stored branches; one atom (render cache scope); four
`d/since` whole-index scans. Added: one reader of L (≈ 10 lines), `seon.db/datoms-diff` (in reach-maps.patch),
`:seon.test.run/commit` (one attribute). No new cache kind beyond what P3/P7 reuse.

## 7. Open questions for the owner

**Q1. Where does a test world's commit live?**
- (a) **Recommended.** Per-request overlay as today, with the world's derivation (overlay tx, program context) shared
  by content in `seon.cache`. Guarantee: nothing durable grows; cost: each request still writes the overlay tx
  (small, in memory). Sacrifice: no node sharing of W across requests.
- (b) One process-lived overlay store per physical store; W commits named by `[L plan-digest config-digest]` as
  branches in it; requests branch from W. Guarantee: W written once per content. Cost: an overlay GC to free member
  writes (none exists; Datahike GC over a memory frontend is unproven). Sacrifice: a new lifecycle to own.
- (c) Durable W branches in the store (like checkpoints). Guarantee: survives restart. Cost: storage growth per
  content, retention by `retire-unclaimed!`. Sacrifice: the owner's "don't cause storage growth".

**Q2. Should a checkpoint's key include the process?** Today `checkpoint-<digest [base cluster effective process]>`.
- (a) **Recommended.** Drop `process` if `seed-cluster-facts!` writes it only as provenance: worlds then outlive a
  restart's reuse. Cost: verify what reads the process fact in a world (one grep and a test).
- (b) Keep: every restart rebuilds checkpoints (one per config digest; ≈ 100 ms each, unmeasured). Cost: nothing new.

**Q3. The answered basis: t or commit?** (AGENTS names `:seon.agent/answered-t`.)
- (a) **Recommended.** A t, written by the recording transaction, with the law "a t is read only on its own
  lineage" added to AGENTS. Guarantee: `d/since` works directly; the agent reads only its own branch. Cost: none.
- (b) A commit id (`:seon.agent/answered-commit`). Guarantee: comparable everywhere. Cost: a vocabulary change and a
  record read to turn it back into a `d/since` bound (0.7 ms).

**Q4. MERGE-STEP's reader of the step's end.**
- (a) **Recommended.** One reader of a row's end on the branch its row names (`chain-read`, `chain-finishes`,
  `chain-attempts` call it). Guarantee: facts read where written. Cost: ≈ 20 lines, three callers.
- (b) Re-point `:seon.chain/branch` to the merge branch on refusal. Cost: none in readers. Sacrifice: a second
  "which branch is the chain" moved by hand.

## Verification limits

- No acquisition after a commit and no test world build were run (they write); their costs in P3/P7 are derived or
  marked unmeasured. The 3.4 s case is the 1-arity default, not the agent's path; its production frequency is
  unmeasured.
- The probe request that measured both chains allocated 9.3 GB request-level (above the stop line; read-only, once).
- Derived after-costs rest on one-merge-base claim 3 and replay-impact §4, not on this lane's own prototype.
- Row counts per verdict collapse D/S rows to their main verdict.

## Summary

1. The thesis holds: every row that hurt keeps a point in time by hand (a stored t, a record copy, a place-keyed cache).
2. A t is a coordinate within one lineage; it may be stored only where every reader is on that lineage.
3. Revisions are the lookup key, digests the share key: 170/170 revisions differ main vs chain for 24 changed identities.
4. The JVM's loaded commit L is the one physics-imposed place; it must be one value passed to every derivation.
5. Measured: a branch's 1-arity program context keys on the branch's record copy, costing 2.6-3.4 s and 2-2.6 GB.
6. The interpreted set (ruling 2) costs 357 ms / 653 MB cold today; `datoms-diff(L, HEAD)` should make it ~40 ms.
7. `datoms-diff` is the one primitive: own change, history scans, reach maps, interpreted set, world overlay.
8. Of 58 rows: 26 derivations (9 installed), 9 deletions, 23 stay (live objects, the writer, the JVM's Vars, memos).
9. Seven phases, P1 first (no bump), then the fork bump batch; about 20 functions and 300 lines go.
10. Four owner questions: world placement, checkpoint process key, answered basis form, merge-step reader.
