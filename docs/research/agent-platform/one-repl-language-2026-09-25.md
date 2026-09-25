---
type: design
status: revised design (Opus, revision 3), integrating Astra's re-review of e5f9b923e (appended to one-repl-language-review-2026-09-25.md) and aligned with language-requirements-2026-09-24.md at 19e9fe5f4; for root ruling; no production code
created: 2026-09-25
revised: 2026-09-25
lane: design-one-repl-language-opus → revise-one-repl-opus → revise2-one-repl-opus
source-pin: 19e9fe5f4. Every cited file is byte-identical at 3785970b9 except src/seon/{fn,render,error,repl,plan}.clj, src/seon/cluster/message.clj and the render and sci.eval schemas, whose citations stay at 3785970b9
scope: the one REPL path from the agent's side; one definition and deletion authority; gated write-back; tasks and issues moved into the codebase; co-location and configuration layering; auto-pulled context; ordered and priced milestones; the requirement rows still to revise
---

# One REPL, one language: read → eval → record → print, and where facts live

**What exists.** Seon already has the owners of one REPL path:

- the reply reader, `seon.cluster.reply/sources`;
- SCI evaluation, `seon.sci.eval/evaluate`;
- ordinary run submission and settlement, `seon.cluster.agent/submit-source!` (`src/seon/cluster/agent.clj:635`)
  and `turn/evaluation-facts`;
- test capture and recording, `seon.test/run` and `seon.test.runner`;
- program rows and call preparation (`src/seon/call_preparation.clj`);
- one declared render selection, with a floor;
- guarded source publication, `seon.cluster.source/publish!`;
- an issue owner that joins problem statements to program identities (`src/seon/issue.clj`).

Three entrants still bypass parts of this path. MCP SCI evaluates without settlement. MCP JVM reads and
evaluates separately. Test bodies are invoked outside the evaluation recorder (duplicate-path audit §5). Issues
are also out of place: they are indexed by parsing Markdown prose, whereas they belong in the codebase as tasks
(requirements 7.11).

**The principal approach.** Every entrant goes through the existing owners; no new path is added.

- MCP SCI submits source through `submit-source!` as a named agent.
- Tests hand their captured body to the same recorder.
- Evaluation admits a form's program change as one whole set, or refuses it.
- Files change only by a gated write-back of accepted rows, proven before they are exposed.
- Tasks are the issue owner evolved in place. Their data is task EDN. Their subject edges and proof edges are
  metadata that the analyzer already reads.
- Printing is the one render selection, applied at the terminal boundary.

**The owner's goal, and how this design orders it.** The owner wants to "start using this so we can move the
docs and issues into the codebase". This design therefore orders the work in three steps (§3.4, §6):

1. **M0** moves the tasks in. Its slices are file-disjoint from the REPL slices and can run alongside them.
2. **M1** makes one existing-function replacement land from the REPL.
3. **D1**, the first dogfood milestone, has a lane settle one migrated task through M1.

## 0. Broken things first

The first-pass probes ran on default (pid 35446, health `faulted`) on the retired SCI branch
`design-one-repl-language-opus`. Revision 2 made read-only JVM reads and source reads. Revision 3 made one
read-only JVM query of the issue index on default (8 ms), plus source reads at 19e9fe5f4. The re-review confirmed
items 4, 8, 9 and 10 against source. It did not reproduce any of them live.

1. **A qualified `defn` into another namespace is refused, in SCI as in Clojure.** Two sources confirm this:
   `reference-code/sci/src/sci/impl/analyzer.cljc:839-849` accepts only a simple name or the current namespace,
   and Clojure's `Compiler.java:538-552` behaves the same. Binding `*ns*` explains the success reported earlier.
   Requirement 1.10 asks the entrance to admit `(defn other.ns/f …)`. That clause stays deferred (§2.1).
2. **The entry gate checks rows, not changed Vars.** `definition-rows` (`src/seon/sci/eval.clj:489`) emits a
   row only when analysis matches a changed Var, and `refuse-uncontracted-definitions!` (`:456`) checks only
   those rows. An `eval` of a quoted `defn` therefore changes a Var that has no row, and it passes unrefused
   and unarmed. The accurate name is "an unindexed program change".
3. **Evaluation keeps only the first definition row.** `evaluate` arms every entry of `var-rows` but keeps
   `(first var-rows)` (`eval.clj:3820-3834`). Turn submission then collects one row per evaluation
   (`src/seon/turn.clj:4887-4894`).
4. **Deletion has two authorities and one hole.** Three paths exist today:
   - **(i) Top-level `ns-unmap`.** The reader marks a top-level `(ns-unmap 'n 'f)`
     (`src/seon/sci/reader.cljc:395-404`). Only in that case does `evaluate` take the full intern snapshot
     (`eval.clj:3729-3746`). `unmap-row` (`:3143-3157`) has the same guard.
   - **(ii) `my.program/ns-unmap!`.** It checks references inside a transaction function, commits, and then
     mutates both the executing context **and the shared base** (`src/my/program.clj:527-542`).
   - **(iii) Nested native `ns-unmap`.** `native-call-refusal` (`:636-641`) omits `ns-unmap`, so a nested call
     removes the Var while its row survives. This hole follows from the code. It is the mechanism behind the
     2026-09-24 deletion-run defect.
5. **Entry refusals render with a contract finding.** This is partly supported (review §1.3). The offending
   envelope ("got nil nil") was not retained, so it must be reproduced before a fix is designed.
6. **MCP SCI has no ordinary run or evaluation settlement.** `script/seon/dev/mcp.clj:501-545` calls
   `evaluate` directly. The tool's own description says so: "private SCI state, never program rows".
7. **Unbounded printers remain on the MCP path.** The sites are `src/seon/cluster.clj:532`,
   `mcp.clj:434-441`, `src/seon/sci/admit.clj:85-95` (an unbounded `pr-str`), and `mcp.clj:333-356`.
8. **`config apply` loses layers for the cluster it applies.** Three sites each compose a different stack:
   - boot composes defaults, then development, then `--config` (`src/seon/cluster/boot.clj:432-437`);
   - apply compiles defaults and the submitted file only (`boot.clj:629-634`,
     `script/seon/operator.clj:1405-1412`);
   - publication recompiles defaults and development only (`boot.clj:660-676`).

   Reconciliation is exact (`src/seon/config.clj:673-725`), so a later apply erases **that cluster's** earlier
   layer decisions. Other clusters' referenced rows are deliberately retained (`config.clj:677-705`).
   Nothing records which layers produced a row.
9. **Nine readers have no bound cluster identity.** The sites are:
   - `src/seon/turn.clj:2697-2700` and `:4012-4017`;
   - `src/seon/agent.clj:132` and `:185-187`;
   - `src/seon/repl.clj:69-72`, `src/seon/issue.clj:1232-1233` and `src/seon/bootstrap.clj:131-134`;
   - `src/seon/render/transcript.clj:1180-1182` and `src/seon/sci/eval.clj:738-745`.

   These are nine sites, not one repeated query. `turn.clj:4020-4023` also reads the blob threshold from any
   entity. A branch that inherits another cluster's referenced row holds several eligible rows, and the
   selection then has nothing to do with custody. The one-row probe on default (4 ms) shows the ambiguity
   exists. **It does not demonstrate a wrong live result.**
10. **Inline render functions get no supplied defaults.** `src/seon/sci/kernel.clj:633-640` bypasses
    preparation for a function value. Even the present hook passes through a callee that has no program
    identity (`call_preparation.clj:1538-1541`). The render boundary passes the unit, including its database
    and context fields (`src/seon/render.clj:196-249`, `:452-482`), as a caller argument. Declared attribute
    renderers receive the raw attribute instead. The fix is the shared preparation owner consuming the inline
    function's retained declared contract (§2.4).
11. **Allocation is unattributed.** The first pass observed 31-87 MB for each one-line `defn` and 71 MB for one
    `callers` read. There is no per-owner breakdown, so these figures are observations. The R-timing rows in
    §6 measure them.
12. **The issue owner parses prose.** `seon.issue` indexes each `docs/seon/issues/*.md` at publication
    (`src/seon/cluster/source.clj:512-523`; the changed-path filter is at `:559`) with a hand parser:
    - `parse-note` (`src/seon/issue.clj:96-122`) reads header fields and the "## Problem" section;
    - the citation tokenizer (`:35-58`, `:166-320`) turns words into function, test, error, key, file and
      commit refs.

    This conflicts with 1.4 ("prose is never parsed") and 7.4 ("code owns subject edges"). The cited
    functions are **mentions**, not the producers that must change (2.3).

    Probe (read-only JVM, 8 ms): 405 issues are indexed as open. Of these, 241 cite at least one function (438
    function edges) and 75 name tests. But 450 note files carry `status: open`. The missing 45 are
    **unexplained**; T3b must account for each one.
13. **MCP JVM mode reads with the bridge's single-form reader.** `execute-clj-eval` calls
    `require-single-clj-form!` before choosing a mode (`mcp.clj:692-705`), and `jvm-evaluation-form` wraps a
    single `read-string` (`:486-499`). Moving SCI alone therefore cannot retire audit R7 (§2.5).

These are recorded here as their durable home. Root files the issues; this lane commits only this file.

## 1. The failures, and what would have prevented each

| # | Failure (verified source) | Cause | Prevention |
|---|---|---|---|
| 1 | OOMs: an overlay `pr-str`'d as `:seon.error/offending` (open-items.md:162-168); an infinite `(range)` key hashed by floor code (:52-61); a >2 GB PREPL answer (fix-schedule-2026-09-23.md:184) | unbounded printing, hashing and ordering before presentation | classify with a bound before hash, order or path; bound serialization during encoding; one presentation boundary |
| 2 | Publication order (5c80755da) | incoherent publication | fixed by atomic publication; the reverse edge is a typed `breaks`/`callers` field (§5) |
| 3 | Shipped `no-provider true` (fixed 3a7ffa69c) | development policy in the shipped layer | separated layers, with each default's provenance reported (§4.4) |
| 4 | Refusals dropped data (`boot.clj:527-536`; open-items.md:49) | print-then-read inside the system | typed refusal values kept; rendered at the edge |
| 5 | MCP is a parallel REPL (audit §1.1) | a second submission and result policy | MCP SCI becomes an ordinary entrant (§2.5) |
| 6 | Paused hooks, stale Vars; a 16,352 ms two-file publication | whole-program recomputation in publication | cost follows changed inputs (publication owner's cut); intended branch differences reported separately from drift |
| 7 | False premises (S2b-c landing :36-48) | unsupported diagnosis; stale graph | the task opener pulls positive analysis evidence and its unknowns; an empty answer is never proof |
| 8 | Three floor design rounds before prior art (717e01bbb) | the dependency seam was not in view | a design task receives the seams its subjects reach (§5) |
| 9 | Issues cite mentions as subjects; 45 open notes not indexed as open (§0.12) | prose parsed for relations | tasks carry code-owned subject edges and test-owned proof edges; the Markdown body is linked evidence (§3.5) |

## 2. The one REPL path, from the agent's side

### 2.1 Read: shared owners now, once-only later

"Read once" is a **target**. Today the reader runs several times:

- `reply/sources` parses twice (`src/seon/cluster/reply.clj:350-359`);
- `plan-sources` discards the parsed forms (`:185-218`);
- `turn/planned-sources` repairs the strings (`turn.clj:3112-3140`);
- `evaluate` calls `one-event` again when no reader event is supplied (`eval.clj:3688-3697`).

**M1 claims shared owners only:** the same reader functions, evaluator and settlement for every entrant, not a
guarantee that each form is parsed once. Once-only reading is its own slice, **S0**. S0 carries the reader event
(exact bytes, reader namespace and aliases, repair evidence) from `reply` through `turn` into `evaluate`, and
reads each successive form after the namespace effects of the form before it. S0 counts reader invocations
before and after. It is unpriced until its reply/turn call graph is traced, and no other slice depends on it.

**Qualified definitions are deferred** (1.10's `(defn other.ns/f …)` clause). The first pass named APIs that do
not exist (`sci/macroexpand-1` in `sci.core`, `my.program/create-ns!`). It also risked two problems:

- expanding once to find the destination and again to evaluate would run the macro twice;
- `*ns*` cannot reinterpret what the reader already resolved.

The later cut separates the reader context (the session's) from the target evaluation and analysis context. It
expands once, under the existing arm, and passes the target namespace and aliases to `definition-rows`, which
receives the originating ones today (`eval.clj:3820-3824`). Its proofs:

- a macro counter shows a single expansion;
- a target alias resolves;
- a reader-resolved `::kw` behaves correctly;
- an unknown namespace is refused by name;
- a private def works;
- the session namespace is unchanged.

Until then, agents use the request namespace or separate `in-ns` and `defn` forms. No shim is authorized.

### 2.2 Eval: one definition authority (P0-A)

**The change set of one evaluated form has two members**, kept apart because they are observed differently:

- **Definitions.** These are the function or test Vars whose root **or metadata** changed.
  - *Observation.* `turn-interns` (`eval.clj:388-426`) already observes them. It walks only the namespace maps
    that are not `identical?` to the acquired program's maps, and it captures values and metadata for Vars
    stamped with this fork's generation. Inherited-root writes are stamped too (`sci/impl/evaluator.cljc:25-49`).
  - *Matching.* Each observed definition must match exactly one analyzed row (`definition-rows`).
- **Removals.** These are the program identities whose intern disappeared.
  - *Observation.* Before the form runs, the evaluator holds SCI's `namespace-state`: the persistent namespace
    map, an O(1) read (`reference-code/sci/src/sci/core.cljc:751-760`). After the form, only namespace maps
    that are not `identical?` to the held ones are diffed, by key. `namespace-interns` (`:732-749`) walks every
    namespace and is **not** used.
  - *Matching.* A removed intern counts as a program removal only when the held before-state gives it a
    **row-backed identity** (`:seon.fn/sym` or `:seon.test/sym` present in the acquired database). It is never
    matched against an analyzed `defn` row. A private Var with no row unmaps with no program write.

The prior roots and metadata are retained separately from the maps, because the maps hold mutable Vars. For a
Var that the fork generation owned before the form, `turn-interns`' before-map supplies them. For an inherited
program Var written for the first time, the root comes from the Var read under the acquired program's
generation. That is unverified: probe **P-S1b** establishes it before S1 is coded.

**Rules**, all at the existing boundary in `evaluate` (`:3820-3834`); there is no new evaluator:

1. **Match or refuse.**
   - An unmatched definition refuses as `:seon.sci.eval/unindexed-change`, a new declared error over
     `:seon.error/base`. It names the Var and form and gives the fix "define it as a top-level form".
   - Anonymous `(fn …)` rows (1.11) are data owned by their evaluation, not program Vars. They are neither
     matched nor refused here.
2. **One declaration per form, for now.** More than one analyzed declaration refuses as
   `:seon.sci.eval/multiple-declarations`, naming each one. A form that both defines and removes also refuses,
   because it would silently choose one row (`eval.clj:3851`); the refusal names both members. These are
   initial constraints, not Clojure restrictions. MCP accepts many top-level forms per submission once R7 goes
   (§2.5), so nothing is lost.
3. **Validate inside the refusal boundary.** Contract conversion (`eval.clj:515-516`) and contract validation
   can throw inside `definition-rows`. The throw is caught into the same declared refusal and restoration.
   Today it escapes before restoration runs. The derived contracts of 1.12 come from the function-face owner;
   this boundary validates whatever contract that owner produces and derives none of its own.
4. **Restore at every failure point**, reusing the evaluation's execution ownership rather than a second
   rollback manager. The failure points are:
   - (a) a throw after a program mutation but before the post-evaluation check;
   - (b) an unmatched or refused change;
   - (c) contract installation or arming failure;
   - (d) a later settlement refusal.

   Restoration puts every observed definition's root and metadata back, and re-interns every removed Var
   into its held namespace map. The private-binding retention after a throw (`:3626-3637`) keeps
   **private, row-less** Vars only; it never keeps a refused replacement of an existing program Var. There are
   two further guards:
   - Restoration never overwrites an intervening acquisition. If the context's loaded-program identity at
     restoration differs from the one captured at the start, the execution is made unavailable instead.
   - A restoration that throws also leaves the execution **unavailable**, named in status with the whole
     cause. It never returns a result that reads as accepted.
5. **Limits.**
   - A post-evaluation check cannot undo IO, atom mutation or calls made inside the form.
   - `:gate` refusals that can run before evaluation still run first (`evaluate-for-install`, `:3944-3966`).
   - Entry policy is unchanged: `:gate` refuses, explicit `:warn` admits with declared diagnostics, and merge
     always needs positive proof.

**Cost.** The work is O(namespaces) `identical?` checks, plus O(entries of the changed namespace maps), plus
O(generation-owned Vars). That matches today's `turn-interns`, with one extra held map per form. UNMEASURED:
S1's landing times it on a one-line `defn` against its parent commit.

### 2.3 Record: what each entrant stores

| Entrant or material | Disposition |
|---|---|
| Product replies and MCP SCI submissions | the same reader, evaluator, install and settlement owners, under named execution custody |
| Generated openings and refreshed reads | the same recording owners (`system-turn`, `record-evaluated-*`); historical text and read evidence kept; writes never replay |
| Test members and task proofs | the same evaluation recorder around the existing capture and bounds (§2.6) |
| Test-selection refusals | the test request's own declared result (no member evaluation exists to record) |
| Raw source, evaluation facts, program rows | source and provenance kept apart from declarations; only the program partition merges |
| Live result objects, private state | existing SCI-context handles (`result/e…`); never serialized; after restart, typed unknown |
| Error occurrences, result/report blobs | existing staging, publication and retention owners; core faults also get cluster visibility and delivery |
| Test evidence on the requesting branch | counts, tested identities, completion and evaluation references survive member unlink |
| Browser cuts, MCP retrieval, host PREPL | one cut entrance, never re-evaluating the origin; the host PREPL's record rule is OPEN 8.6 (§2.4) |

**The durable result reference is the owner's open decision, and this design books no saving from either
side.** Two corrections to revision 2:

- `seon.render.value/artifact` uses the print node as its sole value source (`src/seon/render/value.clj:683-710`,
  at 3785970b9). It is **not** a plain-data artifact, and borrowing its name does not make it one.
- An entity lookup plus basis-t is not a pinned observation across divergent branches unless it also retains
  a database commit identity and history.

A future schema therefore needs its own fully namespaced keys, a retained commit identity and a stated
retention rule. It is **unpriced**, because its representation is not established.

**M1 needs none of it.** M1 returns the shown bytes and a live handle, and it keeps the complete durable error
and test evidence through the existing owners, whatever happens to the success handle.

### 2.4 Print is rendering: one selection, rendered once at the terminal boundary

Selection is per face, and the first match wins (requirement 11.1; AGENTS.md "Render ordinary values totally"):

1. the value's own key;
2. a contract-fitting render function in the **viewing** agent's namespace;
3. the schema's declared pair;
4. the floor.

- **The shown bytes are the evaluating agent's view.** The AI face is rendered once, for the final settled
  success or refusal, and saved as exact bytes. Today an install refusal can replace a preliminary shown
  result (`eval.clj:3955-3966`). The target is that the preliminary render never happens.
- **Another viewer gets a current view,** labelled as such. It is rendered from the same selected value, tree
  inputs, profile and render-program identity.
- **Render inputs come from call preparation (1.13), and selection and invocation share it.** A function must
  not fail selection only because a declared default is missing from the caller's arguments.
  - For an inline function value, the shared owner consumes the value's retained declared contract through
    Malli's `m/schema`, `m/properties` and the cached `m/validator`
    (`reference-code/malli/src/malli/core.cljc:2561`, `:2592`, `:2637`). Derivation follows a change in
    contract content, not every render call.
  - Caller arguments win, and ambiguity refusals are preserved.
  - No contract is inferred from shape, and there is no renderer-only supplier.

  This cut belongs to `call_preparation.clj`'s owner. The claim "all functions get supplied defaults" depends
  on it.
- **The host PREPL keeps the behaviors it needs:**
  - it runs with no database, configuration or projection;
  - `valf` is the floor's AI face under a fixed compiled profile;
  - `:out` and `:err` are bounded at the prepl event collector;
  - the whole cause is kept (`exception-summary`, `cluster.clj:348-366`, goes with audit R6);
  - `loaded-code-mark` (`cluster.clj:513-519`) stays until a host change notifies through an existing owner;
  - `mcp-instance` keeps its runtime-status caller (`cluster.clj:663`);
  - machine responses are never clipped and reparsed.
- **OPEN 8.6 (owner): should MCP JVM evaluations be recorded?** Recommended hybrid: when the call names a
  cluster whose database is available, record the evaluation as **root's**, on that cluster's current branch,
  through the existing `record-evaluated-*` owner. The record is floor-rendered, has no custody and is never
  program. When no database is available (boot-failure diagnosis), record nothing and say
  `:seon.dev.mcp/recorded? false`, so diagnosis never depends on the store. Price: about +30, UNVERIFIED
  (whether a root origin can use that recorder without an agent run is probe P-86). It is outside M1.
- **OPEN 9.5 (owner): may the floor realize a lazy value?** This design needs only one thing from either
  answer: the recorder never realizes a value in order to store it. An unrealized result is kept as a live
  handle, and its durable form is typed unavailable. One tension remains for the owner. Under 5c.3, an agent
  used to `clojure.main` expects `(map inc [1 2])` to print `(2 3)`. The recorded OOMs came from hashing and
  ordering an infinite key, not from printing a bounded prefix. A `*print-length*`-style bounded prefix,
  labelled as realized by the printer, is the alternative to put beside "never realize". That decision
  belongs to the floor owner (`one-render-path-design-2026-09-25.md`).

### 2.5 MCP becomes an entrant: execution custody and completion (P1-D)

**Both modes read many forms.**

- *SCI mode* submits the code as reply text through `submit-source!`.
- *JVM mode* replaces `jvm-evaluation-form`'s single `read-string` (`mcp.clj:486-499`) with a read-eval loop
  over one `LineNumberingPushbackReader`. It keeps `{:read-cond :allow}`, evaluates each form after the
  effects of the one before (as `clojure.core/load-string` does,
  `reference-code/clojure/src/clj/clojure/core.clj:4119-4127`), and returns the last value. Reader EOF and
  other reader failures return with their whole cause.
- `execute-clj-eval` then stops calling `require-single-clj-form!`, so all six R7 definitions
  (`mcp.clj:27`, `:249-331`) retire together. No other bridge scanner replaces them.

**SCI mode requires a branch.** Without one it would mutate the cluster's shared context, and that second world
is retired.

- **Custody on every call.** The root, cluster and branch select the live handle and routing on **every**
  submission and retrieval. The bridge keeps its `[root cluster session-id]` session key (`mcp.clj:685-691`).
  A branch-local agent id does not need to encode these values, because agent rows live in the branch's
  database. It also does not prove ownership.
- **The session requires an explicit `session_id`.** The `"default"` value is not accepted.
- **The agent is `mcp/<session-id>` on that branch,** created and armed by the ordinary agent-creation owner.
  A fork inherits agent rows, but an inherited row is not an armed agent. The bridge arms only the agent it
  created, and it records that ownership in its session entry: the agent id plus the branch it armed on.
- **Settings.** The agent's `:seon.agent/settings` carries `:seon.config.ai/no-provider true`, the per-agent
  layer of §4.4.
- **Namespace continuation** uses the previous run's recorded ending namespace, passed as
  `:seon.turn/starting-ns` (`agent.clj:661`). The bridge keeps no namespace state.
- **Busy.** The run transaction refuses a busy agent. The comment at `agent.clj:638-642` describes an **open
  run**, not an exit fence.
- **The exit fence is Flow's.** One agent graph has one turn proc, and Flow runs one transform at a time on that
  proc's loop thread (`agent.clj:525-545`, citing
  `reference-code/core.async/src/main/clojure/clojure/core/async/flow/impl.clj:305`). A second submission's
  wake is handled only after the first transform returns. Across graph replacement, `arm!` refuses until the
  previous executor has terminated (`agent.clj:1084-1093`). No bridge busy registry is added.
- **Completion.** Before submitting, the bridge registers a listener for terminal run datoms on this branch for
  this agent. `submit-source!` allocates the run id during submission (`agent.clj:697`) and returns it. The
  bridge then filters on that id and rechecks durable state, so a fast close is never missed.
- **Timeout.** The tool returns the run id and the requery form, and retains ownership. The next submission
  is refused as busy until settlement, and there is no replay.
- **Errors since.** The cursor is the previous evaluation's stored basis for this agent (requirement 8.3).
  First and lost observations answer typed initial or unknown. `window-bases` is not recreated.
- **Retirement.** `branch retire` retires only the agents this session **owns**, by the ids in its session
  entry. It never retires every `mcp/*` row. For each owned agent, in order:
  1. `disarm!` (`agent.clj:1277`) joins the executor, which is the actual-exit proof;
  2. the agent is archived;
  3. `release-context!` (`:804`) releases the context;
  4. the branch retires.

  A branch still held by an agent the session does not own keeps today's refusal (`mcp.clj:596-601`).

What the bridge keeps: transport, discovery, sessions, `runtime_status`, `branch`, and a thin `get_value` if
the owner keeps it (§2.3).

### 2.6 Tests enter through the recorder (P1-C)

- **The common recording seam is `turn/evaluation-facts`**, the function that records product evaluations. The
  test owner calls it with a test-member origin. There is no recorder for tests alone.
- **The capture owner wraps the body once.** It encloses the body and returns its typed report, keeping
  fixture and hook semantics (`src/seon/test/runner.clj:359-399`, `:447-469`). This covers SCI members
  (`run-test`, `eval.clj:3978-4005`) and host members (`src/seon/test.clj:254-269`).
- **"Host-bound" means no extra outer SCI arm.** A host member's own function contracts stay armed.
- **Task proofs are ordinary deftests that name their task** (7.3), so they are ordinary members. A
  done-when form compiled into a task-local body (3b.2) goes through the same `seon.test/run` on the attempt
  branch and never counts as coverage.
- **Selection refusals** (missing coverage, an unknown member) are the request's declared result, recorded by
  the test owner as today.
- **Evidence survives member unlink.** It is recorded on the retaining branch before unlink
  (`test.clj:2080-2093`, `:2235-2259`), with member origin and tested identities.
- **The report becomes a value.** `failure-report` and `report-identity` (`runner.clj:160-204`) keep native
  operands, rendered once. This retires audit **R10** (`report-value`, `message-text`, `failure-message`; 50
  lines). Root assigns R10 once, across this budget and the render budget.
- **The candidate gate is one ordinary `seon.test/run` request** on the actual candidate. This retires audit
  **C1** (87 lines), conditional on candidate fixture, input and source-location proofs. Copying C1's behavior
  into the adapter would not retire it.

## 3. REPL-native program change

### 3.1 The change loop

| Step | REPL form | Owner | Status at 19e9fe5f4 |
|---|---|---|---|
| write | `(defn f …)` after `(in-ns 'n)` | reader → `evaluate` → change set (§2.2) → gate → settlement | change-set authority **missing** (§0.2-0.3); MCP unsettled (§0.6) |
| write, qualified | `(defn n/f …)` | §2.1 | **deferred** (1.10 clause) |
| delete | `(my.program/ns-unmap! …)`, `remove-ns!`, or nested native `ns-unmap` | §3.2 | two authorities and a hole (§0.4); **outside M1** |
| read first | `(my.program/callers …)`, `tests-reaching`, `breaks`, `reads-key`, `history` | `src/my/program.clj` | exists |
| test | `(my.test/check {:seon.test/changed ['n/f]})` | `src/my/test.clj:14-28` | exists; cost on branch rows UNVERIFIED |
| merge | root: `prepare-merge!`, then `accept-merge!` | `src/seon/cluster/source.clj:747-844` | replacements only |
| write back | root: `write-back!` after accept | §3.3 | **missing** |

### 3.2 Deletion: one authority, the public forms kept (P0-B)

Requirement 1.10 and AGENTS.md name `my.program/ns-unmap!` and `remove-ns!` as the public deletion forms, and
this revision keeps them. Retiring them in favor of native forms alone would be an authority and API change,
which the re-review flags as needing a root ruling. **The hybrid:** keep the public names, and let **one writer**
decide.

1. **The public forms become native changes in the executing fork.** `ns-unmap!` performs the native unmap in
   the calling execution's fork only. Its own transaction and its base mutation (`my/program.clj:510-546`) are
   deleted. The removal is then observed by §2.2's removal member, exactly like a nested native `ns-unmap`,
   which closes hole (iii) without interception. The reader's `:seon.sci.reader/ns-unmap?` special case
   (`reader.cljc:395-404`) and `unmap-row`'s guard retire. The subject is derived from the removed intern
   itself, whatever form the arguments took.
2. **The writer decides.** Settlement writes the removal with the run. The referrer check (`breaks` blocking,
   plus the schema-owning-namespace refusal) runs **inside the transaction function**, as
   `retract-operation!` does today (`my/program.clj:520-537`).
3. **`remove-ns` is a namespace change, not a set of unmaps.** It changes namespace identity, aliases, imports
   and resolver state, and it can remove a namespace that has no interns
   (`reference-code/sci/src/sci/impl/namespaces.cljc:604-609`; compare `sci-ns-unmap` at `:567-587`, which also
   removes refers and imports).
   - The removal member therefore carries one namespace entry: the namespace name plus its row-backed
     identities.
   - The writer decides that whole entry, keeping the schema-owning-namespace refusal.
   - `native-call-refusal`'s `remove-ns` redirect retires only once `remove-ns!` works through this path.
     `ns-unalias!` keeps its component retraction until aliases join the change set.
4. **Failure order.** The fork changes first, and the database write comes last. §2.2 rule 4 restores the fork
   at every failure point, including a nested unmap followed by a throw, which has already changed the fork.
   No shared base is ever mutated by an agent's call. The base advances through ordinary adoption, and other
   executions detect the loaded-identity change at their boundary.

**Proofs** (root runs; none was run here):

- a top-level `ns-unmap!`, and a nested native `ns-unmap` of a row-backed function, each retract the row in
  settlement;
- a referenced function refuses, and its Var is re-interned in the fork;
- a nested unmap followed by a throw restores the fork and writes nothing;
- `remove-ns!` of a namespace with rows, of an empty namespace, and of a schema-owning namespace (which
  refuses);
- a mixed define-and-remove form refuses, naming both members;
- a private Var with no row unmaps with no write;
- the base is untouched until adoption, and a second agent acquiring the branch head sees neither the Var nor
  the row;
- every caller of the retiring internals is converted in the same slice.

Deletion is still **not merged**: `prepare-merge!` refuses retractions (`source.clj:767`) until R8.

### 3.3 Write-back: prove the candidate before exposing it (P0-C)

`my.edit/form!` is an effect capability that refuses without an evaluation context (`src/my/edit.clj:35-60`;
`effect.clj:724-735`). A digest fence proves fresh bytes, not acceptance or equivalence. Publishing a candidate
and then republishing the originals is not rollback either: observers may already have seen the candidate,
history has advanced, and restoring the files does not undo the accepted cluster rows.

**The route.** Root calls `seon.cluster.source/write-back!` with `[source held-store acceptance]`.
**M1 constrains it to one physical file.**

1. **Capture and authenticate the bases.**
   - `accept-merge!` accretes two members onto its transaction report (open map; `source.clj:803-844`):
     `:seon.source/accepted-identities`, the `delta` it already computes, and `:seon.source/tested-commit`, the
     `e-id` it already reads.
   - The acceptance value is that report plus root's named acceptance. It carries the destination before and
     after commits, the tested commit, the accepted identities and the source-publication commit whose file
     digests are the base.
   - Transaction entities are resolved to identities through the identity owner on the report's `db-after`. An
     unchanged identity attribute need not appear in the tx datoms.
   - **There are two fences, because there are two authorities.** The cluster head must still equal the
     report's after-commit. The source publication head (`current-src`) must still equal the base commit.
     Either move refuses the stale acceptance.
2. **Recover the provenance from the before-state.** A replacement row may intentionally lack file
   coordinates (`src/seon/program.cljc:96-105`), so the base declaration is read from the report's
   **`db-before`**:
   - its `:seon.fn/file` entity, resolved to a path;
   - its exact selector: head (`defn` or `defn-`), name, and dispatch.

   A target with no base file, or one whose file is absent from the base commit's
   `:seon.source/relative-file-digests`, refuses by name. The replacement bytes are the accepted row's stored
   **canonical** source (1.14). Files and rendered context therefore agree.
3. **Build and prove the candidate in memory, before anything is exposed.**
   - Read the file's held bytes and check that their digest equals the base digest.
   - Fold every replacement for the file over **one** candidate string with `seon.edit/form`
     (`src/seon/edit.clj:314-373`: pure, lossless, unambiguous), passing its context-byte bound.
   - Analyze the candidate string with the ordinary analyzer through its existing stdin seam
     (`analyzer/analyze` with `::analyzer/paths ["-"]`, as `src/seon/fn.clj:933-934` does). Analyze the base
     bytes the same way.
   - **Require three things:**
     - every accepted identity's semantic digest in the candidate equals the accepted row's digest;
     - every other declaration in the file has a digest equal to the base;
     - the `ns` form and its requires and aliases (the resolver context) are byte-equal to the base.

     `program/digest-map` deliberately excludes file and lint rows (`program.cljc:462-476`), which is why the
     third, exact check accompanies the semantic ones.
   - Missing evidence, or equal-looking refusal maps, is never proof of equality.

   *Disagreement with the review, with evidence.* The review puts this check inside `publish!`, before its
   guarded pointer update (`source.clj:601-685`). But `publish!` populates from files on disk, so a check
   there comes after the candidate is already on disk and visible to colleagues and hooks. The in-memory proof
   comes before any exposure and uses the same analyzer. It leaves `publish!` without a second verification
   seam.

   *Hybrid.* Prove in memory (this step). Then let `publish!` reindex the real path ordinarily. Then require
   that the published commit's recorded digest for the path equals the candidate's digest, and that the
   analyzer digest is unchanged (step 4). Rows are a deterministic function of bytes and analyzer, so this
   carries the in-memory proof over to the published head.
4. **Fence the installation, and name every failure.**
   - At the existing file writer's installation boundary, recheck that the disk digest still equals the base.
     If a colleague has edited the file, refuse and write nothing.
   - Write the candidate to a temporary sibling and rename it atomically.
   - Call `publish!` with `:seon.source/expected-commit-id` set to the fenced source head. Overlapping
     publication is **detected**, not locked: the pre-scratch check refuses (`source.clj:586-593`), and
     `d/force-branch!` refuses inside it with `:expected-current-commit`.
   - Then check the published digest (step 3's hybrid).
   - **Failures:**
     - If the publication refuses or throws, or the published digest differs, restore the original bytes
       **only while the disk bytes still equal this candidate**. A colleague's intervening edit is never
       overwritten.
     - A failed restore leaves a named **write-back-unavailable** state for the path, with the evidence
       retained.
     - Every failure reports **accepted-but-not-written**: the accepted cluster rows are not undone.
   - **Process loss** after the rename and before publication leaves the disk holding a candidate already
     proven equal. The next ordinary publication converges it. The originals held in memory are not relied
     on.

There is one source owner, one analyzer and the existing guarded publication. No filesystem transaction
framework is added. Additions and retractions in write-back are **R9b**, after R8. Price: S5 in §6.

### 3.4 Milestones, ordered

**M0 — tasks in the codebase** (§3.5). It is file-disjoint from M1 except for `src/seon/cluster/source.clj`: T2
edits the issue-indexing seam at `:512-559`, while S5 edits the merge and write-back functions. One owner at a
time, so T2 lands its `source.clj` hunk before S5 starts.

1. **T1** ingests metadata edges.
2. **T2** adds task data and reconciliation.
3. **T3a** migrates the defects listed in the requirements (7.12).
4. **T3b** migrates the issue notes (7.11).
5. **T4** retires the Markdown indexer.

**M1 — one existing-function replacement lands from the REPL (option B, recommended).** The slices land in order
S1, S2, S5. S3 lands when its recorder seam and the conditional C1 proofs are ready. The flow:

1. A lane creates its branch and opens an MCP SCI session with an explicit `session_id`.
2. It evaluates `(in-ns 'n)` and then `(defn f …)`. Settlement records one row (S1, S2).
3. It runs `(my.test/check {:seon.test/changed ['n/f]})` on the branch. The existing harness is used until S3
   lands, and until then there is no "one path for tests" claim.
4. Root runs `prepare-merge!` with the task, `accept-merge!` with the tested run, and `write-back!` (S5) for the
   one file. Root names the accepter and the tested head.

The proof root runs for M1:

- the same source through the product and MCP entrances gives the same settled row;
- effects run once;
- the admitted row and the complete error evidence are present;
- a second call and an affected caller run the new definition;
- focused tests are tied to its digest, with evidence preserved after member retirement;
- a moved cluster head, a moved source head and a moved file each refuse;
- the accepted row, the file bytes and the reindexed digest agree;
- an injected publication refusal restores only an unmodified candidate, and reports accepted-but-not-written.

Each phase is measured separately, with allocation and retained memory: acquisition, read and analysis,
execution, record and print, test selection, body and recording, merge, candidate proof, and installation. Any
phase over one second names its owner.

**D1 — the first dogfood milestone: a migrated task settles through M1.**

- A lane takes one T3a task whose fix replaces one existing function, and works it through MCP SCI on its own
  branch.
- Its proof deftest names the task (`^{:seon/task …}`).
- The replacement's attribute map drops the id from `:seon/tasks`. That is a metadata change in the same
  accepted row, as 7.6 requires.
- Root merges, and write-back lands the one file.
- `(my.issue/task :id)`, the task reader (§3.5, T2), shows the task as settled.

D1 is the point where agents begin working the migrated backlog.

**M2** adds new functions and tests, deletion merge (R8), additions and retractions in write-back (R9b), S4 and
S0. **M3** adds qualified definitions (§2.1) and the durable result store, following the owner's ruling (§2.3).

### 3.5 Tasks in the codebase: the issue owner evolved in place (7.1-7.12)

**What exists.** `seon.issue` is already the one work family (2.1).

- It stores issue entities keyed by `:seon.issue/id`, the note's slug string
  (`resources/seon/schemas/seon.issue.edn`).
- It holds function and test refs, lifecycle facts (`agent`, `budget`, `resolved-tx`), the detectors in
  `seon.issue.detect`, and the thin `my.issue` surface.
- Publication indexes the Markdown notes through `index-issues!` (`source.clj:512-523`).

The target joins each task from three small places (7.1):

- its data comes from `resources/seon/tasks/<owning-ns>.edn`;
- its subjects are the definitions whose attribute map lists the id (7.2);
- its proofs are the deftests that name it (7.3).

The composition keeps one owner, `seon.issue`. Its input changes from parsed prose to EDN plus analyzer edges.
The lifecycle machinery is reused, not rebuilt.

**The identity decision** (3b.6 rules `:seon.task/id`, a qualified keyword under an identity attribute).
Three priced options, simplest first:

| Option | Guarantee | Cost | Sacrifice |
|---|---|---|---|
| **(a) Transitional second identity (recommended)** | the issue entity gains `:seon.task/id`; T3b sets it on every migrated entity; T4 retires `:seon.issue/id` with the indexer that mints it | T2: +3 schema lines; T4 removes the slug identity | one entity carries two identity attributes between T2 and T4, named as a retirement |
| (b) Keyword ids under the old name | `:seon.issue/id` becomes a keyword | one attribute type change plus a scripted conversion of callers | 3b.6's name is not honoured; `issue` and `task` stay two names for one thing |
| (c) Rename the family to `seon.task` now | one name everywhere | a scripted rename across 38 files by `rg` (the issue namespaces, `seon.plan`, `seon.turn`, `seon.cluster`, `source.clj` and their tests) plus schema files; line-neutral but cross-owner | collides with every lane holding those files; delays D1 |

Option (c) can follow later, as one scripted rename, once its files are free.

**The slices, in order:**

- **T1 — ingestion (7.9, 5a.6, 7.3).** The analyzer's row builder in `src/seon/fn.clj` already reads each
  definition's attribute map, including `:malli/schema` and `:seon.workload`. It stores four more metadata
  keys as native values:

  | Metadata key | Written on | Stored as |
  |---|---|---|
  | `:seon/tasks` | function, namespace and schema rows | `[:set :qualified-keyword]` |
  | `:seon/task` | deftest rows | a qualified keyword |
  | `:seon/must-read` | definitions | a set of qualified symbols or keywords |
  | `:seon/see-also` | definitions | a set of qualified symbols or keywords |

  They are keyword values, not refs: a ref would force the task entity to be populated before the code that
  names it. Test seeds (7.9b) are **T1b**, priced separately and not needed for D1.
- **T2 — task data and reconciliation (7.1, 7.4, 7.5, 1.6).**
  - **Reading.** Task files are read with edamame, quote on (`borkdude/edamame` is already a dependency,
    `deps.edn:57`).
  - **Selection.** They are reconciled by `:seon.task/id` at publication through the **existing** seam:
    `index-issues!`, with its changed-path selection (`note-path?`) widened to task files.
  - **Schema.** Three attributes are added to the entity: `:seon.task/intent`, `:seon.task/must-read` and
    `:seon.task/needs`.
  - **Refusals, at the writer.**
    - A dangling id in code or a test (no task entity) refuses.
    - A duplicate id, an unknown need and a cycle each refuse.
    - A task with no subject and no proof is listed for triage.
  - **Subjects in EDN are allowed only for a subject that does not exist yet** (7.4).
  - **Lifecycle.** `done?` (`issue.clj:1002-1044`) reads proof edges from the deftests' `:seon/task` instead of
    `:seon.issue/tests`. `start!` and the budget and resolved-tx facts are reused unchanged. Attempts and
    decisions stay runtime entities (7.7).
  - **Reader.** `my.issue/task` implements `(task :id)` (7.10, 1.9). It returns the entity with its subject
    ids, proof ids and the calls that expand them.
- **T3a — the first migrated tasks (7.12).** The twelve defects in the requirements' "Defects the runs exposed"
  become hand-authored entries, and their subject `defn`s gain the ids. This is data and metadata only. It
  pilots the format and supplies D1's task.
- **T3b — scripted migration of the issue notes (7.11).** One committed script,
  `script/seon/dev/migrate_issues.clj`, runs once over the full set and is deleted after it lands (git keeps
  it).
  - **Sources.** It reads the live index (`:seon.issue/*` on default) and the note files. No new prose parsing
    is added: the existing index has already parsed the notes once.
  - **Entries.** Each open note becomes a task entry: an id from its slug, its intent from its indexed title,
    and a `must-read` to its path. The entry goes into the task EDN of its owning namespace. That is the
    namespace shared by its cited functions when there is exactly one; otherwise the entry goes to a triage
    file named by the script.
  - **Proof edges.** A note's **explicit test refs** (75 notes) become `^{:seon/task …}` on those deftests.
    These are authored relations, not mentions.
  - **Subject edges are not added mechanically** from cited functions (241 notes, 438 edges). Citations are
    mentions (2.3), and turning them into `:seon/tasks` would inflate definitions with ids they do not own.
    Migrated tasks start **raised** (7.5 triage) with their evidence linked.
  - **Triage.** Setting subjects is triage work: a task whose deliverable is subject edges (2.9). Until then,
    grep finds the mentions in the linked note (1.9).
  - **Accounting.** The script reconciles every note file with the live index and names each of §0.12's 45
    unexplained notes. Closed notes stay Markdown history.
- **T4 — retire the Markdown indexer.**
  - **Deleted:** the prose parser and citation indexer (`issue.clj:35-526`: `words`, `note-opened`,
    `parse-note`, `note-path?`, `notes`, the citation functions, `replacement-tx`, `index-tx`, `index!`), the
    `:seon.issue/id` slug identity and the citation attributes that only the indexer writes.
  - **Converted:** the callers at `source.clj:512-523`, `:559` and `cluster.clj:2013`.
  - **Replaced:** `bin/issues-index` and the ranked README index become rendered views of task data (2.1,
    5.11).
  - **Unchanged:** long designs, research and skills stay Markdown, linked by symbol and pinned `file:line`.
  - **Caller check first.** Before deleting, T4 checks that `adopt-tx` and `citation-pattern`
    (`issue.clj:875-952`) do not share these helpers, and converts or retains them by name if they do.

## 4. Co-location, owner rulings, and configuration

### 4.0 Owner rulings, 2026-09-25

1. **Render selection is per face, and the first match wins** (11.1; §2.4). Schema pairs stay as the shipped
   default. The viewing agent's namespace holds overrides, and values may carry their own. The reverse view is
   a typed `callers` field. About 695 schema properties are references, not duplicate implementations, so no
   migration earns deletion credit.
2. **Supplied defaults go by declared schema name, for all functions; caller arguments win** (1.13). Two gaps
   are named for their owners:
   - the render boundary should stop passing the database as a caller argument (landing 549b4d58c: 47 of 105
     renderers read it);
   - inline function values need preparation (§0.10, §2.4).
3. **Configuration: do the move** (13.1-13.2; §4.4).

### 4.1 The rules, for agents with shell tools

- **G1: write native qualified references.** Metadata and data hold qualified symbol and keyword values. Grep
  finds a spelling; the analyzer resolves identity. `(defn notice-prose …)` does not contain the text
  `seon.error/notice-prose`.
- **G2: a fact lives with its owner** (1.2). A task id appears literally at its definition and at every
  referrer (1.9). Code owns subject edges, tests own proof edges, and EDN owns only the task's own data (7.4).
- **G3: grep finds spellings; the index answers relations,** with analysis-digest evidence and typed unknowns.
  A keyword mention is not a read (`my/program.clj:211-226`).
- **G4: a data input carries its identity:** the resolved documents, digests, layers, environment decisions
  and default provenance (§4.4).

### 4.2 The indexed form a REPL agent queries

| Edge | Stored as | Status |
|---|---|---|
| calls / references / invokes | `:seon.fn/calls` etc. | exists (29,375 test call edges) |
| keyword mentions / writes | `:seon.fn/keywords`, `:seon.fn/writes` | exists (mentions, not proven reads) |
| contract → schema keys | `program/contract-facts` with input/output/guard refs (`program.cljc:1007-1081`), queried by `reads-key` | exists; verify population |
| schema → default renderer | schema property; `breaks` `render-declared-by` | exists; `render-referrers` (`my/program.clj:237-241`) must bind the renderer first (S6) |
| agent namespace → override renderer | contract fit (634d8aaa2) | exists as selection |
| definition → task ids, must-read, see-also | `:seon/tasks`, `:seon/must-read`, `:seon/see-also` values | **T1** |
| test → task | `:seon/task` value | **T1** |
| test seeds → writes and calls | — | T1b (7.9b) |
| config compilation → documents, digests, layers | — | S7 (§4.4) |
| evaluation → value, error, rows | evaluation row + refs | partly; §2.3 |

### 4.3 Each data-file family

| Family | Verdict |
|---|---|
| Schema EDN, 211 files | keep: Malli owns the registry |
| Render pairs in schema EDN | keep as the shipped default (ruling 1) |
| `config/default.edn` dial decisions | move onto the dial schemas (S7) |
| `config/default.edn` `:seon.config/initialization` | move to `config/initialization.edn`, read by name |
| `config/development.edn` | keep, sparse, as a deployment layer |
| `config/test.edn` | delete after a verified no-reader check (D0) |
| Help and getting-started | one REPL-protocol docstring; fix `src/seon/cluster/instruction.clj:23` in that cut |
| **Issue notes `docs/seon/issues/*.md`** | **become task entries (T3b); their bodies stay as linked evidence, never parsed; the index becomes a rendered view (T4)** |
| **Task data** | **`resources/seon/tasks/<owning-ns>.edn`, edamame-read, minimal keys (T2)** |
| Test seeds | keep in code; ingest as edges (T1b) |
| Docs, skills, research | keep Markdown; symbol links for navigation, pinned `file:line` for history |
| Reference code | navigation from task subjects' dependency seams (§5) |

### 4.4 Configuration by situation (ruling 3; slice S7)

**What exists.**

- `compile-settings` merges defaults, then one manifest, then environment (`config.clj:474-525`).
- `registration-defaults` reads `:seon.config/default` from dial schemas (`:154-168`).
- `effective-in` returns only the stored row's dials (`config.clj:772-789`).
- Per-agent settings are merged by `seon.ai/settings` (`src/seon/ai.clj:316-323`).

**The layers, least to most specific** (13.2):

| # | Layer | Lives in | Changed by |
|---|---|---|---|
| 1 | shipped default | the dial schema's `:seon.config/default`, reason in `:description` | a program change |
| 2 | deployment overlays, ordered | sparse EDN documents | `config apply NAME PATH…`, `start --config` |
| 3 | environment | the typed environment map at compile (exists) | the launcher |
| 4 | cluster facts | `:seon.cluster/settings`, of the overlay schema agents use | a transaction; never erased by re-apply |
| 5 | per-agent settings | `:seon.agent/settings` (exists) | the agent or root |

The environment is a compile input that already exists. Requirement 13.2 omits it, and §7 proposes adding it.

**The stored row.** Layers 1-3 are compiled and exact-reconciled as today. **In the same transaction** the row
records the selection and its evidence:

- `:seon.config/layers`: the ordered documents, each as a path relative to the named operator root plus a
  content digest;
- the typed environment decisions that were applied;
- each default's provenance (schema key and schema digest).

This evidence comes from the compiled inputs, not from a second configuration authority. Layers 4 and 5 are
merged at read.

**Apply semantics.**

- An explicit `config apply NAME PATH…` **replaces** the recorded stack.
- `start` without `--config` **reuses** the recorded stack, and `start --config` replaces it.
- Boot (`boot.clj:432`) and publication re-apply (`:664-676`) recompile from the recorded paths, re-reading and
  comparing digests. The hard-coded development branch goes.
- Re-apply triggers on a changed recorded document, **or** a changed dial-schema default, **or** changed
  initialization content. Only clusters whose recorded inputs changed are re-applied.
- `apply-compiled!` retains the cluster settings entity as it retains other clusters' rows.

**The readers.**

- `seon.config/effective` itself derives layers 1-4, taking `[db cluster-name]`. An optional agent id adds
  layer 5, so there is one reader with an optional agent.
- A refused read (`:seon.db/invalid-read`, `:seon.config/error`) propagates **before** any `merge`, since an
  error map is also a map.
- The final merged value is validated against `:seon.config/effective`.
- All nine sites (§0.9) and `turn.clj:4020-4023` call the reader with the cluster name taken from their
  caller's handle or environment.
- The transcript reader (`render/transcript.clj:1180-1182`) keeps its **historical** database value, the
  observation made at opening time. It does not switch to a head read.

**The dial move** is one scripted sweep:

1. Select dials by `:seon.config/dial` membership.
2. Write `:seon.config/default` and fold the reason into `:description`.
3. Keep `:seon.config/available-processors` symbolic.
4. Keep explicit absence as optional-with-no-default.
5. Never write `false` for `no-provider`.
6. Keep `validate-default-decisions`.

**Proof** (root runs, before and after, recorded in the landing):

- `(config/default-decisions)`, `config/defaults` and `(config/default-population)` digests are equal;
- `(config/effective db "default")` is unchanged;
- a `seon.test` seed world gives the same effective map and population;
- `config apply default config/development.edn X.edn` keeps both layers through a publication re-apply;
- a changed dial-schema default re-applies;
- a branch carrying a second cluster's row returns this cluster's dials from every converted reader;
- a refused read surfaces as the refusal, not as a merged map.

## 5. Auto-pulled context, derived by query plus render function

Each pull is an ordinary evaluation of a short named call, built **once as a typed observation at the common
evaluation boundary** and rendered by both faces. Following 1.9 and 5a.3, a pull shows **ids and counts plus
the call that expands them**, never the expansion by default. The exception is the subject's own source, which
renders whole (5.8).

| Class | Where | Query (owner) | Cost |
|---|---|---|---|
| a change was accepted | the settled evaluation | per identity in the accepted change set: caller ids and render referrers as typed fields, reaching-test count, open task ids from `:seon/tasks`, the check form. For a removal: the before-state incoming edges | bounded by matched edges; `render-referrers` bound first; target ≤ 50 ms, UNMEASURED |
| a printer call was introduced | the same observation | new `:seon.fn/calls` ∩ known printers | advice only; macros and aliases make it incomplete |
| a test world | the member's evidence | documents, digests, layers, environment decisions, default provenance (§4.4) | one small map |
| a task's subjects | the task opener | `(task :id)`: subject ids, proof ids, must-reads, triggering observations; hop 1 is content for seeds, hop 2 is addresses (5a.2-5a.3) | measured by 5.10's brief budget |
| a design task | the task opener | the dependency seams its subjects reach, with guarantees; the gitlink pin labels the source shown | one query |
| errors since the last evaluation | every settled response | durable per-execution basis (8.3); first and lost observations typed | exists as the MCP window; moves |

## 6. Slice plan, priced against the audit ledger

**Rules.**

- Deletions are booked **only** from `duplicate-paths-audit-2026-09-25.md` §11, each group once.
- A ledger "source addition ceiling" is included in the slice's added column.
- Rendering groups (R1-R6, R8) are the render budget's. R10 is assigned once by root; this table books it in
  S3, and moves it if root assigns it to rendering.
- Relocated EDN is neither added nor deleted code.
- Lines outside the ledger are shown as **unbooked** and are never subtracted in the net column.
- Every size is an UNVERIFIED estimate until its landing measures the diff, and schema, caller and regression
  costs are shown with it.
- This design's configuration slice is named **S7**, so it cannot be confused with ledger C2, the unrelated test
  snapshot and restore cut.

| # | Slice | Owner files | Added | Booked (§11) | Net | Unbooked retirement | Proof root runs |
|---|---|---|---:|---:|---:|---|---|
| **S1** | definition member, match or refuse, one declaration, mixed-change refusal, validation inside the boundary, restore at four failure points, unavailable-on-failed-restore; two errors | `src/seon/sci/eval.clj`, `resources/seon/schemas/seon.sci.eval.edn` | +45 | 0 | +45 | — | quoted `eval` refuses; `(do (defn a) (defn b))` refuses naming both; throwing contract restores root and metadata; throw-after-mutation restores; P-S1b settled first |
| **S2** | MCP entrant: SCI reply text via `submit-source!`; JVM read-eval loop; custody per call; owned-agent session entry; listen, submit, filter by run id, recheck; busy, timeout, exit via Flow; retire owned agents; errors-since cursor | `script/seon/dev/mcp.clj`, `src/seon/cluster/agent.clj` (creation call only) | +85 | **R7 80** | +5 | — | a `defn` via MCP is one settled row; multi-form JVM input evaluates in order; busy and timeout answer the run id; retire leaves unowned agents; no provider attempt row |
| **S3** | tests through `turn/evaluation-facts`; host members armed with no outer arm; evidence before unlink; native report; candidate gate as one request | `src/seon/sci/eval.clj`, `src/seon/test.clj`, `src/seon/test/runner.clj`, test schema | +95 (C1 45, R10 25, adapter 25) | **C1 87** (conditional) + **R10 50** | −42 | — | a member leaves one evaluation on the retaining branch; failure keeps native operands; the candidate gate records a whole run |
| **S4** | removal member, namespace entry, public forms as fork changes, writer-side decision | `src/seon/sci/eval.clj`, `src/seon/sci/reader.cljc`, `src/my/program.clj` | +30 | 0 | +30 | ≈ −50 of `my/program.clj:510-575` (64 lines shrink to thin forms) | the §3.2 proofs |
| **S5** | write-back of one file: acceptance members on `accept-merge!` (+5), two fences, before-state provenance, in-memory candidate proof, installation fence, guarded restore, unavailable state, accepted-but-not-written report | `src/seon/cluster/source.clj` | +75 | 0 | +75 | — | the M1 write-back proofs (§3.4) |
| **S6** | accepted-change observation (§5 row 1) | `src/my/program.clj`, `src/seon/turn.clj` | +40 | 0 | +40 | — | a renderer replacement shows its schemas; ≤ 50 ms measured |
| **S7** | configuration (§4.4): layer record with environment and provenance, cluster settings, apply replace/reuse, re-apply triggers, one reader with error propagation and final validation, ten callers, dial sweep, `config/initialization.edn` | `src/seon/config.clj`, `src/seon/cluster/boot.clj`, `script/seon/operator.clj`, config schemas, nine reader files, `config/*.edn` | +75 | 0 | +75 | ≈ −30 (nine bespoke queries) | the §4.4 proof list |
| **D0** | delete `config/test.edn` | file | 0 | 0 | 0 | 6 lines | HEAD loads |
| **T1** | analyzer stores `:seon/tasks`, `:seon/task`, must-read, see-also | `src/seon/fn.clj`, `resources/seon/schemas/seon.fn.edn` (and test/ns schemas) | +35 | 0 | +35 | — | a `defn` with `:seon/tasks` publishes the edge; a deftest's `:seon/task` publishes; values are keywords, not strings |
| **T2** | task data: edamame reader, reconcile by `:seon.task/id` through `index-issues!`, four attributes, refusals, `done?` on proof edges, `my.issue/task` | `src/seon/issue.clj`, `src/my/issue.clj`, `resources/seon/schemas/seon.issue.edn`, `src/seon/cluster/source.clj` (seam `:512-559` only) | +85 | 0 | +85 | — | a task file publishes one entity; a dangling id, duplicate, unknown need and cycle each refuse; `(task :id)` shows subject and proof ids |
| **T3a** | the requirements' twelve defects as tasks | `resources/seon/tasks/*.edn`, the subjects' attribute maps | data only | 0 | 0 | — | each id resolves; `(task :id)` for each |
| **T3b** | scripted migration of the issue notes | `script/seon/dev/migrate_issues.clj` (+60, deleted after running), `resources/seon/tasks/*.edn`, proof deftests | +60 transient | 0 | 0 at rest | — | every note file accounted for (including the 45); 75 notes' proof edges present; triage list rendered |
| **T4** | retire the Markdown indexer; index and README as rendered views | `src/seon/issue.clj`, `src/seon/cluster/source.clj`, `src/seon/cluster.clj`, `bin/issues-index` | +15 | 0 | +15 | ≈ −490 (`issue.clj:35-526`) −12 (`source.clj:512-523`), subject to T4's caller check | publication indexes no Markdown; `bin/issues-index` renders from task data; HEAD loads |
| later | S0 read-once; R8 deletion merge; R9b; qualified definitions; durable result store; T1b seeds; 8.6 recording (+30) | — | unpriced | — | — | — | — |

**Deletion accounting, stated plainly.**

- §11 has **no task or issue group**. T1, T2 and T4 add about +135 source lines with **0 booked**. They are
  justified only if root measures the indexer retirement (about 500 lines, `issue.clj:35-526` plus the
  `source.clj` seam) and adds it to the ledger, as it was asked to do for `my.program`'s 64 lines. Until then
  M0 is growth that needs root's explicit acceptance.
- M0's case for that acceptance: it removes a prose parser that violates 1.4, and it lets the ranked index stop
  being hand-maintained.

**Prerequisites, priced.**

| Prerequisite | Needed by | Price |
|---|---|---|
| P-S1b: an inherited Var's prior root under the acquired program's generation | S1 | one read-only REPL probe on a scratch branch |
| the stdin analysis yields `program/digest-map`-comparable declaration digests | S5 | one read-only probe |
| whether a REPL replacement row keeps `:seon.fn/file` (it need not, since S5 reads `db-before`) | S5 | — |
| `my.test/check` cost on branch rows | M1 | root measurement |
| candidate fixture, input and source-location proofs for C1 | S3 | the proofs are the gate |
| the T1 row-builder site in `src/seon/fn.clj` | T1 | UNVERIFIED, one read |
| P-86: root origin through `record-evaluated-*` | 8.6 | one read |

**M1: three options** (AGENTS.md step 4; the simplest viable constraint first):

| Option | Slices | Guarantee | Added / booked / net | Sacrifice |
|---|---|---|---|---|
| A. private durable-edit experiment | S1 + S2 | REPL work settles durably and is testable with the existing harness | +130 / −80 / **+50** | nothing lands from the REPL |
| **B. landable replacement (recommended)** | A + S5 | one existing-function replacement lands REPL → merge → one file, proven before exposure | +205 / −80 / **+125** | growth justified by the merge law; tests stay outside the path until S3 |
| C. B plus one path for tests | B + S3 | as B, and test bodies, reports and evidence use the same recorder and printer | +300 / −217 / **+83** | depends on conditional C1; without C1: +255 / −130 / **+125**, which is B plus report cleanup, with no candidate unification |

B is recommended over C, as the re-review proposes. C's lower net rests on the conditional C1 credit, so S3
lands only when its proofs are ready, and not to balance B's budget.

**Combined first dogfood (D1 = M0 + B):** about +340 source, −80 booked, **+260 net booked**. That becomes
about **−240** if root adds the indexer retirement to the ledger. All of this is growth or neutral until each
landing measures it, and root must accept the repriced growth explicitly.

## 7. Requirement rows still to revise (a diff against 19e9fe5f4; not applied)

The revised rows 1.2 and 1.9-1.14, 3b.6, 7.11-7.12, 11, 12 and 13 already absorb most of revision 2's proposals.
Revision 2's own row numbers 11.x-13.x collided with them and are withdrawn. What remains:

| Row | Change | Proposed text |
|---|---|---|
| 1.10 | stage | "…the entrance admits `(defn other.ns/f …)` **(deferred to M3, §2.1: single expansion, reader versus target context)**; `my.program/ns-unmap!`/`remove-ns!` and nested native `ns-unmap` are removals in the evaluation's change set, decided by one writer at settlement; `remove-ns` is a namespace change…" |
| 1.15 | new | **One definition authority.** A form's changed Vars (root or metadata) match its analyzed rows one-to-one, and its removed interns match row-backed identities; the set is admitted whole or refused whole and restored at every failure point; an unindexed change refuses as such |
| 5c.3 | extend | Also true for MCP and test members: each entrant's result is the stored shown text of its settled evaluation |
| 7.6 | clarify | Settlement's "diff removes the id from its subjects" is the id's removal from the accepted replacement's attribute map, merged and written back with it (D1) |
| 7.11 | refine | Migrated tasks take their proof edges from the note's explicit test refs; cited functions are mentions and are **not** converted to subject edges; subjects are set by triage (2.9); every note file is accounted for |
| 7.13 | new | **Write-back is proven before exposure:** accepted replacement rows are spliced into one candidate, proven equal by the ordinary analyzer in memory, fenced by both heads and the file digest, published ordinarily and checked by digest; a failure restores only an unmodified candidate and reports accepted-but-not-written |
| 8.6 | recommend | Record MCP JVM evaluations as root's, floor-rendered, without custody, **when the named cluster's database is available**; with no database, record nothing and say so (§2.4) |
| 8.8 | new | **An MCP SCI session is a named, owned agent on a branch:** explicit session id; custody selected on every call; completion by listening before submitting, then rechecking by run id; retirement disarms only the agents the session owns |
| 9.5 | note | The recorder never realizes a value in order to store it; place the bounded-prefix alternative beside "never realize" for the owner (§2.4) |
| 13.2 | extend | Add the typed environment between the overlays and the cluster facts; record the stack, environment decisions and default provenance atomically; an explicit apply replaces the stack, a start reuses it; re-apply on changed documents, schema defaults or initialization |
| 13.3 | new | **One effective-configuration reader** derives layers 1-4 for a named cluster (plus 5 for an optional agent), propagates read refusals before merging, and validates the final value; historical readers keep their database value |

## 8. Verification limits

- **Revision-3 probes.**
  - One read-only JVM query of the issue index on default, 8 ms: 405 open, 241 with functions, 438 function
    edges, 75 with tests.
  - A file count: 483 notes, 450 carrying `status: open`.
  - Source reads at 19e9fe5f4: `seon.issue` (indexer, schema, callers), `source.clj` (`publish!`,
    `accept-merge!`, the issue seam), `agent.clj` (`submit-source!`, `turn-step`, `arm!`, `disarm!`,
    `release-context!`), `mcp.clj` (JVM form, SCI form, retire, `execute-clj-eval`), `eval.clj`
    (`turn-interns`, `removed-program-identities`, contract conversion), `config.clj` (`effective-in`), SCI
    `namespace-state` and `namespace-interns`, Clojure `load-string`, and `fn.clj`'s stdin analysis.
  - The audit ledger §11, and the re-review.
- **Not run.** No write, test, adoption, arming or new code was run. PROBE SAFETY was observed: one bounded
  count query.
- **Unverified, to be established by the owning slice:**
  - P-S1b;
  - stdin-analysis digest comparability;
  - the T1 row-builder site;
  - the 45 unexplained open notes;
  - T4's shared-helper check;
  - `my.test/check` cost;
  - P-86;
  - every line estimate in §6.
- **Line counts** come from `sed -n` and `grep -n` at 19e9fe5f4. Ledger figures are the audit's, pinned at
  056ef6fe0.
- **This lane's delta.** Source and test: 0 / 0. Changed path: this file only.
