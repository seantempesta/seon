---
type: research
status: slice-0-implemented-reset-needed
created: 2026-09-22
tags: [publication, adoption, one-jvm, measurement]
---

# One JVM publication redesign — slice 0

Scope: delete the loaded-producer guard, then stop. RESET NEEDED: its schema
attributes are removed. The orchestrator owns the reset, cold gates and
measurement before slice 1.

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
