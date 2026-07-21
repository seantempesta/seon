---
type: research
status: active
tags: [research, agent, reference]
---

# Clojure idiom & coding-standards audit (Gemini, 2026-06-25)

A full-context Gemini audit (`agy`, ~74k-token corpus) of the active CLJS-pod
core — `seon.render`, `seon.ctx`, `seon.db`, `seon.schema`, `seon.agent`,
`seon.agent.loop`, `seon.agent.lifecycle`, `seon.agent.message`,
`seon.agent.todo` — for conformance to general Clojure idioms AND the project's
own stated standards (namespaced keys, Malli on public fns, errors-as-values,
db-first, `.internal` pattern, true-state docstrings, no `*-v2`, native async).

## TL;DR

Gemini grades the core **B-**: strong architecture (immutable-db render,
SCI-bounded agent renderers, identity-caching, pure-data section maps,
`;;=>`-replayable transcript) but it regularly diverges from the project's OWN
standards. The three real themes:

1. **Non-namespaced keys in render-dispatch maps** (`render.cljs`
   `renderable-kinds`/`kind-tables`/`!kind-cache` return `:kind`/`:id-attr`/
   `:ai`/`:html`/`:kinds`/`:db`/`:tables`) — a direct violation of the
   namespaced-key rule. HIGH, and a clean fix.
2. **`db` passed SECOND** in several `seon.ctx` helpers (`effective-cap`,
   `current-session`, `session-evals`) — violates "db is the first parameter".
3. **Missing `:malli/schema` on several public `seon.ctx`/`seon.render` fns**
   + **docstrings carrying dates / issue-refs / commit-ids** (matches our own
   standing rule `feedback_docstrings_true_state`) + complex helpers sitting in
   the public `seon.ctx`/`seon.render` rather than `.internal` (exactly the
   refine-wave target).

## Orchestrator synthesis — what to act on, what to drop

**Real + high-leverage (verify, then fix):**

- Namespaced-key violations in `render.cljs` dispatch maps — but these are
  INTERNAL maps; confirm nothing external depends on the bare keys, then
  namespace them. (Cross-check: do these feed `resolve-slot`/`entity-primary-kind`?)
- `db`-second in `seon.ctx` helpers — reorder to db-first; update callers in
  the same patch.
- Missing Malli on genuinely-PUBLIC fns — VERIFY each is `defn` not `defn-`
  first (Gemini can't see privacy reliably); `render` is the recursive walker
  and is a real gap worth schematizing.
- Docstring date/issue/commit noise — aligns with the refine wave; fold in.
- `.internal` extraction for `seon.ctx`/`seon.render` — already the plan
  (refine wave covers fs/search/schema; ctx/render come with P2).

**Borderline (judge per case):**

- `resolve-id` throwing escaping to `messages`/`evals` — throwing on a genuine
  programmer error (no agent in scope) is defensible; errors-as-values is for
  AGENT-facing verbs. Low priority.
- `loop/recur` → `reduce`+`reduced` in `apply-agent-budget`, manual atoms in
  `collect-ns-order` → pure recursion — legitimate idiom improvements, low risk,
  do opportunistically when touching those fns (don't make-work).
- `#(.getTime ^js %)` → `(fn [t] (.getTime ^js t))` — valid CLJS fragility nit.

**FALSE POSITIVE (do NOT act on):**

- "`!kind-cache` naming violation — `!` is reserved for side-effecting fns."
  WRONG for this codebase: `!`-prefixed atoms are deliberate HOUSE STYLE
  (`!compile-state`, `!own-request-ids`, `!sessions-opened-this-run`,
  `!kick-scheduled`-era). Keep `!atom` = mutable-ref. This is the "dissent
  biases to preserving generic conventions" pattern — Gemini surfaced it; we drop it.

**What it praises (do NOT regress):** identity-cached `kind-tables` on immutable
db snapshots; SCI-bounded agent renderers; hiccup-structure hardening with
graceful fallback; pure-data `core-default-ctx` section maps; `;;=>` replayable
transcript.

## Plan

- Fold the docstring-noise + `.internal` findings into the in-flight refine wave
  (they're the same work).
- A dedicated small "idiom cleanup" unit for: namespaced-key dispatch maps
  (render), db-first reordering (ctx helpers), the `^js %` nit, the node/entity
  tolerance collapse — all low-risk, verify-then-fix.
- The Malli-gap closure is its own careful unit (instrumentation implications;
  verify public-vs-private per fn first).

## Verbatim Gemini output

```
### 1. TL;DR Verdict

**Overall Grade: B-**

While the codebase exhibits strong architectural decisions—such as utilizing immutable database snapshots for rendering, using Datalog to query schema catalogs, and enforcing SCI-based runtime isolation for agent-defined execution—it regularly diverges from its own strict coding standards. The most critical violations are a **widespread absence of Malli schemas** on public API functions, **inconsistent parameter ordering** (specifically placing the `db` parameter second instead of first), and **cluttered docstrings/comments** that document historical git sweep dates and issue numbers instead of focusing strictly on current runtime behavior.

---

### 2. Findings by Category

#### Idiomatic Control Flow & Seq Usage
* **Med Severity** | ctx.cljs:apply-agent-budget (L1618-1659)
  * **Problem**: Uses a manual `loop`/`recur` structure to perform an early-terminating reduction while calculating which agent sections to truncate based on budget.
  * **Idiomatic Fix**: Use `reduce` combined with `reduced` to express early termination functionally and cleanly.

#### Destructuring & Function Shape
* **Low Severity** | ctx.cljs:format-eval-row (L525-533)
  * **Problem**: Verbose, manual mapping destructuring of namespaced keywords (e.g., `src :seon.eval/source`).
  * **Idiomatic Fix**: Replace with `:seon.eval/keys` syntax.

#### Naming
* **Low Severity** | render.cljs:!kind-cache (L248)
  * **Problem**: Prefixing atom variables with `!` is not a standard Clojure community idiom; `!` is reserved for side-effecting/mutating functions.
  * **Idiomatic Fix**: Rename to `kind-cache` or `*kind-cache*`.

#### Data Orientation
* **High Severity** | render.cljs:renderable-kinds (L234)
  * **Problem**: The returned maps utilize non-namespaced keywords (`:kind`, `:id-attr`, `:ai`, `:html`), violating the project's own standard.
  * **Idiomatic Fix**: Use fully namespaced keywords.
* **Med Severity** | render.cljs:kind-tables (L267)
  * **Problem**: Returns a map with non-namespaced keys (`:kinds`, `:kinds-by-kw`, `:required-by-kind`).
* **Low Severity** | render.cljs:!kind-cache (L270)
  * **Problem**: Caches state using non-namespaced keys: `{:db db :tables tables}`.

#### State & Effects
* **Med Severity** | ctx.cljs:collect-ns-order (L1276-1278)
  * **Problem**: Widespread use of local mutable atoms (`seen`, `order`, `data-by-kw`) inside a function body to accumulate graph-traversal state.
  * **Idiomatic Fix**: Implement the DFS using a pure recursive helper with accumulator parameters.

#### Error Handling
* **Med Severity** | ctx.cljs:resolve-id (L638)
  * **Problem**: Throws `ex-info` when no agent-id is in scope. This exception escapes to public API boundaries like `messages` and `evals`.
  * **Idiomatic Fix**: Return an error map envelope.

#### Docstrings & Comments
* **Med Severity** | Widespread in render.cljs and ctx.cljs
  * **Problem**: Comments/docstrings contain historical sweep dates, issue numbers, and commit references (e.g. "deleted in the render sweep (2026-06-11)", "#43 / D2", "65dfc90", "PRD §8.1").
  * **Idiomatic Fix**: Strip dates, changelogs, and issue references.

#### CLJS-Specifics
* **Med Severity** | ctx.cljs:effective-cap (L154) and ctx.cljs:messages (L674)
  * **Problem**: The type hint `^js %` inside `#()` shorthand is fragile.
  * **Idiomatic Fix**: Use explicit `fn`: `(fn [t] (.getTime ^js t))`.

#### Project Standard Violations
* **High Severity** | Widespread in ctx.cljs and render.cljs
  * **Problem**: Widespread absence of Malli schemas on public functions: render, effective-cap, messages, current-session, evals, ctx-sections.
* **High Severity** | ctx.cljs:effective-cap (L130), current-session (L192), session-evals (L688)
  * **Problem**: The `db` snapshot parameter is placed second, violating "the DB is the first parameter".
* **Med Severity** | render.cljs:render-entity-html (L335)
  * **Problem**: Tolerating `:seon.render/entity` alongside `:seon.render/node` violates "No parallel/duplicate code paths".
* **Med Severity** | Widespread in ctx.cljs
  * **Problem**: Complex helper functions live in the public seon.ctx namespace instead of a separate internal namespace.
  * **Idiomatic Fix**: Move helpers to seon.ctx.internal and seon.render.internal.

---

### 3. Top 10 Highest-Leverage Fixes (Ranked)

1. Add Malli Schema to `render`.
2. Reorder Parameters in `effective-cap` (db first).
3. Reorder Parameters in `current-session` (db first).
4. Reorder Parameters in `session-evals` ([db agent-id]).
5. Namespace keys in `renderable-kinds`.
6. Namespace keys in `kind-tables`.
7. Clean up destructuring in `format-eval-row` (:seon.eval/keys).
8. Fix fragility in `effective-cap` type hints.
9. Eliminate legacy fallback key (or node entity) in render-entity-html.
10. Sanitize docstrings (strip dates, changelogs, commit IDs).

---

### 4. What the Codebase Does Well

1. Robust memory-identity caching (kind-tables identical? on immutable db snapshots).
2. Safe render isolation (SCI interrupts + deadlines for agent-defined renderers).
3. Double-layer serialization hardening (hiccup-structure-error + graceful fallback).
4. Pure-data schemas (core-default-ctx layout as pure maps).
5. REPL-friendly transcript output (;;=> commented return values, replayable).
```
