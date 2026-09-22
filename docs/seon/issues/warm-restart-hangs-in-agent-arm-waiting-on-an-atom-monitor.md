---
type: issue
status: open
severity: blocker
created: 2026-09-23
tags: [issue, boot, agent, platform]
---

# A warm restart hangs in `seon.cluster.agent/arm!` waiting on an atom monitor

**Evidence (2026-09-22, lane reload-per-declaration).** `bin/seon --root
tmp/reload-b-root start head` from a `git archive` of `c1d2e6d7f` returned after
301,957 ms with the client failing (`:seon.error/message nil`); the JVM (pid 44611)
was alive with no readiness. `jstack` (`tmp/reload-per-declaration-evidence/restart-d-threads.txt`):
`main` BLOCKED "waiting to lock <0x000000701018b330> (a clojure.lang.Atom)" at
`seon.cluster.agent$arm_BANG_ (agent.clj:876)` ← `armer_step (agent.clj:1172)` ←
`seon.cluster/arm-agents! (cluster.clj:3093)` ← `seon.cluster.boot/stand-cluster-runtime!
(boot.clj:93)`. No platform thread in the dump holds that monitor, so the holder is
probably a virtual thread (`jstack` omits them). The same root restarted in 10,298 ms at
`2bd568c08`; `cluster/agent.clj` changed since in `7f6718507`, `da2086452`, `678009fcd`.
The store had one interrupted adoption before this start (the prior JVM was stopped by
`down` while an adoption request had exceeded the 300 s prepl bound).

**Not yet known.** Which thread holds the monitor (`jcmd <pid> Thread.dump_to_file -format=json`
includes virtual threads), and whether a clean root reproduces it.
