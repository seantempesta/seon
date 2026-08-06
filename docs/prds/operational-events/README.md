---
type: prd
status: active
tags: [prd, runtime, observability, operator]
---

# Operational events: significant facts, noHistory gauges, referenced logs

## Decision

Owner-designed 2026-08-03 night, executing the successor slice the
[operator-integration PRD](../archive/operator-integration/README.md) named. ONE
event mechanism with three honestly different destinations:

1. **Significant events become durable facts.** Lifecycle transitions,
   refusals, degradations, backstop firings, recovery actions — anything
   forensics, recovery, or an owner query would ever need — commit as
   namespaced event facts beside the existing fault facts (event keyword +
   data map, the Duct-style shape the reloaded-ergonomics sweep
   recommended). The admission bar is the question itself: would a query
   ever need THIS row? If no one would query it, it is not a fact.
2. **Last-observation gauges are `:db/noHistory` attributes.** Values worth
   SHOWING somewhere but never storing long-term — last tokens-per-second,
   last latency, a proc's last wake instant — live as `:db/noHistory`
   attributes on the entity they describe. Current value queryable and
   renderable; zero history accumulation. (Owner: "things like tokens per
   second on the last use … we might want to show somewhere but we don't
   need to store it long term.")
3. **Text logs keep everything, referencing facts.** The complete verbatim
   stream — including what never becomes a fact — stays in the operator's
   log files. A log line emitted for a committed event carries that event's
   fact identity, so grep leads back to the queryable row ("include
   references to the database entries to tie it back"). First-party
   logging converges on this one emitter; raw text without a fact
   reference remains only for foreign output (JVM stderr, child
   processes, boot-before-store).

High-frequency observation (per-request timings, render churn, streamed
progress) stays on channels and derived surfaces per the transport law —
this PRD adds no fourth destination.

## Dependency ledger

| Dependency | Evidence | Boundary |
|---|---|---|
| Fault facts precedent | `seon.cluster/commit-fault!`; `resources/seon/schemas/seon.error.edn` | Events sit BESIDE fault facts, one committer pattern; a fault is not re-modeled as an event |
| Transport law | AGENTS.md ("How Seon runs", transport law) | Channel loss must stay free; no event may be the only copy of recovery truth |
| `:db/noHistory` semantics | `reference-code/datahike` — verify the flag's exact index behavior at the pin BEFORE relying on it; record file:line in the implementation | Gauges must prove history returns nothing for the attribute |
| Render producers rule | AGENTS.md reactive-context section | The event feed/page declares `:seon.render/ai` + `:seon.render/html` producers as named functions |
| Error-model wave | [error-model PRD](../error-model/README.md) | Event shapes reuse the attribute-presence idiom; no `:event/type` discriminator — the event's namespaced attribute set IS its identity |
| Operator verbs | [operator-integration PRD](../archive/operator-integration/README.md) | Verbs emit events through the one emitter; no verb grows its own logging |

## Shape sketch

```clojure
;; a significant event — attributes, not a kind
{:seon.event/at #inst "…"
 :seon.event/process "…"                  ; provenance, resolvable
 :seon.cluster/started "acme"             ; the marker: namespaced, subject-valued
 :seon.cluster/ready-ms 1416}             ; evidence rides as siblings

```

The same emission also writes this log line:

```text
2026-08-03T… cluster started acme ready-ms=1416 event=<fact-id>
```

One emitter function owns both writes; call sites never write a log line
and a fact separately. The event's marker attribute follows the error
model's rule: namespaced in the owning namespace, valued by the primary
subject, boolean `true` only when subjectless.

## Implementation order

1. The emitter + event schema declarations + the committer (one flow proc
   arm beside the fault committer, same graph — no new graph).
2. Convert the operator verbs and cluster lifecycle transitions (start,
   stop, refork, publish, recovery) — the highest-value forensic seams.
3. The gauges: declare the first `:db/noHistory` attributes (model-row
   last-use gauges land with the model registry; proc/agent gauges here)
   and prove the no-history property.
4. The event feed render (both projections, declared producers) on the
   debug surface.
5. Log-line reference retrofit: every first-party log emission routes
   through the emitter or is classified foreign.

## Falsifiers

- A cluster start/stop/refork round-trip yields queryable event facts whose
  ids appear verbatim in the log file lines.
- `history` on a gauge attribute returns nothing after ten updates; the
  current value is correct.
- Killing the JVM mid-emission loses at most the in-flight event (channel
  loss free); no committed fact ever lacks its log line's referenced id...
  unless the log write raced the crash — the FACT is the authority and the
  log line is the courtesy, asserted in that direction only.
- The event feed renders on the debug page and in agent context through
  declared producers; no event falls to the generic value floor.
- Store growth from one day of normal operation with events on is measured
  and recorded before graduation.

## What not to build

- no log levels as config-gated fact admission (the moment you need the
  event is the moment it wasn't committed); the significance bar is
  design-time, per event seam;
- no `:event/type` or severity enum — attributes and namespaces carry it;
- no second emitter, logging library adoption, or structured-log file
  format beside the fact + referenced-line pair;
- no retention/compaction machinery in this slice — significant events are
  deliberately low-volume, and the measured store-growth falsifier is the
  check on that claim;
- no acknowledgement, read-marker, or notification state on events.
