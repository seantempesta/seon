---
type: issue
status: open
severity: friction
created: 2026-09-20
tags: [issue, schema, operator, wave/publication-velocity]
---

# Scratch boot refuses the shell stdin predicate

Publication dissolution's authorized `bin/seon --root tmp/publication-root
start` refused during namespace boot with `Predicate seon.shell/stdin? has
no admitted callable in the corpus projection.` PID 74353 exited. The start
phase lasted 20,034 ms; no source publication measurement was produced.

This used the shared tree after `a962d75cd`, with uncommitted schema and
cluster edits. No individual edit is identified as the cause. This is not
evidence against publication changes: none had been made. The separate
namespace load of `seon.fn` and `seon.cluster.source` returned `:loads`.

Logs: `tmp/publication-dissolution/scratch-boot.log` and `scratch-start.log`.
The operator's `down` confirmed zero recorded JVMs and a free store flock;
the process table confirmed the PID absent. The owned root was deleted.
No shared-default lifecycle action was taken.

The earlier
[predicate adoption issue](a-new-core-predicate-and-its-schema-cannot-be-adopted-in-place.md)
is resolved and concerns an already-loaded owner. This new observation is
fresh boot; its cause has not been established. Acceptance: the authorized
scratch start reaches readiness with the converged schema and program.
