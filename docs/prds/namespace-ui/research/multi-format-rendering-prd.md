# Research PRD: Multi-Format Data Rendering

**Status:** Research
**Priority:** High
**Type:** Exploratory - find the right approach, don't prescribe one

---

## Vision

Data in Seon should render appropriately for its consumer:

- **AI agents** see concise, schema-aware output (not 15k tokens of raw EDN)
- **Web UI** shows rich, interactive views with expand/collapse
- **Raw** returns EDN for serialization

The rendering definition should:

1. **Live with the data definition** - if you define `:trading/position`, its rendering is part of that definition
2. **Be inherited** - when processing data from another namespace, you get its rendering automatically
3. **Support local overrides** - create `::my-ns/position` that's compatible with `:trading/position` but renders differently
4. **Work purely from REPL** - agents can define rendering without editing files

---

## Problem Statement

Currently:

- nREPL returns `pr-str` output directly - no consumer-aware formatting
- Large data structures blow up AI context windows
- UI rendering (`seon.ui.viewer`) is separate from data definitions
- No way to inherit rendering when receiving data from another namespace

**Example of the problem:**

```clojure
;; In seon.trading.options.analysis namespace
;; We receive positions from seon.trading.core
(defn analyze-positions [positions]
  ;; positions is a vector of :trading/position maps
  ;; When we return results, how do we:
  ;; 1. Inherit trading's position rendering?
  ;; 2. Add our own analysis-specific rendering for our return type?
  ;; 3. Have the AI see something concise vs the UI see something rich?
  ...)
```

---

## Open Questions (For Research)

### Q1: Where do render definitions live?

Options to explore:

- **Malli schema properties** - `[:map {:seon.ui/render ...} ...]`
- **Separate render registry** - keyed by schema keyword
- **Protocol extension** - extend Datafiable for schema types
- **Metadata on values** - values carry render hints

### Q2: How does inheritance work?

If `:trading/position` has a renderer and I create `::analysis/position` that's schema-compatible, does it:

- Automatically inherit the renderer?
- Require explicit inheritance declaration?
- Use Malli's schema subtyping?

### Q3: How do local overrides work?

```clojure
;; I want ::analysis/position to be data-compatible with :trading/position
;; but have its own renderer. How?

;; Option A: Schema that references parent
(schema/register! ::position
  [:merge :trading/position
   {:seon.ui/render 'analysis.ui/render-position}])

;; Option B: Separate render registry with inheritance
(ui/register-renderer! ::position
  {:inherit :trading/position
   :render-fn 'analysis.ui/render-position})

;; Option C: Something else?
```

### Q4: How does multi-format work?

```clojure
;; Same data, different consumers
(render position :ai)    ;; => "Position: AAPL x100 @ $150"
(render position :html)  ;; => [:div.position-card ...]
(render position :raw)   ;; => {:ticker "AAPL" :quantity 100 ...}
```

Is this:

- One render function with format parameter?
- Separate render functions per format?
- Multimethod dispatch on format?

### Q5: Does datafy/nav help here?

Clojure's `datafy` and `nav` protocols allow values to carry navigation behavior. Questions:

- Can we extend these for schema-typed maps?
- Does metadata flow through nav operations?
- Is this complementary or alternative to Malli approach?

### Q6: How does this integrate with nREPL?

When an agent evals `(analyze-positions data)`, the result needs to be formatted before becoming a string. Options:

- Custom nREPL middleware
- Override print-method per type
- Wrapper at MCP level
- Something in the session/ctx

---

## Resources to Study

| Resource | What to Learn |
|----------|---------------|
| `reference-code/portal/src/portal/viewer.cljc` | How Portal attaches viewer metadata |
| `reference-code/reveal/src/vlaaad/reveal/stream.clj` | Multimethod dispatch on type/metadata |
| `src/seon/schema.clj` | Current Malli registry |
| `src/seon/ui/viewer.clj` | Current render-value multimethod |
| `src/seon/agent/ctx.clj` | How *ctx* atom works, validation |
| `bin/mcp-server` | How nREPL results become MCP responses |
| Malli docs | Schema properties, registry, subtyping |
| clojure.datafy | Protocol extension, nav semantics |

---

## Constraints

1. **Pure REPL** - Must work without file editing. Agents define rendering at REPL.
2. **No global mutation** - Don't overwrite other namespaces' definitions
3. **Schema compatibility** - Local types that override rendering must still be compatible with parent type
4. **Clojure-native** - Use existing Clojure/Malli idioms, don't reinvent

---

## Research Deliverables

This is exploratory research. Agents should:

1. **Prototype multiple approaches** - Don't commit to one approach early
2. **Test at REPL** - Actually run code, see what works
3. **Document tradeoffs** - What are the pros/cons of each approach?
4. **Identify blockers** - What doesn't work? What's missing?
5. **Recommend direction** - Based on experiments, what approach should we pursue?

Write findings to `docs/prds/namespace-ui/research/` with concrete code examples showing what works and what doesn't.

---

## Success Criteria

Research is successful if we can answer:

1. Where should render definitions live?
2. How does inheritance work (or should it)?
3. How do local overrides work without breaking compatibility?
4. How does multi-format rendering work?
5. How does this integrate with the nREPL → MCP flow?

With working code examples demonstrating the recommended approach.
