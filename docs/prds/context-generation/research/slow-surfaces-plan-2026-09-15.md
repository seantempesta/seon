---
type: research
status: proposed
tags: [performance, class/n9, class/p1]
---

# Slow surfaces: delete repeated acquisition first — 2026-09-15

**Boundary.** Research only; no source edits, tests, adoption, fixture writes, or lifecycle operations. Read AGENTS.md “How we work here” and §2.1 and both assigned issues end to end. Read the open N9 members: complete publication, AI context, unchanged renderer calls, producer replacement, and core namespace pages. Their old measurements are historical, not today's profile. Inspected history through `5ffc491ae`; source references below describe the concurrent tree at `4c8740cf0`. Default PID **69622**, PREPL **55914**, remained alive. No foreign session was operated.

## Measurements and priority

Every total below accounts explicitly for **unattributed time**; the available evidence cannot honestly locate every millisecond. Read-only rules preclude fresh measurements of adoption, installation and boot. Frequencies and savings below are planning assumptions, not observed rates. Rank is estimated seconds saved × uses/hour; adoption and hook are alternative routes to the same work and must not be added together.

| Priority / surface | Observed breakdown, milliseconds | Waste hypothesis → structural kill; expected after | Assumed uses/h → saving/h |
|---|---|---|---|
| 1. (a) Development adoption | **74,210** converging follow-up; all phase time unattributed. This is the **isolated checkout**, not default: [adoption evidence](adoption-contract-freshness-2026-09-15.md#reproduction-results-on-the-isolated-checkout). Other samples 75,040 / 92,810 / 81,140. A one-line default adoption was not performed. | `cluster.clj:1881` derives published projection, reconciles all declarations, derives target projection, reacquires SCI and arms JVM even on the scalar path. Carry the published/target projections and admitted changed identities through adoption; derive only changed dependencies. Target **<10,000**, not a measured prediction. | 6 → **385 s** |
| 2. (c) First test | **Unknown**: today's [one-namespace log](p1-class-fast-2026-09-15.txt:13) prints acquisition twice, then arming, then first test at **20:09:53.819964Z**; neither acquisition nor arming nor JVM start has a timestamp. Test duration is not startup duration. | `test/arm.clj:237` calls `packaged-test-projection` twice; each walks predicate owners and builds a projection (`:34`). Load declared predicate owners and selected namespaces, then acquire once and carry it into the existing arm. Preserve complete program arming. Expected **one build instead of two**; milliseconds unknown. | 12 → unknown; high-frequency priority provisional |
| 3. (b) Hook | `logs/hook-debug.log:1037–1041`: edit **15:07:56**, convergence **15:08:17 CST** = **21,000**, comprising configured quiet delay **5,000** + **16,000 unattributed** (launch/queue/publication/adoption). Later edit bursts previously ended at exit 124; these are not successful latency samples. | `bin/seon-hook:1281–1302` sleeps for quiet before admission. Remove compulsory quiet time; drain latest pending paths when current publication settles. Apply adoption kill above. Target **<10,000** for isolated edit; zero deliberate quiet delay. | 6 → **66 s**, overlaps row 1 |
| 4. (e) Juniper install | Owner-reported **36,000–44,000** today; no matching timestamped install log established here, all unattributed. | `test/seon/context_blocks_fixture.clj:219` submits schema-source on every install before reseeding. Proposed: submit only absent/changed declarations, with that decision at schema admission; retain the actual agent graph. Expected zero declaration recompilations for identical reinstall; total target **<5,000**, unverified. | 2 → **62–78 s** |
| 5. (d) MCP status | One wall measurement **402**; complete response, all three plumbing pings replied. Earlier **10,000** timeouts are in [existing issue](../../../seon/issues/default-component-probe-times-out-after-adoption.md). Internal split unknown. | `cluster.clj:515,3232` materializes problem rows then counts them; `problems.clj:357` derives every family. Derive counts through the same family queries without restoring full error payloads. Expected **<402**, saving unknown; do not attribute previous timeouts to this without a profile. | 12 → **0–4.8 s** |
| 6. (f) Boot | Runtime readiness reports **9,007**, all phase time unattributed; owner's earlier **7,449** is another boot. `cluster.clj:3173` excludes the caller's preceding require time. | `cluster.clj:3027–3048` already carries source projection and reuses it if schema did not change. **Do not add another reuse mechanism.** Remove any repeated acquisition only if a phase profile demonstrates it. No supported saving or after-number. | 0.25 → unknown |
| Unranked. (g) Provider overhead | Debug GET **765.363**, HTTP 200. Ledger turn 44 shows **10.4 s** wall; historical provider latency is unavailable on its attempt. Hence wall minus provider = **unknown**, not zero. | `turn.clj:3835` writes latency to the model's mutable last-observation, not the attempt. `cluster/prompt.clj:1` now reads retained history: the old N9 fresh-render issue is not proof of present waste. First retain per-attempt latency at settlement; then delete only measured repeated derivation. No defensible expected saving. | 30 → unknown |

## Named read-only probes: hypothesis → evidence → verdict

* **H1: status always exhausts its bound.** Timed one supported `runtime_status(root, default)` → **402 ms** → falsified; current health observation works.
* **H2: the ledger permits historical provider subtraction.** JVM query with explicit `(seon.operator/connection "default")`, acquired projection, and joins over turn opening/closing instants plus attempt latency → **0 rows, 2,868 ms**. Source confirms model-only latency → unsupported. Two preliminary queries were invalid (invented ordinal; sorting an unbound-projection refusal); neither is timing evidence.
* **H3: full projection acquisition remains expensive.** JVM `projection-from-database` on `@connection`, timed with `nanoTime`; `ThreadMXBean.getThreadAllocatedBytes(threadId)` before/after → **2,640.673917 ms, 13,828,110,464 bytes, 2,584 forms**, platform PREPL thread. `schema.clj:2418–2467` queries all schema/contracts/source rows and rebuilds. Confirmed **N9/P1**, but this independent cost is **not** a measured component of the historical adoption/install totals. Carry-on-value eliminates this call where inputs are unchanged: **0 calls / 0 allocation from this seam**, not a cache on top. No stack-based attribution or JFR claim is made.

## Exact proposed regressions and ownership

Simplest first. All use canonical fixtures and armed contracts when implementation is authorized; none ran here. Only the duplicate-acquisition failure is established directly by current control flow; other failures are hypotheses to reproduce before editing.

| Namespace / proposed test | Assertion | Prospective owned files |
|---|---|---|
| `seon.test.runner-test/initialization-acquires-one-projection` | One initialization records exactly **1** packaged projection construction (today **2**); installed wrappers cover every armable program Var. | `src/seon/test/arm.clj`, `test/seon/test/runner_test.clj` |
| `seon.cluster.source-test/scalar-adoption-does-no-global-projection-build` | A body-only edit installs changed behavior; **0** full projection builds; unchanged contract validators retain identity. | `src/seon/cluster.clj`, `src/seon/schema.clj`, `src/seon/sci/eval.clj`, `test/seon/cluster/source_test.clj` |
| `seon.dev.hook-test/idle-edit-starts-without-quiet-delay` | Publication admission follows the edit event without a timer; edits during publication produce exactly one latest successor batch. | `bin/seon-hook`, `.claude/seon-hook.edn`, `test/seon/dev/hook_test.clj` |
| `seon.context-blocks-test/reinstall-skips-identical-declarations` | Second install keeps identical schema projection members, performs zero declaration evaluations, and restores all four orders/seven steps. | `test/seon/context_blocks_fixture.clj`, `test/seon/context_blocks_test.clj` |
| `seon.cluster.mcp-test/status-counts-without-error-payload-restoration` | Nonempty error families have exact counts; **0** payload restores; missed ping still reports unknown. | `src/seon/problems.clj`, `src/seon/cluster.clj`, `test/seon/cluster/mcp_test.clj` |
| `seon.cluster.boot-test/unchanged-boot-acquires-projection-once` | Exactly one acquired projection per exact branch generation; complete schema and instrumentation remain available. May already pass. | `src/seon/cluster.clj`, `test/seon/cluster/boot_test.clj` |
| `seon.turn-test/attempt-latency-survives-later-model-call` | Two attempts retain distinct supplied latencies after the model last-latency changes; ledger subtraction uses their sum. | `src/seon/turn.clj`, `resources/seon/schemas/seon.ai.attempt.edn`, `src/seon/render/transcript.clj`, `test/seon/turn_test.clj` |

**Dependency ledger:** Datahike branches point at the selected stored roots (`reference-code/datahike/src/datahike/versioning.cljc:251`); SCI fork copies its env with a new generation (`reference-code/sci/src/sci/core.cljc:345`). Existing first-party carriage is `cluster.clj:3027`; strengthen it rather than inventing another base/cache.

**Owner decision, hook scheduling:** (1) **Recommended:** drain immediately after completion, 0.5–1 engineer-day; no intentional idle delay, gives up burst quieting. (2) explicit editor batch-complete event, 1–2 days; guaranteed one publication per signalled batch, gives up unsupported editors. (3) retain five-second quiet window, zero work; existing coalescing guarantee, gives up five seconds per isolated edit. Estimates include regression work.

**Ownership/protection:** this lane owns **only this note**. Final `git status` marks these prospective implementation files protected: `src/seon/{cluster,config,db,effect,instrument,problems,render,schema,turn}.clj`, `src/seon/render/{value,walk,web}.clj`, `src/seon/schema/datahike.clj`, `src/seon/sci/{admit,eval,kernel}.clj`, `src/seon/test/runner.clj`; several corresponding tests and P1/fixture issue/evidence files are also dirty and untouched. All other inherited changes were preserved. Recheck protection at implementation start. No new claim of a resolved issue; this is the N9 investigation plan and the explicit record of missing timing evidence.

## Landing

### startup-and-hook-waste — 2026-09-15

Read this plan, `.agents/skills/clojure-testing/SKILL.md`, `bin/seon-hook`,
and `.claude/seon-hook.edn` end to end; read AGENTS.md §2.1/§2.3 and the
data-oriented-clojure, REPL, and flow skills. Initial owned paths were clean.
Default PID **69622**, PREPL **55914**, answered status and the JVM probe.
No test JVM was launched; the assignment reserves those for the orchestrator.

**Dependency ledger and archaeology.** `981ad0b55` extracted the fast
launcher's arming owner. The worker still has a duplicate initialization in
`src/seon/test/runner.clj`; only its actual `arm-contracts!` delegates to
`seon.test.arm`. Malli's `-schema`, `-primitive-fn?`, and `-f->original`
(`reference-code/malli/src/malli/instrument.clj:43,15,8`) ground the existing
`seon.instrument/armable` and wrapper restoration; no contract roster was
introduced. Hook history `ef424c37c` already supplied one locked pending
batch and one detached worker. This change removes that worker's quiet
timer and retains its bounded `publish-source-paths` and completion drain.

**Startup probe: hypothesis → number → verdict.** The unchanged isolated
runner initializer, called through the default JVM REPL at **21:20:07Z**,
constructed **2** packaged projections and covered **994/994** armable
program Vars. The revised `seon.test.arm/initialize-contracts!`, already
hot-loaded in default at **21:18:21Z**, constructed **1**, returned that
identical projection object, and covered **994/994** Vars (**0** unarmed).
Warm call times were respectively **395.305417 ms** and **775.136583 ms**;
these are different loaded generations/warmth, not a cold-start speedup
claim. Entering wrappers and Malli's registry were restored in `finally`.
The exact recurring probe is
`seon.test.runner-test/initialization-acquires-one-projection`; it uses the
existing instrumentation-preservation fixture and real arming.

Both `PACKAGED TEST PROJECTION ACQUIRED` owners and `CONTRACTS ARMED` now
print UTC instants. Cold-worker time to first test remains **unverified**
until the orchestrator supplies its next gate log.

**Hook probe: hypothesis → number → verdict.** The Babashka program stored
in `test/seon/dev/hook_test.clj` runs the real file lock, enqueue, drain and
terminal-result writer, substituting only the publication effect. Before:
publication `23cdd2b9-cbea-482f-8f33-d2d6e11f58ba`, edit
**21:16:43.251067Z**, terminal **21:16:44.255550Z**, **0** publications:
the old quiet branch refused when its one-second admission bound elapsed.
After: publication `62ca56bb-a67e-4e91-940c-8b89a44a20b2`, edit
**21:17:37.112112Z**, admission **21:17:37.113303Z** (**1.191 ms**).
Three edits during that publication all returned successor ID
`2d622358-5e74-4164-9f3d-71af8d0d26d3`; the only successor contained
`["a.clj" "b.clj" "c.clj"]`. First completion **21:17:37.114812Z** to
successor admission **21:17:37.114968Z** = **0.156 ms**. Exactly **2**
terminal results, no remaining pending batch or worker claim. The legacy
24-hour quiet setting is deliberately present in the probe: admission no
longer consults it. These are probe effects, not default convergence.

**Real development-hook observation.** Timestamped edit/admission logs now
distinguish scheduling from publication. The real edit of `runner.clj` and
`hook_test.clj` joined publication `0f9d2d2c-f034-4697-9bed-8bba4440570e`
at **21:18:45.494210Z**, while another publication was running. That
publication completed **21:19:00.690461Z**; ours was admitted
**21:19:00.698067Z**, **7.606 ms** later, with other edits in its one
successor batch. Terminal refusal at **21:19:54.233340Z** is **not**
successful convergence. The original five-second quiet-window sample in
row 3 remains historical; an isolated before/after success comparison is
not established by these concurrent runs.

The pre-change real edit was publication
`45469204-8ff0-4ad7-9fd0-89fcb90ce89c`: **21:14:39.856709Z** edit to
**21:17:13Z** refusal = approximately **153,143 ms** (the old worker logged
only whole seconds). The post-change real edit above took **68,739.130 ms**
to refusal. Neither is a successful-adoption latency sample, and their
difference cannot be attributed to the deleted five-second timer.

**Verification boundaries.** Default's MCP value projection reports
`:seon.config/missing-effective` for missing
`:seon.test/check-time-limit-ms`; the bounded JVM probe prints its result
before that refusal. This is already recorded in
[the partial reload issue](../../../seon/issues/partial-hot-reload-produces-mixed-code-with-no-warning.md).
Shared publication also reported `:stale-branch-head` during another
source change. No foreign file, cluster lifecycle, or session was repaired.
The full failure envelope was unreadable; the existing
[publication output issue](../../../seon/issues/init-failure-dumps-entire-prepl-event-history.md)
owns that output class.

**Pending scope decisions at this checkpoint.** The assignment permits
only log-line edits in `runner.clj`, but its duplicate initializer still
constructs twice. Delegation to the existing arm owner was requested.
`seon.dev.edit-feedback-test/concurrent-editors-queue-without-waiting-for-publication`
also assumes the retired quiet window yields exactly one batch; permission
to update that out-of-scope test was requested. At **21:18:37Z** another
lane edited `bin/seon-hook` and `.claude/seon-hook.edn` to add reaching-test
feedback. Those hunks were preserved; they are not this lane's work.

The explicit runner restriction is preserved: its only change is the
acquisition log timestamp. The complete cold-worker row 2 claim therefore
remains open in
[isolated runner duplication](../../../seon/issues/archive/isolated-runner-duplicates-fast-initialization.md).
The old integration test and AGENTS.md quiet-window claim are recorded in
[quiet-window assumptions](../../../seon/issues/hook-quiet-window-assumptions-survive-immediate-drain.md).
No out-of-scope implementation was silently substituted. The new startup
regression exercises the assigned `seon.test.arm` owner.

Static verification: clj-kondo reports **0 errors**, **3 existing warnings**
(the dynamically called private initializer and two shadowed bindings in
runner.clj); `git diff --check` is clean. Markdown hook feedback reports
unrelated pinned-revision errors in the AGENTS audit note. Canonical tests,
platform gate, cold-worker timings, and successful default convergence
remain for the orchestrator; they are not claimed green here.

### Scope extension completed — 2026-09-15 21:33Z

The owner authorized worker delegation and the existing hook integration
test. Commit **`db7e653ca`** changes exactly
`src/seon/test/runner.clj`, `test/seon/test/runner_test.clj`, and
`test/seon/dev/edit_feedback_test.clj`. The worker now calls the existing
arm initializer; its unused arming-decision copy is deleted. The regression
exercises both entry points. No reaching-tests-tier file or hook hunk was
edited during this extension.

Default **PID 69622**, direct JVM REPL at **21:31:53Z**, after hot-reloading
only the worker's private initializer from the edited source:

| Entry point | Packaged constructions | Identical carried projection | Armable / unarmed | Warm elapsed |
|---|---:|---|---|---:|
| `seon.test.arm/initialize-contracts!` | 1 | true | 998 / 0 | 207.581708 ms |
| `seon.test.runner/initialize-contracts!` | 1 | true | 998 / 0 | 197.990458 ms |

Entering wrappers and Malli registry were restored. These are warm REPL
observations, not cold test-JVM timings. MCP rendered the final nil normally
on this pass; the earlier missing-config observation is historical.

The same default REPL ran the exact `queued-editor-probe` stored in the
integration test through the existing bounded subprocess owner and Babashka
REPL. **Exit 0**, empty stderr; **2** publications, **6** edit responses
while the first publication was active, **1** successor containing **5**
distinct paths, **2** terminal refusal results, no pending batch, and no
worker claim. Edit events are injected before the first publication
returns, so batch membership no longer depends on a sleep or quiet window.
The probe directory was removed in `finally`.

The isolated-runner issue is resolved and archived. The hook-assumptions
issue is narrowed to AGENTS.md §6's remaining quiet-window wording. Static
analysis: **0 errors**; two existing shadowed bindings and the
test-referenced private namespace-discovery helper are warnings.
`git diff --check` passes. No test JVM was launched. The final gate request
names `seon.test.runner-test`, `seon.test-runner-test`, `seon.dev.hook-test`,
and `seon.dev.edit-feedback-test`; canonical and cold-worker evidence remain
the orchestrator's next gate.
