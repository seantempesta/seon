---
type: independent-review
status: complete — design revision required
reviewer: gpt-6-astra
created: 2026-09-26
design: merge-task-design-2026-09-26.md at 2b1a305b5
authority: CLAUDE.md / AGENTS.md at c03761415
verdict: REVISE
---

# Review: merge task and two worlds

Existing branch, host, declaration reconciliation and test owners can implement option B. Compose them around a durable merge task and two explicit environment values; keep selection and transformation in ordinary REPL functions. The proposed composition is sound in direction, but the design is not yet sufficient to implement the owner's data-moving and acceptance requirements.

## Broken or unavailable evidence first

`bin/seon status` and MCP `runtime_status` both reported default **faulted**, PID 80197, start `2026-09-26T17:26:52Z`, no missing readiness layers, one open error signature, four errored receipts and one unowned namespace. The fault is `fa60ad84404c9720c3e6cd72ecdba9756f92bde2721e5568cf43d96d54a72aa3`, a `ClassCastException` reported by status at `seon.schema/valid-candidate-value?`, last occurrence `2026-09-26T17:13:55Z`. The bounded JVM probe returned its value alongside the existing branch error (evidence below). This does not establish merge health.

The running archive is `/Users/sean/src/seon/data/source/39ee6f3f332b2ae2ff3421f5447a40ee95d02330`, not the checkout head when review began, `c03761415`. The live probe confirmed existing merge/host Vars and the absence of loaded `seon.task/create!`, `my.program/open-merge!`, and installed `:seon.agent/task`. It did not exercise proposal, transfer, acceptance, cleanup or write-back. Source findings below are source proofs, not claims that option B is installed.

Read CLAUDE.md end to end, including the new merge and total-REPL-path laws; the full design; and all four assigned source files. Additional reads traced their direct dependencies and callers. Unrelated staged and unstaged work was present, including MCP and SCI/render work; none was changed, loaded or tested here. Source citations are to the reviewed baseline `c03761415`, including committed MCP rather than its in-flight edit. Other lanes advanced HEAD during review; this document does not audit their subsequent changes.

## Binding decisions to integrate into the design

Option B is selected, not still recommended. Propose runs the assigned task's tests on the agent's branch before creating/delivering a merge task. Root opens S from captured cluster head H and C′ from captured proposal C, works both through the ordinary REPL, and selects identities from the diff. The task is not a list of what may merge. Acceptance requires the task's tests on the combined S program plus the tests root chooses to run, with recorded evidence. Reach counts are shown, never an implicit gate. Root holds both environments and may deliberately write to either. The proposing branch retires when the merge closes. There is **no fail-first/base-red check**.

These replace design lines 167–179 and questions Q1, Q2, Q3, Q5 and Q6 at lines 254–267. Update the ordered design in place. Do not add another options exercise or ask those questions again. The current custody law also replaces “authority comes only from held custody” at design line 104: custody supplies defaults, not a security boundary.

## Findings

### F1 — P1: schemas and data cannot pass the claimed selection path

**Locations:** design:133–147; `src/seon/program.cljc:588–629`; `src/seon/cluster/source.clj:773–779`, `:1079–1121`; `src/seon/fn.clj:3618–3630`, `:3693–3775`.

**Failure scenario:** the proposal changes a schema and its function, or produces ordinary data that root needs to inspect and transform. `changed-identities` explicitly refuses families outside functions, tests and namespaces at lines 614–627. Data without a program definition digest is outside that diff altogether. `replacement` is compiled declaration reconciliation; it is not an arbitrary entity-copy primitive. `proven-file` only extracts function/test/namespace sources and requires one file. A schema transfer or a function plus its test in different files cannot use the illustrated write-back unchanged.

**Smallest fix:** extend the existing declaration comparison/reconciliation at its owning schema seams, including schema forms, storage attributes and consumer contracts as one validated cut. Do not relabel arbitrary data as program. Add only a portable selected-row read/transaction composition for explicit data movement (specified below). Keep ordinary data transfer separate from program acceptance and file write-back. State the current one-file/source-family limit and order the needed write-back extension; do not price full schema support as reuse already present.

### F2 — P1: “both environments” lacks a working custody and evaluation contract

**Locations:** design:96–113, `:259–261`; `src/my/program.clj:414–441`, `:491–503`, `:541–545`, `:580–582`; `src/seon/db.clj:383–410`, `:485–518`, `:4939–4967`; committed `script/seon/dev/mcp.clj:420–433`; `src/seon/cluster/host.clj:124–153`.

**Failure scenario:** root is evaluating on S and calls acceptance with the held cluster connection. Merely retaining that connection does not change `my.program/context`, so `cluster-custody` rejects S; supplying a cluster context still encounters `foreign-connection-error` when the write runs under S's bound custody. An explicit `db/transact!` to C′ likewise refuses today. Separately, a host created as `merge/<M>` (design:97) cannot be used by the stated existing MCP branch call: MCP requests `mcp/<branch>`, and `host!` refuses a different agent on an already hosted branch. Returning handles alone does not specify how a product root evaluates with them.

**Smallest fix:** define one environment value per world, carrying connection, database acquisition, branch, SCI context/host handle, agent identity, task and declared evaluation inputs. Select that value at the existing `evaluate-source!` entrance; adapt MCP to use the actual hosted identity or adopt its naming consistently. Product root calls that same entrance with the returned handle. In S, elided database/program calls default to S; in C′ they default to C′; outside the merge, root still defaults to its cluster. A data transfer supplies the destination connection explicitly. Acceptance supplies the captured destination context explicitly. Remove the obsolete cross-connection policy at its owner under the already-binding custody law, preserving destination projection, receipt/provenance and validation correctness. A custody scope alone does not switch the SCI program that executes a form. No new evaluator, context registry, permission token or namespace denylist is warranted.

Define “copy” precisely: C′ copies C's persisted database/program into a fresh ordinary SCI environment. It does not clone the agent's private atoms, channels, result objects or running graph. `sci.core/fork` isolates new/redefined Vars, not arbitrary mutable objects held as values (`reference-code/sci/src/sci/core.cljc:345–351`). The design's “nothing root does there reaches ... the cluster” (line 102) must mean default database writes; explicit writes and external effects remain possible.

### F3 — P1: the runtime writer, assignment and task delivery are prerequisites, not +25 lines of plumbing

**Locations:** design:37–40, `:47–77`, `:243–250`; `resources/seon/schemas/seon.task.edn:2–27`; `src/seon/issue.clj:618–664`, `:698–732`, `:1370–1393`; `src/my/program.clj:455–465`; requirements `language-requirements-2026-09-24.md:160`, `:165–167` (7.8, 7.13, 7.16).

**Failure scenario:** an agent supplies a convenient issue id rather than its assignment; the new merge gate faithfully runs the wrong tests. A merge task written only on C is invisible to root's task query on the cluster. Adding `:merge` to the enum and hashing `[:merge branch C]` does not satisfy the existing task's required namespace/name/intent or its `[ns name kind]` identity rule. Re-proposing one C for distinct assignments also collides under that proposed aspect.

**Smallest fix:** implement 7.8 over the existing task writer's declaration/basis comparison, with an explicit generated-task aspect and schema, before proposal. Record assignment/attempt as the authoritative relation among task, agent and branch, plus the reviewed task revision; proposal reads it rather than trusting a free task parameter. Record proposal commit, proof identity/digest set and terminal recorded run as runtime facts pointing to that task, with a stable recurrence key that includes the assignment identity. The cluster holds the durable merge task/outcome and ordinary root delivery; C holds its test run. Preserve the evidence needed after C retires.

Correct the overly broad claim at design:39: there is no general task assignment attribute, but **legacy issue assignment exists** as `:seon.issue/agent`, written with agent creation (`issue.clj:1386`, schema `seon.issue.edn:30`). Resolve unmigrated issues through that owner; do not add a competing issue-assignment store. Prove that task creation and ordinary delivery are recoverable from facts if either step fails. Automatic acceptance is unnecessary; delivery of a green proposal to root is part of B, even though design option C currently bundles root wake with extra automation.

### F4 — P1: required evidence is underspecified and C′ cannot satisfy the combined-program gate

**Locations:** design:49–64, `:151–163`; `src/seon/cluster/source.clj:961–983`; `src/seon/test.clj:1805–1865`; `src/seon/test/runner.clj:1005–1018`.

**Failure scenario:** root runs an extra test on C′ and cites it as required acceptance evidence, but the selected/edited S program fails it. Or S drops a proof edge/test and a fresh lookup silently shrinks the required set. Or a run records its completion, advancing S's commit, so literal “tested commit = S head” rejects its own successful evidence. Conversely, checking only `program-written-since?` says nothing about changed non-program inputs used by a data proof.

**Smallest fix:** bind assignment/task revision and proof identities **and definition digests** to proposal and acceptance; explicitly record root's additional required tests. All required tests must have current positive evidence against S's accepted program and declared inputs. Runs in C′ remain visible experimental evidence and cannot discharge S obligations. A later required red, unknown, excluded, unfinished or unrecorded member is not green. Preserve explicit proof-set inclusion checks when deleting the issue-specific `answered` code; “all selected members green” alone does not prove all required members were selected. Test output and recording transactions may advance the commit without changing tested inputs; use the runner's tested identity/input evidence and currentness mechanism, not head equality.

The harness builds published program + overlay + boot/config facts; it deliberately does not copy arbitrary S data (`test.clj:1805–1819`). Data proofs must receive the exact held database value or explicit declared seed/input through the existing harness. Record that input basis. “On S” is not proof that a query actually observed S's copied data. No second runner is needed.

### F5 — P1: per-row guards prevent overwrites but do not by themselves prove the accepted combined program

**Locations:** design:144–147, `:158–165`; `src/seon/cluster/source.clj:817–835`, `:952–992`; `src/seon/program.cljc:588–608`.

**Failure scenario:** S forks H0; root takes f and repairs g. If acceptance retains the original proposal's expected map, g is omitted (`delta` is exactly its keys today). If “H” is reread as current H1 when computing the S delta, H1-only changes can be selected for reversal and cross-lineage transaction numbers are misused. Even with the correct H0 delta, another task can change a dependency h in H1 after S's tests pass: the touched-row guard for f/g still passes, but the final f/g+h program was never tested together. Ordinary data writes to H must remain harmless.

**Smallest fix:** retain immutable H0 as the selection base; derive and filter the actual H0→S declaration differences, including root repairs and writer-derived changes. Compute expected old digests from H0 for the complete effective write set, including additions' absence and retractions. Build/reconcile the transaction against the writer's current destination, not an unguarded pre-read; retain the existing digest comparison in that transaction. Separately validate the currentness of the required runs' tested program/input dependencies at acceptance. If a relevant destination program change invalidates them, bring that change into S, repair there and rerun the affected required tests. Unrelated data or content-valid program changes need not invalidate evidence. This is an evidence-currentness check, not a reaching-test coverage gate or a whole-head lock.

Acceptance also checks the reviewed task revision and open merge outcome at the destination writer, then records the selected identities, evidence references and resulting decision with the accepted write. Missing digests are typed unknown/refusal, not absence. The existing guard is worth retaining; “only the gate's required set changes” (design:25) overstates what can stay unchanged.

### F6 — P1: conflict handling and advisory reach cannot be inherited from `prepare-merge!` unchanged

**Locations:** design:99, `:133–138`, `:239`; `src/seon/cluster/source.clj:882–906`; `src/seon/program.cljc:631–668`.

**Failure scenario:** one unrelated identity conflicts, so current `prepare-merge!` returns the whole three-way value before building S or applying a disjoint selection. Its `whole` set excludes conflict identities, preventing root from deliberately taking one. The `unselected-reach` clause at source:905 actually refuses the operation, despite design:136 calling it “data ... not a gate.” Root cannot work the intended repair interactively.

**Smallest fix:** open both worlds and return the diff even when conflicts exist. Let root select a conflict identity explicitly from its source value, or author a resolution on S; never silently select conflicts. Preserve all base/source/head values for review. Make unselected reach an attached advisory observation, while retaining real schema/ref/contract failures. Carry the destination digest root observed with its selection and compare it inside the writer, so a stale `take!` cannot silently overwrite a root repair; root can inspect a fresh diff and deliberately replace it. Do not build a second conflict engine or require task scope amendments.

### F7 — P1: close omits C, and retiring S inside its own call can join the caller

**Locations:** design:85–90, `:129`, `:264–265`; `src/my/program.clj:547–564`; `src/seon/cluster/host.clj:155–180`; `src/seon/cluster/registry.clj:339–369`, `:394–414`.

**Failure scenario:** the illustrated `close!` releases only S and C′, leaving the proposer C live against the ruling. Calling `host/retire!` synchronously from S's executing form asks `disarm-agents!` to join that same execution. Unlinking first is refused while a connection remains held. A partial open can also leave one host/branch behind, and `branch!` returning `created? false` does not prove it belongs to this task/basis.

**Smallest fix:** close is a root lifecycle operation over the held handles, with settlement before retiring the world executing that settlement. Use the existing execution owner to close admission and await actual exit, then release and unlink C, C′ and S. Do it from root's cluster after the S call returns, or submit cleanup to the existing lifecycle owner; do not create a cleanup scheduler. Preserve accepted/declined outcome and evidence independently of unlink success. Failed cleanup remains a loud, incomplete close, not success. Repeated close is idempotent; partial opens release only resources this operation acquired, and repeated opens verify task/bases before reuse. Preserve held resources on missed exit. Coordinate retirement with any other open merge from C by a lifecycle fact; never infer that a timeout terminated it.

### F8 — P1: the new operations need the total-REPL-path failure contract

**Locations:** design:56–57, `:107–116`, `:127–129`; `AGENTS.md:236–244`; `src/seon/cluster/source.clj:990–998`, `:1181–1190`.

**Failure scenario:** acceptance commits, then recording, rendering, write-back or retirement fails. If the combined helper throws away the accepted value, root sees failure and may repeat a committed operation. Conversely, swallowing that fault loses the required diagnostic and delivery. A green test's value is not authority to accept when its evidence recording failed.

**Smallest fix:** keep operations as ordinary values on the existing read→evaluate→record→print path. A precondition refusal is the operation's evaluated value. A later-stage fault accompanies the already produced value, retaining cause, origin and operation, and reaches root through ordinary fault recording/delivery and the tool response. Storage failure stops the execution that cannot continue; do not retry or claim rollback when transaction outcome is unknown. Prove successful acceptance plus failed write-back remains “accepted, not written,” and a renderer failure returns the value plus one diagnostic. This is a cross-owner proof dependency on the total path, not permission to implement merge-specific printing or error storage.

### F9 — P2: line and latency budgets omit necessary work

**Locations:** design:85–94, `:224–250`, `:269–285`.

**Failure scenario:** two forks at 20–21 ms plus a 39–47 ms diff are described as “about 50 ms of forks and diff.” The +125/−95 estimate excludes assignment, schemas/data transfer, evidence-currentness and lifecycle changes. Removing a planned 100-line mechanism is useful simplification but is not a deletion from current source. Host acquisition timings are not an end-to-end `open-merge!` measurement, and there is no memory measurement for two environments.

**Smallest fix:** reprice B after the fixes and count disjoint actual deleted spans once. Name the old `prepare-merge!` orchestration, automatic reach/coverage veto, message/roster proposal inference and duplicated retirement orchestration that disappear. Retain reusable declaration/digest/test owners. Measure complete open/take/test/accept/close and bytes allocated or heap delta on installed code; separate first program acquisition, warm reuse, changed-input work and retained memory. The supplied readings imply roughly 79–89 ms for two forks plus full diff alone, before hosts or task writes; they do not prove the final operation's bound.

## Smallest data-moving toolset

Keep lifecycle entry points thin in `my.program`, delegating to the existing owners. A new `my.merge` façade is not itself a second evaluator, but duplicating stateful wrappers there buys nothing. Use one explicit merge-environment value, stored live in the existing execution roster and linked by durable task/branch identities. Names below describe proposed functions, not installed APIs.

| Operation | Inputs and result | Composition / cost and invalidation |
|---|---|---|
| `diff` | Captured B, source C′ value, destination S value; identity sets and all three states for conflicts | Existing program digest maps/three-way, expanded at the schema owner. Initial lineage walk is bounded by commits; subsequent comparison follows changed declarations. Recompute when compared inputs change. Reach counts are separate observations. Data reads use explicit selectors rather than making a whole-store merge diff. |
| `rows` | Immutable source database + selected identities + declared selector; portable complete owning values or named refusal | Factor the portable-reference/component traversal already in `published-index-rows` into its data owner if needed; use `db/pull`/`pull-many`, schema identities and component declarations. Work is O(selected attributes + reached owned components), with a declared refusal bound. Identity refs cross worlds; raw branch-local entity ids do not. Peer refs resolve by identity; owned children receive destination identities/tempids. |
| `take!` | Explicit source value, destination environment, diff identity selection, expected destination values; changed rows/report | Existing declaration replacement/reconciliation for functions, tests and namespace closure, extended for schemas as F1 requires; `db/transact!` at the destination. A conflict selection is explicit. Validate the actual effective closure and arm/adopt through existing owners before calling transferred definitions. No evaluation of copied source strings as a parallel installation path. |
| ordinary data transformation/write | `rows` result → ordinary `select-keys`, `assoc`, `update`, `mapv`, etc. → explicit destination writer | `db/transact!` for ordinary data; task/config/etc. writers where they own revision rules. Upsert is not exact replacement: clearing a value needs explicit retraction. Reuse exact replacement only where its declared owner semantics apply. Validate final owning values, refs and components in one destination transaction. No transformation DSL or stored callback registry. |

This is two read/selection conveniences and one declaration-taking operation; the existing database/domain writers already provide general data writes. Root can copy C′→S, transform values on S, or deliberately write S→C′ using the same explicit arguments. Data movement into the cluster is also an explicit owner write, with its own evidence and basis; it never rides `program/changed-identities` or program acceptance implicitly. A data-only task can therefore have an accepted outcome without inventing a declaration delta; the current `empty? tx` refusal at source:986 must not reject that task outcome.

Do not make a general recursive database copier: peers are named identities, components are complete owned values, and unsupported/missing ownership is a declared refusal. Admission uses the destination projection and the existing final-report validator. Contracts compile through Malli's `schema`/cached `validator` and failures through `explainer` (`reference-code/malli/src/malli/core.cljc:2569`, `:2645`, `:2661`), not a merge-owned schema interpreter.

## Dependency and verification record

Pinned checkout dependencies:

- `reference-code/datahike`: `19ad462aa67763049d49756b5fdc0f258a2830d8`.
- `reference-code/malli`: `a89ebef0572628b9198b825e5aa8b59484902593`.
- `reference-code/sci`: `7d28561e0b8b94fa08227dc80c7a8c43bc8aa308`.

Datahike `versioning.cljc:236–274` branches the selected stored commit/index roots and publishes roster membership last; it does not copy the store. `db/transaction.cljc:1171–1172` passes the in-transaction database to `:db.fn/call`; `:1224–1233` rejects a non-nil final validation result. Those are the composition seams for writer-time decisions and complete-value validation. No new dependency or web research was needed for this bounded review of pinned source.

One JVM probe, explicit root `/Users/sean/src/seon`, cluster `default`, session `review-merge-task-2026-09-26`, `read_only true`, timeout 5000 ms; no branch or runtime mutation requested:

```clojure
(let [started (System/nanoTime)
      runtime (Runtime/getRuntime)
      used-before (- (.totalMemory runtime) (.freeMemory runtime))
      connection (seon.cluster.boot/connection "default")
      database @connection
      symbols '[seon.task/create! my.program/propose-merge!
                my.program/open-merge! seon.cluster.source/accept-merge!
                seon.cluster.host/host! seon.cluster.agent/evaluate-source!]
      vars (into {}
                 (map (fn [s]
                        [s (when-let [n (find-ns (symbol (namespace s)))]
                             (when-let [v (ns-resolve n (symbol (name s)))]
                               (select-keys (meta v) [:file :line])))]))
                 symbols)]
  {:branch (get-in database [:config :branch])
   :vars vars
   :assignment-attribute? (contains? (:schema database) :seon.agent/task)
   :task-id-attribute? (contains? (:schema database) :seon.task/id)
   :used-heap-before used-before
   :used-heap-after (- (.totalMemory runtime) (.freeMemory runtime))
   :elapsed-ms (/ (- (System/nanoTime) started) 1000000.0)})
```

Returned value: branch `:cluster-default`; vars `seon.task/create! nil`, `my.program/open-merge! nil`, `my.program/propose-merge! {:file "my/program.clj" :line 443}`, `seon.cluster.source/accept-merge! {:file "seon/cluster/source.clj" :line 932}`, `seon.cluster.host/host! {:file "seon/cluster/host.clj" :line 124}`, `seon.cluster.agent/evaluate-source! {:file "seon/cluster/agent.clj" :line 754}`; assignment attribute `false`, task id attribute `true`; heap before/after both **1,486,526,664 bytes**; body **0.235375 ms**, prepl event **3 ms**, `windowed? false`. A zero sampled heap difference is not an allocation measurement.

The complete response also carried one branch error at basis **536870997**, since **0**, signature given above, operation `seon.cluster.agent/turn-step`, occurrence count **1**, origin branch `["rc-m4-terminal"]`, message: `class clojure.lang.Symbol cannot be cast to class clojure.lang.Keyword (clojure.lang.Symbol and clojure.lang.Keyword are in unnamed module of loader 'app')`. Both the result and the fault were inspected; neither was discarded. No tests, paid provider calls, reload, publication, merge, reset, worktree or additional JVM were run. No source/test lines changed (net **0/0**); only this review is delivered. Performance and integration of the proposed design remain unverified.

## Verdict and revised slice order

**REVISE.** Build B; do not revisit the owner's choice. Revise the design around F1–F8 before implementation and replace its two-slice estimate using F9.

1. **Task writer, assignment and proposal.** Land 7.8 and the runtime assignment/proposal/evidence shapes over the existing task owner, with explicit cluster delivery. Prove repeat proposal, assignment mismatch, task revision race, empty/missing/red/excluded/unrecorded proof, complete green, and legacy issue resolution. Green on the base is allowed. Capture C and require evidence current for its program; recording itself may advance its commit. No root wake before positive recorded proof.
2. **Two-world ordinary entrance and lifetime.** Open S from H0 and C′ from the proposal's C with consistent host identities and explicit environment defaults. Remove the obsolete explicit-connection prohibition at its owner. Prove both product and MCP entrances, separate default writes/defs/results, deliberate cross-world data writes, C remaining unchanged by default C′ writes, partial-open cleanup, repeat-open identity checks and a retained incomplete close on missed exit. No private mutable-object cloning.
3. **Selection and movement, including schemas/data.** Split `prepare-merge!`; reuse digest/reconcile owners and add only the portable-row seam actually missing. Prove function/test/schema/namespace addition, edit and deletion; conflict selection and repair; namespace closure; deliberate data copy/transform with peer refs, components and explicit retractions; advisory reach without a veto; destination schema/contract validation; task rows admitted through the task owner. Demonstrate adoption and armed execution of the transferred program in S.
4. **Combined evidence and writer acceptance.** Require the frozen/reviewed task proofs plus root's additional tests on S's actual program and declared data inputs. Prove C′-only green cannot accept S, a repaired declaration joins the guarded delta, stale proof/task/dependency evidence refuses, a touched-row race refuses atomically, unrelated data writes preserve valid evidence, later required red stays red, and accepted outcome/evidence survives closure. Prove a data-only task can close without pretending it changed program. Remove merge's automatic reaching/coverage veto, keeping counts visible.
5. **Write-back, close and installed end-to-end proof.** Extend or explicitly delimit the existing file owner for the accepted schema/multiple-file cases; retain file-basis and reindex-equivalence checks. Prove accepted-but-not-written diagnostics, downstream renderer/recording failures beside the value, and accepted/declined close retiring C, C′ and S only after execution exits. Exercise one ordinary propose→open→select/transform→repair→test→accept→write-back→close path through both entrants. Measure cold/warm time and memory, count actual deletions, and record the loaded revision/arming evidence. Use focused installed `seon.test/run` requests per slice; root alone owns the final affected/platform/bulk proof.
