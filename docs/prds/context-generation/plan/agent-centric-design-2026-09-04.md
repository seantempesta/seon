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

## 2. The TARGET data model — five families, all around the agent

The core knows five things. Everything else is an attribute on one of
them, a message in an inbox, or a family the agent declares for itself.

```
                         ┌──────────────────────────┐
   program index ◀──ref──│          AGENT           │──ref──▶ forked-from agent
 (ns · fn · arity ·      │  id · namespace · process│
  schema rows, shared)   └────────────┬─────────────┘
                     owns (component collections)
          ┌──────────────┬────────────┴───────────────┐
          ▼              ▼                            ▼
      INBOX           EVALS                          DATA
   messages from    the transcript: every form     whatever the agent stores —
   agents, users,   it ran + result + the defs     its own declared families
   AND the system   it produced + what it read     (notes, plans, a user's calendar…)
```

```clojure
;; 1. THE AGENT — the center. Several may steward one namespace; forks are agents.
#:seon.agent
{:id          [:string {:seon.db/identity true}]
 :namespace   :seon.db/ref                  ; → the :seon.ns row it works (the code index is shared)
 :forked-from :seon.db/ref                  ; → agent
 :process     :string                       ; CUSTODY: which process is running it now; absent = idle
                                            ;   (replaces the run entity's custody)
 :inbox       [:set {:seon.db/component true} :seon.db/ref]   ; → message
 :evals       [:set {:seon.db/component true} :seon.db/ref]   ; → eval
 :data        [:set {:seon.db/component true} :seon.db/ref]}  ; → any entity the agent stores

;; 2. A MESSAGE — the ONE arrival channel. From another agent, from a human, or from the SYSTEM
;;    (an error about this agent, a scheduled task's result, a maintenance notice — all messages).
;;    It lives in exactly one inbox; handling POPS it (retract; history keeps it).
#:seon.agent.message
{:id      [:string {:seon.db/identity true}]
 :content :string
 :at      :inst
 :from    :seon.db/ref        ; → agent (absent = outside: a human or the system)
 :about   :seon.db/ref        ; → anything
 :reply-to :seon.db/ref}      ; → message
;; replaces: seon.cluster.message, seon.error entities pointing at agents, maintenance requests,
;;           the run's trigger — the trigger IS the popped message the turn began on

;; 3. AN EVAL — the transcript row: what the agent ran, what came back, what it defined, what it read. (§3.1 is the exact shape.)
;;    Turns are a grouping ATTRIBUTE, not an entity. Provenance is the eval: every fact written
;;    by this form is stamped with it on the transaction.
#:seon.agent.eval
{:id        [:string {:seon.db/identity true}]
 :turn      [:int {:min 0}]                 ; which turn (replaces the run entity)
 :ordinal   [:int {:min 0}]                 ; position in the turn
 :trigger   :seon.db/ref                    ; → the message this turn began on (first eval of a turn)
 :source    :string                         ; the form as parsed
 :at        :inst
 :result    :string                         ; EDN, or…
 :result-blob :seon.blob/digest             ; …the blob when large
 :error     :map                            ; the flat error value, if the form failed (no error entity)
 :defines   [:set {:seon.db/component true} :seon.db/ref]   ; → def: name + value the form defined
 :reads     [:vector {:seon.db/component true} :seon.db/ref] ; the read-evidence: attributes + revisions (the watch's interest)
 :prompt    :seon.blob/digest               ; if this eval was a model call: the exact bytes sent (replaces captures)
 :rendered-by :symbol}                      ; only when the floor rendered the result
#:seon.agent.def {:name :symbol :value :string :blob :seon.blob/digest :atom? :boolean}
;; the agent's live defs = for each name, the newest eval that defines it — a query, no def family

;; 4. SCHEMAS — facts the agent (or boot) asserts; the Malli form IS the database declaration.
#:seon.schema {:key :qualified-keyword-identity :form :string}

;; 5. THE PROGRAM INDEX — shared, referenced: :seon.ns (name, requires) · :seon.fn (sym, doc, arities)
;;    · :seon.fn.arity (inputs, outputs — render and processing functions are FOUND here by contract)

;; a cluster is a set of agents on one database branch — a container, nothing more
#:seon.cluster {:name :string-identity :agents [:set :seon.db/ref]}
```

**What this rearranges away** (each was a family at HEAD): runs (→
`:turn`/`:trigger` on evals + `:process` on the agent), receipts and run
forms (→ one eval), defs (→ `:defines` on the eval that made them), error
entities and maintenance requests (→ system messages in the inbox), prompt
captures (→ `:prompt` on the eval that called the model), `my.note` and
`my.plan` as core families (→ agent-declared data families shipped as
examples), the cluster-level message family (→ the inbox). Twelve families
become five plus whatever the agent declares.

**What the agent's record IS:** one pull —
`(seon.db/pull '[* {:seon.agent/inbox [*]} {:seon.agent/evals [*]} {:seon.agent/data [*]}
{:seon.agent/namespace [:seon.ns/name {:seon.fn/_ns [:seon.fn/sym :seon.fn/doc]}]}] [:seon.agent/id "cal-steward"])`.
Nothing points at the agent from outside except other agents' `:from`
refs and the cluster's `:agents` set.

**User data** (a calendar) is agent data: the agent declares
`:acme.calendar/event` in its namespace, transacts rows, and attaches
them under `:seon.agent/data`. Their summary and per-entity faces are
render functions in that namespace; their processing functions are found
by contract.

**Provenance** is the transaction stamp: `:seon.db/eval` (which form
wrote this) and `:seon.db/process`. A human's writes arrive as messages
or as evals of the UI's agent, so there is no separate user stamp to
invent.

## 3. How the context is built — for the AI and for the human — from data and looked-up render functions only

**The whole context is one expression.** For the AI:
`(render (pull agent-record) :seon.render/ai)`. For the human:
`(render (pull agent-record) :seon.render/html)`. Same pull, same
lookup, two lenses. There is no assembler, no template, no transcript
builder: `render` is one recursive function that, for any value, looks
up the most specific render function for that value's family and calls
it; that function calls `render` on its parts; the base case is the
floor. Cross-cutting behavior (budget, provenance, error-as-value) is
middleware around every call.

**The lookup, precisely.** Given a value `v` and a projection `P`:
1. *family* — if `v` carries `:seon.render/ai` (content or a symbol), use
   it; else if `v` is a map with an identity attribute, its family is the
   entity schema that references that attribute (one query; every
   identity attribute names exactly one family); if `v` is a sequential
   of such maps, its family is `[:coll F]`; else the floor.
2. *candidates* — one query over the program index: arities whose input
   refs are covered by `{F} ∪ {injectables}` and whose output ref is `P`.
3. *order* — the viewing agent's own namespace, then other agents'
   namespaces by distance from the viewer's (a rule over `:seon.ns/requires`),
   then the family's general face (the schema property), then the floor;
   within a rung, more required-key coverage wins, then newest; a
   remaining tie is loud.
4. *call* — wrap the chosen function in the middleware (fit to the
   profile's token budget, announce provenance if it is the floor,
   errors become flat values) and call it with `v` and the ctx.

**The dolls, for both lenses.** The agent's family has a general render
function pair — THAT is the layout: for `/ai` it emits `(help)`'s
summary first, then each eval in `[turn ordinal]` order, then the
REPL-state line; for `/html` it emits the page: the record summary card,
the inbox, the transcript as blocks, one panel per data collection. It
never formats a value itself; it calls `render` on the inbox (→ the
message collection face), on each eval (→ the eval face), on each data
collection (→ that family's summary face), on the namespace (→ `(doc ns)`
data). The eval face prints `ns=> source`, calls `render` on the stored
result, and appends `;; result/<id>` plus `rendered-by` when the floor
printed it. A calendar event renders through `acme.calendar/event-ai` in
the transcript and through `event-html` on the page because the two are
found by the same lookup with a different `P`. An agent that writes
`inbox-view` changes both projections of its inbox at once.

**Where the bytes come from — every byte, no exceptions:** the agent face
(layout), the eval face (entry grammar), a family face or the agent's own
render function (values), `(doc …)`/`(dir …)` data rendered by their
faces (teaching), the floor (`seon.print/fit`, a nameable function).
Nothing is concatenated outside a render function; nothing is stored as
rendered text.

### 3.1 The eval model — optimal for querying, extending, and regenerating

The transcript is the agent's evals, nothing else. Three requirements
decide the shape: it must be ONE query to read in order; adding a form
must be ONE transact; regenerating (every turn, and compaction) must
re-render from stored VALUES so a better render function improves old
entries too.

```clojure
#:seon.agent.eval
{:id            [:string {:seon.db/identity true}]   ; "<agent>/<turn>/<ordinal>" — deterministic, so result/<id> is derivable
 :turn          [:int {:min 0}]                       ; grouping attribute; no turn entity
 :ordinal       [:int {:min 0}]                       ; order within the turn → the transcript order is [turn ordinal]
 :at            :inst
 :author        [:enum :agent :system]                ; the agent typed it, or the generator did on its behalf (help, discovery, diff)
 :trigger       :seon.db/ref                          ; on the turn's first eval: the inbox message this turn began on
 :source        :string                               ; the form as parsed (the parser-saved text)
 :result        :string                               ; THE VALUE, as EDN — admitted and bounded WITH ELISION VALUES, never a print node
 :result-blob   :seon.blob/digest                     ; the complete value when :result is a bounded projection of it
 :result-family [:set :qualified-keyword]             ; derived at settlement: the families the result's identities belong to (48b)
 :error         :map                                  ; the flat error value when the form failed
 :defines       [:set {:seon.db/component true} :seon.db/ref]   ; → {name value|blob atom?} — the defs this form produced
 :reads         [:vector {:seon.db/component true} :seon.db/ref] ; attributes + revisions the form read (the watch's interest)
 :effects       [:set :qualified-keyword]             ; external sinks the form reached (derived from the program graph) → replay-only on regeneration
 :prompt        :seon.blob/digest                     ; on the turn's first eval: the exact bytes sent to the model
 :rendered-by   :symbol}                              ; only when the floor rendered the result
```

Why each choice:
- **component of the agent** — one pull returns the record with its
  transcript; retracting the agent retracts its history; nothing else
  points at evals.
- **`[turn ordinal]` as data, no run entity** — the transcript at turn N
  is `(sort-by (juxt :turn :ordinal) evals)` filtered to `turn ≤ N`;
  "extend with one form" is one new row with the next ordinal; custody
  lives on the agent (`:process`), a dead process's open turn is the turn
  whose last eval has no closing disposition — recoverable from evals
  alone.
- **the result is a VALUE** — at HEAD `result-edn` is a print node
  (verified: `#:seon.print{:face :seon.print/map …}`), i.e. the OLD face
  frozen forever. Storing the admitted value (bounded, with elision values
  that carry a requery form; the complete value in the blob) is what
  makes regeneration honest: the current best render function prints old
  results; `(get-in result/t3-7 […])` works on real data; compaction can
  render an old eval as its handle plus one line without losing anything.
- **`:result-family` derived at settlement** — the render lookup does
  not have to re-read the value to know its face; "which evals returned
  events" is `[?e :seon.agent.eval/result-family :acme.calendar/event]`;
  already-satisfied teaching demands are a query, not string matching.
- **`:author`** — the generator's entries (help, discovery, diff) are
  evals too; the AI's transcript and the human's page show them the same
  way, and every turn's regeneration is the same pull.
- **`:reads` + `:effects`** — the watch and the missile rule are facts on
  the row: stale = a commit touched an attribute in `:reads`; a form with
  `:effects` replays its stored result and is never re-run.
- **`:defines` on the eval** — the agent's live defs are "for each name,
  the newest defining eval": a query, no def family, and a fork that
  copies evals copies its defs.

**The three operations, as queries and transacts:**

```clojure
;; read the transcript for regeneration (every turn) — ONE pull, ordered by the layout face
(seon.db/pull '[{:seon.agent/evals [:seon.agent.eval/turn :seon.agent.eval/ordinal :seon.agent.eval/source :seon.agent.eval/result :seon.agent.eval/error :seon.agent.eval/rendered-by]}]
              [:seon.agent/id "cal-steward"])
;; extend: the agent (or the generator) ran one more form — ONE transact, the parser's row + settlement's result
(seon.db/transact! [{:seon.agent.eval/id "cal-steward/3/7" :seon.agent.eval/turn 3 :seon.agent.eval/ordinal 7 :seon.agent.eval/author :agent
                     :seon.agent.eval/source "(acme.calendar/week …)" :seon.agent.eval/result "[…]" :seon.agent.eval/result-family #{:acme.calendar/event}
                     :seon.agent.eval/at #inst "…"}
                    [:db/add [:seon.agent/id "cal-steward"] :seon.agent/evals [:seon.agent.eval/id "cal-steward/3/7"]]])
;; compaction = the same pull, rendered under the budget: the layout face shows the newest evals whole and
;; older ones as their handle + one line (an elision value with the requery form) — no fact changes, nothing is lost
;; which evals returned calendar events (already-satisfied demand, analytics)
(seon.db/q '[:find ?id :where [?e :seon.agent.eval/result-family :acme.calendar/event] [?e :seon.agent.eval/id ?id]])
;; the agent's live defs
(seon.db/q '[:find ?name (max ?turn) :where [?e :seon.agent.eval/defines ?d] [?d :seon.agent.def/name ?name] [?e :seon.agent.eval/turn ?turn]])
```

### 3.2 The sequence of one turn, as data flow

inbox message arrives (a fact) → the wake → the generator pulls the record
→ renders `/ai` (help summary, teaching, evals, trigger last) → the model
replies with forms → the parser transacts one eval row per form (source,
turn, ordinal) → evaluation settles each row (result value, family,
reads, effects, defines) → the last form pops the trigger → the page
re-renders `/html` from the same record. The next turn starts from the
same pull. Nothing in this sequence is a string until a render function
returns one.

## 4. What we have EVIDENCE will work

| claim | evidence |
|---|---|
| A recursive renderer (select by family → call; each face calls render on its parts) with Ring-style middleware for bounding/provenance/errors and a threaded ctx | prototyped on the live cluster, 3 passes, 3.3 ms; swapping one face changed only its entries (`research/scripts/recursive-render-probe-2026-09-03.clj`) |
| A value's family derives from its identity attribute (every identity attribute names exactly one entity family) | queried live, 0.1 ms raw |
| Namespace distance for "closest other agent wins" is one Datalog query with rules | 54 namespaces within 3 hops of root, 16 ms cold / 0.07 warm |
| Schemas → Datahike attributes → instrumentation all derive from facts; an agent-declared schema settles through the run loop with accretion allowed and breaking redefinition refused | `schema/datahike.clj:221-262`, `run.clj:1436-1460`, `sci/eval.clj:591-602` |
| The watch primitive and per-query interest exist | `wake.clj:168-256`, read-evidence revisions, `:seon.render.web/interest` |
| `diff` at a recorded basis shows additions and deletions; a `since` view cannot (lookup refs to older entities fail inside it) | both measured live |
| Recursive and reverse pulls work as Datahike documents them | measured live |
| Transactions carry the eval and process stamps | `receipt_write_carrier_test` |
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
10. **The retype is large**: ~18 schema files, the run loop, wake,
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
8. Produced too many documents. This one replaces the reading order for
   the design question; the others are evidence.

## 7. What to KEEP, CHANGE, DELETE (pointer, not a second register)

The [parallel-paths register](../research/parallel-paths-register-2026-09-04.md)
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
