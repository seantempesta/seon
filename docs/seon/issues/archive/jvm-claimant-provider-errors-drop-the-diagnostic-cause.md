---
type: issue
status: resolved
severity: blocker
tags: [issue, agent, runtime]
---

Terminology: this note records evidence from before the rename; the process holding a run is now `:seon.agent.run/process`.

# Retain the cause of cluster JVM provider errors

## Problem

The durable cluster JVM path terminalizes immediate LLM failures only as
`:seon.ai.attempt/outcome :provider-error`. The durable attempt row drops the
bounded `:seon.ai/msg` and transport classification from the response, so an
operator cannot distinguish credential resolution, request construction,
client-state, transport, or response-decoding failures from database evidence.
This made a graduation-blocking production failure impossible to finish
diagnosing from the system of record.

The original live evidence was misattributed to the JVM. Historical claim
datoms and operator process records prove that the recorded failures were
owned by the Bun pod. The diagnostic loss is nevertheless real at the shared
durable attempt projection and also affected the JVM leaf's exception mapping.

## Evidence

Agent `real-mails-fix` opened three real runs and turns on the ready default
cluster:

- run `xn9l2q67n1cz`, turn `tqnt2mst9nvc`, streamed first-form policy;
- run `t14vkircg9gb`, turn `dp9w4r7dyq0t`, batch policy; and
- run `n9m1a5qcgr07`, turn `npxvax0t657o`, batch policy with zero retries.

All three turns advanced through `:attempt-open` to terminal `:published` and
closed `:error`. Eleven durable DeepSeek attempt receipts were written. Every
receipt has provider `:deepseek`, adapter `:openai-compat`, model
`deepseek-v4-pro`, endpoint
`https://api.deepseek.com/chat/completions`, and outcome `:provider-error`.
None has `:seon.ai.attempt/error-status`, an error message, or a transport
classification. Each open-to-terminal transition took about 0.30–0.34 seconds,
far below the 60-second adapter and 120-second outer bounds.

The failure is specific to the long-lived production cluster JVM path, but not to
the JVM transport:

- a minimal authenticated request to the same endpoint/model returned HTTP
  200;
- the live host process has a nonempty `DEEPSEEK_API_KEY` byte-equal to the
  shell credential, checked without printing it;
- a fresh JVM invocation of the maintained `seon.ai.http/complete` leaf
  succeeded; and
- the exact 171,177-character persisted prompt blob for turn
  `dp9w4r7dyq0t` succeeded through that leaf with DeepSeek request id
  `06cf85ab-7542-4f8f-9926-f9f4e846fe76`.

The failing `:seon.agent.run/process` was
`35849@2026-07-24T05:21:05.189Z`. The contemporaneous operator records identify
workload PID `35849` as the pod and PID `35766` as the JVM host. The pod still
advertised LLM capability and therefore retained its render claim through the
provider attempt.

The remaining subcause is not recoverable from the receipts because
`seon.agent.turn.llm/attempt-row` stores status and selected response identity
but not the bounded `:seon.ai/msg` or `:seon.ai/transport?` value produced by
`seon.ai.http/request!`. Full evidence and transaction timings are in
`tmp/orchestrator/redrive2-gate.log`.

## Resolution

Commit `e21c85417` retains bounded flat error message, exception class and
message, transport/timeout classification, HTTP status/body, retry delay, and
exact successful response status on the attempt receipt. The same change
removed the pod's superseded LLM capability, so the one portable driver now
hands model phases to the cluster JVM.

The suspected long-lived `java.net.http` state defect was falsified. Historical
claim datoms identify the eleven failures' process PID as the Bun pod, not the
JVM host. The maintained JVM leaf reads the credential and builds the
authorization header per request; its one process client freezes only the
configured connect timeout. No stale credential or idle-connection mechanism
was involved.

The source proof is 11 tests / 63 assertions for JVM HTTP plus receipt
projection, 5 / 22 for the portable receipt projection, and 3 / 16 for its
CLJS consumer.

The source-frozen `claimant2` gate then joined claim epoch `2` to JVM host
workload PID `50645`. Attempt `sj29e811vgsg` transitioned atomically from
`:open` to `:success` with literal response status `200`, request ID
`a4e17535-b53d-4f17-a973-82acd6eb89e9`, response model
`deepseek-v4-pro`, and a 163-byte reply blob with hash
`309e90d1655879507b3788194577bc10511ccbca7c2919d09dce390fc5417255`.
The subsequent exact-execution-plan refusal is a later boundary recorded in
[[jvm-claimant-rejects-visible-reply-without-exact-execution-plan]].
Full immutable database values and histories are in
`tmp/orchestrator/claimant2-gate.log`.

## Owner

`seon.agent.turn.llm/attempt-row` owns durable provider-attempt evidence.
`seon.ai.http/request!` owns the bounded failure classification, and
`seon.agent.driver.host/bounded-llm-transport!` owns the long-lived cluster JVM
call boundary.

## Acceptance

- Every non-success terminal attempt persists a bounded, non-secret cause and
  the applicable timeout, transport, or HTTP classification.
- A focused long-lived-host regression distinguishes no credential, changed
  client configuration, invalid request, transport error, HTTP status, and
  invalid response.
- A live isolated-cluster prompt reaches a production cluster JVM whose
  persisted PID matches that cluster's host workload and writes a successful
  attempt receipt with response identity, status 200, and a nonempty reply
  blob.
- Eval and multi-turn graduation continue at the execution-planning boundary;
  they are not provider-transport acceptance.
