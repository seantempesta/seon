---
type: component
status: draft
tags: [component, flow]
---

# Planned: Context Flow-First Model

> Extracted from [[components/context]] -- this describes planned work, not current behavior.

## Overview

All three ctx atom watches (persistence, SSE broadcast, client-targeted push) will be replaced by flow outputs. The atom becomes a read cache; flow step state is source of truth.

## Design

- Persistence debouncing uses `sliding-buffer 1` on the channel to the writer step -- writer I/O provides natural backpressure.
- SSE push uses `async/mult` on a flow out-port.
- Only one watch remains: `::flow-sync` injects into flow when external code changes the atom.

## References

- `prds/unified-namespace-flow/design` -- full PRD
- `prds/unified-namespace-flow/research/ctx-flow-sync` -- research on sync mechanism
