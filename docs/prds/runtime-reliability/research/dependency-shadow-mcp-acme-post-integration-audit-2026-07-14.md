---
type: research
status: completed
tags: [research, orchestrator, mcp, cljs, pod]
---

# Dependency, Shadow, MCP, and Inspect post-integration audit — 2026-07-14

## Question and authority

This is the post-integration continuation of
[[dependency-shadow-mcp-acme-audit-2026-07-14]]. It asks whether the current
Seon and ACME dependency targets, operator split, Shadow topology, MCP
registrations, Inspect harness, and autocomplete remnants form one coherent
supported system after the artifact-flavor and ACME migrations landed.

The active [[../roadmap.md]] is the sole current-state and sequencing
authority. Architecture documents are target descriptions, not evidence that
these mechanisms are implemented. This audit changed no runtime source,
dependency file, process, database, or old worktree. It read current source,
queried effective local classpaths, and compared claims with the checked-out
library implementations under `reference-code/`.

## Verdict

The primary dependency and operator split is correct:

- `deps.edn` owns reproducible Clojure/JVM/CLJS bases, source roots, compiler
  entry points, and JVM options;
- `bb.edn` owns the Babashka operator and MCP adapter libraries;
- `shadow-cljs.edn` owns build targets, outputs, hooks, and compiler options;
- `package.json` owns JavaScript runtime/build dependencies;
- `src-inspect-ai/pyproject.toml` owns Python evaluation dependencies; and
- `bin/seon` should remain the seven-line compatibility launcher over the
  data-driven operator.

Process sequencing, artifact publication, cluster identity, sockets, dynamic
ports, process ownership, and restart transitions belong in
`script/seon/dev/`; moving them into aliases would make dependency selection a
second lifecycle mechanism. `bin/acme` is appropriately an environment and
consumer-command wrapper over the same operator, while `acme/deps.edn` is the
downstream Clojure dependency project injected as one `:local/root`.

Three newly verified gaps remain after the earlier audit's main implementation:

1. ACME's isolated Shadow watcher still watches and publishes the
   checkout-global canonical test artifact concurrently with the default
   watcher.
2. The MCP adapter discovers only `.shadow-cljs/nrepl.port`, so its CLJ side
   can select ACME's writer but its CLJS side cannot reach ACME's separately
   isolated Shadow server.
3. Inspect is declared as a mutable local directory dependency. The installed
   environment is the old proven framework build while the current referenced
   checkout is a newer dirty revision.

These are current-mechanism ownership defects, not reasons to add another
operator, test runner, or MCP implementation. Durable issue owners are
[[../../../seon/issues/archive/acme-shadow-test-artifact-ownership-collision]],
[[../../../seon/issues/archive/mcp-shadow-flavor-discovery-gap]], and
[[../../../seon/issues/inspect-source-dependency-is-not-content-pinned]].

### Post-audit disposition

The first two findings were repaired and live-proven later on 2026-07-14. The
default watcher now owns `client` plus `test`; ACME owns only `acme-client`.
Restarting ACME left the complete default `out/test` tree byte-identical. The
one MCP adapter now derives both Shadow cache coordinates from artifact-flavor
configuration, evaluates both cluster-qualified CLJS roots and both CLJ
writers, and rejects bare `root` as ambiguous. Their issue notes are resolved
and archived; the Inspect source-pin issue remains open.

Live process classpaths also confirmed that both pod watchers use Datahike
`6f90b339…`, Konserve `df6818d4…`, superv.async `3e6ed755…`, and partial-cps
`1e119b03…`. Both writers were rebuilt from the root `:writer` basis with the
same maintained Datahike/Konserve pair. ACME's downstream `deps.edn` adds no
replacement dependency.

## Effective dependency targets

### Seon bases

`deps.edn:1-7` keeps the base deliberately small: `src`, `resources`, Clojure
1.12.0, and Malli 0.20.0. The active processes do not rely on a hidden broad
base:

- `:writer` uses `:replace-paths ["src"]`, explicitly adds Datahike's
  secondary-index source, and uses `:replace-deps` for the exact server graph
  (`deps.edn:18-52`). The effective classpath has 109 entries, begins with
  `reference-code/datahike/src-secondary` and `src`, includes maintained
  Datahike `6f90b339…` and Konserve `df6818d4…`, and contains neither Shadow nor
  ClojureScript.
- `:writer-test` adds only `test` and `script` to that selected writer basis
  (`deps.edn:54-63`). There is no second writer dependency graph.
- `:build` correctly adds tools.build only. `build/writer-uber` explicitly
  creates its artifact basis from `[:writer]` (`build.clj:49-75`), so source
  launch and uberjar use the same dependency authority.
- `:cljs` owns the compiler JVM options, `test`/`script` roots, runtime
  dependencies, and fork overrides (`deps.edn:75-175`). Its effective
  classpath has 112 entries and starts with `test`, `script`, `src`, and
  `resources`; it resolves CLJS 1.12.145, Shadow 3.4.10, Datahike
  `6f90b339…`, Konserve `df6818d4…`, superv.async `3e6ed75…`, and partial-cps
  `1e119b0…`.

This confirms that the present aliases have the right granularity. The writer
should not inherit `resources` merely for symmetry; add a writer resource root
only when a writer-owned resource exists. Conversely, `test` and `script` are
real CLJS classpath roots even though Shadow's inert list omits them.

### ACME downstream injection

`acme/deps.edn:7-8` is a valid downstream project with one `src` root and no
dependencies today. Both one-shot artifact compilation and watcher derivation
inject it before `-M:cljs` through
`-Sdeps {:deps {seon.extra/src {:local/root <acme>}}}`
(`artifact.clj:146-168`, `process.clj:123-140`). Clojure dependencies that only
ACME needs belong in this file; they should not widen Seon's `:cljs` basis.

The comment at `acme/deps.edn:5` incorrectly says npm dependencies can be
declared there. npm packages require ACME-owned npm metadata and a
`node_modules` surfaced by `SEON_EXTRA_NPM`; the existing
[[../../../seon/issues/shadow-deps-mode-declaration-drift]] already owns that
documentation and authority correction.

### What does not belong in `deps.edn`

`artifact/build-source!` deliberately sequences dependency prep, classpath
warm-up, writer uberjar, selected client, bootstrap, macro repair, and CSS
(`artifact.clj:180-206`). `cli/reconcile-development!` quiesces mutable readers,
publishes one manifest, and reconciles the watcher/writer/pod graph
(`cli.clj:42-64`). These are ordered transitions over files and processes, not
classpath definitions. Encoding them as aliases would duplicate the operator
and obscure ownership/restart semantics.

Likewise, cluster names, artifact flavors, output coordinates, process dirs,
logs, sockets, port files, and selected config are runtime target data derived
by `seon.dev.config` (`config.clj:76-139,206-261`). They belong neither in
Seon's nor ACME's dependency maps.

## Shadow configuration

### Correct current mechanisms

The default and ACME flavors now select build id, cache root, output, and
manifest together (`config.clj:76-88`). Non-default Shadow cache selection is
placed in `SHADOW_CLJS` before the JVM starts (`config.clj:121-139`). This is
the correct existing seam: vendored Shadow loads `SHADOW_CLJS` as environment
configuration overriding the file (`reference-code/shadow-cljs/.../config.clj:
110-123`) and publishes port files under the resulting cache root
(`.../server.clj:139-151,343-359`). Action-level `--config-merge` now owns only
build/preload data.

`:nrepl {:port 0}` and `:repl {:runtime-select :latest}` are appropriate for
the development server (`shadow-cljs.edn:4-8,23-29`). Vendored Shadow's worker
selects a newly connected runtime when that flag is active
(`.../worker/impl.clj:771-790`). Cluster-qualified MCP resolution still must
pin the intended advertisement rather than trust “latest” when several
runtimes share a server.

### Inert and stale declarations

`shadow-cljs.edn:31-40` correctly explains that top-level `:source-paths` is
ignored in deps mode, but retaining the declaration as “documentation” is
misleading because it omits the real `resources` and `script` roots. Vendored
Shadow explicitly warns that deps-mode source paths belong in `deps.edn`
(`reference-code/shadow-cljs/.../npm/cli.cljs:410-428`). Remove the inert key
after the clean-build proof already required by
[[../../../seon/issues/shadow-deps-mode-declaration-drift]].

The same issue owns npm Shadow 3.4.10 and unsupported `client:watch`,
`client:run`, and destructive `client:clean` scripts
(`package.json:10-26`). Active operator/build/test commands enter the Clojure
Shadow dependency through `clj -M:cljs`; the npm CLI is a second advertised
lifecycle with no identified supported consumer.

The isolated `:lora-audit` target (`shadow-cljs.edn:244-255`) still names a
namespace under `src-needle/audit`, but that directory is absent from the
`:cljs` classpath. Its historical runbook copied the namespace into another
worktree to make it visible. This is already recorded in
[[../../../seon/issues/lora-audit-runner-drift]] and should migrate into the
canonical Inspect/data-quality path, not receive another alias.

The ACME build comments at `shadow-cljs.edn:75-87` still claim a one-off compile
and no second watch. Current `seon.dev.process` instead starts a flavor-isolated
watcher for `acme-client` and `test`. The implementation is the current truth;
the comment must be corrected with the owning watcher fix.

### New blocker: shared test artifact

`process/extra-cljs-watch-args` unconditionally emits
`watch <client-build-id> test` (`process.clj:123-140`), and readiness requires
both builds (`process.clj:276-286`). Therefore the concurrently live default
and ACME watchers both compile `:test`.

Cache roots do not isolate that build's product boundary:

- `:test` writes `out/test/test.js` and enables the publishing hook
  (`shadow-cljs.edn:222-242`);
- the hook writes `out/test/artifacts/current.edn`, creates shared
  `bundles/`/`objects/`, and prunes them (`test_artifact.clj:152-202,204-246`);
  and
- none of those paths includes artifact flavor, cache root, cluster, or
  process owner.

Thus the isolated watcher proof does not yet establish isolated artifact
publication. The default managed watcher should remain the sole canonical
changed-test publisher; ACME should watch only what its runtime readiness needs
unless a separately named consumer artifact is deliberately introduced.

## MCP and ports

### What works

`.codex/config.toml:3-4` and `.mcp.json:2-8` call the same
`bin/mcp-server-cljs`; Claude retains the historical `seon_cljs` registration
name while Codex uses `seon`. This preserves client compatibility without
maintaining Claude-specific code.

The server itself exposes explicit `eval_cljs` and `eval_clj`
(`mcp.clj:1039-1062`):

- CLJS uses Shadow nREPL sessions, current runtime advertisements, and
  cluster-qualified database agent ids;
- CLJ uses one loopback Clojure `io-prepl` socket per `[cluster session-id]`,
  enforces one form per call, reconnects the default session after a writer
  restart, and reports named-session state loss (`mcp.clj:598-670`); and
- writer discovery derives `tmp/seon-writer-repl-port-<cluster>` or the owning
  environment override (`mcp.clj:601-624`).

The writer starts `io-prepl` only when its development port is supplied and
atomically publishes the actual port file (`src/seon/db/server.clj:96-168`).
The managed writer passes port zero plus its cluster-specific file
(`process.clj:158-176`). These are appropriate development probes, not a typed
administration API and not a new test runner.

### What does not work across isolated flavors

The CLJS port coordinate is hard-coded at server startup to
`<project-root>/.shadow-cljs/nrepl.port` (`mcp.clj:40-60`). Every CLJS eval,
session, advertisement probe, and runtime status uses that one file. In
contrast, ACME's operator now intentionally publishes
`tmp/shadow/acme/nrepl.port` from a different Shadow server.

As a result:

- `eval_clj {cluster: "acme"}` can discover the ACME writer;
- `eval_cljs {agent_id: "acme/root"}` cannot see a runtime that exists only on
  the ACME Shadow server; and
- a second MCP client registration cannot fix this merely by setting
  `SEON_CLUSTER_DIR`, because `shadow-port-file` does not derive artifact
  flavor/cache root from that environment.

The correction belongs in the existing adapter: select an owned flavor/server
coordinate explicitly or derive it from the operator target, then apply the
same cluster-qualified advertisement rules within that selected server. Do not
merge cache roots or rely on the latest-connected runtime.

## Inspect AI and autocomplete implications

### Harness ownership is correct

Inspect dependencies correctly remain in `src-inspect-ai/pyproject.toml`, not
`deps.edn`. The project declares Inspect plus OpenAI for standard
openai-compatible evaluation (`pyproject.toml:1-23`). Current task/scorer code
uses upstream Inspect's solver override, epoch reducers, and task boundary
rather than recreating them. The vendored source exposes the corresponding
`eval(..., solver=...)` and `Epochs` mechanisms under
`reference-code/inspect-ai/src/inspect_ai/_eval/` and scorer reducers under
`src/inspect_ai/scorer/_reducer/`.

The live caller migration is also directionally correct: lease-dependent
cluster operations now fail before subprocess/model work; typeahead corpus
generation requires explicit web/writer endpoints and an injected
ownership-fenced restart transition (`typeahead_corpus.py:24-29,231-307`). The
remaining per-sample owner/token, isolated coordinates, frozen artifact, and
token-fenced create/restart/release contract stays open in
[[../../../seon/issues/inspect-live-cluster-caller-drift]]. MCP eval must not be
used as a substitute supervisor.

Autocomplete training remains correctly paused. The valid prior findings are
requirements for one database-derived, versioned export and Inspect-owned
layered scoring, not justification for the stale `:lora-audit` runner, fake
cards, direct writer Datahike forms, or another evaluator. The owning issue is
[[../../../seon/issues/autocomplete-data-quality-pipeline-drift]].

### New reproducibility defect

The claim that Inspect is pinned is false at the dependency boundary:

- `pyproject.toml:6-7` and `README.md:33-35` identify proven build
  `0.1.dev1+g92dd737b9`;
- `uv.lock:803-805` records only a mutable sibling directory source;
- the installed `.venv` currently reports `0.1.dev1+g92dd737b9`; while
- `reference-code/inspect-ai` is currently commit
  `05322696a0f784ec399ef6abbafd3d2a250ea9cc`, describes itself as
  `0.3.246-dirty`, and has a dirty `_view/ts-mono` entry.

No synchronization was run, because that would destroy the diagnostic state
and silently upgrade the harness. Until a commit/tree digest is enforced and
recorded in run provenance, current offline results prove the already
installed framework only.

## Questionable code and concrete disposition

| Finding | Current disposition |
|---|---|
| Seon base, writer, writer-test, build, and CLJS aliases | Sound; keep current ownership. |
| `bin/seon` launcher | Sound and already minimal; do not move operator logic into aliases. |
| ACME `:local/root` injection | Sound; downstream Clojure dependencies stay in `acme/deps.edn`. |
| ACME npm guidance in `deps.edn` comment | Wrong; existing Shadow/deps drift issue owns correction. |
| Shadow top-level `:source-paths` | Inert and incomplete in deps mode; remove after clean proof. |
| npm Shadow and `client:*` scripts | Unsupported duplicate lifecycle; remove after lock/build proof. |
| `:lora-audit` | Retired, non-reproducible third runner; migrate evidence then delete. |
| ACME build comments | Stale: current ACME uses an isolated managed watcher. |
| ACME watcher also watches `test` | Blocker: shared output/manifest/pruning ownership collision. |
| Unified MCP CLJ/CLJS tools | Correct default workflow and language split. |
| MCP CLJS port discovery | Default-cache-only; cannot reach isolated ACME Shadow. |
| Inspect local source dependency | Mutable and currently version-split; content-pin before new scored evidence. |
| Inspect live cluster modes | Correctly fail closed until operator lease exists. |
| Autocomplete scratch pipelines | Evidence only; rebuild through one database export plus Inspect scorer. |

## Recommended order

1. Stop ACME from watching/publishing the canonical `:test` artifact, then
   rerun concurrent default/ACME watcher and changed-test proof.
2. Make the one MCP adapter select the operator's artifact-flavor Shadow
   coordinate; prove default and ACME CLJ/CLJS eval across restarts while
   preserving Claude's existing registration.
3. Pin Inspect by immutable source identity, synchronize a fresh environment,
   and rerun the complete offline gate plus one representative task.
4. Finish the existing Inspect lease rather than restoring removed lifecycle
   commands or direct arbitrary writer administration.
5. Rebuild autocomplete export/data-quality through database-derived records
   and Inspect; only then remove `:lora-audit` and consider model work.
6. Remove inert Shadow/npm lifecycle declarations after the clean default,
   ACME, bootstrap, complete/focused test, and CSS proofs.

## Verification performed

- Read root, runtime-reliability, source, and issue authorities plus the
  data-oriented Clojure and CLJS workflows.
- Read current dependency/build/operator/MCP/Inspect/ACME sources and existing
  lane-integration research.
- Read relevant Shadow and Inspect implementations in `reference-code/`.
- Ran local read-only `clojure -Spath -M:writer`,
  `clojure -Spath -M:cljs`, and focused `-Stree` resolution probes.
- Queried the existing Inspect environment's installed version and the
  referenced checkout's Git identity without synchronizing either.
- Did not run a model, mutate a database, start/stop a process, alter a
  dependency, or touch the protected untracked shared-schema report.
