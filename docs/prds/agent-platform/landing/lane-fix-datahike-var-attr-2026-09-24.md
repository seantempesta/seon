---
type: landing
status: partial
lane: fix-datahike-var-attr
created: 2026-09-24
issue: docs/seon/issues/datahike-bound-attribute-variable-scans-the-store.md
---

# Lane fix-datahike-var-attr: index path for a bound variable attribute (2026-09-24)

What exists: Datahike's planned executor reads one relation-driven slice per
pattern (`scan-datoms`, and the fused direct path `execute-group-direct`). A
variable attribute bound upstream with a free value had no index path, so it
read all of EAVT. The fix composes the executor's own mechanisms. It adds one
AEVT slice per distinct bound attribute, and the existing value probe now also
filters a variable attribute. Seon's caller-side grounding
(`seon.fn/ground-attribute-rules`, commit `35585cac6`) can then be deleted.

## Broken or not landed

- **The Seon retirement is not committed.** Retiring
  `ground-attribute-rules` and `ground-test-reach-rules` also needs two lines in
  `test/seon/fn_test.clj`. They call `#'seon.fn/ground-test-reach-rules` at
  `:2726` and `:2941`, and that file is not in this lane's row. Committing
  `fn.clj` alone would stop `seon.fn-test` from compiling. The complete
  retirement is `tmp/fix-datahike-var-attr/retire-ground-attribute-rules.patch`
  (`fn.clj` +23/−77, `fn_test.clj` +2/−2). `git apply --check` passes on HEAD.
  It is proven below on a snapshot. The working tree was left at HEAD. To land
  it, grant `test/seon/fn_test.clj`, or apply the patch and run
  `git commit --only -- src/seon/fn.clj test/seon/fn_test.clj`.
- The patch keeps the `[(symbol? ?symbol)]` guard in rule 4 on purpose. A
  bound value of another type still throws in Datahike's AVET seek. This is a
  separate defect:
  `docs/seon/issues/datahike-avet-seek-casts-a-bound-value-of-another-type.md`.
- The cache dependency of `declared-reference-edges` is still `:all`. Its
  `?attribute` comes from data, so no static plan can narrow it. The grounded
  form had 12 concrete attributes. The dependency narrows only for a keyword
  collection input (`[?edge ...]`, `[?attribute ...]`).
- The timings below were taken under load average 20–51 (other lanes' JVMs and
  Backblaze). Treat them as ratios within one run, not as absolute costs. With
  armed Seon, `unresolved-callers` took 2.5–3.2 s and `functions-without-tests`
  took 1.3–1.6 s, both over one second. Both costs are elsewhere:
  `seon.db/q`/`with-declarations` decoding added about 900 ms over raw
  `d/q`, and the scalar and grounded forms cost as much or more.
- The durations of three `seon.fn-test` members were over their 5 s bound in
  the snapshot (7.4–19.9 s, all assertions green) at load 35–51.
  `declared-function-values-contribute-edges-without-arities` was already over
  its bound on the parent (8.5 s, fix-fn-scan note). The other two were not
  compared on the parent.
- The fork's own suite has five red assertions both before and after this
  change: four find-pull dependency assertions (issue
  `docs/seon/issues/datahike-pull-find-dependencies-widen-to-all.md`) and
  `config-mismatch-does-not-open-or-alter-cache-generation` (already listed on
  2026-09-23).
- CLJS was not built. The new executor code is `#?(:clj …)`, and the
  `query.cljc` change is portable.
- **RESET NEEDED** (or `start --head`) before default runs the new fork. Default
  (pid 90471) runs archive `ce7384682`.

## Root cause (dependency, verified)

- `scan-datoms` has an index path for a variable attribute only when `?a` and
  `?v` are bound in one relation (`var-attr-avet-pairs`). Otherwise it slices
  the full EAVT (`execute.cljc`, `full`).
- The fused multi-group path passes the producer's `?a` values as a probe on
  datom field 1. Only fields 0 and 2 can seek, so it scanned EAVT and filtered.
- `pattern-probe-set` built a value probe only when the attribute was
  resolved. So `[?c ?edge ?t]`, with `?edge` and `?t` bound by different
  inputs, merged every datom of the attribute before the join applied `?t`.
- `parsed-input-environment` bound only scalar inputs, so a collection `?a`
  made the dependency plan `:all` (`query.cljc`).

## Change (fork `seantempesta/datahike` main, pushed)

- `f4614c9d`: `var-attr-bound-attrs` and `bound-attribute-slices`. `scan-datoms`
  reads AEVT once per distinct bound attribute. `execute-group-direct` does the
  same for an attribute-field probe and disables forward merge cursors, because
  the concatenated slices are not in EAVT order. Only values that can be datom
  attributes are sliced (keywords, or ints under `:attribute-refs?`), so an
  alien value joins nothing. A keyword `[?a ...]` input narrows the dependency
  to its members. Regression:
  `query-planner-test/test-variable-attribute-bound-upstream-reads-bound-slices`
  covers the direct, merge and rule paths, alien bound values, equality with
  ground patterns and legacy, and cost within 5×+2 ms of ground. It also adds
  the collection-dependency case to `query-test`.
- `620721d6`: the value probe also applies to a variable attribute, as a hash
  filter (a seek still requires a resolved attribute). Regression:
  `test-variable-attribute-and-value-bound-by-different-inputs`.
- Fork source +73 / −8 (`execute.cljc` +56, `query.cljc` +17); fork tests +87.
- Seon gitlink commits `e45e27d73` and `b626390b2`.

## Evidence

Fork JVM (`clojure -M:test`). HEAD is a `git archive` of `c79cd03a`.

| probe | HEAD `c79cd03a` | fork `620721d6` | ground/scalar |
|---|---|---|---|
| `[?r :link/attr ?a] [?e ?a ?v]`, 200k datoms, best of 5, fresh value | 12.9–19.1 ms | 0.27 ms | 0.29 ms |
| same + merge `[?e :x/tag ?t]` | 12.4 ms | 0.38 ms | — |
| same through a rule | 23.6 ms | 0.30 ms | — |
| `:in [?a ...]` | 21.1 ms | 0.19 ms | — |
| `[?c ?edge ?t]`, two collection inputs, 63k datoms | 112 ms (119 ms at `f4614c9d`) | 2.3 ms | 0.9–4.2 ms |

Focused fork suite: 22 query, rule, cache, or/not, planner and attribute-refs
namespaces (`tmp/fix-datahike-var-attr/run-ns.clj`). The HEAD tree ran the new
tests: 217 tests, 1160 pass, 6 fail (the 5 pre-existing reds, plus the new cost
assertion, which failed 1–3 times per run). Fork at `620721d6`: 218 tests,
1167 pass, 5 fail (the same pre-existing 5). Runtime was 22–83 s.

Snapshot: a `git archive` of seon HEAD with the patch and `reference-code`
linked, booted under a scratch root. The store was indexed from zero:
479,826 datoms, ready in 241,882 ms (existing issue
`from-zero-boot-takes-minutes`). The probes ran armed, in the scratch
namespace `scratch.fix-datahike-var-attr`. Each probe used a fresh value
(`d/db-with db [])`) and ran three times. The grounded oracle is
`ground-attribute-rules` copied verbatim from `35585cac6`.

| probe | plain rules, new fork | grounded oracle, new fork | equal |
|---|---|---|---|
| `declared-reference-edges` | 37–91 ms / 16.7 MB | 92–176 ms / 30.7 MB | 26 edges; per rule [10 0 3 13], each rule equal |
| tested set (raw `d/q`, test-reach rules) | 354–410 ms / 122 MB | 466–555 ms / 161 MB | 1,278 |
| `functions-without-tests` (armed) | 1,305–1,614 ms / 450 MB | — | 116 |
| `currently-failing-functions` | 248–314 ms / 178 MB | 304–395 ms / 192 MB | equal (empty) |
| unresolved rows, raw, `[?attribute ...]` | 2,520–3,643 ms / 725 MB | scalar loop 2,061–2,106 ms / 648 MB | 56 |
| `unresolved-callers` (armed) | 2,494–3,223 ms / 763 MB | — | 56 |
| capability parents `[?edge ...]`, 3 targets | 9.6–17.8 ms / 1.15 MB (355 ms / 211 MB at `f4614c9d`) | scalar loop 3.6–8.2 ms / 1.4 MB | 2 |
| `reverse-closure` for `seon.note/add-note!` (± `no.such/fn`) | 237–402 ms / 67 MB | — | 3 affected, 10 tests |

Earlier baselines from the fix-fn-scan note, on the old fork: rule edges
800–902 ms / 2.94 GB on default; parents 622 ms / 1.90 GB. On this lane's
snapshot, the parents dependency plan is
`#{:seon.fn/sym :seon.fn/calls :seon.effect/capability}`. It was `:all`.

Tests on the snapshot (`bin/test-check --root <snap> default --include-long`):
run `fac7e3272f86` executed 4 members: `seon.test.declared-reference-test` and
three `seon.fn-test` edge members. 42 assertions passed. The 3 fails are the
duration bounds noted above.

## Cleanup

The snapshot JVM (pid 10695) was stopped with `bin/seon down`. The snapshot
root and the HEAD fork archive were deleted; the `reference-code` symlink was
removed without being followed. No default Var was touched. Probe scripts and
outputs are in `tmp/fix-datahike-var-attr/`.
