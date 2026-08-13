---
type: issue
status: resolved
severity: blocker
tags: [issue, render, schema]
---

# The render value floor refuses any map with unqualified keys

Found 2026-08-10 during the model-authoring drive
([drive account](../../prds/sci-execution-runtime/research/model-authoring-drive-2026-08-10.md)),
cluster `default`, pid 91415, JVM booted `2026-08-10T21:25:24Z`.

## What happens

`seon.render/render-ai` renders a qualified-key map and a vector, and refuses
an ordinary unqualified-key map with a contract violation from the floor
itself. Verbatim, one probe, one live cluster (`mcp__seon__eval_clj`, jvm mode,
request carrying the cluster's own ctx, db, caps, time limit, and
`:seon.render.call/id`):

```clojure
{:unqualified-map "seon.render.value/prepare violated its contract (invalid-input): [{:b [{:value :b, :message \"should be a qualified keyword\"}], :a [{:value :a, :message \"should be a qualified keyword\"}]}]"
 :qualified-map   "#:my{:a 1, :b 2}"
 :vector          "[1 2 3]"
 :nested          "seon.render.value/prepare violated its contract (invalid-input): [{:rows [{:value :rows, :message \"should be a qualified keyword\"}]}]"}
```

`{:a 1 :b 2}` and `{:rows [{:a 1}]}` are the ordinary shapes agents compute and
return all day — including the return value of the very function the model
authored on this drive (`my.agents.root/token-pressure` returns
`{:turns 2, :prompt-total 400, :completion-total 100, :ratio 0.25}`). Any such
value that reaches the floor without a matching producer renders as a
validator dump instead of as data.

## Mechanism (the class, not the instance)

`seon.render/render-argument` merges a map value's OWN keys into the render
unit at top level:

- `src/seon/render.clj:106-107` — `(if (map? value) (assoc (merge value context) :seon.render/value value) …)`
- `resources/seon/schemas/seon.render.edn:80-87` — `:seon.render/unit` is
  `[:map-of :qualified-keyword …]`
- `src/seon/render/value.clj:213-220` — `prepare` declares `:seon.render/unit`
  as its input, so instrumentation refuses the merged map before the floor
  runs.

So the unit doubles as (a) the render request's own qualified render keys and
(b) an arbitrary user value's keys. Those two are not the same kind of map, and
the second cannot satisfy the first's declaration. The failure class is
structural: a value whose keys are unqualified is unrepresentable as a unit,
and the merge makes every such value construct one.

## Wanted behaviour

The rendered value travels only under `:seon.render/value`; the unit stays a
map of qualified render keys and nothing else. Producer selection that wants to
match a value's own attributes matches against `:seon.render/value` (entity
maps already carry qualified attributes there), so nothing needs the merge.
With the merge gone, "a value's key collided with the unit's contract" cannot
be written.

## Acceptance

One regression that renders `{:a 1 :b 2}` and `{:rows [{:a 1}]}` through
`seon.render/render-ai` at the floor and asserts ordinary printed data, plus
the existing entity-producer selection tests still selecting by attribute.

## Not yet investigated

Whether `:seon.render/html` has the same refusal (probe only covered
`:seon.render/ai`), and whether any live surface currently reaches the floor
with an unqualified-key map — the namespace page for `my.agents.root` rendered
clean (200, 339,914 bytes, no contract-violation text) on this drive.

## Resolution

Resolved by `4bc8104d8`. The floor invocation is now always a qualified render
unit whose arbitrary value exists only at `:seon.render/value`; declared
entity producers retain their established qualified-attribute argument.
`floor-totality-uses-one-prepared-value` covers `{:a 1 :b 2}` and
`{:rows [{:a 1}]}` in both AI and HTML while the existing declared-producer
selection regressions remain green.
