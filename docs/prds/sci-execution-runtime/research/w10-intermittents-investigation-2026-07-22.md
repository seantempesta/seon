---
type: research
status: active
tags: [research, database]
---

# W10 intermittents investigation — sol read-only pass (2026-07-22 overnight)

Orchestrator-accepted. B8 splits: B8-A query response-before-release is a
DETERMINISTIC ordering defect (unit dispatched); B8-B physical-release needs
phase-localized diagnostics first. B11 open, medium-confidence macOS group-
reap hypothesis; fixture-isolation + capture unit is ready. Branch-qualified
eval-cljs hang is RESOLVED/archived — anchor WEAK reference reconciled.

# W10 reliability-debt investigation

## Executive ranking

| Rank | Item | Current status | Overnight recommendation |
|---:|---|---|---|
| 1 | B8 query response-before-release | Not reproduced, but source exposes a deterministic ordering defect | **Take this.** Small, unit-ready |
| 2 | B11 foreign-generation drain | Not reproduced; still open | Diagnostic/isolation work only |
| 3 | Branch-qualified `eval_cljs` hang | Obsolete/resolved; fresh HEAD probe not obtained | Skip runtime work; reconcile docs |

B8 actually contains two separate sightings. Only the query-admission half is implementation-ready; the physical-connection release half still needs phase-localized evidence. The ledger describes both separately at [source-cleanup/roadmap.md:60](/Users/sean/src/seon/docs/prds/source-cleanup/roadmap.md:60).

## 1. B8 intermittent

### Current status

**Not reproduced; remains OPEN.**

The ledger records one `writer-integration` release-path sighting and one `query-admission` injected-release sighting. Neither recurred in the original six-run loop; the TERM-before-shutdown-hook race found during that loop was a distinct defect and was closed separately. [source-cleanup/roadmap.md:61](/Users/sean/src/seon/docs/prds/source-cleanup/roadmap.md:61), [writer-terminal issue:52](/Users/sean/src/seon/docs/seon/issues/archive/writer-terminal-result-lost-when-term-precedes-shutdown-hook.md:52)

Cheapest reproduction was green in both namespace orders:

```text
$ bin/test-writer seon.db.writer-integration-test seon.db.writer-query-admission-test
Ran 20 tests containing 158 assertions.
0 failures, 0 errors.

$ bin/test-writer seon.db.writer-query-admission-test seon.db.writer-integration-test
Ran 20 tests containing 158 assertions.
0 failures, 0 errors.
```

Post-NS-4 and post-W1.5b full writer gates are also green: 342/2,584 and 353/2,652 respectively. [ns-4-resume-stderr.log:47281](/Users/sean/src/seon/tmp/orchestrator/ns-4-resume-stderr.log:47281), [w15b-resume2-stderr.log:22397](/Users/sean/src/seon/tmp/orchestrator/w15b-resume2-stderr.log:22397)

The exact historical failed assertions are **NOT GROUNDED**; the referenced “task chip” is not present in the checkout.

### B8-A: query-admission injected release

#### Root-cause hypothesis

**Strong hypothesis: terminal response is delivered before physical cleanup, making the test’s global fault injection race the worker tail.**

`complete-query-call!` delivers and removes the active request, then releases materialized database values in `finally`. [writer.clj:3058](/Users/sean/src/seon/src/seon/db/writer.clj:3058)

The test installs a process-global `with-redefs` for `d/release-materialized-db` but waits only for the response future. It can therefore unwind the redefinition while release is still pending. [writer_query_admission_test.clj:520](/Users/sean/src/seon/test/seon/db/writer_query_admission_test.clj:520)

This ordering also conflicts with `handle-request!`’s documented promise that completion runs “after physical completion.” [writer.clj:4092](/Users/sean/src/seon/src/seon/db/writer.clj:4092)

Datahike requires callers to ensure no read still uses a materialized database before release; Seon currently catches and logs release failures. [versioning.cljc:447](/Users/sean/src/seon/reference-code/datahike/src/datahike/versioning.cljc:447), [writer.clj:2076](/Users/sean/src/seon/src/seon/db/writer.clj:2076)

#### Cheapest falsifier

Use two latches around `release-materialized-db`:

1. Block release.
2. Assert the response has not yet been delivered.
3. Release the latch.
4. Assert success and zero retained query state.

That directly tests the physical-completion contract at [writer.clj:3058](/Users/sean/src/seon/src/seon/db/writer.clj:3058).

#### Fix scope and readiness

- Owner: `src/seon/db/writer.clj`, specifically `complete-query-call!`.
- Regression: `test/seon/db/writer_query_admission_test.clj`.
- Estimated size: 10–30 LOC.
- **Unit-ready:** yes.

The likely repair is cleanup-before-delivery while preserving catch-and-log behavior. Joined-owner cleanup must be checked against the separate `finish-query-job!` release path. [writer.clj:3018](/Users/sean/src/seon/src/seon/db/writer.clj:3018)

### B8-B: writer-integration physical release

#### Root-cause hypothesis

**Timing in asynchronous socket cleanup is plausible, but the exact cause is NOT GROUNDED.**

The likely affected test closes two physical channels, expects the route to survive the first close, then polls for disappearance after the second. [writer_integration_test.clj:135](/Users/sean/src/seon/test/seon/db/writer_integration_test.clj:135)

The production chain is asynchronous:

1. UDS schedules connection-owner cleanup. [uds.cljc:621](/Users/sean/src/seon/src/seon/db/transport/uds.cljc:621)
2. Writer cleanup cancels and awaits active requests, then releases acquisitions. [writer.clj:4025](/Users/sean/src/seon/src/seon/db/writer.clj:4025)
3. Registry release drains and releases Datahike before removing the route. [registry.clj:798](/Users/sean/src/seon/src/seon/db/registry.clj:798)

A five-second assertion can therefore fail under load without establishing which phase was late. No violated production invariant is visible from current source.

#### Cheapest falsifier

Add test-only barriers around UDS cleanup start and `release-database-acquisition!`, then loop the focused test. This distinguishes:

- cleanup not scheduled;
- active-request drain stalled;
- registry/Datahike release stalled;
- test polling timeout.

Do not merely enlarge the polling budget.

#### Fix scope and readiness

- Potential owners: `writer_integration_test.clj`, `uds.cljc`, `writer.clj`, `registry.clj`.
- Diagnostic change: roughly 20–50 test LOC.
- Production size: **NOT GROUNDED**.
- **Unit-ready:** diagnostic only.

### Did recent work plausibly fix B8?

- **W0:** no direct fix. W0’s relevant changes concern host containment, pooling, framing, and schema staging; the query response-before-release ordering still exists. [program-synthesis:543](/Users/sean/src/seon/docs/prds/sci-execution-runtime/program-synthesis-2026-07-21.md:543), [writer.clj:3058](/Users/sean/src/seon/src/seon/db/writer.clj:3058)
- **NS-4:** no direct fix; it split JVM host ownership. Its green full-writer gate adds non-reproduction evidence only. [program-synthesis:654](/Users/sean/src/seon/docs/prds/sci-execution-runtime/program-synthesis-2026-07-21.md:654)
- **W1.5b/protocol 13:** it plausibly changed physical-release timing by adding session-open admission, but it did not repair the cleanup chain or the query callback ordering. Protocol 13 is current at [protocol.cljc:107](/Users/sean/src/seon/src/seon/db/protocol.cljc:107); admitted test channels now open through [writer_test_support.clj:18](/Users/sean/src/seon/test/seon/db/writer_test_support.clj:18).

## 2. B11 intermittent

### Current status

**Not reproduced; remains OPEN.**

The ledger records one order-dependent `containment-uncertain` occurrence, followed by green isolated and full reruns with no later occurrence. [source-cleanup/roadmap.md:60](/Users/sean/src/seon/docs/prds/source-cleanup/roadmap.md:60)

Later W1.2a and W1.5b operator gates passed, but they were checkpoints rather than a recurrence campaign. [program-synthesis:729](/Users/sean/src/seon/docs/prds/sci-execution-runtime/program-synthesis-2026-07-21.md:729), [program-synthesis:782](/Users/sean/src/seon/docs/prds/sci-execution-runtime/program-synthesis-2026-07-21.md:782)

A faithful reproduction was not run because the test launches and drains a detached `sleep 300`, with hard-kill fallback—outside the requested no-lifecycle boundary. [process_test.clj:527](/Users/sean/src/seon/test/seon/dev/process_test.clj:527)

Read-only filesystem inspection found stale socket entries:

```text
tmp/seon-containment/a6499ee2-3aad-47.sock
tmp/seon-containment/d5686b9f-3995-4d.sock
tmp/seon-containment/fcd74dc2-1e18-4f.sock
```

`lsof` found no holder. Whether leaked owners or `sleep 300` workloads remain is **NOT GROUNDED**; process enumeration was sandbox-blocked.

### Owning path

The test creates unique process and log directories but does not set a unique containment socket directory. [process_test.clj:370](/Users/sean/src/seon/test/seon/dev/process_test.clj:370)

`spawn-detached!` consequently falls back to repository-global `tmp/seon-containment`; record and terminal files remain fixture-local. [process.clj:957](/Users/sean/src/seon/script/seon/dev/process.clj:957)

For a mismatched generation, `contained-one-shot!` drains the retained process and deliberately returns `foreign-one-shot`. [process.clj:2036](/Users/sean/src/seon/script/seon/dev/process.clj:2036), [process.clj:1942](/Users/sean/src/seon/script/seon/dev/process.clj:1942)

The Python owner waits for escalation, reaps its direct anchor, requires the process group to disappear, and only then publishes terminal JSON. [detach.py:321](/Users/sean/src/seon/script/seon/dev/detach.py:321)

### Root-cause hypothesis

**Primary hypothesis, medium confidence:** macOS process-group reaping occasionally outlives the five-second absence proof.

The anchor kills its entire group, including itself, with `SIGKILL`. The owner reaps only its direct anchor and then waits for group absence before publishing terminal evidence. [detach.py:242](/Users/sean/src/seon/script/seon/dev/detach.py:242), [detach.py:376](/Users/sean/src/seon/script/seon/dev/detach.py:376)

If a reparented or zombie workload remains observable, terminal JSON is never published. Clojure then correctly reports owner disappearance without terminal evidence as `containment-uncertain`. [process.clj:1345](/Users/sean/src/seon/script/seon/dev/process.clj:1345)

The causal OS step is **NOT GROUNDED** because the original failure transcript is unavailable.

**Secondary hypothesis:** fixture contamination through the shared containment socket directory. Stale entries establish incomplete cleanup, but UUID-derived names make direct collision unlikely; causal interference is **NOT GROUNDED**. [process.clj:946](/Users/sean/src/seon/script/seon/dev/process.clj:946)

### Cheapest falsifier

Loop the single test in a disposable operator-test environment while retaining first-failure evidence:

- foreign process record;
- owner log;
- result/terminal files;
- PID/PPID/PGID/state;
- `kill -0 -- -PGID`;
- elapsed time inside `wait_group_absent`.

A/B that loop with an explicitly unique containment socket directory. Shared-only failures support fixture contamination; failures in both arms with surviving group evidence support the reaping hypothesis.

### Fix scope and readiness

- Fixture isolation and diagnostics: `test/seon/dev/process_test.clj`, under roughly 30 LOC; **unit-ready**.
- Confirmed reaping repair: `script/seon/dev/detach.py` plus focused process tests, approximately 50–150 LOC.
- Correctness fix: **not unit-ready** without captured evidence or an explicit OS-semantics decision.

### Did recent work plausibly fix B11?

No direct fix:

- W0 concerns host-eval containment rather than detached operator processes. [program-synthesis:543](/Users/sean/src/seon/docs/prds/sci-execution-runtime/program-synthesis-2026-07-21.md:543)
- NS-4 reorganized JVM host namespaces. [program-synthesis:654](/Users/sean/src/seon/docs/prds/sci-execution-runtime/program-synthesis-2026-07-21.md:654)
- W1.5b changed database session admission. Its adjacent operator change affects writer readiness, not restore-admin containment. [process.clj:648](/Users/sean/src/seon/script/seon/dev/process.clj:648)

## 3. Branch-qualified `eval_cljs` hang

### Current status

**Obsolete/resolved in the durable ledger. Fresh live confirmation at current HEAD is NOT GROUNDED.**

The W10 anchor still lists the hang as WEAK, but the later source-cleanup graduation section explicitly records it resolved on current source. [program-synthesis:258](/Users/sean/src/seon/docs/prds/sci-execution-runtime/program-synthesis-2026-07-21.md:258), [source-cleanup/roadmap.md:1082](/Users/sean/src/seon/docs/prds/source-cleanup/roadmap.md:1082)

The issue is archived with `status: resolved`. The original failure reproduced on two branches at 60/90 seconds; later, two simultaneous retained branches returned their own database values in about 0.5 seconds. [branch issue:1](/Users/sean/src/seon/docs/seon/issues/archive/branch-qualified-eval-cljs-database-read-stays-pending.md:1), [branch issue:23](/Users/sean/src/seon/docs/seon/issues/archive/branch-qualified-eval-cljs-database-read-stays-pending.md:23), [branch issue:65](/Users/sean/src/seon/docs/seon/issues/archive/branch-qualified-eval-cljs-database-read-stays-pending.md:65)

No production MCP/database diff exists between the failing frozen source and the resolving proof; commit `1076b639` added regression and evidence. Therefore the exact historical recovery cause is **NOT GROUNDED**.

The attempted read-only live probe was cancelled:

```text
mcp__seon__runtime_status({})
=> {"content":[{"type":"text","text":"user cancelled MCP tool call"}],
    "isError":true}
```

### Owning path

1. The pod advertises launch cluster plus database-derived agent IDs. [client.cljs:314](/Users/sean/src/seon/src/seon/client.cljs:314)
2. MCP probes every concrete Shadow runtime. [mcp.clj:380](/Users/sean/src/seon/script/seon/dev/mcp.clj:380)
3. The shared resolver matches cluster qualifier and agent ID. [runtime_id.cljc:69](/Users/sean/src/seon/src/seon/dev/runtime_id.cljc:69)
4. MCP pins watcher port, build, and Shadow client ID in one session. [mcp.clj:631](/Users/sean/src/seon/script/seon/dev/mcp.clj:631)
5. Async wrapper, polling, and fetch remain on that captured session. [mcp.clj:998](/Users/sean/src/seon/script/seon/dev/mcp.clj:998)
6. Regression coverage asserts all five bridge calls use the same session. [mcp_test.clj:138](/Users/sean/src/seon/test/seon/dev/mcp_test.clj:138)

`seon.db.branch` is not the evaluation router; branch identity enters through the launch/database-session descriptor, while MCP routes by runtime advertisement. [launch.cljc:389](/Users/sean/src/seon/src/seon/launch.cljc:389)

### Root-cause hypothesis

Best hypothesis: transient Shadow-session or Promise-settlement observation, not branch selection or durable database failure.

`seon.db/db` either returns cached state or issues a bounded `resolve-head`; UDS deadlines reject and cancel. [db.cljs:723](/Users/sean/src/seon/src/seon/db.cljs:723), [uds.cljs:455](/Users/sean/src/seon/src/seon/db/transport/uds.cljs:455)

The reported 60/90-second failure was specifically the MCP bridge’s “async value still pending” state. [mcp.clj:1033](/Users/sean/src/seon/script/seon/dev/mcp.clj:1033)

Exact cause: **NOT GROUNDED**.

### Cheapest falsifier

Against an already-running retained branch:

```text
eval_cljs
agent_id: "<branch-cluster>/root"
session_id: fresh
timeout_ms: 3000–5000
code: "(await (seon.db/db))"
```

If it fails, isolate on the same session with:

1. `(seon.db/attached?)`
2. `(js/Promise.resolve :ok)`
3. `(seon.db/db)`
4. manual `globalThis` settlement and polling.

### Fix scope and readiness

- Runtime fix: none currently indicated.
- Immediate scope: remove stale W10/WEAK references; documentation-only, XS.
- Reliability work: not unit-ready without a fresh reproduction.
- If it recurs: begin with `script/seon/dev/mcp.clj` and `test/seon/dev/mcp_test.clj`; widen to `db.cljs`/`uds.cljs` only if the database Promise itself violates its deadline.

## Overnight recommendation

Take **B8-A, query response-before-release ordering**. It has the highest value-per-effort: a narrow owner, a deterministic falsifier, an observable contract mismatch, and an estimated 10–30 LOC fix. [writer.clj:3058](/Users/sean/src/seon/src/seon/db/writer.clj:3058), [writer.clj:4092](/Users/sean/src/seon/src/seon/db/writer.clj:4092)

Then:

1. B11 fixture isolation plus failure capture.
2. B8 physical-release phase localization.
3. Branch-hang documentation reconciliation only.