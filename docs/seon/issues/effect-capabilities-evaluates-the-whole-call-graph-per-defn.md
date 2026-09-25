---
type: issue
status: open
severity: platform
created: 2026-09-25
tags: [issue, effect, test-accretion, performance, agent-platform]
---

# effect/capabilities evaluates the whole call graph per defn

`seon.effect/capabilities` (src/seon/effect.clj:153-170) runs a recursive `reachable` rule
query. `seon.test.accretion/candidate-capabilities` (accretion.clj:98) calls it from
`auto-check` (accretion.clj:171) for every contracted `defn` an agent evaluates.

Measured on default (pid 23282, 2026-09-25; 4926 function rows, 65,693 `:seon.fn/calls`
edges):

| call | time | notes |
|---|---|---|
| `(seon.effect/capabilities db 'seon.turn/turn)`, first | 3008 ms | |
| the same call, second | 0.37 ms | query-cache hit on the same database value |
| `(seon.effect/capabilities db 'clojure.core/inc)` | 3073 ms | a leaf, so the cost does not depend on the root |

Datalog derives the whole recursive relation before it binds `?root`. Every new database
value, which in a turn means every settled form, pays about 3 s. Thread samples of one
batched turn call pass put about 3 s of 16 s here.

Cost target: proportional to the root's reach, not the program. Walk `:seon.fn/calls` from
the root, as `render-program-evidence` does (render.clj:783), or key a memo by the reached
rows' digests.
