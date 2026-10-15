---
title: "reactive-start plan: desired state validated at the write, steps derived each pass"
type: research
status: "corrected after the narrow check f684c6181 (C1-C13); P1 and P2 done, P3 next"
---

# Reactive start: desired state validated at the write, steps derived each pass

Lane flow-pass (Opus), 2026-10-15. It follows the owner's rulings of 2026-10-15 night: "update the data and it
properly goes to the right state including setting up the env first", and "nothing executes until the data is
validated by Malli ... including config entries". Root's decisions: the config home is A (`seon.task/chain!` writes
the branch's config facts in the same transaction as its tasks), routes are option (a), forward-merge conflicts never
hold a launch (the agent resolves them in its context), and the process is one plan, a narrow check, then the build.
The narrow check of revision 49fdd1bb5 (`reactive-start-check.md`, f684c6181) is folded in below, each correction in
its owning section.

**Phase status:** plan corrected (C1-C13 folded in); P1 done (88474708b); P2 done (chain routes, counted acquisition); P3 next. P4-P6 and D2 open.

**Today:** `seon.cluster.agent/run-pass!` is already a level-triggered derivation: `launch-facts`, then
`launch-actions`, then `apply-actions!`. What fails is around it. Wakes are edge-triggered (a chain branch has a
route only while a graph runs there), the environment (config) is applied after the fact, and nothing validates that
a task can run.

## 0. What happened to attempt 4 of chain-10d1fd5956a9 (measured, read-only)

- **The write.** Root's `my.root/run!` committed `:held` → `:running` on `:chain-10d1fd5956a9` at **09:52:32.027Z**
  (the chain branch's `:seon.task/execution` history). That JVM booted at 09:52:09 and was ready at 09:52:22.
- **No wake.** `seon.task/run-held!` writes through a fresh `seon.cluster.registry/on-branch` connection. Only
  `seon.cluster.agent/start-branch-flow!` registers a wake route on a branch, when an agent graph starts there. After
  a restart no graph held the chain branch, so the commit had no listener and woke no one.
- **False claims.** The docstrings of `run-held!` and `my.root/run!` say the commit "wakes my plan". TASK-CHAIN §5
  says run! "offers its own wake". `git log -L` on `my.root/run!` shows no wake offer was ever written.
- **Why attempts 1-3 launched.** Attempt 1 came from the chain fact's installation on main (a `:seon.wake/starts`
  datom on a listened connection). Attempts 2 and 3 followed run! calls made while the previous attempt's graph still
  held the branch's connection with its route, so the commit was heard.
- **The state now (check, pid 54791, 10:07Z boot).** Root's restart booted a pass that wrote the attempt row
  (`e88599e46009`, attempt 4; the task records attempts-made **4**) and acquired its context on the chain branch, but
  no graph started: `launch-actions` plans exactly one `:start-flow` for `attempt/e88599e46009`, and two probes 40 s
  apart still showed none. The missing step is the **graph start**, not config apply and not the attempt row. The JVM
  reads `:faulted` (25 open faults). This is the live instance of P5's mid-launch proof.
- **The branch's provider dial toggles.** Main's row has `:seon.config.ai/no-provider true`. The branch's row
  history holds 14 add/retract pairs of `true` across boot reconciles and applies; at the check's probe its last
  transaction was a retract (provider used), and root's later write switched the provider off again for the live
  check. Something re-decides it on every reconcile: P4's convergence step owns that dial's value from then on.
- **The "query canceled" is a different event** (D2): process 46001's boot pass at 09:22:31.7Z, no run! then.

## 1. The model

The owner's rulings, in order:
- "update the data and it properly goes to the right state including setting up the env first";
- "nothing executes until the data is validated by Malli";
- "make sure it handles transition states too ... have the desired state be spelled out and the minimum changes are
  applied".

**Desired state spells out the whole target, as data.** It is one value per chain task, validated by
`:seon.task/runnable` (§2) at the write, so invalid desired state never exists. It names:
- the branch and what it must be based on: `:seon.task.desired/base`, either `[:main-at-least C]` (main's commit C is
  an ancestor of the branch's merge base) or `:main-head`. `:main-head` is resolved once, to `[:main-at-least C]`
  with C main's head at that pass, by the pass that writes the attempt row (C8): a running chain is never "behind"
  because main moved;
- the branch's config entries (the decided dials of §2);
- the task's execution (`:running`, `:held`, `:stopped`);
- for a merge, its target (`:seon.task.desired/merge-into`, main).

Dependencies are named in the data or derived from it (the base names main; the attempt names the branch), never
implied by call order.

**The planner is pure and stays where it is (C10):** `seon.cluster.agent/launch-actions`, `(facts, registry-facts,
max-running) → actions`, data in and data out, extended in place. No new namespace: `seon.cluster.reconcile` would
collide with `seon.reconcile` (the config and entity reconcile).
- `facts` (`launch-facts`) is read once per pass and keyed by commit ids, as `chain-facts` is. It gains (C7) each
  chain branch's merge base with main (the commit graph, cached by commit pair as H0), its config difference (keyed by
  the branch head and the desired digest), and the chain advance and bail decisions that `run-pass!` today applies
  as writes before it reads facts (`advance-chains!`, `bail-chains!` become `:advance` and `:bail` actions).
- `registry-facts` gains the chain routes held and the in-memory transitions (§1 table).
- An action is `{:seon.flow.actions/op ... :subject ... :basis ...}`. Each applier consumes the action's planned
  values (C7): `write-attempt!` and `write-chain-attempt!` write the `attempt-tx` the plan derived instead of
  re-reading the connection and deriving it again at apply time.
- **The write fence (C5).** A write the pass emits while no graph holds the branch carries top-level
  `:datahike/expected-basis-t` (an int: `seon.db/basis-t` of the branch head it planned on) in its
  `:seon.store/transaction` map. `datahike.writing/transact!` refuses a stale one as
  `{:error :transaction/stale-basis}`, which `seon.db/transact!` answers as a typed refusal. It is not `:tx-meta`
  (unchecked) and it fences only the head of the branch written: a main move is handled by the merge-forward step,
  which reads main inside `seon.merge/merge-main!`, never by a fence. It is a full-head fence, so it is never used on a
  branch a running graph writes (every agent write would refuse it). A stale-basis refusal is absorbed as a re-plan:
  the applier answers `:replan`, `apply-actions!` stops applying that pass and offers the plan channel one wake, and
  the refusal never reaches `failed-pass!` (which would bail the task).
- Non-write effects (acquire, start, stop) are idempotent by identity: an agent id, a branch.
- Events only wake: a listened commit, a timer, the boot prime (`wake/route!` offers a payload-free wake; the pass
  reads `@connection`).

**Steps are the minimum changes from observed to desired.** A step whose observed state already holds emits nothing.
A step runs only when its prerequisites are observed true, in this pass or a later one.

| # | step | prerequisites | observed by | transition state | inverse (desired stop or retire) |
|---|---|---|---|---|---|
| 1 | fork the branch | desired names the branch; main readable | `(contains? (registry/branches store) b)` | none: `registry/fork!` is one atomic head write | retire the branch (`registry/retire-branch!`, through `my.agent/retire-branch!`); prerequisites: steps 7, 6 and 2 inverted (graph exited, contexts and the chain route released: Datahike refuses deleting a branch with an open connection, `::cluster-connected`) and no unmerged work, or root's close |
| 2 | listen on the branch | 1 | routing registry `:seon.agent/chain-routes` holds `b` | none (in-memory, idempotent by key) | unlisten and release (§4), before the inverse of 1 |
| 3 | merge main forward | 1, 2; no open attempt (C8); the branch's merge base is behind the desired base | `seon.cluster.source/merge-base` of the branch head and main: is C (or main's head) an ancestor of it? | durable and atomic: `seon.merge/merge-main!` writes ONE merge commit whose parents add main's head, fenced by `:datahike/expected-basis-t` of the branch head it read. It also writes the conflicts it leaves (C6, below) in that commit. "In progress" never persists across a crash; the commit graph shows either the merge (base = main head, satisfied) or nothing (still behind, planned again). In memory, `:seon.agent/transitions {[:merge-main b] {:basis t}}` stops a concurrent pass from planning a second merge on the same basis | none (git has no un-merge); a stop leaves it |
| 4 | converge config | 1, 3 (the merge never moves config, and config converges on the base it runs on) | the branch's config row against the desired entries | none: one reconcile transaction with its basis | none |
| 5 | write the attempt row | 1-4; desired execution `:running`; no open attempt | `seon.task/open-attempt?` on the branch | none: one transaction with its basis; it resolves `:main-head` (C8) | close the attempt (`:closed` with its reason) after the inverse of 7 |
| 6 | acquire the context | 5 | registry entry for the agent's execution | in memory: `:seon.agent/transitions {[:acquire agent-id] {:basis t}}` while `acquire-context!` runs; a crash leaves it absent, so it is planned again | release the context (`release-context!`) after the graph exited |
| 7 | start the graph | 6 | registry `:started` holds the agent | in memory: `{[:start agent-id] ...}` until `start-flow!` returns its started entry | stop the graph: `stop-flow!` with its bound, which joins the executor. Transition `{[:stop agent-id] {:deadline ...}}` until every proc thread exited (AGENTS "Bound admission and await actual exit"); while it is present, nothing that needs the exit (6 inverse, 5 inverse, 1 inverse) is planned. A stop past its bound keeps the transition, keeps the branch and reports the missing exit |

**Forward-merge conflicts (C6, root's ruling).** A conflict never holds the launch: steps 4-7 proceed and the agent
resolves it in its context. `seon.merge/merge-main!` today answers `:seon.program/conflict` with its three digests to
its caller and records nothing, and after the merge the base is lost, so the conflict cannot be re-derived from the
branch. P5b writes the conflict set and its digests (base, ours, theirs) as facts in the same merge commit, and the
agent's current-state render reads them from its branch, so the conflict is in its first context.

**Transition states are first-class observed state.**
- **In-memory transitions** (acquire, start, stop, merge-main) live in the one routing registry, keyed
  `[step subject]` with the basis they planned from. The registry is the place for live host work, observable in
  `status`.
  - The pass reads them as observed state (`registry-facts`) and never plans a duplicate.
  - It completes them when their result appears: a started entry, exited threads, a merge commit.
  - A crash empties the registry: an incomplete transition is observed absent (no graph) and planned again from the
    facts. It never resumes.
- **Durable transitions** are single atomic commits: the fork, the forward merge, the config reconcile, the attempt
  row. They need no "in progress" fact. The commit graph and the rows say whether they happened. Never doubled: the
  second plan's fence is the new head, and its merge base is already main's head, so it emits nothing.
- Nothing else is remembered between passes.

**Desired state that does not validate never starts.**
- The pass checks it with the same compiled validator (`malli.core/validator` over the registered schema).
- A stored task that fails gets one `:invalid-desired-state` problem task carrying `m/explain` as data, and no step is
  planned for it. `launch-facts` already answers such tasks as `:seon.flow.actions/invalid` and `launch-actions` turns
  them into `:problem`; P5 widens what `invalid` reads, it adds no second path.

## 2. The schema

Its name is **`:seon.task/runnable`**, registered in `resources/seon/schemas/seon.task.edn` at its producer
(`seon.task/chain!`). Consumers declare it: `seon.task/run-held!`, the task writer (`seon.task/written`), the
merge-step append (`seon.cluster.agent/merge-step`'s append, MERGE-STEP) and `seon.cluster.agent/launch-facts`.

It validates an **assembled view**, not the task as its branch holds it: `:seon.chain/branch` comes from the chain
fact on main (`seon.task/chain-branch`), and `:seon.task/config` is the branch's effective config row. Assembling it is
one function, `seon.task/runnable`, the one derivation every consumer validates.

```clojure
[:map {:closed false}
 [:seon.task/id :seon.task/id] [:seon.task/ns :seon.task/ns] [:seon.task/name :seon.task/name]
 [:seon.task/plan :seon.task/plan] [:seon.task/instructions :seon.task/instructions]
 [:seon.task/proofs [:set {:min 1} :symbol]]
 [:seon.task/execution :seon.task/execution]
 [:seon.chain/branch :seon.chain/branch]
 [:seon.task/branch {:optional true} :seon.store/branch]   ; a merge step's own branch
 [:seon.task/model {:optional true} :seon.task/model]      ; overrides the config's model when present
 [:seon.task.desired/base {:optional true} ...]            ; P5b adds it (:main-head or [:main-at-least C])
 [:seon.task/config :seon.task/desired-config]]
```

**`:seon.task/desired-config` holds the 35 dials the launch and the turn loop read on the chain branch** (C1,
measured by P1's test, which supersedes the check's count of 17). They are, by reader:
- **read by keyword** (clj-kondo keyword analysis of `seon.turn`, `seon.ai`, `seon.agent` and `seon.cluster.agent`):
  the agent dials `inbox-size`, `show-all-settings`, `turn-completion-backstop-ms`, `write-refusal-bound`; the
  provider dials `model`, `no-provider`, `endpoint`, `api-key-variable`, `no-auth`, `max-tokens`,
  `retain-reasoning`; `error/escalate-to`, `error/max-evidence-bytes`, `error/recurrence-limit`;
  `eval/time-limit-ms`, `eval.result/blob-threshold`, `eval.result/max-source`; `fn.cost/min-total-us`;
  `message/max-chain`; `run/max-episode-runs`; `test/step?`, `test/step-time-limit-ms`, `test.cost/top` (23);
- **read generically** by `seon.ai` through the dial's `:seon.ai/request-attribute` property (`wire-setting-triples`,
  `agent-setting-attributes`): `chars-per-token-prior`, `extra-body-edn`, `frequency-penalty`, `presence-penalty`,
  `prompt-token-budget`, `response-format`, `stop`, `temperature`, `thinking`, `timeout-ms`, `top-p`, `wrapping`
  (12 more).
- Known limit: keyword analysis cannot tell a read of the branch's row from a read of main's; the `error/*` and
  `message/max-chain` reads may be main-side. P4's convergence step measures where each is read and narrows the list
  with the test.

Three dials are **cluster dials root reads from main**, and are excluded: `:seon.config.agent/max-running`
(`run-pass!`, root's connection), `:seon.config.run/relaunches` (`allowance-left`, main's `config/effective`) and
`:seon.config.agent/keep-ended-branches?` (`kept?`, `seon.task/close!`, main or a report db). A per-chain entry for
them is a copy nobody reads, and it lies (the S5 branch's row says `relaunches 0`, `allowance-left` reads main's 2).

- **"Decided", not "required" (C2, C3).** An entry is decided: a value of its dial's schema, or the explicit absent
  decision. That is exactly what `seon.config/effective` answers over the shipped document `config/default.edn`
  (`seon.config/defaults`), which makes one decision for every registered dial. `no-auth` and `retain-reasoning` are
  `[:= true]` presence dials ("clear by retracting"; `false` violates their own schema) and `no-provider` is optional;
  the shipped document writes `:seon.config/absent` for all three deliberately. So the schema declares each of the 35
  as `{:optional true}` over its dial schema in a closed map, and the view is built from `effective`; there is no required list,
  no Malli `:default` in the dial schemas, and no `malli.transform/default-value-transformer`. P1 edits no
  `seon.config.*.edn` file.
- **The list is checked, not trusted.** P1's test checks it against the code: every `:seon.config.*` keyword read
  under `seon.turn`, `seon.ai`, `seon.agent/effective-settings` and the launch functions of `seon.cluster.agent`
  (clj-kondo keyword analysis) is a key of `:seon.task/desired-config` or one of the three cluster dials above. A new
  reader then fails the test until its entry is declared on one side.
- **Where it is stored.** The desired config is the branch's ordinary config row (choice A): the same
  `:seon.config/*` facts `seon.config/effective` reads, written in `chain!`'s transaction on the chain branch. There is
  no second store.

## 3. Writers that validate, before transacting

- **`seon.task/chain!`** validates each task's runnable view (task members, the chain branch it will write, and the
  effective config of main's row merged with the chain's `:seon.chain/config` option). It refuses with an error built
  from `m/explain` and writes nothing, neither fork nor tasks nor chain fact. The fork happens after validation.
- **The task writer (`seon.task/written` → `adopt-tasks-tx`)** validates the runnable view of every task it writes
  with `:execution :running`.
- **`seon.task/run-held!` (and `my.root/run!`)** validate the task's runnable view as the branch holds it before
  writing `:running`. A refusal names the offending key. Writing it is root's explicit write, by `my.task/update!` on
  the branch or a config write.
- **The merge-step append** validates the appended merge step.

## 4. The route per chain branch

- **Registration.** Each pass, before it plans launches, sees the chain facts on main
  (`[?c :seon.chain/branch ?b]`). For a branch the store holds and the registry has no route for, it takes one
  counted reference (`seon.cluster.store/acquire-branch!`) and registers `wake/route!` on it under the key
  `seon.cluster.agent/chain-route-key` and the cluster's plan channel (with the cluster's render channel and interest,
  as a graph's route carries them). The entry goes in the cluster's routing registry,
  `:seon.agent/chain-routes {branch {:seon.db/connection c :seon.cluster.wake/key k}}`.
  - Boot's first pass registers them all.
  - `chain!` writes the chain fact on main, which is listened, so the next pass registers its route before any
    attempt runs.
- **Its own key (C4).** The chain route's key is not `branch-route-key`. `release-context!` and
  `seon.cluster.host/stop!` unlisten `branch-route-key` on the connection the execution holds: with a shared key the
  first attempt's release would remove the chain's route and the original loss would return after one attempt. With
  its own key each of those sites releases only what `start-branch-flow!` registered, and `start-branch-flow!`
  registers no route on a branch that has a chain route (one route per branch, owned by the chain). A branch where a
  graph's route was registered before the chain route (a running JVM upgraded mid-attempt) carries both until that
  attempt's release removes its own; two payload-free wakes coalesce in the plan channel's sliding buffer.
- **Mailbox routing (C9).** The route has no graph of its own. Its `channels` and `fenced?` functions read, at each
  delivery, the branch's child registry in the cluster's routing (`[:seon.agent/children branch]`), the registry
  `acquire-context!` records the attempt's execution in (`child-registry!`). A new attempt's execution lands in that
  same place (a released child is replaced there), so the route follows each attempt with no re-registration; a branch
  with no child routes every recipient to the plan channel (`wake/route!`'s belt).
- **Every holder counts its reference (C12).** Datahike reference-counts `d/connect` on one branch, and the plan
  assumed `acquire-context!` did too. It does not: it opens with `store/open-branch!` (which refuses a branch already
  open) or borrows the open connection (`registry/active-branch-connection`) as a handle that owns nothing. With the
  chain route holding the branch's connection, every attempt would borrow it: its release would neither join its
  graphs (`stop-owned-graphs!` runs only for owners) nor count. P2 makes `acquire-context!` take its own counted
  reference (`store/acquire-branch!`) whenever no execution in its registry already holds the branch, so an attempt
  owns its reference and its release joins and releases exactly what it holds, whoever else holds the branch.
- **Retirement.** A route is released (unlisten, then `store/release-branch!`, then the entry leaves) by
  `seon.cluster.agent/release-chain-route!`:
  - through the branch owner, `my.agent/retire-branch!`, before it deletes the branch (it already releases the
    contexts held there first), so `finish-chains!`' `seon.task/delete-branch!` of a merged chain works unchanged;
  - by the pass's sweep, for a route whose branch main no longer names as a chain or the store no longer holds;
  - for every entry by `seon.cluster.boot/stop!` once `seon.cluster/stop-flows!` has joined db<->flow, before the
    cluster connection is released.
  `seon.cluster.boot/readiness` (the `status` answer) lists the routed branches.
- **What is deleted.** `start-branch-flow!`'s own route on a chain branch. The false docstrings of
  `seon.task/run-held!` and `my.root/run!`, and TASK-CHAIN §1's and §5's wake claims. No wake offer is added
  anywhere: the route is the wake (`:seon.task/execution` is `:seon.wake/starts true`, so run!'s commit offers the plan
  channel a wake through the chain route).
- **Cost.** One held connection per live chain, and about 0.2 ms of listener work per commit on that branch
  (`wake/route!` docstring, measured 2026-09-26). Default has 2 chains. A pass's cost does not depend on N chains
  listened beyond one set difference; it stays `chain-facts`' 9-14 ms for 20 chains (§5). P2 measures memory per held
  connection with `seon.profile/measure` around one acquire and route.

## 5. What is deleted

- **Edge-triggered starts:**
  - `start-branch-flow!`'s own route on a chain branch (§4, P2);
  - the start of chain attempt agents through `agents-to-start` (`turn/more-agent-work?`). This is a **filter, not a
    deletion**: `more-agent-work?` and `agents-to-start` stay for root and branch agents, and the pass's last step
    excludes chain attempt agents, whose graph starts only through step 7 (P5, converting
    `test/seon/cluster/lazy_agents_test.clj` in the same commit);
  - the `:seon.wake/starts`-only reliance of run! (P2: the chain route hears it).
- **The post-hoc config apply for chains** (P4 owns it whole): `bin/seon config apply --chains`, and `--branch` for a
  chain branch, become "write desired config on the branch, then the same convergence step";
  `seon.cluster.registry/apply-config-to-branches!` calls the step's function. Its callers convert in P4:
  `seon.cluster.boot`'s boot reconcile, `script/seon/operator.clj` (`--chains`, `--branch`),
  `script/seon/operator_cli_test.clj`, `test/seon/config_test.clj`. This also removes the contract-failing operator
  response (`seon.cluster.boot/request!` missing `:seon.source/commit-id`). `bin/seon config apply` stays for ad-hoc
  use, built on the same step.
- **False docstrings and spec lines:** `seon.task/run-held!`, `my.root/run!` (P2), TASK-CHAIN §1 "The chain fact on
  main", §2.7 and §5 (P1).

## 6. The small defect slices

- **D1, H0 refusal (done, 78156e4bf).** `branch-admission-end` and `branch-end` throw a read refusal (which
  `seon.cache/lookup` never retains) instead of caching it as an answer. `unmerged-count` already threw.
  - The tightened contract is the derivations' declared return (`:malli/schema`), which excludes the refusal: a
    thrown derivation is not retained, so the refusal class is refused at its producer.
  - No deftest: contracted inputs refuse every reachable refusal class first. Only a concurrent generation close
    (D2) remains.
- **D2, the canceled boot query (dig, then a fix at its owner).**
  - What fired it: Datahike cancels in-flight queries only through `datahike.query/close-query-cache-generation!`
    (→ `single-flight/close-scope!`). That runs on a connection's `:last` release (`datahike.connector`) or a failed
    open; Seon never calls `cancel-query!`.
  - Where it fired: process 46001's boot pass (`seon.cluster/start-flows!` → `db<->flow` → two nested
    `seon.cache/lookup` → `seon.db/q`), at 09:22:31.7Z. So during boot some holder made the last release of a
    connection whose generation the pass was reading. C12 (uncounted borrowed handles) is a suspect.
  - Next probe: on a check JVM, log `datahike.connector` releases with their `(connection-id, generation, refcount)`
    and the releasing frame during `seon.cluster.boot/start!`.
  - Red proof: `seon.cluster.boot-test/boot-releases-only-what-it-acquired`. The connection's refcount after
    `start!` equals the holders the instance lists, and the boot pass records no read refusal.
  - Owner of the fix: whichever step releases more than it acquired.

## 7. Phases (each one loadable commit with its red proof)

| phase | change | files | red proof (by var) |
|---|---|---|---|
| P1 | TASK-CHAIN amendment (§8); `:seon.task/runnable` and `:seon.task/desired-config` (35 optional dials) registered at their producer; the reader-coverage test | `docs/seon/schedule/cards/TASK-CHAIN.md`, `resources/seon/schemas/seon.task.edn`, `test/seon/task_test.clj` | `seon.task-test/the-runnable-schema-names-every-dial-the-launch-reads` (excludes the three cluster dials; reads both the branch-side and the main-side readers, so it fails for a wrong list either way) |
| P2 | Chain routes (step 2) with C4, C9 and C12: registration, sweep, release through the branch owner and at stop, registry entry, readiness; `acquire-context!` counts its reference; `start-branch-flow!` skips a chained branch | `src/seon/cluster/agent.clj`, `src/seon/cluster/boot.clj`, `src/my/agent.clj`, `src/seon/task.clj` (docstring), `src/my/root.clj` (docstring), `test/seon/cluster/db_flow_test.clj` | `seon.cluster.db-flow-test/a-held-chain-with-no-graph-runs-from-run-alone`: a chain whose task is held from its start, so no graph ever ran on its branch (no hand-built state); one pass; `my.root/run!` alone; the route's wake reaches the plan channel (bounded await on the channel); the next pass writes the attempt row and starts its graph, asserted on the branch's facts and the registry after the bounded await, never on elapsed ms. C4's regression `a-chain-route-outlives-its-attempts`: end attempt 1, release it, `my.root/run!` again, still woken |
| P3 | Validation at the writers (§3) | `src/seon/task.clj`, `src/my/root.clj`, `src/seon/cluster/agent.clj` (merge-step append), `test/seon/task_test.clj` | `seon.task-test/a-chain-whose-config-names-a-rejected-dial-value-is-refused` (a reachable refusal: `:seon.chain/config` names a dial with a value its schema rejects; the explanation names it; `registry/branches` and main unchanged); `seon.task-test/a-complete-chain-is-written` |
| P4 | Config in `chain!`'s transaction, plus the convergence step (step 4) before steps 5-7; the operator's `--chains`/`--branch` converted | `src/seon/task.clj`, `src/seon/config.clj` (expose the difference step), `src/seon/cluster/agent.clj`, `src/seon/cluster/registry.clj`, `src/seon/cluster/boot.clj`, `script/seon/operator.clj`, `script/seon/operator_cli_test.clj`, `test/seon/config_test.clj`, `test/seon/cluster/db_flow_test.clj` | `a-chain-written-with-a-provider-has-its-first-attempt-see-it` (the first turn's recorded settings); `config-converges-before-the-agent-starts` (orders only the two rows: the config row's tx < the attempt row's tx) |
| P5 | Step derivation in `launch-facts`/`launch-actions` as the ordered steps; invalid desired state becomes a problem; chain attempt agents excluded from `agents-to-start` | `src/seon/cluster/agent.clj`, `resources/seon/schemas/seon.flow.actions.edn`, `test/seon/cluster/db_flow_test.clj`, `test/seon/cluster/lazy_agents_test.clj` | `a-pass-stopped-mid-launch-launches-exactly-once` (apply `(take 1 actions)` through the public `apply-actions!`, then one pass: one attempt row, one graph); the problem path by `launch-facts` over a stored row the current schema rejects (`a-stored-task-the-schema-rejects-is-a-problem-never-a-launch`) |
| P5b | The facts value gains merge bases, config differences, routes and transitions (C7); advance and bail become actions; the merge-forward step by `seon.merge/merge-main!` with its fence (C5) and its conflicts written as facts (C6); `:main-head` resolved at the attempt write (C8) | `src/seon/cluster/agent.clj`, `src/seon/merge.clj` (`:datahike/expected-basis-t` on its one write map; conflict facts), `resources/seon/schemas/seon.flow.actions.edn`, the conflict schema, `test/seon/cluster/db_flow_test.clj` | `a-chain-based-on-mains-head-is-merged-forward-before-its-agent-starts` (main moves between `chain!` and the launch; the branch's merge base is main's new head before the attempt row's transaction); `a-forward-merge-runs-once-over-committed-facts` (two passes over the same facts with the merge already committed: one merge commit); `a-forward-merge-conflict-reaches-the-agents-context` |
| P5c | Stop and retire as inverses: desired `:stopped` or a retired chain plans stop → wait for exit → release → close → retire | `src/seon/cluster/agent.clj`, `test/seon/cluster/db_flow_test.clj` | `a-stop-waits-for-exit-before-its-branch-is-retired` (a turn held by the no-provider virtual turn with a held permit: assert the `[:stop agent]` transition and `registry/branches` membership while the executor is observably alive, then again after its exit is awaited; no sleeps) |
| P6 | The S5 live check; any deletion not already in the phase converting its last caller | the files of that deletion | the tests above stay green |
| D2 | Canceled boot query | the owner found by the probe | `seon.cluster.boot-test/boot-releases-only-what-it-acquired` |

- **Order.** P1 → P2 (unblocks attempt 4's class of loss by itself) → P3 → P4 → P5 → P5b → P5c → P6. D2 is
  independent.
- **Shared file.** P2-P5c all touch `src/seon/cluster/agent.clj`, so they run one after another.
- **Each deletion is in the commit that converts its last caller** (§5 names the phase); P6 holds only the live check.
- **Cost after P5:** one validator call per startable task per pass (a compiled `m/validator` over a map of ~25
  keys, microseconds) and one config-row comparison per chain branch, keyed by its head commit like H0.

## 8. TASK-CHAIN amendment (into §2 "Flow", replacing the start and resume text of §2.7, and §5)

> **Start is a reconciliation.** A runnable task's desired state is one value under `:seon.task/runnable`: its
> members, its chain branch (from the chain fact on main) and its branch's config, every dial the launch and the turn
> loop read on that branch decided (a value of its dial schema or the explicit absent decision, as
> `seon.config/effective` answers over the shipped document). `seon.task/chain!`, the task writer, `my.root/run!` and
> the merge-step append validate it with Malli before they transact and refuse with its explanation, writing
> nothing. Root's pass derives, from current facts and in order, the steps from what is to what is desired: the
> branch exists, root listens on it, it is merged forward to its base, its config converged, the attempt row, the
> agent's context acquired, the agent's graph started; and in reverse when it is desired stopped: graph stopped and
> exited, context released, attempt closed, route released, branch retired. A forward merge's conflicts are facts on
> the branch the agent's context shows; they never hold the launch. The plan is one pure function of the facts and
> the registry; a write it emits while no graph holds the branch carries the branch head it planned on
> (`:datahike/expected-basis-t`), so a concurrent change refuses it and the next pass plans again. A step already
> satisfied emits nothing. A step that takes time is a transition held in the routing registry with its basis; the
> next pass observes it and never plans a duplicate, and a crash, which empties the registry, is observed as the step
> not done. Root holds one wake route per chain branch main names, from the pass that first sees the chain fact until
> the chain retires, so every commit on a chain branch wakes root. A task whose desired state does not validate never
> starts: root records one problem carrying the explanation. `my.root/run!` is one validated transaction on the chain
> branch; the route delivers its wake.

§1 "The chain fact on main" says its installation wakes root through main's route and that the branch's own commits
wake root through the chain route. §5's "`my.root/run!` offers its own wake, §2.7" is deleted. §6 gains:
- the post-hoc chain config apply;
- `start-branch-flow!`'s own route on a chain branch;
- the `more-agent-work?` start of chain attempt agents (a filter; root and branch agents keep it).

§7 gains P1-P6 and D2 as slices.

## 9. The S5 task (chain-10d1fd5956a9) against the schema

- **Members.** Read on its branch: id, ns, name, plan, instructions, proofs, execution `:running`, attempts-made 4,
  created-by, sym, references. No `:seon.task/model` and no own `:seon.task/branch`, both optional. Its chain branch
  comes from the chain fact on main. Its members validate.
- **Config.** Its branch row decides every one of the 35 dials (some by absence), so its view validates. Its
  provider dial is whatever root last wrote (provider off for the live check), and it toggled across reconciles (§0).
- **What root does after P2 (restart onto it):** boot's first pass registers the chain route and plans the
  `:start-flow` for the open attempt `e88599e46009`; then root sets the provider on the branch when it wants the agent
  to call a model. The attempt row exists, so no config apply or run! is needed to unblock it; the graph start was what
  was missing.

## 10. Questions resolved by the narrow check

- **Every dial, or only the model-facing ones?** The 35 dials read on the chain branch, each decided by `effective`;
  the three cluster dials read from main are excluded (C1-C3).
- **Does the writer refuse a stale planning basis?** Yes, for the head of the branch written:
  `:datahike/expected-basis-t` (an int) at the top of the transaction map, passed through `seon.db/transact!` and
  declared by `:seon.store/transaction`. No new writer check and no new Datahike code; it is used only for pass writes
  while no graph holds the branch, and its refusal is a re-plan (C5).
- **A forward merge with conflicts.** It commits; the conflicts stay the branch's, written as facts in the merge
  commit, and the agent resolves them in its context. They never hold the launch (root's ruling, C6).
