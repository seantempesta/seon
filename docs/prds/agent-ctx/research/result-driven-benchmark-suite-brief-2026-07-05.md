---
type: research
status: draft
tags: [research, agent, flow]
---

# Result-driven benchmark suite — a stage-setting brief (design with fresh eyes)

**This is a BRIEF, not a design.** It sets context and hands you (a fresh
research agent) the building blocks, the reference source to read, and the open
questions — then gets out of the way. Do your OWN research and propose a plan.
Do not treat anything here as a prescribed solution.

## Why this exists — the strategic conclusion that motivates it

The eval lane (`src-inspect-ai/`) is sound: it drives the Seon pod through
`POST /agents/run`, one agent per isolated ephemeral cluster, frozen datasets,
an append-only ledger (`evals/scorecard.jsonl`), a pass^k regression alarm, and
oracle scorers that gate CORRECTNESS (parses ∧ validates ∧ runs ∧ right
outcome), never style. It calibrates in-band against DeepSeek's published
numbers. See `docs/prds/agent-ctx/research/agentic-benchmark-adoption-2026-07-04.md`
and `.../deepseek-published-benchmarks-2026-07-04.md`.

But a session of hard-won findings converged on one conclusion:

- **Format-scored benchmarks fight our architecture.** We adopted BFCL
  (function-calling AST) and it fought us: scored the *emitted call shape*, not
  an outcome. Worse, when we tried our native form surface, the agent tried to
  *execute* the (fictional) candidate functions and looped to the turn cap —
  because in Seon a form IS an action, not a representation. Evidence:
  `.../research/... ` + commit `9befb6f3`. Lesson: **our agent's native surface
  is EXECUTION, not text.** You cannot measure it well by inspecting what it
  writes; you measure it by what it DOES.

- **Outcome-scored benchmarks are the right family.** Give the agent a goal +
  real tools, let it do whatever it wants, score the RESULT (tests pass, file
  correct, repo state right, computed answer matches). The "how" is ignored.
  Our existing bespoke rows (`shell_use`, `file_edit`, `long_term_planning` in
  `seon_inspect/generators.py` + `tool_rows.py` + `planning.py`) already work
  this way, but they are TOY-scale ("read a file, then edit a file") and do not
  maximize useful signal.

- **Our cluster IS the environment.** A Seon cluster is a real Node process with
  a real filesystem workspace and real capability verbs — `seon.agent.shell`
  (shell), `seon.agent.fs` (read/write/edit files), `seon.agent.web`,
  `seon.eval` (eval Clojure). So for any task whose environment is "a filesystem
  + shell + git + a test runner," **we do not need a docker-per-task sandbox —
  the cluster already provides it.** This is the key asymmetry vs. how
  SWE-bench et al. are normally run.

**The owner's ask:** a CUSTOM, RESULT-DRIVEN benchmark suite that maximizes
useful benchmark signal — real engineering tasks in **git-like environments**,
with **file editing / modification** and "all those requirements," scored by
OUTCOMES. Not "call read-file then file-edit." The owner is explicit that the
best way to structure this is unknown and wants a fresh plan — hence this brief.

## What you have to build with (the existing building blocks — READ these)

- **The harness:** `src-inspect-ai/` — a real Python package on Inspect AI.
  `catalog.py` (bench wiring, `swap_generate`, the `BENCH_ADAPTERS` seam),
  `solver.py` (`/agents/run` door), `cluster.py` (ephemeral cluster lifecycle:
  create/restart/destroy, frozen bench bundle, N=2 parallelism), `freeze.py` +
  `evals/datasets.lock` (seeded dev/milestone/test splits, canary GUIDs, tier
  discipline — test blind by construction), `scorecard.py` (the ledger + pass^k
  alarm), `tool_rows.py`/`generators.py`/`planning.py`/`tool_scorers.py` (the
  EXISTING bespoke row pattern — study it; you are extending or superseding it),
  `bfcl_adapter.py` (the format-bench cautionary tale).
- **The cluster + tools (the environment):** `bin/seon cluster create <name>
  [--ephemeral]` / `destroy`. The agent's task-accomplishing verbs live in
  `src/seon/agent/{shell,fs,web}.cljs` + `seon.eval`. Read their capability
  surface — what file/shell/git operations an agent can actually perform is the
  substrate every task sits on. `docs/seon/components/capability-gates.md`.
- **The disciplines (non-negotiable, see below):** the scorers-gate-correctness
  rule, frozen+reproducible datasets, oracle scoring, no answer-shaping, every
  scorer-check stated in the agent's context (the 0/2→~1.0 law), the flake
  taxonomy + attribution, `evals/runs/<date>-<name>/` evidence retention.

## The goal, stated as capability signal (not as a solution)

Maximize the USEFUL signal about "can a Seon agent get real engineering work
done." That almost certainly means multi-file, multi-step tasks in real code
repositories where the agent must read, navigate, edit, run, and iterate —
scored by whether the end state is correct (a test suite passes, a repo reaches
a target state, a bug is fixed, a feature works). "Git-like environments" is the
owner's phrase: real repos, real diffs, real test execution.

## The open questions — what YOU must research and decide (do not assume)

1. **Wrap established datasets, or author bespoke Seon tasks, or both?** The
   standing owner rule is "established benches over homemade; bespoke only where
   no standard bench exists, oracle-scored." SWE-bench / commit0 / aider-polyglot
   / terminal-bench / swelancer are established, real-repo, outcome-scored — but
   they assume their own execution harness (often docker-per-instance). Can we
   run their TASK + their GROUND-TRUTH TESTS through our cluster's shell instead
   of their docker? What breaks? What's the fidelity/effort trade vs. a custom
   Seon-native suite of git tasks we fully control? Resolve this first — it forks
   everything.
2. **The environment/isolation model.** Our cluster gives a real FS + shell. For
   a git benchmark: how is the repo materialized per sample (clone at a pinned
   SHA? a frozen tarball? a fixture like the web_fetch fixtures)? How is it made
   reproducible + frozen (the `datasets.lock` discipline) and leak-guarded
   (canary GUIDs)? How is test execution run and captured as the oracle
   (bb/node/pytest/the repo's own runner)? Does the ephemeral cluster need any
   new capability, or does `seon.agent.shell` already suffice (it can run git +
   a test runner in the workspace)?
3. **What the agent SEES vs DOES.** The agent works via forms/verbs
   (`fs/edit`, `shell/run`, `eval`). What is the task contract (goal-stated, no
   answer-shaping, every scored check stated)? How does a multi-file, iterative
   task fit the turn loop + the turn cap (the bfcl loop was a turn-cap failure —
   long tasks will stress this)? Does the planning-survives-restart capability
   (our headline bespoke row) compose here (a big task interrupted + resumed)?
4. **The oracle.** Outcome scoring: exact repo state, a passing test suite, a
   diff match, a behavioral check (compile + run + assert). Deterministic,
   host-side, no LLM judge. How do you avoid the SWE-bench-style docker judge
   while keeping the same rigor? Where does the ground truth live and how is it
   frozen?
5. **Signal maximization.** What mix of task types (bug-fix, feature-add,
   refactor, test-writing, multi-file navigation, dependency work) yields the
   most discriminating capability signal at the smallest cost? What has HEADROOM
   for DeepSeek (unlike the saturated QA benches) so context A/Bs can move it?
   How does this become the capability ledger's centerpiece?
6. **Language/stack.** Seon is Clojure; the agent's `eval` is Clojure-native.
   Do we bench Clojure repos (maximally native, but a narrow public dataset),
   polyglot (aider-polyglot's languages, broader but the agent leans on shell
   not eval), or both? Trade native fidelity vs. established-dataset breadth.

## reference-code/ read-map (research these — form your own view)

Result-driven / repo-editing / outcome-scored (the core references):
- `reference-code/swe-bench/` — THE canonical result-driven code benchmark: real
  GitHub issues, agent edits the repo, run the repo's test suite → pass/fail.
  Study its dataset shape (task + FAIL_TO_PASS/PASS_TO_PASS tests + the base
  commit) and its docker execution model (what we'd replace with our cluster).
- `reference-code/swe-agent/` — the agent↔repo interaction harness for SWE-bench:
  the file/shell tool surface an agent needs to edit repos. Compare to our
  `seon.agent.{fs,shell}` verbs — what's the gap?
- `reference-code/commit0/` — build a library from a spec, test-driven; a
  from-scratch-implementation flavor of outcome scoring.
- `reference-code/aider-polyglot/` — polyglot coding tasks, edit-and-test across
  languages; a lighter, no-docker-per-task model worth studying for how it
  frames edit+run.
- `reference-code/terminal-bench/` — agentic terminal tasks in a real env,
  outcome-scored; closest to "agent + shell + a real environment."
- `reference-code/swelancer/` — real freelance SWE tasks with economic ground
  truth; another outcome-scored repo-task shape.
- `reference-code/re-bench/`, `reference-code/mle-bench/` — research/ML
  engineering tasks (outcome = a metric); broader "real work" signal, heavier.
- `reference-code/tau2-bench/` — tool-agent-user, scored by final DB state (an
  outcome-scored NON-code example; note WHY its scorer needs its own env — the
  survey flagged it tier-3 for exactly this).

The harness + framework (how tasks/solvers/scorers are structured, and how we
already plug our agent in as the solver):
- `reference-code/inspect-evals/src/inspect_evals/swe_bench/` — the reference
  wiring of SWE-bench INTO Inspect AI (dataset + solver + scorer split; the
  sandbox config). This is the closest model for how we'd wire a repo-task
  bench into `catalog.py`.
- `reference-code/inspect-ai/` — the framework: `Task`, `Solver`, `Scorer`,
  and its sandbox/tool model. Understand what "sandbox" means to Inspect and
  whether our cluster substitutes for it.
- `reference-code/openai-evals/` — the original evals framework; task+grader
  structure, for breadth on how graders are designed.

Optional / adjacent (skim only if relevant to your plan):
- `reference-code/cybench/` (CTF, flag = outcome), `reference-code/browsergym/`
  + `reference-code/webarena/` + `reference-code/online-mind2web/` (web/computer
  envs — heavier, probably out of scope for a git/file suite),
  `reference-code/openevolve/` + `reference-code/funsearch/` + `reference-code/ADAS/`
  (self-improvement loops — interesting for "maximize signal" but likely later).

## Constraints / principles that MUST hold (non-negotiable)

- **Outcome-scored, deterministic, host-side.** No LLM judging (except where
  genuinely unavoidable, and then flag it). The scorer gates CORRECTNESS.
- **Established over homemade** where a standard bench fits (wrap the dataset +
  its ground truth); bespoke ONLY where nothing public measures the capability,
  and then oracle-scored + generator-frozen.
- **Frozen + reproducible:** seeded splits, `datasets.lock` (regenerate = no-op),
  canary GUIDs + the leak grep, test tier blind. Repos pinned by SHA/tarball.
- **Our tools, not theirs.** The agent accomplishes the task via Seon's own
  capability verbs in the cluster — never a bolted-on foreign tool-call format
  (that was the BFCL mistake).
- **Every scored check stated in the agent's context** (the 0/2→~1.0 law).
  Goal-stated tasks, never answer-shaped / never coaching the specific answer.
- **Evidence retention** per run (`evals/runs/<date>-<name>/`: inspect logs,
  executions, blobs). Pod-agnostic (`SEON_CLUSTER_URL`); env never shadows
  config. No maintained code in PRD dirs — the suite lives in `src-inspect-ai/`.
- **Fits the ledger:** append rows to `evals/scorecard.jsonl` with model
  provenance (runtime-derived), the pass^k alarm, the flake taxonomy. Note the
  known taxonomy gap: a **behavior-miss / no-terminal-reply** class (the agent
  looped and never delivered) is not yet distinct from parse/model miss — a
  long-task suite will need it.

## Deliverable (what to produce — a PLAN, not an implementation)

A design doc in `docs/prds/agent-ctx/research/` that: answers the open questions
from your OWN research (with the reference-code evidence that grounds each
choice); proposes the suite's shape (wrap-established vs bespoke vs both, the
environment/isolation model, the task types, the oracle, the language/stack);
gives an ordered, landable build plan (first slice that proves the model
end-to-end through our cluster, then expansion); and honestly names what needs
infra we don't have yet (the sandbox-scorer-host question) vs. what our cluster
already covers. Do NOT build the suite in this unit — set the design.

Cross-lane note: repo-task execution may surface capability-verb gaps
(`seon.agent.{fs,shell}` — e.g. does the agent have everything it needs to
navigate + edit + run a real repo?). Those are tooling-lane items — flag them in
`coordination.md` with evidence, don't fix them here.
