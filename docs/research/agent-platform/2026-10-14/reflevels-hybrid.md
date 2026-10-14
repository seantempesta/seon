---
title: "Reference levels: the hybrid of the Opus and Astra proposals"
type: research
status: hybrid; decides between the two proposals, nothing installed; replacement text for TASK-CHAIN §3a and §7 at the end
date: 2026-10-14
basis: "refactor/agent-platform at c31780f9d; proposals reflevels-opus.md (c81161289, 12:41) and reflevels-astra.md (2b0038eb4, 13:02), dig 207e7c213, context-sanity.md, doc-view-measure.md; code read in the working tree and at HEAD; no SCI request, no JVM probe; live default runs archive de1f48c23 (older than HEAD)"
---

# Reference levels: hybrid

What exists: TASK-CHAIN §3a fixes three delimiters (bare, `` `x` ``, ```` ```x``` ````) and says each is a REPL
function's value; the dig found none of it implemented (depth is graph distance in five render functions). Two plans
were written independently. They agree on the shape and the owners; they differ on where a schema key's data flow
sits, on links and double backticks, and on how the key→functions query is answered. This note takes the agreement,
decides the differences by the measured reason, names what both missed, and lays the phases out so the first one
replaces S3d-1.

The one-line design: **a reference's level is read from its delimiter, and each level is the value an existing REPL
function already answers** (`seon.ref/brief`, `doc` = `seon.sci.lookup/documentation-value`, `source` =
`my.program/source`), extended at its owner for the kinds it does not yet cover. The resolver chooses the level
before it acquires anything. Distance decides nothing; the graph only names the schemas a contract names. The value
printer is the only size limit.

## 1. Agreement, differences, what both missed

### Taken from both (they agree)

| Point | Opus | Astra | Taken |
|---|---|---|---|
| The three questions | what is it / how do I call it / how does it work | what is this / how do I use it / what defines it | yes, one owner per question |
| Owners | `seon.ref/brief`; `doc` (`seon.sci.lookup/documentation-value`); `source` (`my.program/source`), `my.fs/section` for documents | the same three, `doc` and `source` extended to tests, tasks and paths | yes; Astra's extension (§2) so `(doc x)` and the one-backtick reference are one value for every kind |
| Doc level of a function = today's `doc` plus the schemas its contract names, each described once | `:schemas` from `documentation-schemas` (computed and discarded today, 0 new reads) | "directly named schema descriptions once" | yes; measured by both: `seon.ref/section` 75 → 214 (Opus) / 174 (Astra); `seon.sci.reader/read` 249 → 294 / 281 |
| Code level of a function = its exact stored source, no inlined callers | callers and tests-reaching stay operations (126–353 tokens each inlined) | no automatic callees | yes |
| Delete the distance tiers, `names-shown`, `inner`'s callee half, `source-lines` as the source representation | yes | yes | yes (§4) |
| Delimiter width is readable | `IncludeSourceSpans/BLOCKS_AND_INLINES` is on; a `Code` node's first `SourceSpan` selects the literal; probe 1/3/2 | `Code.getSourceSpans()` includes the backticks; live JVM returned 1, 3 and 2 distinctly | verified twice, independently |
| Document levels | heading + lead sentence + size / lead + headings below with sizes / whole section | title + tokens + opening sentence / opening paragraph + child headings with anchors and sizes / whole section | yes; Astra measured the alternative "everything before the first child heading" and it equalled the full text on all three leaf sections (932/570/2105), so the lead paragraph it is |
| The lead rule's check is in the same CommonMark walk, never regex; a missing lead is a problem on the cited document, not a refusal; no lint of every document per edit | yes | yes | yes (§3) |
| One name cited at two levels in one unit: the strongest wins, at the first occurrence; later mentions are names | yes | yes, plus: a nested schema citation never promotes | yes, with Astra's clause |
| Deftest levels | name + status / tasks proved, calls, last failure / source | name + recorded outcome or typed unknown / + direct analyzed calls / source + same | yes (§2) |
| The first-call test on task A | ~1k tokens, owner and proof whole; still no fix because the read event's schema is unnamed | ~902 data tokens; the first call shows the counterexample | yes; the hybrid keeps Opus's filed task `seon.sci.reader/name-the-read-event-schema` |

### Decided where they differ

1. **Who takes and who returns a schema key: which level.** Opus put both name lists at the doc level (the data flow
   is the key's interface, law 1); Astra and the owner's note put them at the code level (doc = form and description
   only). Measured (Opus): returners per key 6 / 2 / 2 / 3 / 36, takers 0 / 0 / 0 / 0 / **897** (`:seon.db/database-value`,
   a binding schema: its takers are the whole program; doc with both lists 9,004 tokens). Decision: **doc = form,
   description and `:returns`** (who produces it: that answers "where do I get one", which is the data-flow question
   an agent building a value asks); **code = everything**: `:takes`, `:guards`, `:members` described one level down,
   `:referenced-by`, `:writes` and the datom count. Takers are the dependents, which is the question asked when
   changing the key, so they sit with the definition. Every measured key's doc level then stays in Astra's 68–183
   range plus a few names; the outlier's 897 names are one `source` away. Owner question 1.
2. **How the key→functions query runs.** Both measured the obvious Datalog join and found it expensive: Opus 154 ms and
   262 MB for 9 keys; Astra 20–60 ms and 29–65 MB per key, with `datahike.query/explain` showing a scan of the whole
   arities population (est. 32,533 rows). Astra found the owner that already does it right: `seon.db/attribute-holders`
   (private today) follows a vector cell to its logical owner with AVET seeks; composed key → arities → functions →
   `seon.db/pull-many` it answered the identical names in **0.54–0.71 ms and 0.43–0.99 MB** per complete key value.
   Decision: Astra's. Promote `seon.db/attribute-holders` to a public contracted owner that captures its read
   evidence (Astra's own caveat: the prototype did not), and every taker/returner read goes through it. No new index
   (`:seon.fn.arity/input-refs|output-refs|guard-refs` are already AVET), no fork bump; the planner defect stays
   filed (`seon.db/an-identity-join-scans-the-whole-ref-attribute`).
3. **Two backticks.** Opus: doc level ("1 or 2"); Astra: only widths 1 and 3 mean a reference. Decision: Astra's. A
   double-backtick span exists to wrap a literal containing a backtick (both say so), and that literal is never a bare
   name, so the rule is simpler and loses nothing: **1 → doc, 3 or more → code, anything else → a code span, not a
   reference**.
4. **Markdown links.** Opus: summary (a link is prose); Astra: doc (a link is an explicit reference, as §3 already
   says). Decision: **doc**. A link is an act of citing; its doc level (lead paragraph and headings below) measured
   70–170 tokens for a section (`#git-words` 123 / 167, `#3-the-plan` 70 / 128). This is what lets help "rely on the
   references to pull in details": a linked guide shows its lead, its sections one `(source "path#anchor")` away.
   Owner question 2.
5. **Namespace doc.** Opus proposed fixing `seon.sci.lookup/namespace-documentation` (about 106 clojure.core Vars
   listed per namespace); Astra said the fix belongs at SCI namespace installation, not a display filter. Both were
   overtaken: **c5a8cd8eb (13:06, after both proposals, in HEAD)** makes `doc` of a program namespace list only its
   declared functions, the set `dir` answers. The live JVM runs archive de1f48c23, which predates it, so the defect
   still shows live until root's restart. The root cause Astra names stays its own task
   (`seon.sci.kernel/an-agent-namespace-ns-interns-lists-only-its-own-vars`). No phase here.
6. **The task's doc level.** Opus: `my.task/current`'s view (status, whole text, proofs); Astra: the Problem's opening
   statement, that task's instructions, proofs with their recorded observations (Astra measured without instructions,
   145–249 tokens, and said that is not sufficient for an Implement task). Decision: **the Problem's lead paragraph,
   the task's instructions and its proofs with status**; the whole plan and `next` are the code level. Summary: name
   and the lead's first sentence (Opus: 398 tokens today because `brief` takes the first *line*; about 40 under the
   sentence rule).

### Missed by both

- **Tables are paragraphs to the parser.** `seon.ref/parsed` enables only `YamlFrontMatterExtension` (and
  `commonmark-ext-heading-anchor` is on the classpath); there is no GFM tables extension, so a `| table |` block after
  a heading parses as a `Paragraph`. Opus's compliance count (3,320 "paragraph" leads) therefore counts tables as
  leads, and the lead rule would take a table row as a section's summary. The check needs
  `org.commonmark/commonmark-ext-gfm-tables` at the same pinned version (0.24.0), a dependency, so it goes in root's
  restart batch (owner question 4).
- **A docstring is a document with no heading, so it has the same rule and the same problem.** Both plans fix
  missing docstrings at the docstring (`seon.edit/single-form` has none). Neither names the mechanism that tells the
  author: a var cited at summary or doc level with no docstring reports `:seon.ref/problem-kind :no-summary` on that
  var, exactly as a section with no lead reports `:no-lead` on its document. One rule, two text kinds; the owner's
  "we improve the docstrings of things when we want to improve its display" has a signal.
- **`doc` needs a SCI context today only by accident.** `seon.sci.lookup/function-doc-map` takes the docstring and
  arglists from `(meta (sci/resolve ctx resolved))`. The program row holds both (`:seon.fn/doc`, `:seon.fn/arglists`,
  `resources/seon/schemas/seon.fn.edn`), and a REPL `def` is a program entity (de1f48c23), so a program function's doc
  reads its row; the SCI context is needed only for a library Var with no row, and it is a binding filled by schema
  name from the evaluation. Otherwise the resolver (`seon.ref/references`, which has a database) cannot call `doc`
  in JVM mode at all.
- **The levels are for references in prose.** A plan, instructions, help, a docstring, a message. A value the REPL
  returns keeps its declared render; `(doc x)` and `(source x)` typed at the REPL are the same values the references
  show. Neither plan said so and S3d-1's old proof blurred it.
- **`seon.ref/declarations` is cached per projection**, not per request (`schema/projection-cache-value`), so Astra's
  item 1 is a cost only on a projection change; not a phase.
- **Help's level policy.** Owner: "(help) should rely on the references to pull in details so they aren't repeating
  themselves". Under the rule that is an authoring choice: help cites its verbs at doc level (`` `my.task/update!` ``
  shows its contract and the schemas it names) and everything else bare. `(help)` is 2,069 tokens today
  (doc-view-measure); the rewrite's size is unmeasured until it exists (S3d-5).

## 2. The per-kind table

Tokens are `seon.ai.tokens/estimate`. "O" = Opus (SCI, `pr-str` of the value), "A" = Astra (SCI, the value printer at
a 100k diagnostic budget); both single warm samples. Cost per call is Astra's acquisition ms where measured.
Unmeasured cells say so. The REPL form is the level's value for the REPL and for the reference.

| Kind | summary (bare) | doc (`` `x` ``) | code (```` ```x``` ````) | One producer, extended at its owner |
|---|---|---|---|---|
| Function | name, docstring's first sentence, read form. `seon.edit/single-form` 35 O / 34 A; `seon.ref/section` 70 / 70; `my.task/refine!` 62 | docstring parts, agent arglists, contract `:in`/`:out`/`:supplied`, **`:schemas`**: each named schema → its description (else its form). 72 / 47; 214 / 174; 1,081 (8 schemas). ~1 ms A | exact stored source, contract and file, no callees; callers and tests-reaching stay operations. 373 / 371; 905 / 886; 1,728. ~0.25 ms A | `seon.ref/brief` as is; `seon.sci.lookup/documentation-value` + `:schemas` (from `documentation-schemas`, 0 new reads) reading the program row; `my.program/source` as is |
| Library Var | name, doc line, pin when known. `malli.core/validate` 48 / 49 | metadata doc and arglists; no invented contract. 75 / 72 | pinned source or typed source-unavailable; never loads a library to inspect it. 205 / 105; 1.5–6 ms A | the same three; `seon.ref/library-name`, `seon.sci.lookup/stored-source-fn` |
| Schema key (attribute, error type, dial alike) | description, else the form. 15–69 O / 9–44 A | form, description, **`:returns`** (names, by use count). unmeasured as such; bracketed by A's form-only 68–183 and O's both-lists 98–259 | + `:takes`, `:guards`, `:members` described one level, `:referenced-by`, `:writes`, datom count. 439–801 O / 118–974 A; **0.54–0.71 ms, 0.43–0.99 MB** per key through `attribute-holders` (join: 20–60 ms, 29–65 MB) | `seon.ref/schema-documentation` + `:returns`; `my.program/source` of a keyword; both through public `seon.db/attribute-holders`; `seon.render.schema/description` public |
| Namespace | name, doc sentence. 39–89 O / 40–77 A | docstring and its own public names (c5a8cd8eb). ~50–120 O / 45–139 A | each declaration with its doc value (lazy), the namespace's schemas. 862–2,643 A; 4.7–9.6 ms A | `brief`; `documentation-value` (`namespace-documentation`); `my.program/source` of a simple symbol (`namespace-declarations`) + each declaration's doc |
| Deftest | name, latest recorded status with its basis, or typed unknown. 41–48 A (+status unmeasured, O "+ status") | + the tasks it proves, its analyzed calls, when red the last run's failing `:expected`/`:actual`. 86–102 A | exact source + the same. 141–253 | `brief` + `seon.test/recorded-result`; `documentation-value` extended to `:seon.test/sym`; `my.program/source` as is |
| Task | name, first sentence of the plan's Problem lead. 35–42 A (398 today, O) | Problem lead paragraph, instructions, proofs with status. unmeasured with instructions (145–249 A without) | whole plan, instructions, proofs with status, `next`; no recursive expansion. 579–1,061 A | `brief` (sentence rule); `documentation-value` and `my.program/source` extended to `:seon.task/sym`, sharing `my.task/current`'s selection, which takes a named task |
| Markdown section | heading, lead's first sentence, tokens. 26–67 O / 95–116 A | lead paragraph, headings below with anchors and tokens. 70–123 O / 90–167 A; ~1 ms A | whole section through the next peer heading. 512–2,039 O / 570–2,105 A | `seon.ref/section` (keeps `:tokens`/`:below`; adds the lead), called by `brief`, by `documentation-value` and `my.program/source` on a path string, and by `my.fs/section` |
| Markdown file | title, tokens, opening sentence. 53–98 A | lead and the H1's children. 199–469 A | whole file. 1,239–14,953 A | the same, anchor-less |
| Pinned library document | the same, carrying its pin | the same | the same | `seon.ref/locate`; no convention imposed on third-party writing |
| Non-Markdown file | path, pin, tokens. 13–54 A | the same (no invented summary of code or EDN) | whole file as text. 611–47,386 A | `seon.ref/locate`, `my.fs/section` |
| Unresolved reference | its typed problem | the same | the same | `seon.ref/answer`; `my.program/nearest`'s whole-program scan is never run as part of a failed reference |

Rules that cross the rows: a referent's nested references are only the schemas its contract names, cited once
(`seon.ref/cite-once`); in one shown unit the strongest requested level wins at the first occurrence and later
mentions are names; a nested citation never promotes; levels select before acquisition (resolve identity, choose the
level, pull that level's members: Astra's "deletion, not a filter").

## 3. The Markdown writing rule and its check

**Rule.** A section that is cited for its summary or doc opens with a **lead**: one CommonMark `Paragraph` that is
the heading's own next block (not a paragraph inside a list item under it). The lead's first sentence is the summary;
the whole lead is the doc level. A heading followed directly by a subheading has no lead and its doc level is its
heading tree. Distinct subtopics get real child headings, not bold pseudo-headings. Tables, lists, fences and examples
follow the lead or sit under their own heading. A docstring is a document with no heading: its lead is its first
top-level paragraph, which is the rule `seon.ref/docstring` already applies (`:seon.ref/first-paragraph`, to become the
first *top-level* paragraph: today it finds a paragraph at any depth, so §2 Flow's lead was found inside list item
"1."). Writing a lead that actually summarizes cannot be checked mechanically; §3a's teaser lead shows the gap.

**Check, commonmark only.** In `seon.ref/parsed`'s one walk, each heading records `:seon.ref/lead` (line, end-line)
when its next sibling is a `Paragraph` and the tables extension is on so a table is a `TableBlock`. A reference at
summary or doc level to a section with no lead answers `:seon.ref/problem-kind :no-lead` with the path and anchor,
reported on the cited document (compare `seon.ref-test/a-nested-docstring-problem-belongs-to-its-var`); a var with no
docstring cited the same way answers `:no-summary` on the var. A code-level citation needs no lead. Nothing is refused,
nothing invents a summary from a child, and no document is linted because another was edited.

**Measured compliance.** Opus, over 863 Markdown files (`docs/seon/**`, help) by CommonMark: 5,159 headings; 3,320
followed by a paragraph (which includes tables, see §1), 1,192 by a subheading (allowed), 647 by a list, fence or
quote (non-compliant). A string-rule estimate this note ran (dated, not the check; a scratch script, not committed) by
document class: living documents (AGENTS.md, help, architecture, cards, specs, skills; 276 files) 2,068 headings:
1,591 paragraph, 377 list, 53 fence, 24 table, 23 subheading, so about **454 (22%) non-compliant**; dated research
(1,039 files) 10,810 headings, about 4,411 (41%) non-compliant, 1,460 of them tables.

**Non-compliant documents.** No sweep. A section is fixed when a plan or help first cites it at summary or doc level
and the `:no-lead` problem arrives; research notes are cited at code level or not at all. Pinned library documents
keep their structure and yield whatever levels exist (Astra). Owner question 3 offers a sweep of the living documents.

## 4. What is deleted

Each deletion lands in the phase that converts its last caller (§6b).

- The distance tiers: `seon.render.ns/node-distance`, the near/far branches of `seon.render.ns/function-ai`,
  `seon.render.ns/render-ai`, `seon.render.ns/task-ai`, `seon.render.test/render-ai` (its `near?`) and
  `seon.render.schema/render-ai` (`listed?`, `selection`'s usage branch), and `:seon.render/distance` as a render
  selector (`seon.context/must-read-ai`'s `(inc distance)`; the walk's own `:seon.render/distance` for graph depth in
  `seon.render.walk` is a different use and stays).
- `seon.render.ns/source-lines` as the representation of a cited source (a source is one value the printer cuts with
  one elision form, never lines cut each).
- `seon.render.schema/names-shown` and the three-attribute join in `seon.render.schema/usage` (the value printer is
  the only cap); `my.program/key-data`'s merged contract-refs join (`reads-key` keeps its keys, computed by the owner);
  `my.program/producer`'s own output-refs query (it reads `:returns`).
- The callee half of `seon.ref/inner` (`:seon.fn/calls`); a doc view's nested references are the schemas its contract
  names. Callees were half of the doc view's tokens (doc-view-measure).
- `seon.ref/resolve-path`'s `dissoc` of `:seon.ref/text :seon.ref/below :seon.ref/tokens` (replaced by level
  selection), and `seon.ref/section`'s re-split of the file and per-child `section-end` scans (one heading fold).
- `seon.ref/brief`'s first-*line* rule for tasks (the shared first-sentence rule).
- The two-tier doc-view wording of TASK-CHAIN §3a ("doc view, full form one evaluation away") and S3d-1's old proof
  text (replaced below).

## 5. Phases

Each is loadable alone, HEAD boots after it, each names a red deftest asserted on data. Files named by owner; a
phase waits for its file (`src/seon/ref.clj`, `src/seon/context.clj`, `src/seon/render/ns.clj`,
`src/seon/render/schema.clj`, `test/seon/ref_test.clj`, `test/seon/context_test.clj`,
`test/seon/sci/documentation_test.clj` are held by lane `s1a-rename` now; S1a lands first per §7).

| # | Phase (owner change; deletion) | Red proof: deftest and its data assertion | Cost per change | Model |
|---|---|---|---|---|
| S3d-1a | `doc` describes the schemas it names and reads the program row: `seon.sci.lookup/function-doc-map` adds `:schemas` (key → `:description`, else form) from the map `documentation-schemas` already computes; doc and arglists from `:seon.fn/doc`/`:seon.fn/arglists`, SCI metadata only for a library Var; `seon.render.schema/description` public | `seon.sci.documentation-test/doc-describes-each-schema-its-contract-names`: `(get-in (doc seon.ref/section) [:schemas :seon.ref/section :description])` is a string; the value has no `:source`; the same value in JVM mode with no SCI context for a program function | 0 new reads; doc 75 → 214 tokens for `seon.ref/section` | Sonnet |
| S3d-1b | The level is read and dispatched (replaces S3d-1): `seon.ref/parsed` keeps each code span's delimiter width; `seon.ref/candidate` derives `:seon.ref/level` (`:summary` bare or prose word, `:doc` one backtick or link, `:code` three or more; two is a code span); `seon.ref/references` resolves a name through `brief` / `documentation-value` / `my.program/source` by level, strongest first occurrence wins; `seon.context/must-read-ai` and `seon.render.ns/function-ai` lose their tiers, `inner` its callees, `source-lines` goes | `seon.ref-test/a-code-span-carries-its-reference-level`, a table: `x/y` → `:summary`, `` `x/y` `` → `:doc`, ```` ```x/y``` ```` → `:code`, ``` ``a `t` x`` ``` → no candidate, a fenced block → no candidate; `seon.context-test/a-referenced-function-shows-its-doc-view`: in one plan a bare name shows only its first sentence, a one-backtick name's entry equals `(doc x)`'s value (with `:schemas`), a three-backtick name's entry holds the whole `:source` string, a fence stays code, and the same name at two levels appears once at the deeper one | one `subs` per span inside the digest-cached parse; resolution acquires the level's members only; no callee pulls | Opus |
| S3d-2 | Documents and docstrings: per-heading `:seon.ref/lead` from the next sibling, `:seon.ref/first-paragraph` top-level only; one heading fold for section ends and children; `seon.ref/section` keeps `:tokens`/`:below`, adds the lead, selects after the front-matter node (Astra's `---` defect); `documentation-value` and `my.program/source` take a path string; `:no-lead` and `:no-summary` problems; `commonmark-ext-gfm-tables` in the restart batch | `seon.ref-test/a-section-doc-is-its-lead-and-headings-below` (`#git-words` at doc: `:seon.ref/lead` text, `:seon.ref/below` with tokens, no whole `:seon.ref/text`); `seon.ref-test/a-section-opening-with-a-list-has-no-lead` (TASK-CHAIN `#2-flow` at one backtick → `:no-lead` on that path); `seon.ref-test/front-matter-is-not-a-heading` extended: the whole-file section's text starts after the closing `---`; `seon.ref-test/a-var-with-no-docstring-cited-for-its-summary-is-a-problem-on-the-var` | one parse per content digest; O(H) fold; the extension adds a `TableBlock` node type, no reparse | Sonnet, after S3d-1b (same file) |
| S3d-3 | Schema keys: `seon.db/attribute-holders` public, contracted, capturing read evidence; `seon.ref/schema-documentation` adds `:returns`; `my.program/source` of a keyword adds `:takes`, `:guards`, `:members`, `:referenced-by`, `:writes`, datom count; delete `usage`'s join and `names-shown`, `key-data`'s join; `producer` reads `:returns` | `seon.db-test/attribute-holders-follow-logical-membership-with-read-evidence`: a vector attribute answers the logical owner, an empty seek records evidence, adding a matching arity invalidates it, an unrelated write preserves it; `seon.ref-test/a-schema-key-doc-names-who-returns-it`: `(:returns (doc :my.edit/parse-refused-error))` holds `seon.edit/single-form` and the doc has no `:takes`; `seon.program-test/a-schema-key-source-separates-takers-guards-and-members`: exact three sets for `:seon.ref/path` (5 / 0 / 0) and `:seon.ref/section` (0 / 2 / 0, 7 members); a `seon.profile/measure` assertion: `(source :seon.ref/section)` under 5 ms and 5 MB on a quiet JVM (prototype 0.54–0.71 ms / 0.43–0.99 MB before evidence) | O((U+1) log N) seeks per role, proportional to the arities naming the key; no whole-program work | Opus (`seon.db`), then Sonnet |
| S3d-4 | Deftests, tasks, namespaces: `brief` adds a deftest's status and the task sentence; `documentation-value` and `source` take `:seon.test/sym` and `:seon.task/sym` (sharing `my.task/current`, which takes a named task; proofs from the field after S2c, `seon.task/obligations` until then); namespace code = declarations with their doc values; delete the test, task and namespace render tiers | `seon.ref-test/a-deftest-brief-carries-its-status` (`:seon.render.test/state` present, or the typed unknown); `seon.ref-test/a-task-brief-is-one-sentence` (task A's brief under 60 tokens; 398 today); `my.task-test/current-reads-a-named-task-at-its-doc-level` (Problem lead, instructions, proofs with status; no `next`, no whole plan); `seon.ref-test/a-namespace-code-reference-lists-each-declaration-with-its-doc` | one recorded-result read per test; O(P) per namespace | Sonnet, after S3d-1b |
| S3d-5 | Help cites instead of restating (verbs at doc level, the rest bare), and the end-to-end measure on task A | `seon.bootstrap-test/every-reference-in-help-resolves` extended: no `:no-lead`/`:no-summary` problem in help; `seon.context-test/a-task-authored-at-three-levels-shows-its-owner-and-proof-whole`: for task A's block (owner ```` ``` ````, proof ```` ``` ````, `seon.sci.reader/read` `` ` ``, `seon.turn` bare) the owner's `:source` and the proof's source are whole (no elision), no library Var carries `my.program/callers` operations, elisions fall from 91, and a `seon.profile/measure` on `seon.context/model-context`: ms and MB not above parent | help's render is cached by its digest and what it read; block units render once | Sonnet |
| S3d-6 | (was S3d-2) Context section and history reuse, verbatim, plus: `seon.ref/document` takes the held parsed document so a cache hit does not re-hash O(B) text (Astra) | as the old S3d-2 row: measured on target HEAD first; then a `seon.profile/measure` assertion that a second turn's `model-context` after one new evaluation costs in proportion to that unit, with the raw block's tokens and the elision count before/after; `seon.ref-test/a-reference-level-does-no-work-on-an-unrelated-definition-change` | per unit changed | Opus (after the errors cut's E4a) |

S3d-1a and S3d-1b are file-disjoint and run in parallel after S1a's commit; S3d-3 is file-disjoint from both
(`seon.db`, `my.program`, `seon.render.schema`) and runs beside them. S3d-2 and S3d-4 follow S3d-1b. S3d-5 follows
all of them. Each commit updates the §7 status column.

## 6. Narrow check

**(a) Every live-system claim the hybrid relies on, and where it reproduces.**

| Claim | Evidence |
|---|---|
| The levels are not implemented; depth is `:seon.render/distance` in five renders | dig §0 (V); read again in `seon.render.ns/node-distance`, `seon.render.test/render-ai`'s `near?`, `seon.render.schema/listed?`, `seon.context/must-read-ai` |
| The delimiter width is readable from the `Code` node's source span (1 / 3 / 2) | Opus probe, JVM read-only; Astra probe, live JVM; independent and identical |
| `doc` omits the named schemas' descriptions; `documentation-schemas` computes key→form and discards it | dig §3 (V); read in `seon.sci.lookup/agent-documentation-contract` |
| `doc`'s docstring and arglists come from the SCI Var; the row holds `:seon.fn/doc` and `:seon.fn/arglists` | read in `function-doc-map` and `seon.fn.edn` (the arglists attribute); JVM-mode `doc` without a context UNVERIFIED by run |
| Function token sizes per level | Opus and Astra agree within print-profile differences (e.g. `seon.ref/section` 70 / 75→214 / 905 vs 70 / 174 / 886) |
| The arity-ref Datalog join scans the arities population; `attribute-holders` composition is sub-millisecond | Astra: `datahike.query/explain` (est. 32,533 rows), 20–60 ms / 29–65 MB vs 0.54–0.71 ms / 0.43–0.99 MB; Opus independently 154 ms / 262 MB for 9 keys. Not re-run; the S3d-3 proof re-measures with evidence |
| Returners are few, takers of a binding schema are the program (897) | Opus's counts; not re-run |
| Namespace `doc` listed ~106 core Vars | Opus and Astra independently; fixed in c5a8cd8eb, in HEAD (`git merge-base --is-ancestor`); live archive de1f48c23 predates it |
| No tables extension; a table parses as a `Paragraph` | read in `seon.ref/parsed`'s parser builder and `deps.edn` (0.24.0, front-matter and heading-anchor only). CommonMark's core grammar has no tables, so the node is a `Paragraph` (library fact, not re-run) |
| `resolve-path` drops `:text`/`:below`/`:tokens`; `section` leaves the closing `---` in a whole-file text | dig §4 (V); Astra's measured 10,639/14,953-token middle levels from the reparse; read in `seon.ref/section`'s target line computation |
| Lead compliance | Opus's CommonMark count (647 of 5,159); this note's string estimate (454 of 2,068 living); neither is the check |
| Help resolves through `seon.ref/references` and is a Markdown file | read in `seon.bootstrap/help-value` (f87629f9b, 0ad513f26); `(help)` 2,069 tokens from doc-view-measure |
| Task A's first block: 10.9k raw, 6.0k shown, owner cut to 3 lines, 10 library Vars with operations | context-sanity.md; authored-level estimates ~1k (Opus) / ~902 (Astra), unmeasured end to end until S3d-5 |

Unmeasured and said so: the schema doc level with `:returns` only; the task doc level with instructions; help after
the rewrite; the block after all phases (S3d-5's proof measures it).

**(b) Every deletion is in the phase that converts its last caller.**

| Deleted | Last callers | Phase |
|---|---|---|
| `seon.render.ns/function-ai` tiers, `node-distance`, `source-lines`, `must-read-ai`'s distance, `inner`'s callees | `seon.context/must-read-ai`, `seon.ref/resolve-name` | S3d-1b |
| `resolve-path`'s dissoc, `section`'s re-split and per-child `section-end` | `seon.ref/references`, `my.fs/section`, `seon.bootstrap/help-section` | S3d-2 |
| `seon.render.schema/usage`'s join, `names-shown`, `listed?`/`selection` tier; `key-data`'s join; `producer`'s query | `seon.render.schema/render-ai`, `render-form`, `my.program/reads-key`, `my.program/search` (`key-match`) | S3d-3 |
| `seon.render.test/render-ai`'s `near?`, `seon.render.ns/render-ai` and `task-ai` tiers; `brief`'s first-line rule | `seon.context/task-sections` (proofs), `seon.render/render-call` units of those kinds | S3d-4 |
| §3a's two-tier wording, the old S3d-1 proof | the card | the replacement text below, applied by root |

**(c) Every phase names a red proof that resolves to a deftest.** All are to be written; none exists at HEAD (`grep`
over `test/` for `a-referenced-function-shows-its-doc-view` and `a-code-span-carries-its-reference-level` finds
nothing; the others are new names in existing namespaces: `seon.ref-test`, `seon.context-test`,
`seon.sci.documentation-test`, `seon.db-test`, `seon.program-test`, `my.task-test`, `seon.bootstrap-test`; the
extended ones, `front-matter-is-not-a-heading` and `every-reference-in-help-resolves`, exist). Each assertion is on
data (a key, a set, a count, a measured ms), never on printed text.

## 7. Owner questions

1. **A schema key's doc level.** (a) form, description and `:returns` (who produces it), takers one `source` away:
   **recommended**, every measured key stays under ~300 tokens and the data-flow question is answered; (b) both lists
   at doc (Opus): `:seon.db/database-value` is 9,004 tokens at one backtick; (c) form and description only (your note,
   Astra): one more call for every key an agent must build.
2. **Links and double backticks.** Links: (a) doc level, **recommended** (an explicit citation; 70–170 tokens for a
   section; help can cite guides instead of restating them); (b) summary (Opus: a link is prose). Double backticks:
   (a) a code span, not a reference, **recommended**; (b) doc (Opus).
3. **The 454 living-document sections with no lead** (estimate). (a) fix each when first cited at summary or doc level,
   **recommended** (no sweep, the `:no-lead` problem names the section); (b) one Sonnet sweep of the living documents
   now, before plans cite them; (c) also report `:no-lead` as a finding beside the value when `my.edit/exact!` writes
   a `.md` file.
4. **`commonmark-ext-gfm-tables` 0.24.0 in root's next restart batch**, so a table is never a lead. (a) add it,
   **recommended** (same pinned family as the two extensions already used; without it the lead rule takes a table row
   as a summary); (b) accept tables as leads and let the problem show up as ugly summaries.

## Replacement text for TASK-CHAIN (root applies after the owner reads this)

Replace §3a from "**What a reference shows: its doc view, the full form one evaluation away.**" through the end of
the "**The author picks the depth…**" paragraph (the paragraph ending "…and elisions to 30.") with the block below;
the preceding paragraphs of §3a (`update!`, `my.edit/text`, help) and the following "**Context assembly costs what
changed.**" paragraph stay as they are. Then replace the two S3d rows of §7 with the seven rows below, and change the
vocabulary row "summary, doc, code (reference levels)" to say "installed in S3d-1b".

````markdown
**What a reference shows: three levels, each the value of one REPL function (owner 2026-10-14).** A reference in
prose (a plan, instructions, help, a docstring, a message) shows one of three levels, chosen by its author with the
delimiter, and each level is exactly the value an agent gets by typing the same thing at the REPL. Nothing else
decides depth: not graph distance, not the kind, not the size. The value printer is the only size limit (one budget
per evaluation, elision forms). Levels are for references; a value the REPL returns keeps its declared render.

| Level | Written as | The question it answers | The value |
|---|---|---|---|
| summary | bare `ns/name`, a prose word that resolves | what is it, do I look further? | `seon.ref/brief`: name, first sentence, status for a deftest, read form |
| doc | `` `ns/name` ``, `` `:ns/key` ``, `` `docs/x.md#a` ``, a Markdown link | how do I call it, build it, read it? | `(doc x)`: `seon.sci.lookup/documentation-value` |
| code | ```` ```ns/name``` ```` inline (three or more backticks) | how does it work, where does it sit? | `(source x)`: `my.program/source`; a document through `seon.ref/section` |

| Kind | summary | doc | code |
|---|---|---|---|
| Function, library Var | docstring's first sentence | docstring parts, agent arglists, contract, `:schemas` (each schema the contract names → its description, else its form; cited once, `seon.ref/cite-once`) | exact stored source and contract; callers and tests-reaching are operations, never inlined |
| Schema key, attribute, error type, dial | description, else the form | form, description, `:returns` (the functions whose output names it, by use) | + `:takes`, `:guards`, `:members` described one level, `:referenced-by`, `:writes`, datom count (through `seon.db/attribute-holders`, never the arities join) |
| Namespace | docstring sentence | docstring and its own publics | each declaration with its doc value |
| Deftest | name and latest recorded status, or typed unknown | + the tasks it proves, its analyzed calls, when red the last failing `:expected`/`:actual` | exact source and the same |
| Task | name and the first sentence of the plan's Problem lead | the Problem lead paragraph, the instructions, the proofs with status | whole plan, instructions, proofs with status, `next` |
| Document section, file | heading, lead's first sentence, tokens | lead paragraph, headings below with anchors and tokens | whole section or file (`(source "path#anchor")`, also `my.fs/section`) |
| Non-Markdown file | path, pin, tokens | the same | whole file |

Rules: only a fully qualified name is a reference; an alias form is code; two backticks wrap a literal and are a code
span, not a reference; a line starting with three backticks is a fenced block, never a reference; an unresolved
reference is a problem on whatever cites it. The width is read from the `Code` node's source span
(`seon.ref/document`), and `seon.ref/candidate` carries `:seon.ref/level`. The resolver chooses the level before it
acquires anything. In one shown unit a name appears once, at the strongest level requested, where first mentioned;
later mentions are the name; a nested citation never promotes. A referent's nested references are only the schemas
its contract names.

**The lead rule.** A section cited for its summary or doc opens with a lead: one CommonMark `Paragraph` that is the
heading's own next block (tables parse as `TableBlock`, `commonmark-ext-gfm-tables`). A heading followed by a
subheading has no lead and its doc is its heading tree. A docstring is a document with no heading: its lead is its
first top-level paragraph. A summary- or doc-level citation of a section with no lead answers
`:seon.ref/problem-kind :no-lead` on the document; of a var with no docstring, `:no-summary` on the var; code-level
citations need neither. No sweep: a section is fixed when first cited. Research: `reflevels-hybrid.md`.

This replaces the graph-distance tiers (`seon.render.ns/function-ai` distance 0 a name, 1 and more the whole source;
the same in the test, schema, namespace and task renders), `seon.render.schema/names-shown`, `seon.ref/inner`'s
callees and `seon.render.ns/source-lines`. Measured (`seon.ai.tokens/estimate`, two independent probes agreeing):
`seon.ref/section` 70 / 214 / 905 tokens by level; `seon.edit/single-form` 35 / 72 / 373; a schema key's code value
0.54–0.71 ms through `attribute-holders` against 20–60 ms for the join; task A's first block, authored at three
levels, about 1k tokens with the owner and the proof whole, against 10.9k raw and 6.0k shown today.
````

````markdown
| S3d-1a | `doc` describes each schema its contract names (`:schemas`) and reads the program row's doc and arglists; `seon.render.schema/description` public | `seon.sci.documentation-test/doc-describes-each-schema-its-contract-names`: `(get-in (doc seon.ref/section) [:schemas :seon.ref/section :description])` is a string, no `:source`, the same value in JVM mode with no SCI context | Sonnet (parallel with S1b after the restart batch) | — |
| S3d-1b | The level is read and dispatched (§3a): `:seon.ref/level` from the delimiter width; `seon.ref/references` resolves through `brief` / `doc` / `source`; strongest first occurrence wins; the function tiers, `inner`'s callees, `source-lines` and `must-read-ai`'s distance deleted | `seon.ref-test/a-code-span-carries-its-reference-level` (bare → `:summary`, one backtick → `:doc`, three → `:code`, two and a fence → no candidate); `seon.context-test/a-referenced-function-shows-its-doc-view`: in one plan a bare name shows its first sentence only, a one-backtick name's entry equals `(doc x)`'s value, a three-backtick name's entry holds the whole `:source`, a fence stays code, a name at two levels appears once at the deeper | Opus (after S1a) | — |
| S3d-2 | Documents and docstrings: per-heading lead, top-level first paragraph, one heading fold, `section` keeps `:tokens`/`:below` and starts after the front matter, `doc`/`source` on a path string, `:no-lead`/`:no-summary`; `commonmark-ext-gfm-tables` in the restart batch | `seon.ref-test/a-section-doc-is-its-lead-and-headings-below`; `seon.ref-test/a-section-opening-with-a-list-has-no-lead`; `seon.ref-test/front-matter-is-not-a-heading` extended; `seon.ref-test/a-var-with-no-docstring-cited-for-its-summary-is-a-problem-on-the-var` | Sonnet (after S3d-1b) | — |
| S3d-3 | Schema keys through public `seon.db/attribute-holders` with read evidence: `:returns` at doc; takes, guards, members, referenced-by, writes, datoms at code; `usage`'s join, `names-shown`, `key-data`'s join deleted | `seon.db-test/attribute-holders-follow-logical-membership-with-read-evidence`; `seon.ref-test/a-schema-key-doc-names-who-returns-it`; `seon.program-test/a-schema-key-source-separates-takers-guards-and-members`; `seon.profile/measure`: `(source :seon.ref/section)` under 5 ms and 5 MB | Opus then Sonnet (file-disjoint from S3d-1) | — |
| S3d-4 | Deftests, tasks and namespaces at three levels; `my.task/current` on a named task; the test, task and namespace render tiers deleted | `seon.ref-test/a-deftest-brief-carries-its-status`; `seon.ref-test/a-task-brief-is-one-sentence` (under 60 tokens; 398 today); `my.task-test/current-reads-a-named-task-at-its-doc-level`; `seon.ref-test/a-namespace-code-reference-lists-each-declaration-with-its-doc` | Sonnet (after S3d-1b) | — |
| S3d-5 | Help cites instead of restating (verbs at doc level, the rest bare); the end-to-end measure on task A | `seon.bootstrap-test/every-reference-in-help-resolves` extended (no `:no-lead`/`:no-summary` in help); `seon.context-test/a-task-authored-at-three-levels-shows-its-owner-and-proof-whole`: owner and proof sources whole, no library Var operations, elisions below 91, `model-context` ms and MB not above parent | Sonnet (after S3d-2, S3d-4) | — |
| S3d-6 | Context section and history reuse (was S3d-2), plus `seon.ref/document` taking the held parse so a hit does not re-hash | measured on target HEAD first; then a `seon.profile/measure` assertion that a second turn's `model-context` after one new evaluation costs in proportion to that unit, with the raw block's tokens and the elision count before/after; `seon.ref-test/a-reference-level-does-no-work-on-an-unrelated-definition-change` | Opus (after the errors cut's E4a) | — |
````
