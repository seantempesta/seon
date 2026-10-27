# Handoff: store-release and datom-cost (worktree session global-ids, 2026-10-23)

## jolt/store-release: ready, reported to the merge session

- Tip 6c93cf13, in /Users/sean/src/seon-jolt/next-global-ids; main 25dc0e4e is merged in.
- Pins, checked with `git ls-tree`, both pushed to seantempesta/*:
  - Datahike bae7c3e: the tip of jolt/release-drains, with jolt/in-scalar 1880fcc merged in.
    - 9cd6e7b: a :sync writer's shutdown commits the run-ahead value, so a release never drops an applied write.
    - b6ae683: database-exists? on Jolt delivers by calling the promise; it used to put! onto it, and the caller hung.
  - konserve 84d8bc0 (jolt/lmdb-env-release): the LMDB env is refcounted per path (acquire-env!, release-env!) and closes with its last store.
- src: seon.db/stored-id releases the store it connects.
- The task seon.db/a-release-drains-and-forgets-its-store is in resources/seon/tasks/seon.db.edn, with both parts fixed. Its proofs, in seon.db-test:
  - a-store-made-again-at-a-removed-path-is-a-new-store
  - database-exists-answers-whether-a-store-is-there
  - a-release-commits-what-was-applied
- Verdicts:
  - bin/build OK; bin/prove PROVE OK.
  - seon.db-test and seon.procs-test: green.
  - seon.agent-test: 2 red, a-root-with-no-provider-ends-the-attempt-on-its-typed-error and a-failure-marked-through-the-server-holds-the-task.
    - Both fail with "No such namespace: seon.e2e" in root's REPL (agent_test.clj:264 and :395 eval seon.e2e/await-*).
    - The diff doesn't reach that path. Not yet checked: whether main is red the same way.
- §9 self-review: 0 findings.
- Next: the merge session merges it.
  - First run the two reds on main. If main is red too, file a task: the test's root eval needs seon.e2e loaded in root, or the tests stop going through it.

## jolt/datom-cost: claimed, no work yet

- Task: datahike.transaction/a-datom-costs-its-index-inserts (resources/seon/tasks/datahike.transaction.edn). It holds pure-tests' measurements:
  - the import costs 5.4-6.3 s and 3.6 GB per test plan, ~24.7k ticks and ~48 KB per datom;
  - the index inserts alone are ~3-4k ticks and ~4 KB;
  - identities multiply it: :db/unique x1.6, a content-id identity x2.8.
- No findings of this session's yet; nothing measured here.
- Next steps:
  1. Time the add path's parts at the REPL, at n <= 100, largest first, in datahike.db.transaction:
     - transact-add;
     - with-datom's validate-datom;
     - attr-info;
     - the cond-> of update-in on the db record;
     - the cardinality-one search;
     - content-tempids.
  2. Fix the largest at its cause on a jolt/<topic> Datahike branch.
  3. Write the cost proof seon.db-test/a-datom-costs-its-index-inserts: d/db-with of n plain datoms at 1,000 and 10,000, per datom within 2x of the index inserts, plus one with :db/unique and content-id identities.
- Reproduction scripts: notes/pure-tests/transact-cost-layers.clj, transact-cost-eids.clj and plan-cold-phases.clj (main tree).

Nothing of this session's is running.
