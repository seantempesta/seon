---
type: prd
status: draft
tags: [prd, agent, context, render, teaching, behavior]
---

# REPL-first context — the BEHAVIOR specification (for the owner's markup)

*Draft 2026-09-03. This document says WHAT an agent experiences and does,
from the agent's seat, and nothing about how the system computes it. The
owner's rule for this phase (2026-09-03): define every behavior first;
only when all behaviors are settled do we discuss efficient
implementation. The implementation companion is
[repl-first-context-design-2026-09-02.md](repl-first-context-design-2026-09-02.md)
(to be revised against THIS document once it settles). Rulings already
sealed are cited by number (ledger 47–56); everything else is a
proposal for markup. Every transcript below uses forms the machinery
emits or accepts today unless marked [P] (proposed).*

**How to read:** each behavior has a one-paragraph statement, a
transcript as the agent sees it, what it teaches the agent, and the open
questions for the owner (marked ❓). A behavior with no ❓ is settled.

---

## B0. The frame

An agent IS a namespace with a REPL. It is dropped into that REPL with
its context already on screen: a REPL session whose entries were typed
on its behalf. Nothing in the context is authored prose except one short
intro (46). Every entry is a form the agent could type again and the
value that form returns, rendered by the best render function for that
value (56a). What the agent learns, it learns by reading ordinary
Clojure: `doc`, `dir`, pull, Datalog, and the render functions' own
docstrings. The agent is encouraged to write its own render functions,
which become its HTML panels too (56). No walls of text.

---

## B1. Arrival — the first screen of a brand-new agent

**Statement.** A fresh agent's context contains, in this order: (1) the
intro instruction; (2) *who am I* — its namespace; (3) *what points at
me* — one entry per ref edge into its entity, collection-first; (4) the
teaching entries the above demand, each placed BEFORE the entry that
first uses the name; (5) the trigger (the message or task that woke it)
LAST, nearest the reply. The screen is small by derivation: a new agent
has one message and no history, so its context is a few entries.

**Transcript (root, a fresh cluster; `[P]` marks proposed spellings):**

```clojure
;; (1) the intro — the ONE authored instruction entity (46); seven sentences at most.

;; (2) who am I
my.agents.root=> (doc *ns*)                                            ; [P] polymorphic doc
my.agents.root
  requires: my.message my.run seon.bootstrap seon.db
  refers:   help dir doc  (from seon.bootstrap)
  owner agent: "root"   open run: "bootstrap:root"
  publics: (none yet)

;; (3) what points at me — collection-first, one form per edge
my.agents.root=> (seon.db/q '[:find [(pull ?m [*]) ...]
                              :where [?m :seon.cluster.message/to [:seon.cluster.agent/id "root"]]])
[{:seon.cluster.message/id "bootstrap-task:root"
  :seon.cluster.message/at #inst "2026-09-02T19:54:58.381-00:00"
  :seon.cluster.message/content "Define a durable contracted function …"
  :seon.cluster.message/to [:seon.cluster.agent/id "root"]}]         ; rendered by the message
                                                                        ; collection's render fn (B3)

;; (4) teaching demanded by the above appears BEFORE first use (B6):
;;     (doc seon.db/q) (doc seon.db/pull) came before (3); (dir my.message) (dir my.run) before (5).

;; (5) the trigger, last
my.agents.root=> (my.message/read "bootstrap-task:root")
{…the one message, whole…}
```

**Teaches:** that everything on screen is a query it can re-run; that
its neighbourhood is reached by refs; the two query spellings it will
use forever (pull by lookup ref, `q` with a reverse edge).

❓ B1.1 Is `(doc *ns*)` the right "who am I" entry, or the plain pull
`(seon.db/pull '[…] [:seon.ns/name 'my.agents.root])` rendered by a
namespace render function? (Recommendation: `(doc *ns*)` — it is the
same thing the agent will type for any other namespace.)
❓ B1.2 The trigger last: keep the run's trigger as the final entry
(today's tail-recency proof: 2/2 forms in the ablation) — settled unless
you object.
❓ B1.3 How much of the intro survives? Proposed: what a REPL is here,
that replies are forms evaluated in the namespace, that a contracted
`defn` is permanent, how to finish a run — and nothing that a `doc` can
say instead.

---

## B2. Reading an entry — form, then value

**Statement.** Every entry is exactly `ns=> (form)` followed by the
rendered value [REAL today]. The form is real: pasting it back returns
the value shown (modulo the basis, B5). The value is rendered by the most
specific render function (B3); when none exists, by the print floor
(B4). There is no third kind of line.

**Teaches:** the grammar of its own reply — forms — by example.

❓ B2.1 Do we show the basis (`t`) per entry? Proposed: no per-entry
noise; ONE final REPL-state line carries the current basis and time
[REAL today], and B5's diff entries name the basis they diff from.

---

## B3. Render functions — ordinary functions, most specific wins (56a)

**Statement.** A render function is an ordinary contracted function
whose input schema is the data's schema and whose output schema is
`:seon.render/ai` (text) or `:seon.render/html` (hiccup). Declaring one
makes it eligible. When a value is printed, the most specific eligible
function renders it: (a) a render function the DATA itself names
(inline) beats (b) a function in the AGENT'S OWN namespace that accepts
the value, which beats (c) the GENERAL render function the data's schema
names in its metadata, which beats (d) the print floor. Two functions at
the same rung that both fit is a loud tie the agent sees (43).

**Transcript — an agent improves how its inbox looks:**

```clojure
my.agents.root=> (defn inbox-view                                    ; [P] contract shape
  "One line per message, newest last: when, from whom, first sentence."
  {:malli/schema [:=> [:cat [:sequential :seon.cluster.message/message]]
                  :seon.render/ai]}
  [messages]
  (clojure.string/join "\n"
    (for [m (sort-by :seon.cluster.message/at messages)]
      (str (:seon.cluster.message/at m) "  "
           (or (:seon.cluster.message/from m) "outside") ": "
           (first (clojure.string/split (:seon.cluster.message/content m) #"\. "))))))
#'my.agents.root/inbox-view

;; from the NEXT turn on, every inbox entry — generated or typed — prints through it:
my.agents.root=> (seon.db/q '[:find [(pull ?m [*]) ...] :where [?m :seon.cluster.message/to [:seon.cluster.agent/id "root"]]])
2026-09-02T19:54:58Z  outside: Define a durable contracted function named largest…
```

Calling a render function directly on data is always allowed and is how
an agent checks what its function produces (56c):

```clojure
my.agents.root=> (inbox-view (seon.db/q '[:find [(pull ?m [*]) ...] :where …]))
"2026-09-02T19:54:58Z  outside: Define a durable …"
```

**Teaches:** that presentation is code it owns; that a contract is what
makes a function part of the system; that the same function (with an
`/html` twin) is its UI panel (B10).

❓ B3.1 Specificity between (b) and (c) when the agent's function fits a
SUPERTYPE (e.g. accepts any entity) — does a specific schema face beat a
generic agent function? Proposed: within a rung, greatest required-key
coverage wins (35); across rungs, the rung wins.
❓ B3.2 Does an agent's render function apply to OTHER agents' views of
the same data? Proposed: no — rung (b) is the viewing agent's namespace;
(c) is shared.
❓ B3.3 Inline naming (rung a): the value carries `:seon.render/ai
'some/fn` [REAL as the explicit-producer rung]. Keep as is?

---

## B4. The print floor — every value, bounded, honest (56c)

**Statement.** Any value returned to the REPL or to the UI that no render
function claims is printed by the floor: readable EDN, namespaced keys
intact, strings quoted (round-trippable), collections bounded by the
agent's render profile with an elision value naming the omitted count
and how to requery — never a bare truncation, never `[clipped]`
[REAL: seon.print + elision values; the profile's collection width
today shows 2 of 4 `requires`, see ❓].

**Transcript:**

```clojure
my.agents.root=> (seon.db/pull '[:seon.ns/name {:seon.ns/requires [:seon.ns/name]}] [:seon.ns/name 'my.agents.root])
#:seon.ns{:name my.agents.root,
          :requires [#:seon.ns{:name my.message} #:seon.ns{:name my.run}
                     … 2 more of 4; requery by [:seon.blob/digest "63d4…"] at path [1] offset 2]}
```

❓ B4.1 The default collection width the agent sees. Today's door output
elided 4 requires to 2 — too small to read a namespace's requires. What
is the right default: full up to the eval result cap (8,192), with the
PROFILE only bounding total tokens? (Recommendation: bound by tokens at
the whole-entry level, never by a per-collection count of 2.)
❓ B4.2 The elision line's shape: is `… 2 more of 4; requery by …` the
face, or should it print the requery FORM the agent can paste?
(Recommendation: print the form.)

---

## B5. What is new — since a basis, as a diff (56b)

**Statement.** At turn N the agent sees, for each discovery query whose
answer changed, ONE entry spelled against the basis it saw last, whose
value is the diff: additions and deletions, both, rendered by the
family's render function. The initial value (B1) is shown once; later
entries are diffs. Unchanged queries produce no entry. The agent can
type the same spelling itself to ask "what changed since t".

**Transcript [P — the spelling is the open question]:**

```clojure
;; turn 1 showed the inbox at basis 536870930. Turn 3:
my.agents.root=> (seon.db/diff 536870930 '[:find [(pull ?m [*]) ...]
                                          :where [?m :seon.cluster.message/to [:seon.cluster.agent/id "root"]]])
+ {:seon.cluster.message/id "m-2" :seon.cluster.message/at #inst "…" :seon.cluster.message/from [:seon.cluster.agent/id "planner"] :seon.cluster.message/content "…"}
- {:seon.cluster.message/id "bootstrap-task:root" …}          ; only if it was retracted
```

Effects fire once (54c): an entry whose form reaches an external sink
(a send, a shell call, a web fetch) is never re-run for a diff; its
stored result replays verbatim.

❓ B5.1 The spelling. `seon.db/diff` exists today for a function Var
(`(seon.db/diff basis #'f & args)`) and would extend to a query/pull
form as the pure read; the owner asked for "the since syntax". Options:
(i) `(seon.db/diff <basis> <query-or-pull-or-var> …)` — one function,
additions and deletions, the basis explicit (recommended); (ii)
`(seon.db/q <query> (seon.db/since <basis>))` — Datomic's own spelling,
but it shows assertions only (retractions invisible) so it cannot honor
"always both"; (iii) both: `since` for what the agent types casually,
`diff` for the generated entries.
❓ B5.2 A changed entity (same identity, different attribute values):
show as `~ id {attr old → new}` or as `-` then `+`? (Recommendation:
`~` with the changed attributes only — `seon.db/diff` already computes
`:changed-attributes`.)
❓ B5.3 Do diff entries accumulate forever in the transcript, or does the
NEXT regeneration fold older diffs into a fresh initial value at a newer
basis? Ties to B8 (compaction). Proposed: they accumulate until
compaction; compaction re-shows the initial value at the compaction basis.

---

## B6. Teaching — `doc` and `dir`, polymorphic, only when demanded

**Statement.** The agent is taught by the same two functions a Clojure
programmer uses, tailored to this system (56 preamble): `doc` takes
anything — a function, a namespace, a test, a schema key, a value, or a
list of those — and shows the relevant parts as data; `dir` lists a
namespace's public names. A teaching entry appears in the context only
when a later entry DEMANDS it (its form or value mentions a name the
agent has not yet seen), and it appears BEFORE that entry (52b). A name
the agent has already used correctly in its own history demands nothing;
a name it used and got an error on demands the doc AND a real test as a
demonstration (52a). `(help)` is the demand closure at empty history.

**Transcript — `doc` on each kind [P; today only function symbols work]:**

```clojure
my.agents.root=> (doc seon.db/pull)
seon.db/pull  ([options] [database-or-selector options-or-eid] [database selector entity-id])
  Pull one entity over an explicit or current database value.
  arity 3: database :seon.db/database-value, selector :seon.db/pull-selector, entity-id :seon.db/entity-id
           → the pulled entity | :seon.error/value

my.agents.root=> (doc my.message)
my.message — The inter-agent message protocol. …
  decline  (decline message reason)            → :my.message/declination | error
  inbox    ()  ([opts])                        → :my.message/inbox | error
  read     (read id)                           → :seon.cluster.message/message | error
  send     (send agent-id content) …           → :seon.cluster.message/delivery-request | error

my.agents.root=> (doc :seon.cluster.message/message)
:seon.cluster.message/message  [:map [:seon.cluster.message/id …] [:seon.cluster.message/to :seon.db/ref] …]
  renders: seon.cluster.message/render-ai  (general); my.agents.root/inbox-view (yours, collections)
  read by:  my.message/read  my.message/inbox     written by: my.message/send
  example:  {:seon.cluster.message/id "bootstrap-task:root" …}

my.agents.root=> (doc [my.message/inbox my.message/send])
…each in turn…
```

**Teaches:** to fish. Everything it needs is one `doc` away, and the
context shows it how by using `doc` itself.

❓ B6.1 The doc output above is DENSE DATA, not prose. Approve the
density? (Every line is a fact the graph stores; the "example row" is a
real pull.)
❓ B6.2 For a schema key: show the writers (`send`) or only the readers?
Ruling 54b says explanations speak the READ vocabulary; the doc of a
schema is reference material, not an explanation — proposed: show both,
labelled.
❓ B6.3 A test as demonstration after an error: show the test's source
form, or its name + docstring + `(doc test)`? (Recommendation: the
source of ONE reaching test, bounded.)
❓ B6.4 `(dir *ns*)`, `(doc *ns*)`: `*ns*` is the agent's own namespace
symbol — settled by Clojure.

---

## B7. The agent's own history — its evals, newest last

**Statement.** Everything the agent evaluated in earlier turns appears
as it happened: the form it typed and the value it got, rendered through
the same chain, in order, newest last, the reply's forms and values
interleaved with the system's discovery and diff entries by basis
(52). Errors appear as the flat error value the agent saw (B9). Its
`defn`s with contracts persist as its namespace's publics and show up
in `(doc *ns*)`; its scratch defs are restored (the agent's defs, ruled).

❓ B7.1 Ordering between the agent's evals and the system's diff entries
at the same turn: system entries first (what changed while you were
away), then your forms? (Recommendation: yes — mail before work.)
❓ B7.2 Does the agent see its own prose (comment lines) replayed?
Proposed: yes, as `;;` comments, exactly as it wrote them [REAL today].

---

## B8. Compaction — when the context is too big (ruled: compaction over evals)

**Statement.** When the context exceeds the model's budget, the agent's
context evals are retracted/superseded and the context regenerates from
the current facts: the same B1 arrival shape at the current basis, then
only what still matters — its namespace's current publics, the current
neighbourhood, the open run and its trigger, unresolved errors — with a
single line stating that compaction happened at basis t. Effect results
survive (they replay); defs survive (they are facts).

❓ B8.1 Does the agent see WHAT was compacted (a count of superseded
entries and the requery form for each family), or only that it
happened? (Recommendation: one line: `;; compacted at t 536870999: 41
earlier entries superseded; (seon.db/history) reaches them`.)
❓ B8.2 Who triggers compaction — the budget check only, or may the
agent ask for it? (Proposed: both; the agent's form is `(my.run/compact)`
[P] — name open.)

---

## B9. Errors — flat values, where they happened

**Statement.** An agent's mistake is a flat `:seon.error` value printed
where the form was evaluated, naming the layer, member, expected shape,
and offending value (2.4); nothing throws into the loop. An error's doc
demand (B6) is the function that failed plus one reaching test. Core
faults do not appear in the agent's context except as facts pointing at
the agent, which arrive like any other data (B5).

❓ B9.1 Should an unresolved error from a PREVIOUS turn be re-shown near
the tail (a reminder), or only where it happened? (Recommendation: only
where it happened; the open run's state is the reminder.)

---

## B10. HTML — the same render functions are the UI

**Statement.** A render function with an `/html` twin (input the same
schema, output `:seon.render/html`) is automatically a panel on the
agent's page; the agent's transcript page is the `/html` projection of
the same entries; nothing is authored twice (mandate 2026-08-14; ui.md).

❓ B10.1 Does an agent's `/ai` function without an `/html` twin get a
generated `/html` (the text in a `<pre>`), or nothing? (Recommendation:
the text, so every custom view is visible in the UI immediately.)

---

## B11. Digging — how the agent goes beyond what was shown

**Statement.** The context shows the neighbourhood at a small ref
distance; the agent digs by typing what it was shown: `(doc …)`, `(dir
…)`, a pull with a deeper or recursive selector (Datahike's own grammar:
`{:attr [...]}`, `{:attr N}`, `{:attr ...}`, `:ns/_attr` reverse refs
[REAL, verified live 2026-09-03]), a `q`, `(seon.db/history)`, or a
requery form from an elision value.

❓ B11.1 Should the context's generated forms deliberately model these
idioms (a nested selector in B1's pull, one reverse ref) so the agent
learns them by example? (Recommendation: yes — B1 (3) already uses a
reverse edge; B1 (2) should use a nested selector.)

---

## B12. What the agent never sees

No hand-authored narration; no rendered text stored as authority; no
`renderer unavailable`; no mid-string chops; no writer's identity in the
explanation of data it reads (54b); no line that is not a form + value
or a `;;` comment, except the intro.

---

## Open questions, collected

B1.1 B1.3 · B2.1 · B3.1 B3.2 B3.3 · B4.1 B4.2 · B5.1 B5.2 B5.3 · B6.1
B6.2 B6.3 · B7.1 B7.2 · B8.1 B8.2 · B9.1 · B10.1 · B11.1. Each carries a
recommendation; a settled behavior loses its ❓ in this file in the same
turn it is ruled.
