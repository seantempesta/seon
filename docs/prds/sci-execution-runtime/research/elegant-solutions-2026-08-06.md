---
type: research
status: complete
tags: [research, runtime, agent]
---

# Elegant solutions pass over the root-cause synthesis — 2026-08-06

## Method and reading proof

I read end to end, in order: the repository authority (`CLAUDE.md`/
`AGENTS.md`, including the vocabulary table and the simplification test);
[root-cause synthesis](docs/prds/sci-execution-runtime/research/root-cause-synthesis-2026-08-06.md);
[live drive](docs/prds/sci-execution-runtime/research/live-drive-2026-08-06.md);
[live-drive observer](docs/prds/sci-execution-runtime/research/live-drive-observer-2026-08-06.md);
[atom census](docs/prds/sci-execution-runtime/research/atom-census-2026-08-06.md);
the `seon-flow-architecture` skill; the vendored flow source
(`reference-code/core.async/.../flow.clj`, `flow/impl.clj`, `flow/spi.clj`);
and the live seams: `src/seon/cluster/loop.clj` complete (1,906 lines),
`src/seon/cluster/agent.clj` (turn-step and blueprint),
`src/seon/cluster/prompt.clj` complete, `src/seon/sci/eval.clj:1290-1430`
(fork-for-turn/agent defs), `src/seon/render/web.clj:750-810` (context pass),
`src/seon/flow.clj:60-130` (var-process). Production source was not edited.

## Verdict in one paragraph

The synthesis's *diagnosis* is sound. Its *solutions* over-mechanize four of
the seven roots. R1, R2, and R3 are not three contracts needing three new
owners — they are one already-ruled shape not yet applied: **the turn is a
pure reduce over values whose only durable exits are transactions, and every
truth the loop asserts derives from a transaction report it already holds.**
R4's owner reaction ("error on budget, auto-compact, retry") replaces a
system-wide composition owner with three one-screen fixes at existing owners.
R5 and R7 are essentially right and I defend them. R6 is right except that its
"four-part contract" phrasing invites a resource framework nobody should
build. The largest single simplification is the run loop itself: roughly half
of `loop.clj` is accidental complexity that the messaging wave, the
eid+commit-id ruling, and a single terminal settlement owner jointly delete.

---

## The run loop — the merged R1 answer

### What the minimal loop is

The ruled semantics need exactly this per episode, and nothing else:

```text
claim        one custody transaction (run/open-tx + run/claim-tx)
prompt       derived from the run's OPENING database value; captured; sent
freeze       reply sources → plan-tx (one transaction)
reduce       for each ordered form:
               evaluate in the turn fork at the previous step's db-after
               settle: ONE terminal transaction — receipt + disposition
                       + deliveries + rows for the agent's defs + error fact if any
close        the disposition IS the close; nothing after it
```

Every arrow is already a committed fact, which is why
`work/next-agent-work` can re-derive the situation (`:open`/`:call`/
`:resume`/`:close`) from facts after any crash. **The loop is already a
state machine over transactions; the accidental complexity is that inside
one pass, phases are stitched with throws and five bespoke failure paths
instead of values reaching one settlement.**

### One total settlement owner (the R1 mechanism)

Today `loop.clj` has five distinct failure-settlement paths:
`refused!` (:704), `terminal-refused!` (:731), `terminal-settlement-fault!`
(:714), `settle-pre-evaluation-fault!` (:844) plus the
`pre-evaluation-call`/`ex-info`/re-catch relay (:896, :1809-1825), and
`call-turn`'s closure-captured `fail!` (:1350). Each hand-assembles a
slightly different receipt/close/error combination. The wedge the live drive
observed exists because `fork-for-turn` ran *outside* all five (before
`settle-pre-evaluation-fault!` landed) — the throw escaped to Flow's error
channel (`flow/impl.clj:312-319`), a core fault committed, and no terminal
run fact did.

**The elegant mechanism:** every phase returns a value; one function is the
only exit.

- `turn` composes `claim → fork → prompt → evaluate* → …` where each step
  returns either its product or a flat `:seon.error` value, and the composition
  short-circuits any error value **into the same one function**:
- `settle!` — the sole writer of a run's terminal state. Input: the run id,
  the ordinal (or none, when no evaluation began), and a disposition value
  (admitted result, `:my.run/value`, or error value). Output: **one
  transaction** built by the existing `run/receipt-settle-tx` /
  `run/close-tx` / `error/commit-tx` constructors, carrying receipt (when an
  evaluation began), close, error fact, rows for the agent's defs, and delivery rows
  together. This is `terminal-tx` (:291) generalized to accept the error
  disposition, replacing the other four paths.

The owner's R1 wish — "the error goes to the root agent, flowing back
through the error messaging" — needs **no new channel**. `error-tx` (:606)
already threads `:seon.config.error/escalate-to`, and delivery rows already
ride the terminal transaction (:1747-1756). A pre-evaluation failure settles
as: run close, zero receipts, one durable error fact, one ordinary message
row addressed to the escalation agent — all in `settle!`'s one commit. The
recipient's wake is the message commit itself (`wake.clj` listener), which is
the existing transport, not a new one.

This also answers the synthesis's owner question 1 without a ruling:
**run-level refusal with zero receipts falls out of the structure** — a
receipt exists exactly when an evaluation attempt began, because only the
evaluate step contributes an ordinal to `settle!`. Question dissolved.

- **Invariant:** after custody commits, the only exits from `turn` are
  `settle!`'s transaction or a process death that boot recovery already
  owns; no Throwable path bypasses settlement because no phase throws.
- **Class-killing regression:** a generative state-machine property that
  injects a failure value at every phase (fork, prompt, capture, freeze,
  admission, evaluation, delivery construction) and asserts the wanted
  behavior: the run reaches exactly one terminal fact, the escalation
  message exists, and `next-agent-work` derives fresh work on the next
  wake. One property, one class.
- **Deletions:** `refused!`, `terminal-refused!`,
  `settle-pre-evaluation-fault!`, `pre-evaluation-call`, the
  `resume-turn` catch relay, `fail!`'s bespoke assembly — ~380 lines →
  one `settle!` plus one refused-transaction branch (~80 lines). Also the
  `receipt-request`/`terminal-tx` double-build of the same map (:247-346,
  ~100 lines → ~50).

### Should the turn become multiple flow procs? No — and here is the flow-source argument

The owner asked whether converting to flows dissolves the problems. The
grounded answer: **the turn is already flow-native at the correct grain, and
a finer grain would add plumbing without adding a guarantee.**

- A flow transform is one plain function call per message on the proc's own
  thread (`flow/impl.clj:304-305`). Phases-as-procs would put a channel hop
  between claim, prompt, evaluate, and settle. Each hop would carry the turn
  fork — a live SCI ctx, exactly the kind of object the SPI says not to
  transmit (`flow/spi.clj:53-54`) — or re-derive it per phase, paying
  rehydration four times.
- Flow's guarantees do not compose across procs for a *sequential* episode:
  pause/stop are observed at `alts!!` boundaries per proc
  (`flow/impl.clj:284,295`), so a four-proc turn can be stopped with the
  episode stranded between phases; the single-proc turn completes its
  transform before any transition (`flow/impl.clj:321`), which is the
  property we want.
- The channels between phases would need loss-free semantics (a claimed run
  with a frozen plan is not "losable by construction") — exactly what the
  transport law says means the value belongs in the database. And it already
  is: every phase boundary that matters is a committed transaction, and the
  inter-phase "channel" is `next-agent-work` over facts plus the sliding-1
  self-rewake (`agent.clj:262-263`). **The database is the phase channel.
  Adding real channels would be a second mechanism.**

What flow **already owns**, whose bespoke copies get deleted:

1. **The last-resort fault path.** Transform escapes go to `::flow/error`
   and the fault committer (`flow/impl.clj:312-319`;
   `src/seon/flow.clj:553-602`). With `settle!` total, the loop keeps zero
   deliberate throw-to-reach-the-error-channel sites
   (`terminal-settlement-fault!` shrinks to: settle!'s own transaction was
   refused — the one genuinely unrecoverable case — and even that can be a
   plain `throw` relying on flow's catch, deleting the hand-rolled
   channel-close at :722).
2. **Pass counting and observation.** `::passes`/`::turns` in turn-step
   state (`agent.clj:222,266-267`) duplicate flow's own transform counter,
   reported in every pong (`flow/impl.clj:271,277`). Delete; keep
   `:ping-map-fn` for the run id only.
3. **Serial execution.** The proc loop processes one message at a time
   (`flow/impl.clj:271-322`), so a per-agent turn is serial by construction.
   The armed-ready completion permit (`agent.clj:232-233,272-279`) is a
   hand-rolled second serialization whose stated purpose is the disarm join
   — but the `::flow/stop` transition already fires only between transforms,
   and `turn-step`'s stop arity already offers `::stopped`
   (`agent.clj:224-228`). Two join mechanisms for one event. **Probe, then
   delete the permit:** if disarm's only requirement is "no transform in
   flight," the stop-transition offer is the whole answer.
4. **Hot reload and pause/observe** — already flow's, via `var-process`
   step vars and ping; nothing to add.

**(c) Yes: this restructure IS the R1 totality answer.** A loop small enough
to read — one transform, phases as pure steps, one settlement exit, flow's
error channel as the only escape — makes "custody without terminal fact"
unrepresentable rather than guarded against five times.

### The rest of the loop's complexity, classified

Essential (keep, some relocated):

- **Paid-call fencing** — `call-turn`'s attempt loop, `ai/disposition` as
  the choke point, attempt rows per call, capture-before-provider
  (:1290-1565). This is the best code in the file.
- **Blob economics** — `settlement-result`, `store-def-values!`,
  `result-blob-smaller?` with the measured 743-byte constant (:464-553).
  Essential semantics, wrong home: move to `seon.cluster.run` (or
  `seon.blob`) as the one settlement projection; the loop calls one
  function.
- **Bounded evaluation submission** — `submit-evaluation!!` through
  `seon.flow/submit!!` (:561-593). Keep.
- **Program-row install from `db-after`** (:1766-1780). Keep — it is the
  R2 idiom (below).

Accidental (delete):

- **The five failure paths and double-built receipts** — above.
- **The second admission mechanism.** `lint-form` + `available-functions` +
  the per-form re-lint in `admitted-form` (:72-162, :1145-1179, ~200 lines)
  statically reject a form the evaluator would reject with an honest error
  value anyway. Two mechanisms deciding validity; the eval error face is the
  surviving one (it is what the agent learns from). Delete from the run
  loop; clj-kondo stays at the edit hook where it belongs. (Probe first for
  any class lint catches that the eval face renders worse — if one exists,
  fix the face, not the pre-pass.)
- **Pre-commit string-identity plumbing.** `(str (random-uuid))` run ids,
  `(pr-str [run-id ordinal])` receipt ids, `attempt-id`, `problem-id`,
  agent defs `pr-str` keys. The eid+commit-id log-identity ruling already kills
  this shape; receipts/attempts are identified by their `(run, ordinal)`
  connection and their entity id.
- **Reply-synthesis machinery.** `asked-value`/`delivery-rows` and the
  trigger-derived auto-reply (:627-674) are the messaging wave's owner;
  when M-series lands, the loop's contribution shrinks to "delivery rows
  ride the terminal transaction."

Net estimate: 1,906 lines → ~800, with the blob split relocated and no
mechanism lost. **The conversion test passes: every surviving piece is
simpler than what it replaces.**

---

## R2 — verdict: WTF-replace. The machinery already exists; the missing piece is one sentence

The synthesis recommends "candidate → terminal CAS/admission → install" as a
new cross-owner contract. Step back: **every piece of that already exists.**

- *Candidate isolation:* each turn evaluates in a fresh generation-aware
  `sci/fork` (`sci/eval.clj:1344-1352`), and the schema candidate delta is
  already invocation-local data
  (`:seon.schema.delta/candidate-forms`, atom census, sanctioned row).
- *One terminal transaction decides:* `terminal-tx` already commits the
  receipt, program row, and disposition in one transaction, and Datahike's
  writer serializes it (`reference-code/datahike/src/datahike/writer.cljc`).
- *Only the winner installs:* `install-row!` already reads
  `(:db-after outcome)` (`loop.clj:1766-1780`).

The owner's reaction is the design: "Datahike solves this with the
transaction log — last one wins. What's the problem?" And concurrency in one
namespace is **already unrepresentable**: `:seon.cluster.agent/namespace` is
unique (`resources/seon/schemas/seon.cluster.agent.edn:9-11`), and each
agent's turn proc is serial (`flow/impl.clj:271-322`). Two agents cannot
mutate one namespace; one agent cannot run two turns. The
"concurrent definition receipts diverge" class has no producer left once
receipts derive from the report (next paragraph). Fork-based repair-and-merge
is the ruled editor/revision/proof workflow, already carved — not R2's job.

What genuinely remains is **one gap and one rule**:

1. **Receipts derive from the transaction report.** A receipt may only claim
   what its own `db-after` shows. Concretely: `settle!` receives the
   transaction report and the "success" it records *is* the fact that this
   transaction committed — no pre-commit success construction survives a
   refused transaction, because the refusal value flows back into `settle!`'s
   refusal branch. This is a wording-level tightening of code that mostly
   does this already; it is not new machinery.
2. **Schema redefinition refuses at admission.** The remaining true
   concurrency is two agents transacting the *same globally identified
   schema key*. The authority already rules: "a key's definition never
   changes; new semantics means a new key." So schema admission refuses a
   registration whose key exists with a different declared form — an
   ordinary admission check (the accretion law executed), not CAS machinery.
   Last-one-wins never arises because a *different* second definition is
   refused and an *identical* one re-asserts no datom.

- **Invariant:** durable program/schema truth is what `db-after` contains;
  a receipt never asserts more than its own report; an existing schema
  key's form is immutable at admission.
- **Regression:** two scratch agents transact (a) divergent definitions in
  their *own* namespaces — both succeed, both receipts match `db-after`;
  (b) divergent declarations of one schema key — the second receives a flat
  refusal value and its receipt says so. Asserts wanted behavior, not
  failure prose.
- **Deletions:** the proposed candidate/CAS/adoption contract as a new
  wave; the namespace-mutation classification of solution direction 2;
  the "serialize durable mutation" queue. Nothing to build but the one
  admission refusal and the receipt wording.
- **Owner question:** none. The synthesis's question ("is concurrent
  durable mutation of one namespace required?") is answered by schema the
  owner already ruled: it cannot happen.

---

## R3 — verdict: WTF-replace. The owner's model is the mechanism; one call site violates it

The owner: "A run starts with a `(d/db *conn*)`… stable through the run…
how does it see something new?" Verified: it sees something new because
`call-turn` derives the prompt from a **fresh deref** —
`(prompt/prompt @connection …)` (`loop.clj:1424`) — and the render context
pass walks whatever database value it is handed
(`prompt.clj:87`, `render/web.clj:791`). The run's opening moment is already
a fact (`:seon.cluster.run/opened-at`, `:seon.cluster.run/opening-commit-id`,
`run.edn:10-11,39-43`). The live-drive leak (message B inside run A's
prompt) is exactly this one violated basis.

**The elegant mechanism — no causal-closure query, no new attributes:**

1. `run/opening-db`: the database value at the run's opening — Datahike's
   own `as-of` at the open transaction (`seon.db/as-of` exists,
   `db.clj:858-868`; the open datom's transaction is the time point, derived
   not stored). `call-turn` passes `(run/opening-db @connection run-id)` to
   `prompt/prompt`. A message committed after the run opened is invisible
   **by construction** — the class is unrepresentable, not filtered.
2. Continuation is already structural. `my.run/wait` *closes* the run
   (`terminal-tx`, `loop.clj:341-346`); a delivered result commits a message;
   that commit wakes the agent; a **new run opens with the delivery inside
   its opening value**. The synthesis's "explicit continuation/wait edge" is
   simply: new episode = new run = new opening basis. Nothing to add.
3. During the reduce, each form evaluates at the previous terminal
   transaction's `db-after` — the run's own causal chain, values already in
   hand. Foreign facts inside that value are ordinary last-one-wins weather,
   per the owner.
4. O4 terminality (the drive-side hole) is a *query*, not a mechanism: the
   transitive closure over the refs that already exist —
   `:seon.cluster.run/trigger`, `:seon.cluster.message/trigger`, delivery
   rows — as one recursive Datalog rule the drive and forensics share.
5. The 16/17 missing message ordinals dissolve by derivation: **arrival
   order is transaction order.** The datom's transaction is the ordinal;
   a stored `:seon.cluster.message/ordinal` is needed only to order several
   messages inside one transaction. Derive, don't store; delete the
   "backfill ordinals everywhere" repair.

- **Invariant:** a run's prompt is a pure function of its opening database
  value; later facts reach an agent only as the trigger of a later run.
- **Regression:** open a run for trigger T₁ at basis t, commit message B at
  t+1, derive the prompt — assert B is absent and T₁ present; then close and
  assert the next derived work is a run triggered by B whose prompt contains
  B. (Kills both the leak and the "unclaimed message" class in one
  behavior test.)
- **Deletions:** the proposed causal-closure attribute set and its
  transitive prompt-inclusion query; the batched-trigger design question;
  the ordinal backfill.
- **Owner question:** none remaining. Both synthesis questions
  (visibility, one-run-per-trigger) are answered by the mechanism the owner
  already described.

---

## R4 — verdict: simplify. Three small invariants at existing owners, not one composition owner

The synthesis's "one final projection/composition owner" is the umbrella the
authority warns against. The owner's reaction is the right size: "error if
it's hit its budget, auto compact and try again." R4 splits into three
one-owner fixes plus the error-model wave that already exists:

1. **Budget = error value + auto-compact + retry.** The walk already has one
   size knob: `:seon.render/distance` (`prompt.clj:88-90`). Prompt
   derivation measures the fitted text (`seon.ai.tokens/estimate`, already
   the law) against the provider budget (a `:seon.config.ai/*` fact). Over
   budget → re-acquire at distance-1; still over → return a flat
   `:seon.error` value, which `call-turn` already settles as a refused
   prompt (`loop.clj:1469-1474`). ~15 lines in `prompt/prompt`. The 44k-token
   prompt class dies at its consumer, and `seon.print/fit` remains the one
   fit owner below it. No new fit authority.
2. **Error values short-circuit before dispatch.** `seon.db/q` (and sibling
   reads) test the database argument for `:seon.error/kind` first and return
   it — one guard in the one database namespace. Kills the error-becomes-nil
   class.
3. **Identity is supplied, never invented.** `render.value/node-id`'s
   anonymous fallback (`render/value.clj:27-41`) is deleted; a rendered root
   without a database identity requires a caller-supplied block id (the
   walk always has one — the block is the ruled render unit). Kills the 185
   duplicate DOM ids as a class.

The remaining R4 items (bounded error faces, named producers for important
schemas, MCP envelopes) are the error-model PRD W1–W5 and the standing
ugly-output order — already owned; do not gather them under a new owner.

- **Invariants:** the prompt never exceeds the provider budget (it compacts
  or errors); an error-valued database argument propagates unchanged; every
  rendered unit's identity is supplied by its caller.
- **Regressions:** (1) a walk forced over budget yields a compacted prompt,
  then an error value at distance 0 — and the run settles, never freezes;
  (2) `(seon.db/q query error-value)` = the error value; (3) rendering two
  anonymous roots on one page is a refusal, not a collision.
- **Deletions:** the system-wide composition-owner wave as a unit;
  `node-id`'s fallback arm; the synthesis's owner questions 1 and 4 (both
  answered structurally). Question 2 (causal blocks vs broad neighborhood)
  is largely mooted by R3's opening-basis walk plus the budget error;
  question 3 (wildcard pulls at promised boundaries) is a real but small
  rule — adopt recommendation A without a ruling: a function promising a
  registered schema pulls what the schema names.

---

## R5 — verdict: adopt, with one sharpening. This one is already minimal

Deriving the activation closure as a publication-time query is the
"everything declared and queryable" law applied to boot, and no simpler
mechanism exists: every witnessed defect (missing maintenance attribute,
missing model schemas, stale source, missing defaults) is a missing fact at
the same boundary. Enumerations would preserve the root; the query kills it.
Defended as-is, with two sharpenings that *reduce* its scope:

- The per-cluster schema projection **already exists**
  (`::projection-state` in the cluster ctx, `sci/eval.clj:1442-1443`,
  census-sanctioned). The `!schema-state` violation is repaired by deleting
  the global projection half into that existing mechanism, not by building a
  new acquisition path. The predicate-callable cache alone stays
  process-local.
- The 22 load-time registration sentinels are the same defect as the
  missing-attribute defects — declaration by side effect instead of by fact.
  The closure query subsumes their repair: declarations are data the
  publication admits, so the sentinels are deleted, not fixed.

Owner questions 1 (refuse stale activation — recommendation A) and 2 (exact
program facts, not `ns-interns` — recommendation A) are worth sealing but
both recommendations follow directly from sovereignty and ruling #20's
program-graph wording; I found no live argument for the B options.
Questions 3–5's recommendations likewise stand; only question 4 (analyzer
veto blast radius) genuinely needs the owner, because it trades publication
strictness against velocity and the elevation mechanism never reproduced.

---

## R6 — verdict: adopt the baseline, refuse the framework, and keep the two real owner calls

Direction 1 is right *because* it is per-owner strengthening; the "four-part
contract" phrasing should never become a protocol, base map, or resource
registry — the synthesis itself says this, and it bears repeating because
"require the contract at every owner" is one refactor away from a manager.
The concrete open items are each one owner: eval-sample retention (a
declared window + blob-backed captures — recommendation A), invalid external
claims (a terminal transition at reconciliation — recommendation A; the
status flood dies with it), and the core-fault overflow (commit the coalesced
drop fact through the fault committer — recommendation A, accepting the
crash window; zero-loss identity under unbounded producers is a real
impossibility, correctly named).

The heap-topology question is the one place I sharpen: the synthesis is
honest that no in-process scheme yields a hard JVM fence, so the ruling is
binary and *cheap to defer* — co-hosting already works for dev, and
"one agent-executing cluster per operator root" is an operational choice,
not a code mechanism. Recommend sealing it as: **hard isolation when wanted
is a deployment topology (separate roots), never an accounting subsystem** —
which closes the issue without building anything.

Remaining owner calls: heap topology (as phrased above) and eval-retention
window size (a number, not a design).

---

## R7 — verdict: adopt. This is already minimal — with one composition note

One recurring scratch-cluster causal drive inside `bin/test`, deterministic
provider per checkpoint, shipped provider + graphical QA at release cadence:
defended as written. No simpler mechanism exists because the class being
killed is precisely "the parts were never exercised together" — only a
composed subject kills it. The minimal run loop above makes this scenario
dramatically cheaper to keep green: fewer phases, one settlement exit, and
each R1–R4 regression above *is* a fault injection this scenario replays.
The remaining owner call is only the provider cadence (synthesis question 1;
its recommendation is right) and the graphical-QA cadence (question 2;
recommendation right).

---

## Revised minimal solution sequence

1. **Minimal turn** (merges R1+R2): phases as values, one `settle!`, receipts
   from the transaction report, schema-key immutability at admission;
   delete the five failure paths, the loop's second admission mechanism,
   pre-commit string identities, `::passes` counters, and (after the disarm
   probe) the completion permit. The generative phase-failure property lands
   with it.
2. **Opening-basis prompt** (R3): `run/opening-db` via `as-of`; one changed
   call site in `call-turn`; the leak/unclaimed-message regression; derive
   message order from transactions.
3. **Budget error + auto-compact, db error short-circuit, supplied identity**
   (R4 core): three small diffs at `prompt.clj`, `seon.db`, `render.value`.
4. **Activation closure query** (R5): at the existing publication owner;
   delete the global schema projection into the existing per-cluster
   mechanism and the load-time sentinels.
5. **R6 singles**: coalesced drop fact; claim terminal transition; retention
   window; seal the heap ruling as deployment topology.
6. **R7 recurring drive** in `bin/test`, absorbing each step's falsifier as
   it lands; graduation gate at the end.

Owner questions that genuinely remain (everything else above dissolves):
analyzer veto blast radius (R5 q4), heap topology as deployment-not-code
(R6 q1, recommend sealing the phrasing given), eval-retention window (a
number), provider/graphical cadence (R7 q1–q2, both recommendations fine).
