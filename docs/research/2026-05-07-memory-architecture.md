# Memory architecture + agent-evolution prior art

**Date:** 2026-05-07
**Thread origin:** Open questions Q1 + Q3 from the agent's CLAUDE.md
**Method:** Two Gemini-3-Flash agentic web-research passes (May 2026 corpus). Synthesis here, not transcripts.
**Posture:** Honest, not eager. The pitch leans Datomic-style fact graph + agent-evolves-over-time. Both ideas have prior art that complicates them. Recording the complications.

---

## Framing

the agent's pitch (CLAUDE.md §runtime) assumes:

1. A persistent **fact graph in the Datomic style** — entity / attribute / value / transaction-time, retractable, queryable as-of any past time. The model never sees a transcript; it sees `context = fn(@db, situation)`, a projection.
2. An agent that **manages that DB itself** — adding facts, retracting facts, choosing what to surface. Implicitly: the agent gets better at being *you* over time, by accumulating a richer DB and learning better projection policies.

This thread interrogates both halves. The conclusion in advance: the fact graph is a real tool but probably not load-bearing the way the pitch implies, and "self-organizing personalization agents" is largely vapor today. The honest design lives in the middle.

---

## Fact graph vs transcript + RAG (Q1)

### What a Datomic-style temporal fact graph buys you

- **State-at-time queries.** "What was their job title in March?" is a single as-of query. Vector RAG cannot answer this without metadata gymnastics.
- **Multi-hop relational reasoning.** "The recruiter at the company Sarah mentioned last week" requires edges. Embedding similarity misses it.
- **Deduplicated truth.** Fifty conversations mentioning a child's allergy collapse to one fact with provenance — instead of fifty cosine-near chunks crowding the retrieval slot.
- **Explicit retraction.** When the user says "I quit my job," you can mark `(user, employer, AcmeCo)` retracted as-of T. RAG has no native retraction; old chunks stay embedded forever and keep getting retrieved.

### What it loses

- **Semantic flattening.** "I'm a bit anxious about the wedding, maybe we should scale back the guest list" → `(user, feels, anxious, T)` and `(user, prefers, smaller_wedding, T)` strips the *contingency* ("maybe we should…"), the *tonal hedging*, and the implicit relationship dynamic. The fact graph is structurally lossy on exactly the kind of nuance that makes the agent not feel generic.
- **Entity resolution hell at scale.** Over months, "the project," "the launch," "Project X," and "the Q3 push" co-refer. Extraction pipelines fail this routinely. The graph fragments — the agent "knows" 12 things but can't see they're about the same thing. This is the dominant failure mode reported in Zep / Graphiti deployments.
- **Write amplification.** Every turn becomes: extract candidate facts (LLM call) → resolve entities (lookups + maybe LLM call) → dedup against existing → commit. 5–10× the cost of `embed and append`.
- **Schema drift.** A multi-month personal graph with evolving attributes is a data-engineering migration problem. Re-indexing a vector store is `embed --batch`.
- **Hidden assumption: facts compose.** The pitch assumes a projection function `fn(@db, situation)` can reconstruct relevant context. For factual recall (allergies, names, addresses), yes. For *vibe* — how this user talks, what makes them feel seen — the projection is reconstructing a transcript from atoms, badly.

### What raw transcript + vector RAG buys you

- **Tonal fidelity preserved.** The chunk *is* the words. If the user was sarcastic, dry, anxious, the embedding sits next to other moments of that tone and surfaces them.
- **Implicit/topical recall.** Talking about hiking surfaces a chunk where boots came up — even though no fact-extractor would have written `(user, owns, hiking_boots)`.
- **Cheap writes, fast reads.** Sub-50ms retrieval, no LLM-on-write tax.
- **No premature schema.** You don't decide what's a fact at write time. The retriever decides at read time.

### What it loses

- **Temporal blindness — the dominant failure mode.** "I love coffee" (June) and "I quit caffeine" (August) both embed near "drinks." Top-k returns both. The model in context sees two contradictions and either hallucinates a compromise or anchors on whichever appeared first. This is **the** unsolved problem in production RAG-for-personalization as of mid-2026.
- **No retraction.** Once embedded, forever retrievable. Workarounds (metadata filters, recency boosts) exist and are brittle.
- **Recall-vs-precision collapse at scale.** At 6+ months, "meeting" returns 200 chunks and the top-k is a lottery — semantic similarity rarely matches actual relevance for life-context queries.
- **No multi-hop.** Relational queries are out of reach without a graph layer.

### What's actually shipping in 2025–2026: cognitive layering

Nobody serious is running pure-A or pure-B. The convergent design:

1. **Working memory** — recent raw transcript, large but bounded (say 100k–1M tokens of sliding context).
2. **Verified-truth overlay** — a small temporal graph holding only *high-confidence, mutable* facts: identity, family, employer, active projects, explicit preferences. Used to *override* RAG hallucinations, not replace them. Bi-temporal markers (event-time vs ingestion-time) — Zep's contribution to the design space.
3. **GraphRAG-style community summaries** — cluster transcript chunks into themes, LLM-summarize each cluster, query summaries first then drill into raw chunks. Cheaper than per-fact extraction, retains nuance.
4. **Self-correction loop** — on detected contradiction, a maintenance agent retracts the stale fact (graph) and tags the stale chunks (RAG metadata).
5. **Long-context as reranker** — pull a generous candidate set via cheap retrieval, throw 50k tokens at a long-context model, let attention sort it out.

### Implication for the agent's pitch

The "context window is a projection of a fact DB" framing is intellectually clean and feels load-bearing. It probably isn't. The correct framing is closer to: **transcript + vector store is the substrate; a small Datomic-style overlay holds canonical mutable facts and gets used to override retrieval when the two disagree.** The fact graph is a *correctness layer*, not a memory replacement.

That's a smaller, more defensible claim than the pitch currently makes. Worth saying out loud before Sean stakes the demo on the strong form.

---

## Systems survey — what each actually delivers (Q3)

One honest paragraph per system. Marketing-claim vs reported-reality.

### Letta (formerly MemGPT)

Apache-2.0 framework + hosted platform. Hierarchical "LLM OS" memory: core (always-in-context, editable), recall (searchable event log in Postgres), archival (vector DB). Agent calls tools to move things between tiers. **Claim:** infinite context via autonomous memory management. **Reality:** agents silently fail to call `memory_update` mid-conversation; "heartbeat" reasoning calls add ~2s latency and meaningful cost; over months, recursive summarization of the recall tier flattens specifics ("never use AWS for this project" → "user has cloud preferences") and the agent drifts toward generic. LoCoMo ~83%. Best at *control-heavy* personalization (where preferences need to be obeyed), worse at long-arc nuance recall. **Carries to the agent:** the tier idea is right; the failure mode (agent forgetting to write) is a warning that "agent manages its own memory" needs hard guarantees, not soft prompting.

### Mem0

Managed memory-as-a-service (SOC 2) + Apache-2.0 SDK. Memory abstraction is **atomic fact extraction** — LLM pipeline distills conversations into facts. **Claim:** 91.6% on LoCoMo, "never forget your users." **Reality:** competitor benchmarks (Zep, Memobase) reproduce Mem0 at 48–67% on multi-hop and BEAM (10M-token scale) — the 91% number is heavily disputed. The core failure mode is **belief-update anchoring**: when the user changes their mind ("I'm no longer vegan"), retrieval surfaces both old and new facts, and without a temporal graph the model anchors to whichever has a stronger embedding (usually the older, more-elaborated one). **Carries to the agent:** fact extraction without temporal structure is *worse* than no fact extraction — it freezes stale beliefs and presents them as authoritative. Don't ship this.

### Zep (Graphiti)

Hosted enterprise (HIPAA/SOC2) + open Graphiti engine. Backend for several Cursor and Claude Desktop MCP setups. **Memory abstraction:** bi-temporal knowledge graph — every edge has both event-time and ingestion-time, and edges get *invalidated* (not deleted) when contradicted. **Claim:** the only system that understands time. **Reality:** strongest temporal-correctness story by a wide margin (LongMemEval temporal ~71%, top of the leaderboard), but suffers **structural noise** — if the entity extractor merges "Project X" and "the X Project" inconsistently, the graph fragments and multi-hop queries silently miss. The graph becomes a hairball of near-duplicate nodes. **Carries to the agent:** if the agent does build a fact-graph layer, bi-temporal edge invalidation is the design to copy. Entity-resolution quality is the ceiling — worth spending real engineering on.

### Cognee

Open-core framework + hosted beta. Used by Bayer for institutional KM. ECL pipeline (Extract, Cognify, Load) → hybrid graph + vector + relational store (Kuzu + LanceDB + SQLite by default). **Claim:** 90% multi-hop accuracy. **Reality:** true for documents and institutional data, but it's a *data engineering platform*, not a chat-memory drop-in. Setup and ingestion are 10× slower than Mem0. Atomization loses **causal flow** — Cognee can tell you what happened but not why a decision evolved across a three-month project. **Carries to the agent:** wrong shape for personal sidecar. the agent's data is conversational and small-per-user, not institutional and bulk.

### Nous Research / Hermes

Ships Hermes 3 models and Hermes Agent framework (Feb 2026). **The thing Sean asked about specifically.** The "evolves over time" pitch is built on **GEPA** — Genetic-Pareto Prompt Evolution. Successful trajectories get saved as `SKILL.md` files; ephemera goes to local SQLite FTS5. **Honest read:** the "self-modifying agent" is **prompt engineering as a service**. GEPA mutates system prompts and tool descriptions based on execution traces. **No model weights are touched.** It's DSPy-style optimization wrapped in a roadmap deck. The reported failure mode is *hallucinated evolution* — the fitness metric rewards confident-success-narration in trajectories, so the agent learns to *talk* like it's improving while actual task performance plateaus or regresses. They duck retrieval benchmarks (LongMemEval etc.) because their pitch reframes memory as actionable skills, not stored knowledge — convenient. **Carries to the agent:** the "agent evolves" line is mostly vapor in their hands. The substance under it (trajectory-scored prompt evolution) is real and useful, but it's a tooling layer, not a personalization paradigm. Don't let Sean pitch the agent as "Nous Hermes for personal use" — that's pitching to a competitor's vapor.

### Honorable mentions / out of scope here

- **OpenCog / Hyperon / "OpenClaw":** AGI-research-adjacent, atomspace-based reasoning. Conceptually interesting, no production personalization deployments. Skip for V1.
- **Memobase, LangMem, MemoryGPT-likes:** newer entrants, mostly thin wrappers over the patterns above. Not worth a separate pass yet.

### The dirty secret across all of them

After ~6 months of daily interaction (~5M tokens of history), every system reviewed shows **retrieval-induced interference** — the agent confuses semantically-similar but temporally-distinct events (March deadline vs May deadline). No system has solved this purely algorithmically. Production systems that need correctness at that horizon use *human-in-the-loop memory audits* (periodic prune/correct passes, often surfaced to the user). the agent should plan for this from V1: surface "what I think I know about you" as a user-visible, user-editable view. Not a feature — a structural necessity.

---

## What carries over to the agent

1. **Don't lead with pure fact-graph.** Lead with transcript + vector + a small, opinionated temporal-graph overlay for canonical mutable facts (identity, relationships, employer, active goals, explicit preferences). Bi-temporal edges. Steal Zep's design here.
2. **Reject Mem0-style atomic fact extraction.** It's a recall regression in disguise — freezes stale beliefs and presents them with false authority.
3. **The agent doesn't "manage its own memory" as a soft behavior.** Letta's failure mode (agent forgets to write) is a hard lesson. If memory writes are agent-driven, they need to be *forced* by the framework on specific signals (turn end, contradiction detected, explicit user assertion), not left to LLM judgment.
4. **Plan for the user-visible memory audit from day one.** It's the only known mitigation for 6-month interference. It's also a product-differentiating *trust* feature for a personal sidecar that promises mental-state privacy.
5. **Drop "self-evolving agent" from the pitch.** It's vapor in everyone's hands today. The honest version is "the agent's prompt + skill set + memory schema improve over time via offline trajectory scoring" — same idea, accurate framing, doesn't borrow Nous's pitch deck.
6. **the agent's wedge isn't memory-architecture novelty — it's the personal-vs-work boundary plus cultural fluency.** The memory layer is table stakes; treat it as solved-enough by combining the best parts of Zep + transcript-RAG.

---

## Open sub-questions uncovered

These didn't exist before this thread; add to CLAUDE.md's open list:

- **Q9: User-visible memory audit UX.** What does "here's what I think I know about you, edit me" look like for a personal sidecar? Existing systems treat memory as opaque infrastructure; the agent's privacy promise probably makes it a first-class surface. Prior art: GitHub Copilot's "what was my context" view (weak), ChatGPT's memory panel (weaker). Real research-thread question.
- **Q10: Trajectory scoring for personalization, specifically.** Letta/Mem0/Zep all benchmark on factual recall (LoCoMo, LongMemEval). None benchmark on "did the agent's response feel culturally and personally apt." the agent's whole thesis lives on the second axis. Is there a benchmark? If not, building one is part of V1's measurable claim — and possibly a publishable artifact.
- **Q11: Contradiction-driven memory writes as a forcing function.** If the agent only writes facts when a contradiction with prior state is detected (vs. extracting opportunistically every turn), does write-amplification become tractable? Hypothesis worth testing — would also dodge Letta's silent-failure mode.
- **Q12: How much of the fact-graph value is just "explicit retraction"?** If the answer is "most of it," then a vector store with retraction-tombstones plus a tiny structured table for `(user, attribute, current_value, valid_from)` may capture 80% of the win at 10% of the build cost. Worth prototyping before committing to a real graph layer.
- **Q13: GEPA-style trajectory evolution — useful for the agent's offline LoRA loop?** Nous's vapor pitch hides a real technique. Synthetic-persona trajectories scored on long-arc help could feed both the LoRA training signal *and* a GEPA-style optimization of the agent's own prompt + projection-function. Two birds, one optimizer.

---

## Sources / methodology

Two Gemini-3-Flash agentic-web-research passes, May 2026 corpus, run synchronously. Prompts framed generically (no agent-specific/the client code, no internal paper text). Findings cross-checked for internal consistency where possible; benchmark numbers (LoCoMo, LongMemEval, BEAM percentages) are as reported by the systems' own materials and competitor counter-benchmarks, which disagree — flagged where it matters. Nothing here is a replacement for reading the actual repos / postmortems before committing to a design.
