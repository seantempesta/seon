---
type: research
status: slice-2-awaiting-orchestrator-proof
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

## Fork correction before slice 2

Owner correction: the first adoption after a fork must use the existing
convergence check. The live scratch host at `c96db3e94` confirmed that its
cluster row had `:seon.cluster/name` but no `:seon.source/commit-id`.
`registry/ensure-cluster!` and `reset-cluster!` now record that exact source
commit with the new cluster row. The same transaction writes the required
config row, produced by `seon.config/compile-manifest`; it adds no config
compiler or cache. `store/open-branch!`, `seon.db/transact!`, and
`store/release-branch!` are the existing connection/write seams. Boot's
`ensure-cluster-entity!` preserves the source fact. Datahike still creates
its branch from the immutable published commit; the cluster/config
transaction advances only the new branch. It does not re-index source.

The branch operation and subsequent row transaction are separate Datahike
operations, not one atomic operation across the store roster and branch.
A refused row transaction is reported rather than acknowledged as a
successful initialization. This change does not alter branch concurrency
or recovery semantics.

`fresh_operator.clj:2830–2889` already sends every named initialization to
an advertised live host's `prepl-eval!`. Its child JVM arm is reached only
without a live process; an alive but unadvertised process refuses. That
remaining child is the allowed cold-start cost. The existing transport selection needs no change for correction (b).

Live evidence uses only `tmp/one-jvm-redesign-root`, cluster `s`, PID 56462.
After reloading the registry, the fork plus its row transaction took
1,371.650 ms. Calling the existing `development-source-refresh!` against
that branch and the same published commit emitted only `development cluster
converged`; the whole inspection took 25 ms, and `:max-tx` did not change.
This is a direct JVM probe, not the complete `refresh-source!` regression.
The scratch host was downed before the armed fast run.

The extended adoption regression performs real publication and boot, then
calls `refresh-source!` with unchanged source. It observes calls to the real
writer and namespace reload owner on that thread, asserting zero calls and
positive convergence progress. The operator host regression reads the
fork's stored source commit; its branch head now includes the required
cluster/config transaction and therefore is no longer byte-identical to
the published head. Both tests retain their explicit long-test reasons and
bounds for full publication and boot.

An MCP read without the explicit cluster projection returned a validation
refusal that itself failed rendering. The repeated read with the supplied
projection succeeded; the diagnostic-render boundary is recorded in
[the existing MCP projection issue](../../../seon/issues/mcp-jvm-small-result-projection-fails-during-live-adoption.md).
The Markdown hook also reports the same two out-of-scope stale Datahike
pins listed in the slice 1 note. No foreign session or file was changed.

Fast iteration at snapshot HEAD `8fc5a6ed7`: run `a4aa660ce57e`, **3
executed, 44 assertions, zero failures/errors**, exit 0. The three owned
paths were `src/seon/cluster/registry.clj`,
`test/seon/cluster/publication_adoption_test.clj`, and
`test/seon/cluster/publication_host_test.clj`; the two test namespaces
were passed to `bin/test-fast --paths`. No cold gate was run.
Program digest `c5c927703cc82a423a1b12b520ec5e99822fd59c32814cc6ec2e295d3566d9f5`;
input digest `296eb62dba1f1bc499a5659f01ce549b74c02a8ca6ad3f3073b44719a63ee93f`.
The fixture/restoration test took 119.177 s; the new regression including
publication/boot took 147.799 s; the operator fixture took 162.372 s.
A virtual-thread-inclusive `jcmd Thread.dump_to_file -format=json` sample
of the sole test JVM showed the new regression awaiting the Datahike writer
from `seon.cluster/populate-source!`: full program population, not its
subsequent no-change assertion. Those full-fixture costs remain O(program).

The operator rows (client wall time) were live fork **5.156 s**, unchanged
`init` **1.960 s**, unchanged `--changed` **1.557 s**, first `--dev`
**2.232 s**, config **0.849 s**, status **0.847 s**, hook admission
**0.502 s**, invalid config **0.245 s**. Every request retained the host's
process records and launched no JVM. The erased core-fault diagnostic
appeared again; slice 4 still owns that recorded issue.

The first implementation missed the fork target. Its row writer's fallback invokes
`schema/projection-from-database`, whose `derive-projection-from-database`
reads every schema, function contract, and function source before this
small write (`src/seon/schema.clj:2705–2735`). The direct live probe supplied
the existing cluster projection and took 1.372 s; the ordinary operator
request has no supplied projection and took 5.156 s. This identifies the
O(program) fallback in the new path; it is not a measured breakdown of
that 5.156 s. The needed cost is the new cluster/config rows. The 2.232 s
no-change request still reads the source inventory and stored manifest;
its adoption now compares equal commit IDs and writes nothing. Slice 2's
snapshot/analysis measurements must separate those remaining reads.

No attributes change and no reset is needed for this correction.

The final operator request carries an existing running cluster's projection
only when its recorded source commit equals the immutable source commit
being forked. It captures that projection with `db/carry-projection-state`
and reads the commit through `db/pull`; the registry receives it on the
request. Boot similarly passes the projection already acquired by
`source-base!`. Refork passes the same value through its existing request.
There is no new cache or retained state. If no matching projection exists,
the source database remains the derivation authority. That less common
case still reads O(program) rows and needs further measurement.

With the final operator form, live `init fork-carried` completed its
lifecycle in 1.134 s. A separate `init fork-timed` through the same
`prepl-eval!` function returned Clojure's terminal `:ret :ms` of **890 ms**,
meeting the JVM-time target. Both forked commit
`6ab035cd-03b5-54e8-bf55-71e33f0f42e2` in the existing scratch JVM.
The final regression additionally observes the real projection owner and
asserts zero whole-program derivations for the forked branch.

Final fast request `2aea6e413827` recovered the final host regression's
recorded green after the terminal session was lost: **0 executed, 1 unchanged,
37 assertions, zero failures/errors**, exit 0. This is reuse, not another
execution. Its recorded program digest is
`6d04dbde59af66ba96286695df7c5487a601cfe131387de3193d6091588ab36c`, input
`45b1656b61157dbaf9ce6ebd4fe98d82e907d7fa593c219b7f6b1e66ef82b97b`,
basis `536870950`. The request selected all six production/test paths below.
The earlier adoption regression remains the 44-assertion run above; the
orchestrator still owns cold/platform proof.

| Path | Before | After | Net deleted |
|---|---:|---:|---:|
| `script/seon/fresh_operator.clj` | 155,975 | 157,158 | -1,183 |
| `src/seon/cluster.clj` | 187,314 | 187,390 | -76 |
| `src/seon/cluster/registry.clj` | 29,575 | 31,482 | -1,907 |
| `src/seon/operator.clj` | 52,873 | 52,963 | -90 |
| `test/seon/cluster/publication_adoption_test.clj` | 2,647 | 4,350 | -1,703 |
| `test/seon/cluster/publication_host_test.clj` | 8,062 | 9,084 | -1,022 |

Correction adds 3,256 production bytes and 2,725 test bytes; no cache is
introduced. Measure the first unchanged adoption after a fresh fork and
reset, plus live fork JVM time. The scratch host is down before load/test
JVMs. Source caching remains unchanged pending the required slice 2 probe.

Load proof: `clojure -Sdeps '{:paths ["src" "resources" "script"]}' -M`
required `seon.cluster.registry`, `seon.cluster`, `seon.cluster.source`, and
`seon.fresh-operator`, printed `:owned-namespaces-loaded`, and exited 0.
`git diff --check` passes.

## Slice 2 — measurement before deletion

Dependency ledger: `seon.fn.analyzer/invoke-kondo` calls
`clj-kondo.core/run!` with `:cache-dir` (`src/seon/fn/analyzer.clj:260–282`).
Read the vendored `clj_kondo/impl/cache.clj` and `impl/core.clj` end to end.
`cache/load-when-missing` (`:128–147`) keeps definitions produced by the
current lint and reads absent required namespaces from the disk/builtin
cache; `update-defs` (`:148–170`) writes fresh definitions only.
`sync-cache*` (`:172–211`) combines those definitions under the cache lock
(`sync-cache`, `:213–218`). Public `clj-kondo.core/run!` processes the
supplied files, then synchronizes that cache before linting usages
(`reference-code/clj-kondo/src/clj_kondo/core.clj:234–255`).
`impl/core.clj:595–614` visits the supplied lint paths. It does not discover
caller files for us: the published database's reverse `:seon.fn/calls`
edges select those files. Datahike's indexed attribute/value search uses
AVET (`reference-code/datahike/src/datahike/db/search.cljc:140–157`).
No second per-file analysis cache is needed for this operation.

Before deletion, at `5cf44da20`, scratch PID 60626, source commit
`6ab03d6b-43cf-592d-a941-146ecf716a6d`: the one-file `my.note` docstring
probe took **138.893 s** including preparation. The existing progress
reporter measured: input inventory **507.3 ms**; toolchain comparison
**28.5 ms**; forget-namespaces **0.7 ms**; known symbols **23.9 ms**;
changed-file analyzed-artifacts **83.8 ms**; candidate manifest **51.6 ms**;
published-index-rows **15,561.8 ms**; publication-inputs caller closure
**104,968.8 ms**; cache keys **39.1 ms**; cached-analysis reads **8.9 ms**;
additional analyzed-artifacts **14,991.6 ms**; replace-manifest-artifacts
**162.2 ms**; result cache keys **50.4 ms**; cache writes **68.5 ms**;
manifest return **20.1 ms**. The file digest walk was **25.6 ms**, below
the 112 ms comparison, while the complete publication snapshot took
**486.1 ms**: it also hashes schema/config/test inputs. The dominant
O(program) work is reading all published rows and reconstructing their
complete declaration graph with `tree-seq`, then following its transitive
closure. Additional lint consumes that broad closure instead of direct
callers. Cache I/O is small in this sample but is still the redundant layer
the owner ruled out. No deletion preceded these numbers.

Reproducer: load and invoke
[one-jvm-slice2-analysis-2026-09-22.clj](one-jvm-slice2-analysis-2026-09-22.clj)
through the advertised host's existing `prepl-eval!`, passing
`ROOT/data/clusters`, `"s"`, and an output filename. The client carries
`publication-bound-ms`, forwards `:out`/`:err`, and reads the terminal
`:ret`; no child JVM is used. Exact phase rows are preserved in
[one-jvm-slice2-before-2026-09-22.edn](one-jvm-slice2-before-2026-09-22.edn).
The probe restores the exact docstring bytes in `finally`. An initial
probe assertion mistakenly expected `source/current` to include a digest;
that function returns branch/commit only. The corrected probe reads the
source digest from that immutable database. No source edit happened in the
failed attempt.

Preparing the corrected publication before this probe took 173.895 s,
including 141.469 s analysis. The setup thread sample independently named
`declaration-targets` → `publication-inputs` → `build-manifest`. Other setup
phases above two seconds were source preparation (4.483 s: snapshot plus
manifest read/validation), reconciliation transaction (6.487 s: selected
program rows and their contracts), issue indexing (2.190 s: whole issue
inventory), activation seal (3.723 s: activation closure), and branch-head
readback (3.872 s: unresolved-call report). Slices 3–4 own publication and
adoption beyond analysis; those are findings, not accepted latency.

### Replacement and verification boundary

The replacement removes `publication-inputs`/`declaration-targets`,
`producer-paths`, `toolchain-digest`, `resolution-digest`,
`cached-analysis`, `cache-analysis!`, and `artifact-cache-key`.
`build-manifest` compares the existing per-file digests and queries direct
`:seon.fn/calls` referrers of declarations in those files in the published
database. The changed files and caller files enter one clj-kondo call.
There is no transitive graph walk.
`replace-manifest-artifacts` retains the other artifacts; removed paths are
excluded before replacement. No-change source performs zero lint, and only
absence of a previous manifest selects every source file.

The analyzer uses clj-kondo's own `.clj-kondo/.cache` with explicit
`:cache true`. The existing `forget-namespaces!` removes superseded namespace
entries for changed/deleted source, so a renamed or removed namespace cannot
answer from stale definitions. No Seon per-file artifact cache remains.
The cluster passes the previous manifest/database without an artifact-cache
existence check and no longer widens publication on a producer digest.

RESET NEEDED: `:seon.source/toolchain-digest` is removed from the schema,
manifest declaration, and source seal. The parity update subtracts that
attribute from the current oracle and updates the manifest form while
preserving the historical baseline. No bridge implementation changes.
`AGENTS.md` now states partial lint and the output-shape reset boundary.

The broad fast request selected `seon.fn-test`, `seon.cluster.source-test`,
and the three publication namespaces plus source evidence. Its errors in
unchanged carried-projection/contract-finding code are recorded in
[the program-graph projection issue](../../../seon/issues/program-graph-tests-do-not-carry-their-current-contract-projection.md)
and [the refusal propagation issue](../../../seon/issues/test-refusal-observations-overflow-in-projection-acquisition.md).
Those are explicit verification boundaries; no foreign source path or
session was edited to get past them. The canonical fixture's initial
population is O(program): a thread sample names `compile-index-transaction`
from `test-support/create-base`. That setup is separate from partial lint.

The broad run exited **143 by explicit lane termination**, not a harness
verdict. It observed six errors: four missing carried-projection calls,
one missing compiled function contract, and one undeclared refusal facet.
The sampled source test finished in **100.779 s**; the stop reached the
following complete-publication test, after an **18.326 s** contract
projection. No complete tally is claimed. These timings extend
[the existing commit-projection liveness issue](../../../seon/issues/full-publication-tests-exceed-liveness-while-compiling-the-commit-projection.md).
The direct-caller query uses the published graph's indexed file, function,
and call relations. The parity/load script passed: **3,358 schemas,
1,201 attributes, zero mismatches**, historical baseline unchanged.

Focused run `81ee2aeae0cc` executed **5 tests / 46 assertions / 2 failures /
1 error**, exit 1. Both failures reopen
[unchanged publication identity facts advancing the branch](../../../seon/issues/archive/unchanged-publication-identity-facts-still-advance-the-branch.md):
the third unchanged seal moves the head and forces a fourth recording
attempt. That existing assertion remains intact for slice 3. Its test took
322.147 s; the missing-activation regression passed in 104.565 s. Both
already declare their complete-publication work and bounds. The error was
the new regression's attempted unresolved-call fixture: clj-kondo correctly
refuses it before publication. That speculative case and the unnecessary
second lint pass were removed. Final selection is one lint batch over the
published graph's direct caller files and the changed files.
The selected-row indexing regression passed in 5.715 s; it now declares
its two real indexed publications and a long bound. The analyzer-config
input regression passed in 3.9 ms.

### Slice 2 bytes and seams

| Path | Before | After | Net deleted |
|---|---:|---:|---:|
| `src/seon/fn.clj` | 157,638 | 146,507 | 11,131 |
| `src/seon/fn/analyzer.clj` | 30,350 | 30,373 | -23 |
| `src/seon/cluster.clj` | 187,390 | 187,099 | 291 |
| `src/seon/cluster/source.clj` | 32,330 | 32,014 | 316 |
| `resources/seon/schemas/seon.source.edn` | 6,303 | 5,961 | 342 |
| `resources/seon/schemas/seon.fn.manifest.edn` | 1,050 | 969 | 81 |
| `test/seon/fn/publication_cache_test.clj` | 2,711 | 3,341 | -630 |
| `test/seon/fn/publication_test.clj` | 11,016 | 3,497 | 7,519 |
| `test/seon/fn/publication_toolchain_test.clj` | 5,171 | 1,655 | 3,516 |
| `test/seon/cluster/source_evidence_test.clj` | 12,956 | 12,651 | 305 |
| `test/seon/schema/datahike_parity.edn` | 1,577,169 | 1,576,598 | 571 |

Net deletion: **12,138 production/schema bytes; 11,281 test/fixture bytes**.
Documentation and executable evidence scripts are separate.
`caller-files` calls `seon.db/q` against the existing indexed call/file
relations; `build-manifest` calls `analyzed-artifacts` and the existing
`replace-manifest-artifacts` once. `invoke-kondo` calls the dependency's
`run!` with its existing namespace cache. The source seal simply stops
asserting the retired attribute. No production reference to the removed
cache functions, producer closure, or toolchain digest remains.

### Final focused iteration

Run `7878fbb649ae` passed **2 tests, 13 assertions, zero failures/errors**
with program digest `0936404923ad965f7a30cc995e2fc6648ab43e2bec40db608d51f0b510555181`
and basis `536870950`. The actual clj-kondo call proves cold/all files,
changed/direct caller files, and no-change/zero lint; partial artifacts equal
a separate complete analysis. The first test took 150.989 s including the
canonical fixture's O(program) construction; selected-row indexing took
7.219 s across two real publications and full database comparisons. Both
declare their long reason and bound. This focused green does not erase the
broader verification boundaries above.

### Slice 2 live after measurement

Fresh scratch publication used the final source bytes on `2b7819320` plus
this diff. Cluster `s`, PID 67012, advertised prepl 54387, forked published
commit `6ab045ed-599f-5899-915c-4f8871636265`. MCP runtime status answered
for that explicit root/cluster. The same bounded `prepl-eval!` probe
returned successfully and restored `src/my/note.clj` exactly.

| Phase | Before ms | After ms |
|---|---:|---:|
| Manifest call, sum of recorded phases | 136,579.843 | **1,140.922** |
| File digest walk | 25.617 | **42.798** |
| Complete publication snapshot | 486.130 | 596.938 |
| Input inventory | 507.279 | 129.131 |
| Direct caller selection plus projection/cache preparation | — | 680.494 |
| Selected file artifacts | 83.846 plus 14,991.566 additional | 207.736 |
| Actual clj-kondo `run!` | not separately wrapped | **78.770** |
| Manifest replacement | 162.219 | 91.220 |

The only lint path was `/Users/sean/src/seon/src/my/note.clj`; the real
regression above separately proves direct caller inclusion. The after
prepl form took 6.282 s including loading the probe and preparation; its
recorded phases sum to 5.909 s. Preparation alone took 4.127 s acquiring
the immutable source database and its projection: the current acquisition
derives schema from the complete publication, O(program), before supplying
the instance projection. That is an unresolved acquisition cost, not lint.
The complete snapshot still hashes source, schema, configuration and test
inputs, so it is wider than the 43 ms source-only digest walk. Exact rows:
[after evidence](one-jvm-slice2-after-2026-09-22.edn).

Cold analysis of all **381 files** took **15.846 s**: 15.368 s selected
artifacts, 123 ms inventory, 360 ms projection/cache preparation, 95 ms
manifest construction. This is O(all source) only with no manifest. It is
below this lane's earlier 17.445 s cold analysis, but above the owner's
10.2 s comparison; different runs are not a controlled CPU benchmark.
Cold publication wall time was **178.826 s**. Its phases above two seconds
remain findings: preparation 7.760 s (loading/acquiring publication inputs),
schema population 2.454 s (all declarations), program-row preparation
4.613 s (all program rows), contract compilation batches 12.879/9.499/2.610 s
(all function contracts), final population compilation 13.226 s (all
entities and keyword facts), transaction 36.068 s (107,049 datoms), issue
indexing 6.124 s (all findings), activation seal 3.440 s (complete activation
closure), and branch-head readback 4.875 s (whole unresolved-call report).
These O(program) publication operations remain for slices 3–4; no latency
is accepted merely because this slice does not own it. Raw progress:
[cold publication](one-jvm-slice2-cold-2026-09-22.txt).

A cold-process thread sample also found `DestroyJavaVM` waiting 44.20 s
with idle non-daemon Clojure agent executor threads. This is executor
expiry, not analysis; the existing
[idle-agent-thread issue](../../../seon/issues/archive/test-base-publication-waits-for-idle-agent-threads.md)
records the operator recurrence. Cold fork took 20.465 s including JVM
startup. Boot took 29.515 s plus preflight 11.589 s: dependency cache warming
9.502 s and lint of 11 changed files 2.085 s; those are source/dependency
input work at boot, not the live one-file call.

The orchestrator must reset for the removed attribute, run the cold and
platform gates, and repeat the publication-path script against this commit.
Compare actual lint lists and the analysis phase first, then attribute the
remaining publication/adoption time to the later slices. Do not interpret
the focused green as a green broader suite.

Final source load passed (`seon.fn`, `seon.fn.analyzer`, `seon.cluster`,
`seon.cluster.source`). `down` stopped PID 67012 and confirmed the store
lock free; after verifying no JVM referenced it, the lane deleted its own
scratch root. No worktree was created and no default/foreign root was
operated. Named authorities were read end to end as recorded above.

## Slice 3 — publication transactions, measured before editing

Read the new AGENTS.md **SECONDS, NOT MINUTES** owner law, the redesign
plan end to end, the unchanged-publication issue end to end, and the new
working-edge blocks through the reset/complete-tier report. The existing
data-oriented Clojure, Datahike, REPL and testing skills remain applied.

At `d5648c1da`, own root `tmp/one-jvm-redesign-root`, cluster `s`, PID
71495, prepl 54881: the common publisher's unchanged empty upsert took
**21.567 s** and moved `6ab048ea-651e-5aba-95f3-769b84445672` to
`6ab049e9-8f7a-5eae-b7fe-cbd3b857aee2`. Phases: preparation 516.8 ms;
unchanged seal setup 7,465.2 ms; issue indexing 4,033.7 ms; activation
seal 423.3 ms; branch publication/readback 9,116.4 ms. It did O(program)
projection acquisition, issue inventory and unresolved-call reporting for
an unchanged digest. The existing cluster shortcut instead took **1.854 s**
and kept the head. It still read and validated the entire manifest.

The actual one-file docstring publication took **45.842 s**, excluding
adoption. It processed **one input, five rows, three function contracts**;
the prior attribution of whole-program contract compilation to every
publication does not hold for this measured path. Larger intervals: source
build before analysis 7.894 s; after findings/before branch publication
12.428 s; reconciliation transaction 2.619 s; activation seal 7.283 s;
branch publication/readback 9.004 s. Contract projection was **0.335 ms**.
Step 2 must remove the measured whole-program work, not optimize that
already-small projection.

Reproducer: [publication probe](one-jvm-slice3-publication-2026-09-22.clj),
called through the existing bounded `prepl-eval!`. Evidence:
[unchanged seal](one-jvm-slice3-before-seal-2026-09-22.edn),
[cluster no change](one-jvm-slice3-before-nochange-2026-09-22.edn),
[one file](one-jvm-slice3-before-docstring-2026-09-22.edn).
The first probe omitted the supplied projection and was refused before
publication; the corrected call carries the instance projection. Two
probe reader mistakes were corrected before the docstring call executed.
The docstring was restored in `finally`.

Foreign boundary: the error lane began editing `error.clj`, `db.clj`,
`blob.clj`, `turn.clj` and their tests during setup. Live measurements use
a HEAD archive inside the own root, constructed like `bin/test:694–753`,
with existing dependency/cache links and no Git worktree. No foreign
source bytes enter that snapshot. Tests use the required HEAD-plus-owned
paths snapshot. The initial complete publication cost 198.873 s; snapshot
boot also rebuilt its dependency classes once (44.385 s). Those are the
explicitly authorized initial-index/boot operations, not edit latency.

Dependency ledger: Datahike `commit-as-db` loads an immutable commit and
retains the connection's cache identity (`reference-code/datahike/src/
datahike/versioning.cljc:469–492`); no transaction is needed to inspect
its source digest. `force-branch!` checks `:expected-current-commit` before
mutating (`:323–388`), preserving changed-publication conflict semantics.
`program/exact-replacement-tx-in` already computes changed owned attributes
and calls Datahike attribute retraction (`src/seon/program.cljc:1007–1040`);
step 2 will hand it only the changed declaration rows.

### Step 1 — unchanged means no population or seal

The common `source/publish!` compares the stored source digest before
opening any scratch branch, resolving population, computing an input
inventory or sealing. A match returns the current stored commit with
`:seon.source/built? false`. The cluster shortcut now precedes full
manifest reading/validation. An issue-only change cannot override the
no-transaction invariant; the issue documentation names `seon.issue/index!`
for explicit issue-only indexing. No new state or cache is introduced.

Step-1 fast run `5b4e2c650ac6`: **1 executed, 5 assertions, zero failures
or errors**, basis `536870921`; program digest
`832a410414d9ce7e5f3ab4dcf4ebfd7a06b20fa495ec3c1a89049095f9d4ad72`.
The complete initial publication took 136.688 s inside the explicitly long
fixture; its thread sample named schema-shape fingerprinting during full
contract population. The repeated publication called population, activation
and `db/transact!` **zero times** and retained its head. The existing
evidence/race test's unchanged-rebuild assertion now expects that same
recorded commit, not a new descendant; the broader race test has not been
rerun in this step. After the snapshot ran, only dead issue-note path
selection and indentation were removed from the tested production bytes.

| Step-1 path | Before bytes | After bytes | Net deleted |
|---|---:|---:|---:|
| `src/seon/cluster.clj` | 187,099 | 186,507 | 592 |
| `src/seon/cluster/source.clj` | 32,014 | 32,873 | -859 |
| `test/seon/cluster/source_evidence_test.clj` | 12,651 | 12,657 | -6 |
| `test/seon/cluster/source_nochange_test.clj` | 0 | 1,842 | -1,842 |

Step-1 after-commit live measurement (`8de7a8868`): the common unchanged
publisher took **12.653 ms**, retained commit
`6ab04a4b-d320-5bf6-af1c-d31214b05070`, and returned `built? false`.
The before measurement was 21,567 ms and moved the head. Evidence:
[step-1 after seal](one-jvm-slice3-step1-after-seal-2026-09-22.edn).

### Step 2 — declaration and attribute differences

The first live development probe reduced the one-file publication from
45,842 ms to 20,167 ms (sum of progress intervals). This is **not the 5-second target**
and is not a final armed-boot measurement: the three edited namespaces
were reloaded through the scratch host's prepl. Contract projection was
0.515 ms and five contract rows took 40.9 ms. Analysis selected-file work
was 64.706 ms; reconciliation 2,547.950 ms; activation sealing 4,355.498 ms;
source acquisition before analysis 6,608.016 ms. Further progress labels
separate manifest reading, manifest validation and database acquisition.

The file digest selects the affected artifacts. Their previous/current
rows then select changed declarations, excluding file-wide analysis
provenance as the sole cause of a declaration update. The existing stored
`:seon.program/analyzed-source-digest` is a **file** digest; the artifact's
`declaration-digests` hashes declaration signatures, not function bodies.
Neither alone may skip body edits. The regression therefore edits a body
and checks an unchanged same-file function and unchanged arity components.
`program/exact-replacement-tx-in` still owns retractions; the assertion map
now contains changed owned attributes and identity, so unchanged component
maps cannot allocate replacement children.

A repeated `artifact-by-path` call previously validated and searched the
whole manifest once per artifact. One local immutable map replaces that
O(files²) traversal. No retained cache is added. The published database now
gets its projection directly from `schema/projection-from-database`, rather
than building a second runtime projection and composing a delta. Activation
preflight receives the requirements already derived for that same database
instead of deriving them a second time. The O(program) unresolved-call
report remains a cold-publication result; changed publication retains the
writer's deletion refusal and uses the changed-file analysis findings.

Final focused fast run `d4462013e6c1`: **1 executed, 12 assertions, zero
failures or errors**, basis `536870921`, program digest
`6f24ffeb28a0c11b3dcd388ac9f22d853d95cdbf2149be89378022d99a098a72`.
The test took 37.510 s including first canonical fixture acquisition; its
existing long declaration names that initial construction. The preceding
run `891843500f00` passed 11 assertions in 51.334 s. The final assertion
identifies every changed entity carrying an installed identity attribute:
exactly the changed file and function, with unchanged arity entity IDs.
The existing cold regression remains
`seon.fn-test/fresh-population-flattens-with-its-supplied-declarations`;
it is not claimed rerun by this focused namespace invocation.

Foreign boundary: the fast snapshot used HEAD bytes for dirty `db.clj`,
`test/runner.clj`, `cluster/mcp_test.clj`, `test/runner_test.clj`, and both
`test_support` files. None of those checkout edits entered this proof.
The harness spent 137 seconds awaiting a repository JVM slot in the first
run; the scratch host was down throughout both runs.

| Step-2 production/test path | Before bytes | After bytes | Net deleted |
|---|---:|---:|---:|
| `src/seon/fn.clj` | 146,507 | 148,166 | -1,659 |
| `src/seon/cluster.clj` | 186,507 | 187,567 | -1,060 |
| `src/seon/cluster/source.clj` | 32,873 | 33,019 | -146 |
| `test/seon/fn/publication_test.clj` | 3,497 | 5,380 | -1,883 |

### Final committed-code measurement and remaining boundary

Production commit **`e71e0e4b6`** loaded successfully with
`clojure -M -e "(require 'seon.fn 'seon.cluster.source 'seon.cluster)"`
both before and after committing. The own-root snapshot's three production
files were compared byte-for-byte with that commit. A fresh branch of the
published program booted normally with instrumentation; no namespace reload
preceded the final measurement. Boot was 51.814 s, separately paid. Publishing
the lane's three remaining changed source files to establish the baseline
was 40.037 s; the following single-file edit was **17.537 s end to end through
the prepl, excluding adoption** (before: 45.842 s). Evidence:
[committed-code docstring publication](one-jvm-slice3-step2-after-docstring-2026-09-22.edn).

| Actual operation | Final ms |
|---|---:|
| Published manifest validation | 235.058 |
| Published database/projection acquisition | 3,007.488 |
| clj-kondo selected files | 171.602 |
| Contract projection | 0.439 |
| Contract-row progress intervals (five rows; three functions) | 70.478 |
| Reconciliation transaction | 2,312.867 |
| Changed-definition comparison | 108.064 |
| Issue indexing | 1,089.369 |
| Activation seal, transaction and final deletion validation | 4,830.735 |
| Branch head/readback | 66.609 |
| Artifact write | 566.700 |

The reporter records the interval following its previous label: in the raw
EDN, `published manifest read` precedes validation, and `published manifest
validation` precedes database acquisition. The table names the actual
operations between those transitions. Source-build/manifest-read work was
1,428.116 ms; the analysis input inventory was 202.075 ms, still above the
112 ms comparison walk and not presented as that walk's equivalent workload.

**The ≤5 s target is not met.** The three phases above 2 s are explained by
remaining whole-program algorithms, not by an acceptable-duration claim:

- Published database acquisition queries/parses all schemas, function contracts
  and source-admission rows and builds their projection. The current caller
  supplies no previous immutable projection. `schema/projection-from-database`
  has an explicit reusable-projection arity, but still queries all rows; carrying
  the existing connection projection across publication is the existing seam
  to improve, not another cache.
- Reconciliation submits only changed attributes, but the database writer's
  `write-report-error` invokes `arity-mismatches-with` over the whole program.
  Its affected entities and reverse call edges can bound that work to the
  change and callers. `db.clj` is explicitly another lane's owner.
- Activation derives the complete schema projection and closure, then runs
  another writer transaction and `db/deletion-error`'s whole-program identity
  comparison. The duplicated requirements derivation is gone; the remaining
  complete derivation and validation have not become incremental.

These findings extend
[the existing projection/publication issue](../../../seon/issues/full-publication-tests-exceed-liveness-while-compiling-the-commit-projection.md).
They are unfinished performance work, not proof that O(change) is impossible.
The orchestrator must measure the complete script at `e71e0e4b6` and gate the
changed publication, unchanged-head and existing cold-population regressions.
No cold gate or platform proof is claimed by this lane.

The final unchanged common publisher took **13.483 ms**, returned `built? false`,
and kept commit `6ab0520a-90f2-5e2d-a03a-407cc19ccf22` exactly:
[unchanged-seal evidence](one-jvm-slice3-step2-after-seal-2026-09-22.edn).
The probe's separate artifact-reading preparation was 502.680 ms.

Cleanup: `bin/seon --root tmp/one-jvm-redesign-root down` reaped PID 84063
and confirmed the store lock free. With no JVM referencing it, the own
scratch root and source archive were removed without following symlinks.
No foreign root or session was operated.
