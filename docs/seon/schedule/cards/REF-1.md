---
title: "Card REF-1: One reference resolver for intents, Markdown docs and docstrings"
type: reference
status: done (a807ec0b4)
created: 2026-10-08
---

# REF-1: One reference resolver for intents, Markdown docs and docstrings

Landed (`a807ec0b4`). The task text it resolves is `:seon.task/plan` once TASK-CHAIN S1a renames `intent`; the resolver is
unchanged ([TASK-CHAIN](TASK-CHAIN.md) §3).

Depends on: CS-01c2 (the library fallback in `must-read`, which moves here whole). Group D. Model: Sonnet.

Owner: "markdown as standard for docs ... parse those for other references and just use the same rules everywhere."
Model: [references.md](../../../research/agent-platform/2026-10-08/references/references.md), adopted
(`review/owner-rulings.md` "References"). Every text that can carry a reference (a task intent, a Markdown doc, a
docstring) is Markdown, so one parse finds the candidates in all three: a code span is a candidate reference, a link
with a relative target is a file reference. One function answers each candidate resolved or as a problem. The block
(CS-01c1..c3), ingestion (TASK-CHECK) and the edit hook (REF-2) call it; nothing parses references a second way.

## Files

- `src/seon/ref.clj` (new)
- `resources/seon/schemas/seon.ref.edn` (new)
- `src/seon/context.clj`, `resources/seon/schemas/seon.context.edn`, `test/seon/context_test.clj` (callers converted)
- `test/seon/ref_test.clj` (new)
- `deps.edn`
- delete: `script/seon/dev/citations.clj`, `script/seon/dev/citations_test.clj`

## What is true at HEAD (probed on default, JVM mode, commit `6a244149d` loaded)

- `seon.context/referenced-names` (CS-01c1, uncommitted in lane cs01c1) finds backticked
  spans with `(str/split intent #"`" -1)` and reads each with `edamame/parse-string`; `seon.context/must-read`
  resolves a symbol (function, test, namespace) or keyword (`seon.sci.eval/schema-documentation`). This is
  the start; it moves here.
- `io.github.nextjournal/markdown 0.7.213` (`deps.edn`) wraps `org.commonmark/commonmark 0.24.0`
  (its POM). Read in `~/.m2/repository/io/github/nextjournal/markdown/0.7.213/markdown-0.7.213.jar`:
  `nextjournal.markdown.impl/parser` builds a commonmark `Parser` without source spans, so its data
  carries no line positions; its heading ids (`nextjournal.markdown.impl.utils/text->id+emoji`) keep punctuation
  (`"5.-proofs-and-references-(coordinator-items)"`), while the 216 heading links already in `docs/` and
  `.agents/` use GitHub's form (`plan/README.md#7-decisions-and-proof-gates`).
- Probe, `docs/seon/schedule/agent-loop-ledger.md` (248 KB): commonmark with
  `(.includeSourceSpans builder IncludeSourceSpans/BLOCKS)` parses it in 10 ms warm (16 ms first) and gives each
  `Heading` its level and `(.getLineIndex (first (.getSourceSpans h)))`; `nextjournal.markdown/parse` takes 40 ms
  warm. Both read YAML front matter as a level-2 setext heading (`"title: … created: …"`), so front matter needs
  commonmark's front-matter extension.
- `script/seon/dev/citations.clj` hand-scans backticks (`spans`, `:14`) and fences (`blocks`, `:33`) for `path:N`
  citations. Its only caller is the git pre-commit hook its docstring names, which is not installed
  (`.git/hooks` holds only `commit-msg.disabled-2026-09-23`; `core.hooksPath` unset).
- clj-kondo analysis of one source file gives each var's `:doc` (`:var-definitions`) and the namespace's `:doc`:
  `src/seon/task.clj`, 52 definitions, 51 with a docstring, 145 ms (`clj-kondo.core/run!`, `:cache false`).

## Dependencies

Add, at the version nextjournal already brings:

```clojure
org.commonmark/commonmark-ext-heading-anchor {:mvn/version "0.24.0"}
org.commonmark/commonmark-ext-yaml-front-matter {:mvn/version "0.24.0"}
```

First probe after adding them (stop and report if it fails, do not write a slug function):
`(.generateId (.build (org.commonmark.ext.heading.anchor.IdGenerator/builder)) "7. Decisions and proof gates")`
must answer `"7-decisions-and-proof-gates"`, and `"2. First slices (each ≤ ~100 added lines, net far negative)"`
must answer `"2-first-slices-each--100-added-lines-net-far-negative"` (the anchor
`docs/research/agent-platform/cut-analysis-2026-09-23.md` links to). A dependency-class miss is filled by `start --head`
(AGENTS.md "Operate through `bin/seon`"); say RESET NEEDED in the landing note.

## Functions (`seon.ref`)

```clojure
(defn document
  "Markdown text's headings and reference candidates, parsed once per content digest."
  {:malli/schema [:=> [:cat :string] :seon.ref/document]}
  [text] ...)
```

`(seon.cache/lookup ::document (seon.schema/sha-256 [(.getBytes text "UTF-8")]) weigh derive)`, weigh
`#(* 64 (+ (count (:seon.ref/headings %)) (count (:seon.ref/spans %))))`. Derive: one
`Parser` built once (a `def`) with `includeSourceSpans BLOCKS` and the YAML front-matter extension; walk the
`Document` with commonmark's `AbstractVisitor`:

Lines are 1-based file lines (commonmark's `getLineIndex` plus one) in every answer, so a finding names the line an
editor shows.

- `Heading` → `{:seon.ref/level n :seon.ref/heading text :seon.ref/anchor id :seon.ref/line i}`, text by
  `TextContentRenderer`, id by one `IdGenerator` per document (it numbers duplicates as GitHub does).
- `Code` (a code span) → `{:seon.ref/span literal :seon.ref/line i}` (the enclosing block's line).
- `Link` → `{:seon.ref/link destination :seon.ref/line i}`.
- `FencedCodeBlock` and `IndentedCodeBlock` contribute nothing (code is not a reference).
- The document's line count, for CS-01c5's section ends.

```clojure
(defn- candidate
  "One span or link read as a reference: a qualified name, a based path with an optional anchor, a line citation, or nil (prose)."
  {:malli/schema [:=> [:cat [:set :string] [:maybe :string] [:or :seon.ref/span-entry :seon.ref/link-entry]]
                  [:maybe :seon.ref/candidate]]}
  [bases document-path entry] ...)
```

The same rules for every source:

- A link's destination is parsed by `java.net.URI` (RFC 3986). A scheme (`https:`, `mailto:`) → nil. A relative one
  resolves against the document's directory (`.resolve` on the document path's URI) when the source has a
  `:seon.ref/document-path`, else against the checkout; `#anchor` alone names the document itself.
- A code span whose first segment (before the first `/`, `#` or `:`) is in `bases` is a path: `java.net.URI` gives
  path and fragment (`:seon.ref/anchor`). `bases` is the set of entries at the loaded source directory's root
  (`(set (.list (io/file (seon.fs/source-directory))))`, read once per call of `references`), so `docs`, `src`,
  `AGENTS.md`, `.agents` and `reference-code` are bases because they exist, never by a hand list. A path ending in
  `:N` or `:A-B` is a line citation, the problem `:line-reference` (references.md rule 1: code is never a path).
- Any other span: `edamame.core/parse-string`; a qualified symbol or qualified keyword is a name; anything else is
  prose (nil).

```clojure
(defn references
  "Each reference `source`'s text makes, in first-seen order, resolved on `database` or as a problem."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.ref/source]
                  [:vector [:or :seon.ref/resolved :seon.ref/problem :seon.error/value]]]}
  [database {:seon.ref/keys [text names document-path]}] ...)
```

`names` (optional) are names the caller holds as data (a task's `:seon.task/must-read`), placed first, sorted, then
the text's candidates, `distinct` by name or path plus anchor. A name resolves through the moved `must-read` body
(renamed `resolve-name`, private, unchanged behaviour: function, test, namespace, schema key, CS-01c2's library Var).
A path resolves through CS-01c3's `resolve-path` once that lands; until then a path is `:seon.ref/problem
:unresolved` with no `resolve-path` call. Source kinds need no tag: an intent and a docstring are `{:seon.ref/text s}`,
a doc is `{:seon.ref/text s :seon.ref/document-path "docs/…/x.md"}`.

Delete from `seon.context`: `referenced-names` and `must-read`. `current-state` calls
`(ref/references database {:seon.ref/text (:seon.task/intent held) :seon.ref/names (:seon.task/must-read held)})`.

## Schemas (`seon.ref.edn`)

The block entries move with their producer (AGENTS.md "Declare facts at their producer"): CS-01c1's
`:seon.context/name`, `:seon.context/must-read`, `:seon.context/name-problem`, `:seon.context/problem`,
`:seon.context/schema-form` become `:seon.ref/name`, `:seon.ref/resolved`, `:seon.ref/problem`,
`:seon.ref/problem-kind`, `:seon.ref/schema-form`; `:seon.context/must-reads` keeps its name and holds
`[:vector [:or :seon.ref/resolved :seon.ref/problem]]`. One script renames the keys across `context.clj`,
`seon.context.edn` and `context_test.clj`; lint and load.

```clojure
{:name [:or :qualified-symbol :qualified-keyword]
 :path [:string {:min 1}]
 :anchor [:string {:min 1}]
 :line [:int {:min 1}]
 :heading-entry [:map [:seon.ref/level [:int {:min 1 :max 6}]] [:seon.ref/heading :string]
                 [:seon.ref/anchor :seon.ref/anchor] [:seon.ref/line :seon.ref/line]]
 :span-entry [:map [:seon.ref/span :string] [:seon.ref/line :seon.ref/line]]
 :link-entry [:map [:seon.ref/link :string] [:seon.ref/line :seon.ref/line]]
 :document [:map [:seon.ref/headings [:vector :seon.ref/heading-entry]]
            [:seon.ref/spans [:vector [:or :seon.ref/span-entry :seon.ref/link-entry]]]
            [:seon.ref/line-count :seon.ref/line]]
 :candidate [:or [:map [:seon.ref/name :seon.ref/name]]
                 [:map [:seon.ref/path :seon.ref/path] [:seon.ref/anchor {:optional true} :seon.ref/anchor]]]
 :source [:map [:seon.ref/text :string] [:seon.ref/names {:optional true} [:set :seon.ref/name]]
          [:seon.ref/document-path {:optional true} :seon.ref/path]]
 :problem-kind [:enum :unresolved :anchor-missing :line-reference]
 :problem [:map [:seon.ref/problem-kind :seon.ref/problem-kind] [:seon.ref/line {:optional true} :seon.ref/line]
           [:seon.ref/name {:optional true} :seon.ref/name] [:seon.ref/path {:optional true} :seon.ref/path]
           [:seon.ref/anchor {:optional true} :seon.ref/anchor]]
 :resolved [:map [:seon.ref/name {:optional true} :seon.ref/name] [:seon.ref/line {:optional true} :seon.ref/line]
            [:arglists {:optional true} [:sequential [:vector :symbol]]] [:seon.fn/spec {:optional true} :seon.fn/spec]
            [:doc {:optional true} :string] [:seon.ref/schema-form {:optional true} :seon.schema/value]
            [:seon.render/operations [:vector :seon.render/read-form]]]}
```

CS-01c3 adds the path members to `:resolved`. `:anchor-missing` is produced from CS-01c3 on.

## Returned shape

```clojure
(seon.ref/references db {:seon.ref/text "Use `seon.task/home`, see [ledger](agent-loop-ledger.md#5-critical-path) and `src/seon/task.clj:381`."
                         :seon.ref/document-path "docs/seon/schedule/README.md"})
;; =>
[{:seon.ref/name seon.task/home :seon.ref/line 1 :arglists ([database]) :doc "The connection tasks live on, …"
  :seon.render/operations [(doc seon.task/home) (clojure.repl/source seon.task/home)]}
 {:seon.ref/problem-kind :unresolved :seon.ref/path "docs/seon/schedule/agent-loop-ledger.md"
  :seon.ref/anchor "5-critical-path" :seon.ref/line 1}           ; a path until CS-01c3
 {:seon.ref/problem-kind :line-reference :seon.ref/path "src/seon/task.clj" :seon.ref/line 1}]
```

## Proof

`test/seon/ref_test.clj`, through `seon.mcp/call` as the cards' common rule says (the value read back with
`test-support/reply`), each asserting data:

- `seon.ref-test/one-text-yields-names-paths-and-line-citations-by-the-same-rules`: the text above as an intent
  (no document path) and as a doc; the vector of `(juxt :seon.ref/name :seon.ref/path :seon.ref/problem-kind)` is the
  same except the link's path, which resolves against the document's directory for the doc only.
- `seon.ref-test/fenced-code-and-prose-spans-are-not-references`: a fenced block holding `` `seon.task/home` `` and a
  span `` `(+ 1 2)` `` answer `[]`.
- `seon.ref-test/front-matter-is-not-a-heading`: `(:seon.ref/headings (seon.ref/document "---\ntitle: x\n---\n# A\n"))`
  is one heading, anchor `"a"`, line 4.
- `seon.ref-test/a-repeated-parse-is-a-cache-hit`: `test-support/armed-calls` on the derivation shows one call for
  two `document` calls on the same text.
- CS-01c1's two block tests keep passing with the renamed keys.

`--test seon.ref-test --test seon.context-test --test seon.sci.documentation-test`.

## Cost

`document`: one commonmark parse, proportional to the text (10 ms for 248 KB warm), then a cache hit (one SHA-256 of
the text, about 1 ms per MB). `references`: one directory listing plus one entity lookup per name; a task intent is
microseconds of parsing. Measure `references` over `docs/seon/schedule/agent-loop-ledger.md` on default and put the
number in the landing note; over 1 s names the responsible function.

## Deleted

- `seon.context/referenced-names`, `seon.context/must-read` (moved, callers converted).
- `script/seon/dev/citations.clj`, `script/seon/dev/citations_test.clj`: a second backtick and fence scanner with no
  installed caller; its `path:N` class is now the `:line-reference` problem. Skills still carry `path:N` citations;
  REF-2's sweep lists them for their owners (AGENTS.md "Verify touched skill claims against `file:line`" reads as the
  verification step, not a reference form; root rules if the two conflict).
- Not deleted here: `script/seon/dev/markdown.clj`'s link and wikilink extraction (REF-2 owns that file with the
  hook); `docs/research/agent-platform/2026-10-08/task-census/refs.clj` (dated research evidence, not a caller).

## Commit

`bin/lane commit ref1 -m "REF-1: one reference resolver for intents, Markdown docs and docstrings" --test seon.ref-test --test seon.context-test --test seon.sci.documentation-test deps.edn src/seon/ref.clj resources/seon/schemas/seon.ref.edn src/seon/context.clj resources/seon/schemas/seon.context.edn test/seon/ref_test.clj test/seon/context_test.clj script/seon/dev/citations.clj script/seon/dev/citations_test.clj`
