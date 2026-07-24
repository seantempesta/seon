---
type: issue
status: resolved
severity: blocker
tags: [issue, operator, database, flow]
---

# Make cluster reset stop before explicit apply

## Problem

`bin/seon cluster reset default` deleted the database and then launched
ordinary startup. Admission queried the initialization identity before apply
had installed its schema.

## Resolution

Resolved by `9e061cad6`, refined by `e37d44f9f` and `e9f72db8d`. Reset now
drains application generations, deletes the database and applied manifest,
and verifies the current release without admitting writer, host, pod, or
web-render. It preserves the byte-verified watcher owner, or consumes any
retained watcher generation before republishing. Explicit apply alone owns
initialization.

## Proof

On 2026-07-24, the final source-frozen chain completed reset in 7.44 seconds
and explicit apply in 38.61 seconds. Apply returned
`:seon.cluster.apply/ok? true`, `:root-created? true`, and release
`885395a2775ed87e1a819689af38d4d6e7c9001e905b360917a3de1478e7570b`.
No lookup or schema validation was weakened.
