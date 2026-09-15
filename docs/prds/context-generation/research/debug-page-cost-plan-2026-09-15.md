---
type: research
status: complete
tags: [render, performance, database]
---

# Debug page cost: remove repeated derivation — 2026-09-15

**Design only.** Read AGENTS.md §2.1/§2.4, the Datastar skill and
[page-speed landing](page-speed-and-estimate-landing-2026-09-15.md) end to end;
also used REPL, data-oriented Clojure and Datahike skills. Default pid **69622**
answered MCP health and JVM probes. No source/test edits, test JVMs, reload,
restart, refork, messages or provider calls. HEAD moved from `5258d7bb0` to
`b80f78a7c` as the concurrent projection lane committed. These measurements
exercise its already loaded definitions, not a clean-HEAD comparison.

## Measured breakdown and hypothesis verdicts

`GET /agent/juniper/debug`: **670.685 ms**, HTTP 200, **146,776 bytes**;
plain page warm **20.551 ms**. The morning 130 ms / 67,410-byte page is a
different population. Current population: **45 turns, 88 evaluations,
28 provider prefixes, 27 stable adjacent pairs, 5 directory evaluations**.
At basis **536871658**, one complete warm ledger costs **645.623 ms**:
**642.904 ms / 1,563.938 MB** constructing/validating its Hiccup and
**2.719 ms / 12.919 MB** serializing it. That partition accounts for the
entire measured ledger; HTTP routing/shell/scheduling is outside this probe.

| Section / hypothesis → probe | ms / allocated MB | Verdict and unnecessary work (source line) |
|---|---:|---|
| Rows + summaries → existing `turn-rows`, `ledger-rows` | 0.48 + 0.15 / 1.54 + 0.29 | Small; keep the single acquisition (`transcript.clj:1180,1608`). |
| Evaluations: equal-basis wrapper loses reuse → `ledger-evaluations` | fresh 40.8 / 83.2; same object 15.3 / 59.2; cold 854.6 / 3,051.1 | Confirmed identity mismatch; repeat evidence checks plus per-evaluation emission/label derivation (`web.clj:2420`, `transcript.clj:1514`). |
| Prefix stability → `prefix-problem` | fresh 287.8–346.0 / 633.4–633.7; same object 14.5 / 28.3 | 28 retained calls carry **291 reads**; GET revalidates stable captured prefixes (`transcript.clj:1872`, `render.clj:1262`). |
| Problems → `session-problems` in normal acquisition order | 500.6 / 1,314.6 | Inclusive of prefix/directory/budget; recomputes historical diagnostics (`transcript.clj:1948`). |
| Directory audit → `directory-problem` | fresh 167.3–225.4 / 369.0; same object 4.7 / 11.7 | Reconstructs and renders five historical directories instead of reading their shown text (`transcript.clj:1821`). |
| Budget → `session-budget` | fresh 23.6–32.6 / 50.4–50.5 | Requeries each provider turn's opening plan step (`transcript.clj:1926`). |
| Stories → all 45 `turn-story` calls | fresh 98.3–102.8 / 289.2–289.8; same object 8.6 / 37.6 | Repeats effect queries, also used by expanded bodies (`transcript.clj:1578,2109`). |
| Three expanded bodies → `ledger-turn-body` + serialization | fresh 19.7–22.2 / 68.0 | Repeats calibration/effects; one calibration alone **2.50 ms / 9.78 MB** (`transcript.clj:1638`). Estimate including construction of an 80 KB string is only **0.232 ms / 1.08 MB**; do not optimize arithmetic. |
| Found values; fallback rebuilds | **0 calls on this route; 0 warning bytes** | Default agent route bypasses found values (`web.clj:3071`). Live fallback now refuses, never rebuilds (`db.clj:952`); old warning frequency cannot explain today's floor. |

Section rows are **separate, overlapping microbenchmarks**, not additive
fractions of the 645.623 ms sample. Fresh-wrapper tests share basis
536871656; later cold sample is basis 536871659. Broad MCP result rendering
can itself store evidence, so bases were recorded, not assumed constant.
Same-object whole-ledger results ranged **77.7–304.8 ms**; identity repair
alone is not proven sufficient for tens of milliseconds.

**Carriage hypothesis → probe → verdict:** two `(seon.db/db c)` values are
equal but not identical; their `datahike.db/committed-value-identity` values
are equal. Raw `@c` lacks projection; `db/db`, `as-of` and `history` carry it.
`carry-projection-state` creates metadata wrappers (`db.clj:160`). The old
timing script uses `@connection` under a supplied projection, masking this
distinction. Two complete ledger probes captured **zero** fallback warnings.
No currently failing page read was observed; attributing old logs to a
specific present section would be unsupported.

**JFR check:** `jcmd 69622 JFR.start name=debug-page-cost-plan settings=profile
duration=30s filename=tmp/debug-page-cost-plan.jfr`, then 30 curl GETs;
`jfr print --json --events jdk.ExecutionSample`. GETs ranged **670.6–1543.4 ms**.
Of **2,509** samples, **1,954** were virtual-thread samples. Top frames:
`Util.compare` 113, `AFunction.compare` 91, hash-map `assoc` 89,
`Util.equiv` 85. Virtual inclusive frames: sorted-set chunk creation 131,
Datahike value comparison 90, temporal merge processing 36. **2,385** samples
had no Seon frame within the truncated stack: these establish collection/query
work, not precise section percentages. Scratch recording/JSON removed after summary.

## Kills, simplest first; exact future regressions

All proposed tests use the canonical database fixture, real SCI and armed
contracts; counters observe real calls rather than substitute results.

1. **Recognize the existing committed identity at retained-call boundaries.**
   Compare dependency identity, plus existing projection/program/input evidence,
   before replaying reads. Never use bare basis `:t`, full DB equality, or grant
   this shortcut to speculative/history/since values. Dependency ledger:
   Datahike `cdcb5792`, `reference-code/datahike/src/datahike/db.cljc:385`;
   temporal identity `query.cljc:2658`; first-party `db.clj:642` already follows
   it. No additional cache. Expected warm prefix **≤15 ms**, evaluations
   **≤16 ms** (estimates grounded in same-object samples).
   **`seon.render.retained-test/equal-committed-database-skips-read-replay`:**
   two distinct carried wrappers of one commit return identical retained bytes
   with **zero second-call read-currentness checks**; changed shown text,
   changed program, and another connection must invalidate. Today pointer-only
   reuse fails the zero-check assertion (`render.clj:1262`, `web.clj:2423`).

2. **Delete directory reconstruction from passive GET.** Expected directory
   cost **0 ms**, replacing the current integrity claim with “not checked”.
   Owner choice (effort estimates): **A (recommended), remove this automatic
   audit, ~0.25 day**, preserving shown text but giving up automatic directory integrity;
   **B, explicit diagnostic action, ~1 day**, preserving the check on demand
   but adding a user action; **C, evaluate integrity at original settlement,
   ~2–3 days plus schema/refork coordination**, preserving automatic evidence
   but widening ownership. No stored synthetic success when evidence is absent.
   **`seon.render.web-debug-test/passive-ledger-never-reconstructs-directory`:**
   a saved directory evaluation appears byte-for-byte, with **zero calls to
   `directory-value` and `render-directory-ai`**, and unavailable audit status.
   Today five saved directories enter that reconstruction branch.

3. **Derive page data once and carry it on rows/request.** Bulk-acquire turn
   effects once, attach summaries for story/body reuse; derive calibration once
   per distinct model; carry evaluation emissions/labels rather than regenerate
   them after retained history acquisition. Use existing acquisition ownership
   (`web.clj:2400`) for stable basis-derived values, not another cache. Expected
   stories **≤10 ms**, bodies **≤15 ms**, budget **≤17 ms**; combined warm
   ledger target **30–60 ms**, an estimate requiring implementation measurement.
   **`seon.render.web-debug-test/ledger-derives-shared-data-once`:** two expanded
   provider cards with the same model call calibration **once**, and each
   turn's effects derivation **once** despite appearing in both summary/body;
   resulting billing/effect text equals the canonical facts. Today both callers
   independently invoke these owners. Token estimation needs no new mechanism.

**Execution answer:** no `system-turn` call occurs on this GET route; buttons
invoke separate actions. It does execute render functions: history misses call
the evaluation pair via `walk.clj:902` → `render.clj:907` → real SCI invocation;
that pair reads saved text (`repl.clj:323,387`), not saved source. Prefix misses
also invoke a renderer. Directory audit directly executes the documentation
owner and AI renderer. No saved source evaluation was found on the inspected
route; this is source-path evidence, not a dynamic zero-call counter proof.

**Ownership / boundary:** this lane owns only this note. Future implementation:
`src/seon/render.clj`, `src/seon/render/web.clj`,
`src/seon/render/transcript.clj`, `test/seon/render/retained_test.clj`,
`test/seon/render/web_debug_test.clj`, optionally `web_context_test.clj`.
At entry, `db.clj`, `problems.clj`, `render.clj`, `render/walk.clj`,
`render/web.clj` were protected concurrent edits; at `b80f78a7c` they are clean.
Remaining dirty/untracked projection/fixture research and issue notes stay
foreign/protected; no implementation/test path above is currently dirty.
Related class record: [namespace-page costs](../../../seon/issues/core-namespace-pages-spend-seven-seconds-without-declaration-fallbacks.md).
Reproduce section probes by adapting
`consolidate_debug_timing_2026_09_15.clj` to explicit `(seon.db/db
(seon.operator/connection "default"))`, no supplied-projection binding, and
measure each named owner with `nanoTime` plus ThreadMXBean allocated-byte
deltas on the MCP JVM thread; never infer HTTP virtual allocations from that
thread. Markdown hook reported 11 cross-file lint errors (visible feedback
names foreign `agents-md-audit-2026-09-15.md` gitlink citations); this note's
`git diff --check` is clean. No foreign session operated. Plan complete;
stop after path-only commit.

## Landing

Implemented by lane `debug-page-cost` on 2026-09-15. Read this approved plan,
AGENTS.md §§2.1/2.4/2.5, the Datastar skill, and the linked page-speed landing
end to end. Also applied the data-oriented Clojure, REPL, Datahike and testing
skills. No subagents, test JVMs, provider calls, agent messages, process stops,
restarts, reforks, or manual Var reloads were launched by this lane.

### Three slices

1. `cfb35a22b`: committed database identity at retained render and history
   acquisition boundaries, with the existing program/projection/input checks.
   Datahike supplies connection, generation and commit identity; temporal and
   speculative values receive no shortcut. The final regression refinement
   reads actual `:seon.eval/shown`, counts real renderer calls on program
   change, and verifies another connection invalidates.
2. `671108b60`: passive directory reconstruction deleted; saved shown text
   stays intact and the audit says **Directory integrity · not checked**.
   The existing namespace-page cost issue now records settlement option C as
   future work in one line.
3. This landing commit: the existing history acquisition carries evaluation
   emissions/labels, turn summaries and per-model calibrations. Effects are
   bulk queried once per fact family, then summarized once per turn for both
   story and body. Root read evidence captures these derivations. Successful
   reuse now carries the database value it just checked, so a later GET of that
   commit does not replay the old basis again. History segments are constructed
   once and reused for joined prompt text. No additional cache or schema.

### Live evidence and timing

Default remained PID **69622**, URL **http://127.0.0.1:7994**. The committed
[probe](debug_page_cost_probe_2026_09_15.clj) measured at basis **536871675**:
retained entries identical; **0** second-acquisition read-currentness calls;
**1** effect batch; **45** turns each summarized exactly **once**; calibration
called **once**, for `deepseek-flash`; **0** `directory-value` calls; **0**
`render-directory-ai` calls; rendered audit text included **not checked**.
The probe calls the real owners, then renders all stories, three bodies, and
one ledger using the carried data. It does not substitute results.

The [section timing probe](consolidate_debug_timing_2026_09_15.clj) now takes
`(seon.db/db (seon.operator/connection "default"))`, with no supplied-projection
binding, and measures `nanoTime` plus the MCP thread's `ThreadMXBean` allocated
bytes. Final form explicitly warms the complete ledger at the captured basis.
The before measurement at **536871662** used the earlier acquisition order;
the after measurement at **536871679** uses the carried data. These overlapping
microbenchmarks are not additive or a controlled same-generation ratio.

| Section | Before ms / allocated MB | After ms / allocated MB |
|---|---:|---:|
| Turn rows | 0.945 / 1.439 | 9.035 / 8.645 |
| Evaluation acquisition | 760.103 / 1432.535 | 5.548 / 7.952 |
| Row summaries | 0.185 / 0.245 | 0.299 / 0.272 |
| Prefix check | 3.370 / 7.536 | 48.607 / 28.526 |
| Problems, inclusive | 51.673 / 62.866 | 95.763 / 67.141 |
| All stories | 153.135 / 291.218 | 0.393 / 0.409 |
| Three bodies plus serialization | 8.781 / 19.879 | 12.638 / 22.108 |
| Whole ledger plus serialization | 819.408 / 1445.772 | 124.245 / 115.363 |

The final directory-status derivation was **0.038 ms / 648 allocated bytes**;
budget was **47.964 ms / 34.725 MB**. Before dedicated directory/budget values
are the approved plan's measurements above, not a new matched baseline.
An earlier after sample at **536871677** measured whole ledger **46.960 ms /
115.375 MB**, acquisition **2.339 ms / 7.932 MB**, stories **0.226 ms /
0.409 MB**, and bodies **5.534 ms / 22.111 MB**. Prefix was cold in that sample
(**271.416 ms**); the later warm-prefix measurement is in the table. Timing
variance remains visible: process-table observation during measurement showed
default and several concurrent worker JVMs consuming CPU. No foreign worker
was changed. These allocated bytes are for the MCP thread, not HTTP threads.

Baseline three `curl -w '%{time_total}'` GETs were **0.555650, 0.593223,
0.686417 seconds**, all HTTP 200. The final three were **8.826322, 0.076125,
0.063841 seconds**, all HTTP 200. The latter two are warm tens-of-milliseconds
GETs; the first is explicitly not a warm success. Earlier concurrent-activity
sequences included **19.539559, 18.348096, 0.588172** and **17.429848,
16.903313, 0.157429 seconds**. This change does not claim a bounded cold GET or
uniform latency during source invalidation. Stop at the three approved kills;
no additional performance mechanism was introduced to chase those samples.

Chrome separately displayed the complete ledger, its billing totals, carried
message/definition summaries, and **Directory integrity · not checked**. The
last expanded provider card showed rebuilt ≈19,015 tokens and billed 19,302.
Its normal disclosure and reply/result layout remained readable.

### Adoption and verification boundary

At entry MCP health and result projection refused missing
`:seon.test/check-time-limit-ms`; bounded JVM `println` still exposed probe
results. This was recorded in the existing
[partial hot-reload issue](../../../seon/issues/partial-hot-reload-produces-mixed-code-with-no-warning.md).
The hook initially encountered source-analysis churn and an incompatible
foreign `:seon.test/adoption-changes` declaration (installed string/one,
proposed ref/many). Later hook activity loaded and armed this lane's owners;
MCP result projection recovered without lane intervention. No foreign schema,
hook, runner, or session was operated to obtain that recovery.

The final source check found both `ledger-data` and the checked-basis update
in default's stored `seon.render.web/derive-context!` source. The live
`acquire-ledger-data` Var bears its `:seon.instrument/var` wrapper. At the
preceding stamp check default had adopted
**6aa9b7fe-c56c-58e0-994e-1462dbc42ad6** while publication was already
**6aa9b989-5e17-5b7e-9af1-14c9201432ef**. This proves the changed loaded owners
and stored program source through hot adoption, not convergence with every
concurrent publication. The final publication log still reported source
changing during incremental analysis. No reset is requested or performed by
this lane.

Added the three named regressions and extended the existing history regression
for carried wrappers and successful checked-basis reuse. Hook syntax checks and
`git diff --check` passed for owned changes. The Markdown hook still reports
11 foreign gitlink-citation errors in `agents-md-audit-2026-09-15.md`.
**Tests remain orchestrator-only and unrun by this lane.** The three namespaces
are in `tmp/orchestrator/gate-requests/debug-page-cost.txt`:
`seon.render.retained-test`, `seon.render.web-debug-test`, and
`seon.render.web-context-test`. No test verdict is implied by the live probes.

Touched production/test paths: `src/seon/render.clj`,
`src/seon/render/web.clj`, `src/seon/render/transcript.clj`,
`test/seon/render/retained_test.clj`, `test/seon/render/web_debug_test.clj`,
`test/seon/render/web_context_test.clj`. Supporting changes: this plan,
the two linked probe scripts, and the existing namespace-page-cost and
partial-hot-reload issue notes. No scratch cluster or worktree was created;
all owned shell commands ended, and unrelated shared-tree edits were preserved.

### Follow-up: unrelated adoption

The final bounded slice replaces adoption-wide retained identity with the
selected renderer's recorded program dependencies, schema inputs, shown-text
inputs, and profile. The acquisition owner checks retained child programs too.
An exposed bulk-effects join now acquires definition facts once rather than
rescanning them for each turn interval. No new cache or cluster lifecycle path.

Entry curl: **17.909071 s, 0.125783 s, 0.119813 s**. After a real one-line
unrelated `seon.schedule` hook adoption: **1.697270 s, 0.059250 s, 0.068848 s**,
all HTTP 200 and **zero evaluation renders**, with source and basis unchanged
through the three GETs. Effects-query equivalence was verified on one basis:
**11,827.265 ms / 3,965.991 MB → 85.570 ms / 91.722 MB**, identical results.
The remaining cold acquisition cost stays open in the
[adoption issue](../../../seon/issues/the-first-debug-page-after-an-adoption-takes-eighteen-seconds.md),
which owns the full evidence, hook test failures/corrections, and foreign
verification boundary. No test JVM launched; the three namespaces were
appended for the orchestrator gate. Default remained pid 69622.

### Batch 3 repair: in-process verification

Read the Batch 3 lane report and named log. The retained fixture's transaction
was refused: its evaluation lacked required run and timestamp facts. Its
database **did carry its projection**, falsifying the proposed carriage cause
for that regression. The fixture now seeds the canonical agent/turn/evaluation
relationship and asserts transaction success. Program-change checks update the
durable function source, acquire that evidence, and install the same authored
form in the private SCI fork. Equal committed wrappers still require zero
second-call read checks; changed shown text, program and connection invalidate.

`raw-output` now diagnoses nil from nonoptional renderers. Explicit optional
source/observation contracts still permit absence; prohibiting all nils removed
the real system opening. This distinction is exercised by the two regressions.

The web-debug error was `:malli.core/invalid-schema :example/order`: earlier
declarations in one settlement transaction were present in its database but
absent from its entering carried projection. `seon.turn/row-tx` now uses the
existing `schema/projection-from-database` owner with that projection as reusable
input for declaration validation. This expands the repair to `src/seon/turn.clj`,
which was clean before editing. Dependency evidence: Datahike transaction
function results are spliced before following operations
(`reference-code/datahike/src/datahike/db/transaction.cljc:1152`); the codec
carries entering projection state (`src/seon/schema/datahike.clj:478`); the
existing exact-database derivation is `src/seon/schema.clj:2452`. Passive GET
does not enter this declaration path. No new cache or schema was introduced.

Each changed definition was evaluated in default before its file edit, called
through the canonical fixture with real SCI/database values, and its returned
test result inspected. Exact in-process commands throughout were:

```clojure
(seon.test/run #'seon.render.retained-test/equal-committed-database-skips-read-replay
               (seon.operator/connection "default"))
(seon.test/run #'seon.render.web-debug-test/turn-details-use-the-loop-opening-and-exact-segments
               (seon.operator/connection "default"))
```

| UTC / run entity | Regression | pass / fail / error | Probe result |
|---|---|---|---|
| 22:17:21 / 64375 | retained | 11 / 10 / 0 | Missing evaluation timestamp exposed |
| 22:18:13 / 64398 | web-debug | 0 / 0 / 1 | Cold canonical bootstrap refused projection |
| 22:19:37 / 64412 | retained | 0 / 0 / 1 | Same bootstrap boundary |
| 22:21:24 / 64436 | retained | 0 / 0 / 1 | Same bootstrap boundary |
| 22:31:14 / 64450 | retained | 17 / 8 / 0 | Compiled fixture callable still original |
| 22:31:36 / 64455 | retained | 25 / 0 / 0 | Corrected program evidence and callable |
| 22:31:45 / 64459 | web-debug | 89 / 3 / 0 | Optional absence rejected |
| 22:32 / 64461 | web-debug | 89 / 3 / 0 | Observed system opening result |
| 22:33 / 64463 | web-debug | 89 / 3 / 0 | Identified optional render contracts |
| 22:34:03 / 64469 | web-debug | 95 / 0 / 0 | Declared absence preserved |
| 22:34:35 / 64472 | retained | 24 / 1 / 0 | REPL reader resolved diagnostic in user ns |
| 22:34:55 / 64474 | retained | 25 / 0 / 0 | Namespace-correct evaluated form |
| **22:39:36 / 64559** | **retained, adopted** | **25 / 0 / 0** | Basis 536871895 |
| **22:39:43 / 64560** | **web-debug, adopted** | **95 / 0 / 0** | Basis 536871896 |

Verification boundary: cold canonical bootstrap also exposed the separately
recorded [missing carried projection issue](../../../seon/issues/canonical-fixture-population-missing-carried-projection.md).
Temporary JVM bootstrap probes were restored from disk and instrumentation
re-armed (1057 registered/instrumented) before final adoption. No bootstrap or
foreign fixture file is included in this repair. Final runs used the warm
canonical fixture base; they do not prove that separate cold bootstrap fixed.
Web-debug emitted two `agent-already-running` writer refusals while its complete
95 assertions passed; these are not silently omitted from the evidence.

The hook first encountered a stale `current-src` branch head; an operator CLI
attempt also reported no running operator despite PID 69622 remaining alive.
The existing hook owner was then invoked through MCP:
`(seon.cluster/refresh-source! "." ["src/seon/render.clj" "src/seon/turn.clj"
"test/seon/render/retained_test.clj"] "default")`. It completed in 59.507 s.
Default's adopted source commit is **6aa9c904-ca29-5942-b132-e234c779ed89**;
stored program sources contain both repaired definitions. The final two runs
above occurred after that adoption. No process was stopped/reforked/restarted,
no test JVM was launched, and no foreign session was operated.

Gate request rewritten with the three render namespaces once each. The
orchestrator's batched gate remains the final proof. This repair changes only
`src/seon/render.clj`, `src/seon/turn.clj`,
`test/seon/render/retained_test.clj`, and this landing note.

### Batch 7: cold worker projection repair

Read `tmp/orchestrator/gate-results/batch-7/debug-page-cost.md` end to end and
the named log's published-base evidence. Reapplied the testing, REPL, Datastar
and data-oriented Clojure skills. The warm retained test passed 25 assertions
(run 64756); replacing its inherited SCI fork with `cluster-ctx` also passed
(64758). Neither alone reproduced the worker's entering projection.

The [cold fixture probe](debug_page_cost_cold_fixture_probe_2026_09_15.clj)
uses the runner's packaged bootstrap projection, clones Batch 7's published
base through the canonical `create-base`, substitutes only that fresh base for
the scoped test invocation, and constructs fresh SCI contexts with explicit
database, connection and projection state. `with-database` still creates the
test's isolated branch and carries the environment; existing test requests
carry fixed profiles. All substitutions restore on exit and each cloned base
is closed. No default live SCI context is reused.

This reproduced the exact namespace fallback: **17 pass / 8 fail / 0 error**,
run **64770**, 22:57:46Z, basis 536872029. A separate probe found **0 function
contracts in the base context's carried projection versus 1045 in the same
database**. The fixture renderer's contract was absent from the former and
present in the latter. `create-base` called `db/db` before carrying its own
projection, so the worker's schema-only bootstrap projection became its world.
The warm JVM had supplied a richer projection and concealed the defect.

Fix the canonical owner once: after population/connection, derive the exact
database projection, create its projection state, carry it on the connection,
and pass it explicitly to `cluster-ctx`. `test/seon/test_support.clj` was clean
when this lane expanded to that root cause. No renderer, profile, runner or
contract was weakened. The existing seven failing tests provide recurring
coverage when the orchestrator runs them in its fresh workers.

The new `create-base` form was evaluated before editing the file; the same
cold retained probe then passed **25 / 0 / 0**, run **64772**, 22:58:31Z.
The adoption test initially used a stale loaded deftest (64774: 10 / 4 / 0);
loading the current three namespace files through `seon.test/with-test-loader`
removed that additional development-JVM difference. Exact tests below all ran
via `(seon.test/run #'namespace/test (seon.operator/connection "default"))`
inside the committed cold fixture probe's scope, one test per invocation:

| Test (namespace prefix below) | Run / UTC | pass / fail / error |
|---|---|---|
| retained-test/equal-committed-database-skips-read-replay | 64862 / 23:01:24 | 25 / 0 / 0 |
| retained-test/adoption-of-an-unrelated-namespace-re-renders-zero-evaluations | 64776 / 22:59:14 | 16 / 0 / 0 |
| web-debug-test/turn-details-use-the-loop-opening-and-exact-segments | 64781 / 22:59:28 | 95 / 0 / 0 |
| web-debug-test/reverse-declarations-receive-the-actual-relationship-value | 64860 / 23:01:09 | 3 / 0 / 0 |
| web-debug-test/agent-identity-groups-scalars-and-keeps-declared-components | 64863 / 23:01:31 | 3 / 0 / 0 |
| web-debug-test/saved-history-preserves-shown-text-with-numeric-lookups | 64893 / 23:01:51 | 11 / 0 / 0 |
| web-context-test/a-new-message-does-not-reinvoke-the-identity-pair | 64891 / 23:01:37 | 6 / 0 / 0 |

Every test namespace has prefix `seon.render.`. The hook initially refused;
the existing `refresh-source!` owner invoked through MCP timed out at 60s.
A subsequent source query verified adopted commit
**6aa9ccb8-31a7-5bab-95f8-f97e1026ce9c** and the stored `create-base` repair.
The final retained run 64862 is after that verified adoption, satisfying the
same-regression rerun. One MCP request reported session-lost; a new session
completed the saved-history probe. PID **69622** remained alive throughout.
These transient tool failures are observations, not claims of a restart.

An unbound attempt to build a new base from source still reached the separately
open canonical population missing-projection issue. This repair verifies the
gate's published-base path; it does not claim to repair that earlier population
boundary. The loop-opening test again logged two agent-already-running refusals
while all its assertions passed. No test JVM, process restart, foreign session,
or foreign edited file was operated. Three gate namespaces were rewritten once
each; the orchestrator's fresh-worker gate remains final proof.

### Batch 9: the cluster observation depends on adoption

Read the Batch 9 lane report end to end. Its one remaining 9→10 invocation
failure reproduced with the [fresh-base probe](debug_page_cost_batch9_probe_2026_09_15.clj):
run **64954**, 23:07:16Z, basis **536872076**, **7 pass / 1 fail / 0 error**.
The probe clones Batch 9's canonical published base, uses its explicitly carried
projection/environment and fixed profiles, constructs fresh SCI contexts, and
runs the single deftest in-process through `seon.test/run` with default custody.

An invocation trace (run **64957**, 23:07:31Z, same 7/1/0 result) identified
exactly one repeat: **`seon.cluster.status/render-html`**. The other eight
renderers were identity, plan, inbox, settings, notes, namespace, runtime and
faults. Source confirms `seon.cluster.status/snapshot` queries
`:seon.source/commit-id` and puts it in `:seon.cluster.status/adopted-commit`;
`render-html` displays that observation. The test changes that very fact.
This is correct dependency invalidation, not an extra unrelated page render.
The earlier warm fixture's missing contracts concealed this renderer.

Corrected the regression's observation seam: count by renderer, require every
other renderer's count to remain identical, require the status renderer to run
once initially and exactly once more after adoption, and assert that the HTML
contains the newly adopted UUID. The unchanged SCI snapshot assertion remains.
No production rendering behavior or retained evidence was weakened.

Evaluated the corrected deftest before the file edit. The same fresh-base
`(seon.test/run #'seon.render.web-context-test/unrelated-adoption-preserves-pages-with-an-unchanged-sci-snapshot
(seon.operator/connection "default"))` passed **11 / 0 / 0**, run **64960**,
23:08:05Z, basis **536872082**. The complete returned result was inspected.
The committed probe records the exact scoped invocation and owns clone cleanup.

Publication reported its declared timeout, as did the explicit existing
`refresh-source!` MCP call. A subsequent query nevertheless verified the updated
test source in adopted commit **6aa9d043-bf2b-5082-88ed-d346428ef9b9**.
Reloaded the checked-in test through the test loader and repeated the same cold
probe: **11 pass / 0 fail / 0 error**, run **64970**, 23:10:38Z, basis
**536872095**. This is after verified adoption, not an inference from timeout.
No test JVM, restart, refork or foreign session operation occurred; PID 69622
remained alive. Preserved concurrent production edits. The gate request now
contains only `seon.render.web-context-test`. Stop after this path-limited slice.
