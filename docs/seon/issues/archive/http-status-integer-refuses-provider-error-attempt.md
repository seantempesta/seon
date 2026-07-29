---
type: issue
status: resolved
severity: blocker
tags: [issue, agent-runtime, provider, database]
---

# HTTP status `Integer` refuses a provider-error attempt

## Problem

The JDK HTTP client returns `HttpResponse.statusCode()` as a JVM `Integer`.
`seon.ai/send-request` places that value directly under
`:seon.ai/http-status`, whose Datahike declaration is `:db.type/long` and
therefore accepts exactly `java.lang.Long`.

A provider error such as HTTP 502 consequently causes the attempt/error
transaction itself to refuse. The recorder repeatedly logs the same
`:datahike/write-error`, so the failure path cannot durably say why the model
call failed.

## Evidence

The dedicated load smoke on 2026-07-29 pointed a real Seon agent at a
recording proxy while its MLX upstream was down. Every request received 502.
The first attempt transaction failed with:

```text
Bad entity value 502 at
[:db/add 734 :seon.ai/http-status 502],
value does not match schema definition.
Must be conform to: (= (class %) java.lang.Long)
```

The value originates at `src/seon/ai.cljc` in `send-request`:
`status` is `(.statusCode response)`, then stored as `::http-status`.
`src/seon/cluster/loop.cljc` already documents and applies the same required
`long` conversion for attempt ordinals, but not HTTP status.

Raw reproduction evidence is under
`tmp/load-testing/evidence/smoke/proxy.jsonl`; the scratch cluster used
`tmp/load-testing/runtime/smoke-1`, never the default cluster.

## Owner

The ordinary HTTP response boundary in `seon.ai` and the provider-attempt
transaction in `seon.cluster.loop`.

## Resolution

Resolved by `ff9dde36e`. `seon.ai/send-request` converts the JDK
`HttpResponse.statusCode()` value with `long` exactly once as it crosses the
HTTP boundary. The provider-error value and every downstream transaction
therefore carry the `java.lang.Long` required by Datahike's
`:db.type/long`.

## Proof

The recurring `a-real-jdk-provider-status-commits-with-its-attempt` test runs
the JDK client against a real loopback 502 response, asserts the normalized
status is a `Long`, then invokes the production attempt recorder against a
canonical in-memory database. The attempt and its referenced error fact commit
together with status 502 intact. The complete `bin/test` gate passed 480 tests
and 2,039 assertions with zero failures or errors on 2026-07-29.
