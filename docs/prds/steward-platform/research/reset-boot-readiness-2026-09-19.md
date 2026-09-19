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
