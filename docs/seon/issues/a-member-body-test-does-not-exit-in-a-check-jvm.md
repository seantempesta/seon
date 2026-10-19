---
type: issue
status: open
severity: high
created: 2026-10-20
tags: [issue, test-runner, check, hang]
---

# A member-body test does not exit in a check JVM

On HEAD `81ab24b8f`, `bin/seon check --include-long --test seon.test-test/a-member-body-reads-its-member` (with other
`seon.test-test` long vars) reports it unfinished: it "did not exit within :seon.test/check-time-limit-ms remainder
114775 ms; thread 104 remains live and keeps its branch until it exits", and every test named after it stays pending
(runs `cbb0d3e581b5`, `4ea3c54f648d`, lane runtime-fix4). A lane naming `seon.test-test` with `--include-long`
therefore cannot prove its accepted reds red on HEAD: the HEAD confirmation run stops at this test. Also red on HEAD in
the same runs: `seon.test-test/a-branch-test-world-runs-the-jvm-loaded-program-not-its-fork-point-record` and
`seon.test-test/a-members-reads-stay-out-of-its-callers-evidence` (error).
