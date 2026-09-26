---
type: issue
status: open
severity: friction
created: 2026-09-26
tags: [issue, dev-mcp, blob-storage]
---

# get_value on a non-artifact blob throws instead of refusing

`bin/test-check` prints oversized failure values as `(seon.blob/get "<digest>")`. Passing such a digest to MCP
`get_value` reaches `seon.cluster/mcp-get-value` → `seon.render.value/read-artifact` (`cluster.clj:502`), whose
armed output contract refuses the plain EDN blob (no `:seon.sci.admit/print-node`). The tool answers an 82 KB
whole Throwable->map with 32 raw frames, rather than a declared refusal naming the digest and the expected
artifact shape.

## Evidence (default, 2026-09-26 ~02:53Z)

`get_value` digest `2397c2de…` (render-coverage-test's seed refusal): `read-artifact refused return value at
[:seon.sci.admit/print-node] … Called from seon.cluster (cluster.clj:502)`. The same blob reads with
`(clojure.edn/read-string {:default tagged-literal} (seon.blob/get conn digest))`.
