---
type: landing
status: complete
created: 2026-09-22
tags: [agent-platform, platform, schema, fixtures, verification]
---

# Platform regressions: schema refusal and fixture acquisition

## Outcome

Commits `c1d059abb59d251183bcf4b60dd9ef45370e5376` and
`732866ead311b0c1701113182748577f0cb4d9fa` fix the three platform regressions
reported from cold gate `tmp/cold-gate-2026-09-22d.log`. The final focused
request executed 22 tests and 175 assertions with no failures or errors. No test
bound or assertion was weakened.

The task named HEAD `789eb63b0`; the shared tree was already at
`446db7223ff52fd8f4bcc4f588d78c95e2531001` when this lane began. The two
pre-existing modified documentation files and every pre-existing untracked file
were excluded from snapshots and commits and remain untouched.

## Schema refusal

`seon.schema/declaration-population` constructed the missing-projection
exception directly and supplied only `:seon.schema/missing-projection`, a
message and caller evidence. The B3 kind cut removed the retired
`:seon.error/kind`; it did not retire `:seon.schema/expected-value`. The kind-cut
domain table retains that member as the schema-refusal decision member, and the
installed `:seon.schema/validation-refusal` declaration requires both
`:seon.schema/refused-value` and `:seon.schema/expected-value`.

The producer now uses `seon.error.refusal/diagnostic` and identifies:

- operation: `seon.schema/declaration-population`;
- member and expected shape: `:seon.schema/projection`;
- offending value: `nil`;
- caller evidence and the existing `:seon.schema/missing-projection` facet.

The durable `:seon.schema/missing-projection-error` facet remains the small,
open distinguishing facet. It was not made to extend the transient validation
schema: doing so would incorrectly require transient arbitrary values to become
stored attributes. The producer value nevertheless validates as the already
declared raw `:seon.schema/validation-refusal` shape.
The regression now checks that declared schema directly, plus the exact expected
and refused values, before confirming that the refusal read no resources.

## Fixture acquisition root cause

Baseline focused run `1e862f601009` measured the retry test at **4,442.226 ms**
and the canonical-open test at **4,510.850 ms** on this machine, close enough to
the 5,000 ms platform bound to reproduce the cold-gate sensitivity. Dedicated
baseline timing run `a6218966ab34` measured first acquisition at
**4,625.091 ms**:

| phase | milliseconds |
| --- | ---: |
| copied-store re-identification | 3,547.522 |
| directory clone | 469.808 |
| projection from database | 522.262 |
| Datahike connect | 104.173 |

The expensive work was not a cold JVM step or full projection acquisition.
`seon.cluster.export/reidentify!` walked and rewrote **266 reachable branch-head
and commit records** in every private fixture copy. That work is proportional
to the published store's retained commit ancestry. The fixture then opens and
branches only from the current published head; it never uses an exact retained
commit as a branch source.

The owner now exposes `reidentify-branches!`, which preserves the copied store's
path-derived identity while rewriting only explicitly supplied branch heads.
Ordinary fixture bases name `:current-src`; the operator-root fixture names
`:db` and `:current-src`. Full `export!` and `reidentify!` retain the original
complete ancestry walk, including exact-commit branching guarantees.

Dependency seam inspected before the change:

- pin: Datahike `6dd49e5eab243a42ebd8d847830d037d0dae3c6a` through
  `deps.edn` local root `reference-code/datahike`;
- source: `datahike.connector/-connect-impl*` and
  `datahike.store/ready-store`;
- guarantee: connect reads the selected stored branch and the tiered store
  populates its memory frontend from the copied backend;
- supplied input: copied file-store configuration plus selected branch;
- recomputation: once for the first private fixture base, then isolated branch
  acquisition from the held base;
- proportional work after the fix: one selected head re-identification,
  directory copy, one Datahike connection and one database projection. The
  measured projection still processes 3,277 schemas and 1,788 contracts but
  takes about 487 ms rather than dominating the operation.

Dedicated post-fix timing run `f421bf4d3250` measured first acquisition at
**1,095.423 ms**, directory clone at **470.441 ms**, projection acquisition at
**487.034 ms**, Datahike connect at **106.337 ms**, and warm fixture median at
**37.060 ms**. It executed 1 test / 17 assertions with no failures or errors.
No `:seon.test/long-ms` declaration was added because the owner work is now
comfortably within the ordinary five-second bound.

## Required three measurements

Each request used one test JVM and the path overlay
`src/seon/schema.clj src/seon/cluster/export.clj test/seon/test_support.clj`.
Minor final docstring clarifications changed the program digest between requests,
so all 22 tests executed in every request rather than reusing recorded greens.

| run | missing-projection regression | retry construction | canonical open | result |
| --- | --- | ---: | ---: | --- |
| `0c55015c4f2e` | pass | 977.230 ms | 1,041.391 ms | 22 / 173, green |
| `de8842168dc5` | pass | 996.625 ms | 1,052.855 ms | 22 / 173, green |
| `c3c5de8164c8` | pass | 994.511 ms | 1,080.526 ms | 22 / 173, green |

After strengthening the schema regression, final run `2fd59f5e04a5` executed
22 tests / 175 assertions with no failures or errors. Its retry construction
measured **997.012 ms** and canonical open measured **1,073.826 ms**.

Command shape:

```sh
bin/test-fast --paths src/seon/schema.clj src/seon/cluster/export.clj test/seon/test_support.clj test/seon/schema/declaration_population_test.clj -- seon.schema.declaration-population-test seon.test-support-test
```

The complete export contract separately passed run `d75c75c7f56c`: 6 tests,
27 assertions, no failures or errors. This exercised complete branch and commit
re-identification, opening a non-`:db` branch, exact-commit branching,
idempotence, fallback export and refusal of incomplete stores.

## Load and operational boundary

The prescribed command

```sh
clojure -M -e "(require 'seon.schema 'seon.test-support)"
```

exited 1 because `seon.test-support` lives under `test/`, which the default
classpath does not include. This is a command/classpath mismatch, not a source
load failure. The installed test-classpath equivalent

```sh
clojure -M:test -e "(require 'seon.schema 'seon.test-support)"
```

exited 0. `clojure -M -e "(require 'seon.schema)"` also exited 0. Focused test
JVMs loaded both namespaces with 1,690 contracts registered and instrumented.
Final read-only status still reported the untouched default PID 38968 and start
instant `2026-09-22T12:04:10.581Z`. The lane did not restart, reset, publish to,
or otherwise operate that cluster. Cold-gate/platform integration remains the
orchestrator's stated follow-up boundary.

The repository markdown checker reported 11 existing stale Datahike-pin
citations in other landing notes and one skill reference: those files cite older
pins while the current gitlink is
`6dd49e5eab243a42ebd8d847830d037d0dae3c6a`. They are outside this lane's owned
paths and were not edited; this landing note itself passes `git diff --check`.

## Changed paths

- `src/seon/schema.clj`
- `src/seon/cluster/export.clj`
- `test/seon/test_support.clj`
- `test/seon/schema/declaration_population_test.clj`
- this landing note

The code commit changed 67 lines inserted and 15 deleted across its three owned
paths. No schema resource bytes changed because the ruled validation-refusal
declaration was already present and correct.
