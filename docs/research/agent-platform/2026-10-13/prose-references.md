---
title: "Prose references: what is uniquely identifiable, what is parsed, what resolves"
type: research
status: research, read-only; no code, schema or doc changed; probes read-only on default in JVM mode
date: 2026-10-13
basis: "HEAD refactor/agent-platform at 2b2d71818 plus working tree; code read with sed and rg; probes via mcp__seon__eval_clj jvm"
---

# Prose references: what is uniquely identifiable, what is parsed, what resolves

Research only, dated 2026-10-13. Owner rule: "all prose everywhere in every docstring should be getting the same parsing treatment which pulls out everything that we know how to uniquely identify in the system."

Evidence tags: [V] verified by reading code or running a probe on default's database (JVM mode, read-only); [I] inference. Probes ran through `mcp__seon__eval_clj`, mode jvm, cluster default, `(def db (datahike.api/db (seon.cluster.boot/connection "default")))`, calling `(seon.ref/references db {:seon.ref/text ...})`.

## Summary

- Seon has one prose parser, `seon.ref`: commonmark-java reads a text once (`seon.ref/document`), takes code spans and links as candidates, and resolves them by one rule (`seon.ref/references`). [V] `src/seon/ref.clj`, ns docstring: "a code span is a candidate name or path, a link is a path."
- Seon parses only four prose sites today: task intent and instructions (writer-derived references), a function's docstring (one level, when the function is cited), a schema key's `:description` (one level, when cited), and Markdown section bodies (when a doc section is cited). [V] See part 2.
- Not parsed: namespace docstrings, deftest docstrings, `(help)` text, error messages.
- A backticked qualified symbol or keyword resolves for functions, tests, tasks, schema keys, library Vars and document paths. A namespace name (`my.agent`) does not resolve, because it has no slash. Bare (unbackticked) names are ignored. [V] probes P1 to P8 in part C.
- The smallest change for "one parse for all prose" is in part D.
- The owner's cost figure (1,426 tokens, 24 refs, 28 ms) is not in any document under `docs/`. [V] A search for `28 ms`, `1,426` and `24 refs` finds only unrelated lines. My own measurement on 5,600 characters of `docs/seon/architecture/vocabulary.md` (about 1,400 tokens at 4 characters per token [I]) gave 30 references, 13 resolved, at 26 to 51 ms over three runs. [V] The order matches; the exact figure is not reproduced.

## Part 1. Identity kinds in the database

Identity attributes come from `:seon.db/identity true` or `:seon.db/unique true` in `resources/seon/schemas/*.edn`. [V] The grep returned 35 identity declarations across 30 files.

| Identity kind | Identity attribute (schema file) | Text form that names it | Resolves in `seon.ref/references` today |
|---|---|---|---|
| Function (Var) | `:seon.fn/sym`, qualified symbol (`seon.fn.edn:213`) | backticked `ns/name` | yes [V] P1 |
| Library Var (loaded or in the dependency cache) | not a Seon attribute; `library-name` reads the host Var or `analyzer/unloaded-definition` | backticked `ns/name` | yes [V] P2 |
| Test | `:seon.test/sym`, qualified symbol (`seon.test.edn:57`) | backticked `ns/name` | yes [V] P3 |
| Namespace | `:seon.ns/name`, plain symbol with no slash (`seon.ns.edn:8`) | backticked `ns` | no [V] P4 (not a qualified name, so `span-candidate` returns nil) |
| Task | `:seon.task/sym`, unique qualified symbol (`seon.task.edn:5`); `:seon.task/id`, a 12-character handle (`seon.task.edn:3`) | backticked `ns/task-name` | yes for the symbol [V] P5; the handle has no text form |
| Schema key (also attribute keywords and error types, which are schema keys) | keyword in the Malli registry; no identity attribute | backticked `:ns/key` | yes [V] P6, P7, P9, P10 |
| Attribute | the keyword itself; attributes and schema keys share one keyword space [I] | backticked `:ns/key` | yes [V] P9 |
| Error type | `:seon.error/type` values are schema keys extending `:seon.error/base` (`seon.error.edn:424`) | backticked `:ns/key` | yes [V] P10 |
| Config dial | a schema key, e.g. `:seon.config.eval.result/blob-threshold` (`seon.config.eval.result.edn`) | backticked `:ns/key` | yes [V] P11 |
| Document (Markdown file) | `:seon.fn.file/relative-path` (`seon.fn.file.edn:1`) only for Clojure files; Markdown has no entity [V] | backticked relative path, first segment a base (`docs/`, `src/`, `AGENTS.md`) | yes [V] P12 |
| Document section | heading anchor, derived by `IdGenerator` from heading text [V] `ref.clj` | `path#anchor` in backticks or a link | yes [V] P12, P13, P18 |
| Pinned library file | `reference-code/<project>/...` at the gitlink pin [V] `ref.clj` `pinned-revision` | backticked `reference-code/<project>/path#anchor` | yes [V] P14 |
| Program agent | `:seon.agent/id` (`seon.agent.edn:137`), string such as `root` | none; `root` is not a qualified name | no [V] (no probe; the bare-word rule excludes it) |
| Cluster | `:seon.cluster/name`, string such as `default` (`seon.cluster.edn:15`) | none | no [V] P19 |
| Branch | `:seon.branch/fork-id`, 12-character string (`seon.branch.edn:2`) | none | no [V] P20 |
| Message | `:seon.message/id` (`seon.message.edn:48`) | none | no [I] (same rule as P19) |
| Lint finding | `:seon.lint/id` (`seon.lint.edn:1`) | none | no [I] |
| Error occurrence and signature | `:seon.error/id`, `:seon.error/signature` (64-character hash) (`seon.error.edn:113`, `:270`) | none | no [I] |
| Call preparation | `:seon.call-preparation/key`, a keyword tuple (`seon.call-preparation.edn:22`) | none | no [I] |
| Blob | `:seon.blob/digest`, hash | none | no [I] |
| Datahike `:db/ident` | not an identity kind in Seon schemas; used only by `seon.cluster` for declarations (`cluster.clj:559-869`) | none | no [I] |

Probe results for the identifiers that have no text form (P19, P20): `default` and `93c138d1bfc3` backticked both returned no answer [V]. Opaque hashes and handles cannot be named in prose without a new syntax.

## Part 2. Where prose is parsed today, and where not

Every prose site below goes through `seon.ref` only where the code calls `ref/references`, `ref/names`, `inner`, or `section`. [V] Call sites: `src/seon/task.clj:265` (`ref/names`), `src/seon/context.clj:242` (`ref/references`), `src/seon/context.clj:620` (`ref/names`), `src/seon/ref.clj` `resolve-name`, `resolve-path`, `section`, `inner`.

| Prose location | Parsed? | By what | Stored as | Shown where |
|---|---|---|---|---|
| Task intent and instructions (`:seon.task/intent`, `:seon.task/instructions`) | yes, writer-side | `seon.task/with-references` calls `ref/names` on intent and instructions (`task.clj:261-267`) | `:seon.task/references`, a set of symbols, derived at write (`task.clj:265`) | context: `task-references` (`context.clj:236-243`) into `:seon.context/must-reads` (`context.clj:661`); each referent resolved and shown one level |
| Function docstring (`:seon.fn/doc`) | yes, when the function is cited | `resolve-name` calls `inner` on `(:seon.fn/doc function "")` (`ref.clj` `resolve-name`) | `:seon.fn/doc` raw string (`fn.clj:835`; `seon.fn.edn:149`); references are not stored | context: only the first line of its summary (`ref.clj` `resolve-name` `:doc`), plus the one-level `:seon.ref/references` of a cited function |
| Schema description (`:description` on a schema form) | yes, when the key is cited | `resolve-name` calls `inner` on the description from `lookup/schema-documentation` (`lookup.clj:451`) | not stored; read live from the schema form | context: `:seon.ref/schema-form` and one-level references of a cited key |
| Markdown section body (`docs/...#anchor`, `AGENTS.md#...`) | yes, when the section is cited | `resolve-path` calls `section`, then `inner` on the section text (`ref.clj` `resolve-path`) | not stored; files only | context and `my.fs/section`; one-level references |
| Markdown document as a whole | only through its sections above | `document` (headings, spans) via `cache/lookup` by digest | cache only (`seon.cache`) | `my.fs/section` returns text and sizes, never the whole document as a reference source |
| Deftest docstring (`deftest name "doc" ...`) | no | `resolve-name` for tests calls `inner` with text `""` (`ref.clj` `resolve-name`, test branch) | inside `:seon.test/source` (the whole form); no attribute | shown inside the test source as raw text, unparsed |
| Namespace docstring (`:seon.ns/doc`) | no | none; `render/ns.clj:250`, `:260-263` shows its first line only | `:seon.ns/doc` (`seon.ns.edn:27`; `fn.clj:261`) | ns render, first line, unparsed |
| `(help)` text | no | none; `src/seon/bootstrap.clj` has no `ref` reference (grep count 0) [V] | not stored; composed by `help-value` (`bootstrap.clj:41`) | the system message |
| Error messages (`:seon.error/message`) | no | none; `problem-messages` is fixed text in `ref.clj` | `:seon.error/message` string (`seon.error.edn:113`) | the reply |
| `:seon/must-read`, `:seon/see-also`, `:seon/tasks` | not prose; read as symbols by the reader | `fn.clj` `task-links` (`fn.clj:658-672`) | `:seon.fn/must-read`, `:seon.fn/see-also`, `:seon.task/tasks` | must-read sections (`context.clj:611-661`) |
| Task intent that is not backticked | no | `span-candidate` takes code spans only | nothing | nothing |

## Part 3. Does parsing need backticks, and which spans count?

[V] `ref.clj` `span-candidate` and `candidate`:

- A code span (`Code` node) is a candidate. A link (`Link` node) is a path candidate, RFC 3986 relative, resolved against the document directory. Fenced and indented code blocks contribute nothing (`FencedCodeBlock nil`, `IndentedCodeBlock nil`).
- A span is a name only when `edamame/parse-string-all` reads exactly one form, and that form is a qualified symbol or qualified keyword (`(nil? more)`, `qualified-symbol?`, `qualified-keyword?`).
- A span whose first segment (before `/`, `#` or `:`) is a root directory of the checkout is a path. A span with a `path:N` or `path:A-B` suffix is a `:line-reference` problem.
- Everything else is prose.

So bare text is never a candidate: `Text` nodes are not visited. [V] The parser has no `Text` branch in `parsed`.

Probe evidence: the bare prose `seon.ref/references` returned no answer (P15); the bare task handle in prose returned no answer (P16). [V]

False-positive risk if bare names were parsed [I, measured by grep]:
- `rg` over `src/seon/**/*.clj*` for an unbackticked `ns/name` token, excluding lines that start with `(`, gives about 2,362 matching lines. Most are code (`db/q`, `db/pull`, `malli.core/schema?`, `clojure.core/ifn?`) or file paths (`sci/eval.clj`, `render/web.clj`, `cluster/agent.clj`). A grep cannot separate docstrings from code, so that count is an upper bound, not a docstring count.
- Prose-shaped false positives found: `and/or` (1 line), `input/output` (1 line), URLs (`https?://` in 2 lines of `src/seon` ; the `http` substring appears in 61 lines but mostly in names).
- A bare file path like `sci/eval.clj` looks like `ns/name`. Its first segment is not a base, so it must not parse as a name, but it would need the check.
- Conclusion [I]: bare parsing is safe only if a candidate is kept only when it resolves to an existing program entity (fn, test, task, schema key, library Var). Unresolved bare tokens are then dropped silently, which matches what the backtick rule avoids.

## Part 4. Cost

- Probe timing for single-line texts [V]: 0.4 to 11 ms per call for names; 60 ms for a Markdown section (`AGENTS.md#data-and-contracts`, which reads a file); 21 ms for a `reference-code` section; 8 ms for a function; 0.4 ms for prose with nothing to resolve.
- A 5,600-character text (`vocabulary.md` prefix, about 1,400 tokens [I]) parsed in 26 to 51 ms over three runs, with 83 spans and 30 references (13 resolved) [V]. The owner's figure (28 ms, 24 references) is in the same range; it is not reproduced in a document.
- Cost is dominated by the library and file lookups of the resolved names, not the parse [I]: the `document` cache keys by SHA-256 of the text, so a repeat of the same text costs the lookup only.

## Part A. Every reference syntax Seon defines

| # | Syntax | Where specified (quoted) | Resolved by | Notes |
|---|---|---|---|---|
| 1 | Backticked fully qualified symbol: code, Seon or library | CLAUDE.md, "Write tasks as data an agent can be handed": "code is a backticked fully qualified symbol (Seon or library, e.g. `malli.core/validator`)" [V] | `span-candidate` → `:seon.ref/name` | the only code-name syntax |
| 2 | Backticked fully qualified keyword: a schema key | CLAUDE.md, same line: "a schema a backticked fully qualified keyword" [V]; `data-oriented-clojure/SKILL.md` 9.3: "a reference is a backticked span that reads as one fully qualified symbol or keyword" [V] | `span-candidate` → `:seon.ref/name` | also resolves attributes and error types |
| 3 | Task `ns/task-name` | CLAUDE.md, "Documentation is data": "a task's is `ns/task-name`, its identity derived from namespace and name" [V] | same as 1, resolved through `:seon.task/sym` | the handle is not a name |
| 4 | Relative path with `#heading` anchor, first segment a base | CLAUDE.md, "Write tasks as data": "any other file a relative path whose first segment names its base (the checkout, or `reference-code/<project>` at its pinned revision) with `#heading` anchors" [V] | `span-candidate` → path; `resolve-path` → `section` | |
| 5 | Markdown link `[text](path#anchor)` | `seon.ref` ns docstring "a link is a path" [V]; `link-candidate` RFC 3986 | `candidate` (link branch) | a `#anchor` alone names the document (`candidate`); with no document path it resolves nothing (P17) |
| 6 | `reference-code/<project>` path | CLAUDE.md line above; `docs/seon/architecture/reference-code.md`; `seon.ref/pinned-revision` [V] | path; `pinned-revision` attaches the gitlink pin | |
| 7 | `path:N`, `path:A-B` | CLAUDE.md, "Never write a file or line for code" (no `path:N`) [V]; `seon.ref/line-citation` returns `:line-reference` [V] | a problem (`:line-reference`), not a reference | a stale citation is refused, not resolved |
| 8 | `:seon/tasks` metadata | CLAUDE.md, "A definition or test names its tasks with `:seon/tasks` metadata (on a deftest, `^{…}` before the name)" [V]; `clojure-testing/SKILL.md` 2.12 | `fn.clj` `task-links` (reader metadata, not prose) | links a deftest to a task |
| 9 | `:seon/must-read`, `:seon/see-also` | `fn.clj:658-672` [V] | reader metadata | not prose |
| 10 | Task handle (12 characters) | `seon.task.edn:3` identity [V] | none | not a text reference |
| 11 | `tmp/` paths: forbidden as references | CLAUDE.md: "A reference never points into gitignored `tmp/`" [V] | not enforced: P21 resolves to `:unresolved` | a gap (part D) |
| 12 | Library `reference-code` project paths for code | CLAUDE.md, "read its source under `reference-code/<project>`" [V] | `library-name` | |
| 13 | Docstring Example forms | `instrument.clj:276` reads the Example section as data, never run [V] | not a reference | separate syntax |

Searched and not found as separate reference syntaxes: `docs/seon/architecture/*.md` (only `reference-code/<project>` and `#heading` as above), `.agents/skills/*` (9.3 and 9.4 above), `seon.ref` docstrings (items 1 to 7). No `[[wiki]]` or `@ref` syntax exists [V by grep of `docs/seon/architecture` and `.agents/skills`].

## Part B. Coverage: identity kind × syntax × resolves today × renders

| Identity kind | Syntax that names it | Resolves today (probe) | What it renders |
|---|---|---|---|
| Function | item 1, backticked | yes (P1) | source whole; `my.program/callers` and `my.program/tests-reaching` operations; `:malli/schema`; `:arglists` when no source |
| Library Var | item 1 with a library namespace | yes (P2) | `doc` line; `clojure.repl/source` operation; `:seon.ref/pin` |
| Test | item 1 | yes (P3) | `:seon.test/source` whole |
| Namespace | no syntax | no (P4) | nothing |
| Task | item 3 | yes (P5) | `:seon.task/intent` and `:seon.task/instructions` first line; one-level references |
| Schema key | item 2 | yes (P6, P7) | `:seon.ref/schema-form`; one-level references from its `:description` |
| Attribute | item 2 | yes (P9) | schema form |
| Error type | item 2 | yes (P10) | schema form |
| Config dial | item 2 | yes (P11) | schema form (`:int`) |
| Document path (no anchor) | item 4 | yes (P12) | heading; operation `my.fs/section` |
| Document section | item 4 or link (item 5) | yes (P13); bad anchor is a problem (P18) | heading and section operation |
| Pinned library document | item 6 | yes (P14) | path with pin; section operation |
| Path with `path:N` | item 7 | problem (P8) | problem `:line-reference` |
| Unknown name | item 1 | problem (P22) | `:unresolved` |
| Tmp path | item 4 | `:unresolved` (P21) | problem; should be refused |
| Agent, cluster, branch, message, lint, error id, call-preparation, blob, handle | none | no (P19, P20) | nothing |
| Datahike `:db/ident` | none (it is a schema key) | not a separate kind | n/a |

## Part C. Probes (each run on default, JVM mode, read-only)

Status: RESOLVED, PROBLEM, or NOT A REFERENCE. Long source is trimmed to its first line.

- P1 `` `seon.ref/references` ``: RESOLVED. Name `seon.ref/references`; source first line `(defn references`; ops `(my.program/callers 'seon.ref/references)`, `(my.program/tests-reaching 'seon.ref/references)`.
- P2 `` `malli.core/validator` ``: RESOLVED. Doc `Returns an pure validation function of type x -> boolean for a given Schema.`; ops `(doc malli.core/validator)`, `(clojure.repl/source malli.core/validator)`.
- P3 `` `my.agent-test/retire-branch-accepts-what-branch-returns` ``: RESOLVED. Test source first line `(deftest retire-branch-accepts-what-branch-returns`.
- P4 `` `my.agent` `` (namespace): NOT A REFERENCE. No answer. No slash, so `span-candidate` returns nil.
- P5 `` `my.agent/a-refused-retire-leaves-the-agent-admission-closed` ``: RESOLVED. Intent first line begins "`(my.agent/retire-branch! {:seon.store/branch :agent-cfd70b5547f7})` run by agent r-turn ..." (trimmed).
- P6 `` `:seon.ref/name` ``: RESOLVED. Schema form head `:or`.
- P7 `` `:seon.ref/section-error` ``: RESOLVED. Schema form head `:and`.
- P8 `` `src/seon/ref.clj:12` ``: PROBLEM `:line-reference`, path `src/seon/ref.clj`.
- P9 `` `:seon.fn/sym` `` (attribute): RESOLVED. Schema form head `:qualified-symbol`.
- P10 `` `:seon.error/base` `` (error type base): RESOLVED. Schema form head `:map`.
- P11 `` `:seon.config.eval.result/blob-threshold` `` (dial): RESOLVED. Schema form head `:int`.
- P12 `` `docs/seon/architecture/vocabulary.md` `` (document, no anchor): RESOLVED. Heading `Vocabulary`; op `(my.fs/section "docs/seon/architecture/vocabulary.md")`.
- P13 `` `AGENTS.md#data-and-contracts` ``: RESOLVED. Path `AGENTS.md`, anchor `data-and-contracts`.
- P14 `` `reference-code/malli/README.md#quickstart` ``: RESOLVED. Path `reference-code/malli/README.md`, anchor `quickstart`.
- P15 bare prose `see seon.ref/references in prose`: NOT A REFERENCE. No answer.
- P16 bare task handle in prose: NOT A REFERENCE. No answer.
- P17 `[here](#no-such)` with no document path: NOT A REFERENCE. No answer; an anchor alone needs `:seon.ref/document-path`.
- P18 `` `docs/seon/architecture/vocabulary.md#no-such-heading` ``: PROBLEM `:anchor-missing`, with the message "The file has no heading with this anchor; (my.fs/section path) lists its headings."
- P19 `` `default` `` (cluster name): NOT A REFERENCE. No answer.
- P20 `` `93c138d1bfc3` `` (fork id): NOT A REFERENCE. No answer.
- P21 `` `tmp/foo.md` ``: PROBLEM `:unresolved`. Should be refused per CLAUDE.md "A reference never points into gitignored `tmp/`"; the parser does not check it.
- P22 `` `seon.ref/no-such-fn` ``: PROBLEM `:unresolved`.
- P23 `` `references` `` (unqualified) and `` `(seon.ref/references db {})` `` (a list form): NOT A REFERENCE. No answer.
- P24 `[site](https://example.com/x#y)`: NOT A REFERENCE. No answer; the scheme makes it external, which is the intended behavior.

Discovery (for choosing instances) [V]: the first three values of each identity attribute in default's database. `:seon.fn/sym` included `astra-cost-handoff/costs` (a scratch namespace, not a checked-out source), so probes used main-tree instances instead.

## Part D. Gaps, and the smallest change in `seon.ref` for each

1. Namespace names do not resolve. A backticked `my.agent` is not a qualified name. Smallest change: in `span-candidate`, accept a plain symbol that matches a `:seon.ns/name` in the database (one `db/pull` in `resolve-name`, plus an `ns` branch in the candidate rule). Requires a rule for ambiguity with a single-word span; the backtick keeps it unambiguous.
2. Bare names in prose are not parsed. Smallest change: add a `Text` branch to `parsed` that collects qualified-symbol tokens, and keep one only when `resolve-name` finds it in the database; unresolved bare tokens are dropped, not reported. This fixes the owner's rule with the false-positive guard in part 3 of this document. [I]
3. Namespace docstrings (`:seon.ns/doc`) are not parsed. Smallest change: call `inner` from `render/ns.clj` for the ns docstring text, using the cited names as in `resolve-name`. Stored as today; references derived at read.
4. Deftest docstrings are not parsed and not stored. Smallest change: `resolve-name` for a test passes the docstring instead of `""` (`ref.clj` test branch). The docstring is inside `:seon.test/source`; extract it with the analyzer's docstring (`analyzer/docstring`-style, `analyzer.clj:450`), not a second parser.
5. `(help)` text is not parsed. Smallest change: none inside `seon.ref`; `bootstrap.clj` `help-value` would call `ref/references` on its own prose, which is a change to the help owner, not to `seon.ref`.
6. `tmp/` paths are not refused. Smallest change: in `resolve-path` or `section`, a path whose first segment is `tmp` returns a refusal (a `:seon.ref/problem-kind` value such as `:ignored`), counted in the contract `:seon.ref/problem-kind`. Adding a value to the enum in `resources/seon/schemas/seon.ref.edn` is one line.
7. Anchor-only links need a document path. Smallest change: pass `:seon.ref/document-path` from the caller that holds the document (`section` already does this); for task intents, a `#anchor` alone has no document and should be a problem, not silent.
8. The owner's cost figure is not in a document. Smallest change: none in code; add the measurement in part 4 to a commit message or a dated research note.

Smallest change for one parse over all prose: make `seon.ref/references` the one entry for every prose site. Today `inner` is already that entry for four sites (function docstring, schema description, document section, task intent). The change is to route the three missing sites (namespace docstring, deftest docstring, bare-name `Text` nodes) through `inner` with the same `document` parse, and to keep the candidate rule in `span-candidate` the only place that decides what a name is. [I] About 25 lines in `ref.clj` plus one call in `render/ns.clj` and one in `resolve-name`'s test branch.
