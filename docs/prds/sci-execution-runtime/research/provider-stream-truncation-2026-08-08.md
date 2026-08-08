---
type: research
status: complete
tags: [research, runtime, ai, transport]
---

# Provider stream truncation — hypothesis, refutation, and the fix

Repair lane for the whole-system arc's provider blocker, 2026-08-08.
Read end to end before starting:
[a-mid-stream-provider-disconnect-discards-the-whole-turn.md](../../../seon/issues/a-mid-stream-provider-disconnect-discards-the-whole-turn.md),
[concurrent-provider-calls-fail-with-a-closed-response-body.md](../../../seon/issues/concurrent-provider-calls-fail-with-a-closed-response-body.md),
the provider findings in
[whole-system-arc-observer-2026-08-08.md](whole-system-arc-observer-2026-08-08.md),
and the [llm-providers skill](../../../../.agents/skills/llm-providers/SKILL.md).

## Verdict

**The per-request `HttpClient` hypothesis is REFUTED as the cause of the
`closed` failures.** It is a real defect on its own merits and is fixed,
but four independent probes and the JDK's own source say it cannot close
a body that is still being read.

**The cause is still unnamed — because our diagnostic threw it away.**
The JDK puts the real failure in the cause of the `IOException("closed")`
it raises, and the catch site recorded only `ex-message`. Seven
consecutive production turns therefore reported the single word `closed`
and nothing else. That is fixed: the next occurrence is self-diagnosing.

**The recovery half is fixed and proven.** A truncated stream keeps what
arrived, so a mid-body disconnect can no longer discard billed output.

## Dependency ledger

- `java.net.http`, openjdk 26.0.1 (`lib/src.zip`, extracted to
  `tmp/provider-transport/jdksrc/`) — the transport actually in use;
- `src/seon/ai.clj` (owner) and `src/seon/web/jvm.clj:21-24` (the
  one-client idiom already in the tree);
- live provider: DeepSeek `deepseek-v4-flash`, HTTP/2, off-peak.

## The hypothesis, and why it was plausible

The observer noted 14 `:seon.ai/unparseable-body` errors reading `The
provider's response was not readable JSON: closed`, all from the three
concurrent siblings and never from `root`, and named
`src/seon/ai.clj:1069` building a new `HttpClient` per request. The
mechanism fits on paper, and the JDK does supply every part of it:

| Fact | Source |
|---|---|
| `ofInputStream`'s body future completes "immediately, before the response body is received" | `ResponseSubscribers.java:342-347` |
| the request is unreferenced when that future completes | `HttpClientImpl.java:1144` |
| the selector exits when `referenceCount()==0` and the facade is unreachable | `HttpClientImpl.java:1201-1206,943-945` |
| the reader then throws `IOException("closed", failed)` | `ResponseSubscribers.java:355-380` |

## Four probes (`tmp/provider-transport/`)

1. **`probe_client_reachability.clj` — the mechanism is real.** Closing a
   client mid-read (what the cleaner would do) throws *exactly*
   `java.io.IOException: closed`, the production message verbatim. But
   30 concurrent per-request-client calls against a local SSE stub all
   succeeded: `{:ok 30}` for per-request and shared alike.
2. **`probe_client_collected.clj` — collection happens, and is harmless.**
   Watching a `WeakReference` across the body read: **280 of 280**
   per-request clients were collected while their stream was still being
   read, and **not one read failed**.
3. **`probe_long_stream.clj` — time is not the missing variable.** 12-second
   streams, 4 concurrent, client collected in all 4: `ok:121` for both
   arms.
4. **`probe_live_deepseek.clj` — the real provider, both client shapes.**
   24 live concurrent streaming calls (6 × per-request/shared × small/20k
   prompt), HTTP/2 throughout: **24/24 succeeded**, 1.3–4.6 s each, no
   truncation.

**Why the mechanism cannot fire:** the JDK holds an operation reference
for the whole body read. HTTP/1.1 does it through `ClientRefCountTracker`
— its own comment reads "increment the reference count on the
HttpClientImpl to prevent the SelectorManager thread from exiting until
our operation is complete" (`Http1Response.java:119-147,318-330`) — and
HTTP/2 holds one while the stream is in the connection's `streams` map
(`Http2Connection.java:1565-1580`, released at `1290-1300`). So the
facade going unreachable is not sufficient, which is precisely what
probes 2 and 3 measured.

## What the durable facts actually say

Read-only queries against cluster `default` (the attempt rows survive the
restart):

- **8 attempt rows** carry a `:seon.ai/unparseable-body` error, not 14 —
  the 14 counts error facts, some shared;
- **every one is `:seon.ai.attempt/ordinal 0`.** There are no retry pairs
  in the attempt rows; the pairing the observer saw is not a second call;
- **none carries `usage-edn`, `finish-reason`, or reasoning.** Nothing
  arrived from these streams at all;
- the runs opened and closed **within the same second**, so these are not
  long streams dying late — they died immediately after the 200;
- one occurrence is at **10:05:00, after the restart**, with no
  concurrency. The concurrency correlation is weaker than reported.

**`:seon.ai/output-observed? true` on those rows was not evidence.** It
was a hardcoded constant on every 2xx failure path, so the issue's
central premise ("output WAS seen") rested on an asserted flag rather
than an observation. It is derived now.

## What landed

`src/seon/ai.clj` (commit `8c6c2d90c`):

- **the read failure ends the line sequence instead of throwing through
  the fold.** This is the construction that kills the class: the snapshot
  is in the caller's hands, so there is no code path on which a partial
  can be dropped. Text that arrived returns an ordinary completion
  carrying a flat `:seon.ai/stream-truncated` value under
  `:seon.ai/truncation`; nothing arriving makes that value the outcome,
  distinguished from an unreadable body;
- **the whole cause chain is recorded and rendered**, plus characters
  received and whether the thread was interrupted — an interrupt reaches
  a reader as a closed stream too, and the two have different owners;
- **`:seon.ai/output-observed?` is derived**, not asserted;
- **one process-wide `HttpClient`**, matching `seon.web.jvm`.

Regressions in `test/seon/ai-test` (44 tests, 186 assertions green):
a stream that ends mid-body keeps every character and records the cause
chain; an early close with no text is a named truncation and not bad
JSON; a truncated stream never reports output that never arrived, and is
still terminal for `disposition`; a stub server that promises a
Content-Length it does not deliver settles what it sent; six concurrent
streams complete on the one client.

## Not yet landed, and why

The truncation is on the value but is **not yet a durable fact**. It
must ride a NEW attempt attribute (`:seon.ai.attempt/truncation`), never
`:seon.ai.attempt/error` — `test/seon/cluster/turn_test.clj:2393`
derives "this attempt failed" from that ref's presence, so reusing it
would change an existing key's relationship to the output, which the
accretion rule forbids.

That needs a declaration in `resources/seon/schemas/seon.ai.edn`, and
**schema admission is currently blocked by a foreign break**:
`src/seon/cluster/prompt.clj:22` requires `clojure.tools.logging`, which
is not in `deps.edn`, so `seon.cluster` will not load and the admission
hook refuses every schema edit with `Schema population refused
:seon.boot/instance (unregistered-predicate)`.

## The next occurrence is the evidence to wait for

The cause is genuinely unknown, and guessing a fifth hypothesis would
repeat the mistake that produced the first four. What is known: HTTP 200,
sub-second, zero deltas, both alone and concurrent. The next occurrence
now records the JDK's full cause chain and the interrupt flag, which
distinguishes at least a provider-side reset, a connection failure, and
an interrupt of the reading virtual thread — three different owners.
