---
type: research
status: active
tags: [research, agent]
---

# Memory Operation Design Space — what an evolving agent could invent/tune on datahike

## TL;DR

Three vendored memory baselines (mem0, letta/MemGPT, generative_agents) implement
the *same* store/retrieve/manage skeleton with very different mechanics. Mapping
their ops onto our datahike substrate (EAV, schema-as-data, datalog, real
`transact!/query/register!`) yields **6 design-space axes** an evolving agent can
move on: **schema shape, derived/indexed attrs, retrieval query, consolidation/dedup,
importance & decay, and reflection/summarization**. Of these, the three ops most
likely to *differentiate a good memory design from a bad one* — i.e. what a fitness
function will actually select on — are: **(1) the write-time consolidation decision
(ADD vs UPDATE vs DELETE vs NONE)**, **(2) the retrieval ranking function (what mix
of recency × importance × relevance × keyword, and the threshold)**, and **(3)
reflection / summarization (turning a flood of raw facts into a few queryable
higher-order rows)**. The first controls precision of the store, the second controls
what reaches context under a token budget, the third controls whether the store stays
small and answers questions raw facts can't.

A key structural finding for Seon: **all three baselines hand-roll a separate storage
engine** (a vector store, a flat block of text, a Python dict of ConceptNodes + an
inverted keyword index). On datahike, *every one of those is just a schema choice +
a datalog query* — there is no separate store to build. So the agent's design surface
is unusually clean: it `register!`s attributes, writes `transact!` rows, and writes
`query` fns. "Inventing a graph memory" or "inventing an importance-decay retriever"
is, for us, writing a few fns + schemas, not adopting a new backend. That is exactly
the regime where an evolutionary loop can explore.

---

## 1. mem0 — LLM-mediated fact store with hybrid retrieval

mem0's whole thesis: don't store raw turns, **extract atomic facts**, **dedup/merge
them against existing memory via an LLM**, and **retrieve with a hybrid
semantic+keyword+entity ranker**. The store is a vector DB + a SQLite history table +
an optional graph layer.

### Op inventory (file:line)

- **Public surface** (`mem0/memory/main.py`): `add` (`:716`/`:2352`), `search`
  (`:1326`/`:2947`), `get_all` (`:1202`/`:2823`), `update` (`:1762`/`:3380`),
  `delete` (`:1803`/`:3422`), `delete_all` (`:1824`/`:3443`), `history` (`:1866`/`:3497`),
  `reset` (`:2048`/`:3701`). (Two copies — sync + async.)
- **Extraction (store path)** — `_add_to_vector_store` (`main.py:830`). The V3 pipeline:
  Phase 0 gather last-10 messages (`:871`); Phase 1 retrieve top-10 existing memories by
  vector similarity (`:877`); Phase 2 **single LLM call** extracts atomic facts via
  `ADDITIVE_EXTRACTION_PROMPT` (`:891`, prompt at `configs/prompts.py:468`); Phase 3 batch
  embed (`:938`); Phase 5 **hash dedup** — `hashlib.md5(text)` against `existing_hashes`
  and within-batch `seen_hashes` (`:952`-`:971`); Phase 6 batch persist (`:992`); Phase 7
  **entity linking** — extract entities, global-dedup, embed, link memory↔entity (`:1033`+).
- **Consolidation decision (the core differentiator)** — `DEFAULT_UPDATE_MEMORY_PROMPT`
  (`configs/prompts.py:176`). The LLM is shown old memories + new facts and emits per-fact
  `event` ∈ {`ADD`, `UPDATE`, `DELETE`, `NONE`} with `old_memory` when updating
  (`:182`-`:185`, examples `:189`-`:320`). Driven by `get_update_memory_messages`
  (`:406`). NOTE: V3 default path is **ADD-only** (`ADDITIVE_EXTRACTION_PROMPT`,
  `:468`) with `linked_memory_ids` for soft-merge (`:513`); the four-way UPDATE prompt
  is the v1 mechanism still present.
- **Retrieval ranking** — `_search_vector_store` (`main.py:1575`). Hybrid: semantic
  over-fetch `max(limit*4, 60)` (`:1588`), **BM25 keyword search** normalized via a
  sigmoid `(midpoint, steepness)` (`:1594`-`:1606`), **entity boosts** (`:1608`-`:1611`),
  then a candidate merge/rerank with a `threshold` (default `0.1`, `:1575`).
- **History/audit** — every write appends a row to a SQLite history table with
  `old_memory`/`new_memory`/`event` (`:1011`-`:1031`); `history()` reads it back.
- **Per-memory CRUD** — `_create_memory` (`:1881`), `_update_memory` (re-embeds, bumps
  `updated_at`, writes UPDATE history, `:1952`), `_delete_memory` (`:2018`).

### What's hand-built that could be evolved
The extraction prompt, the dedup strategy (hash-exact vs LLM-semantic vs vector-threshold),
the ADD-only-vs-four-way decision, the hybrid ranker weights (semantic vs BM25 vs entity),
the over-fetch factor, and the `threshold`. All are knobs or swappable fns.

---

## 2. letta (ex-MemGPT) — agent edits its own memory via tools

letta's thesis: **the agent manages its own memory** through a tool interface, across a
**hierarchy**: *core* (always in-context, editable text blocks), *recall* (conversation
history, searchable), *archival* (unbounded long-term, semantic). Paging between tiers is
the OS metaphor.

### Op inventory (file:line — `letta/functions/function_sets/base.py`)

- **Core memory (in-context, self-edited blocks)**:
  - `core_memory_append(label, content)` (`:233`) — appends a line to a labelled block.
  - `core_memory_replace(label, old_content, new_content)` (`:250`) — exact-string replace;
    raises if `old_content` absent.
  - `memory_replace` / `memory_insert` / `memory_rethink` / `memory_apply_patch` (`:309`,
    `:392`, `:470`, `:436`) — the v2 "sleep-time" set: precise str-replace, line-insert,
    full rewrite, and a **unified-diff patch** that can Add/Update/Delete/Move blocks.
  - `rethink_memory(new_memory, target_block_label)` (`:289`) — rewrite a block,
    integrating new info, dropping outdated/inconsistent lines.
  - `memory_finish_edits()` (`:587`) — signal done.
  - The catch-all `memory(command, ...)` tool (`:9`) — sub-commands `create`/`str_replace`/
    `insert`/`delete`/`rename` over `/memories/<path>` block paths.
- **Recall (conversation history)** — `conversation_search(query, roles, limit, start_date,
  end_date)` (`:84`) — **hybrid text + semantic** over prior messages, with role + date
  filters; delegates to `message_manager.list_messages_for_agent`.
- **Archival (long-term semantic)** — `archival_memory_insert(content, tags)` (`:171`) and
  `archival_memory_search(query, tags, tag_match_mode, top_k, start/end_datetime)` (`:194`) —
  store self-contained facts with tags; search by semantic similarity, filter by tags
  (any/all) + time.
- **Hierarchy schema** (`letta/schemas/memory.py`, `schemas/block.py`): a `Block` has
  `value`, `limit` (char budget, default `CORE_MEMORY_BLOCK_CHAR_LIMIT`), `label`,
  `read_only`, `description` (`block.py:18`-`:39`). The in-context `Memory` renders blocks
  with `chars_current` / `chars_limit` so the agent *sees its own budget pressure*
  (`memory.py:149`-`:192`). `ContextWindowOverview` reports `num_archival_memory`,
  `num_recall_memory`, `num_tokens_core_memory` (`memory.py:23`-`:45`) — the paging signal.

### What's hand-built that could be evolved
The **tier boundary policy** (when does a fact graduate from core→archival? letta leaves
it to the agent + the char `limit`). The block label taxonomy. The edit granularity
(append vs str-replace vs full-rethink vs diff-patch). The "rethink → finish_edits"
consolidation loop. All are agent-authored behaviors, not engine code — directly
analogous to a Seon agent writing its own verbs.

---

## 3. generative_agents — memory stream with recency × importance × relevance + reflection

Park et al.'s thesis: a flat **memory stream** of `ConceptNode`s (events/thoughts/chats),
retrieved by a **weighted sum of three normalized scores**, plus **reflection** that
synthesizes high-importance memories into higher-order "thought" nodes.

### Op inventory (file:line)

- **Node structure** (`memory_structures/associative_memory.py:20`-`:43`): a `ConceptNode`
  carries `node_type` (event/thought/chat), `created`/`expiration`/`last_accessed`,
  an SPO triple `(subject, predicate, object)` (`:35`-`:37`), `description`, `embedding_key`,
  **`poignancy`** (importance, `:41`), and `keywords` (`:42`).
- **Store** — `add_event` (`:153`), `add_thought` (`:199`), `add_chat` (`:243`). Each appends
  to a `seq_*` list, registers the node in `id_to_node`, AND **builds an inverted keyword
  index** `kw_to_event`/`kw_to_thought` + maintains `kw_strength_*` counts (a frequency
  signal) (`:178`-`:192`, `:222`-`:236`). Thoughts get a 30-day `expiration` (`reflect.py:124`).
- **Retrieval scoring** (`cognitive_modules/retrieve.py`):
  - `extract_recency` — `recency_decay ** i` over chronologically-sorted nodes (`:145`).
  - `extract_importance` — reads each node's `poignancy` (`:168`-`:170`).
  - `extract_relevance` — cosine sim of focal-point embedding vs node embedding (`:189`-`:194`).
  - `new_retrieve` — normalize each component to [0,1], combine as
    `recency_w·rec·gw[0] + relevance_w·rel·gw[1] + importance_w·imp·gw[2]`, with
    `gw=[0.5,3,2]` (`:230`-`:249`), take top-`n_count` (`:262`), and **bump `last_accessed`**
    on retrieved nodes (`:266`-`:267`) — retrieval itself refreshes recency. The comment at
    `:241` flags the weights as a candidate for *learned* tuning ("perhaps through an RL-like
    process").
  - Keyword path: `retrieve_relevant_events/thoughts` (`associative_memory.py:305`-`:326`)
    pull candidates from the inverted index by SPO keyword match.
- **Importance assignment** — `generate_poig_score` (`reflect.py:73`) asks an LLM for a
  1–10 poignancy score per event/thought/chat (idle → 1, `:76`-`:83`).
- **Reflection** (`cognitive_modules/reflect.py`):
  - `reflection_trigger` (`:135`) — fires when an **accumulated importance counter**
    (`importance_trigger_curr`) crosses a threshold (`:152`); `reset_reflection_counter`
    refills it (`:158`).
  - `run_reflect` (`:99`) — `generate_focal_points` from the N most-recent salient nodes
    (`:21`), retrieve relevant nodes per focal point, `generate_insights_and_evidence`
    (LLM produces insight + evidence node-ids, `:38`), then `add_thought` the synthesized
    insight back into the stream **with evidence links** (`:121`-`:132`). Reflection is
    recursive — thoughts can reflect on thoughts (the `depth` field, `associative_memory.py:29`).

### What's hand-built that could be evolved
The three retrieval weights + the recency decay base + the normalization scheme; the
poignancy scale and prompt; the reflection trigger threshold and focal-point selection;
the keyword-strength accumulator; the SPO-triple representation itself.

---

## DESIGN-SPACE TABLE — axes an evolving agent could move on (datahike substrate)

Substrate note: on datahike the agent has `schema/register!` (define attributes — the
ONLY way to make a value queryable), `db/transact!` (write rows), `db/query` (datalog),
`db/pull`/`db/entity`, lookup-refs/identity, refs+components, CAS, and as-of/since history
**for free**. There is no separate vector/keyword/graph store to build; each baseline's
engine collapses into "schema + query". With `SEON_EMBED`, semantic search is the
Proximum HNSW index over an embedding attr.

| # | Axis | Structural (invent fns/schemas) vs Tunable (knob) | Concrete datahike example | Baseline it generalizes |
|---|------|---------------------------------------------------|---------------------------|--------------------------|
| 1 | **Schema shape** — what a "memory" *is* (attrs + connections, NOT a kind) | **Structural** — agent `register!`s the attribute set; the presence of attrs *is* the type | `(schema/register! :my.mem/fact :string)` `(schema/register! :my.mem/subject :keyword)` `(schema/register! :my.mem/evidence [:vector :seon.db/ref])` → an SPO-triple fact with evidence links is just these rows. A flat text block (letta) is one `:my.mem/block-text` attr; a graph memory (mem0) is `:my.mem/entity` refs. | all three (ConceptNode, Block, fact-row) |
| 2 | **Derived / indexed attrs** — what's precomputed for fast access | **Tunable→Structural** — adding an index is a schema property; adding a derived rollup is a new fn | `:db.unique/identity` on `:my.mem/fact-hash` for exact dedup; an embedding attr for HNSW recall; a derived `:my.mem/keyword` cardinality-many attr replacing the hand-built inverted index (datalog scans the attr index for free — no `kw_to_event` dict needed) | mem0 hash + entity index; gen_agents `kw_to_*` |
| 3 | **Retrieval query** — what reaches context under the token budget | **Structural** (the ranking fn) wrapping **Tunable** weights/threshold/limit | A `recall` fn: datalog-pull candidate facts, score each `(+ (* w-rec (decay (- now created))) (* w-imp poignancy) (* w-rel (cos query emb)))`, threshold + top-k. Weights `[w-rec w-imp w-rel]`, decay base, threshold, over-fetch factor are config rows the loop can mutate. | gen_agents weighted sum; mem0 hybrid+threshold; letta hybrid+date filter |
| 4 | **Consolidation / dedup** — write-time precision of the store | **Structural** (the decision fn) — could be hash-exact, vector-threshold, OR an LLM ADD/UPDATE/DELETE/NONE pass | On `add`: query existing facts by `:my.mem/subject`; if `:my.mem/fact-hash` collides → NONE; if same-subject-different-value → `[:db/retract …] + new` (UPDATE) or CAS; else ADD. The agent can write this as pure datalog (cheap) or route through the LLM (mem0's prompt). Upsert via lookup-ref is the native primitive. | mem0 UPDATE_MEMORY (ADD/UPDATE/DELETE/NONE) |
| 5 | **Importance & decay** — salience and forgetting | **Tunable** (the score + decay base) with a **Structural** assignment policy | `:my.mem/poignancy :int` written at store time (LLM-scored or rule-scored); decay is *derived at query time* `(Math/pow base age)` — NOT stored (Seon's derive-don't-store rule). `:my.mem/expiration :inst` + a query filter implements forgetting with zero GC. `:my.mem/last-accessed` bumped on recall = access-driven recency. | gen_agents poignancy + recency_decay + expiration |
| 6 | **Reflection / summarization** — turning many raw rows into few higher-order rows | **Structural** — a periodic fn that queries salient rows, LLM-synthesizes insights, transacts them back **with evidence refs** + a `depth` | A `reflect!` fn: query top-importance facts since last reflection → LLM insight + evidence node-ids → `(db/transact! [{:my.mem/insight "…" :my.mem/depth 1 :my.mem/evidence [ref ref]}])`. Trigger = a derived query (sum of new poignancy > threshold), not a stored counter. Recursive: insights are queryable as evidence for deeper insights. | gen_agents reflection; letta rethink/finish_edits |
| (7) | **Tier / paging policy** — what stays "hot" vs archived | **Structural** policy fn, mostly **Tunable** budget | No physical tiers needed on datahike: "core" = the subset a render-fn selects into context each turn (a query), "archival" = everything else (still one query away). The `limit`/budget is a config knob; graduation core→archival is just *which query the context renderer runs*. This is Seon's reactive-context model already. | letta core/recall/archival hierarchy |

### Structural vs tunable, summarized
- **Structural** (agent invents fns/schemas — the big moves): schema shape (#1), the
  ranking fn (#3), the consolidation decision (#4), the reflection fn (#6), the tier policy (#7).
- **Tunable** (knobs the loop can mutate without new code): retrieval weights / decay base /
  threshold / top-k / over-fetch (#3, #5), index choices (#2), char/token budget (#7).
- Datahike makes #1/#2 unusually cheap: where the baselines *build a store*, the agent just
  *picks a schema* — and the inverted index, the history table, the as-of time-travel come free.

---

## The 2–3 ops fitness will actually select on

A memory design is good iff, on a store-then-recall-across-turns/restarts task, the agent
*retrieves the right fact later, cheaply, without the store bloating*. Three ops dominate
that outcome:

1. **Write-time consolidation (ADD vs UPDATE vs DELETE vs NONE)** — axis #4. This is the
   single biggest precision lever and the one mem0 spends an entire LLM call on. Get it wrong
   one way (always-ADD) and the store fills with near-duplicates that drown recall and blow
   the token budget; get it wrong the other way (over-merge/over-NONE) and you silently lose
   facts. A bad design here fails the recall task even with a perfect retriever. On datahike
   the cheap-but-strong default is **upsert-by-identity + hash-dedup**, with semantic
   UPDATE/DELETE as the evolvable upgrade — so the fitness gradient from "always ADD" to
   "dedup + supersede" is exactly the kind of move an evolutionary loop can climb.

2. **Retrieval ranking + threshold (the recency × importance × relevance × keyword mix)** —
   axis #3. This decides *what reaches context*, which is the whole point under a token cap.
   gen_agents' own author flags the weights as something to *learn* (`retrieve.py:241`),
   mem0 hand-tunes a hybrid + a `0.1` threshold, letta adds date/role/tag filters. The fitness
   selects hard on: did the answer-bearing fact make the top-k? Pure-recency, pure-semantic,
   and pure-keyword each fail different queries; the differentiator is the *combination and
   the cutoff*. This is mostly tunable (great for a search loop) but the choice of *which
   signals exist* (does the schema even carry poignancy / keywords / embeddings?) is structural.

3. **Reflection / summarization** — axis #6. The differentiator between a memory that merely
   *stores* and one that *answers questions the raw facts can't* (e.g. "what does the user
   care about?" — never stated, only inferable). It's also the main thing that keeps the store
   small over long horizons (few insight rows standing in for many raw rows). A design without
   reflection plateaus: recall stays literal and the store grows monotonically. With it
   (evidence-linked, recursive, triggered by derived importance), the store compounds. This is
   the highest-variance op — easy to get net-negative (hallucinated insights pollute recall),
   so fitness will select sharply on *grounded, evidence-linked* reflection vs free-form summary.

Recency/importance/decay (#5) and schema/index (#1/#2) matter but are **enablers** — they set
the table for the three ops above. If forced to rank: **consolidation > retrieval ranking >
reflection** for a basic store-then-recall fitness; reflection rises to the top as the horizon
(turns/restarts) and the abstraction demanded by the question grow.

---

## Entry points (read for depth)

- mem0 store+dedup: `reference-code/mem0/mem0/memory/main.py:830` (`_add_to_vector_store`),
  ranking `:1575`; consolidation prompt `reference-code/mem0/mem0/configs/prompts.py:176` & `:468`.
- letta self-edit tools: `reference-code/letta/letta/functions/function_sets/base.py`
  (whole file); hierarchy schema `reference-code/letta/letta/schemas/memory.py` + `schemas/block.py`.
- gen_agents stream+reflection: `reference-code/generative_agents/reverie/backend_server/persona/`
  — `memory_structures/associative_memory.py`, `cognitive_modules/retrieve.py`,
  `cognitive_modules/reflect.py`.
- Seon substrate: the `/datahike` + `/data-modeling` skills; `docs/seon/concepts/reactive-context.md`
  (derive-don't-store, which collapses the tier/decay axes); `my.kb` (the agent's worked
  memory manual).
