---
type: issue
status: open
severity: blocker
tags: [issue, render, agent, class/p1, wave/live-drive-render, wave/strict-repl-display]
---

# A displayed run entry must name the run its own form pulled

## Problem

In a rendered session, a `db/pull` of one run can display a value naming a
DIFFERENT run. The form and its answer contradict each other on the page the
agent reads and the human reviews. An agent reasoning from that entry
believes the wrong run completed.

## Evidence

Observed live 2026-08-14 on the Drive 1 attempt-5 cluster,
`http://127.0.0.1:55156/agent/drive-one-agent-attempt-5/debug`, in the
`:seon.render/ai` pane. Verbatim, as the last run pull in the transcript:

```text
my.agents.drive-one-agent-attempt-5=> (db/pull db (quote [*]) [:seon.cluster.run/id "bootstrap:drive-one-agent-attempt-5"])
Run a887d305-c8ae-4b6e-842f-43287f7f7496, opened #inst "2026-08-14T11:25:09.152-00:00". It completed.
```

The lookup names `bootstrap:drive-one-agent-attempt-5`; the rendered value
names `a887d305-c8ae-4b6e-842f-43287f7f7496`. The instant and the disposition
DO belong to the bootstrap run, so only the identity is substituted.

The FIRST occurrence of the identical form, at the head of the same
transcript, renders the correct identity:

```text
my.agents.drive-one-agent-attempt-5=> (db/pull db (quote [*]) [:seon.cluster.run/id "bootstrap:drive-one-agent-attempt-5"])
Run bootstrap:drive-one-agent-attempt-5, opened #inst "2026-08-14T11:25:09.152-00:00". It is running now, held by 69568-1786706658408.
```

The database is NOT at fault. A live pull against the drive root returns both
runs with their own identities:

```clojure
[{:seon.cluster.run/id "bootstrap:drive-one-agent-attempt-5"
  :seon.cluster.run/opened-at #inst "2026-08-14T11:25:09Z"
  :seon.cluster.run/closed-at #inst "2026-08-14T11:28:43Z"}
 {:seon.cluster.run/id "a887d305-c8ae-4b6e-842f-43287f7f7496"
  :seon.cluster.run/opened-at #inst "2026-08-14T11:28:56Z"
  :seon.cluster.run/closed-at #inst "2026-08-14T11:28:56Z"}]
```

`seon.cluster.run/render-ai` (`src/seon/cluster/run.clj:1885-1966`) reads
`id` from its unit — `(get unit ::id)` — and interpolates it at `:1965`, so
the substitution happens BEFORE the renderer: the unit handed to it, or the
captured entry that stored the text, carries the current run's identity
rather than the pulled entity's. Naming the exact upstream seam needs one
more probe; this note records the confirmed observable.

Full walk:
[ui-verification-2026-08-14](../../prds/sci-execution-runtime/research/ui-verification-2026-08-14.md).

## Owner

The seam that builds the render unit for a pulled run entity in the captured
history — `seon.render.walk` / the capture path feeding
`seon.cluster.run/render-ai`. The renderer itself reads its unit correctly.

## Acceptance

A `db/pull` of a run by identity renders a value naming exactly that run,
whatever run is current when the entry is rendered or re-rendered. A
recurring proof renders two runs' entries within one session while a third
run is open, and asserts each entry's identity matches its own form's lookup.
