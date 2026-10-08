# CLAUDE.md patch for GV-1 (root applies; AGENTS.md is the same file by symlink)

Two bullets in "The system: one JVM, many realities". Replace each in place.

## Clusters and branches

Old (CLAUDE.md lines 174-176):

> A cluster is a Datahike branch plus its agents and shared plumbing; one JVM hosts many. A branch is `d/branch!`, a
> pointer to shared immutable indexes, never a store copy or a system to boot. **Unlink** removes a branch from the
> roster; retention GC reclaims it without per-branch cleanup.

New:

> A cluster is a Datahike branch, called main, plus its agents and shared plumbing; one JVM hosts many. A branch is
> `d/branch!`, a pointer to shared immutable indexes, never a store copy or a system to boot. An agent's context has
> one branch checked out. `d/delete-branch!` removes a branch from the roster; retention GC reclaims its commits
> without per-branch cleanup.

## Merge is a root task

Old (CLAUDE.md lines 200-207):

> An agent proposes only after the tests naming its task pass on its branch; the proposal becomes a merge task. Root
> works it through the ordinary REPL holding both worlds — a merge branch forked from the cluster head and a copy of
> the agent's world — ... only root's acceptance advances the cluster pointer, and the agent's branch retires when its
> merge closes.

New (same sentences, git words):

> An agent requests a merge only after the proofs naming its task pass on its source branch; the merge request is a
> task. Root works it through the ordinary REPL holding both worlds — a merge branch forked from the target branch
> (main) and a copy of the agent's world — with easy tools to move data between them, and chooses exactly what to take
> by identity from the diff value (the diff is since the merge base; ours is the target, theirs the source); the task
> never lists what to merge. Merging requires the task's proofs to pass on the combined program plus whatever root
> chose to run, recorded as evidence; reaching-test counts are shown, not gated. Conflicts stay unmerged on the merge
> branch for repair. The writer checks the touched entities' digests; only root's merge advances main, and the agent's
> branch is deleted when its merge request closes. Local green never implies shared acceptance.

Note: code still says `propose-merge!`, `accept-merge!` and `retire-branch!` until git-vocab rename slices 2-4 land
(docs/research/agent-platform/2026-10-08/git-vocab/git-vocab.md section 5).
