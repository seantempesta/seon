---
type: prd
status: active
tags: [prd, agent, architecture, runtime]
---

# generate-code v0 — the whole loop on the local model (2026-07-29)

> **REVISION 2026-07-29 (rev 3, owner rulings batch 4).** Two owner rulings
> (plan README, `4193c3f91`) resolve rev 2's two blockers, and both go
> *against* rev 2's design:
>
> 1. **The fold is OWNER-ROUTED, not stop-at-first-red.** Rev 2's E2 (halt at
>    the first failure) is **SUPERSEDED**. The fold continues per-form; every
>    red form is a **routed problem** to its namespace owner carrying the
>    planner's context; only an owner's explicit can't-fix bubbles to the
>    planner as failure. **Completion = all forms settled**, and false
>    completion dies because unsettled routed problems keep the plan open —
>    not because evaluation halts. §2.3 and the new §2.4 are rewritten to this.
> 2. **Namespace attribution is PARSE-TIME, REPL semantics** (D8 resolved).
>    `(ns a)(defn x)(ns b)(defn y)` associates x with a and y with b, via the
>    ONE reader's per-event namespace-in-effect. **Rev 2's withdrawal of
>    `:seon.cluster.run.form/ns` is reversed** — with the reader, not this
>    plan, as its owner. E1's evaluated-namespace receipt fact survives as the
>    **evaluation-truth complement**; parse/eval disagreement is a detectable
>    anomaly, not a design choice.
>
> Rev 2 (`6ed8fc3ea`) answered the falsification
> ([../research/generate-code-v0-falsification-2026-07-29.md](../research/generate-code-v0-falsification-2026-07-29.md),
> 10 seal-blocking / 2 revision / 6 note); all of its other dispositions
> stand. Changed sections carry **[rev 3]**; §13 keeps the full per-finding
> disposition with rev-3 amendments marked.

**The law of this wave (owner, verbatim): "DO NOT PORT THINGS EXACTLY"** —
the quarry supplies inventory and lessons, this plan derives everything fresh
from the ruled architecture. And: *"I want us to improve what the system can
do through learning from the past."*

`plan/README.md` remains the only ordering.

Evidence authority:

- **plan README rulings batch 4** (`4193c3f91`) — the two rulings above.
- [parse-primitives-plan-2026-07-29.md](parse-primitives-plan-2026-07-29.md)
  — **the owner of namespace attribution.** Its read event carries
  `:seon.sci.reader/ns` ("ns IN EFFECT for this form"), tracked as the read
  proceeds, changing at each `(ns …)`/`(in-ns 'x)`, and — critically —
  yielding **absence** rather than inheritance after a malformed declaration.
  That plan is mid-revision under the same rulings; the two documents are
  coordinated in §2.2 and unit X1.
- [../research/generate-code-v0-falsification-2026-07-29.md](../research/generate-code-v0-falsification-2026-07-29.md)
  — the review rev 2 answered; its six NOTE rows remain the state of record.
- [../research/generate-code-quarry-2026-07-29.md](../research/generate-code-quarry-2026-07-29.md)
  — the archaeology, and the lesson that survives everything: **attempt the
  whole program once, preserve every accepted fact, bisect only red residue
  to owners through queryable provenance.**
- [f1-agent-graph-contracts-2026-07-28.md](f1-agent-graph-contracts-2026-07-28.md),
  [../research/local-provider-2026-07-28.md](../research/local-provider-2026-07-28.md).

**The tree's state.** P2 (test results) and P3 (namespace assignment) landed;
P1 absent. The form entity has `/id`, `/run`, `/ordinal`, `/source`.
`:seon.cluster.message/about` is declared in `schema/error.edn` and written
only by the error recorder. Verified by reading source, because they shape the
design: the evaluator rebinds `sci/ns` per form (`sci/eval.clj:319-342`);
`creation-request` requires `:seon.ns/name` (`schema/agent.edn:8-14`);
`seon.cluster.message/reply` auto-replies to the triggering agent on
completion (`message.cljc:134-183`); `caused-by` walks tx-meta and is total
(`message.cljc:100-119`).

---

## 0. The v0 in five sentences **[rev 3 — rewritten]**

A human messages **root** one goal; root messages the **planner**, an ordinary
agent with its own namespace, and the planner's turn is the whole-program
attempt, whose forms are associated with the namespace each was written under
by the **one reader's parse-time namespace-in-effect** — REPL semantics, the
reader's contract, not this plan's invention. The fold **continues per-form**:
every form that fails becomes a **routed problem** addressed to that
namespace's owner agent, carrying the planner's context, while successes
simply land. An owner repairs in its own namespace and its work lands as
ordinary facts the planner later sees as results; **only an explicit can't-fix
bubbles back as failure**. **A plan is complete when every form is settled** —
succeeded, owner-fixed, or owner-declared-can't — so an unsettled routed
problem keeps the attempt open, which is what kills false completion: not a
halt, not prose, and not delivery evidence. Everything else follows from
landed mechanisms: the auto-reply wakes the caller, `caused-by` scopes the
goal, and the derived settlement is a query anyone can run.

---

## 1. The surface — a message, not a function

| candidate | verdict |
|---|---|
| a `my.generate-code` value the loop interprets | **REJECTED.** The loop would have to launch an agent, install a scheduler and deliver a terminal result — a lifecycle call wearing a value's clothes. |
| a capability through `seon.effect` | **REJECTED for v0.** The door does not exist; building its first arm for this would rebuild `^:async generate-code!`. |
| **orchestration by message between ordinary agents** | **ADOPTED.** |

**The surface is `(my.message/send "planner" "<the goal>")` from root, or a
human's message to root.** Zero new agent-facing constructs for the *surface*
(§12 names the one new agent-facing value the fold semantics require).

### 1.1 The cast

Rev 1 made the planner namespace-less and claimed self-delegation was
structurally impossible; S3 falsified that — the one formal creation path
requires a namespace, and root itself uses it.

| agent | namespace | role |
|---|---|---|
| `root` | `my.agents.root` (landed) | caller; receives the goal and the planner's reply; **delegates** |
| `planner` | `my.gen.planner` | attempts the whole program; **owns any red form no namespace owner claims** |
| `alpha`, `beta` | `my.gen.alpha`, `my.gen.beta` | own the target namespaces; repair |

**Every red form has an owner.** When a form's namespace has no assigned
agent, the **author** — the planner — owns it. This retires rev 2's separate
"unattributable residue" bucket: residue is not a leftover category, it is
ownership falling back to the author, which is both honest and total.

The planner's context is **its own namespace at distance** (the landed
single-root pilot); the goal names target namespaces in prose. No multi-root
view extractor is invented here.

---

## 2. The attempt **[rev 3 — attribution and fold both re-ruled]**

### 2.1 History: two dead designs, one restored

- **Rev 1** lifted the namespace at parse by its own inheritance rule. **S2
  killed it** — the evaluator rebinds `sci/ns` per form, so the fact would
  have contradicted the runtime, and rev 1's own suite property contradicted
  its own inheritance rule.
- **Rev 2** concluded attribution must therefore come *only* from the
  evaluator.
- **Ruling 2 resolves it properly**: attribution is parse-time **REPL
  semantics**, owned by the ONE reader — and the evaluator's truth is kept as
  a complement, so the two are *compared* rather than one guessing.

The distinction that makes ruling 2 right and rev 1 wrong: rev 1 invented a
private inheritance rule inside this capability; ruling 2 puts the
namespace-in-effect in **the one reader every code-bearing text goes through**,
where a malformed declaration yields absence rather than inheritance
(`parse-primitives-plan-2026-07-29.md:117-140`). Same fact, correct owner.

### 2.2 Attribution — parse-time, with evaluation as its complement **[rev 3]**

| layer | fact | owner |
|---|---|---|
| parse | `:seon.sci.reader/ns` on each read event — the namespace in effect, REPL semantics | the parse-primitives plan's reader |
| freeze | `:seon.cluster.run.form/ns` — ref to the upserted `:seon.ns`, projected from the read event | plan freeze (`seon.cluster.run`) |
| eval | the namespace the form **actually** ran in, on its receipt | E1 (§12), the evaluator |

**Coordination.** The parse-primitives plan already cites this one for
`:seon.cluster.run.form/ns` and states the fencing property as a reader
property. Rev 2 withdrew the attribute; **rev 3 restores it**, and the two
plans now agree: the reader produces the namespace, plan freeze projects it,
this plan consumes it. It is a `select-keys` projection, not a translation
layer. Neither document may re-derive the other's half (X1).

**Disagreement is an anomaly, not a design choice.** Today's evaluator rebinds
to `my.agents.<id>` per form, so a parse-attributed `my.gen.alpha` form will
report `my.agents.planner` at eval — a *guaranteed* disagreement until E1
lands. That is precisely why E1 survives as the complement: **the anomaly is
detectable, and v0's drive must report it rather than assume it away.** Until
E1 lands, attribution is the parse fact and the drive states plainly that
execution did not honour it.

### 2.3 The fold — owner-routed, continuing **[rev 3 — supersedes rev 2's E2]**

Rev 2 made "the fold stops at the first red form" a blocking precondition.
**Ruling 1 supersedes it.** The fold continues per-form, and the correction to
the old behavior is not a halt but a **destination**: a red form is neither
silently continued past (the open issue's defect) nor globally aborted (rev
2's over-correction). It is **routed**.

```text
form fails → the form's namespace (parse-time) → that namespace's owner agent
          → an assignment carrying the planner's context and the exact refs
          → the owner repairs in its own namespace, or declares can't-fix
```

Successes and owner-fixed parts **simply land**; the planner later sees results
as diffs. This preserves the quarry's central lesson exactly — accepted facts
are never re-generated — while removing the quarry's dispatcher entirely.

**The open issue resolves by these semantics, and rev 3 states the one
residual honestly.** `a-failed-form-does-not-stop-the-fold` is closed by
routing rather than stopping: form 0's failure becomes a routed problem
instead of a silent continue. But its *evidence case* — form 1 computing on a
missing definition and completing with `Unbound: #'…/primes-below-100` — is
not closed by routing alone, because form 1 still evaluates.

**Rev 3's answer, inside the ruled semantics:** a result that carries an
**unbound-var reference is itself red**, and routes like any other red form.
This is a computed rule at the one admission gate, not a marker layer: the
admit codec already renders a Var as `:seon.sci.admit/reference`, so a value
referencing an unbound var is detectable exactly where every value is already
bounded. That closes the issue's evidence case without halting anything.
**It needs confirmation from the admit owner** that the marker is visible at
that seam (§12, E2′).

**A red form is not always the owner's fault — the cold-resume interaction
[rev 3].** Another lane filed a blocker while this revision was being written:
`cold-resume-loses-the-defs-and-aliases-the-plan-prefix-established`. The fold
threads one sci ctx within a pass, but `:resume` after a process death forks a
fresh ctx and re-evaluates only the target ordinal, so every def, require and
alias the prefix installed is gone — **and so is the reading context the
namespace-in-effect tracking depends on**.

Under this plan's routing model that failure would be **misattributed**: a
form that failed because the process died between ordinals would be addressed
to a namespace owner as though its code were wrong. Routing must therefore
**exclude resume-artifact failures** — a red form whose run shows an
interruption before its ordinal is not the owner's problem — or that blocker
must land first. Recorded as X2 (§12); this plan does not attempt to fix
someone else's owner.

### 2.4 The settled-form state model **[rev 3 — new, the ruling's core]**

Every form of a frozen plan is in exactly one state, and **every state is
derived from presence of facts** — no stored status attribute
(presence-not-kinds, 2026-07-28).

| state | derived from | settled? |
|---|---|---|
| **unevaluated** | no receipt for this ordinal | no |
| **running** | receipt present, no terminal facts | no |
| **succeeded** | terminal receipt with `result-edn`, no `error`, no unbound-var reference in the result | **yes** |
| **routed** | red receipt **and** a live assignment to the form's owner | no — *this is what keeps the plan open* |
| **unrouted red** | red receipt with no assignment yet | no |
| **owner-fixed** | the routed problem no longer derives at the current basis | **yes** |
| **owner-declared-can't** | the owner's explicit can't-fix value (E5) | **yes** |

**Plan settlement** = every form settled. **A derived value — not stored, and
not a run state.**

Two consequences stated precisely, because they are where this model could be
misread:

1. **Plan settlement is independent of run closure.** The planner's run closes
   normally (`wait` or `complete`) and the agent parks — F1's model, custody,
   and the episode dial are untouched; holding a run open across an owner
   round-trip would break all three. What stays open is **the plan's
   settlement**, a derivation over the plan's forms. **A closed run does not
   settle its plan.** This is how "unsettled routed problems keep the plan
   OPEN" is realized without a long-held run.
2. **False completion dies by contradiction, not by halting.** A planner that
   completes while forms are unsettled is contradicted by the derived
   settlement, which root's context renders beside the reply. Combined with
   §10, the wrong answer has nowhere to hide — and unlike rev 1's version, the
   *facts* say so rather than a convention.

**What "owner-fixed" is, objectively.** Not the owner's claim: the routed
problem stops deriving at the current basis — the same "a problem stops being
a problem when the facts stop saying so" rule `seon.problems` is already built
on. Completion stays evidence-derived, as the quarry's hardest lesson demands.

**What "owner-declared-can't" is.** The one place a claim is irreducible:
nothing observable distinguishes "I cannot fix this" from "I have not fixed
this yet". It needs an explicit **value** — never prose the driver parses —
and it collides with the sealed two-disposition surface, where "adding a third
disposition is a design change, not a convenience". **Rev 3 does not invent
it**: §12 E5 names the unit and §11 D10 puts the collision to the owner.

---

## 3. Scope, trigger, assignment **[rev 3 — S6 strengthened by the ruling]**

### 3.1 Attempt scope (S8)

**`caused-by` is the relation**: a problem is in scope for a goal when the run
that produced it has a trigger chain reaching the goal message. Landed, total,
no attempt entity.

Two stated limits: **test results are not chain-reachable** (they come from
`bin/test` runs outside the population) and are scoped by target namespace
instead; and **one live goal per planner** at v0 (D9).

### 3.2 The repair-turn trigger (S1)

Rev 1 claimed F1's self-rewake produced the planner's next turn;
`next-agent-work` has four situations and none reads `seon.problems`, so the
graph parks.

**The landed auto-reply is the trigger.** The planner's run closes →
`seon.cluster.message/reply` derives a reply to **root** → an unanswered
message → root's `:open` situation. No new trigger contract, no protocol the
model must remember.

**No problems-driven wake is proposed**: the loop wakes on exactly one thing
it does not itself commit (L8 disjointness), and a wake derived from
`seon.problems` would violate it.

### 3.3 Assignment needs a commit-time identity (S4, S5)

`my.message/send` returns a closed two-field map and the committed row carries
no `about`; a render-time exclusion is a read, not a fence, and one frozen plan
can hold two sends for the same problem whose ids differ by
`(run, ordinal, index)`.

**E3 (§12)** supplies: an optional **string identity** on `send` naming what
the message is about (agents hold names, never entity ids — the driver
resolves it exactly as it resolves recipients); and a **derived
`(about, recipient)` identity** on the assignment, so a duplicate lands on the
same entity by upsert. **The fence is in the transaction**, and the suite
drives it under latched concurrency.

### 3.4 No silent loss — now structural **[rev 3]**

Rev 1 let delivery exclude a problem forever, so a silent owner erased work
(S6). Rev 2 fixed the visibility half: delegation never removes a problem from
the problems value.

**Ruling 1 makes the guarantee structural rather than presentational.** An
unsettled routed problem keeps the **plan** unsettled. A silent owner does not
merely leave a visible row — it leaves the whole attempt incomplete, by
derivation, forever.

| question | answer |
|---|---|
| may this be **messaged again**? | no, while a live assignment exists — the commit-time identity |
| is this **still a problem**? | yes, until its own facts stop saying so |
| is the **plan complete**? | no, while any form is unsettled |

**Termination comes from the red set shrinking.** If it does not shrink, the
loop does not silently succeed — it **visibly stalls**, with an unsettled plan
naming exactly which forms and which owners.

---

## 4. What v0 explicitly DEFERS

| deferred | unblocked by |
|---|---|
| **Corpus composition** — accepted code is durable source, not callable definitions across runs | **N5** (the v0/v1 line) |
| **Warm namespace repair** | N5 + dependent tracking |
| **P1** — function/schema/call-path attribution; v0 is namespace-granular | P1 + `:seon.fn/calls` |
| **Dependency-ordered admission** — authored order is the order | not scheduled |
| **Accrete-first admission, spec-first economics** — one local model in both roles | after the shape is proven |
| **`seon.effect`** and capabilities inside generated programs | the door's rung |
| **Two live goals per planner; chain-scoped test results** | §3.1's limits |
| **Fan-out beyond the episode headroom** | E4 (trigger coalescing) |

---

## 5. The live proof on local Qwen **[rev 3 — follows the routing model]**

Cluster `generate-code-v0`, disposable path, landed local row. Cast: root,
`planner`, `alpha`, `beta` — all created through the one formal path, all
armed as F1 graphs.

**Act 1 — the attempt.** Root messages the planner a two-namespace goal.
Observe: one run, one plan, per-form receipts, **each form carrying its
parse-time namespace**, and — until E1 — the **reported disagreement** between
parse attribution and the evaluator's actual namespace (§2.2).

**Act 2 — failure through production paths (R1).** A real failing discovered
`deftest` via `bin/test --result-cluster generate-code-v0` (so
`clojure.test/report` → `runner/run!` → `record-tx` all execute), and model
failure injected **at the provider-response boundary** so splitter, freeze,
evaluator and receipt path all see throwing source. Natural Qwen residue is
reported separately as evidence about the model, never about the design.

**Act 3 — routing.** The fold **continues**; red forms become routed problems.
Observe: assignments with commit-time identity, each owner's derived prompt
already containing its own namespace's source, and the **plan derived as
unsettled** with exactly the red forms named.

**Act 4 — the three endings, all run.**

1. **owner fixes** → the problem stops deriving → the form settles → the plan
   settles;
2. **owner declares can't-fix** (E5) → the form settles as declared → the plan
   settles **with a named failure**, which the planner sees;
3. **owner never replies** → the form stays routed → **the plan stays
   unsettled forever**, visibly, and nothing anywhere marks the goal done.
   This is S6's adversarial history, run as a required proof.

**Proof obligations:**

| # | obligation | observed by |
|---|---|---|
| 1 | accepted forms are never re-evaluated | one receipt per `(run, ordinal)` |
| 2 | the derivation transacts nothing | datom census; `:max-tx` unchanged |
| 3 | parse attribution matches the reader's contract, and eval disagreement is **reported** | per-form parse ns vs. receipt ns |
| 4 | at-most-once assignment **under concurrency** | two delegating transactions raced with latches → one entity |
| 5 | a silent owner leaves the plan unsettled | ending 3 |
| 6 | a failed form cannot make a plan settle | the settlement derivation over ending 3 |
| 7 | an unbound-var result is red and routes | the open issue's exact case, replayed |
| 8 | episode headroom respected | root's episode count vs. §9's arithmetic |
| 9 | one model, honestly reported | tokens, wall time, what Qwen got wrong |

Evidence: `../research/generate-code-v0-drive-2026-07-29.md`; the drive is
committed code.

---

## 6. The sealed suite **[rev 3 — settlement properties replace the halt property]**

Written from **required outcomes** (R2 adopted), not from mechanisms:

1. **plan settlement totality** — every form is in exactly one of the seven
   states; settled ⇔ succeeded | owner-fixed | owner-declared-can't. Generated
   receipt/assignment histories, including partial and adversarial ones.
2. **an unsettled form keeps the plan unsettled** — no history of messages,
   replies, or run closures settles a plan with a routed problem outstanding.
   This replaces rev 2's stop-at-first-red test.
3. **at most one live assignment per (problem, owner) under concurrent
   commits** — the production transition with latches, including two sends for
   the same pair in one frozen plan.
4. **owner fix / can't-fix / supersession change the derived state**, and
   nothing else does.
5. **an unbound-var result is red** — the open issue's invariant as a
   value-level property at the admission gate.
6. **attribution follows the reader's namespace-in-effect**, including
   **absence after a malformed declaration** (never inheritance), and the
   parse/eval comparison surfaces disagreement rather than hiding it.
7. **scope totality** — a problem is in scope for at most one goal;
   `caused-by`'s cycle-totality exercised.
8. **author fallback** — a red form whose namespace has no owner is owned by
   the author, never dropped.

Every proof is claimed by `bin/test`; a live-only proof counts as NOT COVERED.

---

## 7. Primitive map — honest readiness **[rev 3]**

| primitive | state | v0's use |
|---|---|---|
| `my.message/send` | **needs E3** | the surface and the assignment |
| `seon.cluster.message/reply` | landed | the repair-turn trigger |
| `caused-by` chain | landed | goal scoping |
| F1 graphs, custody-as-presence | landed | four agents, no dispatcher |
| the fold + receipts | landed; **needs E2′** (routing + the unbound rule) | the attempt |
| the ONE reader's `:seon.sci.reader/ns` | **parse-primitives plan, in flight** | attribution |
| the evaluator's namespace | **E1, non-blocking complement** | anomaly detection |
| the can't-fix value | **needs E5** | settlement's third arm |
| P2 test facts, P3 assignment | landed | red facts and the ownership join |
| render walk + distance, `seon.problems` | landed (gains a family) | root's block; the derivation |

---

## 8. Old-design elements: reconceived or retired

| old element | verdict |
|---|---|
| `^:async generate-code!` wrapper | **RETIRED** — a lifecycle call from inside an eval. |
| `:my.plan/goal` request map + caller id | **RETIRED** — goal is message content; caller is the sender fact. |
| per-goal `:planning` agent | **RETIRED** — one standing planner. |
| root observer + scheduler + recovery registry | **RETIRED** — owners wake on messages; recovery is the ordinary derivation. |
| CAS claim per unit | **RETIRED as a unit mechanism**; the at-most-once property returns as E3's commit-time identity (S5). |
| `parse-program` / `project-program` (1,517 lines) | **RECONCEIVED to a reader property. [rev 3]** Namespace fencing survives as the ONE reader's namespace-in-effect with absence-not-inheritance — not rev 1's private rule (S2), and not rev 2's eval-only attribution (ruling 2). |
| generated dependency ordering | **RETIRED for v0.** |
| `publish-generated-program!` + `:my.plan/needs` | **RETIRED** — the settlement derivation answers it from existing receipts. |
| evidence-derived positive completion | **RECONCEIVED, and rev 3 restores its full strength.** Completion is **plan settlement**: every form succeeded, owner-fixed, or declared. Rev 1 called fold continuation "free" (S7); rev 2 over-corrected to halting; ruling 1 routes instead, which keeps sibling progress *and* kills false completion. |
| the quarry's per-unit stored completion | **RETIRED** — settlement is derived, never stored. |
| repair assignment as a pointer | **RECONCEIVED** — the owner's context IS its namespace view; the assignment carries vision + exact refs. |
| namespace resident birth-on-demand | **RECONCEIVED** as landed P3; unowned ⇒ **author ownership** (§1.1), not a residue bucket. |
| compact terminal `:done` message | **RECONCEIVED** as the landed auto-reply, with §10's semantics. |
| no-reply retry | **RETIRED** — no auto-retry, ever. |
| planner scratch ns as self-recipient | **RETIRED** by an explicit refusal rule (rev 1's structural claim died with S3). |
| `:seon.ai.attempt/*` batch identity | **RETIRED**, replaced by the `caused-by` chain (S8). |
| node budgets, whole-database equality fences | **RETIRED.** |
| the fake Flow prototype's coordination laws | **RECONCEIVED** as §6.1–6.4 against real facts and real concurrency. |

---

## 9. Episode economics **[S9 — rev 2, with a rev 3 note]**

`episode-runs` counts inclusively from the run answering the last **outside**
trigger, and a trigger is outside only when it carries **neither `from` nor
`about`** — so every owner reply is an inside trigger:

```text
runs in one episode = 1 outside-triggered run + N reply runs
```

At the default 100, N = 100 delegates needs 101. Rev 1's D7 ("a firing cap is
just a bug report") is **retracted**.

**Ruling: fan-out per delegating run is bounded by derived headroom.**

```text
max-delegates = max-episode-runs − episode-runs(db, delegator) − 1
```

The remainder stay outstanding — and under ruling 1 they also keep the plan
unsettled, so over-fan-out is **slower, never lost**. The general fix (one run
answering every unanswered trigger at its basis) is **E4**, not v0's problem.

**Rev 3 note:** the routing model *raises* fan-out relative to rev 2, because
the fold no longer halts at the first red form — a bad attempt can produce
several red forms at once. The derived bound therefore matters more here than
it did in rev 2, and obligation 8 asserts it rather than assuming it.

---

## 10. Completion semantics **[rev 3 — settlement supplies the objective check]**

S10: the loop accepts `my.run/complete` from a frozen form and closes without
re-deriving at the terminal transaction's basis, so a completion authored at
basis B can close at B+2 after a contradicting fact at B+1.

**Ruled for v0:**

1. **An agent's completion is not an acceptance claim.** It means "I have
   nothing further to say this turn". **The goal's doneness is plan
   settlement** (§2.4) — derived, runnable by anyone, assertable by no agent.
2. **Root's context always renders settlement beside the reply.** A "done"
   reply next to an unsettled plan is one agent's prose next to the facts; the
   drive asserts the composition.
3. **The general class is filed, not absorbed** — a frozen disposition closing
   against newer facts is true of every agent
   (`docs/seon/issues/a-frozen-disposition-can-close-against-newer-facts.md`,
   filed by rev 2). If its owner rules that the terminal transition must
   refuse stale completions, this plan inherits the fix.

Rev 3 strengthens rev 2 here: rev 2 could only offer "trust the query"; ruling
1 makes the query **structurally decisive**, because an unsettled plan is not
complete no matter what any agent said.

---

## 11. Owner decisions **[rev 3 — D8 resolved, D10 new]**

**D1 — `generate-code` as a name?** *Yes for the loop, no for any code.*

**D2 — namespace-less planner?** **RETRACTED (S3).** The planner owns
`my.gen.planner`; self-delegation is an explicit refusal rule.

**D3 — namespace-granular attribution at v0 (P1 deferred).** *Confirm.*

**D4 — unowned namespace?** **REVISED [rev 3]:** owned by the **author**
(§1.1), which is total and removes the residue bucket.

**D5 — one local model in both roles.** *Confirm for v0.*

**D6 — staged failure through production paths only** (R1). *Confirm.*

**D7 — episode cap.** **RETRACTED (S9)** for §9's derived bound.

**D8 — E1 or the smaller v0-B?** **RESOLVED by ruling 2 [rev 3]:** attribution
is parse-time REPL semantics via the ONE reader; E1 survives as the
evaluation-truth complement and is **no longer blocking**. v0-B is withdrawn.

**D9 — one live goal per planner at v0.** *Recommendation: accept and record.*

**D10 — how does an owner say "I can't fix this"? [new]** The settled-form
model needs its third arm, and it collides with the sealed two-disposition
surface. Options, recommendation first:

1. **A third disposition** (`my.run` gains one member). Honest, minimal, and
   symmetric with `complete`/`wait` — but it breaks a seal the owner set
   deliberately ("adding a third disposition is a design change").
2. **A resolution value on the assignment** — a distinct agent-facing value
   meaning "this assignment is declined", leaving `my.run` sealed. Costs a
   fourth agent-facing value family.
3. **Derive it from silence plus a bound** — **rejected**: a timeout standing
   in for a declaration is exactly the tuned constant the standing ruling
   bans, and it resurrects the silent-loss class S6 named.

*Recommendation: option 1*, because the fact being expressed genuinely is a
run disposition. Either way it is **E5**, its own unit.

### Name table

| name | what it is | grounded in |
|---|---|---|
| `:seon.sci.reader/ns` | namespace in effect per read event | the parse-primitives plan (its owner) |
| `:seon.cluster.run.form/ns` | the projected ref at plan freeze | **restored [rev 3]**; sits beside `/id`, `/run`, `/ordinal`, `/source` |
| evaluated namespace on the receipt | evaluation truth, the complement | `sci/eval.clj`'s own `sci/ns` binding |
| **settled** / **routed** / **owner-fixed** / **owner-declared-can't** | the form states of §2.4 | the owner's own words in ruling 1 |
| the assignment identity | derived `(about, recipient)`, upsert-based | the existing derived-identity idiom |
| **"residue"** | *retired* — say **author-owned red form** | R34; §1.1 |
| ~~"stop-at-first-red"~~ | **superseded by ruling 1** | — |

---

## 12. Preconditions — separate units with owners **[rev 3 — E2 replaced, E5 new]**

| id | unit | owner | status |
|---|---|---|---|
| **E2′** | **owner-routed fold [replaces E2]**: a red form emits a routed problem to its namespace owner (author fallback); plus the **unbound-var result is red** rule at the admission gate. Closes `a-failed-form-does-not-stop-the-fold` by routing, per ruling 1. | `seon.cluster.loop` + `seon.sci.admit` (the unbound rule) | **BLOCKING** |
| **E3** | `about`-carrying sends + commit-time `(about, recipient)` assignment identity | `my.message` + `seon.cluster.message` + `schema/message.edn` | **BLOCKING** (S4, S5) |
| **E5** | **the can't-fix value [new]** — settlement's third arm; see D10 | `my.run` (or a new value family), owner-ruled | **BLOCKING** — without it, a plan containing an unfixable form can never settle |
| **E1** | evaluated-namespace on the receipt | `seon.sci.eval` + `schema/run.edn` | **no longer blocking [rev 3]** — the complement that makes parse/eval disagreement detectable. Until it lands, v0 reports the disagreement rather than detecting it per-form. |
| **E4** | trigger coalescing — one run answers every unanswered trigger at its basis | `seon.cluster.work` | not blocking (S9) |
| **I1** | issue: a frozen disposition can close against newer facts | the run-closing owner | not blocking under §10 |
| **X1** | **coordination**: the parse-primitives plan keeps `:seon.sci.reader/ns`; this plan keeps the freeze projection; neither re-derives the other's half | both plans | in flight, same rulings |
| **X2** | **dependency [rev 3]**: `cold-resume-loses-the-defs-and-aliases-the-plan-prefix-established` (blocker, filed by another lane). Either it lands, or E2′'s routing excludes resume-artifact failures — otherwise a process death is misattributed to a namespace owner (§2.3) | that issue's owner | **blocks correct attribution**, not the loop's shape |

---

## 13. Per-finding disposition **[rev 3 amendments marked]**

| # | finding | disposition |
|---|---|---|
| **S1** | no fact wakes the planner for the repair turn | **ACCEPTED** — the landed auto-reply wakes root, which delegates. No problems-driven wake (L8). |
| **S2** | parse-time vs evaluation-time namespace | **ACCEPTED, remedy amended [rev 3].** Rev 1's private inheritance rule stays dead. Rev 2's eval-only attribution is **superseded by ruling 2**: attribution is parse-time REPL semantics owned by the ONE reader, with the evaluator's namespace as the complement and disagreement as a detectable anomaly. `:seon.cluster.run.form/ns` is **restored**. |
| **S3** | namespace-less planner contradicts creation | **ACCEPTED** — the planner owns a namespace; self-delegation is an explicit refusal. |
| **S4** | ordinary sends cannot carry `about` | **ACCEPTED** — precondition E3. |
| **S5** | render-time exclusion is not a fence | **ACCEPTED** — commit-time identity, latched-concurrency proof. |
| **S6** | non-replying owner ⇒ silent loss | **ACCEPTED, strengthened [rev 3].** Rev 2 kept the problem visible; ruling 1 makes an unsettled routed problem keep the **plan** unsettled, so a silent owner cannot produce a completed goal at all. Required proof (§5, ending 3). |
| **S7** | the fold continues and can complete with a lie | **ACCEPTED; rev 2's remedy SUPERSEDED [rev 3].** Not stop-at-first-red: the fold continues and every red form routes, so completion dies by unsettled work rather than by halting. The issue's evidence case is closed by the unbound-var-result rule (§2.3, E2′). |
| **S8** | no attempt scoping | **ACCEPTED** — `caused-by` chain, with two stated limits. |
| **S9** | the cap starves 100 delegates | **ACCEPTED** — D7 retracted; derived headroom bound, and rev 3 notes routing raises fan-out (§9). |
| **S10** | done not enforced at the terminal transition | **ACCEPTED** — completion is not acceptance; **plan settlement** is the objective check [rev 3]; the general class is issue I1. |
| **R1** | injected test failure bypasses the P2 path | **ACCEPTED** — production paths only. |
| **R2** | properties can pass while work is lost | **ACCEPTED** — suite rewritten from required outcomes; rev 3 replaces the halt property with the settlement properties (§6.1–6.2). |
| **N1–N6** | current-tree verification | **ADOPTED** as the state of record. |

**Nothing in the review is refuted.** Two of its readings were extended by rev
2 (`reply` supplies S1's trigger; `caused-by` supplies S8's scope), and two of
rev 2's own remedies are now superseded by owner ruling rather than by
falsification (attribution owner; fold semantics).

---

## 14. Seal assessment **[rev 3]**

**Both of rev 2's blockers are resolved by the rulings; one new blocker
appears from the ruling itself (E5).**

| reseal question | answered | by |
|---|---|---|
| 1. what triggers the first repair turn? | yes, landed | §3.2 |
| 2. does one run execute several namespaces? | **resolved differently [rev 3]** — attribution is parse-time REPL semantics; execution truth is the complement, and disagreement is reported | §2.2, D8 |
| 3. what scopes one goal across runs? | yes, landed | §3.1 |
| 4. at-most-once, and delivered/outstanding/repaired? | **design given, needs E3 + E5** | §3.3, §2.4 |
| 5. what prevents a stale disposition closing? | **plan settlement**; class filed | §10 |
| 6. what fold ruling closes the false-completion issue? | **RULED** — owner-routed fold | §2.3 |
| 7. what fan-out bound composes with the cap? | yes, derived | §9 |

**Seal requires three contracts authored by their owners: E2′** (routing plus
the unbound-var rule — needs the admit owner's confirmation that the marker is
visible at that seam), **E3** (about-carrying sends and the assignment
identity), and **E5** (the can't-fix value, gated on D10). E1 is no longer
blocking; D8 is closed.

**One element is deliberately left open rather than guessed:** D10, because it
touches a surface the owner sealed on purpose. Everything else is landed,
derived from landed facts, or owned by a named unit.

---

## 15. Sequencing

**Blocked on E2′, E3, E5.** Not blocked on P1, N5, E1, or the effect door, and
must not acquire those dependencies during implementation. Coordinated with
the parse-primitives plan through X1. It occupies a parallel product slot,
never the F-series spine; its first honest v1 begins when N5's corpus round
trip makes accepted code callable.
