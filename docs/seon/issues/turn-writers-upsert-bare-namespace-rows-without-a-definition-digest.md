---
type: issue
status: resolved
severity: blocking
created: 2026-09-23
tags: [issue, turn, program, definition-digest]
---

# Turn writers upsert bare namespace rows without a definition digest

## Problem

`:seon.program/definition-digest` is required on namespace rows
(`resources/seon/schemas/seon.ns.edn:17`). Three turn writers still upsert a
namespace entity whose only attribute is its name:

- `append-generated-call`, `src/seon/turn.clj:782`
- the generated-turn open transaction, `src/seon/turn.clj:817`
- `record-evaluated-call`'s `namespace-rows`, `src/seon/turn.clj:1459-1466`,
  one row for the starting namespace and one for every source's namespace

When the namespace already exists the upsert is harmless. When the reply moved
into a new namespace (`(in-ns 'my.agents.probe)`) and no digested row for it is
in the same transaction, the writer refuses:
"refused transaction data at [eid :seon.program/definition-digest] ...
Entity: #:seon.ns{:name my.agents.probe}".

The owner that digests a namespace row is `program/declaration-row`
(`src/seon/program.cljc:1257-1265`). `seon.sci.eval` already builds the ending
namespace row through it (`src/seon/sci/eval.clj:3492-3503`), and
`seon.cluster.agent/namespace-seed-call` seeds agent namespaces through it
(`src/seon/cluster/agent.clj:159-181`).

## Evidence (2026-09-23, default pid 90963, lane fixture-reds)

`seon.render.transcript-test/one-reply-reads-identically-on-the-page-in-history-and-in-the-prompt`
refuses at the `record-evaluated-tx` write in runs 911d57d52fe3, f430406e6816
and 3d30c0558fa5. The test's fixture rows are canonical: the agent comes from
`agent/creation-tx`. The refused row is produced by the turn writer.

## Wanted

These writers reference the namespace and never create a bare row. A new
namespace arrives through its digested declaration row, either the evaluation's
own row or `namespace-seed-call`. Owner: `seon.turn`, outside lane fixture-reds.

## Resolution (2026-09-23, lane turn-ns-digest)

`append-generated-call` and `generated-run-tx` no longer write a namespace row:
both already reference the namespace by lookup ref. `record-evaluated-call`
references an existing namespace and, for a new one, carries the digested
`:seon.program/row` its `in-ns` evaluation declared (built by
`program/declaration-row` in `seon.sci.eval`); no second digest path. With no
such row the bare identity remains and the writer refuses by name, as before.
Proof: run 83087cf65291 (38 assertions pass, no refusal; the test stays red
only on its 5000 ms duration bound, 8820 ms). Baseline run 8ad8eb11b01b
reproduced the refusal. Not converted: `source-rows` (plan-time receipts,
`src/seon/turn.clj` near `defn- source-rows`) still upserts an identity-only
row per source namespace; no digested row exists before evaluation there.
Landing: `docs/prds/agent-platform/landing/lane-turn-ns-digest-2026-09-23.md`.
