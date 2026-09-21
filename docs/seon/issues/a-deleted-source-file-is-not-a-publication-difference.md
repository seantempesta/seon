---
type: issue
status: open
severity: blocker
created: 2026-09-23
tags: [publication, seon.cluster.source, class/absence-as-health, wave/publication-velocity]
---

# A deleted source file is not a publication difference

## Sighting (2026-09-23, `default` after the 22:10 reset, HEAD `d1fa4561d`)

The reset published the working tree, which held an untracked
`test/seon/test/offenders_test.clj`. The lane then deleted the file.
`bin/seon init --dev default` (no `--changed`) reported
`findings: 637; added=0; resolved=0 (unchanged publication)` and
converged in 3.3 s. Afterwards:

```
[:find ?p :where [?f :seon.fn.file/relative-path ?p] [(includes? ?p "offenders")]]
=> #{["test/seon/test/offenders_test.clj"]}
```

The file row (and the test it declared) survive. `bin/test --platform`
selects that test from the published program and refuses at the
coordinator: `Could not locate seon/test/offenders_test.clj on classpath`
(`src/seon/test/runner.clj:4741`; logs
`tmp/orchestrator/platform-2026-09-23-2220.log`, `-2300.log`).

## The class

The publication difference enumerates PRESENT inputs and compares their
digests with stored rows; a stored file row whose path no longer exists
is never a difference, so nothing retracts it. Absence of the file reads
as "unchanged". `seon.cluster.source/deleted-identities`
(`src/seon/cluster/source.clj:207`) exists for the changed-paths case;
the explicit no-paths case and the digest equation do not consult it.

## Fix shape

The difference is symmetric: `(stored rows) − (present inputs)` is a
deletion set, transacted as `[:db/retractEntity …]` for the file row and
every declaration row it owns (functions, tests, namespace when no other
file declares it), refused when surviving callers still name a removed
identity (the ordinary deletion rule, AGENTS.md §3). Both the explicit
and the changed-paths requests compute it. Regression: publish a fixture
with a test file, delete the file, publish again, assert the file row,
the test row and the namespace row are retracted and a caller of a
removed function refuses with the caller named.

Owner: `one-jvm-redesign` (owns `src/seon/cluster/source.clj`), as part of
slice 4 — the platform tier cannot go green on `default` until the row is
gone.
