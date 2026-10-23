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

A var is shown by the same depths (```seon.context/resolve-ref```): at summary its arglists and its docstring's first sentence, at doc its arglists, docstring and contract, at code its recorded source. A source over `:seon.config.render/reference-tokens` (3,000, v1's per-function cap) shows the doc instead, with `:left-out` (how many tokens) and `:read`, the `(clojure.repl/source ns/f)` that reads it; a var with no docstring shows its source at doc depth. Uncapped, whole sources were 82-88% of a measured task's context.

## Cost

One parse is linear in the text. Most of its allocation is reading each candidate name with `seon.reader/read-text` (about 13 µs and 18 KB per name, half of it `seon.reader/opts` rebuilt per call): `AGENTS.md` (21 KB) parses in 0.8 ms and 1.2 MB. The proof is `seon.ref-test/one-parse-costs-one-pass`. Edamame on Jolt allocates about 2 KB per symbol it reads (100 KB for a 100-byte vector of 50 symbols), so the reads, not the scan, are where a cheaper parse would come from.

## Task references

A task's references are the names its plan and instructions cite, derived by the task writer and never authored.

```seon.task/create-tx``` stores them as `:seon.task/references` (the set from ```seon.ref/names```; no names, no key), and ```seon.task/load-tx``` restates them when a plan changes, so the context and the write set read one derivation. A citation that resolves to nothing is a problem on the task, never an error (v1's rule, TASK-CHAIN §3): ```seon.task/citation-problems``` answers `{:task id :name sym :problem :unresolved}` for each, and `my.root/create!` and `my.root/load-tasks!` carry them as `:problems` beside the write, so whoever wrote the plan sees it when they write it. In the agent's context the same name shows `:unresolved true` with `:problem`, the message from ```seon.ref/problem-messages```: it resolves to nothing on the branch, what the task intends is its plan's to say, and two backticks mention a name without citing it. A plan that cites the function its proof calls, not the task's own name, is the cheapest way to a good context.

## The wiki in the store

The wiki's sections are entities on main, so who cites a section or a var is a database query.

```seon.docs/load-tx``` reads every page under `docs/` with the one parse and makes main hold one entity per heading, `:seon.section/fact`: its address (the parse's `path#anchor`, so ``docs/flow.md#idioms``), heading, lead, own text (the lines after its heading up to the next heading of any level) and parent. Its `:seon.section/references` are what its own text cites, resolved on the db: a link, or a cited `path#anchor`, reaches a section (a link with no anchor reaches the page's first section), and a name reaches its var by `:var/name`. A section's backlinks are therefore a query, the sections whose `:seon.section/references` hold it (`seon.docs-test/backlinks-are-a-sections-instances`): a pattern's home knows its instances with no key of its own. A var's home is the backlink citing it at code depth, read from that section's own text by the same parse, so no depth is stored. ```seon.docs/load-dir-tx``` is the one load of `docs/`: root's boot writes it in the boot fact's commit (`seon.root/wiki-tx`, [root](root.md#boot)), and `my.root/load-docs!` writes it on demand.

Loading writes only what differs, as task files do (```seon.task/load-tx```): each section's stated attributes are made exactly what its text says by the one `seon.task/entity-tx` (a value main holds that the text dropped is retracted, one it lacks asserted), a section no page holds any more is retracted, and an unchanged wiki answers `[]`. A changed section rewrites only itself, because nothing stored depends on line numbers. A link, or a cited `path#anchor`, that reaches no section does not refuse the load: it is kept on the section that makes it as `:seon.section/dead-links` (the target's address), every other section loads as usual, and while the target is still no stored section it is a derived `:dead-citation` problem naming the page, the section and the target ([health](health.md#a-dead-citation)); adding the target section closes it with nothing written (`seon.docs-test/a-link-to-no-section-is-a-dead-link-and-the-rest-loads`, `seon.root-test/a-dead-link-in-the-wiki-is-a-problem-and-the-boot-goes-on`). A schema key, a file citation and a name that names nothing are not stored: there is no file entity, and nothing reports the unresolved ones: no `bin/check` step reads the wiki's citations (task `seon.docs/the-wiki-stays-true-and-healthy`).

Cost, measured on a wiki of 8 pages, 60 KB and 60 sections on a test branch: a first load 12.6 ms and 17 MB, an unchanged reload 19 ms and 23 MB, of which the parse is 9 ms and 13 MB. Per section, an unchanged reload costs about 200 KB, most of it the seeks of what main holds (one per stated attribute, `seon.task/entity-tx`) (Datahike seeks on Jolt allocate ~33 KB each, `seon.db/avet-seek-allocates`); the proof is `seon.docs-cost-test/an-unchanged-reload-costs-a-parse-and-a-pull-per-section`. Skipping the parse of an unchanged page would need a stored page digest, a key this design does not add.

## Sections in a task's context

The context shows each wiki section the task's plan or instructions cite, at the depth the citation asks for.

Beside them, `:found` names the helpers the plan does not (```seon.context/found```): the program functions the plan's cited vars and the task's proofs name in their recorded definitions (`:form/refs`, one hop), macros left out, ranked as a search ranks terms (tf-idf: called by more of those sources, and by fewer forms of the whole program, so the contract plumbing every `defn` names sinks), at doc depth, at most `:seon.config.render/found` and then `+N more` with the form that lists them. Each hit's score, like each var's use count in the namespace map (```seon.context/ns-map```), is taken once before the sort (`sort-by` would take its key on every comparison; ```seon.context-cost-test/a-namespace-map-counts-each-vars-uses-once```, ```seon.context-cost-test/found-scores-each-hit-once```). Measured with embeddings, this lever took a task set from 7/32 green without it to 22/24 with; the call graph finds only a helper something already calls.

`seon.context/current-state` keeps the citations that carry a path and an anchor (```seon.ref/references``` over the same text that gives the names) and shows each as `:sections`: ```seon.docs/view``` reads the section main holds and ```seon.ref/shown``` renders it, the one rule `seon.ref/view` also uses for a freshly parsed document. A link or one backtick shows the lead (or opening) and the sections below; three backticks show the section whole (its own text); a section main does not hold shows `:unresolved` beside the same message as an unresolved name. Stored sections keep no opening block, so a section whose first block is not a paragraph shows no lead at doc depth, and its sections below come in the order the wiki was written. A link with no anchor (a whole page) is not shown, because main stores no page entity: the first section's address would have to be derived. A prose bare address is a summary, and `seon.ref/references` keeps it only when it names a file, so a bare section address does not reach the context; write the link.

## Files and namespaces on demand

A file or a namespace the plan cites is read when the context is built and never stored; only Seon's own `docs/` is (above).

A backticked dotted simple symbol (`seon.ref`) is a namespace candidate, resolve-only as v1's rule: ```seon.ref/references``` keeps it when it names a namespace on the db (`:ns/name`) or one loaded, otherwise it stays prose (`seon.ref-test/a-namespace-is-read-as-a-namespace`); its `:seon.ref/name` is the simple symbol. ```seon.ref/namespace-view``` shows it by the one rule `seon.ref/shown`: the docstring is its lead and its public vars are the sections below, so summary is the docstring's first sentence and doc adds the publics.

A cited path that is not a stored section is a file reference: ```seon.ref/file-view``` reads it from the checkout now. A Markdown file goes through `seon.ref/parse` and `seon.ref/view` (the anchor's section, else the first, and at code depth its own text, the lines up to the next heading, as the stored section shows it: `seon.docs-test/one-address-shows-one-text-through-ref-and-docs`); any other file shows its address and line count, and its text whole at code depth. A file under ``reference-code/<project>`` also carries ``:seon.ref/pin``, the revision main's source pins that submodule at: its gitlink (`git ls-files -s`), which root's import records on its `:seon.root/source` fact (`seon.fx/read-source`), never written by the author and never read from git per render. It is not the submodule's checked-out HEAD, which `bin/build` copies. `seon.context/current-state` puts all three kinds in `:sections` (`seon.context-test/a-cited-file-is-read-on-demand`); a path with a `:line-reference` is not shown, and one that reaches nothing shows `:unresolved`.

Cost: a cited file is read and parsed on every context build, linear in its size. A task citing `src/seon/ref.clj` (30 KB) at code depth makes a context of about 11,000 tokens, `bin/env` about 430 and one `docs/references.md#cost` section about 480 (printer estimate). Cite a section or a var rather than a whole file.
