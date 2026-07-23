---
type: issue
status: open
tags: [issue, agent, runtime]
severity: blocker
---

# JVM LLM transport has no claimant phase door

## Problem

The JVM LLM HTTP leaf cannot be installed without changing the protected
claimant and turn owners. The portable eligibility rule understands an LLM
capability, but the JVM claimant neither advertises nor executes it. The
durable provider-attempt phase remains a pod-only function, and the existing
host watchdog does not surround claimant-native I/O.

## Evidence

`src/seon/agent/loop/core.cljc:56-67` makes LLM eligibility a pure function of
the claimant's capability set. `src/seon/agent/driver/host.clj:252-300`
advertises only `:seon.agent.driver.capability/eval` and handles only `:eval`
and `:settle-eval`.

`src/seon/agent/turn.cljs:947-1114` owns the complete durable LLM phase:
attempt deadline evidence, pre-dispatch open transaction, takeover-shaped
`:open → :crashed`, transport dispatch, terminal CAS, reply-blob publication,
retry, and cursor advancement. There is no JVM-callable portable phase entry.

`src/seon/host/invoke.clj:29-43,106-134` arms the one watchdog only inside
`execute-invocation!`, after setting the session worker phase to `:evaluating`.
The claimant's virtual thread calls `driver/drive-run!` directly at
`src/seon/agent/driver/host.clj:270-279`; a synchronous `HttpClient/send`
inserted there would not inherit the watchdog.

The local JDK 26 API inspection is retained at
`tmp/orchestrator/u6b-jdk-http-javap.txt`: `HttpClient/send` declares
`InterruptedException`, `HttpRequest.Builder/timeout` is present, and the JDK
supplies line/body subscribers plus limiting handlers. Those properties do not
close the missing Seon invocation boundary.

## Owner

The claim-driver spine owner must settle and expose one portable durable LLM
phase entry, widen the JVM claimant's capability and step dispatch, and route
that step through the existing watchdog ceremony. Reply-blob publication must
compose with the one JVM blob leaf rather than introducing another writer.
After that seam lands, U6b owns the `java.net.http` transport and provider
entry arms under `src/seon/ai/**`.

## Acceptance

- The JVM claimant advertises `:llm` only when the JVM transport and required
  blob publication leaf are installed.
- `:open-attempt` and `:settle-attempt` execute through one portable durable
  phase entry on the JVM, with the same run/epoch and turn-phase fences.
- The existing host watchdog interrupts a hung synchronous provider request;
  no second deadline scheduler exists.
- A JVM claimant can persist open, terminal, and crashed attempt transitions
  and advance a successful reply to `:reply-ready`.
- U6b can prove batch and stream turns on its isolated cluster without editing
  protected `src/seon/agent/**` or `src/seon/host/**` files.
