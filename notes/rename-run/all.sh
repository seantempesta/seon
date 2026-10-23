#!/bin/sh
# notes/rename-run/all.sh: the whole rename pass over the git checkout you run it from (cwd), by this copy of the scripts,
# so a branch cut before the rename runs main's copy: `sh /path/to/main-checkout/notes/rename-run/all.sh` (idempotent; a tree already renamed is untouched).
# For a branch cut before the rename: commit your work, run this on the branch (it renames the branch's own text exactly
# as main was renamed), commit the result by explicit paths (`git add -A -- . ':!reference-code'`), THEN `git merge main`.
# Both sides are then renamed alike and only real conflicts remain; resolve them by hand (never `--theirs` on a feature branch).
# A line it cannot rename, or a task of the dissolved seon.fences.edn without an owner, stops it with a message.
set -e
here=$(cd "$(dirname "$0")" && pwd)
cd "$(git rev-parse --show-toplevel)"
python3 "$here/step1.py"
python3 "$here/attempt_to_run.py"
python3 "$here/vocab_pass.py"
