---
type: issue
status: open
severity: blocker
tags: [issue, agent, database]
---

# Pull run defaults from their owning agent attributes

## Problem

Opening a run asks Datahike to pull obsolete
`:seon.agent.run/default-turn-limit` and
`:seon.agent.run/default-deadline-ms` attributes even though the optional
overrides are owned and stored as `:seon.agent/default-*`. Datahike rejects the
unknown selector attribute before a run can open.

## Evidence

A real user message committed and entered the wake loop, then `open-run!`
returned `Bad entity attribute :seon.agent.run/default-turn-limit ... not
defined in current schema`. `seon.agent` already registers and stores both
agent attributes. `seon.agent.run` registers duplicate run-namespaced schema
keys, selects both spellings, and reads either spelling despite its own comment
that cluster defaults have no duplicate storage.

## Owner

`seon.agent.run/open-run!` and `effective-deadline-ms` own the two agent/config
pulls and policy fallback.

## Acceptance

- Run policy reads only the existing `:seon.agent/default-*` overrides and the
  config singleton's run policy.
- Duplicate run-namespaced default schema keys and branches are deleted.
- Focused run tests assert the exact pull selector, and a real inbound message
  opens a run.
