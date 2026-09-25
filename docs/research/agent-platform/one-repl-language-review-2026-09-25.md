---
type: research
status: REVISE; revision-4 design review; no installation proof
created: 2026-09-25
reviewed-design: dc4f71d67fb78f1a258662a64c04fbb5fde19ba5
source-pin: 19a29c683
scope: composable REPL stages, agent custody, MCP retirement, shared record and print, cost, Q1-Q3
---

# One REPL language: revision 4 review

**Verdict: REVISE.** Keep MCP SCI and JVM evaluations on the agent's run, with reader and
evaluator supplied as values. Revision 4 makes that important correction, but does not yet
deliver its claimed single composition: it explicitly postpones reading once, gives tests a
separate composition, and retains MCP-specific printing and retrieval. The estimated
5 ms → 150 ms increase is a performance question to investigate, not an accepted price of
recording. These are design corrections before implementation, not evidence of installed defects
in a change that has not been implemented.

The existing owners are source submission, the agent's Flow proc, evaluation, settlement and
rendering. The smallest approach is to make their existing composition accept stage values and
use it from each entrant; retain transport and lifecycle differences around that composition.
Do not add a second runner to solve the test case or an MCP-only exception to solve cost.

## Evidence and authority

`D` below means `docs/research/agent-platform/one-repl-language-2026-09-25.md` at
**dc4f71d67** (revision 4, 936 lines). AGENTS.md citations use that same revision. Other source
citations are pinned to **19a29c683**, the design's source pin, unless explicitly named. Short source paths expand under `src/seon/`;
`mcp.clj` means `script/seon/dev/mcp.clj`. This distinction matters in the shared moving checkout.

Read the complete revision and AGENTS.md. The controlling laws are `AGENTS.md:130–145`:
core composes functions/stage values; MCP, tests and web are views of the agent; each form is
parsed and evaluated once through the same pipeline. Performance requirements are
`AGENTS.md:283–293`, including investigation of regressions over 20% or 50 ms. Applied the REPL
and data-oriented Clojure skills. Their historical MCP descriptions are not installation proof
for this proposed change. Earlier reviews remain available in Git at **7117f211f** and
**48e2882e1**; this file now reviews revision 4 rather than carrying earlier verdicts forward.

Source inspection covered the named submission/Flow, evaluation/finalization, MCP
projection/retrieval, test request and refresh seams. Dependency evidence: Clojure
**3bc2b3e91fdff462620d84bbdfd40379d9c562e5**, `clojure/main.clj:414–446`, really does compose
read/eval/print functions; Malli **befcd302a8e626a67d84b80aabee0cd1b38dadb6**,
`malli/core.cljc:3131–3153`, instruments a function against the supplied schema. Neither seam
establishes the stronger claims identified below. No dependency modification is needed.

No runtime probe, test, submission, provider call, adoption, publication, stop or reset was run.
The design's reported default faults and measurements are historical observations, not
reproduced here. Foreign lane health was not a dependency of this review.

## What is accepted

- **Both MCP modes use the agent's run.** D:349–370 correctly routes SCI and JVM through
  `evaluate-source!` → `submit-source!` → the agent's Flow proc → shared evaluation/settlement.
  The reader/evaluator choices differ; there must be no direct evaluator shortcut in the bridge.
  `agent.clj:635–723` confirms the existing submission owner, and `:525–560` the proc owner.
- **JVM evaluation is a stage, not a second recorder.** D:261–279 and D:304–307 correctly
  reject using `record-evaluated-call` as a JVM-only recording route. Returning an evaluation
  value to settlement is the right seam. Full error storage/delivery remains an implementation
  proof, not something calling `kernel/failure-value` alone proves.
- **Move printing out of the evaluator.** D:319–329 correctly requires the final result and
  verbatim stored shown bytes. The current candidate acceptance changes the value after evaluation
  (`sci/eval.clj:4487–4489`), so rendering earlier is genuinely wrong.
- **Use the existing execution owner.** Listen before submit, correlate/recheck by run id,
  retain ownership on timeout, and retire only owned agents. `agent.clj:1280–1305` explicitly
  distinguishes executor exit from a stop request. No bridge busy registry is warranted.

## Required revisions

### 1. Reading once cannot be an independent, deferred slice

**Location:** D:160–163, D:222–234, D:654 and D:843.

P3 first parses all code at submission, then its evaluator parses each source again. D explicitly
admits that `(require '[x :as a])` followed by `::a/k` can be read before the alias exists, yet
says no slice depends on S0. The JVM variant compounds this: submission uses the agent's reader
bindings while the evaluator uses host namespace mappings. A later correct read cannot recover
code rejected or interpreted incorrectly at submission. This violates the explicit parse-once
law and changes code semantics; it is not just duplicate work.

**Smallest fix:** make S0 a prerequisite of P3/P4's complete code path. Preserve raw source at
submission; in the existing ordered evaluation loop, obtain the next reader event using the
namespace/aliases left by the preceding evaluation and pass that event to the selected evaluator.
Keep prose segmentation distinct from semantic reading. SCI already accepts a supplied event
(`sci/eval.clj:4448–4461`); use that seam. Specify refusal and source preservation for malformed
trailing input. Price the call-site change before claiming M1 compliance. The future proof must
count reads/effects and cover namespace changes, aliases, reader conditionals and regexes.

### 2. P6 shares builders, but does not establish the same composition

**Location:** D:144, D:174–183, D:398–417, D:838 and D:855.

The design identifies the pipeline with an agent run, then says a test member is not a run and
has its own driver assembling receipts. Sharing `evaluation-facts` is necessary, but does not
show that tests enter the same read/evaluate/record/print composition. The proposed stage
contracts also do not fit the test stages: a text/namespace reader returning source maps is not
`resolve-test` returning a Var; a capture report is not automatically an evaluation.

**Smallest fix:** identify the single callable composition inside the existing agent path and
show the test adapter entering it with its isolated custody and body stage. Keep batching,
selection, retention and exit ownership with `seon.test/run`; do not require one wake and run
transaction per member merely to reuse functions. State the native input/result adaptation and
shared finalization/recording call explicitly. If the law is instead implemented by a run per
member, measure and fix that owner rather than rejecting it with the unverified 147 ms estimate.
Until this is specified, option C cannot claim “one path for tests.”

### 3. The 150 ms cost is unproven and potentially symptomatic

**Location:** D:410–413, D:463–476 and D:926–927.

`agent.clj:647–653` says carrying projection state changed an earlier source-turn measurement
from 728 to 147 ms. It provides no stage breakdown, sample set, current workload or memory
measurement. It does not establish that run transaction + wake + settlement intrinsically cost
147 ms. D also mixes evaluation duration and round-trip measurements. The claimed change is
approximately **30× / +145 ms**, exceeding both regression thresholds in AGENTS.md. Being below
250 ms, or below one second, does not waive them.

There are known places to investigate: projection acquisition, profile/config derivation,
transaction work and refresh. `turn.clj:4704–4710` describes an old per-form profile cost that
was already moved outside the loop; it is not a current per-turn measurement. The proposed JVM
mark walks every loaded namespace (`cluster.clj:253–275`), so its cost follows JVM size, not only
the changed input. The 3.3 s refresh is reported evidence elsewhere, not measured here.

**Smallest fix:** replace “every MCP call pays it” with an unverified hypothesis and a required
parent/change measurement of the same finite request. Separately report acquisition, read,
evaluate, record/transactions, print, completion, allocation and retained memory, including warm
repeats and a multi-form request. Attribute and remove redundant work at its owner. Price necessary
new durability separately from avoidable overhead; any deliberate remaining cost needs a root
ruling, not acceptance by this review. Keep the sub-250 ms aspiration without presenting it as a
pass criterion that supersedes the regression law.

D:474–476's alternatives are also insufficient: a root without an issue hides the dependency,
and skipping required refresh for externally submitted runs changes semantics. Fix incremental
refresh at its owner or declare the milestone blocked on that cut. Exercise the task-bearing root
case; `turn.clj:5295–5305` confirms that close can perform more work after settlement.

### 4. “Print once” must cover final refusals and specify record/print ordering

**Location:** D:166–172, D:319–325 and D:834.

Moving the three named render sites is incomplete. After installation gating,
`evaluate-sources` may replace the result with a disposition refusal
(`turn.clj:4772–4789`); `unrun-evaluation` prints it using `pr-str`
(`sci/eval.clj:3856–3862`). That leaves another pre-edge printer. Also D's numbered arrows say
record then print, while D:31 and P1 render before settlement. Existing `evaluation-facts`
consumes shown text (`turn.clj:126–128`), so the physical ordering needs an explicit explanation.

**Smallest fix:** route ordinary results, installation refusals and unrun/disposition failures
through the same finalization point after all result replacements. Constructors return native
evaluation values. Keep form-local render reads attached to that evaluation. Specify when the
semantic record is formed, when its final value is printed, and when exact shown bytes become
durable; do not silently add a second recording transaction or emit a success that later failed
to settle. Separate record construction from durable shown-text persistence if that is the
intended composition. Extend P1's proof and price to the surviving refusal producer.

### 5. MCP-only behavior survives, and Q3(b) does not preserve retrieval

**Location:** D:393–395, D:437–458, D:853–858 and D:879–885.

The retirement list explicitly leaves `runtime_status`'s printer, bridge-created branch errors
and the MCP artifact retrieval path. Thus “transport and stage choice, nothing else” is false
for the completed milestone. Reusing the general blob tier does not create a second physical
database, but the MCP-specific artifact production/windowing policy is still a second result path.

More concretely, `mcp-project` creates and stores the artifact (`cluster.clj:444–479`). P4 deletes
it. Keeping `get_value` then preserves only access to old surviving blobs, not retrieval of new
oversized results. Its current bridge caller also invokes `project-next-prepl-value!`
(`mcp.clj:765–769`), which P4 deletes. Q3(b)'s “no loss of today's retrieval” is therefore false.

**Smallest fix:** resolve Q3 before booking the deletions: route retrieval through the shared
cut owner, or explicitly retire the tool and return an honest unavailable-cut value for results
without retained backing. A requery must retrieve a held result; it must not replay an effectful
origin. Remove every caller of the deleted projection mark. Move status presentation through
ordinary render selection and branch refusals to their registry producer. Reprice these retirements,
and distinguish legitimate transport/session discovery from semantic MCP-only machinery. If
those cuts are deferred, label A/B partial migrations and withdraw the full compliance claim.

### 6. An armed Var's own contract does not prove stage compatibility

**Location:** D:174–180 and D:901.

Malli `m/-instrument` validates against the supplied function schema
(`reference-code/malli/src/malli/core.cljc:3131–3153`); it does not prove that an arbitrary
registered Var has the reader/evaluator contract the pipeline expects. A function with a different
or broader result contract can be perfectly armed and still return the wrong stage value.
The stated default evaluator contract is also abbreviated: `evaluate-for-install` requires
`:seon.test.accretion/candidate-context-request` as well (`sci/eval.clj:4442–4445`).

**Smallest fix:** declare the actual stage boundary inputs/results and validate them through the
existing schema/arming owner. Resolve the supplied symbol in the execution's program context and
refuse missing/incompatible declared stages before submission where decidable; validate returned
owning values at the shared boundary. Do not invent structural function-subtyping machinery or a
second stage registry. Price required schemas/callers, including the test adaptation in finding 2.

### 7. Root and session custody need explicit continuation and admission rules

**Location:** D:351–360, D:378–390 and D:928–934.

Using root for both modes makes its prior ending namespace shared state. A JVM-created namespace
may not be a database namespace or exist in SCI. Yet submission resolves `starting-ns` with a
schema-backed pull (`agent.clj:665–677`). “Previous run's recorded ending namespace” alone is not
a valid cross-evaluator continuation rule. Branch sessions have another incomplete seam: the
design has not identified the writer creating the assigned agent, while `arm!` explicitly requires
a committed agent (`agent.clj:1060–1062`). A session string is identity, not evidence that a branch's
inherited agent row is armed or owned by this caller.

**Smallest fix:** specify mode-appropriate namespace continuation from recorded evaluations,
with explicit request namespace winning and incompatible/missing contexts refusing by name.
Name the existing agent writer and timeout-result reader, their contracts and failure cleanup;
price only any actual missing seam. Keep settings and ownership with the agent owner. Do not
silently set permanent root `no-provider` merely to make one submission safe. Retain the existing
JVM-with-branch refusal (`mcp.clj:579–583`), and never release branch custody after a failed exit.
These are prerequisites for the proposed adapter, not permission to build bridge state owners.

## Q1–Q3 recommendations

| Question | Review recommendation | Guarantee, cost and constraint |
|---|---|---|
| **Q1: JVM placement** | **(a), root's Flow run.** Already consistent with the stated ruling; do not reopen it as three equally acceptable architectures. | Shared custody, recording, printing and actual-exit owner. The host evaluator is the differing stage. Price after findings 1/3/4/6/7. A stuck host call holds root; explicit host diagnostics remain separate recovery access. Option (b) changes the stated identity ruling; (c) resurrects the second driver and is rejected under the present laws. |
| **Q2: branchless SCI** | **(b), root's run**, for the orchestrator acting as root. | Retires mutation of the shared SCI base and uses root's private context and ordinary gate. Shares root's busy state and continuation rules. Enforce actual root custody; no anonymous caller is inferred to be a different agent. (a) is a viable narrower product constraint, not required by the composition law; (c) adds agent lifecycle without necessity for the stated root use case. |
| **Q3: retrieval** | **(c) for the full one-path milestone; (a) only for an explicitly reduced milestone. Reject (b)'s guarantee as written.** | The shared cut entrance preserves retrieval without an MCP result owner. Its cost is unpriced and must be traced before implementation. Deletion is smaller but sacrifices retrieval where no common retained result exists; report that honestly and never replay effects. Retaining an obsolete reader after deleting its producer does not preserve the capability. |

The diagnostic host prepl is a deliberate availability exception, not a model for ordinary
MCP evaluations. D:422–425 correctly forbids timeout fallback. Keep its exceptional unrecorded
status explicit in the response and outside “every MCP call is a run” claims. Protocol encoding
of already bounded response data is not another semantic printer; arbitrary diagnostic host
values still need an explicit bounded presentation contract rather than unlimited `pr-str`.

## Landing and verification limits

This review changes **only this file**. Production source/test delta: **0 / 0**. No tests or
runtime actions were run, and default was neither stopped nor reset. Runtime timing, allocation,
retained memory, installed adoption/arming and browser paint are **unmeasured**. This is a design
verdict, not a performance pass or installed-health approval. The unrelated
`reference-code/http-kit` state and concurrent edits to `src/seon/shell/jvm.clj` and
`test/seon/shell/jvm_test.clj` were preserved. No lane was contacted and no worktree was created.

Document validation is limited to the pinned source citations, whitespace/diff checks and the
single-file commit scope. The pre-commit hook was inspected: this documentation-only change does
not invoke tests. Commit uses explicit `git add` followed by `git commit --only --` for this path;
the resulting hash is reported with delivery. The path is released on completion. No source
implementation, push, merge or issue-index change is authorized by this review.
