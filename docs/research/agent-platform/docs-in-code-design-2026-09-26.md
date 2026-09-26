---
type: design
status: "Opus first pass for the owner's decision (three priced options, §4); Astra review not yet run; no source edited"
created: 2026-09-26
scope: Docs move into the codebase and the database. Status of every language-plan row about docs, links and tasks; an inventory of the 1,755 Markdown files with a disposition per kind; how docs are parsed, stored, linked, written back and checked for staleness; three priced options and the migration order
source-pin: HEAD 0818f4497. REPL probes read-only in JVM mode on default, first pid 48787 (started 17:02:56Z), then pid 80197 (started 17:26:52Z; root replaced the JVM during this lane, not this lane)
aligned-with: language-requirements-2026-09-24.md §§1, 2, 5, 5a, 7; references-as-refs-design-2026-09-25.md §3e and its review; long-form-docs-and-markdown-2026-09-23.md; clojure-native-doc-metadata-2026-09-23.md; decoration-language-v0-2026-09-24.md; one-repl-language-2026-09-25.md §4.3; open-items.md item 4b
---

# Docs in the code: parse, store and link documentation as data

**What exists.**
- Docs are already data in the running database, in two places:
  - docstrings, stored by the one analyzer's producers as `:seon.fn/doc` and `:seon.ns/doc` (`src/seon/fn.clj:234`, `:787`);
  - schema `:description`, which lives inside the stored `:seon.schema/form` string. Renderers read it back with
    `edn/read-string` (`src/seon/render/ns.clj:179-185`).
- Typed links exist. `task-links` (`src/seon/fn.clj:616-640`, landed in 8ccdd694b) stores:
  - `:seon/tasks` as string handles;
  - `:seon/must-read` and `:seon/see-also` as resolved symbol edges.
- Task EDN in `resources/seon/tasks/<ns>.edn` is reconciled at publication by `index-tasks!`
  (`src/seon/cluster/source.clj:526-557`).
- A second, older path parses Markdown issue notes by hand: `seon.issue/parse-note`, `words` and the citation index
  (`src/seon/issue.clj:38-530`). It is the one place Seon parses prose.

**The principal Clojure approach.**
- A doc is a fact on its owner's row:
  - a function's doc lives in its `defn`;
  - a namespace's doc in its `ns` form;
  - a data shape's doc in its schema `:description`;
  - a unit of work in a task entry.
- Links are native values that the one producer resolves:
  - `:seon/must-read` and `:seon/see-also` quoted symbols;
  - docstring `[[sym]]` links, read from the nextjournal/markdown parse tree (root ruling Q2). No Seon regex reads them.
- Links are stored as the value edges that already exist, with `:seon.db/reference-to` naming their target identity
  (references-as-refs §2a).
- Staleness is found by a detector query whose empty result is the proof (2.11). Nobody updates docs by hand to
  prevent it.
- Files stay the durable form, written back through the one gated write-back.
- Markdown that is not a doc of some definition does one of two things:
  - it stops being current input and becomes evidence, linked by path from a task (7.15);
  - or it is deleted, because git is the archive.

## 0. Broken things first

1. **Default is `faulted`.** One open fault: `java.lang.ClassCastException` in `seon.schema/valid-candidate-value?`,
   signature `fa60ad84…`, last at 17:13:55Z. It survived root's JVM replacement (pid 48787 → 80197). This lane did
   not touch it.
2. **Two writable representations of one problem (breaks 7.15).**
   - 35 task entries cite `docs/seon/issues/*.md` files as `:seon.task/evidence`.
   - **34 of those notes still say `status: open`**, so the Markdown indexer still indexes them as open issues.
   - Example: task `d6e3b775f474` in `resources/seon/tasks/seon.db.edn:1`.
   - The migration boundary says a migrated note stops being current input. Nothing enforces that.
3. **Prose is parsed in production (breaks 1.4).**
   - `seon.issue/words` splits the whole text of every issue note into citation tokens (`issue.clj:44-60`).
   - `parse-note` reads front matter and the `## Problem` section by string splitting (`issue.clj:99-125`).
   - The result is 1,805 distinct backticked names in issue notes, of which **531 (29%) resolve to no function or
     schema row**. That was measured with a probe regex, which is measurement only, never production.
4. **Two dangling docstring links, still unrepaired.**
   - `seon.schema/restore!` links `[[snapshot-state]]` and `[[restore-state!]]` (P13 in references-as-refs §0).
   - Nothing refuses them because the S1 producer has not landed. The third P13 case (`shipped-calibration`) now
     resolves.
5. **85 of the 92 stored tasks have no subject and no proof edge.** Only 7 distinct handles appear in any
   `:seon/tasks` annotation (15 edges on 14 holders). The rest belong on the triage list of 7.5.
6. **No `(task h)` REPL call exists.** No public `my.*` Var has "task" in its name. Rows 1.9 and 7.10 promise one.

## 1. Language plan status: docs, links and tasks

Numbers come from default pid 80197 unless marked 48787. Legend:
- **installed+proven**: HEAD code plus a passing named regression or live data;
- **installed, unproven**: code present, live data empty or unexercised;
- **designed**: a reviewed design exists, no code;
- **open**: neither.

| Row | Requirement (short) | Status | Evidence (file:line, measured) |
|---|---|---|---|
| 1.2 | A fact lives with its owner | **partly installed** | Function docs: 2,774 of 5,306 fn rows (52%) have `:seon.fn/doc`; public functions 1,406 of 1,513 (93%); private 3,793. Namespace docs: 280 of 464 rows, but only 7 non-test namespaces lack one. Tests: 2,267 rows, 0 with a doc attribute (the test producer stores none). Schemas: 487 of 3,502 forms (14%) carry `:description`, and it is not an attribute, only a member of the form string. Issue and plan prose still lives outside its owner (§2). |
| 1.4 | Prose never parsed | **violated** | `issue.clj:44-125` tokenizes issue prose. Retired only when issues become tasks (7.11). |
| 1.5 | Typed must-read / see-also, quoted fully qualified symbols | **installed, unproven** | `task-links` `fn.clj:616-640`; schema `seon.fn.edn:68-69`; regressions `test/seon/issue/task_ingestion_test.clj:22-127`. **Live: 0 `:seon.fn/must-read` and 0 `:seon.fn/see-also` datoms**, because no source outside tests writes the keys (`rg ':seon/must-read' src` finds only `fn.clj`). |
| 1.6 | Task files read by edamame, no eval | installed+proven | `index-tasks!`; 92 entries in 33 files, 92 task rows. |
| 1.9 | Handles followable both ways | **partly** | Task handles are literal (14 holders, grep finds them). There is no `(task h)`. `(source f)` and `(callers 'f)` exist (`src/my/program.clj:152`). |
| 1.15 | Every stage is specified data | **partly** | Task stages have schemas: `:seon.task/entry`, `declaration`, `annotation`, `links` (`resources/seon/schemas/seon.task.edn`). The docstring-link stage has none (review finding: `:seon.task/annotation` does not cover `[[sym]]`). |
| 2.1 | One family: issue/backlog/plan are views | **open** | Two stores: 463 issue rows (456 `:open`) indexed from 587 notes (539 say open), plus 92 task rows. |
| 2.10–2.11 | Detectors grouped per namespace; empty result is the proof | **designed (v0 prototype)** | The v0 stale-doc detector generated 42 tasks from 115 drifted functions (row 2.4); it is not installed. `seon.issue/generate` and `detector-rows` exist (`issue.clj:837-970`) on the issue family, not the task family. |
| 5.3 | Must-reads always in context | **installed for tasks, empty for functions** | 181 `:seon.task/must-read` datoms, shown by `task-ai` (`render/ns.clj:142-159`). Function-level must-read has 0 edges, so the walk has nothing to include. |
| 5.6 | Contradictions surfaced (doc teaches a no-op; description not enforced) | open | — |
| 5a.6 | Must-read / see-also not ingested | **closed in code** (8ccdd694b), no data | as 1.5 |
| 5c.6 | Help is data, one source | open | Two prose sources: `seon.bootstrap/help-value` hard-codes `:seon.help/lines` (`src/seon/bootstrap.clj:15-60`), and `seon.cluster.instruction/getting-started-text` (`src/seon/cluster/instruction.clj:13-29`). |
| 7.1–7.4 | Task = EDN data + code subjects + test proofs | installed+proven | 15 edges on 14 holders match source exactly (4 in `src`, 10 in `test`). |
| 7.5 | Dangling id refuses; subjectless tasks triaged | **dangling installed; triage unrendered** | `candidate-dangling` (`issue.clj:754`); `task-findings` (`:742`). 85 subjectless tasks are not shown anywhere. |
| 7.6 | Lifecycle: settled when accepted and the id is removed | designed | the D1 milestone (one-repl §3.4) |
| 7.9 | Ingestion of tasks, must-read, see-also | installed+proven | as 1.5 |
| 7.9b | Test seeds as edges | open | — |
| 7.10 | `(task :id)` context; a function lists its open tasks | **half** | `task-ai`/`task-html` render a task node with subjects and proofs (`render/ns.clj:142-177`). `function-ai` shows no open tasks (`:228-248`), and there is no `(task h)`. |
| 7.11 | Issues and plans move into the code as tasks | **started** | 2 issues migrated (0a332e8fc). 89 new tasks logged (20b156860). 539 open notes remain. The issue index README and `bin/issues-index` are still live. |
| 7.13 | Tasks never ride merge | installed | handles are string values (`seon.task.edn:10`) |
| 7.14 | Bootstrap exception (hand-authored task files) | active | `write-back!` (`source.clj:1192`) writes function, test and namespace rows only. There is no data-file write-back for task EDN or schema EDN. |
| 7.15 | Migration boundary, never two writable copies | **violated** | the 34 notes in §0.2 |
| 7.16 | One writer, every edit with a basis | installed | `task-basis` (`issue.clj:611`) |
| refs §3e / S1 | Docstring `[[links]]` read from the markdown parse tree | **designed, reviewed REVISE** (Astra, 413e26aa4) | nextjournal/markdown is not in `deps.edn`. Live: 42 `[[x]]` links in 37 fn docstrings, 40 resolve; 4 links in namespace docs. |
| open-items 4b | Docs become queryable; long docs indexed by heading; stale surfaces | open | this design |

**Counts:**
- installed+proven: 6 (1.6, 7.1–7.4, 7.9, 7.13, 7.16, 5a.6-in-code);
- installed, unproven or empty: 3 (1.5, 5.3 for functions, 7.5 triage);
- partly: 4 (1.2, 1.9, 1.15, 7.10);
- designed: 3 (2.10–2.11, 7.6, refs S1);
- open: 5 (2.1, 5.6, 5c.6, 7.9b, 4b);
- violated: 2 (1.4, 7.15).

## 2. Inventory: 1,755 Markdown files, one disposition per kind

**Measured rot.**
- Each file is scanned for `src|test|resources|bin|script/…` path citations that are not suffixes of a longer path.
- A citation counts as missing when the file no longer exists.
- It counts as beyond EOF when its line number exceeds the file's length. That is a floor: a line that shifted
  within a file is invisible to this check.

| Kind (path) | Files | Path citations | Missing file | Line beyond EOF | Disposition |
|---|---|---|---|---|---|
| Issue notes `docs/seon/issues/*.md` | 587 + README | 1,375 | 187 (14%) | 40 | **MIGRATE → task EDN**, by script (7.11). Each open note becomes one entry in its owning namespace's `resources/seon/tasks/<ns>.edn`: `name` from the slug, `intent` from the `## Problem` paragraph (the script reads it once; production never does), `must-read` from its resolved symbols. The subject's `:seon/tasks` gains the handle. The note's body **stays as evidence** under `:seon.task/evidence` and stops being indexed. Resolved or closed notes (48) are **deleted**; git keeps them. The README and ranked index become a rendered view of task data (5.11). |
| Architecture `docs/seon/architecture/*.md` | 8 | 106 | 5 | 0 | **MIGRATE → namespace docstrings** of the owning namespaces. `architecture.md` → `seon.cluster`, `seon.db`, `seon.render`, `seon.sci.eval`, `seon.flow`. `clusters-branches-contexts.md` → `seon.cluster.registry`. `agent-runtime.md` → `seon.turn`, `seon.cluster.agent`. `ui.md` → `seon.render.web`. `data-modeling-guide.md` → the `seon.schema` namespace doc plus `:description` on `:seon.db/identity`, `:seon.db/component-schema` and related schemas. `vocabulary.md` → **stays one file** (see below). `reference-code.md` → the `seon.fn.analyzer` doc with see-also to the vendoring owner. `README.md` → deleted. |
| Decision records `architecture/decisions/*.md` | 16 | (in the 106) | — | — | **KEEP AS EVIDENCE**. Each ruling's *current* sentence moves into the owner's docstring. The ADR path is cited from that namespace's task evidence, not from `:seon/see-also`, which takes symbols only. Superseded ADRs (for example 006 separate-jvm, 013 per-run forks) are **deleted**. |
| Skills `.agents/skills/**` | 22 (3,082 lines) | 249 | 3 | 0 | **SHRINK, KEEP EXTERNAL** (they serve outside agents that load files before any REPL). Two parts per skill: (1) a workflow card for outside agents (when to load, which `bin/` and MCP calls, the laws); (2) mechanism claims, which move into the cited namespace's docstring and are replaced by `(doc ns)` / `(dir ns)` calls and symbol lists. Dependency teaching (datahike querying, malli) stays, since those docs belong to libraries without rows. `clojurescript` (deleted pod) → **delete**. The skill citation checker (3a8f2e6d7) keeps pins honest meanwhile. |
| Reference `docs/seon/reference/*.md` | 18 | 42 | 10 (24%) | 0 | Datastar, Gemini, hyperlith, hooks: third-party knowledge. **KEEP AS EVIDENCE** where a skill or task cites it (`llm-adapters.md` is cited 3× from `src`). `durable-ctx-design`, `separate-jvm-exploration` and `flow-foundation` are superseded → **delete**. |
| Research `docs/research/agent-platform/*.md` | 269 (71 reviews, 34 audits/censuses, 38 landings) | 6,100 | 557 (9%) | 101 | **KEEP AS EVIDENCE**, never current input. A research or design doc is current only until its slices land. Then its rulings live in docstrings and task EDN, and the file is cited by path from those tasks (150 task evidence paths already point here). `open-items.md` becomes a rendered view of task data after 7.11. |
| Agent-platform PRDs `docs/prds/agent-platform` | 333 (295 landings) | 4,202 | 374 (9%) | 51 | Landings = **evidence** (a landing is the per-commit clock row law 7 asks for). Plan and spec files = **evidence once landed**. The 12 `draft` files are reviewed for live content, which moves to tasks. |
| Steward-platform PRDs | 273 | 17,611 | 1,617 (9%) | 486 | **DELETE** (superseded by the agent-platform plan; the "steward" name is retired by owner ruling). Inbound references remain in 16 files across `src`, `resources`, `.agents` and CLAUDE.md (this count covers both PRD trees). The slice checks and cuts them. |
| Context-generation PRDs | 240 | 5,518 | 884 (16%) | 90 | **DELETE** except files a task or ADR cites as evidence (the same inbound check). Their live idea, context from references, is language §5a. |
| `docs/prds/next-turn` (untracked), `readme.md`, `docs/seon/_dashboard.md`, `namespaces.md`, `TRANSFER_PROMPT.md`, `docs/wiki/seon/doc/errors_render_once.md`, `docs/seon/pod` | ~12 | — | — | — | `next-turn` is someone's work in progress, untracked, not this lane's. The dashboard and `namespaces.md` → **rendered views** (delete the files). `TRANSFER_PROMPT` → **delete**. The wiki page is the fenced-EDN experiment and stays until option C is ruled out. |

**Why `vocabulary.md` stays one file.** CLAUDE.md makes it the grounded-term authority for outside agents. It could
become the `seon` root namespace docstring, but outside agents read files first. Proposed: it stays Markdown in
option A. Options B and C index it as a page.

**CLAUDE.md** stays external. It is the law for outside agents before they have a REPL, and it links into code by
symbol.

**Totals by disposition** (approximate; the script run gives exact numbers):

| Disposition | Files | Examples |
|---|---|---|
| Migrate | ~555 | 539 open issues, 7 architecture docs, the ADR sentences |
| Shrink or keep external | ~25 | skills, vocabulary, CLAUDE.md |
| Keep as evidence | ~650 | research, landings, the cited reference docs and ADRs |
| Delete | ~530 | 48 closed issues, 273 steward PRDs, ~200 context-generation PRDs, superseded references and ADRs, dashboards |

## 3. The design

### 3.1 Who parses what (one producer per source)

| Source | Producer | Parser | Output |
|---|---|---|---|
| `ns` docstring, `defn` docstring | `namespace-row` / `var-row` (`fn.clj:226`, `:683`) via the clj-kondo analysis already consumed | `nextjournal.markdown/parse` with `internal-link-tokenizer` (refs §3e). Code spans and blocks never tokenize; 31 µs per docstring measured in P16 | resolved `[[sym]]` → joins `:seon.fn/see-also` (fn) or its ns-level twin; a dangling link refuses `:dangling-doc-link`; malformed text refuses `:malformed-doc-link` |
| `:seon/must-read`, `:seon/see-also` metadata | `task-links` (installed) | the Clojure reader (edamame through kondo `:meta`) | `:seon.fn/must-read`, `:seon.fn/see-also` |
| schema `:description` | `seon.schema/canonical-schema-rows` (`schema.clj:3700`) | the same markdown call over the description string | links → `:seon.schema/see-also` (new name, value edge, `reference-to #{:seon.fn/sym :seon.schema/key}`). The description itself stays inside the form: no second copy |
| task EDN | `seon.issue/index-tasks!` (installed) | edamame | task rows |
| long-form pages (options B and C only) | §3.4 | markdown parse tree (B) or EDN (C) | page and section rows |
| issue Markdown | **retired** with the migration: `parse-note`, `words`, the citation index and `index!` (`issue.clj:38-530`, about 490 lines) plus `index-issues!` (`source.clj:514-524`) | — | — |

No production regex is added, and no renderer parses links again. Resolution reuses `seed-symbol` (`fn.clj:487`), the
alias resolver `task-links` already uses.

### 3.2 Entities and attributes (reuse first)

- **Reused as they are:** `:seon.fn/doc`, `:seon.ns/doc`, `:seon.fn/must-read`, `:seon.fn/see-also`, `:seon.task/*`,
  and `:seon.task/evidence` (paths, "linked, never parsed", `seon.task.edn:10`).
- **One new value attribute:** `:seon.schema/see-also`, needed because schema rows are not fn rows. Schema
  `:description` links have no other home. It is proven needed only if the migration puts links into descriptions;
  the data-modeling guide conversion does.
- **Descriptor:** every link attribute declares `:seon.db/reference-to` (refs §2a). Deletion obligation then derives
  from row ownership (refs §2b). Removing a function that a surviving docstring links to refuses at the writer,
  unless the same transaction repairs the docstring. The unbreakable graph now covers docs.
- **No `:seon.doc/*` entity in option A.** Options B and C add one family (§3.4).

### 3.3 How links become refs

- Links stay **values**, for three reasons: authored links may name external Vars (`task_ingestion_test.clj:24` uses
  `` `s/join ``); a task may name a subject that does not exist yet; and refs over redefinition hit D1–D6 (refs §1b).
- `:seon.db/reference-to` gives navigation both ways: an AVET seek on the value from the target side.
- Deletion obligation comes from row ownership, as above.
- This follows the references-as-refs verdict ("must-read, see-also … value + reference-to", refs §2d), so there is
  nothing new to decide here.

### 3.4 Long-form knowledge: where it lives

| Knowledge | Home |
|---|---|
| What a function does, its contract's intent, why it is shaped so | its docstring, with `[[links]]` |
| What a namespace owns, its data flow, its laws (today's architecture prose) | its `ns` docstring. Namespace docs today total 118 KB over 280 namespaces; the 8 architecture files add about 1,100 lines, spread over ~12 owners |
| What a key means | schema `:description` |
| A unit of work, its evidence and must-reads | task EDN |
| History and the reason a ruling was made | evidence files, cited by path from tasks; git |
| Cross-cutting narrative that no single owner holds (vocabulary, "one JVM, many realities") | **the fork in the road** — options A, B and C |

**Option B's page model.** A page is a Markdown file under `resources/seon/docs/<page>.md`.
- Its first block is a fenced EDN map `{:seon.doc/id sym :seon/must-read [...] :seon/see-also [...]}`. That is the
  shape of the 5dab9e74a wiki experiment.
- The indexer parses the page with the same markdown call.
- Each heading becomes a section row: identity `[page-id heading-slug]`, a text digest, and its `[[links]]` plus the
  fenced-EDN link maps as see-also value edges.
- The page file stays the only authored form. Shell edits are the working medium until data-file write-back lands:
  that is the 7.14 exception, extended.

**Option C** stores pages as EDN data (hiccup vectors with native symbols, the long-form research §4 prototype).
Authoring happens at the REPL, and the pages are written back as EDN through the gated write-back.

### 3.5 Write-back keeps files and the database in step

- **Docstrings and namespace docs** are already covered. A docstring is part of the canonical head, the definition
  digest covers it, and `write-back!` splices replaced function, test and namespace spans (`source.clj:1192-1224`,
  97826db63). An agent fixes a stale doc with `(defn f "new doc" …)` at the REPL, and merge and write-back do the rest.
  **No new write path.**
- **Schema `:description`** lives in schema EDN, which has no write-back. Today's path is a hand edit under 7.14.
  Schema-file write-back is the same missing capability that ends the 7.14 exception for task EDN, so it is one
  slice, owned by write-back and shared by both.
- **Task EDN** has the same gap. It stays hand-authored until that slice lands.
- **Pages** (B: hand-authored Markdown; C: EDN written back) — B needs nothing more, C needs the data-file slice.

### 3.6 How the render walk and the debug page show docs with code

- `function-ai` and `function-html` show the first doc line today (`render/ns.clj:228-262`). They change to show:
  - the must-read and see-also names as addresses, in the existing `names` style (hop 2 of §5a, never expanded);
  - the open task handles that list the function (7.10), from one AVET seek on `:seon.task/tasks`;
  - in B and C only, the governing page sections, named by id.
- The walk expands must-read edges at distance 1 (5.3). The same unit renders on the debug page through `*-html`,
  keeping one pipeline and two consumers (11.3).
- `(task h)` joins `my.program` as a thin call over `issue/task-edges`. That closes 1.9 and 7.10 for REPL agents.
- About +25 lines in `render/ns.clj` and +8 in `my/program.clj`.

### 3.7 Staleness is detected, never hand-maintained (2.11)

One detector namespace function runs over one database value. Its findings are grouped per owning namespace (2.10)
into generated tasks whose done-when is "this detector returns nothing here":

| Finding | Query over | Blocks or reports |
|---|---|---|
| dangling doc link | none needed: the producer refuses at admission (S1) | refuses at the writer |
| deleted link target | refs §2b deletion obligation | refuses at the writer |
| **drifted doc**: a function's body digest changed in a commit while its docstring bytes did not, and it links to or is linked from something that changed | `history`/`as-of` pairs on `:seon.program/definition-digest` plus the doc value (Datahike owns time) | report → per-namespace task. The v0 prototype found 115 drifted functions → 42 tasks |
| missing doc on a public function or non-test namespace | 107 public functions, 7 namespaces | report → task |
| evidence path missing | `:seon.task/evidence` paths against the checkout | report (the §2 measure, as a query) |
| migrated note still open | a task's evidence path ∈ open issue notes | disappears when the indexer is retired |
| (B/C) a section whose linked definitions changed after the section's recorded digest | section → see-also → the target's definition digest at the section's basis | report → task |

The detector's empty result is the proof that docs are current. Its tasks go through the ordinary task path. Cost:
about 40 lines plus one regression per finding class.

## 4. Three options for the owner

Costs are Opus first-pass estimates; lane-days assume one lane plus Astra review. Added lines are source and test
together, excluding one-shot migration scripts in `tmp/`.

### Option A — docstrings, descriptions and tasks only (simplest viable)

- **What it is:** §3.1 without pages; §3.2 to §3.7 without page rows. Architecture prose moves into namespace
  docstrings. `vocabulary.md`, the skills and CLAUDE.md stay external Markdown.
- **Guarantees:**
  - every current doc is a fact on its owner's row;
  - every link is a resolved, typed, deletion-protected edge;
  - staleness is a detector query;
  - the issue Markdown indexer is gone, so prose is no longer parsed (1.4 restored) and there is one store for work
    (2.1, 7.15).
- **Cost:** about +170 lines.
  - S1 docstring links, per refs §3e: +30 plus the nextjournal/markdown dependency.
  - `:seon.schema/see-also`: +15.
  - Render and `(task h)`: +33.
  - Detector: +40.
  - Regressions: +50.
  - Migration scripts are one-shot, in `tmp/`.
  - About 4–5 lane-days in total, with the issue migration the largest part (539 notes, script plus hand residue for
    notes whose owner the script cannot name).
- **Deletes:**
  - `issue.clj` Markdown indexer, about 490 lines;
  - `index-issues!`, 11 lines;
  - `bin/issues-index`;
  - the issue-note tests (`issue_test.clj`, 515 lines, the parts covering notes) and `publication_notes_test.clj`;
  - the `seon.issue.citation` schema, 13 lines, plus the note-only members of `seon.issue.edn`;
  - about 530 Markdown files (§2).
  - Net: about −900 source and test lines.
- **Sacrifices:**
  - Cross-cutting narrative (vocabulary, why the architecture is shaped this way) is not queryable. It is a file an
    agent greps, and it can rot unseen: its links are not checked.

### Option B — A plus Markdown pages indexed by heading (**recommended**)

- **What it is:** A, plus §3.4's page model. Markdown files in `resources/seon/docs/`, each with a fenced-EDN head map
  naming the page symbol and its must-reads, indexed by the same markdown parser into page and section rows with
  see-also edges. `vocabulary.md`, the cross-cutting parts of `architecture.md` and the skills' mechanism parts become
  pages. The skill files keep only the outside-agent workflow card, pointing at pages and namespaces by symbol.
- **Guarantees:** everything A guarantees, and in addition:
  - a definition shows the design sections that govern it (open-item 4b);
  - a section citing a gone or changed definition is detected (drifted-section finding);
  - outside agents and GitHub readers still read plain Markdown.
- **Cost:** A plus about +110 lines.
  - Page and section schema: +20.
  - Indexer at publication, beside `index-tasks!`: +50.
  - Render pair: +20.
  - Detector clause: +10.
  - Regressions: +30.
  - About +2 lane-days.
- **Deletes:** A's list, plus about 1,500 skill lines that duplicate namespace docs (estimated from the 249
  mechanism citations), plus the fenced-EDN wiki experiment file, which is folded in.
- **Sacrifices:**
  - Pages are authored in the shell, not at the REPL, until data-file write-back lands (the 7.14 exception extended to
    pages).
  - A second authored format beside Clojure. It is still Markdown read by a library, so there is no second Seon
    parser.

### Option C — docs as EDN data, authored at the REPL

- **What it is:** A, with pages as EDN hiccup values holding native symbols, written at the REPL. The writer walks the
  values for links without parsing. Pages are written back to `resources/seon/docs/*.edn` through the gated
  write-back.
- **Guarantees:**
  - everything B guarantees;
  - the REPL is the authoring path for docs too (1.10), with no Markdown parse for pages.
- **Cost:** A plus about +200 lines, plus the data-file write-back slice (about +150, shared with task and schema
  EDN).
  - About +5 lane-days, and it depends on write-back M2 or M3.
- **Deletes:** as B, and later the markdown dependency for pages (it remains for docstrings).
- **Sacrifices:**
  - Human readability on disk and on GitHub.
  - Outside agents must read rendered output, not files.
  - The largest cost.
  - It blocks on write-back.

**Recommendation: B, landed as A's slices first.** A is the prerequisite of both B and C, and it alone deletes about
900 lines and restores 1.4, 2.1 and 7.15. B's page slice then answers the owner's 4b ("long docs indexed by heading
and links… a doc citing a gone definition surfaces as stale") at +110 lines, and needs no write-back. C stays open as
the later conversion from B's pages to EDN once data-file write-back exists, because B's section rows are the same
data either way.

### Migration order and the proof of each step

| # | Step | Proof (a detector or regression whose result is checked, plus timings) |
|---|---|---|
| M0 | Repair `restore!`'s two dangling links (needs `schema.clj` free in the ledger) | the P13 probe answers 42/42 |
| M1 | **S1: docstring links from the markdown parse tree** (refs §3e, after its REVISE findings are folded) | refusal regressions `:dangling-doc-link` and `:malformed-doc-link`; the live count of see-also edges from docs is 40 or more; full reindex delta measured (target under 100 ms), one-file publication delta measured |
| M2 | **Issues → tasks by script** (7.11). Batches by owning namespace; `status: open` notes only. Each batch marks its notes migrated and removes them from indexing in the same commit (7.15). Closed notes are deleted | "migrated note still open" = 0; task count rises by the number of notes; every generated handle equals `seon.id` over its aspect; `(task h)` answers for a sample |
| M3 | Retire the issue Markdown indexer, the README index and `bin/issues-index` | `rg 'seon.issue/(index!|notes|parse-note)'` finds nothing; publication clock row before and after (the note corpus is about 7 MB per index; an earlier `words` split cost 484 ms per publication, per its docstring at `issue.clj:46`; the current cost is unmeasured) |
| M4 | `(task h)`, function-node open tasks and must-read and see-also names in the renderers | render regressions on a task-annotated function (AI and HTML over the same unit) |
| M5 | Staleness detector plus generated per-namespace doc tasks | the detector's findings equal the generated tasks; an empty-result regression on a fixture namespace |
| M6 | **Architecture → namespace docstrings**, one owner per commit (file ownership rules apply), with see-also symbols; the ADR sentences fold in | the detector finds no missing namespace docs among the targets; `docs/seon/architecture` is deleted except `vocabulary.md` |
| M7 | Deletions: steward and context-generation PRDs, superseded references and ADRs, dashboards (after an inbound-reference check: 16 inbound hits to resolve) | `rg` for each deleted path finds nothing outside git history |
| M8 (B) | Page indexer; `vocabulary.md` and the cross-cutting architecture become pages | the section detector is empty; a definition renders its governing section |
| M9 | **Skills last:** each shrinks to a workflow card, with mechanism claims replaced by `(doc ns)` and page symbols | the skill citation checker passes; a skill's line count falls; Astra reviews that each removed claim exists in its namespace doc |

## 5. Questions for the owner

1. **Option A, B or C?** The recommendation is B, landed as A first.
2. **Skills:** may their mechanism content move into namespace docs and pages, leaving outside agents a workflow card
   that points at `(doc ns)`? Or must skills stay self-contained for agents without a REPL?
3. **Deletion scope:** may the 273 steward-platform and about 200 context-generation PRD files be deleted outright
   (git is the archive) after the inbound-reference check? Or should they stay as evidence?
4. **Issue migration granularity:** one task per open note (539), or grouped per owning namespace by the script
   (fewer tasks, following the 2.10 detector rule), with every note path kept as evidence?
5. **`vocabulary.md` and CLAUDE.md:** keep them as Markdown files outside the database (A), or index them as pages
   (B)? CLAUDE.md would stay a file either way, since outside agents load it before any REPL.

## Appendix: probes (read-only JVM mode, session `docs-in-code-design`)

| Probe | Result | ms |
|---|---|---|
| P1: row and doc counts (pid 48787) | fn 5,306 (with doc 2,774); tests 2,267; namespaces 464 (with doc 280); schemas 3,502; `:seon.fn/must-read` 0; `:seon.fn/see-also` 0; task rows 92; task must-read 181; issues 463 | 8 |
| P2: doc split and links (48787) | public 1,513 (with doc 1,406); private 3,793; tests with doc 0; 42 fn-doc links in 37 docstrings; 4 ns-doc links; 673 KB fn doc text; 118 KB ns doc text | 139 |
| P3: schema descriptions (48787) | 487 of 3,502 forms (the form is stored as a string) | 46 |
| P4: link resolution and doc-name resolution (80197) | 40 of 42 fn-doc links resolve (the two `restore!` links dangle); 7 non-test namespaces without a doc; backticked names that resolve to no row: architecture 9 of 73, skills 23 of 139, issues 531 of 1,805 | 63 |
| P5: task annotation holders (80197) | 15 edges on 14 holders, 7 distinct handles; 456 issues `:open`; no `my.*` task function | 7 |
| Shell: path-citation rot | the §2 table | — |

The probes that measured docstring links used a regex. That was measurement only, never production (refs Q2).
