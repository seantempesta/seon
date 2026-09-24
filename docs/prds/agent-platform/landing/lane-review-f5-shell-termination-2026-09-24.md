---
type: landing
status: code only (owner load rule); scratch-namespace probe only; unadopted
lane: review-f5-shell-termination
created: 2026-09-24
design: docs/research/agent-platform/review-landings-2026-09-24.md F5
---
# Lane review-f5-shell-termination — 2026-09-24

Fixes review finding F5 (docs/research/agent-platform/review-landings-2026-09-24.md):
the shell threw away the process owner's "a captured process survived" result and
reported "The process was terminated", which ended ownership while the process still lived.

Before the fix, the process owner returned only a boolean, and the shell never read it.
After the fix, the owner returns the survivors themselves. The shell then waits for
their exit once more through `seon.await/await!`, which is the one bounded-wait owner.
If they still have not exited, that owner's declared `:seon.await/timeout-error`
becomes the result. The error names the missing event (the exit of each survivor),
the bound (`:seon.config.shell/termination-grace-ms`), the operation and the argv/cwd.

## Not done or not proven yet

- **Not run.** Under the load rule this lane ran no test requests. The edited
  namespaces were not loaded into default either (hook publication is paused, and
  root adopts). The only checks run were clj-kondo lint (0 errors, no new warnings)
  and one scratch-namespace probe, shown below.
- **The shell's full path with a survivor has no test.** A survivor that the child's
  own tree produces cannot be built. A zombie stays alive only while its parent lives,
  and that parent is always captured too, so it gets killed. The shell regression
  therefore calls the shell's `reap!` directly with a real zombie identity passed in.
  Survivors do happen in practice: descendants this JVM may not signal (for example
  after `sudo`) and processes blocked in the kernel.
- **"Retain ownership" is only partly delivered.** When a process survives, the shell
  does not read or interrupt its capture tasks, so those tasks stay attached to the
  survivors' pipes until they exit. The survivors' identities go to the caller in
  `:seon.error/offending`. The shell has no admission lock, so nothing blocks a later
  shell call from running alongside a survivor. That blocking would be new machinery
  and needs an owner decision.
- **The error's config field only fits by accident.** `:seon.await/config-value`
  (in seon.await.edn, which this lane does not own) lists only the eval and turn
  bounds. The grace value validates only because both are `[:int {:min 1}]`. The
  owner of seon.await.edn should add `:seon.config.shell/termination-grace-ms`.
- **An interrupted evaluation still throws, with the evidence attached.** On
  `InterruptedException` with a survivor, the unfinished-termination error is
  attached to the `InterruptedException` as a suppressed `ex-info`. The interrupt
  still propagates.
- **Ledger overlap.** Row 21 (m4-n1, "running") also lists
  src/seon/cluster/process.clj. The file had no uncommitted changes when this lane
  started.

## Evidence

Scratch-namespace probe (`tmp.review-f5-probe`, deleted afterwards) against the
installed owners. It used a real zombie (an exited child that its living perl parent
has not reaped) plus a `sleep 30` record:

- The installed `terminate-subprocess!` returned `false` in 207 ms (grace and
  cleanup 100 ms each). This reproduces the defect: the shell would have reported
  the process as terminated.
- `await!` on the survivors' exit returned a timeout error in 105 ms:
  `:seon.await/config-attribute :seon.config.shell/termination-grace-ms`,
  `:seon.error/offending [{:seon.boot/pid 50804 ...}]`,
  `:seon.await/requested-operation seon.cluster.process/terminate-subprocess!`,
  and the argv/cwd in `:seon.error/data`.
- Once the parent was destroyed, the zombie's exit completed.
- An earlier probe showed that a zombie is present, `isAlive` true, and still
  unexited 513 ms after `destroyForcibly`.

Cost: the path where every process exits normally is unchanged (empty survivors
means no extra wait). With a survivor, the extra wait is one more grace period
(default 1000 ms), and it happens only then.

## Changes

- src/seon/cluster/process.clj: `terminate-subprocess!` returns the surviving
  identities (a vector, empty only when every exit was observed). New
  `subprocess-exit`: an allOf future over the identity-verified `onExit` of each
  given identity.
- src/seon/shell/jvm.clj: new `reap!` (contracted), which consumes the owner's
  result. All three call sites (normal exit, both deadlines, interrupt) go through
  it. With a survivor, the unfinished error is returned in place of the exit result
  or the "terminated" diagnostic. No `:seon.effect/disposition :interrupted` is
  stamped, and the capture tasks are left running.
- test/seon/cluster/process_test.clj: new `zombie` fixture and
  `a-survivor-of-the-cleanup-bound-is-returned-never-reported-reaped`. Existing
  assertions changed from `true?` to `= []`.
- test/seon/shell/jvm_test.clj: new
  `a-survivor-of-termination-is-the-unfinished-result-never-terminated`.

Net lines: src +78/−41 (whitespace-insensitive +50/−13, mostly reindenting the
wrapped `if`), tests +53/−4.

## Root runs

    bin/test-check default --ns seon.cluster.process-test
    bin/test-check default --test seon.shell.jvm-test/a-survivor-of-termination-is-the-unfinished-result-never-terminated
    bin/test-check default --ns seon.shell.jvm-test
    bin/test-check default --test seon.bounded-boundary-census-test

These runs need default to have adopted the two source files first.
