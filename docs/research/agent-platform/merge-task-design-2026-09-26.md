---
type: design
lane: merge-task-design-opus
status: revised 2026-09-26 for the owner's option-B ruling and Astra review F1–F9 (review-merge-task-2026-09-26.md, 71e8f6b05); nothing implemented
created: 2026-09-26
head: 87068f5fc (default restarted during this revision; probes on pid 80197 and its successor)
authority: AGENTS.md merge law c03761415; total REPL path ee4044ff8; one error shape 1d32f9979; owner 2026-09-26 ("Root should have both envs and should have easy tools to move data and manipulate it")
---
# Merge as a root task: two held worlds and plain tools between them

## What exists and the approach

**What exists today.**
- **Proposal.** An agent proposes with a message: `my.program/propose-merge!` (`src/my/program.clj:443`).
  Root infers which proposals are pending from its messages plus the roster (`proposals` `:467`).
- **Preparation.** One call, `seon.cluster.source/prepare-merge!` (`src/seon/cluster/source.clj:840`),
  does the whole preparation:
  - it computes three-way B/C/H over functions, tests and namespaces;
  - it applies an optional selection;
  - it forks S from H;
  - it runs the reaching gate (`seon.cluster/candidate-gate!` `src/seon/cluster.clj:2758` → `reaching-run` `:2737`).
- **Acceptance.** `accept-merge!` (`source.clj:932`) checks that the named run's members are green. It then
  runs a coverage veto (`uncovered`, `:970-973`), checks for dangling tasks, and relies on the writer's
  per-row digest guard (`declarations-held-tx` `:817`).
- **Write-back.** `write-back!` (`:1192`) writes one file.

**The approach.** Take that call apart along the owner's ruling. **Merge is a root task**, recorded from the
agent's positive proof. Root **holds two worlds** as ordinary environments with ordinary REPLs:
- **S**, forked from the captured cluster head H0;
- **C′**, forked from the proposed commit C.

Root moves and reshapes values between the two worlds with **plain functions over database values**, and
chooses what to take by identity from a diff value. Acceptance is the writer's decision over three things:
- S's actual declaration delta from H0;
- current evidence for the task's tests plus root's recorded runs on S's program;
- a digest guard on every touched row.

No second evaluator, runner, registry, conflict engine or copier is added.

**Rulings this design implements (owner 2026-09-26; AGENTS.md c03761415).**
- **Option B.**
- **Propose gate.** The agent proposes only after the tests naming its assigned task pass on its branch.
- **Acceptance gate.** Acceptance needs the task's tests on the combined program plus root's recorded runs.
  Reach counts are shown, never gated.
- **Both worlds.** Root holds both worlds, with easy tools to move and manipulate data.
- **Close.** The proposing branch retires on close.
- **No fail-first check.** The task's tests may already pass on the base.

The custody law applies: **custody supplies defaults and is not a security boundary.** The earlier draft's
"authority comes only from held custody" wording is withdrawn.

## Broken or unverified first

- **Default was `faulted` and then restarted during this revision.** The open fault is `fa60ad84…`, a
  `ClassCastException` (`Symbol` cast to `Keyword`) in `seon.cluster.agent/turn-step`, origin branch
  `rc-m4-terminal`, as reported via `valid-candidate-value?`. My MCP session was lost to the restart. The
  fault is not from this lane.
- **Nothing here is installed.** The two-world costs below are component measurements. `open-merge!`,
  `take!`, the transfer of rows, acceptance under the new gate, close, and memory for two worlds are all
  unmeasured.
- **The merge path has almost no tests.** Two-hop static test reach: `my.program/prepare-merge!` is reached
  by **0** tests and `seon.cluster.source/prepare-merge!` by **7**.
- **Missing prerequisites.** There is no runtime task writer (7.8 `seon.task/create!`) and no general task
  assignment. Unmigrated issues have a legacy assignment, `:seon.issue/agent`, written when the agent is
  created (`src/seon/issue.clj:1386`, `seon.issue.edn:30`). It is a listened attribute. This design resolves
  issues through that owner and adds no competing store.
- **Schemas are compared but cannot merge.** Schema rows are digested and compared: 3,501 rows, and
  `:seon.schema/key` is in `compared-families` (live).
  - `changed-identities` refuses them by scope (`src/seon/program.cljc:625-627`).
  - `replacement` → `fn/published-index-rows` / `reconcile-tx` (`src/seon/fn.clj:3704`, `:3629`) is compiled
    declaration reconciliation, not an entity copier.
  - `proven-file` writes back only function, test and namespace spans in **one** `.clj` file. Schema EDN files
    under `resources/seon/schemas/` have no write-back.
- **Cross-connection writes are refused today.** `seon.db/foreign-connection-error` (`src/seon/db.clj:485`,
  applied at `:4957`) refuses a write to a connection other than the bound custody. `my.program/cluster-custody`
  (`program.clj:491`) refuses acceptance outside the cluster's branch. Both predate the custody-is-defaults
  law, and slice 2 removes them.

## 1. The merge task (slice 1)

**Runtime writer (7.8).** `seon.task/create!` is a runtime entrance to the existing task writer. It uses the
same declaration/basis comparison as `index-tasks!` (`issue.clj:698`, `tasks-tx`/`task-delta` `:618-664`):
- an unchanged declaration is a no-op;
- a competing revision returns base, current and proposed values (7.16).

Generated tasks get an explicit aspect under the existing `[ns name kind]` identity rule:
- `ns` is the assigned task's owning namespace;
- `kind` is `:merge`, accreted to the enum;
- `name` is `merge <assignment-id>`.

So the identity is `seon.id` over `[ns "merge <assignment>" :merge]`. Re-proposing a new commit for the same
assignment finds the same merge task and records a new proposal fact (2.5). Different assignments never collide.

**Assignment.** An assignment is an attempt entity (2.8) pointing at three things:
- the task and its **reviewed revision** (the declaration digest);
- the agent;
- its branch.

It is written on the **cluster** when root assigns work. Unmigrated issues resolve through
`:seon.issue/agent`. The proposal reads the assignment; it never trusts a task id argument.

**Propose (the agent's gate).** `my.program/propose-merge!` runs these steps:
1. It reads the assignment on the cluster for this agent and branch. An absent or mismatched assignment
   refuses by name.
2. It reads the task's proofs, frozen as identities with their **definition digests**: `task-edges`
   `:seon.task/proofs` (`issue.clj:716`), or `:seon.issue/tests`. Empty proofs refuse.
3. It captures C, the branch commit, and runs exactly those proofs:
   `seon.test/run {:policy :named :identities proofs}` on the agent's world (`my.test/check`'s path).
4. Every proof must have **positive recorded evidence current for C's program**. The runner's reach and
   input digests decide currentness (`seon.test/verified?` `test.clj:1535`, `runner/reach-digests`
   `runner.clj:955`), not head equality, because recording the run advances the commit. Any of the following
   refuses and is returned to the agent as data: red, unknown, excluded, unfinished, or recording failed.
5. It writes a **proposal** on the cluster through `seon.task/create!` plus proposal facts pointing at the
   merge task. The facts are:
   - C;
   - the assignment;
   - the proof identities and digests;
   - the run id;
   - the run's tested program identity.

   The recurrence key is `[assignment C]`. C's run stays on C, and the proposal copies the evidence
   **references** root needs after C retires (one-error-shape law: references, not copies).
6. It delivers to root through the ordinary route: a message naming the merge task id. The message is the
   wake, and the task is the record.

If step 5 commits and step 6 fails, the task still exists. `proposals` reads open merge tasks, so it is
recoverable from facts.

**Root's inbox.** `proposals` reads merge tasks without an outcome fact. Outcomes are `:accepted` (with M,
identities and evidence) or `:declined` (with a reason). The roster inference (`program.clj:483-488`) is deleted.

## 2. Two held worlds: the custody and evaluation contract (slice 2)

**`(my.program/open-merge! {:seon.task/id M})`.** Root calls it at its cluster REPL. It captures **H0** (the
cluster head's commit) and **C** (from the proposal), then:
- forks **S** `merge-<M>` from H0 and **C′** `review-<M>` from C, both with `registry/branch!`
  (`src/seon/cluster/registry.clj:192`, `:from`);
- hosts one agent on each through `host/host!` (`src/seon/cluster/host.clj:124`).

Before reusing an existing branch, a repeat open verifies the task, H0 and C. `created? false` is not proof
of ownership. A partial open releases only what this call acquired.

**The environment value (one per world).** Each world is one map:

```clojure
#:seon.merge{:task M :role :merge|:review :branch :merge-<M> :commit <captured>
             :agent-id "root/merge-<M>" :host <host value>}
```

- `:seon.merge/host` carries the connection, the SCI context and the roster.
- Both worlds live in root's execution roster (`:seon.agent/routing`), keyed by `[M role]`. That is the one
  roster per scope, observable in status.
- Durable identity is the facts task → branches; live objects are never serialized.

`open-merge!` returns `{:seon.merge/merge env :seon.merge/review env :seon.merge/destination <root's cluster
handle> :seon.merge/base H0 ...}`.

**Which world a form writes to by default.**

| Where root evaluates | Default database / connection (elided `seon.db`, supplied defaults) | defs land | test records land |
|---|---|---|---|
| root's cluster REPL | cluster | cluster | cluster |
| S's REPL (`eval_clj branch merge-<M>`, or `(my.program/evaluate! merge-env "…")`) | S | rows on S | S |
| C′'s REPL | C′ | rows on C′ | C′ |

Evaluation in a world is always `seon.cluster.agent/evaluate-source!` (`src/seon/cluster/agent.clj:754`) with
that world's host. `my.program/evaluate!` is that call with the environment's host: a thin wrapper, not a
second evaluator. For MCP, `eval_clj` with a branch uses **the branch's hosted agent if one exists**
(`host/hosted` `host.clj:117`), otherwise `mcp/<branch>`. This removes the `merge/<M>` versus `mcp/<branch>`
clash (review F2). A custody scope never switches the SCI program a form runs in; the world's host does.

**Deliberate cross-world writes.** Every moving function takes the destination environment explicitly, as its
`:seon.db/connection`. `foreign-connection-error` is deleted at its owner (`db.clj:485`, `:4957`). The writer
keeps its destination projection, receipt/provenance and final validation, all from the **destination's** own
value. `cluster-custody` (`program.clj:491`) is deleted. Acceptance takes the destination handle
`open-merge!` captured from root's cluster, as an explicit argument.

**What "copy" means.** C′ copies C's **persisted** database and program into a fresh ordinary SCI environment.
It does not clone the agent's private atoms, channels, live results or running graph (`sci.core/fork` isolates
Vars, not held objects; `reference-code/sci/src/sci/core.cljc:345-351`). Only the *defaults* in C′ never reach
C or the cluster; explicit writes and external effects remain possible, as for any root evaluation.

**Measured components** (default, JVM, 2026-09-26):

| Operation | Time |
|---|---|
| `branch!` fork | 20–21 ms each (two forks: 40–42 ms) |
| `changed-identities` | 31 ms |
| full three-way | 39–47 ms |
| forks + diff | **79–89 ms**, before hosts or task writes |
| `acquire-context!` | 719 ms cold (first acquisition of a program), 10.6–11.4 ms warm |
| host start | ≈240 ms first (host.clj comment) |
| release | 0.3 ms |
| retire | 8.6 ms |

Owed in slice 2: end-to-end `open-merge!` and heap retained by two worlds.

## 3. The data-moving toolset (slices 3–4)

All of these are ordinary contracted functions over database values in `my.program` (the thin entry) that
delegate to owners. All take explicit worlds and return values or declared refusals, never throws.

| Function | Inputs → value | Composition and cost |
|---|---|---|
| `(diff {:from review :onto merge})` | `:seon.program/three-way` over functions, tests, namespaces **and schemas**, computed against **H0**. Conflicts keep base/source/destination values. Reach counts (two-hop, per changed identity) are attached as an observation | `program/digest-map` + `three-way` (`program.cljc:459`, `:631`), with the scope refusal widened to the compared families it already digests. Cost follows changed declarations (39–47 ms for one). Reach is `seon.fn/call-reach` (`fn.clj:1869`) at 1–64 ms; it is information only |
| `(take! {:from review :onto merge :seon.program/identities #{…} :seon.merge/expected {id digest}})` | The destination's changed rows and report, or a refusal naming each stale identity | `replacement` (`source.clj:773`) of the chosen identities from the source value, applied by the destination writer as `[:db.fn/call …]`. The **destination digests root observed** (from `diff`) are compared inside that transaction, so a stale `take!` never overwrites a repair on S. Includes the namespace-row closure; a conflict identity is taken only if named explicitly. `unselected-reach` (`:781`) becomes an attached advisory, not a refusal. Schemas reconcile at the schema admission owner (slice 3b). No copied source strings are evaluated; the transferred rows arm and adopt through the existing owners before anything calls them |
| `(rows {:from world :seon.program/identities #{[attr v] …} :seon.db/selector sel})` | Complete portable owning values: peer refs as lookup refs by identity, owned components complete, no raw entity ids. Refuses on a missing identity, unsupported ownership or a bound | The portable-ref/component traversal inside `fn/published-index-rows` (`fn.clj:3704`), factored to its data owner and applied to any declared entity schema. Cost is O(selected attributes + reached owned components) under a declared bound |
| transform | plain `assoc`, `update`, `select-keys`, `mapv` over `rows` output | No DSL. Clearing a value is an explicit `[:db/retract …]`: upsert is not replacement |
| `(put! {:onto world :tx-data …})` | writer report | `seon.db/transact!` on the destination's connection. Validation of final owning values, refs and components happens there under the destination projection (Datahike `:db.fn/call` and final validation, `reference-code/datahike/src/datahike/db/transaction.cljc:1171`, `:1224-1233`). Task, config and issue rows go through their own writers where those own revision rules |

**Direction is free.** Root can take C′→S, rewrite on S, write S→C′ to try something in the agent's world, or
write data to the cluster from its own REPL, which is an ordinary root write with its own basis. **Data never
rides program acceptance**: `changed-identities` stays declaration-only. A data-only task closes with an
accepted outcome and no declaration delta (§4).

```clojure
;; S's REPL (defaults: S); `review` and `merge` are the open-merge! environments
(def d (my.program/diff {:from review :onto merge}))
(my.program/take! {:from review :onto merge :seon.program/identities (:seon.program/added d)
                   :seon.merge/expected (:seon.merge/destination-digests d)})
(defn a/g …)                                              ; repair: an ordinary row on S
(->> (my.program/rows {:from review :seon.program/identities #{[:my.note/id "n1"]} :seon.db/selector '[*]})
     (mapv #(assoc % :my.note/title "fixed"))
     (hash-map :onto merge :tx-data) my.program/put!)    ; data C′ → S, transformed
(my.test/check {:seon.test/identities (my.program/merge-proofs M)})  ; on S
```

## 4. Evidence and acceptance (slice 4)

**The required set.** It has two parts, fixed by facts rather than by a fresh lookup:
- the **frozen proofs**: identities and digests from the proposal;
- **root's additions**, recorded by `(my.program/require-tests! M #{…})`.

If S drops or changes a proof test, that shows as a digest mismatch and refuses. The set never silently shrinks.

**Evidence rules.**
- Each required member needs positive recorded evidence **current for S's program at acceptance**, decided by
  the runner's reach and input digests (`verified?`), not by head equality.
- Runs on C′ are visible experimental evidence and **never discharge an S obligation**.
- A later red, unknown, excluded, unfinished or unrecorded required member blocks acceptance. The
  `answered` inclusion check survives as "required ⊆ recorded current members".
- **Data proofs** receive S's held database value or a declared seed as explicit input
  (`apply-seeds!` `test.clj:131`). The test world is published program + overlay + boot facts and
  deliberately does not copy S's data (`clean-request-handle` `:1805`). The input basis is recorded.

**Acceptance: `(my.program/accept-merge! {:seon.merge/task M :seon.merge/destination dest})`.**
1. **Delta.** The effective write set is `changed-identities(H0, S)`. It includes root's repairs and the
   writer-derived re-digests (`fn/report-identities` `fn.clj:3643`). H1-only changes are never selected for
   reversal. The earlier proposal's key set is not the delta (review F5).
2. **Expected digests** come from **H0** for every identity in that set, with `:seon.program/absent` for
   additions and the old digest for retractions. Missing digests are a typed refusal.
3. **Writer transaction** on the destination. `declarations-held-tx` is kept. The replacement is built inside
   the writer against the current destination, and the writer also checks the task revision and that the
   merge task has no outcome yet. The accepted write records selected identities, evidence refs, H0/C/S
   parents and the outcome in the same transaction.
4. **Evidence currentness against H1.** A program change on the destination since H0 that is in a required
   run's reach refuses with `refresh needed`, naming the changes. The test is the member's reach digest
   recomputed on the would-be-merged value (`seon.db/with` H1 + tx) compared with the recorded one. Root then
   runs `(take! {:from cluster :onto merge …})` for those identities, repairs, and reruns the affected
   required tests. Unrelated data writes and content-equal program changes keep the evidence. This checks
   currentness only: it is not a reach gate and not a head lock. The honest cost: the static closure behind
   reach digests saturates (§6), so a change to a hub such as `seon.db/*` invalidates many runs.
5. **Data-only tasks.** The `empty? tx` refusal (`source.clj:986`) no longer rejects them. An empty
   declaration delta with a green required set records the accepted outcome only.

**Deleted:**
- the coverage veto (`uncovered`);
- the automatic reaching run in the merge path;
- the issue-only `answered` code, replaced by the inclusion check.

**Failure contract (total REPL path, ee4044ff8).** Every operation returns its value. A precondition refusal
*is* the evaluated value. A fault in a later stage (recording, rendering, write-back or retirement after an
accepted write) is attached beside the returned value and recorded and delivered as a core fault. If storage
fails, execution stops; there is no retry, and no rollback is claimed when the transaction outcome is unknown.
"Accepted, not written" is a value carrying one diagnostic.

## 5. Write-back and close (slice 5)

**Write-back.** It keeps its file-basis and reindex-equivalence checks (`fix-merge-basis`, `merge-ns-rows`).
Slice 5 extends it from one file to one write-back per touched file over the same `proven-file`
(`source.clj:1070`). Schema identities are written into their `resources/seon/schemas/<ns>.edn` resource by an
exact-span map-entry splice with an equivalence proof. That extension is the new owner question below; until
it is ruled, a schema change is accepted and reported "accepted, not written" by name.

**`(my.program/close-merge! M)`.** It is a root lifecycle operation over the held environments, run from root's
cluster REPL. Called from within S, it submits the work to the existing work launcher (the pattern
`accept-merge!` already uses, `program.clj:547-564`) and runs after the S call returns. It never joins its own
execution. Steps:
1. Record the outcome (accepted or declined) first. The outcome survives any failure after it.
2. Retire the hosts of **C, C′ and S** with `host/retire!` (`host.clj:155`), which closes admission, awaits
   actual exit and releases.
3. Unlink each branch with `retire-branch!` (`registry.clj:339`).

Failure handling:
- A missed exit keeps the held resources and records a loud, **incomplete** close. It is never read as success.
- Repeating close is idempotent.
- C retires only when no other open merge task names it; that is decided from lifecycle facts, never from a
  timeout.
- The accept path's own synchronous retirement of S is folded into close.

## 6. Selection: what is indexed

Live, default, basis 536870992. "Isn't this just a selection problem?" Yes. The ruling makes the required set
an indexed read.

**Indexed:**
- **`:seon.fn/calls`: 99,042 datoms**, 44,663 of them on the 2,270 tests (every test has calls). There are
  5,309 functions.
- **Two-hop `call-reach`: 1–64 ms.** `seon.db/db` has 926 reaching tests.
- **Task proofs** are indexed through `:seon.task/tasks`: 101 tasks, of which **10 tests** name one.
  Issues: 463, with 110 test edges.

**Not indexed:** recorded **`:seon.test/reach`: 0 datoms**. The schema exists; no producer writes it.

**Static transitive closure per function** (walk of all 5,309, 1,268 ms, one-off):

| Statistic | Tests reached (of 2,270) |
|---|---:|
| median | 75 |
| p75 | 316 |
| p90 | 1,468 |
| p99 | 1,738 |
| max | 1,945 |

961 functions (18%) reach more than 1,000 tests, because every path meets the database and fixture hubs.
Exact reach needs the recording in research ★1 (`docs/prds/next-turn/research/test-selection.md`). Under this
design, that recording would sharpen the advisory counts and §4's currentness check. It is not a gate, and it
is a separate lane.

## 7. Slices and prices (re-priced after the review, gross additions counted honestly)

Every slice uses focused installed `seon.test/run` requests. Root alone owns the final affected, platform and
bulk proof. The five slices below break into eight ordered cuts, because a slice over 100 gross added lines is
split at a loadable seam.

| # | Slice (cuts) | Owners / files | Gross add / delete (src+schema) | Tests add | Hours | Proves |
|---|---|---|---|---|---|---|
| 1 | Task writer, assignment, proposal (1a: `seon.task/create!` + generated aspect + assignment/proposal schema; 1b: `propose-merge!` gate + delivery + `proposals`) | `src/seon/issue.clj` (task owner), `seon.task.edn`, `src/my/program.clj` | ≈ +130 / −35 | ≈ +90 | 7–9 | repeat proposal finds the task, assignment mismatch, revision race, empty/missing/red/excluded/unrecorded proof, green, legacy issue, delivery failure recoverable, no wake before positive proof |
| 2 | Two worlds and lifetime (2a: environment value, `open-merge!`, `evaluate!`, MCP hosted-identity lookup; 2b: delete `foreign-connection-error` and `cluster-custody`, `close-merge!`) | `my/program.clj`, `src/seon/db.clj`, `script/seon/dev/mcp.clj`, `host.clj` | ≈ +130 / −55 | ≈ +100 | 8–10 | product + MCP entrances, separate default writes/defs/results, deliberate cross-world write, C unchanged by C′ defaults, partial open, repeat-open verification, incomplete close on missed exit, memory for two worlds |
| 3 | Selection and movement (3a: split `prepare-merge!` into `diff`/`take!`, conflict selection, expected-destination digests, advisory reach, `rows`/`put!`; 3b: schema families through `changed-identities` + schema reconcile at the admission owner) | `source.clj`, `program.cljc`, `fn.clj` (factor the traversal), `src/seon/schema/admission.clj` | ≈ +175 / −110 | ≈ +120 | 12–16 | add/edit/delete of fn/test/ns/schema, conflict selection and repair, ns closure, data copy with peer refs, components and explicit retractions, destination validation, transferred program armed and callable on S |
| 4 | Evidence and acceptance | `source.clj`, `test.clj` (reach-digest read), `my/program.clj` | ≈ +100 / −30 | ≈ +90 | 7–9 | C′-only green refuses, repair joins the delta, stale proof/task/dependency refuses and `refresh` repairs it, touched-row race refuses atomically, unrelated data keeps evidence, later red stays red, outcome survives close, data-only task accepted |
| 5 | Write-back and close, end to end (5a: multi-file write-back + failure-beside-value; 5b: schema EDN write-back, **if ruled**) | `source.clj`, `src/seon/edit.clj`, tests | ≈ +50 / −10 (5a); ≈ +70 (5b) | ≈ +110 | 6–8 (+4–6 for 5b) | accepted-not-written diagnostic, renderer/recording failure beside the value, C/C′/S retired only after exit, one full propose→open→take/transform→repair→test→accept→write-back→close through MCP and product root, cold/warm time and memory |

**Totals.**
- About **+585 / −240 gross** source and schema lines (≈+655 with 5b), plus about 510 test lines.
- **46–58 engineering hours** (≈4–5 days serial), plus Astra review per cut.

**Deletions from current source, counted once:**

| What | Location | Lines |
|---|---|---:|
| `prepare-merge!` orchestration: S construction, gate call, issue `answered` | `source.clj:840-930` | ≈−70 net after `diff`/`take!` reuse its parts |
| `uncovered` veto | `:970-973` + clause | −6 |
| `proposals` roster inference | `program.clj:483-488` | −10 |
| `cluster-custody` and its two callers | `program.clj:491-503` | −16 |
| `foreign-connection-error` and its call | `db.clj:485` + `:4957` | ≈−30, measured at cut |
| accept's synchronous retirement, folded into close | `program.clj:547-564` | −18 |
| unselected-reach refusal clauses | `source.clj:903-906` | −5 |

Also removed, but not counted as deletions from current source: planned code that becomes unnecessary for
merge (D1 O3a, budget 100, and O3c's reached-seed veto).

**Why it grows (net ≈ +345).** Four capabilities the ruling requires do not exist today:
- runtime tasks and assignment (7.8);
- two-world lifecycle;
- schema and data movement;
- evidence currentness.

**The smaller alternative.** Drop C′ hosting: root reads C′ only as values and runs its tests through a C′
execution handle, with no hosted agent. That saves about 40 lines and 240 ms per open, but root can no longer
"run code in" the agent's world at a REPL, which the owner asked for. Not recommended.

## Owner question (genuinely new)

1. **Schema write-back (slice 5b).** Should accepted schema changes write back into
   `resources/seon/schemas/<ns>.edn`? Each option has a price and a trade-off:

   | Option | What it does | Price | Sacrifice |
   |---|---|---|---|
   | (a) Delimit (simplest) | Schema changes merge into the cluster and report "accepted, not written"; root edits the EDN by hand | +5 lines | Every schema merge needs a manual file edit until (b) |
   | (b) **Recommended** | Exact-span splice of the map entry in its EDN resource, proven by reindex equivalence, like `proven-file` for `.clj` | ≈+70 lines, 4–6 h | — |
   | (c) Move schemas next to their producer | Schema declarations move into the `.clj` producer namespaces (spec 1.2: a data shape's facts on its schema, where grep finds it), so the existing `.clj` write-back covers them | a cross-owner migration, days | Large |

   Recommendation: (b) now, with (c) as its own decision later.

## Evidence and limits

**Probes (JVM, sessions `merge-task-design` and `merge-task-design-2`, read-only except my own branches):**
- the counts;
- the reverse-closure survey;
- three rounds of fork, acquire and diff, then release and retire;
- the declaration families and schema digest counts after the restart.

**Branches.** `merge-task-design-probe`, created through MCP with one `defn` and retired, and
`merge-task-design-copy{,-2,-3}`, created and retired. The roster was verified clean.

**Not measured:** a proof run on S (landing figures: 1.5 s changed, 72 ms reused, from
`fix-test-request-cost`), end-to-end `open-merge!`, memory for two worlds, `merge-base` on a long lineage,
and any of the new operations. No source was edited and no test was run.
