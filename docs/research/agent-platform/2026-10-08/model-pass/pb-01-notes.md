# PB-01: one cluster base per loaded program (lane pb-01, 2026-10-08)

Commits `6191eace4` and `48191071f` (the second fixes a miss the first left in place). The forms are in
`pb-01-probes.clj`. They ran in a throwaway root at `48191071f` with the fixture provider, so no paid call was made.

## Broken or not met

- **First-pass budget not met by PB-01 alone.** A new agent's first MCP pass measures **137–141 MB / 159 ms**,
  with zero base misses. The row's limit is ≤ 50 MB / 100 ms, and the parent measured 648 MB / 402 ms.
  `acquire-context!` is 26 ms of that. The rest is host and agent creation: `cluster.host/host!` takes 83 ms, and
  three `transact!` take 85 ms. Those belong to other rows.
- **The pass after a settings write meets its limit:** 39–40 MB, 56–188 ms. The wall time is noisy under load:
  four ordinary passes on the same JVM measured 51–366 ms. The settings pass itself is 106–119 MB, all of it in the
  write.
- **A base for the previous loaded commit stays retained** after `start --head` adopts a new commit, until
  Caffeine evicts it (about 11.5 MB weight). The first pass after an adoption still derives the new base. Advancing
  the base on adoption is PB-03's work.
- **Red on HEAD, unchanged by this slice:**
  - `seon.sci.branch-execution-test/branch-definitions-and-unchanged-callers-share-one-context` fails with
    "No declaration was produced". The existing task is `seon.sci.branch-execution-test/branch-proof-expects-a-retired-evaluation-row`.
  - `seon.sci.branch-execution-test/interpreted-callers-resolve-unchanged-names-to-compiled-vars` gets `:jvm` where it
    expects `:unavailable`. New task: `seon.sci.branch-execution-test/interpreted-callers-proof-expects-host-bound-callers-to-refuse`.

## Measurements (change; parent figures from reconciled-plan.md at 81020da6a)

| Probe | Parent | Change |
|---|---|---|
| MCP first pass of a new agent | 648 MB / 402 ms, base built | 137–141 MB / 159 ms, 0 base misses |
| MCP pass after settings write | 318 MB / 228 ms, base built | 39–40 MB / 56–188 ms, 0 misses |
| MCP pass after a one-line `defn` | 519 MB / 301 ms, base built | 40 MB / 52–293 ms, 0 misses (the overlay re-runs) |
| Model passes (run-model 3 #{2}), first / after-defn | 872 / 1,360 MB | 608 / 727 MB; 421 / 475 ms; 0 base misses over 5 passes |
| Retained heap, 8 agents | +1.2 MB per agent, one base per agent branch | +12 MB for 8 agents (1.5 MB each); base count constant at 2 (current loaded commit plus the pre-adoption one) |
| `base-ctx` on the cluster head (cache hit plus overlay) | — | 11–12 ms, 13 MB |

The parent column was not re-measured. The plan's figures predate P11 (`666c767f7`), which changed only the
`set!` print-option case.

## Design check

- **What held.** The base is derived from the loaded value (`derive-base-ctx loaded`), so `acquire-program!` runs
  with no overrides. An agent's own changes are found from the admission datoms (`authored-rows`, about 0.5 MB,
  never a whole-program query) plus one memoized digest map per commit. They are interpreted on the caller's fork
  through the same install loop (`install-selected!`), which was extracted from `acquire-program!`.
- **What was wrong in the plan's key.** `[loaded commit, projection identity, recorder]` still missed in two ways:
  - The projection fingerprint covers function contracts, so a contracted `defn` changed the key. The fix derives
    the base under the loaded value's own projection, and the fork carries the branch's projection.
  - Agent forks carry no recorder (`fork-cluster-ctx` drops it on purpose), so the first agent after boot built a
    second base costing 624 MB. The base interprets nothing and arms no wrapper, so the recorder left the key, and
    `copy-base-ctx` gives each fork its own.

  The key is now `[store identity, loaded commit]`.
- **The overlay holds more than "overrides plus closure".** It also binds the compiled Vars of rows the loaded
  program lacks, such as a fixture's test namespace. Without that, a branch lost them, which the old whole-program
  acquisition had covered through `host-function-rows`. It also installs authored namespaces and tests whose rows
  differ from the loaded ones.
- **The acquisition test keeps the revision basis.** `acquired-database?` still compares revision bases, now over
  program attributes only. The plan asked it to compare digests, but the revision basis is free, while the
  whole-program digest query costs 13–18 MB per pass. Dials left the basis. The context instead records
  `armed-dials` (the caps and the evidence bound its wrappers arm with) and compares them.
- **Tests converted.** `seon.sci.acquisition-attributes-test` was deleted: the dial basis it tested is gone.
  - The memo and concurrent tests asserted that two contexts get an `identical?` acquisition. They now assert that
    no new cluster base appears. The overlay is per fork, so a branch with its own namespace gets its own
    acquisition map.
  - The plan's `a-second-model-pass-…` became `an-agents-second-run-and-a-settings-write-construct-no-base`. It
    drives ordinary agent runs through `evaluate-source!`, not a provider model pass. The model-pass evidence is the
    p9 probe above.
- **Removed rows** (on the loaded program but deleted on the branch) still bind compiled Vars, as before. Unmapping
  them is PB-02.
