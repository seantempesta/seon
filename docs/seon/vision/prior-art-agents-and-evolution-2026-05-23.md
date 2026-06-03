---
type: research
status: active
tags: [research, vision]
---

# Prior Art: Agents, Evolution, and Self-Improving AI

Companion to the classical-CS prior-art document. This file covers the modern AI lineage only: self-modifying programs, evolutionary code generation, LLM agents, tool use, sandboxing, multi-agent foundations, continual learning, and contract-driven AI.

## Statement of purpose

Seon is a substrate for AI agents to write and evolve software they themselves run inside. That puts it downstream of a long lineage of ideas: self-modifying programs, evolutionary code synthesis, LLM agents, tool-using language models, sandboxed execution of agent code, and continual learning. This document credits those predecessors so the project can speak honestly about which ideas are its own and which are borrowed.

The format throughout: a single entry per work, with year, citation, optional one-line quote, and a one-line "how Seon relates." We err on the side of acknowledging too many predecessors rather than too few — the goal is humble citation, not novelty claims.

---

## Section A — Self-improving programs (foundational)

### Douglas Lenat — AM (1976)

> Lenat, D. B. (1976). *AM: An Artificial Intelligence Approach to Discovery in Mathematics as Heuristic Search.* PhD thesis, Stanford University, STAN-CS-76-570.

AM was a Lisp program that started with a few hundred concepts of elementary set theory and a body of ~250 heuristics, then rediscovered the integers, prime numbers, Goldbach's conjecture, and Ramanujan-style results by searching for "interesting" new concepts to define. It is one of the earliest and most-cited examples of a program that modifies its own knowledge to grow its understanding.

How Seon relates: Seon's pitch that an agent edits the code it runs inside, and that the system gets more capable as that code grows, is a direct descendant of AM's "interestingness-driven self-extension." The lineage is not unbroken — AM's heuristics were hand-written Lisp — but the *form* of the loop is the same.

### Douglas Lenat — Eurisko (1983)

> Lenat, D. B. (1983). "Eurisko: A program that learns new heuristics and domain concepts." *Artificial Intelligence* 21(1–2): 61–98.

Eurisko was AM's successor and the first widely-cited *recursively self-improving* program: heuristics that modified other heuristics, including themselves. It famously won the U.S. Traveller Trillion Credit Squadron championship in 1981 and 1982 by inventing fleet designs its human author did not understand. The system reported that "the discovery of heuristics which help the system discover heuristics" was one of its eight task domains.

How Seon relates: Eurisko is the canonical prior art for the "Seon agent edits Seon's own substrate" claim. Where modern LLM-driven systems (FunSearch, AlphaEvolve, Promptbreeder) recreate Eurisko's loop using a language model as the mutation operator, Seon imagines the same loop with an agent that also owns the surrounding scaffolding (DB, REPL, web UI, sandbox).

### Jürgen Schmidhuber — Gödel Machine (2003)

> Schmidhuber, J. (2003). "Gödel Machines: Self-Referential Universal Problem Solvers Making Provably Optimal Self-Improvements." arXiv:cs/0309048. Later expanded in Goertzel & Pennachin (eds.), *Artificial General Intelligence*, Springer, 2007.

The Gödel Machine rewrites any part of its own code as soon as it has found a proof that the rewrite is useful, where "useful" is defined by a problem-dependent utility function. The contribution is theoretical: a mathematically rigorous framework for provably optimal self-improvement.

How Seon relates: Seon does not aspire to formal proofs of improvement. But Schmidhuber's framing — *the program contains its own utility function and can reason about modifications to itself* — is the philosophical ceiling that ad-hoc self-modifying agent loops are reaching toward.

### Karl Sims — Evolving Virtual Creatures (1994)

> Sims, K. (1994). "Evolving Virtual Creatures." *Computer Graphics (SIGGRAPH '94 Proceedings)*, pp. 15–22. Thinking Machines Corporation, on the Connection Machine CM-5.

Sims evolved both the body morphology and the neural-network controller of simulated creatures, jointly. The result was creatures that swam, jumped, and followed light, with body plans that emerged from the same evolutionary pressure that shaped their brains. The work is the gold-standard reference for *co-evolution of structure and behavior.*

How Seon relates: Seon-the-substrate evolves alongside the agents that use it — the agent's code and the surrounding system are co-designed. Sims showed how dramatic that joint search can be even with primitive primitives.

### Allen Newell, John Laird, Paul Rosenbloom — SOAR (1987)

> Laird, J. E., Newell, A., & Rosenbloom, P. S. (1987). "SOAR: An Architecture for General Intelligence." *Artificial Intelligence* 33(3): 1–64.

SOAR organized cognition around a single weak-method problem solver, universal subgoaling, and learning by *chunking* — converting the resolution of an impasse into a new production rule. It is one of the earliest worked-out unified cognitive architectures.

How Seon relates: The pattern "system encounters something it can't do, decomposes into subgoals, and *retains the solution as a new capability*" is exactly the loop Seon wants its agents to run. Chunking-by-namespace is roughly the Seon analog: the agent solves a problem, distills the solution into a `seon.*` namespace, and the next agent can call it.

### John Koza — Genetic Programming (1992)

> Koza, J. R. (1992). *Genetic Programming: On the Programming of Computers by Means of Natural Selection.* MIT Press, 836 pp.

Koza's book established genetic programming as a discipline distinct from genetic algorithms: instead of evolving fixed-length bitstrings, evolve syntax trees that are themselves executable Lisp programs. The contribution is that the *individual* in the population is a program.

How Seon relates: All modern LLM-evolutionary systems (FunSearch, AlphaEvolve, Eureka, OpenAI Evolve) are direct descendants of Koza's "evolve programs" framing, with the LLM replacing crossover-plus-mutation as the variation operator. Seon's "agents modify Seon code, the better versions persist" is the same selection pressure with humans (and tests) as fitness function.

### John Holland — Genetic Algorithms (1975)

> Holland, J. H. (1975). *Adaptation in Natural and Artificial Systems.* University of Michigan Press; reprinted MIT Press, 1992.

The foundational text. Holland's schema theorem and the framing of evolution as adaptive search underpins every later evolutionary computation result, including Koza's GP, NEAT, FunSearch, and AlphaEvolve.

---

## Section B — Evolutionary code generation

### Kenneth Stanley & Risto Miikkulainen — NEAT (2002)

> Stanley, K. O., & Miikkulainen, R. (2002). "Evolving Neural Networks through Augmenting Topologies." *Evolutionary Computation* 10(2): 99–127.

NEAT evolves the structure of neural networks (not just weights) using historical markers and speciation to protect topological innovations. It is the canonical reference for "evolve the architecture, not just the parameters" and predates the recent LLM-as-evolutionary-operator wave by two decades.

### DeepMind — AlphaCode (2022)

> Li, Y., Choi, D., Chung, J., et al. (2022). "Competition-level code generation with AlphaCode." *Science* 378(6624): 1092–1097.

AlphaCode reached the top 54% of human competitive programmers on Codeforces by massively sampling, executing, and filtering programs. It is the first credible demonstration that *generate–filter at scale* is a viable program-synthesis strategy.

### DeepMind — FunSearch (2023)

> Romera-Paredes, B., Barekatain, M., Novikov, A., et al. (2023). "Mathematical discoveries from program search with large language models." *Nature* 625: 468–475.

FunSearch evolves Python functions using an LLM as the variation operator and an external evaluator as fitness. It found a new construction for the cap-set problem and improved heuristics for online bin packing — billed by the authors as "the first scientific discovery — a new piece of verifiable knowledge about a notorious scientific problem — using an LLM."

How Seon relates: This is the closest published predecessor for "LLM proposes a code change, the system runs it, the better one survives." Seon proposes to run this loop on the agent's own working codebase rather than on isolated short Python functions.

### DeepMind — AlphaEvolve (2025)

> Novikov, A., Vũ, N., Eisenberger, M., et al. (2025). "AlphaEvolve: A coding agent for scientific and algorithmic discovery." DeepMind technical report; arXiv:2506.13131.

AlphaEvolve generalizes FunSearch from short snippets to entire codebases, using Gemini 2.0 Flash and Pro in a population-based evolutionary loop with continuous evaluator feedback. DeepMind reports practical wins on matrix multiplication kernels and Google data-center scheduling code.

How Seon relates: Of all listed predecessors, AlphaEvolve is the one whose loop *most closely resembles what Seon wants to do.* The two material differences: AlphaEvolve runs in a black-box DeepMind harness with internal-only tooling; Seon aims to make the same loop reproducible on a single developer's laptop, with the substrate, DB, and sandbox all open.

### OpenAI — Eureka (2023)

> Ma, Y. J., Liang, W., Wang, G., et al. (2023). "Eureka: Human-Level Reward Design via Coding Large Language Models." arXiv:2310.12931; ICLR 2024.

Eureka uses GPT-4 to generate reward functions as code for reinforcement-learning agents, then evolves those reward functions across an outer loop. It demonstrated a simulated five-finger hand learning to spin a pen — a task that had resisted hand-engineered rewards for years.

### Wang et al. — Voyager (2023)

> Wang, G., Xie, Y., Jiang, Y., et al. (2023). "Voyager: An Open-Ended Embodied Agent with Large Language Models." arXiv:2305.16291.

Voyager is a Minecraft agent whose primary deliverable is an *ever-growing skill library of executable JavaScript*. Each successfully-executed program is added to the library and indexed for retrieval, producing a compounding capability curve.

How Seon relates: Voyager's "skill library that grows by accretion" is the closest match to Seon's vision of agents accumulating namespaces. The Seon variant adds: the library is the *system itself*, the skills are first-class fns with `:malli/schema`, and retrieval is via Datalog rather than embedding similarity.

### DeepMind — Promptbreeder (2023)

> Fernando, C., Banarse, D., Michalewski, H., Osindero, S., & Rocktäschel, T. (2023). "Promptbreeder: Self-Referential Self-Improvement Via Prompt Evolution." arXiv:2309.16797.

Promptbreeder evolves *both* task-prompts and the mutation-prompts that mutate them — explicitly self-referential. It outperforms Chain-of-Thought and Plan-and-Solve prompting on standard reasoning benchmarks.

How Seon relates: Promptbreeder is the prior art for "the system's own mechanism of improvement is itself a target of improvement." A Seon agent that edits the harness that runs Seon agents inherits Fernando et al.'s loop wholesale.

---

## Section C — LLM agents (planning and tool use)

### Yao et al. — ReAct (2022)

> Yao, S., Zhao, J., Yu, D., Du, N., Shafran, I., Narasimhan, K., & Cao, Y. (2022). "ReAct: Synergizing Reasoning and Acting in Language Models." arXiv:2210.03629; ICLR 2023.

ReAct interleaves chain-of-thought reasoning with tool-use actions in a single trace. It is the foundational pattern for the modern LLM-as-agent loop and the direct ancestor of AutoGPT, LangChain agents, and (via OpenAI function-calling) most production agent systems today.

How Seon relates: Seon's REPL-driven agent loop — think, evaluate, observe, repeat — is a ReAct loop where the action surface is a Clojure namespace.

### Shinn et al. — Reflexion (2023)

> Shinn, N., Cassano, F., Berman, E., Gopinath, A., Narasimhan, K., & Yao, S. (2023). "Reflexion: Language Agents with Verbal Reinforcement Learning." NeurIPS 2023; arXiv:2303.11366.

Reflexion has agents verbally reflect on task failures and store those reflections in an episodic buffer, producing iterative improvement without weight updates. Reported gains: +22% on AlfWorld, +20% on HotpotQA, +11% on HumanEval.

### Madaan et al. — Self-Refine (2023)

> Madaan, A., Tandon, N., Gupta, P., Hallinan, S., Gao, L., Wiegreffe, S., et al. (2023). "Self-Refine: Iterative Refinement with Self-Feedback." NeurIPS 2023; arXiv:2303.17651.

Same LLM produces an output, critiques it, and refines it. Roughly +20% across seven tasks vs single-shot GPT-3.5/GPT-4. Reflexion / Self-Refine / Self-Debug form a tight lineage: a single LLM playing multiple roles in a critic-actor loop. Credit is shared.

### Significant Gravitas (Toran Bruce Richards) — AutoGPT (March 2023)

> AutoGPT public release, 30 March 2023. <https://github.com/Significant-Gravitas/AutoGPT>

The first widely-used autonomous-agent product. Demonstrated the basic loop — goal → subgoals → tool use → reflection → repeat — at a level the broader developer community could run. Fastest-growing project in GitHub history at the time.

### Yohei Nakajima — BabyAGI (April 2023)

> Nakajima, Y. (2023). BabyAGI. <https://github.com/yoheinakajima/babyagi>

105 lines of Python that orchestrate a task-creation/execution/reprioritization loop backed by a vector store. Stripped-down enough to read in one sitting and clarified the *shape* of an autonomous-agent loop for thousands of subsequent developers.

### Microsoft — AutoGen (2023)

> Wu, Q., Bansal, G., Zhang, J., Wu, Y., Zhang, S., Zhu, E., et al. (2023). "AutoGen: Enabling Next-Gen LLM Applications via Multi-Agent Conversation Framework." arXiv:2308.08155.

Multi-agent conversation as the primary abstraction: agents talk to each other, optionally with human-in-the-loop, to accomplish complex tasks.

### Li et al. — CAMEL (2023)

> Li, G., Hammoud, H. A. A. K., Itani, H., Khizbullin, D., & Ghanem, B. (2023). "CAMEL: Communicative Agents for 'Mind' Exploration of Large Language Model Society." NeurIPS 2023; arXiv:2303.17760.

Role-playing as a coordination mechanism: a "user" agent and an "assistant" agent are seeded with complementary personas and instructed to cooperate. Predated and influenced AutoGen.

### Hong et al. — MetaGPT (2023)

> Hong, S., Zhuge, M., Chen, J., et al. (2023). "MetaGPT: Meta Programming for Multi-Agent Collaborative Framework." arXiv:2308.00352; ICLR 2024.

Encodes software-engineering SOPs (PRD → architecture → API spec → code → tests) into agent roles. Important as the first widely-cited demonstration that *structured workflow*, not raw chat, is the right shape for multi-agent code generation.

### Park et al. — Generative Agents (2023)

> Park, J. S., O'Brien, J. C., Cai, C. J., Morris, M. R., Liang, P., & Bernstein, M. S. (2023). "Generative Agents: Interactive Simulacra of Human Behavior." UIST '23.

The "Smallville" paper. Twenty-five agents with memory, reflection, and planning behaved coherently for days in a sandbox. The key architectural contribution: a memory stream with a relevance/recency/importance scoring function, and a *reflection* layer that periodically synthesizes higher-level observations from raw events.

How Seon relates: Seon's reliance on Datahike as a queryable agent memory plus periodic summarization echoes Park et al.'s memory-stream + reflection architecture, scaled to a single agent operating on a real codebase rather than 25 simulated humans gossiping.

### Yang et al. — SWE-agent (2024)

> Yang, J., Jimenez, C. E., Wettig, A., Lieret, K., Yao, S., Narasimhan, K., & Press, O. (2024). "SWE-agent: Agent-Computer Interfaces Enable Automated Software Engineering." NeurIPS 2024.

Coined the term *Agent-Computer Interface (ACI)*. The thesis: agents need a purpose-built interface to a codebase, not a generic shell, and the interface shape dominates the choice of model.

How Seon relates: This is exactly Seon's bet. Seon's `seon.*` API surface, with map-in/map-out fns and `:malli/schema` metadata, is a deliberately-designed ACI. SWE-agent is the academic statement of that thesis.

### Cognition Labs — Devin (March 2024)

> Cognition Labs (2024). "Introducing Devin, the first AI software engineer." Product announcement, 12 March 2024.

The product that made autonomous-software-engineer agents a category. Equipped with a shell, editor, browser, and long-horizon planning; achieved 13.86% on SWE-bench (vs prior SOTA 1.96%) on first announcement.

### OpenHands / OpenDevin (2024)

> Wang, X., Li, B., Song, Y., et al. (2024). "OpenHands: An Open Platform for AI Software Developers as Generalist Agents." arXiv:2407.16741.

Open-source replication of the Devin-style agent. Started as OpenDevin in March 2024 as an explicit homage, renamed OpenHands. 188+ contributors, MIT-licensed.

### Anthropic — Computer Use (October 2024)

> Anthropic (2024). "Introducing computer use, a new Claude 3.5 Sonnet, and Claude 3.5 Haiku." Product announcement, 22 October 2024.

Claude controls a computer via screenshots and synthetic mouse/keyboard events. The first major lab product to make GUI-grounded agent action a first-class capability rather than a research demo.

---

## Section D — Code-writing and self-correcting LLM techniques

### Chen et al. — OpenAI Codex (2021)

> Chen, M., Tworek, J., Jun, H., et al. (2021). "Evaluating Large Language Models Trained on Code." arXiv:2107.03374.

The paper that introduced HumanEval and the Codex model — the engine behind the first commercial coding assistant (GitHub Copilot, 2021). Codex solved 28.8% of HumanEval pass@1, 70.2% pass@100. The "repeated sampling is surprisingly effective" observation seeded the entire generate-and-filter line.

### Chen et al. — Self-Debug (2023)

> Chen, X., Lin, M., Schärli, N., & Zhou, D. (2023). "Teaching Large Language Models to Self-Debug." ICLR 2024; arXiv:2304.05128.

Frames LLM debugging as "rubber-duck debugging": the model executes its own code, narrates the result in natural language, and proposes a fix. State-of-the-art on Spider, TransCoder, and MBPP at publication.

How Seon relates: Seon's dev hook — auto-reload, auto-run-affected-tests, surface failure to the agent — is the Self-Debug loop running as infrastructure rather than as a prompt template.

### GitHub Copilot (2021) and Cursor's Composer (~2024)

Commercial product line. Important because they established what tens of millions of developers expect from "code that the LLM writes for them": inline completion, conversational editing, project-scoped retrieval. Seon's relationship to this line is asymmetric — Seon assumes the agent *is* the developer, not the developer's assistant.

---

## Section E — LLM tool use and environment interaction

### Schick et al. — Toolformer (2023)

> Schick, T., Dwivedi-Yu, J., Dessì, R., Raileanu, R., Lomeli, M., Zettlemoyer, L., et al. (2023). "Toolformer: Language Models Can Teach Themselves to Use Tools." NeurIPS 2023; arXiv:2302.04761.

LLMs self-supervisedly learn *when* and *how* to call external APIs (calculator, search, translation, calendar) by inserting tool-call markers into training data. The paper that established tool use as a teachable model behavior rather than a prompt convention.

### Patil et al. — Gorilla (2023)

> Patil, S. G., Zhang, T., Wang, X., & Gonzalez, J. E. (2023). "Gorilla: Large Language Model Connected with Massive APIs." NeurIPS 2024; arXiv:2305.15334.

Finetunes LLaMA to call thousands of APIs correctly, with a retriever for documentation. Demonstrates that scale of tool-use is achievable when you grind on the data.

### Qin et al. — ToolLLM (2023)

> Qin, Y., Liang, S., Ye, Y., et al. (2023). "ToolLLM: Facilitating Large Language Models to Master 16000+ Real-world APIs." arXiv:2307.16789; ICLR 2024.

Pushes the API-call surface to 16,000+ real-world APIs and introduces ToolBench. Important as the data-side counterpart to Gorilla and Toolformer.

### OpenAI Function Calling (June 2023) and Anthropic Tool Use (2024)

Production-side standardization of tool-call grammar in the major commercial APIs. The mechanism by which Toolformer-style ideas reached the world.

### Anthropic — Model Context Protocol (November 2024)

> Anthropic (2024). "Introducing the Model Context Protocol." Specification version 2024-11-05, released 25 November 2024.

Open standard for connecting LLMs to external tools and data sources via JSON-RPC, modeled on LSP. Solves the M×N integration problem (M models × N tools).

How Seon relates: Seon agents speak MCP. The Seon-side authoring story — write a fn with `:malli/schema`, expose it as an MCP tool — is the inverse of Anthropic's intended consumer path, but it works because of the standard.

---

## Section F — Agent sandboxing and capability-based execution

(Classical capability-security material — Dennis & Van Horn, Hardy's KeyKOS, Miller's E/Caja — is covered in the companion classical-prior-art document.)

### WASI Preview 2 + WebAssembly Component Model (January 2024)

> Bytecode Alliance. WASI 0.2 / Preview 2 launch, 25 January 2024.

The 0.2 release froze the Component Model and WIT (WebAssembly Interface Types) as the typed, capability-style interface format for WebAssembly modules. A component declares its imports — `wasi:filesystem`, `wasi:http`, custom interfaces — and the host decides which to grant. Architecturally this is microkernel capability passing, not microservices RPC.

How Seon relates: Seon's WASM-Tauri containment plan (`docs/seon/pod/wasm-spike-2026-05-20.md`) is *the agent runs as a wasm32-wasip2 component, the Rust host grants WIT-typed capabilities*. WASI 0.2 is the load-bearing standard underneath that whole design.

### OpenAI Code Interpreter / Advanced Data Analysis (2023)

The first widely-used production sandbox for LLM-generated code. Pioneered the consumer pattern of "model writes Python, system runs it in an isolated container, model sees output, iterates." Direct ancestor of Devin-style products.

### Anthropic Claude Code permission model (2024)

> Anthropic. Claude Code permission system, 2024–.

Per-tool, per-command allowlists with prompt-on-deny escalation. The current best-in-class developer-facing model for "agent runs as a real shell user but is gated at every system call."

How Seon relates: Seon's `seon.fs` default-deny allowlist (Phase 1, 2026-05-20) was designed against this template. The WASM-Tauri Phase 3 work moves the same idea behind a hardware-enforced boundary.

---

## Section G — Multi-agent foundations (1990s)

### Yoav Shoham — Agent-Oriented Programming (1993)

> Shoham, Y. (1993). "Agent-Oriented Programming." *Artificial Intelligence* 60(1): 51–92.

Proposed AOP as a specialization of OOP: an agent's state is *mental* — beliefs, decisions, capabilities, obligations — described in epistemic logic. The paper that named the field.

### Michael Wooldridge & Nicholas Jennings — Intelligent Agents: Theory and Practice (1995)

> Wooldridge, M. J., & Jennings, N. R. (1995). "Intelligent Agents: Theory and Practice." *The Knowledge Engineering Review* 10(2): 115–152.

The canonical survey. Established the working definition of "agent" (autonomy, reactivity, pro-activeness, social ability) that every subsequent textbook recycles.

### Anand Rao & Michael Georgeff — BDI architecture (1991)

> Rao, A. S., & Georgeff, M. P. (1991). "Modeling rational agents within a BDI-architecture." *Proceedings of the 2nd Int'l Conf. on Principles of Knowledge Representation and Reasoning (KR-91)*, pp. 473–484.

Belief–Desire–Intention as a formal architecture, derived from Bratman's philosophy of practical reasoning, implemented in PRS at SRI/Australian AI Institute. The framework that every "agent has beliefs, has goals, commits to plans" system descends from.

How Seon relates: Seon doesn't adopt BDI formally, but the framing "agent maintains a persistent representation of its commitments and updates them in response to observation" is BDI's contribution. Modern LLM-agent loops are BDI loops with a language model in place of formal modal reasoning.

### Stuart Russell & Peter Norvig — Artificial Intelligence: A Modern Approach (1995, first edition)

> Russell, S. J., & Norvig, P. (1995). *Artificial Intelligence: A Modern Approach.* Prentice Hall. (Now in its 4th edition, 2020.)

Important less for novel results than for the textbook treatment that organized "intelligent agent" into a canonical taxonomy (simple reflex, model-based, goal-based, utility-based, learning) studied by every CS graduate of the last 30 years.

---

## Section H — Continual and lifelong learning

### Sebastian Thrun — Lifelong Robot Learning (1995)

> Thrun, S., & Mitchell, T. M. (1995). "Lifelong Robot Learning." *Robotics and Autonomous Systems* 15(1): 25–46.

Argued that robots should learn novel tasks from far fewer training examples when they have already learned related tasks — i.e., that the unit of learning should be a *life*, not an episode. Introduced explanation-based neural-network learning (EBNN) as a vehicle.

### Mark Ring — CHILD (1997)

> Ring, M. B. (1997). "CHILD: A First Step Towards Continual Learning." *Machine Learning* 28(1): 77–104.

The acronym — Continual, Hierarchical, Incremental Learning and Development — names the four properties Ring argued an agent needs to keep growing. CHILD itself was a temporal-difference RL system that could transfer skills across tasks; the conceptual framing aged better than the algorithm.

### Kirkpatrick et al. — Elastic Weight Consolidation (2017)

> Kirkpatrick, J., Pascanu, R., Rabinowitz, N., et al. (2017). "Overcoming catastrophic forgetting in neural networks." *PNAS* 114(13): 3521–3526.

The high-water mark for "make a single neural network keep learning without forgetting prior tasks." Adds a Fisher-information-weighted quadratic penalty on changes to parameters important to previously-learned tasks.

### Richard Sutton & Andrew Barto — Reinforcement Learning: An Introduction (1998)

> Sutton, R. S., & Barto, A. G. (1998). *Reinforcement Learning: An Introduction.* MIT Press. (2nd ed., 2018.)

Foundational textbook; relevant here as the conceptual ancestor of every LLM-agent system that thinks of itself as performing trial-and-error against an environment with delayed reward.

How Seon relates: Seon's bet is that the "lifetime" of an agent is the lifetime of the codebase it inhabits, and continuity comes from the *persistent substrate* (DB, namespaces, accumulated tests) rather than from carrying weights forward. The continual-learning lineage is the conceptual ancestor of that bet even though the mechanism is different.

---

## Section I — Schema and contract-driven AI

### Khattab et al. — DSPy (2023, ICLR 2024)

> Khattab, O., Singhvi, A., Maheshwari, P., Zhang, Z., Santhanam, K., Vardhamanan, S., et al. (2023). "DSPy: Compiling Declarative Language Model Calls into State-of-the-Art Pipelines." ICLR 2024; arXiv:2310.03714.

Treats LLM calls as typed modules with input/output *signatures*, composed into pipelines that an optimizer can compile by selecting prompts and demonstrations to maximize a metric. The mature academic statement of "programs, not prompts."

How Seon relates: DSPy is the closest published analog to Seon's "every public fn is a `:malli/schema` map-in/map-out contract" principle, applied to LLM calls specifically. Seon extends the contract notion to the entire system surface, not just to model invocations.

### Pydantic AI / Instructor (2023–)

Production-side patterns for forcing LLMs to emit JSON conforming to a Pydantic schema, validated and re-prompted on failure. Practical reference for "make the model output match a typed contract" in the Python ecosystem.

### Microsoft TypeChat (2023)

> Microsoft (2023). TypeChat. <https://microsoft.github.io/TypeChat/>

Constraint: the LLM must emit TypeScript code that type-checks against a given interface. Tight type-system-as-prompt-spec is the mechanism.

How Seon relates: TypeChat + Pydantic AI + Instructor + DSPy are four arrows pointing at the same idea — *the model's output should be validated against a structural contract, and rejection should be a normal control-flow event.* Seon adopts this fully (Malli schemas at every boundary, validating `db/transact!`, instrumentation at function boundaries).

---

## Acknowledgments draft

Seon draws openly on a long lineage of work it does not pretend to invent.

The idea that a program can extend itself by discovering new heuristics traces to Doug Lenat's *AM* (1976) and *Eurisko* (1983), and to Schmidhuber's *Gödel Machine* (2003). The idea that programs can be evolved as a population is John Holland's (1975) and John Koza's (1992); modern LLM-driven variants — DeepMind's *FunSearch* (Romera-Paredes et al., 2023), *AlphaEvolve* (2025), *Promptbreeder* (Fernando et al., 2023), and OpenAI's *Eureka* (Ma et al., 2023) — taught us what the inner loop looks like when an LLM is the variation operator. The agent loop itself — reason, act, observe, repeat — is Yao et al.'s *ReAct* (2022); the practice of self-critique is Shinn et al.'s *Reflexion* (2023), Madaan et al.'s *Self-Refine* (2023), and Chen et al.'s *Self-Debug* (2023). The pattern of an agent that grows its own skill library is Wang et al.'s *Voyager* (2023). The argument that agents need a purpose-built interface to a codebase, not a generic shell, is Yang et al.'s *SWE-agent* (2024).

For the substrate side: tool-use as a teachable behavior is Schick et al.'s *Toolformer* (2023); standardizing tool exchange across model vendors is Anthropic's *Model Context Protocol* (2024); typed, capability-style sandboxing for agent code is the Bytecode Alliance's *WASI Preview 2* (2024). The contract-driven framing — every LLM call is a typed module — is Khattab et al.'s *DSPy* (2023). For the older multi-agent foundations: Shoham's *Agent-Oriented Programming* (1993), Wooldridge & Jennings' agents survey (1995), and Rao & Georgeff's *BDI* (1991). For the framing that intelligence accrues across a lifetime: Thrun & Mitchell's *Lifelong Robot Learning* (1995) and Ring's *CHILD* (1997).

Seon's contribution, to the extent it has one, is not in any single one of these mechanisms but in the *integration*: an LLM agent runs an evolutionary improvement loop over a Clojure substrate it itself extends, with typed contracts at every boundary, capability-sandboxed execution, and a queryable persistent memory. Every piece of that sentence is somebody else's idea. The combination, and the bet that the combination matters, is what we are putting forward.

---

## Unresolved attributions

Entries below could not be cleanly verified in this pass; they are flagged for follow-up rather than cited.

- **Karl Sims** is cited as having published a 1994 *Artificial Life* journal paper as well as the SIGGRAPH '94 paper. The SIGGRAPH version is verified above; the ALife paper exists but the exact title/page numbers were not confirmed in this pass.
- **Stuart Kauffman** — NK fitness landscapes (1989, 1993 *The Origins of Order*). Important conceptually for evolutionary computation but the specific influence on LLM-evolutionary systems is diffuse rather than via a single paper. Listed in the original brief; left out of the main sections because the citation would be cosmetic.
- **RepoCoder / RepoBench** — repo-scale code understanding (2023–2024). Real papers exist but the field is fragmented; the most-cited entry was not pinned down to a single canonical reference in this pass. Listed because Sean asked, but not given a section header.
- **Cursor Composer** — commercial product, no academic paper. Influence is real but uncitable in the academic format used elsewhere here.
- **Pydantic AI / Instructor** — these are tools, not papers. Acknowledged in Section I but not given a "citation."
- **OpenAI Function Calling** (June 2023) and **Anthropic Tool Use** (2024) — product announcements, not papers. Treated similarly.
- **Sakana AI's Darwin Gödel Machine (2025)** — surfaced in the Schmidhuber search results. Looks like a directly relevant modern follow-on (LLM-based self-improving agent that rewrites its own code, explicitly framed as a Gödel Machine descendant). Not pursued in this pass because the brief did not list it; worth a focused read for whoever does the next revision.
- **AlphaCode 2** (December 2023) — exists; not deeply researched. AlphaCode 1 covers the seminal claim.
- The brief mentioned an OpenAI paper named **"Olausson et al. — Self-Edit (2023)."** The paper that exists with that author and theme is Olausson et al., "Is Self-Repair a Silver Bullet for Code Generation?" (ICLR 2024). Worth re-checking before formal citation; included here for honesty.
