---
title: The read stage, observed (r-read)
type: research
status: observed
date: 2026-10-07
---

# r-read: the read stage, observed (2026-10-07)

Lane `r-read`, research only, no src/test changes. Default runs `722f110df`. The read owners (`src/seon/sci/forgive.clj`, `src/seon/sci/reader.cljc`, `src/seon/sci/batch.clj`, `src/seon/turn.clj`, `src/seon/turn/batch.clj`, `src/seon/mcp.clj`, `src/seon/cluster/agent.clj`) are identical at HEAD `ead9d9a14`. Pinned dependencies: edamame `196ea5e4` (v1.6.42-2), sci `ce0aa53b`.

Health before probing: `bin/seon status` OK, cluster `:faulted` with 17 open faults (top: `seon.batch/evaluate-step` max 43.6 s, `seon.turn/show-row` shown-failure nil fault, task `7f5dfb6ad7a2`). Nothing in this lane needs a restart. **No RESET NEEDED.**

Method: MCP `eval_clj` as agent `r-read`: JVM mode for measurements on default's connection, SCI mode for the production path. Allocation = `ThreadMXBean/getThreadAllocatedBytes` on the calling thread. Armed calls = deltas of `seon.profile` entry counters on the armed Vars. These counters are JVM-wide, and other lanes ran at the same time, so the counts are clean only for large distinctive runs (marked below).

## 1. What reads a reply today (observed)

### Model reply (turn)

1. **Segment** (`seon.turn/planned-sources` turn.clj:3031, called in `freeze!` turn.clj:4446):
   - `segmented-sources` :3009, then `seon.sci.forgive/segments` forgive.clj:337 in `:reply` mode;
   - `forms` :269 loops `next-form` :192 over the text with a bare SCI context (`structural-ctx` :290);
   - each step reads one form with `sci/parse-next` (`read-one` :89), and before that tries edamame directly (`first-form-structural?` :125). If that trial fails it runs `delimiter-repair` :156, which is Parinfer over the rest of the text plus `structural-forms` of the candidate;
   - a prose line is turned into a `; ` comment (`comment-rest-of-line` :143, with `code-suffix` :133 running edamame trials once per `(`/`[`/`{` in the line), and the step restarts on a new text version (`version` :43 builds a new SCI reader and reads it char by char up to the cursor);
   - the result is a vector of rows `{:seon.cluster.eval/source :seon.cluster.eval/comment :seon.ns/name}`. Repairs are computed and then discarded: `segments` drops `forms`' `:repairs`, and the agent is never told what was repaired.
2. **Read each row again** (`seon.turn/read-row` turn.clj:4663, the fold's `:seon.batch/read-next`, turn.clj:4935):
   - each row's source gets a new `forgive/version` and one `forgive/next-form` in `:code` mode, with the agent's live SCI context under `sci/ns` = the run's current namespace;
   - this is the "one semantic read". It reads ONE form per row; anything else in the row is silently ignored (observed: a row `"(+ 1 2) (+ 3 4)"` reads end 7 of 15).
3. **Analyze each form with clj-kondo** (`seon.turn.batch/derive-form` → `analyze-form` turn/batch.clj:222 → `seon.fn/runtime-analysis-batch`). This happens for EVERY form, not only definitions; see §3.
4. **`evaluable-source?`** (turn.clj:2310, `seon.sci.reader/read` with `defer-auto-resolve?`) reads each pending row's source when `next-ordinal` :2332 derives `:resume`/`:close` (`fold-or-close` :2783, `resume-or-generate` :2797, `close-folded-call` :2363). UNVERIFIED at runtime for a model turn: I drove no provider turn. In the MCP SCI path, `seon.sci.reader/read` had 0 calls across my 40-form run, so the MCP path does not reach it.
5. **System turn (refresh)** `latest-evaluations` turn.clj:1794:
   - calls `source-key` :1685 → `source-events` :1679 → `seon.sci.reader/read` twice per stored evaluation of the agent (lookup, then assoc), plus `repl/shown-value`, a read of each evaluation's shown text;
   - measured: `(#'seon.turn/latest-evaluations db "root")` = **1,171 ms, 1.77 GB**, 1,229 kept of 1,388 evaluations (554,868 source chars); `source-key` alone over root's 1,388 sources = 100 ms, 181 MB;
   - generated opening blocks are segmented in `:reply` mode (turn.clj:1777) and then read again by `read-row`.

### MCP `eval_clj`, SCI mode

The path is `seon.mcp/sci-run` mcp.clj:226 → `seon.cluster.agent/evaluate-source!` → `submit-source!` → `segmenter` agent.clj:644 → `seon.turn/code-sources` turn.clj:3047 (`segments` in `:code` mode), then the same turn fold with `read-row`. That is two SCI reads per form plus one clj-kondo parse per form.

Measured on a run of 40 forms `(+ n 1)` (JVM-wide counter deltas, clean):

| What | Calls | Time |
|---|---|---|
| `seon.turn/code-sources` | 1 | 0.46 ms |
| `seon.sci.forgive/segments` | 1 | 0.45 ms |
| `seon.turn/read-row` | 41 | 3.9 ms |
| `seon.sci.forgive/read-one` (SCI `parse-next`) | 84, i.e. 2 per form | |
| `seon.turn.batch/analyze-form` | **40** | **441 ms** |
| `seon.batch/evaluate-batch` | | 667 ms |

`submit-source-in-projection` also sets `:seon.turn/reply-size (count (pr-str sources))` (agent.clj:751): it prints the sources to measure a size, which is a printer use outside the value printer (task `seon.cluster.agent/reply-size-is-measured-by-printing-the-sources`, `28d34ed17824`).

### MCP `eval_clj`, JVM mode

`seon.turn.batch/jvm-request` → `jvm-read-next` turn/batch.clj:843. This is one host `read+string` after `forgive/skip-to`, so fence lines are skipped. One read per form.

### Tests

`seon.test-support/evaluate` and `code-request` (test_support.clj:347, :418) use `seon.turn.batch/code-request`: `forgive/read-state` + `seon.sci.batch/read-next`, ONE live forgiving read over the whole text, no segmentation. No production entrant uses this stage. `seon.turn.batch/turn-request` (the `:reply`-mode twin) and `seon.sci.batch/read-code` have no caller at all. Tests therefore prove a read path that MCP and model replies do not run (task `seon.turn.batch/tests-read-forms-through-a-stage-no-entrant-uses`, `ae7db2e9d173`).

## 2. Every real reply, traced

`planned-sources` = `segments :reply`. Allocation is the calling thread's. Armed calls are `seon.sci.forgive` entry deltas (single thread, small noise).

| Reply | Chars | Segment ms | Allocated | Armed calls | Sources | Unread rest | What the read did |
|---|---|---|---|---|---|---|---|
| live 01 | 2,042 | 7.7 | 5.6 MB | 805 | 24 | – | 24 fenced forms; prose → comments |
| live 02 | 0 | 0.03 | 8 KB | | 0 | – | empty: freeze plans one row `"\n"`; read-row → "The row holds no form." |
| live 03 | 424 | 0.6 | 0.2 MB | | 2 | – | ok |
| live 04 | 120 | 0.9 | 0.3 MB | | 0 | yes | stop-truncated `(defn- reply-map "The entries as one \``: the whole rest becomes one unread source → read error; its prose comment is lost |
| live 05 | 1,420 | 2.4 | 1.4 MB | | 3 | – | ok; long prose → comment |
| live 06 | 1,518 | 10.4 | 8.1 MB | 186 | 3 | – | **`` `reply-map`` and `` ` joins`` (line-start backticks) evaluated as syntax-quote**: they read live as `'user/reply-map` and `'user/joins`; the trailing "turns left: 6 of 12" (an imitation of context text) is dropped |
| live 07 | 1,531 | 2.9 | 1.7 MB | | 1 | – | ok |
| live 08 | 1,352 | 4.2 | 2.1 MB | | 2 | – | ok |
| live 09 | 2,889 | 9.4 | 8.0 MB | 235 | 3 | yes | 3 forms and a truncated `(defn- reply-map …` as the unread source |
| live 10 | 448 | 2.6 | 1.3 MB | | 1 | yes | as 09 |
| live 11 | 247 | 0.6 | 0.3 MB | | 0 | yes | `## What I read …` is the unread rest → read-row: "Invalid token: ##What"; the prose before it is lost |
| loop 1 | 729 | 2.9 | 2.0 MB | 223 | 4 | – | `scratch.add2=>` stripped (defn ran in `user`); the fabricated `#:seon.repl{…}` is a source, refused at read-row; trailing "The fix is complete …" dropped |
| loop 2 | 959 | 1.6 | 1.4 MB | 282 | 6 | – | five `scratch.add2=>` markers stripped; forms run after `(in-ns 'scratch.add2)` |

The `loop 1` read reaches the agent as shown in `loop-pass/runs/00-installed-turn-lp-add-2/call-2-prompt.md:75-80`:
- the defn in `user`;
- `user=> #:seon.repl{…}`, then `{:error "Only the REPL writes responses: this map was not evaluated."}`;
- `#'add-one-adds-one` "Unable to resolve var" in `user`.

The model's closing claim ("The fix is complete") appears nowhere in its history.

## 3. Behavior table (observed, `planned-sources` :reply / `code-sources` :code)

| Input | :reply today | :code today |
|---|---|---|
| prose only `I will add two numbers.` | no-forms error. **The turn then plans the whole reply as one row, so read-row evaluates `I` and drops the rest**; the no-forms message never reaches the agent (task `seon.turn/a-prose-only-reply-evaluates-its-first-word`, `eda2e37d4785`) | 5 symbol forms |
| prose line then form | `(+ 1 2)` with `; prose` comment | every word is a form |
| prose with `(the value)`, apostrophes, `Note:`, numbers, `**bold**`, bullets, `1.` lists | comment, form kept | words as forms; `Note: this works.\n(+ 1 2)` becomes ONE unread source, so the form is lost |
| inline backticks mid-line ``see `seon.db/q` `` | comment | `` `seon.db/q`` syntax-quote form |
| line-start backtick `` `reply-map` joins `` | **two syntax-quote forms** `` `reply-map``, `` ` joins`` | same |
| heading `## What I read` / `# Plan` | heading glued to the next form as ONE source → read error (`Invalid token: ##What` / `Reader tag is not accepted`); the form never runs | same |
| ```` ```clojure ```` fence lines, `~~~` | skipped (`skip-to` forgive.clj:64, both modes) | skipped |
| bare symbol / keyword alone in a fence (`foo`, `:k`, `result/e1a2b3`) | **not code**: "no form" when it is the only thing (the agent cannot dig a result handle alone) | a form |
| same, in a reply that has any line starting with a collection | a form (`structure?` :171) | a form |
| one-word prose line `Done.` in a reply with code | **evaluated as a form** | a form |
| form mid-line `Then call (+ 1 2) to check.` | whole line a comment, nothing runs | 5 forms |
| form ending a line `Then call this: (+ 1 2)` | form kept (`code-suffix`) | `this: (+ 1 2)` unread |
| form starting a line `(+ 1 2) is the answer` | form; the trailing prose becomes a comment line nobody shows | 4 forms |
| `user=> (+ 1 2)`, `scratch.add2=> (defn …)`, also inside a fence | marker stripped, form runs in the **current** namespace (task `seon.sci.forgive/a-prompt-marker-is-stripped-and-its-namespace-ignored`, `c9f69d5aaa31`) | `user=>` is a symbol form |
| a symbol that contains `=>` at line start, `a=>b` | **marker regex strips `a=>`, evaluates `b`** | a form |
| `#:seon.repl{:value 3 …}` or `{:seon.repl/value 3}` at top level | refused at read-row (`:fabricated-response`, forgive.clj:237), not run. **The refused entry has no error value, so `show-row` calls `shown-failure` with nil: a core fault attached to the NEXT form's reply** (reproduced via MCP, below) | same: MCP code cannot write such a map literal at top level |
| `;; => 3` | comment, not flagged | comment |
| bare value line `3` after a form | evaluated (structured reply) | a form |
| unclosed `(defn f [x]\n (inc x)` | Parinfer silently closes it and it runs; the agent is not told | unread → read error |
| extra closer `(+ 1 2))` | Parinfer silently drops `)` | `)` becomes a read error source |
| `#'x`, `#(…)`, `@x`, `#"re"` | read correctly by SCI, but each costs a failed edamame trial plus Parinfer over the rest (§4) | correct, cheap |
| empty reply | no-forms; the turn plans row `"\n"`; read-row: "The row holds no form." | "Your evaluation had no form" |

MCP reproduction of the fabricated-map fault (sci, agent r-read): `(+ 1 2)`, then `#:seon.repl{:value 3}`, then `(* 2 3)`.
- form 2 answered `{:error "Only the REPL writes responses: this map was not evaluated."}`;
- form 3 (`6`) carried `:errors [{… :seon.error/operation seon.sci.eval/shown-failure … "refused argument 0 \`error\`: expected a map, got nil … Called from seon.turn/show-row (turn.clj:4745)"}]`.

The cause:
1. `seon.batch` batch.clj:390 builds the refused entry's error with only `{:seon.batch/refused …}` in ex-data;
2. `show-row` turn.clj:4741 calls `(sci.eval/shown-failure (or (:seon.batch/error-value (ex-data error)) value) …)` with nil.

This is the producer of open fault `seon.turn/show-row` (task `7f5dfb6ad7a2` "faults of show-row") and of task `fe8f23d6095c` `evaluate-answers-nil-for-a-refused-form`. The fault is also delivered on the wrong form, so the agent cannot tell which one caused it.

## 4. Cost of reading a long reply (the largest finding)

Measured: `segments :reply` on the live replies joined (`rr-big`, replies 01, 05-08, loop 1-2), repeated k times. Default JVM, forgive armed.

| Chars | ms | Allocated | Sources |
|---|---|---|---|
| 9,551 | 61 | 219 MB | 43 |
| 19,102 | 297 | 1.57 GB | 86 |
| 28,653 (`planned-sources`) | 541 | **5.1 GB** | 129 |
| 38,204 | 1,411 | 12.0 GB | 172 |
| 76,408 | 4,099 | **93.9 GB** | 344 |

Allocation grows about n^2.9. For comparison, `code-sources` (`:code` mode) on the already-repaired 9.5 KB text takes 2.2 ms and 1.9 MB, and on 76 KB 35.6 ms and 14.9 MB (linear). A DeepSeek reply at its output cap (about 8K tokens, about 30 KB) therefore allocates gigabytes in the read alone, far past the plan's budget (< 5 MB, < 200 ms per pass).

Causes, isolated at the REPL:

1. **The structural trial is wrong for whole classes of forms.**
   - `structural-options` (forgive.clj:108) is passed raw to `edamame.core/parse-next` (forgive.clj:121, :130). Edamame requires options normalized by `normalize-opts` (edamame core.cljc:126-128), and `:all true` is expanded only there (parser.cljc:960-967).
   - `first-form-structural?` is therefore false for `(meta #'x)`, `(map #(inc %) xs)`, `(f @a)`, `(re-find #"a" s)` and `` `(f ~x) ``, with the edamame message "Var literal not allowed. Use the `:var` option". All of these read true with normalized options. SCI's own parse normalizes (sci parser.cljc:44-51), so the live read is unaffected.
2. **Every failed trial runs Parinfer over the rest of the reply.**
   - `next-form`'s `fixed` delay (forgive.clj:216) is forced at every `:reply` step before the read. When the trial fails, for the forms above or for prose tokens that are not valid tokens (`Note:`, `left:`), `delimiter-repair` parses everything from the cursor to the end.
   - Measured: one such step at offset 944 of the 9.5 KB reply allocated 48.7 MB, against 0.3 MB for the read itself. One `left:` step at about 8 KB from the end of a 28.6 KB text allocated 333 MB.
   - The candidate is then refused, because it still contains the same construct, so the work is always thrown away.
   - Reply mode on text with no prose left (no repairs) still costs 50 MB vs 1 MB in code mode at 9.5 KB.
3. **Every repair restarts on a new text version.** `version` (forgive.clj:43) builds a new SCI reader and reads it char by char up to the cursor, and the line index is rebuilt. That is O(n) per repair; the 9.5 KB reply has 57 repairs (49 prose, 8 prompt markers).
   - With `#'`/`#(`/`#"`/`` ` `` removed from the 28.6 KB text, 825 MB remains: `left:`-style prose trials plus the version rebuilds.

Task: `seon.sci.forgive/structural-trials-refuse-var-quote-anonymous-fn-deref-regex-and-syntax-quote` (`56dc5f9cb734`), same class as the existing `seon.sci.forgive/a-prose-repair-replays-the-whole-reply` (`61c1346b6ef2`, which measured 339 MB at 11.9 KB; its evidence path `…/2026-10-07/reader-prose/design-notes.md` does not exist).

`planned-sources` and `code-sources` ignore their `max-source` argument (`_max-source`, turn.clj:3041, :3057). The only bound is per row in `read-row` and `seon.sci.reader/read`, and the configured `max-source` is 1,048,576 chars. Nothing bounds the segmentation of a reply.

Per-form cost in the fold (MCP, 40 forms): read-row about 0.1 ms per form, but clj-kondo about 11 ms per form. That is because:
- publication binds `result/e…` (`sci.eval/bind-result!`);
- the write observer reports that `:bind :root` write;
- `derive-form`'s `finals` (turn/batch.clj:316-321) keeps it, whereas `seon.turn/entry-bindings` (turn.clj:4845) explicitly drops the `result` namespace.

Task: `seon.turn.batch/every-form-is-analyzed-because-its-result-handle-is-a-binding` (`65f8da37f31c`).

Commits and store bytes: the read stage writes nothing itself. The turn stores the raw reply (`stage-reply!`, as a blob when large) and each row's source on its evaluation, so the reply text is stored about twice. UNVERIFIED in bytes: I drove no provider turn.

## 5. The stopped lane's patch (`stopped-wip/reader-prose.patch`), judged by observation

What it does: if a `:reply` holds a fence, it rewrites the text line by line as Markdown (`fenced`):
- only untagged/clojure/clj/cljc/cljs/edn fence bodies stay code;
- every other line becomes `; `;
- the rewritten text is read in a new `:fenced` mode (every form is code, Parinfer still on).

Without a fence, `:reply` gains:
- heading/code-span lines are prose (`markdown-line?`);
- a line opening `ns=>` or `=>` starts an "imitated transcript" refused through the next blank or fence line, in EVERY mode, MCP code included;
- the refusal message text changes.

I ran the patch's pure line functions verbatim (as one `let` in JVM mode, nothing interned), then the installed `forms` in `:code` mode on their output. This approximates `:fenced`, since `:fenced` accepts as `:code` does.
- **Live run** (9 of 11 replies fenced): code is only the fence bodies. Call 06's two backtick syntax-quotes disappear. Calls 04/09/10 keep their truncated defn as the unread source. Call 11 has no fence and goes through the heading rule.
- **Loop-pass replies**: every `scratch.add2=>` line is an imitation, so in call 2 **5 of 6 forms are refused** (only `(in-ns …)` runs), and call 1's defn is refused with its fabricated result. The model writes `ns=>` because the context shows it a `ns=>` transcript. A prompt-line refusal rule plus a REPL-style context refuses most of what such an agent sends.
- It does not touch the cost in §4: `structural-options` is unchanged and the `fixed` trial now runs in `:fenced` too.
- It does not remove the second read: `segments` plus `read-row` stay.
- It does not fix the refused-entry nil fault: an imitation still goes through batch.clj:390, and only the message changed.
- It adds about 130 lines to forgive.clj; its test file adds 3 deftests.

## 6. Facts the plan draft gets wrong

1. "Two readers of a reply (segments, then a re-read in `:reply` mode)":
   - the re-read is `read-row` in `:code` mode with the live context; segmentation is the `:reply` pass;
   - besides these, every form is parsed a third time by clj-kondo (every form, not only defns);
   - a turn's pending rows are read again by `evaluable-source?` (by code; unverified at runtime);
   - each system turn reads the agent's whole history twice through `seon.sci.reader/read` (root: 1.17 s, 1.77 GB).
2. "Its `clojure.test/run-test-var` could not resolve the test var from SCI":
   - in loop-pass the unqualified `#'add-one-adds-one` failed because the stripped `scratch.add2=>` marker left the run in `user`;
   - with a resolvable var, `run-test-var` throws ClassCastException "sci.lang.Namespace cannot be cast to clojure.lang.Symbol" (MCP, agent r-read; task `seon.sci.eval/run-test-var-throws-on-an-sci-test-var`, `9236eba3f99e`).
3. "The reader: fenced code is code; a form that starts a line is code":
   - a bare symbol or keyword alone in a fence is NOT code today unless another line of the reply starts with a collection;
   - one-word prose lines (`Done.`) ARE code when one does.
4. "Text that imitates a result is never evaluated and is flagged back":
   - only a top-level map with `seon.repl`-namespaced keys is flagged, and in every mode, MCP code included;
   - flagging it raises a core fault (`shown-failure` nil) delivered on the next form;
   - `;; => 3` is silently a comment, and a bare `3` after a form is evaluated.
5. The draft assumes the one-read stage must be built. A single-read stage already exists: `turn.batch/code-request` / `turn-request` + `sci.batch/read-next`, used only by tests and with one function uncalled.
6. Segmenting a long reply is the largest read cost (§4), not the eval overhead: 28.6 KB costs 541 ms and 5.1 GB.

## 7. Constraints the plan must respect

- The live read must stay sequential with evaluation. `::alias/k` and syntax-quote resolve against the namespace table that earlier forms built (`read-row` binds `sci/ns` to the run's current namespace; `seon.sci.eval/reader-context`). A reader that reads all forms ahead of evaluation must stay structural (no tags, any alias), as `segments` does today.
- A repair must report itself: today the agent sees neither Parinfer's added or removed delimiters nor stripped markers.
- Prose that is neither before a form nor kept anywhere is lost from the agent's history (task `seon.sci.forgive/prose-after-the-last-form-is-dropped`, `81773872798e`). An exchange-based context needs every line of the reply to land in some exchange.
- Prompt markers are what the model copies from a REPL-shaped context (loop-pass: 7 of 10 forms carried `scratch.add2=>`). Whatever the reader does with them (strip, honor, refuse) decides whether a REPL presentation works at all. Refusing them, as the stopped patch did, refuses most code. Today the markers are stripped and the named namespace is ignored.
- Edamame options must be normalized whenever edamame is called directly (core.cljc:126-128).
- The anti-fabrication check is read-mode independent (forgive.clj:237, reader.cljc:570-579). Moving it to model replies only is existing task `seon.sci.reader/fabrication-refusal-guards-every-reader` (`83b10f8c64d2`).

## 8. Slated for deletion in this area, with callers

| What | Callers |
|---|---|
| `seon.sci.forgive/segments` (structural pre-pass) | `seon.turn/segmented-sources` (turn.clj:3018), reached through `planned-sources` (freeze! :4446, generated blocks :1777, `seon.cluster.agent/segmenter` default agent.clj:652) and `code-sources` (mcp.clj:231) |
| `seon.turn/read-row` and `turn-rows-state` (row re-read) | `evaluate-sources` turn.clj:4934-4935 |
| `seon.turn/evaluable-source?` | `next-ordinal` (turn.clj:2347) ← `fold-or-close`, `resume-or-generate`, `close-folded-call` (D2 run machinery) |
| `seon.turn/source-events` / `source-key` | `latest-evaluations`, `system-plan` area (turn.clj:1813-2205), all D1 |
| `seon.cluster.agent/segmenter` and the `:seon.agent/segmenter` request key | `submit-source-in-projection`, `seon.mcp/sci-run` |
| `seon.sci.batch/read-code` | no caller |
| `seon.turn.batch/turn-request` | no caller |
| the `:prompt-marker` repair | forgive.clj:169, :222 |
| the stopped patch | not landed; `stopped-wip` only |

## 9. Tasks

Created on default with `seon.task/create!`, evidence = this file:

| Id | Task |
|---|---|
| `56dc5f9cb734` | `seon.sci.forgive/structural-trials-refuse-var-quote-anonymous-fn-deref-regex-and-syntax-quote` |
| `65f8da37f31c` | `seon.turn.batch/every-form-is-analyzed-because-its-result-handle-is-a-binding` |
| `81773872798e` | `seon.sci.forgive/prose-after-the-last-form-is-dropped` |
| `eda2e37d4785` | `seon.turn/a-prose-only-reply-evaluates-its-first-word` |
| `9236eba3f99e` | `seon.sci.eval/run-test-var-throws-on-an-sci-test-var` |
| `c9f69d5aaa31` | `seon.sci.forgive/a-prompt-marker-is-stripped-and-its-namespace-ignored` |
| `ae7db2e9d173` | `seon.turn.batch/tests-read-forms-through-a-stage-no-entrant-uses` |
| `28d34ed17824` | `seon.cluster.agent/reply-size-is-measured-by-printing-the-sources` |

Linked existing tasks:

| Id | Task |
|---|---|
| `61c1346b6ef2` | `a-prose-repair-replays-the-whole-reply` |
| `7f5dfb6ad7a2` | faults of show-row |
| `fe8f23d6095c` | `evaluate-answers-nil-for-a-refused-form` |
| `83b10f8c64d2` | `fabrication-refusal-guards-every-reader` |
| | `seon.turn/an-agent-form-is-read-again-by-each-consumer` |
| | `seon.turn/a-markdown-heading-in-a-reply-is-read-as-code` |
| | `seon.sci.reader/prose-in-a-reply-is-evaluated-as-symbols` |
| | `seon.turn/history-acquisition-decodes-stored-read-plans` |

## 10. Implications for the plan

- S2 can start from the existing single-read stage (`code-request`/`sci.batch/read-next`) that tests already use. Deleting `segments` + `read-row` + `segmenter` + `evaluable-source?` makes it the only read, for MCP, model replies and tests.
- Fix the edamame normalization and stop trying Parinfer over the rest of the text before each read. Without that, no read-stage slice can meet the 200 ms / 5 MB budget on a realistic reply.
- The 11 ms per form of clj-kondo on non-defining forms is the largest per-form cost the S0 probe will see. It sits in the record stage, not the reader.
- Decide the prompt-marker rule together with the REPL-vs-DATA presentation experiment (S6). The loop-pass data shows the model copies the markers.
- The no-forms and refused-imitation paths both need their error value at the producer; both currently cause wrong deliveries.

## Verification limits

- No provider turn was driven, so the model-turn path after `freeze!` (`evaluable-source?`, resume/close) is traced by code only.
- Armed-call counts come from JVM-wide counters while other lanes ran: the 40-form figures are clean, the 10-form run was not used.
- The stopped patch was evaluated only through its pure line functions plus the installed `:code` reader, not as a patched `next-form`.
- Store bytes per read: not measured.

## Cleanup

- Disposable agent `r-read`, branch `:agent-7ed2c97121a8`, which held the scratch namespace `rr.scratch`.
  - Self-retire as `r-read` answered `{:seon.error/message "run transition refused: admission-closed"}` and left the branch in the roster. That is existing task `bc5e022a68e6` `retire-branch-refuses-a-finished-agent-as-admission-closed`, reproduced again here.
  - Root's `(my.agent/retire-branch! {:seon.store/branch :agent-7ed2c97121a8})` then answered `:my.agent/retired? true` (31 ms).
- The JVM probe Vars `user/rr-*` in default's JVM: all 28 were unmapped.
