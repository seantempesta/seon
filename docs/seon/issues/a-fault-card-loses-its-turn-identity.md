---
type: issue
status: open
severity: defect
created: 2026-09-26
tags: [issue, error, render, fault-card]
---

# A fault card loses its turn identity

`seon.error/render-html` (`src/seon/error.clj:1850-1896`) renders from `rendered-error-value`, which
re-pulls the error through `observation-selector`. That selector keeps peer references as references, so
the latest occurrence's `:seon.error.occurrence/turn` arrives as `{:db/id n}`. `turn-ref` then falls to
the entity id, and the card says "Turn: identity unavailable" for a fault whose turn exists.

## Evidence (2026-09-26, default pid 84287, isolated branch)

Two faults for agent `juno`, the older one on turn `run-1`: the caller's pull carries
`#:seon.turn{:id "run-1"}`, while the card's HTML is `<a href="/data?entity=33332">Turn: identity unavailable</a>`.
Red: `seon.turn-test/fault-unit-lists-agent-faults-newest-first-with-run-links` (turn_test.clj:2033).

## Proposed fix (src/seon/error.clj, held by the lost-error-seams lane)

Resolve an entity-id turn through the unit's database:

```clojure
turn-ref (if (map? turn)
           (if-let [id (or (:seon.turn/id turn)
                           (some-> (:seon.db/db unit) (db/pull [:seon.turn/id] (:db/id turn)) :seon.turn/id))]
             [:seon.turn/id id]
             (:db/id turn))
           turn)
```
