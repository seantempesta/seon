#!/bin/sh
# notes/rename-run/merge-main.sh: merge main into the current branch, a branch cut BEFORE the rename (idempotent when main is already merged).
# Run from your checkout with a clean, committed tree:   sh /path/to/main-checkout/notes/rename-run/merge-main.sh
# It renames (all.sh) the merge base and your branch alike, then three-way merges them with main, so only the lines you and main
# both really changed conflict. On conflicts it stops after printing them: resolve, `git add`, `git commit` (the merge is not made).
# A clean result is committed as the merge of main. Then bin/build and bin/prove as usual.
set -e
here=$(cd "$(dirname "$0")" && pwd)
cd "$(git rev-parse --show-toplevel)"
[ -z "$(git status --porcelain --untracked-files=no -- . ':!reference-code')" ] || { echo "merge-main: commit your work first (explicit paths)"; exit 2; }
base=$(git merge-base HEAD main)
T=$(mktemp -d "${TMPDIR:-/tmp}/rename-base.XXXXXX")
trap 'git worktree remove --force "$T/wt" 2>/dev/null; rm -rf "$T"; git worktree prune' EXIT
git worktree add -q --detach "$T/wt" "$base"
( cd "$T/wt" && sh "$here/all.sh" >/dev/null && git add -A -- . ':!reference-code' && git commit -q --allow-empty -m "rename pass over the merge base" )
rbase=$(git -C "$T/wt" rev-parse HEAD)
sh "$here/all.sh" >/dev/null
git add -A -- . ':!reference-code'
git commit -q --allow-empty -m "rename pass over the branch"
if tree=$(git merge-tree --write-tree --merge-base="$rbase" HEAD main); then
  c=$(git commit-tree "$tree" -p HEAD -p main -m "Merge main into $(git rev-parse --abbrev-ref HEAD) (renamed base and branch, then merged)")
  git reset -q --hard "$c"; echo "merge-main: merged main at $c"
else
  echo "$tree"; echo "merge-main: conflicts above (a rename-aware merge of $rbase, HEAD, main); resolve them by hand against main's side"; exit 1
fi
