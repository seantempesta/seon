---
type: research
status: draft
tags: [research, agent, flow]
---

# Result-driven benchmark suite — design + ordered build plan (2026-07-05)

**REVISED 2026-07-05 (second same-day revision), per a new owner ruling on
the substrate.** The prior revision kept the Seon pod ON THE HOST and
designed a "container-exec shell transport" — `seon.agent.shell` reaching
into each bench task container via docker exec. The owner rejected that
framing: **Seon is to be fully packaged so it runs in docker** — (a) users
who won't grant full host permissions can run it containerized, (b) it
simplifies deployment, (c) benchmarking then uses THE SAME packaged
environment users get: deployment-env == test-env, one artifact. Seon is
being designed from the ground up, so the container is the canonical
distribution, not a bench hack. Everything else from the prior revision
stands: SWE-bench Verified + terminal-bench as the two anchors with their
OFFICIAL oracles untouched, the mini-swe-agent baseline arm, polyglot
demoted, BFCL/tau2 dropped, restart-resume composition, `behavior_miss`,
freeze/lock discipline, dev-slice economy, and the thesis. Prior-revision
reference-code citations were independently verified and are reused; NEW
claims in this revision were verified fresh against the repo and vendored
sources. Superseded material is REPLACED, not struck through — the
host-bound shell transport survives only as composition option B (§2b).

## 1. TL;DR — the revised shape

- **One canonical Seon docker image** (§2a): a single container running the
  wire-server JVM + the Node pod (supervisor-entrypoint, UDS between them,
  cluster store on a volume, pod HTTP published). This is the user-facing
  distribution artifact AND the bench substrate — one artifact, two jobs.
- **Composition with per-task bench environments** (§2b): the Seon runtime
  goes INTO each bench task container, so the agent's shell/fs/cwd are
  NATIVELY the task environment — no transport, no periscope. Mechanism:
  a **runtime-overlay** — the canonical image's `/opt/seon` tree, extracted
  once at a pinned digest, mounted read-only into the UNMODIFIED official
  task image (SWE-bench, via inspect-evals' `sandbox_config` seam) or
  copied in (terminal-bench, via docker-py `put_archive`). Zero derived
  images, official image digests untouched, the official oracle runs
  unchanged in the same container. The sidecar-plus-exec-transport design
  survives only as fallback option B for task images that cannot host the
  runtime (musl/amd64-only).
- **Two anchor benches, their own oracles** (unchanged): SWE-bench Verified
  (DeepSeek NT published **73.6**) and terminal-bench (published **59.1**,
  Terminal Bench 2.0). For SWE-bench, Inspect's docker sandbox + the
  official scorer run as-is (we supply only the solver + a per-sample
  compose); for terminal-bench, THEIR harness runs with our pod plugged in
  as a `--agent-import-path` adapter.
- **The thesis (unchanged):** fixed model (DeepSeek NT), same frozen
  instances — the Seon harness (derived context, database memory,
  plan-survives-restart) beats the published-scaffold numbers; the measured
  claim is the delta vs a reproduced **mini-swe-agent baseline arm** on the
  same frozen slice, with 73.6/59.1 as external anchors.
- **Kept:** `behavior_miss` + flake taxonomy (§7), freeze/lock/canary
  discipline (now the SEON IMAGE DIGEST joins the instance-image digests in
  `datasets.lock`), evidence retention, every-check-stated, dev-slice
  economy (n≈10-25), restart-resume composition (§5) — now even cleaner:
  kill the pod process inside the task container; the container, the repo
  state, and the durable store all survive.
- **First slice (new):** build + boot the canonical Seon image standalone —
  an agent answers a trivial task over `POST /agents/run` from inside
  docker. That proves the packaging before any bench composition.

## 2. The packaged Seon image + composition model

Host status: docker 29.4.0 is installed; the host is an arm64 Mac, and both
bench image sets serve arm64 (§2b). All paths below are verified against
the current tree.

### 2a. The canonical Seon image

**What actually runs (inventory, from `bin/seon` + `deps.edn`):**

- **wire-server (JVM)** — `clojure -M:simd:fork-deps:writer --backend file
  --db-name <cluster> --path <dir>/store --req-sock <uds> --pub-sock <uds>
  --repl-port <port>` (`bin/seon:276`). Main ns `seon.server.boot`
  (`deps.edn:196-199`). JDK pinned to 25, hard floor 22 (Proximum class-file
  66 + Foreign Memory API — `bin/_java-home-resolver` sets
  `SEON_JAVA_VERSION="25"` and already scans Linux JVM roots
  `/usr/lib/jvm/*`; `bin/seon:60-71` keeps the >=22 assert). Heap is sized
  by the `:simd` alias: `-Xmx2g` + G1GC + `jdk.incubator.vector`
  (`deps.edn:107-110`).
- **pod (Node)** — `node out/client/main.js` (`bin/seon:243`), a shadow-cljs
  `:node-script` CJS bundle (`shadow-cljs.edn` `:client`). Node 22-line LTS
  (host runs v26; no exotic APIs — pin an LTS in the image). Runtime npm
  deps resolve from `node_modules` (82 MB on host; includes
  `@vscode/ripgrep`, which installs a PLATFORM BINARY — the image must
  `npm ci` on linux, never copy the mac tree; `node-sqlite3-wasm` is wasm,
  portable).
- **the pod's self-host eval bundle** — `out/bootstrap` (15 MB), loaded via
  `platform/artifact-path "out/bootstrap"` (`src/seon/eval.cljs:349-372`);
  the pod dies opaquely without it (`bin/seon:480-507` builds it pre-spawn).
- **compiled client code** — the `main.js` loader is ~70 KB; the real
  compiled code is the cljs-runtime chunk dir (~55 MB re-measured for the
  bench build; ~122 MB if both bench + client builds ship — the image ships
  ONE). No `:advanced` release build is exercised today — the image
  ships the dev-compile output honestly, and a release-build slimming pass
  is a later, separately-verified unit.
- **sources + config** — the pod re-seeds the core from the source tree at
  boot (`read-src-file`, `src/seon/client.cljs:1148,1230`), so `src/`
  (4.3 MB) + `resources/` (1.2 MB) + `config/` ship in the image.
  `SEON_CONFIG` selects the manifest (`bin/seon:171`); `SEON_RUNTIME_ROOT`
  makes build/source artifacts resolve off an immutable install root while
  data stays CWD-local (`src/seon/platform.cljs:42-88`) — exactly the
  immutable-image / mutable-volume split, already built.
- **plumbing** — two Unix sockets pod↔wire-server (`bin/seon:108-109`), the
  loopback socket REPL (supervisor channel, 7891), the pod port file
  (`bin/seon:113-119`), CSS artifact (`npm run css:build` on pod start,
  `bin/seon:243`).
- **the store** — datahike on **konserve `:file`** (file-tree store:
  `src/seon/server/store.clj:47,140-141`) — NOT LMDB. Plain fsync'd files;
  no mmap/overlayfs hazard. A named volume still holds the data dir so a
  cluster survives container replacement.

**Image design (recommended):**

- **Base:** `debian:bookworm-slim` (glibc, matches the bench task bases in
  §2b), built **multi-arch** (arm64 for this host, amd64 for CI/cloud).
  **The JRE and Node live INSIDE `/opt/seon`** (`/opt/seon/jre` = Temurin
  JRE 25 tarball or jlink'd image; `/opt/seon/node` = the official Node LTS
  linux tarball — both are glibc-linked, portable across the debian/ubuntu
  bases in §2b), NOT as OS packages. This is a hard requirement of the
  A-overlay composition, not packaging taste: the bench task images ship
  NEITHER java NOR node (verified — terminal-bench
  `docker/base-images/ubuntu-24-04/Dockerfile` is ubuntu + tmux/asciinema
  only; SWE-bench `dockerfiles/python.py:2-20` is ubuntu +
  build-essential/miniconda), so a mounted `/opt/seon` must be
  runtime-self-contained to execute at all. The entrypoint invokes
  `/opt/seon/jre/bin/java` and `/opt/seon/node/bin/node` by absolute path —
  never PATH.
- **Build stage** (all compilation at IMAGE BUILD, none at boot):
  `npm ci` (linux-native node_modules) → `clojure -X:deps prep` +
  `clojure -P -M:simd:fork-deps:writer` (bakes the maven cache, 285 MB, +
  gitlibs, 28 MB, + the datahike fork's compiled Java — the same steps
  `bin/seon prep` runs, `bin/seon:614-707`) → `clj -M:cljs compile client`
  → `clj -M:cljs compile bootstrap && bin/fix-bootstrap-macros` →
  `npm run css:build`. **Bake the resolved classpath to a file** so the
  runtime stage launches `java -cp $(cat cp.txt) ... seon.server.boot`
  directly — the clojure CLI and shadow-cljs stay in the builder stage.
- **Runtime stage:** `/opt/seon` = **jre/ + node/ (the bundled runtimes,
  see Base)** + src + resources + config + out/ + node_modules + the baked
  classpath tree — the tree is fully self-contained (mounting it into any
  glibc container yields a bootable cluster); `SEON_RUNTIME_ROOT=/opt/seon`;
  data dir + sockets under `/seon-data` (volume); env in:
  `SEON_PORT=7890` (published), LLM keys (`DEEPSEEK_API_KEY` etc. — the pod
  reads provider config from env), `SEON_SHELL`/`SEON_WEB` grants,
  `SEON_FS_ROOT`/`SEON_FS_READ_ONLY`.
- **One container, both processes** — NOT a compose pair. Reasons: the UDS
  pair and the port file want one filesystem; a cluster is architecturally
  ONE unit (one db + one pod; isolation lives at the CLUSTER boundary, not
  between the pod and its own writer); users get one `docker run`; and the
  bench composition (§2b) must inject the whole cluster into a foreign
  container anyway, which a compose pair cannot do. A compose pair buys
  independent restart of the writer — not worth two lifecycles; the
  restart-resume choreography restarts the pod PROCESS, not a container.
- **Entrypoint:** a slim `seon-entrypoint` (contract in §9, tooling-lane
  ask): start wire-server → gate on the req socket accepting (the existing
  readiness semantics, `bin/seon:321-336`) → start the pod → supervise both
  in foreground, forward signals, exit non-zero when either dies. It is NOT
  the full `bin/seon` (nohup/mkdir-mutex/cljs-watch/jvm are host-dev
  concerns), but it must reuse the same readiness definitions. `bin/seon`
  has no hard macOS assumptions (the java resolver already handles Linux;
  `shasum`/`nc` exist on debian), but the entrypoint sidesteps that class
  of question entirely.
- **Honest awkwardness:** image ≈ 1 GB (bundled JRE ~250 MB full /
  ~60-100 MB jlink'd + node ~110 MB + node_modules 82 MB + m2 285 MB +
  chunks ~55 MB + bootstrap 15 MB + base); the m2 cache can be pruned to
  the :writer classpath and the JRE jlink'd as later slimming — size is a
  polish axis. What is NOT optional is that both runtimes live in the tree
  (see Base): without them A-overlay cannot boot in the runtime-bare task
  images. `deps.edn:72` pins
  `brotli4j/native-linux-x86_64` in the BASE deps ("assumes linux x86_64
  deployment") — it is only loaded by the paused JVM web track
  (`seon.web.brotli`), so the arm64 writer should never touch it, but it is
  a marker that this stack has never actually booted on linux/arm64; slice
  1 exists to flush exactly this class of surprise. JVM memory: `-Xmx2g` is
  the shipped setting; container memory limit ≥ 4 GB (JVM + node + node's
  own heap growth under long trajectories).

### 2b. Composition with per-task bench environments (the crux)

Both anchors are per-task-container benches: SWE-bench Verified = 500
per-instance prebuilt images (repo at base_commit + per-repo conda env,
multi-GB, epoch ghcr `x86_64`/`arm64` variants); terminal-bench = per-task
docker(-compose) with task-authored images. The Seon agent must WORK in the
task's environment while the bench's official oracle runs unchanged. Three
options, argued:

**Option A — Seon runtime INTO the task container (RECOMMENDED).** The
agent's shell, fs, and cwd are natively the task env; the pod's HTTP door
is the container's; no transport verb exists at all. Two mechanisms:

- **A-overlay (primary): mount, don't build.** Extract the canonical
  image's `/opt/seon` tree ONCE (at a pinned seon-image digest) into a
  docker named volume; run the UNMODIFIED official task image with that
  volume mounted read-only + a writable `/seon-data` + the pod port
  published; boot the cluster inside via the same entrypoint. Zero derived
  images (nothing ×500), the official image digest recorded at freeze time
  is the digest that RUNS, and disk cost is one runtime tree. For SWE-bench
  this slots into a first-class seam: `inspect_evals.swe_bench` takes
  `sandbox_config: Callable[[str, Sample], SandboxEnvironmentSpec]`
  (`swe_bench.py:69`, applied at `:154-155`) — we generate a per-sample
  compose file exactly like their `_create_docker_spec` (`:227-250`: image,
  `command: sleep infinity`, `working_dir: /testbed`) plus our two volume
  mounts, the port mapping, and the boot command. Their scorer still runs
  `sandbox().exec` in the SAME container (`scorers.py:32-76`); our mounts
  add paths only under `/opt/seon` + `/seon-data`, touching nothing in
  `/testbed` or the conda env — slice 3 verifies this with a null-run
  (overlay mounted, no agent, official scorer verdict unchanged).
- **A-build (documented, not primary):** per-instance
  `FROM <task-image>` + `COPY --from=<seon-image> /opt/seon /opt/seon`.
  Layer content dedups across instances, but it is ×500 builds, ×500 NEW
  image digests (breaking the freeze rule's "pin the official digest"
  simplicity), and registry/cache churn — all bought for nothing the mount
  doesn't already give. Falls back in if a runtime ever needs root-level
  install steps in the task image (none identified).
- **Base-image compatibility (checked):** SWE-bench instance images are
  ubuntu-based (`reference-code/swe-bench/swebench/harness/dockerfiles/python.py:2`
  — `FROM ubuntu:{ubuntu_version}`; Verified is all-Python, and the epoch
  images ship arm64 variants — `swe_bench.py:50,133-136`). terminal-bench's
  vendored 241 tasks are overwhelmingly glibc: 97×
  `t-bench/ubuntu-24-04` + 88× `t-bench/python-3-13` + assorted
  debian-slim/python-slim (Dockerfile survey of
  `reference-code/terminal-bench/original-tasks/*/Dockerfile`); ~7 tasks
  pin `--platform=linux/amd64` and are excluded from the dev slice on this
  arm64 host. A glibc linux/arm64 Seon runtime tree covers effectively all
  of it.
- **Cost honesty:** every task container now also carries a JVM (-Xmx2g) +
  node — roughly +2.5-3.5 GB RSS worst-case per concurrent sample. Start at
  concurrency 1; raise only on measurement.

**Option B — canonical Seon container as sidecar (FALLBACK ONLY).** The
unmodified Seon image runs next to the task container; `seon.agent.shell`
gets the docker-exec transport from the prior revision (config-selected,
envelope unchanged, in-container `timeout -k` to avoid orphans, cwd gate on
the container root, grants text stating where the shell acts). Deployment
goal met (Seon is containerized) but the agent periscopes into the task env
— the exact framing the owner rejected — and it needs the cross-lane
transport contract. It remains the honest fallback for task images that
cannot host the runtime (musl/alpine, amd64-only under emulation, bizarre
init). It is NOT scheduled; it gets built only if slice 3-5 evidence shows
a non-trivial excluded-task population.

**Option C — task materialized into the Seon container (REJECTED).** Works
only for git-clone-able tasks. SWE-bench's whole value is the prebuilt
per-repo conda environments — the images ARE the reproducible env, and
rebuilding them inside our container means re-owning epoch's build system
(the swebench dockerfile machinery spans per-language base/env/instance
templates). terminal-bench tasks are Dockerfiles + compose services (some
multi-container), not repos to clone. C would silently fork both oracles'
environment assumptions — the anchor comparability dies there.

**The hybrid, stated plainly:** the canonical image is the ONE artifact.
Users run it whole. Bench composition extracts its `/opt/seon` runtime tree
at a pinned digest and injects it into task containers — same bytes, the
surrounding userland is the task's (which is the point of the bench). The
seon-image digest is recorded in `datasets.lock` next to the instance-image
digests; the frozen-bench-bundle sha machinery
(`bin/seon:526-612`, `cluster.py:156-187`) is SUBSUMED by the image digest
for bench rows — one identity, stronger (it pins the whole runtime, not
just the compiled CLJS).

### 2c. Capability 2 — the oracle host (per bench, their own oracle)

- **SWE-bench:** Inspect's docker sandbox provider IS the oracle host.
  `inspect_evals` attaches a per-instance image to every sample
  (`swe_bench.py:149-155`;
  `DEFAULT_IMAGE_TEMPLATE = "ghcr.io/epoch-research/swe-bench.eval.{arch}.{id}:latest"`,
  `:50`; ghcr auth required, `:43-44`) and the scorer runs the official
  eval script via `sandbox().exec` (`scorers.py:32-76`; `get_eval_script`
  `:278-370` activates the per-repo conda env and runs
  FAIL_TO_PASS/PASS_TO_PASS). **We change the solver and the per-sample
  compose (via their `sandbox_config` parameter — a supported extension
  point, not a fork). Nothing oracle-side is written by us.** One
  deviation to name: their default compose sets `network_mode: none`
  (`swe_bench.py:238`) because THEIR model calls happen host-side; our
  in-container pod must reach the LLM API, so our compose gives the
  container egress. §10 carries the hygiene consequences.
- **terminal-bench:** their harness owns the whole oracle: it copies
  `run-tests.sh` + `tests/` into the task container
  (`terminal_bench/harness/harness.py:544-555`), runs them in the tmux
  session (`:557-596`), parses the pane with the task's declared parser
  (`:597-611`), and resolves all-PASSED (`:536-542`). **We change only the
  agent** via the first-class custom-agent hook
  (`agent_factory.py:64-79`, `tb run --agent-import-path`); their harness
  keeps its own docker-compose lifecycle per task.

### 2d. How each bench mounts on the substrate

- **SWE-bench (Inspect-native):** dataset + sandbox + scorer come whole
  from `inspect_evals.swe_bench`; our solver replaces theirs and our
  `sandbox_config` supplies the per-sample compose (§2b A-overlay). Per
  sample the solver: (1) waits for the sandbox (the task container, with
  the Seon runtime mounted and the cluster booted by the compose command),
  (2) resolves the pod's published host port (`docker compose port` /
  inspect on the sample's container — the docker provider exposes the
  connection, `docker.py:486-495`, `environment.py:212`), (3) applies
  per-sample agent config (the `apply_ai_config` precedent,
  `src-inspect-ai/src/seon_inspect/cluster.py:121` — now delivered over
  `docker exec nc 127.0.0.1 <repl-port>` into the in-container wire REPL
  instead of a host loopback port), (4) drives `POST /agents/run` with the
  issue statement (the existing door, `solver.py`), (5) hands back to the
  UNCHANGED official scorer.
- **terminal-bench (their harness, our adapter):** a `BaseAgent` subclass
  run via `tb run --agent-import-path seon_inspect.tb_adapter:SeonAgent`.
  `perform_task(instruction, session, ...)` (`base_agent.py:125`)
  receives the task container directly — `session.container` is a docker-py
  `Container` (`tmux_session.py:7,29,34`) with `exec_run` already used
  throughout (`tmux_session.py:309-310`) and `put_archive` available from
  the same API. The adapter: (1) `put_archive`s the Seon runtime tarball
  into the container (no compose seam exists to mount it — tb tasks own
  their compose files; copying ~1 GB locally is seconds per task, measured
  in slice 5 before acceptance), (2) `exec_run`s the entrypoint to boot the
  cluster, (3) drives `POST /agents/run` — since tb published no ports for
  us, the door is driven via `exec_run(["curl", "-s", "-X", "POST",
  "http://127.0.0.1:7890/agents/run", ...])` from the harness side (an
  operator channel, not an agent-facing surface), (4) returns an
  `AgentResult`; a bridge script converts their results JSON into
  `scorecard.jsonl` rows.
  - **Why their harness and not importing their tasks into Inspect
    (unchanged):** comparability. The published 59.1 is produced by THEIR
    harness — test-copy path, tmux execution, per-task parsers, timeout
    policy, resolution rule. Running it preserves official scoring exactly;
    our number differs from published ONLY by the agent. Accepted costs:
    tb results live outside the Inspect eval log (bridged into the ledger +
    `evals/runs/`), and dataset freezing uses THEIR pinned-dataset
    mechanism recorded in our `datasets.lock`.
  - A container-resident agent is arguably CLEANER under their model than
    their own built-in agents (which live outside and drive tmux
    send-keys): our agent occupies no tmux channel, and their oracle's
    tmux session is untouched by us.

### 2e. What stays Seon-side per sample

One cluster per sample — now the in-container process pair booted from the
overlay, with `POD_MAX_SAMPLES=1` trivially true (one container, one
sample, destroyed with the sandbox). Evidence retention to
`evals/runs/<date>-<name>/` and honest door metadata (`pod_turns`,
`pod_closed_reason`, `pod_model_config` — `solver.py:_record_result`)
unchanged. The cluster and the task container are no longer independent
processes — the cluster LIVES in the container — but the restart-resume
composition survives intact because the unit killed is the pod PROCESS,
not the container (§5). Of the existing `seon_inspect.cluster` machinery
(407 lines): `apply_ai_config` (:121), `wait_pod_ready`-style port probing
(:259), and the `Cluster`/`url` shape (:209) survive re-targeted;
`create_cluster`/`destroy_cluster`/`restart_pod` (:278,:342,:312) become
docker-lifecycle operations for bench rows (`bin/seon cluster create` stays
the HOST-dev verb — it is not deleted, it is simply not the bench path);
`bundle_identity`/`ensure_bench_bundle` (:156,:189) are subsumed by the
seon-image digest.

## 3. SWE-bench Verified plan

- **Dataset + freeze:** `princeton-nlp/SWE-bench_Verified` (500 instances)
  as loaded by `inspect_evals.swe_bench` (revision pinned in their source,
  `swe_bench.py:53-54`). Dev slice: **n≈10-25 instances, frozen by
  instance id in `datasets.lock`**, seeded shuffle, stratified across repos
  (Verified is django-heavy — state the strata in the lock entry).
  **Images pinned by digest:** the epoch templates end `:latest` — at
  freeze time resolve each instance's image to its sha256 digest and record
  it; runs assert the digest before dispatch. **NEW: the seon-image digest
  is a lock entry too** — a row is comparable only within one seon-image
  digest. Cost honesty: instance images are multi-GB; a 25-instance dev
  slice is tens of GB of local cache; the full 500 is hundreds of GB —
  milestone-tier, disk-budgeted.
- **Baseline arm — mini-swe-agent (reproduced open scaffold), unchanged.**
  Not vendored (verified by ls; the heavyweight `swe-agent` sibling is);
  pip-installable and pinned (terminal-bench's own adapter installs it that
  way — `mini-swe-setup.sh.j2`). Baseline arm = mini-swe-agent + DeepSeek
  NT on the SAME frozen slice against the SAME pinned images via its
  official runner, under THEIR standard conditions (including
  `network_mode: none`, since their model calls are host-side). A secondary
  sanity arm — inspect-evals' own default solver — costs one solver flag.
  Pinning flag (owner call, unchanged): vendor mini-swe-agent as a
  submodule (preferred) or pip-pin + hash in the lock.
- **Seon arm:** §2d wiring. Task text = the instance's problem statement
  plus stated environment facts ("your workspace is /testbed inside this
  machine; the fix is judged by this repo's test suite") — NO hints beyond
  what every scaffold gets. FAIL_TO_PASS test NAMES are withheld exactly as
  the official bench withholds them (the stated-check law yields to
  published comparability here; deviation recorded). The context/grants
  statement is now SIMPLER than the transport revision: the shell is
  native — context must state the workspace path and the judging rule,
  nothing about containers.
- **What "beating 73.6" requires, honestly (unchanged):** the full 500 at
  pass@1, official epoch images + official scorer, same model pinned,
  stated k/epochs, flakes classified — milestone-tier, owner-gated, run
  ONCE after dev-slice iteration says ready. Before that, everything is the
  DELTA claim vs the baseline arm on identical frozen inputs. Dev-slice
  numbers never compare to 73.6 directly (±~9pt binomial noise at n=25).

## 4. terminal-bench plan

- **Dataset + freeze (unchanged):** the vendored pin (`1a6ffa96`,
  2026-01-21) carries 241 tasks + a registry of pinned dataset versions
  with NO 2.0 entry — before any comparability claim, resolve which task
  set + harness version produced 59.1 and pin THAT; until then tb rows are
  internal-delta only. Dev slice: n≈10-15 frozen task ids spanning
  categories, EXCLUDING the ~7 `--platform=linux/amd64` tasks on this host
  (recorded in the lock with the exclusion reason).
- **Arms:** baseline = tb's own installed-agent adapters on the same subset
  (`tb run --agent mini-swe-agent --model deepseek/...`; optionally
  Terminus-2). Seon arm = the `--agent-import-path` adapter (§2d). Same
  model, same tasks, same harness — the only variable is the agent.
- **Oracle:** untouched — their copy-in + run-tests.sh + parser +
  all-PASSED resolution (§2c). Their tasks carry canary lines already.
- **Operational:** `--n-concurrent 1` to start (their harness parallelizes
  via ThreadPoolExecutor, `harness.py:1125`; JVM-per-task-container
  compounds host contention). The adapter translates tb's
  `max_agent_timeout_sec` into the door's `timeout_ms` (pod cuts first,
  with margin — the `config.HTTP_MARGIN_S` rule). New: the adapter measures
  and logs the runtime-injection time (`put_archive` + boot) SEPARATELY
  from agent time, so their timeout accounting is not silently eaten by
  our boot.

## 5. Restart-resume composition (the unique claim, kept)

Plan-survives-restart has no public equivalent — it stays ours, composed
with both anchors. The choreography exists whole in `pod_planning_driver`
(create → phase 1 → restart → phase 2 same agent → snapshot → destroy —
`planning.py:234-298`; `agent_id` reuse is a door feature).

- **The structural fact, now in-container:** the task container keeps
  running; the kill target is the pod PROCESS inside it (`docker exec
  pkill -f 'node /opt/seon/out'`, then re-exec the entrypoint's pod stage).
  The repo state, the partial work, the wire-server, and the durable
  cluster store (in-container fs — which persists as long as the container
  does, and on the `/seon-data` volume beyond that) are all UNTOUCHED. The
  agent's plan and memory live in the cluster store. Resume = the same
  `agent_id` woken with "your runtime restarted; finish the task."
- **Scoring: the bench's own oracle, unchanged.** A resumed sample is
  scored exactly like an uninterrupted one — the claim is "kill the agent
  mid-task; the score does not change." `check_plan_trajectory`
  (`planning.py:72-121`) stays a SECONDARY diagnostic column, never a gate.
- **Mechanics per bench:** SWE-bench — a solver variant kills/re-execs the
  pod at a fixed wall-clock fraction (or turn count from door metadata)
  and re-posts; terminal-bench — the adapter does the same inside
  `perform_task` via `exec_run`. Rows: `swe_bench_resume` /
  `terminal_bench_resume`; headline comparison is resume-row vs plain-row
  on the SAME instances, Seon arm only (no baseline scaffold survives a
  kill — that asymmetry IS the result).
- **Order:** after the plain rows are stable — slice 6 in §8.

## 6. Demotions (unchanged)

- **aider-polyglot → at most a cheap smoke row.** Contamination-saturated,
  toy scale, no git/navigation. Keeps one legitimate use: a fast,
  composition-free smoke row to sanity-check cluster/oracle plumbing after
  harness changes — never a headline number, never an A/B target. Not
  scheduled; wired only if a cheap smoke gap actually appears.
- **BFCL — dropped from the roadmap (disposition = owner call).** Protocol-
  scored (grades emitted call SHAPE), fights the form-IS-an-action
  architecture. The gap only ever affects protocol-scored benches; no
  outcome-scored bench needs the tool_calls surface. Disclosure: BFCL is NOT
  hypothetical — it shipped 2026-07-04 (`seon_inspect.bfcl_adapter`, 36
  passing offline tests, a frozen `datasets.lock` split, ledger row
  `2026-07-04:bfcl_ast:dev:k1:armD-full`, plus the form-vs-JSON A/B whose
  eval-native follow-up was awaiting an owner decision). Dropping it moots
  that pending decision. Recommendation: keep the adapter + tests in place
  unscheduled (zero maintenance, they document the format-scored cautionary
  tale); delete only if the owner prefers no inert code.
- **tau2 — dropped.** Same protocol-scoring family + a user-simulator LLM;
  its stateful-backend signal is dominated by terminal-bench's
  stateful-environment tasks, which score outcomes.

The bespoke generator rows from v1 (`bug_fix`, `multi_file_change`,
`repo_navigation`) stay DE-CENTERED, not doctrinally dropped; they return
only on measured need (e.g. a contamination-proof fresh-seed control row).

## 7. Ledger, flake taxonomy, `behavior_miss` (kept, one addition)

Rows: `swe_bench_verified` (+ `_resume`), `terminal_bench` (+ `_resume`),
one `scorecard.append_row` line per run with model provenance
(runtime-derived via `pod_model_config`), pass^k via epochs where cost
allows (state k honestly per row), the standing regression alarm unchanged.
tb rows enter through the bridge script; evidence (their run dir + our door
metadata) lands in `evals/runs/<date>-<name>/`.

The **`behavior_miss`** class is unchanged and MORE essential at these
trajectory lengths: run ended without a terminal reply —
`pod_closed_reason` ∈ {`:turn-limit`, `:deadline-exceeded`}
(`src/seon/agent/run.cljs:92-109`; loop close reasons
`src/seon/agent/loop.cljs:314-321`) or a non-timeout close with empty
reply. Counted as FAIL in the capability mean, attributed distinctly,
disjoint from `solve_timeout` and `run_error`. Uniform behavior_miss across
a row triggers the uniform-0 law. Sibling classes: **`oracle_env_fault`**
(the bench's own oracle failed — image pull failure, container died, tb
TEST_TIMEOUT/PARSE_ERROR) and NEW **`seon_env_fault`** (the in-container
cluster failed to boot or died for non-agent reasons — entrypoint exit,
wire-server OOM; detected by the entrypoint's exit status + pod log,
retained as evidence). Both are flake classes, never fails.

Turn budgets: SWE-bench trajectories will not fit the default turn-limit 20
(`run.cljs:92-109`) or 300s (`config.py`). Per-sample `timeout_ms` threads
through the door (`solver.py:_resolve_timeout_ms`); the **cluster-level
default run bounds remain the standing cross-lane ask (§9) — REQUIRED for
this suite**, sized from dev-slice turn distributions.

## 8. Ordered build plan (landable slices, each with acceptance criteria)

1. **The canonical Seon image, standalone (proves the packaging).**
   Dockerfile (builder + runtime stages per §2a) + the entrypoint; build
   for linux/arm64; `docker run -p 7890:7890 -v seon-data:/seon-data
   -e DEEPSEEK_API_KEY=… seon:<tag>`. Drive `POST /agents/run` with a
   trivial task from the host. **Accept when:** the container boots to
   "agent roster" with zero host mounts of the repo; the agent returns a
   terminal reply through the door; `docker restart` of the container with
   the volume shows the cluster db intact (core NOT re-seeded from
   scratch); the image digest is recorded. This slice is EXPECTED to flush
   linux-first-boot surprises (the brotli4j-style markers, path
   assumptions) — that is its job. Zero bench code.
2. **Oracle-path proof, no Seon (de-risk docker + epoch images + scorer).**
   Run `inspect_evals.swe_bench` UNCHANGED (their default solver) on 1-2
   hand-picked Verified instances: ghcr auth, arm64 pull, sandbox up,
   official scorer verdict. **Accept when:** an Inspect eval log shows a
   scored sample with the eval script's test output in evidence; instance
   image digests recorded. Zero Seon code touched.
3. **The composition — Seon layered into ONE instance (first honest ledger
   row).** Build the runtime-overlay volume from the slice-1 image digest;
   write the `sandbox_config` compose generator + the §2d solver; run the
   same 1-2 instances with the Seon arm. **Accept when:** (a) a null-run
   (overlay mounted, no agent) scores IDENTICALLY to slice 2 on the same
   instance — the overlay provably doesn't perturb the oracle; (b)
   evidence shows the agent's shell verbs executed natively in `/testbed`
   (files changed in-container, no transport in any code path); (c) the
   official scorer produced the verdict; (d) one honest
   `swe_bench_verified` ledger row exists (n=1-2, labeled dev-spike, both
   digests in the lock).
4. **Frozen dev slice + both arms (the first real comparison).** Freeze
   n≈10-25 instances + instance digests + the seon-image digest in
   `datasets.lock`; run baseline (mini-swe-agent, pinned) and Seon arms on
   the identical slice; record turn/latency distributions (the
   budget-sizing memo). **Accept when:** two comparable ledger rows exist
   with flake attribution, and the run-bounds ask in §9 carries measured
   evidence.
5. **terminal-bench adapter + dev subset.** The `BaseAgent` adapter
   (`put_archive` + exec boot + exec-curl door + results-JSON→ledger
   bridge); resolve the TB 2.0 pin question (§4); freeze the subset; run
   tb's mini-swe-agent baseline + Seon arm at `--n-concurrent 1`.
   **Accept when:** `terminal_bench` rows exist for both arms on the same
   frozen subset, injection time is measured + logged separately, and
   their harness's own results files are retained as evidence.
6. **Restart-resume composition rows** (§5) on the stable slices of both
   benches. **Accept when:** resume rows exist, scored by the unchanged
   bench oracles, plan-trajectory attributed as a secondary column, and a
   mid-task pod kill demonstrably did not corrupt the container, the repo
   state, or the score path.
7. **Milestone-tier full runs (owner-gated, costed).** Full 500 Verified /
   full tb set, both arms, pass@1 with stated epochs — the runs behind any
   public "beats 73.6 / 59.1" claim. Disk (hundreds of GB), API spend, and
   wall-clock budgeted and approved BEFORE launch.

## 9. Needs-infra + cross-lane flags (honest list)

- **The canonical image + entrypoint — cross-lane: tooling (the big one,
  REPLACING the shell-transport ask).** Containerization is now canonical
  distribution, so the packaging is a product surface the tooling lane
  owns. The CONTRACT this suite needs (eval lane prototypes it in slice 1
  and hands it over): a `seon-entrypoint` that boots wire-server + pod in
  FOREGROUND from an immutable `/opt/seon` tree (no compile at boot, no
  nohup, no locks), gates the pod on the wire socket exactly as
  `ready_check` does (`bin/seon:321-336`), forwards signals, exits non-zero
  on either process death, and supports "restart the pod stage only" (the
  resume choreography). Plus: everything path-configurable that today
  assumes the repo CWD keeps resolving via `SEON_RUNTIME_ROOT` (already
  true for artifacts — `platform.cljs:42-88`; slice 1 flushes stragglers).
  No agent-facing surface changes at all — `seon.agent.shell` stays exactly
  the host argv-exec it is (`src/seon/agent/shell.cljs:173`), because "the
  host" is now the task container.
- **Cluster-level run bounds (turn-limit/deadline defaults) — cross-lane:
  tooling. KEPT, still required** (§7); sizing evidence arrives from
  slice 4.
- **LLM egress from bench containers — eval lane, needs design.** Interim:
  compose grants egress + `SEON_WEB=0` (the agent has no web verb; the
  MODEL API is the only intended egress). A strict allowlist (proxy sidecar
  or network policy admitting only the LLM endpoint) is the honest
  end-state and a named gap until built — recorded on every row produced in
  the interim.
- **ghcr auth + image cache management — operational, eval lane.** Login
  per `swe_bench.py:43-44`; digest pinning at freeze; documented
  cache-prune policy (instance images are the disk budget).
- **mini-swe-agent pinning — owner call (kept).** Vendor as submodule
  (preferred) or pip-pin + hash in the lock.
- **terminal-bench 2.0 pin — eval lane (kept).** Bump or fetch-and-pin
  before any comparability claim.
- **Code home — owner call.** Harness-side code stays in `src-inspect-ai/`
  (`seon_inspect.swebench_arm`, `seon_inspect.tb_adapter`, the compose
  generator, the ledger bridge). The Dockerfile + entrypoint are NOT bench
  tooling — as canonical distribution they belong at top level (suggest
  `docker/` or `bin/`), consistent with the no-maintained-code-in-PRD-dirs
  rule. Flagged with a recommendation (top-level `docker/`), not decided
  here.
- **Resource isolation remains soft:** task containers now each carry a
  JVM + node beside the repo's own test processes, all on one host with the
  model API; concurrency defaults start at 1 and rise only on measurement.

## 10. Risks + falsifiers

- **Comparability-to-published-anchor (top methodological risk, kept).**
  73.6/59.1 come from scaffolds/harness versions we don't control.
  Mitigations structural: the delta claim vs a reproduced baseline on
  identical frozen inputs; tb scored by THEIR unmodified harness; no
  dev-slice number compared to a published full-set number. NEW asymmetry
  to disclose: the Seon arm's container has LLM egress while the baseline
  runs under their standard `network_mode: none` (their model calls are
  host-side) — both arms' agents get the same INFORMATION only if Seon's
  egress is limited to the model API; until the allowlist lands, every row
  states the interim condition. Falsifier: our baseline arm lands far from
  its own published ballpark — indicts our environment/pins before any
  Seon claim.
- **Linux-first-boot unknowns.** The stack has only ever run on macOS
  hosts; `deps.edn:72`'s x86_64-only brotli native pin is a visible marker
  of untested linux corners. Slice 1 exists to flush these cheaply,
  standalone, before any bench coupling. Falsifier: the canonical image
  needs src changes to boot — those are ordinary portability fixes, made
  loudly, never worked around in the compose layer.
- **Overlay must be runtime-self-contained (load-bearing for A-overlay).**
  The task images ship neither java nor node (verified, §2a Base), so the
  mounted `/opt/seon` carries its own JRE + Node, glibc-linked against the
  debian/ubuntu bases. Untested corners: a task image on a musl base or an
  older glibc than the bundled runtimes were built against would not
  execute them. Falsifier: slice 3's boot-inside-the-instance-image step
  fails to exec the bundled runtimes → fall back to A-build
  (`FROM <task-image>` + COPY) for that image class; the design survives,
  the zero-derived-images claim narrows honestly.
- **Overlay perturbs the oracle.** The mount adds paths outside `/testbed`
  and the conda env, but "should not interfere" is proven, not assumed:
  slice 3's null-run accepts only on an identical official-scorer verdict
  with and without the overlay. Falsifier: any verdict delta on the
  null-run → the composition mechanism is wrong, stop and redesign before
  scoring anything.
- **In-container resource footprint.** JVM (-Xmx2g) + node inside every
  task container beside the repo's test suite; OOM inside a sample would
  read as agent failure. Mitigation: container memory limits set explicitly
  (≥4 GB), `seon_env_fault` attribution (§7) keeps boot/OOM deaths out of
  the capability mean, concurrency 1. Falsifier: `seon_env_fault` clusters
  on memory-hungry repos → shrink the JVM (the writer serves ONE pod here;
  -Xmx can likely drop well below 2g — measured, not assumed).
- **Cost realism (kept).** Multi-GB instance images (hundreds of GB at
  500), long trajectories, paid API per turn, epochs multiply. The
  runtime-overlay keeps OUR side at zero derived images and one ~1 GB
  runtime tree; the instance-image cache remains the disk budget. Dev-slice
  discipline + once-per-unit cadence; slice 7 owner-gated with a budget.
- **Turn/clock budget insufficiency masquerading as incapability (kept).**
  `behavior_miss` + the uniform-0 law make it visible in one ledger glance;
  slice 4 measures before any budget is trusted.
- **Context-omission scoring zeros (kept, simplified).** The rendered
  context must state the workspace path and how the work is judged. With
  the agent native in the task env there is no transport story to explain —
  the grants text says "your shell runs in this machine; the workspace is
  /testbed". Every scored check that can be stated without leaking the
  oracle is stated.
- **arch coverage (kept, sharpened).** Epoch serves `{arch}` per instance —
  arm64 coverage verified at freeze; tb's ~7 amd64-pinned tasks excluded on
  this host and noted in the lock. Falsifier: digest resolution fails for
  arm64 on chosen instances → re-draw the slice, never emulate silently.
- **Image drift = the new bundle drift.** The frozen-bundle sha machinery
  is subsumed by the seon-image digest; a run records the digest at start
  and asserts it at end (same contract, stronger scope). Falsifier: any
  bench row produced from an image not matching the lock digest is VOIDED,
  exactly as `frozen_bundle_changed` rows were.
