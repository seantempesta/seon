---
type: prd
status: draft
tags: [prd, agent, context, render, data-model, architecture]
---

# The agent-centric design — one document, honest

*2026-09-04. Written because the owner could not follow the trail of
rulings, probes, and diagrams, and asked for a second opinion. THIS
DESCRIBES THE TARGET SYSTEM, NOT HEAD: the data is rearranged around the
agent and simplified wherever a family can be an attribute, a message, or
an agent-declared schema instead. Where today's shape is mentioned it is
only to say what the target replaces. It supersedes every earlier draft
where they disagree. A
reviewer with no context from this session critiques it in
[second-opinion-2026-09-04.md](../research/second-opinion-2026-09-04.md).*

## 1. The goals, in the owner's words

1. An agent is dropped into a Clojure REPL in its own namespace.
2. Its context is generated from ITS data and ITS graph neighbourhood —
   never authored, never hand-assembled — as forms we evaluate to surface
   the data and to explain how we found it.
3. The agent's work is data work: schemas, facts, queries, transactions.
   It writes a function only for a surface the user sees (a render
   function) or a calculation reused enough to name.
4. `(help)` summarizes the data on the agent's record and its connections
   through render functions (a whole-collection summary and a per-entity
   face, both `/ai` and `/html`) and names the functions that can process
   that data.
5. Teaching is `doc` and `dir`, polymorphic, demanded before use. No
   prose walls.
6. Every value prints through the most specific render function
   (inline → the agent's own namespace → other agents by distance → the
   family's general face → the floor). Render functions accrete across
   agents; nobody starts at zero. The same functions through `/html` are
   the entire UI. Nothing is hardcoded; turtles all the way down.
7. New data arrives through the watch and shows as a diff, rendered by
   the same function that showed the original. Every turn regenerates the
   whole context; compaction is a fresh session that loses nothing.
8. Every value is a real symbol (`result/<id>`) the agent can use.
9. ONE platform. No parallel systems. Nothing deleted before the design
   is agreed; no coding before it convinces.

## 2. The TARGET data model — a graph around the agent, nothing hidden

**The perspective.** A graph database has no tables. An entity is an open
bag of attributes; any attribute may sit on any entity; a ref connects
two entities and can be read from either end. "Where does this data
live" is therefore a modeling choice about WHICH ENTITY CARRIES THE
ATTRIBUTE, and the answer here is: everything that is about running an
agent is carried by the agent entity or by entities that reference it,
and nothing is hidden behind a family that has to be joined to find out
whose it is. The agent's context is then not a pull of a tree but a
DISCOVERY: read the agent entity's own attributes, follow every ref out,
follow every ref in, and you have found all the data there is to render.

### 2.1 What the agent entity itself carries (its plumbing, as attributes)

```clojure
;; the agent — one entity; its running state is ITS attributes, not a separate run family
#:seon.agent
{:id            [:string {:seon.db/identity true}]
 :namespace     :seon.db/ref              ; → its OWN home namespace (my.agents.<id> by default) — where its defs live
 :stewards      [:set :seon.db/ref]        ; → namespaces it works on behalf of (my.note …); several agents may steward one
 :forked-from   :seon.db/ref              ; → agent
 :cluster       :seon.db/ref              ; → the cluster it runs in (referenced)
 ;; the turn, as facts on the agent: begin-turn asserts them in ONE transaction, close-turn retracts/advances them
 :turn          [:int {:min 0}]           ; the turn number it is on
 :process       :string                   ; custody: which process holds it now; ABSENT = idle (the crash-honest representation)
 :trigger       :seon.db/ref              ; → the message this turn is answering; absent = no open turn
 :turn-opened-at :inst
 :last-turn-closed-at :inst
 :context-basis :seon.db/basis-t}         ; the database basis the current context was generated at
```

A crash is readable from these alone: `:process` names a dead process and
`:trigger` is present → a claimed turn that never finished; whether any
form ran is the presence of evals with that `:turn` (below); an eval with
`:started-at` and no `:settled-at` is the interrupted form. Takeover is
one transaction on the agent entity: retract the dead `:process`, assert
the live one. This is the reviewer's decision 2 answered with attributes
instead of a run entity — a claim the crash falsifier (§9) must prove.

### 2.2 What references the agent (found by reverse refs — the graph's own index)

```clojure
;; an eval — the agent's transcript row; it POINTS AT its agent
#:seon.agent.eval
{:id :string-identity  :agent :seon.db/ref  :turn :int  :ordinal :int  :author [:enum :agent :system]
 :source :string  :started-at :inst  :settled-at :inst
 :result :string  :result-blob :seon.blob/digest  :result-family [:set :qualified-keyword]
 :error-kind :keyword  :error-message :string  :error-data :string     ; flat, indexed fields — not a :map
 :defines [:set {:seon.db/component true} :seon.db/ref]               ; component: a def has exactly one defining eval
 :reads   [:vector {:seon.db/component true} :seon.db/ref]             ; component: read-evidence belongs to one eval
 :effects [:set :qualified-keyword]  :prompt :seon.blob/digest  :rendered-by :symbol}

;; a message — one entity, attached to BOTH ends by refs; nothing is hidden in an inbox
#:seon.message
{:id :string-identity  :to :seon.db/ref  :from :seon.db/ref  :content :string  :at :inst
 :about :seon.db/ref  :reply-to :seon.db/ref
 :handled-by :seon.db/ref}          ; → the eval/turn that dealt with it; ABSENT = unhandled (derive, don't store a boolean)

;; a fault the system recorded ABOUT the agent (a core fault, not an agent mistake) points at it too
#:seon.fault {:id … :agent :seon.db/ref :eval :seon.db/ref :kind :keyword :message :string :at :inst}
```

Why refs in, not components out: the reviewer measured that a component
child can sit under two owners and is cascade-deleted with either, and
that a positional pull silently stops at 1,000 children. Reverse refs
have neither problem — `[?e :seon.agent.eval/agent ?agent]` is exact,
countable, pageable, and readable from either end. Components are kept
ONLY where one owner is a fact of life (a def belongs to the eval that
made it; read-evidence belongs to its eval). "Information hiding" is
gone: a message is queryable by sender, recipient, subject, and handler
without any join to an owner; the agent's "inbox" is just the view
`[?m :seon.message/to ?agent] (not [?m :seon.message/handled-by _])`.

### 2.3 What the agent references but does not own

Its home namespace row and the namespaces it stewards, and through them
the program index (functions, arities, contracts, tests, usages); the
cluster; other agents (through messages'
`:from`/`:to` and `:forked-from`); the schema rows of every family it
touches. These are read, never copied.

### 2.4 The agent's own data — any entity, any attributes

An agent declares families in its namespace and transacts rows. Its data
is connected to it the way any domain connects: by refs the domain
already needs — an event's `:acme.calendar/calendar` → a calendar whose
`:acme.calendar/steward` → the agent; a note's `:about` → anything; a
plan item's `:agent` → the agent. Discovery finds them through reverse
refs like everything else. There is no generic `:seon.agent/data` bag:
the reviewer was right that it duplicates the domain's own ownership and
supplies no order.

### 2.5 The full picture

```
   program index (ns · fn · arity · schema · test) ◀── :namespace ── AGENT ── :cluster ──▶ cluster ──▶ agents…
                                                                     ▲ ▲ ▲ ▲
                     evals (:agent) · messages (:to / :from) · faults (:agent) · domain rows (their own refs) · plan/notes (:agent)
   plumbing ON the agent: :turn :process :trigger :turn-opened-at :context-basis
```

## 3. How the context is built — discover, propose forms, order, execute, render

Not one pull. Five phases, each a function of the database value, each
with nothing hidden:

1. **Discover.** Read the agent entity. Its scalar attributes are its
   plumbing (who am I, what turn, what woke me). Its ref attributes lead
   out (namespace → code; cluster → the world). The installed schema says
   which attributes are refs; for each ref attribute a query finds what
   POINTS AT the agent (`[?x ?attr ?agent]`) — evals, messages, faults,
   plan items, domain rows through their own chains. Every connection
   comes back as a COLLECTION DESCRIPTOR, not rows: the attribute, the
   count, the newest instant, a stable order, and a bounded page — all
   from database-side aggregates, so 10,000 events cost a count, not a
   pull.
2. **Propose forms.** For each discovered connection the generator writes
   the form the agent could type: an intent comment derived from the
   attribute's name and direction, and the query WRAPPED IN THE RENDER
   FUNCTION it chose — explicitly, in the form:
   ```clojure
   ;; messages to me, unhandled, newest first
   (seon.message/inbox-view (seon.db/q '[:find [(pull ?m [*]) ...] :where [?m :seon.message/to [:seon.agent/id "cal-steward"]] (not [?m :seon.message/handled-by _])]))
   ;; my calendar, as a whole
   (acme.calendar/events-summary (seon.db/q '[:find [(pull ?e [*]) ...] :where [?e :acme.calendar/calendar ?c] [?c :acme.calendar/steward [:seon.agent/id "cal-steward"]]]))
   ```
   The render function is chosen by the contract query (input covered by
   the collection's family, output `:seon.render/ai`; the agent's own
   namespace first, then by distance, then the family's general face);
   its NAME is in the form, so there is no magic: the agent sees which
   function rendered, can call it itself, and can write a better one. A
   connection with no fitting function gets the generic collection
   summary (identity attribute, count, newest, sample, requery) — never a
   blank. Teaching forms are proposed the same way: every name a proposed
   form uses that the agent has not yet used correctly gets its
   `(doc …)`/`(dir …)`.
3. **Order.** The graph is unordered; the transcript is not. The order is
   a function over the proposed forms' metadata: plumbing first (who am
   I), then teaching before the forms that need it (dependencies before
   dependents — a topological sort on the names each form mentions), then
   connections grouped by kind (my code, my data, my messages, my history)
   and within a group newest first, the trigger last. The order function
   is itself the agent family's layout render function — replaceable per
   agent like any face.
4. **Execute — in parallel.** Every proposed form is independent: evaluate
   them concurrently, settle each as an eval (`:author :system`, source,
   result value, result-family, reads, effects). The agent's own earlier
   evals are not re-executed; they are read.
5. **Render.** The transcript is the ordered evals rendered: for each,
   `ns=> source`, the result printed by the render function the form
   names (already applied — the result IS the rendered value for wrapped
   forms; unwrapped results print through the floor), then
   `;; result/<id>`. The page is the same ordered evals rendered through
   `/html`. Compaction is the same five phases at the current basis with
   a token budget applied in phase 3 by the layout function (oldest
   history as handles + one line).

So the transcript is exactly what the owner said: information discovery
(`doc`, `dir`), queries wrapped in render functions so they read well,
and the agent's own forms — nothing else.

### 3.1 What every agent wants to know (the discovery inventory)

Derived from the agent's attributes and connections, in the order phase 3
emits them:

| about | source on the graph | rendered by |
|---|---|---|
| who am I, what turn, what woke me | the agent entity's own attributes | the agent face (one block) |
| my code | `:namespace` → publics, contracts, tests reaching them | `(doc *ns*)` data |
| what can process my data | contract query over arities whose inputs include my families | one line per function in help |
| my messages, unhandled first | reverse `:seon.message/to`, minus handled | `inbox-view` (family face or my own) |
| my data, as a whole and by entity | reverse refs through my families' own ownership chains | each family's summary face; per-entity faces on demand |
| my history | reverse `:seon.agent.eval/agent`, ordered `[turn ordinal]` | the eval face |
| my defs | the newest defining eval per name | `(dir *ns*)` |
| what went wrong | evals with `:error-kind`; faults pointing at me | the error face, where it happened |
| what changed since my last context | `:context-basis` vs now: `seon.db/diff` of each discovery query | the same face that showed the original |
| the world I touch | required namespaces; agents I message; the cluster | `(dir ns)`; agent summaries |
| how to finish | `my.run/complete` / the reply form | one line in help |

### 3.2 Root — the same discovery, a different projection

Root's connections include the cluster's agents. Root must know what its
agents are doing without regenerating their contexts. That is a RENDER
FUNCTION, not new data: `seon.agent/agents-summary` takes the agent
collection and prints one line per agent from facts already on each
agent entity — id, turn, process alive or dead, `:turn-opened-at`,
unhandled message count, evals with errors this turn, last settled eval
instant — and a drill-in form `(seon.agent/summary [:seon.agent/id
"planner"])` for one agent's plumbing, last five evals, and unhandled
messages. Root's help therefore lists processing functions over agents
(fork, message, take over, reassign a namespace) beside its own data.
The projection is the render function; the data is the same graph.

### 3.3 Three projections of one record — SCI, the completion prompt, the page

The agent's transcript is a projection of its data: its comments, forms,
and results, sorted by execution time. The same data has three consumers,
and each wants a different OPTIMAL FORMAT. Nothing is stored in any of
the three formats; each is a function of the facts, produced by functions
that are themselves facts (program rows), found by contract.

| consumer | optimal format | what a "render function" is here | ordering |
|---|---|---|---|
| **SCI** — the agent's execution environment for a turn | bindings: namespaces required, defs interned, `result/<id>` vars bound (lazily) to stored values, contracts wrapped | INSTALLERS: `require-namespace`, `install-def`, `bind-result`, `wrap-contract` — input a fact family, output a ctx effect | dependency order: requires → defs in `[turn ordinal]` (later redefinitions win) → result bindings → wrappers |
| **the completion endpoint** — the AI's context | one string: entries `;; intent` · `ns=> form` · the result rendered · `;; result/<id>`; prefix-stable across turns | `/ai` functions: input the value's family (or collection), output text | the layout function: plumbing → teaching before use → kinds by recency → the agent's own evals by time → trigger last; fitted to the token budget |
| **the user's page** — HTML | hiccup: a shared header, the transcript as blocks, panels the user asked for; morph-updated over SSE | `/html` functions: same inputs, output hiccup; a LAYOUT function composes regions | regions: header (plumbing) · main (the transcript by execution time) · panels (newest basis first); within the transcript, the same order as the prompt |

**One composition rule for all three.** Given the discovered data (§3
phase 1): for each datum and each consumer, select the function whose
contract accepts the datum's family and returns that consumer's format;
compute the PRE-REQUISITES the chosen forms need; order; apply.

**Pre-requisites are a derivation, per consumer:**
- *SCI*: a def's namespace must be required before it is interned; a
  result binding needs its value readable (EDN or blob) — an unreadable
  one binds nothing and the transcript shows no handle; a contract wrapper
  needs the fn row. Nothing is taught here; the environment HAS the real
  bindings.
- *the prompt*: every symbol a proposed form uses whose row the agent has
  not yet used correctly demands `(doc sym)` before it; every namespace
  first touched demands `(dir ns)` before it; the render function named
  in a wrapped form demands nothing (it is a name in the form; its doc is
  one form away) unless the agent later errs on it. The demand edges are
  the DAG the layout sorts.
- *the page*: the same demand edges become links and hover cards: a form's
  symbols link to their namespace pages; a rendered value's family links
  to its schema; a block shows which function rendered it.

**The page, all of it, as functions of the same facts:**
- **Shared header** — `seon.agent/header-html`: the agent's plumbing
  attributes (id, namespace, turn, process alive, basis, unhandled count)
  plus the cluster's agents as one line each (root's `agents-summary`
  reused at one line). Pure function of the agent entity.
- **Main content: the transcript** — `seon.agent/transcript-html`: the
  ordered evals; each eval is a BLOCK (`seon.agent.eval/html`): the intent
  comment as a caption, the source syntax-highlighted (a function over the
  source string — highlighting is a render function on `:seon.agent.eval/source`,
  replaceable), the result through the value's `/html` face, the
  `result/<id>` chip. A prose comment the agent wrote (its `;;` lines) is
  rendered as MARKDOWN inline: emphasis, code spans, lists — a render
  function on the comment string, so "the agent outputs markdown for the
  user" is one more face, not a special case.
- **Panels: specialized visualizations** — every function in the agent's
  namespace (or reachable by distance) whose contract accepts a family the
  agent's data belongs to and returns `:seon.render/html` is a candidate
  panel; the user or the agent asks for one by evaluating it
  (`(acme.calendar/week-html …)` is an eval like any other) and it appears
  as a block keyed by its eval id, newest basis first. A panel IS a
  function that processes data and returns hiccup; nothing else.
- **Delivery** — blocks are keyed by eval id or entity identity, so a
  regeneration morphs only changed blocks (the existing keyframe/delta
  packages).

**Compaction, in all three.** SCI: the newest defining eval per name
still wins; result bindings for compacted evals stay bound (the value is
a fact). Prompt: the layout function renders compacted evals as their
handle plus one line. Page: the transcript block list pages by time;
nothing is dropped.

**What is deliberately NOT here:** a stored transcript, a stored prompt,
a stored page, a per-consumer data model. Three functions, one graph.

### 3.4 What already exists at HEAD — where the context's forms and results are recorded today (verified 2026-09-05 on `ctxprobe`)

The owner's requirement: the comments and forms the generator produces are
executed through the SAME parser and eval system as the agent's own, their
results recorded with the agent, so a session can be resumed and
inspected. Most of this exists; it is scattered, and one part is missing.

| what | recorded today? | where | joined how |
|---|---|---|---|
| the generated OPENING (help, dirs, teaching, the trigger) as comments + forms | **YES — exactly the wanted mechanism** | the bootstrap run's `:seon.cluster.run.form` rows, `author :system`; on `ctxprobe`, run `bootstrap:root` holds 11 system forms (`"; A new run just opened. Why am I awake — do I have messages?\n(help)"`, `(dir my.message)`, `(dir my.run)`, …) | by `run` + `ordinal` |
| their RESULTS | yes | 11 `:seon.cluster.eval` receipts on the same run (`result-edn` as a print node, `read-evidence`) | by `run` + `ordinal` (a second family for one entry — census §1) |
| the agent's own forms + results | yes | the same two families, `author :agent` | same join |
| the agent's defs | yes | `:seon.def` rows | by agent/ns/name |
| the per-turn NEIGHBOURHOOD entries (what the walk renders each turn: messages, runs, errors, namespace dirs) | **NO** — rendered bytes only | `seon.render.walk/history` builds `:seon.render.history/bytes` in the render proc; retained in `seon.render.web`'s proc state; NO schema row exists for `:seon.render.history/*` or `:seon.render.call/*` — a process memo, lost on restart, invisible to queries | — |
| the prompt actually sent | yes, as BYTES | `:seon.context.capture` (one per run) + `:seon.context.contribution` rows carrying `hash`, `position`, `tokens`, `:seon.render.block/name :walk` — fingerprints of segments, not forms or values | by run |
| what each render cost | partly | `:seon.render.cost` facts (shape key, profile, tokens, at) — no producer symbol | by run |

So: the OPENING already does what the design asks — the system types
forms on the agent's behalf, they run through the loop, and comments,
forms, and results land on the agent's run as facts. The per-turn CONTEXT
does not: the walk renders the neighbourhood straight to bytes, captures
only the final prompt, and forgets the forms it would have typed. That is
the one missing piece, and it is a generalization, not a new mechanism:
route every generated entry — discovery, teaching, diff — through the same
`generated-form-request` path the opening uses (`run.clj:778-831`), and
the per-turn context becomes forms + results on the agent's run like the
opening's. Colocation then follows from the record shape (§2: evals point
at the agent), and the run-form/eval split collapses into one row (64).

**The result-handle question, seen this way, is not a design fork.** A
generated form runs through the eval system and its result is whatever the
form returns: `(seon.db/q …)` yields data and its handle is data;
`(inbox-view (seon.db/q …))` yields text and its handle is text. Both are
recorded identically. Which form the generator emits is a per-entry choice
(data forms where the agent should reuse the value; wrapped forms where
only the view matters, with the render function's name in the form), and
the lab shows both on the same data. §3's provisional marks are lifted;
B13 is amended to "a handle denotes what its form returned."

## 4. What we have EVIDENCE will work

| claim | evidence |
|---|---|
| A recursive renderer (select by family → call; each face calls render on its parts) with Ring-style middleware for bounding/provenance/errors and a threaded ctx | prototyped on the live cluster, 3 passes; swapping one face changed only its entries (`research/scripts/recursive-render-probe-2026-09-03.clj`). **Second opinion:** the mechanism holds; the 3.3 ms was 22.8 ms cold on a fresh cluster, and the probe uses STAND-INS for the contract query and the print floor — it proves composition, not production selection or bounded help. |
| A value's family derives from its identity attribute | **REFUTED as stated** by the second opinion: on a fresh cluster 40 identity attributes gave 41 identity→family pairs over 38 attributes — `:seon.cluster.agent/id` maps to three entity schemas, `:seon.test/sym` to two, two identities to none. My probe checked singleton cardinality only for the three hand-picked identities. The derivation needs a rule for zero, one, and several families and for maps carrying several identities. |
| Namespace distance for "closest other agent wins" is one Datalog query with rules | reproduced by the reviewer: 55 rows, 19.4 ms cold / 0.59 ms warm — the shape holds; the numbers are not invariants |
| Schemas → Datahike attributes → instrumentation all derive from facts; an agent-declared schema settles through the run loop with accretion allowed and breaking redefinition refused | `schema/datahike.clj:221-262`; the refusal is at `run.clj:1134-1165` (invoked `:1422-1424`), settlement at `:1436-1460`; `sci/eval.clj:591-602` installs a wrapper from a supplied row — the chain holds, my line citations overclaimed. The sketched `:error :map` attribute is NOT mappable by the schema bridge (`schema/datahike.clj:50-61,112-174`). |
| The watch primitive and per-query interest exist | `wake.clj:168-256`, read-evidence revisions, `:seon.render.web/interest` |
| `diff` at a recorded basis shows additions and deletions; a `since` view cannot (lookup refs to older entities fail inside it) | both measured live |
| Recursive and reverse pulls work as Datahike documents them | measured live — but a positional pull silently returns at most 1,000 children per attribute (Datahike default, `pull_api.cljc:16,304-324`; the reviewer's 10,000-child probe returned 1,000 with no omission marker). "The record is one pull" is false for large records without an explicit bounded collection contract. |
| Transactions carry provenance stamps | **Corrected by the second opinion:** `receipt_write_carrier_test` proves `:seon.db/receipt` + caller-supplied `:seon.db/user`, not eval + process. Keep user and process; add the eval/receipt ref when one exists. An eval is a causal identity, not the authorizing human or agent. |
| The query cost problem was the wrapper, not Datahike | `seon.db/q` 2.4 s → 0.048 ms after the projection cache landed |

## 5. What is UNCLEAR or UNPROVEN

1. **Generated `(help)` has never been read.** We have a design and a
   prototype renderer; we have never generated a help for a real record
   and read it at the bytes. This is the first thing to build and read,
   on a scratch cluster, before anything else.
2. **The schema spelling** (one schema fact with inline entry schemas,
   attribute rows derived at settlement) needs one accretion at
   `run.clj:1436` that is unbuilt and unmeasured.
3. **`result/<id>` binding at scale** — binding one symbol per eval at
   ctx build through the def-restore seam is a design, not a measurement;
   a 500-eval history has not been tried.
4. **The smart print ladder** (breadth first, strings last, 5k tokens per
   value) is specified, not built; the current ladder destroys strings
   first.
5. **Wake routing under the agent-centric model.** `route!` routes on
   `:seon.cluster.message/to` datoms today. With messages as component
   children of the inbox, the routed attribute becomes
   `:seon.agent/inbox` (the datom's entity is the recipient). Small, but
   the wake-attribute set is a computed property that must be re-derived,
   not edited.
6. **Cross-agent analytics** ("who messages whom") become history queries
   over inbox additions plus the tx stamp. Fine in Datalog; never
   measured.
7. **Component cascade**: retracting an agent retracts its record. Right
   by design; the fork semantics (`forked-from` with copied defs) are
   unspecified — does a fork copy evals or reference them?
8. **Turns without a run entity**: custody on the agent and `:turn` on
   evals must still give the crash model what it needs (a dead process's
   turn is recoverable from evals whose `:turn` has no closing eval) —
   this is a claim, not yet a proof.
9. **The inbox as the one arrival channel** means system errors are
   messages: the fault committer writes a message, not an error entity;
   error ANALYTICS ("which function errs most") become queries over
   messages `:about` a function plus the eval's `:error` map — plausible,
   unmeasured.
10. **From the second opinion — the questions I had not asked** (each
    blocking): atomic turn state (what one transaction claims a message,
    assigns a turn, records custody, and later one terminal disposition —
    the eval sketch has no disposition attribute, so a crash before the
    first eval leaves no fact about the open turn); interrupted-form
    evidence (a form that performed an effect and died before settlement);
    pop atomicity and wake behavior (is the trigger popped with the terminal
    disposition? what stops an open inbox edge waking the agent repeatedly?);
    bounded collection semantics (order, page, basis, count, omission for
    >1,000 rows); component ownership enforcement (Datahike admits one child
    under two owners and cascades on retractEntity — measured); family
    ambiguity (zero/one/several families per identity — measured false as a
    singleton); render purity, cycles, and versioning (no seen-set in the
    prototype; external-sink exclusion; today's renderer vs the original
    basis); a queryable error shape (`:map` is unmappable); definition and
    namespace conflicts (two agents per namespace collide on same-named
    defs; HEAD enforces one owner — `agent_namespace_test.clj:53-71`);
    complete teaching evidence before the full-parse bridge (a reader walk
    cannot resolve aliases, refers, macros, or names emitted by rendered
    values).
11. **Error VALUES the target must represent** (from the second opinion,
    none yet in the model): ambiguous/no family, no collection renderer,
    tied render candidates, renderer cycle, renderer effect refusal,
    pull-page elision, trigger already claimed, turn already open, stale
    process takeover, interrupted-before-first-eval, duplicate
    `[agent turn ordinal]`, invalid fork inheritance,
    result/error/blob exclusivity refusal.
12. **The retype is large**: ~18 schema files, the run loop, wake,
   message delivery, the transcript renderers, plus every test fixture.
   Reset, never migrate. It cannot be done in pieces that leave two
   mechanisms alive.

## 6. What I got WRONG along the way (so the reviewer can check them)

1. Kept `seon.cluster.message` as the family in every draft and in the
   atlas MODEL while the owner said the model is around the agent. Fixed
   here; the atlas and the one-platform doc §0e still carry the old
   names until this document is agreed.
2. Asked the owner questions whose answers were already on record
   (lost with a harness process exit and never written down).
3. Guessed family keys by hand in the first renderer probe; derivation
   caught it.
4. Proposed a `read-at` fact; the owner ruled pop.
5. Recommended dissolving the schema face property; the owner kept it
   as the general rung.
6. Reported "no tx-meta on this cluster" before checking whether any
   agent had written data there (none had; the stamp is test-proven).
7. Described the present in a document meant to describe the target
   (the first version of this §2); rewritten the same night.
8. Overstated evidence: a 3.3 ms figure from a warm run; "every identity
   attribute names exactly one family" from three hand-picked identities;
   "eval + process stamps" where the test proves receipt + user; line
   citations that pointed near, not at, the refusal.
9. Wrote "several agents may steward one namespace" as if it were free;
   HEAD enforces one owner and the program index identifies functions by
   namespace-qualified symbol — two stewards collide on same-named defs.
10. Produced too many documents. This one replaces the reading order for
   the design question; the others are evidence.

## 7. What to KEEP, CHANGE, DELETE (pointer, not a second register)

The [parallel-paths register](../research/parallel-paths-register-2026-09-03.md)
and the [one-platform document](repl-first-one-platform-2026-09-03.md)
§1–§4 hold the file:line evidence. In one line each: keep the eval facts,
blob tier, schema-derived root selection, `seon.print/fit` (refactored),
`seon.db/diff`, the wake, the prompt capture, the web delta packages, the
def-restore seam; change the render selection into the contract query,
`doc`/`dir` into live polymorphic dispatch, faces onto the family input
contract, the print ladder; delete the transcript projection, the prompt
distance shrink, private presentation ladders, bootstrap's candidate
lists and supervision strings, narration faces, `membership-diff`,
`editscript`, stored `read-result`, the ambient walk context — each only
when its replacement is live and proven.

## 8. The first thing to build, when the owner says go

Generate `(help)` for one real agent with one real owned family on a
scratch cluster and READ IT. Nothing else proves or refutes §1–§5 faster.

## 9. The second opinion — verdict and the three decisions (2026-09-04; §2–§3 rewritten 2026-09-05 in response)

The independent review ([second-opinion-2026-09-04.md](../research/second-opinion-2026-09-04.md),
Codex, no context from the design sessions, every cited claim verified at
the bytes and the probes re-run on its own scratch cluster) says: **the
target is not ready to implement.** Its verdict, in its words: the
document "is faithful about the experience the owner wants and unfaithful
about what that experience forces the database to own."

The decisive findings:
- **Deleting the run/receipt authority is the most likely reason the
  design fails.** It collapses four independent facts — process custody,
  message claim, turn identity and terminal disposition, form/effect
  interruption — into `:process` plus the presence of eval rows, and reads
  the absence of an eval as "no open turn" in exactly the crash window
  where no eval could have been written.
- **Component ownership is lifecycle, not nesting.** Datahike admits one
  child under two owners and cascade-deletes it when either owner is
  retracted (measured). "Pop" has two incompatible readings. The agent is
  a good QUERY ROOT, not a universal component owner.
- **One pull is not complete**: a positional pull silently returns at most
  1,000 children per attribute; the token budget after the pull cannot
  see the missing 9,000.
- **Addressed messages (HEAD) are better than inbox-owned ones** for
  "who sent what to whom" after handling and for outside senders; the
  inbox can still be the agent-rooted VIEW.
- **Recursive rendering is necessary and insufficient**: `(help)` over
  10,000 rows needs database-side counts and bounded collection
  descriptors chosen by SOME function — an assembler, however data-driven;
  and "never authored" is false — the honest claim is "all presentation
  bytes are owned by named, contracted render functions, except the one
  declared introduction."

The three decisions the owner must make before any production code
(reviewer's recommendation first in each):
1. **View versus ownership** — the agent is the root of a derived
   query/view; keep addressed messages and semantic domain refs; use
   components only where a one-owner cascade lifecycle is proved; keep one
   namespace per agent until agent-scoped definition identity and a
   co-steward conflict policy exist. Gives up: "the whole record is one
   physical tree."
2. **Turn authority** — keep a minimal run plus receipt/eval custody
   authority while unifying run-form and eval settlement into one eval
   row; delete the run only if a crash falsifier proves an equally atomic
   replacement. Gives up: "five families" as a metric.
3. **Bounded context contract** — define a collection descriptor (family,
   count, stable order, basis, bounded page, requery identity), the generic
   no-summary fallback, and limit P-TEACH-BEFORE-USE to forms with settled
   usage facts until the full-parse bridge lands. Gives up: "one pull
   renders everything."

**Build first (the reviewer's pick, which I accept over my §8):** a
transaction-only crash falsifier in a scratch database with the target
schema — `begin-turn`, `settle-eval`, `close-turn` — cut at (a) after
message arrival before the first eval, (b) after an effect begins before
settlement, (c) after the last eval before pop; from facts alone recover
exactly one trigger, owner, turn, interrupted form, and disposition, with
takeover as one transaction. If that needs a run/receipt entity, the
largest destructive premise is falsified before any renderer exists.
Then, and only then, generate and read `(help)` for one real agent.

**Response to the three decisions (2026-09-05, the owner's graph perspective):**
1. *View versus ownership* — taken as the reviewer advised, in graph
   terms: the agent is the root of DISCOVERY over an open graph; messages,
   evals, faults, and domain rows point AT the agent by refs and are
   queryable from either end; components only for defs and read-evidence;
   no generic data bag; one namespace per agent. Nothing is hidden.
2. *Turn authority* — the turn's facts (`:turn`, `:process`, `:trigger`,
   `:turn-opened-at`) live ON the agent entity and evals carry
   `:started-at`/`:settled-at`; this claims atomic begin/close/takeover as
   single transactions on one entity and must pass the crash falsifier
   before a run entity is declared unnecessary.
3. *Bounded context contract* — discovery returns collection descriptors
   (count, newest, order, bounded page, requery) from database-side
   aggregates; the render function decides the view; teaching-before-use
   is limited to names the reader can resolve until the bridge lands.

**Correction (owner, 2026-09-05):** an agent is independent of the
namespace it works. Every agent has its OWN namespace (`:seon.agent/namespace`,
its home, where its defs live) and may STEWARD other namespaces
(`:seon.agent/stewards`, a set — several agents may steward one namespace,
which resolves the reviewer's one-owner objection without collisions: defs
land in each agent's home, stewardship is a ref). §2.1 carries both
attributes.

**Open (2026-09-05, from a lane's reading of §3 against B13):** a form
that wraps a query in a render function returns TEXT, so its `result/<id>`
would denote text — but B13 promises the handle is DATA the agent can
`get-in`. Two consistent options: (a) the generated form IS the query, the
handle is its data, and the print floor applies the chosen render function
whose name appears on the handle line (`;; result/b2 rendered-by
inbox-view`); (b) the form is the wrapped call, the handle is text, and the
data is a second handle. The lab shows both for the same entry; the owner
chooses. Resolved 2026-09-05 (§3.4): a handle denotes what its form returned; the
generator chooses per entry which form to emit; both are recorded alike.
