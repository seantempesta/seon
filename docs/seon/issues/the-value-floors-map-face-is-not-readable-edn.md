---
type: issue
status: open
severity: friction
tags: [issue, render, agent, class/n1, wave/strict-repl-display]
---

# Render a map as readable EDN, with its namespaces intact

## Problem

The value floor has a second map face that is not data: it drops the enclosing
braces, joins entries as `label ": " value`, and STRIPS the namespace from each
qualified key unless its short name collides. The agent cannot read the result
back, and it cannot query the attributes it was shown, because the attribute
names it was shown are not the attribute names.

Two different map faces therefore appear in one agent context — the EDN-ish one
and this one — which is a duplicate mechanism in the rendering path, not a
styling choice.

## Evidence

Observed live 2026-08-14 in the Drive 1 stored capture facts
(`tmp/drive-1-root`), in **15 of 210 result positions**. Verbatim:

```text
my.agents.root=> (db/pull db (quote [*]) [:seon.schedule.fire/id "[\"root/maintenance/compact\" #inst \"2026-08-09T03:00:00.000-00:00\"]"])
:seon.schedule.fire/id: "[\"root/maintenance/compact\" #inst \"2026-08-09T03:00:00.000-00:00\"]", task: {:db/id 29994, :seon.schedule.task/id "root/maintenance/compact"}, nominal-at: #inst "2026-08-09T03:00:00.000-00:00", observed-at: #inst "2026-08-14T11:24:27.953-00:00", :db/id: 29995
```

The live value needed no re-facing at all — it was already ordinary readable
data:

```clojure
{:db/id 29995,
 :seon.schedule.fire/id "[\"root/maintenance/compact\" #inst \"2026-08-09T03:00:00.000-00:00\"]",
 :seon.schedule.fire/nominal-at #inst "2026-08-09T03:00:00.000-00:00",
 :seon.schedule.fire/observed-at #inst "2026-08-14T11:24:27.953-00:00",
 :seon.schedule.fire/task #:db{:id 29994}}
```

Three defects in one face: `nominal-at` no longer names
`:seon.schedule.fire/nominal-at`, so the agent cannot query it back; `:db/id:`
acquires a second colon; the braces are gone, so the whole thing is not one
value.

The seams are `seon.render.value/attribute-label`
(`src/seon/render/value.clj:365-372`), which calls `(name attribute)` on any
qualified keyword whose short name is unique, and `components-text`
(`src/seon/render/value.clj:393-398`):

```clojure
(str/join ", " (map (fn [{:seon.render.value/keys [label value elision]}]
                      (or elision (str label ": " value)))
                    components))
```

Full walk and counts:
[results-as-data audit](../../prds/context-generation/research/results-as-data-audit-2026-08-14.md).

## Owner

`seon.render.value` owns the floor; `seon.print/fit` owns bounded output.

## Acceptance

A map renders as readable EDN with its qualified attributes intact, through the
one `seon.print/fit` owner, and the second map face is deleted rather than
kept beside it. One regression renders a map of qualified attributes and
asserts the output reads back as an equal value.
