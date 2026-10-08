---
title: "Card REF-2: The edit hook checks references; one sweep lists every problem"
type: reference
status: active
created: 2026-10-08
---

# REF-2: The edit hook checks references; one sweep lists every problem

Depends on: REF-1, CS-01c3 (paths and anchors), TASK-CHECK (a task's references assembled once). Group D (owns
`bin/seon-hook`; serial with any lane holding it). Model: Sonnet.

Owner: "Schedule the edit hook to check all docs and task edn files for validity of all refs too." After an edit to a
`.md` file, a task `.edn` file or a Clojure file, the hook asks the running JVM for that one file's unresolved
references (REF-1, the same rules everywhere) and shows them as findings, printed by the value printer through the
one REPL path. A one-time scripted sweep runs the same function over every doc and task file and lists the problems by
owner.

## Files

- `src/seon/ref.clj` (`file-problems`)
- `src/seon/task.clj` (`file-problems` for task files)
- `test/seon/ref_test.clj`
- `bin/seon-hook`
- `test/seon/dev/hook_test.clj`
- `script/seon/dev/markdown.clj`, `script/seon/dev/markdown_test.clj` (wikilink rule deleted)
- `script/seon/dev/ref_sweep.clj` (new, the sweep)
- `docs/research/agent-platform/<landing date>/ref-sweep/problems.tsv` (the sweep's output, committed)

## What is true at HEAD

- `bin/seon-hook` (Babashka) reaches default after a Clojure edit through
  `seon.operator/prepl-value!` (`script/seon/operator.clj:180`) in `entrance-one` (`bin/seon-hook:1250`): it sends an
  eval request as the lane's agent (`eval-request lane namespace code`), and the reply's texts are the value printer's
  output. With no lane it answers "REPL entrance: no lane … lint only"; with no advertised endpoint "REPL entrance:
  default unavailable: lint only" (`:1265`, `:1268`).
- Markdown edits go to `markdown-feedback` (`bin/seon-hook:1383`), which loads `seon.dev.markdown` into Babashka
  (no JVM) for style rules; task-file edits are refused before the write only when the text is not one vector
  (`validate-task-edit`, `:977`; `task-file-path?`, `:968`).
- `seon.dev.markdown` extracts links by line scanning (`line-links`, `script/seon/dev/markdown.clj:201`) and checks
  only wikilinks (`rule-wikilink-target-exists`, `:586`, via `find-in-vault`, `:568`). `docs/` and `.agents/` hold no
  wikilinks (`grep -rhoE '\[\[[^]]+\]\]'` finds 0) and 216 Markdown heading links, which nothing checks.
- SCI evaluation reaches host `seon.*` functions (probed: `(seon.task/task-path? "resources/seon/tasks/seon.turn.edn")`
  → `true`, 0 ms, from an agent's SCI context).
- clj-kondo analysis of one file with `:var-definitions` and `:namespace-definitions` gives every docstring and its
  row: 145 ms for `src/seon/task.clj` (52 definitions), `:cache false`.

## Functions

```clojure
(defn file-problems
  "The unresolved references one checkout file makes: a Markdown doc's, each docstring's, each task's, by line or task."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.ref/path] [:or [:vector :seon.ref/finding] :seon.error/value]]}
  [database path] ...)
```

In `seon.ref`. Reads the working-tree file at `path` (the hook checks what was just written). By extension:

- `.md`: `(references database {:seon.ref/text text :seon.ref/document-path path})`, problems only. A document whose
  front matter `type` is `research` or `issue` is point-in-time evidence (the same rule as `seon.dev.markdown`'s pin
  check, `markdown.clj:846`): answer `[]` (REF-1's `document` gains `:seon.ref/type` from the YAML front-matter
  extension's `YamlFrontMatterVisitor`; accretion on `:seon.ref/document`).
- `.clj`, `.cljc`: clj-kondo analysis of the one file (the call above); each namespace and var docstring is a source
  `{:seon.ref/text doc}`; each problem's line is the definition's `:row` plus its line in the docstring minus one.
- A task file (`seon.task/task-path?`, `src/seon/task.clj:70`): `((requiring-resolve 'seon.task/file-problems) database
  path text)` (`seon.task` requires `seon.ref`, so the call resolves late, as `seon.cluster.source` already does).

`seon.task/file-problems [database path text]`: the file's tasks through the private `file-declarations`
(`src/seon/task.clj:119`), each through TASK-CHECK's per-task assembly (the same function `task-problems` uses, never
a copy), as findings carrying `:seon.task/sym`. A refused file answers its refusal.

```clojure
:finding [:merge :seon.ref/problem [:map [:seon.task/sym {:optional true} :seon.task/sym]]]
```

## The hook (`bin/seon-hook`)

One new `references-feedback [event config]`, called beside `markdown-feedback` and `entrance-feedback` on
`PostToolUse` for each edited path that is Markdown (`authority-markdown-file?` or a root-level `.md`), a task file
(`task-file-path?`) or a contract source (`contract-source-path?`). Same entrance as `entrance-one`: lane from
`entrance-lane`, endpoint from `operator/advertisement`; no lane → `"References: no lane: skipped"`; no endpoint →
`"References: default unavailable: skipped"`. Otherwise one eval request as the lane's agent with the form
`(seon.ref/file-problems (seon.db/db (seon.cluster.boot/connection "default")) "<relative path>")`. First probe: does
an SCI evaluation supply the agent's own database by schema name for a host function's omitted
`:seon.db/database-value` (AGENTS.md "Supply defaults by declared schema name")? If it does, send
`(seon.ref/file-problems "<relative path>")` so the lane's branch, where its own definitions live, answers; if not,
record the gap as a task on the supply owner and send the agent's branch database explicitly. References resolve against the
loaded commit (REF-1's bases and CS-01c5's `locate`), so a link to a doc written but not yet committed is reported
`:unresolved` until `start --head` loads it; the finding says what is true of the running program. The reply's printed
text is the feedback (value printer, its budget); an empty vector prints nothing. Bound: `:repl-entrance :timeout-ms`, as
`entrance-one`.

Delete from `script/seon/dev/markdown.clj`: `rule-wikilink-target-exists`, `find-in-vault`, the `:wikilink` branch of
`line-links` and the `:wikilink` link type, the rule's entry in the rule set (`:810`, `:839`) and the wikilink tests in
`markdown_test.clj`. Relative link targets are REF-1's now.

## The sweep (`script/seon/dev/ref_sweep.clj`)

Babashka, run once: `bb script/seon/dev/ref_sweep.clj OUT.tsv`. Paths: `git ls-files '*.md' 'resources/seon/tasks/*.edn'`
(tracked only). One prepl form to default in JVM mode (`operator/prepl-value!`), which maps
`seon.ref/file-problems` over all paths on one database value and answers the findings as EDN data; the script writes
one TSV row per finding (`path line-or-task kind name-or-path anchor`) sorted by path, and prints per-directory counts
(owners: `docs/seon/…` architecture, `.agents/skills/…` each skill, `resources/seon/tasks/<ns>.edn` its namespace's
owner). Commit the TSV beside the landing note. Point-in-time documents are counted, not listed.

## Proof

- `seon.ref-test/file-problems-answers-a-doc-a-docstring-and-a-task-file-by-the-same-rules`: three fixture files under a
  test root (a Markdown doc with a dead link and a dead `#heading`, a `.clj` file whose docstring backticks
  `` `tq.a/missing` ``, a task file whose intent names `` `AGENTS.md#no-such-heading` ``), through `seon.mcp/call`;
  assert each finding's kind, line or task as data.
- `seon.dev.hook-test/a-markdown-edit-reports-its-unresolved-references`: the hook fed a `PostToolUse` event for the
  fixture doc with a lane on a running test cluster (the file's existing `a-clojure-edit-evaluates-its-changed-defn-on-the-lane-branch`
  fixture); assert the feedback contains the dead path, and with no endpoint the feedback is the skipped line.
- `seon.dev.markdown-test` keeps passing without the wikilink cases.

`--test seon.ref-test --test seon.dev.hook-test --test seon.dev.markdown-test`.

## Cost

One file per edit: a read, one cached parse (10 ms for the 248 KB ledger warm), one lookup per name, one file read per
path; a Clojure file adds one clj-kondo analysis (145 ms for `task.clj`). Target well under 1 s per edit including
the prepl round trip; measure with `test/seon/dev/hook_measure.clj` on the ledger, `AGENTS.md`, `src/seon/turn.clj`
(the largest source file) and `resources/seon/tasks/seon.turn.edn`, and report each. Over 1 s names the responsible
function and is fixed before landing. The sweep: one JVM call over every tracked doc and task file; report its time.

## Deleted

`seon.dev.markdown`'s wikilink rule, `find-in-vault` and wikilink extraction (no wikilinks exist). Not deleted:
`seon.dev.markdown/extract-headings` (`:166`), a line-scanning heading reader its formatting rules use
(heading style, increment, single h1, blanks around headings). It reads no references, but it is a second Markdown
heading parser in Babashka, where commonmark is not loaded: the sweep's landing note records it as a task on
`seon.dev.markdown` (move those rules onto REF-1's `document` in the JVM, or delete them).

## Commit

`bin/lane commit ref2 -m "REF-2: the edit hook checks references; one sweep lists every problem" --test seon.ref-test --test seon.dev.hook-test --test seon.dev.markdown-test src/seon/ref.clj src/seon/task.clj test/seon/ref_test.clj bin/seon-hook test/seon/dev/hook_test.clj script/seon/dev/markdown.clj script/seon/dev/markdown_test.clj script/seon/dev/ref_sweep.clj`, then the sweep's TSV in its own docs commit.
