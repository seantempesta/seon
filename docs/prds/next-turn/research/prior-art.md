---
type: research
status: draft
lane: prior-art
date: 2026-09-26
---

# Prior art — giving an agent exactly what its next step needs

**What this is.** A survey (2023 – Sept 2026) of how working systems and papers
select an agent's context, retrieve tools/skills/memories, and predict the next
action cheaply, each finding mapped onto Seon (program, schemas, contracts,
call graph and every evaluation are Datahike rows; agents act through one REPL).

**How to read the evidence.** Each item is tagged:

- **[M]** measured in a paper with a public benchmark (peer-reviewed where noted);
- **[V]** vendor-internal measurement (numbers given, eval not public);
- **[C]** claim, anecdote or illustrative example, no measurement.

Many 2026 arXiv items were read at abstract/HTML level only (via fetch); the
numbers are the authors' and were not reproduced. Papers dated after mid-2026
are single, unreplicated preprints — treat them as leads, not settled results.
No models or JVMs were run for this lane.

---

## 1. Context engineering for coding agents

### 1.1 What the production agents do

| system | selection mechanism | evidence |
|---|---|---|
| **Claude Code** | Hybrid: `CLAUDE.md` loaded up front; everything else found just-in-time with glob/grep/read; sub-agents return 1–2k-token summaries; compaction + tool-result clearing. Early versions used RAG + local vector DB and were dropped: agentic search "outperformed everything. By a lot." (Boris Cherny, Latent Space, May 2025; [X post](https://x.com/bcherny/status/2017824286489383315)). | **[C]** — no numbers published. Anthropic's [Effective context engineering](https://www.anthropic.com/engineering/effective-context-engineering-for-ai-agents) (Rajasekaran, Dixon, Ryan, Hadfield, 2025-09-29) gives principles ("smallest set of high-signal tokens"), no metrics. |
| **Cursor** | Grep **plus** semantic search over a custom embedding model. The embedding model is trained on the agents' own session traces: an LLM ranks "what content would have been most helpful at each step", and the embedder is trained to match those rankings. ([blog, 2025-11-06](https://cursor.com/blog/semsearch)) | **[V]** +12.5% avg accuracy on internal Cursor Context Bench (6.5–23.5% by model); +0.3% code retention overall, +2.6% on repos ≥1,000 files. Cursor: "the combination of these two leads to the best outcomes." |
| **Aider** | Repo map: tree-sitter def/ref tags → file graph → **personalized PageRank** seeded by files in chat and mentioned identifiers → top definitions rendered as elided signatures within a token budget (default 1k tokens, ×8 when no files in chat). ([aider, 2023-10-22](https://aider.chat/2023/10/22/repomap.html)) | **[C]** for the map itself; independently, Agent Retrieval Bench (2026, below) found RepoMap the most efficient method under an 8K budget **[M]**. |
| **Sourcegraph Cody** | Recommender-system framing: a recall-oriented retrieval stage from *complementary* sources (keyword, embedding, graph) then a precision-oriented ranking stage under a latency budget. ([Hartman et al., RecSys '24, arXiv 2408.05344](https://arxiv.org/abs/2408.05344)) | **[C/V]** design lessons; numbers not extracted. |
| **OpenAI Codex** | `AGENTS.md` as a ~100-line table of contents pointing into a versioned `docs/` system of record: progressive disclosure ([Lopopolo, "Harness engineering", 2026-02](https://openai.com/index/harness-engineering/)). | **[C]** |
| **Devin** | "Knowledge" items each carry a required *trigger description*; retrieved when current work matches the trigger; DeepWiki auto-generates repo docs ([docs](https://docs.devin.ai/product-guides/knowledge)). | **[C]** |
| **SWE-agent** | Designed agent-computer interface: bounded 100-line viewer, summarizing search commands, lint-gated edit ([Yang et al., NeurIPS 2024, arXiv 2405.15793](https://arxiv.org/pdf/2405.15793)). | **[M]** +10.7 pp over shell-only on SWE-bench Lite (18.0% vs 11.0%, GPT-4 Turbo); lint-gated edit alone +3.0 pp. |
| **OpenHands** | LLM-summarizing condenser. ([blog, 2025-04](https://www.openhands.dev/blog/openhands-context-condensensation-for-more-efficient-ai-agents)) | **[V]** 54% vs 53% solved, ~2× lower spend, linear instead of quadratic cost growth. |

### 1.2 What measurements say about RAG vs agent-driven exploration

- **More retrieved text hurts.** SWE-bench (Jimenez et al., ICLR 2024, [arXiv 2310.06770](https://arxiv.org/pdf/2310.06770)): Claude 2 resolves 1.96% with BM25 retrieval vs 4.80% with oracle files; raising the BM25 budget 13K→50K raises recall but *lowers* resolution for every model **[M]**.
- **Good retrieval can nearly match gold.** CodeRAG-Bench (Z. Wang et al., NAACL 2025, [arXiv 2406.14497](https://arxiv.org/abs/2406.14497)): GPT-4o gains 27.4% on SWE-bench Lite with canonical documents; best retrieve-then-rerank gives +21 points over no retrieval, still 9 points short of gold. Top retrieval metrics did not always give the best end results **[M]**.
- **Recall is the wrong objective under a fixed budget.** "The Recall Trap" (Adkins & Trapaidze, 2026-08, [arXiv 2608.14838](https://arxiv.org/abs/2608.14838)): on SWE-bench Verified with 12 fixed context slots, turning off one-chunk-per-file dedup (less file breadth, more depth) raised resolution 39.2→46.8% (+7.6 pp, p=0.0003); +3.6 pp replicated on Qwen3.6-27B; reversed (−3.2 pp) with BM25 **[M, single preprint]**.
- **Stale context misleads.** Weng et al. (2026-05, [arXiv 2605.14478](https://arxiv.org/abs/2605.14478)): stale-only retrieval produced obsolete references in 15/17 (Qwen2.5-Coder) and 13/17 samples; no retrieval gave zero stale references but only 1/17 successes **[M, tiny n]**. This is the concrete mechanism behind Cherny's "staleness" objection to indexes.
- **Length itself degrades.** Chroma "Context Rot" (Hong, Troynikov, Huber, 2025-07, [report](https://www.trychroma.com/research/context-rot)): all 18 models tested degrade with input length even on trivial tasks; distractors hurt non-uniformly and more at length **[M]**.
- **Simple masking matches summarization.** "The Complexity Trap" (Lindenbauer et al., JetBrains, NeurIPS 2025 DL4Code, [arXiv 2508.21433](https://arxiv.org/abs/2508.21433)): masking old observations halves cost vs raw agent and matches or slightly beats LLM summarization **[M]**.
- **Fewer tokens is not lower cost.** "Token Reduction Is Not Cost Reduction" (Weinberger & Hozez, 2026-07, [arXiv 2607.12161](https://arxiv.org/abs/2607.12161)): the most aggressive tool-output compression cut tokens 38.4% but *raised* billed cost 6.8%; token savings correlated r=0.15 with cost, because cache writes/reads dominate input cost and compression changed agent behaviour (extra retrieval/test steps) **[M, single preprint]**.
- **Keyword agentic search ≈ vector RAG** on document QA (Subramanian et al., AWS, 2026-03, [arXiv 2602.23368](https://arxiv.org/pdf/2602.23368)) **[M, abstract-level]**.

**Net reading.** No study shows pre-loaded RAG dumps beating agent-driven exploration for coding agents. Cursor's measured win comes from a retriever *trained on its own agents' trajectories* and used *alongside* grep. Every controlled study that varies the amount of context finds precision beats recall once the relevant item is present.

### 1.3 Seon mapping

- Seon already has the Aider precondition, and more: the def/ref graph is not
  parsed with tree-sitter guesses but stored as rows (`:seon.fn/calls`,
  `resources/seon/schemas/seon.fn.edn:57,79,159`), with contracts, tests and
  namespaces. Personalized PageRank over the call/requires graph, seeded by the
  task's entities and the last evaluations' read evidence, is a Datalog +
  a small in-memory iteration. It replaces a fixed-radius hop walk
  (`src/seon/render/walk.clj:13`, "distance is spent on connections") with a ranked one
  that has the same budget.
- File agents fight **staleness**; Seon doesn't have to. An index keyed by
  definition digest or commit id can never point at obsolete code. That fixes the
  main measured failure of code RAG (stale references), which Cherny also named
  as a reason to drop RAG.
- The **Recall Trap** and SWE-bench results argue against rendering many
  2-hop neighbours shallowly. Fewer entities rendered in more depth is the
  measured direction.
- **Prompt-cache economics**: a per-turn re-ranked context invalidates the cached
  prefix. Put stable context (system turn, task, curated namespaces) first and
  the predicted "next-turn" block last, or the savings disappear (2607.12161).

---

## 2. Tool retrieval and skill/documentation loading

| work | finding |
|---|---|
| **ToolLLM** (Qin et al., ICLR 2024, [arXiv 2307.16789](https://arxiv.org/abs/2307.16789)) | Dense API retriever trained on instruction→API pairs over 16k RapidAPI tools: NDCG@5 avg **84.9** vs Ada embeddings 45.4 vs BM25 17.0. Top-5 retrieved APIs gave 67.3% pass rate vs 66.7% with oracle API sets **[M]**. A *trained* retriever beats generic embeddings by ~2×. |
| **Gorilla** (Patil et al., 2023, [arXiv 2305.15334](https://arxiv.org/pdf/2305.15334)) | Retriever-aware fine-tuning lowers API hallucination vs GPT-4 on APIBench; performance is bounded by retriever quality **[M]**. |
| **RAG-MCP** (Gan & Sun, 2025-05, [arXiv 2505.03275](https://arxiv.org/abs/2505.03275)) | Retrieving a tool subset instead of listing all MCP tools: selection accuracy 43.13% vs 13.62%, >50% fewer prompt tokens **[M]**. |
| **Anthropic Tool Search** ([advanced tool use, 2025-11-24](https://www.anthropic.com/engineering/advanced-tool-use)) | `defer_loading` + regex/BM25 search tool: ~85% fewer tool tokens; MCP-eval accuracy Opus 4 49→74%, Opus 4.5 79.5→88.1%. Tool-use *examples* 72→90% on complex parameters. Programmatic tool calling: 43.6k→27.3k tokens **[V]**. |
| **Code execution with MCP** ([Anthropic, 2025-11-04](https://www.anthropic.com/engineering/code-execution-with-mcp)); **Cloudflare Code Mode** ([blog](https://blog.cloudflare.com/code-mode-mcp/)) | Tools exposed as a code API; the model searches definitions and filters data *in code* before anything enters context. The widely quoted 150k→2k tokens (98.7%) is an **illustrative example**, not an eval **[C]**. |
| **Agent Skills** ([Anthropic engineering](https://www.anthropic.com/engineering/equipping-agents-for-the-real-world-with-agent-skills)) | Three-level progressive disclosure: name+description (~55–235 tokens/skill) always loaded → SKILL.md on match → bundled files on demand **[C]**. |
| **Graph-of-Skills** (2026, EMNLP '26, [arXiv 2604.05333](https://arxiv.org/abs/2604.05333)) | Semantic retrieval finds the topical skill but misses its prerequisites. Hybrid seeding + **reverse-direction personalized PageRank** over a skill dependency graph leads baselines by 4.6–7.0 reward points at ≥500 skills; reverse→forward traversal costs 9.1 points, more than dropping the graph altogether **[M, single]**. |
| **ASI — programmatic skills** (Z. Wang, Gandhi, Neubig, Fried, COLM 2025, [arXiv 2504.06821](https://arxiv.org/abs/2504.06821)) | Skills induced as *verified programs*: +23.5% success over static agent and +11.3% over text skills on WebArena; 42.5% of later tasks reused ≥1 induced skill **[M]**. |

**Seon mapping.** Seon's "tools" are program functions, so tool retrieval and code
retrieval are the same problem. Three things transfer directly:
(a) a thin always-present index (names + one-line docs + contract arity) with
typed on-demand expansion is the measured pattern (Tool Search, Skills);
(b) *examples of calls* matter more than descriptions (72→90%). Seon has recorded
successful evaluations of each function to show as examples, with no need to write them;
(c) dependency-aware retrieval should walk **reverse** edges: from the needed function
to what it needs (argument schemas, the producers of those values).
Seon's contracts make "what does this function need" a query, not a guess. ASI's
"skills = verified programs" is what Seon already is: an agent's `defn` with a
contract and a reaching test *is* an induced skill, and the merge gate is ASI's
verification step.

---

## 3. Next-action prediction, speculation, System 1/System 2, verifiers

### 3.1 How predictable is the next action?

| work | measured predictability |
|---|---|
| **Speculative Actions** (Ye, Ahuja, Liargkovas, Lu, Kaffes, Peng; 2025-10, rev. 2026-04, [arXiv 2510.04371](https://arxiv.org/abs/2510.04371)) | Fast model guesses the next action and runs it in parallel; commit only on match (lossless). Up to **55%** next-action accuracy, up to **20%** latency reduction (chess, e-commerce, web search, OS) **[M]**. |
| **PASTE** (Sui et al., 2026-03, [arXiv 2603.18897](https://arxiv.org/html/2603.18897v1)) | No LLM speculator: sequential **pattern mining** over tool-event signatures (tool, status; payloads stripped) plus **symbolic parameter derivation** ("URL = first search result"). Top-1 27.8%, top-3 43.9%; task time −48.5%, tool latency −55.2%. Measured regularities: coding, 55% of edits are immediately followed by a test run; 38% of file searches followed by an editor open; 95% of fetched URLs come directly from the preceding search output **[M, single]**. |
| **Self-speculating agents** (Ji et al., 2026-07, [arXiv 2607.25816](https://arxiv.org/html/2607.25816v1)) | The agent model itself, RL-trained jointly as speculator, predicts its next call: Hit@1 44.1→61.2 (Qwen3-4B), 48.9→66.3 (Qwen3.5-4B) with task success unchanged or slightly up **[M, single]**. |
| **Speculative Macro Commit** (Liu, Kundu, Beerel, 2026-09, [arXiv 2609.03236](https://arxiv.org/abs/2609.03236)) | Drafter predicts *chains* on an isolated environment snapshot; −44.9% wall time vs sequential on AppWorld (slight completion loss) **[M, single]**. |
| **Cursor Tab** ([online RL](https://cursor.com/blog/tab-rl)); **Zed Zeta** ([blog](https://zed.dev/blog/edit-prediction)) | Next-edit prediction from recent edit history. Cursor: 21% fewer suggestions at 28% higher accept rate after on-policy RL with 1.5–2 h checkpoint loops **[V]**. |
| **Sleep-time compute** (Lin et al., Letta/Berkeley, 2025-04, [arXiv 2504.13171](https://arxiv.org/abs/2504.13171)) | Precompute over a context before the query arrives: ~5× less test-time compute for equal accuracy on stateful GSM/AIME; 2.5× lower cost per query when amortized over related queries; **benefit correlates with how predictable the query is** **[M]**. |

**Reading.** Tool *type* is quite predictable (top-3 ≈ 45–65%) and many arguments
are *derivable* from the previous result rather than generated. Exact full-call
prediction is harder. Every reported win is **latency** from overlapping work,
plus cost where the precompute is cheap. None of these papers uses the prediction to
*select context*. That use is untested in the literature.

### 3.2 World models

- **WebDreamer** (Gu et al., TMLR 2025, [arXiv 2411.06559](https://arxiv.org/abs/2411.06559)) — an LLM simulates each candidate action's outcome, then scores it; beats reactive baselines on VisualWebArena / Mind2Web-live **[M]**.
- **WMA** (Chae et al., ICLR 2025, [arXiv 2410.13232](https://arxiv.org/abs/2410.13232)) — trains a world model to predict only *state differences* ("transition-focused abstraction"); competitive with tree search at lower cost **[M]**.
- **Discriminative world models** (Li … Darrell, Herzig, 2026-09, [arXiv 2609.02885](https://arxiv.org/abs/2609.02885)) — train predicted states to be *discriminable* among alternatives rather than reconstructive; better PRM and WebArena-Lite success **[M, single]**.

For Seon the world model does not need to be learned. For read-only forms it is
the system itself: `d/with` or a branch evaluates a form's effects exactly and cheaply.
A "predicted state" is a real database value.

### 3.3 System 1 / System 2 and verifiers

- **SwiftSage** (Lin et al., NeurIPS 2023, [arXiv 2305.17390](https://arxiv.org/abs/2305.17390)) — small fine-tuned T5 emits actions; LLM planner invoked only when the fast module stalls; ScienceWorld 84.7 vs ReAct 36.4 / Reflexion 45.3 **[M]**.
- **RouteLLM** (Ong et al., LMSYS, 2024) — learned router sends easy queries to a cheap model: >85% cost reduction on MT-Bench at 95% GPT-4 quality **[M]**.
- **SWE-Gym verifier** (Pan et al., ICML 2025, [arXiv 2412.21139](https://arxiv.org/abs/2412.21139)) — learned trajectory verifier: 20.6% @1 → 29.8% @8 → 32.0% @16 on SWE-bench Verified **[M]**.
- **SWE-PRM** (Gandhi et al., IBM, 2025-09, [arXiv 2509.02360](https://arxiv.org/abs/2509.02360)) — process reward model course-corrects mid-trajectory with taxonomy-guided feedback: 40.0→50.6% on SWE-bench Verified, ≈$0.2 added per task **[M]**.
- **The Verification Horizon** (2026-06, [arXiv 2606.26300](https://arxiv.org/abs/2606.26300)) — argues verification is now harder than generation; fixed reward functions degrade as policies improve **[M/position]**.
- **CLM** (`reference-code/CLM/README.md`) — contrastive state/action embedding model ("System One"), states and actions embedded separately, so candidate embeddings cache. README claims on-par with a larger model at up to 9× lower latency and 87.6% / 81.6% as a verifier on Terminal-Bench 2.1 / DeepSWE **[C — vendor README, no independent replication found]**.

**Seon mapping.** Seon has **deterministic** System-1 checks that file agents
approximate with learned verifiers. Armed Malli contracts, the gate, reaching
tests, and read evidence can reject a predicted or drafted form before any model
judges it. The measured value of verifiers (SWE-Gym, SWE-PRM) says *cheap
rejection of bad steps is worth ~10 pp*. Seon gets part of that for free and
should spend learned models only where contracts are silent (intent, choice among
valid forms).

---

## 4. Memory retrieved by situation, not text

| work | key point / number |
|---|---|
| **Synapse** (Zheng et al., ICLR 2024, [arXiv 2306.07863](https://arxiv.org/abs/2306.07863)) | *State abstraction* + whole trajectories as exemplars, retrieved by task similarity: 99.2% on MiniWoB++ (64 tasks from demos of 48) **[M]**. |
| **Agent Workflow Memory** (Z. Wang, Mao, Fried, Neubig, ICML 2025, [arXiv 2409.07429](https://arxiv.org/abs/2409.07429)) | Induced reusable workflows: +24.6% / +51.1% relative success on Mind2Web / WebArena **[M]**. |
| **SWE-Exp** (Chen et al., 2025-07, [arXiv 2507.23361](https://arxiv.org/abs/2507.23361)) | Experience bank from success *and* failure trajectories; retrieve top-10 by error-type similarity, rerank to 1; 41.6% Pass@1 SWE-bench Verified (open-source SOTA at the time) **[M]**. |
| **Memento** (2025-08, [arXiv 2508.16153](https://arxiv.org/abs/2508.16153)) | Case-based reasoning with a *learned* case-selection policy (memory-based RL, no LLM fine-tuning); GAIA val 87.88% Pass@3 **[M]**. |
| **Cue-anchored working memory** (Saha, 2026-07, [arXiv 2607.20972](https://arxiv.org/abs/2607.20972)) | Memories carry first-class **triggers** (path, symbol, semantic, event, temporal) evaluated deterministically by the harness. Agents made **0 voluntary memory operations in 114 turns**; deterministic injection 100%, zero false alarms; all 10 facts were lost at first compaction with conversation-only storage **[M, single, small]**. |
| **Skeptic: "Are skill/memory modules worth their tokens?"** (Hajimiri et al., EMNLP 2026, [arXiv 2606.15017](https://arxiv.org/abs/2606.15017)) | Under **equal token budgets**, a vanilla agent matches or beats AWM, ASI and ReasoningBank on WebArena/WorkArena across three models; run-to-run variance hides claimed gains **[M]**. |

**Reading.** Memory helps when it is (a) retrieved by a *structured situation key*
(error type, state abstraction, trigger condition) rather than raw text similarity,
(b) *delivered by the harness*, because agents don't call memory tools on their own,
and (c) measured at equal budget. Most published memory gains shrink or vanish at
equal budget.

**Seon mapping.** Seon's situation keys are exact, not embedded: the error
schema (`:seon.error/*` named schema), the function/contract being edited, the
namespace, the failing test id. A lesson (issue, prior evaluation that fixed the
same error schema on the same function) can be delivered by a Datalog trigger
through `d/listen`. Nothing depends on the agent choosing to search, and nothing
depends on cosine similarity. The cue-anchored paper's vocabulary (symbol, event)
maps onto Seon identities one-to-one.

---

## 5. Structured / typed context for code models

| work | finding |
|---|---|
| **Typed holes / ChatLSP** (Blinn, Li, Kim, Omar, OOPSLA 2024, [arXiv 2409.00921](https://arxiv.org/abs/2409.00921)) | Context = the hole's **expected type**, relevant type definitions and headers, from the language server. It beats vector retrieval on MVUBench (Hazel, replicated in TypeScript). "Contextualization with type definitions is particularly impactful" **[M]**. Exact percentages not extracted (PDF too large to fetch). |
| **Monitor-guided decoding** (Agrawal et al., NeurIPS 2023, [arXiv 2306.10763](https://arxiv.org/abs/2306.10763)) | Static analysis constrains identifiers during decoding. SantaCoder-1.1B + MGD beats text-davinci-003 on compilation rate and next-identifier match **[M]**. |
| **Type-constrained decoding** (Mündler, He, Wang, Sen, Song, Vechev, PLDI 2025, [arXiv 2504.09246](https://arxiv.org/abs/2504.09246)) | Prefix automata + inhabitable-type search: compilation errors cut by more than half; functional correctness up on synthesis/translation/repair (TypeScript, models up to 30B+) **[M]**. |
| **RepoCoder** (Zhang et al., EMNLP 2023, [arXiv 2303.12570](https://arxiv.org/abs/2303.12570)) | Iterate: generate a draft, retrieve with the draft as the query. >10% over in-file baseline **[M]**. The draft *is* a next-step prediction used as the retrieval key. |
| **CodePlan** (Bairi et al., FSE 2024, [arXiv 2309.12499](https://arxiv.org/abs/2309.12499)) | Incremental dependency analysis + change may-impact analysis drive the plan graph; 5/6 repos pass validity vs 0/6 for baselines **[M]**. |
| **RepoGraph** (Ouyang et al., ICLR 2025, [arXiv 2410.14684](https://arxiv.org/abs/2410.14684)) | Line-level code graph plugged into agents/procedures: +32.8% average relative on SWE-bench **[M]**. |
| **LocAgent** (Chen et al., ACL 2025, [arXiv 2503.09089](https://arxiv.org/abs/2503.09089)) | Heterogeneous graph (files/classes/functions; import/invoke/inherit) + graph tools. A fine-tuned Qwen2.5-32B reaches 92.7% file-level localization, about Claude-3.5's quality at −86% cost ($0.66→$0.09/example). Trained on only ~770 trajectories **[M]**. |

**Reading.** When real structure (types, bindings, dependency/impact edges) is
available, it beats similarity retrieval. It also lets small models match large
ones (MGD, LocAgent). This is the strongest and most consistent body of evidence
in the survey.

**Seon mapping.** This is where Seon has the largest structural advantage over file agents:

- The **expected type of the hole** is a Malli contract. When an agent is about
  to call or define `f`, the exact context is: `f`'s contract, the schemas of its
  arguments (resolved through the registry), the functions whose output schemas
  produce those arguments (reverse edge, as in Graph-of-Skills), and one recorded
  successful call. All of it comes from Datalog over definition digests, never stale.
- **Change impact** (CodePlan) is `:seon.fn/calls` reversed plus tests reaching
  the changed digest: already the merge gate's selection set.
- **Constrained decoding** against known Vars/contracts is possible locally
  (token-level lane owns this). Provider models accept no grammar, so the transferable
  form is *post-hoc* validation (read → contract check) before eval.

---

## 6. Evaluation: measuring context precision/recall and its effect

- **ContextBench** (Li … Barr, Sarro, He Ye; 2026-02, [arXiv 2602.05892](https://arxiv.org/abs/2602.05892)): 1,136 issues, 66 repos, 8 languages, human-annotated gold context at file/block/line. Instruments every region an agent *inspects*. GPT-5 backbone file recall/precision: mini-SWE-agent 0.682/0.709 (Pass@1 0.645), OpenHands 0.733/0.400 (0.463), Agentless 0.609/0.352 (0.390). Findings: elaborate scaffolds barely improve retrieval; LLMs favour recall over precision; a large **"usage drop"** between context explored and context used (≈0.43 for Gemini 2.5 Pro and Devstral 2); recall correlates with success **[M]**. The simplest scaffold had the best precision *and* the best Pass@1.
- **Agent Retrieval Bench** (Qin & Xie, 2026-07, [arXiv 2607.24882](https://arxiv.org/abs/2607.24882)): labels "what the agent needs next" rather than query similarity; 427 samples, 25 repos. No method wins everywhere; RepoMap most efficient at 8K; agent trajectories missed gold files in 27–35% of samples; abstention thresholds did not transfer **[M]**.
- **Metrics in use**: NDCG@k for tool retrieval (ToolLLM); file/block/line P/R/F1 over inspected intervals (ContextBench); Hit@k for next-call prediction (Speculative Actions, Ji et al.); downstream resolve rate at *fixed budget* (Recall Trap); cost per *successful* task including cache behaviour (2607.12161); equal-budget comparison with run variance (2606.15017).

**Seon mapping.** Seon can compute ContextBench-style metrics **online, for every
turn, at no labelling cost**. The rendered prompt is persisted exact text
(`:seon.eval/shown`), and the agent's next evaluations record source and read
evidence (`:seon.cluster.eval/read-evidence`,
`resources/seon/schemas/seon.eval.edn:20`). "Gold context for turn *t*" is then
approximated by the entities turn *t*'s forms called, read or defined. Precision =
shown ∩ used / shown; recall = shown ∩ used / used; the "usage drop" is directly
visible. File agents must reconstruct this from logs by heuristic interval mapping.
Caveat: "used" is a lower bound on "needed" (an agent can succeed by guessing, or
fail for lack of something it never named), so pair it with outcome (did the turn's
eval error, did the task pass).

---

## 7. What Seon can do that file-based agents cannot

1. **Exact, non-stale structure.** Call graph, contracts, schemas and test reach are
   rows keyed by definition digests. File agents parse or embed text and fight
   index staleness (the named reason Claude Code dropped RAG; measured harm in 2605.14478).
2. **Free labels.** Every evaluation records what it read and called, so each turn
   produces a (state → needed entities) training/eval pair. Cursor needed an LLM to
   label "what would have helped" after the fact. Seon observes it directly.
3. **Lossless speculation for reads.** Speculative Actions must sandbox side
   effects. In Seon a predicted *read* or *render* is a pure function of an
   immutable db value, so it can be precomputed during the provider call and
   reused if the commit id (or read basis) is unchanged. Predicted *writes* can run
   under `d/with` or on a throwaway branch.
4. **Deterministic System 1.** Contracts, the gate and reaching tests reject bad
   forms without a model, which is the job learned verifiers do elsewhere.
5. **Situation keys are identities.** Error schema, function digest, namespace, task
   id: memories and lessons can be keyed and triggered exactly, not by cosine
   similarity.

Cautions from the literature that apply equally to Seon: per-turn context
churn fights prompt caching; equal-budget comparisons erase many claimed gains;
memory tools the agent must remember to call go unused (0/114).

---

## 8. The five most promising transferable ideas

| # | idea | Seon form | evidence strength |
|---|---|---|---|
| 1 | **Structure-derived context for the "hole"** (typed holes, MGD, LocAgent, RepoGraph, CodePlan, GoS reverse PPR) | For the function the agent is editing/calling: contract, argument schemas, reverse-edge producers, reaching tests, one recorded successful call. Rank the neighbourhood with **personalized PageRank over `:seon.fn/calls`/requires** seeded by task entities + last read evidence, instead of fixed hops. Datalog + an in-memory iteration; no model. | **Strong.** Multiple peer-reviewed measured results (OOPSLA, NeurIPS, PLDI, ICLR, ACL, FSE); consistent direction; small models match large ones. |
| 2 | **Pointers up front, typed just-in-time expansion; precision over recall** (Claude Code, Tool Search, Skills, SWE-bench budget result, Recall Trap, Context Rot, observation masking) | Keep the opening a thin index (names + one-line doc + arity); render few entities deeply instead of many shallowly; mask old evaluation output rather than summarizing; keep stable blocks as the cached prefix, volatile predicted block last. | **Strong** for "more context hurts / JIT works" (SWE-bench, Chroma, JetBrains: measured). **Moderate** for the specific tool-search numbers (vendor internal). |
| 3 | **Learn the ranker from our own trajectories, and measure context P/R online** (Cursor semsearch, ToolLLM retriever, ContextBench, Agent Retrieval Bench) | Label = entities later called/read/defined (read evidence) vs entities shown (`:seon.eval/shown`). First as a metric on every turn (usage drop, precision, recall); then as training data for any ranker (CLM, a small embedder, or plain counts). | **Moderate–strong.** Trained retrievers ≈2× generic embeddings (ToolLLM NDCG 84.9 vs 45.4, [M]); Cursor +12.5% [V]; the evaluation method is published and measured [M]. |
| 4 | **Mined next-action patterns + speculative precompute of reads/renders** (PASTE, Speculative Actions, sleep-time compute, RepoCoder's draft-as-query) | Mine (event-signature → next event) and argument-derivation rules ("next call's arg = previous result's `:x`") from recorded evaluations; during the provider call, precompute the top-k predicted renders/queries against the current commit; commit-id-checked reuse. Also use a cheap draft of the next form as the retrieval key. | **Moderate.** Measured latency wins (−20% to −48%) and predictability (top-3 ≈ 44–66%), but all single preprints, and **no study uses prediction to choose context**; the gain for Seon is mostly latency, not answer quality. |
| 5 | **Harness-delivered, situation-keyed memory** (cue-anchored memory, SWE-Exp error-type keys, Devin triggers, Synapse state abstraction) | Lessons/issues/prior fixing evaluations keyed by error schema + function digest + namespace; delivered by a Datalog trigger (`d/listen`) when the situation recurs, never waiting on the agent to search. | **Moderate–weak.** Consistent qualitative evidence and SWE-Exp's measured SOTA, but the strongest trigger result is one small preprint, and the equal-budget study (EMNLP 2026) shows many memory gains vanish. Budget-match any evaluation. |

**Not recommended on current evidence:** a generic embedding RAG dump of "maybe
related" rows (measured to hurt at length and when stale); LLM summarization
as the primary context compressor (masking matches it at lower cost); adopting
CLM as the core selector on the strength of its README alone (no independent
measurement found). Test it as one candidate ranker inside idea 3's online metric.
