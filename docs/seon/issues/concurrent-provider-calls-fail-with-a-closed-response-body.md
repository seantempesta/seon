---
type: issue
status: open
severity: blocker
tags: [issue, runtime, agent, ai, live-drive]
---

# Reuse one HttpClient so concurrent provider calls stop closing mid-read

## Problem

When several agents take turns at the same time, provider responses fail while
being read, with `The provider's response was not readable JSON: closed`. The
call reached a 2xx — our own code comment says so explicitly — which means the
provider generated and **charged for** a completion we then discarded. The turn
then retries and often fails the same way.

This only appeared once more than one agent ran a turn concurrently, which is
exactly the configuration the whole-system arc exists to prove.

`src/seon/ai.clj:1069` constructs a **new `HttpClient` per request**:

```clojure
(let [client (.build (HttpClient/newBuilder))
```

A JDK `HttpClient` owns its connection pool and its selector thread. When one
becomes unreachable its connections close, which is precisely the `closed` that
`slurp` on the streamed body reports. Building one per call also defeats
keep-alive entirely and creates a selector thread per provider request.

Stated as the leading hypothesis, not a proven cause: the correlation is strong
and the mechanism fits, but it has not been falsified directly. The per-request
client is worth fixing on its own merits regardless of whether it is the whole
cause here.

## Evidence

Cluster `default` (pid 31475), whole-system-arc observer lane, 2026-08-08.
Four agents (`root`, `inventory`, `health`, `timeline`) with measured 20–30 s
overlapping run intervals.

14 `:seon.error/kind :seon.ai/unparseable-body` facts, all with the same
message:

```text
The provider's response was not readable JSON: closed
```

Distribution is the finding:

```clojure
:by-agent {"inventory" 6, "health" 4, "timeline" 4}   ; root: 0
:by-time  {"09:47:38" 6, "09:52:28" 2, "09:53:28" 2, "09:53:29" 2, "09:55:40" 2}
```

- Only the three agents that ran concurrently are affected. `root`, whose calls
  were mostly alone, never hit it in 10 runs.
- Failures arrive in pairs — the initial call plus its retry
  (`:seon.config.ai.retry/maximum-retries 2`). The burst of 6 at 09:47:38 is
  three agents × 2, triggered by three delegation messages sent at that instant.
- It is intermittent, not total: three concurrent calls at 09:45:24–25 all
  succeeded with correct usage accounting.

The construction site (`src/seon/ai.clj:1069`) and the failure site
(`src/seon/ai.clj:1105-1130`, catching around `streamed-completion` /
`slurp`) are in the same function. The catch block's own comment records the
cost:

```clojure
;; a 2xx body EXISTS, so the provider
;; generated and charged for output
;; even though we cannot read it
```

## Relationship to the mid-stream disconnect note

[Settle what arrived when a provider stream closes mid-body](a-mid-stream-provider-disconnect-discards-the-whole-turn.md)
was filed independently by the drive lane on the same symptom. The two are
complementary and should both land:

- that note owns **recovery** — a 200 whose stream ends early has produced real
  output, and the run should settle what arrived instead of discarding the
  whole turn;
- this note owns **prevention** — the disconnects correlate with concurrency
  and with a per-request `HttpClient`, so most of them should stop happening.

Neither substitutes for the other: recovery without prevention keeps paying for
discarded completions, and prevention without recovery still loses a turn on the
disconnects that remain.

## Acceptance

- One `HttpClient` is built once and reused for every provider request, so a
  request cannot lose its connection because a per-call client became garbage.
  Its lifetime belongs to the environment that owns the provider seam, not to
  the call.
- A falsifier runs first and is recorded: hold one client, drive a concurrent
  burst of N agent turns, and show `unparseable-body: closed` does not recur.
  If it still recurs, the hypothesis is wrong and the real cause is named
  before any fix lands.
- A discarded-but-billed completion is loud: the durable fact says the provider
  charged for output we could not read, so the cost is queryable rather than
  buried in a parse error.
- One regression proves the class at the concurrency that exposed it, not at
  one call.
