---
type: landing
status: landed
created: 2026-09-24
tags: [agent-platform, sci, contracts, entry]
---

# Lane fix-entry-eval — SCI entry: aliases, arming, contract gate, private-def walk (2026-09-24)

Reports: `docs/research/agent-platform/three-entry-defects-2026-09-24.md` §3, §4, §2 and
`docs/research/agent-platform/sci-context-efficiency-opus-2026-09-24.md` root cause 4 / slice 3.
Owned paths: `src/seon/sci/eval.clj`, `test/seon/sci/eval_test.clj`, `test/seon/sci/entry_gate_test.clj` (new).

## Broken or open first

- **The `:warn` entry dial does not exist.** No entry-policy key was found anywhere (`rg` for
  `:gate`, `entry-policy`, `missing-contract`, `:seon.config.entry`). Following the assignment, an
  uncontracted `defn` is refused by default and no config key was invented. **Admitting it under
  `:warn` with an advisory needs an owner ruling** on the key (the report proposes
  `:seon.config.entry/contract [:enum :gate :warn]`).
- **The missing-contract refusal reuses `:seon.sci.eval/declaration-absent-error`**
  (`::missing-declaration :seon.fn/spec`, `:seon.error/member :malli/schema`,
  `:seon.error/offending` the symbol). A new named schema would need `resources/seon/schemas/seon.sci.eval.edn`,
  which this lane does not own.
- **The gate sees only a top-level `defn`/`defn-` reader event.** `(do (defn f [x] x))` is not
  refused: the reader does not produce a declaration event for a nested form. Arming has the same
  limit, because `definition-row` runs once after the whole form.
- **Refers are still installed only for selected namespaces.** Aliases and imports now go on every
  namespace row. Installing refers for all rows would throw: 7 of 770 stored refer targets have no
  SCI Var (live probe on default): `seon.operator.runtime/running-instances` (×4),
  `root-store-holder`, `held-flocks` (defonce state, not program identities) and
  `clojure.test.check.clojure-test/defspec`. SCI's `install-namespace-bindings!` refuses a missing
  target (`reference-code/sci/src/sci/core.cljc:808-822`). A refer also captures the Var object at
  install time, which interacts with the stale-Var hazard below. Filtering the 7 would be an
  exemption, so this needs a ruling.
- **Other tests evaluating uncontracted `defn` through `seon.sci.eval/evaluate` will now refuse.**
  `rg` finds uncontracted `"(defn …"` strings in about 60 test files. Most are stored row sources
  installed by `install-row!`, which evaluates with `sci/eval-form` and is not gated. Only
  `seon.sci.eval-test` (fixed here), `seon.cluster.reply-test` (green, 124/0) and
  `seon.turn-loop-test` were run. The turn-loop reds are fixture-write/branch errors with no
  missing-contract refusal among them. The affected tier is root's to run.
- **Stale-Var hazard (Astra report): these changes do not interact with it.** Arming rebinds the
  agent's own generation Var in place (`sci.impl.utils/bind-root!`, same generation, no copy).
  Aliases resolve namespace names at analysis and capture no Var. The gate refuses before
  evaluation. `turn-interns` only reads.
- **The scratch-root boot takes 118–145 s** (existing issue `from-zero-boot-takes-minutes`).
  `init --dev --changed` adoption takes 7.5–10.7 s (`seon.cluster/refresh-source!`). Neither is
  this lane's cost, but both are over ten seconds.

## Changes (four commits)

1. **Aliases for every namespace row.** `acquire-program!` installs `install-declared-classes!` and the
   alias/import namespace bindings over `all-namespace-rows` / `all-namespace-row-by-name`,
   instead of only the interpreted selection.
2. **Arm at evaluation.** `evaluate` calls `install-function-contract!` on the live Var right after
   `definition-row` validates a `:seon.fn/spec`. The commit's later install re-wraps idempotently
   (`instrument/wrap-interpreted` unwraps `original-interpreted`).
3. **Gate.** `evaluate` refuses a reader event with `:seon.fn/sym` and no `:seon.fn/spec` before
   evaluation, using the new contracted `missing-contract`. In `eval_test.clj`, five SCI
   definitions in other behaviour tests gain contracts.
4. **Namespace-identity walk.** `derive-base-ctx` records the acquired program's
   `::program-namespaces` (generation plus namespace map) in the program snapshot. Forks, copies
   and regenerations already copy that snapshot. `turn-interns` walks only namespace maps that are
   not `identical?` to it, and only when the recorded generation differs from the current one.
   SCI `assoc-in`s every new generation-owned Var (`evaluator.cljc:25-49`, `utils.cljc:362-379`),
   so this walk is a superset of the old one. Without a reference, it walks everything, as before.
   `intern-values` is deleted, and the test that used it reads the three Vars through `sci/resolve`.

Net: `eval.clj` +58 / −35 (net +23), `eval_test.clj` +8 / −6, new `entry_gate_test.clj` 65 lines.

## Evidence

The default JVM runs archive `ce7384682`, and hook publication is off, so working-tree edits cannot
reach it. The proof therefore uses two scratch roots built from the same `git archive` source,
with `reference-code` and `target` linked:
- mine: `tmp/fix-entry-src` → `tmp/fix-entry-root`;
- parent: identical except for HEAD's `eval.clj` / `eval_test.clj`, `tmp/fix-entry-parent-src` → `tmp/fix-entry-parent-root`.

Probes ran in SCI through MCP on branch `fix-entry-eval`, namespace `scratch.fix-entry`.

| probe | parent / default before | mine after |
|---|---|---|
| `(ns-aliases 'seon.note)` on a fresh branch | `{}` (default and parent) | `{db, render.value, repl, route, schema.edn, str}` |
| `(thrice "a")` after contracted `defn`, no commit | `ClassCastException`, no ex-data (default) | ex-info `:seon.instrument/check :input`, "refused argument 0 … expected an integer" |
| `(defn thrice "No contract." [x] …)` redefining armed `thrice` | admitted `:ok` (default) | refused, 4 ms / 0.63 MB; `(thrice 2)` still 6 (armed root intact) |
| `(+ 1 2)` `:seon.eval/allocated-bytes` / duration | 9,473,584 B / 25 ms (parent); 9,574,400 B / 5 ms (default) | **702,232 B / 2 ms** |
| `turn-interns` alone (JVM probe on the branch ctx, 20 runs after 5 warm, 450 namespaces) | old algorithm 4,886,767 B | **99,296 B**; equal keys and values |
| `derive-base-ctx` on the root's default db, 3 runs | 526 / 860 / 428 ms; 286 / 272 / 267 MB | 513 / 403 / 428 ms; 297 / 285 / 274 MB (+3%, within noise) |
| first MCP evaluation on the new branch (acquisition) | 1,181 ms / 783 MB | 946 ms / 715 MB |

`derive-base-ctx` and first acquisition run over one second, from 403 ms to 1.2 s. That cost is
pre-existing (see the efficiency report, root causes 1–3); these changes do not add to it.

Tests (`bin/test-check --root tmp/fix-entry-root default …`):
- `--ns seon.sci.entry-gate-test`: run `8a952a0fa149`, 3 executed, 12 pass / 0 fail, 7,528 ms.
- `--ns seon.sci.eval-test`: the request's result recording refused on a pre-existing red
  (`a-configless-database-refuses-contract-installation`, a 9.6 MB actual), so the tally came
  back as "check unavailable". The parent root refused the same way. I parsed per-member
  results from the returned data (`tmp/fix-entry-parse.py`) and ran the remaining 47 members one
  `--test` at a time (`tmp/fix-entry-rest.log`):
  - Before the test edits, the gate turned three tests red. After contracts were added, both
    re-run ones are green: `a-previously-defined-function…` (5/0) and `a-selected-render…` (8/0).
    `both-entrances…` stays red at 16 pass / 2 fail, the same counts as the parent.
  - Every remaining red has the same counts on the parent, or a cause outside this diff:
    - the same counts on the parent: the nine reds in the parent's parsed list, plus
      `database-provenance…` at 20/7, run singly on each root;
    - a cause outside the diff: fixture writes refused (`:seon.agent/branch`,
      `:seon.program/definition-digest`, the wrong-branch writer); `desired-rows` indexing all of
      `src` (39–62 s); `instrument/apply!` no longer returning `:db-after`; a nil message in
      `unloadable-host-namespace…`; a `:jvm-fallback` load state; timing overruns of 5.5–6.2 s on
      a loaded scratch JVM.
- `--ns seon.cluster.reply-test`: 18 executed, 124 / 0, green.

Each commit's `eval.clj` was loaded with `load-file` in the scratch JVM (`tmp/fix-entry-stages/s1..s4.clj`):
all four stages compile and load. clj-kondo reports 0 errors on the final file.

## Verification limits

- Default was not touched. **RESET NEEDED** is not required for correctness, but the live JVM
  keeps the old behaviour until root restarts it from the checkout.
- There is no parent run of the whole `seon.sci.eval-test` namespace, because recording refused
  on both roots. The comparison is by member and by cause.
- The allocation numbers come from a scratch root whose program equals HEAD. Default's
  9.57 MB before-figure is from archive `ce7384682`.
- `load-file` of the stages in the scratch JVM replaced its armed `seon.sci.eval` Vars. That root
  is disposable and was stopped afterwards.
