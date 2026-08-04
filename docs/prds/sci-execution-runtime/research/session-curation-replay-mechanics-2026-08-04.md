---
type: research
status: complete
tags: [research, agent, runtime, database]
---

# Session-curation replay mechanics (2026-08-04)

## Scope and required reads

This is a research and design report. It makes no production changes.

I read all three requested authorities end to end before investigating or
probing:

- `docs/prds/sci-execution-runtime/plan/bootstrap-vector-design-2026-08-01.md`;
- `src/seon/bootstrap.clj`; and
- `src/seon/bootstrap_drive.clj`.

The bootstrap design's central mechanical claim is still the right starting
point: a system-authored plan is an ordinary run whose plan is already frozen,
so `next-agent-work` selects `:resume` instead of `:call`
(`bootstrap-vector-design-2026-08-01.md:31-56`). The landed implementation now
does the creation-time work which that 2026-08-01 document still listed as a
gap (`bootstrap-vector-design-2026-08-01.md:72-84`).

## Dependency ledger

| Dependency or Seon owner | Selected revision | Source read for this report | Existing use that establishes the seam |
|---|---|---|---|
| Datahike | `574c5f0f0db9411d1982769f14512cb24ef719da` (`deps.edn:26-30`) | `reference-code/datahike/src/datahike/versioning.cljc:216-291,323-477`; `reference-code/datahike/src/datahike/writing.cljc:470-552` | `src/seon/cluster/registry.clj:131-198,253-284`; `src/seon/cluster/store.clj:374-405` |
| Konserve | `89795ae1b769aafd47adf4168e2393d7b4721bc2` (`deps.edn:31-37`) | The key-level branch publication is exercised through Datahike's maintained fork | `src/seon/cluster/registry.clj:123-141` reads branch and commit records through the held store |
| Proximum | `9846d3e79e1aee48474bc876d3d563d7137209c6` (`deps.edn:41-44`) | Branching is delegated by Datahike when secondary indices are present (`versioning.cljc:102-149,276-286`) | The replay design must continue to use `registry/branch!`, never copy only the primary database |
| Run transitions | repository `HEAD` during the probe | `src/seon/cluster/run.clj:250-537,690-818` | Bootstrap composes `open-tx`, `claim-tx`, and `plan-tx` (`src/seon/bootstrap.clj:254-308`) |
| Run execution | repository `HEAD` during the probe | `src/seon/cluster/work.clj:552-627`; `src/seon/cluster/loop.clj:1466-1742` | Every agent graph calls the same `cluster.loop/turn` owner (`src/seon/cluster/agent.clj:208-244`) |
| SCI acquisition and program installation | repository `HEAD` during the probe | `src/seon/sci/eval.clj:637-725,1312-1339,1570-1675` | The run loop commits then installs declarations from `db-after` (`src/seon/cluster/loop.clj:1612-1629`) |
| Grading in fact space | repository `HEAD` during the probe | `src/seon/eval/drive.clj:110-168,201-326`; `src/seon/bootstrap_drive.clj:120-181,321-374` | `run-episode!` forks the ending commit and `one-drive!` opens, grades, releases, and retires it |

The live work used the isolated operator root
`tmp/session-curation-replay-2026-08-04/operator` and only the named scratch
cluster `session-curation-replay`. It was published and booted with
`bin/seon --root tmp/session-curation-replay-2026-08-04/operator init` followed
by `bin/seon --root tmp/session-curation-replay-2026-08-04/operator start
session-curation-replay`; the default cluster was never selected. JVM probes
then selected that same root and cluster explicitly.

## Findings

### 1. What `seed-tx` does today, and why it is creation-only

`bootstrap/seed-tx` is pure transaction-data assembly. It resolves the shipped
bootstrap sources, adds the agent namespace's requires/refers, and concatenates
three transaction functions: open, claim, and plan
(`src/seon/bootstrap.clj:254-308`). Its run id is the deterministic
`bootstrap:<agent-id>` identity (`src/seon/bootstrap.clj:130-135`), and its
digest is tied to the cluster's shipped bootstrap-plan rows
(`src/seon/bootstrap.clj:247-252`). Those two choices are bootstrap policy, not
general replay mechanics.

The creation-only restriction is one caller, not a run-transition restriction.
`ensure-entity-call` returns `[]` as soon as the agent exists; only its absent
branch composes `cluster.agent/creation-tx` and `bootstrap/seed-tx`
(`src/seon/cluster.clj:1224-1248`). The underlying transitions already support
an existing agent:

- `open-call` resolves the supplied agent lookup ref and atomically asserts the
  run plus the agent's current-run pointer; it refuses an absent agent, a reused
  run id, or an already-busy agent (`src/seon/cluster/run.clj:250-286`);
- `claim-call` asserts process custody or performs dead-process takeover; a live
  holder cannot be stolen (`src/seon/cluster/run.clj:288-333`); and
- `plan-call` accepts the caller's ordered sources, derives the existing
  agent's namespace, creates ordered form components, and freezes the digest;
  the second planner is refused (`src/seon/cluster/run.clj:394-471`).

Datahike transaction functions see the database produced by earlier items in
the same transaction. Therefore the same existing composition can open, claim,
and plan atomically for an existing agent. No agent-creation transaction and no
bootstrap namespace row are needed. `plan-call` already supplies the default
namespace and upserts any explicitly attributed source namespaces
(`src/seon/cluster/run.clj:420-470`).

#### Every seam needed for a general system-authored plan

1. **A source-plan digest owner.** The normal model path hashes the actual
   ordered sources in private `cluster.loop/digest`
   (`src/seon/cluster/loop.clj:599-603`). `bootstrap/plan-digest` hashes shipped
   plan rows instead (`src/seon/bootstrap.clj:247-252`). General replay needs
   the source-vector digest lifted to a public run-level pure function; copying
   either private implementation would create two plan identities.
2. **A pure composition function.** Add one run-owned function which accepts a
   database value, existing agent lookup ref, fresh run id, process identity,
   instant, and caller-provided `:seon.cluster.reply/sources`, and returns the
   concatenated `open-tx` + `claim-tx` + `plan-tx`. The three existing
   transaction functions remain the only semantic owners.
3. **A system-side transaction caller.** It transacts that composition with
   `:seon.db/process` metadata, checks the returned error value, and captures
   the transaction report's `db-before` and `db-after` commit IDs. It must not
   call `ensure-entity!`, whose existing-agent behavior deliberately does
   nothing (`src/seon/cluster.clj:1250-1262`).
4. **A fresh run identity.** Receipt identity is `(pr-str [run-id ordinal])` and
   a receipt can exist only once forever (`src/seon/cluster/run.clj:484-537`).
   Reusing the original run id is therefore structurally wrong even on a
   branch intended for later fact adoption.
5. **A replay execution driver.** `work/next-agent-work` already returns
   `:resume` for a held planned run and `:close` after its forms are exhausted
   (`src/seon/cluster/work.clj:552-627`). A replay driver can repeatedly call
   that derivation and the public `cluster.loop/turn` until it returns no work.
   This reuses receipt, evaluation, program-row, session-image, disposition,
   and close semantics (`src/seon/cluster/loop.clj:1466-1742`).
6. **A fork-local SCI context.** `sci.eval/cluster-ctx` cold-acquires the exact
   program and session image from a supplied database value and branch
   connection (`src/seon/sci/eval.clj:1312-1339`). Replay must use that context,
   never the live cluster's mutable SCI context.
7. **The existing work launcher.** The fork-local loop handle may reuse the
   process's existing bounded work launcher; evaluation requests carry their
   own fork connection and context. Creating a second scheduler or agent graph
   is unnecessary.
8. **A branch lifecycle boundary.** Create through `registry/branch!`, open
   through `store/open-branch!`, then release the connection before the
   idempotent `registry/retire-branch!` cleanup
   (`src/seon/cluster/registry.clj:160-198,253-284`;
   `src/seon/cluster/store.clj:374-405`).
9. **A validation and adoption boundary.** Replay facts do not become main
   branch facts merely because the fork is clean. Result equivalence and the
   one main-branch adoption transaction are separate seams described below.
10. **An external-effect eligibility fence.** A branch isolates database
    writes, not filesystem, web, model, or other external effects. Replaying a
    plan which reaches a capability can repeat a real effect. The first design
    must accept only plans whose computed program-graph reachability is
    capability-free, or separately design recorded-effect substitution. This
    is correctness against duplicated effects, not a callability restriction.
    The loop already has a program-graph reachability predicate for this exact
    fact (`src/seon/cluster/loop.clj:343-369`); it should be lifted rather than
    reimplemented.

There is also a live-agent wake seam, but the replay driver should not use it.
The current routing listener wakes agents only for new agent identities,
messages, and effect responses (`src/seon/cluster/wake.clj:78-93,224-243`). A
transaction containing only run/form facts will not wake an already armed
agent. Adding a post-commit channel offer would create a crash window. The
fork-local replay is explicitly driven by its coordinator, so it needs no new
route and no new agent graph. If system-authored plans later become a general
live-cluster feature, their durable triggering fact must participate in the
existing routing derivation; that is a separate feature.

### 2. What the grading fork does

At episode completion, `eval.drive/run-episode!` reads the live connection's
ending database value, captures `db/commit-id`, and calls `grading-branch!`
(`src/seon/eval/drive.clj:297-306`). `grading-branch!` creates a deterministic
`:inspect-grade-<episode-id>` branch through `registry/branch!`, with the
ending commit UUID as `:seon.cluster.registry/from`
(`src/seon/eval/drive.clj:258-263`). `bootstrap-drive/one-drive!` then opens a
normal branch connection, evaluates the grading probes, releases the
connection, and retires the branch in `finally`
(`src/seon/bootstrap_drive.clj:321-374`).

The physical operation is copy-on-write branch publication, not a database
copy:

1. `registry/branch!` checks the store roster and delegates to Datahike's
   `branch!`; an already-created branch is idempotent, while an absent source
   commit is a refusal (`src/seon/cluster/registry.clj:160-198`).
2. Datahike resolves the immutable stored database value under the source
   commit, branches any secondary indices from that same root, writes the new
   branch head, and publishes the `:branches` roster last under the store's GC
   guard (`reference-code/datahike/src/datahike/versioning.cljc:237-291`).
3. Opening the branch creates one independently written Datahike connection;
   Seon refuses a second open connection to the same branch
   (`src/seon/cluster/store.clj:374-405`).
4. Retirement removes only the roster root. The bytes remain until GC, and
   descendants remain independently rooted
   (`src/seon/cluster/registry.clj:253-284`).

The maintained benchmark measured 16.7-17.9 ms and one additional blob
(about 1.6 KB) for a 5,000-row ancestor, with no row copy
(`docs/prds/sci-execution-runtime/research/b2-plan-2026-07-27.md:115-133`).
The two isolated-cluster probes for this report measured 37.7 ms and 37.3 ms.
They include Seon's registry call and current secondary-index state and confirm
the same constant-shape operation; they are not a replacement benchmark.

### 3. The exact replay base is the pre-open parent commit

“Opening commit” has two possible meanings which must not be conflated:

- the **post-open commit** is the transaction which asserted the run and the
  agent's current-run pointer; and
- the **opening basis** is that transaction report's `db-before`, whose commit
  is the post-open commit's parent.

The curated replay needs the second. A fresh curated run on the post-open
database collides with the existing agent current-run pointer. Reusing that
run instead would also reuse its run and receipt identities, making later
projection-level adoption impossible. The pre-open basis has the complete
agent history up to the original run and no original-run identity or pointer.

No new stored `opening-commit` attribute is required. The run identity datom
already carries the exact transaction which opened it, as the grading code's
`objective-run-ids` query demonstrates (`src/seon/eval/drive.clj:110-119`).
Datahike records one commit per transaction and exposes each stored database
value's commit and parents (`reference-code/datahike/src/datahike/versioning.cljc:216-235,446-477`). The derivation is:

1. query the run identity datom's transaction id;
2. walk the current branch's commit ancestry to the database value whose
   `:max-tx` equals that transaction id; and
3. select its single parent commit as the pre-open basis.

A run-opening commit should have exactly one parent; refuse ambiguity rather
than choose one. This lookup belongs beside `registry/branch-commit-id` in the
branch/commit owner (`src/seon/cluster/registry.clj:126-141`), not in the
transcript or curator.

The live probe derived post-open commit
`6a724cee-2d22-5888-befd-cf6ba51d938a` from opening transaction `536871003`,
then derived parent `6a724c6c-a12e-5ea8-ba2b-b7a2567783ed`. A branch from the
parent did not contain the original run. On that branch, one atomic
open+claim+plan for the existing agent committed, the ordinary loop produced a
`:resume` report followed by `:close`, the result was `42`, and the replay had
zero error receipts.

The existing grading seam can therefore fork the right commit unchanged.
What is new is only commit derivation plus the replay driver; grading today
evaluates held-out forms directly and does not create a run or receipts
(`src/seon/bootstrap_drive.clj:142-158`).

### 4. Adoption design A — projection-level supersession

#### What already exists

- The cluster branch remains the one append-only database history.
- Runs, ordered forms, receipts, program rows, and session-image rows are
  ordinary facts committed by the existing loop.
- Transcript history is already derived from run/form/receipt facts rather
  than stored prose (`src/seon/render/transcript.clj:367-384`).
- One transaction can atomically assert related facts and reject a stale
  decision through a transaction function.

#### What is new

1. A globally declared cardinality-one ref
   `:seon.cluster.run/supersedes` on the curated run, pointing to the original
   run. The current registry has no curation or supersession attribute
   (`resources/seon/schemas/seon.cluster.run.edn:1-43`).
2. An adoption transaction function which verifies: both runs exist and have
   the same agent; the curated run is closed and clean; it has no existing
   supersedes ref; the target is not already superseded by another adopted
   run; and adding the edge cannot form a cycle. It then commits the curated
   run, its forms and receipts, the supersedes ref, and any admitted program or
   session-image reconciliation atomically.
3. A fork-to-main fact projection. Raw branch entity ids cannot be copied.
   Rebuild transaction data from run/form/receipt identities and lookup refs.
   The fork is evidence; the main-branch transaction is the adoption.
4. Transcript effective-run derivation. Exclude a run with an adopted incoming
   supersedes edge and include the leaf curated run. To render it “in place,”
   derive ordering from the superseded root run's `opened-at`; do not lie by
   storing the original instant as the curated run's actual open time. Current
   ordering reads each entry's own run instant
   (`src/seon/render/transcript.clj:343-384`).
5. A current-state fence for declarations and session-image rows. The replay
   branch starts before the original run, while the cluster branch may have
   advanced. Adoption may exact-replace a program row only if the main
   branch's current row is still the original run's expected result. A later
   agent-authored redefinition is a conflict, not data to overwrite. Apply the
   same rule to `:seon.code.def` identities. The loop already computes and
   commits exact session rows (`src/seon/cluster/loop.clj:465-504`) and exact
   program replacement (`src/seon/cluster/run.clj:705-768`); adoption must
   reuse those owners rather than invent looser upserts.

The supersedes edge is not stored derived state. It is the durable human/agent
adoption decision. Which run to render, where to order it, and which history is
effective are projections derived from that fact.

#### Crash and recovery

- Before a replay branch exists: the main branch is unchanged.
- During replay: a crash may leave a deterministic curation branch and a
  dangling replay run. No main fact has changed. The coordinator may recover
  and inspect it, or retire it and begin a new capability-free replay.
- After validation but before adoption: the main branch is still unchanged;
  validation can be re-derived from the fork's facts.
- During adoption: Datahike commits the entire adoption transaction or none of
  it. There is no partial transcript switch.
- After adoption but before branch retirement: the main projection already
  uses the curated run; retirement is idempotent cleanup.

The proposal identity and deterministic branch name must be durable/queryable
enough for startup cleanup to distinguish an active proposal from an
unreachable leaked branch. No mutable status label is needed: proposal fact,
branch presence, closed replay run, and supersedes fact are the states.

#### Concurrent facts

Adoption transacts against the cluster branch's current head, so facts committed
by other agents after the original run survive untouched. The transaction
function's expected-row checks prevent a curated replay from silently replacing
a later definition or session value. Other agents' messages, runs, receipts,
config changes, and program rows remain in the branch history.

This is consistent with both append-only history and derive-don't-store: the
original evidence remains queryable, the adoption decision is one new fact,
and transcript replacement is computed.

### 5. Adoption design B — move the cluster branch head

#### What already exists

Datahike has `force-branch!`, including an expected-current-commit check and
read-back verification (`reference-code/datahike/src/datahike/versioning.cljc:323-444`). Seon uses that dangerous primitive only in the quiesced
`current-src` publication owner (`src/seon/cluster/source.clj:147-213,242-294`).

#### What would be new

- a live-cluster authority allowed to force a cluster branch;
- a cluster-wide pause/stop, branch connection release, head move, reconnect,
  SCI-context rebuild, graph rebuild, and resume protocol; and
- a merge policy for every fact committed after the replay base.

This is not what grading does. Grading creates and later retires a sibling
branch; it never changes the live branch head.

#### Crash and recovery

The head pointer update itself is durable and guarded, but all existing
connections become stale and must be released and reconnected; Datahike states
this explicitly (`reference-code/datahike/src/datahike/versioning.cljc:327-336`). A crash across the surrounding stop/reconnect sequence creates a
new cluster lifecycle protocol which does not exist today. Process-local SCI
and Flow state would have been built from the old head.

#### Concurrent facts

Moving the head to a fork made from the original run's opening basis makes all
later cluster-branch commits unreachable from that head. That includes other
agents' messages, receipts, session values, definitions, and config changes—not only
the original run. The expected-head option detects concurrent movement; it
does not merge it.

Datahike's `merge!` does not solve this automatically: the caller must supply
the data to merge; the function only records the parent commits and serializes
the provided transaction (`reference-code/datahike/src/datahike/versioning.cljc:688-702`). Reconstructing the desired fact delta with conflict fences is
exactly design A's adoption transaction, after which moving the branch head
adds no value.

Branch adoption is therefore a new destructive reset mechanism, conflicts with
the append-only cluster history, stores the effective-history choice in an
operational pointer rather than deriving it, and cannot preserve concurrent
agents without implementing A inside B.

### 6. Result-equivalence seams from grading

The grading work already contains the right fact-space decomposition:

- `objective-run-ids` finds the run ids and opening transactions from durable
  facts (`src/seon/eval/drive.clj:110-119`);
- `run-receipts` joins run, form, ordinal, source, result, error, error kind,
  and time, then orders receipts (`src/seon/eval/drive.clj:132-159`);
- `read-result` parses admitted EDN and normalizes a `:seon.print/face`
  (`src/seon/eval/drive.clj:121-130`);
- `completion-values` finds `:my.run/disposition :completed`, and
  `completed-result` extracts its `:my.run/result`
  (`src/seon/eval/drive.clj:161-168`); and
- `terminal-state` distinguishes completed, capped, stopped, and still-running
  episodes from facts plus `next-agent-work`
  (`src/seon/eval/drive.clj:211-244`).

Those helpers are private and the current grader is intentionally permissive.
Session curation needs one shared, stricter pure predicate over two database
values and run ids:

1. The original run must have exactly one completed disposition and be closed.
2. The curated run must be closed; have receipts for exactly the planned
   ordinals; have no `:seon.cluster.eval/error`, `:seon.error/kind`,
   `:seon.cluster.eval/interrupted-at`, or run error; and have exactly one
   completed disposition on its terminal executed ordinal.
3. Resolve each completion's faithful value. When `result-blob` is present,
   compare the full blob value, not the window stored in `result-edn`; the loop
   deliberately stores a bounded window plus the full blob for large results
   (`src/seon/cluster/loop.clj:535-556`). The current grading helper reads only
   `result-edn`, so blob resolution is a required strengthening.
4. Compare the two admitted `:my.run/result` values with Clojure `=`, not raw
   serialized text. Print faces are presentation and already have a named
   normalization seam. An unreadable value is a refusal, never “not equal.”
5. Return an ordinary evidence map naming every failed clause, the two terminal
   ordinals, and the normalized results. The adoption transaction consumes the
   already-validated identities and rechecks the main-branch fences; it does
   not trust prose from the curator.

This should lift, not duplicate, the grader's receipt/result functions. The
transcript has another strict EDN reader and print-face emitter
(`src/seon/render/transcript.clj:386-453`); result decoding needs one owner so
grading, curation, and rendering cannot disagree.

### 7. Durable definitions on replay

Durable declarations use identity upsert plus exact replacement, not insert
and conflict. A function's identity is `:seon.fn/sym`. During receipt
settlement, the transaction function pulls the existing declaration by that
identity and calls `program/exact-replacement-tx`; an absent declaration gets
one deterministic tempid (`src/seon/cluster/run.clj:705-768`). Exact replacement
retracts changed owned attributes and component trees, then asserts the desired
row on the same entity id (`src/seon/program.cljc:430-465`). After the terminal
transaction, `install-program-row!` resolves the committed row by identity and
refuses only if its committed source differs from the install request
(`src/seon/sci/eval.clj:637-703`).

The live probe first installed
`my.agents.curation-probe/replayed` on the cluster branch, forked that ending
commit, cold-acquired a fork-local SCI context, and executed another ordinary
run containing the identical contracted `defn`. The replay:

- completed two forms, then closed through the normal loop;
- produced zero error receipts;
- left exactly one entity with that `:seon.fn/sym`; and
- retained the same entity id (`14150`) before and after replay.

So the answer is **semantic idempotent upsert, not a uniqueness conflict**.
It is not necessarily a no-write optimization: the exact-replacement
transaction may still assert the desired row. A changed definition also
replaces the one row exactly, as the existing regression demonstrates
(`test/seon/program_test.clj:114-152`; `test/seon/cluster/turn_test.clj:1117-1148`).

The important conflict is later, at adoption into a main branch which may have
advanced. Identity upsert alone is too permissive there. Adoption must compare
the current declaration with the original run's expected post-state and refuse
to overwrite a later change.

### 8. Rendered-output findings

The isolated probe exposed two ugly diagnostic faces:

- `runtime_status` returned one dense single-line nested EDN value and replaced
  buffer names and the durable error fact with repeated bare
  `seon.sci.admit/elided` markers. The marker does not say what was elided or
  how to inspect it.
- Returning `ensure-entity!` projected a 2,027,506-byte transaction report,
  showed a partial `tx-data`/`tempids` map with bare elision markers, and then
  required a blob drill. For this operation, the useful face is the committed
  agent/run identities and commit ID, not the full database values and
  transaction datoms.

The later bounded replay evidence was readable, but its first projection also
silently omitted map entries behind `seon.sci.admit/elided`; `get_value` was
needed to recover `function-entity-count` and `same-function-eid?`. These are
render-quality defects in the MCP/debug projection, not replay failures.

## Recommended design

Adopt **A, projection-level supersession**. Do not move the live cluster branch
head.

### Named construction

1. **Proposal fact.** Commit a curation proposal which identifies the original
   run and carries the curator's ordered source vector/digest. Its identity
   deterministically names the replay branch and fresh curated run id.
2. **Opening-basis derivation.** In `seon.cluster.registry`, derive the original
   run's opening transaction, its post-open commit, and the single pre-open
   parent commit. Store no duplicate basis attribute.
3. **Replay branch.** Call the existing `registry/branch!` from that parent and
   `store/open-branch!`.
4. **Replay handle.** Build a fork-local `sci.eval/cluster-ctx`; reuse the
   process's bounded work launcher and loop dials in a fork-local loop handle.
5. **System-authored run.** Use one new pure run-level
   `open-claim-plan-tx` composition with the existing agent, fresh run id, and
   caller sources. Drive only through `work/next-agent-work` and
   `cluster.loop/turn` until no work remains. Add no replay evaluator, central
   loop, agent graph, or wake route.
6. **Eligibility and validation.** Refuse capability-reaching source plans in
   the first version. Apply the shared clean-replay/equivalent-result predicate,
   including full result blobs.
7. **Atomic adoption.** Rebuild identity-based transaction data for the curated
   run/forms/receipts plus any exact program/session rows. A transaction
   function checks same-agent, uniqueness/cycle, result evidence, and
   current-program/session fences, then asserts
   `:seon.cluster.run/supersedes` in the same commit.
8. **Derived transcript.** Resolve each supersession chain to its adopted leaf,
   render the leaf's forms and receipts at the root run's historical position,
   and keep both original and curated facts queryable.
9. **Cleanup.** Release and retire the replay branch. Startup reconciliation
   derives unfinished cleanup from proposal/adoption facts plus branch roster;
   no stored status machine is required.

### Required new mechanisms, kept narrow

- one source-vector digest function promoted from the current private loop
  helper;
- one pure open+claim+plan composition over the existing transition functions;
- one commit-at-transaction/pre-open-parent derivation in the branch owner;
- one coordinator which drives the existing loop on a fork-local handle;
- one shared clean/equivalent result predicate lifted from grading and extended
  for blobs;
- one run supersedes ref plus its atomic adoption transition; and
- one transcript effective-run projection.

Everything else—branch creation, branch connection, SCI acquisition, work
submission, form reduction, receipt settlement, exact declaration replacement,
session-image production, close semantics, and branch retirement—already
exists and was exercised in the isolated probe.
