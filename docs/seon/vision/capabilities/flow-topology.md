---
type: capability
status: complete
tags: [vision, flow]
---
# Observable Flow Topology

All cross-boundary calls route through a single topology built on core.async flow. Database writes, REPL evals, and inter-namespace messages all follow the same pattern: register promise, inject, step, reply-route, deliver. The topology is observable -- every message has a path through the system that can be traced.

## What Exists

- `topology/request!` is the universal entry point for cross-boundary calls
- Infrastructure flow: writer, reader, REPL eval, reply-router
- Per-agent namespace flows with isolated step functions
- Nippy wire protocol, sync barrier at startup, cycle detection
- Promise-register -> inject -> step-fn -> reply-router -> deliver pattern

## Gaps

None.

## Related

- Components: [[components/flow-topology]]
- PRDs: [[prds/unified-flow/prd]], [[prds/flow-datalevin-writer/prd]]
