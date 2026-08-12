---
type: research
status: complete
tags: [research, audit, agent, runtime, delegation, testing]
---

# generate-code v0 falsification review

## Verdict

**REJECT FOR SEAL: 10 SEAL-BLOCKING, 2 REVISION, 6 NOTE.**

The plan does not compose the landed mechanisms into a loop. The first repair
turn has no trigger; the proposed per-form namespace would disagree with the
namespace in which the evaluator actually runs; and ordinary agent messages
cannot carry `:seon.cluster.message/about`. Even after adding that field,
delivery evidence is neither an atomic uniqueness fence nor evidence that an
owner repaired anything. A non-replying owner therefore makes a live problem
disappear from delegation forever. The actual fold also has the exact
open issue the plan treats as a benefit: it continues after a failed form and
can commit a later false completion. The capability needs a contract reseal,
not implementation against this document.

## Review basis

Reviewed target:
`docs/prds/sci-execution-runtime/plan/generate-code-v0-plan-2026-07-29.md`
at commit `aa8f2c24f68abb3412d2ee8e214e4e5e0af1fce1`.

Current-tree basis was
`c7725253a6c1ce89e30859e45b6173e323f463f9`. The source and test paths cited
below were clean in the shared checkout; unrelated oversight/render edits were
present and were not read as evidence for this review.

Dependency ledger:

- Clojure 1.12.5, selected by `deps.edn:15`.
- SCI from `reference-code/sci` at
  `8fac6e88f32d`, especially its namespace binding
  semantics as exercised through the first-party evaluator.
- Datahike from `reference-code/datahike` at
  `9a7a9ef10a95`, especially refs, unique-value
  enforcement, and serial transactions.
- core.async 1.10.874-alpha3, with vendored source at
  `dc35f3e0d7bc`; the first-party evidence is the
  single per-agent turn proc and its sliding wake connection.
- First-party owners:
  `src/seon/cluster/{agent,work,loop,message,reply}.clj*`,
  `src/seon/sci/eval.clj`, `src/seon/problems.clj`,
  `src/seon/test/runner.clj`, their schema EDN, and their discovered tests.

## Current-tree verification

These are the six NOTE findings. They distinguish what actually landed from
what the plan only proposes.

| # | claim | current-tree verdict |
|---|---|---|
| N1 | P2 test-result refs landed | **Verified.** `src/seon/schema/test.edn:35-47` declares result identity plus exact refs to `:seon.test` and `:seon.test.run`; `src/seon/test/runner.clj:123-174` creates those connected rows. `test/seon/test_runner_test.clj:49-79` joins failing result → exact test → namespace → owner and result → run. Commit `4128c281d`. |
| N2 | P3 namespace assignment landed | **Verified.** `src/seon/schema/agent.edn:1-14` makes the namespace a unique-value ref and makes namespace name required by the one formal creation request. `src/seon/cluster/agent.clj:81-106` commits namespace plus agent together and implements `owner-of`; `test/seon/cluster/agent_namespace_test.clj:14-65` proves creation, lookup, reassignment, and uniqueness. Commit `d8f94f7db`. |
| N3 | form entity attributes | **Verified absent.** The landed form entity has exactly `/id`, `/run`, `/ordinal`, and `/source` in `src/seon/schema/run.edn:59-70,93-96`; `plan-call` writes the same four in `src/seon/cluster/run.cljc:396-405`. `/ns` is a proposed delta, not a landed precondition. |
| N4 | message `about` is an indexed ref | **Verified, narrowly.** `src/seon/schema/error.edn:181` declares it. Only `src/seon/error.clj:683-703` writes it; the ordinary message entity in `src/seon/schema/message.edn:36-44` does not include it. |
| N5 | max-chain guard landed | **Verified.** Default is 16 (`config/default.edn:65`). `src/seon/cluster/message.cljc:228-260` returns zero rows plus a flat error when the dial is absent or depth exceeds it. It is a delivery backstop, not a repair-progress proof. |
| N6 | remaining introductory `rg` claims | **Verified as primitives, not as composition.** Per-agent graphs, custody-as-presence, the reply splitter, the two admitted value families, distance walk/agent pilot, and six `seon.problems` families exist. P1 remains absent: `:seon.error/data-edn` is still a string (`src/seon/schema/error.edn:55`). |

Focused recurring proof:

```text
bin/test seon.test-runner-test seon.cluster.agent-namespace-test
         seon.cluster.message-test seon.cluster.reply-test
         seon.cluster.loop-test seon.cluster.work-test
         seon.sci.eval-test

Ran 44 tests containing 168 assertions.
0 failures, 0 errors.
```

That green result verifies the rows above. None of those tests claims the
generate-code loop proposed by the target plan.

## SEAL-BLOCKING findings

### S1. No fact wakes the planner for the proposed repair turn

The plan says the planner's next turn is triggered by the existing self-rewake
when problems remain. That work does not derive.

`next-agent-work` has only four non-idle situations: resume a held plan, call
for a held unplanned run, open for an unanswered message, or close a fully
settled run (`src/seon/cluster/work.cljc:14-32,259-301`).
`more-agent-work?` is exactly a non-nil `next-agent-work`
(`work.cljc:303-312`). `seon.problems/problems` is a pure read and is not an
input to either function (`src/seon/problems.clj:191-227`).

After the initial plan's last receipt and close, no unanswered message exists
for the planner. The graph parks. Act 3's “planner's next turn” therefore never
happens unless a human or another agent sends an additional message. Adding a
problems-driven wake would be a new trigger contract and must be designed as
such; calling the current fold/close rewake that mechanism is false.

### S2. Parse-time namespace and evaluation-time namespace disagree

The splitter returns only source strings
(`src/seon/cluster/reply.cljc:34-42,286-319`). It neither receives the starting
agent namespace nor returns parse state. More importantly, the evaluator
rebinds SCI `*ns*` to `my.agents.<id>` separately for every form
(`src/seon/sci/eval.clj:319-342`). A shared SCI context preserves definitions,
not the dynamic namespace binding.

Two direct probes over the production splitter plus evaluator, sharing one
per-run SCI context, returned:

```clojure
reply: (in-ns 'my.gen.alpha) (def x 1) (str *ns*)
forms: ["(in-ns ...)" "(def x 1)" "(str *ns*)"]
def:   #'my.agents.planner/x
last:  "my.agents.planner"

reply: (ns my.gen.beta) (def y 2) (str *ns*)
def:   #'my.agents.planner/y
last:  "my.agents.planner"
```

The first form can transiently return a namespace object, but the next
evaluation is rebound to the planner's home namespace. A parser that carries
the preceding `(ns ...)` or `(in-ns ...)` forward would therefore commit a
false `/ns` ref. The plan must first decide whether multi-namespace execution
is real; a stored attribution cannot manufacture it.

The suite sketch compounds the mismatch: it requires a namespace to appear in
each form's “own source prefix,” while the proposed inheritance rule assigns a
later `(def ...)` from a preceding namespace form that is not in that source.
The stated property rejects the design it is supposed to prove.

### S3. A namespace-less planner contradicts formal creation and the landed view

P3 did not make namespace assignment optional in the formal creation path.
`:seon.cluster.agent/creation-request` requires both agent id and namespace
name (`src/seon/schema/agent.edn:8-14`), and `agent/creation-tx` commits both
without an ownerless basis (`src/seon/cluster/agent.clj:81-93`). The actual root
uses that path with `my.agents.root` (`src/seon/cluster.clj:537-542`).

The landed agent-view pilot is also single-root: it starts the walk from
`[:seon.cluster.agent/id agent-id]` (`src/seon/render/agent.clj:118-134`).
The prompt invokes installed blocks for that one agent
(`src/seon/cluster/prompt.cljc:151-196`). There is no landed operation that
extracts several namespace roots from free-form goal text and substitutes
them for a namespace-less agent's view.

The plan must choose:

- bypass the one formal creation contract to create an exceptional ownerless
  planner, introducing a second creation path; or
- assign the planner a namespace, forfeiting the claimed structural
  self-recipient exclusion.

“Namespace-less, therefore unrepresentable” is not a property of the landed
system.

### S4. Ordinary sends cannot carry `about`

`my.message/send` returns a closed two-field map containing only recipient and
content (`src/my/message.cljc:72-100`;
`src/seon/schema/message.edn:60-73`). The driver's delivery request also has no
`about`, and the committed row writes id, to, from, content, and time only
(`src/seon/cluster/message.cljc:201-218,279-287`).

The plan's example send forms therefore cannot produce the two `about` refs
Act 3 requires. The existing attribute being type-compatible does not connect
it to this path. Supporting exact delegated refs requires a revised
agent-facing value or a system-side derivation with an explicit input and
owner. Either change falsifies “everything landed except `:attributed`, `/ns`,
and the drive” and the four-owner touch list.

### S5. Exclusion at render time is not an idempotency fence

Even if `about` were added to ordinary messages, a query that excludes already
messaged problems is only a read. There is no unique compound identity for
`(problem, owner)` and no transition that rechecks absence in the terminal
transaction.

The exact requested same-planner race is prevented for a narrower reason:
one agent has one open run, and its one turn proc is sequential
(`src/seon/cluster/work.cljc:21-25`; `src/seon/cluster/agent.clj:155-168`).
Two turns for the same planner do not execute concurrently. That does not make
the `about` ref an idempotency mechanism:

- one frozen plan may contain two send forms for the same problem and owner;
  their ids differ by `(run, ordinal, index)`, so both commit;
- two designated planners, or any future caller of the same derivation, can
  read absence and commit distinct message ids; and
- replaying the derivation at a basis proves only what the read returned, not
  what a competing terminal transaction can commit.

The suite must drive the production transition with latches and prove a
commit-time uniqueness/refusal invariant. A sequential `{derive, send, crash}`
model cannot prove the missing race.

### S6. A non-replying owner turns exclusion into silent loss

The stopping rule treats “has an owner and a committed delegating message” as
terminal enough to end the episode. The derivation then excludes the problem
forever because the message is durable. Nothing records repair, acceptance,
refusal, supersession, or abandonment.

Adversarial history:

1. Problem P is attributed to owner A.
2. Planner commits message M with `M/about = P`.
3. A opens its run and then waits forever, fails before completion, is paused,
   or deliberately never replies.
4. Every later derivation excludes P because M still exists.
5. P remains red, but is neither re-delegated nor root-unattributable; no event
   wakes the planner and the “all entries delegated” stopping rule calls the
   episode done.

This is silent loss, not termination. Delivery evidence can prove at-most-once
notification. It cannot prove the problem was repaired. The plan needs a
derived outstanding/settled contract that remains visible when the owner does
not complete.

### S7. The actual fold continues after failure and can complete with a lie

The plan's factual claim is correct and its judgment is not: failed evaluation
becomes a terminal receipt, `next-agent-work` selects the next unsettled
ordinal, and the loop recurs (`src/seon/cluster/work.cljc:96-124`;
`src/seon/cluster/loop.cljc:744-877`).

The owning issue is open, not archived:
`docs/seon/issues/a-failed-form-does-not-stop-the-fold.md`. Its live evidence
shows form 0 failing to define a var and form 1 completing with an `Unbound`
marker, which was delivered to another agent as a confident answer
(`a-failed-form-does-not-stop-the-fold.md:8-44`).

Sibling preservation does not require evaluating forms authored against a
state that failed to materialize. Accepted earlier receipts can remain
terminal while the fold stops at the first red form. Until the open issue is
ruled, the plan may not call continuation “free” or use a later completion as
acceptance evidence.

### S8. “The run is the attempt” does not scope later planner runs

The initial planner run closes. Every owner reply is a new message and opens a
new planner run. P2 rows reference a test run, not the initial planner run;
`seon.problems` is deliberately cluster-global; and the proposed `about` ref
points from one delivery to one red fact. No query in the plan defines which
problems belong to the original goal across those later runs.

Consequences:

- unrelated cluster failures can enter the standing planner's block;
- two human goals handled by the same standing planner can consume or exclude
  each other's failures; and
- “the attempt's problems are empty” is not a query anyone can run because
  the plan supplies no attempt-scoping relation.

A message-chain derivation might provide the missing scope, but it has to be
specified and tested. Retiring the attempt entity is defensible only after an
existing fact path is shown to answer the same question.

### S9. The default episode cap deterministically starves 100 delegates

The episode count is inclusive of the run answering the last outside trigger
(`src/seon/cluster/work.cljc:153-189`). At the default limit 100, an
agent-sent trigger cannot open once the count is already 100
(`work.cljc:201-225`; `config/default.edn:81`).

For N owners whose replies each trigger the planner:

```text
planner runs = 1 initial outside-triggered run + N reply-triggered runs
```

At N = 100, the required count is 101. Only 99 replies can open after the
initial run; at least the final reply is deferred until a new outside message
resets the episode. Any extra planner run used to perform delegation lowers
the safe N further. Each reply is a distinct unanswered trigger, so observing
all fixes at the first reply's basis does not answer the remaining messages;
they still open their own runs.

The plan's D7 statement that cap activation would merely report a bug ignores
this deterministic arithmetic. Either the planner must coalesce/answer a batch
through a settled mechanism, the episode definition must change, or the
cap must be derived from a bounded fan-out contract. A standing 100 does not
support 100 delegates.

### S10. Evidence-derived “done” is not enforced at the terminal transition

The loop accepts `my.run/complete` from the evaluated value and closes the run
in the same receipt transaction (`src/seon/cluster/loop.cljc:116-187,755-876`).
It does not re-derive the attempt's problems at that transaction's current
database value.

Interleaving:

1. Planner pins basis B and renders what looks complete under the plan's
   exclusion rule.
2. While the model call is in flight, an owner reply or a new failing result
   commits at B+1.
3. The frozen planner form evaluates and `complete` closes at B+2 without
   testing B+1's facts.

The same-agent turn remains sequential, but external agents and test-result
writes are intentionally concurrent. Saying “a reader trusts the later query,
not planner prose” does not prevent the runtime from delivering and recording
the false completion. If completion is evidence-derived, the terminal
transaction must refuse stale completion against the current facts or
completion must cease to be a planner disposition.

## REVISION findings

### R1. The injected test failure bypasses the production P2 path

Act 2 says the harness commits a deliberately failing
`:seon.test.result`. That exercises the downstream query over a correctly
shaped fact, but it does not exercise the path a real failure takes:
`clojure.test/report` → `seon.test.runner/run!` → `record-tx` → named
non-default cluster (`src/seon/test/runner.clj:54-109,123-208`).

Use a real failing discovered `deftest` through
`bin/test --result-cluster ...`, then query its committed row. For the model
failure, inject at the provider-response boundary so the ordinary splitter,
freeze, evaluator, and receipt path see the throwing source. Directly
transacting either terminal row proves only the consumer.

### R2. The proposed properties can pass while work is lost

The termination property defines success as exclusion after delivery, so the
non-reply history in S6 passes it. The idempotency model lists sequential
operations but omits same-plan duplicates, concurrent terminal transactions,
and an owner that never settles. The attribution property conflicts with
namespace inheritance as shown in S2. The sibling test asserts that old
receipts are untouched but omits the open issue's critical invariant: a
failed prefix must not permit a false completion or reply.

Rewrite the suite from the required outcomes:

- every unresolved problem remains visible as outstanding;
- at most one live assignment exists per `(problem, owner)` under concurrent
  commits;
- owner completion/refusal/supersession changes that derived state;
- a failed form cannot yield a completed run or delivered reply based on
  missing definitions; and
- attribution equals the evaluator's actual namespace, not a second parser's
  syntactic guess.

## Required reseal before implementation

The minimum coherent reseal must answer these in order:

1. What committed fact triggers the first repair turn after the initial run
   closes?
2. Does one planner run actually execute several namespaces? If yes, change
   the evaluator contract first and derive `/ns` from that same owner.
3. What stable relation scopes one goal across later planner and owner runs?
4. What transaction owns at-most-once assignment, and what fact distinguishes
   delivered, outstanding, repaired, refused, and superseded work?
5. What prevents a stale planner disposition from closing against newer red
   facts?
6. What fold ruling closes the open false-completion issue?
7. What fan-out bound composes with the episode cap without human reset?

Until those contracts are explicit, the live drive would at best demonstrate
a hand-held scenario with two owners. It would not prove a terminating,
lossless generate-code loop.
