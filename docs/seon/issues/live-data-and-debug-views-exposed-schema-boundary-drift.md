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

## Acceptance

- The database view uses the public `seon.db/index-page` request fields and
  renders a bounded AEVT page.
- The existing `seon.ai/agent-config-pull-pattern` and the Datahike-installed
  agent configuration attributes are the same set.
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
