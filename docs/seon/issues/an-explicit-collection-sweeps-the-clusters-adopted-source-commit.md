---
type: issue
status: open
severity: high
created: 2026-09-23
tags: [issue, gc, publication, adoption]
---

# An explicit collection sweeps the cluster's adopted source commit

## Observation (lane wrapper-profiling, scratch `tmp/wrapper-profiling/root-gc-broken`)

`(seon.cluster.registry/collect! store (java.util.Date.))` on a development
root swept 2,206 keys in 12,074 ms. The next hook adoption refused
"The adopted source commit is unavailable." (`seon.cluster.source/refuse!`,
`source.clj:39`) and the next start refused "The JVM's recorded source commit
is unavailable." (`seon.sci.eval/loaded-program`, `sci/eval.clj:884`). The
cluster row's `:seon.source/commit-id` names a `current-src` commit that is no
longer a branch head; the mark (`registry/collect-and-inventory!` →
`d/gc-storage` over roster branch heads) does not root it, so a cutoff of now
collects it.

## Wanted behavior

Every commit a live cluster names as its adopted source (and every commit a
running JVM's loaded program names) is a GC root, so an explicit sweep never
makes a root unbootable. The regression: adopt, collect with a now cutoff,
adopt and restart again.
