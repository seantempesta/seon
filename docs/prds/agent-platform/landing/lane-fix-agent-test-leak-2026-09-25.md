---
type: landing
status: complete
lane: fix-agent-test-leak-opus
created: 2026-09-25
---

# Agent test: the provider stand-in is an injected local provider (census 2026-09-25d, class 3 / N2)

`ai/complete` already reads its endpoint and authentication from cluster config dials (`seon.ai/targets`,
ai.clj:461; `resolved-target`, ai.clj:381). The test now starts a local OpenAI-compatible HTTP server and names it in
the cluster's config row. `ai/complete` runs as installed, and only the network peer is a stand-in. Nothing JVM-global
is redefined, so a trial that never exits can no longer leave `ai/complete` rebound for the next namespace.

## Broken or unproven first

- **No test was run.** The lane follows the LOAD RULE. Every behavioural claim waits for root's runs below.
- **The local provider is new test code that has not run.** PROBE SAFETY forbids calling unrun code on default.
  Only its pieces were checked (see Evidence). The first root run is its first execution.
- **The size is at the limit.** `git diff -w` shows +112/−60. Of the 112 added lines, 13 only move closing parens,
  which leaves 99 substantive lines. The five extra sites cost about 40 of them.
- **`HttpServer.stop` behaviour while a held reply blocks.** The server uses the JDK default executor, so a handler
  runs on the dispatcher thread. If the trial's cleanup reaches `with-provider`'s `.stop` while the held reply
  blocks, the stop may wait for that handler. The handler's wait is `await-event!` under the 20 s event backstop.
  It then throws and answers 500, so the stop is bounded by that backstop. This is not measured.
- **The future is awaited with the existing bound, not beyond it.** `routing-conservation-waits-for-terminal-evidence`
  awaits `verdict` in its `finally` through `test-support/await-event!` (20 s backstop). If the backstop fires, the
  future can outlive the test. It now holds no redefinition, so it leaks only its own thread and fixture, not global
  state. clj-kondo's "future nobody joins" warning on that line is unchanged from HEAD.
- **Still in this file: `with-redefs`/`alter-var-root` over default's Vars.** These are the same law class, but no
  seam covers them:
  - `prompt/prompt` at :620 (with an `ai/complete` stand-in that asserts no provider call, which keeps
    `recording-completer` alive for that one caller) and at :1042.
  - `async/close!` :812, the forms at :877, :939 and :1204, `with-redefs-fn` :1265, and `alter-var-root
    #'seon.turn/step` :1630/:1648.
  - These need owner seams in prompt and turn. None is a test-only change.

## Change (test/seon/cluster/agent_test.clj only)

- New `provider-server!`: a JDK `HttpServer` on 127.0.0.1:0. It appends each decoded request document to `ledger`
  (the count oracle) and answers `reply`'s text. It answers SSE when the request says `"stream": true`, otherwise one
  JSON document. A reply that throws answers 500, which `ai/complete` returns as `::provider-error`.
- New `provider-config`: sets `:seon.config.ai/endpoint`, sets `:seon.config.ai/no-auth true`, and sets
  `:seon.config.ai/model "agent-test-provider"`. That model has no descriptor row, so `resolved-target` keeps the
  endpoint. It retracts `no-provider` and `backup/model`.
- New `with-provider`: stops the server in `finally`, however the body exits.
- `routing-trial` gains a `[operations reply]` arity. Its contract is now a `:function` of both arities.
  - The `with-redefs [ai/complete …]` is deleted.
  - The `bootstrap/next-entry (constantly nil)` redefinition is deleted: `rg next-entry src` shows it has no
    production caller, so the stand-in replaced nothing. The `seon.bootstrap` require went with it.
- `routing-conservation-waits-for-terminal-evidence`: the held one-arity wrapper around a two-arity stub (the N2
  producer) is replaced by a held `reply` passed to `routing-trial`. There is no `with-redefs` inside the future.
- The same seam replaced `with-redefs [ai/complete …]` in five more places:
  - the n-agent trial (:668)
  - `park-wake-test`
  - the pause test (a held reply, keeping `(= 2 (count @ledger))`)
  - the restamp recovery test
  - `wait-closes-in-terminal-tx-test`

## Evidence

- `seon.ai/targets` in default with `{endpoint "http://127.0.0.1:1/x", no-auth true, model "stub-model"}` kept the
  configured endpoint and `:seon.config.ai/no-auth true` on the primary. The shipped backup model survives unless
  retracted, which is why `provider-config` retracts it.
- `config/compile-manifest` accepted the five `provider-config` dials, with `:seon.config/absent` for no-provider and
  backup/model.
- `seon.ai/request-body` on that target built the wire document with `"model" "agent-test-provider"`, `"stream"
  true`, and no credential needed.
- The installed parsers accept both response shapes the server writes:
  - `seon.ai/completion-text` gave `{:seon.ai/text "(seon.run/complete \"done\")", :seon.ai/finish-reason "stop"}`.
  - `#'seon.ai/streamed-completion` over the same SSE bytes gave that text with `:seon.ai/tokens 2`.
- `(require 'seon.cluster.agent-test :reload)` in default took 112.6 ms, and all four new or changed fns resolve.
  `(class @#'seon.ai/complete)` is still the instrument wrapper `clojure.lang.AFunction$1`.
- `clj-kondo --lint test/seon/cluster/agent_test.clj`: 0 errors, 11 warnings, the same 11 as HEAD.
- Net lines: +112/−60 ignoring whitespace (+52). Source changes: none.

## Root runs (serially, in a quiet window)

1. `bin/test-check default --test seon.cluster.agent-test/routing-conservation-waits-for-terminal-evidence`
2. `bin/test-check default --test seon.cluster.agent-test/routing-proof-rejects-an-empty-production-subject`
3. `bin/test-check default --test seon.cluster.agent-test/wake-routing-conservation-property --include-long`: record
   the elapsed time. Set `:seon.test/long-ms` from it if it is over 5 s, and file an issue if it is over 10 s.
4. `bin/test-check default --test seon.cluster.agent-test/park-wake-test`
5. `bin/test-check default --test seon.cluster.agent-test/wait-closes-in-terminal-tx-test`
6. `bin/test-check default --ns seon.cluster.agent-test --include-long`: covers the n-agent trial, pause and restamp
   members.
7. N2 class regression, run immediately after step 6: `bin/test-check default --ns seon.ai-test`. All 13 members must
   be green. Before it, MCP `eval_clj` `(class @#'seon.ai/complete)` must be `clojure.lang.AFunction$1`.

No RESET NEEDED.
