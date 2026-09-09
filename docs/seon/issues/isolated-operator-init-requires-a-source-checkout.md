---
type: issue
status: open
severity: friction
tags: [issue, runtime, class/p1, wave/operator-artifact-follow-up]
---

# Isolated operator init requires source files inside its root

Observed 2026-09-08 by turn-cut at `ff9507c1b`.

An empty existing `tmp/turn-cut-root` starts the REPL/store through
`bin/seon --root tmp/turn-cut-root start turn-cut`, then refuses because no
`current-src` branch exists. The required `bin/seon --root tmp/turn-cut-root
init` immediately refuses dependency-cache preparation because
`tmp/turn-cut-root/deps.edn` does not exist. These are the commands prescribed
for a lane's schema-reset proof, so an isolated data directory is insufficient.

The lane downed its failed scratch root through the operator (no recorded
JVM remained and the flock was free), and uses a disposable checkout at the
same path for verification. Default's lifecycle was not changed. The operator
owner should either carry the invoking checkout's source authority into an
isolated data root or document and construct the required checkout at the one
root-creation seam. No alternate source indexer was introduced.
