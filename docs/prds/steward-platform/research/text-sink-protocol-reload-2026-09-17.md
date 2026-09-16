---
type: research
status: active
tags: [research, instrumentation, testing, gate, wave/steward-platform]
---

# `seon.print/text-sink` refused its own return value — the reload split, 2026-09-17

## The observation

Cold gate batch 88, run root `tmp/test-runs/run.j9rx0e`, log
`tmp/orchestrator/gate-results/batch-88/named.log`. Six identical faults, all
in worker `pool-1` (`workers/pool-1/logs/worker-stderr.log` line 6 records the
dev panic):

```
clojure.lang.ExceptionInfo: seon.print/text-sink refused return value at []:
expected must implement seon.print/Sink, got an instance of seon.print.TextSink.
Fix: must implement seon.print/Sink Contract: :seon.print/sink.
```

The stack is always `seon.print$fit` (print.cljc:1344) →
`seon.print$emit_text` (print.cljc:887) → the armed `text-sink` wrapper →
`seon.instrument$throwing_report` (instrument.clj:425). A Var refusing the
value it just built is a class-identity split, never a shape error.

Errored tests, in `pool-1`'s own order:

| time (Z) | task |
|---|---|
| 15:44:45 | `seon.turn-test/a-refused-generated-form-records-its-refusal` |
| 15:44:50 | `seon.turn-test/generated-read-evidence-rejects-turn-activity` |
| 15:45:08 | `seon.turn-test/virtual-turns-use-the-proc-and-compaction-is-agent-scoped` |
| 15:45:35 | `seon.web.jvm-test/public-search-settles-one-receipt-with-provider-credits` |
| 15:45:47 | `seon.turn-loop-test/a-refused-batch-settlement-closes-the-turn-and-the-agent-turns-again` |
| 15:45:53 | `seon.turn-loop-test/prompt-and-call-resolve-once-record-settings-and-see-next-turn-config` |

Every one of them runs AFTER the `seon.cluster.boot-test` block that `pool-1`
finished at 15:44:32, and none of them is about printing. Worker `pool-1` armed
1079 contracts at 15:38:52 (`CONTRACTS ARMED … instrumented= 1079`).

## The sequence, with file:line

1. `seon.cluster.boot-test/development-adoption-targets-one-of-two-cohosted-clusters`
   (BEGIN 15:42:24Z, 161 s, `named.log:42`) runs a real publication and
   development adoption INSIDE the worker JVM.
2. `src/seon/cluster.clj:2247` — `(require namespace-name :reload)` over the
   changed namespace set. With no prior commit (`prior-commit` nil at
   `src/seon/cluster.clj:2157`) that set is every namespace carrying
   `:seon.ns/source`, `seon.print` included. The reload builds a NEW `Sink`
   protocol object and a NEW `TextSink` class, and adoption's
   `instrument/apply!` arms the new `text-sink` over them: consistent.
3. `test/seon/cluster/boot_test.clj:112` — every boot test runs inside
   `seon.test-support/preserving-instrumentation-state`, which restored the
   ENTERING CALLABLE ROOTS in its `finally`. Callable roots are restorable;
   the protocols, types and classes those closures build from are not, and
   were not restored. The pre-reload `text-sink` closure went back over the
   post-reload `Sink`.
4. From then on, every `text-sink` call in `pool-1` built a superseded
   `seon.print.TextSink`, and `seon.print/sink?` (`src/seon/print.cljc:27`,
   `satisfies?` against the CURRENT `Sink` Var) refused it. The Var's own
   armed output contract then refused the call.

## What was falsified

The contract is NOT the defect. `:seon.print/sink` is
`[:fn {:error/message "must implement seon.print/Sink"} seon.print/sink?]`
(`resources/seon/schemas/seon.print.edn:383`), and `sink?` derefs the protocol
Var at call time, so it always tests against the LOADED protocol. Live probe on
`default` (JVM mode, disposable namespace `probe.sinkreload`, `seon.print`
never reloaded):

```clojure
{:old-instance-satisfies            true    ; before the reload
 :new-instance-satisfies            true    ; a fresh instance, after it
 :old-instance-satisfies-now        false   ; the pre-reload instance, after it
 :restored-root-output-satisfies    false   ; after restoring the captured root
 :restored-class "probe.sinkreload.PText"}  ; same NAME, different class
```

The last two lines are the gate's signature reproduced exactly: the Var's own
output failing its own protocol, printed with the same class name.

## The fix

`seon.instrument` now owns what restoring instrumentation means
(`src/seon/instrument.clj`, after `remove!`):

- `state` — every armed Var's current root plus Malli's function-schema
  registry, as one value;
- `replaced-definitions` — the Vars whose loaded definition this JVM replaced
  since the state was captured, DERIVED by comparing each captured root's
  `malli.instrument/-f->original` with the Var's current original;
- `restore!` — unarms and restores everything else, skips those Vars entirely,
  and RETURNS them, so a reload inside a scope is reported rather than silent
  (an empty set is the ordinary case; the check cannot read absence as health).

`seon.test-support/preserving-instrumentation-state` delegates to it
(`test/seon/test_support.clj`); its own `malli.instrument` require is gone.

Regression, one per class:
`seon.instrument-test/restoring-instrumentation-state-never-reinstalls-a-replaced-definition`
— an armed-shaped probe Var over its own protocol and type, reloaded inside the
scope, asserting it is named, left to the loader, emits a value its own
protocol accepts, that `seon.print/text-sink` still satisfies
`seon.print/sink?`, and that every unreplaced root round-trips
(`#'seon.db/transact!` still armed, count exactly one lower).

## Live proof, on `default` (pid 30138)

- Probe 1 (above) — the split reproduced and the restore shown to cause it.
- Probe 2, after `(require 'seon.instrument :reload)` in the dev JVM, on the
  new functions: `{:armed-before 1076, :replaced-names [probe.sinkreload/make],
  :restore-returned true, :probe-output-satisfies-its-own-protocol true,
  :print-sink-still-ok true, :print-still-armed true}` — the one replaced Var
  named and left alone, 1075 other armed roots round-tripped untouched.
- Both probe namespaces were removed afterwards; `(count (instrument/instrumented))`
  returned to 1075.
- Adoption: `bin/seon init --dev default --changed src/seon/instrument.clj`
  converged at `:current-src` commit `6aaabc96-1e0b-5678-bf9d-fd6b08567304`,
  digest `8b5da88dddfc189eccb1899585e21a4e2988ba16523ac1521b6dd8c4dc54418b`.
  Two earlier attempts refused first for a non-literal `:malli/schema`
  (a `def`-referenced form is not EDN-readable at analysis) and then for a
  concurrent `:current-src` head change; both are recorded here because they
  are the shape of a first adoption after adding a public function.

## What this lane did to the shared JVM

`(require 'seon.instrument :reload)` was evaluated once in `default`'s JVM to
exercise the new functions before adoption; the subsequent `init --dev`
adoption re-armed from the publication. Two disposable probe namespaces were
created and removed. `seon.print` was never reloaded in `default`. `default`
was not stopped, restarted or reforked.

## Verification boundary

In-process on `default` only: the one regression above, plus the two live
probes. No test JVM was launched. The cold gate over
`seon.instrument-test`, `seon.cluster.boot-test`, `seon.turn-test`,
`seon.turn-loop-test` and `seon.web.jvm-test` is the proof that the six
attributed errors are gone.
