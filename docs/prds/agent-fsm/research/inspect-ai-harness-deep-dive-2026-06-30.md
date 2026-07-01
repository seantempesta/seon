---
type: research
status: active
tags: [research, agent]
---

# inspect-ai as the Host Harness for a Seon Self-Evolving-Memory Eval Loop

## TL;DR

`inspect-ai` (UK AISI's eval framework, vendored at
`reference-code/inspect-ai/`) is a near-perfect fit for hosting a Seon
"self-evolving memory" eval. Its model is **Task = dataset + solver +
scorer**, and its **`SandboxEnvironment`** ABC is a small, well-documented
4-method surface (`exec`, `read_file`, `write_file` + the
`sample_init`/`sample_cleanup` lifecycle classmethods) that maps cleanly
onto `mvm`'s Python SDK (`Sandbox.create / exec / copy_in / copy_out /
files.write`).

Crucially for an **anti-cheat** memory eval: **the scorer runs in the host
process, never in the sandbox** (`reference-code/inspect-ai/src/inspect_ai/_eval/task/run.py`
calls scorers after the solver, in-process; the sandbox only ever sees the
solver's tool calls). The held-out answer (`Target`) is loaded host-side
from the dataset and is never copied into the sandbox unless *you* put it
there. So "keep the checker out of the agent's reach" is the default, not
a feature you bolt on.

**What we'd reuse (3-5 pieces):**

1. The **Task/Sample/Solver/Scorer** spine — gives us datasets, epochs,
   `pass@k`/reducers, logging, and the viewer for free.
2. The **`react()` agent** (`agent/_react.py`) — a complete ReAct tool-use
   loop with a `submit()` tool, attempts/retries, and compaction; or the
   **agent bridge** to drive *our own* Seon pod as the solver.
3. The **`SandboxEnvironment` provider contract** + registry
   (`util/_sandbox/registry.py`) — we add one `@sandboxenv("mvm")` class.
4. **Model-graded + deterministic scorers** (`scorer/_model.py`,
   `_match.py`) and **metrics/reducers** (`accuracy()`, `stderr()`,
   `pass_at_k`) — host-side grading, anti-cheat by construction.
5. **inspect-evals** memory/QA/long-horizon tasks (`niah`, `infinite_bench`,
   `scbench`, `assistant_bench`, `gaia`, `babilong`-style) as ready
   **generalization probes**.

**Effort to add an `mvm` provider: M (medium).** The ABC is small and `mvm`
gives us `exec`/`copy_in`/`copy_out`/`files.write` directly. The real work
is (a) the per-sample lifecycle (`mvm` enforces *one Sandbox per process* —
see Gaps §3.4) and (b) `read_file` (mvm has no in-place file *read* RPC
exposed on the SDK `Sandbox`; we synthesize it via `exec(["cat", ...])` or
`copy_out` to a temp). Not L, because there's no protocol to invent — both
sides are subprocess-shelling CLIs.

**Best doc entry points:** Sandboxing
<https://inspect.aisi.org.uk/sandboxing.html>, Scorers
<https://inspect.aisi.org.uk/scorers.html>, Agents
<https://inspect.aisi.org.uk/agents.html>, Solvers
<https://inspect.aisi.org.uk/solvers.html>, Datasets
<https://inspect.aisi.org.uk/datasets.html>. Source anchors:
`util/_sandbox/environment.py` (the ABC), `util/_sandbox/local.py` (the
minimal reference impl), `util/_sandbox/context.py` (per-sample
orchestration), `_eval/task/sandbox.py` (sample→sandbox wiring).

---

## 1. The eval model: Task = dataset + solver + scorer

A `Task` is constructed in
`reference-code/inspect-ai/src/inspect_ai/_eval/task/task.py:67`. The
load-bearing arguments:

```python
class Task:
    def __init__(
        self,
        dataset: Dataset | Sequence[Sample] | None = None,
        setup: Solver | list[Solver] | None = None,
        solver: Solver | Agent | list[Solver] = generate(),
        cleanup: Callable[[TaskState], Awaitable[None]] | None = None,
        scorer: "Scorers" | None = None,
        metrics: ... | None = None,
        sandbox: SandboxEnvironmentType | None = None,
        epochs: int | Epochs | None = None,       # repetitions + reducer (pass@k)
        message_limit / token_limit / time_limit / working_limit ...,
        ...
    )
```

The pipeline per sample is: **dataset → (setup solver) → solver → scorer →
metrics**. You run it with `inspect eval task.py --model ...` or the Python
`eval()` API.

### 1.1 Samples & datasets

A `Sample` (`inspect_ai/dataset`) carries: `input` (the prompt /
`ChatMessage`s), `target` (the held-out answer — a `str` or `list[str]`),
`id`, `metadata`, and the **sandbox-relevant** fields surfaced in
`_eval/task/sandbox.py`:

- `sample.files: dict[str, str]` — files copied **into** the sandbox before
  the solver runs (inline text, `data:` URI, http URL, or a host path; an
  `envname:path` prefix targets a named env). See
  `copy_sandbox_environment_files()` in
  `util/_sandbox/context.py:307`.
- `sample.setup` — a bash script run inside the default sandbox after files
  are copied (`setup_sandbox_environment()`,
  `util/_sandbox/context.py:329`).
- `sample.sandbox` — a per-sample `SandboxEnvironmentSpec` overriding the
  task default (resolution rules in `resolve_sandbox()`,
  `_eval/task/sandbox.py:225`).

Datasets come from `csv_dataset`/`json_dataset`/`hf_dataset`/`MemoryDataset`.
For our memory eval, the **store→retrieve** scenarios are just samples whose
`input` is the question and whose `target` is the fact to recall.

### 1.2 Solvers, agents, tool use

A **Solver** is `async (state: TaskState, generate: Generate) -> TaskState`
(`solver/_solver.py`), registered with `@solver`. Solvers chain (`chain(...)`,
`solver/_chain.py`). The default is `generate()` — a single model call.

An **Agent** is the richer abstraction
(`agent/_agent.py`): `async (state: AgentState) -> AgentState`, registered
with `@agent`. An `Agent` is usable anywhere a `Solver` is expected
(`agent/_as_solver.py`, `as_solver()`), so `Task(solver=react())` works.

The built-in **`react()`** agent (`agent/_react.py:51`) is a full ReAct
tool-use loop:

```python
@agent
def react(
    *, name=None, description=None,
    prompt: str | AgentPrompt | None = AgentPrompt(),
    tools: Sequence[Tool | ToolDef | ToolSource] | None = None,
    model: str | Model | Agent | None = None,
    attempts: int | AgentAttempts = 1,     # retry on wrong submit
    submit: AgentSubmit | bool | None = None,   # the submit() tool
    on_continue: str | AgentContinue | None = None,
    compaction: CompactionStrategy | None = None,
    truncation: ... = "disabled",
    approval: list[ApprovalPolicy] | None = None,
) -> Agent: ...
```

It loops: generate → execute tool calls (`model/_call_tools.py::execute_tools`)
→ urge-continue/submit, until the model calls `submit()`. Tools are plain
async functions decorated with `@tool`; built-ins include `bash()`,
`python()`, `web_browser()` (see how GAIA wires them,
`reference-code/inspect-evals/src/inspect_evals/gaia/gaia.py:100`).

**Where tool code runs:** tools like `bash()`/`python()` call
`sandbox().exec(...)` internally — so the *agent's actions* execute **inside
the sandbox**, while the agent loop, the model calls, and the scorer all run
**in the host process**. This split is the anti-cheat foundation (§5).

For Seon, two integration shapes (see §3.5):
1. Use `react()` with **Seon-as-tools** (`db_transact`, `db_query`,
   `eval_form`) that proxy into our mvm-hosted pod.
2. Use the **agent bridge** (`agent/_bridge`) to make our *own* pod loop the
   solver, treating inspect-ai purely as dataset+scorer+sandbox host.

### 1.3 Scorers & where they run

`@scorer` (`scorer/_scorer.py:129`) registers a function returning a
`Scorer` `Protocol`:

```python
@runtime_checkable
class Scorer(Protocol):
    async def __call__(self, state: TaskState, target: Target) -> Score | None: ...
```

`Score` (`scorer/_metric.py:83`) is `value` (correct/incorrect/float/…) +
optional `answer`, `explanation`, `metadata`. `Target`
(`scorer/_target.py:4`) is a read-only sequence wrapping the dataset's
held-out answer, with a `.text` property.

**Scoring runs in the host process** — confirmed both in the docs
("scoring runs (host process)", Scorers page) and in source: the scorer
receives `TaskState` (which holds the completion + the sandbox handle is
already torn down or irrelevant) and `Target` (loaded host-side from the
dataset). Built-ins: `match()`/`includes()`/`pattern()`
(`scorer/_match.py`, `_pattern.py`), `model_graded_qa()`/`model_graded_fact()`
(`scorer/_model.py`) for open-ended grading via a *grader model* the agent
never sees. Metrics: `accuracy()`, `stderr()`, plus epoch **reducers**
(`scorer/_reducer/`) that give us `pass@k` / `mean` / `max` over `epochs`.

GAIA is the canonical minimal example
(`reference-code/inspect-evals/src/inspect_evals/gaia/scorer.py`):

```python
@scorer(metrics=[accuracy(), stderr()])
def gaia_scorer() -> Scorer:
    async def gaia_scorer(state: TaskState, target: Target) -> Score:
        answer = state.output.completion
        score, explanation = question_scorer(answer, target.text)
        return Score(value=CORRECT if score else INCORRECT,
                     answer=answer, explanation=explanation)
    return gaia_scorer
```

---

## 2. SandboxEnvironment in depth

Source of truth: `reference-code/inspect-ai/src/inspect_ai/util/_sandbox/environment.py`.

### 2.1 The ABC

`class SandboxEnvironment(abc.ABC)` (`environment.py:92`). The **abstract
(must-implement)** surface is small:

| Member | Kind | Signature (abbreviated) |
|---|---|---|
| `exec` | abstract, instance | `async exec(cmd: list[str], input=None, cwd=None, env=None, user=None, timeout=None, timeout_retry=True, concurrency=True) -> ExecResult[str]` (`:104`) |
| `write_file` | abstract, instance | `async write_file(file: str, contents: str \| bytes) -> None` (`:153`) |
| `read_file` | abstract, instance | `async read_file(file: str, text: bool = True) -> str \| bytes` (`:180`) |
| `sample_cleanup` | abstract, **classmethod** | `async sample_cleanup(task_name, config, environments, interrupted) -> None` (`:409`) |

Everything else has a **default impl** and is optional to override:

- `sample_init` (classmethod, `:388`) — returns `dict[str, SandboxEnvironment]`;
  **the first key is the `default` env**. (Has a no-op default returning `{}`,
  but you must override it to return at least one env — `validate_sandbox_environments`
  enforces non-empty, `context.py:370`.)
- `task_init` / `task_init_environment` / `task_cleanup` / `cli_cleanup`
  (classmethods) — shared setup/teardown hooks (`:352`, `:364`, `:428`, `:442`).
- `connection()` (`:212`) — optional terminal/IDE connect info.
- `exec_remote()` (`:227`) — streaming/long-running process handle; **auto-injects
  the inspect "sandbox tools" binary** on first use. Optional — only needed for
  the RPC/service path (§2.4); a basic provider can skip it.
- `config_files()` / `is_docker_compatible()` / `config_deserialize()` —
  config plumbing (`:451`, `:456`, `:461`).
- `default_concurrency()` (classmethod, `:347`) — provider-wide `max_sandboxes`.
- `default_polling_interval()` (`:343`) — service poll cadence.

`exec` returns `ExecResult[str]` (from `inspect_ai/util/_subprocess.py`) with
`success`, `returncode`, `stdout`, `stderr`.

### 2.2 The registry & provider registration

`util/_sandbox/registry.py` — providers self-register with a decorator:

```python
@sandboxenv(name="local")            # registry.py:24
class LocalSandboxEnvironment(SandboxEnvironment): ...
```

`sandboxenv(name)` calls `sandboxenv_register()` → `registry_add(type,
RegistryInfo(type="sandboxenv", name=name))`. Lookup is by **unqualified
name** (`registry_match_sandboxenv`, `:76`) so a provider in any installed
package is reachable as `sandbox="mvm"` from the CLI or `.env`. Unknown
types raise with an install hint (`_SANDBOX_PACKAGES`, `:13`). `docker` and
`local` register at import; `k8s`/`ec2`/`modal`/`daytona`/`proxmox` live in
external packages.

The **minimal reference impl** is `LocalSandboxEnvironment`
(`util/_sandbox/local.py`, ~120 lines): `sample_init` returns
`{"default": LocalSandboxEnvironment()}`, each instance owns a
`tempfile.TemporaryDirectory`, `exec` shells `subprocess(...)`,
`read_file`/`write_file` are plain file IO resolved against that temp dir,
`sample_cleanup` calls `directory.cleanup()`. **This is the template to copy
for `mvm`.**

### 2.3 Per-sample setup / teardown

Orchestration lives in `util/_sandbox/context.py` and the wiring in
`_eval/task/sandbox.py`:

1. `_eval/task/sandbox.py:43 sandboxenv_context(...)` is the
   `@asynccontextmanager` wrapping each sample's execution. It resolves the
   sandbox spec, enforces `max_sandboxes` concurrency (`concurrency(...)`),
   reads `sample.files` and `sample.setup`, then calls
   `init_sandbox_environments_sample(...)`.
2. `context.py:241 init_sandbox_environments_sample(...)`:
   `sample_init()` → `validate_sandbox_environments()` → wraps each env in a
   `SandboxEnvironmentProxy` (for `SandboxEvent` recording) → sets the
   `ContextVar`s so `sandbox()` resolves the default → `copy_sandbox_environment_files()`
   → `setup_sandbox_environment()`. On any exception it calls `sample_cleanup(..., interrupted=True)`.
3. The sample's solver runs (`yield`).
4. `finally:` → `cleanup_sandbox_environments_sample()` →
   `sample_cleanup(..., interrupted)` (shielded from cancellation so cleanup
   completes).

`sandbox(name=None)` (`context.py:41`) is what tools call to get the current
env; with no name (or `"default"`) it returns the first env in the dict.
`sandbox_with(file=...)` (`context.py:71`) finds the env that has a given
file — relevant if we run a multi-env sample (e.g. an "agent" env + a
"memory store" env).

### 2.4 Service / RPC and resource limits

- **Limits** (`util/_sandbox/limits.py`): `exec` stdout/stderr capped at
  **10 MiB** (`INSPECT_SANDBOX_MAX_EXEC_OUTPUT_SIZE`), `read_file` capped at
  **100 MiB** (`INSPECT_SANDBOX_MAX_READ_FILE_SIZE`); both overridable via
  env or the `override_max_*` context managers. Exceeding raises
  `OutputLimitExceededError`. **Timeouts** are per-`exec` (`timeout=` seconds,
  with `timeout_retry` doing up to two shorter retries). **Concurrency** is
  `max_sandboxes` (provider default via `default_concurrency()`, else
  `2 * cpu_count`) enforced in `sandboxenv_context`.
- **Service/RPC** (`util/_sandbox/service.py`, `exec_remote.py`): inspect can
  run a long-lived in-sandbox "sandbox tools" service and stream events
  (`exec_remote()`), used by tools like `web_browser`. **Optional** — a memory
  eval that only needs `bash`/`python`/file IO doesn't touch this. If we want
  the in-sandbox helper binary we'd implement `exec_remote` (the default
  already injects + delegates to `exec_remote_streaming`).

---

## 3. Registering a custom `mvm` provider

### 3.1 The mvm Python SDK surface

`reference-code/mvm/sdks/python/mvm/_sandbox.py`:

```python
class Sandbox:
    @classmethod
    def create(cls, template=None, *, image=None, workload_id=None,
               env=None, ttl=..., resources=None, network=None) -> "Sandbox"   # :965
    def exec(self, *argv, timeout=None, cwd=None, env=None) -> ExecResult       # :1082
    async def aexec(self, *argv, ...) -> ExecResult                             # :1122 (asyncio.to_thread)
    def copy_in(self, host_path: str, guest_path: str) -> None                  # :1138
    def copy_out(self, guest_path: str, host_path: str) -> None                 # :1160
    def forward(self, host_port: int, guest_port: int) -> None                  # :1181
    def kill(self) -> None                                                       # :1204
    # context manager: __enter__/__exit__ + __aenter__/__aexit__               # :1219
    files.write(path, content: bytes|str) -> None                              # _Files, :488
ExecResult: { exit_code: int, stdout: str, stderr: str }                       # :91
```

`exec`/`copy_in`/`copy_out` are **live-mode only** (boot a real microVM via
`mvmctl machine run`); they refuse on prod/sealed templates with
`SandboxDevOnly` (ADR-002 claim 4). For an eval harness we run **live, dev-tier**
images. `aexec` is the awaitable face (runs the blocking `exec` in a worker
thread), which slots straight into inspect's async `exec`.

### 3.2 ABC → mvm method mapping

| inspect ABC method | mvm SDK | Notes |
|---|---|---|
| `exec(cmd, ..., timeout, ...) -> ExecResult[str]` | `await sb.aexec(*cmd, timeout=…, cwd=…, env=…)` | Direct. Translate mvm `ExecResult(exit_code, stdout, stderr)` → inspect `ExecResult[str]` (`success = exit_code==0`). `user=` unsupported by mvm → warn+ignore (local provider does the same, `local.py:60`). |
| `write_file(file, contents)` | `sb.files.write(file, contents)` (or `copy_in` from a host temp) | `files.write` takes `bytes\|str` and streams over `mvmctl fs write`. Need to ensure parent dirs (`exec(["mkdir","-p",dirname])`) — ABC requires auto-mkdir. |
| `read_file(file, text)` | **synthesize** | mvm SDK `Sandbox` has **no `read_file`**. Options: `copy_out(file, host_tmp)` then read host-side; or `exec(["cat", file])` (text) / base64 for bytes. Enforce the 100 MiB cap ourselves (`verify_read_file_size`-style, or `override_max_read_file_size`). |
| `sample_init(...)` → `{"default": env}` | `Sandbox.create(image=...)` | Boot one microVM per sample; wrap in our provider instance. |
| `sample_cleanup(...)` | `sb.kill()` | `mvmctl machine stop`. Shielded by inspect on interrupt. |
| `task_init` / `task_cleanup` | (optional) `mvmctl build`/cache warm | Pre-build/pull the dev image once per task so per-sample boot is fast. |
| `connection()` | (optional) `sb.forward(...)` / vm id | Only if we want IDE/terminal attach; skip for batch evals. |
| `exec_remote()` | (skip v1) | Only if we need streaming long-running procs / the injected tools service. |
| `config_deserialize()` | parse our `mvm` config (image, cpus, mem, ttl, network) | A small pydantic `MvmSandboxConfig`. |

### 3.3 Sketch of the provider

```python
# seon_inspect/mvm_sandbox.py
import asyncio, tempfile, os
import mvm
from typing_extensions import override
from inspect_ai.util._subprocess import ExecResult
from inspect_ai.util._sandbox.environment import (
    SandboxEnvironment, SandboxEnvironmentConfigType,
)
from inspect_ai.util._sandbox.registry import sandboxenv
from pydantic import BaseModel

class MvmSandboxConfig(BaseModel):
    image: str = "python-3.12"
    ttl: int | None = None
    cpus: int | None = None
    memory: int | None = None

@sandboxenv(name="mvm")
class MvmSandboxEnvironment(SandboxEnvironment):

    def __init__(self, sb: "mvm.Sandbox") -> None:
        super().__init__()
        self._sb = sb

    @override
    @classmethod
    async def sample_init(cls, task_name, config, metadata):
        cfg = config if isinstance(config, MvmSandboxConfig) else MvmSandboxConfig()
        # NB: mvm enforces ONE live Sandbox per *process* (see Gaps §3.4).
        sb = await asyncio.to_thread(mvm.Sandbox.create, image=cfg.image, ttl=cfg.ttl)
        return {"default": cls(sb)}

    @override
    @classmethod
    async def sample_cleanup(cls, task_name, config, environments, interrupted):
        for env in environments.values():
            await asyncio.to_thread(env.as_type(MvmSandboxEnvironment)._sb.kill)

    @override
    async def exec(self, cmd, input=None, cwd=None, env=None, user=None,
                   timeout=None, timeout_retry=True, concurrency=True):
        r = await self._sb.aexec(*cmd, timeout=timeout, cwd=cwd, env=env)
        return ExecResult(success=(r.exit_code == 0), returncode=r.exit_code,
                          stdout=r.stdout, stderr=r.stderr)

    @override
    async def write_file(self, file, contents):
        await asyncio.to_thread(self._sb.exec, "mkdir", "-p", os.path.dirname(file) or ".")
        await asyncio.to_thread(self._sb.files.write, file, contents)

    @override
    async def read_file(self, file, text=True):
        if text:
            r = await self._sb.aexec("cat", file)
            if r.exit_code != 0:
                raise FileNotFoundError(file)
            return r.stdout
        # binary: copy_out to a host temp, read bytes
        with tempfile.NamedTemporaryFile(delete=False) as tmp:
            await asyncio.to_thread(self._sb.copy_out, file, tmp.name)
            return open(tmp.name, "rb").read()

    @override
    @classmethod
    def config_deserialize(cls, config): return MvmSandboxConfig(**config)
```

Then a task uses it with `sandbox=("mvm", "mvm.yaml")` or
`sandbox=SandboxEnvironmentSpec("mvm", MvmSandboxConfig(image="seon-pod"))`.

### 3.4 Gaps / friction

- **One-Sandbox-per-process (the big one).** mvm's SDK
  (`_sandbox.py:998` — `_recording is not None or _live_sandbox_active()`)
  **refuses a second concurrent `Sandbox` in the same process**. inspect runs
  many samples **concurrently in one process** (each calls `sample_init`). So
  either (a) set `default_concurrency()` / `--max-sandboxes 1` to serialize —
  simplest, slowest; or (b) bypass the SDK guard and shell `mvmctl machine
  run`/`stop`/`exec` ourselves (the SDK's live transport just shells `mvmctl`
  anyway — we can talk to the CLI directly and get real concurrency). For a
  first cut, **(a)**; for throughput, **(b)**.
- **No SDK `read_file`.** Synthesized via `cat`/`copy_out` (above). Minor; we
  own the size-cap enforcement.
- **No `user=` switching** in mvm exec → warn + ignore (matches `local`).
- **dev-only `exec`.** mvm refuses `exec` on sealed/prod images
  (`SandboxDevOnly`). Eval images must be **dev-tier** (`mvmctl up --dev` /
  `build_mode == "dev"`). This is fine — eval VMs are throwaway.
- **Boot latency.** A microVM per sample is heavier than `local`/docker. Use
  `task_init` to pre-build/cache the image; consider `ttl` + reuse, or mvm
  snapshots (Firecracker tier) later.
- **macOS.** mvm live mode on macOS goes through libkrun/vz builder VMs;
  cheap CI iteration is Linux+KVM (the README's Hetzner path). For local dev,
  the **`local` provider** is the stand-in while the `mvm` provider matures.

### 3.5 How we'd host the evolve-memory loop

The Seon eval shape (per MEMORY/CLAUDE: long-horizon planning + DB-backed
store→retrieve, recall surviving restarts):

- **Dataset:** `MemoryDataset` of samples. A "store-then-retrieve" sample
  pairs an *ingest* turn (facts to learn, with provenance) and a later
  *recall* turn (a question whose `target` is the fact). Multi-turn / cross-
  restart continuity is modeled with **epochs** or multi-message `input`.
- **Sandbox = `mvm` running the Seon pod + wire-server.** `sample.setup`
  boots `wire-server` + `pod` (or we bake them into the dev image); files
  seed the cluster. A `bin/seon restart pod` mid-sample exercises the
  "resume from open plan items / recall across restart" win condition — the
  sandbox persists the datahike store across the restart.
- **Solver:** EITHER inspect `react()` with **Seon-as-tools** (`db_transact`,
  `db_query`, `eval_form`, `todo_add` — thin `@tool`s that `sandbox().exec`
  into the pod's HTTP/REPL), OR the **agent bridge** so our own pod agent
  loop *is* the solver and inspect only supplies dataset+scorer+sandbox.
- **Scorer (host-side):** `model_graded_qa()` for open-ended recall, or
  `match()`/`includes()` for exact facts; `accuracy()` + `stderr()` +
  `pass_at_k` reducer over epochs for the noise-robust `pass^k` the
  benchmark survey recommends. The grader model and the `Target` live
  **only in the host** — the agent in the microVM can never read them.
- **Generalization probe:** alongside the bespoke memory samples, run an
  inspect-evals long-context recall task (`niah`, `infinite_bench`) through
  the *same* solver to check the memory mechanism generalizes, not overfits.

---

## 4. inspect-evals: reusable task families

Layout: `reference-code/inspect-evals/src/inspect_evals/<family>/<family>.py`
(a `@task` fn returning a `Task`), `dataset.py`, `scorer.py`. GAIA is the
clean worked example (dataset + `react` default solver + host scorer +
docker sandbox, `gaia/gaia.py`).

**Ready memory / retrieval / QA / long-horizon probes:**

| Family | Why it's a memory/generalization probe |
|---|---|
| `niah` | **Needle-in-a-haystack** long-context retrieval — generates a haystack, hides a needle, model-graded recall. Directly tests "retrieve a stored fact." |
| `infinite_bench` | Long-context (100k+) retrieval/QA/summarization — stresses recall over very long horizons. |
| `scbench` / `babilong`-style | Multi-turn shared-context / long-doc reasoning. |
| `assistant_bench` | Realistic multi-step web tasks (planning + recall). |
| `gaia` | Long-horizon tool-use agent tasks with a held-out scorer (the template for our Task wiring). |
| `squad`, `boolq`, `triviaqa`-like, `simpleqa`, `drop`, `pubmedqa`, `medqa` | Plain factual QA — clean store→retrieve recall targets with `match`/model-graded scorers. |
| `swe_bench`, `usaco`, `mbpp`, `humaneval`, `bigcodebench`, `ds1000` | **Sandboxed code** tasks — exercise the `mvm` provider's `exec`/file IO under a real grader (tests that build+run-in-sandbox works end to end). |
| `gdm_self_reasoning`, `gdm_self_proliferation`, `theagentcompany`, `tau2` | Long-horizon **agentic** tasks — closest to "planning survives interruption." |
| `persistbench` | (name suggests persistence/long-horizon memory — worth auditing as the closest analog.) |

For a first generalization signal, wire **`niah` + `infinite_bench`** through
our solver; they need no special sandbox and grade host-side.

---

## 5. Scoring & anti-cheat / host-scorer story

The threat in a self-evolving-memory eval: the agent (which controls the
sandbox) could read the answer key, or could write a scorer that always
passes. inspect's architecture defends this **by construction**:

1. **The scorer runs in the host process, not the sandbox.** The `@scorer`
   coroutine receives `TaskState` (model output) + `Target` (the answer) and
   runs in the same Python process as the eval driver. The microVM never
   executes scorer code and never holds the `Target` object. (Docs: Scorers
   page, "scoring runs (host process)"; source: scorers are invoked from
   `_eval/task/run.py` after the solver, with the dataset-loaded `Target`.)
2. **The held-out answer never enters the sandbox unless you put it there.**
   `Target` comes from `Sample.target`, loaded host-side. Only
   `sample.files` / `sample.setup` content is copied in
   (`copy_sandbox_environment_files`), and *we* author those — so we simply
   never stage the answer.
3. **Model-graded scoring uses a separate grader model** (`model_graded_qa`,
   `scorer/_model.py`) the agent has no handle to. The grading prompt +
   rubric live host-side.
4. **No "encrypted answer in the sandbox" needed.** Because the answer is
   never in the sandbox, there's nothing to encrypt. (If a task *must* ship a
   verifier into the sandbox — e.g. unit tests for codegen — the standard
   pattern is: the agent's exec produces output; the **host scorer** reads
   it back via `read_file`/`exec` and compares against the host-held
   expectation. The checker logic that *decides pass/fail* stays in the host
   `@scorer`.)
5. **`pass@k` / reducers** over `epochs` (`scorer/_reducer/`) give the
   noise-robust `pass^k` the agentic-benchmark survey
   (`reference_agentic_benchmarks_survey`) calls for — cheap insurance
   against single-run luck.

**Net:** to "keep the checker in the host scorer, never in the sandbox," we
do nothing special — we just keep grading logic + `Target` + grader model in
the `@scorer`, and never list the answer in `sample.files`/`setup`. This is
strictly stronger than Seon's current in-process gym (where "agents never
read the harness" is a convention); here it's a process boundary.

---

## 6. Where to read more (annotated)

### Canonical docs (inspect.aisi.org.uk)

- **Sandboxing** — <https://inspect.aisi.org.uk/sandboxing.html> —
  provider config, `sandboxenv` decorator, lifecycle hooks, limits, the
  `sandbox()` accessor. **Start here for the mvm provider.**
- **Scorers** — <https://inspect.aisi.org.uk/scorers.html> — `@scorer`,
  `Score`/`Target`, host-process scoring, model-graded scorers, metrics.
  **Anti-cheat story.**
- **Agents** — <https://inspect.aisi.org.uk/agents.html> — `react()`,
  `AgentState`, tools, `as_solver`/`as_tool`/`handoff`, the **agent bridge**
  (drive Seon's own loop).
- **Solvers** — <https://inspect.aisi.org.uk/solvers.html> — `@solver`,
  `TaskState`, `generate`, chaining.
- **Datasets** — <https://inspect.aisi.org.uk/datasets.html> — `Sample`
  (`files`/`setup`/`sandbox`/`target`), `hf_dataset`/`csv_dataset`/`MemoryDataset`.
- (Also useful: Compaction <https://inspect.aisi.org.uk/compaction.html>,
  Evals index <https://inspect.aisi.org.uk/evals/>.)

### Key source files (vendored)

- `reference-code/inspect-ai/src/inspect_ai/util/_sandbox/environment.py` —
  the `SandboxEnvironment` ABC (`:92`), abstract methods at `:104/:153/:180`,
  classmethod lifecycle `:347-:478`, `SandboxEnvironmentSpec` `:495`.
- `reference-code/inspect-ai/src/inspect_ai/util/_sandbox/local.py` —
  **minimal reference provider** to copy for mvm (~120 lines).
- `reference-code/inspect-ai/src/inspect_ai/util/_sandbox/registry.py` —
  `@sandboxenv` decorator + lookup-by-name (`:24`, `:52`, `:76`).
- `reference-code/inspect-ai/src/inspect_ai/util/_sandbox/context.py` —
  `sandbox()` accessor (`:41`), per-sample init/cleanup/file-copy/setup
  (`:241`, `:285`, `:307`, `:329`).
- `reference-code/inspect-ai/src/inspect_ai/util/_sandbox/limits.py` —
  exec/read caps + override context managers.
- `reference-code/inspect-ai/src/inspect_ai/_eval/task/sandbox.py` —
  `sandboxenv_context` (`:43`), sample→sandbox resolution (`:225`).
- `reference-code/inspect-ai/src/inspect_ai/util/_sandbox/docker/docker.py` —
  full real provider (`@sandboxenv("docker")`, `:57`) — reference for
  `task_init`/concurrency/exec.
- `reference-code/inspect-ai/src/inspect_ai/_eval/task/task.py` — the `Task`
  constructor (`:67`).
- `reference-code/inspect-ai/src/inspect_ai/scorer/_scorer.py` (`@scorer`,
  `Scorer` protocol, `:33`/`:129`), `_target.py`, `_metric.py` (`Score`,
  `:83`), `_model.py` (model-graded), `_match.py`.
- `reference-code/inspect-ai/src/inspect_ai/agent/_react.py` — `react()`
  (`:51`); `agent/_as_solver.py`, `agent/_bridge/`.
- `reference-code/inspect-evals/src/inspect_evals/gaia/{gaia,scorer,dataset}.py`
  — clean Task=dataset+react+host-scorer+sandbox worked example.
- `reference-code/inspect-evals/src/inspect_evals/niah/`,
  `.../infinite_bench/`, `.../persistbench/` — memory/long-context probes.

### mvm side

- `reference-code/mvm/sdks/python/mvm/_sandbox.py` — `Sandbox.create`
  (`:965`), `exec`/`aexec` (`:1082`/`:1122`), `copy_in`/`copy_out`
  (`:1138`/`:1160`), `files.write` (`:488`), `ExecResult` (`:91`),
  one-sandbox guard (`:998`).
- `reference-code/mvm/README.md` + `reference-code/mvm/CLAUDE.md` — backends,
  dev-vs-prod (sealed) posture, live-mode requirements, security claims.

---

## Appendix: effort & reuse call

- **Reuse (3-5):** Task/Sample/Solver/Scorer spine · `react()` agent (or the
  bridge) · the `SandboxEnvironment` provider contract + registry ·
  model-graded/deterministic scorers + reducers · inspect-evals
  `niah`/`infinite_bench` (+ codegen-in-sandbox families) as probes.
- **mvm provider effort: M.** Small ABC, mvm gives `exec`/`copy_in`/`copy_out`/
  `files.write` directly; the only real engineering is the
  one-Sandbox-per-process concurrency workaround (serialize, or shell
  `mvmctl` directly for parallelism) and synthesizing `read_file`. No
  protocol to invent.
- **Best entry points:** Sandboxing + Scorers docs; then `local.py` (copy
  it) and `gaia/gaia.py` (copy the Task wiring).
