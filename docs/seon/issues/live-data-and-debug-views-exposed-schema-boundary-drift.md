---
type: issue
status: active
severity: blocker
tags: [issue, web, database, schema]
---

# Restore the maintained data and debug views

## Problem

Real-browser verification found two source-boundary mismatches hidden by the
existing unit gates:

- `/data` passed `:seon.db/index-limit` to `seon.db/index-page`, whose public
  request and JVM Datahike index-page protocol both name the field
  `:seon.db/limit`.
- `/agent/root/debug` pulled `:seon.ai/agent-thinking`, but the six established
  per-agent AI configuration attributes were standalone Malli schemas rather
  than members of a map marked `:seon.db/entity`. Database initialization
  therefore had no declaration telling it to install those attributes.
- After that delta installed, the same live debug path exposed
  `:seon.agent.ctx/cache-breakpoint`. The existing agent entity schema omitted
  its context configuration attributes even though prompt acquisition and
  transcript rendering consume them from the agent entity.
- The repaired index request returned rows, but the view read
  `:seon.db/datoms` instead of the producer's established
  `:datahike.index-page/datoms` field.

## Acceptance

- The database view uses the public `seon.db/index-page` request fields and
  renders a bounded AEVT page.
- The existing `seon.ai/agent-config-pull-pattern` and the Datahike-installed
  agent configuration attributes are the same set.
- The agent entity declares the established context configuration attributes
  consumed by prompt and transcript rendering.
- The data view consumes Datahike's `:datahike.index-page/datoms` result
  directly.
- Restarting an existing database installs the missing schema delta without
  retransacting converged program or initial data.
- `/data` and `/agent/root/debug` render through their Datastar feeds without a
  visible error or browser console error.

## Evidence

- The in-app browser rendered `:malli.core/invalid-input` on `/data` and
  Datahike's `Bad entity attribute :seon.ai/agent-thinking` on the debug view.
- A live pod probe showed the Malli schema registered while the installed
  Datahike schema did not contain the attribute.
- Focused regressions currently pass for the AI entity declaration and the
  database view's index-page request. Live restart proof remains pending.
