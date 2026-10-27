# Audit 2 (main 8517a97d6 vs previous audit base b80738d74)

Scope: 18 commits, 5 merge commits (3 real merges plus 2 "merge main into branch"). src, bin, resources/seon/config: no change (0). Net diff in src/test/resources/bin: 4 files, +19/-7: test/seon/check.clj (+12/-4), three task .edn files (text).

## Merges
1. 8517a97d6 jolt/a-killed-root-recovers-main-in-the-durable-read-check (025b1c05a, 2e13680c0): WORTH IT. Fixes a red (durable-read check counted root's :seon.agent/root links, which a new boot retracts). test/seon/check.clj:685-750: main-data-datoms leaves :seon.agent/root out, and the check now asserts `linked` is zero (:earlier-agents-are-not-this-roots), so the exclusion is itself proven rather than silent. src delta 0, no new key/ns/attr. Note: this fixes at the reader (the count), but the links are facts the boot owns; the assertion makes it defensible. No measured value, none needed. Caveat: sits on a plan item the Vocab row calls "durable-read" red; sibling branches (durable-read-runs-alone, +1, 2151c65bf) carry the other half (bin/check durable as one mode) and are not merged.
2. fdecd074e jolt/viewer: docs/notes only (agent-view hand-over, notes archived). WORTH IT as hand-over for THE PLAN step 3; it is a note, not a src change. Minor: a ~hand-over file plus restart.md are working papers; fine if archived when step 3 merges.
3. 19bc91c63 jolt/ticks-are-exact-2 (b4297a71e, 28ba69834, f3afe0e6e): QUESTIONABLE (low cost). Task text only (seon.cost.edn, seon.db.edn): three commits recording a time-boxed hunt, ending "not the collect-request handler; next: timer chunk and Chez's event entry". It moves no THE PLAN step (plan lists ticks-are-exact as "allowed for"), has no code, no number gate. Cut: nothing to revert; but stop the hunt: the previous audit's correction 5 (choose repeats or quantum) would remove the cause of the symptom. 
4. 221857750, dfd8f21e0: merges of main into branches; no content of their own.
Direct-to-main commits: 8958ce797 (audit note), 9b5753a17 (plan), 4da014a75, 9347fae13, 3ffd30317 restart.md, eda0a2009 merge-log: docs only. Fine. restart.md prompt is long-lived process text; keep to one home (AGENTS §7).

Overall: the period was almost idle on src (0 growth). Nothing overbuilt. No §9 violation found in the diffs.

## Previous audit's 5 corrections (status on main and all 41 jolt/* branches; grep of branch-only commit subjects/bodies)
1. seon.render/shown: render only the HTML face, delete render/data & rendered: NOT STARTED. src/seon/render.clj still has data (:376), shown (:391), rendered (:406). No branch commit mentions it.
2. wiki baseline (git blame, SEON_WIKI_BASELINE, Reviewed trailers): NOT STARTED. Env switch still in src/test on main; no branch commit.
3. vector search (128-dim pass, rerank, C kernel, RRF): NOT STARTED. rrf still in src/seon/context.clj (5 hits) per grep; jolt/a-search-allocates-in-proportion is merged (0 ahead) and predates the audit.
4. seon.judge / my.root/first-moves!: NOT STARTED, and moving the other way: seon.judge is still src/seon/judge.clj (+test). Branches jolt/acts-by-second-move (+18), jolt/evaluations (+15), eval-record-keeps-a-failing-form-whole (+8), examples (+8) all EXTEND the judge (standard-request, pieces, :repl-calls, a ref to run on main, a one-run-loop review). Closest to the correction: acts-by-second-move commit 661dfb064 "a run's request is made by act! from the one basis it records", i.e. it moves toward a real run through the turn path; not merged, not a move to test/. Needs a ruling before that branch merges.
5. tick-quantum vs repeats (choose one; config keys): NOT STARTED. tick-quantum still in src/seon/cost.clj (2 hits); jolt/ticks-are-exact-2 merged only research text; no branch changes it.

Also open (not audited, unmerged branches with commits ahead): one-content-keyed-derivation +14 (seon.derive memo, task waits on data context per plan: watch for growth), wiki +13 (old, 10-21 branch, stale), evaluations +15, acts-by-second-move +18, self-contained +8, examples +8, eval-record +8 (these share commits with evaluations/acts: overlapping work, merge order matters), unitflake +5 (10-21, stale).
Recommended: root assigns corrections 1,2,3,5 (about -420 src net, per previous audit) before merging acts-by-second-move/evaluations, which add to the judge.
