---
type: issue
status: open
tags: [agent, database, issue]
severity: friction
---

# Installed schema map misclassified as database error

## Problem

Agent namespace setup treated any truthy `:seon.error/message` lookup as a
database error. Datahike's installed schema is keyed by installed attributes,
so its ordinary schema map necessarily contains `:seon.error/message` after
that attribute is installed. Namespace setup returned the entire schema map in
place of the agent's require specs and crashed every fresh execution child.

## Evidence

Live agents `plain-chefs-do` and `root` both failed before model work. Persisted
core error `5977` points through `seon.eval/setup-agent-ns!` to
`seon.agent.home/home-requires-for`; its message is the installed
`:seon.error/message` schema entity and its data is the complete installed
schema map.

## Owner

`seon.agent.home/home-requires-for` owns the database read and must distinguish
the documented string-message error value from ordinary maps returned by
Datahike. `seon.eval/setup-agent-ns!` must enforce the same returned union at
its call boundary.

## Acceptance

- An installed schema containing the `:seon.error/message` attribute falls
  through to the configured or canonical home requires.
- Real string-message error values still propagate unchanged.
- Focused home and execution tests pass.
- Retrying the same live agent completes namespace setup without a core fault.
