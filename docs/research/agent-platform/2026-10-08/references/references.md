---
title: "References that resolve: one model for tasks, must-reads, evidence and facts"
type: research
status: active
created: 2026-10-08
---

# References that resolve

Question (owner): every reference in tasks, must-reads, evidence and stored facts must resolve — Seon symbols,
schema keywords, library code in `reference-code/`, files with lines. Do we need a custom EDN tag or reader?

**Answer: no new tag and no new reader.** Three plain EDN kinds cover everything: a qualified symbol (code), a
qualified keyword (schema), and a relative path string read as an RFC 3986 relative reference whose first segment
names its base. File and line are derived from the loaded program, never authored. The CS-01c1..3 cards already
implement this model at render time; this note adds ingestion validation and the migration.

## What the REPL showed (default, JVM mode, read-only; commit 406bd11 loaded)

- **Seon var**: `(meta #'seon.task/home)` → `:file "seon/task.clj" :line 381`; `(io/resource :file)` →
  `data/source/406bd11…/src/seon/task.clj`. The program entity has more: `:seon.fn/file`, `:seon.fn/form-span
  [23468 24078]` (byte offsets) and `:seon.fn/source`. Counting newlines before offset 23468 in the *loaded* file
  gives line 381; in the working tree it gives 380, because `src/seon/task.clj` changed since 406bd11. An authored
  `task.clj:381` is already stale; a derived one is not.
- **Library var**: `malli.core/validator` → `malli/core.cljc:2672`, URL
  `…/pins/reference-code-malli/43c6dca8…/src/malli/core.cljc`. The URL carries project and pinned revision
  (CS-01c2). `clojure.core/map` resolves into the Clojure jar (`jar:file:…clojure-1.12.5.jar!/clojure/core.clj`).
- **Schema key**: `:seon.task/must-read` has a `:seon.schema/key` entity (`:seon.schema/form` stored as a string,
  `:seon.schema/references`) and an entry in `(seon.schema/registered-schemas)` (3424 entries). Its owning file is
  derived: the key's namespace names `resources/seon/schemas/seon.task.edn`, and edamame's `:postprocess` gives the
  key's location `{:row 11 :col 2 :end-row 11 :end-col 12}` in 4 ms for the 16 KB file (map keys cannot carry
  metadata, so `:postprocess` is the seam, `edamame/core.cljc:72`).
- **SCI var**: `:file nil` unless `sci.core/file` is bound (`sci/core.cljc:165`); with the binding, `{:file
  "src/a/b.clj" :line 1}`. Seon's stored `:seon.fn/form-span` covers branch definitions instead.
- **Orchard** is not on the classpath. Its `info` (`orchard/info.clj:52`) is `(meta var)` filtered by
  `orchard.meta/var-meta` (`meta.clj:273`), plus `io/resource` on `:file` (`java/resource.clj`, which finds jar paths
  with a regex). It adds special forms, munged names, Java members and ClojureScript. Seon needs the first two calls,
  which are `clojure.core` itself, not a recreation; source text is `clojure.repl/source-fn`
  (`clojure/repl.clj:147`) or Seon's stored `:seon.fn/source`. Adopt Orchard when a task must reference Java
  members; not before.
- **Census of 547 tasks in `resources/seon/tasks/*.edn`** (root counts 567 ingested; the 20 difference is not
  explained here): 917 must-read symbols, 895 resolve to a function entity, 22 do not (most are Babashka code in
  `script/seon/operator.clj`, `seon.operator/check!`, which the JVM never loads; others are deleted or renamed:
  `seon.turn/analyze-settlement`). 641 backticked spans in intents: 378 functions, 29 tests, 187 schema keys,
  14 unresolved, 33 prose or code. Outside backticks: about 433 bare qualified symbols, 270 bare keywords and 160
  bare paths (18 with `:line`). Evidence: 165 distinct paths, 2 missing, 37 under gitignored `tmp/`, 26 untracked
  by Git. No task carries a proofs key.

## The standards, and what they settle

| Standard | Shape | Base | Line range | Verdict for Seon |
|---|---|---|---|---|
| RFC 3986 §4.2, §5 | relative reference resolved against a base URI | one base per document | fragment `#…`, meaning set by media type | **Use**: a path string is a relative reference; its base is named by its first segment |
| SARIF 2.1.0 `artifactLocation` | `{uri, uriBaseId}` | named bases in `originalUriBaseIds` (`%SRCROOT%`) | separate `region {startLine endLine}` | **The model**: named bases. Our bases are `.` (checkout) and `reference-code/<project>` |
| LSP `Location` | `{uri, range}` | absolute `file://` URI only | 0-based `{line character}` | An output face (editor link), not storage: machine-specific |
| deps.edn `:local/root`, `:paths` | plain relative strings | the `deps.edn` directory | none | Same as ours: plain string, implied base. No tag needed |
| Aero `#include` | tagged literal | the including file (`aero/core.cljc:105`) | none | Shows the cost: a tag needs a reader in every reader |
| EDN tagged literal (`#seon/ref …`) | `#tag value` | — | — | **Reject**: Clojure, SCI, edamame, Babashka, clj-kondo and Datahike's own readers would each register it; plain EDN already types symbols, keywords, strings |
| Datomic `:db.type/uri` | `java.net.URI` value | — | — | Datahike has no URI type (`datahike/schema.cljc:35`); store a string validated by Malli |
| git `rev:path` (gitrevisions) | `43c6dca8:README.md` | a commit | none (GitHub adds `#L10-L20`) | The revision is derived from the gitlink, never written |

Line ranges are derived, not authored. For code the symbol gives `:line` (Var) or `:form-span` (entity), and
clj-kondo gives `:row`/`:end-row` (`:var-definitions`, `clj_kondo/impl/analysis.clj:111`). For a schema key,
edamame's `:postprocess` gives the row. For Markdown, an RFC 3986 fragment names a heading
(`docs/seon/architecture/README.md#boot`): GitHub's slug rule, resolved by reading headings at render. A literal
`#L10-L20` fragment is accepted only for files with no symbol or heading (logs, data) and is shown as a drift-prone
problem when the file changed since the task's commit.

## Recommendation: one reference model

| Kind | Written in task EDN | Stored in Datahike | Resolves through | Derived automatically |
|---|---|---|---|---|
| Seon function, test or namespace | `seon.task/home` (symbol in `:must-read`, or backticked in intent) | `:db.type/symbol` (as today, `:seon.program/edge-symbol`) | `[:seon.fn/sym s]`, `[:seon.test/sym s]`, `[:seon.ns/name s]` on the db | file, line (from span), arglists, doc, contract, source |
| Library var or namespace | `malli.core/validator` | symbol | `find-ns` + `ns-resolve` (`find-var` throws on an unknown namespace), `io/resource (:file meta)` | file, line, project and pinned revision from the resource URL |
| Schema | `:seon.task/must-read` (backticked) | `:db.type/keyword` | `[:seon.schema/key k]` and the registry | form, owning resource `resources/seon/schemas/<ns>.edn`, row by edamame |
| File | `"reference-code/malli/README.md#usage"` or `"docs/…/x.md"` | `:db.type/string`, Malli `[:string {:min 1}]` | base = first segment: `reference-code/<p>` → pin from `gitlink-pins` (CS-01c3); else the loaded commit's source directory | revision, existence, heading anchor check |

Rules:

1. **Code is never a path.** A path to a `.clj` file with a line is rewritten to the symbol defined there.
2. **No new reader.** Ingestion already reads task EDN with edamame; backticked spans are read with
   `edamame.core/parse-string` (CS-01c1). A span starting `reference-code/`, `docs/`, `src/`, `test/`, `resources/`
   or `script/` is a path; anything else that does not read as a qualified symbol or keyword is prose.
3. **One attribute per value type.** Datahike attributes take one value type, so keep `:seon.task/must-read`
   (symbols) and `:seon.task/evidence` (strings); keywords and paths named in the intent are read from the intent
   at render, not copied into a second attribute (a copy would be a mirror).
4. **`tmp/` is not a reference.** It is gitignored and absent on every other machine; evidence must live in a
   tracked path (memory: no important info in tmp).
5. **Proofs are not authored on the task.** The deftests carrying the task in `:seon/tasks` are its proofs; the
   487 prose mentions become that metadata on the named deftest.

**Ingestion validation.** After the task transaction, ingestion resolves each reference against the same db value
and returns problems beside the ingested tasks, never refusing: a task may name what it will create. The shape
follows `:seon.fn/unresolved-references` (`src/seon/fn.clj:1499`): `{:seon.context/name x :seon.context/problem
:unresolved}` from CS-01c1, plus `:not-tracked` (a path under `tmp/` or not in Git) and `:anchor-missing`. Cost: one
entity lookup per symbol or keyword and one existence check per path; 917 must-reads plus 641 spans resolved in
150 ms in the probe above.

**Current-state block.** Each kind shows as CS-01c1..3 specify: a function shows name, arglists, first doc line and
`(doc s)` / `(clojure.repl/source s)` operations; a library var adds `:seon.context/pin {project revision}`; a schema
shows its form and `(doc k)`; a file shows its path, pin and a `slurp` read form; an unresolved one shows its
problem. The line appears only in the read form's answer, never in the task.

**Migration, by one script over `resources/seon/tasks/*.edn`.**
1. Wrap the bare qualified symbols and keywords in intents in backticks when they resolve (433 and 270 candidates);
   leave the rest as prose and list them.
2. Rewrite `file.clj:N` mentions to the symbol whose span contains line N in the loaded commit's file (clj-kondo
   analysis of that file); report the ones it cannot place.
3. Move `tmp/` evidence (37) into tracked research directories or drop it; report the 2 missing paths.
4. For each prose proof name that reads as a deftest symbol, add the task's handle to that deftest's `:seon/tasks`.
5. Re-ingest and print the problem list; the remainder is hand work.

## Limits of this note

Read-only probes on default (degraded only by over-bound warnings already recorded). The 547 vs 567 count and the
22 unresolved must-reads were not traced further. The Markdown heading slug rule and SARIF, LSP and RFC sections
are cited from the standards, not from code in `reference-code/`.
