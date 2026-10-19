---
type: issue
status: open
severity: medium
created: 2026-10-20
tags: [issue, performance, projection, review3-astra]
---

# The projection builder hit path adds work to unchanged requests

Independent measurements of the same unchanged-program MCP requests on `716cc7527` and `06614f6a8` show more work even when no branch definition changes. Five warm `nil` requests each use 6,210 instrumented calls on the baseline and 6,908 on HEAD (+11.2%); allocated memory is about 16.2 versus 18.4 MiB (+13.6%). Printing a small form and reading a function count show the same increase. Reads are unchanged. A second pair observes 6,195 versus 6,908 calls and 16.2 versus 18.7 MiB; the increase reproduces.

The request profile identifies a concrete part of this growth: over five `nil` requests HEAD adds 505 calls of `seon.db/loaded-builder`, 500 `seon.cache/lookup` calls, 500 `seon.cache/join` calls, and 505 cache claims. The baseline's `seon.db/projection-builder` reads an already present builder digest before entering `seon.db/loaded-builder`. The refactor deleted that function and calls `seon.db/loaded-builder` on every lookup, including hits. New `seon.db/connection-projection-state` calls and validation of the state account for additional entries. This profile does not attribute every allocated byte to one function.

The relevant work is reading an already held builder identity for an unchanged program, not deriving its reach again. Preserve the explicit executing state introduced by P5; address the hit cost at its owner without restoring process-wide name lookup or adding another cache.

Reproduction: the same test-only cost file on candidate `7f09f23db9501d929e43e71592ecf9f32c825f3e` (parent `716cc7527`) and `93723720cd384cecbcbd8c51eec33cdf03ebf663` (parent `06614f6a8`), `seon.cost-estimate-test/review3-unchanged-program` and `seon.cost-estimate-test/review3-profile-main`. Runs `aaa58143a716` and `b3d23199996a`; each test makes ordinary MCP requests through a running fixture agent, without changing the program. The profile's counters are JVM-window observations in an otherwise isolated test JVM; request costs come from the request's own cost handle.

The existing desired-cost task remains `seon.repl/an-unchanged-request-costs-seventeen-megabytes-and-six-thousand-calls`. This issue records the newly measured hit-path regression, not a second desired cost. The installed accepted-overrun gate still passes. See the [review and full cost table](../../research/agent-platform/2026-10-20/runtime-review3-astra.md) and its [probe source](../../research/agent-platform/2026-10-20/runtime-review3-astra-probes.patch).
