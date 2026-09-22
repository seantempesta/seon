---
type: issue
status: open
severity: friction
created: 2026-09-21
tags: [issue, test, error-model, contracts, bounds]
---

# Test check classifies completed errors as expiry

## Problem

`seon.test/check` at `src/seon/test.clj:1907-1908` sends every complete error
returned by `seon.await/await!` to `expired-result`. The Future may have
completed normally with a test unknown or another declared refusal.
`await!` returns that actual completion at `src/seon/await.clj:118`.
The caller therefore cannot infer a fired bound from the presence of an error.

The await boundary's output is `[:or :seon.schema/value :seon.error/value]`
(`src/seon/await.clj:106-107`). It declares no timeout error schema. Its diagnostic producer
at `:28-51` still supplies a retired kind and omits the mandatory base members.
Timeout's bound evidence exists only in diagnostic data. The owning schema
resource `resources/seon/schemas/seon.await.edn` declares requests and bounds,
not an expiry observation.

## Evidence

The [bounded source probe](../../prds/steward-platform/research/sci-program-expiry-boundary-2026-09-21.clj)
uses the real unknown producer, a completed FutureTask, the real await owner,
the exact check branch and the real expiry producer. No default cluster or
test fixture was changed. It reports:

```clojure
{:sci-program/completed-error-preserved true
 :sci-program/completed-error-selects-expiry true
 :sci-program/original-unknown ":seon.test/selection"
 :sci-program/replacement-unknown "reaching selection"
 :sci-program/timeout-base-members {}
 :sci-program/timeout-cause :seon.await/backstop-fired}
```

This is an unarmed source probe, not canonical armed test evidence. The
incomplete timeout producer must not be claimed valid because this probe can
call it unarmed. Both await source/resource files were clean, outside the
sci-program assignment; no foreign breakage is blamed.

## Owner and acceptance

The await owner must expose the distinction at the seam that observes the
timed-get outcome. Reuse its declared `:seon.await/config-attribute` and
`:seon.await/config-value` as substantive evidence. Then `seon.test/check`
can return completed failures unchanged and construct the ruled expiry error schema
only for that actual timeout, carrying measured `:seon.test/elapsed-ms`.
Do not infer timeout from message text, inspect a retired kind, poll isDone
after the outcome, or copy a timed-get implementation into the caller.

Canonical regressions must prove both outcomes, including expiry before any
test completes and expiry after recorded results, under normal instrumentation.
The [landing note](../../prds/steward-platform/research/kind-sweep-sci-program-2026-09-21.md)
records the scope decision and exact verification boundary.
