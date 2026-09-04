---
type: prd
status: draft
tags: [prd, agent, context, render, data-model, architecture]
---

# The agent-centric design — one document, honest

*2026-09-04. Written because the owner could not follow the trail of
rulings, probes, and diagrams, and asked for a second opinion. This is
the condensed statement of what he wants as I understand it, what we
have evidence will work, what is unclear, and what I got wrong. It
supersedes the spelling of every earlier draft where they disagree. A
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

## 2. The data model — built AROUND the agent

The agent entity is the center of everything it can see or do. What it
owns hangs off it as component collections under its own attribute
namespace. What it uses (code, the cluster) it references. What the
system knows about it (runs, errors, schedule) points at it. There is no
cluster-level message family: a message lives in exactly one agent's
inbox.

```clojure
;; THE AGENT — its own entity; several may work one namespace; forkable
#:seon.agent
{:id          [:string {:seon.db/identity true}]
 :namespace   :seon.db/ref                      ; → the code index (a :seon.ns row)
 :forked-from :seon.db/ref                      ; → agent
 :inbox       [:set {:seon.db/component true} :seon.db/ref]   ; → agent.message
 :evals       [:set {:seon.db/component true} :seon.db/ref]   ; → agent.eval  (THE transcript)
 :defs        [:set {:seon.db/component true} :seon.db/ref]   ; → agent.def
 :notes       [:set {:seon.db/component true} :seon.db/ref]   ; → agent.note
 :plan        [:set {:seon.db/component true} :seon.db/ref]   ; → agent.plan-item
 :data        [:set {:seon.db/component true} :seon.db/ref]}  ; → anything the agent stores for itself or its user

;; A MESSAGE lives in ONE inbox. Sending = transact it into the recipient's :seon.agent/inbox.
;; Handling = pop it (one retract; history keeps it). The sender keeps only its eval.
#:seon.agent.message
{:id :string-identity :content :string :at :inst :from :seon.db/ref :about :seon.db/ref :caused-by :seon.db/ref}

;; ONE eval family: the parser-saved form and its result in one row (ruled 64).
#:seon.agent.eval
{:id :string-identity :ordinal :int :source :string :started-at :inst
 :result-edn :string :result-blob :seon.blob/digest :error :seon.db/ref
 :read-basis-t :seon.db/basis-t :read-evidence [:vector {:seon.db/component true} :seon.db/ref]
 :rendered-by :symbol}                          ; only when the floor rendered it

;; the agent's own defs, notes, plan items — same shape of ownership
#:seon.agent.def  {:id :string-identity :ns :seon.db/ref :name :symbol :value-edn :string :blob :seon.blob/digest :atom? :boolean}
#:seon.agent.note {:id :string-identity :content :string :about :seon.db/ref}
#:seon.agent.plan-item {:id :string-identity :title :string :parent :seon.db/ref :needs [:set :seon.db/ref] :subjects [:vector {:seon.db/component true} :seon.db/ref] :completed-at :inst}

;; SYSTEM facts point AT the agent; the agent never writes them
#:seon.run   {:id … :agent :seon.db/ref :trigger :seon.db/ref :opened-at :inst :closed-at :inst :process …}
#:seon.error {:id … :agent :seon.db/ref :run :seon.db/ref :kind :keyword :message :string :at :inst}

;; the cluster is a container, the program index is shared and referenced
#:seon.cluster {:name … :agents [:set :seon.db/ref]}
```

What the agent's record IS, therefore: `(seon.db/pull '[* {:seon.agent/inbox [*]}
{:seon.agent/evals [*]} {:seon.agent/notes [*]} {:seon.agent/data [*]}
{:seon.agent/namespace [:seon.ns/name {:seon.fn/_ns [:seon.fn/sym :seon.fn/doc]}]}]
[:seon.agent/id "cal-steward"])` — one pull. The only reverse queries
left are the system's facts about the agent (its runs, its errors, its
scheduled tasks).

User data (a calendar) is the agent's data: the agent declares the
family in its namespace (`:acme.calendar/event …`), stores rows, and
attaches them under `:seon.agent/data` so they are part of its record —
or references them from its own families; both are one transact.

Provenance rides the transaction, not the entity: `transact!` already
stamps `:seon.db/receipt` (the eval) and `:seon.db/process` on the
transaction entity (proven by `receipt_write_carrier_test`); the
data-first writer boundary adds `:seon.db/user`. "Who wrote this" is a
join, so no family carries an `agent` attribute for provenance — only
ownership refs.

## 3. How the context is generated from that model

1. **The record is one pull** (§2). The walk adds one reverse query per
   system family pointing at the agent (runs, errors, schedule) —
   collection-first, counts before rows.
2. **`(help)` renders the record**: for every owned collection, the
   family's collection render function summarizes it (count, span,
   newest, its own notion of what matters) and names its per-entity face;
   then the functions whose contracts accept the family (the processing
   functions, one query over arity input refs); then the root commands
   the session's forms use. Different records, different helps.
3. **Teaching before use**: every name a later entry uses that the agent
   has not seen gets its `doc`/`dir` first; the agent's own correct prior
   use satisfies the demand (52b).
4. **Entries** are `ns=> form`, the value rendered by the most specific
   render function (the query of §4.3), then `;; result/<id>` and, only
   when the floor printed it, `rendered-by <fn>`.
5. **The trigger last** (the newest message / the run's trigger).
6. **The watch**: each generated query is an eval with read-evidence;
   Datahike `listen!` through `wake/route!` wakes the generator when a
   commit touches those attributes; the woken pass settles ONE diff form
   per stale query, rendered by the same function. Every turn is a full
   regeneration; the diff eval is a fact and reappears.
7. **The page** is the same walk through `/html`.

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
8. **The retype is large**: ~18 schema files, the run loop, wake,
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
7. Produced too many documents. This one replaces the reading order for
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
