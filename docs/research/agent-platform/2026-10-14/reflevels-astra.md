---
title: Three reference levels — independent Astra proposal
type: research
status: independent design; measured SCI prototypes; no implementation installed
date: 2026-10-14
basis: dig 207e7c213; live archive de1f48c23ce0da42f5961bbc44a2dec5a03e5ab6
---

# Three reference levels — independent Astra proposal

Seon already parses prose with CommonMark, stores contracts and their directional schema references, and answers documentation at the REPL. Resolve an identity once, then call the same summary, `doc`, or `source` owner the REPL calls; select the level before acquiring its data. Delete distance-selected content and automatic callee expansion.

Design only; no implementation is installed. Basis: dig commit `207e7c213`, TASK-CHAIN §3–3a, context-sanity and doc-view-measure, checked against source and the live system on 2026-10-14. I did not read the other proposal. The measured runtime was archive `de1f48c23ce0da42f5961bbc44a2dec5a03e5ab6`, not checkout HEAD. All prototypes ran as SCI agent `reflevels-astra`, branch `:agent-5627642d6933`, namespace `scratch.reflevels-astra`. JVM calls were read-only dependency inspection as root, with the explicit default connection.

## Recommendation and its limits

The levels answer three questions: **what is this**, **how do I use it**, and **what exactly defines it**. The author chooses, rather than graph distance. The third level does not mean a transitive dump of everything related to the name. Vocabulary commit `76985e26b` names these levels **summary, doc, code**; “source” in this note names the shared `(source x)` value/owner, including table columns measuring it, not a fourth level. Use `:summary`, `:doc`, `:code` in the occurrence data.

Keep the card's function ladder. Give document references an opening paragraph in their middle level: a heading tree alone cannot tell an agent whether a leaf section contains the rule it needs. Give schema source its declared form, direct member definitions, and separately labelled input/output/guard users. Those lists are declarations, not a claim that every named function actually produces a key on every execution.

The critical cost finding changes the implementation premise: **an indexed schema attribute does not make the current join output-sensitive**. The live Datahike plan scans the arities population. There is already an indexed traversal at `seon.db/attribute-holders`; promote that owner, including read evidence, rather than add a schema usage index. Do not ship level three over the current join and call it cheap.

Three limitations of the measurements are material:

- The scratch indexed traversal calls a private database function which does not capture its own read evidence. Its latency demonstrates the algorithm, not a cache-safe implementation. The production phase must add evidence at that owner and repeat the measurement.
- Namespace `doc` is currently polluted by core Vars after program changes. Stored declarations provide the prototype's finite population, but the product fix belongs to SCI namespace installation, not a display filter.
- Markdown prototypes still read the whole file and compose existing functions more than once. The target parses one changed file once and retains source spans; the measured numbers are not a claim that current summary reads already cost only their output.

## Shared ownership and value contracts

Dependency source basis: Malli `dd13ff81139159778edb0a67b491eccc0baa991d`, Datahike `c9e57c6fb65365d5ce92b59854fb02ea59054fe7`, SCI `eb3290d5c5ed72133ae1947492ecd324413868ed`, rewrite-clj `60782e501aaf312cb90c9ff0bee05d5da5125563`, Clojure `3bc2b3e91fdff462620d84bbdfd40379d9c562e5`, CommonMark 0.24.0. Relevant dependency seams are `malli.core/form`, `malli.core/properties`, `malli.core/children` and `malli.core/walk` over one held schema; `datahike.query/explain` and Datahike's indexed search over one held database; SCI's namespace public-Var selection and installed source macro. Malli traversal costs the visited contract nodes, not the registry; index read costs and recomputation are specified below. Seon's stored references already supply the direct named-schema set, so a display need not walk all schemas.

Use three public entrances with the same values for references and the REPL:

| Level | Authored form | One owner |
|---|---|---|
| Summary | bare qualified name/path | Extend `seon.ref/brief` to all supported kinds; expose the same callable function, no second summary renderer |
| Documentation | single-backtick name/path, ordinary Markdown link | `seon.sci.lookup/documentation-value`, extended to tests, tasks and paths, with kind-specific work delegated to its current owner |
| Source | triple-backtick **inline** name/path | `my.program/source`, extended to the remaining kinds; `clojure.repl/source` becomes a data-returning adapter installed by `seon.sci.lookup/install-program-doc!` |

The second and third rows are exact value equality, before printing. The reference's authored name, location, level and problems belong beside the value, not inside a second copy of it. `seon.render.ns/function-ai`, `seon.render.test/render-ai`, `seon.render.schema/render-ai`, `seon.render.ns/task-ai` and `seon.ref/render-doc-ai` compose these owners. A REPL call and an inline reference receive the same supplied database, SCI context and viewer bindings.

Existing named result schemas are the starting point: `:seon.ref/brief`, `:seon.ref/doc-view`, `:seon.sci.eval/schema-doc`, `:seon.sci.eval/namespace-doc`, `:my.program/declaration-source`, `:seon.ref/section` and the test result schemas. Add optional members where meanings remain unchanged. A source keyword currently returns schema-doc; retain its form and description and add member/usage data. Namespace source currently returns declarations; retain those names and add their documentation. Proposed reference-level data is an optional `:seon.ref/view` enum of `:summary`, `:doc`, `:code` on occurrences; it is reader data, not a lifecycle attribute or new entity. The level words are already recorded in vocabulary; register the new occurrence attribute only if the implementation needs it. Expand the authored-reference input at its owner to admit path strings; do not pass strings through symbol parsing.

Do not evaluate operations during rendering. The one value printer owns the one evaluation budget and elisions. Source is one source value, not split into independently clipped lines. Nested contract schemas are cited once by name and description, not expanded recursively. Deduplicate requested names before acquisition: within one shown unit the strongest explicitly requested level wins, at the first occurrence's position; later mentions are names. This prevents a preceding summary from swallowing an author's later source request. Nested schema summaries do not promote explicit requests or trigger a reference graph traversal.

## Levels by kind

Here “full” means the complete selected subject. A referenced function is not its callers; a referenced schema is not its entire transitive graph.

| Kind | Summary | Documentation | Source | Producing owners |
|---|---|---|---|---|
| Function, including a private implementation target | Name, first docstring sentence, read operation; a missing doc remains absent | Whole docstring parts, agent arglists, declared input/output/guard contract, directly named schema descriptions once, supplied bindings and any existing override note | Exact stored source, existing contract/file metadata and source provenance; no automatic callees | `seon.ref/brief`; `seon.sci.lookup/documentation-value`; `my.program/source` |
| Library Var, including macros and constants | Name, summary, pin when known | Declared metadata/arglists/doc; contract only if actually available; never invent one | Source from the pinned classpath/dependency definition and pin, or typed source-unavailable data | Reuse `seon.ref/library-name`, `seon.sci.lookup/stored-source-fn`, `seon.fn.analyzer/unloaded-definition` at their owners; source lookup never loads a library to inspect it |
| Namespace | Name and doc sentence | Full namespace doc and its own public names; preserve private definitions as explicitly addressable names | Public documentation values, lazily acquired; preserve source's existing declaration lists | `seon.sci.lookup/namespace-documentation`, `seon.sci.lookup/program-documentation`, `my.program/namespace-declarations`; compose the function doc owner |
| Schema key; attribute, error type and configuration dial are the same kind | Key and declared description | Exact declared form and description | Same form, immediate named member definitions/descriptions, direct input users, output users, guard users, and schemas directly referring to it; a nested named schema remains a name | `seon.ref/schema-documentation`, public `seon.render.schema/description`, extended `seon.render.schema/usage`; source delegates to these |
| Deftest | Name and latest recorded outcome with basis, or typed unknown | Same evidence plus direct analyzed calls; observed reach separately labelled when stored, never substituted for current proof | Exact test source plus the same evidence/read operation; no host-closure walk merely to display source | Extract the data selection from `seon.render.test/render-ai`; `seon.test/recorded-result`; `my.program/source` |
| Task | Task symbol/name | Problem's opening statement, that task's instructions, proof names with recorded observations | Whole plan, instructions, proofs and next-task name; no recursive next-task expansion or reference expansion of the whole plan | `seon.task/effective`/the installed task read owner; extend `my.task/current` to address a named task and share its selection with `doc`/source |
| Markdown file | Path/title, total estimated tokens, opening sentence | Opening paragraph and immediate child headings with anchors and token sizes | Entire file body, provenance/pin and child headings | Extend `seon.ref/document` and `seon.ref/section`; share with `my.fs/section` and `doc`/source |
| Markdown section | Path/anchor/title, selected section's tokens, opening sentence | Its opening paragraph plus immediate child headings | Entire selected section through the next peer/ancestor heading; no siblings | Same document owners |
| Pinned dependency document/section | Same Markdown levels, carrying its actual pin | Same | Same | `seon.ref/locate` and the same document owners; no special rendering family |
| Non-Markdown file | Path, pin if any, token size | Same metadata and the source read operation; no invented summary of code/EDN | Entire file as text from its existing filesystem owner | `seon.ref/locate`, `my.fs/section`/file owner; never parse code as Markdown to find references |
| Unresolved explicit reference | Authored name and typed problem | Same problem | Same problem | `seon.ref/answer`/problem owner; do not run `my.program/nearest`'s whole-program fuzzy scan as a hidden part of a failed source reference |

Task measurements below omit instructions at the middle level to isolate the card's proposed Problem-plus-proofs value. That is not sufficient for an implementation task: task A's instructions specify the exact owner and change. The recommended middle level includes its instructions, or relies on the identical instructions already held in the surrounding task block rather than copying them. No claim of a measured token count for that additive version is made.

For a configuration dial, “source” describes the schema/default and declarative consumers. It does not read every cluster's live configuration. For an error type it describes the variant and its contract users; occurrences/history remain explicit tools. For a test, “latest recorded pass” is historical evidence, not a newly recomputed green verdict. No level runs tests or traverses the current whole call graph to turn history into current health.

## Measurements: three real referents per kind

Each cell is **estimated tokens / acquisition ms / print ms**. All values were built in the lane's SCI context. Timing used `System/nanoTime`; selected comparisons also used `seon.profile/measure`. Tokens are `seon.ai.tokens/estimate` of `seon.render.value/text` with a fixed measurement profile: 100,000-token budget, depth 32, ceiling 983,040. This is a diagnostic print profile, not a proposed product dial. No `pr-str`, additional truncation or second printer was used. Source values in this table are maps/strings printed structurally, not their source-only render, so these are comparable data-shape costs, not predicted exact prompt sizes. Single samples on a shared JVM; do not treat small differences as stable speedups.

The function/schema prototypes retain existing missing descriptions as empty description maps or absent keys. Production should report that absence as a problem on the declaration, not claim a description was supplied. Function source has no recursive doc-schema expansion. The schema measurements include direct member definitions and directional users, but not the additional referenced-by list in the recommendation; that list's incremental cost is its own indexed matches and printed names, still to measure.

| Function / library Var | Summary | Doc | Source |
|---|---:|---:|---:|
| `seon.edit/single-form` | 34 / .153 / .470 | 47 / 1.119 / .477 | 371 / .255 / .623 |
| `seon.ref/section` | 70 / .136 / .248 | 174 / 1.071 / .671 | 886 / .207 / .700 |
| `seon.sci.reader/read` | 59 / .104 / .211 | 281 / 1.347 / 1.085 | 891 / .244 / .588 |
| `malli.core/validate` | 49 / .396 / .386 | 72 / .250 / .325 | 105 / 6.367 / .378 |
| `clojure.core/count` | 45 / .293 / .238 | 142 / .166 / .341 | 95 / 1.554 / .305 |
| `rewrite-clj.zip/of-string` | 78 / .539 / .277 | 188 / .139 / .305 | 225 / 1.533 / .245 |

The absence of a summary for `seon.edit/single-form` is real: its source has no docstring. A level cannot manufacture one. Library source may be shorter than doc; levels are purposes, not monotonic token quotas.

| Namespace, using indexed stored declarations | Summary | Doc | Source/public docs |
|---|---:|---:|---:|
| `seon.edit` | 40 / .162 / .390 | 110 / .483 / .428 | 862 / 4.724 / 4.871 |
| `seon.ref` | 77 / .232 / .331 | 139 / .647 / .509 | 2643 / 9.569 / 13.848 |
| `my.fs` | 40 / .317 / .325 | 45 / .247 / .186 | 1086 / 4.908 / 4.107 |

The uncorrected live `(doc seon.edit)` grew to 895 tokens and source/public-doc composition to 12,673 after SCI namespace pollution. For `seon.ref` those were 889 and 14,413. These are defects, not representative namespace sizes.

| Schema, using indexed owner composition | Summary | Doc | Source |
|---|---:|---:|---:|
| `:seon.ref/path` | 30 / .148 / .359 | 68 / .090 / .377 | 118 / .269 / .571 |
| `:seon.ref/section` | 44 / .125 / .248 | 183 / .102 / 1.093 | 558 / .771 / 2.763 |
| `:seon.ref/section-error` | 9 / .134 / .155 | 86 / .100 / .558 | 974 / .666 / 6.182 |

Counts respectively: direct member definitions **0/7/5**; input users **5/0/0**; output users **0/2/3**; guard users **0/0/0**. `:seon.ref/section-error` has no declared description. Its full value is larger because one immediate member is the full error base; recursively expanding those members would multiply cost without helping the initial decision.

| Deftest | Summary | Doc | Source |
|---|---:|---:|---:|
| `seon.ref-test/fenced-code-and-prose-spans-are-not-references` | 48 / .071 / .347 | 99 / .141 / .243 | 141 / 5.264 / .405 |
| `seon.ref-test/a-reference-with-no-path-is-a-missing-file` | 45 / .062 / .156 | 102 / .115 / .247 | 178 / .220 / .290 |
| `seon.edit-test/an-odd-map-anywhere-is-not-one-form` | 41 / .032 / .129 | 86 / .077 / .196 | 250 / .185 / .278 |

The samples had no admitted result on this branch: the unknown identifier was retained. Source-column measurements are the installed source map alone; adding the summary observation is recommended but unmeasured. The doc column labels `:seon.fn/calls` as analyzed calls, not observed transitive reach.

| Task, installed effective read | Summary | Problem + proofs | Whole effective task + proofs |
|---|---:|---:|---:|
| `seon.edit/implement-an-unreadable-odd-map-is-not-one-form` | 42 / .135 / .374 | 249 / 1.378 / .568 | 1061 / 1.021 / .765 |
| `seon.edit/an-unreadable-odd-map-is-not-one-form` | 35 / .053 / .127 | 145 / .596 / .276 | 579 / .578 / .333 |
| `seon.ref/a-reference-with-no-path-is-a-missing-file` | 38 / .054 / .125 | 247 / .859 / .464 | 871 / .556 / .595 |

The runtime still stores `:seon.task/intent`; HEAD's target is `:seon.task/plan`. The task prototype reads the installed effective value and `seon.task/obligations`; it does not silently substitute a nonexistent plan key. The middle cell uses its existing first-sentence rule. The 145-token opening statement is an authoring problem, not a reason to add a character cutoff.

| File / section | Summary | Opening paragraph + headings | Full |
|---|---:|---:|---:|
| README.md | 53 / 1.135 / .448 | 199 / .887 / 1.474 | 1239 / .349 / .650 |
| docs/seon/architecture/vocabulary.md | 71 / 1.381 / .411 | 333 / 1.854 / 1.133 | 10639 / 1.001 / 1.303 |
| docs/seon/schedule/cards/TASK-CHAIN.md | 98 / 1.554 / .356 | 469 / 2.265 / 1.754 | 14953 / 1.373 / 1.414 |
| vocabulary.md#git-words | 116 / 1.060 / .280 | 167 / 1.036 / .369 | 932 / 1.014 / .421 |
| TASK-CHAIN.md#3-the-plan-and-its-references | 103 / 1.285 / .292 | 128 / 1.273 / .323 | 570 / 1.291 / .385 |
| TASK-CHAIN.md#3a-agent-verbs-and-what-references-show | 95 / 1.338 / .270 | 90 / 1.338 / .322 | 2105 / 1.445 / .598 |
| reference-code/malli/README.md | 254 / 3.555 / .458 | 295 / 3.443 / .896 | 38186 / 3.393 / 3.115 |
| reference-code/sci/README.md | 62 / 1.475 / .349 | 611 / 1.396 / 3.657 | 10900 / 1.519 / 4.447 |
| reference-code/datahike/README.md | 123 / 1.086 / .406 | 525 / .781 / 2.181 | 5275 / .848 / 2.510 |

For the three first-party files, a single H1 title is the document container and the middle cell shows its children, not a directory consisting only of that title. The library files do not follow that shape: Malli has two H1s and SCI/Datahike no H1s. Their middle cells retain the root's actual heading level. No convention is imposed on pinned third-party writing. A middle value can be smaller than a summary because field names and quoted inline formatting differ.

An alternative “everything before the first child heading” middle level measured 228/464/877 tokens for the three first-party files, and 932/570/2105 for the three leaf sections. In every leaf it equalled full text. Prefer the opening paragraph: it keeps the level useful without pretending bold introductory labels are headings.

| Non-Markdown file | Summary | Doc metadata | Source |
|---|---:|---:|---:|
| deps.edn | 13 / 1.679 / .241 | 13 / .309 / .161 | 3642 / .293 / .648 |
| resources/seon/schemas/seon.fn.arity.edn | 23 / .887 / .138 | 23 / .154 / .135 | 611 / .143 / .335 |
| reference-code/malli/src/malli/core.cljc | 54 / 18.667 / .433 | 54 / 1.623 / .289 | 47386 / 1.503 / 3.180 |

Those metadata samples omit the offered source operation. Equal first and middle data is honest where the file has no documentation structure. Prefer a schema or Var reference when one exists.

## Cost: no level needs whole-program work

Let B be changed prose/file length, H its headings, R its occurrences, S the selected source length, C one authored contract's nodes, K its directly named schemas, P one namespace's own declarations, and U the matching schema-role arities. N is the database population. Index navigation has logarithmic cost in N; traversing unrelated definitions is never a level's work.

| Kind | Summary per uncached call | Doc per uncached call | Source per uncached call | Extra work per change introduced by levels |
|---|---|---|---|---|
| Function | Identity seek + held doc summary | Same + C and K named schema reads; reuse compiled Malli contract | Identity seek + S; library source may read its containing resource | Reparse changed doc B and its R occurrences; contracts already produce their arity facts. No new contract index/reverse index |
| Namespace | One namespace lookup | Indexed namespace membership, O(P) names | Those P docs, ideally lazy; union directly named schema reads once if materialized | Existing namespace membership facts change only for edited declarations. No per-namespace cached copy to rewrite |
| Schema | One key lookup + description | One form | Immediate member forms + O((U+1) log N) indexed holder navigation per role, plus matching referenced-by schemas | Existing per-arity input/output/guard facts and schema-reference facts already change at their producer. No work at level owners until a read |
| Test | Its last recorded observation | That observation + stored direct calls/reach facts | Its source S and same observation | Runner already writes evidence for the tests it actually ran. Display does not add a test run or compute reach |
| Task | One identity lookup | Its own content and Q proofs' recorded observations | Its plan/instructions plus Q observations | Parse only changed content; proof observations stay on tests, not copied into tasks |
| Document/section | Indexed section metadata and paragraph | Paragraph + immediate children | Selected text S | One CommonMark parse O(B), one heading-stack fold O(H), source spans and cumulative token counts; no repository scan |
| Missing reference | Bounded identity probes | Same | Same | None; optional explicit search is a different operation |

These are algorithm targets, not measurements of all current owners. In particular:

1. `seon.ref/declarations` currently walks every schema form on a projection cache miss. Select the requested identity's entity schema directly and derive its selector once from that declaration; never rebuild a map for all identities because a reference is requested. Do not reuse the existing whole-population derivation as the three-level dispatcher.
2. `seon.ref/resolve-name` currently fetches source/calls before a level is known and `inner` expands callees. Resolve identity first, choose the level, then acquire only its members. This is deletion, not an additional filter over a large acquired value.
3. `seon.ref/section` currently rereads and splits the whole file, and calls `section-end` over the headings for each child. A heading stack computes each section end and immediate children once; cumulative source lengths permit `seon.ai.tokens/estimate-of-characters` without joining each descendant's text. Cache the parsed source/spans by the existing content digest, pin and path. Runtime archives are immutable; resolve a path against that held archive/pin. Editable overlays must provide their changed content digest, not stat-and-slurp every file on every reference.
4. `my.program/file-line` rereads a containing source file to derive display metadata. Reference source should reuse stored exact source/provenance; if a disk line is requested, derive it from the held file's spans once. File line verification must not dominate every small source read.
5. `seon.ref/document` hashes supplied text on every call, even when parsing is cached. Passing the held parsed document avoids repeated O(B) hashing. A hit is not automatically O(1) just because the parser did not run.
6. Read evidence must cover absence as well as returned members. Unrelated writes must preserve a cached value; insertion/removal of a matching role or referenced schema must make it current only after recomputation. Reuse `seon.render/render-call`/`seon.cache` and `seon.db/read-evidence-current?`; do not install another cache or eager invalidator.

One heavily used schema can genuinely have many users. Reading its entire user list costs those matches, not every function or every contract. The result is not capped at eight. The printer limits presentation; the complete matched value remains addressable. A new key index is unnecessary: `:seon.fn.arity/input-refs`, `:seon.fn.arity/output-refs` and `:seon.fn.arity/guard-refs` are already indexed. Do not compute transitive producer lists: functions accepting a containing map are not automatically functions accepting the member alone.

### The measured join problem and smaller composition

The obvious query, repeated for each role, is:

```clojure
[:find [?s ...] :in $ ?k ?a
 :where [?arity ?a ?k]
        [?f :seon.fn/arities ?arity]
        [?f :seon.fn/sym ?s]]
```

Three complete schema source probes initially cost 26.86/22.76/23.31 ms. A later profiled first-use set cost **60.55/56.16/54.12 ms**, allocating **65.47/55.39/55.54 MB**. `seon.db/vector-query-call` accounted for 58/54/52 ms. An immediate repeated path read was .75 ms; another request was .66 ms. These variable cache-dependent results are not a sufficient guarantee.

Root JVM read-only `datahike.query/explain` of the physical membership equivalent on the default connection showed:

```text
SCAN aevt [?cell :seon.fn/arities ?a] (est. 32533 rows)
SCAN avet [?a :seon.fn.arity/input-refs :seon.ref/path] (est. 650 rows)
ENTITY-GROUP on ?f
  scan: [?f :seon.db.vector/cells ?cell] [aevt] est=32533
  merge: [?f :seon.fn/sym ?s]
```

The estimate of 650 is not the actual five matching arities. Literal role attributes still cost 19.74 ms / 29.04 MB for one role. Supplying the indexed arity ids to a smaller logical query still cost 29.41/11.62/11.37 ms for the three role sets. Neither is the recommended design.

The existing `seon.db/attribute-holders` already follows a vector cell to its owner and uses AVET seeks. Composing it key → matching arities → function owners, then one `seon.db/pull-many`, produced the identical sorted directional names. Complete source probes measured **.54/.66/.71 ms**, allocating **.43/.89/.99 MB**. The member definitions were included. No program-wide index was built. This is a prototype win, not a performance commit: the final public owner must capture index read evidence before its values can be cached. The existing `seon.db/an-identity-join-scans-the-whole-ref-attribute` records the broader planner defect. Fixing that planner could make the original Datalog the best implementation; this proposal does not require a fork bump to expose the already-existing owner.

A raw `seon.db/datoms` traversal was deliberately rejected: the arities attribute's matching entity is a storage cell, not the logical function. Likewise a tested reverse logical pull returned `{}`. A renderer must not learn bridge-cell traversal; the database owner already owns it.

## Markdown parsing and authoring

CommonMark 0.24.0 is sufficient. `Code.getSourceSpans()` includes the backticks; `SourceSpan.getInputIndex()` and `getLength()` identify the exact original UTF-16 slice. The live JVM returned these three code spans distinctly: single delimiters, three inline delimiters, and two delimiters. A line starting a code fence was a separate `FencedCodeBlock`. See the pinned [SourceSpan implementation](https://raw.githubusercontent.com/commonmark/commonmark-java/commonmark-parent-0.24.0/commonmark/src/main/java/org/commonmark/node/SourceSpan.java).

After CommonMark says “Code”, count the opening backticks at that known span; this is delimiter inspection, not regex parsing or a second prose parser. Only widths one and three have reference meaning; other widths remain code spans. Fence/indented-code nodes never become references. Fully qualified symbols/keywords use the existing EDAMAME name reader; alias-qualified forms are code. A dotted namespace must resolve as a namespace. Bare unresolved prose stays prose; explicit unresolved names are problems on their containing subject. Existing Markdown links choose doc, with no new link syntax.

Recommended first-party style: one H1 title for a file; immediately after each addressed heading, one short paragraph states the rule/purpose and enough context to decide whether to read further. Put distinct subtopics under real child headings, rather than bold pseudo-headings. Examples, tables and code follow that paragraph or have their own headings. There is no numeric paragraph length fence. The existing first-sentence rule supplies summary; the paragraph supplies doc; the entire section supplies source.

Check structure in the same CommonMark walk: the first block directly owned by an addressed heading is a `Paragraph`, not a paragraph found under its first child. Emit a resolver/documentation problem on the authoring subject when absent; preserve the heading and source read. Do not refuse rendering, invent a summary from a child or lint every document on a single edit. Check changed first-party documents and sections they introduce/edit; a reference can report a pre-existing absence when encountered. Pinned library documents are not rewritten or required to follow Seon's style. Their actual structure still yields whatever levels exist.

A concrete producer bug emerged: whole-file `seon.ref/section` for vocabulary.md and TASK-CHAIN.md leaves the closing YAML `---` at the start of `:seon.ref/text`. Reparsing that selected text treats the remaining document as front matter, yielding no headings/paragraph. Keep original parse spans and select after the actual front-matter node. The initial scratch implementation that reparsed this string made the middle levels 10,639/14,953 tokens; those are rejected results. The final prototype reads the original spans. This is why the production design has one original parse, not parsers over reconstructed sections.

## Task A: what the first call should contain

The context-sanity implementation task already names `seon.edit/single-form` and specifies reading source with `seon.sci.reader/read`. Put its own instructions in the task doc/block. Cite the implementation target with inline triple backticks (371 measured data tokens); cite `seon.sci.reader/read` at doc level (281), the regression table at source (250), and incidental `seon.edit`/`seon.turn` namespace mentions bare. Keep `clojure.core/count`, generators and incidental callees as ordinary names, not automatic full entries.

Those three selected values are about **902 estimated data tokens**, before common envelopes/instructions. The rule and exact change location are available on the first call. The reader probe on `"{:a 1 :b}"` returned a read-event vector with `:seon.sci.reader/error` and `:error-kind :odd-map` in 0 ms reported form time (.3 MB allocation). The first call can show that counterexample through the task/proof data; a summary of the private owner cannot convey its body, because the owner has no docstring. A doc-only policy would still require source before editing. The claim is “first-call information to choose the correct next edit,” not proof that an agent can skip the REPL regression.

## Loadable phases and red proofs

These are proposed phases for the hybrid, not assigned tasks. Every named new proof is a specification to write red before implementation, not an existing passing test. Modify the owning sections of TASK-CHAIN §3a in the implementation phase; this independent note changes no shared spec.

| Phase | Owner change and deletion | Red proof, asserted on data | Cost gate |
|---|---|---|---|
| 1. Occurrences and original document spans | Extend `seon.ref/document` to retain view/source spans, first owned paragraph, section ends and direct children. Remove selected-text reparsing and repeated `section-end` scans. Fix front-matter start. | Extend `seon.ref-test/front-matter-is-not-a-heading` and `seon.ref-test/a-doc-section-reads-with-its-headings-below-and-size`: selected body reparses to original headings; paragraph belongs to parent, not child. New `seon.ref-test/inline-delimiters-select-reference-values`: 0/1/3 views, fence excluded, aliases excluded, unknown explicit preserved, later stronger occurrence wins. | One parse and one heading fold per changed text, cache reuse for unchanged digest; no other file read |
| 2. Indexed read owner | Extend existing `seon.db/attribute-holders` with public contract and complete read evidence; use it at schema/namespace acquisition owners. Do not add a new index or publish duplicate facts. Investigate/fix reverse vector pull separately at its owner, not inside a renderer. | New `seon.db-test/attribute-holders-follow-logical-membership-with-read-evidence`: normal and vector attributes return exact owners, an empty seek records evidence, matching insert/remove invalidates, unrelated writes preserve it. New `seon.render.schema-test/contract-users-are-separated-by-role`: exact input/output/guard names and a containing schema are distinguished. | End-to-end level-source measurement via `seon.profile/measure` against original joins; added unrelated functions do not add holder visits. Verify evidence costs before accepting the .54–.71 ms prototype result |
| 3. Shared doc/source values | Extend `seon.sci.lookup/documentation-value` and `my.program/source` with the per-kind shapes above, using current family owners. Install the source macro as a returning adapter. Make `seon.render.schema/description` public and backfill touched missing descriptions. Preserve source's old members additively. | Extend `seon.sci.documentation-test/documentation-is-returned-data-without-a-second-printed-copy` and `seon.sci.documentation-test/doc-classifies-authored-references`: doc/reference equality for all kinds, source/reference equality and no extra `:out`; direct schema descriptions present; source keeps exact authored text. Convert `seon.ref-test/an-unloaded-library-name-resolves-from-the-dependency-cache`'s source stdout assertion in the same commit. | Every scalar reference reads only its subject/direct dependencies; no whole registry selector rebuild; namespace work proportional to its own members |
| 4. Context selection and rendering | Select authored view before pulling content, share owner values at the render pairs, replace old distance branches, replace `inner` callee expansion with direct contract schemas and delete schema `names-shown`. Keep HTML sharing selections. | Convert `seon.ref-test/each-reference-kind-uses-its-declared-render` into a per-kind/per-level data table; extend `seon.ref-test/a-name-resolved-at-top-level-is-cited-by-name-below`. New `seon.context-test/a-task-author-selects-the-reference-levels`: task A owner/source, reader/doc, proof/source and incidental namespace/summary appear with their complete selected data. | Measure same context before/after with `seon.profile/measure`, equal proof inputs and fixed printer profile; assert acquisition owners called only for requested levels. Never use equal shown tokens as the saving proof |
| 5. Content-valid reuse | Route shared selections through the existing render cache with their actual reads. Remove any whole-projection level map and duplicate cached doc/source representation. | Extend cache-owner tests with subject edit, direct schema-description edit, role-user addition, and unrelated task/evaluation write; assert equal values and exact recomputations. New `seon.ref-test/a-reference-level-does-no-work-on-an-unrelated-definition-change`. | Same level request on parent/change on a quiet JVM; report acquisition, print, request, allocation and read counts. No publication or whole-program reindex merely because documentation changed |

Phases 2 and 3 must not obscure the SCI namespace defect with a filter. Its existing task `seon.sci.kernel/an-agent-namespace-ns-interns-lists-only-its-own-vars` owns the root correction; the namespace portion of phase 3 depends on it. A stored-program namespace directory remains useful for prototypes, but the REPL must continue to include genuine private-context public Vars, macros and ordinary defs correctly. This is not permission to replace `ns-publics` with only indexed functions.

What goes away, concretely: `seon.render.ns/function-ai`'s graph-distance source tier, equivalent reference distance branches in namespace/task/test/schema renders, `seon.render.ns/source-lines` as the representation of source references, `seon.ref/inner`'s automatic callee list for doc views, the extra copies of doc selection in renders, `seon.render.schema/names-shown`, and per-request derivation of all identity declarations. Preserve graph navigation for explicitly requested neighbours and preserve declared render selection for ordinary non-reference values.

## Findings, boundaries and owner questions

`bin/seon status` and MCP `runtime_status` initially agreed: runtime available, health `:faulted`, one open fault, one failed test, 16 untested-hot warnings. First own `(seon.context/current-state)` was 19 ms / 25.1 MB form cost and 200.1 ms request cost. No task was assigned to the designer; it answered only the turns block. Ordinary measurement requests were below one second; interpreter definition requests were roughly .28–.69 s including tests/recording. No platform restart was performed.

Additional observed boundaries:

- `(doc :seon.task/plan)` returned documentation-unavailable on the live archive. This is the known task rename boundary, not evidence that the future plan key is invalid. No foreign schema/source edits were loaded.
- Defining `probe-profile` from `(seon.db/db)` at top level caused its later installation to run without an immutable database and `sample` to report an unresolved profile. Replaced the scratch dependency with a literal diagnostic profile and removed both experimental data defs through `my.program/ns-unmap!`. The existing `seon.sci.eval/a-test-namespace-def-that-fails-to-interpret-is-used-as-its-refusal` covers the related installation failure class; exact messages and signatures were `9eb599f195809f6f3e7f996316f179342b54670fd4182375ca3422adefedb480` and `7c52d6ce256964e2e1bdc5b72c6eb450ac4bba47d1e27dcf3c47032b3643a91d`. This does not prove that task is the same root cause.
- The first printer experiment omitted its profile/root and returned a diagnostic beside the value. Subsequent measurements supplied both. An accidental two-argument `agent-render-profile` and a string instead of `:seon.ref/source` were correctly refused input probes; their results are not included in tables.
- A non-root JVM-mode call was refused; the two host inspections subsequently used root JVM mode read-only. All actual level prototypes remained on this lane's SCI branch.
- Created runtime finding tasks on the disposable branch: `seon.ref/whole-file-section-keeps-the-front-matter-closing-delimiter` (handle `8a23a2ba94e2`) and `seon.render.schema/a-key-usage-query-scans-all-arities` (`4156c4a37e5e`). This note preserves their evidence because the probe branch is retired, not merged. Consolidate the latter reproduction with the existing database join-scan task before assigning work.

Open decisions for the hybrid/owner:

1. Adopt first sentence / opening paragraph plus child headings / whole section for Markdown? Recommended. A heading-only middle saves fewer useful decisions on leaf sections; a whole introductory block collapses into source on all three sampled leaves.
2. Keep schema source to **direct** directional users and immediate member definitions? Recommended. A transitive producer label makes a stronger, often false claim and can walk the program.
3. Make tests/tasks documentation addressable through `doc` and paths through `(doc "path#anchor")`, with source dispatch at the same owner? Recommended to satisfy exact shared values. Existing `my.*` readers remain callable aliases/delegates, not independent renderers.
4. Preserve all genuine namespace public Vars while fixing the inherited-core pollution at SCI installation. Do not ship the prototype's stored-functions-only directory as the final `doc` meaning.

## Verification and handoff

Only this note is owned/committed. Foreign boundary: the `s1a-rename` lane holds a broad source/schema/test rename, including `src/seon/ref.clj`, `src/seon/context.clj`, task resources and `test/seon/ref_test.clj`; dependency checkouts also differ from their pins. The live archive and the note-only candidate do not load those working edits. No worktree, shared-index staging, foreign edit, message, reset or restart was performed.

No production behavior test reaches a Markdown-only change. The first commit gate named `seon.ref-test/front-matter-is-not-a-heading`, `seon.ref-test/fenced-code-and-prose-spans-are-not-references` and `seon.sci.documentation-test/documentation-is-returned-data-without-a-second-printed-copy` as baseline smoke checks of the owners used by this research, not proof that the proposed levels are installed.

Candidate `846bd54cb30881a3178e684ce197cf9b9c86afd1`, stored run `b4d178421c80`: 3 executed, 0 reused, 27 assertions passed, 1 failed, 1 errored, 11,539 ms. Both parser vars passed. The documentation var expected `:example` among doc's keys but received only `:arglists :body :in :out :summary :supplied`; its subsequent `(read-string (:example doc))` threw NullPointerException. This is a baseline red in unchanged executable inputs: the candidate adds only this note. The final gate selects the two passing parser vars individually; the documentation failure remains unresolved and must not be described as passing. Diagnostic: `data/clusters/default/logs/operator-check-20261014T130043.446.edn`. No foreign test was edited or suppressed in code.

The full working-tree lint took 6,340 ms and found one error in `seon.transaction-result-test/agent-transactions-return-resolved-changes-and-preserve-refusals`, an assertion-message printing violation, plus 1,666 warnings. That file was changing while the check ran; neither this note nor its candidate includes a change to it. An isolated `git archive` of HEAD `76985e26b` ran `clj-kondo --lint src script test --cache false --parallel`: **0 errors**, 1,665 warnings, 6,649 ms. Those committed inputs are the authoritative source lint for this Markdown-only slice; warnings are not claimed absent.

## Reproducible SCI probes

Cleanup completed: the agent's own retirement returned `:my.agent/retiring? true` (54 ms, 4.2 MB). Because its unmerged experimental definitions kept the branch, administrative cleanup through root's `my.agent/retire-branch!` returned `:my.agent/retired? true` (45 ms, 3.9 MB); no prototype was run as root SCI. A read-only JVM check of `datahike.api/branches` with the explicit default connection confirmed `:agent-5627642d6933` absent (1 ms). No probe definitions or finding tasks were merged.

These are the actual finite scratch compositions behind the tables, on the installed archive named above. Start an own SCI agent with `(seon.context/current-state)`, then `(in-ns 'scratch.reflevels-astra)`; evaluate the definitions below there. They deliberately use the installed task names. They are experimental composition contracts, not proposed production schemas. The private holder call is the measured algorithm without read-evidence capture. `contract-doc` measures input/output schema descriptions only; adding direct guard descriptions is part of the recommendation and remains unmeasured.

```clojure
+(defn sample "One finite level's acquisition and printer costs." {:malli/schema [:=> [:cat :seon.profile/thunk] :map]} [f] (let [start (System/nanoTime) value (f) acquired (System/nanoTime) shown (seon.render.value/text {:seon.render/value value :seon.render/profile {:seon.render.profile/id :seon.render.profile/agent :seon.render.profile/token-budget 100000 :seon.render.profile/token-ceiling 983040 :seon.render.profile/max-depth 32 :seon.render.profile/max-children 32 :seon.render.profile/composition :multiline} :seon.render.value/root 'result/probe}) printed (System/nanoTime)] {:tokens (seon.ai.tokens/estimate shown) :ms (/ (- acquired start) 1000000.0) :print-ms (/ (- printed acquired) 1000000.0) :value value}))

(defn levels "Measure each finite requested level once." {:malli/schema [:=> [:cat [:vector :seon.profile/thunk]] [:vector :map]]} [fs] (mapv #(dissoc (sample %) :value) fs))
(defn doc-value "The installed doc value." {:malli/schema [:=> [:cat :seon.sci.eval/authored-reference] :map]} [s] (seon.sci.lookup/documentation-value (seon.db/db) s s))
(defn contract-doc "The installed doc with direct contract schema descriptions." {:malli/schema [:=> [:cat :qualified-symbol] :map]} [s] (let [db (seon.db/db) row (seon.db/pull db [{:seon.fn/arities [:seon.fn.arity/input-refs :seon.fn.arity/output-refs]}] [:seon.fn/sym s]) ks (into #{} (mapcat #(concat (:seon.fn.arity/input-refs %) (:seon.fn.arity/output-refs %))) (:seon.fn/arities row))] (assoc (doc-value s) :schemas (into (sorted-map) (map (fn [k] [k (select-keys (seon.ref/schema-documentation db k k) [:description])])) ks))))

(defn schema-users "The database owner's indexed holders composed over one contract role." {:malli/schema [:=> [:cat :seon.db/database-value :qualified-keyword :qualified-keyword] [:vector :qualified-symbol]]} [db k a] (let [ids (vec (distinct (mapcat #(#'seon.db/attribute-holders db :seon.fn/arities %) (#'seon.db/attribute-holders db a k))))] (vec (sort (keep :seon.fn/sym (seon.db/pull-many db [:seon.fn/sym] ids))))))
(defn schema-full "A schema's declaration, direct members and indexed directional contract users." {:malli/schema [:=> [:cat :qualified-keyword] :map]} [k] (let [db (seon.db/db) row (seon.db/pull db [:seon.schema/references] [:seon.schema/key k])] (assoc (seon.ref/schema-documentation db k k) :members (mapv #(seon.ref/schema-documentation db % %) (sort (:seon.schema/references row))) :input (schema-users db k :seon.fn.arity/input-refs) :output (schema-users db k :seon.fn.arity/output-refs) :guard (schema-users db k :seon.fn.arity/guard-refs))))
(mapv (fn [k] (let [p (seon.profile/measure #(schema-full k))] {:key k :ms (:seon.profile/ms p) :alloc (:seon.profile/alloc-bytes p) :counts (update-vals (select-keys (:seon.profile/value p) [:input :output :guard :members]) count) :levels (levels [#(select-keys (doc-value k) [:key :description]) #(doc-value k) #(schema-full k)])})) [:seon.ref/path :seon.ref/section :seon.ref/section-error])

(defn test-summary "A test name and its recorded evidence, unknown when absent." {:malli/schema [:=> [:cat :qualified-symbol] :map]} [s] (let [r (seon.test/recorded-result (seon.db/db) s)] (assoc (select-keys r [:seon.test/pass-count :seon.test/fail-count :seon.test/error-count :seon.test/run-basis-t :seon.test/recorded-basis-t :seon.test/unknown]) :seon.test/sym s)))
(defn test-doc "A test's evidence and direct analyzed calls, without claiming observed reach." {:malli/schema [:=> [:cat :qualified-symbol] :map]} [s] (merge (test-summary s) (seon.db/pull (seon.db/db) [:seon.fn/calls :seon.test/reach :seon.test/reach-unknown] [:seon.test/sym s])))
(mapv (fn [s] {:subject s :levels (levels [#(test-summary s) #(test-doc s) #(my.program/source s)])}) '[seon.ref-test/fenced-code-and-prose-spans-are-not-references seon.ref-test/a-reference-with-no-path-is-a-missing-file seon.edit-test/an-odd-map-anywhere-is-not-one-form])

(defn task-full "The installed effective task, its instructions and recorded proof observations." {:malli/schema [:=> [:cat :qualified-symbol] :map]} [s] (let [db (seon.db/db) row (seon.db/pull db [:seon.task/id] [:seon.task/sym s]) task (seon.task/effective db (:seon.task/id row)) obligations (seon.task/obligations db s)] (assoc task :seon.task/proofs (mapv test-summary (sort (:seon.task/proofs obligations))))))
(defn task-doc "The task's opening statement and proof observations." {:malli/schema [:=> [:cat :qualified-symbol] :map]} [s] (let [t (task-full s)] {:seon.task/sym s :summary (:summary (seon.ref/docstring (:seon.task/intent t ""))) :seon.task/proofs (:seon.task/proofs t)}))
(mapv (fn [s] {:subject s :levels (levels [#(select-keys (seon.db/pull (seon.db/db) [:seon.task/sym :seon.task/name] [:seon.task/sym s]) [:seon.task/sym :seon.task/name]) #(task-doc s) #(task-full s)])}) '[seon.edit/implement-an-unreadable-odd-map-is-not-one-form seon.edit/an-unreadable-odd-map-is-not-one-form seon.ref/a-reference-with-no-path-is-a-missing-file])

(defn document-level "The summary, opening paragraph with child headings, or whole selected section." {:malli/schema [:=> [:cat :string :int] :map]} [path level] (let [s (seon.ref/section path) raw (slurp (:seon.ref/file (seon.ref/locate (:seon.ref/path s)))) d (seon.ref/document raw) hs (:seon.ref/headings d) target (if (:seon.ref/anchor s) (first (filter #(= (:seon.ref/anchor s) (:seon.ref/anchor %)) hs)) (first hs)) from (or (:seon.ref/line target) 1) next-h (first (filter #(> (:seon.ref/line %) from) hs)) lines (vec (clojure.string/split-lines raw)) stop (dec (or (:seon.ref/line next-h) (inc (count lines)))) own (clojure.string/join "\n" (subvec lines (dec from) stop)) parsed (seon.ref/document own) p (:seon.ref/first-paragraph parsed) own-lines (vec (clojure.string/split-lines own)) intro (when p (clojure.string/join "\n" (subvec own-lines (dec (:seon.ref/line p)) (:seon.ref/end-line p)))) base (select-keys s [:seon.ref/path :seon.ref/anchor :seon.ref/heading :seon.ref/pin :seon.ref/tokens])] (case level 0 (cond-> base intro (assoc :summary (:summary (seon.ref/docstring intro)))) 1 (cond-> (assoc base :seon.ref/below (:seon.ref/below s)) intro (assoc :seon.ref/text intro)) 2 s)))
(mapv (fn [path] {:subject path :levels (levels [#(document-level path 0) #(document-level path 1) #(document-level path 2)])}) ["README.md" "docs/seon/architecture/vocabulary.md" "docs/seon/schedule/cards/TASK-CHAIN.md" "docs/seon/architecture/vocabulary.md#git-words" "docs/seon/schedule/cards/TASK-CHAIN.md#3-the-plan-and-its-references" "docs/seon/schedule/cards/TASK-CHAIN.md#3a-agent-verbs-and-what-references-show" "reference-code/malli/README.md" "reference-code/sci/README.md" "reference-code/datahike/README.md"])
```

For functions, evaluate `(mapv (fn [s] {:subject s :levels (levels [#(seon.ref/brief (seon.db/db) s) #(contract-doc s) #(my.program/source s)])}) '[seon.edit/single-form seon.ref/section seon.sci.reader/read])`. Library subjects use `doc-value` and `{:sym s :source (clojure.repl/source-fn s)}` for their second/third thunks, as measured. No result is stored in a top-level data def.

The document table's first-party file middle column substitutes the sole H1's `:seon.ref/below` for the whole file's H1 wrapper: read `seon.ref/section` at the same path plus that H1's anchor. Sections already have the right direct children. Dependency files keep their whole-file children because their heading structure is not constrained by Seon's proposed style. The prototype otherwise retains the installed section owner's front-matter defect, explicitly measured above.
