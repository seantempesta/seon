---
title: Law-breaks survey (mechanically detectable AGENTS.md breaches in src and test)
type: research
status: measured in one time-boxed Haiku pass; not reviewed by a second lane; numbers are counts from scripts, classes are by inspection of catch lines and names
basis: HEAD 45bfab18e; default not queried (no eval_clj run); the working tree has uncommitted edits to 16 tracked test files, and scans read the working-tree text of tracked files
method: scratchpad scripts (outside the repo) /private/tmp/claude-501/-Users-sean-src-seon/a5acff01-0859-40f4-bc50-1424bc5205cf/scratchpad/survey/scan.py (per-line scanner that tracks the enclosing top-level def, src and test via git ls-files), classify.py (ordered rule table, first match wins, plus body reads), compose.py and compose2.py (rows), final.py (this file). Counts by rg and grep over the same tree.
---

## For root

1. Ranked dragons (site, count, why):
2. `seon.turn.batch/settle` (src/seon/turn/batch.clj:888,894) and `seon.turn/settle-batch!` (src/seon/turn.clj:3210-3230): bounded retries after a writer refusal; the writer's compare-and-swap should decide. Two sites.
3. `seon.fault/record!` (src/seon/fault.clj:299-301): last-resort recording shape when recording fails; a fallback beside the mechanism.
4. `seon.cluster.agent/segmenter` (agent.clj:683) and `stop-flow!` (agent.clj:1579): silent fallback to the default stage; swallowed starting future.
5. `seon.ai/stream-fold` (ai.clj:800), `seon.web.jvm/search` and `decoded-text` (web/jvm.clj:187-189,432): failures swallowed to nil.
6. `seon.db/q` (db.clj:2977) catches StackOverflowError; `seon.test/attempt-request-context` (test.clj:209) creates a Clojure agent in the runner.
7. `seon.render.value/bounded-text`, `core-text`, `causes` (render/value.clj:125-269): retired word and pr-str inside the one printer, with printer fallbacks.
8. `seon.schema/!fallback-counts` (schema.clj:1016): defonce atom counting fallbacks, a fence-shaped counter.
9. Counts: try/catch 312 sites in 65 src files: E 41, R 16, T 137, C 44, OTHER 74 (58 symbols). Retry-shaped: 2 named settle forms plus 19 catch-with-loop forms. Atoms: 101 lines (59 atom, 37 volatile!, 4 ref allowed, 1 agent). Memoize 7 sites; ConcurrentHashMap 35 lines; pr-str 133 lines (about 30 suspect listed); policy literals about 25 listed. Test: with-redefs 104 code lines in 44 files, alter-var-root 23, intern 28, load-file 9, Thread/sleep 34, timing under 10 ms 6.
10. Already filed: seon.test.edn (with-redefs census, about 300 sites), seon.print.value.edn (printed-copy census, 35 sites, bounded-text), seon.instrument.edn and seon.cluster.edn (test Var redefinitions).
11. Not filed: the retry, fallback and swallow sites above, the 7 memoize sites, policy literals (10000 cleanup, 900 health deadline, 30000 turn time limit, 240000 remote factor). Grep of task names is not exhaustive.

## Table

Catch classes, all 312 sites in src (counts): E entrant boundary 41, R returning tool top catch 16, T named translation 137, C cleanup-and-rethrow 44, OTHER 74 (listed below as category 1).

| Category | Fully qualified symbol | Evidence (path:line) | Why suspect |
|---|---|---|---|
| 1 try/catch OTHER | `seon.ai/interruptible-lines` | src/seon/ai.clj:1031 | provider call catches: body not read; interrupt/exit path is suspect [IOException] |
| 1 try/catch OTHER | `seon.ai/send-request` | src/seon/ai.clj:1250,1271,1290,1295 | provider call catches: body not read; interrupt/exit path is suspect [Exception, HttpTimeoutException, IOException, InterruptedException] |
| 1 try/catch OTHER | `seon.ai/stream-fold` | src/seon/ai.clj:800 | sink failure swallowed to nil [Throwable] |
| 1 try/catch OTHER | `seon.batch/print-into` | src/seon/batch.clj:517 | Throwable to unprintable marker or false (silent fallback) [Throwable] |
| 1 try/catch OTHER | `seon.batch/write?` | src/seon/batch.clj:535 | Throwable to unprintable marker or false (silent fallback) [Throwable] |
| 1 try/catch OTHER | `seon.bootstrap/render-help-html` | src/seon/bootstrap.clj:64 | Exception to 0 (silent) [Exception] |
| 1 try/catch OTHER | `seon.cache/lookup-within` | src/seon/cache.clj:231 | timeout handling body unread [TimeoutException] |
| 1 try/catch OTHER | `seon.cluster.agent/segmenter` | src/seon/cluster/agent.clj:683 | requiring-resolve failure silently falls back to default stage [Exception] |
| 1 try/catch OTHER | `seon.cluster.agent/stop-flow!` | src/seon/cluster/agent.clj:1579 | swallows a failed starting future (try @starting catch nil) then continues [Throwable] |
| 1 try/catch OTHER | `seon.cluster.process/terminate-subprocess!` | src/seon/cluster/process.clj:208,209 | TimeoutException swallowed to nil [ExecutionException, TimeoutException] |
| 1 try/catch OTHER | `seon.cluster.source/proven-schema-file` | src/seon/cluster/source.clj:1444 | read failure to nil [Exception] |
| 1 try/catch OTHER | `seon.cluster.source/same-value?` | src/seon/cluster/source.clj:1338 | equality compare failure to false [Exception] |
| 1 try/catch OTHER | `seon.cluster.store/acquire-flock!` | src/seon/cluster/store.clj:386,392 | OverlappingFileLock branch body unread; lock path [OverlappingFileLockException, Throwable] |
| 1 try/catch OTHER | `seon.cluster/cluster-name?` | src/seon/cluster.clj:145 | predicate catches InvalidPath to false [InvalidPathException] |
| 1 try/catch OTHER | `seon.db/q` | src/seon/db.clj:2970,2977 | StackOverflowError caught: suspect; body unread [ExceptionInfo, StackOverflowError] |
| 1 try/catch OTHER | `seon.error.refusal/basis` | src/seon/error/refusal.clj:25 | read/stacktrace failure to nil [Exception] |
| 1 try/catch OTHER | `seon.error.refusal/interpreted-frames` | src/seon/error/refusal.clj:536 | read/stacktrace failure to nil [Exception] |
| 1 try/catch OTHER | `seon.error/example` | src/seon/error.clj:1821 | ExceptionInfo to nil or leaf fallback in renderer [ExceptionInfo] |
| 1 try/catch OTHER | `seon.error/leaf-expected` | src/seon/error.clj:1796 | ExceptionInfo to nil or leaf fallback in renderer [ExceptionInfo] |
| 1 try/catch OTHER | `seon.error/problem-shown` | src/seon/error.clj:1835 | ExceptionInfo to nil or leaf fallback in renderer [ExceptionInfo] |
| 1 try/catch OTHER | `seon.fault/record!` | src/seon/fault.clj:299,301 | last-resort recording shape when recording fails (fence-like) [Throwable] |
| 1 try/catch OTHER | `seon.fn.analyzer/referenced-program-namespaces` | src/seon/fn/analyzer.clj:909 | parser disagreement to nil or empty context [Throwable] |
| 1 try/catch OTHER | `seon.fn.analyzer/stored-arglists` | src/seon/fn/analyzer.clj:859 | parser disagreement to nil or empty context [Throwable] |
| 1 try/catch OTHER | `seon.fs.jvm/write` | src/seon/fs/jvm.clj:737,748,751 | AtomicMove fallback / NoSuchFile swallowed (body unread) [AtomicMoveNotSupportedException, NoSuchFileException, Throwable] |
| 1 try/catch OTHER | `seon.fs/attributes` | src/seon/fs.clj:289 | NoSuchFile to nil [NoSuchFileException] |
| 1 try/catch OTHER | `seon.fs/delete-recursively-impl!` | src/seon/fs.clj:392 | NoSuchFile to nil [NoSuchFileException] |
| 1 try/catch OTHER | `seon.instrument/example-forms` | src/seon/instrument.clj:290 | Throwable to nil [Throwable] |
| 1 try/catch OTHER | `seon.mcp/printed` | src/seon/mcp.clj:141 | print fallback to core-text: second path when printing fails [Throwable] |
| 1 try/catch OTHER | `seon.print/compare-values` | src/seon/print.cljc:307 | ClassCastException to 0 (fence in comparator) [#?(:clj] |
| 1 try/catch OTHER | `seon.print/generated-item-identity` | src/seon/print.cljc:103 | fallback text for unprintable identity [#?(:clj] |
| 1 try/catch OTHER | `seon.program/namespace-stub?` | src/seon/program.cljc:656 | predicate-style catch to false (body unread) [ExceptionInfo] |
| 1 try/catch OTHER | `seon.ref/link-candidate` | src/seon/ref.clj:94 | parse failure to nil candidate [URISyntaxException] |
| 1 try/catch OTHER | `seon.ref/span-candidate` | src/seon/ref.clj:123,127 | parse failure to nil candidate [Exception, URISyntaxException] |
| 1 try/catch OTHER | `seon.render.data/parse-cursor` | src/seon/render/data.clj:42 | bad cursor to [] [RuntimeException] |
| 1 try/catch OTHER | `seon.render.value/bounded-text` | src/seon/render/value.clj:127 | printer fallback (bounded-text, causes, core-text): inside the one printer, retired-word and fallback suspect [Throwable] |
| 1 try/catch OTHER | `seon.render.value/causes` | src/seon/render/value.clj:138 | printer fallback (bounded-text, causes, core-text): inside the one printer, retired-word and fallback suspect [Throwable] |
| 1 try/catch OTHER | `seon.render.value/core-text` | src/seon/render/value.clj:256,269 | printer fallback (bounded-text, causes, core-text): inside the one printer, retired-word and fallback suspect [Throwable] |
| 1 try/catch OTHER | `seon.render.value/link-lines` | src/seon/render/value.clj:168 | printer fallback (bounded-text, causes, core-text): inside the one printer, retired-word and fallback suspect [Throwable] |
| 1 try/catch OTHER | `seon.render.value/shown-chain` | src/seon/render/value.clj:231 | printer fallback (bounded-text, causes, core-text): inside the one printer, retired-word and fallback suspect [Throwable] |
| 1 try/catch OTHER | `seon.render.web/same-origin?` | src/seon/render/web.clj:2786 | URISyntax caught to nil predicate [URISyntaxException] |
| 1 try/catch OTHER | `seon.repl/def-note` | src/seon/repl.clj:116 | read failure to fallback value or nil [Throwable] |
| 1 try/catch OTHER | `seon.repl/read-shown` | src/seon/repl.clj:60 | read failure to fallback value or nil [Exception] |
| 1 try/catch OTHER | `seon.schema.datahike/reader-round-trips?` | src/seon/schema/datahike.clj:533 | predicate catch to false [RuntimeException] |
| 1 try/catch OTHER | `seon.schema/canonical-reference-graph` | src/seon/schema.clj:809 | predicate-style catch to nil/false [Exception] |
| 1 try/catch OTHER | `seon.schema/malli-form?` | src/seon/schema.clj:1360,1367 | predicate-style catch to nil/false [ExceptionInfo, RuntimeException] |
| 1 try/catch OTHER | `seon.schema/pull-selector?` | src/seon/schema.clj:1405 | predicate-style catch to nil/false [ExceptionInfo] |
| 1 try/catch OTHER | `seon.sci.admit/project` | src/seon/sci/admit.clj:530,546 | rethrow-or-degrade: degrades to a failed node (fallback) [Throwable] |
| 1 try/catch OTHER | `seon.sci.eval/declaration-source-value` | src/seon/sci/eval.clj:1047 | Exception to source fallback [Exception] |
| 1 try/catch OTHER | `seon.sci.forgive/no-value?` | src/seon/sci/forgive.clj:442 | parse failure to nil predicate [Exception] |
| 1 try/catch OTHER | `seon.sci.kernel/on-platform-worker` | src/seon/sci/kernel.clj:480,486 | InterruptedException / ExecutionException body unread [ExecutionException, InterruptedException] |
| 1 try/catch OTHER | `seon.sci.kernel/stacktrace` | src/seon/sci/kernel.clj:855 | stacktrace failure to nil [Exception] |
| 1 try/catch OTHER | `seon.sci.reader/recovering-events` | src/seon/sci/reader.cljc:811 | reader recovery fallback [#?(:clj] |
| 1 try/catch OTHER | `seon.shell.jvm/await-exit` | src/seon/shell/jvm.clj:274 | timeout returns false: body unread [TimeoutException] |
| 1 try/catch OTHER | `seon.shell.jvm/output-descriptor` | src/seon/shell/jvm.clj:312 | charset failure to nil [CharacterCodingException] |
| 1 try/catch OTHER | `seon.turn.batch/schema-notes` | src/seon/turn/batch.clj:318 | returns exception message string as note [ExceptionInfo] |
| 1 try/catch OTHER | `seon.turn.batch/schema-tx` | src/seon/turn/batch.clj:456 | returns exception message string as note [ExceptionInfo] |
| 1 try/catch OTHER | `seon.web.jvm/decoded-text` | src/seon/web/jvm.clj:187,188,189 | admitted-uri returns error value; decoded-text returns nil on charset error (silent) [CharacterCodingException, IllegalCharsetNameException, UnsupportedCharsetException] |
| 1 try/catch OTHER | `seon.web.jvm/search` | src/seon/web/jvm.clj:432 | catch Throwable to nil on search (silent) [Throwable] |
| 2 loop containing catch and recur (check: retry or walk) | `seon.batch/evaluate-batch` | src/seon/batch.clj:933,953 | a loop whose body also catches; most are walks or recovery readers, not re-attempts of the same work |
| 2 loop containing catch and recur (check: retry or walk) | `seon.blob/stage-binary!` | src/seon/blob.clj:329 | a loop whose body also catches; most are walks or recovery readers, not re-attempts of the same work |
| 2 loop containing catch and recur (check: retry or walk) | `seon.bootstrap/render-help-html` | src/seon/bootstrap.clj:64 | a loop whose body also catches; most are walks or recovery readers, not re-attempts of the same work |
| 2 loop containing catch and recur (check: retry or walk) | `seon.db/decode-query-field` | src/seon/db.clj:2442 | a loop whose body also catches; most are walks or recovery readers, not re-attempts of the same work |
| 2 loop containing catch and recur (check: retry or walk) | `seon.db/write-owned-values-error` | src/seon/db.clj:4663 | a loop whose body also catches; most are walks or recovery readers, not re-attempts of the same work |
| 2 loop containing catch and recur (check: retry or walk) | `seon.print/compare-values` | src/seon/print.cljc:307 | a loop whose body also catches; most are walks or recovery readers, not re-attempts of the same work |
| 2 loop containing catch and recur (check: retry or walk) | `seon.program/namespace-stub?` | src/seon/program.cljc:656 | a loop whose body also catches; most are walks or recovery readers, not re-attempts of the same work |
| 2 loop containing catch and recur (check: retry or walk) | `seon.render.value/causes` | src/seon/render/value.clj:138 | a loop whose body also catches; most are walks or recovery readers, not re-attempts of the same work |
| 2 loop containing catch and recur (check: retry or walk) | `seon.render.web/feed` | src/seon/render/web.clj:2716 | a loop whose body also catches; most are walks or recovery readers, not re-attempts of the same work |
| 2 loop containing catch and recur (check: retry or walk) | `seon.sci.admit/project` | src/seon/sci/admit.clj:530,546 | a loop whose body also catches; most are walks or recovery readers, not re-attempts of the same work |
| 2 loop containing catch and recur (check: retry or walk) | `seon.sci.forgive/step` | src/seon/sci/forgive.clj:364 | a loop whose body also catches; most are walks or recovery readers, not re-attempts of the same work |
| 2 loop containing catch and recur (check: retry or walk) | `seon.sci.kernel/on-platform-worker` | src/seon/sci/kernel.clj:480,486 | a loop whose body also catches; most are walks or recovery readers, not re-attempts of the same work |
| 2 loop containing catch and recur (check: retry or walk) | `seon.sci.reader/read-events` | src/seon/sci/reader.cljc:522 | a loop whose body also catches; most are walks or recovery readers, not re-attempts of the same work |
| 2 loop containing catch and recur (check: retry or walk) | `seon.sci.reader/recovering-events` | src/seon/sci/reader.cljc:811 | a loop whose body also catches; most are walks or recovery readers, not re-attempts of the same work |
| 2 loop containing catch and recur (check: retry or walk) | `seon.shell.jvm/await-exit` | src/seon/shell/jvm.clj:274 | a loop whose body also catches; most are walks or recovery readers, not re-attempts of the same work |
| 2 loop containing catch and recur (check: retry or walk) | `seon.test.runner/tests-reaching` | src/seon/test/runner.clj:1360 | a loop whose body also catches; most are walks or recovery readers, not re-attempts of the same work |
| 2 loop containing catch and recur (check: retry or walk) | `seon.test/run-request` | src/seon/test.clj:2864 | a loop whose body also catches; most are walks or recovery readers, not re-attempts of the same work |
| 2 loop containing catch and recur (check: retry or walk) | `seon.turn/arm-turn-completion-backstop!` | src/seon/turn.clj:5280 | a loop whose body also catches; most are walks or recovery readers, not re-attempts of the same work |
| 2 loop containing catch and recur (check: retry or walk) | `seon.web.jvm/fetch` | src/seon/web/jvm.clj:353 | a loop whose body also catches; most are walks or recovery readers, not re-attempts of the same work |
| 2 retry | `seon.ai/attempt-ai, attempt-html` | src/seon/ai.clj:96-110 | named attempt wrappers around rendering without private provider data; retry-shaped, body unread |
| 2 retry | `seon.turn.batch/settle` | src/seon/turn/batch.clj:888,894 | on a writer refusal (head moved) rebases and attempts once more: a bounded retry; CAS is the writer's job |
| 2 retry | `seon.turn/settle-batch!` | src/seon/turn.clj:3210,3230 | a refused settlement revalidates and calls attempt again: second settle pass after refusal |
| 3 atom/volatile/ref/agent | `seon.batch/evaluate-batch` | src/seon/batch.clj:940 | 1 volatile!; local volatile! accumulation: a fold candidate (law: per-call accumulation is a fold) |
| 3 atom/volatile/ref/agent | `seon.batch/evaluate-step` | src/seon/batch.clj:601,602,604 | 3 volatile!; local volatile! accumulation: a fold candidate (law: per-call accumulation is a fold) |
| 3 atom/volatile/ref/agent | `seon.batch/form-world` | src/seon/batch.clj:552 | 1 atom; atom outside the allowed cells; justify in plain words or fold into the owning state map |
| 3 atom/volatile/ref/agent | `seon.cluster.agent/acquire-context!` | src/seon/cluster/agent.clj:1155 | 1 atom; atom outside the allowed cells; justify in plain words or fold into the owning state map |
| 3 atom/volatile/ref/agent | `seon.cluster.agent/graph-definition` | src/seon/cluster/agent.clj:626 | 1 atom; atom outside the allowed cells; justify in plain words or fold into the owning state map |
| 3 atom/volatile/ref/agent | `seon.cluster.agent/routing` | src/seon/cluster/agent.clj:651 | 1 atom; atom outside the allowed cells; justify in plain words or fold into the owning state map |
| 3 atom/volatile/ref/agent | `seon.cluster.agent/start-claimed-flow!` | src/seon/cluster/agent.clj:1366 | 1 atom; atom outside the allowed cells; justify in plain words or fold into the owning state map |
| 3 atom/volatile/ref/agent | `seon.cluster.agent/wake-channel` | src/seon/cluster/agent.clj:141 | 1 atom; atom outside the allowed cells; justify in plain words or fold into the owning state map |
| 3 atom/volatile/ref/agent | `seon.cluster.registry/collect-and-inventory!` | src/seon/cluster/registry.clj:700,701 | 2 atom; atom outside the allowed cells; justify in plain words or fold into the owning state map |
| 3 atom/volatile/ref/agent | `seon.cluster.wake/route!` | src/seon/cluster/wake.clj:537,538 | 2 volatile!; local volatile! accumulation: a fold candidate (law: per-call accumulation is a fold) |
| 3 atom/volatile/ref/agent | `seon.cluster/start-flows!` | src/seon/cluster.clj:3454,3455,3459 | 3 atom; atom outside the allowed cells; justify in plain words or fold into the owning state map |
| 3 atom/volatile/ref/agent | `seon.db/write-owned-values-error` | src/seon/db.clj:4442 | 1 volatile!; local volatile! accumulation: a fold candidate (law: per-call accumulation is a fold) |
| 3 atom/volatile/ref/agent | `seon.env/environment-state` | src/seon/env.clj:104 | 1 atom; atom outside the allowed cells; justify in plain words or fold into the owning state map |
| 3 atom/volatile/ref/agent | `seon.env/environment-state-generator` | src/seon/env.clj:71 | 1 atom; atom outside the allowed cells; justify in plain words or fold into the owning state map |
| 3 atom/volatile/ref/agent | `seon.flow/atom-reference-generator` | src/seon/flow.clj:200 | 1 atom; atom outside the allowed cells; justify in plain words or fold into the owning state map |
| 3 atom/volatile/ref/agent | `seon.flow/start-work-launcher!` | src/seon/flow.clj:722,723,724 | 3 atom; atom outside the allowed cells; justify in plain words or fold into the owning state map |
| 3 atom/volatile/ref/agent | `seon.flow/submit!` | src/seon/flow.clj:841,842,843 | 3 atom; atom outside the allowed cells; justify in plain words or fold into the owning state map |
| 3 atom/volatile/ref/agent | `seon.flow/var-process` | src/seon/flow.clj:447 | 1 atom; atom outside the allowed cells; justify in plain words or fold into the owning state map |
| 3 atom/volatile/ref/agent | `seon.fn.analyzer/parsed-facts` | src/seon/fn/analyzer.clj:631 | 1 atom; atom outside the allowed cells; justify in plain words or fold into the owning state map |
| 3 atom/volatile/ref/agent | `seon.fn/compile-index-transaction` | src/seon/fn.clj:3614 | 1 volatile!; local volatile! accumulation: a fold candidate (law: per-call accumulation is a fold) |
| 3 atom/volatile/ref/agent | `seon.health/derived` | src/seon/health.clj:117,121 | 1 volatile!, 1 atom; atom outside the allowed cells; justify in plain words or fold into the owning state map |
| 3 atom/volatile/ref/agent | `seon.render.walk/acquire-entity` | src/seon/render/walk.clj:404 | 1 atom; atom outside the allowed cells; justify in plain words or fold into the owning state map |
| 3 atom/volatile/ref/agent | `seon.render.web/acquire-debug-data` | src/seon/render/web.clj:1524 | 1 atom; atom outside the allowed cells; justify in plain words or fold into the owning state map |
| 3 atom/volatile/ref/agent | `seon.render.web/acquire-root` | src/seon/render/web.clj:1900 | 1 atom; atom outside the allowed cells; justify in plain words or fold into the owning state map |
| 3 atom/volatile/ref/agent | `seon.render.web/debug-page-result` | src/seon/render/web.clj:1669 | 1 atom; atom outside the allowed cells; justify in plain words or fold into the owning state map |
| 3 atom/volatile/ref/agent | `seon.render.web/debug-render-experiment` | src/seon/render/web.clj:1424 | 1 atom; atom outside the allowed cells; justify in plain words or fold into the owning state map |
| 3 atom/volatile/ref/agent | `seon.render.web/derive-page` | src/seon/render/web.clj:2015 | 1 atom; atom outside the allowed cells; justify in plain words or fold into the owning state map |
| 3 atom/volatile/ref/agent | `seon.render.web/feed` | src/seon/render/web.clj:2664 | 1 volatile!; local volatile! accumulation: a fold candidate (law: per-call accumulation is a fold) |
| 3 atom/volatile/ref/agent | `seon.render.web/page-result` | src/seon/render/web.clj:486,487 | 2 atom; atom outside the allowed cells; justify in plain words or fold into the owning state map |
| 3 atom/volatile/ref/agent | `seon.render.web/render-source-call` | src/seon/render/web.clj:1345 | 1 atom; atom outside the allowed cells; justify in plain words or fold into the owning state map |
| 3 atom/volatile/ref/agent | `seon.render/render-call` | src/seon/render.clj:1574 | 1 atom; atom outside the allowed cells; justify in plain words or fold into the owning state map |
| 3 atom/volatile/ref/agent | `seon.render/shared-cache` | src/seon/render.clj:1701,1707 | 2 atom; atom outside the allowed cells; justify in plain words or fold into the owning state map |
| 3 atom/volatile/ref/agent | `seon.schema/!fallback-counts` | src/seon/schema.clj:1016 | 1 atom; atom outside the allowed cells; justify in plain words or fold into the owning state map |
| 3 atom/volatile/ref/agent | `seon.schema/delta-over` | src/seon/schema.clj:3791 | 1 atom; atom outside the allowed cells; justify in plain words or fold into the owning state map |
| 3 atom/volatile/ref/agent | `seon.sci.admit/admit-walk` | src/seon/sci/admit.clj:781 | 1 volatile!; local volatile! accumulation: a fold candidate (law: per-call accumulation is a fold) |
| 3 atom/volatile/ref/agent | `seon.sci.eval/build-base-ctx` | src/seon/sci/eval.clj:450,451 | 2 atom; atom outside the allowed cells; justify in plain words or fold into the owning state map |
| 3 atom/volatile/ref/agent | `seon.sci.eval/call-detecting-program-change` | src/seon/sci/eval.clj:1203 | 1 volatile!; local volatile! accumulation: a fold candidate (law: per-call accumulation is a fold) |
| 3 atom/volatile/ref/agent | `seon.sci.eval/copy-base-ctx` | src/seon/sci/eval.clj:3677,3679 | 2 atom; atom outside the allowed cells; justify in plain words or fold into the owning state map |
| 3 atom/volatile/ref/agent | `seon.sci.eval/evaluate` | src/seon/sci/eval.clj:4748,4759,4794,4801,4812,4813,4816,4875 | 5 volatile!, 3 atom; atom outside the allowed cells; justify in plain words or fold into the owning state map |
| 3 atom/volatile/ref/agent | `seon.sci.eval/fork-cluster-ctx` | src/seon/sci/eval.clj:4162,4164 | 2 atom; atom outside the allowed cells; justify in plain words or fold into the owning state map |
| 3 atom/volatile/ref/agent | `seon.sci.eval/fork-for-turn` | src/seon/sci/eval.clj:3527,3529 | 2 atom; atom outside the allowed cells; justify in plain words or fold into the owning state map |
| 3 atom/volatile/ref/agent | `seon.sci.eval/forked-program` | src/seon/sci/eval.clj:3291,3293 | 2 atom; atom outside the allowed cells; justify in plain words or fold into the owning state map |
| 3 atom/volatile/ref/agent | `seon.sci.eval/loaded-identity` | src/seon/sci/eval.clj:1153 | 1 atom; atom outside the allowed cells; justify in plain words or fold into the owning state map |
| 3 atom/volatile/ref/agent | `seon.sci.eval/regenerate-agent-context!` | src/seon/sci/eval.clj:3404,3408 | 2 atom; atom outside the allowed cells; justify in plain words or fold into the owning state map |
| 3 atom/volatile/ref/agent | `seon.sci.forgive/scan` | src/seon/sci/forgive.clj:158,159,160,161,163,167 | 6 volatile!; local volatile! accumulation: a fold candidate (law: per-call accumulation is a fold) |
| 3 atom/volatile/ref/agent | `seon.sci.kernel/invoke` | src/seon/sci/kernel.clj:997 | 1 volatile!; local volatile! accumulation: a fold candidate (law: per-call accumulation is a fold) |
| 3 atom/volatile/ref/agent | `seon.sci.kernel/memory-pressure-state` | src/seon/sci/kernel.clj:121 | 1 volatile!; local volatile! accumulation: a fold candidate (law: per-call accumulation is a fold) |
| 3 atom/volatile/ref/agent | `seon.sci.kernel/new-armed` | src/seon/sci/kernel.clj:353,356 | 1 atom, 1 volatile!; atom outside the allowed cells; justify in plain words or fold into the owning state map |
| 3 atom/volatile/ref/agent | `seon.sci.lookup/agent-run-test-var` | src/seon/sci/lookup.clj:73 | 1 ref; clojure.test report counter (allowed cell) |
| 3 atom/volatile/ref/agent | `seon.sci.lookup/agent-test-ns` | src/seon/sci/lookup.clj:102 | 1 ref; clojure.test report counter (allowed cell) |
| 3 atom/volatile/ref/agent | `seon.shell.jvm/execute` | src/seon/shell/jvm.clj:378 | 1 volatile!; local volatile! accumulation: a fold candidate (law: per-call accumulation is a fold) |
| 3 atom/volatile/ref/agent | `seon.test.runner/prepare-failures!` | src/seon/test/runner.clj:1606 | 1 volatile!; local volatile! accumulation: a fold candidate (law: per-call accumulation is a fold) |
| 3 atom/volatile/ref/agent | `seon.test.runner/run-selected-tests` | src/seon/test/runner.clj:477 | 1 ref; clojure.test report counter (allowed cell) |
| 3 atom/volatile/ref/agent | `seon.test.runner/run-vars!` | src/seon/test/runner.clj:410,411,414 | 2 atom, 1 ref; atom outside the allowed cells; justify in plain words or fold into the owning state map |
| 3 atom/volatile/ref/agent | `seon.test/attempt-request-context` | src/seon/test.clj:209,225 | 1 agent, 1 atom; Clojure agent as test-runner state: not one of the allowed cells |
| 3 atom/volatile/ref/agent | `seon.test/bounded-result` | src/seon/test.clj:277 | 1 volatile!; local volatile! accumulation: a fold candidate (law: per-call accumulation is a fold) |
| 3 atom/volatile/ref/agent | `seon.test/member-result` | src/seon/test.clj:2353,2354,2373,2382,2384 | 5 volatile!; local volatile! accumulation: a fold candidate (law: per-call accumulation is a fold) |
| 3 atom/volatile/ref/agent | `seon.test/run-request` | src/seon/test.clj:2779,2799 | 1 volatile!, 1 atom; atom outside the allowed cells; justify in plain words or fold into the owning state map |
| 3 atom/volatile/ref/agent | `seon.turn/await-turn-permit!` | src/seon/turn.clj:5144 | 1 atom; atom outside the allowed cells; justify in plain words or fold into the owning state map |
| 3 atom/volatile/ref/agent | `seon.turn/evaluate-sources` | src/seon/turn.clj:4529 | 1 volatile!; local volatile! accumulation: a fold candidate (law: per-call accumulation is a fold) |
| 3 atom/volatile/ref/agent | `seon.web.jvm/bounded-input` | src/seon/web/jvm.clj:130 | 1 volatile!; local volatile! accumulation: a fold candidate (law: per-call accumulation is a fold) |
| 4 ad-hoc map cache / CHM | `seon.batch/sink` | src/seon/batch.clj:100,105,145,171 | ConcurrentHashMap sink for per-batch counters; check it is the profile sink, not a cache |
| 4 ad-hoc map cache / CHM | `seon.fault/folded` | src/seon/fault.clj:89 | defonce ConcurrentHashMap keyed by [branch occurrence]: process-wide counter map beside the store; fold candidate |
| 4 ad-hoc map cache / CHM | `seon.profile/cells-by-key` | src/seon/profile.clj:124-149 | ConcurrentHashMap of profile cells: the allowed profile counters; listed for completeness, not a suspect |
| 4 ad-hoc map cache / CHM | `seon.schema/!fallback-counts` | src/seon/schema.clj:1016 | defonce atom {} of fallback counts: a counter of a fallback path (fence-shaped) |
| 4 ad-hoc map cache / CHM | `seon.sci.kernel/pending-work` | src/seon/sci/kernel.clj:709,720,729,731 | process-wide ConcurrentHashMap of pending evaluation ids: a registry outside the admitted registries |
| 4 cache note | `seon.cache (Caffeine)` | src/seon/cache.clj:60-62 | the one allowed cache; not suspect |
| 4 memoize | `seon.db/write-digest-error` | src/seon/db.clj:5604 | memoize of declaration-value per call; derived state |
| 4 memoize | `seon.db/write-owned-values-error` | src/seon/db.clj:4498 | memoize inside an error builder: derived state held in a closure |
| 4 memoize | `seon.fn.analyzer/roots-of` | src/seon/fn/analyzer.clj:259 | memoize of manifest-roots: process-lifetime cache with no bound |
| 4 memoize | `seon.fn/compile-index-transaction` | src/seon/fn.clj:3590 | memoize of index transaction builder |
| 4 memoize | `seon.fn/reconcile-tx-in` | src/seon/fn.clj:3765 | memoize of db/pull by entity id: an ad-hoc cache over database reads |
| 4 memoize | `seon.test.runner/record-latest-tx` | src/seon/test/runner.clj:1925 | memoize of a database pull by path |
| 4 memoize | `seon.test/admit-run` | src/seon/test.clj:1550 | memoize of scope-of inside admission |
| 5 printer (suspect; pr-str total 133 lines / 89 syms in src) | `seon.batch/print-into, write?, generated-item-identity (print.cljc)` | src/seon/batch.clj:517,535; src/seon/print.cljc:103 | Throwable fallbacks to an unprintable marker: printer fallbacks outside the one printer |
| 5 printer (suspect; pr-str total 133 lines / 89 syms in src) | `seon.cluster.message/format-ai, delivery, data-link, read` | src/seon/cluster/message.clj:162-316,716 | pr-str of refs and ids into messages or links |
| 5 printer (suspect; pr-str total 133 lines / 89 syms in src) | `seon.db/unknown-attribute-error, lookup-ref-error, foreign-connection-error` | src/seon/db.clj:557-562,2146,2187 | pr-str of attributes and branches into error message text |
| 5 printer (suspect; pr-str total 133 lines / 89 syms in src) | `seon.db/write-digest-error` | src/seon/db.clj:5621 | pr-str of a digest-bearing value into message text |
| 5 printer (suspect; pr-str total 133 lines / 89 syms in src) | `seon.error/evidence-path, render-html` | src/seon/error.clj:1778,2396,2413 | pr-str of references into href or entity strings |
| 5 printer (suspect; pr-str total 133 lines / 89 syms in src) | `seon.error/evidence-text` | src/seon/error.clj:2591 | pr-str of evidence values into text |
| 5 printer (suspect; pr-str total 133 lines / 89 syms in src) | `seon.error/explain-problem, problem-parts, scalar-text` | src/seon/error.clj:907-982 | pr-str of keys and values into error message text; messages should be fixed text with values as data keys |
| 5 printer (suspect; pr-str total 133 lines / 89 syms in src) | `seon.error/fit-fact-payload` | src/seon/error.clj:468,489 | pr-str of a fact to size it: printing to measure instead of byte counts of stored data |
| 5 printer (suspect; pr-str total 133 lines / 89 syms in src) | `seon.fn/manifest-data, reconciled-paths, analyze-forms` | src/seon/fn.clj:1325,2784,4101 | pr-str of paths and manifests into text |
| 5 printer (suspect; pr-str total 133 lines / 89 syms in src) | `seon.fs/write-edn-atomically!` | src/seon/fs.clj:25 | pr-str to EDN file: a serializer for a file write; listed, not counted as a printer |
| 5 printer (suspect; pr-str total 133 lines / 89 syms in src) | `seon.mcp/printed` | src/seon/mcp.clj:141 | print failure falls back to core-text: second path |
| 5 printer (suspect; pr-str total 133 lines / 89 syms in src) | `seon.program/binding-entity, key-kind, typed-key-facts, label-facts, arity-entity, component-id, map-binding-entries` | src/seon/program.cljc:945,1026,1051,1060,1145-1171,1247,1296 | pr-str of forms, keys and arities stored as facts: printed copies of data kept as stored text |
| 5 printer (suspect; pr-str total 133 lines / 89 syms in src) | `seon.render.transcript/message-text, turn-header, runtime-message-link` | src/seon/render/transcript.clj:359,719,937 | pr-str into prose and HTML |
| 5 printer (suspect; pr-str total 133 lines / 89 syms in src) | `seon.render.value/bounded-text` | src/seon/render/value.clj:125,127 | retired word named by the law, pr-str inside the printer: a second printer under another name |
| 5 printer (suspect; pr-str total 133 lines / 89 syms in src) | `seon.render.web/debug-header-html` | src/seon/render/web.clj:759,767 | pr-str of values into HTML instead of the printer's HTML face |
| 5 printer (suspect; pr-str total 133 lines / 89 syms in src) | `seon.render.web/debug-query-strings` | src/seon/render/web.clj:274-293 | pr-str of debug values into query strings: printed copies in URLs |
| 5 printer (suspect; pr-str total 133 lines / 89 syms in src) | `seon.render.web/debug-response` | src/seon/render/web.clj:3142-3148 | pr-str of viewer, output and database identity into HTML |
| 5 printer (suspect; pr-str total 133 lines / 89 syms in src) | `seon.render.web/graph-node-id, graph-model` | src/seon/render/web.clj:818,835 | pr-str of identity as node id: printed text used as identity |
| 5 printer (suspect; pr-str total 133 lines / 89 syms in src) | `seon.schema/canonical-schema-entities` | src/seon/schema.clj:3903 | binding of print options then pr-str of entities: printed text used for identity or compare (inferred; body unread) |
| 5 printer (suspect; pr-str total 133 lines / 89 syms in src) | `seon.schema/compilable-form, bind-contract-predicates, render-contract-refusal!` | src/seon/schema.clj:368,1974,4263 | pr-str of predicates and inputs into message text |
| 5 printer (suspect; pr-str total 133 lines / 89 syms in src) | `seon.sci.eval/failure-text` | src/seon/sci/eval.clj:4249 | pr-str of a message into failure text |
| 5 printer (suspect; pr-str total 133 lines / 89 syms in src) | `seon.sci.reader/function-declaration, schema-declaration` | src/seon/sci/reader.cljc:289,302,340 | pr-str of arglists and schemas stored as :seon.fn/spec text |
| 5 printer (suspect; pr-str total 133 lines / 89 syms in src) | `seon.turn.batch/write-entity, schema-tx` | src/seon/turn/batch.clj:293,468 | pr-str of contracts and forms stored as text in facts |
| 5 printer (suspect; pr-str total 133 lines / 89 syms in src) | `seon.turn/proposal-text, settle-batch-refusal!` | src/seon/turn.clj:3403-3407,3440 | pr-str of proposals into refusal message text; binding of print-length nil for it |
| 5 printer (suspect; pr-str total 133 lines / 89 syms in src) | `seon.turn/record-attempt!` | src/seon/turn.clj:3833,3835 | pr-str of settings and usage stored as EDN text (the printed-copy class in seon.print.value.edn) |
| 5 printer (suspect; pr-str total 133 lines / 89 syms in src) | `seon.turn/turn-completion-error, write-refusal-error, dial-absent, episode-exhausted!` | src/seon/turn.clj:4907,5043-5045,5096-5097,5181-5182 | pr-str of ids into message text |
| 6 hardcoded policy number (code literal >= 100; small literals not scanned) | `seon.cache/entry-bytes; seon.cache/lookup weight` | src/seon/cache.clj:119,174 | hardcoded byte weights (256; 3700 per form) not from a dial |
| 6 hardcoded policy number (code literal >= 100; small literals not scanned) | `seon.cluster.process/subprocess-cleanup-ms` | src/seon/cluster/process.clj:111 | timeout literal 10000 ms |
| 6 hardcoded policy number (code literal >= 100; small literals not scanned) | `seon.cluster.source/compare-branch` | src/seon/cluster/source.clj:850 | bound :seon.program/max-datoms 10000 hardcoded |
| 6 hardcoded policy number (code literal >= 100; small literals not scanned) | `seon.cluster/stored-derivation-schema-keys, derivation-world-weight, derivation-keys` | src/seon/cluster.clj:1645,1655,1730 | hardcoded weights 384+64n, 160+64n, 512+64n |
| 6 hardcoded policy number (code literal >= 100; small literals not scanned) | `seon.eval.drive/run-sample!` | src/seon/eval/drive.clj:351 | remote timeout derived as run-cap times 240000 ms: hardcoded factor |
| 6 hardcoded policy number (code literal >= 100; small literals not scanned) | `seon.fn/population-targets-weight` | src/seon/fn.clj:1467 | weights 48 and 128 |
| 6 hardcoded policy number (code literal >= 100; small literals not scanned) | `seon.health/report` | src/seon/health.clj:551,583,588 | default deadline-ms 900 hardcoded |
| 6 hardcoded policy number (code literal >= 100; small literals not scanned) | `seon.health/weight` | src/seon/health.clj:93 | weight 512 per value |
| 6 hardcoded policy number (code literal >= 100; small literals not scanned) | `seon.id/structural-digest` | src/seon/id.clj:139 | ByteBuffer 8192 |
| 6 hardcoded policy number (code literal >= 100; small literals not scanned) | `seon.program/shape-bytes-per-family; seon.program/namespace-stub?` | src/seon/program.cljc:391,677-678 | bytes per family 40600; :limit 200; :max-result-weight 10000000 |
| 6 hardcoded policy number (code literal >= 100; small literals not scanned) | `seon.render.value/text-limits` | src/seon/render/value.clj:88 | printer budget literal map: chars 400, items 8, depth 3, entries 12, throwable-chars 8192; the one budget is not a dial |
| 6 hardcoded policy number (code literal >= 100; small literals not scanned) | `seon.render.web/debug-defaults, debug-query` | src/seon/render/web.clj:221-246 | debug max-result-weight 4000, pull-max-work 4000, limit 200 |
| 6 hardcoded policy number (code literal >= 100; small literals not scanned) | `seon.render.web/render-step` | src/seon/render/web.clj:2492 | Thread/sleep of a computed remainder (frame pacing); the period source is unverified |
| 6 hardcoded policy number (code literal >= 100; small literals not scanned) | `seon.schema.edn/population-bytes-per-form` | src/seon/schema/edn.clj:399 | 376 bytes per form |
| 6 hardcoded policy number (code literal >= 100; small literals not scanned) | `seon.schema/declaration-projection-bytes-per-form, projection-bytes-per-form, projection-bytes-per-contract` | src/seon/schema.clj:1254-1256 | measured byte constants 2550 / 2850 / 4050 hardcoded |
| 6 hardcoded policy number (code literal >= 100; small literals not scanned) | `seon.sci.eval/loaded-digests; seon.sci.eval/overlay-plan` | src/seon/sci/eval.clj:1337,2944-2945 | weights 256 and 128 per item |
| 6 hardcoded policy number (code literal >= 100; small literals not scanned) | `seon.sci.kernel/new-guard` | src/seon/sci/kernel.clj:83 | sampling mask 1023 hardcoded |
| 6 hardcoded policy number (code literal >= 100; small literals not scanned) | `seon.shell.jvm / seon.web.jvm io-buffer-bytes` | src/seon/shell/jvm.clj:25; src/seon/web/jvm.clj:29 | buffer 65536 bytes |
| 6 hardcoded policy number (code literal >= 100; small literals not scanned) | `seon.task/task-basis-bytes-per-task, tasks-tx-in, tasks-tx-weight` | src/seon/task.clj:169,319,333 | weights 512 and 256 |
| 6 hardcoded policy number (code literal >= 100; small literals not scanned) | `seon.test.runner/reach-entries` | src/seon/test/runner.clj:1231,1245 | weight 160 per node |
| 6 hardcoded policy number (code literal >= 100; small literals not scanned) | `seon.test/changes; seon.test/declared-bound-ms` | src/seon/test.clj:2150,2478 | weights 256 and 128; default test bound 5000 ms (the law's default, but a literal) |
| 6 hardcoded policy number (code literal >= 100; small literals not scanned) | `seon.turn.batch/show, jvm-show` | src/seon/turn/batch.clj:917,922,1049 | default time limit (or limit 30000) ms hardcoded |
| 7 test Thread/sleep | `34 lines, 30 symbols, 21 files` | test/seon/turn_continue_test.clj:121,123 (sleep 10000); cluster/subprocess_test.clj:147,176; operator_test.clj:121,127,215,220; armed_calls_test.clj:17 (1100 ms); ai_test.clj:1214 | sleep-based timing and polling in tests; the law says a test driver waits for what production waits for |
| 7 test alter-var-root | `23 code lines, 17 symbols, 11 files` | test/seon/cluster/agent_test.clj:1583,1601; instrument_test.clj:1423,1551; profile_test.clj:29,31,94; sci/eval_test.clj:541,608,627,628,723,729,2903; test_reaching_test.clj:303 | root redefinition; several restore via mi/-f->original |
| 7 test intern | `28 lines, 24 symbols, 11 files` | test/seon/instrument_test.clj (13 sites); cluster/program_change_test.clj:42; test/one_request_test.clj:162; test_runner_test.clj:293,355,356 | interns Vars into live namespaces; test helper creation, but still a Var write |
| 7 test load-file | `9 lines, 9 symbols, 8 files` | test/seon/cluster/publication_host_test.clj:81; dev/hook_test.clj:15,86; dev/edit_feedback_test.clj:392; cluster/publication_adoption_test.clj:26 | loads bin/seon-hook into the JVM; a test loading source into the platform |
| 7 test timing (above 10 ms, noted) | `db_flow_test.clj:822 (<30 ms); agent_test.clj:2147 (<25); db_flow_test.clj:604 (<50)` | various | close to the 10 ms floor; listed for root |
| 7 test timing < 10 ms | `6 assertions` | test/seon/merge_test.clj:208 (<=10 ms), :227 (<=5 ms); task_test.clj:233 (<10.0); cluster/message_test.clj:913 (<2.0); db_test.clj:2942 (<10.0); program_test.clj:1346 (<5) | millisecond assertions under 10 ms on measured wall time: flake-prone; the law's bound is five seconds with a reason |
| 7 test with-redefs | `104 code lines, 90 symbols, 44 files` | test/seon/cluster/turn_test.clj (28 lines), db_test (4), render_simplification_test (4), turn_loop_test (4) | Var redefinition for the whole JVM; the law forbids with-redefs. rg total 162 includes strings and docstrings. Already filed as seon.test.edn census |

## Notes

- Method limits: the scanner attributes each line to the nearest preceding top-level def, so a catch inside a let is attributed to its def. Try and catch counts use code-line regexes, so a few docstring hits are included (for example the rg raw count of with-redefs is 162 against 104 code lines). Strings and comments are stripped only for the numeric scan.
- Numeric scan covers literals of 100 or more. Small literals (for example `(.waitFor x 5 SECONDS)`, or `(Thread/sleep 5)` in src) are not scanned, so the timeout count is a floor. Date literals (2026) and unit conversions (1000000, 1000) are excluded. Of 124 policy-context hits, about 30 are code and listed; the rest are docstrings or comments.
- Catch classes: E, R, T and C come from the rule table and a read of about 60 catch bodies. Entries whose body was not read are classed by name and file and say so in the why column where it matters (for example ai.clj send-request, cache lookup-within, shell await-exit, db q).
- Cleanup-and-rethrow (class C, 44 sites) is not a fence by the law's own terms; it is listed as a separate class so root can rule on it.
- Retry detection is by named forms and catch with loop. A grep for retry or attempt returns 471 raw hits, mostly in test/ and in the word attempt for provider attempts; not counted. Rendering attempt wrappers in ai.clj are listed as retry-shaped but the body was not read.
- Atoms: `(ref` in src is only the four clojure.test report counters (allowed). `(agent` is one site, test.clj attempt-request-context (inside the test runner). Local volatile! accumulations are listed as fold candidates; they are not allowed cells under the law.
- Printer scan: `format "%s"` and `str` on whole values were not measured (unverified). The printer list is pr-str only.
- Surprises: src has one Thread/sleep (render/web.clj:2492). alter-var-root in src appears in seon/instrument.clj (five code sites, lines 1024-1300): this is the instrumentation owner's own mechanism restoring original Vars, not a test; listed for the owner's ruling, not as a breach.
- Default is not queried; no number here comes from default.
- The table is complete for the catch sites (74 OTHER rows); the other categories are complete as scanned, with the gaps above.
