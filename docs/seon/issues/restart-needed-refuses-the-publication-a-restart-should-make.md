---
type: issue
status: open
severity: blocking
created: 2026-09-23
tags: [issue, publication, boot, dependencies, platform]
---

# RESTART NEEDED refuses the publication a restart should make

## Problem

`seon.cluster.source/classify-paths` (`src/seon/cluster/source.clj:143-162`)
refuses every publication whose changed paths include `deps.edn` or a gitlink:
"JVM RESTART NEEDED ... Run `bin/seon down` then `bin/seon start` on the same
store". The refusal is right for a hook publication into a running JVM, whose
classpath is fixed at launch. It is wrong for the publication a freshly started
JVM makes of the files it just loaded, and nothing else ever publishes them, so
the advice loops:

- 2026-09-23T21:44Z: `bin/seon start` from the repository (pid 64314, ready
  51,138 ms) resumed on the store published from `bc8a1fa68`; `bin/seon init --dev
  default` then refused RESTART NEEDED for `deps.edn` (the working tree's
  `deps.edn` differs from the published one). Log:
  `tmp/orchestrator/nuke-is-total-rejoin-publish-2026-09-23.log`.
- Since `9744c970d`, resume compares the files with the published program and
  publishes the changed paths at boot (`seon.cluster.boot/changed-source-paths`,
  measured 588–1,037 ms over 714–717 inputs) and a refused boot-time publication
  refuses the boot. On today's working tree that set holds 58 paths including
  `deps.edn`, so a start from the repository would refuse at boot instead of
  running stale program rows silently.

`default` therefore cannot rejoin a working tree whose `deps.edn` differs from
the published one.

## Fix owner

`src/seon/cluster/source.clj` / `src/seon/cluster.clj` `full-source-refresh!`: a
publication made by the JVM that loaded those dependencies (the boot-time call,
3- or 2-arity without a development cluster, from
`seon.cluster.boot/stand-boot-layers!`) admits dependency changes; only a
publication into a JVM that loaded an older classpath refuses.
