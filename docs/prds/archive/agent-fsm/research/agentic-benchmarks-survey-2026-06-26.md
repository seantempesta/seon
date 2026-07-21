---
type: research
status: active
tags: [research, agent]
---

# Agentic AI benchmark / test-harness survey — 2026-06-26

Deep survey of the agentic-AI benchmark landscape (the suites the big labs and
serious research groups release and report scores against), cross-referenced
against Seon's own internal "gym" (`test/seon/gym/`). Goal: inform Seon's
lean-context tuning, read the real-world agent task distribution off these
benchmarks, and assess whether a Seon agent could be benchmarked against any of
them. The companion deliverable is 20 vendored submodules under `reference-code/`
(see [Vendored repos](#vendored-repos)).

## TL;DR

- **The landscape splits into five buckets:** (1) **coding/SWE** —
  patch-a-repo-and-run-the-hidden-tests (SWE-bench family, Aider polyglot,
  Commit0, SWE-Lancer, Terminal-Bench, MLE-bench); (2) **tool-use / function
  calling / multi-turn assistant** (tau/tau2-bench, BFCL, ToolBench,
  AgentBench); (3) **web / computer use** (WebArena, OSWorld, WebVoyager,
  Online-Mind2Web, AndroidWorld, BrowserGym); (4) **general long-horizon
  assistant** (GAIA, AssistantBench); (5) **frontier-autonomy / safety**
  (METR RE-Bench, Cybench). Cutting across all of them are **eval harnesses**
  (Inspect AI + inspect_evals, OpenAI Evals, OpenHands, SWE-agent).
- **The dominant verification pattern is execution-based, not string-match.**
  Almost every serious suite scores by *running code / inspecting world-state*:
  unit-test pass/fail (SWE-bench, Aider, Commit0, SWE-Lancer, Terminal-Bench),
  DB/system-state inspection (tau-bench, AndroidWorld, WebArena), a scoring
  function (RE-Bench), or flag-match (Cybench). LLM-as-judge is used only where
  there is no executable oracle (WebVoyager, parts of GAIA, ToolBench, Online-
  Mind2Web). **This is exactly Seon's gym model** — datalog predicates over the
  post-run datahike store + a *separate* LLM-judge axis.
- **The closest external analog to Seon's gym is tau2-bench**: multi-turn,
  tool-using agent + an LLM-simulated user, scored by **database-state
  equality** against a goal state plus a `pass^k` reliability metric. Seon's gym
  is a Clojure-native cousin: tool calls = `eval` of Clojure verbs, the "world"
  = a scratch datahike `:memory` store, the goal-check = datalog over that store.
- **Isolation pattern Seon should recognize:** the field standardized on
  **Docker-per-task** (SWE-bench, Terminal-Bench, SWE-Lancer, MLE-bench,
  Cybench, Inspect, OpenHands) or a **VM-per-task** (OSWorld, AndroidWorld).
  Seon achieves the same hermeticity far more cheaply with a **scratch
  `:memory` conn swapped in per run** — sub-second, no container. That is a
  genuine architectural advantage for fast context-tuning sweeps.
- **Task distribution (the real-agent-use proxy):** weighting by task count and
  lab attention, the mass is **bug-fixing / repo-level code edits**, then
  **multi-turn tool-use customer-assistant flows**, then **web/computer GUI
  automation**, then **terminal/sysadmin ops**, then **data/ML pipelines**, then
  **long-horizon research**. For a headless Clojure pod the runnable subset is
  coding + tool-use + terminal + data; GUI/computer-use is out of lane.
- **Recommendation headline:** adapt **tau-bench-style multi-turn DB-state
  tasks** and **Commit0/Aider-style spec→implement→test tasks** into the Seon
  gym (both map onto the existing predicate engine with near-zero new
  machinery), and target **Inspect AI** as the bridge if we ever want to run a
  Seon agent against an external suite — its `Task / Solver / Scorer` shape is a
  near-isomorphism of Seon's `scenario / loop / predicate` shape.

## Comparison table

| Benchmark | Bucket | Tasks | Env setup | Scoring | Seon relevance |
|---|---|---|---|---|---|
| SWE-bench (+ Verified/Lite/Multimodal/Live) | Coding | 2,294 full / 500 Verified / 300 Lite / ~617 MM / 1,319 Live | Docker per repo-commit (~120GB cache) | `FAIL_TO_PASS` + `PASS_TO_PASS` pytest deltas | High |
| Aider polyglot | Coding | 225 (hardest Exercism, 6 langs) | local per-exercise dir | unit tests pass; 2 attempts; edit-format measured | High |
| Commit0 | Coding | 54 Python libs from spec | Docker per lib | unit-test pass-rate + lint + types; interactive feedback | High |
| SWE-Lancer | Coding (economic) | ~1,488 Upwork tasks ($1M) | unified Docker image | end-to-end tests (IC) + manager-choice match | Medium |
| Terminal-Bench | Terminal | ~100 (TB1) / 89 hard (TB2) | Docker + tmux sandbox | per-task test scripts (resolution rate) | High |
| MLE-bench | Data/ML | 75 Kaggle competitions | Docker; large datasets | medal thresholds vs human leaderboards | Medium |
| tau-bench / tau2-bench | Tool-use assistant | retail+airline (~165) → +telecom (tau2) | python domain sim (DB + tools) | DB-state equality + reward; `pass^k` | Very High |
| BFCL (Berkeley FCL) | Function calling | thousands of cases (v1–v4) | python; some executable APIs | AST match + executable + relevance/irrelevance | High |
| ToolBench / ToolLLM | Function calling | 16,464 RapidAPI APIs | RapidAPI sandbox | ToolEval: pass-rate + win-rate (LLM judge) | Low–Medium |
| AgentBench | Multi-env agent | 8 environments | per-env (OS/DB/KG/games/web) | per-env success | Medium |
| GAIA | General assistant | 466 (3 levels) | tools + files, real web | exact-match; 300 held-out | High |
| AssistantBench | General web | 214 time-consuming tasks | live web | automatic answer match | Medium |
| WebArena | Web | 812 (241 templates) | 4 self-hosted Docker sites | programmatic functional correctness | Medium |
| VisualWebArena | Web (visual) | 910 | self-hosted sites + images | functional + visual checks | Low |
| OSWorld | Computer use | 369 (361 runnable) | real Ubuntu/Win VM | execution-based per-task scripts | Low |
| WebVoyager | Web (live) | 643 / 15 sites | live websites, screenshots | GPT-4V-as-judge (85% human agreement) | Low |
| Online-Mind2Web | Web (live) | 300 / 136 sites | live websites | WebJudge auto-eval (agent-as-judge) | Low |
| AndroidWorld | Mobile | 116 / 20 apps | Android emulator | reward via adb system-state | Low |
| METR RE-Bench | Frontier R&D | 7 environments | GPU box per env | continuous scoring fn vs 71 humans | Medium |
| Cybench | Security | 40 CTFs (+subtasks) | Docker + Kali | flag-match (+ subtask credit) | Medium |
| **Inspect AI / inspect_evals** | **Harness** | 200+ bundled evals | Docker/k8s sandbox | `Scorer` abstraction | **Very High (bridge)** |
| OpenAI Evals | Harness | registry of evals | local; Git-LFS data | Completion-Function Protocol | Medium |
| OpenHands / SWE-agent | Harness/agent | (runner) | Docker workspace | wraps SWE-bench etc. | Medium |

(Where two sources disagreed on a number, both are noted in the per-benchmark
section below.)

## Coding / SWE benchmarks

### SWE-bench (and Verified / Lite / Multimodal / Live)

- **Org/paper:** Princeton NLP + Stanford, "SWE-bench: Can Language Models
  Resolve Real-world GitHub Issues?" ICLR 2024 — <https://arxiv.org/abs/2310.06770>.
  Site: <https://www.swebench.com>. Repo: <https://github.com/SWE-bench/SWE-bench>
  (formerly `princeton-nlp/SWE-bench`).
- **What it tests:** given a real GitHub issue + the repo snapshot at the
  pre-fix commit, produce a patch that resolves the issue. Pure repo-level
  bug-fix / small-feature work.
- **Task count + format:** full test set **2,294** issue–PR pairs from 12
  popular Python repos. Subsets: **Verified** = 500 human-filtered "solvable"
  instances (the de-facto headline number labs report); **Lite** = 300 cheaper
  instances; **Multimodal** = ~617 instances (517 test) from 17 JavaScript
  libraries, each with at least one image in the problem/test (UI/diagram/visual
  diff) — "Do AI Systems Generalize to Visual Software Domains?"
  <https://arxiv.org/abs/2410.03859>; **Live** = 1,319 instances from 93 repos,
  restricted to issues created Jan-2024→Apr-2025 and refreshed monthly to fight
  pretraining contamination, <https://arxiv.org/pdf/2505.23419>.
- **Environment:** **one Docker image per repo-commit**, built by the harness;
  the official note says "requires at least 120GB free space for cache."
- **Scoring (the canonical pattern worth internalizing):** each instance ships
  two test sets — **`FAIL_TO_PASS`** (tests that fail before the gold PR and must
  pass after) and **`PASS_TO_PASS`** (tests that pass before and must still
  pass). A prediction "resolves" the instance iff *all* FAIL_TO_PASS pass *and*
  all PASS_TO_PASS still pass, after applying the model patch and running the
  repo's test command in the container. No LLM judge anywhere.
- **Use-case signal:** the single most-reported agentic number in the industry.
  Confirms that "fix a bug in an existing codebase, verified by its own test
  suite" is the canonical commercial agent task.
- **Seon relevance:** the **FAIL_TO_PASS / PASS_TO_PASS** model is *exactly*
  Seon's gym predicate philosophy (assert post-condition mechanically, never on
  output strings). To run Seon against SWE-bench the pod would need a
  file-edit + bash + pytest tool surface (it has `seon.agent.fs` +
  `seon.agent.search`; it lacks a generic non-Clojure subprocess/test runner) —
  doable but a real lift. The cheaper win is to **steal the dual-test-set idea**
  for Seon's own fn-authoring scenarios (a "must now pass" + "must still pass"
  pair over the agent's `deftest` rows).

### Aider polyglot benchmark

- **Org/repo:** Aider (Paul Gauthier). Repo: <https://github.com/Aider-AI/polyglot-benchmark>;
  writeup <https://aider.chat/2024/12/21/polyglot.html>; harness lives in the
  main aider repo's `benchmark/`.
- **What it tests:** 225 of the *hardest* Exercism exercises (out of 697) across
  **C++, Go, Java, JavaScript, Python, Rust** — algorithmic coding + the model's
  ability to **emit a correct file edit** in the requested diff format and to
  self-correct.
- **Format/scoring:** two attempts per problem; on failure the model is shown
  the failing unit-test output and tries again; success = the exercise's unit
  tests pass. Separately tracks **edit-format adherence** (did the model produce
  a well-formed diff the harness could apply). Current leaderboard topped ~88%.
- **Seon relevance:** the "show the failing test, let it retry" loop is the
  retry/repair behavior Seon wants to elicit. Edit-format adherence is the
  analog of Seon's `clojure_replace` structural-edit discipline. Polyglot
  exercises are small + self-contained — the easiest external coding tasks to
  port into a Seon "write-a-fn-with-a-test" gym scenario.

### Commit0

- **Org/paper:** "Commit0: Library Generation from Scratch"
  <https://arxiv.org/abs/2412.01769>; repo <https://github.com/commit-0/commit0>;
  site <https://commit-0.github.io>.
- **What it tests:** implement a whole Python library **from a specification +
  a test suite**, from scratch. 54 libraries (ML, networking, databases,
  visualization). The agent gets the API spec + docs + an interactive unit-test
  suite + lint + type-check, and must produce a passing implementation.
- **Scoring:** unit-test pass-rate per library, plus lint + type-check; the
  environment streams static-analysis and execution feedback (interactive, not
  one-shot).
- **Seon relevance — HIGH and conceptually central.** Commit0 is the external
  benchmark closest to Seon's *thesis* ("agents author and persist their own
  functions/toolkit, verified by tests"). "Spec in → implementation that passes
  the provided tests" is precisely the loop Seon wants agents to run when they
  build their own harness. A Seon gym scenario seeded with a spec doc + a
  `deftest` and scored on pass-count is a direct port.

### SWE-Lancer

- **Org/paper:** OpenAI, "SWE-Lancer: Can Frontier LLMs Earn $1 Million from
  Real-World Freelance Software Engineering?" <https://arxiv.org/abs/2502.12115>;
  repo <https://github.com/openai/SWELancer-Benchmark>.
- **What it tests:** ~1,488 real Upwork freelance tasks (the paper frames "1,400+",
  $1M total payout) on the Expensify codebase — **independent contributor (IC)
  tasks** ($50 bug → $32k feature) and **manager tasks** (choose between
  technical proposals).
- **Environment/scoring:** a unified Docker image; IC tasks graded by
  **end-to-end tests triple-verified by engineers**; manager tasks graded against
  the choice the *original* hired engineering manager made. Public split =
  **SWE-Lancer Diamond**. Maps performance to dollars.
- **Seon relevance:** the **economic-value framing** is a strong north star for
  "what agents will actually be paid to build." End-to-end (not unit) tests +
  the manager-decision task are both interesting predicate kinds; the manager
  task in particular is a *judgement* task that Seon's LLM-judge axis could grade.

### Terminal-Bench

- **Org/repo:** Stanford + Laude Institute; repo
  <https://github.com/laude-institute/terminal-bench> (the org now shows as
  `harbor-framework`); the runner is **Harbor**. ICLR 2026 paper.
- **What it tests:** end-to-end tasks an engineer would do **in a terminal** —
  "data processing, games, debugging, system admin, scientific computing,
  software engineering, ML, and security" (Terminal-Bench Pro taxonomy).
- **Task count:** TB **1.0** ~100 tasks (beta); TB **2.0** = **89 hard,
  human-verified** tasks in containers; TB-Pro = 400 (200 public + 200 private).
- **Environment:** "the harness connects a language model to a sandboxed terminal
  environment … a dataset of tasks + an execution harness that connects a
  language model to our terminal sandbox" — Docker container + tmux session per
  task.
- **Scoring:** per-task **test scripts** run in the container after the agent
  finishes → task-resolution rate.
- **Seon relevance — HIGH on the "agent builds its own tools" axis.** The terminal
  is the most general tool surface, and the "give the agent a shell, grade by a
  test script" pattern is the cleanest fit if Seon ever exposes a bash verb.
  Strong source of *terminal-ops* task ideas (the bucket Seon currently doesn't
  exercise at all).

### MLE-bench

- **Org/paper:** OpenAI, "MLE-bench: Evaluating Machine Learning Agents on
  Machine Learning Engineering" ICLR 2025 <https://arxiv.org/abs/2410.07095>;
  repo <https://github.com/openai/mle-bench>.
- **What it tests:** 75 curated Kaggle competitions — the *whole* ML pipeline
  (data prep, architecture, hyperparameter tuning, produce a valid submission).
- **Scoring:** graded against Kaggle's real leaderboards — **medal thresholds**
  (bronze/silver/gold) give a human-calibrated bar. Best reported setup
  (o1-preview + AIDE scaffolding) reached ≥bronze on 16.9% of competitions.
- **Environment note:** Docker; **datasets are large** (the competition data must
  be downloaded; full set is hundreds of GB → multi-TB). The vendored repo is
  only the harness/code (~5MB) — data is fetched separately.
- **Seon relevance:** Medium. Long-horizon, self-directed, file-heavy. Mostly out
  of lane for a Clojure pod, but the **human-leaderboard-calibrated scoring** is
  a nice idea (score relative to a human baseline, not absolute).

## Tool-use / function-calling / multi-turn assistant

### tau-bench / tau2-bench (THE closest analog)

- **Org/repo:** Sierra. tau-bench: <https://github.com/sierra-research/tau-bench>
  ("A Benchmark for Tool-Agent-User Interaction in Real-World Domains",
  <https://arxiv.org/abs/2406.12045>). tau2-bench:
  <https://github.com/sierra-research/tau2-bench>.
- **What it tests:** a **multi-turn** conversation where the agent must use
  domain **tools** (APIs over a database) to satisfy a customer, *while* an
  **LLM-simulated user** supplies information and changes their mind. Domains:
  retail, airline (tau-bench); tau2 adds **telecom** + a "dual-control" mode
  (both agent and user can act) + voice. Policies/business-rules matter (the
  agent must follow a domain policy doc).
- **Task count:** retail ~115 + airline ~50 (tau-bench); tau2 extends with
  telecom and a `mock` domain.
- **Environment:** a python simulation — a domain database, a set of tool
  functions that read/write it, a policy document, and a user-simulator LLM.
- **Scoring (the key bit):** after the dialogue, compare the **database state**
  to the goal state (state equality), plus required-action/output checks. tau2
  emphasizes **`pass^k`** — run each task k times, report the fraction solved on
  *all* k tries — i.e., **reliability under stochasticity**, not a single pass.
- **Seon relevance — VERY HIGH.** This is Seon's gym in a different language:
  - tau "tools that mutate a DB" ≈ Seon agent `eval`'ing `seon.db` verbs.
  - tau "final DB-state equality" ≈ Seon's `:datalog` post-run predicates.
  - tau "LLM user simulator" ≈ a feature Seon's gym *lacks* and should add (today
    Seon scenarios are scripted single/multi-message, not an adaptive simulated
    user). Adding a user-simulator turn-driver is a high-value port.
  - tau `pass^k` ≈ **exactly** the lesson in
    `gym-findings-2026-06-26.md` ("2 of 4 axes flipped between identical runs …
    a single sweep is NOT a trustworthy signal … need pass-RATES over k reps").
    tau-bench independently arrived at the same conclusion; Seon should adopt
    `pass^k` as the headline gym metric.

### BFCL — Berkeley Function-Calling Leaderboard

- **Org/repo:** UC Berkeley (Gorilla project, Shishir Patil et al.). Repo
  <https://github.com/ShishirPatil/gorilla> (BFCL lives in
  `berkeley-function-call-leaderboard/`); blog
  <https://gorilla.cs.berkeley.edu/blogs/13_bfcl_v3_multi_turn.html>.
- **What it tests:** can a model **emit the correct function call(s)** given a
  prompt + a set of tool schemas. v1 single-turn AST + executable; v2
  enterprise/live data; **v3 multi-turn & multi-step**; later versions add
  agentic categories (web search, memory), and **relevance/irrelevance**
  detection (don't call a tool when none applies / do call when one does).
- **Scoring:** **AST match** (did it produce the right call structure) +
  **executable** (actually run the call against a real/mock API and check the
  result) + relevance accuracy. Plus cost/latency. Thousands of test cases.
- **Seon relevance — HIGH.** Seon agents *author* and *call* functions; BFCL's
  "did it pick the right function + args, and know when NOT to call" is directly
  relevant to Seon's "reuses-functions" and "reuses-schemas" axes. The
  **relevance/irrelevance** category maps onto Seon's "narrow question → no
  over-retrieval" scenario (`x12`). BFCL's AST-vs-executable split mirrors Seon's
  `:eval-count-matching` (structural) vs `:datalog` (outcome) split.

### ToolBench / ToolLLM

- **Org/paper:** OpenBMB, "ToolLLM: Facilitating LLMs to Master 16000+ Real-world
  APIs" ICLR 2024 spotlight <https://arxiv.org/abs/2307.16789>; repo
  <https://github.com/OpenBMB/ToolBench>.
- **What it tests:** instruction-following over **16,464 RapidAPI** REST APIs
  (49 categories), single- and multi-tool. Primarily a *training-data + eval*
  for general tool use.
- **Scoring:** **ToolEval** — pass-rate + win-rate, the win-rate judged by an LLM
  (ChatGPT) comparing two solution paths. Less execution-grounded than
  BFCL/tau-bench (many APIs are unstable/paywalled, so it leans on LLM judging).
- **Seon relevance — Low/Medium.** Useful as a catalog of real API categories
  (what people wire agents to), but the LLM-win-rate scoring is the *weaker*
  pattern Seon already does better with mechanical datalog. The dataset is large.

### AgentBench

- **Org/paper:** THUDM, "AgentBench: Evaluating LLMs as Agents" ICLR 2024
  <https://arxiv.org/abs/2308.03688>; repo <https://github.com/THUDM/AgentBench>.
- **What it tests:** LLM-as-agent across **8 environments**: operating system
  (bash), database (SQL), knowledge graph, digital card game, lateral-thinking
  puzzles, house-holding (ALFWorld), web shopping (WebShop), web browsing
  (Mind2Web-style).
- **Scoring:** per-environment success metric (task completion / reward).
- **Seon relevance — Medium.** The **OS** and **DB** environments are
  in-lane (bash command sequences; SQL query construction → Seon does datalog).
  Good source of "interact with a database to answer / mutate" task templates.

## General long-horizon assistant

### GAIA

- **Org/paper:** Meta-AI + HuggingFace, "GAIA: a benchmark for General AI
  Assistants" <https://arxiv.org/abs/2311.12983>; data
  <https://huggingface.co/gaia-benchmark>.
- **What it tests:** 466 human-authored questions that "require reasoning,
  multi-modality handling, web browsing, and generally tool-use proficiency."
  Conceptually simple for humans, hard for AI. Three difficulty levels (by number
  of steps/tools). Often accompanied by a file (image/spreadsheet/PDF).
- **Scoring:** **exact-match** of a short final answer (quasi string/number
  match) — cheap, unambiguous, no judge. 300 of 466 answers are held out to power
  the leaderboard. Reported gap: **humans 92% vs GPT-4+plugins 15%**.
- **Seon relevance — HIGH on flavor.** GAIA's tasks are *personal-assistant*
  questions over tools + files — the same register as Seon's gym scenarios
  (workouts, subscriptions, todos). The **exact-match-on-a-short-answer**
  scoring is a clean, judge-free pattern Seon could add for factual scenarios
  (assert the reply contains the one right number/value, mechanically). GAIA is
  also the most common agent eval bundled in harnesses (Inspect ships it).

### AssistantBench

- **Org/paper:** "AssistantBench: Can Web Agents Solve Realistic and
  Time-Consuming Tasks?" EMNLP 2024 <https://arxiv.org/abs/2407.15711>; site
  <https://assistantbench.github.io>.
- **What it tests:** 214 realistic, *time-consuming* web tasks (monitor
  real-estate, find nearby businesses) across 258 sites — open-web, multi-step.
- **Scoring:** automatic answer-matching (with partial credit for structured
  answers). Best models <26% — very hard.
- **Seon relevance — Medium.** Live-web, so not directly runnable headless, but a
  good example of **automatic answer scoring for open-ended factual tasks**.

## Web / computer use (the GUI-automation cluster)

These are the highest-task-count, highest-lab-attention web/computer-use suites.
For a headless Clojure pod they are mostly **out of lane** (they need a browser /
GUI / VM), but they define a major slice of "what people build agents for," and
their *scoring* designs are instructive.

### WebArena

- **Repo/paper:** <https://github.com/web-arena-x/webarena>,
  <https://arxiv.org/abs/2307.13854>. **812 tasks** from 241 templates over **4
  self-hosted, dockerized** sites (shopping/OneStopShop, a Reddit-like forum,
  GitLab, a CMS) + map/wiki. **Programmatic functional-correctness** eval
  (does the world reflect the intent — e.g. the order was placed), not
  trajectory match. GPT-4 ~14.4% vs human ~78%. **VisualWebArena**
  (910 tasks, adds visual grounding) is the sibling.
- **Seon relevance — Medium.** The **functional-correctness-over-final-state**
  scoring is philosophically identical to Seon's datalog-over-store. Self-hosted
  and reproducible is the gold standard for a web env.

### OSWorld

- **Repo/paper:** <https://github.com/xlang-ai/OSWorld>,
  <https://arxiv.org/abs/2404.07972> (NeurIPS 2024). **369 (361 runnable)** real
  computer tasks in a full **Ubuntu/Windows VM** — file I/O, multi-app workflows,
  web + desktop apps. **Execution-based per-task setup+eval scripts** that inspect
  the OS/file state. Best ~38% vs human ~72%. "OSWorld-Verified" is a cleaned
  rerun.
- **Seon relevance — Low** (needs a VM + GUI). Instructive for execution-based
  multi-app scoring.

### WebVoyager

- **Repo/paper:** <https://github.com/MinorJerry/WebVoyager>,
  <https://arxiv.org/abs/2401.13919> (ACL 2024). **643 tasks / 15 live sites**
  (Amazon, Apple, Google Flights, BBC…). Scored by **GPT-4V-as-judge** over the
  screenshot trajectory (85.3% agreement with human judges). Pioneered the
  multimodal LLM-judge for live web.
- **Seon relevance — Low.** Notable as the canonical *vision-judge* example
  (relevant to Seon only as "judge calibration matters" — Seon already calibrates
  its DeepSeek judge with good/bad probes).

### Online-Mind2Web

- **Repo/paper:** <https://github.com/OSU-NLP-Group/Online-Mind2Web>, "An Illusion
  of Progress? Assessing the Current State of Web Agents." **300 tasks / 136 live
  sites**, 3 difficulty tiers (Easy 83 / Medium 143 / Hard 74). Auto-eval via
  **WebJudge** (an agent-as-judge), validated against humans. Successor of
  Mind2Web (the original offline 2,000-task dataset). **Note: the vendored repo is
  ~5.4GB** — it bundles a large `data/` trajectory set.
- **Seon relevance — Low** (live web). The "illusion of progress" finding
  (benchmarks overstate agent ability; careful eval deflates scores) is a healthy
  caution for Seon's own metric-trust work.

### AndroidWorld

- **Repo/paper:** <https://github.com/google-research/android_world>,
  <https://arxiv.org/abs/2405.14573>. **116 tasks / 20 apps** on an Android
  emulator. **Reward from inspecting Android system-state via `adb`** (durable
  across task parameterizations) — explicitly *not* UI-diff or LLM-judge. Tasks
  are parameterized/templated (unlimited NL variations). Best agent ~30.6%.
- **Seon relevance — Low** (mobile), but the **"reward from system state, not
  surface UI"** philosophy is the single cleanest statement of the principle
  Seon's gym already embodies.

### BrowserGym + AgentLab (the web-eval harness)

- **Repos:** <https://github.com/ServiceNow/BrowserGym>,
  <https://github.com/ServiceNow/AgentLab>; "The BrowserGym Ecosystem for Web
  Agent Research" <https://arxiv.org/abs/2412.05467>.
- **What it is:** a **Gym-style unified environment** that wraps MiniWoB,
  WebArena, VisualWebArena, WorkArena, AssistantBench, etc. behind one
  observation/action API, with AgentLab for parallel runs + leaderboards.
- **Seon relevance — Medium (as a *harness pattern*).** The "one env API, many
  benchmarks behind it" design is what Seon's gym driver is for the Clojure side
  — a single scenario/predicate runner that many task EDNs plug into. Good prior
  art for the gym's runner architecture.

## Frontier-autonomy / safety

### METR RE-Bench

- **Org/repo:** METR. Repo <https://github.com/METR/RE-Bench>; report
  <https://metr.org/AI_R_D_Evaluation_Report.pdf>. **7 ML research-engineering
  environments** (fit a scaling law, optimize a GPU/Triton kernel, fix a broken
  model, etc.). Each environment ships a **continuous scoring function**
  (maximize accuracy / minimize runtime), a GPU box, and an 8-hour time budget;
  compared against **71 human-expert** attempts with full transcripts released.
- **Seon relevance — Medium.** The **continuous score (not binary)** + **direct
  human-vs-agent on the same task with a time budget** design is the most rigorous
  comparison methodology in the survey. Relevant if Seon ever scores "how well"
  not just "did it." Also see METR's HCAST + the "time-horizon" methodology
  (length of task a model can do at 50% reliability) as a way to report capability
  growth.

### Cybench

- **Org/repo:** Stanford (CRFM). Repo <https://github.com/andyzorigin/cybench>;
  <https://arxiv.org/abs/2408.08926>. **40 professional CTF tasks** from 4
  competitions (HackTheBox, SekaiCTF, Glacier, HKCert), each decomposed into
  **subtasks** for graded credit. **Flag-match** scoring in a Docker + Kali
  environment. Used by US/UK AISI for pre-deployment testing. **Note: vendored
  repo ~1.9GB** (challenge binaries/files).
- **Seon relevance — Medium.** Flag-match is the simplest possible mechanical
  oracle; **subtask decomposition for partial credit** is a useful idea for
  Seon's multi-step scenarios (credit each completed step, not just the whole
  task — Seon's `todo-multistep` scenario already gestures at this with per-step
  predicates).

## Eval harnesses (cross-cutting — the "how to run + score" layer)

### Inspect AI + inspect_evals (the bridge target)

- **Org/repos:** UK AI Security Institute (+ Meridian). Framework
  <https://github.com/UKGovernmentBEIS/inspect_ai>; eval collection
  <https://github.com/UKGovernmentBEIS/inspect_evals>; docs
  <https://inspect.aisi.org.uk>.
- **What it is:** the most serious open-source eval framework. Core abstractions:
  **`dataset → Task → Solver → Scorer`**, multi-turn/agent workflows with tools, a
  built-in ReAct agent + multi-agent + **bridges to external agent frameworks**,
  **sandboxed execution** (Docker built-in, optional k8s/Proxmox), and a log
  viewer. **inspect_evals ships 200+ ready evals**, including **GAIA, SWE-bench,
  Cybench, GDM CTF, AssistantBench** — i.e., most of this survey is already
  runnable *through Inspect*.
- **Seon relevance — VERY HIGH (the realistic bridge).** Inspect's
  `Task/Solver/Scorer` is a near-isomorphism of Seon's `scenario / agentic-loop /
  predicate`. The **agent bridge** is the mechanism by which a *non-Python* agent
  (a Seon pod, behind an HTTP/MCP endpoint) could be plugged in as the `Solver`
  and scored by Inspect's `Scorer` against GAIA/SWE-bench/Cybench. **If Seon ever
  benchmarks against an external suite, Inspect is the path of least resistance.**

### OpenAI Evals

- **Repo:** <https://github.com/openai/evals>. A framework + an open **registry**
  of evals; the **Completion Function Protocol** lets you evaluate *any* system
  (prompt chains, tool-using agents), not just a raw model. Registry data is
  stored via **Git-LFS** (the vendored shallow clone is code-only, ~11MB; data
  pulls separately).
- **Seon relevance — Medium.** The "Completion Function" abstraction (anything
  that maps prompt→completion can be scored) is the same seam Seon would expose to
  be benchmarked. Less agent-centric than Inspect.

### OpenHands (+ benchmarks) and SWE-agent

- **OpenHands:** <https://github.com/OpenHands/OpenHands> (was `All-Hands-AI/OpenHands`);
  eval harness <https://github.com/OpenHands/benchmarks>. The de-facto open
  **coding-agent harness** — "becoming the preferred harness for evaluating LLMs
  on coding tasks … top performer across SWE-bench, SWT-bench, multi-SWE-bench."
  Agents act like a developer: write code, use a shell, browse the web, in a
  sandboxed Docker workspace.
- **SWE-agent:** <https://github.com/SWE-agent/SWE-agent> (Princeton, NeurIPS
  2024). Introduced the **Agent-Computer Interface (ACI)** — purpose-built
  commands/feedback formats that make a codebase navigable by an LM. Also retargets
  to offensive security (EnIGMA) and competitive coding.
- **Seon relevance — Medium.** Both are *reference designs* for the tool surface a
  coding agent needs (view/edit/search/run + a tuned feedback format). The **ACI
  thesis — "the interface the agent sees is a design problem, optimize it"** — is
  directly Seon's lean-context thesis applied to tools. Worth reading SWE-agent's
  command set when designing Seon's file/edit verbs.

## How Seon's gym compares

Source read: `test/seon/gym/driver.cljs` (1,573 ln), `bin/gym`,
`docs/prds/gym-v2/design.md`, the scenario EDNs, and
`docs/prds/agent-fsm/research/gym-findings-2026-06-26.md`.

**Architecture (what it is):** scenarios are **EDN data** — one or more user
messages, optional **fixtures** (datahike tx-data + schema registrations seeded
before the run, with `{{today}}`/`{{days-ago:N}}` relative-date placeholders),
optional fixture **source** strings (Clojure `eval`'d so seeded fns are callable),
and a vector of **pass-predicates**. A driver boots **fresh agents on a scratch
`:memory` datahike conn** (root `*conn*` swapped for the run, restored in
`finally`; minted schema keys removed after) → the live cluster store is
untouchable by construction. Two tiers: **`:stub`** (free; scripts LLM responses
or replays through the real loop) and **`:paid`** (real provider — Anthropic or
DeepSeek — through `seon.agent.loop/run-loop!`, gated on `:allow-paid? true` + an
API key).

**Predicate kinds (how it scores):** `:datalog` (query the post-run store, assert
`:non-empty`/`:empty`/`[:count* n]`/`[:some-includes s]`/`[:every-in [...]]`),
`:domain-attrs` (every agent-provenance attr via `seon.warn/domain-attrs` —
`[:every-in …]` = "no forked attr", vocabulary-agnostic), `:eval-count-matching`
and `:first-eval-matches` (regex over the agent's **eval source**, scoped to
message-driven turns), `:transcript-includes/excludes`,
`:prompt-includes/excludes/every-turn` (assert against the **exact prompt bytes
the agent saw** — blobs persisted per turn), and **`:llm-judge`** (rubric +
reference facts + the verbatim reply → graded verdict on a **separate** scorecard
axis, never merged with the mechanical pass). Behavioral **rubric axes**:
`sees-question, searches-first, models-work-directed, reuses-schemas,
consults-findings, reuses-functions, writes-tests, replies-honestly, terminates,
stores-proactively`. Scorecards are keyed **(scenario × git-sha × run-id)** so a
context change shows up as a moved number; the judge is **calibrated** (good→PASS,
bad→FAIL, `discriminates?`) before being trusted.

**Task distribution (what it grades):** *personal-assistant data-modeling*, not
repo bug-fixing — e.g. `s21` (log a workout reusing an existing `:my.workout/*`
schema, **zero attr forks**), `s32` (consult a stored finding before re-searching
the repo), `todo-multistep-tracking` (a 3-step task that never says "todo" → mint
≥2 owner-scoped todos, close them all, design a schema, write a `deftest`, reply
once), `x1` (subscriptions SUM/MAX), `x3` (cross-agent expense reuse + category
total), `x12` (narrow question → no over-retrieval), `err-recovery-unregistered-attr`,
`envelope-honesty`, `blank-message-refusal`. The gym-v2 plan adds a **cross-session**
protocol (agent A stores, cold agent B retrieves) with discovery / retrieval-hit /
link-follow signals, and a future embeddings "lift" condition.

**Where Seon's gym is already best-in-class:**

- **Outcome-over-state scoring is correct and matches the field.** Datalog over
  the post-run store is the same philosophy as SWE-bench's test deltas,
  tau-bench's DB-state equality, WebArena's functional correctness, and
  AndroidWorld's adb-state reward. Seon is *not* doing the weak thing
  (string-matching outputs) by default.
- **Isolation is dramatically cheaper.** Everyone else pays for Docker- or
  VM-per-task; Seon swaps a `:memory` conn in sub-second. For high-rep
  context-tuning sweeps this is a real edge.
- **The judge/mechanical split + judge calibration** is more disciplined than
  most (ToolBench/WebVoyager lean on un-calibrated LLM judges; Seon separates the
  axes and proves the judge discriminates).
- **Self-bait / no-coaching load checks** (a turn message may not appear verbatim
  in a fixture; the planted answer may not leak) are a rigor most public
  benchmarks lack.

**Where the external suites are ahead (gaps to close):**

1. **`pass^k` / pass-rates, not n=1.** Seon's own `gym-findings-2026-06-26.md`
   discovered "2 of 4 axes flipped between identical runs" — the metric is too
   noisy at n=1. **tau2-bench solved this with `pass^k`** (solve on all k tries).
   Seon should make k-rep pass-rates the headline, not single verdicts. *This is
   the single highest-value import.*
2. **An adaptive user simulator.** tau-bench's LLM-simulated user (supplies info,
   changes its mind, must be satisfied) is a turn-driver Seon's gym lacks (today:
   scripted messages). A simulated user unlocks realistic multi-turn assistant
   scenarios.
3. **A non-Clojure execution surface.** Seon's "tools" are Clojure `eval`s; every
   coding benchmark assumes file-edit + bash + a foreign test runner. Seon can
   grade *its own* fn/test authoring, but cannot (yet) run SWE-bench/Terminal-Bench
   without a bash/subprocess verb + a generic test harness.
4. **Difficulty tiers + partial credit.** GAIA (3 levels), Online-Mind2Web
   (Easy/Med/Hard), Cybench (subtask credit) all stratify; Seon scenarios are
   flat pass/fail per axis. Subtask/step credit would reduce variance.
5. **A human baseline.** RE-Bench/GAIA/WebArena all report a human ceiling; Seon
   has none. Even an informal "what a competent Clojurist does on this scenario"
   anchor would calibrate the axes.

## Recommendations for Seon

### A. Context tuning — which external tasks to adapt into the gym

1. **Port tau-bench's multi-turn DB-state pattern (highest value).** Author 2-3
   gym scenarios where a **simulated user** drives a multi-turn flow (e.g. "book
   / amend / cancel" over a seeded domain), scored by **final datalog state
   equality** + a policy-adherence judge axis. This maps onto the existing
   predicate engine *and* forces the lean-context question: with a stripped
   system prompt, does the agent still follow a seeded policy doc and use the
   right verbs across turns? Add the **user simulator** as a new `:llm` turn mode
   in the driver (sibling to `:scripted-replay`).
2. **Port Commit0/Aider "spec → implement → test" tasks.** Seed a spec doc + one
   `deftest`; score on the agent producing a fn that makes the test pass
   (`:eval-count-matching deftest` already exists; add a "the seeded test now
   passes" datalog/eval predicate). This is the *most on-thesis* external task
   (build-your-own-toolkit) and directly stresses the lean-context bet (does the
   agent build the right thing from a spec without a fat prompt).
3. **Adopt GAIA-style exact-match factual scoring** for the question-answering
   scenarios (assert the reply carries the one correct value, mechanically — a
   cheap judge-free axis alongside the existing LLM-judge).
4. **Adopt BFCL's relevance/irrelevance axis explicitly.** Seon's `x12`
   (no-over-retrieval) is exactly BFCL's irrelevance category — generalize it: a
   scenario where the *correct* behavior is to NOT call a tool / NOT register a
   schema, scored mechanically by absence.

### B. Metric hardening (do this before trusting any lean-vs-fat comparison)

- **Make `pass^k` the gym headline.** `bin/gym` already keys cards by
  `scenario × sha × run-id`; aggregate k reps into a per-(scenario × sha)
  **pass-rate** and report `lean − fat` as a rate delta with a noise band. This is
  the fix `gym-findings-2026-06-26.md` itself prescribes and tau2-bench validates.
- **Add per-step / subtask credit** (Cybench-style) to multi-step scenarios to
  cut variance and localize *which* step the lean prompt breaks.

### C. Which suite to actually benchmark Seon against

- **Realistic now: Inspect AI as a bridge.** Inspect's `Solver` can be an external
  agent; expose the Seon pod behind an HTTP/MCP `Solver` shim and let Inspect's
  `Scorer` run it against **GAIA** (closest to Seon's assistant flavor, exact-match
  scoring, no GUI) and a **Lite SWE-bench slice** once Seon has a file-edit+test
  surface. Inspect already ships both. (`reference-code/inspect-ai` +
  `reference-code/inspect-evals`.)
- **Most conceptually fair: tau-bench.** A Seon agent is a tool-using multi-turn
  assistant over a DB — that *is* tau-bench. Re-implementing a tau domain on Seon's
  datahike store + grading by datalog state is a smaller lift than SWE-bench and
  plays to Seon's strengths. Strong candidate for a "Seon vs frontier on the same
  tool-agent-user task" headline.
- **Aspirational: a Terminal-Bench / SWE-bench-Lite run** once a bash/subprocess
  verb + generic test runner exist. High prestige, high lift — defer until the
  tool surface is real.

### D. Task distribution → which pre-made UX components / tools actually matter

Reading the task mass across all 20 suites, the agent capabilities that *recur*
(and therefore the pre-built surfaces worth investing in for Seon) are:

- **File read / structural edit / grep over a repo** (every coding suite + GAIA +
  Terminal-Bench). Seon has `seon.agent.fs` + `seon.agent.search` — keep them
  first-class; model them on SWE-agent's ACI (tuned commands + compact feedback).
- **A test runner with structured pass/fail** (SWE-bench, Aider, Commit0,
  SWE-Lancer, Terminal-Bench). Seon has `deftest` + a runner — the gap is a
  **dual-test-set** (`FAIL_TO_PASS` + `PASS_TO_PASS`) result shape so an agent's
  edit can be scored "fixed it without breaking what worked."
- **A tabular/DB query + aggregate surface** (tau-bench, AgentBench-DB,
  WebArena). Seon's **datalog + `store-inventory`** already is this — it is a
  differentiator; the SUM/MAX scenarios (`x1`,`x3`) prove agents can be graded on
  it. Lean into "the DB is the tool."
- **A "consult stored knowledge before acting" retrieval surface** (GAIA,
  tau-bench policy docs, Seon's `s32`/`consults-findings`). Seon's findings
  section + future embeddings "lift" is the right bet; keep breadcrumbs as
  **pointers, not blobs** (gym-v2 §4).
- **A multi-turn user-interaction harness** (tau-bench) — the biggest *missing*
  Seon component; a simulated-user driver is the unlock for realistic assistant
  scenarios and is squarely in the lean-context experiment's interest.
- **Out of lane for the pod:** browser/computer-use/GUI (WebArena, OSWorld,
  WebVoyager, AndroidWorld). Don't build these into the core; they're a different
  product surface (a Tauri/edge client could host them later).

## Vendored repos

Added as **shallow** (`--depth 1`) submodules under `reference-code/`
(`GIT_LFS_SKIP_SMUDGE=1` so LFS blobs are not pulled). All 20 succeeded; gitlinks
and `.gitmodules` entries are recorded. (During this session a concurrent agent's
`git add -A` commit `184adeb` swept 18 of these into HEAD; `cybench` +
`openai-evals` were left staged. Either way all 20 are tracked.)

| Submodule path | Upstream | Pinned SHA | On-disk size |
|---|---|---|---|
| `reference-code/swe-bench` | SWE-bench/SWE-bench | `f7bbbb2` | 5.0M |
| `reference-code/swe-agent` | SWE-agent/SWE-agent | `abd7d69` | 35M |
| `reference-code/swelancer` | openai/SWELancer-Benchmark | `4afbde3` | 8.0K (code only; Docker img external) |
| `reference-code/commit0` | commit-0/commit0 | `1123db3` | 29M |
| `reference-code/terminal-bench` | laude-institute/terminal-bench | `1a6ffa9` | **169M** |
| `reference-code/mle-bench` | openai/mle-bench | `507f92e` | 4.9M (harness only; Kaggle data external, multi-TB) |
| `reference-code/aider-polyglot` | Aider-AI/polyglot-benchmark | `7e0611e` | 29M |
| `reference-code/tau2-bench` | sierra-research/tau2-bench | `8ebb749` | **777M** (`data/` 734M — domain DBs + voice) |
| `reference-code/gorilla-bfcl` | ShishirPatil/gorilla | `6ea5797` | **180M** (BFCL in `berkeley-function-call-leaderboard/`) |
| `reference-code/agentbench` | THUDM/AgentBench | `d1e4a10` | 44M |
| `reference-code/webarena` | web-arena-x/webarena | `dce0468` | 7.4M |
| `reference-code/osworld` | xlang-ai/OSWorld | `83e8534` | 21M |
| `reference-code/browsergym` | ServiceNow/BrowserGym | `9e779f0` | 3.1M |
| `reference-code/online-mind2web` | OSU-NLP-Group/Online-Mind2Web | `f0d805e` | **5.4G** (`data/` 5.4G — trajectory dataset) |
| `reference-code/webvoyager` | MinorJerry/WebVoyager | `5a78967` | 28M |
| `reference-code/inspect-ai` | UKGovernmentBEIS/inspect_ai | `92dd737` | 44M |
| `reference-code/inspect-evals` | UKGovernmentBEIS/inspect_evals | `ce900d6` | **173M** |
| `reference-code/re-bench` | METR/RE-Bench | `93b9806` | 2.2M |
| `reference-code/cybench` | andyzorigin/cybench | `88d6893` | **1.9G** (`benchmark/` 1.9G — CTF binaries) |
| `reference-code/openai-evals` | openai/evals | `8ebb749`→`8eac749` | 11M (registry data via LFS, not pulled) |

**Size flags (note for the orchestrator):** three submodules are large from
bundled data — `online-mind2web` **5.4G**, `cybench` **1.9G**, `tau2-bench`
**777M**. These do **not** bloat the `seon` repo's git history (submodule content
lives in each submodule's own `.git`, not in seon's objects — only the gitlink +
`.gitmodules` line are committed), but they consume local disk. If disk matters,
`online-mind2web` and `cybench` are the natural drop candidates (their *value* is
mostly the bundled datasets, not the harness code; the harness/eval logic is
small).

## Sources (key links, verbatim)

- SWE-bench: <https://github.com/SWE-bench/SWE-bench> · <https://www.swebench.com/SWE-bench/reference/harness/> · Verified data <https://huggingface.co/datasets/princeton-nlp/SWE-bench_Verified> · Multimodal <https://arxiv.org/pdf/2410.03859> · Live <https://arxiv.org/pdf/2505.23419>
- Aider polyglot: <https://github.com/Aider-AI/polyglot-benchmark> · <https://aider.chat/2024/12/21/polyglot.html>
- Commit0: <https://github.com/commit-0/commit0> · <https://arxiv.org/abs/2412.01769>
- SWE-Lancer: <https://github.com/openai/SWELancer-Benchmark> · <https://arxiv.org/abs/2502.12115>
- Terminal-Bench: <https://github.com/laude-institute/terminal-bench>
- MLE-bench: <https://github.com/openai/mle-bench> · <https://arxiv.org/abs/2410.07095>
- tau-bench: <https://github.com/sierra-research/tau-bench> · tau2: <https://github.com/sierra-research/tau2-bench> · <https://arxiv.org/abs/2406.12045>
- BFCL: <https://github.com/ShishirPatil/gorilla> · <https://gorilla.cs.berkeley.edu/blogs/13_bfcl_v3_multi_turn.html>
- ToolBench/ToolLLM: <https://github.com/OpenBMB/ToolBench> · <https://arxiv.org/abs/2307.16789>
- AgentBench: <https://github.com/THUDM/AgentBench> · <https://arxiv.org/abs/2308.03688>
- GAIA: <https://arxiv.org/abs/2311.12983> · <https://huggingface.co/gaia-benchmark>
- AssistantBench: <https://assistantbench.github.io> · <https://arxiv.org/abs/2407.15711>
- WebArena: <https://github.com/web-arena-x/webarena> · <https://arxiv.org/abs/2307.13854>
- OSWorld: <https://github.com/xlang-ai/OSWorld> · <https://arxiv.org/abs/2404.07972>
- WebVoyager: <https://github.com/MinorJerry/WebVoyager> · <https://arxiv.org/abs/2401.13919>
- Online-Mind2Web: <https://github.com/OSU-NLP-Group/Online-Mind2Web>
- AndroidWorld: <https://github.com/google-research/android_world> · <https://arxiv.org/abs/2405.14573>
- BrowserGym/AgentLab: <https://github.com/ServiceNow/BrowserGym> · <https://github.com/ServiceNow/AgentLab> · <https://arxiv.org/abs/2412.05467>
- RE-Bench: <https://github.com/METR/RE-Bench> · <https://metr.org/AI_R_D_Evaluation_Report.pdf>
- Cybench: <https://github.com/andyzorigin/cybench> · <https://arxiv.org/abs/2408.08926>
- Inspect AI: <https://github.com/UKGovernmentBEIS/inspect_ai> · evals <https://github.com/UKGovernmentBEIS/inspect_evals> · <https://inspect.aisi.org.uk>
- OpenAI Evals: <https://github.com/openai/evals>
- OpenHands: <https://github.com/OpenHands/OpenHands> · benchmarks <https://github.com/OpenHands/benchmarks>
- SWE-agent: <https://github.com/SWE-agent/SWE-agent>
