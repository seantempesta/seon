---
type: issue
status: resolved
severity: cleanup
tags: [issue, runtime]
---

# Web portable core was misnamed internal

The pure web policy and response transformations lived in
`seon.agent.web.internal`, but both the public family namespace and the JVM
platform leaf consumed them. The JVM leaf therefore violated the repository's
parent-only `.internal` require law.

Resolved on 2026-07-23 by renaming the one shared mechanism to
`seon.agent.web.core` and updating every source and test consumer. The old
`.internal` namespace was deleted because it described no parent-private
implementation.
