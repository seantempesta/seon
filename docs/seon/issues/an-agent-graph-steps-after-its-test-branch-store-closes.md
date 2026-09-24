---
type: issue
status: open
severity: high
created: 2026-09-24
tags: [issue, flow, test, branch, lifecycle]
---

# An agent graph steps after its test branch's store closes

## Problem

On default (pid 70372), agent graphs on test fixture branches keep running
`seon.turn/step` after the branch's store channel is closed. Their reads then
refuse with `java.nio.channels.ClosedChannelException`, raised at
`sun.nio.ch.FileLockImpl.release`.

The call chain is `seon.db/q` inside `seon.turn/episode-runs`, reached from
`opening-deferred?` and `next-agent-work`. The branches are already gone from
`d/branches`.

The law this breaks is "stop graphs before retracting their facts; release
resources even if setup/cleanup fails".

## Evidence

- **Occurrence `751c1496992d`** on `:agent-518647aa0a67`, at 17:29:32Z.
  - Before eb14a2f22 it was recorded as a `seon.turn/turn` input contract
    violation at `[:seon.turn.work/next :seon.turn.work/situation]`.
  - Its explanation's actual value is the `seon.db/q` read refusal described
    above.
- **Signature `d3ef61b6…`** on `:agent-96b7917bed55`, at 17:41:44Z.
  - After eb14a2f22 it records as `seon.db/q` "Datahike refused the database
    read." from `seon.turn/step`.
  - Its whole evidence is in blob `a11ac961…`.
- Both happened while hook-driven test runs on default were releasing
  `with-database` branches. One of those runs reported a member thread that
  "remains live and keeps its branch".

## Wanted

Release of a fixture or agent branch stops and joins that branch's agent
graph before it closes the store. A graph never derives work over a closed
store.
