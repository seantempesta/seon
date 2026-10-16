---
title: "live run: one DeepSeek chain, zero hand acts (seon.edit/an-unreadable-odd-map-is-not-one-form)"
type: evidence
status: "run complete 2026-10-16; the merge landed and broke a main test (defect 1)"
---

# Live run: a one-task chain started with one `seon.task/chain!` call

Lane live-run (Sonnet), default JVM pid 35580, HEAD 6fa21abd7 at the start.

## The task

`seon.edit/single-form?` threw on an odd map literal (`"{:a 1 :b}"`) instead of answering false. A filed task
(`resources/seon/tasks/seon.edit.edn`, `an-unreadable-odd-map-is-not-one-form`) with its two proofs already committed
in `test/seon/edit_test.clj`: `seon.edit-test/an-odd-map-anywhere-is-not-one-form` and
`seon.edit-test/every-source-holding-an-odd-map-is-not-one-form`. Red on default before the start: `bin/seon check
--test seon.edit-test/an-odd-map-anywhere-is-not-one-form` failed, and a JVM probe showed the throw. Other filed tasks I
screened were already fixed and never retired (defect 5).

## The one call (MCP eval_clj, SCI, root)

```clojure
(seon.task/chain! {} [(merge (seon.task/template (seon.db/db) 'seon.step.implement/template)
                             {:seon.task/ns 'seon.edit :seon.task/name "an-unreadable-odd-map-is-not-one-form"
                              :seon.task/plan plan :seon.task/proofs #{...the two deftests...}})]
                  {:seon.chain/config {:seon.config.ai/no-provider :seon.config/absent
                                       :seon.config.run/max-episode-runs 40}})
```

The `{}` context filled from the evaluation's bindings. Answer: `{:seon.chain/first "68d99c8aef55", :seon.chain/branch
:chain-68d99c8aef55, :seon.task/handles ["68d99c8aef55"]}` in 570 ms (request 1,096 ms, 948 MB allocated). The plan was
the filed text re-typed as Problem / What exists / Approach with backticked symbols.

## Outcome

Merged. Hand acts: **0** (no config apply, no `run!`, no edits, no restart).

| Measure | Value |
|---|---|
| chain / first task / branch | `chain-68d99c8aef55` / `68d99c8aef55` / `:chain-68d99c8aef55` (kept, no open connection) |
| attempts | 1 (`:seon.task/attempts-made` 1), agent `attempt/5548298627d3`, assignment id `5548298627d3` |
| model calls | 27 (9 `tool_calls`, 18 `stop`), 27 turns, one call per turn |
| tokens | 465,680 prompt (384,640 cached), 3,729 completion |
| wall time | chain! 03:34:2x Z; first call 03:34:23.970Z; last call 03:35:20.378Z; merged 03:35:27Z (commit time 10:35:27 +0700): about 63 s from start to merge |
| end | proofs green, outcome `:merged` on the attempt row; the chain fact on main retracted |
| merge | root's `db<->flow`, commit `a9e4e9904` (`Merge assignment 5548298627d3`), merge commit id `6ad19b7f-5e9d-5022-a32d-6a429be0a197` |
| turns per task (key metric) | 27 model turns, 1 attempt |
| faults | open-fault count 29 before and after; agent-error signatures 4 before and after (none caused by this run) |

## The published diff (`git show a9e4e9904`)

One file, `src/seon/edit.clj`, +27/-12. It adds `[seon.sci.reader :as reader]` to the ns requires and, in
`seon.edit/single-form`, reads the whole source with `seon.sci.reader/read` first, returning the refusal when an event
carries `:seon.sci.reader/error`. No REPL requires or stray defs. Debris and problems:
- the refusal map is written out twice (new unreadable branch plus the old form-count branch), identical but for
  `:seon.edit/form-count 0`; the old one could have been a shared local;
- the commit message carries `Co-Authored-By: Claude Opus 5.5`, not the model that worked it (DeepSeek);
- the test file was already in HEAD; the filed task entry in `resources/seon/tasks/seon.edit.edn` was not retired
  (defect 2).

## Defects

1. **The merge broke a test that is green on its parent, and the done rule did not see it.** `seon.edit`'s ns is
   `^{:seon/babashka true}`: the edit hook loads it in Babashka while Seon is down, and its docstring says it has no
   Seon loading dependency. The plan I handed the agent said to require `seon.sci.reader`; that sentence came from the
   filed task's text, written before `e5dd2abd5` ("Revert becee4d99's seon.edit change: seon.edit must load in
   babashka") and `c113d7197` (the bb-load test). The agent followed the plan; the merge's green gate (proofs, changed
   tests, reaching tests) passed. On `a9e4e9904`, `bin/seon check --test seon.babashka-load-test` is red:
   `{seon.edit "... Could not find namespace: clojure.core.specs.alpha. Location: .../src/seon/sci/reader.cljc:4:3
   ... seon.edit - src/seon/edit.clj:7:3"}`. `seon.babashka-load-test/declared-namespaces-load-in-babashka` shells out
   to `bb`, so no reaching-test derivation links it to `seon.edit`. Consequence: every edit through `bin/seon-hook` on
   HEAD fails to load `seon.edit`. **ROOT: revert or repair `a9e4e9904` (the reader cannot be required by a
   Babashka namespace; the odd-map check needs another seam, e.g. `edamame` which `seon.edit` already may use).**
   Two defects in one: stale filed plans contradict later rulings (my pick), and `seon.cluster.source/done-refusals`
   has no way to see a test that reaches a declaration only through a subprocess (the "no regression of main" clause
   judges only reaching tests).
2. **A merged chain does not retire the filed entry.** TASK-CHAIN says the merge of a chain that worked a filed task
   deletes that entry (S3f-1, installed later). The entry and its two legacy `:step`/`:children` task rows in
   `resources/seon/tasks/seon.edit.edn` are all still there after the merge.
3. **The merged task stays `:running` on main.** After the merge `[:seon.task/id "68d99c8aef55"]` on main reads
   `:seon.task/execution :running`, attempts-made 1, outcome `:merged` on its attempt row. A running task with a
   merged attempt may be relaunched by a later pass (not observed within five minutes). The chain fact was
   retracted correctly.
4. **`(seon.context/current-state)` for root costs 1,140 ms and 929 MB** and prints a 10,849-token value of 60+
   unread messages dating from 2026-10-14. Over the one-second law; the inbox is never cleared by `received!`.
5. **Filed backlog entries are stale.** Of the filed `seon.fs`, `seon.repl` and `seon.edit` bug tasks I probed,
   `seon.fs/under-path?`, `seon.fs/intermediate-paths` and `seon.repl/token-boundary?` were already fixed with their
   proofs committed (tests tagged `:seon/tasks`), and the entries remain.
6. Lane noise: a JVM-mode `d/q` over `[?a (count ?e)]` of all attributes on the chain branch took 2.8 s and 12.6 GB
   allocated (my probe, not a system path); `seon.cluster.boot/connection` returns a thing whose deref is not a
   connection while `seon.db/branch-connection` needs the deref, and `seon.db/db` of the wrong one answers a contract
   error naming `:seon.error/at` as missing: a confusing refusal for a wrong argument.

## What it shows about the model

A chain of one with a typed plan and two committed red proofs went from one `chain!` call to merged main in about a
minute with 27 turns and no hand act: reactive start works end to end (P3/P4 of the reactive-start plan). The
quality gap is the gate, not the agent: it implemented exactly the plan, and the plan was wrong for the current tree.
