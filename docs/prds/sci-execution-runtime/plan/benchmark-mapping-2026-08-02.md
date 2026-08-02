---
type: prd
status: active
tags: [prd, evaluation, agent, testing]
---

# The benchmark mapping — how most benchmarks just run (2026-08-02)

Design-only. No production file is changed by this document. It builds directly
on ruling #36 (`plan/inspect-ai-adaptation-2026-08-02.md`): Inspect is THE
agent-eval surface, the crossing is one warm JVM plus per-sample clusters over
prepl, and grading is `clojure.test`/`test.check` on the ending commit's fork
with DeepSeek-thinking judges advising. That document designed the *episode*.
This one designs the *demand side*: which external benchmarks that episode can
already satisfy, which ones a capability door would unlock, which ones would be
benchmark-washing if we claimed them, and the conventions that make a benchmark
task become a Seon episode **without per-benchmark code**.

## 1. The charter

Owner, verbatim intent: *"I want to be able to drive agents in scenarios we
define and that the benchmarks define. Figure out the mapping so most of the
benchmarks can just run."*

Two halves, and they have different answers:

- **scenarios we define** — the O1–O5 shape from
  `plan/bootstrap-vector-design-2026-08-01.md`: an objective message, an
  episode, a goal test namespace over the graded fork. Ruling #36 already owns
  this; nothing here changes it.
- **scenarios the benchmarks define** — an external dataset, an external
  prompt, and an external checker. The question this document answers is
  *where we plug in* so their dataset, their prompt template, and their scorer
  all run **verbatim**.

The verdict up front, then the evidence:

> **Two plug points, not one.** For every benchmark whose solver ends in
> `generate()`, Seon plugs in as an Inspect **model provider** — one
> `@modelapi("seon")` class, and the task runs unmodified, dataset, prompt
> template, scorer and all. For benchmarks whose solver is an *agent* driving
> tools in a sandbox, Seon plugs in as a **solver** — and that is exactly where
> the missing effect door bites. **106 of 131 vendored `inspect-evals` families
> (133 of 249 tasks) sit on the first plug point.** That is what "most
> benchmarks just run" actually means, and it needs no door at all.

## 2. Dependency ledger

| Dependency | Pin / path | What it establishes |
|---|---|---|
| `inspect-ai` | `reference-code/inspect-ai`, `05322696a` (0.3.246) | the solver/scorer/model contracts below |
| `inspect-evals` | `reference-code/inspect-evals`, `97c99f5` (v0.14.3) | 131 task families, 249 `@task` functions (probe `tmp/benchmark-map/classify.py`) |
| ruling #36 | `plan/inspect-ai-adaptation-2026-08-02.md` | the episode crossing, the goal-test gate, the judge roles |
| the drive harness | `src/seon/bootstrap_drive.clj:500-556` (`one-drive!`), `:322` (`completed-result`) | the episode; `completed-result` is the settled reply text — our `state.output.completion` |
| the test runner | `src/seon/test/runner.clj:80-108` (`run!`) | the one gate engine, shared with §6 |
| impacted-test design | `plan/runtime-impacted-tests-2026-08-02.md` | in-process runs, grading-fork safety, contract properties — §6 |

### The Inspect interfaces this design stands on

| Interface | Source | Why it matters here |
|---|---|---|
| solver override | `_eval/eval.py:122`, `_cli/eval.py:316`, resolved at `_eval/task/run.py:282` (`solver = solver or task.solver`) | `--solver file.py@name` replaces a task's solver wholesale, with `-S k=v` args |
| solver-from-file loading | `_eval/loader.py:623-670` | a solver in *our* repo, referenced by path, needs no upstream edit and no registry entry |
| `ModelAPI` | `model/_model.py:187`, abstract `generate` at `:310-317` | `async (input: list[ChatMessage], tools, tool_choice, config) -> ModelOutput`. The entire provider contract is one method |
| provider registration | `model/_registry.py:30` (`@modelapi(name)`), lookup at `model/_model.py:1885-1900` | `--model seon/<cfg>` finds an unqualified registered provider globally — no package prefix, no upstream change |
| `choice()` scorer's precondition | `scorer/_choice.py:44` — "required by the `multiple_choice` solver" | multiple-choice scoring reads `state.choices`, not text |
| MC answer marking helpers | `solver/_multiple_choice.py:82` (`parse_answers`), `:152` (`set_choices_based_on_generated_response`) | module-level and importable — a solver-side adapter can mark choices in ~10 generic lines |
| `Sample.files` / `Sample.setup` | `dataset/_dataset.py:29-75` | per-sample environment seeding, benchmark-provided |
| `Task(cleanup=…)` | `_eval/task/task.py:77`; precedent `paperbench/paperbench.py:124` | retire the per-sample cluster after evidence is copied out |

## 3. The inventory — what we actually vendor

`tmp/benchmark-map/classify.py` walks every family directory under
`reference-code/inspect-evals/src/inspect_evals`, collects every `.py`, counts
`@task` definitions, and detects the capability markers (`sandbox`, `bash()`/
`python()`/`bash_session()`, `web_browser`, `basic_agent`/`react`,
`multiple_choice`, `ContentImage`). A second pass splits the sandbox families by
**where** `sandbox()` is called: inside a `@scorer` (grading only) or inside a
`@solver`/`@tool`/`@agent` (the agent needs the shell). That split is the whole
argument of this document, and it is derived from source, not from READMEs.

**131 families, 249 tasks.**

| Class | Families | Tasks | Solver shape | Where the capability lives |
|---|---|---|---|---|
| **A1** text-only | 71 | 113 | `prompt_template` + `generate()`, or `multiple_choice()` | nowhere — pure text in, text out |
| **A2** code-gen, sandbox in the **scorer only** | 15 | 20 | `system_message`/`prompt_template` + `generate()` | Inspect's own Docker sandbox, at score time |
| **B** agentic shell/filesystem | 26 | 72 | `basic_agent`/`react` + `bash()`/`python()` in a container | the *agent* needs a shell |
| **C** tool-loop / web / function-calling | 7 | 13 | agent + non-shell tools (browser, APIs, simulated users) | the *agent* needs typed tools |
| **X** multimodal | 12 | 31 | `ContentImage` inputs | vision, orthogonal to every door |

Class members (family, `@task` count):

- **A1** — `abstention_bench(1) agieval(9) aime2024/2025/2026(3) air_bench(1)
  anima(1) ape(1) arc(2) bbeh(2) bbh(1) bbq(1) bold(1) boolq(1) chembench(1)
  coconot(1) commonsense_qa(1) cybermetric(4) drop(1) fortress(2)
  frontierscience(1) gpqa(1) gsm8k(1) healthbench(4) hellaswag(1) ifeval(1)
  infinite_bench(9) instrumentaleval(1) lingoly(2) livebench(1) mask(1) math(1)
  medqa(1) mgsm(1) mmlu(2) mmlu_pro(1) moru(1) musr(1) niah(1) novelty_bench(1)
  onet(1) paws(1) persistbench(3) personality(2) piqa(1) pre_flight(1)
  pubmedqa(1) race_h(1) sad(5) sciknoweval(1) sec_qa(4) sevenllm(4) simpleqa(2)
  sosbench(1) squad(1) stereoset(1) strong_reject(1) sycophancy(1)
  truthfulqa(1) uccb(1) winogrande(1) wmdp(3) worldsense(1) writingbench(1)
  xstest(1) agentic_misalignment(1) make_me_pay(1) makemesay(1) mind2web(1)
  cve_bench(1) gdm_capabilities(0)`
- **A2** — `apps(1) bigcodebench(1) class_eval(1) compute_eval(1)
  cyberseceval_2(3) ds1000(1) gdm_stealth(4) humaneval(1) ifevalcode(1)
  kernelbench(1) livecodebench_pro(1) mbpp(1) scicode(1) usaco(1)
  vimgolf_challenges(1)`
- **B** — `agent_bench(1) agentdojo(1) assistant_bench(5) browse_comp(1)
  core_bench(1) cti_realm(4) cybench(1) cybergym(1) frontier_cs(3) gaia(4)
  gdm_in_house_ctf(1) gdm_intercode_ctf(1) gdm_self_proliferation(20)
  gdm_self_reasoning(11) gdpval(1) ipi_coding_agent(1) mind2web_sc(1)
  mle_bench(3) mlrc_bench(1) osworld(2) paperbench(2) scbench(1) swe_bench(2)
  swe_lancer(1) theagentcompany(1) threecb(1)`
- **C** — `agent_threat_bench(3) agentharm(2) b3(1) bfcl(1) tac(2) tau2(4)`
- **X** — `cyberseceval_3(1) cyberseceval_4(10) docvqa(1) hle(1) lab_bench(8)
  macbench(1) mathvista(1) mmiu(1) mmmu(2) vqa_rad(1) vstar_bench(2)
  zerobench(2)`

Two caveats the classifier itself flags, kept honest rather than smoothed:
`gdm_stealth(4)` is non-agentic but calls `sandbox()` outside a scorer, so it is
listed in A2 by solver shape while its four tasks may still need a container —
it needs a reading pass before slice 2 claims it. `cve_bench(1)` matched no
sandbox marker in its own package but is a container benchmark by subject; it
should be re-read. Neither changes any conclusion below by more than one family.

### The standalone vendored suites

These are the upstream repositories, not Inspect tasks. They matter as *sources*
(datasets, checkers, container definitions) and as the thing an `inspect-evals`
family often wraps.

| Suite | Path | Relationship to the mapping |
|---|---|---|
| `swe-bench` | `reference-code/swe-bench` | the harness behind `inspect_evals/swe_bench`; class **B** |
| `terminal-bench` | `reference-code/terminal-bench` | shell-native by construction; class **B**, no Inspect wrapper vendored |
| `commit0` | `reference-code/commit0` | write-a-library-from-spec, python test suites; class **B** |
| `aider-polyglot` | `reference-code/aider-polyglot` | per-language exercise dirs with test files (`cpp go java javascript python rust`) — **no Clojure**, see §5 |
| `gorilla-bfcl` | `reference-code/gorilla-bfcl` | function-calling; class **C**; wrapped as `inspect_evals/bfcl` |
| `tau2-bench` | `reference-code/tau2-bench` | simulated-user tool dialogues; class **C**; wrapped as `inspect_evals/tau2` |
| `cybench`, `mle-bench`, `re-bench`, `deepswe` | `reference-code/*` | container CTF / ML-engineering / research-engineering; class **B** |
| `osworld`, `webarena`, `browsergym`, `browsecomp-plus` | `reference-code/*` | GUI and web environments; class **B**/**C**, need a browser door and a desktop |

None of the standalone suites changes the class arithmetic: every one of them
lands in **B** or **C**, which is precisely the door-gated half.

### The four dominant solver patterns, from source

1. **Prompt-then-generate, text-scored.** `gsm8k/gsm8k.py:59` —
   `solver = [prompt_template(MATH_PROMPT_TEMPLATE), generate()]`, scored by
   `match(numeric=True)` at `:88`. The scorer reads `state.output.completion`.
   This is the A1 majority.
2. **Multiple choice.** `mmlu/mmlu.py:96-97` — `mmlu_multiple_choice(...)` with
   `scorer=choice()`. The solver wraps Inspect's `multiple_choice`
   (`mmlu.py:198`), and `choice()` reads `state.choices`, *not* text
   (`scorer/_choice.py:44`). Structural, not textual — see §4.3.
3. **Generate, then execute the code in a sandbox at score time.**
   `humaneval/humaneval.py:85-87` — `solver=solver or generate()`,
   `scorer=verify()`, `sandbox="docker"`; `verify()` at `:94` does
   `find_code(state.output.completion)` (`:97`) then
   `await sandbox().exec(cmd=["python","-c", …])` (`:109`). Same shape in
   `mbpp/mbpp.py:134-138`, `apps/apps.py:177-181`, `class_eval:95`,
   `bigcodebench:128`. **The solver never touches the sandbox.** This is A2, and
   it is the finding that moves 20 tasks out of the door-gated column.
4. **Agent with shell tools in a container.** `gaia/gaia.py:68`
   (`solver = solver or default_solver(max_attempts)`, defined `:100` with
   `code_timeout`), `swe_bench/` (`solvers.py`, `scorers.py` — the scorer writes
   an eval script into the environment and parses its output, ruling #36 §2).
   This is B: the *agent's own loop* is a tool loop over `bash`. Note that
   `gaia:28` types the parameter `solver: Solver | Agent | None` — B tasks
   overwhelmingly expose a `solver` parameter, which is the plug point of §4.2.

## 4. The two plug points

### 4.1 Plug point 1 — Seon as an Inspect model provider (the "just run" lever)

`ModelAPI` is one abstract method (`model/_model.py:310-317`):

```python
async def generate(self, input: list[ChatMessage], tools: list[ToolInfo],
                   tool_choice: ToolChoice, config: GenerateConfig
                   ) -> ModelOutput | tuple[ModelOutput | Exception, ModelCall]
```

`@modelapi("seon")` (`model/_registry.py:30`) registers it, and the lookup at
`model/_model.py:1885-1900` matches the **unqualified** name from
`--model seon/<cluster-config>` with no package prefix required. Model args
after `-M` reach the constructor as `**model_args` (`:1899`).

The implementation is the ruling-#36 crossing with a different caller:

```text
generate(input, tools, tool_choice, config)
  → render `input` into ONE objective message (the last user message plus a
    rendered prefix of the prior turns — this is a projection, §4.4)
  → one form over the warm host's prepl:
      (seon.eval.drive/run-episode! {…objective, run-cap, artifact-root})
  → ModelOutput(completion = completed-result   ; bootstrap_drive.clj:322
                model = "seon/<cluster>",
                metadata = {ending-commit, grading-branch, cluster,
                            agent-id, run-ids, transcript, terminal-outcome})
```

What that buys, verbatim and with zero adapter code per benchmark:

- **every A1 family (71 families / 113 tasks)** — dataset, prompt template,
  system message, few-shot construction, and scorer (`match`, `includes`,
  `choice`, `model_graded_qa`) all run upstream and unmodified;
- **every A2 family (15 families / 20 tasks)** — likewise, and their `verify()`
  scorer runs *its own* Docker sandbox at score time. **Seon needs no fs and no
  shell for these**: the capability is Inspect's, on the harness side of the
  crossing, exactly where it already is for every other model;
- **multiple choice for free** — `multiple_choice()` calls `generate` internally
  and then marks `state.choices` itself; because we replaced the *model* and not
  the *solver*, that marking still happens and `choice()` still works.

This is the honest reading of "most benchmarks just run": **86 families / 133
tasks, 53% of the vendored task surface, with one provider class.**

The dishonesty this must not commit: a Seon episode is *not* a chat completion.
It is a multi-turn agent run whose intermediate turns are Clojure evaluations.
Declaring it as a model means Inspect's `ModelEvent` records one call where many
turns happened. That is acceptable and must be stated in the eval log metadata
(the episode transcript rides `ModelOutput.metadata`), because the *benchmark's*
contract is "here is a question, return an answer" and we honour that contract
exactly. It becomes dishonest the moment the task passes `tools` — see the
refusal rule in §4.3.

### 4.2 Plug point 2 — Seon as a solver

For B and C, the benchmark's agent loop *is* the thing under test, so the
provider seam is wrong: their loop would drive our episode as a single model
turn, and their tools would go unused. The seam is
`--solver src-inspect-ai/src/seon_inspect/solver.py@seon_episode`
(`_eval/loader.py:623-670`), which replaces `task.solver` at
`_eval/task/run.py:282`. Most B tasks additionally expose a `solver:` parameter
(`gaia/gaia.py:28`, `bigcodebench:58`, `humaneval:64`), so a Python driver can
pass it directly and keep the task's other configuration.

The cost of this seam, stated plainly: **`--solver` replaces the whole chain**,
including `system_message` and `prompt_template`. Everything the task did to
construct its prompt is lost. That is fine for B (the agent constructs its own
context from `state.input`) and wrong for A (the template is load-bearing) —
which is another reason A goes through the provider seam and B through the
solver seam. They are not interchangeable.

### 4.3 The refusal rule — when neither seam is honest

**A Seon episode must refuse, loudly, when `generate` is called with a non-empty
`tools` list.** Seon agents do not emit tool calls; they evaluate Clojure. A
provider that silently drops `tools` and returns prose would produce a green
number on a benchmark measuring something we did not do. That is the
benchmark-washing failure mode, and the construction that makes it
unrepresentable is a raised error at the provider boundary, surfaced as an
Inspect **sample error** (`EvalSample.error`, `log/_log.py:382-…`), never a
score. `Score.unscored()` (`scorer/_metric.py:104`) is the analogous honest
sentinel on the scoring side.

This one rule is what keeps the provider seam from quietly absorbing class C.

### 4.4 The message projection

`generate` receives `list[ChatMessage]`; an episode takes one objective message
(`bootstrap_drive.clj:163-189`). The projection is a pure function and belongs
in the adapter, not the runtime:

- one user message → the objective verbatim;
- system + user (the A1 majority: `system_message` then `prompt_template`) →
  the system content rendered as a prefix block, then the user content;
- longer histories (few-shot, `sample_to_fewshot` at `gsm8k.py:106`) → rendered
  in order into the one objective, since the few-shot exchange is *content*, not
  a conversation with our agent;
- assistant messages produced by *our own* prior call in the same sample → a resumed
  episode is the honest mapping, and it is unbuilt. Until stateless
  resume lands (`plan/stateless-resume-design-2026-08-01.md`), a second
  `generate` call within one sample must start a fresh episode with the full
  rendered history, and that fact belongs in the log metadata. **Open question
  O2.**

## 5. The honest verdict — translation versus benchmark-washing

The owner asked for honesty here, so this section states refusals as clearly as
capabilities.

### Genuinely translates (no distortion)

- **A1 in full.** A knowledge/reasoning/math question is model-shaped. The
  benchmark asks for an answer; the episode produces one; their scorer grades
  it. Nothing about Clojure, the REPL, or the cluster changes what is measured.
  If the episode *uses* the REPL to compute a `math` answer, that is a
  capability difference between agents, which is the point of an eval.
- **A2 in full.** The benchmark asks for Python source in a completion and runs
  it in *their* container. Our agent may reason in Clojure and emit Python; the
  measured quantity — does the emitted program pass the hidden tests — is
  unchanged. This is the same contract every other model signs.

### Translates with a stated distortion (allowed, must be labelled)

- **`ifeval`, `writingbench`, `sad`, `personality` and the other
  instruction-following / self-knowledge families.** These measure a *model*;
  we submit an *agent with a working memory and a REPL*. The number is real but
  it is not comparable to a published single-turn number. Label the run.
- **Long-context families (`infinite_bench(9)`, `niah`).** The objective message
  carries the full context, which collides with our own context budget
  (`plan/context-budget-design-2026-07-31.md`). A truncated context silently
  produces a low score that looks like a reasoning failure. Either the budget is
  raised for the run and that is recorded, or the family is skipped. **Do not
  report a truncated long-context number.**

### Does NOT translate — refuse rather than adapt

- **SWE-bench and every repo-shaped benchmark (`swe_bench(2)`, `swe_lancer(1)`,
  `commit0`, `deepswe`).** The task is "edit this Python repository so that
  `FAIL_TO_PASS` passes under pytest." Re-expressing it as "do the equivalent
  with `java.nio` from a Clojure REPL" changes the artefact, the toolchain, and
  the difficulty. It would be benchmark-washing to report a SWE-bench number
  earned that way. The honest path is the real one: the agent gets a genuine
  shell in a genuine container through the effect door, and until then SWE-bench
  is **not runnable**, not "runnable with interop."
- **`terminal-bench`, `cybench`, `gdm_intercode_ctf`, `gdm_in_house_ctf`,
  `threecb`, `mle_bench`, `re-bench`.** Same argument: the shell *is* the
  benchmark.
- **`aider-polyglot`.** Its exercise tree is `cpp go java javascript python rust`
  — there is no Clojure track. Adding one would be authoring a new benchmark and
  calling it aider-polyglot.
- **Class X (12 families / 31 tasks).** No image input path exists on any Seon
  surface. Not a door question; a modality question. Out of scope entirely.
- **`osworld`, `webarena`, `browsergym`, `mind2web_sc`.** A GUI/browser
  environment is a different substrate from a capability door; even a web door
  would not make these runnable.

### The specific trap: "REPL-fs equivalence"

The tempting claim is that once JVM interop lands, `java.nio.file` inside the
REPL is a filesystem capability, so fs-touching benchmarks run. **Half true, and
the half that is false is the expensive half.** It is genuinely true for *our*
goals: a scenario we define that says "write this analysis to a file and a
validator function will check it" is satisfied by interop, and ruling #36's
slice 2 (`seon.eval/*artifact-root*`) is exactly that. It is false for
*benchmarks that define the toolchain*: `bash`, `git`, `pytest`, `pip`, and a
POSIX process model are not incidental packaging around a filesystem, they are
the object of measurement. Interop moves **zero** external benchmark families
from unrunnable to runnable. It moves our own scenario library forward, which is
the other half of the charter and worth doing on its own terms.

Interop *does* pay one indirect dividend on the benchmark road: it lets the
agent read a large problem statement or a data file from the sample's artifact
root instead of carrying it in the objective message, which relieves the
long-context distortion above for some A1/A2 samples.

## 6. The generic adapter — conventions, not per-benchmark code

The design law is `feedback_no_hand_maintained_lists`: no registry of
benchmark→adapter rows. Four conventions, each a pure mapping.

### 6.1 Sample input → objective message

The §4.4 projection, applied to whatever `list[ChatMessage]` the task built.
Because the provider seam sits *below* the task's solver chain, the benchmark's
own prompt engineering has already happened. Nothing is re-implemented.

For samples that carry environment state — `Sample.files` and `Sample.setup`
(`dataset/_dataset.py:29-75`) — the files are materialized into the sample's
artifact root before the episode, and the root is named in the objective. This
is the only place a benchmark's environment reaches Seon without a door, and it
is read-only-by-construction until interop lands.

### 6.2 Benchmark checker → the goal test namespace, *or* their scorer verbatim

This is the shortcut ruling #36 §6 left open, and the answer is sharper than
that document assumed.

> **Run their scorer verbatim whenever the scorer reads
> `state.output.completion` or `state.choices` and nothing else about the
> solver.** Port to a Clojure goal test namespace only when the scorer reads
> solver-private state.

Under the provider seam this covers **all of A1 and all of A2**, because their
scorers are exactly that: `match(numeric=True)` (`gsm8k.py:88`), `choice()`
(`mmlu.py:97`), `verify()` reading `find_code(state.output.completion)`
(`humaneval.py:97`), `model_graded_qa`. Nothing is ported. Nothing is generated.
The Clojure goal-test mechanism of ruling #36 remains the gate for **scenarios
we define**, where there is no upstream scorer — and that is the correct
division of labour rather than two competing graders.

Our own scorers still attach as *additional* columns on the same run
(`_eval/task/task.py:78` takes a scorer list): `seon_terminal_honesty()` and
`seon_cheating_judge()` from ruling #36 §4.4 apply to a benchmark episode
unchanged, reading the transcript we put on `ModelOutput.metadata`. So a
benchmark run reports the benchmark's own verdict *and* our honesty
instrumentation side by side, which is strictly more information than either
alone.

### 6.3 Multi-turn tool loops → not adapted, refused

Per §4.3. When the effect door exists, the mapping becomes concrete and is
already anticipated by their interface: `basic_agent`/`react` pass `tools:
list[ToolInfo]` into `generate`, and the door's capability requests are the
natural other side of that. The design is deliberately **not** written here,
because writing it before the door exists would be designing against an
imagined interface — the failure mode this program has been burned by. It gets
written when `seon.effect` has a shape.

### 6.4 Per-sample isolation

Unchanged from ruling #36 §7: one sample = one cluster (a branch fork, ~17 ms) +
one artifact directory + one grading branch, retired by `Task(cleanup=…)`
(`_eval/task/task.py:77`, precedent `paperbench.py:124`). The provider seam does
not change this; it changes only who initiates the episode.

## 7. Integration duty — one test-running story, not two

`plan/runtime-impacted-tests-2026-08-02.md` LANDED in parallel with this design
(commit `e166e5310`).
Its subject (run the tests impacted by an agent's install, inside the process,
without wedging the turn) and ruling #36's subject (run a goal's tests against
the episode's ending state) are **the same machinery seen from two ends**. They
must converge, and the shared mechanism is named here so neither lane builds a
second one.

| Shared piece | Owner | How each design uses it |
|---|---|---|
| `seon.test.runner/run!` (`runner.clj:80-108`) | the runner | impacted-tests: the in-process run over the impacted `:seon.test` set. Benchmark/goal grading: the run over the goal's namespaces. **One engine, one result shape** — `{summary, results[{sym, outcome, failure}]}` — which is also exactly what a `Score` needs |
| the grading fork | `bootstrap_drive.clj:469-474`; generalized in impacted-tests §5 | both fork the current commit and run against the fork, never the live branch. Impacted-tests §5 states the invariant ("a test must never run against the live cluster branch"); ruling #36's grader is the first citizen of it |
| contract properties via `malli.generator/function-checker` (`reference-code/malli/src/malli/generator.cljc:526-551`) | the corpus's `:seon.fn/spec` facts | impacted-tests §6 derives the free property per contracted function. Ruling #36 §4.2 wants properties over an agent's *solution*. **These are the same property, generated the same way** — the goal author writes a property only where the free one cannot see the invariant (relations between calls, effects, facts) |
| `test.check` on the classpath | `deps.edn` | impacted-tests §0 records that ruling #36 promoted it and the promotion has **not landed** (`deps.edn:89` `:test` only). This blocks both designs; it is one dependency line and should land first |
| `:seon.test` result facts via `record-tx` | the runner's sink | impacted-tests commits results as facts the agent reads next turn. A benchmark episode's goal-test results land in the same rows, which is why the overseer loop can compare them |

**The convergence rule, stated once:** there is ONE test-running mechanism —
`seon.test.runner/run!` against a grading fork, budgeted in trial counts and
backstopped by the door's `time-limit` (impacted-tests §4.3). The impacted-tests
proc and the eval grader are two *callers* of it. Neither may introduce a runner,
a second result shape, or a second notion of "the tests passed."

**One consequence for benchmark scoring, from that design's finding #1:**
agent-authored functions are currently **graph islands** — runtime installs
carry no `:seon.fn/calls` edges (falsified directly, probe P2; fixed in that
design's slice 1). Any scorer or judge that reasons about the *structure* of the
agent's code — the cheating judge's "is there an authored function behind this
completion", a goal test that walks from the agent's entry point to its helpers,
an impacted-set derivation over agent code — sees an unconnected node today.
This does not affect §4/§5 at all (A1 and A2 scorers read the completion), but
it is a hard prerequisite for any *scenario we define* that grades multi-function
or multi-namespace generated code, which is ruling #36 slice 3's subject.
Benchmark work must not build a second traversal around it; it waits on that
slice.

One divergence needs an owner call, recorded rather than resolved here: the
impacted-tests design runs as a **`:compute` flow proc** so a turn never blocks
(§4.2), while the eval grader runs **synchronously at score time** because
Inspect's scorer is awaiting a value. Both are correct for their caller; the
shared piece is the function, not the scheduling. This is not a second mechanism
and should not be "unified" into one.

## 8. The door demand schedule

The payoff table: which unbuilt door unlocks which families, ranked by unlock
count. This turns door-building from a design preference into a measurable
schedule.

| Rank | Door | Families unlocked | Tasks unlocked | Representative families | Notes |
|---|---|---|---|---|---|
| **1** | **shell/exec in a container** (`bash`, `python`, a POSIX process) | **~22** | **~63** | `swe_bench(2) gdm_self_proliferation(20) gdm_self_reasoning(11) gaia(4) cti_realm(4) frontier_cs(3) mle_bench(3) cybench(1) gdm_intercode_ctf(1) gdm_in_house_ctf(1) agent_bench(1) core_bench(1) paperbench(2) scbench(1) threecb(1) …` | **the single largest unlock by a wide margin.** Also unlocks every standalone shell suite (`terminal-bench`, `commit0`, `re-bench`, `deepswe`) |
| **2** | **filesystem** (read/write a per-sample root) | 0 external | 0 external | — | zero *external* families move; it is a prerequisite of rank 1 and the enabler of ruling #36 slice 2 (our own artifact goals). Ranked here for honesty, not to demote it |
| **3** | **typed tool calls** (the agent emits a tool call the harness dispatches) | ~7 | ~13 | `bfcl(1) tau2(4) agent_threat_bench(3) agentharm(2) tac(2) b3(1)` | independent of the shell door; it is a *protocol* change in how an episode ends a turn, not a capability |
| **4** | **web** (fetch/search) | ~4 | ~11 | `browse_comp(1) assistant_bench(5) mind2web_sc(1) gaia(4, overlaps rank 1)` | mostly overlaps rank 1 in practice, since these agents also want a shell |
| **n/a** | browser/GUI automation | ~4 | ~5 | `osworld(2) webarena browsergym mind2web_sc(1)` | a substrate, not a door; out of scope |
| **n/a** | vision | 12 | 31 | class X | a modality, not a door; out of scope |

Read that table with §5's refusals in hand: rank 1's ~63 tasks are unlocked only
if the door gives a **genuine** shell in a **genuine** container. A door that
offers `java.nio`-flavoured file access unlocks none of them, and claiming
otherwise is the washing failure.

Cumulative picture:

- **today, provider seam only: 86 families / 133 tasks (53%)**
- **plus the shell door: ~108 families / ~196 tasks (79%)**
- **plus typed tools: ~115 families / ~209 tasks (84%)**
- the remaining ~16% is vision and GUI substrates, which are not door questions

## 9. Slices

### Slice 1 — one A1 family, end to end, upstream untouched

**Recommended pick: `gpqa` (`gpqa/gpqa.py`, 1 task, `gpqa_diamond`, 198
samples).**

Why this one over the alternatives:

- it is **multiple choice scored by `choice()`**, which is the *harder* of the
  two A1 shapes — proving it proves `match`-shaped families trivially, while
  proving `gsm8k` first would leave the structural path unproven;
- it exercises the provider seam's one non-obvious property: because we replace
  the model and not the solver, `multiple_choice()` still marks `state.choices`
  and `choice()` still works. If that fails, the whole plug-point-1 thesis is
  falsified in one run, which is exactly what slice 1 is for;
- 198 samples at ~0.9 s per cluster plus model latency is an affordable first
  real run, and there is a published diamond number to sanity-check against;
- it needs **no** goal directory, no ported checker, no generated test — the
  first proof that "just run" is literal.

Scope, exactly:

1. `seon_inspect/provider.py` — one `@modelapi("seon")` class over the ruling-#36
   warm host, implementing `generate` per §4.1, refusing non-empty `tools` per
   §4.3, and attaching the episode record to `ModelOutput.metadata`.
2. The §4.4 message projection as a pure function with its own unit test.
3. `inspect eval inspect_evals/gpqa_diamond --model seon/eval-host` with the
   ruling-#36 host started in `Task.setup` — or, for slice 1's simplicity, as an
   explicit pre-step.
4. Attach `seon_terminal_honesty()` as an extra scorer column so the run reports
   both verdicts.

Acceptance: an eval log with a populated `choice()` accuracy column, a
`seon_terminal_honesty()` column, and per-sample metadata carrying the ending
commit and grading branch — with **no file under `reference-code/inspect-evals`
modified**. That last clause is the actual acceptance criterion; if any upstream
file had to change, the thesis is wrong.

### Slice 2 — one A2 family through the same seam

**Recommended pick: `humaneval`** (`humaneval/humaneval.py:85-87`) — smallest
dataset of the A2 set, the canonical `verify()` scorer, and the sandbox is
theirs. Second choice `mbpp` (`mbpp.py:134-138`), same shape, `Epochs` with
`pass_at_k` already configured, which is the pass^k discipline ruling #36 §6
wants preserved.

Scope: none beyond slice 1 on the Seon side. The whole slice is *evidence* that
an A2 family needs no new mechanism — plus the one real question it answers: can
a Clojure-native agent, asked for Python in a completion, reliably emit it? A low
score here is a genuine finding about our bootstrap content, not an adapter bug,
and the distinction is visible because the honesty scorer runs alongside.

### Slice 3 — the goals shape for scenarios we define

Ruling #36 slice 1 (goal directories for O1–O5, the Clojure test gate) is
unchanged and independent. It goes through the **solver** seam, not the provider
seam, because there is no upstream task. Both seams landing is what proves the
two halves of the charter are one system.

### Slice 4+ — door-gated

Not scheduled here. §8's table **is** the effect-door PRD's demand schedule:
rank 1 first, with SWE-bench as its acceptance benchmark precisely because §5
refuses every cheaper substitute for it.

## 10. Open owner questions (recommendation first)

**Q1 — Is the model-provider seam acceptable, given that an episode is not a
chat completion?**
*Recommendation: yes, with the §4.3 refusal rule and a mandatory metadata
label.* It is the only construction that runs 133 tasks with zero per-benchmark
code, and the benchmark's contract ("question in, answer out") is honoured
exactly. Alternative: solver-only, which means re-implementing every task's
prompt construction and losing `multiple_choice`/`choice()` — strictly more code
and strictly less fidelity. **This one gates slice 1's first line.**

**Q2 — What happens when a task calls `generate` more than once in a sample?**
*Recommendation: slice 1 starts a fresh episode per call and records that in
metadata; when stateless resume lands
(`plan/stateless-resume-design-2026-08-01.md`), the second call resumes the same
episode.* Alternative: refuse multi-generate tasks until resume lands — cleaner,
but drops several A1 families for no measurement benefit.

**Q3 — Do we report distorted-but-real numbers, or suppress them?**
*Recommendation: report with a mandatory `comparability` label in the eval log
metadata, and suppress only where §5 says "refuse".* The long-context and
instruction-following families produce real signal about our agents even when
not comparable to published numbers. Alternative: publish only the clean set —
safer externally, but throws away our own best regression signal.

**Q4 — Does the `tools`-present refusal raise, or return `Score.unscored()`?**
*Recommendation: raise, surfaced as `EvalSample.error`
(`log/_log.py:382-…`).* A sample error is retryable and cannot be averaged into
a metric; `unscored()` (`scorer/_metric.py:104`) is for a *judge* that could not
decide, which is a different fact. Alternative: return an explicit incorrect
score — rejected, it would understate capability with a number that looks
measured.

**Q5 — Where does the provider live, and does `test.check` land first?**
*Recommendation: `src-inspect-ai/src/seon_inspect/provider.py`, and yes — the
`test.check` promotion (ruling #36, still unlanded per impacted-tests §0,
`deps.edn:89`) lands before either lane's slice 1, since both depend on it.*

## 11. Falsifiers this design owes

- **The provider thesis is a claim until one `inspect eval` runs against
  `--model seon/…` with an unmodified `inspect-evals` checkout.** Slice 1 exists
  to falsify it; the `choice()`/`state.choices` interaction is the specific
  place it would break.
- **The 86/133 arithmetic comes from a static classifier**
  (`tmp/benchmark-map/classify.py`) over marker regexes. It is grounded in
  source, not READMEs, but it is not execution. Two families are already flagged
  as needing a reading pass (`gdm_stealth`, `cve_bench`). The number should be
  re-derived by *attempting* a run across the A1 set once slice 1 works —
  failures will reclassify some families, and that is the point.
- **"A2 needs no door" assumes the harness may run Docker.** If the eval host
  cannot, A2 collapses into the door-gated column for an operational reason
  rather than an architectural one. Verify before slice 2.
- **The message projection is where fidelity is silently lost.** A few-shot
  prompt flattened into one objective may measure something different from the
  same prompt as a conversation. One A1 family should be run both ways and the
  scores compared before the projection is trusted.
- **The interop verdict ("moves zero external families") is an assertion.** It
  is falsified the day someone names an external family whose measured quantity
  survives the toolchain swap. Nobody has.
