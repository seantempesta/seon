---
type: research
status: complete
date: 2026-09-07
tags: [research, review, agent, wake]
---

# Independent data-model review — the agent record and the turn loop (PRD r3)

Reviewer role: data modeller. Question asked of every attribute in §4a:
needed, derivable, or deletable; is the name the dependency's own word; is
the Malli shape complete and honest; would a refactor remove the need.

## Method and evidence base

Read end to end: `AGENTS.md`; the PRD
(`docs/prds/context-generation/plan/agent-record-and-turn-loop-prd-2026-09-07.md`);
`run-loop-unpacked-2026-09-07.md`, `cluster-branch-sci-wake-model-2026-09-07.md`,
`eval-points-and-caches-census-2026-09-07.md`, `audit-repl-and-record-2026-09-07.md`,
`evaluation-merge-landing-2026-09-07.md`; ledger rulings 69–72
(`design-ideas-ledger-2026-08-13.md:1041-1079`); the `data-modeling` and
`datahike` skills; and the live code and schemas the PRD replaces.

Probes ran on my own scratch cluster (`tmp/review-opus-root`, clusters
`review-opus` and `review-opus-b`), MCP `eval_clj`, `jvm` mode, reads only.
The root was brought down and deleted; no shared cluster was touched.

| probe | result |
|---|---|
| `[_ :seon.cluster/name ?n]` on a branch db | exactly one cluster entity per branch |
| pull every `[?e :db/valueType _]` (582 attributes) | attribute entities carry ONLY `db/ident db/valueType db/cardinality db/index db/unique db/isComponent db/noHistory db/id` — no Seon property |
| pull `[?e :seon.schema/key :seon.cluster.message/to]` | the schema ROW carries `:seon.render/ai`, `/html`, `/form` as datoms |
| `[?e :db/ident :seon.cluster.run/trigger]` | `:db/index true`, `:db.cardinality/one`, `:db.type/ref` |
| two clusters in one root | **one JVM, pid 50596, hosts both** |

---

## §9 questions 7–9, first

### Q7 — is every KEEP needed, every DERIVE/DELETE safe?

**The premise of the process-stamp DERIVE is true, and stronger than §1a
states.** §1a says "ONE JVM per cluster". Probed: one JVM per *operator
root*, hosting every cluster in that root (both clusters reported pid
50596). The `flock` is on the store, which is the root's, not the cluster's
(`src/seon/cluster/store.clj`). So at boot, every open turn in every branch
of the root belongs to a dead process **by construction**, and the turn
permit is per-(cluster, agent) because it is a channel in the agent proc's
own state, not a keyed global (`src/seon/cluster/agent.clj:448-468`, read
out of `(:seon.cluster.loop/cluster state)`). Deleting
`:seon.cluster.run/process`, `claim-call`'s takeover, `release-call` and
the holder-only close is safe. Fix the sentence in §1a; the conclusion
survives.

Proofs in `run-loop-unpacked` §5.6 each deletion breaks:

| deletion | proof killed | does the behaviour still matter? |
|---|---|---|
| process stamp, `claim-call` takeover | `transitions-agree-with-the-model` (`run_test.clj:1404`), `custody-mismatch-regression` (`agent_test.clj:1766`) | No. Nothing else can hold the run. |
| holder-only close, pointer coherence | `a-non-holder-refuses-every-held-run-transition` (`run_test.clj:776`), `close-refuses-a-broken-agent-pointer` (`run_test.clj:1680`) | No. With no pointer and no holder, both states are unrepresentable — the right way to retire a check. |
| generated runs, `append-generated-call` | `generated-system-runs-grow-only-after-their-settled-prefix` (`run_test.clj:290`), `a-generated-run-resumes-then-requests-one-more-form` (`work_test.clj:324`) | **Unanswered.** The generated opening is how a fresh agent's first context is *executed*, not merely rendered. §6 asserts "the opening is the projection" without saying where the opening's executed forms and their printed values go. Name that. |
| `turns-left` replacing the episode gate | `episode-cap-refusal-test` (`agent_test.clj:1385`), `answered-trigger-is-a-terminal-work-verdict` (`agent_test.clj:729`), `triggers-come-back-oldest-first` (`work_test.clj:537`) | **Yes — see B2.** A real behaviour is silently lost. |
| `interrupted-at` on the run | `recovery-marks-a-run-that-settled-no-receipt` (`run_test.clj:1595`) | Partly. That test exists exactly for the run that died with ZERO evaluations; moving the stamp to the evaluation leaves that case with no fact at all. The turn's `closed-at` with no `reply` is the derivation — say so, and keep the regression pointed at it. |

Everything else in §1a's DELETE column checks out. `KEEP`s are all needed
except `:seon.turn/id` and `:seon.eval` identity — see S1 below, where the
identity is the cheaper fence.

### Q8 — is "no resume" right? Is the freeze still worth a commit?

**"No resume" is not a change; it is already the crash semantics.** Today
`:resume` is reachable only for a run *this process holds*
(`src/seon/cluster/work.clj:536-540`), and the namespace says so in prose:
"the ordinary live fold, never a cold continuation after recovery"
(`work.clj:13-15`). §1a's "this deletes the resume arm" is wrong in the
letter — §7 keeps the arm as `:evaluate`; what dies is its custody
predicate. Say it that way or the wave reads as larger and riskier than it
is.

What is lost when a crash cuts a turn after a form transacted side effects:
nothing that resume gave, because resume never crossed a crash. The agent's
next context shows the turn up to the cut, which is exactly today's
behaviour. The honest residue is unchanged and unchangeable: a two-form
intention (write the file, record it) can be left half-done, and the fix is
the agent's next turn reading the cut — which is why the evaluation-level
`interrupted-at` must render.

**The freeze is worth its commit; reply + results cannot be one write.**
Without the freeze, a crash during evaluation loses (a) the paid reply and
(b) every completed evaluation's record. (a) is money; (b) is worse — a
form that already ran side effects would have no fact that it ran, so
"nothing re-executes" would rest on the turn never reopening rather than on
a stored (turn, ordinal). Keep three writes: open, freeze, settle.

One reduction is available: the only thing forcing `open` to write anything
beyond the claim is the `turns-left` decrement. Derive the bound (B2) and
`open` writes claim + basis, which is the irreducible pair.

### Q9 — is the stored wake claim needed?

**Yes, and not for concurrency.** The claim is not a lock; it is the
*definition of answered*. Today `unanswered-triggers` is literally
`(not [_ :seon.cluster.run/trigger ?message])`
(`src/seon/cluster/work.clj:604-621`) — pending is derived from the absence
of a claim ref, exactly as §3 wants. Remove the claim and the agent turns
forever on the same message.

**But the direction is inverted against ruling 70 and against the cheaper
model.** Ruling 70 (`design-ideas-ledger-2026-08-13.md:1051-1063`) says
"handled = **a claim ref from the handling run**". §3 and §4a instead put
`:seon.wake/turn` on the *item*. That inversion costs three things:

1. the turn must write onto entities it does not own — another agent's
   message, a fault entity, a schedule row;
2. it needs an index per item family instead of one index on one attribute
   (`:seon.cluster.run/trigger` is already `:db/index true`, probed);
3. it makes the item mutable, so "the message another agent sent" is no
   longer that agent's immutable fact.

Keep turn→item: `:seon.turn/wake`, indexed, and pending is
`(not [_ :seon.turn/wake ?item])`. Ruling 70's own words already say this.

---

## The three hardest wake cases, and what §3 does

**Case 1 — two sources fire in one transaction** (a message and a fault, one
commit). `route!` walks `:tx-data` and offers per datom into the same
sliding-1 mailbox, so the agent gets ONE wake — correct
(`src/seon/cluster/wake.clj:227-250`). Then §4a's `:seon.turn/wake` is
cardinality-one: the turn claims one item, the other stays pending, the
rewake opens a second turn, and the second turn pays the model again **for
a context that already contained both items** — the projection is over the
whole database at basis, not over the claimed item. Today's code has the
same shape (`openable-trigger` returns `(first triggers)`,
`work.clj:449-452`), so the PRD carries a live double-pay defect forward.
Fix: `:seon.turn/wake` cardinality-many, claiming *every item pending at the
basis this turn projected from*. That also makes the claim mean "what this
context saw", which the stored `:seon.turn/basis` already witnesses.

**Case 2 — an item arrives mid-turn.** Its datom fires the listener; the
sliding-1 mailbox holds one wake; the turn's basis predates the item, so
the context never showed it. Under "claim what was pending at basis" the
item is correctly left pending and the rewake opens the next turn. Under
"claim what is pending at settle" — which §3 does not disambiguate — the
turn would claim an item its context never contained, and the agent would
never see it. **§3 must say the claim is taken at the basis, not at
settlement.**

**Case 3 — a steward fault about the agent's own code.** Fault in `f`, `f`
lives in namespace `N`, `:seon.ns/steward` of `N` is the agent that just
failed. §3 asserts `:seon.error/to` and wakes it. This is exactly the
2026-08-08 escalation loop recorded in the tree: "every refused phase of
root's mailed root about root, woke root, met the same unfixed cause, and
mailed root again. Nine paid provider calls in twenty minutes with no
external stimulus" (`src/seon/cluster/loop.clj:682-687`). Today that is
structurally impossible — "a recurrence escalation to the attributed agent
is skipped, so the failing agent is structurally unmailable about its own
refusal" (`loop.clj:690-694`). §3 reinstates the edge without the guard,
and its policy dial cannot express the guard: `:seon.wake/opens-turn?` is a
**schema property**, i.e. per-source, while the self-steward exclusion is a
per-*item* predicate. See B7.

---

## §4a, attribute by attribute

`:seon.agent/id` — needed; identity; shape honest. Keep.

`:seon.agent/namespace` — needed (prompt line, evaluation namespace); ref;
correctly not unique; the "stewardship is `:seon.ns/steward`" note matches
`resources/seon/schemas/seon.ns.edn:27-30`. Keep.

`:seon.agent/turns-left` — **derivable, and storing it deletes behaviour.**
Today the bound is `max-episode-runs − episode-runs`, computed at
`src/seon/bootstrap.clj:47-70` over `work/episode-runs`
(`work.clj:378-419`), and the reset is not code at all: an *outside*
trigger's first run IS the reset, so a human message refills the budget
automatically. A stored counter "refilled by an explicit act" needs a new
refill mechanism, loses the human-message reset, and is a hand-maintained
mirror of a query — the exact shape derive-or-die forbids. If the derived
version is too slow (unmeasured past 9 runs, `run-loop-unpacked` §6.3),
the fix is a stored *episode anchor* (one fact, set once per outside
trigger), not a per-turn decremented counter.

`:seon.agent/plan` — needed; the agent stores it on purpose (ruling 69/70).
Component ref is right. Keep.

`:seon.agent/evals` — needed. **Shape dishonest:** `[:vector …]`. The
bridge maps `:vector`, `:set` and `:sequential` alike to
`:db.cardinality/many` (`src/seon/schema/datahike.clj:184-200`), and a
cardinality-many attribute is a **set** — AGENTS.md's own vocabulary row
says so. A `:vector` declaration promises an order Datahike does not keep.
Declare `[:set {:seon.db/component true} :seon.db/ref]`; the order the
history wants is `:seon.eval/ordinal` plus the turn, which is where it
belongs. Same defect on `:seon.turn/attempts`, and it already exists at
`resources/seon/schemas/seon.cluster.eval.edn:21-22`
(`:read-evidence [:vector …]`) — fix all three in the wave.

Second question on `evals`: as a **component**, retracting the agent
retracts its entire history. Ruling 47 says program identity rows never
retract; nothing says an agent's does not. State the intent.

`:seon.turn/id` — keep, but see S1: the *evaluation's* identity is the one
that carries weight and §4a drops it.

`:seon.turn/agent`, `/opened-at`, `/closed-at` — needed; `open = no
closed-at` is the right derivation. Keep.

`:seon.turn/wake` — needed; wrong direction is right, wrong cardinality
(see Q9, Case 1). Add `:seon.db/index true`: the pending derivation is a
reverse `not`-scan on it, and `:seon.cluster.run/trigger` carries that
index today for exactly this reason (probed).

`:seon.turn/basis` — needed and load-bearing. Declared
`[:seon.db/commit-id …]`; today's `opening-commit-id` is `:uuid`
(`resources/seon/schemas/seon.cluster.run.edn:36`). §5 wants to re-project
"from `as-of` the turn's basis", and Datahike's `as-of` takes a `:t` or a
date, not a commit id. Say which the re-projection receives and how the
commit id converts — AGENTS.md lists *both* "basis transaction `:t`" and
"commit ID" as the dependency's words, so this is a choice, not a slip.

`:seon.turn/reply` + `/reply-blob` — needed (an interrupted turn's history
must say what the model said); absent = no key ✓. Missing counterpart: the
evaluation gets `:missing :lost` for a reclaimed blob and the reply gets
nothing. Under the owner's own ablation rule, a reply whose blob is gone
needs the same marker or the same derivation stated.

`:seon.turn/attempts` — needed (a paid call is a fact). `[:vector]` → set.

`:seon.turn.attempt/*` — **do not re-home these.** "The existing
`:seon.ai.attempt` keys move here unchanged in meaning" moves provider,
model and token vocabulary out of the AI owner's namespace into the turn's.
Ruling 70 said drop the `cluster` segment, not re-parent `seon.ai`. Keep
`:seon.ai.attempt/*` (it is the AI owner's family, and `seon.ai` is the
single HTTP owner) and have the turn reference it. Also `:prompt-digest
:string` is honest only if it names its algorithm; today the digest
vocabulary is `:seon.blob/digest`-shaped elsewhere — reuse or justify.

`:seon.eval/*` — the namespace **already exists**:
`resources/seon/schemas/seon.eval.edn` declares `allocated-bytes`,
`duration-ms`, `fn-entries`, `host-interop-count`, `outcome
[:enum :ok :time :error]`. Accreting the evaluation entity into it is
right (same subject), but the PRD must say it is an accretion, and it must
reconcile `:seon.eval/outcome` against the proposed `error`/`missing` keys —
two spellings of the same verdict is precisely the "one mechanism" law.
Likewise `:print-length`/`:print-level`: take the print owner's existing
`:seon.print/length` / `:seon.print/level` rather than re-coining under
`:seon.eval`.

`:seon.eval/author [:enum :agent :system]` — the docstring justifies the
enum properly. But §6 deletes generated runs and ruling 71 deletes the
debug page's private evaluation, which are two of its three producers.
Name the surviving `:system` producer or delete the key and derive it (a
system evaluation is one whose turn has no reply).

`:seon.eval/ns`, `/ending-ns` — needed; `ending-ns` present only on change
is the right absent-is-no-key discipline. Keep.

`:seon.eval/value` / `value-blob` / `missing` / `size` — needed; the enum
is bounded, justified, and decides nothing in the loop ✓.

`:seon.eval/out`, `/error`, `/triage`, `/duration-ms`, `/interrupted-at` —
needed. `:triage [:string …]` holding "ex-triage data as EDN" is a string
carrying structure; today's `:seon.cluster.eval/triage-edn` names the
encoding in the key. Keep the `-edn` suffix or declare a real shape.

`:seon.wake/source`, `/opens-turn?` — see B4 and B7. As declared they are
Malli properties, not facts.

Missing from §4a entirely: `:seon.ns/steward` is referenced by §2's prose
and by §3's fault routing but never declared here; and the run family's
other fourteen attributes (`live-processes`, `background-results`,
`supersedes`, `missing-results`, `starting-ns`, `error`, `rule`,
`transition`, `refused`, `turns-remaining`, and the five request maps) are
neither kept nor listed in §6.

---

## Byte identity — every non-determinism in `walk/history` + `repl/text`

1. **A wall-clock time limit inside the projection.** Every render producer
   is invoked through `sci.kernel/invoke` under
   `:seon.sci.eval/time-limit-ms` (`src/seon/render.clj:725-756`). A slow
   machine interrupts a producer that a fast one completes. **This alone
   makes §5's regression unachievable as stated.**
2. **A refused render contributes ABSENCE, not a typed unknown.**
   `history-entries` keeps a unit only when its output is a non-empty
   string and `(nil? (:seon.error/value unit))`
   (`src/seon/render/walk.clj:764-766`). So (1) silently changes the prompt
   bytes. It is also a standing violation of AGENTS.md §2.4 — "an
   unavailable observation is the typed unknown, never absence" — today,
   independent of this PRD.
3. **The projection is not `fn(db)`.** `history-request` requires
   `:seon.sci.eval/ctx`, `:seon.sci.admit/caps`,
   `:seon.sci.eval/time-limit-ms`, `:seon.config/on-core-error`
   (`resources/seon/schemas/seon.render.walk.edn:54-69`), and the render
   candidates are contract-fit against `sci.kernel/context-projection` of
   that ctx (`render.clj:721,733`) — i.e. against *loaded code*. On an
   ordinary cluster the program rows are in the branch, so the basis does
   cover the code; on the development cluster (in-place adoption) it does
   not. §5 must state the qualification: same db **and** same adopted
   commit.
4. **The render profile is derived per call from the agent's cluster ref.**
   `render/request-profile` (`render.clj:68-91`) joins
   `:seon.cluster.agent/cluster` → `:seon.cluster/name` →
   `config/effective`. §2 deletes that attribute (see S2), and until it is
   carried the profile is an out-of-band input to the "pure" projection.
5. **A second elision point, before the AI boundary.** The acquisition walk
   truncates every connection at `:seon.config.eval.result/max-collection`
   and emits an `::elided` node (`src/seon/render/walk.clj:208, 249-268`).
   The owner ruled elision happens *only* at AI context generation. §5
   deletes the admission caps but never names this one, so it either
   survives as a second elision point or vanishes and leaves the
   neighbourhood unbounded.
6. **`observation-basis` falls back to the live `db/basis-t`**
   (`walk.clj:724-728, 759`) — metadata, not prompt bytes, but it makes two
   projections of one db unequal as *values* while equal as bytes. Say
   which equality the regression asserts.
7. **`:seon.repl/result` embeds a `:db/id`** (`src/seon/repl.clj:252-254`,
   `admit/result-handle`). Stable under `as-of` on one store; not stable
   across a reset/reseed. Fine for §5's stated regression; state the limit
   so nobody later "fixes" a reseeded mismatch.

Nothing else in that path reads a clock or a random source: the four
`(Date.)`/`random-uuid` sites in the render tree are all web-side or cost
metadata (`render.clj:723`, `render/web.clj:1554, 3000, 3206, 3485`).

---

## What Datahike already provides that the PRD would re-implement

- **`as-of` / `history` / `since`** — §5's "project from `as-of` the turn's
  basis" needs no machinery; only the `:t`-vs-commit-id conversion (F4).
- **The serial writer per connection** — no epoch, no lease, no lock. The
  PRD is already right to delete custody; the reason is Datahike's, not
  ours (`reference-code/datahike/src/datahike/writer.cljc`).
- **`:db.fn/call` transaction functions reading the mid-transaction value**
  — strictly stronger than `:db.fn/cas`, and §7's four fences are exactly
  this. Correct as designed; do not add CAS (`run-loop-unpacked` §4).
- **`d/listen` firing inside the transaction's go block** — the wake is the
  dependency's, which is why §3's "the listener is the one that exists" is
  right and why the handler may not query (B7's consequence).
- **`:db.unique/identity`** — see S1: §7 demotes an identity fence to a
  transaction-function check.
- **`:db/index`** — the pending derivation's index is a declaration, not
  code; §4a omits it on `:seon.wake/turn`.

---

## Ranked findings

### Blockers

**B1. The claim direction contradicts ruling 70 and costs more.** §3/§4a put
`:seon.wake/turn` on the item; ruling 70 says "a claim ref from the handling
run" (`design-ideas-ledger-2026-08-13.md:1058-1061`), which is also what
exists (`work.clj:604-621`) and what needs one index instead of N. Move it
back to `:seon.turn/wake`.

**B2. `turns-left` replaces a derived bound with a stored counter and
deletes the human-message reset.** `bootstrap.clj:47-70` +
`work.clj:378-437`. Three named proofs die and no replacement reset
mechanism is specified. If the derivation is too slow, store the episode
*anchor*, not a decrementing counter.

**B3. `:seon.turn/wake` is cardinality-one, so two items in one transaction
cost two paid model calls on identical contexts.** `work.clj:449-452`
(`first`), trigger probed `:db.cardinality/one`. Make it many and claim
every item pending at `:seon.turn/basis`.

**B4. "A new source is one schema property, no loop change" is false as
written.** Attribute entities carry only Datahike's eight properties
(probed over 582 attributes); only three named properties are lifted onto
schema rows, from a hand list at `src/seon/schema.clj:1419-1420`; and
`route!` still needs a hand-edited `case` branch per source
(`wake.clj:232-250`), which duplicates the hand list at `wake.clj:93`. Under
§2.2 "which attributes can wake an agent" must be a Datalog query. Fix by
generalising the lifting (S3) and deriving the `case` from the query.

**B5. Byte identity is unreachable while a render producer runs under a
wall-clock limit and its refusal is absence.** `render.clj:725-756` +
`walk.clj:764-766`. A refused render must contribute a *stable typed
unknown* naming the failed producer, per §2.4 — then the bound can fire
without moving a byte.

**B6. §3 does not say when the claim is taken.** At basis or at settlement
changes whether a mid-turn arrival is answered or dropped (Case 2). Basis.

**B7. `:seon.wake/opens-turn?` cannot express the self-steward exclusion,
and §3 reinstates the 2026-08-08 escalation loop.** The property is
per-source; the guard is per-item (`loop.clj:682-696`, nine paid calls in
twenty minutes). Either the pending query excludes items whose steward is
the item's own subject agent, or the fault committer must not assert
`:seon.error/to` in that case — decided **inside** the committing
transaction, not by a pre-read (owner law).

### Simplifications — delete more

**S1. Keep the evaluation's `:db.unique/identity` and delete a fence.** §4a
gives the evaluation no identity, and §7 then re-adds "one evaluation per
(turn, ordinal)" as a `:db.fn/call`. Today that invariant is structural:
`:seon.cluster.eval/id` is `:db.unique/identity` over `(run, ordinal)` and
`receipt-start-call`'s `::receipt-exists` rides it
(`run.clj:1204-1226`). A declared identity is cheaper and stronger than a
transaction function. Declare `:seon.eval/id` and delete the fence.

**S2. `:seon.cluster.agent/cluster` is genuinely derivable — probed.**
Exactly one `:seon.cluster/name` entity exists per branch db. The DELETE is
safe, but do it with ONE derivation (`the cluster entity of this database
value`) rather than threading a name through the eleven call sites
(`render.clj:79-85`, `render.clj:1352`, `cluster.clj:2262-2275`,
`agent.clj:144,158,189,237`, `bootstrap.clj:285`,
`render/transcript.clj:1118`, `my/plan.clj:239`). `render/request-profile`
is the load-bearing one.

**S3. Lift every `seon.*` attribute property onto the schema row.**
`render-declaration-properties` (`schema.clj:1419-1420`) is a three-element
hand list — the banned substitute, in the schema owner itself. Generalising
it deletes the list, makes `:seon.wake/source` a queryable fact, and
dissolves half of B4.

**S4. Collapse the two hand lists of the wake set.** `wake-attributes`
(`wake.clj:78-93`) exists to be *compared* against
`loop/committed-attributes`; `route!`'s `case` is a second copy of the same
set. One derived set, one dispatch.

**S5. Name the walk's own elision in §5/§6.** `walk.clj:208, 249-268`. It is
either the second elision point the owner ruled out, or the neighbourhood
bound the caps deletion silently removes. It cannot be neither.

**S6. Say what happens to the other fourteen run attributes.** §4 keeps
eight; `seon.cluster.run.edn` declares roughly twenty-two.
`live-processes`, `background-results`, `supersedes`, `missing-results`,
`starting-ns`, `error`, `rule`, `transition`, `refused`,
`turns-remaining` and five request maps are unlisted in both §4 and §6. An
unlisted attribute survives by accident.

**S7. Delete `:seon.eval/author` if its last producer goes.** §6 deletes
generated runs; ruling 71 deletes the debug page's private evaluation.

### Frictions

**F1.** `:seon.eval` already exists with five gauge attributes including
`outcome [:enum :ok :time :error]`. Declare the new keys as an accretion
into it and reconcile `outcome` against `error`/`missing`.

**F2.** `:seon.error/to` collides conceptually with the existing
`:seon.error/agent`, "the agent this fault happened to"
(`seon.error.edn:1-7`). Those are two relations and the PRD names one. Also
the steward is derivable (fault → function → namespace →
`:seon.ns/steward`); a stored routing edge is justified *only* by the
listener's no-query prohibition (`wake.clj:29-32`) — state that
justification, and compute the steward inside the committing transaction.

**F3.** §1a "deletes the resume arm" contradicts §7, which keeps it as
`:evaluate`. Only the custody predicate dies. Correct the sentence.

**F4.** `:seon.turn/basis` as `commit-id` vs `as-of`'s `:t`.

**F5.** Take `:seon.print/length` / `:seon.print/level` and the `-edn`
suffix on `triage` from their existing owners rather than re-coining.

**F6.** Keep `:seon.ai.attempt/*` in the AI owner's namespace.

**F7.** No `missing` counterpart for a reply whose blob was reclaimed.

**F8.** `:seon.agent/evals` as a component means an agent retraction
retracts its whole history. Intended?

### Agreement

- Three writes per turn (open, freeze, settle) is minimal given a paid call,
  and the reasoning in §1 is exactly right.
- Deleting the process stamp, `claim-call`'s takeover, `release-call`,
  holder-only close and the agent run pointer is safe, and the premise is
  stronger than §1a claims (one JVM per *root*, probed).
- Deleting `situation`, `plan-digest`, `undisposed-at`, the context capture
  and contribution rows, `:seon.ai.attempt/ordinal`, `:seon.def/*`,
  `:seon.render/units` and the gate counters is correct on the evidence.
- "Pending is a query over the absence of a claim ref" is already how the
  system works, and is the right model; §3 only mis-orients it.
- Four `:db.fn/call` fences and no CAS is the correct conclusion.
- The `missing`-with-reason enum and the handle-ablation rule are a good
  design: absence of a value becomes a typed fact rather than silence.
