---
type: issue
status: open
severity: blocker
tags: [issue, database, pod, web]
---

# Install the transcript render attribute during cold start

## Problem

A fresh agent's first transcript prompt pulls `:seon.render/full?`, but the
pod's explicit bootstrap schema omitted that registered attribute. Datahike
therefore rejected the pull before the agent could see its transcript or
complete ordinary work.

## Evidence

`seon.render` registers `:seon.render/full?` and
`seon.agent.ctx.transcript/eval-transcript-data` pulls it. A clean runtime
reported `Bad entity attribute :seon.render/full? ... not defined in current
schema` once per turn. `seon.client/agent-bootstrap-attrs`, which supplies the
cold-start schema, contained only the render slot attributes.

## Owner

The existing `seon.client/agent-bootstrap-attrs` cold-start schema and the
transcript prompt that consumes it.

## Acceptance

- The bootstrap schema includes `:seon.render/full?` before the first prompt.
- A clean restart installs the missing schema in an existing cluster.
- A fresh real agent renders its transcript without the Datahike attribute
  error and completes an ordinary task.
