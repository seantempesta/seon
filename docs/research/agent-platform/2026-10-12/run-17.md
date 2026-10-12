---
title: "Run 17: chain 3 rerun on 4a1fc2e7e: the chain completed unattended in 54 s. Prove ended :satisfied with zero model calls on the table already on main, so no law was ever written. Implement merged a correct seon.fs/intermediate-paths (f54082cf6, [] when root equals target) under a weakened [:cat :any :any] contract that the merge accepted. The last step's branch was deleted with no outside write; Prove's :satisfied branch was not"
type: research
status: complete (lane run17, 2026-10-13); chain done; product correct; contract weak
created: 2026-10-13
---

# Run 17: the chain `seon.fs/an-equal-root-and-target-have-no-intermediate-paths` on 4a1fc2e7e

**Broken first.**

1. The merged contract is `{:malli/schema [:=> [:cat :any :any] [:sequential :any]]}`. It has two bare `:any`
   inputs and no `:seon.schema.admission/exemption`. `seon.cluster.source/accept-merge!` took it as complete (F48).
   The named predicate `seon.fs/path?` exists and `seon.fs/deletion-state` uses it as `[:fn seon.fs/path?]`. The
   agent searched `(seon.schema/registered-schemas)` for "path", found `()`, and settled for `:any`.
2. **No law was written.** Prove ended `:satisfied` 0.8 s after launch with **zero model calls**. Its product test,
   Run 16's table on main (`ccf55fd5f`), already existed and was red at its fork, and that alone satisfied the step.
   The instructions also name a `quick-check` law. Neither the help from `a150c4346` ("a Prove step's product is
   every proof, rows and law") nor anything else could act, because no Prove agent ran (F46b).
3. Prove's `:satisfied` branch `:attempt-d545b4613f42` (and `:base/attempt-d545b4613f42`) was still in `d/branches`
   at 22:48:24. That is 2 min 54 s after its end, and after a `:seon.task/execution` write that woke a pass (F51).

**What went right.** One launch fact and no hand act or wake after it. Implement forked, worked 13 calls, and was
merged and published as `f54082cf6` (`src/seon/fs.clj`, 10 lines added, 6 deleted) 54 s after launch. The merged
body is correct: an equal root and target answer `[]`, a vector, as the intent says. Below the root it keeps the
root first. The last step's branch `:attempt-778d9c897058` was gone by 22:47:23 with no outside write, so F36
(`58d48d234`) holds live. No error was recorded (`seon.problems/open-faults` `[]`).

| Measure | Prove (`3d119f5188da`) | Implement (`88f0b2910d75`) |
|---|---|---|
| Assignment / agent / branch | `d545b4613f42` / `attempt/d545b4613f42` / `:attempt-d545b4613f42` (**kept**, F51) | `778d9c897058` / `attempt/778d9c897058` / `:attempt-778d9c897058` (deleted, with no outside write) |
| Model calls | **0** | **13** (22:45:30.67 → 22:46:14.49) |
| `refine!` / `send-back!` | 0 / 0 | 0 / 0 |
| Law written | **No**: the step was satisfied by the table on main | n/a |
| End | `:satisfied`, outcome `:closed`, at 22:45:30.137 | `:done`, `:merged`, git `f54082cf6`, main commit `6acd633d-4108-…` |
| Relaunches | 0 | 0 (`ea800d326` not exercised: the contract was never refused) |
| Launch → end | 0.8 s | 54 s to publication (22:46:23) |

## Before launching

- Default pid 31841, booted 22:43:48 UTC from `data/source/4a1fc2e7efd1…` (HEAD), and reset. Health `:degraded`:
  only a `seon.cache` discipline warning and one untested-hot warning. The 5 malformed contracts in `seon.fs.jvm`
  (Run 16 F42) are gone. Default was not stopped or reset by this lane.
- Subject `42df482368fc` `:held`; Prove and Implement had no attempts, and `seon.task/ready` was `#{}`.
  `seon.task/obligations` gave Prove `:product #{seon.fs-test/intermediate-paths-lists-the-roots-from-root-to-target-exclusive}`
  and empty `:proofs`. Implement's `:proofs` were that same test. There was no law to own. Run 16's kept branch was
  gone after the reset.
- **Reaching tests, interpreted, on probe branch `run17-probe`** (`:agent-721971f75ee2`).
  `(my.program/tests-reaching 'seon.fs/intermediate-paths)` gates
  `seon.fs-test/concurrent-deletions-treat-a-vanished-path-as-success` and the table, which is tagged to Implement,
  so I excluded it. I ran it with the 7 tests that reach `seon.fs/delete-recursively-impl!`, as in Run 16. Run
  `0996ac6ec552`, all `:interpreted`, 17,170 ms and 6.9 GB (F27 again). Every test was green except
  `seon.cluster.boot-test/start-during-reset`, which was 1/1/1, unchanged from Run 16 (F44). That test is outside
  the gate set, so I launched, the same decision as Run 16. The probe was retired before launch.
- Config: `seon.config/apply!` with `{:seon.config.ai/no-provider false :seon.config.run/max-episode-runs 60}`
  answered `{:converged? false :operations 4}` (C3 again). `seon.config/effective` confirmed both, plus
  `relaunches 2` and model `deepseek-flash`.

## Timeline (UTC, 2026-10-12)

| At | Event |
|---|---|
| 22:45:29.3 | root transacts `[:db/add [:seon.task/id "42df482368fc"] :seon.task/execution :running]`. This was the only hand act. Nothing was written to default until 22:47:23 |
| 22:45:30.137 | Prove's attempt ends `:satisfied`, without a model call |
| :30.674 | Implement call 1: source of `intermediate-paths` and `delete-recursively-impl!` |
| :33.6 | call 2: probes `relativize` and `getNameCount` of equal paths (`1`, one empty name) |
| :36.9 | call 3: `(if (.isEmpty rel) [] …)`. Its probe throws `NoSuchFieldException isEmpty`, because `java.nio.file.Path` has no `isEmpty`. That was the model's mistake, not SCI's |
| :42.1 | call 4: contract `[:=> [:cat :any :any] [:vector :any]]` with `(zero? (.getNameCount rel))`. Output refused: "expected a vector, got a sequence". The same reply's var render says `:contract :missing` (F50) |
| :47.6 | call 5: looks for a Path schema; `seon.schema/search-schemas` does not resolve |
| :50.8 | call 6: `[:sequential :any]`. Probe → `[["/r"] …]`, still wrong; `concurrent-deletions…` shows as `:newly-red` |
| :57.5 | call 7: `(filter #(re-find #"[Pp]ath" …) (map :sym (seon.schema/registered-schemas)))` → `()` |
| 22:46:00.3 | call 8: `my.test/check` changed → 6 pass / 1 fail (the table's root = target row) |
| :05.5 – :11.9 | calls 10-12: `getNameCount` is 1 for the empty path; `.isEmpty` again; `getFileName` |
| :14.49 | call 13: `(if (zero? (count (str rel))) [] …)`. The proofs go green |
| :23 | Implement merged (main commit `6acd633d…`) and published, git `f54082cf6`. **Chain done, 54 s after launch** |
| by 22:47:23 | `:attempt-778d9c897058` deleted with no outside write (F36 holds). `:attempt-d545b4613f42` stays |
| 22:48:12 | cleanup: config restored, subject `:held`. At 22:48:24 Prove's branch is still there (F51) |

## Seon git commits from this run

```
commit f54082cf60705e55674ea9729232cf359aa42bf6
    Merge seon.fs/implement-an-equal-root-and-target-have-no-intermediate-paths from agent attempt/778d9c897058: merged on main as 6acd633d-4108-5381-93ef-c12aff34aa90
    Written: src/seon/fs.clj   Assignment: 778d9c897058
 src/seon/fs.clj | 16 ++++++++++------
 1 file changed, 10 insertions(+), 6 deletions(-)
```

```clojure
(defn- intermediate-paths
  {:malli/schema [:=> [:cat :any :any] [:sequential :any]]}
  [^Path root ^Path target]
  (let [rel (.relativize root target)]
    (if (zero? (count (str rel)))
      []
      (butlast (reductions (fn [^Path parent segment] (.resolve parent ^Path segment))
                           root (iterator-seq (.iterator rel)))))))
```

There was no Prove commit; its product was already on main from Run 16.

## Correctness check

Default's compiled Var is still the pre-merge body until a restart or reload. In the JVM, `/r`→`/r` still answers
`["/r"]`, as in Run 15b. So I graded the merged body, copied verbatim from `f54082cf6` into a JVM local:

| root → target | merged body | type |
|---|---|---|
| `/r` → `/r` | `[]` | vector |
| `/r/` → `/r` | `[]` | vector |
| `/r` → `/r/` | `[]` | vector |
| `/` → `/` | `[]` | vector |
| `/r` → `/r/a` | `["/r"]` | seq |
| `/r` → `/r/a/b`, `/r/` → `/r/a/b` | `["/r" "/r/a"]` | seq |
| `/` → `/a/b` | `["/" "/a"]` | seq |
| `/r/s` → `/r/s/a/b/c/d` | the 4 ancestors, root first | seq |

**Correct on every case.** The empty case answers `[]`, not `nil`, as the intent asks. The non-empty case answers a
seq (`butlast`), which `[:sequential :any]` admits and the only caller reads with `some`. Moving from string length
to "relativize is empty" is sound, because `Path/relativize` of equal paths is the empty path. One case is
unnormalized: `/r/./a` against `/r/a/b` answers `["/r/./a"]`. Its caller, `seon.fs/deletion-state`, normalizes both
paths first, so this is not a defect.

Summary: Prove wrote **no law**: it was satisfied, not rerun. Implement **never** needed `send-back!`. The product
is right; only its contract is weak.

## Grades

- Hand acts after launch: **0**. Wakes: **0**. Relaunches: **0**. Calls: Prove **0**, Implement **13**. The only
  root writes were the launch and, after the chain ended, JVM-mode reads and cleanup.
- Platform: launch → satisfied → fork → merge → publish → delete, unattended, in 54 s. Gap: F51.
- Product: correct, with a weak contract (F48). The law never got a chance (F46b).
- Since Run 16: F40 (a `[:fn …]` contract admitted at the REPL) and F45/F41 (done means merge) were not exercised,
  because the agent never tried a `[:fn]` contract. F36 is now proven live.

## Frictions

| # | Friction (observed) | Owner | Proposed red proof |
|---|---|---|---|
| **F48 (binding, contracts)** | `accept-merge!` accepted `[:=> [:cat :any :any] [:sequential :any]]` on `seon.fs/intermediate-paths`. The law forbids anonymous or incomplete contracts and requires polymorphic boundaries to be justified; `seon.fs.jvm/path?` justifies its `:any` with `:seon.schema.admission/exemption :polymorphic-boundary`. This contract has bare `:any` inputs with no exemption, where `[:fn seon.fs/path?]` was available. The agent's search for a Path schema found nothing, because a named predicate is not a registered schema | the completeness rule used by `accept-merge!` and the REPL (`seon.schema` admission) | in `seon.cluster.merge-test`, a merge carrying a `defn-` whose input schema is a bare `:any` without exemption is refused, naming the input. Red on `4a1fc2e7e` |
| **F46b (binding, Prove)** | Prove ended `:satisfied` with 0 calls, because `seon.turn/step-tests` judges a Prove step by the product tests main already holds. Main held only the table, and the instructions' `quick-check` law was never written. The satisfied verdict reads "the tests tagged to my successor exist and are red"; it does not read "every proof my instructions name exists". After a partial Prove (Run 16), a rerun therefore cannot add the missing proof | `seon.context/step-verdict` / `seon.turn/step-tests` (Prove's satisfied rule) | a Prove step whose instructions name a table and a law, with only the table held on main, is not `:satisfied`. Or Prove's product names its laws as data (a declared proof list on the task), and an absent one keeps it `:working` |
| **F51 (unattended)** | A `:satisfied` Prove attempt's branch `:attempt-d545b4613f42` and its `:base/…` are not deleted. They were seen at 22:47:23 and 22:48:24, the second time after a `:seon.task/execution :held` write that wakes db<->flow. Hypothesis, not verified: `next-actions` deletes only a branch "already merged (head = merge base)". This attempt's head (`6acd630a…`, its proof run) differs from its merge base (`6acd6309…`) and its outcome is `:closed`, not merged, so no rule deletes it | `seon.cluster.agent/next-actions` (`:delete-branch` for a closed attempt) | in `seon.cluster.db-flow-test`, an attempt ended `:satisfied` (closed) has its branch absent from `d/branches` once db<->flow is idle |
| F50 (context) | Call 4's eval reply for `(defn- intermediate-paths {:malli/schema [:=> [:cat :any :any] [:vector :any]]} …)` rendered `:seon.render.var/contract :missing`, with the `[:cat :int]` example. The same reply's call was refused by that contract's output check, so the contract was armed. "Missing" told the agent the opposite of the truth | the var render (`seon.render.var` contract status) | a `defn-` with a complete armed contract renders no `:contract :missing` |
| F52 (context, minor) | The agent had no way from its REPL to find "the contract for a `java.nio.file.Path`": `seon.schema/registered-schemas` does not list named predicates, and the help's `[:fn]` example is `clojure.core/even?`. It cost calls 5 and 7 | the implement help, or a way to list contracted named predicates in `my.program` | a probe: `(my.program/search …)` for `path?` in `seon.fs` answers `seon.fs/path?` with its contract |
| F44 (again) | `seon.cluster.boot-test/start-during-reset`, interpreted, 1/1/1 | as Run 16 | as Run 16 |
| F27 (again) | `my.test/check` of 8 tests: 17,170 ms and 6.9 GB | `seon.test` runner | as Run 13 |
| C3 (again) | `apply!` → `converged? false`, with 4 operations to set and 3 to restore | `seon.config/apply!` | `seon.config/a-reapplied-manifest-converges-with-zero-operations` |
| F47 (again) | `my.agent/retire-branch!` takes `{:seon.store/branch kw}`. `(my.agent/branch)` answers a map `{:seon.agent/branch … :seon.agent/mode …}`, which it refuses, so the two do not compose | `my.agent` | `(my.agent/retire-branch! (my.agent/branch))` retires the caller's branch |

**Lane note (my error).** While grading, I called `(seon.fs/delete-recursively! "/tmp" "/tmp/does-not-exist-run17")`
in the JVM to exercise the caller. It refused ("crosses an intermediate symlink", because `/tmp` is a symlink on
macOS), and the target did not exist, so nothing was deleted. It was the wrong probe to run on a live JVM.

## Residue on default (disposable)

- **Subject** `42df482368fc` is back to `:held` (verified). Prove ended `:satisfied` (`:closed`) and Implement is
  `:merged`.
- **Branches**: Implement's is deleted. **Prove's `:attempt-d545b4613f42` and `:base/attempt-d545b4613f42`
  remain** (F51). `run17-probe` was retired. `:agent-16cf276be9fd` and `:agent-be1c786b7e22` are not this lane's.
- **Config** restored to `no-provider true` and `max-episode-runs 100`, verified with `seon.config/effective`.
- **Shared tree**: `f54082cf6` is on `refactor/agent-platform`. Default's compiled `intermediate-paths` is the old
  body until the next restart. The table is red on default's JVM until then and green on HEAD.
