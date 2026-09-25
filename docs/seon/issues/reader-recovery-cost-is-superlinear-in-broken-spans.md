---
type: issue
status: open
severity: performance
created: 2026-09-26
tags: [issue, reader, recovery, cost]
---

# Reader recovery cost is superlinear in broken spans

For k unclosed lines of `"(x\n"`, one `seon.sci.reader/read` takes 0.65, 1.2, 5.1, 22.6 and 124.6 ms at k = 4, 8, 16, 32, 64
(≤192 characters); the source bound is 1 MiB, so a hostile or badly broken reply can cost seconds to minutes. Recovery restarts
reading after each unreadable span (O(k·n) restarts, each re-scanning). Owner fix: recovery advances past each broken span in
one pass (resume the edamame reader at the recovery offset rather than re-reading from the start), or bounds recovery by span
count with a declared refusal. Evidence: docs/prds/agent-platform/landing/lane-design-one-reader-2026-09-25.md R4.
