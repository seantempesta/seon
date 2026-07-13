---
type: research
status: completed
tags: [research, flow, agent]
---

# Seon CLI and process-lifecycle audit — 2026-07-13

## TL;DR

`bin/seon` should be replaced in place by a Babashka program. It is no longer
shell glue: it is a 2,186-line, 77-function process supervisor, build planner,
cluster manager, readiness system, destructive reset tool, and operator UI.
Babashka is already a documented Seon requirement, starts quickly, provides
structured process/filesystem/CLI libraries, and lets this policy become normal
Clojure data and pure transition functions instead of command strings and shell
branches.

The rewrite must preserve the hard-won safety behavior. The current script has
real protections against PID reuse, concurrent lifecycle races, stale readiness
artifacts, orphan descendants, and deleting a store while its writer is still
alive. Rewriting only the syntax would be a regression. The target is one
desired-state reconciler with mechanically tested process identities, locks,
dependency ordering, readiness, and artifact publication.

The Shadow CLJS watcher is **not required to run agents**. It is required for
hot reload and the Shadow REPL/MCP path used while changing core CLJS. The agent
self-host compiler, web UI, agent loop, and wire client run from compiled files
without a live Shadow server. The current normal `start all` command nevertheless
always starts the watcher. On the live machine, that idle watcher held about
3.18 GiB RSS; its JVM heap had about 2.75 GiB committed, about 1.16 GiB used,
and a roughly 31 GiB maximum because it has no explicit heap bound. This is the
largest clearly avoidable process in an ordinary user runtime.

There is a production-like container build already. It compiles the pod,
self-host bootstrap, and CSS in a builder, then runs only the writer and Node
pod in the runtime image (`docker/Dockerfile:124-169`). That proves the watcher
is not a runtime dependency. It is not yet a polished production artifact:
`clojure -M:cljs compile client` is a Shadow `:dev` compile, the client build
includes test/demo preloads, the runtime closure is coupled to Shadow's dev
cache path, and the container has a second hand-written supervisor. A true
runtime artifact should be immutable, unwatched, exclude development preloads,
and use the same lifecycle library as local startup. It does **not** need to be
Closure `:advanced`; that optimization needs a separate compatibility proof
because Seon intentionally supports runtime redefinition.

The intended human surface can be small:

- `bin/seon up` — build if needed, start writer then runtime pod, wait for full
  readiness, print the URL. No watcher.
- `bin/seon down`, `status`, and `logs [--follow]` — the ordinary operator
  surface.
- `bin/seon dev` — the explicit resource-heavier watcher + writer + dev pod
  target for hot reload and Shadow MCP.
- `bin/seon build`, `doctor`, `restart`, and explicit advanced cluster/config
  operations — developer/operator commands, not prerequisites a new user must
  discover.

There should be no compatibility CLI. Update active call sites, skills,
runbooks, tests, and help atomically, then delete the old verbs and duplicate
paths. No database migration is needed.

## Scope and method

This was a read-only audit of `bin/seon`, the active process state and logs,
Shadow configuration and vendored Shadow source, the container lifecycle,
downstream callers, and current supervisor tests. No runtime code was edited;
no process, cluster, database, or ACME state was changed.

The live baseline was the default cluster on port 7890:

- pod PID 51847, writer PID 51638, watcher PID 26551;
- pod application boot from `-main boot` to `auto-boot ready`: about 8.3
  seconds;
- current watcher first build: 9.52 seconds, with typical incremental builds
  around 0.1–2 seconds;
- CSS build observed during pod startup: 87 ms.

The process memory numbers above are point observations, not leak claims. The
watcher was idle during the sample. They are sufficient to show that a JVM
watch server is not free and should not be part of normal runtime state.

## What `bin/seon` has become

The file mixes six responsibilities:

1. environment discovery and policy, including JDK selection, `.env` execution,
   capability defaults, cluster coordinates, and config selection;
2. build/dependency work for git libraries, writer/CLJS classpaths, the
   self-host bootstrap, CSS, and a separate frozen benchmark bundle;
3. process specifications for pod, dynamic cluster pods, watcher, writer,
   paused JVM app, and optional diffusion server;
4. process supervision: locking, detached spawning, registration, PID reuse
   detection, process-group termination, readiness, and logs;
5. database-cluster lifecycle: reset, create, fork, destroy, and global nuke;
6. the public CLI and its large help/runbook surface.

The public commands currently are `start`, `stop`, `restart`, `status`, `tail`,
`logs`, `adopt`, `prep`, `print-cmd`, `print-env`, `cluster`, `watch-faults`,
`bench-bundle`, and `nuke` (`bin/seon:2021-2054`). Registered processes include
the active stack, a paused JVM track, an optional model server, and dynamically
discovered cluster pods (`bin/seon:238-332`). This is why agents need intimate
knowledge to operate it: it exposes implementation processes instead of a
system state.

### Current `start all` transition

The active transition is:

```text
resolve JDK and source .env
  -> ensure watcher git deps/classpath
  -> start Shadow watcher
  -> accept any historical "Build completed" in its fresh-lifetime log
  -> ensure writer git deps/classpath
  -> start writer
  -> wait for UDS accept + REPL port file
  -> presence-check the self-host bootstrap
  -> rebuild CSS inside the pod command
  -> start pod
  -> wait for port file + auto-boot marker + HTTP three times
```

The stack definition is literally `cljs-watch wire-server pod`
(`bin/seon:324-332`). A caller may also start each process directly. Starting
`pod` does not bring up its dependencies; it only waits for the writer if this
supervisor already knows one is running (`bin/seon:1161-1177`). Thus the public
surface permits partial states that the normal user should never have to
understand.

If a later stage fails, earlier stages remain running. That is not inherently
wrong, but `status` must then show a truthful degraded target and the next `up`
must reconcile it. Today it only shows registered process liveness.

## The dev server is optional runtime infrastructure

Shadow's vendored source makes the distinction explicit:

- node REPL injection happens only when the build carries worker information,
  which is the watch/server path
  (`reference-code/shadow-cljs/src/main/shadow/build/targets/node_script.clj:32-48`);
- development preloads are injected for `:dev` mode, independently of a
  watcher (`node_script.clj:45-47` and
  `reference-code/shadow-cljs/src/main/shadow/build/targets/shared.clj:252-257`);
- `compile` performs an unoptimized `:dev` build, while `release` optimizes a
  `:release` build
  (`reference-code/shadow-cljs/src/main/shadow/cljs/devtools/api.clj:293-362`).

Seon's current `:client` build enables devtools and includes
`seon.dev.test-preload` plus `seon.demo` (`shadow-cljs.edn:58-77`). A watch build
also injects the Shadow Node client. The live `out/client/main.js` loader refers
to runtime chunks under `.shadow-cljs/builds/client/dev/out/cljs-runtime`.

The important operational split is therefore:

| Capability | Unwatched runtime artifact | Shadow watcher |
|---|---:|---:|
| Agent loop and LLM turns | yes | not required |
| `cljs.js` self-host agent eval | yes, with `out/bootstrap` | not required |
| Web UI and Datastar feeds | yes | not required |
| Wire-server reads/writes | yes | not required |
| Hot reload after core source edits | no | required |
| Shadow nREPL/MCP eval into running pod | no | required |
| Live CLJS test preloads | should be absent | development only |

`bin/acme` and frozen Inspect clusters already run one-off compiled bundles
without their own watcher. The container runtime also has no watcher or Clojure
CLI. These are working architectural proofs, though their duplicate build and
supervision paths should not remain.

### Recommended build targets

Use shared build defaults and one entrypoint namespace, but two explicit
artifacts:

- **runtime**: unoptimized or otherwise compatibility-proven, no Shadow client,
  no test/demo preload, immutable loader + complete chunk closure;
- **dev**: watched client with Shadow REPL client and development preloads.

Do not equate “production” with `:advanced`. Seon's runtime code-as-data and
late definition behavior can be changed by Closure optimization. First make a
standalone, unwatched, development-tool-free runtime bundle. Evaluate
optimization later with behavioral proof.

A build must publish one content-addressed manifest covering the whole closure,
not only `main.js`:

- loader and every CLJS runtime chunk;
- `out/bootstrap`, including the post-build macro fix;
- CSS;
- source/config/skill inputs needed by boot indexing;
- dependency and build configuration inputs;
- downstream overlay inputs when applicable.

Build into a staging location and atomically publish the manifest last. The
runtime only starts an artifact whose manifest is complete and whose digest
matches its declared inputs. This replaces mtime heuristics, presence-only
bootstrap checks, and the special benchmark bundle path with one mechanism.

## Findings

### P0 — normal startup always buys a development JVM

`start all` starts the watcher first even though the runtime can execute without
it. The observed watcher held about 3.18 GiB RSS while idle. `jcmd` reported a
heap with roughly 2.75 GiB committed, 1.16 GiB used, and a maximum near 31 GiB.
There is no watcher heap bound in the `:cljs` invocation.

Normal `up` should run exactly the writer and runtime pod after build. `dev`
should opt into the watcher and give it an explicit, measured heap cap.

### P0 — watcher readiness can report a failed build as healthy

The current probe succeeds when either the loader is newer than the watcher
start time **or any** `Build completed` line exists in the current log
(`bin/seon:382-388`). After an initial success, a later rebuild can fail while
the old success remains. `status` still reports the watcher process alive, and
an explicit readiness check can still pass.

Dev health must track the latest build result as structured state: build
sequence, started/completed/failed status, artifact digest, and time. An older
success cannot mask the newest failure.

### P0 — `nuke` crosses supervisor and cluster ownership boundaries

`nuke` deletes `data/clusters/*`, including ACME or any other supervisor's
store, while explicitly admitting it cannot stop those processes
(`bin/seon:1802-1889`). That can remove a store beneath a live writer. The
global command should be deleted. Destructive operations must name one cluster,
prove that this supervisor owns and has drained its writer, then remove only
that cluster's explicitly derived artifacts.

### P0 — artifact validity is inconsistent and sometimes presence-only

The self-host bootstrap is considered valid whenever
`out/bootstrap/index.transit.json` exists (`bin/seon:595-621`). Source,
dependency, or bootstrap-entry changes do not invalidate it. The normal client
artifact is owned implicitly by the watcher, while frozen clusters use a
separate staleness/hash system (`bin/seon:624-727`). CSS is rebuilt on every pod
start inside a shell compound command (`bin/seon:245-259`).

One artifact planner and atomic manifest should own all of these outputs. There
should be no `bench-bundle` special case after Inspect adopts the same immutable
artifact interface.

### P1 — `status` reports registration, not health

`status` checks PID/start-stamp registration and prints a port file
(`bin/seon:1418-1447`). It does not probe the writer socket, HTTP, boot marker,
latest build result, artifact identity, current desired target, or whether the
port file belongs to the current pod. A live PID can therefore be presented as
a healthy system.

The target status is a derived value over desired target, process identities,
readiness probes, and artifact state. It should report `ready`, `starting`,
`degraded`, `failed`, or `down`, the current `runtime`/`dev` mode, the URL when
ready, and one actionable cause when not ready. `status --edn` should expose the
same fully namespaced data for agents and behavioral tests.

### P1 — build/prep planning is duplicated and incomplete

Automatic prep fingerprints only `:git/url` and `:git/sha` lines and checks
gitlib prep output (`bin/seon:518-593`). `cmd_prep` has overlapping classpath and
bootstrap warming logic. Maven version changes, alias changes, source/build
inputs, bootstrap freshness, CSS, and output closure validity do not share one
dependency graph.

Represent build tasks as data with declared inputs, outputs, dependencies, and
probes. Compute the minimal task plan from hashes, and run independent tasks in
parallel when safe. `up` invokes this planner automatically; `build` exposes it
explicitly. There is no separate “prep” concept for a user to remember.

### P1 — environment policy runs for every command and is shell-executable

Even `status` and `logs` source the JDK resolver, check Java, create state/log
directories, and source `.env` as shell code (`bin/seon:52-80` and
`bin/seon:137-188`). This adds latency and side effects to read-only operations.
The current launcher also defaults `SEON_CONFIG` to `config/system.edn`
(`bin/seon:177-188`), which conflicts with the runtime-reliability design where
config application is optional and an ordinary restart preserves database
config.

The CLI should parse data, not execute config as shell. Load only the
prerequisites needed by the selected transition. `up`/`restart` should not
silently reapply config. Expose an explicit `config apply <file>` or
`up --config <file>` transition; no config argument means preserve database
state.

### P1 — process specifications are shell strings

`process_command` builds interpolated command strings and later passes them to
`bash -c` (`bin/seon:238-299` and `bin/seon:1068-1098`). Quoting, environment,
working directory, and executable identity are mixed into strings. This makes
composition and exact testing harder and keeps an extra shell in complex
commands unless every branch remembers `exec`.

Babashka can represent each process with fully namespaced data: argv vector,
environment map, working directory, dependencies, readiness probe, termination
policy, and artifact requirement. Shell is used only when a process genuinely
requires shell semantics.

### P1 — host and container lifecycle are duplicate mechanisms

`docker/seon-entrypoint` independently starts, probes, signals, and logs the
writer and pod (`docker/seon-entrypoint:40-128`). It correctly omits the watcher,
but it duplicates readiness and termination semantics, only gates writer
readiness before pod start, and signals direct PIDs rather than the complete
owned process tree.

The same Babashka supervisor library should support two execution styles:

- detached host mode for `bin/seon up`;
- foreground/container mode, where it owns children, forwards signals, and
  exits when the target is unhealthy.

The container build should publish the same runtime artifact the host uses.

### P2 — logs lose the preceding failure

Every fresh process start truncates its log (`bin/seon:1137-1139`). That makes
readiness matching easier but discards the run that usually explains why an
operator restarted. Merged logs also cannot reliably time-order lines that lack
timestamps.

Use per-lifetime log files keyed by process-instance identity, plus a stable
`current` pointer and bounded retention. Readiness observes only the current
instance without sacrificing history.

### P2 — lock identity is weaker than process identity

Managed processes use PID plus OS start stamp, but an atomic lock publishes
only its owner PID (`bin/seon:840-963`). If that PID is reused, a stale lock can
look live until a long timeout. The replacement lock record should use the same
PID + OS start-instant identity as process records.

### P2 — the main CLI exposes paused and optional subsystems

The paused JVM main app and optional DiffusionGemma server appear beside the
active pod/writer processes. They are not dependencies of the active system.
Move optional model infrastructure and the paused JVM track behind their own
explicit tools or development commands. They should not complicate ordinary
Seon status or target reconciliation.

## What the rewrite must preserve

The current supervisor contains valuable behavior developed from real failures:

- atomic exclusion for concurrent lifecycle operations and one coarse lock
  across reset/restart/store mutation (`bin/seon:1297-1416`);
- process identity as PID plus OS start stamp, so a reused PID is never signaled
  (`bin/seon:965-1066`);
- detached host processes created in a new OS session, so terminal/Codex session
  teardown does not reap them (`bin/seon:1068-1098`);
- process-group TERM, bounded grace, KILL, and confirmed descendant drain before
  a destructive store operation (`bin/seon:1199-1295`);
- stale readiness artifact removal and refusal to unlink an accepting unmanaged
  socket/listener (`bin/seon:420-455`);
- dependency-ordered startup with bounded, fail-loud readiness checks and
  consecutive HTTP observations (`bin/seon:458-516`);
- one restart lock spanning stop + start, preventing duplicate instances
  (`bin/seon:1388-1416`).

These are acceptance criteria, not implementation details to simplify away.

### Babashka process caveat

Babashka is a good policy/runtime choice, and
[babashka.process](https://github.com/babashka/process) provides structured
commands, environment, directories, asynchronous processes, redirection, and
tree destruction. The installed Babashka runtime also exposes Java
`ProcessHandle`, including a process start instant, which is stronger and less
locale-sensitive than parsing `ps lstart`.

However, Java `ProcessBuilder` does not expose POSIX `setsid`. Replacing the
current `python3` `start_new_session=True` call with a plain
`babashka.process/process` call would lose the proven detach property. Retain a
tiny, isolated OS primitive for new-session spawning unless the supervisor
itself remains a foreground daemon. The simplest host implementation is likely
one small Python spawn helper called by Clojure; Python is already a hidden
runtime requirement today. Test that the child survives launcher exit before
claiming equivalence. The rest of the policy belongs in Clojure.

## Target design

### One desired-state reconciler

The public command compiles to a desired target and reconciles current OS state
to it:

```clojure
{:seon.supervisor.target/name :seon.supervisor.target/runtime
 :seon.supervisor.target/processes
 [:seon.supervisor.process/writer
  :seon.supervisor.process/pod]
 :seon.supervisor.target/artifact :seon.artifact/runtime}
```

Development is another value, not another lifecycle implementation:

```clojure
{:seon.supervisor.target/name :seon.supervisor.target/dev
 :seon.supervisor.target/processes
 [:seon.supervisor.process/watcher
  :seon.supervisor.process/writer
  :seon.supervisor.process/pod]
 :seon.supervisor.target/artifact :seon.artifact/dev}
```

The planner derives stop, build, start, and probe operations from current versus
desired facts. Process dependencies live beside each process specification.
Restart reuses the recorded target and artifact; it must not silently change a
runtime system into dev mode or vice versa.

Supervisor state belongs in the filesystem because it must remain readable
when the database writer is down. Store one atomic EDN record per process,
rather than six loosely coordinated files. All keys are fully namespaced. A
record should contain at least:

- process id and OS start instant;
- process group/session identity and ownership;
- exact argv and environment digest;
- artifact digest and desired target;
- start time and current lifecycle phase;
- current log instance.

This is operational fact, not a second source of domain truth. Readiness is
re-probed and derived; it is not trusted merely because a state file says
“ready.”

### Namespaces and entrypoint

Keep `bin/seon` as the one stable executable, but make it a tiny `bb` entrypoint.
Put implementation in focused Clojure namespaces, for example:

```text
script/seon/supervisor/cli.clj
script/seon/supervisor/target.clj
script/seon/supervisor/process.clj
script/seon/supervisor/artifact.clj
script/seon/supervisor/cluster.clj
```

Colocate schemas and helper functions with the namespace that owns each data
shape. Do not create a second `bin/seon-bb`, a compatibility shell wrapper, or
parallel lifecycle implementation. The official
[Babashka book](https://book.babashka.org/) documents the task/CLI/process
foundation; the repo already lists Babashka as a requirement
(`README.md:82-99`).

### Small command surface

| Command | Contract |
|---|---|
| `bin/seon up` | Reconcile normal runtime, build stale/missing artifact, wait for writer and pod, print URL |
| `bin/seon dev` | Reconcile dev target with watcher and Shadow MCP/hot reload, clearly report resource cost |
| `bin/seon down` | Drain the active target in reverse dependency order |
| `bin/seon restart` | Restart the active target without changing its mode or artifact inputs |
| `bin/seon status [--edn]` | Report target, artifact, real health, URL, and actionable failure |
| `bin/seon logs [pod\|writer] [--follow]` | Snapshot by default; follow only when explicit |
| `bin/seon build` | Build/publish the immutable runtime artifact and print its digest |
| `bin/seon doctor [--edn]` | Check tools, versions, config input, ports/sockets, artifact, and stale ownership |
| `bin/seon config apply <file>` | Explicitly reconcile optional config; ordinary restart preserves DB config |
| `bin/seon cluster ...` | Advanced named create/fork/restore/destroy operations with ownership fences |

Low-level process commands, if still needed for debugging, should be hidden
under an explicit expert namespace such as `service`, not be the quickstart.

Delete or fold:

- `start/stop <name|all>` into `up/down/dev/restart` target operations;
- `prep` into automatic artifact planning and `build`;
- `tail` into `logs --follow`;
- `print-cmd` and `print-env` into structured `doctor`/`status --edn` output;
- `adopt` unless a proven operational scenario remains after unmanaged
  listeners fail loudly with their owning PID;
- `bench-bundle` into the one immutable artifact mechanism;
- `nuke` entirely;
- paused JVM and optional diffusion process branches from the main target.

Retain `watch-faults` only if the external wakeup workflow still requires it;
it should be an operator subcommand, not part of the introductory surface.

## Atomic migration and deletion plan

No database migration is required. The only handoff is current OS process
registration and build artifacts.

1. **Characterize current laws.** Port the valuable shell supervisor tests to
   Clojure before changing the executable. Add missing failure/readiness/build
   cases.
2. **Implement pure target/artifact planning.** Inputs are current process and
   artifact facts; output is an ordered transition plan. No process effects in
   planner tests.
3. **Implement process effects once.** PID/start-instant identity, locks,
   detach, group drain, readiness, logs, and atomic state records.
4. **Create runtime and dev artifacts from shared build defaults.** Prove the
   runtime artifact after the Shadow server is stopped. Remove test/demo
   preloads from runtime.
5. **Implement the small CLI over the reconciler.** `up` is the nontechnical
   happy path; failures name one cause and one recovery action.
6. **Update direct callers in the same cutover.** Inspect AI cluster tooling,
   MCP helper messages, pod-host scripts, tests, and active operator scripts
   must use the new semantic commands. ACME should be changed only in its
   coordinated owner lane after the default target is proven.
7. **Unify the container entrypoint.** Invoke the same supervisor library in
   foreground mode and consume the same runtime artifact.
8. **Stop the current default stack with the old supervisor, replace
   `bin/seon` in place, discard old `tmp/proc` registrations, and bring up the
   default cluster with the new supervisor.** This is an operational cutover,
   not a compatibility path.
9. **Delete old mechanisms.** Remove shell lifecycle functions, benchmark
   bundle code/build, duplicate Docker supervision, obsolete process branches,
   and compatibility documentation. Do not leave aliases.
10. **Reset and prove the default cluster.** Only after the clean default proof
    should downstream/ACME lifecycle be updated and restarted by its owner.

### Call sites that require coordinated updates

The live-source scan found at least these active consumers:

- `src-inspect-ai/src/seon_inspect/cluster.py` and its tests: benchmark bundle,
  cluster create/destroy, and dynamic pod restarts;
- `bin/acme`: build/up/down and delegated named process operations;
- `bin/mcp-server-cljs`: startup/restart instructions;
- `pod-host/wasm-tauri`: `start all` invocation;
- `src-diffusion` docs/launcher references;
- `README.md`, `ORCHESTRATOR.md`, `AGENTS.md`,
  `docs/seon/process-management.md`, active architecture/component/runbooks;
- `.agents/skills/browser-automation`, `clojure-testing`, `clojurescript`, and
  `seon-context-config`;
- docstrings and error/fork hints in active `src/` files;
- `test/bin/seon_supervisor_test.sh`, which is not currently wired into the
  main test runner.

Historical research, archived issues, and preserved evaluation evidence may
keep old commands as historical facts. The mechanical stale-reference gate
should target active source, skills, runbooks, tests, and architecture rather
than rewriting history.

## Behavioral test and release gates

Do not assert prose strings. Assert structured state, exit status, process
identity, filesystem effects, probes, and end-to-end behavior.

### Pure planner tests

- runtime target excludes watcher; dev target includes it;
- dependencies topologically start and reverse-stop;
- unchanged artifact inputs produce no build work;
- every source/dependency/bootstrap/CSS input change changes the artifact
  digest;
- a failed build never publishes its manifest;
- restart preserves target, config intent, and artifact identity;
- destructive plans cannot contain an unowned or unnamed cluster.

### Isolated process tests

- concurrent `up` is idempotent and creates one process instance;
- PID reuse is detected and the replacement process is never signaled;
- a detached child survives launcher exit;
- TERM then bounded KILL drains descendants before return;
- no store path is removed until the writer group is confirmed dead;
- stale locks recover using PID + start instant;
- nested operations release every owned lock after failure;
- unmanaged accepting socket/HTTP listeners fail loudly without unlinking.

### Readiness tests

- an older watcher success cannot hide the latest build failure;
- stale port/socket/marker files cannot satisfy a fresh process;
- writer readiness requires a real socket connection;
- pod readiness requires current process identity, boot completion marker, and
  stable HTTP observations;
- early death and timeout expose the current instance log and nonzero status;
- a partially started target reports `degraded` and the next `up` self-heals.

### Black-box runtime tests

- from a clean tree, `bin/seon up` builds as needed and reaches a healthy agent
  roster with exactly writer + pod and no watcher;
- with the Shadow server stopped, the runtime can mint an agent, run a turn,
  execute self-host CLJS, render a canvas, and handle button/form interaction;
- `bin/seon dev` supports hot reload and the Shadow MCP path, and the pod does
  not detach from its newly restarted watcher;
- config is absent/preserved by default and applied only by the explicit
  transition;
- reset affects only the named default cluster and never ACME/another
  supervisor;
- the container starts the same artifact with the same readiness contract and
  exits nonzero if a required child dies.

### Resource and performance gates

- normal idle target contains no Shadow JVM;
- dev watcher has a measured heap bound rather than a roughly 31 GiB maximum;
- unchanged warm `up` and `status` do not initialize Java/Clojure/build tooling;
- no build task runs when its complete input digest is unchanged;
- launcher exits leave no periodic CPU work;
- status and already-converged `up` are subsecond and return structured output;
- cold and warm phase timings are printed in estimated duration, with the
  dominant phase visible rather than one opaque “booting” wait.

The current 8.3-second application boot suggests a warm restart target below
15 seconds is realistic after retaining the stability fence. Set final timing
thresholds from several clean and warm samples during implementation rather
than hardcoding this single observation.

## Decisions still needed from the owner

These do not block the architectural recommendation, but they affect final UX:

1. Should bare `bin/seon` behave as `up`, or print the short operator help?
   `bin/seon up` is already a one-command startup; making a no-argument command
   mutate state is a product choice.
2. Should the local source checkout build a stale runtime artifact
   automatically on `up` every time, or should packaged/nontechnical installs
   consume only a shipped immutable artifact while developers use `build`?
3. Does any current operator still need manual `adopt`, named process restart,
   or `watch-faults` after target reconciliation and structured status exist?
4. Which explicit first-run action should apply `config/system.edn` to a fresh
   database? Ordinary `up`/restart should preserve database config when no
   config is supplied.

## Conclusion

The user-facing problem is not primarily that Bash is ugly. It is that the CLI
models implementation processes rather than one desired system state, and it
mixes runtime, development, build, cluster, and optional-service policy. A
Babashka rewrite is justified because it lets Seon express those transitions as
validated data and reusable functions while keeping a fast single executable.

The simplest robust product path is an immutable unwatched runtime artifact,
`bin/seon up` for writer + pod, and `bin/seon dev` as an explicit opt-in to the
Shadow watcher. Preserve the existing process-safety laws, delete global and
duplicate paths, update active instructions atomically, and prove the cutover
on the resettable default cluster before touching downstream runtimes.
