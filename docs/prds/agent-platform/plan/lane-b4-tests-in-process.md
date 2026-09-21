---
type: plan
status: first pass (Fable, 2026-09-21) for astra review; clean write follows
created: 2026-09-21
owner: lane B4 — `src/seon/test.clj`, `src/seon/test/*` except `arm.clj`, `test/seon/test_support.clj`, `bin/test*`, `resources/seon/schemas/seon.test*.edn`
tags: [agent-platform, lane-b4, seon.test, seon.test.runner, fixtures, bin/test]
---

# Lane B4 — the test system is the runtime

Inputs read end to end: [the brief](WRITER-BRIEF-2026-09-21.md),
[data pack B4](../research/data-pack-b4-test-system-2026-09-21.md),
[the test-system audit](../research/deletion-audit-test-system-2026-09-21.md),
[the synthesis](../research/synthesis-2026-09-21.md),
[goals and rulings](../research/durable-goals-and-rulings-2026-09-21.md) (§2c, C1, C2, D1, D3, D9, E1, E2, F1),
the archived [test-system PRD](../../steward-platform/plan/test-system-is-the-database-prd-2026-09-17.md),
packs [A1](../research/data-pack-a1-malli-2026-09-21.md) §1/§4b,
[B1](../research/data-pack-b1-publication-2026-09-21.md) §9 rows 241–255,
[C1](../research/data-pack-c1-profiling-2026-09-21.md) §2,
[B2](../research/data-pack-b2-sci-turn-render-2026-09-21.md) §7; the surviving-path
functions and kept runner spans named in §3 below. Three read-only `eval_clj`
evaluations were run against `default` (jvm); their forms and values are §4.

## 0. For the owner: what was dumb, and the simpler way

**What the code does today.** To run a test we copy the working tree into a
run root, publish a second copy of the program into a cached base store, clone
that store once per worker JVM (`test_support.clj:363`), start three worker
JVMs, hand each a task over an EDN wire protocol on stdin
(`runner.clj:1907-2135`), let workers claim members from the run entity with a
transaction function written for a race between processes (`:2454-2714`),
watch them with a liveness watchdog that dumps every child's threads
(`:435-640`), write results to EDN files that a coordinator re-reads
(`:3263-3443`), and when a test is red, launch **another** JVM to re-run it
alone and decide whether the red was a scheduling artifact (`:4308-4500`). The
shell around this is 1,320 lines and a slot lock so the machine does not drown
in JVMs. Beside this whole apparatus, the in-process path the owner actually
wants — select the tests reaching a change, run them on a forked Datahike
branch and a forked SCI context inside the cluster's own JVM, record the facts
— already exists: `seon.test/check` over `run-owned` (`test.clj:1901`, `:576`),
driven from the shell with no JVM by `bin/test-check` (53 lines).

**Why it was the wrong shape.** Three mirrors. (1) The worker base is a
*copy* of the cluster the JVM already holds: `seon.cluster` keeps the
connection and the base SCI context of `default` (`cluster.clj:297-305`); the
fixture rebuilt both from a cloned directory at 4.6 s per JVM. (2) The
"which tests reach my change" answer was computed twice: `seon.fn/gate-sets`
(`fn.clj:1520`) and a 300-line incremental *reach-digest index* on the
projection's cache (`runner.clj:2181-2348`) whose only job is to decide whether
the first answer is still true. (3) Every process mechanism guards its own
cost: the watchdog exists because child JVMs wedge; the confirmation stage
exists because pooled workers share state; the arm.clj copy in the runner
exists because the worker arms itself (`:1724-1848` ≡ `arm.clj:12-136`).
And the one **measured fact nobody looked at**: the stored call graph is
saturated — any seed, even a function with four callers, reaches **1,827 of
2,157 tests** (§4). "Run only the tests reaching my change" has been a full
run wearing a hat.

**The simpler way, as data flow.** A request is a map naming a cluster
connection and what changed. *Changed* is a `since` read over per-function
content digests from the last green basis (16 ms for 100 transactions, §4).
*Reaching* is an AVET lookup of each changed symbol in `:seon.test/reach`, the
set of functions the armed wrappers **observed executing** the last time each
test ran — proportional to the change, not to the program. Members whose
last recorded result is green and unreached are answered from the record.
The rest run serially in this JVM: each takes a Datahike branch off the
cluster's head and a `sci/fork` of the cluster's base context (37 ms,
measured), runs under the declared bound, and its result — counts, failures,
observed reach, basis — is one transaction. The tally is a query over that
run. The platform tier (boot from zero, 110 declared rows) is the one thing
that needs a fresh process, and `bin/test --platform` starts exactly one.

## 1. Goal and the numbers that prove it

| Number | Today (measured) | After | How measured |
|---|---|---|---|
| Tests selected for a one-function change | **1,827 of 2,157** for every seed probed (§4 form 2, 3) | the tests whose recorded reach contains the symbol; expected tens | `(count (seon.test/select …))` on `default` |
| Selection time | 134–293 ms (`gate-sets`, §4) | ≤ 20 ms: `since` (16.5 ms/100 tx) + AVET lookups | `(time (seon.test/select …))` |
| Forward closure of one 30-line test | **2,562** functions (§4 form 3) | its observed executed set | `(:seon.test/reach (pull …))` after one run |
| Fixture per test | 37.0 ms p50 (`test-system-fork-2026-09-23.md:104`) | 37 ms; **first** acquisition 4,648 ms → ~40 ms (no base clone, no projection rebuild) | `test/seon/test/fixture_timing_test.clj`, kept |
| JVMs per gate | 1 coordinator + 3 workers (+1 per confirmation) | 0 new (in-process) / 1 (platform) | process table |
| Granted time budget | 68,105,085 ms over 65 files | ≤ 1,500,000 ms, every declaration numbered | `grep -rho 'seon.test/long-ms *[0-9]*' test` |
| Lines (area) | 11,458 (§9) | **≤ 3,300** | `wc -l` |

Anything over 2 s in this area after landing is a defect: no step remaining
is proportional to the program.

## 2. The data flow of one request

`(seon.test/run request)` — one function, in the cluster's JVM, for an agent
(`my.test`), for `bin/test-check`, and for the platform JVM. Seams in order:

| # | Step | Data in | Computed when | Carried on | Proportional to | Seam |
|---|---|---|---|---|---|---|
| 1 | **basis** | `:seon.db/connection` → `db`; the cluster's last green run's `:seon.test.run/basis-t` (query) | per request | the request | 1 query | `changed-since-green` query shape (`test.clj:61-80`, minus the `published-base-digest` clause) |
| 2 | **changed** | `(db/since (db/history db) basis)` over `:seon.program/analyzed-source-digest` (per-function content digest after B1; today the file digest) → symbols whose digest differs as-of basis vs now; ∪ explicit `:seon.test/changed`; ∪ symbols in `:seon.test/paths` files | per request | a `#{sym}` | changed declarations (16.5 ms / 100 tx / 6,757 touched entities, §4) | `changed-definition-symbols` (`test.clj:668`) |
| 3 | **reached** | for each changed symbol, AVET `[?test :seon.test/reach ?sym]`; tests with **no** `:seon.test/reach` datom (never executed, or new) fall back to `seon.fn/gate-sets` (static, saturated) with reason `:first-run` | per request | `{sym #{reasons}}` | |changed| × index seek | Datahike AVET on an indexed symbol set (`seon.test.edn:2`); `fn.clj:1520` |
| 4 | **eligible set** | reached ∪ named identities/namespaces ∪ platform rows when `:seon.test/tier :platform` ∪ every row when `:all`; minus `:seon.test/fixture`; long rows only with `:seon.test/include-long? true` (reported by name otherwise) | per request | sorted map sym → reasons | |eligible| | `select` (`test.clj:840`), rewritten to ≤ 120 lines |
| 5 | **reuse** | a member is answered from the record when its latest result on this branch is green and its symbol is not in *reached* (step 3 already compared its reach against the change) | per request | `:seon.test/unchanged true` + `:seon.test/recorded-basis-t` | 1 query per eligible member | `latest-results` (`runner.clj:3513`) |
| 6 | **host** | for each executable member, `destroyers` ∩ reach ⇒ `:seon.test.host/isolated-snapshot`, excluded with its cold command | per request | member reasons | 4 owners (§4) × reach | `seon.test/host` (`test.clj:302`), reach now the observed set |
| 7 | **admission** | one `[:db.fn/call admit-run …]` writes the run entity and its members with reasons BEFORE execution | per request | `:seon.test.run/id` | 1 tx | `admit-run` (`test.clj:1280`), minus claims/workers/covered-by |
| 8 | **fixture** | `d/branch!` off the cluster's branch head → `d/connect` under the carried projection → `sci/fork` of the cluster's base ctx | per test | the test's `connection` + `ctx` | O(1) pointer + fork: 37 ms | `with-branched-database` (`test_support.clj:946-981`); `versioning.cljc:212`; `sci/core.cljc:345` |
| 9 | **execute** | `bounded-result` runs the Var on a virtual thread under `(or long-ms 5000)` via `sci.eval/run-test` (SCI) or `runner/run-var!` (JVM); the armed wrappers record executed (symbol, digest) into a per-run thread-local set | per test | captured result + reach set | the test's own work | `test.clj:143`; `runner.clj:655-750`; `interrupt.md`; observation seam: C1's wrapper (`instrument.clj:844-882`) |
| 10 | **restore** | live projection compared before/after; drift named and counted as an error | per test | result | touched keys | `restore-live-cluster-schema!` (`runner.clj:1447`) |
| 11 | **record** | one `[:db.fn/call record-tx completion]`: counts, failure components, `:seon.test/reach` (replace), `run-basis-t`, `completed-tx` | per test | the cluster's datoms | 1 tx | `commit-results!` (`runner.clj:3145`), minus staged writes |
| 12 | **tally** | `(seon.test/tally db run-id)` = `run-results` rendered by `render-ai` text; `bin/test-check` prints it | per request | a string | |members| | `run-results` (`:3505`), `print-recorded-tally!` (`:4517`) shrunk to a render |

Recomputation triggers: a publication or adoption changes digests (step 2
re-reads `since`; nothing is cached); a run rewrites a member's reach (step
11); nothing else stores derived state. **No cache, no digest index, no
snapshot.** Serial execution per the 2026-09-16 ruling (`with-redefs` is
process-wide); concurrency waits for the derived redefinition fact.

**The platform tier** is the same function in a fresh JVM: `bin/test
--platform` runs `bin/seon --root tmp/platform-<id> start platform` (boot from
zero — the cost the owner law exempts), evaluates `(seon.test/run {… :seon.test/tier
:platform})` over its prepl, prints the tally, downs the root. It records to
the cluster it ran on (E1); the global "last green" is a query across
clusters (goals §6 decision 6), never a pointer.

## 3. Reading list — open before editing

| Source | What it guarantees |
|---|---|
| `reference-code/clojure/src/clj/clojure/test.clj:325` `report` (dynamic defmulti), `:353` `do-report`, `:316` `inc-report-counter`, `:710` `test-var`, `:725-737` `test-vars` | the whole capture surface is one dynamic Var and a counters ref; fixtures compose per namespace — nothing else to reimplement |
| `reference-code/kaocha/src/kaocha/type/var.clj:30-46` | running one Var with counters, zero-assertion detection and end-event is 17 lines |
| `reference-code/kaocha/src/kaocha/testable.clj:158-228` `run-testable`, `run-testables` | a collection runs as a `loop` with fail-fast; no pool, no claims |
| `reference-code/kaocha/src/kaocha/api.clj:34-36` | reporter substitution is `with-redefs [t/report r]` |
| `reference-code/kaocha/src/kaocha/result.clj:4-40` | tally arithmetic in 65 lines; kaocha never *fails* a slow test — our bound is the one addition |
| `reference-code/datahike/src/datahike/versioning.cljc:212` `branch!`, `:550` `fork-database` | `branch!` is a head pointer (O(1)); `fork-database` copies every konserve key — used once per JVM for blob-scope tests only |
| `reference-code/sci/src/sci/core.cljc:345` `fork`; `reference-code/sci/doc/interrupt.md` | copy-on-write context; `time-limit`/`:interrupt-fn` is the one evaluation bound |
| `src/seon/test.clj:143` `bounded-result` | the interrupt seam: `FutureTask` on a virtual thread, `await/await!` under `:seon.test/remaining-ms`, cancel-without-interrupt on expiry |
| `src/seon/test.clj:302` `host`, `:218` `destroyers` | destructiveness derives by reach from `:seon.fn/destroys` owners (F1) — keep, feed it the observed reach |
| `src/seon/test.clj:61` `changed-since-green` | the last-green-per-branch query; step 1 |
| `src/seon/test.clj:840-1160` `select` | the selection to SHRINK: keep basis/changed/reached/platform/named/long/fixture/reuse; delete snapshot, input-digest, published-base, covered-by, tested-branch, cross-program reach-digest reuse |
| `src/seon/test.clj:1280` `admit-run`, `:1506` `resolve-test` | admission writer (keep, minus claim/worker keys); resolution by identity from facts (P4) |
| `src/seon/test/runner.clj:111-435` capture, `:360-378` `duration-failures`, `:655-750` `run-vars!`/`run-var!`, `:752-805` markers, `:1090-1179` destructive guard, `:1447-1545` restore, `:3014` `record-tx`, `:3145` `commit-results!`, `:3505-3550` result queries, `:4517` tally | the kept spans; everything else in the file is process machinery |
| `src/seon/fn.clj:1520` `gate-sets` | the static reverse walk, seeds a `#{}` (armed contract refused a vector, §4 form 1); the first-run fallback only |
| `src/seon/program.cljc:178` `test-marker-attributes`, `:193` `test-markers` | the ONE lifting rule for `:seon.test/long`, `long-ms`, `platform`, `fixture` (B1's indexer writes them) |
| `src/seon/sci/eval.clj:3282` `run-test`, `:2218` `base-ctx`, `:2184` `fork-for-turn` | SCI test under `kernel/with-arm`; the cluster's base ctx and how a fork is taken |
| `src/seon/cluster.clj:297-305` | the running instance holds `:seon.boot/cluster-connection` and `:seon.sci.eval/ctx` — the fixture base after this lane |
| `src/seon/instrument.clj:844-882` (C1's seam) | the armed wrapper every call passes through; the reach observation hooks here |
| `test/seon/test_support.clj:946-981` | the target fixture shape, already measured at 37 ms |
| `bin/test-check:33-50` | the no-JVM launcher: resolve advertisement, evaluate `check-request` over the prepl, print, exit |

## 4. REPL protocol

Three read-only jvm evaluations on `default` (pid 10562), 2026-09-21:

| # | Form (abridged) | Value |
|---|---|---|
| 1 | `(seon.fn/gate-sets {:seon.db/db db :seon.fn/seeds ['seon.db/transact!]})` | **refused by the armed contract**: "expected a set, got a vector … Contract: :seon.fn/gate-request" (`instrument.clj:766`) — arming is live |
| 2 | counts + `gate-sets` with `#{'seon.db/transact!}` / `#{'seon.test/host}` | `tests-total 2157`, `tests-without-calls 1`, `tests-long-reason-only 49`, `tests-long-ms 97`, `tests-platform 110`, `tests-fixture 6`, `destroys-owners 4`, `runs nil`, `members-green nil`, `gate-sets-heavy [293.3 ms 1827]`, `gate-sets-light [147.9 ms 1827]`, `branches 3`, `basis-t 536870947` |
| 3 | `gate-sets` for `seon.test/destroyers` (4 callers), `seon.print/text-sink` (4), `seon.id/id` (109), `seon.render.test/render-html` (76), `seon.test-support/with-database` (839); forward closure of `seon.test.duration-test/overrun-…`; `since` 100 tx | `1827, 1827, 1838, 1827, 1827` tests; forward closure **2,562** functions in 992.9 ms; `changed-since-100-tx [16.5 ms 6757]` |

Reading: the static graph is one strongly connected core; every test reaches
it through the fixture and `seon.db`, so every function is "reached" by 85 %
of tests. `runs nil` — `default` has never recorded a run: the current
selection has nothing to be incremental from.

**Before** (lane's first act, records the same three forms in the landing
note). **After**, on `default`:

```clojure
(let [conn (seon.operator/connection "default")]
  (seon.test/run {:seon.db/connection conn :seon.test/identities #{'seon.test.duration-test/overrun-is-captured-and-reported-as-an-assertion-failure}}))
;; → run id, one result, :seon.test/reach a set of tens, elapsed < 100 ms
(seon.test/select {:seon.db/db (seon.db/db conn) :seon.test/changed #{'seon.test/destroyers}})
;; → the tests whose recorded reach contains the symbol; count ≪ 1827; < 20 ms
(seon.test/tally (seon.db/db conn) run-id)             ; a string, byte-equal to bin/test-check's output
(time (seon.test-support/with-database (fn [_] nil)))   ; ≈ 40 ms first and every time
```

Codex reaches the REPL through `bin/mcp-server` (`eval_clj`, jvm, read_only
for probes); a scratch cluster is `bin/seon --root tmp/b4-root start b4`,
downed after. `runtime_status` after every commit is the soundness probe.

## 5. The work, ordered as commits

Each commit leaves HEAD loadable: `clojure -M -e "(require 'seon.test 'seon.test.runner 'seon.test-support 'my.test)"`, then `require :reload` on `default` and one `runtime_status`.

| # | Commit | Deletes / adds | Loadability & probe |
|---|---|---|---|
| 1 | **Launchers**: `bin/test` → ≤ 100 lines (`--platform` starts one scratch JVM; everything else `exec bin/test-check "$@"`); delete `bin/test-fast`, `bin/_test-slot`, `src/seon/test/fast.clj`, `src/seon/test/bounds.clj`; delete `test/seon/test_runner_integration_test.clj`, `test/seon/test_runner_test.clj`, `test/seon/test/runner_test.clj`, `bounds_test.clj`, `published_selection_test.clj`, `publication_test.clj`, `test_preparation_test.clj`, `test_cache_test.clj` | −6,600 | `bin/test-check default` prints a tally; AGENTS.md §5 launcher paragraphs rewritten in the same commit |
| 2 | **Runner cut**: delete `runner.clj` spans 3, 6, 7, 9, 11, 13, 14, 15, 17, 19, 21, 22, 24, 25 (audit §1 numbering, pack §3 spans); `run-vars!` loses `ambient-snapshot`/`ambient-drift`; `-main` deleted (`bin/test:1077` gone in #1); `record-tx` keeps only the admitted branch | −2,900 | `require :reload 'seon.test.runner`; `(seon.test/check-request …)` still answers |
| 3 | **One function**: `seon.test/run` (§6 signature) replaces `run`, `run-owned`, `check`, `check-in-process`, `check-admission`, `host-admission!`, `select-snapshot`; `select` shrinks (§2 step 4); the four `test-input-digest` reads (`test.clj:900,916,1365,2161`) and every `published-base-digest`/`tested-branch`/`covered-by` clause go; `my.test/run` and `my.test/check` call the one function; `plan.clj:870`, `issue.clj:855,1039`, `problems.clj:354`, `render/test.clj:61-62`, `cluster/source.clj:287-293` converted in this commit (B3/B2/B1 files: coordinate — see §8) | −1,400 | `(my.test/check {…})` from an agent's SCI eval returns the same map as `bin/test-check` |
| 4 | **Schemas — RESET NEEDED**: `seon.test.member.edn` loses `worker`, `claim-tx`, `claimed-at`, `host`; `seon.test.run.edn` loses snapshot/worker/input-digest/published-base/tested-branch/covered-by; `seon.test.runner.edn` loses worker-observation/wire families; `seon.test.selection.edn` loses overlay; `seon.test.edn` loses `reach-digest(s)`, `reaches`, `reach-unknown`, `subject`, `usage`, `skipped-count`, `skip-reason`, `acquisition*`, `classpath*`, `jvm-options`, `adoption*`, `deferred`, `long-excluded`, `expired` shapes; adds the entity-map constraint **`long ⇒ long-ms`** | −450 | `bin/seon reset --force` (loses nothing durable); `runtime_status` |
| 5 | **Fixture**: `test_support.clj` — the base is `(seon.cluster/instance name)`'s connection and ctx when the JVM hosts the cluster (every in-process run; the platform JVM too); `create-base`, `clone-directory!`, `replace-directory!`, `populate-published-*`, `with-published-file-database`, the `Held` protocol, `retrying-base`, `acquire/release/close-base!` deleted; `with-fresh-database` = one `d/fork-database` into a memory store per JVM (a `delay`), branched per test; `test_support_test.clj` collapses to the fixture's own class tests | −700 | `(time (with-database (fn [_] nil)))` ≈ 40 ms first call |
| 6 | **Bound**: `duration-failures` reads `(or long-ms time-limit-ms)`; the 49 reason-only rows (§7 table) receive their measured number or lose the marker; `duration_test.clj` asserts the new rule (blank reason and reason-without-number are publication refusals, proven by `transacted!`) | −40 test lines | publication of a `:seon.test/long` without `long-ms` is refused by name |
| 7 | **Observed reach** (after C1's wrapper seam lands; until then step 3 falls back to `gate-sets` and the landing note says so): `commit-results!` writes `:seon.test/reach` from the per-run observation set; `select` step 3 reads it; `reach-digests`/`reach-entries`/`reach-refresh`/`reach-cache` (`runner.clj:2181-2361`) deleted | −300 | §4 "after" forms: selection ≪ 1,827 |
| 8 | **Corpus**: `test_reaching_test.clj`, `test_failure_facts_test.clj`, `test_test.clj`, `selection_test.clj`, `reach_test.clj`, `test_provenance_test.clj`, `test_expiry_test.clj`, `fixture_timing_test.clj`, `declared_reference_test.clj` collapse into `test/seon/test/runner_test.clj` (capture, bound, markers, recording, reuse, tally-is-a-query, destructive guard, restore) and `test/seon/test/selection_test.clj` (basis, changed, reached, named, platform, long, fixture, first-run) — one regression per class; the 5 symbols-as-strings sites in this lane's files (`test_reaching_test.clj:68`) fixed on the way | −2,500 | the reaching set green in process |
| 9 | Landing note; AGENTS.md §5 and the `clojure-testing` skill rewritten to this shape; `docs/seon/issues/` notes whose subject died closed by name | docs | — |

Commits 1–2 and 5–6 need nothing from another lane. Commit 3 touches five
foreign call sites (listed) — one slice, or the callers first convert to
`check-request`'s map, which survives unchanged. Commit 7 waits on C1.

## 6. The one function, and better than the floor

```clojure
(defn run
  "Select, admit, execute in this JVM, record, and answer one test request on
   the cluster its connection names. Selection: explicit identities or
   namespaces; else :seon.test/changed symbols (∪ symbols in :seon.test/paths);
   else every definition whose content digest changed since this cluster's
   last green run. :seon.test/tier :platform adds the declared platform rows;
   :all every eligible row. Green members not reached by the change are
   answered from the record with :seon.test/unchanged. Destructive and long
   members are excluded by name with their command unless :seon.test/include-long?.
   Every member runs under (or :seon.test/long-ms 5000) ms on a branch + ctx
   fork; results and observed reach are facts before the function returns."
  {:malli/schema [:=> [:cat :seon.test/request]
                  [:or :seon.test/run-result :seon.test/selection-error
                   :seon.test/host-error :seon.db.write/error]]}
  [{connection :seon.db/connection ctx :seon.sci.eval/ctx :as request}])
```

`:seon.test/request` = `[:map [:seon.db/connection] [:seon.sci.eval/ctx {:optional true}] [:seon.test/identities {:optional true} [:set :seon.test/sym]] [:seon.test/namespaces {:optional true} [:set :seon.ns/name]] [:seon.test/changed {:optional true} [:set :seon.fn/sym]] [:seon.test/paths {:optional true} [:vector :string]] [:seon.test/tier {:optional true} [:enum :platform :all]] [:seon.test/include-long? {:optional true} :boolean] [:seon.test/time-limit-ms {:optional true} :int]]`.
`:seon.test/run-result` = `[:map [:seon.test.run/id] [:seon.test/results [:vector :seon.test/result]] [:seon.test/excluded [:vector :seon.test/host-report]] [:seon.test/passed? :boolean]]`.
`check-request` (`test.clj:2052`) stays as the text projection `bin/test-check` prints; `my.test/run` and `my.test/check` become one-line callers; `run-owned`'s agent custody is `:seon.db/connection` supplied by call preparation, exactly as today.

Candidates the lane probes first, with the deciding probe:

| # | Candidate | Probe | Decides |
|---|---|---|---|
| A | **Observed reach in the wrapper** (§2 step 3) — a per-run thread-local transient set of `(symbol, digest)` that `bounded-result` binds and `commit-results!` drains | arm one namespace, run the duration test, count the set vs the static 2,562 | if the set is tens, reach and the profiler are one observation (pack C1 §8: 40 ns per armed call) |
| B | Is the static saturation a walk defect? | `gate-sets` over `:seon.fn/calls` only, without `:seon.fn/references`, same seeds | if still ≈1,827, hubs are real and A is the only precise answer; if it drops to tens, keep static reach and A becomes a profiler-only concern |
| C | **`clojure.test/test-vars` in place of `run-selected-tests`** (`runner.clj:1244-1283`, `:725-737` composes fixtures already) | diff the captured event sequences for one namespace | kaocha proves 17 lines suffice (`var.clj:30-46`) |
| D | **Results in one transaction per run** instead of per member | crash a member with a bound firing; is the partial run's record needed? | the crash model says interrupted work never resumes; per-member transactions stay only if the tally must show partial results live |

## 7. Tests — what dies, what collapses, each time escape

**Dies with the machinery** (commit 1): `test_runner_integration_test.clj` (1,513; 24 reason-only + 1 × 1,800,000 ms), `test_runner_test.clj` (1,632), `test/runner_test.clj` (1,324; incl. the `:1052` 42 ms marker fixtures), `bounds_test`, `published_selection_test`, `publication_test`, `test_preparation_test`, `test_cache_test`, `test_expiry_test`, `test_provenance_test`. **Collapses** (commit 8): the rest of `test/seon/test*` into two files. `accretion_test.clj` (412) and `src/seon/test/accretion.clj` (411) are contract generation, not the runner — untouched, and misnamed (issue filed, not renamed here).

**The 49 half-declared markers** (live count, form 2; the pack's 38 is the regex count, and namespace-level declarations lift to each row): after commit 4 a `:seon.test/long` without `:seon.test/long-ms` does not publish. Disposition by owning lane, with the O(program) step named:

| Class | Declarations (pack §4) | O(program) step | Disposition |
|---|---|---|---|
| A — fixture acquisition (20; e.g. `my/test_test.clj:13` 300 s, `cluster/publication_adoption_test.clj:15` 600 s, `test/host_test.clj:15` 300 s) | `create-base` → `projection-from-database` 3,996 ms | **B4 commit 5** removes the base; A1 removes the rebuild | marker deleted; the 5 s default applies |
| A — publication (21; `cluster/boot_test.clj:873,1142,1799`, `publication_*_test`, `source_*_test`, `fn_test.clj:1696`, `cluster_test.clj:56`, `schema/admission_test.clj:126`, `schema_redeclare_test.clj:72`) | `publish!`/`refresh-source!` over `src/` | **B1** (publish a small fixture program, never `src/`) | B1's table; B4 lists them |
| A — cold clj-kondo (`fn/publication_cache_test.clj:9` 600 s) | `fn/index!` | **B1** | — |
| A — SCI acquisition (3: `sci/lazy_acquisition_test.clj:9`, `error_result_test.clj:99`, `error_write_timing_test.clj:80`) | `acquire-program!` | **B2** | — |
| A — whole-image instrumentation (2) | `collect-contracts!` | **A1** | — |
| A — whole-store copy (2) | `store.clj` clone | **A2/B1** | — |
| A — property runs (`render/transcript_test.clj:968`, `print_test.clj:240,264`, `schema/datahike_test.clj:243`, `concurrency_independence_test.clj:1`) | per-trial fixture | **B4**: at 37 ms per trial, 200 trials = 7.4 s | keep with a measured number (`long-ms` = 2× measured) |
| B — test-system process machinery (`test_runner_integration_test.clj:46`, `dev/source_instrumentation_test.clj:80`) | child JVM | deleted (commit 1) | — |
| B — platform tier owned elsewhere (`dev/fresh_operator*_test.clj` ×9, `cluster/boot_test.clj:1463`, `cluster/cohost_boot_test.clj:100`, `cluster/store_test.clj:498,568`, `dev/edit_feedback_test.clj:221`, `bootstrap_drive_test.clj:28`) | boot from zero | **B1/B3** keep in the platform tier with the measured number | B4 provides `run` + the platform JVM |
| D — `shell/jvm_test.clj` 7 × 600 s "Publish the canonical program into a physical store" | publication | **B1** folds into one | — |
| C — genuinely long (`flow_test.clj:1427`, `oversight_test.clj:113`, `config_application_test.clj:147`, `cluster/program_restart_test.clj:1` 67.8 s, `cluster/armed_test.clj:1` 48.6 s, `ai_stream_fold_test.clj:374`, `dev/dependency_cache_test.clj:136`) | real boots, deadlines | keep; each carries its measured number (the two ns-level ones become per-test numbers) | — |

**The default bound is 5,000 ms** (`seon.test.edn:27-29`), ruled here: the
objection (a fresh base costs 4,648 ms) dissolves because no test pays a
base after commit 5. **`:seon.test/reach` and `:seon.test/subject`**: reach is
written by the recorder from observation (commit 7), never declared (E2);
`subject` is an annotation on 0 rows — the attribute is deleted here and its
lifting in `fn.clj:519,653,700,917,938,1031,1378,1394,1458` is B1's
conversion in the same publication. **`:seon.test/platform`** stays a declared
fact lifted by the indexer (110 rows; goals §6 decision 7 — a declaration
with a docstring); the platform tier is a query over it.

**The fixture after the cut** (commit 5): `with-database` = branch + ctx fork
of the hosting cluster; `::extra-schema` unchanged; `::fresh-store?` forks a
second memory store once per JVM for the six blob-scope files
(`blob_test.clj:218`, `ai_stream_fold_test.clj:378`, `cluster/mcp_test.clj:567`,
`cluster/source_database_test.clj:14`, two generated fixtures in the deleted
`runner_test.clj`); `::database-id` (0 callers) deleted. B2's turn-test
collapse gets from this fixture exactly: a connection, a ctx forked from the
cluster's base, a seeded cluster name derived from the database, and
`transacted!`/`program-fn-row`/`apply-config!`/`seed-cluster!` unchanged.

## 8. Done, landing note, stop rules

**Done** when, on `default` after the reset: the §4 "after" forms return the
stated shapes and times; `bin/test-check default` and `(seon.test/tally …)`
print the same bytes; `bin/test --platform` boots one scratch JVM, runs the
110 rows through the same `run`, and exits with the run's verdict; every
`:seon.test/long` row carries `long-ms`; `wc -l` meets §9; HEAD loads after
every commit. Landing note:
`docs/prds/agent-platform/landing/lane-b4.md` — the before/after forms with
values, the commit list with net lines, the reach count of the first
observed run, the platform tier's wall time, and the cold proof still owed.

**Stop** at: a foreign dirty file (`src/seon/cluster.clj`, `src/seon/fn.clj`
are dirty today — commit 3's `cluster/source.clj:287-293` conversion and the
`:seon.test/subject` lifting wait for B1's landing); the C1 wrapper seam not
landed (commit 7 waits; commits 1–6 and 8 do not); an unsettled design —
three options in the note. Unsettled here, for astra and the owner:

| Question | Options (simplest first, recommendation marked) |
|---|---|
| E2 says coverage is the *stored call graph*; the graph is saturated (§4) | (1) **rec.** observed reach recorded by the wrapper supersedes static reach per test after its first run; static remains the first-run seed and the "unknown" floor · (2) keep static, prune hub edges by a declared `:seon.fn/hub` marker (a hand list — banned) · (3) keep static, accept 85 % runs (the owner's D9 is then unmet) |
| Platform tier results | (1) **rec.** recorded on the scratch cluster it ran on, tally printed, root downed (E1; decision 6's query) · (2) copy the run entity into `default` afterwards (a mirror) · (3) run the platform tier against `default` itself (destroys the development root — refused by `host`) |
| Result transactions | (1) **rec.** one per member (partial tallies visible live; matches `record-tx`) · (2) one per run (fewer writes; nothing visible until the end) |

## 9. Size target

| File(s) | Today | Audit floor (46 % cut) | Target | Why below the floor |
|---|---:|---:|---:|---|
| `src/seon/test/runner.clj` | 4,967 | 2,406 | **≤ 1,100** | the audit kept the 305-line reach-digest index and the full recording/result-query spans; observed reach deletes the index, and recording without claims/workers/covered-by/staged writes is ~200 lines |
| `src/seon/test.clj` | 2,182 | ~1,680 | **≤ 700** | one `run`; `select` without snapshot/input-digest/cross-program reuse is ≤ 120 lines; `check-adoption`, `feedback`, `stale*`, `commands` go with the hook's shell path |
| `src/seon/test/cache.clj` + `selection.clj` + `fast.clj` + `bounds.clj` | 928 | 546 | **0** | `input-roots`/`gitlink-digests`/`toolchain-dependencies` (~90 lines) move to B1's `seon.cluster.source`, which is their only production caller (`cluster.clj:1633-1643,1825-1851,2162`); `reaching-tests` duplicates `gate-sets` |
| `test/seon/test_support.clj` | 1,123 | ~1,000 | **≤ 450** | no base clone, no hold protocol (the cluster's connection is not retired mid-run — an adoption replaces its ctx, and a fixture holding the old ctx keeps a valid fork), no file-backed roots |
| shell (`bin/test`, `test-fast`, `_test-slot`, `test-check`) | 1,373 | ~160 | **≤ 160** | `bin/test` ≈ 100 (`--platform` + exec), `bin/test-check` 53 unchanged |
| `resources/seon/schemas/seon.test*.edn` | 1,203 | uncounted | **≤ 800** | accretion schemas (388) untouched; run/member/runner/selection lose the process families; `seon.test.edn` loses 14 shapes |
| **Area total** | **11,776** | ≈ 6,300 | **≤ 3,300** | |
| `test/seon/test*` + `test/my/test_test.clj` (corpus, this area) | 10,080 | ~6,900 | **≤ 3,000** | two suites over one class each; `test_support` counted above |

`arm.clj` (254, A1) and `accretion.clj` (411, contract generation) are
excluded from the area total. Whole-corpus deletions outside this area are
other lanes' rows; this lane's corpus rule for them is one sentence: a
`:seon.test/long` without a number does not publish.
