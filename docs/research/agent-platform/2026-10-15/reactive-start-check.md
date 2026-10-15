---
title: "reactive-start plan: narrow check"
type: research
status: narrow check of plan revision 49fdd1bb5; needs correction (C1-C11); read-only probes on default pid 54791
---

# Narrow check of the reactive-start plan (revision 49fdd1bb5)

Lane rs-check (Sonnet), 2026-10-15. Probes ran read-only in JVM mode on default, pid 54791 (booted 10:07:57Z, after
root's restart; the 09:52 JVM the plan measured is gone, so its probes were re-run, not repeated). No writes, no defs.
One probe (a Datahike `history` scan of the branch's config row) allocated **3,758 MB** in 1.6 s, over the 2 GB stop
line; I did not repeat it. Finding: reading a config attribute's history through `datahike.api/history` is a
whole-store scan (file it as a cost task if it is a supported path).

**Verdict: needs correction.** The model (desired state, pure plan, durable-or-registry transitions) is sound and the
seams exist. Eight corrections below; five change what P1-P5b must say. None needs a new idea.

## 1. Claims about the live system

| # | claim | verdict | evidence |
|---|---|---|---|
| 1 | `run-held!` writes through a fresh connection; only `start-branch-flow!` registers a branch route | **Reproduced (static + live)** | `seon.task/run-held!` writes inside `registry/on-branch`. `wake/route!` callers in `src`: `seon.cluster.agent/start-branch-flow!` (the branch route) and the cluster proc's own route on main (`agent.clj` ~2868). No third. |
| 2 | Routing registry has `:children []`, `:started ["root"]` | **Not reproducible now; state moved** | On pid 54791: `:children [:chain-10d1fd5956a9]`, root `:started #{}`, child `:started nil`, child `:contexts [[:chain-10d1fd5956a9 nil]]`. The boot pass on this JVM already wrote the attempt row and acquired a context, but no graph was started (see 3). The old state was the pre-restart JVM's. |
| 3 | Launch derivation plans `e88599e46009` | **Reproduced, and the state is the mid-launch case** | `launch-facts` (28 ms): `:startable #{}`, `:open` holds attempt `e88599e46009` of `10d1fd5956a9` (`:running`, branch `:chain-10d1fd5956a9`). `launch-actions` with `max-running` 4 plans exactly one `:start-flow` for `attempt/e88599e46009` and no `:attempt` (the row exists). Two probes 40 s apart: still no graph. So this JVM is stopped between "context acquired" and "graph started". Root should look at why (the status is `:faulted`, 25 open faults, one is `seon.render.web/feed` await bound). This is the live instance of the plan's mid-launch proof. |
| 4 | Branch config reads `no-provider true` | **Refuted at probe time** | Main's row has `:seon.config.ai/no-provider true`. The branch's row has **no** `no-provider` datom (its `effective` answers the absent decision), so a provider would be used. History: 14 add/retract pairs of `true` on the branch row across boot and apply; the last transaction (536873688) is a retract. Something toggles it on every reconcile; the plan's "reads true" was one point in that toggling. Branch also reads `max-episode-runs 12`, `relaunches 0`, main reads 100 and 2. |
| 5 | About 20 config entries and their readers | **Partly refuted: 17 dials, 5 readers wrong, 3 read from main not the branch** | Table in section 2. |
| 6 | S5 task validates except `no-provider` | **Refuted in both halves** | Members: id, ns, name, plan, instructions, proofs, execution `:running`, attempts-made **4**, created-by, sym, references. `:seon.chain/branch` is not a task member: it comes from the chain fact on main (`seon.task/chain-branch`), so the schema is over an assembled view, not the task as the branch holds it (say so in §2). Config: three dials are absent by design (`no-provider`, `no-auth`, `retain-reasoning`), so a closed map with every entry **required** fails on them, not on `no-provider true`. |

## 2. The config entries (claim 5)

Readers confirmed by grep at HEAD (`src`, `script`):

| dial | plan's reader | actual reader | note |
|---|---|---|---|
| ai/model | `seon.ai/settings` | `seon.agent/effective-settings` (agent.clj:152), `seon.eval.drive` | ok |
| ai/no-provider | `seon.turn/call-turn` | `seon.turn` 4002, 4049, 4105, 4118 | ok |
| ai/endpoint, api-key-variable, no-auth | `seon.ai` | `seon.ai` 368, 377, 378, 396 | ok |
| ai/max-tokens | `seon.ai` | `seon.ai` 479 (by `config-ident`) | ok |
| ai/retain-reasoning | `seon.turn/record-attempt!` | `seon.turn` 3722 | ok |
| run/max-episode-runs | `seon.turn/max-episode-runs` | `seon.bootstrap` 140 and `seon.turn` 1968 | reader names differ |
| run/relaunches | `allowance-left` | `allowance-left`, from **main** `config/effective database cluster` | **not the branch** |
| agent/max-running | `run-pass!` | `run-pass!` 2813, from root's connection (main) | **not the branch** |
| agent/inbox-size | `start-claimed-flow!` | `agent.clj` 1385, `config/effective @connection` of the agent's handle (branch) | ok |
| agent/turn-completion-backstop-ms | `turn/...` | `seon.turn` 4997/5017 and `seon.cluster` 3594 (stop, on the handle) | extra reader |
| agent/write-refusal-bound | `seon.turn/write-refusal-bound` | `seon.turn` 5129 (`agent-dial`) | ok |
| agent/keep-ended-branches? | `kept?`, `close!` | `agent.clj` 2466, `task.clj` 1359 via `agent-cluster-dial` on a main or report db | ok, main side |
| test/step?, step-time-limit-ms | `turn/step`, `step-limit-ms` | `seon.turn` 5876, 5551 | ok |
| eval/time-limit-ms | the eval bound | `seon.effect` 512, `seon.eval.drive` 272, `seon.cluster` 3314, `my.agent` | extra readers |

Corrections:
- **C1. Three dials are cluster-level and root-read from main** (`max-running`, `relaunches`, `keep-ended-branches?`).
  A per-chain desired-config entry for them is a copy nobody reads, and it lies: this branch's row says
  `relaunches 0`, `allowance-left` reads main's 2. Take them out of `:seon.task/desired-config`, or make their readers
  read the branch (a larger change). Recommend removing; that leaves 14.
- **C2. "Every entry required" cannot hold for the optional dials.** `retain-reasoning` and `no-auth` are `[:= true]`
  (presence asserts, "clear by retracting"; `false` violates the dial's own schema). `no-provider` is `:boolean` with
  `:seon.config/optional true`, and the shipped document `config/default.edn` writes `:seon.config/absent` for all
  three deliberately. State the rule as: the entry is **decided**, i.e. a value of its dial schema or the explicit
  absent decision, which is what `seon.config/effective` already answers. Build the desired-config view from
  `effective`, not from a new required list.
- **C3. There is no `{:default v}` in the dial schemas.** The mechanism is the shipped document `config/default.edn`
  (`seon.config/defaults`) and, on two dials, the property `:seon.config/default` (`seon.config.test.edn`). So
  `malli.transform/default-value-transformer` fills nothing today. Replace "declared `{:default}` filled by the
  transformer" with "decided by `seon.config/effective` over `config/default.edn`". That also deletes P1's edit of
  `seon.config.*.edn` defaults.

## 3. Deletions (question 2)

Callers at HEAD (`src test script resources`), and whether the plan's phase converts them:

| deletion | callers | converted by | verdict |
|---|---|---|---|
| `start-branch-flow!`'s own branch route | `agent.clj` 1980 (the registration); **unlisten at `agent.clj` 994-995 (`release-context!`) and `host.clj` 157 (`stop!`)**, both by `branch-route-key` | P2 names only the registration | **Gap (C4).** The chain route shares the key and the (refcounted, same) connection, so the first attempt's `release-context!` or `host/stop!` unlistens the chain's route and the original bug returns after one attempt. P2 must convert both unlisten sites (release only what the call registered) in the same commit, with a red test: end attempt 1, run! again, still woken. |
| `more-agent-work?` start of chain agents via `agents-to-start` | `agent.clj` 2769, 2782, 2829 (all agents, not only chain); `test/seon/cluster/lazy_agents_test.clj` 24, 26, 34; `turn_work_test`, `turn_test`, `web_test`, `no_provider_test`, `turn.edn` task text | P5 says "their graph starts only through step 6" | **Not a deletion; a filter.** `more-agent-work?` and `agents-to-start` stay for root and branch agents. Say "excludes chain attempt agents" and convert `lazy_agents_test` in the same commit. Naming this a deletion invites removing the root start. |
| `apply-config-to-branches!` | `registry.clj` 232, `boot.clj` 766 (boot reconcile), `test/seon/config_test.clj` 938 | P4 "calls the step's function" | Converted (a rewrite, not a deletion). P6's "as §5" file list is empty of `boot.clj` and `config_test.clj`. List them. |
| `--chains`, `--branch` for chain branches | `script/seon/operator.clj` 2236, 2285-2287, 2314; `script/seon/operator_cli_test.clj` | P4 (operator) and P6 | Both phases claim it; P4 changes it, P6 deletes it. Give it to one phase. |
| false docstrings | `task.clj` 976-977, `my/root.clj` 169, TASK-CHAIN 103, 525-526 | P2/P1 | Converted. Add TASK-CHAIN 103 and 525-526 (`run! offers its own wake`). |
| `launch-actions` and "the chain parts of `next-actions`" (P5b) | `agent.clj` 1675 (public), 2817; tests: grep `launch-actions` in `test`; `seon.flow.actions` schemas | P5b | The plan says P5b "replaces" them but lists no caller conversion. `launch-actions` is the pure planner the owner's question (a) names as existing. Keep it and extend it, or list every caller. |

## 4. Proofs (question 3)

All nine named proofs are **new** deftests (none exists: grepped `test`, `script`). None needs a Var redefinition.
Notes:

| phase | proof | verdict |
|---|---|---|
| P1 | `the-runnable-schema-names-every-dial-the-launch-reads` | New. Writable (kondo keyword analysis of `seon.turn`, `seon.ai`, launch functions). It must exclude the C1 dials and read both branch and main readers, or it passes for the wrong list. |
| P2 | `a-held-chain-with-no-graph-runs-from-run-alone` | New. **Hard**: the existing run! tests (`db_flow_test` ~553, 623, 646, 2072) all run with the previous attempt's graph alive, which is exactly what masked the bug. A restart-shaped test needs a cluster restarted over a store (`program_restart_test`, `boot_test` drill) or a pass over a chain with no graph ever started; it cannot be made by hand-building. "Within the db<->flow bound" is an await bound, not a wall-clock proof, if it waits through `seon.await`; assert on facts (attempt row, registry started) after the bounded await, not on elapsed ms. |
| P3 | `a-chain-missing-a-required-dial-is-refused-naming-it` | New, **probably unreachable**: with decided dials filled from main's effective row (C2/C3) a chain cannot lack a dial unless main's row lacks it, and the apply path refuses that earlier. Pick a reachable refusal: a chain whose `:seon.chain/config` names a dial with a value its schema rejects, or a task with no proofs. `a-complete-chain-is-written` is fine, new. |
| P4 | `a-chain-written-with-a-provider-has-its-first-attempt-see-it` | New, writable (read the first turn's recorded settings). |
| P4 | `config-converges-before-the-agent-starts` | New. "Config tx < attempt tx < graph start" has **no graph-start tx**: the registry records no time. Observe the order by the attempt's first recorded settings, or order only the two rows. As stated it is unmeasurable. |
| P5 | `a-pass-stopped-mid-launch-launches-exactly-once` | New, writable without redefs: `apply-actions!` is public and takes a vector, so apply `(take 1 actions)` then run a pass. Section 1 claim 3 shows the live state it targets. |
| P5 | `a-task-written-before-validation-never-starts-and-shows-its-problem` | New; needs a way to write an invalid task, which the writer now refuses (P3). Reachable only by a task written before P3 (fixture data), which is "hand-built entities". Use a task whose config the schema rejects after a later schema tightening, or drop this test and cover the problem path by `launch-facts` on a read of the stored row. |
| P5b | `a-chain-based-on-mains-head-is-merged-forward-before-its-agent-starts` | New, writable (`my.root/merge-main!` tests exist at `db_flow_test` 2440, 2555 and `merge_forward_test`). |
| P5b | `a-forward-merge-interrupted-is-completed-once` | New. "Drop the registry's transition without its commit" is a crash simulated by editing the registry atom: the registry is the place for it, but a test writing into it is a hand-built world. A real crash (JVM drill) is host-bound. Prefer: run the pass twice over the same facts with the merge's write already committed, and assert one merge commit; the "crash between plan and commit" case is the basis refusal (section 5). |
| P5c | `a-stop-waits-for-exit-before-its-branch-is-retired` | New. "A graph whose turn holds past the first pass" needs a held turn without a Var redefinition (use the no-provider virtual turn with a held permit or a real long eval). **It is a wall-clock proof if written as "stays until"**: assert the transition entry and `registry/branches` membership at a point where the executor is observably alive, then after `await-exit`, not after a sleep. |
| D2 | `boot-releases-only-what-it-acquired` | New. Needs the probe's finding first; fine as a red target. |

D1 is declared "no deftest". AGENTS: every bug fix adds or tightens a contract at the producer. The commit 78156e4bf
did not name the tightened contract; say which (the derivation's `:malli/schema` return excludes the refusal).

## 5. Open question 1: does the writer refuse a write whose planning basis is stale?

**Yes for the branch's own head; no for "main's head" and no for `:tx-meta`.** Verified by reading:
- `datahike.writing/transact!` (reference-code/datahike `88d05cf8`, `writing.cljc` 1129-1142): an arg map with
  top-level `:datahike/expected-basis-t` is a full-head precondition. The serialized writer throws
  `{:error :transaction/stale-basis ...}` unless the connection's `:max-tx` equals it. `merge-writer!` applies the
  same fence (`versioning.cljc` 919 arg-map form). Datahike tests: `transact_test` 200, `writer_error_test` 271,
  `versioning_test` 466.
- Seon passes it through: `seon.db/transact!` docstring (db.clj 6486), the schema `:seon.store/transaction` declares
  `[:datahike/expected-basis-t {:optional true} :int]` (`seon.store.edn` 40), and it answers a typed refusal
  (`[:seon.error/data :error] = :transaction/stale-basis`). Seon tests exist:
  `seon.db-test/a-merge-shares-the-write-fence-and-records-immutable-lineage` (db_test 1753) and
  `seon.cluster.publication-lock-test/stale-basis-is-refused-by-the-transaction-writer`.
- **Corrections to the plan's wording (C5).** (a) The fence is a top-level key with an **int** (`db/basis-t` /
  `:max-tx`), not a commit id in `:tx-meta`; `:tx-meta` is not checked. Replace "carries its planning basis as
  transaction metadata" with "carries `:datahike/expected-basis-t` of the branch it writes". (b) It fences only the
  head of the branch written. "Main's head" in the basis cannot be checked by a write on a chain branch; a main move
  is handled by the merge-forward step reading main inside `merge-main!`, not by a fence. (c) It is a full-head fence:
  any commit on the branch refuses, including unrelated ones. Fine before the agent exists (the branch is quiet), but a
  fence on a running chain branch refuses on every agent write; use it only for pass writes made while no graph holds
  the branch. (d) **`src/seon/cluster/agent.clj` uses it nowhere** (`write!` takes a vector); `merge-main!` reads the
  branch head, then writes `{:tx-data tx :parents #{main-commit}}` with no fence, so a commit between its read and
  write is unfenced today. P5b's "basis on the merge commit's tx-meta" should read: add
  `:datahike/expected-basis-t (db/basis-t head)` to that one map; no new writer check, no new Datahike code.
- A refused basis must be a *replan*, not a failure: `apply-actions!` rethrows with `:seon.flow.actions/action`, and
  the boundary "can end its assignment" on it (comment at agent.clj ~2437). A stale-basis refusal must not reach
  `failed-pass!`. State where it is absorbed (the action's applier answers `:replan`, the pass wakes itself) or the
  fence will bail tasks.

## 6. Open question 2 (root's ruling): forward-merge conflicts do not hold the launch; the agent resolves them in context

`seon.merge/merge-main!` already behaves this way: a declaration both sides changed stays the branch's and is
answered as `:seon.program/conflict` with its three digests, and the merge base becomes main's head. **Gap (C6):**
the answer is returned to the caller and recorded nowhere, and after the merge the base is lost, so the conflict cannot
be re-derived from the branch alone ("nothing records it" in the docstring). The agent's context therefore cannot show
it. For the ruling to hold, P5b must write the conflict set and digests as facts in the same merge commit (one
transaction on the branch), and a render of the agent's current-state block must read them. Else the conflict
vanishes at the first pass, silently.

## 7. The owner's questions (a) to (d)

- **(a) Pure function of desired and observed, actions as data?** `launch-actions` is pure and returns
  `:seon.flow.actions/op` maps (docstring "Pure", takes `facts` and `registry-facts`). It is not the whole
  reconcile: (1) `run-pass!` runs `advance-chains!` and `bail-chains!` (writes) before it reads facts
  (agent.clj ~2808); (2) `write-attempt!` and `write-chain-attempt!` re-read `db/db connection` and derive
  `attempt-tx` at apply time, so the applied write is not a function of the planned facts; (3) the plan's steps 1-4
  need observed state that `launch-facts`/`registry-facts` do not carry (routes, per-branch merge base, per-branch
  config difference, transitions). **Correction C7:** add those to the facts value, read once per pass and keyed by
  commit ids (as `chain-facts` is), and make each applier consume the action's planned values.
- **(b) Writes carry their planning basis?** No today; the seam exists (section 5). See C5.
- **(c) Non-write effects idempotent by identity, record written after?** Partly. `start-chain-attempt!` calls
  `acquire-context!` then `start-branch-flow!`; the live probe (claim 3) shows the state in which the first was done
  and the second not, and a pass plans `:start-flow` again, so the plan is idempotent by the agent id. The observed
  record is the registry (process-local), so a crash empties it and the next pass replans: correct. Route
  registration by key is idempotent only if re-listening with a key replaces; verify in `wake/route!`/`d/listen` (not
  probed, it would write a listener). The step table's in-memory transitions are new and must be read by
  `registry-facts`.
- **(d) Events only wake?** Yes: `wake/route!` offers to the plan channel with no payload (TASK-CHAIN 526 "carries no
  data") and `run-pass!` reads `@connection`. Confirmed.

## 8. Other corrections

- **C8. `:main-head` base is a moving target.** If step 3 stays a step while desired base is `:main-head`, every
  main commit makes a running chain "behind" and plans a merge into a branch whose agent is running. State that step 3
  applies only before the attempt row exists (prerequisite: no open attempt), or resolve `:main-head` to
  `[:main-at-least C]` once, at the pass that writes the attempt.
- **C9. The chain route needs the mailbox registry.** `start-branch-flow!` passes `(channels registry)` and
  `fenced-route?` for the attempt's registry so mailbox wakes route to the agent. A route registered per chain with no
  graph has no such registry. Say which registry it uses (the branch's child registry, which exists after
  `acquire-context!`) and how it follows a new attempt.
- **C10. A new namespace `seon.cluster.reconcile` beside `seon.reconcile`** (the config/entity reconcile, `.cljc`) is
  a naming collision in the vocabulary. Use `seon.cluster.plan` or extend `launch-actions`.
- **C11. Plan §0/§9 text is stale**: "attempts-made 3" is 4; `no-provider true` on the branch is not the branch's
  present state; "`bin/seon config apply ... --branch`" is no longer needed to unblock (the attempt row exists; the
  graph start is what is missing).

## 9. Phase order and HEAD boots

P2-P5c all edit `src/seon/cluster/agent.clj`; the order P1 → P2 → P3 → P4 → P5 → P5b → P5c → P6 loads at each step
**if** C4 is inside P2 (otherwise P2 boots but regresses wakes after an attempt ends) and the P5b rename (C10) is
chosen before P5 writes its schema keys (`seon.flow.actions.edn`). P3 refuses writes the existing `chain!` tests
make with minimal `:seon.chain/config`; with C2/C3 (dials taken from `effective`) those tests keep passing, with the
plan's "required every entry" they would not. P6 is too vague to be a loadable commit: give it its file list (section 3)
or fold each deletion into the phase that converts its last caller (AGENTS: a deletion is in the commit that converts
the last caller).

## Ready to build?

**Needs correction**, then P1/P2 can start. Minimum before filing tasks: C1-C3 (desired-config from `effective`,
14 decided dials, no Malli `:default`), C4 (P2 converts the two unlisten sites), C5 (fence is an int on the branch
head, absorbed as replan), C6 (record forward-merge conflicts durably), C8 (step 3 only before the attempt), and the
proof fixes in section 4 (P2, P3, P4-c, P5-b, P5b-b, P5c). The route-first part (P2) can be built as soon as C4 and C9
are folded in; it alone unblocks attempt 4's class of loss. Also for root now: this JVM sits with attempt
`e88599e46009` open, a context acquired and no graph started.
