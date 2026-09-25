---
type: research
status: independent review; proceed after fixes; no installation proof
created: 2026-09-25
reviewed-design: 8f6eb00ff
scope: one REPL entrance, recording and rendering, program changes, co-location, and the dogfood milestone
---

# One REPL language: keep the composition, repair the milestone

Seon already has a source submission owner, SCI evaluation, turn settlement, test capture,
program rows and a shared value renderer. Compose those owners into one read → eval → record → print
path; remove the MCP-specific evaluation/result machinery as its callers move.

**Verdict: proceed after fixes.** The direction is right. R1–R4+R7 is not yet a complete or
landable dogfood milestone. It leaves test execution outside the path, assumes an unavailable
write-back operation, overlooks existing definition/recording defects, and does not price the
new durable-result work. Prefer C1 option 1: retain schema-owned render pairs and expose their
reverse edges. Move dial defaults only after separating them from initialization data.

## Evidence boundary

Read AGENTS.md, the complete first-pass design, language requirements, decoration language v0.3,
and both earlier Astra language reviews end to end. Read all of `src/my/program.clj` and
`script/seon/dev/mcp.clj`; read the complete named evaluation/gate/shown-result, MCP projection/window,
and `seon.test/run` sections, with small reads of their producers and callers. Applied the
data-oriented Clojure and REPL skills. This assignment authorizes one review file only: no code,
tests, runtime probes, `bin/seon`, delegation, publication, issue-index changes or other-lane contact.

Source citations below are pinned to **8f6eb00ff**, unless a historical commit is named.
Short Seon paths expand under `src/seon/`, `my/` under `src/`, `schemas/` under
`resources/seon/`, and report names under `docs/research/agent-platform/`. Dependency paths refer
to the named checkout under `reference-code/`.
The initial checkout was `fed60b7b9f6ef6e1d5868ef553b3acdf3368a77d` and the named source files initially
matched the design revision. Concurrent work subsequently changed evaluation, cluster, agent and test owners; these findings
remain pinned to the reviewed revision, not a claim about those later edits or the loaded JVM.
Dependency gitlinks inspected: SCI `fcbd8862800e638dc0f8f5521111f999279cbcd2`, Clojure
`3bc2b3e91fdff462620d84bbdfd40379d9c562e5`, Malli `befcd302a8e626a67d84b80aabee0cd1b38dadb6`.

**Source-confirmed** means inspected control flow or a documented counterexample; **historical**
means another report records an observation; **unverified** means the supplied evidence cannot
establish it. None means freshly reproduced or installed. The design reports a faulted JVM and
loaded-dependency drift. Its abbreviated forms, omitted envelopes and absent measurement harness
do not establish exact runtime reproduction, allocation attribution or subsequent health.

## 1. Probe claims: verified scope and corrections

| Design §0 | Verdict | Evidence and smallest correction |
|---|---|---|
| 1. Qualified `defn` refused across namespaces | **Source-confirmed**, timings historical | SCI `analyzer.cljc:839–849` permits a simple name or the current namespace. Clojure `Compiler.java:538–552` also refuses foreign definitions; a nonexistent foreign Var may fail earlier with a different message. Binding the target namespace explains the reported success. This establishes the need for entrance semantics, not the correctness of R1's proposed macroexpansion. |
| 2. Quoted `eval` bypasses the contract gate | **Source-confirmed hole**, exact execution historical | `definition-rows` (`sci/eval.clj:464–512`) emits a row only when source analysis matches a changed Var. `refuse-uncontracted-definitions!` (`:443–450`) checks rows, not every changed function. No matched row means no refusal and no arming at `:3803`. It can lose a contracted dynamic definition too. Refuse an unindexable program change as such; do not falsely call every missing row a missing contract. |
| 3. Both errors prove missing error unions | **Only partly supported** | The render diagnostic text exists (`error.clj:1855`); `render.clj:336–374` deliberately excludes bare `:seon.error/base` from named alternatives. But `evaluate` returns `:seon.sci.eval/evaluation`, whose value member is arbitrary (`schemas/seon.sci.eval.edn:32`), while `kernel/failure-value` declares the base (`kernel.clj:531–533`). These are different contracts. The report does not retain the offending envelope needed to diagnose “got nil nil.” Separate producer shape failure, nested-result attribution and missing named render alternatives. Adding an error union to the evaluator's envelope is not automatically the fix. |
| 4. MCP SCI records nothing | **Confirm for turn/evaluation and admitted definition rows; reject the absolute wording** | `mcp.clj:501–545` directly calls `evaluate`, with no `submit-source!`, run identity, ordinal or settlement. A returned definition row is not transacted. However evaluated code may write through database owners, error preparation may write evidence, and MCP can write blobs (`cluster.clj:478`). Say “no ordinary run/evaluation settlement,” not “no writes.” The reported 108 → 108 and absent function lookup remain historical. |
| 5. `callers` omits the render edge; 32 ms / 71 MB | **Edge confirmed; numeric attribution unverified** | `my/program.clj:144–157` returns a breakage **map**, not the displayed set. It includes direct-call facts; `breaks` adds a separately typed `render-declared-by` relation (`:228–245`, `:317`). The report likely abbreviates the map's callers field. Preserve relation types when exposing the edge. No retained complete form/envelope or allocation harness ties 71 MB specifically to the indexed lookup. |
| 6. Unbounded printers and output collection | **Source-confirmed** | `cluster.clj:532`, `mcp.clj:434–440`, `sci/admit.clj:85–95`, and the accumulating vector at `mcp.clj:333–356` are real. `canonical-edn` currently calls unbounded `pr-str`; calling it an encoder does not bound traversal or make unsupported values readable. The 230-line echo is a reported symptom, not reproduced here. |
| 7. Definition allocation is source-analysis cost | **Measurement and attribution unverified** | The design gives 31–87 MB and no independently reproducible allocation form, complete contracts, warm samples, retained heap or per-owner breakdown. `definition-rows` invokes analysis lazily, but the whole evaluation also acquires, validates, instruments and renders. Do not assign all bytes to analysis without that breakdown. Allocation is not retained memory. |

The 1,085 ms first evaluation and concurrent profile window are explicitly inconclusive in the design.
The earlier 1,147.529 ms `:named` refusal and 1.9 s saturated reach observation are different requests,
not evidence that today's `:changed` selection has the same cause. R7 should first separate acquisition,
selection, body, recording, warm allocation and retained memory on the same inputs.

## 2. All eight failure-evidence rows

| §1 row | What the evidence supports | What it does not support; revised prevention |
|---|---|---|
| 1. OOMs | `open-items.md:162–168` records the fourth OOM and 10.8 GB dump; `:52–61` records the later infinite-key hashing incident and 17.8 GB dump. `fix-schedule-2026-09-23.md:184–185` records a >2 GB PREPL response. | These are not one cause. Hashing an infinite key can exhaust the JVM without printing. A namespace printer annotation would not catch it. Keep bounded value classification before hashing/order/path construction, bounded serialization and one presentation boundary. The table does not independently enumerate four printer OOMs. |
| 2. Renderer publication order | Commit `5c80755da` changes incremental publication so schema and function rows land in the final indexing transaction; the incident names `notice-prose`. | A cross-file edge is not inherently two authorities. The schema owns the relation; the function owns implementation. Atomic publication fixes the actual defect. Moving metadata does not remove schema existence, pair completeness or cross-function coordination. |
| 3. Shipped `no-provider` | Census c `:25`, `:43` and `:96` reports 33 reds, 17 unstarted members and the two 120 s failures. `3a7ffa69c` moves the development policy to the overlay. | A schema default of `true` would reproduce the same defect. Correct policy separation and an explicit compiled test world prevent it; co-location merely helps inspection. Record default provenance too: a wrong default is invisible in a “differences from defaults” view. |
| 4. Refusal data lost | `cluster/boot.clj:527–536` still does `edn/read-string` over `pr-str`; the order incident records missing names. | A live result handle alone does not preserve a failure after retirement/restart. Keep typed complete evidence with a durable reference where needed; render at the edge. Do not replace loss with an unavailable handle. |
| 5. MCP duplication | The projection, window, output accumulator and result-artifact path are present in the inspected files. | There are shared dependencies inside the proposed deletion span; remove callers and retain necessary diagnostic/transport behavior, not the whole range by label. Host output events are not fixed by changing `valf`. |
| 6. Files/publication/stale Vars | `open-items.md:60`, `:102–104` records stale Vars and paused hooks. `boot-regression-2026-09-25.md:40–47` records the 16,352 ms selected refresh. | Whole-program recomputation is a publication implementation defect, not an inevitable cost of files. REPL-first work still needs source publication, host adoption and write-back. A branch intentionally differing from loaded code is not itself drift; report adopted upstream identity separately from its accepted local delta. |
| 7. False premises | S2b-c landing `:36–48` falsifies missing seed admission and locates lost seed keywords. Census c's hypothesis table likewise corrects diagnoses. | Neither report proves misuse of `rg` caused the premise. Graphs can also be stale or incomplete. Auto-pull positive analysis evidence and unknowns, then call the owner; do not turn an empty graph query into proof. |
| 8. Floor design rounds/prior art | The three review commits and `717e01bbb` are real; `open-items.md:45–48` records the abandoned growing implementation. | Commit order is not proof that a library roster alone prevents flawed designs. Supply the relevant dependency seam and guarantee with the task's subjects, not every checkout. Keep exploratory reference navigation separate from program authority. |

The common-cause paragraph should consequently name three distinct problems: unbounded work before
or during presentation, missing coherent publication/authority, and unsupported diagnosis. It should
not reduce all of them to text or file placement.

## 3. P0 findings: acceptance and durable evidence

### P0-A. R2 does not establish one accepted definition authority

The row-dependent gate hole is real, but its proposed repair is only one condition. In the same owner:

- `evaluate` computes all `var-rows`, arms them, then keeps only `(first var-rows)` in the singular
  `:seon.program/row` (`sci/eval.clj:3789–3805`, `:3820`, `:3873`). Turn submission likewise collects
  one row per evaluation (`turn.clj:4887–4894`). Whenever analysis yields multiple changed declarations,
  checking every row is not recording every row. Either admit the complete change set together or
  explicitly refuse unsupported multi-definition publication; never silently persist only one.
- `evaluate-for-install` forks a candidate only when the reader event names `:seon.fn/sym`
  (`sci/eval.clj:3913–3937`). A new post-evaluation check does not make dynamic/nested definitions use
  the candidate path. Failure handling explicitly retains definitions installed before a later throw
  as private bindings (`:3626–3637`). A “refused” result must not be reported as an accepted program.
- Contract validation can throw in `definition-rows` before the missing-contract restoration runs
  (`:499–502`, `:3791–3798`). Metadata-only changes also deserve scrutiny: `changed-interns` compares
  root values, although `turn-interns` captures Var metadata. Restoring only the currently detected
  changed roots is not proof of complete rollback or preserved contract metadata.

**Smallest fix:** unify the accepted declaration set at the existing evaluation/install/settlement
boundary. Match every observed function/test mutation to its canonical analyzed row, validate and arm
against the candidate projection, and settle exactly that set. Give missing analysis its own declared
refusal. For the first narrow slice, permit one indexed declaration per form and refuse ambiguous
publication explicitly; multiple ordinary top-level forms remain available. This is an initial
constraint, not a new evaluator or a permanent restriction on Clojure.

The design must preserve the existing entry-policy distinction: `:gate` versus explicitly diagnosed
`:warn` experiments; merge always requires positive proof. Post-evaluation rollback cannot prevent a
call inside the defining form or undo IO/atom mutations. State that limitation, preflight ordinary
admitted definitions where possible, and never promise “refused before evaluation” from a check that
runs afterward. The earlier requirements and Astra v0.3 review already name this problem.

### P0-B. R3 wraps a deletion owner that is not safe to certify unchanged

`native-call-refusal` omits native `ns-unmap` (`my/program.clj:636–641`), so adding interception is
justified. Its existing behavior is a **refusal advising another operation**, not a transparent
redirect. Native `ns-unmap` takes namespace and local name; the proposed three-line entry also needs
the correct combined subject and the refusal condition (`:646–657`).

More seriously, `retract-operation!` commits database deletion and then calls `native!` on both the
executing fork and `:my.program/base-ctx` (`:527–542`). It has no atomic rollback if native installation
fails. Mutating a shared/published base contradicts the previously established SCI fork guarantee;
namespace-map isolation does not make mutable inherited Vars or captured callers safe. Namespace
removal also explicitly refuses namespaces owning schemas (`:520–526`).

**Smallest fix:** keep the writer's referrer validation and retraction; acquire/advance only the
execution's owned successor from the resulting branch head, through the existing acquisition owner.
Do not mutate a published base. An installation failure must leave that execution unavailable and
named, not report an ordinary successful delete. R3 must prove custody, caller refresh, isolation and
failure convergence, not just absence of a row. Do not advertise general deletion in M1 until this is
done; merge of deletions still waits for R8.

### P0-C. M1 cannot land through the stated manual write-back

The cited `my.edit/form!` (`my/edit.clj:35–60`) takes a named top-level form plus a file digest; it is
an effect capability, not accepted-row/span write-back. Its owner calls `effect/request!`, and
`effect.clj:724–735` refuses a host call lacking a current evaluation context. A bare root JVM call
does not supply that context. A digest fence protects against stale file bytes; it does not prove
reviewed acceptance, combined-program green, or equivalence of reindexed declarations.

`prepare-merge!` requires an existing issue, admits replacements only and tests an intermediate branch
(`cluster/source.clj:747–794`); `accept-merge!` checks the tested program and destination fence
(`:797–840`). M1 neither invokes that sequence explicitly nor supplies R9a's equivalence gate.
New regression tests are additions too: R8 cannot be deferred while promising arbitrary fixes with
new coverage. Copying lane rows straight to files would bypass the stated merge law.

**Smallest fix:** either call the first milestone a private durable-edit experiment, with no landing
claim, or pull replacement-only merge plus R9a's checked write-back into it. Name root acceptance,
the existing issue input and exact tested head. Preserve original file provenance for REPL rows;
refuse targets without a trustworthy span/base. A manual invocation of the same gated writer is fine;
a manual sequence of unverified file edits is not that writer.

## 4. P1 findings: one path still needs these joins

### P1-A. “Read once” is a target, not existing evidence

`reply/sources` calls `parsed-events` for admission and again for recovery (`cluster/reply.clj:350–359`).
`plan-sources` discards parsed forms and retains source/comment/namespace (`:185–218`).
`turn/planned-sources` repairs those strings (`turn.clj:3112–3140`); the ordinary evaluator then calls
`one-event` when no event is supplied (`sci/eval.clj:3688–3697`). The “source is never reparsed” comment
describes consuming a supplied event inside that call, not the whole reply-to-evaluation route.

Retain the ordinary reader event through the live submission/evaluation path, including exact bytes,
reader namespace/aliases and repair evidence; store source for history without replaying interrupted
execution. Read successive forms with the namespace effects of preceding accepted forms. Count actual
reader invocations. Do not replace the discarded form with another reprinted string.

### P1-B. Qualified definitions need a real, once-only semantic seam

The design names `sci/macroexpand-1`, but the pinned `sci.core` exposes no such function.
SCI's interpreted `clojure.core/macroexpand-1` is installed by `impl/namespaces.cljc:893–899`,
`:1954`; the underlying implementation is `impl/macroexpand.cljc:11`. `my.program/create-ns!`, offered
as the repair, is absent too. Cite an installed callable before prescribing it.

Macroexpanding to discover the destination and then evaluating the original form can run a macro
twice. Macros may have effects, depend on their namespace, return `do`, or never converge. Binding
`*ns*` after reading cannot reinterpret `::keywords`, syntax quote or reader-resolved aliases already
embedded in the form. Meanwhile `definition-rows` receives the original namespace and bindings
(`sci/eval.clj:3791–3794`): binding evaluation alone does not make indexing agree with execution.

**Hybrid:** first dogfood with the existing request namespace or separate ordinary `in-ns`/`defn`
forms. R1 remains required for the final owner intent, but is not a prerequisite for durable REPL work.
Before implementing it, specify reader context separately from target evaluation context; consume a
verified expansion once under the existing arm, preserve source/metadata, and give analysis the same
target namespace/aliases. No library patch is yet justified. Prove a macro counter, target alias use,
reader-resolved names, unknown namespace, private def and unchanged session namespace. Do not price
this as a demonstrated 25-line sub-millisecond change.

### P1-C. Tests need the recorder as well as `evaluate`

`run-test` is an armed call to `run-tests` and `runner/run-vars!` (`sci/eval.clj:3978–4005`), not an
evaluation recorder. The harness also invokes **host Vars directly** (`test.clj:254–269`). Moving
only SCI `run-test` leaves that entrant behind. Host bodies deliberately run without an outer SCI arm
because they may create their own contexts; blindly wrapping them can cause nested-arm refusals.

A bare `(ns/test-name)` does not return the captured assertion report. The capture owner must enclose
the body and return its typed result, retaining fixture/hook semantics (`test/runner.clj:359–399`,
`:447–469`). Reuse that capture, have the common evaluation boundary own the resulting value, and
record it through the same settlement owner. Do not invoke the test once for capture and again for
evaluation. Candidate gate tests (`sci/eval.clj:4007–4084`), task proofs, host members, and selection
refusals need explicit treatment too.

The harness records results on the requesting branch and unlinks member branches after actual exit
(`test.clj:2080–2093`, `:2235–2259`). An evaluation row stored only on the disposable member branch
does not become lasting evidence merely by existing. Before unlink, retain the evaluation's evidence
through the existing recorder on the retaining branch, with member origin and tested identities;
have test results reference it. Keep assertion counts, current input/digest proof, positive completion,
unfinished-member ownership and reuse checks. Shown text cannot replace those facts.

`failure-report` already renders expected/actual to strings and `report-identity` hashes them
(`test/runner.clj:160–204`). Replacing a 30-line wrapper does not remove that early rendering or its
identity dependence. Give the report a value representation and one final render, preserving old
evidence identities as historical. T1 is required before claiming **one path for tests**, even if
the first narrow editing experiment temporarily uses the existing harness.

### P1-D. R4 must define execution identity and completion, not merely a transport template

Keep `submit-source!`: it already stages the reply, transacts a run and wakes the ordinary owner
(`cluster/agent.clj:635–723`). It requires an assigned agent namespace, a real handle/connection and
routing; acquiring a context with a non-nil id is not equivalent to creating and arming that agent.
The current bridge retains contexts by branch with **nil** agent id and defaults session ids to
`"default"`; `mcp/<session>` alone can collide across roots, clusters or branches.

Specify stable execution identity with explicit root/cluster/branch custody, namespace continuation,
busy refusal and clean retirement. The current branch retire tool refuses any agent holder
(`mcp.clj:596–601`); R4 must convert that caller too. Distinguish terminal run settlement from actual
execution exit, register observation before submission, and recheck durable state so a quick close
cannot be missed. Timeout returns a queryable run identity, retains ownership and prevents overlapping
execution/replay. Persist the previous evaluation's basis for the error view; do not recreate
`window-bases` under another name. Exercise source submission without an incidental paid model turn.

### P1-E. The result store/render contract is incomplete and unpriced

The entrance/store inventory must be explicit; one pipeline does not mean erasing distinct facts:

| Entrant or retained material | Required disposition |
|---|---|
| Product replies and MCP SCI submissions | Same reader/evaluator/install/settlement owners, with named execution custody; one run/evaluation history. |
| Generated opening and refreshed reads | Same evaluation and recording owners; preserve exact historical text and read evidence. Refresh reads only, never replay writes. Remove generated form print/read round-trips. |
| Test members, task proofs and candidate checks | Same evaluation recorder with the existing assertion capture and execution bounds; no truthiness runner. Host-bound execution remains an explicitly classified backend. |
| Raw reply/source, evaluation facts and program rows | Preserve source/provenance separately from program declarations; only the admitted program partition merges. An evaluation's errors/turn data do not become program merely by sharing a transaction. |
| Live result objects/private state | Existing execution-owned memory/handles; no serialization of arbitrary objects and no unannounced loss at an ordinary turn boundary. |
| Durable result/error/report blobs and occurrences | Existing staging/publication/retention owners; one linked evidence identity, not a second MCP store. Core faults still need their ordinary cluster visibility/delivery as well as branch provenance. |
| Test evidence retained on the requesting branch | Typed counts, tested content/input identities, completion and evaluation references survive member unlink; a shown string is an observation, not the test oracle. |
| Browser cuts, MCP retrieval and diagnostic host PREPL | Cuts use the common value selection/read entrance with server custody, never repeat the originating evaluation. Root PREPL alone retains the stated no-record exception. |

The design proposes three result representations but no corresponding implementation slice, schema
change, byte-admission algorithm, GC retention rule or durable-to-live lookup. Today `shown-result`
renders before settlement (`sci/eval.clj:3186–3231`); settlement preserves text and stages optional test
report blobs (`turn.clj:102–221`). MCP's artifact is a **print node**, not the proposed original typed
EDN value (`render/value.clj:673–718`). Moving its blob call does not implement the new representation.

Use one evaluation-owned result reference to existing live handles/facts/blob infrastructure, with
explicit absent/unavailable cases. Pin an entity's observation basis when historical equality is
claimed: a lookup ref alone follows later changes and deletion. The render plan already distinguishes
current entity cuts from historical digest cuts (`one-render-path-design-2026-09-25.md:450–459`).
Keep that distinction in the language design. Preserve nil and false as real results, multiple possible
schema matches as ambiguity, and live-only results as live-only. Never scan the whole registry merely
to find “a schema that validates.” Carry declared provenance and use cached Malli validators.

Enforce byte/traversal bounds **during** encoding under the evaluation's existing bound. Checking
size after `pr-str`, hashing an opaque key, or probing a lazy value to decide if it is finite can
exhaust memory before the check. Blob staging must be tied to settlement so cuts never advertise an
unpublished artifact and referenced evidence survives ordinary GC. An oversized or nonserializable
value must keep an honest live handle/typed unavailability, not a fabricated faithful blob.

Render the final settled success/refusal once for observation. The existing install-refusal path
can replace a preliminary shown result (`sci/eval.clj:3955–3966`); reorganize that boundary rather
than claiming it already prints once. Save exact shown bytes. Both faces must use the same selected
value/tree inputs, profile and render-program identity; re-rendering historical values with today's
renderer may be a useful current view but is not “as shown.” Reuse the render plan's shared selection
and retrieval rather than adding a parallel artifact/tree registry.

### P1-F. The diagnostic exception is valid, but R5 deletes behavior it still needs

Root's host PREPL must remain usable without database/configuration/projection. Keep that named
exception to recording, and the same bounded floor. However io-prepl's `valf` handles values, not
the potentially unlimited `:out`/`:err` events accumulated by the bridge. Bound capture/transport at
their producer without treating a completed string as safe. Preserve exception class, complete cause
and data; `exception-summary` currently loses them (`cluster.clj:348–366`).

Do not delete `loaded-code-mark` solely because adoption signals page invalidation. A root host
`defn` or `ns-unmap` does not necessarily perform adoption; `mcp-valf` explicitly handles that case
(`cluster.clj:513–519`). Either retain the evidence-based invalidation or route the host change
notification through an existing owner. Also `mcp-instance` has a surviving runtime-status caller
at `cluster.clj:663`; it is not wholly deletable. Convert bridge discovery, status, branch and retrieval
callers of the ThreadLocal marker before removing it. A bounded face must not corrupt machine protocol
data by turning an operational response into a clipped, subsequently reparsed value.

## 5. Co-location: G1–G4 and every data-file verdict

| Rule | Review and hybrid |
|---|---|
| G1, fully qualified literals | Keep native qualified **reference values** in metadata/data. Narrow the textual grep promise: `(defn notice-prose ...)` does not contain `seon.error/notice-prose`; `#:seon.config.ai{:no-provider ...}` does not contain the full keyword token. Both are ordinary supported Clojure. A symbol used as a function value must not be forcibly quoted. Grep finds spelling; the analyzer resolves identity. |
| G2, fact with subject | Keep intent/contracts/links on definitions, namespace intent on `ns`, shapes/defaults on their owning declarations. A relation is not owned by every endpoint. Rendering selection belongs naturally to the schema; runtime config belongs to cluster facts, not the compiled default constant. |
| G3, graph for relations | Keep, with positive analysis-digest evidence and typed unknowns. Literal keyword mentions do not prove reads; `my.program/reads-key` already says so (`:211–226`). `callers` should keep symbol callers separate from render declarations; expose the latter in a typed field, not a mixed set. Dynamic dispatch/macro references remain explicitly incomplete. |
| G4, name file inputs | Keep the path near the loader, but a docstring is not provenance. Carry actual resolved document/content identities and overlay/environment decisions from config compilation into the test-world evidence. Avoid making every downstream reader repeat a path or adding a new file registry. |

| Family | Verdict |
|---|---|
| Schema EDN | **Keep.** Namespace-based filenames organize the canonical population; they are not an independent authority. One key has one declaration; ingest links/defaults with it. |
| Render pairs | **Keep on schemas; C1 option 1 recommended.** The relation is schema × output → function. Shared render functions already serve many schemas (`schemas/seon.dev.mcp.edn:2–22`). A scalar reverse annotation cannot express that; a set/map migration, pair completeness, dangling references and writer conflict handling add work. Reusing `:seon.render/ai` with its value type reversed also changes its existing meaning. Atomic publication plus a reverse query solves the demonstrated defect with no sweep. |
| `config/default.edn` dials | **Move defaults conditionally; do not delete the whole file as priced.** `registration-defaults` exists (`config.clj:154–168`); the claimed three defaults are confirmed across the canonical population: two in `seon.config.db.edn` and the test deadline in `seon.test.edn:184`. Select by declared dial membership, not a `seon.config` namespace roster. Preserve explicit absence, environment-derived CPU concurrency, validation and overlay precedence. `no-provider` admits only literal true when present (`schemas/seon.config.ai.edn:64–72`); false is not a substitute for absence. |
| `config/default.edn` initialization | **Keep with its data owner.** At line 519 the file starts `:seon.config/initialization`, containing provider/model entities, consumed by `default-population` and `compile-manifest` (`config.clj:365–371`, `:546–554`). They are not dial defaults. Decide their existing-owner home and preserve bootstrap publication before claiming deletion. A smaller alternative is one scripted dial-only move, retaining an explicitly named initialization document and its existing writer. |
| `config/development.edn` | **Keep sparse.** This is the actual fix for development-only provider policy. Tests compile shipped decisions without silently inheriting it. |
| `config/test.edn` | **Delete only after caller retirement is verified.** The six-line file uses `#merge/#include "system.edn"`, unlike the present plain-EDN compiler; the named include is obsolete. A bounded repository search found no active reader naming this path. That is supporting source evidence, not a load/packaging proof. |
| Help and getting-started | **Consolidate, but do not infer teaching order from `:require`.** The help value already lives in code (`bootstrap.clj:22`); the duplicated seeded prose is stale (`instruction.clj:23` says uncontracted defn is scratch). Fix that claim in the eventual cut. Keep a small REPL protocol docstring and derive relevant tool docs from task uses/explicit must-reads. Implementation dependency order is not teaching prerequisite order. Preserve the earlier review's distinction between a tool use and an explicit subject; do not replace one preamble with a namespace dump. |
| Task backlog | **Keep minimal EDN plus code/test id edges.** Reconcile authored task data without overwriting attempts, decisions or runtime observations. Requirements §7 is the later authority for subject/proof edges; v0.3 still also authors `done-when` in §3 and derives about only from proofs elsewhere. Resolve these competing authorities in place. |
| Test seeds | **Keep in code**, with the owner-approved literal data, qualified function and inline function forms. Index their writes/calls and validate through canonical writers. C4 currently omits this ingestion requirement (7.9b). |
| Long docs, skills, research | **Keep Markdown.** Use symbol links for current navigation and producer docstrings for current intent. Retain pinned revision + `file:line` for historical evidence and non-Var material. Do not replace every citation with a moving wikilink. Parsing `[[...]]` is an explicit exception to requirements 1.4, “prose is never parsed”; resolve that rule before C4. Prefer existing structured metadata links first. |
| Issues | **Keep the existing owner during this cut.** The stated target is one task family; keeping an issue store and adding a backlog store requires a reconciliation/retirement plan, not two independent completion authorities. Do not edit the ranked issue index in an assigned lane. |
| Empty resource directories | **Remove if actually present and unused.** No tracked files were listed under the three named paths. Untracked/empty-directory cleanup buys no source-line deletion and cannot finance new code. |
| Reference code | **Keep navigation without a new store.** Prefer dependencies reached from task subjects plus explicit prior-art references. A gitlink digest identifies a pin, not uncommitted checkout content; label which source is shown. Avoid widening publication invalidation to all reference-only gitlinks. |

**Existing edges invalidate part of C4.** The string `:seon.fn/spec` is not the only contract
representation: `program/contract-facts` compiles it with Malli (`program.cljc:1053–1081`) and
`arity-row` stores input/output/guard refs (`:1007–1010`, `:1049–1051`). `my.program/reads-key`
already queries them (`my/program.clj:182–193`). Verify population/adoption and query that relation;
do not add another keyword edge because an earlier probe only read `:seon.fn/keywords`. Malli's
`schema`, `properties` and cached `validator` are the dependency seams (`core.cljc:2562`, `:2592`,
`:2637`); compilation belongs on changed contract/schema content, not on each context read.

For C1 the three priced choices remain: **(1) recommended:** retain schema pairs, reuse atomic
publication and add the typed reverse view; zero migration, bounded query work, two source locations.
**(2)** reverse ownership into function metadata: at least the design's unverified ~60 code lines
plus all reference/caller conversions; sacrifices a self-contained schema and still coordinates two
face functions. **(3)** move schemas into code: the design's days-scale, 211-file change plus new
publication/write-back obligations; widest scope. No demonstrated failure justifies options 2 or 3.

## 6. Auto-pulled context: keep the queries, repair their meaning and cost

- Change responses should derive callers, render referrers and affected tests from the **accepted
  change set**. Retractions need before-state incoming edges; querying only the now-missing subject
  loses the reason to repair callers. Do not put graph work in the AI renderer: that would repeat on
  page/replay and omit HTML. Build one typed observation at the common boundary and render both faces.
- The real call is `(my.test/check {:seon.test/changed ['n/f]})`, not `(my.test/check '[n/f])`.
  `my/test.clj:14–28` declares a map and uses policy `:named` plus `:seon.test/changed`; there is no
  `:changed` policy. Generate examples from the actual contract and exercise them through the entrant.
- A printer-call intersection is advice only. `with-out-str` and `binding` are macros, aliases and
  wrappers obscure calls, while bounded encoders legitimately produce strings. Reuse existing
  function boundary/sink facts where adequate (`mcp.clj:43`, `:968`); a namespace-wide `edge` exemption
  is too coarse to certify a particular call. Do not introduce it as a safety guarantee.
- A test-world view must show resolved documents, their digests, applied layers and relevant effective
  decisions. “Diff from default” alone hides a defective default. Keyword intersection produces
  candidate dial mentions, not a verified function→dial-read relation.
- Task context needs explicit subjects/must-reads and triggering observations as well as proof calls.
  Preserve the earlier Astra fixes: tools can themselves be subjects, raised research/triage can have
  no proofs, negative/deletion observations matter, and only the **form plan** is reusable without
  fresh read evidence. No universal namespace/require dump.
- Errors-since must use a durable, execution-specific previous basis and a captured current basis.
  Lost/restarted session, first observation, concurrent sessions and inherited branch errors need
  honest unknown/initial semantics. Do not share a mutable cursor across entrants.

Cost target: reuse held analysis/projection and content-valid graph derivations; bound indexed reverse
lookups by matched edges plus rendering, not whole-program size. `render-referrers` currently queries
all pairs then filters (`my/program.clj:237–241`); bind the requested renderer at the database owner
before putting it on every response. The 50 ms footer target is an acceptance target, not measured.
Account for large fan-in with counts and retrieval through the one floor, never silent truncation.

## 7. Shortest honest milestone and deletion accounting

The proposed M1 adds up to **158 lines** before unknown R7 deletions: R1 25, R2 20, R3 3, R4 50
and R7 60, against 155 stated deletions. That apparent balance omits the common recorder/result
representation, actor lifecycle, test integration and gated write-back. Small individual rows do not
satisfy “bound a change by what it lets us cut” when the functioning slice has unpriced prerequisites.

| Slice | Review of price/retirement |
|---|---|
| R1 | No deletion; verified macro/reader/index semantics missing. Defer behind namespace-based dogfooding. |
| R2 | +20/−5 prices one predicate, not complete declaration-set/candidate convergence. Reprice the actual owner cut. |
| R3 | +3 omits native arity/subject handling and base mutation/installation failure. Not a proven safe deletion slice. |
| R4 | Deleting the SCI template and error-window cursor is real. Price branch-agent lifecycle, busy/exit/requery and record retrieval too; retain transport parsing of machine envelopes. |
| R7 | “≤60 / ?” is not a deletion budget. Reproduce the specific selection cause, remove redundant whole-population work at its producer, then price it. |
| R5 | §2.3's ~380 cluster + ~330 bridge lines include SCI-template/window work already credited to R4. Do not count it twice. `mcp-instance`, host invalidation, runtime status, stdout/stderr and machine-response transport have surviving callers. Assign disjoint function ranges. |
| R6 | +60 without retirement; accepted-change context construction and renderer consumption are separate roles. Use existing breakage fields/query owner; do not add a graph cache or prose footer system. |
| R8 | Needed for new regression tests as well as new functions/deletions. Retained exact transaction data and named root acceptance need explicit evidence. |
| R9a/b | No priced deletions and incomplete cross-file/namespace/provenance behavior. Move R9a into a landable replacement milestone; additions/retractions need atomic caller/schema repair before general dogfood. |
| T1 | +30/−30 only covers a wrapper. Host members, assertion values, evidence retention and recording are omitted. Keep test result facts, retire duplicate presentation/evaluation only. |
| C1 | Reject the sweep. ~695 schema properties are references, not duplicate implementation. A shared renderer's reverse metadata needs all its schemas; ~350 entries is not established. |
| C2 | −657 is not legitimate: initialization rows at 519 onward must survive elsewhere; defaults and provenance also move rather than vanish. Count actual compiler/path deletions separately from relocated data. |
| C3 | −140 is unsupported by the named 13-line literal and 79-line instruction file, which also contains toolkit and render owners. Audit callers and preserve protocol teaching before claiming retirement. |
| C4/C5 | New link ingestion/roster work has no matching deletion. Remove the already-existing contract-edge item, retain seed requirements, and defer broad prose parsing/roster pulls. |
| D0 | Six obsolete config lines are plausible; empty untracked directories contribute zero. |

**Recommended order, using existing owners:**

1. Correct the design's installed API/edge claims; settle definition acceptance/recording at the existing
   owner. Use one analyzable definition per form for the initial experiment and existing namespace
   selection. Fix the gate hole before directing ordinary lanes to it.
2. Move MCP SCI onto ordinary durable source submission and stored evaluation retrieval. Retire its
   separate SCI template/window in that same cut. Keep the host diagnostic surface available.
3. Make the existing focused test request correct and cheap on the branch's admitted rows; include
   T1's common evaluation recording before calling the milestone “one path for every entrant.”
   Preserve host-bound isolation and actual-exit ownership, not a second runner.
4. Land one **existing-function replacement with existing tests**, via intermediate-branch gate,
   named root acceptance, checked replacement write-back and reindex equivalence. Pull R9a forward.
   This is the smallest landable dogfood milestone; it deliberately does not claim general additions,
   new proofs or deletion. General REPL development follows the proven R3/R8/R9b retirement.
5. Complete qualified-definition semantics, the shared durable-value/cut contract and R5/R6 after
   their owners' proofs. Co-location sweeps are not prerequisites. Keep C1 option 1 and do C2 only as
   the separated, behavior-preserving dial move.

The root proof for the milestone should show: same source through product/MCP/test entrances where
applicable; one evaluation of effects; admitted row and complete error evidence; a second call and
an affected caller executing the new definition; focused tests tied to that digest; preserved evidence
after member retirement; moved-head/file refusal; and accepted row→file→reindexed digest equality.
Measure acquisition, read/analysis, execution, record/print, test selection/body/recording, merge and
write-back separately, with allocation and retained-memory observations. Over-one-second work needs
the responsible owner and justification; the current numbers do not supply that proof.

## 8. P2 corrections and landing limits

Correct broken section pointers (`§2.5` and `§3.3` for the render-pair rule), the exact `callers` result
shape, test-call syntax, unavailable helper names and stale contract-edge
gap. Keep dated evidence citations rather than replacing them indiscriminately with current symbols.
The REPL skill also overstates sharing a private branch definition with another acquired agent: its own
following section admits the definition is not a program row. Treat cross-agent adoption as unproved;
correct the skill in its owning cut, not this one-file review.

Only this review file is authored. Source/test delta: **0 / 0**. No runtime, test, adoption, arming,
browser paint, memory or performance verification was run or claimed. Existing unrelated changes and
staging were preserved. The dated reports establish historical observations; the source establishes
the gaps and surviving seams. Installation, measured deletion diffs and the milestone proof remain
the implementation/root lanes' work. This review releases its single path after the requested
`git commit --only`; it does not approve a push, merge or production rollout.

Document checks: balanced fences/front matter, required review sections and clean whitespace;
these are document checks only, not Seon tests or runtime proof.
