# Review of "Merge audit and design, 2026-10-27" (cold, read-only)

Verified about a dozen claims against main at 0a1dca7b77. These hold: bin/env:30-63 (lock), reconcile.clj:409-463 (judge, gate, octopus), my/root.clj:67-118 (gate!, task-files applies with-merge to every file task), writer.clj:109-150 (octopus), default.edn:332 vs config/local.edn:13, refresh-tx (source.clj:365), git-commit takes {path text} (fx.clj:272), the :attach/:done door (server.clj:71-91, ~158-180), 301 first-parent merges, 14 "Seon merge:" commits, merge-log 191 lines / 22 --merge lines.

## 1. Claims that are wrong or overstated
- **"1 is in main's history":** `git log main --grep='^Seon merge:'` gives 0. All 14 are only on jolt/seon-merges.
- **"merge-keys / octopus are dead under max-running 1":** wrong. `:seon.config.merge/max-running` caps only `:merge`-kind starts (reconcile.clj:466-477, `merge?` tests kind `:merge`). The judge collects every `:ready-to-merge`, `:whole` included (task.clj:35-40), into one octopus (reconcile.clj:449-456). So with whole tasks, the default now, several ready runs **do** merge as one multi-member octopus. Cutting it to one branch is a change of behaviour. It is not removal of dead code, and the design should say so.
- **The default.edn:332 "inconsistency" mechanism:** default.edn:277-283 says that with `source/root` unset (tests, check stores) "nothing is written". The jolt/seon-merges commits must come from bin/root roots in worktrees (bin/root:28 sets SEON_SOURCE_ROOT, and a worktree's local.edn names no branch), writing into the shared git common dir. The fix is the same, but the cause as stated is wrong.
- **"gate-checkout: the standing root's local.edn names its merges tree"** (default.edn:285-292) is false: no local.edn or bin script sets it. On the standing root the gate has never been able to run. That strengthens the case for deleting it.
- **"Two verdicts" leaves out a third runner.** `seon.prove` (prove.clj:1-25, `prove-proc`, `seon_prove`, `seon.agent/prove!` agent.clj:618) is "the one test service". It runs tests in a forked agent from the store's program and records the results as facts. The design names the `:tests` effect (shell `bin/prove` in a detached worktree) as "the one verdict" and never says what happens to seon.prove. Also, `test-plan` already reads **both** result stores (agent.clj:~580: `proofs/greens dir` merged with `proofs/stored-results db`), so the "two result stores" duplication is half closed already.

## 2. Mechanisms kept or added but not counted
- **Two test runners stay:** seon.prove (in-store, forked) and `:tests` → `bin/prove` (on files). The design picks the shell one for the verdict and leaves the service in place. That is the "second system" the owner ruled out. One of the two has to go, or be shown to be the other's caller.
- **New effect shape:** `:tests` changes from "named tests" (fx.clj:425, `test-command` takes nss) to "the whole selection plus contracts plus smoke plus holding a flock". That is a new contract and a blocking lock inside an fx thread. Not counted.
- **New `:git` read effect:** "`git diff base...branch`, read by the `:git` effect". Today `:git` only commits. Not counted.
- **New `:done` argument:** `:checkout`. Not counted.
- **Two mains stay:** git main and the store's main, kept consistent through jolt/merges plus `bin/update`. Write-back on main directly is deferred, so the loop the audit names as duplication #1 survives the design.
- **Two merge paths stay:** pin bumps, deps.edn and native code stay root's git merges. Deleted files also stay there (git-commit only `update-index --add`s, fx.clj:290-292). AGENTS §3 says "delete first", so deletions are routine, and in practice this exception carries a large share of branches.

## 3. What it underestimates (riskiest first)
1. **Who writes the outside run's branch.** The design has root transact `refresh-tx` onto the run's branch at `seon_done`. But the branch belongs to the agent spawned at attach. `:done` first calls `seon.turn/hand-back!` in that agent (server.clj:~165), and hand-back runs the proofs on the program the agent has loaded. So a root-side import after hand-back is judged on stale code, and an import before it leaves the agent's loaded vars behind its branch. The mechanism that already exists is `seon.agent/load-tests!` (agent.clj:~590): the agent reads the checkout through `:source`, loads it, and imports onto its own branch. The import should be the agent's, using `refresh-tx`, before hand-back.
2. **The verdict tree misses files the store doesn't hold.** `run-tests` writes "the candidate's files" over the merges head. For an outside branch, changes to `resources/seon/config/default.edn`, `system.edn`, `tasks/*.edn`, `bin/` or `deps.edn` are not in the store (dirs = src, test, schemas), so the tests judge a tree without them. §5 requires every new limit to be a default.edn key, so this hits most branches. The verdict has to run on the branch's own tree (or its merge with main), not on a reconstruction.
3. **The write-back corrupts non-program files.** git-commit writes every file as mode `100644` (fx.clj:291), so a changed `bin/` script loses its executable bit. It cannot delete files either.
4. **Git history and authorship.** The Seon merge commit has no worktree-branch parent, so the branch looks unmerged: §8's removal checks and `git branch --merged` will refuse it. The branch's measured numbers in its commit messages (§7 "git") are lost. And unless `file-text` is byte-equal, the next merge of main into any sibling branch conflicts on formatting.
5. **Cost and latency.** A `bin/worktree --at` plus build plus process tests per merge, inside a synchronous `seon_done` ("must allow minutes", server.clj:~158), now on every merge. The 2026-10-23 standing ruling "Process tests are not automatic" (decisions.md:211) is overturned by making them unconditional. That needs the owner's explicit go, and the design does not name it as a ruling.

**What breaks first:** experiment 1's byte-equality on real files, then the first branch that touches default.edn or deletes a namespace.

## 4. Do the experiments decide it?
They test the import's fidelity (1) and digest equality (2). Both are necessary but not sufficient, and (2) is partly answered by `test-plan` already merging both stores. Neither tests the riskiest part: the end-to-end `seon_done` on a real outside branch.

**Third experiment:** on a worktree root, attach to a task, then hand back a past real branch that touched src, test, default.edn and a bin script, and deleted something (for example a recent merge's second parent). Measure:
- that the judge ran on the loaded import;
- what the verdict tree held compared with `git show tip`;
- the write-back commit's tree diff against the branch tip (it should be empty, modes included);
- the wall time and work of the verdict.

If that diff is not empty, the design is not done.

## 5. Rating: questionable, leaning worth it if cut
The audit is worth it:
- whole-task default instead of `with-merge` on every file task;
- delete `gate!`, `gate?` and the `:unrun` problem;
- the merge-log as a render of facts;
- fixing the branch key.

Those deletions stand alone and should merge first.

The "one path" half is underbuilt where it matters (write-back fidelity, branch ownership, the verdict tree) while still keeping two mains, two runners and git merges for deletions and pins.

**Cut, or defer behind experiment 3:** routing Claude worktrees through the store. Until write-back can commit on main with modes and deletions, the simpler single system is the reverse direction: outside branches merge by git, and the store re-imports main through `watch!`/`refresh-tx`, which already works. Seon's agents' merges get the same verdict by calling the one runner. Pick seon.prove or `bin/prove` as that runner and delete the other's verdict role.
