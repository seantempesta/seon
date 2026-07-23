---
type: research
status: complete
tags: [research, agent, runtime, packages]
---

# Loop vertical slice live proof — 2026-07-22

## Outcome

The loop-first mixed-tier slice is green. One durable agent,
`common-camels-sit`, called the Bun-local
`seon.packages.js.fast-deep-equal/equal?` package leaf, then ran the preserved
municipal heat-resilience scenario through the existing JVM SCI host, survived
a clean pod/host/writer restart, and recovered its plan, candidate facts,
set-valued services, EDN assessment data, and project-relative artifact.

The earlier zero-receipt observation was live wiring, not SCI evaluation: the
protected launch manifest reconciled `:seon.config.execution/host-tier?` to
false and removed the eval-socket facts. Enabling the database fact and
reconciling existing host coordinates made ordinary batches execute
immediately. Restart without reapplying the manifest preserved that database
truth. The exact pod write-back boundary is now loud: a non-empty executable
batch returning zero terminal attempts records a core fault and returns a flat
`:seon/error` value instead of silently becoming a formless turn.

## One-page slice plan

The slice routes exactly `seon.execution.runtime/eval-batch!`. The loop calls
it from `seon.agent.turn/eval-parsed!`. `seon.execution.host/invoke-now!`
computes the batch tier from parsed executable symbols, loader forms, and
program-graph require edges: references to `seon.packages.js.*` use the
agent's existing Bun child; every other executable batch uses the existing
`:seon.execution.host/eval-socket-path` and the JVM SCI host. Quoted data and
strings do not influence selection. Cross-tier `result/<id>` references return
steering because result vars remain tier-local; durable continuity crosses
through corpus and database facts.

The SCI lane remains `seon.host.invoke` →
`seon.host.eval/eval-batch-result`, including its invocation admission,
running/terminal receipts, tee, and run fence. Package acquisition, the loader
door, per-agent children, recovery, the database writer, process kinds, and
supervision stay untouched. No package-name hand list, second evaluator,
host-to-Bun package RPC, or new supervisor was introduced.

## Implementation map

- `src/seon/execution/host.cljs:815-879` — parsed reference derivation,
  package selection, and tier-local result steering.
- `src/seon/execution/host.cljs:1017-1048` — computed per-batch routing.
- `src/seon/agent/turn.cljs:440-490` — exact pod write-back invariant: an
  executable batch with zero recorded attempts becomes a recorded core error.
- `src/seon/execution.cljs:342-375` — installed-schema-derived acquisition
  contract; production behavior unchanged.
- `test/seon/execution/host_test.cljs:1036-1098,1216-1263` — routing proof.
- `test/seon/agent/turn_test.cljs` — loud zero-attempt regression.
- `test/seon/execution_test.cljs:685-715` — fixture admits both structurally
  selected acquisition families and explicitly tests installed/absent package
  schema.
- `src/seon/host/invoke.clj:115-144`, `src/seon/host/eval.clj:255-455`, and
  `src/seon/runtime/recovery.cljs:351-525` — unchanged owners.

## Root-cause trace

The hop trace was: parsed forms reached `eval-parsed!`; the mixed-tier router
was correct; but the launch database had host-tier false and no agent
eval-socket facts, so there was no live SCI lane to admit an invocation.
Writer and focused evaluator tests remained green because neither reproduced
that launch reconciliation. After transacting host-tier true and invoking the
existing coordinate reconciliation, a real ordinary batch immediately
produced host receipts. The new `eval-parsed!` guard covers the exact opposite
failure too: if a selected execution tier ever returns a result batch without
accounting for executable entries, the pod records a fault at write-back.

## Live acceptance evidence

The isolated cluster `loop-slice-live` used port 7898 and its own database,
process, socket, log, and package directories. The same agent arc produced:

- package segment: 12 turns, 33 receipts; fully-qualified
  `fast-deep-equal/equal?` returned true through the existing Bun loader door;
- heat turn 1: 20 turns, 186 receipts; durable `my.plan` root
  `zsjr0gfiy1qv`, authored `my.heat-resilience` schema, candidates
  `library-downtown` and `community-center-east`, set-valued services, EDN
  assessment maps, deliberate database/filesystem/shell/web failures and
  successes, and `tmp/e2e-heat-resilience/brief.edn`;
- clean config-free restart: watcher/writer/host/pod all ready and the agent
  resumed; the database host-tier fact remained authoritative;
- heat continuation: 15 turns, 163 receipts; it queried the durable plan,
  recovered both candidate lookup refs with their sets and assessment facts,
  read/wrote the artifact, exercised capability outcomes, and updated the
  plan checkpoint.

Raw HTTP evidence is
`tmp/orchestrator/loop-slice-demo-package.json`,
`loop-slice-demo-turn-01.json`, and `loop-slice-demo-turn-02.json`; restart
evidence is `loop-slice-live-restart.log`. The TERM encore was not run: the
required restart proof completed, and adding a second disruption after the
long bounded model arc was optional.

## Measured `my.*` working set

Counts come only from actual eval receipt source across the three accepted
segments. Data attributes such as `:my.heat-resilience/site-id` are facts, not
function calls, and are excluded.

- `my.ns/functions` — 1 call.
- `my.plan/done!` — 5 calls.
- `my.plan/plan!` — 6 calls.
- `my.plan/tree` — 7 calls.

This exact four-symbol list is the next porting queue. No `my.*` family was
ported in this unit.

## Performance

| Segment | Wall | Turns | Receipts | Prompt tokens | Reply tokens | Reply tok/s |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| package | 166.616 s | 12 | 33 | 422,155 | 2,264 | 13.6 |
| heat turn 1, SCI | 432.498 s | 20 | 186 | 921,673 | 13,148 | 30.4 |
| post-restart continuation, SCI | 329.808 s | 15 | 163 | 896,311 | 12,918 | 39.2 |

These rates use reply tokens divided by HTTP wall time. Per-turn prompt/reply
tokens and receipt survival are present in the raw JSON. The HTTP evidence
does not export receipt duration, and the host log does not separately time
socket framing, so a seam-only round-trip number is not observable without
adding instrumentation and is intentionally not invented.

## Gates

- `tmp/orchestrator/loop-slice-focused-loud.log` — 1 test / 4 assertions.
- `tmp/orchestrator/loop-slice-focused-acquisition-fixed.log` — 2 / 14.
- `tmp/orchestrator/loop-slice-focused-final.log` — 38 / 182.
- `tmp/orchestrator/loop-slice-full-cljs-final.log` — 1,566 / 7,732 / 0
  failures / 0 errors.
- `tmp/orchestrator/loop-slice-full-writer-final.log` — 382 / 2,982 / 0 / 0.

The stale acquisition fixture issue and the zero-receipt live-wiring issue are
resolved and archived. The conversion wiki was appended before completion.
No commit was made.
