---
type: landing
status: blocked
lane: fix-process-tree
created: 2026-09-24
report: docs/research/agent-platform/one-happy-path/cross-cutting-astra-2026-09-24.md
---

# Lane fix-process-tree — STOPPED at the API boundary (2026-09-24)

## Broken

- **Defect reproduced (was UNVERIFIED in cross-cutting-astra row 8).** `seon.shell.jvm/terminate-tree!`
  (`src/seon/shell/jvm.clj:266-277`) leaves a TERM-ignoring grandchild alive once the root
  exits within its grace, because it forces only when `.waitFor` on the root times out (`:272`).
- **Not fixed. The stop rule applied.** The surviving owner `seon.cluster.process/terminate-subprocess!`
  (`src/seon/cluster/process.clj:144-165`) is `defn-`, and its cleanup bound is the fixed private
  constant `subprocess-cleanup-ms` 10000 (`:107`). Shell cannot pass
  `:seon.config.shell/termination-grace-ms` to it, and a lane may not edit `cluster/process.clj`.
  Calling the private Var through `#'` would bend the owner, so this lane did not do it.

## Evidence (default JVM, jvm mode, disposable namespace `scratch.fix-process-tree`, since removed)

Drill: `sh -c "(trap '' TERM; exec sleep 30) & sleep 30"` started by babashka.process with
`:shutdown destroy-tree`. Identities were captured as `(pid, start-instant)` after 300 ms.
- Shell `terminate-tree!` with grace 2000: it returned in **5.48 ms**, the root was dead and
  grandchild pid 12707 (start 2026-09-24T15:14:15.471Z) **survived**. I checked its identity
  with `matching-process-handle`, then forced it and awaited its exit. It is gone.
- Owner `terminate-subprocess!` (called only for this probe) on the same drill: it returned
  `true` in **3.55 ms**, with **0 survivors** among 3 captured identities.
- `ps` later showed no probe pids. The test signalled only processes it had started.

## What the owner needs (for the `cluster/process.clj` owner, not this lane)

Make `terminate-subprocess!` public, or add a public wrapper. It should take
`(process-record launch-identities cleanup-ms)`, with `run-process!` passing its own constant.
Shell would then call `subprocess-tree-identities` at launch and on termination, which is also
private and must become public. After that, shell deletes `handle-identity`, `process-descendants`,
`same-process?`, `force-exact!` and the body of `terminate-tree!`, about 35 lines down to about 5.
One semantic change needs a ruling. The owner forces the whole tree right after `destroy-tree`,
with no polite-grace window. Shell's `termination-grace-ms` would then bound only the wait for
exit, not the SIGTERM grace. Also separate and not yet examined: after a normal `:exited` exit,
shell never inspects descendants, so a detached grandchild after an ordinary exit is not reaped.

## Changes

No source or test changes. This note is the only file added. Net lines: +0 in src and test.
There is no before/after probe timing because no fix landed. Nothing needs a reset.
