---
title: "Reading and editing files from an agent's REPL"
type: research
status: proposed
created: 2026-10-08
---

# Reading and editing files from an agent's REPL

Seon already has classpath source lookup, one Markdown reference resolver, conditional filesystem writes and
exact text edits. Compose those functions: resolve a name or based path against an explicit source basis, read
its bytes, derive a location, propose replacement bytes, check them, then install against the observed digest.

Ranked recommendation: first make the references and read operations already offered to agents work; next make
conditional edits useful for arbitrary repositories; add language navigation when actual work needs it. Keep
Seon declarations authoritative in their branch's program entities. General repository files remain files in
Git. Share the conditional file installer, not a pretend universal program model. This is design research only;
the independently authored files-opus note was not read.

## 1. First need: read what a task names

Authorities: [owner rulings](../review/owner-rulings.md#4-tasks-and-tests),
[reference model](../references/references.md), and AGENTS.md's Words, Write tasks as data, Right context and
Work as a Seon agent sections. The named landings are CS-01c2 `106e8c25e`, REF-1 `a807ec0b4` / `2de59d8b5`
and CS-01c5 `c9a496d6f`. They establish useful source mechanisms; they do not establish installation in default.

### What was actually available

MCP probes used agent files-sol, cluster default, with no source loading, provider request or filesystem write.
The first form was `(seon.context/current-state)` in SCI. It returned in 3 ms, allocated 1.7 MB and counted 742
armed calls. Default's source archive was `32ca5f2e5f8b444a787569084e5c12b3f78638c4`, PID 9276, started at
2026-10-08T09:19:00.823Z. At the initial status read, heap used was about 2.17 GB, live about 1.02 GB, cap 4 GB;
the shared cache held about 507 MB of its 512 MB budget. Health was faulted: memory-pressure observations and
analysis/publication overruns. These are shared findings, not evidence that these small lookups are slow.

| Probe | Result | Evaluation cost |
|---|---|---|
| Host metadata and resources, five names/four paths | Malli loaded; clojure.java.browse unloaded but its source resource exists; Orchard absent | 2 ms; JVM allocation not returned |
| Host dependency-cache lookup for clojure.java.browse | browse-url at row 68, col 1; namespace remains unloaded | 1 ms; JVM allocation not returned |
| Corrected clj-kondo analysis of that one resource | namespace documentation plus browse-url doc, arglist and rows 68–88; remains unloaded | 14 ms total, 12.865125 ms within run! |
| SCI `(doc malli.core/validator)` | documentation-unavailable error value | 1 ms, 0.4 MB, 235 calls |
| SCI `(clojure.repl/source malli.core/validator)` | nil, output "Source not found" | 1 ms, 1.1 MB, 194 calls |
| SCI `(my.fs/section "reference-code/rewrite-clj/README.md")` | unable to resolve my.fs/section | 0 ms reported |
| SCI stat of AGENTS.md | regular file, 58,210 bytes | 62 ms, 69.0 MB, 1,244 calls |
| SCI read of its first 128 bytes | window digest, eof false, no whole-file digest | 35 ms, 26.9 MB, 1,123 calls |

The live SCI gap in library source is already `seon.sci.eval/source-of-a-library-var-answers-source-not-found`
(S1-08; `ac212200d` landed during this research, not proved installed here); library documentation belongs to
S1-06. Missing section is an installation gap for CS-01c5. Stat's work is disproportionate to one metadata read:
`seon.effect/a-file-stat-costs-a-thousand-armed-calls` already records that class. The repeated automatic-test
reply was `{:seon.test/passed? false :seon.turn.loop/red []}`. This is not green evidence; F10 addresses a related
empty/skipped reporting class, but this lane did not prove that skipping caused these replies. A follow-up card
must distinguish nothing selected from tests failed at this entrance. No restart was attempted.

### Globally addressable does not mean loaded or executable

| Subject | Address by symbol? | What can resolve without loading? |
|---|---|---|
| Seon declaration on an agent branch | Yes: qualified function/test symbol; namespace is a simple symbol | Stored entity/source/span through the existing program owners, whether a corresponding JVM Var exists or not |
| Clojure namespace with canonical source on the active classpath | Namespace symbol, e.g. clojure.java.browse | Resource URL; namespace declaration/location from analysis of that resource |
| Var in such an unloaded namespace | Qualified symbol, e.g. clojure.java.browse/browse-url | Static definition and authored doc/arglists via clj-kondo; no live Var, root value or evaluation-derived metadata |
| Loaded library Var | Qualified symbol, including private Vars when deliberately requested | find-ns/find-var, metadata, resource URL and clojure.repl/source-fn |
| Code elsewhere in reference-code | Only if that source root is on this classpath or the branch indexes it | Otherwise based relative path and static analysis; being in the quarry is not classpath membership |
| Babashka scripts, .cljs-only namespaces, JS/Python/shell/SQL | No general JVM Var address | Based file reference and the applicable language tools |
| AOT-only/dynamically created namespace, generated Var or Java class | Possibly live name, possibly no source | Metadata/resource if actually present; otherwise typed source unavailable |

Namespaces share the classloader's name space, not a globally unique identity across every dependency version.
Two dependencies can ship the same resource. The active classpath order selects it; enumerate alternatives only
to diagnose ambiguity. Branch program entities take precedence over the host's different definition. A simple
namespace symbol needs explicit recognition: the landed `seon.ref/references` contracts and span classifier
currently accept qualified symbols/keywords, and `seon.ref/library-name` handles loaded Vars, not unloaded
namespaces. The broad namespace promise in the earlier note/card still needs implementation.

### Reuse the dependency seams

1. For branch code use `seon.sci.eval/stored-source` and the stored declaration's span. For loaded library Vars
   use `clojure.core/find-ns`, `clojure.core/find-var`, `clojure.core/meta`, `clojure.java.io/resource` and
   `clojure.repl/source-fn`. The latter requires a resolvable Var and readable classpath source; it cannot find
   an unloaded Var. The SCI lookup owners must offer the same result as data through the one value printer.
2. For an unloaded namespace derive the canonical resource name (dots to slashes, hyphens to underscores),
   inspect .clj then .cljc for JVM source, and verify its declared namespace with existing analysis. Resource
   lookup does not load anything. Noncanonical source can be found by existing indexed filename evidence; do
   not silently scan the entire classpath on each miss or call requiring-resolve to make the reference work.
3. `seon.fn.analyzer/cache-directory` already identifies the loaded dependency set. Its clj-kondo namespace
   cache contained browse-url's row/column and the jar entry filename, but **not** doc, arglists or end position.
   This cache is not the rich analysis result described in clj-kondo's analysis documentation. Use it for a
   location hint, verify the supplying resource, then ask `clj-kondo.core/run!` about that one file for complete
   source/documentation. Cache that derivation in `seon.cache` by resource content, language/features, analyzer
   version/config and hooks. Changed unrelated database facts must not trigger it.
4. The cache read proved here uses `clj-kondo.impl.cache/from-cache-1` and
   `clj-kondo.impl.core/resolve-cache-dir`, both implementation APIs. Do not spread those calls through Seon.
   Prefer run! on one resource as the public seam; if measurements justify the hint, ask the maintained fork
   for a supported lookup instead. `seon.fn.analyzer/analyze-dependencies!` is the existing fill owner; no new
   dependency scan or independent cache. Never refill a live shared cache merely to render a reference.

Source seams were read at gitlinks: clj-kondo `57252e07975710aa579b24f0d1b2b1e04195caa2`, rewrite-clj
`60782e501aaf312cb90c9ff0bee05d5da5125563`, reference Clojure
`3bc2b3e91fdff462620d84bbdfd40379d9c562e5`. The runtime Clojure resource is instead the Maven artifact 1.12.5;
the reference checkout does not prove its identity. Malli's runtime URL named
`pins/reference-code-malli/418df4417827b66846b799b5e87267a344dffc29/src/malli/core.cljc`, matching the gitlink
at inspection, rather than the earlier research's older Malli pin.

**Revision provenance:** local pinned extracts carry the project/revision in their resource URL, as CS-01c2
uses. The Seon archive carries the loaded source commit. Maven resources carry the artifact/version in the jar
URL; attach its resolved dependency coordinate and artifact digest, not an invented Git revision. Git deps
normally live under the tools.deps git cache, not ~/.m2; use the loaded dependency basis's git SHA and selected
root. Reuse tools.deps basis/root provenance rather than parsing arbitrary URL text to guess a coordinate.
A mutable local root has no immutable revision unless Git/content evidence establishes one. A sources classifier
jar can add readable Java source, but neither a Maven version nor Orchard guarantees that source exists.

[Orchard](https://github.com/clojure-emacs/orchard/tree/329ac7464e324758f3443918fe7598eca47327cf)
was not on the classpath. Its orchard.namespace/canonical-source does canonical io/resource lookup;
orchard.info/clj-meta and orchard.meta/resolve-var resolve loaded namespaces/Vars. It adds useful Java and
ClojureScript information, but it is not an unloaded-Clojure definition parser. Recommendation: retain the
already used core calls plus clj-kondo; adopt Orchard when Java-member navigation is a concrete requirement.

## 2. Second need: refer to any repository file and find a place in it

Keep the ruled plain types: Clojure code symbol, schema keyword, based relative path string. Do not add EDN
tags or turn Python names into fake JVM symbols. A file reference is portable when the same repository/pinned
dependency is materialized on each machine, not when an absolute path happens to exist on this one.

The installed source of `seon.ref/locate` resolves checkout paths against the loaded archive; reference-code
paths resolve against its gitlink's extracted pin. `seon.ref/document` uses commonmark's source spans and
heading-anchor IdGenerator; `seon.ref/section` reads a heading and its descendants up to the next heading of
equal or higher level, preserving authored Markdown. The current `seon.ref/references` still reports path
candidates as unresolved until CS-01c3. Its relative Markdown links resolve from the containing document's
directory; based spans resolve from their named base. Preserve this distinction.

| File | Durable reference/selector | Meaning and limit |
|---|---|---|
| Markdown | checkout-relative path or reference-code/project/path, with #heading | Current commonmark heading anchor; renaming/deleting it makes a visible problem; duplicate-heading suffixes can shift |
| Clojure classpath source | Namespace/qualified Var symbol | Analyze the selected resource to derive file/range; no authored line |
| Clojure outside the classpath | Based .clj path plus declaration selector | Parser/static analysis may identify it; it is not an installed Var. Needs an explicit extension to the "code is never a path" rule |
| JS/TS/Python and other code | Based file path plus parser-derived qualified declaration within that file | Name includes lexical container; ambiguity returns candidates. A file-local name is not a universal semantic ID |
| Shell/SQL/config without named declarations | Based path plus unique exact-text search | Search the actual file; zero or several matches are findings. Do not guess a line or fuzzy-match an edit |
| JSON data | Based path plus JSON Pointer, when that selector is declared for the media type | Stable object keys; array indices shift when items move |
| Other plain text/logs/tabular data | Based path plus unique quote/context, or a format's actual record key | No universal stable anchor. Preserve immutable historical basis for recorded evidence |

For other text, [W3C TextQuoteSelector](https://www.w3.org/TR/annotation-model/#text-quote-selector) supplies
an existing exact/prefix/suffix model; it survives movement while the quotation stays unique. It does not
survive arbitrary edits. Use that vocabulary for a proposed search selector, not an ad hoc regex dialect.
[JSON Pointer](https://www.rfc-editor.org/rfc/rfc6901.html) supplies a data-key selector; its URI fragment form
depends on the media type adopting it. YAML/TOML/EDN do not automatically inherit JSON's fragment semantics.
For plain text the simplest first capability is the existing file read plus an explicit exact search request;
keep its selector separate from #heading until the owner chooses an authored representation. The returned
range is evidence at a digest, not a promise that the same coordinates remain valid tomorrow.

For an arbitrary external repository, the checkout is an explicit input selected once for the agent/task.
Inside it, src/, docs/, scripts/, package.json and other based paths retain the same rule; reference-code pins
are a Seon-specific base when present. Outside that checkout, another machine needs a declared repository or
artifact base and its revision/acquisition information. /Users/sean and ~/.m2 are implementation locations,
never durable authored references. Do not fabricate portability for a personal file with no shared artifact.

### Standards fit at different points

| Standard | Fits | Does not supply |
|---|---|---|
| [RFC 3986](https://www.rfc-editor.org/rfc/rfc3986.html) relative references | Resolve paths against a named/document base; fragments have media-specific meaning | A universal code/quote selector or repository acquisition |
| [LSP Location](https://raw.githubusercontent.com/microsoft/language-server-protocol/gh-pages/_specifications/lsp/3.17/types/location.md), [Position](https://raw.githubusercontent.com/microsoft/language-server-protocol/gh-pages/_specifications/lsp/3.17/types/position.md) | Editor output URI + current range; zero-based positions, negotiated encoding (UTF-16 default) | Stable reference across edits/machines; not every URI is file:// |
| [LSP DocumentSymbol](https://raw.githubusercontent.com/microsoft/language-server-protocol/gh-pages/_specifications/lsp/3.17/language/documentSymbol.md) | Language-server navigation, lexical hierarchy and current definition range | Persistent symbol identity; servers and project configuration still matter |
| [LSP TextDocumentEdit](https://raw.githubusercontent.com/microsoft/language-server-protocol/gh-pages/_specifications/lsp/3.17/types/textDocumentEdit.md) | Nonoverlapping edits against a versioned document; borrow this apply contract | Disk digest CAS or exclusion of external writers |
| [SARIF 2.1.0 §§3.4, 3.30, 3.57](https://docs.oasis-open.org/sarif/sarif/v2.1.0/os/sarif-v2.1.0-os.html) | Portable artifact URI/base and diagnostic regions/fixes at an analyzed basis | A moving semantic anchor; its regions are coordinates, not names |
| [git blame -L](https://git-scm.com/docs/git-blame), [git log -L](https://git-scm.com/docs/git-log) | Authorship/history and revision-specific line/function ranges using Git's own drivers | A current cross-language resolver; function matching depends on language patterns |

Use SARIF-shaped locations as lint evidence and LSP-shaped locations/edits at language-tool boundaries, deriving
coordinates from the same bytes and converting encodings explicitly. Git commit + path + derived range gives
reproducible historical evidence. Neither blame, an LSP range nor #L10-L20 should become a current authored
reference. The earlier note's line-fragment exception is a proposed exception; the landed resolver flags path:N
and section treats a fragment as a heading. Do not claim the exception is installed.

## 3. Third need: change any text file with useful feedback

### Existing tools and their actual guarantees

`my.fs/read`, `my.fs/stat`, `my.fs/glob` and `my.fs/write!` enter `seon.effect/request!` with declared JVM
handlers. Read pages bytes, returns a window digest, and returns a whole-file digest only for a complete read.
Write accepts text, byte values or a blob and requires expected absence or the prior whole-file digest.
Working root, allowed roots and size/traversal bounds come from configuration. The JVM owner uses NIO
SecureDirectoryStream/no-follow access, staged content, file force and a serialized final recheck/move.
These are installed policy/mechanisms, not a new proposal to treat agents as untrusted.

`my.edit/form!` selects a parsed top-level Clojure form by head/name (optionally dispatch), using
`rewrite-clj.zip/of-string*`, `rewrite-clj.zip/position-span` and zipper replacement, then verifies the candidate
against the exact splice. `my.edit/exact!` works on any UTF-8 text: one literal occurrence, or all only when
explicitly requested. `my.edit/lines!` requires a complete exact old window as well as digest and inclusive line
range. `seon.edit.jvm/edit*` reads the whole bounded file, checks the digest, computes the pure transform, then
passes the same digest to the filesystem writer for a second check. Unicode byte spans are derived separately
from Java string indices. Exact/line edits do not validate their file's language or run lint/format/tests.

No claim check was found in these handlers. `bin/lane` claims are today's outside-agent coordination, not a
guarantee enforced by my.edit. The filesystem digest check is **optimistic**, not a kernel CAS: an external
writer can act between the final recheck and rename. The JVM lock only excludes participants using that writer.
Atomic rename prevents a torn replacement, not a lost external write or a multi-file transaction; do not claim
directory-crash durability merely because staged file contents were forced. The source write-back installer is
another installer: a final digest check precedes atomic rename without this filesystem writer's shared lock.
Permissions/mode preservation and all target filesystems need proving, especially executable shell files.

### Seon declarations and ordinary files

`seon.cluster.source/write-back!` starts with an accepted entity merge. It verifies the merged declarations'
digests still hold, checks original file bytes and spans, splices exact replacements/deletions/additions, and
reindexes the candidate to prove it declares exactly the accepted program before installing. It returns partial
installation evidence if a later file fails. That proof has no useful meaning for a Python file or Markdown doc.

`my.edit/file-edit-tools-bypass-write-back` currently requests deletion of the whole edit family (root's earlier
decision, B4). Deleting it unchanged would also remove the existing useful general exact/line transformations.
The new owner request needs a revised decision before that deletion lands. Do not quietly preserve a bypass for
admitted Seon declarations, and do not pretend all arbitrary Clojure repositories are Seon's program entities.

Recommend two **authoring entrances** with one conditional file installation owner:

| Authoring | Acceptance | Installation |
|---|---|---|
| Seon program declaration | Evaluate on agent branch → proofs/reaching tests → accepted entity merge | Existing write-back candidate/equivalence proof → shared conditional installer |
| Other repository text, including Clojure not admitted as Seon's program | Read basis → exact/range candidate → repository's declared checks → file review/commit | Same conditional installer, with no entity equivalence pretense |

Route by the publication owner's actual file/declaration membership and repository role, not a namespace roster
or the .clj suffix. A file containing admitted Seon declarations stays on the first entrance even when the desired
edit is surrounding comments; owner must decide the treatment of that surrounding text. Schema/config/task
resources keep their existing publication/adoption obligations; a successful generic disk edit alone proves none.

### Smallest useful composition

1. Agent selects the repository/basis and claims the concrete file before editing. Extend the existing claim
   owner to REPL effects; do not maintain a second claim roster. A Datahike agent branch alone isolates no disk
   file. In a shared checkout, claims serialize authorship. Git branches are history pointers, not separate files.
2. Read a complete small file and its digest, or page/search a large one and obtain a separately streamed
   whole-file digest. Never use window-digest as expected-digest. Current my.edit needs complete files under its
   read bound; larger edit support is a later measured requirement, not something installed today.
3. Apply the existing pure exact/window transform to held source bytes. Return a candidate/diff as data before
   effects. Refuse ambiguous, overlapping or stale replacements. Preserve unselected bytes, newline convention,
   encoding and file mode. A parser is optional for ordinary literal changes, required when selecting code by its
   syntax/name. The source-window clip in `seon.edit/context-window` should disappear: return values/references
   for the one value printer to display, not a second presentation budget.
4. Run the repository's pinned tools against the candidate. `my.shell/run!` already supplies foreground argv/cwd,
   stdin, exit and outputs via babashka.process. Feed formatter candidates through stdin where supported; a
   formatter that writes files runs only in owned staging and its output becomes new candidate bytes. Check the
   final formatted candidate, not the pre-format one. Use a disposable directory with the required unchanged
   inputs for project checks, never a git worktree or mutating formatter on somebody else's checkout. Commands,
   versions, input digests, exit and diagnostics are evidence; unavailable/skipped checks remain visible.
5. Recheck ownership and base at the existing writer, install atomically per file, return before/after digests
   and check results, then review/commit through the repository's authorized Git owner. The current Seon checkout
   still uses bin/lane commit. No new editor, scheduler, lint engine or Git database. File effects are recorded
   through the ordinary evaluation/effect entrance; acceptance is not inferred from a disk write.

Examples of feedback to configure per repository: clj-kondo/cljfmt and its Clojure tests; ESLint/Prettier and its
JS tests; Ruff and pytest for Python; ShellCheck/shfmt for shell; SQLFluff with the declared SQL dialect; each
config's actual parser/schema; seon.ref for Markdown/task/docstring references. These are illustrative bindings,
not a rule to install or run every tool. File type alone cannot tell the relevant tests or build command. Start
with explicit argv inputs; derive reusable checks from that repo's existing configuration when evidence warrants
it. Missing tooling answers unavailable, never passed.

Cost: one resource lookup/name; one selected file analysis on a content miss; one linear file search/splice and
streaming digest; formatting/lint proportional to declared inputs. Project tests may necessarily cover more than
one file. Avoid classpath-wide analysis, whole-program publication or full-repository formatting per edit. No new
cache besides a content-keyed seon.cache kind. No language server is required for exact text editing.

### Which parser, and for what

| Need | Use | Boundary |
|---|---|---|
| Clojure source selection/editing | rewrite-clj zipper/node functions, clj-kondo for declaration meaning | Preserve original byte spans; don't classify admitted declarations by the first two list elements |
| Markdown headings/references | Existing commonmark in seon.ref | One parser/resolver; no second heading regex |
| Cross-language syntax navigation | [Tree-sitter queries](https://tree-sitter.github.io/tree-sitter/using-parsers/queries/1-syntax.html), [official Java binding](https://github.com/tree-sitter/java-tree-sitter) | Grammar-specific captures; parser + grammar versions pinned; syntax trees are not semantic resolution or lossless writers |
| Project-aware definition/rename | Existing language server using LSP through a maintained client | Adds server lifecycle/configuration; use only when semantic refactoring needs it |
| Python structural refactor | [LibCST](https://libcst.readthedocs.io/en/latest/tutorial.html) | Concrete syntax parse/transform/code generation in Python; invoke the repo's tool, no home-grown JVM AST |
| JS/TS structural refactor | [Babel parser](https://babeljs.io/docs/babel-parser) with [Recast](https://github.com/benjamn/recast) | Parser configuration follows the repo; Recast retains original source for unchanged nodes |
| Arbitrary text replacement | Existing exact/line transforms and conditional write | No AST printer needed; language checks judge the resulting candidate |

Tree-sitter is the recommended optional navigation library, not an immediate dependency addition. Its Java
binding has native-library/grammar deployment costs; the reviewed upstream build asks for JDK 23+, while Seon
runs JDK 26. A JVM Clojure call site is possible, but neither the binding nor these grammars were installed or
measured here. Prefer project-native codemod tools when structural refactoring is needed; one universal AST
editor would recreate several editors and lose their semantics.

## 4. Square pegs: owner choices before implementation

Costs below are relative implementation/review scope, not promised durations. Each approved choice needs a small
card with exact ownership and a red tagged proof before an implementation lane launches.

| Decision | Options, guarantee and sacrifice | Recommendation |
|---|---|---|
| Delete my.edit versus edit arbitrary repos | 1. Keep conditional exact/window edits for non-program files, retire the Seon form bypass, share installation (small API/policy cut; no arbitrary Seon declaration disk authoring). 2. Delete all my.edit and use only conditional whole-file writes (smallest deletion; loses concise edits and must rebuild candidate selection at each caller). 3. Make all language files program entities (large new publication/merge model; falsely equates disparate language semantics). | 1; revise the existing bypass task/B4 explicitly |
| Unloaded symbols | 1. Resolve source with core resource lookup and run! on that file, reuse existing cache only behind a supported seam (small extension; static metadata may differ from runtime). 2. Require namespaces to obtain Vars (small code, unbounded loading effects and changes what "read" means). 3. Add Orchard now (dependency/lifecycle scope; loaded info still needs static analysis for this case). | 1; loading is an agent's deliberate evaluation, not reference resolution |
| Anchors for non-Markdown files | 1. File reference plus explicit exact search first; parser symbol and format-key selectors added when required (smallest useful slice; no stable identity across arbitrary rewrites). 2. Standardize media-specific fragment/selector syntax now with parser-qualified names and TextQuoteSelector/JSON Pointer (larger schema/resolver card; durable authoring possible). 3. Persist current line numbers (cheap but stale). | 1 first; choose 2 when tasks must author those precise places, never 3 for live references |
| Filesystem concurrency | 1. Cooperative claims, digest recheck and atomic rename; declare external-writer race (small common writer; fits today's collaborators). 2. Owned immutable candidates and exclusive repository installation by root, with external editors quiescent during installation (more workflow; closes cooperating writer races, still needs cooperation). 3. Promise digest CAS against arbitrary external processes (no portable filesystem primitive; not implementable by rename). | 1 initially; 2 when strong installation exclusion is required |
| Live files versus pinned references | 1. Explicit read basis: loaded program/dependency pin for task context, claimed working bytes for editing; answers name their basis (small input contract; author understands two versions). 2. Always working tree (cheap; source can disagree with running behavior). 3. Always archive (reproducible; cannot inspect a proposed edit). | 1 through the same resolver with explicit basis; no second resolver |
| Surrounding text in a Seon program file | 1. Admit file-text changes as candidates at write-back's existing gate and prove unchanged declaration digests (bounded extension; comments/spacing can be authored). 2. Reify comments/spacing as program entities (large model; unnecessary identities). 3. Prohibit those edits (small refusal; cannot maintain normal source files). | 1, with owner deciding file-level acceptance rather than another declaration evaluator |

## 5. Rows and cards implied, in agent-use order

This table proposes work to root; it does not edit the issue index or assign a research-only task with already
green proofs. Proposed proof names/behaviors are not claims that new APIs or tests exist.

| Order | Existing authority to extend | Card's slice and proof |
|---|---|---|
| 1 | S1-06, S1-08; CS-01c2/REF-1/CS-01c5; CS-01c3 | Root batch installs landed reading changes, then same SCI doc/source/section probes. Retain existing seon.ref-test section/pin tests and seon.context-test library resolution proof; no duplicate "new" implementation of those rows |
| 2 | CS-01c2/REF-1; seon.fn.analyzer/local-root-dependency-analysis-goes-stale | Unloaded namespace/Var reference: resource exists, source/doc returned, namespace still unloaded, selected resource revision correct. Add table cases for absent source, .cljc, duplicate resources and unindexed script; no full scan |
| 3 | CS-01c3, TASK-CHECK, REF-2 | File references beyond Markdown and outside Seon's repo: explicit base/basis, path existence, exact search ambiguity as problems. Extend resolver, ingestion and hook together; one rule everywhere |
| 4 | my.edit/file-edit-tools-bypass-write-back; RF-3/B4 | Owner-ratified generic file boundary: exact/line editing of JS/Python/shell/SQL/data works; admitted Seon file takes write-back; stale/ambiguous writes leave disk unchanged. Reuse seon.edit-test exact/window/UTF-8 cases; retire tests only for retired form bypass |
| 5 | Same file-edit task; seon.cluster.source/write-back! | One conditional installer + existing claim owner used by both producers; two cooperating writers with one base cannot both replace it. Preserve executable mode, CRLF and non-ASCII; show partial multi-file outcome. Requires writer/platform proof in isolated gate |
| 6 | seon.effect/a-file-stat-costs-a-thousand-armed-calls | Measure/fs effect work before making generic edits automatic; tighten existing cost proof with this stat/read evidence, not a new arbitrary timing bound |
| 7 | New bounded candidate-check card under file owner | Configured formatter/check produces candidate and typed diagnostics; failed/unavailable checks do not certify it; final digest checks protect stale installation. Use my.shell/run!, existing execution bounds and Git owner |
| 8 | Optional navigation card after observed need | Pin Tree-sitter binding and one actual language grammar; resolve nested/duplicate symbols and syntax errors from held bytes. LSP/codemod integration is separate only if navigation cannot meet the task |

F10 and the turn-loop owner also need the empty automatic-test result investigated at this precise entrance;
do not infer the cause from its resemblance to another task. Known shared health findings remain with their
existing owners. No production path, schema, task resource or another lane's file was changed in this research.

## 6. Reproducible read evidence and landing

All forms below ran through MCP eval_clj with agent files-sol and cluster default. JVM forms are read-only host
observations, not proof of SCI installation. No uncommitted source was loaded into default.

Resource/metadata probe (JVM, result/e569e12bf9422, 2 ms):

```clojure
(let [symbols '[malli.core/validator clojure.core/map clojure.data.xml/parse
                orchard.info/info clojure.java.browse/browse-url]
      paths ["malli/core.cljc" "clojure/core.clj" "clojure/java/browse.clj" "orchard/info.clj"]]
  {:symbols (mapv (fn [s]
                    {:symbol s
                     :loaded? (boolean (find-ns (symbol (namespace s))))
                     :metadata (select-keys (meta (when (find-ns (symbol (namespace s))) (find-var s)))
                                            [:file :line :column :arglists])}) symbols)
   :resources (mapv (fn [p] {:path p :url (some-> (clojure.java.io/resource p) str)}) paths)
   :analyzer-cache seon.fn.analyzer/cache-directory
   :source-root (seon.fs/source-directory)})
```

Unloaded cache probe (JVM, result/e16395b9b7d0b, 1 ms):

```clojure
(let [root (clj-kondo.impl.core/resolve-cache-dir seon.fn.analyzer/config-directory
                                                true seon.fn.analyzer/cache-directory)
      cached (clj-kondo.impl.cache/from-cache-1 root :clj 'clojure.java.browse)]
  {:loaded-before? (boolean (find-ns 'clojure.java.browse)) :cache-root root
   :cache-keys (when cached (vec (keys cached))) :definition (get cached 'browse-url)
   :namespace-file (:filename cached) :loaded-after? (boolean (find-ns 'clojure.java.browse))})
```

The result contained row 68/col 1, fixed-arities #{1}, name browse-url, namespace/top-ns clojure.java.browse,
type :fn and jar filename `clojure-1.12.5.jar:clojure/java/browse.clj`. Loaded-before/after were false.
The dependency cache was `.clj-kondo/.cache/dependencies/40ee46b0d8af` in that JVM's source basis.

Rich analysis probe (JVM, result/e04fbd86208be, 14 ms):

```clojure
(let [url (clojure.java.io/resource "clojure/java/browse.clj") source (slurp url)
      started (System/nanoTime)
      analysis (binding [*in* (java.io.StringReader. source)]
                 (:analysis (clj-kondo.core/run!
                              {:lint ["-"] :filename "clojure/java/browse.clj"
                               :cache false :repro true
                               :config {:output {:analysis {:var-definitions {:meta true}
                                                            :namespace-definitions true}}}})))
      elapsed (/ (- (System/nanoTime) started) 1000000.0)]
  {:analysis-ms elapsed :source-chars (count source)
   :definition (first (filter #(= 'browse-url (:name %)) (:var-definitions analysis)))
   :namespace (first (:namespace-definitions analysis))
   :loaded-after? (boolean (find-ns 'clojure.java.browse))})
```

3416 source characters were analyzed, browse-url's doc was "Open url in a browser", arglist-strs ["[url]"],
rows 68–88, namespace declaration rows 9–17 and doc "Start a web browser from Clojure". Neither this nor the
cache probe loaded that namespace. Returned filename was the supplied logical resource name; provenance must
also retain the resource URL, not confuse that filename with the working tree.

One failed probe used the same outer let but called run! with `:stdin source` and without binding *in*. That
option is not its API: impl/core's stdin path slurps *in*. MCP gave no result in 32,000 ms and said the outcome
was unknown. Runtime status then showed seon.mcp/jvm-run max 32,089 ms. A later read-only Thread/getAllStackTraces
filter for clj_kondo frames returned [] in 4 ms (result/e6b7b6f1a814a), before the corrected probe. This was an
invalid measurement setup, not evidence that file analysis costs 32 seconds. No cancellation/termination was
inferred merely from connection closure. The failed source was:

```clojure
(let [url (clojure.java.io/resource "clojure/java/browse.clj") source (slurp url)
      started (System/nanoTime)
      analysis (:analysis (clj-kondo.core/run!
                           {:lint ["-"] :stdin source :filename "clojure/java/browse.clj"
                            :cache false :config {:output {:analysis {:var-definitions {:meta true}
                                                                     :namespace-definitions true}}}}))
      elapsed (/ (- (System/nanoTime) started) 1000000.0)]
  {:analysis-ms elapsed :source-chars (count source)
   :definition (first (filter #(= 'browse-url (:name %)) (:var-definitions analysis)))
   :namespace (first (:namespace-definitions analysis))
   :loaded-after? (boolean (find-ns 'clojure.java.browse))})
```

SCI forms and complete outcome classes are the table in §1: handles result/e1d3545ccf4f5 (doc),
result/eb0167bdc839a (source), result/ecddcd7926a0f (section), result/e948db00038ae (stat),
result/e3a257411acf5 (read). Errors/empty automatic-test evidence were retained above rather than counted as passes.

Pre-commit validation: `clj-kondo --lint src script test --cache false` took 11,839 ms, **0 errors**, 1,484 warnings.
This research does not change source/test lines or admit program entities. Named gate smoke checks will be the
existing section/pin/reference tests, by Var, through bin/lane commit's HEAD-plus-owned-file candidate; they
validate the seams, not the unimplemented proposals. Foreign working-tree changes include configuration,
schema resources, turn/evaluation/print/render owners and their tests. They are excluded from the candidate;
the live archive's earlier installation is the read-proof boundary. No worktree, amendment or broad-tier run.
There are no changed program definitions for a reaching-test selection. The named smoke checks are
`seon.ref-test/a-doc-section-reads-with-its-headings-below-and-size`,
`seon.ref-test/a-reference-code-section-carries-its-pin` and
`seon.context-test/a-library-symbol-resolves-with-its-pinned-revision`. No pre-existing red was established for
these Vars; the broad namespaces were not run. The note is approximately 9,500 estimated tokens;
source/test net change is zero.

The first commit, `af1d204d7`, passed bin/lane commit's check of candidate
`7032c3c56a3760050fc7eb5990ce67928bc0beac`: run e4471d9d5de3, executed 3, reused 0, assertions passed 13,
failed 0, errors 0, passed true; test run 4,668 ms. Total gate time was 60,101 ms: publication 387 ms,
adoption 6,356 ms; isolated JVM readiness 36,067 ms, boot 40,023 ms, test phase 4,766 ms. The throwaway
PID was 26755, start instant 2026-10-08T09:43:51.223Z. This is positive smoke evidence for those installed
candidate seams, not evidence that default was restarted or that proposed editing/navigation exists. The
over-ten-second isolated boot is an observed cost under the explicitly requested gate; it belongs to the boot
owner's follow-up schedule. This landing evidence is recorded by a forward documentation commit, never amendment.
