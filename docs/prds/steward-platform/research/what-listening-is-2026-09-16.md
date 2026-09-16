---
type: research
status: complete
tags: [wake, listen, datahike, agent, runtime, turn]
---

# What listening is today — the pattern, the stream, and the matcher

Read end to end for this note: `AGENTS.md` §1 "Waking and the loop"
(`AGENTS.md:227-235`) and the wake/turn vocabulary rows
(`AGENTS.md:608`, `:612`, `:613`, `:618`); the turn PRD §3 "Waking —
listened attributes, Datahike `listen`, answered by `:t`"
(`docs/prds/context-generation/plan/agent-record-and-turn-loop-prd-2026-09-07.md:296-390`)
and §§13–15 (`:879-1060`); `resources/seon/schemas/seon.listen.edn`,
`seon.wake.edn`, `seon.cluster.wake.edn`, `seon.runtime.edn`;
`src/seon/cluster/wake.clj` whole; `src/seon/turn.clj` §the derivations
(`:2706-3115`); `src/seon/cluster/agent.clj:440-480`, `:700-740`,
`:866-960`; `src/seon/issue.clj:920-956`; and, in the fork,
`reference-code/datahike/src/datahike/core.cljc:200-218`,
`datahike/writer.cljc:392-417`, `datahike/db.cljc:130`,
`datahike/datom.cljc:114-124`,
`datahike/db/transaction.cljc:903-923` and `:1249-1255`,
`datahike/api/specification.cljc:1138-1165`.

Two read-only JVM evaluations were run against cluster `default`
(`(seon.operator/connection "default")`, no transaction); their output is
§5.

---

## 1. What a listen pattern is today, as data

There is no `src/seon/listen.clj`. The family is three attributes in one
schema resource and one component edge:

| attribute | shape | meaning as declared |
|---|---|---|
| `:seon.listen/attribute` | `:qualified-keyword`, required | "The indexed attribute this pattern listens to." (`resources/seon/schemas/seon.listen.edn:1`) |
| `:seon.listen/entity` | `[:and … :seon.db/ref]`, optional | "Optional entity constraint; absent matches any entity." (`:2`) |
| `:seon.listen/value` | `[:or :seon.schema/value [:= []]]`, optional | "Optional logical datom value constraint, encoded by the schema bridge's ordinary EDN union codec." (`:3-4`) |
| `:seon.listen/pattern` | `[:map {:seon.db/attributes true} …]` | the three above, entity and value `{:optional true}` (`:5-8`) |

The patterns hang off the agent's runtime component:
`:seon.runtime/listens` is a `[:set {:seon.db/component true} :seon.db/ref]`
described as "Authored index patterns: attribute with optional entity and
value constraints" (`resources/seon/schemas/seon.runtime.edn:8-9`), and
`:seon.runtime/agent` is the unique identity ref back to the agent (`:1-2`).

**The shape is deliberately the read-evidence index pattern.** The landing
PRD row states it in those words — "A listen is an index pattern (attribute,
optional entity, optional value) — the same shape read evidence stores"
(`docs/prds/context-generation/plan/agent-data-chart-prd-2026-09-09.md:177-178`)
— and `seon.db`'s own read evidence carries exactly the parallel triple
`:seon.db/pattern-entity` / `:seon.db/pattern-attribute` /
`:seon.db/pattern-value`, all three optional
(`resources/seon/schemas/seon.db.edn:36-44`), matched by
`seon.db/index-pattern-change` (`src/seon/db.clj:906-917`). So the
"absent position" convention is borrowed, not invented here (§2).

### Compilation to a matcher

`seon.cluster.wake/wake-matchers` (`src/seon/cluster/wake.clj:403-442`)
builds one map keyed by attribute. It starts from the schema-declared
listened set — `(into {} (map #(vector % {::schema? true})) (wake-attributes database))`
(`:435`) — then folds in every authored pattern found by one Datalog query
over `:seon.agent/runtime` → `:seon.runtime/agent` → `:seon.runtime/listens`
→ `:seon.listen/attribute` (`:436-442`). For each pattern it closes over:

- `entity`: `(get-in pattern [:seon.listen/entity :db/id])` (`:421`) — the
  pulled ref's entity id, `nil` when the key is absent;
- `supplied`: `(find pattern :seon.listen/value)` (`:422`) — presence, not
  truthiness, so a stored `false`/`nil`-shaped value is still a constraint;
- `value`: the supplied value decoded from `:seon.listen/value`'s own union
  codec, then either `entid`-resolved (when the *target* attribute is a ref)
  or re-encoded into the target attribute's storage codec (`:424-429`,
  through `seon.schema.datahike/decode-attribute-value-in`
  `src/seon/schema/datahike.clj:579` and `encode-attribute-value-in` `:560`,
  and `datahike.db.utils/entid` `reference-code/datahike/src/datahike/db/utils.cljc:109`).

The compiled matcher is one function of one datom:

```clojure
(fn [datom]
  (when (and (or (nil? entity) (= entity (:e datom)))
             (or (nil? supplied) (= value (:v datom))))
    agent))
```
(`src/seon/cluster/wake.clj:430-434`)

So the pattern grammar is exactly **(a required, e optional, v optional)**,
conjunctive, equality only.

### What stream it is applied to

**The Datahike transaction report — the commit log as it is committed, not
the stored log.** `route!` registers through `d/listen`
(`src/seon/cluster/wake.clj:512-515`), and the handler's whole input is the
report: it iterates `(:tx-data report)` (`:542`) and rederives from
`(:db-after report)` (`:540-541`). `d/listen` is `datahike.core/listen!`
(`reference-code/datahike/src/datahike/api/specification.cljc:1153`), which
only `swap!`s the callback into the connection's `:listeners` atom
(`reference-code/datahike/src/datahike/core.cljc:200-211`); the connection
carries that atom in its metadata
(`reference-code/datahike/src/datahike/connector.cljc:102`).

The invocation site is `datahike.writer/transact!`: after the writer's
serial loop returns, `(when (map? tx-report) … (doseq [[_ callback] (some-> (:listeners (meta connection)) (deref))] (callback tx-report)))`
and only then `(deliver p tx-report)`
(`reference-code/datahike/src/datahike/writer.cljc:401-416`). Two
consequences `wake.clj`'s own header states and the fork confirms: a
callback that throws or parks damages the committing caller
(`src/seon/cluster/wake.clj:15-27`), and a refused transaction never
dispatches because `(map? tx-report)` is false
(`:62-64`, writer `:401`). *Drift note:* the header cites
`writer.cljc:384-386` for that call site; in the vendored fork it is now
`:414-416`. The claim holds, the coordinates have moved.

Nothing reads a durable log. The fork's API specification has no
`tx-range`; its only reactive entries are `listen` and `unlisten`
(`reference-code/datahike/src/datahike/api/specification.cljc:1136-1165`).
Historical wakes are re-derived from `d/datoms`/`d/history` instead
(`src/seon/cluster/wake.clj:288-317`), and boot injects one synthetic wake
because a listener only sees future commits (`:66-69`).

---

## 2. What `:seon.listen/entity` means precisely

It is the **`:e` position of an index pattern**: a compiled matcher fires
only when the committed datom's entity id equals the pattern's
(`src/seon/cluster/wake.clj:432`). Absence means the `:e` position is
unconstrained — the pattern matches *every* entity that carries the
attribute. That is the schema's own wording: "Optional entity constraint;
absent matches any entity" (`resources/seon/schemas/seon.listen.edn:2`).

**Design or accident?** `git log -S'(or (nil? entity)' -- src/seon/cluster/wake.clj`
returns exactly one commit: `6778a4614` "Route runtime listen patterns
through the cluster wake matcher", Sean Tempesta, 2026-09-09 21:13:37 -0600.
It landed `src/seon/cluster/wake.clj` (+95/-…), `test/seon/cluster/wake_test.clj`,
the evidence note
[`evidence-listens-landing-2026-09-09.md`](../../context-generation/research/evidence-listens-landing-2026-09-09.md),
a live EDN capture, a probe script, and — in the same commit — the issue
note admitting the gap
[`runtime-listens-do-not-yet-participate-in-turn-eligibility.md`](../../../seon/issues/runtime-listens-do-not-yet-participate-in-turn-eligibility.md).

So the branch is **deliberate**, and its optionality is inherited from the
read-evidence index-pattern shape it was explicitly modelled on
(`…/agent-data-chart-prd-2026-09-09.md:177-178`; `src/seon/db.clj:906-917`
has the same three-optional-position `cond`). It is not a stray nil-guard.

What *is* unsound is the carrier, and it is the exact class this branch has
been ruling on. `:seon.listen/entity` is a **ref**
(`resources/seon/schemas/seon.listen.edn:2`), and Datahike's
`retractEntity` sweeps every incoming ref datom
(`AGENTS.md:426-434`, the §1f G2 ruling: "A fact that must outlive its
target stores a VALUE, not a ref"). Delete the constrained entity and the
`:seon.listen/entity` datom vanishes with it; `(get-in pattern [:seon.listen/entity :db/id])`
then answers `nil`, and by line `:432` the pattern **silently widens from
one entity to every entity**. A narrowing constraint whose disappearance
broadens the match is the project's absence-as-health class in listener
clothing (`AGENTS.md:340-346`).

---

## 3. What Datahike actually delivers, and the natural matching grammar

The callback receives one `TxReport`:
`(defrecord TxReport [db-before db-after tx-data tempids tx-meta])`
(`reference-code/datahike/src/datahike/db.cljc:130`).

`:tx-data` is a vector of `Datom`s, and a `Datom` answers five declared
keys — `:e :a :v :tx :added`
(`reference-code/datahike/src/datahike/datom.cljc:114-124`, `val-at-datom`).
Seon's matchers read `:e`/`:a`/`:v` through those keys deliberately, because
positional `nth` refuses the ordinary `:seon.db/datom` map shape
(`src/seon/cluster/wake.clj:405-415`). Only *effective* datoms reach the
public report and the listeners — an idempotent re-assertion emits nothing
(`reference-code/datahike/src/datahike/db/transaction.cljc:613-617`), which
is the "unchanged value emits no wake" trap `wake.clj:54-60` names.

**Transaction metadata arrives in the same stream as ordinary datoms.**
`flush-tx-meta` turns each `:tx-meta` entry into `[:db/add tid attribute value tid]`
(`reference-code/datahike/src/datahike/db/transaction.cljc:903-923`), and
those entities are prepended to the transaction when history is kept
(`:1249-1255`). So Seon's provenance — `:seon.db/user` and
`:seon.db/process`, both indexed refs
(`resources/seon/schemas/seon.db.edn:257`, `:214`; `AGENTS.md:533-537`;
writers at `src/seon/cluster.clj:2566`, `:2621`, `:2664`, `:2717`,
`src/seon/config.clj:527`) — appears in `:tx-data` as ordinary datoms whose
`:e` **is** the transaction id. §5's live read shows this.

Therefore the grammar Datahike's own stream naturally supports, stated
without proposing a design:

| position | available | Seon's pattern today |
|---|---|---|
| `:e` entity id | yes, per datom | yes, equality, optional (`wake.clj:432`) |
| `:a` attribute | yes, per datom | yes, **required** — the matcher map is keyed by it (`wake.clj:430`, `:554`) |
| `:v` value | yes, per datom | yes, equality on the STORED form, optional (`wake.clj:433`) |
| `:added` assert/retract | yes, per datom | **no** — never consulted by a matcher (`wake.clj:430-434`, `:554-560`) |
| `:tx` transaction id | yes, per datom | no |
| transaction metadata (`:seon.db/user`, `:seon.db/process`, `:db/txInstant`) | yes, as datoms on the tx entity in the same `:tx-data` | no, and not reachable: those datoms' `:a` is not a listened attribute, and a pattern cannot say "the entity is this commit's tx" |
| `:db-before` / `:db-after` | yes, whole database values | used only to rebuild matchers (`wake.clj:540-541`) and by the search route (`:521-529`) |
| combinations | free, since the report is ordinary data | conjunction of (e, a, v) only; no disjunction, no predicate, no cross-datom condition |

Two asymmetries inside Seon's own handler are worth naming because they are
not stated anywhere: **arming** checks `(:added datom)`
(`src/seon/cluster/wake.clj:552`), and **the read-side wake derivation**
filters `(filter :added …)` (`:316`) — but **routing checks neither**
(`:554-560`). A retraction on a listened attribute therefore delivers a
wake that no derivation can see.

---

## 4. The answering rule, and where the wake's identity lives

Nowhere. That is the point.

- A wake is any datom on an attribute whose schema row carries
  `:seon.wake/listen true` (`resources/seon/schemas/seon.wake.edn:9-12`),
  derived by one query in `wake/wake-attributes`
  (`src/seon/cluster/wake.clj:92-119`).
- Its `:t` is whatever Datahike stamped
  (`resources/seon/schemas/seon.wake.edn:25-29`: "nothing stores either").
- `turn/latest-answering-turn-t` (`src/seon/turn.clj:3011-3036`) returns the
  opening `:t` of the agent's latest turn that carries a non-errored
  `:seon.ai.attempt` *or* a `:seon.turn/reply-size` datom later than the
  turn identity's own transaction — "Merely opening, or failing before any
  reply, cannot qualify" (`:3017-3018`).
- `turn/unanswered-wakes` (`src/seon/turn.clj:3038-3090`) seeks
  `wake/agent-wake-datoms` and keeps `(> tx since)` (`:3082-3083`).
- `wake/agent-wake-datoms` (`src/seon/cluster/wake.clj:288-317`) is the one
  reader: one `d/datoms :avet <attribute> <agent-eid>` seek per listened
  attribute, reversed for newest-first, `(filter :added)`.

Hence "the listened datom's identity is stored" **nowhere**: the wake is
found again by index seek, and answeredness is a `:t` comparison
(`AGENTS.md:227-230`; PRD §3 `:335-347`; the 2026-09-07 live table at PRD
`:374-390` shows the `:t` rule and the retired trigger-ref rule agreeing
exactly, including a wake committed in the same transaction as the turn that
answered it).

Two derived sets split the policy: `wake/turn-opening-attributes`
(`:seon.wake/opens-turn? true`, `src/seon/cluster/wake.clj:160-178`) for
what may cause a model call, and the whole listened set for what a context
shows and what may anchor the turn bound — `turn/wake-attribute-set`
(`src/seon/turn.clj:2722-2735`) and `turn/outside-wake-t` (`:2737-2773`).
`:seon.wake/inside` (`resources/seon/schemas/seon.wake.edn:17-20`) keeps the
population's own traffic from refilling that bound
(`wake/inside-attributes` `src/seon/cluster/wake.clj:180-199`;
`wake/inside-wake?` `:319-330`).

`wake/declarations-refusal` (`:223-260`) fails the whole registration closed
on an empty listened set, an empty inside set, or a listened attribute
absent from `:avet` — because Datahike's `:avet` holds only
`:db/index true` attributes
(`reference-code/datahike/src/datahike/db.cljc:932-936`, cited at `:209-212`),
so an unindexed listened attribute would make every wake read as absent.

The wake itself carries no payload: `route!` `offer!`s the bare keyword
`::wake` into the agent's `(sliding-buffer 1)` mailbox
(`src/seon/cluster/wake.clj:389`, `:558`), created at
`src/seon/cluster/agent.clj:712`, wired into the agent's Flow graph at
`:440-473` — "a wake says only \"look\", the turn pass derives ALL of this
agent's work from one fresh database value, so coalescing is free"
(`src/seon/cluster/agent.clj:447-451`; vocabulary row `AGENTS.md:618`).
An unroutable recipient falls through to the armer channel
(`src/seon/cluster/wake.clj:559-560`), and asserting an
`:seon.wake/arms true` attribute — only `:seon.agent/id`
(`resources/seon/schemas/seon.agent.edn:71-77`) — offers the armer a
payload-free wake too (`:552-553`).

---

## 5. A worked example from `default` (the two read-only queries)

**Query 1** — the derived sets and every authored listen pattern in the
cluster:

```clojure
{:wake-attributes #{:seon.effect/to :seon.issue/agent
                    :seon.message/inbox :seon.schedule.fire/agent}
 :opening         #{:seon.effect/to :seon.issue/agent :seon.message/inbox}
 :inside          #{:seon.message/about :seon.effect/to :seon.message/from}
 :arming          #{:seon.agent/id}
 :declarations-refusal nil
 :listen-rows     #{:seon.issue/budget}
 :listens [["2393cac275ae"
            {:db/id 80779
             :seon.listen/attribute :seon.issue/budget
             :seon.listen/entity {:db/id 59710}}]]}
```

The four schema-declared listened attributes are exactly the four
`:seon.wake/listen true` rows on disk — `seon.message.edn:141-152`,
`seon.effect.edn:162-172`, `seon.issue.edn:30-33`,
`seon.schedule.fire.edn:7-14` — so the derivation and the resources agree.

**There is exactly one entity-constrained listen pattern in existence**, and
it is the one `seon.issue/start!` writes on resume:

```clojure
{:seon.runtime/agent [:seon.agent/id agent-id]
 :seon.runtime/listens [{:seon.listen/attribute :seon.issue/budget
                         :seon.listen/entity [:seon.issue/id issue-id]}]}
```
(`src/seon/issue.clj:950-953`, guarded by the duplicate-check query at
`:936-942` — the only place in `src/` that writes `:seon.listen/*`.)

**What it matches.** Any datom on `:seon.issue/budget` whose `:e` is 59710 —
assertion *or* retraction, because the matcher ignores `:added` (§3).

**Query 2** — that entity's history, and a message-in wake as datoms:

```
:seon.issue/budget datoms on 59710   ; [e a v tx added]
  [59710 :seon.issue/budget 2 536871407 true]
  [59710 :seon.issue/budget 2 536871723 false]
  [59710 :seon.issue/budget 4 536871723 true]
  [59710 :seon.issue/budget 4 536871733 false]
  [59710 :seon.issue/budget 5 536871733 true]
:seon.issue/agent datom on 59710
  [59710 :seon.issue/agent 66192 536871407 true]
issue 59710 = {:seon.issue/id "65f46c0efa7f" :seon.issue/budget 5}
```

A message-in wake, newest of two live `:seon.message/inbox` datoms:

```
wake datom  [87615 :seon.message/inbox 66192 536871779 true]
message     {:db/id 87615
             :seon.message/id "00b6c228e1d6"
             :seon.message/to    {:db/id 66192}
             :seon.message/inbox {:db/id 66192}      ; the wake edge
             :seon.message/from  {:db/id 47221}      ; makes it an INSIDE wake
             :seon.message/content "Built my.agents.root/largest — …"}
tx entity   {:db/id 536871779
             :db/txInstant #inst "2026-09-16T19:48:24Z"
             :seon.db/receipt {:db/id 87614}
             :seon.db/user    {:db/id 47221}}
```

So a message-in wake is *one* datom — `(e=the message, a=:seon.message/inbox,
v=the recipient agent, tx, added=true)` — and the schema branch of the
matcher takes the recipient straight from `(:v datom)`
(`src/seon/cluster/wake.clj:555`). The transaction's provenance rides
alongside as datoms on entity `536871779`, in the same `:tx-data` the
handler already walks.

---

## 6. Honest ledger: hack versus sound

### Sound

1. **Wakes as commit-stream datoms, carrying nothing.** The report is the
   only stream; the woken pass derives from facts
   (`src/seon/cluster/wake.clj:6-10`, `:558`).
2. **The routed set is a query, not a list** — `:seon.wake/listen`,
   `/opens-turn?`, `/inside`, `/arms` all derive from schema rows
   (`wake.clj:92-199`), and §5's live read matches the resources exactly.
3. **Answeredness by `:t`, with nothing stored** (`turn.clj:3011-3090`),
   verified against the retired trigger-ref rule on live data
   (PRD `:374-390`).
4. **Fail-closed declarations**, including the `:avet`-index requirement
   whose absence would read as health (`wake.clj:201-260`).
5. **The two handler prohibitions**, measured against the fork's own call
   site (`wake.clj:12-33`; `writer.cljc:401-416`).
6. **The offer/fence classification** — closed-and-still-routed is the only
   benign `false` (`wake.clj:332-401`).

### Hack, or unsound as it stands

1. **An authored pattern can name an attribute that no derivation can ever
   answer.** `:seon.issue/budget` is not `:seon.wake/listen true`
   (`resources/seon/schemas/seon.issue.edn:36`), so it is absent from
   `wake/wake-attributes`, hence from `wake/agent-wake-datoms`, hence from
   `unanswered-wakes`, `outside-wake-t` and `turns-left`. Routing delivers;
   eligibility never sees it. Filed as
   [`runtime-listens-do-not-yet-participate-in-turn-eligibility.md`](../../../seon/issues/runtime-listens-do-not-yet-participate-in-turn-eligibility.md)
   with the 2026-09-09 measurement (mailbox proc 6→7, latest turn unmoved).
   **Still open, and §5 shows it is the only authored pattern that exists.**
2. **`:seon.listen/entity` is a ref, so deleting the constrained entity
   widens the pattern to every entity** instead of removing it (§2). This is
   §1f G2 (`AGENTS.md:426-434`) unapplied to this family, and it is
   absence-read-as-health: the pattern keeps working, louder.
3. **The one live pattern violates the invariant the mechanism declares.**
   `:seon.wake/listen`'s own text is "THE DATOM IS ASSERTED ONCE AND NEVER
   RETRACTED-AND-REASSERTED" (`resources/seon/schemas/seon.wake.edn:9-12`),
   restated at `wake.clj:54-60` and `turn.clj:3070-3073`. `:seon.issue/budget`
   is cardinality-one and resumed by raising it (`src/seon/issue.clj:944`),
   so §5's history shows three retract/assert pairs on one entity. Each pair
   delivers **two** matches, because routing ignores `:added`.
4. **Routing ignores `:added`; arming and the read side do not**
   (`wake.clj:552` and `:316` versus `:554-560`). Handling a message
   retracts `:seon.message/inbox` (`resources/seon/schemas/seon.message.edn:143-144`;
   `src/seon/turn.clj:435`), and that retraction routes a wake to the agent
   that just handled it. Harmless today only because the derivation that
   follows filters `:added` and finds nothing — a wasted pass, not a wrong
   answer.
5. **An authored pattern escapes the index requirement.**
   `wake/unindexed-listened-attributes` (`wake.clj:201-221`) checks only the
   schema-derived set. `:seon.issue/budget` has no `:seon.db/index true`
   (`seon.issue.edn:36`). Today that is invisible because no derivation
   seeks it (hack 1); the moment hack 1 is fixed, this becomes the silent
   empty seek the refusal exists to prevent.
6. **`:seon.listen/value`'s second alternative, `[:= []]`**
   (`resources/seon/schemas/seon.listen.edn:3-4`), is an undocumented empty
   vector in a value-constraint union. `find` treats its presence as a
   constraint (`wake.clj:422`), so it compares as an ordinary value; nothing
   in the resource explains what an empty-vector constraint means.
7. **The rendered surface hides two thirds of the pattern.** The runtime
   HTML renders only `:seon.listen/attribute` as a chip
   (`src/seon/render/transcript.clj:2246-2250`), although the AI-side pull
   selects all three (`:1092-1093`, `:2206`). An entity-constrained and an
   unconstrained listen on the same attribute look identical to a reader.
8. **The matcher rebuild trigger is a namespace-string test.**
   `(= "seon.wake" (namespace attribute))` / `"seon.listen"` plus a
   hard-coded five-attribute set (`wake.clj:532-538`) decides when to
   recompile. It is a name-based classification rule inside the dispatch
   path (`AGENTS.md`, §2.2: classification "never name-based").
9. **Docstring coordinate drift**: `wake.clj:17` cites
   `writer.cljc:384-386` for the listener call site, which is now `:414-416`.

### The grammar the log supports, stated and no further

Datahike hands the listener every datom of every committed transaction as
`(:e :a :v :tx :added)`, plus `:db-before`/`:db-after`, plus that
transaction's own metadata as datoms whose `:e` is the transaction id.
A matching language over that stream can therefore constrain entity,
attribute, value, assertion-versus-retraction, the transaction, and
transaction metadata (`:seon.db/user`, `:seon.db/process`, `:db/txInstant`),
in any combination. Seon's pattern today expresses a conjunction of
entity/attribute/value equality with attribute required, and nothing else.
The owner owns the design of the rest.
