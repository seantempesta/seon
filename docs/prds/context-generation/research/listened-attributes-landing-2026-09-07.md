---
type: research
status: complete
date: 2026-09-08
tags: [research, wake, run-loop, schema, agent]
---

# Landing note: listened attributes, Datahike `listen`, and answered-by-`:t`

Written by the `listened-attributes` lane (PRD §8 step 2) against
[AGENTS.md](../../../../AGENTS.md) and
[the turn-loop PRD](../plan/agent-record-and-turn-loop-prd-2026-09-07.md)
(§3 and the wake parts of §4a), both read end to end; the prototype
([claims 1, 3, 4](prototype-turn-loop-in-repl-2026-09-07.md)), the two reviews
([opus](prd-review-turn-loop-opus-2026-09-07.md) wake cases,
[astra](prd-review-turn-loop-astra-2026-09-07.md) B3 and B6),
[the branch/SCI/wake model](cluster-branch-sci-wake-model-2026-09-07.md),
[the run loop unpacked](run-loop-unpacked-2026-09-07.md) §5.6,
[the storage-bound repair](storage-bound-repair-2-landing-2026-09-07.md) and
[the P1-P6 landing](production-defects-p1-p6-landing-2026-09-08.md), all read
end to end. Skills read: `datahike`, `seon-flow-architecture`,
`data-modeling`.

Commits: `c04765e10` (mechanism), `9b276ee34` (proofs, fixture repairs, two
issue notes).

## What waking is now

An attribute wakes an agent exactly when its schema row carries
`:seon.wake/listen true`. Two hand lists are gone —
`seon.cluster.wake/wake-attributes`'s literal set and `route!`'s `case` — and
one derivation replaced both:

```clojure
;; live on the lane's own cluster, root tmp/wake-lane-root, cluster wake-lane,
;; :current-src commit 6aa01596-cb25-5c10-af49-b124e57719ef
(seon.cluster.wake/wake-attributes D)
;; => #{:seon.cluster.message/to :seon.effect/to
;;      :seon.error/steward :seon.schedule.fire/agent}
(seon.cluster.wake/turn-opening-attributes D)
;; => #{:seon.cluster.message/to :seon.effect/to :seon.error/steward}
(seon.cluster.wake/inside-attributes D)
;; => #{:seon.cluster.message/about :seon.cluster.message/from
;;      :seon.effect/to :seon.error/steward}
```

`route!` runs that query ONCE at registration and the handler closes over the
answer, because the listener runs on the committing caller's critical path and
may not query. Dispatch is the same derivation: every listened attribute is a
ref whose value is the agent's entity id, so delivery is one map lookup, and a
recipient with no routing entry falls through to the armer.

Three properties, all declared in the new
`resources/seon/schemas/seon.wake.edn`:

| property | meaning |
|---|---|
| `:seon.wake/listen` | this datom wakes the agent it points at |
| `:seon.wake/opens-turn?` | an unanswered wake here opens a turn (else it only reaches the next context) |
| `:seon.wake/inside` | this attribute's presence marks a wake the population caused, so it never refills the turn bound |

The prototype's claim 3 was re-verified live before anything was built: the
property lift is general (`storable-properties-in`), and
`:seon.cluster.message/to`'s row already carried `:seon.render/ai`, `/html`
and `/form` — declaring `:seon.wake/listen` needed no schema-machinery change
at all.

## Answered by `:t`

`seon.cluster.work/unanswered-wakes` is the ONE answeredness derivation: a
wake is answered when some turn of that agent has `:t` at or after it.
`unanswered-triggers` survives as its projection onto the message family (the
unread count, the concurrency proofs, the page ask about messages
specifically), not as a second path. `latest-turn-t` and `outside-wake-t` are
the two `:t` reads everything else composes from; `episode-runs` keeps its
name and contract and changed its body.

`run/opening-db` ALREADY derived the projection basis from the run's own
`opened-at` datom's transaction, so the prototype's headline amendment
(basis = the opening transaction's `:t`) needed no code change — only the
answeredness half did.

Deleted: `seon.cluster.loop/open-trigger-call` and its
`::trigger-already-answered` refusal class, plus that class's suppression of
the self-rewake in `seon.cluster.agent`. The fence protected a stored
reference; the derivation makes a second turn for an answered wake
underivable, and `open-call`'s `::agent-already-running` is what stops two
openers.

Deleted: the `:seon.cluster.agent/id` wake. A fresh agent's first message is
its first wake, and the armer belt already covers created-and-addressed in one
commit (`an-unrouted-recipient-reaches-the-armer`).

## The live proof

Own scratch cluster, root `tmp/wake-lane-root`, `bin/seon reset --force` onto
this tree then `start wake-lane`, seeded with the Juniper fixture.

**The double pay is gone, on the fixture that produced it.** The fixture's
two `design-lab/root-to-juniper/*` messages share transaction `536870938`:

```clojure
{:messages [["bootstrap-task:juniper" 536870937]
            ["design-lab/root-to-juniper/2" 536870938]
            ["design-lab/root-to-juniper/1" 536870938]]
 :runs [["bootstrap:juniper" 536870937]
        ["85757829-d0e2-47bb-8446-79365a243640" 536870953]]}
```

TWO turns, not three. The prototype measured the old model opening a third
paid turn (`34087`) on exactly this data.

**A hand-transacted message opens a turn and is answered, from one
connection, with nothing stored:**

```clojure
{:runs-before 2
 :unanswered-immediately ["wake-lane/live-probe/1"]   ; on the :db-after
 :runs-after 3                                        ; 18fb8565-… at :t 536871029
 :unanswered-after []}
```

The page repaints: `GET /agent/juniper` returned 200 and 110,585 bytes with
the record, the plan and the configuration rendered.

## Deviations from the lane spec, each with its grounding

1. **The firing family is `seon.schedule.fire`, accreted — not a new
   `seon.schedule.firing`.** `resources/seon/schemas/seon.schedule.fire.edn`
   already IS one immutable entity per nominal instant, keyed
   `(task-id, nominal-at)`, which is exactly what astra B6 asks for. Coining a
   second family for one noun is the `foo-v2` law. `:seon.schedule.fire/agent`
   is the accretion: the fired task's owner, resolved inside `claim-fire-call`.

2. **`:seon.schedule.fire/agent` is declared `:seon.wake/opens-turn? false`.**
   Live on this tree there are five scheduled tasks, all owned by `root`
   (`root/maintenance/{process-census,footprint,compact,reap-dead-roots,rotate-logs}`),
   all on cron expressions. Declaring the firing turn-opening would buy a paid
   model call per maintenance tick. The PRD's own §3 names a schedule tick as
   the archetype of a wake that "only surfaces in the next context", so this is
   the property doing its job rather than an exception to it.

3. **`:seon.cluster.run/trigger` and `:seon.cluster.run/opening-commit-id` are
   NOT deleted.** They decide nothing any more — answeredness is `:t`, and the
   basis was already derived — but seven readers live OUTSIDE this lane's owned
   paths and would go silently wrong (a query on a never-written attribute
   reads every message as unanswered, which is this project's named failure
   class):

   | reader | what it reads |
   |---|---|
   | `src/seon/context.clj:410,425` | current-trigger / pending / history message role |
   | `src/seon/cluster/message.clj:79-85` | `message/trigger` |
   | `src/seon/render/walk.clj:171,347` | the `:seon.cluster.run/_trigger` reverse hop |
   | `src/seon/eval/drive.clj:121`, `src/seon/bootstrap_drive.clj:114` | answered-message queries |
   | `resources/seon/schemas/seon.cluster.agent.edn:158-162` | declared render units |
   | `src/seon/cluster/curate.clj:37-65,173` | `opening-commit-id` as the span basis |

   `open-turn` still writes the trigger, documented in place as PROVENANCE
   ONLY. **Step 3 (the turn rename) is the right owner of the deletion**: it
   renames `seon.cluster.run` to `seon.turn` and therefore must edit every one
   of those readers anyway.

4. **`:seon.wake/inside` is a third property the spec did not name.** Without
   it the "outside wake" anchor had no derivation: `seon.cluster.work`'s old
   test was a hard-coded `from`-or-`about` pair, which cannot express a fault
   or an effect settlement. Each family now declares its own inside-ness and
   the anchor is a query. `:seon.effect/to` is declared inside for a measured
   reason: an agent's own effect settling would otherwise refill the bound the
   agent spent requesting it.

5. **The turn bound anchors on the outside wake's OWN `:t`**, as the spec and
   PRD §1a say. The prototype (ranked item 7) measured that this diverges from
   today's anchor — the first turn ANSWERING the last outside wake — and that
   only the latter preserves today's behaviour exactly. The lane implemented
   what it was told; the divergence is one line in `episode-runs` if the owner
   wants the other.

## The steward, and what it cannot route yet

`:seon.error/steward` is decided inside the committing transaction:
`seon.error/steward-call` is a `[:db.fn/call …]` that resolves
`:seon.instrument/fn` -> `:seon.fn/ns` -> `:seon.ns/steward` against the
mid-transaction database and merges the ref onto the fact's own string tempid.
No steward, no datom; the fact is committed either way.

**There is no self-exclusion.** A fault in an agent's own code wakes that
agent like any other, and the turn bound is what terminates the loop, because
the steward ref is a declared INSIDE wake. The 2026-08-08 escalation incident
(nine paid calls in twenty minutes) is bounded by two things now: the fault
family's recurrence limit and the turn bound.

**What it cannot do yet, measured:** only `:seon.instrument/contract-violated`
faults record the failing function (`projected-instrument-data`,
`src/seon/error.clj:401-408`). Every other class carries
`:seon.error/proc`/`:seon.error/op` — a proc, not a function — so it resolves
no steward. This is the same blocker the branch/SCI/wake research recorded
(§4.4.3(b): 14 faults, 0 carrying `:seon.instrument/fn`), and it is at the
fault seam, not in this routing. The regression names it in its own comment.

The fault-to-message minting (`error/message-tx`, the three `tell` sites) is
UNTOUCHED, so a fault now produces two wakes — the steward ref and the
notification message — in ONE transaction, which is ONE turn by construction.
Deleting the mailing mechanism (and with it the `loop.clj:682-696` escalation
guard, per PRD §3) belongs to whichever lane owns that deletion; this one was
not asked for it.

## Gates

Baselines measured in a detached worktree at `HEAD~1`
(`tmp/wake-baseline`, removed after).

`bin/test seon.cluster.wake-test seon.cluster.work-test seon.cluster.loop-test
seon.cluster.turn-test seon.cluster.agent-test seon.cluster.run-test
seon.bootstrap-test seon.schedule-test seon.effect-test seon.error-test
seon.cluster.prompt-test` — 228 tests, 1,140 assertions.

| namespace | baseline red | now | note |
|---|---|---|---|
| `seon.cluster.wake-test` | 0 (11 tests) | 0 (16 tests) | five new proofs |
| `seon.cluster.work-test` | 1 | 0 | the inherited red was a fixture handing `transact!` a lazy seq |
| `seon.error-test` | 0 | 0 | |
| `seon.bootstrap-test` | 0 | 0 | |
| `seon.cluster.run-test` | 1 | 1 | inherited |
| `seon.effect-test` | 1 | 1 | inherited |
| `seon.schedule-test` | 1 | 1 | inherited (`:seon.error/data-size` refused by its own long schema) |
| `seon.cluster.loop-test` | 5 | 5 | inherited |
| `seon.cluster.prompt-test` | 6 | 6 | inherited |
| `seon.cluster.turn-test` | 19 | 19 | inherited |
| `seon.cluster.agent-test` | 16 | 17 | **one new**, filed |

The one new red is
`disarm-has-a-declared-loud-turn-completion-backstop`
([issue](../../../seon/issues/two-turn-backstops-fire-and-the-sliding-fault-channel-keeps-the-wrong-one.md)):
two backstops fire and the agent's sliding-1 fault channel keeps the second,
which names no held run. A probe (`tmp/wake_probe.clj`) shows the work
derivation itself is correct after a closed, released run; what changed is
timing, and `offer-turn-backstop-fault!` re-derives `held-run-id` at fire time
instead of carrying what the bound was armed against.

`seon.cluster.turn-test/a-real-evaluation-that-runs-away-is-stopped-and-recorded`
is GREEN in isolation and red under a loaded run — a 300 ms leash, load
sensitive, not a verdict about this change.

`bin/test --platform`: **GREEN — 73 tests, 398 assertions, 0 failures,
0 errors.**

## `bin/seon init --dev` REFUSED — and not by a schema deletion

```text
✗ The cluster rejected the prepl operation.
seon.schema/canonical-definition violated its contract (invalid-output):
must be a parseable, EDN-readable Malli form
  offending: [:=> [:cat :seon.db/database-value :seon.cluster.run/process
                   :inst :seon.cluster.agent/creation-request]
              :seon.store/transaction-data]
  caller: seon.fn (fn.clj:391)
```

Not this lane's. The offending contract is
`seon.cluster.agent/creation-tx`, untouched here, and the same refusal
appeared on this lane's FIRST edit before any function had changed. At HEAD
the predicate accepts that form:

```clojure
(seon.schema/malli-form?
 [:=> [:cat :seon.db/database-value :seon.cluster.run/process :inst
       :seon.cluster.agent/creation-request]
  :seon.store/transaction-data])
;; => true
```

So the live `juniper-context` JVM (pid 59736) is holding an intermediate
`seon.schema/malli-form?` that a concurrent lane reloaded into it mid-edit.
The remedy is AGENTS.md's own: stop and start that cluster, then re-run
`init --dev`. This lane's spec permits only `init --dev` on that root, so it
stopped rather than restarting a cluster it does not own.

## Unfinished, named

- The trigger and `opening-commit-id` deletions (deviation 3), for the turn
  rename lane.
- `:seon.error/steward` routes only contract-violation faults until the fault
  seam records the failing function for other classes.
- [A turn that dies before replying still answers its wakes](../../../seon/issues/a-turn-that-dies-before-replying-still-answers-its-wakes.md)
  — filed, with the three cases and three candidate closes.
- [Two turn backstops fire onto a sliding fault channel](../../../seon/issues/two-turn-backstops-fire-and-the-sliding-fault-channel-keeps-the-wrong-one.md)
  — filed, one new red.
- `bin/seon init --dev` on `juniper-context`, blocked above.
- `:seon.cluster.message/to` still carries no `:db/index` while the other
  listened refs do (prototype ranked item 11). Not measured as a scan on this
  data; left for whoever measures it.
