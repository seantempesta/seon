---
title: "Post-vocab verification on live default, 2026-10-11"
type: research
status: evidence
created: 2026-10-11
---

# Post-vocab verification (lane `post-vocab`, default on b101216ae, fresh DB)

All runs are root `my.test/check {:seon.test/identities #{...}}` on the live default. Heap stayed near 0.9 GB live.

1. **The 13 tests of `seon.test/a-test-green-on-a-fresh-jvm-is-green-on-the-live-root` are green on the live root**
   (runs 1de489e5b19f: 8 `seon.error-test`, c1aa21307bc7: blob test and `a-function-and-its-schema-land-as-one-unit`,
   07d7ba0c3398: `a-green-request-lands-on-main-and-disk-in-one-call` and the two `seon.turn-continue-test` ones;
   0 fail, 0 error). The task is not yet retired: `test/seon/test_test.clj` (held by lane w1-world) still tags `a-member-runs-in-its-own-world-never-its-requesters` with it, so deleting the entry fails ingestion; retire both together when w1 releases the file. Cost note: the 8 error tests
   allocated 3.99 GB in 4.6 s for 19 assertions (the finding `:large-allocation`), not filed separately.
2. **Vocab-cut proofs are green** (run 259458686643, 10 tests, 41 assertions): the four `my.root-test` tests,
   both `seon.context-test` end-summary tests, `seon.cluster.source-test` merge-naming-a-task, two
   `seon.cluster.write-back-test` landings, and `my.test-check-test` docstring/body rerun.
3. **`seon.ref-test` section tests are not a regression**: `a-doc-section-reads-with-its-headings-below-and-size`
   and `a-referenced-doc-shows-its-heading-size-and-one-read` (green in revert-after.edn) are green live (run
   50a6e3658b86); the red in vocab-cut's check came from its candidate archive's documents, not the code.
4. **No `:coverage-unknown`**: every root `my.test/check` above answered a run, none a refusal.
5. **`my.test-check-test`** `a-docstring-only-defn-...` evaluates `(in-ns 'seon.task) (defn task-path? ...)` on the
   test agent's own branch through `eval_clj`: that is the product path under test (an agent defines in its SCI
   fork), not `with-redefs`, `alter-var-root` or `intern` over a compiled Var, and it is green. Left as is; the
   injected-world alternative would not exercise a redefinition.
6. **Vocabulary leftovers**: scripted sweep renamed `an-end-proposes-once-whatever-interrupted-it`,
   `an-end-proposes-at-most-once`, `proposals-refuses-its-own-result-on-main` (now
   `merge-requests-refuses-its-own-result-on-main`), `a-green-proposal-lands...`, `a-proposal-waits...`,
   `propose-merge!` and `seon.task/propose!` across tests, task files and schedule/spec docs (excluding
   `seon.task.edn`/`seon.batch.edn`, held by other lanes). `:seon.source/candidate` is a schema resource key
   (about 25 uses): filed as task `seon.test/the-merge-branch-key-is-seon-source-merge-branch`.
7. Filed: `seon.test/redefining-seon-task-agent-task-replies-too-large-to-reread`.
