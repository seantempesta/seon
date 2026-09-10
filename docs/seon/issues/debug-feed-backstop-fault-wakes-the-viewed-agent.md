---
type: issue
status: open
severity: friction
tags: [render, feed, faults, steward, turn, live-test]
created: 2026-09-10
---

# A debug-feed backstop fault is delivered to the VIEWED agent and wakes a paid turn

## Observed (default, 2026-09-10 14:56, first live provider run)

Error `9c931b6e-8128-4552-8290-abb5ca69e00d`, kind
`:seon.await/backstop-fired`: the debug tab's `:seon.render.web/feed-delta`
for `[:seon.render.web/debug-tab {… :seon.agent/id "juniper" …}]` never
arrived within 30,000 ms (three lanes and two gates were loading the JVM).
The fault row carries `:seon.error/agent juniper`, so it reached Juniper
as "Message from outside this cluster: The feed failed with
:seon.await/backstop-fired. Inspect error 9c931b6e… the proc survived and
no work was re-executed." and opened turn `3ded5c0bc8a4` — a paid
provider turn (the model handled it gracefully and went on with its plan).

## Why it is wrong

The debug tab request names the agent being VIEWED, not the agent
responsible for the feed. A render-feed fault belongs to the steward of
`seon.render.web` (root by fully qualified namespace routing, chart §12),
never to the subject of the page. Attribution by "whichever agent id is in
the request map" is the pre-read/mirror disease: the fault committer
should derive the owner from the faulting proc's namespace.

Secondary: the feed delta itself took > 30 s under load — the same class
as `root-page-walks-every-ref-attribute-and-schedule-fires-keep-it-cold.md`.

## Wanted

- Fault routing derives the steward from the faulting code's namespace;
  a request's `:seon.agent/id` is never the fault owner.
- A platform fault never opens a paid turn on an unrelated agent; the
  live §17 run's turns are all its own.
