---
title: "Card PLATFORM-STEP: A branch proves a host-bound change through the same test step"
type: reference
status: active
created: 2026-10-09
---

# PLATFORM-STEP: A branch proves a host-bound change through the same test step

Owner (2026-10-09): "the same auto test interface could in theory run just those affected tests". Today a branch
cannot prove a host-bound or platform change. The compiled platform keeps running on every branch (CLAUDE.md "The
program is entities"), so an agent's proofs of such a change stay red. Only Claude lanes can prove one, through
`bin/lane commit --test`, which runs `bin/seon check`: 74-178 s for one test namespace. Owner: "it shouldn't take
74 seconds to run a single test". Measurements: `docs/research/agent-platform/2026-10-09/platform-step.md`.

## Expected cost and the gap

Proving one changed host-bound function with its reached tests is proportional to two things: compiling the changed
namespaces and their dependents, and running the reached tests. That should take seconds; one warm test body takes
2-6 ms. The check takes **113 s uncontended** for 14 tests that run in 16 s. The other ~97 s is independent of the
tests:

- 61 s publishing the whole program onto an empty store. These facts are identical for every check of the same base.
- 15 s launching the JVM.
- 12 s rehearsing publication and adoption on default.
- 9 s for the archive and a private copy of the analysis cache.
- 4-5 s of teardown on the answer path.
- 6 s for config, SCI and the flow graph.

Floors:

- **(a) A fresh JVM per change, with cached classes, a store cloned from a per-base template, and only the diff
  adopted: about 25-35 s plus the tests.**
- **(b) A warm platform JVM per base commit that reloads only the changed namespaces: seconds.**

## What exists

- **The test step.** `seon.turn/test-step` runs after every eval request that evaluated a form. It runs the held
  task's proofs (`run-proofs`), then the tests reaching the change (`run-reaching`, through `seon.test/run` with
  `:seon.test/changed`), bounded by the dial `:seon.config.test/step-time-limit-ms`. Tests it leaves unrun become
  `:seon.turn.loop/queue` (the tests plus the `:seon.source/commit-id` they were selected on).
  `seon.turn/continue-queue` runs that queue on later passes.
- **Branch evidence today.** A branch-changed host-bound declaration is never interpreted (`seon.sci.eval`
  `host-bound-row?`). Its tests run the compiled Var, so they prove the old code.
  `seon.cluster.branch-evidence` reports such declarations as `:seon.source/host-bound`, and `bin/lane commit
  --branch-evidence` then exits 3 ("throwaway check required").
- **The check.** `seon.operator/check!` has three steps:
  - `candidate-revision!` builds a commit object from HEAD plus the working-tree paths, using a temporary index.
  - The rehearsal runs `connected!` publish and adopt on disposable branches of the running JVM. `--at REV`
    skips it.
  - `candidate-tests!` takes an OS-lock slot (dial `:seon.config.operator/check-slots`). It archives the
    candidate under `data/check/<sha>-<pid>` and boots a throwaway JVM onto an empty store. It runs
    `bin/test-check` over that JVM's prepl, then terminates the JVM by exact identity and deletes the root.
  - The answer is a summary line, not test evidence: nothing returns to the branch.
- **Write-back.** `my.program/write-branch!` takes any `:seon.fn/root`. `seon.cluster.source/write-back!` refuses a
  file whose bytes are not the ones the base commit analyzed (`:seon.fn.file/digest`). Those bytes are the
  **published** commit's archive (`data/source/<published-sha>`), not HEAD's. This was verified on 2026-10-09 and
  root used the seam by hand in d0c42078b.
- **Evidence identity.** `seon.test/reach-digest` digests a test's source, its transitively reached definitions and
  its named schemas from the database. The write-back proves that the candidate file reindexes to exactly the
  branch's definition digests. So a result computed in another JVM over the written candidate has the same reach
  digest as on the branch, and `seon.test/verified?` can reuse it with no new identity.

## Data flow (target)

```
eval request on agent branch B changes host-bound declarations D
 → seon.turn/test-step: proofs + reaching tests selected on B (as today)
 → members whose reach includes a branch-changed host-bound declaration are `:seon.test/host-bound` pending
   (they cannot prove anything in this JVM), the rest run here as today
 → the pending set + B's commit id go to the agent's queue (:seon.turn.loop/queue, accreted with a platform flag)
 → platform run (one at a time per process root; a newer queue for the same agent supersedes a not-yet-started one):
     snapshot = clone of data/source/<published-sha> (cp -c; or the warm JVM's own source root)
     my.program/write-branch! {:seon.fn/root snapshot}   ; proves equivalence, answers written paths + digests
     candidate commit = published-sha + written paths (git hash-object/commit-tree, temporary index, as
       seon.operator/candidate-revision! does with a base parameter)
     run the pending tests in a JVM loaded from the candidate
     answer the seon.test/run result value (members with reach digests), not a summary line
 → recorded on B as an ordinary :seon.test.run through seon.test's recording writer, its provenance naming the
   candidate commit (accreted :seon.test.run/candidate-sha)
 → the agent's next reply: the test step answers those tests from their record (unchanged reuse) and shows
   them as it shows any executed result; a run finished between requests wakes the agent (open question 3)
```

## Decisions

- **No new run entity.** Pending work is the agent's queue, which lives in its Flow proc state and can be re-derived
  from B and its red or unknown tests. Finished work is the ordinary `:seon.test.run` and its members on B.
  The candidate commit is provenance on that run.
- **Supersede without killing.** A running platform JVM is never interrupted. Its result is recorded against its own
  candidate digests. If B moved on, `verified?` refuses reuse on its own, because the reach digest no longer
  matches. A queued run that has not started is replaced by the newer one.
- **Bounds and dials.** One platform JVM per process root: the existing `:seon.config.operator/check-slots`, set to 1
  for agents. Its test bound is `:seon.config.operator/check-bound-ms`. Its boot bound is the operator's boot
  bound.
- **Awaited exit.** The slot is held until the JVM's exact `(pid, start-instant)` has exited, as
  `candidate-tests!` does today. A timeout records the named missing terminal event as the run's error and keeps
  the slot until exit.
- **Lanes use the same tool.** `bin/lane commit --branch-evidence` accepts a host-bound changed declaration when
  current platform evidence on the branch covers every test reaching it. A lane runs the platform step on its
  branch, then commits. `bin/seon check --test` stays for `bin/`, `script/`, boot, configuration, dependencies,
  schema resources and publication code.

## What disappears

- Exit 3 "throwaway check required" for ordinary host-bound functions.
- Claude-only proof of platform functions.
- The check's summary-line answer as the only record of a throwaway run, replaced by evidence on the branch.
- At slice 3: per-check whole-program publication, and the private analysis-cache copy for warm runs.

## Cost per change

| configuration | per change | what it is proportional to |
|---|---|---|
| today (`check --test`) | 113 s + tests | the whole program |
| slice 1 (`check --at` on a published-base candidate) | about 100 s | the whole program; correct, but slow |
| slice 2 (template store per base commit) | about 25-35 s + tests | JVM launch + diff adoption |
| slice 3 (warm platform JVM, diff reload) | seconds | changed namespaces + their dependents + reached tests |

## Slices

0. **Overlap the rehearsal with the throwaway JVM** in `seon.operator/check!` (`script/seon/operator.clj`, unheld,
   ≤15 min). Start `candidate-tests!` in a future before `connected!`, deref both, and refuse on either. Both
   proofs are kept and about 12 s is cut from every lane commit. The proof is an existing operator test plus one
   `bin/seon check --test` timing row against the measured one.
1. **First agent-facing slice: `my.test/check-platform`, synchronous.**
   - Clone the published archive to `tmp/`, then call `write-branch!` into the clone.
   - Build a candidate commit with the published sha as its parent.
   - Run `bin/seon check --at <candidate> --test <pending tests>` as a declared effect.
   - Bring back the `seon.test/run` value. This needs `bin/test-check --edn` to print the run-result rather than
     the tally.
   - Record that value on B. Afterwards `bin/lane commit --branch-evidence` accepts it.
   - Proof: a task whose proof is red only because its function is host-bound turns green on the agent's branch
     through `my.test/check-platform`.
2. **A template store per published commit.** The first boot of a base saves its store under
   `data/check/template/<sha>`. Each check clones it (`cp -c -R`, 3.9 s measured) and adopts only the diff,
   which cuts about 53 s.
3. **A warm platform JVM and an asynchronous step.**
   - One pre-started JVM per published commit, whose source root is the snapshot.
   - Each run writes the branch's files, and the development adoption path reloads the changed namespaces
     (`require :reload`).
   - Before the next branch, the snapshot's files are restored to the base and reloaded.
   - `seon.turn/test-step` enqueues the work, and a Flow `:io` proc owns the platform JVM and the queue.

## Open questions for root

1. **Which base?** The snapshot must be the published commit, not HEAD, or `write-back!` refuses with
   `:file-changed`. The candidate is then "published + B" and drops HEAD commits that default has not adopted. Is
   that acceptable for agents, with lanes rebasing through git, or does the step need a three-way merge of the
   written files onto HEAD?
2. **Is cross-JVM evidence admissible?** The design reuses a result computed in another JVM because its reach
   digest matches. The recording writer must accept a run whose execution was not in this JVM, with candidate-sha
   provenance. Is that within the evidence rules ("Require current positive proof"), or must merge re-run it?
3. **How does an asynchronous result reach an idle agent?** A finished platform run between requests could be a
   wake (a message from the platform proc), or the agent could see it only at its next request. Today
   `continue-queue` runs only on a later pass.
4. **How is the warm JVM (slice 3) kept clean?** Restore-and-reload after each branch trusts `require :reload` to
   undo `defonce`, protocol extensions and registered schemas. Boot, schema, configuration and publication changes
   always need a fresh JVM. Is a path-free classifier for those (by declaration: host-bound boot layer, schema
   resource) acceptable, or should every host-bound change take the slower fresh JVM until slice 3 proves it?
5. **Should `check-slots` be split?** Lanes and agents share it today, and R1 waited 64.7 s for a slot. Should agents
   get their own dial, or are lane checks also agents' platform runs once slice 1 lands?
6. **Should empty-store publication be bisected first?** It doubled in two days, from a 36.7 s throwaway boot on
   2026-10-07 to 67.7 s today. Slice 2 removes it from checks, but every `nuke` and fresh install still pays it.
