---
type: research
status: active
tags: [prd, architecture, runtime]
---

# Stage 2 client rename implementation readiness

## Decision

The settled pod-to-client/cluster terminology cut remains implementable as one
atomic Stage 2 unit, but the observed checkout is not freeze-ready. The rename
must wait for a stable source head, explicit lane and retained-worktree
acknowledgements, and pre-rename quiescence. It must then execute through four
uninterrupted, path-limited commits with no compatibility reader for old
persisted identities.

The current read-only census at `02a88d1e` improves the earlier readiness
picture:

- no `*/processes/pod.edn` or restore-named process record was present;
- ports 7890, 7891, 7980, 7981, 7982, and 7983 had no listener; and
- no `tmp/package-v*` release package was present.

These are observations, not freeze proof. The primary checkout had active
rename-scope edits and the ten-worktree ownership gate was not acknowledged at
one stable head. `.shadow-cljs-b2/` and `out-b2/` remain protected U-series
caches through U11; they are neither proof nor a clean-tree blocker.

## Dependency ledger

The design and sequencing authorities are:

- [[../../database-authority-mesh/research/pod-term-retirement-plan-2026-07-20]]
  for the settled terminology, persisted-form boundary, freeze protocol, and
  four-step execution order;
- [[../roadmap]] and [[../vocabulary-unification]] for Stage 2 dependency order
  and vocabulary rulings;
- [[stage2-freeze-readiness-delta-2026-07-20]] and
  [[stage2-freeze-readiness-refresh-2026-07-20]] for prior live blockers; and
- [[stage2-worktree-disposition-ledger-2026-07-20]] for the complete retained
  worktree classification.

The load-bearing dependency is the checked-out Shadow source at
`reference-code/shadow-cljs`, revision
`c98bf60f70c102abda0fd385f78cc0fcd9c25408`. Its build configuration derives a
build ID into each build map in
`src/main/shadow/cljs/devtools/config.clj`, and its worker selects the latest
runtime in `src/main/shadow/cljs/devtools/server/worker/impl.clj:787-788`.
Those facts explain why the existing build IDs stay `:client`,
`:acme-client`, and `:bench-client`, and why the MCP client must restart before
post-cut runtime-addressing proof counts. No new dependency mechanism is
required.

The first-party implementation owners are:

- `script/seon/dev/process.clj` for process identity, state filenames,
  readiness, ordering, application evidence, and generated log paths;
- `script/seon/dev/branch.clj` and `script/seon/dev/restore_state.clj` for
  retained and restore lifecycle identities;
- `script/seon/dev/release.clj`, `artifact.clj`, and `config.clj` for release
  member identities and `runtime/client.js` publication;
- `script/seon/dev/cli.clj`, `cluster.clj`, `mcp.clj`, and `changed_test.clj`
  for operator, cluster, MCP, and test surfaces;
- `src/seon/client.cljs`, `log.cljs`, `config.cljs`, `eval.cljs`, and
  `web/serve.cljs` for the supervised Bun client and its live boundaries;
- `shadow-cljs.edn`, `bin/acme`, `bin/seon-hook`, `bin/test-cljs`, config
  manifests, and their tests for build and operator integration; and
- `acme/` and `src-inspect-ai/` for downstream and evaluation-harness names.

## Exact rename map

### Process and lifecycle identities

Translate the supervised Bun process consistently:

| Old | New |
|---|---|
| `process/pod-id` | `process/client-id` |
| `:seon.dev.process/pod` | `:seon.dev.process/client` |
| `:seon.dev.process.readiness/pod` | `:seon.dev.process.readiness/client` |
| `pod.edn` | `client.edn` |
| pod port helpers and generated port paths | client port helpers and paths |
| pod readiness/application-evidence helpers | client readiness/application-evidence helpers |
| `:seon.dev.branch.phase/pod-starting` | `:seon.dev.branch.phase/client-starting` |
| `:seon.dev.branch.phase/stopping-pod` | `:seon.dev.branch.phase/stopping-client` |
| `:seon.dev.branch/pod` | `:seon.dev.branch/client` |
| retained-pod stop helpers and locals | retained-client stop helpers and locals |
| restore pod start/prove/match helpers and locals | restore client start/prove/match helpers and locals |

The restore `:seon.dev.restore/consumer-generations` map changes its process
key from `:seon.dev.process/pod` to `:seon.dev.process/client`. The shared
launch consumer predicate and every operator/test process set, order, target,
and descriptor must change in the same code-identity commit.

### Release, artifact, log, and command identities

| Old | New |
|---|---|
| `:seon.release.member/pod` | `:seon.release.member/client` |
| `:seon.dev.release/pod-member` | `:seon.dev.release/client-member` |
| release request `::pod` | release request `::client` |
| `runtime/pod.js` | `runtime/client.js` |
| `data/seon-pod/` | `data/seon-client/` |
| `logs/pod-events.log` and rotations | `logs/client-events.log` and rotations |
| generated `logs/pod.log` and hook path | `logs/client.log` |
| `bin/seon logs pod` | `bin/seon logs client` |
| `bin/seon test pod` | `bin/seon test client` |
| MCP runtime label `cljs-pod` | `cljs-client` |

The old run stores and logs are abandoned rather than migrated. The first
post-cut cold start creates the new names. Both duplicate `data/seon-pod/`
ignore entries and their comments change together.

### Downstream and evaluation harness

Rename `acme/src/acme/pod.cljs` to `acme/src/acme/client.cljs`, its namespace
from `acme.pod` to `acme.client`, and every preload, environment, context,
override, build configuration, and test reference. No `acme.client` source
currently exists, so the file/namespace move has no path collision.

In `src-inspect-ai`, translate symbols such as `pod_api`, `pod_run`,
`pod_solver`, `pod_restart`, and `per_pod` to their client equivalents only
when they denote the supervised process. Names for an addressable composition
door describe the running unit and therefore become `cluster_url`, cluster
endpoint, or cluster prose rather than `client_url`. Tests and fixture URLs
must follow the same meaning-aware distinction.

### Living documentation and vocabulary riders

Translate current source prose, tests, root and localized `AGENTS.md` files,
living `docs/seon/` architecture, component, reference, vision, concept, and
process-management documents, active program roadmaps, and skill sources.
Fold `docs/seon/pod/REPL-WORKFLOW.md` into the architecture tree. Edit skill
sources first, then regenerate adapters with `bin/seon skills sync` and prove
them with `bin/seon skills check`; generated adapters are never hand-edited.

The already-ruled vocabulary riders share the freeze:

- test-only “tile” fixtures become surface/card terms according to meaning;
- the remaining test prose “verbs” become functions;
- renderer prose “shared store” becomes database; and
- current architecture prose maps the whole running unit to cluster and the
  supervised Bun process to client.

The public `/agent/{id}/feed` to `/sse` cut is explicitly Stage 4 work after
route-authority collapse and is not part of this rename.

## Exclusions and collision controls

This is not a global textual replacement. Every occurrence is classified by
the seam it names.

- RunPod, `runpod`, `RUNPOD_*`, `api.runpod.ai`, endpoint modes, credential
  passthrough, and container/vendor types remain byte-identical.
- `pod-host/` is the frozen wasm-era owner and remains unchanged.
- Dated research, archived history, frozen evidence paths, and citations to
  those paths remain historical text.
- Babashka and clj-kondo “pod” terminology in `bin/oracle-server` names a
  dependency mechanism and remains unchanged. Seon-runtime prose in the same
  mixed file still changes by meaning.
- Generated Shadow outputs, `.shadow-cljs*`, `out*`, old release packages,
  runtime logs, data directories, and temporary probes are never source rename
  inputs. B2 caches remain protected through U11.
- `seon.client` and the `:client`, `:acme-client`, and `:bench-client` Shadow
  build IDs already carry the target term; the core source file is not renamed.

The target noun `client` already names several distinct seams: the supervised
Bun process, Shadow's numeric runtime `client-id`, browser-side clients,
database protocol clients, and provider clients. `process/client-id` is
namespace-safe, but a blind replacement inside `script/seon/dev/mcp.clj` would
conflate the process keyword with Shadow's numeric `client-id`. Translate only
the process-identity occurrences. Similarly, a cluster HTTP URL must not become
a client URL merely because its old local name contained `pod`.

The process ID, branch phases, restore generations, and release member are
persisted enum/key identities. Do not add an old-key compatibility reader or
dual registry. Pre-cut records and restore intent must be absent, and pre-cut
packages are invalidated and regenerated.

## Freeze prerequisites

The rename starts only after these ordered falsifiers pass:

1. Record one stable candidate head with no unacknowledged tracked edits in
   rename scope. Every active lane returns a coherent commit or explicit path
   handoff and acknowledges the freeze.
2. Recompute all entries from `git worktree list` and obtain the accepted
   disposition acknowledgements. The primary checkout is merge-before-rename.
   `seon-stable` still needs an owner-recorded pre-cut closure versus
   translate-after choice. The display/evidence owner must acknowledge that
   its lane and the other preserved legacy lanes remain out of scope.
3. Under pre-rename code and through each owner, run the appropriate default,
   ACME, stable, or retained-cluster shutdown. Do not kill, adopt, or delete
   another lane's process or record.
4. Prove both operator surfaces down, ports 7890, 7891, 7980, and 7981 absent,
   no `pod.edn` or restore-admin record in default, ACME, named-cluster, or
   branch process roots, no relevant live lock, and database-backed retained
   restore intent absent.
5. Recheck the exact head and tracked status. Compute a fresh per-file
   vendor-excluded terminology manifest plus frozen RunPod tripwire counts.
6. Run all three complete suites at the unchanged freeze head. Any source or
   head movement invalidates the candidate and restarts the freeze checks.

Leaving `seon-plan-fix` preserved requires no destructive owner decision.
Deleting it would. Worktree existence is not itself a failure; missing
ownership and cross-cut sequencing are.

## Four-commit execution order

### Commit 1 — code identities

Rename `src/`, `script/`, `bin/`, `config/`, tests, `shadow-cljs.edn`,
`bb.edn`, `.mcp.json`, release identities, artifact paths, logs, and process
records as one coherent mechanism. Use explicit path-limited commits and do not
interleave another lane.

Gate this commit with the complete CLJS, writer, and operator suites. Cross the
identity boundary with `bin/seon up` from the quiesced state, never `restart`.
Prove status reports `watcher -> writer -> client`, the web UI responds, a
restarted MCP client completes one `eval_cljs` round trip, and a regenerated
release package reads back and verifies `:seon.release.member/client` at
`runtime/client.js`. Re-run the frozen RunPod tripwire.

### Commit 2 — downstream and eval harness

Rename `acme/` and `src-inspect-ai/` with the meaning-aware process/cluster
mapping. Start ACME cold with `bin/acme up`, not restart, and prove one smoke
eval through its cluster plus the affected Python tests.

### Commit 3 — living docs and skills

Update living documentation and localized authorities, fold the REPL workflow,
edit skill sources, regenerate adapters, and run skill sync/check plus the
Markdown gate. Historical research and vendor terms remain untouched.

### Commit 4 — classified sweep

Run a fresh scoped `rg -in pod --glob '!pod-host/**'`, exclude RunPod vendor
matches, and classify every residual as a dependency-owned term or deliberate
dated historical citation. A residual active authority teaching pod as the
current Seon process fails the gate.

Release the completing commit range to every frozen lane. If a non-rename
commit lands mid-freeze, stop, reconcile, re-run quiescence and all freeze-base
gates at a newly recorded head, then continue only from the last coherent
rename commit.

## Tests and live falsifiers

The required correctness gates are:

- complete `bin/test-cljs`;
- complete `bin/test-writer`;
- complete `bin/seon test operator`;
- affected `src-inspect-ai` Python tests;
- Markdown structure tests; and
- `bin/seon skills sync` followed by `bin/seon skills check`.

The live proof must falsify partial identity cuts:

- operator status and process records expose client and never pod;
- only `client.edn`, `client.log`, and `client-events.log` paths are created;
- no old process, readiness, branch-phase, restore-generation, or release
  member key survives active source or newly produced data;
- default cold start reaches a ready web root and agent page;
- an MCP client restarted after the cut resolves and evaluates against the
  renamed runtime;
- the regenerated package contains and verifies `runtime/client.js` under the
  client member key;
- ACME cold start loads `acme.client` and completes a smoke eval; and
- the final vendor-excluded sweep has no unexplained current-process use of
  “pod”, while the pre-recorded RunPod counts remain byte-identical.

The final graduation gate is one unchanged completing head satisfying all
suite, package, default, downstream, MCP, web, skill, and terminology proofs.
