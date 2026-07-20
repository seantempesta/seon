---
type: issue
status: resolved
severity: blocker
tags: [issue, component, cljs, flow]
---

# Downstream functions were absent from production execution children

## Problem

The optimized ACME Bun pod included downstream namespaces through
`acme.pod/-main`, but the optimized execution child retained
`seon.execution.runtime/-main`. Closure therefore removed downstream render
functions as unreachable. The live agent feed rendered three honest errors:
`The selected function is not loaded in the execution child.`

## Resolution

The release build accepts the downstream Shadow execution `:main` independently
from the pod `:main`. ACME supplies `acme.execution/-main`, whose dependency
closure loads its downstream functions and then delegates to
`seon.execution.runtime/-main`. This preserves one execution runtime and keeps
pod/web startup code out of the child.

Focused operator proof asserts both exact Shadow mains. Graduation requires an
immutable ACME package whose root feed renders the ACME canvas and supporting
surfaces through a real execution child without an unloaded-function error.
