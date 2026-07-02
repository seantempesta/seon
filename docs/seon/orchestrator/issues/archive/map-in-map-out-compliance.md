---
type: issue
status: resolved
severity: friction
milestone: M2
tags: [issue, schema]
---
# Map-In Map-Out Compliance

## Status: CLOSED — premise reversed by the 2026-06-08 rule change

This issue's premise ("convert every public fn to map-in/map-out") was
**reversed** by the rule loosening on 2026-06-08. Positional public functions
are now a first-class, sanctioned shape **provided every slot is named and
specced via Malli `:catn`**. The goal is no longer "all map-in" — it is "every
public fn fully specs and validates its args and return." Many functions
previously flagged here are already compliant under the new rule.

See `docs/prds/agent-runtime/research/map-in-rule-audit-2026-06-08.md` (the
authoritative spec) and the updated `docs/conventions.md` "Public Function
Pattern" section.

## Original problem (historical)

Gemini review consistently flags functions using positional args in public APIs. The convention is that every public function takes one map and returns one map, with all keys namespaced. Many public functions still use positional arguments, making them inconsistent with the codebase convention and harder for agents to discover and use.

## What replaced this

- The compliance checker (`seon.dev.compliance`) now checks **spec
  completeness** (every arg + return specced, no bare/`:any`), not arg shape.
  The old `:no-map-in` violation is gone.
- Positional public fns with a complete `:catn`/`:=>` schema are compliant.
- The only remaining requirement: no unspecced or bare-keyword arguments.

## Related

- [[components/code-graph]]
