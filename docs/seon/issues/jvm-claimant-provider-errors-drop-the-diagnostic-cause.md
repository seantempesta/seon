---
type: issue
status: open
severity: blocker
tags: [issue, agent, runtime]
---

# Retain the cause of JVM claimant provider errors

## Problem

The live JVM claimant terminalizes immediate LLM failures only as
`:seon.ai.attempt/outcome :provider-error`. The durable attempt row drops the
bounded `:seon.ai/msg` and transport classification from the response, so an
operator cannot distinguish credential resolution, request construction,
client-state, transport, or response-decoding failures from database evidence.
This made a graduation-blocking production failure impossible to finish
diagnosing from the system of record.

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

The failure is specific to the long-lived production claimant path:

- a minimal authenticated request to the same endpoint/model returned HTTP
  200;
- the live host process has a nonempty `DEEPSEEK_API_KEY` byte-equal to the
  shell credential, checked without printing it;
- a fresh JVM invocation of the maintained `seon.ai.http/complete` leaf
  succeeded; and
- the exact 171,177-character persisted prompt blob for turn
  `dp9w4r7dyq0t` succeeded through that leaf with DeepSeek request id
  `06cf85ab-7542-4f8f-9926-f9f4e846fe76`.

The remaining subcause is not recoverable from the receipts because
`seon.agent.turn.llm/attempt-row` stores status and selected response identity
but not the bounded `:seon.ai/msg` or `:seon.ai/transport?` value produced by
`seon.ai.http/request!`. Full evidence and transaction timings are in
`tmp/orchestrator/redrive2-gate.log`.

## Owner

`seon.agent.turn.llm/attempt-row` owns durable provider-attempt evidence.
`seon.ai.http/request!` owns the bounded failure classification, and
`seon.agent.driver.host/bounded-llm-transport!` owns the long-lived claimant
call boundary.

## Acceptance

- Every non-success terminal attempt persists a bounded, non-secret cause and
  the applicable timeout, transport, or HTTP classification.
- A focused long-lived-host regression distinguishes no credential, changed
  client configuration, invalid request, transport error, HTTP status, and
  invalid response.
- The exact live default-cluster prompt completes through the production
  claimant, writes a successful attempt receipt with response identity, and
  proceeds to eval receipts.
- A real agent completes multi-turn work after the fix; a standalone leaf
  call is not sufficient proof.
