---
type: issue
status: open
severity: defect
tags: [issue, sci, config, instrumentation, custody]
---

# SCI contract arming guesses its cluster from a database row

## Problem

`seon.sci.eval/database-effective-config` finds the cluster to arm contracts
under with `[:find ?cluster . :where [?c :seon.config/cluster ?cluster] [?c :seon.config/on-core-error _]]`.
A forked branch holds both its parent's config row and its own
(`src/seon/cluster/registry.clj:240-262`), so this scalar picks one of two rows,
and nothing makes the pick deterministic. Two changes are needed. The cluster
name should come from the context, and `seon.config/effective` should be called
once. Commit `39ca6521a` already removed the `:record` default.

The contexts that arm contracts cannot yet supply the name:

1. **Base contexts carry no environment.** `base-ctx` / `derive-base-ctx` arm every
   acquired contract (through `install-function-from-database!` and `acquire-program!`).
   They are derived from a database value alone. The live probe on 2026-09-24 read
   default pid 90471's four `base-context-cache` entries: `(env/of ctx)` gives nil
   for every one. `fork-for-turn` copies these wrapped roots.
2. **`projection-state` invents a cluster name.** `src/seon/sci/eval.clj:174-184` builds
   every such environment with `:seon.boot/cluster-name "current-src"`, and about 118
   call sites use it (`rg "projection-state "` over `src`/`test`). A context forked
   through it would name a cluster that has no config row.

## Fix direction

- `acquire!` passes `(:seon.boot/cluster-name (env/of ctx))` into the arm request.
- `base-ctx-key` includes the name, because arming depends on it.
- `derive-base-ctx` keeps the name in the program snapshot, where it already keeps
  `:seon.flow/commit-fault!`.
- `install-function-contract!` reads the name from the context's environment, or
  from that snapshot on a base.
- `projection-state` takes the caller's cluster name instead of `"current-src"`.
- Direct `base-ctx` test callers (`test/seon/instrument_test.clj:342`, `:405`, `:423`;
  `test/seon/sci/eval_test.clj`) pass the name.

This crosses the owners of `instrument_test.clj`, every `projection-state` caller and
`seon.sci.eval`. It is more than one bounded slice.
