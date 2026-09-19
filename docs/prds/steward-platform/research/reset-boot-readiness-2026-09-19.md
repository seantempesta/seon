---
type: research
status: active
tags: [boot, readiness, testing]
---

# Reset boot readiness diagnosis — 2026-09-19

## Scope and initial evidence

Bounded lane; default pid 41822 is read-only. Read AGENTS.md §§0–6,
the assigned issue, and the context-generation plan README and working edge.
The original child log was deleted with its fixture root. Raw parent evidence
is `tmp/platform-drill-isolated.log:1008–1027`.

The last phase is **completed recovery**, not entry into recovery:
`src/seon/cluster.clj:250` derives it from `:seon.boot/recovered-runs`;
`:3484–3490` publishes that result before config reconciliation. Thus the
reported phase alone cannot identify a recovery-query failure.

## Dependency ledger

- Clojure `with-open`, `reference-code/clojure/src/clj/clojure/core.clj:3854`:
  closes resources in reverse order via finally. The existing operator
  `launch-form` owns the readiness socket and writer (`script/seon/fresh_operator.clj:1990`).
- Core.async Flow `stop`,
  `reference-code/core.async/src/main/clojure/clojure/core/async/flow/impl.clj:174`:
  closes its report/error channels. Boot recovery is an ordinary synchronous
  call before graph/fault-committer construction (`src/seon/cluster.clj:3484`).
- Datahike writer `reference-code/datahike/src/datahike/writer.cljc:147–160`:
  transaction failures return through the writer callback; the error branch
  retains the old database. `seon.turn/recover-call` decides unfinished work
  in that writer (`src/seon/turn.clj:1691`).

## Reproduction (in progress)

Detached worktree `tmp/reset-boot-readiness-wt` at `0aee224c5`, vendored
reference-code linked to the checkout; root `tmp/reset-boot-readiness-root`.
The fixture's two test namespace files are present. Operator sequence:
`bin/seon --root /Users/sean/src/seon/tmp/reset-boot-readiness-root init`,
then `init default`, then `start default`. These call the fixture's same
`init! []`, `init! ["default"]`, `start! ["default"]` owners.
The first publication correctly refused source drift because the two test
files were added while analysis started; the stable retry is separate.

## Observations before the exact fixture reproduction

At `0aee224c5`, the fresh isolated child reached READY. Child log bytes:

```text
boot phase: repl
boot phase: store
boot phase: branch
boot phase: recovery
boot phase: config
boot phase: program
boot phase: work-launcher
boot phase: agents
boot phase: web
boot phase: ready
```

Operator start returned exit 0, phase start 54,944 ms; MCP JVM read returned
`{:boot {:seon.boot/ready-ms 33606, :seon.boot/recovered-runs 0,
:seon.boot/recovery-operations 0}, :test-loaded false}`. The deliberately
unloadable test namespace stayed unloaded. The source boot owners have no
committed difference between `0aee224c5` and the lane's current HEAD.

A CLI-only publication wrapper separately hit its 180,000 ms lifecycle hold
bound even though the publication child completed and wrote commit
`6aaed2f7-b64d-5d3a-8c2a-125c23b7c71c`. This is distinct from a recovery fault.
The reproduction now invokes the fixture's private operator functions to
match its 300,000 ms outer lifecycle lock exactly.

The fixture supplies no `:seon.operator.lock/progress` at
`test/seon/dev/fresh_operator_reset_test.clj:346`. On expiration,
`src/seon/operator/state.clj:534` throws to the waiter while its non-daemon
holder continues (`:553` documents this). The fixture's `finally` directly
invokes private `down!` without acquiring that held lifecycle lock (`:421`).
The parent log's PROCESS RECORD CENSUS precedes the readiness-closed assertion:
cleanup was already running while `start!` still waited.

The initial fast snapshot printed a 19,982,386-byte log dominated by a raw
`seon.fn.manifest` value during overlay admission. That output is unreadable;
this is recorded for the held test-system owners, not repaired here.

## Verification boundaries

Baseline fast snapshot at `733d0f422`: 12 tests / 59 assertions / 1 failure /
0 errors. `schema-row-convergence-uses-the-stores-own-semantics` expected no
schema changes but received schema rows (including config retry aliases).
The recovery-refusal test passed. This baseline preceded the added positive
recovery assertion. The fixture base took about 189 seconds; a stack sample
showed its `seon-test-database-base` thread compiling Malli validators while
the test caller awaited that base.

The changed fast invocation refused at overlay admission with:
`Incomplete --paths overlay; add changed caller files: src/seon/error.clj test/seon/error_test.clj`.
Both belong to the explicitly held error-family lane. A separate HEAD
worktree at `733d0f422`, plus only the owned test diff, is the verification
fallback; no held edits were copied.

The first exact private-function reproduction stopped during publication:
`seon.operator.subprocess/deadline-exceeded` at 18:29:37Z, 185.6 seconds after
entry. No cluster child existed. It did not reproduce readiness closure.
A second identical run without the competing test JVM is in progress.

## Verified cause: fixture cleanup races boot

At 18:34:58.914042Z the controlled probe observed the real recovery
completion event. At 18:34:58.914914Z it called the fixture's own private
`down!` with `["--force"]`, while private `start!` was still awaiting READY.
Cleanup sent SIGTERM to recorded child 94550. At 18:34:59.921012Z boot returned:

```clojure
{:probe/message "The cluster JVM closed readiness before READY."
 :probe/data {:seon.error/kind :seon.fresh-operator/readiness-closed
              :seon.fresh-operator/name "default"
              :seon.boot/pid 94550
              :seon.fresh-operator/phase "recovery"}}
```

The final boot's exact child-log suffix, captured **before** cleanup:

```text
WARNING: Using incubator modules: jdk.incubator.vector
boot phase: namespaces
Warning: environ value /opt/homebrew/opt/openjdk/libexec/openjdk.jdk/Contents/Home for key :java-home has been overwritten with /opt/homebrew/Cellar/openjdk/26.0.1/libexec/openjdk.jdk/Contents/Home
boot phase: repl
boot phase: store
boot phase: branch
boot phase: recovery
```

The complete appended child log was 1194 bytes, SHA-256
`8de9d8566d16c44cf359a8fe772ef2a6469446a75dd162000c63c2d870f9cca8`. Before and after cleanup were
byte-identical. There was no boot-failure event.

The original parent log has the same ordering: recovery completion,
cleanup census, readiness closure, SIGTERM completion, outer hold-timeout.
The original child bytes are unavailable; the controlled experiment verifies
the causal class without claiming to recover those deleted bytes.

### Hypotheses

- Refused recovery query: falsified for the fresh isolated boot; actual
  recovery returned zero operations and boot reached READY. The canonical
  refused-query regression remains and now also checks real fresh recovery.
- Fault-committer closure: excluded by construction; recovery precedes the
  fault committer/graph. The readiness transport is the operator's socket,
  not a Flow channel.
- Recovery bound: no recovery bound fired in either boot. The original
  reported bound is the fixture's outer lifecycle hold, across publication,
  fork and boot without progress. Its throw enters concurrent cleanup.
- Working-tree difference: the reproduction uses `0aee224c5` and only the
  fixture's two added test files. The boot owners are unchanged at the
  verification HEAD. Held source edits were excluded.

**Missing event:** READY after recovery. Cleanup prevented it by killing
its producer; this is not absence of recovery health.

### Repair boundary

Do not alter `recover-runs!`, turn recovery, or readiness parsing to hide
this event. Repair the fixture so cleanup is serialized after the lifecycle
holder's terminal event, and make the declared bound observe actual phase
progress. Preserve every bound. The assigned test file is explicitly held
read-only and currently has foreign edits. A release was requested; absent
that release this lane cannot land the lifecycle repair. The issue stays open.

### Controlled reproduction script

Run from the isolated `0aee224c5` worktree after the ordinary scratch boot
and `down --force`; all paths below refer to this lane's roots. This is a
causal probe, not a replacement test harness. The print wrapper observes
the real phase line and only delivers a promise; boot, database, operator,
readiness socket and process termination remain real.

```clojure
(require '[seon.fresh-operator] '[clojure.java.io :as io] '[clojure.string :as str])
(let [root "/Users/sean/src/seon/tmp/reset-boot-readiness-root"
      invoke (fn [sym & args] (apply (var-get (ns-resolve 'seon.fresh-operator sym)) args))
      recovered (promise)
      print-line println
      child-log (io/file root "data/clusters/default/logs/seon.log")
      boot (future
             (with-redefs [clojure.core/println
                           (fn [& xs]
                             (apply print-line xs)
                             (when (some #{"● default boot: recovery"} xs)
                               (deliver recovered :recovery)))]
               (try (invoke 'start! root ["default"])
                    (catch Throwable t
                      {:probe/message (ex-message t) :probe/data (ex-data t)}))))]
  (try
    (let [phase (deref recovered 300000 :missing-recovery)]
      (prn {:probe/at (str (java.time.Instant/now)) :probe/observed phase})
      (when (= :missing-recovery phase)
        (throw (ex-info "Recovery completion never arrived" {})))
      (spit "/Users/sean/src/seon/tmp/reset-boot-readiness-evidence/interference-before-cleanup-child.log" (slurp child-log))
      (prn {:probe/at (str (java.time.Instant/now)) :probe/event :cleanup-begin})
      (invoke 'down! root ["--force"])
      (prn {:probe/at (str (java.time.Instant/now)) :probe/boot-result (deref boot 300000 :missing-boot-completion)})
      (spit "/Users/sean/src/seon/tmp/reset-boot-readiness-evidence/interference-after-cleanup-child.log" (slurp child-log)))
    (finally (invoke 'down! root ["--force"]))))
```

### Concrete fixture repair for its owner

Keep the 300,000 ms declared acquisition/hold bounds. Supply the lifecycle
request's existing progress atom and advance it at the actual boundaries:
publication, fork, boot, in-process test, cleanup. Put the test's `try/finally`
**inside** the lifecycle transition so the holder also owns cleanup. Then a
waiter timing out cannot start `down!` concurrently with its own holder.
Each existing operator subprocess/readiness/evaluation bound remains intact.
Do not merely lengthen the bound or retry readiness after killing the child.

### Cleanup

The three owned operator roots each reported `0/0 clusters alive` and
`orphan seon JVMs: none`; their operation logs were captured under
`tmp/reset-boot-readiness-evidence/` and the roots were removed. The original
reproduction worktree was removed after its operator calls returned. The
verification worktree is removed after the fast invocation finishes.

### Full-sequence probe retained for reproduction

The following was run via `bb --config bb.edn --deps-root . --classpath
script:src:resources /absolute/path/to/probe.clj` from the detached worktree.
The second run changed only the root name to `reset-boot-readiness-exact2-root`.
Both retained the declared 300,000 ms outer bound and 180,000 ms publication
subprocess bound; both stopped at the latter before launching a cluster.

```clojure
(require '[seon.fresh-operator] '[seon.operator.state :as state] '[clojure.java.io :as io])
(let [root "/Users/sean/src/seon/tmp/reset-boot-readiness-exact-root"
      invoke (fn [sym & args] (apply (var-get (ns-resolve 'seon.fresh-operator sym)) args))
      mark (fn [event value] (prn {:probe/at (str (java.time.Instant/now)) :probe/event event :probe/value value}) (flush))
      child-log (io/file root "data/clusters/default/logs/seon.log")
      capture (fn [stage] (when (.exists child-log) (spit (str "/Users/sean/src/seon/tmp/reset-boot-readiness-evidence/" stage "-child.log") (slurp child-log))))]
  (.mkdirs (io/file root))
  (try
    (mark :begin root)
    (state/with-lifecycle-lock!
     {:seon.operator.lock/path (state/root-lifecycle-lock-path root)
      :seon.operator.lock/command "boot without test namespaces"
      :seon.operator.lock/acquisition-timeout-ms 300000
      :seon.operator.lock/hold-timeout-ms 300000}
     (fn []
       (doseq [[sym args] [['init! []] ['init! ["default"]] ['start! ["default"]]]]
         (mark :begin-operation sym)
         (try
           (invoke sym root args)
           (mark :complete-operation sym)
           (catch Throwable t (mark :operation-failed {:message (ex-message t) :data (ex-data t)}) (throw t))))))
    (mark :ready true)
    (catch Throwable t (mark :outer-failed {:message (ex-message t) :data (ex-data t)}))
    (finally
      (capture "exact-before-cleanup")
      (mark :cleanup-begin true)
      (invoke 'down! root ["--force"])
      (capture "exact-after-cleanup")
      (mark :cleanup-complete true))))
```

## Final bounded-lane result

The owned regression passed its added positive assertion and its existing
refused-query assertions under armed contracts. The clean HEAD worktree fast
run completed **12 tests / 60 assertions / 1 failure / 0 errors**, with the
same pre-existing schema-row-convergence failure as the 12/59 baseline.
It is not a green namespace tally. Log:
`tmp/reset-boot-readiness-fast-isolated.log`; final test ended
2026-09-19T18:40:02.828607Z. No cold gate was run.

Files touched by this lane:

- `test/seon/cluster_test.clj` — extend the existing canonical recovery
  regression with a positive fresh-database completion assertion.
- `docs/seon/issues/isolated-reset-boot-test-closes-readiness-during-recovery.md`
  — diagnosis, evidence, explicit remaining fixture repair; stays open.
- This landing note — child bytes, operations evidence, reproduction scripts,
  falsified hypotheses, counts and ownership boundary.

No production change was justified in the assigned boot/recovery/readiness
regions. The actual lifecycle repair remains in the explicitly held reset
fixture. No held source, default lifecycle, or another lane's session was
modified. Both lane-owned worktrees and all three scratch roots were removed
after their processes/commands returned. Evidence logs remain under `tmp/`;
the causal script and relevant child bytes are preserved above.

Orchestrator cold proof still owed:

```sh
bin/test --paths test/seon/cluster_test.clj -- seon.cluster-test
bin/test --platform
```

After its owner lands the fixture lifecycle repair, the named real reset
boot test must also pass with logs retained on failure. A green recovery
unit test alone cannot prove that fixture's asynchronous cleanup ownership.


## Released fixture repair — 2026-09-19

The orchestrator accepted `9851d239e` and `0c98a5344`, then released the
fixture after `d199f53c0`. The Path.resolve common-directory fix and all
existing platform metadata are preserved. The previous held-file boundary
above is historical; this followup implements the repair at that owner.

`with-boot-cleanup!` puts the complete fixture and cleanup in the lifecycle
holder. Its caller can expire without entering cleanup concurrently. The
holder awaits the existing operator READY or terminal-failure event before
cleanup. Publication, fork, start, in-process test and cleanup publish actual
phase transitions to the existing progress atom. The 300,000 ms acquisition
and hold bounds and all underlying operator bounds remain. An expired
fixture waiter reports its awaited READY/terminal event, phase, elapsed
milliseconds and child log; it leaves cleanup with the running holder.

At the readiness owner, EOF no longer masquerades as the failure cause.
It waits for the already-subscribed ProcessHandle exit event under the
remaining original phase deadline. Exit reports `boot-process-exited` with
phase, elapsed milliseconds, missing READY and child log. If neither READY
nor exit arrives, the same bound reports `boot-phase-silent`, naming what
never arrived. EOF does not renew that deadline. Explicit child failure
messages keep their existing handling.

One fixture regression, `boot-cleanup-awaits-readiness-or-child-exit`, uses
real lifecycle locking, sockets and disposable child processes. Its caller
bound expires while readiness remains pending; it verifies cleanup has not
run and the child remains alive, then releases either READY, child exit,
or EOF from a live child. It verifies delayed READY succeeds, a dead child
reports recovery and its log, and missing terminal events fail boundedly.
It neither replaces the canonical database fixture nor changes recovery.
The earlier canonical positive/refused recovery regression remains.

### The earlier one pre-existing failure, exactly

`seon.cluster-test/schema-row-convergence-uses-the-stores-own-semantics`
failed this assertion:

```clojure
(= [] (changes database (schema/registered-schemas)))
```

Actual changes contained schema rows, including
`:seon.config.ai.retry/base-delay-ms`, `jitter-fraction`,
`maximum-delay-ms`, `maximum-retries`, `maximum-total-delay-ms`, and
`multiplier`, whose forms reference their `:seon.ai.retry/*` aliases.
The prior baseline was 12 tests / 59 assertions / 1 failure / 0 errors;
the positive recovery assertion made it 12 / 60 / 1 / 0. This is **not**
the preflight lane's publication-timeout residue. That lane reported
`seon.operator.subprocess/deadline-exceeded` for publication child 92606
at its 180,000 ms process-exit deadline, before any cluster JVM existed;
its last progress was “program population compiled: 30619 entities, 23656
identities, 39295 keyword facts.” Read its
[landing note](preflight-fixture-path-2026-09-19.md) end to end.

### Durable evidence

The original `tmp/reset-boot-readiness-*` evidence cited above is archived
under [reset-boot-readiness-evidence-2026-09-19/](reset-boot-readiness-evidence-2026-09-19/).
The child logs are unchanged raw bytes (`success-child.log` and
`interference-{before,after}-cleanup-child.log`). `operator-operations.tar.gz`
retains the three roots' `data/operator/operations` captures. The other
`.log.gz` files preserve full earlier output with the original prefix
removed: `interference`, `exact`, `exact2`, `fast-baseline`, `fast-isolated`,
`init`, `init2`, `start`, and `fast` (the refused overlay admission).
The reproduction scripts are already included in this note. These archives
supersede the disposable `tmp/` locations; the original platform-drill log
belongs to its producer and was left alone.

### Followup fast verification

The combined HEAD-plus-owned-paths run at `76ed03e3` completed at
2026-09-19T18:57:01Z: **25 tests / 241 assertions / 2 failures / 1 error**.
The new readiness/cleanup regression passed all **35 assertions**, covering
READY, exit and silent EOF, from 18:51:56.289Z to 18:51:59.002Z. The canonical
recovery regression also passed. The two remaining boundaries were:

1. The existing schema-convergence assertion quoted above.
2. The full boot fixture's publication subprocess, pid 1883, exceeded its
   180,000 ms process-exit deadline, was reaped, and produced one assertion
   failure plus one exception. Cleanup observed zero process records and
   no cluster JVM. This repeats the preflight lane's publication boundary;
   it did not report readiness-closed or run cleanup against a booting child.

Command:

```sh
bin/test-fast --paths script/seon/fresh_operator.clj test/seon/dev/fresh_operator_reset_test.clj test/seon/cluster_test.clj -- seon.cluster-test seon.dev.fresh-operator-reset-test
```

Full output: [repair-fast.log.gz](reset-boot-readiness-evidence-2026-09-19/repair-fast.log.gz).
An earlier initialization attempt captured an unfinished parenthesis while
this lane was editing; it ran no tests, was corrected, and is not a tally.
The final reset-only run follows the last fixture phase-label additions and
initially-dead-child diagnostic fields. No foreign dirty source was included
in these snapshots. The old error-family overlay boundary remains historical;
this followup required no extra worktree and did not touch held files.

### Final bytes: real READY observed, later test-result boundary

The final reset-only snapshot at `76ed03e3` completed at
2026-09-19T19:04:17Z: **13 tests / 184 assertions / 1 failure / 0 errors**.
The new regression again passed all 35 assertions (18:58:34.009Z–18:58:36.690Z).
This time publication completed within its bound, fork succeeded, and
isolated cluster child **4654 reached READY**. The child's own log records
recovery → config → program → work-launcher → agents → web → ready, then
`Testing seon.boot-runner-smoke-test`. At 19:04:05Z the fixture finished
its test observation and cleanup reaped child 4654 with SIGTERM. Cleanup
therefore followed READY and test completion, rather than causing EOF
while boot awaited READY.

Command:

```sh
bin/test-fast --paths script/seon/fresh_operator.clj test/seon/dev/fresh_operator_reset_test.clj -- seon.dev.fresh-operator-reset-test
```

The remaining assertion is in
`cluster-boot-omits-test-namespaces-and-in-process-run-loads-one`:

```clojure
(= {:seon.test/sym 'seon.boot-runner-smoke-test/indexed-smoke
    :seon.test/pass-count 1
    :seon.test/fail-count 0
    :seon.test/error-count 0}
   result)
```

Actual result has the same symbol, pass-count 1 and fail-count 0, but
**error-count 1**. This is after READY, not the publication-timeout residue
and not readiness-closed. The existing result projection omits additional
error detail, so this note does not attribute its cause. That in-process
test-result boundary belongs to the held test-system owners; its assertion
and result bytes are preserved in
[repair-final-fast.log.gz](reset-boot-readiness-evidence-2026-09-19/repair-final-fast.log.gz).
The boot/cleanup repair is verified; the complete namespace is not green.

[final-boot-child.log](reset-boot-readiness-evidence-2026-09-19/final-boot-child.log)
is the raw 681-byte child snapshot taken after READY and smoke-test entry,
before cleanup. It is not claimed to contain later test-result diagnostics.
`final-operator-operations.tar.gz` retains the operation-log snapshots taken
before READY; publication had completed. The full parent log above retains
READY, the exact later assertion and cleanup's recorded process identity.

Files in this repair commit: `script/seon/fresh_operator.clj` (readiness
only), `test/seon/dev/fresh_operator_reset_test.clj` (fixture lifecycle,
cleanup and one regression), the issue note, this landing note, and its
dated evidence directory. The previously accepted `test/seon/cluster_test.clj`
change remains. No recovery production code needed changing. The released
fixture's Path.resolve fix and existing platform metadata remain intact.

All owned test launchers and children have exited; their snapshots and
scratch roots are removed. The cited logs now live in the dated evidence
directory; no `tmp/reset-boot-readiness-*` artifacts remain. No default
lifecycle operation, foreign session operation, cold gate or foreign edit
was performed. Shared HEAD advanced while the named snapshots ran; the
snapshot commits above identify exactly what they exercised.

The orchestrator still owes the cold gates:

```sh
bin/test --paths script/seon/fresh_operator.clj test/seon/dev/fresh_operator_reset_test.clj test/seon/cluster_test.clj -- seon.cluster-test seon.dev.fresh-operator-reset-test
bin/test --platform
```
