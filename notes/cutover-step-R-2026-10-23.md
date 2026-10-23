# Step R: the fresh clone at ~/src/seon-next (2026-10-23)

One command sequence, run by root after main is frozen, `jolt/rename-run` has merged, and `jolt/self-contained` (steps A and C) has merged. Context: [the plan](self-contained-plan-2026-10-23.md) (§6, steps A to E and R) and `docs/plan.md` (the cutover). The old tree `~/src/seon-jolt/next` is read only throughout; if any check fails, delete `~/src/seon-next` and nothing else has changed.

Run it in a shell where long commands may take a while: the build and the full run are the long parts (niced, once). `set -eu` stops at the first failing command; each section prints what it proved. Read each section's comment before running it.

```sh
set -eu
OLD=$HOME/src/seon-jolt/next
NEW=$HOME/src/seon-next

### D (a precondition of R0; check first: `git -C $OLD tag -l 'history/*' | wc -l` is 0 until it has run, 2026-10-23)
# D1 root commits the outer repo's pending work: the staged renames, RESUME, DECISIONS (history-bundles/uncommitted/ holds copies made by old-cleanup)
#    git -C $HOME/src/seon-jolt add -A && git -C $HOME/src/seon-jolt commit -m "Checkpoint before the cutover"
# D2 the outer repo's history as one tag, then the nested repos from the bundles old-cleanup made (the Chez, Jolt, Datahike, konserve and
#    persistent-sorted-set bundles are fork-family: they went to their modules as attic/* in step B, not here)
git -C $OLD fetch $HOME/src/seon-jolt refs/heads/main:refs/tags/history/prototype
B=$HOME/src/seon-jolt/history-bundles
for f in proto__core proto__flow pf1__core-fixed r3__next l3__next r5__next fx1__next e4__rust e9__datahike-jolt-src e9__konserve-jolt-src; do
  git -C $OLD fetch $B/$f.bundle "refs/heads/*:refs/tags/history/${f//__//}/*"       # l3__next -> history/l3/next/<branch>
done
# D3 nothing lost: every bundle branch tip is now a tag
for f in proto__core proto__flow pf1__core-fixed r3__next l3__next r5__next fx1__next e4__rust e9__datahike-jolt-src e9__konserve-jolt-src; do
  git bundle list-heads $B/$f.bundle | awk '$2 ~ /^refs\/heads\//{print $1}' | while read sha; do
    git -C $OLD cat-file -e $sha || echo "LOST ${f} $sha"; done; done
git -C $OLD rev-parse -q --verify history/l3/next/main >/dev/null && echo "D ok (seon.merge.edn cites history/l3/next/*)"

### R0 Preconditions (all must pass; they check what A, C and D were to have done)
[ ! -e "$NEW" ]                                              # the target does not exist
[ "$(git -C $OLD branch --show-current)" = main ]            # the old tree is on main
git -C $OLD diff --quiet --ignore-submodules                 # and clean
git -C $OLD rev-parse -q --verify refs/tags/history/prototype >/dev/null   # step D: the outer repo's history is a tag
! grep -qE '^\s*url = (/|\.)' $OLD/.gitmodules               # step C: no local-path URL is left
gh auth status >/dev/null                                    # the two private forks need it
# every pin resolves on its GitHub fork (a pin that is not there makes the clone fail late)
git -C $OLD ls-tree main reference-code/ | awk '$2=="commit"{print $3, $4}' | while read sha path; do
  url=$(git -C $OLD config -f .gitmodules --get "submodule.$path.url")
  case $url in https://github.com/*) repo=${url#https://github.com/} ;; *) echo "not a github url: $path $url"; exit 1 ;; esac
  gh api "repos/${repo%.git}/commits/$sha" --jq .sha >/dev/null || { echo "MISSING $path $sha on $repo"; exit 1; }
done
# no live worktree holds uncommitted work (a worktree's changes are not in any branch): list the dirty ones, each owner commits first
dirty=$(git -C $OLD worktree list --porcelain | awk '/^worktree /{print $2}' | while read w; do [ -n "$(git -C $w status --porcelain --untracked-files=no -- . ':!reference-code' 2>/dev/null)" ] && echo $w; done)
[ -z "$dirty" ] || { echo "UNCOMMITTED WORK in: $dirty"; exit 1; }
echo "R0 ok: preconditions hold, every pin is on GitHub, no worktree is dirty"

### R1 The clone, with its submodules from GitHub (the tags, the history/prototype history among them, come with it)
git clone --recurse-submodules $OLD $NEW
cd $NEW
[ -z "$(git submodule status | grep -v '^ ')" ]              # every submodule at its pin
[ "$(git rev-parse HEAD)" = "$(git -C $OLD rev-parse main)" ]
echo "R1 ok: $(git rev-parse --short HEAD), $(git tag | wc -l) tags"

### R2 The unmerged branches of the old repo, as local branches here (not jolt/merges: the new root starts that from main)
# every branch of the old repo main does not contain, jolt/* and claude/* (the sessions' work), as a local branch of the same name; the list is kept
for b in $(git -C $OLD branch --no-merged main --format='%(refname:short)' --list 'jolt/*' 'claude/*' | grep -vx 'jolt/merges'); do
  git fetch -q $OLD "refs/heads/$b:refs/heads/$b"
  echo "$b $(git rev-parse --short $b) ahead $(git rev-list --count main..$b)" >> fetched-branches.txt
done
# nothing missed: every unmerged tip of the old repo is a tip here
git -C $OLD for-each-ref --format='%(refname:short) %(objectname)' refs/heads/jolt refs/heads/claude | while read b sha; do
  [ "$b" = jolt/merges ] || git merge-base --is-ancestor $sha main || [ "$(git rev-parse -q --verify $b)" = "$sha" ] || echo "NOT FETCHED $b"; done
git remote remove origin                                     # nothing to fetch from or push to by accident
echo "R2 ok: $(git branch --list 'jolt/*' | wc -l) jolt/* branches"

### R3 This checkout's own config: its ports and its write-back tree (what next-merges was)
mkdir -p config
cat > config/local.edn <<'EDN'
;; This checkout's own config layer (gitignored), over resources/seon/config/default.edn and
;; under the command line's --config and --set (seon.config). bin/ports keeps the port keys
;; current and leaves every other entry as it is.
{;; root's server (bin/root, bin/mcp)
 :seon.config.root/server-port #long #or [#env SEON_SERVER_PORT 7888]
 ;; the dashboard; bin/check and bin/test set SEON_WEB_PORT=0, so their roots serve none
 :seon.config.web/port #long #or [#env SEON_WEB_PORT 7777]
 ;; the standing root's source: main's program imported at boot from .worktrees/merges
 ;; (jolt/merges), the tree merges write back into and commit on (docs/source.md), so
 ;; this tree's working copy stays the owner's; root merges jolt/merges into main (bin/update)
 :seon.config.source/root #seon/checkout ".worktrees/merges"
 :seon.config.source/branch "jolt/merges"
 :seon.config.source/dirs ["src" "test" "resources/seon/schemas"]}
EDN
echo "R3 ok: config/local.edn"

### R4 The build, from the sources only (target/ is not carried over; `cp -cR $OLD/target` would be the shortcut, but this is the proof that the repo builds by itself)
nice -n 15 bin/build > target-build.log 2>&1 || { tail -30 target-build.log; exit 1; }
rm -f target-build.log
test -x target/seon
echo "R4 ok: target/seon built"

### R5 The proof: everything runs (no test results are carried, so this runs all of it once)
nice -n 15 bin/prove

### R6 Nothing refers to the old folder or to an absolute path (notes/ are dated records and reference-code is other people's code)
if git grep -nE 'seon-jolt|/Users/' -- . ':!notes' ':!reference-code'; then echo "R6 FAILED: the lines above"; exit 1; fi
if git grep -nE '(^|[^.[:alnum:]/])\.\./' -- . ':!notes' ':!reference-code' | grep -vE '\]\(\.\./|#include "(\.\./)+resources|dirname "\$0"\)/\.\.|\)/\.\.\)|\.\./\.\.\)'; then echo "R6 FAILED: a path leaves the root"; exit 1; fi
echo "R6 ok: nothing outside the root is named ($(git grep -lE 'seon-jolt|/Users/' -- notes | wc -l) dated notes still name the old paths as they were)"

### R7 The full run, once (about 10 minutes, niced; AGENTS section 3)
nice -n 15 bin/test --all
nice -n 15 bin/check 100

### R8 The standing root, on a store it makes itself
bin/worktree merges                                          # .worktrees/merges, on jolt/merges from main
bin/root start
bin/root status                                              # up, on 7888 / 7777
echo "R8 ok"
```

## After it, for every session (one command each, in the new root)

A session's old worktree (`../next-NAME`, `.worktrees/NAME`) belongs to the old repo; its work is the branch R2 fetched. Recreate it:
```sh
cd ~/src/seon-next && bin/worktree NAME        # an existing branch jolt/NAME is checked out at .worktrees/NAME
```
The list to recreate is `fetched-branches.txt` (delete it afterwards: untracked). Sessions keep working in the old worktree until their branch is fetched; after R2 they commit nothing more there.

- **The old tree stays** as the archive until the owner deletes it (step S of the plan). It is not edited again.
- **Tell `old-cleanup`** (it deletes the nine fork source folders once step C is merged on main and R1 above has cloned from GitHub) and the other sessions: the root is `~/src/seon-next`; their worktrees of the old tree are finished or recreated with `bin/worktree NAME`, which checks out an existing `jolt/NAME`.
- **The Claude project memory** is keyed by path and is not carried over: the repo is meant to explain itself (`AGENTS.md`, `docs/plan.md`).

## What each check proves

| Section | Proves |
|---|---|
| R0 | steps A, C and D happened; every pin is fetchable from GitHub, so the clone cannot stall on a missing commit |
| R1 | a clone with submodules from GitHub alone is complete and at main's pins; history and tags came along |
| R2 | no unmerged work is left behind in the old repo |
| R3 | the new root's ports and write-back tree are its own and relative to it |
| R4 | the repo builds from its sources, with no help from the old tree |
| R5, R7 | the tests and checks pass at the new path |
| R6 | the repo names nothing outside itself |
| R8 | a root starts, makes its own store, and imports main's source from `.worktrees/merges` |

## Known limits of this script, said plainly

- It was **not run end to end**: no tree here has a main that holds steps A and C yet, and it needs the network, GitHub login and about an hour of machine time. Two parts were run on `jolt/self-contained` (tip below): R0's pin check, against this branch's pins and GitHub URLs (all 12 submodules resolve; the run found and fixed a bug, `ls-tree ... reference-code` needs the trailing slash), and R6's first grep, which prints nothing on this branch (the five task plans, `AGENTS.md` and `docs/plan.md` were reworded for it; the task files' quote and backslash counts are unchanged, but `seon.task-files-test` was not re-run: it would rebuild Jolt). The clone mechanics (relative and absolute submodule URLs, a clone of a clone) were exercised on their own; the config entry `#seon/checkout ".worktrees/merges"` follows the existing `#seon/checkout` tag (`seon.config`).
- `bin/prove` in R5 on a tree with no results runs everything, which overlaps R7's `bin/test --all`; R7 is kept because `bin/check 100` is the scale check and AGENTS section 3 names both as the one full run.
- The private forks (`persistent-sorted-set`, `jolt-overlay`) make R1 depend on `gh auth` credentials for git over https (`gh auth setup-git`), not only for the API calls of R0. If the clone fails there, run `gh auth setup-git` and clone again into a fresh directory.
