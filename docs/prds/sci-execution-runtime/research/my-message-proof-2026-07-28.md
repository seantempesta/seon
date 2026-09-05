---
type: research
status: active
tags: [research, agent-runtime, messaging, live-proof]
---

# my.message: the agent-facing hands, and the two-agent delegation they make real

The substrate was already live before this rung: the run loop wakes on
`:seon.cluster.message/to`, and `seon.error/commit-tx` has been committing
messages that open real runs since the error rung. What was missing was the
agent-facing half — an agent could be messaged and could not message. This
document records the design, the two rulings it needs from the owner, the
defects the proof exposed, and the live evidence.

## 1. The value shape, and why it is not a door

`my.message/send` returns a VALUE:

```clojure
(my.message/send "bob" "how many primes under 100?")
{:my.message/to "bob" :my.message/content "how many primes under 100?"}
```

Nothing is delivered by calling it. The form's value carries the map out
through the one admission gate, the loop recognises it exactly as it
recognises a `my.run` disposition, and the LOOP commits the message in the
same terminal transaction as that form's receipt.

**Why option (a), the value, and not option (b), the bounded evaluation.** The three
agent-facing shapes are values the driver interprets, capability REQUESTS
through one door, and durable FACTS the driver commits. A message is the
third: its entire effect is a transaction the loop is already making, against
the database the loop already holds. There is no external resource, no
credential, no host boundary — nothing a door exists to guard. Building
`seon.effect` for one verb whose effect is a row in a transaction would be a
door around the inside of the house, and it would have to be designed
around the wrong first example.

The quarry settles the cost question. `src-old/seon/agent/message.cljc` is 590
lines: an `^:async message!` that runs INSIDE the eval, AsyncLocalStorage-derived
sender identity, a bounded authority query per call, target validation, an
origin enum, and a stored hop counter — all of it effectful machinery in the
place the conversion ruling says agent-facing code must not be effectful. The
replacement is eleven lines of pure function plus a delivery rule the driver
owns.

**Composition — can a turn both send and complete?** Yes, in different forms,
and the fold answers it without a rule: the loop reads EVERY form's admitted
value, not only the last. A single form's value is ONE instruction, which is
enforced by the shapes rather than by a check (the disposition and message
schemas are closed maps with disjoint keys). Fan-out within one form is the
vector — `[(send "bob" …) (send "carol" …)]` — because a form's value is the
only channel and a vector is what Clojure already says for several.

## 2. The driver interpretation

`seon.cluster.message/delivery` is pure over a database value and returns two
things: tx-data rows, and flat error VALUES for candidates it refuses. The
loop rides both in the terminal transaction, so a message never exists without
the receipt that explains where it came from, and a refusal is a durable error
fact rather than a silent drop.

Three refusals, each with its own kind: `::unknown-recipient` (a lookup ref to
an agent this cluster lacks would fail the WHOLE transaction, taking the
receipt with it), `::chain-limit`, and `::no-limit` (fail-closed when the dial
is absent — a messaging path with no bound is exactly the runaway the bound
exists for, and `(> 1 nil)` would throw into the loop).

Message identity is derived — `<run>-<ordinal>-message-<index>` — the same
idiom as receipts and attempt rows, so nothing allocates a uuid and a
re-delivery would upsert rather than double-send.

## 3. The conversation bound: derived, and the human barrier comes free

Delivery being the wake is what makes messaging free, and it is also what makes
an unattended conversation a real infinite shape: alice messages bob, bob's
reply wakes alice, forever, each hop a paid model call. The error path's
recurrence fence cannot see this — it counts occurrences of one SIGNATURE, and
a polite conversation never repeats itself.

What repeats is the CHAIN, and the chain was already being recorded. A
run-opening transaction names its trigger in `:seon.db/trigger` transaction
metadata (the night ruling); a delivering terminal transaction now names the
same trigger. So depth is a walk:

```text
message → the transaction that created it → its trigger → …
```

and the walk ends at a message whose transaction named no trigger — which is
exactly a message from OUTSIDE the agent population: a human's nudge, or the
error recorder's.

**This is the quarry's hardest-won lesson arrived at structurally.** The quarry
stored a `hops` integer on every message and then needed a second rule to make
it correct: count only inbound messages from THIS peer that arrived after the
latest human message. That rule exists because the first one — a global count
over the newest inbound message — summed every hop of a delegation TREE, so a
routine two-round delegation (parent→A→parent→B→parent) hit the cap and
silently deadlocked (`src-old/seon/agent/message/internal.cljc:35-56`). Both
halves dissolve here. The count is per-conversation because causation is, and
the human barrier is free because a human's message has no cause we recorded.
Nothing is stored, so nothing can drift, and nothing can forget to increment.

The dial is `:seon.config.message/max-chain`, default 16 — the quarry's
hard-won 8 doubled, on the reasoning that its number was calibrated against a
count that OVERSTATED depth. **Flagged for the owner** (§7).

## 4. The completion reply: derived from the trigger, not remembered by the agent

The first live drive got four of five milestones and then stalled, and the
stall was the design's real gap. Alice delegated correctly within 2.4 seconds.
Bob's prompt read `Agent alice sent you: How many prime numbers are there
below 100?`, bob worked it out, and bob called `(my.run/complete "…")` — which
addressed nobody. Alice waited forever for a number that already existed.

```text
[22:11:11.730] OK   alice sent bob a message → {:content "How many prime numbers are there below 100?", :to "bob", :from "alice"}
[22:11:12.246] OK   bob opened a run of his own
[22:13:13.840] DRIVE FAILED: MISS: bob answered alice never became true
                runs: alice closed 02:11:11.816, bob closed 02:11:16.016
                errors: #{}
```

Bob was not wrong; `complete`'s own docstring said there was no completion
message because N3 had no addressable recipient. There is one now, and the
driver derives it: when a run's trigger has a `from`, the completion result is
delivered back to that sender as an ordinary `my.message` value — same bound,
same recipient check, same derived id. When the trigger came from outside the
population, nothing is delivered, exactly as before.

The alternative was to instruct the model: "if an agent asked you, reply by
message, THEN complete." That is a protocol an agent can forget on any turn.
The trigger already knows who asked.

**And then the second drive proved that rule half-wrong.** With the reply in
place the delegation completed — and produced a fourth message:

```text
depth 0  HUMAN  → alice   I need the sum of all the multiples of 3 below 1000…
depth 1  alice  → bob     Please calculate the sum of all multiples of 3 below 1000.
depth 2  bob    → alice   The sum of all multiples of 3 below 1000 is 166833.
depth 3  alice  → bob     The sum of all multiples of 3 below 1000 is 166833.   ← the bounce
                          run d1e6216b… agent bob … OPEN
```

Alice's completion was the sentence for the HUMAN, but her run had been
triggered by bob's answer, so the reply rule delivered it to bob, who opened a
run to consider it. Bounded by the chain limit at sixteen — and a bound doing
that work is a bound being used as a design.

A REPLY IS NOT A QUESTION, and the distinction is derivable from the same
chain the depth bound walks: the trigger is an answer to us exactly when the
message that CAUSED it was one of ours. No reply flag, no `in-reply-to`
attribute, no new fact — `caused-by` answers a second question. It is also the
right terminator: a delegation ends when the delegator completes, which puts
the chain bound back to being the backstop it should be. A genuine SECOND
question from the same peer is still answered, because that message was caused
by something else.

## 5. Defects this rung found and fixed

**A wait disposition livelocked the run loop** (blocker; fixed, issue
`a-wait-disposition-livelocked-the-run-loop.md`). `next-work` derives `:close`
for an open planned run whose forms are all settled, including one nobody
holds; `close-call` refuses a run the process does not hold; the self-rewake
fires again. Measured before the fix: twelve passes, `[:open :call :resume
:close :close :close …]`, nine durable error facts, `next-work` still saying
`:close`, and the agent's run pointer never retracted — so no later trigger for
that agent could ever open a run. The `:close` branch now takes custody first,
the same takeover `settle-interruption!` uses. After: four passes, `:closed`,
`next-work` nil, zero error facts.

The existing suite asserted "the run is still open after a wait" and was green
BECAUSE every close refused. A test can pin a livelock.

**`error/commit-tx` could not compose with itself** (fixed in place). Its
tempid was the constant string `"seon.error/fact"`, so two calls in one
transaction would put two different `:seon.error/id`s on ONE entity, silently.
The tempid now derives from the error's own id. This became reachable the
moment a form could produce several undeliverable messages.

**`my.run`'s error values are outside their own declared output** (filed,
`my-run-error-values-omit-their-kind.md`). `[:or :my.run/wait
:seon.error/value]` is declared; `{:seon.error/message "…"}` is returned, and
`:seon.error/value` requires a kind. `my.message/send` does it correctly, and
`my.message-test` asserts the `my.run` defect deliberately so that fixing it
fails the test and points at the issue.

## 6. Live proof

Script: `research/scripts/my-message-two-agent-2026-07-28.clj`. One cluster,
two agents, ONE human message — everything after it is the system's own doing.
The loop is armed by `cluster/start!`, the production path, not a hand-built
graph. Every milestone is a committed fact; a timeout throws so the drive
cannot exit zero on a missed milestone.

Three drives were needed and each failure was the design telling me something
(§4 and §5 are two of them). The final run, verbatim:

```text
[22:26:36.904] TRIGGER — the human asked alice, and alice was told to delegate
[22:26:39.461] OK  alice sent bob a message → {:content "Please calculate the sum of all multiples of 3 below 1000.", :to "bob", :from "alice"}
[22:26:39.466] OK  bob opened a run of his own
[22:26:43.024] OK  bob answered alice → {:content "Alice, the sum of all multiples of 3 below 1000 is 166,833.", :to "alice", :from "bob"}
[22:26:45.075] OK  alice completed with a result derived from bob's answer
                   → "Received sum of multiples of 3 below 1000 from Bob: 166,833. Task complete."

=== THE MESSAGE CHAIN ===
  depth 0  HUMAN  → alice   I need the sum of all the multiples of 3 below 1000. Do not work it out yourself…
  depth 1  alice  → bob     Please calculate the sum of all multiples of 3 below 1000.
  depth 2  bob    → alice   Alice, the sum of all multiples of 3 below 1000 is 166,833.

=== THE RUNS, THEIR TRIGGERS, THEIR FORMS ===
  run a013d810…  agent alice  trigger "human-1"                     closed
      form 0: (my.message/send "bob" "Please calculate the sum of all multiples of 3 below 1000.")
      form 1: (my.run/wait "Waiting for bob's answer to sum of multiples of 3 below 1000")
      receipt 0 :done → {:my.message/to "bob", :my.message/content "Please calculate…"}
      receipt 1 :done → {:my.run/disposition :wait, :my.run/note "Waiting for bob's answer…"}
  run cc5056ce…  agent bob    trigger "a013d810…-0-message-0"       closed
      form 0: (my.run/complete "Alice, the sum of all multiples of 3 below 1000 is 166,833.")
      receipt 0 :done → {:my.run/disposition :completed, :my.run/result "Alice, the sum…"}
  run 9fc95cc8…  agent alice  trigger "cc5056ce…-0-message-0"       closed
      form 0: (my.run/complete "Received sum of multiples of 3 below 1000 from Bob: 166,833. Task complete.")

=== THE ANSWERED-NESS TRAIL (tx-meta, no flags) ===
  transaction 536870922 answered human-1                    ← alice's run opened
  transaction 536870926 answered human-1                    ← alice's delivering terminal tx
  transaction 536870931 answered a013d810…-0-message-0      ← bob's run opened
  transaction 536870935 answered a013d810…-0-message-0      ← bob's delivering terminal tx
  transaction 536870936 answered cc5056ce…-0-message-0      ← alice's second run opened

=== ERROR FACTS (must be empty) ===
   #{}
```

**What the facts prove, milestone by milestone.** Alice's run was triggered by
`human-1` and its form 0 asked bob — the receipt carries the message VALUE the
agent returned, and the message row carrying `from alice` rode that same
transaction. Nothing but that commit woke bob: his run's trigger IS alice's
message. Bob completed, and the completion replied to alice because the
trigger named her, not because bob remembered to. Alice's second run was
triggered by bob's answer, and she completed with 166,833 — a number no form
of hers ever computed.

Note what alice's second run does NOT have: a message. Her completion is the
answer to the HUMAN, and the trigger she was answering was caused by her own
message, so the reply rule (§4) correctly delivers nothing and the
conversation ends. Eight seconds, three messages, three runs, zero error
facts, and the chain bound was never consulted for anything but arithmetic.

**Cost and timing.** Four paid DeepSeek turns per successful drive (alice ×2,
bob ×1, plus alice's first-drive turn), 8.2 s wall from trigger to final
completion, on the default provider row.

**One thing the drive shows that is not mine.** Teardown prints
`No implementation of method: :-dispatch! of protocol: PWriter … for class:
nil` and, on one run, `seon.error commit-fault! failed: Connection has been
released. SEON CORE FAULT (dev panic): nil` — the known released-connection
family (`instrumentation-surfaces-released-connection-contracts`). It happens
after the proof completes and touches nothing it asserts.

## 7. Morning questions for the owner

1. **The conversation bound's number and its shape.** 16 hops is a judgement
   call standing on the quarry's 8. The MECHANISM (derive from the tx-meta
   chain, reset at any message from outside the population) is the part worth
   ruling on; the number is one dial in `config/default.edn`. Is a hop count
   the right currency at all, or should the bound be spend (paid calls per
   chain) or wall time? Hops is what the facts already carry.
2. **Does the sender learn that its message was refused?** Today a refusal is
   a durable error fact attributed to (agent, run) and visible on the problems
   surface, and the sending agent is NOT told by message — telling it would be
   the storm shape, and the recorder's own rule already says a returned VALUE
   tells nobody. But the agent then genuinely does not know, and its next
   prompt does not say. The honest fix is probably a derived prompt sentence
   from the agent's own recent error facts, which is the context-block rung's
   business.
3. **What `wait` should mean.** It releases custody and the next pass closes
   the run, because a run whose plan is fully executed has nothing to resume.
   The docstring now says so. The alternative is to make `wait` close directly
   in the terminal transaction and take `release-tx` off this path — one fewer
   transaction and one fewer intermediate state that nothing can use. Ruling
   wanted before either is called settled.
4. **Self-messaging is allowed.** An agent may address itself, which is a
   genuine "keep going" primitive and also a trivial infinite loop bounded only
   by the chain guard. It was found by accident — a test stub made agent-b
   message itself and the loop delivered it happily. Keep, or refuse?
5. **A completed run replies to its asker with the result text verbatim.**
   That is the whole reply. Should it carry any framing (who it is from is
   already a fact; what it answers is already the chain), or is the bare result
   right? In the live drive the model framed it itself — bob wrote "Alice, the
   sum … is 166,833." — which suggests the bare result is enough.
6. **A failed form does not stop the fold**, so a run can complete with a value
   built from an unbound var and — now — deliver that to another agent. Filed
   as `a-failed-form-does-not-stop-the-fold.md` with three candidate rulings;
   it is N3's decision, not this rung's, but the messaging rung is what turns
   it from a bad receipt into a bad ANSWER travelling between agents.
7. **`Math/sqrt` is not resolvable in the base sci context**, and it is the
   first thing a model reaches for when asked to test primality. The callable
   surface is N5's computed binding table and was deliberately not
   hand-extended here; recorded so the gap is visible where the surface gets
   decided.
