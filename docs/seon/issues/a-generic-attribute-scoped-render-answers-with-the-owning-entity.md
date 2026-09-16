---
type: issue
status: open
severity: friction
tags: [issue, render, wave/render-producers]
---

# A generic attribute-scoped render answers with the owning ENTITY, not the attribute

## Problem

After the no-fallback ruling (owner, 2026-09-17, decision 9 option 1) a render
request scoped to an attribute that declares no render pair resolves to the
generic printer instead of borrowing the owning entity's declared form. That is
the ruled behaviour and it is now pinned by
`seon.render.history-test/form-is-the-third-output-of-the-existing-selection-chain`.

The generic printer, however, is handed the WHOLE OWNING ENTITY, not the
attribute's value, so the honest generic answer still does not name the
attribute that was asked about. Asking about one attribute produces a reading of
the entity — accurate, but not an answer to the question, and unreadable at the
size of a real entity.

## Evidence

In process on `default` pid 53320, live database, 2026-09-16, with the ruling in
place (`src/seon/render.clj`, `attribute-scoped?` / `declared-producer` /
`schema-stage`):

| request | selected producer | output |
|---|---|---|
| Juniper's agent entity, `:seon.render.walk/attribute :seon.agent/id`, `:seon.render/ai` | `seon.render.value/render-ai` | the entire agent entity map, ~5 KB, plan steps and turn replies included |
| the `fixture.history` namespace, `:seon.render.walk/attribute :seon.ns/requires`, `:seon.render/form` | `seon.render/render-form` | `(seon.db/pull '[*] [:seon.ns/name fixture.history])` |

The seam is `seon.render/render-invocation-argument`
(`src/seon/render.clj:380-412`): when the selected producer is NOT the
attribute's declared pair it hands `render-argument`, which carries
`:seon.render/value` = the owning entity. `seon.render/render-form`
(`src/seon/render.clj:1296`) then spells the entity's identity read.

Before the ruling the same request borrowed the entity's declared pair and
produced a LIE, which is why the ruling is right: rendering Juniper's agent
entity scoped to `:seon.agent/id` through
`seon.cluster.agent/render-identity-ai` emitted

```clojure
(seon.db/pull
  '[:seon.agent/id {:seon.agent/namespace [:seon.ns/name {:seon.ns/steward [:seon.agent/id]}]}]
  [:seon.agent/id nil])
```

— a read for the agent whose id is `nil`. The generic answer is honest; it is
simply about the wrong subject.

## What would fix it

An attribute-scoped request that falls to the floor should hand the floor the
ATTRIBUTE's value, and its form should name the attribute — `(seon.db/pull
'[:seon.ns/requires] [:seon.ns/name fixture.history])` rather than `'[*]`. Both
live in `render-invocation-argument` and `render-form`; changing them moves the
floor for every attribute-scoped render, so it needs its own slice and its own
gate rather than a drive-by edit inside the no-fallback change.

`UGLY OUTPUT IS A DEFECT` (AGENTS.md §2.4) is the reason this is filed rather
than absorbed: the generic AI projection of a whole agent entity is what an
agent now reads when it asks about one of that agent's attributes.

## Owned by

The render selection owner.
