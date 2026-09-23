---
type: research
status: current
created: 2026-09-23
scope: G1 (a home for long-form documentation) and G3 (reading Clojure inside docstrings without a regex); prior art, three prototypes, priced options
---

# Long-form docs and Markdown: parse prose, or store docs as data

Implementation is frozen; this note changes nothing outside `tmp/`. Probes ran in
`tmp/docs-research-2026-09-23/` under babashka `v1.12.212` and a plain `clojure -M` JVM,
never Seon's `default`. Base commit `db0ebf75e`. Every claim is VERIFIED (read or ran) or
UNVERIFIED. Scope was widened mid-task by the owner: "we write our own doc functions for
parsing things. Think outside the box. This is not about files and traditional clojure
compile and testing system." So the Markdown parser is one option here, not the assumption.

## 1. Answer first

1. **G3 has a working non-regex answer, but you may not need it.** `nextjournal/markdown`
   returns a plain Clojure AST. Inline code is `{:type :monospace}` and a fenced block is
   `{:type :code :language "clojure"}`. It ships inside babashka, runs on the JVM through
   commonmark-java and runs in ClojureScript through markdown-it. Parsing all six toy
   docstrings and reading their 11 code nodes with edamame took **0.51 ms** in bb and
   13.95 ms on a cold JVM. Every schema key and function they name was found (§3). VERIFIED.
   The same probe also shows the limits of parsing prose. Unqualified names only resolve
   against the docstring's own namespace. So `log-entry` in a test namespace's docstring
   resolves to nothing, and argument names (`entries`) show up as false positives. The
   design draft's own principle 4 says "Prose is never parsed". Parsing prose goes
   against that principle, so it can only ever give warnings.
2. **Documentation stored as data removes G3 entirely.** In this model a doc body is
   hiccup-style vectors with real symbols and keywords inline, stored on a doc entity. I
   prototyped it (§4). The writer walks the value for links (no parsing) and refuses a
   link that names nothing. It records each link's definition digest, so drift is a set
   difference. It renders to AI text and to HTML through hiccup, which is already on
   Seon's classpath. The value round-trips through EDN unchanged. Each operation took
   1–2 ms in bb. VERIFIED on the toy.
3. **There is one real friction when an agent writes a doc at the REPL: symbols evaluate.**
   A bare body fails with "Unable to resolve symbol: set-volume". Worse, if the symbol
   does resolve, it silently stores the function object (clojure-native-doc-metadata
   note, §1). Syntax quote solves this. It qualifies bare names and aliases against the
   current namespace, and the writer's EDN round-trip check refuses anything that is not
   data. VERIFIED.
4. **Durability decides where the authority lives.** By the laws, program rows derive from
   files, and nuke rebuilds from committed inputs (AGENTS.md "Operate…"). So a doc that
   exists only in Datahike does not survive `nuke`. Docs must be program rows with a file
   form that is written back through the ordinary write-back gate. They must not be stored
   in the database alone.
5. **Recommendation.** G1: docs as data, stored in the database and written back to EDN
   files, used for anything longer than a short docstring (option G1-C). G3: parse no
   prose. Keep docstrings short and plain. Links go in `:seon/see-also`/`:seon/must-read`
   metadata. Flag a docstring as possibly stale when its function's body digest changes
   but the docstring's digest does not (option G3-A, about 15 lines).

## 2. Survey: Markdown parsers that return data

| Library | Output | Inline code / ```clojure | Runtime | Size | Status |
|---|---|---|---|---|---|
| **nextjournal/markdown** 0.7.213 | nested maps `{:type :content}`; `->hiccup` in `transform.cljc:158` | `:monospace` with a `:text` child (`impl.clj:123-126`); fenced → `{:type :code :info "clojure" :language "clojure"}` (`impl.clj:143-150`) | JVM via commonmark-java 0.24 (`impl.clj:8-12`); CLJS via markdown-it ^14 (`deps.cljs:2`); **built into bb** (ran `(require 'nextjournal.markdown)` in bb 1.12.212 with no deps) | jar 112 KB; with commonmark + 5 extensions 332 KB (`du` of `~/.m2`) | jar dated 2025-11-25; used by Clerk |
| commonmark-java 0.24 directly | Java `Node` tree, visitor API | `Code`, `FencedCodeBlock.getInfo` | JVM only; not CLJS | core jar 215 KB | maintained |
| flexmark-java | Java AST, many modules (15 artifacts cached locally), `flexmark-ext-wikilink` | yes | JVM only | large | maintained (UNVERIFIED recency) |
| markdown-clj | a string of HTML (`md-to-html-string`) | only as HTML | cljc | small | unsuitable: produces no AST (UNVERIFIED beyond its API name) |
| cybermonday | "hiccup AST" IR via `cybermonday.ir/md-to-ir` | not documented in the README | flexmark on JVM, remark on JS; GraalVM native since 0.6 (https://github.com/kiranshila/cybermonday) | pulls flexmark | 113 stars; recency UNVERIFIED |

- **`[[wikilinks]]`:** nextjournal parses them only when you opt in to its
  `internal-link-tokenizer`, which is itself a regex, `#"\[\[([^\]]+)\]\]"`
  (`impl/utils.cljc:296-299`). That regex belongs to the dependency, not to Seon. VERIFIED.
- **SCI:** SCI does not run nextjournal.markdown on its own. A Seon SCI context would have
  to expose the JVM namespace as a host binding. bb can use it because it is compiled into
  the bb binary. VERIFIED (jar contents; bb probe).

### How existing tools treat docstrings and comments

- **Clerk** parses a notebook namespace with rewrite-clj. Consecutive `;;` comment nodes
  have their semicolons removed (`remove-leading-semicolons`) and are passed to
  `nextjournal.markdown/parse*` in `update-markdown-blocks`
  (https://github.com/nextjournal/clerk/blob/main/src/nextjournal/clerk/parser.cljc). I
  found no docstring→Markdown path in `parser.cljc` or `analyzer.clj`. Clerk's hash is a
  content hash: each form plus the sorted hashes of its dependencies (`hash-codeblock`,
  `hash`, via `weavejester.dependency`). Clerk uses it to decide when to re-evaluate
  results, not to check whether prose is still accurate. VERIFIED (fetched source; line
  numbers are approximate, from the fetch).
- **cljdoc** renders docstrings as Markdown by default and offers a `:plaintext` override.
  `[[fn]]`, `[[ns/fn]]` and `[[ns]]` wikilinks "only work from docstrings". Articles are
  listed in `doc/cljdoc.edn` under `:cljdoc.doc/tree`. cljdoc does not detect drift
  (https://github.com/cljdoc/cljdoc/blob/master/doc/userguide/for-library-authors.adoc).
  VERIFIED (fetched).
- **codox** uses `:doc/format :markdown` in var metadata or project `:metadata`.
  Wikilinks resolve against the current namespace first. Long-form pages are `.md` files
  under `doc/` (`:doc-paths`, `:doc-files`) (https://github.com/weavejester/codox).
  VERIFIED (fetched).

## 3. Prototype A: parsing the docstrings (G3)

`tmp/docs-research-2026-09-23/md_extract.clj`. It takes docstrings from the existing
clj-kondo analysis (`tmp/wiki-toy/kondo.edn`), so no code is evaluated. It parses them
with `nextjournal.markdown/parse`, keeps `:monospace` nodes and `:code` nodes whose
language is `clojure` or `edn`, and reads their text with
`edamame.core/parse-string-all`. Names are then checked against kondo's var definitions
and the keys in `schemas/toy.workout.edn`.

```text
$ cd tmp/docs-research-2026-09-23 && time bb md_extract.clj
{:doc-of toy.workout, :node :monospace, :source ":toy.workout/entry", :names [{:name :toy.workout/entry, :exists? true}]}
{:doc-of toy.workout, :node :monospace, :source "total-volume", :names [{:name toy.workout/total-volume, :exists? true}]}
{:doc-of toy.workout, :node :code, :source "(total-volume [{:toy.workout/kind :lift ...", :names
  [toy.workout/total-volume :toy.workout/kind :toy.workout/sets :toy.workout/exercise :toy.workout/reps :toy.workout/weight — all :exists? true]}
{:doc-of toy.workout/total-volume, :node :monospace, :source "set-volume", ... toy.workout/set-volume :exists? true}
{:doc-of toy.workout/total-volume, :node :monospace, :source "entries", ... entries :exists? false}      ; an argument
{:doc-of toy.workout-test/accept-rest-days, :node :monospace, :source "log-entry", ... log-entry :exists? false} ; lives in toy.workout
{:doc-of toy.workout-test/weekly-summary, :node :monospace, :source "accept-rest-days", ... toy.workout-test/accept-rest-days :exists? true}
docstrings: 6 code nodes: 11 elapsed-ms: 0.513917
bb md_extract.clj  0.01s user 0.01s system 83% cpu 0.019 total

$ time clojure -Sdeps '{:deps {io.github.nextjournal/markdown {:mvn/version "0.7.213"}
                               borkdude/edamame {:mvn/version "1.5.37"}}}' -M md_extract.clj
... same 11 rows ...  elapsed-ms: 13.951208      (1.244 s wall, including JVM start)
```

(Rows are abbreviated. The full output comes from rerunning the command.) Findings, all
VERIFIED:

- The ```clojure example in the namespace docstring reads as real data. Its keys can be
  checked against schemas, and it could run as a test.
- Prose names are ambiguous in two ways. **Scope:** a docstring has no aliases, so a test
  namespace writing `log-entry` means `toy.workout/log-entry` and nothing says so.
  **Kind:** backticks also wrap arguments and literals (`entries`,
  `:toy.workout/kind :rest`). A checker built on this can only warn, or it must require
  fully qualified names, which turns backticks into a mini-language inside strings. The
  owner has excluded mini-languages in strings.
- A docstring is also stored with its own indentation and needs escaped quotes (`\"squat\"`
  in `workout.clj:10`), which is the readability cost G1 describes.

## 4. Prototype B: documentation as data (the owner's widened option)

`tmp/docs-research-2026-09-23/doc_data.clj` (about 90 lines including the probe harness).
A doc is a map. `:seon.doc/body` is a vector of hiccup-style elements whose leaves are
strings, qualified symbols, qualified keywords, or `[:example form expected]`.

```clojure
;; typed at the REPL in toy.workout — syntax quote qualifies bare names
`{:seon.doc/about total-volume
  :seon.doc/body [[:p "Sum of " set-volume " over every set in a vector of "
                   :toy.workout/entry ". A rest day contributes nothing."]
                  [:example (total-volume [{:toy.workout/kind :rest}]) 0.0]]}
```

The pieces, each a small function of data:

- `links`: `tree-seq` over the body. It skips the tag in element position and collects
  qualified symbols and keywords, including those inside examples.
- `write-doc`: the writer. It refuses when any link names nothing. Otherwise it adds
  `:seon.doc/links` and `:seon.doc/basis` (link → definition digest at writing time).
- `stale`: the links whose current digest differs from the recorded basis.
- `ai-text` / `html-node`: two renderers over the same small grammar. HTML goes through
  `hiccup2.core`.
- `check-examples`: finds each `[:example form expected]`. The evaluator was a stub in
  this probe (bb has no Malli, so `toy.workout` could not load). Running examples for
  real belongs to the test runner. That part is UNVERIFIED.

```text
$ time bb doc_data.clj
unquoted: Unable to resolve symbol: set-volume
links: #{:toy.workout/entry toy.workout/set-volume toy.workout/total-volume}
refusal: {:seon.error/message This doc links to names that do not exist., :seon.doc/missing #{toy.workout/weekly-summary}}
EDN round-trip equal: true chars: 433
stale after unchanged source: #{}
stale after set-volume edit: #{toy.workout/set-volume}
--- AI text ---
Sum of `toy.workout/set-volume` over every set in a vector of `:toy.workout/entry`. A rest day contributes nothing.
```clojure

(toy.workout/total-volume [#:toy.workout{:kind :rest}])
;; => 0.0

```
--- HTML ---
<div><p>Sum of <a href="#toy.workout/set-volume"><code>toy.workout/set-volume</code></a> over every set …</p><pre><code>…</code></pre></div>
write+links+basis ms: 2.210083  reindex+stale ms: 1.316917
bb doc_data.clj  0.01s user 0.01s system 83% cpu 0.021 total
```

Findings, VERIFIED on the toy unless marked otherwise:

- **Links are exact.** No string is parsed, and each link is a Datahike-native symbol or
  keyword (design principle 3). "What links here" is a query over `:seon.doc/links`.
- **Drift can be detected per link.** Editing `set-volume`'s body flags only that link. The
  prototype hashes `pr-str` of the read form. In Seon the definition digest already
  exists. The design's staleness-by-transaction-order option (§6, gap G6) could replace
  the stored basis if the indexer re-asserts only changed datoms (UNVERIFIED, G6).
- **Examples are tests.** `[:example form expected]` is a value the runner could evaluate.
  A doc whose example fails is then a red test, not stale prose. This is how Clerk's
  content hashing drives re-evaluation, applied to prose. UNVERIFIED end to end.
- **The agent-writing failure mode is loud when the name does not exist.** It is silent
  when the name resolves: an unquoted existing var stores a function object. The writer
  must refuse values that do not round-trip through EDN, as `schema/register!` already
  does for schema forms (`src/seon/schema.clj:1743-1760`). With that check, the silent case
  becomes a refusal whose message can name syntax quote as the correct form.
- **Datahike storage has a gap.** The Seon bridge maps `:vector` to cardinality-many
  (`src/seon/schema/datahike.clj:104-107`), which would lose order and duplicates. It
  EDN-encodes only heterogeneous `:or` unions (`datahike.clj:115-122`, decoding at
  `src/seon/db.clj:1571-1610`). A body therefore needs one declared "single EDN value"
  storage. That is a small change to the bridge's owner, not a new codec. Whether an
  existing property already expresses it is UNVERIFIED. **Components are the
  alternative.** Each block or inline node becomes an entity with a position attribute.
  Datalog could then see inside paragraphs, at the cost of dozens of datoms per sentence,
  an ordering attribute and a rebuild on every edit. The encoded value is better, with
  `:seon.doc/links` derived by the writer in the same transaction. That fits "derive at
  the writer" and is not a mirror.
- **Readability comes from rendering.** The AI text is Markdown the agent already reads
  well. HTML fits the existing Datastar namespace pages. The raw EDN file is readable
  hiccup but worse than Markdown on GitHub.
- **Devices (G5):** no parser is needed. Anything that reads EDN (SCI, bb, CLJS) can walk
  links and render. VERIFIED for bb.

## 5. Prior art: long-form docs next to code (G1)

| Prior art | How docs link to code | Drift detectable? | Agent/LLM consumption |
|---|---|---|---|
| cljdoc articles (`doc/cljdoc.edn` `:cljdoc.doc/tree`) | wikilinks only from docstrings; articles use repo-relative links | no | rendered HTML; no graph (VERIFIED, URL §2) |
| codox `doc/` pages | `[[var]]` in Markdown docstrings | no | HTML (VERIFIED) |
| Clerk notebooks | a notebook is a namespace; prose is `;;` Markdown next to live forms | results by content hash; prose no | forms and prose in one file, but prose is unlinked text (VERIFIED §2) |
| Marginalia / literate programming | docstrings and comments side by side with code | no | a static page (UNVERIFIED, from memory) |
| Polylith | a workspace plus per-brick READMEs; `poly ws` exposes the workspace as data | no | structure is data, docs are files (UNVERIFIED) |
| Datomic/Datahike `:db/doc` | docs are attribute values on schema entities in the database | history only | queryable, but plain strings (VERIFIED `reference-code/datahike/src/datahike/constants.cljc:28-31`; Seon treats it as updatable, `src/seon/cluster.clj:1014`) |
| Malli `:description` | a schema property; Seon already renders it | the schema form digest | already in context (design §6) |
| org-roam / Obsidian Dataview | notes are nodes, links are an indexed graph, frontmatter fields are queryable | no (links to text, not code) | the closest to "the plan is a query", but targets are notes (UNVERIFIED) |
| Clerk viewers / Portal / nextjournal `->hiccup` | docs as hiccup values rendered by viewer functions | n/a | the render-functions-over-data half of option C (VERIFIED `transform.cljc:158`; viewers UNVERIFIED) |

No surveyed tool both links prose to code symbols exactly and detects drift. The pieces
exist separately: Clerk's dependency hashing, `:db/doc` in the database, and hiccup
rendered by viewers. Option C combines them. That combination is new.

## 6. Comparing the options for Seon

| | (a) docstrings + `:description` only | (b) docstrings + a few Markdown+EDN pages | (c) docs as data (hiccup bodies) in rows, written back to EDN |
|---|---|---|---|
| Exact linking | metadata only; prose unlinked | fenced EDN exact; prose unlinked or parsed (warn-only, §3) | every link exact, from a walk |
| Drift | body-vs-docstring digest; metadata links by digest | fenced links by digest; prose not | per link, by digest; examples run as tests |
| Agent writes it | yes, standard `defn` | writing Markdown files is familiar, but they are files, not REPL data | hiccup is familiar; needs syntax quote (the refusal teaches it) |
| Human reads it | `(doc x)`; long ones bloat and need escaping | best in raw GitHub | via renderer (AI text, HTML page); raw EDN is fair |
| Queryable | kondo rows | links in EDN blocks; the prose is text | links, subjects, examples, all Datalog |
| Runs on devices | yes | needs a parser (bb has one; SCI needs a host binding) | yes, EDN only |
| Implementation | ~15 lines (G3-A) | ~60 lines + 332 KB dep | ~80–110 lines: schema, writer check, walk, two renderers, storage property, write-back; hiccup already present |
| Survives nuke | files | files | only with file write-back (§1.4) |

A fourth option was considered: (d) author Markdown, but store it as the same data by
converting the nextjournal AST into the body grammar at write time. It keeps GitHub
readability. It also doubles the authoring grammars, and it still leaves prose names
ambiguous. Rejected: it pays for (b) and (c) together.

## 7. Priced options

**G1 — where long-form docs live**

1. **G1-A: docstrings only (simplest).** No new code. Guarantee: one place, standard
   Clojure. Cost: none. Sacrifice: subsystem, concept and primer text bloats `(doc ns)`,
   needs escaping, and has no exact links. Concepts with no namespace have no home.
2. **G1-B: Markdown pages with fenced EDN** (the `docs/wiki/seon/doc/errors_render_once.md`
   experiment). Guarantee: exact links inside EDN blocks, and the best raw readability.
   Cost: ~60 lines + nextjournal.markdown (332 KB; built into bb; JVM host binding for
   SCI). Sacrifice: prose is unlinked and cannot be checked for drift, and there are two
   formats (Markdown around EDN).
3. **G1-C (recommended): docs as data.** A `seon.doc` entity with `:seon.doc/about` (a
   symbol or keyword), a `:seon.doc/body` hiccup value, derived `:seon.doc/links`, and
   `:seon/must-read`/`:seon/see-also`. Authored at the REPL with syntax quote, written back
   to `docs/**.edn` through the ordinary write-back gate, and rendered by the entity
   schema's AI/HTML pair. Guarantee: every link exact and checked at the writer, per-link
   drift, examples as tests, no parser, runs anywhere EDN reads. Cost: ~80–110 lines plus
   one bridge storage property (§4). Sacrifice: raw files read worse than Markdown, and
   agents must learn syntax quote for doc bodies.

**G3 — Clojure inside docstrings**

1. **G3-A (recommended): parse nothing.** Docstrings stay short plain prose. Links live in
   metadata. Staleness comes from separate digests for the docstring and the rest of the
   form: "body changed, docstring did not" gives the owner's "at least we know it's
   potentially out of date". Cost: ~15 lines in the indexer. Sacrifice: backticked names
   in prose are not checked. Anything that needs examples or long explanation moves to a
   G1-C doc.
2. **G3-B: nextjournal.markdown + edamame, warn only.** This is prototype A. Cost: ~40
   lines + the dependency. Sacrifice: warnings from ambiguous scope and kind (§3), and it
   goes against design principle 4.
3. **G3-C: require fully qualified backticked names and refuse others.** Cost: as G3-B.
   Sacrifice: a mini-language inside strings, which the owner has excluded. Not
   recommended.

## 8. Open for the owner

- Confirm that docs are program rows with an EDN file form. Otherwise nuke deletes them.
- Choose the body grammar: the minimal `:p`/`:example`/inline-name grammar used here, or
  also headings, lists and tables (each one more renderer branch).
- Decide whether a doc's `[:example …]` joins the owning namespace's tests (so red means
  a doc defect) or runs as its own test class.

Probe files (disposable, not committed): `tmp/docs-research-2026-09-23/md_extract.clj`,
`doc_data.clj`, `workout-changed.clj`, `nj/` (the unpacked nextjournal jar that was read).
