---
type: research
status: current
created: 2026-09-23
scope: reference code as queryable, linkable agent context — gitlink/classpath fidelity, clj-kondo analysis of vendored libraries, cljdoc/codox wikilink grammar, idiomatic exemplars, code-graph prior art, and a proposal for [[qualified/symbol]] resolution and ranking
---

# Reference code as context: linkable library vars, ranked by the program graph

Read-only lane (Opus 5.5). No file under src/, test/, resources/, deps.edn, bin/ or skills was
edited, and nothing touched the running Seon JVM. All analysis runs used the standalone
`/opt/homebrew/bin/clj-kondo` binary (v2026.07.24) and `bb`. Their outputs went to the session
scratchpad, which is not committed. **VERIFIED** means I read the file or ran the command in this
session. **UNVERIFIED** means I could not open the source or did not run it.

## 1. Answer first

1. **Two vendored libraries on the classpath run code that is not the recorded gitlink.**
   VERIFIED. `git ls-files --stage` records malli at `8725a8cb` and babashka-process at
   `e83ec5c2`. The checkouts are at `56394c54` and `43bdd65` (`git submodule status` marks both
   with `+`). `deps.edn` loads both with `:local/root`, so the JVM runs the checkout bytes.
   `seon.test.cache/gitlink-digests` takes its identity from the index gitlink
   (`src/seon/test/cache.clj:176-200`, `ls-files --stage` at `:188`). So a gate's input
   identity can differ from the code that actually runs. A third mismatch: `reference-code/clojure`
   is at `b18d3adc` (before `clojure-1.13.0-alpha5`), but the classpath runs Maven
   `org.clojure/clojure 1.12.5` (`deps.edn:13`). An agent that reads `clojure/core.clj` is
   therefore reading 1.13-alpha source. **Rule:** a reference row is keyed by the commit that
   actually loads (`git -C <sub> rev-parse HEAD`, plus the `status --porcelain` clean check). It
   is never keyed by the index gitlink or by a submodule that nothing loads.
2. **We already store the edges that "our call sites" needs.** VERIFIED in source.
   `:seon.fn/calls` is an indexed set of qualified-symbol values
   (`resources/seon/schemas/seon.fn.edn:49`, `seon.program.edn:64`). It is built by
   `call-targets-by-caller`, which filters callers to first-party code but keeps every resolved
   target, library ones included (`src/seon/fn.clj:406-421`, `usage-symbol` `:355-368`). "Which
   Seon functions call `malli.core/explain`" is one Datalog clause against the program branch. I
   did not query the stored contents (no JVM), so that part is UNVERIFIED. Only the *library*
   side has no rows.
3. **clj-kondo already produces everything a library-var row needs, in about 1-3 s per library,
   with no JVM.** VERIFIED (§2.2): name, ns, doc, arglist strings, fixed arities, `defined-by`,
   `:meta`, and the full span (`row/col/end-row/end-col`). It also produces namespace docstrings
   and metadata, and var usages with `:from`/`:from-var`/`:to`. Seon's own analyzer already asks
   for exactly this (`src/seon/fn/analyzer.clj:22-39`). No new parser is needed.
4. **Library tests are the usage examples, and they are dense.** VERIFIED. Analyzing
   `reference-code/malli/test` takes 0.87 s. It finds 482 tests and 2,680 JVM-side call sites into
   `malli.core`: `validate` 434, `explain` 458, `schema` 315, `form` 102. Each call site has a
   `:from-var` that names its deftest, and that deftest's span is the example.
5. **The Datahike fork is not an idiom exemplar in the places where it differs from upstream.**
   VERIFIED from the fork audit plus `git log`. The fork adds 115 commits, all agent-authored, and
   74 of them are ruled RIP (`fork-audit-datahike-2026-09-23.md`, "Counts"). `single_flight.cljc`,
   `committed_report.cljc`, `gc_guard.cljc` and the planner files are mostly Seon-agent code. If we
   link them as "reference", agents learn our own workarounds back. Ranking must prefer spans
   whose last author is upstream (blame against the fork's merge base) and mark fork spans as such.
6. **Wikilink grammar is settled prior art, and it can be parsed with the EDN reader instead of
   a regex.** VERIFIED for cljdoc and codox (§2.3). `[[fn]]` means a var in the current ns,
   `[[ns/fn]]` a qualified var, `[[ns]]` a namespace, and aliases are *not* resolved. A
   `[[x/y]]` token reads as EDN `[[x/y]]`. `clojure.edn/read` at each `[[` offset yields the
   symbol (bb probe, §2.3), so the law against production regexes is not triggered. Seon
   docstrings already contain 48 unqualified `[[local]]` links and 0 qualified ones. They also
   contain 55 backtick citations of the form `reference-code/<lib>/…:line` (39 of them into
   Datahike). Those line numbers drift, and the dependency audit already had to pin itself to a
   HEAD blob for that reason. Each citation should become a `[[lib.ns/var]]` link that resolves
   to the current span.
7. **Aider's ranking is the right shape, and our graph is better input than its tags.**
   VERIFIED (§3). Aider runs PageRank over a reference→definition graph and personalizes it
   toward the files in the chat and the identifiers mentioned there (×10 for mentioned
   identifiers, ×50 for references from chat files, ×0.1 for private names). It sqrt-dampens
   reference counts and binary-searches the result into a token budget (`--map-tokens`, default
   1k). Our graph has kondo-resolved qualified edges instead of tree-sitter name matching, which
   removes Aider's biggest source of noise. Our focus is one entity rather than a set of files.
8. **Scale is small.** VERIFIED counts: about 8,800 distinct library vars across the 16 libraries
   that load. The six largest are clojure 1,544, datahike 2,166, sci 914, malli 858, core.async
   624 and rewrite-clj 619. The six largest also carry about 68k distinct caller→callee edges. As
   rows, that is on the order of 10^5 datoms. Each library is recomputed only when its commit
   changes.
9. **Our 550 distinct library vars in use are concentrated in a few places.** VERIFIED:
   `clj-kondo --lint src` finds 5,890 library call sites (outside clojure.core). The most-used
   namespaces are `malli.core` (470), `clojure.string` (338), test.check generators (269),
   `datahike.api` (186), `malli.registry` (149), `sci.core` (118) and `clojure.core.async` (114).
   The "already does it" audits name the library functions we re-implemented. Those are the
   first vars to surface when an agent's focus touches caching, identity, locking or
   notification (§2.4).

## 2. Evidence — our reference code

### 2.1 Gitlink revision against classpath (VERIFIED: `git submodule status`, `git ls-tree HEAD reference-code/`, `git ls-files --stage`, `deps.edn`)

| lib | recorded gitlink | checkout (describe) | classpath coordinate (`deps.edn`) | runs the gitlink? |
|---|---|---|---|---|
| babashka | 0fb349c | v1.12.218 | only nested `babashka/fs` `:local/root reference-code/babashka/fs` (nested gitlink 3fdcbcb, v0.5.33) | fs: yes; the rest of babashka: not loaded |
| babashka-process | **e83ec5c** | **43bdd65** (v0.6.25-1) | `:local/root` | **NO**: the checkout runs, and the index gitlink is stale (the `deps.edn` comment edit in the working tree says "Upstream babashka/process") |
| clj-kondo | 57252e0 | v2026.07.24-2 | `:local/root` (excludes datalog-parser) | yes. The edit-hook binary is the upstream release v2026.07.24, not the fork +2 |
| clj-reload | 61c6fa7 | 1.0.0 | not on classpath | reference only |
| clojure | b18d3ad | clojure-1.13.0-alpha5~9 | `org.clojure/clojure {:mvn/version "1.12.5"}` (tag `clojure-1.12.5` = 4df793f) | **NO**: the source is 1.13-alpha, the runtime is 1.12.5 |
| clojurescript | 946d75f | master | excluded (`deps.edn` datahike/konserve exclusions) | reference only |
| core.async | dc35f3e | v1.10.874-alpha3 | Maven `1.10.874-alpha3` | same tag. Jar byte-equality UNVERIFIED |
| core.async.flow-monitor | fbff842 | v0.1.2-9 | `:test` alias `:local/root` | yes (test classpath only) |
| datahike | c79cd03 | fork | `:local/root` | yes. Fork: 115 commits, 74 RIP |
| datastar-clojure | 1cef624 | v1.0.0-RC7 | `:local/root` sdk + sdk-http-kit | yes |
| edamame | 63373df | v1.6.42-1 | `:local/root` | yes |
| editscript | b493ccf | master | `:local/root` | yes |
| http-kit | f56bbea | v2.9.0-beta4-1 | `:local/root` | yes |
| hyperlith | b08a8e8 | master | not on classpath | reference only |
| kaocha | 8846f91 | v1.91.1392-28 | not on classpath | reference only |
| konserve | 5b39fdd | 0.9.359-9 | git dep `seantempesta/konserve` sha 5b39fdd (loaded from `~/.gitlibs`, not this tree) | sha matches. The fork audit's live probe found the JVM on 8cd9144 at the time; I did not re-probe (UNVERIFIED now) |
| langchain4clj | 889f9e6 | v1.0.3-57 | not on classpath | reference only |
| malli | **8725a8c** | **56394c5** (`seon-ref-scope`) | `:local/root` | **NO**: the checkout is one commit ahead of the gitlink |
| rewrite-clj | 60782e5 | v1.2.51-5 | `:local/root` | yes |
| sci | fcbd886 | fork | `:local/root` | yes |
| superv.async | 501b429 | fork | git dep sha 501b429 | yes |

`git diff --stat reference-code/` confirms the two uncommitted gitlink moves: babashka-process
and malli, one line each.

### 2.2 clj-kondo analysis of reference code

**What it returns** (VERIFIED, `reference-code/clj-kondo/analysis/README.md`):

- Options: `:arglists`, `:keywords`, `:symbols`, `:protocol-impls`, `:java-class-usages`,
  `:locals` (`:14-30`). `:var-definitions {:meta true|[keys]}` and
  `:namespace-definitions {:meta …}` return user-coded metadata (`:32-42`).
  `:var-definitions {:shallow true}` skips bodies, and `:var-usages false` skips usages
  (`:51-60`).
- Namespace definitions: `:filename :row :col :name`, plus `:doc :author :added :deprecated
  :no-doc` and `:meta` (`:81-90`). So **yes, namespace docstrings and metadata are included.**
- Var definitions: `:filename :row :col :end-row :end-col :ns :name :defined-by`, plus
  `:fixed-arities :varargs-min-arity :private :macro :deprecated :doc :added :meta :lang
  :arglist-strs :protocol-ns :protocol-name` (`:102-117`).
- Var usages: the location, `:name-row…`, `:from`, `:to` and `:from-var` (`:119-135`).
  Indirect (macro-generated) usages have no location (`:130-133`).
- Sample row (malli, VERIFIED output): `{:ns malli.core :name validate :defined-by
  clojure.core/defn :row 2639 :end-row 2645 :arglist-strs ["[?schema value]" "[?schema value
  options]"] :fixed-arities #{2 3} :doc "Returns true if value is valid …"}`. Sample usage:
  `{:from malli.generator :from-var function-checker :to malli.core :name explain :arity 2
  :row 556}`.

**Seon already requests all of it.** `analysis-config` sets `:arglists`, `:var-usages`,
`:keywords`, `:symbols` and `:var-definitions`/`:namespace-definitions` with `{:shallow false
:meta true}` (`src/seon/fn/analyzer.clj:22-39`). `var-definition`/`var-usage` keep exactly the
fields above (`:112-138`). The call goes through `clj-kondo.core/run!` (`:357-370`).

**Timings** (VERIFIED). Each run was one `clj-kondo --lint <dir> --parallel --cache false`,
with all linters off, EDN output, `:arglists` and definition/namespace `:meta`, and var usages
on (the default):

| source | wall s | EDN bytes | var defs (rows, clj+cljs) | distinct vars | with doc | distinct ns | usages | distinct caller→callee edges |
|---|---|---|---|---|---|---|---|---|
| malli/src | 1.15 | 17,143,547 | 1,673 | 858 | 142 | 33 | 22,753 | 10,594 |
| datahike/src | 2.69 | 43,837,348 | 4,096 | 2,166 | 1,064 | 87 | 58,625 | 27,654 |
| sci/src | 1.08 | 16,869,887 | 1,605 | 914 | 248 | 46 | 25,428 | 12,395 |
| clojure/src/clj | 0.89 | 12,119,594 | 1,580 | 1,544 | 1,075 | 36 | 18,469 | 9,750 |
| rewrite-clj/src | 0.41 | 6,171,056 | 1,193 | 619 | 449 | 52 | 8,700 | 4,650 |
| core.async/src/main/clojure | 0.32 | 4,583,495 | 626 | 624 | 241 | 24 | 6,544 | 3,128 |
| malli/test | 0.87 | 11,318,156 | 482 tests | — | — | — | 2,680 into malli.core (clj) | — |
| Seon `src` | 1.99 | 20,597,741 | 4,099 | — | — | — | 5,890 into non-core libraries | 550 library vars |

A shallow, usage-free pass over the other ten loaded libraries gives distinct var counts:
edamame 165, editscript 146, konserve 342, superv.async 72, http-kit 76, babashka/fs 130,
babashka-process 41, datastar sdk 164, clj-kondo 810 and flow-monitor 106. Each took under
1 s. The total across loaded libraries is **about 8,800 distinct vars**. The bytes are mostly
usages and location keys. Definitions alone are a small fraction, and the retained projection
(sym, doc, arglists, span, calls-set) is far smaller than the EDN. I did not measure that
fraction. The JVM `run!` path was not timed because the brief forbids touching the JVM.

### 2.3 Wikilink grammar (VERIFIED web)

- **cljdoc** ("Use API Wikilinks from docstrings", https://github.com/cljdoc/cljdoc/blob/master/doc/userguide/for-library-authors.adoc):
  `[[some-fn]]` means a var in the current ns, `[[my-lib.ns1/some-other-fn]]` a qualified var,
  and `[[my-lib.ns1]]` a namespace. Wikilinks work only in docstrings. Its words on aliases:
  "Namespace aliases are not considered". It discourages relative cross-namespace links in
  favour of fully qualified ones.
- **codox** (https://github.com/weavejester/codox, README): requires `:doc/format :markdown`.
  Current-namespace vars are matched first, then a best match across all documented vars.
  Example: `[[foo]]`, `[[user/square]]`.
- **Parsing without a regex** (VERIFIED, bb): at each `[[` offset, `clojure.edn/read` on a
  PushbackReader keeps a value only when it has the shape `[[symbol]]`. Input `"Validate via
  [[malli.core/validate]] and see [[seon.db]] or [[local-fn]]; not [[:a/b]]."` →
  `[malli.core/validate seon.db local-fn]`. `edamame` (already loaded) would also return the
  source offset.
- Seon today (VERIFIED `grep` over `src`): 48 `[[unqualified]]` links, 0 qualified, and 55
  `reference-code/<lib>/…` citations (datahike 39, sci 7, malli 4, core.async 3, konserve 1,
  clj-kondo 1).

### 2.4 Idiomatic exemplars and what we re-implemented

**A measurable exemplar signal** (VERIFIED, from analysis spans). The table counts clj-side
`defn`/`defn-` per namespace (n ≥ 12) and reports the median and p90 span in lines, and the share
of vars with a docstring:

| namespace | n | median | p90 | doc % | note |
|---|---|---|---|---|---|
| clojure.set | 13 | 8 | 14 | 100 | small pure functions of data |
| clojure.zip | 28 | 8 | 13 | 100 | zipper as a plain vector plus metadata |
| clojure.core | 605 | 8 | 20 | 84 | seq/collection functions |
| clojure.string | 25 | 10 | 20 | 84 | |
| clojure.math | 43 | 10 | 12 | 100 | |
| rewrite-clj.zip | 104 | 6 | 11 | 100 | the API facade over zipper functions |
| rewrite-clj.node | 57 | 5 | 23 | 100 | |
| clojure.core.async.flow | 15 | 7 | 19 | 100 | step functions and a graph described as data |
| malli.util | 39 | 7 | 15 | 69 | schema transformations as functions |
| malli.core | 189 | 5 | 50 | 23 | small functions, but under-documented; the p90 is the `-into-schema` reify bodies |
| sci.core | 49 | 8 | 22 | 77 | the public API; `sci.impl.analyzer` has median 13, p90 58, doc 10% |
| datahike.api.impl / datahike.core | 30/20 | 6 | 12-16 | 16/65 | upstream parts |
| datahike.query.plan / .lower / .execute | 51/24/97 | 23/17/17 | 73/50/84 | 94/95/82 | **mostly fork-authored** (datahike git log: plan.cljc 13 upstream-author commits and 3 Sean; single_flight/committed_report/gc_guard/optimistic 11 Sean and 4 upstream). Not an exemplar |

This suggests a candidate exemplar set: clojure.core seq functions, clojure.set, clojure.walk,
clojure.zip, rewrite-clj.zip, malli.util/malli.core public API, core.async.flow and
sci.core. `malli.core` is idiomatic but thinly documented, so its *tests* (§1.4) carry the
explanation its docstrings lack.

**What we re-implemented** (VERIFIED, from `dependency-already-does-it-audit-2026-09-23.md`
§1 and `fork-audit-*-2026-09-23.md`):

- `clojure.core.cache.wrapped/lookup-or-miss`: replaced by hand caches with stamps (audit rows
  1, 7, 15; model `DH/schema_cache.cljc:8-29`).
- Datahike commit identity (`datahike.api.impl` `commit-id`, `versioning/commit-as-db`,
  `branch-as-db`): replaced by `basis-t`/`max-tx` compared as if it were an identity (rows 6, 9).
  A SHA-256 is also taken over a git commit id (row 17, `test/cache.clj`).
- The Datahike writer's expected-head refusal and `force-branch! :expected-current-commit`:
  replaced by a `ReentrantLock` publication monitor (row 11).
- `datahike.tx-preds` / `register-tx-pred!`: replaced by fork commit 73afe782 with a
  `:datahike/validate-report` callback. Durable `:commit-listeners`: replaced by fork 2cc313a6.
  `datahike.dependency-tracking`: replaced by the fork's `:cache-context`/attribute revisions,
  which appear 0 times upstream (fork audit finding 1). Upstream's query-result cache: replaced
  by about 1,400 fork lines (finding 2).
- Malli's compiled registry (`m/children`, `m/entries`, `m/-function-info`): replaced by stored
  shape rows (row 19).

These are the vars a focus about caching, identity, serialization or notification should pull
in first. The audits are the ground truth for "the library already does it".

## 3. Evidence — prior art on code-graph context

- **Aider repo map** (VERIFIED: https://aider.chat/docs/repomap.html,
  https://aider.chat/2023/10/22/repomap.html,
  https://github.com/Aider-AI/aider/blob/main/aider/repomap.py). Tree-sitter extracts
  definition and reference tags. The graph has references→definitions edges, weighted by
  `mul × sqrt(num_refs)`: ×10 for identifiers mentioned in the chat, ×10 for long
  snake/kebab/camel names, ×0.1 for `_private` names, ×0.1 for names defined in more than 5
  files, and ×50 for references from files in the chat. NetworkX `pagerank` runs with
  personalization toward chat files and mentioned identifiers. A binary search fits the ranked
  definitions into `--map-tokens` (default 1k, 15% tolerance), and the map expands when no files
  are in the chat. **Fit:** ours is the same algorithm over better edges. Kondo gives resolved
  qualified symbols where Aider matches names, so the "defined in more than 5 files" penalty
  becomes unnecessary. Personalization becomes the focus entity plus its `:seon.fn/calls`, plus
  the symbol in an error.
- **SCIP** (VERIFIED: https://github.com/sourcegraph/scip, `scip.proto`). The model is
  Index → Document → Occurrence (range, symbol, role bitset, enclosing_range), with
  SymbolInformation carrying markdown documentation, relationships
  (reference/implementation/type-definition/definition) and signature docs. Symbols are strings,
  `<scheme> <manager> <package> <version> <descriptor>+`. There is no Clojure indexer in its
  list. **Fit:** the useful lesson is that the *package version is part of the symbol*, which
  matches finding 1: a reference var's identity includes its loaded commit. We need no SCIP
  export. Kondo is our indexer.
- **clojure-lsp analysis DB** (VERIFIED: https://clojure-lsp.io/settings/). It is built from
  bundled clj-kondo analysis of the project *and classpath dependencies*. It is cached under
  `.lsp/.cache`, and re-analysis happens only when the deps file or kondo config changes. **Fit:**
  this is prior art for exactly our plan: kondo over dependencies, recomputed per dependency
  identity. Their invalidation key (the deps file) is coarser than a per-library commit.
- **cljdoc-analyzer** (VERIFIED: https://github.com/cljdoc/cljdoc-analyzer). It analyzes at
  runtime (load-time metadata via tools.namespace, in a separate "metagetta" process), per Maven
  artifact version. It emits EDN of namespaces/publics with name, type, doc, file, line, arglists
  and `:added/:deprecated/:no-doc`. **Fit:** a runtime load catches `import-vars` re-exports that
  static analysis misses. We can get the same effect from the live JVM's `ns-publics` (the Vars
  are loaded already) if a potemkin-style library ever appears. None of ours need it (UNVERIFIED
  across all 16).
- **Clerk** (VERIFIED: https://github.com/nextjournal/clerk README). Namespaces are notebooks,
  it keeps "a dependency graph of Clojure vars" and recomputes only the changes, and it runs
  in-process. Viewers are its render functions. **Fit:** this matches the owner's "data tells
  its own story" with render functions per value. Its per-form hashing is the same idea as our
  definition digests. The viewer mechanics beyond the README are UNVERIFIED (book.clerk.vision
  returned an empty page).
- **CodexGraph** (VERIFIED abstract: https://arxiv.org/abs/2408.03910). The agent writes graph
  queries against a code graph database instead of retrieving by similarity. This is competitive
  on CrossCodeEval, SWE-bench and EvoCodeBench. **Fit:** closest to Seon. Our agents already
  query Datahike, so a library var is one more joinable row.
- **codebase-memory-mcp / codegraph MCP servers** (search results only: UNVERIFIED claims).
  These are tree-sitter or type-checker call graphs served over MCP. One reports about 10× fewer
  tokens at 83% vs 92% answer quality.
- **DocPrompting** (VERIFIED abstract: https://arxiv.org/abs/2207.05987). Retrieving
  documentation from the NL intent gives +2.85 pass@1 on CoNaLa. **CodeRAG-Bench**
  (https://arxiv.org/abs/2406.14497, via search summary: UNVERIFIED text) reports that
  retrievers struggle when lexical overlap is low. That is an argument for *graph* selection
  (resolved symbols) over lexical retrieval.
- **"What builds effective in-context examples for code generation"** (VERIFIED abstract:
  https://arxiv.org/abs/2508.06414). Removing meaningful identifier names costs up to 30 points,
  and models do not generalize insight from merely similar examples. **Fit:** show *real*
  library call sites with their real names (tests and our callers), not paraphrased snippets.
  That supports the owner's "prime the pump" claim.

## 4. Proposal — the smallest composition

**What exists:** kondo analysis, whose rows Seon already consumes (`seon.fn.analyzer`).
`:seon.fn/calls` symbol-value edges from our functions into library vars. The
`seon.render.ns/function-ai` render pair (`src/seon/render/ns.clj:744`). Datahike multi-source
queries (`:in $1 $2`, `reference-code/datahike/test/datahike/test/query_planner_test.clj:603-613`).

**Data flow:**
loaded commit per library → memoized kondo projection → reference-var rows → joined with program
rows by symbol value → ranked by focus → rendered once by the AI renderer under its budget.

**Approach:** values and a pure memoized derivation, keyed by an immutable commit id. There is no
new parser, cache or registry.

### 4.1 Rows (one entity schema, values only)

`:seon.reference/var`, an entity map with these attributes:

- `:seon.reference/sym`: a qualified symbol.
- `:seon.reference/commit`: the 40-hex loaded commit. It is a VALUE, and it is part of identity,
  as in SCIP.
- `:seon.reference/library`: the path string `reference-code/<lib>`.
- `:seon.reference/doc`: optional.
- `:seon.reference/arglists`: kondo `:arglist-strs`.
- `:seon.reference/span`: a tuple `[path row col end-row end-col]`.
- `:seon.reference/calls`: `[:set :qualified-symbol]`, the same value-edge law as
  `:seon.fn/calls`.
- `:seon.reference/upstream?`: true when blame at the merge base says the span is not
  fork-authored. This is a derived *observation* of the commit, recorded as a fact because blame
  costs seconds.
- A namespace row carries `:seon.reference/ns-doc` the same way.

Tests in library test directories are ordinary var rows with `:seon.reference/test? true`.
Their `:calls` *is* the example index: "examples of `malli.core/explain`" is the set of test rows
whose calls contain it. No example rows are stored.

**Source text is not stored.** The span plus `git cat-file -p <commit>:<path>` gives the exact
bytes. That value is immutable, so a memoized function of `[commit path]` is sufficient. This
satisfies "store a value only when it must outlive its subject": the git object outlives
everything.

**Where the rows live:** see open question Q1. Recommended: one shared *reference database*
beside the cluster store. It is never part of any branch's program, so it never merges. A query
joins it as `$ref`:

```clojure
[:find ?caller ?lib-doc
 :in $ $ref ?lib
 :where [$ ?f :seon.fn/calls ?lib] [$ ?f :seon.fn/sym ?caller]
        [$ref ?r :seon.reference/sym ?lib] [$ref ?r :seon.reference/doc ?lib-doc]]
```

### 4.2 Recompute per commit

`(reference-rows commit library-root)` is a pure function of the commit. It runs kondo `run!`
over `git archive <commit>` (or over a clean checkout at that commit) and projects the rows
through the existing `var-definition`/`var-usage` selectors. It is memoized with
`clojure.core.cache.wrapped/lookup-or-miss`, keyed by `[library commit]`. Publication compares
each library's loaded commit with the commits present in `$ref`. It transacts only the libraries
whose commit changed, retracts the old commit's rows, and leaves history to answer what existed.
Cost: 0.3-2.7 s per changed library on the CLI binary (§2.2; the JVM path is UNMEASURED). The
total is under about 12 s for a cold build of all 16. Blame for `upstream?` is measured
separately (UNMEASURED). It is a background `:io` proc and never on a turn's path. Anything over
1 s must name its cost at the publication clock row.

**Estimated size:** about 8,800 var rows × about 7 scalar datoms = 62k, plus the call edges
(about 68k for the six largest libraries, perhaps 90k for all), plus test rows (malli alone has
482). That is about 1.5-2 × 10^5 datoms, rebuilt only per changed library.

### 4.3 Link resolution

- **Where links come from:** ordinary docstrings (ns, fn) and schema `:description`s. Extraction
  is the EDN-read scan of §2.3, a pure function of the string. It stores nothing, because a
  link is a *token observed in text*, so the resolver is a query.
- **How a link resolves:**
  - `[[a.b/c]]` → the `:seon.fn/sym` row on `$`, else the `:seon.reference/sym` row on `$ref`.
  - `[[c]]` → the current namespace first (codox), then nothing. There is no best-match guessing.
  - `[[a.b]]` → the namespace row on either source.
  - Aliases are not resolved, following cljdoc.
  - A link that resolves to nothing is a typed unresolved-link finding naming the token and the
    holder. It is never silently dropped.
- **Keywords:** a qualified keyword in `:description` already names a schema row. The same scan
  reading `:ns/kw` tokens resolves it against the registry.
- **What a resolved link renders:**
  - the sym, arglists and doc;
  - the exact source span;
  - up to *k* usage examples: our callers from `:seon.fn/calls` first, then library tests,
    shortest span first;
  - `upstream?` shown, and fork spans labelled.
- **Presentation:** limits are applied once in the AI renderer of `:seon.reference/var`, the one
  render pair for that schema.

### 4.4 Ranking for a focus

The focus is a function, a namespace, or an error's symbol (instrument explanations already
carry the var).

- **Seeds:**
  - the focus's `:seon.fn/calls` into library namespaces (weight 50, Aider's chat-file boost);
  - symbols named in the error or the task (×10);
  - `[[links]]` in the focus's docstrings and in its schemas' `:description` (×10).
- **Walk:** personalized PageRank over the union graph (program `:calls` plus `$ref` `:calls`),
  with sqrt-dampened edge multiplicity and ×0.1 for `:private` vars. This is a pure function of
  (program db value, `$ref` value, seeds). It is memoized, around 10^5 edges, and expected to run
  in milliseconds (UNMEASURED).
- **Priors:**
  - ×2 for `upstream?`;
  - ×2 when the namespace is in the exemplar set of §2.4;
  - ×3 when the var appears in the "already does it" audit list for a concern the focus touches.
    This is optional and needs Q3.
- **Fill:** Aider's binary search over the ranked list into the renderer's token budget.

### 4.5 Size and deletions

Estimated lines:

| piece | lines |
|---|---|
| `seon.reference` (commit discovery, memoized projection, publication diff) | ~70 |
| link scan and resolver query | ~25 |
| ranker | ~30 |
| one schema EDN | ~15 |
| AI/HTML render pair | ~25 |
| **total** | **~165** |

That is over the ~100-line presumption, so it should land in two slices. First, rows plus the
link resolver (about 110 lines), which already gives agents `[[malli.core/explain]]` with source
and examples. Second, the ranker.

What it deletes or replaces:

- the 55 drifting `reference-code/…:line` citations, converted by one scripted sweep to
  `[[qualified/var]]`;
- the need for skills to restate library APIs (skills cite links, and the rows answer);
- `test/cache.clj`'s SHA-256-of-a-commit (audit row 17), once reference identity is the loaded
  commit.

Simplest alternative: no rows at all. The resolver would call kondo on demand for one symbol,
through the memoized per-commit projection held in memory and passed as a collection source. It
gives up nothing except history and `$ref` joins from other agents. See Q1.

## 5. Open questions for the owner

**Q1. Where do reference rows live?**
(a) **Recommended:** a separate reference Datahike database joined as `$ref`. It is queryable,
has history, never merges into the program, and one copy is shared by all clusters.
(b) Rows on each cluster's program branch. This is simplest for queries, but it copies 10^5
datoms per branch lineage and blurs the program/observation partition.
(c) No stored rows: a memoized in-memory projection per commit, passed to `d/q` as a collection
source. This needs no schema, but it is gone on restart and other agents cannot query it through
their ordinary database.

**Q2. Identity of "reference":**
(a) **Recommended:** the commit that loads (`rev-parse HEAD` of the checkout), refusing a dirty
tree, plus a separate fix so `gitlink-digests` stops disagreeing with the classpath
(finding 1). This affects the malli and babashka-process gitlinks as they stand now.
(b) The recorded index gitlink. This matches gate identity today but links source that is not
running.
(c) The Maven or git coordinate in `deps.edn`, which also covers clojure 1.12.5 via its jar
sources. Most faithful for Maven deps, but it needs jar source extraction.
A related question: should `reference-code/clojure` be repinned to `clojure-1.12.5` so that the
reference matches the runtime? Recommended: yes.

**Q3. Should the "already does it" audits become ranking data?**
(a) No: rank on the graph alone.
(b) **Recommended:** tag each re-implemented library var with the concern it owns (a docstring
`[[link]]` from the Seon function that should call it is enough, with no new attribute).
(c) A curated exemplar list as a config fact. This is a hand-maintained list, which the laws
disfavour.

**Q4. Fork spans:**
(a) **Recommended:** index them, label them `upstream? false`, and rank them down.
(b) Exclude them from context entirely. This is cleaner priming, but agents then cannot see the
code that actually runs.
(c) Treat them equally. This risks re-teaching RIP workarounds (74 RIP commits in Datahike).

**Q5. Regex permission.** The EDN-read scan avoids production regex. Confirm that it counts as
compliant, or grant a named regex for the `[[` scan.
