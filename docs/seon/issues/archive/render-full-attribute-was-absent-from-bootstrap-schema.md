---
type: issue
status: resolved
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
schema` once per turn. `seon.client/agent-bootstrap-attrs` originally omitted
the attribute. After adding it, a clean restart exposed the deeper failure:
the pod never sent that explicit attribute set to the JVM initializer. The
writer derived native schema only from entity-map shapes in the compiled
program, so registered dataless scalar attributes remained uninstalled.

## Owner

The existing `seon.client/agent-bootstrap-attrs` cold-start schema, the typed
database initialization value, and the JVM writer's one initialization
transaction.

## Acceptance

- The bootstrap schema includes `:seon.render/full?` before the first prompt.
- A clean restart installs the missing schema in an existing cluster.
- A fresh real agent renders its transcript without the Datahike attribute
  error and completes an ordinary task.

## Resolution

Resolved by `68634002`. Focused writer and protocol gates prove populated
upgrade and convergence. Clean restart installed the attribute into the live
default database; subsequent fresh-agent transcripts rendered without the
Datahike error and completed normally.
