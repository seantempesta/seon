---
type: issue
status: open
severity: valid
tags: [issue, web, render, data]
---

# The root agent facts link drills a value that has no entities

## Problem

The root view's agent list offers two links per agent: the agent's own page and
a `/data` drill "cursored at that agent's entity". The second link is dead by
construction. `/data` drills the canonical database ATTRIBUTES — the schema —
while the link's cursor names an ENTITY path, so following it always lands on a
refusal instead of the agent's facts.

The refusal itself is correct and legible. The link that produces it is the
defect: a page offers an affordance it can never satisfy, and the drill's whole
economy ("every bespoke inspector page we do not write is one that cannot drift
from the facts") depends on that link working.

## Evidence

`src/seon/render/root.clj`, `agents-html`, builds the cursor from an entity
lookup path:

```clojure
(str "/data?path="
     (java.net.URLEncoder/encode (pr-str [:seon.cluster.agent/id id]) "UTF-8")
     "&offset=0")
```

`src/seon/render/web.clj`, the `/data` route, drills a different value
entirely:

```clojure
(data/drill-html
 {:seon.render/value (schema/canonical-database-attributes) ...})
```

Live, on the default cluster (2026-07-28), following the root page's `facts`
link for `root` renders the error card:

```
:seon.render.data/no-such-path
There is nothing at :seon.cluster.agent/id in this value.
```

Screenshot: `tmp/ui-wave/05-data-drill-entity.png`.

The comment above the route says "the drill over the CLUSTER's own facts",
which is the intent; the value passed is the schema, which is not.

## Owner

The `/data` route in `src/seon/render/web.clj` decides what the drill's root
value is, and `agents-html` in `src/seon/render/root.clj` decides what a cursor
into it means. One of the two must move, and the choice is a design decision
rather than a repair: either the drill's root value gains the cluster's
entities (which makes the existing link correct and costs a query whose shape
and bound need deciding), or the agent list links at a cursor that exists in
the schema value it actually drills.

## Acceptance

Following the `facts` link for any agent on the root page renders that agent's
own facts in the drill, at a shareable cursor, with the windowed paging the
drill already provides — proven live against a cluster with more than one
agent, not only in a fixture.
