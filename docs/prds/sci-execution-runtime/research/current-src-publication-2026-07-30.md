---
type: research
status: active
tags: [prd, research, datahike, indexing, clusters]
---

# `current-src` publication archaeology and dependency grounding

## Decision

Seon has one published `:current-src` database branch. Its branch head commit
ID is the exact input to every new experiment cluster fork. Ordinary file
edits use clj-kondo's cache and a per-file first-party artifact to update that
branch. A removed identity, changed global schema, missing artifact, analysis
failure, component/cardinality-many diff, or uncertain projection selects a
complete scratch build. Publication changes only `:current-src`; existing
clusters are sovereign and never receive file edits.

The branch is never an execution cluster and has no long-lived connection.
The source digest remains provenance and cache evidence, not a branch name.
"Ancestor" remains only the ordinary database relationship between a fork and
its parent commit; it is not a Seon subsystem or operator concept.

## Dependency ledger

| Dependency or mechanism | Selected source | Evidence used here |
|---|---|---|
| Datahike | maintained checkout under `reference-code/datahike/` | `src/datahike/versioning.cljc:237-291,323-499`; `src/datahike/api/impl.cljc:332-349,365-383`; `src/datahike/gc.cljc:22-81,100-143` |
| Proximum secondary indices | maintained sources reached through Datahike | `reference-code/datahike/src-secondary/datahike/index/secondary/proximum.clj:388-459`; `reference-code/proximum/src/proximum/versioning.clj:119-182` |
| clj-kondo | v2026.07.24 plus maintained commits `0fc2f636`, `57252e07` | `reference-code/clj-kondo/src/clj_kondo/core.clj:67-106`; `src/clj_kondo/impl/metadata.clj`; `src/clj_kondo/impl/analyzer.clj:984-989`; `parser/clj_kondo/impl/rewrite_clj/node/keyword.clj`; cache and parallel evidence in `clj-kondo-source-audit-2026-07-30.md` |
| Seon branch registry | current maintained owner | `src/seon/cluster/registry.clj`, especially branch creation from a keyword or commit UUID |

## What history actually proved

The selected design evolves earlier proven mechanisms; it does not restore a
lost `current-src` implementation.

- `c669c2f6b` built an immutable release-keyed template database completely,
  closed it, then cloned through a temporary directory and atomic move. Keep
  complete-before-visible publication; delete directory cloning and reflinks.
- `a35c95d0a` replaced templates with branch-per-cluster, scratch population,
  publish-at-end, and commit-aware registry operations. Its measured branch-off
  was approximately 17 ms and one blob. Keep the branch registry and exact
  commit fork; delete digest-derived ancestor branch accumulation.
- Datahike commit `357ffc87` serialized branch-roster mutation per store. The
  earlier implementation could silently lose branch entries under concurrent
  creation. The maintained fork and Seon's single registry owner remain
  required.
- `d33b29cf9` used analyzer output rather than textual pattern matching and
  derived dependency closure. `45d288afb` deleted namespace hand lists and
  derived first-party ownership from analyzer paths, including namespaces with
  no functions. `fcffcdfdf` hardened dependency/vendor exclusion. The current
  clj-kondo projection keeps those lessons.
- `3fdde32d4`, `4b3d32093`, `ddf2c5aa6`, and `13ebc881d` built deterministic
  complete source/row inventories, exact digests, watch-flush preparation,
  atomic artifact publication, and precomputed base projections. Their
  analyzer/compiler work was incremental while publication remained a complete
  snapshot.
- `f9c8686c3` proved identity-only dedup leaves stale function contracts.
  Correct comparison includes every derived field and retracts disappeared
  optional fields.
- `e643728a4` unified add/change/delete, but needed source-datom provenance,
  agent-home exclusions, registration-call exceptions, empty-population
  guards, component diffs, and optional-field retractions because build and
  agent data shared one database. A dedicated build-only `current-src` removes
  those ownership exceptions. Do not port that reconciliation machinery.

`git log -S current-src` found no historical first-party implementation of the
exact selected model. Per-file database publication is new and must remain
narrow. History supports cached incremental analysis feeding an atomically
published base, not a claim that this database updater was already built.

## Maintained Datahike integration

### Exact commit forks

`datahike.versioning/branch!` accepts a branch keyword or commit UUID
(`versioning.cljc:237-291`). `branch-as-db` materializes one branch head and
`commit-id` extracts its immutable commit ID (`versioning.cljc:446-499`). A
cluster first snapshots `:current-src`'s commit, then passes that UUID to the
existing registry `branch!`. A later source publication cannot change what the
new cluster inherited.

### Guarded publication

`force-branch!` (`versioning.cljc:323-444`) already implements the required
publication primitive:

- it writes immutable values before the mutable branch head;
- it accepts `:expected-current-commit` and checks it both before mutation and
  inside the head update;
- it updates the one branch head with `k/update`;
- it publishes roster discovery only after the head exists; and
- it reads the head back and verifies the new commit ID.

It returns `nil`, so Seon must materialize `:current-src` afterward and return
the actual commit ID. The store-wide `flock` supplies exclusive process
ownership and Seon's in-process publication monitor serializes editor events.
Existing `:current-src` connections would be stale after force and are
forbidden.

Initial publication branches the completed scratch head to `:current-src`.
Later publication forces the completed scratch database value onto
`:current-src` with the previously observed current commit as both expected
head and parent. This retains source-publication history without making the
scratch branch a strict ancestor; the existing safe branch-retirement policy
therefore needs no exception.

### Secondary indices and recovery

The Datahike operation preflights and moves maintained Proximum secondary
indices before the primary head (`src-secondary/.../proximum.clj:388-459`).
The two stores cannot share one physical transaction. Proximum's guarded force
is retry-safe when the desired source generation was already adopted
(`reference-code/proximum/src/proximum/versioning.clj:119-182`), and the
dependency tests cover a crash after the secondary force while the primary
head remains unchanged. Seon must retain the expected-head guard and retry the
same completed publication, not invent another journal.

## Measured cost boundary

On 2026-07-30, the rich first-party analysis covered 123 files and produced
2,061 rows. Same-JVM timings were 3.28 seconds on the first run and 1.76–1.99
seconds warm. Starting a separate JVM made the operation 16.58 seconds, so a
child JVM is forbidden on the edit path. Warm source-string analysis measured
5–32 ms. The historical branch-off measurement is approximately 17 ms.
An integrated fresh file-store proof then measured 9,337.9 ms for complete
analysis, population, publication, and artifact write, followed by 609.3 ms
for a warm unchanged single-file analysis, transaction, guarded publication,
scratch retirement, and atomic artifact replacement. The latter deliberately
includes database publication rather than reporting analyzer time as the whole
operation. Its branch advanced from commit
`6a6b4e86-f8a2-5553-9470-f9e4251cdd57` to
`6a6b4e87-4ec7-51e0-8abf-c5f16b7c8cee`; the recorded source digest remained
`1127772f3fd0968a9add4fd6f3b88a1b61e9d10bf89f460486d72d76a02ac490`, and
`build/current-src.edn` existed after both operations. This is a development
measurement, not a service-level guarantee.

## Static metadata source dive

The first static projection exposed a dependency defect that evaluation had
hidden. clj-kondo's analysis export materialized `::request` in function
attr-map metadata as `:user/request`, even though the analyzed namespace was
`seon.flow`. `KeywordNode/sexpr` fell back to the analyzer JVM's dynamic
`*ns*`; leading metadata passed through `impl.metadata`, while `defn` and `ns`
attr maps called raw `sexpr` directly in `impl.analyzer` and
`impl.analyzer.namespace`.

Maintained commits `0fc2f636` and `57252e07` bind the analyzed namespace and
aliases at every metadata materialization path. The dependency regression
covers both `^:meta` and the attr map after a function name. A fresh-cache
probe now exports
`[:seon.flow/request :seon.flow/source-enumerator-proc-request]`, not
`:user/...`. This source evidence matters: changing Seon's cache key would
only have hidden the dependency bug again.

The corrected export then revealed two first-party contracts that had relied
on evaluation turning predicate symbols into function objects. Their durable
forms now use explicit `[:fn qualified/predicate]` schemas. A fresh
`current-src` projection compiled 559 packaged schema forms without evaluating
repository source.

## Packaged schemas versus ambient registration

The build input is the bootstrap population plus `resources/seon/schema/*.edn`,
derived by `seon.schema.edn/packaged-forms`. It deliberately excludes
process-global registrations made by a test or REPL session. Schemas remain
global database identities rather than namespace children, but "global" does
not mean an unrelated live JVM can silently change the packaged source branch.
Runtime schema admission commits cluster facts; source publication reads the
package resources.

The Datahike public namespace is generated from
`datahike.api.specification/api-specification`. clj-kondo originally reported
valid `d/q`, `d/transact`, `d/pull`, and `d/release` calls as unresolved because
the checked-in generated export lagged the operation table. Datahike already
owns the unifying mechanism: `bb codegen-clj-kondo` derives its consumer export
from that table. Regeneration now exports all 63 public operations, including
the maintained versioning API; source macro extraction also makes direct source
lint aware of `emit-api` without another handwritten definition list.

A clean native dependency-cache proof copied the Datahike export in 2.5 seconds;
the subsequent consumer lint of `src/seon/cluster.clj` took 25 ms with zero
errors and no unresolved Datahike public vars. Attempting to warm the complete
classpath through clj-kondo's in-process JVM API exceeded Seon's 512 MB heap;
that design was rejected. Native clj-kondo owns dependency warming, while the
application JVM consumes the cache and analyzes first-party files only.

## Proof matrix

| Case | Required observation |
|---|---|
| First publication | exactly one `:current-src` branch appears only after complete population; returned commit matches its head |
| Ordinary function edit | only the changed file is analyzed; same identities upsert; source commit advances without full scan |
| Removed or moved declaration | planner selects complete fallback; no incremental retraction path runs |
| Global schema or resource edit | planner selects complete fallback and schema lifecycle rules run on scratch |
| Analysis failure | diagnostics identify the file/form and prior `:current-src` commit remains visible |
| Stale concurrent publisher | `:stale-branch-head` refusal; winning commit remains visible |
| Scratch cleanup | scratch roster name disappears after successful publication; current source data remains queryable |
| Fork race | cluster forked from captured commit A retains A after `current-src` advances to B; later cluster inherits B |
| Sovereignty | existing cluster facts and code rows remain byte-unchanged across source publication |
| Process boundary | all publication occurs inside the flock-owning JVM; no indexing child JVM is launched |
