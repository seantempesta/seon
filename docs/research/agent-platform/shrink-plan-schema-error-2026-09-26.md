---
type: design
lane: shrink-schema-opus
date: 2026-09-26
files: src/seon/schema.clj (3,964), src/seon/error.clj (2,520), src/seon/error/refusal.clj (274), their tests
status: plan only; no source edits. Root chooses the slices to run.
---

# Shrink plan: schema.clj, error.clj, error/refusal.clj (2026-09-26)

What exists:
- `seon.schema` builds the projection: forms → Malli registry → compiled caches, held on the projection's own
  `:seon.schema.projection/compiled` holder.
- `seon.error` records faults, reads them back and renders them.
- `seon.error.refusal` builds `:seon.error/base` diagnostics and reads cause chains.

Evidence sources:
- `seon.profile/cells` on default, pid 89341, running `bb07fdf08` (JVM mode, read-only).
- kondo analysis of `src script test`, run with `--cache false`.
- `rg` over `src script test resources`.
- Probes are in the "Probe forms" section at the end.

## 1. Slow paths first

Inclusive profile numbers mislead for wrappers. `call-with-projection-state` shows 1,761 s over 631 calls, and
`call-with-projection` shows 258 s over 2,162 calls. Both are one `binding` each (`schema.clj:1201-1216`). Their time
belongs to the bodies they run (turns and evaluations), not to schema work. The table below lists self-attributable
costs only.

| # | Function | Calls | Total | Mean | Proportional to | Fix | Status |
|---|---|---|---|---|---|---|---|
| S0 | every projection-holder derivation (`projection-cache-value` misses, `entity-maps`, `storable-attribute-in?` …) | 658,836 | 15.1 s | 22 µs | whole projection (~3,550 forms), rebuilt for every contracted defn | keep the derived tables when a change touches only function contracts | **in flight: defn-scan-opus** (landing `defn-scan-opus-2026-09-26.md`); plan below works around it |
| S1 | `seon.error/occurred-since` → `observation-selector` | 100 / 104 | 5.0 s / 4.9 s | 50 ms / 47 ms | the error-family declarations, compiled again for each new projection value | see S1 below | proposed |
| S2 | `schema/deleted-predicate-functions` | 104 | 5.4 s | 51 ms | every form and contract (`tree-seq` over the whole population) on every `load-projection` | resolve deleted predicates at the one resolution point, `compilable-form`, only for forms that actually compile | proposed |
| S3 | `schema/canonical-database-attributes` → `schema.datahike/database-attributes-in` | 19 | 3.1 s | 161 ms | whole population, not memoized | memoize on the projection holder | proposed |
| S4 | `schema/canonical-value-string`, armed private recursion | 3,608,432 | 13.1 s | 3.6 µs | value size × armed-wrapper cost at every node | one local recursion inside `canonical-data-string` | proposed |
| S5 | `projection-rows` ×2 + `projection-admissions` per `load-projection` | 208 + 104 | 1.3 s + 1.4 s | 6 ms / 13 ms | every declaration datom, not just the difference from the base | measure again after S0 and S2; they are small | deferred |

### S1: `occurred-since` pays for a selector rebuild on every turn after a program change

- **Where it runs.** `occurred-since` (`error.clj:1608`) runs once per turn, from `turn.clj:3738`.
- **The probe.** On the current projection, `observation-selector` took 58.7 ms on the first call and 0.02 ms on the
  second. The profile shows 104 selector calls averaging 47 ms, so nearly every call is a miss.
- **Cause.** The selector is cached on the projection holder, keyed by the projection value (`error.clj:1663`). Every
  contracted defn makes a new projection value (S0), so every turn after a program change rebuilds a selector whose
  inputs have not changed. Its inputs are the error-family entity declarations only.
- **Fix A (preferred, no code here).** If S0 carries the holder, or its schema-form-derived entries, across
  projections whose schema forms are unchanged, S1 disappears. Verify it after S0 lands: the selector misses once per
  schema-form change.
- **Fix B (only if A does not carry it).** Key the selector in `seon.cache` by the content it reads: the digest of
  the forms named by `declared-schema-keys` plus the error base. About 3 lines change and none are added. This
  follows CLAUDE.md "Key derivations by what they read".
- **Regression.** Existing: `test/seon/error/branch_test.clj:43-62` (`occurred-since`) and
  `test/seon/error_result_test.clj:64` (the selector pull). To add with the slice: one assertion that two
  projections differing only in a function contract answer the identical selector.

### S2: `deleted-predicate-functions` walks every form on every load

- **What it does.** `schema.clj:2811-2843` runs a `tree-seq` over `(vals forms)` and `(vals function-contracts)` on
  every `projection-from-rows`. It finds predicates whose namespace is loaded but whose Var is gone, and hands
  refusing callables to `build-projection`.
- **Fix.** `compilable-form` (`schema.clj:244`) is already THE resolution point. It currently throws when
  `loaded-predicate-var` returns nil. Give it the stored-row choice as data:
  - `projection-from-rows` passes `:seon.schema/stored-rows? true` through `build-projection`'s options;
  - compilation of a stored row then gets the refusing callable for a loaded-namespace miss;
  - an authored declaration still refuses at compile.

  Cost becomes proportional to the forms actually compiled (the changed ones and their reverse closure).
- **Size.** About 33 lines deleted, about 8 added.
- **Risk: medium.** The refusal moves from projection build to compile of the named form, which is the same moment
  for a changed row. An unchanged row that reused its compiled schema keeps the callable it compiled with.
- **Regression.** `test/seon/schema_test.clj:1687` (`a-stored-declaration-naming-a-deleted-predicate-stays-readable`)
  reads the private Var `@#'seon.schema/deleted-predicate-functions`. The rewrite must assert the behaviour through
  `load-projection` of a row naming `seon.schema/no-such-deleted-predicate?`: validation refuses by name, and the
  projection still loads.
- **Coordination.** `schema.clj` is held by defn-scan-opus for S0. Sequence after it, or hand it over as a patch.

### S3: `canonical-database-attributes` recomputes the whole population

- **Where.** `schema.clj:3623` is a rename of `schema.datahike/database-attributes-in`, reached through the
  lazy-resolve alias at `:51`. Its callers are `config.clj:265`, `cluster.clj:1101` and `render/web.clj:3667`.
- **Fix.** Wrap it in `(projection-cache-value projection ::database-attributes #(...))`. This is a 1-line change
  with no deletion. Its value grows after S0: the same holder then serves every caller of an unchanged projection.
- **Regression.** Existing callers' tests in `test/seon/schema/datahike_test.clj`. To add: an identity assertion
  across two calls on one projection.

### S4: `canonical-value-string` is armed at every recursion level

- **What it is.** `canonical-value-string` (`schema.clj:778`) is a private, contracted function that calls itself
  through its Var. Each node therefore goes through the profile and instrumentation wrappers.
- **Probe.** Over the full 3,548-form population:
  - armed: 15.9-16.4 ms;
  - the same algorithm as a `letfn`: 10.5-12.3 ms, about 30% less.
- **Fix.** Delete `canonical-value-string`, `canonical-coll-string`, `framed` and `portable-string-hash`
  (`:767-818`, the last is one line). Fold them into `canonical-data-string` as a `letfn`. One contract stays, on the
  public entry.
- **Size.** About 10 lines net and 4 definitions deleted.
- **The bigger cost is the call count**, which S0 drives: new projections need new fingerprints.
- **Regression.** `test/seon/schema_test.clj:181-242` already compares `canonical-data-string` against a legacy
  encoder over generated values.
- **Check before landing.** My probe's quick local copy did not produce equal output (`:equal? false`). The copy
  omitted the `Instant` branch and was typed by hand. The slice must be a moved copy of the existing body, and the
  existing equality test is its proof.

## 2. Deletions

The proof for each item: kondo analysis shows 0 `src`/`script` callers, and `rg` over `src script resources` finds
the name only in docstrings or tests. A public function whose only callers are tests goes together with those tests,
unless the test proves live behaviour through another path (named below).

### schema.clj

| id | Definition (line) | Lines | Proof | What stays | Tests to convert or delete | Risk |
|---|---|---|---|---|---|---|
| D1 | `restore!` (3551) | 11 | 0 code references. The `.agents/skills/clojure-testing/SKILL.md` and `instrument.clj:942` mentions are docstrings. | none; the delta is discarded by dropping it | none | low; fix the skill line in the same commit (root, or skills owner) |
| D2 | `changed-keys` (3534) + `changed-candidate-keys` (3526) | 15 | test-only (`schema_usage_guard_test.clj`) | — | delete that assertion | low |
| D3 | `entity-catalog` (3466) | 5 | test-only (`schema/program_test.clj`) | `build-projection`'s `:catalog` | read the catalog from `build-projection` | low |
| D4 | `enum-members` (1673) | 12 | test-only (`cluster/turn_test.clj`) | `m/children` of the compiled enum | inline in the test | low |
| D5 | `pulled-form-in` (3389) | 14 | test-only (4). `db.clj:2532` mentions it in a docstring. | `projection-with-pulled-form-in` (the live path, `db.clj`) | tests read through the live path | low |
| D6 | `function-returns-in?` (3726) | 15 | test-only (`render_simplification_test`, `schema_test`) | `function-accepts-and-returns-in?`, `function-matching-outputs-in` | convert | low |
| D7 | `admission-from-asserting-transaction` (964) | 31 | test-only (`custody_stability_test`, `schema/admission_test`). A second reader of admission provenance. | `projection-admissions` (2796), the live join | assert through `load-projection`'s `:schema-admissions` | medium: its fail-closed `:agent` default must match `projection-from-rows`'s `admission-for-identity` default, and it does (`:2862-2866`) |
| D8 | `identity-only-projection` (3876) | 5 | test-only | `identity-only-projection-in` (`sci/admit.clj:308`, `render/value.clj:741`) | convert | low |
| D9 | `matching-shapes` (3956) | 9 | test-only | `matching-shapes-in` (6 `src` callers) | convert | low |
| D10 | `active-projection` (1218) | 3 | a one-line alias of `*projection*` used by `current-projection` | `current-projection` | none | low |
| D11 | `snapshot` (3472) | 5 | test-only, 17 uses (11 in `instrument_test`). It duplicates `declaration-population`, which is the same `candidate-forms` read but refuses when unbound. | `declaration-population` | tests bind a projection or call `declaration-population` | medium: tests that run unbound currently rely on the classpath fallback |
| D12 | `begin-registration-delta`'s two arities (3480) | 5 | both arities call `delta-over` | one variadic-free public `delta-over`, or keep one arity | 9 test callers keep working if the 0-arity stays | low |
| D13 | `projection-from-database` 2-arity (3100) | 2 + contract | ignores its second argument | 1-arity | `sci/eval.clj:1758,1844,1882,3373` (**shrink-eval's file → patch**); `datahike_fork_test.clj:64,85`, `schema_test.clj:467-482` | low |
| D14 | the classpath-fallback diagnostics (`:1012-1150`): `!fallback-counts` atom, `frame-namespace`, `resolution-owner-namespaces`, `canonical-directory`, `first-party-source-roots`, `frame-source-path`, `first-party-frame?`, `frame-description`, `fallback-caller`, `decade?`, `warn-classpath-fallback!` | ~120 | a **third** first-party-frame rule besides `seon.error.refusal/first-party-frame?` and `seon.instrument/caller-frame`; plus an atom | see ruling R3 | `schema_test` fallback assertions | medium |

`register!` and `unregister!` show no `src` callers, but they are **live**: the agent reader recognizes
`'seon.schema/register!` and `unregister!` forms (`sci/reader.cljc:336,347`), and agents call them in SCI. Keep them.

Other public functions with 0 code callers are **live through schema resources**. `rg` finds each in
`resources/seon/schemas/*.edn` as a render pair, a `:gen/gen` or a `[:fn …]` predicate. Keep them:
- `projection-identity`;
- the `error.clj` predicates and generators (`ordered-*?`, `*-complete?`, `*-generator`, `compiled-schema-generator`,
  `throwable-generator`);
- `notice-prose`, `render-faults-ai`, `elision-prose`, `elision-html`, `mcp-prose`, `index-refusal-prose`,
  `render-contract-ai`.

Deleting any function also removes its symbol from `test/seon/schema/datahike_parity.edn` when listed there.

### error.clj

| id | Definition (line) | Lines | Proof | What stays | Tests | Risk |
|---|---|---|---|---|---|---|
| E1 | `time-limit-prose` (2088), `edit-prose` (2110), `unclassified-prose` (2154) | 40 | referenced only by `test/seon/error_test.clj:1471-1510` (and one issue doc for `edit-prose`); no schema resource declares them as a renderer | — (`evidence-text` stays for `mcp`/`index-refusal`/`elision`) | delete the 3 `testing` blocks | low. Rendering a unit that reaches them is impossible: no schema names them. |
| E2 | `normalize` (697) | 8 | test-only (18 in `error_test`, 2 in `web_debug_test`, 1 in `run6_stall_test`). A rename of `(:seon.error/fact (prepare r))`. | `prepare` | a scripted conversion `(error/normalize x)` → `(:seon.error/fact (error/prepare x))` | low; the `:seon.error/normalize-request` schema stays, since `prepare` uses it |
| E3 | `seon.error/refusal` (75) | 17 | delegates to `seon.error.refusal/refusal`; 5 callers in `turn.clj` and `sci/eval.clj` | `seon.error.refusal/refusal` | **patch** for shrink-turn and shrink-eval, then delete | low |
| E4 | `flat-data` (754) | 6 | a rename of `fact-source`; 1 caller | `fact-source` | none | low |
| E5 | `mcp-prose` / `index-refusal-prose` | ~12 | the same body with different evidence keys and advice | one private `(evidence-advice-prose unit keys advice)`; both public producers stay, since resources name them | existing `error_test` blocks | low |

### error/refusal.clj

Clean of dead code. It is the owner the other two files should converge on (rulings R2 and R3).

## 3. Rulings needed from root (cross-file, one way to build an error)

- **R1. `seon.schema` builds its own error shape. It has 36 `ex-info` sites.** Each carries
  `{:seon.schema/error :seon.schema/K, :seon.schema/K true, …}`, which is a discriminator plus a stamp, with no
  `:seon.error/at/layer/operation`. `resources/seon/schemas/seon.schema.edn` declares them: 25 `*-error` maps and 9
  `[:= true]` stamps. The readers of `:seon.schema/error` are:
  - `db.clj:614` (shrink-db);
  - `schema/admission.clj:371,386`;
  - `error.clj:1014`;
  - `schema/internal.cljc:229,498,542` and `schema/datahike.clj:216`, which produce the same shape.

  The target is one `seon.error.refusal/diagnostic` per site, with the member and offending value, and deletion of
  the stamp schemas.
  - **Option 1 (simplest).** Convert the 36 sites and the 34 schema entries in one scripted slice, and convert the
    4 readers to read `:seon.error/operation` plus the member. Cost is about ½ day across 5 owners' files. You give
    up nothing.
  - **Option 2.** Convert only the sites nobody reads by kind (about 25). Leave the read kinds until their readers'
    owners free the files.
  - **Option 3.** Leave it until the error-identity work (`rc-m3-error-identity`) finishes.

  **I recommend option 1**, after S0 releases `schema.clj`.
- **R2. The `(vary-meta (refusal/diagnostic …) assoc :seon.error/declared-schema K)` wrapper appears at 33 sites in
  9 files.** 4 of them are in `schema.clj`. `diagnostic` could lift a `:seon.error/declared-schema` member into meta
  itself, which deletes the wrapper everywhere. That touches `refusal.clj`, which is mine, with about 3 lines, plus a
  scripted sweep of the callers, which are other lanes' files.
- **R3. D14, the classpath fallback.** Its only live occurrence on this JVM (probe) is `seon.test (test.clj:2175)`,
  once: `arm-first-loads!` `require`s a namespace whose load-time code asks schema questions unbound.
  - **Option 1.** The producer binds its projection (a `test.clj` patch). Then the fallback in `candidate-forms` can
    refuse like `declaration-population` already does. That deletes `packaged-forms`, `schema-edn-packaged-forms`
    and all of D14: about 150 lines.
  - **Option 2.** Keep the fallback but name the caller with `seon.error.refusal/frame-function`. That deletes about
    75 lines of frame logic; the atom stays.

  **I recommend option 1.**
- **R4. `refusal-data` (`error.clj:987-1071`) guesses who produced an error from its keys.** It keys on
  `:seon.sci.reader/text`, `:seon.schema/definition` + `:seon.schema/error`, and `:seon.sci.eval/symbol`, and
  reconstructs `:seon.error/problems` for those producers. CLAUDE.md: "The producer declares identity; nothing
  downstream guesses it." If `reader.cljc`, `schema.clj` and `sci/eval.clj` emit `:seon.error/problems` themselves,
  about 45 lines here go. That is cross-lane (shrink-eval).

## 4. Ordered slices, slow first

Each slice: lint `src script test` with `--cache false` (0 errors); `contracts-compile-test/check` over the changed
files (0 findings); schema resources read; commit with `--only`.

1. **error.clj E1 + E2 + E4.** Files: `error.clj`, `error_test.clj`, `web_debug_test.clj`, `run6_stall_test.clj`,
   `datahike_parity.edn`. About −54 `src` lines. Independent of S0, so it can run now.
   Focused check: `bin/test-check default --ns seon.error-test`.
2. **S1**, once S0 is adopted. Measure `observation-selector` misses per contracted defn. If there is more than one
   miss per schema-form change, apply fix B. Otherwise record the measurement and add the identity regression.
3. **S2**, `schema.clj` after defn-scan releases it. About −25 lines; about 5.4 s per 104 loads removed.
4. **S3 + S4**, `schema.clj`. About −10 lines; about 3 s and about 30% of the fingerprint encode time.
5. **D1-D12**, `schema.clj` plus tests. About −130 `src` lines and the matching test blocks.
6. **Patches for other owners.** E3 (`turn.clj`, `sci/eval.clj`) and D13 (`sci/eval.clj`). Once they are applied,
   delete E3 and the 2-arity (about −20 lines).
7. **After rulings.** R3 (about −150), R1 (about −60 `src`, −34 schema entries), R2 (about −33 wrappers, cross-file),
   R4 (about −45).

**Estimated total.** Slices 1-6 remove about 250 `src` lines from these three files. With rulings R1-R4, about 550.
The larger win is time: S1 + S2 + S3 remove about 13 s of the measured per-process schema/error cost, plus the S0
share that defn-scan owns.

## Probe forms (JVM mode, read-only, pid 89341)

- Profile rows:
  `(->> (seon.profile/cells) (map seon.profile/observation) (filter #(re-find #"^seon\.(schema|error)" (str (:seon.profile/sym %)))) (sort-by #(- (:seon.profile/total-ms %))))`
- Selector miss and hit: `(seon.error/observation-selector (seon.db/carried-projection @conn))`, run twice. Results:
  58.7 ms, then 0.019 ms. `occurred-since` over the last 1,000 basis-t took 11-15 ms.
- `projection-cache-value` hit: 0.51 µs armed, against 0.24 µs for a raw `lookup`. So its 22 µs mean is misses.
- Fallback counts: `@@(resolve 'seon.schema/!fallback-counts)` returned `{"seon.test (test.clj:2175)" 1}`.
- `canonical-data-string` over 3,548 forms took 16 ms armed, against 11 ms with a local `letfn`.
