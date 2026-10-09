---
type: review
status: measured
date: 2026-10-09
lane: opus-review
---

# An independent review of the 2026-10-08/09 window (opus-review, 2026-10-09)

The window covers 641 commits, from 9e0f1cba8 (2026-10-08 00:58 +0900) to 1a45da7ba. I did not read the Astra audit
that ran alongside this one. I checked each claim against the code, the tests and default through MCP `eval_clj`:
disposable agents `opus-review-probe-1..3`, JVM mode read-only. I did not trust the commit messages or the
orchestrator's notes. Measurements note the machine load when they were taken: this machine has 18 cores, and the
load average ran from 7 to 45 during these probes.

## 1. Verdict

**The project is accreting machinery faster than it converges.** The work is careful, measured and mostly correct,
but most of the window went into building verification and orchestration machinery, and very little into the goal
itself (Seon agents fixing the system from inside the REPL, about 10k lines, sub-second operations). The numbers:

- **Source is about 9x over budget and grew.**
  - src: 90,165 → 91,637 lines (+1,472)
  - test: 97,639 → 99,471 (+1,832)
  - script: 5,279 → 5,673 (+394)
  - resources EDN: 16,439 → 17,150 (+711)

  The largest deletion was 55ede54a9, which removed 2,199 lines of the old context generation. The next largest,
  94df1f74a (-429) and f50ef6059 (-230), were much smaller.
- **The paperwork outgrew the code.**
  - Documentation grew by 81,424 lines across 580 files. 250 of the 641 commits touch only `docs/`. There are now
    214 cards.
  - Task entries went from 426 to 575 (+149 net) in about 33 hours.
- **The goal path is barely used.** Only **7 of the 220 commits** that touch src/script carry a `Branch-evidence:`
  trailer. That trailer is the REPL-native landing path, and it is exactly what wave A is meant to make ordinary. All
  7 trailers in the repository's whole history fall in this window. None of the 8 changes reviewed below used it.
- **Ordinary operations, measured on default** (fresh HEAD JVM, disposable agent, load 7-17 unless noted):

  | Operation | Wall | Allocated | Calls | Notes |
  |---|---|---|---|---|
  | trivial `(+ 1 2)`, warm | 66-88 ms | 0 MB | 6,225 | 41 reads, test step 11-15 ms, settle 33-44 ms |
  | trivial, first request of a new agent | 218-1,336 ms | | | custody 68-708 ms |
  | `defn` + `deftest` of a one-line function | 0.69-1.43 s | reported 28-108 MB | 59-60k | 367 reads, test step 394-989 ms |
  | redefining that `defn` (1 reaching test) | 1.49 s | | 54,653 | test step 1,268 ms (load about 45) |
  | `(my.test/check {})`, nothing changed | 174 ms | 25.9 MB | 83,593 | result reused |

  So a trivial form is sub-second. **The core loop (define a function, prove it) is not:** it costs 0.7-1.5 s for one
  test whose body takes milliseconds. That is the same P1 "whole program per test" cost slow-alloc.md measured.
- **default was unhealthy before this morning's restart.** At 10:13 JST, pid 89239 had been up about 2 h 20 min and
  showed:
  - health `:faulted`;
  - 18 failed tests, 115 unknown, 6 open faults, 24 errored receipts;
  - heap 3.30 GB used of 4 GB, cache 502/512 MB, 811 platform threads.

  Root restarted it a few minutes later for memory pressure. After the restart: 248 platform threads, 1.16 GB heap.
- **Store:** 830,951 files, 5.9 GB on disk. store-gc.md rehearsed 4.21 GB → 0.17 GB. `bin/seon status` still says
  "No completed maintenance receipt records a collection of this root", so the 03:00 UTC collection that
  store-gc.md recommended has not completed.

The wave-A plan is correct about what blocks the goal. But it fixes each block by adding a mechanism (rebase,
snapshot, temporary-index commits, templates per slot, adoption inside checks), and a large share of those mechanisms
exists only because default runs an older commit than the checkout, or because a store's identity is derived from its
path. See §3.

## 2. Deep review of eight changes

**How I chose them.** Risk is the blast radius times how little the tests prove:

- changes to the one REPL path (38ff88494, 86659ca5b, 0f319c838);
- changes that write source files, or decide which tests prove a change (e984eb0ab, c5826eb62);
- the new platform proof and check machinery that every later landing will trust (fa92a2503,
  daaa38c0e+60f530cef);
- a new stored fact and eight dials (006fadb61).

5c6b6885e and 7fc0ebb2a are small and local, so I read them only enough to leave them out.

### 2.1 38ff88494: a refused run answers its values (supported, minor gaps)

- **Correctness.**
  - `seon.turn/settle-batch-refusal!` now answers its receipts under `:unrecorded` when the run did not close with
    them, including the refused-refusal branch.
  - `seon.cluster.agent/evaluate-source!` answers the refusal alone only when no form was evaluated.
  - Every receipt already carries the "Not settled: …" error text, so an unrecorded value is never presented as
    recorded.
  - I found no bug.
- **Laws.**
  - The function it touches is about 110 lines and still builds `:seon.turn/deletion-proposals-edn` with `pr-str`
    under `*print-length* nil`, a second printer copy that predates this commit. It also hand-joins error text with
    `str/join`.
  - The reply has no key saying the values are unrecorded. An agent only finds out by reading the error text.
- **Tests.** `seon.cluster.evaluate-source-test/a-run-whose-open-is-refused-still-answers-its-values` asserts on data
  (`["true" "3"]` and a refusal type). Good. The commit removed the test's `:seon/tasks` tag when it retired the task,
  as the law asks, and records no run id.

### 2.2 e984eb0ab: write-back rebases by declaration (correct alone; does not compose with the commit gate)

- **Correctness of `seon.cluster.source/conflicts`.** A conflict is a head digest that is neither the base digest nor
  the branch digest. That covers the cases I checked:
  - a sibling deletes what the branch changed (head `:absent`);
  - both sides add the same identity differently;
  - both sides make the same change (no conflict, the splice is a no-op).

  The new test asserts the merged digests and the exact conflict map. It is a good data test, 20 s long, declared.
- **Composition gap (code-derived, not reproduced live).**
  - Rebase writes declarations the branch never held (the sibling's) into the file.
  - `write-back!` transacts nothing back onto the branch.
  - `seon.cluster.branch-evidence/file-evidence` compares **every** declaration in the committed file with the
    branch's digest (`::differing`), and `bin/lane commit --branch-evidence` refuses a file differing from the branch.

  So a rebased file is refused by the REPL-native commit path in exactly the situation rebase exists for: main's
  analysis lags the checkout. The lane must then fall back to `bin/seon check`. A2 is "landed" but cannot carry a
  wave-A acceptance run end to end. **Probe to confirm:** rebase-write a file a sibling changed, then run
  `bin/lane commit … --branch-evidence AGENT` on it. Expect exit 2 with `::differing` naming the sibling's
  declarations.
- **What should own this.** The whole rebase exists because default's facts lag HEAD (plan A0). If default tracked
  HEAD, or the branch were rebased in the database (Datahike `merge!`, the owner the laws name for merging), there
  would be nothing to rebase in a file.
- **Cost.** 218 ms / 358 MB warm for one docstring, from the commit's own numbers. That is about 10x what writing one
  declaration needs.

### 2.3 86659ca5b: `own-bindings` (supported)

- **Correctness.**
  - `own-bindings` replaces the armed per-Var predicate `own-binding?` with one pass over `:sci/var-bindings`.
  - The old predicate also required a non-nil binding. In SCI, `sci.impl.vars/context-binding` returns a `Binding`
    object or nil, never a nil value (`reference-code/sci`, `sci.impl.vars/context-binding`), so the semantics are
    the same.
- **Live effect, verified.** A warm `(+ 1 2)` makes 6,225 armed calls. slow-projection.md measured 53,428 before
  this change.
- **Laws.** The contract `[:=> [:cat ctx] :map]` is loose. It should be `[:map-of sci-var binding]`. Minor.

### 2.4 c5826eb62: the closure walk (correct; the gap moves rather than closes)

- **Correctness.** `seon.fn/closure-walk` replaces two walks with one. Selection stays at seeds plus direct callers,
  `(take 2 levels)`, which matches the old `call-reach`. Interpretation now covers the tests that reach the change at
  any depth.
- **The task it closes is only half closed.** A file proof three compiled callers away now *would* see the branch
  definition, but it is never *selected*. The new test asserts `(= [] (functions/gate-set before changed))`. So
  changing `seon.ref/span-candidate` still runs no test unless the agent names one. The owner's one-level ruling
  (20a87b1d1) is a deliberate trade-off, but the commit message reads as if the proof now protects the change.
- **Laws.** The new test edits code with a regex, `(str/replace-first … #"^(\(defn-?\s+\S+)" …)`, where the laws
  require rewrite-clj or edamame.
- **Cost.** Every gate-set call now does the full transitive walk to answer two levels. Walk times stay in the tens of
  ms (the commit's p90 is 57 ms), so this is acceptable.

### 2.5 0f319c838: `enter-namespace!` (contradicted on cost; a platform side effect from agent code)

- **The cost claim is contradicted.** The commit says entry happens "once per context", that the "cost follows the
  namespaces an agent enters" and that the "fork stage [is] unchanged". The marker `::entered-namespaces` lives in
  `::kernel/program-snapshot`, and `regenerate-agent-context!` builds a **new** snapshot atom from the base's on
  every request (`seon.sci.eval`, the `(atom (update @(::kernel/program-snapshot base) …))` branch). So the marker
  does not survive. A controlled probe on agent `opus-review-probe-3`, HEAD 1a45da7ba, load about 7:

  | State | `(+ 1 2)` wall | reads | test step | calls | alloc |
  |---|---|---|---|---|---|
  | after a `defn` + `deftest` in a scratch namespace | 66 ms | 55 | 12.5 ms | 7,577 | 0 |
  | first form inside `seon.ref-test` | **1,531 ms** | 164 | **1,370 ms** | 29,511 | 22.8 MB |
  | `in-ns` back to the scratch namespace | 231 ms | 104 | 156 ms | 9,107 | 21.7 MB |
  | every later `(+ 1 2)` there | **196 ms** | 104 | 141 ms | 9,070 | 0 |

  Agent `opus-review-probe-2` reproduces it: 205 ms, 104 reads, 21.7 MB for each `(+ 1 2)` inside `seon.ref-test`.

  Visiting a test namespace once makes **every later request of that agent 3x slower**, even in another namespace.
  The 1.37 s test step for a form that defines nothing comes back with no `:seon.turn.loop/tests` key, so the agent
  cannot see why. The likely mechanism: the entered namespace's copied Vars count as the agent's own changes in the
  test step. Not confirmed.
- **A platform side effect.** `host-namespace!` `require`s the test namespace into default's shared JVM. `test` is on
  default's classpath (`java.class.path` contains `"test"`). Verified:
  - `(filter …-test (all-ns))` was `[seon.id-test]` before my probe and `[seon.ref-test seon.id-test]` after.
  - default's log shows test namespaces being compiled there: a reflection warning at
    `seon/cluster/agent_test.clj:2073`.

  So one agent's `in-ns` changes the compiled platform for every agent and every later `run-all-tests`. The source
  is the published archive, not uncommitted source, so this is not a correctness breach. But it is a host mutation
  done from agent code, against "a branch never changes the platform".
- **Laws.** It adds state to an atom (`swap! snapshot update ::entered-namespaces`). `host-namespace!`, which it now
  calls on every evaluation, is still uncontracted.
- **Tests.** `seon.sci.library-aliases-test/an-agent-in-a-test-namespace-writes-a-proof-with-its-helpers` proves that
  the helpers resolve. Nothing bounds the per-request cost. A cost assertion through `seon.profile/measure` on a
  second request would have caught this.

### 2.6 fa92a2503: `my.test/check-platform` (works; heavy; reads defaults as a fallback)

- **Correctness.** It snapshots the published archive's roots, writes the branch through `write-branch!`, commits
  through a temporary `GIT_INDEX_FILE` (I read the code: HEAD, the shared index and the working tree are untouched),
  runs `bin/seon check --at`, and records the results on the branch keyed by the branch's reach digests. The
  snapshot is deleted in `finally`.
  - The evidence proves the change on the *published* commit plus the branch, while `bin/lane commit` lands it on
    HEAD. Every commit the published commit lags HEAD is unproven interaction.
- **Laws.**
  - `seon.test.platform/check-bound-ms` merges `config/defaults` under the effective configuration and silently uses
    the defaults when the configuration read fails (`(when-not (:seon.error/at effective) effective)`). The laws say
    `seon.config/defaults` is not a cluster's configuration, and that a failure is loud.
  - A production regex `#"[0-9a-f]{40}"` was added without owner permission.
  - `my.test/check-platform` is a 55-line orchestration in `my.*`, where the laws say "keep `my.*` thin".
- **Cost.** By the commit's own numbers: `write-branch!` 385 ms / 618 MB, and the check 98-148 s under load. The
  eval request blocks on `.waitFor` for the whole check (bound 930 s). 337b923c0 had to raise the MCP bridge timeout to
  make it reachable at all.

### 2.7 daaa38c0e + 60f530cef: template stores, and adoption inside the check

- **What it fixes.** Each check no longer publishes the whole program onto an empty store. The warm check measured
  36.8 s, against 156-183 s before.
- **It is a workaround for a producer defect.** A store's identity is derived from its canonical path
  (`seon.cluster.store/datahike-configuration`, `nameUUIDFromBytes`). That one decision forces:
  - fixed slot roots `data/check/slot-<n>`;
  - **one template per commit and slot**, so two 60 s builds and two 80 MB copies per base;
  - in store-gc.md, rewriting 1,330 head records by hand to open a clone.

  Storing the id instead of deriving it from the path would delete all three.
- **The second-order defect showed up within 15 minutes.** The template's `default` cluster kept the base's program,
  so a test the candidate adds was "not eligible". 60f530cef patched that by adoption. Any other way a resumed store
  differs from a fresh one (agents, runs, faults and cache facts left by the template JVM) is the same class, and
  nothing tests for it.
- **The 60f530cef proof does not test its behavior.**
  - `seon.operator-test/a-checks-adoption-answers-its-failure-as-data` only asserts that `adopted!` with no JVM
    returns a diagnostic. "The check runs a test the candidate adds" has no regression.
  - The commit itself says that `bin/seon check` runs the *running* operator, so neither commit's check exercised its
    own operator code. The same is true of 923c1d54d and 337b923c0 ("live after default runs this commit").
- **Laws.**
  - `free-lock`, `with-template-lock` and `pruned-templates!` are contracted with `:any`.
  - Retention by directory mtime is acceptable.
  - The sweep of dead roots checks pid liveness and `(pid, start-instant)`, so it is safe.
- **Amortization.** A base is the commit default runs. Default restarts several times a day (24 commit messages in
  the window mention restart), so each restart costs two template builds of about 60 s at the next check of each
  slot.

### 2.8 006fadb61: COST-REG slice 1, stored member cost

- **Correctness.** `seon.test/measured-body` gives the body a fresh `seon.batch/sink` and reads the thread's
  allocation and wall time around it. That works. The stored numbers are noisy against a warm measurement, by the
  commit's own table:

  | Test | Stored | Warm |
  |---|---|---|
  | seon.ref-test/a-missing-heading-reply-names-the-nearest-heading | 19.4 MB / 48.9 ms | 0.98 MB / 1.1 ms |
  | my.program-test/source-shows-the-docstring-once-inside-the-source | 21.7 MB / 22.0 ms | 14.4 MB / 2.6 ms |

  The 64 MB and 1,000 ms floors absorb that, so slice 2 will mostly catch only catastrophic regressions.
- **It worsens the reply's honesty.** "The member's calls no longer add to the enclosing request's sink" (the commit's
  known gaps). The reply's `:seon.cost/calls` already excluded the test step (P6,
  `seon.batch/a-request-cost-counts-only-the-evaluation-arm`), and now excludes it by construction. No task records
  this.
- **Laws.**
  - It adds **eight dials**, seven of them for slices 2-3 that do not exist yet. That is accretion ahead of code: a
    restriction or dial should name the failure it prevents now.
  - Each default is declared twice, as `:seon.config/default` in `seon.config.test.cost.edn` and again in
    `config/default.edn`: a second source of defaults. Six schema files already do this, but this commit widens it.
  - The 5,000 ms test bound in `seon.test/bounded-result` is still hard-coded.

## 3. Patterns across the window

1. **Default lags HEAD, and machinery grows around that.** Write-back checks bytes against the *published* commit's
   digests (platform-step.md table). Rather than keep default near HEAD, or key analysis off the checkout, the window
   built:
   - write-back rebase (2.2);
   - check-platform's snapshot of the published archive (2.6);
   - "live after restart" commits whose proofs are red until the restart (923c1d54d, 337b923c0, 60f530cef);
   - a branch-evidence path that the rebase cannot pass.
2. **Workarounds where a producer fix exists.**
   - Store identity derived from the path: templates per slot, and head rewriting.
   - Request cost that counts one thread (P6): every cost report in the overnight batch was a floor, until
     slow-alloc.md found it.
   - Ambient projection Vars (`seon.schema/the-projection-travels-ambiently`): profile cells that report proc
     lifetimes as work.
3. **Proofs assert the mechanism, not the behavior.** Examples: 60f530cef (a diagnostic, not "a new test runs"),
   0f319c838 (helpers resolve, no cost bound), c5826eb62 (interpreted, but not selected). Checks run the old
   operator, so an operator change is never proven by its own check.
4. **Cost claims in commit messages are not re-measured after landing.** 0f319c838 claims a one-time entry cost;
   measured, it is paid on every request (2.5). publish-bisect.md showed that platform-step.md's "doubled
   publication" was contention: the orchestration runs enough parallel JVMs (load 12-45 on 18 cores) that single
   timings are unreliable, and lanes act on them.
5. **Paperwork outpaces code.**
   - 81k lines of docs and 250 docs-only commits against +1.5k src.
   - +149 tasks.
   - Commit messages averaging about 50 words and running to 300, each one a landing note in itself.

   The REPL-native path the plan exists for carried 7 commits.
6. **Health is read and then not acted on.** default ran `:faulted` with 18 red tests and 115 unknown. what-broke.md
   classified 15 reds at HEAD, and most remain open. Heap reached 3.3-3.4 GB of 4 GB within about 2.3 h of uptime,
   and the cure was a restart.
7. **Contracts are getting weaker at the edges.** `:any` in the new operator functions, `:map` returns, and
   uncontracted helpers such as `host-namespace!` called on new hot paths.

**What the orchestration is doing badly.**

- It launches more parallel lanes than the machine can time honestly.
- It accepts "landed, live after restart" as done.
- It lets each wave-A gate grow its own mechanism, without asking whether one producer fix would make several of them
  unnecessary.
- It files findings as tasks faster than it closes them.
- It has not yet run the single acceptance the plan defines ("one wave-B fix taken red to committed only through
  `eval_clj`, `write-branch!`, `check-platform`, `--branch-evidence`"). That run would have exposed 2.2 and 2.5
  immediately.

## 4. Top five recommendations

| # | Recommendation | Cost | What it removes |
|---|---|---|---|
| 1 | **Run the wave-A acceptance now and gate all new mechanism on it.** One Opus lane takes one small wave-B fix red → committed only through the REPL path, and files every friction. No new check, rebase or template machinery lands until it passes. | 0.5-1 day, one lane | Unverified compositions such as 2.2's rebase against branch-evidence |
| 2 | **Close the A0 lag at its producer.** Either start default from HEAD after every landing batch (the incremental `start --head` exists, about 10-15 s ready), or derive write-back's file basis from the analysis of the bytes on disk. Then delete the published-archive snapshot path and decide whether rebase is still needed. | 0.5 day decision + 1 day | A large share of 2.2, 2.6 and the "live after restart" class |
| 3 | **Store identity as a stored value, not the path.** Change `seon.cluster.store/datahike-configuration` so a store's id is read from its config, not from `nameUUIDFromBytes(path)`. Then one template per commit, any root, and clone-and-open with no head rewriting. | about 0.5 day (Opus, boot-critical) | Templates per slot, slot-fixed roots, store-gc's head rewriting |
| 4 | **Fix the 0f319c838 regression and add the missing cost bound.** Keep `::entered-namespaces` across regeneration (or enter on the base), make sure entered Vars are not counted as the agent's own changes, and add a `seon.profile/measure` assertion on the *second* request after an `in-ns` into a test namespace. Decide whether agents may load test namespaces into default's JVM, or only bind them in SCI. | 2-4 h, Opus | A 3x slowdown on every request of any agent that touched a test namespace |
| 5 | **Make the cost loop honest, then cut P1.** Make the reply's cost cover the whole request across threads, test step included (`seon.batch/a-request-cost-counts-only-the-evaluation-arm`), and stop excluding member sinks. Then give one Astra lane the "defn + one test = 0.7-1.5 s / 367 reads / 60k calls" case: the target is the fork + one eval + one transaction it should cost. Pair this with a daily net-deletion quota on src, because 91.6k against 10k is the real gap. | 1-2 days, Astra; quota is policy only | Hidden cost, and the main barrier to sub-second define-and-prove |

Also limit lane concurrency so the load stays below the core count while anyone takes timings. That costs nothing,
and today it is what makes cost evidence trustworthy at all.

## Verification limits

- **2.2:** the composition gap comes from reading the code. I did not run a lane commit.
- **2.5:** the mechanism behind the 141 ms test step is inferred, not traced.
- **Timings:** they were taken under varying load (7-45), and I give the range. The first set was lost when root
  restarted default mid-probe (pid 89239 → 3503 at 01:13 UTC), and I redid every probe on the new JVM.
- **Not done:** I did not run `bin/seon check` or any test JVM, and I did not record tasks. The task files are owned
  by the Astra audit lane, so the findings in 2.2, 2.5, 2.6, 2.7 and 2.8 are listed here for root to file.
- **Disposable agents:** I retired the branches of `opus-review-probe-1..3` with `my.agent/retire-branch!`. Each
  answered `:retiring? true`.
  - **A further finding:** each retirement request cost 1.4-2.5 s, of which 1.05-2.1 s was a test step, 96-134k calls
    and 247-334 reads, for a request that defines nothing.
  - `seon.ref-test` is now loaded in default's JVM because of the 2.5 probe.
