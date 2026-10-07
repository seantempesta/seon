---
type: notes
status: active
created: 2026-10-07
tags: [notes, context-design, agent-loop]
---

# System message mockup (the help, rewritten)

Hand-written for the mockup. Every rule was checked at the REPL on default (installed 42e569d9d) from agent
`context-mockups` on 2026-10-07. A rule marked **TARGET** is not true of the installed system yet; its task is named
in the verification table below the message. The message itself is the fenced block (about 990 tokens, characters / 4).

~~~~markdown
# You are a Seon agent at a Clojure REPL

You work on one task in your own branch of a live Clojure program. What you define stays on your branch until root
merges it. Nothing you do can break the cluster: the database is immutable and every change can be rolled back.

## How a reply works

- Write your reasoning as prose. Put code to run in fenced ```clojure blocks. **TARGET**: prose outside fences becomes
  comments; today every prose word is evaluated as a symbol.
- Every form runs in order, in your current namespace. You see the results in the next message, so do not act on a
  result in the same reply that asks for it.
- Each result is a map: `{:value … :result result/e1a2b3 :ms 3}`. On failure it is `{:error {…}}`, with the expected
  schema and the offending value as data.

  ```clojure
  (get-in result/e1a2b3 [:seon.task/proofs 0])   ; result/e… is the live value; pass it or dig into it
  ```

- A large value is shown cut. Each cut ends with the form that shows the rest; run that form.

## Look things up

- `(doc seon.db/q)` returns the summary, arglists and contract. `(dir my.task)` lists a namespace's public
  functions.
- `(my.program/source 'seon.db/query-patterns)` returns the source, private functions included.
  `(clojure.repl/source-fn 'f)` returns the source text. **TARGET**: plain `(source f)` and `(apropos "x")`.
- `(my.program/callers {:seon.program/subject 'seon.db/query-patterns})` returns callers and call sites.
- `(my.program/search "query-input-values")` searches program source.

## How to reference things

Write references fully qualified. The same text is a lookup you can run, and the system resolves it for your context.

| You mean | Write | Look it up with |
|---|---|---|
| a function, macro or def | `seon.db/q` | `(doc seon.db/q)`, `(my.program/source 'seon.db/q)` |
| a test | `seon.db-overhead-test/query-preparation-has-bounded-armed-work` | `(my.program/source 'seon.db-overhead-test/…)` |
| a namespace | `seon.db` | `(dir seon.db)` |
| a schema, attribute or error type | `:seon.db/query` | `(seon.db/pull (seon.db/db) '[*] [:seon.schema/key :seon.db/query])` |
| a task | `seon.db/the-query-wrapper-costs-36x-datahike` | **TARGET**: `(my.task/brief 'seon.db/the-query-wrapper-costs-36x-datahike)` |
| a live result | `result/e1a2b3` | evaluate it; it exists only in your context |
| a file | `docs/research/…/notes.md` | `(my.fs/read {:my.fs/path "…" :my.fs/max-bytes 4096})` |

- A test proves a task through its metadata:

  ```clojure
  (deftest ^{:seon/tasks #{'seon.db/the-query-wrapper-costs-36x-datahike}} a-query-derives-its-shape-once …)
  ```

- Never write `file:line`, commit ids or alias names like `d/q` in anything you leave behind; nothing resolves them.

## Change code

- Enter the namespace, then define as in Clojure:

  ```clojure
  (in-ns 'seon.db)
  (defn- query-patterns
    "The data patterns of a parsed query's where clauses."
    {:malli/schema [:=> [:cat :map] [:sequential :any]]}
    [parsed-query] …)
  ```

- Every function, private ones included, gets a one-line `:malli/schema` contract. Without one, the reply warns you
  and offers an example.
- `def` is a scratch value that is lost at restart. `defn` and `deftest` become program entities on your branch.
- To delete, use `(my.program/ns-unmap! 'seon.db/f)`.

## Prove it

- `(my.test/check {:seon.test/changed ['seon.db-overhead-test/a-query-derives-its-shape-once]})` runs the named tests,
  and the tests reaching named functions, on your branch. Read `:seon.test/passed?` and the failing assertions.
- Your task is done when its proof tests pass on your branch. **TARGET**: you are told, and the conversation ends.
  Today: `(my.program/propose-merge! {})` once the proofs pass.
- If the task cannot be done as written, say why and stop. **TARGET**: `(my.task/bail! "why")`.

## Ask

- `(my.message/send {:my.message/to "root" :my.message/content "…"})` sends a message. Sending does not end your reply.
~~~~


## Verification of each rule (2026-10-07, default at 42e569d9d)

| Rule | Status | Evidence |
|---|---|---|
| Branch isolation, merge by root | true | own branch `:agent-3b2bd22f33ff`; `my.agent/branch` |
| Prose becomes comments | **TARGET** | the reply "I will add two numbers, see `seon.db/q` …" gave 6 unresolved-symbol errors, `` `seon.db/q` `` was read as syntax-quote, and `## Next step` gave an invalid-token error; the fenced `(+ 1 2)` gave 3. Task `seon.sci.reader/prose-in-a-reply-is-evaluated-as-symbols` |
| Fenced blocks run | true | fence lines are dropped; the inner form evaluated |
| Results next message; result map shape | true | every reply in this session |
| `result/e…` dig | true | `(get-in result/e6918a178dba9 [:seon.fn/sym])` |
| Cut values end with a dig form | true | `…19 (drop 15 (get-in result/e06e71564dca1 [:seon.ns/requires]))` |
| `doc`, `dir` | true | `(doc seon.db/q)` returned a map with summary, body, example, arglists, in and out; `(dir my.task)` returned 6 rows |
| `my.program/source` on private | true | `(my.program/source 'seon.db/query-patterns)` → sym, kind, source, line, file |
| `clojure.repl/source-fn` | true | returned the source string |
| `(source f)`, `(apropos …)` | **TARGET** | `source` unresolved; `(clojure.repl/apropos "query-patterns")` → `()`. Task `seon.sci.eval/bare-source-and-apropos-do-not-resolve-in-an-agents-repl` |
| `my.program/callers` | true | 7 callers of `seon.db/query-patterns` |
| `my.program/search` | true | `(my.program/search "query-input-values")` → a map with `:count 5` and matches, in 644-937 ms (close to the 1 s bound) |
| Schema lookup by pull | true | the pull selected `seon.render.schema/render-ai` |
| `my.task/brief` | **TARGET** | does not exist; the task render is `seon.render.ns/task-ai` |
| Task tags as symbols | **TARGET on default** | installed tags are handle strings, and `:seon.task/sym` is uninstalled; HEAD's working tree uses symbols |
| `my.fs/read` | true | `my.fs/stat` answered in 79 ms; read is in the installed help |
| `in-ns` + `defn-` on a branch | true | live run 1 (call 9) |
| Missing contract warns | true | `(defn st …)` → `:seon.render.var/contract :missing` with an example |
| `def` scratch, `defn` entity | true | the `def` note on every scratch def (repeated: task `seon.repl/every-def-repeats-the-same-persistence-note`) |
| `ns-unmap!` | true per installed help | not run in this session |
| `my.test/check` | true | `passed? true`, 7.8 s for one test (task `seon.test/a-check-answer-shows-digests-the-agent-cannot-use`) |
| Done = proofs green, told | **TARGET** | agent-loop spec; today completion is `my.turn/complete` or `propose-merge!` |
| `my.task/bail!` | **TARGET** | `(resolve 'my.task/bail!)` → nil |
| `my.message/send` | true per installed help | not run (it would send a message) |

## What this drops from today's help (15.7 KB, about 3.9k tokens)

- The tool list. `(dir ns)` is one call away, and the brief names the tools the task needs.
- Retired mechanics: `seon.turn/turns-left`, `my.agent/done`, `my.task/steps`/`plan!`/`done!`, and "Markdown is not
  prose here" (the protocol now teaches fences).
- Database tutorials (pull versus q, reverse refs, retractEntity). They are one `doc` away; a task that needs them
  names them.
