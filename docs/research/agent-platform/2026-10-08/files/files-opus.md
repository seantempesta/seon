---
title: "Files: names, places and edits for any file an agent touches"
type: research
status: active
created: 2026-10-08
---

# Files: names, places and edits for any file an agent touches

Question (owner): "Regarding the reference code we need either relative file refs or if the namespaces are globally
addressable then we can look them up from the deps? Keep in mind we need general file editing solutions too so
agents can work on non seon or clojure code."

What exists: one resolver, `seon.ref` (REF-1, CS-01c2, CS-01c5), reads a backticked span as a qualified name or a
based path and resolves names through the program database, then the loaded Var; `my.fs` reads and writes files
behind a digest precondition; `my.edit` (slated for deletion by `my.edit/file-edit-tools-bypass-write-back`) edits
text behind a digest; `seon.cluster.source/write-back!` installs accepted declarations by byte span. The principal
Clojure approach below is to compose what is already loaded: clj-kondo's dependency cache for unloaded names, git's
own function-name ranges for non-Clojure code, and the existing digest-fenced write as the one installer.

**Answer in one paragraph.** Yes, namespaces are globally addressable from the deps: every Clojure namespace on the
JVM classpath resolves by symbol to its file and pinned revision without loading it, and clj-kondo's dependency
cache, which boot already fills, holds each var's row. So code is always referenced by symbol, never by path, loaded
or not; today `seon.ref` misses the unloaded ones (measured below), a one-function fix. Everything else is a based
relative path (`reference-code/<project>/…` or a checkout path) with an RFC 3986 fragment whose meaning follows the
file's type: a Markdown heading, a function name found by git's own `-L :name` rule for code in other languages, a
quoted text anchor for anything else. Editing any file is two existing operations, `my.fs/write!` and
`my.edit/exact!` (compare-and-set on the file's SHA-256), pointed at a checkout instead of the loaded archive (today
they write into the archive extract: a defect), with the file type's checks returned in the reply and git as the
record. Clojure declarations keep their one path (`defn` on the branch, merge, write-back); both paths end in the
same digest-fenced install.

## Ranked by what a Seon agent needs first

1. Read any code by name, loaded or not (rows FILE-1, FILE-2).
2. Read and edit files in the right world: the loaded commit for references, a checkout for edits (FILE-3).
3. Edit any text file with a base digest and get its checks back as data (FILE-4, FILE-5).
4. Point at a place inside a non-Clojure file without a line number (FILE-6).
5. One installer for every file write (FILE-7).
6. Make an ordinary file read cost what the read costs (existing task, new evidence).

## What the REPL showed

Default JVM, read-only, loaded commit `32ca5f2e5` (archive extract `data/source/32ca5f2e…`), Java 26.0.1 with
`--enable-native-access=ALL-UNNAMED`. Runtime status `:faulted` (memory pressure refusals on
`seon.sci.kernel/own-arm`, recorded with tasks); probes were JVM-mode reads plus one SCI evaluation as agent
`files-opus`.

| Probe | Result |
|---|---|
| Classpath | 273 entries: 233 jars, 40 directories, including `"."` (the extract root) and one directory per pinned `reference-code` project under `data/source/pins/reference-code-<p>/<rev>/` |
| Clojure namespaces on the classpath (dirs and jars, `"."` excluded) | 1,843 by file name; 555 loaded; 1,284 unloaded. Scan 239 ms. With `"."` included the scan walks the whole extract: 7,209 names, 11.3 s |
| Unloaded Seon namespaces | 388 under `test/` (all present as `:seon.ns/name` entities in the program db), 11 under `script/` (Babashka; none in the db) |
| `io/resource` for an unloaded namespace | 180 µs each. `rewrite-clj.paredit` → `…/pins/reference-code-rewrite-clj/60782e50…/src/rewrite_clj/paredit.cljc`; `malli.dev.pretty` → malli pin `418df441…`; `clojure.tools.reader` → `jar:file:~/.m2/…/tools.reader-1.6.0.jar!/…`. The URL carries project and revision (pin) or Maven coordinate (jar path) |
| clj-kondo dependency cache (`seon.fn.analyzer/cache-directory`, filled at boot by `ensure-dependency-analysis!`) | 1,353 namespace files (`clj` 901, `cljc` 452); 849 of the 1,284 unloaded namespaces are in it. The other 435: 388 Seon tests (in the db instead), `META-INF.leiningen.*.project` files (not namespaces) and a few stragglers. Each entry: `{:row :col :type :fixed-arities :arities {n {:arglist-str …}}}` per var plus `:filename`, the absolute path in the pinned extract |
| Reading one namespace's cache entry, `clj-kondo.impl.cache/from-cache-1` (`clj_kondo/impl/cache.clj:23`) | malli.core 0.61 ms (264 vars; `validator` row 2672), seon.operator 0.21 ms, rewrite-clj.paredit 0.13 ms |
| `seon.ref/references` on "`` `seon.operator/check!` `` and `` `rewrite-clj.paredit/slurp-backward` ``" | both `:unresolved`, though the cache holds `check!` at `script/seon/operator.clj` row 1750 arglist `[request]` and `slurp-backward` at row 484. Cause: `library-name` (`src/seon/ref.clj`) requires `find-ns` |
| Docstring of an unloaded var without loading it | edamame `parse-string-all` of paredit.cljc (31.6 KB, 49 forms) 3.6 ms; the form at row 484 is `(defn slurp-backward "DEPRECATED: …")`, rows 484–506 |
| Orchard, cljfmt | not on the classpath (`orchard` is in `reference-code/` only) |
| `my.fs/stat {:my.fs/path "AGENTS.md"}` as agent files-opus | 58,210 bytes, modified 09:18:50Z: the **archive extract**'s copy (checkout copy: 58,212 bytes, 18:27). `:seon.config.fs/working-root "."` (`config/default.edn:209`) is the JVM's cwd, which is the extract |
| `my.fs/read` of a 200-byte window | 48 ms, **44.6 MB allocated, 1,160 armed calls**; `my.fs/stat` 60 ms, 93.9 MB, 1,339 calls |
| Automatic test step after that read-only evaluation | `{:seon.test/passed? false :seon.turn.loop/red []}`: already filed as `my.test` "a reaching run with nothing to run" |
| `my.fs/section` in SCI | "Unable to resolve symbol": default runs `32ca5f2e5`, which predates `c9a496d6f`; not a defect, the next batched restart installs it |
| `git blame -s -L :newQuestion <pin> -- src/clm/static/app.js` in `reference-code/CLM` | lines 141–153, the whole JS function, 16 ms, at the pinned revision with no checkout change |
| `git log -1 -L :git:<file>.py` (a top-level `def git`) | lines 13–15, 53 ms, no `.gitattributes` needed |
| Same rule on an indented Python method and a shell function (scratch repo) | default rule: `fatal: -L parameter 'save' … no match`; with `-c core.attributesFile=<f>` holding `*.py diff=python`: found at line 7, but the range runs to the line before the next funcname match (here end of file, swallowing a following shell function); `*.sh diff=bash` finds `deploy()` lines 5–7 exactly; an absent name is the same `fatal … no match` |
| FILE-1 prototype (a `let` in JVM mode: kondo cache entry, then the form at its row by edamame) | `rewrite-clj.paredit/slurp-backward` rows 484–506 + first doc line, `malli.dev.pretty/explain` rows 186–190, `seon.operator/check!` rows 1750–1790, arglist `[request]`, doc first line; 5 names 7.7 ms; the 1,790-line operator.clj parse alone 11 ms (cache by digest, as `seon.ref/document` does). Shape note: a `.clj` namespace's cache entry is flat (vars at top level beside `:filename`), a `.cljc` one is keyed by `:clj`/`:cljs` |
| Tools on this machine | `git`, `rg`, `clj-kondo`, `tree-sitter` CLI 0.25.6 with **no grammars configured**; no `shellcheck`, `ruff`, `prettier`, universal-ctags |
| File types in the repo (tracked, outside reference-code) | 2,458 md, 960 clj, 720 edn, 398 txt, 197 json, 145 py, 105 patch, 75 html, 23 sh, 22 cjs. In reference-code: clj 2,142, json 791, cljc 702, css 548, md 387, cljs 380, java 320, js 275 |

## 1. Code on the classpath: addressable by symbol, loaded or not

Every Clojure namespace on the classpath is globally addressable by its symbol. The namespace symbol names its
resource (`clojure.core/load`'s munging: dots to slashes, dashes to underscores, `.clj` or `.cljc`), and
`io/resource` answers the URL from the first classpath entry holding it. For Seon that URL already carries the
revision: a pinned project's extract path names `reference-code-<p>/<rev>` (`seon.ref/pinned-revision`), a Maven
jar's path names its coordinate, and a Seon file sits in `data/source/<commit>/`. So the owner's second branch is
right: **look them up from the deps, by symbol; never write a path for code.**

Three sources answer a name, in this order, all without loading anything:

| Name kind | Source | Gives | Cost |
|---|---|---|---|
| Seon function, test, namespace (src, test) | program db: `[:seon.fn/sym s]`, `[:seon.test/sym s]`, `[:seon.ns/name s]` (as `seon.ref/resolve-name` does) | file, form span, arglists, doc, contract, source | one entity pull |
| Loaded library or host Var | `find-var` + `(meta v)` + `io/resource (:file m)` (`seon.ref/library-name`) | file, line, arglists, doc, pin | µs |
| **Unloaded** library or script namespace (849 deps, 11 Babashka scripts) | clj-kondo's dependency cache, `clj-kondo.impl.cache/from-cache-1` on `seon.fn.analyzer/cache-directory`, lang `:clj` then `:cljc` | `:filename` (absolute, in the pinned extract, so the pin follows), `:row`, `:col`, `:type`, arities with `:arglist-str` | 0.1–0.6 ms per namespace |

What the agent can read for an unloaded var: its arglists from the cache entry, and its doc and source by reading the
one form at `:row` of `:filename` with edamame (row metadata, as `seon.ref` already uses edamame) — the same thing
`clojure.repl/source-fn` (`clojure/repl.clj:147`) does with a Var's `:file`/`:line`, minus the Var. The read
operation offered in the block stays `(clojure.repl/source s)` for a loaded Var and becomes
`(my.fs/section "<based path>#<name>")` for an unloaded one (FILE-6 gives code files a name anchor), so the agent
never needs a line number.

Not needed: Orchard. Its `info` is `(meta var)` plus `io/resource`, i.e. it needs the Var loaded; what it adds (Java
members, special forms, ClojureScript) has no current consumer (`references.md` already concluded this). Not
needed: a classpath scan. A name is looked up, never enumerated; the 239 ms scan above was a census, and the 11.3 s
variant shows why `"."` on the classpath must never be walked.

Java classes are addressable by name too (`clojure.lang.RT`), but only as bytecode: `~/.m2` holds no `-sources.jar`
(0 found). Source exists only where a pinned project carries it: `reference-code/clojure` is pinned at tag
`clojure-1.12.5`, the same version as the Clojure jar on the classpath, with 185 `.java` files
(`src/jvm/clojure/lang/RT.java`); http-kit 92, datahike 16, nrepl 10. So a Java class is referenced by its based
path in the pinned project (`reference-code/clojure/src/jvm/clojure/lang/RT.java#methodName`, anchored by FILE-6's
`diff=java` rule), and `seon.ref` keeps reading unqualified dotted symbols as prose. Mapping class names to pinned
source paths automatically is Orchard's Java half; adopt it only when an agent needs it.

A square peg worth naming: `"."` on the classpath makes every file of the extract an `io/resource`
(`(io/resource "AGENTS.md")` and `(io/resource "reference-code/malli/README.md")` both resolve). Resolving based
paths through `io/resource` would therefore work by accident; `seon.ref/locate` correctly resolves them against
`seon.fs/source-directory` and the pins instead, and should keep doing so.

## 2. Files that are not symbols: based paths, and anchors by file type

**The reference form that resolves on every machine** is the one `references.md` settled: a relative path string
whose first segment names its base, either the checkout (`docs/…`, `AGENTS.md`, `script/…`) or
`reference-code/<project>` at its pinned revision (`seon.ref/based-file`; all 34 submodules have a pinned extract,
including non-dependencies such as CLM and orchard). It is SARIF's `artifactLocation {uri, uriBaseId}` with the base
named by the first segment instead of a side table. This holds for any file type; nothing new is needed for paths.

**The anchor** is the URI fragment, and RFC 3986 leaves its meaning to the media type. That is exactly the right
seam: one fragment slot, one resolver, a rule per file type.

| File type | Fragment | Resolved by | Survives edits? | Status |
|---|---|---|---|---|
| Markdown | `#heading-slug` | commonmark heading ids (`seon.ref/document`, `IdGenerator`) | yes, until the heading is renamed (then `:anchor-missing` names the nearest) | landed (CS-01c5, CS-01c3) |
| Clojure | never a path: the symbol | §1 | yes | landed for loaded names; FILE-1 for unloaded |
| EDN schema or task resource | the keyword (schema) or `ns/task-name` (task) | edamame `:postprocess` row (`references.md`) | yes | partly landed (schema form) |
| Other code (JS, Python, shell, Java, CSS, SQL) | `#name`, a function, class or top-level definition name | `git blame -L :name <rev> -- <path>` / `git log -L :name:<path>`: git's diff-driver funcname rule (`diff=python`, `diff=java`, … in gitattributes; default: a line starting with a letter, `_` or `$`) | yes, until renamed | probed: JS 16 ms, Python 53 ms |
| Any other text (logs, data, config) | a text quote: W3C Text Fragment `#:~:text=start[,end]`, the Web Annotation `TextQuoteSelector {exact, prefix, suffix}` idea | one unique exact match in the file; zero or two matches is a problem naming the count | yes, while the quoted text exists | proposal |
| Line range, last resort | `#line=10,20` (RFC 5147, text/plain fragments; optional integrity `;length=N`) | count lines; with `;length` a changed file is detected | no; shown as drift-prone | `seon.ref` already flags `path:N` as `:line-reference` |

Why git's funcname rule before tree-sitter: it is already installed, already language-aware for the languages in
this repo (built-in drivers for Python, Java, bash, CSS, HTML, Markdown, Rust, Ruby, C/C++, Go, …; JavaScript has no built-in driver and uses the default rule),
works **at a pinned revision without checking anything out** (the submodule repos hold the objects), and returns a
line range, which is all an anchor needs. Its limits, probed: the default rule only sees definitions that start at
column 0 (indented Python methods need `diff=python`; the submodule repos may lack a `.gitattributes`, so Seon
passes `-c core.attributesFile=<one Seon file mapping extensions to drivers>`); the start is exact but the end is
"the line before the next funcname match", so it over-includes trailing top-level code; and it matches the first
line containing the name. For an anchor the start line is what matters; the section shown is bounded by the end. A symbol table
(tree-sitter's `tags.scm` queries, `@definition.function` + `@name`, as GitHub code navigation uses) is the precise
upgrade, kept for when a measured miss needs it (square peg C).

The standards, and what they settle here:

| Standard | Shape | What fits Seon |
|---|---|---|
| SARIF 2.1.0 | `physicalLocation {artifactLocation {uri, uriBaseId}, region {startLine, endLine, charOffset, byteOffset, snippet}}`, `logicalLocation {fullyQualifiedName, kind}`, `artifact.hashes {"sha-256"}`, `run.versionControlProvenance {repositoryUri, revisionId, mappedTo {uriBaseId}}`, `result.partialFingerprints`, `fix {artifactChanges [{replacements [{deletedRegion, insertedContent}]}]}` | The best overall match: named bases = our first segment; `logicalLocation` = our symbol; `versionControlProvenance` = our pin; `hashes` = our file digest; a SARIF `fix` = our exact edit. **Use as the output shape for findings from non-Clojure linters** (ruff and semgrep emit SARIF directly, eslint through a SARIF formatter, shellcheck emits JSON), read with `clojure.data.json` into the one error/finding map; do not adopt it as storage |
| LSP 3.17 | `Location {uri, range {start {line character}}}`, zero-based, UTF-16 code units unless `positionEncoding` is negotiated; `TextDocumentEdit {textDocument {uri, version}, edits [TextEdit {range newText}]}` | An editor face only: absolute `file://` URIs are machine-specific. Its one lesson is the versioned edit: an edit names the version it was computed against, which is our base digest. Running language servers is "recreating an editor" (square peg D) |
| RFC 5147 | `#line=a,b`, `#char=a,b`, integrity `;length=` / `;md5=` | The only standard line-range fragment; use for the last-resort row, with `;length` so drift is detectable |
| W3C Text Fragments / Web Annotation selectors | quote with prefix and suffix | The robust anchor for unstructured text, and the same rule as an exact edit's `old-string` (unique match) |
| git `rev:path`, `-L :funcname`, `-L /regex/` | revision-qualified path; named and searched ranges | The pin (derived from the gitlink) and the code anchor resolver above |

## 3. General file editing for agents

**What exists.**

| Operation | Contract (today) | Safety | Where it writes |
|---|---|---|---|
| `my.fs/read` | bounded window, `:my.fs/digest` on a complete read | `SecureDirectoryStream` no-follow traversal, declared roots | — |
| `my.fs/write!` | whole content (text, bytes or blob) | precondition: expected absence or expected SHA-256; recheck and atomic move under one JVM lock (`seon.fs.jvm/write`) | `:seon.config.fs/working-root` = the JVM cwd = **the archive extract** |
| `my.edit/exact!` | replace a unique `old-string` | expected digest; ambiguity or no match is a typed error; then `seon.fs.jvm/write` with the same digest | same |
| `my.edit/lines!` | replace a line window, checked against its old text | same | same |
| `my.edit/form!` | replace/insert/delete a named top-level Clojure form via rewrite-clj (`seon.edit`) | same, plus a lossless reparse check | same |
| `seon.cluster.source/write-back!` (`my.program/write-branch!`) | accepted declarations into their files by recorded byte span | each declaration's digest on the branch head, each file's bytes as analyzed, reindex proves equivalence, then `install!` (its own temp file + `ATOMIC_MOVE`) | an explicit `:seon.fn/root` the caller passes |
| Outside agents (Claude Code, Codex) | their editor's Edit/Write/apply_patch | `bin/seon-hook` checks per file type (clj-kondo for Clojure, contract compile, task-file shape, Markdown lint); `bin/lane` claims; `git commit --only` | the checkout |

Two defects follow from the table. **First, `my.fs` and `my.edit` write into the archive extract**, the immutable
input every JVM boots from and `seon.ref`/`seon.fs/source-directory` read references from. An agent's edit there
reaches no checkout, no git history and no later commit, and silently changes what the running JVM considers the
loaded source. (`seon.fs/source-directory`'s docstring calls that directory "the checkout directory".) The read half
is filed as `my.fs/archive-file-reads-have-no-explicit-source-world`; the write half is worse and is FILE-3. The
configuration's own comment says "the process working directory is not an undeclared fallback"
(`config/default.edn`, above `:seon.config.fs/working-root "."`), yet `"."` is exactly that directory. The
checkout is already known to the JVM: `bin/seon` exports `SEON_CHECKOUT` and the launched JVM inherits it
(`(System/getenv "SEON_CHECKOUT")` → `/Users/sean/src/seon`, probed), so bootstrap can bind the working root from
it with aero's `#env`, the way configuration names other environment inputs.
**Second, there are three atomic installers** (`seon.fs.jvm/write`, `seon.cluster.source/install!`,
`seon.fs/write-edn-atomically!`), only one of which compares a digest at the move.

**How edits stay safe, by mechanism.**

- *Compare-and-set on content*: every write names the SHA-256 it was computed against (`:my.fs/expected-digest`).
  This is LSP's versioned edit and SARIF's `artifact.hashes`, and it is sufficient for correctness between any
  number of writers: a lost update becomes a typed `:my.fs/stale-digest` with the fix "re-read, retry".
- *Exact-span replacement*: a unique quote (`exact!`) or a recorded byte span (write-back) says where; ambiguity is
  an error, never a guess. This is also the anchor rule of §2, so a reference and an edit locate text the same way.
- *Claims* (`bin/lane`) are ownership, not safety: they prevent two lanes from interleaving hunks in one file across
  commits, which a digest cannot see. A Seon agent editing the shared checkout needs the same claim check (FILE-4).
- *Git as the record*: the commit is the durable, reviewable unit for files; the program db is the record for
  declarations. Neither needs a second log.

**One path or two.** Two producers, one installer:

- Clojure declarations: `defn` in the target namespace on the agent's branch → merge by root → `write-back!`
  computes the bytes. Unchanged; it is the program path and the only one that keeps entities, tests and evidence
  consistent. A text edit of a program declaration is the wrong producer: it bypasses the branch, contracts and
  reaching tests. (Outside agents' hook evaluates their edited forms on their branch, so even their text edit
  becomes a `defn`.)
- Everything else (docs, task EDN, config, scripts, data, non-Clojure code, other repositories): the agent computes
  the new bytes at its REPL (`slurp`, `str/replace`, its own transformation) and installs them with `my.fs/write!` or
  `my.edit/exact!` under its base digest, into a checkout it names.
- Both end in the same install: write a sibling, recheck the digest, atomic move (FILE-7).

**The simplest design that lets a Seon agent edit any text file from its REPL, with feedback.**

```clojure
;; read: current bytes and digest of the checkout copy
(my.fs/read {:my.fs/path "docs/x.md"})              ; => {:my.fs/text … :my.fs/digest d0 …}
;; edit: one unique quote, under that digest
(my.edit/exact! {:my.edit/path "docs/x.md" :my.edit/expected-digest d0
                 :my.edit/old-string "old" :my.edit/new-string "new"})
;; => {:my.edit/after-digest d1 :my.edit/source-window … :my.edit/findings […]}   ; findings: FILE-5
```

That is the whole editor: the REPL already has every string function, and the agent's transformation is its own
code. What Seon adds is what an editor cannot: the base digest, the root, the claim, and the checks for the file's
type returned as data in the same reply. No buffer, cursor, undo or language server: undo is the previous digest's
bytes in git or the reply's handle, and navigation is §2's anchors.

**Checks per file type (FILE-5).** One table from file type to check functions, each answering findings as data; the
same functions run in `bin/seon-hook` for outside agents and after `exact!`/`write!` for Seon agents, so the two
entrants get the same feedback (today the hook holds them privately in 1,840 lines of Babashka).

| Type | Check | Library or tool | Present? |
|---|---|---|---|
| `.clj` `.cljc` `.bb` (non-program, e.g. `script/`) | lint | clj-kondo analysis and findings | yes |
| `.edn` task file | one vector, ingestible, references resolve | edamame, `seon.task` ingestion, `seon.ref` (TASK-CHECK) | yes |
| `.edn` schema resource | registers, compiles | `seon.schema`, Malli | yes |
| `.md` | references resolve, anchors exist, house Markdown lint | `seon.ref` (REF-2), `seon.dev.markdown` | yes |
| `.json` | parses | `clojure.data.json` | yes |
| other code | the project's own linter or test command, run through `my.shell/run!` (bounded argv request), SARIF or JSON output read as findings | ruff, eslint, shellcheck, … declared per extension in configuration; absent tool = a typed `:tool-missing` finding, never silence | none installed here |

Formatting is a check with a fix, not a mode: cljfmt is not on the classpath; adding it is a dependency decision.

**Libraries.** Clojure text: rewrite-clj (already the engine of `seon.edit`) and edamame. Other languages: none
needed for editing, since exact quotes work on any text; for locating, git's funcname rule now and tree-sitter later.
On the JVM the official binding is `io.github.tree-sitter:jtreesitter` (0.26.x, Java's FFM API; this JVM is Java 26
with native access enabled, so it would load), but each grammar is a native library to build or vendor per platform;
`io.github.bonede:tree-sitter-*` (tree-sitter-ng) ships prebuilt grammars in Maven jars over JNI, older grammar
versions, and reported Windows gaps. Neither is measured here (no grammar is installed; downloading one is the
owner's dependency call). Diffs for display: git (`git diff --no-index`) or the reply's source window; no diff
library.

## 4. Square pegs and owner decisions

**A. What becomes of `my.edit`?** Root decided "retire my.edit, seon.edit, seon.edit.jvm" (task
`my.edit/file-edit-tools-bypass-write-back`), on the law that files are the write-back of accepted entities. The
owner now asks for general file editing, which `exact!` is.

1. Delete as decided; Seon agents edit only declarations; non-program files only by outside agents. Cheapest (pure
   deletion); sacrifices the owner's stated goal.
2. **Keep `my.fs/write!` and `my.edit/exact!` as the general editor; delete `lines!` and `form!`** (`form!` duplicates
   `defn` + write-back for Clojure; `lines!` is an exact edit with a stale-prone address). `exact!` on a file holding
   program declarations answers a refusal whose fix is `(in-ns …) (defn …)`. Restate the task's intent accordingly.
   *Recommended*: smallest surface that covers every file type, keeps one program path.
3. Keep all four. No work now; keeps a second Clojure editing path beside the REPL.

**B. Where does a Seon agent's non-program edit live before it is accepted?**

1. **Directly in a checkout the agent names** (its task's working tree; for Seon, the shared checkout under a
   `bin/lane` claim), git commit as the record, exactly like outside lanes. *Recommended* first: no new entity,
   language tooling (pytest, eslint) sees the bytes on disk, and it is the only option that works for a non-Seon
   repository.
2. As a file-change entity on the agent's Datahike branch (`{path, base-digest, blob digest}`), merged by root and
   installed by write-back like declarations. One path for every change and root review for docs too; costs a new
   entity kind, an overlay read for the agent's own world, and still needs a disk copy for non-Clojure tools.
3. A git worktree per agent. Real isolation on disk for any language; needs the owner's personal authorization per
   use (AGENTS.md), and cleanup.

**C. How are places in non-Clojure code anchored?**

1. **git's funcname rule (`-L :name`) now**, text quote for other text, `#line=` last. *Recommended*: zero new
   dependencies, works at a pin, measured 16–53 ms.
2. tree-sitter (`jtreesitter` + vendored grammars, `tags.scm`): exact definition spans for nested and same-named
   definitions; a native dependency per grammar and platform.
3. Search anchors only (a quote or regex for everything). Simplest rule; quotes go stale on rename like names do, and
   a regex in a reference is the owner-gated production regex.

**D. How much language intelligence for non-Clojure work?**

1. **Checks only**: run the project's own linter and tests through `my.shell/run!`, read SARIF/JSON findings.
   *Recommended*.
2. Language servers over LSP (definitions, references, renames) per language: an editor's machinery, one process per
   language, positions in UTF-16. Defer until an agent's measured work needs cross-file navigation in another language.
3. Nothing: the agent reads files and runs commands itself through `my.shell/run!` and `my.background`. Leaves findings as printed text,
   against "return data, not comments".

## Rows and cards this implies

| Row | What becomes true | Owner (files) | Proof | Relates to |
|---|---|---|---|---|
| FILE-1 | `seon.ref` resolves an unloaded namespace's var from clj-kondo's dependency cache: file, row, arglists, pin; the 11 Babashka script namespaces and 849 unloaded dependency namespaces stop answering `:unresolved` | `src/seon/ref.clj` (`library-name`), `resources/seon/schemas/seon.ref.edn` | a row in `test/seon/ref_test.clj`: `` `seon.operator/check!` `` and `` `rewrite-clj.paredit/slurp-backward` `` resolve with a positive row and the rewrite-clj pin (red today, probed) | filed as `seon.ref/unloaded-library-names-answer-unresolved`; `references.md` "22 do not resolve"; CS-01c2 |
| FILE-2 | an unloaded var's doc and source are readable by reading its form at the cached row (edamame), offered as an operation | `src/seon/ref.clj`, `src/my/fs.clj` (`section` accepting `#name` on a `.clj` path) | `(my.fs/section "reference-code/rewrite-clj/src/rewrite_clj/paredit.cljc#slurp-backward")` answers rows 484–506 | FILE-6 |
| FILE-3 | `my.fs` and `my.edit` resolve relative paths against the checkout (`:seon.config.fs/working-root` bound at bootstrap from `SEON_CHECKOUT`, or a root the call names, as `write-branch!` takes `:seon.fn/root`), never the archive extract; `seon.fs/source-directory`'s docstring says "the loaded commit's extract" | `src/seon/fs/jvm.clj` (`path-plan`), `config/default.edn`, `resources/seon/schemas/seon.config.fs.edn`, `src/seon/fs.clj` | a `my.fs/stat` of a file that differs between checkout and extract answers the checkout's size | filed as `my.fs/fs-writes-land-in-the-archive-extract`; the read half is `my.fs/archive-file-reads-have-no-explicit-source-world` |
| FILE-4 | decision A option 2: `lines!` and `form!` deleted with their schemas and tests; `exact!` refuses a program declaration's file with the `defn` fix; a write into the shared checkout checks the `bin/lane` claim ledger (today plain files, `tmp/orchestrator/lanes/<lane>.paths`; a Seon agent's claim is its agent id as the lane) | `src/my/edit.clj`, `src/seon/edit.clj`, `src/seon/edit/jvm.clj`, schemas, `test/my/edit_test.clj`, `test/seon/edit_test.clj` | `exact!` on `src/seon/ref.clj` answers the refusal; on a Markdown file it edits | revises `my.edit/file-edit-tools-bypass-write-back` |
| FILE-5 | one table of checks by file type, called by `bin/seon-hook` and by `exact!`/`write!`, findings as data in the reply; unknown or missing tool answers a typed finding | new owner namespace for the table (or `seon.dev.markdown`'s neighbour), `bin/seon-hook` | an `exact!` that breaks a Markdown reference answers an `:unresolved` finding in the same reply | REF-2, TASK-CHECK |
| FILE-6 | `seon.ref/section` gives a non-Markdown file's `#name` its range by git's funcname rule at the file's revision, and a `#:~:text=` quote by unique match; a miss names the nearest definitions | `src/seon/ref.clj` | the CLM `app.js#newQuestion` row answers lines 141–153 at the CLM pin | CS-01c3, CS-01c5 |
| FILE-7 | one digest-checked atomic install: `write-back!`'s `install!` and `write-edn-atomically!` call `seon.fs.jvm/write` with an expected digest | `src/seon/cluster/source.clj`, `src/seon/fs.clj` | existing write-back tests stay green; a concurrent change between proof and install answers `:stale-digest` | — |
| evidence | 200-byte `my.fs/read`: 48 ms, 44.6 MB, 1,160 armed calls; `stat` 60 ms, 93.9 MB, 1,339 calls | — | — | add to `seon.effect/a-file-stat-costs-a-thousand-armed-calls` |

Order: FILE-1 (one function, red proof exists) and FILE-3 (a correctness defect) first and file-disjoint; FILE-6
after CS-01c3 lands; FILE-4 after decision A; FILE-5 after REF-2 and TASK-CHECK; FILE-7 any time.

## Limits

Read-only probes on default while it was `:faulted` under memory pressure; no write was made through `my.fs` or
`my.edit` (the extract target is shown by `stat` sizes and `config/default.edn`, not by a write). tree-sitter
bindings and grammars were not downloaded or measured; their facts are from the publishers' pages. git's funcname
rule was probed on two top-level definitions only, not on indented methods or with language drivers. SARIF, LSP,
RFC 5147 and W3C fragment facts are cited from the specifications, not from code in `reference-code/`.
