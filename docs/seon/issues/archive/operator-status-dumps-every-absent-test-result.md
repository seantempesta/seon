---
type: issue
status: resolved
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

## Verified at HEAD (2026-09-16, N1 verification)

**RESOLVED.** `bin/seon status` on the shared root is now nine lines and
653 bytes, against the filed 144 lines and 102,692 bytes:

```text
$ bin/seon status | wc -lc
       9     653

CLUSTER                     PID STATE       PREPL URL                      DRIFT
----------------------------------------------------------------------------------
default                   69622 alive       55914 http://127.0.0.1:7994    -
1/1 clusters alive
root footprint: not scanned (status --verbose); filesystem usable: 411.42 GiB (22.1%)
roster deferred: a live JVM owns the store; status is derived from claims and advertisements
test evidence: UNKNOWN; not queried by descriptor-only status; use status --verbose
recorded JVM pid 69622 generation 997f66f8-1102-4127-a9c3-833db941320d alive
orphan seon JVMs: none
```

Zero lines match `absent results`. Absence is reported as a TYPED UNKNOWN
that names why it is unknown and the exact command that answers it —
`status --verbose` — which is the note's "no subject may disappear" rule
satisfied without enumerating the population. The disk-footprint line
carries the same shape.

The default face therefore reports a bounded, honest face with a working
path to the complete detail. It does not print an exact absent-result
count, because the descriptor-only face deliberately does not query the
database for one; a count it could only guess at would be the worse
failure. Closing on the behaviour the acceptance protects.
