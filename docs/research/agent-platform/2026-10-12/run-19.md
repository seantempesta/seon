---
title: "Run 19: chain 4 rerun on 6299bcda4 (with the F53 fix 0c684db75): the chain completed unattended in 21 s. Prove wrote both declared product tests, the table and the seeded quick-check law, in one reply (4 calls); Implement fixed seon.render.block/surface-id in 2 calls under its unchanged complete contract; both merged and were published, and both branches were deleted with no outside write. The merged function is correct"
type: research
status: complete (lane run19, 2026-10-13); chain done; product correct
created: 2026-10-13
---

# Run 19: the chain `seon.render.block/a-surface-id-passes-only-ascii-letters-digits-dot-and-hyphen` on 6299bcda4

**Broken first: nothing in the chain.** One minor defect shipped in the Prove product (F56): the table's assertion
message is `(str "surface-id of " (pr-str name))`, which prints a value into an assertion message, against the
testing law. The Run 18 live-lock (F53) did not recur. JVM CPU sampled every 10 s from the OS was 2.7 % → 7.1 % →
0.0 % across the chain and idle after it.

| Measure | Prove (`198592de3663`) | Implement (`ac1862579146`) |
|---|---|---|
| Assignment / agent / branch | `0343dd641b00` / `attempt/0343dd641b00` / `:attempt-0343dd641b00`: **deleted** | `2ca9617587fa` / `attempt/2ca9617587fa` / `:attempt-2ca9617587fa`: **deleted** |
| Model calls | **4** (23:54:15.6 → 23:54:19.9) | **2** (23:54:26.5 → 23:54:28.3) |
| Product | **both** declared deftests in call 4's one reply: the table (8 rows, exactly as specified) and the law (`quick-check` 300, `:seed 20261013`, `support/assert-check!`, a local ASCII-range predicate, no regex) | `surface-id` only: ASCII ranges replace `Character/isLetterOrDigit`; the body comment is corrected; docstring and contract untouched |
| Proofs owned | n/a | `seon.task/obligations` after the chain: `:proofs` = both product tests (they are tagged Implement) |
| `refine!` / `send-back!` | 0 / 0 | 0 / 0 |
| End | `:done`, `:merged` at 23:54:24.985 (2 declarations), git `5f7fc3f7b` | `:done`, `:merged` at 23:54:34.994, git `44ce825d8` |
| Relaunches | 0 | 0 |

Launch at 23:54:15.4, then Implement published at 23:54:36: **21 s** end to end. The coordinator asked whether
Prove would get a "5th call" to write the law. It needed none: call 4 wrote both tests, the step's product was
complete, and the step ended `:done`.

## Before launching

- Default pid 56128, booted 23:53:39 UTC from `data/source/6299bcda4d21…` (HEAD, including `0c684db75`), and reset.
  Health `:degraded` (cache and hot warnings only). Default was not stopped or reset by this lane.
- Subject `2ecf010721dd` `:held`, no attempts, `seon.task/ready #{}`, branches `[:db :current-src :cluster-default]`.
  The Run 18 evidence branch is gone. Gap re-verified: `é`, `ª` and `٣` pass through unescaped.
- **Reaching tests, interpreted, on `run19-probe`** (`:agent-633c958a8cd9`): `tests-reaching` gates
  `seon.render.block-test/stable-surface-addresses-are-injective`. Run `4bd04b198992`, `:interpreted`, green 2/0/0,
  2,061 ms and 4.1 GB (F27). Launched. The probe was retired with `(my.agent/retire-branch! (my.agent/branch))`.
- Config: `apply!` `{no-provider false, max-episode-runs 60}` → `converged? false`, 4 operations (C3). Effective
  values confirmed, plus `relaunches 2` and model `deepseek-flash`.

## Timeline (UTC, 2026-10-12)

| At | Event |
|---|---|
| 23:54:15.43 | root transacts `:seon.task/execution :running` on the subject. This was the only hand act. Nothing was written to default until 23:54:50 |
| :15.61 | Prove call 1: `my.program/source` of the test namespace |
| :17.4 / :18.9 | calls 2-3: ns form, aliases, live `surface-id` values for the rows |
| :19.90 | call 4: both deftests |
| :24.985 | Prove `:done`; merged (main commit `6acd7331…`) and published, git `5f7fc3f7b` (`test/seon/render/block_test.clj` +33) |
| :26.50 | Implement call 1: source of `surface-id` on its branch |
| :28.32 | call 2: the fixed `defn` |
| :34.994 | Implement `:done`; merged (main commit `6acd733b…`) and published, git `44ce825d8` (`src/seon/render/block.clj` +6/−2) |
| by 23:55:05 | both attempt branches absent from `d/branches`, with no outside write |
| 23:55:2x | cleanup: config restored, subject `:held` |

## Seon git commits from this run

```
commit 5f7fc3f7bf732dead9ad7cc96dcd10bea68ad1d3
    Merge seon.render.block/prove-a-surface-id-passes-only-ascii from agent attempt/0343dd641b00: merged on main as 6acd7331-fedd-5e60-b764-8b61a338fa19
 test/seon/render/block_test.clj | 33 +++++++++++++++++++++++++++++++++
 1 file changed, 33 insertions(+)

commit 44ce825d83ae11353c7982a009e1df5d90eff185
    Merge seon.render.block/implement-a-surface-id-passes-only-ascii from agent attempt/2ca9617587fa: merged on main as 6acd733b-616b-5357-b7ca-3e9c674a8ddf
 src/seon/render/block.clj | 8 ++++++--
 1 file changed, 6 insertions(+), 2 deletions(-)
```

## Correctness check

Default's compiled `surface-id` is the pre-merge body until a restart. I copied the merged body verbatim from HEAD
into a JVM local and compared it with the compiled (old) Var:

| name | merged | old |
|---|---|---|
| `:transcript`, `:a.b-c`, `:a_b`, `:my.turn/tree`, `"a b"`, `:Z9.` | unchanged: `surface-transcript`, `surface-a.b-c`, `surface-a__b`, `surface-my.turn_2f_tree`, `surface-a_20_b`, `surface-Z9.` | same |
| `é` / `ª` / `٣` | `surface-_e9_` / `surface-_aa_` / `surface-_663_` | passed through |
| `Ünïcödé` | `surface-_dc_n_ef_c_f6_d_e9_` | passed through |
| `日本` | `surface-_65e5__672c_` | passed through |
| `ǅ` (titlecase letter) | `surface-_1c5_` | passed through |
| `😀` (surrogate pair) | `surface-_d83d__de00_` | same (`isLetterOrDigit` was already false) |
| `:a/b` vs `(keyword "a_2f_b")` | `surface-a_2f_b` vs `surface-a__2f__b`: distinct | same |

Injectivity spot-check: the 568 single-character names `(char 32)` to `(char 599)` give 568 distinct ids. **Correct.**
Contract: the existing complete one, `[:=> [:cat :seon.render.block/name] :seon.render/surface-id]`, unchanged.

## Grades

- Hand acts after launch: **0**. Wakes: **0**. Relaunches: **0**. Calls: Prove **4**, Implement **2**.
- Platform: launch → Prove → merge → publish → Implement fork → merge → publish → both branches deleted,
  unattended, in 21 s. F53 (`0c684db75`) holds live. `ec19ecdfb` holds: the declared product was both tests, and
  Prove wrote both. Branch deletion holds for both steps.
- Product: correct. Law written: **yes**. `send-back!`: never needed.

## Frictions

| # | Friction (observed) | Owner | Proposed red proof |
|---|---|---|---|
| F56 (minor, product) | The merged table's assertion message `(str "surface-id of " (pr-str name))` prints a value. Prove copied the pattern into new code; nothing refused it at merge | `seon.render.block-test` (fix the message); a lint for `pr-str` in `is` messages | a lint finding on `(is … (str … (pr-str …)))` in `test/` |
| F55 (not exercised) | I read only through `commit-as-db`, and those reads were fast (under 60 ms). The coordinator filed F55 at `6299bcda4` | as filed | as filed |
| F27, C3 (again) | 2,061 ms and 4.1 GB to check one test; `apply!` 4 then 3 operations, `converged? false` | as before | as before |

**Not verified:** the turn thread's own CPU between calls. With "no long extra probe threads on default", I watched
only the JVM process's CPU from `ps` (no write to default). It was at most 7.1 % at the 10 s samples, then 0.0 %.

## Residue on default (disposable)

- Subject `2ecf010721dd` is `:held` (verified). Both steps are `:merged`. `run19-probe` was retired. Both attempt
  branches were deleted by db<->flow.
- Config restored to `no-provider true` and `max-episode-runs 100` (verified).
- Shared tree: `5f7fc3f7b` and `44ce825d8` are on `refactor/agent-platform`. Default's compiled `surface-id` is the
  old body until the next restart.
