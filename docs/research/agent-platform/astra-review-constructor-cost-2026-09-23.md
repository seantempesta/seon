---
type: review
status: ready after fixes; production deletion awaits cross-context proof
created: 2026-09-23
---

# Independent review: error constructor cost

The system already retains Malli validators and has separate host and SCI wrapper installations. The smallest composition is to select the contract at installation, keep validation and profiling on every invocation, and let the existing affected-caller mechanism preserve the context through indirect calls.

**Verdict: ready after fixes.** The [diagnosis at `19e48135d`](error-constructor-cost-2026-09-23.md) identifies a credible, substantial avoidable cost at the right owner. Proceed as **A1-2**, with A1-1/1b and B2's ownership prerequisites, not as another constructor optimization or another G1 compile-once patch. The measurements support the direction; they do not establish a production performance pass or context safety. Resolve findings 1–4 below in the implementation assignment before deleting selection. No disarming, reduced validation scope, or constructor-specific exemption is acceptable.

## Evidence and dependency boundary

This is an independent source/evidence review, not a rerun of the author's experiment. Reviewed the root `AGENTS.md`, the data-oriented-clojure skill, [A1](../../prds/agent-platform/plan/lane-a1-projection-carried.md), the [shared plan](../../prds/agent-platform/plan/README.md), instrumentation history including `283ce58c9` and `c9870081f`, the current source, and the stopped lane's complete instrumentation diff. The research document is unchanged from its requested commit.

Review checkout basis: `7eac8e278bf7d4f4e7ed572bfb94009f44f0ca1f`, branch `refactor/agent-platform`. Current-source citations below use that checkout plus the stopped lane's diff. Instrumentation working-file blob: `d8b1aeb2230b26d12dea881c1d926946c32e0965`; diff is **37 added / 13 removed** lines. The research's runtime observations instead concern archive `ce73846828a5cc32798ef630b5a574f777646f30`. These are distinct evidence boundaries.

Malli first: the committed gitlink is `8725a8cbd9d595f4a970ce53a2eefdbe7211b96d`; the checkout is `56394c54e34415a333d6281c4bfddf7122d02bd5`. At `reference-code/malli/src/malli/core.cljc:2202`, `-instrument-f` constructs input/output/guard validators before returning its invocation closure; multi-arity installation constructs the per-arity wrappers at `:2284`. The public internal entrance `-instrument` at `:3125` accepts the schema, callable, scope, report and options. Locally available upstream `origin/master`, `e878083f385f35669eb2024366800e438d1aee49`, has the same retained-validator pattern at `:2219`. No claim about today's remote upstream HEAD is made.

The `valid?` exception-reporting helper and `:malli.core/invalid-schema-at-call` are changes in our checkout relative to the pinned gitlink, not upstream guarantees. Both versions retain notification semantics: a returning `:report` does not generally prevent body execution. Seon's non-returning rejection must survive this optimization. A1-8b's proposed `:refuse` is a separate, uninstalled mechanism here.

Seon's consumer `src/seon/instrument.clj:638` reads the retained schema, constructs the Malli wrapper at `:693`, and preserves refusal disposition in its outer catch. `arm-var!` retains that wrapper but still chooses a supplied wrapper at `:807`. `wrap-interpreted` already captures a compiled wrapper at `:505`. Thus the supplied inputs belong to installation: retained contract with its registry/predicates, original callable, caps, error policy/recorder, and profile identity. Reconstruction belongs to a changed relevant contract, predicate, callable or installation policy, not each call or an unrelated database commit.

## Findings and concrete changes

### 1. Separate the supported diagnosis from the unproven attribution and threshold

**Priority: required measurement correction.** The parent/cut comparison is persuasive: 6.650604 → 9.859875 µs, a 3.209271 µs increment, close to the independently measured 3.213604 µs positional constructor. Source inspection confirms two armed entries and no projection acquisition in the constructor body. `supplied-projection` at `src/seon/instrument.clj:551` scans arguments and then consults `schema/handed-projection`; the measured component includes that path and its compilation-mode binding, not just map lookups. The scan's work follows argument count and candidate paths on every host call.

The 1.869083 µs sum of the two isolated selection medians is about 58% of the site increment. That supports “likely the largest avoidable contributor in this measured host path,” not an exact CPU attribution. Independent medians, different allocation shapes, profiling, `apply` and JIT behavior are not additive. The report properly acknowledges this; retain that qualification in its headline conclusion. Validator compilation is already outside normal constructor calls. G1's landed `283ce58c9` removed the check-and-discard construction and delayed first-call construction; repeating that fix cannot remove this scan.

I decoded the locally retained `tmp/astra-error-cost/{confirm,breakdown,equal}.json` envelopes and checked the reported rows. They support the document's medians, allocation values and three sampled equalities. The confirmation envelope is marked windowed; the available value contains all seven reported timing rounds, not a claim that the oversized result's entire contents were inspected.

The candidate's ratio of medians is **+18.85%**, but its same-round ratios range **+17.59% to +21.22%**, with median **+18.79%**. Rounds 2 and 3 exceed 20% (+21.22%, +20.19%). The ratio-of-medians margin is only **0.076496 µs/call**, smaller than observed variation. The generic prototype was +21.51%; the later experiment changed original-body capture and direct invocation shape together. This is useful design evidence, not evidence that scan deletion alone meets the rule.

Concrete changes to acceptance:

- Name the historical E17 parent, current cut and actual installed fix as separate baselines. Report both parent→fix for regression recovery and immediate-parent→fix for the hot-path rule (>20% **or** >50 ms is a defect). Historical 7.66875 µs from another session is not a threshold substitute.
- Use paired repeated rounds and disclose dispersion/order effects; do not choose the best batch. Keep setup outside timing, check results outside timing, and measure the actual general wrapper with production caps/policy and profiling. The discarded-result loop is useful for diagnosis, but tiny isolated component times can benefit from JIT elimination and cannot establish application cost alone.
- If attribution matters to the implementation choice, compare retained-wrapper variants with the **same invocation shape**, then compare generic/direct invokes separately. No need for a new benchmark framework. Preserve the reproducible forms and exact loaded source/dependency identity.
- Retain allocation reporting (candidate saves about 8,912 B/call versus cut), while distinguishing allocation from retained memory. The report's source copies have unarmed database-view entry functions; supplement them with an installed caller observation before closing E17.

### 2. Make context ownership an explicit prerequisite, including schema-only changes

**Priority: blocks deletion of selection.** The proposed owner is right, but installation is more than closing over the host's current contract. A1-2 and README §3 already explain why: a copied compiled caller resolves its callee through the JVM Var. Wrapping only its SCI entry does not make that callee context-owned. The prototype does not exercise this problem.

There are two concrete source seams to resolve in the assignment:

- `src/seon/instrument.clj:400` unwraps only `::interpreted-original`. Passing a copied host wrapper straight through `wrap-interpreted` can therefore nest the context contract around the host contract. A1-2 must obtain the original callable through the existing Malli original-function seam when installing a host body, without leaving an unarmed public entry. Prove that values allowed only by the context do not get rejected by an inner host wrapper, and that profiling is not doubled at one entry.
- `src/seon/sci/eval.clj:1917` seeds the interpreted closure from function-digest differences. This read alone does not establish handling of a referenced schema/predicate change when the function's own row/digest is unchanged. Explicitly cover those differences in the existing dependency/eligibility owner. A contract-only function edit is necessary but insufficient evidence. Do not create another registry, ambient dispatcher or call-time program scan to compensate.

Retained compiled predicates also need the correct context's callable ownership; a retained schema is insufficient if its predicate still resolves a mutable host Var. State which existing installation path supplies that guarantee and prove it with distinct predicate behavior.

Required proof matrix, through real contexts and the canonical installation boundary:

| Scenario | Required observation |
|---|---|
| Host H and clusters P1/P2, same symbol and body, incompatible contracts | Direct calls obey H/P1/P2 respectively, in both installation orders and interleaved calls; choose values that distinguish all relevant contracts |
| P1/P2 direct input, output and guard violations | Invalid input never enters the body; invalid output/guard never escapes as success; all relevant contracts remain armed |
| Unchanged caller → changed callee, including a private callee | Indirect calls use the context's contract; the host caller retains H; affected callers are interpreted where required |
| Only a referenced schema or named predicate changes | Changed validation reaches direct and indirect calls despite unchanged function source; unchanged context retains its former contract |
| Host-bound affected caller | Named refusal when interpretation cannot preserve the contract; no silent host fallback |
| Re-arm/adopt P2 while P1 and an existing fork remain retained | No mutation of another context's SCI root or contract; unchanged reinstallation preserves wrapper identity; host reload is observed only at the permitted boundary |
| Different policies/recorders and profiling identities | Each refusal records/routes through its owning policy and cell; a context never borrows another context's recorder; recording failure remains loud |

Use current `:panic`/`:record` policies; A1 form 4's historical `:degrade` spelling is not an installed option to reproduce. The existing compilation-mode binding must not be widened across ordinary body execution as a shortcut: that would suppress nested contracts. If the ownership proof fails, keep selection and name the dependent A1/B2 gap; the microbenchmark does not authorize deletion.

### 3. Preserve the general wrapper target, not just the constructor's 20% result

**Priority: required scope/acceptance correction.** Removing selection from `arm-var!` removes this work for every host invocation using that seam. It does **not** prove that every armed function becomes faster: `wrap-interpreted` already retains its wrapper without this scan, validation costs follow each schema, and changed contexts may require interpreted callers. Preserve A1's separate **≤2× bare** `seon.id/valid?` target; a database-view site within 20% of its old body is not that proof.

Start with the existing general retained Malli callable plus C1 cell. Malli already handles fixed/multiple/variadic arities. Add general direct outer invokes only if a paired probe justifies them; keep unsupported arities routed through Malli's report, and do not duplicate its arity/validation engine or ship the prototype's two diagnostic arities as a special case.

Measure the A1 leaf probe and a small declared set covering zero/fixed/multiple/variadic arities, a projection-bearing call, and nested host/context calls. Report nanoseconds and allocation for surviving wrapper work, with identical validation/profile behavior. Keep invalid arity, invalid input/output/guard, thrown validator and thrown body behavior in the focused behavioral proof. Body exceptions must not be relabeled validator errors.

Complexity target: invocation pays call dispatch, profiling and validation proportional to argument/output schema and value shape; **zero world discovery or schema construction per ordinary call**. Installation pays affected contracts plus required reverse callers; unchanged contracts reuse their compiled schemas. Initial context installation may visit its installed population and must be measured separately. Do not move a per-call scan into an unconditional per-turn whole-program rebuild or install a second cache beside Malli.

### 4. Combine with the stopped lane; do not replace its work

**Classification: different changes, shared owner, compatible intent; integration overlap.** The uncommitted diff modifies `compiled-wrapper` only: it delays the refusal-result validator, adds minimal failure construction, handles `invalid-schema-at-call`, and catches refusal-construction failures. It leaves `supplied-projection`, `arm-var!`'s selection branch and its latest-projection atom intact. It is therefore **not the proposed cost fix**. It follows the G1 lane's G2–G4 failure-handling work, while compile-once G1 itself is already committed.

The primary scan deletion is in another form, so there is no evident direct hunk conflict. Any optimization of the common invocation/catch shell overlaps the stopped lane's semantics, however. Both share `src/seon/instrument.clj`, held by that lane in the ownership ledger. No second lane should edit it concurrently.

Have the orchestrator assign the combined work to its existing holder when resumed, or explicitly transfer the whole file after preserving the diff. Keep the reviewed refusal behavior and its Malli dependency paired; pin the actual dependency revision in the implementation slice. Do not cherry-pick the speed prototype over the stopped diff, revert it for a cleaner benchmark, or claim its custom exception handling is upstream Malli. Benchmark the resulting combined wrapper: the working Malli checkout adds validator-call indirection/catches absent from the pinned version, so the research's earlier timing is not automatically transferable.

This review does not independently approve every failure-path detail of that diff. Its delayed refusal validation and minimal fallback need the lane's own G2–G4 evidence. The combined proof must include missing/throwing refusal construction, throwing validators, invalid arity, unchanged body exceptions and both fault policies, while preserving non-returning rejection. A successful valid-arity benchmark cannot stand in for these cases.

## Disposition and landing boundary

The orchestrator should incorporate these findings into the owning A1 assignment before implementation: preserve the A1-2/B2 prerequisite, use original-callable installation, include schema/predicate-only changes, keep the ≤2× leaf target separate, and measure the combined G1/G2–G4/A1-2 wrapper. This is a refinement of the existing plan, not a new constructor mechanism or a demand to complete unrelated platform work first.

The implementation lane owes one focused `seon.test/run` request on its cluster branch plus the timed installed probes; the orchestrator owns platform/cold integration. This reviewer ran **no JVM, REPL evaluation, tests, adoption or publication**. Read-only evidence inspection and arithmetic do not establish runtime health, cross-context safety or adoption. Foreign source/test/dependency changes were left untouched; no foreign session was resumed or messaged. No shared-tree load was attempted, so no archive or worktree was needed.

Only this review is in scope for commit. Source net **0**, test net **0**. Scoped citation check: **1 document, 0 failures, 71 ms**; staged whitespace check passed. The repository-wide Markdown hook reported 51 findings; this is not a repository-wide lint pass. The save/tool call including that hook took 1.7 s, proportional to its repository-wide documentation scan, not a runtime or constructor measurement; the review itself requires only the scoped check. No push.
