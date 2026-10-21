# References

A reference is what a text cites, read by one parser from the text itself, never from a declared list.

Every text Seon reads as prose (a task's plan, a wiki page, a docstring) goes through ```seon.ref/parse```, and what it cites becomes that text's references: a plan's are its task's, a section's are its own, a docstring's are its definition's. The context pulls each one at the depth its citation asks for. The rule is v1's (`~/src/seon/docs/seon/schedule/cards/TASK-CHAIN.md` §3a), ported onto next's program facts.

## One parse

```seon.ref/parse``` reads Markdown once and returns its sections and its citations. A section has an address `path#anchor` (GitHub anchors), a heading, a level, a line span, a parent, and a lead (the heading's next block when it is a paragraph) or an opening (any other first block). A citation is a name, or a path with an optional anchor, with its depth and line. ```seon.ref/scan``` is the scanner under it, one pass over the characters: ATX headings, fenced and indented code, code spans by CommonMark's equal-length backtick rule, inline links, and paragraphs ended by blank lines. Setext headings, reference links and HTML blocks are out of scope; the subset was proven against 70 examples of the CommonMark 0.31.2 spec (`seon.ref-test/commonmark-examples-for-the-subset`). No Markdown library on Jolt gives a tree, which is why the scanner exists.

## The delimiter picks the depth

- A bare word holding a `/` is its **summary**, and counts only when it names something.
- One backtick, or a link, is its **doc**.
- Three or more backticks are its **code**.
- Two backticks are a literal, never a reference: write ``ns/name`` to mention a name without citing it.

Only a span of exactly one qualified symbol or keyword is a name. A form, an alias form such as `str/join`, a name followed by more words, and a name ending in `*` are code or prose. A span whose first segment is a top-level entry of the checkout is a path. A `path:N` or `path:A-B` citation is that file, marked `:line-reference`: line numbers go stale, so cite the var instead. ```seon.ref/cited``` keeps each name once, at its strongest depth, where it is first mentioned.

## Resolving and showing

```seon.ref/references``` resolves names against the program's `:var/name` facts, keywords against the schema registry and paths against the checkout; ```seon.ref/names``` is the qualified symbols alone (what `seon.reconcile/hairy` and the context use). ```seon.ref/view``` shows a section at a depth: at summary its heading and its lead's first sentence, at doc its lead and the headings below, at code the whole section. A section with no lead answers `:no-lead` at summary and doc depth, so every section should open with one sentence that stands alone.

## Cost

One parse is linear in the text. Most of its allocation is reading each candidate name with `seon.reader/read-text` (about 13 µs and 18 KB per name, half of it `seon.reader/opts` rebuilt per call): `AGENTS.md` (21 KB) parses in 0.8 ms and 1.2 MB. The proof is `seon.ref-test/one-parse-costs-one-pass`. Edamame on Jolt allocates about 2 KB per symbol it reads (100 KB for a 100-byte vector of 50 symbols, measured 2026-10-21), so the reads, not the scan, are where a cheaper parse would come from.

## Task references

A task's references are the names its plan and instructions cite, derived by the task writer and never authored.

```seon.task/create-tx``` stores them as `:seon.task/references` (the set from ```seon.ref/names```; no names, no key), and ```seon.task/load-tx``` restates them when a plan changes, so the context and the write set read one derivation. A citation that resolves to nothing is a problem on the task, never a refusal (v1's rule, TASK-CHAIN §3): ```seon.task/citation-problems``` answers `{:task id :name sym :problem :unresolved}` for each, and `my.root/create!` and `my.root/load-tasks!` carry them as `:problems` beside the write, so whoever wrote the plan sees it when they write it. In the agent's context the same name shows `:unresolved true` with `:problem`, the message from ```seon.ref/problem-messages```: it resolves to nothing on the branch, what the task intends is its plan's to say, and two backticks mention a name without citing it. A plan that cites the function its proof calls, not the task's own name, is the cheapest way to a good context.

## The wiki in the store

The wiki's sections are entities on main, so who cites a section or a var is a database query.

```seon.docs/load-tx``` reads every page under `docs/` with the one parse and makes main hold one entity per heading, `:seon.section/fact`: its address (the parse's `path#anchor`, so ``docs/flow.md#idioms``), heading, lead, own text (the lines after its heading up to the next heading of any level) and parent. Its `:seon.section/references` are what its own text cites, resolved on the db: a link, or a cited `path#anchor`, reaches a section (a link with no anchor reaches the page's first section), and a name reaches its var by `:var/name`. A section's backlinks are therefore who cites it (```seon.docs/backlinks```): a pattern's home knows its instances with no key of its own. A var's home is the section citing it at code depth (```seon.docs/homes```), read from its backlinks' own text by the same parse, so no depth is stored. `my.root/load-docs!` is the verb; boot does not load the wiki yet.

Loading writes only what differs, as task files do (```seon.task/load-tx```): each section's stated attributes are compared with what main holds, a section no page holds any more is retracted, and an unchanged wiki answers `[]`. A changed section rewrites only itself, because nothing stored depends on line numbers. A link, or a cited `path#anchor`, that reaches no section refuses the whole load as `:seon.docs/refused`, naming the address and the section citing it. A schema key, a file citation and a name that names nothing are not stored: there is no file entity, and the unresolved ones are the docs check's to report (task `seon.docs/the-wiki-stays-true-and-healthy`).

Cost (2026-10-21, `docs/` at 8 pages, 60 KB, 60 sections, on a test branch): a first load 12.6 ms and 17 MB, an unchanged reload 19 ms and 23 MB, of which the parse is 9 ms and 13 MB. Per section, an unchanged reload costs about 200 KB, most of it the pull of what main holds (Datahike seeks on Jolt allocate ~33 KB each, `seon.db/avet-seek-allocates`); the proof is `seon.docs-cost-test/an-unchanged-reload-costs-a-parse-and-a-pull-per-section`. Skipping the parse of an unchanged page would need a stored page digest, a key this design does not add.
