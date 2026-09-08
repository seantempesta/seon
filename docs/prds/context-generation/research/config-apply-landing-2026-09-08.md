---
type: research
date: 2026-09-08
status: complete
tags: [config, operator, test]
---

# Config apply landing — 2026-09-08

The config apply defect is fixed and proven on `default`. The final owned-file
gate completed with worker-exchange errors, detailed below. Read `AGENTS.md` and
`docs/prds/context-generation/plan/agent-record-and-turn-loop-prd-2026-09-07.md`
end to end, including the lane rules and default-cluster requirement. No
protected turn-cut file was edited.

## Changes and dependency ledger

- `script/seon/fresh_operator.clj` sends an absolute path string to public
  `seon.config/apply!`, with public `seon.operator/connection` supplying custody.
  Manifest symbols never enter executable prepl source. Config apply and
  `start --config` resolve relative paths from the working directory.
- `src/seon/config.clj` adds a contracted file arity to the existing `apply!`.
  Its reader admits exactly one EDN map. A document equal to the shipped
  document selects the existing defaults compiler and initialization; other
  files retain sparse-overlay validation. No second reconciliation mechanism.
- `test/seon/dev/fresh_operator_test.clj` covers relative/absolute file selection
  and extends the real init/start drill with config apply, complete read-back,
  and adoption convergence. Its prepl selectors use Datahike's `:*` wildcard,
  which syntax quoting cannot qualify into `clojure.core/*`.
- Gate repairs: stringify the operator's store path at the string-contract
  caller; expect current instrumented config refusals and status diagnostics;
  make the reload drill verify refusal of deliberately damaged call facts,
  then reload their owner and verify publication; retain the original
  instrumented root that `with-redefs` restores and restore schema registration.
  The wrapper test took 611 ms, so its obsolete long-test classification was
  removed and the bare gate now covers it.
- Source-preparation prerequisite: `seon.id/symbol-in` now names
  `:seon.id/character`, declared as Malli's built-in `char?` in EDN. Inline
  metadata normalizes predicates to qualified symbols, while Malli registers
  the unqualified built-in name. No new predicate or generator was needed.
  Character acceptance, string refusal, and seeded character generation were
  verified in the default JVM; `seon.id-test` is included in the subject gate.

Read dependencies: `reference-code/babashka/fs/src/babashka/fs.cljc:179`
(`absolutize` uses Java `Path.toAbsolutePath`),
`reference-code/clojure/src/jvm/clojure/lang/Compiler.java:2456`
(quoted constants),
`reference-code/datalog-parser/src/datalog/parser/pull.cljc:65`
(the three wildcard spellings),
`reference-code/malli/src/malli/instrument.clj:18`
(instrumentation replaces Var roots), and
`reference-code/malli/src/malli/core.cljc:2930` (the character predicate).
First-party owners remain `seon.config`'s reader/compiler/reconciler and
`src/seon/operator.clj:154`'s public connection selector.

## Live proof

The default cluster was reforked after acquisition refused a malformed temporary
turn-cut program row. The prescribed Juniper fixture was reseeded through MCP
JVM mode. No provider call was made.

```text
bin/seon config apply config/default.edn
● default config applied #:seon.reconcile{:converged? false, :operations 3}
bin/seon init --dev default
● current-src: development cluster converged
● :current-src commit 6aa04f2d-55d7-5cb0-8f09-cf9a2fd2e7c1 digest 417e207443e4d79a65cadc184cc267f7fa93e2633a90e62a4c0111118fceb1f9
```

MCP JVM mode, with no root/cluster arguments, evaluated
`(load-file "docs/prds/context-generation/research/config_apply_probe_2026_09_08.clj")`
in 691 ms. It returned 77 desired entries, complete decision equality, a true
symbol predicate for the search projection, and equal non-absent adopted and
published commit IDs:

```clojure
{:seon.config.probe/desired-count 77
 :seon.config.probe/decisions-equal? true
 :seon.config.probe/projection-symbol? true
 :seon.config.probe/adopted #uuid "6aa04f2d-55d7-5cb0-8f09-cf9a2fd2e7c1"
 :seon.config.probe/published #uuid "6aa04f2d-55d7-5cb0-8f09-cf9a2fd2e7c1"
 :seon.config.probe/converged? true}
```

The processor-count decision resolved to 18. This proves in-place development
adoption, not just a reloaded Var. After the character repair, development
adoption also converged at `6aa054d0-8169-50af-bf24-6e35714447e6`, digest
`96e70458fc08c2d3674043c5ad550fb0d0c4933d58d52d958e51060947a813d5`.

## Gate evidence

Initial subject run: 53 tests, 311 assertions, 9 failures and 1 error across
five tests. Those failures produced the repairs above. A task completion line
is not a verdict: the initial 189,600 ms config drill failed because syntax
quoting changed its selectors; the independent live probe used ordinary quoting
and passed. Full transient log: `tmp/config-apply-subject-retry.log`.

The first gate attempt stopped at a concurrent cache-link collision. The owner
superseded the original stop rule; index locks and adoption races were retried.
The next preparation failure was the character schema, now fixed. An overlapping
subject run later hit its 270-second drill bound; the overlapping subject and
platform runs were terminated and reaped, and a process census found no surviving
JVMs for their roots. The bare run reached and passed its 73-test platform tier,
then widened to 1,405 bulk tests; it was terminated during confirmations and has
no final suite tally. The standalone platform run was also interrupted, so it
is not claimed green. No full-suite flags were used.

The owner's final gating rule selected HEAD plus only owned files:

```bash
bin/test --paths script/seon/fresh_operator.clj src/seon/config.clj test/seon/config_test.clj test/seon/dev/fresh_operator_test.clj src/seon/id.clj resources/seon/schemas/seon.id.edn -- seon.config-test seon.dev.fresh-operator-test seon.id-test
```

Snapshot HEAD: `a6c4c8bd9b241a8d8c42bb4b2d1e81ca967fc954`; no owned-file
differences from HEAD. Final result: **exit 1; 54 tests, 16 assertions,
0 failures, 52 errors**, all classified by the runner as worker-exchange
failures. The config-apply lifecycle task and cached-boot task reached the
270-second completion bound; subsequent tasks report
`:seon.test.runner/worker-retired`. Isolated confirmations ran, but the runner
correctly retained the original exchange failures. This is not a green gate.
The cause behind the missing completion events was not established; no foreign
lane is blamed. Log: `tmp/config-apply-owned-gate.log`; retained failed root:
`tmp/test-runs/run.l0Rg2N`. The launcher exited and a process census found no
remaining JVMs belonging to this or the earlier interrupted gate roots.

Unfinished: obtaining a green automated lifecycle/subject verdict. Implementation,
issue resolution, and the independent live default apply/adopt proof are complete.
No background shell from this lane remains. HTTP verification also observed
`/agent/juniper` returning 200 and 97,775 bytes; no browser-paint claim is made.

## Commits and files

- `eb66a4b12`: operator/config data boundary and recurring regression.
- `8fb5365ec`: durable live probe and successful apply/adopt evidence.
- `f1ebd4754`: original wrapper and schema-registration restoration.
- `6b54338fe`: path contract, wildcard, and current diagnostic expectations.
- `fa1c0bd47`: resolve config apply and record the gate repairs.
- `bb82d1116`, `6502520f0`: character prerequisite, ending in the canonical EDN declaration.

Files touched: `script/seon/fresh_operator.clj`, `src/seon/config.clj`,
`test/seon/config_test.clj`, `test/seon/dev/fresh_operator_test.clj`,
`src/seon/id.clj`, `resources/seon/schemas/seon.id.edn`, this landing note,
`docs/prds/context-generation/research/config_apply_probe_2026_09_08.clj`,
and the resolved issues under `docs/seon/issues/archive/`:
`config-apply-fails-on-a-private-var-in-its-prepl-form.md` and
`unregistered-char-schema-blocks-source-preparation.md` (moved from the open directory).
The owner's issue index was not edited.
