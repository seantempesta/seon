---
type: design-review
date: 2026-09-26
status: complete source review; implementation not performed
verdict: REVISE
reviewed-commit: 29bb6bb3279b43c1093902b2ecd691b4b03c3693
scope: source review only; no implementation, tests, JVM probes, or cluster lifecycle operations
---

# Review of the eval-path audit

**REVISE.** clj-kondo before evaluation is the correct direction for declaration analysis and static entry checks. The proposed option B is not yet a correct composition: it conflates declaration analysis with evidence that a declaration executed, removes restoration without replacing observation of runtime writes, treats context-dependent arming as interchangeable, and assumes interfaces and settlement cardinalities that do not exist. Its deletion estimates and three-day estimate should be withdrawn until these dependencies are resolved.

The root rulings are inputs to this review: clj-kondo is the single declaration authority, and `(def m {:a 1})` is a program row at the REPL exactly as in files. The audit's alternative of narrowing file publication, and its request for another decision on data defs, should be removed. Runtime observation remains necessary to establish what executed; it must not decide which kinds of definitions count as program.

All Seon line references below are to **29bb6bb32**, including files subsequently changed by other lanes. Dependency references are to the pinned checkouts: clj-kondo `57252e07975710aa579b24f0d1b2b1e04195caa2`, SCI `743d1c4317d95af9d1af74928392b913c0d1b91d`, Malli `43c6dca8d2d8df3162b13725939954807be4a4ed`, and Datahike `67b00071e020a7ccf310985d01b28128cdadec34`. The audit was read end to end, as were `CLAUDE.md`, `src/seon/sci/eval.clj`, and `src/seon/sci/reader.cljc`; the requested language requirements, function classifier, settlement, callers, dependency seams, and relevant history were also inspected. Source establishes implementation, not installation. The audit's live observations and timings were not reproduced.

## Stage map corrections

The map is useful as an inventory, but it is not the execution order and omits several material transformations.

1. **The outer loop contains evaluation and its purported later stages.** `turn/evaluate-sources` invokes `sci.eval/evaluate`, then `gate-function-install`, then disposition handling (`src/seon/turn.clj:4959–4987`). Evaluation already calls `shown-result` (`src/seon/sci/eval.clj:4763–4766`). Thus current printing precedes the candidate-test gate, and a rejected disposition can trigger another print. Stage 7 is not simply after stage 6.
2. **“Pre-eval admission” can evaluate.** `declared-row` calls the supplied evaluator inside the schema registration delta (`src/seon/sci/eval.clj:3782–3787`). The normal branch then uses that result. This is not a purely static gate; the new map must distinguish analysis of a declaration from evaluating a computed schema value exactly once.
3. **Planning read and execution read have different resolver inputs.** Reply planning deliberately defers auto-resolution (`src/seon/cluster/reply.clj:63–95`); `one-event` can read in the live SCI namespace (`src/seon/sci/eval.clj:1110–1158`). Reader options and context advancement differ (`src/seon/sci/reader.cljc:119–130,396–448`). Carrying the planning form unchanged is not automatically equivalent to the execution read.
4. **Post-evaluation admission runs per top-level `do` child**, before a subsequent child executes (`src/seon/sci/eval.clj:1043–1071`). This arming order is essential. At present, the accumulated declarations still enforce one declaration across the evaluation (`:998–1001`), not one declaration per child.
5. **Settlement includes preparation, authoritative row validation, transaction, installation, and failure reporting.** `row-tx` checks rows against the writer's database (`src/seon/turn.clj:1258–1390`); `settle-batch!` transacts (`:3774–3871`); `resume-turn` consumes its report and installs only committed rows (`:5205–5237`). These are not interchangeable with the evaluation's projection or candidate branch.
6. **The reply port does not currently carry `:db-after`.** `resume-turn` has it, but its report calls omit it; the report constructor contains outcome/count/run/refusal fields (`src/seon/turn.clj:5480–5496`). `evaluate-source!` reduces an outcome report to a run id (`src/seon/cluster/agent.clj:794`). D9 requires an end-to-end report change, not a local MCP substitution.

The corrected map must show both kinds of gates: static entry eligibility before the relevant evaluation, and runtime/candidate-proof/writer decisions afterward. It must also show the evaluated value surviving every later failure. “Refusals come before side effects” is not a guarantee of the present proposal.

## Finding-by-finding review

### D1 — Competing declaration authorities: confirmed; deletion scope overstated

The reader derives function and test declarations by head inspection (`src/seon/sci/reader.cljc:252–316,364–383`). Runtime admission selects functions or test-marked roots (`src/seon/sci/eval.clj:561–605`). The file/indexer classifier also admits literal and computed `def`, `defonce`, and `defmulti` (`src/seon/fn.clj:259–272`). The disagreement is directly established by source; the live table is not needed to establish the defect.

Two downstream fixes must land with classification. `missing-contract` refuses a function row without a spec regardless of its being a data def (`src/seon/sci/eval.clj:1003–1009`), and the no-spec branch of `gate-function-install` **removes the program row** (`src/seon/turn.clj:3417–3418`). Merely changing the classifier would still fail file/REPL parity.

Empty arglists are not a sufficient exemption rule: the classifier explicitly documents computed/point-free functions as arglist-less (`src/seon/fn.clj:260–264`). Define declaration kind and effective-contract eligibility from the analyzer's facts, including the unresolved case; do not reinstate `fn?` as a second declaration authority or silently exempt callable definitions from contracts. Preserve data rows, dependency edges, metadata, and digests through settlement. Removing classification does not by itself justify deleting the mutation-observation helpers named in D1; see D2–D3.

### D2 — Positional matching: confirmed; symbol-only replacement refuted

`source-offset` translates source coordinates, and `program-change` uses symbol, occurrence count, form span, and rebound evidence (`src/seon/sci/eval.clj:607–672`). Some translation may be avoidable when analysis and reader events share provenance, but the present work does more than join two representations of a symbol.

For example, given an existing `m`, analyzing `(if false (def m {:a 2}) nil)` yields a declaration candidate whose symbol can still resolve to the old Var. A definition inside an uncalled function also need not execute. Repeated definitions and `defonce` require occurrence/execution semantics. Resolving the symbol alone cannot establish which authored source produced the current root.

SCI provides `sci.core/resolve` (`reference-code/sci/src/sci/core.cljc:826–827`), but it is lookup, not a declaration-execution witness. Moreover, SCI's root-binding helper marks rebound state, while a `def` resets metadata and clears that mark (`reference-code/sci/src/sci/impl/utils.cljc:394–402`; definition evaluation at `impl/evaluator.cljc:25–55`). `:sci/rebound` alone therefore does not cover computed `eval` definitions. Keep occurrence/provenance evidence until a smaller complete replacement is demonstrated. No mutation-journal API was verified in the inspected SCI seams.

### D3 — Restoration caused only by post-eval admission: partially confirmed; proposed reduction refuted

Capture and restoration are substantial (`src/seon/sci/eval.clj:701–861`). Capture retains persistent namespace maps and walks generation-owned interns; it is not a deep copy of every namespace on every form (`:504–542,829–840`). Static checks can prevent some rejected bodies from running and reduce recovery work.

They cannot predict runtime exceptions, computed definitions, alias/import changes, removals, or the settlement writer's refusal. The target itself promises to catch a stored Var changed without an analyzed row; deleting the diff without a replacement leaves no way to discover that write. Native mutation wrappers (`src/seon/sci/eval.clj:326–343,418`) are not evidence that every computed definition is observed.

Retain observation and restoration of actual affected state separately from declaration classification. Preserve Var identity, unbound versus bound-nil roots, namespace changes, and ordered rollback of refused evaluations (`src/seon/turn.clj:5064–5081`). Restoration does not undo arbitrary I/O or mutation inside referenced objects, as its current documentation already acknowledges (`src/seon/sci/eval.clj:719`). Option B must not promise that a later failed test or transaction erases those effects.

### D4 — Repeated arming: confirmed; “once per definition” needs a world

The four named sites exist (`src/seon/sci/eval.clj:1014,1817–1818,4800–4810,4828`). Four is not an upper bound: the candidate copy forks through code that re-arms interpreted functions for its receiving connection (`:3766–3771,4952`).

Arming captures projection, instrumentation configuration, call-default snapshot, fault recorder, and definition digest (`:1347–1371`). A candidate branch's wrapper cannot simply become the agent's wrapper unless those inputs agree. `instrument/wrap-interpreted` unwraps the underlying function and reuses its profiling cell, but constructs the context-dependent wrapper (`src/seon/instrument.clj:418,462–521`). Repeated calls are not necessarily stacked wrappers, and different custody is not redundant work.

Use one arming owner, eliminate repeated work for the same root and effective inputs, and retain re-arming where a different world requires it. Keep the child armed before the next child can call it. The plan needs this contract before deleting any of the three sites.

### D5 — Repeated projection work: confirmed; several calls enforce different boundaries

The five named sites exist (`src/seon/sci/eval.clj:943–945,3823–3837,4727–4745,2035–2055,4800`). The discarded return in `definition-row` is still a validation call; it is not dead work merely because its value is unused. `schema/projection-with-function-contract` validates through the declaration projection owner (`src/seon/schema.clj:3436–3451`).

The evaluation fork, candidate branch, receiving context, and writer's actual database need projections of their own inputs. The writer derives its candidate projection at settlement (`src/seon/turn.clj:1340–1353`), where it can detect a changed basis. It cannot accept an earlier projection without checking that basis. Also, initial function arming currently receives the earlier projection while the candidate path advances it; deleting the latter without supplying the correct projection exposes stale contract resolution.

Retain one pure row-to-projection owner and carry/reuse its value where the inputs agree. Replace the global “once per admitted row” claim with explicit database/projection inputs and recomputation events.

### D6 — Three equivalent program-movement checks: refuted

The sites exist, but answer different questions:

- `call-detecting-program-change` compares loaded JVM identity across execution (`src/seon/sci/eval.clj:1556–1617`).
- `loaded-program-current?` verifies that a context's acquisition agrees with the JVM program; `acquired-database?` checks branch program content/revisions (`:3470–3523`).
- `installation-covers-program-change?` checks whether the settlement datoms account for the program change being installed (`:1950–2001`).

A stable branch does not imply stable compiled Vars, and a successful transaction does not establish that its installation covers every intervening change. Move acquisition checking to its owner, but preserve each guarantee under B. A raw commit-id comparison also reacts to receipt-only commits; retain program-content/revision semantics. Removing copy-back under C could retire a check, but that is a separate ownership change, not proof that the check was redundant.

### D7 — Acquisition inside evaluation: confirmed; “mid-form” is imprecise

The acquisition block is inside `evaluate`, before the submitted body's SCI evaluation (`src/seon/sci/eval.clj:4533–4558`). Moving acquisition to the world supplied to the evaluator is appropriate. `fork-for-turn` does not currently perform that acquisition (`:3237–3270`), and lazy function acquisition can also call the acquisition owner (`:3633–3642`). Moving only the named block is incomplete.

Acquire the base world before creating/using the turn fork, and give every entrance an explicit prepared world: agent evaluation, tests, preview, and render evaluation. Preserve the branch-head policy and earlier uncommitted declarations: the current outer loop refreshes its database and overlays pending rows (`src/seon/turn.clj:4913–4920,5021–5023`). Define how intentional durable writes during a run interact with that held world before reducing checks to once per turn.

### D8 — Three bindings of one result: partially confirmed

`shown-result` binds the ordinary result handle (`src/seon/sci/eval.clj:4053`), and the turn loop binds that handle again (`src/seon/turn.clj:5007–5010`). This is a real duplication.

The error path allocates a **new** result id and handle for its offending value (`src/seon/error.clj:485–490`), renders/stores it, and binds it (`:508–512`). It is not a third binding of the same evaluation handle. It is another result-rendering path that needs to compose with the common owner. Removing its callable indirection without providing that behavior loses error-value retrieval. Bind before rendering can use the handle, retain the value even if rendering fails, and remove the ordinary second bind only after disposition/refusal handling no longer replaces that value.

### D9 — MCP rereads the connection: confirmed; the cited applied-before-durable race is refuted at this commit

`run-form` reads `@connection` and reconstructs history/receipts (`script/seon/dev/mcp.clj:445–457`). Carrying the settlement's held database/report is preferable: it gives the reply an exact basis and avoids mixing a later observation into this run.

The proposed causal explanation is stale. Ancestor commit `de67fdd1a` removed reply-when-applied after the no-value failure. At the reviewed commit, `settle-batch!` uses the ordinary transaction path (`src/seon/turn.clj:3842`); `seon.db/transact-call` waits for its result (`src/seon/db.clj:4754–4760`). The pinned Datahike writer awaits commit, updates the connection, then acknowledges the transaction (`reference-code/datahike/src/datahike/writer.cljc:245–264,417–426`). A successful acknowledgement here is not evidence of the historical receipt race described against `fcee85161`.

Do not treat the two earlier landing reports as a reproduction at 29bb6bb32. Preserve the useful report-transport change, correct the rationale, and change the report schema, producer, graph reply, `evaluate-source!`, and MCP consumer together. The report presently discards the needed outcome (stage-map correction 6).

### D10 — Repeated reads: confirmed as an inventory; counts and blanket deletion need correction

The named reader calls exist, but are conditional and serve different entrances. `source-key` also serves historical refresh (`src/seon/turn.clj:1929–1940`); repair reads are not a mandatory pass over every successful form (`:3169–3182`). `install-row!` avoids replaying a test already marked evaluated and similarly guards deletion replay (`src/seon/sci/eval.clj:1849–1850,1873–1875`). Do not describe those as unavoidable replay on the ordinary path.

Carrying source, coordinates, and analysis is appropriate. Carrying a deferred-resolution planning AST into SCI is a semantic change unless its resolution is made equivalent; see the stage map and alias analysis below. Keep sequential semantic reading where an earlier evaluated form changes how a later form reads.

The inventory also misses Seon's additional parses around kondo: `runtime-analysis-batch` invokes `referenced-program-namespaces` (`src/seon/fn.clj:985–988`), which parses sources (`src/seon/fn/analyzer.clj:892–908`), and `analyzer/analyze` obtains `parsed-facts` (`:783–791,631–672`), which parses again for source facts. Inspect the fork's exported analysis/node seams before adding another parser. “One semantic read for SCI” and “one analysis invocation” must be separately measurable; retaining all these parses does not satisfy a literal parse-once claim.

### D11 — Candidate tests have duplicate orchestration: confirmed; not a second interpreter

`run-candidate-test!` calls the existing `evaluate` and test runner (`src/seon/sci/eval.clj:4892–4914`). It is another setup/acquisition/proof route, not another evaluator implementation. Its test lookup supplies source without the test namespace's complete require context (`:4894–4903`), supporting the missing-context diagnosis; the cited failure was not reproduced here.

The ordinary `seon.test/run` request captures a committed program and acquires test worlds (`src/seon/test.clj:2199–2226`). The current candidate is an already evaluated root plus pending rows in the turn's working database. Calling `test/run` on the original commit tests the old program. Persisting only the current function omits preceding schema, namespace, and test changes. Re-evaluating the definition source can repeat its initializer and lose a captured private value.

S5 must define how the canonical test owner receives the complete candidate program and the correct evaluated function/world, with isolated custody, without replaying the declaring form. Extend the existing owner if needed; do not create another runner. Preserve test selection, seed auto-checks, negative/unknown evidence, and exact definition/input digests. Until that seam is proven, keep the existing gate; its deletion is a consequence of the replacement, not the first step.

### D12 — Dead contract branch in `declared-row`: confirmed

`row` yields schema, namespace, or deletion rows (`src/seon/sci/eval.clj:469–484`). The schema-delta path does not turn one into a function row. Therefore the `:seon.fn/spec` branch applying `with-contract-facts` (`:3839–3849`) has no producer in this path. Delete it as a bounded cleanup; do not generalize that conclusion to `definition-row`'s live validation.

### D13 — Dead parameters and observer read: confirmed

`bindings` takes six arguments but uses the context and before-values only (`src/seon/sci/eval.clj:1073–1084`). Its callers calculate `built-in-calls` solely to supply an unused argument (`:4489,4758`); the local closures are at `:4345–4348`. Remove those arguments and local reads. This does not authorize deleting the kernel observer used elsewhere.

### D14 — Repeated evaluation construction: confirmed; proposed input is insufficient

`success-evaluation`, `failed-evaluation`, and `unrun-evaluation` repeat common fields (`src/seon/sci/eval.clj:4075–4153,4181–4216`). Share construction of common facts, but `{value, error?}` does not describe whether evaluation ran, whether it returned nil/false, which namespace it ended in, its bindings/restoration evidence, admission record, timings, or later-stage diagnostics. Preserve those distinctions as declared data. An unrun form and an executed form with a failed gate must not become indistinguishable.

### D15 — Doc/dir concerns in the evaluator namespace: confirmed, with a narrower move

The block includes selection, docstring/contract lookup, directory values, rendered results, macro adapters, and source access (`src/seon/sci/eval.clj:2328–2650`). It is not 303 lines of one renderer. Move document data/selection to the existing REPL/document owner and rendering to its declared face; retain thin SCI binding adapters where necessary. `m/function-schema` is already used for contract lookup (`:2422–2433`; dependency owner `reference-code/malli/src/malli/core.cljc:3130`). Do not add a parallel contract lookup implementation.

S7's context move is a separate concern. It must convert schema predicate/generator symbols, constructors, injection references, and all callers atomically, preserve qualified keyword identities, and check dependency direction. Moving a contiguous range to a new namespace is not sufficient evidence that the resulting namespaces load.

### D16 — Acquisition records faults behind an LRU “once” cache: confirmed; signature-dedup equivalence unproven

Acquisition records faults (`src/seon/sci/eval.clj:2705–2772`) and caches a delayed recording outcome keyed by program and target (`:3525–3568`). The recorder already calls the fault transaction owner (`:2736`); this is not an entirely separate fault writer.

The cache evicts thrown failures, but retains returned maps, including a returned recording-failure result. Reacquisition can consequently reuse failed recording evidence instead of making the failure locally actionable. The error owner distinguishes occurrence information as well as signature (`src/seon/error.clj:1513–1588`); its existence does not prove the same once-per-program/target behavior. Specify durable recording, recurrence, custody, and delivery at that owner, then remove the effectful cache. This repair does not depend on C or on removing copy-back. Other local acquisition LRUs at `src/seon/sci/eval.clj:3281–3288` also need accounting under the central-cache law rather than being silently moved by S7.

## Missing findings and plan obligations

### Preserve the evaluated value through later failures

The most consequential omission is totality. `call-detecting-program-change` replaces a successful result when identity changes (`src/seon/sci/eval.clj:1615–1617`). Post-evaluation admission and rendering exceptions flow into `failure-result` (`:4690–4787`), and the outer catch can rethrow (`:4789–4792`). The turn's invalid-disposition path explicitly replaces an executed result with an unrun evaluation and prints again (`src/seon/turn.clj:4970–4987`). A phase failure is settled against the first ordinal rather than carrying the fold's successful prefix (`:5167–5177`).

The target's `{value | throwable}` must become a declared outcome that distinguishes a thrown body from diagnostics after a returned value. Preserve the actual value, including nil and false, alongside gate/render/record/install faults; preserve every completed form and its ordinal if a later stage fails. Retain retrieval handles even if printing fails. This is an acceptance condition for the stage refactor, not a later cosmetic constructor cleanup.

### Runtime metadata is another authority

After kondo analysis, `definition-row` overwrites the analyzed contract and workload from Var metadata and merges runtime test markers (`src/seon/sci/eval.clj:933–940`). The audit counts three classifiers but misses this later rewriting of analyzed facts. It is incompatible with an unexplained promise that kondo analysis pre-gates the final contract. Use the shared analyzer/effective-contract owner; define how evaluated metadata is checked against it, and refuse unresolved/mismatched authored facts with their original evidence. Do not evaluate metadata expressions a second time to manufacture a static answer.

The effective contract must respect language requirement 1.12: explicit or completely derived, with unresolved derivation reported by name. Use Malli's compiled validator/explainer and function-schema APIs (`reference-code/malli/src/malli/core.cljc:2672,2688,3130`) through the existing schema owner. No new handwritten contract walker is required by this review.

### Row cardinality is an end-to-end change

The target changes the restriction from one declaration per evaluation to one per child. Yet evaluation selects `(first var-rows)` (`src/seon/sci/eval.clj:4724–4725`), the working database carries singular `:seon.program/row` (`src/seon/turn.clj:5021–5023`), and installation likewise consumes one row (`:5215–5221`). Merging different row families into one map is not a solution: `canonical-row-in` refuses multiple identity families (`src/seon/program.cljc:1146–1153`).

Either retain the current restriction during the structural cut and name it as unfinished, or convert ordered declaration results, receipt schemas, pending-state projection, writer preparation, installation, and rollback in one loadable slice. Do not remove the admission check and silently persist only the first child. Keep one evaluation of the submitted form, its correct return value, and each declaration's distinct occurrence; do not solve cardinality by evaluating the form again.

### The proposed analysis API is not installed

`seon.fn/source-rows` takes database, row shapes, one namespace row, **a source string**, and declared attributes (`src/seon/fn.clj:1536–1550`); it calls `runtime-analysis-batch` with a singleton request. Its output filters to function/test rows (`:1570–1572`). It is not the asserted `[event] × ns-context → [[row]]` operation, and it does not supply schema/deletion/namespace operations for the target's entire pipeline.

Extend the existing analysis owner and its named schemas before converting callers. Preserve event-to-source provenance and findings even for expressions with no declaration rows. Specify the operation facts needed for schema and deletion calls without introducing another syntactic declaration classifier. Preserve fully qualified authoring, anonymous evaluation-owned functions, and task/test/seed edges required by language requirements 1.10–1.15 and 7.1–7.9b; explicitly identify dependent work where those features are not installed. Task runtime entities must remain outside program rows (7.13). Removing the reader's metadata path must not silently drop these facts.

### Existing smaller helpers and remaining parses

`row-bindings` reconstructs stored namespace context (`src/seon/sci/eval.clj:1208–1225`) already represented by `seon.fn/stored-namespace-context` (`src/seon/fn.clj:274–282`). Consolidate that transformation, preserving absent-field behavior. Prefer the existing program row-identity owner over additional local row-family tests (`src/seon/sci/eval.clj:896,1227`).

SCI already provides namespace snapshots/bindings (`reference-code/sci/src/sci/core.cljc:701,740`) and root operations; Seon already uses `sci.vars/hasRoot` (`src/seon/sci/eval.clj:540`). The sentinel also represents held unbound state during restoration/acquisition (`:707,1893,3141,3187,3903–3904`). Removing value classification does not eliminate the need to distinguish unbound from nil. Replace representation only if these uses are converted; “SCI has hasRoot” alone proves no deletion.

`::skip-contract-install?` is set during acquisition (`src/seon/sci/eval.clj:3065–3066`) without a reader in the reviewed source. Remove that dead input with its owner rather than preserving it through a namespace move. The extra analyzer parses described under D10 belong in the parse inventory and cost model.

### Rendering recursion needs its actual guarantees checked

Reusing `evaluate` for render forms is the right architectural direction, but is not alone proof of read-only, recorded rendering. `evaluated-source` strips the ordinary turn/evaluation identity fields and sets `::rendering?` (`src/seon/sci/eval.clj:3968–4009`); the flag suppresses recursive renderer setup (`:4061–4062`). It is not a mutation guard. The plan must name the actual read-only enforcement and recording owner or record this as an unresolved defect, and land a regression that checks both. Do not label the whole recursive path sanctioned solely because it calls the common evaluator.

### Cost must follow inputs

Batch analysis amortizes invocation overhead, but `runtime-analysis-batch` repeatedly scans accumulated text for newlines and appends to a growing string (`src/seon/fn.clj:989–994`). That construction can grow quadratically with a long batch. A linear fold carrying the current line offset and source fragments is a smaller algorithm within the same owner. The reported 12–25 ms measurements of single forms do not establish a whole-run bound or the cost of all projections/arming/tests.

Measure analysis, semantic reading, projection derivation, arming, test-world acquisition, printing, and settlement independently, including a no-definition run and a long mixed run. Count repeated work by equal content inputs, not just call sites. Retain the writer's authoritative checks and external dependency caches; do not add another result cache to conceal repeated derivation.

## Does whole-run kondo analysis see an earlier require?

**Yes for the supported static literal require in sequential analysis; no general equivalence to the executing REPL is established.** This can be verified from the pinned fork, which is present and selected as a local dependency (`deps.edn:17–18`):

- `analyze-expressions` processes expressions in order (`reference-code/clj-kondo/src/clj_kondo/impl/analyzer.clj:4349–4375`).
- The analyzer dispatches top-level `require` to `analyze-require` (`impl/analyzer.clj:3470`). That owner explicitly supports quoted require specs and registers their namespaces/aliases (`impl/analyzer/namespace.clj:734–782`, especially `:766`).
- `reg-required-namespaces!` updates the analysis namespace state (`impl/namespace.clj:417–426`), which `resolve-name` reads when qualifying a later symbol (`:756–770`).
- Seon's batch builder concatenates consecutive requests in the same namespace and invokes the analyzer once (`src/seon/fn.clj:963–1006`). It does not reset that group between an earlier literal require and a later definition.

Thus `(require '[clojure.string :as s])` followed by a definition calling `s/upper-case` has a source-supported resolution route. This verifies the narrow claim; it is not a recorded runtime or end-to-end Seon test result.

The limits matter. Computed require arguments are not generally executed by the analyzer. Static analysis is not evidence that the earlier require succeeds or executes. SCI reader resolution of `::s/key` and syntax-quote depends on the actual namespace at read time. Namespace changes and returning to a previously visited namespace require per-event context, not a guessed initial namespace. Seon's row builder collapses namespace contexts by namespace name (`src/seon/fn.clj:1501–1507`); check that later aliases cannot retroactively rewrite earlier declaration metadata. Its synthetic require reconstruction also collects referred target names without their local rename mapping (`:838–868`), so stored resolver-context parity needs a renamed-refer case.

The implementation proof should submit one finite run to the existing agent path and independently inspect the analyzed call target, effective contract, namespace row/digest, returned value, and settled declaration. Cases: a literal alias used in a later body; aliased test/contract metadata; auto-resolved keyword and syntax-quote; renamed refer; namespace A → B → A; alias rebinding; an unsuccessful or conditional require; computed require. Compare the batch artifact with the namespace in which SCI actually reads/evaluates each event. Where analysis cannot know a runtime resolver change, return a named unknown or use a reviewed analysis boundary; do not silently guess. These are proposed regressions, not probes run in this review.

## Smallest corrected composition

Retain B's direction, with fewer deletion promises. Do not adopt C merely to make B's counts work.

1. Acquire a content-valid world at the context owner, before the turn fork is used. Carry database basis, projection, resolver context, custody, and loaded JVM identity explicitly.
2. Have the existing reader/analysis owners produce source events, declaration facts, findings, and provenance. Batch static analysis where resolver assumptions are known. Preserve the distinction between a segmentation read and the semantic SCI read; removing a redundant read requires proving their inputs equivalent.
3. Fold events/children in order. Compile/check entry eligibility against the projection produced by preceding admitted operations. An earlier schema registration can change the next contract's meaning; a single gate over the initial projection is insufficient. A refused static gate does not execute that body.
4. Evaluate once through SCI. Observe which analyzed occurrences executed and any actual unmatched writes. Analysis owns declaration identity; observation owns execution evidence. Keep the current observation/restoration owner until a complete smaller dependency seam is demonstrated.
5. Construct final candidate rows through the shared row/effective-contract owner and arm before later code can call a new function. Use the canonical test owner for the complete candidate world. Keep runtime failures beside the original result; distinguish private evaluation success, admission/proof refusal, and shared acceptance.
6. Bind each result once before its renderer can use it, print through the common owner, and carry exact shown text and diagnostics. Settle completed evaluations and rows through the writer, validate against the actual basis, and install only accepted rows with the correct receiving-world instrumentation.
7. Return the settlement observation through the graph/agent/MCP chain. A caller should not have to reconstruct this outcome by rereading the connection.

The remaining work is proportional to submitted source, executed/observed mutations, affected projection dependencies, selected proof inputs, and settlement datoms. Runtime mutation observation is not yet shown to be O(declared rows). One owner can legitimately run on several distinct immutable worlds; “one owner” does not mean “one invocation globally.”

## Recommended slice order and required regressions

The published S1 → S2 → S3 order is unsafe: it removes execution matching before pre-analysis, arming before the candidate-world contract is settled, and reader facts before an equivalent artifact reaches consumers. Split the labels into loadable cuts. Introduce/convert/remove within the same owning modules; no parallel evaluator or compatibility namespace. Each cut must include its schema and first-party caller changes. The following are regression requirements for the future implementation, **not tests run by this review**.

| Order / original slice | Loadable cut and deletion boundary | Regression that must accompany the behavior change |
|---|---|---|
| 1 — S6, reply portion | Carry the actual settlement outcome/basis through the existing report schema, graph, agent entrance, and MCP. Keep current evaluation semantics. | A successful value, a returned error value, and a refused settlement each return the correct run/ordinal observation. A later connection commit cannot change the reply's basis. Check producer-to-MCP propagation, not a fabricated report. |
| 2 — S4, outcome portion; S1 dead cleanup | Establish the common outcome with executed/unrun distinction, original value, diagnostics, and completed-prefix preservation. Remove D12/D13 without changing admission. | Nil/false remain values; a renderer, gate, disposition, or record failure cannot replace a returned value; failure on a later form preserves earlier outcomes and correct ordinals. Retrieval still works when printing fails. |
| 3 — S6, event portion | Carry source/provenance and analyzer artifacts through all relevant entrances; remove only reads proven to have equal resolver inputs. Expose the existing batch owner with named schemas. | The alias/context cases above, malformed later source, Unicode source coordinates, and a render/preview entrance produce correct source attribution and namespace resolution. No duplicate body/initializer evaluation. |
| 4 — S1 + S3, authority and static gate | Convert reader/runtime declaration classification to kondo facts and move static checks before evaluation together. Fix no-spec data-row persistence and metadata authority in the same cut. Retain observed mutation matching/restoration. | File and REPL rows agree for data, atom-valued, computed callable, multimethod, defonce, and ordinary defn/test declarations. Malformed/missing required effective contracts refuse before an observable body effect; `:warn` diagnostics remain truthful. Conditional/unexecuted and repeated definitions cannot adopt the wrong source/root. Task/seed metadata survives. |
| 4a — S1/S3 cardinality, if included | Convert every singular-row consumer before allowing several top-level children to declare. Otherwise retain and document the current bound; do not claim the target is complete. | Two different declarations in one `do` both settle; the second child can call the armed first; a later refusal preserves the actual returned/thrown outcome and restores the rejected program changes. No lost row or repeated initializer. |
| 5 — S5 | Make the existing test owner consume the complete candidate world, then delete candidate-specific setup/runner/acceptance code in that same cut. Keep necessary receiving-world arming until S2. | A test with its own requires sees the candidate, preceding schema/ns/test rows, and a captured private value where supported. Initializers execute once. Failure leaves the accepted agent program unchanged and records exact tested digests/inputs. Seed checks and unknown evidence cannot become green. Test effects remain in test custody. |
| 6 — S2 | Consolidate projection and arming derivation by actual input identity; remove duplicate ordinary-result binding and compose the distinct error-value path. | Invalid calls fail before body entry immediately after definition and after settlement. Candidate/agent faults and injected defaults use their own custody; definition profiling identity survives. An earlier schema change affects the next function. Writer conflict still refuses. Error offending-value and ordinary result handles both resolve. |
| 7 — S3, acquisition/recovery portion; D16 | Acquire before fork/use for every entrance, retain loaded-JVM and settlement-coverage guarantees, route acquisition recording/delivery through its owner. Delete restoration only for cases whose complete replacement evidence has been proven. | Program adoption preserves unrelated private state; concurrent JVM adoption is diagnosed beside the value. Receipt-only commits do not rebuild the context. Runtime eval/rebinding/unmap and failed settlement restore the correct affected roots/namespaces, including unbound/nil. A recording failure remains visible and cannot be hidden by a cached failed outcome. |
| 8 — S4, remaining decomposition | Split the now-established stages and share common construction without changing their contracts. | Reuse the established outcome/ordered-run regressions; add no tests that merely count extracted functions. Confirm ordinary, thrown, interrupted, and pre-entry-refused outcomes retain their meaning. |
| 9 — S7 | Move doc data/render responsibilities and context ownership only after boundaries stabilize; convert all qualified schema/predicate/generator/caller references and preserve keyword identities. | Namespace loading/acquisition succeeds through the real entrance; doc/dir/source and both relevant render faces retain their values and contracts. Render forms are read-only and recorded through the declared owner. Existing behavior regressions cover the mechanical moves. |
| Deferred — S8 | Do not include C's persistent-world replacement in B. Separately design lifetime, rejection, private-state survival, transaction conflict, and resource ownership before deleting copy-back or rollback. D16 is not deferred with it. | A future C cut needs failed-merge/settlement isolation, repeated turns with private closures/atoms, held Var identity, loaded-program overlap, and actual resource retirement proof. Source deletion alone is not acceptance. |

Run the future behavioral proofs through canonical injected worlds, real SCI, and armed contracts; one regression per behavior class with relevant cases. Source publication, adoption, and agent/MCP behavior are separate evidence. Root owns platform/affected integration and the final bulk tier; this review authorizes no test run or cluster operation. The slice estimates must include the complete candidate-test seam and row-cardinality choice rather than subtracting code before its responsibilities have an owner.

## Review limits and delivery

This is a pinned source/design review. No tests, JVM evaluations, timings, memory measurements, reloads, resets, or default-cluster operations were performed. Consequently it makes no claim that a proposed replacement is installed or that the audit's live regressions are green. Foreign lanes changed HEAD and other source files during review; all evidence above remains pinned to 29bb6bb32, and none of their files or sessions was modified. The only authored deliverable is this review document.
