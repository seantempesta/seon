---
type: research
status: active
tags: [research, agent]
---

# Inspect Docker sandbox integration — running inspect_evals agentic/coding benches

## TL;DR

- **Docker is installed but the daemon is DOWN.** `docker --version` → `29.6.1`
  (Compose v5.3.0), but Docker Desktop is not running: no process
  (`pgrep "Docker Desktop"` empty) and the socket is a dangling symlink
  (`/var/run/docker.sock → ~/.docker/run/docker.sock`, target absent). One
  `open -a Docker` + a ~30s wait fixes it. Nothing else is missing.
- **The sandbox contract is one Task parameter.** A task declares
  `sandbox="docker"` or `sandbox=("docker", "<compose.yaml>")`
  (`inspect_ai.util.SandboxEnvironmentType`). inspect discovers a sibling
  `compose.yaml`/`Dockerfile`, else synthesizes a **generic python container
  from `aisiuk/inspect-tool-support`**, brings it up once per task, and routes
  the model's `bash`/`python`/`text_editor`/`web_browser` tool calls into it via
  `sandbox().exec(...)`. Cite: `docker/config.py:113-133`, `docker/docker.py:61-130`,
  `_sandbox/environment.py:92-115`.
- **Two execution models are NOT mutually exclusive.** Standard inspect mode
  (inspect owns the react loop, native tools → container) measures the raw
  MODEL. Seon already proved they can compose: `swebench_arm.py` boots the seon
  **pod INSIDE the sandbox container** and drives it through `POST /agents/run`,
  keeping the bench's official sandbox + scorer. So "through seon" is real for
  swe_bench, but it's heavy plumbing.
- **Recommendation:** run agentic/coding benches in **standard inspect mode for
  MODEL baselines** (cheap, established numbers, the pod's native-tool mismatch
  that broke gsm8k/bfcl pod-runs is irrelevant here). Keep the seon-native tasks
  (`e1_spec_fn`, `planning.pod_planning_driver`) for SYSTEM measurement. Only
  invest in the `swebench_arm`-style pod-in-sandbox composition where you
  specifically want to score the seon RUNTIME on a repo-editing bench.
- **Bang-for-buck shortlist:** `humaneval` → `mbpp` → `gaia` →
  `gdm_intercode_ctf` → `swe_bench_verified_mini`. First two are near-zero
  setup (generic image, tiny datasets, host-executed scoring). GAIA needs one
  small prebuilt image. swe_bench pulls multi-GB per-instance images and needs
  `uv sync --extra swe_bench`.

---

## 0. Environment check (verbatim)

```
$ docker --version
Docker version 29.6.1, build 8900f1d
$ docker compose version
Docker Compose version v5.3.0
```

Daemon state — **stopped**:

```
$ docker ps
failed to connect to the docker API at unix:///Users/sean/.docker/run/docker.sock;
check if the path is correct and if the daemon is running: ... connect: no such file or directory
$ docker context ls
NAME              DOCKER ENDPOINT                              ...
default           unix:///var/run/docker.sock
desktop-linux *   unix:///Users/sean/.docker/run/docker.sock
$ ls -la ~/.docker/run/          # EMPTY — the desktop-linux socket does not exist
total 0
$ pgrep -lf "Docker Desktop"     # nothing — Desktop VM not launched
```

(The first `docker info` in this session printed only the *client* plugin
block and then stalled on the server section — same root cause: daemon down.)

**To make it usable:** `open -a Docker` and wait for the whale icon / the socket
to appear (`until docker info >/dev/null 2>&1; do sleep 2; done`). No install,
no config change needed.

Tooling in `src-inspect-ai/.venv` is ready:

```
$ .venv/bin/inspect --version
inspect_ai version 0.3.246
$ .venv/bin/python -c "from inspect_evals.humaneval import humaneval; print(humaneval().sandbox)"
type='docker' config=None
```

`swebench` optional dep is NOT installed (`find_spec('swebench') → False`) — only
matters for the swe_bench scorer path.

---

## 1. The sandbox mechanism (inspect_ai source)

**The abstract contract.** `SandboxEnvironment` is an ABC whose core is one
method, `exec(cmd, input, cwd, env, user, timeout, ...)` — "Environment for
executing arbitrary code from tools" providing "both an execution environment
as well as a per-sample filesystem context."
Cite: `reference-code/inspect-ai/src/inspect_ai/util/_sandbox/environment.py:92-115`.
It also exposes `read_file`/`write_file` and a `SandboxConnection`
(`environment.py:73-90`). Providers register via the `@sandboxenv(name=...)`
decorator (`util/_sandbox/registry.py`).

**The docker provider.**
`@sandboxenv(name="docker") class DockerSandboxEnvironment` —
`util/_sandbox/docker/docker.py:61`. Its `config_files()` returns
`COMPOSE_FILES + [DOCKERFILE]` (`docker.py:64-65`). `task_init` (`:77`) validates
prereqs, creates a `ComposeProject`, then **`compose_build` (`:96`)** and for each
service either builds an internal image, or **`compose_pull` (`:125`)** a remote
image (skipped if already present locally — `docker_image_exists_locally`,
`:122`). Default concurrency is `2 × cpu_count` (`:71-74`).

**How a task declares it → gets a container.** A `Task` carries a `sandbox`
field of type `SandboxEnvironmentType`, which is one of:
`"docker"` (bare provider name), or a tuple/`SandboxEnvironmentSpec`
`("docker", "<path/to/compose.yaml>")`. The eval runner reads `task.sandbox`
and calls the provider's `task_init`/`sample_init` lifecycle — see
`inspect_ai/_eval/task/sandbox.py` (`sandboxenv_context`), which wires each
sample's `sandbox()` accessor to a live `DockerSandboxEnvironment`.

**Compose/Dockerfile discovery — the exact rule** (`docker/config.py:19-51`,
`resolve_compose_file`):

1. A sibling `compose.yaml`/`compose.yml`/`docker-compose.yaml` (`COMPOSE_FILES`)
   next to the config path → used directly (`config.py:28-29,54-58`).
2. Else a sibling `Dockerfile` → synthesize
   `COMPOSE_DOCKERFILE_YAML` that `build:`s it (`config.py:37-46,123-133`).
3. **Else nothing declared → a generic container:** `COMPOSE_GENERIC_YAML`,
   `image: "aisiuk/inspect-tool-support"`, `command: "tail -f /dev/null"`,
   `network_mode: none` (`config.py:49-51,113-121`). **This is the default a bare
   `sandbox="docker"` gets.**

So the exact contract a task declares to get a container is: set
`sandbox="docker"` (generic image) or `sandbox=("docker", COMPOSE_FILE)`
(explicit image/services). Per-sample overrides are possible by setting
`sample.sandbox` (used by swe_bench, below).

---

## 2. How three concrete agentic tasks use it

### 2a. `gaia` — react agent + web tools in a container

`inspect_evals/gaia/gaia.py`:
- Sandbox default: `DEFAULT_DOCKER_SANDBOX = ("docker", str(COMPOSE_FILE))`
  (`gaia.py:17-20`), passed as the task `sandbox=` (`gaia.py:36,74-82`).
- Image: `gaia/compose.yaml` is 4 lines — `image: aisiuk/inspect-tool-support`,
  `init: true`. **The small generic tool-support image, no build.**
- Tools reach the container via the default react agent:
  `tools=[bash(code_timeout), python(code_timeout)] + web_browser()`
  (`gaia.py:100-122`) — each tool call runs `sandbox().exec(...)` inside the
  container; `web_browser()` drives a headless browser service in the same image.
- Scored host-side by `gaia_scorer()` (substring/normalized match against the
  validation answer; test split has no scorer, `gaia.py:70-71`).
- **Heaviness:** one small prebuilt image pull (~hundreds of MB), a HF dataset
  download (`gaia_dataset`, revision-pinned `gaia.py:23`) that is gated and may
  require HF auth for the GAIA repo. `message_limit=100` per sample →
  long-ish agentic rollouts.

### 2b. `humaneval` / `mbpp` — generate once, execute-to-score

`inspect_evals/humaneval/humaneval.py`:
- `sandbox: str = "docker"` (`:68`), set on the Task (`:87`). No compose file →
  **generic `aisiuk/inspect-tool-support` image** (config.py rule 3).
- The MODEL does NOT get tools here: solver is plain `generate()` (`:85`). The
  container is used only by the **scorer**: `verify()` runs the model's extracted
  code + the unit tests via `sandbox().exec(["python","-c", ...], timeout=30)`
  (`humaneval.py:93-129`). `mbpp` is identical (`mbpp.py:140` `sandbox="docker"`,
  `:169` `sandbox().exec`).
- **Heaviness:** trivial. One small generic image, 164 samples (humaneval) /
  ~500 (mbpp), datasets download in <1s (proven: humaneval generated 164 rows
  instantly above). `NUM_EPOCHS=5` default. No per-instance builds.

### 2c. `swe_bench` — per-instance prebuilt repo images

`inspect_evals/swe_bench/swe_bench.py`:
- **Per-sample** sandbox, not a task-level one: for each sample it resolves an
  image name from the instance id and writes a per-instance compose file
  assigning `sample.sandbox` (`swe_bench.py:143-155`, `_create_docker_spec:227-249`).
- Image templates (`:47-50`): DockerHub `swebench/sweb.eval.{arch}.{org}_1776_{repo}-{issue}`
  or Epoch AI `ghcr.io/epoch-research/swe-bench.eval.{arch}.{id}` (default). Arch
  auto-detected → `arm64` on this Mac (`:132-134`). Each compose is
  `image: <instance image>`, `command: sleep infinity`, `working_dir: /testbed`,
  `network_mode: none` unless `allow_internet` (`:239-247`).
- Tools reach the container via
  `swe_bench_agent_with_inspect_tool_support(tool_timeout=...)` (`:159`,
  `solvers.py`) — a react agent with bash/python/text_editor bound to the
  `/testbed` checkout.
- Scored host-side by `swe_bench_scorer()` (`:161`, `scorers.py`) which runs the
  instance's `FAIL_TO_PASS`/`PASS_TO_PASS` tests inside the same container.
- **Heaviness — the hard one:** each instance is its own **multi-GB prebuilt
  image** pulled from ghcr.io/DockerHub (ghcr may need
  `gh auth token | docker login ghcr.io`, `:43-44`); requires
  `uv sync --extra swe_bench` for the `swebench` scorer package (`:100-102`,
  currently absent). `message_limit=30`. Use `swe_bench_verified_mini`
  (`:168-213`, the MariusHobbhahn 50-instance subset) to bound the pull count.

### 2d. `bfcl` exec / multi-turn categories

The catalog already runs the **AST subset** host-side with no sandbox
(`catalog.py:99-104`, `_bfcl_ast_kwargs` pins the pure-python categories). The
comment at `catalog.py:47-52` is explicit: bfcl's upstream default is *every*
category "incl. exec + multi-turn, which pull a sandbox." Those exec/multi-turn
categories actually run the candidate functions, so they fall in the same
sandboxed/case-2 tier as humaneval — not wired here, and lower priority than the
dedicated coding benches.

---

## 3. What it takes to stand up ONE bench in standard inspect mode

**Prereqs (one-time):**

```bash
# 1. start the daemon (it is currently DOWN)
open -a Docker
until docker info >/dev/null 2>&1; do sleep 2; done   # ~30s cold

# 2. point inspect at the Muse endpoint via inspect's openai-api service prefix.
#    Provider contract (openai_compatible.py:79-116): model "openai-api/<svc>/<model>"
#    reads <SVC>_API_KEY and <SVC>_BASE_URL from env (svc uppercased, - -> _).
#    Reuse the SAME base_url the pod uses (.env: SEON_AI_BASE_URL / SEON_AI_API_KEY).
export MUSE_BASE_URL="$SEON_AI_BASE_URL"     # e.g. the openai-compat Muse gateway
export MUSE_API_KEY="$SEON_AI_API_KEY"       # never echo this
MODEL="openai-api/muse/$SEON_AI_MODEL"       # e.g. the Muse Spark model id
```

(`.env` currently sets `SEON_AI_PROVIDER=openai-compat`,
`SEON_AI_BASE_URL`, `SEON_AI_MODEL`, `SEON_AI_API_KEY` — the pod's Muse config.
Do not print the key values.)

**Run the cheapest sandboxed bench end-to-end (humaneval, 2 samples):**

```bash
cd /Users/sean/src/seon-stable/src-inspect-ai
.venv/bin/inspect eval inspect_evals/humaneval \
  --model "$MODEL" \
  -M base_url="$MUSE_BASE_URL" \
  --limit 2 --epochs 1 \
  --display plain --log-dir logs/inspect-docker-smoke
```

What happens: inspect brings up one `aisiuk/inspect-tool-support` container for
the task, the model `generate()`s each solution, `verify()` execs the code+tests
in the container, scores `accuracy()`. First run pulls the generic image once
(small); subsequent runs reuse it.

**MBPP is the same one-liner** with `inspect_evals/mbpp`.

**GAIA (needs the same small image + the GAIA dataset):**

```bash
.venv/bin/inspect eval inspect_evals/gaia --model "$MODEL" \
  -M base_url="$MUSE_BASE_URL" -T subset=2023_level1 \
  --limit 2 --message-limit 30 --display plain
```

(If the GAIA HF repo is gated, `huggingface-cli login` first — dataset access,
not a sandbox issue.)

**Easy vs hard:**

| Tier | Benches | Why |
|---|---|---|
| **Easy** (generic image, no build, small data) | `humaneval`, `mbpp`, `gaia` (small image), `gdm_intercode_ctf`, `class_eval`, `ds1000` | prebuilt `aisiuk/inspect-tool-support` or one small image; host-executed scoring; datasets are small HF pulls |
| **Medium** (one custom image build, or a HF-gated dataset) | `bigcodebench`, `scicode`, `assistant_bench`, most `cybench` challenges (per-challenge compose) | a `Dockerfile`/`compose.yaml` build per task, minutes each |
| **Hard** (multi-GB per-instance images / extra deps) | `swe_bench` (+`swe_bench_verified_mini`), `swe_lancer`, `theagentcompany` (~40 per-task composes), `core_bench`, `mle_bench`, `paperbench`, `osworld` | per-instance GB-scale image pulls/builds, optional deps (`--extra swe_bench`), ghcr auth, long rollouts |

Across `inspect_evals` there are **~45 tasks that reference a sandbox** (unique
top-level bench dirs referencing `sandbox` in `.py`), and **257 `compose.yaml`/
`Dockerfile` files** total (most are the per-challenge `cybench`/`gdm_*_ctf`/
`theagentcompany` fan-outs). The "~51" figure in the prompt is the same
population counted with the per-challenge composes rolled in.

---

## 4. The seon integration question — can a sandbox and the pod coexist?

**They are NOT mutually exclusive — seon already built the composition.**
`seon_inspect.swebench_arm` (`src-inspect-ai/src/seon_inspect/swebench_arm.py`)
uses inspect_evals' own `sandbox_config=` seam (`swe_bench.py:69`, applied
`:154-155`) to mount the seon runtime (`/opt/seon` from a pinned image digest,
`swebench_arm.py:80`) READ-ONLY into the **unmodified official instance image**
and boot the pod INSIDE the task container (`command: ["/seon-entrypoint","all"]`,
`:216`), publishing the pod port per sample (`:118-127`). The solver then drives
`POST /agents/run` against the in-container pod (`:392-397`) while the
**official swe_bench scorer's `sandbox().exec` path is untouched** (`:11-13`). It
even builds a socat relay for model-API-only egress (`:233-257`). So "through
seon" is a real, working shape — the sandbox provides the isolated repo + scorer,
the pod provides the agent loop.

**But the two RUN MODES are architecturally distinct per sample:**

- **Standard inspect mode:** inspect owns the react loop; the model's native
  `bash`/`python`/`editor`/`web_browser` tool calls execute in the container via
  `sandbox().exec`. Measures the **raw MODEL**. This is where the ~45 sandboxed
  benches "just work."
- **Pod-backed via `run_bench(cluster_url=...)`:** routes `state.user_prompt`
  text to `POST /agents/run`; the model runs as a seon agent whose tools are
  Clojure evals — **no inspect tool-bridge, no sandbox**. `catalog.load_bench_task`
  hard-refuses case-2/sandboxed benches through this door
  (`catalog.py:148-157`: swe_bench raises "drive it through its own driver").
  This is why gsm8k/bfcl pod-runs surfaced agent errors: the seon agent is not a
  drop-in for inspect's native-tool agentic loop, and for a sandboxed bench there
  is no text-in/text-out contract to route at all.
- **Pod-in-sandbox (`swebench_arm`):** the ONLY way to score the seon RUNTIME on
  a sandboxed bench — and it is bespoke, per-bench plumbing (compose synthesis,
  port allocation, in-container boot, egress relay, run-bound transaction over the
  wire REPL). It exists for swe_bench and nothing else.

**Recommendation.** For the agentic/coding benches, run them in **standard
inspect mode for MODEL baselines** — it is the cheap, established-number path and
sidesteps the pod's native-tool mismatch entirely. Keep the seon-native tasks
(`e1_spec_fn` for Clojure codegen, `planning.pod_planning_driver` for
plan-survives-restart) as the SYSTEM measurement, because no generic bench
measures DB-backed durable planning without a sandbox (README lines 186-197 make
this case). Only extend the `swebench_arm` pod-in-sandbox pattern to a second
bench when you have a specific, funded reason to score the seon RUNTIME on
repo-editing — it is the highest-effort integration in the tree and should not
be the default way to consume these benches.

---

## 5. Ranked shortlist — most signal per unit of setup

Goal weighting: agentic planning + tool use + writing/running code.

1. **`humaneval`** — near-zero setup (generic image, 164 samples, instant
   dataset, host-executed unit-test scoring). The canonical
   "write-and-run-code" MODEL number; a clean daemon-up smoke and the first
   baseline to log. Low ceiling (single-function) but maximal bang/effort.
2. **`mbpp`** — same generic image, same one-liner, ~500 short programming
   tasks. Complements humaneval with more breadth; together they are the
   cheap code-gen pair. Run both in the first Docker session.
3. **`gaia`** — the headline **agentic tool-use + multi-step planning** bench
   (bash+python+web_browser react loop, `message_limit=100`). One small
   prebuilt image; only friction is the possibly-gated HF dataset. Start with
   `subset=2023_level1`, `--limit`. Best signal for "long-horizon tool use"
   without per-instance image builds.
4. **`gdm_intercode_ctf`** — InterCode-style bash/CTF tasks in a container;
   exercises iterative tool use + shell reasoning with modest per-challenge
   images. Good agentic signal, moderate setup, and it is the closest generic
   analog to seon's own shell/file toolbelt.
5. **`swe_bench_verified_mini`** — the real repo-editing agentic bench, but
   gated behind `uv sync --extra swe_bench`, arm64 per-instance GB image pulls,
   and possibly ghcr auth. Use the 50-instance mini subset with a small
   `--limit`. Highest capability signal, highest setup cost — do it AFTER the
   top four prove the daemon + endpoint path, and it is the one bench where the
   existing `swebench_arm` pod-in-sandbox arm can later score the seon runtime.

Deprioritized for now: `theagentcompany` (~40 per-task composes, heavy),
`bigcodebench`/`scicode` (custom builds), the `cybench`/`gdm_*_ctf` security
fan-outs (many per-challenge images, niche for our goals), `swe_lancer`/
`core_bench`/`mle_bench`/`osworld`/`paperbench` (multi-GB, long rollouts).

---

## VERIFIED end-to-end on the LOCAL 35B (2026-07-13)

`humaneval` limit=1 run against the local MLX server, standard inspect mode
(model → Docker sandbox → execute → score). Result: **accuracy 1.000** once the
gotchas below were fixed. Zero paid cost (local model + local sandbox).

Working invocation (the `local` service reads `LOCAL_BASE_URL`/`LOCAL_API_KEY`):

```bash
cd src-inspect-ai
export LOCAL_BASE_URL="http://127.0.0.1:8081/v1"   # the MLX mlx_lm.server
export LOCAL_API_KEY="sk-local-nokey"              # mlx server ignores it; the provider needs it set
.venv/bin/inspect eval inspect_evals/humaneval \
  --model "openai-api/local/mlx-community/Qwen3.6-35B-A3B-4bit-DWQ" \
  --limit 1 --epochs 1 --max-tokens 2048 \
  --display plain --log-dir logs/humaneval-local-smoke
```

Three gotchas hit + fixed (each a ROOT fix, no hacks):

1. **`openai` package was absent** → `OpenAI Compatible API requires optional
   dependencies`. Added to `src-inspect-ai/pyproject.toml` (the pod-backed
   solvers use stdlib urllib and never needed it; standard mode does).
2. **Do NOT pass `base_url` twice** — the `openai-api/<svc>/<model>` prefix
   already derives it from `<SVC>_BASE_URL`; ALSO passing `-M base_url=` →
   `get_model() got multiple values for keyword argument 'base_url'`.
3. **`--max-tokens` default (512) TRUNCATES a thinking model.** The 35B
   (thinking ON) spent all 512 tokens REASONING and emitted no code →
   `stop_reason: max_tokens`, empty answer, accuracy 0.0. Raise to ≥2048 for
   any coding bench. Observed: the 35B burned ~2040 tokens on a TRIVIAL problem
   (had the answer after ~300) — thinking-heavy; for the small workhorse model
   run thinking OFF.

---

## Key file:line index

- Sandbox ABC + `exec` contract: `reference-code/inspect-ai/.../util/_sandbox/environment.py:92-115`
- Docker provider (`@sandboxenv`, task_init, build/pull): `.../_sandbox/docker/docker.py:61-130`
- Compose/Dockerfile discovery + generic image: `.../_sandbox/docker/config.py:19-51,113-133`
- openai-compat provider env contract: `.../model/_providers/openai_compatible.py:79-116`
- gaia task (sandbox default, tools, scorer): `inspect_evals/gaia/gaia.py:17-20,36,74-122`; image `gaia/compose.yaml`
- humaneval (sandbox + execute-to-score): `inspect_evals/humaneval/humaneval.py:68,87,93-129`; mbpp `mbpp.py:140,169`
- swe_bench (per-instance images, per-sample compose): `inspect_evals/swe_bench/swe_bench.py:47-50,132-155,227-249`
- seon catalog (case1 vs swebench kind, case-2 refusal): `src-inspect-ai/src/seon_inspect/catalog.py:99-111,148-157`
- seon pod-in-sandbox composition: `src-inspect-ai/src/seon_inspect/swebench_arm.py:1-52,80,154-257,375-402`
- seon README case-1/case-2 table + planning rationale: `src-inspect-ai/README.md:158-197`
