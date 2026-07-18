---
type: issue
status: open
severity: blocker
tags: [issue, agent, web]
---

# Pass a valid config singleton to final agent evidence

## Problem

The model-transport evidence path pulls only
`:seon.config.render/database-edn-cap`, then passes that partial map to
`config/database-edn-cap`. The accessor correctly requires the config
singleton shape, whose one required field is `:seon.config/id`.

## Evidence

`POST /agents/run` reached final evidence after a real turn, then Malli rejected
`database-edn-cap` input `{:seon.config.render/database-edn-cap 16384}` for its
missing `:seon.config/id`.

## Owner

`seon.web.serve/project-model-transport-evidence` owns the narrow config pull
and its call to the existing config accessor.

## Acceptance

- The pull includes the singleton identity and the one required cap.
- A focused test asserts the exact pull selector.
- Final agent evidence returns an ordinary response instead of throwing.
