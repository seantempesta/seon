---
type: landing
status: S3a d77a9461a (adopted), S3b 389d2d265, S3c 548d7b6f4 committed, code only (owner load rule); no regression has run; S3b/S3c unadopted (hook paused); fault-delivery item 1 stopped (needs program.cljc)
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

## Update after test.clj was released (S3b 389d2d265, S3c 548d7b6f4)

The owner load rule applied: code and lint only. No `bin/test-check`, no `bin/seon init`, and no namespace load in default
(reloading `seon.test` there would redefine default's Vars outside adoption). **Nothing below has been loaded or run.**

### Broken or unproven first

- **Nothing in S3b/S3c has loaded.** clj-kondo is clean, which does not prove it loads. There is also an open question about the new named
  schemas `:seon.cluster/program-change-lock` and `:seon.cluster/program-validity` (`seon.cluster.edn`): they name predicates that
  `seon.cluster` registers when it loads. `seon.flow.edn` names `seon.flow/atom-reference?` the same way. But `seon.test` now
  arms `bounded-result` against the cluster schemas, and that armed contract must compile in the packaged projection. Root verifies it at adoption.
- **Agent SCI evaluations are not gated. Seed execution does not exist yet.** The §2b ordered seeds have not landed; the only "seeds" in
  `test.clj` are selection seeds. Gating evaluations needs `src/seon/sci/eval.clj`, which is not in this lane. It is also a real design choice:
  - holding the read lock across an agent evaluation makes adoption wait up to that evaluation's time limit, then refuse after 5 s;
  - the smaller alternative checks `program-validity` at evaluation entry, but that never excludes an adoption that starts mid-evaluation.
  This needs a root ruling before any edit.
- S3c records provenance **per run, at admission, read at the writer.** An adoption between admission and a member's execution is
  still recorded under the earlier commit. S3a/S3b make that member run the newly recorded program or stop unknown, so the member
  runs correctly, but the recorded provenance is stale. Per-member provenance would add a member attribute and a write per member.
- **Every green recorded before 548d7b6f4 lacks `:seon.test.run/loaded-commit-id`, so it reads unknown and runs once more.** That is the
  design's rule ("old records lacking proof are unknown"). It costs one full re-execution of reused greens on default.
- Resolution now runs on the body thread inside the exclusion. So it counts against the request bound's join, and `resolve-ms` is
  measured inside that thread.
- Fault-delivery item 1 (root's follow-up) is **stopped, not started.** Declaring an expected fault for
  `seon.cluster.agent-test/disarm-has-a-declared-loud-turn-completion-backstop` needs a new test marker. `seon.program/test-marker-attributes`
  (`program.cljc:209`) is a hand list in a file this lane does not hold. `seon.test.edn` and `agent_test.clj` would also change.
  Without that marker, counting member-branch faults turns that test red. The member→branch fact itself is small, about 15 lines.
  It is best written on the admitted member row through `run-batch`'s recording connection, because `member-result` holds no run id.
  Needed from root: `program.cljc` (marker list), `seon.test.edn`, `test/seon/cluster/agent_test.clj`.

### What changed

- **S3b** (`src/seon/test.clj`, `src/seon/cluster.clj`, `seon.cluster.edn`, `seon.test.run.edn`, 3 test files):
  - `bounded-result lock validity test-symbol resolve! …` takes the exclusion explicitly. It calls `resolve-test` on the body thread
    *after* taking the read lock, so no adoption can replace, delete or recreate the Var between resolution and entry.
  - A member refused because of `program-validity` returns `:seon.test.run/stopped :program-unknown` (a new enum value). `run-batch` commits
    no outcome for it or the members after it, and stops the run with that cause. Those members read `:seon.test/result-unknown`, never red.
    This replaces the `failed` mapping at the old `test.clj:184`.
  - The transitional 2-arity `call-excluding-adoption` is deleted; `member-result` passes `@@program-change @@program-validity`.
  - The existing `bounded-result` callers in `test_runner_test.clj` and `one_request_test.clj` now pass a fresh lock and validity atom.
- **S3c** (`src/seon/test.clj`, `seon.test.run.edn`, `one_request_test.clj`):
  - `admit-run` reads the cluster's adopted `:seon.source/commit-id` at the writer and stores it as `:seon.test.run/loaded-commit-id`.
    This is base provenance; the tested world's identity stays the member reach digest.
  - Selection reuses a green only from a run that names a loaded commit.
  - The loaded-drift check now reads the recorded commit once.

### Sizes

- S3b: `test.clj` +82/−65, mostly the moved resolution and the re-indented body; `cluster.clj` +13/−18; `seon.cluster.edn` +8;
  `seon.test.run.edn` +1/−1; `adoption_exclusion_test.clj` +38/−1; the two other test files +2/−1 each. Gross additions total 146;
  net source is about +20.
- S3c: `test.clj` +17/−10; `seon.test.run.edn` +2; `one_request_test.clj` +11/−1.

### Verification root must run, serially, after one restart

1. `bin/seon init --dev default --changed src/seon/cluster.clj src/seon/test.clj resources/seon/schemas/seon.cluster.edn resources/seon/schemas/seon.test.run.edn test/seon/cluster/adoption_exclusion_test.clj test/seon/test/one_request_test.clj test/seon/test_runner_test.clj`
   (not needed if the restart boots from HEAD). Expect the adoption to record, with `@seon.cluster/program-validity` nil.
2. `bin/test-check default --ns seon.cluster.adoption-exclusion-test`: three deftests (the S3a exclusion, the failure paths, and
   resolution-inside-exclusion plus program-unknown).
3. `bin/test-check default --test seon.test.one-request-test/evidence-earned-by-another-loaded-program-is-never-reused`
   (declared long, 30 s; add `--include-long` if the request needs it).
4. `bin/test-check default --test seon.test.one-request-test/a-body-outliving-its-bound-is-released-only-after-it-exits`
5. `bin/test-check default --test seon.test-runner-test/a-huge-execution-failure-becomes-bounded-text`
6. `bin/test-check default --ns seon.test.one-request-test` (the whole namespace; it exercises `member-result`, `run-batch` and admission).
7. Probe: runtime status shows no `:seon.dev.mcp/program-unavailable`. A fresh request records `:seon.test.run/loaded-commit-id`
   equal to the cluster's `:seon.source/commit-id`.

## What changed in S3a (commit d77a9461a)

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
