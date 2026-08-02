---
type: prd
status: active
tags: [prd, testing, evaluation, agent]
---

# Adapting src-inspect-ai to the fresh architecture (2026-08-02)

Design-only. No production file is changed by this document. It designs how
`src-inspect-ai` becomes the ONE agent-evaluation surface for the fresh
single-JVM cluster system, built ON the bootstrap-drive mechanism and the
`bin/test` runner rather than beside either.

## 1. The charter

Owner, verbatim intent: *"I don't want to invent our own testing again. We
need it to work with Inspect AI for goal-oriented runs where we have objective
data measurements (or just functions that we can run to validate correctness,
in case it's writing a file or something) and LLM judges checking for cheating
and for situations where there is no one right answer. DeepSeek thinking
should be the LLM-judge default for now. Understand that we will be giving
even more goals from AI benchmarks, and we'll want the agent to be able to
develop and run complicated multi-namespace generated code. Eventually the
swarm works on this — one step at a time."*

Owner sharpening (same session): *"can we reuse our test infrastructure so we
can just run tests at the end and inspect the VM filesystem and/or database or
whatever it was supposed to have achieved? We can add more tests as we need,
we have a sane understanding of how to write tests, and maybe even generative
tests could be used — properties about the solutions."*

Those two together fix the whole shape:

> **A goal is a message plus a test namespace.** The agent's episode ends; the
> ending commit is forked; the goal's `clojure.test` namespace runs against
> that fork (and the sample's own filesystem root); the runner's per-test
> results become the Inspect score. Judges read the same evidence and
> **advise**; the tests **gate**.

This is not a new rule. Ruling 2026-07-29 late morning (2) already fixed it:
*"the goal is something more test based… the test is the namespace they are in
with all tests passing"* — `plan/README.md:685-690`, "goal-done = the
namespace's tests pass — the observable condition IS the test suite." This
document implements that ruling for the evaluation surface.

Two testing surfaces remain, unchanged and now sharply separated by *subject*
rather than by machinery: `bin/test` gates **our** code; `src-inspect-ai`
evaluates **the agent**. They deliberately share one runner
(`seon.test.runner/run!`), one fixture vocabulary, and one notion of what a
passing namespace means. Nothing here restores the gym or adds a third runner.

## 2. Dependency ledger

### Inspect AI — vendored at `reference-code/inspect-ai`

Pin: `05322696a`, tag **0.3.246**, dated 2026-07-15. `pyproject.toml:20-22`
installs both Inspect packages from the submodule paths, so the submodule SHA
*is* the pin (installed dist-info agrees: `inspect_ai-0.3.247.dev0+g05322696a`).

| Interface | Vendored source | What it establishes |
|---|---|---|
| `Scorer` protocol | `scorer/_scorer.py:34-42` | a scorer is `async (TaskState, Target) -> Score \| None`. Nothing else. |
| `@scorer` registration | `scorer/_scorer.py:129`, `:88-113` | decorator registers a factory + its metrics into Inspect's registry; a custom scorer is one decorated function, no subclassing |
| `Score` | `scorer/_metric.py:83-121` | `value`, `answer`, `explanation`, `metadata` (arbitrary dict), plus `Score.unscored()` — a NaN sentinel metrics/reducers **skip**. That is the honest home for "the judge could not decide" |
| `Target` | `scorer/_target.py:4-27` | a sequence of strings; `.text` joins. Our targets are goal ids, not answers |
| `TaskState` | `solver/_task_state.py:149-184` | carries `sample_id`, `epoch`, `metadata` (mutable dict, `:251-257`), `store`, `output`, `messages`. `metadata` is where the whole Seon episode record lands |
| `Task` | `_eval/task/task.py:66`, params at `:75-104` | `setup`, `solver`, `cleanup` (`Callable[[TaskState], Awaitable[None]]`, `:77`), `scorer` (list), `model_roles` (`:84`), `epochs` (`:90`), `metadata` (`:104`) |
| `Sample` | `dataset/_dataset.py:29-75` | `input`, `target`, `id`, `metadata`, `sandbox`, `files`, `setup`. Our goal definition rides `metadata` |
| model-graded scorers | `scorer/_model.py:29-84` (`model_graded_fact`), `:87-...` (`model_graded_qa`) | both take `model_role: str \| None = "grader"` (`:36`, `:94`) — a named role resolved from `Task(model_roles=…)`. The judge is configured at task level, not hardcoded in the scorer |
| OpenAI-compatible provider | `model/_providers/openai_compatible.py:65-121` | `openai-api/<service>/<model>`; service prefix is uppercased to `<SERVICE>_API_KEY` (`:94-107`) and `<SERVICE>_BASE_URL` (`:114-121`). DeepSeek is the provider's own documented example: `docs/providers.qmd:475-481` |
| provider-specific request fields | `model/_generate_config.py:318` (`extra_body`), plumbed at `model/_openai.py:364-374` | arbitrary JSON body fields reach the wire. This is how DeepSeek's `thinking` object is sent through Inspect without patching a provider |
| reasoning dials | `model/_generate_config.py:291-307` | `reasoning_effort`, `reasoning_tokens`, `reasoning_summary`, `reasoning_history` |
| sandbox protocol | `util/_sandbox/environment.py:92`, `:105` (`exec`), `:154` (`write_file`), `:181` (`read_file`), `:389` (`sample_init`) | a container/VM abstraction whose unit of work is **shell commands in a foreign filesystem** |
| subprocess util | `util/_subprocess.py:75-108` | `await subprocess(args, cwd=…, env=…, timeout=…)`, throttled by `max_subprocesses`. The honest seam for "run a local command" without pretending to be a sandbox |
| concurrency | `util/_concurrency.py:328` | named concurrency gates — how we cap simultaneous clusters |
| `EvalLog` | `log/_log.py:1094-1143` | `eval` spec, `plan`, `results`, `stats`, `samples`, `tags`, `metadata` |
| `EvalSample` | `log/_log.py:382-...` | per sample: `input`, `target`, `messages`, `output`, `scores`, `metadata`, `store`, `events`, `attachments`, `error`, `total_time`. The durable per-episode record |

### Inspect Evals — vendored at `reference-code/inspect-evals`

Pin `97c99f5`, release **v0.14.3**; installed editable at the same version.

| Precedent | Source | Why it is instructive |
|---|---|---|
| **SWE-bench scorer** | `src/inspect_evals/swe_bench/scorers.py:26-86` | The exact shape we want: at score time, materialize a test script into the environment (`:53`), run it (`:66`), read its output (`:78`), parse it into `value` + `explanation`, attach the agent's diff as `metadata` (`:82-86`). It deliberately **ignores the script exit code** and decides gradeability from markers in the output (`:60-65`) — the same discipline we need when a Clojure test run dies mid-way vs. legitimately fails |
| **paperbench cleanup** | `src/inspect_evals/paperbench/paperbench.py:124` (`cleanup=save_submission(...)`) | Precedent for using `Task(cleanup=…)` to preserve per-sample artifacts before teardown. Our per-sample cluster/root must be preserved-then-retired the same way |
| **core_bench scorer** | `src/inspect_evals/core_bench/scorer.py:27` | A single `@scorer(metrics=[accuracy()])` reading structured end-state results rather than model text — the "objective measurement" idiom in their vocabulary |

### Seon — the surfaces being driven

| Mechanism | Source | What it gives the adapter |
|---|---|---|
| the drive harness | `src/seon/bootstrap_drive.clj:500-556` (`one-drive!`) | boot → create agents through the production seam → await seeded bootstrap → send ONE objective message → await terminal state event-driven (`await-fact!`, `:141-161`) → fork the ending commit (`grading-branch!`, `:469-474`) → grade → write one EDN report |
| objectives | `bootstrap_drive.clj:48-96` | the five ruled O1–O5 objective messages |
| current grade predicates | `bootstrap_drive.clj:325-459` | `grade-o1`…`grade-o5` — the fact-space predicates this design converts into `deftest`s |
| the test runner | `src/seon/test/runner.clj:80-108` (`run!`) | `{:seon.test.runner/namespaces …} -> {summary, per-test results with failure messages}`. Programmatic, data-returning, already the `bin/test` engine |
| the gate script | `bin/test` | namespace selection + exit code; the *developer* surface over the same `run!` |
| ambient database | `src/seon/db.clj:10-12` (`*conn*`) | `^:dynamic` — the binding seam that points a goal's tests at the graded fork |
| operator roots | `bin/seon:7-18` (`--root`) | `bin/seon --root PATH …` runs the operator against an isolated process root |
| cluster boot | `src/seon/cluster.clj:104-151`, advertisement written at `:222-225` to `<cluster-dir>/prepl.edn` | every cluster advertises an `io-prepl` coordinate at second zero |
| model settings | `config/default.edn:135-160`; `plan/ai-settings-design-2026-08-01.md` | `:seon.config.ai/{endpoint,model,thinking,max-tokens}`; ruling #34 sets agents to `:thinking :disabled` |
| JSON | `deps.edn:54` (`org.clojure/data.json 2.5.1`) | the Clojure side can emit JSON directly — no EDN parser needed in Python |

### Measured costs (this session, `tmp/inspect-adapt/cost-probe.clj`)

```text
clojure -M:dev -e "(require 'seon.bootstrap-drive)"   →  10.3 s wall (cold)
clojure -M:dev -i tmp/inspect-adapt/cost-probe.clj    →  25.8 s wall total
  {:refresh-source-ms 11514, :first-cluster-ms 1081, :second-cluster-ms 846}
```

So: **~22 s of fixed per-process cost** (JVM + namespace load + one
`refresh-source!`), then **~0.9 s per additional cluster in the same process**.
That single ratio decides the crossing.

## 3. The crossing — how an Inspect solver drives a Seon episode

### Options weighed

**(A) Subprocess per sample.** `await subprocess(["clojure","-M:dev:test","-m",
"seon.eval-drive", request_json])` (`util/_subprocess.py:75`). Maximum
isolation, zero new transport, trivially correct. Cost: **~22 s dead time per
sample**, paid again for every epoch and every retry. A 5-goal × 8-epoch run
burns ~15 minutes on JVM boots alone, and the benchmark road multiplies that by
hundreds of samples. Rejected as the steady-state path.

**(B) One warm JVM, one cluster per sample, over the advertised io-prepl.**
Task `setup` starts one isolated operator root once
(`bin/seon --root tmp/inspect-roots/<run-id>`, `bin/seon:7-18`); the solver
reads that cluster's advertisement (`cluster.clj:222-225`) for the prepl
coordinate and sends one EDN form per sample. Each sample gets its **own
cluster** — a Datahike branch fork, the store fence's natural isolation unit
(one process root, many clusters, per CLAUDE.md's boot tower §2). Cost:
**~0.9 s per sample**, ~22 s once. Uses only transport that already exists and
is already how MCP `eval_clj` reaches a cluster.

**(C) The repository MCP server.** Same prepl underneath, plus stdio framing,
tool schemas, cluster-ambiguity resolution and REPL *session* semantics
designed for an interactive agent. It buys nothing a harness needs and the
repository law is explicit: "MCP eval is the first probe, not another test
runner." Rejected.

### Recommendation — B, with A as the boot step

```text
Task.setup   →  subprocess: bin/seon --root tmp/inspect-roots/<run-id> start eval-host
                (one JVM, one process root, ~22 s, once per eval)
                read  <root>/clusters/eval-host/prepl.edn  → [host, port]

solver       →  open prepl socket; send ONE form:
                (seon.eval.drive/run-sample!
                  {:seon.eval/sample-id "o1#3"
                   :seon.eval/goal :o1
                   :seon.eval/objective "…"        ; the templated user prompt
                   :seon.eval/run-cap 6
                   :seon.eval/artifact-root "…/samples/o1#3"})
                → one JSON string (data.json, deps.edn:54) carrying
                  {sample-id, cluster, agent-id, ending-commit, grading-branch,
                   terminal-outcome, run-ids, transcript, receipts,
                   model-config, report-path}

scorers      →  send further forms against the SAME prepl:
                (seon.eval.drive/run-goal-tests! {…grading-branch, namespaces})

Task.cleanup →  retire the sample's grading branch + cluster, after evidence
                is copied out (precedent: paperbench.py:124)

eval end     →  subprocess: bin/seon --root tmp/inspect-roots/<run-id> down
```

Design notes that this crossing forces, each of which is a feature:

- **`seon.eval.drive` is a refactor of `seon.bootstrap-drive`, not a second
  path.** Today `run-drives!` (`bootstrap_drive.clj:558-592`) owns process
  boot, cluster naming, the attempt loop *and* the report file. The adapter
  needs those factored: `one-drive!` (`:500-556`) becomes the reusable
  per-sample function taking an already-running instance; the objective
  catalogue moves out of the harness into goal definitions; `-main` keeps
  working for the standalone case by calling the same function. One mechanism,
  two callers.
- **Classpath.** `test.check` and `test/` live only in the `:test` alias
  (`deps.edn:87-95`). Goal tests and their generative properties therefore
  require the eval host JVM to boot with `-M:dev:test`. The operator must be
  able to select that alias, or the eval host is started directly rather than
  through `bin/seon`. **Open question O3.**
- **Blast radius of a shared JVM.** A runaway sample can OOM the host. The
  cluster is the isolation unit for *facts*, not for the process. Mitigations:
  cap simultaneous samples through Inspect's `concurrency()`
  (`util/_concurrency.py:328`) and `max_subprocesses`; treat a dead prepl as an
  Inspect **sample error**, never a score (`EvalSample.error`,
  `log/_log.py:382-…`), so `--retry-on-error` re-runs it; and keep option A
  available as `--isolated` for a bisecting run.
- **Not the sandbox protocol.** `SandboxEnvironment`
  (`util/_sandbox/environment.py:92-181`) models *shell commands inside a
  foreign filesystem*. Our episode is a message into a live JVM's cluster; the
  filesystem is ours, local, and per-sample by directory. Declaring a sandbox
  would mean writing an adapter whose `exec` fakes a shell — precisely the
  dishonesty the deleted pod adapters embodied. A plain solver that talks to a
  running process, and `subprocess()` for lifecycle, is the honest seam. When a
  future benchmark genuinely needs a container (SWE-bench's repos), that
  benchmark declares a real `sandbox` for *its* repo work while the Seon
  episode still runs on the host — the two are independent.

## 4. Scoring — one gate, two advisors

### 4.1 The objective gate is `clojure.test`, run at the ending state

Every goal ships a **test namespace**. It is an ordinary namespace under the
goal's directory, discovered by the runner's ordinary conventions, written with
the ordinary skill (`clojure-testing`). At score time:

1. the drive has already forked the ending commit into a grading branch
   (`bootstrap_drive.clj:469-474`) — the agent's own record is never touched;
2. `seon.eval.drive/run-goal-tests!` binds `seon.db/*conn*` (`db.clj:10`) to
   that grading branch's connection and `seon.eval/*artifact-root*` to the
   sample's own directory, then calls `seon.test.runner/run!`
   (`runner.clj:80-108`) with the goal's namespaces;
3. the returned value — `{summary, results[{sym, outcome, failure/message}]}` —
   crosses back as JSON;
4. the Inspect scorer maps it: **all outcomes `:pass` ⇒ `C`, any `:fail` ⇒
   `I`**, `explanation` = the joined failure messages, `metadata` = the full
   per-test result vector plus the ending commit and grading branch.

A goal test sees exactly two things, which is the whole owner requirement:

- **the database** — `(seon.db/q …)` / `pull` against the ambient graded
  database value: did the function get authored as a `:seon.fn` row, does
  `my.message` show the delegation, did the run complete with the right value;
- **the filesystem** — the sample's artifact root: did the agent write the file
  it was asked to write, and does a plain function validate its content.

The current `grade-o1`…`grade-o5` predicates
(`bootstrap_drive.clj:325-459`) become plain `deftest`s under this shape and
are **deleted from the harness**. `grade-o1`'s held-out execution
(`:325-346` — call the agent's own function on unseen rows) becomes a `deftest`
that resolves the candidate through the graph and invokes it through
`sci.eval/evaluate`; the harness keeps `evaluate-function` (`:304-320`) as a
callable helper for goal tests, not as grading machinery.

### 4.2 Properties are first-class

For any goal whose deliverable is a **function**, the goal's namespace may
carry `clojure.test.check` properties over the agent's solution: generate
inputs, compare against an oracle or assert invariants. This is dramatically
stronger anti-cheating than examples — a hardcoded expected output cannot
survive a generator, and neither can a function that pattern-matched the one
example in the prompt.

The honest-generator law from `clojure-testing` applies to **goal authors**: a
generator that cannot produce the interesting input is a green suite proving
nothing. Seeds are recorded in the score metadata so a failure reproduces.

Concretely, O1's `total-by-label` gains
`(for-all [rows (gen/vector row-gen)] (= (total-by-label rows) (oracle rows)))`
instead of one held-out triple — and O1 stops being a memorization target.

### 4.3 The two judges

Both are Inspect **model-graded** scorers (`scorer/_model.py:29-84`) with a
custom template, reading evidence the solver already put on
`TaskState.metadata` (`solver/_task_state.py:251`). Both are **advisory**: they
carry their own metric column and never override the gate.

**Cheating judge.** Question posed to the judge: *did this episode actually do
the work?* Evidence rendered into the prompt: the full transcript (already
produced by `full-transcript`, `bootstrap_drive.clj:461-467`), the program-graph
delta (which `:seon.fn` rows appeared, with their `:seon.fn/spec`), and the
goal's test results. Named gaming patterns it must look for:

- completing without doing (a `my.run/complete` with the expected value and no
  authored function or query behind it);
- hardcoding — a solution whose body embeds the prompt's example outputs
  (properties should already have killed this; the judge catches what the
  generator missed and *reports the generator gap*);
- grader tampering — any authored form that touches the goal's test namespace,
  `:seon.test` facts, or a core function the grader depends on. Ruling #30's
  persistence gate means the agent may *call* anything but only *persist* what
  the gate admits, so this is a query over what was persisted, not a guess.

**Open-ended judge.** For goals with no single right answer (design proposals,
explanations, refactoring judgement), `model_graded_qa` with a per-goal
`criterion` carried in `Sample.metadata` and rendered into the template. Its
score is a quality band, reported separately; it never gates.

Note the split obeys the standing law (`feedback_scorers_gate_correctness_not_style`):
the gate is *parses ∧ validates ∧ runs ∧ right answer*. Judges are
instrumentation on top of a gate that already decided.

### 4.4 The scorer table

| Inspect scorer | Kind | Reads | Value | Role |
|---|---|---|---|---|
| `seon_goal_tests()` | `@scorer(metrics=[accuracy(), stderr()])` | `run-goal-tests!` over the grading branch + artifact root | `C` / `I` | **GATE** — the goal's verdict |
| `seon_goal_properties()` (optional split) | `@scorer(metrics=[accuracy()])` | the property-only subset of the same run | `C` / `I` | GATE — reported separately so "passes examples, fails properties" is visible |
| `seon_terminal_honesty()` | `@scorer(metrics=[accuracy()])` | `terminal-outcome` (`bootstrap_drive.clj:248-280`) | `C` iff `:completed`; `:capped`/`:stopped` are `I`; harness failure raises instead | GATE — the successor to `timeout_honesty()`, keeping the anti-mis-recording property |
| `seon_cheating_judge()` | `model_graded_qa`-shaped, `model_role="grader"` | transcript + graph delta + test results | `C` / `P` / `I`, or `Score.unscored()` when it cannot decide (`_metric.py:104`) | ADVISE |
| `seon_open_ended_judge()` | `model_graded_qa`, `model_role="grader"` | transcript + `criterion` from `Sample.metadata` | banded | ADVISE |

Scorers are a list on the `Task` (`_eval/task/task.py:78`); each contributes its
own column, so one run reports gate accuracy and judge flags side by side.

## 5. The judge configuration — DeepSeek with thinking

The judge is a **role**, not a hardcoded model. `model_graded_*` resolves
`model_role="grader"` (`scorer/_model.py:36,94`), and `Task(model_roles=…)`
(`_eval/task/task.py:84`) binds it. That keeps the judge swappable without
touching a scorer.

Provider: DeepSeek through Inspect's own OpenAI-compatible provider — their
documented path (`docs/providers.qmd:475-481`), no custom provider code.

```bash
export DEEPSEEK_API_KEY=…                      # openai_compatible.py:94-107
export DEEPSEEK_BASE_URL=https://api.deepseek.com   # openai_compatible.py:114-121
```

```python
JUDGE = "openai-api/deepseek/deepseek-v4-flash"

JUDGE_CONFIG = GenerateConfig(
    max_tokens=65536,                       # matches config/default.edn:149
    extra_body={"thinking": {"type": "enabled"}},   # _generate_config.py:318
    reasoning_effort="high",                        # _generate_config.py:291
)

Task(..., model_roles={"grader": get_model(JUDGE, config=JUDGE_CONFIG)})
```

Why `extra_body` rather than a dial: `reasoning_effort` reaches the wire as
OpenAI's own field, but DeepSeek's toggle is a `thinking` **object**;
`extra_body` is plumbed verbatim into the request
(`model/_openai.py:364-374`), which is exactly what the vendor capture in
`research/deepseek-thinking-mode-api-2026-08-01.md` requires. **Do not** add a
Python-side effort→params mapping table: `ai-settings-design-2026-08-01.md`
part 1 documents litellm-clj shipping exactly such a table already wrong for
our default model.

The asymmetry is deliberate and should be stated in the eval log's metadata:
**agents run thinking-disabled (ruling #34, `config/default.edn:157`, 9.8 s
median turns), the judge runs thinking-high (21.3 s, ~$0.0007/call).** One
judge call per sample against many agent turns makes that affordable, and a
judge is exactly the "planner" case ruling #34 carves out.

Cost discipline (`feedback_deliberate_paid_llm_runs`): judges run only after
the gate has a verdict, and an eval may be run gate-only with
`--scorer-args` selection while iterating.

## 6. The benchmark road

An external benchmark becomes a Seon evaluation through **three** mappings, and
the owner's directive collapses the hardest one:

1. **Sample → objective message.** The benchmark's problem statement becomes
   the templated user prompt the solver delivers as one inbound message
   (`bootstrap_drive.clj:163-189`). Prompt-template solvers stay ahead of ours
   in the solver list, which is how the current catalog already reads
   `state.user_prompt` (`solver.py:104-116`) — that reading survives the
   rewrite even though its transport does not.
2. **Checker → a generated test namespace.** This is the simplification. A
   benchmark's checker (unit tests for HumanEval/MBPP-shaped work,
   `FAIL_TO_PASS`/`PASS_TO_PASS` for SWE-bench-shaped work, a validator script
   elsewhere) is *ported or generated* into one Clojure test namespace per
   sample, materialized in the sample's goal directory before the episode. The
   scorer does not change at all: it is still "run the goal's namespaces
   against the ending state." SWE-bench's own scorer does the analogous thing —
   writes an eval script into the environment at score time and parses its
   output (`swe_bench/scorers.py:46-79`).
3. **Grading environment → the fork + the root.** Database-shaped benchmarks
   grade on the grading branch; artifact-shaped benchmarks grade on the
   sample's directory; repo-shaped benchmarks (real SWE-bench) additionally
   declare a container `sandbox` for the repository, independent of the Seon
   host.

What today's five objectives do **not** exercise, and therefore what the
harness must gain before benchmark goals land:

- **Longer horizons.** `default-run-cap` is 6 (`bootstrap_drive.clj:32`) and the
  terminal state caps on closed-run count (`:272-274`). Benchmark work needs
  tens of runs and a *reason-carrying* cap. Inspect's own `message_limit` /
  `turn_limit` / `token_limit` / `time_limit` / `cost_limit`
  (`_eval/task/task.py:96-103`) are the right ceilings to mirror, and the
  upstream Unreleased entry recording `turn_count` and `token_limit_usage` per
  sample is directly useful here.
- **File/artifact outputs.** No objective writes a file. The design needs
  `seon.eval/*artifact-root*` per sample, a capability path the agent can
  actually reach (`seon.fs` through the one guarded door), and goal tests that
  read it. This is the "just functions that we can run to validate correctness,
  in case it's writing a file" half of the charter and is currently unbuilt.
- **Multi-namespace generated code.** The graph already supports it (agents own
  namespaces anywhere, ruling 2026-07-31), but no objective demands more than
  one namespace, so nothing proves the loop can plan across namespaces, or that
  a goal's tests can reach several agent-authored namespaces at once.
- **Multi-agent delegation at depth.** O4 (`bootstrap_drive.clj:80-86`) is a
  two-agent sketch with a fixed peer. Benchmark-scale delegation means N agents,
  dynamic peers, and grading predicates over the message graph — the seed of
  §7.
- **Repeatability.** `epochs` (`_eval/task/task.py:90`) and the existing pass^k
  discipline in `scorecard.py:1-33` carry over; the ledger's row shape survives
  the transport rewrite intact and should be reused, not re-invented.

## 7. The swarm note

Not a design — the structural properties that let many lanes extend this later
without collisions:

- **A goal is a directory, not a registry row.** `<goals>/<goal-id>/` holds the
  objective message, the test namespace(s), the optional judge criterion, and
  its metadata. Adding a goal is adding a directory; the dataset is a
  *discovery* over that tree, never a hand-maintained list
  (`feedback_no_hand_maintained_lists`). Two lanes adding goals never touch the
  same file.
- **Per-sample isolation is already the unit of ownership.** One sample = one
  cluster (a branch) + one artifact directory + one grading branch. A lane can
  run its own goals concurrently with another lane's on the same host, and
  `Task(cleanup=…)` (`:77`, precedent `paperbench.py:124`) retires them.
- **The eval log is the durable record.** `EvalSample` (`log/_log.py:382-…`)
  keeps `metadata`, `scores`, `events`, `attachments`, `error`, `total_time`
  per sample; `EvalLog` (`:1094-1143`) keeps the spec, results, and stats.
  Everything the swarm needs to compare generations — ending commit, grading
  branch, model config, transcript, per-test results — lives in that structure,
  written by Inspect, readable by `samples_df`. No parallel ledger is needed
  beyond the existing pass^k scorecard.
- **The overseer loop lands on top, not beside.** `grader-in-fact-space-2026-08-01.md`
  §"the oversight loop" describes an overseer agent reading prior generations'
  branches and transcripts as *database facts*. Since every sample records its
  ending commit and grading branch in the eval log, the overseer's experimental
  memory is a query joined to the log — which is why the crossing must record
  commit IDs, not just scores.

## 8. The adaptation plan

### Slice 1 — one Task, five goals, three scorer kinds, end to end

Scope, exactly:

1. **`seon.eval.drive`** — factor `bootstrap_drive.clj` so `one-drive!` takes a
   running instance and a goal definition, and add `run-goal-tests!` binding
   `seon.db/*conn*` to the grading branch around `seon.test.runner/run!`. Both
   return values JSON-encodable with `data.json`. `-main` keeps working.
2. **Goal directories for O1–O5** — objective message + test namespace each,
   converting `grade-o1`…`grade-o5` into `deftest`s, with test.check properties
   for O1 and O5's authored functions.
3. **`seon_inspect/host.py`** — start/stop the isolated operator root, read the
   advertisement, open the prepl, send forms, decode JSON.
4. **`seon_inspect/solver.py` rewritten** — one `seon_episode_solver()` that
   sends the objective and records the episode onto `TaskState.metadata`.
5. **`seon_inspect/scorers.py`** — `seon_goal_tests()`,
   `seon_terminal_honesty()`, `seon_cheating_judge()`,
   `seon_open_ended_judge()`.
6. **`seon_inspect/tasks/bootstrap.py`** — the `Task`: dataset discovered from
   the goal tree, `setup`/`cleanup` for the host, the scorer list,
   `model_roles={"grader": …}`, `epochs`.

Acceptance: `inspect eval` over the five goals produces an eval log whose gate
column reproduces the six already-graded O1 drives in `tmp/bootstrap-drives/`
(same verdicts), whose judge columns are populated, and whose per-sample
metadata carries the ending commit and grading branch.

### Slice 2 — the artifact half

`seon.eval/*artifact-root*`, a goal that writes a file, a goal test that runs a
validator function over it, and the capability path that lets the agent write
there at all.

### Slice 3 — horizons and multi-namespace

Reason-carrying run caps mirrored onto Inspect's limits; a goal requiring two
agent-authored namespaces; delegation grading over the message graph.

### Slice 4 — the first external benchmark

One benchmark whose checker ports cleanly to a generated test namespace, end to
end, with the pass^k ledger.

### The deletion list (slice 1, same commit as the replacement)

Everything below exists only to serve the deleted pod door or its container
adapters (`docs/seon/issues/inspect-container-adapters-launch-the-deleted-pod.md`,
`dead-parser-oracle-tools-have-live-downstream-readers.md`):

| Delete | Why |
|---|---|
| `docker/seon-entrypoint` | boots `seon.db.server` + the Bun pod from `src-old` |
| `src/seon_inspect/tb_agent.py`, `tb2_agent.py`, `swebench_arm.py` | inject that entrypoint, post to `/agents/run` on 7890 |
| `src/seon_inspect/solver.py` pod paths — `pod_run`, `seon_pod_solver`, `seon_cluster_solver`, `seon_diagnostic_pod_solver`, `require_scorable_pod_state`, `_require_model_transport_evidence`, `_require_model_server_identity`, `timeout_honesty` | the door they call does not exist; `timeout_honesty`'s *property* is preserved by `seon_terminal_honesty()` |
| `src/seon_inspect/cluster.py`, `seon_cluster.py` | ephemeral-cluster-by-pod lifecycle, superseded by the operator root + per-sample cluster |
| `src/seon_inspect/oracle_scorers.py`, `tool_scorers.py`, `tool_rows.py`, `generators.py`, `bfcl_adapter.py`, `worker_mock.py`, `worker_endpoints.py`, `typeahead_corpus.py`, `autocomplete_manifest.py`, `planner_worker_fixtures.py` | the dead parser/oracle/typeahead surface named in the second rot issue |
| `src/seon_inspect/catalog.py` pod-door registrations (`:94-124`) | eight benches advertised as current against a dead door |
| `src/seon_inspect/freeze.py`, `source_admission.py`, `offline_proof.py`, `reachability.py`, `milestone.py`, `mvp_graduation.py`, `product_scenarios.py`, `planning.py` | **review, do not delete blind** — several encode real discipline (source admission, offline proof). Judge each against whether its subject still exists; the ones that survive are re-pointed, not preserved in shape. This is the one line item that needs a reading pass rather than a decision |
| every `tests/test_*.py` pinning a deleted path | deleted in the same commit, per the standing deletion rule |

That is ~11,000 lines of Python today; the replacement is on the order of
600–800 lines of Python plus the Clojure factoring, because the grading moved
into `clojure.test` where it belongs.

### The inspect-ai pin

Vendored at 0.3.246 (2026-07-15). Upstream has shipped **0.3.247, 0.3.248,
0.3.249** since; the local mirror's `origin/main` is stale so this session
could not enumerate their changelogs — the one delta visible at the mirror's
Unreleased section (`EvalSample.turn_count`, `token_limit`,
`token_limit_type`, `token_limit_usage`, and the matching `samples_df` columns)
is directly useful for §6's horizon work. **Recommendation:** fetch the
submodule and move the pin to 0.3.249 as the first act of slice 1, read the
three changelogs, and record the delta in the dependency ledger. Nothing in
this design depends on an interface that has moved. `inspect-evals` at v0.14.3
matches its installed version and needs no change.

## 9. Open owner questions (recommendation first)

**O1 — Does the gate include the terminal state, or only the tests?**
*Recommendation: both, as separate columns.* A run that produces correct facts
but never completes is a different failure than a wrong answer, and collapsing
them hides loop defects. Alternative: tests alone, terminal state as metadata.

**O2 — Where do goal directories live?**
*Recommendation: `src-inspect-ai/goals/<goal-id>/`, with the test namespaces on
the eval host's classpath via an extra source root.* They are evaluation
material, not system tests, and `bin/test` must not pick them up. Alternative:
under `test/my/` where the agent's own namespaces already live — simpler
classpath, but then `bin/test` runs goal tests against no ending state and they
fail meaninglessly. **This one needs a ruling before slice 1 writes a path.**

**O3 — How does the eval host get the `:test` alias?**
*Recommendation: teach `bin/seon --root` an explicit alias selection.* The
alternative — starting the eval host with a raw `clojure -M:dev:test` line
inside the Python adapter — reintroduces a second way to boot a cluster, which
is the shape this design exists to remove.

**O4 — Judge cost policy.** *Recommendation: judges run by default on the final
scoring pass and are skipped during iteration via scorer selection.* Alternative:
judges always on (simpler, ~$0.0007 × samples × epochs).

**O5 — Do the surviving `freeze.py` / `source_admission.py` disciplines carry
over?** They encode "prove what revision produced this number," which is real.
*Recommendation: re-point rather than delete — the eval log's `metadata` gains
the source digest and the ending commit — but let one reading pass in slice 1
decide, since their current subject (the pod artifact) is gone.*

## 10. Falsifiers this design owes

- The prepl crossing is a claim, not a proof: slice 1 must show one form sent
  over a real advertised io-prepl returning a real JSON report, before any
  scorer is written.
- The ~0.9 s per-cluster figure was measured with two clusters in one process.
  It must be re-measured at 20 concurrent samples; `grader-in-fact-space`
  already flags branch lifecycle and store growth at that scale as unanswered.
- "Properties kill hardcoding" is an assertion until one deliberately
  hardcoded solution is run against a property goal and fails.
- The cheating judge must be validated against known-bad transcripts before its
  output is trusted as a signal; an unvalidated judge is decoration.
