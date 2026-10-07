# Live run 1: one DeepSeek-driven Seon agent on a real task (2026-10-07)

HEAD `860fcc15f`, cluster `default` (running a8e3bde5b). No source/config edits. Raw evidence: `raw/` (per-call prompt, reply, evals; turns.edn; branch faults).

## Summary

- The existing loop worked end to end on a disposable branch agent `live-run-1` (branch `:agent-80e4a2ca8c45`): 11 `deepseek-flash` calls in 35 s, stopped cleanly at its episode cap of 12; no crash, no open turn.
- Cost: 124,157 prompt tokens (86% cache hits), 3,555 completion tokens, about US$0.005; 1-5 s per call.
- Task `seon.repl/reply-map-is-not-edn-when-elided-or-rendered` was not completed. Three causes, none the model's reasoning (its call-5 diagnosis was correct):
  1. Stop sequence `["#:seon.repl"]` (`config/default.edn:438`) truncates any reply containing that text; the task's own code contains it, so 5 of 11 replies were cut (one empty) and the agent never learned why.
  2. No protocol in context: no system message (`:seon.ai/system` has no producer; `ai.clj:564`), and `(help)` is offered but never run into the opening. Call 1 sent 24 forms, 12 of them `(seon.repl/response)` with no args.
  3. It could not run the proof test: `clojure.test/run-test-var` cannot see test vars from SCI, and it never found `my.test/check`.
- The task was already done: proof `seon.repl-test/the-reply-is-one-edn-map-whatever-the-value` passes 21/21 (fixed in 3ee604004 / d6942b298) but task 18c37812b5bc is still open.

## Per-call record

| # | open→close | prompt ~tokens | refresh share | prompt / cache-hit / completion | forms (errors) | what happened |
|---|---|---|---|---|---|---|
| 1 | 4.5 s | 1.4k | 80% | 1960 / 0 / 631 | 24 (10) | 12× `(seon.repl/response)` no args; tried source, source-fn, meta, require, my.task/step! |
| 2 | 1.2 s | 4.8k | 31% | 6373 / 1920 / 6 | 1 (1) | empty: stop sequence fired; raised the shown-failure fault |
| 3 | 1.7 s | 5.7k | 42% | 7750 / 6272 / 124 | 2 (1) | `slurp` unresolved in SCI; source-fn worked |
| 4 | 1.3 s | 6.9k | 38% | 9264 / 7680 / 39 | 1 (1) | defn docstring cut at `#:seon.repl` → EOF |
| 5 | 2.7 s | 7.2k | 39% | 9685 / 9216 / 411 | 3 (0) | read the code and proof test; correct diagnosis |
| 6 | 3.1 s | 9.4k | 33% | 12233 / 9600 / 455 | 3 (0) | Markdown backticks evaluated as syntax-quote |
| 7 | 3.1 s | 10.4k | 32% | 13502 / 12160 / 463 | 1 (0) | paged elided source by hand with `subs` |
| 8 | 2.7 s | 11.6k | 31% | 14849 / 13440 / 378 | 2 (1) | `run-test-var`: unable to resolve var in SCI |
| 9 | 5.2 s | 12.1k | 29% | 15461 / 14592 / 836 | 4 (1) | in-ns + defn- accepted (docstring silently dropped); next defn cut |
| 10 | 1.8 s | 12.8k | 28% | 16376 / 15360 / 142 | 2 (1) | retried with escaped quotes; cut again |
| 11 | 1.6 s | 13.0k | 27% | 16704 / 16256 / 70 | 1 (1) | `## What` became a form error; cap reached |

## Shown vs needed

Shown: evaluation history as `user=>` pairs plus "turns left"; the observer's own MCP/JVM setup runs (mislabelled as refresh); opening reads (identity pull, my.task/steps, raw message pull, settings, `(dir 'user)`, runtime pull, seon.error/for-agent); the waking message a second time as a runtime-trigger diff.
Needed and missing: the REPL protocol; any system message; why replies were cut; how to run a named test (`my.test/check`); that the proof was already green; a way to read elided source.
Waste: call 1's 24-form burst stays in every prompt; the same fault re-shown 6 times (~250 tokens each plus a ~1000-token large-value finding); refreshes 27-42% of each prompt; history grows linearly (1M budget never dropped anything).

## Editing and testing

Editing via `(in-ns 'seon.repl)` + `defn-` on the branch works (program entity accepted; docstring retracted without warning). Tests: `(my.test/check {:seon.test/changed ['seon.repl/data-text]})` ran 0 tests, `:passed? false`; naming `seon.repl/response` ran 31 tests in 26 s, 4 unrelated reds, ~4.2k tokens of printed output.

## Defects found (file:line)

1. Stop sequence truncates real code, looks like a normal `finish stop` (`config/default.edn:438`).
2. No protocol in context; no system-message producer (`ai.clj:564`).
3. Empty reply → self-contradicting contract fault "expected a string of length at least 1, got a string" (`seon.turn/show-row` turn.clj:4755-4763 via `planned-sources` 3044-3058); re-shown on every refresh.
4. Reply segmenter evaluates Markdown as code (backticks → syntax-quote; `##` → invalid token).
5. Agent overlay cannot enable the provider (`no-provider` is `[:= true]`, seon.config.ai.edn:64-72; cluster-then-agent merge ai.clj:293).
6. `my.agent/settings!` reply renders `{}` after a successful write.
7. Outside MCP/JVM runs as the agent enter its model context.
8. `my.test/check` reach selection misses `data-text`; the check prints ~4k tokens.
9. Stale task 18c37812b5bc green on HEAD but open.
10. Observer-side: JVM-mode defn answered "evaluated but not recorded" hiding values; ExceptionInfo inside CompilerException lost its message/data; a refused `seon.db/q` error map silently became map entries under `sort-by`.

## Implications

Agent-loop spec: protocol as a mandatory context unit or system message; show the waking message once (inbox); decide whether outside runs are history; live reads (no system turn) fit the measured refresh share; show a fault once; episode cap was the only bound that acted and worked; remove the fixed stop string or report the cut to the agent; completion must run proofs and the task view must show proof status and offer `my.test/check`; S1 already works today — the blocker is context and protocol, not turn machinery.
Errors spec: contract messages must say why (length 0); chain conversion keeps each ExceptionInfo link's message and data; error values consumed by seq functions become silent garbage; a truncation is an error value the agent receives; deliver a fault once per occurrence.

Unverified: per-stage timing inside a call; exact HTTP body; whether the 4 reds fail on default too; model-observation rows.
Leftovers on default: assignment row 3abf3a49dc4e, closed agent row live-run-1, `user/lr-*` vars (gone at restart).
