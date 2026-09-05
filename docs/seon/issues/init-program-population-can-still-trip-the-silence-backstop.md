---
type: issue
status: open
severity: blocker
tags: [issue, operator, performance, wave/boot-velocity]
---

# Keep `current-src` publication observable through program population

## Problem

`bin/seon init --changed` can still exit with
`:seon.fresh-operator/prepl-response-silent` while a healthy `current-src`
publication continues and commits behind the client. The operator therefore
reports failure for an authoritative write that succeeded.

## Evidence

On 2026-09-05, an isolated-root publication ran:

```text
bin/seon --root tmp/lane-print-floor-strings-2-root init --changed src/seon/cluster.clj
● current-src: program population compiled: 27777 entities, 14114 identities, 23536 keyword facts
! operator event silence backstop fired: prepl response was silent for 30000 ms
✗ The prepl response went silent for 30000 ms.
```

The command exited 1. A JVM query against the same isolated store immediately
afterward showed that `current-src` had nevertheless advanced from commit
`6a9c5ec9-1a02-5024-b1bc-bfbdc70b6325` to
`6a9c610f-8513-5365-abb1-863f7e5ae3aa`. Reforking a lane-owned cluster from
that exact new head succeeded and its MCP surface served the changed code.

The earlier instance of this class was archived as
[`init-publication-silent-beyond-backstop.md`](archive/init-publication-silent-beyond-backstop.md).
Its progress milestones do not cover the currently observed interval between
the compiled population report and terminal publication.

## Owner

The `seon.fn/index!` program-population progress callback and the
`script/seon/fresh_operator.clj` prepl publication wait, as one observable
publication boundary.

## Acceptance

- A publication whose program transaction takes longer than the configured
  silence backstop continues to publish bounded progress and returns its
  terminal commit to `bin/seon init`.
- A publication that genuinely stops making progress still trips the same
  bounded backstop.
- The regression proves that a reported init failure cannot later reveal a
  newly committed `current-src` head.
