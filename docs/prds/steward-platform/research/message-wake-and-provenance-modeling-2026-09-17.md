---
type: research
status: proposed, awaiting owner decision
created: 2026-09-17
tags: [schema, wake, message, evaluation, deletion, data-modeling, turn-loop]
---

# The message wake system, and three refs that a deletion breaks

Dated 2026-09-17. Read-only: no source edit but this note, no test JVM, no
`bin/seon` state change, no transaction. Two read-only
`mcp__seon__eval_clj` calls against cluster `default` (mode `jvm`, custody
`(seon.operator/connection "default")`), both pure `seon.db/q`/`datoms`
bundles; their output is §2. Branch `steward-platform` at the working tree.

The owner's words that opened this:

> *"the message wake system is a hack and we need to refactor it. We ran
> into this before and wrote notes for how to handle these things and I
> don't think identity values were the right fix. … It was there is no one
> size fits all and sometimes you need to refactor how the data is modeled.
> Consider how we can improve the schemas rather than hack shit together."*

Read end to end for this note: the turn PRD §0a, §3, §3a, §13–§15
([`agent-record-and-turn-loop-prd-2026-09-07.md`](../../context-generation/plan/agent-record-and-turn-loop-prd-2026-09-07.md));
the design ideas ledger rulings 61–72
([`design-ideas-ledger-2026-08-13.md`](../../context-generation/plan/design-ideas-ledger-2026-08-13.md));
[`what-listening-is-2026-09-16.md`](what-listening-is-2026-09-16.md) whole;
[`deletion-semantics-agents-and-turns-2026-09-16.md`](deletion-semantics-agents-and-turns-2026-09-16.md)
whole; [`schema-design-review-2026-09-17.md`](schema-design-review-2026-09-17.md)
§C.1 and rows N5/N8/N12/N14;
[`unbreakable-connections-2026-09-16.md`](unbreakable-connections-2026-09-16.md)
§§0–3, §5; [`retirement-is-a-fact-2026-09-16.md`](retirement-is-a-fact-2026-09-16.md) §1;
[`reset-batch-2026-09-17.md`](../plan/reset-batch-2026-09-17.md) rows for
`seon.cluster.eval.edn`, `seon.eval.edn`, `seon.message.edn`,
`seon.listen.edn`, `seon.wake.edn`. Source opened: `src/seon/turn.clj`,
`src/seon/cluster/wake.clj`, `src/seon/cluster/message.clj`,
`src/seon/note.clj`, `src/seon/error.clj` §the notification writer,
`src/seon/render/transcript.clj` §the outline, `src/seon/render/web.clj`
§inbound, and the schema resources named below.

---

## 0. The four sentences

**One.** The prior notes did not propose one fix; they proposed a **menu of
five deletion behaviours already implemented by the writer**
(`deletion-semantics-agents-and-turns-2026-09-16.md:79-93`), and said in as
many words that the choice is per attribute. The identity-value carrier the
owner is rejecting appears only in that note's §7, as an *option* on three
decisions it explicitly labelled "genuinely depend on a policy choice"
(`:421-505`). The blanket rule it recommended against is the eval-path
review's "every non-component ref is living" (`:494-505`).

**Two.** The message wake system is a hack for a reason the deletion notes
never reached: **three independent mechanisms decide whether a message has
been handled**, and the PRD ruled that exactly one should — the `:t`
comparison. Live proof in §2: `unanswered-wakes` with
`:seon.turn.work/answered? :any` — the mode whose whole purpose is to
*include answered wakes* — returns **zero for every agent in `default`**,
although history holds five message wakes. The declared `:t` rule is dead
code for the message family because the retraction got there first.

**Three.** Two of the three attributes in the brief are **not live at all**.
`:seon.cluster.eval/refreshes` has **0 datoms and no caller in `src/`** —
its only writer, `seon.turn/refresh-call` (`src/seon/turn.clj:815`), is
reached from `test/seon/turn_test.clj` and nowhere else. `:seon.eval/origin`
has **0 datoms** and exactly **one constructing writer**
(`src/seon/cluster/agent.clj:220`), which already holds the durable identity
`[:seon.issue/id issue-id]`. Neither needs a new carrier; one needs deleting
and one needs its declared type narrowed to the family that actually writes it.

**Four.** `:seon.message/about` is the real modeling defect, and it is not
a deletion problem. **One optional ref answers three unrelated questions** —
what the message concerns, whether the wake is inside, and which
request/response pair a message belongs to — and it is written from a
pre-read the writer re-decides. Splitting those three questions is the
refactor; the subject then stops being a ref for a reason that has nothing
to do with `retractEntity`.

---

## 1. What the prior notes ruled, and where the code drifted

### 1.1 The menu, not a rule (the note the owner remembers)

> *"Seon already has all four menu behaviours, and the dial that selects
> between 'refuse' and 'silent sweep' is `{:optional true}` in the entity map
> schema."* — `deletion-semantics-agents-and-turns-2026-09-16.md:20-22`

The five, verified there against the fork: **cascade** (`:seon.db/component`,
`reference-code/datahike/src/datahike/db/transaction.cljc:831-836`), **sweep**
(optional ref), **refuse** (required ref, through the final-report validator
at `src/seon/db.clj:3009-3041`), **value** (retype off `:seon.db/ref`), and
**move** — a pending edge that its own settlement migrates to a durable
sibling, which the note observed had been *independently invented twice*:
`:seon.message/inbox` → `:seon.message/read-tx` and `:seon.effect/notify` →
`:seon.effect/to` (`:91-93`, `:399-402` naming it).

That note's closing recommendation is the owner's "no one size fits all",
verbatim in engineering terms:

> *"**Recommendation: option two for this family** … the generic check should
> refuse not on *every* non-component ref, but on any swept ref whose absence
> is semantically load-bearing in the referrer"* — `:497-505`

And the reset batch already carries that as policy, not as a blanket rule:
*"Audit actual storable refs by their writer: components cascade; required
refs refuse …; optional refs may deliberately sweep. … Document the chosen
behavior; no universal enforcement"* (`reset-batch-2026-09-17.md:386`).

**So the standing ruling is already "it depends, per attribute, declared in
the docstring."** The identity-pair carrier was one note's §7 recommendation
on three undecided rows, and this note withdraws it (§3, §4, §5).

### 1.2 The wake rulings, and the drift

Ruling 70 (owner, 2026-09-07), the ledger's own words:

> *"messages, faults, and schedule firings are three peer components each
> routed by its own attribute through the one Datahike listener ('I never
> said one wake queue'); **handled = a claim ref from the handling run,
> because retracting a routed edge would wake**."* —
> `design-ideas-ledger-2026-08-13.md:1051-1059`

The turn PRD then superseded even the claim ref, and retired the vocabulary:

> *"Retired spellings, never written again: claim, pending, **inbox**,
> mailbox, **trigger**, freeze, settle, episode, wake item, wake source,
> situation."* — PRD `:108-110`

> *"**A listened attribute** is a ref attribute whose schema row carries
> `:seon.wake/listen true`; its value is the agent to wake. Today:
> `:seon.message/to` …"* — PRD `:298-301`

> *"**Answered is derived from `:t`, nothing is stored.** … **A wake datom is
> asserted once and never retracted-and-reasserted** … No reference from turn
> to wake, no claim, no per-wake write — the reviewers' B1, B3 and B6
> dissolve rather than get fixed."* — PRD `:330-346`

**The drift, four items, each with its line:**

1. The listened attribute is **not** `:seon.message/to`. It is
   `:seon.message/inbox` (`resources/seon/schemas/seon.message.edn:141-152`),
   a *second* ref to the same agent, asserted beside `to` by both writers
   (`src/seon/cluster/message.clj:216`, `:270-271`). Two datoms carry one
   fact.
2. That edge is **retracted on handling** — `src/seon/turn.clj:435-437` in
   `close-call`, and `src/seon/cluster/message.clj:276-277` in `delivery` —
   which is exactly the "retracting a routed edge would wake" ruling 70
   forbade, and exactly the "asserted once and never retracted" invariant
   the attribute's own sibling declares
   (`resources/seon/schemas/seon.wake.edn:9-12`).
3. `close-call` reaches the message through `::trigger` — a retired spelling
   — stored on the turn and on the runtime (`src/seon/turn.clj:424-430`,
   `:435`), and re-derives answeredness a *third* way inside itself
   (`:427-434`: reply-size later than the opening `:t`, plus a recipient
   equality check).
4. `:seon.message/read-tx` is written in the same breath (`:437`,
   `message.clj:277`) although ruling 64 said *"Messages get NO `read-at`
   fact"* (`design-ideas-ledger-2026-08-13.md:964-968`).

`what-listening-is-2026-09-16.md` §6 already filed the consequences as
hacks 3 and 4 (`:377-398`): routing never consults `:added`
(`src/seon/cluster/wake.clj:554-560` versus the armer's `:552` and the read
side's `:315-316`), so **handling a message routes a fresh wake back to the
agent that just handled it**.

### 1.3 The per-attribute recommendations the prior notes already made

| Attribute | Prior recommendation | Where |
|---|---|---|
| `:seon.listen/entity` | value **plus a positive `any-entity?` fact**, so absence is unrepresentable; "the worst instance in the family and the one that needs no policy debate" | `deletion-semantics…:200`, `schema-design-review-2026-09-17.md:65` |
| `:seon.message/about` | value — "absence is load-bearing here (`presence marks INSIDE`), so a sweep manufactures the false arm of a semantic flag" | `deletion-semantics…:181` |
| `:seon.message/from` | refuse (required) | `deletion-semantics…:178` |
| `:seon.eval/origin` | identity pair, *or* leave and document | `deletion-semantics…:425-437`; N12 said docstring only, `schema-design-review-2026-09-17.md:80` |
| `:seon.cluster.eval/refreshes` | value, "genuinely low stakes and could wait" | `deletion-semantics…:439-448` |
| `:my.note/about` | value or refuse, undecided | `deletion-semantics…:433-437` |

§§3–5 revise four of these six on evidence the prior notes did not have:
the caller census and the live datom counts.

---

## 2. Live evidence (cluster `default`, two read-only JVM queries)

Five agents (`root`, `juniper`, `s3-provenance-a/b/c`), 81 evaluations,
3 messages. The cluster was reset since the 2026-09-16 note, so these
counts replace that note's.

```
:seon.cluster.eval/refreshes datoms          0
:seon.eval/origin datoms                     0
:seon.message/about datoms                   1   → target identities
                                                 [:seon.error/id :seon.error/signature]
:my.note/about datoms                        1
messages                                     3
  attribute combinations (one each):  {about, read-tx} {from, read-tx} {read-tx}
:seon.message/inbox in history          5 asserted, 5 retracted
listened attributes   #{:seon.message/inbox :seon.effect/to
                        :seon.issue/agent :seon.schedule.fire/agent}
opens-turn? true      #{:seon.message/inbox :seon.effect/to :seon.issue/agent}
inside attributes     #{:seon.message/about :seon.message/from :seon.effect/to}
```

Per-agent, from the second query:

| agent | `latest-answering-turn-t` | `outside-wake-t` | `unanswered-wakes` | `unanswered-wakes {:answered? :any}` |
|---|---|---|---|---|
| `root` | 536871014 | 536871216 | `[]` | **0** |
| `juniper` | 536871054 | 536871045 | `[]` | **0** |
| `s3-provenance-c` | 536871174 | 0 | `[]` | **0** |
| `s3-provenance-a`/`-b` | 0 | 0 | `[]` | **0** |

**The `:any` column is the finding.** `unanswered-wakes` documents that key
as *"`:seon.turn.work/answered?` `:any` includes answered wakes"*
(`src/seon/turn.clj:3054-3056`), and it returns zero although history holds
five message wakes and three surviving messages. The reason is structural:
`wake/agent-wake-datoms` seeks the **live** `:avet` index
(`src/seon/cluster/wake.clj:312-316`), and `unanswered-wakes` hands it the
live database (`src/seon/turn.clj:3084`) while `outside-wake-t` hands it
`(db/history db)` (`src/seon/turn.clj:2774`). A retracted `inbox` datom is
absent from the live index, so for the message family **the retraction, not
the `:t` comparison, is what makes a wake stop being a wake.** The declared
answering rule is unreachable, and the `:any` request key cannot do what its
own docstring promises.

Second observation from the same data: the five `inbox` assertions cover
five distinct entities (48645, 49432, 49910, 49969, 50216) against three
surviving messages — two message entities that once carried an inbox edge
are gone from the current database. Whatever removed them removed a wake's
subject; nothing refused, and nothing recorded it.

---

## 3. Case one — the message's subject, and the inside/outside flag

### 3.1 The data model as it is

```clojure
;; resources/seon/schemas/seon.message.edn:25-31
:about [:and {:description "The entity this message concerns when one was named.
               Its presence marks the message an INSIDE wake: an agent in an
               error loop must not have its turn bound reset by its own failure
               notifications."
              :seon.db/index true
              :seon.wake/inside true}
        :seon.db/ref]
```

Optional in `:seon.message/message` (`:82-84`). Three writers:

| writer | what it stores | line |
|---|---|---|
| `seon.cluster.message/delivery` | the eid `resolve-about` produced from an agent-supplied identity **string** | `src/seon/cluster/message.clj:171-190`, `:258`, `:271` |
| `seon.error/message-tx` | the lookup ref `[:seon.error/signature …]` — the writer already holds the identity value | `src/seon/error.clj:1310` |
| `seon.turn/…` problem notification | the evaluation's eid | `src/seon/turn.clj:3483` |

### 3.2 The three questions one attribute is answering

1. **"What does this message concern?"** — rendered
   (`src/seon/cluster/message.clj:397`, `:539`), and the only question the
   docstring's first sentence describes.
2. **"Is this wake the population's own activity?"** — via
   `:seon.wake/inside true`, consumed by `wake/inside-wake?`
   (`src/seon/cluster/wake.clj:319-330`) inside `turn/outside-wake-t`
   (`src/seon/turn.clj:2765-2775`), which anchors the paid turn bound.
3. **"Which assignment does this reply answer?"** — `turn/assignment-facts`
   (`src/seon/turn.clj:2598-2635`) joins
   `[?assignment :seon.message/about ?problem] [?assignment :seon.message/from ?author] [?assignment :seon.message/to ?owner]`
   and the mirrored `declination?` clause. `about` is the **correlation key of
   a request/response protocol** here, not a subject mention.

Question 2 is answered wrongly even with no deletion in sight. The
declaration reads "a message that names a subject is inside", and that is
false in general: a human may name a subject. It happens to hold today only
because `inbound-tx` — the one door from outside
(`src/seon/cluster/message.clj:198-220`) — never writes `about`. The flag is
riding a correlation, not a fact.

### 3.3 What a deletion or a compaction does today

`inside-wake?` seeks the wake entity's datoms in the **live** database
(`wake.clj:328-330`) while the candidate wake comes from **history**
(`turn.clj:2774`). So any retraction that removes an inside-marking attribute
— `[:db/retractEntity <the subject>]` sweeping the incoming `about` datom
(`transaction.cljc:1002-1014`), or a direct retraction — silently reclassifies
a historical inside wake as **outside**. `outside-wake-t` then jumps forward,
`turns-left` (`src/seon/turn.clj:2824-2831`) refills, and the agent gets paid
turns it did not earn. This is the fail-open direction on the expensive side —
the very failure `opening-deferred?` calls out in its own docstring
(`src/seon/turn.clj:2832-2840`).

The routine event that triggers it is not hypothetical: `seon.issue/index-tx`
retracts every stored issue whose note file is gone
(`src/seon/issue.clj:405-407`), unattended, on every adoption.

### 3.4 The properly modeled alternative

Three facts, each answering one question, none of them a pointer that a
deletion can silently unmake.

**(a) The subject is the identity token the agent supplied, stored as a
value.** `my.message/about` is *already a string* at the agent's surface
(`resources/seon/schemas/my.note.edn` has the same shape; message side at
`src/seon/cluster/message.clj:171`), and `resolve-about` converts it to an
eid by scanning `[?entity ?attribute ?identity]` across the whole database
(`:151-169`) — an unbound-attribute scan **and** a pre-read whose answer the
writer re-decides (owner law 2026-08-29, `AGENTS.md`). Store the token.
"Which entity is that?" becomes `identified-entities` at read time, where the
ambiguity refusal belongs, and "a message about a subject that no longer
exists" becomes one `not-join` clause instead of an impossibility. Every
target that exists today has a **string** identity (`:seon.error/signature`,
`:seon.cluster.eval/id`, `:seon.issue/id`, `:my.plan.item/id`), so this is
one `:string`, not an identity pair.

**(b) Inside/outside is a property of the admitting door, and it already has
exactly two.** `:seon.message/from` is present on every message the
population writes through `delivery` (`message.clj:270`) and absent on every
message `inbound-tx` admits (`:216`). Make `from` the *only*
`:seon.wake/inside` attribute on the message family and give the error
recorder's notification a real sender, so the flag rides a fact about the
writer rather than a correlation with a subject. Then no deletion can flip a
classification, because `from` points at an agent and §5(c) of the deletion
note shows agents are undeletable in practice
(`deletion-semantics…:319-343`).

**(c) The assignment protocol gets its own edge.** `assignment-facts`'s two
queries are a *protocol*: "this message assigns that problem", "this message
declines that assignment". Store that as its own attribute over the
evaluation's identity (`:seon.cluster.eval/id`), leaving `about` to mean only
what its first sentence says.

### 3.5 Recommendation, with the trade

**Recommended: (a) + (b) + (c), in that order of value.**

*Pros.* Each attribute answers one question, so no sweep can manufacture a
false arm of anything. The turn bound stops being deletion-sensitive, which
is the only one of the three that costs money when it is wrong. `resolve-about`
and its whole-database scan dissolve, along with two of the six error classes
declared for the message family (`seon.message.edn:32-43`, `:175-184` —
`unknown-about` and `ambiguous-about` move to the reader, where they are
answers rather than refusals). The `[:seon.error/signature …]` lookup ref at
`error.clj:1310` becomes the stored value directly.

*Cons.* `:seon.message/about` changes type, which is breakage under §2.5 and
therefore a **reset-batch item**, not an accretion. Rendering
(`message.clj:397`, `:539`) must resolve the token to show a subject, one
extra lookup per rendered message. And (c) adds an attribute where today
there is reuse — the honest price of separating a protocol from a mention.

*Alternative considered and not recommended: make `about` a required ref
(menu 3).* It would refuse the routine file-driven issue retraction at
`src/seon/issue.clj:405-407` the moment any message named that issue — a lock
nobody asked for, on the family's most frequent deletion.

*Option C, if the owner wants the mechanism gone rather than fixed.*
Derive inside/outside from **transaction provenance** instead of any
attribute: `:seon.db/user` is stamped in `:tx-meta` on every agent-side write
(`src/seon/plan.clj:500`, `src/seon/note.clj:241`,
`src/seon/cluster/message.clj:748`, `src/seon/agent.clj:69`) and arrives in
the listener's own `:tx-data` as a datom on the transaction entity
(`what-listening-is-2026-09-16.md:186-200`). Transactions are never
retracted, so the classification would be permanently deletion-proof, and
`:seon.wake/inside` — a schema property, its derivation
(`wake/inside-attributes`, `src/seon/cluster/wake.clj:180-199`) and its
per-entity seek — would all be deleted. **Prerequisite, unverified and
load-bearing:** today a human post through the web UI stamps *no*
`:seon.db/user` at all unless the poster names an existing agent
(`src/seon/render/web.clj:2875-2879`), so "outside" would be read from an
absence — the project's named failure class. Option C is only honest once a
human account is a positive identity
([`human-accounts-design-2026-08-05.md`](../../sci-execution-runtime/research/human-accounts-design-2026-08-05.md)).
Priced here so the owner can rule on the order.

**Schema edit.** `seon.message.edn:25-31` — `:about` becomes
`[:string {:min 1, :seon.db/index true, :description "The identity token this
message concerns …"}]`, losing `:seon.wake/inside`; `:from` (`:119-125`)
keeps `:seon.wake/inside` and gains the absence-condition docstring the reset
batch already asks for (`reset-batch-2026-09-17.md:605`); one new attribute
for (c). **Writers to change:** `seon.cluster.message/delivery`
(`message.clj:221-277`, deleting `resolve-about` `:171-190` and
`identified-entities` `:151-169`), `seon.error/message-tx`
(`error.clj:1302-1311`), the problem notification (`turn.clj:3483`), and the
`about` readers at `message.clj:397`, `:539`. **Readers to change:**
`turn/assignment-facts` (`turn.clj:2598-2635`). **Reset batch: yes** — it
lands in the existing `seon.message.edn` row
(`reset-batch-2026-09-17.md:604`), whose current text ("about identity
carrier preserves wake classification") this note replaces: the carrier is a
plain token and the classification moves off `about` entirely.

### 3.6 And the hack the owner actually named

The subject is one half. The other half is §1.2's four drift items, and they
are separable work with a simpler shape:

- **Delete `:seon.message/inbox`.** Declare `:seon.wake/listen true` and
  `:seon.wake/opens-turn? true` on `:seon.message/to`
  (`seon.message.edn:20-24`), which is already indexed, already required,
  already "the permanent recipient record", and already what PRD §3 named.
  One datom carries one fact and is never retracted.
- **Delete the retractions** at `src/seon/turn.clj:435-437` and
  `src/seon/cluster/message.clj:276-277`, and `:seon.message/read-tx`
  (`seon.message.edn:199-203`) with them. Handled becomes what the PRD ruled:
  `:t ≤ latest-answering-turn-t`, one comparison, nothing stored — and ruling
  64's "no `read-at` fact" is honoured for the first time.
- **Delete `::trigger`** from the turn and the runtime
  (`seon.turn.edn:43`, `seon.runtime.edn:7`) with `close-call`'s third
  answeredness derivation (`turn.clj:424-437`); `unanswered-triggers`
  (`turn.clj:3092-3110`) keeps its name and reprojects onto
  `:seon.message/to`.
- **The `:any` mode becomes true.** With no retraction, the live `:avet` seek
  and the history seek agree, and `outside-wake-t`
  (`turn.clj:2774`) and `unanswered-wakes` (`turn.clj:3084`) stop reading two
  different databases for one notion.

*Pros.* One mechanism replaces three; the wasted wake that handling routes
back to the handler (`what-listening-is…:392-398`) disappears with the
retraction that caused it; four retired spellings leave the tree. *Cons.*
"Unread" stops being a live-index question and becomes a `:t` comparison, so
the unread count and the page (`turn.clj:3095-3099` names those callers) pay
one `latest-answering-turn-t` query. **Reset batch: yes**, and it should ride
the same publication as §3.5 so the message family is cut once.

---

## 4. Case two — the evaluation's generating origin

### 4.1 The data model as it is

```clojure
;; resources/seon/schemas/seon.eval.edn:1
:origin [:and {:description "The entity whose render declared this generated
                evaluation. Carried from since-diff refreshes; absent on
                agent-authored reads."
               :seon.wake/context-inert true}
         :seon.db/ref]
```

Optional (`seon.cluster.eval.edn:64`, `:129`; `seon.turn.edn:190`). **0 live
datoms.** One constructing writer:

```clojure
;; src/seon/cluster/agent.clj:218-221
{:seon.render/source (issue.opening/source database issue-id)
 :seon.eval/origin [:seon.issue/id issue-id]}
```

Every other mention in `src/seon/turn.clj` — `:103`, `:773`, `:923`, `:1474`,
`:1547`, `:1618`, `:2054`, `:2304`, `:4106`, `:4680`, `:4990` — carries that
same value forward through settlement, recovery and the silent in-place
refresh. **The origin is always an issue, and the writer already holds its
identity value.** N12's premise — *"it is untyped, so it has no single
identity type"* (`deletion-semantics…:429-431`;
`schema-design-review-2026-09-17.md:80`) — is a statement about the
declaration, not about the data.

### 4.2 What question it exists to answer

Two, and they are different:

1. **A live gate.** `issue-origin-read?` (`src/seon/turn.clj:2154-2158`)
   pulls the origin and asks whether the issue still has
   `:seon.issue/agent` — "is this generated read still declared?"
2. **A history label.** `outline-origins`
   (`src/seon/render/transcript.clj:1584-1602`) takes the ref and **converts
   it back to an [identity-attribute, value] pair** by pulling every installed
   identity attribute. The reader wants the identity value; the storage gives
   it an eid and pays a pull per render.

It does **not** answer requery. The requery form is already durable:
`:seon.cluster.eval/source` stores the generated form verbatim
(`turn.clj:891-896`, the `source` branch of `receipt-row`), so any evaluation
can be re-read and re-run from its own row. The brief's question — *"is the
requery form itself the durable fact, with the origin only a derivation?"* —
answers **yes**, and it is already true.

### 4.3 What a deletion does today

A deleted issue note retracts the issue (`src/seon/issue.clj:405-407`), the
sweep removes every incoming `origin` datom, and nothing refuses. Reader 2
loses its label silently. Reader 1 is worse: with the origin gone,
`issue-origin-read?` returns `nil` and the generated read is reclassified as
an ordinary agent read forever, which is a *behaviour* change in the since-diff
selection (`system-plan`, `turn.clj:2114-2125`), not just a missing label. The
2026-09-16 note measured three such edges on one deletable issue
(`deletion-semantics…:296-298`).

### 4.4 The properly modeled alternative

**Retype `:seon.eval/origin` to `:seon.issue/id` (`:string`).** Not an
identity pair — a single typed identity value, because exactly one family
writes it and its writer already spells it that way.

*Pros.* Deletion touches nothing; "generated by an issue that no longer
exists" becomes representable and reportable instead of indistinguishable
from "agent-authored". `outline-origins` collapses from an
identity-attribute census plus a pull to reading the stored value — the
derivation the renderer performs today *is* the value we would store, which
is the tell that the ref is the wrong carrier. N12's "untyped" objection
dissolves by narrowing the declaration to the truth; a second origin family
later is accretion (a widened union, `AGENTS.md` §2.5), not breakage.

*Cons.* Reader 1 pays a lookup by `:seon.issue/id` rather than holding an
eid — one `:avet` seek, against the pull it already does. And the
declaration now names a family, which is a coupling the `[:and …]` ref
hid; that coupling is real either way, and hiding it is what made N12
unable to state a type.

*Alternative considered: delete the attribute.* The declared set is
recomputed from the render walk every system turn
(`declared-sources`, `turn.clj:2020-2059`), and `changed-sources` merges
declared with latest (`turn.clj:2114-2152`), so reader 1's question could be
asked of the *current* declaration instead of a stored back-pointer — the
purest dissolution, and it removes a mirror of derived state. It is not
recommended **only** because reader 2 genuinely needs a historical label: an
evaluation generated by an issue that is no longer declared must still say so
in the outline, and the walk cannot answer that about the past. Worth the
owner's eye: if the outline's origin label is expendable, deleting the
attribute is strictly simpler than retyping it.

**Schema edit.** `seon.eval.edn:1` — `:origin` becomes
`[:string {:min 1, :description "The issue whose opening declared this
generated evaluation, by `:seon.issue/id`. Absent on agent-authored reads.
The issue may since have been retracted; the token survives it."}]`,
keeping `:seon.wake/context-inert`. **Writers to change:**
`src/seon/cluster/agent.clj:220` (drop the lookup-ref vector for the id),
and the eleven carry-forward sites in `src/seon/turn.clj` listed in §4.1
lose their `:db/id` unwrapping (`:1547-1548`, `:1621-1622`, `:4122`).
**Readers to change:** `turn/issue-origin-read?` (`:2154-2158`) and
`transcript/outline-origins` (`:1584-1602`, which shrinks to a lookup);
`transcript.clj:1587-1589`'s comment about reading installed identity
attributes goes with it. **Reset batch: yes** — the `seon.eval.edn` row
already anticipates it: *"Historical origins that must survive source
deletion become target identity values"* (`reset-batch-2026-09-17.md:562`).
With 0 live datoms the cut is free.

---

## 5. Case three — the since-diff's supersession edge

### 5.1 The data model as it is

```clojure
;; resources/seon/schemas/seon.cluster.eval.edn:26-27
:refreshes [:and #:seon.db{:unique true :seon.wake/context-inert true}
            :seon.db/ref]
```

`:seon.db/unique` bridges to `:db.unique/value`
(`src/seon/schema/datahike.clj:272`). Optional in `/receipt` (`:49-51`).

**0 live datoms, and no caller in `src/`.** The only writer is
`seon.turn/refresh-call` (`src/seon/turn.clj:815-869`), reached from
`seon.turn/refresh-tx` (`:804-809`), whose only callers are
`test/seon/turn_test.clj:1334`, `:1363`, `:1379`. The only reader is the
successor guard inside that same function (`:838-849`). The `refresh-tx`
symbol at `src/seon/turn.clj:2293` is a **local binding** in the live
since-diff, a different thing entirely: it retracts stale read evidence and
re-asserts `:seon.cluster.eval/read-basis-transaction` in place
(`:2296-2306`) without writing any supersession edge.

### 5.2 What question it exists to answer, and how the live code answers it

"Which evaluation supersedes which." The **live** since-diff never asks it.
It asks a different question and answers it with a query over what is
already stored:

- `latest-evaluations` (`turn.clj:2062`) folds the agent's evaluations into a
  map keyed by `source-key` — `[namespace, the read forms]`
  (`turn.clj:2013-2018`) — keeping the newest by
  `[:seon.turn/basis-t :seon.cluster.eval/ordinal]` (`:2145-2148`).
- `changed-sources` (`turn.clj:2114-2152`) then asks
  `db/read-evidence-current?` of that latest evaluation's evidence and marks
  it `:unchanged` / `:changed` / `:none`.

That is PRD §14 executed exactly as written — *"for EVERY distinct read form
in the transcript … the loop looks at its **latest** evaluation"*
(PRD `:971-979`). **"The latest evaluation of this read form" is a query over
`source-key` and ordinal. It needs no edge**, and the running system proves it:
81 evaluations, 0 `refreshes` datoms.

### 5.3 What a deletion or a compaction does today

Nothing, because the attribute is never written. The prior note's stated
failure — *"compaction of an older evaluation silently breaks the
supersession chain"* (`deletion-semantics…:441-444`) — is unreachable in
production. Had it been written, compaction (`turn/compact-call`,
`src/seon/turn.clj:2415-2419`) would sweep it exactly as described.

One defect is real and independent of deletion: the successor guard at
`turn.clj:838-849` is **a pre-read the authority re-decides**. `:db.unique/value`
already makes a second successor unrepresentable — Datahike raises
`:transact/unique` from `validate-datom`
(`reference-code/datahike/src/datahike/db/transaction.cljc:26-31`) — so the
query and its `::refresh-successor-exists` refusal restate a constraint the
writer enforces one layer down.

### 5.4 The properly modeled alternative

**Delete `:seon.cluster.eval/refreshes`, `seon.turn/refresh-tx` and
`seon.turn/refresh-call`.** Supersession is not a chain; it is
`latest-evaluations`' `source-key` fold, and it is what the loop already runs.

*Pros.* Dissolution, not a retype: one attribute, two functions, six refusal
paths (`::no-such-form`, `::refresh-agent-authored`,
`::refresh-receipt-not-terminal`, `::refresh-read-evidence-missing`,
`::refresh-successor-exists`, plus the pre-read) and three tests that prove a
path production never takes. The reset-batch docstring row for `refreshes`
(`reset-batch-2026-09-17.md:530`) disappears rather than being written. It
also removes the one remaining pre-read-the-authority-re-decides in the
evaluation family.

*Cons.* The ability to ask "which evaluation refreshed which" as one clause
is lost. It is derivable — same `source-key`, ordered by the turn's `:t` —
but as a fold in Clojure rather than a Datalog join, because `source-key`
reads the source text through the reader (`turn.clj:2014`) and is not a
stored value. If the owner wants that join to exist, the honest fix is to
**store the source key** (the read form's own digest, `seon.id/digest`) on
the evaluation and join on equality — a value, no edge, and it makes the
since-diff's own grouping queryable for the first time. That is a strictly
better version of what `refreshes` was reaching for and it is priced in §6.

*Alternative considered: retype to `:seon.cluster.eval/id` (the prior note's
option two).* It preserves a mechanism no caller uses. Under §2.5's standing
test — *is this simpler than it was?* — retyping is not; deleting is.

**Schema edit.** `seon.cluster.eval.edn:26-27` and `:49-51` deleted.
**Writers to change:** `src/seon/turn.clj:804-869` deleted, and the
`declare` at `:349`. **Tests to change:**
`test/seon/turn_test.clj:1334`, `:1363`, `:1379` deleted with the path they
prove. **Reset batch:** it should ride the `seon.cluster.eval.edn` row
(`reset-batch-2026-09-17.md:527`) for one publication, but with 0 datoms it
needs no reset of its own.

---

## 6. What is still a policy choice

Honestly listed; none of the four is decided by evidence in this note.

1. **Whether the assignment/declination protocol deserves its own
   attribute** (§3.4c), or whether reusing a subject mention as a correlation
   key is acceptable because the pair is small. Cost of the split: one
   attribute and two rewritten queries (`turn.clj:2598-2635`). Cost of not
   splitting: `about` keeps two meanings and the next reader must learn both.
2. **Option C for inside/outside** (§3.5): derive from transaction
   provenance and delete `:seon.wake/inside` entirely, versus keep the
   attribute-level declaration on `:seon.message/from` alone. Option C is
   strictly more dissolution and is blocked on a positive human account
   identity; the owner owns whether that prerequisite is scheduled or the
   flag stays.
3. **Whether the outline's historical origin label is worth an attribute**
   (§4.4). If it is expendable, deleting `:seon.eval/origin` beats retyping
   it.
4. **Whether to store the read form's digest on the evaluation** (§5.4), so
   "the latest evaluation of this read form" and "which evaluation superseded
   which" become Datalog joins instead of a Clojure fold. It is accretion
   (one new optional value), it is cheap, and nothing today needs it — which
   is exactly why it is a choice and not a recommendation.

Two things this note does **not** decide and did not reopen:
`:seon.listen/entity` (the prior notes' worst instance, `deletion-semantics…:200`;
the reset batch deliberately excludes the listener redesign,
`reset-batch-2026-09-17.md:582`), and `:my.note/about` — one live datom, one
writer (`src/seon/note.clj:189-209`), one reader (`:52`, `:72-81`), and no
semantic flag riding on it, so it is the plain case the prior note said it
was: a value if the subject's identity is wanted after deletion, a sweep if
not. Its only current reader pulls `:my.plan.item/id`
(`src/seon/note.clj:72`), which is a string, so the same single-token
treatment as §3.4(a) applies whenever that family is cut.

## 7. Verification boundary

Every file:line above was opened in this session. The live figures in §2 are
from the two read-only JVM evaluations described at the top, against cluster
`default` at the moment of writing; nothing was transacted and no cluster
state changed. Caller censuses ("no caller in `src/`") are `rg` over `src/`
and `test/` at the working tree, which includes other lanes' uncommitted
edits. `:seon.cluster.eval/refreshes` and `:seon.eval/origin` having zero
datoms is a statement about `default` after its most recent reset, not about
every database that ever existed; both attributes had live datoms on
2026-09-16 (`deletion-semantics…:120-128`), which is why §4 recommends a
retype rather than deletion for `origin`. No test JVM was launched and no
schema was published.
