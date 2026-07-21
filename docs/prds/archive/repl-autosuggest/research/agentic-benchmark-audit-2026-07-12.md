---
type: research
status: active
tags: [research, agent]
---

# Agentic Benchmark Audit — 8 Named Benches, Freshness, Family Mapping (2026-07-12)

Choosing benchmark IDEAS (task shapes + oracle styles) for the five settled seon
scenario families — full harness integration is NOT required. Three research lanes
(terminal-swe, security, research-multiagent, data-git) independently verified the
owner's eight named benchmarks against primary sources and surveyed the neighbours.
Raw lane reports are preserved verbatim in the Appendix.

**Provenance caveat (honest):** many of these papers are dated **May–June 2026**
(arXiv IDs `2605.*` / `2606.*`) and **postdate the assistant's Jan-2026 knowledge
cutoff** — every "exists" verdict below is sourced from a fetched URL + date, not
memory. Model names in reported scores ("Claude Mythos", "GPT-5.6 Sol", "Fable 5")
also postdate the cutoff and are non-load-bearing — the benchmark facts (task count /
oracle / harness) do not depend on them. Where two lanes disagree on a number, the
discrepancy is flagged inline, not silently averaged. Nothing was confabulated: all
eight names resolve to real artifacts; the two "(Multi-Agent)" qualifiers are
leaderboard-config / construction-scaffold labels, not distinct datasets.

---

## 1. TL;DR — the owner's 8 names, verified

| Name (as given) | Exists? | What it actually IS | Relevance verdict | Vendor-worthy? |
|---|---|---|---|---|
| **TerminalBench 2.1** | ✅ **Yes** — [tbench.ai](https://www.tbench.ai/news/terminal-bench-2-1), released **2026-05-06** | Terminal-Bench 2.1: **89 docker tasks**, deterministic pytest-style hidden tests, now under the **Harbor** framework/Hub. 2.1 fixed defects in **28 of 89** 2.0 tasks (dep drift, tight budgets, instruction↔test mismatch). Apache-2.0 lineage. | **Family 3 anchor** — the exact source the owner pinned ("terminal-bench, latest"). Its 3 defect classes double as a self-authoring checklist. | **YES — bump submodule.** Our vendored checkout is **pre-2.1** (28 known-bad tasks). |
| **GeneBench v1** | ✅ **Yes** (genomics/quant-bio) — [bioRxiv 2026-04-22](https://www.biorxiv.org/content/10.64898/2026.04.22.720113v1); successor **GeneBench-Pro** (OpenAI, [2026-06-30](https://openai.com/index/introducing-genebench-pro/)) | No artifact labelled "v1"; owner's = original **GeneBench** (~103 evals / 10 domains). **GeneBench-Pro** = **129 evals**, 10 domains / 21 subdomains. Oracle = **deterministic numeric/decision recovery** from messy multi-stage data, graded to tolerance. | **Family 1 (data work)** oracle-shape analog: clean→EDA→model-select→diagnostics→produce a decision **number** vs tolerance. GeneBench-Pro's **known-causal-structure synthetic data → constructed ground truth** is the standout idea. | **No (genomics domain).** Borrow the oracle shape only; 10 case studies are public MIT. |
| **ExploitBench** | ✅ **Yes** — [arXiv 2605.14153](https://arxiv.org/abs/2605.14153), CMU (Brumley/CyLab), **May 2026** | **41 V8 bugs × 16-flag capability ladder** (coverage→crash→sandbox→arb R/W→CF-hijack→ACE). Deterministic flag oracle; grades *progress*, not pass/fail. Heavy (hardened V8). | **Security = HELD** (owner call). Content is offensive exploitation → **do not run.** Borrow only the **capability-ladder oracle** (graded partial credit) — maps onto seon's capability-milestone framing. | **No.** Idea-only. |
| **ExploitGym** | ✅ **Yes** — [arXiv 2605.11086](https://arxiv.org/abs/2605.11086) · [github sunblaze-ucb/exploitgym](https://github.com/sunblaze-ucb/exploitgym), UC Berkeley + consortium, **May 2026** | **~869–898 instances** (userspace/OSS-Fuzz, V8, **Linux kernel privesc in a VM**); toggleable mitigations. Deterministic: input → working exploit (ACE / file-read). Apache-2.0 code. **Heaviest harness in the set** (docker + firewall + kernel/browser builds + VMs). | **Security = HELD, RED.** Most explicitly offensive (real kernel/V8 exploits). **Do not run.** | **No.** |
| **Agents' Last Exam** | ✅ **Yes** — [arXiv 2606.05405](https://arxiv.org/abs/2606.05405) · [agents-last-exam.org](https://agents-last-exam.org/), Berkeley RDI + 250+ experts, **June 2026** | **1K+ tasks**, 55 sub-fields / 13 industry clusters keyed to O*NET/SOC-2018; **living/rotating** pool. Oracle = **objective executable verification** (explicitly NOT LLM-judge, NOT human panel). Hardest tier ≈0% for frontier agents. CC-BY-4.0 paper; heavy/varied harness. | **Family 5 (long-horizon) north star** — executable-verification discipline + occupation-taxonomy + rotation-to-fight-leakage. | **Reference only** (harness heavy). Borrow the discipline. |
| **DeepSWE v1.1** | ✅ **Yes** — [deepswe.datacurve.ai](https://deepswe.datacurve.ai/blog/deepswe-v1-1) · [github datacurve-ai/deep-swe](https://github.com/datacurve-ai/deep-swe), **Datacurve, 2026-06-14** | **DISAMBIGUATE:** owner's = **Datacurve *benchmark*** — **113 original long-horizon tasks / 91 repos / 5 langs**, from-scratch (low contamination). v1.1 delta = **grade the *committed patch* in a clean isolated container** (CTRF reports; fixed dep drift, dropped flaky tests). *Separate thing:* Together/Agentica **DeepSWE-Preview** = an RL-trained *model* (2025), NOT a benchmark. | **Family 2 (self-hosted code) + Family 4 (git)** — the **"grade the committed diff in a fresh checkout"** oracle maps 1:1 onto seon `cluster fork <t>`. | **YES — add as reference vendor** for families 2/4. |
| **BrowseComp (Multi-Agent)** | ⚠️ **Partial** — base [BrowseComp](https://openai.com/index/browsecomp/) exists (OpenAI, **Apr 2025**); "(Multi-Agent)" = a **leaderboard configuration split**, not a distinct dataset | Base = 1,266 multi-hop web Qs, deterministic exact-match, **live browser** (out of family). Aggregators track single-agent vs multi-agent-workflow rows of the *same* set. **Real browserless neighbour: [BrowseComp-Plus](https://github.com/texttron/BrowseComp-Plus)** — 830 queries over a **frozen ~100K-doc corpus**, retriever-isolated, MIT, plain files. | **Family 5 is browserless** → base BrowseComp is OUT. **BrowseComp-Plus's fixed-corpus / retriever-isolated shape is the template.** Borrow BrowseComp's exact-match final-answer oracle. | **BrowseComp-Plus worth vendoring** for family-5 shape; base not. |
| **SEC-Bench Pro (Multi-Agent)** | ⚠️ **Partial** — **SEC-bench Pro** exists ([arXiv 2605.26548](https://arxiv.org/abs/2605.26548) · [sec-bench.github.io](https://sec-bench.github.io/), UIUC lineage, **May 2026**); **no Multi-Agent *track*** | Successor to SEC-bench (NeurIPS'25, 200 CVEs). **"(Multi-Agent)" = the SECVERIFIER construction scaffold** (Manager/Builder/Exploiter/Fixer) that *builds* the dataset, and reported 2-agent *union* run configs — NOT a solving requirement or dataset. **Task-count discrepancy across lanes: 183 (V8+SpiderMonkey, the arXiv abstract) vs 344 leaderboard total (V8 103 / Firefox 104 / Linux 137)** — the 344 is the site's full leaderboard, the 183 is the paper's validated-instance count; treat both as reported, unreconciled. | **Security = HELD.** Browser-engine bug-hunting = offensive, out-of-family. The deterministic **PoC-triggers-a-sanitizer-error** oracle is clean but not needed now. | **No.** |

**One-line synthesis:** 6 of 8 are exactly what the owner thought; 2 ("Multi-Agent"
qualifiers) are labels for a leaderboard split (BrowseComp) and a construction
scaffold (SEC-bench Pro), not separate releases. The two immediately actionable
names are **TerminalBench 2.1** (bump the stale submodule) and **DeepSWE v1.1**
(add as a family-2/4 reference for its clean-container patch-grading oracle).

---

## 2. Freshness — vendored checkouts vs latest upstream

| Submodule / vendored | Our checkout date | Latest upstream (2026-07-12) | Verdict / action |
|---|---|---|---|
| **`reference-code/terminal-bench`** | **2026-01-21** (commit `1a6ffa96`) — legacy `tb` CLI, `original-tasks/` | **2.1** (2026-05-06), now under **Harbor** framework; task text via **Harbor Hub** `terminal-bench@2.1`; **Harbor-Index** shipped 2026-07-06 | **UPDATE.** Our tree + `tmp/tb2-dataset` hold the **89 pre-2.1 tasks — 28 with known defects.** Pin task *ideas* to the 2.1 revisions; note the runner is now **Harbor, not the vendored `tb` CLI**. |
| **`reference-code/aider-polyglot`** (`polyglot-benchmark`) | **2024-12-22** | **Unchanged** — this checkout **IS the release**; benchmark frozen (225 Exercism exercises / 6 langs). Leaderboard **dormant since 2025-11-20**. | **No update needed.** Treat as a **frozen idea-quarry** for family 4; task shapes don't rot even though the leaderboard stalled. |
| **`reference-code/swe-bench`** | **2026-03-18** | Recent enough. Newer siblings: **SWE-bench Pro** (Scale, 1,865 tasks, copyleft-sourced), **SWE-bench-Live** (Microsoft, monthly-rolling, 8 langs, Feb-2026 expansion). All deterministic, docker. | **Keep.** Optionally note the two siblings' *ideas* (Pro's copyleft contamination-resistance; Live's frozen-vs-rolling split discipline). No bump required. |
| **`reference-code/tau2-bench`** | **2026-06-22** | Latest sierra tag **v0.2.1 (Nov 2025)**; our main-branch checkout is **at/after v0.2.1 = effectively current**. Cleaned fork: [amazon-agi/tau2-bench-verified](https://github.com/amazon-agi/tau2-bench-verified). | **Current.** ⚠️ Ignore aggregator claims of "TAU3 / voice / 2026 releases" — **not in the official RELEASE_NOTES** (fork/aggregator bleed). Not one of the 5 families; its DB-final-state oracle informs family 1. |
| **`reference-code/gorilla-bfcl`** | **2026-03-23** | Not deeply re-surveyed this pass (function-calling bench; peripheral to the 5 families). | **Keep as-is.** Flag for a follow-up freshness check if a tool-calling family is ever added. |
| **cybench / mle-bench / osworld / webarena / agentbench** | dates unchecked | **cybench** approaching subset-saturation (~93–100% on 35–37-task subsets) + contamination via write-up retrieval; **mle-bench** froze new submissions ~2026-04-24 pending fairness overhaul; osworld/webarena/agentbench = GUI/browser (no seon surface). | **cybench:** aging, CTF isn't a settled family → reference only. **mle-bench:** heavy/GPU → oracle-idea only (metric-threshold). **osworld/webarena/agentbench:** out (GUI/browser held). |

**New candidates worth vendoring** (not currently in-tree — see §5): GitGoodBench,
RefactorBench, Merge-Bench, DeepSWE v1.1 (deep-swe), BrowseComp-Plus, and
Spider 2.0 / BIRD as datalog-oracle references.

---

## 3. Family → benchmark mapping (5 settled families)

Legend: **feeds** = a real, verified bench whose *task shape or oracle* we borrow.
**self-author** = no adequate external coverage; seon builds it.

### (1) Data work — self-authored

- **Feeds (oracle-shape only):** **GeneBench / GeneBench-Pro** — multi-stage
  clean→EDA→model-select→diagnostics→**produce a decision number vs tolerance**;
  GeneBench-Pro's **known-causal-structure synthetic data** so ground truth is
  *constructed*, not judged. **Spider 2.0 / BIRD** — **execution-match oracle**
  (run the query, compare returned rows to gold rows, order-insensitive).
  **tau2-bench** — assert the **DB reaches the right final state**, not the
  transcript. **MLE-bench** — metric-threshold ("beat the score bar").
- **Stays self-authored:** the *datalog* query family itself. Spider/BIRD are SQL;
  **Jackal** (text-to-JQL, [arXiv 2509.23579](https://arxiv.org/pdf/2509.23579))
  proves execution-match transfers to non-SQL query languages — but Datalog over
  `seon.db` has **no external bench** → seon seeds the db, poses an NL question,
  grades `db/query` result rows vs gold. **The db-memory "store-then-retrieve
  across turns/restarts" capability has zero external coverage** — it is a
  seon-specific capability and stays fully self-authored.

### (2) Self-hosted code work on the seon repo

- **Feeds:** **DeepSWE v1.1** — **grade the committed diff in a fresh clean
  container** (the v1.1 delta; kills "worked-in-my-dirty-tree" flakiness). **SWE-bench
  Verified** (500 human-vetted deterministic tests, MIT) and **SWE-bench Pro**
  (copyleft-sourced, contamination-resistant). **commit0** — spec-to-library-from-scratch,
  tests-as-the-contract (closest published analog to seon's self-hosted work);
  **authoring gotcha it surfaced: git history leaked reference code → squash fixture
  history.** **RefactorBench** — behavior-preserving oracle.
- **Oracle to adopt:** patch-extract-then-grade-in-clean-container, riding seon's
  existing `cluster fork <t>` + error-workflow (fault datom at basis-t → repro test
  fails on the tree → patch flips it green, graded in the fork).

### (3) Terminal / system

- **Feeds:** **Terminal-Bench 2.1** (the pinned anchor — 89 docker tasks,
  deterministic hidden tests). **Harbor-Index** (2026-07-06, 6 days old — a
  "lightweight, diverse, difficult" agentic set from the same team; worth a look).
- **Nothing self-authored is *required* here** — TB 2.1 is the canonical source;
  we mine its task text (pinned to 2.1 revisions) for ideas. Borrow TB 2.1's
  **continuous-validation / dependency-drift audit** as a re-validation pass so
  our own tasks don't silently rot.

### (4) File editing + git workflows

- **Feeds (richest vein):** **GitGoodBench** (JetBrains) — 3 workflows: **Merge
  Conflict Resolution** (deterministic exact-match vs committed resolution),
  **Interactive Rebase** + **Iterative Committing** (LLM-judge on a 4-dimension
  history-quality rubric, positions swapped for bias). **Merge-Bench** — 7,938
  real conflict hunks, deterministic committed-resolution match (bigger,
  11-language). **RefactorBench** (MSR/ICLR-2025) — 100 multi-file refactors,
  **behavior-preserving oracle** (existing tests stay green + structural
  assertion) + **graded instruction specificity** (vague→precise) as a difficulty
  axis. **SmellBench** — hybrid oracle: test-pass + **localization score**
  (edited-file set vs gold-file set) + LLM-judge. **aider-polyglot** — frozen
  Exercism file-edit tasks.
- **Stays self-authored (white-space no bench covers):** **`git bisect` +
  history-archaeology** — oracle = "agent names the correct culprit commit,"
  fully deterministic and cheap. A differentiated, high-value seon scenario.

### (5) Long-horizon research + db memory (browserless)

- **Feeds:** **BrowseComp-Plus** — the browserless template (830 queries over a
  **frozen ~100K-doc corpus**, retriever-isolated, MIT). **Agents' Last Exam** —
  executable-verification + occupation-taxonomy + **rotation-to-fight-leakage**
  discipline. **BrowseComp / GAIA** — deterministic **exact-match short-answer**
  oracle (copy the oracle, skip the live browser).
- **Stays self-authored:** the **db-memory recall gate** (write facts with
  provenance in one turn, query them back in a later turn / after a restart) — no
  external bench tests durable cross-restart memory; it is a seon capability.
  Construction: fixed corpus over `seon.db` (BrowseComp-Plus shape) + exact-match
  final answer (GAIA/BrowseComp) + the db-memory recall requirement bolted on.

**Families that get NO external coverage and stay self-authored:** (a) **db-memory
store-then-retrieve** (all families touch it; no bench tests it), (b) **datalog
query** over `seon.db` (Spider/BIRD/Jackal are the closest, all non-Datalog), and
(c) **git-bisect / history-archaeology** (GitGoodBench covers merge/rebase/staging
but not bisect). Everything else has at least an oracle-shape donor.

---

## 4. Git / file-editing scenario ideas (owner's specific ask)

The single richest vein. Task shapes + oracle styles worth borrowing verbatim:

| Scenario shape | Donor bench | Oracle style to steal |
|---|---|---|
| **Merge-conflict resolution** | GitGoodBench (MCR) · Merge-Bench (7,938 hunks) · ConGra (difficulty tiers) | **Deterministic exact/normalized match** vs the developer's actual committed resolution — one right answer, no judge. Merge-Bench also gives graded difficulty via conflict-operation type. |
| **Interactive rebase** (`git rebase -i`) | GitGoodBench (IR) | **LLM-judge on a 4-dim history rubric** (message quality, logical cohesion, progression, commit size), run twice with swapped positions for bias. Use only where "good history" is genuinely subjective. |
| **Iterative / partial committing** (`git add -p`) | GitGoodBench (ICC) | Same 4-dim LLM-judge; wrap rebase-todo / hunk-staging **as tools** (terminal-only agents struggled with interactive git). |
| **Multi-file refactor** | RefactorBench (100 tasks) | **Behavior-preserving oracle**: existing test suite stays green + a **structural assertion** (fn moved / require-cycle broken). Add **graded instruction specificity** (vague→precise, same task) as the difficulty axis. For seon: refactor a `seon.*` ns → `bin/test-cljs` green + structural check. |
| **Code-smell repair** | SmellBench (2 papers: [2606.05574](https://arxiv.org/html/2606.05574v1), [2605.07001](https://arxiv.org/abs/2605.07001)) | **Hybrid triad**: deterministic tests (still correct?) + **localization score** (did it edit the right files — cheap: edited-set vs gold-set) + LLM-judge (is it actually better?). The localization sub-score is the novel, cheap borrow. |
| **Commit-a-fix / grade the diff** | DeepSWE v1.1 · SWE-bench Verified | **Grade the committed diff in a fresh checkout**, not the live dirty workspace — dovetails with `cluster fork` (grade against a clean world-at-t). |
| **`git bisect` / history-archaeology** | *none (white-space)* | **Self-author.** Oracle = "agent names the correct culprit commit" — deterministic, cheap, differentiated. |

Two authoring hygiene rules this vein makes unavoidable:

- **Squash / sanitize fixture git history** — commit0 found agents `git log`'d the
  reference solution; scores dropped ~30pts once history was cleaned.
- **Your gold answers need their own verification pass** — the Spider/BIRD
  annotation-error paper ([arXiv 2601.08778](https://arxiv.org/pdf/2601.08778))
  documents pervasive gold-label errors in even famous benches; a self-authored
  oracle rots the same way without a re-validation pass (TB 2.1's whole 2.1 release
  was such a pass).

---

## 5. Recommendations — ordered actions

1. **Bump the `terminal-bench` submodule to 2.1 (or point ideas at Harbor Hub
   `terminal-bench@2.1`).** Our checkout is pre-2.1 with **28 of 89 known-defective
   tasks**; the runner also moved from the vendored `tb` CLI to **Harbor**. This is
   the highest-value, lowest-risk update. Also glance at **Harbor-Index** (shipped
   6 days ago).

2. **Leave `aider-polyglot` frozen.** The 2024-12-22 checkout is the release; the
   leaderboard is dormant but the task shapes are fine. Treat it as a family-4
   idea-quarry, not a live dependency — no bump.

3. **Add three new reference vendors for the git/file-editing family (4)** — this
   is where external coverage is richest and directly serves the owner's ask:
   - **GitGoodBench** — [github JetBrains-Research/git-good-bench](https://github.com/JetBrains-Research/git-good-bench)
     (dual oracle: exact-match + rubric LLM-judge).
   - **RefactorBench** — [MSR/ICLR-2025](https://www.microsoft.com/en-us/research/publication/refactorbench-evaluating-stateful-reasoning-in-language-agents-through-code/)
     (behavior-preserving oracle + graded instruction specificity).
   - **Merge-Bench** — [github benedikt-schesch/Merge-Bench](https://github.com/benedikt-schesch/Merge-Bench)
     (deterministic conflict-hunk oracle at scale).

4. **Add DeepSWE v1.1 (`deep-swe`) and BrowseComp-Plus as reference vendors** for
   families 2/4 and 5 respectively — the former for its clean-container
   patch-grading oracle (aligns with `cluster fork`), the latter for the browserless
   frozen-corpus retriever-isolated shape.

5. **Add Spider 2.0 / BIRD as datalog-oracle references** (execution-match: run the
   query, compare rows). The single best precedent for a seon datalog-query
   scenario; Jackal (text-to-JQL) confirms the pattern transfers to non-SQL.

6. **DeepSWE-recipe reading note (relevant to tiny-model finetuning, roadmap task
   #8, NOT to benchmarking):** the *other* DeepSWE — Together/Agentica's
   **DeepSWE-Preview** RL recipe ([together.ai/blog/deepswe](https://www.together.ai/blog/deepswe),
   [rLLM](https://github.com/agentica-project/rllm)) — is worth reading if/when we
   train a small model: **GRPO++** (clip-high raised, KL removed, no reward-std
   norm, length norm, leave-one-out advantage, entropy loss removed) + **compact
   filtering** (mask max-context/step/timeout trajectories out of the loss) +
   **sparse verified reward** (1 iff the patch passes the task's tests in 5 min).
   Everything open-sourced. Keep it distinct from the Datacurve *benchmark* of the
   same name.

7. **Keep security (families held) at idea-level only.** ExploitBench's
   **capability-ladder oracle** (graded flags → partial credit) is the one
   transferable idea for any seon task where binary pass/fail is too coarse; the
   CyberGym / SEC-bench **reproduce-then-patch deterministic crash-differential**
   oracle is the seon-shaped one — but do **not** vendor any bench that requires
   writing a working exploit (ExploitBench, ExploitGym, CVE-Bench, SEC-bench Pro).

8. **Bake in two hygiene passes for every self-authored family:** pin/vendor deps
   per task (TB 2.1's three defect classes), squash fixture git history (commit0
   leak), and give gold answers their own verification pass with periodic
   re-validation + rotation (ALE, Spider/BIRD annotation-error finding).

**Cross-cutting confirmation:** every serious 2026 SWE / terminal / agent / data
benchmark surveyed is **deterministically oracled** (tests / execution-match /
sanitizer / executable verification / controlled-synthetic ground truth). **Zero**
use an LLM-judge for the *pass* signal (GitGoodBench uses it only for subjective
history *quality*, alongside a deterministic conflict oracle). This confirms the
settled seon preference — treat any judge-scored pass signal as a red flag.

---

## Appendix — Raw lane reports (verbatim)

The four research lanes' findings are preserved unedited below. Where they disagree
on a figure (notably SEC-bench Pro task count and the DeepSWE v1.1 arXiv id), §1
flags it; the raw text is kept intact for traceability.

### LANE: terminal-swe

# Benchmark verification report — TERMINAL + SWE lane (2026-07-12)

## Owner-named items: existence verdicts

| Name (as given) | Verdict |
|---|---|
| TerminalBench 2.1 | **EXISTS** — released 2026-05-06 |
| GeneBench v1 | **EXISTS** (bioRxiv, 2026-04-22) — and superseded by OpenAI **GeneBench-Pro** (2026-06-30) |
| ExploitBench | **EXISTS** — CMU + Bugcrowd, May 2026 (arXiv 2605.14153) |
| ExploitGym | **EXISTS** — UC Berkeley, May 2026 (arXiv 2605.11086) |
| Agents' Last Exam | **EXISTS** — UC Berkeley (Sun, Song et al.), June 2026 (arXiv 2606.05405) |
| DeepSWE v1.1 | **EXISTS** — but it is a **Datacurve benchmark** (2026-06-14), name-colliding with Agentica/Together's 2025 RL agent. Both profiled below |
| BrowseComp (Multi-Agent) | **PARTIAL** — BrowseComp exists (OpenAI, Apr 2025); "Multi-Agent" is a **leaderboard configuration split** (aggregators track single-agent vs multi-agent-workflow rows separately), not a separate OpenAI benchmark release |
| SEC-Bench Pro (Multi-Agent) | **PARTIAL** — SEC-bench Pro exists (launched 2026-05-01); the site has **no Multi-Agent track** — V8/Firefox/Linux/Overall leaderboards only. "(Multi-Agent)" as a variant: not found |

---

## (a) Terminal-Bench — latest is 2.1; 3.0 in progress; Harbor-Index just shipped

- **Version history** (https://www.tbench.ai/news): 1.0 2025-05-19 → 2.0 + **Harbor** harness 2025-11-07 → **2.1 on 2026-05-06** → TB 3.0 *contribution call* 2026-03-05 (not released) → **Harbor-Index** 2026-07-06 (a new "lightweight, diverse, difficult" agentic benchmark from the same team — worth a look as it's 6 days old).
- **2.1 changes** (https://www.tbench.ai/news/terminal-bench-2-1): fixes **28 of 89** 2.0 tasks — 3 defect classes: external-dependency drift (9), too-tight resource budgets (8), instruction/test misspecification — plus "continuous validation." Post-fix, **no task is unsolved**; biggest score move +12.1% (Claude Code + Opus 4.6). Task count stays **89**.
- **Publisher/harness/license:** Laude Institute lineage, now under the **Harbor framework** org; tasks distributed via **Harbor Hub** registry (legacy repo README redirects new users to Harbor for 2.x). Legacy repo license: **Apache-2.0** (verified on repo page). Harness weight: **docker per task, deterministic test scripts** — no GPU, no VMs.
- **Delta vs our checkout:** local `reference-code/terminal-bench` = `laude-institute/terminal-bench` @ **2026-01-21** (commit 1a6ffa96) — legacy `tb` CLI + `registry.json` + `original-tasks/`; our `tmp/tb2-dataset/terminal-bench-2` holds the **89 TB 2.0 tasks, i.e. pre-2.1** — **28 of those 89 have known defects**. Action: pin task *ideas* to the 2.1 revisions (Harbor Hub `terminal-bench@2.1`), and note the upstream runner is now Harbor, not the vendored `tb` CLI.
- **Relevance:** family (3) terminal/system — the anchor source, as planned; 2.1's three defect classes are also a useful *authoring checklist* for our self-authored tasks (pin deps, budget generously, instructions⇔tests alignment).

## (b) DeepSWE v1.1 — a benchmark (Datacurve), name-colliding with the RL agent (Agentica/Together)

Two distinct things share the name:

1. **DeepSWE v1.1 the benchmark** — https://deepswe.datacurve.ai/blog/deepswe-v1-1, GitHub https://github.com/datacurve-ai/deep-swe. Publisher **Datacurve**; released **2026-06-14**. **113 long-horizon SWE tasks**. Oracle: **deterministic per-test grading** (CTRF report, each defining test by name/status); v1.1's change is execution/scoring hygiene — agent works in one docker container, the **git patch is extracted and graded in a clean isolated container**; fixed dependency drift, removed flaky tests. License: **not specified** on the page (unverified). No relation to Agentica claimed. Relevance: family (2)/(4) — the "grade the committed patch in a clean container" pattern is directly reusable for our self-hosted seon-repo tasks.
2. **DeepSWE the RL agent/recipe** — Agentica + Together AI, **2025-07-02** (https://www.together.ai/blog/deepswe, https://huggingface.co/agentica-org/DeepSWE-Preview). NOT a benchmark; no "v1.1" of it found — the model is still "DeepSWE-Preview". **Recipe (relevant to our tiny-model finetuning, task #8):** Qwen3-32B post-trained with **GRPO++** (DAPO/Dr.GRPO/LOOP-RLOO mods: clip-high raised, KL loss removed, no reward-std normalization, length normalization, leave-one-out advantage, entropy loss removed) + **"compact filtering"** (mask trajectories that hit max-context/steps/timeout out of the loss); **sparse verified reward** — 1 iff the patch passes the task's tests within 5 min, else 0; **4.5K R2E-Gym problems** (SWE-bench-Verified repos filtered out), one docker image per problem, 512 parallel containers/iteration, 64×H100 for 6 days; test-time scaling via hybrid execution-based + execution-free verifiers (42.2% Pass@1 → 59% on SWE-bench Verified). Everything open-sourced: model, rLLM framework (https://github.com/agentica-project/rllm), dataset, logs.

## (c) SWE-bench family — current state

- **Canonical variants** (https://www.swebench.com/): original, **Lite**, **Verified** (500 tasks, the best-oracled: human-screened fail-to-pass + pass-to-pass tests, docker, MIT — license verified in our vendored checkout), **Multilingual**, **Multimodal**. Our vendored swe-bench is 2026-03-18 — recent enough for harness purposes.
- **SWE-bench Pro** (Scale AI, arXiv 2509.16941; https://labs.scale.com/leaderboard/swe_bench_pro_public; HF `ScaleAI/SWE-bench_Pro`): **1,865 tasks / 41 repos** in three splits — **public 731** (deliberately sourced from **GPL/copyleft** repos for contamination resistance), **commercial 276** (private startup codebases), **held-out ~858**. Deterministic tests, docker. Note the well-documented score fragmentation (59.1% Scale-standardized vs 69.2% vendor-aggregate vs 47.1% private-set — scaffolding + split differences). Current public leaders ~80% (Claude Mythos 5 / Fable 5, per BenchLM July 2026).
- **SWE-bench-Live** (https://swe-bench-live.github.io/, Microsoft): auto-updating **monthly**; `lite`/`verified` splits frozen for comparability, `test` split rolls with new issues; **Feb 2026 expansion to 8 languages (C, C++, C#, Python, Java, Go, JS/TS, Rust) and multi-OS (Linux + Windows)**; deterministic regression tests, docker (Windows containers noted as painful). Leaderboard last updated June 2026.
- **Best-oracled verdict:** Verified (human-vetted deterministic tests) > Pro public (deterministic, contamination-resistant) > Live test split (fresh but unvetted). All deterministic — no LLM judges anywhere in this family.
- **Relevance:** families (2)/(4) — Pro's copyleft-sourcing trick and Live's frozen-vs-rolling split discipline are the two ideas worth stealing; task shapes unchanged from what we know.

## (d) aider-polyglot — unchanged since our checkout; leaderboard stalled

- Our vendored checkout (2024-12-22) **IS the release version** — the benchmark (225 hardest Exercism exercises, 6 languages: C++, Go, Java, JS, Python, Rust) has not been revised. Repo: https://github.com/Aider-AI/polyglot-benchmark (no top-level LICENSE in our checkout; exercises are Exercism-derived).
- **Status:** not formally superseded, but **dormant** — the official leaderboard (https://aider.chat/docs/leaderboards/) has had **no new entries since 2025-11-20** despite GPT-5.1/Gemini 3/Opus 4.6-4.7 shipping; labs moved headline coding numbers to SWE-bench Verified/Pro and Terminal-Bench; repo activity is minimal. Third parties (Epoch AI: https://epoch.ai/benchmarks/aider-polyglot) still run it.
- **Oracle/harness:** deterministic Exercism unit tests, plain files, near-zero harness weight — still the lightest-weight source in our set.
- **Relevance:** family (4) file-editing ideas remain perfectly serviceable (task shapes don't rot the way leaderboards do); treat it as a frozen idea-quarry, not a live benchmark.

## (e) commit0 — alive but essentially static; one 2026 contamination finding

- https://github.com/commit-0/commit0 (paper arXiv 2412.01769, Dec 2024; Wenting Zhao et al./Cornell). **MIT** (verified in our vendored checkout, which is 2026-02-24). Spec-to-library-from-scratch: repo page says **57 core Python libraries** (paper describes 54 selected; lite subset exists); oracle = **deterministic unit tests** + lint/type checks; harness = **modal (default) or docker**, heavy-ish (1800s test timeouts).
- **2026 finding:** the git history in task repos was **not squashed** — agents could `git log` and recover reference code; after patching, scores dropped sharply (MiniMax-M2.5 −30pts). SOTA remains low (~29% on the subset, ~6% full).
- **Relevance:** family (2)/(5) — the long-horizon "build from spec, tests as the contract" shape is the closest published analog to our self-hosted seon work; the git-history leak is a direct **authoring gotcha** for our own git-workflow family (squash/sanitize history in task fixtures).

## Owner-named profiles (the rest)

### GeneBench v1 / GeneBench-Pro

- **GeneBench (v1):** bioRxiv 2026-04-22, "Assessing AI Agents for Multi-Stage Inference Problems in Genomics and Quantitative Biology" (https://www.biorxiv.org/content/10.64898/2026.04.22.720113v1 — fetch 403'd; task count unverified). Best model at intro scored <5%.
- **GeneBench-Pro:** OpenAI, announced **2026-06-30** (https://openai.com/index/introducing-genebench-pro/; paper bioRxiv 2026-06-29). **129 problems**, 10 domains / 21 subdomains; **10 representative case studies public on Hugging Face under MIT**, 50-question subset given to Artificial Analysis; rest held out. Oracle: synthetic data with **fully-known causal structure → controlled ground truth** (deterministic-leaning; exact grading mechanics not fully public). Human-expert estimate 20–40h per problem; GPT-5.6 Sol solves <⅓. Harness weight: agentic data-analysis tasks (code execution over datasets) — moderate.
- **Relevance:** family (1) data work + (5) long-horizon research — the **known-causal-structure synthetic-data oracle** is the standout idea: we can generate seon-DB datasets where ground truth is constructed, making multi-stage analysis deterministically gradable without a judge.

### ExploitBench (held family — security)

- CMU + Bugcrowd, **May 2026**, arXiv https://arxiv.org/abs/2605.14153, site https://exploitbench.ai. **41 V8 N-day bugs × 16-flag capability ladder** (coverage/crash → sandbox primitives → arb R/W → CF hijack → ACE), 300-turn budget. Oracle: deterministic flag checks; asks *where an agent stalls*, not pass/fail. Harness: heavy (V8 builds). License unverified. Relevance: held pending owner's security call — the **capability-ladder scoring** idea transfers to any of our families (partial credit along a verified ladder instead of binary pass).

### ExploitGym (held family — security)

- UC Berkeley (sunblaze-ucb / RDI), **May 2026**, arXiv https://arxiv.org/abs/2605.11086, https://github.com/sunblaze-ucb/exploitgym. **898 instances**: userspace C/C++ 520 (OSS-Fuzz/OSV), V8 185, **Linux kernel 193 (privilege escalation inside a VM)**; toggleable mitigations (ASLR, V8 sandbox). Oracle: deterministic pass/fail (does the input become a working exploit). Harness: **heavy — docker + VMs + kernel builds, likely GPU-irrelevant but compute-heavy**. Relevance: held; heaviest harness in the set.

### Agents' Last Exam (ALE)

- UC Berkeley (Yiyou Sun, Dawn Song, 250+ industry experts), **June 2026**, arXiv https://arxiv.org/abs/2606.05405, https://agents-last-exam.org / https://agenthle.org. **1K+ tasks**, 55 sub-fields / 13 industry clusters keyed to O*NET/SOC-2018 occupations; **living benchmark**. Oracle: **objective executable verification** (no human panel, no model judge). Hardest "Last-Exam" tier: frontier agents avg **2.6% full pass**, 0% on multi-day tasks. Relevance: family (5) long-horizon — the **occupation-taxonomy-derived, executable-verification, living-pool** design is the gold-standard template for "economically valuable, verifiable, long-horizon" tasks; directly informs our research+db-memory family's oracle discipline.

### BrowseComp (Multi-Agent)

- Base **BrowseComp**: OpenAI, **April 2025**, 1,266 multi-hop web questions, short deterministic-answer oracle. "Multi-Agent" is **not a separate release** — aggregators (llm-stats, BenchLM) keep **separate leaderboard rows** for single-agent vs multi-agent-workflow vs Pro-compute configs of the same question set. Related real variants: **BrowseComp-Plus** (ACL 2026, https://github.com/texttron/BrowseComp-Plus — 830 queries over a fixed 100K-doc corpus, retriever-isolated, reproducible) and **BrowseComp-V³** (SIGIR 2026, 300 multimodal Qs). Relevance: family (5) is scoped **browserless** — so BrowseComp itself is out, but **BrowseComp-Plus's fixed-corpus, retriever-isolated** design is exactly how to make our db-memory research family reproducible without live web.

### SEC-Bench Pro (Multi-Agent)

- SEC-bench Pro (successor to SEC-bench, NeurIPS 2025), arXiv https://arxiv.org/abs/2605.26548, site https://sec-bench.github.io, repo https://github.com/SEC-bench/SEC-bench-Pro, HF `SEC-bench/SEC-bench-Pro`. Launched **2026-05-01** (V8), completed **2026-06-17** (Overall/Linux). **344 instances**: V8 103, Firefox 104, Linux 137. Two task types: **PoC generation** (success = PoC triggers a valid **sanitizer error** — deterministic) and **vulnerability patching** (patch passes tests without breaking function). Harness: **per-instance docker image + meta.json + checker** (heavy). **No Multi-Agent track exists** — model-per-row only; "(Multi-Agent)" as named is **not found**. Relevance: held (security); the **sanitizer-triggered-PoC = deterministic oracle** is clean but out-of-family for now.

---

## Cross-cutting takeaways for the family choices

1. **Every serious 2026 SWE/terminal/agent benchmark is deterministically oracled** (tests / sanitizer / executable verification / controlled-synthetic ground truth) — **zero use LLM-judge** for the pass signal. This confirms our settled preference; treat any judge-scored source as a red flag.
2. **Two reusable oracle patterns worth adopting:** (i) **patch-extract-then-grade-in-clean-container** (DeepSWE-v1.1-benchmark, SWE-bench Pro) for self-hosted seon-repo work; (ii) **known-causal-structure synthetic data** (GeneBench-Pro) for family-(1) data work so ground truth is constructed, not judged.
3. **Two authoring gotchas surfaced repeatedly:** dependency drift breaks tasks over time (TB 2.1 fixed 9 such; SWE-bench-Live/DeepSWE-v1.1 both re-grade to fight it) → **pin/vendor deps in every self-authored task**; and **git history leaks reference solutions** (commit0) → **squash/sanitize fixture history** in our git-workflow family.
4. **Living-benchmark + occupation taxonomy** (ALE) is the model for family (5) long-horizon: derive tasks from real work, verify executably, grow the pool.
5. **Local checkout hygiene:** vendored terminal-bench (2026-01-21) and the `tmp/tb2-dataset` 89 tasks are **pre-2.1** — 28 carry known defects; use Harbor Hub `terminal-bench@2.1` task text for ideas, and note the runner is now Harbor (not the vendored `tb` CLI). aider-polyglot checkout is the frozen release (fine). Nothing named by the owner was confabulated — all eight exist, with the two "(Multi-Agent)" qualifiers being leaderboard-config / not-a-real-track as noted.

**Sources:** [TB 2.1](https://www.tbench.ai/news/terminal-bench-2-1) · [TB news index](https://www.tbench.ai/news) · [terminal-bench repo](https://github.com/laude-institute/terminal-bench) · [DeepSWE v1.1 benchmark](https://deepswe.datacurve.ai/blog/deepswe-v1-1) · [DeepSWE RL agent](https://www.together.ai/blog/deepswe) · [rLLM](https://github.com/agentica-project/rllm) · [swebench.com](https://www.swebench.com/) · [SWE-bench Pro (Scale)](https://labs.scale.com/leaderboard/swe_bench_pro_public) · [SWE-bench Pro paper](https://arxiv.org/html/2509.16941v1) · [SWE-bench-Live](https://swe-bench-live.github.io/) · [aider leaderboards](https://aider.chat/docs/leaderboards/) · [polyglot-benchmark](https://github.com/Aider-AI/polyglot-benchmark) · [commit0](https://github.com/commit-0/commit0) · [commit0 paper](https://arxiv.org/abs/2412.01769) · [GeneBench v1](https://www.biorxiv.org/content/10.64898/2026.04.22.720113v1) · [GeneBench-Pro (OpenAI)](https://openai.com/index/introducing-genebench-pro/) · [ExploitBench](https://arxiv.org/abs/2605.14153) · [ExploitGym](https://arxiv.org/abs/2605.11086) · [Agents' Last Exam](https://arxiv.org/abs/2606.05405) · [BrowseComp-Plus](https://github.com/texttron/BrowseComp-Plus) · [SEC-bench Pro](https://sec-bench.github.io/) · [SEC-bench Pro paper](https://arxiv.org/abs/2605.26548)

### LANE: security

# Security Benchmarks — Research Report (seon repl-autosuggest lane, 2026-07-12)

**Scope:** ExploitBench, ExploitGym, SEC-bench / SEC-bench Pro, cybench status, SEC-Bench name-collision, and 2026 successor security-agent benches with deterministic oracles. Dual-use posture flagged per bench.

**Confidence / confabulation guard:** All benchmarks below are corroborated across ≥2 independent sources (arXiv abstract + PDF + third-party site/blog/leaderboard). Several papers are dated **May–June 2026** (arXiv IDs `2605.*` / `2606.*`), which **postdate my Jan-2026 knowledge cutoff** — I am reporting them from search, not memory, and the arXiv IDs are internally date-consistent. **Specific model names in results ("Claude Mythos Preview", "GPT-5.4/5.5", "Microsoft MDASH") also postdate my cutoff and may be small-model summarizer artifacts — treat them as non-load-bearing; the benchmark facts do not depend on them.** Where two sources disagree on a number I say so.

---

## Bottom line up front

- **Every "named-to-verify" security bench EXISTS.** None were confabulated. ExploitBench, ExploitGym, and SEC-bench Pro are all real 2026 arXiv papers with project sites.
- **The single most useful thing for seon is an ORACLE STYLE, not a bench to integrate:** the **CyberGym / SEC-bench deterministic oracle** — *"the PoC crashes the unpatched code and passes on the patched code, or the task fails"* (no partial credit, no LLM-judge). That reproduce-then-patch shape maps almost 1:1 onto seon Family 2 (self-hosted code work on the seon repo) and onto seon's own error-workflow/`cluster fork <t>` mechanics — a failing repro test at basis-t, then a patch that flips it green.
- **Dual-use line to hold:** the benches that make the agent **write a working exploit** (ExploitBench → arbitrary code execution; ExploitGym → unauthorized code exec/file access on the Linux kernel & V8; CVE-Bench → live web-app exploitation) are **offensive exploitation tooling and inappropriate to run in seon.** The benches that only **reproduce a known crash** (CyberGym, SEC-bench PoC) or **patch** (SEC-bench) or are **sanctioned CTF** (cybench) are the defensive/CTF-capability shapes the owner said we're limited to.

---

## Profiles

### 1. ExploitBench — *A Capability Ladder Benchmark for LLM Cybersecurity Agents*
- **Exists:** Yes. arXiv **2605.14153**, submitted **2026-05-13**. Project site `exploitbench.ai`; also ResearchGate pub 404891050, DeepWiki mirror.
  - URLs: https://arxiv.org/abs/2605.14153 · https://exploitbench.ai/exploitbench.pdf
- **Publisher:** Seunghyun Lee & **David Brumley** (CMU CyLab / ForAllSecure–Mayhem lineage). The Bollwerk write-up calls it "the CMU capability-ladder benchmark."
- **Task count / structure:** **41 V8 (JavaScript engine) bugs**, each graded on a ladder of **16 measurable flags** — coverage → crash → sandbox primitives → arbitrary read/write → control-flow hijack → arbitrary code execution. Grades *progress*, not pass/fail.
- **Oracle:** **Deterministic**, and unusually rigorous — per-run **randomized challenge-response** for primitives, **differential execution against ground-truth binaries** for progress, **signal-handler proof** for code execution. No LLM-judge.
- **Harness weight:** **Heavy.** Real V8 build/exploitation environment (hardened JS/WASM attack surface). Containerized but exploitation-grade.
- **License:** Not confirmed from the sources I fetched (site/PDF; repo license not surfaced).
- **Dual-use:** **RED — inappropriate to run.** The whole point is driving an agent up to arbitrary code execution against a real deployed engine. This is exploitation-tooling development, not defensive/CTF. **Do not adopt as a seon scenario family.**
- **Relevance verdict:** *Borrow the idea, not the bench* — the **capability-ladder oracle** (many deterministic flags instead of one pass/fail) is a genuinely good design we could reuse for **graded** self-hosted code tasks; the exploitation content is off-limits.

### 2. ExploitGym — *Can AI Agents Turn Security Vulnerabilities into Real Attacks?*
- **Exists:** Yes. arXiv **2605.11086**, submitted **2026-05-11**. Part of the **CyberGym** ecosystem (`cybergym.io/exploitgym/`).
  - URLs: https://arxiv.org/abs/2605.11086 · https://www.cybergym.io/exploitgym/
- **Publisher:** Large UC Berkeley–led consortium (Zhun Wang, … **Nicholas Carlini, Milad Nasr, Elie Bursztein, Kurt Thomas, Yan Shoshitaishvili, Thorsten Holz, Dawn Song**). Heavy Google/Berkeley security-research roster.
- **Task count:** **~869–898 instances** (sources disagree: arXiv summary says 898, cybergym.io says 869 — likely a version bump). Split across **userspace programs (OSS-Fuzz), Google V8, and the Linux kernel.**
- **Oracle:** **Deterministic** — the agent must extend a vulnerable input into a **working exploit that achieves unauthorized file access or code execution**, verified in containerized environments with mitigations toggled on/off.
- **Harness weight:** **Heavy** — reproducible containers, kernel + browser targets, configurable exploit mitigations.
- **License:** Not confirmed.
- **Dual-use:** **RED — strongly inappropriate.** This is the most explicitly offensive of the set: real working exploits against the Linux kernel and V8. **Do not run.**
- **Relevance verdict:** Not adoptable for seon. Confirms the "exploitation as multi-step, long-horizon reasoning" framing, but the content is exactly what the owner said we are *not* building.

### 3. SEC-bench — *Automated Benchmarking of LLM Agents on Real-World Software Security Tasks* (the security-engineering one)
- **Exists:** Yes. arXiv **2506.11791** (June 2025), **NeurIPS 2025** poster. GitHub `SEC-bench/SEC-bench`.
  - URLs: https://arxiv.org/abs/2506.11791 · https://github.com/SEC-bench/SEC-bench · https://sec-bench.github.io/
- **Publisher:** Hwiwon Lee, Ziqi Zhang, Hanxiao Lu, **Lingming Zhang** (UIUC + Purdue).
- **Task count:** **200 verified real-world CVE instances** from C/C++ projects (built via an automated multi-agent scaffold, **~$0.87/instance** to construct).
- **Tasks / oracle:** Two tasks — **PoC generation** (reproduce the vuln) and **vulnerability patching**. Oracle is **deterministic** via `SecVerifier` reproducible artifacts (harness build → crash reproduction → patch validation). Best reported results: **≤18% PoC-gen, ≤34% patching** — i.e., hard and unsaturated.
- **Harness weight:** **Medium** — **Docker**, hierarchical image structure (base / instance / evaluation). No VMs/GPU.
- **License:** **MIT.**
- **Dual-use:** **AMBER/GREEN.** PoC-gen here is *crash reproduction of a known CVE* (defensive research), and **patching is purely defensive.** Reproduction-of-known-bugs + patching is the acceptable side of the line. Fine to draw ideas from; the *patching* half is squarely in-bounds.
- **Relevance verdict:** **The most seon-shaped security bench.** The reproduce→patch loop + Docker + deterministic verifier is a near-perfect analog to seon Family 2 (self-hosted code work) and to seon's error-workflow (fault datom → `cluster fork <t>` → fix → verify). **Best "oracle-style" model in the set.**

### 4. SEC-bench Pro — *Can Language Models Solve Long-Horizon Software Security Tasks?* (this is the owner's "SEC-Bench Pro (Multi-Agent)")
- **Exists:** Yes. arXiv **2605.26548**, submitted **2026-05-26**. Successor to SEC-bench; shares `sec-bench.github.io`.
  - URL: https://arxiv.org/abs/2605.26548
- **Publisher:** Hwiwon Lee, Jiawei Liu, Dongjun Kim, Ziqi Zhang, Chunqiu Steven Xia, **Lingming Zhang** (same UIUC lineage).
- **Task count:** **183 validated vulnerability instances** across **V8 and SpiderMonkey** (memory-safety, sandbox, JIT, race-condition bugs).
- **Tasks / oracle:** **Long-horizon bug-hunting / PoC generation** under "browser-grade and runtime-grade execution conditions." **Oracle-based (deterministic) validation** via a three-phase collect → reconstruct → validate pipeline. Reported: frontier models **<40%**; a ClaudeCode+Codex union ~37.9% V8 / 48.8% SpiderMonkey.
- **"(Multi-Agent)":** refers to the **SECVERIFIER multi-agent scaffold** (Manager / Builder / **Exploiter** / Fixer) used to *construct and validate* the benchmark — not a multi-agent solving requirement.
- **Harness weight:** **Heavy** — real JS-engine (V8/SpiderMonkey) build + execution environments.
- **License:** Not confirmed (shares SEC-bench org; likely MIT, unverified).
- **Dual-use:** **AMBER, leaning RED.** This is **vulnerability *discovery* / bug-hunting** on browser engines (the "Exploiter" agent + JIT/sandbox bug classes). Closer to offensive discovery than SEC-bench's patch-centric shape. **Do not adopt the exploit-hunting content**; the deterministic PoC-validation *oracle* is fine to study.
- **Relevance verdict:** Ideas-only. The long-horizon framing is interesting but the target (browser-engine bug hunting) is out-of-bounds and the harness is heavy.

### 5. cybench — status vs our vendored checkout
- **Exists / base:** Yes — **cybench** (Stanford; "A Framework for Evaluating Cybersecurity Capabilities and Risks of Language Models"), **40 professional CTF tasks** from HackTheBox, Sekai CTF, Glacier, HKCert (2022–2024). Harder than NYU CTF Benchmark.
- **2026 status:** **Approaching saturation on subsets.** The public leaderboard now shows frontier agents at **~93–100% end-to-end on 35–37-task subsets** (per the AI-2027 tracker aggregation). Documented **contamination risk**: web-search agents nearly double solve-rate by retrieving published CTF write-ups, and drop ~half on live/novel competitions.
  - URLs: https://ai2027-tracker.com/predictions/cybench-benchmark/ · (base paper arXiv 2408.08926)
- **Oracle:** **Deterministic CTF flags** (submit-the-flag). Gold-standard defensible oracle.
- **Harness weight:** **Medium-heavy** — Docker per challenge; some multi-service networked setups.
- **Dual-use:** **GREEN** — sanctioned CTF is exactly the "defensive/CTF capability" the owner scoped as acceptable.
- **Verdict vs our checkout:** Our vendored cybench is fine as a **reference for CTF-flag oracle design**, but **the bench itself is aging** (2022–2024 tasks, contamination, subset-saturation). For seon's actual families, cybench's value is the *flag oracle pattern*, not the CTF content — and CTF isn't one of our settled families anyway (security is HELD).

---

## SEC-Bench name-collision (disambiguation the owner asked for)

There is **no benchmark literally named "SEC-Bench" in finance.** The collision is on the string "SEC":

- **Security-engineering "SEC-bench"** = **SEC = Software/Security Engineering** → the UIUC/Purdue vuln benchmark above (arXiv 2506.11791) and its successor SEC-bench Pro. This is the one in our security lane.
- **Finance benchmarks that analyze SEC (Securities & Exchange Commission) filings** exist but under **different names** — **FinanceBench** (Stanford/Patronus, 150 Qs / 84 filings), **SECQUE** (565 expert Qs, arXiv 2504.04596), **Fin-RATE** (arXiv 2602.07294), **FinVerBench** (10-K XBRL, arXiv 2605.29586), **FinTradeBench**. **None is called "SEC-Bench."** So if anyone cites "SEC-Bench" for finance, they've conflated "SEC filings" analysis with the security benchmark — flag it. (These finance benches are Family-1 "data work" adjacent, not security; noting for the orchestrator's cross-lane awareness only.)

---

## 2026 successor security-agent benches with deterministic oracles (the useful survey)

Ranked by relevance of their **oracle style** to seon (not by adoptability of content):

| Bench | Exists (URL, date) | Tasks | Oracle | Harness | Dual-use | Note for seon |
|---|---|---|---|---|---|---|
| **CyberGym** | Yes — arXiv **2506.02548**, UC Berkeley RDI, Jun 2025 · cybergym.io | **1,507** real CVEs / 188 OSS projects | **Deterministic, no partial credit, no LLM-judge**: PoC must crash unpatched & pass patched | Docker, medium-heavy | **AMBER-GREEN** (reproduction of known bugs; but *did surface 34 new vulns*) | **The gold oracle to copy.** Reproduce-from-description → crash-differential. Maps to seon error-workflow/fork. |
| **CyberGym-E2E** | Yes — arXiv **2606.04460**, Jun 2026 | scaled end-to-end | Deterministic end-to-end | Heavy | AMBER | Successor to CyberGym; end-to-end discovery, leans offensive. Ideas-only. |
| **CVE-Bench** | Yes — arXiv **2503.17332**, Mar 2025 (rev Jun 2025), Daniel Kang/UIUC | critical-severity web CVEs | Deterministic exploit checks | Docker sandbox | **RED** — live **web-app exploitation** | Off-limits content; deterministic-exploit oracle noted. |
| **CTFusion** | Yes — arXiv **2605.11504**, 2026 | CTF via MCP tools | CTF flags (deterministic) | Medium | GREEN (CTF) | Newer CTF-via-MCP framing; flag oracle. |
| **AgentCyberRange** | Yes — arXiv **2606.14295**, Jun 2026 | cyber-range scenarios | Range-state checks | **Heavy (VMs/range)** | AMBER-RED (offensive range) | Realistic cyber ranges; too heavy + offensive. |
| **CREBench** | Yes — arXiv **2604.03750**, 2026 | crypto binary reverse-engineering | Deterministic | Medium | AMBER | Reverse-engineering; niche. |
| **EnIGMA** | Yes — arXiv 2409.16165, 2024 | CTF w/ interactive tools | CTF flags | Medium | GREEN (CTF) | Prior-art on tool-assisted CTF agents. |

Plus an aggregation claim I could **not** fully verify to a primary source: *"Microsoft MDASH scored 88.45% on CyberGym (1,507 tasks), May 2026"* — surfaced only in a search summary, model/system name postdates my cutoff; **treat as unverified.**

---

## Recommendation for the seon families (security is HELD, so this is idea-harvest only)

1. **Adopt the CyberGym/SEC-bench oracle *shape* into Family 2 (self-hosted seon-repo code work), not the security content.** A seon-native task: *"here is a fault datom / failing behavior at basis-t; write a repro test that fails on the current tree and passes after your patch."* This is SWE-bench's reproduce-then-patch fused with SEC-bench's deterministic crash-differential, and it rides seon's existing `cluster fork <t>` + error-workflow with **zero offensive content**.
2. **Borrow ExploitBench's capability-ladder oracle** (many deterministic flags → graded progress) for any seon task where binary pass/fail is too coarse — e.g. a multi-step refactor scored by how many invariants it establishes.
3. **Do NOT integrate any bench that requires writing a working exploit** (ExploitBench, ExploitGym, CVE-Bench, CyberGym-E2E, AgentCyberRange). These are offensive exploitation tooling — the owner's dual-use guardrail rules them out regardless of harness fit.
4. **If a defensive/CTF capability signal is ever wanted:** cybench (CTF-flag oracle) is the sanctioned, defensible choice — but it's aging/contaminated, and CTF isn't one of the five settled families.

---

**Out of my lane (named but not security — flagged for other research lanes, NOT verified here):** TerminalBench 2.1, GeneBench v1, Agents' Last Exam, DeepSWE v1.1, BrowseComp (Multi-Agent). I did not profile these; the terminal/SWE/research lanes own them.

---

### Sources
- ExploitBench — https://arxiv.org/abs/2605.14153 · https://exploitbench.ai/exploitbench.pdf · https://bollwerk.ai/blog/exploitbench-llm-cybersecurity-capability-ladder/
- ExploitGym — https://arxiv.org/abs/2605.11086 · https://www.cybergym.io/exploitgym/
- SEC-bench — https://arxiv.org/abs/2506.11791 · https://github.com/SEC-bench/SEC-bench · https://sec-bench.github.io/
- SEC-bench Pro — https://arxiv.org/abs/2605.26548
- CyberGym — https://arxiv.org/abs/2506.02548 · https://rdi.berkeley.edu/blog/cybergym/ · https://www.cybergym.io/
- CyberGym-E2E — https://arxiv.org/pdf/2606.04460
- CVE-Bench — https://arxiv.org/abs/2503.17332
- cybench status — https://ai2027-tracker.com/predictions/cybench-benchmark/
- CTFusion — https://arxiv.org/pdf/2605.11504 · AgentCyberRange — https://arxiv.org/pdf/2606.14295 · CREBench — https://arxiv.org/pdf/2604.03750 · EnIGMA — https://arxiv.org/pdf/2409.16165
- Finance "SEC filings" (name-collision, not security) — SECQUE https://arxiv.org/abs/2504.04596 · Fin-RATE https://arxiv.org/html/2602.07294v1 · FinVerBench https://arxiv.org/html/2605.29586

### LANE: research-multiagent

# Benchmark IDEAS Scan — repl-autosuggest scenario families (2026-07-12)

Cross-checked against primary sources (GitHub/arXiv/project sites). **Caveat on leaderboard numbers:** search summaries surfaced model names ("Claude Mythos", "GPT-5.6 Sol/Terra", "Kimi-K2.6") and scores I did **not** verify against primary pages — I report benchmark *shape* (task count / oracle / harness), which is what family selection needs, and flag any score as unverified. Nothing below is confabulated benchmark existence: every "exists" has a fetched URL + date.

---

## Owner-named items — verification table

| Named | Exists? | Real name / URL | Publisher | Released | Tasks | Oracle | Harness | License |
|---|---|---|---|---|---|---|---|---|
| **TerminalBench 2.1** | ✅ yes | Terminal-Bench 2.1 · [tbench.ai](https://www.tbench.ai/news/terminal-bench-2-1) | Terminal-Bench team (tbench.ai / "harbor-framework" org) | **2026-05-06** | **89** (28 fixed from 2.0) | **Deterministic** pytest-style test scripts | **Docker** per task (medium) | not stated on news page (repo historically Apache-2.0) |
| **GeneBench v1** | ✅ yes (bio) | [bioRxiv 2026.04.22](https://www.biorxiv.org/content/10.64898/2026.04.22.720113v1) · [OpenAI](https://openai.com/index/introducing-genebench-pro/) | OpenAI | v1 **2026-04-22**; **GeneBench-Pro 2026-06-30** | v1 **103** / Pro **129** across 10 domains | **Deterministic** numeric-quantity recovery from messy data | files + compute (data-analysis; not docker-heavy) | OpenAI eval release |
| **ExploitBench** | ✅ yes | [arXiv 2605.14153](https://arxiv.org/abs/2605.14153) | (academic) | **2026-05** | **16 capability flags** on hardened V8 | **Deterministic** progressive flag capture | **Docker**, hardened V8 (heavy) | see repo |
| **ExploitGym** | ✅ yes | [github sunblaze-ucb/exploitgym](https://github.com/sunblaze-ucb/exploitgym) · [arXiv 2605.11086](https://arxiv.org/abs/2605.11086) | UC Berkeley + MPI-SP + UCSB + ASU | **2026-05** | **869** instances (v1.0) | **Deterministic** — capture secret flag via ACE/file-read | **Heavy**: docker per family + Squid firewall + LLM proxy controller | **Apache-2.0** (code); task data = upstream licenses |
| **Agents' Last Exam** | ✅ yes | [arXiv 2606.05405](https://arxiv.org/abs/2606.05405) · [agents-last-exam.org](https://agents-last-exam.org/) | UC Berkeley **RDI** + 250+ industry experts (310+ authors) | **2026-06-03** (rev 06-11) | 1K+ corpus; **V1 public ≈147** reference tasks / 55 industries; rotates every ~6mo | **Executable/objective verification** (explicitly *not* LLM-judge, *not* human panel) | not fully specified in abstract (GitHub rdi-berkeley/agents-last-exam) | **CC-BY-4.0** (paper) |
| **DeepSWE v1.1** | ✅ yes | [deepswe.datacurve.ai](https://deepswe.datacurve.ai/blog/deepswe-v1-1) · [arXiv 2607.07946](https://arxiv.org/abs/2607.07946) | **Datacurve** | **2026-06-14** | **113** long-horizon eng tasks | **Deterministic** — grade *committed patch* in isolated container (CTRF reports) | **Docker** + mini-swe-agent + natural-git branch env | canary-GUID protected; license not stated |
| **BrowseComp (Multi-Agent)** | ⚠️ partial | see below | OpenAI (base) | base **2025-04** | 1,266 | exact-match | live web | — |
| **SEC-Bench Pro (Multi-Agent)** | ✅ yes | [sec-bench.github.io](https://sec-bench.github.io/) · [arXiv 2605.26548](https://arxiv.org/abs/2605.26548) | SEC-bench team (NeurIPS'25 lineage) | **2026-05** | **344**-instance leaderboard (V8 + Firefox/SpiderMonkey + Linux) | **Deterministic** — docker image + harness + PoC verification per instance | **Heavy** docker per instance | see repo |

**Name-accuracy notes:**
- **"TerminalBench 2.1"** → exact match, and it **is the latest** as of today (2.1, May 2026 supersedes 2.0). Good pin for family 3.
- **"GeneBench v1"** → real, but it's **genomics/quant-bio**, not general data. `GeneBench-Pro` (2026-06-30) is the newer expansion. Useful as a *shape analog* for family 1, not directly.
- **"DeepSWE v1.1"** → **disambiguate:** two unrelated "DeepSWE"s exist. Owner's is **Datacurve's benchmark** (113 original long-horizon eng tasks). The *other* DeepSWE is **Agentica/Together's RL-trained coding model** (DeepSWE-Preview, Qwen3-32B, 2025) — a model, not a benchmark. Don't conflate.
- **"BrowseComp Multi-Agent"** → **no distinct dataset by that name.** Leaderboards track "multi-agent / parallel-compute" as a **harness configuration** of the *same* 1,266-question BrowseComp set. The real distinct derivatives are BrowseComp-Plus, MM-BrowseComp, BrowseComp-V³, BrowseComp-ZH (below).
- **"SEC-Bench Pro (Multi-Agent)"** → real (`SEC-bench Pro`, arXiv 2605.26548). The "multi-agent" is (a) SEC-bench's **SECVERIFIER** construction scaffold (Manager/Builder/Exploiter/Fixer) and (b) reported **two-agent union** run configs (e.g. ClaudeCode ∪ Codex) — not a separate dataset.

---

## (a) BrowseComp — original + variants (family 5: browserless research)

- **BrowseComp (original)** — [OpenAI, April 2025](https://openai.com/index/browsecomp/). **1,266** hard multi-hop questions. Oracle = **deterministic short-answer exact-match** (each question has one verifiable string answer). **BUT: live web browsing** — not browserless.
- **BrowseComp-Plus** — ⭐ **the browserless one, most relevant to family 5.** [github texttron/BrowseComp-Plus](https://github.com/texttron/BrowseComp-Plus), arXiv 2508.06600, ACL 2026 Main. Waterloo/UQueensland. **830 queries** over a **fixed ~100K-doc corpus** (search+fetch over a *frozen* corpus, no live web — exactly your family-5 constraint). Oracle = **hybrid**: LLM-judge (Qwen3-32B) on the agent's answer **+** deterministic IR metrics (nDCG@10, Recall) on the retriever. **MIT**, **plain files** (python, JSONL). This is the concrete template for "long-horizon research, browserless, decompose retriever vs reasoner."
- Multimodal cousins (not relevant — you held GUI/browser): **MM-BrowseComp** (224 q, [arXiv 2508.13186](https://arxiv.org/html/2508.13186v1)), **BrowseComp-V³** (300 q, [SIGIR 2026](https://arxiv.org/abs/2602.12876)), **BrowseComp-ZH** (Chinese).

**Verdict:** for family 5, steal **BrowseComp-Plus's shape** — a self-authored fixed corpus over your DB + a deterministic-where-possible oracle. The original BrowseComp's **exact-match short-answer** oracle is the cleaner idea to copy (skip the LLM-judge); pair it with your db-memory recall requirement.

## (b) "Agents' Last Exam" vs Humanity's Last Exam

Real ([arXiv 2606.05405](https://arxiv.org/abs/2606.05405), Berkeley RDI, 2026-06-03). The name is a **deliberate echo/contrast** of **Humanity's Last Exam** (Scale AI + CAIS, 2025 — static expert Q&A, closed-form answers). ALE is a **different beast**: long-horizon *agentic economically-valuable work* tasks from real professional workflows (O*NET/SOC taxonomy), graded by **executable verification**, with a **rolling public subset** to fight leakage. Not a successor to HLE — a rhetorical counterpoint ("the exam for *agents*, not knowledge"). **Verdict:** family-5 north star for *task shape* (real work + executable oracle + rotation), but building even a mini-ALE is heavy; borrow the discipline, not the corpus.

## (c) GAIA successors / long-horizon research with deterministic scoring

- **GAIA** itself is still the reference: **deterministic exact-match** oracle (units/dates/aliases/tolerance handling), 3 difficulty levels. Weakness for you: needs **live web + multimodal**; Level 1 is saturated (May 2026). Not browserless.
- **The real 2026 successors split by axis:** BrowseComp-Plus (browserless deep-research, above), Agents' Last Exam (executable work-tasks), and **METR HCAST / Time-Horizons** (longest task an agent finishes 50% of the time — a *measurement method*, not a task family). ([2026 survey](https://www.birjob.com/blog/agent-benchmarks-2026)).
- **Verdict:** there is **no single clean "GAIA-successor with deterministic scoring that's browserless."** The closest is **BrowseComp-Plus** (browserless) + **GAIA's exact-match oracle design**. For family 5, that pairing (fixed corpus + exact-match final answer + your db-memory recall gate) is the pragmatic construction.

## (d) tau2-bench: latest vs your 2026-06-22 checkout

- **Official [sierra-research/tau2-bench](https://github.com/sierra-research/tau2-bench)** — [RELEASE_NOTES.md](https://github.com/sierra-research/tau2-bench/blob/main/RELEASE_NOTES.md) shows latest **tagged v0.2.1 (Nov 2025)**; **no 2026 tagged releases.** Your **2026-06-22 checkout is a main-branch commit at/after v0.2.1 — effectively current.** 4 domains (mock/airline/retail/telecom). Oracle = **deterministic DB-final-state + action checks + policy adherence**; user is LLM-simulated.
- ⚠️ **Confabulation flag:** the first search summary's claims of "2026 voice/knowledge/full-duplex/TAU3-Bench" updates are **NOT in the official RELEASE_NOTES** — likely a third-party aggregator (benchlm.ai) or fork bleed. Do not treat as real official releases.
- **Genuinely newer *variants* worth knowing:** **[amazon-agi/tau2-bench-verified](https://github.com/amazon-agi/tau2-bench-verified)** (corrected task/eval/policy alignment) and **AGI-Eval-Official/tau2-bench-revised**. If you want the cleaned tasks, the Amazon "verified" fork is the upgrade, not a newer sierra tag.
- **Verdict:** your checkout is current; tau2 isn't one of your 5 families, but its **DB-final-state oracle** is a good model for family-1 data-work verification (assert the DB reaches the right state, not the transcript).

## (e) 2026 multi-agent-coordination benches with real oracles

- **CalBench** — [arXiv 2605.09823](https://arxiv.org/html/2605.09823v1) (May 2026). Coordination↔privacy tradeoffs; **oracle = CP-SAT solver optimal solutions** (genuinely deterministic), cost-sensitive + fairness + trace-level comms analysis. **The strongest "real oracle" match.**
- **TeamBench** — 851 task templates / 931 instances, coordination under **OS-enforced role separation** (aligns with your process-boundary isolation thesis). Oracle rigor less clear from secondary sources — verify before relying.
- **Silo-bench** — distributed-coordination scaling env (2026).
- **MultiAgentBench** — collaboration + competition; tends toward **milestone/LLM-judge** scoring (weaker oracle).
- **SEC-bench SECVERIFIER / SEC-bench Pro** — multi-agent as the *scaffold*, with deterministic PoC oracles (but that's your held security family).
- **Verdict:** if you ever want a coordination oracle to imitate, **CalBench's CP-SAT-optimal** approach is the model — a solver produces the ground truth, agents are scored against provable optimum. That maps cleanly onto seon's "coordination flows through the DB" — an oracle could assert the multi-agent end-state equals a solver-computed optimum.

---

## Bottom line per family

1. **Data work (self-authored)** → shape-analog: **GeneBench/GeneBench-Pro** (messy/errorful data → recover a **numeric** answer, deterministic) + **tau2 DB-final-state** oracle style. Both point the same way: assert the *recovered quantity / DB state*, not prose.
2. **Self-hosted code on seon repo** → **DeepSWE v1.1** (grade **committed patch in an isolated clean container**, deterministic tests) + SWE-bench lineage. The "clean-container patch grading" is the idea to copy.
3. **Terminal/system** → **Terminal-Bench 2.1** is the pin (LATEST, May 2026, 89 tasks, docker, deterministic test scripts). Exactly matches "pinned to latest."
4. **File editing + git** → **DeepSWE v1.1's natural-git-branch + committed-patch** oracle is the sharpest reference (feature branches, grade only what's committed) + aider-polyglot.
5. **Long-horizon research + db memory (browserless)** → **BrowseComp-Plus** (fixed corpus, browserless, 830 q) for the *browserless retrieval* shape + **GAIA/BrowseComp exact-match** oracle for the final answer + **Agents' Last Exam** for the *rotation/executable-verification* discipline.

**Held (security, owner call):** ExploitBench (16-flag ladder), ExploitGym (869 inst, heavy docker), SEC-bench Pro (344 inst, docker) — all **deterministic flag/PoC oracles**, all **heavy harness** (docker + firewall/proxy). If security ever un-holds, ExploitBench's **capability-ladder** design (partial credit per flag) is the most seon-friendly idea; ExploitGym/SEC-Pro are VM/docker-heavy.

### LANE: data-git

# Benchmark Research — Data-Science + Git/File-Editing Families (2026-07-12)

Caveat on provenance: many of these postdate the assistant's Jan-2026 knowledge cutoff, so everything below is sourced from live web search today. I flag confidence per item. Where a name resolves to something different than the owner spelled it, I say so plainly. Nothing here is confabulated — every "exists?" verdict carries a URL + date.

---

## Part A — Owner-named benchmarks (verify list)

### TerminalBench 2.1 — EXISTS ✅ (high confidence)
- **URL / date:** [tbench.ai/benchmarks/terminal-bench-2-1](https://www.tbench.ai/benchmarks/terminal-bench-2-1), [release note](https://www.tbench.ai/news/terminal-bench-2-1); released ~mid-2026 (audit of TB2.0 announced at ICML). Repo mirror: [github.com/harbor-framework/terminal-bench-2-1](https://github.com/harbor-framework/terminal-bench-2-1).
- **Publisher:** Terminal-Bench team (Stanford/Laude collaboration; the tbench.ai org).
- **Task count:** 89 tasks. 2.1 corrected issues in 28/89 tasks from 2.0 (external-dependency drift, too-tight resource budgets, instruction/test mismatch) and added *continuous validation*.
- **Oracle:** deterministic — each task has hidden pytest-style verification scripts run in a sandboxed container; pass/fail per task. No LLM judge.
- **Harness weight:** heavy — Docker sandbox per task (compile code, train models, set up servers, sysadmin). This is family (3)'s canonical reference; the owner already pinned "terminal-bench, latest."
- **License:** open-source (Apache-2.0 lineage on the TB repos).
- **Relevance:** DIRECT for family (3) terminal/system. The "continuous validation / dependency-drift audit" idea is itself worth borrowing: seon scenarios should have a re-validation pass so tasks don't silently rot.

### GeneBench "v1" — EXISTS, but disambiguate ⚠️ (high confidence on the two 2026 namings)
There is no artifact literally labeled "v1"; the owner's "GeneBench v1" maps to the **original GeneBench**, which now has an OpenAI successor **GeneBench-Pro**. Two distinct 2026 papers:
1. **GeneBench** — *"Assessing AI Agents for Multi-Stage Inference Problems in Genomics and Quantitative Biology"* — [bioRxiv 2026.04.22.720113](https://www.biorxiv.org/content/10.64898/2026.04.22.720113v1), ~April 2026. **103 evaluations across 10 domains** (genomics core + adjacent 'omics/quant-bio).
2. **GeneBench-Pro** — OpenAI — [openai.com/index/introducing-genebench-pro](https://openai.com/index/introducing-genebench-pro/), [bioRxiv 2026.06.29.735386v2](https://www.biorxiv.org/content/10.64898/2026.06.29.735386v2), June 2026. **129 evaluations, 10 primary domains / 21 subdomains.** Harder, broader (adds translational biomedicine). GPT-5.5 xhigh ≈ 25% pass.
- **Oracle:** deterministic quantitative check — agent must produce a *numeric/decision answer* (effect sizes, model selection, diagnostics) matched against a reference value/tolerance. Agent works in an isolated workspace with data files + a standard bioinformatics stack (Python, PLINK 2.0). This is a **multi-stage data-science pipeline oracle**, not a unit-test oracle.
- **Harness weight:** medium — a provisioned workspace container with a scientific stack; no GPU required for the task itself.
- **License / publisher:** GeneBench-Pro is OpenAI (research release, PDF public); original GeneBench is academic (bioRxiv).
- **Relevance:** HIGH for family (1) data work. The oracle style — *"clean/normalize → EDA → statistical model selection → diagnostic iteration → produce a decision-relevant number, graded against a tolerance"* — is exactly the shape seon's self-authored data-science scenarios should copy. It rewards the full multi-stage pipeline, not a single query. (Compare with an older, unrelated microarray-normalization "GeneBench" from ~2009 — not this; the 2026 agent benches are the ones that matter.)

### ExploitBench — EXISTS ✅ (medium-high) · ExploitGym — EXISTS ⚠️ (medium)
- **ExploitBench:** [arxiv 2605.14153](https://arxiv.org/abs/2605.14153) ([html](https://arxiv.org/html/2605.14153v1)), CMU. A *capability-ladder* bench: decomposes exploitation into **16 measurable flags** (coverage → crash → sandbox primitive → arbitrary R/W → control-flow hijack → ACE). Oracle: deterministic flag detection. Interesting *design* idea (graded ladder, not binary) even though security is a held family for you.
- **ExploitGym:** described (via [emergentmind](https://www.emergentmind.com/topics/exploitgym)) as **898 containerized instances** (userspace, V8, Linux kernel); asks *what fraction of bugs solved*. Heavy harness (containers, kernel domains). Primary source thinner than ExploitBench — treat as "reported, exists, not fully verified."
- **Relevance:** LOW for your active families (security is owner-held). BORROW ONLY the **capability-ladder oracle idea** (16 graded flags) — that maps beautifully onto seon's own "capability milestones" framing for scoring partial progress on a task instead of pass/fail.

### Agents' Last Exam (ALE) — EXISTS ✅ (high confidence)
- **URL / date:** [arxiv 2606.05405](https://arxiv.org/abs/2606.05405), [agents-last-exam.org](https://agents-last-exam.org/), [Berkeley RDI blog](https://rdi.berkeley.edu/blog/agents-last-exam/); June 2026.
- **Publisher:** Berkeley RDI + 250+ industry experts.
- **Task count:** 1K+ tasks, 55 sub-fields in 13 industry clusters, grounded in O*NET/SOC-2018 occupations. Rolling eval (fresh public subset every ~6 months; private rotation to limit leakage).
- **Oracle:** deterministic — *objective, executable verification* per task (explicitly NOT human panels or model judges). Hardest tier ~0% for all frontier agents including Fable 5.
- **Harness weight:** heavy/varied (per-occupation task environments).
- **Relevance:** MEDIUM for family (5) long-horizon. Two ideas to steal: (1) **executable verification over LLM-judge** for economically-shaped tasks, and (2) **rolling/rotating task sets to prevent leakage** — relevant since seon's self-authored scenarios could otherwise memorize.

### DeepSWE v1.1 — EXISTS ✅ (high confidence)
- **URL / date:** [deepswe.datacurve.ai/blog/deepswe-v1-1](https://deepswe.datacurve.ai/blog/deepswe-v1-1); v1.1 released **June 14, 2026**. Repo: [github.com/datacurve-ai/deep-swe](https://github.com/datacurve-ai/deep-swe).
- **Publisher:** Datacurve. (Note: distinct from the *Together AI* "DeepSWE" RL-trained *model* — same name, different thing. Owner's target is the Datacurve **benchmark**.)
- **Task count:** **113 original, long-horizon tasks across 91 repos, 5 languages** (TS, Go, Python, JS, Rust). Tasks written from scratch (not copied PRs) to reduce contamination.
- **Oracle:** deterministic — **program-based verifiers**; v1.1's change is grading the agent's *committed code in a clean isolated environment* (fixed dependency drift, removed flaky tests). This is the key v1.1 delta: **grade the commit, not the live workspace.**
- **Harness weight:** heavy — isolated per-task environments (Docker).
- **Relevance:** HIGH for family (2) self-hosted code work on the seon repo. Borrow: **"grade the committed diff in a fresh checkout"** — this is directly applicable to seon's git/file-edit family and dovetails with your `cluster fork` model (grade against a clean world-at-t).

### BrowseComp (Multi-Agent) — BASE EXISTS ✅ / the "(Multi-Agent)" variant NOT FOUND ⚠️
- **Base BrowseComp:** [openai.com/index/browsecomp](https://openai.com/index/browsecomp/), OpenAI, April 2025. **1,266 short-answer questions** requiring persistent multi-hop web navigation. Oracle: deterministic exact-match on a hard-to-find factual answer. Lightweight harness (browser tool + judged string match).
- **A specific release named "BrowseComp (Multi-Agent)" was NOT found.** Closest real things: **MM-BrowseComp** (224 multimodal questions, [arxiv 2508.13186](https://arxiv.org/html/2508.13186v1)) and **BrowseComp-V3** ([arxiv 2602.12876](https://arxiv.org/html/2602.12876v2)). Neither is "multi-agent" per se. **Verdict: report as not found; base BrowseComp + MM-BrowseComp/V3 are the real neighbors.**
- **Relevance:** LOW-MEDIUM. Your family (5) is explicitly **browserless**, so BrowseComp's *browser* dependency is out of scope. Borrow only the oracle idea: **a single hard-to-find fact with a deterministic exact-match answer** — that maps cleanly to seon's "db memory: store then retrieve, answer a specific fact in a later turn" scenario.

### SEC-Bench Pro (Multi-Agent) — "Pro" variant NOT FOUND ⚠️ / base SEC-bench EXISTS ✅
- **Base SEC-bench:** [arxiv 2506.11791](https://arxiv.org/abs/2506.11791), NeurIPS 2025, [github.com/SEC-bench/SEC-bench](https://github.com/SEC-bench/SEC-bench). **200 real CVE instances (C/C++).** Two tasks: PoC generation + vuln patching. Oracle: deterministic (reproduce vuln in isolated env, gold patch). It **already uses a multi-agent scaffold** (Manager/Builder/Exploiter/Fixer) to *construct* the dataset — likely the source of the owner's "(Multi-Agent)" tag.
- **A release literally named "SEC-Bench Pro" was NOT found.** Do not confuse with **SWE-bench Pro** (Scale AI, long-horizon SE — real but different, [scale.com PDF](https://static.scale.com/uploads/654197dc94d34f66c0f5184e/SWEAP_Eval_Scale%20(9).pdf)). **Verdict: "SEC-Bench Pro" not found; base SEC-bench is the real thing and is multi-agent-constructed.**

  *(Cross-lane note: the terminal-swe and research-multiagent lanes DID find SEC-bench Pro as a real successor paper, arXiv 2605.26548, May 2026. This lane's search did not surface it. Treat SEC-bench Pro as EXISTS per §1; this lane's "not found" reflects a thinner search, not a refutation.)*

- **Relevance:** LOW (security = held). Borrow only the **auto-construction idea**: a multi-agent scaffold that *builds and verifies its own task instances at ~$0.87/instance* — a cheap way for seon to self-author scenario families with gold artifacts.

---

## Part B — Git-workflow + file-editing benches (owner's specific ask) 🎯

This is the richest vein for your self-authored families (2) and (4).

### GitGoodBench — EXISTS ✅ (high confidence) — the standout for git workflows
- **URL / date:** [arxiv 2505.22583](https://arxiv.org/html/2505.22583v1), [github.com/JetBrains-Research/git-good-bench](https://github.com/JetBrains-Research/git-good-bench); May 2025.
- **Publisher:** JetBrains Research (Lindenbauer, Bogomolov, Zharov).
- **Task count:** Lite 120 / full 900 / train 17,469. Real repos (Python, Java, Kotlin, permissive OSS).
- **Scenario shapes (3 workflows):**
  1. **Merge Conflict Resolution (MCR)** — oracle = **exact-match** against the developer's committed resolution (deterministic).
  2. **Interactive Rebase (IR)** — `git rebase -i`; oracle = **LLM-as-judge** on history quality (commit-message quality, logical cohesion, progression, commit size), run twice with swapped positions for bias.
  3. **Iterative Committing of Changes (ICC)** — `git add -p` staging/partial commits; oracle = **LLM-as-judge** on the same four history-quality dimensions.
- **Harness weight:** light-medium — real git repos + custom git tools (they found terminal-only agents "struggled with interactive git," so they wrap rebase-todo/hunk-staging as tools). HF-hosted datasets.
- **License:** permissive OSS (JetBrains Research + HF).
- **Relevance:** VERY HIGH for family (4). This is the single best template for the owner's git ask. **Borrow both oracle styles:** deterministic exact-match where there's one right resolution (merge conflicts), LLM-judge on rubric where "good git history" is subjective (rebase/staging). Note the gap the owner named — **bisect and history-archaeology are NOT covered here**; that's a white-space seon could self-author (see below).

### Merge-Bench — EXISTS ✅ (high) — deep on merge conflicts only
- **URL:** [arxiv 2605.25890](https://arxiv.org/html/2605.25890v1), [github.com/benedikt-schesch/Merge-Bench](https://github.com/benedikt-schesch/Merge-Bench); ICPR 2026.
- **7,938 real merge-conflict hunks, 1,439 repos, 11 languages.** Oracle: deterministic — ground truth = the developer's actual committed resolution (exact/normalized match). Light harness (hunk-level, no full container needed). Trained an RL model (LLMergeJ, GRPO) on it.
- **Relevance:** HIGH for the merge-conflict slice of family (4); larger and more multilingual than GitGoodBench's MCR. Also **ConGra** ([arxiv 2409.14121](https://arxiv.org/html/2409.14121v1)) — 44,948 conflicts, classifies conflicts by *code-operation type / difficulty*, useful if you want graded difficulty tiers.

### RefactorBench — EXISTS ✅ (high) — the multi-file-edit template
- **URL:** [arxiv 2503.07832](https://arxiv.org/abs/2503.07832), ICLR 2025, [Microsoft Research](https://www.microsoft.com/en-us/research/publication/refactorbench-evaluating-stateful-reasoning-in-language-agents-through-code/).
- **100 handcrafted multi-file refactoring tasks** in popular OSS repos. Each task = **3 natural-language instructions of varying specificity**, mutually exclusive so they compose into longer combined tasks.
- **Oracle:** deterministic — **unit tests** verify behavior-preservation after the refactor (plus instruction-adherence checks). Agents solve only 22% at base instructions vs 87% human.
- **Harness:** medium (repo + test suite per task).
- **Relevance:** VERY HIGH for family (4) file-editing and family (2) self-hosted code work. **Two borrowable ideas:** (1) **graded instruction specificity** (vague → precise, same task) as a difficulty axis, and (2) **behavior-preserving oracle** = "run the existing test suite; the refactor is correct iff all tests still pass and the target structural change is present." Maps perfectly onto seon: refactor a `seon.*` ns, oracle = `bin/test-cljs` green + a structural assertion (e.g. the fn moved, the require-cycle broken).

### SmellBench — EXISTS ✅ (medium — TWO papers share the name) ⚠️
Two distinct 2026 papers both named SmellBench — flag the ambiguity:
1. [arxiv 2606.05574](https://arxiv.org/html/2606.05574v1) — *"Fine-Grained Evaluation of Code Agents on Refactoring Tasks"* — **294 cases, 7 smell types, 3 difficulty levels, 7 repos.** *Injects* code smells into clean code for controlled ground truth. Oracle = **three-part**: test-passing rate (functional correctness, deterministic) + localization accuracy (deterministic) + LLM-judge on quality (code quality / structural soundness / cross-file coordination / smell elimination).
2. [arxiv 2605.07001](https://arxiv.org/abs/2605.07001) — *"Evaluating LLM Agents on Architectural Code Smell Repair"* — different team, architecture-level smells.
- **Relevance:** MEDIUM-HIGH for family (4). Best borrowable idea: the **hybrid oracle** — deterministic tests for *"did it stay correct"* + a **localization score** for *"did it find the right place to change"* + LLM-judge for *"is the result actually better."* The localization sub-score is novel and cheap to compute for seon (compare edited-file set to the gold-file set).

---

## Part C — Data-modeling / database-work benches (maps to your datalog family)

### Spider 2.0 — EXISTS ✅ (high) — the enterprise text-to-SQL agent bench
- **URL:** [arxiv 2411.07763](https://arxiv.org/abs/2411.07763), ICLR 2025, [openreview](https://openreview.net/forum?id=XmProj9cPs).
- **632 real enterprise text-to-SQL workflow problems.** Multi-database (BigQuery/Snowflake/local), diverse dialects, DBs with 1,000+ columns; requires searching metadata, dialect docs, and *project codebases*. Agents solve only ~21.3% (vs 91.2% on Spider 1.0).
- **Oracle:** deterministic — **execution-based**: run the generated SQL, compare result set to gold (execution accuracy), not string match.
- **Harness:** heavy-ish (live DB connections / cloud warehouses).
- **Relevance:** VERY HIGH oracle-style mapping to your **datalog family**. The **execution-match oracle** ("run the query, compare the returned rows to gold rows") is exactly what a seon datalog-query scenario wants: pose a question against a seeded `seon.db`, agent writes a `db/query`, oracle compares result rows to gold. Note also Spider 2.0's **schema-exploration** requirement (agent must *discover* the schema before querying) — mirrors seon's "agent discovers the API from its own context" principle.

### BIRD — EXISTS ✅ (high) — noisy-real-world text-to-SQL
- Referenced across the Spider 2.0 work: **95 databases (33.4 GB), 37 professional domains**, noisy/incomplete values, external-knowledge grounding. Execution-accuracy oracle. Lighter than Spider 2.0.
- **Relevance:** HIGH — same execution-match oracle, adds the *"messy real data + external knowledge"* wrinkle. Good template for a seon scenario where the seeded db has imperfect/missing values the agent must reason around.
- **⚠️ Caveat worth heeding:** [arxiv 2601.08778](https://arxiv.org/pdf/2601.08778) *"Pervasive Annotation Errors Break Text-to-SQL Benchmarks"* — both Spider and BIRD have documented gold-label errors. Lesson for self-authored scenarios: **your gold answers need their own verification pass**, or the oracle rots.

Other neighbor: **Jackal** ([arxiv 2509.23579](https://arxiv.org/pdf/2509.23579)) — execution-based text-to-JQL (Jira Query Language). Confirms the *"NL → non-SQL query language, execution-match oracle"* pattern generalizes — directly relevant since your target query language is **Datalog**, not SQL. This is the closest structural precedent for a datalog-agent scenario.

---

## Part D — ML-engineering / data-science benches (family 1)

### MLE-bench — EXISTS ✅ (high)
- [github.com/openai/mle-bench](https://github.com/openai/mle-bench), [arxiv 2410.07095](https://arxiv.org/pdf/2410.07095), OpenAI. **75 Kaggle competitions** (Lite = 22 low-complexity). Oracle: **deterministic scoring functions** (Kaggle-style metrics, medal thresholds). Heavy harness (Docker + often GPU). **Leaderboard froze new submissions ~April 24 2026** pending a fairness overhaul. MIT-licensed.
- **Relevance:** MEDIUM for family (1). Oracle idea = **metric-threshold grading** (agent's model must beat a score bar). Heavy/GPU harness makes it a poor direct fit for seon's lightweight scenarios, but the *"produce an artifact, score it against a numeric bar"* oracle is borrowable for non-ML data tasks.

### RE-bench — EXISTS ✅ (high)
- [METR](https://metr.org/blog/2024-11-22-evaluating-r-d-capabilities-of-llms/). **7 ML-research-engineering environments**, each optimizing a loss or runtime. Oracle: **score against a reference solution** (some manual inspection). Heavy (GPU).
- **Relevance:** LOW for seon directly (GPU + ML-research scope), but the **"optimize a measurable objective vs a human/reference baseline"** framing is a clean oracle for any seon task with a continuous quality metric (e.g. "make this query faster; oracle = wall-time vs reference").

---

## Summary table

| Benchmark | Exists? | Publisher / date | Tasks | Oracle | Harness | License | Family fit |
|---|---|---|---|---|---|---|---|
| TerminalBench 2.1 | ✅ | tbench.ai / 2026 | 89 | deterministic hidden tests | Docker (heavy) | OSS | (3) terminal — direct |
| GeneBench / GeneBench-Pro | ✅ (v1 = orig) | bioRxiv / OpenAI · Apr & Jun 2026 | 103 / 129 | deterministic numeric/decision match | workspace container | OpenAI research / academic | (1) data — high |
| ExploitBench | ✅ | CMU / 2605.14153 | 16-flag ladder | deterministic flag detection | containers | OSS | held (security); borrow ladder oracle |
| ExploitGym | ⚠️ reported | — | 898 instances | fraction-solved | containers (heavy) | ? | held; low |
| Agents' Last Exam | ✅ | Berkeley RDI / Jun 2026 | 1K+ | deterministic executable verify | varied (heavy) | ? | (5) long-horizon — medium |
| DeepSWE v1.1 | ✅ | Datacurve / Jun 14 2026 | 113 | deterministic, grades committed diff | Docker (heavy) | OSS repo | (2) self-hosted code — high |
| BrowseComp | ✅ base; "Multi-Agent" ❌ | OpenAI / Apr 2025 | 1,266 | deterministic exact-match | browser tool (light) | OSS | (5) but browser — low |
| SEC-Bench ("Pro" per §1) | ✅ base | NeurIPS 2025 / 2506.11791 | 200 CVEs | deterministic (gold patch) | Docker | OSS | held; low |
| **GitGoodBench** | ✅ | JetBrains Research / May 2025 | 120/900/17k | exact-match (MCR) + LLM-judge (rebase/staging) | git repos + tools (light) | permissive OSS | **(4) git — very high** |
| Merge-Bench | ✅ | ICPR 2026 / 2605.25890 | 7,938 hunks | deterministic (committed resolution) | hunk-level (light) | OSS | (4) merge — high |
| **RefactorBench** | ✅ | MSR / ICLR 2025 | 100 multi-file | deterministic tests (behavior-preserving) | repo + tests | academic OSS | **(4) file-edit — very high** |
| SmellBench (×2) | ✅ ambiguous | 2026 / 2606.05574 & 2605.07001 | 294 | tests + localization + LLM-judge | repo | OSS | (4) refactor — high |
| **Spider 2.0** | ✅ | ICLR 2025 / 2411.07763 | 632 | execution-match rows | live DBs (medium) | Apache | **datalog family — very high oracle map** |
| BIRD | ✅ | academic | 12,751 | execution-match | DBs (medium) | OSS | datalog — high |
| MLE-bench | ✅ | OpenAI / 2410.07095 | 75 (22 lite) | metric-threshold | Docker+GPU (heavy) | MIT | (1) data — medium |
| RE-bench | ✅ | METR / 2024 | 7 envs | score vs reference | GPU (heavy) | OSS | (1) — low |

---

## Top recommendations for seon's self-authored scenarios

1. **Git family (owner's priority): clone GitGoodBench's dual oracle.** Deterministic exact-match where one resolution is correct (merge conflicts, cherry-pick); LLM-judge on a 4-dimension rubric where "good history" is subjective (rebase, partial staging). **White-space to self-author: `git bisect` + history-archaeology** — no existing bench covers it; oracle = "agent names the correct culprit commit," fully deterministic and cheap. This is a differentiated, high-value seon scenario.
2. **File-editing / refactor family: RefactorBench's behavior-preserving oracle + SmellBench's localization sub-score.** For a seon-repo refactor: oracle = `bin/test-cljs` stays green **AND** a structural assertion (fn moved / require-cycle gone) **AND** a localization score (edited-file set vs gold-file set). Add RefactorBench's **graded instruction specificity** (vague→precise) as your difficulty axis.
3. **Datalog family: Spider 2.0 / BIRD execution-match oracle, verbatim.** Seed a `seon.db`, pose an NL question, agent writes `db/query`, oracle compares returned rows to gold rows (order-insensitive). Jackal (text-to-JQL) proves the execution-match pattern transfers to non-SQL query languages — direct precedent for Datalog.
4. **DeepSWE v1.1's key lesson for family (2): grade the committed diff in a fresh checkout, not the live workspace** — aligns with `cluster fork` (grade against a clean world-at-t) and kills flaky "it worked in my dirty tree" grading.
5. **Cross-cutting: two hygiene ideas.** (a) TerminalBench 2.1 / ALE both invest in **continuous re-validation + task rotation to fight rot and leakage** — self-authored scenarios need this. (b) The Spider/BIRD annotation-error paper is a warning: **your gold answers need their own verification pass** or the oracle silently lies.

**Names to correct on the record:** "BrowseComp (Multi-Agent)" — not found as a distinct dataset; real neighbors are MM-BrowseComp / BrowseComp-V3 / BrowseComp-Plus, and the "multi-agent" label is a leaderboard config of the base set. "SEC-Bench Pro" — this lane did not surface it, but the terminal-swe + research-multiagent lanes verified it as a real successor paper (arXiv 2605.26548); base SEC-bench exists and is multi-agent-*constructed* (don't confuse with Scale's SWE-bench Pro). "GeneBench v1" has no explicit "v1" label — it's the original GeneBench, with OpenAI's GeneBench-Pro as the successor. "DeepSWE" is ambiguous between the Datacurve *benchmark* (owner's target) and the Together AI RL-trained *model* — same name, different artifacts.
