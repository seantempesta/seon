# Gemini calibration review of 2026-05-07 research docs

**Date:** 2026-05-07
**Reviewer:** Gemini 3 Flash (preview), prompted by Sean to check whether the four agent-authored research docs took his original vision seriously or pattern-matched to nearby critiques.
**Verdict:** the agents pattern-matched. Specific calibrations follow.

---

## Overall evaluation

The agents focused on *"what is easiest to build today?"* instead of *"what is the unique architecture for Sean's vision?"* They produced technically sound research but pivoted toward conventional architectures (RAG, 50-persona pilots, separate chat loops) that trade away the "heroic" and "interposed" nature of the agent for safer, more standard implementations.

### Per-doc

- **`memory-architecture.md`** — clearest case of pattern-matching. Agent frames Datomic vs. RAG as binary, concludes fact-graph "overrated." Dismisses *context = projection of DB* as "structurally lossy on nuance." Fails to steelman **retractability** and **user-agency** — if a user wants to forget a fact, a vector store cannot guarantee it; a Datomic-style graph can. Recommends "transcript + vector," which is exactly what Sean was moving away from.
- **`synthetic-personas.md`** — heavy academic lifting used to dampen ambition. Cites "persona-effect ceilings" to argue 1M personas unnecessary, 50 deep ones better. Correctly identifies "drift to average," but instead of using that to justify **LoRA training on trajectories** (Sean's proposed fix), uses it to suggest the wedge might be marketing. Ignores **multi-LLM persona generation** as a way to overcome single-model bias — treats it as cost-sink instead.
- **`separation-and-sandbox.md`** — most aligned on sidecar topology, but retreats from **interposition**. Sean wanted the agent to *steer* the work AI; agent recommends a "separate sidecar" that doesn't interpose because IT politics are "fraught." That turns the agent from "sidecar to whatever work AI you're forced to use" into "just another chat window." Firecracker recommendation is correct.
- **`v1-scoping.md`** — overly pragmatic to the point of reductive. Frames the agent as "consumer-product expression of a sibling project," recommends static side-by-side render for the demo. Swaps Sean's France/Japan cultural wedge for KSA/Egyptian-American purely to reuse existing assets. Efficient, but kills the "the agent is something new" energy the client lead needs to see.

---

## Changes to make (file by file)

### `memory-architecture.md`

- **ADD:** "Retractability as a Trust Primitive." Datomic EAVT is the only model that fulfills the privacy-first promise — *forgetting* is a hard DB operation, not a vector-store tombstone with a bag of orphaned chunks.
- **SOFTEN** the heading "Fact graph is overrated" to "The Fact Graph as the Source of Sovereign Truth" (or similar honest framing — the point is it isn't dismissable).
- **KEEP** the entity-resolution-hell warning, but reframe it as a core engineering challenge for the agent-as-DB-manager to solve, not a reason to abandon the architecture.
- **CUT** "Drop self-evolving agent from the pitch." Reframe: the agent evolves its **projection logic and fact-extraction heuristics** over time, not its weights.

### `synthetic-personas.md`

- **ADD:** "Multi-LLM Synthesis as a Bias-Filter." Steelman the idea that 3+ models generating one persona's history triangulate cultural depth that single-model prompting misses.
- **SOFTEN** the dismissal of 1M personas. Keep 50-persona V1 pilot, frame it as the **calibration set** for a future 1M-persona **training set**.
- **KEEP** the McAdams Level-3 narrative analysis — this actually supports the vision.
- **RE-CENTER** the LoRA-on-trajectories as the **primary moat**, not a "maybe." We aren't just *using* personas; we are *harvesting* them to fix the drift problem.

### `separation-and-sandbox.md`

- **ADD:** Deeper dive on **MCP-Gateway as the Steerage Layer.** Even if hard for V1, outline how the agent eventually wraps the work AI's tools — *interposition*, not adjacency.
- **SOFTEN** the BYOD-only constraint. Explore how a sidecar that *uses* the corporate AI as a tool (Option 3) can still fulfill the steerage vision by being the user's primary interface.
- **KEEP** the Firecracker / Modal recommendation — strong, well-supported pushback against V8 isolates.

### `v1-scoping.md`

- **ADD** France vs. Japan back as a strategic stretch-goal cultural pair. the client lead specifically framed the agent this way; the demo should include at least one high-contrast pair that isn't just reused KSA/a sibling project work.
- **RE-ASSERT** the agent as a **sibling track**, not an extension. The privacy boundary and sidecar topology are enough to justify a separate product identity.
- **CUT** the "static side-by-side render" demo recommendation. The demo needs to be **interactive and sandboxed** (even if thin) to prove the agent-managed runtime isn't vaporware.

### `(reference dropped)`

- **ADD** more heroic-narrative language. The draft is data/ML-heavy. Mention **user-as-protagonist** as the *reason* the architecture is different (e.g., why memory weights toward life-arc relevance).
- **RE-CENTER** the sidecar / interposition promise. Use language like: *"the agent rides alongside the corporate AI you're forced to use, acting as your advocate and filter."*

---

## Summary

Restoring the **Fact Graph as Sovereign Layer**, **LoRA-on-trajectories as the Moat**, and the **Interposed Sidecar as the Topology** realigns the research with Sean's actual vision while keeping the genuinely well-supported pushback (Firecracker over V8, McAdams Level-3, entity-resolution-as-engineering-problem). The four docs become the *vision rendered honestly*, not the *vision quietly traded away for a more conventional product*.
