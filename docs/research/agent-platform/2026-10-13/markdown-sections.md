---
title: "Markdown sections in one task document: what Seon has, and a splice design"
type: research
status: research only; wrote no source, test or database state; the splice sketch is not committed
basis: "rg, source reads and the nextjournal jar in ~/.m2 at the 2026-10-13 checkout"
model: Claude Haiku 5.5
---

# Markdown sections in one task document: what Seon has, and a splice design

Research only, 2026-10-13. Nothing here is committed to `src/`. Tags: [V] verified by reading source or running a
search on this checkout; [I] inference, not yet measured or run.

## 1. What Seon already has

The one Markdown owner is `seon.ref`, built on commonmark-java 0.24.0 with the heading-anchor and YAML front-matter
extensions (`src/seon/ref.clj` require block) [V].

- `seon.ref/document` [V] (`src/seon/ref.clj`, the public fn over the private `parsed`). Returns
  `{:seon.ref/headings [...] :seon.ref/spans [...] :seon.ref/line-count n}`. A heading entry is
  `{:seon.ref/level :seon.ref/heading :seon.ref/anchor :seon.ref/line}`, where `line` is the 1-based line of the
  heading's first source span. A span entry is `{:seon.ref/span literal :seon.ref/line n}` for inline code, or
  `{:seon.ref/link dest :seon.ref/line n}`. Fenced and indented code contribute nothing (`FencedCodeBlock nil`,
  `IndentedCodeBlock nil` in the visitor). Cost: one commonmark parse per distinct text, memoised through
  `seon.cache/lookup` keyed by SHA-256 of the UTF-8 bytes [V]. The cache lookup hashes the whole text on every call [V],
  so a repeated read still costs O(file bytes) even when the parse is cached [I].
- Heading anchors come from `IdGenerator` over the rendered heading text, so duplicates get `-1`, `-2` suffixes in
  document order [V by code; the suffix rule is the library's behaviour, I did not run it].
- `seon.ref/section` (public) [V]. Takes `"path#anchor"`, locates the file, slurps it, calls `document`, splits lines
  with `str/split-lines`, and returns `{:seon.ref/text :seon.ref/tokens :seon.ref/below :seon.ref/heading ...}` or a
  `:seon.ref/section-error`. `:seon.ref/text` is the lines from the heading to the next heading at or above its level
  (`section-end`), so it includes subsections. `:seon.ref/below` lists the direct children with their token counts.
  Cost: O(file) slurp, parse (cached), line split and a linear filter over headings [V code, I cost].
- `seon.ref/span-tokens` (private `defn-`) [V]. Estimates tokens of `lines[from..end)` through `tokens/estimate`. It is
  a read-only measure; it does not split or edit anything.
- `seon.ref/section-end` (private) [V]. `(or (some next-heading-at-or-above-level) end)`. The "section" here is a
  heading plus its subtree.
- `my.fs/section` [V] is a thin wrapper over `ref/section` (`src/my/fs.clj`).
- Heading parsing elsewhere: `script/seon/dev/markdown.clj` is a separate lint/format namespace for hooks, not used by
  the runtime [V]. No other heading or anchor parser exists in `src/seon/*.clj` (the `rg` hits there are prose about
  turn anchors, not Markdown) [V].
- Setext headings are not handled specially: `line` takes only the first `SourceSpan`, so the start line is correct
  but the underline's line is not known [V by code, I on the commonmark span list].

What is missing for editing: there is no end line for a heading (only `section`'s computed `stop`), no body-only
view (the text includes the heading line), and no splice. `section-end` and `span-tokens` are private.

## 2. Markdown libraries

| Library | Present | Version | Parse tree with source spans? |
|---|---|---|---|
| `org.commonmark/commonmark` | Yes, transitively (required by `commonmark-ext-heading-anchor` and `commonmark-ext-yaml-front-matter`, both declared in `deps.edn`); `ref.clj` imports `org.commonmark.node.*` directly | 0.24.0 (in `~/.m2`) [V] | Yes. `IncludeSourceSpans/BLOCKS_AND_INLINES` gives each block a `SourceSpan` with `lineIndex`, `columnIndex`, `length` [V use in ref.clj]. Columns, not character offsets [I from the API]. |
| `io.github.nextjournal/markdown` 0.7.213 | Declared in `deps.edn` (line 16 of the `:deps` map) [V]; in `~/.m2` [V]; NOT in `reference-code/` [V]; no `src/` or `test/` use [V, `rg -F` empty] | 0.7.213 [V] | No. Its `impl.clj` builds heading nodes with `:heading-level` only and no `SourceSpan` use [V by reading the jar]. Unused, so it can be deleted from `deps.edn` as a dead dependency. |
| `markdown-clj`, `flexmark` | Not declared, not in `reference-code/`, not in `~/.m2` (not checked exhaustively) [V for deps.edn and reference-code listing] | n/a | flexmark has source positions (not inspected). Adds a second parser beside commonmark; the one-owner rule says no. |

Recommendation: use commonmark-java (already the one parser) and delete the unused nextjournal declaration, or keep it
only if root wants its renderer. Do not add a second parser.

## 3. Design sketch (not committed)

Reuse `seon.ref/document` for headings and lines. The proposed change is to promote `section-end` into a public
`seon.ref/sections` and give each heading its own-body end, so a stage's section does not swallow later stages' subsections.

```clojure
(ns seon.markdown.sections                ; proposed, not committed
  (:require [clojure.string :as str]
            [seon.ref :as ref]))

(defn- own-end
  "The line after a heading's own body: the next heading of any level, else past the last line."
  [headings line end]
  (or (some #(when (> (:seon.ref/line %) line) (:seon.ref/line %)) headings) end))

(defn sections
  "Each heading's own body: {:heading :anchor :level :start :end}, 1-based lines, end exclusive."
  [md]
  (let [{:seon.ref/keys [headings line-count]} (ref/document md)]
    (mapv (fn [{:seon.ref/keys [heading anchor level line]}]
            {:heading heading :anchor anchor :level level :start line
             :end (own-end headings line (inc line-count))})
          headings)))

(defn- find-section [md anchor]
  (first (filter #(= anchor (:anchor %)) (sections md))))

(defn section
  "The own body of the section with `anchor`, without its heading line, or nil."
  [md anchor]
  (when-let [{:keys [start end]} (find-section md anchor)]
    (str/join "\n" (subvec (str/split-lines md) start (dec end)))))

(defn- splice
  "md with the 1-based lines [from, to) replaced by `new-lines`; the other lines are copied, never re-rendered."
  [md from to new-lines]
  (let [lines (str/split-lines md)]
    (cond-> (str/join "\n" (concat (subvec lines 0 (dec from)) new-lines (subvec lines (dec to))))
      (str/ends-with? md "\n") (str "\n"))))

(defn replace-section
  "md with the body of `anchor` replaced by `body`; its heading and the next heading stay."
  [md anchor body]
  (let [{:keys [start end]} (find-section md anchor)]
    (splice md (inc start) end (str/split-lines body))))

(defn append-section
  "md with `text` added at the end of the body of `anchor`, before the next heading."
  [md anchor text]
  (let [{:keys [end]} (find-section md anchor)]
    (splice md end end (str/split-lines text))))

(def stage-entry
  [:map [:heading :string] [:level [:int {:min 1 :max 6}]] [:anchor :string]])

(defn validate
  "The required stage entries md lacks, in their order; empty when md holds all of them."
  [md required]
  (let [present (set (map (juxt :heading :level) (sections md)))]
    (vec (remove #(present ((juxt :heading :level) %)) required))))
```

Notes on the sketch: `find-section` returns nil for a missing anchor, and each caller must answer a refusal map
rather than destructure nil (the repo's rule); the sketch omits that for brevity. `validate` checks presence by
heading text and level, not order, and not anchor; a stage that needs stable identity should require its anchor.
`replace-section` takes a body that is a string; an empty body is `""`, which `str/split-lines` returns as `[""]`,
so an empty body leaves one blank line [I, to be checked]. Line-based splicing copies O(document) strings per edit;
that is far below a re-render, but it is not O(section) [I; not measured].

### Edge cases

- **Fenced code with `#`.** Not a heading: `document` skips `FencedCodeBlock` in the visitor [V]. Splicing uses the
  same line numbers as the parser, so fenced lines stay inside the body they belong to. Caveat: a `#` line inside a
  list item or block quote is still a heading to commonmark [I, the spec's behaviour; not run here].
- **Setext headings** (`Title` over `===` or `---`). Start line is right; the underline line is not. A body that starts
  at `start + 1` would contain the underline. Fix at the producer: the heading's end line is its last `SourceSpan`'s
  `lineIndex + 1`, which needs `parsed` to record it [I; verify the span list on a setext sample].
- **Duplicate headings.** Text is not unique, but `IdGenerator` makes anchors unique in document order [V by code].
  Anchors are not stable: inserting a heading above a second `Goal` renames it `goal-1` to `goal-2`. A task document
  should address stages by a declared anchor that it never reorders, and `validate` should check that anchor.
- **Trailing newline.** `str/split-lines` drops a final empty line; `splice` re-adds one newline when the input ends
  with `\n` [V by code]. CRLF input is normalised to `\n` by `split-lines`, so a CRLF document would change its line
  endings on the first edit [I]. Reject CRLF at the owner, or preserve the separator.
- **Empty heading** (`#` alone). Its anchor is `""`, which violates `:seon.ref/anchor` (`:min 1`) [V schema], so
  `document` output would fail its own schema. Refuse the document at its producer.
- **Preamble before the first heading and YAML front matter.** Neither belongs to a section; `sections` ignores them,
  and the front matter is already skipped by `section` [V, `ref.clj` line-after-`---` logic].
- **Heading level skips** (`#` then `###`). Allowed by commonmark; `validate` checks only what the schema lists.

## 4. Fit with the reference resolver

Backticked spans and links inside a section body still resolve through `seon.ref/references` [V]. `references`
takes a `:seon.ref/source` map `{:seon.ref/text ... :seon.ref/document-path ...}` and runs `document` on the text, so
a section body works as its `:seon.ref/text`. Pass the document's path as `:seon.ref/document-path` so `#anchor`
links resolve against the document itself. Two consequences:

- `:seon.ref/line` in spans is relative to the body, not the whole document [I from `parsed`]. It only matters for
  `path:N` line citations, which report as `:line-reference` problems, so the line in a message would be wrong
  unless the body is passed with its start offset. Either keep whole-document resolution for line citations or add
  the offset when building the source.
- `references` deduplicates within one call (`cite-once`) [V]. Resolving each stage's section separately gives each
  stage its own references; resolving the whole document once gives one list. Pick by who reads the references.

The stage's `held` names and paths (`:seon.ref/names`, `:seon.ref/paths`) are passed in the same source map, so a
stage's declared names stay attached to its section, not the document.

## Summary of findings

1. Seon's Markdown owner is `seon.ref`: `document` (headings with anchors and lines, spans, line-count; cached per
   digest), `section` (text, tokens, children, for a `path#anchor`), and private `section-end` and `span-tokens`. There
   is no heading end line, no body-only view and no splice.
2. commonmark-java 0.24.0 is the one parser and gives source spans; nextjournal/markdown 0.7.213 is declared but unused
   and gives no spans, so it is dead weight. flexmark and markdown-clj are not present.
3. The sketch reuses `document` and adds `sections`, `section`, `replace-section`, `append-section` and `validate` in
   about 40 lines. It needs setext end lines and stable anchors before a stage schema can rely on them.
4. Section bodies resolve through `seon.ref/references` unchanged, given the document path; line citations inside a
   body need an offset. Nothing measured yet: the costs above are inferences.
