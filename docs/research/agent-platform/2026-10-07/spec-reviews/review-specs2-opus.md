# Second design review: errors.md and agent-loop.md (reviewer: opus, 2026-10-07)

Read-only. HEAD `33fb994f2`; turn.clj, refusal.clj, fault.clj, task.clj and message.clj are unmodified in the working tree,
so the line numbers below are HEAD's. No JVM, MCP, tests or commits. "[U]" marks a claim I did not verify.

Verdict: the revision fixes most first-round HIGH findings. The direction is right, and the constructor, the report
adapter, `work`, the bounds and the settlement outcomes now hold up. Four new problems need fixing before any slice
lands:
- errors P1 is visible and ordered before the schemas it needs;
- a fault opening cannot message a branch agent in a task-home transaction;
- the "new" cause-chain walk rebuilds `refusal/chain`, which already exists;
- the loop context reads the task from the branch database, but tasks live at the task home.

## 1. First-round HIGH findings

| Finding | Status | Evidence |
|---|---|---|
| Astra E1 / Sol E1: S2 removed at/layer before consumers | **Resolved** | at/layer stay until the cut (errors.md:41-44). Producers are typed per failure site (:140-145). There is one cut (:312-317). The reset is ruled (:376). New defect: P1's order and visibility, see E-H1. |
| Astra E2 / Sol E2: thrown variants are never validated | **Resolved** | The one constructor validates against the type's schema for both carriers (errors.md:95-119). T8 and T12 cover it. This is in fact today's `refusal/error` behavior (refusal.clj:743-791: cached explainer, packaged-projection fallback, typed result). |
| Astra E3 / Sol E4: report adapter | **Resolved** | Five-kind `case` (errors.md:187-204). Caller/callee blame uses the digest captured at the report (:207-210). schema-at-call keeps its cause (:211-213). No `pr-str` (:216). The kinds and data match pinned malli `core.cljc:2219-2240` (Seon fork, `43c6dca8`). The sketch does not show the reporter-failure suppression (:214-215 states it), so the slice must test it. |
| Astra E4 / Sol E3: Throwable->map is not total | **Partially** | The diagnosis is right (:67-76) and `frame-function` now gets a class symbol (:156). But the fix rebuilds a walk that already exists and loses a relation. See E-H3. |
| Astra E5 / Sol E5: fault row semantics | **Partially** | Fixed: 64-character signature, receipts on repeats, task link through the canonical writer, and a resolution rule with no digest-only resolution (:248-273). Not fixed: Sol E5's "root/branch delivery cannot assume the cluster copy" (see E-H2), and the gap between the errors cut and the loop-cut reset (E-M2). |
| Sol E6: carriers need control-flow conversion | **Resolved** | Table rows 2-3 (errors.md:136-137) and the one top catch (:125-131). |
| Astra L1 / Sol L1-L2: seams to evaluator and writer | **Resolved, with one contradiction** | The pass id is minted first, the context is acquired, the reply is built from the SETTLED batch, and `settle-pass!` generalizes the writer in place (agent-loop.md:92-106, 124-146). But the evaluation-id arity contradicts "ids unchanged" (L-M2). |
| Astra L2 / Sol L5: terminal reply and termination | **Resolved, with one gap** | try/catch/finally (:91-111), and disarm joins under the caller's deadline (:173-179). Gap: two `disarm!` callers pass no deadline (L-M5). |
| Astra L3 / Sol L3: effects and unknown outcome | **Resolved** | :148-163 and T8, T11. |
| Astra L4 / Sol L4: fault window duty | **Resolved** (depends on the errors cut) | `fault-signatures` on the evaluation and the call (:39-44, :135, errors.md:264-268). Reply maps carry receipts (errors.md:315-316). |
| Astra L5 / Sol L6 / Sol L10: no-provider, opening vs anchor, AVET order | **Resolved** | :49-74 and T1, T9. Checked against turn.clj:2625-2661 and wake.clj:156-174. |
| Astra L6 / Sol L9: activation order, retained closure | **Resolved in shape** | Prep slices plus one cut (:285-326). The closure is kept, including `open-for-agent` (turn.clj:2232-2244) and the `append-generated-*` helpers. Caveats in L-M6 and §3 below. |
| Sol L7: cache inputs | **Resolved** | The inbox key carries `answered-t`. The history-unit projection is immutable, and the turn query goes (:205-215). |

**Wrongly rejected?** None outright. All ten rejections and modifications in notes.md:101-129 check out against code. For
example, rejection 1 is right: `agent.clj:849-870` resolves `seon.eval.drive/run-receipts`. One supersession is
incomplete. Ruling 6 resets fault data at the **loop** cut, but the errors cut lands earlier, so Astra E1's "existing
fault identities" concern is open for that interval (E-M2).

## 2. New problems, by spec and severity

### errors.md

**E-H1 (HIGH): P1 "Type producers" is not invisible, and it needs P2 first.**
- Since S1, render selection takes `(:seon.error/type value)` first (render.clj:569). Typing a site that was untyped
  changes which render pair is chosen. And if `pairs` returns `[]` for a type with no declared pair, the `or` stops
  there.
- The recorder reads the thrown type as `declared-schema` (fault.clj:423-430). `prepare` validates the observation
  against it (error.clj:672, 694), and the signature hashes it (error.clj:171-176). So:
  - an unregistered variant type makes recording refuse;
  - a registered one changes the signature, which means a new fault row, task and message for an existing failure.

P1 names variants "in its owner's EDN", but P2 (variant wrappers) comes after P1. Fix: order P2 before P1. Either accept
P1 as visible (ask the owner), or have P1 type only sites whose variant schema and render pair already exist.

**E-H2 (HIGH): an opening's message cannot go "in the same writer transaction" to a branch agent.**
- Messages are written on the recipient's home branch (`message/send!`, message.clj:837-866).
- Tasks and fault rows are written at the task home (task.clj:296-326; errors.md:243-261).
- Every non-root agent works on its own branch, so the failing world's agent is usually not homed at the task home. One
  transaction cannot write two branches.

Fix: the home transaction writes the row, the task and the receipt `opened? true`. The boundary then sends the message
with `message/send!` to the failing agent's home (a second write, durable before the reply), or to root when the agent
is absent. State this in §6 and in decision 5. Q-E1 option A is still right for row and task.

**E-H3 (HIGH): the cause chain rebuilds `seon.error.refusal/chain`.** `walk-links`/`chain`/`recorded-chain`
(refusal.clj:441-498) already walk cause AND suppressed edges cycle-safely. They mark relations with
`:seon.error/suppressed-by` and `:seon.error/cause-of`, and the installed `:seon.error/link` already declares both
(seon.error.edn:271-281).

The spec's new `links` with Throwable->map's `:via` shape (`:type`/`:at`) differs in three ways:
- it drops `:cause-of`, the relation T9's "suppression with its own cause" needs;
- it drops `:seon.error/frames` for a single `:at`;
- it puts a link-schema migration of every chain and receipt reader into the cut, with no behavior gain.

Fix: keep `chain` and the link schema. The only change is that the display bound moves to the printer: use
`recorded-chain`, which keeps data whole, and delete `bounded-data`/`bounded-cost`/`within-evidence-bound*` and
`:seon.error/bounded-chain`. Remove "chain schema and readers" from the cut.

**E-M1 (MEDIUM): the cut is too large for one lane.** It holds:
- the constructor signature, with about 257 `error`/`diagnostic` call sites (my count) plus 120 literals;
- the base change and every `(:seon.error/at x)` predicate (441 reading lines, per lane notes);
- `caught` at about 10 entrants, the report adapter, attribution, recorder, signature, resolution, reply and render.

The constructor half can be prep work. Today's `error` is arity 2 `[alternative members]` (refusal.clj:774). Add
`[type operation message]` and `[type operation message data]` arities to the SAME defn: this is not a second
constructor. They produce at/layer until the cut, so families convert one at a time in invisible slices. The cut then
needs only: base requires type/message and drops at/layer, the predicates, the recorder/signature, and `caught` at the
entrants. The report adapter changes what agents see, so it can only go in the cut or be an owner-approved visible
slice. Ask the owner.

**E-M2 (MEDIUM): there is an interval between the errors cut and the loop-cut reset.**
- `:seon.error/signature` declares row-schema `:seon.error/error` (seon.error.edn:268), whose entity requires
  at/layer/declared-schema (:114-133, :300-324). New rows will be refused unless the cut rewrites that schema, and the
  spec does not say it does.
- Old fault tasks (`:seon.task/faults`, task.clj:606-626) will be resolved by the new rule over rows that have no
  `latest-digest`.

Fix: either reset fault data at the errors cut, or state the schema rewrite and how old rows resolve.

**E-M3 (MEDIUM): the guide contradicts the spec.**
- reference-code.md:34 says "The boundary reads `(:via (Throwable->map t))` once".
- SKILL.md:314-323 and :659 call `:via` "EQUIVALENT" to an `ex-cause` loop. It is not: it has no seen-set, so it never
  ends on a cause cycle (core_print.clj:484-501), and it ignores suppressed throwables.

Lanes copy the guide. Fix both rows to name `refusal/chain`.

**E-M4 (MEDIUM): the new signature merges distinct failures.** `[type operation producer first-problem-:in frame-site]`
drops what today's signature includes (error.clj:171-180): the throwable class and expected-key/expected-shape. Two
consequences:
- every host exception (`:seon.error/exception`) at one frame of one producer gets one row;
- two contract refusals with the same first `:in` but different failure types also get one row.

Fix: add the root link's class for `:seon.error/exception`, and the sorted set of `[:in :type]` over problems.

**E-L1 (LOW): `fault/record!` is the stated recorder, but it sits inside the deleted span.** §2 row 4 and §4 call it
the recorder, while `record!` (fault.clj:138) lies inside "Goes … :26-315". Fix: say it is rewritten in place.

**E-L2 (LOW): the `caught` sketch doubles up.** `own` comes from a link's `:data`, so the map ends up nested inside its
own `:chain`. `references*` is also re-applied to data the walk has already referenced.

**E-L3 (LOW): the fault writer cannot use `task-call` as named.** `task-call` is private and reads a connection's
database (task.clj:335-343). Inside the `:db.fn/call`, call `adopt-tasks-tx` on the in-transaction database.

**E-L4 (LOW): stale citations and a dangling reference.**
- turn.clj citations in §2 row 4 are 5 lines high: the sites are 5102, 5427, 5714 and 5863.
- refusal.clj has 828 lines and seon.error.edn has 459.
- "File ownership with the loop: §9.7" dangles, because §9.7 is about order.
- errors P2 and loop P0 both accrete `:seon.cluster.eval/fault-signatures` and `:seon.ai.attempt/fault-signatures`;
  assign them to loop P0.

### agent-loop.md

**L-H1 (HIGH): the context reads the task from the wrong database for every branch agent.**
- Tasks, assignments and steps live at the task home. `agent-task` says "(the task home's)" (task.clj:496-507), and
  `my.task` reads through `task/home-connection` (my/task.clj:100-104).
- The pass's `db` is the agent's branch connection (agent-loop.md:84-85).

So `(derived db [::task agent-id] …)` sees no assignment made after the fork. `health/derived` watches the wrong
revisions. `complete!` and the completion row/notify also live at the home. Fix: `context` takes the home database too
(`(db/db (task/home-connection conn))`): the task unit is derived and keyed there, while the inbox and history stay on
the branch. T7 should run on a branch agent.

**L-M1 (MEDIUM): the spec does not say which agent row carries the new facts.** An agent row exists on the cluster AND
on its branch (`home-call` writes both, host.clj:96-104). Fix: name the branch row (same transaction as its
evaluations), and say how status and root read it.

**L-M2 (MEDIUM): the evaluation-id arity contradicts "ids unchanged".**
- §1 and errors §1 use the 3-arity `(id/evaluation branch pass-id position)`.
- Every current minter uses the 2-arity `(id/evaluation run-id ordinal)`: turn.clj:552, turn/batch.clj:876,
  refusal.clj:653, effect.clj:225, sci/eval.clj:4021.
- §2.1 says "pass id = run id, so ids and handles are unchanged".

These are incompatible, and P2 would change every id and handle, which agents see. Fix: keep the 2-arity. A fresh
`(id/id)` is already unique, and run ids already digest the branch (turn.clj:530-543). Today's ordinal is the per-run
position (turn.clj:4907), so "keeps its meaning" is correct.

**L-M3 (MEDIUM): the pass sketch contradicts its own bullets and the tests.**
- A throwing `context` or provider call goes to `catch`. That writes no call row, so it is not counted, contrary to
  §2 bullet 2 and T15.
- The catch never records the fault, so the reply's error has no receipt, contrary to errors §6 "every occurrence
  attached".

Fix: `provider-call` takes the thunk and turns any throw into the call row's error (`caught` plus record). The outer
catch records before answering.

**L-M4 (MEDIUM): JVM mode has no evaluator.** The cut routes `jvm-run` through `pass` (agent-loop.md:316-319), but the
sketch always evaluates SCI through `evaluate-sources` with an SCI context. Under the one-path rule the request
supplies its evaluate stage as a value. Fix: the request carries `:seon.batch/evaluate` (or the equivalent stage key),
with SCI as the default.

**L-M5 (MEDIUM): two joins lose their bound.** `stop-graph!` falls back to the backstop dial when no deadline is passed
(agent.clj:1550-1556). `release` (agent.clj:948) and re-arm (:1296) call `disarm!` with no deadline, so after the dial
is deleted their join has no bound. Fix: name the deadline each caller supplies, or the declared bound that applies.

**L-M6 (MEDIUM): the prep slices may not be invisible.**
- P1 writes `last-ordinal` and `current-ns` on the agent row at every settlement, and new attributes on evaluations.
  Every existing evaluation attribute declares `:seon.wake/context-inert true` (seon.cluster.eval.edn:40-48;
  wake.clj:144-152). The new ones must too, or the old loop's generated reads may re-derive them [U].
- P1 replaces `turn.batch/settle`'s run-writing `record-facts` (turn/batch.clj:683-725) for JVM/test runs. It must
  keep writing the run row that the MCP observation reads (agent.clj:849-870) until the cut.

**L-M7 (MEDIUM): "completion always has a task and tests" is false.** `own-task` creates a `:work` task with no
proofs (my/task.clj:106-124), so `complete!` refuses `:no-proofs`. Fix: either the agent must write a deftest naming
its `:work` task, or a no-proof agent finishes by `my.turn/wait` or the cap. This is an owner choice.

**L-M8 (MEDIUM): T13 ("the whole prompt fits the budget") is not guaranteed.** `prompt/select` keeps the newest unit
even over budget (prompt.clj:330-333). Its budget argument is the dial schema `:seon.config.ai/prompt-token-budget`,
so a small remainder may fail its contract. Fix: pass the remainder with a `[:int {:min 0}]` contract. When mandatory
units are priced separately, drop the keep-newest rule or count that unit as an elision.

**L-L1 (LOW): a misattributed cost.** "0.163 ms" is the docstring's figure for the *replaced* trigger-anchored form
(turn.clj:2640-2643). The real cost is an index walk over all of the agent's lifetime wake datoms per pass (no early
exit, by design). State that.

**L-L2 (LOW): small errors in the sketches.**
- `(prompt/model-calibration db agent-id)` has the wrong arity; the function takes `[db model fallback agent-id]`
  (prompt.clj:124-140).
- The SCI dial is `:seon.config.eval/time-limit-ms` (turn.clj:4903). `:seon.sci.eval/time-limit-ms` is the world
  member.

**L-L3 (LOW): ordering.**
- P2 edits `refusal/world-reference`, which is errors-owned and rewritten in the errors cut.
- P4 uses `error/caught` and the 4-arity `error`.

Both must land after the errors cut. Say so in §6 and in decision 9.

**L-L4 (LOW): turn.clj citations are 5 lines high.** For example, step is 5802 and provider-targets is 4059. The file
has 6050 lines, not 6055.

**Fine as written:** the `work` predicate and wake maxima (matches `outside-wake-t`); the three dials; settlement
outcomes (committed / refused / unknown, with refused-retry on a fresh head); effects; the cache keys; the
reconstruction limits; the §5 kept closure; and the named kept tests, all of which exist.

## 3. Invisibility, bootability, dependency order

- **errors S1b:** invisible; it repairs the S1 type after the conversion (error.clj:686-696). Fine.
- **errors P1:** visible and misordered (E-H1).
- **errors P2:** invisible if it adds schemas only. Bootable.
- **errors P3:** invisible (unwired). But the new `links` should not exist (E-H3).
- **errors cut:**
  - dependency-complete except for the `:seon.error/error` row-schema rewrite (E-M2);
  - it needs loop P1, as stated;
  - its "caught at the loop pass" entrant does not exist until loop P4/cut. Drop it from the errors cut list and give it
    to the loop cut.
- **loop P0:** invisible if the new attributes are context-inert (L-M6).
- **loop P1:** invisible only with the run row kept for JVM/test and with the 2-arity ids (L-M2).
- **loop P2:** as written it changes ids, so it is visible.
- **loop P3:** changes calibration only for agents with attempts. On default, the calibration is prior-only today
  (map.md) [U].
- **loop P4 and P5:** additive.
- **Order:** loop P0 → P1 → errors S1b, P2, P1, P3 → errors cut → loop P2 → P3 → P4 → P5 → loop cut. The spec has the
  errors-before-loop rule right. It omits that loop P2 and P4 follow the errors cut.

## 4. Lane size and proofs

- The errors cut is several lanes' work. Split its constructor half into prep slices (E-M1) and drop the chain migration
  (E-H3). That leaves one cut a single lane can prove with T1-T12 and adoption on a live-store branch.
- The loop cut is also oversized; it touches about 25 reader files. P1 already writes `agent`, `agent-ordinal`, `pass`
  and `origin` on the old loop's evaluations, so add **P6 "readers switch"**. It moves transcript (group by `pass`),
  `eval/of-agent`, status and the MCP observation onto the new attributes while the old loop runs. It is invisible
  (same data) and provable by the existing transcript and MCP tests.
- Also add **P7 "dial rename"**: `max-episode-runs` → `max-calls-per-wake`, with its defaults, stored facts and
  consumers. It is invisible.
- The loop cut is then: transform, armer, permit/backstop, entrant submission, bootstrap opening, render attachment and
  disposition.
- Proofs per slice are named and the tests exist. Add T7 on a branch agent (L-H1). Add a P1 test that the JVM/MCP run
  observation is unchanged.

## 5. Concrete edits

1. errors §1 "Cause chain": replace the paragraph with "Keep `refusal/chain`'s cycle-safe cause+suppressed walk and the
   installed `:seon.error/link` (`:suppressed-by`, `:cause-of`, `:frames`). Only the bound moves: the recorder and
   `caught` use `recorded-chain` (data whole); `bounded-*` and `:seon.error/bounded-chain` go."
2. errors §3 `caught` sketch: replace `(links t)` / `(update % :data references*)` with `(refusal/recorded-chain t
   projection)`. Drop `own`'s link `:data` from the chain.
3. errors §6 Opening: replace "and adds one message to the agent of the failing world, else root, its content the error
   map" with "and returns `opened? true`. The boundary then sends one message with `message/send!` on the failing
   agent's home branch (root when absent), durable before the reply."
4. errors §6 signature: replace `[type operation producer (-> problems first :in) (frame-site frame)]` with `[type
   operation producer (into (sorted-set) (map (juxt :in :type)) problems) (:seon.error/throwable-class (peek chain))
   (frame-site frame)]`.
5. errors §7: replace "P1 Type producers … P2 Accrete schemas" with "P1 Accrete schemas (variants + render pairs);
   P2 new arities `[type op msg]`/`[type op msg data]` on the existing `error` (at/layer still produced); P3 type
   producers by family through the new arities (visible render/signature changes listed per family, owner-approved);
   P4 unwired pure functions." Remove "chain schema and readers" from the Cut. Add "rewrite `:seon.error/error`
   (signature row-schema); old rows reset or resolved as …" to the Cut.
6. errors §2 table row 4 and §4: replace `fault/record!` with "`fault/record!` (rewritten in place, fault.clj:138)",
   and change "Goes … `fault.clj` … :26-315" to ":26-137".
7. reference-code.md:34 and SKILL.md:314-323, :659: replace "`(:via (Throwable->map t))` once" with
   "`seon.error.refusal/chain` once (Throwable->map has no seen-set and drops suppressed)".
8. loop §1 Evaluation identity: replace `(id/evaluation branch pass-id position)` with `(id/evaluation pass-id
   position)` (the 2-arity every minter uses today). Also edit errors §1:46.
9. loop §1 table: add "Row: the agent's row on its home branch (the settlement's database); `home-call` keeps the
   cluster row for routing" and "all four declared `:seon.wake/context-inert true`".
10. loop §2 sketch: replace `ctx (when work (context db agent-id))` and `call (when work (provider-call …))` with
    `call (when work (provider-call db home-db cluster agent-id))`, where `provider-call` renders the context and turns
    any throw into the call row's recorded error. In `catch`, record before `offer!`.
11. loop §2 request: add "`:seon.agent/evaluate` stage value: SCI `turn/evaluate-sources` by default, the JVM stage for
    `jvm-run`."
12. loop §2.2: after "caller's lifecycle deadline", add "`release` and re-arm (agent.clj:948, 1296) pass the deadline
    they hold; …".
13. loop §3 sketch: replace `(derived db [::task agent-id] …)` with `(derived home-db [::task agent-id] …)`, where
    `home-db = (db/db (task/home-connection conn))`. Replace `(prompt/model-calibration db agent-id)` with
    `(prompt/model-calibration db model prior agent-id)`. Replace "history gets the remainder through `prompt/select`"
    with "… `prompt/select` with a `[:int {:min 0}]` remainder and no keep-newest exception".
14. loop §4 last bullet: replace "so completion always has a task and tests" with "; it completes only after writing a
    deftest naming that task, else it ends by `my.turn/wait` or the cap" (owner to confirm).
15. loop §6: add "P2 and P4 land after the errors cut". Add P6 (readers switch) and P7 (dial rename). Change P1 to "JVM
    and test entrants keep writing their run row until the cut". Change the 0.163 ms cost claim to "O(lifetime wake
    datoms) per pass".

## Counts

- errors.md: **3 high, 4 medium, 4 low**.
- agent-loop.md: **1 high, 8 medium, 4 low**.

## Files read

- Specs and notes, end to end:
  - docs/seon/specs/errors.md and agent-loop.md
  - tmp/specs-revision/notes.md
  - both first-round reviews
  - tmp/errors/s1-s2-notes.md and tmp/agent-loop/s1-notes.md
  - the owner rulings memory
  - docs/seon/architecture/reference-code.md
- Skill: .agents/skills/data-oriented-clojure/SKILL.md (error sections).
- Not read: tmp/agent-loop/map.md (only the citations to it).
- Source excerpts:
  - src/seon/error/refusal.clj (80-206, 336-498, 595-800)
  - src/seon/error.clj (150-182, 660-700, 1262-1295; fn index)
  - src/seon/fault.clj (fn index, 420-440)
  - src/seon/render.clj (560-575)
  - src/seon/id.clj (95-140)
  - src/seon/turn.clj (520-640, 2025-2045, 2325-2345, 2625-2662, 4865-4940; fn index)
  - src/seon/turn/batch.clj (785-792; settle sites)
  - src/seon/task.clj (165-345, 378-410, 496-522, 600-660)
  - src/my/task.clj (100-175) and src/my/program.clj (111-120, 416-475)
  - src/seon/cluster/message.clj (1-60, 780-866)
  - src/seon/cluster/host.clj (20-120)
  - src/seon/cluster/agent.clj (140-175, 849-870, 940-952, 1290-1298, 1492-1580)
  - src/seon/cluster.clj (3550-3560)
  - src/seon/cluster/wake.clj (120-182)
  - src/seon/cluster/prompt.clj (107-140, 302-334)
  - src/seon/db.clj (490-502, 2784-2846)
  - src/seon/test.clj (1547-1575)
  - src/seon/instrument.clj (fn index)
- Schemas: resources/seon/schemas/seon.error.edn (80-86, 262-285) and seon.cluster.eval.edn (40-70).
- Tests: test name greps across test/seon, plus test/seon/error/declared_identity_test.clj and turn_test lines for S1b.
- Libraries: reference-code/malli/src/malli/core.cljc (196-210, 2205-2245) and error.cljc (326-395) at `43c6dca8`;
  reference-code/datahike/src/datahike/db/transaction.cljc (1160-1185) at `d9121bbb`.
