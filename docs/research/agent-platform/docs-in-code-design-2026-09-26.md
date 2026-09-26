---
type: design
status: "Opus revision 3 (deeper round on the owner's answers, 2026-09-26): docstrings are structured Markdown parsed into typed facts; issues are mined from docstrings, metadata and errors; every fact has a named surface. Revision 2's Astra findings (cf3d3cfb9) stay folded. Ready for Astra re-review; no source edited"
created: 2026-09-26
scope: Docs move into the codebase and the database. The docstring as structured Markdown and its typed facts in Datahike; what a doc must not repeat; mining tasks from docstrings, metadata and errors (recurrence and repeated agent misuse); where each fact surfaces; migration of the existing docstrings; pages on the same parser; per-record issue conversion; status, inventory, prices and the remaining owner questions
source-pin: "file counts: HEAD cf3d3cfb9 (commands in §2.0). Code citations: 0818f4497 unless marked. Database counts: default pid 80197 (P1-P5, review probe) and the post-restart store (P6). Docstring structure survey S1: docstrings exported from default, parsed by nextjournal/markdown 0.7.213 in a disposable JVM (appendix)"
aligned-with: language-requirements-2026-09-24.md §§1, 2, 3, 5, 5a, 7; references-as-refs-design-2026-09-25.md (S0, S1, §3e) and its review; long-form-docs-and-markdown-2026-09-23.md; clojure-native-doc-metadata-2026-09-23.md; decoration-language-v0-2026-09-24.md; one-repl-language-2026-09-25.md §4.3; open-items.md item 4b; AGENTS.md "The REPL path is total", "Merge is a root task", "Make ordinary work sub-second"
review: review-docs-in-code-2026-09-26.md (cf3d3cfb9, REVISE, recommends B; its findings stay resolved in §§3.6-3.9)
owner-answers: "2026-09-26 — skills become workflow cards; the detector starts with ALL docs unreviewed, worked by per-namespace tasks; obsolete docs are deleted through one reviewed list; docstrings are Markdown, parsed and represented structurally, complementing the code (edge cases, recommendations, CPU and memory costs), and issues are mined from docstrings, metadata and errors, then surfaced"
---

# Docs in the code: the docstring as structured data

**What exists.**
- Docs are data in the running database:
  - docstrings are `:seon.fn/doc` and `:seon.ns/doc`, produced from the clj-kondo analysis (`src/seon/fn.clj:234`,
    `:787`);
  - a schema `:description` lives inside the canonical `:seon.schema/form`;
  - task EDN is reconciled by `index-tasks!` (`src/seon/cluster/source.clj:526-557`).
- Typed link edges exist: `task-links` (`fn.clj:626-650`) and `:seon.fn/must-read` / `:seon.fn/see-also`.
- The error writer already deduplicates by signature. It stores `:seon.error/operation` (a function symbol),
  `:seon.error/declared-schema` and occurrence components carrying `/count`, `/agent`, `/first-at` and `/last-at`
  (`resources/seon/schemas/seon.error.edn:57`, `:282`; `seon.error.occurrence.edn`).
- The profiler keeps live per-function timing keyed by symbol and definition digest (`src/seon/profile.clj`,
  `resources/seon/schemas/seon.profile.edn`: values, not stored).
- **Today's docstrings have no Markdown structure** (survey S1, 3,064 docstrings):
  - 0 headings, 4,398 paragraphs, 27 lists and 4 code blocks;
  - 58 docstrings use an "Example:" label paragraph;
  - 40 state a measured cost in prose;
  - 470 function docstrings mention a refusal, a throw or an error;
  - median length 142 characters, p90 553, max 3,175.

  The knowledge the owner wants is partly there, but only as flat prose.

**The principal Clojure approach.**
- A docstring is CommonMark. The one parser is the nextjournal/markdown parse tree, which already gives headings,
  lists, fenced code with its language, inline code and `[[internal links]]`.
- The producer that already reads the docstring (`var-row`, `namespace-row`, `canonical-schema-rows`) turns that
  tree into typed facts:
  - sections from a small declared vocabulary of headings;
  - items from their lists;
  - runnable example forms from `clojure` fences;
  - cost measurements from `edn` fences validated by a schema;
  - links resolved to symbols and schema keys.
- **Nothing reads the words.** Structure comes only from node types and from an exact heading-name enum declared as
  a Malli schema.
- **A doc says what the code cannot.** The renderer derives the argument shapes, contract, declared error
  alternatives, callers, live timings and open tasks, and shows them beside the doc. The doc carries edge cases,
  recommendations, the cost model and why each error happens.
- **Issues are mined** by detectors over these facts, over metadata and over the error writer's
  signatures/occurrences. They become tasks through one entrance, `seon.task/create!`, and their identity makes a
  recurrence find the existing task.
- **Every fact has a surface.** It appears in the function's context when an agent works on or calls it, in the
  error an agent receives, on the debug page, and in REPL queries.

## 0. Broken things first

1. **Default is `faulted`, and it restarted twice during this lane**: pid 48787 → 80197 → a fresh store.
   - The fault at revision 2: `ClassCastException` in `seon.schema/valid-candidate-value?` (`fa60ad84…`).
   - After the latest restart the store holds **one** error signature (`seon.cluster.agent/turn-step`) (P6).
   - **Error history does not survive a store reset or nuke.** Error-mined tasks and doc review records are data too
     (§3.9 Q2): as designed today they would vanish with the store, and every doc would fall back to unreviewed.
2. **Candidate 7.15 overlap:** 33 distinct open issue notes are cited as evidence by 34 task occurrences, and one note
   by two tasks. These are associations to review in §3.8, not proven duplicates.
3. **Prose is parsed in production (breaks 1.4):** `seon.issue/words` and `parse-note` (`issue.clj:44-125`).
4. **Two dangling docstring links:** `seon.schema/restore!` `[[snapshot-state]]` and `[[restore-state!]]`.
5. **93 tasks are in triage** (no subject, no proof), and triage is not rendered anywhere. There is no `(task h)`.
6. **Docstring indentation defeats Markdown.** Clojure docstrings indent continuation lines by two spaces, and
   `defn` examples by four or more. Parsed raw, a four-space-indented example becomes an indented code block and a
   two-space body is ordinary text by accident. S1 therefore removes the common continuation indent first (D7). Only
   4 fenced or indented code blocks exist today.

## 1. Language plan status (subclauses; one taxonomy)

Legend:
- **proven**: code plus a passing named regression, or live data exercising the behavior;
- **installed, unexercised**: code whose live population is empty;
- **source-present**: code without an end-to-end proof run here;
- **designed**: a reviewed design, no code;
- **open**: neither;
- **violated**: installed code contradicts the row.

| Row | Subclause | Status | Evidence |
|---|---|---|---|
| 1.2 | function and namespace docs on their rows | proven (as data) | 2,775 fn docs on 5,309 rows; 280 of 464 namespace docs; public with doc 1,406 of 1,513; 7 non-test namespaces without one |
| 1.2 | docs structured (sections, costs, edge cases) | **open**, this design | S1: 0 headings |
| 1.2 | test docs | open | 0 of 2,270 |
| 1.2 | schema descriptions | installed (in the form) | 487 of 3,502 |
| 1.4 | prose never parsed | **violated** | `issue.clj:44-125` |
| 1.5 | must-read/see-also typed edges | proven by regression; unexercised live | `task_ingestion_test.clj:22-127`; 0 live datoms |
| 1.5 | docstring links | designed (refs S1, REVISE) | 42 links, 40 resolve |
| 1.6 | task files by edamame | proven | 102 entries / 34 files; 101 live |
| 1.9 | literal handles; one REPL call | handles proven; call open | 15 edges / 14 holders; no `(task h)` |
| 1.15 | task stages specified | installed | `seon.task.edn` |
| 1.15 | doc stages specified | open | §3.1 D5 |
| 2.1 | one work family | open | issue and task stores; runtime issue writers |
| 2.4 | tasks from errors, detectors, docs | open | no task entrance; the issue family's `generate` (`issue.clj:828-970`) writes issues |
| 2.5 | recurrence finds the task | open for runtime | no `create!`, no producer member (`seon.task.edn:12`); the error writer's signature dedupe is the model |
| 2.10–2.11 | per-namespace detectors | designed (v0) | 115 drifted → 42 tasks, not installed |
| 3.6 | delivered functions carry a doc | partly | 93% public; no check |
| 5.3 | must-reads always in context | source-present | `walk.clj:912-920`; function must-read has 0 edges |
| 5.6 | contradictions surfaced | **designed here** (§3.3 M4) | — |
| 5c.6 | help is data | open, outside this cut | `bootstrap.clj:15-60`; `instruction.clj:13-29` |
| 7.1–7.4 | fn, ns and test holders | proven | `task-edges` `issue.clj:716` |
| 7.1–7.4 | schema and future subjects | open | subjects are `[:set :symbol]` |
| 7.5 | dangling refuses / triage listed | proven / unrendered | `candidate-dangling` `:754`; `task-findings` `:742` (93) |
| 7.6 | settlement | designed; the merge-task law (c03761415) | — |
| 7.9b | test seeds as edges | source-present | `fn.clj:754`; `seed_admission_test.clj:60` |
| 7.10 | task context; function shows its tasks | half | `task-ai` (`render/ns.clj:142`); no open tasks in `function-ai` (`:228`) |
| 7.11 | issues → tasks | started | 2 of 539 |
| 7.13 | tasks never ride merge | proven | `:seon.data` partition (`seon.task.edn:23`) |
| 7.14 | bootstrap exception | in force | `write-back!` covers fn, test and ns rows only |
| 7.15 | one writable representation | open | §0.2 |
| 7.16 | one writer with a basis | partly | `task-basis` (`issue.clj:611`) |
| 4b | docs queryable, indexed, stale docs surface | open | this design |

**Counts:** proven 9, installed or unexercised 5, source-present 3, designed 4, open 14 (1 explicitly outside the
cut), violated 1.

## 2. Inventory and dispositions (owner answers applied)

### 2.0 Reproducible counts (HEAD cf3d3cfb9)

- Tracked Markdown: `git ls-files -- 'docs/*.md' '.agents/skills/*.md' | wc -l` → **1,776** (1,754 docs, 22 skills).
- Issue notes: `git ls-files -- 'docs/seon/issues/*.md' | grep -vE '/(README|index|AGENTS)\.md$'` → **586**.
  - By the first `^status:` line, verbatim: **539 open** (537 exact, 2 annotated);
  - **35 settled with a stated basis**;
  - **12 uncertain** (`fixed-unverified` 3, `fixed-unproven` 2, `fixed-pending-*` 2, `fixed (committed, unadopted)` 1,
    `fix implemented; … pending` 3, none 1).
- Tasks: `grep -c '#:seon.task{' resources/seon/tasks/*.edn` → 102 entries in 34 files.
- Task→note evidence: 35 occurrences, 34 distinct, 33 distinct exactly-open.
- Path-citation rot uses `rg -oP "(?<![/\w.-])(src|test|resources|bin|script)/[A-Za-z0-9_./-]+\.(clj|cljc|edn|md|sh)(:[0-9]+)?"`
  per tree, at 0818f4497. A citation counts as missing when its file is gone and as beyond EOF when its line exceeds
  the file length. That is a floor.

### 2.1 Dispositions

| Kind | Files | Citations / missing / beyond EOF | Disposition |
|---|---|---|---|
| Open issue notes | 539 | 1,375 / 187 / 40 | **Convert per record** into tasks through the issue owner (§3.8). The body stays evidence |
| Settled notes | 35 | — | convert as settled history; files go on the reviewed deletion list |
| Uncertain notes | 12 | — | **reviewed triage**, never deleted, never settled by subtraction |
| Issue README, `bin/issues-index` | 2 | — | a rendered task view replaces them after the last conversion |
| Architecture | 8 | 106 / 5 / 0 | **Move into owners as structured docstrings** (§3.1). Each claim is verified at its new owner, and AGENTS.md links (`:10`, `:106`, `:217`, `:380`) move in the same commit. Cross-cutting remainder and `vocabulary.md` become pages (§3.7) |
| ADRs | 16 | — | evidence; each ruling's current sentence moves into its owner's doc |
| Skills | 22 (3,082 lines) | 249 / 3 / 0 | **Workflow cards (owner)**: when to load, the bin/MCP calls, the laws, and `(doc ns)` / page ids. Mechanism text moves into owners' docstrings and pages. Links into PRDs (`repl/SKILL.md:9-10`, `data-oriented-clojure/SKILL.md:9`) are relocated first |
| Reference, research, PRDs, dashboards | ~1,140 | — | evidence while cited; otherwise on **the one reviewed deletion list (owner)**, which lands after its inbound references move |

## 3. The design

### 3.1 The docstring as structured Markdown

**The grammar is CommonMark plus one declared heading vocabulary.** The producer does, in order:

1. **D7, normalize.** Remove the common leading indentation of lines 2..n, like Python's `inspect.cleandoc`: a
   whitespace transform, not a text search. This is required so the parse tree reflects the authored structure
   (§0.6).
2. **Parse** with `nextjournal.markdown/parse` and the `internal-link-tokenizer`. Measured over all 3,064 current
   docstrings: **180.6 ms total, 59 µs per docstring** (S1, cold process). The earlier warm figure was 31 µs (P16).
   Resolution, validation and storage are not included; M1 records their clock row.
3. **Split:**
   - The **lead**, every block before the first heading, is the summary: what the thing is for and why. A docstring
     with no headings is all lead, and valid.
   - Each heading whose text equals, trimmed and case-folded, one member of `:seon.doc/section-kind` opens that
     section. Its content runs to the next heading of the same or higher level. The comparison is an exact enum
     match, not a pattern.
   - Any other heading opens an `:other` section that keeps its heading text.

**The section vocabulary** (the enum lives on its schema; adding a kind is accretion):

| Heading | Kind | Recognized content (node types) | Typed facts | Why the code cannot say it |
|---|---|---|---|---|
| (lead) | `:summary` | paragraphs | links | intent and reason |
| `Examples` | `:examples` | ```` ```clojure ```` fences; the prose between them explains | each form read by edamame with the holder's resolution environment. The form must read, and it must call the holder or a linked function. Its called symbols are stored as links | how to call it well; runnable at the REPL |
| `Edge cases` | `:edge-cases` | list items | one item each, with links and schema keys | boundary behavior the contract admits but does not explain |
| `Costs` | `:costs` | list items (the cost model in prose) and ```` ```edn ```` fences holding `:seon.doc/cost` maps | a cost map: `:seon.doc.cost/case` (what was measured), `/time-us`, `/allocated-bytes`, `/retained-bytes`, `/complexity` (for example `"O(datoms of attr)"`), `/measured` (commit and the probe function symbol) | CPU and memory behavior; the live profile is shown beside it, derived (§3.4) |
| `Recommendations` | `:recommendations` | list items | items with links | the intended use, and what to call instead |
| `Errors` | `:errors` | list items, each linking one error schema `[[:ns/error-key]]` | one item per error schema: when it happens and what to do | the contract lists **which** errors; the doc says **when and why** |
| `Known issues` | `:known-issues` | list items, optionally naming a task handle `[[task:abc123def456]]` | items, **mined** (§3.3 M1) | defects not yet fixed |
| `See also` | `:see-also` | list items of links | joins `:seon.fn/see-also` | navigation |

**What a doc must not repeat.** The renderer derives these and shows them next to the doc:

| Derived | Source |
|---|---|
| argument and return shapes | `:malli/schema` / derived contract, via arity input and output refs |
| the list of declared error alternatives | the output contract's error schemas |
| who calls it, what it calls | `:seon.fn/calls` and its reverse |
| live timings | `seon.profile` observations for this symbol and digest |
| tests reaching it, open tasks | test edges, `:seon.task/tasks` |

A heading named `Arguments`, `Parameters`, `Returns` or `Throws` is an `:other` section **and** a `restates-contract`
finding (§3.3 M4). The contract already says it; the doc should keep what the contract admits but cannot explain.

**Link grammar** (revision 2's D1–D6, revised):
- **D1.** A link names a function, test or namespace symbol, **or a qualified keyword that is a registered schema
  key**. Keyword links are now needed: an `Errors` item names its error schema, and an edge case may name a key.
  edamame reads the link text as exactly one symbol or one qualified keyword. `::alias/k` refuses, as for task ids
  (7.2).
- **D2.** A qualified symbol resolves through the holder's aliases (`seed-symbol`, `fn.clj:497`). An unqualified
  symbol resolves to the holder namespace's own definition, then its refers (about 8 lines). Pages resolve qualified
  names only.
- **D3.**
  - A program target must exist in the candidate world or be defined in the same publication.
  - An external Var must be loaded; it is stored as an observation with no deletion obligation.
  - A schema key must be in the projection.
  - Future subjects belong only in tasks.
- **D4. Refusals** are alternatives of `:seon.task/refused` (`task-refused!`, `fn.clj:614`), each naming the holder,
  section, text and resolution tried:
  - `:dangling-doc-link`;
  - `:malformed-doc-link`;
  - `:unreadable-doc-example` (an `Examples` fence that edamame cannot read);
  - `:invalid-doc-cost` (a `Costs` EDN block that fails `:seon.doc/cost`);
  - `:unlinked-doc-error` (an `Errors` item naming no error schema).

  Text the library does not make into a node is prose and never refuses.
- **D5. Stages** (1.15), each validated against the projection:
  - `:seon.doc/source` (normalized text);
  - `:seon.doc/tree` (the library's node map, never stored);
  - `:seon.doc/structure` (sections and items with resolved links, forms and cost maps);
  - the stored facts (§3.2).
- **D6. The REPL path stays total.** A refusal at REPL entry is a diagnostic beside the evaluated result, delivered
  in the agent's MCP reply or context (ee4044ff8, 60d323a08). Under `:gate` the row is not admitted; at publication
  it names file and holder.

### 3.2 The stored model: structure as facts, prose not copied

The docstring bytes stay once, in `:seon.fn/doc` / `:seon.ns/doc` / the schema form. The producer stores only what a
query or a check needs. Item prose is re-derived at render by the parse, memoized on the doc's digest (59 µs miss,
hit free).

| Attribute | Holder | Value | Why stored |
|---|---|---|---|
| `:seon.doc/sections` | fn, ns, schema row, page | components of `:seon.doc/section` | "which functions document costs / edge cases" |
| `:seon.doc.section/kind` | section | the `:seon.doc/section-kind` enum | query and render grouping |
| `:seon.doc.section/heading` | `:other` section | string | unknown headings stay visible |
| `:seon.doc.section/items` | section | components of `:seon.doc/item`, ordered by `/order` | per-item facts |
| `:seon.doc.item/links` | item | `[:set :seon.program/edge-symbol]`, `reference-to #{:seon.fn/sym :seon.test/sym :seon.ns/name}` | navigation and deletion obligation (with refs S0) |
| `:seon.doc.item/keys` | item | `[:set :qualified-keyword]`, `reference-to #{:seon.schema/key}` | errors explained, keys mentioned |
| `:seon.doc.item/cost` | `:costs` item | the `:seon.doc/cost` map as components | cost queries; comparison with the live profile |
| `:seon.doc.item/example` | `:examples` item | the form's source text (the fence body; a form is not a storable value) | read-only evaluation at render; the example-test slice |
| `:seon.doc.item/task` | `:known-issues` item | task handle | the mined task (§3.3 M1) |

- **Partition.** These are components of program declaration rows, derived from the same bytes the definition digest
  already covers, like `:seon.fn/calls`. They ride with the row and never need their own write-back.
- **Links.** Resolved summary and see-also links also join the holder's existing `:seon.fn/see-also`, so the walk's
  existing edge (`walk.clj:912`) follows them unchanged.
- **Size.** 3,064 docs contain about 4,400 top-level blocks. Once structured, roughly one section per block and an
  item per list element: a few thousand datoms, well below `:seon.fn/calls`' 65,605.

**Schema descriptions** use the same producer and model. `canonical-schema-rows` parses the description, and its
sections hang from the schema row. Most descriptions are one sentence (all lead).

### 3.3 Mining tasks

Every miner is one function of one database value that returns findings or a declared unknown (never an empty
result when a basis is missing). Findings go through **one entrance, `seon.task/create!`**. Its identity is `seon.id`
over the **aspect** `[producer subject distinguishing-value]`, so re-running a miner finds the same task and records a
recurrence (2.5, the error writer's pattern). Each generated task needs the platform task that delivers its miner
(2.11). Findings are grouped per owning namespace where the owner asked for that (the unreviewed-doc detector); a
defect with its own identity keeps one task.

| # | Miner | Input facts | Finding | Aspect (identity) | Task subject; proof |
|---|---|---|---|---|---|
| M1 | known issues in docs | `:known-issues` items | an item without `:seon.doc.item/task` | `[seon.doc/known-issues holder item-digest]` | the holder; the item is removed or its handle's task settles (7.6). The author adds the handle to the item and the holder's `:seon/tasks` in the fixing merge |
| M2 | recurring faults | error rows: `/operation`, `/declared-schema`, `/occurrence-count`, occurrences' `/count`, `/agent`, `/first-at` | a **core fault** (not a declared agent mistake) whose operation is a program function | `[seon.error/recurring signature]` | the operation's function; its regression names the task. The existing `:seon.error/resolved-by` digest rule (`seon.error.edn:297`) shows the fix |
| M3 | **repeated misuse** | agent-returned declared errors: the same `(operation, declared-schema)` from ≥ `:seon.config.doc/misuse-agents` distinct agents (dial, default 2) or ≥ `/misuse-occurrences` (default 3) since the last review | "agents keep calling F wrong" | `[seon.doc/misuse operation declared-schema]` | **a doc-or-refactor task** on F. Done when the finding is gone: F's `Errors` item for that schema, or F's digest (the refactor), was reviewed after the counted occurrences, and no new occurrence arrived since. Recurrence reopens it |
| M4 | contradictions (5.6) | structure + contract | an `Errors` item naming a schema that is not an output alternative; a declared error alternative that is not explained **when the function has an `Errors` section**; a `restates-contract` heading; an example calling neither the holder nor a linked function | `[seon.doc/contradiction holder kind key]` | the holder |
| M5 | metadata | `:deprecated` with live callers; a contract using `:any`/`:some` with no reason (10.1 advisory); a public function or non-test namespace without a doc (3.6) | one class each | `[seon.doc/metadata holder class]` | the holder, or the callers' namespaces for deprecation |
| M6 | unreviewed documentation | §3.6 | own-change, linked-change, unreviewed, basis-unknown | per owning namespace: `[seon.doc/unreviewed ns]` | the namespace; the §3.6 proof |
| M7 | measured cost | a `Costs` map vs the live profile: `mean-us` > 2× the documented `/time-us` for the same digest, or a live `max-ms` > 1,000 (the sub-second law) | "cost drifted" or "over one second" | `[seon.doc/cost holder]` | the holder; the law's "fix the cost or justify it beside the number" |

**Misuse is the owner's feedback loop.** When the same wrong call keeps coming back, M3 raises one task per
function and error schema. The first fix may be a better `Errors` or `Recommendations` item. If misuse continues
after that change was reviewed, the task reopens with its recurrence count, which signals root to change the API
itself. Both roads settle through the same proof.

**Durability.** Errors, occurrences and runtime tasks are data, so a store reset or nuke loses them (§0.1). Two
choices, in Q2:
- a mined task is **promoted** into its owning namespace's task EDN when root triages it;
- or it stays database-only and is re-mined only when its inputs recur.

M1, M4, M5 and M7 re-derive from committed code, so they survive any rebuild. M2 and M3 depend on retained errors.

### 3.4 Surfacing: where each fact appears

| Where | What appears | Mechanism |
|---|---|---|
| **An agent working on F** (task context: seeds → subject F, hop 1) | lead; `Edge cases`, `Recommendations`, `Known issues` (with task handles), `Costs` (with the live profile beside it); `Examples` as runnable forms; the derived contract and error alternatives; open tasks and misuse counts (M3) | `function-ai` renders the structure, re-parsing on a memoized miss. Addresses (links, callers) stay names (5a.3). The floor alone clips |
| **An agent calling F** (distance 1 in the walk, or F named in a proof or seed) | lead, `Edge cases`, `Costs`, `Recommendations`; examples by count, with the call that shows them | the same renderer at the lower distance |
| **An agent receiving an error from F** (refusal time) | the `Errors` item for that `declared-schema`, and F's `Edge cases` and `Recommendations`; if M3 counts this misuse, "k agents hit this" plus the task handle | the error render pair: one lookup `[F :errors item with key = declared-schema]` over the error's own `:seon.error/operation` and `/declared-schema`. It is placed beside the error value, never replacing it (the total path) |
| **`(doc f)` in the agent's SCI** | sections, the derived contract, error alternatives explained or unexplained, profile | `doc` reads the stored program row (as `source` does since 5454abd5b) and renders the same structure |
| **The debug page** | the same unit through `function-html`: sections as headed blocks, cost table (documented vs live), examples with an evaluate expander, known issues and misuse tasks linked, "unreviewed since …" | one pipeline, two consumers (11.3) |
| **Queries agents can ask** | `(my.program/doc-items {:seon.doc.section/kind :costs})`; `(my.program/documented-errors 'f)`; "functions whose `Errors` section misses an alternative" (M4 as a query); "functions with known issues in ns X"; "costs over 10 ms" | thin `my.program` calls over `seon.db/q` on §3.2's attributes |

### 3.5 Link guarantees and their prerequisites

| Guarantee | Delivered by | Prerequisite |
|---|---|---|
| links, keys, examples and cost maps are valid at admission | §3.1 at the producer | nextjournal/markdown in `deps.edn`, vendored, `analyze-dependencies!` run once |
| navigation both ways | `:seon.db/reference-to` on each link attribute | refs **S0** |
| removing a linked program target refuses unless the doc is repaired in the same transaction | derived deletion obligation | refs **S0 with its open review findings (1, 2, 4, 8) resolved** |

Without S0, links are validated at admission only, and M6 reports a later dangling link as linked-change.

### 3.6 Detector of unreviewed documentation (M6)

- **Owner answer:** it starts with **all** documentation unreviewed, worked through per-namespace tasks. There is no
  baseline.
- **Holders:** fn and ns rows with a doc, schema rows with a description, and page sections.
- **Inputs** from one database value:
  - the holder's declaration digest (`program.cljc:388`), which includes metadata, so it errs toward reporting;
  - the current digests of its link targets.
- **Review record** (data partition): `:seon.doc.review/holder`, `/digest`, `/targets` (target + digest), `/by` and
  `/commit`. It is written by `seon.doc/reviewed-tx`:
  - on root's acceptance of a merge task whose diff changes the holder's doc (the merge-task law supplies the holder
    list from the diff);
  - or by root's `(my.program/reviewed! 'holder)`.
- **Findings,** each sufficient on its own:
  - own-change: the digest differs from the review's;
  - linked-change: a target's digest differs, or the target is gone;
  - unreviewed: no record;
  - basis-unknown: a declared error value, never omitted.
- **Clearing:** current state is compared with the last review only, so a repaired doc clears and an unrelated commit
  changes nothing. Filesystem checks are outside the proof.
- **Proof:** a deftest naming the namespace task,
  `(is (= {} (seon.doc/unreviewed (seon.db/db) {:seon.ns/name 'x})))`.
- **Regressions:** red → repair → empty; a re-run without duplicates; an unrelated commit; a linked-only change; a
  missing basis; a metadata-only edit.
- **Starting load.** About 3,064 holders, plus schema descriptions (487), in about 280 namespace tasks.
  - Each task's work: read the doc against the code, restructure it into sections, move measured prose costs into a
    `Costs` block, turn "Example:" paragraphs into fences, write edge cases and recommendations, and delete what
    restates the contract.
  - Root accepts through the merge task, which writes the review records.

### 3.7 Pages share the parser and the model (option B)

- A page is `resources/seon/docs/<page>.md`. Its first block is a fenced EDN head `{:seon.doc/id sym :seon/must-read
  [...] :seon/see-also [...]}`, and the file stem must equal the id's name.
- The page row holds the same `:seon.doc/sections` as a docstring. Every heading becomes a section: known kinds keep
  their meaning, and others are `:other`, identified by `[page-id path]` (the heading-slug path from the library's
  nesting). Duplicate paths refuse.
- Pages reconcile as a whole page value at publication. A rename retracts and adds a section; a removal retracts.
- A page is a **program** declaration derived from its file, and the file is its only authored input.
- A section's review basis is set only by a review record (§3.6).
- Pages, docstrings and descriptions are therefore one parser, one stage chain, one stored model and one detector,
  held by three kinds of holder.
- Until data-file write-back lands, pages are edited in the shell (Q1).

### 3.8 Per-record issue conversion (unchanged from revision 2)

- `seon.issue/convert!` takes an issue id and a reviewed decision:
  - `{:reuse handle}` or `{:create entry}`, through the one task writer with its basis;
  - `{:settled evidence}`;
  - `{:triage}`.
- In one transaction it writes `:seon.issue/task` and marks the issue converted. Issue-writer and `my.issue/add!`
  edits then refuse naming the task, and old-id reads resolve to the task.
- The note moves to `docs/seon/evidence/issues/`, outside `note-path?` (`issue.clj:127`).
- `index-tx` stops retracting converted issues (`:492`).
- The association manifest is committed under `docs/research/agent-platform/issue-conversion/`, and root reviews
  each batch.
- Runtime issue writers move to `seon.task/create!` in a separate family slice before 2.1 is claimed. M2 and M3 are
  the first miners built on that entrance, which makes them the natural first converts.
- **Proofs:** a repeat conversion is a no-op; an old-id read and write; reuse creates nothing; the end state has no
  unconverted, untriaged note in the indexed path.
- After the last conversion the Markdown reader is retired:
  - `issue.clj:38-186`, `:220-286`, `:325-532`, about −424 lines;
  - `index-issues!` and `bin/issues-index`;
  - `publication_notes_test.clj`.

  `citation-attributes` and `replacement-tx` are kept, because other paths still use them.

### 3.9 Write-back and durability

- **Docstrings and namespace docs:** the installed write-back splices function and namespace spans
  (`source.clj:1192`). A restructured doc is an ordinary change under the merge-task law.
- **Schema descriptions, task EDN, promoted mined tasks, pages and review records:** all need data-file write-back,
  which is one shared slice. Until then they are shell-edited under the 7.14 exception (Q1 for pages; Q2 for review
  records and promoted tasks).

### 3.10 Migrating the existing docstrings

- **All 3,064 stay valid as they are.** With no headings, each parses as a lead. Its existing `[[links]]` resolve
  under D2: 40 of 42 today, and M0 repairs the two dangling ones.
- **The detector starts every holder unreviewed (owner).** The ~280 per-namespace M6 tasks are the migration. Each
  task restructures its namespace's docs by hand at the REPL (`defn` with the new doc), and root accepts through the
  merge task.
- **One scripted, reviewed pre-pass** (mechanical-edit law): the 58 "Example:" paragraphs become `## Examples` with a
  `clojure` fence. The script's output goes through `edamame` and the D4 checks, is committed per namespace, and its
  manifest is kept as evidence. That is the only automated rewrite. Everything else needs judgment.
- **The 40 docs with measured costs in prose** are listed by the M6 task for their namespace, found once by the survey
  script and not by a production scan. Their numbers move into `Costs` blocks with the `/measured` commit, or are
  re-measured.
- **The 470 docs that mention errors** become `Errors` items linked to the contract's alternatives when their
  namespace task runs. M4 checks them against the contract.

## 4. Prices (unverified Opus estimates; source plus tests)

**The common core** covers every option: the owner's answers make the structured docstring and the miners core.

| Part | Added | Deleted | Lane-days |
|---|---|---|---|
| refs S0 (targets; derived deletion obligations; review findings first) | +40 | −42 | 1 |
| S1: the docstring producer: D7 normalize, parse, split, sections and items, examples, cost maps, D1–D5, refusals | +110 | the misfiled ns-level references (refs T4) | 1.5 |
| schemas: `:seon.doc/*` sections, items, cost, section-kind, stages | +45 (EDN) | — | 0.5 |
| `seon.task/create!` + `:seon.task/producer` + aspect identity | +40 | — | 0.5 |
| miners M1–M7 in one `seon.doc` namespace sharing one findings shape and the dials | +150 | — | 2 |
| M6 review records and `reviewed!` | +40 | — | 0.5 |
| surfacing: `function-ai`/`-html` structure, error-render doc items, SCI `doc`, `my.program` queries, `(task h)` | +110 | the one-line doc header path (−10) | 1.5 |
| regressions: producer (each refusal class and a golden structure), miners (red/repair/empty, recurrence, unrelated commit, misuse threshold), surfacing (AI and HTML of one unit) | +220 | — | (in the above) |
| per-record issue conversion (`convert!`, refusals, `index-tx` clause, regressions) | +100 | — | 1 |
| retire the Markdown issue reader | — | about −435 source, −58 tests, `bin/issues-index` | 0.5 |
| **Core total** | **about +855** | **about −545** | **about 9 lane-days of code** |

**Migration effort, not code:**
- issue conversion review: 3–4 lane-days;
- the ~280 namespace doc tasks: the largest body of work, spread across agents, a few minutes to an hour per
  namespace;
- architecture into docstrings: about 1 lane-day.

**Honesty about size.** +855 lines is far past the 100-line presumption, and the core deletes only about 545. What
justifies it:
- it replaces a hand prose parser with a library parse tree;
- it makes docs, errors and misuse one queryable graph (the owner's ask);
- it lets the ~3,000 docstrings carry knowledge that is currently lost.

**The smallest first slice that proves the loop** is S1 plus schemas plus `function-ai` structure plus producer
regressions: **about +250**. It parses real docstrings, stores sections and shows them in context. Miners follow only
after that slice is green and measured. If the owner wants less, M7 (cost drift vs the profile) and M4 (contradictions)
can wait, saving about 70 lines.

**Options** (the owner's direction settles the docstring core; what remains is long-form):
- **A. Core only.** Cross-cutting prose (`vocabulary.md`, architecture remainders) stays unindexed Markdown.
- **B. Core plus pages (recommended).** The same parser, model and detector, with a page holder: about **+95**
  (the page holder, reconciliation, duplicate refusal and render) and about 1.5 lane-days. Sacrifice: shell editing
  until data-file write-back (Q1).
- **C. Core plus EDN pages at the REPL.** About +140 plus the data-file write-back slice (+150, 2–3 lane-days).
  Sacrifice: prose becomes hiccup data, and it waits on write-back.

### Migration order

| # | Step | Needs | Proof |
|---|---|---|---|
| M0 | Repair `restore!`'s two links | `schema.clj` free | 42/42 resolve |
| 1 | refs S0; S1 producer + schemas + `function-ai` structure (the +250 first slice) | dependency vendored | each D4 refusal; a golden structure for a multi-section docstring; 3,064 docs admitted unchanged; reindex and one-file publication clock rows, parse separated from resolution, validation and memory |
| 2 | `seon.task/create!`, `(task h)`, M6 with review records; the namespace tasks begin | 1 | the §3.6 regressions |
| 3 | "Example:" pre-pass (reviewed script) | 1 | examples read; M4 finds no stray calls |
| 4 | M1, M4, M5, M7 | 2 | per-miner red/repair/empty and recurrence |
| 5 | M2, M3 and the error-render doc items (refusal-time surfacing) | 2, and Q2 | misuse threshold regression: two agents × one schema → one task, a third occurrence counted, a reviewed `Errors` item clears, and a new occurrence reopens |
| 6 | Issue conversion batches | 2 | §3.8 proofs |
| 7 (B) | Pages; `vocabulary.md` and the architecture remainder | 1, 2 | page reconciliation regressions |
| 8 | Architecture into owners' docstrings, one owner per commit | 1, 7 | Astra verifies each claim at its new home; AGENTS.md links moved |
| 9 | Retire the Markdown issue reader | 6 complete | `rg` empty; publication clock row |
| 10 | Skills become workflow cards | 8 | the citation checker; each removed claim present in a doc |
| 11 | The reviewed deletion list | 8, 10 | no inbound references |

## 5. Questions for the owner (only the genuinely new ones)

Already answered on 2026-09-26: skills become workflow cards; all docs start unreviewed; deletion goes through one
reviewed list; docstrings are structured Markdown with issues mined from docs, metadata and errors.

1. **Long-form pages (B):** may cross-cutting prose (`vocabulary.md`, the architecture remainder) become Markdown
   pages on the same parser, edited in the shell until data-file write-back lands? This extends the 7.14 exception to
   pages. The alternatives are to keep it unindexed (A), or to wait for EDN pages (C).
2. **Durability of mined data:** errors, runtime tasks and doc review records vanish with a store reset or nuke
   (§0.1). The options:
   - **(a) recommended:** root's triage promotes mined tasks into task EDN, and review records are committed per
     namespace as `resources/seon/reviews/<ns>.edn`, shell-committed until write-back;
   - (b) database-only: a nuke returns every doc to unreviewed and forgets misuse history;
   - (c) the review digest goes into each `defn`'s metadata. That puts it in code, which makes metadata larger than
     the function, the thing the owner warned against.
3. **Section vocabulary:** is this the right fixed English heading set: Examples, Edge cases, Costs, Recommendations,
   Errors, Known issues, See also, plus the lead as the summary? Any rename is cheap now and expensive after the ~280
   namespace tasks run.

## Appendix: probes and survey

**Database probes** (read-only JVM mode; each bound `(datahike.api/db (seon.cluster.boot/connection "default"))`):

| Probe | Result |
|---|---|
| P1–P5 (revision 2; pids 48787 and 80197) | fn 5,306–5,309; fn doc 2,774–2,775; ns doc 280; schema descriptions 487 of 3,502; fn must-read and see-also 0; task annotation edges 15 on 14 holders; 42 doc links, 40 resolve; triage 93 (review probe) |
| P6 (post-restart store, session `docs-in-code-design-2`, 30 ms) | error signatures 1 (`seon.cluster.agent/turn-step`), occurrences 1, operation-and-schema pairs with more than one agent: 0. It also exported fn and ns docstrings to the scratchpad as `docs.edn` (911 KB) for S1 |

**S1, the docstring structure survey.** Disposable `clojure -Sdeps '{:deps {io.github.nextjournal/markdown {:mvn/version "0.7.213"}}}'`
process over `docs.edn`, 0.78 s wall. Its core, kept here because the scratch file is not durable:

```clojure
(defn dedent [s] (let [[h & t] (str/split-lines s)] (str/join "\n" (cons h (map #(str/replace % #"^  " "") t)))))
(md/parse {:text-tokenizers [u/internal-link-tokenizer]} (dedent doc))
;; counted: top-level node :type frequencies; :heading nodes; :code nodes; :bullet-list/:numbered-list;
;; :internal-link nodes; paragraphs whose first line is "Label:"
```

Results:
- 3,064 docs (2,783 fn, 281 ns);
- 180.6 ms total, 58.9 µs per doc (cold);
- top-level nodes: paragraph 4,398, bullet list 22, numbered list 5, code 4;
- headings 0; internal links 46;
- label paragraphs: "Example" 58, "Returns …" 9, others at most 12 each;
- docs with a measured cost in prose: 40;
- fn docs mentioning refuse, throw or error: 470;
- fn doc length: median 142, p90 553, max 3,175 characters.

The measurement's `dedent` and counting regexes are probe-only. Production uses D7 (common-indent removal) and parse
nodes only.
