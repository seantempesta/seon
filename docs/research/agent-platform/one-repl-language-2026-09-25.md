---
type: design
status: revised design (Opus, revision 2), integrating Astra's review 7117f211f, the duplicate-path audit 7c577876d and the owner rulings of 2026-09-25; for root ruling; no production code
created: 2026-09-25
revised: 2026-09-25
lane: design-one-repl-language-opus → revise-one-repl-opus
source-pin: 3785970b9 (HEAD at revision; first pass pinned a34e5d800/8f6eb00ff)
scope: the one REPL path from the agent's side; one definition and deletion authority; REPL-native change with gated write-back; co-location and configuration layering; auto-pulled context; a priced milestone; proposed requirement revisions
---

# One REPL, one language: read → eval → record → print, and where facts live

**What exists.** Seon already has the owners of one REPL path: the reply reader
(`seon.cluster.reply/sources`), SCI evaluation (`seon.sci.eval/evaluate`), ordinary run submission and
settlement (`seon.cluster.agent/submit-source!`, `src/seon/cluster/agent.clj:635`; `turn/evaluation-facts`),
test capture and recording (`seon.test/run`, `seon.test.runner`), program rows, call preparation
(`src/seon/call_preparation.clj`), and one declared render selection with a floor. Three entrants still
bypass parts of it: MCP SCI evaluates without settlement, MCP JVM reads and evaluates separately, and test
bodies are invoked outside the evaluation recorder (duplicate-path audit §5, 7c577876d).

**The principal approach.** Route every entrant through the existing owners instead of adding a path:
MCP SCI submits source through `submit-source!` as a named agent; tests hand their captured body to the same
recorder; evaluation admits a form's program change as one whole set or refuses it; files change only by a
gated write-back of accepted rows. Printing is the one render selection at the terminal boundary. Facts live
with their owner: shapes and render defaults on schemas, dial defaults on dial schemas, initialization rows
in their own file, per-agent choices in the agent's namespace and settings.

## 0. Broken things first

The first-pass probes ran on default (pid 35446, health `faulted`) on the retired SCI branch
`design-one-repl-language-opus`. Astra's review (7117f211f §1) re-scoped each claim; the corrected claims
are below. Revision-2 probes were read-only JVM reads on default and source reads at 3785970b9.

1. **A qualified `defn` into another namespace is refused, in SCI as in Clojure.** Source-confirmed:
   `reference-code/sci/src/sci/impl/analyzer.cljc:839-849` accepts a simple name or the current namespace;
   Clojure `Compiler.java:538-552` likewise. Binding `*ns*` to the target explains the reported success. This
   shows the entrance needs a rule. It does not show the first pass's macroexpansion rule is correct (§2.1).
2. **The entry gate checks rows, not changed Vars.** `definition-rows` (`src/seon/sci/eval.clj:489`)
   emits a row only when analysis matches a changed Var. `refuse-uncontracted-definitions!` (`:456`) checks
   those rows. An `eval` of a quoted `defn` therefore changes a Var with no row and passes unrefused and
   unarmed. The correct name is "an unindexed program change". Calling it "missing contract" would be false,
   because a contracted dynamic definition is lost the same way.
3. **Evaluation keeps only the first definition row.** `evaluate` computes every `var-rows`, arms them, then
   keeps `(first var-rows)` as the singular `:seon.program/row` (`eval.clj:3820-3834`). Turn submission then
   collects one row per evaluation (`src/seon/turn.clj:4887-4894`). A `(do (defn a …) (defn b …))` arms two
   Vars and records one row. This silent loss is P0-A (§2.2).
4. **Deletion has two authorities and one hole.**
   - (i) A top-level `(ns-unmap 'n 'f)` is marked by the reader (`src/seon/sci/reader.cljc:395-404`).
     `evaluate` diffs interns only in that case (`eval.clj:3729-3745`, `unmap-row` `:3132-3172`) and yields
     `program/deletion-row` (`src/seon/program.cljc:1374`), which settles with the run.
   - (ii) `my.program/ns-unmap!` (`src/my/program.clj:548`) calls `breaks`, retracts in its own transaction,
     then runs native unmap on the executing fork **and the shared base** (`:510-546`), with no rollback if
     the native step fails.
   - (iii) A nested native `ns-unmap` (inside `let`, `when`, a function) is neither intercepted
     (`native-call-refusal`, `:625-666`, omits it) nor diffed. The Var disappears and its row survives:
     the 2026-09-24 deletion-run defect.
5. **Entry refusals render with a contract finding.** Partly supported (review §1.3). `evaluate` returns an
   evaluation envelope whose value member is arbitrary (`resources/seon/schemas/seon.sci.eval.edn:32`), and
   `kernel/failure-value` declares the base (`src/seon/sci/kernel.clj:531-533`). The fix separates three
   things: the producer's shape failure, nested-result attribution, and missing named render alternatives.
   Adding an error union to the envelope is not automatically the fix. The offending envelope ("got nil nil")
   was not retained and must be reproduced before a fix is designed.
6. **MCP SCI has no ordinary run or evaluation settlement.** `script/seon/dev/mcp.clj:501-545` calls
   `evaluate` directly, with no run, ordinal or settled row. Evaluated code may still write through its
   owners, and MCP writes result blobs, so "records nothing" was too strong. The tool description admits it:
   "private SCI state, never program rows".
7. **Unbounded printers remain on the MCP path:**
   - `src/seon/cluster.clj:532`;
   - `mcp.clj:434-441` (`printed-value-event`);
   - `src/seon/sci/admit.clj:85-95` (`canonical-edn` is an unbounded `pr-str`, not a bounded encoder);
   - `mcp.clj:333-356`, which accumulates every `:out`/`:err` event.
8. **`config apply` keeps one overlay.** Three places each compose layers differently:
   - boot composes defaults → `config/development.edn` → `--config` (`src/seon/cluster/boot.clj:432-437`);
   - `bin/seon config apply NAME PATH` compiles defaults → PATH only (`boot.clj:630-632`,
     `script/seon/operator.clj:1405-1412`), dropping the development overlay;
   - the publication re-apply compiles defaults → development only (`boot.clj:664-676`), dropping any
     `--config` or applied overlay.

   `apply-compiled!` reconciles exactly (`src/seon/config.clj:661-729`). The last writer therefore erases the
   others, and nothing records which layers produced the row.
9. **Nine readers take an arbitrary cluster's config row.** Each queries `[?c :seon.config/cluster _]` with
   a scalar find (`.`) and so answers from whichever row it meets:
   - `src/seon/turn.clj:2698` and `:4017`;
   - `src/seon/agent.clj:132` and `:186`;
   - `src/seon/repl.clj:71`, `src/seon/issue.clj:1232`, `src/seon/bootstrap.clj:132`;
   - `src/seon/render/transcript.clj:1181`, `src/seon/sci/eval.clj:741`.

   `turn.clj:4019` also reads `:seon.config.eval.result/blob-threshold` from any entity. Probe (JVM,
   read-only, 4 ms): default holds one row today (`#{["default"]}`, `no-provider true`). A branch holds more
   when it inherits rows that another cluster's `:seon.cluster/config` names, because `apply-compiled!`
   retains those (`config.clj:681-693`). A test world forked there reads a stranger's dials.
10. **Inline render functions get no supplied defaults.** `kernel/invoke` applies a function value's
    arguments without call preparation (6d25d9483; `call_preparation.clj:1535`, `callee-identity` nil).
    The render boundary passes the whole unit, database included, as a caller argument (render-dispatch
    landing 549b4d58c). Both conflict with the ruling that declared-but-omitted inputs are supplied for
    **all** functions (§4.0).
11. **Allocation is unattributed.** The first pass reported 31-87 MB per one-line `defn` and 71 MB for one
    `callers` read. There is no per-owner breakdown, warm sample or retained-heap figure, so these are
    observations, not attributions (review §1.7). R-timing in §6 measures them per phase.

These are recorded here as their durable home. Root files issues; this lane commits only this file.

## 1. The failures, and what would have prevented each

Each row was verified against its cited file, commit or report. The review corrected rows 1-4 and 6-8
(7117f211f §2). "Pull" is context placed before the agent unasked. "Grep" is a co-located fact `rg` finds.

| # | Failure (verified source) | Cause | Prevention |
|---|---|---|---|
| 1 | OOMs: a whole overlay `pr-str`'d as `:seon.error/offending` (open-items.md:162-168, 10.8 GB); an infinite `(range)` key hashed by floor code (open-items.md:52-61, 17.8 GB); a >2 GB PREPL answer (fix-schedule-2026-09-23.md:184) | **not one cause**: unbounded printing, and unbounded hashing and ordering before presentation | the value is classified with a bound before any hash, order or path is built; serialization is bounded *during* encoding; one presentation boundary. A namespace "edge" annotation would not have caught the hashing case, so it is dropped |
| 2 | Publication order (5c80755da): `:seon.error/notice` names `seon.error/notice-prose`; schema rows published before function rows | publication was not coherent | **fixed by atomic publication** (5c80755da). The schema owns the relation and the function owns the implementation. The reverse edge is shown by `breaks`/`callers` as a typed field (§5). No migration is needed (§4.0 ruling 1) |
| 3 | Shipped `config/default.edn` carried `no-provider true`: 33 reds and 17 never started (census c :25, :43, :96; fixed 3a7ffa69c) | development policy in the shipped layer; a test world read a document nobody saw | layers are separated (§4.4). The test world reports its resolved documents, digests, applied layers and effective decisions, **and each default's provenance**, since a wrong default is invisible in a diff-from-default view |
| 4 | Refusals dropped data: `(edn/read-string (pr-str response))` at `boot.clj:527-536`; message strings lost names (open-items.md:49) | print-then-read inside the system | the typed refusal value is kept with a durable reference where it must outlive its subject; it is rendered at the edge. A live handle alone does not survive restart |
| 5 | MCP is a parallel REPL (audit §1.1) | a second submission policy and result policy | MCP SCI becomes an ordinary entrant (§2.5); retrieval uses the shared cut entrance. Assignment is by the audit ledger, once |
| 6 | Paused hooks, stale Vars (open-items.md:60, :102-104); a 16,352 ms 2-file publication (boot-regression-2026-09-25.md:40-47) | whole-program recomputation is a **publication defect**, not a cost of files | publication cost follows changed inputs (its owner's cut). REPL work still needs publication, adoption and write-back. A branch that intentionally differs from loaded code is not drift: report adopted upstream identity separately from accepted local delta |
| 7 | False premises (S2b-c landing :36-48; census c hypotheses) | unsupported diagnosis; the graph can also be stale | the task opener pulls positive analysis evidence **and its unknowns** before the first form; an empty graph answer is never proof |
| 8 | Three floor design rounds before prior art (717e01bbb) | the relevant dependency seam was not in front of the designer | a design task receives the dependency seams its subjects reach, with guarantees, not a roster of every checkout |

**Three distinct problems**, not one: unbounded work before or during presentation (rows 1, 4); missing
coherent publication and authority (rows 2, 3, 5, 6); unsupported diagnosis (rows 7, 8).

## 2. The one REPL path, from the agent's side

### 2.1 Read: once, as a target

"Read once" is **a target, not existing behavior** (review P1-A). Today:

- `reply/sources` parses once for admission and again for recovery (`src/seon/cluster/reply.clj:350-359`);
- `plan-sources` discards parsed forms (`:185-218`);
- `turn/planned-sources` repairs the strings (`turn.clj:3112-3140`);
- `evaluate` calls `one-event` again when no event is supplied (`eval.clj:3688-3697`). The "never
  reparsed" comment describes only a supplied event.

The design keeps the ordinary reader event from submission through evaluation: exact bytes, reader
namespace and aliases, and repair evidence. Each successive form is read after the namespace effects of the
accepted form before it. Source is stored for history, never replayed. The cut that does this must count
actual reader invocations before and after. The bridge's own pre-reader (`mcp.clj:27, 249-331`, audit
**R7 = 80** lines) is deleted when MCP SCI submits reply text (§2.5).

**Qualified definitions are deferred** (review P1-B, accepted). The first pass named `sci/macroexpand-1`,
which `sci.core` does not expose, and `my.program/create-ns!`, which does not exist. It also risked two
problems: expanding once to find the destination and again to evaluate runs a macro twice, and binding
`*ns*` after reading cannot reinterpret `::kw`, syntax quote or aliases the reader already resolved.
Dogfooding uses the existing request namespace or separate `in-ns`/`defn` forms.

The later cut must specify two contexts: the reader context (the session's) and the target evaluation and
analysis context (the target namespace's). It expands once under the existing arm, preserves source and
metadata, and gives `definition-rows` the target namespace and aliases (today it receives the originating
ones, `eval.clj:3820-3824`). Proofs required:

- a macro counter showing a single expansion;
- target alias use;
- a reader-resolved `::kw`;
- an unknown namespace refused by name;
- a private def;
- an unchanged session namespace.

It is unpriced until specified.

### 2.2 Eval: one definition authority (P0-A)

**The accepted change set** of one evaluated form is:

- every function or test Var whose root **or metadata** changed (`turn-interns` captures metadata,
  `changed-interns` compares roots, `eval.clj:363-429`);
- every program identity whose Var was removed.

It is matched one-to-one against the analyzed rows. It is admitted whole or refused whole, at the existing
boundary in `evaluate` (`:3820-3834`). There is no new evaluator.

1. **Match.** Each observed mutation must match exactly one analyzed row. An unmatched mutation refuses as
   `:seon.sci.eval/unindexed-change`, a new declared error over `:seon.error/base`. It names the Var, the form
   and the fix ("define it as a top-level form"), and it is never labelled a missing contract.
2. **One declaration per form, for now.** More than one analyzed declaration in one form refuses as
   `:seon.sci.eval/multiple-declarations`, naming them, with the fix "send them as separate top-level forms".
   This is an initial constraint, not a Clojure restriction. MCP accepts reply text with many top-level forms
   once R7 goes (§2.5), so nothing is lost. The general form (a `:seon.program/rows` vector through
   settlement, `turn.clj:4887`) is a later accretion, priced when needed.
3. **Validate inside the refusal boundary.** Contract validation that throws inside `definition-rows`
   (`:499-502`) is caught into the same declared refusal and restoration. Today it escapes before the
   missing-contract restoration runs.
4. **Restore and say so.** A refusal restores every observed changed root **and its metadata** from the
   before-snapshot and returns a refusal, never an evaluation that reads as accepted. The private-binding
   retention after a later throw (`:3626-3637`) stays for private Vars only.
5. **State the limitation.** A post-evaluation check cannot undo a call made inside the defining form, IO,
   or atom mutation. `:gate` refusals that can run before evaluation still do so: a reader event naming
   `:seon.fn/sym` with no `:malli/schema` refuses first (`evaluate-for-install`, `:3944-3966`). The
   post-evaluation match only catches what preflight cannot see. Entry policy is unchanged: `:gate` refuses,
   explicit `:warn` admits with declared diagnostics, and merge always needs positive proof.

Cost: one set comparison over the intern diff the gate already takes, O(changed Vars); UNMEASURED.
Price in §6 (S1).

### 2.3 Record: what each entrant stores

One pipeline does not erase distinct facts (review P1-E; audit §7). The inventory:

| Entrant or material | Disposition |
|---|---|
| Product replies and MCP SCI submissions | the same reader, evaluator, install and settlement owners, under named execution custody; one run and evaluation history |
| Generated openings and refreshed reads | the same evaluation and recording owners (`system-turn`, `record-evaluated-*`); exact historical text and read evidence kept; reads refresh, writes never replay |
| Test members, task proofs, candidate checks | the same evaluation recorder around the existing assertion capture and execution bounds (§2.6); host-bound members remain a classified backend |
| Raw source, evaluation facts, program rows | source and provenance kept apart from declarations; only the admitted program partition merges |
| Live result objects, private state | the existing SCI-context handles (`result/e…`); never serialized; after restart, typed unknown |
| Error occurrences, result/report blobs | the existing staging, publication and retention owners; core faults also get cluster visibility and delivery |
| Test evidence on the requesting branch | typed counts, tested content and input identities, completion and evaluation references survive member unlink; shown text is an observation, not the oracle |
| Browser cuts, MCP retrieval, host PREPL | one cut entrance with server custody, never re-evaluating the origin; the host PREPL alone keeps the no-record exception (§2.4) |

**The durable result reference is an open owner decision.** The audit (§1.4) found two contradictory
plans. README §7 and B1 §3b R5 delete `get_value` and the MCP blobs. Render design §3f keeps `get_value`
and adds artifact digests to evaluations. Root is asking the owner. This design books **no saving from
either side** and prices the store so the ruling can be made:

- *Shape.* One optional `:seon.cluster.eval/result` component with exactly one of three members:
  - `{:entity lookup, :basis-t t}`: a current entity, pinned to its observation basis when historical
    equality is claimed;
  - `{:artifact digest}`: an admitted plain-data artifact reusing `seon.render.value/artifact`, not a print
    node;
  - `{:live handle}` or typed `:unavailable` with its reason.

  Nil and false are real results. Several matching schemas are ambiguity. The schema name comes from
  declared provenance, never a registry scan, and is validated by cached Malli validators.
- *Bounds.* Traversal and byte bounds apply **during** encoding under the evaluation's existing bound.
  There is no post-hoc size check after `pr-str`, no hashing of an opaque key, and no probing of a lazy
  value for finiteness. An oversized or non-EDN value stays a live handle or typed unavailable.
- *Staging.* The blob is staged with settlement, so no cut advertises an unpublished artifact, and a
  referenced artifact survives ordinary GC. This is a retention rule for the store owner.
- *Price (UNVERIFIED):* schema 3 attributes; settlement +30; bounded encoder +40 (replacing the unbounded
  `canonical-edn` body in `sci/admit.clj:85-95`); the MCP artifact write moves, not duplicates. If the
  owner keeps `get_value`, the retrieval side is the render design's shared `prepare` at path and offset.
  If the owner deletes it, the store shrinks to live handles plus entity refs, and cuts of a finished
  evaluation answer "not retrievable". **M1 needs neither** (§3.4): it returns shown bytes and a live
  handle, as today.

### 2.4 Print is rendering: one selection, rendered once at the terminal boundary

Printing is the one render pipeline (owner, AGENTS.md "Apply presentation limits once"). Selection is per
face, first match wins (owner ruling 2026-09-25, AGENTS.md 7fc304482; §4.0):

1. the value's own key;
2. a contract-fitting render function in the **viewing** agent's namespace;
3. the schema's declared pair;
4. the floor.

What this design takes from that:

- **The shown bytes are the evaluating agent's view.** The AI face is rendered once, for the final
  settled success or refusal, at the terminal boundary, and saved as exact bytes. Today an install refusal
  can replace a preliminary shown result (`eval.clj:3955-3966`). That boundary is reorganized so the
  preliminary render never happens: "prints once" is the target, not today's claim.
- **Another viewer renders a current view.** Root through MCP, or a human on the page, may select a
  different renderer at stage (b). That view is labelled current, not "as shown". Both faces take the same
  selected value and tree inputs, profile and render-program identity.
- **Render inputs come from call preparation.** Before calling a render function, the selection reads its
  contract. Inputs it declares but the caller omitted are supplied by declared schema name through
  `seon.call-preparation/hook` (`call_preparation.clj:1497-1547`), as for every function; caller arguments
  win. Gap (§0.10): inline function values skip preparation. That is `call_preparation.clj`'s owner cut,
  not this design's.
- **The host PREPL keeps its exception, and the behaviors it still needs** (review P1-F). Root's MCP
  `jvm` mode records nothing and needs no database, configuration or projection. Its `valf` is the floor's
  AI face under a fixed compiled profile. `:out`/`:err` are bounded at their producer, the prepl event
  collector, not by `valf`. The exception class, whole cause and data are kept; `exception-summary`
  (`cluster.clj:348-366`) loses them and goes with audit **R6**. `loaded-code-mark` (`cluster.clj:513-519`)
  stays until a host `defn` or `ns-unmap` notifies through an existing owner, because a host change does
  not pass through adoption. `mcp-instance` keeps its runtime-status caller (`cluster.clj:663`). Machine
  protocol responses are never clipped and reparsed.

### 2.5 MCP SCI becomes an entrant: execution identity and completion (P1-D)

`eval_clj` with `mode sci` **requires a branch**. Without one it would mutate the cluster's shared SCI
context, which is a second world and is retired. The call submits the code as **reply text** (many
top-level forms, so the bridge's single-form reader, audit R7, is deleted) through `submit-source!`.

- **Identity.** The agent is an ordinary agent row **on that branch's database**, with id
  `mcp/<session-id>`, created and armed by the ordinary agent-creation owner (namespace assigned, routing
  armed). It is not a context acquired with a nil id (`mcp.clj` today).

  *Disagreement with the review, with evidence:* agent rows live in the branch database, and each branch is
  a separate database within one root's store. The id therefore cannot collide across roots, clusters or
  branches. It collides only between sessions on the same branch that share the bridge's `"default"`
  session id.

  *Hybrid:* the tool requires an explicit `session_id` for `sci` with a branch (no `"default"`). A second
  session on the same branch is a different agent.
- **Settings.** The MCP agent's `:seon.agent/settings` carries `:seon.config.ai/no-provider true`, the
  per-agent layer of §4.4. A submission can never cause a paid model turn, whatever the cluster decides.
- **Namespace continuation.** The next submission starts in the namespace the previous run ended in (the
  run's recorded ending namespace). `submit-source!` takes `:seon.turn/starting-ns` for this
  (`agent.clj:661`), so no bridge state is kept.
- **Busy.** The run transaction already refuses a busy agent (`agent.clj:640-641`). The tool returns that
  refusal with the open run's id.
- **Completion.** The tool registers its listener on the run's terminal datom **before** submitting, then
  rechecks durable state after registration, so a fast close cannot be missed. Terminal run settlement and
  actual execution exit are distinct. The tool answers on settlement. It reports "execution still exiting"
  if the agent graph has not released the context, and the next submission is refused as busy until exit.
- **Timeout.** On timeout the tool returns the run id and the requery form. Ownership is retained, and
  there is no overlap or replay.
- **Errors since.** The previous evaluation's stored basis on this agent's runs is the durable cursor
  (requirement 8.3). The first observation and a lost session answer typed initial or unknown, never
  "no errors". `window-bases` is not recreated under another name.
- **Retirement.** `branch retire` today refuses any agent holder (`mcp.clj:596-601`). It is converted to
  archive the branch's `mcp/*` agents after their actual exit, then retire.

What the bridge keeps: transport, discovery, sessions, `runtime_status`, `branch`, and a thin `get_value`
if the owner keeps it (§2.3).

### 2.6 Tests enter through the recorder (P1-C)

A bare `(ns/test-name)` evaluation does not return the assertion report, and wrapping host bodies in an
outer SCI arm causes nested-arm refusals. The design is therefore **a body adapter at the common evaluation
boundary**, not a source form:

- **The capture owner wraps the body.** It encloses the body and returns its typed report value, keeping
  fixture and hook semantics (`src/seon/test/runner.clj:359-399`, `:447-469`). This holds for SCI members
  (`run-test`, `eval.clj:3978-4005`) and host members (`src/seon/test.clj:254-269`). The body runs once.
- **Recording and printing are the evaluation owner's.** The same recorder records the typed value and the
  same terminal render prints it. Host-bound members keep running unarmed, classified as host.
- **Evidence survives member unlink.** Before unlink (`test.clj:2080-2093`, `:2235-2259`), the evaluation
  evidence is recorded through the existing recorder **on the retaining branch**, with member origin and
  tested identities. Test results reference it. Assertion counts, input and digest proof, positive
  completion, unfinished-member ownership and reuse checks stay the test owner's facts.
- **The report becomes a value.** `failure-report` renders expected/actual to strings, and `report-identity`
  hashes them (`runner.clj:160-204`). The report keeps native operands, bounded as values, and is rendered
  once at the boundary. Old identities remain historical. This is audit **R10** (50 old lines, ceiling 25).
  Converting the schema and identity for `failure-report` (25) and `report-identity` (19) earns no extra
  credit (audit §6).
- **The candidate gate uses the ordinary request.** The pre-install candidate test
  (`run-candidate-test!`, `evaluate-candidate`, `candidate-test-result`) becomes one `seon.test/run`
  request on the actual candidate, recording its whole outcome. This is audit **C1** (87 old lines, ceiling
  45). It is conditional on candidate fixture, input and source-line proofs and ordinary settlement.

## 3. REPL-native program change

### 3.1 The change loop

| Step | REPL form | Owner | Status at 3785970b9 |
|---|---|---|---|
| write | `(defn f …)` in the session namespace (or after `(in-ns 'n)`) | reader → `evaluate` → change set (§2.2) → entry gate → settlement | reply path exists; change-set authority **missing** (§0.2-0.3); MCP not settled (§0.6) |
| write, qualified | `(defn n/f …)` from elsewhere | §2.1 | **deferred** |
| delete | `(ns-unmap 'n 'f)`, `(remove-ns 'n)` | §3.2 | two authorities and a hole (§0.4); **not advertised in M1** |
| read before changing | `(my.program/callers …)`, `tests-reaching`, `breaks`, `reads-key`, `history` | `src/my/program.clj` | exists. `callers` returns a breakage **map**; `breaks` has a typed `render-declared-by` relation (`:228-245`). The render reverse edge joins `callers` as a typed field, not a mixed set |
| test the change | `(my.test/check {:seon.test/changed ['n/f]})` | `src/my/test.clj:14-28` → `seon.test/run` policy `:named` | exists; branch-row correctness and cost UNVERIFIED (earlier 1,147 ms was a different request, `:named` refusal) |
| merge | root: `prepare-merge!` then `accept-merge!` | `src/seon/cluster/source.clj:747-840` | exists for **replacements only**; needs an issue on the candidate |
| write back | root: `write-back!` after accept | §3.3 | **missing** |

### 3.2 Deletion: one authority (P0-B)

**The hybrid.** The review's smallest fix keeps `my.program`'s transaction and advances only the executing
fork. This design goes one step further to remove the second authority altogether: deletion becomes part
of the same accepted change set as definition (§2.2), written by settlement.

1. **Detect every removal.** `evaluate` diffs interns for **every** form, not only a reader-marked
   top-level `ns-unmap` (`eval.clj:3729-3745`). The diff is taken from the snapshot the gate already takes.
   The `:seon.sci.reader/ns-unmap?` special case (`reader.cljc:395-404`) retires. A nested native
   `ns-unmap` is then seen like a top-level one, which closes hole (iii) with no interception. The *real
   subject* is derived from the removed intern, `(symbol (ns-name ns) name)`, whatever argument forms
   `ns-unmap` received (namespace object or symbol).
2. **Validate at the writer.** Settlement writes the deletion row with the run. The retraction's
   referrer check (`breaks` blocking, schema-owning namespace refusal, `my/program.clj:520-526`) runs
   **inside the transaction function**, where `retract-operation!` runs it today (`:527-537`). This is one
   writer-side decision, not a pre-read.
3. **Fail in order.** The SCI change happens first, in the executing fork only, and is reversible from the
   before-snapshot. The database write comes last. If settlement refuses, the fork is restored and the
   evaluation returns the declared refusal. If the native step throws, nothing has been written. No shared
   base is mutated by an agent's call. The base advances through ordinary adoption (rows → load → arm →
   record), and other executions detect the loaded-identity change at their boundary.
4. **Retire the second path.** `my.program/ns-unmap!` and `remove-ns!` and their `retract-operation!`
   become redundant with native forms: 64 lines at 3785970b9 (`src/my/program.clj:510-575`). These lines
   are **not in the audit ledger**, so this design books 0 for them and asks root to measure and add them.
   `ns-unalias!` keeps its own component retraction until namespace aliases join the change set.
   `native-call-refusal` keeps `intern`/`alter-var-root` advice.

Proof (root runs):

- a top-level and a nested `ns-unmap` of a row-backed function each retract the row in the run's settlement;
- a referenced function refuses, and its Var is restored in the fork;
- the base is untouched until adoption;
- a second agent acquiring the branch head sees neither Var nor row;
- a private Var with no row unmaps with no program write.

Deletion is still **not merged**: `prepare-merge!` refuses retractions (`source.clj:767`) until R8.

### 3.3 Write-back: the smallest gated route (P0-C)

`my.edit/form!` is an effect capability (`src/my/edit.clj:35-60`, `:seon.effect/capability
'seon.edit.jvm/edit`). A bare root JVM call has no evaluation context and refuses (`effect.clj:724-735`).
A digest fence proves fresh bytes, not acceptance or equivalence. It is **not** write-back.

**The route: accepted rows → file bytes → publication → equivalence, all after `accept-merge!`.**
`seon.cluster.source/write-back!` takes `[source held-store accept-report]` and is called by root:

1. **Read the accepted set.** From the merge commit, read the exact delta `accept-merge!` transacted (its
   tx-data identities). For each replaced function or test, read the row's file (`:seon.fn/file`), source
   and name. Refuse, by name, targets with no trustworthy file or base: a REPL row that did not keep the
   replaced row's file provenance, or a file absent from the destination commit's
   `:seon.source/relative-file-digests`.
2. **Fence each file.** The file's current digest must equal the destination commit's recorded digest,
   otherwise refuse "file moved since publication".
3. **Splice purely.** Call `seon.edit/form` (`src/seon/edit.clj:314`), the pure, lossless, unambiguous
   top-level-form transform the effect handler also uses, with `:replace` and the row's source. An
   ambiguous or missing form refuses.
4. **Write and publish.** Write every changed file, keeping the original bytes in memory, then publish the
   changed paths through the ordinary `publish!` (`source.clj`). This is not a second publication path.
5. **Prove equivalence.** The published head's `program/digest-map` over the delta must equal the tested
   branch's. On mismatch, restore the original bytes, republish, and refuse with both digest maps.

Additions (append to a file) and retractions (remove a span) are **R9b**, after R8. Price: +45, no
deletion (§6 S5). Its justification is the owner's merge law: files are the write-back of accepted rows,
and without this route M1 cannot land.

### 3.4 The milestone, re-cut

**M1: one existing-function replacement, with existing tests, lands from the REPL end to end.** It
deliberately does not claim additions, new tests, deletion or qualified definitions. The steps:

1. A lane creates its branch (`branch create`) and opens an MCP SCI session with an explicit `session_id`.
2. It replaces `n/f` with a top-level `(defn f …)` after `(in-ns 'n)`. Settlement records the row (S1, S2).
3. It runs `(my.test/check {:seon.test/changed ['n/f]})` on the branch. The record goes through the
   recorder if S3 is in scope; otherwise the existing harness is used, with no "one path for tests" claim.
4. Root runs `prepare-merge!` with the existing issue, then `accept-merge!` with the tested run, then
   `write-back!` (S5). Root names the accepter and the tested head.

The root proof for M1:

- the same source through the product and MCP entrances gives the same settled row;
- effects run once;
- the admitted row and complete error evidence are present;
- a second call and an affected caller execute the new definition;
- focused tests are tied to that digest, with evidence preserved after member retirement;
- a moved head and a moved file each refuse;
- the accepted row, the file and the reindexed digest are equal.

Measure acquisition, read and analysis, execution, record and print, test selection, body and recording,
merge, and write-back separately, each with allocation and retained memory. Anything over one second names
its owner.

**M2** (general REPL development) adds new functions and tests, deletion merge, and additions and
retractions in write-back (R8, R9b, and §3.2 proven). **M3** adds qualified definitions (§2.1) and the
durable result store and cuts, per the owner's ruling (§2.3).

## 4. Co-location, owner rulings, and configuration

### 4.0 Owner rulings, 2026-09-25

1. **Render selection is per face, first match wins** (AGENTS.md "Render ordinary values totally",
   7fc304482):
   - **(a)** the value's own `:seon.render/ai` or `:seon.render/html` key: the master override, which may
     be a renderer symbol, an inline function, or rendered data;
   - **(b)** a contract-fitting render function defined in the **viewing** agent's namespace: that agent's
     own defaults. The deleted namespace stage was restored by render-dispatch-opus (634d8aaa2, regression
     47eb7af16);
   - **(c)** the schema's declared pair: the shipped default;
   - **(d)** the floor.

   Render output may be any spec'd shape, rendered in turn.

   **Consequence for C1:** it is no longer a migration. It becomes "keep pairs on schemas, add a reverse
   view, add the agent-namespace override, keep the value override". This composition is better than moving
   pairs onto functions, for four reasons:
   - The schema pair is now the shipped *default*, not the only owner. A relation schema × face → function
     belongs to the schema, and shared renderers serve many schemas (`seon.dev.mcp.edn:2-22`). A scalar
     reverse annotation on the function cannot express that.
   - The demonstrated defect was publication order, and atomic publication fixed it (5c80755da).
   - The owner's co-location goal, "change how I see X next to my code", is met by stage (b). An agent's
     own renderer lives in its own namespace file, beside the code that uses it, and needs no schema edit.
   - The value override covers one-off presentation with no program change.

   The ~695 schema properties are references, not duplicate implementations, so a sweep would add work
   and delete nothing (review §5).
2. **Supplied defaults by declared schema name, for all functions; caller arguments win** (AGENTS.md "Match
   supplied defaults by declared schema name"). Before calling a render function, the selection reads its
   contract. Inputs it declares but the caller omits (the database, the unit) are supplied by
   `seon.call-preparation`. This applies to every entrant in this design: MCP submissions, test bodies
   declaring their inputs, and renderers. Two gaps are named for their owners, not implemented here:
   - the render boundary should stop passing the database as a caller argument, so preparation supplies it
     only when declared (render-dispatch landing 549b4d58c, 47 of 105 renderers read it);
   - inline function values need preparation too (`call_preparation.clj:1535`).
3. **Configuration: do the move** (§4.4):
   - dial defaults and their reasons go onto the dial schemas;
   - initialization data stays with its owner in its own file;
   - configs differ by situation through ordered layers, most specific winning;
   - `config apply`'s one-overlay defect and the arbitrary-row readers are fixed;
   - proof: effective configuration is equal before and after, on default and on a test world.

### 4.1 The rules, for agents with shell tools

- **G1: Write native qualified references.** Metadata and data hold qualified symbol and keyword *values*;
  function-value symbols are not forcibly quoted. The grep promise is narrowed to what is true. `rg` finds
  a spelling; `(defn notice-prose …)` does not contain `seon.error/notice-prose`, and `#:seon.config.ai{…}`
  does not contain the full keyword. Identity is the analyzer's.
- **G2: A fact lives with its owner.** Intent, contracts and links go on the definition; namespace intent on
  `ns`; shapes, render defaults and dial defaults on their declarations. A relation is owned once, by the
  side that decides it. The schema owns its default renderer; the viewing agent's namespace owns its
  overrides; the cluster's facts own its running configuration, not the compiled constant.
- **G3: grep finds spellings, the index answers relations**, with positive analysis-digest evidence and
  typed unknowns. A keyword mention is not a read; `reads-key` says so (`my/program.clj:211-226`). Dynamic
  dispatch and macro references remain explicitly incomplete.
- **G4: A data input carries its identity.** The loader names its path. Configuration compilation
  carries the actual resolved documents, content digests and applied layers into its result and the test
  world's evidence (§4.4). A docstring is not provenance.

### 4.2 The indexed form a REPL agent queries

| Edge | Stored as | Status |
|---|---|---|
| calls / references / invokes | `:seon.fn/calls` etc. | exists (29,375 test call edges) |
| keyword mentions / writes | `:seon.fn/keywords`, `:seon.fn/writes` | exists (mentions, not proven reads) |
| contract → schema keys | `program/contract-facts` compiled by Malli (`src/seon/program.cljc:1053-1081`), arity input/output/guard refs (`:1007-1010`, `:1049-1051`), queried by `reads-key` (`my/program.clj:182-193`) | **exists**. The first pass's "missing" claim and requirement 5a.5 are stale. Verify population and adoption; add no keyword edge |
| schema → default renderer | schema property `:seon.render/ai`/`html`; `breaks` `render-declared-by` | exists; `callers` gains it as a typed field (S6). `render-referrers` queries all pairs then filters (`:237-241`) and must bind the renderer at the database owner first |
| agent namespace → override renderer | a contract-fitting render function in the agent's namespace (634d8aaa2) | exists as selection; no stored edge (the contract fit is the relation) |
| definition → must-read / see-also / tasks | — | missing (5a.6, 7.9); structured metadata first; `[[…]]` parsing deferred (conflicts with 1.4) |
| test seeds → writes / calls | — | missing (7.9b); seeds validated through canonical writers |
| config compilation → documents, digests, layers | — | missing; §4.4 |
| evaluation → value, error, rows | evaluation row + refs | partly; §2.3 |

### 4.3 Each data-file family

| Family | Verdict | Reason |
|---|---|---|
| Schema EDN, 211 files | **keep** | Malli owns the registry; filenames organize one population; one key, one declaration |
| Render pairs in schema EDN | **keep as the shipped default** (ruling 1); add the typed reverse view; overrides live in agent namespaces or on values | §4.0.1 |
| `config/default.edn` dial decisions (lines 1-518) | **move onto dial schemas** (ruling 3, §4.4) | a dial's shape, default and reason in one declaration; provenance stays visible |
| `config/default.edn` `:seon.config/initialization` (line 519 on: call-preparation rows, providers, models) | **keep with its owner in its own file**, `config/initialization.edn`, read by `default-population` and `compile-manifest` (`config.clj:365-371`, `:527-554`) | these are entities, not dial defaults |
| `config/development.edn` | **keep, sparse**, as a deployment layer | the actual fix for development-only policy (3a7ffa69c) |
| `config/test.edn` | **delete after a verified no-reader check**: it uses `#merge/#include "system.edn"`, which the plain-EDN compiler cannot read | dead input; a bounded search found no reader, which is not a load proof |
| Help and getting-started | **consolidate without inferring order from `:require`**: one small REPL-protocol docstring; tool docs derived from task uses and explicit must-reads. Fix the stale "uncontracted defn is scratch" claim (`src/seon/cluster/instruction.clj:23`) in that cut | dependency order is not teaching order |
| Task backlog | **keep minimal EDN** plus code/test id edges; reconcile without overwriting attempts or decisions | — |
| Test seeds | **keep in code** (literal data, qualified fn, inline fn); ingest as edges (7.9b) | — |
| Docs, skills, research | **keep Markdown**; symbol links for current navigation; pinned revision + `file:line` for historical evidence and non-Var material | a moving link cannot cite history |
| Issues | **keep the existing owner** during this cut | one task family is a separate reconciliation |
| Empty `resources/{seed,sample-data,integrant}` | remove if present and unused; **zero line credit** | untracked |
| Reference code | navigation from task subjects' dependencies plus explicit prior-art references; no new store | — |

### 4.4 Configuration by situation (ruling 3)

**What exists.** `compile-settings` merges defaults → one manifest → environment (`config.clj:474-525`).
`registration-defaults` already reads `:seon.config/default` from a dial's schema properties
(`config.clj:154-168`). Three dials use it today, two in `seon.config.db.edn` and one in `seon.test.edn:184`.
The effective map is exact-reconciled as one row per cluster. Per-agent settings exist as an overlay entity
(`:seon.agent/settings`, merged by `seon.ai/settings`, `src/seon/ai.clj:316-323`).

**The layers, least to most specific:**

| # | Layer | Where it lives | Who changes it |
|---|---|---|---|
| 1 | shipped default | each dial schema's `:seon.config/default`, with unit and reason in `:description` | a program change (schema file) |
| 2 | deployment overlays, **ordered and composable** | sparse EDN documents, e.g. `config/development.edn`, then `--config` files | `config apply NAME PATH…`, `start --config` |
| 3 | environment | the typed environment map at compile (exists) | the launcher |
| 4 | cluster facts | a sparse overlay entity referenced by the cluster, `:seon.cluster/settings`, of the same overlay schema agents use | a transaction by root or an authorized agent; never erased by re-apply |
| 5 | per-agent settings | `:seon.agent/settings` (exists) | the agent or root |

The **effective row** stores layers 1-3, compiled and exact-reconciled as today, plus
`:seon.config/layers`: an ordered vector of `{:path … :digest …}` for the applied documents. Layers 4-5
are merged at read, because they are small sparse maps and a stored merge would drift.

**`config apply` records the selection**, so every re-apply composes the same stack:

- `apply!` takes an ordered vector of documents. The operator accepts several paths.
- Boot (`boot.clj:432`) and publication re-apply (`boot.clj:664-676`) recompile from the **recorded** layer
  paths, re-reading each document and comparing digests, instead of hard-coding the development overlay.
- A config-document change re-applies only clusters whose recorded layers name it. This keeps the existing
  cost-follows-changed-inputs check.
- `apply-compiled!` retains the cluster settings entity as it retains other clusters' rows
  (`config.clj:681-693`).

**The one reader.** `seon.config/agent-effective` takes `[db cluster-name agent-id]` and returns
`(merge (effective db cluster-name) cluster-settings agent-settings)`. It is contracted, and it returns the
existing declared errors. All nine arbitrary-row readers (§0.9) and `turn.clj:4019` call it, or
`effective` when no agent is in scope. Each gets `cluster-name` from its caller's handle or environment
(`:seon.boot/cluster-name`); the world is passed explicitly, never discovered. The per-site caller edits
are UNMEASURED.

**The dial move** is one scripted sweep:

1. For every dial (selected by declared `:seon.config/dial` membership, never a namespace roster) with an
   explicit decision in `default.edn`, write `:seon.config/default` on its schema, and fold its unit and
   provenance comment into `:description`.
2. Keep the symbolic `:seon.config/available-processors` resolved in `resolve-smart-decision`.
3. Keep explicit absence as "optional with no default" (`registration-defaults` already maps that to
   `absent`).
4. Never write `false` for `no-provider`, which admits only literal true when present
   (`seon.config.ai.edn:64-72`).
5. `validate-default-decisions` keeps its "missing default for a required dial" refusal.
6. `default-document` stops reading dial decisions; the initialization document is read by name.

**Proof (root runs, before and after the sweep, recorded in the landing):**

- `(= before after)` for `(config/default-decisions)`, `config/defaults` and `(config/default-population)`,
  captured as EDN digests;
- `(config/effective db "default")` on default's live branch is unchanged;
- a test world compiled by `seon.test`'s seed path (`test.clj:157-158`) gives the same effective map and
  population;
- `config apply default config/development.edn X.edn` keeps both layers, and a following publication
  re-apply keeps both;
- a branch carrying a second cluster's row returns this cluster's dials from every converted reader.

## 5. Auto-pulled context, derived by query plus render function

Every pull is an ordinary evaluation of a short named call, rendered through its schema's pair. The pulls
are built **once, as a typed observation at the common evaluation boundary**, and rendered by both faces.
There is no graph work in an AI renderer, which would repeat on page and replay and omit HTML.

| Class | Where | Query (owner) | Cost |
|---|---|---|---|
| a change was accepted | the settled evaluation | per changed identity in the **accepted change set**: callers and render referrers as typed fields (`breaks` shape), reaching-test count, the check form `(my.test/check {:seon.test/changed […]})`. For a retraction: the **before-state** incoming edges | bounded by matched edges plus rendering; `render-referrers` bound to the renderer first; target ≤ 50 ms is an acceptance target, UNMEASURED; large fan-in as counts retrievable through the floor |
| a printer call was introduced | the same observation | the new row's `:seon.fn/calls` ∩ known printers | **advice only**: macros (`with-out-str`, `binding`), aliases and bounded encoders make it incomplete. Existing sink facts are reused (`mcp.clj:43`, `:968`). No namespace "edge" exemption |
| a test world | the member's evidence | resolved documents, digests, applied layers (§4.4), effective decisions relevant to the member, and each default's provenance | one small map per world |
| a task's subjects | the task opener | explicit subjects, must-reads, triggering observations, proof calls; tools may be subjects; raised tasks may have no proofs; negative and deletion observations count; only the form plan is reusable without fresh read evidence | as above |
| a design task | the task opener | the dependency seams its subjects reach (`:seon.fn/calls` into non-first-party namespaces) with their guarantees; the gitlink pin labels which source is shown | one query; no invalidation widened over reference-only gitlinks |
| errors since the last evaluation | every settled response | durable per-execution previous basis → captured current basis (8.3); first observation, lost session and inherited branch errors answer typed initial or unknown | exists as the MCP window; moves |

## 6. Slice plan, priced against the audit ledger

**Rules.** Deletions are booked **only** from the audit ledger (7c577876d §11), each group once. The
ledger's "source addition ceiling" is the replacement cost the audit already assigned to that group. My
"added" column is the whole slice, including that ceiling. Rendering groups (R1-R4, R6, R8) belong to the
render design's budget and are not booked here. Lines relocated between files (config EDN into schemas) are
neither deleted nor added code. All sizes are UNVERIFIED estimates until each landing measures its diff.

| # | Slice | Owner files | Added | Booked deletion (ledger) | Net | Proof root runs |
|---|---|---|---:|---:|---:|---|
| **S1** | one definition authority (§2.2): change-set match, one-declaration constraint, validation inside the boundary, full restore; two declared errors | `src/seon/sci/eval.clj`, `resources/seon/schemas/seon.sci.eval.edn` | +30 | 0 | +30 | `(eval '(defn …))` refuses as unindexed; `(do (defn a) (defn b))` refuses naming both; a contract that throws in analysis refuses and restores root + metadata |
| **S2** | MCP SCI entrant (§2.5): reply text through `submit-source!`, named agent with explicit session, no-provider agent setting, listen-then-check completion, busy/timeout/exit, errors-since cursor, retire conversion; delete the bridge pre-reader and the non-settling template | `script/seon/dev/mcp.clj`, `src/seon/cluster/agent.clj` (agent creation call only) | +60 | **R7 80** | −20 | a `defn` through MCP is a settled row; a second call and a caller see it; evaluation count grows; busy and timeout answer the run id; no provider attempt row |
| **S3** | tests through the recorder (§2.6): body adapter at the common boundary; evidence on the retaining branch before unlink; report as native value; candidate gate as one ordinary request | `src/seon/sci/eval.clj`, `src/seon/test.clj`, `src/seon/test/runner.clj`, test schema | +95 (C1 ceiling 45 + R10 ceiling 25 + adapter/retention 25) | **C1 87** (conditional) + **R10 50** | −42 | a member leaves one evaluation on the retaining branch after unlink; its failure keeps native expected/actual; candidate gate records a whole run |
| **S4** | deletion as change set (§3.2) | `src/seon/sci/eval.clj`, `src/seon/sci/reader.cljc`, `src/my/program.clj` | +20 | 0 (64 measured lines of `my.program` retire, **not in ledger**) | +20 booked | the five §3.2 proofs |
| **S5** | gated write-back of replacements (§3.3) | `src/seon/cluster/source.clj` | +45 | 0 | +45 | accepted replacement lands with equal digests; moved file refuses; mismatch restores bytes |
| **S6** | accepted-change observation (§5 row 1): typed callers + render referrers + reach count + check form, built once at the boundary | `src/my/program.clj` (`callers`, `render-referrers` bound), `src/seon/turn.clj` (settled observation) | +40 | 0 | +40 | a renderer replacement shows its schemas; a retraction shows before-state callers; ≤ 50 ms measured |
| **C2** | configuration (§4.4): layers vector, cluster settings entity, recorded layer selection, one reader + nine callers, scripted dial move, `config/initialization.edn` | `src/seon/config.clj`, `src/seon/cluster/boot.clj`, `script/seon/operator.clj`, config schemas, nine reader files, `config/*.edn` | +45 code | 0 (audit §8.4 books 0) | +45 − caller edits (the nine readers each lose a 3-5 line query: ≈ −30, UNMEASURED, unbooked) | §4.4 proof list |
| **D0** | delete `config/test.edn` after a verified no-reader check | file | 0 | 0 (6 plausible lines, not in ledger) | 0 | HEAD loads |
| later | R8 deletion merge; R9b add/retract write-back; qualified definitions (§2.1); durable result store (§2.3, after owner ruling); C4 link/seed ingestion | — | unpriced | — | — | — |

**Dropped from the first pass:**

- the old R2 (replaced by S1);
- the old R3 "+3" (replaced by S4);
- the old R5 (MCP projection deletion: rendering budget R3/R4/R6 plus the durable-value ruling; ~380 + ~330
  lines were double-counted with R4's template and window);
- the old T1 "+30/−30" (replaced by S3);
- the old C1 sweep (owner ruling 1);
- the old C3 help "−140" (unsupported; a later consolidation);
- C4 as priced (the contract edge exists);
- C5 roster (replaced by §5 row 5).

**M1, three options** (AGENTS.md step 4: a new cross-owner decision; simplest viable constraint first):

| Option | Slices | Guarantee | Added / booked deletion / net | Sacrifice |
|---|---|---|---|---|
| **A. private durable-edit experiment** | S1 + S2 | a lane's REPL work is settled, durable, testable with the existing harness; no landing claim | +90 / −80 / **+10** | nothing lands from the REPL; tests stay outside the path; files stay the working medium for landing |
| **B. landable replacement** | A + S5 | one existing-function replacement lands REPL → merge → file with equal digests | +135 / −80 / **+55** | growth justified only by the owner's merge law; tests still outside the path |
| **C. landable replacement, one path for every entrant (recommended)** | B + S3 | as B, and a test member's body, report and evidence use the same recorder and printer; the only option that satisfies "bound a change by what it lets us cut" | +230 / −217 / **+13** | S3's C1 half is conditional on candidate-gate proofs; if they fail, C degrades to B + R10 (+160 / −130 / +30) |

S4, S6 and C2 are outside M1. C2 is an owner-ordered defect repair with no ledger deletion. It is
justified by §0.8-0.9 (wrong-cluster reads and erased overlays), and nothing smaller fixes the readers than
one reader function. It lets go the nine bespoke queries and the hard-coded development-overlay branches in
boot.

## 7. Proposed revisions to `language-requirements-2026-09-24.md` (a diff of rows; not applied)

| Row | Change | Proposed text |
|---|---|---|
| 1.2 | revise | Code carries what is true of one definition: intent, contract, links. Schemas carry shapes, their default render pair and dial defaults with reasons. An agent's namespace carries its own render overrides. EDN carries overlays, initialization entities and backlog. A fact with two homes is a defect |
| 1.5 | extend | Metadata and data hold native qualified reference values; grep finds spellings, the analyzer resolves identity (G1) |
| 5a.5 | correct | ~~contract is stored only as a string~~: contract facts are compiled by Malli with input/output/guard refs (`program.cljc:1007-1081`) and queried by `reads-key`; verify population, add no edge |
| 5c.3 | extend | Also true for MCP and test members: each entrant's result is the stored shown text of its settled evaluation, rendered for the evaluating agent |
| 5c.4 | revise | One printer at the terminal boundary: the per-face selection (value key → viewing agent's namespace → schema pair → floor) renders the final settled result once; bounds apply during encoding; no namespace exemption |
| 5c.6 | revise | Help is one REPL-protocol docstring plus the docs of tools the task uses or must-reads; one source; order is not inferred from `:require` |
| 5.5 | revise | One value, two faces: shown text (AI) and HTML come from the same selected value and tree inputs; another viewer's render is a current view, never "as shown" |
| 5.5b | new | **Printing is rendering, one pipeline, two consumers** (owner 2026-09-25); retrieving a cut is one entrance, subject to the owner's durable-value ruling |
| 5.12 | new | **Render inputs are supplied defaults.** A render function's declared-but-omitted inputs are supplied by declared schema name through call preparation, as for every function (inline functions included); caller arguments win |
| 8.3 | revise | Errors since the previous evaluation are part of every settled response, from a durable per-execution basis; first and lost observations are typed |
| 8.6 | revise | MCP JVM mode is root's host REPL: io-prepl, floor face under a fixed profile, output bounded at its producer, whole cause kept, no record, no custody |
| 8.7 | extend | The MCP window and `window-bases` are deleted with S2; `get_value` and MCP blobs follow the owner's durable-value ruling |
| **11.1** | new | **One entrance.** Product agents, MCP SCI sessions (named agents with explicit session ids, on a branch) and test bodies (through the capture adapter) are read, evaluated, recorded and printed by the same owners |
| **11.2** | new | **Values before the edge.** A result is a live handle, a pinned entity reference, or a bounded admitted artifact; a non-EDN or oversized value is never printed for storage |
| **11.3** | new | **REPL-native change.** Define in the namespace (qualified definitions later, §2.1); delete with native `ns-unmap`/`remove-ns`; files are write-back of accepted rows |
| **11.4** | new | **One definition authority.** A form's changed Vars (root or metadata) and removed Vars match its analyzed rows one-to-one and are admitted whole or refused whole; an unindexed change refuses as such |
| **11.5** | new | **A settled change carries its reach** as a typed observation built once: callers and render referrers as typed fields, before-state edges for retractions, reaching-test count, the exact check form |
| **11.6** | new | **Write-back is gated.** Accepted replacement rows are spliced by the pure form transform, fenced by the published file digest, published ordinarily, and proven by digest equality or restored |
| **12.1** | new | **Grep finds spellings, the index answers relations**, with analysis evidence and typed unknowns (G3) |
| **12.2** | new | **Data inputs carry identity**: configuration compilation records resolved documents, digests and applied layers; a test world reports them with each default's provenance (G4) |
| **13.1** | new | **Configuration is layered**, least to most specific: dial-schema default, ordered deployment overlays, environment, cluster facts, per-agent settings; the applied overlay selection is recorded and every re-apply composes it |
| **13.2** | new | **One effective-configuration reader** takes the cluster name (and agent) explicitly; no reader takes an arbitrary cluster's row |

## 8. Verification limits

- Revision-2 probes:
  - one read-only JVM read on default (config rows and `no-provider`, 4 ms);
  - source reads at 3785970b9 of `config.clj`, `boot.clj`, `operator.clj`, the nine readers, `eval.clj`
    `evaluate`/`unmap-row`, `reader.cljc`, `my/program.clj` deletion owners, `my/edit.clj`, `edit.clj`,
    `source.clj` merge, and `agent.clj` `submit-source!`;
  - the audit's ledger;
  - the render-dispatch landing.
- No write, test, adoption or new code was run. PROBE SAFETY was observed: only small finite values were
  read.
- Unverified, and to be established by the owning slice:
  - SCI namespace-object argument handling in `ns-unmap` subject derivation;
  - whether REPL replacement rows keep `:seon.fn/file`;
  - writer-side `breaks` inside settlement;
  - `my.test/check` correctness and cost on branch rows;
  - the per-site edits for `agent-effective` callers;
  - every line estimate in §6.
- Line counts: `wc -l` and `sed -n` at 3785970b9. Ledger figures are the audit's, pinned at 056ef6fe0.
- Source/test delta of this lane: 0 / 0. Changed path: this file only.
