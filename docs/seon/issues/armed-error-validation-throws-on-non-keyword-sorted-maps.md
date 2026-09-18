---
type: issue
status: open
severity: blocker
created: 2026-09-17
tags: [issue, instrumentation, contracts, errors]
---

# Armed error validation throws on non-keyword sorted maps

At HEAD `eb2d9a20c`, the Stage 1 isolated fast iteration completed 89 tests /
559 assertions / 12 failures / 13 errors. The 13 exceptions originate in
`clojure.lang.Keyword/compareTo`, through Malli map validation and
`seon.instrument/compiled-wrapper`, when a successfully returned sorted map
has string keys. The wrapper probes it with an error-marker keyword. The
other 12 failures are publication-refusal expectations whose expected
transition was never reached because of that earlier exception.

Evidence: `tmp/test-system-stage1-inputs/fast-2.log`; the first instance is
`seon.test.selection-test/changed-inputs-are-decided-by-content-not-modification-time`.
The map-of contract admits the result; error classification must be total
for it. The instrument/error owners were held by the wrapper lane and were
not changed here. This is the same comparator failure previously observed in
[the MCP projection](archive/mcp-projection-crashes-on-non-keyword-map-keys.md),
at a different owning boundary.

The cache inventory now returns ordinary maps and sorts only at the digest
seam, where order affects identity. That keeps its result contract and exact
fingerprint deterministic, but does not claim to repair error validation for
other sorted-map results. The wrapper needs its own regression.
