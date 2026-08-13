---
type: issue
status: open
severity: friction
tags: [issue, agent, docs, class/n12, wave/docs-honesty]
---

# Make production docstrings describe the surviving runtime

## Problem

Callable, agent-visible source documentation still teaches deleted execution,
comment, lifecycle, construction, and vocabulary semantics. These strings are
part of the program graph and agent context, not harmless migration notes.

Several files are **in flight (schema-edn-consolidation lane)**; their current
diffs only update schema-resource comments.

## Evidence

- `src/my/message.cljc:49-54` says `my.run` returns schema-invalid bare error
  maps, while `src/my/run.cljc:62-82` includes `:seon.error/kind`.
- `src/my/run.cljc:50-55` says the next run gets a fresh SCI context, contrary
  to `src/seon/sci/eval.clj:71-92` and the shipped bootstrap at
  `resources/seon/bootstrap.edn:4-12`.
- `src/seon/context.clj:64-69` describes `wait` as pausing the same run, while
  `src/my/run.cljc:40-48` says it closes and a later trigger starts another.
- `src/seon/cluster/instruction.cljc:17-34` teaches `;;` prose, while
  `src/seon/cluster/reply.cljc:78-85,211-217` owns the single-`;` grammar.
- `src/seon/reconcile.cljc:5-12` says tests and implementation are not authored;
  `src/seon/cluster/run.cljc:5-12` asks a future lane to fill implemented calls.
- `src/seon/flow.clj:1-6` calls a live scheduling owner a testbed.
- `src/seon/cluster/loop.cljc:1380-1387,1547-1585` and
  `src/seon/cluster/work.cljc:14-28,519-540` call plan execution a “fold,”
  contrary to the maintained `reduce` vocabulary.

## Owner

Each surviving public namespace, with `seon.sci.eval` and the vocabulary table
as the execution/naming authorities.

## Acceptance

An agent reading `doc`, the bootstrap, and the owning function docstrings gets
one true account of context lifetime, run disposition, comment grammar, and
plan reduction. Construction diaries and banned vocabulary are absent from
fresh production source.

## Re-grounded evidence — 2026-08-13

**STILL-REAL at `06e654c76`, with a smaller current census.** Most renamed-file
examples are gone, but two live agent-facing claims still teach ruled-out
semantics:

- `src/seon/cluster/instruction.clj:13-24` tells every new agent that prose is
  retained as `;;` comments and demonstrates that spelling. The actual owner
  emits single-`;` comments at `src/seon/cluster/reply.clj:91-97,225`.
- `src/seon/flow.clj:1-6` still calls the production Flow owner a "standing
  testbed", despite that namespace owning the running graph launchers.

The earlier `my.message`, `my.run`, context-lifetime, construction-diary, and
fold examples do not survive at HEAD; the two current lines above are the
remaining work for this note.
