---
type: landing
lane: contract-cost-opus
date: 2026-09-26
status: fixed in the malli fork and pinned; adopted at the next restart (RESET NEEDED); per-eval after-numbers not yet measured
---

# Landing: where the armed-contract cost goes, and the fix (2026-09-26)

The owner ruled that every contract stays armed. Each armed Var is the wrapper `seon.instrument/arm-var!` builds
(instrument.clj:739), around Malli's `m/-instrument` output (malli `core.cljc` `-instrument-f`, for `:=>` and
`:function`). The validators are compiled once when the wrapper is armed. This lane measured what a *passing*
call pays and removed the allocation in Malli's success path. No contract was disarmed, and no seon source
changed.

## Broken first

1. **Not adopted yet. RESET NEEDED.** The fix is in the malli fork (`43c6dca8`), and seon pins it at
   `c0c39ec5c`. Default loaded Malli at boot, and lanes may not redefine default's Vars, so the per-eval
   after-numbers below are projected from measured per-call costs, not re-run.
   Root runs after the restart:
   - the probe `tmp/contract-cost-opus/probe.clj`: `(contract-cost-probe/call-counts "default" "<branch>" (vec (repeat 10 "(inc 1)")))`, plus the defn batch shown in §Numbers;
   - one JFR window, then `python3 tmp/contract-cost-opus/agg.py <file>.jfr`.
2. **The malli fork commit is not pushed.** Lane rule: never push. Root pushes `seantempesta/malli`
   `seon-ref-scope` at `43c6dca8`.
3. **A defn is not sub-second, and contracts are not the main cause.** A branch agent's contracted
   `defn` measured 1.72 s p50, 1.57 GB allocated JVM-wide and **182,000 armed calls per form**. Most of those calls
   are whole-projection walks, for example `seon.program/source-family?` 38,000 times per defn. Filed as task
   `0d8be4a76ab3` in `resources/seon/tasks/seon.program.edn`; those files are not mine.
4. **Default restarted twice under the lane** (14:38 and 14:41 local, pids 59671 → 68654 → 70849, by root). The
   first probe's transport closed with the old JVM. The branch was recreated on the new JVM. The restarts were
   not caused by the probe: the new JVM had started at 14:38:49, during that call.

## Where the cost went (measured, default pid 70849 at a34078273)

### Per armed call (JVM mode, 50-200k calls each, allocation per call)

`seon.profile/adder?` has a 1-arg `[:=> [:cat :any] :boolean]` contract. Its armed call costs **231 ns and
1,000 B**; the original costs 23 ns and 0 B. The table decomposes the armed call; the components overlap, so they
do not sum exactly.

| layer | ns | B | owner |
|---|---:|---:|---|
| seon `arm-var!` outer fn: `*compiling-contract*` deref, `profile/timed` (2× nanoTime, LongAdders), apply | ~100 | 0 (escape-analysed) | instrument.clj:776-778, profile.clj `timed` |
| Malli `-instrument-f` glue: `(vec args)` + `apply` | 51 | 520 | core.cljc `-instrument-f` |
| input `:cat` via `re/validator`: `CheckDriver`, `ArrayDeque` (`make-stack`), `Cache`, and a continuation plus a `seq`/`rest` per element | 45 (1 arg) to 224 (3 args with a map) | 360-528 | regex.cljc:571 `validator` |
| guard: `(valid? :guard any? [args value] …)` on every call, even with no guard | 18 | 64 | core.cljc `-instrument-f` |
| `seon.profile` bookkeeping | inside the first row | 0 | — |

Profiling is not the allocation cost; it costs about 40 ns of the outer wrapper and nothing else.

### Per trivial agent form (`(inc 1)`, 10 forms on branch `contract-cost-opus`, via `seon.cluster.agent/evaluate-source!`)

- p50 38-40 ms on a quiet machine and 168 ms under load.
- 55-57 MB allocated JVM-wide per form, 160 MB under load.
- **~6,600 armed calls per form.** The largest are:
  - `seon.db/database-value?`: 1,600;
  - `query-binding-values`: 560;
  - `schema-database`: 460;
  - `transaction-report-datom?`: 360;
  - `projection-cache-value`: 290;
  - `db/q`: 53.

JFR over the same 10 forms (`jfr/before.jfr`, classified by `agg.py`):
- Leaving out one foreign prepl evaluation, which accounted for 32 GB of the window, armed validation is about 22%
  of allocation and about 10% of CPU (JVM-wide 3.0% and 3.1%).
- **75% of armed-validation allocation is `malli.impl.regex/make-stack`**, the per-call driver.
  `malli.core/-instrument-f` glue is the next largest share.

### Nested predicates

65 of the projection's 94 named predicates are armed Vars (`:seon.schema.projection/predicate-functions`,
schema.clj:1334, with Vars kept so that redefinition takes effect). Validating a value calls each named predicate
through its own armed wrapper. So each predicate call inside validation paid the regex driver again, for an
`[:=> [:cat :any] :boolean]` contract. The fix makes such a call about 150 ns and 59 B. Disarming the predicates was
rejected: the owner ruled that contracts stay armed.

## Owner's two cache questions, answered with measurements

1. **Compile once per schema identity: already true on the success path.** `-instrument-f` compiles
   `validate-input`/`validate-output` once, at arm time (`compiled-wrapper`, instrument.clj:591, runs only in
   `arm-var!`/`wrap-interpreted`). Registry schemas share their cached validator (`-cached … :validator`, fork
   `a89ebef0`). The JFR window had:
   - no `m/validator`, `m/explain` or `m/validate` frames under a wrapper on the success path;
   - `m/schema` and `-into-schema` samples totalling 10 MB, all from `seon.schema/structural-schema`
     (schema.clj:1377), which is a deliberate compile of a syntax check, not per call.

   Explanation runs only on the refusal path (`violation` → `m/explain`). No cache was added; nothing is recompiled.
2. **Memoize re-validation by value identity: not built.** After the fix, a passing check of a small value costs
   50-150 ns and 0-60 B. A weak identity cache lookup (Caffeine `weakKeys`, identity-compared) costs about the
   same, and a miss allocates a reference. So the memo can only help validators that walk large values.
   - Ceiling: the whole remaining armed-validation leaf work, below 3% of JVM CPU in the window, and less after the
     fix.
   - Hit rate: not measured. Counting identity repeats per schema needs a counting wrapper on default's armed Vars,
     which lanes may not install. If root wants the number, it is one scratch instrumented copy of `db/q`'s
     callees in a test.

## Fix (malli fork `43c6dca8`, pin `c0c39ec5c`)

- `-cat-items-validator` (core.cljc, beside `regex-validator`): a `:cat` whose children are all single items (no
  nested regex operator and no `:ref`, which the regex path refuses as potentially recursive) validates by position:
  - `nth` for vectors;
  - one `seq` walk otherwise.

  A passing value allocates nothing. The explainer, parser and every other sequence operator keep the regex
  engine. This also speeds every `m/validate` against an item `:cat` elsewhere in seon.
- `-instrument-f` (`:=>`) checks the rest args as given (a counted `ArraySeq`, or `[]` for zero arguments). It builds
  the `(vec args)` that every report carries only when a report is made. It checks the guard only when a guard is
  declared.
- Net lines: malli +36/−9 plus a new 48-line test. seon: 0 source lines; pin +1/−1.

### Before → after per call

Measured in a bare JVM (`-Xmx512m`, `tmp/contract-cost-opus/bench/bench.clj`), comparing old `core.cljc` against
new. The same wrapper classes are used, without seon's outer 100 ns.

| call | before | after |
|---|---|---|
| 1-arg predicate `[:=> [:cat :any] :boolean]` | 334 ns / 704 B | 149 ns / 59 B |
| 3-arg `[:=> [:cat map kw [:or :nil :int]] map]` | 521 ns / 1,312 B | 351 ns / 304 B |
| 2-arity `:function`, 2 args | 441 ns / 1,176 B | 323 ns / 232 B |
| validator `[:cat map kw [:or :nil :int]]` | 224 ns / 528 B | 80 ns / 24 B |

In the live JVM, the scratch item validator measured 86 ns / 0 B against the regex validator's 247 ns / 504 B on
the same schema.

### Projected per form (not re-run; see Broken 1)

- **Trivial form:** about 6,600 armed calls × about 0.8 KB saved ≈ 5-6 MB less out of 55 MB (≈10%), and about
  1-1.5 ms less CPU.
- **defn:** 182,000 calls × about 0.8 KB ≈ 150 MB less out of 1.57 GB, and about 30-60 ms less. The rest of the
  defn cost is task `0d8be4a76ab3`.

## Verification

Malli suites in a bare JVM, all green:
- `cat-items-test` (new), `core-test`, `instrument-test`, `ref-scope-test`, `registry-test`, `util-test`,
  `error-test`, `parser-test`, `transform-test`;
- 164 tests, 15,577 assertions, 0 failures.

The regression is `malli.cat-items-test`:
- `an-item-cat-validates-exactly-as-its-regex-explainer`: validator = `(nil? explain)` over vectors, lists, lazy
  seqs, short, long, empty, nil, maps, strings and sets, for plain, registry-pointer and `:schema`-wrapped items.
  Splicing of a nested `:cat` pointer and `[:? …]` is kept, and `:ref` is still refused;
- `an-instrumented-call-reports-its-arguments-as-a-vector`: zero-argument calls, and input, output and guard
  reports carry vector `:args`.

`clj-kondo` on the changed malli files reported 0 errors, and no new warnings in the edited regions. No seon
`src`, schema or config file changed, so the contracts-compile and schema-EDN static checks have no new input.
Root runs them after the restart together with `bin/test-check default --ns seon.instrument-test`, because it
reads Malli report data.

## Not changed and why

- `arm-var!`'s outer fn (~100 ns, 0 B): the timing cell is the owner's armed profile. `*compiling-contract*` is the
  refusal path's recursion guard.
- `seon.profile`: no allocation per call; it is not a cause.
- Status polls validate every profile cell through armed `observation` and 4 armed predicates each: about 2,000
  cells per poll, 3,000-6,000 of the ~6,600 calls in a window. After the fix this is cheap; it was not changed.

## Files

- `reference-code/malli/src/malli/core.cljc`, `reference-code/malli/test/malli/cat_items_test.clj` (fork
  `43c6dca8`);
- pin `c0c39ec5c`;
- `resources/seon/tasks/seon.instrument.edn` (task `96ac997dcf2f` restated);
- `resources/seon/tasks/seon.program.edn` (+`0d8be4a76ab3`).

Evidence in `tmp/contract-cost-opus/`:
- `probe.clj`, `agg.py`, `stacks.py`;
- `jfr/before.jfr`, `before-agg.txt`;
- `bench/`.

Branch `contract-cost-opus` is retired, and the scratch namespace `contract-cost-probe` was removed from default.
