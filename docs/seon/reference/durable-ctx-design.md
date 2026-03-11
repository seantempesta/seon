# Reactive UI Architecture

**Status:** Vision / Research
**Date:** 2026-01-30

---

## Vision

The browser is a thin terminal to a Clojure server. Agents write pure Clojure - functions that update atoms, render hiccup. The system handles everything else: reactivity, persistence, user input, push updates.

**The Reagent experience, but server-side:**

```clojure
;; Agent writes normal Clojure
(defn add-signal! [{:keys [name price]}]
  (swap! *ctx* update :signals conj {:name name :price price}))

(defn render [_]
  [:main
   [:ul (for [s (:signals @*ctx*)] [:li (:name s)])]
   [:form {:on:submit (add-signal!)}
    [:input {:field :name}]
    [:button "Add"]]])
```

That's it. No signals, no Datastar attributes, no client-side state management. The system translates to whatever the underlying tech requires.

---

## Goals

1. **Agents write pure Clojure** - No framework-specific concepts leak into agent code
2. **Reagent-like mental model** - Atoms + render functions + reactivity
3. **Server owns all state** - Browser is a view layer, not a state container
4. **Incremental adoption** - Build on Datastar/Hyperlith, don't replace
5. **JS ecosystem access** - Can call into JavaScript when needed
6. **Multi-format render** - Same data renders as HTML (users) or EDN (AI agents)

---

## Core Concepts

### The `*ctx*` Atom

Every namespace instance has a durable atom. Agents read/write it like any atom:

```clojure
@*ctx*                                    ; read
(swap! *ctx* assoc :count 0)              ; write
(swap! *ctx* update :items conj item)     ; update
```

Changes to `*ctx*` automatically trigger re-render and push to connected clients.

**Durability:** STM-backed, survives restarts. Not a database - use Datalevin for heavy queries.

**Schema enforcement:** Every key must have a Malli schema. Invalid data throws.

### Render Functions

A render function takes format and returns hiccup:

```clojure
(defn render [{:keys [format]}]
  (case format
    :html [:main [:h1 "Hello"]]
    :edn  {:greeting "Hello"}))
```

If no render function defined, system provides default introspection view.

### Actions

User interactions trigger Clojure function calls:

```clojure
[:button {:on:click (increment!)} "+1"]
[:form {:on:submit (create-order!)} ...]
```

The system collects form data and passes it as the function's argument.

### Multi-Instance

Same code, different data:

```
seon.email:work     → *ctx* with work data
seon.email:personal → *ctx* with personal data
```

Each instance is isolated. Clients subscribe to specific instances.

---

## What We Want

### For Agents

1. Write normal Clojure functions that update `*ctx*`
2. Write hiccup render functions
3. Never think about: signals, client state, SSE, Datastar attributes
4. Get reactive UI updates automatically

### For Users

1. Click buttons, fill forms, see updates instantly
2. Optionally: REPL in browser to run Clojure directly
3. Same UI works on any device

### For the System

1. Translate agent's Clojure hiccup to Datastar-compatible HTML
2. Handle form data collection and type coercion
3. Route updates to correct clients
4. Persist `*ctx*` durably

---

## Key Questions to Research

### 1. Hiccup Translation

How do we translate clean Clojure hiccup to Datastar?

```clojure
;; Agent writes:
[:form {:on:submit (add-signal!)}
 [:input {:field :name}]
 [:button "Add"]]

;; System produces:
[:form {:data-on:submit "@post('/action', {form: 'add-signal!'})"}
 [:input {:name "name" :data-model "name"}]
 [:button "Add"]]
```

- What's the minimal transformation?
- Can we use a macro or is runtime rewriting better?
- How do we handle the action form serialization?

### 2. Form Data → Clojure Data

When user submits a form, how does it become a Clojure map?

- Datastar sends signals as JSON
- We need to parse and coerce types (string "100" → number 100)
- Malli schemas can guide coercion

### 3. JS Interop

How do we call JavaScript when needed?

```clojure
;; Agent wants to use a charting library
[:div {:ref (fn [el] (js/Chart. el config))}]
```

- Do we need a ClojureScript interpreter (cherry/squint)?
- Or just escape hatches to raw JS?
- How do we keep this clean in the Clojure code?

### 4. Datastar vs Custom

What can we leverage from Datastar directly vs what needs custom code?

- `data-model` for two-way binding ✓
- `data-on:*` for actions ✓
- SSE for updates ✓
- But: can we hide these behind nicer syntax?

### 5. Latency

Server-side state means network latency on every interaction.

- Debouncing for typing (100ms?)
- Optimistic updates?
- What's acceptable UX?

---

## Implementation Approach

### Phase 0: Research

1. Study Datastar deeply - what can we leverage directly?
2. Look at cherry/squint for potential ClojureScript-lite
3. Understand Hyperlith patterns we can adopt
4. Prototype the hiccup translation

### Phase 1: Minimal Viable

1. `:on:click` → Datastar `data-on:click` translation
2. Form `{:field :name}` → collect into map, pass to function
3. `*ctx*` watch → SSE push
4. Works for simple UIs

### Phase 2: Polish

1. Type coercion via Malli
2. Multi-instance support
3. Better error handling
4. Default introspection renderer

### Phase 3: Advanced

1. JS interop story
2. Rich form controls (datepickers, etc.)
3. Performance optimization
4. Developer tooling

---

## Two-Tier Storage

| Layer | Use Case |
|-------|----------|
| `*ctx*` durable atom | Fast prototyping, UI state, per-instance |
| Datalevin | Heavy queries, shared data, cross-agent |

Progression: prototype with `*ctx*`, graduate to Datalevin. Same Malli schemas.

---

## Open Questions

1. How much of Datastar can we use directly vs wrap?
2. Is cherry/squint worth it for JS interop, or just escape hatches?
3. What's the right abstraction for forms with complex controls?
4. How do we handle file uploads, drag-drop, etc.?
5. Can the browser literally be an nREPL client?

---

## Research Findings

### Datastar SDK Patterns

The Datastar Clojure SDK establishes these patterns:

1. **Dual-Mode Handler** - Check `(d*/datastar-request? request)`:
   - If true → return `->sse-response` with fragments
   - If false → return full HTML page

2. **Fragment-Based Updates** - `patch-elements!` sends HTML fragments that Datastar morphs into DOM

3. **Signal Sync** - `patch-signals!` updates client-side state without DOM changes

4. **Form Signals** - Datastar automatically bundles all `data-bind` signals into POST body

### Hiccup Transformation Strategy

**This is the key to hiding Datastar complexity.**

Agent writes clean Clojure:

```clojure
[:button {:on:click :increment} "Add"]
[:input {:field :user-name}]
[:form {:on:submit :create-order}
 [:input {:field :symbol}]
 [:input {:field :quantity :type "number"}]
 [:button "Create"]]
```

Framework transforms via `clojure.walk/postwalk`:

```clojure
[:button {:data-on:click "@post('/action/ns/increment')"} "Add"]
[:input {:name "user-name" :data-bind:user-name true}]
[:form {:data-on:submit "@post('/action/ns/create-order')"}
 [:input {:name "symbol" :data-bind:symbol true}]
 [:input {:name "quantity" :data-bind:quantity true :type "number"}]
 [:button "Create"]]
```

Implementation sketch:

```clojure
(defn transform-attrs [attrs instance-id]
  (reduce-kv
    (fn [m k v]
      (cond
        ;; :field :name → {:name "name" :data-bind:name true}
        (= k :field)
        (assoc m :name (name v)
                 (keyword (str "data-bind:" (name v))) true)

        ;; :on:click :fn → {:data-on:click "@post('/action/id/fn')"}
        (str/starts-with? (name k) "on:")
        (let [event (subs (name k) 3)
              action-url (str "/action/" instance-id "/" (name v))]
          (assoc m (keyword (str "data-on:" event))
                   (str "@post('" action-url "')")))

        :else (assoc m k v)))
    {}
    attrs))
```

### Signal → Clojure Data Flow

1. **Client** - User fills form, Datastar maintains signals
2. **Submit** - Datastar POSTs all signals as JSON: `{"symbol": "AAPL", "quantity": "100"}`
3. **Server** - Extract via `get-signals` or Ring params
4. **Coerce** - Malli decodes string "100" → integer 100
5. **Execute** - Call `(create-order! {:symbol "AAPL" :quantity 100})`
6. **Update** - Function swaps `*ctx*`, watch triggers
7. **Push** - Framework calls `patch-elements!` with new HTML

### Latency Mitigation

Since server owns state, network latency is visible:

1. **Debouncing** - Don't send on every keystroke, wait 100ms
2. **Loading indicators** - Auto-add `:data-indicator` for visual feedback
3. **Optimistic updates** - Show immediate feedback, reconcile on server response (future)

### ClojureScript Options (if needed)

| Option | Size | Data | Best For |
|--------|------|------|----------|
| Scittle (SCI) | ~400KB | Immutable | Full Clojure in browser |
| Squint | ~1-5KB | Mutable JS | Tiny, performance-critical |
| Cherry | ~350KB | Immutable | Real CLJS without build |

**Recommendation:** Start with pure Datastar. Only add CLJS if needed for specific JS interop.

---

## References

- `reference-code/datastar-clojure/` - Datastar SDK
- `reference-code/hyperlith/` - Hyperlith examples
- `docs/reference/datastar-quick-reference.md` - Current patterns
- [cherry](https://github.com/squint-cljs/cherry) - CLJS without build step
- [squint](https://github.com/squint-cljs/squint) - CLJS to JS compiler
