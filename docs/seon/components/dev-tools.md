---
type: component
status: production
tags: [component, agent]
---
# Dev Tools

> Automated feedback loop for AI agents editing Clojure code — lint, repair, test, review, and instrument on every edit.

## Purpose

The dev tools system provides real-time feedback to Claude Code agents (and human developers) whenever Clojure source files are edited. It intercepts Claude Code hook events (PreToolUse/PostToolUse for Edit/Write) and runs a multi-stage pipeline: syntax validation, delimiter repair, namespace reload, unit tests, generative tests, convention compliance, and Gemini AI code review. The goal is to catch errors immediately — before they compound — and provide actionable feedback.

## Architecture

The system is orchestrated by `seon.dev.hook`, which is the single entry point called by a thin Babashka hook script (`.claude/seon-hook.edn`). It coordinates all other `seon.dev.*` namespaces.

### Pipeline Flow

```
PreToolUse (before edit lands)
  +- lint/validate-for-write
       +- syntax-check (edamame, ~1ms) — catches delimiter errors
       +- lint-source (clj-kondo, ~50-100ms) — catches undefined symbols, wrong arity
       +- suggestions/enrich-findings — adds "did you mean?" hints
       -> BLOCK if invalid (edit never happens)

PostToolUse (after edit lands)
  Markdown files (.md):
    -> markdown/validate-file — checks frontmatter, structure, wikilinks
    -> markdown/fix — auto-fixes formatting (blank lines, trailing whitespace)
    -> re-validate, FEEDBACK if structural issues remain

  Clojure source files (.clj):
    1. repair     — parinferish fixes unbalanced delimiters, cljfmt reformats
    2. reload     — clj-reload reloads changed namespaces + dependents
                    (index update is inline: best-effort code graph update after reload)
    3. compliance — checks :malli/schema, map-in pattern, docstrings
    4. unit tests — runs *-test namespace if it exists
    5. gen tests  — Malli mg/check on schema-annotated functions
    6. record     — stores edit event in memory (context tracking)
    7. review     — Gemini AI review if rate limit allows (every 60s)
    -> BLOCK if reload fails or tests fail (configurable)
    -> FEEDBACK with dense summary line: "5 tests, gen-tests, compliant (0.3s)"

```

### Key Design Decisions

- **PreToolUse blocks bad edits** — syntax errors and lint errors prevent the edit from landing. This saves agents from debugging broken code they just wrote.
- **Dense mode** — by default, passing stages produce a single summary line instead of verbose output, keeping agent context windows clean.
- **Rate-limited reviews** — Gemini reviews are advisory (never blocking) and throttled to at most one per 60 seconds to avoid API cost explosion.
- **MCP parity** — `process-mcp-edit!` gives the `clojure_replace` MCP tool the same pipeline as the regular Edit hook, sharing `run-post-edit-pipeline!`.

## File Map

| File | Purpose |
|------|---------|
| `src/seon/dev/hook.clj` | Main orchestrator — `process-hook-event!` and `process-mcp-edit!` |
| `src/seon/dev/lint.clj` | Syntax checking (edamame) and static analysis (clj-kondo) |
| `src/seon/dev/suggestions.clj` | "Did you mean?" enrichment for lint findings |
| `src/seon/dev/repair.clj` | Delimiter repair (parinferish) + formatting (cljfmt) |
| `src/seon/dev/verify.clj` | Unit test runner + generative test runner (Malli mg/check) |
| `src/seon/dev/review.clj` | Gemini AI code review — builds context, calls API, formats output |
| `src/seon/dev/context.clj` | In-memory edit/review event tracking, rate limiting, observability |
| `src/seon/dev/compliance.clj` | Convention compliance checking (schema, map-in, docstrings) |
| `src/seon/dev/analysis.clj` | clj-kondo library integration for call graphs and var definitions |
| `src/seon/dev/codebase.clj` | File-to-namespace mapping, source reading, test file detection |
| `src/seon/dev/instrumentation.clj` | Malli runtime instrumentation with agent-friendly error messages |
| `src/seon/dev/markdown.clj` | Markdown validation and auto-fixing — parses, validates frontmatter/structure, auto-fixes formatting |
| `src/seon/dev/clojure_replace.clj` | Comment-aware s-expression match/replace editing using rewrite-clj (MCP `clojure_replace` tool backend) |
| `src/seon/dev/test.clj` | REPL-first structured test runner (`test`, `test-all`, `test-affected`, `test-gen`) |
| `src/seon/dev/test_select.clj` | Dependency-aware test selection via code graph |
| `src/seon/repl.clj` | REPL form router — classify, eval via flow, store in Datalevin, index |
| `src/seon/repl/context.clj` | Agent context cockpit — graph-based context for functions/namespaces |
| `src/seon/repl/graduate.clj` | Namespace graduation — assembles Datalevin forms into .clj files |

## Subsystems

### Hook System (`hook.clj`)

The main entry point `process-hook-event!` accepts a Claude Code hook event JSON and a config map (merged with defaults). It classifies the event by tool name and event type, then dispatches:

- **PreToolUse Edit/Write** on Seon source files: runs `lint/validate-for-write` which does syntax + clj-kondo analysis. Blocks with detailed error message including "did you mean?" suggestions if invalid.
- **PostToolUse Edit/Write** on Seon source files: runs the full 7-stage pipeline. Each stage can short-circuit with a block response.
- **PostToolUse Edit/Write** on Markdown files: runs `markdown/validate-file` then `markdown/fix` (auto-fix formatting), re-validates, and returns feedback for remaining structural issues.
- **PostToolUse TodoWrite**: records agent todo list snapshots to in-memory store for widget display.
- **Non-Clojure, non-Markdown files**: passed through with no processing.

Configuration is deeply merged with `default-config`:

```clojure
{:lint {:enabled true}
 :repair {:enabled true :cljfmt true}
 :reload {:enabled true}
 :tests {:unit {:enabled true :block-on-fail true :timeout-seconds 30}
         :generative {:enabled true :num-tests 10 :block-on-fail true}}
 :review {:enabled true :interval-seconds 60 :max-code-length 12000}
 :compliance {:enabled true :block false}
 :feedback {:dense true :max-length 1000}}

```

### Lint & Validation (`lint.clj`)

Two-stage validation:

1. **Fast syntax check** via edamame (~1ms) — catches unbalanced delimiters, unclosed strings
2. **Static analysis** via clj-kondo (~50-100ms) — catches unresolved symbols, wrong arity, private var access

`validate-for-write` is the **single source of truth** for pre-write validation. Both the hook (PreToolUse) and MCP (`clojure_replace`) call it, ensuring consistent error messages and suggestions.

clj-kondo config focuses on errors, not style: `unresolved-symbol`, `unresolved-namespace`, `invalid-arity`, `private-call` at `:error` level; style linters like `missing-else-branch` and `unused-binding` turned off.

### Auto-Repair (`repair.clj`)

Uses parinferish in indent mode to infer correct delimiters from code indentation. Effective for common LLM errors like missing closing parentheses. After repair, optionally reformats with cljfmt. Only runs on PostToolUse — repairs the file on disk, then the rest of the pipeline validates the repaired version.

### Test Orchestration (`verify.clj`, `test.clj`, `test_select.clj`)

Three test modes:

- **Unit tests** (`verify/run-unit-tests`): Reloads test namespace, runs clojure.test, captures structured results (test/pass/fail/error counts + output).
- **Generative tests** (`verify/run-gen-tests`): Uses Malli `mg/check` to generate random inputs for all schema-annotated functions in a namespace. Ensures registry sync after reload.
- **Affected tests** (`test-select/affected-test-namespaces`): Queries the [[components/code-graph]] to find dependents of a changed namespace, maps to test namespaces, runs them all.

`seon.dev.test` provides the REPL-facing API exposed via `user/run-tests`, `user/test-affected`, `user/test-gen`. Returns structured data maps (not text), stores results in an atom for later inspection via `last-results` and `results-history`.

### Gemini AI Review (`review.clj`)

Rate-limited AI code review using the Gemini API:

1. `build-context` — collects source + test files, loads CONVENTIONS.md, formats test results
2. `call-gemini` — sends to `seon.ai.gemini/review-code` with system instruction, code context, and test summary
3. `format-output` — truncates to max length, prefixes with "Gemini:"

Full Gemini interaction data (prompt, response, system instruction, code, token counts) is stored via `context/record-review!` for training data collection.

### Convention Compliance (`compliance.clj`)

Checks every public function in a namespace for:

- `:malli/schema` metadata present
- Map-in pattern (single map argument with destructuring)
- Docstring present
- Schema refs registered in the Malli registry
- Schema naming convention (`fn-name-request`/`fn-name-response`)

`format-violations` produces brief summaries; with `::with-fixes true` it generates copy-pasteable fix code including schema registrations, metadata, and map-in signatures.

### Markdown Validation (`markdown.clj`)

Seon-native markdown linter that runs automatically on every `.md` file edit. Replaces the external `markdownlint-cli2` npm dependency.

Two operations:

- **`validate-file`** — parses the document and checks: YAML frontmatter present, ATX headings only (no setext), no heading level jumps, one h1 per doc, dash for lists, wikilink targets exist in the vault, no bare URLs.
- **`fix`** — auto-fixes formatting violations: blank lines around headings and code fences, no multiple blank lines, trailing newline, no trailing whitespace. Returns `::fixed-count` so callers know if anything changed.

After auto-fix, the hook re-validates and reports only the remaining structural violations (which require human judgment to fix).

### Schema Instrumentation (`instrumentation.clj`)

Malli function instrumentation managed as an Integrant component (`:seon.dev/instrumentation`). On start, collects `:malli/schema` metadata from all loaded namespaces and instruments them with a custom `agent-reporter` that throws `ExceptionInfo` with rich, structured error messages:

- Which argument failed and what was expected
- The full schema expanded with descriptions
- An example valid call (generated via `mg/generate`)
- The function's docstring

Survives `(user/reset)` via Integrant suspend/resume. `refresh!` re-collects and re-instruments after code reload.

### Context Tracking (`context.clj`)

In-memory event store (atoms, ephemeral per dev session) tracking:

- **Edit events**: file path, namespace, test results, decision (continue/block), reason
- **Review events**: files reviewed, Gemini prompt/response/tokens
- **Todo events**: agent task list snapshots from TodoWrite

Provides observability queries: `failure-rate`, `gemini-token-usage`, `recent-activity`, `edits-for-file`. Rate-limits reviews via `should-review?` (simple interval-based).

### Code Analysis (`analysis.clj`)

Uses clj-kondo as a library (in-process) for deep analysis: var definitions, var usages (call graphs), namespace dependencies, lint findings. `callees-of` and `callers-of` extract the call graph from analysis results. `format-file!` runs cljfmt on disk.

### REPL System (`repl.clj`, `repl/context.clj`, `repl/graduate.clj`)

The REPL form router (`seon.repl`) handles agent code evaluation:

1. **Classify** — edamame parses the form to determine type (defn/def/ns/require/expression)
2. **Eval** — routes through the infrastructure [[components/flow-topology]] via `flow/inject`
3. **Store** — versioned form storage in Datalevin (`:form/id`, `:form/version`, etc.)
4. **Index** — updates the code graph via `graph/analyzer` + `graph/ingest`

`repl/context.clj` provides agent context cockpit — graph-based context retrieval for functions, namespaces, and data (finding matching [[concepts/renderer-discovery|renderers]]).

`repl/graduate.clj` assembles Datalevin-stored forms into proper `.clj` files, writes to disk, and optionally git commits. This is the "Super REPL" graduation path.

## Connections

- **[[components/code-graph]]** — analysis feeds the graph; test-select queries it for dependents; hook updates the index after edits
- **[[components/renderer]]** — repl/context uses renderer discovery for `for-data`
- **[[components/flow-topology]]** — REPL eval routes through the infrastructure flow topology
- **[[components/schema-system]]** — instrumentation validates all public functions at runtime; compliance checks schema presence
- **[[components/database]]** — REPL forms stored in Datalevin; context queries use Datalevin

## REPL Helpers

The `user` namespace exposes these dev tool functions:

| Function | What it does |
|----------|-------------|
| `(user/reload)` | Fast reload via clj-reload |
| `(user/reset)` | Full Integrant restart |
| `(user/status)` | Check system health |
| `(user/run-tests 'ns)` | Run unit tests for namespace |
| `(user/run-tests)` | Run all unit tests |
| `(user/test-affected 'ns)` | Test namespace + dependents |
| `(user/test-gen 'ns)` | Generative tests on schema-annotated fns |
| `(user/search "q" :files [...])` | Gemini with web access + code context |
| `(user/ask "q")` | Gemini model knowledge only |
