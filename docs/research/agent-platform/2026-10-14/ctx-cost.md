---
title: Held-task request cost
type: research
status: complete
date: 2026-10-14
lane: astra-ctx-cost
---

# Held-task request cost — astra-ctx-cost

`seon.test/stale-in` used green verification to decide whether a result was current. A current red proof therefore entered the automatic test step after every ordinary form, although `seon.test/select` then reused that same red result. The change separates result currency from green verification in `seon.test/current-result?`. The existing reaching-digest, source, input-digest and fixture-observation checks are shared; no cache, lifecycle rule or additional execution path is introduced.

## Environment and boundaries

Measured on 2026-10-14, default PID 10712, started 02:58:28 UTC from committed archive `de1f48c23ce0da42f5961bbc44a2dec5a03e5ab6`. The supplied context-sanity evidence is at `58f130e03`; default had not loaded that HEAD. Initial health was degraded: 16 untested-hot declarations, 13 errored evaluations, one failed test and three agent-error signatures. These observations are not a claim of a green baseline.

All product evaluations used this lane's SCI agents and branches. Root JVM calls only inspected the system, assigned the held task on the named private branch, or invoked the ordinary evaluator with that private agent's custody for profiling. No root SCI evaluation, restart, reset, worktree or other lane's edits were used. Default remains on its original compiled implementation: **RESTART NEEDED** in root's next batch to install this host test-owner change. A branch SCI definition alone does not replace the compiled test runner's calls.

Agents A/B/C were `astra-ctx-cost`, `astra-ctx-cost-b`, `astra-ctx-cost-c`; branches `:agent-e6b1119dcb0c`, `:agent-dc5b5eba7982`, `:agent-b96f13c2090d`. The extra profile agents were `astra-ctx-cost-profile` and `astra-ctx-cost-scoped` (`:agent-3449f68e39bd`, `:agent-a062e0c7f9fc`). Each fresh agent was created by one unassigned block read, then assigned the existing red task `seon.edit/implement-an-unreadable-odd-map-is-not-one-form` on its own branch. Thus “first” below means first **held-task** block read, not first acquisition of an unassigned agent.

## Default: three samples of each request

Each cell below is an observed value, not an estimate. First/repeat code was `(seon.context/current-state)` as one eval request; trivial code was `(+ 1 2)`. Form columns are `:seon.repl/ms` and `:seon.repl/alloc-mb`. Request and step columns are `:seon.repl/request`'s `:seon.cost/ms` and `:seon.cost/step` allocation. Step allocation covers the evaluator pipeline, not just the stage named `:test-step`.

| Agent | Request | Form ms | Form MB | Request ms | Step MB | Reads | Transactions |
|---|---|---:|---:|---:|---:|---:|---:|
| A | first | 74 | 94.6 | 997.8 | 745.0 | 1180 | 6 |
| B | first | 63 | 76.1 | 906.3 | 721.9 | 1180 | 6 |
| C | first | 61 | 75.3 | 940.0 | 792.2 | 1180 | 6 |
| A | repeat | 50 | 56.6 | 318.2 | 273.4 | 821 | 1 |
| B | repeat | 46 | 57.3 | 294.3 | 276.5 | 821 | 1 |
| C | repeat | 56 | 57.4 | 300.5 | 276.8 | 821 | 1 |
| A | trivial | 0 | 0.0 | 145.3 | 107.0 | 302 | 1 |
| B | trivial | 0 | 0.0 | 144.1 | 86.7 | 302 | 1 |
| C | trivial | 0 | 0.0 | 129.4 | 90.3 | 302 | 1 |

All request stage times, milliseconds:

| Agent/request | custody | fork | read | evaluate | derive | settle | test-step | print | unattributed |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| A first | 0.5 | 0.5 | 0.1 | 294.4 | 0.0 | 82.8 | 585.4 | 3.6 | 30.5 |
| B first | 0.5 | 0.4 | 0.1 | 242.3 | 0.0 | 53.3 | 576.5 | 4.2 | 29.0 |
| C first | 0.5 | 0.6 | 0.1 | 248.9 | 0.0 | 52.4 | 606.7 | 3.9 | 26.9 |
| A repeat | 0.9 | 1.1 | 0.1 | 127.3 | 0.0 | 66.0 | 88.3 | 2.0 | 32.5 |
| B repeat | 0.9 | 0.8 | 0.2 | 103.3 | 0.0 | 69.1 | 83.1 | 1.7 | 35.2 |
| C repeat | 0.5 | 0.9 | 0.1 | 111.1 | 0.0 | 67.2 | 85.6 | 1.6 | 33.5 |
| A trivial | 0.5 | 0.4 | 0.2 | 1.1 | 0.0 | 47.5 | 72.8 | 1.0 | 21.8 |
| B trivial | 0.7 | 0.6 | 0.1 | 1.3 | 0.0 | 35.8 | 79.8 | 1.3 | 24.5 |
| C trivial | 0.5 | 0.5 | 0.1 | 1.1 | 0.0 | 35.2 | 70.1 | 1.0 | 20.9 |

This reproduces the large first-read allocation and persistent overhead, though the original 1244 ms/763 ms sample is not repeated exactly: these first reads were 906–998 ms with 577–607 ms test stages. Ordinary settle cost in this set was 35–83 ms rather than 100–170 ms. The first blocks were about 10,872 estimated tokens, the repeated blocks about 11,312; the size is itself evidence for the separate context-rendering work, not part of this patch.

The first request runs exactly the held proofs `seon.edit-test/an-odd-map-anywhere-is-not-one-form` and `seon.edit-test/every-source-holding-an-odd-map-is-not-one-form`. The former records four errors; the latter one failure and one error. Their bodies together cost 56.9/46.4/44.4 ms and 20.165/20.1294/20.1311 MB for A/B/C. A's stored proof run is `fd44754a9a72`. No program definition was changed by these reads. Running previously unobserved task proofs is required; rerunning selection for a current red result is not.

Raw request cost replies and profiles, with result handles, are in [ctx-cost-evidence.json](ctx-cost-evidence.json). JVM-window allocation explicitly includes unrelated work and is not used as request-owned allocation. Two earlier global profile snapshots overlapped other lanes' publication (`seon.fn/var-entity` and schema admission appeared); those snapshots are excluded from causal ranking.

## Profile and proportionality

The repeat request's route is `seon.turn/step-due?` → `seon.test/stale` → `seon.test/stale-in` → `seon.test/verified?`. Green verification rejects a red result, so `seon.turn/test-step` reacquires execution, calls `seon.turn/run-proofs`, selects the old red results, and derives `seon.turn/step-reds` through `seon.context/step-verdict`. A direct no-change test-step measurement was 50.63 ms, 45.47 MB, 196 reads, 107 vector queries, 75 pulls and four reach graphs, while executing no test body. That is the largest fully reproduced removable work per ordinary request.

A request-scoped profile of `(+ 1 2)` on agent C (result `result/e63ed4294bab2`) recorded settle 91.07 ms and test-step 124.28 ms. The cost sink, unlike global before/after counters, follows this request's instrumented calls:

| Instrumented function | Calls | Sum of self wall time ms | Interpretation |
|---|---:|---:|---|
| `seon.db/vector-query-call` | 160 | 95.25 | Reads across the request, especially proof selection/verdict; not all charged to settle |
| `seon.db/transact-call` | 1 | 74.75 | Synchronous recording transaction, including waiting for the writer and durable commit |
| `seon.db/pull-logical` | 126 | 4.64 | Request and test-evidence reads |
| `seon.db/commit-database` | 5 | 4.13 | Resolve held commit values |
| `seon.schema/projection-cache-value` | 900 | 2.81 | Existing schema projection lookups |
| `seon.test.runner/reach-graph` | 4 | 1.52 | Repeated evidence reach work |

Settle's owner is `seon.turn/settle-batch!`: prepare terminal observations, fold program facts if present, then one `seon.db/transact!`. Its transaction's measured writer work was 9.02 CPU ms/8.4636 MB and commit work 31.42 CPU ms/45.5138 MB. The 74.75 ms caller self time includes asynchronous writer/commit waiting and cannot be relabeled all CPU. Remaining settle time includes observation construction and queries such as `seon.turn/terminal-agent-ordinal` and `seon.turn/with-fault-windows`. This is attribution to the owner, not proof of which dependency operation explains every millisecond; no writer change is claimed here.

A separate first-held-block request-scoped profile on `astra-ctx-cost-scoped` (result `result/ecf21e7d07946`) recorded evaluate 262.60 ms, settle 59.93 ms, test-step 523.67 ms and step allocation 754.1 MB. The two bodies were 45.7 ms/20.1776 MB. The request sink recorded:

| Instrumented function | Calls | Sum self ms |
|---|---:|---:|
| `seon.db/vector-query-call` | 281 | 226.35 |
| `seon.db/transact-call` | 6 | 174.72 |
| `seon.test/bounded-result` | 2 | 139.12 |
| `seon.instrument/instrumented` | 4 | 23.18 |
| `seon.db/pull-logical` | 369 | 23.15 |
| `seon.sci.eval/install-row!` | 37 | 20.96 |
| `seon.sci.eval/install-caller-host-bindings!` | 4 | 18.18 |
| `seon.test.runner/reach-names` | 4 | 16.32 |
| `seon.sci.reader/read` | 128 | 13.98 |

The six writes are request settlement plus test preparation/recording. `seon.test/clean-request-handle` builds the test request's branch from its checkpoint and overlays program differences; member acquisition installs reachable definitions into SCI. `seon.test/bounded-result` includes waiting for body execution, so its time must not be added to the body's time as independent work. A forced run of just those same two proofs, under their own branch binding, took 584.42 ms and 236.315 MB on the calling thread (run `8b29da16de8f`); global allocation from that probe was contaminated and is retained only with that qualification in the raw evidence.

| Stage | What it currently does | What cost should follow |
|---|---|---|
| custody/fork | Acquire the held agent context and fork SCI | Acquiring this agent and changed program inputs; not rebuilding the whole program |
| read | SCI reads one form | Authored source size |
| evaluate | Execute the form, render/admit its value and capture observations | The form plus the actual task block and its references; trivial arithmetic stays trivial |
| derive | Fold admitted program changes | Changed declarations; empty for these probes |
| settle | Construct and durably record evaluated observations | This request's facts and affected owning values; one transaction remains necessary |
| test-step, first | Prepare and run the two unobserved held proofs, derive verdict | Those proofs, their reachable definitions, and their actual writes; the measured body cost explains little of total overhead |
| test-step, repeated | Previously reselected already-current red results and derived the verdict | Zero execution/selection when neither evidence inputs nor definitions changed; current-evidence reads still remain |
| print | Print the already-rendered value | Shown value under the existing printer budget |
| unattributed | Entrance/reply work outside timed stages | Only observed as a remainder; no inferred owner or optimization claim |

## Change and proof

Red proof: `seon.pass-cost-test/a-current-red-proof-does-not-repeat-the-test-step` creates a held task with a real red proof using the canonical fixture helper, reads its block, then measures a repeat block and `(+ 1 2)` through `seon.mcp/call` with `seon.profile/measure`. It asserts that red is not verified, current red is not stale, no test selection runs on the repeats, test-step costs less than 5 ms, and each complete measured request costs less than one second.

Before the body changed, branch run `cf30b4d98a50` had 6 passes, 5 failures and no errors: stale returned the proof symbol, selection ran once per repeat, and test-step cost 65.9/37.2 ms. The independent HEAD-plus-test archive reproduced it in run `8b15f3b68ef5`, candidate `d8ffd46f6bcfb7689605578816ae97a662dc60da`: 6 passes, 5 failures, no errors; test-step 36.4/67.6 ms.

Candidate `886d3157ab55bcfed364db561d76a181d6931940`, run `3b9a0ba0e478`, ran seven named tests: 36 passes, **zero failures**, two errors. The new regression and the other four usable checks passed. Two existing fixtures failed before exercising the changed expressions: the provenance test writes an entity without its required definition digest, and the stopped-request test calls the evaluator without its now-required read form. Both reproduced without the body change in run `8e83c118d65d` on parent `0ad513f26`. They are filed as `seon.test/provenance-proof-fixture-declares-its-test` and `seon.test/one-request-fixture-uses-the-one-eval-entrance`.

The full static reaching set and result is:

| Test | Result / boundary |
|---|---|
| `seon.pass-cost-test/a-current-red-proof-does-not-repeat-the-test-step` | New regression green |
| `seon.pass-cost-test/a-request-that-changed-nothing-runs-no-test-step` | Green, unchanged unassigned-agent behavior |
| `seon.test-reaching-test/fixture-observations-remain-stale` | Green |
| `seon.test.interrupted-request-test/one-incomplete-member-is-its-own-unknown-and-its-run-mates-stay-readable` | Green, missing execution remains unknown |
| `seon.test.platform-test/a-platform-run-recorded-on-a-branch-verifies-its-tests-there` | Green, positive evidence still verifies |
| `seon.test-provenance-test/verified-requires-the-subject-positive-assertions-and-exact-program` | Existing fixture error on parent and candidate; its calls target the unchanged three-argument arity |
| `seon.test.one-request-test/a-stopped-requests-unstarted-member-is-unknown-and-an-identical-request-reruns-it` | Existing fixture error on parent and candidate, before its unknown-result assertion; that branch of verification is unchanged |

The final commit check names the five usable vars individually. The two baseline errors above are the foreign boundary, not passing evidence or permission to replace their fixtures in this slice.

The helper's Malli contract takes the existing database, test-symbol and recorded-result schemas and returns boolean or the existing host-error union. It extracts the previous currency checks from `seon.test/verified?`; the unchanged green/count checks still decide verification. Missing/unstarted results and fixture observations remain stale. Selection's existing reuse of current red results is the authority for this distinction. The alternative of adding a special case to `seon.turn/step-due?` was rejected: the false value came from the test-evidence owner.

Relevant library seams inspected: `malli.core/validator` caches a compiled validator and `malli.core/-instrument` builds the contracted wrapper; HEAD pins Malli `dd13ff81139159778edb0a67b491eccc0baa991d`. Datahike `datahike.versioning/branch!` shares immutable commits; HEAD pin `c9e57c6fb65365d5ce92b59854fb02ea59054fe7`. The dependency checkouts had unrelated changes; these pin citations do not claim that default had loaded their working trees. No new library mechanism is needed for this correction.

## Remaining work and ownership

- First-proof setup remains expensive. Owners: `seon.test/clean-request-handle`, `seon.test/program-overlay-tx`, `seon.sci.eval/install-row!` and `seon.test.runner/commit-results!`; existing tasks `seon.turn/a-one-test-change-tests-in-proportion-to-its-reach` and `seon.test/a-check-compares-every-definition-digest-of-the-program-twice`. The initial proof run remains required.
- Held-task rendering still allocates 57–95 MB in these forms and prints about eleven thousand estimated tokens. Owner: `seon.context/current-state`; existing task `seon.context/one-agents-current-state-costs-hundreds-of-megabytes`. The forbidden reference/render owners were not changed.
- Recording still costs tens of milliseconds for trivial forms. Owners: `seon.turn/settle-batch!`, `seon.db/transact-call`, checked writer and Datahike commit. Existing `seon.db/the-checked-writer-is-slow-on-a-fresh-branch` covers write cost; the turn bookkeeping-query task covers reads proportional to retained history. No evidence here justifies removing durability or adding a cache around the writer.
- The static reaching set contains two broken fixtures described above; their errors must remain visible to root. No unrelated fixture or runner code was changed to make this check green.

## Final measurements and verification

The paired experiment uses the new regression's small held task with one real red proof, not the large odd-map task from the default census. Both sides use the exact same fixture and requests through `seon.mcp/call`, inside `seon.profile/measure`, in separate otherwise quiet throwaway JVMs. The shared machine continued other lanes' work; it was not an exclusive machine benchmark. This isolates the repeated-selection behavior and does not predict the full odd-map task's post-restart time.

The reproducible diagnostic overlay is [ctx-cost-probe.patch](ctx-cost-probe.patch). It repeats each request three times and deliberately fails an equality against `:cost-probe` so clojure.test records the measurement **data** through the normal value printer. Those six diagnostic failures are expected, distinct from the regression's real assertions; the overlay is absent from the committed test. To reproduce, apply the overlay to the final test and check the named regression with the test path only for the parent's implementation, and with both the test path and `src/seon/test.clj` for the changed implementation. For an archived parent without the new test, supply the test file as the candidate diff. Do not load either candidate into default.

Parent candidate `3a60aac4057001e1c4996ff8c02c146fdc428d6d` (parent `0ad513f26`), run `8e83c118d65d`; changed candidate `6f4f74fb6f5d91e62c4314f4a117188306859249` (parent `65ad7ee96`), run `3ab28223bcd7`. The difference between those parent commits is documentation only (`proof-audit.md`, `docs/seon/plan.md`), not a runtime input. The changed diagnostic run has 27 real passes, six intentional diagnostic failures and no errors. Every real regression assertion passes.

| Request/sample | Profile ms parent → change | Request ms parent → change | Step MB parent → change | Test-step ms parent → change |
|---|---:|---:|---:|---:|
| block 0 | 218.75 → 85.21 | 206.2 → 76.7 | 160.1 → 50.6 | 92.1 → absent |
| block 1 | 94.53 → 58.04 | 88.9 → 52.2 | 86.9 → 32.7 | 38.1 → absent |
| block 2 | 102.48 → 92.04 | 96.7 → 85.2 | 87.0 → 32.8 | 38.1 → absent |
| trivial 0 | 70.46 → 29.64 | 62.0 → 23.8 | 64.1 → 9.7 | 37.1 → absent |
| trivial 1 | 68.55 → 28.62 | 62.6 → 23.1 | 63.8 → 9.7 | 37.6 → absent |
| trivial 2 | 79.12 → 41.21 | 73.3 → 33.6 | 63.9 → 9.7 | 48.4 → absent |

All three samples are retained, including the first repeat. Median complete profile measurement: block 102.48 → 85.21 ms (16.9% less), trivial 70.46 → 29.64 ms (57.9% less). Median request: block 96.7 → 76.7 ms (20.7% less), trivial 62.6 → 23.8 ms (62.0% less). Median step allocation: block 87.0 → 32.8 MB (62.3% less), trivial 63.9 → 9.7 MB (84.8% less). Each request loses 103 reads (block 250 → 147, trivial 156 → 53); trivial instrumented calls fall 14,363 → 6,528. Allocation is allocation volume, not retained heap. No retained-memory improvement is claimed.

The changed request still reads current proof evidence and settles once. Changed settle times are block 23.4/10.9/17.1 ms and trivial 9.4/9.5/14.4 ms; these are measured on the small isolated fixture, not evidence that default's durable writer cost is fixed. No gain is credited to changes outside the evidence-currency owner.

Final clean candidate `7a702d38091bcd8929753fd9355fcfc2d197a637`, run `cbab53d1d8de`: **5 executed, 35 passes, zero failures/errors**, 8499 ms across the five vars. The check published/reloaded the candidate on disposable live-store branches and booted its committed archive in a throwaway JVM. Operator total 58,397 ms: publication rehearsal 22,182 ms, boot 31,868 ms, test phase 8587 ms (phase scopes overlap and are not summed). Publication itself reported 19,041 ms. These are platform-check costs, not ordinary probe timings, and no improvement to them is claimed.

Full working-tree lint completed after removing diagnostics with `clj-kondo --lint src script test --cache false`: **0 errors**, 1664 warnings, 7497 ms; warnings include pre-existing code outside this slice. The lane commit repeats the required isolated check. The solved task is retired and its regression untagged. Net source change is +11 lines; tests +30 lines.
