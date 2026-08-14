---
type: issue
status: resolved
severity: friction
tags: [issue, ai, provider, class/p1]
---

# Classify 402 as a "not here" refusal so it can fail over

## Problem

`seon.ai/status-class` sent DeepSeek's 402 Payment Required response to
`:request`, so `seon.ai/disposition` returned terminal `:fail` even when a
backup target was configured. The one observed provider-exhaustion refusal
therefore bypassed the existing failover path.

## Resolution

402 now joins 401 in the existing `:authentication` family: a conclusively
free "not here" refusal. `disposition` consequently returns `:failover-now`
when a backup exists and `:fail` when it does not; it never backs off against
the same empty-balance target.

The regression starts a local JDK `HttpServer`, receives a real 402 through
`seon.ai/complete`, and proves the resulting disposition selects a configured,
distinguishable backup target. It makes no provider call.

## Evidence

- Before the repair, a live JVM probe returned
  `{:seon.ai/error-class :request, :seon.ai/disposition :fail}` for 402 with a
  backup present.
- `bin/test seon.ai-test` exercises
  `payment-required-primary-selects-the-configured-backup` against the real
  HTTP leaf and the pure disposition seam.

## Dependency ledger

- JDK `java.net.http.HttpClient` supplies the response status consumed at
  `src/seon/ai.clj`'s one HTTP leaf; the test uses the JDK's local
  `com.sun.net.httpserver.HttpServer` to preserve that boundary without a
  paid call.
- `seon.ai/targets` derives primary and backup values, and
  `seon.ai/disposition` derives the action from leaf evidence.
- `seon.cluster.loop/call-turn` is the only caller that reduces
  `:failover-now` into a second target attempt.

## Grounding

- `src/seon/ai.clj` — `status-class`, `disposition`, and the HTTP leaf
- `src/seon/cluster/loop.clj` — `call-turn`
- [provider-routing-openrouter-2026-08-14.md](../../../prds/sci-execution-runtime/research/provider-routing-openrouter-2026-08-14.md)
  §4, where this was found
