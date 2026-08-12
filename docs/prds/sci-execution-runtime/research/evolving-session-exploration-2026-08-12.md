---
type: research
status: active
tags: [research, agent, context, repl, render, database]
---

# Evolving-session exploration: T0, message delta, and passive change

## Verdict

`generate(pull, retained-history)` has a clean data contract. The landed T0
mechanism also settles the most important placement question: generation is
derived work executed by the agent's turn proc through the ordinary run fold,
not a render-proc transaction. The current tree has not yet extended that
mechanism coherently to T1/T2.

- At T1, the cheapest coherent placement is a system-authored prefix inside
  the message wake's run: execute the delta listing and new-id reads before
  prompt acquisition, then append the model reply's agent-authored forms after
  that prefix.
- At T2, the render proc can derive and retain a pending suffix without waking
  agent work. It cannot truthfully append that suffix as settled REPL history,
  because no run or receipt exists. Ruling 36 forbids opening a run from the
  plan change alone, while the executed-history ruling forbids pretending that
  a render result is a receipt. The simplest recommendation is therefore to
  keep T2 as a passive pending delta and execute/append it at the next message
  wake. The owner must explicitly rule whether "appends passively" requires
  immediate settled history; if it does, it conflicts with "must NOT open a
  run" under the current ordinary-form/receipt model.

The delta query itself worked. It requires two database sources: the current
database for stable identities and the `since` database for changed datoms.
Querying only the `since` database returned no rows because the pre-existing
agent and database-user identity datoms are intentionally outside that
temporal view.

## Scope and authorities read

I read end to end before probing:

- [self-generating context PRD](../plan/self-generating-context-prd-2026-08-11.md), including all 36 rulings;
- [incremental invalidation design](incremental-invalidation-design-2026-08-11.md);
- [execute the agent's opening environment once](env-once-execution-design-2026-08-11.md);
- the localized runtime instructions and the complete data-oriented Clojure,
  Datahike, REPL, and Flow skills; and
- commit `16f022fc9` plus the landed generator chain for situation shape,
  print-node references, frontier derivation, fixed-point expansion,
  wake-reason/turn-budget data, and the my.run usage walkthrough; and
- the subsequently landed
  [independent fresh-context review](evolving-session-fable-review-2026-08-12.md),
  read end to end. Its turn-proc placement conclusion agrees with the live
  evidence here; its demand-pull critique is an additional constraint on the
  suffix generator, not a different execution owner.

No production source was edited. Live repairs were process-local Var wrappers
and disappeared when the isolated JVM stopped. Durable writes are this report,
the probe, and issue notes.

## Dependency ledger

| Dependency or owner | Selected revision | Boundary exercised |
|---|---|---|
| Seon | `16f022fc9` | `src/seon/bootstrap.clj` owns live-pull candidates and `next-entry`; `src/seon/render/walk.clj` owns explained fixed-point ordering; `src/seon/cluster/loop.clj:1576-1636` owns generated execution; `src/seon/render/web.clj:972-1037` owns retained AI entries. |
| Datahike | root gitlink `cdcb5792db8bd599487f099437265d18a31164a5` | `seon.db/since` supplies the delta database value; transaction datoms join through tx metadata to `:seon.db/user` and `:seon.db/process`. |
| SCI | maintained fork selected by the repository | The live generated form executes in the per-turn fork created at `src/seon/sci/eval.clj:1441-1464`; call preparation reads the environment carried by that fork. |
| core.async Flow | repository-selected revision | The message wake derives `:open`; the passive attribute change derives no agent work. The render proc remains the passive invalidation owner. |

The reproducible T1/T2 probe is
[`tmp/evolving_session_exploration.clj`](../../../../tmp/evolving_session_exploration.clj).

## T0: the live-pull episode in the running system

### Unpatched landed result

I published `current-src` and booted cluster `evolving-session` in isolated
operator root `tmp/evolving-session-root`. Root's actual durable opening was
one form and one terminal receipt:

```clojure
; A new run just opened. Why am I awake — do I have messages?
(help)

{:seon.error/kind :seon.call-preparation/unavailable
 :seon.error/message
 "Cannot call seon.bootstrap/situation: :seon.cluster.agent/id is unavailable. This call's environment carries no agent id; pass one explicitly."
 :seon.error/data
 {:seon.fn/sym "seon.bootstrap/situation"
  :seon.call-preparation/key :seon.cluster.agent/id
  :seon.call-preparation/supplier-symbol seon.env/supplied-agent-id
  :seon.fn.argument/index 1
  :seon.call-preparation/cause
  {:seon.error/kind :seon.env/agent-id-absent
   :seon.error/message
   "This call's environment carries no agent id; pass one explicitly."}}}
```

That is the verbatim semantic value retained in the receipt's print node. A
fresh `explorer` agent reproduced it.

### Process-local environment repair

I wrapped `seon.sci.eval/fork-for-turn` in the live JVM so its returned fork
carried an environment state scoped with the request's agent id. A newly
created and explicitly armed `explorer2` agent then produced this real receipt:

```clojure
; A new run just opened. Why am I awake — do I have messages?
(help)

{:seon.cluster.agent/id "explorer2"
 :seon.cluster.agent/namespace-ref [:seon.ns/name my.agents.explorer2]
 :seon.cluster.agent/unread-message-count 0
 :seon.cluster.run/turns-remaining 99
 :seon.cluster.agent/protocol-namespaces
 [my.message my.run seon.bootstrap seon.db]
 :seon.cluster.agent/open-run-ref
 [:seon.cluster.run/id "bootstrap:explorer2"]
 :seon.cluster.run/trigger
 [:seon.cluster.message/id "bootstrap-task:explorer2"]}
```

The next pass then failed before producing a second entry:

```text
seon.bootstrap/next-entry violated its contract (invalid-input):
[#:seon.render{:output [{:value nil, :message "missing required key"}]}]
```

Adding `:seon.render/output :seon.render/form` in another process-local wrapper
removed that refusal. The resulting live pull for `explorer3` did not return:
the direct prepl call ran about 27 seconds, the JVM sustained about 297% CPU,
and the run remained at one form/one receipt. I stopped the isolated JVM using
`bin/seon --root ... down` rather than killing its child.

Thus there is not yet a successful full T0 episode to quote honestly. The
successful live prefix and all three exact blockers are durable evidence; the
report does not substitute a test fixture for the missing live episode.

## T1: message wake and honest delta

The probe retained basis `536870924`, then root transacted one addressed
message with the normal two provenance refs. The two-source delta returned:

```clojure
#{["t1-message" 536870925 "root" "repl"]}
```

The generated suffix was:

```clojure
(db/q
 '[:find ?id ?tx ?user-id ?process-id
   :in $current $delta ?agent-id
   :where
   [$current ?agent :seon.cluster.agent/id ?agent-id]
   [$delta ?message :seon.cluster.message/to ?agent ?tx]
   [$current ?message :seon.cluster.message/id ?id]
   [$delta ?tx :seon.db/user ?user]
   [$current ?user :seon.cluster.agent/id ?user-id]
   [$delta ?tx :seon.db/process ?process]
   [$current ?process :seon.db.process/id ?process-id]]
 (db/db)
 (db/since (db/db) 536870924)
 "evolving")

; Root sent a new message.
(my.message/read "t1-message")
```

`seon.cluster.work/next-agent-work` independently derived:

```clojure
{:seon.cluster.work/situation :open
 :seon.cluster.agent/id "evolving"
 :seon.cluster.message/id "t1-message"}
```

The retained history was assumed to have already explained `db/q`,
`db/since`, and `my.message/read`. The suffix contains no `dir` or `doc`, so
the explained set suppresses reteaching. After advancing the shown basis to
`536870925`, the identical delta query returned `#{}`. The gap therefore
self-erases from basis arithmetic; no acknowledgement or seen flag is stored.

## T2: passive plan-shaped change with provenance

The registry currently has no agent plan relationship. The probe used the
nearest existing durable ref, `:seon.cluster.agent/instructions`, and filed the
missing fact separately. At shown basis `536870925`, root added instruction
`:passive-plan` to agent `passive` with transaction metadata. The delta was:

```clojure
#{[:passive-plan
   "Inspect the new source facts, then wait for an explicit message."
   536870926
   "root"
   "repl"]}
```

The provenance comment and read were derived as:

```clojure
; Root updated my plan.
(db/pull
 (db/db)
 '[{:seon.cluster.agent/instructions
    [:seon.cluster.instruction/id
     :seon.cluster.instruction/text]}]
 [:seon.cluster.agent/id "passive"])
```

The comment is a render of the datom's transaction joining through
`:seon.db/user`; it is not inferred from the process or hard-coded from the
attribute.

Most importantly, `next-agent-work` returned `nil` before and after the
transaction:

```clojure
{:before nil, :after nil}
```

No run opened. Advancing the shown basis to `536870926` made the provenance
delta return `#{}`. This proves both ruling 36 and self-erasure at the data
level.

## Where T1 generation executes: priced options

### Option 1 — turn-proc generate phase in the message run (recommended)

At T1, open the message-triggered run, then let the agent's turn proc derive,
append, and execute system-authored delta forms at ordinals `0..n` through the
ordinary run fold. Acquire the prompt only after generation reaches its fixed
point, then append the model reply's agent-authored forms at ordinals
`n+1..`. This extends the landed T0 `:generate` situation instead of placing
generation inside the run-opening transaction. At T2, the render proc may
derive a pending delta for display/invalidation purposes but does not claim it
is a settled receipt. The next message wake re-derives and executes that
suffix before prompt acquisition.

- **Guarantee:** only a message opens work; every form shown as settled history
  has a real receipt; the delta prefix is before every form produced by that
  wake's model call; crash recovery remains fact-derived.
- **Cost/risk:** medium-high. `generated-run?` currently classifies a run with
  any system form as forever generated (`src/seon/cluster/work.clj:570-591`),
  and no-entry is currently an error (`src/seon/cluster/loop.clj:1604-1614`).
  The state transition must become `generate -> call`, and plan publication
  must append after the existing prefix instead of starting at ordinal zero.
- **Operational trade-off:** pages can show the current plan block at T2, but
  the agent's settled history gains the form/value only at the next message.
- **Capability given up:** immediate settled-history append for a passive data
  change. This is the only option that preserves the literal "plan change
  alone must NOT open a run" law.

### Option 2 — append directly in the render proc

Run generation during `context-pass` and append its form plus rendered value to
`::ai-entries` immediately at both T1 and T2
(`src/seon/render/web.clj:995-1037`).

- **Guarantee:** immediate passive append, prompt-prefix stability, page morph,
  and no work wake.
- **Cost/risk:** low-medium implementation cost, high semantic risk. This is
  almost the current mechanism.
- **Operational trade-off:** the append is process-local and is lost on crash.
- **Capability given up:** truthful REPL history, ordinary receipts, authorship
  fencing, and forensic reproduction. It reintroduces the defect recorded in
  [render history serializes unexecuted form projections](../../../seon/issues/render-history-serializes-unexecuted-form-projections.md).

Reject this option.

### Option 3 — passive change opens a pure system refresh run

Let the render invalidation path derive the suffix, then submit it to the
existing system-run/receipt owner without a model call. T1 waits for that
refresh run before opening the message run; T2 executes immediately.

- **Guarantee:** immediate T2 settled history, actual receipts, passive model
  behavior, and clean separation between generated and agent-authored runs.
- **Cost/risk:** high. The render proc must not transact; coordinating it with
  the run owner adds a cross-owner completion dependency and another runnable
  situation. It is exactly the kind of semantics that requires an owner gate.
- **Operational trade-off:** data changes perform pure system evaluation even
  when the agent is otherwise idle.
- **Capability given up:** the literal ruling that a plan change alone must not
  open a run. Calling it "not agent work" does not change the database fact
  that a run opened.

Reject unless the owner explicitly narrows ruling 36 to "must not open a model
run."

## Placement conclusion

Option 1 is the simplest coherent constraint and the recommendation. One pure
generator is shared by T1 and T2; the turn proc is the only execution owner,
and the render proc remains read-only. The suffix should be derived
demand-first from the wake's action arc, as the independent review recommends,
so the explained-set closure emits only discovery required by the delta forms
rather than surveying every newly ready candidate. This keeps generation a
function of `(pull, retained-history)` rather than a new owner of work.

The PRD should put one precise question before the owner: does "the plan change
itself appends passively to context" require a terminal form receipt before the
next message? If yes, ruling 36 must be amended or the ordinary-receipt rule
must be weakened. No implementation should silently choose between them.

## Defects filed

- [Generated turn fork omits the agent-scoped environment](../../../seon/issues/generated-turn-fork-omits-the-agent-scoped-environment.md).
- [Generated turn omits the required render output](../../../seon/issues/generated-turn-omits-the-required-render-output.md).
- [Generated opening live pull does not return after help](../../../seon/issues/generated-opening-live-pull-does-not-return-after-help.md).
- [Agent plan has no declared database relationship](../../../seon/issues/agent-plan-has-no-declared-database-relationship.md).
- [Operator status refuses its own readiness result](../../../seon/issues/operator-status-refuses-its-own-readiness-result.md).
- [Fresh agent created after boot was not armed](../../../seon/issues/fresh-agent-created-after-boot-was-not-armed.md).
- [Opening generator pushes undemanded candidates](../../../seon/issues/opening-generator-pushes-undemanded-candidates.md).
- [Opening walkthrough replicates a usage test](../../../seon/issues/opening-walkthrough-replicates-a-usage-test.md).
