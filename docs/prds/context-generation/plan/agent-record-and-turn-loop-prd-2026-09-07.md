---
type: prd
status: draft — under independent review
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

## 2. The agent record — every attribute with the need it serves

An entity IS its attributes. No kind stamp, no pointers to things a query can
derive, no counters a query can count.

| attribute | value | the need |
|---|---|---|
| `:seon.agent/id` | string, identity | lookup, handles, messaging by id |
| `:seon.agent/namespace` | ref → `:seon.ns` | the prompt line and where forms evaluate; NOT unique (many agents may share a namespace); stewardship is `:seon.ns/steward` on the namespace |
| `:seon.agent/turns-left` | int | the bound on turns; the one counter the loop keeps, decremented per turn, refilled by an explicit act |
| `:seon.agent/process` | `(pid, start-instant)` string | custody while a JVM is turning this agent; absent = idle; a dead value = recover |
| `:seon.agent/turn` | ref → the open turn | "am I mid-turn"; absent when idle; replaces the run pointer |
| `:seon.agent/plan` | the `my.plan` data | the agent stores it on purpose |
| `:seon.agent/evals` | component set → evaluation entities | history (rendered), handles, nothing-re-executes |

Questioned and REMOVED from the record:

- **`:seon.def/*`** — a `defn` is a program row already (the install gate
  writes it); data the agent wants kept, it transacts; an atom's contents are
  not a fact. Deleted.
- **cluster attribute** — the branch is the cluster. Deleted.
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
- **Pending** = a wake item with no `:seon.wake/turn` claim. **Claim** = the
  turn asserts `:seon.wake/turn` on the item it answers (a ref; never a
  retraction, so the item, its answer, and the order are all facts).
- **The listener** is the one that exists (`seon.cluster.wake/route!` on
  Datahike `listen`): a transaction that asserts a wake-source attribute
  offers one payload-free wake to that agent's turn proc. The attribute set it
  watches is derived from the `:seon.wake/source` declarations, not a hand
  list.
- Naming: **wake** is the mechanism's own name (core.async wakes; the Datahike
  listener is already `seon.cluster.wake`). Source, item, claim, pending are
  the four words; "inbox", "mailbox", "queue", "trigger" retire.

Policy lives on the source, not the agent: a source declares whether it
opens a turn (`:seon.wake/opens-turn? true`) or only surfaces in context on
the next turn (a schedule tick might; a low-priority notice might not).

## 4. The turn — what is stored, and why each thing

A turn groups an ordered set of evaluations, names what woke it, and records
what the model call cost. Every attribute questioned:

| attribute | why stored |
|---|---|
| `:seon.turn/id` | identity for evaluations and attempts to reference |
| `:seon.turn/agent` | ref; whose turn |
| `:seon.turn/wake` | ref → the wake item claimed (§3); "why this turn" |
| `:seon.turn/opened-at`, `/closed-at` | the bound; open = no closed-at |
| `:seon.turn/basis` | the database basis the context was projected from — the record of the context, since projection is deterministic; nothing else about the context is stored |
| `:seon.turn/reply` (+ blob over the bound) | the model's bytes, under the storage bound; the freeze evidence |
| `:seon.turn/attempts` | component set: provider, model, duration, cost gauges, error — one per attempt; a paid call is a fact |

Questioned and removed from the turn: the prompt text and per-segment
contribution rows (derivable from basis + profile — and the whole point of
byte-identical projection); `interrupted-at` on the turn (it lives on the
evaluation that was cut); the situation stamp; the plan digest; the forms
list (evaluations point at the turn).

An **evaluation** entity: `turn`, `ordinal`, `comment`, `source`, `ns`,
`author` (`:agent` or `:system`); then, once run: `value` under the storage
bound OR `missing` (why: over-bound, unserializable, lost), `out`, `error` +
triage, `ending-ns` when changed, `ms`, print options in effect;
`interrupted-at` when recovery cut it. No terminal fact = not yet evaluated.

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
- **Byte identity is the regression**: project the same agent from the same
  database twice; the bytes are equal. Project from `as-of` the turn's basis;
  the bytes equal what the provider was sent (recorded as a digest on the
  attempt, not as text).

## 6. What this deletes

`:seon.cluster.work/situation` and the generate arm, `generation-complete-*`,
`append-generated-call`, generated runs and `bootstrap/seed-tx`'s run (the
opening is the projection), `:seon.cluster.run` as a family (renamed to the
turn with the attributes in §4 only), the run pointer and
`::agent-pointer-broken`, `:seon.context.capture` and
`:seon.context.contribution`, `plan-digest`, `undisposed-at`, the gate
counters, `:seon.ai.attempt/ordinal`, `:seon.def/*`, `:seon.render/units`,
the admission caps, `bind-stored-results!`'s windowed ambiguity, and 20 of
the loop's 21 connection reads (one database value enters a pass).

## 7. The loop code

```
(defn step [db agent]                                   ; pure
  (cond
    (open-turn-with-unsettled-evals db agent) :evaluate  ; also crash resume
    (and (pending-wake db agent) (pos? turns-left))  :reply
    :else                                            :idle))
```

`:reply` = open (claim the wake, record basis, decrement turns-left, set
process) → project → model → freeze reply + evaluations (one commit) → fall
into `:evaluate`. `:evaluate` = fork → evaluate each unsettled ordinal →
settle the batch and close the turn (one commit). Commits per model turn: open,
one per attempt, freeze, settle+close = 4 (today 5–6). Fences kept, all
`:db.fn/call`: one evaluation per (turn, ordinal); settle once; one turn per
wake item; holder-only close; dead-holder takeover; total never-refusing
recovery.

## 8. Order of work (reset the dev database at each schema step)

1. Storage bound + missing marker + AI-boundary elision (`seon.sci.admit`,
   `seon.print/fit`, `seon.repl`).
2. Wake sources: declare `:seon.wake/source` on the three attributes, derive
   the listener's set, claim refs on the turn; delete message `to` reverse
   units.
3. The turn: rename/trim `seon.cluster.run` to §4; delete generated runs,
   situation, captures; the two-arm loop; one db value per pass.
4. The record: `:seon.agent/*` keys, delete `:seon.def`, cluster, pointer,
   units; page renders the record's components in declared order.
5. Reset, reseed, byte-identity regression, docs and vocabulary.

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
