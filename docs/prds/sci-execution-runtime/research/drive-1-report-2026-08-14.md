---
type: research
status: complete
date: 2026-08-14
---

# Drive 1 — live DeepSeek evolving session

## Attempt 2 verdict

**Stopped while the generated opening advanced beyond its first healthy
receipt.** The fresh cluster and Drive 1 agent both booted, and the generated
`(help)` form evaluated successfully. Generation then closed the run with a
typed `:seon.db/invalid-read`: one captured call-preparation query's
`:seon.db/read-request` was not canonical EDN when the next opening pull read
the receipt.

Per the live-drive spec's terminal-defect rule, no paid task message,
mid-session message, or rebirth was attempted. This is Attempt 2's clean stop
boundary, distinct from Attempt 1's now-fixed advisory contract failure.

### Live endpoint and retained root

**Web UI: [http://127.0.0.1:62886](http://127.0.0.1:62886)**

- Fresh isolated operator root: `tmp/drive-1-root`
- Cluster: `default`
- Git HEAD: `fe73d8922eb7065786a7a20e519aedaa6f289c53`
- Published `:current-src` commit:
  `6a7ed1ce-9551-514e-94ca-74d2586341e9`
- Publication inventory: 232 source inputs, 2,318 schemas, 823 functions,
  7,169 contract rows, 535 namespaces
- Agent: `drive-one-agent-attempt-2`
- Namespace: `my.agents.drive-one-agent-attempt-2`
- Generated run: `bootstrap:drive-one-agent-attempt-2`
- Opening commit ID:
  `6a7ed1ce-9551-514e-94ca-74d2586341e9`
- Opening settlement basis: `t=536870969`
- Fault observation basis: `t=536870968`
- Preservation: the root was neither stopped nor reset after the attempt.
  Its cluster, episode facts, receipt, read-evidence components, error fact,
  blobs, and logs remain live.

The previous Attempt 1 root was stopped through the operator and removed before
this fresh publication, exactly as directed. Attempt 1's evidence remains in
the prior report commit.

### Attempt 2 headline measurements

| Measurement | Fact-derived result |
|---|---:|
| Creation through generated-opening observation | **9.740 s** |
| Generated forms / durable receipts | **1 / 1** |
| Healthy generated receipts | **1** |
| Receipt read-evidence components | **20** |
| Provider attempts | **0** |
| Context captures | **0** |
| Recorded `:seon.render.cost` rows | **0** |
| Provider spend | **$0.00** |
| Driver task messages | **0** |
| Authored plan items | **0** |
| Rebirths | **0** |

Agent creation began at `2026-08-14T08:30:38Z`; the healthy receipt was
recorded at `08:30:39Z`; the generated run closed at `08:30:42Z`; the
bounded observer returned at `08:30:48Z`. The complete driver-observed wall
interval was **9.740 seconds**.

### Verbatim generated entry and value

The sole stored generated source was:

```clojure
; A new run just opened. Why am I awake — do I have messages?
(help)
```

Its receipt had no `:seon.cluster.eval/error` and no `:seon.error/kind`.
The admitted result, rendered from the stored print face, was:

```clojure
{:seon.cluster.agent/id "drive-one-agent-attempt-2"
 :seon.cluster.agent/namespace-ref
 [:seon.ns/name my.agents.drive-one-agent-attempt-2]
 :seon.cluster.agent/unread-message-count 0
 :seon.cluster.run/turns-remaining 99
 :seon.cluster.agent/protocol-namespaces
 [my.message my.run seon.bootstrap seon.db]
 :seon.cluster.agent/open-run-ref
 [:seon.cluster.run/id "bootstrap:drive-one-agent-attempt-2"]
 :seon.cluster.run/trigger
 [:seon.cluster.message/id "bootstrap-task:drive-one-agent-attempt-2"]}
```

The receipt read at `t=536870967` and owns 20 read-evidence component rows.
It has no recorded `:seon.eval/fn-entries` or
`:seon.eval/allocated-bytes` fact.

### Typed stop evidence

The generated run closed with:

```clojure
{:seon.cluster.run/id "bootstrap:drive-one-agent-attempt-2"
 :seon.cluster.run/opened-at #inst "2026-08-14T08:30:38.000-00:00"
 :seon.cluster.run/closed-at #inst "2026-08-14T08:30:42.000-00:00"
 :seon.cluster.run/error
 "The EDN-backed attribute :seon.db/read-request has an invalid logical value."}
```

The total fault committer recorded error fact
`9f54bedb-7424-4857-b862-7fc4a8ab36c2`:

```clojure
{:seon.error/kind :seon.db/invalid-read
 :seon.error/message
 "The EDN-backed attribute :seon.db/read-request has an invalid logical value."
 :seon.error/basis-t 536870968
 :seon.db/operation :datahike.pull/result
 :seon.schema.datahike/rule :seon.schema.datahike/noncanonical-edn
 :seon.schema.datahike/attr :seon.db/read-request}
```

The offending stored logical value, verbatim from
`:seon.error/data-edn`, was:

```clojure
#:seon.db{:read-operation :q,
          :query-request
          {:query
           {:find [?key ?schema-key ?fingerprint ?supplier],
            :in [$],
            :where
            [[?row :seon.call-preparation/key ?key]
             [?row :seon.call-preparation/schema ?schema]
             [?schema :seon.schema/key ?schema-key]
             [?schema :seon.schema/shape ?shape]
             [?shape :seon.schema.shape/fingerprint ?fingerprint]
             [?row :seon.call-preparation/supplier ?function]
             [?function :seon.fn/sym ?supplier]]},
           :args [:seon.db/database]}}
```

`bootstrap:root` independently exhibited the same shape before agent
creation: one healthy receipt followed by the same run error. The Drive 1
agent reproduction establishes that the defect is not root-specific.

### Attempt 2 measurement families

#### 1. Opening composition

The complete opening was not generated. One source/value entry settled, but
there are zero context captures and zero `:seon.render.cost` facts in the
fresh database. Total opening tokens, explained-closure tokens,
beyond-closure tokens, and per-entry shape/cost are therefore **unavailable**,
not zero. The one entry reached `help`; generation failed while deriving its
successor.

#### 2. Live pull

Named conditions: fresh isolated root at the published commit above, shipped
`deepseek-v4-flash` with thinking disabled, one new agent, opening
`(help)`, 30,000 ms eval limit, receipt read basis `t=536870967`.
Creation-to-observer wall time was **9.740 s**. The pull terminated rather than
wedging. Its receipt contains 20 read-evidence components, but no
`:seon.eval/fn-entries` fact, so a call-count comparison with the attribution
baseline is unavailable.

#### 3. Delta economics

**Not reached.** No task or mid-session message was sent, so no delta or
counterfactual full rerender exists.

#### 4. Rebirth in vivo

**Not reached.** A rebirth after a terminally incomplete generated opening
would be a different experiment. Lived/reborn tokens, determinism, and
post-rebirth behavior are unavailable.

#### 5. Agent behavior against context

No model turn occurred. The system-generated `(help)` form executed, but the
agent did not replay shown forms, use `doc`/`dir`, follow a first-use
demonstration, operate `my.plan`, or anchor a temporal form. These are
unobserved opportunities, not negative model decisions.

#### 6. Provider cost

There are zero `:seon.ai.attempt/run` facts and zero context captures for
the run. Total provider spend is therefore **$0.00**. Neither the primary
DeepSeek route nor the configured failover was called.

#### 7. Basis-`t` comprehension

**Not reached.** The receipt records `t=536870967`, but no model prompt was
captured and no agent could interpret or reuse the basis display.

#### 8. Planner text without structured `:about`

**Not reached.** There are zero authored `my.plan.item` entities. The
frequency has denominator zero and is unavailable rather than 0%.

### Attempt 2 stop boundary

```text
fresh init and start
→ system root opening: healthy (help), then invalid read
→ Drive 1 agent creation
→ generated opening form 0: healthy (help) receipt
→ next-entry pull decodes noncanonical :seon.db/read-request
→ durable :seon.db/invalid-read error fact
→ generated run closes with error
→ STOP before any paid message
```

Continuing would have bypassed the generated-opening contract and invalidated
the requested evolve→rebirth→continue measurement.

### Attempt 2 codec resolution — 2026-08-14

This is the **same database-codec class** as
[`wildcard-receipt-pull-refuses-a-stored-dependency-plan.md`](../../../seon/issues/archive/wildcard-receipt-pull-refuses-a-stored-dependency-plan.md),
not a separate `:seon.db/read-request` defect. Both that attribute and
`:datahike.read/dependency-plan` select the heterogeneous-union EDN fallback in
`seon.schema.datahike`. Its write seam used ambient `pr-str`, so
`*print-namespace-maps*` and map/set iteration order could change durable bytes;
the strict reader then correctly refused a stored string unequal to its
canonical re-encoding. That was a two-sided contract break created by the
writer, not a reason to weaken the reader.

Commit `8ec96cbf1` fixes the one canonicalization seam: recursively ordered map
and set data is printed under one complete readable binding policy. The strict
noncanonical refusal remains unchanged. The production-database regression
round-trips the exact captured call-preparation query plus a dependency plan
through `seon.db/transact!` and wildcard `seon.db/pull`, under opposite
namespace-map bindings and opposite construction/iteration orders. The test
failed six assertions before the fix; afterward the focused gate passed **9
tests / 101 assertions / 0 failures / 0 errors**. Commit `17787d4a6` archives
the single issue note with that evidence.

A fresh isolated root at `tmp/codec-live-root`, published as current-source
commit `6a7ed89d-557a-531a-b9a2-bfab87b3b2a2`, supplied the live boundary proof.
With `*print-namespace-maps*` forced false, wildcard-pulling the real ordinal-0
`bootstrap:root` receipt returned an ordinary value: receipt entity `29971`,
23 read-evidence components, 22 decoded `:seon.db/read-request` values, 23
decoded `:datahike.read/dependency-plan` values, and no `:seon.error/kind`.

The fixed opening then advanced past the codec boundary and exposed the
distinct existing prefix-drift failure, `"A stored generated form is outside
the pull."`, in `seon.bootstrap/next-entry`. That evidence belongs to the
already-open
[`generated-opening-live-pull-does-not-return-after-help.md`](../../../seon/issues/generated-opening-live-pull-does-not-return-after-help.md);
it is not part of the database-codec class and was not patched here.

## Attempt 1 verdict

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
