---
type: notes
status: reviewed-fix-forward
created: 2026-10-07
lane: s5a-review-codex
---

# Landed-diff review: s5a-reply (Codex)

Reviewed commit **7b5535cf66d41ed48d36f80763383e03b818a9f6**, parent **56f0efe0eb4e274248ac1730afa8e61de66ba196**.
This is a review of installed executable bytes, not approval of the earlier design. Authorities read: CLAUDE.md,
lane-rules.md, the complete s5a design (including v2 and Landing), agent-loop.md, and the Clojure, REPL, testing and
Datahike skills. No production source edits, test runner, provider call, worktree, or other lane's session was used.

**Verdict: fix forward.** Keep the shared held-receipt path and automatic-routing deletion. Repair the failure-path
contract mismatch and the three missing contracts, then obtain root's focused proof and actual parent red evidence.
The performance improvement is plausible and the removed work is real; the remaining pass cost is still over budget.

## Findings, ranked

### P1 — the phase-failure arm cannot pass its submitted forms to the new helper

`src/seon/turn.clj:5125` constructs submitted evaluations with
`:seon.cluster.eval/ns {:seon.ns/name namespace-symbol}`. In the `evaluate-sources` phase-failure arm,
`:5169`–`:5172` selects source and namespace from those evaluations and passes the resulting map to `with-saved`.
That namespace map has **no `:db/id`**. `with-saved` (`:5047`, contract `:5051`) requires admitted forms;
`resources/seon/schemas/seon.turn.loop.edn:163` requires `:seon.cluster.eval/ns`, hence `:seon.db/ref`.
`resources/seon/schemas/seon.db.edn:40` permits a map only with `:db/id :int`.

The armed helper in the throwaway JVM rejected exactly this producer shape, in **12.942792 ms**:

```clojure
(#'seon.turn/with-saved
 {:seon.agent/id "s5areview" :seon.turn.work/situation :call
  :seon.turn.loop/forms-run 1 :seon.turn.loop/outcome :error}
 {0 {:seon.cluster.eval/source "(+ 1 1)"
     :seon.cluster.eval/ns {:seon.ns/name 'user}}}
 nil)
```

Result: `:seon.error/type :seon.instrument/contract-error`, operation `seon.turn/with-saved`, check `:input`,
problem path `[0 :seon.cluster.eval/ns]`. The message says argument 1 `forms` expected `:seon.db/ref`, got a map.
This happens even with **no saved receipts**, because argument validation precedes the helper body.

`settle!` runs before this helper. Thus the arm intended to deliver a saved phase refusal introduces a new failure
after recording. The arm is absent on the parent. Normalize the producer's namespace to the existing lookup-ref
form; do not broaden every reference schema to accommodate it.

Proof limit: the armed boundary and its exact producer shape were verified; I did not inject an entire
`evaluate-sources` phase failure through MCP. That entrance regression is still needed, including one receipt saved
while later planned forms remain unsaved. New task **seon.turn/phase-failure-passes-a-pulled-namespace-to-with-saved**,
handle **06cd4d9307eb**. Proposed proof (not installed):
`seon.mcp-entrance-test/a-phase-failure-answers-the-saved-refusal`.

### P2 — three changed functions lack contracts

The baseline-aware installed `seon.contracts-compile-test/check` against the actual parent reports:

| Changed function | Landed source line | Finding |
|---|---:|---|
| seon.render.transcript/candidate-history | 613 | incomplete-contract |
| seon.turn/asked-value | 3154 | incomplete-contract |
| seon.turn/evaluation-terminal-data | 3288 | incomplete-contract |

This is not a demand to backfill untouched legacy functions. All three bodies change in this commit, and the
review rules require contracts on changed functions. The ordinary compile census cannot detect missing declarations;
it compiles only declarations that exist. The other new helpers do declare contracts, and those contracts compile.
Several new result contracts use broad `:map`; the missing contracts above are the concrete checker refusals.

New task **seon.render.transcript/held-reply-changes-lack-complete-contracts**, handle **774c3d7fe02b**.
Proof: baseline-aware `check`, plus `seon.contracts-compile-test/a-new-or-changed-function-needs-a-complete-contract`.

### P2 — the claimed identical parent reds are not independently supported by retrievable evidence

The Landing section names seven namespaces and two unnamed transcript tests, not an assertion-by-assertion record.
I queried the four cited run identities on default through MCP with an explicit connection:

```clojure
(seon.db/pull-many
 (seon.db/db (seon.cluster.boot/connection "default"))
 [:seon.test.run/id :seon.test.run/git-sha :seon.test.run/stopped]
 [[:seon.test.run/id "2318e41ae2a1"] [:seon.test.run/id "e3b54636427c"]
  [:seon.test.run/id "659f66f8104f"] [:seon.test.run/id "9a4e634452ad"]])
;; value [nil nil nil nil], MCP :ms 1
```

Absent evidence is not proof of different failures, either. **The assertion of identical reds remains unverified.**
The named parent performance revision `0c2a12900` also is not this commit's immediate parent `56f0efe0e`; do not call
that table a newly measured HEAD^ comparison. I started no test runs, as expressly instructed. Root needs the saved
failure data, test identities, tested program identities and normalized assertion diffs, or a central comparison on
`56f0efe0e` and `7b5535cf6`. The fixture explanation `seon.test-support/task-row-mints-an-unreadable-symbol-for-a-digit-first-handle`
(handle cce8dbd3d718) is a claim, not a substitute for those records.

### Remaining measured budget defect — full-program renderer setup and repeated database work

Not introduced here, but still a blocker to the spec's performance target. The precise measured attribution is below.
Existing tasks: **seon.render/the-viewer-scans-every-program-function** (f55702031f83),
**seon.db/an-identity-join-scans-the-whole-ref-attribute** (ca5598748c9d), and
**seon.turn/a-trivial-agent-turn-takes-eleven-seconds** (f5ec53da8a90).
No duplicate performance task was created.

## Reply-path audit and probes

Throwaway root: `/tmp/seon-review-session/s5a-review-codex/root`, launched only through `bin/seon --root ... start`.
The successful measurement JVM was PID **98495**, start **2026-10-07T12:23:41.408Z**, archive **9c07dfb58d863a33d0545f58261970b510dad337**.
`git diff 7b5535cf6 9c07dfb58 -- src resources test script deps.edn reference-code` was empty: executable inputs
and dependency pins exactly match the reviewed commit. Default's JVM was never loaded with prototype code.

The probes call production `seon.mcp/call` and compare its text items with production `history-entries` for the saved
run at the captured post-call database. They are direct observations, not invocations of test bodies or a parallel test
runner. The timing below includes the stored-response read and comparison, so it is **not** pure MCP pass timing.

| Case / exact source | Observation | Probe ms |
|---|---|---:|
| `(+ 1 2)` then `[nil false ""]` | SCI held and stored replies equal | 141.101 |
| `(def held-value 7)` | equal, temporary-state note retained | 189.938 |
| `#'clojure.core/inc` | declared renderer, equal | 497.472 |
| `(in-ns 'scratch.review)` then `(in-ns 'my.agents.root)` | both namespace responses equal | 86.949 |
| `(set! *print-length* 2)` then `(range 5)` | equal | 80.637 |
| `(/ 1 0)` then `:after` | equal, MCP isError true | 100.215 |
| `(seon.db/q '[:find ?id . :where [?a :seon.agent/id ?id]] (seon.db/db))` | equal, read evidence accepted | 104.062 |
| `(defn held-inc "Add one." {:malli/schema [:=> [:cat :int] :int]} [x] (inc x))` | equal, but over 1 s | 1328.796 |
| `{:seon.render/ai (fn [_] (throw (ex-info "review renderer failure" {}))) :probe 1}` | fallback and diagnostics equal | 147.276 |
| SCI `(+ 1 1)` then `{:a}` then `(+ 2 2)` | equal, MCP isError true | 137.258 |
| `(my.turn/complete {:my.turn/result "done"})` | equal | 111.171 |
| three deliberate core faults on the hosted agent's connection, then `(+ 2 2)` | held and stored errors equal | not separately timed |
| JVM `(+ 1 2)` after earlier runs | fresh reply has `:ms 1`; stored history omits it | 442.521 |
| JVM `(+ 1 1)` then `{:a}` then `(+ 2 2)` | only first value returned, isError false | 49.776 |

The JVM duration difference is the **documented intended behavior change**, not an undisclosed regression. The JVM
read-error omission remains a real defect: task **seon.turn.batch/a-jvm-read-error-drops-the-form-and-every-later-form**
(e333444a896c). Parent `jvm-read-next` already returns `:seon.batch/unread-from` for any reader exception; that body
is unchanged by this diff. Saved-versus-returned equality cannot detect an omitted error when neither side contains it.

Fault-probe detail: first trying deliberate faults on throwaway root itself woke that agent, and the immediate call
answered `run transition refused: agent-already-running`. That attempt did not prove a faulted receipt. Repeating on
the existing hosted `s5areview` connection did: comparison true. No foreign process was touched.

**Closed run / skipped refusal receipts:** reproduced the landed regression's production sequence: one durable
fixture task write opens the submission, then its second form queries its own open run and transacts its
`:seon.turn/closed-tx` before the normal settlement. In **253.401 ms**, MCP returned exactly one item:
`{:seon.error/message "run transition refused: run-closed"}`, `isError true`. `run-receipts` showed two frozen source
rows, neither with shown text. The reply correctly invents no empty receipt results for those unsaved evaluations.

**Other refusal paths, source audit:**

- `settle-batch!` returns the same fault-windowed receipt vector supplied to its successful transaction. On a
  revalidation retry it returns the retry's vector, including `not-admitted` changes, not the first attempt's vector.
- `settle-batch-refusal!` returns receipts only if `closed-in?` sees this run's added `closed-tx` datom in the
  writer report. The close and receipt assertions are inside the same `open-run-tx-call` block. A skipped block
  produces no held receipts even if the error-recording half commits.
- `refusal-terminal-data` exposes the single proposed receipt; `resume-turn` conditions its inclusion on that same
  writer evidence. Its caller's admitted-form conversion is the P1 above.
- Refused-refusal and pre-evaluation failures with no receipt answer a refusal alone. A timeout may still be running;
  it is not a claim to possess saved receipt results.
- JVM `evaluation-record` and `evaluated-sources` share `entry-evaluation`; `evaluate-batch` incorporates the settled
  entries before MCP calls `evaluated-sources`. No-recording fallback still returns held shown values plus the
  recording error, explicitly **not** a claim that those values were stored.

Limits: no full entrance injection of phase failure, partially successful program revalidation, or failed storage.
Static inspection is not marked as an executed pass. The existing `not-admitted` unit proof must run centrally.
In particular, "reply exactly what was stored in every path" is too strong: the P1 refutes it for the phase-failure
arm, JVM timing intentionally differs, and unrecorded failures explicitly answer unrecorded values/errors.

## One converter and printer

**Shared, verified in source:** MCP `observed` → `history-entries` → `candidate-history`'s held arm → `held-entry`
→ existing `receipt-entry` and `repl/entity-emission` → `repl/response`. The page preview feeds the same
`evaluated-sources` arm. Stored history still uses `receipt-entry`. `fault-values` and `with-errors` are extracted
shared transformations; they feed the existing value printer. `turn.batch/entry-evaluation` factors the existing
recording mapping and is used on both sides of the JVM boundary; it is not an independently implemented reply codec.
No new value printer or independent held-reply converter was added.

`history-entries` still builds both `printed-value` with `repl/response` and `bytes` with `repl/text`, which itself
calls `response`. That is repeated envelope composition in the same existing owner, not a new printer introduced
here. The measured held reply is only 4–6 ms per 100 forms. Arbitrary result renderers are not re-run by held reply
assembly; saved shown text passes through the existing response grammar.

The startup health report's `entity-emission` input error did not reproduce for the ordinary held cases above.
Its union accepts a complete `seon.eval/entity` without a database. It is not evidence that **all** held replies fail.

## Routing removal

The automatic route is removed end to end: `form-problem`, `assignment-value`, `planner-scoped-attempt?`, `problem-id`,
the two delayed resolutions, their `resume-turn` caller, request threading, and both `form-problem` schemas. Search
of executable source, tests and live schema/task resources found no surviving callable reference to these deleted
Vars. The task prose uses the explicitly historical wording "since-deleted form-problem".

The old names in `test/seon/schema/datahike_parity.edn` are a historical baseline. Its consumer builds the baseline
projection from **that baseline's own forms** (`datahike_test.clj:88–110`); this is not a live dangling schema edge.
Explicit `my.message` assignment and `form-settlement` states remain with their consumers, as v2 explicitly chose.
The retained `problem_routing_test` proves explicit messages, not automatic assignment. The affected loop and turn
expectations were converted. No additional dead automatic-routing caller or test was found.

## Cost remeasurement and attribution

One hosted agent, same throwaway JVM; one warm-up and three observations per mode/size. Workload is `n` copies of
`(+ 1 1)` separated by newlines, through the real MCP bridge. Allocation is **whole-JVM allocated bytes**, not retained
heap or RSS. Profiler marks are taken before the allocation/timing bracket; explain runs after it. Armed-call totals
include explain's documented counter-observation overhead and can differ with which context cells already exist.
The exact script is committed as `review-diff-codex-probe.clj`; submit its one form over the throwaway root's PREPL
using installed `seon.operator/prepl-value!`. It is a measurement probe, not a test namespace.

| Mode | Forms | Wall ms (three runs) | Allocated decimal MB | Armed calls |
|---|---:|---|---|---|
| SCI | 1 | 101.449 / 147.197 / 134.045 | 45.788 / 61.737 / 50.515 | 8268 / 7952 / 8451 |
| SCI | 10 | 221.022 / 186.368 / 227.860 | 100.987 / 102.417 / 134.736 | 30733 / 30402 / 30230 |
| SCI | 100 | 737.175 / 732.558 / 716.891 | 583.003 / 594.721 / 593.777 | 248721 / 248236 / 248560 |
| JVM | 1 | 70.514 / 58.803 / 44.172 | 25.130 / 25.173 / 26.918 | 3134 / 3132 / 3132 |
| JVM | 10 | 92.974 / 90.538 / 86.098 | 68.173 / 65.536 / 65.630 | 14544 / 14544 / 14544 |
| JVM | 100 | 510.016 / 499.194 / 543.995 | 429.281 / 430.224 / 428.053 | 128664 / 128664 / 128666 |

These are not the original ~300 ms SCI timings: host load/JIT conditions were not controlled against the old run.
I have no independently measured immediate-parent rows and make no new speedup percentage claim. Allocation broadly
reproduces the lane's observation. Even one-form passes exceed the spec's 5 MB allocation target.

Armed profile, SCI 100: `settle-batch!` 362 ms; `evaluate-sources` 312 ms; `db/q` 331 calls / 270 ms. These are inclusive
and **must not be added**. The two per-form queries in `evaluation-terminal-data` still exist, as does per-form
fold work. A later larger-limit profile found `public-functions-in` exactly 100 calls / 96 ms, while `observed`
was 6 ms and `history-entries` 5 ms. JVM 100: `jvm-show` 338 ms, `shown-result` 222 ms, `render.value/prepare`
158 ms, `viewer` 125 ms, `config/effective` 201 calls / 114 ms, and the one transaction 113 ms. Later profile:
`public-functions-in` 100 / 86 ms, `db/q` 103 / 27 ms, `observed` 5 ms, `history-entries` 4 ms.

To attribute allocation rather than guess from inclusive time, I recorded five 100-form passes per mode with a
JDK `Recording`, enabling `jdk.ObjectAllocationSample`, `throttle=1000/s`, stack traces; stopped and dumped it before
reading it with `jfr print --json --events jdk.ObjectAllocationSample --stack-depth 64`. Nearest Seon frame attribution:

| Mode | Samples / total sampled weight | Largest attributed allocation over five passes |
|---|---|---|
| SCI | 2922 / 3098.6 MB | public-functions-in 902.3 MB (29.1%); vector-query-call 610.3 MB (19.7%); query-variable-attributes 143.8 MB; may-read-vectors? 102.3 MB |
| JVM | 2400 / 2134.7 MB | public-functions-in 850.7 MB (39.9%); pull-plan-with-evidence 277.3 MB (13.0%); effective-in 193.5 MB (9.1%) |

Samples estimate allocation distribution, not exact per-function byte counters. Truncated stacks leave 385.5 MB SCI
and 319.0 MB JVM without a Seon frame. Inclusive Malli/instrument wrapper frames contain their callees: their large
inclusive weights do **not** prove validator self-allocation dominates.

Cause of the largest named bucket is visible at `src/seon/sci/kernel.clj:250–262`: `public-functions-in` walks all
functions in `::program-snapshot`, reconstructs namespace symbols, filters and sorts for each value. `render/viewer`
(`src/seon/render.clj:667–678`) asks for it per render. Thus the remaining cost is not primarily the held reply:
it is still full-program work **per form**, database query processing/settlement, and JVM's repeated config/pull work.
The existing viewer task already names that cause.

Dependency authority: target pins Malli **43c6dca8d2d8df3162b13725939954807be4a4ed**. Malli `validator` / `explainer`
(`reference-code/malli/src/malli/core.cljc:2672–2711`) cache on the compiled schema; the checker uses Seon's packaged
projection and Malli function-schema compilation. That seam checks shape; it cannot guarantee a caller constructs the
shape it declares.

Target `reference-code/datahike` pin: **e5453d967b749794944dbdfe64b751519983ba59**. Its transaction report and atomic transaction failure, composed through Seon's writer,
are the authority for the guarded close/receipt decision; no speculative reread is needed.

## Verification, foreign boundaries, and landing

Initial default health: PID 91875, start 2026-10-07T12:18:27.392Z, archive e80131ef1. Both `bin/seon status` and MCP
`runtime_status` reported **faulted**: `seon.turn/show-row` passed nil to shown-failure; `record-fault` branch custody
fault (3d2fb1b41dc8); `entity-emission` input refusal; source/change and adoption calls 1972 / 3725 ms.
These are reported as observed health, not attributed to this diff without reproduction. No reset or restart of
default was attempted or is required for this documentation-only review.

Owned startup anomaly: first throwaway PID 96297, start 12:21:18.810Z, reached readiness after 65,996 ms; publication
accounted for most of the readiness span. Its first measurement PREPL then closed without a terminal result and the
process was gone, with no exception in its log. Cause unknown; no first-attempt timing row was retained or used.
Starting the same disposable root with an owned foreground session produced PID 98495, ready in 10,434 ms. This is
an operational evidence limitation, not a claimed Seon crash diagnosis. Raw logs were preserved in the owned scratch
root until shutdown. These startup costs are far over budget; they are not excused as cold ordinary work.

Archive lint before the note: HEAD e0eca860a, `clj-kondo --lint src script test --cache false --parallel` over a
`git archive`: **0 errors, 1481 warnings, 10,524 ms**. No lint cache was written.

Contracts compile check in the throwaway JVM against that archive:

- Canonical `src` + `test` census: **475 files, findings [], 882.307 ms, 718,216,104 per-thread allocated bytes**.
- Extended `src script test`: **488 files, ten existing script findings, 1675.239 ms, 786,175,368 bytes**. All ten
  are authored Var-based schema forms in `script/seon/dev/docstring.clj` (285, 318, 334, 360) and
  `script/seon/dev/markdown.clj` (1020, 1050, 1090, 1124, 1170, 1215), outside this diff. Existing task
  **seon.contracts-compile-test/script-contract-vars-do-not-compile-against-packaged-projection** (8ec5d0c145f6).
- Baseline-aware changed-source check: three incomplete-contract findings above, plus seven uncontained-throw
  diagnostics. The latter apply the checker's older return-error rule to internal throwing helpers and cross-file
  caller chains; they are tooling output, not seven independently established violations of today's three-catch law.
  They must not be described as a zero-finding diff check.

The complete script census was exploratory; the installed canonical contract census's roots are `src` and `test`.
Neither compilation success nor a schema census proves the behavior of the changed functions.

Foreign boundary at final archive preparation: HEAD 4665fae35c390e4cfc97b990745c679b262d27b6 additionally contains `src/seon/fn.clj` and
`test/seon/fn_test.clj` changes beyond the reviewed commit. Their bytes are excluded from the target behavior and
measurement claims. The concurrent dirty render/transcript, render/web, schema, CSS and skill edits were excluded
by the archive. No foreign dirty files, hooks, lane claims, test JVMs, or sessions were modified.

Final `git archive` of that HEAD plus exactly this review's two new files: **clj-kondo src script test, --cache false,
0 errors, 1481 warnings, 5322 ms**. The final canonical contract compile check over **475 src/test files** returned
**findings [], 3687.306 ms, 754,049,048 per-thread allocated bytes**. The compile operation itself exceeded the
one-second/allocation budgets; existing issue families are the contract-census allocation and slow contract-check
tasks recorded by the prior review. No correctness pass is claimed for its cost. The extended script failures and
baseline-aware missing-contract findings above remain; this documentation commit does not repair them.

Cleanup: `bin/seon --root /tmp/seon-review-session/s5a-review-codex/root down` returned PID 98495 with its matching
start instant and **process-exit? true**. The owned launcher and disposable root/archive directories were removed;
raw probe forms, data, JFR samples and the unresolved first-startup log remain under the lane's owned scratch
directory for inspection. Default needs no restart/reset for these notes. The source fix will need root's ordinary
proof and coordinated reload/restart.

### Central proof request (no lane test runs)

Run these installed tests on the repaired combined program:

- seon.mcp-entrance-test/an-sci-reply-is-what-the-run-saved (include its declared long bound)
- seon.mcp-entrance-test/an-sci-call-answers-what-the-agents-next-context-reads
- seon.mcp-entrance-test/a-jvm-reply-keeps-its-duration-after-an-earlier-run
- seon.mcp-entrance-test/a-jvm-call-is-the-agents-recorded-run-and-its-value-digs-by-handle
- seon.cluster.turn-test/a-red-form-stays-with-its-author-and-the-fold-continues
- seon.turn-test/a-form-whose-facts-are-refused-says-it-was-not-admitted
- seon.contracts-compile-test/a-new-or-changed-function-needs-a-complete-contract
- seon.contracts-compile-test/every-declared-contract-compiles-against-the-packaged-projection

Add the proposed phase-failure entrance regression named under P1; it does not exist yet. Separately recover the
exact red identities from the four cited runs before root's parent comparison; the original note's namespaces and
unnamed transcript tests do not identify a reproducible focused list.

Design check: v2's shared converter and writer-evidence approach survive the review. Its "every terminal path"
claim does not: the phase-failure arm does not honor the admitted-form representation. The fallback can be fixed in
place without reinstating rereads, routing, or another converter. The benchmark attribution also makes the next
performance work concrete: stop scanning the whole program for every shown value.

Review source/test delta: **0 / 0**. The target commit itself is 21 files, +451/-505 total; src +267/-300 (net -33),
test +121/-174 (net -53). This review commits only notes and the measurement form; it proves no shared acceptance.
