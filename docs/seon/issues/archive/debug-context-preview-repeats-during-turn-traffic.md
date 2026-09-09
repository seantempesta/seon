---
type: issue
status: resolved
severity: friction
tags: [issue, render, performance]
---

# Debug context previews dominate the stronger simultaneous turn/write probe

Adopted source `6aa0db4f-b37a-5712-923e-f55a3828146b`, default PID 22932,
on 2026-09-08: three debug tabs plus two bounded source turns and one write per
second produced a first SSE event of 2994.7665 ms. The separate required
write-per-second probe passed at 1699.843583 ms; plain changed-content GETs
stayed below 690 ms and cold context acquisition was 284 ms.

Virtual-thread-aware `jcmd Thread.dump_to_file -format=json` sampling retained 30
active caller page stacks. Thirteen entered the debug context algorithm through
`debug-prompt` (`seon.eval/of-agent` / `seon.turn/system-turn`), eight entered
`render-source-call`'s preview evaluation. The samples do not show a render-proc
wait. Probe whether these derived preview values can reuse the existing shared
read-evidence cache without stale output or duplicating the turn algorithm.

Full stacks, exact bytes, timestamps, and runnable probes are recorded in the
[page-feed landing note](../../prds/context-generation/research/page-feed-landing-2026-09-08.md).


Resolved by `3fe446205`: ordinary paired-entity inspection no longer executes the
optional context algorithm; `prompt=true` remains explicit. Identity input excludes
reverse concerns, and shared caller results are published correctly. Default's
combined three-tab/two-turn/write-per-second probe measured 691.343083 ms maximum
first SSE and 851.743250 ms maximum plain GET over 30 samples, all HTTP 200.
Both turns were accepted and overlap is recorded in five consecutive samples.
The path-limited web gate passed 71 tests / 419 assertions and platform passed
82 tests / 486 assertions. Exact evidence is in the linked landing note.
