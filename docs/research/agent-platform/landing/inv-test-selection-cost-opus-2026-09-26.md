---
type: landing
status: "call-reach selection committed (20a87b1d1, unadopted); survey done; docstring digest needs a ruling (three options); reuse re-measure blocked by default's OOM"
lane: inv-test-selection-cost-opus
created: 2026-09-26
authority: owner rulings 2026-09-26 (gate by call reach; docstring edits select nothing; digests decide change); docs/seon/issues/a-first-party-redefinition-gates-on-saturated-reach-interpreted-in-sci-and-is-refused.md
---

# Landing: test selection by call reach, and what a request costs (2026-09-26)

A test used to be selected when the reverse walk found it through any edge, to any depth. The walk
followed `:seon.fn/calls`, `:seon.fn/references` (quoted symbols and values), a file's unresolved
mentions and subjects. The owner ruled that the walk must follow calls only, to one caller level. In
20a87b1d1, `seon.fn/gate-set` and the `gate-sets` map arity select two sets of tests: those that call
the changed definition, and those that call one of its direct callers. They follow `:seon.fn/calls`,
the declared invocation edges and `:seon.test/subject`. The analyzer now tells a quoted symbol (a
mention) apart from a var usage (a call).

## Broken first

- **RESET NEEDED. Default died of OutOfMemoryError at about 16:10Z.** The heap dump is
  `data/heap/java_pid19859.hprof`.
  - This was during my SCI probe `my.test/check` on branch `inv-tsc-1`, which timed out at 60 s.
  - Its log also shows a `:panic` core fault just before: `seon.fn/analyze-forms` gave "clj-kondo
    holds no analysis of effect" (`fn.clj:1011`).
  - I have not attributed the OOM. The dogfood-newcomer lane was also driving default. The unbounded
    recursion that the heap lane proved (`host_test.clj:305` quoting a symbol) runs through exactly
    the edge that 20a87b1d1 removes, but default did not have the commit loaded.
  - Branch `inv-tsc-1` could not be retired (no JVM). Root: retire it after restart.
- **20a87b1d1 is committed, not adopted, and not tested.** It changes `src/seon/fn.clj`,
  `src/seon/fn/analyzer.clj`, `test/seon/fn_test.clj` and
  `test/fixtures/call_graph_fidelity/references.txt`.
  - Root granted fn.clj. analyzer.clj was free in the ledger after the 15:45Z release; it is a
    one-key change the fn.clj rule depends on.
  - **Land them together.** The fn.clj rule "not quoted ⇒ call" without analyzer.clj's
    `:reference` key would classify quoted symbols as calls.
- **The stored graph must be re-analyzed before value uses count as calls.** Until every file is
  re-indexed with the new analyzer, stored `:seon.fn/calls` hold only arity/var-quote usages. So
  selection is calls-only (the survey below), and value uses (`apply`/`partial`/`comp`) stay in
  `:seon.fn/references`, unselected.
  - The recursion fix does not depend on re-analysis.
  - The full-program union of calls and references is unchanged by the re-classification. It only
    moves edges between the two attributes, so deletion refusals keep the same edges.
- **The docstring-only change still selects tests (item 2): this needs a ruling (options below).**
  - The definition digest includes the docstring: `:seon.fn/doc`, and the source text that carries
    it (`seon.program/definition-digest`, `src/seon/program.cljc:388`; excluded attributes at
    `:372`).
  - After 20a87b1d1, a doc edit to `my.issue/status` selects 0 tests. That is only because nothing
    calls it. A doc edit to a function that has callers still selects its call reach.
- **Coverage rules and selection now differ.** `seon.fn/test-reach-rules`
  (`currently-failing-functions`, `functions-without-tests`) still reach through references and
  file mentions, transitively. I removed the fn_test assertion that tied the 1-hop rule to the gate
  set. Aligning them is a follow-up; it is not done.
- **Not verified:**
  - I ran no test request; default runs the committed archive, and it is down. The scratch-copy
    proofs below are unarmed.
  - `[:vector :qualified-symbol]` held on four real outputs. `seon.contracts-compile-test/check` over
    fn.clj and analyzer.clj returned `[]`.

## 1. Survey: how many tests reach one function

**Source:** default, basis 536871269, 5,264 function rows, 2,242 test rows, 67,135 `:seon.fn/calls`
datoms, 34,533 `:seon.fn/references`, 0 `:seon.test/subject`.

**Why static calls, not recorded reach:** no test records exercised definitions.
- `:seon.test/reach` holds 0 datoms.
- `:seon.test.member/reach-digest` digests the static closure (`runner/reach-entry`).
- So I used the stored `:seon.fn/calls` facts, and compared them against the current union walk.

**How it was computed:** one in-memory reverse index, 146 ms to build, all 5,264 rows in 4.6 s.
The script is `tmp/inv-test-selection-cost-opus/survey.clj`. Tests are leaves: a walk never passes
through a test.

Per function, over all 5,264 (callers are functions only; tests are counted separately):

| measure | mean | median | p90 | p99 | max | zero |
|---|---:|---:|---:|---:|---:|---:|
| (a) tests calling it directly | 2.4 | 0 | 4 | 25 | 935 | 3,265 |
| (b) direct callers | 1.8 | 1 | 3 | 17 | 413 | 1,839 |
| (b) callers to depth 2 | 6.7 | 2 | 11 | 99 | 733 | 1,839 |
| (b) callers, full transitive | 118.7 | 6 | 295 | 1,617 | 2,058 | 1,839 |
| (c) tests: it + direct callers (**owner ruling, now installed**) | **10.3** | **1** | **16** | **187** | 1,279 | 2,035 |
| (c) tests: it + callers to depth 2 | 27.5 | 3 | 44 | 543 | 1,337 | 1,463 |
| (c) tests: calls, full transitive | 296.1 | 12 | 1,425 | 1,688 | 1,884 | 1,076 |
| before: union walk (calls + references + file mentions + declared, full) | 667.8 | 167 | 1,582 | 1,877 | 2,023 | 233 |

Variants of the one-caller level (the ruling), same population:

| edges at one caller level | mean | median | p90 | p99 | max | zero |
|---|---:|---:|---:|---:|---:|---:|
| calls + declared + handlers | 10.3 | 1 | 16 | 187 | 1,279 | 2,035 |
| + file unresolved mentions | 10.6 | 1 | 16 | 187 | 1,331 | 2,023 |
| + all references (upper bound after re-analysis moves value uses into calls) | 12.9 | 2 | 20 | 221 | 1,358 | 1,285 |

**Which depth the survey supports.** One caller level (the ruling) keeps most changes to a few tests:
median 1, p90 16. Depth 2 is still contained (p90 44). The full transitive closure is not: calls
alone reach p90 1,425. That is because internals of the fixture hubs (`seon.db/db`, `transact!`, `q`)
are, truthfully, exercised by nearly every test.
- **The price of one level:** a change at distance three or more from a test does not select it.
  That is the owner's trade. The reach digest (below) still covers the full closure, so a reused
  green is never vouched for by a partial closure.

**The 20 worst functions at the ruled depth**, and why. Callers are shown as `[caller, its direct
tests]`.

| function | direct tests | direct callers | tests (ruled) | depth 2 | full | union before | largest callers |
|---|---:|---:|---:|---:|---:|---:|---|
| seon.test-support/with-database | 935 | 43 | 1,279 | 1,286 | 1,286 | 1,380 | itself 935, web-test/with-server 48, cluster.turn-test/with-cluster 45 |
| seon.test-support/with-branched-database | 0 | 1 | 935 | 1,279 | 1,286 | 1,380 | with-database 935 |
| seon.test-support/with-fresh-database | 1 | 1 | 935 | 1,279 | 1,286 | 1,380 | with-database 935 |
| seon.db/db | 590 | 204 | 906 | 1,337 | 1,494 | 1,878 | fork-cluster-ctx 219, seed-cluster! 150, config/apply! 60 |
| seon.test-support/transacted! | 534 | 128 | 862 | 904 | 905 | 907 | seed-cluster! 150, with-server 48, with-plan 21 |
| seon.db/transact! | 187 | 106 | 811 | 1,093 | 1,456 | 1,619 | transacted! 534, itself 187, with-cluster 45 |
| seon.db/refused-read | 0 | 5 | 791 | 1,197 | 1,614 | 1,878 | db/db 590, db/q 354 |
| seon.db/current-database-value | 0 | 12 | 777 | 1,156 | 1,614 | 1,878 | db/db 590, db/pull 370 |
| seon.db/q | 354 | 413 | 728 | 1,077 | 1,548 | 1,876 | test-support/agent-tx 140, turn/next-agent-work 38 |
| seon.db/connection-object? | 0 | 5 | 692 | 1,174 | 1,593 | 1,880 | db/db 590, transact! 187 |
| seon.db/resolve-database-value | 1 | 3 | 691 | 1,206 | 1,624 | 1,878 | db/db 590, transact! 187 |
| seon.db/call-with-custody | 18 | 31 | 647 | 1,008 | 1,458 | 1,574 | transacted! 534, seed-cluster! 150, sci.eval/evaluate 60 |
| seon.db/pull | 370 | 239 | 640 | 971 | 1,511 | 1,876 | seed-cluster! 150, program-fn-row 69 |
| seon.test-support/offending-fixture-row | 0 | 1 | 534 | 862 | 905 | 907 | transacted! 534 |
| seon.db/dependency-error | 0 | 9 | 487 | 1,195 | 1,624 | 1,878 | db/q 354, transact! 187 |
| seon.schema/handed-projection | 324 | 37 | 481 | 1,157 | 1,720 | 1,942 | instrument/apply! 31, config/effective 31 |
| seon.test-support/execution-handle | 51 | 19 | 450 | 1,185 | 1,328 | 1,380 | fork-cluster-ctx 219, seed-cluster! 150 |
| seon.db/carried-projection | 98 | 108 | 445 | 1,221 | 1,621 | 1,876 | test-support/namespace-row 74, sci.eval/evaluate 60 |
| seon.test-support/fork-cluster-ctx | 219 | 30 | 423 | 435 | 435 | 436 | itself 219, with-server 48, with-cluster 45 |
| seon.db/pull-call | 0 | 3 | 375 | 649 | 1,514 | 1,876 | db/pull 370, pull-many 14 |

Every row in the worst 20 is a fixture helper or a `seon.db` read/write primitive. The tests really
run them. These are the changes whose gates should be large.

**The dogfood case.** `my.issue/status` has no callers and no tests: it selects 0 (was 1,522). The
old walk reached it this way:
1. `seon.issue.opening/status-form` holds the quoted form `(list 'my.issue/status …)`, which is a
   `:seon.fn/references` edge.
2. From there, references carried the walk transitively to 1,522 tests.

The heap lane's recursion (`host_test.clj:305` quoting `'my.agents.host-probe/proposed-square`) is
the same class, with the test itself as the mentioner.

**Current selector cost.** `gate-set` for `my.issue/status` took 72 ms. It builds the whole identity
map and all declared relations per call. The call-reach copy measured:

| seed | tests | time |
|---|---:|---:|
| `my.issue/status` | 0 | 0.3–0.7 ms |
| `seon.db/q` | 728 | 12 ms |
| `test-support/with-database` | 1,281 | 12 ms |

## 2. The cut (20a87b1d1)

- **`src/seon/fn.clj` `call-reach` (new, private).** Seeds, then their callers (`:seon.fn/calls` and
  the declared invokers, one avet read per target), then the tests that call either set or name one
  as `:seon.test/subject`, plus any seed that is itself a test.
  - Identity names come from one bound query per step.
  - A refused read returns the owner's refusal unchanged.
  - `gate-sets` (map arity) and `gate-set` call it. So `seon.test/select`, `seon.test/reaching`,
    `turn/gate-function-install` (the install gate) and `my.program/tests-reaching` all change with
    no caller edit.
- **The positional `gate-sets` arity is unchanged.** It keeps the conservative closure (calls +
  references + declared + file mentions, full depth) because `seon.test/destructive-reach`
  (`src/seon/test.clj:374`) reads it. Destructive isolation must not shrink.
  - The name now carries two relations. When test.clj is free, move destructive-reach to
    `reverse-closure` and delete the positional arity and `gate-sets-in`'s non-union branch
    (about −15 lines).
- **Producer.** `call-target` becomes: every var usage except a quoted symbol.
  `references-by-caller` becomes: quoted symbols, plus every usage whose caller is not a
  declaration.
  - `analyzer/var-usage` keeps kondo's `:reference` marker, which `attributed-usages` sets on
    `:symbols` entries (`src/seon/fn/analyzer.clj:583-589`).
  - kondo's own data, checked on the fixture:
    - value uses and syntax-quoted bodies are `var-usages` with no `:arity`;
    - quoted symbols appear only in `:symbols`
      (`reference-code/clj-kondo/src/clj_kondo/impl/analysis.clj:37`).
- **Regressions (`test/seon/fn_test.clj`):**
  - **Rewritten.** `value-uses-are-calls-and-quoted-symbols-select-nothing` was
    `unresolved-call-shapes-preserve-reference-edges-and-reach`. It asserts:
    - `apply`/`partial`/`comp`/macro targets are calls that select their callers' test;
    - `requiring-resolve 'x/y` is a reference that selects nothing;
    - a new fixture test that only quotes `apply-target` is not selected.
  - **Flipped.** `cross-file-implementations-keep-edges-or-widen`: a file's unresolved mention no
    longer widens.
  - **Updated.** The scoped fixture: `(partial target/target)` is a call.
  - **Unchanged; they should hold** (checked by reading the new walk against them):
    - `gate-set-walks-only-indexed-callers-once` (cycle, distinct avet reads, one declared query per
      operation, edge removal);
    - `a-refused-reference-read-refuses-gate-set-derivation`;
    - `tests-reaching-follows-calls-and-explicit-subjects`.
- **Size.** fn.clj +52/−10, analyzer.clj +1/−1, tests +49/−41, fixture +2.

**REPL proof (unarmed scratch copy `inv-tsc.reach`, real default data).**
- Call-reach equals the survey's one-level set for 600 random functions: 0 mismatches.
- The host-probe symbol selects `[]`; the old gate-set gave 1.
- A test seed returns itself.
- An absent symbol gives `[]`.
- The fixture classification, from kondo output through the new rule:
  - `applied`, `partialled`, `composed` and `expanded` call their targets;
  - `reaches-references` calls all five functions;
  - `resolved` only references `resolve-target`.

## 3. Docstring-only edits: three priced options (a new decision; nothing implemented)

This is the change the ruling "digests decide change" needs. The comparison sites are:
- `seon.test/changed-definition-symbols` and `seon.test/changes` (test.clj, held by the heap lane);
- `turn/gate-function-install` (turn.clj, held);
- `runner/reach-row`'s leaf (mine).

1. **★ Derive an executable digest from the stored row (simplest viable).**
   - `seon.program/executable-digest` is the same `definition-digest` owner. Its input is the
     canonical row with `:seon.fn/doc` and `:seon.fn/doc-order` removed, and `:seon.fn/source`
     replaced by its read form without the docstring. The read uses edamame, the reader dependency,
     with `:read-cond :preserve`. The resolver context stays the same.
   - `seon.db/definition-digest-of` already re-derives every stored digest from `declaration-value`
     (7,040/7,040, `src/seon/db.clj:4270-4300`). The function is memoized by definition digest in a
     core.cache LRU.
   - The comparison sites call it instead of reading `:seon.program/definition-digest`.
   - Cost: about 40 lines in program.cljc plus about 15 across three callers. One parse per changed
     row: microseconds to milliseconds. A cold reach index parses each reached row once, estimated
     0.5–1 s per JVM.
   - Sacrifice: the digest is computed, not stored, so the writer does not check it.
2. **Store `:seon.program/executable-digest` beside the definition digest.**
   - Producers: `seon.fn` `:258`/`:834`, the SCI row producer, and the writer's
     `definition-digest-of` check. Plus a schema key.
   - Readers read an attribute, at no parse cost.
   - Cost: about 60 lines across four owners plus re-analysis of every row on adoption.
   - Sacrifice: a new stored fact and a republish.
3. **Keep documentation out of the definition.** Store the source without the docstring and rebuild
   it on write-back.
   - Cost: hours; it touches exact-span write-back.
   - Not recommended.

## 4. Reuse keyed by digests; the one-test check

**Reuse works for an unchanged program.**
- `seon.test/run` for a named identity with recorded green (`call-preparation-test/
  a-second-cluster-compiles-from-its-own-facts`) answered in **36 ms**: reused, 0 executed.
  Selection took 33 ms of that (`select` 31–34 ms, `reach-digests` 6 ms).
- That is 6bb96a6a9 (the fix-test-request-cost patch, applied): a named change no longer blocks
  reuse.

**The changed-program check: not re-measured.** The SCI probe (`defn` + `deftest` +
`(my.test/check {})` on `inv-tsc-1`) timed out at 60 s and default then died of OOM (see Broken).
- The 2026-09-26 numbers in `fix-test-request-cost-2026-09-26.md` still stand:
  - 1.5–1.6 s changed;
  - 0.12–0.16 s reused;
  - about 1 s of it is `clean-request-handle`: an overlay write of about 0.5 s and a fresh SCI
    acquisition of about 0.45 s per request commit.
- Root measured `my.program/prepare-merge!` of a one-test delta at 4.06 s: `seon.test/run` 2.85 s
  for 1 member, `candidate-gate!` 3.24 s.
- The recommended cut is that landing's option 1: fold the overlay into the checkpoint keyed by
  program content, so a request is `branch!` plus a hit on the base-context memo. It is in test.clj
  (held).

**The reach digest after the ruling.** It keeps the full static closure (conservative; it only costs
reuse misses). Its leaf is `:seon.program/definition-digest`, so a docstring edit invalidates reuse
too. Option 1 or 2 above changes that leaf in `runner/reach-row`. I made no runner.clj edit
until the ruling.

## 5. Why `seon.call-preparation-test` took 46.8 s for 27 members

Reproduced: 46,465 ms, 27 executed, all green (run `3d8c7f97deb4`).

Per-member timings from the run result, summed:

| phase | total | per member |
|---|---:|---:|
| acquire (branch + forked context) | 781 ms | ~29 ms |
| resolve | 165 ms | ~6 ms |
| **body** | **40,817 ms** | 369–3,196 ms, mean 1,512 ms |
| release | 240 ms | ~9 ms |
| request floor (select, admit, record) | ~4.5 s | once |

**The harness is not the cost.** It spends about 45 ms per member. The members run serially, and
each body averages 1.5 s.

Inside the bodies, `with-database` → `with-branched-database` → `run-database-body` holds about
35 s. The branch itself costs about 40 ms: 2,669 against 2,629 ms in the slowest member.

A measured producer inside the bodies:
- `schema/declaration-projection` over the whole population (3,482 forms) costs **296–304 ms per
  call**, unmemoized, even for an identical input. `schema/build-projection` costs 349 ms.
- The file calls `(projection)` 29 times and `synthetic-population`, which builds a projection, 10
  times. That comes to about 12 s of the 40.8 s, rebuilding the same projection.
- The owner fix is to memoize `declaration-projection`/`build-projection` by their input forms
  (seon.schema; not my file), or to have the test take the member's carried projection plus
  `:sample/marker` once per namespace.

The remainder is fixture writes (`transacted!` through the checked writer) and the call-preparation
operations under test. I could not force re-execution to profile deeper: `seon.test/run` reuses the
green, and it exposes no key that disables reuse.

## Root runs (serially, after restart and adoption of 20a87b1d1)

```
bin/test-check default --test seon.contracts-compile-test/every-declared-contract-compiles-against-the-packaged-projection
bin/test-check default --ns seon.fn-test
bin/test-check default --test seon.test.accretion-test/one-gate-set-query-includes-edges-subjects-and-pending-tests
bin/test-check default --ns my.program-test
bin/test-check default --ns seon.test-reaching-test
bin/test-check default --ns seon.test.selection-test --include-long
bin/test-check default --test seon.cluster.host-test/an-agents-check-runs-the-tests-reaching-its-change
```

Then, as an agent on a branch:
- a docstring-only `defn` of `my.issue/status` should gate 0 tests;
- `(my.program/tests-reaching {:seon.program/subject 'seon.db/q})` should give about 728 tests.

After a full re-analysis (nuke or re-index), rerun the survey script. Expect the one-level mean to
move from 10.3 toward the 12.9 upper bound, as value uses become calls.

## Files

- Committed:
  - 20a87b1d1: `src/seon/fn.clj`, `src/seon/fn/analyzer.clj`, `test/seon/fn_test.clj`,
    `test/fixtures/call_graph_fidelity/references.txt`.
- Scripts:
  - `tmp/inv-test-selection-cost-opus/survey.clj` (survey);
  - `tmp/inv-test-selection-cost-opus/call_reach_scratch.clj` (proof copy).
- Released on commit: src/seon/fn.clj, src/seon/fn/analyzer.clj, test/seon/fn_test.clj.
- Still held: src/seon/test/runner.clj, test/seon/test/selection_test.clj,
  test/seon/test/runner_test.clj. There are no edits yet: they wait for the docstring ruling.
