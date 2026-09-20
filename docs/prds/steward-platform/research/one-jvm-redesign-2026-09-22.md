---
type: research
status: slice-1-awaiting-measurement
created: 2026-09-22
tags: [publication, adoption, one-jvm, measurement]
---

# One JVM publication redesign

Slice 0 deleted the loaded-producer guard and required a reset for removed
attributes. That reset and its measurements were accepted at `cd701afc2`.
Slice 1 below removes child JVMs from requests to a running root; the
orchestrator owns its cold gates and comparative measurement.

Read end to end, in order: `AGENTS.md`;
[the redesign plan](../plan/one-jvm-publication-redesign-2026-09-22.md);
[the working edge](../plan/unsettled.md) from “2026-09-22 ~14:00 local” to
its end;
[the loaded-producer issue](../../../seon/issues/the-loaded-producer-guard-refuses-a-freshly-booted-host.md);
[the fault-message issue](../../../seon/issues/a-core-fault-whose-record-is-refused-loses-its-own-message.md).
The latter remains slice 4 work.

## Dependency ledger and existing calls

- Clojure `require :reload` loads the identified namespace even when loaded:
  `reference-code/clojure/src/clj/clojure/core.clj:6075–6086,6200–6205`,
  gitlink `b18d3adc5b5f4d5d0ccea966203fb67a614d5c3d`.
  `seon.cluster/load-development-definitions!` already calls it in
  `reload-order`. Slice 0 adds no replacement for the deleted guard.
- Publication still enters `seon.cluster/full-source-refresh!` and
  `seon.cluster.source/publish!`; adoption still enters
  `seon.cluster/development-source-refresh!`, reloads definitions, applies
  Malli instrumentation and acquires the SCI context before recording the
  adopted commit. The later slices own reducing their work.
- Boot's `seon.fresh-operator/launch-form` still calls `start-cluster-form`
  and `instrument-form`, then announces readiness. The input digest read
  and generation-recording transaction are gone.

## Inherited state and deletion

`bin/seon status` and MCP runtime status answered: `default` alive, PID
30170. One read-only JVM evaluation observed the loaded guard Var, loaded
host `6debe4994826`, and a retained loaded database. No reload, publication,
reset or other mutation was requested on `default`. Hook publication was
already disabled and is unchanged.

Removed the four guard functions, all callers, input-digest reads used only
by recording, the live-only refusal requiring a producer manifest, and all
13 schema declarations exclusive to the guard. The malformed-manifest check
still determines whether a previous manifest is usable for incremental
analysis. Removed the guard-only test and guard setup/assertions from the
existing boot/publication and adoption tests. Both retained boot tests keep
their existing explicit 600,000 ms bound and reason.

The foreign unstaged progress callback in `seon.cluster/full-source-refresh!`
is preserved and excluded from this commit. Fast `--paths` snapshots include
that line because they overlay whole files; this is an explicit difference
between that iteration and the committed bytes.

The schema parity fixture's current capture removes only the deleted keys,
the corresponding native attributes, and updates its input digest. Its
historical `:seon.bridge.parity/initial-baseline` is retained unchanged as
an EDN value. No bridge output is regenerated from the implementation.

## Verification and measurement boundary

The assignment's exact bare command loaded `seon.cluster` and
`seon.cluster.source`, then failed to locate `seon.fresh-operator`:
`deps.edn:5` puts only `src` and `resources` on that classpath; the operator
lives in `script`. Verification supplies `script` explicitly, without
changing the repository classpath.

Fast iteration, load verification and exact deletion counts follow below.
The orchestrator must reset for the removed attributes, run the cold named
and platform gates, and run
[measure-publication-path-2026-09-22.sh](measure-publication-path-2026-09-22.sh)
from zero through first adoption. Record all measured cases; the immediate
acceptance is a freshly booted host reaching adoption without the deleted
refusal. No performance improvement or live adoption is claimed by this lane.

## Exact UTF-8 byte counts

Sizes compare the pre-slice HEAD bytes with the intended committed bytes;
the foreign progress callback is excluded from the `cluster.clj` count.

| Path | Before | After | Bytes deleted (net) |
|---|---:|---:|---:|
| `src/seon/cluster.clj` | 199,651 | 187,314 | 12,337 |
| `script/seon/fresh_operator.clj` | 158,364 | 157,762 | 602 |
| `resources/seon/schemas/seon.source.edn` | 8,529 | 6,303 | 2,226 |
| `test/seon/cluster/publication_test.clj` | 3,144 | 328 | 2,816 |
| `test/seon/cluster/publication_host_test.clj` | 2,505 | 1,142 | 1,363 |
| `test/seon/cluster/publication_adoption_test.clj` | 3,503 | 2,451 | 1,052 |

Production deletion: 15,165 bytes net. Test deletion: 5,231 bytes net.
Documentation and the schema parity capture are accounted separately.

The mechanical subtraction is reproducible with
[the parity evidence script](one-jvm-slice0-parity-2026-09-22.clj). It reads
the pre-slice declaration keys from `a70995402` and never derives surviving
expected native attributes from the new bridge.

## Fast iteration result

```sh
bin/test-fast --paths src/seon/cluster.clj script/seon/fresh_operator.clj \
  resources/seon/schemas/seon.source.edn \
  test/seon/cluster/publication_test.clj \
  test/seon/cluster/publication_host_test.clj \
  test/seon/cluster/publication_adoption_test.clj \
  -- seon.cluster.publication-test seon.cluster.publication-host-test \
  seon.cluster.publication-adoption-test
```

Exit 0. Recorded run `27f633bcb814`: **4 executed, 0 unchanged, 12 assertions,
0 failures, 0 errors**. Arming: 1,478 instrumented Vars, 1,471 program-armable.
Snapshot HEAD `a70995402`; program digest
`d3a709a2441a17be18fbb422d7d7cc8286ccf0eff85666a6200609f8a4ba9088`,
input digest `4b641d53b9726a5bd802aaa1b7fe6e3fb830fb5208b4236c083441a3ba0806d0`.
The runner used published base `e8cb1a8c76cfe6b393cf4b1a167ff815b1dbd56ef90d15c2373fa7fa53635411`,
reported 173 commits behind HEAD. It executed the selected tests rather than
reusing earlier greens. The snapshot root was removed by the launcher.

Observed BEGIN/END intervals: malformed manifest 0.203 s; fresh-host
publication 290.619 s; unchanged restoration test including first canonical
fixture construction 187.483 s; fresh-host adoption 350.983 s. These are armed
test intervals, not the orchestrator's publication-path measurements. The two
modified boot tests retained their explicit 600,000 ms declarations.

The adoption fixture emitted the existing erased fault with
`:malli.core/invalid-schema`; passing its commit-id assertion does not prove
that fault healthy. The occurrence is recorded in the existing
[fault-message issue](../../../seon/issues/a-core-fault-whose-record-is-refused-loses-its-own-message.md).
The fixture-construction observation is recorded in the existing
[fixture-silence issue](../../../seon/issues/the-cold-fixture-base-outruns-the-liveness-silence-backstop.md).

Static lint: 0 errors, 69 warnings on unchanged lines. The first invocation
lacked the cached `seon.dev.clj-kondo/clj-kondo-output-config` declaration;
including `script/seon/dev/clj_kondo.clj` in the lint inputs resolved it.
No reference was rewritten to satisfy the cache. `git diff --check` passed.

## Schema parity capture

The current capture changes 3,372 forms to 3,359, and 1,209 native attributes
to 1,202. Removed stored attributes: `:seon.source/loaded-host`,
`loaded-producer-digest`, `loaded-producers`, `producer-namespace`,
`producer-path`, `producer-digest`, and `requested-producer-digest` (all in
`seon.source`). The historical baseline is equal to its pre-slice EDN value.
Current input digest:
`f1bdd8aa0306abdd7dcc87beec4a8c926b4dc3020fbcf127bc6f0003d045991b`.
The fixture file shrinks from 1,580,246 to 1,577,169 UTF-8 bytes: 3,077 bytes
net deleted. That capture update occurred after the fast run; its independent
comparison uses the evidence script below, not the preceding fast tally.

```sh
clojure -Sdeps '{:paths ["src" "resources" "script"]}' -M \
  docs/prds/steward-platform/research/one-jvm-slice0-parity-2026-09-22.clj
```

The script exited 0: **3,359 schemas, 1,202 native attributes, zero
mismatches** against the current declaration projection. All three requested
namespaces loaded in that same JVM. No schema or production change followed
that verification. The final source load command, with the required operator
classpath, is:

```sh
clojure -Sdeps '{:paths ["src" "resources" "script"]}' -M -e \
  "(require 'seon.cluster 'seon.cluster.source 'seon.fresh-operator) (shutdown-agents)"
```

Slice 0 stops here. RESET NEEDED; cold gates and the publication-path
measurement remain the orchestrator's next action.

## Slice 1 — one JVM for requests to a running root

Slice 0 was accepted at `cd701afc2`; the orchestrator's reset converged,
including adoption in 122.8 s. The accepted measurement rows in the working
edge's 19:50 block are: from zero 206.3 s, cold fork 27.5 s, boot 38.3 s,
no change 128.4 s, non-core docstring 350.4 s (analysis 224.5 s), and core
docstring 211.7 s. This slice does not change analysis, publication
transactions, reload selection, or SCI acquisition.

The redesign plan was reread end to end, followed by the new working-edge
entries. The slice 0 authority reads above still apply. Skills used:
`data-oriented-clojure`, `repl`, and `clojure-testing`.

### Existing calls and deletions

- `bin/seon` already executes Babashka, and the hook's
  `publish-source-paths` already invokes that command. Their publication
  requests use `seon.fresh-operator/prepl-eval!`; neither needs a new
  transport. Clojure's `prepl` emits exactly one terminal `:ret` with elapsed
  `:ms`, and `io-prepl` prints its value (`reference-code/clojure/src/clj/clojure/core/server.clj:194–295`).
  The existing socket silence bound remains at the send/read boundary.
- Deleted the per-adoption `ensure-dependency-cache!` invocation and
  `with-test-classpath-form`. Cold `launch!` now supplies the existing
  resolved `:seon.dev-cache/test-classpath` to `child-jvm-command` once.
  `dev-cache/test-classpath!` already derives its paths and dependencies
  through tools.deps (`dev_cache.clj:461–500`). The cluster retains its
  existing JVM options; test-only JVM options are not copied into boot.
- Deleted `publish-before-fork?` and every corresponding argument and
  branch. A named initialization reads `seon.cluster.source/current`, then
  calls `seon.cluster.registry/ensure-cluster!` (or the existing refork
  owner for `--force`). `ensure-cluster!` calls the existing `branch!` with
  the published commit ID (`src/seon/cluster/registry.clj:224–249`). Datahike
  writes the selected database under the new branch and then updates its
  roster (`reference-code/datahike/src/datahike/versioning.cljc:224–280`).
- Named initialization selects a live advertisement directly, like
  publication. It no longer starts an offline roster JVM before the cold
  fork JVM. A live recorded process without an advertisement refuses
  instead of launching another JVM at its store.
- Deleted `init`'s shell preflight and duplicate clj-kondo cache preparation.
  Publication's existing stable-snapshot analysis validates source inside
  the addressed JVM; a named fork consumes stored facts. Cold start and
  reset retain their source preflight before boot or destruction.
- `prepl-value!` refuses a returned error map with its complete data.
  `config!` uses that reader; detailed status no longer catches and
  replaces a prepl refusal with successful console output. `init!` also
  recognizes the current `:seon.error/at` shape. The command's existing
  outer handler prints the map and exits non-zero.

The boot regression is extended in `publication_host_test.clj`, rather
than adding another publication fixture. It boots in the existing test
JVM, registers that actual process identity, and invokes the Babashka
operator against its advertisement. Its observation wraps
`babashka.process/process`, calls the original function, and saves each
returned record's command and PID. Assertions cover both the absence of a
Clojure/Java subprocess and unchanged, nonempty host process records.
Timing is printed as evidence, not asserted. An invalid config additionally
must print its error map and exit non-zero. The fixture explicitly carries
its ephemeral owner to the child and removes its own claims at teardown.

The old launch regression still asserted slice 0's deleted input recording;
that assertion is removed while retaining the Clojure launch-form compilation
size check. All callers of the reduced `init-form` arity are converted.
The obsolete scoped-loader test is deleted; the launch argv test now expects
the resolved classpath at boot. `AGENTS.md`'s classpath claim is updated with
that change.

### Verification boundary

No command operates `default`. The foreign progress callback in
`src/seon/cluster.clj` is neither edited nor selected by this slice's fast
snapshot. The bridge lane's schema/database/fixture changes are excluded;
the first snapshot is HEAD `f08558cb5` plus this lane's explicit paths.
No cold gate is run by this lane.

The measurement script's existing `fork` case precedes `start`, so it has
no running JVM and exercises the allowed cold path. Measure a second
`init NAME` after `start` for the live fork target of less than one second
of JVM time. The existing script's no-change/non-core/core rows remain the
comparison for the removed per-command JVM cost. The orchestrator must also
verify a fresh boot acquires test-alias dependencies without a separate
loader and run the named cold and platform gates before releasing slice 2.

The hook's separately configurable `run-schema-admission` also contained a
JVM launch. It now calls the existing `live-root-value!` with
`seon.schema.admission/admit` and the same request. With no live host it
uses `seon.operator.state/run-process!` under the existing operator silence
backstop. The hook's disabled schema-admission setting is unchanged. The
host regression enables it only in its own fixture configuration and checks
its real subprocess records alongside the operator requests. No schema
implementation is changed.

The first fast run exposed a fixture setup error: the new Babashka client
did not carry the fixture JVM's ephemeral-owner identity. The operator
correctly refused the first fork before sending it. The corrected fixture
claims its root as ephemeral and passes `SEON_OPERATOR_EPHEMERAL_OWNER_PID`;
no production ownership rule was weakened. That initial failure is not a
fork timing result. The existing boot/publication test passed in 183.5 s;
canonical fixture construction accounted for 121.3 s in the loaded-definition
restoration test, which now declares that reason and its long-test bound.

The shell deadline regression passed in 177.3 s. Its physical-store fixture
performs a standalone complete publication in the fast harness; a thread
sample reached `seon.issue/index-tx`. The shell tests now declare that work
and the long-test bound while retaining their physical blob/process
observations. This extends the existing
[full-publication fixture issue](../../../seon/issues/full-publication-tests-exceed-liveness-while-compiling-the-commit-projection.md),
not the protected fixture implementation. Test duration declarations are
not a publication performance claim.

A background turn-completion diagnostic failed its input contract after the
adoption test had completed: `seon.turn/turn-completion-error` at line 5008
omits the required base observation members. This is recorded in the existing
[scratch backstop issue](../../../seon/issues/scratch-debug-feed-and-turn-backstops-after-adoption.md).
It is outside slice 1 and does not establish the cause of the earlier erased
boot fault. A passing assertion tally must not be read as absence of that
background failure.

The shell working-directory refusal regression also failed under armed
contracts: `seon.fs.jvm/stat` returned a map missing `:my.fs/path` before
`seon.shell.jvm/cwd-path` could inspect it. The production filesystem owner
and this test body were unchanged. The exact boundary is recorded in
[the stat refusal issue](../../../seon/issues/filesystem-stat-refusal-fails-its-return-contract.md).

The Markdown hook reports two unrelated stale Datahike citations in
`wave-3a-task-family-spec-2026-09-21.md:737` and
`wave-3bc-render-pairs-and-template-proofs-spec-2026-09-21.md:750` under this
PRD's `plan/` directory. Both cite the SCI pin for Datahike. The existing
[pin-validation issue](../../../seon/issues/archive/datahike-current-pin-statements-drifted-again.md)
owns the implemented checker; those plan files are outside this slice.

The first run recorded **20 executed, 0 unchanged, 87 assertions, 1 failure,
3 errors**, run `0b0cd41bc68d`. One failure and one error were the corrected
operator fixture setup; the other two errors were the filesystem refusal
above and
[the shell timeout return contract](../../../seon/issues/shell-timeout-refusal-is-not-admitted-by-run-contract.md).
The latter reports an undeclared `:seon.effect/handler-failed-error` facet;
its underlying handler failure is not established by that outer diagnostic.
The seven other shell tests passed. No shell implementation is changed.

### Slice 1 exact production bytes

| Path | Before | After | Removed lines' bytes | Added lines' bytes | Net deleted |
|---|---:|---:|---:|---:|---:|
| `script/seon/fresh_operator.clj` | 157,762 | 155,975 | 5,450 | 3,663 | 1,787 |
| `bin/seon-hook` | 85,647 | 86,099 | 1,075 | 1,527 | -452 |

Production totals: **6,525 bytes removed, 5,190 added, 1,335 net deleted**.
Counts use UTF-8 file bytes and `git diff --unified=0` content lines, including
newlines and excluding diff headers. `bin/seon`, `src/seon/cluster.clj`,
`src/seon/cluster/source.clj`, and the process-record/advertisement owners
have no changes in this slice.

### Slice 1 recorded iteration and measurements

The corrected snapshot at `c5016e014` recorded run `f96ad7874009`:
**8 executed, 0 unchanged, 70 assertions, 0 failures, 0 errors**, exit 0.
It armed 1,474 Vars, including all 1,471 program-armable Vars.
Program digest: `1effbaf5da3102551a8a8b9db35ed2b8e53eee33acabaad49d996fa862649135`.
Input digest: `296eb62dba1f1bc499a5659f01ce549b74c02a8ca6ad3f3073b44719a63ee93f`.

```sh
bin/test-fast --paths bin/seon-hook script/seon/fresh_operator.clj \
  test/seon/cluster/publication_host_test.clj \
  test/seon/cluster/publication_adoption_test.clj \
  test/seon/dev/fresh_operator_test.clj \
  test/seon/dev/fresh_operator_reset_test.clj \
  test/seon/dev/source_instrumentation_test.clj \
  test/seon/dev/hook_test.clj test/seon/dev/publication_test.clj \
  test/seon/dev/publication_launch_test.clj test/seon/shell/jvm_test.clj \
  -- seon.cluster.publication-host-test seon.cluster.publication-test \
  seon.dev.publication-test seon.dev.publication-launch-test seon.dev.hook-test
```

All rows below include the Babashka client's wall time. They use the same
live test JVM and unmodified source bytes; they are not docstring-edit
measurements. Each successful row asserts unchanged nonempty host process
records and no Clojure/Java launch in actual returned subprocess records.
The fork additionally asserts the exact previously published Datahike commit.

| Request | Seconds |
|---|---:|
| `init NAME` live fork | 0.439 |
| `init` no change | 6.002 |
| `init --changed src/my/note.clj` unchanged bytes | 3.160 |
| `init --dev NAME --changed src/my/note.clj` | 116.921 |
| `config apply NAME config/default.edn` | 0.977 |
| `status --verbose` | 0.388 |
| hook schema admission | 0.516 |
| invalid config, error map printed and exit 1 | 0.250 |

The fork's entire client wall time is below one second, bounding its JVM
work below that target. The orchestrator still measures the non-core and
core docstring cases with the committed measurement script; no such timing
is inferred from these unchanged-byte requests. The existing erased fault
was emitted during adoption even though the adopted-commit and process
assertions passed; slice 4 still owns its diagnostic fix.

After the recorded snapshot, the test's subprocess observation keys were
changed to existing namespaced keys (`:seon.operator.subprocess/argv` and
`:seon.boot/pid`), and an unused clj-kondo mock/import was deleted from the
publication unit test. A native Babashka process record round trip verified
the final keys against a real `true` process. No production bytes changed
after the green snapshot. The long-test declarations account for measured
fixture construction; shell/adoption bodies are unchanged.

The complete `fresh-operator-test`, `fresh-operator-reset-test`, and
`source-instrumentation-test` namespaces were not run in this lane because
they include nested cold JVM launches. Their reduced-arity callers were
reviewed; the five generated `init-form` cases from
`generated-init-compiles-before-runtime-owners-are-loaded` were instead
compiled in a single cold JVM before any cluster namespace loaded. That
same JVM then required `seon.cluster`, `seon.cluster.source`, and
`seon.fresh-operator`; it printed both `cold-init-compilation-passed` and
`owned-namespaces-loaded`, exit 0. The operator classpath needs `script`, as
recorded for slice 0. The load check is repeated against the committed HEAD.

Final clj-kondo: zero errors, 74 warnings. `git diff --check` passes. The
stopped setup attempts and completed fast runs removed their snapshots;
no default operation, cold gate, or foreign-session operation was performed.

### Slice 1 test bytes

| Path | Before | After | Net deleted |
|---|---:|---:|---:|
| `test/seon/cluster/publication_host_test.clj` | 1,142 | 8,062 | -6,920 |
| `test/seon/cluster/publication_adoption_test.clj` | 2,451 | 2,647 | -196 |
| `test/seon/dev/fresh_operator_test.clj` | 98,686 | 97,876 | 810 |
| `test/seon/dev/fresh_operator_reset_test.clj` | 39,590 | 39,737 | -147 |
| `test/seon/dev/source_instrumentation_test.clj` | 5,158 | 5,538 | -380 |
| `test/seon/dev/hook_test.clj` | 9,814 | 9,808 | 6 |
| `test/seon/dev/publication_test.clj` | 4,482 | 4,348 | 134 |
| `test/seon/dev/publication_launch_test.clj` | 1,289 | 632 | 657 |
| `test/seon/shell/jvm_test.clj` | 19,491 | 20,817 | -1,326 |

Tests add 7,362 bytes net, principally the one real-host request regression
and explicit long-fixture declarations. Documentation is separate from the
production/test byte counts. There is no schema change in slice 1; a fresh
boot acquires the new initial test classpath. Stop here for the orchestrator's
cold gate, platform proof, reset/boot measurement, and remaining publication
rows before slice 2.
