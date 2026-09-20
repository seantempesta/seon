---
type: issue
status: open
severity: friction
tags: [issue, operator, schema, wave/dev-tooling-face-hygiene]
---

# Runtime health rejects an error signature with no occurrences

On 2026-09-16, MCP runtime status on default (PID 7595) reported the
process alive but failed its health projection with a contract violation:
`seon.problems/problems` returned `:seon.problems/occurrences` below its
declared minimum. The explanation said “expected an integer, got an integer.”

The read-only JVM probe

```clojure
(mapv #(select-keys % [:seon.error/signature :seon.problems/occurrences])
      (#'seon.problems/error-signatures
       (seon.db/db (seon.operator/connection "default"))))
```

returned counts 2, 1, and **0**. The zero belongs to signature
`9aee2b65e3bdb8a0b90f0edc20e1f51d687bb1a559e7fd872c7755f878f83866`.
`src/seon/problems.clj:102` selects every signature; `:113` sums its
occurrence counts with initial zero. The contract at
`resources/seon/schemas/seon.problems.edn:107` requires minimum 1.
This verifies the projection/contract mismatch, not why that signature lacks
occurrences. Program-provenance did not change these foreign owners.

Acceptance: the error owner and health projection agree on signatures without
occurrences, and runtime status returns truthful health data for that real
fixture rather than failing the whole observation. Probe the signature's
creation provenance before choosing whether to repair its producer or query.

Gate-restructure startup observation (2026-09-20T09:23:21Z): default PID
24777 is alive, but MCP runtime status refuses `seon.problems/problems` at
`[:seon.problems/error-signatures 0 :seon.error/at]`: the returned map lacks
the required timestamp. This is an unavailable health observation, not a
healthy result. No cause or fix is inferred; default was not changed.
