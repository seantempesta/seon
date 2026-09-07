---
type: research
status: active
tags: [context, render, message, error, namespace]
---

# Agent units landing — identity, messages, current run, history, faults, namespace

Lane `agent-units`, 2026-09-07. Migration steps 4 and 5 of
[the plan](../plan/entity-debug-curation-prd-2026-09-06.md), plus the added
identity unit.

## Authority read

Read end to end: `AGENTS.md`, the plan PRD, the top sections of
[unsettled.md](../plan/unsettled.md), and the render selection, coherence, and
transcript owners named below.

## What landed

Six units on the agent entity now declare paired producers. Every one was
verified on the live page
`http://127.0.0.1:7766/ns/my.agents.juniper/debug?subject=33770`
(cluster `juniper-context` under `tmp/juniper-context-live`, reseeded at
06:38Z; Juniper moved from entity 34620 to 33770).

| Unit | Declared on | AI | HTML |
|---|---|---|---|
| identity | `:seon.cluster.agent/id` | `seon.cluster.agent/render-id-ai` | `seon.cluster.agent/render-id-html` |
| namespace | `:seon.ns/ns` (unchanged owner) | `seon.render.ns/render-ai` | `seon.render.ns/render-html` |
| messages | `:seon.cluster.message/to` | `seon.cluster.message/render-inbox-ai` | `seon.cluster.message/render-inbox-html` |
| current run | `:seon.cluster.agent/run` | `seon.cluster.run/render-current-ai` | `seon.cluster.run/render-current-html` |
| history | `:seon.cluster.run/agent` | `seon.render.transcript/render-history-ai` | `seon.render.transcript/render-history-html` |
| faults | `:seon.error/agent` | none, deliberately | `seon.error/render-faults-html` |

Alias, refer, and import bindings also gained paired producers in
`seon.render.ns`; refer and import declared none before.

## Commits

- `312dc046f` identity unit
- `ceaaf3ae5` inbox unit (builds on the stopped lane's uncommitted work)
- `7ff102072` current-run and faults units
- `0f80d3891` namespace empty state and bindings in Clojure's words
- `e05b68127` history unit
- `879967692` fault card states the fault, not every stored attribute
- `066fc962c` transcript value printing without an SCI context

## Focused proofs

| Namespace | Tally | Failing |
|---|---|---|
| `seon.cluster.message-test` | 19 tests, 126 assertions | 0 |
| `seon.cluster.run-test` | 22 tests, 231 assertions | 1, pre-existing (`settlement-mints-rows-for-unindexed-call-targets`) |
| `seon.render.ns-test` | 8 tests, 151 assertions | 2, pre-existing (disabled rendering limits) |
| `seon.render.transcript-test` | 18 tests, 247 assertions | 8, pre-existing (disabled rendering limits; unsettled.md records them) |

Every new regression is green. No pre-existing red was touched; the two
rendering-limit families are the ones unsettled.md says not to satisfy by
restoring limits.

## Exact recorded output per unit

### identity — `:seon.cluster.agent/id`

AI (executed at the namespace prompt on the live page):

```clojure
;; Who am I? Identity is not remembered — it is three attributes stored
;; on my own entity, and `whoami` reads them from the current database.
(seon.cluster.agent/whoami)
```

whose printed result on the page is:

```text
Agent     juniper
Namespace my.agents.juniper
Cluster   juniper-context
```

HTML:

```clojure
[:article {:class "seon-family-entry seon-agent-identity-entry"}
 [:header [:p {:class "seon-kicker"} "Identity"] [:h3 [:code "juniper"]]]
 [:dl [:div [:dt "Namespace"] [:dd [:code "my.agents.juniper"]]]
      [:div [:dt "Cluster"] [:dd [:code "juniper-context"]]]]]
```

### namespace — `:seon.cluster.agent/namespace`

AI:

```clojure
(ns my.agents.juniper (:require my.message my.run seon.bootstrap seon.db))

;; No definitions are indexed in this namespace yet; it belongs to agent juniper.
;; Every `defn` evaluated here with a :malli/schema becomes one.
```

HTML: the namespace heading, a `[:ul]` of one require per entry, then
"No definitions are indexed in this namespace yet; it belongs to agent
juniper. Every contracted `defn` evaluated here becomes one.", then the
namespace source under disclosure.

An alias binding:

```clojure
;; Here `str` is an alias for clojure.string, so `str/name` reads a Var
;; in that namespace — the `:as` half of [clojure.string :as str].
(dir (quote clojure.string))
```

```clojure
[:article {:class "seon-family-entry seon-namespace-alias-entry"}
 [:p {:class "seon-kicker"} "Namespace alias"]
 [:p [:code "str"] " → " [:code "clojure.string"]]
 [:details {:class "seon-namespace-binding-data"}
  [:summary "libspec"] [:pre [:code "[clojure.string :as str]"]]]]
```

A refer binding:

```clojure
;; Here the bare symbol `q` is seon.db/q —
;; the `:refer` half of [seon.db :refer [q]].
(doc seon.db/q)
```

```clojure
[:article {:class "seon-family-entry seon-namespace-refer-entry"}
 [:p {:class "seon-kicker"} "Namespace refer"]
 [:p [:code "q"] " ← " [:code "seon.db/q"]]
 [:details {:class "seon-namespace-binding-data"}
  [:summary "libspec"] [:pre [:code "[seon.db :refer [q]]"]]]]
```

### messages — `:seon.cluster.message/_to`

AI:

```clojure
;; What have I been sent? Nothing stores an inbox: `my.message/inbox`
;; joins :seon.cluster.message/to against me and returns the messages
;; oldest first, so the newest one is the last printed. The empty
;; request map is the whole call — the database and I are supplied.
(my.message/inbox {})
```

HTML, per message, through the one message renderer:

```clojure
[:section {:class "seon-family-entry seon-message-inbox"}
 [:h2 "Messages (3)"]
 [:article {:class "seon-family-entry seon-message-entry"}
  [:header {:class "seon-message-meta"}
   [:span {:class "seon-message-direction"}
    [:span {:class "seon-message-from"} "Agent root"]
    [:span {:class "seon-message-arrow" :aria-hidden "true"} "→"]
    [:span {:class "seon-message-to"} "Agent juniper"]]
   [:time {:class "seon-message-at" :datetime "2026-09-06T19:35:00Z"}
    "2026-09-06T19:35:00Z"]]
  [:p {:class "seon-message-content"} "Please make your current plan …"]]
 …]
```

with an `about`/`caused-by` links paragraph appended only when present. A
message's own recipient reference (the forward walk) renders as
`[:section … [:p {:class "seon-kicker"} "Addressed to"] [:p [:a …]]]`.

### current run — `:seon.cluster.agent/run`

AI, when a run is held:

```clojure
;; Am I inside a run? An agent carries :seon.cluster.agent/run
;; exactly while one is open, so its absence is the whole answer.
;; This reads the one it holds now and says what state it is in.
(seon.cluster.run/render-ai (seon.db/pull (quote [:db/id :seon.cluster.run/id :seon.cluster.run/opened-at :seon.cluster.run/closed-at :seon.cluster.run/interrupted-at :seon.cluster.run/error :seon.cluster.run/plan-digest :seon.cluster.run/process]) [:seon.cluster.run/id "run-open"]))
```

HTML:

```clojure
[:article {:class "seon-family-entry seon-run-current"}
 [:p {:class "seon-kicker"} "Current run"]
 [:p "Run run-open, opened #inst \"…\". It is running now, held by 1-2."]
 [:p {:class "seon-run-current-link"} [:a {:href "/data?entity=…"} "run-open"]]]
```

Absent: AI renders nothing at all (`nil`), HTML renders
"No run is open; this agent is parked between episodes." On the live page
Juniper holds no run, so the page's own "No value is stored or connected for
this attribute" section is what appears — the producer is never called.

### history — `:seon.cluster.run/_agent`

AI:

```clojure
;; What have I already done? These are my own submitted forms and the
;; values they returned, newest run first, printed exactly as the run
;; loop printed them at the time.
(seon.render.transcript/format-history-ai (seon.render.transcript/agent-history {}))
```

whose printed result on the live page begins:

```text
Run 1bb7a710-483a-4a0b-9408-79673ef1fbb0, opened #inst "2026-09-07T06:39:15.575-00:00", closed #inst "2026-09-07T06:39:15.602-00:00".
It did not run: The environment variable OPENROUTER_API_KEY is not set.

Run bootstrap:juniper, opened #inst "2026-09-07T06:38:54.052-00:00", closed #inst "2026-09-07T06:39:06.675-00:00".
my.agents.juniper=> ; A new run just opened. Why am I awake — do I have messages?
(help)
{:seon.cluster.agent/id "juniper", :seon.cluster.agent/namespace-ref [:seon.ns/name my.agents.juniper], …}
my.agents.juniper=> (dir my.message)
decline
inbox
read
send
[my.message/decline my.message/inbox my.message/read my.message/send]
```

HTML: `[:h2 "History (N runs)"]` then per run
`[:article {:class "seon-run-history-entry"} [:p {:class "seon-kicker"}
"Historical run — its results are stored, not fresh"] [:h3 [:code run-id]]
[:p {:class "seon-run-history-window"} "Run …, opened …, closed …."]
[:pre {:class "seon-run-history-transcript"} [:code …bytes…]]]`, then, when
runs were left out, `[:p {:class "seon-run-history-elision"} …]` carrying an
ordinary elision value. Measured over the pre-reseed database, 609 runs:

```text
… 577 more children of 609; requery by [:seon.cluster.agent/id "juniper"] at path [:seon.render.transcript/runs] offset 32 with :seon.render.profile/agent
```

### faults — `:seon.error/_agent`

No `:seon.render/ai` is declared, per the plan. HTML:

```clojure
[:section {:class "seon-family-entry seon-error-faults"}
 [:h2 "Faults (6)"]
 [:article {:class "seon-error-fault"}
  [:p {:class "seon-kicker"} ":seon.ai/no-credential"]
  [:article {:class "seon-family-entry seon-error-entry"}
   [:h3 {:class "seon-error-message"}
    "The environment variable OPENROUTER_API_KEY is not set."]
   [:p {:class "seon-error-link"} [:a {:href "/data?entity=…"} "Inspect durable evidence"]]]
  [:time {:class "seon-error-at" :datetime "2026-09-07T01:03:51.704Z"} "…"]
  [:p {:class "seon-error-run"} [:a {:href "/data?entity=…"} "in run 4290d93b-…"]]]
 …]
```

## Findings and requests

### A declared reverse unit has no schema key of its own — protected-path request

`src/seon/render/web.clj` sets `:seon.render.walk/attribute` to the FORWARD
attribute for a reverse unit, so both the render-contract coherence check
(`src/seon/schema.clj` `render-contract-observation`) and Malli instrumentation
read a schema describing ONE ref while the seam hands a COLLECTION of pulled
entities. Worse, `schema-accepts-schema?` cannot see through an `:and` on the
declaring side — every stored ref attribute is `[:and {props} :seon.db/ref]` —
so no `:or` input can be declared coherent either. The three coherent inputs
are the attribute's own key, `:seon.schema/value`, and `:any`.

The reverse units therefore declare `:seon.schema/value` as their producer
input, with the honest collection shape enforced one call inward where
instrumentation can state it (`:seon.cluster.message/inbox-unit` at
`seon.cluster.message/inbox-html`). **Request:** give a declared reverse
relationship its own schema key — for example admitting
`:seon.cluster.message/_to` as a registry key whose form is the collection —
so coherence and instrumentation check what is actually handed over. That is a
change to `src/seon/render.clj`, `src/seon/render/web.clj`, and
`src/seon/schema.clj`, all outside this lane.

### `(my.message/inbox)` argless is ambiguous — a real refusal, recorded

The request-map arity the previous lane added makes zero supplied arguments fit
two derived call shapes, so the live cluster answers:

```text
:seon.call-preparation/ambiguous-call — Cannot call my.message/inbox with 0
arguments: more than one derived call shape fits that count … candidates [[0] [0 1]]
```

The refusal is correct behaviour and the regression asserts it. The AI
projection therefore emits `(my.message/inbox {})`, matching `(my.plan/plan {})`.
Note this is a general consequence: **adding a named request-map arity beside a
positional arity removes the argless call**, and every producer emitting an
argless form for such a function must be revisited.

### The forked test ctx supplies no prepared arguments

In `test-support/fork-cluster-ctx`, `(my.message/inbox)` fails with
`Wrong number of args (0)` rather than the typed ambiguity refusal, and a
request map supplied without a database returns `[]` rather than the branch's
messages. unsettled.md already records this fixture gap ("its fixture omitted
the initialization facts declaring supplied arguments … re-verification is
pending"), so the regression asserts only what the fixture can honestly prove:
the indexed arity facts, both explicit arities against real data, and that the
argless call is refused rather than guessed.

### The reverse pull of a leaked run population exceeds the query-work budget

Before the reseed, Juniper had 609 runs and the page's own reverse-value pull
in `src/seon/render/web.clj` refused with
`:datahike.budget/name :query-work, observed 4001, allowed 4000`, so the
history unit could not render at all. The reseed dropped this to 9 runs and it
renders. Two separate things follow: migration step 3 (previews in memory)
removes the leak, and the page's unbounded `:selector reverse-units` pull is
itself a defect for any genuinely large reverse population. Protected path.

### `render-run-html` is O(the agent's whole history) per call

Rendering three runs through `seon.render.transcript/render-run-html` did not
finish inside 30 seconds, because `projection` calls `history-count`, which
re-reads and re-parses every form source of every run through the SCI reader.
The history unit deliberately does not use that path; both its projections read
one `agent-history` derivation, measured at ~260ms over 609 runs. The cost in
`render-run-html`/`projection` remains, on the page's per-run surfaces.

### `source:` runs are not distinguishable by any fact

Unchanged from the earlier note: run ids beginning with `source:` are a
spelling, not a fact, and nothing in the render path reads the prefix. If
preview evaluations stay stored after the preview lane lands, the writer that
knows the purpose should declare it as an attribute.

### Paths touched outside the lane's literal list

- `src/seon/cluster/agent.clj` — render functions only; explicitly granted with
  the identity unit.
- `resources/seon/schemas/seon.cluster.agent.edn` — only the `:id` and `:run`
  attribute property maps; the `:seon.render/units` vector and the
  `:seon.cluster.agent/agent` map are untouched.
- `src/seon/error.clj` — one added render function plus its two private
  helpers, because `resources/seon/schemas/seon.error.edn` is owned but
  `seon.error` is the only coherent owner of a fault renderer.
- `test/seon/cluster/run_test.clj` carries the faults regression, because
  `seon.error-test` opens no database by design and the fault card needs a run
  to link to.
- `resources/seon/schemas/seon.render.transcript.edn` is new — the EDN schema
  of an owned namespace.

### Unfinished

- The faults unit's AI column falls to the value floor on the page, because
  nothing is declared. That is what the plan asked for, but the page showing a
  floor render rather than "no AI projection is declared" is a page-lane
  presentation question.
- `:seon.render/form` remains declared on `:seon.cluster.message/to`
  (`seon.render.transcript/inbox-form`), left in place for the orchestrator's
  single deletion commit.
- The current-run unit's producers are proven by test and by direct live call,
  not by the page, because Juniper holds no run.
