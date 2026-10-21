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
