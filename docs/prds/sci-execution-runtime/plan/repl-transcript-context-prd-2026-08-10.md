---
type: prd
status: draft
tags: [prd, render, agent]
---

# The agent's REPL history is the context, walk, bootstrap, and live view

This is the line-by-line design for the owner ruling in
[`agent-interface-economy-2026-08-10.md`](agent-interface-economy-2026-08-10.md).
It records received rulings and supplies priced options only where the owner
has not yet ruled, so the remaining lines can be confirmed or marked up before
production work starts.

## How this works (read this first)

An agent in Seon operates a real Clojure REPL. Every model reply is parsed
into ordered forms; every form is evaluated; every evaluation settles to a
value. Both halves of every exchange are already durable database facts: the
form's exact submitted text is stored at `:seon.cluster.run.form/source`, and
the value it produced is stored at `:seon.cluster.eval/result-edn` as
serialized print-node data (large values spill to a blob). This was verified
against the live `default` cluster on 2026-08-10—see
[Storage verification](#storage-verification--the-facts-already-exist-live-default-2026-08-10).

This PRD makes one move: **the agent's prompt IS its own REPL history, replayed
from those stored facts.** To build the model's context we do not assemble
prose, summaries, headers, or schema walls; we query the agent's ordered
history—bootstrap forms, inbound messages, submitted forms, settled values—and
print it exactly as REPL scrollback: `namespace=> form`, then the value that
form actually produced. The web page for the same agent is not a different
report; it is the same ordered history printed to a second target (HTML Hiccup
instead of text) and fitted with a different size profile. **The prompt and
the page are two printings of one history.**

The agent's history is a replay, not a reconstruction:

1. **Nothing re-executes at render time.** Rendering a history never evaluates
   a form. Forms are display text; values are recorded facts, decoded and
   printed. The only code that runs during rendering is a declared render
   producer—a pure function whose argument is the value being rendered—and
   the generic value printer beneath it (`src/seon/render.clj:238-265`).
2. **Exactly one form is synthesized.** Every agent form is verbatim stored
   source. The single exception is an inbound message: arrival is displayed
   as an injected `(my.message/read "<id>")` form whose value is the stored
   message fact—an honest, rerunnable read the system writes on the agent's
   behalf. Bootstrap is not synthesized either: its forms execute once, for
   real, when the agent is created, and their receipts are stored like any
   other run's; every later prompt replays them from facts.
3. **Teaching is demonstrated retrieval.** A fresh agent's opening context is
   its stored bootstrap history: it looks as if the agent already ran
   `(help)`, a `require`, a few bare `docs` calls, one successful `defn`, and
   its task-message read—each with the actual value it returned. There is no
   API wall or schema dump; anything deeper is one more `doc` or `docs` call
   away.

Everything else in this document follows from that move: one pure derivation
(`seon.render.walk/history`) returning ordered form/value entries; two
independent projection passes (AI text under the agent profile, HTML under the
page profile) that must agree on entry identities but not bytes; a debug route
whose pane is byte-equal to the next provider prompt; a `/` system view that
is the same walk rooted at the cluster under a preview profile; and deletion
of every mechanism this replaces (the separate history assembler, schema
wall, comment-framed headers, per-family budgets, and fleet append).

Vocabulary used throughout: a **target** is a print destination—AI text or
HTML Hiccup (the two P's of the REPL). A **profile** is a declared fit policy
(token budget, maximum depth, maximum children, composition) selected by a
consumer; fitting never changes which entries exist, only how much of each
value is shown. The **walk** is the existing bounded traversal of database
refs outward from a root entity. **Open** maps and entries admit extra keys
under the accretion rule.

## Research completed

I read every requested source, schema, skill, ruling, issue, and research file
end to end. The evidence indexes are the
[`ruled direction`](agent-interface-economy-2026-08-10.md),
[`context audit`](../research/context-quality-audit-2026-08-10.md),
[`drive`](../research/model-authoring-drive-2026-08-10.md),
[`observer`](../research/model-authoring-observer-2026-08-10.md),
[`critique`](repl-transcript-context-prd-critique-2026-08-10.md), and
[`fresh-eyes audit`](repl-transcript-context-prd-wtf-audit-2026-08-10.md).
Current source evidence is cited at each affected contract below.

The worked examples, grammar, phase regressions, and Phase 7 falsifier are the
acceptance contract. The prior critique's evidence remains in
[`repl-transcript-context-prd-critique-2026-08-10.md`](repl-transcript-context-prd-critique-2026-08-10.md);
this revision applies it without repeating its disposition table.

## Dependency ledger

| Dependency | Selected revision | Boundary read and first-party owner |
|---|---|---|
| Datahike | `10540578248e` | Relation and tuple inputs become query relations at `reference-code/datahike/src/datahike/query.cljc:832-890`; all calls remain through `src/seon/db.clj:779-823`. |
| Malli | `80138076960e` | Schema properties/forms are dependency-owned protocol operations at `reference-code/malli/src/malli/core.cljc:30-43`; Seon's declared producer/profile properties remain in `resources/seon/schemas/seon.render.edn:1-55` and `resources/seon/schemas/seon.render.profile.edn:1-18`. |
| core.async | `dc35f3e0d7bc2eef502e77982f48641f025c8051` | A mult distributes each value to all taps and requires buffering to isolate slow taps at `reference-code/core.async/src/main/clojure/clojure/core/async.clj:797-845`; Seon's package taps and writer live at `src/seon/render/web.clj:673-800`. |
| Datastar Clojure | `1cef624e9e59a2ea79ffe2f65df2e7b06f8198d2` | `patch-elements!` sends ID-bearing fragments over an SSE response at `reference-code/datastar-clojure/src/dev/examples/snippets.clj:10-16,26-33`; Seon's one patch call and target package live at `src/seon/render/web.clj:978-1085`. |

## One agent-history owner

`seon.render.walk/history` receives an open request map and returns the one
ordered value consumed by prompt/page/debug/system. Here “the agent's history”
never means Datahike's `:history` time-axis:

```clojure
(seon.render.walk/history
 {:seon.render.walk/root [:seon.cluster.agent/id "task-agent-17"]
  :seon.render.walk/database-value db
  :seon.render.walk/profile :seon.render.profile/agent})
=>
[{:seon.repl/form "(help)"
  :seon.repl/value "You are task-agent-17 in a Seon cluster…"}
 <more open form/value entries>]
```

Public `seon.render/walk` remains a string; `history` is a new data-returning
contract, never silent redefinition (`src/seon/render/walk.clj:469-496,568-671`).
Phase 3 converts prompt/web/agent consumers and deletes transcript assembly in
one change (`src/seon/render/transcript.clj:26-226,639-778`). At one database
value, debug AI bytes equal the next acquired provider context.

Entries sort by one lexicographically compared key vector; positions are:

- **bootstrap form** — `[0 ordinal form-id]`: band 0, form ordinal, identity;
- **inbound message** — `[1 at-ms 0 tx ordinal message-id]`: band 1, recorded
  arrival instant, message sub-band 0, transaction, transaction ordinal,
  identity; and
- **run form** — `[1 run-opened-ms 1 ordinal form-id]`: band 1, stored run
  opening instant, form sub-band 1, form ordinal, identity.

Sub-bands put a same-instant message before a run; stored identities break ties
and no render clock participates. **An unsettled form** stays in position: text
ends after its exact form, HTML sets `data-settled="false"`; no placeholder.
Runs named by
`:seon.cluster.run/supersedes` are excluded entirely
(`src/seon/render/transcript.clj:85-91,400-416`).

## Worked example A — complete fresh-task context

This is the recommended 12-exchange starting candidate. Each exchange first
explains provenance; the flat block afterward is the byte authority.

### Exchange 1 — standing orientation `[stored — bootstrap receipt]`

IN (exact `:seon.cluster.run.form/source`):

```clojure
(help)
```

OUT (recorded value decoded from `:seon.cluster.eval/result-edn`):

```clojure
"You are task-agent-17 in a Seon cluster, operating a real Clojure REPL. Your reply is read as ordered forms; each form settles to the value printed below it. Batch independent forms because model round trips are expensive. Every function in the cluster program graph is callable. Public functions are the API; private functions remain inspectable. A defn with a complete :malli/schema becomes a durable program fact; scratch defs remain the agent's defs. The cluster is one graph database, so use dir, doc, docs, and seon.db/q instead of guessing. Send work with my.message/send. Finish with my.run/complete or my.run/wait."
```

### Exchange 2 — enter the assigned namespace `[stored — bootstrap receipt]`

Bootstrap forms execute once, for real, when the agent is created; their
receipts are stored like any other run's. That is why this value has a stable
stored address rather than one regenerated per prompt.

IN:

```clojure
(in-ns 'my.agents.task-agent-17)
```

OUT:

```clojure
#object[sci.lang.Namespace 0x1a2b3c4d "my.agents.task-agent-17"]
```

### Exchange 3 — load common namespaces `[target — Phase 3 executes once]`

IN:

```clojure
(require '[my.run :as run] '[my.message :as message] '[seon.db :as db])
```

OUT:

```clojure
nil
```

### Exchange 4 — retrieve run docs `[target — Phase 3 executes once]`

IN:

```clojure
(docs 'my.run/complete :my.run/result 'my.run)
```

OUT:

```clojure
[{:seon.program/name my.run/complete, :seon.fn/arglists ([result]), ...}
 {:seon.schema/key :my.run/result, :seon.schema/accepted-by [my.run/complete], ...}
 {:seon.ns/name my.run, :seon.ns/public-functions [my.run/complete my.run/wait], ...}]
```

### Exchange 5 — retrieve message docs `[target — Phase 3 executes once]`

IN:

```clojure
(docs 'my.message/send 'my.message/decline 'my.message)
```

OUT:

```clojure
[{:seon.program/name my.message/send, :seon.fn/arglists ([to content] [to content about]), ...}
 {:seon.program/name my.message/decline, :seon.fn/arglists ([to about reason]), ...}
 {:seon.ns/name my.message, :seon.ns/public-functions [my.message/decline my.message/send], ...}]
```

### Exchange 6 — inspect the assigned namespace `[target — Phase 3 executes once]`

IN:

```clojure
(docs 'my.agents.task-agent-17)
```

OUT:

```clojure
[{:seon.ns/name my.agents.task-agent-17,
  :seon.ns/doc nil,
  :seon.ns/public-functions [],
  :seon.ns/used-schemas []}]
```

### Exchange 7 — request deep schema documentation `[target — Phase 3 executes once]`

IN (the fully namespaced map arity carries options):

```clojure
(docs {:seon.program/identities [:my.run/result]
       :seon.program/detail :deep})
```

OUT:

```clojure
[{:seon.schema/key :my.run/result,
  :seon.schema/definition [:string {:min 1}],
  :seon.schema/example "The report is complete and the focused tests pass.", ...}]
```

### Exchange 8 — query the program graph `[target — Phase 3 executes once]`

IN:

```clojure
(db/q '[:find (count ?function) . :where [?function :seon.fn/sym _]])
```

OUT:

```clojure
2775
```

### Exchange 9 — author one valid function `[target — Phase 3 executes once]`

IN:

```clojure
(defn total
  "Sum integers."
  {:malli/schema [:=> [:cat [:sequential :int]] :int]}
  [values]
  (reduce + 0 values))
```

OUT:

```clojure
#'my.agents.task-agent-17/total
```

### Exchange 10 — call the new function `[target — Phase 3 executes once]`

IN:

```clojure
(total [3 5 8])
```

OUT:

```clojure
16
```

### Exchange 11 — read back its contract `[target — Phase 3 executes once]`

IN:

```clojure
(db/q '[:find ?spec . :in $ ?sym
        :where [?function :seon.fn/sym ?sym]
               [?function :seon.fn/spec ?spec]]
      "my.agents.task-agent-17/total")
```

OUT:

```clojure
"[:=> [:cat [:sequential :int]] :int]"
```

### Exchange 12 — receive the task `[injected — one synthesized form; stored message value]`

IN (written by the system on the agent's behalf; honest and rerunnable):

```clojure
(my.message/read "inbound-536871250-0")
```

OUT (pulled admitted message row):

```clojure
{:seon.cluster.message/id "inbound-536871250-0",
 :seon.cluster.message/to [:seon.cluster.agent/id "task-agent-17"],
 :seon.cluster.message/content "Inspect the failed invoice import, explain the cause, and propose the smallest durable fix."}
```

### What the model literally sees

```text
user=> (help)
"You are task-agent-17 in a Seon cluster, operating a real Clojure REPL. Your reply is read as ordered forms; each form settles to the value printed below it. Batch independent forms because model round trips are expensive. Every function in the cluster program graph is callable. Public functions are the API; private functions remain inspectable. A defn with a complete :malli/schema becomes a durable program fact; scratch defs remain the agent's defs. The cluster is one graph database, so use dir, doc, docs, and seon.db/q instead of guessing. Send work with my.message/send. Finish with my.run/complete or my.run/wait."
user=> (in-ns 'my.agents.task-agent-17)
#object[sci.lang.Namespace 0x1a2b3c4d "my.agents.task-agent-17"]
my.agents.task-agent-17=> (require '[my.run :as run] '[my.message :as message] '[seon.db :as db])
nil
my.agents.task-agent-17=> (docs 'my.run/complete :my.run/result 'my.run)
[{:seon.program/name my.run/complete, :seon.fn/arglists ([result]), :seon.fn/doc "Finish this run with a reply for its requester.", :seon.fn/tests-reaching [my.run-test/a-disposition-is-an-ordinary-value]}
 {:seon.schema/key :my.run/result, :seon.schema/definition [:string {:min 1}], :seon.schema/accepted-by [my.run/complete], :seon.schema/returned-by [], :seon.schema/contained-by [:my.run/completed]}
 {:seon.ns/name my.run, :seon.ns/public-functions [my.run/complete my.run/wait], :seon.ns/used-schemas [:my.run/completed :my.run/result :my.run/waited]}]
my.agents.task-agent-17=> (docs 'my.message/send 'my.message/decline 'my.message)
[{:seon.program/name my.message/send, :seon.fn/arglists ([to content] [to content about]), :seon.fn/doc "Address a message to another agent.", :seon.fn/tests-reaching [my.message-test/sends-an-addressed-message]}
 {:seon.program/name my.message/decline, :seon.fn/arglists ([to about reason]), :seon.fn/doc "Decline an assignment and explain why to its sender.", :seon.fn/tests-reaching [my.message-test/declines-an-assignment]}
 {:seon.ns/name my.message, :seon.ns/public-functions [my.message/decline my.message/send], :seon.ns/used-schemas [:my.message/about :my.message/content :my.message/to :my.message/value]}]
my.agents.task-agent-17=> (docs 'my.agents.task-agent-17)
[{:seon.ns/name my.agents.task-agent-17,
  :seon.ns/doc nil,
  :seon.ns/public-functions [],
  :seon.ns/used-schemas []}]
my.agents.task-agent-17=> (docs {:seon.program/identities [:my.run/result] :seon.program/detail :deep})
[{:seon.schema/key :my.run/result,
  :seon.schema/definition [:string {:min 1}],
  :seon.schema/properties {:min 1},
  :seon.schema/example "The report is complete and the focused tests pass.",
  :seon.schema/example-seed 1224898382,
  :seon.schema/program-commit #uuid "6a7a4c6f-9745-55b1-ae67-510c8e622317",
  :seon.schema/accepted-by [{:seon.fn/sym my.run/complete,
                             :seon.fn/arglists ([result])}],
  :seon.schema/returned-by [],
  :seon.schema/contained-by [:my.run/completed]}]
my.agents.task-agent-17=> (db/q '[:find (count ?function) . :where [?function :seon.fn/sym _]])
2775
my.agents.task-agent-17=> (defn total
                            "Sum integers."
                            {:malli/schema [:=> [:cat [:sequential :int]] :int]}
                            [values]
                            (reduce + 0 values))
#'my.agents.task-agent-17/total
my.agents.task-agent-17=> (total [3 5 8])
16
my.agents.task-agent-17=> (db/q '[:find ?spec . :in $ ?sym :where [?function :seon.fn/sym ?sym] [?function :seon.fn/spec ?spec]] "my.agents.task-agent-17/total")
"[:=> [:cat [:sequential :int]] :int]"
my.agents.task-agent-17=> (my.message/read "inbound-536871250-0")
{:seon.cluster.message/id "inbound-536871250-0",
 :seon.cluster.message/to [:seon.cluster.agent/id "task-agent-17"],
 :seon.cluster.message/content "Inspect the failed invoice import, explain the cause, and propose the smallest durable fix."}
my.agents.task-agent-17=>
```

`[target]` values are normative, not claims about current behavior. Phase 3
executes them once and stores the resulting print data in receipts. The candidate
is intentionally not minimal; Phase 6 ablates it against task outcomes.
The successful `defn` replaces the current deliberate `:any` and wrong-arity
teaching failures at `resources/seon/bootstrap.edn:49-70`, which the audit
found were committed as faults
([audit, lines 149–202](../research/context-quality-audit-2026-08-10.md#L149)).

## Worked example B — root mid-conversation

The human message uses recommended HUMAN-2 *(defined below: inject an honest
`my.message/read` rather than paste a literal)*. The remaining exchanges are
target fixtures whose Phase 3 proof must produce these recorded values.

### Exchange 1 — receive the human message `[injected — synthesized read; stored message value]`

IN:

```clojure
(my.message/read "inbound-536871311-0")
```

OUT:

```clojure
{:seon.cluster.message/id "inbound-536871311-0", ...}
```

### Exchange 2 — retrieve the current view `[target — Phase 5 executes once]`

IN:

```clojure
(my.view/current)
```

OUT:

```clojure
{:seon.render.view/id "default/root", ...}
```

### Exchange 3 — query active runs `[target — Phase 3 executes once]`

IN:

```clojure
(seon.db/q '[:find ?agent-id ?run-id
             :where
             [?agent :seon.cluster.agent/id ?agent-id]
             [?agent :seon.cluster.agent/run ?run]
             [?run :seon.cluster.run/id ?run-id]])
```

OUT:

```clojure
#{["invoice" "6177a48d-3dd0-44ef-b351-299bd0b8fe0a"]}
```

### Exchange 4 — derive the system view `[target — Phase 5 executes once]`

IN (the comment is exact agent-authored source):

```clojure
;; Derive the newest changed value; do not store an activity rank.
(seon.render.walk/history
 {:seon.render.walk/root [:seon.cluster/name "default"]
  :seon.render.walk/profile :seon.render.profile/preview})
```

OUT:

```clojure
[{:seon.cluster.agent/id "invoice", :seon.render.walk/changed-at 536871319, ...}
 {:seon.cluster.agent/id "root", :seon.render.walk/changed-at 536871311, ...}]
```

### Exchange 5 — settle the run `[target — Phase 3 executes once]`

IN:

```clojure
(my.run/complete "invoice is active; its latest settled query found invoice inv-203 has an invalid tax code. The other agents are parked.")
```

OUT:

```clojure
{:my.run/disposition :completed, :my.run/result "invoice is active; …"}
```

### What the model literally sees

```text
my.agents.root=> (my.message/read "inbound-536871311-0")
{:seon.cluster.message/id "inbound-536871311-0",
 :seon.cluster.message/to [:seon.cluster.agent/id "root"],
 :seon.cluster.message/content "Which agents are active, and what changed most recently?"}
my.agents.root=> (my.view/current)
{:seon.render.view/id "default/root",
 :seon.render.view/root [:seon.cluster.agent/id "root"],
 :seon.render.view/subject [:seon.cluster/name "default"]}
my.agents.root=> (seon.db/q '[:find ?agent-id ?run-id
                              :where
                              [?agent :seon.cluster.agent/id ?agent-id]
                              [?agent :seon.cluster.agent/run ?run]
                              [?run :seon.cluster.run/id ?run-id]])
#{["invoice" "6177a48d-3dd0-44ef-b351-299bd0b8fe0a"]}
my.agents.root=> ;; Derive the newest changed value; do not store an activity rank.
                 (seon.render.walk/history
                  {:seon.render.walk/root [:seon.cluster/name "default"]
                   :seon.render.walk/profile :seon.render.profile/preview})
[{:seon.cluster.agent/id "invoice",
   :seon.render.walk/changed-at 536871319,
   :seon.render.preview/content
   {:seon.cluster.run.form/source "(db/q invoice-query)"
    :seon.render/value [["inv-203" :invalid-tax-code]]}}
  {:seon.cluster.agent/id "root",
   :seon.render.walk/changed-at 536871311,
   :seon.render.preview/content
   {:seon.cluster.message/content "Which agents are active, and what changed most recently?"}}]
my.agents.root=> (my.run/complete "invoice is active; its latest settled query found invoice inv-203 has an invalid tax code. The other agents are parked.")
{:my.run/disposition :completed,
 :my.run/result "invoice is active; its latest settled query found invoice inv-203 has an invalid tax code. The other agents are parked."}
```

Comments remain exact submitted source (`src/seon/cluster/reply.clj:155-268,
330-390`). Absence of `:seon.cluster.message/from` means an origin outside the
cluster (`src/seon/cluster/message.clj:267-269,437-440`). The query result is a
set because that is Datahike's actual relation shape (`src/seon/db.clj:779-823`).

## Exact history-entry grammar

There is no stored history-entry discriminator. Attribute presence identifies each
fact family. The renderer derives the following open maps and hands their
`:seon.repl/value` print node to the printer. The address remains data for
requery and HTML identity; it is not emitted as a decorative text header.

Provenance rule: every form below is verbatim stored
`:seon.cluster.run.form/source` except the inbound-message read, which is the
one synthesized form; every value is recorded `:seon.cluster.eval/result-edn`
(or the pulled message fact), decoded and printed—never recomputed.

| Family | Form (as displayed) | Actual value | Durable address |
|---|---|---|---|
| Inbound message | `(my.message/read "<message-id>")` | Pulled admitted message map | `[:seon.cluster.message/id <id>]` |
| Agent form | Exact `:seon.cluster.run.form/source` | Receipt's admitted `:seon.print` node | `[run-id ordinal]` |
| Settled success | Same agent form; never a second form | `:seon.cluster.eval/result-edn`, decoded as one print node | `[:seon.cluster.eval/id <id>]` |
| Settled error | Same agent form | Flat `:seon.error` value printed through the same node grammar | `[:seon.cluster.eval/id <id>]` |
| Elision | The exact read that produced the bounded value | `:seon.print/elided` with omitted count, total when known, path, next offset, profile, and requery identity/refusal | Result identity or explicit refusal |
| Injected require | `(require '[my.run :as run] ...)` | `nil` | Bootstrap run + ordinal |

These are attribute patterns on one entry, not separate entries: settled
success/error describe how an agent-form entry's value slot fills; the form is
never repeated.

The derived in-memory shape is concrete:

```clojure
{:seon.repl/form "(total [3 5 8])"
 :seon.repl/namespace 'my.agents.task-agent-17
 :seon.repl/value {:seon.print/value 16}
 :seon.cluster.run/id "bootstrap:task-agent-17"
 :seon.cluster.run.form/ordinal 9
 :seon.cluster.eval/id "[\"bootstrap:task-agent-17\" 9]"}
```

For a message, the entry is derived rather than committed as a fake eval:

```clojure
{:seon.repl/form "(my.message/read \"inbound-536871250-0\")"
 :seon.repl/namespace 'my.agents.task-agent-17
 :seon.repl/value <admitted message print node>
 :seon.cluster.message/id "inbound-536871250-0"}
```

`;; d0 · [:lookup]`, `;; unit=… branch=…`, `;; Volatile context metadata`,
and `;; (seon.render/walk …) => …` are replaced by nothing in the text target.
The corresponding facts remain on the agent-history entry, on the elision value, and as HTML
`data-*` attributes. Today those comment strings are constructed in
`src/seon/render/walk.clj:609-669`; deleting their assembly closes
[`render-walk-frames-values-as-comments.md`](../../../seon/issues/render-walk-frames-values-as-comments.md).

### Printer behavior by agent-history entry

No form evaluates. Only a declared producer runs with the recorded value
(`src/seon/render.clj:238-265`). Text emits prompt, form, stdout, then value;
HTML emits the same entry through `emit-hiccup`. Errors and elisions remain
ordinary values (`src/seon/render/transcript.clj:43-59,539-566`;
`src/seon/print.cljc:277-296,600-724`). HTML form spans derive server-side from
reader tokens, are CSS-themed, and survive SSE; regex/client parsing is banned
(`src/seon/sci/reader.cljc:28-116,296-405`).

## Concrete schema and API docs

Bare injected `doc` and `docs` query one database projection in the accepted
implementation home `seon.program`; qualified Vars are plumbing. Both cover
functions, schemas, tests, and namespaces. `doc` prints one deep result then
returns `nil`; `docs` returns `:seon.program/doc` values. Function output has
`tests-reaching`, test output `calls`, schema output its three relationship
directions plus generated example. Schema-key `doc` uses the map arity:

```clojure
(doc symbol-or-namespace)
(doc {:seon.program/identity identity})
symbol-or-namespace := [:or :qualified-symbol :seon.ns/name]
identity := [:or :qualified-symbol :qualified-keyword :seon.ns/name]
doc-map-input :=
[:map
 [:seon.program/identity
  [:or :qualified-symbol :qualified-keyword :seon.ns/name]]]
output := :nil
```

### Current schema wall versus proposed printed representation

The before/after contract is literal:

| Current schema-wall line | Proposed printed representation |
|---|---|
| `; schema :my.fs/not-found-error = [:map {:seon.error/class true, :seon.render/ai seon.error/render-ai, :seon.render/html seon.error/render-html, :error/message "must identify the absent filesystem path"} [:my.fs/not-found :my.fs/not-found] [:seon.error/message :seon.error/message]]` | `:my.fs/not-found-error — refusal: must identify the absent filesystem path.` |

The wall cost 43 lines/3,459 tokens; SCHEMA-1 omits unrequested definitions
([audit](../research/context-quality-audit-2026-08-10.md#L204)).

### Light API line

The exact summary line is:

```text
my.fs/read [path] — Read UTF-8 text from path.
```

Multiple arities repeat the bracketed vectors:

```text
my.message/send [to content] [to content about] — Address a message to another agent.
```

The underlying open map carries `:seon.program/name`, `:seon.fn/arglists`, and
`:seon.fn/doc`; several results may print as a table. Current `doc` already
derives these facts (`src/seon/sci/eval.clj:1016-1086`); no second index is added.

### Deep schema doc — actual target call and result

```clojure
(docs {:seon.program/identities [:my.run/result]
       :seon.program/detail :deep})
[{:seon.schema/key :my.run/result
  :seon.schema/definition [:string {:min 1}]
  :seon.schema/properties {:min 1}
  :seon.schema/example "The report is complete and the focused tests pass."
  :seon.schema/example-seed 1224898382
  :seon.schema/program-commit #uuid "6a7a4c6f-9745-55b1-ae67-510c8e622317"
  :seon.schema/accepted-by [{:seon.fn/sym my.run/complete, :seon.fn/arglists ([result])}]
  :seon.schema/returned-by []
  :seon.schema/contained-by [:my.run/completed]}]
```

Definition and consumer are current facts (`resources/seon/schemas/my.run.edn:1-8`;
`src/my/run.clj:32-47`). The example seed is identity+program commit; failure
occupies the slot as a flat error (`src/seon/schema.clj:482-493,1974-2028`).

### Bare `docs` — both exact target arities

```clojure
(docs identity & identities)
(docs request)

positional-input :=
[:catn
 [:identity [:or :qualified-symbol :seon.ns/name]]
 [:identities [:* [:or :qualified-symbol :qualified-keyword :seon.ns/name]]]]

map-input :=
[:map
 [:seon.program/identities [:vector [:or :qualified-symbol :qualified-keyword :seon.ns/name]]]
 [:seon.program/detail {:optional true} [:enum :summary :deep]]]

output :=
[:vector [:or :seon.program/doc :seon.error/value]]
```

The common positional arity preserves order:

```clojure
(docs 'my.run/complete :my.run/result 'my.run)
```

Options exist only in the namespaced map arity; positional keywords are schema
identities, never flags. One call demonstrates every shape:

```clojure
(docs {:seon.program/identities
       ['my.run/complete :my.run/result 'my.run
        'my.run-test/a-disposition-is-an-ordinary-value]
       :seon.program/detail :summary})
[{:seon.program/name my.run/complete
  :seon.fn/arglists ([result])
  :seon.fn/doc "Finish this run with a reply for its requester."
  :seon.fn/tests-reaching [my.run-test/a-disposition-is-an-ordinary-value]}
 {:seon.schema/key :my.run/result
  :seon.schema/definition [:string {:min 1}]
  :seon.schema/example "The report is complete and the focused tests pass."
  :seon.schema/accepted-by [my.run/complete]
  :seon.schema/returned-by []
  :seon.schema/contained-by [:my.run/completed]}
 {:seon.ns/name my.run
  :seon.ns/doc "Return values that tell the run loop to wait or complete."}
 {:seon.test/sym my.run-test/a-disposition-is-an-ordinary-value
  :seon.fn/calls
  [my.run/complete my.run/wait seon.schema/valid-candidate-value?]}]
```

Unknown/ambiguous identities are in-position flat errors. Database injection
stays at `src/seon/sci/eval.clj:1016-1122`; pure shapes live in
`src/seon/program.cljc:12-42`.

### What changes, in one list

**Deleted:** `src/seon/render/transcript.clj` whole; the AI prose/comment
assembler (`src/seon/render/walk.clj:568-671`); `compact-ai-text` and namespace
schema-wall closure (`src/seon/render/ns.clj:354-475`); per-family sizes
(`src/seon/render/transcript.clj:26-30,700-752`,
`src/seon/render/ns.clj:320-330`); outside-walk `fleet-call` and fleet table
(`src/seon/render/web.clj:336-375`, `src/seon/oversight.clj:261-301`);
unavailable substitute representations (`src/seon/render.clj:479-519`,
`src/seon/render/walk.clj:424-438`); deliberate-fault bootstrap lessons
(`resources/seon/bootstrap.edn:49-70`); latest capture as live debug authority.

**Schema changes:** new `:seon.render/producer-request`; delete
`:seon.render/unit` after all 49 consumers convert; new
`:seon.render.view/{id,root,subject}`; one preview profile using existing keys;
repoint the agent renderer to history; replace bootstrap content in its current
stored shape.

**New functions:** `seon.render.walk/history`; bare injected `doc` and `docs`
implemented in `seon.program`; `my.message/read`; `my.view/current` and
`my.view/show`.

**Unchanged:** form/result storage; public `seon.render/walk` string contract;
SSE package/delta/keyframe delivery; provider captures as forensics; guarded
producer invocation.

## Current-state and deletion inventory

| Current mechanism | Current evidence | Target disposition |
|---|---|---|
| Producer precedence and guarded SCI invocation | Explicit → contract candidates → schema → floor at `src/seon/render.clj:150-221`; calls execute through the guarded kernel at `:238-265` | Survives; one option below settles whether nested values also consult candidates. |
| Recursive print-node projection | `project-node*` traverses admitted child nodes at `src/seon/render.clj:274-353` | Survives, with the selected nested policy and cycle guard. |
| Render-call reuse | Static/read evidence reuse at `src/seon/render.clj:401-447` | Survives and becomes the unchanged-basis hard gate for both targets. |
| Neighbourhood traversal | Forward/reverse refs and bounded walk at `src/seon/render/walk.clj:56-206,280-496` | Survives as the one walk for agent roots and cluster roots. |
| AI walk prose assembler | Comment headers/body/metadata at `src/seon/render/walk.clj:568-671` | Deleted. Ordered agent-history entries go straight to the printer text sink. |
| Separate `seon.render.transcript` agent-history assembler | Independent queries, ordering, six-entry policy, AI/HTML assembly at `src/seon/render/transcript.clj:26-226,432-455,639-778` | Namespace deleted after its queries/order move into `seon.render.walk/history`. No second agent-history projection survives. |
| `compact-ai-text` schema dump | Full/compact namespace render paths and schema closure at `src/seon/render/ns.clj:354-475` | Deleted. Summary program docs are pulled on demand; namespace HTML may still show program members through the shared profile. |
| Per-family/per-rendered-value size policy | `recent-entry-count` is hard-coded 6 at `src/seon/render/transcript.clj:26-30`; that namespace invents its own token budget at `:700-752`; namespace budget logic sits at `src/seon/render/ns.clj:320-330` | Deleted. One render profile is selected at the consumer boundary and one `seon.print/fit` owner spends it. |
| Generic print grammar and two sinks | Sink protocol at `src/seon/print.cljc:10-14`; text/Hiccup/tee at `:98-220`; fitting at `:756-791` | Survives and is strengthened by the stress harness. |
| Bootstrap source forms | 13 current forms at `resources/seon/bootstrap.edn:1-73` | Survives as real creation-run forms; failing lessons and schema-wall probes are replaced by the 12-form candidate. |
| Prompt acquisition and budget | One retained walk, distance fallback, calibrated budget at `src/seon/cluster/prompt.clj:140-225` | Survives, but acquires the agent-history text projection; distance fallback becomes profile fitting, not “all or nothing” walk depth. |
| Exact provider capture | Capture writes prompt, basis, contributions, and token characters at `src/seon/context.clj:150-188`; schema marks prompt no-history at `resources/seon/schemas/seon.context.capture.edn:1-33` | Survives as forensic “what this provider call saw”; it is not the authority for the live debug pane. |
| Debug overlay | AI pane reads latest capture at `src/seon/render/web.clj:503-531`; current debug code also wraps the normal HTML projection at `:537-566` | Converted to an AI-context-only debug route. The normal route remains the main HTML view; each route receives only its declared targets. Historical captures remain drillable facts. |
| Debug feed packages | Debug has its own registration key at `src/seon/render/web.clj:700-737,1030-1034`; package/delta/keyframe logic is shared at `:596-643` | Survives. Each route package contains only IDs declared by that route, preventing no-target patches by construction. |
| Fleet oversight special value | Root page calls `oversight/unit` outside the walk at `src/seon/render/web.clj:336-375`; its bespoke table is `src/seon/oversight.clj:261-301` | Replaced by cluster-root walk + preview profile. Flow observations may remain ordinary values reached from the cluster root. |
| Agent renderer | Agent schema currently points both projections to history wrappers at `resources/seon/schemas/seon.cluster.agent.edn:1-14` | Updated to the one agent-history producer; no status-plus-history composition. |

### Render-producer argument ABI conversion

The target does **not** redefine `:seon.render/unit`. Current
`render-argument` merges a rendered map's arbitrary keys into the producer
argument (`src/seon/render.clj:76-108`); a live query found 49 contracts in 18
namespaces using it, including the message producer
(`src/seon/cluster/message.clj:429-463`). Silent redefinition would break them.

Phase 2 therefore introduces a new, globally identified request shape:

```clojure
[:seon.render/producer-request
 [:map
  [:seon.render/value :seon.render/value]
  [:seon.render/context
   [:map
    [:seon.render/target [:enum :seon.render/ai :seon.render/html]]
    [:seon.render/profile :qualified-keyword]
    [:seon.render/database-value :seon.db/database-value]
    [:seon.render/rendering-stack [:set :qualified-symbol]]]]]]
```

Phase 2 converts all 49 contracts/callers atomically, proves zero old consumers,
then deletes flattening. Domain data lives under `value`, custody under
`context`; the old key disappears rather than changing meaning.

### Storage verification — the facts already exist (live `default`, 2026-08-10)

This proves the page-one claim: both halves of every exchange are already
stored facts, so this is render-side unification plus deletions, with no new
history storage. The probe used the live default cluster's effect-door
configuration and this exact query:

```clojure
(seon.db/q
 {:query
  '[:find ?run-id ?ordinal ?form-id ?receipt-id ?source ?result-edn
    :where
    [?run :seon.cluster.run/id ?run-id]
    [?form :seon.cluster.run.form/run ?run]
    [?form :seon.cluster.run.form/ordinal ?ordinal]
    [?form :seon.cluster.run.form/id ?form-id]
    [?form :seon.cluster.run.form/source ?source]
    [?receipt :seon.cluster.eval/run ?run]
    [?receipt :seon.cluster.eval/ordinal ?ordinal]
    [?receipt :seon.cluster.eval/id ?receipt-id]
    [?receipt :seon.cluster.eval/result-edn ?result-edn]]
  :limit 5})
```

One live row was:

```clojure
["fe408373-9c06-4b41-87d5-9c5bebeee9d8"
 0
 "[:seon.cluster.run.form/id \"fe408373-9c06-4b41-87d5-9c5bebeee9d8\" 0]"
 "[\"fe408373-9c06-4b41-87d5-9c5bebeee9d8\" 0]"
 "(map (fn [sym] ...) [\"my.agents.root/token-pressure\" ...])"
 "#:seon.print{:items [...]}" ]
```

Run+ordinal is the total form→receipt join; no direct ref is missing
(`src/seon/cluster/run.clj:567-583,639-663,731-790,1172-1245`;
`resources/seon/schemas/seon.cluster.run.form.edn:1-24`;
`resources/seon/schemas/seon.sci.eval.edn:20-47`). `result-edn` is serialized
print-node data, not final text, so one fact emits text or Hiccup; larger values
use existing blob/size fields ([rulings #25–26](README.md#L2390)).

## Design surface: live AI-context debug route

### Mid-turn worked example

Everything below is OUTPUT: the AI pane is exact text for the next provider
call, and the Hiccup is the normal main view. Nothing is typed by the owner.

At basis 536871319, the message read, current-view retrieval, and query have
settled; the history form is submitted and has no value yet. The debug route's
package contains exactly
the AI-context element:

```clojure
#{"debug-ai-root"}
```

The owner-visible bytes inside the debug `<pre>` are exactly:

```text
my.agents.root=> (my.message/read "inbound-536871311-0")
{:seon.cluster.message/id "inbound-536871311-0",
 :seon.cluster.message/to [:seon.cluster.agent/id "root"],
 :seon.cluster.message/content "Which agents are active, and what changed most recently?"}
my.agents.root=> (my.view/current)
{:seon.render.view/id "default/root",
 :seon.render.view/root [:seon.cluster.agent/id "root"],
 :seon.render.view/subject [:seon.cluster/name "default"]}
my.agents.root=> (seon.db/q '[:find ?agent-id ?run-id
                              :where
                              [?agent :seon.cluster.agent/id ?agent-id]
                              [?agent :seon.cluster.agent/run ?run]
                              [?run :seon.cluster.run/id ?run-id]])
#{["invoice" "6177a48d-3dd0-44ef-b351-299bd0b8fe0a"]}
my.agents.root=> (seon.render.walk/history
                  {:seon.render.walk/root [:seon.cluster/name "default"]
                   :seon.render.walk/profile :seon.render.profile/preview})
```

There is no following value or prompt because that last form has not settled.
The normal route is the main HTML view; it is not a right debug pane. At the
same database value its HTML target contains the same four history-entry
identities, concretely:

```clojure
[:section {:id "agent-html-root" :class "seon-main-view"}
 [:article {:id "repl-message-inbound-536871311-0"}
  [:pre {:class "seon-repl-form"}
   [:span {:class "tok-prompt"} "my.agents.root=> "]
   [:span {:class "tok-delimiter"} "("] [:span {:class "tok-symbol"} "my.message/read"]
   " " [:span {:class "tok-string"} "\"inbound-536871311-0\""] [:span {:class "tok-delimiter"} ")"]]
  [:dl {:class "seon-print-map"}
   [:dt ":seon.cluster.message/id"] [:dd "inbound-536871311-0"]
   [:dt ":seon.cluster.message/to"] [:dd "[:seon.cluster.agent/id \"root\"]"]
   [:dt ":seon.cluster.message/content"] [:dd "Which agents are active, and what changed most recently?"]]]
 [:article {:id "repl-view-current-root"}
  [:pre {:class "seon-repl-form"} "my.agents.root=> (my.view/current)"]
  [:dl {:class "seon-print-map"}
   [:dt ":seon.render.view/id"] [:dd "default/root"]
   [:dt ":seon.render.view/root"] [:dd "[:seon.cluster.agent/id \"root\"]"]
   [:dt ":seon.render.view/subject"] [:dd "[:seon.cluster/name \"default\"]"]]]
 [:article {:id "repl-form-6177a48d-0"}
  [:pre {:class "seon-repl-form"} "my.agents.root=> (seon.db/q '[:find ?agent-id ?run-id :where [?agent :seon.cluster.agent/id ?agent-id] [?agent :seon.cluster.agent/run ?run] [?run :seon.cluster.run/id ?run-id]])"]
  [:table {:class "seon-print-table"}
   [:tbody [:tr [:td "invoice"] [:td "6177a48d-3dd0-44ef-b351-299bd0b8fe0a"]]]]]
 [:article {:id "repl-form-6177a48d-1" :data-settled "false"}
  [:pre {:class "seon-repl-form"} "my.agents.root=> (seon.render.walk/history {:seon.render.walk/root [:seon.cluster/name \"default\"] :seon.render.walk/profile :seon.render.profile/preview})"]]]
```

Form spans above are derived server-side from the parsed form's own tokens and
themed in CSS; SSE carries the Hiccup. Text and HTML preserve the same four
entry identities but fit independently (`src/seon/render/web.clj:503-566`).
On settlement, the existing multicast updates subscribers; debug registers
only `debug-ai-root`, normal HTML only main-view IDs. That makes the 200+
no-target warning class unrepresentable
([issue evidence](../../../seon/issues/debug-pages-receive-block-patches-for-elements-they-do-not-have.md#L56)).
Captures remain durable forensics, never the live pane authority
(`src/seon/context.clj:150-188`).

## Design surface: cluster-root system view

### Root and profile

The ruled `/` route includes root's message bar and asks the same walk for:

```clojure
(seon.render.walk/history
 {:seon.render.walk/root [:seon.cluster/name "default"]
  :seon.render.walk/profile :seon.render.profile/preview})
```

`:seon.cluster.agent/cluster` connects every agent to this root
(`resources/seon/schemas/seon.cluster.agent.edn:37-46`; `src/seon/render/walk.clj:63-137,182-206`).
For each agent, reduce admitted preview values by greatest derived `changed-at`,
then least stable path (`src/seon/render/walk.clj:219-225,366-370,535-550`). No
activity/latest/rank fact exists; reversing insertion order cannot change a tie.
The walk query yields ordinary `[agent-id stable-path changed-at value]` rows;
Hiccup is produced only after that deterministic per-agent reduce.

### Root's message bar and current view

`/` is the system view, not root's namespace page. Its fixed bar posts through
`POST /agent/{id}/message` (`src/seon/render/route.clj:5-27`). Root controls,
rather than appears inside, the activity-sorted main view. Its one open entity is:

```clojure
{:seon.render.view/id "default/root"
 :seon.render.view/root [:seon.cluster.agent/id "root"]
 :seon.render.view/subject [:seon.cluster/name "default"]}
```

`id` is identity; `root` and `subject` are refs. Cluster subject selects the
system view; agent subject selects that agent's HTML in the same main element.
Worked example B demonstrates `(my.view/current)`; `(my.view/show
[:seon.cluster.agent/id "invoice"])` replaces only `subject`, then the current
listener/render/SSE path morphs the element (`src/seon/cluster/wake.clj:163-228`;
`src/seon/render/web.clj:596-643,673-784`). Navigation never assigns work.

Preview profile proposal:

```clojure
{:seon.render.profile/id :seon.render.profile/preview, :seon.render.profile/token-budget 220,
 :seon.render.profile/max-depth 4, :seon.render.profile/max-children 12,
 :seon.render.profile/composition :multiline}
```

These existing keys yield 12 values plus an elision of 8 at 20 agents; CSS
cannot change spend, and only the whole profile is configurable
(`resources/seon/schemas/seon.render.profile.edn:1-18`).

### Four-agent worked example

Everything below is DISPLAY from one cluster-root walk; none is model input.
At one immutable database value the owner sees:

```text
default — 4 task agents
[Message root: Which agents are active, and what changed most recently?]

invoice                                      research
mid-turn · changed at t=536871319             active · changed at t=536871311
Latest settled result                        Latest changed canvas
[["inv-203" :invalid-tax-code]]              Import failure map: tax-code branch highlighted

qa                                           archive
parked · changed at t=536871280               parked · changed at t=536870901
Completed                                    Last result
Focused import regression passes.            {:seon.print/omitted 18,
                                               :seon.print/elision-unit :children,
                                               :seon.render.data/total 30,
                                               :seon.render.data/path [12],
                                               :seon.render.data/next-offset 12,
                                               :seon.render.profile/id :seon.render.profile/preview,
                                               :seon.print/requery-id
                                               [:seon.cluster.eval/id "[\"archive-run\" 4]"]}
```

HTML renders each agent's most-recently-changed settled value under PREVIEW-1;
in-flight tokens are not durable history. Existing multicast sends the
delta/keyframe—no new feed—and the outside-walk `fleet-call`/table are deleted
(`src/seon/render/web.clj:336-375,596-643,673-784,1003-1085`;
`src/seon/oversight.clj:261-301`).

## Live NESTED-2 decision probe

This decides whether nested values may select a declared producer and whether
checking is affordable at every node. “Acquired candidates” means producer
candidates derived once per acquired program generation, keyed by target and
accepted output shape. Full probe evidence remains in the critique/audit; the
decisive live `default` results are:

| Selection design | AI | HTML | Combined work |
|---|---:|---:|---|
| Current per-node full candidate scan | 306.991 ms | 303.866 ms | 687.871 ms first run; 2,000 candidate enumerations, 160,000 accepts validations, 48,000 return validations |
| Same database basis, current scan | — | — | 624.906 ms; zero retained selection hits |
| Unrelated data basis, current scan | — | — | 620.353 ms; zero retained selection hits |
| Acquired output-compatible candidates + per-node input fit | — | — | 4.720 ms first run; 2 candidate derivations, 1,998 acquired-candidate hits, 2,000 accepts validations, 160 return validations |
| Same program acquisition, indexed | — | — | 3.314 ms; zero derivations, 2,000 acquired-candidate hits, 2,000 accepts validations, zero repeated invocations |
| Unrelated data basis, indexed | — | — | 3.543 ms; same counts as same-program run |

Ambiguity was stable under reversed candidate order: one sorted flat error in
1 ms. Alternating A→B→A completed in 147.748 ms with order `[A B]`; neither
producer re-entered (`src/seon/render.clj:238-265,294-353`).

**Decision:** the probe refutes uncached NESTED-2 but supports indexed
NESTED-2. The output-compatible candidates are derived once into the acquired
program snapshot, keyed by program generation, target, and accepted output
shape. Per-node work validates only their input contracts; unrelated data does
not invalidate them, and publication creates a new acquired generation. The
gate is ≤20 ms selection work per target for this fixture.

## Option blocks and received owner rulings

Unsettled blocks keep exactly three options, simplest first. Received rulings
remain under their original block names and mark rejected alternatives
explicitly.

### NESTED — producer selection inside print values

**Stakes:** whether an authored producer takes effect for a shape inside
another value without making every render slow. Top-level selection already
works (`src/seon/render.clj:203-221`); this settles nesting.

**RULED (owner, 2026-08-11): NESTED-1.** Nested selection stays
explicit-or-declared-schema; contract-fit remains a top-level
convenience; the acquired-candidates nested mechanism is DROPPED (the
probe stands as evidence it was affordable — simplicity outranked it).
The taught habit replaces the machinery: DECLARE YOUR SHAPE as a schema
key carrying its render producer property and it renders through its
face at every depth via the existing door. The red test
`nested-values-render-their-declared-faces` is a defect in that
EXISTING door (a declared face not honored nested), not a missing third
mechanism. (Original options for the record:)

1. **NESTED-1 — Nested schema-only.** Low cost and no cycles; gives up authored nested producers.
2. **NESTED-2 — Acquired candidates + visit + stack.** Probe-affordable; not taken.
3. **NESTED-3 — Cache final selection by shape.** High invalidation risk; not taken.

### HUMAN — form shape for an inbound human message

**Stakes:** highest-frequency injected bytes. Inbound has no `from`; `to` is
already `[:seon.cluster.agent/id id]`. Only its printed ref-vs-id awaits owner
confirmation (`resources/seon/schemas/seon.cluster.message.edn:1-73`; `src/seon/cluster/message.clj:258-304`).

1. **HUMAN-1 — `(identity <map>)`.** Low cost; duplicates bytes and teaches no read.
2. **HUMAN-2 — `(my.message/read "id")` (Recommended).** Medium cost; honest, rerunnable, concise database read.
3. **HUMAN-3 — raw `seon.db/pull`.** Low code cost; exposes storage and repeats a long selector.

### PROSE — model-authored prose in a reply

**Stakes:** whether a prose-only reply is representable in history or remains
a loud no-forms result (`src/seon/cluster/reply.clj:155-268,330-390`).

1. **PROSE-1 — Submitted comments only (Recommended).** Low cost, exact source; prose-only is a loud no-forms value.
2. **PROSE-2 — Inject a string-returning form.** Medium cost; represents prose but fabricates source.
3. **PROSE-3 — Durable prose facts.** High cost; queryable, but adds a second reply/ordering mechanism.

### SCHEMA — replacement for raw schema walls

**Stakes:** replaces the measured 43-line, 3,459-token schema wall in every
root context.

1. **SCHEMA-1 — Demonstrated `docs` + on-demand depth (Owner-reframed recommendation).** Medium cost; real calls return functions/referenced schemas, definitions by follow-up.
2. **SCHEMA-2 — Compact schema-key lines.** Medium-low cost; repeats identities and still needs `doc`.
3. **SCHEMA-3 — Sentence per schema.** High generated-prose risk; retains a shorter wall.

### DOC — familiar prose versus returned data

**Stakes:** whether bare `doc` keeps Clojure's familiar print-plus-`nil`
meaning while the new bare `docs` returns inspectable data.

1. **DOC-1 — Familiar print, return nil (Recommended).** Low cost; preserves semantics and shares the schema/test-aware `docs` projection (`src/seon/sci/eval.clj:1111-1122`).
2. **DOC-2 — Print and return data.** Medium breakage; composable but changes today's `nil`.
3. **DOC-3 — Return data only.** Medium cost; loses familiar REPL output and duplicates `docs`.

### OPENING — forms pinned in a fresh agent history

**Stakes:** the fixed token cost prepaid by every fresh agent.

1. **OPENING-1 — Six forms.** Low cost; help/in-ns/require/two docs/task, but no durable authoring or graph query.
2. **OPENING-2 — Worked 12 forms (Recommended for experiment).** Medium prepaid cost; complete path, no deliberate faults; ablation may delete forms.
3. **OPENING-3 — Model-specific.** High cost and premature; gives up one generic prefix before evidence.

### MINIMUM — experiment and judgment

**Stakes:** the experiment that proves which prepaid opening forms earn their
place.

1. **MINIMUM-1 — Greedy ablation + removed pairs (Recommended).** Medium cost; attributable deltas and interaction check.
2. **MINIMUM-2 — Factorial subsets.** Very high provider spend/noise; captures all interactions.
3. **MINIMUM-3 — Model requests context.** High cost; circular first-turn negotiation for a possibly smaller prefix.

MINIMUM-1 runs five families (authoring, modeling, query/debug, delegation,
flat-error recovery) × ten fixed seeds, 50 attempts/condition, fixed model,
program commit, order, and fresh refork. Mechanical facts judge success,
settlement, contracts, retrieval, repairs, tokens, completion:prompt ratio
(c:p), and time; two blind reviewers resolve only nonmechanical outcomes. Pass:
≥45/50 success, 50/50 settlement, zero undeclared contracts, no family drop
over one attempt, and ≤10% token increase. Restore each removed pair once;
commit seeds/prompts/receipts/judges/disagreements. Thresholds await markup.

### DEBUG-LIVE — RULED live AI-context pane

**Stakes:** the debug pane equals the next provider prompt byte-for-byte instead
of showing a stale capture or nothing.

1. **DEBUG-1 — Re-render on relevant settlement (RULED).** Medium cost; evidence skips unrelated wakes; may differ from last forensic capture.
2. **DEBUG-2 — Capture per settlement (Rejected).** High writes; corrupts capture semantics (`src/seon/context.clj:150-188`).
3. **DEBUG-3 — Stream partial text (Rejected).** High cost; partials destroy settled-history/prompt equality.

### PREVIEW — content selected for each agent's system-view value

**Stakes:** which one live rendered value represents each agent on the system
view, and whether selection requires family-specific state.

1. **PREVIEW-1 — Most-recently-changed settled value (Recommended).** Medium cost; one `changed-at` + stable tie, any family; gives up persistent old canvas.
2. **PREVIEW-2 — Pinned canvas, else last changed.** Medium-high cost; adds pin facts and can hide activity.
3. **PREVIEW-3 — History tail only.** Low and uniform; ignores newer canvases/errors.

### SYSTEM-ROUTE — RULED root system view

**Stakes:** `/` becomes one live fleet surface root can see and steer, without
a second dashboard or delivery path.

1. **SYSTEM-1 — `/` system view + root bar (RULED).** Medium cost; one cluster-global view fact and current SSE navigation.
2. **SYSTEM-2 — `/system`, keep `/` (Rejected).** Medium cost; splits one surface across routes (`src/seon/render/route.clj:17-76`).
3. **SYSTEM-3 — Root as agent value (Rejected).** Low cost; loses fixed bar/current-view relationship.

## Measured baselines

These are before-values, not targets.

| Surface | Baseline on 2026-08-10 | Evidence |
|---|---:|---|
| Root stage-1 provider prompt | 12,161 tokens | Drive table, `model-authoring-drive-2026-08-10.md:100-108` |
| Root audited exact context | 15,917 estimated tokens, 18 rendered values | `context-quality-audit-2026-08-10.md:30-42` |
| Audited toolkit namespaces combined | 9,552 estimated tokens | `context-quality-audit-2026-08-10.md:36-40` |
| Audited `my.fs` / `my.web` rendered values | 2,843 / 2,249 estimated tokens | `context-quality-audit-2026-08-10.md:36-39` |
| Latest live-capture `my.background` | 801 estimated tokens | Effect-door configuration measurement in this PRD lane, cluster `default`, latest root capture |
| Latest live-capture `my.edit` | 1,010 estimated tokens | Same probe |
| Latest live-capture `my.fs` | 990 estimated tokens | Same probe; current source reads the profile at `src/seon/render/ns.clj:320-325` |
| Latest live-capture `my.message` | 744 estimated tokens | Same probe |
| Latest live-capture `my.run` | 511 estimated tokens | Same probe |
| Latest live-capture `my.shell` | 885 estimated tokens | Same probe |
| Latest live-capture `my.web` | 997 estimated tokens | Same probe |
| Latest live-capture `my.agents.root` | 459 estimated tokens | Same probe |
| Warm namespace HTML | 17 ms | Observer route table, `model-authoring-observer-2026-08-10.md:331-343` |
| Changed-basis agent-page walk | 3.168 s, 178 render calls, 3,859 pulls, 21,560 datoms | Critique live probe, `repl-transcript-context-prd-critique-2026-08-10.md:287-302,385-407` |
| Unchanged-basis agent-page walk | 16.5 ms, zero render calls, zero pulls | Same critique probe |
| Nested full scan, 1,000 nodes × 2 targets × 80 candidates | 687.871 ms first; 624.906 ms same basis | Live NESTED-2 decision probe in this PRD |
| Nested acquired candidates, same fixture | 4.720 ms first; 3.314 ms same program; 3.543 ms unrelated data basis | Live NESTED-2 decision probe in this PRD |
| Alternating producer cycle | 147.748 ms cold, invocation order A then B once each | Live NESTED-2 decision probe in this PRD |
| Warm `/data` | 123–131 ms (report as 130 ms) | Observer, `model-authoring-observer-2026-08-10.md:337-354` |
| Drive completion:prompt ratio | 0.12–0.42 for the three directed turns | Driver, `model-authoring-drive-2026-08-10.md:100-105` |
| Debug no-target warnings | 200+ on one load | Issue, `debug-pages-receive-block-patches-for-elements-they-do-not-have.md:24-44` |

The revised fresh worked agent history above is **1,230 estimated tokens** on the
shipped uncalibrated estimator, including prompts, forms, and values. This
number is a candidate starting point, not the minimum-context answer.

## Implementation phases and lane-ready boundaries

No production phase begins until the owner confirms the remaining NESTED and
HUMAN wording plus the unresolved PROSE, DOC, MINIMUM, and PREVIEW blocks. “One
class regression” below means one recurring test for the structural failure
class, not one test per example.

These phases supersede the ruling document's coarse skeleton: skeleton 1 maps
to Phases 1–2, skeleton 2 to Phase 3, skeleton 3 to Phases 4–6, and skeleton 4
to bad-output classes surviving Phase 7.

| Phase | Owned files | Changes | Falsifier | One class regression per closed hole |
|---|---|---|---|---|
| 0. Freeze | This PRD; `docs/seon/architecture/{context,ui}.md`; one research report | Freeze example bytes, profiles, seeds, and gates. | Two reviewers reconstruct every value or find an unlabeled injection/target. | Preview spend exists only as one profile value. |
| 1. Bound acquisition | `src/seon/render/{walk,render}.clj`; walk tests | Query bootstrap + profile-bounded recent identities; retain evidence first; elide with continuation. | Candidate query plus cold changed-basis run <1 s versus 3.168 s/3,859 pulls. | 10,000 old forms do not enlarge pulls; unrelated tx executes zero history work. |
| 2. Producer ABI + floor | `src/seon/{render,render/value}.clj`; `src/seon/print.cljc`; `resources/seon/schemas/seon.render.edn`; 18 producer owners/tests | Add request shape; atomically convert 49 consumers, then delete flattening; add acquired candidates, sorted ambiguity, stack guard, total floor, stress harness. | Zero old consumers; message reads `:seon.render/value`; 1,000 nodes ≤20 ms/target. | Custody cannot collide with domain data; ambiguity cannot fall through; A→B→A cannot re-enter; failures stay values; elisions are requeryable/refuse. |
| 3. History + docs | `src/seon/render/{walk,ns,agent}.clj`; delete `render/transcript.clj`; `src/seon/{program.cljc,bootstrap.clj}`; `src/seon/sci/eval.clj`; affected schemas/tests | Add `history`, bare injected `doc`/`docs`, `my.message/read`, bootstrap; delete prose/wall/headers/six-entry/family budgets; server token spans; prompt uses text target. | Fixture equals both examples byte-for-byte; HTML tokens reconstruct source. | Stored comments only; one form/receipt; unsettled/superseded rules; one docs query owner; docs errors in position; examples commit-stable; no regex/client parser. |
| 4. Live debug | `src/seon/render/web.clj`; `resources/public/css/input.css`; web tests | Debug emits current AI text; normal route highlighted HTML; target-owned IDs; retained evidence gates wakes. | Settle with both routes: disjoint targets and debug bytes equal next provider context at one database value. | Zero no-target warnings; same entry IDs; unrelated tx emits nothing; syntax spans survive SSE. |
| 5. System view | `src/seon/render/{walk,web,route}.clj`; `src/seon/oversight.clj`; `src/my/view.clj`; renderers/schemas/tests | `/` + root bar/current-view; cluster walk; deterministic changed selection; delete fleet append; reuse page feed. | Scrambled four-agent winner stays deterministic; `show` changes one ref/morphs `/`; 20 agents <1 s. | No stored rank/family selector/second SSE; 12 values + elision 8; root/main cannot diverge; navigation cannot assign work. |
| 6. Minimum | Existing evaluation harness; one research report; no production files | Run MINIMUM-1 at fixed model/settings/basis; record prompts, receipts, tokens, cache, time, calls, outcomes, ablations. | Savings fail if success falls or tokens/repair turns rise. | Final bootstrap/profile is smallest passing condition; marked example remains record. |
| 7. Integrate | Focused gates, complete checkpoint, live browser/agent drive | Integrate 1–6. | Any mismatch in bytes, printer stress, nested selection, bounded history, prompt/debug equality, syntax, package targets, ordering, or economics. | Deleted mechanisms have zero callers/declarations; unchanged basis runs zero producers. |

## Owner markup checklist

The implementation brief is ready only after the owner marks:

- the exact bytes of Worked examples A and B;
- the exact debug AI-context and main-view examples;
- the four-agent system-view example, root message bar, current-view values,
  and candidate preview profile;
- the NESTED visit wording and HUMAN recipient printed value;
- the remaining verdicts in PROSE, DOC, MINIMUM, and PREVIEW;
- the experiment success thresholds; and
- the exact demonstrated bare `docs` outputs; its database-binding and pure
  `seon.program` projection owners are fixed above.
