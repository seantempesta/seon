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

Bridge step 1 reproduced the same predicate refusal in the canonical
`seon.config-test/config-loads-the-packaged-population-in-a-fresh-jvm` child
at 05:32 UTC. This time the child exited inside the unchanged 30-second
bound and its output named `seon.shell/stdin?`. Earlier iterations only
observed the 30-second backstop, which remains an open observation rather
than evidence of an executor-shutdown cause.

The step-1 declaration registry now realizes the complete population;
unlike `build-projection`, its constructor had not acquired the population's
predicate Vars with the existing `runtime-predicate` resolver. The common
registry constructor now performs that construction-time acquisition before
compilation, preserving supplied bindings and retained roots. The canonical
fresh-process regression passes in the 337-test combined iteration and the
subsequent recorded config iteration (23 tests, 179 assertions, 0 failures,
3 unrelated refusal-boundary errors). Its 30-second bound is unchanged.
The earlier timeout observation remains open, and scratch-start acceptance
is still owed; a passing fixture child does not establish live boot readiness.
See [the bridge evidence](../../prds/steward-platform/research/bridge-step1-registry-2026-09-20.md).
