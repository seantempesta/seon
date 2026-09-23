---
type: landing
status: UNPROVEN on default: proof owed after the checkout restart
created: 2026-09-23
tags: [agent-platform, testing, fixture]
---

# Fixture agent rows through `support/agent-tx` (issue fixture-agent-rows-lack-the-required-agent-branch)

One rewrite-clj script (`tmp/agent-branch-sweep/convert.clj`, plus `indent.py` for
continuation lines) rewrote every `transacted!` / `transact!` / `transact-fixture!`
call whose literal tx vector held a single-key `{:seon.agent/id x}` map: one agent
becomes `(support/agent-tx @conn x)`, several `(support/agents-tx @conn [x y])`
(new in `test/seon/test_support.clj`), other rows follow via `into`. 60 test files,
108 sites; by hand: db_test elided-arity write converted, its foreign-branch
refused write kept bare (the test asserts the submitted transaction). clj-kondo: 0 errors.

Before (run 7222eb81d8f7, HEAD, `--policy named` over the 60 namespaces, 177,971 ms,
dominated by the long concurrency-independence member 136.7 s and a concurrently loaded
JVM): pass 60 / error 7, all seven the missing-branch fixture refusal (my.agent, my.background,
my.message); 5 members pending, 9 excluded as platform.

After: NOT RUN. `bin/seon init --dev default --changed <61 files>` (20 s) coincided with
default dying of heap exhaustion; the orchestrator rebuilt it at committed HEAD.
Owed: adopt these files, then one `bin/test-check default --policy named --ns …` over the
60 namespaces (list: the files in this commit).

Residue not converted by the script: nested `:seon.turn/agent {:seon.agent/id x}` maps
and multi-key agent maps (issue_settlement_test settings rows, web_debug_test namespace
rows, value_test, render/retained_test) — convert only if the after-run shows them refused.
