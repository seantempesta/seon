---
title: "Reference levels: the three most useful levels per kind (Opus proposal)"
type: research
status: proposal; measured at the REPL, nothing installed
date: 2026-10-14
basis: "refactor/agent-platform at 207e7c213; default HEAD-booted, health :faulted (cache 503/512 MB, 16 untested-hot warnings)"
---

# Reference levels: Opus proposal

What exists: TASK-CHAIN §3a fixes the delimiters (bare, `` `x` ``, ```` ```x``` ````) and states that each level is a
REPL function's value. The dig (`docs/research/agent-platform/2026-10-14/dig-reference-levels.md`) found the levels
not implemented: depth is chosen by graph distance in five render functions. Principal approach: each level answers
one question an agent asks, and each level's value comes from the REPL function that already answers that question.
That function is extended at its owner and called by the resolver. No new renderer is added.

| Level | Delimiter | The agent's question | Function (one for the REPL and for references) |
|---|---|---|---|
| summary | bare name, Markdown link | What is it, and do I need to look further? | `seon.ref/brief` |
| doc | `` `x` `` (one or two backticks) | How do I call it or build it: what goes in, what comes out? | `doc`, which is `seon.sci.lookup/documentation-value` and, for a keyword, `seon.ref/schema-documentation` |
| code | ```` ```x``` ```` | How does it work, and where does it sit in the data flow? | `source`, which is `my.program/source`, and `my.fs/section` for documents |

The rule: the doc level is the interface, so it covers every schema the interface names, each described once. The code
level is the definition plus the definition's place in the graph. Graph neighbours stay at code level as offered
operations (forms the agent may run), never as inlined values. One exception: for a schema key, who produces and who
consumes it IS its interface (CLAUDE.md law 1), so their names appear at the doc level.

## Measured: per kind, three referents or more

Tokens are `seon.ai.tokens/estimate` of the `pr-str` value (3.2 characters per token). I computed them in SCI mode as
agent `reflevels-opus` on its own branch (`:agent-a7a39120a3ca`, now retired), with existing functions and scratch
defns for the parts that are missing. Times are per form, warm.

### Function and library Var

| Referent | summary (`brief`) | doc today (`doc`) | doc proposed (+ named schemas described) | code (`source`) |
|---|---|---|---|---|
| `seon.edit/single-form` | 35 | 32 | 72 (1 schema) | 373 |
| `seon.sci.reader/read` | 60 | 249 | 294 (1) | 909 |
| `seon.ref/section` | 70 | 75 | 214 (3) | 905 |
| `seon.ref/brief` | 75 | 89 | 297 (5) | 835 |
| `my.task/refine!` | 62 | 604 | 1,081 (8) | 1,728 |
| `malli.core/validate` (library) | 48 | 71 | 75 (0) | 205 |

- **summary**: `seon.ref/brief` as it is: name, first sentence, read form.
- **doc**: today's `doc` value plus `:schemas`, a map from each schema key the contract names to its `:description`,
  or to its form when it has no description. `seon.sci.lookup/documentation-schemas` already builds exactly this map
  (key to form) inside `agent-documentation-contract` and then discards it. The change adds no new reads.
- **code**: `my.program/source` as it is. `(my.program/callers 'f)` and `(my.program/tests-reaching 'f)` stay as
  operations. Inlined they cost 126–353 tokens each, mostly `:seon.program/identity`/`:basis-t`/`:unknown` metadata,
  so they stay one evaluation away.
- 2,229 of 4,009 contracted arities name an output schema (56%), and 2,832 name an input schema. Where the contract
  is an anonymous form, the doc level shows the form itself, and nothing more can be shown.

### Schema key, attribute, error type

| Referent | summary (description, else form) | doc proposed | code proposed | takes / returns / members |
|---|---|---|---|---|
| `:my.edit/parse-refused-error` | 17 | 98 | 801 | 0 / 6 / 2 |
| `:seon.ref/section` | 43 | 190 | 439 | 0 / 2 / 7 |
| `:seon.ref/document` | 69 | 259 | 585 | 0 / 2 / 7 |
| `:seon.task/conflict-error` | 16 | 107 | 448 | 0 / 3 / 4 |
| `:seon.db/database-value` | 15 | 9,004 | 76,498 | 897 / 36 / 0 |

- **summary**: the `:description`, or the form when there is no description (the short forms are their own
  description).
- **doc**: `schema-documentation`'s `{:key :form :description}` plus three name lists:
  - `:returns`: the functions whose output names the key.
  - `:takes`: the functions whose input names the key.
  - `:member-of`: the entity rows that hold the key.
  Each list is ordered by use count (`my.program` `use-count`, `:seon.fn/calls` plus `:seon.test/reach`). When the
  value printer elides a list, the elided tail is the least used part. An attribute such as `:seon.task/intent` that
  no contract names gets only `:member-of` and the writers.
- **code**: the doc value, plus three more members:
  - each member key described one level down (`:seon.schema/references`);
  - each taker and returner as its `brief`;
  - `:writes` (`:seon.fn/writes`) and the datom count, both of which `my.program/reads-key` already computes.

  This is the owner's suggestion ("the full schemas, what functions take it as inputs, what returns it as outputs").
  I moved only the bare names down to the doc level, because they are the data flow.
- Cost: one query over the AVET-indexed `:seon.fn.arity/input-refs|output-refs|guard-refs`, with the attribute kept in
  `:find` so takers and returners stay separate. It took 154 ms for 9 keys, and `:seon.db/database-value` alone has 933
  holders. Allocation was 262 MB, which is too high. The query must go through `seon.db/q`, because a raw `d/q` on
  the keyword ref finds nothing. The work is proportional to the number of arities that name the key, so no
  whole-program work is added per change. `:seon.db/database-value` is the honest outlier: its 9k tokens are its real
  interface, and the printer's budget and elision forms deal with that size.

### Namespace

| Referent | summary | doc today | doc proposed (own publics, names) | code (`dir`) |
|---|---|---|---|---|
| `seon.edit` | 39 | 840 (108 "publics") | ~60 (5 publics) | 1,093 |
| `seon.ref` | 76 | 833 (114) | ~120 (11) | 3,400 |
| `my.task` | 89 | 806 (106) | ~70 (3) | 1,455 |
| `seon.sci.reader` | 41 | 971 (106) | ~50 (3) | 442 |

**Defect found:** `(doc ns)` reports about 106 `:publics` for every namespace. They are clojure.core's own Vars
qualified under the namespace (`seon.sci.reader/*agent*`, `seon.sci.reader/->ArrayChunk`, …). `dir` correctly finds 5,
11, 3 and 3. The producer is `seon.sci.lookup/namespace-documentation`, whose `scoped` term reads
`sci.namespaces/sci-ns-publics*`. Proposed levels:
- summary: `brief`.
- doc: the docstring plus the namespace's own public names.
- code: `(dir ns)`, meaning each public with its summary, arglists and contract, plus the namespace's schemas.

### Deftest

| Referent | summary today | summary proposed | doc proposed | code (`source`) |
|---|---|---|---|---|
| `seon.edit-test/an-odd-map-anywhere-is-not-one-form` | 54 (name, read form) | + latest status | ~90 | 253 |
| `seon.ref-test/a-reference-with-no-path-is-a-missing-file` (doc-view note) | 57 | + status | 89 | 218 |
| doc-view note, 5 deftests | | | 515 total | 1,834 total |

- **summary**: name, latest status (`:green`/`:red`/`:unknown`) and the read form. Status is the one fact that decides
  the agent's next step.
- **doc**: a deftest has no docstring. Its interface is:
  - the tasks it proves (`:seon.task/tasks`, on the entity);
  - the functions it calls (`:seon.fn/calls`, on the entity);
  - its status, and when it is red, the failing assertion's `:expected`/`:actual` from its last stored run.
- **code**: its source. A proof is the specification of done, so an instruction should cite its own proofs with three
  backticks.

### Task

| Referent | summary today | summary proposed | doc proposed | code |
|---|---|---|---|---|
| `seon.edit/implement-an-unreadable-odd-map-is-not-one-form` | 398 (the first *line* of the instructions, which is one long line) | first sentence, ~40 | step, status, the whole intent and instructions, proofs with status | the effective task (`seon.task/effective`), 596 |

- The task summary uses the docstring rule: the first sentence of the first lead paragraph (see Markdown below) of its
  text member (the plan once TASK-CHAIN S1 lands, the intent today), read through `seon.ref/docstring`.
- The doc level is `my.task/current`'s view of one task, without its references expanded.

### Document section and document

| Referent | summary (heading, lead's first sentence, size) | doc (heading, lead, headings below with sizes) | code (`my.fs/section`) |
|---|---|---|---|
| `docs/seon/architecture/vocabulary.md#git-words` | 67 | 123 + below | 887 |
| `docs/seon/schedule/cards/TASK-CHAIN.md#3-the-plan-and-its-references` | 40 | 70 + below | 512 |
| `docs/seon/schedule/cards/TASK-CHAIN.md#3a-agent-verbs-and-what-references-show` | 26 (lead is a teaser) | 26 | 2,039 |
| `docs/seon/schedule/cards/TASK-CHAIN.md#2-flow` | none: opens with a list ("1.") | none | 3,584 |
| `docs/seon/schedule/cards/TASK-CHAIN.md` (whole) | none: no top-level lead | the heading tree | 14,879 |

- `seon.ref/section` already answers `:seon.ref/tokens` and `:seon.ref/below`. Today `seon.ref/resolve-path` drops
  them. It keeps them under the proposal.
- A non-Markdown file (`deps.edn`, `src/a.clj`) has no lead. Its summary is path plus size, and its doc equals its
  summary. Code in a source file should be cited by symbol, not by path.

## Task A: which levels would have found the owner and the fix in the first call

Context-sanity task A spent its budget on 10 library Vars and 4 namespace directories (`seon.turn` alone was 65
rows), while the change target `seon.edit/single-form` was cut to 3 lines. With authored levels, the instructions read:

- ```` ```seon.edit/single-form``` ````: the change target, whole, 373 tokens.
- ```` ```seon.edit-test/an-odd-map-anywhere-is-not-one-form``` ````: the proof, whole, 253 tokens.
- `` `seon.sci.reader/read` ``: the dependency, at doc level, 294 tokens.
- bare `seon.turn`: a parenthetical mention, about 40 tokens.

That is about 1k tokens with nothing cut, against 10.9k raw and 6.0k shown. The agent would have had the owner and
the proof in call 1.

**It still would not have had the fix.** The agent needed to know that a read event carries `:seon.sci.reader/error`
with `:error-kind :odd-map`. But `seon.sci.reader/read`'s contract returns `[:or [:vector :map] :seon.sci.reader/failure]`:
the event is an anonymous `:map`, and `:seon.sci.reader/event` does not exist (0 holders). No level can describe a
schema that has no name. The fix is at the producer: name the event schema, per CLAUDE.md law 1 ("every value has a
named Malli schema at its producer"). This is the strongest argument for the doc level: once schemas are named, doc
is complete. Proposed task: `seon.sci.reader/name-the-read-event-schema`.

## Markdown: the lead-paragraph rule and its check

**Rule.** A section with body text opens with a lead paragraph: a CommonMark `Paragraph` that is the heading's next
sibling. The first sentence of the lead is the section's summary, and the whole lead is its doc level. A heading
followed directly by a subheading is allowed, and its doc level is its heading tree. A plan's `## Problem` lead is the
task's summary, so one rule serves documents, plans, help and docstrings. A docstring is a document with no heading:
its lead is the first top-level paragraph.

**Measured over 863 Markdown files** (`docs/seon/**`, `resources/seon/help.md`): of 5,159 headings,

| What follows the heading | Count | Under the rule |
|---|---|---|
| A paragraph | 3,320 (64%) | complies |
| A subheading | 1,192 | allowed |
| A list, fence or quote (498 bullet lists, 68 ordered lists, 72 fences, 9 quotes) | 647 | non-compliant |

**Check, with no regex.** In `seon.ref/parsed`, record per heading `:seon.ref/lead` (line, end-line), read from the
node's next sibling. Today `:seon.ref/first-paragraph` is the document's first `Paragraph` at any depth: for §2 it
found the paragraph inside list item "1.". It becomes the first top-level paragraph, which is the same rule for a
docstring. When a summary or doc reference cites a section with no lead, `seon.ref/references` reports
`:seon.ref/problem-kind :no-lead` with that path. The problem belongs to the cited section's document, not to the text
that cites it (compare context-sanity defect 4). A code-level citation needs no lead. Writing a lead that actually
summarizes cannot be checked mechanically, and §3a's teaser lead shows the gap.

**Delimiter length, verified.** The dig marked this UNVERIFIED. `seon.ref/parsed` already enables
`IncludeSourceSpans/BLOCKS_AND_INLINES`. A `Code` node's first `SourceSpan` (line, column, length) selects its source
text, and the leading backtick count gives the delimiter. Probe output (JVM read-only, pinned commonmark): `` `x` `` →
1, ```` ```x``` ```` → 3, ```` ``a `tick` x`` ```` → 2. Rule: 3 or more backticks is the code level, 1 or 2 is the doc
level. A two-backtick span almost always wraps a literal that contains a backtick, and that literal is not a name. A
line starting with three backticks is a fence, which commonmark parses as a `FencedCodeBlock` and never as a span.

## What is deleted

- The distance tiers:
  - `seon.render.ns/function-ai` (`node-distance` 0 versus 1+);
  - the tier branches of `seon.render.test/render-ai`, `seon.render.ns/render-ai` and `seon.render.ns/task-ai`;
  - `seon.render.schema/render-ai`'s usage tier, including `seon.render.schema/usage` and its `names-shown` cap of 8.
    The cap is a truncation rule; the value printer is the only size limit.
- The callees in `seon.ref/inner`. The `:seon.fn/calls` half goes. A doc view's nested references are only the schemas
  it names, cited once by `seon.ref/cite-once`. Callees were half of the doc view's tokens (doc-view note).
- The distance-based selection in `seon.context/task-sections`. Each cited name renders at its authored level.
- `my.program/key-data`'s merged contract-refs query, replaced by the one split query. The private
  `my.program/producer` reads the returners from it.
- `seon.ref/brief`'s "first line" rule for tasks, replaced by the first-sentence rule shared with docstrings.

## Phases, each with a red proof

| # | Owner change | Red proof (deftest, assertion on data) | Cost per change |
|---|---|---|---|
| P1 | `seon.ref/parsed` keeps `:seon.ref/ticks` per code span; `seon.ref/candidate` derives `:seon.ref/level` (`:summary`/`:doc`/`:code`) | `seon.ref-test/a-code-span-carries-its-reference-level`, a table: `"x/y"` bare → `:summary`, `` "`x/y`" `` → `:doc`, ```` "```x/y```" ```` → `:code`, a fenced block → no candidate | one subs per span, inside the digest-cached parse |
| P2 | `seon.sci.lookup/function-doc-map` adds `:schemas` (description, else form) from the `documentation-schemas` result it already computes | `seon.sci.lookup-test/doc-of-a-function-describes-each-named-schema`: `(contains? (:schemas (doc seon.ref/section)) :seon.ref/section)` and no `:source` key | 0 new reads |
| P3 | `seon.sci.lookup/namespace-documentation` lists only the namespace's own publics | `seon.sci.lookup-test/namespace-doc-lists-only-its-own-publics`: every `(:publics (doc seon.sci.reader))` symbol is a `:seon.fn/sym` of that namespace, and `'seon.sci.reader/*agent*` is absent | removes about 100 Vars per call |
| P4 | `seon.ref/schema-documentation` adds `:returns`/`:takes`/`:member-of`, ordered by use; `my.program/source` of a keyword adds members, briefs, writers and the datom count | `seon.ref-test/a-schema-key-doc-names-who-takes-and-returns-it`: `(:returns (doc :my.edit/parse-refused-error))` contains `seon.edit/single-form`; `(:returns (doc :seon.ref/section))` contains `seon.ref/section` | one AVET query, proportional to the holders; budget it with `seon.profile/measure` (154 ms and 262 MB for 9 keys today: fix that allocation before committing) |
| P5 | per-heading `:seon.ref/lead`; `resolve-path` keeps `:tokens`/`:below`; a section's summary and doc come from its lead; the `:no-lead` problem | `seon.ref-test/a-section-doc-is-its-lead-and-headings-below` (vocabulary `#git-words` doc holds its lead and `:seon.ref/tokens` 887, no `:seon.ref/text`); `seon.ref-test/a-section-opening-with-a-list-has-no-lead` (TASK-CHAIN `#2-flow` cited with one backtick → `:no-lead`) | per parse, cached by digest |
| P6 | the task and deftest summaries: first sentence; deftest status; the deftest doc (tasks, calls, last failure) | `seon.ref-test/a-task-brief-is-one-sentence` (the task A brief is under 60 tokens, 398 today); `seon.ref-test/a-deftest-brief-carries-its-status` | one status read per test |
| P7 | `seon.ref/references` calls the level's function (`brief` / `doc` / `source`); cite-once keeps the deepest level per name; the tiers and callees above are deleted in the same commit | `seon.context-test/a-cited-name-shows-at-its-authored-level`: in task A's block, ```` ```seon.edit/single-form``` ```` holds the whole `:source`; `` `seon.sci.reader/read` `` holds `:schemas` and no source; bare `seon.turn` has no `:seon.repl/rows`. Plus a `seon.profile/measure` on `seon.context/model-context` for that agent: raw block tokens and elisions fall, ms and MB not above parent | net deletion; renders cached as today |

P2 and P3 are independent and small (Sonnet). P1 and P5 share `seon.ref` and run one after the other. P7 depends on
P1, P2 and P5, and is Opus work because it touches context assembly.

## Findings during the probe (to file)

1. **A repeating recorded error on my branch.** It appeared on every reply after `(def L (mapv levels fns))` and grew
   to 25 occurrences: `seon.sci.eval/install-row!` reported "Cannot interpret user/L: seon.ref/brief refused argument
   0 `database` … got a map". The cause: `user/db`, def'd to `(seon.db/db)`, is reinterpreted at install as a map.
   - An install re-runs a def's init against a different value of its dependency, so the error is delivered on
     unrelated replies.
   - Removing the def failed: `(my.program/ns-unmap! 'user 'L)` refuses because `:my.program/context` is not filled
     by schema name. CLAUDE.md names `ns-unmap!` as the agent's delete.
2. **Namespace `doc` publics** (P3 above): about 800 tokens of clojure.core Vars per namespace doc.
3. **Over one second.** The first takers query took a 1,048 ms request (evaluate 739 ms) with a 3.6 GB step
   allocation. Later probes allocated 0.5–1.3 GB (`:large-allocation` findings), on an arity-ref query.
4. **`:seon.task/plan` has no schema entity on default.** `(doc :seon.task/plan)` returns documentation-unavailable,
   although TASK-CHAIN and this round's brief cite it. Either S1 is not installed, or the key is not published.
5. **`(my.fs/section "resources/seon/help.md")` returned no `:seon.ref/text`**, and `#your-task` reported
   section-missing. UNVERIFIED: why. The resolver may not treat `resources/` as a base.
6. **`seon.edit/single-form` has no docstring**, so its summary is its name alone. The fix belongs at the docstring.

## Open questions for the owner

1. **Schema key, doc level.** I put the names of who takes and who returns the key at the doc level, and their briefs
   and the members described at the code level. Your note put them all at the code level. Measured: doc 98–259
   tokens, code 439–801 for ordinary keys. Is the data flow part of a key's interface?
2. **Two backticks.** Read as the doc level (my rule), or as no reference?
3. **Markdown links.** Read a link as the summary level (my rule, the link is prose), or as the doc level?
4. **The lead rule's scope.** 647 sections open with a list, fence or quote today. Should the `:no-lead` problem fire
   only when such a section is cited at summary or doc level (my rule)? Or should a lint over every changed `.md` run
   as well?
5. **Unnamed schemas.** 44% of contracted arities name no output schema, so their doc level cannot describe what they
   return. Should `doc` report an anonymous `:map` output as a finding on that var, the way a missing contract is
   reported?
6. **One name cited at two levels in one block.** Show it once, at the deepest level (my rule)?
