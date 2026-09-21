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
| `src/seon/cluster.clj` | 187739 | 172013 | 15726 |
| `script/seon/fresh_operator.clj` | 158,364 | 157,762 | 602 |
| `resources/seon/schemas/seon.source.edn` | 5961 | 5640 | 321 |
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
| `src/seon/cluster.clj` | 187739 | 172013 | 15726 |
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
| `src/seon/cluster.clj` | 187739 | 172013 | 15726 |
| `src/seon/cluster/source.clj` | 35582 | 30240 | 5342 |
| `resources/seon/schemas/seon.source.edn` | 5961 | 5640 | 321 |
| `resources/seon/schemas/seon.fn.manifest.edn` | 1,050 | 969 | 81 |
| `test/seon/fn/publication_cache_test.clj` | 2,711 | 3,341 | -630 |
| `test/seon/fn/publication_test.clj` | 11,016 | 3,497 | 7,519 |
| `test/seon/fn/publication_toolchain_test.clj` | 5,171 | 1,655 | 3,516 |
| `test/seon/cluster/source_evidence_test.clj` | 12657 | 11325 | 1332 |
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
| `src/seon/cluster.clj` | 187739 | 172013 | 15726 |
| `src/seon/cluster/source.clj` | 35582 | 30240 | 5342 |
| `test/seon/cluster/source_evidence_test.clj` | 12657 | 11325 | 1332 |
| `test/seon/cluster/source_nochange_test.clj` | 1842 | 1545 | 297 |

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
| `src/seon/cluster.clj` | 187739 | 172013 | 15726 |
| `src/seon/cluster/source.clj` | 35582 | 30240 | 5342 |
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

## Slice 4 — transaction-report adoption (in progress)

Before measurement used an immutable source archive at `42660b9ca` under
`tmp/one-jvm-redesign-root/source`, its own root and cluster `s`, through the
operator's existing advertised-prepl client. A first cold initialization against
the changing shared tree refused source drift after 101.171 s; the fixed archive's
cold initialization completed in 309.189 s, fork in 23.694 s without a running
host, and boot in 62.109 s. The one-file docstring measurement completed in
139.859 s: program-row acquisition 31,046 ms, reconciliation transaction 22,676 ms,
whole-program definition comparison 27,103 ms, and the span from issue reconciliation through the subsequent projection/deletion reads 18,702 ms.
Reload was 16 ms, instrumentation 498 ms, SCI acquisition 1,308 ms. Publication
also spent 4,718 ms acquiring the published database projection, 2,509 ms reconciling rows,
4,403 ms indexing issues, 5,320 ms sealing activation, and 10,883 ms between branch
publication completion and schema reconciliation. These are measurements, not
accepted bounds: the first four adoption costs walk all declarations/issues when
the transaction report already identifies the changed entities.

Dependency ledger: Datahike's writer returns the committed transaction report
(`reference-code/datahike/src/datahike/writer.cljc:404–428`). It delivers the
transaction promise before notifying listeners; collecting reports in a listener
and reading an atom after `transact!` would race. The returned report is the seam.
Datahike `since` excludes its basis transaction (`db.cljc:150`), composes temporal
predicates (`db.cljc:680`), and `history` retains retractions (`api/impl.cljc:185`).
A missed publication therefore derives its changed identities from history since
the adopted source commit; no report cache or second durable history is added.

The issue writer requires a scoped arity in `src/seon/issue.clj`: its existing
unscoped `adopt!` interprets omitted issues as deletions. This clean file was
extended at its existing transaction owner instead of duplicating that writer in
`cluster.clj`. The foreign dirty boundaries observed were `src/seon/schema.clj`
and `test/seon/test/fixture_timing_test.clj`; neither is part of this slice.

The first fast run (`run.hbOZnR`, HEAD-plus-owned-paths) selected a published
base 18 commits behind HEAD. `activation-missing`'s four-argument call was
refused although the snapshot's authored function and contract both declare
that arity. The following existing source-publication test began complete
population; its own JVM thread sample showed `populate-source!` awaiting a
Datahike transaction after several minutes. The lane terminated that owned
run (exit 143), retained its evidence, and selected the new bounded regression
namespace instead. This is the existing
[`canonical-fixture-retains-old-function-contracts-after-adoption`](../../../seon/issues/canonical-fixture-retains-old-function-contracts-after-adoption.md)
verification boundary, not a green source-suite claim. No test-system file or
foreign session was changed.

Item 1 after measurement: 48,982 ms end to end, versus 139,859 ms before.
The published result carried the report identities for the three `my.note`
functions and its file row. The only reload was `my.note`; the only changed
armed roots were `my.note/add!`, `my.note/notes`, and `my.note/forget!`. Clojure
`require :reload` replaces those definitions, so the fresh roots must be armed;
unrelated wrappers retained object identity. The existing SCI fault recorder is
carried through acquisition and instrumentation rather than creating a new
callback (and therefore a different record-mode policy) on every adoption.
No-change was 1,738 ms, with the exact same source commit before/after and an
empty changed-wrapper set. The five-second edit and one-second no-change targets
are NOT met.

Remaining spans over two seconds (progress labels are preserved in the raw
output; several announce completion, not start): published database projection
acquisition 6,037 ms; row preparation before contract projection 2,176 ms;
publication reconciliation/report extraction 3,313 ms; publication issue indexing
2,733 ms; activation derivation/sealing plus the final deletion comparison
8,264 ms; adoption's published-database acquisition 6,097 ms; selected program
transaction followed by live projection derivation 6,372 ms; SCI acquisition
2,863 ms. Projection acquisition reads the complete stored declaration population
one row at a time (the concurrently held `schema.clj` owner is correcting that).
Issue publication parses the complete issue population. Activation derives all
schema/config/function membership again. The publisher's final deletion check
compares every program identity despite `db/write-report-error` already checking
affected entities through `write-deletion-error` and reverse AVET seeks
(`src/seon/db.clj:3924,4000,4055`). The writer also computes whole-program arity
mismatches after any affected entity (`:4061`). SCI's required base regeneration
reads the complete program. These identify remaining algorithms; none is an
acceptance of the elapsed time.

The focused fast run `2eb1d5bee425` ran three tests: namespace selection and
wrapper preservation passed; the report test had a fixture namespace omission,
subsequently corrected. The next fast run proves the remaining contract boundary:
the old canonical fixture refuses `published-index-rows`' two-argument arity
before its implementation runs. No unarmed result is substituted. The new
namespace regression uses four tiny source files, not a complete publication;
its declared long allowance names the measured first fixture acquisition plus
analysis (12.0 s). The last live measurement's JVM was downed before the explicit
Clojure load check.

Item 1 exact UTF-8 source bytes (diff payload, including line endings):

| Path | Deleted | Added | Net |
|---|---:|---:|---:|
| `src/seon/cluster.clj` | 187739 | 172013 | 15726 |
| `src/seon/cluster/source.clj` | 35582 | 30240 | 5342 |
| `src/seon/fn.clj` | 4026 | 3051 | -975 |
| `src/seon/issue.clj` | 1411 | 2372 | +961 |

Seams: `fn/report-identities` reads the report’s touched entities in before/after; `published-index-rows` selects those lookup refs; `program/exact-replacement-tx` remains the row writer; `source/changed-identities` uses native history for missed publications; `issue/adopt-tx` receives an identity scope; Clojure `require :reload`, `reload-order`, existing `instrument/apply!`, and SCI `acquire!` remain the execution owners. No schema attribute was added or removed. The explicit require of cluster/source/fn/issue exited zero. The orchestrator still owes the cold gate and its measurement-script row.

### Item 1 landing and item 2 decision

Item 1 landed as `245f693f6`. Retained evidence:
[before](one-jvm-slice4-before-2026-09-22.edn),
[after](one-jvm-slice4-item1-after-2026-09-22.edn),
[no change](one-jvm-slice4-item1-nochange-2026-09-22.edn).
The final fast snapshot at `926acd2e0` used a published graph 29 commits behind
HEAD. Run `b4ab7f05f54c` recorded three executed tests, five assertions,
zero failures and two errors: namespace/dependent selection passed;
wrapper preservation could not compile a loaded function contract; the report
test was refused by the fixture's one-argument `published-index-rows` contract.
The wrapper compilation error's offending function was not printed, so its
cause is not attributed. Its earlier pass and live wrapper evidence do not
turn this latest run green. [Full fast output](one-jvm-slice4-item1-fast-2026-09-22.txt).

The assignment permits stopping at this coherent seam for an item 2 design
decision. The decision is whether activation's stored membership remains a boot
contract or is deleted as duplicated program state. `activation-requirements`
(`src/seon/cluster.clj:1258`) enumerates every function, schema and config dial;
`activation-seal-tx` (`src/seon/cluster/source.clj:272`) stores those memberships
again for each changed digest. `require-activation!` (`src/seon/cluster.clj:1649`)
then requires that stored closure before boot. Deleting it is consequently a
boot/schema contract change, not just removing a slow publication call. No item 2
production edit has been made. Three options, priced by implementation scope:

1. **Keep the boot contract and transact only its membership difference
   (recommended, smallest scope).** Use publication report identities to update
   the existing closure; a changed schema/config input derives the affected
   requirements again. Cost: one owned implementation commit plus cold/changed/
   deleted-member regressions and measurement; no schema reset. Guarantee:
   preserve the current declared missing-member refusal. Tradeoff: retain the
   stored membership and its maintenance rather than dissolve that duplication.
2. **Remove the complete executable roster, retain declared boot prerequisites.**
   Boot checks its required entry points, schema/config and lookup facts directly;
   the database writer continues enforcing deletion integrity. Cost: coordinated
   activation schema, publisher and boot edits, replacement boot regressions and
   a reset. Guarantee: required boot inputs and surviving referrers remain checked.
   Tradeoff: no separate historical roster detects deletion of an unreferenced,
   non-boot function. This is a deliberate narrowing of the current boot promise.
3. **Remove the entire stored activation closure.** Derive boot requirements from
   the published database and packaged declarations at cold acquisition. Cost:
   the widest schema/boot/caller cut, reset, and cold admission proof. Guarantee:
   boot checks its current declared requirements without another stored roster.
   Tradeoff: remove the historical publication-membership check entirely and pay
   the complete derivation at cold boot; this does not itself optimize the writer.

All options still need the straightforward item 2 fixes: remove the publisher's
duplicate `db/deletion-error` scan (the writer already validates affected
identities through reverse AVET seeks), restrict unresolved calls to indexed
callee namespaces, and compare findings only for changed files. The protected
database writer's whole-program arity query remains a separate O(program) cost;
it must use affected declarations and reverse callers to meet the target. This
lane has not changed `db.clj`, schema ownership, or any test-system implementation.
The projection owner landed its changes during the final fast iteration; the
49-second live measurement predates that landing and must not be presented as
its performance. Item 3's core-fault message repair remains pending after item 2.

Cleanup at the item 1 stop: the own-root operator reported zero process records,
no JVMs and a free store lock. The completed fast and explicit-load sessions
were reaped; the scratch root/source archive was removed without following its
symlinks. Final explicit namespace load exited zero. The documentation hook
reported two pre-existing Datahike gitlink citation mismatches in the wave-3a
and wave-3bc plan documents; those foreign plans were not edited.


### Activation ruling: current-database derivation boundary

The orchestrator selected option 3 at `ed6540c17`: remove the closure, with
one-query boot derivation, and stop before production edits if the required
facts cannot be derived that way. No production or test edits were made in
this follow-up. The source inspection found the precise distinction:

- Current callable functions and functions with no incoming calls/references
  are queryable from current program facts. The old boot check does not ask
  whether a function is unreferenced.
- `closure-fact-missing` (`src/seon/cluster.clj:1583–1646`) instead subtracts
  current identities from the stored expected identities. It also asks whether
  every recorded initialization lookup resolves (`:1628–1636`).
- Those expected lookups are obtained by `derive-activation` from
  `config/default-population` (`src/seon/cluster.clj:1488–1495`). That function
  reads and admits the shipped file (`src/seon/config.clj:446–463`), rather
  than querying a durable declaration of initialization requirements.
- Config application transacts actual desired entities
  (`src/seon/config.clj:728–769`). Its digest covers effective config, not the
  initialization rows (`:758–759`). After both an initialization referrer and
  its target have been removed, their expected lookup has no current datom
  outside the closure. The same applies to a removed unreferenced function.

Thus two current databases with identical non-closure facts can have different
answers from the old check solely because their closures remember different
expectations. No query of those identical remaining facts can reproduce both
answers. Native history could recover former names, but does not distinguish
intentional removal from a missing requirement; it is not the requested query
of the current published value. Reading stored Clojure/EDN text and rebuilding
requirements is also not that query.

Option 3's deletion remains the ruling. Its permitted deletion of the old check
can remove this historical missing-name diagnostic, with the reason above;
there is then no equivalent boot derivation to time. A query reporting current
unresolved references or unreferenced functions is feasible, but has different
semantics. The stop is to identify which of those current-fact questions the
mandatory one-query boot derivation is meant to answer, not to preserve or
incrementally maintain the banned roster. No replacement cache or declaration
was proposed or added.

The attempted own-root cold baseline failed after 115,988 ms, before any
publication completed: `Keyword cannot be cast to Symbol` in
`seon.schema/projection-registry:478`, via `fn/add-contract-facts:2473`.
The trace enters Clojure `merge` and `PersistentTreeMap` comparison. This is
separate from the derivation decision, not the reason for stopping. The
[complete operator output](one-jvm-slice4-activation-before-2026-09-23.txt)
retains the refusal and progress phases. Analysis of 390 files was 29,644 ms;
schema population was 5,072 ms; preparation before contract projection was
15,346 ms. No successful before/after or one-query timing is claimed.

The own-root `down` reported zero records and a free store lock; its completed
launcher was reaped and the root removed without following symlinks. Foreign
edits in `src/seon/test.clj`, `src/seon/test/runner.clj`, and their tests were
preserved. No default operation, fast test or cold gate ran in this follow-up.

Explicit require of `seon.cluster`, `seon.cluster.source`, `seon.fn` and
`seon.issue` exited zero after the failed cold publication. The failure is
projection construction during population, not namespace loading.

## Slice 4 — activation dissolution, config owns the expectation (2026-09-23)

The owner's clarification supersedes the preceding semantic stop: the expectation
is the config manifest's qualified symbols, not a historical program roster.
`seon.config/require-functions!` derives `[config-key symbol]` tuples from the
compiled desired rows and performs one Datalog query against `:seon.fn/sym`.
Missing rows refuse with every symbol and its config key. The config writer's
existing `:db.fn/call` runs this check on its own database value; unchanged
reconciliation checks that same immutable value without transacting. Initial
publication checks its initialization rows at their existing population seam.

The activation producer, stored closure, lookup rows, boot closure reads, and
closure-specific regressions are deleted. The canonical config regression now
proves the actual requirement: a missing function refuses without moving the
basis; declaring it makes the same config reconcile. Armed fast run: 6
assertions, zero failures/errors (`config-fast-3.log` in the lane's temporary
work directory). The bootstrap compatibility check remains; current unresolved
calls remain a separate report.

RESET NEEDED: removed `:seon.source/activation`,
`:seon.source/activation-closure`, `:seon.activation/closure`, the stored closure
member attributes and `seon.activation.lookup` schema family. Transient lookup
refusals used by initialization still have their existing shape. No migration.

The foreign cold-publication failure is now corrected by the schema lane in
`2d9984a50`; its introducing `(merge forms contracts)` came from `dc1efaf3c9`,
verified with blame/show, not the later `30baf050a`. Evidence is recorded in
[the issue](../../../seon/issues/archive/cold-publication-merges-mixed-identities-into-a-sorted-map.md).
No schema-owner source file was edited by this lane.

Live proof used only `tmp/one-jvm-redesign-root`, an archived HEAD plus the owned
paths, through the existing operator `prepl-eval!` client and its existing
silence bound. The complete cold publication succeeded in **308,610 ms**.
The one-query config derivation took **4.704583 ms**, including deriving the
manifest's symbol/key pairs, against that published database. Its
[reproducible form](one-jvm-config-query-2026-09-23.clj) and
[prepl reply](one-jvm-config-query-2026-09-23.txt) are retained.

The one-file docstring publication plus adoption took **24,013.698 ms**
([complete phase data](one-jvm-activation-docstring-2026-09-23.edn)); the prior
item-1 measurement was **48,982 ms**, but the intervening schema-owner fix
means the total improvement cannot be attributed solely to this deletion.
Only `my.note` reloaded, in **12.411 ms**; only `my.note/add!`, `notes`, and
`forget!` re-armed. The source-identity phase is now **1,347.131 ms**, including
the still-present final deletion comparison, versus the earlier **4.4 s**
activation seal. No activation facts are produced.

No-change took **1,408.716 ms** across reported phases, with the same commit
`6ab06ac2-03ca-5f89-b24b-2b81fdb6fdcf` before and after, zero re-armed wrappers,
and no publication/adoption transaction
([complete data](one-jvm-activation-nochange-2026-09-23.edn)). These numbers
still miss the end-to-end targets; they are not acceptance claims.

One-file phases above about two seconds: snapshot construction **2,120.521 ms**
reads/digests the declared source inputs and compiles the merged schema
declarations, O(files + schema declarations), instead of reusing the
unchanged declaration projection. The publication reconciliation/report
phase **3,410.276 ms** still invokes the database writer's program-wide arity
comparison and then decodes identities per touched entity. The owned report
extraction can use the report's identity datoms and an indexed query;
`db.clj` is concurrently edited by the error lane and is excluded from this
snapshot. Other measured phases: issue indexing **1,869.828 ms**, row
preparation **1,041.511 ms**, adoption reconciliation **1,946.638 ms**, SCI
acquisition **1,263.082 ms**. Their algorithms, not a wider bound, remain the
next item.

Cold work is recorded separately in the
[operator output](one-jvm-activation-cold-2026-09-23.txt): lint all files
**21,097 ms**; whole-program row construction and first transaction remain
O(program), including **60,361 ms** committing the initial population. A
thread sample reached `db/write-owned-values-error`'s per-entity component
reverse-index seeks. Cold issue indexing was **12,421 ms**, scanning every
note and constructing the citation index. Cold exit also waited for
non-daemon Clojure agent threads, the already-recorded
[operator recurrence](../../../seon/issues/archive/test-base-publication-waits-for-idle-agent-threads.md).

Boot initially exposed two missed test callers of the deleted closure; both
were converted, and every touched production/test namespace then loaded
together. A later boot hit the existing **30,000 ms** config-labelled silence
bound. Subsequent boots reached READY in **50,521 ms** and **66,442 ms**,
without changing the bound. The
[thread evidence](one-jvm-activation-boot-2026-09-23.txt) identifies Clojure
`require` from `sci.eval/load-core-namespaces!`, which walks all indexed core
namespaces before `cluster-ctx*` constructs the base. This is O(program)
eager acquisition, not the config symbol query. First-use acquisition belongs
to explicitly protected `src/seon/sci/eval.clj`; its ownership question was
sent to the orchestrator while independent work continues.

Dependency seams: Datahike `db/transaction.cljc:1153` supplies the writer's
database to `:db.fn/call`; `query.cljc:2259` subtracts bound `not-join`
matches. No function roster, cache, or timeout was introduced. The source
identity transaction keeps the existing row by entity ID and calls the
existing `db/transact!`. The orchestrator must reset, gate the removed
attributes and config regression, and run its publication measurement script.

Exact UTF-8 source/test/schema bytes (against HEAD before this commit):

| Path | Before | After | Net deleted |
|---|---:|---:|---:|
| `src/seon/cluster.clj` | 187739 | 172013 | 15726 |
| `src/seon/cluster/source.clj` | 35582 | 30240 | 5342 |
| `src/seon/config.clj` | 41283 | 42586 | -1303 |
| `src/seon/artifact.clj` | 4397 | 4336 | 61 |
| `resources/seon/schemas/seon.source.edn` | 5961 | 5640 | 321 |
| `resources/seon/schemas/seon.activation.edn` | 4919 | 347 | 4572 |
| `test/seon/cluster/source_test.clj` | 26334 | 21791 | 4543 |
| `test/seon/cluster/source_evidence_test.clj` | 12657 | 11325 | 1332 |
| `test/seon/cluster/boot_test.clj` | 100108 | 96162 | 3946 |
| `test/seon/cluster/cohost_boot_test.clj` | 8263 | 6890 | 1373 |
| `test/seon/cluster/publication_reuse_test.clj` | 5658 | 5568 | 90 |
| `test/seon/db_test.clj` | 115476 | 114681 | 795 |
| `test/seon/predicate_publication_test.clj` | 5858 | 5865 | -7 |
| `test/seon/schema/admission_test.clj` | 5709 | 5775 | -66 |
| `test/seon/test/publication_test.clj` | 3258 | 3184 | 74 |
| `test/seon/config_functions_test.clj` | 0 | 1776 | -1776 |
| `resources/seon/schemas/seon.activation.lookup.edn` | 653 | 0 | 653 |
| `test/seon/cluster_test.clj` | 25039 | 24502 | 537 |
| `test/seon/cluster/source_nochange_test.clj` | 1842 | 1545 | 297 |

Fast run `91133393e7b0`: 13 tests / 66 assertions / 0 failures / 2 errors.
The existing schema-convergence test passed a projection to a forms-only
private helper; its owned caller is corrected. The no-change regression hits
the retained fixture-manifest boundary: `test-support/build-source-manifest`
reads `cache/manifest` when `seon.test.published-base` is set. Every retained
base inspected still includes `seon.cluster/derive-activation` with
`[:=> [:cat :seon.activation/request] :seon.activation/result]`, whose schemas
this reset cut removes. The failure enters Malli function-schema compilation
in `schema/projection-registry:494`. This is the existing
[retained canonical fixture issue](../../../seon/issues/canonical-fixture-retains-old-function-contracts-after-adoption.md);
the protected fixture/test-system files were not changed, and no lane base
preparation or cold gate was run. The own-root fresh publication, boot,
one-file adoption and unchanged-head proof above do not use that retained
manifest. The orchestrator must refresh its base at the reset and rerun this
regression before claiming the gate green.

Follow-up fast run `365be14cb295`: **12 executed, 66 assertions, zero
failures/errors**, covering config and cluster regressions after the forms
caller correction. Config's full regression measured **13.05 s** in this
run versus about 3 s in the earlier isolated run; canonical schema convergence
measured **19.57 s**, rebuilding complete projections twice. Both now carry
explicit long bounds and reasons (20 s and 30 s). These bounds document
measured work, not acceptable performance: the next owned cut must pass the
existing projection to schema-row construction; the protected writer's
whole-program work remains separately named. No timing assertion was added.
Foreign dirty error/database/test-system paths stayed at HEAD in the snapshot.
The scratch cluster was downed before either fast run.


## Slice 4 — publication difference, 2026-09-23

This is a coherent partial item-2 landing, not completion of slice 4. Activation
removal is `3c0b47dfe` (RESET NEEDED). The remaining Markdown indexing behavior
and ownership of lazy SCI acquisition need the rulings described below.

**Measured on this lane's scratch root and advertised prepl:** one-file
publication plus adoption **24,013.698 → 12,450.319 → 8,171.094 ms**. The last
step carries the already-computed test-input digest into publication, avoiding
a second whole-repository digest walk; ordinary warm-up also changed timings,
so the entire improvement is not attributed to that edit. No-change is
**756.691 ms**, with commit `6ab07210-eb86-57de-a3b6-07248cdbd51b` unchanged.
The target of 5 s for a one-file edit is **not met**. The exact phases and
changed-wrapper identities are in [one-file data](one-jvm-publication-delta-2026-09-23.edn)
and [no-change data](one-jvm-publication-nochange-2026-09-23.edn).
Only `my.note` reloaded; its three wrappers changed.

The existing seams now receive their already-derived inputs:

- `populate-source!` passes the held projection to `seon.fn/index!`;
  `schema-row-changes` calls `schema/canonical-schema-rows` with that projection.
  No new projection/cache owner is introduced.
- `seon.fn/report-identities` queries identity datoms for the report's affected
  entity ids against before and after, instead of a pull per entity.
- Publication deletes its final `db/deletion-error` whole-program comparison.
  The writer already checks affected retracted identities in
  `seon.db/write-deletion-error` (`src/seon/db.clj:4017`) and seeks surviving
  referrers through AVET in `removed-definition-error` (`:3923`). That protected
  owner was read, not edited. Deletion still refuses at the transaction authority.
- Findings comparison receives only rows from replaced file artifacts.
- `source-snapshot` carries the existing `:seon.source/test-input-digest`;
  `source/publish!` uses it, retaining the inventory fallback for direct callers.
- The unresolved report joins callee namespace to `:seon.ns/name`, without a
  namespace roster. Datahike supports `namespace` at
  `reference-code/datahike/src/datahike/query.cljc:612` and qualified Clojure
  function resolution at `:1129`. The result is 124 unresolved calls over
  6,697 analyzed declarations, rather than the historical 42,767 library entries.

**Algorithmic costs remaining:** all phases in the final one-file trace are
below 2 s, but their sum still exceeds the target. Snapshot acquisition hashes
source plus the test inventory (943 ms initially; stability walks 526/507 ms).
`test.cache/input-digests` hashes all enumerated files before its caller filters
to declared roots: filter-before-hash is the simple remaining solution in the
protected test-system owner, not a reason to add a cache. Published database
projection acquisition is 278 ms and still reads the stored declaration world.
The carried projection avoids the redundant index reconstruction; it does not
claim every projection acquisition has disappeared. Publication reconciliation
and report extraction together take 1,467 ms; adoption reconciliation 633 ms.
Source identity/adoption record transactions cost 250/303 ms. The existing
writer validates each actual transaction; no extra writer or bypass is added.
Artifact persistence plus adoption entry takes 644 ms. SCI acquisition takes
408 ms; no new interpreter cache was added.

The whole unresolved report took **3,080.291 ms on a new database value**,
versus 7.397 ms on a repeat of the prior value. It scans all program calls and
recorded reach because its public request asks for the whole report. Incremental
publication already omits this report; cold publication still includes it.
The indexed-namespace clause fixes correctness/noise, not all query cost.

Markdown indexing remains **728.880 ms**, O(all notes + citation population),
including issue Git history. Stored rows omit full source and tags; the
publication's changed program identities cannot recover those authored inputs.
The [existing issue now contains three priced options](../../../seon/issues/issue-indexing-at-publication-costs-13-seconds.md):
remove incremental Markdown indexing and use its explicit owner (recommended),
pass changed Markdown paths, or store canonical authored note facts. No choice
was silently made. Lazy SCI acquisition also crosses explicitly excluded
`src/seon/sci/eval.clj`; ownership was requested, with no edits there.
Core-fault fallback, branch-publication serialization and boot timing/laziness
remain outstanding and are not claimed by this commit.

**Verification:** selected fast run `d93d8f6b33d5`: 16 executed, 80 assertions,
0 failures, 0 errors. It includes the exact wrapper set, namespace reload set,
report identities including retractions/history, findings delta, unresolved
callee namespace/declaration, and schema convergence. Report-identity regression
uses fresh synthetic symbols: fixed names were themselves indexed test
references and correctly prevented deletion. Schema convergence fell from
19.57 s to 5.048 s with the handed projection. The new unresolved regression
took 20.543 s (two analyses/writes/reports), with a declared long-test bound
and reason; this remains performance debt, not acceptable steady-state work.

The broader `seon.turn-test` run was stopped before the edited settlement
regression. A protected `turn.clj:2091` diagnostic lacks `:seon.error/at`; a
subsequent writer rejection expanded the complete projection into a 432 MiB
log. The [issue and bounded evidence](../../../seon/issues/writer-rejection-prints-the-complete-program-projection.md)
name that boundary. No green tally is claimed for that run.
`clojure -M -e "(require 'seon.cluster 'seon.cluster.source 'seon.fn)"`
loaded successfully. The orchestrator still owes the cold gate and fresh
measurement-script row. The retained fast base is 15 commits behind the
snapshot and predates activation deletion, as previously documented.

**Shared-tree boundary:** the foreign `seon.fn/declared-reference-edges` hunk
was preserved and excluded from this commit through selected-hunk staging.
The live measurement archive excluded it; the file-level fast snapshot
included it. Dirty `src/seon/test/runner.clj`, `test/seon/fn_test.clj` and
other foreign paths remained outside this commit. No protected schema, error,
database, interpreter or test-system implementation was edited.

Exact UTF-8 bytes for code/schema/tests (deleted/inserted counts are changed
line bytes, including their newline; before/after are complete files):

| Path | Before | After | Deleted | Inserted |
|---|---:|---:|---:|---:|
| `src/seon/cluster.clj` | 172013 | 172569 | 861 | 1417 |
| `src/seon/cluster/source.clj` | 30240 | 30055 | 372 | 187 |
| `src/seon/fn.clj` | 147191 | 147522 | 553 | 884 |
| `resources/seon/schemas/seon.source.edn` | 5640 | 5832 | 0 | 192 |
| `test/seon/cluster/publication_delta_test.clj` | 6006 | 6368 | 799 | 1161 |
| `test/seon/cluster/publication_findings_test.clj` | 1763 | 1902 | 574 | 713 |
| `test/seon/cluster_test.clj` | 24502 | 24475 | 347 | 320 |
| `test/seon/turn_test.clj` | 118029 | 118513 | 333 | 817 |
| `test/seon/fn/unresolved_test.clj` | 0 | 1801 | 0 | 1801 |

Total changed-line bytes: **3839 deleted, 7492 inserted**.

## Slice 4 — changed Markdown paths, 2026-09-23

The existing refresh request now carries its relative changed paths to
`seon.cluster.source/publish!`. `index-issues!` does no note work for a
source-only request. A changed note selects its replacement/deletion and
affected class membership through the existing `seon.issue/index-tx` writer.
Cold publication and explicit `seon.issue/index!` retain complete indexing.
A note-only edit can advance the published branch without re-populating
program rows or writing a source seal; an unchanged note delta does neither.
No note source or tags are stored. Class membership still derives from
authored notes: a note-edit request reads the complete notes to resolve
class tags, but replaces only selected notes and affected classes. That
O(notes) derivation remains a note-edit cost; source edits do none of it.

Fresh own-root before/after: **12480.248 → 8270.733 ms** for the same
`my.note` docstring operation, through its advertised prepl. Before spent
1806.439 ms indexing Markdown and 1720.609 ms adopting unrelated issues;
after has neither phase. Raw progress is in
[before](one-jvm-note-paths-before-2026-09-23.edn) and
[after](one-jvm-note-paths-after-2026-09-23.edn), produced by the existing
`one-jvm-slice4-adoption-2026-09-22.clj` script. The baseline differs from
the prior warm 8.17 s row; it is not presented as the same run.

No individual after span exceeded 2 s. Publication reconciliation was
1744.647 ms, source acquisition 1063.261 ms, adoption reconciliation
881.282 ms, SCI acquisition 395.691 ms. These still sum above the target:
source acquisition walks all inputs, reconciliation must be restricted to
touched rows, and SCI acquisition still installs the program on every
adoption. The remaining assigned changes are not claimed by this commit.
The new owner docstring ruling in `unsettled.md` also requires caller lint
only for contract/arity changes and removal of unnecessary dependent reloads.

Verification: own live publication loaded these changes and converged.
`clojure -M -e "(require 'seon.cluster 'seon.cluster.source 'seon.issue)"`
exited 0. Fast run `16228f564898`: 2 tests, 2 assertions, 0 failures,
1 error. The source-only zero-read regression passed; the changed-note
regression hit the stale published arity before its assertions, recorded
in the existing canonical-fixture-contract issue. No green proof is claimed
for that regression. The orchestrator must publish the widened contract,
run the two focused regressions, then measure source-only and note-only edits.
The own scratch JVM was stopped before the fast/load JVMs.

Foreign dirty `test/seon/cluster/source_test.clj`,
`test/seon/cluster/source_evidence_test.clj` and `test/resources/` were
preserved and excluded. The formerly foreign fn.clj hunk is now committed
in HEAD. No test-system files or default cluster were operated.

Exact changed-line UTF-8 bytes (including newlines):

| Path | Before | After | Deleted | Inserted |
|---|---:|---:|---:|---:|
| `resources/seon/schemas/seon.source.edn` | 5832 | 5978 | 0 | 146 |
| `src/seon/cluster.clj` | 172569 | 172923 | 121 | 475 |
| `src/seon/cluster/source.clj` | 30055 | 30654 | 1011 | 1610 |
| `src/seon/issue.clj` | 85128 | 87462 | 1057 | 3391 |
| `test/seon/cluster/publication_notes_test.clj` | 0 | 3527 | 0 | 3527 |

Total: **2189 deleted, 9149 inserted**.

## Slice 4 — SCI acquisition on first use, 2026-09-23

Boot's `cluster-ctx` constructs the minimal SCI interpreter. `evaluate`
acquires program rows inside its existing execution boundary;
`fork-for-turn` acquires the base before the agent fork. `acquire!` reuses
the existing context's acquired program snapshot while the supplied database
identity is unchanged. It serializes acquisition on that context's existing
snapshot atom, with no second cache. An uncommitted database reuses only the
identical supplied value. The original fault recorder travels into later
acquisition. `bind-result!` and `result-handle` are untouched.

The dependency seams are SCI `init`/`fork`
(`reference-code/sci/src/sci/core.cljc:330,345`) and Datahike
`committed-value-identity` (`reference-code/datahike/src/datahike/db.cljc:385`),
through `seon.db/committed-value-identity`. The latter compares connection,
generation and commit ID; it avoids database equality's EAVT comparison
(`db.cljc:703–715`). The minimal interpreter construction measured 1.981 ms
in the own live JVM before editing.

Deleted adoption's `acquire-development!`, its eager program acquisition and
its individual SCI deletion installation. The next acquired database naturally
excludes retracted functions. Deleted the sole stale test caller: its
handwritten string-identity fixture duplicated the canonical
`one-unloadable-row-cannot-prevent-cold-acquisition` regression in
`test/seon/sci/eval_test.clj`. No other caller remains.

Same scratch-root docstring: **8270.733 → 7683.081 ms**.
[The complete progress](one-jvm-lazy-sci-after-2026-09-23.edn) has no SCI
acquisition span. Publication reconciliation 1697.784 ms; source acquisition
1378.252 ms; adoption reconciliation 837.719 ms; final digest walk 517.560 ms.
No individual span exceeds 2 s, but their aggregate still misses the target.
Initial convergence of the changed implementation took 45.814 s because
its core namespaces still reload their dependents and lint caller files;
that is the next owner-ruled algorithm correction, not steady-state evidence.

Fast run `524b14d377b5`: **2 tests, 17 assertions, 0 failures/errors**.
The new regression proves zero installed program before first use, acquired
program afterward, identical context/snapshot reuse for the same committed
database, and reacquisition after a transaction. It took 20.892 s under its
30 s declared bound: two complete acquisitions plus first-use loading of
the published namespaces in the fresh test JVM. That O(program) work is
now paid on first use, not boot/adoption; the measured warm acquisition
removed from adoption was 395.691 ms. The other test took 1.201 s.
The own cluster booted and adopted successfully, then was stopped before
the final namespace-load proof. Orchestrator cold proof remains owed.

Foreign source_test/source_evidence_test and test/resources changes remain
excluded. Exact UTF-8 changed-line bytes:

| Path | Before | After | Deleted | Inserted |
|---|---:|---:|---:|---:|
| `AGENTS.md` | 98302 | 98398 | 82 | 178 |
| `src/seon/cluster.clj` | 172923 | 171440 | 1744 | 261 |
| `src/seon/sci/eval.clj` | 167355 | 169266 | 1076 | 2987 |
| `test/seon/adoption_rows_test.clj` | 6053 | 1595 | 4499 | 41 |
| `test/seon/sci/lazy_acquisition_test.clj` | 0 | 2116 | 0 | 2116 |

Total: **7401 deleted, 5583 inserted**.

## Slice 4 — preserve a refused core fault's message, 2026-09-23

The last-resort `commit-fault!` tuple now carries the source's message
instead of nil. Flow's existing committer hands that fact and the recording
outcome to `emit-core-fault!`; no second error normalizer or writer was added.
Throwable fallback includes its class. The error lane's recording code is
untouched. The existing message-loss issue is resolved by the regression.

Fast run `34dfd601d3b6`: **1 test, 12 assertions, 0 failures/errors**,
3.100 s. The real preparation refusal retains map, flow Throwable and bare
Throwable messages in printed output. Live own-root one-file edit after the
change: **7702.651 ms**, versus 7683.081 ms before; this error-only change
has no expected publication speed effect. [Raw progress](one-jvm-fault-message-after-2026-09-23.edn).
No phase exceeds 2 s; the remaining aggregate costs are unchanged from the
SCI entry above. The scratch JVM was downed before the namespace-load proof.
Foreign `test/seon/test_support.clj` and `test/seon/turn_backstop_test.clj`
edits were preserved and excluded. Orchestrator cold proof remains owed.

Exact changed-line UTF-8 bytes:

| Path | Before | After | Deleted | Inserted |
|---|---:|---:|---:|---:|
| `src/seon/cluster.clj` | 171440 | 171779 | 29 | 368 |
| `test/seon/cluster/fault_message_test.clj` | 0 | 1634 | 0 | 1634 |

Total: **29 deleted, 2002 inserted**.

## Slice 4 — serialize every source head move, 2026-09-23

`source/publish!` and `source/record-results!` now invoke the existing
`cluster/with-source-refresh-monitor!` before reading the head. Runtime Var
resolution avoids the existing cluster/source namespace dependency cycle.
Refresh already holds this reentrant monitor, so its nested publication
uses the same lock and holder report. The monitor's existing acquisition
bound remains; result recording no longer has a retry loop, retry counter,
or separate retry deadline. No new lock, cache or tuned constant was added.
Datahike's `force-branch!` contract explicitly requires exclusive writes
(`reference-code/datahike/src/datahike/versioning.cljc:323–334`); the existing
expected-head refusal remains intact for explicitly stale requests.

[The live concurrent refresh script](one-jvm-concurrent-refresh-2026-09-23.clj)
ran in the own scratch prepl: two changed-file requests produced
[one head and two consistent replies](one-jvm-concurrent-refresh-2026-09-23.edn),
one built and one unchanged, in 8745 ms combined.
The next one-file edit measured **7343.350 ms**, versus 7702.651 ms before;
[raw progress](one-jvm-serialized-publication-after-2026-09-23.edn).
No span exceeds 2 s. Serialization itself adds no whole-program traversal;
remaining digest walks and reconciliation still dominate the aggregate.

The direct-publication regression uses the canonical private store and
two concurrent requests. The existing evidence regression now runs an
actual concurrent completion writer and publication instead of mocking
reentrant head moves and asserting retries. Fast iteration remains blocked
at the stale fixture's required activation field: 1 test, 1 assertion,
0 failures, 1 error, before the publication writer. This is recorded in
the existing fixture-contract issue; no green canonical concurrency tally
is claimed. The orchestrator must refresh its base and run both
`seon.cluster.publication-concurrency-test` and
`seon.cluster.source-evidence-test`. Source and both test namespaces load.

The prior source_evidence_test edits were committed at `bf3b5df9f` and the
path was clean before this edit. Current foreign test_support, turn_backstop
and test-system landing-note edits are excluded. The scratch JVM was downed
before namespace loading. Exact UTF-8 changed-line bytes:

| Path | Before | After | Deleted | Inserted |
|---|---:|---:|---:|---:|
| `src/seon/cluster/source.clj` | 30654 | 28296 | 2846 | 488 |
| `test/seon/cluster/source_evidence_test.clj` | 11304 | 8655 | 4306 | 1657 |
| `test/seon/cluster/publication_concurrency_test.clj` | 0 | 1614 | 0 | 1614 |

Total: **7152 deleted, 3759 inserted**.

## Slice 4 — lazy boot acquisition and phase timing, 2026-09-23

Boot now creates the existing minimal SCI context; agent forks retain their
base context and acquire its program only at evaluation. The existing
program snapshot still owns reuse for the exact database value, using
Datahike committed-value identity rather than database equality. No second
cache was added. `bind-result!` and `result-handle` behavior is unchanged.
SCI fork still owns environment isolation (`reference-code/sci/src/sci/core.cljc:345`);
`seon.sci.eval/evaluate` acquires and regenerates the private agent context.

The existing armer selects unarmed agents with pending work or schedules.
Idle agents have no graph until the existing wake listener observes work.
Schedule owners must keep their timer proc; the existing `:seon.wake/arms`
property on schedule ownership triggers this same armer. No new scheduler,
listener or stored readiness flag exists. Boot and add-cluster use their
existing progress callback to print each completed phase's elapsed time.

Live own-root evidence: zero installed SCI functions after boot, only the
scheduled root agent armed. Operator start fell **23998 → 16173 ms**;
cluster readiness was 6861 ms. [Boot phase evidence](one-jvm-lazy-boot-phases-2026-09-23.txt).
The labels report the interval AFTER the named readiness fact: branch
3118 ms covers projection/schema coherence and recovery; config 2330 ms
covers process/root seeding, Lucene acquisition and minimal SCI construction,
not config reconciliation alone (`stand-cluster-runtime!`). Namespace loading
7001 ms is the authorized cold JVM cost. Branch projection is O(program)
once per new connection; Lucene `search/open!` rebuilds O(indexed entities)
when its stored basis differs (`src/seon/search.clj:344`). These remain boot
costs, not hidden incremental-publication work. The 1992 ms final ready
interval covers instrumentation before operator acknowledgement.

One-file publication plus adoption: **7343.350 → 7138.908 ms**.
[Raw progress](one-jvm-lazy-boot-after-2026-09-23.edn). No individual span
exceeds 2 s; publication reconciliation 1674 ms, adoption reconciliation
834 ms, source build 1006 ms and final digest verification 546 ms remain.
The multi-file convergence before that measurement took 36263 ms: current
caller lint and dependent namespace reload still scale beyond changed
files; this is a finding, not an acceptable incremental bound.

Fast run `ea08434bdaff` passed the lazy SCI regression (11 assertions),
but its companion agent test had a missing fixture cluster name. After
repair, agent run `0a7f30379e87` passed 1 test / 3 assertions, 0 failures
or errors, 5.154 s; its declared 10 s bound names the canonical setup and
transaction cost. The SCI regression's declared 30 s bound covers two
complete first-use acquisitions. Namespace load passed after downing the
scratch JVM. The orchestrator's isolated cold proof remains owed.

Config's stored digest covers dials, not initialization; the existing
initialization-only-change regression requires reconciliation despite an
unchanged digest. No unsafe whole-config shortcut was added; that decision
remains explicit. Foreign test-support, test-runner, turn-work and testing
skill edits are excluded. No reset is required for the existing wake property.

Exact UTF-8 changed-line bytes:

| Path | Before | After | Deleted | Inserted |
|---|---:|---:|---:|---:|
| `AGENTS.md` | 98398 | 98411 | 284 | 297 |
| `resources/seon/schemas/seon.schedule.task.edn` | 764 | 784 | 72 | 92 |
| `script/seon/fresh_operator.clj` | 157158 | 157978 | 107 | 927 |
| `src/seon/cluster.clj` | 171779 | 171571 | 781 | 573 |
| `src/seon/cluster/agent.clj` | 50654 | 51531 | 811 | 1688 |
| `src/seon/cluster/wake.clj` | 28319 | 28279 | 472 | 432 |
| `src/seon/sci/eval.clj` | 169266 | 169522 | 1117 | 1373 |
| `test/seon/sci/lazy_acquisition_test.clj` | 2116 | 2424 | 43 | 351 |
| `test/seon/cluster/lazy_agents_test.clj` | 0 | 1759 | 0 | 1759 |

## Slice 4, remaining item 1 — report-scoped validation, 2026-09-23

The clean own-root docstring baseline is **8831.448 ms**. An earlier shared
checkout run touched 21 entities including call edges while another lane
edited; it is not the one-file baseline. The retained measurement uses a
HEAD archive with reference-code linked, plus only this lane's edits.
No foreign JVM, session or files were changed.

The [profiling script](one-jvm-validator-profile-2026-09-23.clj) delegates
all instrumented calls to their entering roots. [Before profile](one-jvm-validator-before-profile-2026-09-23.edn):
only five entities were touched (three functions, their file, transaction
metadata), but final-report validation ran the full arity query at
702.666/481.017 ms and scanned all render declarations at 55.036/27.750 ms.
Owning-value validation was already scoped: 64.823/7.337 ms. The cause was
using a touched function identity as evidence of changed arities, and
attempted idempotent identity assertions as evidence of changed render targets.

Now effective datoms decide whether a function's identity, calls, arity
relation or owned components changed. All touched owning values still
validate. Schema/default changes retain the existing arity validation;
this does not claim that their global query is now incremental. Render
validation ignores idempotent identity assertions. Datahike supplies both
attempted and effective datoms at its final callback
(`reference-code/datahike/src/datahike/db/transaction.cljc:1206`).
The same-transaction component repair guarantee remains at that callback.

`fn/index!` carries its existing projection on the writer's database argument.
`fn/report-identities` now pulls only installed identity attributes of report
entities, before and after, through `db/pull-many`; the relation query over
entity and attribute collections cost 486 ms on the new publication branch.
The indexed pull measured 42 ms and preserves deleted identities. Native
pull owns the EAVT access (`reference-code/datahike/src/datahike/pull_api.cljc`).
No transaction count, validation guarantee or cache was added.

[After profile](one-jvm-validator-after-profile-2026-09-23.edn): final report
76.501/7.935 ms, zero arity scans, zero render scans. One-file edit:
**5416.485 ms**. Publication/adoption reconciliation spans: 614.731/388.754 ms.
The population still normalizes the three changed functions' complete owned
rows (282.559 ms on the new branch versus 5.608 ms on the live branch);
this is O(the changed functions' owned contract rows), not a whole-program
pull. Remaining whole-program acquisitions include three schema projections
(~250 ms each), the artifact read/validation/write and full digest walks.

Phase labels mark the interval after the named event: the 542.996 ms
“findings” interval is the pre-publication snapshot verification, not lint;
“analysis selected files” is 85.004 ms. The 561.799 ms “branch publication
complete” interval includes serializing the stored artifact, then acquiring
the adopted publication. These are the next ordered deletions.

Fast run `2e9031a125aa`: 5 tests, 16 assertions, 1 failure, 1 error.
Documentation's zero-global-check regression and changed/deleted report
identity regression pass. Invalid arity reaches the existing supplied-default
coherence refusal for `seon.db/supplied-connection`, before its expected arity
diagnostic; this is also recorded by the error lane in the full-publication
issue. The wrapper regression refuses a loaded function contract during
instrumentation under exported base `0e3ced...`, 39 commits behind HEAD.
No green wrapper or invalid-arity proof is claimed; the orchestrator owes
these regressions against its current canonical base. The duration declaration
on the invalid-arity regression names its measured 5.078 s fixture/check cost.
Source namespaces load with the scratch JVM down. Foreign test-support,
test-system runner/turn-work and testing-skill edits are excluded.

Exact changed-line UTF-8 bytes:

| Path | Before | After | Deleted | Inserted |
|---|---:|---:|---:|---:|
| `src/seon/db.clj` | 221722 | 222730 | 134 | 1142 |
| `src/seon/fn.clj` | 149108 | 149206 | 396 | 494 |
| `test/seon/publication_validation_test.clj` | 0 | 1883 | 0 | 1883 |

| Completed phase (in order) | Before ms | After ms |
|---|---:|---:|
| request | 1.199 | 1.150 |
| request accepted | 1.193 | 1.119 |
| bootstrap configuration | 106.449 | 100.713 |
| store acquisition | 0.576 | 0.480 |
| source build | 987.439 | 909.097 |
| published manifest read | 126.267 | 116.808 |
| published manifest validation | 288.865 | 265.322 |
| published database acquisition | 0.276 | 0.285 |
| analysis started | 12.819 | 12.713 |
| analysis input inventory | 104.568 | 105.576 |
| analysis caller files | 328.973 | 235.399 |
| analysis selected files | 95.846 | 85.004 |
| analysis replace artifacts | 80.393 | 60.888 |
| analysis manifest complete | 13.785 | 13.561 |
| analysis complete | 1.599 | 1.248 |
| findings in analyzed files: 0; added=0; resolved=0 | 635.756 | 542.996 |
| branch publication started: 1 inputs | 225.010 | 148.772 |
| program rows started | 186.830 | 175.724 |
| contract projection started: 3336 schemas, 3 functions | 0.123 | 0.089 |
| contract projection complete | 0.649 | 0.682 |
| contract rows: 1/5 | 3.510 | 3.104 |
| contract rows: 2/5 | 3.656 | 3.459 |
| contract rows: 3/5 | 3.135 | 2.666 |
| contract rows: 4/5 | 0.338 | 0.129 |
| contract rows: 5/5 | 34.289 | 34.099 |
| development reconciliation transaction | 1783.698 | 614.731 |
| program rows complete | 0.465 | 0.137 |
| publication source identity | 107.625 | 81.883 |
| publication branch head | 88.081 | 54.649 |
| branch publication complete | 682.279 | 561.799 |
| development changed program rows | 108.956 | 88.288 |
| development reconciliation transaction | 1332.393 | 388.754 |
| development loaded definitions | 5.280 | 1.968 |
| development JVM instrumentation | 340.447 | 173.362 |
| development source verification | 939.835 | 522.576 |
| development adoption record | 197.908 | 106.734 |
| development cluster converged | 0.938 | 0.521 |

### Item 2 decision boundary — unchanged non-program input facts

The next live probe found 788 digest inputs but only 402 `:seon.fn.file`
digest rows; **386 individual input digests are absent from the database**.
Their aggregate test-input digest is stored, but cannot reconstruct the
map hashed by the current publication identity. The
[issue and three priced options](../../../seon/issues/publication-input-digests-are-not-all-database-facts.md)
record the exact gap; [raw evidence](one-jvm-input-digest-facts-2026-09-23.edn)
and [script](one-jvm-input-digest-facts-2026-09-23.clj) are retained.
Recommended: populate the existing file digest rows for all publication
inputs, explicitly including non-analyzed resource and pin paths, then
remove the disk mirror. No item-2 production edit has been made.

The lane stops at this permitted data-model decision. Items 2, 4, 5, 6,
8 and config remain open in the assigned order. Item 1 commit is
`d726468e0`; docstring is 5416.485 ms, not the subsecond target. The scratch
JVM was downed and its root removed; no default operation was performed.

### Input-row ruling: retained test boundary evidence

The item-1 fast run `2e9031a125aa` had these two boundaries (not attributed
to a cause without a separate proof):

- `seon.publication-validation-test/changed-call-facts-still-refuse-an-invalid-arity`
  received `:seon.error/operation seon.call-preparation/incoherent`,
  `:seon.call-preparation/incoherent-key :seon.db/connection`, and
  `:seon.db/transaction-refused true`. Exact message: "The supplied default
  :seon.db/connection is not admissible: seon.db/supplied-connection's declared
  return does not agree with the row's value schema :seon.db/connection."
  The expected `:seon.fn/arity-mismatches` was absent.
- `seon.cluster.publication-delta-test/reloading-one-file-preserves-unrelated-wrappers`
  errored before its wrapper assertions: `clojure.lang.ExceptionInfo: The
  loaded function contract cannot compile.` The stack names
  `seon.instrument/apply!` at `instrument.clj:1019`; the printed exception
  supplied no offending function identity.

The owner chose file rows for all publication inputs, excluding the derived
merged schema entry. Existing analysis-input queries in `seon.test/check-admission`,
`seon.fn/caller-files`, `seon.issue.detect/declarations`, and `seon.program/overrides`
join declarations through `:seon.fn/file`; none selects lint inputs merely
from file-row existence. `:seon.source/test-input-digest` still has readers in
`seon.test` (selection admission and provenance); its conditional retirement
cannot delete those readers' input while that owner is outside this lane.

### Fresh input-row baseline, before production changes

At `10c28c885`, an isolated HEAD archive and its own root/cluster `s`
completed cold publication in 275,256 ms (initial indexing authorized).
The 107,580-operation population transaction took 130,373 ms; complete
analysis took 10,282 ms. Fork without a live host took 10,237 ms including
the authorized cold JVM; boot took 12,859 ms. Through that host prepl,
one `my.note` docstring edit took **6,174.993 ms**, with three functions.
The existing measurement script produced [raw progress and result](one-jvm-input-rows-before-2026-09-23.edn).

| Completed phase | ms |
|---|---:|
| request | 1.367 |
| request accepted | 1.694 |
| bootstrap configuration | 103.608 |
| store acquisition | 0.536 |
| source build | 926.705 |
| published manifest read | 124.704 |
| published manifest validation | 340.389 |
| published database acquisition | 0.120 |
| analysis started | 13.392 |
| analysis input inventory | 115.473 |
| analysis caller files | 548.050 |
| analysis selected files | 133.320 |
| analysis replace artifacts | 69.982 |
| analysis manifest complete | 12.394 |
| analysis complete | 1.195 |
| findings in analyzed files: 0; added=0; resolved=0 | 497.843 |
| branch publication started: 1 inputs | 147.686 |
| program rows started | 171.650 |
| contract projection started: 3336 schemas, 3 functions | 0.109 |
| contract projection complete | 0.780 |
| contract rows: 1/5 | 5.913 |
| contract rows: 2/5 | 4.223 |
| contract rows: 3/5 | 3.588 |
| contract rows: 4/5 | 0.229 |
| contract rows: 5/5 | 33.382 |
| development reconciliation transaction | 501.586 |
| program rows complete | 0.141 |
| publication source identity | 35.369 |
| publication branch head | 48.750 |
| branch publication complete | 556.058 |
| development changed program rows | 483.429 |
| development reconciliation transaction | 557.738 |
| development loaded definitions | 2.963 |
| development JVM instrumentation | 177.838 |
| development source verification | 472.738 |
| development adoption record | 79.309 |
| development cluster converged | 0.744 |

The source build and both verification spans still hash whole inventories;
selected-file analysis itself is 133.320 ms. No individual measured span
exceeds 2 s. Their sum remains above target; this does not justify leaving
the O(program) work in place. A live query found zero file rows without
a `:seon.fn/file` referrer among the 402 current analysis inputs. The
namespace-only relation is needed for that admitted future case, not
evidence that current files were omitted.

Production edits await release of the existing private Git-pin reader
in `src/seon/test/cache.clj`: the public alternative hashes unrelated
`deps.edn`, while copying its parser would duplicate the protected owner.
The owner question is pending; no private-reader bypass was installed.

Verification/cleanup: `(require 'seon.cluster 'seon.cluster.source 'seon.fn
'seon.db)` passed in one foreground JVM after the scratch host was down.
No tests were changed or rerun in this evidence-only checkpoint. Production
bytes deleted/added: **0/0**. The raw measurement is 3,859 UTF-8 bytes.
`down` confirmed the store flock free and every recorded own-root JVM stopped;
the own archive/root and disposable thread sample were removed. Foreign
edits in the testing skill, selection tests, `test_support.clj`, and turn-work
tests were preserved. The orchestrator still owes the cold proof for the
accepted validator commits and attribution of the two exact refusals above.

## Slice 4, remaining item 2 — per-path publication input facts

RESET NEEDED before partial requests against a pre-cut publication: a
complete publication now writes every input path/digest as a file row. The
new namespace/file relation uses the existing `:seon.fn/file` attribute.
No merged-schema pseudo-path is written. The own cold publication contains
**788/788 matching input digests**, zero missing paths, and **403 analysis
files** selected by declaration refs. [Coverage query](one-jvm-input-row-coverage-2026-09-23.clj),
[coverage result](one-jvm-input-row-coverage-2026-09-23.edn).

`source/path-digests` reads the request paths only and calls the released
`test.cache/gitlink-digests` for directory pins. `source/stored-path-digests`
uses unique file lookup refs: Datahike selects AVET for bound indexed
attribute/value (`reference-code/datahike/src/datahike/db/search.cljc:150`).
The existing `fn/index!` transaction includes changed external file rows and
retracts removed inputs through the existing exact replacement. Analysis
files are selected through `:seon.fn/file`, never by file-row presence.
The new request data uses existing attributes; no new cache or attribute.

With a publication present, an empty changed-path request reads no files.
The real prepl probe took **482.739 ms**, `built? false`, head unmoved, hash
requests `[[]]`: [result](one-jvm-input-rows-no-change-2026-09-23.edn).
The first cold root was built before the final empty-request correction;
[the live probe](one-jvm-input-row-live-2026-09-23.clj) re-evaluates precisely
`full-source-refresh!` from the edited source. The measured `my.note` edit
then uses real publication and in-place adoption, not a simulated writer.

One-file before **6174.993 ms**, after **7826.353 ms**. This is not an
end-to-end speedup claim. Pre-publication verification fell from **497.843
ms to 0.212 ms**; source build fell from **926.705 to 665.008 ms**, but
other spans rose. No after span exceeds 2 s. The source-build span still
includes the whole stored-artifact read; manifest validation/database
projection, artifact replacement/write and program acquisition remain
O(program), owned by item 5. Caller selection remains before contract
comparison (item 6). Post-adoption snapshot remains O(program) until item 4.
The shared library cache is unchanged. Cold complete analysis was **10328
ms**, complete population **132864 ms** for 108442 operations; cold init
including JVM/cache startup and exit was **274394 ms** (authorized once).

| Completed phase | after ms |
|---|---:|
| request | 1.246 |
| request accepted | 2.199 |
| bootstrap configuration | 181.986 |
| store acquisition | 0.790 |
| source build | 665.008 |
| published manifest read | 164.175 |
| published manifest validation | 360.055 |
| published database acquisition | 0.484 |
| analysis started | 17.042 |
| analysis input inventory | 80.820 |
| analysis caller files | 729.263 |
| analysis selected files | 215.966 |
| analysis replace artifacts | 97.667 |
| analysis manifest complete | 16.855 |
| analysis complete | 0.750 |
| findings in analyzed files: 0; added=0; resolved=0 | 0.212 |
| branch publication started: 1 inputs | 209.412 |
| program rows started | 211.472 |
| contract projection started: 3336 schemas, 3 functions | 0.135 |
| contract projection complete | 1.958 |
| contract rows: 1/5 | 9.410 |
| contract rows: 2/5 | 6.922 |
| contract rows: 3/5 | 5.254 |
| contract rows: 4/5 | 0.491 |
| contract rows: 5/5 | 50.198 |
| development reconciliation transaction | 619.272 |
| program rows complete | 0.186 |
| publication source identity | 57.852 |
| publication branch head | 75.954 |
| branch publication complete | 905.852 |
| development changed program rows | 730.718 |
| development reconciliation transaction | 1040.944 |
| development loaded definitions | 5.862 |
| development JVM instrumentation | 567.394 |
| development source verification | 580.073 |
| development adoption record | 209.840 |
| development cluster converged | 2.638 |

The test-input aggregate remains because `seon.test` admission and
provenance still read it. File-backed publication decides from per-path
rows at both the refresh and publication seams. The existing explicit
digest-only low-level publication API (artifact installation and direct
publication tests) retains its contract; the stored-manifest identity
check remains until item 5 removes that mirror.

Exact changed-line UTF-8 bytes for the owned code, schema and tests: **3229 deleted, 14553 added** (`git diff --unified=0`, newline included).
The new no-change regression uses a private physical canonical store to
assert the real branch head; its first run passed its assertions but cost
6.100 s including fixture setup. It now declares `:seon.test/long-ms 10000`
with that reason. Other new tests are below 5 s.

Foreign boundary: shell lint briefly refused the concurrently edited
`test/seon/fn/schema_shape_test.clj:84:70` with `Unmatched bracket: unexpected )`.
The own archive excluded that edit. Fast snapshots used HEAD for the named
foreign dirty callers (`src/seon/issue/detect.clj`, test runner/selection
tests); no foreign file or session was edited. `src/seon/instrument.clj`
is currently dirty in the schema-shape lane and remains untouched here.

Final fast run `4e66d8f05168`: **5 executed, 17 assertions, 0 failures,
0 errors**. The private-store test cost 8.386 s inside its declared 10 s;
the remaining tests were below 5 s. The explicit namespace require passed
in a fresh HEAD-plus-owned-files archive after the live root was down:
`(require 'seon.cluster 'seon.cluster.source 'seon.fn 'seon.test.cache)`.
The fast snapshot and load archive exclude the active schema-shape edits.
The orchestrator's cold gate is still owed.

## Slice 4, remaining item 4 — adoption compares commit identities

Deleted the post-adoption filesystem snapshot. The existing entry check
compares the cluster row’s `:seon.source/commit-id` with the publication;
reconciliation and loaded definitions must succeed before the existing
adoption transaction records that commit. The existing lifecycle lock
serializes publication/adoption. No new check, cache or mechanism.

Before: **7826.353 ms**, with **580.073 ms** in post-adoption snapshot
verification. After: **11707.899 ms** for the reverse docstring edit in
`my.note`, with **zero source snapshot calls**. This is not an overall
speedup claim. No individual after span exceeds 2 s, but the sum exceeds
the owner’s 10 s bound; that path is not repeated before deleting the
remaining O(program) manifest work in item 5.

[The live probe](one-jvm-adoption-commit-live-2026-09-23.clj) re-evaluates
the two edited entry definitions in the own-root host and asserts zero
whole-tree snapshots around real publication/adoption. [Raw result](one-jvm-adoption-commit-after-2026-09-23.edn).

| Completed phase | after ms |
|---|---:|
| request | 2.646 |
| request accepted | 3.690 |
| bootstrap configuration | 366.272 |
| store acquisition | 1.054 |
| source build | 1251.159 |
| published manifest read | 293.726 |
| published manifest validation | 778.911 |
| published database acquisition | 0.510 |
| analysis started | 28.323 |
| analysis input inventory | 190.291 |
| analysis caller files | 1301.277 |
| analysis selected files | 443.236 |
| analysis replace artifacts | 168.897 |
| analysis manifest complete | 31.074 |
| analysis complete | 1.581 |
| findings in analyzed files: 0; added=0; resolved=0 | 0.569 |
| branch publication started: 1 inputs | 430.765 |
| program rows started | 434.024 |
| contract projection started: 3336 schemas, 3 functions | 0.194 |
| contract projection complete | 2.327 |
| contract rows: 1/5 | 17.953 |
| contract rows: 2/5 | 13.812 |
| contract rows: 3/5 | 9.901 |
| contract rows: 4/5 | 0.518 |
| contract rows: 5/5 | 83.803 |
| development reconciliation transaction | 1134.876 |
| program rows complete | 0.335 |
| publication source identity | 73.141 |
| publication branch head | 94.305 |
| branch publication complete | 1439.373 |
| development changed program rows | 1046.801 |
| development reconciliation transaction | 1343.393 |
| development loaded definitions | 8.751 |
| development JVM instrumentation | 500.701 |
| development adoption record | 208.517 |
| development cluster converged | 1.193 |

Exact production changed-line UTF-8 bytes: **490 deleted, 55 added**.
Fast run `79c3ba60f67e`: **4 executed, 13 assertions, zero failures or
errors**. The private-store test retains its declared 10 s fixture bound.
`(require 'seon.cluster)` passed in the HEAD-plus-owned-files archive with
no live own-root JVM concurrent with that load or fast run. The own host
was started only after both exited. Foreign shape/instrumentation edits
remain excluded; the orchestrator owns the cold proof.


## Item 5 probe and shared-file boundary

The [database probe](one-jvm-manifest-database-probe-2026-09-23.clj), run
through the own scratch cluster's prepl, compared the stored artifact with
`seon.fn/published-index-rows` over that published database value. Its
[complete result](one-jvm-manifest-database-probe-2026-09-23.edn) has five
artifact rows and five database rows. Only `:seon.fn/arities` differs on
`my.note/add!`, `my.note/forget!`, and `my.note/notes`: those are the compiled
contract rows. All 3,336 manifest schema declaration digests equal the
digests derived from the database value's carried projection. This probe
found no missing schema provenance requiring a new fact or owner decision.
It does not yet prove equivalence for every declaration or finding shape.

Item 5 has no production edit yet. Its remaining work is to select the
changed files' declaration/finding rows by their existing file refs, use
the carried projection, preserve capability validation against the database
call graph, and derive the offline exported manifest only at the export
seam. Reconstructing every artifact on each edit would retain O(program)
work and would not satisfy the ruling.

At this checkpoint `src/seon/fn.clj` has a foreign unstaged line in
`add-contract-facts`: `(schema-shape/prepare-forms projection)`.
`src/seon/instrument.clj` has foreign unstaged edits (8 added / 10 deleted
lines). Neither was changed by this lane. The slice 4 assignment explicitly
says to STOP and name instrumentation when it is dirty; this checkpoint
honors that boundary. No claim that the probe exposed a design gap or that
slice 4 is landed.

The own scratch root was downed through `bin/seon --root
tmp/one-jvm-redesign-root down`: recorded PID 95239 exited and the operator
reported the store flock free. The own source/load archives were removed
after that exit. No default or foreign root was operated. This checkpoint
adds evidence only; the last production load/fast proof remains the item 4
proof above. Production bytes changed here: **0**.


## Free-file continuation: algorithm audit before edits to the spans

The 11,707.899 ms row is iteration evidence under concurrent lane load;
the orchestrator's quiet run remains the landing row. These are intervals
between progress callbacks, not timings of functions with matching names.

* **"branch publication complete", 1439.373 ms:** the callback is emitted
  before `write-source-artifact!` in `full-source-refresh!`. The following
  interval serializes and atomically writes the **entire manifest** with
  `pr-str`, releases the publication database values, enters adoption and
  acquires its published projection. `source/database` unconditionally calls
  `schema/projection-from-database`; that derives all schema rows, function
  contracts and function source rows. Both the manifest serialization and
  projection acquisition are O(program), not work required by three changed
  functions. The interval ends at "development changed program rows"; it
  does not establish a 1439 ms Datahike branch commit.
* **"development changed program rows", 1046.801 ms:** from that callback
  through the next "development reconciliation transaction" callback,
  `seon.fn/index!` derives `program/shapes-in` from its six entity roots,
  calls `published-index-rows` on the changed identities, checks duplicate
  identities and probes for any existing namespace/function/test row.
  Identity selection is scoped. However, portable-reference conversion
  wildcard-pulls each referenced entity before extracting its identity;
  compiled arities lead to schema-shape rows, including the large expanded
  forms being removed by the schema-shape lane. Thus selected rows do not
  imply selected **bytes**. The three unbound existence queries select from all namespace/function/test
  identities instead of seeking one changed identity. The shape-owner read
  itself is bounded by six entity schemas, not all declarations. Pulling
  complete shared shape values merely to obtain their identities is
  unnecessary. The actual reconciliation transaction begins
  only after this interval. Required held-file changes: omit the fresh-store existence probe when an
  explicit previous database is supplied, and seek a referenced entity's identity before
  recursively reading identity-less owned children.

No modification to either audited span is claimed by this audit.


## Config: the writer submits the fact difference

`apply-compiled!` already compared the desired config and initialization
through `reconcile/plan` and returned without a transaction for an empty
plan. Its writer callback nevertheless first submitted **every desired row**
and only then recomputed the plan. It now computes the plan against the
writer's database, resolves lookup refs/tempids in only the changed maps,
and submits that exact difference. The existing initialization and direct
hand-edit repair regressions remain; the added regression observes the real
writer callback and requires one dial retraction plus one three-key map
(identity, db/id, changed dial), followed by an unchanged basis on reapply.

Deleted the applied-manifest digest computation, writer, renderer display,
attribute and entity/compiled schema entries. No production reader remains.
**RESET NEEDED: `:seon.config/applied-manifest-digest` removed.** Historical
parity evidence and foreign-held fixture docstrings are not runtime readers;
the latter still need their digest wording retired by their owning lane.

The generic `reconcile/plan` still enumerates installed identity facts and
historical first assertions before selecting the config process's population
(`src/seon/reconcile.cljc`, `current-identity-facts`,
`first-assertion-transactions`, `process-by-transaction`). That is O(program
history), not O(config). This commit fixes the submitted transaction, not
that acquisition cost. Its existing process provenance is sufficient to
select candidate entities by config process transaction refs first, then
check first assertion only for those candidates and explicitly adopted
identities. No stored digest or new cache is needed for that follow-up.

Fast command: `bin/test-fast --paths src/seon/config.clj
src/seon/schema/edn.clj resources/seon/schemas/seon.config.edn
test/seon/config_test.clj -- seon.config-test`. Run `e054d77fdb5b` was refused
**before test execution** by `seon.test.runner/record-snapshot!`:
`Missing schema reference :seon.config/applied-manifest-digest from namespace
seon.config.` The recording host still has the old generated config entity
schema. It is not a test red or a green tally; the orchestrator's reset/cold
proof is owed. No test-system file or default process was modified.

An explicit `(require 'seon.config 'seon.schema.edn 'seon.config-test)`
passed in a clean HEAD-plus-owned-paths source archive, after the fast JVM
exited. No live scratch root exists during these JVM runs. There is no new
publication span table: no publication measurement was run through a stale
schema. The prior 11,708 ms row remains iteration evidence only.

Exact changed-line UTF-8 bytes (production plus regression; excludes diff markers, includes line endings): **2586 deleted, 2693 added**.


## Item 5 free-file hunk: previous findings from file refs

`source/file-rows` binds requested paths to file identities, then follows
`:seon.fn/file` or `:seon.lint/file` and selects existing program identities.
`published-index-rows` supplies the existing portable-row conversion. The
publication warning delta now acquires previous findings through that query;
it no longer scans the prior manifest to select its findings. A file digest
row without a declaration ref does not become an analysis input. The added
regression requires the three `my.note` declarations, excludes an unrelated
schema input, and checks empty/missing selections.

This is the free-file part of item 5, not deletion of the stored manifest.
`build-manifest` still requires the prior artifacts for its capability and
known-function checks. Deleting its producer in cluster.clj before converting
those consumers would make a warm edit a complete analysis or change the
checks' semantics. The prior database equivalence probe is the grounding for
that conversion.

Fast run `7974e8b92309` again stopped at `record-snapshot!` before any test
executed, with the exact missing applied-manifest-digest schema refusal
recorded above. The published base is 65 commits behind the tested HEAD.
This is no runtime/timing proof; no additional publication was attempted.
The quiet-run span table is owed after reset, with the previous 11,708 ms
row retained as iteration evidence rather than a landing result.

### Exact held-owner edits to carry forward together

The following replacement chunks are **not independently landable**. They
must be connected to caller analysis on the unpublished branch before its
head is published. During this turn `c6db6b358` committed the previously held
shape edits; this lane has not modified fn.clj or instrument.clj.

In `seon.fn/index!`, retain the existing transaction report on the result:

```diff
          {:seon.reconcile/converged? (empty? changed-identities)
           :seon.reconcile/operations (count changed-identities)
+          :seon.db/transaction-report report
           :seon.reconcile/adopt-identities changed-identities})
```

In `seon.fn/caller-files`, accept the changed function symbols from that
report instead of treating every declaration in a changed file as a changed
contract. Replace its query and input expression with:

```clojure
(db/q '[:find [?path ...]
        :in $ [?symbol ...]
        :where
        [?caller :seon.fn/calls ?symbol]
        [?caller :seon.fn/file ?caller-file]
        [?caller-file :seon.fn.file/relative-path ?path]]
      database (vec changed))
```

If called from cluster/source.clj, change `defn- caller-files` to
`defn caller-files` in that same cut. The contract's second argument becomes
`[:set :qualified-symbol]`; its
name/docstring must state changed contract symbols. Empty symbols means zero
caller files. Contract change includes changed referenced schema definitions,
not just a changed `:seon.fn/spec` string: use the before/after projections'
contract and referenced definitions, as `instrument/current-wrapper?` already
does. A docstring-only transaction supplies no changed contract symbols.

In `build-manifest`, replace the pre-analysis caller expansion binding with
`paths changed`. Do not apply this alone: after `index!` returns its report,
read callers only for changed contracts and lint those files before moving
the branch head. The already-existing `analyzed-artifacts` function is the
partial clj-kondo seam; its `defn-` must become a contracted public `defn`
if the cluster population caller owns that second analysis. No second cache,
full source walk, or publication of a partially checked head is involved.
Caller findings must replace the previous findings in those files, including
retracting findings that disappeared; unchanged caller declaration rows must
not be reasserted merely because they were linted.

For item 5's remaining fn.clj conversion, the required replacement boundaries
are `build-manifest`, `manifest-data`/`assert-capability-contracts!`, and the
previous-row consumers in `desired-rows`/`index!`:

* Read only selected file/declaration/finding rows from the previous database;
  remove generated `:seon.fn/arities` before comparing authored declarations.
  Use the existing `declaration-digests` function on those rows. Keep the
  database value available for handler/call lookups outside the selected files.
* Delete the all-file `analysis-paths` query and all-artifact known-symbol
  inventory for a partial request. Partial inputs are the explicit changed
  paths; clj-kondo supplies namespace resolution from its own cache.
* Compare schema resource changes against the carried projection's forms;
  do not hash every form on an ordinary source edit. Complete manifest
  derivation remains only at explicit offline export, whose existing
  `manifest.edn` protocol still has test-system consumers.
* Resolve referenced entities' identity attributes before wildcard-pulling
  their contents in `published-index-rows`. Only identity-less components
  require recursive row conversion. Skip index!'s unbound existing-program
  query when `previous-database` is supplied.

These larger replacement bodies have not been fabricated as an untested
patch: the capability checks and offline export consumers must be converted
in the same loading commit. There is no required instrument.clj hunk for the
free-file finding selection. The current `current-wrapper?` already compares
contract plus referenced definitions; item 8 still needs its wrapper-set
regression after namespace reload selection changes.

### Caller-analysis ordering decision

The requested **actual transaction report** exists after the changed rows
are transacted. Today all lint runs before that transaction and the report
is reduced to identities. The concrete choices are:

1. **Recommended: use the actual report on the existing unpublished scratch
   branch.** Analyze changed files, transact their declarations, then analyze
   caller files only for changed contracts and transact their finding delta
   before `force-branch!`. Docstring edit: one file lint, no caller transaction.
   Contract edit: one extra partial clj-kondo call plus at most one finding
   transaction; failed caller analysis never moves current-src. No new cache
   or retained report is required.
2. Compare changed analysis with the immutable previous database **before**
   the transaction. Keeps one population transaction and one combined result,
   but changes the explicit ruling that the actual report selects callers.
3. Obtain a speculative Datahike `with` report, lint callers, then transact.
   Preserves a report-shaped selection before publication, but validates the
   changed declarations twice and needs a new first-party seam; largest cost
   and least deletion. Not recommended.

No measured time is assigned to an unimplemented option. The next cut needs
option 1's ordering confirmed or the report requirement explicitly changed;
this note records the dependency instead of installing an unused callback.

Free-file changed-line UTF-8 bytes, production plus regression: **221 deleted, 2151 added** (without diff markers, including line endings).

An explicit `(require 'seon.cluster 'seon.cluster.source
'seon.cluster.publication-inputs-test)` passed in the source archive with
only the selected files overlaid. This proves loading, not the unexecuted
regression. The archive was removed after its JVM exited; no scratch host
or shell is retained.


## Item 5: database-selected artifacts — 2026-09-23

Before-edit evidence is [one-jvm-manifest-before-2026-09-23.edn](one-jvm-manifest-before-2026-09-23.edn), produced by the existing adoption measurement script through the advertised prepl of `tmp/one-jvm-redesign-root`, cluster `s`. Source archive HEAD was `1020775ab`; the host was downed before fast verification. Phase sum is **5695.306 ms**. These are iteration numbers under shared-machine load, not a quiet landing row.

| Completed phase | Before ms |
|---|---:|
| source build | 609.226 |
| published manifest read | 176.024 |
| published manifest validation | 439.466 |
| analysis caller files | 742.041 |
| analysis selected files | 201.576 |
| analysis complete | 597.474 |
| publication reconciliation transaction | 357.021 |
| branch publication complete | 788.773 |
| development changed program rows | 79.790 |
| adoption reconciliation transaction | 407.245 |
| JVM instrumentation | 197.590 |

The earlier 1047 ms changed-program read expands portable rows and their component refs; the authored-shape change reduces this same read to 79.790 ms in this observation. It is selected by report identities, not a whole-program read. The branch-complete span includes extraction/reconciliation of publication results and scoped issue work; the remaining caller expansion is still pre-transaction and remains item 6, not claimed fixed here.

Item 5 removes the on-disk manifest read, validation and ordinary-publication write. `seon.fn/database-manifest` selects file identities through declaration refs and acquires only those declarations/findings through the existing portable-row reader, omitting compiled arity components. The complete manifest remains an explicitly requested test-base export. Partial analysis acquires known names through an indexed query of the analyzed file's referenced names; ordinary contract projection reuses the database's carried projection. Capability checks compare the selected declaration facts and query their referrers; unchanged docstrings do not walk the capability graph. No second analysis cache is added.

Production `(require 'seon.fn 'seon.cluster.source 'seon.cluster)` passed. An earlier load command also named test namespaces without the test alias and failed because those namespaces were not on that classpath; the subsequent production load is the valid load result. Fast run `de2a2099de86`: 71 executed, 372 assertions, 9 failures, 18 errors. Two exact old-contract boundaries and affected selected-file regressions are recorded in [the existing fixture issue](../../../seon/issues/canonical-fixture-retains-old-function-contracts-after-adoption.md). `a-refused-reference-read-refuses-gate-set-derivation` separately reports `seon.fn/declared-reference-edges returned undeclared error facets #{:seon.schema/validation-refusal}.` The test that enumerated all file rows as lint inputs has now been corrected to join `:seon.fn/file`; this correction has not yet been rerun. Other failures remain unclassified; this is not a green claim.

Current production/test diff bytes (before this note): **12584 deleted, 17881 added**. The orchestrator authorized committing this item on namespace loading and clean clj-kondo checks, then preparing a HEAD-only base. After-measurement and armed assertions remain owed. The 9 failures and 18 errors are unverified against this implementation until that fresh-base rerun; no claim attributes all of them to stale contracts. Foreign dirty files are the test-system runner and its tests; none were edited. The lane's scratch host is down. Report-directed caller lint and changed-namespace reload remain subsequent commits, as ordered.

Commit verification repeated after the ruling: production namespaces load; clj-kondo reports **0 errors, 61 warnings** across the selected production/test paths. Thus “clean” here means no blocking errors, not warning-free source. No retired reader or `seon.cluster.source/file-rows` callers remain under `src`, `test`, or `script`. The next action is the orchestrator’s HEAD-only base preparation, followed by the same fast namespaces; surviving reds must be fixed before items 6 and 8.


## Explicit export must inspect its checkout — 2026-09-23

Fresh-base rerun `d4ea3973438d`: **71 executed, 372 assertions, 8 failures,
18 errors**. Base `5cfdc9ae3d…` reports zero commits behind `d1fa4561d`, but
its `base/manifest.edn` contains the old `analyzed-artifacts` set argument
and only two `published-index-rows` arities. This is a real export defect:
`publication-base!` passed an empty changed-path list, and the ruled empty
request correctly inspects no files. The preparation label therefore did not
prove that the export contained HEAD. The exact refusals and source evidence
are in [the fixture issue](../../../seon/issues/canonical-fixture-retains-old-function-contracts-after-adoption.md).

The fix uses the existing `seon.test.cache/input-paths` inventory (now public
and contracted), unions stored input paths for removed files, then calls the
same `refresh-source!`. This explicit complete-checkout request hashes those
paths once at `source/path-digests`; normal edits retain their original
changed-path bound. No file cache or second publication operation is added.
The export regression starts with the canonical published base and checks
that its exported reader contract matches the checked-out declaration.

Production `seon.cluster` and `seon.test.cache` load. Focused fast run
`f9536b35fff9`: **1 test, 2 assertions, 0 failures, 2 errors**. The first is
`seon.fn/published-index-rows refused argument count at []: expected the
declared arglists, got an argument count of 3`, at the selected-row read
before analysis. The second was reading the absent export after that refusal;
the regression now lets the export exception terminate its body, avoiding
that misleading secondary error. The final test edit is linted, not yet
rerun. This fix must be active in the publisher before another base
preparation can establish new program contracts. A prepared label alone is
not that evidence.

Changed-line bytes for production plus regression: **269 deleted,
1996 added**. Before measurement remains **5695.306 ms**; no after row is
claimed while the published source/contract mismatch blocks the assertions.
Other surviving test failures remain pending diagnosis against the actual
updated program. The scratch host is down; no foreign runner or test-system
file was edited. Items 6 and 8 remain ordered after this verification.


## Export request carries snapshot paths — 2026-09-23

Base `2c3e6b2247…`, labelled HEAD `ae6a0cdd4`, still contains the old set
argument in `analyzed-artifacts`. Rerun `ac30944affeb`: **71 tests, 372
assertions, 8 failures, 18 errors**. The remaining seam is
`seon.test.cache/prepare-base!`: its prepl form resolves the live host's
loaded `publication-base!`; compiling the launcher does not replace that
host Var. The previous export-body fix was therefore not exercised by that
host. This is observed in the exported source, not inferred from its label.

The current launcher now sends its exact snapshot inventory to the existing
four-argument `refresh-source!` before requesting export. Base preparation
also compares the exported per-path digests with the requested declared
inputs before writing `ready.edn`; a mismatch refuses with changed/removed
paths instead of labelling old facts as HEAD. No alternate writer, reload
roster, child JVM or retry was added. On an updated host, explicit export may
repeat the complete inventory comparison; that O(checkout) work belongs to
explicit base preparation, not a one-file edit. Normal publication remains
O(requested paths) before analysis.

`seon.test.cache` loads. Fast `ce41167db977`: **3 tests, 22 assertions,
0 failures, 0 errors**. These exercise existing cache behavior, not a live
base preparation; the orchestrator owns that operation. Changed-line source
bytes: **0 deleted, 779 added**. Before row stays **5695.306 ms**;
after publication measurement is still owed. While the shape lane held
`fn.clj`, work continued on namespace selection in `cluster.clj`; its separate
uncommitted diff is excluded from this commit.
