---
type: design
status: first-pass design (Opus), for Astra review, then owner ruling; no production code
created: 2026-09-25
lane: design-one-repl-language-opus
scope: the one REPL path from the agent's side; REPL-native program change; co-location of facts in code vs data files; auto-pulled context per failure class; proposed requirement revisions; a priced slice plan to dogfooding
---

# One REPL, one language: read → eval → record → print, and where facts live

**What exists.** Seon already has the pieces of one REPL path, but in three copies. The agent
reply path reads a reply once (`seon.cluster.reply/sources`), evaluates each form in SCI
(`seon.sci.eval/evaluate`, `src/seon/sci/eval.clj:3401`), records evaluations and program rows by
settling the run (`seon.cluster.agent/submit-source!`, `src/seon/cluster/agent.clj:635`), and stores
the exact shown text. MCP has its own evaluator template, its own printer, its own error window and
its own blob store (`script/seon/dev/mcp.clj`, `src/seon/cluster.clj:247-658`). The test harness
invokes test Vars through its own capture (`seon.sci.eval/run-test`, `eval.clj:3994`, into
`seon.test.runner/run-vars!`).

**The principal approach.** Delete the copies. MCP and tests become *entrants* that submit forms to the
one entrance and read back the one record. There is one print per evaluation, done once by the
declared renderer or the floor and stored as bytes. Everything before that print is a value. Facts about
code live in the code form that defines it. Data files are kept only for facts that are not about one
definition: shapes, deviations and backlog. Each data file is joined to code by fully qualified names
that grep finds and that the index turns into typed edges.

## 0. Broken things first (found while probing, 2026-09-25)

These probes ran on default (pid 35446, started 14:24:44Z, health `faulted`, 20 open faults
including "JVM RESTART NEEDED: loaded dependencies changed (reference-code/clay …)"). They ran on the
disposable SCI branch `design-one-repl-language-opus`, which is now retired, in scratch namespaces
`scratch.one-repl-{a,b}`. Every value was small and finite.

1. **A qualified `defn` into another namespace is refused today, in SCI as in Clojure.**
   `(defn scratch.one-repl-a/probe-dec … )` evaluated in `scratch.one-repl-b` gave
   `:seon.cluster.eval/error "Var name should be simple symbol."` (1 ms). The refusal comes from
   `reference-code/sci/src/sci/impl/analyzer.cljc:846-848`, and Clojure refuses the same way at
   `reference-code/clojure/src/jvm/clojure/lang/Compiler.java:552` ("Can't create defs outside of
   current ns"). Both readers accept a qualified name *whose namespace is the current one*
   (`analyzer.cljc:845-846`; `Compiler.java:542`). Probe: `(binding [*ns* (the-ns 'scratch.one-repl-a)]
   (eval '(defn scratch.one-repl-a/probe-dec … )))` from `scratch.one-repl-b` →
   `#'scratch.one-repl-a/probe-dec`, 25 ms, 31 MB allocated. So the AGENTS.md law "defn … by its fully
   qualified name" needs one entrance rule (§2.1). No library change is needed.
2. **`eval` of a quoted `defn` bypasses the contract entry gate.** A direct `(defn probe-bare-2 [x] x)`
   refused correctly ("declares no :malli/schema", 47 ms, 87 MB). `(binding [*ns* …] (eval '(defn
   scratch.one-repl-a/probe-bare [x] x)))` was **admitted** (`#'scratch.one-repl-a/probe-bare`, 24 ms).
   Cause, from the source: `refuse-uncontracted-definitions!` (`eval.clj:431-462`) refuses only rows
   that the source analysis produced (`definition-rows`, `:464`). A Var that changed with no analyzed row
   passes. The interned-Var diff (`turn-interns`/`changed-interns`, `:363-429`) does see the Var, but
   the gate never asks about Vars without rows. This is a gate defect, and the same class as the
   "two definition authorities" item in the requirements file.
3. **Entry refusals render as contract findings.** Both refusals above ended with "Contract finding:
   seon.sci.eval/evaluate returned this error, and none of its declared error alternatives matches it"
   (and, for probe 1, the same about `seon.sci.kernel/failure-value`, with "got nil nil"). The render
   ruling e7c4deb4c is working as designed, which exposes that `evaluate`'s and `failure-value`'s
   declared error unions omit the missing-contract refusal and SCI analyzer errors. For the agent the
   result is ugly: two sentences of meta-diagnosis around a one-line fix.
4. **MCP SCI evaluation records nothing.** After two `defn`s on the branch, `(seon.db/pull
   '[:seon.fn/sym] [:seon.fn/sym 'scratch.one-repl-a/probe-inc])` → `nil`. The count of
   `:seon.cluster.eval/source` rows stayed 108 → 108 across evaluations. The Var is callable
   (`(scratch.one-repl-a/probe-inc 1)` → `2`) only as private SCI state. This is outside-agents B3,
   still true.
5. **`callers` misses the render edge.** `(my.program/callers {:seon.program/subject
   'seon.error/notice-prose})` → `#{seon.error-test/… seon.render/invoke-selected}`, 32 ms, **71 MB
   allocated**. It does not show `:seon.error/notice`, which names it as `:seon.render/ai`. Only
   `breaks` joins `render-referrers` (`src/my/program.clj:228-246`, `:317`). That is failure 2
   (§1) in miniature: the edge exists in the index, but the read agents reach for first does not
   show it.
6. **Unbounded printers remain on the MCP path:** `src/seon/cluster.clj:532` (`pr-str` of a projection
   failure with `*print-length* nil`), `script/seon/dev/mcp.clj:440` (`printed-value-event`, the same),
   and `src/seon/sci/admit.clj:89` (`canonical-edn`). `collect-prepl-response!` (`mcp.clj:333-356`)
   keeps every `:out`/`:err` event with no bound, which is the ~230-line clj-kondo echo.
7. **Allocation per definition is high:** 31–87 MB per one-line `defn` (source analysis), and 71 MB for
   one `callers` read. None is over a second, but each is far above what the work is proportional to.

These findings are recorded here, with their exact forms, as their durable home. Root files issues;
this lane commits only this file. Probe 1 is the premise the owner asked us to verify. Probes 2–4 are
gaps the dogfood slices close (§6: R1, R2, R4).

## 1. The failures, and what would have prevented each

Each row was verified against its cited file, commit or document. "Pull" means context the entrance or
turn places in front of the agent without being asked. "Grep" means a co-located fact that `rg` finds.

| # | Failure (verified source) | Cause in REPL terms | Pull that prevents it | Grep that prevents it |
|---|---|---|---|---|
| 1 | Four OOMs: a whole overlay as `:seon.error/offending` pr-str'd by the runner (open-items.md:154, 10.8 GB dump); an infinite `(range)` map key hashed by new floor code (open-items.md:44, 17.8 GB); a >2 GB answer (repl-investigation D7, fix-schedule-2026-09-23.md:184) | text built far from the edge: error data and test reports printed to strings before storage | when a new definition calls a printer (`clojure.core/pr-str`, `prn`, `print-str`, `with-out-str`, `binding *print-length* nil`) outside a namespace declared as an edge, the response shows the calls and the rule: "store the value; the floor prints" | the edge is declared in `ns` metadata (`^{:seon.ns/edge true}` on `seon.render.value`, `seon.print`, the MCP bridge), so `rg seon.ns/edge` lists every lawful printer |
| 2 | Publication order (5c80755da): `:seon.error/notice` in `resources/seon/schemas/seon.error.edn:241` names `seon.error/notice-prose` (`src/seon/error.clj:1157`); schema rows were published before function rows | one edge, two owners, two files, two transactions | at `defn` of a renderer, the response shows which schemas declare it (`render-referrers`). Better: the edge lives on the function (§3.3), so no second file exists | `rg seon.error/notice-prose` already finds both files; the grep was never run because nothing prompted it |
| 3 | Shipped `config/default.edn` carried `no-provider true`: 33 reds + 17 never started, two 120 s hangs (census c class 1; fixed 3a7ffa69c) | a test world read a document nobody saw it read | a test member's world names the config documents it compiled and every dial value that differs from the dial's schema default | the dial's default and provenance live on the dial's schema (`:seon.config/default`, already read at `src/seon/config.clj:159-172` for 3 dials), so `rg :seon.config.ai/no-provider` shows the one place its default is decided |
| 4 | Refusals dropped their data: boot's `readable-response` round-trips through `(edn/read-string (pr-str response))` (`src/seon/cluster/boot.clj:527-535`); `refused!` message strings lost the missing names (open-items.md:49) | printing and re-reading inside the system; message text standing in for data | the response carries the refusal *value* under its handle, and prints it only at the edge | the same edge declaration as row 1 |
| 5 | MCP is a second system: own projection, window, blob store, get_value, stderr echo; JVM mode floor-only by ruling (open-items.md:59) | a second evaluator, printer and result store | none needed: deleted by §2.3 | — |
| 6 | Shell editing: hook publication paused (open-items.md:92); stale Vars (default up over old rows, "18 stale Vars", open-items.md:51); a 2-file `:selected` publication ran `full-source-refresh!` for 16,352 ms (`boot-regression-2026-09-25.md:40-44`) | files are the working medium, so every change must be re-derived from files by whole-program publication | each response states the branch's program identity against the loaded identity, and shows the replaced-root count when it is non-zero | files become write-back (§2.5); grep stays true because write-back lands accepted rows in files |
| 7 | False premises: S2b-c item 1 "mostly false" (`landing/lane-harness-s2b-c-2026-09-25.md:36`); census hypotheses falsified (census c, "Root's hypotheses" table) | lanes asked `rg` a relational question | a task opener evaluates `(my.program/callers …)`, `(my.program/tests-reaching …)` and `(my.program/breaks …)` for the task's subjects before the agent's first form (v0.3 §4) | the rule in §3.1 G3: relational questions go to the index |
| 8 | Three failed floor design rounds (2d9f3641a, 59649109e, 9f839573e) before prior art (717e01bbb, Clerk/Erlang/Orchard) | a design task started without the libraries that already solve it | a design task's opener lists the libraries its subjects call, plus the reference-only checkouts in `reference-code/` (clerk, portal, reveal, fipp, puget, orchard …), each with its README path | skills and docstrings cite `[[lib.ns/var]]`, not `path:line` (`reference-code-as-context §4.3`) |

**The common cause.** Rows 1, 4 and 5 are one defect: a string made before the edge. Rows 2, 3 and 6
are another: a fact whose owner is a file that no evaluation reads. Rows 7 and 8 are a third: a question
answered by a tool that cannot answer it. The design below takes each apart once.

## 2. The one REPL path, from the agent's side

### 2.1 Read: once

An entrant hands the entrance **reply text** in the agent's namespace: one or more forms with `;;`
comments. The one reader, `seon.cluster.reply/sources` followed by the one reader event
`evaluate` consumes (`eval.clj:3418`, "source is never reparsed"), yields each form's value and its exact
bytes. clj-kondo analyzes those same bytes into rows (`definition-rows`, `eval.clj:464`). That is
analysis, not a second parse for evaluation. What disappears: the MCP bridge's hand-rolled form
skipper (`mcp.clj:249-332`), JVM `read-string` of a re-printed form, and the print-then-read round
trip of generated openers (`turn/declared-sources`, turn.clj:1880-1896; render pipeline B3).

**Qualified definitions (new entrance rule).** A top-level form that macroexpands (`sci/macroexpand-1`,
repeated until the head is `def`) to a `def` of a qualified symbol `n/x`, where `n` is not the current
namespace, is evaluated with `*ns*` bound to `n`. The session's namespace does not change. This is
probe 1's `binding` form, done by the entrance. `n` must be an existing program namespace on the branch,
or the entrance refuses by name (`:seon.sci.eval/unknown-definition-namespace`, naming `n` and
`(my.program/create-ns! 'n)` as the fix). The body then resolves aliases in `n`, as its file would.
The rule is derived by macroexpansion, not a hand list of def-like heads. It costs one macroexpansion per
top-level form (sub-millisecond, UNMEASURED).

### 2.2 Eval → record: what every form stores

One evaluation row per form (`:seon.cluster.eval/*`, exists) on the entrant's branch:

| Stored | Form | Status |
|---|---|---|
| source bytes, namespace, ordinal, run, author, `ms`, allocated bytes, read evidence | datoms | exists |
| program rows the form defined or retracted | fn/test/ns/schema rows, same transaction as the run settlement | exists for reply turns; **missing for MCP** (probe 4) |
| the result **value** | (a) an entity with identity → the identity, a ref, never a copy; (b) plain data under `:seon.config.eval.result/max-bytes` → a content-addressed blob of canonical EDN plus its declared schema name when one validates; (c) anything else (fns, atoms, objects, infinite seqs) → live in the SCI context under the handle `result/e…`, with class name; after restart it is typed unknown, never re-evaluated | (c) exists; (a)/(b) partly (MCP writes blobs at >4 KB, `cluster.clj:494`; the reply path stores only shown text) |
| the error | the flat `:seon.error` value under its declared schema, recorded as an occurrence on the branch (requirement 8.2) | exists / lane |
| printed output (`:out`) | text, bounded at storage by `max-bytes` (`eval.clj:3502-3520`) | exists |
| **shown** | exact bytes, printed once at record time by the declared renderer or the floor (`shown-result`, `eval.clj:3186`) | exists |

The value is never printed to be stored. Probe 6's `canonical-edn` is an *encoding* of plain data
into a blob, not a print for a reader. It must refuse non-EDN values (case c) instead of printing them.

### 2.3 Print is rendering: one pipeline, two consumers

Owner ruling 2026-09-25 (AGENTS.md 15f9c2b28): printing *is* rendering, and it has two consumers, AI
agents through MCP and humans through the browser, on one happy path. The print step is therefore the one
render pipeline of `one-render-path-design-2026-09-25.md` (§1 selection by declaration, §3 floor,
0b83d61a5, under Astra review). This design uses that pipeline and does not redesign it:

```text
value (live under a handle, entity, or stored artifact digest)
  → select   one selection by declared identity: the declared render function, else the floor (§1)
  → node tree  shell-order selection under one budget B, cuts carrying (source, path, offset) (§3a–3d)
  → face     :seon.render/ai → clipped text via fipp layout   |   :seon.render/html → explorable hiccup (§3d–3f)
consumers    MCP tool result, provider prompt (AI face)        |   browser page, debug side-by-side (HTML face)
```

- **The AI face is computed once per evaluation, at record time, and saved as the exact shown bytes**
  (`shown-result`, `eval.clj:3186`; AGENTS "save their exact text"). Every AI consumer reads those
  bytes: the provider prompt (`seon.repl/render-ai` over stored rows), the MCP tool result and the debug
  page's left column. The HTML face is rendered at the page from the same stored value, never clipped.
- **Retrieving a cut is one entrance for both consumers.** Given a cut request (an artifact digest or
  entity lookup, a path and an offset), it is `prepare` at that path and offset under the same B, as the
  floor design specifies (§3f). The result is a node tree whose own cuts are again cut requests. MCP
  `get_value` asks for the AI face of that tree. The browser's expander (`GET /data`, `route.clj:25`)
  asks for the HTML face. Both are thin transports: they parse coordinates, call the one entrance with
  the server's custody, and render the face they need. The value must have a stored artifact
  (§2.2 b). The floor design names this durable write as a root data decision. A live-only value answers
  "not retrievable: no stored artifact".
- **The JVM host REPL** (MCP `jvm` mode, root only, no custody, requirement 10.3) is Clojure's own
  io-prepl whose `valf` is the same pipeline's AI face under a fixed compiled profile
  (`seon.render.value/floor`). It needs no database, configuration read or projection, and it records
  nothing, because it is the diagnostic REPL that must survive failure of every Seon layer
  (repl-investigation braid 1). This is the one named exception to "record". Root's host REPL is not an
  agent entrant. It is still the same printer.

**Duplicate machinery that goes away.**

- *The MCP projection and window in `src/seon/cluster.clj`:*
  - the ThreadLocal hand-off (`project-next-prepl-value!`, `consume-mcp-projection!`);
  - `mcp-instance` and `mcp-effective` (a config read per value);
  - `first-seon-frame`, `exception-summary` and `mcp-projection-error`, whose unbounded `pr-str` is at
    `:532`;
  - `mcp-project` and the projection branch of `mcp-valf`, replaced by a `valf` of about 5 lines that
    calls the floor;
  - the test-only duplicate `mcp-io-prepl`;
  - per-result blob writes at the projection (`:494`), replaced by settlement's one artifact write
    (§2.2 b);
  - `mcp-get-value`'s own blob read, `render.data/at`, and its string-window/`window` branch
    (`:555-597`). These become the cut entrance above. The floor design's slice 2 already converts the
    window.
  - the `loaded-code-mark` page invalidation, since adoption already signals it.

  Together that is about 380 of the 412 lines at `:247-658`.
- *MCP-only renderers and event echo:*
  - `seon.error/mcp-prose` (`src/seon/error.clj:2086`), declared on the 5 `:seon.dev.mcp/*-error`
    schemas (`resources/seon/schemas/seon.dev.mcp.edn`). These errors take the error base pair like every
    other error (one pair per entity schema). mcp-prose was also one of the two candidates in the
    render-ambiguity class (agent-repl-reality §1.3).
  - the bridge's elision fabrication (`elision-value` and the `enrich-*` walk, `mcp.clj:371-433`, whose
    requery refusal says "the MCP projection retained no stable identity for this cut"). Cuts now come
    from the tree with real coordinates.
  - `printed-value-event` (`mcp.clj:434-441`), an unbounded re-print.
  - the unbounded `:out`/`:err` echo (`collect-prepl-response!`, `mcp.clj:333-356`). Output belongs to
    the evaluation's `:seon.cluster.eval/output`, bounded at storage (`eval.clj:3502-3520`), and in JVM
    mode it goes through the same face under B.
  - the bridge's own error window and its `take 20` (`window-form`, `branch-errors!`, `window-bases`,
    `:632-684`). These are replaced by the entrance response's errors-since-last-evaluation (8.3).
  - the SCI evaluation template (`sci-evaluation-form`, `:501-545`) and the single-form validator
    (`:249-332`).

  Together that is about 330 lines of `script/seon/dev/mcp.clj`. The bridge keeps transport,
  discovery, sessions, `runtime_status`, `branch` and a thin `get_value`.
- *Browser-only retrieval and projection:*
  - `/data`'s own cursor-to-value path (`data-response`, `web.clj:3511`, via `data/parse-cursor`,
    `data.clj:27-44`) keeps only coordinate parsing and calls the cut entrance.
  - the debug outline toggle between "As the model saw it" and "Rendered" (`transcript.clj:1574-1612`)
    becomes the side-by-side view of the same evaluation (floor design §3f).
  - `seon.render.transcript`'s second projection of an agent session (its own history, entry order and
    full/summary/elided decisions; render pipeline B7) reads the same prompt-unit vector as the provider
    prompt. The line count of that retirement is the least certain estimate (≈500, needs a caller audit).

**MCP `sci` mode becomes an entrant.** `eval_clj` with a branch submits the code as a reply of a named
agent, `mcp/<session>`, whose `:seon.agent/branch` is that lane branch. This fixes "the MCP entrance
acquires with a nil agent id" (open-items.md:162). The submission goes through `submit-source!`, then
awaits the run's close datom through the existing listener, bounded by the tool timeout. On timeout the
run keeps its ownership and the tool answers with the run id and the requery form. The tool result is the
stored shown bytes, the handle, and the branch's errors since the previous evaluation (requirement 8.3,
now in the entrance response, so every entrant gets it, not only MCP).

**Tests become an entrant.** A member already runs as "fork the context onto a test branch, inject
custody, run one body, unlink" (`seon.test/run`, `src/seon/test.clj:2057`). The change: the body is
invoked as one evaluation of the form `(ns/test-name)` through `evaluate` on the member branch, with
`clojure.test` report events captured as data (the runner keeps its capture; `run-test`,
`eval.clj:3994`, becomes a call to `evaluate`). The member's failure report is then an evaluation value,
printed by the same floor, and the member's evaluation row is the evidence. The requirement "attempt writes
no evaluation row" (open-items, harness S4) closes as a side effect. The runner's own bounded report
text (`report-identity`, `src/seon/test/runner.clj:185-204`) becomes the shown text of that evaluation,
not a second printer.

## 3. REPL-native program change

### 3.1 The change loop, and what exists

| Step | REPL form | Owner | Status |
|---|---|---|---|
| write | `(defn f …)` in `n`, or `(defn n/f …)` anywhere | entrance → `evaluate` → `definition-rows` → entry gate → settle | exists for reply turns; qualified form **missing** (R1); gate hole (probe 2, R2); MCP does not record (R4) |
| delete | `(my.program/ns-unmap! 'n/f)`, `(my.program/remove-ns! 'n)` | `src/my/program.clj:548,562`: `breaks` first, retract, then unmap in fork and base | exists. The native `clojure.core/ns-unmap` is **not intercepted**: `native-call-refusal` (`program.clj:625-666`) covers `remove-ns`, `ns-unalias`, `intern`, `alter-var-root` only (the 2026-09-24 deletion run's "program row survives" defect). Custody supply of `:my.program/context` through call preparation (`src/seon/call_preparation.clj:108`) is UNVERIFIED on a branch |
| read before changing | `(my.program/callers …)`, `tests-reaching`, `breaks`, `reads-key`, `history` | `src/my/program.clj` | exists; `callers` omits render referrers (probe 5) |
| test the change | `(my.test/check '[n/f])` → `seon.test/run` `:changed` on the branch | `src/my/test.clj`, `src/seon/test.clj:2057` | exists; selection measured 1,147 ms refusing a private test (astra v0.3 review) and 1.9 s / 6.5 GB for a saturated `tests-reaching`: cost defect, dependency of R6/R7 |
| merge | root: `prepare-merge!` / `accept-merge!` | `src/seon/cluster/source.clj:698-834` | exists for replacements; **refuses additions and retractions** (`:767`); no `my.*` entrance; no named accepter |
| write back | accepted rows → files | — | **missing** (D1 stage 6); pieces exist: row spans (4,819/4,820 functions), `my.edit/form!` digest-fenced splice (`src/my/edit.clj:35`), publication reindex |

### 3.2 Files are write-back

The branch's rows are the working program. Accepted rows land in files so that grep agents, git and
review see them. Write-back of a replacement splices the row's source bytes into the row's stored span,
fenced by the file digest the span was indexed from. An addition appends to the namespace's file after
its last form. A retraction removes its span. Publication then reindexes and must yield the same
definition digests, or it refuses (the AGENTS "reindexes to prove equivalent declarations" law). Nothing
about write-back is a second publication path: it produces bytes that the ordinary publication indexes.

### 3.3 Shortest path to dogfooding (the milestone)

**Milestone M1: our lanes code primarily through the REPL.** A lane creates its branch
(`branch create`), defines and deletes through MCP `sci` mode on that branch, tests each change with
`(my.test/check …)`, and reports the changed symbols. Root lands by write-back of the lane's
replacements. Slices R1–R4 and R7 reach M1. Until R8/R9, root's write-back is an explicit JVM call of
`my.edit/form!` per changed row, from the row's source and span. This is a hand step, but a fenced one.
**M2** (additions/retractions merge and automatic write-back) needs R8–R9.

Why this order: R4 alone makes a lane's REPL work durable and testable (today it evaporates, probe 4).
R1 lets a lane change `seon.x/f` from its scratch namespace without `in-ns` juggling, which a
dynamically nested `in-ns` makes unsafe (issue `dynamic-in-ns-cannot-persist-definition-namespace.md`).
R2 keeps the gate honest once agents write through the REPL.

## 4. Co-location: which facts live where

### 4.1 The rule, for agents with shell tools

- **G1: Name things literally, in full.** Every reference in code, data files, docs and tests uses the
  fully qualified symbol or keyword, never an alias and never a string. Then `rg 'seon.error/notice-prose'`
  finds the definition, every schema that renders with it, every test and every task. That is the
  grep contract. Aliases in code bodies are fine: the index resolves them. In attribute maps,
  schema files, config and docs, write the full name.
- **G2: A fact lives with its subject.** A fact about one definition goes in that definition's form
  (docstring, attribute map). A fact about a namespace goes in the `ns` form. A fact about a data shape
  goes in the schema file named after the key's namespace (`resources/seon/schemas/<key-ns>.edn`). A
  fact about configuration goes in the dial's schema declaration. Overlay files hold only deviations.
- **G3: grep finds where a thing is said; the index answers who uses it.** Any question containing
  *who calls, what reaches, what renders, what reads, what breaks* goes to `my.program`
  (`callers`, `tests-reaching`, `breaks`, `reads-key`). `rg` misses alias-resolved calls, macro-generated
  calls, dispatch, render-by-name and every test-reach relation. Rows 7 and 8 of §1 are this rule broken.
- **G4: A data file is never an anonymous input.** The function that reads it names the path in its
  docstring (`seon.config/defaults` reads `config/default.edn`, `config.clj:30-31`), so `rg default.edn`
  finds its reader. The test world's context (§5) names which files it compiled.

### 4.2 The indexed form a REPL agent queries

These are rows already in the database (`:seon.fn/*`, `:seon.test/*`, `:seon.ns/*`, `:seon.schema/*`,
evaluations, errors, issues), joined by typed edges:

| Edge | Stored as | Status |
|---|---|---|
| calls / references / invokes | `:seon.fn/calls` etc., indexed symbols | exists (29,375 test call edges) |
| keyword mentions / writes | `:seon.fn/keywords`, `:seon.fn/writes` | exists (mentions, not proven reads) |
| schema → renderer | schema property `:seon.render/ai`/`html` | exists as a property; `callers` omits it (probe 5) |
| contract → schema keys | — | **missing** (requirement 5a.5; contract is a string) |
| definition → must-read / see-also / tasks / `[[wikilinks]]` | — | **missing** (5a.6, 7.9; reference-code-as-context §4.3) |
| function → dials it reads | join `:seon.fn/keywords` with dial schemas (`:seon.config/dial true`) | derivable now, no new attribute |
| test world → config documents compiled | — | **missing**: one value on the member evaluation |
| evaluation → value, error, rows it defined | evaluation row + refs | partly (§2.2) |

### 4.3 Each data-file family: keep, move into code, or derive

| Family (today) | Verdict | Reason | Failure it prevents |
|---|---|---|---|
| Schema EDN, 211 files `resources/seon/schemas/` | **keep** as data, one file per key namespace | shapes are data; Malli owns the registry; G1/G2 make them grep-joinable | — |
| Render pairs inside schema EDN (350 AI + 345 HTML properties) | **move into code**: the render function declares the schema it renders, `(defn notice-prose {:seon.render/ai :seon.error/notice …})`; the schema → renderer map is derived from function rows, and two functions claiming one schema/output refuse at the writer | one edge, one owner, one transaction: the publication-order class cannot recur; `callers`/`breaks` read it as a function attribute | #2 |
| `config/default.edn` (657 lines, ~105 dials with provenance comments) | **move into the dial schemas**: `:seon.config/default` (the mechanism exists, `config.clj:159-172`, used by 3 dials) and provenance in `:description`; `seon.config/defaults` is derived from the registry | a dial's shape, default and reason sit in one place; tests and boot compile the same derived constant | #3 |
| `config/development.edn` (12 lines) | **keep**, sparse overlay | a deviation is data about one cluster, not about a dial | — |
| `config/test.edn` (6 lines, `#include "system.edn"`, a file that does not exist) | **delete** after verifying no reader | dead input | #3 class |
| Help: `seon.bootstrap/help-value` literal (13 lines), seeded `:getting-started` instruction (`src/seon/cluster/instruction.clj:12-29`) | **move into code**: `(help)` prints the docstrings, with examples, of the tool namespaces the agent's namespace requires, ordered by the require graph | help cannot drift from the functions it teaches; one source, not two contradicting ones (render pipeline finding 6) | #7 |
| Task backlog (`resources/seon/tasks/` retired in 6780e926a; requirement 7.1) | **keep** EDN for authored tasks, with ids referenced from code (`:seon/tasks`) and tests; runtime tasks are database writes (`seon.task/create!`); both reconcile by id | a backlog is not one definition's fact; grep needs it on disk | #7 |
| Test seeds (`:seon.test/seed` on deftest) | **keep in code** (already) | — | — |
| Long-form docs, skills, research | **keep** Markdown; replace `path:line` citations with `[[ns/var]]`; claims about a function move to its docstring | citations that drift make agents read the wrong code | #7, #8 |
| Issues (`docs/seon/issues/`) | **keep** (indexed by `seon.issue`) | — | — |
| Empty `resources/{seed,sample-data,integrant}` | **delete** | noise for grep agents | — |
| Reference code (`reference-code/`) | **derive**: nav target and task-opener roster, no rows (reference-code-as-context Q1 c / Q4 a) | prior art must be visible without a new store | #8 |

The two "move into code" rows are mechanical sweeps (scripted once, then lint and load) and each needs an
owner ruling. §6 prices three options for the render-pair move.

## 5. Auto-pulled context, derived by query plus render function

Every pull is an ordinary evaluation: a short named call whose value renders through its schema's pair.
It is never an inlined selector (the 109 KB faults form, agent-repl-reality §1). Each is placed where its
failure happens.

| Class | Where it is pulled | Query (owner) | Render | Cost |
|---|---|---|---|---|
| a definition changed (#2, #6, #7) | the response of the evaluation that defined or retracted rows | per changed symbol: `callers` + `render-referrers` + `tests-reaching` counts; branch program identity vs loaded identity | `;; n/f: 2 callers, 7 reaching tests, renders :seon.error/notice · (my.test/check '[n/f])` | callers 32 ms / 71 MB measured (too much allocation: fix at the owner); `tests-reaching` today 1.9 s saturated, **must be cheap first** (R7) |
| a printer was introduced (#1, #4) | the same response | the new row's `:seon.fn/calls` ∩ the printing functions of `clojure.core`, when the row's namespace is not `^:seon.ns/edge` | one line per call site with the rule | set intersection on one row, microseconds (UNMEASURED) |
| a test world (#3) | the member's evaluation value | the documents the world compiled, plus dials whose effective value ≠ the dial schema default | a small map under its schema | one diff over ~105 dials, sub-ms (UNMEASURED) |
| a task's subjects (#7) | the task opener (v0.3 §4) | `callers` / `tests-reaching` / `breaks` for each seed | the forms and their current values | as above |
| a design task (#8) | the task opener | libraries called by the subjects (`:seon.fn/calls` into non-first-party namespaces) + reference-only checkouts (`git submodule status` over `reference-code/`, memoized by gitlink commit) | names and README paths only, no source | one query plus one cached submodule list |
| errors since the last evaluation (8.3) | every response | `seon.error/occurred-since` on the entrant's branch | error pairs, bounded by the floor | exists (the MCP window), moved into the entrance |

## 6. Slice plan, ordered and priced

Each slice is ≤100 added lines unless marked **waiver**. "Root runs" lists the proof. Costs are
estimates (UNVERIFIED) until the slice's landing measures them.

| # | Slice | Owner files | Added / deleted | Proof root runs |
|---|---|---|---|---|
| **R1** | Qualified definition: the entrance binds `*ns*` to a qualified `def`'s namespace (§2.1); unknown namespace refuses by name | `src/seon/sci/eval.clj` | +25 / 0 | regression: `(defn n/f …)` from another ns on a branch → row for `n/f`, session ns unchanged; unknown ns refuses |
| **R2** | Gate hole: a changed function Var with no analyzed row refuses as a missing contract; `evaluate`'s and `failure-value`'s error unions name the entry refusals (probe 3) | `src/seon/sci/eval.clj`, `src/seon/sci/kernel.clj`, `resources/seon/schemas/seon.sci.eval.edn` | +20 / −5 | probe 2 form refuses; direct refusal renders without a contract finding |
| **R3** | Intercept `clojure.core/ns-unmap` → `my.program/ns-unmap!` | `src/my/program.clj` | +3 | the 2026-09-24 deletion run: the row is retracted, or `breaks` refuses |
| **R4** | MCP `sci` mode submits through `submit-source!` as agent `mcp/<session>` on the lane branch; awaits the run's close; returns the shown bytes + handle + errors since the last evaluation; delete the SCI template and window | `script/seon/dev/mcp.clj`, `src/seon/cluster/agent.clj` (named-agent acquisition) | +50 / −150 | a `defn` through MCP is a row on the branch; a second call sees it; the evaluation count grows |
| **R7** | Per-change test from the REPL: `(my.test/check '[n/f])` on the branch; remove whole-population selection work (1,147 ms refusal) | `src/seon/test.clj` | ≤60 / ? (probe first) | `--changed` on a branch touches only reaching members, <1 s selection |
| — | **M1 reached**: lanes code through the REPL; root writes back replacements with `my.edit/form!` | — | 0 | one lane lands a replacement end to end |
| **R5** | MCP `jvm` mode: io-prepl whose `valf` is the pipeline's AI face; delete the MCP projection, `mcp-prose` and the bridge elision/echo (§2.3); `get_value` and `/data` become transports onto the one cut entrance, which is the floor design's slice 2 `prepare` at path/offset. **Sequenced after** that slice lands, so the conversion is done once | `src/seon/cluster.clj`, `script/seon/dev/mcp.clj`, `resources/seon/operator/prepl.clj`, `resources/seon/schemas/seon.dev.mcp.edn`, `src/seon/error.clj` (`mcp-prose`) | +30 / −650 | a 200k vector, `(range)` and a throwing form each answer bounded; one cut fetched by `get_value` and by `/data` gives the same node coordinates; D1 desync regression |
| **R6** | Response reach footer (§5 row 1) and printer finding (§5 row 2) | `src/seon/repl.clj` (evaluation AI render), `src/my/program.clj` (`callers` joins render referrers) | +60 | a renderer `defn` shows its schema; a `pr-str` call in a non-edge ns shows the rule |
| **R8** | Merge additions and retractions; retained tx-data (write-back-and-custody option B) | `src/seon/cluster/source.clj`, `src/seon/program.cljc` | ≤100 | added and retracted functions merge; fence refuses a moved head |
| **R9a** | Write-back of replacements by span splice, digest-fenced; reindex equality | `src/seon/cluster/source.clj`, `src/my/edit.clj` | +50 | accepted replacement lands in the file with an equal digest |
| **R9b** | Write-back of additions (append) and retractions (remove span) | same | +60 | same, for add and delete |
| **T1** | Tests enter through `evaluate` (§2.3) | `src/seon/sci/eval.clj`, `src/seon/test.clj` | +30 / −30 | a member leaves one evaluation row; failure report is its shown text |
| **C1** | Render pair on the function (§4.3): **waiver** for a scripted sweep of ~695 properties; code about +60 | `src/seon/render.clj`, schema EDN, renderer namespaces | +60 code / −695 EDN properties, +~350 metadata entries | publication of a renderer and its schema in either order; duplicate claim refuses |
| **C2** | Dial defaults into dial schemas: **waiver**, scripted sweep of ~105 dials; `config/default.edn` deleted | `src/seon/config.clj`, `resources/seon/schemas/seon.config*.edn`, `config/default.edn` | +20 code / −657 EDN, +~105 properties | effective config equal before/after on default and a test world |
| **C3** | Help from tool namespace docstrings; delete `help-value` literal and the `:getting-started` instruction | `src/seon/bootstrap.clj`, `src/seon/cluster/instruction.clj` | +30 / −140 | `(help)` output lists each required tool ns once, in require order |
| **C4** | Ingest `:seon/must-read`, `:seon/see-also`, `:seon/tasks`, `[[wikilinks]]` (EDN read at `[[`) and contract keys as edges | `src/seon/fn/analyzer.clj`, `src/seon/fn.clj`, schema EDN | ≤100 | edges queryable; a dangling link is a typed finding |
| **C5** | Design-task roster pull (§5 row 5) | task opener owner | +30 | a design task shows reference-only checkouts |
| **D0** | Cleanup: delete `config/test.edn` and the empty resource dirs after verifying no reader | files | −6 | HEAD loads |

**Three options for C1** (the render-pair owner). The recommendation is marked.

1. *Keep the pair on the schema; publish schemas and functions in one transaction (done, 5c80755da);
   `callers` joins render referrers (in R6).* Cost: 0 more. Guarantee: the order defect is fixed, and
   agents see the edge. Sacrifice: two owners for one edge remain; a grep agent editing the renderer must
   still find the schema file.
2. **Recommended: the pair moves onto the function's attribute map; the schema → renderer map is derived.**
   Cost: about 60 code lines plus one scripted sweep. Guarantee: one owner, one file, one transaction; a
   duplicate is refused at the writer. Sacrifice: a schema read alone no longer shows its renderer
   without a join. The render selection reads a derived index.
3. *Schemas move into code as `def`s beside their renderers.* Cost: days (211 files; the Malli registry
   from code). Guarantee: full co-location. Sacrifice: data shapes become program rows that merge and
   write back, which widens R8–R9 before they exist.

## 7. Proposed revisions to `language-requirements-2026-09-24.md` (a diff of rows; not applied)

| Row | Change | Proposed text |
|---|---|---|
| 1.2 | revise | Code carries what is true of one definition: intent, contract, links, **the schemas it renders, the dials it reads by name**. EDN carries shapes, overlays and backlog. A fact with two homes is a defect |
| 1.5 | extend | Every reference outside a function body is written fully qualified, so grep finds every home of a name (G1) |
| 5c.3 | keep, extend | Also true for MCP and test members: each entrant's result is the stored shown text of its evaluation |
| 5c.4 | revise | One printer, at the edge: the declared renderer or the floor prints once per evaluation, at record time; nothing before it builds a string to clip; a namespace that prints declares `^:seon.ns/edge` |
| 5c.6 | supersede | Help is the docstrings of the tool namespaces an agent requires, ordered by the require graph; no help-topic EDN, no instruction entity |
| 5.5 | revise | One value, two edges: the shown text (AI) and HTML render from the same evaluation and entity values |
| 7.1 | keep | (unchanged; the EDN backlog is the grep view of authored tasks) |
| 8.3 | revise | The errors since the previous evaluation are part of the entrance response for every entrant, not an MCP window |
| 8.6 | revise | MCP JVM mode is root's host REPL: io-prepl + floor printer, no record, no custody, no projection |
| 8.7 | extend | Also delete the MCP window, `window-bases` and per-result projection blobs; `get_value` stays only as a transport onto the one cut entrance |
| 5.5b | new | **Printing is rendering, one pipeline, two consumers** (owner 2026-09-25): select → node tree → face; MCP and the provider prompt consume the AI face, the browser the HTML face; retrieving a cut is one entrance (`prepare` at path/offset) that `get_value` and `/data` share; no consumer-only renderer, echo or retrieval route |
| **11.1** | new | **One entrance.** Product agents, MCP `sci` evaluations and test members submit forms to the same entrance; each form is read once, evaluated once, recorded once (evaluation row, value storage §2.2, error occurrence, program rows) |
| **11.2** | new | **Values before the edge.** Everything before the print is a value under its Malli schema: live under a handle, a datom, or a content-addressed blob; a non-EDN value is never printed for storage |
| **11.3** | new | **REPL-native change.** `defn` in the namespace, or by qualified name from anywhere (the entrance binds the target namespace); delete with `my.program/ns-unmap!`/`remove-ns!`, and the native forms are intercepted; files are write-back of accepted rows |
| **11.4** | new | **The gate sees every changed Var**, not only analyzed rows: an interned function without an analyzed row refuses |
| **11.5** | new | **A definition's response carries its reach:** callers, render referrers, reaching-test count and the check form, with measured cost ≤ 50 ms |
| **12.1** | new | **Grep finds declarations, the index answers relations** (G3); the task opener and the skills say so where the agent is |
| **12.2** | new | **Data files name their reader and their readers name them** (G4); a test world reports the documents it compiled and every dial that deviates from its schema default |

## 8. Verification limits

- Probed (SCI, disposable branch, retired): qualified `defn` refusal; in-namespace qualified `defn` under
  `binding`; `do`/`in-ns` then `defn` (`["scratch.one-repl-a" 6]`, 45 ms, 50 MB); the entry gate for a
  direct `defn` and its bypass through `eval`; no row and no evaluation record from MCP; `callers` of a
  renderer. The first branch evaluation took 1,085 ms wall. Its profile window included concurrent test
  work (`seon.test/member-result` etc.) from other callers, so that time is not attributable to the
  `defn` (38 ms evaluation).
- Not probed: `my.program/ns-unmap!` custody supply on a branch (a write); `submit-source!` await
  semantics for R4; any test request (load rule); the cost of `tests-reaching` today; the render-pair and
  dial sweeps.
- Line counts come from `wc -l`/`awk` over the checkout at a34e5d800. Deletion estimates are ranges
  over named functions, not measured diffs.
- Source/test delta of this lane: 0 / 0. Changed path: this file only.
