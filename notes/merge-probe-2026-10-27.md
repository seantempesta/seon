# Merge probe: a git branch imported into the store, against the design's two experiments

**Setup.** This was read-only. The probe root (`.worktrees/merge-probe`, its own store, no model) ran on a checkout of `bf9ff7502`, 4840ddb's first parent, so its main is that tree.
- **Branch:** `jolt/data-attrs-from-the-index`, tip `b8cb0a618`; merge base `2719f3579`.
- **The three values**, each made by `seon.source/refresh-tx` in the root's REPL:
  - base: main brought back to the merge base. Main's 5 changed files (`render.clj`, `render/ui.clj`, `r3/oracle.clj`, `ctx_test`, `render_test`), at the base's texts.
  - probe: base brought to the tip. The branch's 2 files (`src/seon/merge.clj`, `test/seon/merge_cost_test.clj`), at the tip's texts.
  - merged: main plus the tip's 2 files, which is 4840ddb's program, since 4840ddb's tree differs from bf9ff in exactly those 2 files.
- All three are `d/db-with` values in memory, not store branches.
- Scripts and outputs are in `scratchpad/probe/` (exp1.clj, exp2.clj, *.out).

## Experiment 1: the import reproduces the branch's diff as definitions. It holds, except the cost

- **`:ours` is exactly what git's diff touches: 26 keys.**
  - 10 vars:
    - `seon.merge`'s `carried-tx`, `data-attr-set`, `data-attr?` (new), `entity-keys`, `key-content`, `program-of`, `touched-keys`;
    - `seon.merge-cost-test`'s `a-merge-reads-the-data-attributes-from-the-index`, `data-of`, `data-store` (all new).
  - Their 16 forms: 13 in `seon.merge` (6 changed vars × old and new form id, plus 1 new) and 3 in the test.
  - Checked against `git diff -U0` mapped to top-level forms. No other definition is touched; the hunk next to the unchanged `a-three-way-costs…` deftest is an insertion after it.
- **Conflicts: 0.** `:theirs` is main's 79 keys since the base, and `:apply` is 79.
- **The refresh writes 67 datoms** for the branch. The base refresh, main's 5 files back to the base, writes 193.
- **Files byte-equal:** `(seon.source/files probe-db paths)` equals `git show tip:path`.
  - `src/seon/merge.clj`: 20,471 chars.
  - `test/seon/merge_cost_test.clj`: 6,214 chars.
- **Cost of `delta-three-way base probe main #{}`** (`seon.cost/measure`, minima of repeats): **12,607 contracted calls, 277 MB, 88.8M ticks**. That is not the expected ~39 calls and under 600 KB. Two differences from the proof that measured 39:
  1. The 39 came from `merge_cost_test`'s synthetic store, where only ours and theirs differ in one value. Here main's side changed 79 keys since the base, many of them `seon.render.ui` forms, and `program-of` reads each changed key on all three sides: 105 keys, about 120 calls a key.
  2. The three values are `d/db-with` copies over main, not branches of one store. `d/datoms-diff` skips shared index subtrees, but a `db-with` value's new nodes are its own, so more may be read than on store branches.

  Not measured: the same delta on real store branches (`d/branch!` on the probe store). That is the number the design should quote.

## Experiment 2: one result store answers both paths. It does not yet; 917 of 917 to run

- **Method:** the merged program planned as the judge plans it.
  - `seon.program/reach-graph` and `graph-index` over 4840ddb's inputs: `seon.fx/inputs` of a detached checkout of 4840ddb, tree `c4aa40e6…`.
  - Each test's reach digest against main's shared `test-results/` (about 303k results) and the db's stored results, through `seon.proofs/plan`.
- **Result: 917 tests, 917 to run, 0 reused.** Expected: 0 to run, since `bin/prove --merge` proved 4840ddb green.
- **The first differing digest:** `datahike.query-test/a-nested-call-in-a-predicate-is-refused`. The import's digest is `11baa001bce0212a`. The 5 greens stored for it are at `654c8e21…`, `30aea258…`, `78d4d5ee…`, `e36012eb…` and `b7cf312c…`. None match.
- **What differs, as far as measured.** The `:env` input, which every test reaches, digests `seon.fx/config-input`: the whole config in force minus each process's `:seon.config/place` keys. That is 5,400 chars, with keys such as agent `max-running`, `max-turns`, `stall-ms`, and `ai/*`.
  - The probe root's config is not `bin/test`'s. It was started with `--set :seon.config.agent/max-running=1` and its own `--config` layer.
  - Setting `max-running` back to its default (4) changed `:env` (`9ca1a413…` → `740c2261…`) and the digest (`11baa001…` → `b6f5c43d…`), but still 917 to run. So more of the root's config differs from `bin/test`'s, or the program the import holds differs from the tree's, or both.
- **What the import misses (the finding):** a root's digests take its own config as input. A root started with any non-place `--set`, or a config layer `bin/test` does not read, keys its results apart from every checkout's. "The same tree gives the same digests in any checkout" holds for checkouts, not for a root with a different config in force.
- **Not settled** (stopped here as AGENTS §2 says): whether, once the config is equal, the program digests match as well. The next probe:
  - diff `config-input`'s text in a `bin/test` process against the probe root's;
  - or run `bin/test --plan` on the 4840ddb tree and compare its per-test digests with the import's (`cd`, kept in the probe script) one by one.

**Left running:** nothing. The probe root is stopped, and the detached 4840ddb checkout is removed.
