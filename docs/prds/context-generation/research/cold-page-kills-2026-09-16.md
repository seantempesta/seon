---
type: research
status: active
tags: [render, performance, database]
---

# Cold page: preserve unchanged acquisitions

Lane `cold-page-kills`, 2026-09-16 UTC, on `steward-platform`.
Read end to end: AGENTS.md, the issues README, the
[approved cold-page plan](cold-page-plan-2026-09-15.md), the
[cold-page issue](../../../seon/issues/the-first-debug-page-after-an-adoption-takes-eighteen-seconds.md),
its [debug-page-cost plan and landing](debug-page-cost-plan-2026-09-15.md),
and [page-speed landing](page-speed-and-estimate-landing-2026-09-15.md).
Read the issue-mining class table: N9's structural kill is dependency-closure
invalidation. This assignment is the specific cold-page residual, not closure
of the other N9 members. Applied data-oriented Clojure, Datahike, REPL,
clojure-testing and Datastar skills.

## Slice 1: read-only returns preserve retained work

Guarantee: a declared read-only MCP request, including its discovery
observations, emits no runtime-evaluation invalidation; unspecified or
mutating evaluations retain conservative notification.

The existing PREPL projection marker now carries explicit request data.
`mcp-valf` consumes that intent once. Its existing arities derive the current
cohosted destinations; an additional arity accepts those channels explicitly,
letting the canonical fixture own its notification destination without adding
a global fake cluster. The render consumer's behavior is unchanged.

Three discovery returns were relevant: operator process-census responsiveness,
the JVM snapshot, and cluster-layer observation. A live return trace found the
unmarked census keyword after the latter two had been fixed. All now declare
read-only intent explicitly and keep their raw observation return shape.
Runtime-health and artifact-read requests also declare their observation intent.
The `eval_clj` tool accepts `read_only: true`; no form-text inference exists.

Dependency ledger: Clojure PREPL calls its return consumer synchronously after
evaluation (`reference-code/clojure/src/clj/clojure/core/server.clj:228`).
`src/seon/cluster.clj` owns the thread-local marker and return projection;
`script/seon/dev/mcp.clj` owns request construction; the existing operator
observation owners are `script/seon/fresh_operator.clj` and
`resources/seon/operator/state.clj`. `src/seon/render/web.clj` owns retained
calls and runtime-evaluation consumption. No new cache, process, channel, or
notification mechanism was introduced.

The hook lane's two protected files were dirty at entry. After its commit
`1c0f61fea`, `git status` showed `fresh_operator.clj` clean; only then did this
lane change its JVM snapshot form. Later reach-digest changes were preserved.

### In-process verification

Candidate function forms were evaluated in the development JVM before source
edits. Each result below was produced by `seon.test/run` with explicit
`(seon.operator/connection "default")`; no Seon test JVM was launched.

| UTC / result run | Test | Pass / fail / error | Boundary |
|---|---|---|---|
| 00:27:08 / 67591 | read-only-mcp-return-preserves-root-page | 10 / 1 / 0 | Initial counter measured retained SCI calls rather than root acquisition. |
| 00:27:46 / 67593 | same | 12 / 0 / 0 | Candidate return seam. |
| 00:28:32 / 67595 | read-only-intent-crosses-evaluation-and-discovery-forms | 6 / 0 / 0 | Candidate bridge/discovery forms. |
| 00:30:01 / 67597 | page | 12 / 0 / 0 | File-loaded test; adoption not yet established. |
| 00:32:12 / 67757 | page | 10 / 2 / 0 | Old canonical fixture contracts and global notification interference exposed. |
| 00:34:20 / 67761 | page | 1 / 0 / 1 | Complete error named the fixture's old boolean-only marker contract. |
| 00:35:07 / 67763 | page | 12 / 0 / 0 | Candidate with explicit fixture notification channels. |
| 00:36:47 / 67914 | page | 12 / 0 / 0 | Fresh canonical population from current source. |
| 00:40:07 / 68066 | bridge/discovery forms | 6 / 0 / 0 | Checked-in forms. |
| 00:43:16 / 68070 | census-return-declares-read-only-intent | 2 / 0 / 0 | Candidate census, real loopback PREPL. |
| 00:46:58 / 68082 | page | 12 / 0 / 0 | Fresh canonical population; both changed host functions verified armed. |
| 00:48:14 / 68083 | census | 2 / 0 / 0 | Checked-in census behavior, real loopback PREPL. |

Page tests have namespace `seon.render.web-context-test`; bridge and census
tests have namespace `seon.dev.mcp-bridge-test`. The
[fresh-fixture probe](cold_page_fixture_probe_2026_09_16.clj) records the exact
scope, canonical population, instrumentation observation and cleanup.
The page test requires nonempty real SCI work, unchanged retained-call count,
identical page bytes, zero root acquisitions, and an actual asynchronous
invalidation after an unspecified return. The census test observes the intent
at the actual PREPL return. The orchestrator gate request includes the existing
runtime-repaint regressions as well.

### Default observations

PID **69622** remained alive; no stop, restart or refork. The
[bounded measurement script](cold_page_probe_2026_09_16.py) uses the actual
`bin/mcp-server` request path and `curl -w '%{time_total}'`. A freshly started
bridge is required to exercise the new tool argument; an already running
bridge retains its old loaded request constructors.

After the full discovery fix, retained calls were **11 → 11**, basis
**536872585**, adopted source **6aa9e594-cf98-58c8-9ac4-fba18975c3d8**.
Initial root GETs: **0.614961, 0.121628, 0.127359 s**, HTTP 200,
**44,605 bytes**, identical SHA-256
`f436b33b7bbcbdf4f7864b6f9654b2ac2d4749ca7da6d4f450cdb2af7e1077db`.
After **120.010699 s without a lane GET**, cold was **0.667470 s**;
immediate warm GETs were **0.128055, 0.141022, 0.139521 s**.
All were HTTP 200, 44,605 bytes, SHA-256
`8c8889ccc469c84a791414e0d93b80ae21e80fc0fe50fc1329303f4eb4b6db83`.
The ratio to following warm median is **4.784×**, not a passing 2× result.
Source advanced to **6aa9e66f-89d6-5324-b9dd-24427d088b48**, basis
**536872588**: this is not unchanged-source idle proof. Retained calls after
the final observations remained **11**.

Earlier incomplete-discovery samples were **0.917999 s cold / 0.114764 s
warm median**, and **0.714509 / 0.131951 s**, both after 120 seconds.
Their zero retained-call observations were failures, not preservation evidence.

Publication initially reported `root-creator-mismatch`, the existing
[operator custody issue](../../../seon/issues/operator-status-refuses-foreign-live-root.md).
The existing `refresh-source!` call timed out at 60 seconds; later source reads
verified the new marker in adopted program facts. Hook adoption subsequently
advanced normally. The final armed test and external request observations are
after that verification. Neither timeout nor a queued hook was counted as
adoption success.

## Remaining work and gate boundary

Slice 2 still owns overly broad history read evidence and the two-minute debug
page regression. The cold-page issue remains open. No dependency file has yet
been changed, and no dependency test result is claimed in this slice.

Gate request: `tmp/orchestrator/gate-requests/cold-page-kills.txt`.
The orchestrator must run the listed namespaces with the owned paths and then
the platform gate. This lane deliberately ran no `bin/test`, `bin/test-fast`,
or other Seon test JVM. `git diff --check` passed; hook lint reports no new
syntax, namespace, or arity errors. Existing unrelated style warnings remain.
