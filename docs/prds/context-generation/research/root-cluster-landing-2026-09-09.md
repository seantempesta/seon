---
type: research
status: complete
tags: [agent, render, cluster]
---

# Root cluster block — 2026-09-09

Bounded assignment: chart PRD §12 and roadmap row 13. Read the chart and
roadmap end to end, AGENTS.md (whose opening copies turn PRD §10), the
binding §10 and §§13–15, and `git show 778be4b94 -- src`.

## Dependency ledger and design evidence

- `src/seon/render/walk.clj`, `declared-acquisition`: matching schema
  declarations order concerns. The root-only matching schema declares the
  cluster concern; the walker admits derived concerns through its existing
  producer path. No turn-loop list or stored metrics.
- `resources/seon/operator/state.clj:1209`, `footprint`: same no-follow
  filesystem derivation used by `script/seon/fresh_operator.clj:2596`.
  The cluster observation measures the physical store directory.
- `src/seon/cluster/registry.clj:127`: Konserve branch heads contain
  `[:meta :datahike/commit-id]`. The connection read reuses `head-record`;
  no store or branch is opened. Dependency:
  `reference-code/datahike/src/datahike/writing.cljc` (branch head write),
  `reference-code/datahike/src/datahike/connector.cljc` (connection database).
- `reference-code/datahike/src/datahike/api/specification.cljc`, query/pull:
  all accounting runs through `seon.db` against one supplied database.
  Runtime owns turns; evaluations reference their turn. UTF-8 bytes are
  measured, not character counts. Distinct blob digests count once per agent.
- JDK 26.0.1 source, `java.base/jdk/internal/vm/ThreadDumper.java:319`:
  JSON has explicit `virtual: true`; parked threads are included. The
  HotSpot diagnostic bean writes a disposable JSON observation, deleted in
  `finally`. Counts are observations during a changing JVM, not atomic.
  Disabled virtual-thread tracking returns unavailable. The scheduler
  MXBean probe showed one mounted and zero queued threads on default;
  those values would omit parked threads and cannot answer the request.
- `src/seon/turn.clj:3689`: attempts retain provider usage EDN. Reported
  tokens and USD cost are summed; missing usage/cost is unknown, not zero.
  No attempts means zero. Session means since this JVM's boot instant.

## Verification

Inherited default: PID 23557, Java 26.0.1, URL port 7994. MCP status and
JVM evaluation answered. Foreign edits in `src/seon/env.clj` belong to
schema-redeclare and are excluded from this lane's snapshots. Default has
not been stopped, reforked, or restarted.

Owned paths: `src/seon/cluster/status.clj`, `src/seon/cluster/registry.clj`,
`src/seon/cluster/agent.clj`, `src/seon/render/walk.clj`,
`resources/seon/schemas/seon.cluster.status.edn`,
`test/seon/cluster/status_test.clj`, and this note.

The scoped gate also exposed stale walk fixtures: their removed
`:seon.cluster.eval/result-edn` attribute refused the setup transaction,
and the test continued against an absent entity. Updated
`test/seon/render/walk_test.clj` to assert setup, use `:seon.eval/shown`,
and inspect the turn's evaluation connections. Its presentation-width
assertion now enforces the rule that presentation clipping is outside the
walk. The missing-root walk now reaches its existing typed no-such-entity
result rather than calling `render/transacted` on nil.

### Prompt and page evidence

The [capture script](scripts/root-cluster-capture-2026-09-09.clj) ran through
MCP JVM evaluation on the disposable `root-cluster` instance. It compacts
root, writes the ordinary system turn, and calls the same
`seon.cluster.prompt/prompt` owner as the provider path, with the carried
projection and profile. No provider request was sent. The first manual
capture omitted the projection around `system-turn`; its typed
missing-projection refusal was corrected in the script.

Read the [entire exact prompt](root-cluster-prompt-2026-09-09.txt): **7,859
UTF-8 bytes, 11 evaluations**. Root's initial boot opening also contained
the new form, before any compaction: cluster 369 ms, agent accounting
226 ms. The final reseeded cluster evaluation took **157 ms** and showed:

```clojure
{:seon.cluster.status/adopted-commit #uuid "6aa22426-3ff8-575a-a021-61e46e443537"
 :seon.cluster.status/current-commit #uuid "6aa22426-3ff8-575a-a021-61e46e443537"
 :seon.cluster.status/agents 1
 :seon.cluster.status/open-turns 0
 :seon.cluster.status/faults []
 :seon.cluster.status/heap-used 1066484680
 :seon.cluster.status/heap-max 17179869184
 :seon.cluster.status/platform-threads 44
 :seon.cluster.status/virtual-threads 14
 :seon.cluster.status/store-bytes 161411894
 :seon.cluster/name "root-cluster"}
```

The scratch namespace page served one `seon-root-agents` table and one
`seon-cluster-status` section. Its table showed root, 11 evaluations /
1,031 ms, 0 tokens / USD 0, and 4,550 storage bytes. These later numbers
differ from the opening's zero accounting because the opening itself is
now stored. A fresh fork has no adoption fact; it truthfully reports that
absence until development adoption records one. Scratch was restarted
after its adoption during capture diagnosis; default was never restarted.

The requested default debug URL served the cluster form and both metrics
forms. Its preview measured 56,431,805,252 store bytes in 23,988 ms, with
50 platform / 14 virtual threads, two agents and no open turns. Root's
attempts reported 3,145 tokens but no USD cost, shown as unavailable.
The shared-store scan cost is recorded in the existing
[store-growth issue](../../../seon/issues/store-grew-to-69-gigabytes-in-one-day-of-lanes.md).
The [empty plan result](../../../seon/issues/root-empty-plan-read-shows-nil.md)
was also filed.

Browser paint is unverified: CUA found no browser; Safari returned
`cgWindowNotFound`. Updated the existing
[browser issue](../../../seon/issues/browser-ui-observation-has-no-accessible-window.md).
Served HTML is not a screenshot or a paint claim.

### Gates and first slice

Final scoped gate: **6 tests, 44 assertions, zero failures/errors**,
`SEON_TEST_WORKERS=3 bin/test --paths` the seven source/schema/test paths
listed above (including `test/seon/render/walk_test.clj`), followed by
`-- seon.cluster.status-test seon.render.walk-test`. The final thread
regression uses the canonical event backstop for readiness, release and
completion. It verifies a parked virtual thread with the JVM's real dump.
The accounting regression verifies non-ASCII shown bytes, a real blob
referenced twice, known and missing provider cost, and prior-session
evaluation exclusion. Final run `run.IRSQwA` exited zero and removed itself.

Platform gate: **84 tests, 505 assertions, zero failures/errors**, same
owned production paths, `SEON_TEST_WORKERS=3 bin/test --paths … --platform`.
No `--all` or `--full` was run. Its `run.nTiIyX` exited zero and removed
itself. Failed earlier roots were removed only after their runners ended
and the failures were reproduced and corrected.

At this first gated checkpoint, default adoption is still pending behind
the protected schema lane's operator lifecycle lock. The explicit
`bin/seon init --dev default` is waiting; a live query at 03:39 UTC read
adopted `6aa22005-3cf7-52c7-a38b-511c0e8a17f5` versus current
`6aa22655-707e-5dbb-8a34-91ec66ae094b`. Thus default's successful form
preview proves loaded behavior, **not completed in-place adoption**.
`logs/current-source-failure.log` reports “Publication did not finish
within its declared bound.” No foreign file or process was changed to
repair this boundary. The scratch root has been downed and deleted.

### Final default adoption and observation

Implementation commit: `501b45570`. The queued adoption subsequently
completed normally: “development cluster converged.” A live MCP query
verified **adopted = current = `6aa226e2-731d-55ef-9574-b1f1bf142fba`**,
with the original **PID 23557** still running. The foreign lock was never
operated or bypassed.

After convergence, fetched and read
`http://127.0.0.1:7994/ns/my.agents.root/debug?prompt=true` again (86,229
served HTML bytes). Its cluster preview showed the matching commits,
heap 3,476,016,688 / 17,179,869,184 bytes, 50 platform / 18 virtual
threads, 56,615,938,382 store bytes, two agents, no open turns and no
fault signatures. Evaluation time: 19,659 ms. The agent block showed:

| Agent | Last closed turn ms | Session evaluations / ms | Provider tokens / USD | Retained storage bytes |
|---|---:|---:|---|---:|
| juniper | 152 | 11 / 621 | 0 / 0 | 5,842 |
| root | 4,719 | 17 / 1,756 | 3,145 / unavailable (provider omitted cost) | 10,961 |

The page had no missing-projection diagnostic. This establishes successful
in-place development adoption and served debug content; the separately
recorded browser-paint limitation remains. All owned command shells ended;
the disposable root and failed owned test roots were deleted. Unrelated
working-tree edits and untracked files were preserved.
