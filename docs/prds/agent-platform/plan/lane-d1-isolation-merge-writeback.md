---
type: plan
status: implementation specification; proof gates explicit
created: 2026-09-21
lane: D1
depends-on: [A1, A2, B1, B2, B3, B4]
tags: [agent-platform, isolation, merge, write-back, datahike-branch, sci-fork]
---

# Lane D1 — candidate isolation, explicit merge and write-back

Implementation ownership: net-definition comparison in `src/seon/program.cljc`,
merge/write-back composition in `src/seon/cluster/source.clj`, candidate request
facts in `resources/seon/schemas/seon.cluster.edn`, and regressions in
`test/seon/program_test.clj` / `test/seon/cluster/merge_test.clj`. B1 owns canonical
identity, source analysis/publication and MCP repairs; B2 owns acquisition,
arming/wake/turn lifecycle; B3 owns task writers and remaining previously unowned
code; B4 owns the one execution authority. Shared edits land as paired slices.
This is a specification with dated measurements; no fresh runtime proof is claimed.

## 0. For the owner: compose the isolation and writers we already have

Agents currently edit one shared program graph. Opening-time comparison tries to
prevent conflicting writes, and definition-time candidate evaluation runs tests
before an agent can keep an experimental definition (`turn.clj:1162-1180,
1301-1315,3253`; `sci/eval.clj:3170-3400`). That puts the acceptance gate inside
ordinary thinking. Accepted database definitions also lack a complete reverse
path to source files. Three separate changed-definition derivations repeat work.

Datahike branches share immutable indexes; SCI contexts fork with copy-on-write
Vars; the database writer already checks a supplied basis atomically; the program
owner already replaces declarations by identity; and the edit owner already
splices source under digest preconditions. Compose these mechanisms. Do not
confuse their individual guarantees: a cheap branch is not complete cluster
startup, a distinct SCI env is not isolation from shared JVM roots, and an
immutable test database does not prove which callable actually ran.

A candidate executes only its assigned task on a cluster forked from one immutable
shared commit. An explicit merge request freezes candidate, basis and shared
commits, derives net definition changes, and validates the proposed combined
program on a scratch branch. B4 executes every required test against that exact
program, preserving its evidence. The shared writer accepts only if the tested
shared head is still current. Write-back stages the complete accepted file delta
in an isolated checkout, verifies it with B1's file analyzer and callable proof,
then integrates and publishes through the existing source owner.

## 1. Goals, costs and evidence boundary

Historical 2026-09-21 evidence at HEAD `209a6652a` is retained in §4. It does not
measure the new candidate lifecycle. Full startup includes recovery decisions,
configuration, context construction and graph admission; measure all of them.

| Operation | Historical evidence | Required result |
|---|---|---|
| Materialize immutable commit | 1.857583 ms | Release every materialized value; record complete candidate start separately |
| SCI fork | 0.021875 ms; distinct env | Prove retained old/new callable and contract isolation, including indirect JVM calls |
| Speculative one-datom transaction | 0.339875 ms, two datoms | Actual scratch and final writer validation, not speculative commit identity |
| Source-attribute history sketch | 0 identities in 21.441292 / 20.675792 ms; 10,500 in 1506.152958 ms | Net definition digest projection; measure examined work with fixed delta and growing retained history |
| Static gate for `seon.id/id` | 1,838 of 2,157 tests in 130.576958 ms | Required static reaching tests union explicit task tests; no promised reduction to tens |
| Function span census | 4,600 of 4,600 | Does not establish file-ref completeness or provenance after an agent replacement |
| Write-back | Zero observed overrides | Complete file/source/digest/callable round trip; no implementation proof yet |

Measure start, projection, validation, selection, execution, acceptance and export
separately. Price tests by selected work and declaration bounds. A slow history
join may inspect retained history rather than only returned rows; no O(delta)
claim is accepted without an index/visited-work probe. Suspicious costs require
algorithmic explanation; the specification does not authorize an operation over
the owner's time bound by calling it a gate.

## 2. Data flow

### 2a. Candidate custody and lifecycle

| Data/event | Existing owner and invariant | Work |
|---|---|---|
| Trigger | B3 `trigger-call` records the task; it does not launch an agent | Task identity lookup and transaction |
| Assignment/start | B3 `start-call` owns assignment and first-turn admission; coordinate B2 arming so the same work does not execute on shared and candidate branches | Assigned task and agent, not every inherited agent |
| Fork basis | Registry records the exact immutable shared commit on the candidate start request/row; required for candidates, not invented on ordinary roots | Branch head/roster plus recorded basis |
| Candidate environment | **Ruled 2026-09-21 (README §7 "Candidate shape"): a candidate is a branch plus a HANDLE hosted by its cluster's JVM, not a cluster; a cluster stays the unit of a shared program and agent population, and candidates merge back to its branch.** `graph-definition` (`cluster/agent.clj:469`) is a pure function of `(agent-id, handle)`, and the handle already carries `:seon.db/connection`, `:seon.sci.eval/ctx`, `:seon.env/environment`, `:seon.flow/executor` and `:seon.agent/context-state` (the `default` handle's keys, review note §5.1). A candidate handle = `registry/branch!` off the immutable commit (0.34 s, landed) + `open-branch!` + `(sci/fork base-ctx)` (0.007 ms) + `arm!`; faults commit on the fault's own scoped connection (`env/carry` stamps the environment on every proc). Cluster start additionally stands store, source base, config, recovery, prepl, web, search, error fanout and graphs (`cluster.clj:3187-3276`): measured fork 9,924 ms + start 15,725 ms per candidate; the primitives measured live on 2026-09-21: branch 74.65 ms, open 25.62 ms, SCI fork 0.021 ms (Codex's REPL verification note) | A pointer, a fork, one `arm!`; the composed proof still owed: on a scratch cluster, branch, build the handle, arm one agent, evaluate one form with one effect and one fault; facts, effect rows and faults only on the candidate branch, the shared page unchanged, recovery after a stop |
| Work | Ordinary evaluations and B4 requests use the candidate's own connection/context | Actual evaluations and selected tests |
| Address | Existing cluster-qualified address and message owner, with the shared task identifying the candidate | Branch-local message/wake/reply facts |
| Refusal | Keep candidate and its evidence available for repair | No discard on red |
| Acceptance/discard | Stop and release via existing lifecycle owners; retire only after source, run and history retention is proven | No whole-store collection on every merge; maintenance owns GC |

A branch inherits agents, open turns, schedules and profile facts. Ordinary boot
currently recovers turns and arms agents (`cluster.clj:2297-2341,3114-3119`;
`cluster/agent.clj:900-943`), so passing a different source commit alone is not
task-only isolation. Before the lifecycle slice lands, agree the B3 start/B2
arming transaction order and prove unrelated inherited work neither recovers nor
runs. Use current task facts at those owners; no parallel registry, namespace
roster or agent-level connection rebinding. Candidate first-turn admission is an
explicit request/lifecycle contract, not an accidental inherited open turn.

A candidate's environment retains its connection. Messages arriving after the
fork must reach that cluster's owner, and reply/history access must remain
explicit through retirement. Shared-only inbox forwarding would require a
separate cross-branch custody/read-evidence proof; copying messages is not the
design. Conversation tasks use the same task/turn owners with their reply done
condition and can run on shared without a candidate; do not require a detector
or test merely because implementation tasks have them.

### 2b. One net-definition projection

Inputs are an immutable basis commit B and a descendant database value C.
Verify ancestry; branch-local transaction numbers alone do not identify a common
basis. Bind B1's `:seon.program/definition-digest` attribute before the historical
identity join. Select assertions and retractions from `since(history)`, resolve
removed identities through history, deduplicate, and compare each identity's
basis/final digest before reading complete rows.

| Basis/final state | Result |
|---|---|
| Equal digest | Unchanged, including edit/revert or equal delete/recreate |
| Absent then present | Addition |
| Present then absent | Retraction |
| Different present digests | Replacement |
| Surviving row without required digest or unavailable basis | Named typed refusal |

B1 supplies the digest from exact source, qualified identity, normalized resolver
context and effective acquisition/test metadata. Resolver-only changes therefore
participate; namespace bindings and input obligations remain B1/B4 evidence.
No second source-only digest or literal list of source attributes appears here.

A declaration projection returns definitions, not test run/member facts. Preserve
B4 evidence by commit and run identity; do not replay recorder data as declaration
replacement. Reuse this projection for missed-publication or per-test-basis
selection only when their full namespace/file/input semantics match. Retain B1's
missed-publication fallback until that conversion is complete.

Normalize complete declaration values through the indexer's existing identity,
reference and component owner (`fn.clj:2992-3045`). `canonical-row` only selects
owned fields; it does not normalize numeric refs. No raw branch-local eid or
wildcard pull with a silent 1,000-member cut crosses branches. Use complete
declared selectors, lookup refs for existing targets, tempids for new identified
rows and owned nested components. Additions upsert, replacements use exact
replacement, deletions retract; final surviving callers still govern deletion.
Keep pure comparison in `program`; explicitly settle its database-read owner and
require graph before adding a `db` alias or copying pseudocode.

### 2c. Explicit merge through the tested head

Only explicit `my.task/merge!` requests acceptance. Green candidate settlement is
evidence, not shared acceptance or permission to auto-merge. A candidate-local
completion does not resolve the shared task; final shared acceptance does.

1. Capture fork basis **B**, candidate head **C**, and shared head **H** as
   immutable commits. Retain H's pre-application basis-t. Materialized values
   have bounded ownership and release on every exit.
2. Derive net candidate/shared changes relative to B. Conflict means both changed
   one identity away from B to different final digests, including deletion.
   Equal final content is not a conflict. Compose B3 trigger/start in one shared
   transaction to assign the existing root. Its structural conflict identity
   derives through `seon.id` from the subject, competing definitions and basis;
   store both source commits and a resolvable subject-local detector. The same
   conflict repeats idempotently; changed competing content is distinct work.
3. Fork scratch from immutable H. Apply the normalized proposal in one transaction
   through Seon's ordinary prepared write admission. Reuse schema compilation,
   materialization and final-report validation. Before tests, refuse malformed
   schemas, invalid final refs, unresolved surviving callers and incomplete merge
   contracts by identity. An untested working definition is allowed in the
   candidate; uncontracted is not synonymous with untested.
4. Derive obligations over the combined program and before/after dependencies:
   changed declarations, callers, references, namespace bindings and B1's schema
   contract closure. Keyword mentions are not that closure. Required tests are
   **current static reaching test identities union the task's explicit tests**.
   Each admitted function needs a reaching test; unknown dependency or missing
   coverage refuses by name. Observed reach remains diagnostic.
5. Call B4's one `seon.test/run` with named policy and those exact identities.
   Supply the immutable combined program/database/commit, matching projection
   and acquired context, and a separate explicit durable result connection.
   Fixtures and resolution must use the combined value, never implicitly
   `current-src`. Every required member must have positive executed/reused proof;
   excluded, unknown, missing, red or unfinished work refuses acceptance. Reuse
   retains the original tested basis and meets B4's complete evidence predicate.
6. Retain the exact tested scratch commit, run identity and recording authority.
   Acceptance uses the same prepared write admission as scratch, the immutable
   proposal and H's original basis-t. It settles the shared task only against
   accepted evidence. The Datahike merge request carries immutable parent commits,
   never mutable branch keywords.
7. If H moved, the writer refuses before mutation. Rebuild scratch from the new H,
   recompute conflicts/obligations and execute or reuse evidence valid for that
   new combined state within the existing request deadline. Never apply a smaller
   untested delta or retry forever.
8. Record the outcome for the candidate's next turn through the existing system
   evaluation/history owner. Release scratch connections and materialized values;
   retire storage only when accepted evidence remains queryable. Refusal preserves
   the working candidate. Root's conflict repair uses this same explicit gate.

**One writer guard and validator.** Current Datahike `merge!` does not expose an
expected-basis argument and its writer calls `core/with` directly
(`versioning.cljc:734-748`; `writing.cljc:860-889`). Extend the existing public
merge request and route it through the existing transaction operation before
adding parents. Carry Seon's prepared final-report validator too; direct
Datahike merge does not install it. Include public API, callback, stale-basis,
refusal and parent-lineage regressions in the maintained-fork slice. This is not
a copied four-line guard. Ordinary guarded `transact!` already suffices for atomic
content acceptance if the owner explicitly gives up multi-parent lineage.

**Evidence retention is a gate before cleanup.** Name the exact parent graph and
recording authority in the implementation contract: accepted shared M has prior
shared H plus immutable candidate C and tested scratch S as parents, or retains
S through B4's equivalent existing durable result lineage. If B4 records elsewhere,
retain its tested S reference and actual run/member authority explicitly. Query
run/member facts at that authority after scratch release/retirement; definition
replacement does not copy them. Datahike GC follows parents subject to its cutoff,
so ancestry is not eternal retention. Agree the existing retention lifetime and
verify it before automatic retirement; no new acceptance entity or result replay.

### 2d. Write-back of the accepted delta

Write-back consumes accepted definition changes at M: functions, tests, schemas,
new namespaces, additions and deletions. `overrides` is a useful function-scoped
post-publication check, not the export inventory (`program.cljc:43-77`).

1. Resolve original file/span provenance through existing declaration/history
   reads (`effect.clj:278-306`). An agent replacement may omit current file/span
   facts. A genuinely new declaration supplies destination and insertion position;
   ambiguous namespace-to-file mapping refuses with identity and candidate paths.
2. The source owner creates isolated staging at the expected source commit.
   Read each touched file once; group edits against original bytes; apply existing
   lossless splices in descending half-open UTF-8 span order. Deletions remove old
   forms, additions have actual positions, and new-file absence is expressed
   under `:my.fs/precondition`. Stage the complete file set before analysis/loading.
   An implementation lane does not create an ad hoc worktree to evade ownership;
   this is the explicitly owned export operation's isolated-checkout contract.
3. Run B1's ordinary file publication analysis with captured staged bytes and its
   namespace/alias/refer/import/macro, reader, dependency and configuration context.
   Compare canonical definition digests for every accepted identity; verify intended
   absences, unchanged neighbors, valid callers and no unexpected declarations.
   Compare exact stored source bytes separately. `analyze-forms` with a synthetic
   prelude is not an equivalent file oracle.
4. Require complete merge evidence, staged round trip and proof the proposed
   callable loads and executes. A typed SCI-unloadable JVM fallback cannot certify
   the changed body. Use B4's existing isolated execution host for that proof,
   retaining its one admission/resolution/recording authority; no D1 runner.
5. Commit the complete loadable file change path-limited in staging, naming task
   and M. Integrate through B1's source owner only against the expected checkout
   state, then publish/adopt the complete set. Its publication monitor alone does
   not serialize filesystem editors. Never expose partially written multi-file
   source to a live reload; use existing digest-fenced edits/controlled integration.
6. Verify publication completion, actual loaded behavior and browser paint
   separately. No automatic push. Remove staging through its lifecycle owner only
   after retaining required evidence or a resumable commit.

Recovery derives desired staged bytes, current bytes, source publication and Git
commit state. Desired bytes already present require no write; unexpected bytes
refuse. A stale expected digest is not an unchanged result. Name the existing
commit awaiting integration/adoption and resume that operation without a second
completion registry. Refusal preserves accepted database definitions and produces
root's conflict task naming the affected file/identity. No `git reset` or restore
of unrelated edits.

### 2e. Outside agents take the same path

Owner (2026-09-23): "the goal is to switch over to SEON agents writing their own code
within the system and I want our agents to have a way to do that"; "make it easy for our
agents to use mcp tools or whatever to do things the way we want agents running inside it
to do things. Don't create some broken loading shit." The traced evidence, with forms,
values and timings, is in
[outside-agents-process](../../../research/agent-platform/outside-agents-process-2026-09-23.md).

**What already exists.** An inside agent writes code by replying source.
`seon.cluster.agent/submit-source!` (`cluster/agent.clj:606`) accepts system-authored
source and sends it through the path a model reply takes: parse, `sci.eval/evaluate`
(`sci/eval.clj:3192`) whose `definition-row` (`:426`) analyzes the form with the file
indexer, then `settle-batch!` commits the rows on the agent's custody connection, then
`install-evaluated-rows!` (`:1218`) installs them. **The smallest composition:** an
outside agent (a Claude Code or Codex lane) is an ordinary agent row on its own branch.
Its replies arrive through MCP instead of a provider. MCP is a door onto that one
entrance. It has no evaluator of its own, no persistence and no loader.

**How an agent writes code.** Evaluating a contracted `defn`, `deftest`, schema
declaration or `ns` form in its context produces one program row, analyzed by the same
indexer the files use. When the turn settles, the row commits on the agent's branch, and
the evaluated root is installed into the context. From then on every agent on that branch
can call it: live agents see it at their next evaluation; an isolated agent's writes stay
unseen until merge. A `defn` without `:malli/schema` is refused and never becomes a row.
`my.program/ns-unmap!` (`src/my/program.clj:555`) retracts a declaration and unmaps it in
the fork and the base. It is refused while `my.program/breaks` names surviving callers.
`my.test/check` runs the tests that reach a change, each on its own branch off the agent's
commit. The complete per-form catalog (what each form writes, loads and refuses) and the
ranked API gaps are in
[agent-code-writing-api](../../../research/agent-platform/agent-code-writing-api-2026-09-23.md).

| step | the outside agent does | the one function (installed / **gap**) | proportional to |
|---|---|---|---|
| identity | `seon agent` MCP tool: create once per lane, or reuse by name | existing agent creation (`creation-tx`, `agent.clj:185`) with `:seon.agent/branch` isolated off the cluster head; `registry/branch!` 28–30 ms | O(1): a pointer and one agent transaction |
| custody | nothing further: every later call names the agent | `acquire-context!` keyed `[branch agent]` in `:seon.agent/context-state` and retained across calls (**gap**: the MCP door releases the handle per call, `mcp.clj:528`) | unchanged head: a commit-id compare; changed: the overridden closure, not the program |
| read | `my.program/callers`, `tests-reaching`, `history`, `breaks` and the row's `:seon.fn/source` on its branch; files only for code it has not changed | existing `my.program/*` (**gap**: a `source` read of a row) | the rows read |
| write | submit forms: `(defn … {:malli/schema …} …)`, `deftest`, schema declarations, `ns-unmap!` | `submit-source!` through MCP `submit`; the answer is each evaluation's shown text once the run's `:seon.turn/closed-tx` is observed (`d/listen`, the exact terminal event) | forms × (analysis of the one form + settle + install of changed rows and affected callers) |
| feedback | contract refusal at definition; `(my.test/check {:seon.test/changed ['ns/f]})` | existing contracts and `seon.test/run`; the over-one-second directive rides every answer (C1) | reached closure plus selected bodies (reach p50 24 tests) |
| merge | `(my.task/merge! …)` requests acceptance of its branch | `prepare-merge!` / `accept-merge!` (`source.clj:741,791`) as the explicit request (**gap**: additions and deletions refuse at `:767`; the accepter is an owner JVM call) | commits since fork + changed identities + the gate run |
| conflict | reads the three-way value; repairs on its branch; requests again | `program/three-way`; conflicts become root tasks (§2c.2) | changed identities |
| files and git | nothing: the write-back operation commits | §2d on the accepted delta (**gap**: slice 7) | touched files' bytes + changed declarations |

**The door, exactly.** MCP keeps three tools. `eval_clj` in JVM mode stays as it is, for
host inspection and bounded probes (B1 §3b). `submit` is new, about 30 lines: agent name
plus source text → `submit-source!` → shown text per evaluation. The agent tool is about
20 lines: create, list or retire, mapped to existing agent creation and `release-context!`.
Lane branch-repl's MCP branch create/list/retire becomes this agent tool, since a branch
without an agent has no custody to evaluate under. The SCI mode of `eval_clj` that
evaluates without settling (`mcp.clj:482-528`) is deleted: its `defn` persists nothing and
disappears when the call ends (break B3). A submitted run closes the way a model reply
does (`seon.run/complete`). An outside agent's configuration names no provider, so a
continuation that would generate refuses loudly and never pays silently. That behavior
is proven, not assumed, in slice O1.

**What stays on files, and how it joins the same gate.** Four things stay on files:
host-bound declarations, which are 830 of 4,820 functions spread over 159 namespaces and
are a computed fact; schema resources, whose 3,373 rows have no `:seon.schema/file`
destination yet (B1 §2a′ S6); new namespaces until the namespace→path rule exists; and
non-Clojure files. The agent edits the file, then publishes the named paths onto **its
own branch** (`publish!` to a held candidate, one-lifecycle commit 3), never onto
default's. The resulting rows merge through the same `prepare-merge!` / `accept-merge!`.
For a file-originated delta, write-back finds the file bytes already equal to the rows,
so export is a digest comparison followed by the commit. Host-bound rows cannot run
interpreted, so their gate runs compiled after the reload on default. That is
`adopt-then-test!` (`cluster.clj:2728`), with `git revert` of the write-back commit on red.
This is a weaker guarantee than the interpreted gate and is named as such (decision
O-d below).

**Coordination without file holds.** A branch replaces a ledger row for program work.
Two lanes may change one file on different branches; conflict is per declaration identity
at merge (`three-way`), which is finer than a file. Write-back groups each accepted delta
by file against its original bytes under a digest precondition, and the writer's expected
head serializes accepts. The orchestrator's view is a query, never a file: open agent
branches × `program/changed-identities` since each branch's base. The ledger keeps only
file-path work (above) and non-program files.

**Staging without a worktree.** §2d step 2's "isolated staging at the expected source
commit" is captured bytes, not a checkout (owner 2026-09-23: no worktrees). Read each
touched file once (`capture-paths`), splice in memory in descending span order, run B1's
analysis on those bytes, then write each file with `my.fs/write!` and its
`:my.fs/precondition` expected digest. A lane's uncommitted edit to the same file fails
the precondition and becomes a conflict task. Last, commit with `git commit --only --`
the touched paths. The commit is made by the write-back operation; its trailer names the
lane agent, the task and the merge commit M.

**Costs today and targets.** Branch 28–30 ms. Acquisition is refused today and costs
1.3–1.7 s, proportional to the whole program: two digest maps over 4,820 rows plus a
980 ms reverse closure of 275 overridden rows (breaks B1, B2 and B4). The target is 0 for
an unchanged head and O(changed closure) otherwise. A `defn` evaluates in 77 ms; settling
costs 0.7–0.8 s and installing 0.7 s. The install check still scans the store for a
defining batch (5.6 s on default, break B6); the target is O(changed identities). Prepare
measured 3.8 s and accept 1.2 s (nsa slice 4). The target for a leaf change is under
1 s, and the run dominates.

**Braids removed.** (1) The loaded code no longer follows other lanes' disk bytes: an
agent's change is rows on its own branch, and files change only through write-back of
accepted bytes. That removes B1 §2a′ braid A and the 275-row lead observed. (2)
Evaluation, persistence and context lifetime in the MCP-only SCI path become one entrance.
(3) File ownership stops standing in for concurrency; identity conflicts replace it. (4)
The acceptance gate moves out of ordinary thinking into an explicit merge (slice 6). (5)
The adoption token no longer serializes publication; the writer's expected-head guard
does. **Braid added:** an MCP call names an agent. Its one role is custody. It fails
alone: an unknown agent refuses by name.

**Transition, ordered.** Until O1 lands, lanes edit files directly, and the hook keeps
syntax lint while save-time publication and testing stay off (owner 2026-09-23).

| slice | change (≤ ~100 added src lines) | maps to | deletes |
|---|---|---|---|
| O0 | `:as-alias` is not a require (`fn.clj:258`) | issue `as-alias-recorded-as-a-require-refuses-interpreted-acquisition` | the false cycle that refuses every interpreted acquisition |
| O1 | MCP `submit` and the agent tool over `submit-source!` and agent creation; the handle retained by `[branch agent]`; proof: a lane agent's `defn` persists on its branch, survives the next call, and a no-provider continuation refuses | B1 §3b (R3 bridge) + D1 §2a (lifecycle) | the commit-less SCI form (`mcp.clj:482-528`) and its branch plumbing (`:552-580`); branch-repl's bare-branch tools |
| O2 | the install check keyed by the batch's changed identities | B2 / A2 c1 | the O(store) since-scan in `installation-covers-program-change?` |
| O3 | merge admits additions and deletions (final surviving callers refuse); the issue is optional and the reaching tests required; a named accepter (root) | D1 slice 5 | `merge-base` (→ the fork's common-ancestor function); accept's evidence block → `seon.test` |
| O4 | write-back of the accepted delta: captured-byte staging, B1 analysis, digest-fenced writes, path-limited commit, then `init --dev --changed` of exactly those paths | D1 slice 7 (§2d) | `verify-development-sources!` and its calls (loaded bytes are the exported bytes); the adoption token for program work |
| O5 | file-path work publishes onto the lane's own branch and merges through O3 | one-lifecycle commit 3; B1 §2a′ S6 | save-time publication into default (`save-gate!` candidate `:cluster-default-candidate` for lanes) |
| O6 | the ledger becomes the branch query, kept only for non-program files | orchestrator practice | ledger rows for `src/`/`test/` Clojure |
| O7 | tool descriptions and the REPL skill (below) | B1 R8 | the skill's `eval_clj` SCI instructions |
| — | retire the definition-time gate | D1 slice 6 (independent) | ≈ 300 lines in `turn.clj`/`sci/eval.clj` |

O0 first. O1 and O2 are file-disjoint and can run together. O3 then O4 make the loop close
through git. O5 and O6 follow O4. O7 lands with O1 and is revised at O4.

**What the tools and skill say.** `submit`: "Submit source as your agent's reply on its
branch. Each form is evaluated, contracted definitions become program rows on your
branch, and you get each evaluation's shown text. Nothing is written to files; files
change only when an accepted merge is written back." `agent`: "Create or reuse your agent;
it owns an isolated branch off the cluster head; every `submit` names it." `eval_clj`:
"JVM inspection of the host; it defines nothing in any cluster's program." The REPL skill
states the workflow: read rows, submit definitions, `my.test/check`, request merge, read
the conflict, and never `init --dev` for program work after O4. Every claim cites its
`file:line`.

**Decisions for the owner (recommendation first).**

- **O-a identity.** (1, recommended) One ordinary agent row per lane on an isolated
  branch, reached through `submit`. Guarantees the inside path exactly. Costs about
  50 lines. Gives up bare branches without an agent. (2) Bare branches plus an MCP-side
  settle. Cheap to reach, but it is a second persistence path, which the owner's ruling
  rejects. (3) All lanes live on one shared candidate branch of default (the AGENTS
  wording). No merge per lane, but lanes see each other's half-finished work, and
  conflicts surface only at write-back.
- **O-b accepter.** (1, recommended) The orchestrator accepts as root through the named
  accept, and the owner accepts by exception. Latency is minutes; per-change human
  review is given up. (2) The owner accepts every merge. Human review of every change,
  at owner latency. (3) Automatic acceptance on a green gate. Fastest, but it gives up
  the ruled explicit merge; rejected by §2c.
- **O-c commit.** (1, recommended, as in §8) The write-back operation commits
  path-limited right after acceptance, complete evidence and the isolated analysis. Costs
  the changed files plus selected work. (2) The same, plus the platform tier per export.
  Broader proof, but a separate JVM per export, tens of seconds. (3) Plus human approval.
  Owner latency.
- **O-d host-bound.** (1, recommended) File edit → the agent's own branch → accept →
  write-back → compiled gate after reload, reverted on red. Honest, weaker guarantee:
  red is caught after the files change. (2) A platform-tier fresh JVM gate per
  host-bound merge. Stronger, but tens of seconds each and a second host. (3) Host-bound
  changes are orchestrator-only. Simplest, but it gives up 17% of functions to outside
  agents.

## 3. Reading list

| Source | Guarantee and limitation |
|---|---|
| `reference-code/datahike/src/datahike/versioning.cljc:212-321,457-490` | Branch/roster, active-connection deletion refusal, immutable materialization and release |
| `reference-code/datahike/src/datahike/versioning.cljc:734-748`; `writer.cljc:421-437`; `writing.cljc:860-889` | Merge supplies lineage, caller supplies content; only transaction operation currently owns expected-basis guard |
| `reference-code/datahike/src/datahike/gc.cljc:22-81` | Parent traversal and cutoff; lineage alone is not permanent retention |
| `reference-code/datahike/src/datahike/db.cljc:149,180-185,678`; `pull_api.cljc:16,315,323` | Temporal filters and many-pull limit; returned rows do not establish work visited/completeness |
| `reference-code/sci/src/sci/core.cljc:260-276,345-350`; `impl/utils.cljc:362-379` | Fork env and copy-on-write inherited Vars; does not isolate shared JVM roots |
| `src/seon/program.cljc:43-77,134-177,211-273,848-886,973-1047`; `src/seon/fn.clj:2992-3045` | Ownership projection, replacement and separate reference normalization |
| `src/seon/db.clj:4050-4121,4290-4319`; `reference-code/datahike/src/datahike/db/transaction.cljc:1206-1226` | Prepared final-report validation and dependency callback |
| `src/seon/cluster/registry.clj:178-282,327-358`; `src/seon/cluster.clj:1660,3216,3286`; `src/seon/env.clj:330-370` | Exact fork/start input and scoped environment; startup must select intended work |
| `src/seon/turn.clj:1162-1180,1301-1315,3253-3330`; `src/seon/sci/eval.clj:3170-3400` | Existing definition-time gate; retain writer conflict checking through retirement |
| `src/seon/fn.clj:207,600-680,951-1014,1176-1213,1520,1779`; `src/seon/fn/analyzer.clj:657-698` | Spans, agent provenance differences, synthetic prelude and graph selection |
| `src/seon/edit.clj:242,254-316,344`; `src/seon/edit/jvm.clj:95-122`; `src/seon/fs/jvm.clj:593-712`; `src/my/fs.clj:57` | Lossless splice, stale-source refusal, nested preconditions and per-file atomic write |
| `src/seon/effect.clj:278-306`; `test/seon/test_support.clj:946-981`; `src/seon/test.clj:576-598` | Existing provenance lookup, bounded branch lifetime and execution seam B4 replaces in place |

## 4. Exact historical probe and implementation proof protocol

The following read-only JVM evaluation ran on `default`, root
`/Users/sean/src/seon`, with a 10,000 ms MCP timeout on 2026-09-21. It changes only
immutable speculative values and a fresh local SCI fork. An earlier six-position
history pattern failed with `Pattern mismatch` and supplied no measurements.
The corrected complete form and value follow; no fresh probe ran for this plan.

```clojure
(let [conn (seon.operator/connection "default") db (seon.db/db conn) t (seon.db/basis-t db) h (datahike.api/history db) timed (fn [f] (let [s (System/nanoTime) v (f)] [v (/ (- (System/nanoTime) s) 1e6)])) attrs [:seon.fn/source :seon.fn/spec :seon.fn/calls :seon.fn/references :seon.test/source :seon.ns/source :seon.schema/form] ids seon.program/identity-attributes instance (get @seon.operator.runtime/running-instances "default") ctx (get-in instance [:seon.turn.loop/cluster :seon.sci.eval/ctx]) cid (seon.cluster.registry/connection-branch-commit-id conn :current-src) material (timed #(datahike.api/commit-as-db conn cid)) src (first material)] (try {:head-t t :commit (datahike.api/commit-id db) :src-commit cid :src-t (:max-tx src) :commit-as-db-ms (second material) :fns (datahike.api/q '[:find (count ?e) . :where [?e :seon.fn/sym]] db) :spanned (datahike.api/q '[:find (count ?e) . :where [?e :seon.fn/sym] [?e :seon.fn/form-span]] db) :overrides (count (seon.program/overrides db)) :cluster-row (datahike.api/pull db [:seon.cluster/name :seon.source/commit-id] [:seon.cluster/name "default"]) :since (mapv (fn [n] [n (timed #(count (datahike.api/q '[:find ?ia ?iv :in $delta $history [?a ...] [?ia ...] :where [$delta ?e ?a _ _ _] [$history ?e ?ia ?iv]] (datahike.api/since h (- t n)) h attrs ids)))]) [1 10 100]) :with (timed #(let [r (datahike.api/with db [[:db/add [:seon.fn/sym 'seon.id/id] :seon.fn/doc "d1 read-only speculative probe"]])] {:datoms (count (:tx-data r)) :commit (datahike.api/commit-id (:db-after r))})) :fork (if ctx (timed #(let [f (sci.core/fork ctx)] {:different-env (not (identical? (:env f) (:env ctx)))})) :ctx-absent) :tests (datahike.api/q '[:find (count ?e) . :where [?e :seon.test/sym]] db) :reach (datahike.api/q '[:find (count ?e) . :where [?e :seon.test/reach]] db) :gate (timed #(count (seon.fn/gate-sets {:seon.db/db db :seon.fn/seeds #{'seon.id/id}})))} (finally (datahike.api/release-materialized-db src))))
```

Returned value (MCP JSON decoded to EDN; envelope `ms 1693`, `windowed? false`, cluster alive):

```clojure
{:fns 4600 :since [[1 [0 21.441292]] [10 [0 20.675792]] [100 [10500 1506.152958]]] :overrides 0 :src-commit "6ab0a852-4511-5f0e-8462-dcb611714979" :src-t 536870957 :commit-as-db-ms 1.857583 :tests 2157 :head-t 536870949 :reach nil :cluster-row {:seon.cluster/name "default" :seon.source/commit-id "6ab0a0b4-8927-5986-aedc-a3d20f36a036"} :with [{:commit "6ab15cfb-6177-50d4-9ccd-91ef300b2084" :datoms 2} 0.339875] :commit "6ab15cfb-6177-50d4-9ccd-91ef300b2084" :gate [1838 130.576958] :spanned 4600 :fork [{:different-env true} 0.021875]}
```

The speculative with result retains the parent's commit ID; it is not a committed candidate. The fork measurement proves a distinct env atom, not complete cluster isolation. Reach nil is absent recorded evidence, not a complete empty observation. Zero overrides supplies no write-back proof.

During implementation, record executable exact forms as each request contract
lands; do not paste future APIs with ellipses or unbound handles as proof. Use the
owner's fresh implementation baseline and canonical branch/context fixtures,
explicit MCP custody and one evaluation in flight. Mutating candidate scenarios
run on owned disposable resources, never by restarting `default`. The acceptance
scenarios in §8 are required outputs of those forms, not assertions that they
already pass. After each source slice, verify host `runtime_status`, the loaded
changed behavior and its adoption record; observe the candidate agent page.

## 5. Ordered implementation slices

| Slice | Change, prerequisites and proof |
|---|---|
| 1 | Agree B3 task/start/addressing, B2 task-only arming/recovery, B4 actual execution program/recording authority and evidence retention. Prove candidate-only failing body, indirect JVM call and unrelated inherited work before retiring any gate |
| 2 | Land B1 digest and complete-row normalization consumers, net projection and ancestry checks. Convert compatible publication/B4 derivations atomically; retain unmatched missed-publication semantics |
| 3 | Land candidate lifecycle and its required basis/request facts with all writers/readers. Mark incompatible persisted changes RESET NEEDED, batched with B1/B3/B4 by orchestrator |
| 4 | Maintained Datahike public merge request delegates guard/transaction work and retains validation callback; fork tests, push fork and bump gitlink before dependent Seon deletion |
| 5 | Compose explicit merge, full combined validation, B4 named obligations, immutable parent/evidence lineage, root conflicts and stale-head reconstruction. Prove red/unknown/unfinished never accepts |
| 6 | Retire definition-time gate and superseded helpers with every caller/schema/test in the same slice. Preserve B2's writer-owned conflict-basis check at exact replacement and any unsuperseded candidate evaluation |
| 7 | Land accepted-delta export, isolated complete-file analysis, callable proof, controlled integration and resumable adoption; round-trip regressions land together |
| 8 | Run §8 demonstration and record size/performance, REPL and browser evidence |

Before each commit, prove the touched require graph loads in the authorized
implementation harness; then use the running host's reload/adoption owner and
verify a debug read/ordinary turn. Graph topology changes use existing lifecycle
completion. No lane stops/resets/restarts `default`. Recovery reset is the
orchestrator's `bin/seon reset --force` (ruled 2026-09-23, README §7 "Schema change and reset": `reset --force` unlinks the cluster branch and forks a fresh one from the program rows, keeping every cache; `start --head` moves the JVM to committed HEAD keeping the store; `nuke --force` alone deletes the store, for a truly broken store), after preserving needed evidence: it
loses disposable database turns/results/tasks and private/result objects, rather
than “nothing durable.” Reset recovery is not proof of intermediate loadability.

## 6. Smaller mechanisms to probe first

| Candidate | Probe and decision |
|---|---|
| Guarded transaction vs multi-parent merge | Ordinary transaction proves atomic content acceptance; retain the fork API change only for required lineage, delegating its existing guard and validator |
| Net digest comparison before row expansion | Equal edit/revert, alias-only change and fixed delta under growing history; measure visited rows and complete normalization cost |
| One captured file set through analysis/loading | Multi-file refer/import/macro change, two splices in one file, unchanged neighbors and staged callable invocation; keep B1's analyzer rather than inventing a text-only oracle |
| Candidate rebasing instead of scratch | Would require pausing mutable candidate work and changes its repair state on refusal; use scratch unless existing lifecycle supplies the same isolation without a new pause protocol |

Numeric datom replay is not a simplification: two branches can allocate equal
numeric ids for different entities. Include that falsification in normalization
regressions; identity/ref/component conversion stays at its existing owner.

## 7. Tests and provisional size target

Preserve class coverage on canonical fixtures, real SCI and armed contracts:
net changes (alias-only, edit/revert, delete/recreate, >1,000 refs); task-only
startup; candidate callable isolation; combined schema/caller interactions;
missing/disconnected task tests; malformed/red/unfinished refusal; stale head;
conflict idempotence; retained run evidence; multi-file round trip and interrupted
export. Include symbolic ref normalization with colliding branch-local ids.

Delete tests only for retired definition-time gate behavior and superseded
changed-definition derivations; keep writer conflict, admission and candidate
isolation regressions at their surviving owners. B4's one `run` executes affected
members with default five-second bounds or a declared numeric allowance; no D1
runner or suite. The isolated hosting exception uses that same B4 authority.

Historical rough deletion inventory: approximately 300 source lines in the
turn/SCI definition-time gate, 30 in the source history arm, 25 in B4's duplicate
projection and 400 test lines. These spans overlap dependency cuts and must be
recounted after them; all retained behavior remains priced. Complete export,
lifecycle and evidence handling still need a disjoint addition ledger before
any net reduction is claimed.

Provisional target: replacement Seon source ≤355 lines, schema ≤40, maintained
fork API/guard composition ≤40, regression additions ≤300 test lines. These are
stretch caps, not measured floors or guarantees. Reconcile exact disjoint spans
with B1/B2/B4, charge moves once, and record `wc -l` plus per-commit additions and
deletions. If missing guarantees exceed the cap, report the existing owner that
can absorb work before adding machinery or claiming the target met.

## 8. Completion, decisions and stop rules

The landing note `docs/prds/agent-platform/landing/lane-d1.md` must retain:

1. Two actual task identities, one schema guarantee with reproducing example and
   regression, one function missing a reaching test; initial defect queries,
   evaluation source, agents, fork commits and carried projections.
2. Concurrent candidates with positive facts proving only assigned work ran,
   shared definitions unchanged before explicit acceptance and candidate calls
   observing candidate definitions. Include old/new contracts and reload elsewhere.
3. Exact required tests and executed/reused/excluded/unfinished verdicts, digests,
   tested commits and termination evidence. A candidate-only failing body must
   fail through both SCI and an indirect JVM caller before its repaired green.
   Include a shared schema/contract/caller change without same-identity conflict.
4. Disjoint explicit acceptances, then a shared-head move after green: typed
   stale-basis, zero partial acceptance and fresh combined proof before retry.
   Record actual parents and retrieve the run after scratch retirement.
5. Same-identity conflict routed once to existing root with both sources and B;
   identical repeat is idempotent, changed digest is not suppressed. Root's repair
   passes the conflict task's tests through the same explicit gate.
6. Malformed admission refuses before any gate execution; a separately untested
   candidate definition works locally but refuses by name at merge.
7. Both accepted deltas exported: original/staged file digests, exact source bytes,
   all affected identity digests, intended deletions, path-limited Git commit,
   publication/adoption commits, scoped `overrides` check and schema/test round trip.
   Include two edits in one file, new/deleted declaration, resolver change, stale
   source, symlink refusal and interrupted commit/integration/adoption recovery.
8. Trigger→task→agent→message→reply for conversation and candidate addressing,
   including a message after fork and during a turn and history after retirement.
   Observe page content; record startup/discard and each phase's cost, cleanup,
   host REPL reachability and remaining unknowns as failed proof obligations.

**Settled constraints:** explicit merge; all first-party namespaces may be
proposed; conservative static reach plus task tests; structural conflict task for
root; moved shared head requires a new combined proof. No merge-critical roster,
ten-merge approval counter, file exception or automatic settlement merge.

**Write-back policy remains an owner choice.** Recommend automatic path-limited
commit after explicit acceptance, complete required evidence, isolated file
analysis and callable proof (cost: changed files plus selected work; gives up
per-change human review). Alternatives are that same proof plus the platform
tier per export (more execution, broader boot proof) or plus human approval
(owner latency, human inspection). None substitutes old-root fallback green for
execution of the proposal.

**Addressability recommendation:** use the existing cluster-qualified candidate
address (branch-local custody, explicit cluster selection). Shared-only addressing
requires the existing message owner to prove cross-branch custody/read evidence
(additional integration work, simpler sender address); copying messages is rejected
because it gives up one authority. B2/B3 must settle that interface before startup.

Stop only the dependent slice at a held file, missing owner interface, unproven
candidate/combined-callable/retention guarantee, or genuine owner decision. Name
the exact boundary and three concrete options when needed. Preserve all unrelated
edits; do not operate another lane's session to pass a gate.
