---
type: prd
status: active
tags: [prd, agent, architecture, runtime]
---

# generate-code v0 — the whole loop on the local model (2026-07-29)

> **REVISION 2026-07-29 (rev 2, after falsification).** Rev 1 (`aa8f2c24f`)
> was **REJECTED FOR SEAL** by
> [../research/generate-code-v0-falsification-2026-07-29.md](../research/generate-code-v0-falsification-2026-07-29.md)
> (`042f57726`): 10 SEAL-BLOCKING, 2 REVISION, 6 NOTE, with 44 tests / 168
> assertions of verification behind them. **I verified the load-bearing
> findings against source myself and accept every one of them.** Three of rev
> 1's mechanisms are dead and are redesigned here, not patched: parse-time
> namespace attribution (S2), delegation-by-ordinary-send with `about` (S4,
> S5), and delivery-as-termination (S6). Two of rev 1's claims were simply
> false about the tree: the planner had no trigger for its repair turn (S1),
> and a namespace-less planner contradicts the one formal creation path (S3).
> Every changed section carries a **[rev 2]** marker; §13 gives a
> per-finding disposition; §12 names the preconditions that are now separate
> units with owners. **This document is not sealed** — §14 states what
> remains before it can be.

**The law of this wave (owner, verbatim): "DO NOT PORT THINGS EXACTLY"** —
the quarry supplies inventory and lessons, this plan derives everything
fresh from the ruled architecture. And: *"I want us to improve what the
system can do through learning from the past."* Every element of the old
design below is either RECONCEIVED (§8 says how) or RETIRED (§8 says why).
Nothing is transcribed.

`plan/README.md` remains the only ordering; this is a contract-shaped plan
for one product capability, not a second ledger.

Evidence authority:

- [../research/generate-code-v0-falsification-2026-07-29.md](../research/generate-code-v0-falsification-2026-07-29.md)
  — **the review this revision answers.** Its six NOTE rows are the current
  landed/absent boundary and are treated as the tree's state of record.
- [../research/generate-code-quarry-2026-07-29.md](../research/generate-code-quarry-2026-07-29.md)
  — the archaeology: the 771-line `seon.ai/generate-code!` orchestrator, the
  coordinator-free Flow prototype, what live-proved, what never graduated,
  and the one lesson that survives everything: **evaluate the ambitious
  whole-program pass once, preserve every accepted fact, and bisect only red
  residue to owners through queryable provenance.**
- [../research/renderable-corpus-plan-2026-07-28.md](../research/renderable-corpus-plan-2026-07-28.md)
  §7 — delegation preconditions P1–P4 and the §7.4 falsifier.
- [f1-agent-graph-contracts-2026-07-28.md](f1-agent-graph-contracts-2026-07-28.md)
  — every agent is its own flow; the episode dial; the turn proc.
- [../research/local-provider-2026-07-28.md](../research/local-provider-2026-07-28.md)
  — the local Qwen row: 42.6 tok/s, `:seon.config.ai/no-auth true` landed.

**The tree's state, as verified by the review and re-verified here.** P2 (test
results) and P3 (namespace assignment) are landed. P1 is absent. The form
entity has exactly `/id`, `/run`, `/ordinal`, `/source` — `/ns` was never a
precondition, only rev 1's proposal. `:seon.cluster.message/about` is declared
in `schema/error.edn` and written **only** by the error recorder; the ordinary
message entity does not carry it. And three findings I confirmed by reading
source, because they kill mechanisms:

- **`sci/eval.clj:319-342` rebinds `sci/ns` to `my.agents.<id>` for every
  form.** A shared ctx preserves *definitions*, not the dynamic namespace
  binding. `(ns my.gen.alpha)` in form 0 does not put form 1 in that
  namespace. **Multi-namespace execution is not real today** (S2).
- **`:seon.cluster.agent/creation-request` requires `:seon.ns/name`**
  (`schema/agent.edn:8-14`); root itself is created that way. A
  namespace-less agent needs a second creation path (S3).
- **`docs/seon/issues/a-failed-form-does-not-stop-the-fold.md` is OPEN**,
  with live evidence of a run completing on an `Unbound` marker and
  delivering it to another agent as a confident answer (S7).

Two landed mechanisms the review's own reading surfaces, which rev 2 builds
on and rev 1 missed:

- **`seon.cluster.message/reply` auto-replies to the triggering agent on
  completion** (`message.cljc:134-183`), deriving the recipient from the
  trigger. A completed run wakes its caller with no protocol to remember.
- **`caused-by` walks transaction metadata** (`message.cljc:100-119`):
  message → its creating tx → that tx's `:seon.db/trigger` → the causing
  message, total against cycles by a `seen` set. **The causal chain is a
  landed, durable relation** — which is what rev 1 lacked when it retired
  the attempt entity with nothing in its place.

---

## 0. The v0 in five sentences **[rev 2 — rewritten]**

A human messages **root** one goal; root messages the **planner**, an
ordinary agent with its own namespace, and the planner's turn is the
whole-program attempt. The attempt evaluates through the existing fold, and
**attribution comes from the evaluator** — the namespace a form actually ran
in, recorded on its receipt — never from a second parser's syntactic guess,
which is why multi-namespace execution has to become real before the loop can
exist at all (precondition **E1**). When the planner's run closes, the landed
auto-reply wakes **root**, whose context carries the attributed-problems
block; **root delegates**, because root is the caller and needs no new trigger
contract, and a problems-driven wake would break the loop's L8 disjointness.
Delegation is an assignment with a **commit-time identity** — at most one live
assignment per (problem, owner) by upsert, not by a render-time read — and it
**never removes a problem from the problems value**: an unrepaired problem
stays visible as outstanding forever, so a silent owner cannot make work
disappear. **Done is empty red at the acceptance basis, derived by anyone**;
no agent's prose is ever acceptance, and the loop stops because red is gone,
not because messages were sent.

---

## 1. The surface — a message, not a function **[rev 2 — S3 fixed]**

**Judged against the three agent-facing shapes law** (values the driver
interprets / capability requests through the one door / durable facts the
driver commits):

| candidate | verdict |
|---|---|
| a `my.generate-code` value the loop interprets | **REJECTED.** The loop would have to launch an agent, install a scheduler and deliver a terminal result — runtime semantics performed from inside an eval. That is precisely the old-engine residue the conversion law names: a lifecycle call wearing a value's clothes. |
| a capability through `seon.effect` | **REJECTED for v0.** The door does not exist yet, and building its first arm to carry "run a whole strong-model pass" would rebuild `^:async generate-code!` with a new spelling. |
| **orchestration by message between ordinary agents** | **ADOPTED.** A whole-program attempt IS an agent taking a turn. `my.message/send` already commits, wakes and carries content; F1 gives each agent its own flow; and the landed auto-reply closes the loop back to the caller. |

**The surface is `(my.message/send "planner" "<the goal>")` from root, or a
human's message to root.** Zero new agent-facing constructs.

### 1.1 The cast, and why the planner owns a namespace **[rev 2 — S3]**

Rev 1 made the planner namespace-less and claimed self-delegation was
therefore *structurally* unrepresentable. **That was false about the tree**:
the one formal creation path requires a namespace name, root itself uses it,
and an ownerless planner would need a second creation path — precisely the
"one mechanism" violation this program refuses.

| agent | namespace | role |
|---|---|---|
| `root` | `my.agents.root` (landed) | the caller; receives the goal; receives the planner's reply; **delegates** |
| `planner` | `my.gen.planner` | attempts the whole program |
| `alpha`, `beta` | `my.gen.alpha`, `my.gen.beta` | own the target namespaces; repair |

Self-delegation is now an **explicit refusal, not a structural claim**: an
attributed problem whose owner is the delegating agent is held as
unattributable residue and rendered loudly. Stating it as a rule is honest;
rev 1's structural claim was not.

**Context is a render, and the planner's view is single-rooted.** Rev 1 wanted
several namespace roots extracted from free-form goal text; the landed pilot
walks from one agent (`render/agent.clj:118-134`) and the prompt invokes
installed blocks for that one agent. Rev 2 does not invent a multi-root
extractor: the planner sees **its own namespace at distance**, and the goal
text names the target namespaces in prose. Multi-root views are a render
question for N5, not a dependency of this loop.

---

## 2. The attempt **[rev 2 — attribution moved to the evaluator]**

### 2.1 The dead mechanism, and why

Rev 1 proposed `:seon.cluster.run.form/ns`, lifted at parse from the preceding
`(ns …)`/`(in-ns …)` form. **S2 kills it, and the probes are decisive**: the
evaluator rebinds `sci/ns` per form, so a `def` after `(in-ns 'my.gen.alpha)`
lands in `my.agents.planner`. A parse-time ref would have committed a **false
attribution** — a stored fact contradicting the runtime — and rev 1's own
suite property (a namespace must appear in the form's own source prefix)
contradicted rev 1's own inheritance rule. Both are retired.

The deeper error: **a stored attribution cannot manufacture an execution
semantics that does not exist.** Reseal question 2 is the real question, and
it must be answered before anything else in this plan is buildable.

### 2.2 Precondition E1 — multi-namespace execution, and attribution from its owner

**v0 requires that one run can really author several namespaces**, because
otherwise the whole-program attempt is a single-namespace attempt and there is
nothing to bisect. That is a change to the **evaluator contract**, and it is
its own unit (§12, E1), not something this plan absorbs:

- the run's namespace becomes **stateful across its forms** — the evaluator
  threads the namespace it ended in, exactly as it already threads the ctx
  that preserves definitions;
- each receipt records **the namespace the form actually evaluated in**, so
  attribution is an *evaluation* fact from the *same owner* that produced the
  behavior (reseal Q2's requirement, verbatim);
- the one-parser ruling is untouched: the parse still lifts *workload*
  classification; it does not guess namespaces.

**If the owner declines E1**, the fallback is v0-B: the planner authors no
code, only a decomposition, and each owner authors its own namespace in its
own run — where the home namespace is correct by construction. That is a
smaller v0 and it forfeits the quarry's central lesson (attempt once, preserve
accepted work, bisect residue). §11 D8 puts the choice to the owner.

### 2.3 Precondition E2 — the fold must stop at the first red form

Rev 1 called continuation after a failed form "free" and used a later
completion as acceptance evidence. **S7 is right and rev 1's judgment was
wrong**: the open issue's live evidence is a run that completed on
`Unbound: #'my.agents.bob/primes-below-100` and delivered it to another agent
as an answer. A loop that can do that cannot host a capability whose entire
output is "which parts are accepted".

**This plan therefore DEPENDS on that issue being ruled and fixed first**
(§12, E2). Sibling preservation does not require evaluating forms authored
against a state that failed to materialize: accepted earlier receipts stay
terminal while the fold stops at the first red form.

**A consequence rev 2 states rather than hides:** with stop-at-first-red, one
attempt yields *one* red form, not N. The loop runs more rounds, each with
smaller fan-out. That is a cost — and it is also what makes §9's episode
arithmetic survivable at v0 scale.

---

## 3. Bisection, delegation, and the three things rev 1 got wrong **[rev 2 — rewritten]**

### 3.1 Attempt scope — the landed relation rev 1 lacked (S8)

Rev 1 retired the attempt entity with nothing in its place, so "the attempt's
problems are empty" was not a query anyone could run. **`caused-by` is that
relation** (`message.cljc:100-119`): every run's opening transaction names its
trigger as `:seon.db/trigger` tx-meta, and the walk is total.

**A problem is in scope for a goal when the run that produced it has a trigger
chain reaching the goal message.** No new fact, no attempt entity.

**Two honest limits, stated because S8 is right that scope must be specified,
not assumed:**

- **test results are not chain-reachable.** They come from `bin/test` runs,
  outside the agent population. v0 scopes them by **target namespace** — the
  namespaces the goal names — and that is a weaker relation which two
  concurrent goals over the same namespace would confuse. v0 forbids that
  configuration rather than pretending to resolve it;
- **one standing planner serving two live goals** is out of scope for v0 for
  the same reason. §11 D9.

### 3.2 The trigger for the repair turn (S1)

Rev 1 claimed F1's self-rewake would produce the planner's next turn. **False:**
`next-agent-work` has four situations, none of which reads `seon.problems`, so
after the last receipt and close the graph parks. Nothing happened next.

**Rev 2 uses the landed auto-reply instead, and moves the delegating role to
root.** When the planner's run closes, `seon.cluster.message/reply` derives a
reply to the agent that triggered it — **root** — and that reply is an
ordinary unanswered message, which is exactly the `:open` situation. **Root
wakes. No new trigger contract, no new fact, no protocol the model must
remember.**

Why root rather than a problems-driven wake: the loop wakes on exactly one
thing it does not itself commit (L8 disjointness). A wake derived from
`seon.problems` would be the loop waking on facts the loop commits — the
disjointness violation the run model exists to avoid. **Rev 2 does not propose
one.**

Why root rather than a planner self-message: a self-message works and is
ruled-legitimate, but it depends on the model emitting it every time, and the
landed reply is derived rather than remembered — the exact argument
`message.cljc:145-158` already makes about why completion replies are derived.

### 3.3 Delegation needs a real assignment (S4, S5)

**S4 is correct: ordinary sends cannot carry `about`.** `my.message/send`
returns a closed two-field map; the delivery request has no `about`; the
committed row writes id/to/from/content/at. Rev 1's Act 3 was unbuildable, and
its "everything landed except `:attributed`, `/ns` and the drive" was false.

**S5 is correct that a render-time exclusion is not an idempotency fence.** A
read proves what the read returned, not what a competing terminal transaction
can commit — and one frozen plan can contain two send forms for the same
problem, whose ids differ by `(run, ordinal, index)`, so both commit.

Rev 2's answer is **one accretion with a commit-time identity** (§12, E3):

- `my.message/send` gains an optional third argument naming **what the
  message is about**, as a **string identity** — never an entity id, because
  agent code holds names, exactly as it holds recipient names and lets the
  driver resolve them. The producing facts already have string identities:
  `:seon.cluster.eval/id`, `:seon.test.result/id`, `:seon.error/id`;
- the driver resolves it to the entity and writes `about` on the row, the
  same resolution it already performs for the recipient;
- **at-most-once is a schema fact, not a query**: the assignment carries a
  **derived identity** over `(about-entity, recipient)`. Datahike identity
  gives upsert semantics, so a second assignment for the same pair — from a
  duplicate send form, a re-derivation, or a second delegating agent — lands
  on the same entity instead of creating a second one. **The fence is at
  commit, in the transaction, where S5 requires it.**

The suite must therefore drive the **production transition under concurrency
with latches**, not a sequential model (S5's closing sentence, adopted
verbatim in §6).

### 3.4 Delegation is not termination (S6)

**S6 is the most important finding in the review**, because rev 1's design
turned a silent owner into permanent invisible loss: delivery excluded the
problem forever, and the stopping rule called that done.

**Rev 2 separates the two things rev 1 conflated:**

| question | answer |
|---|---|
| may this problem be **messaged again**? | No, if a live assignment exists for (problem, owner) — the commit-time identity |
| is this problem **still a problem**? | Yes, until its own facts stop saying so |

**Delegation never removes a problem from the problems value.** It adds an
owner and a delivered marker, changing how the row *renders*, never whether it
exists. An owner that waits forever, fails, is paused, or refuses leaves an
**outstanding** row visible to root and to any human, permanently.

**The stopping rule is therefore: no red facts in scope at the acceptance
basis.** Not "everything has been delegated". Rev 1's rule is retired.

Termination no longer comes from exclusion. It comes from the red set
shrinking — and if it does not shrink, the loop does not silently succeed, it
**visibly stalls**, which is the outcome a human can act on.

---

## 4. What v0 explicitly DEFERS

| deferred | why, and what unblocks it |
|---|---|
| **Corpus composition** — accepted code is durable *source*, not callable definitions across runs. | **N5.** The honest v0/v1 line. |
| **Warm namespace repair** — the quarry's headline promise, never proven. | N5 plus dependent tracking. |
| **P1 — function/schema/call-path attribution.** v0 attributes at namespace granularity. | P1's normalize-time refs + `:seon.fn/calls`. |
| **Dependency-ordered admission.** Authored order is the order. | Not scheduled. |
| **Accrete-first admission and spec-first economics.** One local model in both roles. | After the shape is proven. |
| **`seon.effect`** and capabilities inside generated programs. v0 programs are pure. | The door's own rung. |
| **Two live goals on one planner; test results scoped by chain.** | §3.1's stated limits; needs a scoping relation for non-agent-produced facts. |
| **Fan-out beyond the episode headroom** (§9). | Trigger coalescing, E4 — a candidate unit, not v0. |

---

## 5. The live proof on local Qwen **[rev 2 — R1 adopted]**

Cluster `generate-code-v0`, disposable path, landed local row.

**Cast:** root (landed namespace), `planner` (`my.gen.planner`), `alpha`
(`my.gen.alpha`), `beta` (`my.gen.beta`) — **all created through the one
formal path**, all armed as F1 graphs.

**Act 1 — the attempt.** Root messages the planner a two-namespace goal.
Observe: one run, one plan, per-form receipts, **each receipt carrying the
namespace it actually evaluated in** (E1), and the fold **stopping at the
first red form** (E2).

**Act 2 — failure, through the production paths (R1 adopted).** Rev 1 planned
to transact a `:seon.test.result` directly; **S/R1 is right that this proves
only the consumer.** Rev 2 instead:

- **test failure**: a real failing discovered `deftest`, run through
  `bin/test --result-cluster generate-code-v0`, so `clojure.test/report` →
  `runner/run!` → `record-tx` all execute;
- **model failure**: injected **at the provider-response boundary**, so the
  ordinary splitter, freeze, evaluator and receipt path see throwing source.

Natural residue from Qwen is reported separately as evidence about the model,
never as evidence about the design.

**Act 3 — reply, delegation, assignment.** The planner closes; the landed
reply wakes root; root's block shows attributed problems; root delegates with
`about`-carrying sends (E3). Observe two assignments, each with the derived
identity, and **each owner's derived prompt already containing its own
namespace's source**.

**Act 4 — repair, and the two honest endings.** Owners fix; the derived red
set empties; root's block goes empty. **And the adversarial ending is run
too**: one owner deliberately never completes, and the proof requires that its
problem **remains visible as outstanding** and is never silently dropped
(S6's history, run as a test rather than argued away).

**Proof obligations:**

| # | obligation | how it is observed |
|---|---|---|
| 1 | accepted forms are never re-evaluated | one receipt per `(run, ordinal)` across the drive |
| 2 | the derivation transacts nothing | datom census; `:max-tx` unchanged |
| 3 | attribution equals the evaluator's actual namespace | receipt's namespace vs. where the `def` resolved (S2's probe, inverted into a check) |
| 4 | at-most-once assignment **under concurrency** | two delegating transactions raced with latches → one assignment entity |
| 5 | a silent owner does not lose work | the non-replying owner's problem still outstanding at the end |
| 6 | a failed form cannot produce a completed run or a delivered reply | E2's regression, driven live |
| 7 | episode headroom respected | root's episode count vs. the dial, with the arithmetic of §9 |
| 8 | one model, honestly reported | tokens, wall time, and what Qwen actually got wrong |

Evidence lands in `../research/generate-code-v0-drive-2026-07-29.md`; the
drive is committed code.

---

## 6. The sealed suite **[rev 2 — rewritten from required outcomes, R2 adopted]**

R2 is adopted in full: rev 1's properties could pass while work was lost.
The suite is rewritten **from the required outcomes**, not from the
mechanisms:

1. **every unresolved problem remains visible as outstanding** — generated red
   sets with generated delegation histories, including owners that never
   settle; no history makes a red fact invisible.
2. **at most one live assignment per (problem, owner) under concurrent
   commits** — the production transition, driven with latches, including two
   send forms for the same pair in one frozen plan.
3. **owner completion / refusal / supersession changes the derived state** —
   the state transitions that *do* retire an assignment, proven to be the only
   ones that do.
4. **a failed form cannot yield a completed run or a delivered reply** (E2's
   invariant, the open issue's own falsifier).
5. **attribution equals the evaluator's actual namespace** — not a parser's
   syntactic guess; the property is written against the evaluator's output, so
   it cannot contradict its own design the way rev 1's did.
6. **scope totality** — every problem is in scope for at most one goal, or is
   out of scope; `caused-by`'s cycle-totality is exercised.
7. **self-delegation refusal** and unowned-namespace residue.

Every proof is claimed by `bin/test`. A live-only proof counts as NOT COVERED.

---

## 7. Where the primitives are exercised — the honest map **[rev 2 — corrected]**

Rev 1's version of this table asserted "everything landed except three
things", which S4 correctly called false. The corrected map:

| primitive | state | v0's use |
|---|---|---|
| `my.message/send` | **needs E3** | the surface and the delegation |
| `seon.cluster.message/reply` | landed | the repair-turn trigger (S1's answer) |
| `caused-by` chain | landed | goal scoping (S8's answer) |
| F1 per-agent graphs, custody-as-presence | landed | four agents, no dispatcher |
| the fold + receipts | **needs E2** | the attempt, and preservation |
| the evaluator | **needs E1** | multi-namespace execution and attribution |
| P2 test facts, P3 assignment | landed | red facts and the ownership join |
| the render walk + distance | landed | root's block and each owner's local view |
| `seon.problems` | landed, gains a family | the derivation |
| the local no-auth provider row | landed | the model |

**Three of the ten rows are preconditions.** That is the honest reading of
this plan's readiness, and it is why §14 does not claim seal.

---

## 8. Every old-design element: reconceived or retired

*(Unchanged from rev 1 except the marked rows.)*

| old element | verdict |
|---|---|
| public `^:async generate-code!` wrapper | **RETIRED.** An effectful lifecycle call from inside an eval. Replaced by a message. |
| `:my.plan/goal` request map + injected caller id | **RETIRED.** The goal is message content; the caller is the message's sender fact. |
| launching a `:planning` agent per goal | **RETIRED.** One standing planner; per-goal disposables recreate the task-agent shape F1 rejects. |
| root observer + `:execution` scheduler + recovery registry | **RETIRED.** Owners wake on messages; recovery is the ordinary derivation. |
| CAS claim of each unit | **RETIRED as a unit mechanism**; one open run per agent is the fence. **[rev 2]** The at-most-once property it was reaching for returns as E3's commit-time assignment identity — S5 was right that rev 1 had *no* fence at all. |
| `parse-program` / `project-program` | **RETIRED entirely. [rev 2]** Rev 1 kept its namespace-fencing idea as a parse-time fact; S2 killed that. Attribution is an evaluation fact (E1). |
| generated dependency ordering | **RETIRED for v0.** |
| `my.plan/publish-generated-program!` + `:my.plan/needs` | **RETIRED.** The problems query derives the same answer from existing receipts. |
| evidence-derived positive completion | **RECONCEIVED, and rev 2 restores what rev 1 weakened.** Completion is absence of red in scope at the acceptance basis. Rev 1 also called fold continuation "free" and leaned on a later completion as evidence — S7 showed that is exactly how the old system's false greens happened. E2 closes it. |
| repair assignment as a pointer | **RECONCEIVED.** The owner's context IS its namespace view at distance; the message carries vision + refs. |
| namespace resident birth-on-demand | **RECONCEIVED** as landed P3. Unowned namespace ⇒ root residue; auto-birth is D4. |
| compact terminal `:done` message | **RECONCEIVED** as the landed auto-reply. **[rev 2]** Rev 1 said "a reader trusts the derived value, not prose"; S10 showed the runtime still records and delivers a stale completion. §10 rules the semantics and files the general class. |
| no-reply retry path | **RETIRED.** No auto-retry, ever. |
| planner scratch namespace as self-recipient | **RETIRED by an explicit refusal rule. [rev 2]** Rev 1's structural claim depended on a namespace-less planner, which S3 falsified. |
| `:seon.ai.attempt/*` batch identity | **RETIRED**, and **[rev 2]** replaced by the `caused-by` chain rather than by nothing (S8). |
| exact node budgets, whole-database equality fences | **RETIRED.** |
| the fake Flow prototype's coordination laws | **RECONCEIVED** as §6 items 1–3 against real facts and real concurrency. |

---

## 9. Episode economics — ruled, not waved at **[rev 2 — new, S9]**

S9's arithmetic is correct and I re-verified it: `episode-runs`
(`work.cljc:153-189`) counts inclusively from the run answering the last
**outside** trigger, and a trigger is outside only when it carries **neither
`from` nor `about`**. So every owner reply is an *inside* trigger, and:

```text
planner/root runs in one episode = 1 outside-triggered run + N reply runs
```

At the default 100, N = 100 delegates needs 101. Rev 1's D7 ("if the cap fires
that's a bug report") ignored deterministic arithmetic. **Retracted.**

**The v0 ruling: fan-out per delegating run is bounded by derived headroom.**

```text
max-delegates = max-episode-runs − episode-runs(db, delegator) − 1
```

The delegator renders **at most that many** assignments per run; the remainder
stay outstanding (§3.4 guarantees they stay *visible*) and are delegated on a
later run. This is a derived bound from a landed dial, not a new constant, and
it degrades honestly: over-fan-out becomes *slower*, never *lost*.

**A second consequence of E2 helps:** stop-at-first-red means one attempt
yields one red form, so v0's realistic fan-out is 1–2 and the bound never
binds. The proof still asserts obligation 7 rather than assuming it.

**The general answer is a separate unit, not v0's problem.** Each reply
opening its own run is not merely a cap issue — it is N paid model calls that
all re-derive the same basis. The right fix is **trigger coalescing**: one run
answers *every* unanswered trigger at its basis, because a run derives from a
basis, not from a queue. That is E4 (§12) — a real change to run opening and
the `:seon.db/trigger` tx-meta shape, with its own owner. v0 does not need it
and must not smuggle it in.

---

## 10. Completion semantics **[rev 2 — new, S10]**

S10 is correct: the loop accepts `my.run/complete` from the frozen form and
closes in the same receipt transaction without re-deriving anything, so a
completion frozen at basis B can close at B+2 after an owner's failure
committed at B+1.

**Rev 2 rules the semantics rather than pretending the query protects us:**

1. **An agent's completion is not an acceptance claim.** It means "I have
   nothing further to say this turn". The goal's doneness is the derived value
   of §3.4, which any reader can run and which no agent can assert.
2. **Root's context always shows the outstanding block.** A reply saying
   "done" beside a block showing two outstanding problems is not a lie the
   system told; it is one agent's prose next to the facts. The drive asserts
   this composition explicitly.
3. **The general class is filed, not absorbed.** "A frozen disposition can
   close against newer facts" is true of *every* agent, not just this
   capability. It belongs to the run-closing owner as an issue with S10's
   interleaving as evidence — §12 lists it. If the owner rules that the
   terminal transition must refuse stale completions, this plan inherits the
   fix for free.

What rev 2 will **not** do is claim, as rev 1 did, that "a reader trusts the
derived value" makes the recorded false completion harmless.

---

## 11. Owner decisions, and the name table **[rev 2 — D7 retracted, D8/D9 new]**

**D1 — does `generate-code` survive as a name?** *Yes as the name of the
loop, no as the name of any code.* No namespace, function, or attribute in
this plan carries it.

**D2 — is the planner namespace-less?** **RETRACTED (S3).** The planner owns
`my.gen.planner` through the one formal creation path; self-delegation is an
explicit refusal rule.

**D3 — attribution granularity at v0 = namespace (P1 deferred).** *Confirm* —
but note it now depends on E1 rather than on a parse fact.

**D4 — unowned namespace: root residue, or birth an agent?** *Root residue.*

**D5 — one local model in both roles.** *Confirm for v0.*

**D6 — staged failure acceptable?** *Yes, through the production paths only*
(R1): a real failing `deftest` via `--result-cluster`, and model failure
injected at the provider-response boundary.

**D7 — episode cap.** **RETRACTED (S9).** Replaced by §9's derived headroom
bound; the general fix is E4.

**D8 — E1, or the smaller v0-B? [new]** Does one planner run really author
several namespaces (E1, evaluator change), or does v0 drop to a decomposition
planner whose owners each author their own namespace? *Recommendation: E1* —
v0-B forfeits the quarry's central lesson and makes the capability little more
than "message two agents". But E1 is an evaluator contract change and the
owner should choose knowingly.

**D9 — one live goal per planner at v0. [new]** §3.1's scoping limit.
*Recommendation: accept the restriction* and record it, rather than invent a
scoping relation for test results that the facts do not support.

### Name table — for veto

| name | what it is | grounded in |
|---|---|---|
| receipt's evaluated namespace (E1) | the namespace a form actually ran in | `sci/eval.clj`'s own `sci/ns` binding; named by what the evaluator did |
| `:attributed` (a `seon.problems` family key) | problems grouped by owner, retaining exact refs | the six existing family keys |
| the assignment identity (E3) | derived `(about, recipient)` identity giving upsert-based at-most-once | receipts' and messages' existing derived-identity idiom |
| **"residue"** | *retired as a coinage* — say **unattributable problems** | R34 |
| **"attempt"** | prose for one planner run; scoped by `caused-by` | no entity |
| ~~`:seon.cluster.run.form/ns`~~ | **withdrawn (S2)** | — |

---

## 12. Preconditions — separate units, each with an owner **[rev 2 — new]**

Per the coordinator's instruction, these are **not absorbed into this plan**.
Each is its own unit; this plan is blocked on E1–E3 and merely benefits from
E4 and the S10 issue.

| id | unit | owner | why v0 needs it |
|---|---|---|---|
| **E1** | **Run-stateful namespace + evaluated-namespace on the receipt.** The evaluator threads the namespace across a run's forms as it already threads the ctx; each receipt records where its form ran. | `seon.sci.eval` + `schema/run.edn` | Without it multi-namespace execution is fiction and there is nothing to bisect (S2, reseal Q2). **BLOCKING.** |
| **E2** | **The fold stops at the first red form.** Closes the open issue `a-failed-form-does-not-stop-the-fold`. | `seon.cluster.loop` + the issue | A loop that can complete on `Unbound` cannot host a capability whose output is "which parts are accepted" (S7, reseal Q6). **BLOCKING.** |
| **E3** | **`about`-carrying sends with a commit-time assignment identity.** Optional string identity on `my.message/send`, resolved by the driver; derived `(about, recipient)` identity giving at-most-once by upsert. | `my.message` + `seon.cluster.message` + `schema/message.edn` | Delegation is unbuildable without it, and a render-time read is not a fence (S4, S5, reseal Q4). **BLOCKING.** |
| **E4** | **Trigger coalescing** — one run answers every unanswered trigger at its basis. | `seon.cluster.work` | Not v0-blocking; the general answer to N replies costing N paid calls (S9, reseal Q7). |
| **I1** | **Issue: a frozen disposition can close against newer facts.** S10's interleaving as evidence. | the run-closing owner | Not v0-blocking under §10's ruling; v0 inherits any fix. |

---

## 13. Per-finding disposition **[rev 2 — new]**

| # | finding | disposition |
|---|---|---|
| **S1** | no fact wakes the planner for the repair turn | **ACCEPTED — mechanism replaced.** The landed auto-reply wakes **root**, which delegates (§3.2). No problems-driven wake is proposed; that would break L8 disjointness. |
| **S2** | parse-time vs evaluation-time namespace | **ACCEPTED — mechanism killed.** `/ns` withdrawn. Attribution becomes an evaluation fact from the evaluator (E1, §2.2). Rev 1's contradictory suite property is deleted. |
| **S3** | namespace-less planner contradicts creation | **ACCEPTED — design changed.** The planner owns `my.gen.planner` via the one formal path; self-delegation is an explicit refusal, not a structural claim (§1.1). |
| **S4** | ordinary sends cannot carry `about` | **ACCEPTED — precondition created.** E3, with the string-identity resolution consistent with recipient names (§3.3). Rev 1's "everything landed" claim is retracted (§7). |
| **S5** | render-time exclusion is not an idempotency fence | **ACCEPTED — fence moved to commit.** Derived `(about, recipient)` identity, upsert semantics, proven under latched concurrency (§3.3, §6.2). |
| **S6** | non-replying owner ⇒ silent loss | **ACCEPTED — stopping rule replaced.** Delegation never removes a problem; done is empty red, never "all delegated"; the adversarial history is a required proof (§3.4, §5 Act 4, §6.1). |
| **S7** | the fold continues and can complete with a lie | **ACCEPTED — named as blocking precondition E2.** Rev 1's "free" judgment retracted; the consequence for fan-out is stated (§2.3). |
| **S8** | "the run is the attempt" does not scope later runs | **ACCEPTED — landed relation supplied.** `caused-by` chain scoping, with the two limits (test results, one-goal-per-planner) stated rather than assumed (§3.1, D9). |
| **S9** | the cap starves 100 delegates | **ACCEPTED — D7 retracted, bound derived.** Fan-out ≤ derived headroom; the general fix is E4 (§9). |
| **S10** | evidence-derived done is not enforced at the terminal transition | **ACCEPTED — semantics ruled, class filed.** Completion is not an acceptance claim; root's block always shows outstanding; the general defect is issue I1 (§10). |
| **R1** | injected test failure bypasses the P2 path | **ACCEPTED.** Real failing `deftest` through `bin/test --result-cluster`; model failure injected at the provider-response boundary (§5 Act 2). |
| **R2** | properties can pass while work is lost | **ACCEPTED — suite rewritten from required outcomes** (§6), including the non-settling owner, same-plan duplicates, and concurrent terminal transactions. |
| **N1–N6** | current-tree verification | **ADOPTED as the state of record** and reflected in §7's corrected map. |

**Nothing in the review is refuted.** Two of its readings are *extended*
rather than contradicted: `seon.cluster.message/reply` supplies S1's missing
trigger, and `caused-by` supplies S8's missing scope — both landed, both
missed by rev 1.

---

## 14. Seal assessment **[rev 2 — new]**

**This document is not seal-ready, and rev 2 does not claim it is.** It
answers all seven reseal questions, but three of its answers are
*preconditions owned elsewhere*:

| reseal question | answered | by |
|---|---|---|
| 1. what triggers the first repair turn? | yes, with landed mechanism | §3.2 |
| 2. does one run execute several namespaces? | **only if E1 lands** | §2.2, D8 |
| 3. what scopes one goal across runs? | yes, with landed mechanism | §3.1 |
| 4. what owns at-most-once, and what distinguishes delivered/outstanding/repaired? | **design given, needs E3** | §3.3, §3.4 |
| 5. what prevents a stale disposition closing? | ruled for v0; class filed | §10, I1 |
| 6. what fold ruling closes the false-completion issue? | **owner ruling needed** | E2 |
| 7. what fan-out bound composes with the cap? | yes, derived | §9 |

**Seal requires, in order:** the owner's D8 choice (E1 or v0-B); the E2 fold
ruling; and E3's contract authored by its owner. When those three land, this
plan's remaining content is implementable and the §7.4 falsifier can be run
honestly. Until then, as the review says, a drive would demonstrate a
hand-held two-owner scenario — which rev 2 no longer claims is the product.

---

## 15. Sequencing

**Blocked on E1, E2, E3** (§12). Not blocked on P1, N5, or the effect door,
and must not acquire those dependencies during implementation. It occupies a
parallel product slot, never the F-series spine. Its first honest v1 begins
when N5's corpus round trip makes accepted code callable.
