---
type: issue
status: open
severity: friction
tags: [issue, web, operator, dev-cluster, class/availability]
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

## Second observation 2026-09-08 15:28 — the advertisement drifts the other way

After a hook `init --dev default --changed …` adoption (which restarts the
web server; see
[development-adoption-drops-the-web-server](development-adoption-drops-the-web-server.md)),
the JVM (pid 45036) was listening on 7994 again — the preferred port had
been freed — while `data/clusters/default/prepl.edn` still advertised
`http://127.0.0.1:58444`. Every reader of the advertisement (monitors, the
URL handed to the owner, `bin/seon open`) pointed at a closed port for the
rest of the session. The advertisement is written once at boot
(`src/seon/cluster.clj`, web phase); the adoption path rebinds the server
without rewriting it. Fix in the same place as the restart: whatever
rebinds the web server rewrites the advertised URL and port, and a probe
that reads the advertisement must be able to trust it.
