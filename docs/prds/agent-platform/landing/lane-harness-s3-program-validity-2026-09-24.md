---
type: landing
status: S3a committed d77a9461a and adopted on default; regression not yet run (owner load stop); S3b/S3c wait for test.clj
lane: harness-s3-program-validity
created: 2026-09-24
requirements: docs/research/agent-platform/one-test-harness/design-2026-09-24.md §5, §9c S3
---
# Lane harness-s3-program-validity (2026-09-24)

Authority: [one-test-harness design §5, §9c row S3](../../../research/agent-platform/one-test-harness/design-2026-09-24.md),
[Astra review finding 2](../../../research/agent-platform/one-test-harness/astra-review-2026-09-24.md),
[publication under concurrent lanes, root cause 4](../../../research/agent-platform/publication-under-concurrent-lanes-2026-09-24.md).
Files named first: `src/seon/cluster.clj`, `test/seon/cluster/adoption_exclusion_test.clj`
(the existing test of the changed lock owner, whose calls had to change with its signature). No schema resource was needed for S3a.

## Broken or unproven first

- **The regression is committed and adopted, but it has not run.** The owner's load-easing stop (root, 2026-09-24) arrived
  before the focused request. Root runs it later:
  `bin/test-check default --ns seon.cluster.adoption-exclusion-test`
  (both deftests; the new one is
  `an-adoption-failing-after-roots-change-keeps-the-program-unavailable-until-one-records`).
- **S3b and S3c are not started.** Both need `src/seon/test.clj` (resolution at `test.clj` ~1509/1533 before the body lock;
  evidence provenance and reuse) and `seon.test.run.edn`. `fix-unstarted-members-unknown` holds those files and is still running.
- A member refused for an unknown program is **counted as red** today: `bounded-result` (`test.clj:184`) maps any
  `:seon.test/not-runnable` answer to `failed` with one error. It should be unknown (S0 vocabulary). That mapping is in `test.clj`
  (held), so it goes into S3b.
- **Only test members are gated.** Agent SCI evaluations, renders and seed execution still run while `program-validity` is set.
  They do not take `call-excluding-adoption`. This is S3b's "resolve the callable and enter under the same exclusion" work.
- The unknown-program diagnostic is **held in the JVM and shown in status. It is not committed as a fault, and no agent is woken.**
  The adoption refusal still returns to the hook caller as before. The CLAUDE.md delivery law for core faults is not yet met for this failure.
- The mark covers the whole JVM program, not only the affected namespaces. A failed adoption stops every host test member until an
  adoption records. This is deliberate: a partial reload or partial arming can reach a caller in any namespace, and nothing smaller is proven safe.
- `call-excluding-adoption` keeps a 2-arity that reads the global `program-validity`, so `test.clj:151` works unchanged. S3b converts that
  caller to pass validity explicitly and then deletes the 2-arity.
- The ledger row for `harness-s1-attempt-release` lists a "cluster.clj:3606 caller" hunk. This lane holds the whole of `cluster.clj`.
  Root must settle that overlap before either lane edits it again.

## What changed (commit d77a9461a)

- `program-validity` (defonce atom) sits beside `program-change`. Loaded Var roots have no database owner, so this atom is the
  JVM's own record of whether its loaded definitions, arming and adoption record agree. It holds nil when they agree, otherwise the diagnostic.
- `call-with-program-change lock validity database commit-id change` does three things:
  - Before `change` runs, it sets validity to an unknown-program diagnostic (`seon.error.refusal/diagnostic`, whole cause chain,
    `:seon.test/not-runnable`, `:seon.source/commit-id`).
  - It clears validity only when `change` returns.
  - If `change` throws, it stores the diagnostic with that throwable's chain, then rethrows. The mark stays after unlock.
  - The same applies to a member's own nested adoption.
- The adoption record transaction (`adoption-guard-tx`, commit id, `:seon.test/adoption-*`) moved inside the `change` fn. It now
  commits under the write exclusion, after the post-reload byte check and arming. Readiness is published only after bytes, loaded
  definitions, arming and the record all agree.
- `call-excluding-adoption lock validity work` returns the validity diagnostic without starting `work` when the program is unknown.
- `mcp-runtime-observation` reports `:seon.dev.mcp/program-unavailable`, and health becomes `:faulted` while the program is unknown.
- Docstring corrections in `refresh-source!`:
  - A host-bound change adopts before its red verdict. The old blanket "red gate leaves the cluster unchanged" was false.
  - A refusal after reload leaves the record unchanged and the program unknown.

## Evidence

- **Before (defect reproduced, live default, fresh lock):** a `change` that threw "arming refused after reload" unlocked, and the next
  `call-excluding-adoption` **entered** and returned its green result. The whole probe took 0.60 ms.
- **Adoption:** `bin/seon init --dev default --changed src/seon/cluster.clj test/seon/cluster/adoption_exclusion_test.clj`
  was refused six times by other lanes' in-flight edits: stale head ×2, the "Source changed during development adoption" precheck on
  `src/seon/db.clj`, and analysis errors in `turn.clj`/`flow_test.clj` naming `seon.flow/submit!!`. The seventh attempt succeeded, with
  136.8 s elapsed; the profile is dominated by concurrent test runs. The cluster record is `6ab595b3-b142-5620-b3cc-d876e67f95f3`,
  `call-excluding-adoption` arities are `(2 3)`, and `@program-validity` is nil.
- **After (same probe, installed code, fresh lock and fresh validity atom):** the member did **not** enter. Its answer was the
  unknown-program diagnostic, and its `:seon.error/chain` was `("arming refused after reload")`. The failure path took 1.75 ms. The extra
  cost is diagnostic construction, paid only on failure. The success path adds two `reset!` calls.
- The regression covers each failure class separately:
  - a real partial `load-string` into a scratch namespace (a Var defined, then a throw);
  - arming refusal and record refusal through the real `refused!`;
  - no member entry, with the refusal validating as `:seon.test/not-runnable-error` against the carried projection;
  - recovery once an adoption records.
  It never touches default's lock or Vars.
- clj-kondo is clean on both files.

## Sizes

- `src/seon/cluster.clj`: +103 / −54 (net +49). About 35 of those lines are re-indentation of the moved record and the lock body.
- `test/seon/cluster/adoption_exclusion_test.clj`: +49 / −4.

RESET NEEDED: no.
