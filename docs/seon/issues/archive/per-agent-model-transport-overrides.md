---
type: issue
status: resolved
severity: friction
tags: [issue, agent, architecture]
---

# Complete per-agent model transport overrides

## Problem

Agent entities could override provider, model, temperature, output cap, and
thinking, but endpoint, credential-variable name, adapter timeout,
DiffusionGemma backend, and extra request body remained global. Two agents
using different OpenAI-compatible gateways therefore could not derive complete
independent request configurations in one cluster. The manifest also rejected
the existing per-agent model attributes despite documentation describing
config-driven routing.

## Owner

The one `seon.ai/resolved-config-from-rows` precedence chain and the
`:seon.ai/agent-config` supplemental entity schema.

## Acceptance

- Every non-secret provider field has an optional agent-entity override.
- Absent or `:inherit` values continue through the global row and shipped
  defaults.
- Credential attributes store only environment-variable names.
- Ordinary, root, and per-mint context config accepts the same logical values.
- Agent birth commits routing facts atomically with identity and context.
- Named sparse model variants can be selected by `create!`, `mint!`, `start!`,
  and `delegate!` without exposing provider details to the calling agent.
- Two compatible-gateway agents resolve different endpoints and credentials
  without changing the cluster default.

## Resolution

Resolved by `4129738a`. The existing overlay now includes timeout, base URL,
credential-variable name, DiffusionGemma backend, and extra-body EDN. The
execution child and web evidence paths already consumed the shared dynamic pull
pattern, so no second dispatcher or adapter path was added.

The follow-up named-variant boundary stores sparse maps once on the
configuration singleton and accepts a request-only
`:seon.config/model-variant` selector on every agent-birth entry point. The
selected ordinary `:seon.ai/agent-*` values are copied into the atomic birth
transaction; the selector is not stored. Unknown names allocate nothing, stale
database retries resolve the name again from the reacquired configuration, and
a request cannot silently retune an existing namespace resident.

Focused proof passed 125 tests/558 assertions across config resolution, atomic
agent birth, provider routing, all provider adapters, execution acquisition,
and web evidence. A live default-cluster probe showed Kimi resolving the
Moonshot endpoint and `MOONSHOT_API_KEY`, Muse resolving the Meta endpoint and
`META_API_KEY`, and the installed database schema carrying every new attribute
as cardinality one.

Named-variant focused proof passed 39 tests/206 assertions across configuration
validation, the EDN-slot database facet, atomic copied birth attributes,
unknown-selector rejection, and selector re-resolution across stale retries.
