---
type: issue
status: resolved
tags: [issue, sci]
---

# `admit` paid a protocol lookup per node via `clojure.core/inst?`

`src/seon/sci/admit.clj` called `inst?` — `(satisfies? Inst x)` — before
classifying strings and collections. The 2026-08-01 admission-cap probe
measured 29–41 KB allocated per protocol miss.

## Resolution

Common instants now take allocation-free `instance?` arms for
`java.util.Date` and `java.time.Instant`. The `inst?` protocol fallback remains
after ordinary collection classification only for third-party `Inst`
extensions, with a regression covering all three paths.

On the 1,000-row / 9,001-node `alloc_attribution.clj` probe, admission moved
from 9.886 ms and 4,436.4 bytes/node to 2.710 ms and 379.7 bytes/node. The
focused `seon.sci.admit-test` gate passed 5 tests / 22 assertions.
