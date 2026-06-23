---
type: research
status: active
tags: [research, agent]
---

# Eval'able context — section demarcation, namespace blocks, and large-data elision (2026-06-23)

## TL;DR

The redesign makes the **entire** agent context one valid, eval'able Clojure
REPL transcript (FSM north star, `agent-fsm-redesign-2026-06-23.md` §"Channels
— all valid Clojure"). This report nails down the three open format questions
and grounds each in the standard Clojure idiom, the vendored source, AND the
project owner's clarification: *we control the whole render, a little magic is
fine, optimize for the LLM* (not for vanilla editor/REPL tooling).

Recommendations (each detailed below):

1. **Section demarcation** — keep the **`;;;` heading comment** the runtime
   already uses for turn headers as the section START, and add a matching
   **`;;; ╶─ end <name> ─╴` (or `;;; └─ … ─┘`) closing comment** as the END.
   Do NOT wrap whole sections in `(comment …)` or `#_(…)`: those make the
   section's forms NON-eval'able (the agent can't paste-and-run them) and
   invert the live/real framing the masthead sells. The `;;;` pair is valid
   Clojure (it's a comment), reads as "the runtime's structure, not yours,"
   and a debug viewer folds on the `;;; ── … ──` … `;;; ── end … ──` pair
   trivially because we own the viewer. **Prose inside any section must be
   `;;` comments** (fixes the current bare-prose-in-`system-text` reader bug).

2. **Namespace blocks** — use a leading **`(in-ns 'the.target.ns)`** per block,
   NOT a full `(ns …)`. In a sequentially-evaluated transcript `in-ns`
   correctly retargets the following top-level forms, it's one line (cheaper
   tokens, less visual noise), and it matches the readline sugar the spec
   already defines (`(in-ns 'x)` flips the prompt to `x=>`). The full `(ns …)`
   form is reserved for the **`:namespaces` body** section, where each block
   genuinely IS a namespace *definition* (its real `(ns … (:require …))`
   header + defs). One critical caveat for our CLJS/sci runtime: a namespace
   entered via bare `in-ns` does **not** auto-refer `clojure.core` — but since
   we render fully-qualified calls (`seon.db/query`, `message/user`) and the
   agent's home ns is pre-built with refers, this is a non-issue. Document it
   so nobody hand-rolls a fresh ns and then calls `defn` in it.

3. **Large-data elision** — use the **var-handle pattern Seon already has**
   (`result/<id>`), extended with an explicit, eval-safe elision tag. Render a
   huge value as a `;;=>` comment line carrying a one-line summary + the
   `result/<id>` handle + a `(N of M)` clip marker (this exact mechanism is
   already in `format-eval-row`). For data the runtime emits in-band (catalog
   sections, not eval results) use a **tagged-literal placeholder**
   `#seon/elided {:seon.elide/count 4823 :seon.elide/handle result/a1 …}` with
   a registered reader so it always reads. NEVER use `*print-length*`/
   `*print-level*` truncation in the transcript — the resulting `...` and `#`
   tokens are NOT readable Clojure and would break the eval'able invariant.

The throughline: the transcript must be **eval'able forms + `;;` comments**,
where comments come in two registers — **`;;` = the agent's** (narration,
prose), **`;;;` = the runtime's** (structure: headers, section bounds, status).
That two-register split is the whole demarcation system, and it's already
half-built. (`;;=>` is a third, special runtime register: a *result* line.)

---

## 0. Context: what the redesign already fixes, and what's still open

The FSM spec (`agent-fsm-redesign-2026-06-23.md`) already commits to:

- ONE bottom transcript section that absorbs prompt/turns/status (§"There is
  ONE bottom section").
- `;;;` for runtime headers (`;;; ── turn N · … ──`, `;;; ◀ from X`), `;;` for
  agent narration, `;;=>` for results-as-comments, `ns=>` readline sugar.
- The masthead framing: "this REPL is LIVE and ALWAYS CURRENT… you write forms
  and `;;` comments; the runtime shows each form's value as `;;=>`."

So the **transcript** channel is largely designed. What this research resolves
is how to apply the SAME discipline to **every OTHER section** — the system
masthead, the namespace catalog, the schema catalog — so the whole prompt
top-to-bottom is one eval'able artifact, with a clean per-section start/END a
debug viewer can fold.

### The concrete bug this must fix

`src/seon/ctx.cljs` `system-text` (line ~762) opens with a real heading comment
then dumps ~14k chars of **bare, un-commented prose**:

```clojure
(def system-text
  (str
    ";; ── system ───────────────────────────────────────────────────\n"
    "You are at a live Clojure REPL on one human's runtime. The REPL is\n"   ; <- NOT a comment
    "your only tool: everything you do — read, compute, store, reply,\n"     ; <- reader error if eval'd
    ...))
```

If the agent pasted this section into the REPL, the first prose word (`You`)
is a symbol the reader tries to resolve → error. Under the eval'able-everything
north star this section is **invalid Clojure today**. Every prose line must
become a `;;` (agent-voice) or `;;;` (runtime-voice) comment. Because the
masthead is the runtime talking ABOUT the runtime, it should be `;;;`.

---

## 1. Section heading + END demarcation

### 1.1 The standard idiom (Gemini Q1, reference-code survey)

The Lisp/Clojure semicolon hierarchy (bbatsov style guide, inherited from
Emacs Lisp) is a real, recognized convention:

| Comment | Meaning | Column |
| --- | --- | --- |
| `;` | margin / trailing inline | end of code line |
| `;;` | line comment inside a form, indented to code | code indent |
| `;;;` | top-level comment / sub-section heading | column 0 |
| `;;;;` | major section heading / file divider | column 0 |

This is borne out across the vendored repos:

- `reference-code/cider-nrepl/src/cider/nrepl/middleware/test.clj` —
  `;;; ## Overview`, `;;; ## Test Results` (markdown-in-`;;;` headings).
- `reference-code/datahike/src/datahike/pod.clj` — full banner:
  ```clojure
  ;; =============================================================================
  ;; I/O Infrastructure
  ;; =============================================================================
  ```
- `reference-code/orchard/src/orchard/print.clj` —
  `;;;; Diffing support. Used for orchard.inspect/diff.`
- `reference-code/clj-kondo/.../ast.clj` — `;;;; Scratch` over a `(comment …)`.
- `reference-code/nrepl/.../jvmti.clj` — `;;; Agent unpacking` / `;;; Agent loading`.

Seon's own `.cljs` source uses the `;; =========` banner block for major code
sections (`ctx.cljs`, `render.cljs`) and `;; ── name ──` for agent-facing
section headers (`namespace x`, `system`). So the codebase already mixes two
banner styles; the transcript should pick ONE and use it everywhere.

### 1.2 Marking the END (Gemini Q1 Question 2)

**Vanilla Clojure has NO end-of-section convention.** Editors derive section
END *implicitly*: a `;;;` heading opens a region that runs until the next
heading of equal-or-higher level or EOF (Emacs `outline-minor-mode`,
`clojure-lsp`). For EXPLICIT folding, the ecosystem offers:

- **`#region` / `#endregion`** line comments — VS Code/Calva only.
- **`<editor-fold desc="…">`** — IntelliJ/Cursive only.
- **`^L` form-feed page breaks** — old-school Emacs `forward-page`.
- **Wrapping in `(comment …)`** — the closing `)` IS the structural end; every
  editor folds the s-expression.

**None of these are portable, and we don't need portability** — the owner's
clarification is decisive here: *we control the debug viewer*. We are not
folding in Calva/Cursive; we render the HTML twin ourselves
(`debug-view-section-twins-2026-06-18`, `render.cljs:185`). So the END marker
just has to be (a) valid Clojure (a comment), (b) visually unambiguous to the
LLM, (c) machine-detectable by OUR viewer. A paired `;;;` comment satisfies all
three:

```clojure
;;; ┌─ namespaces ──────────────────────────────────────────────────────────
... section body (in-ns blocks, defs) ...
;;; └─ end namespaces ──────────────────────────────────────────────────────
```

or the lighter:

```clojure
;;; ── namespaces ──────────────────────────────────────────────────────────
...
;;; ── end namespaces ───────────────────────────────────────────────────────
```

The HTML twin folds on the `;;; …` / `;;; … end …` pair with a one-line regex;
the LLM reads a clean open/close bracket; and the whole thing is a comment, so
it's 100% eval-safe. This reuses the EXACT `;;;`-is-runtime register the
transcript already uses for turn headers — one rule, not two.

### 1.3 Why NOT wrap sections in `(comment …)` or `#_(…)`

Gemini Q2 makes a strong general case for `(comment …)` as a section wrapper:
strictly-parsed (valid-Clojure guaranteed), universally foldable, and editors
let you eval inner forms. The reference survey confirms `(comment …)` is THE
idiomatic scratch/RCF block (`malli/dev/user.clj`, `datahike/dev/user.clj`,
`nrepl/.../tls_client_proxy.clj`). **For our specific goal it is the wrong
tool**, for three reasons:

1. **It kills paste-and-run.** The owner's constraint #1 is "everything that's
   code must be executable" — a shown `(defn …)` must run when pasted. Wrap a
   section in `(comment …)` and pasting the whole section returns `nil` and
   runs nothing. The agent can't trust that "this code is live." Same for
   `#_(do …)` — the reader discards it. (Gemini Q2 matrix: both score "whole
   block eval = no-op.") The transcript's POWER is that it's real and runnable;
   wrapping defeats it.

2. **It breaks the LIVE/REAL framing the masthead sells.** The spec is emphatic
   (§"Framing rule"): the substrate must NEVER emit a shape it told the agent
   is non-live, or the agent mimics it. `(comment …)` literally means "this is
   not run." Rendering the namespace catalog inside `(comment …)` tells the
   LLM "these definitions aren't real," which is the opposite of true (they ARE
   loaded and callable). The framing must be: *the catalog shows you the real,
   loaded definitions — call them*.

3. **Editor-fold support is irrelevant** — we don't fold in editors. The one
   advantage of `(comment …)`/`#_(…)` over a `;;;` pair (native s-expression
   folding) is moot because we own the viewer.

`^{:section …} (do …)` metadata wrapping (Gemini Q2 option 4) is also out: it's
*fragile* (attaching metadata to a `nil`/number/string return throws), and it
still wraps the forms so the section isn't independently meaningful.

**Verdict:** paired `;;;` comments. Valid Clojure, eval-transparent, our-viewer
foldable, framing-consistent, and it's the register the transcript already uses.

### 1.4 Prose → `;;` / `;;;` (the bare-prose fix)

Every prose line in every section becomes a comment. Two registers:

- **`;;`** — the AGENT's voice (its own narration; what it would type).
- **`;;;`** — the RUNTIME's voice (structure, headings, section bounds, the
  masthead's "this is your live REPL" explainer, status).

`system-text` is the runtime explaining the runtime → all `;;;`. A turn's
narration the agent wrote → `;;`. This is the single most important fix: it
turns the masthead from "invalid Clojure" into "a valid `;;;` comment block."

---

## 2. Namespace blocks — `(in-ns …)` vs `(ns …)`

### 2.1 The semantic difference (Gemini Q3 — high-confidence, verified against codebase)

- **`(ns the.ns (:require …))`** is a *macro*. It creates the ns, **auto-refers
  `clojure.core`** (≈775 mappings: `defn`, `let`, `println`, …), processes
  `:require`/`:import`/`:refer-clojure`. It is a namespace *definition* / DSL.
- **`(in-ns 'the.ns)`** is a *function*. It `findOrCreate`s the ns and points
  `*ns*` at it — that's all. A namespace entered via bare `in-ns` that was
  never otherwise set up is **bare**: it has the default `java.lang` imports
  (on JVM) but does **NOT** refer `clojure.core`, so `(defn …)` / `(println …)`
  in it throw *"Unable to resolve symbol: defn"* until you
  `(clojure.core/refer-clojure)`.

Gemini cited this exact pattern live in the codebase
(`test/seon/dev/verify_test.clj` `create-test-ns!`, and `src/seon/flow/pool.clj`
`setup-namespace!` which does `(create-ns …) (in-ns …) (clojure.core/refer-clojure) …`).

**Sequential eval is the key fact (Gemini Q3 Question B):** when forms are
evaluated as a top-level stream (which is exactly how a REPL/our eval loop
processes the transcript), each `(in-ns 'x)` correctly retargets the FOLLOWING
top-level forms. The compiler reads → compiles → executes each top-level form in
order, so `*ns*` is already flipped before the next form compiles. The ONLY
trap is nesting `in-ns` + `def` inside a single compiled unit (a `let`, a
non-top-level form): there, `def` resolves against the compile-time `*ns*`, not
the runtime one. Top-level `(do (in-ns …) (def …))` is special-cased and works.
**We render top-level forms, so we're in the safe regime.**

For our CLJS/sci runtime, `reference-code/sci/src/sci/impl/namespaces.cljc`
exposes `sci-in-ns` / `sci-create-ns` (`set-namespace!`); sci manages namespaces
in its env. Same top-level-sequential semantics apply; the implementer should
confirm sci's `in-ns` refers behavior on a freshly-created ns (sci's defaults
differ from JVM Clojure), but again we never create a bare ns and then call
`defn` in it from the transcript — agent home namespaces are pre-built with the
seon refers, and we render fully-qualified calls.

### 2.2 Recommendation: `(in-ns …)` per transcript block, `(ns …)` only for the catalog body

**Transcript / readline blocks → `(in-ns 'x)`.** This is what the spec already
specifies (§"Channels": "`(in-ns 'x)` flips it to `x=>`"). Reasons:

- **One line, fewer tokens, less noise** than a full `(ns …)` header — the LLM
  sees a clean context-switch, not a re-declaration.
- **Correct semantics** for "switch to an already-loaded ns" — which every
  agent-referenced ns is (they're loaded at boot / on define).
- **Matches the readline sugar** the agent is taught to read (`x=>`).
- Re-evaluating a full `(ns …)` mid-session would re-run its `:require`s and
  emit redefinition warnings; `in-ns` is the idiomatic "just switch" verb
  (Gemini Q3 §4: "Use `in-ns` … to switch to a namespace that has already been
  fully loaded").

**The `:namespaces` catalog body → full `(ns …)`.** This section's job (per
`ctx.cljs` `render-namespace`, line ~1190 `:full` mode) is to show each
namespace's *definition*: its real `(ns foo (:require …))` header followed by
its `defn`s/schemas. There the `(ns …)` form is correct and load-bearing — it's
literally the namespace's source, and it documents the requires the agent can
rely on. (This is the "code IS the database" principle — the catalog renders
the analyzer's stored source, which begins with the real `(ns …)`.)

So the rule is **semantic, not stylistic**: `(ns …)` where a block *defines* a
namespace; `(in-ns …)` where a block *switches into* an already-defined one.

### 2.3 A little magic (owner's note: "we can inject namespaces")

Because we own the render, we can guarantee the eval'able invariant holds
*without* burdening the agent: the runtime injects the `(in-ns 'x)` line at each
context switch (the agent never types it — it just writes the form, and the
runtime knows which ns it landed in and prepends the switch on render). This is
already the model (the readline tracks current ns; `in-ns` history is
derivable). Magic that's invisible to the agent but keeps the artifact valid is
exactly the right kind here.

---

## 3. Large-data elision

### 3.1 The hard constraint: print-truncation is NOT eval'able (Gemini Q4)

`*print-length*` / `*print-level*` produce output that is **not readable
Clojure** and therefore breaks the whole-context-eval'able invariant:

- `(0 1 2 ...)` — `...` reads as a symbol; in a call position it errors.
- `{:a 1 :b 2 ...}` — odd number of map forms → **reader exception**.
- `[[#]]` (`*print-level*`) — `#` is not a valid token → **syntax error**.

> "Built-in `*print-length*` and `*print-level*` truncation are designed
> strictly for human console viewing and should **never** be used for
> transcripts that need to remain executable." — Gemini elision guide §3

This is decisive: any elision we use must read back cleanly.

### 3.2 What Seon already does (the var-handle pattern)

`format-eval-row` (`ctx.cljs` ~475) already renders eval results the right way:

```clojure
;; add 1 and 2
(+ 1 2)
;;=> 3 ;; result/EVLabc-123
```

- The value is a **`;;=>` comment** (so re-eval runs only the form).
- `result/<id>` is a **live var handle** — the agent derefs it to reuse the
  value, and the literal value is never re-inlined.
- A clipped value appends **`(N of M)`** so the agent knows it's a partial view
  and how big the whole is.

This IS the "Var-Store / Live-Handle pattern" Gemini calls the gold standard
(Q4 §4): bind the big value to a var, render only a summary + handle + how to
inspect it. Seon already has it for eval results. **Keep it; it's correct.**

### 3.3 Extend it: an explicit, eval-safe elision tag for in-band data

For data the runtime emits that is NOT an eval result — a giant catalog row, a
store inventory, a huge map shown inside a section — we want the same "summary +
handle, never inline the literal" treatment, but it appears as in-band data, not
a trailing `;;=>` comment. Two eval-safe options (Gemini Q4 §1–2):

**Option A — tagged-literal placeholder (recommended for in-band data):**

```clojure
#seon/elided {:seon.elide/count   4823
              :seon.elide/type    :vector
              :seon.elide/handle  result/a1
              :seon.elide/preview [{:seon.kb/text "consult the store…"} ...]}
```

Register the reader so it ALWAYS reads (constraint #1 — the transcript must
never throw on read). Two layers of safety (Gemini Q4):

1. Register `seon/elided` in `*data-readers*` (or its CLJS/sci equivalent) →
   reads to a small placeholder record carrying the metadata.
2. Bind a `*default-data-reader-fn*` fallback so even an UNregistered tag reads
   to a `TaggedLiteral` instead of throwing. (Belt-and-suspenders; in our own
   runtime the tag is always registered.)

The tag is self-documenting to the LLM (`#seon/elided {…count 4823… handle
result/a1}`), stays valid Clojure, and the `:seon.elide/handle` points at the
live var the agent can deref/slice.

**Option B — plain map + metadata (zero-setup-safe):**

```clojure
^{:seon.elide/elided true} {:seon.elide/count 4823 :seon.elide/handle result/a1}
```

100% readable on any reader with no custom tag registration. Slightly less
visually distinct than a `#tag`. Use this if we ever want transcripts to read
in a stock REPL with zero seon setup.

**`#_` for inline elision** (Gemini Q4 §2) is valid but limited — the discarded
form must still READ (be lexically well-formed), so
`[:a :b :c #_"4820 more elided" ]` works but `#_(… unbalanced` does not. Useful
for "drop a tail and leave a note" inside an otherwise-shown literal; not a
general large-data mechanism.

### 3.4 What to borrow from the data browsers (Gemini Q4 §5)

Reveal/Portal/REBL/Morse never string-serialize huge values: they keep the live
JVM object and render a *window* (lazy paging), tracking truncation in metadata
(`reference-code/reveal/.../memory.clj` uses a `:truncated` count key and
renders `"…N more"`). For a TEXT transcript we can't lazy-page, but we borrow
the principle: **render a bounded preview + a count + a live handle, and give
the agent inspect verbs** (`(count result/a1)`, `(take 5 result/a1)`,
`(seon.db/store-inventory …)`). The agent pages by writing Clojure against the
handle — which is exactly the live, eval'able loop we want.

Orchard's `pp.clj` shows the truncation-with-`"..."` approach
(`reference-code/orchard/src/orchard/pp.clj`) — good for humans, but its `"..."`
output is the non-eval'able trap §3.1; we deliberately do NOT copy it into the
transcript.

### 3.5 Recommendation

- **Eval results** → keep `format-eval-row`'s `;;=> <summary> ;; result/<id>
  (N of M)`. No change needed.
- **In-band large data** (catalog/inventory rows) → render
  `#seon/elided {:seon.elide/count … :seon.elide/handle result/<id>
  :seon.elide/preview …}` with a registered reader + default-data-reader-fn
  fallback. The handle is a real var; the agent slices it with Clojure.
- **NEVER** `*print-length*`/`*print-level*` in transcript output.
- Schema-register `:seon.elide/*` keys (count, type, handle, preview) so the tag
  body is itself validated namespaced data — consistent with the data rules.

---

## 4. Live rendering + cache layering — swapping sections in/out without breaking the prefix

The context is **re-generated live every turn**. It SHOULD change every turn —
but almost all of the change must happen at the **bottom**, because the **top is
a cache prefix** and every byte that changes above the boundary invalidates the
provider's prompt cache from that point down. The demarcation scheme in §1–3 is
not just cosmetic: it's what makes a section **cleanly swappable** (a known
START and END means the composer can drop/replace a whole region as one unit
without disturbing its neighbors' bytes). This section ties demarcation to the
caching contract.

### 4.1 Seon already has the boundary — build ON it, don't reinvent

`ctx.cljs` already implements this exact model:

- **`stable-boundary`** (line ~1464) — a delimiter comment
  `;; ──── ctx cache boundary — everything above this line is the byte-stable
  prefix; everything below changes per turn ────`, built by string
  concatenation so it can never appear verbatim in rendered source.
- **`split-context`** (line ~1470) — splits the one assembled string into
  `:seon.render/stable-text` (cacheable prefix) + `:seon.render/volatile-text`
  (per-turn tail), so a provider adapter caches the prefix and sends only the
  tail as the live message. (Provider-neutral: the render emits ONE string with
  a marker; the adapter decides how to map that to its cache API.)
- **`core-default-ctx`** (line ~1485) — sections are **priority-ordered
  top→bottom = static→volatile**: `:system` (10) → `:namespaces` (20) →
  `:your-entity` (30) → `:live-tile` (35) → `:warnings` (40) → `:open-todos`
  (45) → `:relevant-source` (48) → `:inventory` → `:transcript` (the live
  bottom). The cache boundary is dropped after `:namespaces`.

So the architecture the user describes is **already the shipped design**. The
demarcation work in this report must *respect and reinforce* this, not fight it.

### 4.2 The three cache tiers (and which sections live in each)

Think of the context as three concentric bands, outermost = most stable:

| Tier | Sections | Churn | Cache role |
| --- | --- | --- | --- |
| **A. Cluster-static** | `:system` masthead; `:namespaces` core catalog | byte-identical across agents AND turns (only changes when the CORE codebase changes) | the big cacheable prefix — the bulk of the tokens, the bulk of the savings |
| **B. Agent-stable** | `:your-entity`, standing soul/instructions, agent-owned ns catalog | identical turn-to-turn for a given agent; differs per agent | a per-agent cache extension below the cluster prefix |
| **C. Live tail** | `:live-tile`, `:warnings`, `:open-todos`, `:relevant-source`, `:inventory`, `:transcript` | changes every turn (or whenever the DB changes) | never cached; this is where ALL per-turn motion belongs |

The owner's key insight — *"most agents won't be updating the core codebase, so
this minimizes byte-identical changes"* — is precisely why Tier A is huge and
stable: the core `:namespaces` body is the same forms every turn for every agent
that isn't editing the core. That's the cache win. The job of the render is to
keep **every per-turn change in Tier C** and never let volatility leak upward.

### 4.3 How demarcation enables clean swapping (the load-bearing link)

A section with a known START and END marker (§1's `;;; ┌─ <name> ─` /
`;;; └─ end <name> ─` pair) is a **self-contained, replaceable unit**. This buys
three things the cache contract needs:

1. **Whole-section swap without byte-disturbing neighbors.** When `:warnings`
   goes from 3 warnings to 0, the composer drops the entire
   `;;; ┌─ warnings ─ … └─ end warnings ─` region. Because the region is
   bracketed, removing it leaves the surrounding sections byte-identical — the
   diff is exactly one contiguous block, never a ragged edit that ripples. (A
   section that "vanishes when fixed" is the reactive-context default; the
   bracket is what makes the vanish *byte-clean*.)
2. **Stable boundary placement.** The cache boundary marker is emitted right
   after the last Tier-A/B section's END marker. As long as no Tier-A/B section
   reorders or rewrites, every byte above the boundary is identical → full cache
   hit. The composer's only rule: **Tier-A/B sections render in a FIXED order
   with FIXED formatting**; only their CONTENT changes when the core/agent
   actually changes (rare).
3. **Idempotent prefix.** Section START/END markers must themselves be
   byte-stable — NO timestamps, ids, or counts in a Tier-A/B header (the
   `system-text` docstring already calls this the "CACHE-PREFIX invariant: no
   timestamps, no ids, no counts"). Counts/timestamps belong ONLY in Tier-C
   headers (the `;;; ── turn N · <time> · loop K/cap ──` line, which is supposed
   to change every turn). Putting "live REPL · 14:03:22" in the masthead would
   silently bust the entire prefix every turn — exactly the trap to avoid.

### 4.4 The smart swap strategy — a section is a pure fn of the DB, gated by a stability class

This is the recommendation, and it composes cleanly with §1–3:

- **Every section is `(section-fn db agent-id) → string`** (already true —
  `:seon.render/ai` symbols). The string is fully bracketed by its `;;;`
  START/END markers, internally valid Clojure (forms + `;;`/`;;;` comments +
  `#seon/elided` tags), and **empty-string = section omitted** (already the
  composer convention).
- **Each section declares a stability class** `:seon.ctx/cache-tier` ∈
  `{:cluster-static :agent-stable :live}`. The composer:
  1. sorts by priority (already does),
  2. renders each section,
  3. emits the cache-boundary marker after the last non-`:live` section,
  4. (optional, future) memoizes `:cluster-static` section strings keyed by the
     core's content-hash so they're not even re-rendered when the core is
     unchanged — pure perf, doesn't change bytes.
- **Cache-busting guard (cheap, high-value):** a dev/test assertion that renders
  the prefix twice for the same DB snapshot and fails if the two prefixes differ
  byte-for-byte. This catches the #1 caching bug (a stray timestamp/count/id
  sneaking into a Tier-A/B section) the moment it's introduced. The spec already
  treats "the substrate must never break its own format rules" as load-bearing;
  this is the same discipline applied to cache-stability.

### 4.5 Why this is better than swapping raw strings

Without bracketed sections, "swap a region in/out" is a fuzzy string operation —
you can't tell where one section ends and the next begins, so any change risks
disturbing adjacent bytes (busting cache) or producing invalid Clojure (a
half-removed form). With the `;;;` START/END brackets:

- the boundary is unambiguous to the composer (drop/replace by bracket),
- unambiguous to the LLM (it reads "this region is X, runtime-owned"),
- unambiguous to the debug viewer (fold by bracket),
- and each region is independently valid Clojure, so removing/replacing one can
  never produce a torn form.

**One mechanism — the `;;;` bracket — serves demarcation, foldability, AND
cache-clean swapping.** That's the turtles-all-the-way-down payoff: the same
marker the agent reads as "section boundary" is the seam the composer cuts on
and the line the cache boundary sits after.

### 4.6 Net guidance for the build

1. Keep the existing `stable-boundary` + `split-context` + priority-ordered
   `core-default-ctx`. They ARE the cache-layering mechanism; this report just
   formalizes the section brackets that make swapping byte-clean.
2. Add `:seon.ctx/cache-tier` to the section schema; place the boundary after
   the last non-`:live` section programmatically (don't hard-code its position).
3. Enforce the CACHE-PREFIX invariant with a byte-identity test on the prefix.
4. Bracket EVERY section with `;;; ┌─ <name> ─` / `;;; └─ end <name> ─` so each
   is a swappable, foldable, individually-valid-Clojure unit.
5. All per-turn motion (time, counts, inbound, results, live-tile, warnings)
   lives in Tier C, at the bottom — never above the boundary.

---

## 5. Putting it together — a sketch of the eval'able context

```clojure
;;; ═══════════════ seon · my.agent.seon · live REPL ═══════════════════════
;;; This is your live REPL — a Clojure session backed by the database. The
;;; history below is real and ALWAYS current: it re-derives from the DB every
;;; turn. You write Clojure forms and ;; comments. After each form the runtime
;;; evaluates it and shows the value as `;;=> …` — that's how results arrive.
;;; ── end masthead ─────────────────────────────────────────────────────────

;;; ┌─ namespaces (the loaded core you can call) ────────────────────────────
(ns seon.db
  "Sole DB API. ...")
(defn query "..." {:malli/schema ...} [db q] ...)
;; (full real source of each loaded ns — these ARE loaded and callable)
(ns my.kb.system "...")
(defn instructions "..." [] ...)
;;; └─ end namespaces ──────────────────────────────────────────────────────

;;; ┌─ store (large data elided to handles) ─────────────────────────────────
;; Inventory of what's in the store right now:
#seon/elided {:seon.elide/count 4823 :seon.elide/type :vector
              :seon.elide/handle result/inv
              :seon.elide/preview [{:seon.kb/text "consult the store…"} ...]}
;; Slice it with (take 5 result/inv) / (count result/inv).
;;; └─ end store ───────────────────────────────────────────────────────────

;;; ── turn 1 · 14:04:13 · loop 1/20 · my.agent.seon ───────────────────────
;;; ◀ from :user @ 14:04:12 — "refactor the foo namespace"
;; On it.
my.agent.seon=> (in-ns 'my.foo)
;;=> my.foo
my.foo=> (refactor)
;;=> {:moved 3 :ok true} ;; result/b7

;;; ── turn 2 · 14:09:40 · loop 2/20 · my.foo ──────────────────────────────
my.foo=>
```

Every line is valid Clojure: `;;;` (runtime structure), `;;` (agent/runtime
prose), `;;=>` (result comment), forms (real, runnable), `#seon/elided` (reads
to a placeholder). The agent can paste any block and it runs in the right ns.

---

## 6. Open implementation questions (for the build, not this research)

1. **sci `in-ns` refers semantics** — confirm in the live pod that
   `(in-ns 'already.loaded.ns)` in sci leaves `clojure.core`/seon refers intact
   (it should, since the ns is already built). Only the *fresh-ns* case is
   risky, and we don't render that. Verify with a one-liner against the pod.
2. **Reader registration in CLJS/sci** — `*data-readers*` and
   `*default-data-reader-fn*` exist in CLJS; confirm sci honors them for
   `#seon/elided`, or register the tag in sci's `:readers`. Read
   `reference-code/sci` for the `:readers` ctx option.
3. **HTML twin fold markers** — the debug viewer
   (`debug-view-section-twins-2026-06-18`) keys its fold on section name; align
   it to the `;;; ┌─ <name> ─` / `;;; └─ end <name> ─` pair so the text twin and
   HTML twin share one boundary definition (turtles-all-the-way-down).
4. **One banner style** — Seon currently mixes `;; ====` (code sections) and
   `;; ── name ──` (agent sections). Pick ONE for the transcript; the box-corner
   `;;; ┌─ … ─` / `;;; └─ … ─` reads cleanest as an open/close pair. Decide and
   apply uniformly.

---

## Appendix A — Reference-code survey (raw findings)

From an Explore agent over `/Users/sean/src/seon/reference-code/`:

### A.1 Section heading / banner styles

- `reference-code/nrepl/src/clojure/nrepl/util/jvmti.clj` — `;;; Agent unpacking`
  / `;;; Agent loading` (3-semicolon sub-section headings).
- `reference-code/cider-nrepl/src/cider/nrepl/middleware/test.clj` —
  `;;; ## Overview`, `;;; ## Test Results` (markdown inside `;;;`).
- `reference-code/datahike/src/datahike/pod.clj` —
  ```clojure
  ;; =============================================================================
  ;; I/O Infrastructure
  ;; =============================================================================
  ```
- `reference-code/clj-kondo/analysis/src/clj_kondo/tools/ast.clj` —
  `;;;; Scratch` heading over a `(comment …)`.
- `reference-code/orchard/src/orchard/print.clj` —
  `;;;; Diffing support. Used for orchard.inspect/diff.`

### A.2 Rich Comment Forms `(comment …)`

- `reference-code/malli/dev/user.clj` — refresh/run-test scratch block.
- `reference-code/datahike/dev/user.clj` — schema/conn sandbox setup.
- `reference-code/nrepl/src/clojure/nrepl/tls_client_proxy.clj` — TLS proxy
  start scratch.

### A.3 `#_` reader-discard

- `reference-code/clojure-mcp/src/clojure_mcp/sse_core.clj` — `#_[…]` /
  `#_McpServerFeatures` eliding imports in a `:require` vector.
- `reference-code/clojure-mcp/src/clojure_mcp/core.clj` — `#_(.logging)`
  disabling an optional call.
- `reference-code/clojure-mcp/src/clojure_mcp/agent/langchain.clj` —
  `#_ToolParameter` in a require.

### A.4 Namespace switching in eval tooling

- `reference-code/nrepl/src/clojure/nrepl/middleware/interruptible_eval.clj` —
  `evaluator` resolves an optional `:ns` via `find-ns` and pushes
  `{#'*ns* explicit-ns}` thread binding.
- `reference-code/nrepl/src/clojure/nrepl/cmdline.clj` —
  `(when (:ns res) (set! *ns* (create-ns (symbol (:ns res)))))`.
- `reference-code/clojure-mcp/src/clojure_mcp/dialects.clj` — appends
  `"(in-ns 'user)"` after loading helpers.
- `reference-code/sci/src/sci/impl/namespaces.cljc` — `sci-create-ns`,
  `sci-in-ns` (`set-namespace!`); `reference-code/sci/src/sci/core.cljc`
  `create-ns`.
- (codebase) `src/seon/flow/pool.clj` `setup-namespace!` —
  `(create-ns …) (in-ns …) (clojure.core/refer-clojure) (require …)`.

### A.5 Large-data elision / truncation

- `reference-code/orchard/src/orchard/pp.clj` — `*print-length*` truncation
  emitting `"..."` (human-only; non-eval'able).
- `reference-code/orchard/src/orchard/trace.clj` — binds
  `*print-length* 100`, `*print-level* 5`.
- `reference-code/reveal/src/vlaaad/reveal/memory.clj` — `:truncated` count in
  metadata, renders `"…N more"`; bounded priority-queue window over a coll.

---

## Appendix B — Raw Gemini answers (verbatim)

### B.1 Q1 — Section heading conventions + end-of-section

```
Here is a detailed breakdown of Clojure section heading and comment conventions, along with how editors and tools handle section boundaries.

---

### Question 1 — Section Heading and Comment Conventions

In Clojure, every semicolon-based comment is ignored by the reader at evaluation time, meaning they are fully REPL-compatible. However, the Clojure community adheres to standard Lisp comment conventions (inherited from Emacs Lisp) that dictate how many semicolons to use. These levels represent a structural hierarchy that Clojure-aware editors recognize for auto-indentation and outlining.

#### The Semicolon Hierarchy

The Clojure Community Style Guide (by Bozhidar Batsov) and Lisp conventions define four tiers of comments:

##### 1. Four Semicolons (;;;;) — Section Headings / Major Dividers
Used to outline or separate major sections of code or to describe very high-level ideas. These are always placed at the left margin (column 0).
* Style Guide Rule: "Write heading comments with at least four semicolons."
* Example:
  ;;;; System Prompt & Behavior Guidelines

##### 2. Three Semicolons (;;;) — Top-Level Comments / Sub-sections
Used for comments that live at the top-level of the file but are outside of any specific function definition. They are also placed at the left margin (column 0).
* Style Guide Rule: "Write top-level comments with three semicolons."
* Example:
  ;;; Helper functions for handling schema parsing

##### 3. Double Semicolons (;;) — Code Block / Line Comments
Used for comments that occupy their own line but are nested inside a function or data structure. Editors auto-align these to the current indentation depth of the surrounding code block.
* Style Guide Rule: "Write comments that occupy their own line with double semicolons."
* Example:
  (defn parse-input [raw-data]
    ;; Validate the schema before processing
    (if (valid? raw-data)
      (process raw-data)
      (throw (ex-info "Invalid data" {}))))

##### 4. Single Semicolon (;) — Margin / Inline Comments
Used for trailing inline comments placed to the right of code on the same line.
* Style Guide Rule: "Write margin comments with a single semicolon."
* Example:
  (def timeout-ms 5000) ; Timeout set to 5 seconds for slow API responses

---

#### Common Section-Divider Banner Styles

Real-world codebases utilize various banner styles depending on aesthetic preferences. Here are the three most common conventions:

##### Style A: The Classical ASCII Equal Banner (Common in Older/Large Codebases)
This style uses a visual horizontal line to immediately catch the eye. It is commonly found in older Lisp libraries and larger Clojure namespaces.
;;;; =========================================================================
;;;; Section 1: System Prompt
;;;; =========================================================================

##### Style B: The Minimalist Markdown/Org-mode Style (Recommended for Tooling)
Modern Clojure projects (especially those using the outshine Emacs package) adopt Org-mode heading style inside comments. This enables hierarchical folding and outline cycling within the file.
;;;; * Section 1: System Prompt
;;;; ** Subsection 1.1: System Instructions

##### Style C: Unicode Box-Drawing and Line Dividers (Modern / Premium Look)
Used in modern, highly polished developer tools and configuration templates to look sleek and readable in modern terminals.
;;;; ── Section 1: System Prompt ───────────────────────────────────────────────

---

### Question 2 — Marking the END of a Section

Clojure has no formal, built-in visual convention or syntax for closing a commented section. Lisp files are designed to be read as a continuous flow of forms.

However, tools, editors, and developers solve the boundary problem in a few distinct ways:

#### 1. Implicit Section Boundaries (The Standard Way)
Editors configured with outline-minor-mode (Emacs) or language servers using clojure-lsp determine the end of a section implicitly.

A section heading (e.g., ;;;; Section 1) begins a region. The editor parses all subsequent lines as the body of that section until it encounters another heading of equal or higher hierarchy (e.g., another ;;;; or ;;;), or until it reaches the end of the file.

For example, when folding "Section 1", Emacs or VS Code folds everything up to the line starting with ;;;; Section 2:

;;;; Section 1: System Prompt
(def prompt "You are a Clojure assistant...")

;;;; Section 2: Function Catalog    <-- This implicitly ends Section 1
(defn call-tool [name args] ...)

#### 2. Editor-Specific Region Markers (Explicit Folding)
If you need to explicitly group and collapse arbitrary blocks of code, both VS Code (Calva) and IntelliJ (Cursive) support standardized IDE region comments:

##### For VS Code / Calva (#region and #endregion):
;; #region System Prompt Details
(def system-message "Always reply in Markdown.")
(def agent-rules [:concise :helpful])
;; #endregion

##### For IntelliJ IDEA / Cursive (<editor-fold>):
;; <editor-fold desc="Function Catalog">
(defn tool-a [] ...)
(defn tool-b [] ...)
;; </editor-fold>

#### 3. Traditional Emacs Page Breaks (^L / Form Feed)
In older Lisp and Clojure codebases, developers use the ASCII Form Feed control character (Control-L, represented as ^L or \f, character code 12) on a line by itself to divide a file into logical "pages."
* Emacs commands C-x [ (backward-page) and C-x ] (forward-page) use these markers to navigate.
* With packages like page-break-lines installed, the ^L character renders as a clean horizontal divider spanning the width of the editor window, serving as both an explicit end to the previous page and a start to the next.

#### 4. Rich Comments ((comment ...))
For interactive development and execution contexts (like an agent REPL transcript), you can wrap a whole section in the (comment ...) form.
Because Clojure is homoiconic, syntax-aware editors (AST-based folding like TS-fold or structural editors like ParEdit) naturally treat the (comment ...) form as a single structural block. The closing parenthesis ) explicitly marks the end of the section.

(comment
  ;; Section: Active agent memory
  (def chat-history [])

  (add-message! :user "Hello World")
  ) ;; <-- The closing parenthesis explicitly marks the structural end
(Note: The Clojure community style guide makes an exception for (comment ...) blocks, allowing the trailing parenthesis to sit on its own line to make adding/removing forms inside the comment easier.)
```

### B.2 Q2 — `(comment …)` vs `#_` vs marker comments vs metadata

```
Here is a detailed comparison of the four mechanisms for wrapping and demarcating Clojure forms within a REPL transcript, evaluated against your goals: valid Clojure guarantee, editor foldability, and inner form evaluation.

---

### Comparison of Mechanisms

#### 1. (comment ...) — Rich Comment Form (RCF)
The Rich Comment Form is the idiomatic Clojure way to embed scratchpad code or metadata sections.

(comment
  (defn process-agent-input [x]
    (* x 2))

  (process-agent-input 21))

* Valid Clojure Guarantee: Yes (Strict). Because comment is a standard macro, the Clojure reader must parse it. If any form inside the block contains unbalanced brackets or invalid syntax, the reader will throw an exception during compilation or loading.
* Whole-Form Evaluation: Returns nil instantly. It is safe to evaluate the entire file/buffer because comment forms do not execute their bodies at load time.
* Inner-Form Evaluation: Excellent.
  * Calva (VS Code): Pressing Alt+Enter (evaluate top-level form) inside a (comment ...) block evaluates the current inner form under the cursor, not the whole (comment) block. Calva explicitly treats RCFs as evaluation containers.
  * CIDER (Emacs): cider-eval-defun-at-point (C-c C-c) inside a (comment) form evaluates the top-level form inside the comment, bypassing the wrapper.
  * Cursive (IntelliJ): "Send top-level form to REPL" similarly evaluates the inner form under the caret.
* Folding: Excellent. Every Clojure editor natively supports folding parenthesized s-expressions.

---

#### 2. #_ Reader Discard Macro on a wrapping form
This uses the reader-discard macro to tell the Clojure reader to completely ignore the next parsed form.

#_(do
    (defn process-agent-input [x]
      (* x 2))

    (process-agent-input 21))

* Valid Clojure Guarantee: Yes. The reader parses the form before discarding it. Invalid Clojure syntax inside the #_(...) block will throw a read error when the file is parsed.
* Whole-Form Evaluation: The reader discards the form entirely. If you evaluate the whole block, nothing is sent to the compiler/REPL.
* Inner-Form Evaluation: Moderate to Poor.
  * While you can place your cursor on an inner s-expression (like (process-agent-input 21)) and evaluate it using "evaluate current form" (Ctrl+Enter / Cmd+Enter in Calva), "evaluate top-level form" will evaluate the entire #_(do ...) block, producing no output.
  * Loading the namespace or compiling the file will completely ignore the forms, making it harder to inspect global state changes.
* Folding: Good. Editors will fold the (do ...) form, but the #_ prefix often causes editors to style the entire block as commented out (e.g., greyed out), which can reduce readability in debug viewers.

---

#### 3. Paired Marker Comments
Line-based comments that act as visual boundaries.

;; >>> section START
(defn process-agent-input [x]
  (* x 2))

(process-agent-input 21)
;; <<< section END

* Valid Clojure Guarantee: No. If the rest of the transcript loads fine, the forms inside are parsed. However, the comments themselves do not enforce structural syntax wrapping.
* Whole-Form Evaluation: If you load the file, every single form inside the region will be evaluated immediately.
* Inner-Form Evaluation: Excellent. They are just normal top-level forms.
* Folding: Poor / Config-dependent. Standard Clojure editors fold s-expressions, not arbitrary line-comment ranges. You would have to configure custom folding rules (like VS Code's #region / #endregion syntax) which CIDER and Cursive do not support out of the box.

---

#### 4. Metadata-Tagged Forms
Attaching metadata to a wrapping evaluation form (like do).

^{:section :system}
(do
  (defn process-agent-input [x]
    (* x 2))

  (process-agent-input 21))

* Valid Clojure Guarantee: Yes. Parses normally.
* Whole-Form Evaluation: Unsafe / Fragile. Evaluating the block will evaluate the body and attempt to attach the metadata to the returned value. If the last form inside the do block evaluates to nil, a number, a string, or a boolean, Clojure will throw an exception: Can't attach metadata to nil or Can't attach metadata to java.lang.Long.
* Inner-Form Evaluation: Excellent. Forms are standard evaluatable expressions.
* Folding: Excellent. Folds the standard s-expression.

---

### Comparison Summary Matrix

| Criterion | 1. (comment ...) (RCF) | 2. #_(do ...) (Reader Discard) | 3. ;; START / ;; END | 4. ^{:meta} (do ...) |
| Valid Clojure Guarantee | Yes (Strictly parsed) | Yes (Strictly parsed) | No (No wrapper structural guarantee) | Yes (Strictly parsed) |
| Foldability in Editors | Excellent | Good (Styles block as greyed out) | Poor (No native s-expression folding) | Excellent |
| Inner Form Eval | Excellent (Special editor support) | Moderate | Excellent | Excellent |
| Whole Block Eval | Safely returns nil | Safely discarded (No-op) | Evaluates all forms inside | Fragile (Throws error on primitives/nil) |

---

### Recommendation: Which is Best?

For your goals, (comment ...) (Rich Comment Form) is the best choice.

* Why it wins:
  1. It provides a 100% valid Clojure guarantee (syntax must be correct to parse).
  2. It is universally foldable in every Clojure editor because it is a standard list s-expression.
  3. Every modern Clojure editor (Calva, CIDER, Cursive) has first-class support for evaluating forms inside a (comment ...) block without having to copy-paste or strip the wrapper.
  4. Evaluating the entire block safely returns nil without polluting the REPL state or crashing.
```

> NOTE (report author): Gemini recommends `(comment …)` for the GENERAL
> wrap-a-section question. For OUR specific paste-and-run + live-framing
> constraints we explicitly reject wrapping (see §1.3) — the section forms must
> stay runnable, so we use paired `;;;` comments instead. The matrix is still
> valuable for understanding the tradeoffs.

### B.3 Q3 — `ns` vs `in-ns`, REPL `*ns*` tracking

```
### Question A: Switching Namespaces (ns vs in-ns)

In Clojure, managing namespaces revolves around the dynamic var clojure.core/*ns* which points to an instance of clojure.lang.Namespace. While both ns and in-ns alter this var, they differ significantly in their mechanism, timing, and defaults.

#### 1. Under the Hood: Macro vs Function
* in-ns is a function (clojure.core/in-ns). It evaluates its argument and expects a quoted symbol representing the target namespace:
    (in-ns 'my-namespace)
* ns is a macro (clojure.core/ns). It does not evaluate its arguments (hence no quote is needed) and acts as a declarative domain-specific language (DSL) for configuring namespace dependencies:
    (ns my-namespace
      (:require [clojure.string :as str]))

#### 2. What in-ns does NOT do that ns does
When you evaluate (in-ns 'brand.new.ns) where the namespace does not already exist, the Clojure runtime calls clojure.lang.Namespace/findOrCreate to initialize it. However:
* No Auto-Refer of clojure.core: The newly created namespace is completely bare. It does not refer to any core Clojure vars (defn, let, println, if-not, etc.).
* Automatic Java Imports: It does, however, automatically import classes from java.lang (e.g., java.lang.String, java.lang.Exception).

If you inspect the namespace mappings via clojure.core/ns-map, a freshly initialized in-ns namespace contains only 95 default java.lang class mappings. In contrast, running (ns brand.new.ns) automatically runs a refer operation on clojure.core, populating it with 775 mappings (the core Clojure functions and macros + the default Java classes).

#### 3. Compilation Errors in a Fresh in-ns Namespace
If you switch to a brand new namespace via in-ns and immediately try to define a function, the compilation will fail:
(in-ns 'brand.new.ns)
(defn my-func [] :hello)
;; => Syntax error compiling: Unable to resolve symbol: defn in this context
Because the Clojure reader and compiler resolve symbols against the namespace currently bound to *ns*, and defn is not mapped in brand.new.ns, the compiler cannot find the definition.

To make the namespace usable, you must either:
1. Fully qualify your calls: Use clojure.core/defn and clojure.core/refer.
2. Manually refer core: Call (clojure.core/refer 'clojure.core) or (clojure.core/refer-clojure).

This pattern is explicitly utilized in your project's test files, such as inside create-test-ns! in verify_test.clj:
(defn- create-test-ns!
  "Create a temporary namespace with test functions."
  [ns-sym fns]
  (create-ns ns-sym)
  (in-ns ns-sym)
  (clojure.core/refer 'clojure.core) ; <-- Manually referring core to make standard functions available
  (clojure.core/require '[clojure.test :refer :all])
  (clojure.core/require '[malli.core :as m])
  (doseq [[fn-name fn-body] fns]
    (eval fn-body))
  (in-ns 'seon.dev.verify-test))

#### 4. When to Use Which
* Use ns: At the top of a file or during initial source code declaration. It is declarative, handles dependencies (:require, :import), allows exclusions, and bootstraps clojure.core.
* Use in-ns: When you want to switch context to a namespace that has already been fully loaded (e.g., in a REPL session, developer tools, or dynamic code execution scripts). It is a lightweight pointer update. If you used ns to switch back and forth in a REPL, it would re-evaluate the full declarative header, potentially reloading dependencies, clearing existing mappings, or throwing warnings about redefined vars.

---

### Question B: REPL Session Namespace Persistence and Semantics

#### 1. Namespace Tracking in REPL Sessions
A REPL maintains the "current namespace" state across separate requests through dynamic var bindings:
* Socket REPL: Operates on a loop reading from an input stream, calling eval, and writing to an output stream on a dedicated thread. Since *ns* is a dynamic var, modifying its value (e.g., using in-ns) mutates the thread-local binding for that loop.
* nREPL (Network REPL): Because nREPL is message-based, it relies on sessions to preserve state across stateless TCP connections.
    * An nREPL session is a map of dynamic Clojure var bindings (such as *ns*, *warn-on-reflection*, etc.).
    * When an eval request is received, nREPL retrieves the session, sets the thread's bindings using clojure.core/with-bindings, compiles and executes the form, and then persists any mutations to those bindings back to the session.
    * Additionally, nREPL supports an optional ns key in its request messages. If sent, the nREPL middleware binds *ns* to the namespace specified by ns prior to evaluating the code, temporarily overriding the session's active namespace.

This process is visible in your codebase within setup-namespace! in pool.clj, where forms are dynamically prepared and sent to an nREPL socket connection:
(defn- setup-namespace!
  "Create a namespace on the agent JVM and eval forms into it."
  [port ns-sym forms]
  (let [ns-str (str ns-sym)
        setup-code (str "(do"
                        " (create-ns '" ns-str ")"
                        " (in-ns '" ns-str ")"
                        " (clojure.core/refer-clojure)"
                        " (require '[malli.core :as m])"
                        " (require '[clojure.core.async :as async])"
                        " (require '[cheshire.core :as json])"
                        " :ok)")]
    (nrepl-eval! port setup-code)
    (doseq [form forms]
      (nrepl-eval! port (pr-str form)))
    ns-str))

#### 2. Semantics of Concatenated Eval Strings
If you concatenate multiple forms—each separated by (in-ns '...)—and evaluate them as one big string (e.g., via clojure.core/load-string or streaming them directly to a REPL):

Yes, each in-ns correctly retargets the following forms.

This is because Clojure's compiler reads, compiles, and evaluates forms sequentially when they are at the top level of a file or evaluation stream. The execution flow is:
1. Read form 1: (in-ns 'ns-a) -> Compile -> Execute. *ns* is now bound to ns-a.
2. Read form 2: (defn x ...) -> Compile (resolving symbols in ns-a) -> Execute. Var ns-a/x is defined.
3. Read form 3: (in-ns 'ns-b) -> Compile -> Execute. *ns* is now bound to ns-b.
4. Read form 4: (defn y ...) -> Compile (resolving symbols in ns-b) -> Execute. Var ns-b/y is defined.

#### 3. The Nested Form Caveat (Compilation vs. Runtime Execution)
While sequential evaluation works perfectly at the top level, it fails or behaves unexpectedly if the namespace switches are nested inside a single form that is compiled as a unit (such as a let block or inside a macro).

Consider this example:
(let []
  (in-ns 'foo)
  (clojure.core/refer 'clojure.core)
  (defn bar [] :val))

When evaluated, the Clojure compiler compiles the entire let form before executing any of its code:
1. Compile-time: The compiler encounters the defn form. Since compilation happens before any execution has occurred, the runtime state *ns* is still bound to the original namespace (e.g., user).
2. The compiler resolves the var bar relative to the compile-time namespace (user), compiling code that defines #'user/bar.
3. Run-time: The compiled bytecode executes.
    * (in-ns 'foo) runs and successfully switches the runtime value of *ns* to foo.
    * (clojure.core/refer 'clojure.core) runs inside foo.
    * The code for defn bar executes. However, because it was compiled to target the user namespace, the var is defined as #'user/bar, not #'foo/bar.
4. Result: *ns* is successfully mutated to foo, but the defined function is registered in the original user namespace.

> [!NOTE]
> Top-level do blocks (e.g. (do (in-ns 'foo) ...)) are treated as a special case by the Clojure compiler. When evaluated at the top level, the compiler expands and processes the subforms inside the do sequentially, avoiding the nesting issue and executing each step before compiling the next.

---

### Summary of Work

I analyzed and detailed:
1. The compiler and runtime differences between ns and in-ns.
2. What a fresh in-ns namespace lacks (e.g. clojure.core refers) and how to resolve it.
3. How REPL states and nREPL sessions manage the dynamic binding of *ns*.
4. The sequential evaluation rules of concatenated strings and the critical compilation differences when nesting namespace operations inside macros or local bindings.
5. Concrete references to these exact behaviors in your workspace files: verify_test.clj and pool.clj.
```

### B.4 Q4 — Large-data elision (summary + full guide)

Gemini's chat summary:

```
### 1. The Dynamic Var Registry (The Golden Standard)
For active, eval'able Clojure sessions, the most robust option is the Var-Store Pattern. When the REPL output size exceeds your threshold:
- Intern a var inside a dedicated namespace (e.g., user/r-1049281) containing the full collection.
- Print a summary annotation: ;; => #'user/r-1049281 with instructions on how the agent can slice it (e.g., via (take 5 user/r-1049281)).
- This keeps the text transcript lightweight, maintains 100% eval'ability, and enables the agent to programmatically inspect the dataset.

### 2. Safeguarding Reader Tags
If you use tagged literals such as #result/elided {:id "r-1234" :count 5000}:
- Reader tag fallback: Standard readers will crash with a No reader function for tag... exception if the tag is unregistered.
- Solution: Bind clojure.core/*default-data-reader-fn* during reads to return clojure.lang.TaggedLiteral objects when unknown tags are encountered. This ensures transcripts are always read-safe, even on vanilla REPL setups.

### 3. Avoiding Print-Truncation Pitfalls
- Avoid standard *print-length* or *print-level* settings for transcripts that must be read back; they produce invalid Clojure tokens (... and #) that cause reader syntax errors.

### Key Decisions for Your Architecture
1. Durable vs. Memory-Only Registry: Should elided data be persisted to a local file store (like a cache directory) or kept purely in the JVM memory of the active REPL? (For agent workflows, a memory-only atom is usually sufficient, but a disk-backed store allows restarting the agent session without losing historical data pointers).
2. Fallback Strategy: Would you prefer utilizing custom reader tags (which look more semantic in transcripts) or standard maps decorated with metadata (which require zero setup to read safely)?
```

Full guide Gemini wrote to
`~/.gemini/antigravity-cli/brain/9683ccb1-…/clojure_elision_guide.md`,
preserved verbatim:

```
# Guide: Clojure Data Elision & Annotation in AI REPL Transcripts

When building AI agent contexts based on eval'able Clojure REPL transcripts, exposing huge datasets (thousands of datoms, large maps/vectors) can quickly blow out the context window. However, truncating this data naively breaks Clojure's readability and evaluation.

This guide explores Clojure's reader mechanisms, identifies the tensions between human readability and machine readability, and provides a concrete, production-grade architecture to elide large data structures while keeping transcripts fully valid and queryable.

---

## 1. Tagged Literals (#my/tag <data>) for Annotation

Clojure allows custom syntax parsing via Tagged Literals. A tagged literal consists of # followed by a symbol (the tag), and then any valid Clojure form.

### How Custom Reader Tags Work

When the reader encounters #prefix/tag form, it reads the form and passes it to a registered reader function. This function returns the final Clojure object.

Custom tags are registered in two ways:
1. Declaratively (data_readers.clj): A file named data_readers.clj placed at the root of the classpath containing a map of tag symbols to parser symbols:
   ;; data_readers.clj
   {result/ref    my.repl.elision/read-ref
    result/elided my.repl.elision/read-elided}
2. Dynamically (*data-readers*): Dynamic binding of the clojure.core/*data-readers* var:
   (binding [*data-readers* (assoc *data-readers* 'result/ref read-ref)]
     (read-string "#result/ref \"r-1234\""))

### Defining the Reader Functions
Reader functions take one argument: the parsed Clojure form following the tag.

(ns my.repl.elision)

;; Representation of a reference in the transcript
(defrecord ResultRef [id])

(defn read-ref [form]
  (if (string? form)
    (->ResultRef form)
    (throw (IllegalArgumentException. "result/ref expects a string ID"))))

;; Representation of elided metadata
(defrecord ElidedResult [metadata])

(defn read-elided [form]
  (if (map? form)
    (->ElidedResult form)
    (throw (IllegalArgumentException. "result/elided expects a map of metadata"))))

### The Safety Trade-off: Handling Reader Errors
If a consumer reads a transcript containing #result/ref "r-1234" but does not have the custom tag registered, read-string will throw a reader exception:
> RuntimeException: No reader function for tag result/ref

To ensure transcripts are always readable on any standard Clojure REPL without requiring upfront classpath configuration, use these safety guards:

1. Leverage *default-data-reader-fn*:
   Before reading the transcript, bind *default-data-reader-fn* to a fallback function. When an unregistered tag is encountered, Clojure passes the tag symbol and the value to this fallback instead of throwing:
   (binding [*default-data-reader-fn* (fn [tag val] (clojure.lang.TaggedLiteral/create tag val))]
     ;; This now reads safely and returns a TaggedLiteral object
     (read-string "#result/ref \"r-1234\""))
2. Represent as Normal Map Literals with Metadata:
   Instead of using reader tags, represent elided values as standard map literals tagged with specific metadata:
   ^{:repl/type :ref} {:id "r-1234"}
   This is 100% safe, readable on any Clojure installation, and requires no custom reader setup.

---

## 2. The Discard Macro (#_) for comments

The discard reader macro #_ instructs the Clojure reader to read the next form but completely discard it from the AST.

[:a :b :c #_(... 4820 more elided ...)]

### Does the discarded form need to be valid Clojure?
Yes. The reader parses the form before discarding it. Therefore:
- #_(... 4820 more elided ...) is valid because ..., 4820, more, and elided parse as valid Clojure symbols and numbers inside a list form.
- #_(unclosed-parenthesis will fail with a reader error.
- #_({:key val :odd-elements}) will fail because a map must have an even number of forms.

### Recommendation for #_ Elisions
If you want to use #_ to embed human-readable elision descriptions, wrap the text in a string literal or map literal, which are guaranteed to read cleanly:
[:a :b :c #_"4820 items elided here"]
;; or
[:a :b :c #_{:elided 4820 :type :vector :id "r-1234"}]

---

## 3. Dynamic Printing Limits vs. Readability

Clojure provides dynamic variables to control how data structures are printed:
- *print-length*: Controls how many items in a collection are printed before truncating.
- *print-level*: Controls how deep nested collections are printed before truncating.

### The Tension
When *print-length* is set to 3, (range 10) prints as:
(0 1 2 ...)
When *print-level* is set to 2, [[[1]]] prints as:
[[#]]

### Why this breaks readability
- ... is parsed as a symbol. While ... is a valid symbol in Clojure, in a list it evaluates to whatever ... is bound to (usually unbound, causing an evaluation error).
- In a map: {:a 1 :b 2 ...} reads as an odd number of forms, causing a Reader Exception.
- # is not a valid token. Encountering # outside of a dispatch macro throws a syntax error.

Verdict: Built-in *print-length* and *print-level* truncation are designed strictly for human console viewing and should never be used for transcripts that need to remain executable.

---

## 4. The Var-Store (Live-Handle) Pattern

The cleanest, most idiomatic way to handle huge results in an interactive AI REPL is the Var-Store Pattern.

Instead of embedding the literal value directly in the transcript:
1. Bind the large value to a dynamically generated var in a dedicated namespace (e.g., user/r-1234).
2. Render only the Var symbol and metadata summaries in the transcript.
3. The agent is shown the Var handle and helper commands to query it.

### Example REPL Transcript Output
user> (db/query-large-dataset)
;; => #'user/r-1234
;; Info: 4823 datoms.
;; Use (take 5 user/r-1234) or (clojure.set/project user/r-1234 [:db/id]) to inspect.

### Why this is the gold standard for agents
- Keeps transcripts lightweight: The LLM's context window is spared.
- Maintains liveness: The data is active in the JVM. The agent can write Clojure code to programmatically filter, slice, or inspect the dataset using standard Clojure sequences.
- 100% Eval'able: The transcript runs top-to-bottom without throwing syntax or reader errors.

---

## 5. Lessons from Portal, Reveal, REBL, and Morse

Clojure data browsers handle massive data structures by avoiding string serialization:
1. tap> Integration: Values are sent as live JVM objects via clojure.core/tap>. The console prints a simple reference, and the tool renders the actual object out-of-band.
2. Lazy Paging: Portal and Reveal render vectors and maps dynamically as the user scrolls. They query the JVM heap for only the visible window of elements.
3. Data Inspection APIs: They allow navigating data path-wise (like get-in).

### What we can borrow for a text-based AI transcript
We can provide a set of lightweight inspect-fns in the REPL environment:
- (inspect r-1234): Prints a paginated summary.
- (inspect r-1234 :offset 100 :limit 10): Slices a subset.
- (keys-summary r-1234): Aggregates keys and types.

---

## 6. Concrete Recommendation

To achieve safe, eval'able Clojure transcripts that keep large data structures queryable, implement a Size-Aware Var-Store Interceptor.

### The Strategy
1. Monitor evaluation results.
2. If a result is under a threshold, print it normally.
3. If it exceeds the threshold (e.g., collection size > 100 or nesting depth > 4):
   - Save the object in a registry.
   - Intern a var inside user named r-<hash>.
   - Print a tagged literal #result/elided {:id "r-1234" :count 5000 :type :vector}.
   - Configure a default reader fallback so the tagged literal evaluates safely back to a placeholder or dereferences the live object if present.

### Implementation

(ns my.repl.elision
  (:import [clojure.lang Var TaggedLiteral]))

;; 1. The registry keeping live values in memory
(defonce registry (atom {}))

(defn register-value! [val]
  (let [id (str "r-" (System/identityHashCode val))
        ;; Generate an interned var in the 'user namespace for easy agent access
        v (intern 'user (symbol id) val)]
    (swap! registry assoc id val)
    {:id id :var v}))

;; 2. Size threshold check
(defn large-collection? [val]
  (cond
    (map? val)    (> (count val) 100)
    (coll? val)   (> (count val) 100)
    :else         false))

;; 3. Transform output for REPL printing
(defn prepare-result [val]
  (if (large-collection? val)
    (let [{:keys [id var]} (register-value! val)]
      (clojure.lang.TaggedLiteral/create
        'result/elided
        {:id id
         :type (cond (map? val) :map (vector? val) :vector :else :seq)
         :count (count val)
         :var-sym (symbol "user" id)}))
    val))

;; 4. Safely reading elided forms
(defn safe-read-string [s]
  (binding [*default-data-reader-fn*
            (fn [tag val]
              (if (= tag 'result/elided)
                (get @registry (:id val) val)
                (clojure.lang.TaggedLiteral/create tag val)))]
    (read-string s)))

;; 5. Human/Agent inspection helper
(defn inspect
  ([elided]
   (inspect elided 0 10))
  ([elided offset limit]
   (let [val (cond
               (instance? TaggedLiteral elided) (get @registry (get-in elided [:val :id]))
               (map? elided) (get @registry (:id elided) elided)
               :else elided)]
     (if (coll? val)
       (do
         (println (format "--- Slicing %d items starting at offset %d ---" limit offset))
         (take limit (drop offset val)))
       val))))

### How the REPL session flows with this architecture

;; --- TRANSCRIPT ---
;; Agent executes a query that yields 5,000 maps:
user> (def result (db/fetch-all-users))
;; => #result/elided {:id "r-1049281" :type :vector :count 5000 :var-sym user/r-1049281}

;; The transcript stays tiny. The reader safely parses the TaggedLiteral.
;; The Agent notes the var-sym 'user/r-1049281' and queries it:
user> (count user/r-1049281)
;; => 5000

user> (my.repl.elision/inspect user/r-1049281 0 3)
;; --- Slicing 3 items starting at offset 0 ---
;; ({:id 1 :name "Alice"} {:id 2 :name "Bob"} {:id 3 :name "Charlie"})

### Why this is the safest and most robust solution
1. Syntax Integrity: Tagged literals are fully standard Clojure syntax.
2. Error Prevention: Dynamic binding of *default-data-reader-fn* ensures that even if this transcript is read offline without custom reader setup, it will parse into clean data structures without throwing reader exceptions.
3. No Bloat: Avoids inlining large maps/vectors.
4. Complete Queryability: Since the result is bound to an active Var in the JVM, the agent can interact with it using all Clojure tools.
```

> NOTE (report author): the guide's examples are JVM-flavored
> (`System/identityHashCode`, `clojure.lang.TaggedLiteral`, `intern 'user`). In
> the CLJS/sci pod, substitute: a sci-registered `:readers` entry (or
> `*data-readers*`) for the tag, a `random-uuid`/seon id-gen for the handle, and
> the existing `result/<id>` var mechanism (Seon already interns these). The
> *pattern* (size-aware → intern var → render handle + summary tag) ports
> directly; the host APIs change.
