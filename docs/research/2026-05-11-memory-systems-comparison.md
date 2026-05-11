# LLM memory systems — sober comparison for the agent positioning

**2026-05-11**

the agent's architecture bet (Decisions table, 2026-05-08): the agent learns its own storage idiom from primitives + curriculum, writes its own functions in CLJS via sci against a Datascript fact graph, and those functions get spec+test-gated before persisting to a per-user library. No inherited memory APIs. Retrieval is **code the agent wrote and tested for itself**, operating on **typed datoms with provenance**, not fuzzy embedding lookup over remembered summaries.

This doc surveys the popular memory systems on the dimensions that matter for that positioning, with honest pushback where the differentiation claim is weaker than we'd like.

## The contenders

### 1. Letta (formerly MemGPT) — [github.com/letta-ai/letta](https://github.com/letta-ai/letta)

Production runtime descended from the MemGPT paper. Memory is **fixed text blocks** (core/human/persona) plus a vector **archival store** plus a recall log. Writes are **agent-decided** via tool calls (`core_memory_replace`, `archival_memory_insert`). Retrieval is **exact-block read** for core memory (always in context) and **top-K vector** for archival. Schema is freeform-text-inside-bounded-blocks. Provenance: archival entries can be timestamped, but they're entries the agent wrote — *summaries*, not pointers back to source artifacts. Hallucination posture is the system's main weakness: the LLM is the curator, so a hallucinated extraction becomes an entrenched "fact" the agent keeps re-injecting. Retractability is per-entry. No code at retrieval time — just tool-call paging.

### 2. Mem0 — [github.com/mem0ai/mem0](https://github.com/mem0ai/mem0)

Markets itself as "the memory layer." Under the hood: an **extraction pass** (LLM call) that pulls facts out of conversation turns, embeds them, stores them in a vector DB with optional graph edges. Retrieval is **hybrid** — semantic vector search + BM25 + entity filters. Schema is freeform extracted text. Groundedness: low — the stored fact is a *paraphrase* the extractor LLM produced, not a citation to the source turn. Hallucinations: if the extractor invents an attribution, that invention is what gets retrieved later, with no link back to verify. Retractability: per-memory UUID. No code at retrieval time. Honest read: Mem0 is a well-engineered RAG-over-extracted-facts wrapper. Solid, ships, popular — but architecturally it's the thing the agent's design is reacting against.

### 3. Zep / Graphiti — [github.com/getzep/graphiti](https://github.com/getzep/graphiti)

The strongest competitor on the agent's claimed differentiators. Graphiti is a **temporal knowledge graph**: typed entity nodes, typed relationship edges, each with **validity intervals** (`valid_from` / `valid_to`). Writes happen on an **async extraction pass** that resolves entities, detects contradictions, and **invalidates superseded edges by setting their valid-to timestamp** rather than overwriting. Retrieval is hybrid (vector + graph traversal + MMR). Schema is typed. **Groundedness is real**: facts trace back to "Episodes" — the raw ingested turns/documents they were extracted from. Retractability is edge/node/episode-level. Hallucination posture: temporal validity handles the *contradiction* failure mode well, but the *extractor-invented-a-fact* failure mode is unchanged — Graphiti still relies on an LLM extraction pass to produce its typed facts. No agent-written code at retrieval time; retrieval is a fixed hybrid search pipeline.

**This is the system the agent should worry about.** The "typed facts with provenance and explicit retraction" pitch is largely Graphiti's pitch. the agent's remaining differentiation is the *agent writes code against the graph*, not the graph shape itself.

### 4. Cognee — [github.com/topoteretes/cognee](https://github.com/topoteretes/cognee)

Knowledge graph + vector hybrid with **Pydantic-typed ontologies** the user defines upfront. Background "cognify" pipeline extracts entities + relationships, builds the graph, embeds nodes. Retrieval auto-routes between graph traversal and vector search. Groundedness is good — nodes link back to source documents. Heavy upfront schema design cost; rigid ontologies are a feature for compliance domains and a friction for personal use where you don't know the schema in advance. No agent-written code at retrieval.

### 5. MemGPT (original paper) — [arxiv.org/abs/2310.08560](https://arxiv.org/abs/2310.08560)

The intellectual ancestor. Frames the LLM as an OS managing tiered memory: main context (RAM), recall storage (chat log), archival storage (vector DB). Explicit API the agent calls: `recall_memory_search`, `archival_memory_insert`, etc. Influential framing; in practice brittle on early models due to constant tool-call overhead. Letta is the maintained descendant.

### 6. Nous Research — no public memory product

Searched: Nous ships open-weight models (Hermes 3, Hermes 4, DeepHermes) and the [Atropos](https://github.com/NousResearch/atropos) RL environments framework. There is **no publicly documented "Nous memory system"** as a distinct product. The closest is the convention of `MEMORY.md` / `USER.md` markdown files as part of agent prompts — a pragmatic "facts in a file, always injected" pattern. Useful as a baseline ("can the dumbest possible thing beat your fancy graph?"); not architecture.

### 7. Anthropic Claude memory — [docs.claude.com/en/docs/agents-and-tools/tool-use/memory-tool](https://docs.claude.com/en/docs/agents-and-tools/tool-use/memory-tool)

Anthropic shipped a **memory tool** (beta, 2025) — a client-side tool spec (`view`, `create`, `str_replace`, `insert`, `delete`, `rename`) operating on a file-tree under `/memories/`. Storage is **the developer's problem** — Anthropic provides the tool schema, you provide the backend (filesystem, S3, whatever). Primitive: text files. Write trigger: agent-decided via tool call. Retrieval: exact path read. Groundedness: whatever the agent wrote down — summaries, not source pointers. Retractability: file/path level. **Not a memory system; a memory-tool-calling convention.** The substance lives in whatever backend the dev wires up.

### 8. OpenAI ChatGPT memory — [openai.com/index/memory-and-new-controls-for-chatgpt](https://openai.com/index/memory-and-new-controls-for-chatgpt/)

A hidden `bio` tool the model uses to write **freeform text bullets** to a per-user store. Bullets are injected into the system prompt on subsequent turns. No vector retrieval — the entire bullet list is just dumped in. User-visible memory management UI (delete bullets one at a time). Primitive: text. Trigger: agent-decided. Retrieval: exact full-set injection. Groundedness: none — bullets are model-authored paraphrases with no link to the conversation they came from. Hallucination posture: vulnerable; a wrong inferred "user prefers X" persists until the user notices and deletes it. Architecturally primitive; UX is the product.

### 9. 2025-2026 entrants worth flagging

- **A-MEM** ([arxiv.org/abs/2502.12110](https://arxiv.org/abs/2502.12110)) — Zettelkasten-style "agentic memory" where the agent links new notes to existing ones; self-evolving graph. Provenance to origin turn. Closer in spirit to the agent, but still extraction-pass-then-vector-retrieval at query time, not agent-written code.
- **MemoryOS** ([arxiv.org/abs/2506.06326](https://arxiv.org/abs/2506.06326)) — three-tier OS-like cache (short/mid/long), heat-based promotion, fact extraction. OS-metaphor revival; still embedding retrieval underneath.
- **LangGraph / LangMem** — LangChain's memory stack. Vector + summary + entity store. Boring, mainstream, no architectural surprise.

## Comparison table

| System | Storage | Write trigger | Retrieval | Schema | Groundedness | Hallucination posture | Retractability | Code at retrieval |
|---|---|---|---|---|---|---|---|---|
| **the agent** | Typed datoms (EAVT) + agent-written CLJS functions | Agent-decided + ingestion pipeline | Agent-written queries (code) over typed graph | Typed (Malli specs) | High — datoms cite source artifacts | Queries are deterministic; no fuzzy blend | Per-datom retraction (Datomic-native) | **Yes — agent writes + tests query functions** |
| Letta | Text blocks + vector archival | Agent tool call | Exact block + top-K vector | Freeform text | Low — summaries the agent wrote | Vulnerable — entrenched extractions | Per-entry | No |
| Mem0 | Vectors + extracted facts | Extraction LLM pass | Hybrid (vector + BM25 + entity) | Freeform | Low — paraphrased extractions | Vulnerable — no source link | Per-UUID | No |
| Zep / Graphiti | Typed graph w/ temporal edges | Async extraction pass | Hybrid (vector + traversal) | Typed | **High — Episode provenance** | **Strong on contradictions; still LLM-extraction-bound** | Edge/node/episode | No |
| Cognee | Graph + vectors w/ Pydantic ontology | Background cognify | Auto-routed hybrid | Typed (user-defined) | High — doc-anchored | Strong via ontology gate | Node/edge/doc | No |
| MemGPT paper | Tiered text + vector archival | Agent function call | Exact + vector | Freeform | Mixed | Vulnerable | String | No |
| Nous (markdown convention) | Text files | Agent edits | Full injection | Freeform | Low | Vulnerable | File edit | No |
| Anthropic memory tool | Files (dev-hosted) | Agent tool call | Exact path | Freeform | Low | Dev-controlled | File | No |
| ChatGPT memory | Text bullets | Hidden `bio` tool | Full injection | Freeform | None | Vulnerable | Per-bullet | No |
| A-MEM | Self-linking notes graph | Extraction + linking | Graph + vector | Semi-structured | Medium-high | Better via cross-validation | Node | No |

## Synthesis — where the agent actually stands

**The honest read on differentiation:**

1. **"Typed facts with provenance and retraction" is not unique to the agent.** Graphiti, Cognee, and A-MEM all deliver this. The temporal-graph + Episode-provenance pattern in Graphiti is, if anything, *more developed* than what the agent has spec'd today. We should stop selling this as a differentiator and start selling it as **table stakes for a serious memory system**, of which the agent is one.

2. **"Agent writes code to process its own memory" is the actual differentiator.** Every system surveyed treats retrieval as a fixed pipeline — vector search, graph traversal, hybrid rerank — that the agent *invokes* but does not *compose*. the agent's `define(name, spec, impl, tests)` loop, with spec+test-gated functions persisting to a per-user library, has no equivalent in the surveyed field. The closest is A-MEM's self-linking, but that's still inside a fixed retrieval pipeline; the agent does not write the retrieval itself. **This is the load-bearing claim.** It should lead the pitch.

3. **The "queries are deterministic code, not fuzzy embedding match" angle is real and load-bearing for the privacy promise.** Most surveyed systems blend retrieved text into the prompt as if authoritative. When ChatGPT remembers "Sean prefers concise responses," there is no way to ask *why it thinks that*. When the agent says "Sean owes a teammate a reply about UX," the agent ran a query whose source is inspectable, whose datoms cite Episodes, and whose code lives in the user's library. This is not just a different retrieval mechanism — it is a different **epistemics**.

4. **Groundedness-by-construction is partially overlapping with Zep.** the agent's datoms-cite-source claim is real; Zep's Episodes-as-provenance is also real. The differentiator is *who composes the query that surfaces them* (the agent: agent code; Zep: fixed pipeline) and *whether the user can read/audit/edit the query logic* (the agent: yes, it's a CLJS function in their library; Zep: no, it's a search call in Zep's binary).

5. **the agent's risk:** the function-library moat depends on the agent being good enough at writing CLJS-against-Datascript that the resulting library is actually useful and compounds over time. If the agent's emitted code is mediocre, what we have is a fancier Mem0 with extra ceremony. The Qwen CLJS eval ([2026-05-08-qwen-cljs-eval.md](2026-05-08-qwen-cljs-eval.md)) is the honest test of whether this thesis ships.

**Recommended positioning shift:** stop leading with "typed graph + provenance" (Graphiti already owns that framing in the market). Lead with **"the agent writes inspectable code against its own database,"** and let groundedness/retractability/typing follow as consequences of that architectural choice.

## Sources

- Letta: [github.com/letta-ai/letta](https://github.com/letta-ai/letta), [docs.letta.com](https://docs.letta.com)
- Mem0: [github.com/mem0ai/mem0](https://github.com/mem0ai/mem0), [docs.mem0.ai](https://docs.mem0.ai)
- Zep / Graphiti: [github.com/getzep/graphiti](https://github.com/getzep/graphiti), [help.getzep.com](https://help.getzep.com), paper: [arxiv.org/abs/2501.13956](https://arxiv.org/abs/2501.13956)
- Cognee: [github.com/topoteretes/cognee](https://github.com/topoteretes/cognee), [docs.cognee.ai](https://docs.cognee.ai)
- MemGPT: [arxiv.org/abs/2310.08560](https://arxiv.org/abs/2310.08560)
- Nous Research (no memory product): [github.com/NousResearch](https://github.com/NousResearch), [github.com/NousResearch/atropos](https://github.com/NousResearch/atropos)
- Anthropic memory tool: [docs.claude.com/en/docs/agents-and-tools/tool-use/memory-tool](https://docs.claude.com/en/docs/agents-and-tools/tool-use/memory-tool)
- ChatGPT memory: [openai.com/index/memory-and-new-controls-for-chatgpt](https://openai.com/index/memory-and-new-controls-for-chatgpt/)
- A-MEM: [arxiv.org/abs/2502.12110](https://arxiv.org/abs/2502.12110)
- MemoryOS: [arxiv.org/abs/2506.06326](https://arxiv.org/abs/2506.06326)
