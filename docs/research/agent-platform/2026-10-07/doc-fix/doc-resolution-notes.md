# doc-fix: `(doc h)` could not resolve `seon.sci.eval/reference-in` on default

Lane doc-fix, 2026-10-08. The task is `seon.turn/an-armed-agents-run-forks-the-program-acquired-before-a-later-commit`.

## What happened

Default restarted onto b5ff51e4c at 00:34:20 UTC and was ready at +27 s. Adoption then wrote the loaded files' entities:
the `seon.sci.eval/reference-in` row landed at 00:34:57, and the cluster's `:seon.source/commit-id` moved
6ac6cdf0 to 6ac6e52b at 00:35:03.

Root's MCP SCI runs (`(doc h)` at 00:35:11, `(doc scratch.root-repl-check/h)` at 00:35:53) failed with
"Unable to resolve symbol: seon.sci.eval/reference-in". In root's SCI context, `(ns-publics 'seon.sci.eval)`
had 37 names and no `reference-in`. A fresh `seon.sci.eval/base-ctx` on the same database had 38, including it.

At the same time, a freshly hosted agent (doc-fix, first run at about 00:35:40) resolved it, and so did the JVM path.
After the lane's first JVM-mode call as root (00:36:17), root's SCI `(doc inc)` worked.

The `:seon.error/in-namespace` hint listed `reference-in` because `seon.error` reads it from database rows
(`names-in`), not from the context. The row existed; the context did not have it.

## Cause

`seon.turn/resume-turn` (the armed graph's pass) forked `(:seon.sci.eval/ctx cluster)`, which is the base the graph
captured when `arm-graph!` ran at boot, and it never acquired the program the connection now holds. Only
`seon.cluster.agent/acquire-context!` called `seon.sci.eval/acquire!`. That function runs on JVM-mode MCP calls,
hosting and arming, but not on an SCI run.

So every SCI run of an already-armed agent kept the program acquired before boot's adoption. A program committed
by any other writer, such as another agent's definition, stayed invisible in the same way. The lane's JVM call ran
`acquire-context!`, and `regenerate-agent-context!` replaced root's agent context in place, which is why root
recovered.

The `doc` macro (S1-04/S1-05) is not at fault. `reference-in` was simply the first newly added core function an
agent called after the restart.

## Why the check JVM passed

`seon.sci.documentation-test/doc-classifies-authored-references` evaluates through `support/evaluate` on a context
forked by `support/fork-cluster-ctx` at the current database. Nothing commits program between that acquisition and
the evaluation, and no armed graph or adoption step is involved. The check JVM's own boot adopts before any agent is
armed, so it never has the window that default had.

## Reproduction (JVM mode, disposable branch, since retired)

- Retract the `reference-in` row and set the record to the old source commit, then build `cluster-ctx` and run
  `acquire!`. Result: `(resolve 'seon.sci.eval/reference-in)` gives nil.
- Restore the row and the record, then run `acquire!` again. It resolves. `acquire!` is correct; it was simply not
  called on the turn path.
- Regression: `seon.cluster.evaluate-source-test/an-armed-agents-run-sees-a-program-change-committed-by-another-writer`.
  The agent is armed and runs once, then `support/define!` commits `my.agents.later-writer/later-inc` through another
  writer, then the agent's next run calls it and runs `doc`.
  - Before the fix it failed: "Unable to resolve symbol: my.agents.later-writer/later-inc", with `:in-namespace`
    listing it, the same signature as default's error.
  - After the fix it passed.

## Fix

`resume-turn` acquires its base at the connection's current value inside the fork phase, then forks at that same
value. This is the mechanism `acquire-context!` already uses, including the same refusal when the acquisition cannot
record its refusals. When the program is unchanged, `acquire!` returns at once: 109 us and about 145 KB allocated per
call, measured over 100 calls on default's cluster context with armed contracts.

## Design check

The design held: the owner (`acquire!`) existed and was correct, and only the pass skipped it. Two other turn sites
also fork without acquiring:
- the preview fork in `seon.turn` (system-run previews). It forks right after `acquire-context!`, so it is current.
- `preview-sources`. Its caller supplies the base.

Neither was changed.

## Other findings

- `seon.sci.eval/acquire!` returns the whole schema projection in its value. A JVM-mode probe printed thousands of
  elided entries. That is ugly output for an agent that calls it at the REPL; the value printer elided it.
- `(doc h)` for a definition the agent just made (an agent branch) answered only `{:arglists ([x])}`, without the
  declared `:in`/`:out`. The row path in `documentation-value` was not taken there.
- `seon.cluster.store/open-branch!` and `seon.cluster.registry/branch!` work for disposable probes. `seon.store` is
  not a namespace.
