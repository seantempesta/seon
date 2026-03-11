# PRD: Agent REPL Interface — Composable Context & REPL-Only Development

## Status: Design

## Summary

Agents develop Clojure code exclusively through REPL eval — no file editing, no line numbers, no `clojure_replace`. The `*ctx*` atom is the agent's entire world: schemas, functions, tests, history, validation issues. The agent's AI context is **rendered from `*ctx*` data** using composable, schema-discovered renderers — the same discovery mechanism used for everything else.

This PRD supersedes:
- `super-repl/prd.md` Phase 3 (graduation), Phase 5 (Dynamic Context + MCP Cockpit)
- `spec-driven-rendering/prd.md` Phase 4 (Agent Context Cockpit)
- `render-pipeline/prd.md` Phase 6 (Flow Channels for AI clients)

## Motivation

Current agent development uses Claude Code's `Edit`/`Write` tools with line numbers and file paths. This is fragile:
- Line numbers shift after edits, causing match failures
- Agents must reason about file structure, not just code
- The `clojure_replace` MCP tool does s-expression matching but still operates on files
- No validation gate between "agent wrote code" and "code is approved"
- Context is a growing scroll of messages, not a live cockpit

The REPL-first approach eliminates all of this. Agents eval forms. The system validates, tracks state, and persists when ready. The `*ctx*` atom is both the state store and the context source.

---

## The `*ctx*` Shape

Every key is a namespaced keyword with a registered Malli schema. The shape of the data determines how it renders — no separate rendering configuration.

```clojure
{;; === Identity (reserved, immutable) ===
 :seon.agent/namespace   'seon.trading.signals    ; symbol
 :seon.agent/session-id  "a13b"                    ; string
 :seon.agent/started-at  #inst "2026-03-06T..."    ; inst

 ;; === Namespace Inventory (vectors of maps -> tables) ===
 :seon.repl/schemas
 [{:seon.repl/key         :seon.trading.signals/ticker
   :seon.repl/definition  "[:string {:min 1 :max 10}]"
   :seon.repl/status      :live           ; :live | :persisted
   :seon.repl/consumers   2               ; count of functions using it
   :seon.repl/defined-at  #inst "..."}]

 :seon.repl/functions
 [{:seon.repl/name        :seon.trading.signals/analyze
   :seon.repl/schema      "[:=> [:cat ::analyze-req] ::analyze-resp]"
   :seon.repl/status      :live           ; :live | :persisted
   :seon.repl/tested?     false
   :seon.repl/defined-at  #inst "..."}]

 :seon.repl/tests
 [{:seon.repl/test-name   :seon.trading.signals-test/value-test
   :seon.repl/result      :pass           ; :pass | :fail | :error | :pending
   :seon.repl/tested-fns  [:seon.trading.signals/value]
   :seon.repl/ran-at      #inst "..."}]

 :seon.repl/requires
 [{:seon.repl/ns  "seon.schema"   :seon.repl/as  "schema"}
  {:seon.repl/ns  "seon.db"       :seon.repl/as  "db"}]

 ;; === REPL History (vector -> ordered list, most recent last) ===
 :seon.repl/history
 [{:seon.repl/form    "(schema/register! ::ticker [:string {:min 1}])"
   :seon.repl/result  ":seon.trading.signals/ticker"
   :seon.repl/at      #inst "..."}]

 ;; === Validation Issues (vector of maps -> table) ===
 :seon.repl/issues
 [{:seon.repl/target   :seon.trading.signals/analyze
   :seon.repl/issue    :no-tests
   :seon.repl/message  "No test coverage - cannot be persisted"}]

 ;; === Domain Data (agent's own workspace) ===
 :seon.trading.signals/market-data   {:AAPL 150.0 :GOOGL 140.0}
}
```

### Status Model

Two states: **`:live`** and **`:persisted`**.

- **`:live`** — eval'd in this JVM, exists in memory. Normal REPL state. Other agents cannot see it.
- **`:persisted`** — validated, written to `.clj` file, registered in Datalevin graph. Discoverable by other agents.

Status is **recomputed after every eval**, not manually set:
- Schema with 0 consumers -> stays `:live` (orphaned)
- Function without test coverage -> stays `:live` (cannot be persisted)
- Function with passing tests + all schemas concrete -> eligible for `:persisted`
- Transition to `:persisted` is **explicit**: agent calls `(seon/persist!)`

### Orphan Cleanup

- Schema with 0 function consumers -> orphan, dropped on persist
- Require with 0 references in any function/schema -> orphan, omitted from `(ns ...)` form
- Function with 0 test coverage -> cannot be persisted

The `*ctx*` always reflects truth. `:seon.repl/issues` surfaces problems persistently — the agent sees them every context refresh.

---

## Composable AI Renderers

### The Core Insight

No monolithic `for-ai` function. Instead, normal Clojure functions that:
1. Accept a map with specific namespaced keys
2. Return `{:seon.render/ai "..."}` (a string)
3. Are discovered by the existing specificity-based resolution in `seon.render/find-renderer`

The most specific renderer (most matching required input keys) wins. Generic fallbacks handle anything without a specific renderer.

### Why XML Section Delimiters

Research confirms XML is optimal for AI context:

- **Claude is specifically trained on XML tags** — Anthropic recommends `<section>` delimiters for structured prompts ([Anthropic docs](https://docs.anthropic.com/en/docs/build-with-claude/prompt-engineering/use-xml-tags))
- **Clear parse boundaries** — XML tags mark where sections begin and end unambiguously, unlike markdown headers which rely on visual hierarchy ([Medium: XML vs Markdown](https://medium.com/@isaiahdupree33/optimal-prompt-formats-for-llms-xml-vs-markdown-performance-insights-cef650b856db))
- **Nestable** — sections within sections compose naturally
- **Cross-model compatible** — works well for Gemini and GPT too ([SSW Rules](https://www.ssw.com.au/rules/ai-prompt-xml))
- **Context pollution prevention** — XML prevents different sections from "contaminating" each other ([Anthropic: Context Engineering](https://www.anthropic.com/engineering/effective-context-engineering-for-ai-agents))

Trade-off: XML costs ~15-80% more tokens than markdown for the same content. For structured agent context, the clarity benefit outweighs the cost.

### Renderer Hierarchy

```
Level 0: Type-based fallbacks (any map, any vector, any scalar)
Level 1: Key-specific renderers (renders {:seon.repl/schemas [...]} specifically)
Level 2: Multi-key renderers (renders {:seon.repl/schemas [...] :seon.repl/functions [...]} together)
Level 3: Full ctx renderer (renders the entire *ctx* map as one unit)
```

Higher levels win via specificity (more matching required keys). All levels are normal functions with `:malli/schema` metadata, discovered by the same `seon.render/find-renderer` algorithm described in `spec-driven-rendering/prd.md`.

### Example: Schemas Section Renderer

```clojure
(ns seon.repl.render
  (:require [clojure.string :as str]
            [seon.schema :as schema]))

;; Schema for input
(schema/register! ::schemas-section-request
  [:map [:seon.repl/schemas [:vector [:map
    [:seon.repl/key :keyword]
    [:seon.repl/definition :string]
    [:seon.repl/status [:enum :live :persisted]]
    [:seon.repl/consumers :int]
    [:seon.repl/defined-at :inst]]]]])

;; Schema for output
(schema/register! ::ai-render-response
  [:map [:seon.render/ai :string]])

(defn schemas-ai
  "Render schemas section for AI context."
  {:malli/schema [:=> [:cat ::schemas-section-request] ::ai-render-response]}
  [{:seon.repl/keys [schemas]}]
  {:seon.render/ai
   (str "<schemas count=\"" (count schemas) "\">\n"
        (str/join "\n"
          (map (fn [{:seon.repl/keys [key definition status consumers]}]
                 (str "  " key " " status
                      " (" consumers " consumers) "
                      definition))
               schemas))
        "\n</schemas>")})
```

### Example: Functions Section Renderer

```clojure
(defn functions-ai
  "Render functions section for AI context."
  {:malli/schema [:=> [:cat ::functions-section-request] ::ai-render-response]}
  [{:seon.repl/keys [functions]}]
  {:seon.render/ai
   (str "<functions count=\"" (count functions) "\">\n"
        (str/join "\n"
          (map (fn [{:seon.repl/keys [name schema status tested?]}]
                 (str "  " name
                      " [" status (when-not tested? " UNTESTED") "]"
                      "\n    " schema))
               functions))
        "\n</functions>")})
```

### Example: Generic Fallbacks

```clojure
;; Generic map renderer — 0 required keys, always matches, always loses to specific
(defn map-ai-fallback
  "Fallback: render any map as indented key-value pairs."
  {:malli/schema [:=> [:cat :map] ::ai-render-response]}
  [m]
  {:seon.render/ai
   (str/join "\n"
     (map (fn [[k v]] (str "  " k ": " (pr-str v)))
          (sort-by key m)))})

;; Generic vector-of-maps renderer
;; Discovered when value is a vector of maps and no specific renderer exists
(defn vector-of-maps-ai-fallback
  "Fallback: render vector of maps as a text table."
  {:malli/schema [:=> [:cat [:vector :map]] ::ai-render-response]}
  [rows]
  (let [all-keys (distinct (mapcat keys rows))
        header (str/join " | " (map name all-keys))
        sep (str/join " | " (repeat (count all-keys) "---"))
        body (map (fn [row]
                    (str/join " | " (map #(str (get row %)) all-keys)))
                  rows)]
    {:seon.render/ai
     (str header "\n" sep "\n" (str/join "\n" body))}))
```

### Composition: The Ctx Walk

The full ctx render is **not a special function**. It's a generic map renderer that, for each key-value pair, finds the most specific renderer and calls it:

```clojure
(defn render-ctx-ai
  "Compose AI context from *ctx* by rendering each key with the best renderer."
  [ctx-value]
  (let [identity-keys #{:seon.agent/namespace :seon.agent/session-id :seon.agent/started-at}
        header (str "<agent ns=\"" (:seon.agent/namespace ctx-value)
                    "\" session=\"" (:seon.agent/session-id ctx-value) "\">\n")
        sections (->> (dissoc ctx-value :seon.agent/namespace :seon.agent/session-id :seon.agent/started-at)
                      (map (fn [[k v]]
                             ;; Find renderer for {k v} — specificity resolution
                             (let [data {k v}
                                   rendered (render/render data :ai)]
                               rendered)))
                      (str/join "\n\n"))]
    (str header sections "\n</agent>")))
```

This renderer itself can be overridden — if an agent writes a more specific function that takes the full ctx shape, it wins. Turtles all the way down.

### Example Output

```xml
<agent ns="seon.trading.signals" session="a13b">

<schemas count="2">
  :seon.trading.signals/ticker persisted (3 consumers) [:string {:min 1 :max 10}]
  :seon.trading.signals/signal-type live (1 consumers) [:enum :buy :sell :hold]
</schemas>

<functions count="2">
  :seon.trading.signals/analyze [live UNTESTED]
    [:=> [:cat ::analyze-request] ::analyze-response]
  :seon.trading.signals/value [persisted]
    [:=> [:cat ::value-request] ::value-response]
</functions>

<tests count="1">
  value-test PASS covers: [value]
</tests>

<issues count="1">
  analyze: No test coverage - cannot be persisted
</issues>

<requires>
  seon.schema (as schema), seon.db (as db)
</requires>

<history recent="3">
  1. (schema/register! ::ticker ...) -> :ok
  2. (defn value ...) -> :ok
  3. (deftest value-test ...) -> :pass
</history>

</agent>
```

---

## REPL Eval Pipeline

### Agent Workflow

```
1. (require '[seon.schema :as schema])
   -> Interceptor: loads namespace, updates *ctx* :seon.repl/requires

2. (schema/register! ::ticker [:string {:min 1}])
   -> Interceptor validates: no :any, no [:maybe], Nippy-serializable, generator works
   -> Pass: schema registered, *ctx* updated (status :live, 0 consumers)
   -> Fail: rejected with structured error, *ctx* :seon.repl/issues updated

3. (defn analyze [{::keys [ticker]}] ...)
   -> Interceptor validates:
      - :malli/schema metadata present
      - Schema concrete (no :any)
      - Map-in/map-out pattern
      - All referenced schemas registered
   -> Pass: function defined in JVM, *ctx* updated (status :live, tested? false)
   -> Fail: rejected, issues updated

4. (deftest analyze-test ...)
   -> Interceptor runs test, records which functions called via instrumentation
   -> *ctx* :seon.repl/tests updated with results
   -> Functions exercised get :tested? true

5. (seon/persist!)
   -> Checks: all functions tested? schemas concrete? no :any?
   -> Generates (ns ...) form from *ctx* :seon.repl/requires
   -> Writes .clj file: ns form + schema registrations + functions + tests
   -> Updates Datalevin graph (seon.graph.ingest)
   -> Status transitions :live -> :persisted
   -> Other agents can now discover these functions via graph queries
```

### Removing Things

Standard Clojure, intercepted:

| Action | Clojure Form | Interceptor Effect |
|--------|-------------|-------------------|
| Remove function | `(ns-unmap *ns* 'old-fn)` | Remove from graph, update `*ctx*`, mark file for regen |
| Remove schema | `(schema/unregister! ::old-key)` | Reject if consumers > 0, else remove from registry + `*ctx*` |
| Remove require | `(ns-unalias *ns* 'alias)` | Update `*ctx*` :seon.repl/requires |
| Redefine function | Just `(defn ...)` again | Clojure naturally overwrites. Interceptor re-validates. |
| Redefine schema | Just `(schema/register! ...)` again | Overwrites in registry. Interceptor re-validates. |

### `schema/unregister!`

New function needed in `seon.schema`:

```clojure
(defn unregister!
  "Remove a schema from the global registry.
   Refuses if any function currently depends on this schema."
  [k]
  (swap! *schemas dissoc k)
  k)
```

The REPL interceptor adds the dependency check before allowing the unregister.

---

## Test Harness

### Agent Test Environment

Each agent gets:
- **Isolated JVM** — from `seon.flow.pool` (already built)
- **`*ctx*` atom** — injected via `intern` + `.setDynamic` (already built in `agent_runner.clj`, `ns/lifecycle.clj`)
- **`*conn*`** — Datalevin connection for test data
- **Fresh test DB** — temporary Datalevin for each test run, using existing `with-temp-conn` pattern from `test/seon/test_utils.clj`

### Test Discovery

When `(deftest ...)` is eval'd, the interceptor:
1. Runs the test immediately
2. Records which instrumented functions were called (via Malli instrumentation hooks)
3. Updates `*ctx*` :seon.repl/tests with results and `:seon.repl/tested-fns`
4. Updates `:tested?` on matching functions in `:seon.repl/functions`

This is more reliable than static analysis of the test body — instrumentation catches indirect calls too.

### Validated Boundaries

Both `*ctx*` and `*conn*` validate all inputs:
- `*ctx*` already has validators (namespaced keys, registered schemas) — see `seon.ctx` lines 360-451
- `db/transact!` already validates via Malli (Phase 1 validation gate) — see `seon.db`
- Agents get helpful rejection messages, not cryptic errors

---

## Namespace Management

### Requiring New Namespaces

```clojure
;; Agent evals this
(require '[seon.health.workout :as workout])
```

The interceptor:
1. Loads the namespace (normal Clojure `require`)
2. Updates `*ctx*` :seon.repl/requires
3. The required namespace's schemas and functions become available
4. On `(seon/persist!)`, the generated `(ns ...)` form includes this require

### `(ns ...)` Form Generation

The `(ns ...)` form is **generated from `*ctx*` state**, never edited by the agent directly:

```clojure
(defn generate-ns-form [ctx-value]
  (let [ns-sym (:seon.agent/namespace ctx-value)
        requires (mapv (fn [{:seon.repl/keys [ns as]}]
                         [(symbol ns) :as (symbol as)])
                       (:seon.repl/requires ctx-value))]
    `(~'ns ~ns-sym
       (:require ~@requires))))
```

### External Dependencies

Constrained for now: agents can only require namespaces already on the classpath. New Maven/git deps require orchestrator approval + JVM restart. This is a reasonable constraint — "add constraints to simplify, relax later."

---

## Rendering Architecture

### No Special Cases

The rendering system has one mechanism: specificity-based function discovery. There is no "for-ai" function, no "render-default-page" special case. Just functions:

1. **Data exists** — a map with namespaced keys
2. **System queries** — "what function accepts these keys and returns `:seon.render/ai`?"
3. **Most specific wins** — function with most matching required input keys
4. **Fallback** — generic type-based rendering (map->kv, vector-of-maps->table, etc.)

### How This Replaces Current Code

| Current | New |
|---------|-----|
| `seon.render/for-ai` (monolithic recursive function) | Composable per-key renderers discovered by specificity |
| `seon.render/for-html` (monolithic recursive function) | Same composable approach (future, out of scope for this PRD) |
| `seon.render.default-page/render-default-page` (hardcoded layout) | Generic ctx renderer + per-section overrides |
| `seon.graph.context/build-for-namespace` (hardcoded text format) | AI renderer for namespace graph data |

### Override by Writing Functions

An agent that wants different context rendering writes a function:

```clojure
;; Agent writes this in their JVM
(defn my-compact-schemas
  "I prefer one-line schema summaries."
  {:malli/schema [:=> [:cat ::schemas-section-request] ::ai-render-response]}
  [{:seon.repl/keys [schemas]}]
  {:seon.render/ai
   (str "<schemas>" (str/join ", " (map :seon.repl/key schemas)) "</schemas>")})
```

Scanner picks it up. Newest + most specific wins. Session ends, function gone, default wins again. No configuration. No cleanup. This is the "Agent Context Cockpit" from `spec-driven-rendering/prd.md` Phase 4 — realized through the same mechanism as everything else.

---

## Phases

### Phase 1: `*ctx*` Schema + REPL Interceptor

Define Malli schemas for all `:seon.repl/*` keys. Build the REPL interceptor that updates `*ctx*` after every eval. No rendering yet — just the data model.

**Files**: `src/seon/repl/ctx.clj` (schemas), `src/seon/repl/interceptor.clj` (eval pipeline)

**Depends on**: `seon.schema`, `seon.ctx`, `seon.flow.pool` (all exist)

### Phase 2: AI Section Renderers

Write the composable AI renderers: schemas, functions, tests, issues, history, requires. Each is a normal function with `:malli/schema`. Write the generic fallbacks (map, vector-of-maps, scalar).

**Files**: `src/seon/repl/render.clj`

**Depends on**: Phase 1, `seon.render/find-renderer` (exists in `spec-driven-rendering`)

### Phase 3: `schema/unregister!` + `ns-unmap` Interception

Add `unregister!` to `seon.schema`. Wire `ns-unmap` interception in the REPL interceptor. Ensure `*ctx*` stays in sync.

**Files**: `src/seon/schema.clj` (add unregister!), `src/seon/repl/interceptor.clj` (extend)

### Phase 4: Test Harness Integration

Wire `deftest` interception: run test, record coverage via instrumentation, update `*ctx*`. Provide fresh Datalevin per test run.

**Files**: `src/seon/repl/interceptor.clj` (extend), builds on `seon.dev.test`

### Phase 5: Persist Pipeline

`(seon/persist!)` — validate all requirements, generate `(ns ...)` form, write `.clj` file, update Datalevin graph. This is the "graduation" concept from `super-repl/prd.md` Phase 3.

**Files**: `src/seon/repl/persist.clj`

**Depends on**: `seon.graph.ingest` (exists), Phase 1-4

### Phase 6: Ctx-as-Context Integration

Wire the AI renderer output into agent launch. When an agent starts, their initial context is the rendered `*ctx*`. After every eval, the context refreshes from updated `*ctx*` data. This realizes the "cockpit" vision.

**Depends on**: Phase 2, agent launch infrastructure (exists in `seon.flow.pool`)

---

## Related Documents

### Internal (Seon Codebase)

| Document | Relevance |
|----------|-----------|
| [`super-repl/prd.md`](../super-repl/prd.md) | Phase 1-2 (pool, graph) done. Phase 3 (graduation) -> this PRD Phase 5. Phase 5 (cockpit) -> this PRD Phase 6. |
| [`spec-driven-rendering/prd.md`](../spec-driven-rendering/prd.md) | Renderer resolution algorithm (done). Phase 4 (Agent Context Cockpit) -> this PRD Phase 2+6. |
| [`render-pipeline/prd.md`](../render-pipeline/prd.md) | Phases 1-5 done. Phase 6 (flow channels for AI) -> this PRD Phase 6. |
| [`schema-unification/design.md`](../schema-unification/design.md) | No `:any`, no `[:maybe]`, Nippy serialization — constraints enforced by REPL interceptor. |
| `VISION.md` | "The REPL as Sole Interface" (lines 97-105), "The Core Primitive" (lines 64-68), "Constraints That Simplify" (lines 117-127). |
| `CONVENTIONS.md` | Map-in/map-out, `:malli/schema` metadata, schema registration patterns. |
| `src/seon/schema.clj` | Current `register!` API — needs `unregister!` added. |
| `src/seon/ctx.clj` | Current ctx system — validation, persistence, SSE push. |
| `src/seon/render.clj` | Current `find-renderer`, `for-ai`, `for-html` — composable renderers replace `for-ai`. |
| `src/seon/flow/pool.clj` | Agent JVM pool — warm JVMs for agent isolation. |
| `src/seon/flow/agent_runner.clj` | Agent JVM entry point — `*ctx*` injection. |
| `src/seon/dev/test.clj` | REPL-first test runner — structured results. |
| `src/seon/dev/clojure_replace.clj` | S-expression editor — may be retired for agent use (kept for orchestrator/Claude Code). |

### External Research

| Source | Key Insight |
|--------|------------|
| [Anthropic: Use XML tags to structure prompts](https://docs.anthropic.com/en/docs/build-with-claude/prompt-engineering/use-xml-tags) | Claude specifically trained on XML. Section delimiters prevent context contamination. |
| [Anthropic: Effective Context Engineering for AI Agents](https://www.anthropic.com/engineering/effective-context-engineering-for-ai-agents) | Context = curated tokens. Compaction, structured notes, sub-agents. Tools should return token-efficient info. |
| [XML vs Markdown Performance Insights (Medium)](https://medium.com/@isaiahdupree33/optimal-prompt-formats-for-llms-xml-vs-markdown-performance-insights-cef650b856db) | XML costs ~15-80% more tokens but provides unambiguous boundaries. Claude optimized for XML, GPT more flexible. |
| [Markdown vs XML Comparative Analysis](https://www.robertodiasduarte.com.br/en/markdown-vs-xml-em-prompts-para-llms-uma-analise-comparativa/) | Model performance varies up to 40% based on format alone. Use model-specific formatting. |
| [SSW Rules: XML vs Markdown for AI Prompts](https://www.ssw.com.au/rules/ai-prompt-xml) | XML for multi-section structured prompts. Markdown for simple formatting within sections. |
| [Which Nested Format Do LLMs Understand Best?](https://www.improvingagents.com/blog/best-nested-data-format/) | XML best for hierarchical data. Markdown best for flat content. Combine both. |
| [Prompt Format Impact on LLM Performance (arXiv)](https://arxiv.org/html/2411.10541v1) | Format affects accuracy significantly. Newer models (GPT-4-turbo) less susceptible but still benefit. |
| [Anthropic: Prompting Best Practices](https://platform.claude.com/docs/en/build-with-claude/prompt-engineering/claude-prompting-best-practices) | Organize prompts into distinct sections. XML tagging or Markdown headers to delineate. |

---

## Design Decisions

| Decision | Rationale |
|----------|-----------|
| `:live` / `:persisted` naming | Matches Clojure REPL semantics: "in memory" vs "on disk." Clearer than draft/approved, scratch/committed. |
| Status recomputed, not stored | Derived from current state (test coverage, schema validity). Cannot get stale. |
| Explicit `(seon/persist!)` | Like `git commit` — agent decides when work is ready. Auto-persist risks partial states. |
| XML section delimiters for AI | Claude trained on XML. Clear boundaries. Nestable. Cross-model compatible. Token cost acceptable for structured context. |
| Composable per-key renderers | Same discovery mechanism as everything else. No monolithic functions. Override by writing more specific functions. |
| Generic fallbacks have 0 required keys | Empty set is subset of everything -> always matches, always loses to specific renderers. Natural specificity ordering. |
| `*ctx*` carries function source as strings | Needed for persist step. Useful for AI rendering. Small cost since Clojure functions are typically short. |
| Schema definitions stored as strings in `*ctx*` | Serializable to Datalevin. Can be parsed back via `edamame` when needed. |
| No external deps without orchestrator approval | Simplifies JVM lifecycle. Agents work with what's on classpath. Relax later if needed. |
| `clojure_replace` kept for orchestrator use | Claude Code (the orchestrator) may still edit files directly. Agents don't. |

## Open Questions

1. **History depth in `*ctx*`**: All history persisted to Datalevin, but how many entries in `*ctx*` for rendering? Sliding window of last N? Configurable per agent?

2. **Cross-agent visibility timing**: When agent A persists, how quickly does agent B see it? Immediate via Datalevin graph update? Or polling interval?

3. **Markdown within XML**: Should content inside XML tags use markdown formatting (tables, code blocks)? Or plain text only? Hybrid (XML sections, markdown tables within) may be optimal.

4. **Renderer for the renderer**: Should the AI context renderer itself be discoverable from day one? Or hardcode initially and make discoverable once the system is proven?

5. **Test DB strategy**: Fresh empty Datalevin per test run (simple, isolated) vs snapshot of production DB (realistic data)? Start with fresh, add snapshot option later?
