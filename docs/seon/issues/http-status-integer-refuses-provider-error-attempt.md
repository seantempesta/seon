---
type: issue
status: open
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

## Acceptance

- A real non-2xx JDK response commits one attempt and one referenced error
  fact with the status value intact.
- The conversion happens once at the HTTP boundary; transaction consumers do
  not each coerce it.
- One recurring test uses the JDK HTTP client against a real loopback server,
  because a hand-built Clojure numeric literal is already a `Long` and cannot
  reproduce this class.
- The run closes with the ordinary provider-error value rather than a
  secondary transaction refusal or an error loop.
