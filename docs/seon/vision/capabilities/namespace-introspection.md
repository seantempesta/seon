---
type: capability
status: partial
tags: [vision, flow]
---
# Namespace Introspection UI

Every loaded `seon.*` namespace has a web page showing its functions, vars, atoms, and requires -- discovered at runtime, not authored manually. Content negotiation serves HTML for humans and structured text for agents. Dynamic namespaces push updates via context watches.

## What Exists

- `GET /ns/{namespace}` works for all loaded `seon.*` namespaces
- Runtime introspection discovers functions, vars, atoms, requires
- Content negotiation: html, ai, raw formats
- Dynamic namespaces push via ctx watches

## Gaps

- No expand/collapse for nested data structures
- Dead code remains (`ui/viewer.clj`, `web/namespace.clj`)
- Two rendering systems overlap for namespace pages

## Related

- Components: [[components/web-layer]], [[components/namespace-lifecycle]]
- PRDs: [[prds/namespace-ui/prd]]
- Issues: [[orchestrator/issues/dead-web-namespace-viewer]], [[orchestrator/issues/overlap-three-rendering]]
