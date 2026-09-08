---
type: prd
status: r11 (2026-09-08 midday) — steps 1–2 landed; step 3 partial (answered-by-attempt + index-bound derivation `8dc708b24`; backstop subject carried `4c4bcde70`); clipping collapsed to the value renderer (`9248692d6`); all lanes stopped — the orchestrator is cutting the retired code directly (§11)
date: 2026-09-07 (evening)
supersedes: the record (§2, §3) and loop (§6) sections of agent-record-and-repl-response-prd-2026-09-07.md
tags: [prd, agent, wake, storage, runtime]
---

# The agent record and the turn loop

## 0. The owner's rulings this evening, verbatim where it matters

- "The agents context can and should always be a projection of its data. If
  we store evals then those are surfaced as part of its history. We are also
  generating forms and running them to make context. There is no ever
  increasing message transcript. We want a byte identical projection method
  for this reason so the same db creates the same context."
- "Elision happens only at the AI context generation boundary."
- "I want bounded storage for result blobs too … We never promise to be able
  to restore a session fully and if we do restore and values are missing we
  mark it as missing in the context by ablating the result ids." (A total
  storage limit is retention — the root maintenance portfolio — not this PRD.)
- "Functions, tests, schemas should be persisted. Data that is intentionally
  stored in the database should be persisted. Intermediate eval values? I
  think that's likely to cause problems."
- "The loop needs to keep track of turns and otherwise I don't understand why
  it isn't as simple as `context = fn(db)`, `eval_forms = model(context)`,
  `evals = eval(eval_forms)` and then store everything in the db."
- "The inbox is the wrong mechanism for the waking … we need a better name
  and for other things to be able to wake the agent." (Earlier, ruling 70:
  separate, well-schemad wake components; a claim is a ref, never a
  retraction; namespace is not identity.)
- "Do the design work for the data we will be storing on the agent's entity
  first and ground everything in data that we actually need. Question
  everything else."

## 0a. Words

Only Clojure's and the dependency's words. A **wake** is a datom asserted on
an attribute declared listened; Datahike `listen` reports the transaction
and we filter by attribute. A turn records the **basis** it projected from,
Datahike's basis transaction `:t`. A wake is **answered** when a turn of
that agent has a basis at or after the wake's own transaction `:t`;
otherwise it is **unanswered**. A turn **stores** the reply's forms, then
**stores** the results. Retired spellings, never written again: claim,
pending, inbox, mailbox, trigger, freeze, settle, episode, wake item, wake
source, situation.

## 0b. What the REPL prototype changed (commit `8d3745a04`)

1. **The basis is the turn's own transaction.** The `:t` of the transaction
   that opened the turn — the `:t` on its identity datom — reproduced a
   real 345,439-character stored prompt EXACTLY at `(as-of db t)`; the
   pre-open commit today's `opening-commit-id` records is off by one every
   turn and swallows a wake transacted with its own turn (a fresh agent's
   first wake was unanswerable forever). So `:seon.turn/basis-t` is NOT
   stored: it is the turn's own `:t`. `opening-commit-id` is deleted and
   nothing replaces it.
2. **A wake datom is asserted once.** Retract-then-reassert moves its `:t`
   forward and re-opens a paid turn. Message, fault, and firing entities are
   immutable facts; the rule is stated on every listened attribute.
3. **Two wakes in one transaction are one turn — and that deletes a live
   defect**: the fixture's two messages share one `:t`; today's loop opened
   two runs and paid twice.
4. **Attribute properties already reach schema rows** through one general
   mechanism (25 distinct `seon.*` properties across 2,459 rows), so the
   Opus review's B4/S3 is refuted and §8 step 2 shrinks to: declare
   `:seon.wake/listen`, derive `route!`'s set from it, delete the two hand
   lists. Today's hand list also includes `:seon.effect/to` (an effect's
   settlement wakes the requester — a listened attribute this PRD adds) and
   `:seon.cluster.agent/id` (a STRING identity used to wake a freshly created
   agent; deleted — the agent's first message is its first wake).
5. **The turn bound derived by `:t` costs 0.115 ms** against 0.134 ms for
   today's derivation, same answer. No counter. The anchor is the OUTSIDE
   wake's `:t`: `turns = count of turns whose :t ≥ that wake's :t` (the
   answering turn counts as one).
6. **`:seon.render/distance` is a projection input** the PRD had not listed:
   distance 2 reproduced the capture, distance 1 gave 51,561 characters. It
   belongs to the render profile, so "same db, same adopted commit, same
   profile" stays the whole qualification.
7. The two-arm `step` returned `:evaluate` / `:reply` / `:idle` from one
   database value on the three fixtures; one JVM per operator root and one
   root-scoped flock confirmed (pid equality across two clusters).

## 0c. Names in the wave (owner go, 2026-09-07)

| today | after | grounding |
|---|---|---|
| `seon.cluster.run`, `:seon.cluster.run/*` | `seon.turn`, `:seon.turn/*` | "agent turn" is the vocabulary row |
| `seon.cluster.eval` storage keys (`result-edn`, `triage-edn`) | accreted into `seon.eval`: `:seon.eval/value`, `/out`, `/error`, `/triage-edn`, `/missing`, `/size` | Clojure's words for a REPL's outputs |
| `seon.cluster.agent` | `seon.agent` | ruling 70: drop the `cluster` segment |
| `seon.cluster.message` | `seon.message` | same |
| `seon.cluster.wake` | `seon.wake` | same; Datahike `listen` inside |
| `seon.cluster.work` + `seon.cluster.loop` | one owner `seon.turn`: `step`, `open`, `store`, `close` | the loop IS the turn |
| `my.run` | `my.turn` | agent-facing protocol over the same facts |
| receipt, trigger, situation, claim, inbox, mailbox, episode, freeze, settle | retired spellings | §0a |

Unchanged: `seon.ai.attempt`, `seon.error`, `seon.schedule` (+ `seon.schedule.firing`),
`seon.print`, `seon.repl`, `my.plan`, and `seon.cluster` itself (the boot
owner of a branch).

Simulated turns suffice for every proof: a source submission opens a turn
with a supplied reply and no attempts; a wake is one transacted message;
tests stand in the evaluator. No real provider call is required.

## 1. The loop, as the owner stated it, plus exactly what a crash forces

```
context    = project(db, agent)          ; pure; same db + profile ⇒ same bytes
reply      = model(context)              ; the paid call
forms      = read(reply)                 ; comments + forms, the existing reader
evals      = eval(forms)                 ; in the agent's fork, in order
store everything; next turn is the same
```

Two facts a crash forces, and nothing else:

1. **The reply and its forms are stored BEFORE evaluation.** A crash between
   "the model replied" and "results stored" must neither pay the model again
   nor re-execute a form (the nothing-re-executes law). So a turn is two
   writes: store the forms, then store the results. Not one.
2. **The process turning the agent is a stored fact.** After a restart, an
   evaluation with no terminal fact whose process is dead is *interrupted*;
   one still running is not. Absence is the one value a dead process cannot
   corrupt.

Plus one thing that keeps an agent from running forever: a turn happens only
when something woke it (§3) and it has turns left.

## 1a. Every fact the loop stores today, audited against derive-or-need

Ground truth the audit rests on (probed by the Opus review): ONE JVM per
OPERATOR ROOT hosting every cluster in it, under a lifetime `flock` on the
root's store (`src/seon/cluster/store.clj`); one turn proc per (cluster,
agent) with an in-memory turn permit (`seon.cluster.agent/await-turn-permit!`);
Datahike's serial writer. A second live process on any branch of the root is
unrepresentable, so at boot every open turn is dead by construction.

| stored today | verdict | reason |
|---|---|---|
| run id, agent, opened-at, closed-at | KEEP as the turn | open = no closed-at |
| run trigger ref | DELETE | answered is derived: the wake's `:t` ≤ a turn's own `:t` (§3). Datahike already stamps every datom with its `:t` |
| run opening basis (`opening-commit-id`) | DELETE — the basis is the turn's own `:t` | the transaction that opened the turn is the basis the context projected from (prototype 0b.1); nothing to store |
| run reply text / blob / size | KEEP under the storage bound | without it an interrupted turn's history cannot say what the model said |
| run `process` custody | DERIVE, delete the stamp | at boot every open turn in this cluster belongs to a dead process by construction; within a JVM the permit prevents a second turn. Deletes the stamp, the holder check, dead-holder takeover, holder-only close |
| agent run pointer | DERIVE: the agent's turn with no closed-at | its busy-fence role is the permit's |
| run `situation` | DELETE | generated runs are gone; every turn is a model call |
| run `plan-digest` | DELETE | "frozen" = the turn has evaluation entities |
| run `forms` component list | DELETED (`caef3850e`) | evaluations point at the turn |
| run `undisposed-at` | DELETE | derived in the same transaction from the evaluations |
| run `interrupted-at` | DELETE from the turn | the stamp lives on the evaluation that was cut |
| context capture + contribution rows | DELETE | basis + profile reproduce them byte for byte (§5) |
| ai attempt rows | KEEP, written in the forms' commit | a paid call is a fact; its own commit is not |
| ai attempt `ordinal` | DELETE | counted before the write today |
| evaluation source, comment, ns, ordinal, author | KEEP | the history |
| evaluation terminal facts (value/missing, out, error, ms, ending-ns, print options) | KEEP | the history and the handles |
| evaluation `interrupted-at` | KEEP, written at boot | the one recovery stamp |
| evaluation `result-size` | KEEP only when the value is missing | size reached is the reason |
| agent `turns-left` | DERIVE — no counter | the bound is `max turns − turns whose basis is at or after the last wake from outside the agent` (today `bootstrap.clj:47-70`, `work.clj:378-437`); a human message resets it by being such a wake. Everything needed is `:t` |
| agent `cluster` | DELETE | the branch |
| `:seon.def/*` rows | DELETE | a defn is a program row; kept data is transacted; an atom is not a fact |
| gate counters | DELETE | derivable from the stored gate report |
| `:seon.render/units` | DELETE | the record's components in declared order |

**On resume, explicitly.** A crash mid-turn is never resumed — which is
already today's semantics: `:resume` is reachable only for a run this
process holds (`work.clj:13-15, 536-540`). At boot, every open turn is
closed and its unsettled evaluations stamped `interrupted-at`; a turn that
died with no reply is derived (closed, no reply) and needs no stamp. The
agent's next context shows the turn up to the cut, and the evaluation-level
`interrupted-at` MUST render. The model is never re-called because the turn's
basis was recorded at open, so the wake is answered; no form re-executes because nothing re-runs an
interrupted turn. What dies is the custody predicate — the process stamp,
`claim-call`'s takeover, `release-call`, holder-only close — not the
evaluate arm, which §7 keeps for turns this JVM opened.

**Writes per turn: three.** Open (the turn entity; its own `:t` is the
basis that answers every wake the context contained) → store the reply, the
attempts, and the forms as evaluation entities → store the results and
close. The middle write survives because without it a crash loses the paid
reply AND the record that side-effecting forms ran.

## 2. The agent record — every attribute with the need it serves

An entity IS its attributes. No kind stamp, no pointers to things a query can
derive, no counters a query can count.

| attribute | value | the need |
|---|---|---|
| `:seon.agent/id` | string, identity | lookup, handles, messaging by id |
| `:seon.agent/namespace` | ref → `:seon.ns` | the prompt line and where forms evaluate; NOT unique (many agents may share a namespace); stewardship is `:seon.ns/steward` on the namespace |
| `:my.plan/*` | the plan facts the agent already stores on itself | the agent stores them on purpose; no wrapper entity (astra: a wrapper duplicates existing ownership) |

That is the whole stored record: identity, namespace, plan. Everything
else the page or the prompt shows is a QUERY over turns and evaluations
(astra's angle: "turns consume wakes; evaluations belong to turns; the
record is a query over those facts"). No `:seon.agent/evals` back-edge:
`:seon.eval/turn` → `:seon.turn/agent` already says whose evaluation it is,
and a rendered one-hop value needs no stored ownership edge. The debug page
renders the record's declared keys, then the derived history, in declared
order.

Questioned and REMOVED from the record:

- **`:seon.def/*`** — a `defn` is a program row already (the install gate
  writes it); data the agent wants kept, it transacts; an atom's contents are
  not a fact. Deleted.
- **cluster attribute** — exactly one `:seon.cluster/name` entity exists per
  branch (probed); deleted, with ONE derivation "the cluster entity of this
  database value" replacing the eleven call sites, `render/request-profile`
  first (review S2).
- **`turns-left`** — derived (§1a). The loop keeps track of turns by
  counting them, not by remembering a number. The two reviews split here
  (Opus: derive; astra: keep one explicit allowance because "turns since the
  last outside wake" is a heuristic, not a grant model). Decision: derive,
  as the prototype lane measures it; if a grant model is ever wanted, it is
  stored GRANTS and `remaining = granted − turns`, never a decrementing
  counter.
- **`:seon.agent/evals` component** — deleted (above).
- **process custody and the open-turn pointer** — both derivable (§1a):
  "mid-turn" is "my turn with no closed-at"; "dead" is "open at boot".
- **any collection of wakes on the agent** — a wake is a datom on a listened attribute whose value is the agent (§3); nothing is copied onto the record and nothing references the wake back.
- **`:seon.render/units`, reverse-ref units** — the record's components are
  the units, in the entity schema's declared order. Deleted.
- **run pointer, situation stamp, plan digest, generated runs, the form
  family, context captures, contribution rows, every derived-and-stored
  counter** — see §6.

## 3. Waking — listened attributes, Datahike `listen`, answered by `:t`

- **A listened attribute** is a ref attribute whose schema row carries
  `:seon.wake/listen true`; its value is the agent to wake. Today:
  `:seon.message/to`, `:seon.error/steward`, `:seon.schedule.firing/agent`
  (one firing entity per firing; a recurrence definition asserts no datom),
  and `:seon.effect/to` (an effect's settlement wakes the agent that
  requested it — in today's hand list, missing from earlier drafts).
  Declaring one is one schema property: attribute properties already reach
  schema rows through one general mechanism (prototype 0b.4), so "which
  attributes wake an agent" is one Datalog query over `:seon.wake/listen`,
  and `seon.cluster.wake/route!` derives its set and dispatch from it
  instead of two hand lists (`wake.clj:78-93`, `:232-250`).
- **A wake** is a datom asserted on a listened attribute. Datahike `listen`
  reports the transaction; `route!` offers one payload-free value into the
  agent's sliding-1 channel. The entity carrying the datom (a message, a
  fault, a schedule row) is ordinary data; nothing is copied, nothing is
  written back onto it.
- **Only a turn whose reply came from a model attempt answers wakes** (r9;
  r8 said "holds a reply", and the verifier showed a source submission
  stores a reply too): the query joins the turn to a `:seon.ai.attempt` that
  produced its reply. A turn that crashed before its reply, a turn whose
  attempts all failed, and a source submission (no attempt) never showed
  the wakes to a model and answer nothing.
  The wakes stay unanswered and the next turn opens; the turn bound (§1a)
  keeps that finite. Lane 2 landed `:seon.wake/inside` as the third
  property: the attributes that mark a wake as coming from inside the agent
  (its own messages, its own effects), so "the last wake from outside the
  agent" is a query, not a hand-coded `from`/`about` pair. Schedule firings
  are the existing `seon.schedule.fire` family (one immutable entity per
  nominal instant), declared `:seon.wake/opens-turn? false`.
- **Answered is derived from `:t`, nothing is stored.** Every datom carries
  its transaction `:t`; the turn's own `:t` is the basis it projected from
  (the context is projected on the opening transaction's database,
  `loop.clj:1375`, and the prototype reproduced a stored prompt exactly at
  that `:t`). A wake is answered iff a turn of that agent has `:t ≥` the
  wake's `:t`. A wake datom is asserted once and never retracted-and-
  reasserted (prototype 0b.2). Unanswered wakes are
  the datoms on listened attributes for this agent with `:t` greater than
  the agent's latest turn's `:t`:

  ```clojure
  [:find ?wake :in $ ?agent :where
   [?attr :seon.wake/listen true] [?attr :db/ident ?a]
   [?wake ?a ?agent ?tx] [(> ?tx ?latest-basis)]]
  ```

  Two wakes in one transaction are one turn (both `≤ basis`). A wake
  asserted mid-turn has `:t > basis` and opens the next turn. No reference
  from turn to wake, no claim, no per-wake write — the reviewers' B1, B3
  and B6 dissolve rather than get fixed.
- **Whether a listened attribute opens a turn** is the property
  `:seon.wake/opens-turn?`; one declared false only surfaces in the next
  context (a schedule tick, a notice) — and it must reach the derivation:
  `unanswered-wakes` for OPENING binds the turn-opening set, the context
  walk binds the whole listened set (verifier blocker 8: today both bind
  the opening set).
- **The bound's derivation is O(turns), never O(all wakes)** (verifier
  blocker 9: 47.5 ms per pass at 2,008 lifetime wakes vs 0.163 ms before).
  The latest outside wake is found by scanning the listened attributes'
  datoms for the agent through Datahike's own index (`d/datoms :avet`) and
  taking the max `:t`, bounded ≤ 1 ms at 10,000 wakes; if that cannot be
  met, the turn that answers an outside wake records that wake's `:t` once
  (one fact, written at open, derived at the writer — never a counter).
- **An empty derived set fails CLOSED**: no listened attributes ⇒ no wake
  can open a turn and the bound does not refill (verifier: an empty
  `inside` set refilled the bound — fail-open on a paid loop). `route!`
  re-derives its set when a transaction asserts `:seon.wake/*` on a schema
  row (today it freezes the set at registration).

A fault in an agent's own code wakes that agent through `:seon.error/steward`
like any other wake; the turn bound (§1a) is what stops it looping. The
per-item escalation guard at `loop.clj:682-696` is deleted with the mailing
mechanism it guarded. Both relations on a fault are declared:
`:seon.error/agent` (whom it happened to) and `:seon.error/steward` (whom it
is routed to: function → namespace → `:seon.ns/steward`, computed inside
the committing transaction).

### 3a. REPL evidence (live `juniper-context`, 2026-09-07 evening, read-only)

Probe: every `:seon.cluster.message/to` datom for Juniper with its `:t`,
every run's opening `:t`, and today's `:seon.cluster.run/trigger` refs.

| | value |
|---|---|
| wakes (eid, `:t`) | `[33893 536870941] [33900 536870942] [33901 536870942]` |
| turn openings `:t` | `536870941`, `536870954`, `536870987` |
| answered by trigger ref today | `#{33893 33900 33901}` |
| answered by `:t ≤ latest turn :t` | `#{33893 33900 33901}` |
| unanswered by `:t` | `#{}` |

Identical sets, including the wake asserted in the SAME transaction as the
turn that answered it (`33893`, `:t` equal: `≤` is the right comparison).
Two wakes in one transaction (`33900`, `33901`) are one answer.

## 4. The turn — what is stored, and why each thing

A turn groups an ordered set of evaluations, names what woke it, and records
what the model call cost. Every attribute questioned:

| attribute | why stored |
|---|---|
| `:seon.turn/id` | identity for evaluations and attempts to reference |
| `:seon.turn/agent` | ref; whose turn |
| `:seon.turn/opened-at`, `/closed-at` | the bound; open = no closed-at |
| (no basis attribute) | the turn's own transaction `:t` — the `:t` on its identity datom — is the basis: Datahike's `as-of` input, and the fact that answers every wake with `:t ≤` it (§3). "Why this turn" is the query for wakes between the previous turn's `:t` and this one's |
| `:seon.turn/reply` (+ blob over the bound; `reply-missing` when the blob was reclaimed) | the model's bytes, under the storage bound; stored before evaluation; a reclaimed blob is marked like a missing value |
| `:seon.turn/attempts` | component set of `:seon.ai.attempt` entities — the AI owner's existing family, referenced, not re-homed (review F6); one per attempt; a paid call is a fact |

Not in this schema, by design: session curation's superseding ref
(`[TARGET]` ruled 2026-08-04, today spelled `:seon.cluster.run/supersedes`)
is an ACCRETION onto the turn when that mechanism lands — one ref, added
then, declared then. The docs lane (2026-09-08) noted the gap; this is the
answer.

Questioned and removed from the turn: the prompt text and per-segment
contribution rows (derivable from basis + profile — and the whole point of
byte-identical projection); `interrupted-at` on the turn (it lives on the
evaluation that was cut); the situation stamp; the plan digest; the forms
list (evaluations point at the turn).

**A turn can end without evaluations** (astra B2): a crash after open and
before the reply, a provider failure, an empty or unreadable reply. The
terminal owner is one function: it closes the turn with its attempts (each
carrying its error) and, when the reply was never stored, the turn is
derivably interrupted (closed, no reply, no attempt error) — the
zero-evaluation recovery proof (`recovery-marks-a-run-that-settled-no-receipt`)
points at that derivation. No stamp on the turn.

**One open turn per agent is a fence in the writer** (astra B3): `open`
refuses when the agent already has a turn with no `closed-at`, decided
inside the `:db.fn/call` by query — the in-memory permit is not the
writer's fence, because `submit-source!` opens a turn without the permit.
System-authored turns stay: a submitted source is a turn with a reply and
no attempts, so `author` is derived (no attempts = system), not stored.

**A refused terminal transaction closes the turn with failure outcomes in
that same refusal path** (astra B4; today `settle-batch-refusal!`,
`loop.clj:730-775`). Without that, re-entering `:evaluate` would execute
the same side effects again before the identity refused the second
settlement. "Also crash resume" is struck from §7: the evaluate arm handles
only a turn this pass opened.

**A schedule firing is its own entity** (astra B6): `:seon.schedule/*` is a
recurrence definition; each firing transacts one entity with
`:seon.schedule.firing/agent` (the listened attribute), so every firing is a
new datom with its own `:t`. One wake entity addresses one agent.

**One database value per pure derivation, not per turn** (astra B7): a form
may transact and its next form may read that transaction, so evaluation
reads the connection's current value between forms as it does today
(`loop.clj:1538-1576`). The "20 of 21 reads" line is struck; what is deleted
is every re-derivation of a value the pass already holds.

An **evaluation** entity, accreted into the EXISTING `seon.eval` family
(which already holds the gauges `duration-ms`, `allocated-bytes`,
`fn-entries`, `host-interop-count`; its `outcome` enum is deleted as
derivable from `error`/`interrupted-at`/`missing`): `id` (`:db.unique/identity`
over (turn, ordinal) — the structural nothing-re-executes fence, review S1),
`turn`, `ordinal`, `comment`, `source`, `ns`; then, once run: `value` under
the storage bound OR `missing` (why: over-bound, unserializable, lost) with
`size`, `out`, `error` + `triage-edn`, `ending-ns` when changed,
`duration-ms`, `:seon.print/length`/`level` in effect; `interrupted-at` when
boot cut it. No terminal fact = not yet evaluated. `author` is deleted:
generated runs and the page's private evaluation were its producers, and
both are gone; a system evaluation is one whose turn has no reply.

**The other fourteen run attributes** (`live-processes`, `background-results`,
`supersedes`, `missing-results`, `starting-ns`, `error`, `rule`,
`transition`, `refused`, `turns-remaining`, and the five request maps) are
DELETED. `starting-ns` is the agent's namespace; `error`/`rule`/`transition`/
`refused` become `:seon.error` facts referencing the turn; the request maps
are in-memory shapes, not stored attributes. A lane that finds a live
reader names it in its landing note before keeping it.

## 4a. The schema, complete — every key namespaced, every shape declared

Owner rule (2026-09-07): "every piece of data should have a fully namespaced
key and a malli schema. We are moving the data to the agent's entity so we
have a chance to redesign and rename everything so it makes sense and is
optimal." This section IS the proposed `resources/seon/schemas/` population
for the record; reviewers judge these keys and shapes, not the prose. Legacy
spellings (`seon.cluster.run`, `seon.cluster.eval`, `seon.cluster.message`,
`seon.cluster.agent`) retire in the same wave; names below take the
dependency's word where one exists (Datahike ref/component, sci ns, Clojure
ns/symbol) and coin nothing that a query could express.

```clojure
;; seon.agent.edn — the record
#:seon.agent{:id        [:string {:seon.db/identity true
                                  :description "The agent's stable identity; handles, messages, and stewardship name it."}]
             :namespace [:seon.db/ref {:description "The :seon.ns entity whose REPL this agent sits in. Not unique: agents may share a namespace; stewardship is :seon.ns/steward."}]
             :agent      [:map {:seon.db/attributes true
                                :seon.render/ai seon.agent/render-ai
                                :seon.render/html seon.agent/render-html}
                          [:seon.agent/id :seon.agent/id]
                          [:seon.agent/namespace :seon.agent/namespace]
                          [:my.plan/steps {:optional true} :my.plan/steps]
                          [:my.plan/current-step {:optional true} :my.plan/current-step]]}

;; seon.turn.edn — one model call and what it produced
#:seon.turn{:id        [:string {:seon.db/identity true :description "Identity for evaluations and attempts to reference."}]
            :agent     [:seon.db/ref {:description "Whose turn."}]
            :opened-at [:inst {:description "When the turn opened; open = no closed-at."}]
            :closed-at [:inst {:description "When the turn settled or was closed as interrupted at boot."}]
            :reply     [:string {:description "The model's reply under the storage bound; storing the forms evidence."}]
            :reply-blob [:seon.blob/digest {:description "The reply's blob when it exceeds the inline bound."}]
            :reply-missing [:enum {:description "Why no reply text is available: the blob was reclaimed."} :lost]
            :attempts  [:set {:seon.db/component true :description "One :seon.ai.attempt entity per provider attempt."} :seon.db/ref]
            :turn      [:map {:seon.db/attributes true}
                        [:seon.turn/id :seon.turn/id]
                        [:seon.turn/agent :seon.turn/agent]
                        [:seon.turn/opened-at :seon.turn/opened-at]
                        [:seon.turn/closed-at {:optional true} :seon.turn/closed-at]
                        [:seon.turn/reply {:optional true} :seon.turn/reply]
                        [:seon.turn/reply-blob {:optional true} :seon.turn/reply-blob]
                        [:seon.turn/reply-missing {:optional true} :seon.turn/reply-missing]
                        [:seon.turn/attempts {:optional true} :seon.turn/attempts]]}

;; Every family below carries its own [:map {:seon.db/attributes true} …] entity schema so the
;; population INSTALLS it (astra B1: a component ref does not declare its children; leaves need the
;; map or an explicit declaration property; seon.schema.form admits map entries).
;; :seon.ai.attempt/* stays in the AI owner's namespace (resources/seon/schemas/seon.ai.edn); the turn references it.
;; It loses :ordinal and gains :prompt-digest [:seon.blob/digest] — the digest of the projected prompt bytes, the byte-identity witness.

;; seon.eval.edn — ACCRETED into the existing family (duration-ms, allocated-bytes, fn-entries, host-interop-count stay; outcome is deleted as derivable)
#:seon.eval{:id      [:string {:seon.db/identity true :description "(turn id, ordinal) as one string: the structural nothing-re-executes fence — a second freeze of the same ordinal upserts, never duplicates."}]
            :turn :seon.db/ref
            :ordinal [:int {:min 0 :description "Position in the turn's reply."}]
            :comment [:string {:description "The agent's prose above the form, verbatim."}]
            :source  [:string {:description "The form's exact source text."}]
            :ns      [:seon.db/ref {:description "The namespace in effect when the form was read."}]
            :ending-ns [:seon.db/ref {:description "Present only when the form changed the namespace."}]
            :value   [:string {:description "The result as EDN print node under the storage bound."}]
            :value-blob :seon.blob/digest
            :missing [:enum {:description "Why no value is stored: over the storage bound, not serializable, or lost at recovery."} :over-bound :unserializable :lost]
            :size    [:int {:min 0 :description "Bytes reached when the value is missing; the reason's evidence."}]
            :out     [:string {:description "Captured *out* text."}]
            :error   [:string {:description "The thrown message."}]
            :triage-edn [:string {:description "clojure.main/ex-triage data as EDN; the suffix names the encoding."}]
            ;; :seon.eval/duration-ms already exists; :seon.print/length and :seon.print/level are the print owner's keys and are reused, not re-coined
            :interrupted-at [:inst {:description "Asserted at boot on an evaluation with no terminal fact whose turn was open."}]
            :eval [:map {:seon.db/attributes true :seon.render/ai seon.repl/render-ai :seon.render/html seon.repl/render-html}
                   [:seon.eval/id :seon.eval/id] [:seon.eval/turn :seon.eval/turn] [:seon.eval/ordinal :seon.eval/ordinal]
                   [:seon.eval/source :seon.eval/source] [:seon.eval/ns :seon.eval/ns]
                   [:seon.eval/comment {:optional true} :seon.eval/comment]
                   [:seon.eval/ending-ns {:optional true} :seon.eval/ending-ns]
                   [:seon.eval/value {:optional true} :seon.eval/value] [:seon.eval/value-blob {:optional true} :seon.eval/value-blob]
                   [:seon.eval/missing {:optional true} :seon.eval/missing] [:seon.eval/size {:optional true} :seon.eval/size]
                   [:seon.eval/out {:optional true} :seon.eval/out] [:seon.eval/error {:optional true} :seon.eval/error]
                   [:seon.eval/triage-edn {:optional true} :seon.eval/triage-edn] [:seon.eval/duration-ms {:optional true} :seon.eval/duration-ms]
                   [:seon.print/length {:optional true} :seon.print/length] [:seon.print/level {:optional true} :seon.print/level]
                   [:seon.eval/interrupted-at {:optional true} :seon.eval/interrupted-at]]}

;; seon.schedule.edn — accretion: each firing is an entity (astra B6)
#:seon.schedule.firing{:schedule :seon.db/ref
                       :at :inst
                       :agent [:seon.db/ref {:seon.wake/listen true :seon.wake/opens-turn? true :description "The agent this firing wakes; one firing, one agent, one datom with its own :t."}]
                       :firing [:map {:seon.db/attributes true} [:seon.schedule.firing/schedule :seon.schedule.firing/schedule] [:seon.schedule.firing/at :seon.schedule.firing/at] [:seon.schedule.firing/agent :seon.schedule.firing/agent]]}

;; seon.wake.edn — the mechanism (§3). Both are schema PROPERTIES lifted onto the schema ROW as facts (review S3), so "which attributes wake an agent" is a Datalog query.
#:seon.wake{:listen [:boolean {:description "On a ref attribute: Datahike listen reports its assertions and the referenced agent is woken; answered is derived from :t."}]
            :opens-turn? [:boolean {:description "Whether an unanswered wake on this attribute opens a turn or only surfaces in context."}]}

;; seon.error.edn — two relations, both declared
#:seon.error{:agent   [:seon.db/ref {:description "The agent this fault happened to (exists today)."}]
             :steward [:seon.db/ref {:seon.wake/listen true :seon.wake/opens-turn? true
                                     :description "The steward routed to fix it: function → namespace → :seon.ns/steward, computed inside the committing transaction."}]}
```

Listened attributes declared where they live: `:seon.message/to` (renamed
from `seon.cluster.message`), `:seon.error/steward`, `:seon.schedule.firing/agent` each
carry `{:seon.wake/listen true :seon.wake/opens-turn? true}`. `:seon.ns/steward`
(exists, `seon.ns.edn`) is the routing fact they derive from.

Every key above is a full namespaced attribute with a Malli shape; absent
means no key; no `:any`, no `[:maybe]`, no `:kind`. Reviewers: is any key
misnamed against Clojure's or the dependency's own word? Is any shape
incomplete?

## 5. Storage bound, elision, and the missing marker

- **One storage bound per value** (`:seon.config.eval.result/max-bytes`),
  enforced as STREAMING serialization under the evaluation's own SCI
  interrupt (astra B8: a lazy sequence can block before its first byte, and
  admission already checks the interrupt at every node, `admit.clj:395-410`;
  a byte count on a finished string is not an execution bound). Under it the
  value is stored faithfully; over it, or unserializable, `:seon.eval/missing`
  names the reason and the size reached. Identity-only admission of
  references stays (`admit.clj:162-171`). The display caps (depth,
  collection, string, node budget) are deleted.
- **Elision happens once**, at AI context generation, from the stored value,
  under the render profile, with requery forms into the stored value — IN
  THE AI RENDER FUNCTIONS AND THE VALUE RENDERER ONLY (owner, 2026-09-08:
  no clipping at the walk, the transcript, a render terminal, or a request
  seam; the repair lanes had introduced three such spots, being collapsed).
  HTML renders the stored value without limits.
- **A missing value ablates the handle**: no `:seon.repl/result` key, and
  `:seon.repl/value` says the result is unavailable and why. A later form
  naming a dead handle gets an ordinary unresolved-symbol error.
- **Byte identity is the regression**, qualified exactly: same database
  value (`as-of` the turn's own `:t`), same adopted program commit, same
  render profile (which includes `:seon.render/distance`, prototype 0b.6)
  ⇒ same bytes — REPL-proven on a 345,439-character capture; the bytes' digest is `:seon.ai.attempt/prompt-digest`.
  Two things make it unreachable today and are in scope (review B5): a
  render producer runs under a wall-clock time limit (`render.clj:725-756`)
  and its refusal contributes ABSENCE (`walk.clj:764-766`). A refused render
  contributes a stable typed unknown naming the producer, so the bound can
  fire without moving a byte. The walk's own truncation of connections
  (`walk.clj:208, 249-268`) is a QUERY-WORK bound at the pull, reported as
  its own elision naming the bound; it is not presentation and never reads
  the render profile (owner, 2026-09-08: presentation clips in the AI render
  functions and the value renderer only — the r4 wording that moved the
  walk's cut "under the render profile" is withdrawn). Result
  handles embed entity ids, so identity holds within one store, not across a
  reset (review, non-determinism 7).

## 6. What this deletes

`:seon.cluster.work/situation` and the generate arm, `generation-complete-*`,
`append-generated-call`, generated runs and `bootstrap/seed-tx`'s run (the
opening is the projection), `:seon.cluster.run` as a family (renamed to the
turn with the attributes in §4 only), the run pointer and
`::agent-pointer-broken`, the process custody stamp with `claim-call`'s
takeover and `release-call`, the resume arm, `:seon.context.capture` and
`:seon.context.contribution`, `plan-digest`, `undisposed-at`, the gate
counters, `:seon.ai.attempt/ordinal`, `:seon.def/*`, `:seon.render/units`,
`:seon.eval/outcome`, `:seon.eval/author` (derived: no attempts = system), the `:seon.agent/evals` and `:seon.agent/plan` wrappers, `:seon.cluster.run/trigger` (answered by `:t`), the two hand lists of the wake
set (`wake.clj:78-93`, `:232-250`) and the render-property hand list
(`schema.clj:1419-1420`), the admission caps, `bind-stored-results!`'s windowed ambiguity, and 20 of
the loop's 21 connection reads (one database value enters a pass).

## 7. The loop code

```
(defn step [db agent]                                   ; pure
  (cond
    (open-turn-with-unsettled-evals db agent) :evaluate  ; only a turn this pass opened; boot closes the rest
    (and (unanswered-wake db agent) (turns-left? db agent)) :reply
    :else                                            :idle))
```

`:reply` = open (record the basis `:t`) → project → model → store reply +
attempts + evaluations (one commit) → fall into `:evaluate`. `:evaluate` = fork → evaluate each unsettled ordinal →
settle the batch and close the turn (one commit). Commits per model turn:
three (today 5–6). Boot: close every open turn, stamping its unsettled
evaluations interrupted — total, never refusing. Fences kept, all `:db.fn/call` deciding by query inside the transaction: one open turn per agent (open refuses); store results once; a refused terminal transaction closes the turn with failure outcomes;
one evaluation per (turn, ordinal) as the declared `:seon.eval/id`
identity — a declaration, not a transaction function (review S1). Deleted
fences: claim/takeover, release, holder-only close, pointer coherence, the
receipt-exists call.

## 8. Order of work (reset the dev database at each schema step)

1. Storage bound + missing marker + AI-boundary elision (`seon.sci.admit`,
   `seon.print/fit`, `seon.repl`).
2. Listened attributes: declare `:seon.wake/listen` on the four attributes
   (message `to`, error `steward`, schedule firing `agent`, effect `to`);
   derive `route!`'s set and dispatch from one query; answered by `:t`
   replaces the trigger ref and `opening-commit-id`; the steward computed
   inside the fault committer's transaction; delete message `to` reverse
   units and the agent-id wake.
3. The turn: rename/trim `seon.cluster.run` to §4; delete generated runs,
   situation, captures, `:seon.cluster.run/trigger` and `opening-commit-id`
   (lane 2 left them as provenance because seven readers live in the turn's
   files); the two-arm loop; the reply-holding qualification above; prove
   the refused-settlement close (unproven by the verifier); one db value per
   derivation.
4. The record: `:seon.agent/*` keys, delete `:seon.def`, cluster, pointer,
   units; page renders the record's components in declared order.
5. Byte identity: a refused render is a stable typed unknown; the walk's
   truncation under the render profile; `prompt-digest` on the attempt.
6. Reset, reseed, byte-identity regression, docs and vocabulary.

## 8a. The generated opening, answered

A fresh agent's first context is the walk over its record with each unit's
`:seon.render/ai` source EXECUTED in the turn's fork at projection time —
the debug-units model that already works on the page. The executed forms and
their printed values are prompt bytes, not stored evaluations; nothing about
the opening is transacted. The two proofs that die with generated runs
(`generated-system-runs-grow-only-after-their-settled-prefix`,
`a-generated-run-resumes-then-requests-one-more-form`) proved append-only
prefix growth of a mechanism that no longer exists.

## 9. Questions for the independent reviewers

1. Is anything in §2 or §4 stored that a query could derive? Is anything a
   crash needs missing?
2. Does §3 really let "other things" wake an agent with no loop change? What
   breaks when two sources fire in one transaction?
3. Is the two-write turn (freeze, then settle) the minimum, or can storing the forms
   ride the open?
4. `turns-left` as a stored counter: is it the one legitimate counter, or
   should the bound be derived (turns since the last human message)?
5. Byte identity: what in today's projection is non-deterministic (time,
   hash order, entity ids in text)?
6. Where is this still overly complicated?
7. §1a: is every KEEP genuinely needed, and is every DERIVE/DELETE safe?
   Name the test in run-loop-unpacked §5.6 each deletion breaks and say
   whether the behaviour it proved still matters under "no resume".
8. Is "no resume" right? What is lost when a crash cuts a turn after its
   forms transacted side effects, and is storing the forms before evaluating
   still worth one commit — or could reply + results be one write?
10. §4 terminal owner: is one function for reply failure, reader failure,
    provider failure, and evaluation refusal genuinely one, or four arms in
    a trench coat? Show its `case`.
9. Answered-by-`:t` (§3): is there any case where a wake with `:t ≤ basis`
   was NOT in the projected context, or one with `:t > basis` was? (History
   attributes, `noHistory`, a wake on the agent's own transaction.)

## 10. Lane rules for this wave (the one place; every spec cites this section)

Owner rulings 2026-09-07/08, collected so no two lanes read different rules:

0. **You are a hyper-competent principal engineer.** Do the right thing
   quickly; do not fuss over detail a later revision will erase. Obsess over
   two things: correctness, and that the tests test the right thing — on the
   SAME harness the codebase runs on (canonical fixtures, real database,
   real SCI fork, armed contracts), never a mocked or hand-rostered
   stand-in. A misrepresented harness produces garbage code that passes.
1. **Gate = `bin/test --paths <your own files…> <namespaces…>`** — it
   snapshots HEAD and overlays ONLY the paths you name, so no other lane's
   in-flight edit can block you (landed `e33a887fe`); bare `bin/test`
   selects by `:seon.fn/calls` reach when you are alone; **plus
   `bin/test --platform` green.** Foreign breakage is never a reason to
   stop. NEVER
   `--all` or `--full` in a lane — full suites are the orchestrator's
   integration checkpoints only. "It's a waste of time to run the entire
   test suite for every change."
2. **The gate is instrumented**: every regression runs under the same
   contracts a cluster arms. A test that passes only unarmed is a defect.
3. **One clipping spot**: presentation elision happens in the AI render
   functions and the value renderer only; storage is bounded by `max-bytes`
   only; HTML never clips. A spec or diff adding a `fit`/`elision` call
   anywhere else is wrong on sight.
4. **Protected = concurrently edited only.** A path is protected while
   another lane holds uncommitted edits in it; otherwise fix every root
   cause wherever it lives and list every file touched. Never revert or
   restore a shared file; baseline in a throwaway worktree
   (`git worktree add tmp/<lane>-wt HEAD` + link `reference-code`).
5. **Commits are the heartbeat**: path-limited (`git commit --only -- …`),
   one coherent slice each; never `git add -A`, `reset --hard`, `checkout --`.
6. **Background hygiene**: never poll with `pgrep -f` on your own command
   line; run awaited commands in the background; end every shell and delete
   scratch roots/worktrees before reporting.
7. **Words**: verify / falsify / probe — never adversarial verbs. The
   retired spellings in §0a are never written.
8. **The default cluster IS the development environment** (main root,
   `bin/seon start`, MCP with no root argument): the edit hook keeps it
   current on every edit and a lane verifies there. On "predates the
   incompatible schema change" the lane reforks it itself at once
   (`bin/seon stop default; bin/seon init default --force; bin/seon start;
   bin/seon init --dev default`, reseed the Juniper fixture) — never waits.
   Scratch clusters are for destructive drills only (owner, 2026-09-08).
9. **Default lane agent**: `bin/codex-agent` on `gpt-6-astra` at `low`
   effort; raise effort only for design review.
10. **Landing note** under `docs/prds/context-generation/research/`, dated,
    with exact bytes and measured numbers; issues under `docs/seon/issues/`
    for anything out of scope; never a finding left in chat.

## 11. Where the code actually is (2026-09-08 midday, measured)

| retired thing | references in `src/` | status |
|---|---|---|
| generated opening runs, `situation` | 25 | present |
| the process attribute on a run, takeover, release, holder-only close | 74 | present |
| the resume arm | — | present |
| `:seon.context.capture` + `contribution` (a stored copy of the prompt) | 95 | present |
| `:seon.def/*` (stored defs, rehydrated each turn) | 66 | present — see the open question below |
| `run/trigger`, `opening-commit-id`, `plan-digest`, `undisposed-at` | 14, 19, … | present, decide nothing |
| `:seon.render/units`, reverse-ref attributes, `agent/cluster`, instructions | 1, 11 | present |
| presentation clipping outside the value renderer | 0 | DONE `9248692d6` |
| `seon.cluster.*` → `seon.turn/agent/message/wake` rename | — | not started |

Open question for the owner (2026-09-08): deleting `:seon.def/*` storage
means each agent keeps ONE live SCI context in the JVM across turns
(forked once from the base) instead of a fresh fork per turn; a restart
loses defs (history shows they were made). Today's fresh-fork-per-turn
model cannot keep an unstored def alive. Decide before the def family is
cut.

Method from here: the orchestrator cuts the table above directly, one row
per commit, leaving a one-line typed refusal where a live caller remains
(`:seon.error/kind :seon/retired`, naming this PRD) so a mechanism that
still needs to exist announces itself on the live cluster instead of in
argument.

## 12. Proof standard for the loop (owner, 2026-09-08)

No live model turns and no context submitted to a provider while the record
is being ported. The loop is proven by VIRTUAL TURNS: N turns through the
ordinary per-agent proc, each reply a fixture of no-op forms, nothing else
running between turns. Required: three transactions per turn; the SCI
context computed correctly and ISOLATED — agent A's def visible in A's next
turn and never in B's, never in the base context; handles bind per agent; a
third agent created mid-run sees neither ("don't cross the streams"); boot
closes an open run and the next virtual turn proceeds; two projections of
one agent's context are byte-identical.

The SCI model that makes this true (owner, same day): the cluster's BASE
context is shared and accretes every installed program row — a `defn`
through the install gate, a schema through `register!`, a test — so agents
pick up what other agents install on their next turn. Each turn takes a
fresh `sci/fork` of the CURRENT base and lays the agent's PRIVATE layer on
top: its defs of data and its atoms, a per-agent in-memory value the proc
holds, never stored, lost on restart. A private def never reaches the base
or another agent; an installed defn reaches everyone. Both directions are
regressions. This supersedes §2's "`:seon.def` deleted … kept data is
transacted" only in mechanism: the storage is still deleted.

Ruled 2026-09-08 ("yes stop serializing. Store it in memory"): the private
layer holds the agent's defs and atoms as the OBJECTS THEMSELVES in the
agent proc's state, never an EDN serialization — an atom keeps its identity
across turns, and the `:seon.def` rows (which could not hold an atom at all)
are deleted with their serializer.

### The def note (owner-approved bytes, 2026-09-08)

Every response to a top-level `def` (not `defn`, `defschema`, `deftest`,
which persist as program rows) carries `:seon.repl/note`, placed after
`:seon.repl/out` in the response order, one datum, never comment-shaped:

```
#:seon.repl{:value #'my.agents.juniper/x, :result result/e34291, :note "x lives only in your SCI context and is lost when the JVM restarts. Nothing defined with def is persisted, atoms included; they are for temporary data-modeling experiments. To keep something, write a function, schema, or test, or transact the data into the database."}
```

The text, with the var's name substituted: "`<name>` lives only in your SCI
context and is lost when the JVM restarts. Nothing defined with def is
persisted, atoms included; they are for temporary data-modeling
experiments. To keep something, write a function, schema, or test, or
transact the data into the database." Declared in `seon.repl.edn` as
`:seon.repl/note :string`; written by the one generator `seon.repl` from
the evaluation's read evidence (the form's head symbol), never by the loop.

### Functions without contracts are refused (owner, 2026-09-08)

"Don't allow functions without malli contracts." A `defn` an agent evaluates
with no `:malli/schema` is NOT installed: no program row is written, the
var does not enter the base context, and the response says so in one
sentence (`:seon.repl/note`): "`<name>` was not installed: every function
needs a `:malli/schema` contract to become part of the program." Today the
install gate skips a contract-less function while settlement still writes
its program row, and development adoption then refuses the whole cluster
(measured on `default`, 2026-09-08). The refusal moves to the one seam —
the install gate — and adoption never sees such a row.

### Blocks: one per concern (owner, 2026-09-08)

Context blocks are self-contained and succinct — never one block per
scalar. A scalar attribute renders inside its entity's block. A block is
either the entity's OWN render function covering its scalars (the agent's
identity: id, namespace, its steward, in one short paragraph of comment +
forms) or one component/derived value with its own producers: the plan
tree, the history (turns newest first, evaluations through `seon.repl/text`),
unanswered wakes, faults routed to me as steward. Five blocks for an agent.

## 13. Storing and rendering data (owner, 2026-09-08)

Storage: an entity has scalars (facts many things need by themselves: id,
namespace) and components (one ref to an entity holding a whole concern:
the plan; a turn with its reply, attempts, evaluations). Derived concerns
(history, unanswered wakes, faults routed to me) are queries, never stored
on the record.

Rendering: ONE render pair per entity schema, never per attribute; a block
is one entity rendered by its schema's `:seon.render/ai` and `/html`. The
record renders as blocks in declared order: the entity's own block (its
scalars in one paragraph), each component's block, then the derived blocks
the agent schema declares once by naming their query functions. Every value
inside a block prints through the one value renderer (the only elision).
No pair declared ⇒ the default printer renders the attribute map.

Teaching: no separate teaching prose. `(dir ns)` returns DATA from program
rows — per public function: sym, arglists, the docstring's first line, and
the contract's input/output — and `(doc sym)` returns one function's full
docstring and contract. Every `my.*` read returns small maps with the
item's own namespaced keys; every write returns the entity it changed.

THE RENDER FUNCTION IS A FUNCTION OF THE DATA and chooses its forms: an
empty plan emits a comment saying there is none, `(dir my.plan)` and
`(doc my.plan/add!)` executed so the agent sees the shape to write; a
non-empty plan emits `(my.plan/current)`, `(my.plan/ready)`,
`(my.plan/blocked)` and one comment pointing at `dir`; a first turn's
history says it is the first; a block with nothing to say is absent.

The plan block, exact target bytes (non-empty case):

```
; Your plan. (dir my.plan) is its API; (doc my.plan/complete!) explains one form.
my.agents.juniper=> (my.plan/current)
#:seon.repl{:value #:my.plan.item{:id "juniper/render-plan", :title "Render this plan clearly", :done-when "…"}}
my.agents.juniper=> (my.plan/ready)
#:seon.repl{:value [#:my.plan.item{:id "juniper/render-plan", :title "Render this plan clearly"}]}
my.agents.juniper=> (my.plan/blocked)
#:seon.repl{:value [#:my.plan.item{:id "juniper/compare-changed-results", :title "Compare refreshed results", :needs ["juniper/render-plan"]}]}
```

### History (owner, 2026-09-08)

ALL turns are shown by default; no clipping in the history "until we get
shit under control". Order in the prompt: the record's own block, the
plan, unanswered wakes, faults routed to me, then the history LAST — the
agent's turns are the tail before it continues. Within the history:
chronological, oldest first, the newest turn last. Each evaluation through
`seon.repl/text`; nothing else formats it.

## 14. Additive context: the prompt and the SCI context only grow (owner, 2026-09-08)

"We shouldn't overwrite the previous context as that breaks caching … unless
we wipe the data and do a compaction it is an additive process." Provider
prompt caching works on a stable prefix, so:

- **The transcript IS the prompt, and it only grows.** Generated context is
  never re-rendered at the top. Generated forms are EXECUTED AND STORED as
  evaluations on the agent's record like the agent's own forms, in one
  additive sequence: a SYSTEM TURN 0 (identity, plan, `dir`s — the opening),
  then agent turn 1, then a system turn holding what changed since (new
  wakes, plan changes, the `since-last` query), then agent turn 2, and so
  on. Every prompt is the stored evaluations in order through
  `seon.repl/text`; bytes already sent never change; only the tail is new.
- **A system turn is an ordinary turn with a reply and no provider
  attempt** (the source-submission shape). "System" is derived, never
  stamped. A system turn holding wakes' results answers those wakes under
  the `:t` rule. The old generated-opening machinery does not return: a
  system turn evaluates its forms like any turn.
- **Before each agent turn the loop runs the system turn for what
  changed**; nothing changed ⇒ no system turn. This is "render the diff as
  if the agent queried it before its turn started".
- **Compaction = wipe and regenerate**: retract the agent's evaluations;
  the next system turn regenerates the opening from the current record. No
  manual curation anywhere; one algorithm, every turn, including the first.
- **The SCI context is additive too.** Each agent keeps ONE live context
  across its turns (its fork of the base + its private layer), never
  re-forked from scratch. When the base changes (a function installed by
  anyone, an adoption), the agent's context RECEIVES THE DIFF — the new or
  changed vars interned into it, the operation the install gate already
  performs for one function. Result handles accrete. A JVM restart starts
  the context empty and the history says what was there.
- **Byte identity becomes trivial** (the bytes are stored facts). The
  in-memory invocation cache serves the debug page's previews; `?prompt=true`
  projects the stored transcript plus the would-be system turn WITHOUT
  writing, for inspection. "Prompts are never stored" still holds: the
  evaluations are stored, the prompt is generated from them.

§8a ("the opening is a projection, nothing transacted") is superseded by
this section: the opening is system turn 0, stored.

**Everything, not the inbox** (owner, same day): for EVERY distinct read
form in the transcript — generated or agent-written; a form that reads
facts and neither transacts nor requests an effect — the loop looks at its
latest evaluation; if any fact its read evidence names changed since that
evaluation's `:t` (a `since` query), the system turn appends a fresh
evaluation of that same form. Writes and effects are never re-run. One
algorithm, no block-specific code: the cache validity check applied to the
whole transcript.

## 15. Results: objects in memory, shown text on disk (owner, 2026-09-08)

Supersedes §5's stored print node and the separate storage bound.

- **In memory**: each agent's live SCI context keeps a map from evaluation
  id to the ACTUAL result object — anything: an atom, a function, a
  channel, a lazy seq. `result/e<id>` binds to that object directly. No
  serialization, no admission walk, no rehydration; requery runs against
  the real value. Lives as long as the JVM.
- **On disk**: the evaluation entity stores the form and WHAT THE AGENT
  SAW — the text the value renderer produced at evaluation time, elided
  under the profile with its requery forms — plus out and error. A fact
  about what was shown, never a serialization of the value. It regenerates
  the prompt byte for byte after a restart because it IS the bytes.
- **Deleted**: the stored print node, `result-edn`, result blobs,
  `restorable-node`, `semantic-value`, `max-bytes` as a separate bound. The
  one bound is the value renderer's profile, applied once at evaluation
  time; the stored text inherits it. HTML renders the live object while it
  exists, the stored text after a restart.
- **Inspection**: `(my.turn/evals)` returns the agent's evaluations as
  maps (id, form, shown text, `:t`, error), filterable by turn or form;
  `(my.turn/eval id)` returns one in full including its read evidence;
  `result/e<id>` is the live object, and after a restart the map says the
  object is gone and the text remains. `dir` teaches the API.
- Accepted: a result too large to show is stored as its elided text with
  a requery form; after a restart that requery has nothing to reach.

Vocabulary: "transcript" is retired; the agent's evaluations are
`(seon.eval/of-agent db agent)` and their rendered block is the history.
`seon.render.transcript` is renamed with the history it renders.

### Result names are stable digests (owner, 2026-09-08; supersedes "random")

There is no short random id generator in the system and none is added. The
one derivation reused is the digest: `:seon.eval/id` is
`seon.schema/sha-256` over the evaluation's identity — the branch id, the
turn id, the ordinal — truncated to twelve hex characters the way
`seon.render.value/node-id` already truncates, declared
`:db.unique/identity`. A Clojure symbol cannot begin with a digit, so the
handle is the id with one leading letter: `result/e9c1b3f2a7d04`. Stable:
the same evaluation yields the same id after a refork of the same data;
unique across every cluster a JVM holds (48 bits); nothing minted, only
derived. (turn, ordinal) stays as the ORDER.

### The history is a render function like everything else (owner, 2026-09-08)

"NO HAND MADE SHIT. Turtles all the way down." The evaluation schema
declares its pair (`seon.repl/render-ai`, `render-html`); the history is the
walk rendering each of the agent's evaluation entities through that pair,
in order, from the STORED shown text — never re-running a form. HTML may
reach the live result object from the in-memory map while it exists. The
transcript namespace's hand-assembled entries, entry kinds, and any
history-specific formatting are deleted; nothing assembles the history but
the walk.



### Record-render integration boundary (2026-09-08)

Record-render stopped at a foreign shared-base preparation failure:
`seon.id/symbol-in`'s unregistered `:char` contract. Details and partial
commits are in
[the landing note](../research/record-render-landing-2026-09-08.md).
This records implementation dependencies, not a change to the owner rulings:

- The protected SCI injection seam (`program-documentation`,
  `program-doc-var`, `program-dir-var`, `install-program-doc!` in
  `src/seon/sci/eval.clj`) must hand back data instead of printing its old
  documentation lines. Record-render did not edit that seam.
- Replacing the old history namespace requires changing its protected
  bootstrap/run-schema consumers in the same integration. No compatibility
  namespace or duplicate history assembly should be introduced.
- The history walk and `my.turn` need the settled evaluation query
  `seon.eval/of-agent`, stored shown-text field, read-evidence shape, and
  live-object carrier from the evaluation owner. Those APIs were not yet
  present when this lane stopped; the lane did not invent a parallel store
  or rerun saved forms to stand in for them.

## 16. The debug page shows the algorithm (owner, 2026-09-08: "I want to see it in practice")

`/ns/{ns}/debug` for an agent is the one place §14's walk is visible in
every state, without running a model:

1. **State line**: the agent, how many evaluations it holds, the `:t` of its
   last turn, and which case the next walk is in — fresh (no evaluations)
   or continuing.
2. **Context now**: every evaluation of the agent in (turn `:t`, ordinal)
   order through `seon.repl/text` — exactly the prompt's stored prefix —
   with, per evaluation, the as-of check: the stored shown text rendered
   again at `as-of` its own `:t` equals the stored text (green) or not
   (red, the renderer stopped being a function of the data).
3. **Would-be system turn**: the walk computed NOW, writing nothing: for
   each declared block and each of its read forms — none / unchanged /
   changed since `:t` naming the facts that moved — and, for the forms that
   would run, their evaluated bytes. Fresh ⇒ every form; continuing ⇒ only
   the changed ones; nothing changed ⇒ "no system turn".
4. **`?prompt=true`** = context now + the would-be system turn = the exact
   bytes a provider would receive, with their digest.
5. **Three controls**, each a same-origin POST to the existing context
   route, after which the page repaints through the feed: **Run system
   turn** (store what §3 showed), **Virtual turn** (one turn through the
   ordinary loop with a fixture reply of no-op forms — no provider),
   **Compact** (retract the agent's evaluations; the next walk is fresh).
6. The HTML column stays as today, one block per concern.

Owned: the page and the controls by `record-render`; `seon.turn/system-turn`
(compute, with `:write? false` for the preview), `seon.turn/virtual-turn!`,
`seon.turn/compact!` by `turn-cut`. Both read this section as their spec.

## 17. Components: merge similar data (owner, 2026-09-08)

The record's scalars stay scalars (id, namespace: many things need them
alone). Everything else about one concern is ONE component entity, one
pull, one block, one render pair:

- `:seon.agent/plan` → one entity: `:my.plan/objective`, the step tree
  (`:my.plan/steps`, component), `:my.plan/current-step`. Today steps and
  current-step sit directly on the agent and the objective on the root
  step; they merge. `my.plan` reads and writes address this entity.
- `:seon.agent/settings` → one entity: the per-agent overlay — model,
  time limit, the turn bound, and every other `:seon.config.agent/*` dial
  — today config rows keyed off the agent elsewhere. `(my.agent/settings)`
  returns it; the block renders it once.
- The turn stays as it is: reply, attempts, evaluations under one entity.
- Messages and faults stay one entity each; no thread component yet.

Schema: each component is `[:seon.db/ref {:seon.db/component true}]` on the
agent map, with the component's own `[:map {:seon.db/attributes true
:seon.render/ai … :seon.render/html …}]` entity schema. The debug page
renders the record as identity, plan, settings, then the derived blocks.

