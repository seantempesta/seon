---
type: research
status: proposed (lane git-vocab, 2026-10-08)
tags: [vocabulary, merge, branch]
---
# Git vocabulary for branches and merges

What exists: an agent works on a Datahike branch (`my.agent/create-branch!` src/my/agent.clj:117), proposes it
(`my.program/propose-merge!` src/my/program.clj:416), and root prepares a test merge on a fresh branch
(`prepare-merge!` program.clj:513) and accepts it onto the branch root's context holds (`accept-merge!`
program.clj:547). The names come from several eras: "lane branch", "unlink", "retire", "proposal", "candidate",
"cluster head", "decide", and the single letter S. The approach here: Datahike already speaks git (`branch!`,
`delete-branch!`, `merge!`, `commit-id`, `parent-commit-ids`, reference-code/datahike/src/datahike/versioning.cljc
at e5453d96, lines 212, 279, 738, 461, 467), so Seon takes git's words where git's meaning holds, Datahike's where
the concept is a database one (norms), and says plainly where git would mislead.

Owner, 2026-10-08: "can we base the concepts on git so all agents will have an immediate understanding with the
vocab and verbs / functions / data expectations?" and "Please stop using all these acronyms and use real clojure
library names and concepts." The ruling is recorded in docs/research/agent-platform/2026-10-08/review/owner-rulings.md
("Git vocabulary").

## 1. The one picture

| Git | Seon |
|---|---|
| repository (the object database with branches and commits) | the store: one Datahike store, many branches (`:seon.store/store`) |
| `main` | a cluster's main branch, today `:cluster-<name>` (`seon.cluster.branch/cluster-branch` src/seon/cluster/branch.clj:8) |
| the branch you have checked out (`HEAD`) | the branch an agent's context holds (its custody) |
| a feature branch | an agent's branch |
| a commit, with parents | a Datahike commit (`commit-id`, `parent-commit-ids`) |
| a merge request on a forge | a merge task with its proposal |
| the forge's test merge (`refs/merge-requests/N/merge`) | the merge branch root's prepare creates |
| the maintainer who presses Merge | root |
| required checks | the task's proofs |

Git has no counterpart for data: the store also holds facts that are not program (messages, evaluations, the
agent's own rows). Those never merge. Section 4 explains what happens when program and data disagree.

## 2. The vocabulary map

Each row: today's names, the git term and why it fits, where git's meaning differs, and the Clojure names.

### Nouns

**Branch.**
- Today: "lane branch" (my/agent.clj:88 section heading, docstrings of `create-branch!` and `retire-branch!`),
  "an agent's branch", "agent's own branch", `:seon.agent/branch`, `:seon.store/branch`.
- Git: branch. Exact: a named pointer to a commit, cheap, no copy (Datahike `branch!` versioning.cljc:212).
- Differs: nothing that misleads.
- Names: drop "lane"; say "branch". Keys stay `:seon.store/branch` and `:seon.agent/branch`.

**Main.**
- Today: "the cluster head", "the cluster's own branch", "the cluster's shared branch", "home", `cluster-branch`.
- Git: `main`. A cluster's branch is the line every accepted change lands on.
- Differs: a store holds several clusters, so several mains; the branch name stays `:cluster-<name>` because names
  are store-wide. Do not say "HEAD" for it: in git, HEAD is whatever you have checked out, and an agent on its own
  branch has its own branch checked out. "Head" today means two things (the cluster head, and in the three-way value
  the main side); both become "main".
- Names: `seon.cluster.branch/cluster-branch` → `main-branch`. Prose: "main", "the tip of main" for its latest commit.

**Checked-out branch.**
- Today: "custody", "the branch my context holds", `my.agent/branch` returning `{:seon.agent/branch :seon.agent/mode}`.
- Git: the checked-out branch, HEAD; `git branch --show-current`.
- Differs: an agent never switches its checkout inside one context; a new checkout is a new context. The mode
  (`:live` or `:isolated`) only says whether the checkout is main, which `(= branch main)` derives.
- Names: `my.agent/branch` stays (it is `git branch --show-current`). Agent-facing text says "checked out";
  `custody` stays as the internal word in `seon.db/call-with-custody`.

**Commit.**
- Today: commit id, `:seon.task/commit`, `:seon.source/commit-id`, basis.
- Git: commit. Datahike commits are immutable, identified by uuid, and record parents; a merge commit has several.
- Differs: a Seon commit holds data and program together. The program part has its own content identity,
  `:seon.test.run/program-digest`, which is what git calls a tree id (same content, same id, whatever the history).
- Names: keep `commit-id`. `:seon.task/commit` → `:seon.merge/source-commit`. `:seon.task/program` →
  `:seon.merge/program-digest`.

**Merge request.**
- Today: "proposal", "merge task", "the proposal row", `:seon.task/proposal`, `:seon.task/merge`,
  `:seon.task/kind :merge`, `seon.task/propose!`, `proposals`.
- Git: a merge request (GitLab) or pull request (GitHub); `git request-pull` is git's own command for asking.
  "Merge request" is chosen over "pull request" because Seon's verb is merge and it has no pull or fetch.
- Differs: on a forge one merge request receives many pushes. Seon writes one proposal row per proposed commit
  beside the merge task. The git-shaped model is one merge request whose source commit moves with each new request;
  Datahike history answers the earlier versions, so the proposal row disappears.
- Names: the merge task is the merge request. Its data, on the task entity: `:seon.merge/source-branch`,
  `:seon.merge/source-commit`, `:seon.merge/target-branch`, `:seon.merge/proofs` (each proof with its definition
  digest, today `:seon.task/proved`), `:seon.merge/run` (today `:seon.task/run-id`), `:seon.merge/program-digest`.
  `:seon.task/kind :merge` is deleted: under the owner's "no kinds" ruling a task is a merge request because it has
  `:seon.merge/source-branch`, validated by a specific Malli schema.

**Source branch and target branch.**
- Today: "the proposing branch" (`:seon.task/branch`), "the branch my context holds" for where accept lands, no
  stored target.
- Git: GitLab's `source_branch` and `target_branch`.
- Differs: GitHub calls them head and base. Both words already mean something else here: "head" was main's tip, and
  "base" is the merge base of a three-way merge. GitLab's pair has no such clash.
- Names: `:seon.merge/source-branch`, `:seon.merge/target-branch`.

**Merge base.**
- Today: `merge-base` (src/seon/cluster/source.clj:727), "base" in the three-way value.
- Git: merge base (`git merge-base`). Exact: the newest common ancestor commit.
- Names: keep `merge-base`; in data `:seon.merge/merge-base`.

**Merge branch.**
- Today: S, "the candidate", "the tested branch", `:seon.source/candidate`, `candidate-gate!`
  (src/seon/cluster.clj:2642).
- Git: a merge in progress. On a forge it is the test merge ref, where checks run on the merged result before
  anyone presses Merge.
- Differs: git keeps a merge in progress in the working tree of the checked-out branch; Seon puts it on its own
  branch off the target. That is better (main is untouched until merge), but call it a branch, not a working tree.
- Names: "the merge branch". `:seon.source/candidate` → `:seon.merge/merge-branch`. `candidate-gate!` →
  `test-merge-branch!`. Never S.

**Conflict, ours, theirs.**
- Today: `:seon.program/conflict` with `:seon.program/base-digest`, `:branch-digest`, `:head-digest`
  (src/seon/program.cljc:704-748); spec prose "branch" and "head".
- Git: a conflict; during `git merge`, "ours" is the branch merged into (the target, checked out) and "theirs" is
  the branch being merged (the source). `git checkout --ours/--theirs` and `git merge-file` use these names.
- Differs: in `git rebase` the two words swap. Seon never rebases (commits are never rewritten), so the merge meaning
  is the only one; every docstring says "ours is the target, as in `git merge`".
- Names: conflict entries `{:seon.merge/identity [:seon.fn/sym a/g] :seon.merge/base "d0…" :seon.merge/ours "d6…"
  :seon.merge/theirs "d2…"}`. The three-way classification keys become `:seon.program/added`,
  `:seon.program/modified` (today `changed-on-branch`), `:seon.program/deleted` (today `retracted`),
  `:seon.program/modified-ours` (today `changed-on-head`), `:seon.program/conflict`, `:seon.program/unchanged`.

**Unmerged.**
- Today: `:seon.task/unresolved` (target, unbuilt), "conflict stays on the merge branch".
- Git: unmerged paths, the heading `git status` prints during a conflicted merge.
- Differs: git writes conflict markers into the file; Seon keeps main's declaration on the merge branch and lists
  the identity as unmerged. Saying "conflict markers" would send an agent looking for `<<<<<<<` in source that is
  not there. Git needs `git add` to mark a path resolved; in Seon any write to that identity on the merge branch
  (a checkout or a `defn`) resolves it.
- Names: `:seon.merge/unmerged`, a set of identities.

**Behind.**
- Today: `:seon.merge/head-only` (spec target), "changed on the target since the fork", `:seon.program/changed-on-head`.
- Git: "your branch is behind main by N commits"; `git diff branch...main`.
- Names: `:seon.merge/behind`, the identities main modified since the merge base.

### Verbs

| Today | Git | Clojure name | Fit and difference |
|---|---|---|---|
| `my.agent/create-branch!` | `git branch <name> <start-point>` | `my.agent/branch!` | Datahike's own name (`branch!` versioning.cljc:212). Keeps `:seon.cluster.registry/from`, git's start point. |
| `my.agent/branches` | `git branch --list` | `my.agent/branches` | Keep. Result keys `:my.agent/own-branch` → `:my.agent/current-branch`, `:my.agent/roster` → `:my.agent/branches`. |
| `my.agent/branch` | `git branch --show-current` | `my.agent/branch` | Keep. |
| `my.agent/retire-branch!`, `seon.cluster.registry/retire-branch!`, "unlink" | `git branch -d` | `my.agent/delete-branch!`, `seon.cluster.registry/delete-branch!` | Datahike's name (`delete-branch!` versioning.cljc:279). Same lifetime as git: the commits stay until garbage collection. Git refuses to delete a checked-out branch; Seon refuses a branch an agent still holds, the same rule. Difference: Seon first retires the agent hosted on the branch, like `git worktree remove` before `git branch -d`. "Retire" stays the word for agents only. |
| `propose-merge!`, `seon.task/propose!` | `git request-pull`; open a merge request | `my.program/request-merge!`, `seon.task/request-merge!` | Opening again with a new commit updates the same merge request, as a push does. |
| `my.program/proposals` | the forge's list of open merge requests | `my.program/merge-requests` | |
| `my.program/diff` | `git diff main...branch` (three dots: since the merge base) | `my.program/diff` | Keep. Takes `{:seon.merge/request m}` for root's view of any request. |
| `my.program/declaration-diff` | `git diff main...branch -- <path>` | `my.program/diff` with a symbol | Folded into `diff`: a symbol argument is git's pathspec. One name fewer. |
| `prepare-merge!` | `git merge --no-commit` | `my.program/merge!` with `:seon.merge/no-commit true` | See below. |
| `accept-merge!` | `git merge --continue` (commit the merge) | `my.program/merge!` | See below. |
| none | `git merge --abort` | `my.program/abort-merge!` | Deletes the merge branch; the merge request stays open. |
| `seon.task/decide! … :declined`, target `decline!` | close a merge request | `my.program/close-merge-request!` | Takes `:seon.merge/reason`; messages the source branch's agent. |
| target `take!` / `drop!` | `git checkout --theirs -- <path>` / `--ours` | `my.program/checkout!` with `:seon.merge/side` `:theirs` or `:ours` and `:seon.program/identities` | Not cherry-pick: cherry-pick copies a commit, and Seon copies one identity's declaration, which is what `checkout -- <path>` does. Works on any identity, not only conflicted ones, like `git checkout <branch> -- <path>`. |
| re-prepare after main moved | GitHub's "Update branch" (merge main into the source), not rebase | calling `merge!` again | Rebase rewrites the source's commits onto a new base; Seon never rewrites commits. Re-merging main's new tip into the kept merge branch keeps root's repairs, which is a merge, so the verb stays merge. |
| `write-back!` | none exact | `my.program/write-back!` | Keep. It writes the merged declarations into source files after checking the files did not change since read, the same guard as a push that refuses a non-fast-forward. Calling it push would suggest a second repository with history; the files have none. |
| `bin/seon reset` | `git reset --hard` to the committed files | keep | Close enough: it rebuilds a cluster from the program in files and discards the rest. |

**One merge verb.** Git's `git merge <branch>` either finishes (a clean merge commits) or stops with conflicts for
you to resolve, then `git merge --continue` commits and `git merge --abort` gives up. The owner ruled
auto-accept when every test on the merge branch is green (owner-rulings.md, "Approved … afternoon"). Together these
make one verb:

```clojure
(my.program/merge! {:seon.merge/request m})
;; clean and every test green  -> merged: the merge commit on the target, the request closed as merged
;; conflicts, red tests or a held criterion -> stopped: the merge branch kept, its :seon.merge/unmerged,
;;   the red tests, and the forms to run next (diff, checkout!, merge!, abort-merge!)
(my.program/merge! {:seon.merge/request m :seon.merge/no-commit true})   ; stop before committing, to review
(my.program/merge! {:seon.merge/request m})                               ; again: continue the stopped merge
(my.program/abort-merge! {:seon.merge/request m})
```

This deletes three names (`prepare-merge!`, `accept-merge!`, the planned `review!`): the review proc of the redo
note calls `merge!`, which already applies the policy. Root's extra tests ride on the same call
(`:seon.test/identities`).

**Merge commit parents.** Today's accept writes three parents: main's tip, the merge branch and the source commit.
Git calls a merge with more than two parents an octopus merge; it is valid and stays.

## 3. Accept targets, in plain words

**The question.** When root accepts, which branch receives the change?

**Today.** `accept-merge!` lands on whatever branch root's own context has checked out (program.clj:547: "The merge
lands on the branch my context holds"). For root working on main, that is main. But the dogfood (spec merge.md §9
item 4) had a reviewer agent on a scratch branch accept a request: the change landed on the scratch branch, and the
merge request at main was still marked accepted, as if main had it. Nothing recorded where it went.

**Git's answer.** On the command line, `git merge` merges into the branch you have checked out, which is today's
behaviour. On a forge, a merge request names its target branch when it is opened, and pressing Merge always merges
into that branch, whoever presses it and whatever they have checked out. A merge request is "merged" only when its
target received it.

**Proposal (the forge's rule).**
- `request-merge!` records `:seon.merge/target-branch`: by default the main the source branch was created from
  (`branch!` already knows its start point), or a branch the agent names.
- `merge!` always lands on the request's target branch, never on root's checkout.
- To rehearse a merge without touching main, an agent branches from a scratch branch; its requests then target that
  scratch branch, and merging them is real and honest. The `:seon.merge/no-commit` option covers "show me the
  merged result first".
- What disappears: the "reviewer branch" ambiguity, the target `:seon.task/target` key the spec planned, and the
  false "accepted" record.

## 4. Migrations, in plain words

**The problem.** A branch carries program (functions, tests, schemas). It never carries data. Suppose an agent
splits the attribute `:user/name` into `:user/first` and `:user/last`, and rewrites the functions to read the two new
keys. On its branch it fixes its own few users and its tests pass. Main, meanwhile, holds two thousand users stored
the old way. Merging the program gives main new functions that read `:user/first` and old data that only has
`:user/name`: the code is right and the data is wrong. A migration is the function that rewrites main's existing
data into the new shape, run when the merge lands.

**Git has nothing here.** Git stores files only. The everyday comparison is a pull request in a web application
that ships a database migration file, which runs on deploy.

**The Clojure library concept: Datahike's norms.** Datahike already has this
(reference-code/datahike/src/datahike/norm/norm.clj at e5453d96). A norm is a named change to a database:
`{:norm :some/name :tx-data [...] :tx-fn some.ns/f}`, where `tx-fn` is a function returning transaction data.
`ensure-norms!` (norm.clj:204) runs each norm not yet applied and records its name on the transaction as `:tx/norm`
(norm.clj:142-158), so it never runs twice on the same database (`norm-installed?`, norm.clj:31). Datomic's
conformity library has the same idea under the same name.

**Proposal (a norm carried by the merge request).**
- A norm is an ordinary contracted function, `[:=> [:cat :seon.db/database-value] :seon.db/tx-data]`: pure, it reads
  a database value and returns the transaction data that brings it to the new shape. It is program, so it merges like
  any `defn`.
- The agent names it: `(my.program/request-merge! {:seon.merge/norms #{app.user/split-name}})`. The agent runs the
  same function on its own branch, so its proofs test it on real rows.
- `merge!` runs each named norm on the merge branch before the tests (so the tests and schema checks see migrated
  data), and again inside the merge transaction on main, against main as it is at that moment, so rows written to main
  after the test are migrated too.
- The merge transaction records each norm's symbol and definition digest, and `merge!` skips a norm main already
  records: Datahike's never-twice rule.
- Why not call `ensure-norms!` itself: it reads norms from EDN resource files and transacts each one separately
  (norm.clj:142-158). The merge must commit program and migrated data in one transaction, so main never holds the new
  functions beside unmigrated data. Seon takes the concept, its name and its rule, not that function.
- Names: `:seon.task/migrations` (the redo note's target, unbuilt) → `:seon.merge/norms`. Prose: "norm", with
  "migration" as the plain-language gloss.

## 5. Rename slices

Each slice converts every caller, quoted symbol, schema reference, error operation, help text, living document and
test in one commit, and retires the old name; nothing aliases it. Living documents are CLAUDE.md, AGENTS.md,
`docs/seon/specs`, `docs/seon/architecture`, `docs/seon/schedule` and `.agents/skills`. Dated research notes are
history and keep their words. Each commit runs `bin/lane commit` with the tests named.

1. **Vocabulary and acronyms in the living documents.** No code.
   - `docs/seon/architecture/vocabulary.md`: rows for branch, main, checked-out branch, merge request, source and
     target branch, merge base, merge branch, conflict with ours and theirs, unmerged, behind, norm, delete branch;
     "unlink" moves to the legacy column of delete branch; the `my.branch` target row is deleted (the verbs stay in
     `my.agent` and `my.program`).
   - `docs/seon/specs/merge.md` and the redo note: S becomes "the merge branch"; slice ids become their proof test
     names.
   - CLAUDE.md "Merge is a root task" and "Clusters and branches": root edits these (instruction file).
   - Test: `seon.contracts-compile-test`.
2. **Branch verbs in `my.agent`.** `create-branch!` → `branch!`, `retire-branch!` → `delete-branch!`;
   `:my.agent/retired?` and `:retiring?` → `:my.agent/deleted?` and `:deleting?`; `:my.agent/own-branch` →
   `:current-branch`, `:my.agent/roster` → `:branches`; schemas `:my.agent/created-branch` and
   `:my.agent/retired-branch` renamed to match. Callers: src/my/agent.clj, src/seon/mcp.clj:75-77 and :197 (the help
   text and the first-use branch), src/my/program.clj accept's `requiring-resolve`, src/seon/cluster/agent.clj,
   resources/seon/tasks/my.agent.edn, test/my/agent_test.clj (its two branch deftests renamed), the help pages.
   Tests: `my.agent-test`, `seon.cluster.host-test`.
3. **Delete branch and main in the platform.** `seon.cluster.registry/retire-branch!` → `delete-branch!` with
   `::cannot-retire-main` → `::cannot-delete-main`; `seon.cluster.branch/cluster-branch` → `main-branch`; "unlink" in
   docstrings → delete. About ten source files each, the registry and host tests. Tests: `seon.cluster.registry-test`,
   `seon.cluster.host-test`, `seon.cluster.release-context-test`. Collides with slice 2 on src/my/program.clj; run
   after it.
4. **Merge request functions.** `propose-merge!` → `request-merge!`, `proposals` → `merge-requests`,
   `seon.task/propose!` → `seon.task/request-merge!`, `declaration-diff` folded into `diff`; the message text says
   "merge request". Callers: src/my/program.clj, src/seon/task.clj, src/seon/mcp.clj help,
   resources/seon/schemas/seon.task.edn descriptions, resources/seon/tasks/my.program.edn, test/my/program_test.clj,
   test/seon/cluster/merge_test.clj. Tests: `my.program-test`, `seon.cluster.merge-test`.
5. **Merge request data.** The proposal row folds into the merge request: `:seon.merge/source-branch`,
   `:source-commit`, `:target-branch`, `:proofs`, `:run`, `:program-digest`; `:seon.task/kind :merge` deleted; the
   outcome enum becomes positive data: `:seon.merge/merge-commit` present means merged,
   `:seon.merge/close-reason` present means closed, neither means open. A schema resource change: proven by publishing
   and adopting it on a branch of the live store. Opus lane. Tests: `my.program-test`, `seon.cluster.merge-test`,
   `seon.task-ingestion-test`.
6. **The diff value.** Sections `:seon.merge/added`, `:modified`, `:deleted`, `:conflicts`, `:behind`; conflict
   entries with `:base`, `:ours`, `:theirs`; the three-way keys in src/seon/program.cljc renamed as in §2. Callers:
   program.cljc, cluster/source.clj, my/program.clj, the `:seon.program/three-way` schema. Test:
   `my.program-test/diff-names-what-the-branch-added-changed-and-removed-against-its-cluster`, renamed
   `diff-names-what-the-branch-added-modified-and-deleted-since-the-merge-base`.

Born with these names (the redo note's slices, not renames; each lands under its own proof):
- the kept merge branch with `:seon.merge/merge-branch` and `:seon.merge/unmerged`, and `prepare-merge!` plus
  `accept-merge!` collapsing into `merge!`, `:seon.merge/no-commit` and `abort-merge!`
  (proof `seon.cluster.source-test/a-conflict-stays-on-the-merge-branch`); this is a change to the one merge path,
  so an Opus lane;
- `checkout!` with `:seon.merge/side` (proof `my.program-test/take-and-drop-move-one-identity`, renamed
  `checkout-ours-and-theirs-move-one-identity`);
- `:seon.merge/target-branch` honoured by `merge!` (§3);
- `close-merge-request!` with a reason (proof `my.program-test/a-declined-merge-tells-its-agent`, renamed
  `a-closed-merge-request-tells-its-agent`);
- `:seon.merge/norms` (§4; proof `seon.cluster.source-test/a-split-attribute-migrates-head-rows-written-after-prepare`,
  renamed `a-norm-migrates-main-rows-written-after-the-test-merge`).

Order: 1 first (the table is what the code slices cite), then 2, 3, 4 in that order (they share
src/my/program.clj), then 5 and 6 (file-disjoint from each other except program.clj; serialize), then the born slices
as the redo plan orders them.

## 6. Owner questions

1. **Merge request or pull request?** Recommend merge request: Seon's verb is merge, it has no pull, and GitLab's
   source and target branch avoid the head and base clash. Pull request is the more common word in training data.
2. **One `merge!` verb, auto-committing when green?** Recommend yes: it is `git merge`, it applies the approved
   auto-accept ruling, and it deletes three names. `:seon.merge/no-commit` keeps root's look-first review.
3. **Does a merge always land on the request's target branch?** Recommend yes (§3). The alternative, today's "merge
   into whatever root has checked out", needs a stored target and leaves the request's state ambiguous.
4. **Migrations named "norms"?** Recommend yes: it is Datahike's and conformity's word, so agents who know either
   library recognise it; "migration" stays as the gloss in prose.
