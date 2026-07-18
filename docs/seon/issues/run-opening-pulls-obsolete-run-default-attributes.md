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

After those obsolete attributes were removed, the real restart drive exposed
the other half of the selector contract: an existing agent without either
optional override pulls as nil when the selector contains no identifying
attribute. The committed-work read had just found that same entity. The policy
read must include `:db/id` so entity existence is independent of optional
configuration.

## Owner

`seon.agent.run/open-run!` and `effective-deadline-ms` own the two agent/config
pulls and policy fallback.

## Acceptance

- Run policy reads only the existing `:seon.agent/default-*` overrides and the
  config singleton's run policy.
- The agent pull includes `:db/id`, and an agent with no overrides uses the
  cluster policy rather than being reported missing.
- Duplicate run-namespaced default schema keys and branches are deleted.
- Focused run tests assert the exact pull selector, and a real inbound message
  opens a run.
