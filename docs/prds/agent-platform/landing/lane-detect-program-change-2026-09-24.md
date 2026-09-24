---
type: landing
status: e81cb51dc (sci.eval detection), 7f53d0809 (lock deleted), 2bf66450c (review F1/F2), code only (owner load rule); nothing loaded into default, no regression run
lane: detect-program-change
created: 2026-09-24
---
# Lane detect-program-change (2026-09-24)

Owner ruling 2026-09-24: "just detect and push back if there's a conflict". This replaces the read/write lock
from da9910fec/2bd861329 and the S3a/S3b lock parts
([S3 landing](lane-harness-s3-program-validity-2026-09-24.md)) with a value that is read at start and at end.
What exists now: `seon.sci.eval/loaded-identity` is a defonce atom. It holds either `{:seon.source/commit-id c}`
or the unknown-program diagnostic. Development adoption advances it and never waits. Each test member and each agent
SCI evaluation reads it when it starts and when it ends.

## Broken or unproven first

- **Nothing has loaded or run.** Under the load rule I did not reload `seon.sci.eval`, `seon.cluster` or `seon.test`
  into default, because that would redefine default's Vars outside adoption. clj-kondo reports no errors. The save
  hook's contract-compile check passes on every changed source and schema file (it refused the first sci.eval edit
  until the schema existed, so the check is live).
- **REPL proof covers only the detection function.** I evaluated the exact source of `advance-loaded-identity!` and
  `call-detecting-program-change` in a scratch namespace (since removed) and checked these cases:
  - no change: the value;
  - another thread changes the identity: a refusal "from A to B";
  - the caller's own adoption: the value;
  - nested own adoption: the value;
  - a foreign change before a nested start, or inside a nested call: a refusal;
  - unknown at start: the diagnostic, and the body never runs.
  I also checked that `seon.sci.kernel/failure-value` keeps a thrown refusal whole: layer, `:seon.test/not-runnable`
  and message survive. The new `evaluate` and `bounded-result` paths have not executed.
- **Review findings F1 and F2 are closed in code, but no regression has run** (2bf66450c;
  [review](../../../research/agent-platform/review-landings-2026-09-24.md)).
  - **F1.** Each detecting execution pushes its own expectation onto a per-thread stack. An adoption advances only
    the adopting thread's chain. So member B, running beside member A's own adoption, reads unknown even when both
    inherit one enclosing evaluation. An execution that ends in an unknown program answers that diagnostic; its own
    failed adoption is the case. A nested entry after a failed adoption refuses by name
    (`seon.cluster/call-with-program-change`, with the cause chain).
  - **F2.** `seon.test/run` reads the loaded identity before admission (`since`). A member whose start identity is no
    longer `since` never resolves or runs, and stops `:program-unknown`. So a pass is never recorded under an
    admission whose program differs from the one that executed.
  - Both counterexamples now have regressions in `seon.cluster.program-change-test`, and the REPL scratch probe
    reproduced both:
    - F1 gives `[:a ":B -> :C"]`: A keeps its value, B is refused;
    - F2 gives `":C -> :D"`, and the body did not run.
- **Residual of F2: the recorded provenance is still the cluster's DB record.** `:seon.test.run/loaded-commit-id` is
  read at the writer, as in S3c. The in-memory identity can name another commit: after any adoption of a different
  root in this JVM (a test's scratch-root `refresh-source!`, or another cluster's development adoption), or as the
  booted `{}`. I did not compare the two. Doing so would make every nested scratch-cluster request in a test read
  unknown, because the JVM's commit is never the scratch cluster's record. Continuity from before admission to member
  end is enforced. Recording the execution's own commit per run needs `:seon.test.run/loaded-commit-id` in the
  admission request (`seon.test.run.edn`). That is a follow-up; the reuse rule is unchanged (test.clj ~870).
- **"Own work" is decided by dynamic-binding conveyance.** `future`, `bound-fn` and Flow tasks that convey bindings
  count as the member's or evaluation's own thread. An adoption such a thread runs advances that execution's
  expectation, the same scope as the deleted `*member-lock*`. An adoption on a thread without those bindings (the hook,
  another lane, another agent) is foreign. A member body that hands work to an unconveyed executor, and that work
  adopts, would read as changed. That errs toward unknown, never toward green.
- **CLAUDE.md now contradicts the ruling.** "Stability comes from branch custody; no reload may run under an evaluation"
  and "Adopt exclusively through rows → load → arm → record, excluding conflicting reloads/evaluations" describe the
  deleted exclusion. Root owns that file; it needs an edit to "a reload under an evaluation or member makes its result
  refuse/unknown".
- The member result for a detected change goes through the existing mapping: `:seon.error/layer :seon.cluster/adoption`
  becomes `:seon.test.run/stopped :program-unknown`, at `test.clj` `bounded-result`. I reused `:program-unknown`
  rather than adding `:program-changed`, so `seon.test.run.edn` is unchanged. The run stops with that cause and the
  members after it read `:seon.test/result-unknown`. They rerun next request.
- The evaluation refusal reuses the S3 diagnostic shape (`:seon.test/not-runnable-error`: base plus
  `:seon.test/not-runnable "A -> B"`). That is a test-named key on an evaluation refusal. If root wants a separate
  `:seon.sci.eval/program-changed-error`, it is a one-schema follow-up.
- `evaluate` reads `(:seon.sci.eval/loaded-identity request loaded-identity)`. The optional request member exists so
  tests inject their own atom and never touch the JVM's. Production callers pass nothing.
- **Extra files outside the named list** (free in the ledger, and each the schema resource of an owned namespace):
  - `resources/seon/schemas/seon.sci.eval.edn`: `:seon.sci.eval/loaded-identity` plus the optional request member;
  - `resources/seon/schemas/seon.cluster.edn`: deleted `:seon.cluster/program-change-lock` and
    `:seon.cluster/program-validity`, whose predicates are gone;
  - `test/seon/test_runner_test.clj` and `test/seon/test/one_request_test.clj`: the `bounded-result` argument change,
    one line each.
- Mid-lane, S2a committed fe5dcc072 over `cluster.clj`/`test.clj`. I had prepared slice 2 on scratch copies based on
  e81cb51dc, and three-way checked the patch against the new HEAD before applying. S2a's `clean-request-handle` and
  `seed-cluster-facts!` are intact in 7f53d0809.

## What changed

- **e81cb51dc** (`sci/eval.clj` +73/−1, `seon.sci.eval.edn` +8/−1, new `test/seon/sci/program_change_test.clj` 67):
  - `loaded-identity`, `advance-loaded-identity!` and `call-detecting-program-change`;
  - `evaluate` wraps acquisition, read, eval and admission in `trusted-evaluation`. A change answers a failed
    evaluation whose value is the refusal "The loaded program changed during this execution, from A to B; its result
    is not trusted. Rerun it." An unknown program at start never runs the form.
- **7f53d0809** (`cluster.clj` +31/−92, `test.clj` +13/−23, `seon.cluster.edn` −8, two test files ±1, old
  `adoption_exclusion_test.clj` −142, new `test/seon/cluster/program_change_test.clj` +91):
  - **Deleted:** `program-change` (the `ReentrantReadWriteLock`), `program-validity`, `program-validity?`,
    `program-change-lock?`, `*member-lock*`, `call-excluding-adoption`, the longest-member-bound query with its
    5 s wait-and-refuse, and test.clj's three `requiring-resolve` delays.
  - **`call-with-program-change identity-atom commit-id change`:**
    - marks the program unknown before any root is touched;
    - names the commit only after `change` returns, which happens after its record commits;
    - keeps the unknown mark, with its cause, on a throw.
  - `mcp-runtime-observation` reads the value for `:faulted` and `:seon.dev.mcp/program-unavailable`.
- **Net after the first two commits:** source −1 (+117/−116 over sci/eval, cluster and test); schemas −2;
  tests +16 (+158/−142 net of the deleted lock tests).
- **Cost:** a member or evaluation adds two atom reads, one `binding` and one volatile. Adoption loses the lock wait,
  which was up to the longest declared member bound, and one Datalog query. Before: the installed `evaluate` of
  `(do (change!) (+ 1 2))` on a fresh base ctx took 5.99 ms. After: not measured, because the new code is not loaded.

- **2bf66450c** (`sci/eval.clj` +43/−31, `test.clj` +14/−10, tests +41/−7): per-thread expectation stack,
  `since`-arity detection, and an unknown program at end. `member-result` and `bounded-result` take `since`.
- **Totals over the three commits:** source +101/−101 (`sci/eval`, `cluster`, `test`); schemas +8/−10; tests
  +203/−150.

## Root runs (serially, after adopting e81cb51dc, 7f53d0809 and 2bf66450c)

1. `bin/test-check default --ns seon.sci.program-change-test`: an evaluation refuses by name on a change, returns its
   value with no change or with its own adoption, and never runs in an unknown program.
2. `bin/test-check default --ns seon.cluster.program-change-test`:
   - adoption completes while a member runs, and the member reads `:program-unknown`;
   - no change gives the normal result;
   - F1: two members, A adopting, B unknown;
   - F2: an adoption between admission and member start runs nothing;
   - the failed-adoption paths (partial load, arming, record) stay unknown until one records;
   - a nested entry after a failed own adoption refuses by name.
3. `bin/test-check default --test seon.test.one-request-test/a-body-outliving-its-bound-is-released-only-after-it-exits`
4. `bin/test-check default --test seon.test-runner-test/a-huge-execution-failure-becomes-bounded-text`
5. `bin/test-check default --ns seon.sci.eval-test`: `evaluate`'s wider behaviour under the wrapper.
6. Probe: time the same `evaluate` form before and after, and check that runtime status shows no
   `:seon.dev.mcp/program-unavailable`.

RESET NEEDED: no.
