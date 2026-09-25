---
type: design-review
created: 2026-09-25
verdict: ACCEPT-AFTER-FIXES
reviewed-design: task-context-install-design-2026-09-25.md at 50c5b3e2a
source-pin: 5dba6a14cb72182eadff92dd98cf4c8a43f638db
scope: Read-only independent review; this document is the only authored file
---

# Task context installation: independent review

**ACCEPT-AFTER-FIXES.** Keep the agent → task → acquired value → declared render pair → ordinary evaluation architecture. Do not implement the source-constant refresh filter or activate the opening in the proposed order. The smallest first delivery is task acquisition and its two faces, with the existing refresh semantics preserved. Narrowing refresh is a separate correctness change, not necessary to deliver task context.

Today `declared-sources` discovers opening forms by rendering a neighbourhood, and `system-plan` checks retained reads. The principal Clojure composition is one domain acquisition over a held database value, one ordinary result value, and the existing read/evaluate/record/print stages. No additional context store, evaluator, printer or execution graph is needed.

## Evidence and limits

Reviewed the design at **50c5b3e2a**, the complete living language requirements, the complete query/render building-block design, AGENTS.md, and the relevant current producers/callers. Read the data-oriented-clojure and REPL skills and the turn PRD §§13–15. All source citations below are at **5dba6a14c**, unless a document revision is explicitly named. `D` means `docs/research/agent-platform/task-context-install-design-2026-09-25.md`; `Q` means `docs/research/agent-platform/query-render-building-block-2026-09-25.md`; `L` means `docs/research/agent-platform/language-requirements-2026-09-24.md`. These abbreviations always name files, not another authority.

This was a source/design review: **no tests, runtime evaluations, provider calls, adoption, stop or reset**. The design's live measurements are author-reported evidence, not measurements repeated here. Installed behaviour, latency, memory and loadability remain unproved. No foreign breakage was diagnosed or used to stop work. Unrelated working-tree edits were left untouched.

The requested owner's rule—start simply, follow the task and all its references to N hops—governs this review. The revised design's actual §3.1 quotes the agent/task/render question at D:179–181; it does not quote that entire rule. Do not invent a longer verbatim quotation. AGENTS.md's composability, agent perspective, staged REPL, refresh and render laws are the other governing authorities. The no-graph-work-in-renderers ruling is explicitly carried by Q:42–49 and the turn PRD §13.

## Numbered fixes required before implementation

### 1. Preserve refresh correctness; source constants are not read subjects

**Location:** D:295–312, D:373–378; `src/seon/turn.clj:1925`, `:1959`, `:1977`, `:2105`.

The proposed intersection can omit a read that matters to the task. For example, a database read whose lookup comes from `result/e…`, a private binding, or a wrapper can depend on a task member without spelling that member in its source. A query for an initially empty set can matter when a member is inserted; existing result identities are not complete dependency evidence. The design itself concedes the handle case at D:374. Retaining the old shown text and making no currency claim does not satisfy AGENTS.md's instruction to refresh each distinct changed read before the turn.

Membership has another false negative: D:252 includes only hops 1 and 2 even when N=3, and says nothing about reads explicitly expanded beyond the default brief. Display relevance cannot define the completeness of read dependencies. A changed task also creates an ordering problem: `system-turn` currently computes the whole plan at `turn.clj:2105–2107` before evaluating anything at `:2115`. “The newest context” is not available there merely by changing candidates. Acquiring it separately would execute the context query twice; selecting against the previous result can defer newly relevant reads by a turn. A restarted JVM may have shown text but no live context value; parsing that text is not a substitute.

**Smallest fix:** defer T2's semantic restriction. Preserve the current distinct-read set and optimize evidence acquisition within the database owner. Do not introduce `:read-subjects` just to make a lossy filter fast. If scoped refresh is pursued later, define relevance from actual captured dependencies, retain unknown/unbounded dependencies conservatively, cover empty-result insertions, indirect arguments and task changes, and define an explicit context-first stage using the same evaluation result once. `seon.db/read-evidence` already retains dependency plans, patterns and optional read requests (`src/seon/db.clj:892–942`); reuse that authority.

The proposed ordering “read-only check, then evidence pull” also cannot use today's predicate unchanged: `read-only-evaluation?` requires nonempty `:seon.cluster.eval/read-evidence` at `turn.clj:1964`. A cheap selection query must retain evidence existence, or the predicate must be split at its actual concerns.

### 2. Do not rely on the relevance filter to prevent effect replay

**Location:** D:51–55, D:298–300, D:402–403; `src/seon/turn.clj:1959–1973`.

“Outside every context” is not a safety property. A form can mention a member and complete a turn, or a task can concern `my.agent/done` itself. Once it intersects, the same admitted effect can replay. The existing predicate tests for recorded receipts/effects, so the design's observed unrecorded effect exposes a producer defect regardless of candidate selection.

**Smallest fix:** remove the claim that §4 contains this defect. Make correct effect/receipt evidence at the producer, and rejection of effectful refresh, an activation prerequisite owned by that fix. Do not add a function-name blacklist or another effect classifier to the context builder. Until then neither the new filter nor unchanged refresh is proved safe for the reported completion calls.

### 3. Specify all reference families and give explicit subjects/must-reads precedence

**Location:** D:111–122, D:239–254, D:273–277; L:100, L:114–118.

The algorithm actually described seeds proof calls and keywords, then follows callers/callees. That is narrower than “all its references.” It does not specify proof-referenced lookup entities, function- and namespace-level must-reads, seed-function writes, contract-key refs, render referrers, open tasks on a function, or config/prior-art references. Some are data gaps; they must be named as such rather than silently omitted. `task-edges` currently discovers function/namespace subjects and test proofs, not every schema subject (`src/seon/issue.clj:716–732`). `open-tasks` already owns one required relation (`:734–740`); L:118 identifies the existing contract-fact relation.

The taught-set approximation is also too broad. Help's Tools query lists required namespaces containing public non-internal functions (`src/seon/bootstrap.clj:40–48`); it does not establish that every public in those namespaces was taught. In particular, an explicit task to change a taught wrapper must still show its subject, and a must-read must remain included. The current exclusion rule gives neither precedence.

**Smallest fix:** add a compact edge-to-owner table to §3.3; reuse installed relations and explicitly mark missing ones as prerequisites or a narrower first slice. Include explicit subjects and task/function/namespace must-reads before applying taught-tool suppression to incidental expansion. Define N=0 and N=1 as well as the default N=2; do not silently equate namespace subjects with a namespace dump. Keep taught tools as terminal handles unless explicitly selected as content. This adds task policy, not a second language or graph registry.

### 4. Keep traversal policy small and make work bounds real

**Location:** D:31–33, D:146–151, D:205–211, D:239–254; Q:24–26, Q:264–287.

`task-view` is a new domain reference traversal, even if approximately 50 lines. The existing `seon.render.walk/acquired-tree` already owns generic entity traversal, distinct-entity visitation, cached pulls and captured evidence (`src/seon/render/walk.clj:483–533`). It follows declared ref connections, whereas task handles and program symbols are values: it is not automatically a correct task-graph API. The design must distinguish that semantic work from reusable rendering and acquisition machinery. Its “no walk ... is added” sentence currently conceals this distinction.

Counting hop 3 reduces output, not acquisition. O(frontier × two lookups) omits lookup result cardinality, source bytes, test reach work, retained membership and sorting. The 15,605 pulls/1,556 ms measurement does not establish a sub-second N=3 path, and a cap read from the old neighbourhood implementation is not automatically enforced by the new task function. D:228 passes no caps/profile/environment, although the proposed limit must come from explicit world inputs or a named configuration read on the held database.

**Smallest fix:** keep task-specific edge selection in the task owner, feed acquired values to `units`, and reuse the existing query owners. Name precisely where distinct visitation, query limits and continuation data come from; do not copy `neighborhood`'s render loop, retention or budget. Define total visited nodes/edges and query deadlines before fetching broad frontiers, with explicit inputs and a named partial/unknown result at exhaustion. Counts are exact only if counted work completes; otherwise expose the bound and continuation. N=2 is the initial default. N≥3 summaries are lawful renderer policy, not a new character/display budget. Require warm and acquisition timings, read counts and memory at N=1/2/3, plus a high-degree task; the 10 ms target is not proof.

### 5. Activate one fully declared value and both faces together

**Location:** D:227–269, D:354–362; Q:250–252 and §5 S3; `src/seon/render.clj:1322–1353`.

Today's AI output accepts text/source blocks and HTML accepts Hiccup; the new `:seon.render/composition` is not accepted there. D:269's fallback to today's `render-call` therefore cannot render the proposed pair. T1a switches the opening before T1b supplies its pair. A floor dump of the envelope in the interim neither proves the intended context nor the whole-source requirement.

The declaration estimate also omits named shapes for the shown `:seon.task.view/*` values and request/return alternatives. The sample puts the context inside an agent-shaped envelope, while D:258 merely says the context key declares a pair. The full return schema and producer-selection witness must establish that the context result selects its pair and an identity-only agent selects the identity pair. Do not assume an attribute pair handles an arbitrary containing map. Current agent schema dispatch is declared at `resources/seon/schemas/seon.agent.edn:35–59`; `src/seon/render.clj:487–521` selects satisfied root-call return schemas before falling back to entity ownership.

**Smallest fix:** stage contracts and unused acquisition first; land Q's composition and shared-floor support before activation; switch the opening and install both faces in one loadable activation. Keep one implementation throughout. Declare and validate the whole context result and errors; account for those declarations. Malli already supplies compiled validation/explanation (`reference-code/malli`, pinned `befcd302a8e626a67d84b80aabee0cd1b38dadb6`, `src/malli/core.cljc:2637` `validator`, `:2653` `explainer`). Prove selection for a context, an agent identity, and an agent nested in a message through the existing ladder. No custom dispatch is needed.

### 6. Resolve task assignment and migration before replacing every opening

**Location:** D:193–196, D:314–323, D:347, D:357, D:379–394.

A taskless agent gets help, situation and a no-task error; root gets the same error telling it to ask root. A standing root task is the sensible choice, but merely listing status/faults/agents functions as must-reads shows their source, not their current answers. Root must be able to fetch and act on current observations, and notifications must keep their existing delivery path. The claimed “under 1 KB” opening has no evidence: `(help)` alone contains extensive prose (`src/seon/bootstrap.clj:50–70`).

T1 removes issue-generated opening content before T3 assigns issue workers tasks, although the latter is conditional on migration. That can leave every unmigrated worker without its assigned work. MCP has a separate missing prerequisite: branch creation returns branch data (`script/seon/dev/mcp.clj:502–541`), and SCI acquisition passes a nil agent id (`:470–473`). Adding a task argument to `branch` does not create agent identity, task assignment or ordinary evaluation settlement.

**Smallest fix:** make the standing root task and assignment of all affected existing agents prerequisites of the opening switch, with one migration boundary for unmigrated issue workers. Taskless agents retain a visible ordinary error and callable task discovery; do not block root's turn or invent a namespace fallback. Define absent agent, absent task assignment and dangling task handle separately. Route outside-agent assignment and first context evaluation through the shared agent/REPL entrance; name the one-REPL dependency instead of pricing it as a complete ten-line adapter.

Extending `task-findings` is not “one or clause” alone: its dangling-holder contract is a set of symbols (`resources/seon/schemas/seon.task.edn:37`), whereas agent ids are strings. Prefer returning a named dangling-assignment error at the agent/task acquisition boundary, or deliberately extend the existing finding schema and its consumers. Do not coerce ids into symbols.

### 7. Remove retained renderer-side acquisition and make expansion calls callable

**Location:** D:202–204, D:251, D:266–288; `src/seon/cluster/agent.clj:396–412`; `src/my/program.clj:159–167`.

The context pair itself is correctly read-free, but the retained situation renderer calls `bootstrap/situation` when passed the stored agent. That is a database-read path in a renderer. It also means “the situation line stays” needs an explicit acquisition stage once the neighbourhood opening is removed; `(help)` returns help lines, not that situation value (`bootstrap.clj:50`).

The displayed `(my.program/tests-reaching 'f)` violates the installed map-input contract; the callable form is `(my.program/tests-reaching {:seon.program/subject 'f})`. Likewise, an expansion call `(seon.cluster.agent/context {})` shown for another agent expands the viewer's default agent, not necessarily the shown agent. A hop-3 count needs a real expansion call that reveals the counted members; increasing N while every hop ≥3 stays count-only is not such a call.

**Smallest fix:** acquire situation data in the agent's ordinary opening/turn acquisition and hand the renderer the value. Keep turn-dependent situation separate from automatic read replay where required by `generated-read-fault`. Emit explicit subject/agent coordinates for handle expansion and use the installed request shapes. Specify the count's query continuation. These are ordinary calls through the same REPL, not an adapter-side evaluator.

### 8. Reconcile deletion ownership and the loadable slice ledger

**Location:** D:331, D:350–363, D:408–409; Q §4 Q4 and §5 S5.

The old ranges total **902 real lines** at the reviewed HEAD; the table below verifies them. This is gross old-code credit, not a measured final diff. T0's 502 lines include Q4's `direct-candidates` (42) and `listing-candidates` (20): **62 lines are double-booked across designs**. If T0 lands, Q4's replacement and its bootstrap consumer conversion in S5 disappear as well. Q5/Q6/Q2 outside-path credit is correctly left in the other design.

T0 also has more than “two test cases.” Six named tests use retiring opening functions, beginning at `test/seon/bootstrap_test.clj:83`, `:162`, `:223`, `:279`, `:352`, `:373`. Direct uses of `next-entry` occur in two of them, but `pull-result` disappears in the same deletion. Leaving the others means unresolved Vars. Deleting `seon.issue.opening` must also remove the namespace require at `src/seon/cluster/agent.clj:82` and its caller at `:301` coherently.

**Smallest fix:** give T0 exclusive credit and remove Q4/S5 bootstrap work from the other ledger at coordination; enumerate all affected test callers and retire only tests of retired behaviour. Keep migration-dependent T3 conditional. Reprice the context schema, situation acquisition, task assignments and MCP prerequisite; D:189 still quotes +89 while the detailed T1 is +109. Splitting to meet a line threshold must not split activation from its contract/render prerequisites.

## Retirement spot-check at HEAD

Count method: whole definition spans through their last nonblank/noncomment line, excluding separator lines; the deleted file includes its namespace and comments. Read via `git show 5dba6a14c:<path>`; no Clojure was executed.

| Old owner | HEAD location | Verified old lines | Qualification |
|---|---|---:|---|
| Bootstrap candidate machinery | `src/seon/bootstrap.clj:204` through `:731` | 502 | Source search found only test callers outside the range for `next-entry`/`pull-result`; runtime dynamic resolution was not inspected. Includes Q4's 62 lines. |
| Declared opening walk | `src/seon/turn.clj:1870` | 54 | Rewritten, not wholly removed without replacement. |
| Identity form and AI faces | `src/seon/cluster/agent.clj:246`, `:278`, `:306` | 7 + 27 + 5 = 39 | Their references and output contracts must change together. |
| Latest evaluations | `src/seon/turn.clj:1925` | 33 | Evidence pull visible at :1948; defer unsound candidate filtering. |
| System plan | `src/seon/turn.clj:1977` | 41 | Existing read currency/selection owner; keep semantic obligations. |
| Issue opening namespace | `src/seon/issue/opening.clj:1` | 233 | Conditional migration deletion; live source caller at agent.clj:301. |
| **Gross total** | | **902** | 669 excluding conditional T3; final net remains an estimate. |

## Answers to the seven review checks

1. **Smallest composition:** yes to a function returning a value with a declared pair. A thinner internal arrangement is `agent/context` pulling the task handle then delegating to the existing task owner, as proposed. No second public `my.task/context` is necessary. If its two faces prove identical to an existing generic composition, return/reuse that shape instead of inventing another rendering algorithm. Acquiring in the agent renderer is not smaller in responsibility. Option 1's statement that renderer output must be text is too strong under L §11.2: the real objections are repeated acquisition and a hidden read boundary, not inability to return data.
2. **Reuse:** rendering correctly delegates to Q. Domain task-edge acquisition is legitimate; the new generic visited/pull/cut machinery would duplicate `acquired-tree` if recopied. Fix 4 requires a precise seam rather than assuming symbol edges are entity refs. The certain ledger duplicate is bootstrap Q4's 62 lines.
3. **Retirements:** real old ranges, verified above; no installed-deletion claim. Their arithmetic alone does not prove all callers retired or the final net.
4. **Scoped refresh:** incorrect as specified. Task-relevant indirect reads can be omitted, unknown dependencies lost, and task changes selected against stale membership. Keep the actual read-evidence authority.
5. **Taskless/root:** current proposal yields an error for both; root self-referral is not an operating plan. Adopt the standing root task and assignment/migration prerequisite, without stopping taskless interaction.
6. **Cost/N=3:** output growth is acknowledged but work growth is not bounded or proved. Counts are permissible semantic summaries, with an explicit expansion and honest unknowns; only the shared floor clips displayed values. N and query-work bounds are not display allowances.
7. **Loadability:** not yet. T0's extra test callers, T1a-before-pair, unsupported composition fallback, task assignment timing and MCP identity/settlement prerequisites must be made explicit. The proposed ordering cannot be called loadable from source review alone.

## Opinions on §8

- **Q1, read subjects:** reject A and C as correctness rules. B is closer because capture knows the inputs, but an extra indexed subject set is not automatically complete and is not needed for the first delivery. Preserve refresh semantics now; price an evidence-based optimization only after proving no false negatives, including unknown patterns.
- **Q2, root/taskless:** choose **B**, with the assignment and root-observation qualifications in fix 6. A alone leaves root asking itself; C disables the authority needed to recover/assign work.
- **Q3, N:** choose **A, N=2**. Preserve an explicit caller's N, use lawful summaries/handles for deeper work, and measure N=3 before claiming its target. N=1 is useful as an explicit request. C's repeated names are not justified as the default.
- **Q4, messages/outside agents:** choose **A**, provided the actual trigger and task-linked message references remain reachable, delivery semantics are retained, and the MCP path uses an agent identity plus the shared REPL. Multiple semantic concerns are not inherently multiple producers: a messages concern under the same acquisition/render pipeline is lawful. Reject B's new edge until there is a demonstrated requirement, not because messages are intrinsically out of context.

## Landing limits

This review adds documentation only: **source +0/−0; tests +0/−0**. No runtime timing or memory was measured. The design's 24 ms N=2 and 1,556 ms N=3 samples are neither parent/change comparisons nor installed acceptance proof. Required implementation evidence is the real context call once, captured reads, AI/HTML selection from the same value, unchanged historical bytes, callable continuations, assignment/migration, refresh safety and bounded work. Root owns those future checks. This lane releases only this review file after its requested commit.
