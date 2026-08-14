---
type: issue
status: open
severity: blocker
tags: [issue, render, agent, class/n1, wave/strict-repl-display, wave/live-drive-render]
---

# Never splice a prose render inside a printed value

## Problem

A declared `:seon.render/ai` producer selected for a value NESTED inside
another value is emitted into the printed output raw — no quotes, no escaping,
embedded newlines and periods. One such splice makes the ENTIRE enclosing value
unreadable: a map that is otherwise perfect EDN stops being data because one of
its values is an English sentence where a value belongs.

This is not a display blemish. The agent cannot read the result back, cannot
`get` a key out of it, and cannot tell which characters are the value and which
are the renderer's voice.

## Evidence

Observed live 2026-08-14 in the Drive 1 stored capture facts
(`tmp/drive-1-root`, capture `9e7db417-…-context-536871318`,
`:seon.context.capture/prompt`). It occurs in **45 of 210 result positions**
across the six captures that carry a prompt.

Verbatim — nine keys of honest data, one prose splice, and the map never closes
correctly:

```text
my.agents.root=> (db/pull db (quote [*]) [:seon.maintenance.receipt/id "maintenance-receipt/[\"root/maintenance/compact\" #inst \"2026-08-09T03:00:00.000-00:00\"]"])
{:seon.maintenance.receipt/fire {:db/id 29995, :seon.schedule.fire/id "[\"root/maintenance/compact\" #inst \"2026-08-09T03:00:00.000-00:00\"]"},
  :seon.maintenance.receipt/task {:db/id 29994, :seon.schedule.task/id "root/maintenance/compact"},
  :seon.maintenance.receipt/started-at #inst "2026-08-14T11:24:27.953-00:00",
  :db/id 29997, :seon.maintenance.receipt/completed-at #inst "2026-08-14T11:24:32.038-00:00",
  :seon.maintenance.receipt/handler Restart the JVM to remove stale loaded Var seon.operator/collect!; it is absent from the published program graph.}
```

The most severe instance splices 400 characters of second-person TEACHING TEXT
into a maintenance record, where the ref's identity belongs, ending in `.,`
because the map separator had to follow the sentence's own period:

```text
  4, :seon.maintenance.request/agent You are agent root in namespace my.agents.root. Your opening is generated from live facts. You have 3 unread messages. 97 turns remain in this episode. This run exists because of [:seon.cluster.message/id "maintenance-error/…-your-run"].
Injected callables: help — Read the calling agent's live situation. dir — List the public names in namespace-name through Clojure's REPL macro. doc — Print documentation for symbol through Clojure's REPL macro.
Every run ends with my.run/complete or my.run/wait; an undisposed run is unfinished work.,
```

`:seon.maintenance.request/agent` is a REF. Its value at that position is
`{:db/id 29979, :seon.cluster.agent/id "root"}`. The same splice repeats on
`:seon.schedule.task/owner` and on every `:seon.maintenance.*/handler`, so the
identical 400-character paragraph is restated more than thirty times inside one
91 KB context.

The mechanism is two seams meeting:

- `seon.render/project-node*` (`src/seon/render.clj:445-495`) substitutes a
  declared producer's output for a sub-value at EVERY depth, not only at the
  root;
- the text sink appends an `/ai` fragment raw
  (`src/seon/print.cljc:107-112`):

```clojure
(-fragment [_ output value]
  (append-chunk! state
                 (if (= :seon.render/ai output)
                   value
                   (pr-str value))))
```

Full walk and counts:
[results-as-data audit](../../prds/context-generation/research/results-as-data-audit-2026-08-14.md).

## Owner

`seon.render`'s one selection/projection construction and the one
`seon.print` sink.

## Acceptance

A rendered value is readable data at every depth. An `/ai` projection is
selected only for a whole context BLOCK; below the root a nested position takes
the `:seon.render/form` projection, the identity ref, or `pr-str` — prose that
is genuinely the value is a QUOTED STRING. One regression prints a value whose
nested ref carries a declared `/ai` producer and asserts the complete output
reads back through the reader as one form with nothing left over.
