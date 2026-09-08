---
type: defect
status: open
severity: friction
tags: [web, operator, dev-cluster, class/availability]
---

# `default` falls back to an ephemeral web port after a refork

Observed 2026-09-08 15:22: after a lane's stop / `init --force` / start,
`default` advertised `http://127.0.0.1:58444` instead of its derived
port 7994, because the preferred port was still held when the new JVM
bound (the previous JVM's listener not yet released, or a lingering
socket). The owner's bookmark and the page monitor read a dead socket for
the whole window.

## Fix

Stop must release the listener before it reports the JVM exited, and start
must wait (bounded, loud) for the derived port to be free rather than fall
back silently; the fallback stays for a genuine collision with another
process, and then the advertisement is the only truth — `bin/seon open`
and any monitor read the advertised URL, never a remembered port.
