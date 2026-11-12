# References

A reference is what a text cites, read by one parser from the text itself, never from a declared list.

Every text Seon reads as text (a task's plan, a wiki page, a docstring) goes through ```seon.ref/parse```, and what it cites becomes that text's references (```seon.ref/references```, each once, only what names something on the db): a plan's are its task's, a section's its own, a docstring's its definition's. The context pulls each at the depth its citation asks for. The rule is v1's, ported onto the program's facts.

## One parse

```seon.ref/parse``` reads Markdown once (given its path, or nil for a plan or docstring) and returns its sections and citations. A section has an address `path#anchor` (GitHub anchors), a heading, a level, a line span, a parent, a lead (the heading's next block when it is a paragraph) or an opening, and its own text up to the next heading. A citation is a name, or a path with an optional anchor, with its depth and line. ```seon.ref/scan``` is the one-pass scanner under it: ATX headings, fenced and indented code, code spans by CommonMark's backtick rule, inline links, and paragraphs. A fenced block carries its content's offsets (`:seon.ref/text-from`, `:seon.ref/text-to`) and a list its items' (`:seon.ref/items`, `:seon.ref/ordered`), so a renderer cuts them with `subs`. Setext headings, reference links and HTML blocks are out of scope; the subset is checked against CommonMark 0.31.2's examples (`seon.ref-test/commonmark-examples-for-the-subset`). No Markdown library on Jolt gives a tree.

## The delimiter picks the depth

The delimiter around a citation decides how much of what it cites is pulled.

- A bare word holding a `/` is its **summary**, and counts only when it names something. A var's `#'` is punctuation; the name's own trailing `!` or `?` is not (`seon.ref-test/a-text-word-keeps-its-names-bang`). A word inside a `"..."` string is data, never a citation (`seon.ref-test/a-string-literal-in-text-cites-nothing`).
- One backtick, or a link, is its **doc**.
- Three or more backticks are its **code**.
- Two backticks are a literal, never a reference: write ``ns/name`` to mention a name without citing it.

Only a span of exactly one qualified symbol or keyword is a name; a form, an alias form such as `str/join`, a name followed by words, or a name ending in `*` is not. A span whose first segment is a top-level entry of the checkout is a path. A `path:N` citation is marked `:line-reference` (line numbers go stale: cite the var). ```seon.ref/cited``` keeps each name once, at its strongest depth.

## Resolving and showing

What a citation names is a db entity, by one rule (```seon.ref/target```): a qualified symbol a var by `:var/name`, else a task by `:seon.task/id`; a qualified keyword a schema entry (`:schema/key`); a dotted symbol a namespace (`:ns/name`); a `path#anchor` a section (`:seon.doc/address`); a path a page by its top section, else a file (`:file/path`), else the namespace whose file it is. It names something when the db holds that entity (```seon.ref/names-something?```), so library vars, directories and host functions name nothing: mention them as two-backtick literals. ```seon.ref/names``` is the qualified symbols alone.

```seon.ref/view``` shows a section at a depth: summary its heading and lead's first sentence, doc its lead and the headings below, code the whole section. A section with no lead answers `:no-lead`, so every section opens with one sentence that stands alone.

A var is shown by the same depths (```seon.doc/resolve-ref```): summary its arglists and docstring's first sentence, doc adds its docstring and contract, code its recorded source and `:change`, the form that changes it (```seon.repl/edit!```, [changing a definition](agent.md#changing-a-definition)). The source is whole; the one printer cuts it where shown, with the form that reads the rest (```my.task/data```). A var with no docstring shows its source at doc depth.

A namespace at code depth is its recorded ns form and a line for each definition in file order (```seon.doc/namespace-source```), the same text `(source ns)` shows. A schema key's part shows at summary the form that reads it, at doc and code its form and `:change` (``(my.schema/register! k form)``). A key no schema registers answers `:seon.schema/unknown-key` from ``(my.schema/forms k)``, and a registration whose form is no EDN is refused (```seon.program/non-edn```).

## Cost

One parse is linear in the text, about 70 bytes allocated per byte. A candidate name is read by its characters (```seon.ref/qualified-name```, held equal to the reader by `seon.ref-test/a-name-is-read-as-the-reader-reads-it`) rather than by the reader, which allocated about 2 KB a symbol on Jolt. The proof is `seon.ref-test/one-parse-costs-one-pass`.

## Task references

A task's cites are what its plan and instructions cite, written as cite entities by the write of the text, never authored. ```seon.task/create-tx```, ```seon.task/load-tx``` and ```seon.task/rederive-tx``` (any write of the plan or instructions through `seon.agent/transact!`) parse the text once, keep the targets the db holds (```seon.ref/kept```) and write them with ```seon.task/cites-tx```, the one writer, in the same transaction as the text, so no db holds new text with old cites and no render parses a plan with a parser the agent may be changing (`seon.task-test/the-writer-writes-a-tasks-cites`, `a-rewritten-text-leaves-exactly-its-cites`). A plan naming its own task's id cites the task until the function exists, then the var (`seon.task-test/a-task-naming-its-own-function-cites-what-the-write-holds`).

A citation that names nothing is refused by the write: its target is a lookup the db lacks, answered as ``:seon.db/no-target`` naming the lookup and the text holding it (```seon.db/checked```), so no db holds a dead citation. A plan names what it will create as a two-backtick literal; the exception is a target the same write makes. When a cited target goes later, the cite ends with it and its task gets `:seon.task/gone`, the target's identity, read as the `:uncited-task` problem `:gone` ([health](health.md#a-task-that-cites-nothing)); rewriting the text clears it (`seon.task-test/deleting-a-cited-var-ends-its-cites-and-the-task-holds-what-is-gone`). The cites every agent is made with are written only for addresses the store holds; the rest are named at the agent's start as `:seon.agent/cite-missing` problems (``seon.task/agent-cites-missing``; `seon.procs-test/an-agent-whose-store-lacks-a-cited-section-says-so-at-its-start`).

The program's vars among a task's cites (```seon.program/program-names```) are what its judge reads (```seon.judge/subject```'s `:cited`) and what `:no-references` asks for (```seon.problems/uncited-tasks```). A test the plan names is its proof (`:seon.task/proofs`), shown with its status, and the page leaves its cite out. Citing the function a proof calls is the cheapest way to a good context.

## An entity cites

A citation is an entity: `:seon.cite/of` the citing entity (a task or an agent), `:seon.cite/target` what it names, its depth and its place, drawn by its schema's renders, which read what it names from the db.

- Its attributes (``:seon.cite/cite-fact``): `:seon.cite/target`, a ref to a section, var, task, schema entry, namespace or file ([things relate by refs](data-modeling.md#things-relate-by-refs)), written as the lookup of its identity; `:seon.cite/depth` (`:summary`, `:doc`, `:code`); `:seon.cite/order`, its first mention's place; and `:seon.cite/id`, the content id `"<of> <target>"` (```seon.ref/target-value```). A page cited whole is its top section (```seon.ref/page-top```). A lookup to nothing is refused (`seon.task-test/a-cite-of-nothing-is-refused-naming-its-target`).
- An address may name its depth in its query, `path?depth#anchor` (`summary`, `lead`, `full`) (```seon.ref/address-citation```).
- The pull joins them on the task, its needs (one level) and the agent ([the pattern](ctx.md#the-pattern)), holding each target's identity alone, so ctx holds no cited content and the model is not sent it twice. A cited task is also held with what may be edited of it there (plan, instructions, proofs, cause), so a task can curate another by a `swap!` (`seon.ctx-test/a-cited-task-is-held-with-what-may-be-edited-there`).
- The page places one part per target, the task's, then its needs', then the agent's, each once at its strongest depth, proofs left out, sections after those they reference. Each part is ```seon.render.ui/cite```, a component reading its target by ``:seon.cite/target-read``'s pattern and its owner by ``:seon.cite/of-read``'s through ```seon.ctx/build```, so a change of a cited var or section redraws that part alone (`seon.ctx.wire-test/a-cited-section-main-changed-is-its-part-alone`). It is drawn by ```seon.render.ui/cite-hiccup```, one hiccup for the page and the model; ```my.agent/doc``` draws an on-demand cite by the same render.
- An agent is made citing ``:seon.config.agent/cites`` (```seon.task/agent-cites-tx```, written on its branch at ```seon.agent/begin-run!```) and changes its own: ```my.agent/cite!``` adds or deepens one, ```my.agent/uncite!``` retracts one (`seon.agent-test/an-agent-cites-what-it-reads-beside-its-task`).
- A cited var's part shows the newest ``:seon.config.ctx/callers`` callers; past that it names ``(my.agent/tests-reaching 'v)``.

## The wiki in the store

The wiki's sections are entities on main, so who cites a section or a var is a database query.

```seon.doc/load-tx``` parses the pages it is given and makes the db hold one `:seon.doc/fact` entity per heading: address, heading, lead, own text and parent. `:seon.doc/names` are the qualified names its text cites that name something; `:seon.doc/read` the form id of each such var when the text was written ([health](health.md#a-stale-section)); `:seon.doc/references` what its text cites, resolved (a link or `path#anchor` to its section, a name to its var). Backlinks are a query over `:seon.doc/references` (`seon.doc-test/backlinks-are-a-sections-instances`). ```seon.doc/load-dir-tx``` is the one load of `docs/`, and the one that retracts the sections of a page gone; root's boot writes it after the boot fact (`seon.system/wiki-tx`, [root](root.md#boot)), and `my.root/load-docs!` on demand.

Loading writes only what differs (the one `seon.task/entity-tx`), and an unchanged wiki answers `[]`. A link that reaches no section refuses the load with ``:seon.db/no-target`` naming it (`seon.doc-test/a-link-to-no-section-refuses-the-load-naming-it`); every page of the tree loads with none, checked on every land by ```test-runner/health!``` (its `with-wiki`), which refuses the landing on a refusal. A refusal at boot is a `:platform` error and the boot goes on. `seon.system/wiki-tx` takes a baseline from git for sections with no record of the code they were written against (```seon.system/wiki-baseline```). An unchanged reload costs a parse and a pull per section (`seon.doc-cost-test/an-unchanged-reload-costs-a-parse-and-a-pull-per-section`); Datahike's seek cost on Jolt is task ``seon.db/avet-seek-allocates``. The wiki is indexed by a seed or a re-index ([source](source.md#indexing-a-tree)).

## Sections in a task's context

The page shows each section the task, its needs or its agent cite as its own part, at the depth its cite asks ([an entity cites](#an-entity-cites)). Beside them, the task's data (```seon.ctx.status/task-data```, what ```my.task/data``` answers; cites by ```seon.task/task-cites```) holds `:write-set`, each program var it cites with its source, its callers (```seon.doc/callers```) and the sections citing it (```seon.doc/citing-sections```, at most `:seon.config.ctx/cited-by`, decisions last); `:sections`, each section by ```seon.doc/view```; and `:to-prove`, each proof with the form that runs it. Other helpers are the agent's to ask for (`my.agent/callees`, `my.agent/callers`, `my.agent/similar`).

## A section read at a depth, with its edges

Every page and section is on main and readable at three depths, each with the sections it cites and those citing it.
- ```seon.ref/shown``` is the one rule for a section at a depth; a summary whose lead holds more says so (`:seon.ref/left`, `:seon.ref/read-at`; `seon.ref-test/a-summary-names-the-citation-that-reads-the-rest`).
- ```seon.doc/view``` shows a section at any depth with its `:references` and `:cited-by` at summary, from the stored edges (`seon.doc-test/a-view-at-summary-lists-references-and-cited-by`), costing its section and edges, flat in the wiki's size (`seon.doc-cost-test/a-view-costs-its-section-and-its-edges`). ```seon.doc/page``` reads a page through it (`seon.doc-test/a-page-reads-at-a-depth`), its listing without edges (`seon.ctx-test/doc-reads-a-local-and-a-page-lists-its-sections-without-their-edges`). ```my.agent/help``` is that view, and on a miss lists the nearest addresses (```my.agent/help-nearest```), never nil (`seon.repl-test/doc-reads-any-address-and-lists-on-a-miss`).
- Every section has a lead; one without is a `:leadless-section` ([health](health.md#a-section-with-no-lead), `seon.doc-test/every-section-of-the-wiki-has-a-lead`).

## Files and namespaces on demand

A namespace a cite names joins its entity (`:ns/name`), and its part shows its doc and the form that lists its definitions (```my.agent/doc```); a file a text cites joins its entity (`:file/path`, or its namespace's), shown with the form that reads it. Only Seon's wiki pages are stored as sections. A backticked dotted symbol is a namespace candidate, kept by ```seon.ref/kept``` only when it names a namespace on the db or loaded (`seon.ref-test/a-namespace-is-read-as-a-namespace`); ```seon.ref/namespace-view``` shows it at the REPL by `seon.ref/shown`.
