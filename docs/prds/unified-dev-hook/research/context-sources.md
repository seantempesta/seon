# Research Context Sources

**Date:** 2024-12-28
**Status:** Complete

---

## Research Files in This Folder

| File | Purpose | Key Insight |
|------|---------|-------------|
| `parsing-approaches.md` | Compare static parsing vs REPL introspection | **Use REPL** - `(m/function-schemas)` already exists |
| `malli-resolution.md` | How to resolve schema refs recursively | Use `m/walk` + registry lookup |
| `recommendations.md` | **Implementation plan** - start here | Full code examples for each phase |

---

## External Research

### Gemini Native Integration
**Location:** `docs/research/gemini-native-integration.md`

Contains:
- HTTP client patterns (hato - already in deps.edn)
- Gemini REST API reference (generateContent, streaming)
- Google Search grounding
- Cost analysis (~$5-20/month for active development)
- Proposed namespace structure for `seon.ai.gemini`

---

## Existing Code to Study

### clojure-mcp-light Hook System
**Location:** `~/.gitlibs/libs/org.babashka.bbin/script--693757147.../src/clojure_mcp_light/`

Key files:
- `hook.clj` - Multimethod dispatch for Pre/PostToolUse events
- `nrepl_eval.clj` - nREPL client with session management
- `nrepl_client.clj` - Low-level bencode communication
- `delimiter_repair.clj` - Syntax fixing logic

**Patterns to borrow:**
- Multimethod dispatch by `[hook_event_name tool_name]`
- Backup/restore on edit failure
- Session persistence across invocations

### Current auto-test-hook
**Location:** `bin/auto-test-hook`

Key patterns:
- File → namespace mapping
- nREPL test execution via `clj-nrepl-eval`
- JSON response with `{:decision "block"}`

---

## Malli Reference

### Context7 Documentation
Use Context7 tool with `/metosin/malli` for:
- `function-checker` - Generative testing for functions
- `instrumentation` - Runtime schema enforcement
- `m/=>` - Function schema declaration

### Key APIs (from research)

```clojure
;; Get all function schemas (the key discovery!)
(m/function-schemas)
;; => {:clj {ns-sym {fn-sym {:schema ..., :ns ..., :name ...}}}}

;; Walk schema to find refs
(m/walk schema
  (fn [s _ children _]
    (when (keyword? (m/type s))
      (swap! refs conj (m/type s)))
    (m/-set-children s children)))

;; Run generative tests
(mg/check fn-schema fn-impl {:num-tests 10})
```

---

## Related PRDs

- `docs/prds/auto-test-hook/` - Original test hook design (being replaced)
- `docs/prds/logging-system/` - May need logging integration
