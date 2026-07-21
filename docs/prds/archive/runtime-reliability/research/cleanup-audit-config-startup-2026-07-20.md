---
type: research
status: complete
tags: [research, component, architecture]
---

# Cleanup audit — configuration and startup/launch (2026-07-20)

Read-only audit of the boot sequence, environment/config reads, duplicate
config paths, removed-era residue, and the stray repo-root `locks/stack.lock`.
All claims grounded in file:line reads; the locks bug was reproduced and
traced empirically.

## Dependency ledger

- `bin/seon` (bash, 7 lines) → babashka `-m seon.dev.cli --seon-root <root>`.
- `script/seon/dev/cli.clj` — the one operator entry; every lifecycle
  transition wraps `state/with-lock configuration :stack …`.
- `script/seon/dev/config.clj` `load!` — the one host-configuration derivation
  (env + `.env` + artifact descriptor + launch descriptor).
- `script/seon/dev/state.clj:73-99` `with-lock` — kernel file lock at
  `(fs/path (:seon.dev.config/process-dir config) "locks")`.
- `script/seon/dev/process.clj` — process specs, readiness, and the pod child
  environment (`SEON_LAUNCH_DESCRIPTOR` at :442).
- `src/seon/launch.cljc` — the shared descriptor schema; CLJS side decodes
  `SEON_LAUNCH_DESCRIPTOR` or falls back to per-var env reads (:489-526).
- `src/seon/config.cljs` — manifest → `:seon.config` DB singleton;
  `platform/env-val` is the one low-level env reader (:551-554, :892-893).
- `src/seon/db/server.clj` — JVM writer `-main` (:407+).
- `bin/acme` — pure env composition over `bin/seon`; no second supervisor.

## Boot sequence (actual, verified)

1. `bin/seon` execs `bb -m seon.dev.cli --seon-root <checkout>`.
2. `cli/-main` → `config/load!` (config.clj:356-465): absolutizes root,
   detects source-checkout vs package (presence of `deps.edn` +
   `shadow-cljs.edn`, :310-312), merges `.env` under the live environment
   (:286-308, invoking env wins), selects JDK 26 (:278-284), derives artifact
   descriptor (`SEON_ARTIFACT_DESCRIPTOR` or `default-artifact`, :182-226),
   pins the Shadow cache root through `SHADOW_CLJS` (:228-246), resolves
   cluster/proc/log/socket/port-file coordinates from `SEON_*` env with
   defaults under the checkout (:387-415), and builds one validated
   `launch/default-descriptor` (:417-434).
3. `up!` (cli.clj:282-292) takes the `:stack` lock, resumes any retained
   restore, then `reconcile-development!` starts, in dependency order via
   `process/specs` + `start-order`: shadow watcher → JVM
   `seon.db.server` (writer jar preflight/build under the checkout build lock,
   artifact.clj:826-835) → Bun pod. The pod child receives its identity as
   one `SEON_LAUNCH_DESCRIPTOR` EDN value plus mirror `SEON_*` vars
   (process.clj:439-463).
4. Pod boot: `launch.cljc:522-526` decodes `SEON_LAUNCH_DESCRIPTOR` (the
   env-var fallback block :489-520 exists only for descriptor-less starts,
   i.e. tests/bare bun). `seon.config` then reconciles the selected manifest
   into the `:seon.config` DB singleton; runtime reads the database.
5. Manifest selection (config.clj:110-131): explicit `--config` wins, then
   inherited `SEON_CONFIG`, then `config/system.edn` only for a database that
   has never been born (`database-born?` :97-108). This matches the standing
   rule (config optional on reopen).

## Root cause: repo-root `locks/stack.lock` (REPRODUCED)

- `state/with-lock` (state.clj:76) computes the lock directory as
  `(fs/path (:seon.dev.config/process-dir config) "locks")` and
  `fs/create-dirs` it. With a **nil/absent** `process-dir`, `fs/path`
  degrades to the bare relative path `locks`, which resolves against the
  JVM cwd — the repo root.
- The caller is **`test/seon/dev/cli_test.clj:703`
  `branch-commands-call-only-the-retained-lifecycle-owner`**. Its fixture
  configuration is `{:seon.dev.config/launch-descriptor :source}` (line 704)
  and its `with-redefs-fn` map (lines 708-725) redefs the `branch/*` fns and
  printers but **not `state/with-lock`**. `cli/branch!` open/restart/close
  (cli.clj:501-527) each wrap the real `state/with-lock configuration :stack`,
  so the real lock runs with no `process-dir` and creates
  `<cwd>/locks/stack.lock`.
- Reproduced: deleting `locks/` and running
  `bb -m seon.dev.test-runner seon.dev.cli-test` (cwd = repo root, exactly
  what the `bin/seon test changed` hook's `run-operator!`
  (changed_test.clj:622-628, `.directory root` at :550) does) recreates
  `locks/stack.lock`. An instrumented `with-lock` confirmed the config value:
  `CULPRIT lock :stack config #:seon.dev.config{:launch-descriptor :source}`,
  hit three times (open/restart/close). This is why the file reappeared at
  09:54 today while the changed-test hook ran; my reproduction residue was
  removed.
- Fix (two parts, one mechanism):
  1. cli_test.clj:703 — add `#'state/with-lock (fn [_ _ _ t] (t))` to that
     test's redef map (as every sibling lifecycle test already does), or give
     the fixture a temp-dir `:seon.dev.config/process-dir`.
  2. Harden the owner: `state/with-lock` should throw when
     `:seon.dev.config/process-dir` is absent, blank, or relative — a
     lifecycle lock outside the operator's absolute process directory is
     always a bug. This also guards the `bin/acme` relative
     `SEON_PROC_DIR=tmp/proc-acme` (below).
  3. Delete the stray `locks/` directory (zero-byte lock only).

## Environment / config-file reads — table with verdicts

Legend: BOOTSTRAP = legitimate pre-database read needed to find the database
or compose a child process; VIOLATION = runtime behavior read from env/file
after the database exists; SEAM = accepted call-time secret/process seam.

| Read | Location | Verdict |
|---|---|---|
| All `SEON_*`, `JAVA_HOME`, `.env` parse | script/seon/dev/config.clj:151-155, 286-308, 387-416 | BOOTSTRAP (operator host derivation; `.env` parsed as data) |
| `SEON_ARTIFACT_DESCRIPTOR`, `SEON_CLIENT_OUT`, `SHADOW_CLJS` | config.clj:188, 213, 237 | BOOTSTRAP (artifact identity) |
| `SEON_LAUNCH_DESCRIPTOR` decode | src/seon/launch.cljc:524 | BOOTSTRAP (the intended one-value handoff) |
| Env-var fallback descriptor (`SEON_CLUSTER_DIR`, `SEON_DB_SOCK`/`SEON_REQ_SOCK`, `SEON_PROC_DIR`, `SEON_PORT`, …) | launch.cljc:489-520 | BOOTSTRAP but a **duplicate resolution scheme** — see duplicates. Defaults here (`tmp/seon-operator`, `7890`, `tmp/seon-port`) restate operator defaults. |
| `SEON_CLUSTER_DIR` | src/my/blob.cljs:200 | VIOLATION-adjacent: blob root re-derives cluster identity from env at runtime instead of the launch descriptor already decoded in the same process. Should read `launch/process-launch-descriptor`. |
| `SEON_DB_SOCK`/`SEON_REQ_SOCK` | src/seon/db/transport/uds.cljs:28 | Same: duplicate of launch.cljc:503-505; should consume the descriptor. |
| `SEON_WRITER_REPL_PORT_FILE` | src/seon/db/server.clj:316 | BOOTSTRAP (writer publishes its dynamic REPL port before any database exists) |
| `(System/getenv)` → `terminal-configuration` | db/server.clj:414 | BOOTSTRAP (process wiring at `-main`) |
| `SEON_EXTRA_SRC` | src/seon/indexing.clj:61 | BOOTSTRAP (compile-time classpath root) |
| `SEON_PROGRAM_SOURCE_PATH`/`DIGEST` | src/seon/client.cljs:1195-1196 | BOOTSTRAP (artifact admission) |
| `SEON_EMBED` presence gate | src/seon/embed.clj:153 | BOOTSTRAP-ish but a presence gate (any value = ON), forcing both `bin/seon`'s child-environment scrub (config.clj:305-308) and `bin/acme`'s duplicate translation. Should be a config fact or at least a value test. |
| `GEMINI_API_KEY` | embed.clj:522, 1010; embed/preflight.clj:83; agent/web/internal.cljs:559 | SEAM (secrets stay out of the database) |
| `SERPER_API_KEY`, `ANTHROPIC_API_KEY`, provider `:api-key-env` | agent/web/internal.cljs:677; ai/anthropic.cljs:110; ai/openai_compat.cljs:138; ai/diffusiongemma.cljs:236 | SEAM (call-time secret resolution; selection itself is config data) |
| `SEON_WEB` | src/seon/agent/web/internal.cljs:43 | VIOLATION: web capability master gate read from env at call time; reachability policy already lives in `:seon.config/web`. Should collapse into the config singleton (grant remains a launcher decision → a launch-descriptor field, not an ambient env probe). |
| `SEON_SHELL` | shell gate (set by config.clj:300) | Same class as `SEON_WEB`. |
| `SEON_RENDER_STRICT`, `SEON_BRAND_*` | config.clj:302-304, 366-369; web/brand.cljs | VIOLATION-class: render/branding behavior via env; brand belongs in the manifest/DB. |
| `seon.config/env` accessor | src/seon/config.cljs:551-554 | Documented single seam; acceptable while the launch-wiring vars above still exist, deletable after they collapse. |

## Duplicate / parallel config paths

1. **Two endpoint-resolution schemes in the pod.** The operator publishes one
   validated `SEON_LAUNCH_DESCRIPTOR` (process.clj:442) yet
   launch.cljc:489-520, uds.cljs:28, and my/blob.cljs:200 still re-resolve
   sockets/dirs/ports from individual `SEON_*` vars with their own defaults.
   Owner to collapse into: `seon.launch/process-launch-descriptor`; the
   fallback block should exist only for the test runner, and consumers must
   read the descriptor, never `platform/env-val`.
2. **Defaults declared twice.** `7890`, `tmp/seon-operator`, `tmp/seon-port`,
   `tmp/seon-writer-repl-port-<cluster>`, `data/clusters/default` each appear
   in config.clj:391-416 AND launch.cljc:491-520 (and the writer repeats the
   port-file default at db/server.clj:316-317). Owner: operator `config.clj`;
   children receive values, never defaults.
3. **`SEON_EMBED` presence-gate translation duplicated** in
   config.clj:305-308 and bin/acme (case block). Owner: fix the gate in
   `seon.embed` (value test or config fact); delete both scrubs.
4. **`bin/acme` env composition.** By design it is env-only (good — no second
   supervisor), but it hard-codes relative `SEON_PROC_DIR`, `SEON_LOG_DIR`,
   `SEON_PORT_FILE` values that `config/load!` accepts verbatim without
   root-normalization (config.clj:394-406 has no `root-path` call, unlike the
   artifact outputs at :210-226). Any operator invocation from another cwd
   would scatter state. Owner: `config/load!` should absolutize every
   directory/file coordinate it accepts from env.
5. **deps.edn overlap: none found.** `acme/deps.edn` is `{:paths ["src"
   "test"] :deps {}}` — no copy of the root `:writer`/`:cljs` fork
   coordinates. The root aliases remain the sole authority. No flag.
6. **Manifest readers: one.** `config/select-manifest` (operator) chooses the
   path; `seon.config` (pod) is the only reader/reconciler. No parallel
   manifest parser found.

## Startup residue from the removed Integrant/JVM-application era

- No `integrant` reference remains in `src/`, `script/`, `deps.edn`, or
  `shadow-cljs.edn`. The only copies are vendored: `reference-code/integrant/`
  (plus incidental matches in reveal/clj-kondo corpora). With no first-party
  consumer, the `reference-code/integrant` submodule is deletable residue —
  owner decision, since `reference-code/` policy is "load-bearing deps only".
- `config.clj:97-108 database-born?` still accepts a legacy layout probe and
  cli.clj:60-77 guards `store` vs `db` — intentional preservation, not
  residue.
- No second supervisor, nREPL application path, or core.async topology found
  in the audited startup path.

## Ordered fix plan

1. Delete stray `locks/` at the repo root (done for the reproduction copy;
   re-delete if the hook has run since).
2. cli_test.clj:703 — redef `state/with-lock` (or supply a temp
   `process-dir`) in `branch-commands-call-only-the-retained-lifecycle-owner`.
3. state.clj `with-lock` — reject absent/blank/relative
   `:seon.dev.config/process-dir` with an ex-info naming the lock.
4. config.clj `load!` — absolutize `SEON_PROC_DIR`, `SEON_LOG_DIR`,
   `SEON_PORT_FILE`, `SEON_REQ_SOCK`, `SEON_CLUSTER_DIR`,
   `SEON_WRITER_REPL_PORT_FILE` against root (mirrors `root-path` handling of
   artifact outputs).
5. Pod: make `launch/process-launch-descriptor` the only consumer surface —
   migrate uds.cljs:28 and my/blob.cljs:200 to it; then shrink the
   env-fallback default block so defaults live only in the operator.
6. Collapse `SEON_WEB`/`SEON_SHELL`/`SEON_RENDER_STRICT`/`SEON_BRAND_*` into
   launch-descriptor fields or `:seon.config` facts; fix the `SEON_EMBED`
   presence gate at its owner and delete both translation scrubs.
7. Owner decision: drop the `reference-code/integrant` submodule.
