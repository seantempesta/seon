---
type: issue
status: open
severity: friction
tags: [issue, operator, test, class/n1, wave/operator-status-face]
---

# Bound `bin/seon status` instead of dumping every absent test result

## Problem

The default human status face prints every absent test result for every
namespace. On 2026-09-05, a healthy isolated root with one live `prd-review`
cluster produced **144 lines and 102,692 bytes** from:

```text
bin/seon --root /Users/sean/src/seon/tmp/lane-prd-review-root status | wc -lc
     144  102692
```

After the six-line process/root summary, lines begin with `namespace ...:
UNKNOWN; absent results: ...` and enumerate the full missing population. The
useful process fact is therefore followed by roughly 100 KiB of unbounded
diagnostic detail on every session-hygiene check. This is unreadable output
even when the cluster itself is healthy; absence is important evidence, but
printing every member is not a bounded human face.

Owner: the `bin/seon status` result renderer in
`script/seon/fresh_operator.clj` (currently owned by the gate-evidence lane).

## Expected

The default face reports the total unknown namespace/result counts and a small
bounded sample with a stable identity for retrieving the complete detail.
No subject may disappear: an unavailable complete detail is a typed refusal,
not silence. A verbose or machine-readable face retains the exhaustive list.

## Acceptance

With the same program population and no recorded test results, default
`bin/seon status` output stays within its declared byte/line budget, reports
the exact total absent-result count, shows a bounded sample, and provides a
working path to the complete list. A deliberately absent namespace must not be
reported healthy.
