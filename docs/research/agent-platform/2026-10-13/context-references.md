---
title: "Context references: kinds, forms, the current-state block, gaps, overengineered paths"
type: research
status: research, read-only; no source, schema or doc changed; no SCI run; no bin/seon; no Seon eval tools
date: 2026-10-13
basis: "HEAD refactor/agent-platform at fdc34a9ba; code read with sed and rg; git log and git log -1 for commit facts"
---

# Context references

Evidence tags: **[V]** read in the named code, commit or doc. **[I]** inference from [V] text; not probed. Code is cited by fully qualified symbol; file paths are checkout-relative and carry no line numbers.

The owner's model, as stated: a rough problem is approximated; references in it are identified and resolved; the task description is expanded, which adds references; each resolved reference becomes context the agent receives as data plus **forms it can evaluate to read more**. No context is built by hand.

---

## (A) READ THIS, in order

Code (one parser, one resolver, one block):

1. `seon.ref/document` and its `parsed`: one commonmark parse per text (cached by digest). Produces headings (with anchors), code spans, links, bare prose words containing `/`, first paragraph, fenced code blocks. [V]
2. `seon.ref/span-candidate`, `seon.ref/link-candidate`, `seon.ref/candidate`, `seon.ref/candidates`: turn each span, link or word into a candidate: a qualified name, a namespace name (resolve-only), a based path with optional anchor, a `:line-reference` problem, an `:anchor-missing` problem, or prose (nil). [V]
3. `seon.ref/references`: the entry point for a text. Held names first, held paths, then the text's candidates; `answer` each on the database; `cite-once` shows a repeated name once. Excludes the subject itself. [V]
4. `seon.ref/resolve-name` (identifies an entity by `seon.ref/identity-of`, pulls what `seon.ref/declarations` says identity needs, merges its declared render, then `seon.ref/inner`) and `seon.ref/resolve-path` (a document or section, via `seon.ref/section`, then its declared render and `seon.ref/inner` when it is Markdown). [V]
5. `seon.ref/brief` and `seon.ref/inner`: the one-level view. A name cited by a referent is shown as `brief`: its name, one summary line and one read form, never its content. [V]
6. `seon.ref/names`: the qualified symbols a text cites, for writers and indexers (`seon.task/*` writer, `seon.fn/*` docstring edges, `seon.context/step-start`). [V]
7. `seon.ref/section` (and `my.fs/section`, which only forwards to it): a Markdown section by `path#anchor`, its text, tokens and headings below it, or a `seon.ref/section-error` naming the nearest. [V]
8. `seon.ref/render-doc-ai`, `seon.ref/render-doc-html`: the declared render of a document or section. [V]
9. `seon.context/task-sections` and `seon.context/task-references`: the held task's intent and instructions, with `:seon.ref/names` set to its proofs, resolved once. [V]
10. `seon.context/chain`, `seon.context/step-before`, `seon.context/merged`, `seon.context/merged-families`: the subject, the steps before (with what their merge moved) and after. [V]
11. `seon.context/step-start`: the in-ns forms for the owner and for test namespaces its instructions cite. [V]
12. `seon.context/current-state`: the block's data. `seon.context/current-state-ai`: its AI view, must-reads through their declared renders. `seon.context/model-context`: help as system message, then the agent's exchanges, then the block last. [V]
13. `seon.bootstrap/help-value`, `render-help-ai`: the static REPL instructions; the reference lines the agent is taught. `seon.bootstrap/help` (macro), `seon.bootstrap/dir` and `seon.bootstrap/doc` (macros that expand to `clojure.repl/dir` and `clojure.repl/doc`). [V]
14. `seon.render.ns/function-ai`, `seon.render.ns/task-ai`, `seon.render.ns/render-ai` (namespace), `seon.render.ns/namespace-form`, `seon.render.test/render-ai`, `seon.render.test/render-form`, `seon.render.schema/render-ai`: the declared AI faces each kind displays. [V]
15. `my.program/source` (keyword: `seon.ref/schema-documentation`; simple symbol: namespace declarations; qualified: `declaration-source`), `my.program/search`, `my.task/refine!` (writes intent and instructions, then the writer derives references). [V]

Docs (read in this order):

16. `docs/seon/schedule/cards/TASK-CHAIN.md`, section "3. Shared functions, one prose path" (anchor `3-shared-functions-one-prose-path`) and its reference-kind table. The design: one parse, one candidate rule, one identity lookup, one declared render per kind. [V]
17. `docs/research/agent-platform/2026-10-13/prose-references.md`: identity kinds, which syntaxes name them, probes P1 to P22 on default. [V]
18. `docs/research/agent-platform/2026-10-13/link-statement-experiment.md`: a plain statement's words matched to references; cost 1 to 6 ms per `seon.ref/references`, `my.program/search` about 100 ms. [V]
19. `docs/research/agent-platform/2026-10-13/reference-reading-list.md`: source ranking for the reference work. [V]
20. `CLAUDE.md`, "Documentation is data" and "The right context is the product". [V]

Commits (oldest to newest; `git log --oneline -30 -- src/seon/ref.clj src/seon/context.clj` is the recent list):

21. `8ccdd694b`: `:seon/must-read` and `:seon/see-also` ingested as typed symbol edges. Later deleted. [V]
22. `37313a731`: every task attribute's references go through the one resolver (needs, evidence, instructions join intent and must-read). [V]
23. `b4814acf5`: one resolution rule; a test-naming reference shows the test source. [V]
24. `070c01736`: a must-read is the referenced thing's own content; callers and reaching tests become forms to run, not content (12,638 to 2,618 tokens on the merge task's block). [V]
25. `7b6163a07`: must-reads list the task's own proofs and count the rest; `:malli/schema` for contracts; `start` is a form. [V]
26. `8d14faeee`: a task is its name, intent with inline references, and optional needs; the writer derives `:seon.task/references`; must-read, evidence and instructions attributes deleted. [V]
27. `73968333e`: one reference rule for all prose; `seon.ref/docstring` replaces `seon.sci.lookup/docstring-parts`; `:seon/must-read` and `:seon/see-also` ingestion deleted; `seon.ref/render-doc-ai` introduced in part. [V]
28. `47c49ea43`: each reference carries the render its schema declares (`declared-render`); documents gain `render-doc-ai`. [V]
29. `3421834cb`: each must-read shows its declared render; `function-ai` offers callers and tests-reaching. [V]
30. `41bcd24d8`: a cited test's AI face shows its source lines (`seon.render.test/render-ai`). [V]
31. `f155c1494`: `seon.ref/references` shows a name once: at top level or in the first referent that cites it; later mentions are the name alone (`cite-once`). [V]
32. `1932ed7e3`, `3410f6ca8`: the chain in the block; instructions as a step's own work; `seon.task/effective`. [V]
33. `b74344579`, `25973cd04`: step proof and results judgment (most recent change to the block's proof sections). [V]

---

## (B) Reference-kind table

Syntax key. **BC** = backticked code span. **BW** = bare prose word, which is a candidate only if it contains `/`. **ML** = Markdown link destination. **AL** = anchor-only link (`#heading`). **PA** = `path#anchor` inside backticks.

"Accepted at" lists prose sites that call `seon.ref/references` or `seon.ref/names` (or `seon.ref/inner` one level). Sites are: task intent and instructions (writer and `my.task/refine!`), the proofs' names, function and test docstrings (one level, when cited), schema `:description` (one level, when cited), Markdown bodies (when a section is cited), namespace docstrings (summary only; see gap D.4).

| Kind | How it is written | Accepted at | Identity lookup (`seon.ref/identity-of`, `seon.ref/resolve-name`) | Render shown (declared pair) | Clojure form offered to read it (`seon.ref/brief`) |
|---|---|---|---|---|---|
| Function | BC `ns/name`; BW `ns/name` | task, docstring, schema description, Markdown | `:seon.fn/sym`; else library Var via `seon.ref/library-name` (loaded Var, or the dependency cache's definition, never loaded) | `seon.render.ns/function-ai` | `(my.program/source 'ns/name)`; at distance 0 `function-ai` adds `(my.program/callers 'x)`, `(my.program/tests-reaching 'x)`, and `(clojure.repl/source x)` when no source. Library Var not indexed: `(doc x)` |
| Deftest | BC `ns/name`; BW | task, proof names, docstring, Markdown | `:seon.test/sym` | `seon.render.test/render-ai` (source lines beyond distance 0; state; reach) | `(my.program/source 'ns/name)`; `seon.render.test/render-form` shows `(seon.test/recorded-result (seon.db/db) 'x)`, shown never run |
| Task | BC `ns/task-name` | task intent, instructions, needs | `:seon.task/sym` (handle `:seon.task/id` has no text form) | `seon.render.ns/task-ai` (intent and cited references beyond distance 0) | **none in `brief`**: it returns `{:seon.ref/name, :doc <first line of intent or instructions>}`. Only `seon.render.ns/task-form` (`(seon.task/obligations (seon.db/db) 'x)`) names a read, and `brief` does not use it. See gap D.2 |
| Schema key, attribute, error type, config dial | BC `:ns/key` | task, docstring, schema description, Markdown | `:seon.schema/key` (keyword with no schema key: `:unresolved`) | `seon.render.schema/render-ai` (definition with `:description`, usage report beyond distance 0) | `(my.program/source :ns/key)` (keyword branch uses `seon.ref/schema-documentation`) |
| Namespace | BC dotted bare `ns` (for example `my.agent`); not BW (no `/`) | task, Markdown; docstring-only via `seon.ref/namespace-name` | `:seon.ns/name`, or loaded on the host (`find-ns`); a dotted name is resolve-only: prose unless it names one | `seon.render.ns/render-ai` (name, requires, public functions beyond distance 0) | `(dir ns)` from `seon.ref/namespace-name` (unquoted). `seon.render.ns/namespace-form` builds `(dir 'ns)` (quoted). Two producers of one read, see gap D.2 |
| Document (Markdown or source/data file) | BC `docs/x.md`, `src/a/b.clj`, `deps.edn` (root entries), `reference-code/<project>/...`; first segment must be a checkout root or `reference-code`; or ML `./x.md` relative to the document | task, Markdown, docstring | `seon.ref/based-file` / `seon.ref/locate` (file exists in the loaded commit); no DB identity | `seon.ref/render-doc-ai` (declared on `:seon.ref/resolved-doc`); HTML: `seon.ref/render-doc-html` | `(my.fs/section "path")` |
| Document section | BC `path#anchor`; ML `path#anchor`; AL `#anchor` (same document); anchor is `IdGenerator` slug of the heading text | task, Markdown | heading with that anchor in `seon.ref/document`; a missing anchor is `:anchor-missing`, not prose | `seon.ref/render-doc-ai` (declared on `:seon.ref/section`) | `(my.fs/section "path#anchor")` |
| Pinned library document | BC `reference-code/<project>/path#anchor` | task, Markdown | `seon.ref/based-file` reads the gitlink pin (`seon.test.cache/gitlink-pins`) and the pinned extract; the reference carries `:seon.ref/pin` | `seon.ref/render-doc-ai` | `(my.fs/section "reference-code/...#anchor")` |

### What is NOT a reference, and what happens to it

| Text | Result | Where decided |
|---|---|---|
| Directory `src/seon`, `docs/` | nil: no entry, no problem | `seon.ref/answer` calls `seon.ref/directory?` |
| `tmp/...` path | Not refused. Resolves as a path if the file exists [V: probe P21 in prose-references.md reports `:unresolved`, so it is not resolved as a document in that probe]. CLAUDE.md says a reference never points into `tmp/`, and the code does not enforce it. See gap D.5 | `seon.ref/span-candidate` (no tmp rule) |
| `path:N`, `path:A-B` | Problem `:line-reference`, path kept. Not a reference | `seon.ref/line-citation` in `seon.ref/span-candidate`, only for a root-based path |
| URL, `https://...` | Not a reference: a URI with a scheme returns nil. Prose | `seon.ref/link-candidate` |
| Fenced code block | Not a reference. Collected as `:seon.ref/code-blocks`, never as spans | `seon.ref/parsed` (FencedCodeBlock branch); indented code dropped |
| Example code under `## Example` in a docstring | Not a reference (fenced) | same; `seon.ref/docstring` returns it as `:example` |
| Self-citation | Excluded from its own references | `seon.ref/references` drops `subject` |
| Unqualified backticked word `answer` | Prose (no dot, no slash) | `seon.ref/span-candidate` returns nil |
| Bare unknown word with `/` (`http/foo`) | Resolve-only: prose unless it names an entity | `seon.ref/answer` with `seon.ref/named?` |
| Dotted name that names nothing (`java.lang.String`) | Resolve-only: prose | same |
| Anchor-only link `#x` in text with no document | Problem `:anchor-missing` | `seon.ref/candidate` |
| Opaque handle (12-char task id, agent id `root`, message id) | Not a reference: no text form, no identity lookup | `seon.ref/identity-of` has no clause for them |
| Plain prose word without `/` (`message`, `stage`) | Not a candidate at all | `seon.ref/parsed` only collects words with `/` |
| Help text (`seon.bootstrap/help-value`) | Not parsed. Static lines | No `seon.ref` call in `seon.bootstrap` [V] |
| `:seon/tasks`, `:seon/must-read`, `:seon/see-also` metadata | Metadata, not prose. `:seon/must-read` and `:seon/see-also` ingestion deleted by `73968333e` | `seon.fn` task-links |

---

## (C) What the agent's first block contains

`seon.context/current-state` returns a map. Its AI view `seon.context/current-state-ai` keeps only these keys: `:seon.context/task`, `:chain`, `:proofs`, `:step`, `:send-backs`, `:must-reads`, `:cost`, `:unread`, `:turns`. [V] Empty sections are absent. [V: "Sections are absent when empty", the docstring of `seon.context/current-state`]

Section by section:

1. **`:seon.context/task`**: `{:seon.task/sym, :seon.task/intent, :seon.task/instructions}` of the held task, the effective one (`seon.task/effective`: subject goal merged with its own declaration). The handle `:seon.task/id` is removed in the AI view. [V] Contains references in its text; they are resolved in section 5 below.
2. **`:seon.context/chain`** (only when the task is a step): `:seon.context/subject` (the goal's owner symbol), `:seon.context/before` (vector of `{:seon.task/sym, :seon.task/step, :seon.task/outcome :merged, :seon.context/merged}`; `:seon.context/merged` is what that step's merge moved, one `seon.ref/brief` per name, grouped by `:seon.context/functions`, `:seon.context/schemas`, `:seon.context/tests`, `:seon.context/tasks`), and `:seon.context/after` (vector of `{:seon.task/sym, :seon.task/step, :seon.task/instructions}`). [V] No forms here: these are data.
3. **`:seon.context/proofs`**: `{:seon.context/counts, :seon.context/proof-list}`. Each proof entry: `:seon.test/sym`, `:seon.context/proof-status` (`:pending`, `:red`, `:green`), `:seon.context/proof-failures` when red, and the proof's resolution (its `:seon.test/source` and render keys) merged in by `seon.context/task-sections`. [V]
4. **`:seon.context/step`**: `{:seon.task/status, :seon.task/step, :seon.source/refusals, :seon.context/red-tests, :seon.context/product-tests, :seon.context/start}`. `:seon.context/start` is `{:seon.render/operations [(in-ns 'owner) (in-ns 'test-ns) ...]}`: forms to evaluate, built by `seon.context/step-start` from the owner namespace of the task symbol and the namespaces of tests its instructions cite (`seon.ref/names`). [V]
5. **`:seon.context/must-reads`**: a vector of references the task's intent and instructions cite (resolved by `seon.ref/references` with `:seon.ref/names` set to the proofs), minus those already shown as proof resolutions. Each entry is a resolved reference: `:seon.ref/name` plus the entity's keys (for a function: `:seon.fn/sym`, `:seon.fn/source`, `:malli/schema`, `:seon.ref/pin` for a library), its declared AI render (through `seon.context/must-read-ai`, which calls `seon.render/render-ai` one hop beyond distance), and `:seon.ref/references`, the names its own content cites, one level, each a `brief`. [V] A problem entry carries `:seon.ref/problem-kind` and `:seon.ref/message`. [V]
6. **`:seon.context/send-backs`**: the latest send-back to this task with its reason. [V] Only when non-empty.
7. **`:seon.context/cost`**: the branch's stored function costs differing from main's (`seon.context/branch-cost`). [V]
8. **`:seon.context/unread`**: `{:seon.context/items [...], :seon.render/operations ['(my.message/inbox)]}`. Undelivered messages, each with id, sender, instant and content. [V]
9. **`:seon.context/turns`**: `{:my.agent/turns-left n, :seon.task/status (:done|:capped|:working), :seon.context/namespace}`. [V]

The reference forms appear in section 5 (`:seon.context/must-reads`), and in section 2 as `:seon.context/merged` briefs (`seon.ref/brief` of a moved name).

### Top-level must-reads versus nested one-level briefs

- A **top-level must-read** is a reference the task itself cites: rendered whole through its declared render (function source, test source, schema form, document heading and section operation). It is a `resolved` entry. [V]
- A **nested one-level brief** is a reference cited by a must-read's own content (docstring, intent, instructions, schema description, Markdown body). It is `brief`: name, one summary line and one read form (`(my.program/source 'x)`, `(doc x)`, `(dir x)`, `(my.fs/section "...")`), never content. It is not rendered further. [V] `seon.ref/cite-once` shows a name once; later mentions are `{:seon.ref/name n}` alone. [V]
- A function's own callers and tests reaching it are not pulled in: they are forms in `:seon.render/operations` (`(my.program/callers 'x)`, `(my.program/tests-reaching 'x)`), to run. [V] per commit `070c01736`.

### Concrete example (test fixture)

`test/seon/context_test.clj`, `current-state-shows-the-current-task-declaration` asserts the task section for a task created with intent `"First."`:

    {:seon.task/sym 'tq.cs/shape :seon.task/intent "First."}

This is the `:seon.context/task` section with the handle removed. [V] Other test expectations: `current-state-shows-only-its-sections` asserts that a held-nothing agent's block holds only `:seon.context/turns`. [V] The must-read shape is asserted by `test/seon/context_test.clj` lines around `must-reads` (fixtures read `:seon.ref/name` and `:seon.task/intent`). [V] No test in the tree writes a whole block as a literal fixture; shapes are checked key by key.

### Where the block reaches the model

`seon.context/model-context` builds the request: system = `(help)` rendered (`seon.bootstrap/help-value`), then the agent's exchanges (`:seon.context/exchanges`), then the block last, rendered with request function `seon.context/current-state`. [V] Whether `current-state-ai` is the function the renderer dispatches to for this block is [I] (it is the only AI view of the block; the dispatch was not traced).

---

## (D) Gaps and duplicates

### D.1 Kinds the spec lists that the code handles only in part

Spec: `docs/seon/schedule/cards/TASK-CHAIN.md` section 3 table. [V]

- **Per-kind display branches still exist.** The spec says `seon.ref/resolve-name` "only identifies the entity; delete its per-kind display branches." [V] Code: `resolve-name` still branches on identity attribute, keyword, simple symbol and library, and merges a render per branch; `seon.ref/brief` branches again on simple symbol, keyword, and the fn/test/task pulls, with its own read forms. [V] The render itself is declared (`declared-render`), but the branch structure is not gone.
- **Task brief has no read form.** The spec's table gives Task the display `seon.render.ns/task-ai`. In `seon.ref/brief`, a task returns only `:doc` and no `:seon.render/operations`. [V] So a task cited from another task's text gives the agent no form to read its obligations (`seon.render.ns/task-form` exists but is not used there). [V]
- **Help is not a prose site.** The spec's row "every function/test/namespace docstring, schema descriptions, documents and help" lists help. `seon.bootstrap/help-value` contains static text and no `seon.ref` call. [V] The help's own backticked references (`seon.db/q`, `docs/seon/specs/merge.md#1-the-merge-request`) are shown raw, not resolved.
- **Namespace docstrings are not one-level.** `resolve-name` for a namespace returns the name, requires and render; it does not call `inner`. [V] `own-prose` includes `:seon.ns/doc` only for the identity-pulled entity path. [I] A namespace's docstring citations are therefore not one-level resolved. The spec requires "any docstring".
- **Deftest and task docstrings: unverified.** The pull in `resolve-name` includes `:seon.fn/doc` and `:seon.ns/doc`, and `own-prose` reads intent and instructions. [V] Whether a test's docstring is cited one level is [I] (not probed).
- **`tmp/` paths are not refused.** [V] The rule is in CLAUDE.md and the spec ("only files/anchors, never gitignored `tmp/` paths"). Code has no tmp clause in `span-candidate`.
- **Root-level files resolve as documents.** `roots` in `seon.ref/candidate` is the directory listing of the checkout root, which includes files. [V] So a backticked `deps.edn` is a based path to a file and goes to `resolve-path` (its text is code, so no `inner`). The spec's A5 test expects `deps.edn` to remain prose. [I] Probe needed.
- **`seon.fn/references-by-caller`**: the commit `73968333e` says it unions docstring names into `:seon.fn/references`. Not read in this pass. [I]

### D.2 Duplicated decisions

- **Identity lists, three hand copies.** `seon.ref/identity-of` lists `[:seon.fn/sym :seon.test/sym :seon.task/sym]` and `[:seon.schema/key]` and `[:seon.ns/name]`. `seon.context/merged-families` maps `:seon.fn/sym`, `:seon.schema/key`, `:seon.test/sym`, `:seon.task/sym` to family keys. `src/seon/cluster.clj` holds a set `#{:seon.ns/name :seon.fn/sym :seon.schema/key :seon.test/sym}` near line 2012 (grep, not read). [V] Each is an identity list; the schema already declares identity (`:seon.db/identity`, per `prose-references.md` part 1). [V]
- **Two resolve-only rules.** `seon.ref/names` keeps a dotted or word candidate only if its namespace's source file exists in `src`, `test` or `script` (`sourced?`, a filesystem probe). `seon.ref/references` (via `seon.ref/answer`, `seon.ref/named?`) keeps it if it names an entity by index or a loaded namespace or Var. [V] Divergence: a namespace stored in the database but without a source file is cited by `references` and not by `names`. [I] The task writer uses `names`, the block uses `references`.
- **Two summary splitters.** `seon.ref/docstring` (with the abbreviation rule in `first-sentence`) and `seon.render.ns/first-doc-line` (splits on the first `". "`, no abbreviation rule). [V] `seon.ref/brief` uses the first; `seon.render.ns/render-ai` and `function-ai` use the second. The spec says one splitter (commit `73968333e`). The second was not deleted.
- **Two forms for namespace `dir`.** `seon.ref/namespace-name` and `seon.render.ns/agent-namespace-form` (its `:else` branch) emit `(dir ns)` unquoted; `seon.render.ns/namespace-form` and `seon.render.ns/render-alias-form` emit `(dir 'ns)` quoted. Tests expect the quoted form (`test/my/turn_test.clj`, `test/seon/render/history_test.clj`). [V] Which one works in SCI is not probed [I]; the `seon.bootstrap/dir` macro passes its argument to `clojure.repl/dir` unchanged.
- **Two reads of the same instructions.** `seon.context/task-references` resolves intent and instructions once through `seon.ref/references`. `seon.context/step-start` calls `seon.ref/names` on the instructions again and then one `db/pull` per name to find test namespaces. [V]
- **Held task read twice per block.** `seon.context/current-state` calls `held-task`, and `seon.context/task-sections` calls `held-task` again. [V]
- **Roots listed per call.** `seon.ref/references`, `seon.ref/inner` and `seon.ref/names` each list the checkout directory (`(.list (io/file (fs/source-directory)))`) on every call. [V]
- **Identity probed twice.** `seon.ref/answer` calls `seon.ref/named?` (an index probe) for a resolve-only candidate, then `seon.ref/resolve-name` probes the index again through `seon.ref/identity-of`. [V]

### D.3 Layers that only forward

- `my.fs/section` forwards to `seon.ref/section`. [V] CLAUDE.md "my.* thin" allows this, but it is a whole layer for one call.
- `seon.bootstrap/dir`, `seon.bootstrap/doc`, `seon.bootstrap/help` are macros that expand to `clojure.repl/dir`, `clojure.repl/doc` or a `help-value` call. [V] `seon.sci.lookup` and `seon.sci.eval` inject `doc` and `dir` vars by name. [V]

### D.4 Other gaps

- **`my.program/doc` does not exist.** `(doc x)` is `seon.bootstrap/doc`, a macro over `clojure.repl/doc`. The owner's `(doc x)` form is that macro. [V]
- **Library Var reads by `doc` need the Var loaded or cached.** `seon.ref/library-name` returns `:unresolved` when neither the Var is loaded nor the dependency cache holds it. [V]
- **Bare namespace names do not resolve.** Only backticked dotted names are candidates (a bare word needs `/`). [V] The spec says "backticked bare symbol", so this matches the spec.
- **Problem messages are instructions in data.** `seon.ref/problem-messages` tells the agent to call `my.message/send` to tell root. Correct by design [I], but the message strings are the only place the rule lives.

---

## (E) History

Commits named by the owner, read by message:

- `73968333e` "One reference rule for all prose": `seon.ref/document`, resolve-only words, `:seon.ref/subject`, directories excluded, Markdown-only prose. Deleted `seon.sci.lookup/docstring-parts` (replaced by `seon.ref/docstring`), `:seon/must-read` and `:seon/see-also` ingestion, and the `must-read` referrer rows. Heading sweep: 107 headings in 32 files to `## Name`. [V]
- `47c49ea43` "Each reference carries the render its schema declares": `declared-render`, `seon.ref/render-doc-ai`, `seon.ref/render-doc-html`. [V]
- `3421834cb` "Each must-read shows its declared render; function-ai offers callers and tests-reaching": `seon.context/must-read-ai` calls `seon.render/render-ai` one hop beyond distance. [V]
- `41bcd24d8` "A cited test's AI face shows its source lines": `seon.render.test/render-ai` gets `:seon.render.ns/source-lines` beyond distance 0. [V] (The commit exists; the earlier error was a shell quoting issue.)
- Must-read and see-also deletion: `8d14faeee`'s message says must-read, evidence and instructions attributes are deleted; `instructions` returned later as a step's own work (`3410f6ca8`, chain H2). `37313a731` joined needs, evidence and instructions to the one resolver; `070c01736` made a must-read the referenced thing's own content, and callers and reaching tests into forms. `8ccdd694b` had ingested `:seon/must-read` and `:seon/see-also` as edges; `73968333e` deleted that ingestion. [V]
- `b4814acf5`: one resolution rule; a test-naming reference shows the test's source. [V]
- `f155c1494`: `cite-once` (a name shows once). [V]

Docs by date: the prose-references, link-statement and reference-reading-list notes (all 2026-10-13) are the research behind the one rule. [V]

---

## (F) Open questions for root

1. Probe `(dir x)` and `(dir 'x)` in SCI on default: which one reads a namespace? Two producers exist (D.2).
2. Does `deps.edn` (root-level file, backticked) resolve as a document today? Expected per the spec: prose. Code says it resolves as a based path (D.1).
3. Should a task brief carry a read form? The spec says `seon.render.ns/task-ai`; `brief` returns none. Candidate form: `(seon.task/obligations (seon.db/db) 'x)`, or `(my.program/source 'x)` for the task's declaring function. Owner decision.
4. Is help a prose site? The spec row says yes; code says no.
5. Should `seon.ref/names` use the index like `references` does (D.2, two resolve-only rules)?
6. Does `seon.render/render-ai` dispatch the block to `seon.context/current-state-ai`? Not traced.
7. Is the `seon.bootstrap/help-value` pull of `:seon.fn/source` (discarded result) needed for dependency evidence? The code comment says it "records its code version"; the result is unused. Verify before deleting (G.15).
8. Test and task docstrings: are their references resolved one level? Not probed.
9. `seon.fn/references-by-caller` (the docstring edge): not read in this pass.

---

## (G) Overengineered paths

Each item: the symbol, what it does, the simpler form, rough lines removable. **[V]** = the code shape is read; **[I]** = inferred from the reading, not measured. Line counts are estimates from the function bodies, not diffs.

1. **`seon.ref/section`**: reads and tokenizes the whole file and the headings below for every call; `seon.ref/resolve-path` then dissolves `:seon.ref/text`, `:seon.ref/below` and `:seon.ref/tokens` (dissoc) for the document entry. [V] Simpler: find the heading in `seon.ref/document` headings and read the body only for `my.fs/section`. About 20 lines removable. [I]
2. **Three identity lists** (`seon.ref/identity-of`, `seon.context/merged-families`, the set in `src/seon/cluster.clj` near line 2012). [V] Simpler: derive from the schema's identity declarations, as `seon.ref/declarations` already does. About 15 lines removable across the three. [I]
3. **`seon.ref/brief` and `seon.ref/resolve-name`**: two per-kind dispatches, each with its own pulls and read forms; `namespace-name` duplicates the namespace branch of `resolve-name`. [V] Simpler: `resolve-name` once; `brief` projects its result (name, first line, read form from the declared render). About 40 lines removable. [I]
4. **`seon.ref/names` `sourced?`**: a filesystem probe over six `src`/`test`/`script` by `.clj`/`.cljc` combinations for each cited dotted name, and a second resolve-only rule (D.2). [V] Simpler: `named?` from `seon.ref/references`. About 12 lines removable and one rule instead of two. [I] It also breaks the "no name guessing" law in CLAUDE.md. [V]
5. **Roots listed per call** in `seon.ref/references`, `seon.ref/inner`, `seon.ref/names`. [V] Simpler: compute once per source directory in a `seon.cache` kind keyed by the source commit. About 3 lines plus repeated I/O removed. [I]
6. **`seon.ref/answer` with `seon.ref/named?`**: an index probe, then `resolve-name` probes again. [V] Simpler: `resolve-name` returns nil for a resolve-only name that names nothing. About 8 lines. [I]
7. **Exceptions as classification in `seon.ref/span-candidate` and `seon.ref/link-candidate`**: `try`/`catch` of `Exception` around `edamame/parse-string-all` ("a span that does not read is prose") and of `URISyntaxException`. [V] Simpler: a span is a candidate only if its first character can start a symbol; a URI that does not parse is prose. About 6 lines and no exception path. [I] CLAUDE.md "NO GUARDS OR FENCES" applies to the catch-as-filter pattern. [V]
8. **`seon.ref/candidates`**: a dedupe keyed by an identity tuple, with an explicit set and a first-of reduce, to implement "one is resolve-only only when every mention is". [V] Simpler: mark resolve-only at parse, once per mention, and keep the first mention. About 8 lines. [I]
9. **`seon.ref/cite-once`**: a nested reduce over answers and their briefs. [V] Simpler: one pass over a flattened list of names. About 8 lines. [I] Low priority.
10. **`seon.render.ns/first-doc-line`** and `seon.ref/docstring`: two summary functions (D.2). [V] Simpler: delete `first-doc-line` and use `seon.ref/docstring`. About 10 lines. [I]
11. **`seon.render.ns/function-ai` text sniffing**: `(str/includes? source line)` decides whether the doc is shown, and `(str/includes? source ":malli/schema")` decides whether the spec is shown. [V] Simpler: a fixed rule (show the doc only when there is no source; show the spec only when there is no source). About 4 lines and no string search of source. [I] CLAUDE.md forbids "text parsing" for identity; this is display, but the same law applies. [V]
12. **`seon.context/current-state` and `seon.context/task-sections`**: `held-task` read twice per block [V]. Simpler: pass `held` into `task-sections`. One query and about 3 lines per block. [I]
13. **Proofs resolved twice**: `seon.context/task-references` resolves the proof names through `seon.ref/references`, then `seon.context/proofs` gives each proof its status, and `task-sections` merges the resolution into each proof and removes it from the must-reads. [V] Simpler: resolve the proofs once and derive both the proof entry and the must-read split from that result. About 10 lines. [I]
14. **`seon.context/current-state-ai`**: a hand list of nine keys in `select-keys` that duplicates the keys `current-state` builds, and a `dissoc` of the handle. [V] Simpler: the block holds only these sections, so `current-state` builds them and the AI view is the identity, with the handle removed where it is built. About 8 lines. [I]
15. **`seon.bootstrap/help-value`**: the first `db/pull` of `:seon.fn/source` for the help function has its result discarded; the comment says it "records its code version in the same dependency evidence". [V] Simpler: delete it if dependency evidence is recorded elsewhere. 3 lines. [I] Owner check needed. The 60 lines of help text are rebuilt on every call; they are data and could be a `def`. [V]
16. **`seon.context/step-start`**: a second read of instructions (`seon.ref/names`) and one `db/pull` per name to decide test namespaces, already resolved by `task-references`. [V] Simpler: take the test namespaces from the resolved references (the deftest entries). About 10 lines. [I]
17. **`seon.ref/namespace-name` and `seon.render.ns/namespace-form`**: two producers of the `dir` read form (unquoted and quoted) (D.2). [V] Simpler: one `namespace-form` function. About 5 lines. [I]
18. **Forward-only layers**: `my.fs/section` to `seon.ref/section`; `seon.bootstrap/dir`, `doc` and `help` macros. [V] Simpler: bind the `clojure.repl` vars and the section function directly in the SCI namespace map, if injection allows. About 10 lines. [I] Check `seon.sci.eval` injection before removing.
19. **`seon.ref/nearest-files`**: climbs parent directories until one exists, to list nearby Markdown files in an error. [V] Simpler: list the parent of the path only. About 8 lines. [I] Low priority.
20. **`seon.context/step-before` and `seon.context/merged`**: each chain step before the held task runs a query over all attempts of that step to find the latest merge, and then `source/merge-accepted` to find what it moved. [V] This is proportional to chain length, so it is acceptable; flagged so cost is measured, not removed. [I]

Rough total: about 170 to 220 lines removable across G.1 to G.19, inferred. Largest single items: G.1 (section read), G.3 (brief and resolve-name), G.2 (identity lists). None of these was measured; the owner's rule is that a design is judged by measured cost, so each needs a measured before and after on a real block before it is committed.
