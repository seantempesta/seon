---
type: prd
status: active
tags: [prd, agent, architecture, runtime]
---

# generate-code v0 — the whole loop on the local model (2026-07-29)

**The law of this wave (owner, verbatim): "DO NOT PORT THINGS EXACTLY"** —
the quarry supplies inventory and lessons, this plan derives everything
fresh from the ruled architecture. And: *"I want us to improve what the
system can do through learning from the past."* Every element of the old
design below is either RECONCEIVED (§8 says how) or RETIRED (§8 says why).
Nothing is transcribed.

`plan/README.md` remains the only ordering; this is a contract-shaped plan
for one product capability, not a second ledger.

Evidence authority:

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
- [context-blocks-contracts-2026-07-28.md](context-blocks-contracts-2026-07-28.md)
  — pre-provider capture; the one projection-invocation seam.
- [../research/local-provider-2026-07-28.md](../research/local-provider-2026-07-28.md)
  — the local Qwen row: three executable forms, three terminal receipts,
  `"55"`, 42.6 tok/s, `:seon.config.ai/no-auth true` landed.

**Verified live in the tree, 2026-07-29** (`rg`, not prose): F1's per-agent
graphs (`src/seon/cluster/agent.clj`); custody-is-presence
(`schema/run.edn`); the reply splitter (`cluster/reply.cljc`); `my.message`
and `my.run` as the two value shapes bound in `sci/eval.clj`; the render walk
with `:seon.render/distance` and the agent-view pilot
(`render/walk.clj`, `render/agent.clj`); `seon.problems`' six families;
**P2 LANDED** (`:seon.test.result` refs the exact `:seon.test` and
`:seon.test.run`); **P3 LANDED** (`:seon.cluster.agent/namespace`, unique ref
to `:seon.ns`, `owner-of` the pure lookup). **P1 is NOT landed** —
`:seon.error/data-edn` is still a printed string. §4 shows why v0 does not
need it.

---

## 0. The v0 in five sentences

A human or the root agent **messages a planner agent one goal**; there is no
new agent-facing function, because a whole-program attempt is an agent taking
a turn, not a call. The planner's ordinary turn produces **one model reply
carrying several namespaces' worth of ordinary Clojure**, which the existing
splitter freezes into one plan whose **form rows now carry the namespace in
effect at parse** — the only new fact in this design. The fold evaluates each
form once through the one door; **accepted forms hold terminal receipts and
are never re-evaluated**, and a failing form neither aborts the fold nor
erases its siblings. The red residue is then a **pure query in
`seon.problems`, attributed to owners through P3's assignment**, rendered
into the planner's own next context as an ordinary problems block — and the
planner **delegates by returning `my.message/send` values**, which the run
loop commits, which wakes the owners; delivery evidence is the committed
message's `about` ref, so the same problem is never delegated twice and the
loop terminates by construction. Nothing schedules, nothing dispatches,
nothing re-executes.

---

## 1. The surface — a message, not a function

**Judged against the three agent-facing shapes law** (values the driver
interprets / capability requests through the one door / durable facts the
driver commits):

| candidate | verdict |
|---|---|
| a `my.generate-code` value the loop interprets | **REJECTED.** The loop would have to launch an agent, install a scheduler and deliver a terminal result — runtime semantics performed from inside an eval. That is precisely the old-engine residue the conversion law names: a lifecycle call wearing a value's clothes. |
| a capability through `seon.effect` | **REJECTED for v0.** The door does not exist yet, and building the first arm of it to carry "run a whole strong-model pass" would rebuild `^:async generate-code!` with a new spelling. When the door lands, an LLM *completion* is a capability; a whole program attempt still is not. |
| **root/human orchestration by message** | **ADOPTED.** A whole-program attempt IS an agent taking a turn. `my.message/send` already commits, wakes and carries content, and F1 already gives the recipient its own flow. |

**So the v0 surface is: `(my.message/send "planner" "<the goal>")`, or the
same message committed by a human through the web UI.** Zero new
agent-facing constructs — the weirdness count the effect pilot demanded is
zero here by not adding anything.

**What makes it a *generate-code* turn rather than an ordinary one is
entirely context, and context is a render** (post-midnight ruling #2: an
agent's context IS `render(its namespace, distance N)`):

- the planner agent is **namespace-less on purpose** (recommendation, owner
  decision D2 in §9). Its view is not one namespace's neighbourhood; it is
  **the set of namespaces the goal names, each rendered at distance 1** —
  signatures + docstrings by the landed namespace renderer, bodies by budget.
  This is one call into the existing router with several roots, not a new
  view kind;
- plus the **attempt problems block** (§3), which is empty on the first turn
  and is the whole delegation mechanism on later ones;
- plus the goal itself, which arrived as an ordinary message and is already
  in the prompt.

The three "distance" hops are what the quarry never had and what made its
repair assignments useless pointers: the old system mailed a *reference to a
failure*; here the owner already lives inside the rendered view of its own
namespace and the message adds only the vision and the exact refs.

**Attempt identity.** The planner's **run** is the attempt. The old
`:seon.ai.attempt/*` batch identity is retired: `(run, ordinal)` already
names every form and receipt, `:seon.cluster.run/agent` names the planner,
and the run-opening transaction's tx-meta already refs the triggering
message (the N3 night ruling). One goal → one run → one plan → N receipts,
all joinable today. **No attempt entity is created.**

---

## 2. The attempt — one pass, and the one new fact

The turn proc runs unchanged: settle orphan → pin one database value →
derive prompt (now the namespace views at distance) → **pre-provider capture**
→ one model call → `reply/sources` → plan freeze → fold.

### 2.1 The one new fact: `:seon.cluster.run.form/ns`

The quarry's namespace fencing was 1,517 lines of forgiving parser. Its
*idea* survives as one lifted fact, and it lands where the 2026-07-29 ruling
says classification belongs — **at the parse, in the one general parser**,
not in a second pass and not in the error normalizer:

> the eval parse is NOT a reply parser — one GENERAL parser turns any
> code-bearing text into runnable forms anywhere in the system, and the
> `:io`/`:compute` workload detection happens IN THE SAME AREA (the parse
> pass lifts classification facts).

`seon.cluster.reply/sources` already reads every form with SCI's own reader.
It gains one derivation over the forms it has already read: **the namespace
in effect for each source** — the most recent preceding `(ns …)` or
`(in-ns 'x)` form, defaulting to the agent's own `my.agents.<id>`. The plan
freeze upserts a `:seon.ns` entity by name and refs it from the form row:

```clojure
:seon.cluster.run.form/ns :seon.db/ref   ; → :seon.ns/name, upserted at freeze
```

That is the **entire** schema delta of this plan. It is a *derived* fact
committed with the thing it describes, not stored derived state: the source
is durable, the namespace is a pure function of the source, and it is
materialised only because a Datalog join cannot re-read a string.

**Why this and not P1.** P1 (queryable failure refs on `:seon.error`) buys
*function*, *schema-key* and *call-root* granularity. v0 needs only
**namespace** granularity, because P3's assignment is namespace-keyed
anyway: a failing form → its form row → its `:seon.ns` → the assigned agent.
The join exists without touching `seon.error` at all. **P1 is therefore not a
v0 precondition; it is the v1 upgrade from namespace granularity to
function/schema/call-path granularity.** (§4.)

**Fencing safety, reconceived.** The quarry's rule — "a malformed
declaration never lets later forms fall through into the previous namespace"
— becomes: a form whose namespace cannot be determined gets **no** `/ns` ref,
and therefore lands in root residue, loudly. Absence, never a guess (R34).

### 2.2 Evaluation — today's fold, no second evaluator

Each source is evaluated once, in ordinal order, at the previous step's
`:db-after`, through the one `:compute` door under the one `:interrupt-fn`,
producing one terminal receipt. Three properties the old design fought for
are **free here** and must not be re-implemented:

- **an independent namespace survives a sibling's failure** — nothing aborts
  the fold; an errored form is a terminal receipt, the next ordinal proceeds;
- **nothing re-executes** — the crash model, not a scheme;
- **accepted work is preserved** — the receipt IS the preservation, and the
  bisection query (§3) is read-only, so re-planning at a later basis cannot
  disturb it.

The old dependency-ordered admission is **RETIRED for v0** (§4): the model
authors in its own order, and authored order is the order. A forward
reference fails, becomes residue, and is delegated — which is the loop
working, not the loop failing.

---

## 3. The bisection — a problems family and two ordinary sends

### 3.1 It is a query, and it already has a home

`seon.problems` is "everything wrong RIGHT NOW, derived from the facts that
say so", keyed by family, absent when empty, transacting nothing. The
bisection is not a new mechanism; it is **one more family** plus one
attribution join over families that already exist:

| red fact (already committed) | ownership join |
|---|---|
| errored receipt | receipt → `:seon.cluster.run.form/ns` → `:seon.cluster.agent/namespace` |
| failing test result (P2) | result → `:seon.test/ns` → assigned agent |
| `:seon.error` fact carrying a namespace | its ns → assigned agent |
| anything else, or an unowned namespace | **root residue, loudly** — never a prefix guess |

Recommended shape: `seon.problems` gains **`:attributed`** — problems grouped
by owning agent, each entry retaining the **exact refs**, never copied error
prose — and the existing unattributable rows stay where they are. The
grouping is the delegation unit; the refs are what the owner reads.

**A healthy attempt derives `{}`,** which is also the stopping rule (§3.3).

### 3.2 Delegation is the planner's next turn, not a delivery routine

There is no delivery routine, no dispatcher, no delivery transaction of its
own. The planner's **next** turn — triggered by the same self-rewake F1
already performs when work remains — sees the `:attributed` block in its
context and returns ordinary values:

```clojure
[(my.message/send "alpha" "<vision> … your namespace's failures: …")
 (my.message/send "beta"  "<vision> … your namespace's failures: …")
 (my.run/wait "delegated 2 namespaces; awaiting fixes")]
```

The run loop commits them in its ordinary terminal transaction, which wakes
the owners. **That is the whole of P4.** The "idempotent multi-failure
message shape and a durable record that each message committed" is: the
message fact itself, with `:seon.cluster.message/about` — already a ref —
pointing at the problem entity it delegates.

**Idempotency, and why the loop terminates.** The `:attributed` derivation
**excludes any problem for which a message already exists with that `about`
ref and that recipient**. Delegated once means never delegated again, so:

- a re-plan at a later basis cannot re-delegate settled work;
- a crash between the sends and anything later re-derives the same exclusion
  and sends nothing twice;
- **the round-trip cannot ping-pong**: the problem set at any basis is
  finite and each (problem, owner) pair is delivered at most once. New
  problems produced by a fix are legitimately new work, not a loop.

This is the ruled dissolution rather than a cap: *"any race that could loop
agents forever is a design defect to dissolve, never a thing to cap."* Two
standing backstops already exist and remain backstops, never the mechanism:
the episode dial (100) and the landed message chain-depth guard
(`:seon.config.message/max-chain`, which refuses delivery past the limit with
a flat error value). If either fires during a drive, that is a bug report.

**Verified caveat.** `:seon.cluster.message/about` is a bare indexed ref, but
it is declared today in `schema/error.edn` and written only by the error
recorder. Pointing it at a problems entity is within its type and needs no
new attribute; whether its declaration should move to `schema/message.edn`
once it references more than errors is a review point (§10.7), not a blocker.

### 3.3 Stopping, and what "done" means

The global episode ends when **the attempt's problems value is empty, or
every entry in it has an owner and a committed delegating message.** Done is
never planner prose — the quarry's two terminal owners let the planner's own
`complete` bypass evidence-derived delivery, and that is retired: the planner
may say `complete`, but what a reader trusts is the derived value, which is a
query anyone can run.

An owner's reply is an ordinary message, which starts a **new outside
episode** for the planner, which re-derives everything at the current basis
with sibling success intact. **No owner self-wakes; the planner has no
special status; there is no coordinator.**

**The owner does not even have to send that reply deliberately.**
`seon.cluster.message/reply` already derives it: a run that completes replies
to the message that triggered it, and completing back at something that was
itself a reply is refused as a bounce. So the round trip closes through
landed machinery — the owner writes `my.run/complete "fixed …"` and the
planner wakes. This is the strongest evidence that delegation needed no new
mechanism: the return path was already built by the messaging rung.

---

## 4. What v0 explicitly DEFERS — named, with the gate

| deferred | why, and what unblocks it |
|---|---|
| **Corpus composition — accepted code is SOURCE, not callable definitions.** N5's `defn → :seon.fn facts → callable` round trip is not landed, so an owner's fix run cannot *call* the planner's accepted `my.gen.alpha` functions; it reads their source in its rendered namespace view and re-authors. | **N5.** This is the honest v0/v1 line and the single biggest scope cut. v0 proves the loop's SHAPE with source-level composition; v1 proves it with program composition. |
| **Warm namespace repair** — the old system's headline promise, never proven: a failed namespace repaired by a warm resident that had previously completed it, releasing a dependent. | Requires the above plus dependent tracking. v0's owners are cold each turn by construction (fresh sci fork per run), and that is fine because the *view* carries continuity, not the process. |
| **P1 — function/schema/call-path attribution.** v0 attributes at NAMESPACE granularity only. A cross-namespace call path yields one owner (the failing form's), not every namespace on the path. | P1's normalize-time refs + `:seon.fn/calls` at N5. |
| **Dependency-ordered multi-namespace admission** and cycle rejection. Authored order is the order. | Not scheduled; may never return — a forward reference failing and being delegated is the loop working. |
| **The accrete-first admission gate** and spec-first economics (strong model authors contracts/tests, cheap models implement). v0 runs **one local model in both roles**. | After the loop's shape is proven; the economics split is a provider-row change, not a design change. |
| **`seon.effect`** and any capability inside a generated program (fs, web, db writes). v0 programs are pure. | The door's own rung. |
| **A second re-plan round beyond what messages naturally cause.** No escalation ladder, no retry policy. | Nothing; if evidence demands one it is a context block, not a mechanism. |

---

## 5. The live proof on local Qwen

Cluster `generate-code-v0`, rooted under a disposable path (never `default`),
against the landed local row (`:seon.config.ai/no-auth true`, 42.6 tok/s
measured).

**Cast:** `planner` (no namespace assignment); `alpha` assigned `my.gen.alpha`;
`beta` assigned `my.gen.beta`. All three armed as F1 graphs.

**Act 1 — the attempt.** One message to `planner`: a real two-namespace goal
(`my.gen.alpha` provides a pure transformation with a Malli contract;
`my.gen.beta` consumes it and carries a test). Observe: one run, one plan,
per-form receipts, and **every form row carrying a `/ns` ref** — the
attribution fact exists before anything fails.

**Act 2 — the staged failure.** Two sources, deliberately:

1. **natural residue**, whatever a 35B local model actually gets wrong — this
   is evidence about the model, not about the design;
2. **injected residue**, so the round trip is proven *regardless of Qwen's
   competence*: the harness commits one deliberately failing
   `:seon.test.result` referencing a `:seon.test` in `my.gen.beta` (the P2
   path, exactly the §7.4 falsifier's "failing test in B"), and one form is
   made to throw in `my.gen.alpha`. **The design's correctness must not
   depend on the model failing in an interesting way.**

**Act 3 — bisection and delegation.** The planner's next turn shows the
`:attributed` block; it returns two `send` values and a `wait`. Observe: two
message facts with `about` refs, two owner runs opened, **each owner's derived
prompt already containing its own namespace's source** (the distance render
doing the work the old repair bundle never did).

**Act 4 — the fix and the close.** Each owner authors a fix in its own run.
The planner wakes on the replies, re-derives at the new basis, finds the
delegated problems excluded and the residue empty, and completes.

**Proof obligations — each a falsifier, not a vibe:**

| # | obligation | how it is observed |
|---|---|---|
| 1 | accepted forms are never re-evaluated | receipt count per `(run, ordinal)` is 1 across the whole drive; no second receipt for any accepted ordinal |
| 2 | the bisection transacts nothing | datom census across the derivation: zero new datoms, `:max-tx` unchanged |
| 3 | attribution is sound | every errored receipt's `/ns` equals the namespace in effect at parse (checked against the frozen source) |
| 4 | delivery is idempotent | re-run the planner's delegating derivation at the same basis → zero additional messages |
| 5 | no re-attempt of delegated parts | the planner's re-plan touches no ordinal already delegated |
| 6 | the episode dial bounds it | consecutive runs per episode observed < cap; no self-wake storm |
| 7 | one model, honestly reported | tokens, wall time, and *what Qwen actually got wrong*, recorded verbatim |

Evidence lands in `../research/generate-code-v0-drive-2026-07-29.md`; the
drive script is committed (`tmp/` is for throwaway probes, and this is a
reproducible measurement, so it is real code under the drive harness).

---

## 6. The sealed suite sketch — seeds continuing the series

Model-free, per-trial databases, seeded state transitions in the established
style. **One regression per failure class, at the choke point:**

1. **attribution totality** (property) — for any frozen plan, every form row
   has either exactly one `/ns` ref or none; no form is attributed to a
   namespace that does not appear in its own source prefix. Generated from
   the reply schema: fenced replies, `in-ns` switches, a malformed `(ns …)`,
   prose-only sources.
2. **bisection totality** (property) — every red fact in a generated database
   lands in exactly one of {attributed to owner X, root residue}. There is no
   third outcome and no silent drop.
3. **bisection purity** — the derivation over any generated database
   transacts nothing. (Guards the class the old scheduler violated by
   claiming inside its own observation.)
4. **delivery idempotency** (state transition) — random interleavings of
   {derive, send, crash, re-derive} produce at most one message per
   (problem, owner); replaying any prefix adds none.
5. **termination** — from any generated red set, the delegate/exclude loop
   reaches an empty derivation in ≤ |problems| rounds. This is the ping-pong
   class, proven dead by construction rather than capped.
6. **sibling preservation** — a failing ordinal never retracts, alters, or
   re-opens an accepted ordinal's receipt.
7. **unowned refusal** — an unassigned namespace's problems appear as root
   residue and are never delivered to a name-similar agent.

Every proof is claimed by `bin/test`. A live-only proof counts as NOT COVERED.

---

## 7. Where the primitives are exercised — the honest map

| primitive | v0's use |
|---|---|
| `my.message/send` | the surface AND the delegation; nothing else carries either |
| F1 per-agent graphs | three agents, three episodes, no dispatcher |
| custody-is-presence | one open run per agent makes concurrent owner fixes safe by construction |
| `reply/sources` | the one parser, now lifting one classification fact |
| the fold + receipts | the attempt, and the preservation of accepted work |
| P2 test-result facts | one of three red-fact sources, joined by exact ref |
| P3 namespace assignment | the entire ownership join |
| the render walk + distance | the planner's multi-root view AND every owner's local view |
| `seon.problems` | the bisection, as one more family |
| `:seon.cluster.message/about` | delivery evidence and idempotency, with no new attribute |
| the local no-auth provider row | the model |

Everything in that table is landed today except the `:attributed` family, the
`/ns` fact, and the drive.

---

## 8. Every old-design element: reconceived or retired

| old element | verdict |
|---|---|
| public `^:async generate-code!` wrapper | **RETIRED.** An effectful lifecycle call from inside an eval — the exact ported-defect shape. Replaced by a message (§1). |
| `:my.plan/goal` request map + injected caller id | **RETIRED.** The goal is message content; the caller is the message's sender fact. |
| launching a `:planning` model-variant agent per goal | **RETIRED.** Agents are durable and namespace-centred; a per-goal disposable planner would recreate the task-agent shape F1 rejects. One standing planner. |
| root observer + `:execution` scheduler + recovery registry | **RETIRED.** "The fresh architecture rejects that dispatcher shape." Replaced by: owners wake on messages, and recovery is the ordinary derivation. |
| CAS claim of each unit | **RETIRED as a unit mechanism**, reconceived at the run level: one open run per agent is already the fence. Nothing claims a namespace. |
| `parse-program` / `project-program` (1,517 lines) | **RECONCEIVED to one fact.** Namespace fencing survives as `:seon.cluster.run.form/ns` lifted at the existing parse (§2.1); alias/`register!` recognition, require-edge derivation and cycle rejection are retired (§4). |
| generated dependency ordering across namespaces | **RETIRED for v0** — authored order, and a forward reference becomes residue. |
| `my.plan/publish-generated-program!` + `:my.plan/needs` children | **RETIRED.** A durable plan DAG is stored derived state; the problems query derives the same answer from the receipts that already exist. |
| positive completion derived from exact eval ids + green test summary + no later error | **RECONCEIVED, and this is the lesson that survives hardest.** Completion is still evidence-derived, never model prose — but it is now *absence of red at the acceptance basis*, derived by the same query anyone can run, rather than a stored per-unit completion. |
| repair assignment as a pointer | **RECONCEIVED.** The owner's context IS its namespace view at distance; the message carries vision + exact refs only. The old "promised bundle" (accepted prefix, sibling status, local source) is a render, not a payload. |
| namespace resident birth-on-demand (`:seon.agent/namespace`) | **RECONCEIVED** as landed P3 `:seon.cluster.agent/namespace`. v0 does NOT birth agents on demand: an unowned namespace is root residue, loudly. Auto-birth is a policy decision (§9 D4), not a mechanism to inherit. |
| compact terminal `:done` message to the caller | **RECONCEIVED** as the planner's ordinary `my.run/complete` plus the derived stopping value. The old dual terminal owners — the defect that let planner prose bypass delivery — cannot recur, because prose closes nothing a reader trusts. |
| no-reply retry path for a timed-out planner | **RETIRED.** No auto-retry, ever (2026-07-27 night). A lost call is lost; the agent adapts. |
| planner scratch namespace becoming a self-addressed step | **RETIRED by construction.** The planner owns no namespace, so it can never be its own owner. (§9 D2 is the decision that keeps this true.) |
| `:seon.ai.attempt/*` batch identity | **RETIRED.** `(run, ordinal)` and the run's tx-meta trigger ref already carry it. |
| exact node-count budgets, whole-database equality fences | **RETIRED**, as the rerun already found: honest caps and presence fences. |
| the fake-agent Flow prototype's seeded coordination laws | **RECONCEIVED** as suite items 4 and 5 (§6) against real facts. It was never the product and must not be shown as one. |

---

## 9. Owner decisions, and the name table

### Decisions

**D1 — does `generate-code` survive as a name?** *Recommendation: yes as the
name of the LOOP, no as the name of any code.* The owner used it tonight and
it says exactly what happens. But there is no `seon.generate-code` namespace,
no `my.generate-code` function, and no `generate-code` attribute in this
plan — the mechanisms live in `seon.problems`, `seon.cluster.reply` and
`my.message`, which is the evidence that it is a capability rather than a
subsystem. Alternative: retire the phrase entirely in favour of "the
delegation loop"; rejected as losing a name the owner reaches for.

**D2 — is the planner namespace-less?** *Recommendation: yes.* It makes the
old self-recipient defect unrepresentable, and its context is honestly
multi-root. Alternative: give it `my.gen` as a home; costs the guarantee and
buys a scratch space it does not need.

**D3 — attribution granularity at v0 = namespace (P1 deferred).** *Confirm.*
The alternative is blocking v0 on the `seon.error` normalize-time rework,
which is a different unit with a different owner.

**D4 — an unowned namespace: root residue, or birth an agent?** *Recommendation:
root residue for v0.* Auto-birth on demand is exactly how the old system grew
a lifecycle beside the run model. Alternative: root explicitly creates and
assigns an agent as an ordinary act — available today, and the right shape if
the owner wants it, but it should be root's decision, not the loop's.

**D5 — one local model in both roles.** *Confirm for v0.* The spec-first
economics split (strong authors contracts, cheap implements) is deferred to
after the shape is proven. Alternative: DeepSeek as planner + Qwen as owners
now; costs paid calls on an unproven loop.

**D6 — is staged failure injection acceptable proof?** *Recommendation: yes,
and required.* The design's correctness must be observable independently of
whether a 35B model happens to make an interesting mistake; the natural
residue is reported separately as evidence about the model.

**D7 — episode cap for the planner.** *Recommendation: the standing 100, no
per-agent override.* Termination comes from delivery idempotency (§3.2); if
the cap ever fires, that is a bug report, not the mechanism working.

### Name table — for veto before contracts seal

| name | what it is | grounded in |
|---|---|---|
| `:seon.cluster.run.form/ns` | the namespace in effect for one frozen form source; ref to `:seon.ns` | sits beside `/id`, `/run`, `/ordinal`, `/source` on the existing form entity; `:seon.ns/name` is the landed identity |
| `:attributed` (a `seon.problems` family key) | problems grouped by owning agent, retaining exact refs | the six existing family keys; the 2026-07-28 ruling that these are "problems-family blocks" |
| **"residue"** | *retired as a coinage.* Say **unattributable problems** (they are already a problems row) | R34 / the no-invented-nouns rule |
| **"bisection"** | kept as prose for the act of attributing red facts to owners; **not** a function name — the function is a `problems` derivation | the quarry's own word, used descriptively |
| **"attempt"** | prose for one planner run; **no entity, no attribute** | `:seon.cluster.run/*` already names it |
| **"the delegation loop" / "generate-code"** | the capability; no code carries either name | D1 |

No name in this plan introduces a new namespace, and the design touches
`seon.cluster.reply`, `seon.cluster.run`'s schema, `seon.problems`, and the
drive. **A revision that needs a fifth owner is misdesigned.**

---

## 10. Orchestrator review points

1. **Is the message surface really the whole surface?** The claim is that a
   whole-program attempt needs zero new agent-facing constructs. If
   implementation discovers it needs one, that is the falsifier for §1 and
   the plan is wrong, not the implementation.
2. **Is `/ns` a derived fact or stored derived state?** It is materialised
   because Datalog cannot re-read a string. If the corpus at N5 makes it
   redundant, it is deleted then — recorded here so the deletion is
   scheduled, not discovered.
3. **Does `:attributed` belong in `seon.problems`, or is it a second
   interpretation of the same facts?** Review the derivation for any state
   `seon.problems` does not already own.
4. **Is the exclusion-by-`about` genuinely idempotent** under crash between
   the sends and the terminal transaction — walk it against the custody
   revision's presence fences.
5. **The v0/v1 line (§4, corpus composition).** Confirm it is stated honestly
   in the drive report: v0 proves the loop's shape, not a composed program.
   The quarry's central failure was claiming a loop that never closed.
6. **Model-independence of the proof.** Verify obligations 1–6 hold with the
   injected failures alone.
7. **`:seon.cluster.message/about`'s declaration home.** It lives in
   `schema/error.edn` and is written only by the error recorder. Once it
   refs problems entities, decide whether it moves to `schema/message.edn`
   (colocation rule: the attribute namespace takes the owning code
   namespace) — a one-line move, but it should be a decision, not a drift.

---

## 11. Sequencing

Dependency-ready **now**: P2 and P3 are landed, F1's graphs are landed, the
render walk is landed, the local provider row is landed. This plan's own
deltas are small and confined (§9). It does **not** block on P1, on N5, or on
the effect door — and it must not acquire that dependency during
implementation.

It **is** downstream of nothing in the F-series spine; it fills a parallel
slot as a product capability. Its first honest v1 begins when N5's corpus
round trip makes accepted code callable, at which point §4's first two rows
close and the §7.4 falsifier can be run in full.
