---
type: issue
status: open
severity: medium
created: 2026-09-26
tags: [issue, test-check, platform, tool]
---

# An excluded member's hint names a `bin/test` form that does not exist

## Problem

`bin/test-check default --ns seon.cluster.fault-storage-test` prints, for each excluded member,
`excluded seon.cluster.fault-storage-test/<member> — run bin/test --platform -- seon.cluster.fault-storage-test/<member>`
(tmp/orchestrator/verify-0400.out.d/005.txt). `bin/test` accepts only a bare `--platform` (`bin/test:18-23`) and runs
the whole platform tier; `bin/test --platform -- NS/TEST` prints `Usage: bin/test --platform` and exits 0
(tmp/orchestrator/platform-merge-0810.txt). Long-marked members (e.g. every `seon.cluster.merge-test` member) therefore
have no focused route at all: the only way to run one is the full platform tier.

## Wanted

The hint names a command that runs exactly that member on an isolated host (or the long policy of `bin/test-check`), and
exits non-zero on unknown arguments. The producer of the hint text is the test request's exclusion report
(`src/seon/test.clj` around the `:seon.test/long-excluded` / destructive-excluded keys, :1000 and :1840-1853); find the
text's owner before editing.
