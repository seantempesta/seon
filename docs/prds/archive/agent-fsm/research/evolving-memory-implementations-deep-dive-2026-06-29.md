---
type: research
status: active
tags: [research, agent]
---

# Evolving-Memory Implementations — Deep Dive (grounding the "evolve our own store/retrieve" build)

Companion to [[self-evolving-memory-survey-2026-06-29]] (the literature map +
anti-cheat playbook). That file answered *who has published this*. This file
answers *how the real code works, what to steal, and how we host a cheat-proof
harness on what we already have*. Every claim below is cited to source we
vendored and read (`reference-code/<repo>/<path>:<line>`). Date: 2026-06-29.

Repos vendored for this pass (shallow clones under `reference-code/`, left
untracked like `mvm` — NOT submodules, NOT committed):

| Repo | URL | Why | Read depth |
|---|---|---|---|
| **Voyager** | github.com/MineDojo/Voyager | the self-verification + skill-library loop = our pattern | DEEP (loop + critic + skill mgr) |
| **funsearch** | github.com/google-deepmind/funsearch | the canonical island/program-DB + execution-evaluator | DEEP (programs_database + evaluator) |
| **openevolve** | github.com/algorithmicsuperintelligence/openevolve | AlphaEvolve OSS: cascade eval + MAP-Elites DB | SKIM (evaluator/controller/database) |
| **SimpleMem / EvolveMem** | github.com/aiming-lab/SimpleMem (`EvolveMem/`) | THE match — diagnose→propose→guard→revert loop | DEEP (evolution.py + diagnosis.py) |
| **ADAS** | github.com/ShengranHu/ADAS | meta-agent programs agents in code; archive | SKIM |
| **mem0** | github.com/mem0ai/mem0 | concrete memory ops (add/search/update/consolidate) | SKIM (op signatures) |
| **letta** | github.com/letta-ai/letta | MemGPT tiered-memory op design space | SKIM |
| **generative_agents** | github.com/joonspk-research/generative_agents | multi-signal retrieval baseline (recency·relevance·importance) | reference only |

Already-present and read for isolation/harness reuse: **`mvm`** (the microVM SDK),
**`inspect-ai`** (`util/_sandbox`), **`re-bench`** (METR protected scoring),
plus `inspect-evals`, `mle-bench`, `terminal-bench`, `cybench`, `tau2-bench`.

**Evo-Memory / MemEvolve / EvolveMem release status:** EvolveMem **ships code**
— it is the `EvolveMem/` subtree inside `aiming-lab/SimpleMem` (NeurIPS-2026
badge in its README). MemEvolve (2512.18746, "EvolveLab" codebase) and
Evo-Memory (2511.20857, the streaming *benchmark*) advertise code in their
papers but **I found no separate public repo under the obvious names** at clone
time — only the arXiv/alphaXiv/researchgate pages resolve. If Evo-Memory's
benchmark lands, it is the ready-made fitness harness to grab; right now it is a
gap. **Honest gap: we got the diagnosis-loop reference (EvolveMem) but not the
benchmark (Evo-Memory).**

---

## TL;DR / verdicts (the three the task asked for)

1. **Isolation verdict — can `mvm` give us a cheat-proof REPL-in-microVM on
   macOS? YES, on Apple Silicon, via the Vz backend — with one caveat.** The
   guest is **vsock-only, no SSH, no host-fs access beyond explicit shares**
   (mvm security claim 1), the host runs `mvmctl` and the guest cannot reach the
   host process. So we put the agent's REPL + only the test files *inside* the
   VM and keep the checker/answer-key in the *host* python that called
   `Sandbox.exec(...)` — the agent physically cannot read the answer key, fake
   the score log, or edit the detector, because all three live in a different
   address space it has no channel to. **Caveat:** macOS runs the **Tier-2**
   backend (Apple Virtualization.framework on macOS 26+, libkrun on 13-25), NOT
   Firecracker — "macOS can't run live Firecracker microVMs natively"
   (`mvm/README.md:316`). Tier-2 is a real hardware-virtualized microVM (full
   isolation for our purposes); the *maximal* posture (dm-verity sealed rootfs,
   seccomp-confined services, Tier-1) is **Linux+KVM only**. For a cheat-proof
   *memory-design eval* on our darwin pod, Vz is sufficient; if we later want the
   hardened posture we run the harness on a Linux+KVM box (Hetzner CCX/CAX, the
   `ops/hetzner/` scaffold).

2. **Single most reusable existing harness: `inspect-ai` (`util/_sandbox`).** It
   already ships the exact abstraction we need — a `SandboxEnvironment` ABC with
   `exec(cmd, …)` / `read_file` / `write_file`, per-sample filesystem context,
   output/time limits, AND a registry that lets us **add an `mvm` sandbox
   provider** beside its `docker`/`local` ones. It also ships datasets + scorers
   + `inspect-evals` (100+ ready tasks). We host our eval loop *in* inspect-ai
   and plug `mvm` in as the isolation provider. Second choice for *scoring
   pattern* specifically: **re-bench's METR `task_protected_scoring`** (encrypted
   answer keys + `strip_score_information` — the anti-cheat scoring primitive).

3. **Loop to fork: EvolveMem's `EvolutionEngine.evolve`** (the elitist
   accept/revert + LLM-diagnose-from-failure-logs + meta-explore loop) is our
   control structure almost verbatim — but **swap its config-vector action space
   for our real `register!`/`transact!`/`query` substrate, and swap its static
   LoCoMo QA fitness for a fresh-child-agent-after-restart probe** (Voyager's
   self-verifier mechanism). Wrap the candidate program-DB in funsearch/AlphaEvolve
   **islands + MAP-Elites** so it doesn't collapse to one local optimum/cheat.

---

## Per system — mechanism, what to steal, what to avoid, test-env?

### 1. EvolveMem (`SimpleMem/EvolveMem/`) — THE match, read deepest

**Mechanism (the real code).** `evolvemem/evolution.py` `EvolutionEngine.evolve`
(`:402`) is a round loop. Per round:

- **Phase 2-4 — evaluate:** build a `MultiViewIndex` over extracted memories
  (`:482`), answer all held-out QA (`_evaluate_qa`, `:485`), score F1 per
  question + per category (`:488-495`). This is the **objective measured
  fitness**.
- **Phase 6 — elitist accept/revert (this is "accretive-or-revert"):**
  `evolution.py:510-577`. Round 0 is the baseline. Each later round computes
  `delta = overall_f1 - best_f1` (`:526`); `accepted = delta >
  acceptance_threshold` (`:527`). On reject it **writes the incumbent config back
  over the current one** (`:575-577` — `setattr(ret_config, k, v)` from
  `best_config`) and increments `consec_noaccept`; `max_consec_noaccept`
  consecutive rejects → converge/stop (`:580-598`). A small-but-positive delta is
  a "SOFT-ACCEPT" kept as new baseline (`:559-565`) — they explicitly *don't*
  discard sub-threshold real gains. **This is the monotonic learning curve; steal
  it exactly.**
- **Phase 5 — diagnose (LLM, AFTER the elitist step so it sees the incumbent on a
  reject):** `diagnostics.diagnose(qa_results, …, attempt_history=…)` (`:609`).
  The `DIAGNOSIS_PROMPT` (`diagnosis.py:22`) is *"You are the diagnosis engine of
  a self-evolving memory system. Your job is to turn evaluation failures into a
  concrete next-round action."* It feeds the LLM the failure summary + raw sample
  failures and a **rule-table mapping symptom→lever** (`diagnosis.py:172-180`:
  "many abstention failures → recall too low → raise top_k"; "wrong answer + high
  retrieval → precision → lower max_context"). Crucially it passes
  **`attempt_history`** (`:528-535`, `:613`) so the LLM **can't re-propose a move
  that already failed** — every round records `{diff, f1_before, f1_after, delta,
  accepted}`.
- **Phase 7 — propose:** `diagnostics.suggest_config_update(report, …,
  max_changes=max_changes_per_round)` (`:641`) — the LLM emits a *capped* config
  diff (one movable direction per round under elitism, `:639-647`).
- **Phase 7b — meta-analysis (explore-on-stagnation):** `meta_analyzer.analyze(…)`
  (`:684`) reads ALL rounds + current failures and returns a `MetaPlan` whose
  `decision_type` can be `revert` / `focus` / `explore` / propose-new-dimension
  (`:698-731`). This is the "inject randomness / open a new axis when the baseline
  plateaus" trigger.

The action space is a `RetrievalConfig` dataclass (fusion weights, top_k's,
`max_context`, per-category overrides, boolean prompt-surface flags) —
`evolution.py:139-150`, `multi_retriever.py`. The README frames it as a
"structured action space" optimized by closed-loop AutoResearch.

**STEAL:** (a) the **elitist accept/revert with a separate "true best" tracker**
(`:536-549`) — our accretive-or-revert, already debugged for the
"sub-threshold-but-real gain" footgun; (b) **`attempt_history` fed to the
proposer** so it never re-tries a dead move — cheap, high-value, we get it free;
(c) **diagnose AFTER the accept/revert decision** so the LLM critiques the state
it will actually mutate; (d) the **symptom→lever rule table** as the proposer's
system prompt scaffold; (e) **meta-analyzer as the stagnation escape valve**
(distinct from the per-round proposer).

**AVOID:** (a) the action space is a **frozen-skeleton config vector** — it tunes
knobs on a fixed retrieval pipeline, it does NOT let the agent rewrite the
schema/API (our substrate differentiator); (b) **fitness is a static QA set
(LoCoMo)** — no fresh-agent, no restart, no distractor pressure, so "store more
verbatim + raise top_k" is a legitimate winning move there but would be a *cheat*
for us; (c) **no anti-cheat layer at all** — there is no hidden checker, no
hack-detector, the config can't even express "store the answer key" so they never
needed one. We DO need one. (d) the prompt is drenched in **LoCoMo/MemBench-
specific flags** (`diagnosis.py:160-169`) — that benchmark-coupling is the
overfit smell their own "positive transfer" claim is trying to refute; keep our
levers substrate-general.

**Test-env?** Ships `run_evolution.py` (LoCoMo driver) + `run_benchmark.py` +
`evolvemem/benchmarks/`. Reusable as a *reference driver*, not as our fitness
(wrong task: conversational QA, not store-then-retrieve-across-restart).

### 2. Voyager (`Voyager/voyager/`) — the fresh-agent self-verifier + skill library

**Mechanism.** `voyager.py` is curriculum → rollout → critic → skill-admission:

- **`learn()` loop** (`voyager.py:315-368`): `curriculum_agent.propose_next_task`
  (`:319`) → `rollout(task,…)` (`:328`) → **`if info["success"]:
  skill_manager.add_new_skill(info)`** (`:353-354`). The skill library only grows
  on a *verified* success — the admission gate.
- **`step()`** (`:203-285`): the action agent writes code, the env *executes* it
  (`self.env.step(code, programs=self.skill_manager.programs)`, `:215`), then
  **`critic_agent.check_task_success(events=…)`** (`:221`) returns `(success,
  critique)`. `done` when success or retry-cap (`:266-269`). The critique is fed
  back into the next human message (`:256`) — execute→verify→critique iteration.
- **The self-verifier** (`agents/critic.py`): a SEPARATE LLM agent. `render_human_message`
  builds an observation from the *environment state* (inventory, blocks, biome,
  the task) — `critic.py:44-77` — and `ai_check_task_success` (`:91`) asks it to
  return JSON `{success: bool, critique: str}`. **It judges the world state, not
  the agent's own claim.** That separation is the load-bearing anti-self-grading
  property the survey flagged (Voyager ablation: -73% item count without it).
- **Skill library** (`agents/skill.py`): each verified program is stored as
  executable code + an LLM-generated description, embedded into a Chroma
  vectordb (`add_new_skill`, `:61-100`); `retrieve_skills(query)` does
  similarity search top-k (`:114-127`) and injects the retrieved code into the
  action agent's system prompt. **The library IS retrieval-augmented executable
  memory** — directly analogous to our `my.*` verb corpus + the program graph.

**STEAL:** (a) **the success-gated admission** — a candidate memory design enters
the archive ONLY after a fresh agent provably succeeds with it (Voyager admits a
skill only after the program runs and the critic confirms); (b) **critic judges
observed state, not the agent's self-report** → our judge reads the *measured*
retrieval result from the DB, never the candidate-agent's prose; (c) **skill =
executable code + embedded description, retrieved by similarity** — our evolved
store/retrieve verbs persist exactly this way already (code-as-data); (d) the
**execute→verify→critique inner loop** giving each candidate a few refine
iterations before scoring (the survey's cold-start mitigation).

**AVOID:** the critic grades *its own task on shared context* (self-grading bias
the survey named). For us: keep the child genuinely cold (no probe answers in its
context) and keep the **judge (Gemini-Flash, diagnoses) separate from the
selector (measured held-out number)**.

**Test-env?** It's Minecraft/Mineflayer — not reusable as a harness, but the
*loop architecture* is the most directly transferable thing in this whole survey.

### 3. funsearch (`funsearch/implementation/`) — islands + program-DB + execution evaluator

**Mechanism.**
- **`programs_database.py`** — the QD archive. `ProgramsDatabase` holds
  `num_islands` `Island`s (`:88-94`). Each island clusters programs by a
  **signature = the tuple of per-test scores** (`_get_signature`, `:54`); within
  a cluster, sampling **prefers shorter programs** (`Cluster.sample_program`,
  `:292-297` — anti-bloat). `get_prompt` (`:205`) samples clusters by
  softmax-over-score with a temperature schedule (`:211-215`) → builds a
  few-shot prompt of *best→worst* prior implementations and a header for the next
  version (`_generate_prompt`, `:236-271`). **`reset_islands`** (`:147-167`)
  periodically **kills the weaker half of islands and reseeds them from a
  survivor's best** — the diversity-preservation mechanism.
- **`evaluator.py`** — the gate. `Evaluator.analyse` (`:135`) compiles the LLM
  sample into a program (`_sample_to_program`, trims to a valid AST, `:46-65`),
  runs it on each test input **through a `Sandbox`** (`:147`), and registers it
  **only if `runs_ok and not _calls_ancestor and output is a number`**
  (`:149-154`). **The fitness is code execution, full stop** — `Sandbox.run` is
  abstract and the docstring is *"Must provide a sandbox for executing untrusted
  code"* (`evaluator.py:88-100`). That `NotImplementedError` is exactly the seam
  where **`mvm`/`inspect-ai` plug in**.

**STEAL:** (a) **islands + periodic reset-weaker-half-from-survivor** (`:147-167`)
— the cheapest possible QD-archive that beats a single mutable baseline; (b)
**cluster by score-signature, sample shorter-program** (anti-bloat == our token
budget pressure); (c) **few-shot prompt of ranked prior winners** to the proposer
(ADAS's archive-augmentation, concretely); (d) **the `Sandbox` seam** — funsearch
deliberately leaves untrusted-code execution as a pluggable boundary; that is
literally our `mvm` integration point.

**AVOID:** fitness is a single scalar from a *trusted, deterministic* test (a math
score) — there's no LLM judge, no anti-cheat, because a cap-set program can't lie.
Our memory agent CAN (store the key), so we add the hidden-checker + judge-veto
layer funsearch doesn't need.

**Test-env?** The math problems (cap_set, bin_packing) aren't our domain, but
`programs_database.py` is ~300 lines and is the cleanest island/archive impl to
port to Clojure.

### 4. openevolve (`openevolve/openevolve/`) — AlphaEvolve OSS: cascade + MAP-Elites

**Mechanism (skim).** `evaluator.py` imports `subprocess` (`:10`) and runs
candidate programs out-of-process with timeouts; `controller.py` drives the
loop; `database.py` is the MAP-Elites/island program database; `novelty_judge.py`
is an LLM novelty scorer; `process_parallel.py` parallelizes evaluation. The
README documents **cascade evaluation** (cheap test stage → expensive stage only
for survivors) and **diff-based edits**. This is funsearch + (MAP-Elites feature
grid, cascade, LLM-ensemble proposer, novelty).

**STEAL:** (a) **cascade evaluation** — a cheap static/tiny-probe check before
spending a full fresh-agent spawn (the survey's #7 anti-cheat/efficiency lever);
(b) **MAP-Elites feature grid** (diversity on chosen behavior axes, e.g.
storage-size × query-latency) layered over islands; (c) **`novelty_judge`** as a
separate "is this design interestingly different" signal if the proposer
plateaus.

**AVOID:** it's a big general framework — don't adopt openevolve wholesale; lift
the cascade + MAP-Elites *ideas* into our funsearch-style port. Its subprocess
isolation is weaker than a microVM (same process tree, host fs visible) — we
replace it with `mvm`.

**Test-env?** `examples/` has worked evolution tasks; reference only.

### 5. ADAS (`ADAS/`) — meta-agent programs agents; archive-augmented

**Mechanism (skim).** "Meta Agent Search": a meta-agent writes new agent
*programs* in code; discovered agents go in an **archive** that's fed back into
the meta-prompt. Turing-complete design space (real code, not knobs).

**STEAL:** the **archive-of-prior-discoveries in the proposer's context** (we get
this for free via the program graph) + **don't over-constrain the action space**
— our REPL substrate naturally allows real code, which is ADAS's whole thesis and
EvolveMem's main limitation.

**AVOID:** ADAS evolves *agent control flow*, not memory; relevant as a design-
space-breadth argument, not a loop to fork.

**Test-env?** ARC/eval domains; reference only.

### 6. mem0 / letta / generative_agents — the store/retrieve OP design space

Not loops — these define **what operations a memory API should expose** (so our
evolving agent has a vocabulary to rearrange):

- **mem0** (`mem0/memory/main.py`): the op set is **`add` (`:716`), `search`
  (`:1326`), `get_all` (`:1202`), `update` (`:416`/`:1762`), `delete` (`:1803`),
  `delete_all`, `reset` (`:2048`)** — each with sync + async faces. `add` runs an
  LLM **fact-extraction + dedup/consolidation** pass (not raw append); `search`
  is embedding + optional graph. **This `add/search/update/delete/consolidate`
  set maps cleanly onto our `transact!`/`query`/`retract` + a "consolidate"
  verb** — it's the MemEvolve `encode/store/retrieve/manage` four-stage space
  made concrete.
- **letta/MemGPT**: tiered memory (core/recall/archival) with the LLM issuing
  paging calls — the "manage/forget under pressure" verbs. Steal the *tiering*
  idea as one design the evolving agent could discover (don't hard-build it).
- **generative_agents**: retrieval = weighted **recency·relevance·importance**.
  Steal as the **strong default baseline-0 to beat** (seed the archive with it +
  our existing `my.kb/remember`).

**AVOID:** all three are hand-built fixed structures (the survey's Area-3 point) —
they're the *baselines*, not the evolver. Don't import their frameworks; import
their **op vocabulary**.

---

## Isolation section — the cheat-proof "REPL-in-microVM, checker-outside" harness

### What `mvm` gives us (concrete API, read from source)

The Python SDK (`mvm/sdks/python/mvm/_sandbox.py`) is the surface:

```python
import mvm
# host process — the CHECKER + ANSWER KEY live HERE, never enter the VM
with mvm.Sandbox.create(image="clojure-node-pod", ttl="10m") as sb:   # _sandbox.py:964
    sb.copy_in("probe_facts.edn", "/work/facts.edn")                  # host→guest, :1138
    r = sb.exec("node", "pod-eval.js", "/work/agent-task.clj",        # one-shot, :1082
                timeout=120)
    # r.exit_code / r.stdout / r.stderr  (ExecResult, :90)
    sb.copy_out("/work/agent-answers.edn", "answers.edn")             # guest→host, :1160
# VM torn down on __exit__ (kill → mvmctl machine stop, :1204)
score = grade(read("answers.edn"), ANSWER_KEY)   # HOST-side. Agent never saw ANSWER_KEY.
```

Why this is cheat-proof, traced to mvm's security claims (`mvm/CLAUDE.md`
§"Security model"):

- **The agent can't read the answer key / probe set.** Claim 1: *"No host-fs
  access from a guest beyond explicit shares."* Only what we `copy_in` /
  `files.write` is in the guest. The answer key never gets copied in — it stays in
  the host python that holds the `Sandbox` handle. Different address space, no
  channel.
- **The agent can't fake the score log or edit the detector.** The grader runs in
  the **host** process AFTER `exec` returns `ExecResult`. The guest produces only
  `answers.edn`; scoring/diagnosis/hack-detection are host code the guest cannot
  reach. This is the structural fix for the Darwin-Gödel "faked test logs + edited
  the detector" failure — the detector is *physically outside* the box, not a
  function the agent can rewrite.
- **`exec` is dev-tier and refuses prod templates** (`SandboxDevOnly`,
  `_sandbox.py:170`, claim 4/15): the interactive `do_exec`/console path only
  exists in dev builds. For the eval we WANT a dev template (we need to run the
  REPL); the point is the guest is still vsock-only, no SSH (`mvm/CLAUDE.md`
  "No SSH in microVMs, ever").
- **Time/output bounds:** `exec(timeout=…)` (`_sandbox.py:1082`) + the per-VM TTL
  (`DEFAULT_TTL_SECONDS=1800`, `:136`) reap a runaway candidate.

### inspect-ai as the host harness (where we put the loop)

`inspect-ai/src/inspect_ai/util/_sandbox/environment.py` defines the
`SandboxEnvironment` ABC we host the loop on:

- `async exec(cmd, input, cwd, env, user, timeout, …) -> ExecResult[str]`
  (`environment.py:104`) — same shape as mvm's `aexec`.
- `write_file(file, contents)` (`:153`), `read_file(file, text)` (`:180`) — the
  copy_in/copy_out analogues, with a **per-sample filesystem context** ("copy
  samples files into and resolve relative paths", `:92-97`).
- Output/read limits via `INSPECT_SANDBOX_MAX_EXEC_OUTPUT_SIZE` /
  `…_MAX_READ_FILE_SIZE` (`:121`, `:184`) and `limits.py`.
- A **provider registry** (`registry.py`) — inspect ships `docker`/`local`/remote
  providers; we **register an `mvm` provider** (subclass `SandboxEnvironment`,
  back `exec`/`read_file`/`write_file` with `mvm.Sandbox`). Then inspect's
  datasets + scorers + `Task`/`solver`/`scorer` flow drive everything, and
  `inspect-evals` (100+ tasks) become available probes.

So: **inspect-ai owns the eval loop, datasets, and scoring; `mvm` is the
isolation provider plugged into its sandbox seam.** We do not build a harness from
scratch.

### macOS support story (our pod is darwin)

- **macOS 26+ Apple Silicon → Vz backend** (Apple Virtualization.framework, ships
  with the OS, **no libkrun/brew needed**) — `mvm/README.md:285`,
  `mvm/CLAUDE.md` "Builder backend selection". Real hardware-virtualized microVM.
- **macOS 13-25 Apple Silicon → libkrun** (`brew install slp/krun/libkrun
  libkrunfw gvproxy`) — `mvm/CLAUDE.md` "Host dependencies (macOS)".
- **NOT Firecracker on macOS:** *"macOS can't run live Firecracker microVMs
  natively"* (`mvm/README.md:316`). Firecracker + the Tier-1 hardened posture
  (dm-verity sealed rootfs claim 3, seccomp claim 1, fuzzed framing) are
  **Linux+KVM only** (Hetzner CCX/CAX via `ops/hetzner/cloud-init.yaml`).
- **Verdict for us:** the **isolation property we need (guest can't reach host fs
  / host process) holds on the Vz/libkrun macOS backend** — that's enough to make
  the answer-key-outside, checker-outside design cheat-proof on the dev pod. The
  hardened tamper-proof-rootfs posture is a Linux-box upgrade we take only if we
  need defense against a guest that tries to corrupt the *image* (not a concern
  when the image is ours and ephemeral per candidate).

### A subtlety worth flagging

mvm is **Rust + Nix-flake images** (`mkGuest`). Standing up a `clojure-node-pod`
guest image is real work (a Nix flake bundling Node + our pod bundle + a REPL
entrypoint). The cheaper *first* spike can use **inspect-ai's `docker` provider**
(a Dockerfile with our pod) for the isolation property (separate container, host
fs not mounted, checker host-side) and **graduate to the `mvm` provider** for the
stronger microVM boundary once the loop is proven. Same `SandboxEnvironment` API,
swap the provider string — that's the reuse win.

---

## Reuse map — which vendored benchmark gives scenario + scoring scaffolding

| Repo | Scenario shape | Scoring pattern | Borrow for us |
|---|---|---|---|
| **re-bench** (METR) | 7 AI-R&D self-improvement tasks; agent edits code in a `base_dir`, score = perf metric (`ai_rd_*/`) | **`metr.task_protected_scoring`** — encrypted/zipped answer keys (`extract_protected_zips.py`), `strip_score_information` flag, "score on a **held-out** dataset of 175 problems" (`README.md:66`) | **THE anti-cheat scoring primitive**: protected/encrypted answer key + held-out set + strip-score-info so the agent can't read its own grade. Closest to self-improvement. |
| **inspect-evals** | 100+ ready `Task`s (gaia, swe_bench, cybench, core_bench, …) under `src/inspect_evals/` | inspect-ai scorers + datasets | The **harness host** + a library of generalization probes (run an evolved design against a *different* task family = the cross-transfer overfit alarm). |
| **mle-bench / terminal-bench / cybench / tau2-bench** | Kaggle ML / terminal-task / cyber / tool-agent scenarios | per-bench scorers; several already inspect-ai or Docker-sandboxed | Reference for *sandboxed-agent-with-scorer* packaging; tau2-bench is the closest tool-use analog (survey's note). |
| **Evo-Memory** (if it ships) | streaming task sequences, store-evolve-retrieve, easy→hard + hard→easy orderings | memorization-detection via ordering | **Would be the ready fitness harness** — clone it the moment code appears. Currently a gap. |

**Recommendation:** host on **inspect-ai**, steal **re-bench's protected-scoring**
for the answer-key handling, seed probes from **inspect-evals**, and keep an eye
out for **Evo-Memory's** benchmark.

---

## Scenario-design guide — store/retrieve test scenarios (with anti-cheat baked in)

Each scenario = (facts to store, a *separate* novel-question battery, a grading
key that stays host-side). Anti-cheat properties from the survey are annotated.

1. **Store-then-retrieve-across-restart (the core durability probe).** Turn 1:
   the cold child agent is handed N facts (some with provenance) and uses the
   candidate store/retrieve design to persist them. **Restart the pod / tear down
   + recreate the VM.** Turn 2: a *fresh* child (cold context) gets novel
   questions and must reconstitute + answer from the persisted store. *Anti-cheat:
   the win condition is recall after a real restart (survey §"restart/continuity"),
   so "keep it all in context" can't win — the context is gone.*
2. **Memory-pressure + distractors.** Mix M irrelevant/contradictory facts into
   the N relevant ones, and **cap the storage budget in tokens** (our token rule).
   Questions require the relevant subset. *Anti-cheat: "store everything verbatim"
   overflows the budget and drowns retrieval in distractors → the design must
   actually encode/select (survey playbook #4).*
3. **Novel-query generalization (synthesis, not lookup).** Questions require
   **joining 2-3 stored facts** or paraphrase the stored wording; ask in a
   **different order** than facts were given. *Anti-cheat: defeats verbatim
   key-matching and answer-key storage; mirrors Evo-Memory's easy→hard/hard→easy
   ordering + EvilGenie's "heuristic solutions still pass holdouts" warning — so
   pair with the judge-veto below.*
4. **Cross-agent handoff.** Agent A stores under the candidate design; **Agent B
   (different id, cold context) retrieves** to answer. *Anti-cheat: tests that the
   design's value is in the persisted DATA, not in A's residual context — and
   exercises our reactive-context "the section function sees the whole core"
   property.*
5. **Poison/stability round (governance, from SSGM).** Inject a few adversarial/
   corrupt facts; a good design must not let them degrade later retrieval. *Anti-
   cheat: catches "accumulate everything" designs that look great until a bad
   entry tanks them; rewards quarantine/provenance.*

**The held-out + judge-veto wrapper for every scenario (survey playbook #1-3,9):**
- **Select on the measured number** (fresh child's accuracy on the *held-out*
  battery — questions the candidate-design agent never saw). The
  candidate-proposer sees only the *failure diagnosis*, never the probe questions,
  answer key, or grading code (all host-side, all inside the inspect-ai
  driver — the agent runs in the VM).
- **Gemini-Flash judge runs host-side as a VETO, not a score:** its only job is
  *"did this design cheat?"* — did it store the literal answer, read a probe file,
  bypass the budget, hardcode outputs? (EvilGenie: LLM judge ≈ 0 false negatives.)
  A veto kills the candidate regardless of its number.
- **Cross-transfer alarm:** a design that records on battery-A but *regresses* on
  a different scenario family (or an inspect-evals task) is overfit → demote
  (EvolveMem positive-transfer check).
- **Tightly specify the goal** (EvilGenie: hacking 10-40× rarer on unambiguous
  specs): "answer these K novel questions correctly after a restart, under a T-
  token store budget" — crisp and measurable.

---

## Recommended minimal spike architecture (grounded in what exists)

**One sentence:** *Voyager's success-gated self-verifier loop + EvolveMem's
elitist-diagnose-revert engine, over a funsearch islands archive, hosted in
inspect-ai with `mvm` (or Docker first) as the cheat-proof isolation provider, on
our datahike/`register!` substrate.*

Concretely, fork/assemble:

1. **Engine — fork `EvolveMem/evolvemem/evolution.py` `EvolutionEngine.evolve`**
   structure: keep the round loop, the **elitist accept/revert** (`:510-577`), the
   **`attempt_history`** (`:528`), the **diagnose-after-decision** ordering
   (`:609`), and the **meta-explore-on-stagnation** (`:684`). Replace its
   `RetrievalConfig` action space with **a candidate = a Clojure
   store/retrieve design** (registered schema + `transact!`/`query` verbs as
   code-as-data), and its `_evaluate_qa` with ↓.
2. **Fitness — Voyager's mechanism (`voyager.py:203-285`, `critic.py`):** spawn a
   **fresh cold child agent** in isolation, run scenario 1-5, and the
   **measured held-out accuracy after restart** is the score. Admit to the archive
   only on success (Voyager `:353-354`). The Gemini-Flash **critic/judge is a
   host-side veto**, separate from the selector.
3. **Archive — port funsearch `programs_database.py`** (~300 lines) to Clojure:
   **islands + reset-weaker-half-from-survivor** (`:147-167`), cluster by
   score-signature, **prefer shorter (lower-token) designs** (`:292-297`). Add
   openevolve's **cascade** (cheap static check → tiny-probe → full fresh-agent
   spawn) and seed island-0 with our existing `my.kb/remember` + the
   generative_agents recency·relevance·importance baseline.
4. **Isolation — inspect-ai `SandboxEnvironment` host + an `mvm` provider.** The
   driver (selector + answer key + judge + scorer) is host-side python; the child
   agent + only the scenario's facts live in the VM via `copy_in`/`exec`/
   `copy_out` (`_sandbox.py:1082/1138/1160`). **Spike first with the Docker
   provider** (cheaper to stand up), graduate to `mvm`/Vz on Apple Silicon for the
   microVM boundary, and run on a Linux+KVM box only if we need the dm-verity
   hardened posture.
5. **Anti-cheat — re-bench's protected scoring discipline:** answer keys
   encrypted/host-side (`strip_score_information`), held-out battery, judge-veto,
   cross-transfer demotion. Lineage/archive (DGM) so a poisoned branch is
   auditable + revertable.

**First milestone (smallest honest slice):** scenario 1 (store-then-retrieve-
across-restart) only, Docker isolation, a 2-design archive (baseline-0 =
`my.kb/remember` vs one LLM-proposed variant), select on measured restart-recall,
Gemini-Flash veto for "stored the answer key." Prove the loop accretes or reverts
correctly *once* before adding islands, cascade, mvm, and the other scenarios.

---

## Honest gaps / caveats

- **Evo-Memory benchmark + MemEvolve EvolveLab code were not findable** as public
  repos at clone time — only EvolveMem (inside SimpleMem) ships. If Evo-Memory
  lands, it likely *is* a drop-in fitness harness; re-check.
- **`mvm` guest image is real work** (a Nix `mkGuest` flake bundling Node + the
  pod). The Docker-provider-first path de-risks this; don't let the microVM image
  block the loop spike.
- **EvolveMem read was source + README**, not a live run (no LoCoMo data / API
  key wired). The control-flow citations are from reading `evolution.py` /
  `diagnosis.py` directly; the reported +25.7% numbers are their README's claim,
  unverified by us.
- **openevolve/ADAS/letta were skimmed, not deep-read** — cascade/MAP-Elites and
  the op-vocabulary points are from README + signatures, sufficient for the
  "what to steal" call but not a line-by-line audit.
- **Isolation verdict is property-level, not a live mvm boot** — I read the SDK +
  security claims, did not boot a microVM on this host. The macOS-Vz support is
  documented in `mvm/CLAUDE.md`/`README.md`; a `mvmctl doctor` on the target box
  is the live confirmation step before committing to the mvm provider.
