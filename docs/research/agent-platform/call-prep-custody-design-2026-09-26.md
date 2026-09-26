---
type: design
lane: inv-call-prep-custody-opus
status: "design for owner ruling: three priced options for call preparation (half A); half B dropped by owner ruling"
created: 2026-09-26
head: 853bae417 (default pid 13262, booted 15:48:58Z)
---
# Where a call gets its world: the entrant-world table and call preparation from custody

**What exists.** Each evaluation's world (database value, connection, agent id, turn) is produced
once at the evaluation boundary (`seon.sci.eval`, `src/seon/sci/eval.clj:3999-4150`). It is then
copied into six carriers: the turn-scoped `seon.env` record on the ctx's environment atom, the
ctx's own `::custody` map, `db/*conn*`, `db/*read-database*` and `db/*receipt*`, and
`effect/*request-context*`. Call preparation (supplied defaults) reads a seventh place: the
environment of whichever **SCI ctx the calling closure captured** (`sci/impl/analyzer.cljc:1796`,
`call_preparation.clj:1111-1171`). **Principal approach.** Custody is one value per evaluation, and
it travels where the evaluation travels: the kernel **arm**. The arm is already per-evaluation, is
already carried across threads as a value (`kernel.clj:333-360`, `current-arm`/`adopt-arm`
`:427-520`), and already holds the evaluation ctx (`::kernel/ctx`, observed live below). The callee's
contract wrapper supplies the defaults its contract declares from that value (S12). SCI's hook, the
per-cluster plan atom and the second preparation entrance are deleted.

## Owner rulings this design follows (2026-09-26)

- Custody is the default world a call uses. It is not an authority boundary (AGENTS §Laws,
  af2ee3ee8, 43277a00a). Half B of the assignment is **dropped**: no held marker, no refusal in
  `branch-connection` or `call-with-custody`, no admission marker for handlers, no delivery
  declarations. Cross-branch delivery (`message/send!`, `my.issue`, `fault`) stays as it is.
- Isolation is the **default** so that merging is intentional. Every agent gets a scratch space, and
  "the values each entrant receives … are declared once per entrant kind in the owning spec". The
  entrant-world table below is that declaration, as found live today.
- Astra H1 (`message.clj:723-746`) and E1 (`agent.clj:625,1070`, `my/program.clj:420`) are kept only
  as **correctness** questions. See "H1 and E1 as correctness" below.

## Evidence (live, default HEAD 853bae417, branch `inv-call-prep-custody-opus`, retired afterwards)

Defect A reproduced through the agent's own REPL (MCP SCI on the branch):

```clojure
(defn plan-now "…" {:malli/schema [:=> [:cat] :int]} [] (count (my.plan/current {})))
(clojure.test/deftest plan-now-answers (clojure.test/is (int? (plan-now))))
(my.test/run)
;; => error: "my.plan/current refused request at [:seon.db/db]: expected the required key
;;    :seon.db/db … got a map missing :seon.db/db"   (3,391 ms, flagged OVER ONE SECOND)
```

At the agent's own REPL, the same call and every shape of it are supplied:
`{:direct 0, :closure 0, :via-eval 0}` (plan count 0, no refusal). The agent's own definitions
survive as objects of its turn ctx, which carries the environment. The test member instead
re-acquires `plan-now` from rows into the member's program base. That base is memoized
**across branches**. It holds no connection by construction (`eval.clj:3139-3170`
`derive-base-ctx`, keyed by `base-ctx-key` `:3172-3188` = program identity, loaded commit,
fault recorder). The closure's call node reads the hook from that captured ctx
(`reference-code/sci/src/sci/impl/analyzer.cljc:1792-1804`). The hook finds no environment
connection and passes the arguments through unchanged (`call_preparation.clj:1144-1150`,
`(nil? database) arguments`).

**Model error.** The shared, memoized SCI ctx is a *program* value. It is used as the carrier of a
*per-evaluation* value (custody). The base is correct to hold no connection, so the fault is in the
carrier. Any function whose closure was made in a base (test members, another agent calling my
function after a reload, an acquired namespace's callers) calls with no world. Option (c) of the
earlier landing, which acquires agent rows into each execution's own fork, would keep the wrong
carrier and pay for it: 389 ms against 29 ms per member.

Other live observations:

- `(seon.sci.kernel/current-arm)` at the agent REPL answers an arm whose keys include
  `:seon.sci.kernel/ctx`, the evaluation ctx. `(seon.sci.kernel/adopt-arm nil f)` keeps the current
  arm: `(some? (current-arm))` inside it answered `true`, because a nil carried arm runs work
  unchanged.
- Agent SCI has no `future`: "Unable to resolve symbol: future". Agent code cannot make a thread,
  so every thread crossing is host code, which already carries the arm (`kernel.clj:427-440`).
  `System/nanoTime` is also unresolved at the agent REPL (a small separate gap).
- Supplied-default rows on default (JVM read-only): four rows. They are `:seon.agent/id` →
  `seon.env/supplied-agent-id`, `:seon.db/connection` → `seon.db/supplied-connection`,
  `:seon.db/db` (`:seon.db/database-value`) → `seon.db/supplied-database-value`, and
  `:my.program/context` → `my.program/supplied-context`. The snapshot has **878** prepared
  symbols, and deriving it cold took 2.9 ms.
- Cost of preparation today, measured at the agent REPL (warm, 200 calls):
  `(my.plan/current {})` took 52.2 ms, and the same call with `{:seon.db/db db :seon.agent/id id}`
  took 46.2 ms. So preparing one call with two supplied map entries costs about **30 µs**. The
  pass-through the hook adds to *every* SCI Var call was measured at about **8.5 µs** in the source
  (`call_preparation.clj:1133-1139`, default 2026-09-24). Two thousand `(seon.db/basis-t db)` took
  16.2 ms (8.1 µs per call, contract included); `clojure.string/upper-case` took 10.3 ms.
- An earlier identical loop of `(my.agent/identity)` took 6,866 ms for 2,000 calls, while
  `seon.config/apply!` ran concurrently for 21 s. The same 200 calls later took 17.5 ms. The
  first number is contention, not preparation. It is recorded here because the profile named
  `seon.db/call-with-custody` at 33 s inclusive.

## The entrant-world table (verified at HEAD; ✗ missing · ≠ produced two ways · ⧉ duplicated)

Columns: **DB** is the database value and when it is re-read. **Conn** is the connection, which is
where writes land by default. **SCI** is the context, base and fork. **Id** is the agent id or task.
**Store** is the store and registry. **Caps** is the capabilities and effects. **Defaults** is how
contract-declared defaults are supplied. **Reach** is how the entrant deliberately reaches another
branch, which is allowed. **Merge/discard** is how its work reaches the cluster and how its branch
goes away.

| Entrant | DB | Conn | SCI | Id | Store | Caps | Defaults | Reach | Merge/discard |
|---|---|---|---|---|---|---|---|---|---|
| **1 Root at its REPL** (MCP SCI, no branch; `script/seon/dev/mcp.clj:421-423` → `agent/evaluate-source!` `agent.clj:752` → run + wake → root's graph) | cluster head. **≠ re-read in 5 places** per turn: `turn.clj:4825` (per form), `:5012`, `:5030`, `:4796`, `eval.clj:4192` | the handle's cluster connection (`agent.clj:768`), taken from ctx `::custody` (`eval.clj:4108`). **≠** The request's `:seon.db/connection` (`turn.clj:4851`) is passed but ignored. Live: `:cluster-default` | memoized base (`base-ctx-key` `eval.clj:3172`, LRU 4 `:3050`); agent ctx regenerated **in place** each turn with private state kept (`:2940-3007`) | `"root"` hard-coded (`mcp.clj:422`). Live: `root`, ns `my.agents.root` | store from the base ctx's environment (`boot.clj:85`, `agent.clj:989`); **≠** not on the handle | `effect/*request-context*` (`eval.clj:4151-4180`). **⧉** It copies env + conn + id + turn + ctx | SCI hook reading the **closure's** ctx env (`analyzer.cljc:1796`). Own defs ✓ (live). **✗ row-loaded closures** (row 8). Host elided `seon.db` arities read `db/*conn*` instead (**≠**, a second supplier path) | `db/branch-connection`, `boot/connection` | root *is* the cluster; `accept-merge!` requires cluster custody (`my/program.clj:530`) |
| **2 Task/namespace agent in the cluster** (`armer-step` `agent.clj:1449`, arms only agents whose branch is its own `:1533`) | head per turn (`turn.clj:5012`), same as row 1 | its row's branch. `creation-tx` **defaults to the creator's branch** (`agent.clj:219-241`), so a task agent root creates writes **the cluster** (mode `:live`, `agent.clj:996`). **✗ not isolated by default** | the same base as root plus its own agent ctx (`agent.clj:1203,1206`) | env scoped with the id (`agent.clj:576`) | the same environment store | as row 1 | as row 1 | as row 1 | none needed: it wrote the cluster. **✗ no intentional merge** |
| **3 Outside agent via MCP branch** (`branch` tool → `host/host!` `host.clj:99` as `mcp/<branch>`) | its branch head, forked from the cluster head's commit (`registry/branch!`) | its branch, via `acquire-context!` `{:seon.agent/branch b}` (`host.clj:113`). Live: `:inv-call-prep-custody-opus` | `fork-cluster-ctx` of the source base (`agent.clj:1051-1058`); private defs kept across runs | `mcp/<branch>` (`mcp.clj:418`); rows on cluster and branch (`host.clj:44-59`) | store added to the handle (`mcp.clj:416`) | as row 1 | as row 1; same ✗ | as row 1 | `my.program/propose-merge!` is a message to root (`my/program.clj:459`) → root `prepare-merge!` on a fresh `merge-<id>` (`source.clj:861`) → `accept-merge!` retires `merge-<id>` (`my/program.clj:534`). **✗ The proposer's branch is not retired.** Discard: MCP `branch retire` → `host/retire!` + `retire-branch!` (`registry.clj:339`); GC `collect!` (`registry.clj:535`) |
| **4 Experimental scratch agent** ("write 50, merge 3") | **✗ no agent-REPL entrance.** The mechanism exists (`acquire-context!` `{:seon.agent/isolate? true}`, `agent.clj:980-1015`) but only test members call it (`test.clj:1980`) | a fresh `agent-<id>` branch | fork of the source base | ✗ none (`agent-id` nil) | the held store is required (`agent.clj:1006`) | none | as row 1 | — | as row 3; discard `release-context!` (`agent.clj:882-915`) |
| **5 Test member** (REPL deftest via `my.test/run`/`check`, `my/test.clj:25,39`; file test via `bin/test-check:118-123`; both → `seon.test/run`) | the **published base** (`world-base`, adoption record, `test.clj:1757-1766`) plus a checkpoint (`:1831-1836`) plus a program overlay (`:1855-1857`). **≠** Selection reads `source/current` instead (`:2251-2254`). **≠** The docstring says "off my current commit" (`my/test.clj:12,53`) | one branch **per member** (`test.clj:1977-1979`), bound by `call-with-custody` (`test.clj:245`, `runner.clj:393`) | `fork-cluster-ctx` **twice**, request then member (`test.clj:1879`, `agent.clj:1054`). **⧉** | **✗ stripped** (`eval.clj:3517`); only effect-reaching members get a synthetic `attempt-<id>` (`test.clj:192-209`) | request handle | effects through a synthetic agent only | hook over the member env: **✗ row-loaded functions get nothing** (reproduced live: `refused request at [:seon.db/db]`); `:seon.agent/id` is never suppliable (✗ above) | as row 1 | evidence recorded on the recording connection. Discard: `release-context!` (`test.clj:1997`); request release `:2482`; GC only when `:seon.config.maintenance/collect?` (`:2489-2495`) |
| **6 Entry gate / auto-check** (`eval.clj:4479-4675`, `test/accretion.clj`) | the turn's working value | **the agent's live connection** (`turn.clj:4795`); gate test bodies run under it (`eval.clj:4630-4637`). **✗ not isolated**: a gate body can write the agent's branch (read, not driven) | `fork-for-turn` of the turn ctx (`eval.clj:4479-4488`); in-memory install only | the real id (`turn.clj:3297`) | — | effectful candidates skipped (`accretion.clj:215-223`) | **≠ two producers**: `supplied-registry` snapshot + `supply` (`accretion.clj:163-183`) for generated arguments, and the hook again at invocation | — | n/a |
| **7 Render entry for an agent's context** (`render-call` `render.clj:1426` → `invoke-selected` `:1074` → `sci.kernel/invoke` `:1108`) | the turn's opening (`render.clj:1680`) | `db/*conn*` bound by `call-with-walk-context` (`render.clj:1750`). **⧉** Previews can transact (nothing prevents it) | **✗ the base ctx** (`turn.clj:4567` → `agent.clj:1203`), not the agent's ctx: private defs and walk handles are invisible (`repl.clj:463` works around this). **≠** Not the `evaluate` path the ruling requires ("render forms run through the SAME mechanism") | env scoped with `:seon.db/db` only (`render.clj:1094-1096`) | — | none (`*request-context*` unbound) | **≠** `kernel/invoke`'s second hook entrance (`kernel.clj:676-688`) over the base ctx: **✗** nothing is supplied | — | n/a. Page previews go through `evaluate` via `fork-for-turn` without an agent ctx (`turn.clj:4931`) |
| **8 A function loaded from program rows**, called by any of the above | whatever its caller's thread binds | host `seon.db` elided arities: `db/*conn*` ✓ | **the base the closure was created in** (memoized, no connection by construction) | ✗ | — | as its caller | **✗** The hook reads the captured base ctx and passes through (`call_preparation.clj:1144-1150`). **≠** It disagrees with the same function's elided `seon.db` calls, which do see custody | as caller | as caller |

## Rows that disagree with "isolated by default, same mechanism for everyone"

- **Rows 5, 7 and 8 do not use the same mechanism.** A call's defaults come from whichever ctx the
  closure captured, so the same function behaves differently in a test, a render and the agent's own
  REPL. **Fix: the design below.** It supplies defaults from the arm's custody, the one producer.
- **Row 2 is not isolated by default.** A task agent that root creates writes the cluster
  (`creation-tx` defaults to the creator's branch). This contradicts "every other agent's on its own
  branch by default" (AGENTS, 43277a00a). Fix: in `creation-tx`, default to a fresh branch for
  non-root agents, which routes such an agent through rows 3-4's hosting. That changes wake and
  hosting for task agents, so it is **owner question Q4**; about +10 lines.
- **Row 4 has no entrance.** "Write 50, merge 3" has no agent-REPL function, and the missing entrance
  is itself the defect. Fix: `my.agent/spawn` over `acquire-context!` `{:seon.agent/isolate? true}`
  plus `host!`, so that the scratch agent is row 3 without MCP. About +15 lines. **Q5.**
- **Row 5's world is produced two ways, and it has no agent id.** Fix (≈ +3 lines): the member
  environment keeps the executing agent's id instead of `fork-cluster-ctx` stripping it for members.
  Pick one base commit producer (**Q3**).
- **Row 6 is not isolated.** Gate test bodies run on the agent's live connection. Fix: run gate
  bodies as test members (row 5's world). That deletes the separate `run-candidate-test!` custody path
  (`eval.clj:4628-4637`). **Q6.**
- **Row 7 uses a second evaluator.** Render producers go through `kernel/invoke` on the base ctx. The
  owner ruled today that they run through `evaluate` in the agent's ctx. That is the render lane's
  cut. Under this design it inherits defaults for free, with no second entrance.
- **Row 3: after accept, the proposer's branch stays.** Retire it with the merge branch
  (`my/program.clj:534`, +1 line), or leave it to retention GC by an explicit ruling.
- **⧉ carriers** (rows 1-3, 7). The connection lives in ctx `::custody`, the env, `db/*conn*` and
  `effect/*request-context*`, and the head is re-read 5 times per turn. Collapsing these is
  **Option 3** below.

**Smallest change set so that every row comes from one producer.** The evaluation boundary produces
the world once, and the arm carries it:

1. The design below (Option 2): defaults are supplied from the arm's custody, fixing rows 5, 7 and 8.
2. The member environment keeps the executing agent's id (row 5).
3. Gate bodies run in member worlds (row 6).
4. Renders run through `evaluate` in the agent's ctx (row 7, the render lane).
5. Non-root agents default to their own branch, plus a spawn entrance (rows 2 and 4, Q4 and Q5).

Items 1-2 are this design. Items 3-5 are listed for their owners and need no new mechanism: each one
reuses an existing entrance (member worlds, `evaluate`, `acquire-context!`/`host!`).

## The design: one custody value, read by the contract wrapper

**Data flow.**

1. **Produce once.** The evaluation boundary already builds the turn environment
   (`eval.clj:4018-4035`: `env/scope` with db, agent id, turn id and ordinal) and arms the thread
   (`kernel/with-arm`). The environment the arm's ctx carries *is* the custody value, and it already
   holds `:seon.db/connection`, `:seon.db/db`, `:seon.agent/id`, `:seon.turn/id`, projection,
   cluster name and store. Nothing new is constructed.
2. **Carry.** The arm is the carrier. Host crossings take it with `current-arm` and install it with
   `adopt-arm`. That path already exists for deadlines and must stay exact for them, so custody gets
   no second carrier. A test member, a gate candidate or a render entry is armed with **its own**
   ctx, whose environment is its own world (the member's branch connection and the executing agent's
   id). The executing agent's id is the one member the member world lacks today.
3. **Consume at the callee.** Arming (`instrument/arm-var!` `instrument.clj:746`, and
   `wrap-interpreted` `:457`) already compiles one wrapper per contract. At arm time it computes the
   callee's **plan** from its contract arities and the four supplied-default rows (`plan-for`,
   `call_preparation.clj:699`). This is a pure function of the contract form and the row snapshot,
   memoized by their digests. A contract with an empty plan gets **no preparation step at all**. A
   non-empty plan composes one step *outside* Malli's wrapper:
   `(fn [& args] (apply malli-wrapped (prepare (custody) plan args)))`. Malli is untouched, and its
   `-instrument-f` (`reference-code/malli/src/malli/core.cljc:2202-2230`) still validates the
   prepared arguments. `(custody)` is `(env/of (::kernel/ctx (kernel/current-arm)))`. When there is
   no arm, it is the refusal `supply` already returns ("pass it explicitly"). Silence is never read
   as a world.
4. **Suppliers unchanged.** `supply` (`:862`) and the four supplier functions keep their contracts
   and still read the environment value, never a dynamic var (seon-env ruling 2 holds: the arm hands
   them the environment). Installation caches only the plan (the schema and dispatch). It never
   caches supplier results, as the S12 review requires (`astra-cut-review-2026-09-23.md:123`).

**Why the arm and not the ctx or a new dynamic var.** The ctx is shared program state, and that is
the model error. A new `*held*` var would be an eighth carrier. The arm is already the
one-per-evaluation value with a cross-thread protocol and a single owner (`seon.sci.kernel`).

**Deleted** (current line counts):

| What | Where | Lines |
|---|---|---|
| SCI fork call-preparation hook (revert 40fcaabd + 6ee57c9c) | `reference-code/sci` `core.cljc`, `impl/analyzer.cljc:1780-1804`, `impl/opts.cljc` 214-239/267/305/325/368, `test/sci/call_preparation_hook_test.clj` | src ≈ −76, test −127 |
| Hook installation, and the native-operation bypass of it | `eval.clj:303-312`, `:1724-1727` | ≈ −14 |
| Second preparation entrance (named invocation) | `kernel.clj:672-688` (`call-preparation/hook` in `invoke`) | ≈ −13 |
| Per-cluster plan atom and its install (an atom beside the program) | `call_preparation.clj:55-96` (`state?`, `state-generator`, `state`, `carrier`, `install`), `:809-828` `plan`, and the 2 `install` calls `eval.clj:3452,3512` | ≈ −65 |
| `hook`, `refused-call`, `callee-identity` | `call_preparation.clj:939-951`, `:1104-1171` | ≈ −80 |
| `:my.program/executing-ctx` scoping (the ctx is on the arm) | `call_preparation.clj:1162`, `eval.clj:3517`, `my/program.clj:417` | ≈ −6 |
| Auto-check's second supplier producer | `test/accretion.clj` `supplied-registry` reads the same `(custody)` | ≈ −10 |
| **Total** | | **src ≈ −264, fork tests −127** |

**Added:** plan-at-arm plus the prepare step in `instrument.clj` (≈ +35); `my.program/native-call-refusal`
moved from the hook to SCI's own `:namespaces` overrides of `clojure.core/{intern alter-var-root
remove-ns ns-unalias}` in `build-base-ctx` (≈ +15, reading the arm's ctx); the member world gets
the executing agent's id (≈ +3 in `test.clj`). **Net ≈ −210 src.**

**Per-call cost target.**
- A callee outside the 878 prepared symbols (one that declares no supplied member) pays
  **0 added**. Today every identity-bearing SCI Var call pays about 8.5 µs.
- A prepared callee pays one ThreadLocal read, a vector lookup by arity and the suppliers:
  **≤ 3 µs plus supplier cost**. `d/db` is a deref. Today it is about 30 µs.
- Arming pays one `plan-for` per contract with a non-empty plan, memoized by contract digest plus
  row-snapshot digest. The snapshot derivation measured 2.9 ms cold.

**Behaviour classes to keep** (`test/seon/call_preparation_test.clj`, 1,114 lines, 138 green at
verify-1910). Each moves from hook to wrapper, with the same assertions: full arity wins;
all-default shape; unique partial placement; ambiguous refusal; optional absence; an invalid
supplier value is the supplier's fault; the supplier throws whole. The one new class regression
asserts the wanted behaviour: **a function acquired from rows and called inside a test member
receives the member world's db and the executing agent's id**. It is the `plan-now` form above, run
by `my.test/run`.

## H1 and E1 as correctness (not security)

- **H1** (`message.clj:723-746`). Delivery to the recipient's home is intended and stays. The
  correctness question is whether a write lands where the sender did not intend. The target branch is
  read from the recipient's row **on the sender's own branch** (`:724-726`). A branch that edited or
  forked stale agent rows delivers to a stale home. That is a wrong-world bug, independent of
  security. Smallest fix: resolve the recipient's home on the cluster head, where agent rows are
  authored, not on the sender's fork. This is out of scope here (message.clj is not this lane's
  file). It is noted for its owner.
- **E1** (`agent.clj:625,1070`; `my/program.clj:420`). Reaching the parent is allowed. The
  correctness problem is that `:seon.agent/routing`, a live roster atom with a `:seon.agent/parent`
  edge, sits inside the **environment**, which is the custody value. Custody should be a value, and a
  mutable management roster inside it makes the default world depend on lifecycle state (release
  detaches, E2). Under this design the environment carries only world members. The roster stays with
  its lifecycle owner, and `supplied-context` reads it from there. This is a 1-line removal at
  `agent.clj:1070` plus `supplied-context` taking the roster from the execution handle the arm's ctx
  names.

## Three priced options for the owner (half A only; simplest first)

**Option 1: the hook reads the arm, not the closure (simplest).**
- Change: the hook's environment line becomes `(env/of (::kernel/ctx (kernel/current-arm)))`, with
  a fallback to the ctx when there is no arm, for a scratch ctx. The member environment keeps the
  executing agent's id.
- Size: ≈ +12/−4 in `call_preparation.clj` and `kernel.clj`, and +3 in `test.clj`.
- Cost: one slice, under an hour, plus the class regression.
- Guarantee: rows 5, 7 and 8 get the evaluation's world on every SCI Var call.
- Sacrifice: the SCI fork hook and its about 8.5 µs on every Var call stay. So do the per-cluster
  plan atom, `kernel/invoke`'s second entrance and the six copied carriers. The ctx remains
  half-carrier: the hook still falls back to it.

**Option 2 (RECOMMENDED; ruled S12): defaults applied by the callee's wrapper from the arm's custody.**
- Change: the design above. The plan is computed at arm time in `instrument.clj`. The prepare step
  reads `(custody)` from the arm. Native-mutation refusals move to SCI `:namespaces` overrides. The
  hook, the plan atom and the second entrance are deleted, and the SCI fork hook is reverted.
- Size: src **≈ +53/−264, net ≈ −210**; fork tests −127.
- Cost: 2-3 slices over hours, each loadable. (i) Plan at arm plus the prepare step, with the 138
  behaviour classes ported and the row-8 regression. (ii) Delete the hook, the state and the second
  entrance, and move the native refusals. (iii) Revert the SCI fork and move the pin. The slices touch
  held files (`instrument.clj`, `sci/eval.clj`, `sci/kernel.clj`, the SCI fork), so root sequences
  them.
- Guarantee: one producer of supplied defaults. Zero cost for callees that declare no default, and
  ≤ 3 µs plus supplier cost for those that do (today about 30 µs). A call with no evaluation (no arm)
  refuses by name instead of passing through.
- Sacrifice: a change to a supplied-default row takes effect at the next arming, because rows are
  program rows. That is the ordinary reload path.

**Option 3: Option 2 plus one carrier for all custody (largest).**
- Change: everything reads the arm's environment. Delete ctx `::custody` (9 src sites),
  `db/*conn*`/`*read-database*`/`*receipt*` (39 src uses; the elided `seon.db` arities read the arm)
  and `effect/*request-context*` (25 src uses; handlers read the arm). `call-with-custody` becomes
  adopting an arm with a derived environment. The head is read once per form.
- Cost: about a day, across `db.clj`, `effect.clj`, `sci/eval.clj`, `kernel.clj`, `render.clj` and
  the runner, with Astra review. A further net of about −150 is estimated, not measured.
- Guarantee: every ⧉ cell in the table disappears, and a world has exactly one place.
- Sacrifice: a large cross-owner cut. Host code at a JVM REPL (no arm) must pass connections
  explicitly, which the law already requires.

**Recommendation: Option 2 now, Option 3 as the next deletion lane.** Option 1 fixes the symptom but
keeps the wrong carrier as a fallback and the per-call tax on every call.

## Owner questions

1. **Q1.** Call preparation: Option 1, 2 (recommended, S12) or 3?
2. **Q2.** Is the carrier of an evaluation's custody the **kernel arm**? The arm is one value per
   evaluation, is carried across threads, and holds the evaluation ctx. Suppliers still receive the
   environment value, so seon-env ruling 2 holds in substance. Yes or no?
3. **Q3.** Should a test member's world carry the **executing agent's id**? And should it branch from
   the **agent's current commit** (as `my.test` documents) or from the **published base, checkpoint
   and overlay** (as the code does)? Recommended: carry the id; branch from the agent's current
   commit, which is what "run tests as agents run" says.
4. **Q4.** A task or namespace agent that root creates defaults to the **cluster branch**
   (`creation-tx`). Should non-root agents default to their own branch, per "isolated by default"?
5. **Q5.** Add an agent-REPL entrance for a scratch agent (`my.agent/spawn` → isolated branch + host)?
6. **Q6.** Should gate test bodies run in member worlds instead of the agent's live connection?

## Verification limits

- Read-only JVM probes and one branch agent only. No source edits, no test requests. `my.test/run` of
  one REPL deftest on my own branch is the agent's ordinary entrance.
- Per-call costs come from `time` in SCI (no `System/nanoTime` in agent SCI). Warm, one sample each.
  The 8.5 µs pass-through figure is the source's own 2026-09-24 measurement, not re-measured.
- The table's cells were read from source at HEAD (file:line) and checked live for root and the MCP
  branch agent. The member, gate and render rows were read, not driven.
