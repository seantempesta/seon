---
type: research
status: blocked
tags: [bootstrap, agents, experiment, rendering]
---

# Bootstrap baseline experiment — blocked 2026-08-04

## Verdict

The Arm A/Arm B experiment is not valid and did not complete. The lane stopped
when every completed raw report showed the agents a broken transcript: calls to
`seon.cluster.run/render-receipt-ai` failed with a
`java.lang.NullPointerException`, and message rendering through
`seon.cluster.message/render-ai` failed the same way. Continuing would have
measured behavior under a broken renderer rather than either bootstrap vector.

The exact agent-visible value was:

```clojure
#:seon.error{:kind :seon.sci.kernel/invocation-failed,
             :message "Invocation of seon.cluster.run/render-receipt-ai failed: Cannot invoke \"java.lang.Number.doubleValue()\" because \"x\" is null"}
```

This report records the invalid partial run faithfully. Its predicate scores
must not be used as bootstrap-quality evidence.

## Grounding and implementation

I read
[`bootstrap-vector-design-2026-08-01.md`](../plan/bootstrap-vector-design-2026-08-01.md)
and `src/seon/bootstrap_drive.clj` end to end before editing or running the
experiment.

Commit `02dd76e8a` parameterizes only the drive's fresh source-population call.
The request key `:seon.bootstrap-drive/bootstrap-forms` accepts a candidate
vector, validates it against `:seon.bootstrap/default-forms`, and supplies it
while the isolated drive root publishes `current-src`. A live scratch proof
refused an empty vector as
`:seon.bootstrap-drive/invalid-bootstrap-forms`; a one-form candidate then
published one database plan row whose source was `(help)`.

The current shipped resource contains 13 database plan forms. The design's
14-item count includes the separate banner plus those 13 forms. Arm A used the
actual shipped 13-form resource; Arm B was prepared as the first form map only,
including its `:seon.bootstrap.plan.form/context`, but was not launched after
the blocker appeared.

The drive also records `:seon.bootstrap-drive/model-attempts` in each raw
report so token usage remains auditable, and a local `:runs` sequence stops
after a recorded HTTP 402. No 402 occurred in this partial run.

## Partial predicate matrix

The denominator is the number of raw reports produced before the stop, not the
ruled 10 attempts. `not run` means the lane obeyed the stop condition; it does
not mean predicate failure.

| Objective | Arm | Raw reports | Predicate results |
|---|---:|---:|---|
| O1 | A — shipped | 1 | P1a 0/1; P1b 0/1; P1c 0/1 |
| O2 | A — shipped | 10 | P2a 10/10; P2b 10/10 |
| O3 | A — shipped | 1 | P3 0/1; expected 2, completed result 3 |
| O4 | A — shipped | 10 | P4a 0/10; P4b 0/10; P4c 0/10; P4d 0/10 |
| O5 | A — shipped | 0 | not run |
| O1 | B — `(help)` only | 0 | not run |
| O2 | B — `(help)` only | 0 | not run |
| O3 | B — `(help)` only | 0 | not run |
| O4 | B — `(help)` only | 0 | not run |
| O5 | B — `(help)` only | 0 | not run |

Across the partial Arm A run, 11 reports reached a completed disposition and
11 stopped without one. All 22 nevertheless contain the renderer failure, so
even the mechanically passing O2 rows are invalid comparison evidence.

## Token use and cost

The drives began at approximately 21:47 UTC. That is outside DeepSeek's stated
2× peak windows of 01:00–04:00 and 06:00–10:00 UTC, so the off-peak registry
prices were used: $0.14/M uncached input tokens, $0.0028/M cached input tokens,
and $0.28/M output tokens.

| Objective | Reports | Model attempts | Prompt | Cached | Completion | Total | Estimated cost |
|---|---:|---:|---:|---:|---:|---:|---:|
| O1 Arm A | 1 | 1 | 0 | 0 | 0 | 0 | $0.000000 |
| O2 Arm A | 10 | 10 | 279,641 | 0 | 10,961 | 290,602 | $0.042219 |
| O3 Arm A | 1 | 1 | 20,481 | 0 | 2,736 | 23,217 | $0.003633 |
| O4 Arm A | 10 | 10 | 382,981 | 0 | 6,827 | 389,808 | $0.055529 |
| **Partial total** | **22** | **22** | **683,103** | **0** | **20,524** | **703,627** | **$0.101381** |

The O1 provider attempt returned HTTP 200 but no usage document: after roughly
180 seconds its body was recorded as unreadable JSON with message `closed`.
This was not a payment or quota refusal.

## Raw reports

All paths are under `tmp/bootstrap-drives/` and are intentionally outside Git.

- O1 Arm A:
  [`o1-1-40cb47b0.edn`](../../../../tmp/bootstrap-drives/o1-1-40cb47b0.edn)
- O2 Arm A:
  [`o2-1-f6536439.edn`](../../../../tmp/bootstrap-drives/o2-1-f6536439.edn),
  [`o2-2-ed44c498.edn`](../../../../tmp/bootstrap-drives/o2-2-ed44c498.edn),
  [`o2-3-a8d2fa99.edn`](../../../../tmp/bootstrap-drives/o2-3-a8d2fa99.edn),
  [`o2-4-e0af82b1.edn`](../../../../tmp/bootstrap-drives/o2-4-e0af82b1.edn),
  [`o2-5-29e9b9d1.edn`](../../../../tmp/bootstrap-drives/o2-5-29e9b9d1.edn),
  [`o2-6-300a3f33.edn`](../../../../tmp/bootstrap-drives/o2-6-300a3f33.edn),
  [`o2-7-527fd9db.edn`](../../../../tmp/bootstrap-drives/o2-7-527fd9db.edn),
  [`o2-8-648e446a.edn`](../../../../tmp/bootstrap-drives/o2-8-648e446a.edn),
  [`o2-9-61d04dd9.edn`](../../../../tmp/bootstrap-drives/o2-9-61d04dd9.edn),
  [`o2-10-b36d6ffb.edn`](../../../../tmp/bootstrap-drives/o2-10-b36d6ffb.edn)
- O3 Arm A:
  [`o3-1-ab391b55.edn`](../../../../tmp/bootstrap-drives/o3-1-ab391b55.edn)
- O4 Arm A:
  [`o4-1-b215ac9f.edn`](../../../../tmp/bootstrap-drives/o4-1-b215ac9f.edn),
  [`o4-2-aea457ed.edn`](../../../../tmp/bootstrap-drives/o4-2-aea457ed.edn),
  [`o4-3-bf4f97b6.edn`](../../../../tmp/bootstrap-drives/o4-3-bf4f97b6.edn),
  [`o4-4-b0c5a8b8.edn`](../../../../tmp/bootstrap-drives/o4-4-b0c5a8b8.edn),
  [`o4-5-b77274c1.edn`](../../../../tmp/bootstrap-drives/o4-5-b77274c1.edn),
  [`o4-6-cf921603.edn`](../../../../tmp/bootstrap-drives/o4-6-cf921603.edn),
  [`o4-7-dbb9486d.edn`](../../../../tmp/bootstrap-drives/o4-7-dbb9486d.edn),
  [`o4-8-54a6866b.edn`](../../../../tmp/bootstrap-drives/o4-8-54a6866b.edn),
  [`o4-9-6d332cbc.edn`](../../../../tmp/bootstrap-drives/o4-9-6d332cbc.edn),
  [`o4-10-2aff9553.edn`](../../../../tmp/bootstrap-drives/o4-10-2aff9553.edn)

## Arm B winner forms

There are no Arm B winners and therefore no agent-authored winner forms to
extract. Arm B was not launched after the renderer blocker was confirmed.

## Agent-visible ugly output

This was not merely a report-rendering problem after the run. The agents saw
the failure value in place of every bootstrap result and in place of inbound
message rendering. In the O4 transcripts, `(help)`, `(in-ns ...)`, both
`dir`/`doc` discovery forms, every example `defn` and call, the objective
message, peer messages, `my.message/send`, and `my.run/wait` were all followed
by the same `:seon.sci.kernel/invocation-failed` value. The repeated full error
map made the transcript both unusable and extremely large: the first O4 prompt
alone reached 54,811 uncached input tokens.

The exact blocked boundary is the AI transcript projection invoking the
declared render producers `seon.cluster.run/render-receipt-ai` and
`seon.cluster.message/render-ai`. This lane did not edit those runtime owners.
