# rename-run: attempt -> run, as scripts

The owner (2026-10-23): "I don't like the attempt name. Call it agentic loop or run... Rename everywhere."
The rename is two scripts, run from the repository root, in this order, after every merge of main:

1. `python3 notes/rename-run/step1.py`: the test run keeps its name where `run` would clash with the new noun
   (`test-run-errors`, `test-run-answer`, the `test-run` locals in proofs, turn and my.agent). Idempotent.
2. `python3 notes/rename-run/attempt_to_run.py`: every tracked file outside `reference-code/` and `notes/`,
   attempt -> run (keys `:seon.attempt/*` -> `:seon.run/*`, vars, fns, deftests, task ids and their citations,
   docs and anchors). "an attempt" -> "a run". Owner quotes, the quoted Jolt message "Attempting to call unbound fn"
   and the image file name are left as written. Idempotent: text already renamed is untouched.

3. `python3 notes/rename-run/vocab_pass.py`: the other section 7a words (task files seon.entrance -> seon.server and
   seon.fences -> seon.audit with their ids, the ids named with an old word, FENCES -> GUARDS, stray prose and comments).
   Idempotent. `seon.docs-test/no-text-uses-an-old-word` is red when an old word reappears outside its `text-kept` list.

To refresh a branch after main moves: merge main, take main's side of each conflicted file
(`git checkout --theirs -- FILE`), run the three scripts, `git add` the explicit paths, commit. Then `bin/build`,
`bin/check health` (dead citations must read 0) and the docs and task-files tests.
By hand, after a merge: a `run` local in code main added that means a test run (step1 patches turn.clj's
`observe` and `observed`; the build catches an unresolved symbol, not a destructure that is now nil).
