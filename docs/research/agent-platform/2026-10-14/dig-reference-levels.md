---
title: "Dig: reference levels (bare, one backtick, three backticks) and what each kind shows today"
type: research
status: dig only; no source, schema, doc or test changed; no SCI run; no recommendations
date: 2026-10-14
basis: "branch refactor/agent-platform at 8b50744bd; code read with sed and grep; notes read, not re-measured"
---

# Dig: reference levels

Scope: TASK-CHAIN 3a's three levels (bare = summary, one backtick = `(doc x)`, three backticks = `(source x)`) and the
owner's ask (2026-10-14) to iterate on the three most useful levels per reference kind. This note records facts only.

Tags: **V** = read in the named symbol or file this session. **U** = taken from a dated research note, not re-run
here. **I** = inference from **V** text, not probed.

## 0. Headline facts

- **V** The three levels are specified (`docs/seon/schedule/cards/TASK-CHAIN.md` §3a, commit 8b50744bd) but not
  implemented. `seon.render.ns/function-ai` still has two tiers, selected by `node-distance` (0 = name and doc line,
  1+ = whole source plus `my.program/callers` and `my.program/tests-reaching` operations). The same two-tier shape holds
  in `seon.render.test/render-ai`, `seon.render.schema/render-ai`, `seon.render.ns/render-ai`, `seon.render.ns/task-ai`.
- **V** `seon.ref/render-doc-ai` has no depth at all: it returns the entry without render keys plus one operation
  `(my.fs/section "path#anchor")`.
- **V** The existing "bare" level is `seon.ref/brief` (one name, one summary line, one read form). It is what every
  inner reference uses through `seon.ref/cite-once`.
- **V** `(doc x)` is `seon.sci.lookup/documentation-value`, and it does not return schema descriptions for the schemas a
  contract names (see §3). Schema descriptions exist only in `seon.ref/schema-documentation` (keyword branch) and in the
  namespace directory (`:schemas`).

## 1. Every reference kind `seon.ref` resolves today

Resolver chain: `seon.ref/candidates` → `seon.ref/candidate` (spans from `seon.ref/document`) → `seon.ref/answer` →
`seon.ref/resolve-name` (entities, by identity-attribute index via `seon.ref/identity-of`) or `seon.ref/resolve-path`
(documents, via `seon.ref/section`). Library Vars: `seon.ref/library-name`.

| Kind | Written as | Identity probe (V) | Resolver (V) | AI render shown today (V: declared or symbol) | Read form offered (`seon.ref/brief`) |
|---|---|---|---|---|---|
| Function | `` `ns/name` `` or bare `ns/name` | `:seon.fn/sym` (`identity-of`) | `resolve-name` → pull per `declarations` (source-attribute `:seon.fn/source`) | `seon.render.ns/function-ai` (`seon.fn.edn`): d0 name + doc line; d1+ source lines, `:seon.fn/spec` when source lacks `:malli/schema`, operations `my.program/callers`, `my.program/tests-reaching` | `(my.program/source 'f)` |
| Library Var | `` `malli.core/validate` `` | none; `library-name` (loaded Var, or dependency cache, never loaded) | `library-name` | the same `:seon.fn/sym` pair (`render :seon.fn/sym`) | `(doc f)` (no source) |
| Deftest | `` `ns/test-name` `` | `:seon.test/sym` | `resolve-name` | `seon.render.test/render-ai` (source lines beyond d0, state, reach) | `(my.program/source 't)` |
| Schema key, attribute, error type, dial | `` `:ns/key` `` | `:seon.schema/key` (keyword only) | `schema-documentation` via `resolve-name` (keyword: `unresolved`) | `seon.render.schema/render-ai` (`seon.schema.edn`): definition; usage report beyond d0 (referenced-by, contracts, capped at 8: `names-shown`) | `(my.program/source :k)` |
| Namespace | `` `seon.ref` `` (dotted, no `/`) | `:seon.ns/name` or `find-ns` on host | `resolve-name` | `seon.render.ns/render-ai` (`seon.ns.edn`): name, doc line, requires and public-function table beyond d0 | `(dir seon.ref)` (`namespace-name`) |
| Task | `` `ns/task-name` `` | `:seon.task/sym` | `resolve-name` | `seon.render.ns/task-ai` (`seon.task.edn`): d0 id, sym, name, ns; beyond d0 plan and references | none in `brief`: `:doc` is the first line of plan or instructions; `(my.task/current …)` named by the task docs only |
| Document (any file) | `` `docs/x.md` `` `` `src/a.clj` `` `deps.edn`; or link `./x.md` | none; `based-file` / `locate` | `resolve-path` → `section` with no anchor | `seon.ref/render-doc-ai` (declared `:seon.ref/resolved-doc`, `seon.ref.edn`) | `(my.fs/section "path")` |
| Document section | `` `docs/x.md#anchor` `` or `#anchor` or a link | heading with that anchor in `document` | `resolve-path` → `section` | `seon.ref/render-doc-ai` (heading, anchor, path, pin, line, inner references) | `(my.fs/section "path#anchor")` |
| Pinned library doc | `` `reference-code/<project>/path#anchor` `` | `based-file` reads gitlink pin (`seon.test.cache/gitlink-pins`) | `resolve-path` | `seon.ref/render-doc-ai`, plus `:seon.ref/pin` | `(my.fs/section …)` |
| Missing anything | any | none | `problem-messages` | `seon.error.refusal` problem map (`:seon.ref/problem-kind` :unresolved / :anchor-missing / :line-reference) | none |

Notes:

- **V** `seon.ref/answer` decides "resolve-only" words: a bare prose word with `/` is a candidate only when
  `named?` holds (index probe). A dotted bare namespace is resolve-only.
- **V** `seon.ref/document` already distinguishes the delimiter span: code spans, links, bare words, and fenced blocks
  (`FencedCodeBlock` goes to `:seon.ref/code-blocks` and is never a candidate). The span's literal is in
  `:seon.ref/span`; the source span is not kept, so the backtick count (1 vs 3) is **not** in the parsed document today
  (the spec's "read from the commonmark source span" is not implemented). **I** Adding it means carrying the
  `Code` node's delimiter length, which commonmark's `Code` node does not expose directly (UNVERIFIED which accessor
  exists in the pinned commonmark).
- **V** `resolve-name` pulls `[attribute reference]` with `:seon.fn/doc`, `:seon.ns/doc`, `:seon.fn/calls` added, then
  `inner` adds one level of referenced names (`:seon.ref/references`).

## 2. Schema keys: producers, consumers, existing indexes

| Question | Where (V) | Answer |
|---|---|---|
| Where a contract is stored | `:seon.fn/spec` (string, whole Malli `:=>`/`:function`) on `:seon.fn/sym` rows (`resources/seon/schemas/seon.fn.edn`); per arity `:seon.fn.arity/input-refs`, `:seon.fn.arity/output-refs`, `:seon.fn.arity/guard-refs` (`resources/seon/schemas/seon.fn.arity.edn`) | Arity refs are the schema keys the contract names, split by input, output, guard. |
| Index from key to the functions naming it | `:seon.fn.arity/input-refs` declared `:seon.db/index true`, `:seon.db/reference-to :seon.schema/key` (`seon.fn.arity.edn` line 3); output-refs likewise | Yes, an AVET-indexed reference attribute. Query shape: `[?arity ?attribute ?key] [?function :seon.fn/arities ?arity] [?function :seon.fn/sym ?name]` over `[input-refs output-refs guard-refs]`. |
| Consumers of `:k` (who takes it) | `seon.render.schema/usage` (`contracts`, capped `names-shown` 8); `seon.test/schema-consumers` (closure over `:seon.schema/references`); `seon.db` `holders` for `:seon.fn.arity/input-refs` in the change-set computation (`seon.db`, near line 4871) | Input refs only in `seon.db`; input, output and guard together in `seon.render.schema` and `my.program/key-data`. |
| Producers of `:k` (who returns it) | `my.program` `producer` (private, in the search path): output-refs query, then containers via `:seon.schema/references`, ranked by `use-count` (`seon.fn/calls` + `seon.test/reach`) | Exists privately (not a tool). Returns the first match or the key itself. |
| My tools that answer "who takes/returns :k" | `my.program/reads-key` (`key-data`): `:seon.program/contract-refs` (all three ref kinds merged, no in/out split), `writes-of`, `schema-references`, `mentions` (`function/functions-using`), `data-in-use`. `my.program/search` (`key-match`) returns `:my.program/producer`. | No single tool returns separate `takers` and `returners`. `reads-key` merges input, output and guard; `search` gives the producer only. |
| Declared description of a schema | `:description` in the schema form property map; read by `seon.render.schema/description` (**private**, `defn-`, line 94); `seon.ref/schema-documentation` reads it inline | 3a's "make it public" is a visibility change only (V). |

## 3. What `doc` and `source` return today

**`(doc x)`** = `seon.sci.lookup/documentation-value`, bound as `clojure.repl/doc` via `install-program-doc!`.

| Input | Branch | Returns (V) |
|---|---|---|
| qualified keyword | `seon.ref/schema-documentation` | `{:key :form [:description]}` |
| qualified symbol (function) | `function-doc-map` | `ref/docstring` parts (`:summary`, `:body`, `:example`), `:arglists` (`agent-arglists`, positional and supplied map entries removed), `agent-documentation-contract` (`:in`, `:out`, `:supplied`), `:seon.schema.admission/note` for an override |
| qualified symbol (library Var, SCI only) | `:doc`/`:arglists` from SCI metadata | docstring parts, arglists |
| simple symbol (namespace) | `namespace-documentation` | docstring parts and `:publics` |

- **V** `agent-documentation-contract` returns contract `:in`/`:out` as **form** (Malli `m/form` of each arity's
  input/output), plus `:supplied`. It does **not** return the schemas' forms or descriptions. `documentation-schemas`
  builds a `key → form` map and uses it only to resolve keyword arguments inside the contract (its result is not in the
  returned map). **This is a fact that contradicts 3a's "contract with its named input and output schemas, each
  schema's one-line description" for `doc`.**
- **V** `ref/docstring` returns `:summary` (first sentence of the first paragraph; abbreviations `e.g.`, `i.e.`,
  `etc.`, `vs.` are not sentence ends), `:body` (rest of the text outside the Example section), `:example` (code of
  `## Example` / `## Examples`).
- **V** Namespace directory (`directory-value`) returns `:schemas` (keys to forms, from the namespace's functions'
  arity refs and its own schemas) and per-function `:doc` (summary only).

**`(source x)`** = `clojure.repl/source` via `seon.sci.lookup` (`stored-source`), which delegates to `my.program/source`.
`my.program/source` (`src/my/program.clj`): a keyword → `seon.ref/schema-documentation` (form and description, or not-found);
a simple symbol → `namespace-declarations`; a qualified symbol → `declaration-source` (source text, `:doc` when the doc is
not inside the source, `:contract` read from `:seon.fn/spec`, `:file`, `:seon.print/budget` = 64 + tokens of source).
The value printer cuts it (budget), as the doc-view measure note records (U).

## 4. Markdown: parse, sections, first paragraph, style

- **V** `seon.ref/document` (one parse per digest, cached by `cache/lookup ::document`) returns `:seon.ref/headings`
  (`level`, `heading`, `anchor` from commonmark's `IdGenerator`, `line`), `:seon.ref/spans` (code spans as
  `:seon.ref/span`, links as `:seon.ref/link`, bare words with `/` as `:seon.ref/word`), `:seon.ref/code-blocks`,
  `:seon.ref/line-count`, `:seon.ref/first-paragraph` (`line`, `end-line`). Front matter via `YamlFrontMatterExtension`.
- **V** `seon.ref/section` (`reference` = `path#anchor`): returns `:seon.ref/heading`, `:seon.ref/text` (the whole
  section), `:seon.ref/tokens` (`seon.ai.tokens/estimate` of the section), `:seon.ref/below` (child headings at the next
  level with their own token counts), `:seon.ref/anchor`, `:seon.ref/pin`. Without an anchor it is the whole document.
- **V** `resolve-path` **drops** `:seon.ref/text`, `:seon.ref/below`, `:seon.ref/tokens` before the entry is
  returned, so a section reference's read shows heading, anchor, path, pin, line, and inner references only. (Matches
  the doc-view measure note's finding; the drop is at `resolve-path`, not `render-doc-ai`.)
- **V** Inner references of a document are `inner` over `(:seon.ref/text found)` for `.md` paths only.
- **V** `seon.ref/first-sentence` is the only sentence splitter: first `. `, skipping listed abbreviations.
- **Style rules found (V):**
  - `AGENTS.md` ("Documentation is data"): a docstring says briefly what the function returns; Markdown headings
    (Examples, Edge cases, Errors, Known issues) only on public `my.*` docstrings; cross-cutting knowledge in Markdown
    pages; "Never author what the call graph already derives".
  - `docs/seon/schedule/cards/TASK-CHAIN.md` §3 plan blocks: `## Problem` (written by the chain's creator, never
    changed later), `## What exists` and `## Approach` (added by Design). Headings become anchors (`## What exists` →
    `#what-exists`).
  - `resources/seon/help.md` headings: `## Who you are`, `## How to speak`, `## Your task`, `## How work proceeds`,
    `## Write what you learn`, `## When you cannot`, `## Each reply is data`, `## Data`. Help's first block sections teach
    a verb each (`change a member`, `change text`, `see what changed`), per TASK-CHAIN §3a.
  - No file in `docs/` or `.agents/` names a "first main block" or "first paragraph" writing rule (grep: none). The
    owner's "first main block vs whole section" distinction has no written rule yet (V by grep; **I** the
    `first-paragraph` key is the mechanism that exists).

## 5. Prior attempts and rulings

| Source | What it found or decided (one line) | Status |
|---|---|---|
| `docs/seon/schedule/cards/TASK-CHAIN.md` §3a (c14856b25, 8b50744bd) | Author picks depth: bare / one backtick / three backticks; distance tiers go; `(doc x)` is the one-backtick value; `seon.ref/cite-once` for names | Spec; not implemented (§0) |
| `docs/research/agent-platform/2026-10-14/doc-view-measure.md` (U) | Function distance tiers have no middle; doc view = new tier; inner references are callees today, half the doc view's tokens; deftests -72%, functions -46%, schemas ~0; help grows with cited doc views | Dated probe on default, read-only |
| `docs/research/agent-platform/2026-10-14/render-reuse.md` (U) | No render is cached today; `must-read-ai` bypasses `render-call`; the prompt is not deterministic for one database value because `seon.cluster.source/results-verdict` stamps refusals (time and world) | Measured; root cause at producer named |
| `docs/research/agent-platform/2026-10-13/context-references.md` (U) | Reference-kind table; Task has no text form in `brief`; document kind's render and identity probes; gaps D.1–D.4 | Research, read-only |
| `docs/research/agent-platform/2026-10-13/reference-reading-list.md` (U) | Ranked sources for references, proofs, task data, chains | Research |
| `docs/research/agent-platform/2026-10-14/context-and-first-request-cost.md` (U) | Context assembly cost decomposition; `model-context` ~382 ms, ~898 MB allocated per request (per doc-view note) | Measured |
| `f155c1494` | `seon.ref/references`: a name shows once; later mentions are the name alone (cite-once) | Committed (V) |
| `ac34ce5a3`, `e64e3997b` | Function and test entity render pairs; a function node is its source with neighbours by name | Committed (V); the source-tier design is what 3a removes |
| `41bcd24d8` | A cited test's AI face shows its source lines beside its state | Committed (V) |
| `35cafc1dc`, `978f7122c` | Render invocations in one `seon.cache` kind; must-reads render through `render-call` | Committed (V); `must-read-ai` still reaches `render-ai` directly per render-reuse (U) |
| `bfa61a410` | Research: render-call reuse 112.73 → 21.88 ms for 16 must-reads | Research commit (V by log) |
| `c7f0383b0` | A cited namespace carries its stored requires; only a referent shown by its source is pulled as its source | Committed (V) |
| `0ad513f26` | help.md teaches only installed keys; points at schemas instead of restating them | Committed (V) |

## 6. Real referent sizes (from the doc-view note; U, not re-run here)

Tokens = `seon.ai.tokens/estimate` (3.2 characters per token, floored). "d1" is the source tier today.

| Referent | d0 | d1 today (source) | Doc view (3a, measured in the note) |
|---|---|---|---|
| Function `seon.ref/section` | 10 | 917 | not stated per function in the note; the note's function row set: 8 functions 7,709 raw → 4,144 doc view (2,079 without inner refs) |
| Library Var `malli.core/validate` | 31 | 104 | same as today (already a doc view) |
| Deftest `seon.ref-test/a-reference-with-no-path-is-a-missing-file` | 23 | 218 | ~89 (deftests 1,834 → 515 over 5 entries) |
| Schema key `:seon.ref/section-error` | 13–36 | 124–168 (form plus usage) | ~same (schemas 283 → 280 over 3 entries) |
| Namespace `seon.ref` | 7 | 604 (every public with arglists and summary) | not measured |
| Document section `docs/…/vocabulary.md#git-words` | 837 (no d0/d1 split) | 837 | heading, headings below, size: not measured |
| Task `seon.ref/a-pathless-…` | 54 | 591 (1,367 shown) | not measured |

Whole block (U): raw 37.4k characters, 11,973 tokens; shown prompt 6,013 tokens with 91 elisions; doc views in every
must-read: raw 7,087 tokens, shown 5,982, elisions 30. `(help)` system message 2,069 tokens; ~10 cited doc views would
add ~0.6–2.5k (I, from the function rows).

## 7. Open facts for the next step (no recommendation)

1. `(doc f)` omits the named schemas' descriptions (§3, V). 3a says it carries them.
2. The `` ` `` vs ```` ``` ```` delimiter length is not kept by `seon.ref/document` (§1, V).
3. `my.program/reads-key` merges input, output and guard refs; no tool returns takers and returners separately; `producer`
   is private (§2, V).
4. `seon.render.schema/description` is private (§2, V).
5. `resolve-path` drops `:seon.ref/below` and `:seon.ref/tokens` before the render (§4, V).
6. No written rule for a "first main block" versus "whole section" writing style exists in `docs/` or `.agents/` (§4, V by grep).
7. Schema-key takers/returners sizes, as a count per key, are not measured here (UNVERIFIED).
