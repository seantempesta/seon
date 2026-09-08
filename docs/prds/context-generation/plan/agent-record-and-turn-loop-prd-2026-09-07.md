---
type: prd
status: draft r4 — Opus data-model review integrated (research/prd-review-turn-loop-opus-2026-09-07.md); astra review pending
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
   writes: freeze, then settle. Not one.
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
| run trigger ref | KEEP as the wake claim (§3) | the one fact that stops a wake being answered twice |
| run opening basis (`opening-commit-id`) | KEEP as `:seon.turn/basis` | the entire record of the context: projection is deterministic |
| run reply text / blob / size | KEEP under the storage bound | without it an interrupted turn's history cannot say what the model said |
| run `process` custody | DERIVE, delete the stamp | at boot every open turn in this cluster belongs to a dead process by construction; within a JVM the permit prevents a second turn. Deletes the stamp, the holder check, dead-holder takeover, holder-only close |
| agent run pointer | DERIVE: the agent's turn with no closed-at | its busy-fence role is the permit's |
| run `situation` | DELETE | generated runs are gone; every turn is a model call |
| run `plan-digest` | DELETE | "frozen" = the turn has evaluation entities |
| run `forms` component list | DELETED (`caef3850e`) | evaluations point at the turn |
| run `undisposed-at` | DELETE | derived in the same transaction from the evaluations |
| run `interrupted-at` | DELETE from the turn | the stamp lives on the evaluation that was cut |
| context capture + contribution rows | DELETE | basis + profile reproduce them byte for byte (§5) |
| ai attempt rows | KEEP, written in the freeze commit | a paid call is a fact; its own commit is not |
| ai attempt `ordinal` | DELETE | counted before the write today |
| evaluation source, comment, ns, ordinal, author | KEEP | the history |
| evaluation terminal facts (value/missing, out, error, ms, ending-ns, print options) | KEEP | the history and the handles |
| evaluation `interrupted-at` | KEEP, written at boot | the one recovery stamp |
| evaluation `result-size` | KEEP only when the value is missing | size reached is the reason |
| agent `turns-left` | DERIVE — no counter | the bound is `max-episode-runs − turns since the last OUTSIDE wake` (today `bootstrap.clj:47-70`, `work.clj:378-437`); a human message resets it by being an outside wake, with no refill mechanism. If the derivation measures slow, store one episode ANCHOR per outside wake, never a decrementing counter (review B2) |
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
`interrupted-at` MUST render. The model is never re-called because the wake
was claimed at open; no form re-executes because nothing re-runs an
interrupted turn. What dies is the custody predicate — the process stamp,
`claim-call`'s takeover, `release-call`, holder-only close — not the
evaluate arm, which §7 keeps for turns this JVM opened.

**Writes per turn: three.** Open (claim every wake item pending at the
basis, record the basis — the irreducible pair) → freeze (reply, attempts,
the forms as evaluation entities) → settle and close (results). The freeze
survives because without it a crash loses the paid reply AND the record
that side-effecting forms ran.

## 2. The agent record — every attribute with the need it serves

An entity IS its attributes. No kind stamp, no pointers to things a query can
derive, no counters a query can count.

| attribute | value | the need |
|---|---|---|
| `:seon.agent/id` | string, identity | lookup, handles, messaging by id |
| `:seon.agent/namespace` | ref → `:seon.ns` | the prompt line and where forms evaluate; NOT unique (many agents may share a namespace); stewardship is `:seon.ns/steward` on the namespace |
| `:seon.agent/plan` | the `my.plan` data | the agent stores it on purpose |
| `:seon.agent/evals` | component SET → evaluation entities | history (rendered), handles, nothing-re-executes. Order lives on the evaluation (`turn`, `ordinal`), never on the set. An agent identity row never retracts (ruling 47 extended), so the component cascade is never exercised |

Questioned and REMOVED from the record:

- **`:seon.def/*`** — a `defn` is a program row already (the install gate
  writes it); data the agent wants kept, it transacts; an atom's contents are
  not a fact. Deleted.
- **cluster attribute** — exactly one `:seon.cluster/name` entity exists per
  branch (probed); deleted, with ONE derivation "the cluster entity of this
  database value" replacing the eleven call sites, `render/request-profile`
  first (review S2).
- **`turns-left`** — derived (§1a). The loop keeps track of turns by
  counting them, not by remembering a number.
- **process custody and the open-turn pointer** — both derivable (§1a):
  "mid-turn" is "my turn with no closed-at"; "dead" is "open at boot".
- **the inbox / any wake collection** — waking is not a thing the agent owns
  (§3). Not on the record.
- **`:seon.render/units`, reverse-ref units** — the record's components are
  the units, in the entity schema's declared order. Deleted.
- **run pointer, situation stamp, plan digest, generated runs, the form
  family, context captures, contribution rows, every derived-and-stored
  counter** — see §6.

## 3. Waking — a declared source, never a queue

The inbox was wrong because it made "what can wake this agent" a collection
the agent owns, and only messages fit it. The ruled model: **anything can wake
an agent, each thing is its own well-schemad fact family, and the loop
derives "pending" by query.**

- A **wake source** is an attribute whose schema declares
  `:seon.wake/source true` and whose value is a ref to the agent. Today's
  sources: `:seon.cluster.message/to` (a message, from an agent, a person, or
  the system), `:seon.error/to` (a fault routed to a steward),
  `:seon.schedule/agent` (a scheduled firing). A new source is one schema
  property, no loop change.
- A **wake item** is any entity that references an agent through a wake
  source. It is ordinary data with its own attributes; nothing is copied into
  the agent.
- **Claim** = the TURN asserts `:seon.turn/wake` → item (ruling 70's own
  words: "a claim ref from the handling run"; also what exists at
  `work.clj:604-621`). The item stays another agent's immutable fact; one
  indexed attribute serves every source. `:seon.turn/wake` is
  cardinality-MANY: a turn claims EVERY item pending at its `basis`, because
  the context it projected already contained all of them — claiming one and
  re-paying the model for the next is the live double-pay defect at
  `work.clj:449-452` (review B3). The claim is taken at the basis, never at
  settlement: an item arriving mid-turn stays pending and opens the next
  turn (review B6). **Pending** = `(not [_ :seon.turn/wake ?item])`.
- **The listener** is the one that exists (`seon.cluster.wake/route!` on
  Datahike `listen`): a transaction that asserts a wake-source attribute
  offers one payload-free wake to that agent's turn proc. Today the source
  set is TWO hand lists (`wake.clj:78-93` and the `case` at `wake.clj:232-250`)
  and attribute properties never reach the database — only three named
  render properties are lifted onto schema rows from a hand list at
  `schema.clj:1419-1420` (review B4). So "one schema property, no loop
  change" requires: every `seon.*` attribute property lifted onto the schema
  row (review S3), the listener's set derived by one query over
  `:seon.wake/source`, and `route!` dispatching from that set (S4).
- Naming: **wake** is the mechanism's own name (core.async wakes; the Datahike
  listener is already `seon.cluster.wake`). Source, item, claim, pending are
  the four words; "inbox", "mailbox", "queue", "trigger" retire.

Policy lives on the source, not the agent: a source declares whether it
opens a turn (`:seon.wake/opens-turn? true`) or only surfaces in context on
the next turn. One exclusion is per ITEM and cannot be a source property: a
fault whose steward is the agent the fault is about must not wake that
agent, or the 2026-08-08 escalation loop returns (nine paid calls in twenty
minutes, `loop.clj:682-696`). The fault committer decides that INSIDE its
transaction — it computes the steward (fault → function → namespace →
`:seon.ns/steward`) and asserts `:seon.error/steward` only when the steward
is not the subject agent (review B7, owner law: decide at the authority).
`:seon.error/agent` (the subject) and `:seon.error/steward` (the route) are
two relations and both are declared.

## 4. The turn — what is stored, and why each thing

A turn groups an ordered set of evaluations, names what woke it, and records
what the model call cost. Every attribute questioned:

| attribute | why stored |
|---|---|
| `:seon.turn/id` | identity for evaluations and attempts to reference |
| `:seon.turn/agent` | ref; whose turn |
| `:seon.turn/wake` | refs (many, indexed) → every wake item pending at the basis (§3); "why this turn"; pending is the absence of this ref |
| `:seon.turn/opened-at`, `/closed-at` | the bound; open = no closed-at |
| `:seon.turn/basis-t` | the basis transaction `:t` the context was projected from — Datahike's `as-of` takes a `:t`, so this IS the replay input; the commit id is derivable from it. Nothing else about the context is stored |
| `:seon.turn/reply` (+ blob over the bound; `reply-missing` when the blob was reclaimed) | the model's bytes, under the storage bound; the freeze evidence; the same ablation rule as a value |
| `:seon.turn/attempts` | component set of `:seon.ai.attempt` entities — the AI owner's existing family, referenced, not re-homed (review F6); one per attempt; a paid call is a fact |

Questioned and removed from the turn: the prompt text and per-segment
contribution rows (derivable from basis + profile — and the whole point of
byte-identical projection); `interrupted-at` on the turn (it lives on the
evaluation that was cut); the situation stamp; the plan digest; the forms
list (evaluations point at the turn).

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
             :plan       [:seon.db/ref {:seon.db/component true
                                        :description "The agent's my.plan entity; stored on purpose by the agent."}]
             :evals      [:set {:seon.db/component true
                                :description "Every evaluation this agent has made, one entity per (turn, ordinal); the history is a projection of this set, ordered by (turn, ordinal). A set: Datahike keeps no order on cardinality-many."}
                          :seon.db/ref]
             :agent      [:map {:seon.db/attributes true
                                :seon.render/ai seon.agent/render-ai
                                :seon.render/html seon.agent/render-html}
                          [:seon.agent/id :seon.agent/id]
                          [:seon.agent/namespace :seon.agent/namespace]
                          [:seon.agent/plan {:optional true} :seon.agent/plan]
                          [:seon.agent/evals {:optional true} :seon.agent/evals]]}

;; seon.turn.edn — one model call and what it produced
#:seon.turn{:id        [:string {:seon.db/identity true :description "Identity for evaluations and attempts to reference."}]
            :agent     [:seon.db/ref {:description "Whose turn."}]
            :wake      [:set {:seon.db/index true
                              :description "Every wake item (§3) pending at this turn's basis; asserting it IS the claim; pending = no turn references the item."}
                        :seon.db/ref]
            :opened-at [:inst {:description "When the turn opened; open = no closed-at."}]
            :closed-at [:inst {:description "When the turn settled or was closed as interrupted at boot."}]
            :basis-t   [:int {:min 0 :description "The basis transaction :t the context was projected from; `as-of` this t plus the adopted program commit and the render profile reproduce the prompt byte for byte."}]
            :reply     [:string {:description "The model's reply under the storage bound; the freeze evidence."}]
            :reply-blob [:seon.blob/digest {:description "The reply's blob when it exceeds the inline bound."}]
            :reply-missing [:enum {:description "Why no reply text is available: the blob was reclaimed."} :lost]
            :attempts  [:set {:seon.db/component true :description "One :seon.ai.attempt entity per provider attempt."} :seon.db/ref]
            :turn      [:map {:seon.db/attributes true}
                        [:seon.turn/id :seon.turn/id]
                        [:seon.turn/agent :seon.turn/agent]
                        [:seon.turn/wake :seon.turn/wake]
                        [:seon.turn/opened-at :seon.turn/opened-at]
                        [:seon.turn/closed-at {:optional true} :seon.turn/closed-at]
                        [:seon.turn/basis-t :seon.turn/basis-t]
                        [:seon.turn/reply {:optional true} :seon.turn/reply]
                        [:seon.turn/reply-blob {:optional true} :seon.turn/reply-blob]
                        [:seon.turn/reply-missing {:optional true} :seon.turn/reply-missing]
                        [:seon.turn/attempts {:optional true} :seon.turn/attempts]]}

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
            :interrupted-at [:inst {:description "Asserted at boot on an evaluation with no terminal fact whose turn was open."}]}

;; seon.wake.edn — the mechanism (§3). Both are schema PROPERTIES lifted onto the schema ROW as facts (review S3), so "which attributes wake an agent" is a Datalog query.
#:seon.wake{:source [:boolean {:description "On a ref attribute: a transaction asserting it wakes the referenced agent."}]
            :opens-turn? [:boolean {:description "Whether a pending item of this source opens a turn or only surfaces in context."}]}

;; seon.error.edn — two relations, both declared
#:seon.error{:agent   [:seon.db/ref {:description "The agent this fault happened to (exists today)."}]
             :steward [:seon.db/ref {:seon.wake/source true :seon.wake/opens-turn? true
                                     :description "The steward routed to fix it; asserted inside the committing transaction, never when the steward is the subject agent."}]}
```

Wake sources declared where they live: `:seon.message/to` (renamed from
`seon.cluster.message`), `:seon.error/steward`, `:seon.schedule/agent` each
carry `{:seon.wake/source true :seon.wake/opens-turn? true}`. `:seon.ns/steward`
(exists, `seon.ns.edn`) is the routing fact they derive from.

Every key above is a full namespaced attribute with a Malli shape; absent
means no key; no `:any`, no `[:maybe]`, no `:kind`. Reviewers: is any key
misnamed against Clojure's or the dependency's own word? Is any shape
incomplete?

## 5. Storage bound, elision, and the missing marker

- **One storage bound per value** (`:seon.config.eval.result/max-bytes`).
  Serialization stops at the bound; that is also the realization bound for a
  lazy sequence. Under it the value is stored faithfully; over it, or
  unserializable, `:seon.cluster.eval/missing` names the reason and the size
  reached. The admission caps (depth, collection, string, node budget)
  are deleted.
- **Elision happens once**, at AI context generation, from the stored value,
  under the render profile, with requery forms into the stored value. HTML
  renders the stored value without limits.
- **A missing value ablates the handle**: no `:seon.repl/result` key, and
  `:seon.repl/value` says the result is unavailable and why. A later form
  naming a dead handle gets an ordinary unresolved-symbol error.
- **Byte identity is the regression**, qualified exactly: same database
  value (`as-of` the turn's `basis-t`), same adopted program commit, same
  render profile ⇒ same bytes; the bytes' digest is `:seon.ai.attempt/prompt-digest`.
  Two things make it unreachable today and are in scope (review B5): a
  render producer runs under a wall-clock time limit (`render.clj:725-756`)
  and its refusal contributes ABSENCE (`walk.clj:764-766`). A refused render
  contributes a stable typed unknown naming the producer, so the bound can
  fire without moving a byte. The walk's own truncation of connections
  (`walk.clj:208, 249-268`) IS the AI boundary's elision and moves under the
  render profile; it is not a second elision point (review S5). Result
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
`:seon.eval/outcome`, `:seon.eval/author`, the two hand lists of the wake
set (`wake.clj:78-93`, `:232-250`) and the render-property hand list
(`schema.clj:1419-1420`), the admission caps, `bind-stored-results!`'s windowed ambiguity, and 20 of
the loop's 21 connection reads (one database value enters a pass).

## 7. The loop code

```
(defn step [db agent]                                   ; pure
  (cond
    (open-turn-with-unsettled-evals db agent) :evaluate  ; also crash resume
    (and (pending-wake db agent) (pos? turns-left))  :reply
    :else                                            :idle))
```

`:reply` = open (claim the wake, record basis, decrement turns-left) →
project → model → freeze reply + attempts + evaluations (one commit) → fall
into `:evaluate`. `:evaluate` = fork → evaluate each unsettled ordinal →
settle the batch and close the turn (one commit). Commits per model turn:
three (today 5–6). Boot: close every open turn, stamping its unsettled
evaluations interrupted — total, never refusing. Fences kept: settle once and one turn per wake item as `:db.fn/call`;
one evaluation per (turn, ordinal) as the declared `:seon.eval/id`
identity — a declaration, not a transaction function (review S1). Deleted
fences: claim/takeover, release, holder-only close, pointer coherence, the
receipt-exists call.

## 8. Order of work (reset the dev database at each schema step)

1. Storage bound + missing marker + AI-boundary elision (`seon.sci.admit`,
   `seon.print/fit`, `seon.repl`).
2. Wake sources: lift every `seon.*` attribute property onto the schema
   row; declare `:seon.wake/source` on the three attributes; derive the
   listener's set and `route!`'s dispatch from one query; `:seon.turn/wake`
   many + indexed, claimed at basis; the steward decided inside the fault
   committer's transaction; delete message `to` reverse units.
3. The turn: rename/trim `seon.cluster.run` to §4; delete generated runs,
   situation, captures; the two-arm loop; one db value per pass.
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
3. Is the two-write turn (freeze, then settle) the minimum, or can the freeze
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
   forms transacted side effects, and is the freeze write still worth one
   commit under that rule — or could reply + results be one write?
9. The wake claim at open: with one JVM and one permit, is a stored claim
   needed at all, or is "a turn references this wake item" enough?
