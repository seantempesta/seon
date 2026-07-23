---
type: research
status: complete
tags: [research, agent, runtime, capability]
---

# E2E capability-seam drive — 2026-07-22

## Outcome

The final-evidence 500 is fixed and live-proven, but the requested capability
drive could not graduate. A newly landed package-corpus acquisition clause
names `:seon.packages/package` before that attribute is installed on a cluster
with no package rows. Every post-restart agent turn therefore fails during
authored-program acquisition, before it can evaluate database, filesystem,
shell, or web forms. This is the important E2E result: the seam's first live
consumer is currently blocked by an integration defect outside the four
capability families.

## Setup and source state

- Cluster: `e2e-seam-20260722`; isolated cluster, proc, socket, port-file, and
  log coordinates. The default cluster was never used.
- Operator sequence: `up` → `cluster reset e2e-seam-20260722` → drive →
  restart/down+up proof → clean `down`.
- Provider: configured default DeepSeek, model `deepseek-v4-pro`; no provider
  override.
- Scenario: a municipal heat-resilience decision log for selecting and
  retrofitting neighborhood cooling centers before summer 2027. The prompts
  require a restart-surviving `my.plan`, schema'd candidate facts, a set-valued
  services attribute, an EDN assessment slot, and deliberate error/success
  calls across db/fs/shell/web.
- Prompt evidence:
  `tmp/orchestrator/e2e-drive-evidence/turn-01-prompt.txt` and
  `tmp/orchestrator/e2e-drive-evidence/turn-02-prompt.txt`.

## Blocker fix

The issue text described an ancestor render-cap pull, but the same missing
identity remained in the refactored historical and final model-transport
selectors. Both selectors now include `:seon.config/id`, and the known
lookup-ref identity is restored before singleton validation
(`src/seon/web/serve.cljs:1140-1171,1410-1487`). The owning regression asserts
both exact selectors (`test/seon/web/serve_test.cljs:1242-1247`).

Before the complete fix, `/agents/run` returned the Malli 500 captured in
`turn-01-http.txt`, `turn-02-http.txt`, and `turn-02-retry-http.txt`. After the
fix, the same real-agent door returned HTTP 200 with ordinary evidence in
`turn-02-retry2-http.txt`: agent `lucky-results-push`, one error turn, zero
evals, `elapsed_ms` 14636, `model_transport_evidence.status` `absent`, and an
ordinary DeepSeek model projection. That closes the web blocker without
claiming the agent work succeeded.

## Drive transcript evidence

1. The first DeepSeek turn opened as `ock1374kkqdn`. It began authoring the
   heat-resilience namespace and candidate facts, but acquisition failed while
   teeing/reloading the authored program. The log also retained a parser EOF
   against a clipped candidate map; no final eval evidence was returned.
2. The cluster was fully stopped and started again with the same database and
   agent id. This proves the continuation addressed durable cluster state, not
   an in-process stash.
3. Three continuation attempts opened turns `c9d0e9d0e7vl`, `erlonvasuoxl`,
   and `i02s0d2jhsev`. All failed at the identical uninstalled package
   provenance attribute before any eval. One DeepSeek call also reported a
   transient connection error and retried before the same acquisition failure.

Pointers: `tmp/orchestrator/e2e-drive-evidence/turn-timing-lines.txt`, the four
HTTP evidence files in that directory, and pod logs
`ede1858d-e1e9-4479-8fe4-b70229527743.log` plus
`36305bfe-d296-41ae-ada2-a4c70d707ecf.log` under the isolated log root.

## Performance

| Turn | End-to-end HTTP | Turn wall from pod log | Reply tokens | End-to-end tok/s | Eval/seam timing |
|---|---:|---:|---:|---:|---:|
| `ock1374kkqdn` | 59.892 s | 52.156 s | unavailable | unavailable | unavailable after capture failure |
| `c9d0e9d0e7vl` | 13.373 s | 3.759 s | unavailable | unavailable | no eval reached |
| `erlonvasuoxl` | 29.307 s | 20.825 s | unavailable | unavailable | no eval reached |
| `i02s0d2jhsev` | 14.730 s | 4.184 s | unavailable | unavailable | no eval reached |

The pod open lines report estimated prompt sizes of 33,629–34,542 tokens plus
898 system tokens. No reply blob/eval receipt survived the failing turns, so a
reply-token rate or per-capability round-trip would be fabricated. The HTTP
response's `elapsed_ms` and paired ISO log timestamps are the only honest
measurements. The terminal turn log also omits the turn id; serialization makes
the pairing possible but awkward.

## Weirdness and defects

| # | Observation | Evidence | Disposition |
|---:|---|---|---|
| 1 | Final evidence had two partial config pulls after the ancestor cap path was removed; the issue named only the old helper. | `src/seon/web/serve.cljs:1140-1171,1410-1487` | Fixed; archived issue updated. |
| 2 | Pulling the identity attribute did not reliably preserve it in the merged row, so the boundary also has to restore the lookup-ref value before Malli validation. | Live 500 HTTP files; same source lines | Fixed at the owning merge boundary. |
| 3 | Package acquisition unconditionally queries an uninstalled provenance attribute and prevents every agent turn on a package-empty cluster. | `src/seon/execution.cljs:343-366`; isolated pod logs | Existing blocker issue updated: `cluster-package-corpus-has-no-loader-door.md`. |
| 4 | `/agents/run` can spend 4–52 seconds and then return no reply/eval timing when acquisition fails after the model call. | Performance table and HTTP evidence | Consequence of defect #3; no separate issue. |
| 5 | Terminal `turn ▸ run-turn! error` log lines omit the turn id outside the exception body, making cheap timing extraction fragile. | `src/seon/agent/turn.cljs:1159-1163` | Measurement awkwardness, not filed as a blocker. |
| 6 | A transient DeepSeek connection error was clear and retried, but final HTTP evidence could not distinguish it once the later acquisition failure won. | Isolated pod log around `erlonvasuoxl` | Honest observability limitation; no separate defect filed. |

## Gates and cleanup

- Focused serve: `tmp/orchestrator/e2e-focused-serve.log` — 2 tests, 3
  assertions, 0 failures/errors.
- Full CLJS after the final fix: `tmp/orchestrator/e2e-full-cljs.log` —
  1,561 tests, 7,714 assertions, 0 failures/errors.
- Failed initial boot and coordinated concurrent-lane evidence:
  `tmp/orchestrator/e2e-up.log` and related `e2e-*` operator logs.
- Final operator cleanup: `tmp/orchestrator/e2e-final-down.log`; stopped status
  in `tmp/orchestrator/e2e-final-status.edn`.

The drive does not claim db/fs/shell/web success or deliberate steering
coverage. Those forms never reached evaluation after restart. Re-run this same
scenario only after the open package-corpus issue installs or conditionally
omits its provenance attribute on package-empty clusters.
