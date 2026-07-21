---
type: research
status: active
tags: [research, agent]
---

# Cross-language indexing into the Seon program graph (2026-07-05)

Question: can we reliably index non-Clojure source (Python, TypeScript/JS,
Java, Go, Rust, …) into the same `:seon.ns` / `:seon.fn` / `:seon.schema`
shape so the derived-context system works on SWE-bench-style repos, while the
agent edits with ordinary file/shell tools? The user asked "can we find a
really competent LSP" — this evaluates the whole solution space.

## TL;DR

**Yes, and the answer is a two-layer hybrid, not a single "competent LSP".**

- **Batch layer: SCIP** (Sourcegraph Code Intelligence Protocol, Apache-2.0,
  now under the `scip-code` org, v0.9.0 released 2026-06-29). One protobuf
  format, per-language indexers, and the schema carries exactly what our
  entities need: `SymbolInformation` has `documentation`,
  `signature_documentation` (the typed signature as markdown),
  `display_name`, `kind` (Class/Method/Function/Struct/Interface/…),
  `enclosing_symbol` (the hierarchy), and `relationships`
  (implements/references/type-definition). The symbol grammar itself encodes
  package → type → term/method descriptors, i.e. the namespace hierarchy is
  in the symbol string. `scip print --json` / the Go bindings make ingestion
  trivial. This is a direct ETL into `:seon.ns` / `:seon.fn` / `:seon.schema`.
- **Live layer: LSP** for post-edit freshness and on-demand depth
  (`documentSymbol` after an edit, `hover` for a typed signature,
  `references`/`definition` as agent verbs). Wrap via **multilspy**
  (Microsoft) or Serena's **Solid-LSP** fork (synchronous, agent-oriented,
  40+ languages) rather than hand-rolling JSON-RPC clients.
- **Fallback layer: tree-sitter** (syntax-only) for anything the semantic
  indexers can't build — always produces names/signatures-as-written/doc
  comments; this is what Aider's repo-map ships on and it demonstrably works.
- The **biggest risk is build requirements**: scip-java and scip-clang need a
  working compile; scip-python and scip-typescript mostly need only
  dependency install; rust-analyzer/gopls need a resolvable module graph.
  SWE-bench images already build the repo (tests must run), so in-image
  indexing is usually feasible — but budget for per-repo indexing failures
  and degrade to tree-sitter, never to "no graph".
- Evidence this helps: LSP tooling lifted a strong agent from **68.4% →
  70.4%** resolved on SWE-bench-style eval while cutting turns 82 → 77
  (SWE-Master, arXiv 2602.03411); LocAgent's graph localization improved
  Pass@10 issue resolution by ~12% (ACL 2025); Aider's tree-sitter+PageRank
  repo map is long-proven. Structured repo views are worth real points; they
  are not magic.

## 1. SCIP — the batch indexing candidate

### The protocol itself

- Repo: [scip-code/scip](https://github.com/scip-code/scip) (moved from
  `sourcegraph/scip`; same project). License **Apache-2.0**. Latest release
  **v0.9.0, 2026-06-29** — actively maintained.
- Design: a protobuf **transmission format** (`scip.proto`) replacing LSIF;
  deliberately easier to produce and debug, robust to partial indexer bugs.
  Consumers include Sourcegraph, Mozilla Searchfox, and Meta's Glean.
- What a document carries per symbol (`SymbolInformation`):
  - `symbol` — a **grammar-structured global name**:
    `scheme manager pkg version descriptor+` where descriptors are
    `namespace/` `type#` `term.` `method().` etc. The namespace/type/member
    hierarchy is parseable from the symbol string alone.
  - `display_name`, `kind` — a rich enum (Class, Method, Function, Struct,
    Enum, Interface, TypeAlias, Module/Namespace, Field, …). Docs say to use
    `kind`, not the descriptor suffix, for classification.
  - `documentation` — the docstring/comment (markdown).
  - `signature_documentation` — **the typed signature** rendered as a code
    block ("use signature_documentation to document the method/class/type
    signature"). This is our `:seon.fn` signature source.
  - `enclosing_symbol` — parent scope, i.e. the containment tree
    (fn → class → module) without re-parsing.
  - `relationships` — is_implementation / is_reference / is_type_definition /
    is_definition links between symbols.
  - `Occurrence` rows give every definition/reference with ranges and
    `enclosing_range` — enough to slice exact source text for each fn out of
    the file (SCIP does not embed source bodies; we read them by range).
- Tooling: the `scip` CLI (`print` (JSON dump), `snapshot`, `stats`,
  `convert`, `lint`) plus first-class Go/TS/Rust bindings. Ingestion is
  "protobuf → datoms", no bespoke parser.

Verdict on schema fit: **sufficient to populate all three entity kinds** —
`:seon.ns` from namespace/module descriptors + file documents, `:seon.fn`
from Function/Method symbols (name, doc, signature_documentation, source via
enclosing_range), `:seon.schema` from Class/Struct/Interface/Enum/TypeAlias
symbols with their field children (via enclosing_symbol).

### Indexer-by-indexer reality (2026)

| Indexer | Languages | Built on | Build needed? | Maturity |
|---|---|---|---|---|
| scip-typescript | TS, JS | TypeScript typechecker | `npm/yarn install` only; `--infer-tsconfig` for plain JS | High — Sourcegraph's flagship, years in prod |
| scip-python | Python | **Pyright** (thin fork, "no substantial changes to pyright") | No build; `--environment` flag can even skip pip introspection | High for indexing; pyright-quality inference on untyped code |
| scip-java | Java, Scala, Kotlin | javac/scalac **compiler plugins** | **Yes — runs inside a real Gradle/Maven/sbt compile**; auto-config works for most builds but Gradle 8 quirks exist (issue #544), may clean compile caches | High for Java/Scala; **Kotlin support less mature** |
| scip-go | Go | go/packages type-checker | Needs resolvable modules (`go mod download`); no compile artifacts | High; known gaps: no stdlib jump-to, cross-repo nav limits |
| rust-analyzer `scip` | Rust | rust-analyzer itself (native `rust-analyzer scip .` CLI; `scip-rust` is a thin wrapper) | Needs cargo/rustc on PATH + metadata resolution, not a full build | High — maintained in-tree by rust-lang |
| scip-clang | C, C++ | clang | **Yes — needs `compile_commands.json`** (CMake/Bazel/Meson emit it; Bear can intercept Make); Linux-oriented | Medium |
| scip-ruby | Ruby | **Sorbet** | No build, but quality tracks Sorbet adoption; untyped files indexed best-effort (`typed: false`) | Medium — best on Sorbet codebases |
| scip-dotnet | C#, VB | Roslyn | Needs .NET 8 SDK + restorable solution | Medium (v0.1.x on NuGet; C#-version-lag issues reported) |
| scip-dart, scip-php, debian-lsp | Dart, PHP, … | — | — | Community/lower |

Speed: all are batch compiles of the repo — minutes for large repos, seconds
for typical SWE-bench-sized ones; scip-python and scip-typescript are the
fastest paths since they skip real compilation. `scip-io`
([GlitterKill/scip-io](https://github.com/GlitterKill/scip-io)) is a
community orchestrator that installs/runs indexers and **merges multi-language
indexes** — worth evaluating before writing our own driver.

## 2. LSP as the live layer

LSP servers give the same information interactively, per-file, always fresh:

- `textDocument/documentSymbol` — hierarchical symbols of one file (module →
  class → fn), the primitive for "re-index this file after an edit"
  (`didChange`/`didSave` → refresh that document's entities; sub-second).
- `textDocument/hover` — **typed signature + doc** for a symbol, even when
  inferred (this is where untyped-Python signatures come from).
- `workspace/symbol`, `references`, `definition`, `implementation` — the
  navigation verbs, useful as agent tools beyond indexing.

Servers per language (all healthy in 2026): **basedpyright/pyright**
(Python), **typescript-language-server** today with **tsgo** arriving — the
Go-native TypeScript 7.0 is at RC (June 2026), language service "implemented
and working well", ~10x faster; **gopls**, **rust-analyzer**, **jdtls**
(Java — slow to warm, needs project import), OmniSharp/Roslyn LS (C#),
clangd (C/C++, needs compile_commands.json).

Client libraries so we don't hand-roll JSON-RPC:

- [microsoft/multilspy](https://github.com/microsoft/multilspy) — Python LSP
  client library from the NeurIPS 2023 Monitor-Guided Decoding work; handles
  server download/setup/lifecycle; supports Python, Rust, Java, Go,
  JavaScript, Ruby, C#, Dart. The de-facto research standard (used by
  SWE-agent-adjacent work).
- [oraios/serena](https://github.com/oraios/serena) — MCP "IDE for your
  agent". Its **Solid-LSP** layer (a multilspy fork) makes LSP calls
  synchronous/transactional for agents and claims **40+ languages** including
  everything we care about (and Clojure). Even if we don't run Serena as an
  MCP server, Solid-LSP is the best-engineered agent-facing LSP wrapper to
  copy or vendor.

Practicality of keeping N servers warm: each server is a process with real
memory (jdtls and rust-analyzer are the heavy ones, hundreds of MB to GB on
big repos). For SWE-bench that's fine — **one repo, one language, one server
per task container**. For a general harness, lazy-start per language with an
idle-kill (the dg-worker pattern) is the proven shape. Warm-up cost matters:
jdtls/rust-analyzer can take tens of seconds to first-answer on a cold repo;
issue the boot at container start, not at first agent query.

## 3. Alternatives, briefly

- **tree-sitter** — syntax-only, universal (~every language has a grammar),
  milliseconds per file, zero build requirements. Gives: function/class/
  method names, parameter lists **as written** (with type annotations only if
  present in source), doc comments, nesting. Does NOT give: resolved types,
  cross-file references (only name-match heuristics), inferred signatures.
  **Good enough for namespace/function extraction as the universal
  fallback** — it is exactly what Aider's repo-map uses, successfully. Weak
  for `:seon.schema`-grade type info on untyped code.
- **universal-ctags** — strictly less than tree-sitter (flat tags, no ranges/
  hierarchy in some languages, no docs). Skip; only relevant where no
  tree-sitter grammar exists.
- **GitHub stack-graphs** — the repo was **archived 2025-09-09** (still
  powering GitHub's precise nav internally, but not a platform to build on).
  Skip.
- **Kythe (Google)** — US team laid off April 2024, maintenance-mode releases
  since (0.0.70 added a Rust extractor). Heavy Bazel-centric extraction.
  Effectively dead for outside adopters. Skip.
- **Glean (Meta)** — serious multi-language fact database; notably it
  **ingests SCIP** for several languages (e.g. its .NET support is literally
  scip-dotnet). Overkill as our store (we have datahike), but validates
  SCIP-as-lingua-franca.
- **ast-grep** — structural search/rewrite on tree-sitter; a great agent
  *editing/search verb*, not an indexer. Complementary.

## 4. The mapping problem — per-language fit to `:seon.ns` / `:seon.fn` / `:seon.schema`

Namespace mapping (mostly clean):

- **Python** module = file, package = dir → `:seon.ns` per module. Clean.
- **Go** package = dir (multi-file!) → one `:seon.ns` per package; the ns has
  N source files — our "one file per namespace" assumption must relax to
  ns→files (cardinality-many).
- **Java** package + the class as the dominant container → model package as
  the ns and classes as schema-entities whose methods are fns; or treat the
  class as a sub-ns. The `enclosing_symbol` chain preserves both readings.
- **Rust** module tree (`mod` can nest several per file, or span files) →
  ns = module path, again ns↔file is not 1:1.
- **TS/JS** file ≈ module — fine; barrel files (`index.ts` re-exports) mean
  the *public* surface of a directory-package is a derived view, which our
  render layer already knows how to do. CommonJS dynamic patterns degrade to
  tree-sitter-level info.
- **C** has no namespaces → ns = file (header/impl pairing is a heuristic).
  Accept the degradation.

Schema mapping (`:seon.schema` per language):

- TS: `interface`/`type`/`enum` (SCIP kinds Interface/TypeAlias/Enum).
- Python: `class`, and especially TypedDict / dataclass / pydantic models —
  all just classes to the indexer; detecting "data-shape class" (base-class
  check) is a cheap enrichment pass.
- Go: `struct` + `interface` types; Java: classes/records/interfaces; Rust:
  `struct`/`enum`/`trait`. All are first-class SCIP symbols with fields as
  enclosed children.

Signature fidelity by approach:

- **SCIP**: `signature_documentation` — compiler-grade, includes inferred
  types (pyright inference for Python). Best batch source.
- **LSP hover**: same quality, on demand, always fresh.
- **tree-sitter**: only what's literally in the source. In gradually-typed
  Python/JS this is often `def f(x, y):` — name + arity, no types. Fine for
  cards, weak for contracts.

There is no Malli on the other side — `:seon.schema` for foreign code is a
*descriptive* record (fields + types as strings/structured data), not an
enforced contract. That's fine: the context system needs discoverability,
not instrumentation, for benchmark repos.

## 5. Prior art — what agent systems actually do

- **Aider repo-map**: tree-sitter tags → reference graph → **PageRank**
  (personalized toward the files in play) → top-ranked symbol signatures
  rendered into the prompt within a token budget. Proven accuracy lift over
  naive file inclusion; the closest existing thing to our rendered cards.
  ([aider.chat repomap post](https://aider.chat/2023/10/22/repomap.html))
- **SWE-agent / mini-SWE-agent / Claude Code**: file-viewer + grep/bash only
  — no semantic index — and they set strong SWE-bench baselines. Lesson: the
  index is an *efficiency and localization* win, not a hard requirement.
- **SWE-Master (arXiv 2602.03411)**: adding LSP tools to the agent →
  **68.4% → 70.4%** resolution, turns 82 → 77 ("deterministic semantic
  context instead of noisy keyword search"). The cleanest direct evidence.
- **LocAgent (ACL 2025)**: parse the repo into a heterogeneous graph (files/
  classes/functions; import/invoke/inherit edges), let the agent multi-hop —
  92.7% file-level localization, **+12% Pass@10 issue resolution**. This is
  structurally the SAME idea as our program graph, built with ~tree-sitter-
  level parsing, validating the derived-context thesis in other languages.
- **Serena**: LSP-symbols-as-agent-verbs (find_symbol, insert_after_symbol,
  references) — widely adopted as an MCP server through 2025-26; proof that
  warm language servers in an agent loop are operationally practical.
- **Sourcegraph Amp** (Cody's 2026 successor): SCIP-powered global code graph
  as the context backend for their agent — the commercial version of exactly
  this architecture.
- **Monitor-Guided Decoding (NeurIPS 2023)**: LSP static analysis constraining
  generation, showing repo-context static analysis beats larger unaided
  models — the origin of multilspy.

## Recommended architecture

Keep ONE ingestion contract: something produces per-file symbol trees
(name, kind, signature, doc, range, enclosing) → one ETL transacts
`:seon.ns` / `:seon.fn` / `:seon.schema` datoms (source text sliced by
range into the blob store). Three producers, best-available per repo:

1. **Batch (task start): SCIP indexer** for the repo's language, run inside
   the task container after its normal dependency install. `scip print
   --json` → ETL. Cache per repo+commit.
2. **Live (after each agent edit): LSP `documentSymbol` (+ `hover` for
   signatures)** on the touched file → re-derive that file's entities. This
   keeps render-time context honest without re-running the batch indexer.
   Also expose `references`/`definition` as agent verbs.
3. **Fallback (indexer failed / exotic language): tree-sitter extraction** —
   same ETL shape, lower fidelity, never zero graph.

Per-language plan:

| Language | Batch | Live server | Confidence |
|---|---|---|---|
| Python | scip-python (pyright) — no build | basedpyright | High |
| TS/JS | scip-typescript — npm install only | typescript-language-server → tsgo (TS 7 RC) | High |
| Go | scip-go — go mod download | gopls | High |
| Rust | rust-analyzer scip | rust-analyzer | High |
| Java | scip-java — needs real Maven/Gradle compile | jdtls | Medium (build fragility) |
| C/C++ | scip-clang — needs compile_commands.json (Bear for Make) | clangd | Medium-low |
| Ruby | scip-ruby (Sorbet) or fall back to tree-sitter | ruby-lsp | Medium |
| C# | scip-dotnet | Roslyn LS | Medium |

Build order: start with Python + TS (covers SWE-bench classic + most of
Multilingual's volume, both indexers build-free), tree-sitter fallback in the
same milestone, then Go/Rust (cheap), then Java/C++ (build-coupled, do last).
Evaluate `scip-io` and Serena's Solid-LSP before writing orchestration code.

### Biggest risks

1. **Build-coupled indexers** (Java, C/C++, C#): a repo that doesn't compile
   in our container yields no SCIP index. Mitigation: SWE-bench images
   already build (tests run), so index inside the eval image; fall back to
   tree-sitter + LSP-hover-on-demand when compile fails.
2. **Staleness between batch index and agent edits**: an agent that renames a
   fn must see the new graph. The LSP live layer is the answer; if it's ever
   absent, tree-sitter re-parse of dirty files is the cheap stand-in.
3. **Signature fidelity on untyped code** (plain JS, unannotated Python,
   `typed: false` Ruby): pyright/tsserver inference helps but cards will
   sometimes show `(x, y) -> Any`. Don't overclaim contracts in the render.
4. **ns↔file non-1:1** (Go packages, Rust modules, Java class-as-container):
   the pod's current assumptions about one-file namespaces need the ns→files
   relation to be cardinality-many before foreign import.
5. **Monorepos / multi-project repos**: scip-java auto-config and tsconfig
   discovery both wobble on unusual layouts (Gradle 8 issue #544; multiple
   tsconfigs). Index per sub-project and merge (scip supports index merging;
   scip-io does this).
6. **Kotlin**: explicitly less mature in scip-java — if a benchmark includes
   Kotlin, verify early.
7. **Memory of warm servers** (jdtls, rust-analyzer): lazy-start +
   idle-unload per language, one server per task container.

## The Seon mapping — namespace-as-place, not tools-as-transcript

Owner constraint (2026-07-05): do NOT turn this into the standard LSP-MCP
shape where the agent calls `find_symbol` / `hover` and the results pile up
as historical tool-call turns. Seon's model stays: **the agent lives in a
Clojure REPL, is "in" a namespace, and its ambient context is derived from
the db at render time.** Foreign code must slot into that, not replace it.

The key inversion vs. Serena/multilspy-style tooling: there, the LSP
*answer* is the context (appended forever to the transcript). Here, SCIP/LSP
output is **written into the db as program-graph datoms**, and context stays
a function of the db — the same render pipeline, recomputed every turn. A
tool result that matters becomes a datom; the transcript stays thin.

### How it works, piece by piece

- **Foreign modules ARE `:seon.ns` entities.** A Python module
  (`django.db.models.query`), a Go package, a Rust module path, a TS
  file-module — each is one ns entity (plus a language attr), with ns→files
  cardinality-many (Go packages span files), and its `:seon.fn` /
  `:seon.schema` children from the SCIP ETL. The agent doesn't "open files";
  files are a storage detail hanging off the ns.
- **"Switching to a namespace" = moving the agent's current-ns pointer to a
  foreign ns.** The same `in-ns`-shaped verb the agent already has, aimed at
  a foreign ns name. The verb fetches nothing; it retargets the pointer. The
  NEXT render derives, exactly as today:
  - the **full card** of the current ns — every fn signature (from SCIP
    `signature_documentation`) + docstring first line, its schema entities;
  - **compact cards of its imports** — the require-closure/twins gradient,
    fed by SCIP import/reference relationships instead of Clojure
    `:require`;
  - optionally reverse-deps ("who calls into this ns") — one more section
    function, same pattern.
- **The agent keeps thinking in Clojure.** The REPL stays a Clojure REPL;
  foreign code is never eval'd there — it is *data the agent queries and
  text the agent edits*. Graph questions are Clojure verbs returning data
  (`(code/callers …)`, `(code/schema "pandas.DataFrame")` — pull/query over
  the program graph, rendered under the existing caps). Running the foreign
  code is the shell verb it already has (`pytest`, `go test`, `cargo
  build`); editing is the file-edit verb. No new execution model, no new
  noun.
- **Edits refresh the graph, not the transcript.** After a file edit, the
  live layer (LSP `documentSymbol` + `hover`, or tree-sitter re-parse of the
  dirty file) re-derives that file's ns/fn/schema datoms — the
  foreign-language analog of the analyzer tee on agent-eval'd Clojure. The
  next render of the current-ns card simply shows the new signature. The
  agent never re-reads its own edit out of history; the ambient view is
  current. Derive-don't-store, self-healing.
- **On-demand depth is a datom/blob read, not an accumulated turn.** When
  the agent needs a fn body, the verb pulls the source slice (by SCIP
  `enclosing_range`) from the blob store into a rendered block — expanded
  while relevant, re-compacted by the existing gradient when the agent moves
  on. A deep LSP answer worth keeping (an inferred signature on untyped
  code) is transacted onto the `:seon.fn` entity so every later render has
  it for free.
- **What "file?" resolves to per language:** Python/TS — ns ≈ file, the
  intuition holds. Go/Rust/Java — the ns is the package/module/class and its
  card aggregates across files; the agent *navigates* by ns but *edits* a
  specific file. That navigate-by-ns / edit-by-file split is the one
  genuinely new seam, and it is why ns→files must go cardinality-many before
  foreign import.

Net: SCIP/LSP are **producers for the analyzer slot** — the one-mechanism
rule "program-graph entities come from the analyzer plus a source string"
generalizes to "a per-language analyzer plus a source string". The context
engine, the ns gradient, the render caps, and the Clojure verb surface do
not change shape at all.

## Key sources

- [scip-code/scip](https://github.com/scip-code/scip) — protocol, v0.9.0
  (2026-06-29), Apache-2.0; `scip.proto`
  ([sourcegraph/scip scip.proto](https://github.com/sourcegraph/scip/blob/main/scip.proto))
- [SCIP announcement](https://sourcegraph.com/blog/announcing-scip) ·
  [scip-python blog](https://sourcegraph.com/blog/scip-python) ·
  [scip-typescript blog](https://sourcegraph.com/blog/announcing-scip-typescript)
- Indexers: [scip-python](https://github.com/sourcegraph/scip-python) ·
  [scip-typescript](https://github.com/sourcegraph/scip-typescript) ·
  [scip-java + getting-started](https://sourcegraph.github.io/scip-java/docs/getting-started.html) ·
  [scip-go](https://github.com/sourcegraph/scip-go) ·
  [rust-analyzer scip CLI](https://rust-lang.github.io/rust-analyzer/rust_analyzer/cli/scip/index.html) ·
  [scip-clang](https://github.com/sourcegraph/scip-clang) ·
  [scip-ruby](https://github.com/sourcegraph/scip-ruby) ·
  [scip-dotnet](https://github.com/sourcegraph/scip-dotnet) ·
  [scip-io orchestrator](https://github.com/GlitterKill/scip-io)
- LSP layer: [microsoft/multilspy](https://github.com/microsoft/multilspy) ·
  [oraios/serena](https://github.com/oraios/serena) +
  [language support](https://oraios.github.io/serena/01-about/020_programming-languages.html) ·
  [typescript-go / TS 7 RC](https://github.com/microsoft/typescript-go)
- Alternatives: [stack-graphs (archived 2025-09-09)](https://github.com/github/stack-graphs) ·
  [Kythe](https://github.com/kythe/kythe) · [Glean scip-dotnet indexer](https://glean.software/docs/indexer/scip-dotnet/)
- Evidence: [Aider repo-map](https://aider.chat/2023/10/22/repomap.html) ·
  [LocAgent, ACL 2025](https://arxiv.org/abs/2503.09089) ·
  [SWE-Master LSP ablation](https://arxiv.org/pdf/2602.03411) ·
  [monitors4codegen](https://github.com/microsoft/monitors4codegen)
- Benchmarks: [SWE-bench Multilingual](https://www.swebench.com/multilingual.html)
  (300 tasks, 9 languages) · [Multi-SWE-bench](https://multi-swe-bench.github.io/)
  (1,632 instances; Java/TS/JS/Go/Rust/C/C++)
