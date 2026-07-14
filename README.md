# Seon

**A personal AI that can do anything for you, because it can write code.**

Seon — from the archaic "to see", and from the Seons of Brandon Sanderson's Cosmere: sentient, luminous beings bonded to their humans — is built around a different premise. The right shape for an AI assistant is not a chat window and a fixed catalog of tools, but a long-running runtime that grows alongside you. You ask Seon for something; Seon writes the code that does it; that code lives in Seon from then on; the next thing you ask uses what was built last time. It all stays with you, runs locally, and is open source.

This README is the short version. The long-form story — the full argument, the architecture in narrative, the lineage, and an honest accounting of where the eighteen months of work have landed — is in [`docs/seon/vision/index.md`](docs/seon/vision/index.md).

## What this lets you do

Today's commercial AI assistants are excellent autocomplete and good at answering questions. Seon is aimed at something different: a personal system that, over months and years, accretes into a tailored interface to your own life. Some of the domains I've personally explored or want to explore on top of this runtime:

- **Education.** *The Diamond Age*'s "Young Lady's Illustrated Primer" — a pedagogical AI bonded to a child — is the design north star here. The repo has a working prototype of a primer-style storytelling system; the long-term goal is something my niece and nephew could actually use, and that anyone with kids could fork and tailor.
- **Personal finance and trading.** Track your accounts. Have the system propose strategies. Have it write the dashboard you want, not the dashboard a SaaS company decided to ship.
- **Health.** Workout data, biomarkers, sleep, the boring spreadsheets that turn into useful patterns when something is actually paying attention.
- **Knowledge work.** A persistent, queryable graph of what you've read, what you've decided, and why. Notes that the system actually understands, because it wrote the schema for them with you.
- **Whatever else.** The point of a self-evolving harness is that you don't have to enumerate in advance.

The personal domains are test cases. The runtime is the product.

## The premise

More code will be written in the next couple of years than has been written in all of history, and the writers will be AI agents. Today's languages and toolchains were designed for humans who edit text files in editors, then build, then ship — a loop that fits poorly around an agent that should be making thousands of small adjustments per day to a system already running. The right shape is the opposite: an always-on runtime that the agent rewrites continuously, in place, while it serves you.

Clojure already is that shape. McCarthy designed Lisp in 1958 because the languages of his day were ill-suited to symbolic reasoning, the kind of thing he was just starting to call "artificial intelligence." Lisp's defining property is that code and data are the same — everything you can read, you can write to. Rich Hickey carried that lineage into Clojure (2007) and Datomic (2012): immutable data, schemas as data, time as a first-class queryable dimension, the REPL as the way you actually develop. An agent inside a Clojure REPL can read its own functions, edit them, transact the change, and the running system reflects it the next call. No build step, no redeploy, no abstraction between intent and effect.

That's the bet. Hand the agent a language, not a list of tools.

## The bet: hand the agent a language, not a list of tools

The prevailing shape for an AI assistant is to give the model a fixed catalog of tools — `read_file`, `write_file`, `run_bash`, `search_web`, maybe a dozen others — and let it compose its work within that surface. The catalog defines what the agent can do; new capabilities mean someone has to write a new tool.

Seon takes a different path. The agent's surface is Clojure (today, on the JVM and via a CLJS pod) inside a self-evolving harness — a Datalog graph for memory, Malli schemas for contracts, a REPL pipeline for change, a capability-gated WebAssembly boundary for what's safe. Where the tool catalog ends, the language continues. Need to call an API? Write a function. Need a new dashboard? Write a namespace. Need a behavior nobody anticipated? Write it.

This is Sutton's "[Bitter Lesson](http://www.incompleteideas.net/IncIdeas/BitterLesson.html)" applied to agent harness design. Across seventy years of AI research, general methods that leverage computation tend to outlast methods that bake in human knowledge. Seon's bet is that the same principle applies a level up: hand-curating an agent's tool surface might serve for a while, but generality is the long game.

The mechanism has a long lineage. Lenat's [Eurisko](https://en.wikipedia.org/wiki/Eurisko) (1981–83) was a program that modified its own heuristics, including the heuristics for modifying heuristics. Schmidhuber's [Gödel Machine](https://arxiv.org/abs/cs/0309048) (2003) formalized it. [Voyager](https://voyager.minedojo.org/) (Wang et al., 2023) is the LLM-era version: an agent that grew its own skill library in Minecraft and got monotonically better at the game. DeepMind's [FunSearch](https://www.nature.com/articles/s41586-023-06924-6) (Nature, 2023) and [AlphaEvolve](https://arxiv.org/abs/2506.13131) (2025) ran the same loop against published mathematics and discovered new results. The underlying shape is reinforcement learning with a self-modifying policy. Seon is one small version of that, running on a developer's laptop, over a computational fabric the agent inhabits rather than visits.

## How it works (briefly)

The runtime is built on five primitives. Each has a component note in [`docs/seon/components/`](docs/seon/components/); each has a PRD in [`docs/prds/`](docs/prds/); the v1 specification lives in [`docs/prds/agent-runtime/v1.md`](docs/prds/agent-runtime/v1.md).

- **Multi-agent shared workspace.** Multiple agents work inside the same running runtime, calling each other's functions, transacting into the same graph, and editing each other's code under capability gates. Code is organized into Clojure namespaces wired into a `core.async.flow` topology — typed message envelopes, uniform backpressure, observability — but agents aren't pinned to a single namespace. Any agent can touch any surface the capability layer permits. ([`docs/seon/concepts/namespace-as-process.md`](docs/seon/concepts/namespace-as-process.md))
- **Schema-as-contract.** Public functions advertise Malli input/output schemas with fully namespaced keywords (`:seon.trading/position`, never `:position`). The schemas land as datoms in a Datalog graph. "Which functions accept this shape?" is a database query. ([`docs/seon/components/schema-system.md`](docs/seon/components/schema-system.md))
- **Graph-as-source-of-truth.** Datahike (Datomic-style EAV with bitemporal history) holds the program graph, the data graph, and the conversation graph. The active CLJS pod forwards writes over a Unix socket to the JVM database server (file-backed Datahike at `data/clusters/default/db`) and reads local lazy database values; that server also carries the Proximum HNSW vector index for semantic search. (The paused JVM track embeds Datahike in-process.) One pull reconstructs any agent turn, and durable facts survive restarts. ([`docs/seon/components/database.md`](docs/seon/components/database.md))
- **REPL-as-interface.** The agent does not edit files. It evals forms in a REPL. The pipeline validates the schema, transacts metadata into the graph, persists the source to disk, and runs the tests the schema selects. Files are a persistence format, not the source of truth. In the live CLJS pod this goes one step further — the agent's *whole context is a render of the database* and its *loop is a function of the database* (see Status). ([`docs/seon/components/dev-tools.md`](docs/seon/components/dev-tools.md))
- **WASM containment.** The agent's eval surface is moving into a `wasm32-wasip2` Component embedded in a Tauri host process. The capability surface is WIT-typed: `fs`, `http`, `mcp`, `capability-prompt`, `eval`. The Rust host decides what to grant. Wasmtime enforces. ([`docs/prds/agent-runtime/platform.md`](docs/prds/agent-runtime/platform.md))

The language story is open-ended. CLJS today; Python next; ultimately anything the WebAssembly Component Model can host. The runtime is language-agnostic by design — the agent's intelligence comes from the LLM, the safety from the WIT-typed boundary, the persistence from the graph. The language choice becomes a matter of "what fits this task," not a permanent commitment.

## Inspirations

A short list of the people I'm explicitly indebted to. The longer list — Engelbart, Bush, Licklider, Kay, Bret Victor, Hewitt, Lenat, Wang, the WASI working group, and many more — lives in [`docs/seon/vision/prior-art-credits-2026-05-23.md`](docs/seon/vision/prior-art-credits-2026-05-23.md) and [`docs/seon/vision/prior-art-agents-and-evolution-2026-05-23.md`](docs/seon/vision/prior-art-agents-and-evolution-2026-05-23.md).

- **John McCarthy** (Lisp, 1958). Designed a language because the ones he had couldn't carry the symbolic reasoning AI was going to need. Sixty-eight years later, his choice is still load-bearing for this kind of work. ([History of Lisp](http://jmc.stanford.edu/articles/lisp/lisp.pdf))
- **Rich Hickey** (Clojure, 2007; Datomic, 2012; clojure.spec, ~2016). Immutable data; identity and state as distinct things; schemas as data; EAV with time as a queryable graph; the REPL as the unit of work. Seon uses every one of these. ([clojure.org](https://clojure.org/about/rationale))
- **Brandon Sanderson** (the Cosmere, 2005 onward). The Seons — sentient luminous beings bonded to their humans, introduced in *Elantris* — gave this project its name and its metaphor.
- **Neal Stephenson** (*The Diamond Age*, 1995). The Young Lady's Illustrated Primer is the design-fiction reference point for what a bonded AI ought to do for the person it serves.
- **Rich Sutton** ("[The Bitter Lesson](http://www.incompleteideas.net/IncIdeas/BitterLesson.html)", 2019). Seventy years of AI research collapsed into one usable principle. Seon's "language, not tools" bet is one version of his thesis applied to agent harness design.

## Acknowledgements

Seon is built on open-source work it owes directly, across two layers.

The data and language foundation:

- **datahike** (the replikativ team, EPL-1.0). The open-source, Datomic-compatible Datalog database — plus the Proximum HNSW vector index — that Seon runs as its store. It implements the EAV-with-bitemporal-time data model of Datomic, on the Clojure language — both designed by **Rich Hickey**, whose deeper influence (immutable data, schemas as data, time as a queryable dimension) is credited under Inspirations above.

The real-time UI:

- **Datastar** (Star Federation, MIT). The hypermedia framework behind Seon's SSE-driven canvas — declarative `data-*` attributes, one connection up, the server streams HTML and the DOM patches itself.
- **[hyperlith](https://github.com/andersmurphy/hyperlith)** (Anders Murphy, MIT). The render-loop design Seon's live-feed layer adopts: render the view as a pure function of state, stream the compressed result, let the client apply it — no client-side state machine. Anders's "send the whole render down and diff on arrival" approach (and his poor-man's-datastar experiment) shaped how Seon builds composable feeds and interactive tiles.

Resilience:

- **[again](https://github.com/liwp/again)** (Lauri Pesonen, originally listora, EPL-1.0). The retry library whose **strategy-as-a-lazy-seq-of-delays** design Seon's `seon.retry` adopts — backoff is a composable sequence (`multiplicative` → `randomize` → `clamp-delay` → `max-retries` → `max-duration`), not a tangle of flags. `again` itself is JVM-only (it blocks on `Thread/sleep` and catches thrown exceptions), so Seon ports the pure combinators to the CLJS pod and pairs them with a native `async`/`await`, errors-as-values executor — the design is Lauri's, the inspiration explicit.

These licenses (EPL and MIT) are permissive; where Seon ports their code, the upstream copyright and permission notices travel with it.

## Quickstart

### Requirements

| Tool | Version | Why |
|------|---------|-----|
| **Java (JDK)** | **26** | The writer, Shadow compiler, test runners, and packaged image use one supported JVM version. `bin/_java-home-resolver` selects an installed JDK 26 or exits with an installation hint; `JAVA_HOME` is honored when it already points to JDK 26. |
| **Clojure CLI** | 1.12+ | Builds the CLJS pod and runs the database server. |
| **Babashka** (`bb`) | 1.x | Dev hooks, MCP servers, and datahike's build tasks. |
| **Node.js** + npm | 22+ (24 recommended) | The agent pod is a long-running Node process. |
| **Git** | 2.x | Resolves the datahike fork by `:git/sha`. |
| **An LLM API key** | — | The one required secret — e.g. `DEEPSEEK_API_KEY` (other providers configurable). |

Optional: **Caddy** 2.x (HTTPS reverse proxy), and the JVM dev seat
(`bin/run`, nREPL 7888 — for development/orchestration, not needed to run
agents). Platform: **macOS, Linux, and Windows via WSL** — the stack is all
cross-platform (JVM, Node, Clojure, Babashka) and `bin/seon` selects JDK 26 on
each. The `reference-code/*` git submodules are vendored dependency
source for reading when stuck — not needed to run, so a plain `git clone` is
fine.

### Run it

Run the core, talk to an agent, watch it work:

```bash
git clone https://github.com/seantempesta/seon && cd seon
npm install
cp .env.example .env      # the config surface — edit it for keys/provider/ports
export DEEPSEEK_API_KEY=sk-...   # (env vars override .env; either works)
bin/seon up               # full build → database server → agent pod
open http://localhost:7890/agents
```

`bin/seon up` builds and publishes the complete writer, CLJS, bootstrap, and
CSS artifact closure before reconciling the managed processes. The first pod
boot also reconciles the configured database facts. `bin/seon status` shows
live health; `bin/seon logs pod --follow` follows boot.
To verify a build end-to-end, `bin/test-cljs` runs the full suite in a
fresh process (~3 min).

`.env` (gitignored) is Seon's entire config surface — there is no config
file, every knob is an env var. `bin/seon` sources `.env` at boot with shell
env vars taking precedence. `.env.example` documents every setting
(`SEON_AI_PROVIDER`, `SOUL.md` path, ports, the `SEON_EMBED` flag, …); edit
`.env`, not `.env.example`.

Mint an agent on that page and talk to it: the left pane is the
conversation, the right pane is the agent's **canvas** — the thing
it is currently showing you, which it updates by writing code. Press
backtick (or the ⚙ button) for the debug overlay: the exact context
the agent sees each turn, because the prompt IS a REPL session over
the shared database.

You customize Seon with **a couple of files and some data, not source
edits**:

- **Identity → `SOUL.md`.** This is who your agent is. It is read
  **live on every turn**, so editing it takes effect on the agent's
  next reply — no restart, no rebuild, no transact. Drop an `AGENTS.md`
  beside it (the cross-tool standard for repo/work instructions) and it
  is read too; both are joined into the system prompt. Set
  `SEON_SOUL_FILE=<path>` to use a different primary identity file.
- **Standing instructions for all agents → `my.kb.system` rows.**
- **Everything your agents build** — schemas, functions, knowledge —
  lands in `my.*` namespaces that survive restarts.

`src/seon/` is the core — treat it like a runtime you installed, not a
library you fork. The universal agent mechanics (how the REPL-as-output
works) are baked into the core's system prompt, so you can freely edit
or even empty your identity files without breaking anything.

`bin/seon status|logs|down` operate the complete local system; database state
lives under `data/clusters/`. Use the scoped `bin/seon cluster reset <name>`
transition for a fresh database.

## Status

This is a research project in active development, not a product. Some parts work; others are designed but not built; honest detail is in [`docs/seon/vision/`](docs/seon/vision/) and [`docs/seon/_dashboard.md`](docs/seon/_dashboard.md).

| Milestone | Status |
|---|---|
| [M1: Reliable runtime](docs/seon/vision/m1-reliable-runtime.md) | partial — flow + pool + embedded Datahike on main `[JVM track — paused]` |
| [M2: Trustworthy data](docs/seon/vision/m2-trustworthy-data.md) | partial — validation gate live; some `:any` holdouts remain |
| [M3: Convention uniformity](docs/seon/vision/m3-convention-uniformity.md) | in progress — dev hook enforces on new code |
| [M4: Discoverable codebase](docs/seon/vision/m4-discoverable-codebase.md) | partial — renderer discovery and shape graph live |
| [M5: Observable system](docs/seon/vision/m5-observable-system.md) | partial — observatory and reactive SSE live |
| [M6: Eval pipeline](docs/seon/vision/m6-eval-pipeline.md) | prototyped — three working implementations; constraint-fn discovery pending |
| [M7: Namespace as living process](docs/seon/vision/m7-namespace-as-process.md) | prototyped — default step in production; reactive surface pending |
| [M8: Autonomous agents](docs/seon/vision/m8-autonomous-agents.md) | prototyped — single-agent loop works; inter-agent messaging pending |

Per-milestone evidence (which commits and branches back which status) is in [`docs/seon/lineage/milestone-prior-work.md`](docs/seon/lineage/milestone-prior-work.md).

### Where the work has landed — the CLJS pod track (2026-06)

The milestone table above frames the long arc on the original JVM runtime. Since it was written, the center of gravity moved to the **CLJS pod** — a long-running Node process that is the agent's actual home today — paired with the JVM database server over a Unix socket.

**Working today.**

- **A live ClojureScript REPL is the agent's entire surface — no fixed tool catalog.** The agent reads, computes, stores, and replies by evaluating Clojure forms against the shared database. Two properties make this tractable and are the newest load-bearing pieces: the agent's **whole context is a render of the database** (every message, eval, todo, namespace, and document is a *renderable* projected from datoms by its schema — one recursive walker, two views: text for the model, HTML for the human), and its **loop is a function of the database** (runnability is a single datom; a datahike tx-listener wakes the agent when a message lands; the stop policy is one `cond` over DB state). Context is *derived*, not accumulated — fix the underlying data and the surface heals itself, with nothing stored that needs clearing.
- **One writer, local reads.** The JVM database server is the sole authoritative Datahike writer and carries a Proximum HNSW vector index; the pod is a local read replica that executes functions locally and forwards writes over the socket. Reads are lazy database values, so **pod memory scales with the working set, not the corpus**.
- **Measured scale.** On an isolated benchmark store, point lookups and ref-joins stay sub-millisecond and KNN vector search stays ~5 ms at **100k entities / ~28k vectors**, with the heap at ~**150 MB** after GC and storage at ~**9.5 KB/entity**. Reads scale on a `log(n)` / lazy-paging curve; the one real cost is *bulk write* throughput (HNSW insertion + file commits), a one-time, cache-mitigated batch cost that never touches the read path. Concrete evidence the foundation holds for a six-figure-entity personal corpus. Full numbers: [`docs/prds/embeddings/db-scalability-benchmark-2026-06-25.md`](docs/prds/embeddings/db-scalability-benchmark-2026-06-25.md).
- **Inspect AI evaluations.** The `src-inspect-ai/` package drives the real
  agent boundary, restarts pods, scores durable facts and trajectories, and
  retains portable `.eval` artifacts. It is the sole model/agent evaluation
  control plane; focused CLJS tests own deterministic production behavior.

**Designed and proven against the real API, being wired in.**

- **Semantic search over everything the agent knows.** The embeddings infrastructure — pod search client, JVM write/query sides, the wire path, the Proximum index, and a predictive per-turn retrieval that renders breadcrumb pointers into context — is built and merged, gated by a single `SEON_EMBED` flag (on by default in `bin/seon`, but a graceful no-op without Vertex/`GEMINI_API_KEY` credentials). The multimodal layer is verified directly against Vertex `gemini-embedding-2`: text, image, audio, video, and PDF land in **one unified vector space** (cross-modal retrieval proven; Matryoshka dimension-truncation exact to the bit), and the model is governed so inputs are **not used to train Google's models**. A **content-addressed cache** (SHA-256 of content, folded with model/dim/task) means duplicate content is never re-embedded and an index rebuild never re-pays the API. What remains is wiring the Vertex routing, the multimodal ingest, and the cache/archive into the live pipeline, plus relevance tuning — designed and empirically proven, not yet wired end-to-end on the live pod. Specs: [`docs/prds/embeddings/`](docs/prds/embeddings/).

The immediate focus is converging the two tracks and activating semantic search on the live pod. The broader [v1 agent REPL specification](docs/prds/agent-runtime/v1.md) — session-survival, observability, program-graph discovery against an LLM (DeepSeek today, others later) — remains the reference target, with status in [`docs/prds/agent-runtime/STATUS.md`](docs/prds/agent-runtime/STATUS.md).

API is unstable. Direction may shift unilaterally as the underlying research evolves. Treat anything you build on this as your own to maintain.

## Reading the codebase

If you want to understand the project from the inside, in roughly this order:

1. [`docs/seon/vision/index.md`](docs/seon/vision/index.md) — the project thesis, eight milestones, the architectural pillars in long form.
2. [`docs/seon/_dashboard.md`](docs/seon/_dashboard.md) — system map. Component notes, concept notes, the current state of every piece.
3. [`docs/prds/agent-runtime/v1.md`](docs/prds/agent-runtime/v1.md) — what's being built next, in detail.
4. [`docs/seon/architecture/overview.md`](docs/seon/architecture/overview.md) — how the moving parts fit today.
5. [`AGENTS.md`](AGENTS.md) — the maintained orientation for anyone (human or AI) sitting down to write code in this repo. Claude reads the same authority through its `CLAUDE.md` symlink.

For the long-form research that fed into the current README and vision documents: [`docs/seon/vision/full-scope-synthesis-2026-05-23.md`](docs/seon/vision/full-scope-synthesis-2026-05-23.md), [`docs/seon/vision/biggest-ideas-2026-05-23.md`](docs/seon/vision/biggest-ideas-2026-05-23.md), [`docs/seon/vision/full-framing-found-2026-05-23.md`](docs/seon/vision/full-framing-found-2026-05-23.md).

For the eighteen-month predecessor chain in detail: [`docs/seon/lineage/predecessors.md`](docs/seon/lineage/predecessors.md).

## Lineage

Seon began as a `git mv` of `ml-options-trading` on 2025-12-13, but the architectural ideas were iterated across seventeen predecessor repositories over the eighteen months before that. Five of those are published as a dated prior-art spine:

| Repo | Period | Significance |
|---|---|---|
| [`seon-2024-10-xtdb-biff`](https://github.com/seantempesta/seon-2024-10-xtdb-biff) | Oct 2024 | First XTDB+Biff exploration; the `seon.repl` namespace pattern. |
| [`seon-2024-10-kit-migration`](https://github.com/seantempesta/seon-2024-10-kit-migration) | Oct 2024 – Jan 2025 | Kit-framework variant; 45 commits exploring agentic-runtime ideas in CLJS/Reagent. |
| [`seon-2025-02-architecture`](https://github.com/seantempesta/seon-2025-02-architecture) | Feb – Mar 2025 | Primary design realization. The ~72 KB README in this repo documents namespace-as-process, code-graph, schema-discovery, REPL-pipeline, and multi-agent isolation as one architecture, ten months before the current repo existed. |
| [`seon-2025-11-trading-domain`](https://github.com/seantempesta/seon-2025-11-trading-domain) | Nov – Dec 2025 | The immediate git ancestor of this repo. |
| [`seon`](https://github.com/seantempesta/seon) (this repo) | Dec 2025 – present | Consolidation. |

All five are RFC 3161 timestamped via FreeTSA on 2026-04-21 (tag [`v0.1-prior-art-2026-04-21`](https://github.com/seantempesta/seon/releases/tag/v0.1-prior-art-2026-04-21) here; corresponding tags on each predecessor). Twelve other predecessor repos remain local; their on-disk git timestamps and the inventory in this repo document them.

The full seventeen-repo timeline — with concept inflection points, technical detail, and the dependency lineage — is in [`docs/seon/lineage/predecessors.md`](docs/seon/lineage/predecessors.md). A by-concept view — namespace-as-process, schema-as-contract, REPL-as-interface, and the rest, each traced through the commits where it took shape — is in [`docs/seon/lineage/concepts-and-origins.md`](docs/seon/lineage/concepts-and-origins.md).

The lineage is here because the ideas all have predecessors and the predecessors deserve naming — mine and other people's. It also happens to make the "who built what, when" question easier to answer for anyone (including me) who needs to.

## License

Released under [AGPL-3.0](LICENSE). Using Seon unmodified as a dependency does not trigger §13's network-copyleft provision; modifying it and deploying it across a network does.

**Commercial / non-AGPL licensing is available** for users whose deployment doesn't fit AGPL terms. Contact Sean Tempesta Consulting LLC.

**Existing engagements with separate written agreements** (e.g., consulting clients with a Statement of Work referencing seon as pre-existing IP) operate under those agreements, not AGPL. The public license is the default; bilateral agreements override it.

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md). All contributions are accepted under AGPL-3.0 plus an inbound-license clause that grants Sean Tempesta Consulting LLC the right to relicense, which is what preserves the dual-licensing path above.

## Contact

For licensing inquiries, partnership questions, contracting opportunities, or anything that doesn't fit the issue tracker: sean@tempesta.io.

---

*This README is a living document. Last updated 2026-06-25.*
