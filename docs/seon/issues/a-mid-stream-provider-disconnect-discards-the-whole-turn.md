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
