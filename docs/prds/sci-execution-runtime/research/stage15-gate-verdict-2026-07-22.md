---
type: research
status: active
tags: [research, architecture]
---

# Stage 1.5 gate verdict — sol read-only pass (2026-07-22)

Orchestrator-accepted: the retire-while-sampling gate is OPEN-with-scope —
no demonstrated production defect; the missing piece is EVIDENCE (a bounded
test driver exposing the retirement race live on both tiers + the frozen-
artifact route/browser/SSE proof). Becomes W5-0, the cutover preflight:
test-only, must finish before the first child-lane deletion.

# Decision: OPEN-with-scope

The W5 cutover gate is **not satisfied**. The sampling mechanism is implemented and extensively focused-test-proven, but the required real-process, in-flight retirement proof was never completed. It has not been obsoleted: the Bun child lane that the proof must exercise still exists, and W5 is the unit that deletes it.

W3 is no longer a blocker; the program records the entire host-parity series complete ([program synthesis](/Users/sean/src/seon/docs/prds/sci-execution-runtime/program-synthesis-2026-07-21.md:987)).

## 1. What “retire while sampling” means

The value browser does not browse persisted `result-edn`. It sends a bounded request back to the exact process that still holds the original raw eval value:

1. An eval’s raw value, effective sampling limits, and producing database value are retained process-locally under its managed eval ID. The JVM implementation uses a bounded oldest-first session slot ([sample.clj](/Users/sean/src/seon/src/seon/host/sample.clj:178)).
2. The HTTP route first authorizes that eval ID against the requesting agent in one immutable database value, then invokes the execution dispatcher ([serve.cljs](/Users/sean/src/seon/src/seon/web/serve.cljs:425)).
3. The dispatcher locates the recorded owner by eval-ID membership across the Bun-child and JVM-host lanes—not by the agent’s current tier ([host.cljs](/Users/sean/src/seon/src/seon/execution/host.cljs:906)).
4. It claims that exact owner generation, installs the request as the lane’s active work, and sends one correlated `value-sample` frame ([host.cljs](/Users/sean/src/seon/src/seon/execution/host.cljs:915)).
5. The required race is: after that frame is observed and sampling is genuinely active, retire that exact child or host session.
6. Acceptance is exactly one terminal bounded `:unavailable` result with `:recompute? true`, with:

   - one send to the old owner;
   - no retry or spawn against a replacement/current-tier owner;
   - no stale late frame changing the settled result;
   - no active request or FIFO residue; and
   - a later recomputation producing a new eval ID while the old ID stays unavailable.

The executable proof definition states these requirements directly ([sampler-retirement proof](/Users/sean/src/seon/docs/prds/source-cleanup/research/stage1-5-child-sampler-retirement-proof-2026-07-21.md:76)).

### Why W5 depends on it

W5 deletes the Bun child lane and its cljs.js execution engine, splits/deletes the child lifecycle tests, and rewires sampling toward the surviving JVM host ([deletion inventory](/Users/sean/src/seon/docs/prds/sci-execution-runtime/research/audit-deletion-inventory-2026-07-21.md:94)). The collision audit says the Stage 1.5 proof deliberately exercises the machinery W5 deletes and therefore must run before deletion unless the owner explicitly rescopes the contract ([deletion inventory](/Users/sean/src/seon/docs/prds/sci-execution-runtime/research/audit-deletion-inventory-2026-07-21.md:168)).

Without the proof, W5 could erase the only opportunity to demonstrate that an in-flight request:

- remains attached to the historical owner;
- settles once when that owner dies; and
- is never silently redirected to the new host tier.

This is why the anchor explicitly sequences W5 after the proof ([program synthesis](/Users/sean/src/seon/docs/prds/sci-execution-runtime/program-synthesis-2026-07-21.md:354)).

## 2. Implementation and proof status

| Area | Status | Evidence |
|---|---|---|
| Bounded get-in/path sampling | Implemented | Requests enforce path, paging, and realization caps before descent; the portable producer returns bounded projections ([value.cljc](/Users/sean/src/seon/src/seon/render/value.cljc:1221), [value.cljc](/Users/sean/src/seon/src/seon/render/value.cljc:1668)). |
| Bun/JVM retained-value transport | Implemented | Roadmap records `bc0a3b11`, with focused CLJS and writer proof and honest retirement handling ([roadmap](/Users/sean/src/seon/docs/prds/source-cleanup/roadmap.md:299)). |
| Exact-owner dispatch/no fresh-tier selection | Implemented and unit-tested | `sample-owner` searches retained eval membership across both lanes ([host.cljs](/Users/sean/src/seon/src/seon/execution/host.cljs:906)); focused test proves current-tier selection is ignored ([host_test.cljs](/Users/sean/src/seon/test/seon/execution/host_test.cljs:1295)). |
| Retirement settlement | Implemented and fake-process-tested | Configuration retirement resolves an active sample unavailable ([host.cljs](/Users/sean/src/seon/src/seon/execution/host.cljs:640)); fake-process tests cover settlement and late-frame suppression ([host_test.cljs](/Users/sean/src/seon/test/seon/execution/host_test.cljs:1311), [host_test.cljs](/Users/sean/src/seon/test/seon/execution/host_test.cljs:1415)). |
| Real JVM same-session value and later replacement | Process-tested, but not the required race | Writer conformance samples a real retained value and then observes unavailability from a replacement session ([host_conformance_writer_test.clj](/Users/sean/src/seon/test/seon/host_conformance_writer_test.clj:760)). It does not kill the owner after an active sample frame is observed. |
| Real Bun retirement | Invocation retirement proven, sampling retirement not proven | The existing integration driver retires a stuck invocation and proves replacement execution ([integration_driver.cljs](/Users/sean/src/seon/test/seon/execution/integration_driver.cljs:190)); it never starts or holds a value-sample. |
| Live route/browser/SSE composition | Open | The only recorded live `/data` observation produced an initial SSE frame but explicitly did not prove browser interaction, later broadcast, ownership, or retirement ([route/UI proof](/Users/sean/src/seon/docs/prds/source-cleanup/research/stage1-5-route-ui-live-proof-2026-07-21.md:83)). |
| Frozen Stage 1.5 matrix | Open | The roadmap’s latest disposition says the missing dependency-critical evidence is a real Bun/JVM page-and-retirement journey plus same-artifact route/browser/SSE crossing ([roadmap](/Users/sean/src/seon/docs/prds/source-cleanup/roadmap.md:507)). |

The earlier U1.5/U4 host-kill drive cannot be reused: its audit explicitly says it proved invocation retirement and replay, with no `value-sample` frame or value-route observation ([sampler-retirement proof](/Users/sean/src/seon/docs/prds/source-cleanup/research/stage1-5-child-sampler-retirement-proof-2026-07-21.md:18)).

The open blocker issue agrees: focused transport tests exist, but acceptance still requires paging one real Bun and one real JVM value, retiring each owner, and observing honest unavailability ([retain-live-eval issue](/Users/sean/src/seon/docs/seon/issues/retain-live-eval-values-in-the-owning-jvm-host.md:74)).

No usable live MCP evidence was collected in this investigation; the attempted read-only probes were cancelled. Current default-cluster state is therefore **NOT GROUNDED**, but that does not alter the durable open status.

## Ruling 9 status

Ruling 9 has **not landed**. It schedules “drill” → get-in/path vocabulary at the Stage 1.5/W5 boundary ([program synthesis](/Users/sean/src/seon/docs/prds/sci-execution-runtime/program-synthesis-2026-07-21.md:73)), and the current public surface still contains names such as:

- `::drill-request` ([value.cljc](/Users/sean/src/seon/src/seon/render/value.cljc:152));
- `admitted-drill-request?` ([value.cljc](/Users/sean/src/seon/src/seon/render/value.cljc:1221));
- `drill-value` ([value.cljc](/Users/sean/src/seon/src/seon/render/value.cljc:1668)); and
- `effective-value-drill-limits` ([config.cljs](/Users/sean/src/seon/src/seon/config.cljs:1256)).

That rename is explicitly assigned to W5. Its non-completion is not evidence that the retirement mechanism is missing, and it need not precede the proof; the proof should use current names, then W5 performs the rename.

## 3. Exact remaining scope

### Required before the first W5 deletion

- Add or adapt a **test-only driver inside one complete isolated pod** so the web route and `seon.execution.host` share the same process-local retained values. The roadmap rejected a standalone process because it would observe a different value authority ([roadmap](/Users/sean/src/seon/docs/prds/source-cleanup/roadmap.md:522)).
- Give that driver test-only counters/latches for:

  - sample-frame observed;
  - raw-value touches;
  - sends and spawns;
  - owner lane/generation/process;
  - active/FIFO settlement; and
  - controlled retirement after sampling starts.

- Run the real Bun and JVM cases:

  - page zero and a later bounded page;
  - missing/cross-agent refusal with zero sends;
  - retire the exact owner while the sample is held;
  - require one unavailable/recompute settlement and zero replacement retry;
  - recompute under a new eval ID and confirm the old ID remains unavailable.

- Against the same frozen artifact, run the authorized HTTP route, real-browser disclosure/paging/unavailable transition, and server-side SSE capture required by the proof ([route/UI proof](/Users/sean/src/seon/docs/prds/source-cleanup/research/stage1-5-route-ui-live-proof-2026-07-21.md:322)).
- Run the focused owners and one full CLJS checkpoint, then record the evidence and close only the acceptance clauses actually proven.

### Owners and size

Expected production source change: **none**. The audit found no demonstrated source defect and says evidence collection—not production repair—is next ([graduation reconciliation](/Users/sean/src/seon/docs/prds/source-cleanup/research/stage1-5-graduation-matrix-reconciliation-2026-07-21.md:9)).

Harness ownership is:

- `test/seon/execution/` for the in-pod driver;
- likely `test/seon/execution_process_test.clj` or an equivalent process runner;
- `shadow-cljs.edn` only if a dedicated compiled entry is required.

The exact new filename, build entry, and LOC are **NOT GROUNDED**: the roadmap stopped while selecting the smallest normal-client entry/build and trigger seam ([roadmap](/Users/sean/src/seon/docs/prds/source-cleanup/roadmap.md:530)). Classification: **medium, test-only integration work**, not a production mechanism—roughly one bounded driver plus orchestration and evidence collection.

If the proof falsifies behavior, the conditional production owners are:

- [execution/host.cljs](/Users/sean/src/seon/src/seon/execution/host.cljs:906) — recorded-owner claim, FIFO, settlement, retirement;
- [execution.cljs](/Users/sean/src/seon/src/seon/execution.cljs:1071) — Bun-local sampling;
- [host/sample.clj](/Users/sean/src/seon/src/seon/host/sample.clj:49) — JVM session sampling/retention;
- [web/serve.cljs](/Users/sean/src/seon/src/seon/web/serve.cljs:425) — authorization/status translation; and
- [render/value.cljc](/Users/sean/src/seon/src/seon/render/value.cljc:1668) — bounded get-in/path producer.

Any repair size is **NOT GROUNDED** until the live falsifier identifies which contract fails.

## Recommendation

Treat Stage 1.5 as **W5 preflight unit W5-0**:

1. freeze the current artifact;
2. implement only the test driver needed to expose the race;
3. complete the real Bun/JVM retirement plus same-artifact route/browser/SSE proof;
4. record the result; then
5. start W5 deletion and ruling-9 renames.

It may ride the W5 series administratively, but it must finish **before the first child-lane deletion**. Running only a host-session equivalent after cutover would require an explicit owner rescope and would make the original child-retirement cell obsolete by decision, not satisfied by evidence.