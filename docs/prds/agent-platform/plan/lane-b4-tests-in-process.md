---
type: plan
status: implementation specification; proof gates stated below. SUPERSEDED IN PART by README §4 1.3d (tests run as agents run, ruled 2026-09-22): the independent capture/acquire/context/thread lifecycle and worker ownership below are deleted from the plan; commit 4 landed `678009fcd` (one `seon.test/run` on the acquisition entrance), commit 5 (machinery deletion) is running; 1.3d commit 6 rewrites this spec to the installed seams. Selection, per-member evidence, fixture semantics, recorder and actual-exit stand
created: 2026-09-21
owner: lane B4 — `src/seon/test.clj`, `src/seon/test/*` except `arm.clj` and `accretion.clj`, `test/seon/test_support.clj`, `bin/test*`, `resources/seon/schemas/seon.test*.edn`
tags: [agent-platform, lane-b4, seon.test, seon.test.runner, fixtures, bin/test]
---

# Lane B4 — the test system is the runtime

**Owner rulings (2026-09-23) — the test system is built from the agent system.** "The goal is have all tests be written by agents so having the testing system be built from the same agent system seems like a requirement. All the resources and mechanisms and rules we expect them to follow should be in line with allowing us to generalize and optimize the testing and make a simple interface that they are familiar with. I think just by schema declaration we should be able to inject the testing versions of whatever the agents need to do their tests." And: "Tests should just be simple to define -- just functions with schemas really. If they request a db we can hand them a branch … shared branches … unique cases fast to prep (ms to fork and load the env) … prep all the envs in advance and then quickly execute them … normal defn and deftest conventions." Therefore: a test is written by an agent through the same REPL entrance and entry checks as any function; its Malli schema declares what it needs (database, connection, agent, world, external effects such as the model completion function or a capability), and the runner injects exactly that — real system values, with test versions only for external effects — never fixtures, cleanup, hand-built rows or redefined Vars. Worlds are data built through the system's own writers, cached by content, shared by every test that can share them, forked in milliseconds for tests that write, prepared ahead of execution.

Source anchors and historical measurements refer to HEAD `209a6652a` (working tree;
`src/seon/cluster.clj` and `src/seon/fn.clj` carry another lane's uncommitted
edits and are cited from `git show HEAD`). §4 preserves exact historical probes and separates acceptance scenarios. No runtime proof is claimed by this specification; the implementation session must establish a fresh baseline.

## 0. For the owner: what was dumb, and the simpler way

**What the code does today.** To run one test the launcher copies the working
tree into a run root, publishes a second copy of the program into a cached base
store, clones that store once per worker JVM (`test/seon/test_support.clj:363`
`create-base`), starts three worker JVMs, feeds each a task over an EDN
protocol on stdin (`src/seon/test/runner.clj:1907-2135`), lets workers claim
members through a transaction function written for a race between processes
(`:2454-2714`), watches them with a liveness watchdog that dumps every child's
threads (`:435-640`), exchanges results through staged files (`:3263-3443`),
and re-runs a red test in yet another JVM to decide whether the red was a
scheduling artifact (`:4308-4500`). The shell around it is 1,373 lines with a
slot lock so the machine does not drown in JVMs. Beside all of that, the
in-process path already exists: `seon.test/run` (`src/seon/test.clj:541`),
`run-owned` (`:576`), `check` (`:1901`), and a no-JVM launcher that evaluates
one form over the live cluster's prepl (`bin/test-check:43`).

**Why it was the wrong shape.** Three mirrors of state the JVM already held.
(1) The worker's base store is a *copy* of the program the running JVM already
holds open (`src/seon/cluster.clj:297-305` keeps the cluster connection and its
SCI ctx); the copy cost 4,647.8 ms on first use, 3,995.96 ms of it rebuilding a
projection the database value already carries (`docs/prds/steward-platform/research/test-system-fork-2026-09-23.md:94-97`).
(2) "Which tests reach my change" was computed twice — `seon.fn/gate-sets`
(`src/seon/fn.clj:1520` at HEAD) and a 300-line reach-digest index
(`runner.clj:2135-2440`) whose only job is deciding whether the first answer is
still true. (3) Every process mechanism guards its own cost: the watchdog
exists because child JVMs wedge, the confirmation stage because pooled
workers share state, the arm.clj copy (`runner.clj:1724-1848` ≡
`src/seon/test/arm.clj:12-136`) because a worker arms itself.

**The simpler way, as data flow.** A request is a map naming one cluster
connection and a policy. *Changed* is a `since` read from each candidate
test's own last green basis over B1's per-definition content digest. *Reaching*
is the stored reverse call graph, read once for the changed set. A candidate
whose last recorded execution is green, terminated, and whose reach saw no
digest change is answered from the record. The rest run serially in this JVM:
each takes a Datahike branch off the immutable program commit supplied to the request in the store the
JVM already has open (a head pointer, `reference-code/datahike/src/datahike/versioning.cljc:212`)
and a copy-on-write `sci/fork` of the cluster's base context
(`reference-code/sci/src/sci/core.cljc:345`), runs under its declared bound
on its own thread, and records counts, failures, termination and basis as one
transaction. The tally is a query. Boot-from-zero regressions are the one
thing that needs a fresh process; `bin/test --platform` starts one.

## 1. Goal and the numbers that prove it

| Number | Today | After | How measured |
|---|---|---|---|
| Tests selected for a one-function change | 1,827 of 2,157 for six seeds, 1,838 for `seon.id/id`; calls-only 1,142–1,716 (§4 historical graph probes) | the static reach of the change, minus members whose per-test evidence proves reuse; **not predicted** — measured at landing | `(seon.test/select …)` on `default`, count and ms |
| Runs recorded on `default` | 0 (§4 probe 1: `runs nil`) | one run entity per request | query |
| Fixture, first and later | first 4,647.8 ms, later p50 37.0355 ms (fork note `:94-95`, a file-store base) | measure first and later acquisition separately, including projection/context preparation; historical branching and the ≈650 ms residual hypothesis do not establish replacement cost | `test/seon/test/fixture_timing_test.clj`, kept |
| JVMs per request | 1 coordinator + 3 workers (+1 confirmation) | 0 new; 1 for `--platform` | process table |
| Bound declarations | 146 `:seon.test/long` rows, 49 without `long-ms` (§4 probe 1) | 0 without a number | probe 1's query, expected 0 |
| Area lines | 11,776 (§9) | ≤ 3,300 stretch, ≤ 4,200 provisional ceiling | `wc -l` over §9's paths |

Anything over 2 s in this area after landing is explained by algorithm in the
landing note, or it is a defect.

## 2. The data flow of one request

`(seon.test/run request)` is the one request owner, in the cluster's JVM, for an
agent (`my.test`), for `bin/test-check`, and for the platform JVM. `select`
(pure, over a database value) and `tally` (a query) remain separate functions
`run` calls.

| # | Step | Data in | Computed | Carried on | Cost is proportional to | Seam |
|---|---|---|---|---|---|---|
| 1 | capture | explicit execution connection → one immutable `db`/commit, matching carried projection and acquired ctx; separate named recording connection | per request, once | the request | O(1) reads | `seon.db/carried-projection` (A1); `sci.eval/base-ctx` `src/seon/sci/eval.clj:2218` |
| 2 | eligibility | policy `:named` → identities/namespaces; `:platform` → `[?t :seon.test/platform]` (110 rows); `:all` → every row; `:incremental` → step 3 ∪ outstanding obligations (red, interrupted, admitted-never-completed, never-run) | per request | `#{sym}` | named: O(named); platform/all: O(rows) | `select` `test.clj:840`, shrunk |
| 3 | changed | for each candidate, resolve the last green member's immutable **execution commit**, independently of the recording transaction. Use `since`/`as-of` only after proving that execution commit is an ancestor in the current lineage; otherwise compare retained declaration/input signatures, or report unknown and execute. Group candidates by execution commit; changed = differing `:seon.program/definition-digest` values (B1), namespace bindings and explicit `:seon.test/changed` | per request | `{basis #{sym}}` | O(datoms since the oldest basis) + O(distinct bases) | `changed-since-green` `test.clj:61-99` (already per test); `changed-definition-symbols` `:668` |
| 4 | reached | `gate-sets` over the changed set: `:seon.fn/calls` ∪ `:seon.fn/references` reverse walk | per request | `#{sym}` | visited reverse-graph edges plus returned memberships (today ≈ 1,827) | `src/seon/fn.clj:1520` HEAD |
| 5 | schema / input change | a `:seon.schema/key`/`edn` datom since the basis, or a changed non-program input, is **unknown**, never unchanged: today `select` refuses (`test.clj:986-990`); after B1's per-path input rows it selects the declared consumers | per request | refusal or `#{sym}` | O(datoms since basis) | B1 seam |
| 6 | reuse | one batched query of the latest member per candidate from the explicit recording authority, retaining its original execution commit; reused iff the §2a row holds | per request | `:seon.test/unchanged true` + original bases | O(candidates) in ONE query, not one pull per symbol (`runner.clj:3536` today) | `latest-results` `:3513`, batched |
| O3a (steps 2–6) | acceptance selection — B4 owns the `seon.test/select` change specified in [D1 Stage 4](lane-d1-isolation-merge-writeback.md#5-stage-4--gating) | named merge delta, explicit task tests, cluster custody and required eligibility including declared-long members | reuse content-valid member evidence even for explicitly changed seeds, using `:seon.test.member/reach-digest` (`f3a4b33d9`); remove the unconditional reached-seed reuse veto | complete required set, positively covered with no remaining/excluded member; refusal is never empty success | changed/reached graph plus member evidence; no execution or writes for fully covered acceptance | existing B4 selector/evidence owner; D1 consumes it, never duplicates the dependency walk |
| 7 | host | `destroyers` (`test.clj:218`, four owners live) ∩ static reach ⇒ `:seon.test.host/isolated-snapshot`, excluded with its command; **never** derived from an observation's absence | per request | host reports | 4 owners × reach | `host` `test.clj:302` |
| 8 | admission | `[:db.fn/call admit-run …]` writes the run and members with reasons before execution; retains the existing execution-owner exclusion until process-wide serialization is proven; run facts on separate branches alone cannot serialize one JVM | per request | `:seon.test.run/id` | 1 tx | `admit-run` `test.clj:1280`, minus claims/workers/covered-by |
| 9 | fixture | `d/branch!` off the captured program commit → `d/connect` under the carried projection → `fork-cluster-ctx` when the test is SCI-resolved | per test | connection + ctx | pointer + roster update (`versioning.cljc:212-235`); fork is a map update (`core.cljc:345`) | `with-branched-database` `test_support.clj:946-981`; `fork-cluster-ctx` `:615` |
| 10 | execute | `bounded-result` runs the Var on its own virtual thread under `(or long-ms time-limit-ms)`; C1's wrapper records distinct `(symbol, definition-digest)` values under the admitted member observation carried into all owned asynchronous work | per test | captured result + observation | the test's own work; observation is set insertion per armed call | `test.clj:143-196`; `runner.clj:655-700`, `:1244-1283` |
| 11 | terminate | join the body thread under the request bound; `terminated-tx` asserted only after exit | per test | member facts | O(1) | §2c |
| 12 | record | one `[:db.fn/call record-tx completion]` per member: counts, failure reports, began/ended, terminated, reach observation, tested basis | per test | cluster datoms | 1 tx | `record-tx` `runner.clj:3014`, `commit-results!` `:3145` minus staged writes |
| 13 | tally | `(seon.test/tally db run-id)` renders `run-results` (`:3505`) through the schema pair; `bin/test-check` prints it | per request | text | O(members) | `print-recorded-tally!` `:4517` becomes a render |

Reuse crosses requests through content-keyed evidence and the existing derived caches.
A latest admissible green member with matching current reach digest and input evidence
reuses its recorded result and original provenance (`src/seon/test.clj:655`, `:835-868`;
`src/seon/test/runner.clj:902`, `reach-digests`; landed `f3a4b33d9`). Missing evidence,
loaded-source drift, a non-green/unfinished member or changed inputs do not reuse green.
Today explicit reached seeds still veto reuse (`src/seon/test.clj:855`); O3a removes
that veto only when the complete named obligation has content-valid evidence.
The host walk `destructive-reach` memoizes through the carried projection’s LRU cache,
keyed by program revisions plus revisions of declared edge attributes
(`src/seon/test.clj:263-297`); a missing key walks again and an error evicts the entry.
Neither cached reach nor past observed calls certify test completion or host isolation.
These are source observations (2026-09-23), confirmed in committed HEAD as well as the
working tree; `src/seon/test.clj` has unrelated in-flight edits. No runtime proof is claimed.

Group admitted Vars through the existing namespace fixture owner: once fixtures run once per namespace, each fixtures per member. Preserve hooks and SCI resolution. JVM globals remain process-wide; keep current isolation and restoration for tests that mutate them. Admission on one branch does not exclude another branch or running agents. Existing host isolation/serialization remains until the cross-request and adoption proofs establish an equivalent boundary.

Authority for O3a: D1 §2e (`7523dd510`), 2026-09-23; Astra D1 §2e review #1.

### 2a. Reuse evidence, per test

| Recorded per execution (member + run entity) | Source |
|---|---|
| `:seon.test.member/symbol`, `reasons`, `completed-tx`, `terminated-tx`, `began?`, `ended?`, `pass/fail/error-count`, `failures`, `error` | `resources/seon/schemas/seon.test.member.edn:1-20` |
| `:seon.test.run/id`, `at`, `basis-t`, `branch`, `selection-tx`, `policy`, `include-long?`, `identities`, `namespaces` | `seon.test.run.edn` (attributes at `:13-52,93,120`) |
| the test's own `:seon.program/definition-digest` as-of the tested basis | B1 (`lane-b1:222`) |
| `:seon.test/reach` — the OBSERVED executed set (after C1); absent before | `seon.test.edn:2` `[:set {:seon.db/index true} …]` |

"Unchanged" for one candidate means ALL of: its latest admissible member from the explicit recording authority has
`completed-tx`, `terminated-tx`, `began? ended?` true, `pass-count > 0`,
`fail-count 0`, `error-count 0`, no `error` ref (`green-members` `test.clj:759-774`);
its own definition digest matches the recorded immutable execution commit and current program; no
symbol in its static reach differs between those programs (use numeric `since`/`as-of` only after proving ancestry in the same lineage); no schema, namespace-binding, resource, config, macro or dependency-input change since that basis invalidates the execution. Unknown input dependency is a refusal or conservative execution, never reuse. Retain B1's input evidence until the per-path producer and consumer replace it together. A member lacking
any of these — including a run admitted and never completed, or an execution
whose body did not exit — is an obligation, never reuse. A green at t3 for
test B discharges nothing for test A (the independent-test case): the
comparison is per test against its own basis.

### 2b. Observed reach — what it is and what it misses

B1's graph-fidelity gate precedes precision claims. `:seon.fn/calls` combines
lexical calls with callable dependencies declared by actual dispatch owners through
`:seon.fn/invokes`; therefore even “calls-only” reverse reach is not lexical-only
or observed execution. Descriptive operation symbols and mere readers of
function-valued attributes create no invocation dependency. Retain real callable
dependencies and explain selection paths; do not prune common functions simply
because many tests depend on them. Prove an ordinary leaf, a genuinely shared
function and an unchanged green request through the canonical execution authority
before starting the four refactor cuts. A missing dynamic edge remains an explicit
coverage limitation, never evidence that its tests are unaffected.

| Source | What it captures | What it misses |
|---|---|---|
| static graph (`:seon.fn/calls`, `:seon.fn/references`, clj-kondo) | every syntactic call and Var reference the analyzer resolved | targets named by value (`requiring-resolve`, config facts naming symbols, multimethod/protocol dispatch to an implementation), library-internal paths, macro bodies expanded away |
| observed (C1's wrapper, `src/seon/instrument.clj:878-889`) | the armed functions that actually executed, with the definition digest they carried at arming | uncontracted and primitive functions (`collect-contracts!` `:898-908` arms contracted, non-primitive Vars only — 1,570 armed per C1 pack §8), macros, Java interop, work on threads whose bindings did not carry the member's observation, resource/config/schema changes, dependency gitlinks, an interrupted or expired body (incomplete) |

Current declared call/reference/subject and schema/input dependencies remain the conservative selection and destructive-host authority. Observed reach is diagnostic only; absence from an execution never excludes a required test. Newly added or disconnected tests and unfinished executions remain obligations within scope. Completion evidence distinguishes an empty complete observation from no observation.

The observation is scoped to one admitted member, including attributable fixture work, the test itself and joined asynchronous work, excluding recording. No database read, digest computation or transaction occurs per call. Persist exact observed versions, or verify every observed digest against the immutable tested commit before retaining symbols plus that commit. Version mismatch or missing arming is incomplete evidence. Delta-write unchanged memberships; a partial observation never replaces complete evidence. C1 supplies this interface separately from timing counters, and its overhead is measured separately.

### 2c. Termination

`bounded-result` (`test.clj:143-196`) cancels with `(.cancel task false)` and
records `terminated? (.isDone task)`: cancellation bounds the WAIT, not the
body. SCI's `:interrupt-fn` fires only in interpreted code and never inside a
host call (`reference-code/sci/doc/interrupt.md:52,63-87`); a JVM test body has
no interrupt at all. Rule after this lane: an overrun is red
(`duration-failures` `runner.clj:360-378`, kept); `terminated-tx` is asserted
only when the body's thread has exited (joined under the request-wide
`:seon.test/check-time-limit-ms`, default 120,000 ms, `seon.test.edn:172-175`,
`config/default.edn:367`); a body still live at that bound records a member
`error` naming the test and its thread, the run admits no further body, the
result carries `:seon.test/unfinished`, and `passed?` is false. The member
schema already states the rule (`seon.test.member.edn:20`); the code now obeys
it. The hang is reported, never waited out silently.

### 2d. Fixture isolation

The fixture branches from the request's captured execution commit. For ordinary checks this is the explicitly selected cluster's program; for D1 it is the combined scratch program. It is never silently replaced by `:current-src`. Every member receives that same commit, matching projection and acquired context. The branch inherits its source facts, including agents and turns when present; tests own new entities and writes and do not run inherited graphs. Use the canonical seed helpers where the subject needs them.

| Isolated by branch + fork | NOT isolated |
|---|---|
| datoms, history, branch writer and schema state (`versioning.cljc:212`) | loaded JVM Vars/classes, mutable objects, Malli registry, instrumentation and other running graphs; keep existing isolation and `preserving-instrumentation-state` / `preserving-schema-registry` (`test_support.clj:1073,1092`) |
| copy-on-write SCI Vars (`core.cljc:345-351`) with explicitly supplied custody (`sci/eval.clj:2370-2395`) | JVM roots or mutable objects shared by those Vars; a SCI fork alone proves no version isolation |
| nothing store-global | blob tests get independent stores; `blob_test.clj:218`, `ai_stream_fold_test.clj:378`, `cluster/mcp_test.clj:567`, `cluster/source_database_test.clj:14` and surviving generated cases |

`fork-database` (`versioning.cljc:550-640`) copies every konserve key and warns that a concurrently written source can tear; a publication monitor does not quiesce other branches' writes in one JVM, and a duration allowance is never a consistency claim (Codex; review note §3). No "coherent export" mechanism is added: the four sites split by their real need. `cluster/source_database_test.clj:14` needs a commit-bearing store for `commit-as-db`; that need dissolves with commit 1, because the fixture branches off the OPEN file store, which carries the commit graph. A test of blob MECHANICS (`blob_test.clj:218`, `ai_stream_fold_test.clj:378`, any generated case that needs no program facts) opens a fresh EMPTY store with the schema installed — milliseconds, no copy, no program. `cluster/mcp_test.clj:567` (artifact identity, paging and root retraction) is decided by a probe first: if its retraction assertion derives from datoms on the test's own branch, a branch suffices; otherwise it takes a **reachable-only copy**: `fork-database` with `{:reachable-only? true}` copies exactly the keys `reachable-in-branch`'s walk marks from the fork-point commit record (`gc.cljc:22-81`) plus Seon's `:datahike.gc/reachable-extension` blob keys (`gc.cljc:152`), instead of `k/keys`. That set is closed and complete at any time after the commit is written, whatever other writers do, by the barrier invariant the GC itself relies on (`gc-storage!`'s docstring, `gc.cljc:83-100`: every referenced key is written before the head flips, and a commit is immutable). The barrier proves completed writes precede publication, not continued lifetime during copying. Preserve the source/target reachability permits already acquired by `fork-database` (`versioning.cljc:608-614`, released at `:730-732`), start from the exact captured commit, fail explicitly on missing records, and include schema/history/index/blob roots and target metadata. The GC walker tolerates absent records and accepts a history cutoff; its result is not automatically a complete export inventory. The proposed ≈15-line size is unverified; require reopen, exact-fact/blob and concurrent-GC proof before the test converts; its cost is proportional to the commit's reachable keys and is measured once for that one test. Setting a branch option never makes the helper read that branch.

A running request retains its admitted program even if the live cluster adopts later. B2/A1 must prove both direct SCI calls and calls through loaded JVM functions execute that definition and contract. Shared root replacement makes a retained fork alone insufficient. Mismatched custody, unproven version, or SCI-unloadable fallback for a changed implementation refuses green evidence. Keep the existing isolated host where needed, using this same `run` owner. A candidate-only failing body must make D1's combined gate fail before this interface is accepted.

### 2e. Boot proof, destructive execution and result custody

Boot-from-zero uses an isolated runner host with the explicitly identified checkout program. Destructive work uses an immutable snapshot of the named cluster, derived by conservative current reach to declared destructive owners. These are different execution inputs to the same `run` function, not separate runners. Keep the current platform eligibility and destructive-host guard until its callers move together (`runner.clj:1142-1177`). A long allowance never grants destructive hosting.

Both record settled members through the existing recorder on the named durable authority, carrying the execution commit/program/input evidence. Retain that evidence before removing disposable storage. If the evidence remains in a retained tested commit instead, D1 must prove it is still queryable by run identity after scratch retirement under the declared retention policy. Printing a tally and deleting its only facts does not satisfy this contract. One subprocess denotes the runner host; boot tests may own children. Lanes never reset `default`.

## 3. Reading list — open before editing

| Source | What it guarantees |
|---|---|
| `reference-code/clojure/src/clj/clojure/test.clj:325` `report`, `:353` `do-report`, `:316` `inc-report-counter`, `:710` `test-var`, `:725-737` `test-vars` | the whole capture surface is one dynamic Var and a counters ref; namespace fixtures compose in `test-vars` |
| `reference-code/kaocha/src/kaocha/type/var.clj:20-28` `test-var`, `:30-63` the `-run` method; `testable.clj:214-228`; `result.clj` (65 lines) | one Var runs with counters and zero-assertion detection in ~40 lines; a collection is a `loop`; tally arithmetic is trivial; kaocha never *fails* a slow test — the bound is ours |
| `versioning.cljc:212-235` `branch!`; `:550-585` `fork-database` | `branch!` is a head pointer plus roster update under a permit; `fork-database` copies every key and can tear |
| `reference-code/sci/src/sci/core.cljc:345-351` `fork`; `reference-code/sci/doc/interrupt.md:52,63-87` | copy-on-write context; interruption never reaches a host call |
| `src/seon/test.clj:61-99` `changed-since-green`; `:143-196` `bounded-result`; `:218` `destroyers`; `:302` `host`; `:646-716` digests and changed symbols; `:759` `green-members`; `:840` `select`; `:1280` `admit-run`; `:1506` `resolve-test` | the kept rules: per-test basis, the interrupt seam, host derivation, admission before execution, resolution from facts |
| `src/seon/test/runner.clj:111-435` capture; `:360-378` `duration-failures`; `:655-700` `run-vars!`; `:1244-1283` `run-selected-tests` (calls `test/test-vars` at `:1279`); `:1422-1543` restore; `:2454-2470` `execution-refusal!`/`execution-read` (recording dependencies, kept); `:3014` `record-tx`; `:3145` `commit-results!`; `:3505-3548` result queries; `:4517` tally | the kept spans; everything else in the file is process machinery |
| `src/seon/instrument.clj:844-908` | the armed wrapper (`:878-889`) C1 extends; `collect-contracts!` selects what is observable |
| `src/seon/sci/eval.clj:2218` `base-ctx`, `:2350-2400` `fork-cluster-ctx`, `:3282` `run-test` | the cluster's base ctx, what a fork rebuilds, how a SCI test runs under `run-vars!` |
| `src/seon/cluster.clj:295-305` (HEAD) | `running-instances` holds the connection and ctx per cluster; `mcp-instance` is private — `run` receives custody from its caller, it never adds a lookup owner |
| `src/seon/program.cljc:178` `test-marker-attributes`, `:193` `test-markers` | the ONE lifting rule for `long`, `long-ms`, `platform`, `fixture` (B1's indexer writes them) |
| `test/seon/test_support.clj:615-649` `fork-cluster-ctx`; `:921-944` `with-fresh-database` (calls `create-base` at `:927`); `:946-981` `with-branched-database`; `:982-997` `with-database` | the target fixture shape is already the branch path; the base it holds is the only thing that changes |
| `bin/test-check:6-53` | resolve the advertisement, evaluate one form, print, exit 1 unless `passed?`; accepts `[CLUSTER [--test NS/TEST [--time-limit-ms N]]]` only |

## 4. REPL protocol

Two read-only jvm evaluations on `default` (branch `cluster-default`, basis
536870949), 2026-09-21:

| # | Form (abridged) | Value |
|---|---|---|
| 1 | counts over `(seon.db/db (seon.operator/connection "default"))`: tests, long rows, long without `long-ms`, `long-ms` rows, platform, fixture, `:seon.fn/destroys` owners, runs, reach rows, tests without calls, branches | `{:tests 2157 :long-rows 146 :long-without-ms 49 :long-ms-rows 97 :platform-rows 110 :fixture-rows 6 :destroys-owners [seon.operator.state/cleanup-root-under-lock! seon.test-support/populate-published-root! seon.test-support/populate-published-operator-root! seon.operator/cleanup-root-under-lock!] :runs nil :reach-rows nil :tests-without-calls 1 :branches 3 :branch "cluster-default"}`, 8 ms |
| 2 | time `definition-digests` over all 2,157 test symbols, `changed-definition-symbols` at `(- basis 100)`, `latest-results` over all tests, `reach-memberships` over 20 | **refused** at `test.clj:689`: `seon.db/as-of` returned a map — Datahike: *"Invalid transaction ID. Must be bigger than 536870912"* for time-point 536870849. The branch's first transaction is 536870912; a basis before the branch point is not an `as-of`-able value on this branch. The first pass's "16.5 ms / 100 tx" was measured on a different basis and is withdrawn. |

Reading: `runs nil` — nothing on `default` has ever been recorded, so
incremental selection has no evidence to be incremental from; `reach-rows nil`
means no observation, not an empty one. Probe 2's refusal is itself a finding
for step 3: a candidate's basis is always a transaction ON its branch (a run
records `:seon.test.run/branch`), and `select` must refuse a supplied basis
below the branch point by name instead of throwing from `as-of`.

Form 1:

```clojure
(let [db (seon.db/db (seon.operator/connection "default"))
      seeds ['seon.db/transact! 'seon.test/host 'seon.test/destroyers 'seon.print/text-sink 'seon.id/id 'seon.render.test/render-html 'seon.test-support/with-database]
      start (System/nanoTime)
      gates (seon.fn/gate-sets db seeds)]
  {:basis (seon.db/basis-t db)
   :tests (seon.db/q '[:find (count ?e) . :where [?e :seon.test/sym]] db)
   :observed-rows (seon.db/q '[:find (count ?e) . :where [?e :seon.test/reach]] db)
   :runs (seon.db/q '[:find (count ?e) . :where [?e :seon.test.run/id]] db)
   :selected (if (:seon.error/at gates) gates (into (sorted-map) (map (fn [[s ts]] [s (count ts)]) gates)))
   :elapsed-ms (/ (- (System/nanoTime) start) 1000000.0)})
```

Value: `{:basis 536870949 :tests 2157 :observed-rows nil :runs nil :selected {seon.db/transact! 1827 seon.id/id 1838 seon.print/text-sink 1827 seon.render.test/render-html 1827 seon.test-support/with-database 1827 seon.test/destroyers 1827 seon.test/host 1827} :elapsed-ms 790.179583}`. Tool event 791 ms, cluster alive. Nil aggregate results mean no matching rows, not an observation of complete empty reach.

Form 2 (diagnostic whole-graph read, not the proposed production selector):

```clojure
(let [db (seon.db/db (seon.operator/connection "default"))
      start (System/nanoTime)
      rows (seon.db/q '[:find ?symbol ?callee :where (or [?e :seon.fn/sym ?symbol] [?e :seon.test/sym ?symbol]) [?e :seon.fn/calls ?callee]] db)
      tests (set (seon.db/q '[:find [?s ...] :where [_ :seon.test/sym ?s]] db))
      incoming (reduce (fn [m [caller callee]] (update m callee (fnil conj #{}) caller)) {} rows)
      seeds ['seon.db/transact! 'seon.test/host 'seon.test/destroyers 'seon.print/text-sink 'seon.id/id 'seon.render.test/render-html 'seon.test-support/with-database]]
  {:basis (seon.db/basis-t db) :call-edges (count rows)
   :calls-only (into (sorted-map)
     (for [seed seeds]
       [seed (loop [pending [seed] seen #{}]
               (if-let [s (peek pending)]
                 (if (seen s) (recur (pop pending) seen)
                     (recur (into (pop pending) (get incoming s)) (conj seen s)))
                 (count (filter tests seen))))]))
   :elapsed-ms (/ (- (System/nanoTime) start) 1000000.0)})
```

Value: `{:basis 536870949 :call-edges 76192 :calls-only {seon.db/transact! 1701 seon.id/id 1716 seon.print/text-sink 1706 seon.render.test/render-html 1701 seon.test-support/with-database 1142 seon.test/destroyers 1701 seon.test/host 1701} :elapsed-ms 150.172041}`. Tool event 152 ms, cluster alive. Neither read proves loaded-source/adoption freshness; both describe this exact live database basis.

**Before** (the lane's first act records probe 1 and the exact historical graph forms below in
the landing note). **After**, on `default`, with every symbol bound locally:

```clojure
(let [conn (seon.operator/connection "default")
      result (seon.test/run {:seon.db/connection conn :seon.test/recording-connection conn :seon.test/policy :named
                             :seon.test/identities #{'seon.test.duration-test/overrun-is-captured-and-reported-as-an-assertion-failure}})]
  [(:seon.test.run/id result) (mapv (juxt :seon.test/sym :seon.test.run/terminated? :seon.test/pass-count) (:seon.test/results result))])
;; → one member, terminated true, elapsed recorded; a second identical request → :seon.test/unchanged true, zero executions
(let [conn (seon.operator/connection "default") db (seon.db/db conn)]
  (time (count (:seon.test.run/members (seon.test/select {:seon.db/db db :seon.test/policy :incremental :seon.test/changed #{'seon.test/destroyers}})))))
;; → the static reach count and its ms, both recorded, neither predicted
;; Fixture timing acceptance supplies the captured database/projection/context
;; through the converted canonical fixture, and records first/later cost separately.
```

Codex reaches the REPL through `.codex/config.toml` → `bin/mcp-server` →
`eval_clj` (jvm, `read_only` for probes) against `default`; a scratch cluster
is `bin/seon --root tmp/b4-root start b4` (create the directory first; a fresh
cluster seeds agent `root`), downed after. `runtime_status` after every commit
is the soundness probe; the lane never resets, stops or reforks `default`.

## 5. The work, ordered as commits

Each commit is dependency-complete: every caller, contract, schema consumer,
script and test of a retired Var converts in the same commit; HEAD loads
using `clojure -M -e` with the actual touched and surviving caller namespaces from that slice (never requiring retired `seon.plan`/`seon.issue` after B3 retires them),
then `require :reload` on `default` and one `runtime_status`. Line counts are
estimates to be replaced by `git diff --stat` in the landing note.

| # | Commit | Converts / deletes | Loadability & probe |
|---|---|---|---|
| 1 | **Fixture on the open store**: `with-branched-database` branches off the explicit captured execution commit in the supplied store under the carried projection; `fork-cluster-ctx` forks the hosting instance's base ctx handed in by the caller; the four blob tests fork per test; `create-base`, `clone-directory!`, `replace-directory!`, the `Held` protocol, `retrying-base`, `acquire/release/close-base!`, `database-base`, `*held-base*` (`test_support.clj:257-577`) go; `fixture-observation!` callers at `:108,133,922` go with them; `with-published-file-database` and `populate-published-*root!` stay for the platform tier (27 caller files, e.g. `cluster/boot_test.clj`, `dev/fresh_operator_export_test.clj`) | ≈ −600 fixture; `test_support_test.clj` keeps the fixture's class tests | time the canonical fixture with explicit captured custody, first and later; `fixture_timing_test.clj` green in process |
| 2 | **One request owner**: `seon.test/run` (§6) replaces `run` (`:541`), `run-owned` (`:576`), `check`/`check-in-process`/`check-admission` (`:1901,1627,1595`), `check-adoption`, `check-request` (`:2052`), `host-admission!`, `execute-admitted!`, `select-snapshot`, `verified?` (`:2142`, folded into §2a), `feedback` (→ `tally`); `select` loses duplicate snapshot construction and worker coverage claims only after execution-commit and input-evidence consumers replace them; retain tested branch/commit and input provenance; callers converted here: `src/my/test.clj:24,56`, `src/seon/plan.clj:811,840,870`, `src/seon/issue.clj:855,1039`, `src/seon/problems.clj:354`, `src/seon/render/test.clj:61-62`, `src/seon/cluster/source.clj:287-293` (B1's file — see §8), `bin/test-check:39-44` (arguments: `[CLUSTER [--policy P] [--test NS/TEST]… [--time-limit-ms N]]`), `src/seon/turn.clj:2077` comment | ≈ −1,300 | `bin/test-check default --test <sym>` prints the tally `(seon.test/tally …)` renders; `(my.test/run)` from an agent's SCI evaluation returns the same member facts |
| 3 | **Launchers and process machinery**: `bin/test` ≤ 100 lines (`--platform` per §2e; everything else `exec bin/test-check "$@"`); delete `bin/test-fast`, `bin/_test-slot`, `src/seon/test/fast.clj`, `bounds.clj`, `selection.clj` (`reaching-tests` duplicates `gate-sets`); `cache.clj` keeps only `input-roots`, `input-path?`, `input-digests`, `gitlink-digests`, `toolchain-dependencies`, `test-input-digest`, `classpath` (callers `cluster.clj:1633-1643,1825-1851,2146-2149` HEAD, `cluster/source.clj:115,357`, `script/seon/fresh_operator.clj:511`) until B1 moves them — a MOVE, ≈ 90 lines, not a deletion; runner spans deleted with their tests: `:435-640`, `:874-960` (the destructive-platform rule moves into `select`), `:1019-1077`, `:1179-1244`, `:1393-1447`, `:1545-1656`, `:1657-1720`, `:1724-1848`, `:1907-2135`, `:2135-2440` except `provenance`/`program-digest`, `:2454-2714` except `execution-refusal!`/`execution-read`, `:3263-3443`, `:3628-4307`, `:4308-4500`, `:4666-4967`; `run-vars!` retires duplicate snapshot/drift bookkeeping only after equivalent cross-request/adoption/global-state evidence is established; `restore-live-cluster-schema!` (`:1447`) stays until A1 proves no retained mutable registry needs restoration; `script/seon/dev/changed_test.clj` converted; surviving assertions from `test_runner_test.clj`, `test_runner_integration_test.clj`, `test/runner_test.clj`, `bounds_test`, `published_selection_test`, `publication_test`, `test_preparation_test`, `test_cache_test` move into `test/seon/test/runner_test.clj` in this commit, the rest die with their mechanism | ≈ −3,900 src, −1,300 shell, −6,000 test | `bin/test --platform` boots one scratch JVM and exits with the run's verdict; `bin/test` bare prints `bin/test-check`'s tally; AGENTS.md §1 and §5 launcher paragraphs rewritten here |
| 4 | **Schemas and markers — RESET NEEDED**: `seon.test.member.edn` loses `worker`, `claim-tx`, `claimed-at`, `host`; `seon.test.run.edn` loses worker/overlay bookkeeping only after explicit execution and recording custody replace it; retain tested commit/branch, original execution and recording bases, and sufficient input evidence; `seon.test.runner.edn` loses the worker/wire families; `seon.test.selection.edn` loses the overlay; `seon.test.edn` retires duplicate reach digests only after equivalent content/schema/input comparison lands; preserves declared subject edges regardless of historical row count; retires `usage`, `skipped-count`, `skip-reason`, the acquisition/classpath/jvm-options/adoption/deferred/expired shapes; adds the entity-map rule **an allowance above the default requires a positive number and nonblank reason; a reason requires its number**, refused by the whole-entity validator at publication; the 49 rows without a number receive their measured number or lose the marker (§7); `duration-failures` reads `(or long-ms time-limit-ms)`; `duration_test.clj` asserts a blank reason and a reason-without-number are publication refusals through `transacted!` | ≈ −450 schema | the orchestrator performs `bin/seon reset --force` at the batched reset (loses recorded runs, turns, agents, private objects — disposable by ruling); publication of a numberless `:seon.test/long` is refused by name |
| 5 | **Diagnostic observed reach** (after C1's wrapper and B1's digest): `commit-results!` persists complete version-attributed observation; selection remains conservative; the duplicate index retires only with its content/schema/input guarantees replaced | ≈ −50 | a request after one recorded run shows its observed set; a timed-out member records no reach |
| 6 | Landing note; `.agents/skills/clojure-testing/SKILL.md` rewritten; `docs/seon/issues/` notes whose subject died closed by name | docs | — |

Commit 1 requires A1/B2 matching program/context carriage; the blob-scoped tests take the §2d split (fresh empty store, or a numbered `fork-database` allowance) and wait on nothing. Commit 4 pairs B1 marker lifting and every loaded schema consumer before reset. Commit 2
touches `cluster/source.clj` (B1) — one slice when free, or B1 converts its two
`requiring-resolve` sites to `run`'s admission first. Commit 3's `cache.clj`
remainder waits on B1's move. Commit 5 waits on C1 and B1; it does not change the selection rule.

## 6. The one request owner, and better than the floor

```clojure
(defn run
  "Select, admit, execute the named program on its admitted host, record on the explicit authority, and answer
   one test request. :seon.test/policy is required: :named selects only the
   identities/namespaces; :incremental derives changed reach and outstanding
   obligations per test from its own last green basis; :platform the declared
   rows; :all every eligible row. A green member whose evidence still holds is
   answered from the record with :seon.test/unchanged. A member reaching a
   :seon.fn/destroys owner is excluded with its command; :seon.test/include-long?
   admits declared-long members and never changes a host. Each body runs on its
   own thread under (or :seon.test/long-ms :seon.test/time-limit-ms); the whole
   request under :seon.test/check-time-limit-ms. Results, termination and
   observed reach are facts before this returns; passed? is true only when
   every required obligation has complete valid green evidence; exclusions and unknowns remain unfulfilled."
  {:malli/schema [:=> [:cat :seon.test/request]
                  [:or :seon.test/run-result
                   :seon.test/selection-error :seon.test/unknown-error
                   :seon.test/resolution-error :seon.test/not-runnable-error
                   :seon.test/admission-error :seon.test/program-mismatch-error
                   :seon.test/expired :seon.db.write/error :seon.db/invalid-read-error
                   :seon.schema/missing-projection-error]]}
  [{connection :seon.db/connection policy :seon.test/policy :as request}])
```

The request/result declarations land with this API. The table defines custody explicitly; schema discovery reuses existing value schemas and adds the new request members only at this owner.

| Request member | Contract |
|---|---|
| `:seon.db/connection` | required execution connection; capture one immutable database/commit at admission |
| `:seon.test/execution-commit` | optional immutable commit UUID supplied by D1/isolated execution; materialize through the existing database owner and prove membership in the supplied history; otherwise capture the connection's current commit |
| `:seon.test/recording-connection` | required durable result authority, allowed to equal execution connection; never inferred from a scratch default |
| `:seon.sci.eval/ctx` | context supplied by the execution owner; required for SCI members, canonical definitions/resolvers/contracts must match execution commit. JVM members must independently prove loaded-definition equivalence |
| `:seon.test/policy` | required named/incremental/platform/all eligibility policy; named requires test identities or namespaces |
| `:seon.test/identities`, `/namespaces`, `/changed` | optional sets using current declared symbol schemas; D1 supplies the complete required test identity set under named policy |
| `:seon.test/include-long?`, `/check-time-limit-ms` | duration eligibility and a positive schema-typed request bound; neither changes host permission |

The captured database carries its projection; do not reconstruct it or accept a mismatched separately supplied projection. An adopted source id alone does not establish program identity after agent writes. Unknown commit/history, missing SCI context, fallback body, mismatched version or invalid custody is a named refusal. Extend the illustrative output union above with any additional actual callee outcomes before retirement; no generic base-error escape.

Results carry run identity, execution commit/program/input evidence, recording basis, executed and reused member facts, exclusions, unfinished obligations and `passed?`. Each reused member retains its original tested and recorded bases. `passed?` requires every requested obligation to have complete valid green evidence, including zero new execution only when every obligation was reused. Recording refusal never returns a green tally. `tally` queries the explicit recording authority by run identity and uses the existing render pair. D1 retains the exact tested commit and run identity through scratch retirement.

Candidates the lane probes first, with the deciding probe:

| # | Candidate | Probe | Decides |
|---|---|---|---|
| A | attribution of C1's observation | one member with: a nested `testing`, a joined `future`, a Flow hop, an unarmed function, a SCI override sharing a symbol with another body, background cluster activity on the same JVM | exact membership/version per member, or explicit unknown; the number is not the target |
| B | `test/test-vars` already at `runner.clj:1279` | run one namespace with `:once`/`:each` fixtures and a `test-ns-hook` through `run-selected-tests` alone; diff the event sequence against today's | delete only the dispatch around it, never the fixture semantics |
| C | batched latest-result read | one query returning the latest member per candidate vs `:3536`'s pull-per-symbol, over 2,157 symbols | O(candidates) in one read |
| D | branch from the program head vs the cluster branch | two branches off one captured commit, a publication between them, an agent seeded in one | the second sees neither the first's agent nor the newer program; exact isolation asserted |
| E | blob fixture cost | the reachable-only copy of the supplied execution commit (§2d) into an independent store while another branch commits concurrently; compare the copied key set with the walk's marked set; open the target and read the commit | exact source/target facts and independent blob mutation hold with concurrent writers; missing source data refuses rather than yielding an incomplete fixture; the measured cost is the one test's declared number |

## 7. Tests — what dies, what collapses, each time escape

**Dies with the machinery** (commit 3): `test_runner_integration_test.clj`
(1,513 lines; 24 reason-only markers and one 1,800,000 ms), `test_runner_test.clj`
(1,632), `test/runner_test.clj` (1,324), `bounds_test`, `published_selection_test`,
`publication_test`, `test_preparation_test`, `test_cache_test`, `test_expiry_test`,
`test_provenance_test` — their assertions about retained behaviour (capture,
markers, recording, restore, admission, tally) move first. **Collapses**
(commit 3 and 2): `test_reaching_test`, `test_failure_facts_test`, `test_test`,
`selection_test`, `reach_test`, `declared_reference_test`, `check_request_test`,
`host_test`, `fixture_timing_test` into `test/seon/test/runner_test.clj`
(execution, bound, termination, recording, tally, host, restore) and
`test/seon/test/selection_test.clj` (basis per test, changed, reached, named,
platform, long, fixture, first-run, reuse, the canonical A/B basis case,
deleted test, disconnected new test, schema-only change, basis below the branch
point). Two files do not prove two classes; the landing note lists the class
each retained test proves. `accretion_test.clj` and `src/seon/test/accretion.clj`
are contract generation, untouched. The five symbols-as-strings sites in this
lane's files (`test_reaching_test.clj:68` and its siblings) are fixed on the way.

**The 49 numberless `:seon.test/long` rows** (§4 probe 1; the pack's 38 is a
text count, namespace declarations lift to each row) after commit 4: a
`:seon.test/long` without `long-ms` does not publish. Disposition by owner of
the O(program) step, from the pack's §4:

| Class | Declarations (pack §4) | O(program) step | Disposition |
|---|---|---|---|
| A — fixture acquisition (20; `my/test_test.clj:13` 300 s, `cluster/publication_adoption_test.clj:15` 600 s, `test/host_test.clj:15` 300 s, …) | `create-base` → `projection-from-database` 3,995.96 ms | gone with commit 1 (B4) and A1's carried projection | marker deleted; 5,000 ms applies; a survivor over 5 s is measured and numbered, never re-excused |
| A — publication (21; `cluster/boot_test.clj:873,1142,1799`, `publication_*_test`, `fn_test.clj:1696`, …) | `publish!` over `src/` | **B1** publishes a small fixture program | B1's table; B4 only refuses the numberless marker |
| A — cold clj-kondo, SCI acquisition, whole-image instrumentation, whole-store copy (8) | `index!`, `acquire-program!`, `collect-contracts!`, store clone | **B1 / B2 / A1 / A2** | their tables |
| A — property runs (`render/transcript_test.clj:968`, `print_test.clj:240,264`, `schema/datahike_test.clj:243`, `concurrency_independence_test.clj:1`) | per-trial fixture | measured per trial in this lane; repeated setup is the namespace agents' hoisting task | number = the measured run, recorded in the landing note; no "2× measured" rule |
| B — test-system child JVMs (`test_runner_integration_test.clj:46`, `dev/source_instrumentation_test.clj:80`) | child JVM | deleted (commit 3) | — |
| B — platform tier owned elsewhere (`dev/fresh_operator*_test.clj` ×9, `cluster/boot_test.clj:1463`, `cluster/cohost_boot_test.clj:100`, `cluster/store_test.clj:498,568` — the OS-lock proof A2 assigns here, `dev/edit_feedback_test.clj:221`, `bootstrap_drive_test.clj:28`) | boot from zero | run in the platform JVM through `run`; their owners supply the measured number | B4 provides `run` and the platform JVM |
| C — genuinely long (`flow_test.clj:1427`, `oversight_test.clj:113`, `config_application_test.clj:147`, `cluster/program_restart_test.clj:1` 67.8 s, `cluster/armed_test.clj:1` 48.6 s, `ai_stream_fold_test.clj:374`, `dev/dependency_cache_test.clj:136`) | real boots, deadlines | kept; the two namespace-level ones become per-test numbers | measured number required |
| D — `shell/jvm_test.clj` 7 × 600 s | publication | **B1** folds into one | — |

The default bound is 5,000 ms (`seon.test.edn:27-29`): fixture removal must be measured before claiming it fits that bound. A reason without a number is
a refusal; a whitespace reason is a refusal (`duration_test.clj:18` already
declares that case). `:seon.test/platform` stays a declared fact lifted by the
indexer (110 rows; goals §6 #7). The 68,105,085 ms figure is the text sum over
65 files with that limitation; no per-test allocation is claimed from it.

## 8. Done, landing note, stop rules

**Done** when, on `default` after the batched reset: the §4 "after" forms
return the stated shapes with their measured ms; a second identical request
executes zero bodies; `bin/test-check default` prints `(seon.test/tally …)`'s
bytes; `bin/test --platform` boots one scratch JVM, runs the 110 rows through
`run`, exits with the run's verdict; probe 1's `long-without-ms` is 0; HEAD
loads after every commit; `wc -l` over §9's paths is recorded against the
target. Landing note: `docs/prds/agent-platform/landing/lane-b4.md` — the
before/after forms and values, the commit list with `git diff --stat`, the
first observed run's reach count, the platform tier's wall time, each retained
test's class, and the cold proof still owed.

**Stop** at a held file (`src/seon/cluster.clj`, `src/seon/fn.clj` are dirty
today: commit 2's `cluster/source.clj` conversion and commit 3's `cache.clj`
move wait for B1); at C1's wrapper seam not landed (commit 5 only); at a
refused overlay naming a foreign caller; at an unsettled design, with three
options in the note. Implementation gates: exact tested-program resolution (including a candidate-only failing body and adoption during execution), coherent per-test store export, cross-request exclusion and actual termination, complete input invalidation, and retained result custody. Retain the existing mechanism until each replacement proves its guarantee. Observed exclusion is outside this plan; a later proposal would need an explicit coverage ruling and its assumptions. Per-member result recording is settled, not a batching decision.

## 9. Size target

| File(s) | Today | Audit floor | Target | Why, and what is unpriced |
|---|---:|---:|---:|---|
| `src/seon/test/runner.clj` | 4,967 | 2,406 | **≤ 1,300** | kept: capture `:111-435`, `run-vars!`/`run-selected-tests`, markers, restore (until A1), `provenance`, recording, result queries, tally render; the reach-digest index (≈ 305) and every process span go; failure identity (`:223-239`) routes through `seon.id/digest` |
| `src/seon/test.clj` | 2,182 | ~1,680 | **≤ 800** | one `run`; `select` without duplicated snapshot construction, retaining tested-program/input evidence; `host`/`destroyers`/`resolve-test`/`admit-run` kept |
| `cache.clj` + `selection.clj` + `fast.clj` + `bounds.clj` | 928 | 546 | **≈ 90 until B1 moves them, then 0** | the 90 input-fact lines are a move, charged to `seon.cluster.source` |
| `test/seon/test_support.clj` | 1,123 | ~1,000 | **≤ 550** | no base clone, no hold protocol; the file-backed platform helpers stay (≈ 120) |
| shell (`bin/test`, `test-fast`, `_test-slot`, `test-check`) | 1,373 | ~160 | **≤ 170** | `bin/test` ≈ 100, `bin/test-check` ≈ 70 |
| `resources/seon/schemas/seon.test*.edn` | 1,203 | uncounted | **≤ 850** | accretion schemas (388) untouched; the process families go; one entity-map rule added |
| **Area total** | **11,776** | ≈ 6,300 | **≤ 3,300 stretch; ≤ 4,200 provisional ceiling** | the stretch holds only if A1 retires restore and B1 takes the input facts; the ceiling remains conditional on disjoint retained blocks for termination, per-test reuse, coherent export and actual-program resolution |
| corpus (`test/seon/test*`, `test/seon/test/*`, `test/my/test_test.clj`) | 10,080 | ~6,900 | **≤ 3,500** | counted separately; retained assertions move, machinery tests die |
