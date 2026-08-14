---
type: research
status: complete
date: 2026-08-14
---

# Drive 1 — live DeepSeek evolving session

## Verdict

**Stopped at the generated-opening gate.** The isolated cluster booted and
the Drive 1 agent was created, but its first generated form, `(help)`,
settled as a durable `:seon.instrument/contract-violated` receipt. The
opening run then closed with `:seon.cluster.run/error`. Per the live-drive
spec, no task message, unrelated mid-session message, provider turn, or
rebirth was attempted after this terminal opening failure.

This is a successful failed drive with clean evidence. The retained facts also
show that `bootstrap:root` failed with the same receipt error before the
Drive 1 agent was created. That makes the exact boundary the published
generated-opening evaluation path, not the contracted-function task.

## Retained live evidence

- Isolated operator root: `tmp/drive-1-root`
- Cluster: `default`
- Web UI: `http://127.0.0.1:54474`
- Operator start: successful; dependency cache current with 363 namespaces
- PID/generation at final status: `46196` /
  `66339cf1-b143-4a8b-9b6c-babc969f123a`
- Agent: `drive-one-agent`
- Namespace: `my.agents.drive-one-agent`
- Generated run: `bootstrap:drive-one-agent`
- Opening commit ID:
  `6a7ea9b9-e2bd-52e4-9ec9-4ec1e6de0770`
- Opening settlement basis: `t=536870968`
- Final evidence-query basis: `t=536870972`
- Preservation: the operator was not stopped or reset. The root, cluster,
  episode facts, receipts, blobs, and logs remain live after this report.

Final `bin/seon --root tmp/drive-1-root status`: 1/1 clusters alive,
0.13 GiB root footprint, no orphan Seon JVMs.

## Headline measurements

| Measurement | Fact-derived result |
|---|---:|
| Creation through generated-opening observation | **10.892 s** |
| Generated forms / durable receipts | **1 / 1** |
| Healthy generated forms | **0** |
| Opening provider attempts | **0** |
| Opening context captures | **0** |
| Recorded `:seon.render.cost` rows | **0** |
| Provider spend | **$0.00** |
| Inbound task messages sent by the driver | **0** |
| Rebirths | **0** |

Agent creation began at `2026-08-14T05:39:41Z`; the receipt was recorded at
`05:39:42Z`; the run closed at `05:39:45Z`; the bounded fact observer
returned at `05:39:52Z`. The wall measurement is deliberately the complete
driver-observed interval, not just receipt latency.

## Terminal facts

The generated run has these terminal facts:

```clojure
{:seon.cluster.run/id "bootstrap:drive-one-agent"
 :seon.cluster.work/situation :generate
 :seon.cluster.run/opened-at #inst "2026-08-14T05:39:41.000-00:00"
 :seon.cluster.run/closed-at #inst "2026-08-14T05:39:45.000-00:00"
 :seon.cluster.run/error
 "The EDN-backed attribute :seon.db/read-request has an invalid logical value."}
```

The only receipt has identity
`["bootstrap:drive-one-agent" 0]`, read basis `t=536870966`, result size
3,672 bytes, and:

```clojure
{:seon.error/kind :seon.instrument/contract-violated
 :seon.cluster.eval/error
 "seon.test.accretion/non-generatable-advisory violated its contract (invalid-input): invalid type"}
```

The same fact pair already existed for `bootstrap:root`:

```text
receipt at 2026-08-14T05:38:04Z
run closed at 2026-08-14T05:38:08Z
seon.test.accretion/non-generatable-advisory violated its contract (invalid-input): invalid type
The EDN-backed attribute :seon.db/read-request has an invalid logical value.
```

The publication at Git HEAD
`e569a89bedf8591d95a391cbc29404c07c23f166` instruments
`non-generatable-advisory` with input contract `[:cat :map]`.
The ordinary non-declaration evaluation path calls it with the evaluation's
program row. For the generated `(help)` form that row is absent. The durable
receipt proves the contract failure; this source observation explains the
boundary but is not substituted for the facts.

## Verbatim generated context sample

This is the exact sole generated form source stored on the run:

```clojure
; A new run just opened. Why am I awake — do I have messages?
(help)
```

Its exact stored receipt error is:

```text
seon.test.accretion/non-generatable-advisory violated its contract (invalid-input): invalid type
```

No later opening entry was generated, so there is no honest larger context
sample to quote.

## Required measurement families

### 1. Opening composition

The opening did not complete. There is one stored entry and one receipt, but
no context capture and no `:seon.render.cost` fact. Therefore total opening
tokens, explained-closure tokens, beyond-closure tokens, and per-entry
shape/cost are **unavailable**, not zero. The fact counts are: captures 0,
render-cost rows 0.

### 2. Live pull

Named conditions: isolated root, shipped publication, one newly created
agent, generated opening, `(help)` at read basis `t=536870966`.
Creation-to-observer wall time was **10.892 s**. The receipt contains no
`:seon.eval/fn-entries` fact, so call-count comparison against the
attribution baseline is unavailable. The live pull terminated with the
contract error above; it did not wedge.

### 3. Delta economics

**Not reached.** No driver task message or mid-session message was sent, so
there is no generated delta or counterfactual full rerender to compare.

### 4. Rebirth in vivo

**Not reached.** No rebirth was triggered after the terminal opening error.
Consequently lived/reborn tokens, determinism, and post-rebirth behavior are
unavailable.

### 5. Agent behavior against context

The agent never received a model turn. It did not replay shown forms, call
`doc` or `dir`, follow a first-use demonstration, operate `my.plan`, or
anchor a temporal form on `t=`. These are **not observed**, rather than
negative model choices.

### 6. Provider cost

There are **zero `:seon.ai.attempt/run` facts** for the generated run.
Total provider spend is therefore **$0.00**. No primary or failover provider
request occurred.

### 7. Basis-`t` comprehension

**Not reached.** Although the receipt durably records read basis
`t=536870966`, no model turn occurred and the agent had no opportunity to
interpret or reuse a rendered basis tag.

### 8. Planner text without structured `:about`

**Not reached.** No authored plan item exists. Frequency has denominator zero
and is reported as unavailable, not 0%.

## Driver probe hygiene

After settlement, one diagnostic pull mistakenly requested two undeclared run
attributes, `:seon.cluster.run/generated-at` and
`:seon.cluster.run/generation-complete-at`. It returned the expected typed
`:seon.db/invalid-read` value and did not transact. A later diagnostic also
called `seon.eval.drive/terminal-state` with the wrong arity; instrumentation
refused it before execution. Neither diagnostic altered the run or accounts
for the earlier receipt: both occurred after the run had closed, and
`bootstrap:root` already carried the same failure.

## Stop boundary

The exact boundary is:

```text
isolated operator start
→ agent creation transaction
→ generated opening form 0: (help)
→ durable contract-violation receipt
→ generated run closes with error
→ STOP
```

Continuing would have converted a terminally failed opening into a different
experiment and violated the drive spec's evidence-preserving stop rule.
