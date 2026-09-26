---
type: design
status: "Opus revision 2: Astra REVISE (review-docs-in-code-2026-09-26.md, cf3d3cfb9) folded in place — four blocking and four should-fix findings resolved; laws ee4044ff8/60d323a08 (total REPL path, loud = delivered) and c03761415 (merge is a root task) applied; ready for Astra re-review; no source edited"
created: 2026-09-26
scope: Docs move into the codebase and the database. Status of every language-plan row about docs, links and tasks; a pinned inventory of Markdown with a disposition per kind; how docs are parsed, stored, linked, written back and checked for unreviewed change; per-record issue conversion; three priced options and a dependency-ordered migration
source-pin: "file counts: HEAD cf3d3cfb9 (commands in §2.0). Code citations: 0818f4497 unless marked. Database counts: default pid 80197 (started 2026-09-26T17:26:52Z), read-only JVM probes; the forms are in the appendix, so a later adoption can be re-measured, not compared by pid"
aligned-with: language-requirements-2026-09-24.md §§1, 2, 3, 5, 5a, 7; references-as-refs-design-2026-09-25.md (S0, S1, §3e) and its review; long-form-docs-and-markdown-2026-09-23.md; clojure-native-doc-metadata-2026-09-23.md; decoration-language-v0-2026-09-24.md; one-repl-language-2026-09-25.md §4.3; open-items.md item 4b; AGENTS.md "The REPL path is total", "Merge is a root task"
review: review-docs-in-code-2026-09-26.md (cf3d3cfb9, REVISE, recommends B)
---

# Docs in the code: parse, store and link documentation as data

**What exists.**
- Docs are already data in the running database, in three places:
  - docstrings, stored by the one analyzer's producers as `:seon.fn/doc` and `:seon.ns/doc` (`src/seon/fn.clj:234`, `:787`; clj-kondo supplies them, `src/seon/fn/analyzer.clj:3`);
  - schema `:description`, a member of the canonical `:seon.schema/form` string, which renderers read back with `edn/read-string` (`src/seon/render/ns.clj:179-185`);
  - task EDN, reconciled at publication by `index-tasks!` (`src/seon/cluster/source.clj:526-557`).
- Typed links exist. `task-links` (`src/seon/fn.clj:626-650` at 0818f4497, 8ccdd694b) stores `:seon/tasks` as string handles and `:seon/must-read` and `:seon/see-also` as symbols. It resolves them through `seed-symbol`, which reads aliases and refers only (`fn.clj:497-505`).
- An older path reads Markdown issue notes by hand: `words`, `parse-note` and the citation index (`src/seon/issue.clj:38-186`, `:220-286`) and `index-tx` and `index!` (`:325-532`). It is the one place Seon parses prose.
- The issue family also has runtime entrances that write issues without Markdown:
  - `my.issue/add!` (`src/my/issue.clj:33`) → `seon.issue/add!` (`issue.clj:1551`);
  - detector generation (`issue.clj:828-970`);
  - issue workers (`start!` `:1506`, with callers in `seon.turn`, `seon.cluster.wake` and `seon.cluster.agent`).

**The principal Clojure approach.**
- A doc is a fact on its owner's row:
  - a function's doc lives in its `defn`;
  - a namespace's doc in its `ns` form;
  - a data shape's doc in its schema `:description`;
  - a unit of work in a task entry;
  - and, in option B, cross-cutting narrative in one Markdown page whose rows are derived.
- Links are native values that the existing producer resolves. Docstring `[[sym]]` links are read from the nextjournal/markdown parse tree (root ruling Q2). Seon adds no regex and no second analyzer.
- Links are stored as the value edges that already exist, and `:seon.db/reference-to` names their target (references-as-refs §2a).
- Documentation that may no longer be true is found by a detector of **unreviewed documentation changes**. Its empty result proves only that its named checks found nothing outstanding on its inputs, not that the prose is true (§3.7).
- Migrated Markdown work becomes tasks one record at a time, through the existing issue owner and the one task writer (§3.8).

## 0. Broken things first

1. **Default is `faulted`.** One open fault: `java.lang.ClassCastException` in `seon.schema/valid-candidate-value?`, signature `fa60ad84…`. It survived root's JVM replacement (pid 48787 → 80197) and was still open at the review. This lane did not touch it.
2. **Candidate 7.15 overlap (associations, not yet proven duplicates).**
   - 35 task→issue-note evidence occurrences name 34 distinct notes.
   - 34 of those occurrences name a note whose status is exactly `open`, covering **33 distinct open notes** (review finding 8, reproduced at cf3d3cfb9, §2.0).
   - One open note, `an-agents-test-world-has-no-agent-so-my-functions-cannot-be-tested-as-documented.md`, is evidence for two tasks: one in `seon.call-preparation.edn` and one in `seon.test.edn`.
   - An evidence link is a candidate association. Each one is reviewed in the per-record conversion (§3.8), not counted as a violation.
3. **Prose is parsed in production (breaks 1.4).** `seon.issue/words` tokenizes the whole text of each note, and `parse-note` splits front matter and `## Problem` by string (`issue.clj:44-125`). Of 1,805 distinct backticked names in issue notes, 531 name no function or schema row. That was measured with a probe regex (P4), for measurement only.
4. **Two dangling docstring links, unrepaired.** `seon.schema/restore!` links `[[snapshot-state]]` and `[[restore-state!]]` (P4; references-as-refs P13). No producer refuses them yet.
5. **Tasks with no subject and no proof.** Live `seon.issue/task-findings` returned triage 93 and dangling none (review probe, 13 ms). Only 8 distinct handles appear in any `:seon/tasks` annotation (15 edges on 14 holders). The triage set is not rendered anywhere.
6. **No `(task h)` REPL call.** No public `my.*` Var names a task lookup (P5). `task-edges` returns only edges, not the task's id, intent or evidence.

## 1. Language plan status (split into subclauses)

One taxonomy:
- **proven**: code at the pin plus a passing named regression, or live data that exercises the behavior;
- **installed, unexercised**: code at the pin whose live population is empty or was not run by this lane;
- **source-present**: code exists but no end-to-end proof was run here;
- **designed**: a reviewed design exists, no code;
- **open**: neither;
- **violated**: installed code contradicts the row.

An empty live population is an observation about installation, never a behavior proof.

| Row | Subclause | Status | Evidence |
|---|---|---|---|
| 1.2 | function and namespace docs on their rows | proven (as data) | 2,775 `:seon.fn/doc` datoms on 5,309 fn rows; 280 `:seon.ns/doc` on 464 namespaces (review probe). Public functions with a doc: 1,406 of 1,513 (P2). Non-test namespaces without one: 7 (P4) |
| 1.2 | test docs | open | 2,270 test rows; the test producer stores no doc attribute (P2: 0) |
| 1.2 | schema descriptions | installed (inside the form) | 487 of 3,502 forms carry `:description` (P3); not an attribute, by design (no second copy) |
| 1.2 | work and plans with their owner | open | issue and plan prose is outside its owners (§2) |
| 1.4 | prose never parsed | **violated** | `issue.clj:44-125` |
| 1.5 | must-read and see-also ingested as typed symbol edges | proven by regression; installed, unexercised live | `task-links` `fn.clj:626-650`; `test/seon/issue/task_ingestion_test.clj:22-127`. Live: 0 `:seon.fn/must-read` and 0 `:seon.fn/see-also` datoms, because no source outside tests writes the keys |
| 1.5 | docstring `[[links]]` as edges | designed (references-as-refs S1, REVISE) | nextjournal/markdown is not in `deps.edn`. Live: 42 links in 37 docstrings, 40 resolve (P4) |
| 1.6 | task files read by edamame | proven | 102 task entries in 34 files at cf3d3cfb9; live `:seon.task/id` 101 at the review probe |
| 1.9 | handles literal, greppable both ways | proven for task handles | 15 edges, 14 holders, equal to source |
| 1.9 | one REPL call per handle | open for tasks | no `(task h)`; `(callers 'f)` and `(source f)` exist (`src/my/program.clj:152`) |
| 1.15 | task stage schemas | installed | `:seon.task/entry`, `declaration`, `annotation`, `links` (`resources/seon/schemas/seon.task.edn`) |
| 1.15 | docstring-link stage schemas | open | named in §3.1 |
| 2.1 | one work family | open | 463 issue rows plus 101 task rows; the issue runtime entrances listed above still write issues |
| 2.5 | recurrence finds the existing task | open for runtime producers | there is no `seon.task/create!`; the task schema has no producer member (`seon.task.edn:12`) |
| 2.10–2.11 | detector per namespace; empty result as proof | designed (v0 prototype) | v0: 115 drifted functions → 42 tasks, not installed. The issue-family `generate` (`issue.clj:828-970`) is not the task family |
| 5.3 | must-reads always in context | source-present | task must-reads are named by `task-ai` (`render/ns.clj:142-159`). The walk follows `:seon.fn/must-read` and `:seon.task/must-read` edges (`src/seon/render/walk.clj:912-920`, interim value edges), but no end-to-end context test was run here. Function must-read has 0 edges |
| 5.6 | contradictions surfaced | open, **outside this cut** | — |
| 5c.6 | help is data, one source | open, **outside this cut** | `bootstrap.clj:15-60` and `cluster/instruction.clj:13-29` |
| 7.1–7.4 | function, namespace and test holders | proven | 14 holders; `task-edges` `issue.clj:716-733` |
| 7.1–7.4 | schema subjects | open | `task-edges` selects fn, ns and test holders only; `:seon.task/subjects` is `[:set :symbol]` (`seon.task.edn:36`) |
| 7.1–7.4 | subjects that do not exist yet | open | no runtime-subject member |
| 7.5 | dangling handle refuses | proven | `candidate-dangling` `issue.clj:754`; the destination gate |
| 7.5 | triage listed | installed, unrendered | `task-findings` `:742` (93) |
| 7.6 | lifecycle and settlement | designed; now governed by the merge-task law (c03761415) | no acceptance member on tasks |
| 7.9 | tasks, must-read and see-also ingestion | proven by regression | as 1.5 |
| 7.9b | test seeds as edges | source-present | seed occurrences `fn.clj:754`; `test/seon/fn/seed_admission_test.clj:60` (not run here); context proof missing |
| 7.10 | a task shows itself with its subjects and proofs | half | `task-ai`/`task-html` (`render/ns.clj:142-177`) render a stored task. There is no `(task h)` composition (pull + `task-edges`). `function-ai` shows no open tasks (`:228-248`) |
| 7.11 | issues become tasks | started | 2 migrated (0a332e8fc); 539 notes still say open (§2) |
| 7.13 | tasks never ride merge | proven | `:seon.program/partition :seon.data` on `:seon.task/task` (`seon.task.edn:23`); the destination gate |
| 7.14 | bootstrap exception | in force | `write-back!` (`source.clj:1192`) writes function, test and namespace rows only; no data-file write-back |
| 7.15 | one writable representation | open | 33 candidate associations (§0.2); no conversion entrance |
| 7.16 | one writer with a basis | partly | `task-basis` (`issue.clj:611`) for file reconciliation; no runtime edit entrance or acceptance binding |
| 4b | docs queryable; long docs by heading; stale docs surface | open | this design |

**Counts:**
- proven: 9 subclauses;
- installed or installed-unexercised: 5;
- source-present: 2;
- designed: 3;
- open: 12, of which 2 are explicitly outside this cut;
- violated: 1.

## 2. Inventory and dispositions

### 2.0 Reproducible counts (HEAD cf3d3cfb9)

**Inclusion rule:** tracked Markdown under `docs/` and `.agents/skills/`.
- `git ls-files -- 'docs/*.md' '.agents/skills/*.md' | wc -l` → **1,776**: 1,754 under `docs/` and 22 skills.
- Revision 1 counted untracked files with `find` (1,755) and missed tracked skills. The review's 1,771 at 0818f4497 predates later commits.

**Issue notes:**
- `git ls-files -- 'docs/seon/issues/*.md' | grep -vE '/(README|index|AGENTS)\.md$'` → **586**.
- Status is the first `^status:` line, grouped verbatim:
  - **539 open**: 537 exactly `open`, plus 2 annotated `open (…)`;
  - **35 settled with a stated basis**: `resolved` 17, `closed` 8, `fixed` 2, `resolved (<commit…>)` 4, `closed (<date, evidence>)` 2, `fixed (verified live …)` 2;
  - **12 uncertain**: `fixed-unverified` 3, `fixed-unproven` 2, `fixed-pending-verification` 1, `fixed-pending-default-reload` 1, `fixed (committed, unadopted)` 1, `fix implemented; … pending` 3, no status 1.

**Tasks:** `grep -c '#:seon.task{' resources/seon/tasks/*.edn` → **102 entries in 34 files**.

**Task→note evidence:** `grep -oh '"docs/seon/issues/[^"]*"' resources/seon/tasks/*.edn` → **35 occurrences, 34 distinct**. Of these, 33 distinct notes are exactly `open`.

**Path-citation rot:**
- Method: `rg -oP "(?<![/\w.-])(src|test|resources|bin|script)/[A-Za-z0-9_./-]+\.(clj|cljc|edn|md|sh)(:[0-9]+)?"` per tree.
- A citation is **missing** when its file does not exist. It is **beyond EOF** when its line exceeds the file's length.
- This is a floor: a line that moved within its file is not detected.
- Measured at 0818f4497.

The commands are retained here. The migration manifest (§3.8) is committed as evidence, not kept only in `tmp/`.

### 2.1 Dispositions

| Kind | Files | Citations / missing / beyond EOF | Disposition |
|---|---|---|---|
| Open issue notes | 539 | 1,375 / 187 / 40 (whole tree) | **Convert per record** into tasks through the issue owner (§3.8). One note becomes one task, or is associated with an existing task that represents the same aspect. Notes are never grouped unless root records a supersession. The body stays evidence. |
| Settled issue notes | 35 | — | **Convert per record** as settled history: the issue row keeps its status and evidence. Deletion goes to the separate archive manifest (below), not to this cut. |
| Uncertain issue notes | 12 | — | **Reviewed triage**: each becomes a raised task, or root records an explicit resolution. Never deleted, never counted as settled (7.15). |
| Issue README, `bin/issues-index` | 2 | — | Replaced by a rendered task view (5.11) once every note is converted. |
| Architecture docs | 8 | 106 / 5 / 0 | **Move into owners**, each claim verified at its new owner before the file goes. `architecture.md` → docstrings of `seon.cluster`, `seon.db`, `seon.render`, `seon.sci.eval`, `seon.flow`. `clusters-branches-contexts.md` → `seon.cluster.registry`. `agent-runtime.md` → `seon.turn`, `seon.cluster.agent`. `ui.md` → `seon.render.web`. `data-modeling-guide.md` → the `seon.schema` docstring plus the identity and component schema descriptions. `reference-code.md` → the `seon.fn.analyzer` docstring. In B, the cross-cutting remainder and `vocabulary.md` become pages. **AGENTS.md links** (`:10`, `:106`, `:217`, `:380`) move in the same commit that moves their target. |
| ADRs (`architecture/decisions`) | 16 | in the 106 | **Evidence**: the current sentence moves into the owner's docstring; the path stays citable from that namespace's task evidence. Not deleted in this plan. |
| Skills | 22 (3,082 lines) | 249 / 3 / 0 | **Shrink last**: each keeps an outside-agent workflow card (bin/MCP calls, laws). Mechanism claims move to namespace docs (A) or pages (B) and are replaced by `(doc ns)` and page ids. Skill links to PRDs (for example `.agents/skills/repl/SKILL.md:9-10`, `data-oriented-clojure/SKILL.md:9`) are relocated before any PRD leaves the current path. |
| Reference docs | 18 | 42 / 10 / 0 | **Evidence** where cited (`llm-adapters.md` 3× from `src`); the rest go to the archive manifest. |
| Research | 269 | 6,100 / 557 / 101 | **Evidence**, never current input once their slices land. 150 task evidence paths point here. |
| PRDs: agent-platform 333 (295 landings), steward 273, context-generation 240 | 846 | 27,331 / 2,875 / 627 | **Evidence** while any current authority or task cites them. Otherwise, the archive manifest. |
| Dashboards, `namespaces.md`, `TRANSFER_PROMPT.md`, the wiki page | ~5 | — | Rendered views replace the dashboards. The wiki page is folded into B's first page. The rest go to the archive manifest. |

**The archive manifest** is separate. Deleting obsolete files is not needed to integrate docs with code. It is one
reviewed list of path, the reason it is obsolete, its inbound references, and a check that no current authority or
task cites it. It lands after its inbound references are relocated.

## 3. The design

### 3.1 Producers and the link grammar (decisions stated)

| Source | Producer (existing) | Parser | Result |
|---|---|---|---|
| `defn` / `ns` docstring | `var-row` / `namespace-row` | `nextjournal.markdown/parse` with `internal-link-tokenizer`; code spans and blocks never tokenize | resolved links join `:seon.fn/see-also` on the holder row. For a namespace holder this is the same attribute `task-links` already emits on `ns` rows (`seon.ns.edn:31`); there is no ns-level twin |
| `:seon/must-read`, `:seon/see-also` metadata | `task-links` (installed) | the Clojure reader via kondo `:meta` | unchanged |
| schema `:description` | `canonical-schema-rows` (`schema.clj:3700`) | the same markdown call | symbol links → `:seon.schema/see-also` (new value attribute). The description stays inside the form |
| task EDN | `index-tasks!` (installed) | edamame | unchanged |
| B pages | §3.4 | the same markdown call | page and section rows |

**Decisions** (chosen here; Astra checks them; none is an owner question):
- **D1. Target kinds.** A doc link names a function, test or namespace symbol. **Keyword links are omitted.**
  - Refs S1 refuses keyword text, and schema-to-schema and contract-key relations are already derived from forms
    (`:seon.schema/references`, arity refs), so a keyword link would restate a derived edge.
  - `[[:k]]` refuses `:malformed-doc-link`, whose message says "write the key in backticks".
- **D2. Resolution environment,** per holder, from the kondo analysis the producer already holds:
  - a qualified symbol resolves through the holder namespace's aliases (`seed-symbol`);
  - an unqualified symbol resolves to the holder namespace's own var definition in that analysis, then its refers.
    This adds a holder-namespace fallback of about 8 lines beside `seed-symbol`.
  - A schema description's holder namespace is the schema file's namespace, with no aliases.
  - A B page resolves only qualified symbols. A page has no namespace context.
- **D3. Existence policy.**
  - A **program target** must have a row in the candidate world, or be defined in the same publication.
  - An **external target** (a dependency Var) must resolve to a loaded Var at admission. It is stored as an
    observation: no deletion obligation, because it has no row.
  - A **future subject** is never a doc link. Only a task may name something that does not exist yet (7.4).
- **D4. Refusals,** as alternatives of the installed `:seon.task/refused` enum used by `task-refused!`
  (`fn.clj:614`), each with holder, link text and resolution tried:
  - `:dangling-doc-link` — the text reads as one symbol but resolves nowhere under D2 and D3;
  - `:malformed-doc-link` — the library emitted an `:internal-link` node whose text is not exactly one symbol, or is a
    keyword (D1).
  - Text the library does not turn into a link node is prose, never a refusal. The library's grammar is the only
    grammar.
- **D5. Stage schemas (1.15):**
  - `:seon.doc/link-text` — authored: the `:internal-link` node's `:text`, a string;
  - `:seon.doc/link` — parsed: edamame reads one symbol that prints back to the same text;
  - `:seon.doc/links` — resolved: `[:set :seon.program/edge-symbol]`, merged into `:seon.task/links`'s `see-also`.
  - Each stage validates against the projection. The final row is still checked by the writer.
- **D6. The REPL path stays total** (ee4044ff8).
  - A link refusal at REPL entry is a diagnostic attached beside the evaluated result, delivered in the agent's MCP
    reply or Seon context (60d323a08). The row is not admitted under `:gate`.
  - At publication, the refusal names the file and holder in the publisher's result.

### 3.2 Attributes: reuse first

- **Reused:** `:seon.fn/doc`, `:seon.ns/doc`, `:seon.fn/must-read`, `:seon.fn/see-also` (on fn and ns rows),
  `:seon.task/*` and `:seon.task/evidence`.
- **New, each justified by a use:**
  - `:seon.schema/see-also` — schema rows are not fn rows (§3.1);
  - `:seon.task/producer` plus the `seon.task/create!` entrance — runtime and detector tasks need a producer in their
    identifying aspect (2.5, 3b.6, 7.8);
  - `:seon.issue/task` — the per-record conversion link (§3.8);
  - `:seon.doc.review/*` — review records (§3.7);
  - B only: `:seon.doc.page/*` and `:seon.doc.section/*` (§3.4).
- **No second copy of any doc text.**

### 3.3 What each link guarantee depends on

| Guarantee | Delivered by | Prerequisite |
|---|---|---|
| a doc link resolves at admission (D3) | S1 plus D1–D5 | nextjournal/markdown in `deps.edn`, vendored under `reference-code/`, `analyze-dependencies!` run once |
| navigation both ways | `:seon.db/reference-to` on each link attribute | refs **S0** (descriptor and validation) |
| removing a linked program target refuses unless the same transaction repairs the doc | derived deletion obligation (refs §2b) | refs **S0**, **including the repair-evidence and holder/target lifecycle findings still open in its review** (1, 2, 4, 8) |
| external targets never block deletion | D3 (no row) | none |

**Without S0,** options A and B guarantee **producer validation only**: links resolve when admitted. They do not
block a later deletion. The unreviewed-change detector (§3.7) then reports the dangling link as a finding.

### 3.4 Long-form knowledge, and B's page contract

| Knowledge | Home |
|---|---|
| a function's intent and shape | its docstring |
| a namespace's role, data flow and laws | its `ns` docstring |
| a key's meaning | schema `:description` |
| work | task EDN |
| history and reasons | evidence files cited by tasks; git |
| cross-cutting narrative (vocabulary, "one JVM, many realities") | A: external Markdown, unindexed. **B: one page** |

**B's contract.** A page is a Markdown file `resources/seon/docs/<page>.md`.

- **Identity:**
  - A page is identified by the qualified symbol in its first block, a fenced EDN head map
    `{:seon.doc/id seon.doc/vocabulary :seon/must-read [...] :seon/see-also [...]}`. The file stem must equal the id's
    name, or the page refuses.
  - A section is identified by `[page-id path]`, where `path` is the vector of heading slugs from the page root, which
    the library's heading nesting supplies.
  - Duplicate paths within a page refuse `:duplicate-section`. The author disambiguates the heading.
- **The canonical page value** is the page id, the head map, and the ordered sections. Each section has its path,
  its level, its order, the digest of its own text (up to the next heading at its level or above), its `[[links]]`
  (D2 page rule) and its fenced-EDN link maps.
  - Sections are **components** of the page row. The whole page reconciles by value on each publication:
    - an unchanged text digest writes nothing;
    - a renamed heading is a retracted section plus a new one, and inbound links refuse at the next publication if
      they named the old path;
    - a removed section retracts.
- **Partition: program.** A page is a declaration derived from a file, like a namespace. It rides program merge and
  write-back identity, derives from files after `nuke`, and its rows are never independently editable. **The Markdown
  file is the only authored input.** This is not a second writable copy.
- **Basis:** a section's review basis is set only by a review record (§3.7). Reindexing never resets it.
- **Stages** are validated with the existing projection: `:seon.doc/page-file` (text), `:seon.doc/page` (canonical
  value), and the rows.
- **Authoring:** until data-file write-back lands, pages are edited in the shell. That extends the 7.14 exception to
  pages, and it is **an owner decision** (Q3).
- **C** stores the same canonical page value as EDN at `resources/seon/docs/<page>.edn`, authored at the REPL and
  written back through the gated write-back. Converting B→C is its own slice: it re-authors each page file, and
  section identities are kept by path.

### 3.5 Write-back keeps files and the database in step

- **Docstrings and namespace docs** are covered by the installed write-back (`source.clj:1192-1224`), which splices
  function, test and namespace spans. Under the merge-task law (c03761415), a doc fix is an ordinary change:
  1. the agent proposes it once the tests naming its task pass;
  2. root takes the holder by identity from the diff and accepts;
  3. write-back writes the span.
- **Schema descriptions, task EDN, and C pages** need data-file write-back. That is one shared slice, owned by
  write-back, and it also ends the 7.14 exception.
- **B pages** need no write-back while the owner keeps the shell exception.

### 3.6 Render and the REPL

- **`(task h)`** in `my.program`: pull the task row (id, name, kind, intent, must-read, evidence) and join it with
  `task-edges`. About 12 lines. This closes 1.9 and 7.10's lookup half.
- **`function-ai`/`function-html`** additionally name, as addresses and never expanded:
  - must-read and see-also;
  - open task handles (`open-tasks`, `issue.clj:734`);
  - in B, governing sections.

  About 25 lines. The same unit renders as AI text and as HTML.
- **Must-read inclusion** is proven by a context regression on the walk's edges (`walk.clj:912`), not asserted from
  `task-ai`.

### 3.7 Detector of unreviewed documentation changes

**Subjects.** Every holder of documentation:
- fn and ns rows with `:seon.fn/doc` or `:seon.ns/doc`;
- schema rows whose form carries `:description`;
- in B, page sections.

A missing doc on a public function or a non-test namespace is a separate finding class.

**Inputs.** Only stored values from one database value `db`:
- the holder's `:seon.program/definition-digest`. This is a declaration digest covering source, metadata and resolver
  context (`program.cljc:388`), used as-is: there is no body parser, so a metadata-only edit is a finding, which is
  the conservative choice;
- for a section, the digest of its own text;
- the current definition digests of the holder's link targets (`:seon.fn/see-also`, `:seon.fn/must-read`,
  `:seon.schema/see-also`, section links).

**Review record** (data partition, one entity per holder):
`:seon.doc.review/holder` (identity value, `reference-to`), `/digest` (the holder digest reviewed), `/targets`
(components: target symbol + digest), `/by`, `/commit`.

It is written by one writer function, `seon.doc/reviewed-tx`, in two cases:
- when root accepts a merge task whose diff changes the holder's doc bytes (the acceptance supplies the holder list
  from the diff; no new merge machinery);
- by root's explicit `(my.program/reviewed! 'holder)` when the doc was checked and is still true.

**Findings,** grouped per owning namespace (2.10). Each is a separate sufficient finding:

| Class | Condition |
|---|---|
| own-change | the holder's current digest ≠ its review's `/digest` |
| linked-change | some target's current digest ≠ its `/targets` digest, or the target is gone |
| unreviewed | a holder with no review record |
| missing-doc | a public function or non-test namespace without a doc |

**Unknown is not empty.** A holder whose digest, or whose review's target digests, cannot be read returns the
declared `:seon.doc/basis-unknown` error value naming the holder, never an omission (3.13, 9.1). Filesystem checks
(for example that an evidence path exists) are **not** part of this function. They belong to the archive manifest's
check, outside the proof.

**Clearing.**
- The comparison is always current-versus-last-review. History is not rescanned, so a repaired doc clears, and an
  unrelated commit changes nothing.
- Only a new review record clears a finding. Reindexing does not.

**Install baseline.** The first run would report about 3,000 unreviewed holders. The options are:
- the install records one `:baseline` review per current holder at the install commit, marked as assumed rather than
  reviewed and visible in the record;
- or it starts with every doc unreviewed.

This is **an owner decision** (Q4).

**Tasks and proof.**
- Each namespace with findings gets one generated task through `seon.task/create!`. Its identity is `seon.id` over
  `[producer 'seon.doc/unreviewed namespace]`, so a re-run finds the task and counts a recurrence (2.5).
- Every generated task needs the platform task that delivers the detector (2.11).
- Its proof is an ordinary deftest naming it:
  `(is (= {} (seon.doc/unreviewed (seon.db/db) {:seon.ns/name 'x})))`. That is a function of one database value
  (3.13), red before the repair.
- It settles by 7.6 through the merge-task law: root accepts, and the repair removes the id from its subjects.

**Regressions required:**
- red → repair → empty;
- a re-run without duplicates (recurrence counted);
- an unrelated commit leaves findings unchanged;
- a linked-only change is found;
- a missing basis returns `basis-unknown`;
- a metadata-only edit is found.

### 3.8 Per-record issue conversion (7.11, 7.15)

**One entrance in the existing issue owner**, `seon.issue/convert!`. It takes an issue id and a reviewed decision:
- `{:reuse handle}` — an existing task represents the same aspect;
- `{:create entry}` — the task entry, submitted through the one task writer with the basis it read (7.16);
- `{:settled evidence}` — a settled note is kept as history, with no task;
- `{:triage}` — an uncertain note becomes a raised task.

In one transaction it:
1. writes `:seon.issue/task` on the issue row (reuse or create), or keeps its settled status;
2. marks the issue **converted**. The issue writer and `my.issue/add!` updates then refuse edits to a converted issue,
   naming its task (one writable representation);
3. makes old entrances resolve the same current task: `my.issue/status` and `issue/status` by old id or path return
   the task through `:seon.issue/task`.

**Retiring the Markdown input.**
- The same commit moves the note from `docs/seon/issues/` to `docs/seon/evidence/issues/<slug>.md`, where
  `note-path?` (`issue.clj:127-136`) never indexes it. The move is the retirement of Markdown input; there is no
  invented status.
- `index-tx` must not retract a converted issue whose note has left (today it retracts removed notes, `issue.clj:492`).
  That is one clause, about 3 lines.

**The association manifest.**
- A script proposes associations: task evidence → note, the note's resolved symbols → subjects, and the handle from
  the aspect.
- The manifest is committed under `docs/research/agent-platform/issue-conversion/` as evidence.
- Root reviews each batch. The shared note in §0.2 needs one decision: which of its two tasks it becomes, or whether
  it becomes a third task linked from both.

**Unconverted records keep the current owner** (7.15). Runtime producers (`seon.error` and `issue/generate` issue
writes, `my.issue/add!`, issue workers) are converted to `seon.task/create!` in a **separate family slice** before
2.1 is claimed. Until then, the runtime issues they write are unconverted records under their existing owner.

**Proof:**
- repeat conversion is a no-op;
- an old-id read returns the task;
- an old-id write refuses naming the task;
- `{:reuse}` creates no task;
- a runtime detector after conversion writes through its current owner, unaffected.

The count proof is "every note converted or triaged, none in the indexed path", never "the task count rises by the
number of notes".

**Retiring the indexer** after the last conversion removes these functions:
- `citable-character`, `words`, `record-separator`, `git-bound-seconds`, `note-opened`, `parse-note`, `note-path?`,
  `notes`, `diagnostic`, `hex-token?`, `qualified-token` (`issue.clj:38-186`, 149 lines);
- `citation-spellings`, `citation-index`, `line-number`, `cited-span`, `citation`, `file-citations`
  (`:220-286`, 67 lines);
- `index-tx`, `index!` (`:325-532`, 208 lines);
- `index-issues!` (`source.clj:514-524`) and its caller check (`cluster.clj:1964`);
- `bin/issues-index`;
- `test/seon/cluster/publication_notes_test.clj` (58 lines), and the note-parsing tests in `issue_test.clj` (not
  measured).

**Kept:** `citation-attributes` (`:189`, used at `:872` and `:1138`) and `replacement-tx` (`:287`, used by
`task-delta` `:635`). That is about −435 source lines, all of which are the Markdown reader.

## 4. Three options for the owner

Prices are **unverified Opus estimates**. Lines count source plus tests. Migration review effort is counted
separately from steady-state code.

**Common core, priced once** (every option needs it; A is exactly this core):

| Part | Added | Deleted | Lane-days |
|---|---|---|---|
| refs S0 (target descriptor, derived deletion obligations; its review findings resolved first) | +40 (refs estimate) | −42 | 1 (refs design) |
| refs S1 plus decisions D1–D6, stage schemas, holder-namespace fallback | +50; dependency nextjournal/markdown with commonmark-java and 5 extensions | — | 1 |
| `seon.task/create!` and `:seon.task/producer` | +40 | — | 0.5 |
| `convert!`, `:seon.issue/task`, converted-issue refusals, old-id resolution, `index-tx` clause, regressions | +100 | — | 1 |
| `(task h)`, render addresses, must-read context regression | +60 | — | 0.5 |
| detector, review records, `reviewed!`, per-namespace tasks, six regressions | +170 | — | 1.5 |
| retire the Markdown indexer after the last conversion | — | about −435 source, −58 test and more | 0.5 |
| **Core total** | **about +460** | **about −535** | **about 6 of code** |

Migration review effort, not code:
- converting 586 notes: script plus root batch review, about 3–4 lane-days;
- architecture → namespace docstrings: about 1 lane-day.

The core exceeds the 100-line presumption. That is justified only by what it deletes (the Markdown reader, and the
issue store as current input) and by the laws it satisfies (1.4, 7.15, 2.11). The owner should weigh it as such.

### Option A — the core only (simplest viable)

- **Guarantees:**
  - every doc is a fact on its owner's row;
  - doc links resolve at admission; with S0 they also block deletion;
  - documentation changes that nobody has reviewed are findings, with per-namespace tasks;
  - issue Markdown stops being current input, one record at a time, with one writable representation each;
  - prose is no longer parsed.
- **Cost:** the core.
- **Deletes:** the Markdown reader and its tests.
- **Sacrifices:**
  - Cross-cutting narrative (`vocabulary.md`, architecture remainders, skill mechanism text) stays external Markdown,
    with unchecked links and no finding when it goes stale.
  - Skills shrink only as far as namespace docs can hold their content.

### Option B — core plus Markdown pages (**recommended**, as the review also recommends)

- **Guarantees:** all of A's, plus:
  - cross-cutting narrative lives in pages whose sections are rows;
  - a function names the sections that govern it;
  - a section whose own text or linked definitions changed since its last review is a finding;
  - the Markdown file stays the only authored input, readable by outside agents and on GitHub.
- **Cost:** the core, plus:
  - page and section schema and stages: +25;
  - indexer beside `index-tasks!`, with reconciliation and duplicate refusal: +60;
  - render pair: +20;
  - detector clause for sections: +10;
  - regressions (reconcile, rename, duplicate, section drift): +40.
  - That adds about **+155** and about 2 lane-days, plus about 1 lane-day of content moves.
- **Deletes:** A's list, plus the moved architecture and vocabulary files once their claims are verified at the pages,
  plus the skill mechanism text.
- **Sacrifices:** pages are edited in the shell until data-file write-back lands (Q3).

### Option C — core plus EDN pages authored at the REPL

- **Guarantees:** all of A's, plus the page guarantees of B, plus REPL authoring of pages with gated write-back
  (1.10).
- **Cost:** the core, plus:
  - B's page model with EDN instead of Markdown parsing: about +140;
  - the shared data-file write-back slice: about +150, 2–3 lane-days, depending on write-back M2 or M3.
- **Deletes:** A's list and the moved files. It needs no markdown parse for pages; docstrings still need one.
- **Sacrifices:**
  - page bodies become EDN hiccup, readable as data but not as prose on GitHub;
  - it is blocked on write-back.

A later B→C conversion is its own slice: it re-authors each page, keeping section identity by path.

**Recommendation: B.** The core satisfies the laws that are broken today. B then answers item 4b for about +155
lines, without waiting on write-back. C remains the later step once data-file write-back exists.

### Migration order (dependencies first; each step proves itself)

| # | Step | Needs | Proof |
|---|---|---|---|
| M0 | Repair `restore!`'s two links (needs `schema.clj` free in the ledger) | — | the P4 probe: 42 of 42 resolve |
| M1 | refs S0 after its review findings; then S1 with D1–D6 | the dependency added and vendored | refusal regressions for each D4 alternative; 40 or more doc see-also edges live; reindex and one-file publication clock rows (parse cost of 31 µs per docstring measured separately from resolution, validation and memory) |
| M2 | `seon.task/create!`, `(task h)`, render addresses | M1 for the render links | lookup and render regressions |
| M3 | `convert!` and the conversion manifest; batches by owning namespace, each batch reviewed | M2 | §3.8 proofs; "no note in the indexed path that is unconverted and not triaged" at the end |
| M4 | Detector with review records, baseline per Q4 | M1, M2 | the six §3.7 regressions |
| M5 (B) | Page indexer; the wiki page folded in; `vocabulary.md` becomes the first page | M1, M4 | page reconciliation regressions; a function renders its governing section |
| M6 | Architecture → namespace docstrings and pages, one owner per commit | M1, M5 for B | each moved claim located at its owner and reviewed by Astra; AGENTS.md links relocated in the same commit; the old file removed only then |
| M7 | Retire the Markdown indexer and `bin/issues-index` | M3 complete | `rg 'seon.issue/(index!\|notes\|parse-note)'` finds nothing; publication clock row before and after |
| M8 | Skills shrink to workflow cards | M6 | the skill citation checker passes; Astra checks each removed claim exists at its new home |
| M9 | Runtime issue family → tasks (2.1): a separate plan | M2 | 2.1 claimed only after this |
| — | Archive manifest | after M6 and M8 relocate inbound links | a reviewed manifest; inbound references none |

## 5. Questions for the owner

1. **A, B or C?** The recommendation is B. Each option's price includes the core of about +460 / −535 lines and about
   6 lane-days of code, plus 4–5 lane-days of migration review.
2. **Skills:** may skills shrink to an outside-agent workflow card that points at `(doc ns)` and page ids, with their
   mechanism text moved into the code? Or must skills stay self-contained for agents without a REPL?
3. **B only: may pages be edited in the shell until data-file write-back lands?** This extends the 7.14 bootstrap
   exception from tasks to pages.
4. **Detector baseline:** at install, record every current doc as a marked `:baseline` review, assumed rather than
   reviewed? Or start with all about 3,000 documented holders unreviewed and let per-namespace tasks work through
   them?
5. **Archive:** after every inbound link is relocated, should obsolete PRDs, references and dashboards be deleted
   through one reviewed manifest (git is the archive)? Or kept indefinitely as evidence?

Dropped since revision 1:
- *issue grouping* — the review keeps authored issues individual unless a supersession is recorded;
- *vocabulary and CLAUDE.md* — CLAUDE.md stays a file by law, and vocabulary follows Q1;
- *outright PRD deletion* — replaced by Q5's reviewed manifest.

## Appendix: probes (read-only JVM mode, session `docs-in-code-design`)

Every form bound `(datahike.api/db (seon.cluster.boot/connection "default"))`. Re-running a form on a later database
value gives a new observation; it does not correct an older one.

| Probe | Form (abridged) | Result | ms |
|---|---|---|---|
| P1 (pid 48787) | `count` of entities per attribute: `[:find ?e :in $ ?a :where [?e ?a _]]` | fn 5,306; fn with doc 2,774; tests 2,267; namespaces 464 (doc 280); schemas 3,502; fn must-read 0; fn see-also 0; tasks 92; task-must-read holders 181; issues 463 | 8 |
| P2 (48787) | public or private split; `re-seq #"\[\[[^\]]+\]\]"` over docs (measurement only) | public 1,513 (doc 1,406); private 3,793; tests with doc 0; 42 fn-doc links in 37 docstrings; 4 ns-doc links | 139 |
| P3 (48787) | `edn/read-string` of each `:seon.schema/form`; `:description` in its property map | 487 of 3,502 | 46 |
| P4 (80197) | resolve each fn-doc link via `ns-resolve` in the holder namespace, then check for a row; backticked names in docs against fn and schema rows | 40 of 42 (`restore!` × 2); non-test namespaces without doc 7; unresolved names: architecture 9 of 73, skills 23 of 139, issues 531 of 1,805 | 63 |
| P5 (80197) | `[:find ?h ?t :where [?e :seon.task/tasks ?t] (or …holders…)]`; `my.*` publics matching "task" | 15 edges, 14 holders, 8 handles; issues `:open` 456; no task lookup Var | 7 |
| Review probe (80197) | the review's corrected attribute/entity/value form (review appendix) | fn/doc 2,775 datoms; task/id 101; task/tasks 15 on 14 holders; triage 93 | 26 |

P1's "task-must-read 181" (entities, older store) exceeds that store's 92 task rows and is not used. The review's 194 datoms on 101 holders is the later
observation.
