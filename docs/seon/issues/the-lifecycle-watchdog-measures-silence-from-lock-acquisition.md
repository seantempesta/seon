---
type: issue
status: open
severity: friction
tags: [issue, operator, wave/operator-child-lifecycle, wave/operator-lock-contention, class/tools]
---

# The lifecycle watchdog measures silence from lock acquisition, not from the last phase event

## Observation — 2026-09-20, `bin/seon reset --force` (pid 22783)

Phases completed with output: preflight 25 s, down, destroy, republish 233 s,
refork 52 s, start 51 s (default alive, advertised). The adopt phase printed
`phase=adopt started` and nothing more; at lifecycle elapsed 900,045 ms the
watchdog failed the whole reset with "Operator lifecycle holder was silent for
900000 ms in phase \"reset --force\"" (`data/operator/operations/reset-lifecycle-22783.log`).
The reset had been producing phase output for the first ~360 s, so the holder's
heartbeat was never refreshed by phase progress: the bound counted from lock
acquisition. Meanwhile the adopt phase itself was silent for ~540 s with no
event of its own — the same phase whose first request after a reset waits on a
cold page/projection derivation (the root page took >40 s to first paint).

## Fix

Two halves (AGENTS.md §2.3): phase output refreshes the lifecycle holder's
heartbeat (or the watchdog reads the phase log's mtime), and the adopt phase
emits its progress events (the publication progress lines already exist for
`current-src`). A reset whose cluster is alive and advertised after `start`
should report the adopt failure as a partial result naming default's state,
not as a failed reset. Related: tools item 2 (publication ~150 s, progress in
eventless phases) in the working edge.


## Publication progress implemented — 2026-09-20

Publication init (including the complete path) now carries the phase atom into
the existing lifecycle watcher. Cold output is streamed through that same
phase decoder with an explicit subprocess silence bound; live prepl output
uses it directly. Reset hands the request through its named phases and init
operations. Instrumentation restoration emits its own phase. Focused real
subprocess/lifecycle verification: 3 tests, 13 assertions, green
(`cce4c6eb4613`). The same scratch JVM completed live publications in 97.6 s
and 93.2 s without spawning another JVM. Full evidence and the initial
30-second-bound correction are in the publication dissolution landing note.

The separate requested reset partial-result presentation after an adoption
failure is not implemented here; this issue remains open for that obligation.
