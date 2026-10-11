---
title: "Post-restart verification of 4dc20e21a on default, 2026-10-11"
type: research
status: evidence
created: 2026-10-11
---

# Post-restart verification of 4dc20e21a on default, 2026-10-11

Lane `post-restart-verify`. Default ran archive 4dc20e21a after `start --head` and a reset (root the only agent,
`:seon.config.root/land? false` read live through `seon.config/effective`). Question: does the code landed since
c53c9306e work on the live default? The commits are f43646710, a8c2c170a, 232e55f87, 08d384c53, 5fcbf5ed8, 85a4f18e0,
e2462c3ca and f68dd4086, plus 4f81411fa (astra-fix-c: `seon.db`, `seon.sci.eval`), which also landed in that range.

**Answer: no code regression found.** Every red that was new against
[revert-after.edn](revert-after.edn) passes in an isolated JVM booted from 4dc20e21a, so the code itself works. The same
tests fail only on the live root, and filling the live root's memory blocked the parity run:

1. **Default is under memory pressure. RESTART NEEDED.** About 290 test members run from root, plus four probe
   tests, raised the old generation from 535 MB live at reset to 3.01 GB after a full GC. From 08:38Z every new
   evaluation is refused (`seon.sci.kernel/acquire-arm`; enter 3.0 GB, resume 2.36 GB).
   - Evicting every `seon.cache` kind freed 420 MB and left 2.59 GB, so most of the retention is outside `seon.cache`.
     The cache then refilled to 2.75 GB.
   - The live histogram held 9.0 M `datahike.datom.Datom` (360 MB), 17 M Object[] (876 MB) and 4.5 M byte[] (437 MB).
   - Data added to the existing task `seon.test/a-full-tier-on-one-jvm-retains-what-its-members-built`.
2. **13 tests are green on a fresh JVM and red on the live root.** Filed as
   `seon.test/a-test-green-on-a-fresh-jvm-is-green-on-the-live-root`.
3. **`seon.test/change-reds` refuses a test that only the branch holds.** A branch-only red deftest makes the turn's
   `:reached-red` answer `seon.test/select`'s `:seon.test/identity-unresolved` refusal on `:cluster-default`. Filed as
   `seon.test/change-reds-judges-a-test-main-does-not-hold-as-not-red-there` (f68dd4086).

## 1. Proofs and reaching tests on the live default (as root, `my.test/check`, compiled)

**Reaching run 98e264a4b01d** checked 53 tests reaching the changed functions, in 26.4 s, and allocated 8.6 GB. The
changed functions: `my.program/nearest`, `seon.cluster.source/accept-merge!`, `red-test-verdicts`,
`seon.cluster.status/cluster-row`, `render-ai`, `snapshot`, `seon.context/task-done?`, `seon.error/nearest-calls`,
`nearest-names`, `reply-value`, `seon.sci.forgive/markup-end`, `scan`, the four `seon.test.runner` member functions,
`seon.test/change-reds`, `member-result`, `run-request`, and `seon.turn/land-end!`, `reached-red`,
`record-reached-red!`, `root-execution`, `test-change-reds`, `test-step`.

The run had 46 green and 7 not green:

| test | here | revert-after |
|---|---|---|
| `seon.error-test/a-guessed-schema-key-is-answered-with-the-nearest-registered-schemas` | red | (new; filed red by 4dc20e21a) |
| `seon.sci.forgive-test/a-stray-closer-keeps-tokens-apart` | red | red |
| `seon.test-provenance-test/runs-are-distinct-immutable-and-linked-atomically` | red | red |
| `seon.test-provenance-test/verified-requires-the-subject-positive-assertions-and-exact-program` | error | error |
| `seon.test-test/recording-distinguishes-run-replay-from-a-new-event` | red | red |
| `seon.test.request-acquisition-test/a-member-installs-its-program-unobserved-by-the-calling-evaluation` | error | error |
| `seon.test.request-acquisition-test/members-of-one-request-share-its-acquired-program` | red | green; green again alone in run 030844e13f0f, so order-dependent |

**Named run a134d0981e00** checked every test of the namespaces the commits named as proofs: `seon.turn-continue-test`,
`seon.problems-test`, `seon.test-failure-facts-test`, `seon.error-test`, `seon.sci.forgive-test`,
`seon.cluster.merge-test`, `my.test-check-test` and `seon.cluster.status-test`. That is 151 tests, long ones included,
in 97.1 s, allocating 10.0 GB with 44.8 s of GC in the window. 129 were green.

Every proof the commits named was green:

- `seon.turn-continue-test/root-without-a-branch-row-lands-in-the-clusters-world` (a8c2c170a)
- `seon.test-failure-facts-test/failure-readers-use-the-structured-claims` and the probe rows of 08d384c53/85a4f18e0
- `seon.error-test/a-guessed-name-is-answered-with-the-nearest-real-calls` and
  `seon.sci.forgive-test/tool-markup-among-code-is-dropped-at-read` (5fcbf5ed8)
- the merge and turn tests that f68dd4086 turned green
- the `seon.cluster.status-test` rows (232e55f87)

The 22 tests that were not green:

| class | tests |
|---|---|
| filed red on purpose (4dc20e21a) | `my.test-check-test/a-reused-check-answers-a-run-id-it-recorded`, `seon.error-test/a-guessed-schema-key-is-answered-with-the-nearest-registered-schemas` |
| red in revert-after too | `seon.cluster.merge-test/a-require-in-the-agents-user-namespace-lands-with-its-change`, `seon.sci.forgive-test/a-stray-closer-keeps-tokens-apart`, `forgiving-forgiven-text-changes-nothing`, `seon.test-failure-facts-test/a-changed-reach-member-retracts-only-itself`, `an-unchanged-re-record-emits-no-datom`, `seon.turn-continue-test/provider-progress-has-its-own-bound`, `read-results-arrive-before-done` |
| green in revert-after, red here | `seon.cluster.merge-test/a-function-and-its-schema-land-as-one-unit` (error), `a-green-proposal-lands-on-main-and-disk-in-one-call` (error), `seon.error-test/a-core-fault-wakes-the-configured-escalation-owner-root-by-default`, `an-unattributable-fault-without-a-throwable-is-delivered`, `an-unattributable-throwable-goes-to-the-escalation-owner`, `attribution-is-a-lookup-ref-or-nothing`, `normalization-is-total`, `recurrence-counting-does-not-require-a-notification-threshold`, `the-message-points-at-the-fact-it-explains`, `the-storm-is-bounded-by-the-signature-count`, `seon.test-failure-facts-test/an-oversized-actual-settles-into-a-blob`, `seon.turn-continue-test/a-green-end-lands-its-merge-without-root` (error), `a-task-that-ends-green-sends-root-one-merge-request` (error) |

The 13 green-before losses:

- **Same result on a second live run.** All 13 were red again by themselves in run 030844e13f0f.
  `a-green-proposal-lands-on-main-and-disk-in-one-call` was red alone in run fd86c9f920e6.
- **Same result through the prepl form.** Five of them failed the same way through the `seon.test/run` form that
  `script/seon/interpreted_census.clj` sends to the cluster handle (run 52aee1f1f212). So `my.test/check`'s SCI caller
  is not the cause.
- **Green in an isolated JVM at the same commit.** `bin/seon check --at 4dc20e21a` with the 13 tests and
  `--include-long` passed: run 6458986e6bf3, 13 executed, 67 pass, 0 fail, 0 error, 21.0 s of tests and 41.1 s in
  total.

The 13 are therefore not code regressions. Their outcome depends on what the live root holds. revert-after ran on
isolated fresh roots, so it could not see this.

- **Error tests.** `attribution-is-a-lookup-ref-or-nothing` is a pure `seon.error/normalize` call, yet it finds a
  `:seon.error/agent` that the request never named. A world member is supplied from the caller's world, as it is for
  the escalation tests that mail no one.
- **Merge tests.** These answer "The store at …/data/store holds no branch :agent-…": the proposing agent's branch,
  read by `seon.cluster.source/prepare-merge!`'s `compare-branch`. A probe deftest, interpreted on agent `pv-probe`'s
  branch, found that branch present right after `propose-merge!` and in the store `land!` reads. Its `land!` answered
  no refusal there.

Filed as `seon.test/a-test-green-on-a-fresh-jvm-is-green-on-the-live-root`.

## 2. Guessed names and tool markup (fresh agent `pv-guess`, SCI)

- `(seon.task/current)` → "Unable to resolve symbol". The reply also carries:
  - `:seon.error/in-namespace`, the public names of `seon.task`;
  - `:seon.error/nearest`, eight calling forms: `(seon.cluster.source/current store)`,
    `(seon.context/current-state database agent-id)`, `(seon.schema/current-projection)`, …
- `(my.cluster.source/write)` → `:seon.error/nearest` has eight calling forms with destructured arguments:
  - `(seon.cluster.source/write-back! {connection :seon.db/connection, accepted :seon.source/accepted, root :seon.fn/root})`
  - `(my.program/write-back! …)`
  - `(my.program/write-branch! …)`
  - and five others.
- `(+ 1 2)`, then a line holding `</invoke>`, then `(* 3 4)` → values 3 and 12. The first reply carries
  `:repairs [[2 :dropped "</invoke>"]]`.

All three work as 5fcbf5ed8 intends.

## 3. Parity run: blocked

`seon.competency/start-run!` for agent `parity-slug-2` was refused at `:setup`. The agent's first request was refused
for memory pressure (finding 1). Default needs a restart before the bench run can be repeated. The procedure is
unchanged:

- settings `{:seon.config.ai/no-provider false :seon.config.run/max-episode-runs 15}`, deepseek-flash, `land? false`
  live;
- `start-run!`, then `await-runs` and `grade` from root's JVM REPL.

Nothing was launched, so nothing was paid.

## 4. Startup: ready-ms 19.4 s against 5.1–5.3 s

The time goes to the boot layer `:seon.source/current`, from 162 ms to 16,922 ms. That layer publishes the inputs
whose bytes differ from the published program (`seon.cluster/refresh-source!` over `changed-source-paths`). The other
boot phases: launch 28.8 s, adopt 3.9 s, `:capture-ms` 7.8 s, and no program-class hit, because e2462c3ca re-keyed the
class cache by `script/`.

| start | archive | inputs changed since the previous start | `:seon.source/current` | ready |
|---|---|---|---|---|
| 10:33 | c1230efc3 | few | 0.5 s | 5.1 s |
| 11:05 | f51240f34 | 5 files | 5.3 s | 8.2 s |
| 12:08 | c53c9306e | 24 files | 13.8 s | 16.7 s |
| 15:28 | 4dc20e21a | 36 files, 4 schema resources among them | 16.9 s | 19.4 s |

The cost follows the changed inputs, about 0.4 s per file. The 5.1–5.3 s figure was a start with almost nothing to
publish. This is not a regression; the open task `seon.cluster/adoption-readopts-what-the-jvm-loaded` already owns
it. It is still above the ten-second start law.

## Smaller observations

- A probe `(vec (keys (registry/roster …)))` mistake printed `#unprintable[clojure.lang.PersistentVector
  java.lang.ClassCastException]` instead of the error. A lazy failure while printing is shown as unprintable rather
  than as an error value.
- `my.test/check` right after a `deftest` in the same request answers `:my.test/after-recording`, and the step's
  result arrives in the next reply. This is as documented.

## Changed paths

- `resources/seon/tasks/seon.test.edn`: two new tasks; today's numbers added to
  `a-full-tier-on-one-jvm-retains-what-its-members-built`.
- This note.
