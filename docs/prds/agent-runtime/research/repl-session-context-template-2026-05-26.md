---
type: research
status: draft
tags: [research, agent, render, repl, template]
---

# REPL-session context template — chronological strategy

Companion to [ctx-render-strategies-prd.md](../architecture/ctx-render-strategies-prd.md).
This doc refines the *rendering* details of Strategy 1
(`naive-chronological`). It does not introduce new strategies, schemas,
or mechanisms — it specifies the **shape of the text** the chronological
strategy emits, so the LLM sees something that looks like a REPL
transcript the agent could have produced.

The other three strategies (most-referenced, decay, data-shape) reuse
the same per-entity render fns; they differ only in **which entities
they pick and in what order**. So nailing the templates here pays off
for all four.

---

## §1 — The vision

**The rendered context IS the agent's REPL session, reconstituted from
the tx-log.** Every event renders as one of two things: a Clojure form
(possibly inside a `(comment …)` block to denote "this is an entity
literal, not an active form"), or a Clojure line comment. Nothing
else — no markdown headings, no `[user]` chat brackets, no invented
section markers, no `## What you can do` blocks. If you concatenated
the rendered text into a `.clj` file, the reader would accept it; if
you eval'd it top-to-bottom in a fresh pod with the same substrate,
you'd roughly recreate the agent's world (modulo deliberately
unrepeated side effects — HTTP, DB writes, time). That fidelity is the
load-bearing property: every line is something the agent *could* have
typed or *did* watch happen.

---

## §2 — Per-entity render templates

Each kind has two render fns: `render-full` and `render-concise` (PRD
§2.2). Concise is what `most-referenced`, `decay`, and `data-shape`
strategies use for tier-2 entities; chronological uses `:full` for
everything.

For each kind below: the *handle* is a real Clojure value the agent
can paste into its next form to drill in. Handles are never wrapped in
prose — they sit as their own line, ready to be copied.

### 2.1 `:seon.system-prompt` and `:seon.conventions`

These are stable substrate entities, transacted once at boot. They
render as **a single top-of-file `;` comment block** — the agent reads
prose, the parser ignores it.

```clojure
;; ============================================================
;; You are a Clojure-fluent agent inside a CLJS pod on Node.
;; Emit forms as text — no markdown fences, no tool envelopes.
;; Narrate with `;` comments. Each contiguous comment block
;; binds to the form that follows; form N+1 runs even if N failed.
;; Your home namespace is shown at the top of this file as
;; `(ns seon.agent.XAR-…)`. defn / def into it freely.
;; ============================================================
```

Concise = same text truncated to the first N chars (PRD's fallback).

Handle: none — these are not drillable; they are the wrapper.

### 2.2 `:seon.ns` (substrate-shipped only)

Renders as a real `(ns …)` form. For the agent's home ns, just the
bare `(ns sym)` opener; for substrate-shipped imports, an `(ns …)` with
a `:require` vector. Renders **only at the front** (substrate-shipped)
or **only when the agent's home ns is being introduced**. Per-eval `ns`
forms inside an eval batch are subsumed (§4).

```clojure
;; Substrate namespaces available to you:
(ns seon.db
  "Datalog reads + writes. The sole DB API.")
;; #'seon.db
```

Handle: `#'seon.db` — the symbol; the agent evals it to drill in.

### 2.3 `:seon.schema` (substrate-shipped + front)

Renders as the literal `register!` call. Shape lives inline; if the
shape is large, concise truncates the vector with ` … ]`.

```clojure
(seon.schema/register! :seon.message/content :string)
(seon.schema/register! :seon.message/role
  [:enum :user :assistant :system :tool])
(seon.schema/register! :seon.handler/match
  [:map
   [:seon.handler.match/attr :keyword]
   [:seon.handler.match/value? {:optional true} :seon.handler.match/value?]])
;; :seon.schema/key :seon.message/content
```

Handle: the schema key keyword itself. The agent evals
`(seon.schema/show :seon.message/content)` to fetch the live shape
plus tx history. (`schema/show` is the only new helper this design
needs — ~10 LOC.)

### 2.4 `:seon.fn` (substrate-shipped + front)

Renders as the literal `defn` form. The full source is in
`:seon.fn/source`. Concise drops the body and keeps the signature
plus docstring:

```clojure
;; Concise:
(defn seon.db/query
  "Datalog query against an agent-scoped db. Map-in, map-out."
  [{:seon.db/keys [query args]}] ,,,)
;; #'seon.db/query

;; Full:
(defn seon.db/query
  "Datalog query against an agent-scoped db. Map-in, map-out."
  {:malli/schema [:=> [:cat :seon.db/query-request] :seon.db/query-response]}
  [{:seon.db/keys [query args]}]
  (apply d/q query @*conn* args))
;; #'seon.db/query
```

Handle: `#'seon.db/query`. The agent can eval that symbol bare and get
the var back (substrate ships a `var` whose pr-str shows the full
entity).

### 2.5 `:seon.handler` (substrate-shipped + front; one example only)

Renders as the literal `register!` call. **The substrate ships exactly
one of these into the front block** — the `wake-on-message` handler.
That's enough to teach the agent the handler mechanism by example;
subsequent agent-registered handlers render concisely.

```clojure
(seon.handler/register!
  {:seon.handler/name  :seon.handler/wake-on-message
   :seon.handler/match {:seon.handler.match/attr :seon.message/to}
   :seon.handler/fn    'seon.handlers.wake/wake-on-message})
;; #'seon.handler/wake-on-message
```

Handle: `#'seon.handler/wake-on-message` (the keyword name resolved
via `seon.handler/show`).

### 2.6 `:seon.message`

The load-bearing case — see §3. Renders as a **namespaced map
literal** inside a `(comment …)` wrapper so it's syntactically inert
but readable:

```clojure
(comment
  #:seon.message{:role :user
                 :from :user
                 :at #inst "2026-05-26T10:00:00.000Z"
                 :content "build a calculator with add"})
```

Concise truncates `:content`. Handle: the message id, but the agent
rarely drills in on a message — the content is right there.

### 2.7 `:seon.eval`

**The most important entity in the chronological flow.** An eval is
what the agent did. Renders as:

1. Narration (the `;` comments the agent typed) — preserved verbatim,
   each line prefixed with `;` (already is).
2. The literal source from `:seon.eval/source` (byte-faithful per
   parse.cljc).
3. A `;; => <result>` comment OR `;; ! <error>` comment.
4. A trailing handle comment: `;; #'seon.agent.XAR.../eval-ABC123`.

```clojure
;; Define add.
(defn add [x y] (+ x y))
;; => #'seon.agent.XAR-2605251544/add
;; #seon.eval/id "ev-1"
```

For a failed eval:

```clojure
(throw (ex-info "test" {}))
;; ! :seon.error/message "test"
;; #seon.eval/id "ev-fail-1"
```

For a multi-form eval batch (one LLM turn produced N forms), each
form's `:seon.eval` entity renders independently in tx-order — they
appear contiguously because they share a tx, but each is its own block
with its own narration + source + result. See §10 Q1.

Concise: keep narration + source, drop result. Drill via `(seon.eval/result "ev-1")` — the existing substrate fn (referenced in deepseek.cljs system prompt).

Handle: `#seon.eval/id "ev-1"` — a tagged literal. Substrate can ship
a data-reader that resolves it; alternatively the agent calls
`(seon.eval/result "ev-1")` explicitly.

### 2.8 `:seon.async-result`

Renders as a `(comment …)`-wrapped namespaced map, because it arrived
asynchronously and the agent did not type any of it. Visually it's
between two evals — a foreign event the agent watched land:

```clojure
(comment
  ;; --- async result arrived ---
  #:seon.async-result{:of "corr-llm-99"
                      :ok? true
                      :value "(brief textual summary)"
                      :at #inst "2026-05-25T12:00:04.000Z"})
```

The leading `;; --- async result arrived ---` comment is the only
"chrome" we permit, because there's no Clojure form that naturally
expresses "something happened while you were stopped." Even this is
just a comment; the parser would skip it.

Handle: the corr id; the agent drills via
`(seon.async-result/find "corr-llm-99")`.

---

## §3 — How messages arrive

**Sean's question**: from the agent's POV, when a message arrives in
their REPL, what does that LOOK like?

**Decision**: Hybrid — closer to (a) (just the message literal) than
to (b) (handler-firing trace before each message). **Once** at the
front of the context, immediately after the substrate's
wake-on-message handler is shown (§2.5), include **one worked
example** of a message arriving and the handler firing. After that,
every subsequent message renders as the bare literal map (§2.6) — the
agent has already learned the mechanism.

The worked example, rendered:

```clojure
(seon.handler/register!
  {:seon.handler/name  :seon.handler/wake-on-message
   :seon.handler/match {:seon.handler.match/attr :seon.message/to}
   :seon.handler/fn    'seon.handlers.wake/wake-on-message})
;; #'seon.handler/wake-on-message

;; --- Example: when a :seon.message lands with :seon.message/to
;; pointing at you, the wake handler fires and your loop runs.
;; You don't observe the handler explicitly — you just see the
;; message and, on your next render, you see the eval you produced
;; in response. The mechanism is taught by example; subsequent
;; messages render as their literal maps and you respond by
;; transacting a :seon.message entity with :role :assistant.

(comment
  #:seon.message{:role :user
                 :from :user
                 :to [#'self]
                 :at #inst "2026-05-26T09:00:00.000Z"
                 :content "(example) ping"})

(comment
  #:seon.message{:role :assistant
                 :from #'self
                 :to [:user]
                 :at #inst "2026-05-26T09:00:00.500Z"
                 :content "pong"})
```

**Justification**: Option (a) alone undertrains — the agent sees user
messages but never learns *why it wakes up*. Option (b) overtrains —
re-emitting the handler-fire trace before every message wastes tokens
and confuses the agent (the same handler keeps "firing" cosmetically
even though it's the same fn). The hybrid teaches the mechanism once,
then trusts the agent to extrapolate. This is the same pedagogy a
human gets from reading the first chapter of a REPL tutorial.

A second-order benefit: by including the worked example in the
**boot block** (stable prefix), it lives in the LLM's prompt cache
forever — zero marginal cost.

---

## §4 — Subsumption rule

**An entity is chronologically rendered at most once, in its most
informative form.** Tee'd entities (the `:seon.fn` / `:seon.schema` /
`:seon.ns` rows the substrate writes when it sees the agent eval a
`defn` / `register!` / `ns` form) are **subsumed by the eval that
created them**. The eval's `:seon.eval/source` already contains the
exact `defn` text; rendering the `:seon.fn` again next to it would
duplicate the same source.

### Rendered chronologically

- `:seon.message` (every one, after the front block's example)
- `:seon.eval` (every one)
- `:seon.async-result` (every one)
- `:seon.handler` (only when the **agent** registers a new one
  mid-session; substrate handlers are front-block only)

### Subsumed (never rendered as their own block in chronological view)

- `:seon.fn` — the eval that ran the `defn` shows the source
- `:seon.schema` — the eval that ran the `register!` shows the call
- `:seon.ns` — the eval that ran the `(ns …)` form shows it

### Front-block only (substrate-shipped; not subsumed because no eval created them)

- `:seon.system-prompt`, `:seon.conventions`
- The handful of substrate `:seon.ns` / `:seon.schema` / `:seon.fn`
  entities the agent needs to know exist (`seon.db`, `seon.handler`,
  `seon.message`, …)
- The one worked-example handler + worked-example message pair (§3)

### Drill-in only

Any subsumed entity is fully queryable. The agent evals `#'seon.foo/add`
or `(seon.schema/show ::foo)` to see the live `:seon.fn` or `:seon.schema`
row — which is useful when the *eval* that created it has scrolled out
of the window. The renderer for that drill-in call uses the same
per-entity `render-full` from §2.4 / §2.3.

### Consequence for stamping

The substrate currently stamps `:seon.render/ai` on every tee'd
`:seon.fn` / `:seon.schema` / `:seon.ns` entity (see
[the audit doc §6 cleanup plan]). Under this design,
**tee'd entities should not be stamped with render symbols at all** —
they are not chronologically rendered, only drill-in queried. Stamping
them just inflates the `:aevt :seon.render/ai` index. The front-block
substrate-shipped versions are stamped (because those *do* render).

This is a real simplification: one stamp at substrate boot for the
core fns/schemas/nses; zero stamps on the per-eval tees. The audit
doc's "we built renderers for entities that don't exist" anxiety
resolves cleanly — we keep the renderers (for drill-in) and stop
stamping the per-eval tees.

---

## §5 — Front of context: the substrate boot block

The first N entities are the substrate boot — fixed and cacheable.
Order:

1. **System prompt** — one `;;` comment block (~30 lines).
2. **Conventions** — one `;;` comment block (~15 lines).
3. **Home-ns declaration** — `(ns seon.agent.XAR-2605251544)`.
4. **Core substrate namespaces** — 3-5 `(ns …)` skeleton forms
   (`seon.db`, `seon.handler`, `seon.message`, `seon.schema`,
   `seon.eval`).
5. **Core schemas** — ~10 `(seon.schema/register! …)` calls,
   alphabetical by key.
6. **Core fns (concise)** — `(defn seon.db/transact! … ,,,)` form
   for the ~5 most-leaned-on fns. Concise (signature + docstring), not
   full bodies — those are huge.
7. **Worked-example handler + message pair** — §3.
8. **Boundary marker** — a single `;; ════` comment so the agent can
   tell where stable prefix ends and volatile tail begins. (This is
   the *only* substrate-injected chrome we permit, and it's a
   pure comment.)

A literal sample of the first 40 lines:

```clojure
;; ============================================================
;; You are a Clojure-fluent agent inside a CLJS pod on Node.
;; Emit forms as text — no markdown fences, no tool envelopes.
;; Narrate with `;` comments. Each contiguous comment block
;; binds to the form that follows; form N+1 runs even if N failed.
;; To message the user, transact a :seon.message entity with
;; :role :assistant.
;; ============================================================

(ns seon.agent.XAR-2605251544)

(ns seon.db   "Datalog reads + writes. The sole DB API.")
(ns seon.handler "DB-entity handler registry; tx → effects.")
(ns seon.schema "Malli schema registry.")
(ns seon.message "Message entity helpers.")

(seon.schema/register! :seon.message/content :string)
(seon.schema/register! :seon.message/role
  [:enum :user :assistant :system :tool])
(seon.schema/register! :seon.message/at :inst)
;; … ~7 more substrate schemas …

(defn seon.db/transact!
  "Transact a vector of maps under :seon.db/tx-data."
  [{:seon.db/keys [tx-data]}] ,,,)
;; #'seon.db/transact!

(defn seon.db/query
  "Datalog query. Map-in, map-out."
  [{:seon.db/keys [query args]}] ,,,)
;; #'seon.db/query

(seon.handler/register!
  {:seon.handler/name  :seon.handler/wake-on-message
   :seon.handler/match {:seon.handler.match/attr :seon.message/to}
   :seon.handler/fn    'seon.handlers.wake/wake-on-message})
;; #'seon.handler/wake-on-message

;; --- example: a message arrives and the handler fires (boot demo) ---
(comment
  #:seon.message{:role :user :from :user :to [#'self]
                 :content "(example) ping"
                 :at #inst "2026-05-26T09:00:00.000Z"})
(comment
  #:seon.message{:role :assistant :from #'self :to [:user]
                 :content "pong"
                 :at #inst "2026-05-26T09:00:00.500Z"})
;; ════════════════════════════════════════════════════════════════
```

After the `════` line, everything is chronological tail.

---

## §6 — Mid + back of context: the conversation + evals

Visual rhythm after the boot block:

1. User message arrives → its `(comment #:seon.message{…})` literal.
2. Agent's eval batch — one block per `:seon.eval` entity:
   - Narration as leading `;` lines (no extra prefix; the agent typed
     them as `;` already).
   - Source verbatim.
   - `;; => <result>` (or `;; ! <error>`).
   - `;; #seon.eval/id "ev-N"`.
3. If the batch transacted a `:seon.message` with `:role :assistant`,
   that message renders next (as its literal). Otherwise the next
   user message is what comes next.

The agent literally watches a transcript of: form, result, form,
result, message arrived, form, result, message arrived. Same shape as
any nREPL session log.

---

## §7 — Drill-in mechanics (no special fn)

There is **no `inspect/show` wrapper**. Drill-in is normal Clojure
resolution. The agent types one of:

- `#'seon.db/query` — eval the var; substrate ships a `print-method`
  that includes the full `:seon.fn` entity render.
- `seon.db/query` — same thing without the reader macro; var lookup
  followed by `pr-str`.
- `(seon.schema/show :seon.message/content)` — small substrate helper
  (~10 LOC) that pulls the `:seon.schema` entity for the key and
  returns its render.
- `(seon.eval/result "ev-1")` — existing substrate fn; documented in
  deepseek.cljs system prompt.

That's the full list of drill-in verbs. The pattern: **a real
Clojure call that takes a real handle and returns data**. No
`inspect/show {:seon.inspect/symbol …}` wrapper map — the agent
already has the symbol; it should evaluate it.

For the PRD's existing `seon.inspect/show` proposal: redirect it. The
inspector's *HTML* drill-in panel can still call a single
`inspect/show-html` fn for uniformity, but the agent's *eval* path
uses the per-kind helpers above. The two surfaces converge on the
same per-entity render fns; they just take different inputs.

---

## §8 — Literal 100-line sample

Scenario: agent boots, user asks for an `add` fn, agent defines + tests
it, user asks to make it variadic, agent redefines + tests.

```clojure
;; ============================================================
;; You are a Clojure-fluent agent inside a CLJS pod on Node.
;; Emit forms as text — no markdown fences. Narrate with `;`.
;; To message the user, transact a :seon.message with :role :assistant.
;; ============================================================

(ns seon.agent.XAR-2605261000)

(ns seon.db   "Datalog reads + writes. The sole DB API.")
(ns seon.handler "DB-entity handler registry; tx → effects.")
(ns seon.schema "Malli schema registry.")
(ns seon.message "Message entity helpers.")
(ns seon.eval  "Eval log; tee'd fn/schema/ns extraction.")

(seon.schema/register! :seon.message/content :string)
(seon.schema/register! :seon.message/role
  [:enum :user :assistant :system :tool])
(seon.schema/register! :seon.message/at   :inst)
(seon.schema/register! :seon.message/from [:or :keyword :seon.db/ref])
(seon.schema/register! :seon.message/to   [:vector :seon.db/ref])
(seon.schema/register! :seon.eval/source  :string)
(seon.schema/register! :seon.eval/ok?     :boolean)
(seon.schema/register! :seon.fn/sym       :symbol)
(seon.schema/register! :seon.fn/source    :string)
(seon.schema/register! :seon.schema/key   :keyword)

(defn seon.db/transact!
  "Transact a vector of entity maps under :seon.db/tx-data."
  [{:seon.db/keys [tx-data]}] ,,,)
;; #'seon.db/transact!

(defn seon.db/query
  "Datalog query. Map-in, map-out."
  [{:seon.db/keys [query args]}] ,,,)
;; #'seon.db/query

(defn seon.schema/show
  "Return the live :seon.schema entity for `k`."
  [k] ,,,)
;; #'seon.schema/show

(defn seon.eval/result
  "Return the result-edn of a past eval by id."
  [id] ,,,)
;; #'seon.eval/result

(seon.handler/register!
  {:seon.handler/name  :seon.handler/wake-on-message
   :seon.handler/match {:seon.handler.match/attr :seon.message/to}
   :seon.handler/fn    'seon.handlers.wake/wake-on-message})
;; #'seon.handler/wake-on-message

;; --- example: a message arrives and the handler fires (boot demo) ---
(comment
  #:seon.message{:role :user :from :user :to [#'self]
                 :content "(example) ping"
                 :at #inst "2026-05-26T09:00:00.000Z"})
(comment
  #:seon.message{:role :assistant :from #'self :to [:user]
                 :content "pong"
                 :at #inst "2026-05-26T09:00:00.500Z"})
;; ════════════════════════════════════════════════════════════════

(comment
  #:seon.message{:role :user :from :user
                 :at #inst "2026-05-26T10:00:00.000Z"
                 :content "build a calculator with add"})

;; Define add and test it.
(defn add [x y] (+ x y))
;; => #'seon.agent.XAR-2605261000/add
;; #seon.eval/id "ev-1"

(add 2 3)
;; => 5
;; #seon.eval/id "ev-2"

(seon.db/transact!
  {:seon.db/tx-data
   [#:seon.message{:role :assistant :from #'self :to [:user]
                   :content "add ready: (add 2 3) ;; => 5"
                   :at #inst "2026-05-26T10:00:01.000Z"}]})
;; => {:tempids {} :tx-data [,,,]}
;; #seon.eval/id "ev-3"

(comment
  #:seon.message{:role :user :from :user
                 :at #inst "2026-05-26T10:00:30.000Z"
                 :content "now make it variadic"})

;; Redefine add to take any number of args.
(defn add [& xs] (apply + xs))
;; => #'seon.agent.XAR-2605261000/add
;; #seon.eval/id "ev-4"

(add 1 2 3 4)
;; => 10
;; #seon.eval/id "ev-5"

(add)
;; => 0
;; #seon.eval/id "ev-6"

(seon.db/transact!
  {:seon.db/tx-data
   [#:seon.message{:role :assistant :from #'self :to [:user]
                   :content "variadic: (add) ;; => 0; (add 1 2 3 4) ;; => 10"
                   :at #inst "2026-05-26T10:00:31.000Z"}]})
;; => {:tempids {} :tx-data [,,,]}
;; #seon.eval/id "ev-7"
```

**Line count**: ~100 lines including blanks. **Character count**:
~3,400 chars → ~850 tokens at the 4-chars-per-token heuristic.

**Validity check** (mentally pasted into a fresh REPL):

- All `(ns …)` forms are real and reachable.
- All `(seon.schema/register! …)` calls are valid; the registry is
  idempotent so re-eval is fine.
- The `(defn seon.db/transact! … ,,,)` placeholder bodies (`,,,`) read
  as `clojure.core/,,,` which doesn't exist — **this is the one spot
  where the "eval top-to-bottom" property doesn't strictly hold**.
  We can fix it by emitting either real bodies (huge) or a
  conventional sentinel like `(throw (ex-info "elided" {}))` (ugly)
  or a real body that just rethrows. Sean to decide; see §10 Q4.
- `(seon.handler/register! …)` is a real call.
- `(comment …)` blocks reader-skip; their contents validate but don't
  run. Good.
- The `;; #seon.eval/id "ev-1"` tagged-literal lines are inside
  comments, so they don't need a data-reader to be present at read
  time. (Only at drill-in time, when the agent types
  `#seon.eval/id "ev-1"` as a real form.)
- `#'self` is the only non-Clojure-stdlib symbol. Substrate ships a
  dynamic var `*self*` aliased to `#'self` for the duration of a turn;
  this is one extra ~5 LOC.

---

## §9 — Minimum render-fn changes from current state

| Current file | Action |
|---|---|
| `src/seon/handlers/eval.cljs::render-ai` | **Rewrite**: emit narration as `;` lines, then source verbatim, then `;; => result` (or `;; ! err`), then `;; #seon.eval/id "…"`. Drop the `[eval id ms :ok]` header — it's chrome, replaced by the `#seon.eval/id` handle. |
| `src/seon/handlers/message.cljs::render-ai` | **Rewrite**: emit a `(comment #:seon.message{…})` block. Drop the `[user] hi` bracket form entirely. |
| `src/seon/handlers/fn.cljs::render-ai` | **Keep but repurpose**: this is no longer called from the chronological flow (subsumed by its eval). It IS called from drill-in (`#'seon.foo/add`) and from the front-block substrate-fn rendering. Output: a `(defn … ,,,)` form + a `;; #'sym` handle. |
| `src/seon/handlers/schema.cljs::render-ai` | **Same**: subsumed in chronological flow; used at front-block + drill-in. Output: the literal `(seon.schema/register! …)` call. |
| `src/seon/handlers/ns.cljs::render-ai` | **Same** as schema. Output: `(ns sym docstring? require-clauses?)`. |
| `src/seon/handlers/retro_stamp.cljs` | **Delete or repurpose**: the per-eval tees of `:seon.fn` / `:seon.schema` / `:seon.ns` should no longer be stamped with `:seon.render/ai`, so there's nothing to retro-stamp on those kinds. The stamp on `:seon.message` and `:seon.eval` is still needed; that subset stays. Audit before delete. |
| Substrate boot tx (`start-agent!` or similar) | **Add**: write the substrate-shipped boot entities (system-prompt, conventions, core nses, core schemas, core fns, one example handler, one example message-pair) with `:seon.render/ai` stamps. These ARE the front block. |
| Per-eval tee-write path | **Remove `:seon.render/ai` stamp** on the tee'd `:seon.fn` / `:seon.schema` / `:seon.ns` entity. Keep the tee (the entity must exist for drill-in queries); just don't index it for the chronological renderer. |
| `seon.render/ai-render` symbol-on-entity dispatch | **Keep as-is**. Just one render fn per kind; no `:full` / `:concise` ladder of separate symbols. The strategy decides which to call (PRD §2.2 already has this design); the render fn itself takes a `:seon.render/detail` key in its input map and branches. |

The PRD's existing `:seon.render.ctx/{full,concise}` schemas survive;
this doc just specifies the *content* the `:full` fn emits.

---

## §10 — Open questions

1. **Multi-form eval batches.** `eval-batch!` produces N
   `:seon.eval` entities per LLM turn. In the chronological renderer
   they render as N separate blocks. **Question:** do we group them
   under a single comment header (`;; --- turn 7 ---`) or leave them
   to flow naturally? The §8 sample leaves them naturally — the
   adjacent `#seon.eval/id` handles make the boundaries obvious. I'd
   ship that and only add headers if A/B testing shows confusion.

2. **Narration containing `;` comments.** Today
   `:seon.eval/narration` is a string of `;` comments already
   accumulated by the parser. If we just emit it verbatim, we're
   fine — but if a future analyzer pass strips the `;` prefixes, we
   would need to re-add them on render. Decision needed: store
   narration *with* its `;` prefixes (current) or *without* (future).
   I'd vote keep-with-prefixes — round-trip fidelity.

3. **Substrate-fn body in the front block.** `seon.db/transact!` is
   ~50 LOC including instrumentation. **Concise** (signature +
   docstring + `,,,`) is the right call for the front block — it's
   permanently cached and the body adds little signal. Full body
   available via `#'seon.db/transact!` drill-in. Confirmed in §8.

4. **`,,,` as the elided-body sentinel.** The `,,,` reads as a
   comma (whitespace) so the form `(defn foo [x] ,,,)` reads as
   `(defn foo [x])` — an arity-0 defn-on-the-name with a vector
   destructure — which **is not a valid `defn`** and will fail to
   eval. Sean to decide between: (a) real bodies for substrate fns
   (token cost), (b) a `(throw ::elided)` body (works, ugly), (c)
   accept that the front block isn't strictly eval-able and document
   it (the "rough fidelity, not strict" property). I lean (c) — the
   property the agent cares about is *reading* the form to learn the
   signature, not running it.

5. **Schemas with huge shapes.** `[:map …100 entries…]` — concise
   truncates the vector with ` … ]`. Open question: where exactly to
   cut? After 5 entries? 10? Per-line char budget? Punt to a
   `*concise-shape-max-entries*` var defaulting to 8; tune later.

6. **`#:seon.message{…}` namespaced-map literals containing
   `#inst "…"`** read fine and validate, **but** the `:to`
   `[#'self]` notation requires `*self*` to be bound. If the agent
   eval'd this top-to-bottom in a fresh pod, `#'self` would fail to
   resolve. Acceptable cost — fidelity is "rough" by design. We
   could substitute the literal agent id keyword
   (`[:seon.agent/id "XAR-…"]`) but it's noisier.

7. **`:seon.async-result` rendering — visual marker.** The `;; ---
   async result arrived ---` comment is the one piece of chrome in
   the whole template. Alternatives considered: render as a bare
   `(comment #:seon.async-result{…})` (loses the "this is a foreign
   event" signal); render as a `;; A:` prefix on every line
   (cluttered). The single marker comment is the cheapest signal.
   Open: do we want a corresponding marker on user messages? The
   `:role :user` field already signals it; I'd say no.

---

## Cross-references

- [ctx-render-strategies-prd.md](../architecture/ctx-render-strategies-prd.md) — umbrella PRD
- [loop-walkthrough-2026-05-25.md](../loop-walkthrough-2026-05-25.md) — scenarios this template renders
- [codebase-audit-and-cleanup-plan-2026-05-26.md](../codebase-audit-and-cleanup-plan-2026-05-26.md) — current state + cleanup tasks this design feeds into
- `src/seon/render.cljs` — `assemble-ai-context` is the dispatch point
- `src/seon/handlers/eval.cljs` — biggest render-fn rewrite
- `src/seon/handlers/message.cljs` — second rewrite
- `src/seon/parse.cljc` — narration + source structure this design preserves
