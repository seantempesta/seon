---
type: issue
status: open
severity: friction
tags: [issue, render, transcript, datahike, pre-read, log-noise, ugly-output]
created: 2026-09-16
---

# Resolving a message's `about` ref logs a Datahike error for every identity attribute that does not match

## Observed

Rendering any transcript that contains a message with `:seon.message/about`
emits one `:error` line per installed identity attribute that the value does
not belong to. From an in-process run of
`seon.render.transcript-test/every-generated-history-is-ordered-and-total` on
`default` (2026-09-16, pid 45917) — three lines per render, hundreds per test:

```
:error datahike.db.utils [138 10] Expected number or lookup ref for entity id, got "generated-target"
  data: {:error :entity-id/syntax, :entity-id "generated-target"}
```

The same lines appear in every gate log that touches this namespace
(`tmp/orchestrator/gate-results/batch-35/named.log:184`, and batch 39).

## Root cause

`seon.render.transcript/about-identities` (`src/seon/render/transcript.clj:211`)
disambiguates an `about` value by asking, for EVERY installed
`:db.unique/identity` attribute, whether `[attribute identity-value]` resolves:

```clojure
(keep (fn [attribute]
        (some-> (db/pull db [:db/id] [attribute identity-value]) :db/id))
      attributes)
```

Most of those lookup refs are by construction non-existent — that is the
point of the probe — and Datahike logs each miss at `:error`. So normal,
successful operation writes errors to the log.

Two things are wrong with it beyond the noise:

1. **It is a speculative existence pre-read**, one pull per attribute per
   value, where the database can answer the question directly. The value's
   owning attribute is a fact of the datom, not something to search for:
   `:avet` already indexes `[attribute value]`, and a single
   `(d/datoms db :avet)` scan — or `d/entid` per attribute, which answers
   without raising — replaces the whole loop.
2. **It scales with the identity-attribute population**, which grows with the
   schema, so every new unique attribute makes every transcript render
   slower and noisier.

## Why it matters

A log that prints `:error` during healthy rendering trains every reader to
ignore `:error`, which is how a real fault gets missed. UGLY OUTPUT IS A
DEFECT is a standing order, and this is its logging twin.

## Wanted

Resolve the owning identity attribute without probing for misses, and prove
it with the existing regression
`seon.render.transcript-test/about-identity-resolution-pulls-one-deterministic-ordered-id-vector`
extended to assert that a render emits no `:entity-id/syntax` log line.

Out of scope for the transcript/web-debug reds lane, which owned the test
expectations and fixtures rather than the identity-resolution mechanism.
