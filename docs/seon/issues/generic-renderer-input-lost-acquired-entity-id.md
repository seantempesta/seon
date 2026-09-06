---
type: issue
status: resolved
severity: blocker
tags: [issue, render]
---

# Preserve acquired entity identity in generic renderer input

## Problem

Generic render call preparation normalized a pulled entity through
`seon.render.value/transacted`, which deliberately removes the root `:db/id`.
Render functions that query related facts from that entity therefore received
its attributes without its database identity. The paired namespace inspection
rendered no function rows because `seon.render.ns/render-data` queries them from
the missing id.

## Resolution (2026-09-06)

`seon.render/producer-argument` restores the acquired root `:db/id` after value
normalization. Nested values retain the existing transacted normalization. A
focused generic-invocation regression defines a renderer that consumes the id
and proves the actual acquired entity identity reaches it.

Live evidence on `lab-browser-0906` showed the acquired `seon.flow` row carried
`:db/id 7348`, its producer argument omitted that key, and the direct function
row query returned 50 rows when supplied `7348`. After the fix, the AI output
again included `(defn start-graph! ...)` and the HTML projection included
`seon.flow/start-graph!`.
