---
type: issue
status: open
severity: blocker
tags: [issue, runtime, agent, ai, live-drive]
---

# Settle what arrived when a provider stream closes mid-body

## Problem

When the provider accepts a request, returns HTTP 200, and then closes the
connection part-way through the response body, Seon records
`:seon.ai/unparseable-body` and refuses the ENTIRE run — zero forms, zero
receipts, run closed in the same second it opened. Everything the model had
already produced is discarded.

The attempt's own durable facts show how much was known at the moment of
failure (cluster `default`, attempt
`97182639-eca3-468a-9750-480572f07c76-attempt-0`, 2026-08-08T09:55:40Z):

```clojure
{:seon.ai/http-status          200
 :seon.ai/request-transmitted? true
 :seon.ai/response-started?    true
 :seon.ai/output-observed?     true      ; <- output WAS seen
 :seon.ai/model                "deepseek-v4-flash"
 :seon.ai/endpoint             "https://api.deepseek.com/chat/completions"
 :seon.ai.attempt/error        {…}}
```

`:seon.ai/output-observed? true` is the point. This is not a rejected request,
a bad key, an oversized prompt, or a malformed body — the provider answered
200 and streamed real output. The transport then ended early, and the run loop
treated a partial success as a total failure.

The rendered message is also uninformative:

```text
The provider's response was not readable JSON: closed
```

It does not say the status was 200, how many bytes arrived, whether output had
been observed, or where parsing stopped — all of which the attempt row already
records. A reader of the run error is told strictly less than the database
knows.

## Reproduction and blast radius

Whole-system arc drive, cluster `default` (pid 31475), 2026-08-08. Once it
started, it did not stop. Every arc-agent turn from 09:47:38 onward failed this
way — seven consecutive runs across three agents:

| Agent | Opened | Closed | Forms | Error |
|---|---|---|---:|---|
| inventory | 09:47:38 | 09:47:38 | 0 | unparseable-body: closed |
| health | 09:47:38 | 09:47:38 | 0 | unparseable-body: closed |
| timeline | 09:47:38 | 09:47:38 | 0 | unparseable-body: closed |
| inventory | 09:52:28 | 09:52:28 | 0 | unparseable-body: closed |
| health | 09:53:28 | 09:53:28 | 0 | unparseable-body: closed |
| timeline | 09:53:28 | 09:53:29 | 0 | unparseable-body: closed |
| inventory | 09:55:40 | 09:55:40 | 0 | unparseable-body: closed |

It is not a provider outage and it is not concurrency:

- the same three agents' FIRST turns (09:45:24–09:45:25, three concurrent
  calls) all succeeded with real usage rows;
- `root` succeeded at 09:50:55 with 10 forms while inventory was failing;
- inventory failed at 09:55:40 with no other run open at all.

The rendered prompt is not the cause either. The captured prompt for the last
failing run is 58,071 characters, contains no control characters, no
surrogates, and a maximum code point of 8212 (an em dash).

## Why it is a blocker

Three agents could not complete a single turn of real work across eight
minutes of the arc. Because the refusal discards the whole plan, an agent that
hits this makes no progress at all, and the durable record shows a run that
opened and closed having done nothing — which reads like an idle agent rather
than a transport failure.

## Retry policy cannot absorb it

From the same attempt's settings:

```clojure
{:seon.config.ai.retry/maximum-retries        2
 :seon.config.ai.retry/maximum-total-delay-ms 3000
 :seon.config.ai/timeout-ms                   180000}
```

Two retries inside a 3-second total budget, against a failure mode that
recurred over eight minutes. The bound is far below the timescale of the
condition it would have to ride out.

## Acceptance

- A 200 response whose stream ends early is distinguished from an unreadable
  body. Its error names the status, the bytes received, and whether output was
  observed — the attempt row already holds all three.
- Observed output is not thrown away: either the forms that fully arrived are
  settled, or the refusal states explicitly that complete output was
  unavailable, rather than reporting the body as unparseable.
- One regression proving the class dead: a provider response truncated
  mid-body on a 200 produces a typed early-close refusal carrying the observed
  byte count, and never a run that closes with zero forms and no explanation.

## Owner

`src/seon/ai.clj` (response reading and `:seon.ai/unparseable-body`) with
`src/seon/cluster/loop.clj` at the phase-refusal boundary.

## Recovery landed 2026-08-08 — commit `8c6c2d90c`

Evidence and the refuted hypothesis:
[provider-stream-truncation-2026-08-08.md](../../prds/sci-execution-runtime/research/provider-stream-truncation-2026-08-08.md).

The class is dead by construction rather than by a check. A read failure
now ENDS the line sequence instead of throwing through the fold, so the
accumulated snapshot is in the caller's hands and there is no path on
which a partial can be dropped:

- text that arrived returns an ORDINARY completion carrying a flat
  `:seon.ai/stream-truncated` value under `:seon.ai/truncation`, so the
  run settles the forms that arrived instead of closing with zero;
- nothing arriving makes that same value the outcome — distinguished
  from an unreadable body, and it no longer blames the JSON;
- the message and data carry the JDK's WHOLE cause chain, the characters
  received, and whether the reading thread was interrupted. The old
  message said only `closed` because the catch site kept `ex-message`
  and dropped `.getCause` — which is why seven consecutive failures were
  never rooted.

Two corrections to this note's own premises, from the durable rows:

- **`:seon.ai/output-observed? true` was not an observation.** It was a
  hardcoded constant on every 2xx failure path, so "output WAS seen" was
  asserted, not measured. It is derived now, and the eight affected
  attempt rows carry no usage, no finish-reason and no reasoning —
  nothing arrived from those streams at all.
- **The runs opened and closed within the same second**, and one
  occurrence (10:05:00) had no concurrency, so this is not a long stream
  dying late.

Regressions (`test/seon/ai_test.clj`, 44 tests / 186 assertions green):
a mid-body end keeps every character and records the cause chain; an
early close with no text is a named truncation, not bad JSON; a
truncated stream never reports output that never arrived and stays
terminal for `disposition`; and a stub server that promises a
Content-Length it does not deliver settles what it sent.

**Still open, and the reason this note is not archived:** the truncation
is on the value but is not yet a durable FACT. It must ride a new
`:seon.ai.attempt/truncation` ref recorded by `seon.cluster.loop` —
never `:seon.ai.attempt/error`, whose presence already means "this
attempt failed" (`test/seon/cluster/turn_test.clj:2393`).

## Independent live re-verification — 2026-08-10

The old `clojure.tools.logging` blocker has cleared, but the durable half still
has not landed. On the running `default` cluster, pid 31570, this full schema
and fact probe returned:

```clojure
{:installed? false
 :fact-count 0}
```

The exact probe took the live connection's current database value, tested
`(contains? (:schema database) :seon.ai.attempt/truncation)`, and queried
`[:find (count ?v) . :where [_ :seon.ai.attempt/truncation ?v]]`. A production
source and schema search finds no declaration or loop settlement owner.

The value half is genuinely live. A JVM probe against the loaded
`seon.ai/streamed-completion` fed one valid SSE delta and then an
`IOException("closed", IOException("v3 reset"))`. It returned the text
`"(+ 1 2)"` as an ordinary completion, with no top-level error, plus
`:seon.ai/stream-truncated` and the full two-element cause chain under
`:seon.ai/truncation`. Two reads of the loaded private `client` were identical
and its class was `jdk.internal.net.http.HttpClientFacade`.

Remaining acceptance is therefore narrow and unblocked: declare and install
the attempt ref, settle the completion's truncation value through the one loop
attempt owner, and prove it queryable after settlement without classifying the
attempt itself as failed.
