#!/bin/zsh
# notes/cutover-step-R.sh: the cutover, start to finish, on root's word (2026-10-23). Read notes/cutover-step-R-2026-10-23.md first.
#   zsh notes/cutover-step-R.sh              the real run: OLD=~/src/seon-jolt/next (read only except step D's tags), NEW=~/src/seon-next
#   REHEARSE=1 OLD=<a clone> NEW=<scratch> BRANCH=<its branch> zsh notes/cutover-step-R.sh
#                                            everything up to R3 plus the R6 grep, nothing outside NEW and OLD's tags/branches; no D1 commit
# Phases: D (history tags, idempotent), R0 preconditions, R1 clone, R2 branches, R3 config, [R4 build, R5 prove, R6 grep, R7 full run, R8 root] unless REHEARSE.
# Ends with the table of each live session's new worktree path (fetched-branches.txt, SESSIONS in the new root).
# If anything fails: delete $NEW; the old repo has only gained tags (step D) and was otherwise not touched.
set -eu
OLD=${OLD:-$HOME/src/seon-jolt/next}
NEW=${NEW:-$HOME/src/seon-next}
OUTER=${OUTER:-$HOME/src/seon-jolt}
BR=${BRANCH:-main}
REHEARSE=${REHEARSE:-}

### D (a precondition of R0; check first: `git -C $OLD tag -l 'history/*' | wc -l` is 0 until it has run, 2026-10-23)
# D1 root commits the outer repo's pending work: the staged renames, RESUME, DECISIONS (history-bundles/uncommitted/ holds copies made by old-cleanup)
[ -n "$REHEARSE" ] || [ -z "$(git -C $OUTER status --porcelain)" ] || { git -C $OUTER add -A && git -C $OUTER commit -q -m "Checkpoint before the cutover"; }
# D2 the outer repo's history as one tag, then the nested repos from the bundles old-cleanup made (the Chez, Jolt, Datahike, konserve and
#    persistent-sorted-set bundles are fork-family: they went to their modules as attic/* in step B, not here)
git -C $OLD rev-parse -q --verify refs/tags/history/prototype >/dev/null || git -C $OLD fetch --no-tags $OUTER refs/heads/main:refs/tags/history/prototype
B=$OUTER/history-bundles
for f in proto__core proto__flow pf1__core-fixed r3__next l3__next r5__next fx1__next e4__rust e9__datahike-jolt-src e9__konserve-jolt-src; do
  [ -n "$(git -C $OLD tag -l "history/${f//__//}/*")" ] || git -C $OLD fetch --no-tags $B/$f.bundle "refs/heads/*:refs/tags/history/${f//__//}/*"       # l3__next -> history/l3/next/<branch>
done
# D3 nothing lost: every bundle branch tip is now a tag
lost=$(for f in proto__core proto__flow pf1__core-fixed r3__next l3__next r5__next fx1__next e4__rust e9__datahike-jolt-src e9__konserve-jolt-src; do
  git bundle list-heads $B/$f.bundle | awk '$2 ~ /^refs\/heads\//{print $1}' | while read sha; do
    git -C $OLD cat-file -e $sha 2>/dev/null || echo "LOST ${f} $sha"; done; done)
[ -z "$lost" ] || { echo "$lost"; exit 1; }
git -C $OLD rev-parse -q --verify history/l3/next/main >/dev/null && echo "D ok (seon.merge.edn cites history/l3/next/*)"

### R0 Preconditions (all must pass; they check what A, C and D were to have done)
[ ! -e "$NEW" ]                                              # the target does not exist
[ "$(git -C $OLD branch --show-current)" = "$BR" ]            # the old tree is on its main branch
git -C $OLD diff --quiet --ignore-submodules                 # and clean
git -C $OLD rev-parse -q --verify refs/tags/history/prototype >/dev/null   # step D: the outer repo's history is a tag
! grep -qE '^\s*url = (/|\.)' $OLD/.gitmodules               # step C: no local-path URL is left
gh auth status >/dev/null                                    # the two private forks need it
# every pin resolves on its GitHub fork (a pin that is not there makes the clone fail late)
git -C $OLD ls-tree $BR reference-code/ | awk '$2=="commit"{print $3, $4}' | while read sha sub; do
  url=$(git -C $OLD config -f .gitmodules --get "submodule.$sub.url")
  case $url in https://github.com/*) repo=${url#https://github.com/} ;; *) echo "not a github url: $sub $url"; exit 1 ;; esac
  gh api "repos/${repo%.git}/commits/$sha" --jq .sha >/dev/null || { echo "MISSING $sub $sha on $repo"; exit 1; }
done
# no live worktree holds uncommitted work (a worktree's changes are not in any branch): list the dirty ones, each owner commits first
dirty=$(git -C $OLD worktree list --porcelain | awk '/^worktree /{print $2}' | while read w; do [ -n "$(git -C $w status --porcelain --untracked-files=no -- . ':!reference-code' 2>/dev/null)" ] && echo $w; done)
[ -z "$dirty" ] || { echo "UNCOMMITTED WORK in: $dirty"; exit 1; }
echo "R0 ok: preconditions hold, every pin is on GitHub, no worktree is dirty"

### R1 The clone, with its submodules from GitHub (the tags, the history/prototype history among them, come with it)
git clone --recurse-submodules $OLD $NEW
cd $NEW
[ -z "$(git submodule status | grep -v '^ ')" ]              # every submodule at its pin
[ "$(git rev-parse HEAD)" = "$(git -C $OLD rev-parse $BR)" ]
echo "R1 ok: $(git rev-parse --short HEAD), $(git tag | wc -l) tags"

### R2 The unmerged branches of the old repo, as local branches here (not jolt/merges: the new root starts that from main)
# every branch of the old repo main does not contain, jolt/* and claude/* (the sessions' work), as a local branch of the same name; the list is kept
for b in $(git -C $OLD branch --no-merged $BR --format='%(refname:short)' --list 'jolt/*' 'claude/*' | grep -vx 'jolt/merges'); do
  git fetch -q $OLD "refs/heads/$b:refs/heads/$b"
  echo "$b $(git rev-parse --short $b) ahead $(git rev-list --count $BR..$b)" >> fetched-branches.txt
done
# nothing missed: every unmerged tip of the old repo is a tip here
git -C $OLD for-each-ref --format='%(refname:short) %(objectname)' refs/heads/jolt refs/heads/claude | while read b sha; do
  [ "$b" = jolt/merges ] || git merge-base --is-ancestor $sha $BR || [ "$(git rev-parse -q --verify $b)" = "$sha" ] || echo "NOT FETCHED $b"; done
git remote remove origin                                     # nothing to fetch from or push to by accident
echo "R2 ok: $(git branch --list 'jolt/*' 'claude/*' | wc -l) branches, listed in fetched-branches.txt"

### R2b Each live session's new worktree path (print only; sessions move after R8, each running the one command in the right column)
echo "SESSION WORKTREES: old path -> new path (branch)   | in the new root: cd $NEW && bin/worktree NAME"
git -C $OLD worktree list --porcelain | awk '/^worktree /{w=$2} /^branch refs\/heads\//{b=substr($2,12); print w, b} /^detached/{print w, "(detached)"}' | while read w b; do
  [ "$w" = "$OLD" ] && continue
  n=${b#jolt/}; n=${n#claude/}
  printf '%-60s -> %s/.worktrees/%s  (%s)\n' "$w" "$NEW" "$n" "$b"
done | tee sessions-new-worktrees.txt

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

# REHEARSE=1 stops here after the grep that must find nothing (the build, prove and full run are the long parts)
if [ -n "$REHEARSE" ]; then
  git grep -nE 'seon-jolt|/Users/' -- . ':!notes' ':!reference-code' && { echo "R6 FAILED"; exit 1; }
  echo "REHEARSAL ok through R3 and the R6 grep"; exit 0
fi

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
