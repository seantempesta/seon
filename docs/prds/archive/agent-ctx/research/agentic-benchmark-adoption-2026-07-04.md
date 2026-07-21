---
type: research
status: active
tags: [research, agent]
---

# Agentic-benchmark adoption plan — what to wire into the /agents/run door, in what order

Which ESTABLISHED agentic benchmarks (tool-calling / SWE / terminal / web-nav /
computer-use) to adopt into the inspect-ai harness over our `POST /agents/run`
pod door, ranked by product-signal × published-DeepSeek-anchor × cost-to-wire.
Paper survey off the vendored source (`reference-code/inspect-evals/`,
`reference-code/{terminal-bench,gorilla-bfcl,tau2-bench,swe-bench,agentbench}`)
and our catalog (`src-inspect-ai/`). No cluster runs, no implementation.

## TL;DR — the ranked adopt-list

The one structural fact that orders everything: **`catalog.swap_generate`
replaces a task's SOLVER, never its SCORER.** So a bench fits our door iff its
scorer grades the pod's final TEXT reply host-side (gsm8k `match`, gpqa
`choice`, an LLM grader, a pure-Python AST match). A bench whose scorer must
EXECUTE the model's output in an environment it controls — a docker image
(swe_bench, gaia, agent_bench, livecodebench_pro), a simulated tool/DB env
(tau2, agentdojo), or a VM/browser (osworld, mind2web) — does NOT fit, because
we'd have to host that execution ourselves. This cleaves the field exactly
against the grain of the DeepSeek anchors.

**Tier 1 — wire now (established ∧ NT-anchored ∧ inspect-ready ∧ infra-light):
EMPTY, and that is the headline finding.** Every DeepSeek **Non-Think**-anchored
agentic bench (SWE Verified 73.6 / Pro 52.1 / Multilingual 69.8, Terminal Bench
2.0 59.1, LiveCodeBench 56.8, MCPAtlas 69.4, Toolathlon 46.3) needs scorer-side
execution or a tool/VM environment our text door doesn't host. Every bench that
DOES fit the door cheaply (bfcl-AST, browse_comp) has **no** published Non-Think
number. The intersection the owner hoped for is genuinely empty through the
current door — so the real decision is which tier-2 to pull forward first.

**Tier 2 — valuable, needs modest integration (adopt in this order):**

1. **BFCL — single-turn AST subset** (`bfcl` in inspect-evals). Foundational
   agentic primitive (tool/function-calling), deterministic AST-match scorer
   (fits our "scorers gate correctness" rule exactly), 1-turn/cheap, and
   maximally aligned with the pod — which natively emits Clojure calls. Cost to
   wire = a small call-extraction adapter (text `(f :a v)` → BFCL's AST target)
   + a pinned GitHub dataset download. **This is the single best first pick.**
   Caveat: no NT anchor (shared by every door-fitting agentic bench).
2. **LiveCodeBench (plain, from source)** — the best ANCHORED pick (NT **56.8**),
   self-contained competitive-code with stdin/stdout test cases → a light
   self-hosted subprocess runner (no per-task docker), plays to the pod's
   write-and-run-code strength. Not in inspect-evals (build from source); the
   vendored `livecodebench_pro` is the harder, docker-judged variant — do NOT
   confuse the anchor.
3. **SWE-bench Verified** (`swe_bench`) — the crown-jewel product signal (NT
   **73.6**, already in inspect-evals). Solver-swap works (pod emits a patch as
   text, written into the sample sandbox); the blocker is the scorer's
   docker-per-instance apply-and-test (multi-GB images). Highest value, heaviest
   tier-2 infra — the strategic target once we host a docker sandbox scorer.
4. **browse_comp** — fits the door (drop its docker+web_browser solver; pod
   browses with its own `web-fetch`; LLM-grader on the final answer). Established
   web-nav. But no NT anchor, live-web non-hermetic (fence to milestone/test),
   and BrowseComp is brutally hard for a single-fetch agent → predictably near-0
   → collides with the "0 scores → suspect harness first" rule. Adopt AFTER the
   pod's web tool is stronger, or only as a milestone/test-tier reserve.

**Tier 3 — impractical through the current door (blocker named):**

| Bench | Anchor | Blocker |
|---|---|---|
| tau2 (airline/retail/banking/telecom) | none | scorer `db_match` checks the SIMULATED tool-env DB final state — the pod can't touch inspect's env; needs the rejected tool-bridge |
| swe_bench Pro / Multilingual | 52.1 / 69.8 | same docker-per-instance scorer as Verified; adopt with #3's infra |
| livecodebench_pro | (56.8 is plain LCB) | docker `LightCPVerifier` judge in a sandbox |
| gaia | none | react-agent + bash/python/web_browser in docker; scorer execs in sandbox |
| agent_bench (OS) | none (≠ DeepSeek's Terminal Bench) | per-task docker; scorer `exec`s in the sandbox |
| terminal-bench | **59.1** | its OWN docker-per-task harness + agent adapter (not inspect); closest to Seon's identity but heaviest to wire |
| osworld / mind2web | none | live VM / browser computer-use env |
| cybench / gdm_* CTF | none | CTF docker sandboxes |
| MCPAtlas / Toolathlon | **69.4 / 46.3** | not vendored, not in inspect-evals; need an MCP/tool environment built from scratch |
| agentdojo / agentharm | none | simulated tool env + injection sandbox |

## The single best first agentic bench to add: BFCL (single-turn AST)

**My prior was tau2 or bfcl; the evidence refutes tau2 and confirms bfcl — but
for a sharper reason than "it's tool-calling."**

- **tau2 is refuted on infra.** `tau2/retail/scorer.py` scores with
  `db_match(domain="retail")` — it compares the FINAL STATE of the simulated
  retail/airline/banking/telecom database after the agent's tool calls ran
  inside inspect's environment. The task's solver IS a user-simulator agent
  (`get_*_user_agent`) driving a tool loop. Our pod can neither call those
  simulated tools nor mutate that DB — swapping the solver leaves the scorer
  with an empty env. tau2 is tier-3 for us, not tier-1. (It's also NOT a
  DeepSeek-anchored bench — DeepSeek reports Toolathlon/MCPAtlas, not tau2.)

- **bfcl-AST wins the door constraint.** `bfcl/utils/task_categories.py`:
  `is_executable` (the `exec_*`/`rest` categories) and `is_multi_turn` are the
  only ones needing execution; the multi-turn scorer uses inspect's
  `generate(tool_calls="loop")` with backend classes as Tools — the tool bridge
  we don't have. But the **V1 single-turn, non-live, non-multi-turn** categories
  are scored by pure-Python `ast_match` (and even the single-turn `exec_*`
  ground truth is *preprocessed into the same AST matcher* — `bfcl.py:175`), so
  that subset needs **no sandbox, no execution, no tool bridge**. Deterministic
  oracle on the final output = a perfect fit for our correctness gate.

- **Why bfcl over browse_comp as FIRST:** bfcl measures a capability the pod
  actually HAS (it emits structured Clojure calls) and yields a graded
  distribution; browse_comp measures a known weakness (single-fetch web) and
  predictably scores ~0, which our standing rule says to read as a harness
  defect, not a clean signal. bfcl is also deterministic (browse_comp needs an
  LLM grader) and cheap (1 turn vs multi-hop).

- **The one honest cost:** the pod does not emit OpenAI-style structured
  `tool_calls`; bfcl's single-turn solver expects them. So bfcl is **tier-2, not
  tier-1**: it needs a small adapter that (a) renders each sample's function
  schemas into the prompt asking for a single Clojure/JSON call, and (b)
  extracts that call from the pod's text reply into the shape `ast_match`
  consumes. This adapter is small and *natural* for a Clojure agent — arguably a
  better-motivated bench for Seon than for a JSON-tool model. The missing NT
  anchor is not a bfcl-specific loss: NO door-fitting agentic bench has one.

## Full scoring table

Scored 1–5 where relevant; ✓/✗ for boolean. "Signal" = real agentic capability
(vs QA). "NT anchor" = a DeepSeek-V4-Pro **Non-Think** published number (our
mode). "Door fit" = scorer grades final text host-side (swap-solver suffices).

| Bench | Agentic signal | DeepSeek NT anchor | inspect-ready | Door fit (scorer host-side?) | Scoring | Rough cost | Tier |
|---|---|---|---|---|---|---|---|
| **bfcl** (single-turn AST) | tool-calling (5) | ✗ | ✓ | ✓ (needs call-extract adapter) | deterministic AST oracle | 1 turn, cheap | **2 → FIRST** |
| **LiveCodeBench** (plain, from source) | code gen+run (5) | ✓ **56.8** | ✗ (build) | ✓ (self-host stdin/stdout runner) | deterministic test-pass | medium | **2** |
| **SWE-bench Verified** | real SWE (5) | ✓ **73.6** | ✓ | ✗ scorer execs in docker/instance | deterministic (test suite) | high (multi-GB images) | **2/3** |
| **browse_comp** | web nav (5) | ✗ (NT unreported) | ✓ | ✓ (pod uses own web-fetch; drop task docker) | LLM grader | multi-hop; likely low | **2** |
| assistant_bench | web QA (4) | ✗ | ✓ | ✓ answer-match | deterministic-ish | medium | 2 |
| SWE-bench Pro / Multilingual | SWE (5) | ✓ 52.1 / 69.8 | ✓ | ✗ docker scorer | deterministic | high | 2/3 |
| terminal-bench | terminal/shell (5) — = Seon's identity | ✓ **59.1** | ✗ own harness | ✗ own docker-per-task + agent adapter | deterministic | high | 3 (strategic) |
| tau2 (4 domains) | tool-agent (5) | ✗ | ✓ | ✗ scorer = simulated-DB state | env-state oracle | multi-turn | 3 |
| livecodebench_pro | competitive code (5) | (56.8 = plain) | ✓ | ✗ docker judge | deterministic-in-sandbox | high | 3 |
| gaia | general assistant (5) | ✗ | ✓ | ✗ sandbox scorer + web_browser | mixed | high | 3 |
| agent_bench (OS) | terminal/OS (5) | ✗ | ✓ | ✗ docker scorer `exec` | env-state | high | 3 |
| osworld / mind2web | computer/web-use (5) | ✗ | ✓ | ✗ live VM/browser | env-state | very high | 3 |
| cybench / gdm_* CTF | security-agent (4) | ✗ | ✓ | ✗ CTF docker | flag-match in sandbox | high | 3 |
| MCPAtlas / Toolathlon | MCP/tool (5) | ✓ **69.4 / 46.3** | ✗ | ✗ needs MCP/tool env | env-state | high | 3 |
| agentdojo / agentharm | tool + injection (4) | ✗ | ✓ | ✗ simulated tool env | env-state | high | 3 |

## Per-bench notes (evidence)

- **bfcl** — `bfcl/bfcl.py` downloads dataset from `ShishirPatil/gorilla` pinned
  at commit `dac44e7a…` (frozen ⇒ contamination-proof; fits our datasets.lock
  discipline). `V1_CATEGORIES` = non-live, non-multi-turn (AST). `func_source`
  download + `LightCPVerifier`-style execution is only for the `exec_*`/`rest`
  and multi-turn paths — the AST subset avoids all of it. Deterministic. The
  Berkeley Function-Calling Leaderboard is THE canonical established tool-calling
  bench.
- **LiveCodeBench (plain)** — the ONLY code bench with a DeepSeek chat NT anchor
  (Table C: **56.8**). Self-contained competitive problems with stdin/stdout
  tests → a subprocess runner is far lighter than swe_bench's per-instance
  docker. Not in inspect-evals (only `livecodebench_pro`, harder + `judge.py`
  runs `LightCPVerifier` in a docker sandbox — `livecodebench_pro/judge.py:27`).
  Building plain LCB from source + a stdin/stdout scorer is the cheapest path to
  an ANCHORED agentic/code number.
- **SWE-bench Verified** — `swe_bench/swe_bench.py` uses
  `SandboxEnvironmentSpec` (docker), `swe_bench/solvers.py` applies the patch and
  runs `git apply` + tests INSIDE the sandbox. Solver-swap works (pod → patch
  text); the scorer's docker apply-and-test is the cost. The gold product signal
  with the strongest NT anchor (73.6). Worth one dedicated infra investment (a
  docker sandbox scorer host) — but that IS the investment.
- **browse_comp** — `browse_comp.py` ships `DEFAULT_DOCKER_SANDBOX` + a `react`
  agent with `web_browser`/`web_search` tools and an LLM `GRADER_TEMPLATE`.
  swap-solver drops the docker+react+browser (all solver-side); dataset pinned
  (`browse_comp_test_set.csv`, SHA `7b2447…`); scorer is the LLM grader → keep
  it. Fits the door, but: no NT anchor (DeepSeek reports High 80.4 / Max 83.4,
  NT "—"), non-hermetic live web, and designed to require hundreds of page-hops
  → a single-fetch pod scores ~0. Milestone/test-tier candidate, not a dev-tier
  first pick.
- **tau2** — refuted above; `db_match` scorer + simulated tool env = tier 3.
- **terminal-bench** — vendored at `reference-code/terminal-bench/` with its OWN
  `terminal_bench/harness`, `docker/`, `agents/`, `llms/`. NT anchor 59.1 and the
  closest bench to what Seon actually is (a shell agent). But wiring means
  running terminal-bench's harness with our pod as its "agent" adapter + docker
  per task — heaviest integration, deferred. Strategically the most Seon-shaped
  bench; revisit after SWE-Verified's sandbox infra exists (shared docker path).
- **MCPAtlas / Toolathlon** — the two NT-anchored TOOL benches (69.4 / 46.3), but
  neither is vendored nor in inspect-evals; each needs an MCP/tool environment
  built from scratch. Highest anchor value among tool benches, highest build
  cost. Park until the tool-bridge question is reopened.

## Wiring sketch for the tier-1 winner (BFCL single-turn AST)

Everything below is a proposal; no code was written.

1. **Catalog entry** (`src-inspect-ai/src/seon_inspect/catalog.py`,
   `CASE1_BENCHES`): add `"bfcl_ast": ("inspect_evals.bfcl", "bfcl")` with
   `task_kwargs={"categories": [<V1 non-live non-exec AST categories>]}` (i.e.
   the `simple`/`multiple`/`parallel`/`parallel_multiple` python/java/js AST
   categories — exclude `exec_*`, `rest`, `live_*`, `multi_turn_*`).
2. **The adapter** (the only new code, and the reason bfcl is tier-2 not tier-1):
   bfcl's single-turn solver expects OpenAI-style `tool_calls`; our pod returns
   text. So the sample prompt must ASK the pod to emit exactly ONE call in a
   parseable form, and a thin extraction step must lift it into the shape
   `ast_match` scores. Two options, cheapest first:
   - **Prompt-mode + regex/read extract:** render the sample's function schemas
     into the prompt ("call the correct function; reply with only the call as
     `(name :arg val …)`"), then a `pod_backed`-style wrapper reads the pod's
     final reply, parses the s-expr/JSON, and sets it where `ast_match` looks
     (mirrors exactly how `catalog.swap_generate` already keeps a bench's own
     format-contract solver and swaps only `generate`). This is the "every check
     the scorer makes must be stated in context" finding applied to bfcl.
   - If `ast_match` reads structured `state.output.tool_calls`, the extractor
     synthesizes that structure from the parsed call instead of free text.
3. **/agents/run integration:** unchanged — `seon_pod_solver` /
   `seon_cluster_solver` already deliver `_prompt_text(state)` and record the
   reply; the adapter sits between the reply and `ast_match`, exactly like
   `pod_backed`. Per-sample ephemeral cluster (frozen `:bench-client` bundle),
   `POD_MAX_SAMPLES=1`, bench-cluster-N for parallelism — all as-is.
4. **Frozen-dataset / canary story:** the GitHub download is already commit-
   pinned (`dac44e7a…`) → deterministic. Apply the three-way split
   (dev/milestone/test) under the recorded global seed and add the ids to
   `datasets.lock`, same as the QA rows. bfcl is public (no canary GUID needed —
   canaries are for our bespoke generators); note contamination risk is inherent
   to any public bench and handled by the milestone/test reserve, not by us.
5. **Scorecard row:** `{row: "tool_calling", source: "bfcl", tier: "dev", …}` —
   deterministic mean + pass@k/pass^k, flakes excluded. NO NT anchor, so band it
   against the *established leaderboard* number for the model (report-only), not
   a DeepSeek NT column — state that honestly in the row's notes.

### And the strategic second: LiveCodeBench (plain) to get an ANCHORED number

Because tier-1 is empty, the suite has zero NT-anchored AGENTIC rows after bfcl.
The cheapest fix is LiveCodeBench (plain): build the task from source, host a
subprocess stdin/stdout runner as the scorer (write the pod's code to a temp
file, run the frozen test cases, deterministic pass/fail — no per-task docker),
band against NT **56.8**. This is the lightest path to calibrating the harness
on a genuinely agentic/code capability the pod is actually good at.

## Surprises

- **Unexpectedly BLOCKED — browse_comp.** I expected it to be the cleanest
  door-fit ("question in, short answer out, string match"). It ships a docker
  sandbox + containerized `web_browser` react solver AND an LLM grader; the fit
  only works because swap-solver discards its solver+sandbox. Even then it's a
  poor FIRST pick (no anchor, near-0 expected, live-web flake) — the opposite of
  my going-in assumption.
- **Unexpectedly BLOCKED — tau2 (my prior).** Not a light multi-turn chat bench:
  its scorer grades simulated-DB final state, so it structurally requires the
  tool-bridge our door deliberately rejects. A clean case of reading the source
  flipping the answer.
- **Unexpectedly CHEAP — BFCL's AST subset.** The single-turn non-exec/non-live
  categories are pure host-side AST match with zero sandbox — and even the
  single-turn `exec_*` ground truth is preprocessed into the SAME matcher
  (`bfcl.py:175`), so no code ever runs. A deterministic, established,
  foundational-agentic bench that fits our correctness gate almost exactly —
  gated only by a small, Seon-natural call-extraction adapter.
- **The structural surprise that ordered the whole survey:** the DeepSeek
  Non-Think anchors and the door-fitting benches are **anti-correlated**. Every
  bench DeepSeek anchors in NT (SWE, Terminal, LiveCodeBench, MCPAtlas,
  Toolathlon) needs execution hosting; every bench that fits our text door
  (bfcl, browse_comp) is unanchored. Calibration and cheap-adoption pull in
  opposite directions — so the roadmap is: adopt bfcl now for signal, then make
  the ONE infra investment (a sandbox/execution scorer host) that unlocks the
  anchored tier (LiveCodeBench-plain first, then SWE-Verified).

## Pointers

- Anchors: [[research/deepseek-published-benchmarks-2026-07-04]] (Tables B/C —
  Pro NT column).
- Integration surface: `src-inspect-ai/src/seon_inspect/{catalog,solver,cluster}.py`.
- Tier/scoring philosophy: [[eval-design]]; standing rule
  [[feedback_scorers_gate_correctness_not_style]].
- Bench sources: `reference-code/inspect-evals/src/inspect_evals/{bfcl,tau2,swe_bench,browse_comp,gaia,agent_bench,livecodebench_pro}`;
  `reference-code/{terminal-bench,gorilla-bfcl,tau2-bench,swe-bench,agentbench}`.
