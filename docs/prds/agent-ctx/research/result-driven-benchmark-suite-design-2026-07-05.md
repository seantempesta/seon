---
type: research
status: draft
tags: [research, agent, flow]
---

# Result-driven benchmark suite — design + ordered build plan (2026-07-05)

Answers the six open questions of
[[result-driven-benchmark-suite-brief-2026-07-05]] from fresh research over
`src-inspect-ai/`, the agent capability surface, and the vendored reference
benches. This is a PLAN — no suite code is written by this unit.

## 1. TL;DR — the recommended shape

- **Both, ordered: one established wrap + one bespoke row, sharing one new
  oracle vocabulary.** Wrap **aider-polyglot** (225 Exercism edit-and-test
  tasks, no docker anywhere in its model — the ONLY established repo-edit
  bench that runs through our cluster today) and author a bespoke
  **`repo_task`** row: generator-built git repositories with real history,
  goal-stated engineering tasks, oracle = the repo's own test suite re-run
  host-side + git-state checks.
- **SWE-bench Verified is explicitly deferred** — its scorer executes INSIDE
  the per-instance docker sandbox (per-instance conda envs); wrapping it
  honestly requires a docker-oracle host we don't have. Not faked, not
  half-wrapped.
- **The environment model is the existing one, extended:** per-sample
  workspace materialized host-side (tarball/generator instead of a dict of
  small files), one ephemeral cluster per sample, `run_tool_row`
  bench-cluster-N dispatch, evidence retention, `datasets.lock` freeze.
- **The oracle is two new host-side check kinds** — `tests_pass` (run the
  pinned test command as a subprocess, gate on exit code) and `git_state`
  (git plumbing reads) — added to `tool_scorers.check_workspace`'s existing
  vocabulary. Deterministic, host-side, no LLM judge, no docker.
- **A new attribution class `behavior_miss`** (no-terminal-reply: the run
  closed on `:turn-limit`/`:deadline-exceeded`, or closed with an empty
  reply) is specified — counted as FAIL in the capability mean, attributed
  distinctly so a turn-cap loop is never confused with a wrong answer.
- **First slice:** one bespoke template family (small seeded git repo, a
  failing test, a bug to fix) through the EXISTING `run_tool_row` machinery →
  one honest ledger row. Everything after that is expansion.

## 2. The six open questions, answered with evidence

### Q1 — Wrap established, author bespoke, or both? → BOTH, in this order

This forks everything, so the evidence first.

**What "wrapping established" actually requires.** The catalog's standard
path (`catalog.py:1-23`, `swap_generate` at `catalog.py:175`) works only for
benches with a host-side scorer and no Inspect sandbox — the docstring calls
this CASE-1 and explicitly defers code-exec benches. The repo-task benches
split into two families:

- **Sandbox-scorer benches — cannot be wrapped today.** SWE-bench's Inspect
  wiring attaches a per-instance docker image to every sample
  (`reference-code/inspect-evals/src/inspect_evals/swe_bench/swe_bench.py:47-50`
  — `swebench/sweb.eval.{arch}...` images; `:149-155` assigns
  `sample.sandbox`) and its scorer runs `sandbox().exec(...)` — the eval
  script activates a per-repo conda env and repo-version-specific install
  commands (`scorers.py:32-76`, `get_eval_script` at `:278-370`,
  `MAP_REPO_VERSION_TO_SPECS`). The environment IS the ground truth: those
  conda envs are not reproducible on a macOS host shell. The same holds for
  terminal-bench (per-task `Dockerfile` + `run-tests.sh` in every
  `original-tasks/<task>/`), commit0 (per-repo isolated test environments),
  and tau2-bench (scorer reads final DB state inside its own env — the
  survey's tier-3 flag, [[reference_agentic_benchmarks_survey]]).
- **Host-toolchain benches — wrap cleanly.** aider-polyglot is deliberately
  docker-free: each task is an Exercism exercise directory (stub +
  `.docs/instructions.md` + the track's own test file; reference solution
  hidden under `.meta/example.py` —
  `reference-code/aider-polyglot/python/exercises/practice/bowling/`), and
  the oracle is "the language's native test runner passes." 225 exercises
  across 6 languages (cpp 26, go 39, java 47, javascript 49, python 34,
  rust 30; submodule pinned at `7e0611e7`, 2024-12-22). The host already has
  `python3`, `node`, `bb`, `rustc`, `javac` (no `go`). This is an
  established, outcome-scored, real-file-editing bench that our cluster can
  run TODAY with zero new capability.

**What bespoke buys that polyglot does not.** Polyglot is single-directory,
single-file-stub, no git, no navigation — it measures edit+iterate against a
spec, not repository work. Nothing public measures multi-file repo
engineering WITHOUT a docker-per-task judge; nothing public measures it in
Clojure; and nothing public composes it with plan-survives-restart (our
headline row, `planning.py`). The standing rule ("bespoke only where nothing
public measures the capability, then oracle-scored + generator-frozen") is
satisfied for a `repo_task` row exactly as it was for `long_term_planning`.

**Decision:** wrap aider-polyglot as the established anchor (comparability:
aider publishes DeepSeek-class numbers ~45-60%, so headroom exists and the
number is externally calibratable), AND author the bespoke `repo_task` row
for the repository-work signal polyglot lacks. SWE-bench Verified is a
LATER, gated adoption (§6) — the honest path is docker-as-oracle host-side,
which is new infra.

### Q2 — Environment / isolation model

**The cluster substitutes for the docker sandbox on the AGENT side, not on
arbitrary-environment fidelity.** What a Seon cluster provides
(`cluster.py:1-18`, `bin/seon:243,262`): a real Node pod on the HOST
filesystem, shell as argv-exec (`seon.agent.shell/run` —
`src/seon/agent/shell.cljs:130`, runs `git`, `python3`, `node`, any PATH
binary, per-call 30s default timeout with `:seon.agent.shell/timeout-ms`
override), file verbs (`seon.agent.fs` read/write/edit/list/walk/stat —
`src/seon/agent/fs.cljs`), ripgrep search (`seon.agent.search`), web fetch,
and Clojure eval. What it does NOT provide: per-task language environments
(no conda, no per-repo virtualenv discipline), resource isolation (all
clusters share the host CPU/RAM — calibration already showed drive-time
inflation at N=4, `config.py:54-67`), or a hard security boundary (the gates
are explicitly soft — `capability-gates.md`). So the honest scope rule is:
**task sources are chosen such that the HOST toolchain is the task's whole
environment** (polyglot's model). That covers python/js/clojure(+rust/java)
repos with vendored-or-no deps; it does not cover SWE-bench's pinned
scientific-python stacks.

**Materialization per sample.** Extend the existing pattern, not replace it:
`run_tool_sample` already materializes `metadata["setup"]` under
`workspaces_root/e<epoch>/<sid>` and renders `{workspace}` into the task
text (`tool_rows.py:117-131`, `generators.py:863-885`). Two additions:

- **Generator-built repos:** the generator emits `setup` (files) plus a
  `repo` spec — an ordered list of commits (message + file states). The
  runner materializes files and builds the git history with plain `git init/
  add/commit` subprocesses (deterministic: fixed author/committer/date env
  vars so the SAME seed yields byte-identical history). Frozen exactly like
  the existing bespoke rows: generator + seed, sha256 of the canonical rows
  in `datasets.lock` (`freeze.py:1-38` — dev seed 1, milestone seed 2, fresh
  seed per blind test draw). No tarballs needed for the bespoke row.
- **Polyglot exercises:** the source of truth is the pinned submodule
  (`reference-code/aider-polyglot@7e0611e7`). The loader copies the exercise
  dir into the workspace EXCLUDING `.meta/` (the reference solution must
  never be agent-visible), records the submodule SHA + a per-exercise
  content sha256 in the lock (the "upstream pin" slot external sources
  already carry, `freeze.py:80-120`), and freezes the dev/milestone/test
  split over exercise ids with the standard seeded shuffle.

**Leak-guarding:** canary GUIDs ride the existing lock discipline
(`freeze.py` mints one per test split, injected into sample METADATA, never
task text). For generator repos, additionally commit a canary file into the
repo's first commit on TEST-tier draws only — greppable contamination
evidence in any future training-data audit.

**Isolation:** one ephemeral cluster per sample with the frozen bench bundle
(`ephemeral_cluster`, `cluster.py:349`; bundle identity pinned + asserted,
`FrozenBundleChanged`), workspace keyed on (epoch, sample id) so epochs
never share state (`tool_rows.py:100-107`). Nothing new is needed —
`seon.agent.shell` already runs git and test runners in the workspace. One
posture note: bench pods inherit `SEON_FS_ROOT=$SEON_ROOT
SEON_FS_READ_ONLY=1` (`bin/seon:262`), so fs WRITE verbs are read-only by
default and agents edit via shell or by widening their own unlocked grant
(`seon.agent.fs/configure!` — allowed, it is a soft boundary). The task
contract stays goal-stated, so whichever path the agent discovers is fine;
if a run shows agents wedging on the read-only default, granting the
workspaces root writable via `extra_env` at create is a one-line,
already-supported change (`cluster.py:278-293` — `extra_env` exists for
exactly this).

### Q3 — What the agent SEES vs DOES (the task contract + the turn loop)

**Contract (unchanged discipline, new content).** Task text is goal-stated,
never verb-coached, and states EVERY scored check (the 0/2→~1.0 law,
`generators.py:12-17`). For a repo task that means the text carries: the
absolute `{workspace}` path; what is wrong / what is wanted (behavior, not
patch); the EXACT test command the oracle will run, verbatim ("after your
changes, `python3 -m pytest` run in {workspace} must exit successfully");
any git-state requirements ("commit your work; the working tree must be
clean; do not amend or remove existing commits"); and any must-not-change
constraints. The test command being stated is not coaching — it is the
scored check, and it is also how the agent closes its own loop (run tests,
read failures, iterate), which is the capability under measurement.

**Turn loop / budgets.** The pod runs its OWN FSM to idle; inspect never
manages turns (`solver.py:1-19`). Bounds today: default turn-limit **20**
(`src/seon/agent/run.cljs:92-109`, agent-entity overridable via
`:seon.agent/default-turn-limit`), wall-clock 300s default per sample
(`config.py:31`), per-sample overridable via `metadata["timeout_ms"]`
(`solver.py:77-80`). The BFCL failure mode was the loop hitting the cap
without a terminal reply. Plan: (a) slice 1 tasks are sized to the existing
budget (small repo, one bug, tests run in seconds) and we MEASURE turn
consumption from the door's honest metadata (`pod_turns`,
`pod_closed_reason` — `solver.py:97-114`) before touching any budget;
(b) if measurement shows real tasks need >20 turns, the mechanism to raise
it per-cluster already has a precedent — `apply_ai_config` transacts config
data over the wire REPL after create (`cluster.py:49-135`); a run-bounds
row/attr the pod reads the same way is the natural extension, but the pod
READ side (a cluster-level default-turn-limit the run seeder consults) is a
tooling-lane surface — flagged in §6, not designed here.

**Restart-resume composition — yes, as a later slice.** The choreography
exists whole: `pod_planning_driver` (create → phase 1 → `restart_pod` →
phase 2 same agent → snapshot → destroy, `planning.py:234-298`). A
`repo_task_resume` variant swaps the data-batch phases for "start this repo
task" / "your runtime restarted; finish it", and the oracle becomes
`tests_pass` + the EXISTING `check_plan_trajectory` (`planning.py:72-121`)
unchanged. This composes the headline continuity row with real work — the
strongest single signal the suite can add — but it should land only after
the plain repo row is stable (it inherits every flake mode of both parents).

### Q4 — The oracle

**Two new check kinds inside the existing vocabulary** (extend
`check_workspace`'s spec — `tool_scorers.py:70-102` — which already has
`equals`/`absent`/`clj_parses`/`behavioral`):

- `tests_pass`: `{"cmd": ["python3", "-m", "pytest", "-x"], "timeout_s": N}`
  — the runner executes the pinned command as a host subprocess with cwd =
  the workspace, `check=False`, and gates on exit code 0. Never parses agent
  narration; never trusts the agent's own test run. For node-track polyglot
  exercises the cmd is the track's runner; for Clojure the existing bb-parse
  + node-eval oracles (`oracle_scorers.py`, already used by `behavioral`)
  are reused unchanged.
- `git_state`: deterministic reads via git plumbing subprocesses —
  `clean_tree` (`git status --porcelain` empty), `min_new_commits` (count
  since the frozen base SHA), `base_history_intact` (the frozen base SHA is
  an ancestor of HEAD, i.e. no rewrite), `head_not` (HEAD ≠ base — work was
  committed). Each check is only ever included when the task text states it.

**Where ground truth lives:** the generator (seed + procedure) for bespoke
rows; the pinned submodule + per-exercise sha256 for polyglot; both recorded
in `datasets.lock` under the existing verify-not-reshuffle discipline
(`freeze.py:14-23`). The oracle needs no state the workspace + lock don't
carry — no docker judge, because the task sources were CHOSEN so the host
toolchain is the whole environment (Q2). We do not claim this reaches
SWE-bench's arbitrary-repo rigor; we claim equal determinism on a scoped
environment class, which is the honest trade.

**Determinism guards:** fixed git env (author/date) at materialization;
test commands run with a pinned cwd and no network; per-check timeout so a
hanging test suite becomes a classified harness outcome, not a wedge.

### Q5 — Signal maximization (task-type mix + headroom)

Current ledger (first dev pass, 2026-07-03): shell_use .667, file_edit .800,
long_term_planning .286, gsm8k .730 — the toy tool rows sit high with little
discrimination room; planning is the only hard row. Aider's published
DeepSeek-class polyglot numbers (~45-60%) say the established anchor has
real headroom. Proposed mix, ordered by expected signal per unit cost:

- **`bug_fix` (bespoke, slice 1):** repo + failing test + goal-stated defect
  report; oracle `tests_pass` + `git_state`. The tightest
  read-navigate-edit-run-iterate loop; every context A/B (skill edits, ns
  renders, search guidance) should move it.
- **`polyglot` (established, slice 2):** feature-implementation against a
  spec + test suite, externally comparable, two language sub-rows first
  (python, javascript — the strongest host toolchains).
- **`multi_file_change` (bespoke, slice 3):** a rename/behavior change that
  spans 3-6 files with tests locking the behavior; punishes
  single-file-tunnel-vision, exercises search + walk.
- **`repo_navigation` (bespoke, slice 3, cheap):** compute an answer from
  repo structure/history ("which commit introduced X", "how many callers of
  Y") — reply-scored like web_fetch (`check_answer`), measures reading
  without editing; fast rows for A/B iteration.
- **`repo_task_resume` (composition, slice 4):** the headline — a bug_fix or
  multi_file task interrupted by a pod restart, oracle = tests_pass +
  `check_plan_trajectory`.

Deliberately EXCLUDED for now: test-writing rows (grading "good tests"
without an LLM judge needs mutation/coverage infra — flagged, not smuggled
in), dependency work (needs network + package managers — nondeterministic),
and anything needing `go` (not on the host).

### Q6 — Language / stack

**Polyglot for breadth, Clojure for native fidelity — both, weighted to
what the host runs.** Established sub-rows: python + javascript first
(toolchains present, cheapest oracles), rust/java optional later, go
excluded until installed. Bespoke `repo_task` templates target TWO stacks
(phased — slice 1 builds one, per the build plan): (a) node/javascript —
universally present, fast test runs;
(b) Clojure(Script) — the agent's `seon.eval` surface is Clojure-native and
the bb-parse/node-eval oracles already exist (`oracle_scorers.py`), so
Clojure repo tasks measure the form-IS-an-action fidelity nothing public
measures. Exercism's public Clojure track is a candidate later established
addition (same wrap model as polyglot); the `clojure` CLI is present on this
host (`/opt/homebrew/bin/clojure`), so it is unblocked when prioritized.

## 3. The suite design (consolidated)

- **Task sources:** bespoke generators in `seon_inspect/generators.py`
  (extended: `repo` spec alongside `setup`) + the pinned aider-polyglot
  submodule via a new loader module. All code in `src-inspect-ai/` (owner
  rule — no code in PRD dirs).
- **Environment:** host workspace + per-sample ephemeral frozen-bundle
  cluster; N=2 dispatch default (`BENCH_CLUSTER_PARALLELISM`), re-calibrated
  once real test suites run inside samples (pytest is heavier than the
  trivial drives that produced N=2).
- **Oracle:** `check_workspace` + new `tests_pass`/`git_state` kinds +
  `check_answer` for navigation rows + `check_plan_trajectory` for resume.
  All host-side subprocesses, deterministic, no LLM judge anywhere.
- **Contract:** goal-stated, every check stated (incl. the verbatim oracle
  test command), absolute workspace path, no Seon verb names.
- **Freeze:** generator+seed rows (dev=1/milestone=2/fresh-test) with jsonl
  sha256s in `datasets.lock`; polyglot as an external source with submodule
  SHA + content hashes + seeded split + canary GUID; test tier blind by
  construction (`TierDisciplineError` machinery unchanged).
- **Ledger:** rows `bug_fix`, `polyglot_python`, `polyglot_js`,
  `multi_file_change`, `repo_navigation`, `repo_task_resume` — each one
  `scorecard.append_row` line per run, pass^k via epochs, the standing
  regression alarm applies unchanged.

### The `behavior_miss` class (the flake-taxonomy gap, specified)

Definition: **the run ended without the agent delivering a terminal reply —
the loop was cut by its own bounds, not by the harness clock.** Structural
detection from the door's honest metadata (no narration parsing):

- `pod_closed_reason` ∈ {`:turn-limit`, `:deadline-exceeded`} (the loop's
  own bounds — `src/seon/agent/loop.cljs:314-321`), OR
- the run closed non-timeout with an empty `reply`.

Placement: **counted as FAIL in the capability mean** (finishing within a
stated budget IS part of the capability — excluding it would let a looping
agent look merely "flaky"), but recorded as
`attribution: {"behavior_miss": n}` on the ledger row and as
`outcome_detail: "behavior_miss"` on the execution record, so regression
analysis distinguishes "wrong answer" from "never answered". This is
deliberately DIFFERENT from `solve_timeout` (the pod's clock cut — a
latency/harness class, stays a flake, `scorecard.py:100-108`) and from
`run_error` (`:error` close — a runtime-defect class). Uniform
behavior_miss across a row triggers the uniform-0 law: suspect the budget /
context before the model.

## 4. Ordered build plan (landable slices, each with acceptance criteria)

Slice numbering continues the eval-lane roadmap; every slice ends with a
committed ledger row + evidence dir (`evals/runs/<date>-<name>/`) or an
explicit measurement memo — never inference.

1. **Oracle vocabulary + one bug_fix template (prove the model end-to-end).**
   Add `tests_pass` + `git_state` to `check_workspace` (offline unit tests:
   a hand-made passing and failing workspace each, incl. timeout + missing
   binary paths). Add the `repo` materializer (git subprocess build,
   deterministic env) + ONE `bug_fix` template family (small node or python
   repo, ~4-6 files, seeded defect, failing test) to `generators.py`. Add
   `behavior_miss` detection to `run_tool_sample`'s outcome recording.
   Freeze the dev split. Run n=5, epochs=1, through the UNCHANGED
   `run_tool_row`. **Accept when:** one honest `bug_fix` ledger row exists
   with evidence blobs; the oracle unit tests prove both verdicts offline;
   turn/latency distribution is recorded (the budget-measurement memo for
   Q3).
2. **Wrap aider-polyglot (python + javascript).** Loader module (exercise →
   sample: workspace = exercise dir minus `.meta/`, task text = the
   `.docs/instructions.md` content + the stated test command + edit-the-stub
   goal), lock entries (submodule SHA, content sha256s, seeded split, canary
   GUID), oracle = `tests_pass` with the track's runner. Dev n≈15/language.
   **Accept when:** `polyglot_python` + `polyglot_js` dev ledger rows exist;
   the split verifies as a no-op regenerate; a spot-check confirms no
   `.meta/` (reference solution) byte reaches any workspace or prompt.
3. **Signal expansion + calibration.** `multi_file_change` +
   `repo_navigation` template families; re-derive per-row timeouts and the
   parallelism default from slice-1/2 measurements (the `config.py`
   constants are calibration-derived by convention); if the turn-budget memo
   shows >20-turn need, file the cross-lane run-bounds ask (§6) and hold the
   affected templates until it lands. **Accept when:** four bespoke rows
   report with epochs≥3 (pass^k live) and flake_rate <10%.
4. **`repo_task_resume` (the composition headline).** Reuse
   `pod_planning_driver` choreography with a slice-1 repo task; oracle =
   `tests_pass` + `check_plan_trajectory` unchanged. **Accept when:** a dev
   ledger row exists with both oracle parts attributed separately, and a
   VOIDED-phase (run_error) sample demonstrably classifies as flake, not
   fail.
5. **Later / gated (not scheduled):** Exercism Clojure track wrap (needs
   `clojure` CLI decision); rust/java polyglot sub-rows; SWE-bench Verified
   via a host-side docker-oracle (below); test-writing rows (needs a
   deterministic test-quality oracle design of its own).

## 5. Needs infra we don't have (honest list)

- **Docker-oracle host (blocks SWE-bench et al.).** Wrapping any
  sandbox-scorer bench needs: docker on the eval host, image
  pull/cache management, and a scorer path that extracts the workspace diff
  and replays it in the instance image (`git diff` → apply → run
  FAIL_TO_PASS/PASS_TO_PASS, exactly `inspect_evals/swe_bench/scorers.py`).
  The agent-side iterate-in-their-env question is worse still (the agent
  can't run the repo's tests without that env). Real infra, real design —
  out of scope for this suite's first four slices.
- **Cluster-level run-bounds config (cross-lane, tooling).** A per-cluster
  default turn-limit/deadline the run seeder consults (today the knobs are
  agent-entity attrs, `run.cljs:263-266`, unreachable before the door mints
  the agent). Only needed IF slice-1 measurement shows >20-turn tasks; will
  be flagged in `coordination.md` with the measurement evidence, not
  designed here.
- **Host toolchain gaps:** no `go` (excludes the go track).
  `python3`/`node`/`bb`/`rustc`/`javac`/`clojure` confirmed present.
- **Resource isolation is nonexistent by design** — concurrent samples share
  host CPU; heavier in-sample test runs may force N=2→1 for some rows
  (a calibration outcome, not a blocker).
- **Test-quality oracle** (for test-writing rows): no deterministic
  no-LLM-judge design exists yet; excluded rather than faked.

## 6. Risks + what would falsify the key assumptions

- **Turn/clock budget insufficiency masquerading as incapability.** BFCL's
  death mode. Falsifier: uniform `behavior_miss` on a row; response: the
  uniform-0 law — raise budget/re-scope task size and re-run dev before
  reading any capability number. The behavior_miss class exists precisely so
  this is visible in one ledger glance.
- **Exercism contamination inflates polyglot.** These exercises are in every
  training set; the score partly measures recall. Mitigation: polyglot is
  the COMPARABILITY anchor (aider's own leaderboard has the same exposure),
  while the bespoke generator rows (fresh-seed test tier) are the
  contamination-proof capability read. Falsifier: polyglot ≫ bug_fix at
  matched difficulty → weight bespoke rows in the headline.
- **Synthetic repos too easy → no discrimination.** Falsifier: dev means
  ≥0.9 with epochs≥3. Response: the generators carry difficulty knobs (file
  count, defect subtlety, cross-file coupling) — escalate under the frozen
  procedure, re-freeze deliberately (`--write`, canaries kept).
- **The cluster-substitutes-for-docker hypothesis fails at the edges.**
  Where it demonstrably holds: FS+shell+git+host-runtime tasks (the whole
  slice-1..4 scope). Where it fails: pinned foreign environments,
  resource-isolated parallel test execution, untrusted-code containment.
  Falsifier for the scoped claim: cross-sample interference at N=2 with real
  test suites (watch drive-time inflation + wrong-workspace writes in the
  evidence blobs); response: N=1 for heavy rows, workspace-path canaries in
  the git_state check.
- **Agent write-path friction (read-only fs default) reads as incapability.**
  Falsifier: evidence blobs showing agents stuck on read-only denials while
  shell was available; response: `extra_env` workspace grant at create (one
  line, already supported) + re-run — a context/harness fix, never scored
  through.
