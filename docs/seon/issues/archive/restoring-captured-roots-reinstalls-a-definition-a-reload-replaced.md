---
type: issue
status: resolved
severity: blocker
tags: [issue, testing, instrumentation, runtime, class/p1, wave/steward-platform]
---

# Restoring captured callable roots reinstalls a definition a reload replaced

## What the gate measured

Cold gate batch 88 (run root `tmp/test-runs/run.j9rx0e`, log
`tmp/orchestrator/gate-results/batch-88/named.log`) recorded the same fault six
times, all in worker `pool-1`:

```
clojure.lang.ExceptionInfo: seon.print/text-sink refused return value at []:
expected must implement seon.print/Sink, got an instance of seon.print.TextSink.
```

`seon.print/text-sink` returning a `seon.print.TextSink` its OWN armed output
contract refuses is a class-identity split, not a shape error. It errored
`seon.turn-test/a-refused-generated-form-records-its-refusal`,
`seon.turn-test/generated-read-evidence-rejects-turn-activity`,
`seon.turn-test/virtual-turns-use-the-proc-and-compaction-is-agent-scoped`,
`seon.web.jvm-test/public-search-settles-one-receipt-with-provider-credits`,
`seon.turn-loop-test/a-refused-batch-settlement-closes-the-turn-and-the-agent-turns-again`
and `seon.turn-loop-test/prompt-and-call-resolve-once-record-settings-and-see-next-turn-config`
— every one of them AFTER `pool-1` ran the `seon.cluster.boot-test` block, and
none of them named in the failing tests' own subjects.

## The mechanism

1. `seon.cluster.boot-test/development-adoption-targets-one-of-two-cohosted-clusters`
   (worker `pool-1`, 15:42:24Z, 161 s) performs a real publication and
   development adoption inside the WORKER JVM.
2. Adoption reloads the program's namespaces in the hosting JVM
   (`src/seon/cluster.clj:2247`); on a cluster with no prior commit the
   namespace set is every namespace carrying `:seon.ns/source`, `seon.print`
   among them. The reload replaces the `Sink` protocol object AND the
   `TextSink` class, and re-arms the new `text-sink` — a consistent world.
3. The fixture at `test/seon/cluster/boot_test.clj:112` wraps every boot test
   in `preserving-instrumentation-state`, which restored the ENTERING CALLABLE
   ROOTS on the way out. Callable roots are restorable; protocols, types and
   classes are not, and were not restored. The pre-reload `text-sink` closure
   went back over the post-reload `Sink`, and from that moment every
   `text-sink` call in that JVM built a superseded `TextSink` that
   `seon.print/sink?` (`src/seon/print.cljc:27`, `satisfies?` against the
   CURRENT `Sink` var) refuses.

The contract is not the defect: `sink?` reads the protocol Var at call time, so
it always sees the loaded protocol. Live falsification on `default`
(2026-09-17, disposable probe namespace, no `seon.print` reload): after
re-loading a probe namespace, a pre-reload instance fails `satisfies?` while a
fresh one passes, and restoring the captured root makes the Var's OWN output
fail its own protocol — the gate's exact signature.

This is the owner law in its usual clothes: a captured root is a mirror the
class loader re-decides.

## The fix

`seon.instrument` now owns what restoring means: `state`,
`replaced-definitions` and `restore!` (`src/seon/instrument.clj`). A Var whose
current original is no longer the one the captured root was compiled against is
left exactly as the loader left it, and `restore!` RETURNS that set, so a
reload inside a scope is reported rather than silent.
`seon.test-support/preserving-instrumentation-state` delegates.

Regression:
`seon.instrument-test/restoring-instrumentation-state-never-reinstalls-a-replaced-definition`.

Related: [the SCI reload fixture issue](sci-reload-test-leaves-worker-instrumentation-changed.md),
whose resolution was to adopt this same helper, and
[partial hot reload](partial-hot-reload-produces-mixed-code-with-no-warning.md).
