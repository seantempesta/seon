---
type: landing
status: landed — install pending RESET NEEDED
lane: fix-fault-visibility
created: 2026-09-24
report: docs/research/agent-platform/fail-loud-dev-resilient-prod-2026-09-24.md
---

# Lane fix-fault-visibility (2026-09-24)

Owner requirement: "Crashing the entire system just because a rendering error occurs is
not good for dev work. How can we make sure you are notified when errors occur without
making you look manually?"

## Broken or unproven, first

- **RESET NEEDED to install.** `default` (pid 90471) runs the committed archive
  `ce7384682`, which is 127 commits behind HEAD, and hook publication is off. None of the
  JVM-side changes (`cluster.clj`, `boot.clj`, `problems.clj`) are loaded there. `runtime_status`
  still reports `health observed` and 16 counted signatures. Only the MCP bridge
  (`script/seon/dev/mcp.clj`) is live: it auto-reloaded with no reload error. Until
  `start --head` it attaches nothing, because the old JVM's observation has no
  `:seon.dev.mcp/open-faults`.
- **No test was executed.** `bin/test-check` runs the JVM's loaded program (the archive), so
  the new regression would be measured against old code. In a `git archive` snapshot,
  `with-database` refuses outside a `seon.test/run` member, and booting a cluster costs more
  than the ten-second law allows. Root must run these after `start --head`:
  - `bin/test-check default --test seon.problems-test/a-resolved-fault-stays-closed-until-it-recurs`
  - `--ns seon.cluster-test`
  - `--ns seon.cluster.publication-delta-test`
  - `--ns seon.cluster.mcp-test`
- **`mcp_test/runtime-observation-counts-problems-without-embedding-facts` may go red.** It
  uses a fake `::connection`. The new `open-faults` call does `db/db` on that fake, which
  throws when armed (probed: `seon.db/db refused connection ... got a keyword`). The existing
  `replaced-roots` call already did the same, so this is not a new class of failure. The test
  should get a real branch connection.
- **Stored faults stay open until someone resolves them.** The 4 `seon.cluster/refused!`
  signatures (`7cda…`, `2c17…` and the two others) are historical misclassifications. After
  the restart, call `(seon.problems/resolve! conn sig)` for each. They will not re-open,
  because refusals no longer record.

## What landed (4 commits, +107/−16 including tests)

| commit | change | lines |
|---|---|---|
| `43addd46c` | `cluster/refused!` carries `:seon.cluster.boot/disposition :refused`, the one name `request!` reads (`boot.clj:662`). `request!` now returns it with disposition `:refused` instead of `:operation-failed`. Three test assertions moved to the new name. | +6/−6 |
| `5d1c0f592` | `seon.problems/resolve!`: the one writer of `:seon.error/resolved-tx`, contracted. `seon.problems/open-faults db entries`: keeps the signatures with no resolution after their latest occurrence (`last-at` compared with the resolution tx's `:db/txInstant`). Regression `a-resolved-fault-stays-closed-until-it-recurs`: open → resolve closes it → history kept → a later occurrence re-opens it → an unknown signature is refused. Namespace docstring corrected. | +57/−6 |
| `de13aa02c` | `mcp-runtime-observation` lists `:seon.dev.mcp/open-faults` (signature, operation, occurrences, last-at, message, exception class, first frame). Health is `:faulted` while any fault is open, `:observed` only when none are, and `:unknown` when problems cannot be read. It is never derived from the connection. | +16/−1 |
| `e868e5ec5` | The bridge keeps each discovery's open faults. Every `tools/call` result gets a `{:seon.dev.mcp/new-faults [...]}` content item holding the open faults newer than this MCP session's per-cluster cursor. The item is absent when there are none. A `:faulted` cluster stays `:alive` in discovery. | +28/−3 |

## Evidence

- **Before (live JVM, `mcp-runtime-observation "default"`):** 69.8 ms, `:observed`,
  `{:error-signatures 16 :errored-receipts 34 :failed-runs 1 :failed-tests-unknown 1}`.
- **The added derivation, run in scratch namespace `scratch.fault-visibility` on default's db:**
  - resolution query 1.0 ms;
  - filter and face 0.9 ms;
  - 16 open faces, 10,090 printed bytes.
  - The existing `problems` derivation is 39.5 ms. The added cost is ≈2 ms, far under the 20% / 50 ms bar.
- **Branch `fix-fault-visibility`, SCI mode:**
  - `[:db/add [:seon.error/signature "2c17…"] :seon.error/resolved-tx "datomic.tx"]` committed tx `536872675` with `:db/txInstant 2026-09-24T14:55:04.293Z`.
  - The open-fault filter then answered 16 entries and 15 open, with `2c17…` absent.
  - That transaction took 1,568 ms. 844 ms of it was `seon.call-preparation/derive-snapshot`, which is a foreign cold cost, not this lane's code.
  - The branch was retired.
- **Bridge probe** (`tmp/fix-fault-visibility/bridge-probe.clj`, bb, tooling classpath):
  - first call reports `[a b]`; second call reports `[]`;
  - after `b` recurs, the third call reports `[b]`;
  - the JSON encodes symbols, instants and frames.
- **Snapshot:** `git archive HEAD` (`e868e5ec5`) with `reference-code` and `.cpcache` linked, then `clojure -M:dev:test proof.clj`.
  - Loaded `seon.cluster`, `seon.problems`, `seon.cluster.boot`, `seon.problems-test` and `seon.cluster-test` in 26,543 ms (cold snapshot JVM).
  - `open-faults` and `resolve!` resolve.
  - `(reload-order '#{a} '{a #{a}})` refuses with `#:seon.cluster.boot{:disposition :refused}`.
  - The snapshot was removed.
- **Expected after `start --head`:**
  - health `:faulted`, 16 open faults listed;
  - after the 4 refusal signatures are resolved, 12 open;
  - a new publication refusal returns as `:refused` and records no fault.

## Not done / out of scope

- **MCP JVM probe noise (report rows 10-14).** `record-uncaught!` (`boot.clj:340-374`) files
  a throwable from a thread with no carried environment into the sole live instance. An MCP
  JVM evaluation's own throw returns to the caller already. Rows 10-14 were threads *spawned*
  by probes (`async-mixed-*`), which carry no evaluation identity. So this is not a one-line
  classification fix. It needs the prepl evaluation to bind a carried environment, or a
  thread-group marker that the handler reads. Owner: `boot.clj` plus the operator prepl.
- **`bin/seon faults --wait`.** Not done, because it does not fit trivially in `mcp.clj`. The
  per-response `new-faults` is the no-look route.
- **Stale key `:seon.boot/refused`, still in the schema and one test fixture.**
  - `resources/seon/schemas/seon.boot.edn:155-156` (`:refused`, `:refused-error`) no longer has
    a producer. Delete it with its resource owner.
  - `test/seon/adoption_diagnostic_test.clj:39` builds that old key by hand to simulate a
    cluster refusal. Update it to `:seon.cluster.boot/disposition :refused`.
- **The render still shows "Resolved" for any resolution.** `error/render-html`
  (`error.clj:1726`) prints "Resolved" whenever `:seon.error/resolved-tx` is present, even
  after a re-occurrence. It should derive open from `seon.problems/open-faults`. Owner:
  `error.clj`.
- **The problems family still lists every signature.** `:seon.problems/error-signatures`
  keeps resolved ones, because narrowing it would break
  `error_test.clj:154`, which is held by fix-faults-read. `problem-counts` therefore still
  counts history. The open list and health are the current-state surface.
- **First-ever delivery and stderr dedup (report R4, `error.clj:1393`, `fault.clj:32-39,124`).**
  Untouched. Those files are not owned here.

## Proposed next slice: `:panic` stops only the failing graph; render errors render a diagnostic

1. The committer's `:seon.flow/panic!` callback (`cluster.clj:3523-3529`) now only prints,
   through `emit-core-fault!` (`cluster.clj:3313`). Under `:panic` it should also stop the
   graph named by the fault's `:seon.agent/id`. Use the existing agent stop and join path
   (`cluster/agent.clj:1075-1087`), not a new stopper. A plumbing fault should instead set
   readiness to panicked and leave the REPL and the page up. Re-arming is an explicit armer
   wake after `resolve!`.
2. A render failure in the web render proc (`render/web.clj:640,1830`, pid
   `:seon.render.web/render` at `:2341`) should render the failed rendering's diagnostic
   in place, as the law "failed rendering gives a diagnostic" requires. It should be recorded
   once as a fault, never stop the graph. The existing `seon.render` invocation diagnostic is
   at `render.clj:934`.
3. `flow.clj:1135-1140` calls `panic!` only on the first signature per JVM. Keep that, so
   stopping happens once per signature per process.

## Released paths

- `src/seon/cluster.clj`
- `src/seon/problems.clj`
- `script/seon/dev/mcp.clj`
- `src/seon/cluster/boot.clj`
- `test/seon/cluster_test.clj`
- `test/seon/cluster/publication_delta_test.clj`
- `test/seon/problems_test.clj`
- this note
