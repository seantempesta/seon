---
type: issue
status: open
severity: friction
tags: [issue, agent, capability]
---

# Root context replaces inherited capability requirements

## Problem

Root's configured `:seon.eval/home-requires` replaces the complete ordinary
agent vector. Root therefore loses ordinary blob, shell, and web capabilities,
and a downstream manifest's product additions never enter root's home
namespace. ACME separately copies the whole ordinary vector to add fixture
namespaces, creating another drift point.

The function menu and typeahead blocks are also enabled in ACME's standing
context even though they are experimental surfaces rather than default agent
capabilities.

## Owner

`seon.config` owns manifest composition and the root specialization of the
database-backed agent context. Namespace identity in a home require spec is
the merge identity; no role registry or renderer exception is needed.

## Acceptance

- A sparse downstream manifest adds or refines requires by namespace identity
  without copying the base vector.
- Root inherits the resolved ordinary/downstream vector and adds only its root
  namespaces.
- The normal root prompt contains one concise responsibility block and the
  complete ordinary plus root namespace cards.
- ACME requires `acme.brand` and `acme.widget`, not its helper/notes fixtures.
- Function-menu and typeahead blocks remain absent unless a named experiment
  selects them.
- Focused config tests and a fresh-agent live ACME read prove the persisted
  require edges and rendered namespace set.

## Evidence

The source now resolves sparse manifest and root requires through one additive
namespace-identity merge. ACME declares only its product additions and inherits
the ordinary context tree. The combined config/context/render/lifecycle gate
passes 106 tests and 530 assertions with zero failures or errors. A frozen-db
render regression proves that changing one worker purpose changes only root's
late `:canvas` block; the seven earlier root blocks remain byte-identical. Live
persisted-edge and prompt proof waits for the next coordinated ACME rebuild
after the runtime lane releases the source checkpoint.
