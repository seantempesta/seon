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

## Ref expansion recurrence (2026-09-08)

The same normalization also discarded the names acquired through nested refs.
The armed render-coverage harness observed eight missing effect-owner/turn
labels and one cluster configuration mismatch. An evaluation's namespace name
was lost by the same conversion. Transaction shape is appropriate for schema
selection, but is not the value the renderer was asked to present.

The invocation now carries the original pulled value, with render custody;
selection retains its existing transaction-shape check. The evaluation pair
is declared on the expanded query shape and accepts the value envelope,
including its Datahike `:t`. The debug page presents each evaluation through
that pair rather than printing a vector of its database attributes. The
ordinary-proc regression checks a real SCI evaluation's rendered source,
namespace, and card, with no inline evidence or collection depth labels.
