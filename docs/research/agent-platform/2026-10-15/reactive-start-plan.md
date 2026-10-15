---
title: "reactive-start plan: desired state validated at the write, steps derived each pass"
type: research
---

# Reactive start: desired state validated at the write, steps derived each pass

Lane flow-pass (Opus), 2026-10-15, on default 02a2a987f (pid 52914). It follows the owner's rulings of
2026-10-15 night: "update the data and it properly goes to the right state including setting up the env first",
and "nothing executes until the data is validated by Malli ... including config entries". Root's decisions: the
config home is A (`seon.task/chain!` writes the branch's config facts in the same transaction as its tasks), routes
are option (a), and the process is one plan, a narrow check, then the build.

**Today:** `seon.cluster.agent/run-pass!` is already a level-triggered derivation: `launch-facts`, then
`launch-actions`, then `apply-actions!`. What fails is around it. Wakes are edge-triggered, the environment
(config) is applied after the fact, and nothing validates that a task can run.

## 0. What happened to attempt 4 of chain-10d1fd5956a9 (measured, read-only)

- **The write.** Root's `my.root/run!` committed `:held` → `:running` on `:chain-10d1fd5956a9` at **09:52:32.027Z**,
  not 09:22. This is in the chain branch's `:seon.task/execution` history. That JVM booted at 09:52:09 and was ready
  at 09:52:22.
- **No wake.** `seon.task/run-held!` writes through a fresh `seon.cluster.registry/on-branch` connection. Only
  `seon.cluster.agent/start-branch-flow!` registers a wake route on a branch, when an agent graph starts there. After
  a restart no graph holds the chain branch: the routing registry has `:children []` and `:started ["root"]`. So the
  commit had no listener and woke no one.
- **False claims.** The docstrings of `run-held!` and `my.root/run!` say the commit "wakes my plan". TASK-CHAIN §2.7
  and §5 say run! "offers its own wake". `git log -L` on `my.root/run!` shows no wake offer was ever written.
- **The derivation is fine.** On the current facts `launch-facts` lists `10d1fd5956a9` as startable, and
  `launch-actions` (with `max-running` 4) plans `:attempt` and `:start-flow` for `e88599e46009`. Any pass would launch
  it. The work is due and only the wake is missing.
- **The "query canceled" is a different event.** It came from the boot pass of process 46001, which booted at
  09:22:13, at 09:22:31.7Z. No run! happened then.
- **Wrong config on the branch.** The chain branch's effective config reads `:seon.config.ai/no-provider true`
  (model `"deepseek-flash"`). Attempt 4 would run with no provider. This is the race the owner named.
- **Why attempts 1-3 launched.** Attempt 1 came from the chain fact's installation on main, which is a
  `:seon.wake/starts` datom on a listened connection. Attempts 2 and 3 followed run! calls made while a graph on that
  branch (the previous attempt's) still held a connection with its route, so the commit was heard. Under (a) this no
  longer matters: every chain branch named on main has a route whether or not a graph runs.

## 1. The model

The owner's rulings, in order:
- "update the data and it properly goes to the right state including setting up the env first";
- "nothing executes until the data is validated by Malli";
- "make sure it handles transition states too ... have the desired state be spelled out and the minimum changes are
  applied".

**Desired state spells out the whole target, as data.** It is one value per chain task, validated by
`:seon.task/runnable` (§2) at the write, so invalid desired state never exists. It names:
- the branch and what it must be based on: `:seon.task.desired/base`, either `[:main-at-least C]` (main's commit C is
  an ancestor of the branch's merge base) or `:main-head` (main's head at the pass that plans);
- the branch's config entries;
- the task's execution (`:running`, `:held`, `:stopped`);
- for a merge, its target (`:seon.task.desired/merge-into`, main).

Dependencies are named in the data or derived from it (the base names main; the attempt names the branch), never
implied by call order.

**The reconcile function is pure:** `(seon.cluster.reconcile/plan desired observed) → actions`, data in and data out.
- `desired` is read from main and the chain branches.
- `observed` is read once per pass: the store's branches and heads, each branch's merge base with main (the commit
  graph, cached by commit pair as H0), each branch's config row, the attempt rows, and the routing registry,
  transitions included.
- An action is `{:op ... :subject ... :basis {...}}`.
- **Every write action commits with its planning basis** as transaction metadata, and the writer checks it, so a
  concurrent change refuses the write and the next pass re-plans. The basis is the commit ids it read: the branch head
  and main's head; an attempt write's basis is the branch head it planned on.
- Non-write effects (acquire, start, stop) are idempotent by identity: an agent id, a branch.
- Events only wake: a listened commit, a timer, the boot prime.

**Steps are the minimum changes from observed to desired.** A step whose observed state already holds emits nothing.
A step runs only when its prerequisites are observed true, in this pass or a later one.

| # | step | prerequisites | observed by | transition state | inverse (desired stop or retire) |
|---|---|---|---|---|---|
| 1 | fork the branch | desired names the branch; main readable | `(contains? (registry/branches store) b)` | none: `registry/fork!` is one atomic head write | retire the branch (`registry/retire-branch!`); prerequisites: step 6's inverse observed (graph exited) and no unmerged work, or root's close |
| 2 | listen on the branch | 1 | routing registry `:seon.agent/chain-routes` holds `b` | none (in-memory, idempotent) | unlisten and release, after the inverse of 1 |
| 3 | merge main forward | 1, 2; the branch's merge base is behind the desired base | `seon.cluster.source/merge-base` of the branch head and main: is C (or main's head) an ancestor of it? | durable and atomic: `seon.merge/merge-main!` writes ONE merge commit whose parents add main's head. "In progress" never persists across a crash; the commit graph shows either the merge (base = main head, satisfied) or nothing (still behind, planned again). In memory, the registry's `:seon.agent/transitions {[:merge-main b] {:basis main-head :started t}}` stops a concurrent pass from planning a second merge on the same basis | none (git has no un-merge); a stop leaves it |
| 4 | converge config | 1, 3 (the merge never moves config, and config converges on the base it runs on) | the branch's config row against the desired entries (key by the branch head and desired digest, as H0) | none: one reconcile transaction with its basis | none |
| 5 | write the attempt row | 1-4; desired execution `:running`; no open attempt | `seon.task/open-attempt?` on the branch | none: one transaction with basis = branch head | close the attempt (`:closed` with its reason) after the inverse of 7 |
| 6 | acquire the context | 5 | registry entry for the agent's execution | in memory: `:seon.agent/transitions {[:acquire agent-id] {:basis head}}` while `acquire-context!` runs; a crash leaves it absent, so it is planned again | release the context (`release-context!`) after the graph exited |
| 7 | start the graph | 6 | registry `:started` holds the agent | in memory: `{[:start agent-id] ...}` until `start-flow!` returns its started entry | stop the graph: `stop-flow!` with its bound, which joins the executor. Transition `{[:stop agent-id] {:deadline ...}}` until every proc thread exited (AGENTS "Bound admission and await actual exit"); while it is present, nothing that needs the exit (6 inverse, 5 inverse, 1 inverse) is planned. A stop past its bound keeps the transition, keeps the branch and reports the missing exit |

**Transition states are first-class observed state.**
- **In-memory transitions** (acquire, start, stop) live in the one routing registry, keyed `[step subject]` with the
  basis they planned from. The registry is the place for live host work, observable in `status`.
  - The pass reads them as observed state and never plans a duplicate.
  - It completes them when their result appears: a started entry, or exited threads.
  - A crash empties the registry: an incomplete transition is observed absent (no graph) and planned again from the
    facts. It never resumes.
- **Durable transitions** are single atomic commits: the fork, the forward merge, the config reconcile, the attempt
  row. They need no "in progress" fact. The commit graph and the rows say whether they happened, so a crash during a
  forward merge either left the merge commit (satisfied) or did not (planned once more). Never doubled: the second
  plan's basis is the new head, whose merge base is already main's head, so it emits nothing.
- Nothing else is remembered between passes.

**Desired state that does not validate never starts.**
- The pass checks it with the same compiled validator (`malli.core/validator` over the registered schema).
- A task that fails, such as one written before this rule, gets one `:invalid-desired-state` problem task carrying
  `m/explain` as data, and no step is planned for it.
- This is the owner's typed refusal at the owner: the writer's own contract, applied to data that predates it.

## 2. The schema

Its name is **`:seon.task/runnable`**, registered in `resources/seon/schemas/seon.task.edn` at its producer
(`seon.task/chain!`). Consumers declare it: `seon.task/run-held!`, the task writer (`seon.task/written`), the
merge-step append (`seon.cluster.agent/merge-step`'s append, MERGE-STEP) and `seon.cluster.agent/launch-facts`.

```clojure
[:map {:closed false}
 [:seon.task/id :seon.task/id] [:seon.task/ns :seon.task/ns] [:seon.task/name :seon.task/name]
 [:seon.task/plan :seon.task/plan] [:seon.task/instructions :seon.task/instructions]
 [:seon.task/proofs [:set {:min 1} :symbol]]
 [:seon.task/execution :seon.task/execution]
 [:seon.chain/branch :seon.chain/branch]
 [:seon.task/branch {:optional true} :seon.store/branch]   ; a merge step's own branch
 [:seon.task/model {:optional true} :seon.task/model]      ; overrides the config's model when present
 [:seon.task.desired/base [:or [:= :main-head] [:tuple [:= :main-at-least] :seon.source/commit-id]]]
 [:seon.task.desired/merge-into {:optional true} :seon.store/branch]   ; a merge step: main
 [:seon.task/config :seon.task/desired-config]]
```

`:seon.task/desired-config` is a closed map. Every entry is required, and each entry is named by the reader that
reads it on the launch or turn path (found by grepping each dial and checking its reader):

| entry | reader |
|---|---|
| `:seon.config.ai/model` | `seon.ai/settings` (via `seon.agent/effective-settings`) |
| `:seon.config.ai/no-provider` | `seon.turn/call-turn` |
| `:seon.config.ai/endpoint`, `:seon.config.ai/api-key-variable`, `:seon.config.ai/max-tokens`, `:seon.config.ai/no-auth` | `seon.ai` provider build (`seon.ai/settings`) |
| `:seon.config.ai/retain-reasoning` | `seon.turn/record-attempt!` |
| `:seon.config.run/max-episode-runs` | `seon.turn/max-episode-runs` |
| `:seon.config.run/relaunches` | `seon.cluster.agent/allowance-left` |
| `:seon.config.agent/max-running` | `seon.cluster.agent/run-pass!` → `launch-actions` |
| `:seon.config.agent/inbox-size` | `seon.cluster.agent/start-claimed-flow!` |
| `:seon.config.agent/turn-completion-backstop-ms` | `seon.turn/turn-completion-error`, `seon.turn/await-turn-permit!` |
| `:seon.config.agent/write-refusal-bound` | `seon.turn/write-refusal-bound` |
| `:seon.config.agent/keep-ended-branches?` | `seon.cluster.agent/kept?`, `seon.task/close!` |
| `:seon.config.test/step?`, `:seon.config.test/step-time-limit-ms` | `seon.turn/step`, `seon.turn/step-limit-ms` |
| `:seon.config.eval/time-limit-ms` | the eval request's bound (`seon.agent/effective-settings` shows it) |

- **The list is checked, not trusted.** Phase 1 checks it against the code by a test: every `:seon.config.*` keyword
  read under `seon.turn`, `seon.ai` and `seon.cluster.agent`'s launch functions (clj-kondo keyword analysis) is a key
  of `:seon.task/desired-config`. A new reader then fails the test until its entry is declared.
- **Defaults.** An entry whose dial schema declares `{:default v}` (in `resources/seon/schemas/seon.config.*.edn`) is
  filled by `malli.transform/default-value-transformer` at the writer, before validation. The stored value then
  carries it: "specified" means present after the declared defaults, visible in the data. `seon.config/defaults`
  remains the boot constant; a dial with no `:default` must be written by root.
- **Where it is stored.** The desired config is written as the branch's ordinary config row. This is choice A: the
  same `:seon.config/*` facts `seon.config/effective` reads, in `chain!`'s transaction on the chain branch.
  `:seon.task/config` is the validated view assembled for validation (task members plus the branch's effective
  entries), not a second store.

## 3. Writers that validate, before transacting

- **`seon.task/chain!`** validates each task's runnable value: task members, plus the branch's effective config from
  main's row merged with the chain's `:seon.chain/config` option, with declared defaults filled. It refuses with
  `seon.error` built from `m/explain` and writes nothing, neither fork nor tasks nor chain fact. The fork happens after
  validation.
- **The task writer (`seon.task/written` → `adopt-tasks-tx`)** validates the runnable value of every task it writes
  with `:execution :running`.
- **`seon.task/run-held!` (and `my.root/run!`)** validate the task as the branch holds it before writing `:running`.
  A refusal names the missing key. Writing the key is root's explicit write, by `my.task/update!` on the branch or a
  config write.
- **The merge-step append** validates the appended merge step.

## 4. The route per chain branch

- **Registration.** A pass's step 2 sees a chain fact on main whose branch has no route. It acquires one connection
  (`seon.cluster.store/acquire-branch!`) and registers `wake/route!` with key
  `seon.cluster.agent/branch-route-key` and the cluster's plan channel. The connection and route go in the routing
  registry, under `:seon.agent/chain-routes {branch {:connection c :route k}}`.
  - Boot's first pass registers them all.
  - `chain!` writes the chain fact on main, which is listened, so the next pass registers its route before any attempt
    exists.
  - The connection is shared by refcount with the agent graphs on that branch (`acquire-branch!`), so it is still
    one writer per branch.
- **Retirement.** When main no longer names the chain, or the branch is absent from the store's branches, the pass
  unlistens and releases the connection. Removing the entry and releasing are one step, and `seon.cluster/stop!`
  releases every entry. `status` shows the entries (the registry rule).
- **What is deleted.** `start-branch-flow!` no longer registers a branch route of its own when a chain route exists:
  one route per branch, owned by the chain. Also deleted: the false docstrings and TASK-CHAIN's "run! offers its own
  wake". No wake offer is added anywhere; the route is the wake.
- **Cost.** One held connection per live chain, and about 0.2 ms of listener work per commit on that branch
  (`wake/route!` docstring, measured 2026-09-26). Default has 2 chains. A pass's cost does not depend on N chains
  listened; it stays `chain-facts`' 9-14 ms for 20 chains (§5). Phase 2 measures memory per held connection with
  `seon.profile/measure` around one acquire.

## 5. What is deleted

- **Edge-triggered starts:**
  - the `agents-to-start` start of an attempt agent through `turn/more-agent-work?` for chain agents: their graph
    starts only through step 6;
  - `start-branch-flow!`'s own branch route (§4);
  - the `:seon.wake/starts`-only reliance of run!.
- **The post-hoc config apply for chains:** `bin/seon config apply --chains`, and `--branch` for a chain branch,
  becomes "write desired config on the branch, then the same convergence step"
  (`seon.cluster.registry/apply-config-to-branches!` calls the step's function).
  - This also removes its contract-failing operator response (`seon.cluster.boot/request!` missing
    `:seon.source/commit-id`).
  - `bin/seon config apply` stays for ad-hoc use, built on the same step.
- **False docstrings and spec lines:** `seon.task/run-held!`, `my.root/run!`, TASK-CHAIN §2.7 and §5.

## 6. The small defect slices

- **D1, H0 refusal (done, 78156e4bf).** `branch-admission-end` and `branch-end` throw a read refusal (which
  `seon.cache/lookup` never retains) instead of caching it as an answer. `unmerged-count` already threw.
  - The ruling asked for `retain?`. Throwing is stronger: the derivation's declared value is never a refusal, and a
    thrown derivation is not retained.
  - No deftest: contracted inputs refuse every reachable refusal class first. Only a concurrent generation close
    (D2) remains.
- **D2, the canceled boot query (dig, then a fix at its owner).**
  - What fired it: Datahike cancels in-flight queries only through `datahike.query/close-query-cache-generation!`
    (→ `single-flight/close-scope!`). That runs on a connection's `:last` release (`datahike.connector`) or a failed
    open; Seon never calls `cancel-query!`.
  - Where it fired: process 46001's boot pass (`seon.cluster/start-flows!` → `db<->flow` → two nested
    `seon.cache/lookup` → `seon.db/q`), at 09:22:31.7Z. So during boot some holder made the last release of a
    connection whose generation the pass was reading.
  - Next probe: on a check JVM, log `datahike.connector` releases with their `(connection-id, generation, refcount)`
    and the releasing frame during `seon.cluster.boot/start!`. The suspects are boot steps that `d/connect` and
    `d/release` main or a branch (adoption, config reconcile, recovery's `on-branch`) and leave the cluster's own
    reference uncounted.
  - Red proof: `seon.cluster.boot-test/boot-releases-only-what-it-acquired`. The connection's refcount after
    `start!` equals the holders the instance lists, and the boot pass records no read refusal.
  - Owner of the fix: whichever step releases more than it acquired.

## 7. Phases (each one loadable commit with its red proof)

| phase | change | files | red proof (by var) |
|---|---|---|---|
| P1 | TASK-CHAIN amendment (§8); `:seon.task/runnable` and `:seon.task/desired-config` with `:default`s; the reader-coverage test | `docs/seon/schedule/cards/TASK-CHAIN.md`, `resources/seon/schemas/seon.task.edn`, `resources/seon/schemas/seon.config.*.edn` (defaults), `test/seon/task_test.clj` | `seon.task-test/the-runnable-schema-names-every-dial-the-launch-reads` |
| P2 | Chain routes (step 2): registration, retirement, registry entry, status | `src/seon/cluster/agent.clj`, `src/seon/task.clj` (docstring), `src/my/root.clj` (docstring), `test/seon/cluster/db_flow_test.clj` | `seon.cluster.db-flow-test/a-held-chain-with-no-graph-runs-from-run-alone`: restart-shaped (no graph on the branch), `my.root/run!`, then the attempt row and its agent's graph within the db<->flow bound, with no other write (proof d) |
| P3 | Validation at the writers (§3) | `src/seon/task.clj`, `src/my/root.clj`, `src/seon/cluster/agent.clj` (merge-step append), `test/seon/task_test.clj` | `seon.task-test/a-chain-missing-a-required-dial-is-refused-naming-it` (the explanation names `:seon.config.run/max-episode-runs`; `registry/branches` and main unchanged); `seon.task-test/a-complete-chain-is-written` |
| P4 | Config in `chain!`'s transaction, plus the convergence step (step 3) before steps 4-6 | `src/seon/task.clj`, `src/seon/config.clj` (expose the difference step), `src/seon/cluster/agent.clj`, `src/seon/cluster/registry.clj`, `script/seon/operator.clj` (config apply on the step), `test/seon/cluster/db_flow_test.clj` | `a-chain-written-with-a-provider-has-its-first-attempt-see-it` (proof a); `config-converges-before-the-agent-starts` (proof c: the config row's tx < the attempt row's tx < the graph start, read from facts and the registry) |
| P5 | Step derivation in `launch-facts`/`launch-actions` as the ordered steps; invalid desired state becomes a problem | `src/seon/cluster/agent.clj`, `resources/seon/schemas/seon.flow.actions.edn`, `test/seon/cluster/db_flow_test.clj` | `a-pass-stopped-mid-launch-launches-exactly-once` (proof b: apply the first action only, then wake; one attempt row, one graph); `a-task-written-before-validation-never-starts-and-shows-its-problem` |
| P5b | The pure planner and the merge-forward step: `seon.cluster.reconcile/plan` over `(desired, observed)`; step 3 by `seon.merge/merge-main!` with its basis; in-memory transitions in the routing registry | `src/seon/cluster/reconcile.clj` (new, it replaces `launch-actions` and the chain parts of `next-actions`), `src/seon/cluster/agent.clj`, `src/seon/merge.clj` (basis on the merge commit's tx-meta), `test/seon/cluster/db_flow_test.clj` | `a-chain-based-on-mains-head-is-merged-forward-before-its-agent-starts` (main moves between `chain!` and the launch; the branch's merge base is main's new head before the attempt row's transaction); `a-forward-merge-interrupted-is-completed-once` (plan step 3, crash-shaped: drop the registry's transition without its commit, then wake: exactly one merge commit; again with the commit written: zero more) |
| P5c | Stop and retire as inverses: desired `:stopped` or a retired chain plans stop → wait for exit → release → close → retire | `src/seon/cluster/reconcile.clj`, `src/seon/cluster/agent.clj`, `test/seon/cluster/db_flow_test.clj` | `a-stop-waits-for-exit-before-its-branch-is-retired` (a graph whose turn holds past the first pass: the branch stays in `registry/branches` while the `[:stop agent]` transition is present, and is retired only after the executor joined) |
| P6 | Deletions (§5) and the S5 live check | as §5 | the tests above stay green; the operator test `script/seon/operator_cli_test.clj` for config apply |
| D2 | Canceled boot query | the owner found by the probe | `seon.cluster.boot-test/boot-releases-only-what-it-acquired` |

- **Order.** P1 → P2 (unblocks attempt 4 by itself) → P3 → P4 → P5 → P5b → P5c → P6. D2 is independent.
- **Shared file.** P2-P5 all touch `src/seon/cluster/agent.clj`, so they run one after another.
- **Cost after P5:** one validator call per startable task per pass (a compiled `m/validator` over a map of ~25
  keys, microseconds) and one config-row comparison per chain branch, keyed by its head commit like H0.

## 8. TASK-CHAIN amendment (into §2 "Flow", replacing the start and resume text of §2.7, and §5)

> **Start is a reconciliation.** A runnable task's desired state is one value under `:seon.task/runnable`: its
> members, its chain branch and the branch's desired config entries, every entry the launch and the turn loop read
> required, a dial's declared `:default` filled at the writer. `seon.task/chain!`, the task writer, `my.root/run!` and
> the merge-step append validate it with Malli before they transact and refuse with its explanation, writing nothing.
> Root's pass derives, from current facts and in order, the steps from what is to what is desired: the branch exists,
> root listens on it, its config converged, the attempt row, the agent's context acquirable, the agent's graph
> started, and in reverse when it is desired stopped: graph stopped and exited, context released, attempt closed,
> branch retired. Desired state names the branch's base (main's head, or at least commit C), so a branch behind it is
> merged forward (`seon.merge/merge-main!`) before config converges and the agent starts. The plan is one pure
> function of desired and observed state; every write it emits commits with the basis it planned from, so a
> concurrent change refuses it and the next pass plans again. A step already satisfied emits nothing. A step that
> takes time is a transition held in the routing registry with its basis (acquiring, starting, stopping until actual
> exit); the next pass observes it and never plans a duplicate, and a crash, which empties the registry, is observed
> as the step not done. Durable steps are single commits judged by the commit graph and the rows. Root holds one wake route per chain branch main names, from
> the pass that first sees the chain fact until the chain retires, so every commit on a chain branch wakes root. A task
> whose desired state does not validate never starts: root records one problem carrying the explanation.
> `my.root/run!` is one validated transaction on the chain branch; the route delivers its wake.

§5's sentence "`my.root/run!` offers its own wake, §2.7" is deleted. §6 gains:
- the post-hoc chain config apply;
- `start-branch-flow!`'s own route;
- the `more-agent-work?` start of attempt agents.

§7 gains P1-P6 and D2 as slices.

## 9. The S5 task (chain-10d1fd5956a9) against the schema

- **Members.** Read on its branch: it holds id, ns, name, plan, instructions, proofs, execution `:running`,
  attempts-made 3 and created-by. It has no `:seon.task/model` and no own `:seon.task/branch`, both optional. Its
  members validate.
- **Config.** Its branch's config row has every entry, from the forked main row and shipped defaults, so it
  validates. But its value is `:seon.config.ai/no-provider true`.
- **What root must write before attempt 4:** `:seon.config.ai/no-provider false` (and the model, if not
  `"deepseek-flash"`) on that branch's config row. Today that is
  `bin/seon config apply default PATH --branch chain-10d1fd5956a9`; after P4 it is the desired-config write.
- **What root can do now (P2 not yet built), either of:**
  - restart default: boot's pass derives the launch, which was verified planned above;
  - any `:seon.wake/starts` write on main.

## 10. Open question (one)

- **Should `:seon.task/desired-config` hold every dial the readers read (~20, mostly defaulted), or only the
  model-facing ones (ai/*, run/*) with the agent and test dials left to cluster config?**
  - The owner's rule says every field execution needs, so the plan takes all of them, defaults filled.
  - Root may narrow it in the narrow check.
- **The writer's basis check.** "Every write commits with its planning basis and the writer checks it" needs a
  check in `seon.db`'s writer: refuse when the branch head or main's head named in `:tx-meta` is no longer current.
  The digest check the writer runs for program entities is the nearest existing seam. The narrow check should verify
  whether a general head-basis refusal exists, or name it as P5b's addition, priced at one commit-id comparison per
  write.
- **A forward merge with conflicts.** `merge-main!` still commits: the conflicts stay the branch's, and the merge base
  becomes main's head. So step 3 is satisfied, and the conflicts go to root as the task's problem; they do not block
  steps 4-7. Root may rule instead that a conflict holds the launch.
