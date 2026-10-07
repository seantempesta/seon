# task-tags — notes (lane task-tags, 2026-10-07)

Owner rulings: (C) delete `test/my/examples_test.clj`; (A) `:seon/tasks` tags name tasks by quoted `'ns/task-name`
symbols; (B) `seon.id/id` hashes value structure, never printed text. Order C, A, B.

## Probe (start)

- `bin/seon status`: default pid 65136, `:faulted`, running commit 42e569d9d. Health top: definition regressions on
  `seon.db/pull-call` (65→262 us) and `seon.db/pull-logical`; `:seon.test.runner/reach-declarations` cache 0% hits at
  34-68 MB; 14 open faults (one `java.lang.StackOverflowError` in `seon.error/normalize`, no task linked).
- Ugly output: a JVM-mode reply clipped my own repository path strings at 32 characters
  (`"resources/seon/tasks/seon.cluste"…+11`) inside a 30-row vector. Agents' own values are never clipped;
  this is the value printer's per-string budget on a small value.

## Slice C: delete the examples test

- Deleted `test/my/examples_test.clj` (owner ruled delete; the uncommitted edits it carried are moot) and its orphaned
  fixture `test/my/examples/nested_fixture.clj` (its only requirer was the test). `test/my/examples_fixture.clj`
  stays: `seon.test.runner-test` uses it.
- Closed both tasks by deleting their files: `my.examples-test/docstring-examples-needing-a-live-cluster-cannot-run-in-the-examples-fixture`
  and `my.examples/the-examples-test-reads-a-docstring-example-as-an-invalid-token` were about that test's world and
  its reader; nothing else executes docstring examples.
- `seon.cluster.instruction/toolkit-includes-fixtures-and-undocumented-generators` no longer names the deleted fixture.
- Stale references left for their owners: `.agents/skills/clojure-testing/SKILL.md` §2.11 and §3 name the deleted
  test (lane final-revision); `docs/seon/schedule/README.md` lines on `examples_test` (root's index);
  `docs/seon/issues/an-agents-test-world-has-no-agent-so-my-functions-cannot-be-tested-as-documented.md` cites it.
