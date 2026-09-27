---
type: design
status: revised after Astra review f4af60b0f (verdict REVISE); awaiting re-review; no source changed
created: 2026-09-27
branch: refactor/agent-platform
source-pin: f4af60b0f
---
# Config reads name their cluster — design (2026-09-27)

Lane `t4-b-config-scope-design-opus`. Defect: red-triage-4 platform item 1
(`docs/research/agent-platform/red-triage-4-2026-09-27.md:27-45`). Design only; review:
`docs/research/agent-platform/config-read-scope-review-2026-09-27.md` (f4af60b0f). Each review finding is answered where
its owning section now stands; §9 maps findings to sections.

**What exists.** `seon.config/effective db cluster-name` (config.clj:862-883) is the semantic owner of a cluster's
configuration. It pulls the row by the unique lookup ref `[:seon.config/cluster n]` (Datahike resolves it through
AVET, `db/utils.cljc:116-141`, so it does not scan clusters), fills required dials an older row lacks from their
declared defaults, and refuses a missing row. `seon.cluster.prompt/effective-ai-settings` (prompt.clj:48-72) already
composes an agent's turn settings: `effective` of the cluster, then `ai/agent-overlay`, then `ai/settings` (merge). It
takes its cluster name from the context's environment (prompt.clj:39-46). Several places select one row out of a
branch with `:find ?x .` and get an arbitrary cluster's row.

**Approach.** The owner's agent-centric principle (2026-09-27) says an agent's context is built from its record, and
namespace information is separate from agent information.

1. **Agent-scoped reads derive the cluster from the agent's record.** `cluster.agent/creation-tx` (agent.clj:217-241)
   already receives `:seon.cluster/name` in its required creation request (seon.agent.edn:84-89), but drops it. Every
   creation path passes it: host.clj:67, issue.clj:1384-1386, cluster.clj:3383 and 3459-3460. Record it as
   `:seon.agent/cluster`, a ref to `[:seon.cluster/name n]`. The agent-settings composition moves from prompt.clj into
   `seon.ai/agent-settings [db agent-id]` and reads the agent's cluster from the record. Signatures such as
   `(max-episode-runs db agent-id)` stay as they are, so there is no new positional slot, no ambiguity, and no caller
   closure. Transaction functions decide inside the writer from the agent id they already receive.
2. **Non-agent reads name their cluster explicitly** from the world the caller already holds: the environment, the
   service, the handle or the producer's value. **No supplied default is added** (finding 2).
3. **Deliberate aggregates and scoped agent overlays stay unchanged** (§1 B, C).

Probed on default: root's agent row carries `branch`, `id`, `namespace`, `plan`, `runtime` and `settings`, and no
cluster. `:seon.agent/branch` is custody: `:cluster-default` for root, `:<name>` for each MCP agent. There is no
membership fact today. The triage's phrase "the agent row's cluster reaches its `:seon.cluster/config`" describes the
state this design creates, not the current one.

## 1. Inventory, classified (src at f4af60b0f)

Search covered queries on `:seon.config/cluster` and `:seon.cluster/config`, every `:seon.config.*` clause (`?x` or
`_`), variable-attribute queries `[_ ?a ?n]`, AEVT datom reads (`d/datoms … :aevt`), and cluster-name selection with
`[_ :seon.config/cluster ?n]` or `[_ :seon.cluster/name ?n]`.

### A. Arbitrary-row selection: the defect

"Agent" in the last column means the cluster comes from the agent's record through `ai/agent-settings` (§2.1).

| # | Site | Read | Cluster source after the change |
|---|---|---|---|
| A1 | turn.clj:2887-2890 `max-episode-runs` | `run/max-episode-runs` (after issue budget and agent overlay) | agent |
| A2 | turn.clj:4418-4421 (`record-attempt!`) | `eval.result/blob-threshold`; `_` also matches overlays | the `:seon.ai/settings` the attempt request already carries, built by `provider-targets` at :4334 (§2.4). No new read |
| A3 | turn.clj:69-73 `result-blob-threshold` (for `stage-reply!` :85) | `eval.result/blob-threshold` | `stage-reply!` gains a `threshold` argument from its caller's held settings; the helper is deleted |
| A4 | repl.clj:70-73 `frame` | `run/max-episode-runs` | agent. The prompt caller already holds the settings (prompt.clj:406-407), so they are passed through the existing 3-arity |
| A5 | bootstrap.clj:130-134 `situation` | `run/max-episode-runs` | agent |
| A6 | issue.clj:1491-1492 `exhaust-tx` (tx fn) | `message/max-chain` | agent, inside the writer |
| A7 | cluster/message.clj:681-692 `send-call` (tx fn) | `message/max-chain`. **Refuses every message** when two clusters differ | the sender agent, inside the writer |
| A8 | plan.clj:646-649 `query-deadline` | `eval/time-limit-ms` via `[?cluster :seon.cluster/config ?config]` | agent (the overlay arm at :642-645 is kept inside `agent-settings`) |
| A9 | render/transcript.clj:1169-1170 `turn-kind` | `ai/no-provider`, on the historical opening db | agent, on the same opening db |
| A10 | agent.clj:131 `effective-settings`, :184 `render-settings-html` | cluster-name guess | agent |
| A11 | cluster/agent.clj:285 `whoami` | cluster-name guess | agent (the inspected agent's own record) |
| A12 | issue.clj:1338 `create-tx`, :1447 | cluster-name guess for a new worker and for `turn/next-id` | the parent agent (`:seon.issue/agent` → record); new in this revision |
| A13 | cluster.clj:3430 creation-result read | cluster-name guess | the created agent's record; new in this revision |
| A14 | blob.clj:66-80 `binary-threshold` (`stage-binary!` :243, `put-binary!` :346) | `eval.result/blob-threshold`, `_` | explicit `threshold` argument (§2.4) |
| A15 | test/runner.clj:1253, :1274-1276 `prepare-failures!` | blob threshold, `max-bytes` and `max-source` via `[_ ?a ?n]` | the producer's held effective map (§2.5) |
| A16 | render/web.clj:2027-2034 `coalesce-floor` (:2693) | `render/coalesce-ms`, `_` | `current-cluster-name service` (web.clj:3073-3078), already used at :3084 |
| A17 | sci/eval.clj:1319-1330 `database-effective-config` (:1340, :2762, :2800) | cluster-name guess | the ctx environment's `:seon.boot/cluster-name`, as `current-cluster-name` does; see §3 for the :2800-2807 fallback |
| A18 | problems.clj:363-372 `missing-models` | `ai/model` across all rows | explicit cluster in the `problems` request (§2.6) |

### B. Deliberate aggregate policies: unchanged, cited

| Site | Policy |
|---|---|
| db.clj:4563-4567 | Minimum of every asserted `:seon.config.db/validation-node-limit` (AEVT). The comment says every asserted policy on the branch applies to every writer. |
| db.clj:4819-4826 | Minimum of every asserted `:seon.config.db/write-time-limit-ms` (AEVT); same writer rule. |
| cluster/registry.clj:549-577 | Every head's snapshot-window policy decides implicit collection. |
| cluster/registry.clj:386 | Cluster names on a branch, used for roster membership. |
| config.clj:753-779 | `apply-compiled!` retention of every cluster's row. |
| config.clj:903-907 | `available` names listed in the missing-row refusal. |
| sci/eval.clj:214-228 | Branch cluster name: the environment first, else the branch's one cluster row, else a refusal naming all of them. It never picks one arbitrarily. |

These are deterministic over all rows. Changing their semantics is out of scope.

### C. Scoped agent overlays: legitimate, kept

turn.clj:2882-2886 and plan.clj:642-645 read the named agent's `:seon.agent/settings` row. After the change they are
the overlay step inside `ai/agent-settings`, not separate queries.

### D. Reference selection, not a dial

turn.clj:4411-4416 (used at :4462) picks the entity that `:seon.ai.attempt/settings` references: the agent's settings
row, else any config row. `effective` drops identity fields (config.clj:899-900), so it cannot supply a reference.
Replace the fallback with the named lookup ref `[:seon.config/cluster (:seon.cluster/name cluster)]` from the
enclosing handle. The agent-settings preference stays. If the named row is absent, the attempt records the typed
missing-row refusal (§3); the effective map never goes into a ref.

## 2. The composition

### 2.1 Agent membership fact and the one agent-settings reader

- **Schema.** `:seon.agent/cluster` is a `:seon.db/ref` to the cluster row, declared on the agent entity as
  **optional** (accretion: agent rows written before it stay valid under later writes). One schema line in
  seon.agent.edn.
- **Producer.** `creation-tx` adds `:seon.agent/cluster [:seon.cluster/name cluster-name]` from the request's
  existing `:seon.cluster/name`. The cluster row must exist first. Production guarantees this:
  `ensure-cluster-entity!` runs before agent seeding (cluster.clj:3355-3364). An absent cluster row makes the lookup
  ref fail at the writer, which is the correct refusal.
- **Reader.** `seon.ai/agent-settings [db agent-id]` is the body of prompt.clj:48-72, moved and made public. It pulls
  `[{:seon.agent/cluster [:seon.cluster/name]}]`, then applies `config/effective` and `agent-overlay`. `seon.ai`
  gains a `seon.config` require; `seon.config` does not require `seon.ai`, so there is no cycle. prompt.clj then calls
  it and deletes its copy, and prompt's environment cluster read for settings goes with it.
  - Contract: `[:=> [:cat :seon.db/database-value :seon.agent/id] [:or :seon.config/effective :seon.config/error :seon.db/invalid-read-error :seon.schema/validation-refusal :seon.ai/agent-cluster-missing-error]]`.
  - The new named error `:seon.ai/agent-cluster-missing-error` extends `:seon.error/base` with the agent id and
    `:seon.error/expected :seon.agent/cluster`. It is returned when the record lacks the fact, for example an agent
    created before this change.
  - It replaces prompt's `missing-cluster-error`, which named the environment. prompt's `missing-config-error`
    annotation stays as it is.
- **Target selection stays explicit** (finding 2). No `:seon.call-preparation` row is added.
  `:seon.boot/cluster-name` keeps meaning a target in `compile-manifest`, `apply!`, `ensure-cluster!`,
  `reset-cluster!` and the other contracts Astra enumerated, and config_test.clj:506-521 stays green.
  - Agent-facing callers of agent-scoped reads already receive `:seon.agent/id` from the existing supplier, and the
    cluster follows from the record.
  - So no "calling world's cluster" schema or supplier is needed. If a later non-agent SCI read needs one, it gets
    its own declared identity then, priced with its consumers.

### 2.2 Per-site conversion

Each dial is `get` on the value `agent-settings` or `effective` returned, after the refusal branch in §3.

- **A1.** `max-episode-runs` still gives the issue budget precedence, then `(:seon.config.run/max-episode-runs settings)`.
  The dial is optional (seon.config.run.edn:1-7), so an absent dial stays nil and still defers opening, as today. A
  missing row or missing membership is a refusal, which is a different thing.
- **A6 and A7 (writer decisions).** Both derive inside the transaction function from the agent id they already
  receive, so signatures stay. A refusal is **thrown whole** as `ex-info` carrying the typed map; a map is never
  returned as tx-data, because Datahike splices returned data into the transaction (`db/transaction.cljc:1171-1172`).
  `send-call`'s distinct-count refusal is deleted: one agent has one cluster.
- **A12.** `create-tx` reads the parent agent's cluster from its record and passes the name into the worker's creation
  request, where it is recorded. A missing record throws the typed refusal through the existing `refuse!`.

### 2.3 Acquire once per operation (finding 6)

- **Prompt and frame (A4).** `cluster.prompt/prompt` computes settings once. It passes them to `repl/frame` through
  the 3-arity, which already exists for this (repl.clj:66-67). The 2-arity calls `agent-settings`.
- **Work derivation.** `next-agent-work` → `opening-deferred?` / `turns-left` → `max-episode-runs` reads settings once
  per derivation. Nothing else in that derivation reads config.
- **Settlement (A6 via turn → plan/settle-call → issue/settle-call → exhaust-tx; A8 inside plan settlement).** One read
  per transaction-function invocation, on the writer's db. Writer decisions stay in the writer.
- **Runner (A15).** One effective map covers both the threshold and the caps (§2.5).
- **Blob, effects and replies (A2, A3, A14).** Callers that already hold the threshold pass it:
  - effect.clj:506-519 `staged-result` already has `threshold`;
  - `record-attempt!` already carries `:seon.ai/settings`.
  No extra read is added there.
- **`provider-targets` (turn.clj:4334).** It is already scoped (the handle's name), but it composes
  `effective` plus the overlay by hand. It becomes `ai/agent-settings`, so an agent's turn settings have one
  composition.
- **SCI contract install (A17, per installed function, sci/eval.clj:1359).** The environment-named `effective` read
  replaces the guess one for one. It is no more work than today. Caching is out of scope (§5).
- **Transcript (A9).** One read per displayed turn on its historical opening db, as today. Only scoping changes.
- **Web (A16).** One read per render pass, as today.
- **Selective acquisition.** `effective` stays the one semantic reader: it applies required-dial accretion defaults,
  refuses a missing row, and handles projections (config.clj:891-916). If a measured hot path later warrants it,
  selective acquisition extends `seon.config` (for example an `effective` arity taking a dial selector), never a
  per-leaf pull.

### 2.4 Blob threshold (A2, A3, A14)

`blob/stage-binary!` and `blob/put-binary!` (blob.clj:243, :346) take `threshold` as a positional argument, and the
bespoke threshold refusal (blob.clj:74-80) is deleted.

| Caller | Where the threshold comes from |
|---|---|
| effect.clj:518 | the held `threshold` |
| shell/jvm.clj:139 | `effective` of the environment's cluster (the handler holds its environment) |
| web/jvm.clj:203 | same as shell/jvm.clj |
| turn.clj:85 `stage-reply!` | new `threshold` argument from its callers' held settings |
| test/seon/blob_test.clj | explicit value |

### 2.5 Test runner (A15; finding 7)

- **Which world's policy.** The storage policy that governs recording is the **execution cluster's**: the world the
  run was admitted under. `seon.test` already computes `effective` for it (test.clj:2250-2252) before selection.
- **How it reaches the runner.** The producer puts that map in the completion under the existing
  `:seon.config/effective` key (test.clj:1749 uses the same key), and `prepare-failures!` reads the threshold and caps
  from it. The completion schema adds `[:seon.config/effective :seon.config/effective]` as **required at the
  completion boundary**.
- **Absence.** A completion without it is refused by its contract. The stored `:seon.test.run/cluster` ref stays
  optional, preserving published-snapshot and sweep semantics (seon.test.run.edn:28, 43, 119).
- This slice includes src/seon/test.clj and the completion schema.

### 2.6 Problems (A18; finding 7)

- **Request.** `problems` declares `[:map [:seon.boot/cluster-name :seon.boot/cluster-name]]` for its request, which
  names the cluster being inspected: a target, so it is explicit, not supplied.
- **Callers.** Its callers pass it: cluster.clj:611 (the MCP status path holds `cluster-name`) and the requery form
  problems.clj:640, which names the cluster in its form.
- **`missing-models`.**
  - It reads the named cluster's `ai/model` through `effective`.
  - It reads the overlays of agents whose `:seon.agent/cluster` is that cluster.
  - Agents without the fact are reported under a separate `:seon.problems/unscoped-agents` count, never silently
    included or dropped. Membership is then proven for new agents, and the report says plainly where it is not.
- **Deferred agents.** `problems/deferred-agents` (problems.clj:274-287) keeps one derivation per agent through
  `deferred-triggers`, which is now scoped by record.

## 3. Error alternatives per changed producer (finding 5)

Each producer below branches on the refused value before any `get`, `merge`, `pos?` or `zero?`, and routes it through
its existing boundary. `R` names the `agent-settings` error union from §2.1.

| Producer | Contract after the change | Handling |
|---|---|---|
| `turn/max-episode-runs` | `[:or :nil :seon.config.run/max-episode-runs R]` (the declared `:seon.error/value` arm replaced by R's members) | returns R |
| `turn/turns-left` | `[:or :my.agent/turns-left R]` | returns R |
| `turn/opening-deferred?` | `[:or :boolean R]` | returns R; `next-agent-work` already returns an error it finds (turn.clj:3082-3087) |
| `turn/deferred-triggers` | `[:or [:vector …] R]` | **today a truthy error counts as deferral (turn.clj:2962); fix to return R.** Callers: problems.clj:280 surfaces it as that agent's typed unknown; turn.clj:5590 returns it |
| `repl/frame` | `[:or :string R]` | the prompt caller already branches on `effective-ai-settings` refusals |
| `bootstrap/situation` | adds R to its declared alternatives (bootstrap.clj:103-107) | returns R |
| `issue/exhaust-tx`, `message/send-call` | tx-data only | throw `ex-info` whole with the R map as data |
| `plan/query-deadline` | unchanged; its `refuse!` (plan.clj:650-660) carries R as the cause | throws |
| `transcript/turn-kind` | string | R is shown as the "unknown" kind with one diagnostic (a render failure falls back to the floor) |
| `agent/effective-settings`, `whoami` | add R | returned to the calling agent |
| `issue/status` (reads `turns-left`, issue.clj:1068) | adds R | returned |
| `sci.eval/database-effective-config` | `[:or :seon.config/effective :seon.config/error]` plus a `:seon.boot/cluster-name` refusal when no environment exists | see the next row |
| sci/eval.clj:2800-2807 | — | **the `config/defaults` substitution is removed.** A refused read joins `::acquisition-refusals` and is delivered through the existing acquisition-fault boundary, never replaced by shipped defaults |
| `blob/stage-binary!` | its declared alternatives minus the deleted threshold refusal | — |

## 4. Priced options

1. **Constraint: one cluster row per branch.** Readers refuse when a branch has more than one row. It costs about 5
   lines, but every fixture branch and forked cluster inherits rows, and retracting one is refused because
   `:seon.cluster/config` is a required ref (red-triage-4:208-212). Not viable.
2. **Explicit cluster name everywhere**, which the review asked for. Agent-scoped functions become
   `[database cluster-name agent-id]`.
   - Positional omission is ambiguous: `[d "root"]` gives candidates `[[1] [2]]` (finding 4).
   - The caller closure spans turn, plan, issue, problems, eval/drive and transcript, plus the tests Astra listed
     (finding 3).
   - `next-agent-work`'s request needs a declared required name.
   - It stores nothing, but gives up agent-call ergonomics: every cross-agent call must pass the name.
3. **Recommended: the agent-record membership fact plus explicit names for non-agent reads** (this design).
   - It follows the owner's 2026-09-27 principle and keeps every agent-scoped signature.
   - Transaction functions decide in the writer, and nothing positional is added.
   - Cost: one schema line, one line in `creation-tx`, the moved reader (net about 0), and test fixtures.
   - Sacrifice: agents created before this change refuse agent-scoped reads until they are recreated, so **RESET
     NEEDED** on default after the landing (root batches it; DB data is disposable).
   - Hand-built agent rows in tests (about 25 files contain `:seon.agent/namespace` literals) must move to the
     canonical `creation-tx`/`ensure-entity!` wherever they reach an agent-scoped read. The AGENTS test law already
     requires that.

## 5. Cost (measured, JVM mode, unarmed, default, read-only)

| Read | Lane (µs per call) | Astra (µs per call) |
|---|---|---|
| unscoped query | 60 | 54.2 |
| scoped query | 65 | 60.0 |
| one-dial pull | 16 | 17.3 |
| `effective` | 135 | 150.6 |

- The first `effective` on the current db value took 724 µs.
- On an `as-of` value, the first pull of any kind costs about 25 ms, in the pull path, not in `effective`.
- `effective` is not cached in `seon.cache`, and no cache is justified.
- These numbers are unarmed host observations. They are not a hot-path comparison between parent and change.
- `agent-settings` adds one pull through a ref, measured at about 16 µs for a one-attribute pull.

**Required before commit (per AGENTS hot-path rule):** armed timing on parent and change for:
- `next-agent-work` on a woken agent;
- one evaluation settlement through plan/issue settle-call;
- `transcript/turn-kind` over a displayed history;
- one SCI contract install.

## 6. Regression and proof (finding 8)

- **One class test.** `seon.config-scope-test/config-read-scope-test`. Clusters and agents are written through
  canonical writers (`ensure-cluster-entity!`, `creation-tx`, and `config/compile-manifest` desired rows). Two
  clusters on one branch have **different** `run/max-episode-runs`, `message/max-chain`, `eval.result/blob-threshold`,
  `eval/time-limit-ms`, `render/coalesce-ms` and `ai/no-provider`. It asserts:
  1. each agent's reads (A1, A4-A11) give its own cluster's value;
  2. real writer delivery: `send!` from an agent in each cluster delivers, with no refusal, and `exhaust-tx` delivers
     with its own chain bound;
  3. overlay precedence: an agent overlay with a **false** or zero value wins over the cluster (`false` is not
     absence);
  4. a missing cluster row refuses (typed), while an absent optional dial yields nil and deferral;
  5. an agent record without `:seon.agent/cluster` gives `agent-cluster-missing-error`;
  6. an old config row lacking a required dial gets its declared default through `agent-settings`;
  7. explicit cross-cluster selection: `config/effective db "other"` still reads the other cluster.
- **No source-text scan.** The earlier proposal is withdrawn: it would reject legitimate overlays and would miss AEVT
  reads.
- **Compatibility evidence comes from compiled arity plans** (`call-preparation/arm-plan`), not
  `supplied-map-entries`, which currently gives false negatives. `call_preparation.clj:605` passes `database` where
  `planned-arities` expects a projection (:585-590). That is a foreign defect for the call-preparation owner, not this
  lane. Because no supplier row is added, the check is that the arm plans of the contracts Astra's inventory lists
  are **unchanged**.
- **Existing tests whose assumptions change:**
  - agent-test `episode-cap-refusal-test`: the fixture creates cluster `cap-2026072814` and its agents canonically;
  - turn-test `generated-opening-preserves-one-provider-turn-budget`;
  - sci/supplied_database_test.clj:24-29 keeps its `[database agent-id]` shape (the arity is unchanged).

## 7. Slicing (each commit loads; B2 and B3 are file-disjoint from each other)

- **B1: the membership fact and the reader.**
  - Files: seon.agent.edn (attribute), cluster/agent.clj (`creation-tx`, `whoami` A11, and A13 if it moves there),
    ai.clj (`agent-settings`), cluster/prompt.clj (calls it, deletes `effective-ai-settings` and its environment
    read), agent.clj (A10), and the `:seon.ai/agent-cluster-missing-error` schema.
  - Tests: the prompt tests (cluster/prompt_test.clj:317, 489, 529) and agent_identity_test.
  - This slice must land first. Every later agent-scoped read depends on the fact.
- **B2: the turn and writer family, one commit.**
  - Source: turn.clj (A1, A2, A3 threshold argument, D, error unions for `max-episode-runs`, `turns-left`,
    `opening-deferred?` and `deferred-triggers`), repl.clj (A4), bootstrap.clj (A5), issue.clj (A6, A12, `status`
    union), plan.clj (A8), problems.clj (A18, the `deferred-agents` error branch, and the request schema
    seon.problems.edn:65), and cluster.clj (:611 problems request, A13).
  - Blob change in the same commit, because turn.clj's `stage-reply!` and effect.clj share it: blob.clj (A14),
    effect.clj, shell/jvm.clj, web/jvm.clj.
  - Tests and fixtures: every test naming `stage-reply!`, `stage-binary!`, `put-binary!` or `problems` with `{}`,
    plus the tests that hand-build agent rows and reach agent-scoped reads, found by one mechanical search of
    `src script test` including quoted symbols in data. Known members: agent_test, turn_test, turn_work_test.clj:725,
    run6_stall_test.clj:88, issue_settlement_test.clj:198, agent_situation_test.clj:36, blob_test,
    render/web_debug_test.clj:325.
  - turn.clj needs an ownership window. Take the §5 armed timings in this slice.
- **B3: messaging.** cluster/message.clj (A7). It uses no changed signature, so it is file-disjoint from B2 and can
  land after B1.
- **B4: runner.** test/runner.clj (A15), src/seon/test.clj (producer) and the completion schema. Disjoint from B2 and
  B3; after B1.
- **B5: views.** render/transcript.clj (A9) and render/web.clj (A16). Disjoint; after B1.
- **B6: SCI.** sci/eval.clj (A17 and removal of the :2800-2807 substitution). Disjoint; after B1.
- **Collisions.** None among B3-B6. B2 holds turn.clj, issue.clj, plan.clj, problems.clj, cluster.clj, blob.clj,
  effect.clj, shell/jvm.clj and web/jvm.clj. B5 does not touch transcript's `turns-left` call (transcript.clj:1240)
  beyond its error branch, which is its own file.
- **Size.** It deletes about 85 lines of ad-hoc reads, the prompt copy (about 25 lines, moved) and the blob threshold
  refusal. It adds one attribute, about 5 lines of error schema, the error branches, and threshold arguments. Net
  source should be negative, and B2's test churn is the main cost.

## 8. Open questions for re-review

- **Required-ref deletion semantics.** `:seon.agent/cluster` as a ref to the cluster row: does any writer retract a
  cluster row while agents reference it? A required ref would refuse that; an optional ref sweeps. This design
  declares it optional (accretion) and relies on the creation writer to always write it.
- **Agents homed on their own branches.** An MCP agent is admitted on both the cluster and its branch (host.clj:5-67).
  Both writes must carry the same `:seon.agent/cluster`. `home-call` already refuses a branch mismatch; should it
  refuse a cluster mismatch too?
- **Worker agents from issues (A12).** Should a worker inherit its parent's cluster (proposed), or the issue's?

## 9. Review findings → sections

| Finding | Where it is addressed |
|---|---|
| 1 (classify; datom aggregates; row 3 is a ref) | §1 A/B/C/D, the §6 source-scan withdrawal |
| 2 (supplier fills targets) | §2.1: no supplier; target selection explicit |
| 3 (B2 closure; B4 not disjoint; `put-binary!`) | §7, §2.4 |
| 4 (positional ambiguity) | §2.1, §4 option 2: no new positional slot |
| 5 (exact error alternatives; tx functions throw; SCI defaults fallback; optional dial) | §3, §2.2 |
| 6 (acquire once; reuse held values) | §2.3, §5 |
| 7 (problems cluster identity; runner custody) | §2.6, §2.5 |
| 8 (behavioral proof; `supplied-map-entries` false negative) | §6 |
