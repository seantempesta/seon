---
type: research
status: complete
created: 2026-09-17
tags: [research, steward, schema, instrumentation, call-preparation, one-derivation]
---

# One derivation for a projection's bound predicates

Date: 2026-09-17. Branch `steward-platform`. Default cluster pid 74930,
source commit `6aaa99ca-0cf3-53d6-a204-6e2ff481f7c5` at the time of the proofs.

## The class

A projection carrying no bound predicates has NO
`:seon.schema.projection/predicate-functions` KEY — never a stored nil —
while `seon.schema/compilable-form` declares its second argument `:map`. A
bare read therefore hands it `nil` and every compile refuses in a cold
worker, where nothing binds a projection before the arm:

```
seon.schema/compilable-form refused predicate-functions at []:
expected a map, got nil.
```

`eeafb9dba` fixed one site inline, `ed2eb3423` fixed three more, and
[call-preparation-and-recovery-reds-2026-09-17](call-preparation-and-recovery-reds-2026-09-17.md)
named the six that remained plus three that already defaulted — nine
spellings of one question. AGENTS §2.2: the cure is one named derivation, not
a tenth careful `get`.

## What landed

Two owners in `src/seon/schema.clj`, both publicly contracted:

- `seon.schema/predicate-functions-in` (`src/seon/schema.clj:285`) — THE ONE
  READER. Answers the bound predicate map, `{}` when none are bound.
  Contract `[:=> [:catn [:seon.schema/projection :map]]
  [:map-of :qualified-symbol [:fn clojure.core/ifn?]]]`. The key/value shape
  was measured, not assumed: on default's live projection all 60 bindings are
  qualified symbols to `ifn?` values.
- `seon.schema/with-predicate-functions` (`src/seon/schema.clj:305`) — THE ONE
  WRITER, so the checker below can be exact instead of carrying an exemption
  roster for the construction sites.

### `compilable-form`'s contract was deliberately NOT widened

Its docstring (`src/seon/schema.clj:172`) says `predicate-functions` is "the
caller's explicit override — a preprocessed projection carries its own
callables". It is an explicit argument, not a projection: the absence belongs
to the PROJECTION's shape, and translating it is the projection owner's job.
Accepting `[:maybe :map]` would make the absent key silently mean "no
predicates" at the one seam that found this class nine times — the project's
recurring failure mode (absence read as health), installed on purpose. It
keeps declaring `:map`, so a tenth bare read still refuses loudly.

## Every site converted

Reads (`file:line` after the change):

| site | former spelling |
|---|---|
| `src/seon/schema.clj:643` (`direct-references`) | defaulted `get` |
| `src/seon/schema.clj:1568` (`render-contract-observation`) | defaulted `get` |
| `src/seon/schema.clj:2186` (`predicate-functions-with`) | defaulted `get` |
| `src/seon/schema.clj:2200` (`validate-one-contract!`) | BARE keyword read |
| `src/seon/schema.clj:2640` (`projection-without-schema`) | BARE keyword read |
| `src/seon/schema.clj:2947` (`canonical-schema-rows`) | `(or (:key p) (predicate-functions-with {:key {}} …))` |
| `src/seon/schema.clj:3074` (`function-arities-in`) | defaulted `get` |
| `src/seon/fn.clj:1908` (`add-contract-facts`) | BARE keyword read |
| `src/seon/fn.clj:1966` (`backfill-contract-facts!`) | BARE keyword read |
| `src/seon/sci/eval.clj:446` (`declared-row`) | BARE keyword read |
| `src/seon/sci/eval.clj:1939` (`definition-row`) | BARE keyword read |
| `src/seon/instrument.clj:439` (`predicate-callable`) | BARE keyword read |
| `src/seon/instrument.clj:570` (`compiled-wrapper`) | defaulted `get` |
| `src/seon/call_preparation.clj:583` (`argument-validators`) | defaulted `get` |

The two `seon.instrument` sites call the derivation through
`(mi/-f->original schema/predicate-functions-in)`, matching the neighbouring
`compilable-form` and `projection-cache-value` calls: the arming path must not
re-enter its own instrumentation.

Writes, now all `with-predicate-functions`: `src/seon/schema.clj:1878`
(`build-projection`), `:2174` (`materialize-projection`), `:2545`
(`projection-with-schema`), `:2679` (`projection-with-function-contract`).
`canonical-schema-rows`'s synthetic `{:key {}}` projection became a plain
`(predicate-functions-with {} (vals forms))` — it existed only to spell the
absence.

`projection-runtime-keys` (`src/seon/schema.clj:1912`) still names the key in
a `dissoc` key set. It is a `def`, not a function, carries no
`:seon.fn/keywords` fact, and is neither a read nor a write of a projection's
bindings.

## Regressions (`test/seon/schema_test.clj`)

1. `a-projection-with-no-bound-predicates-compiles-every-declared-shape` —
   builds the cold shape the reproduction used (`projection-from-database`
   with the key `dissoc`'d) and drives the REAL functions behind the former
   bare sites: `direct-references` over every declared form,
   `#'function-arities-in` over every declared function contract,
   `projection-with-schema` / `projection-without-schema` /
   `projection-with-function-contract`, `#'instrument/predicate-callable`,
   `#'instrument/compiled-wrapper`, and
   `#'call-preparation/argument-validators` over 25 contracted symbols that
   declare argument shapes. `function-accepts-in?` is deliberately NOT the
   driver: it catches `Throwable` and would report the refusal as `false`.
2. `one-derivation-owns-the-projection-predicate-bindings` — the drift
   checker. A program-graph query over `:seon.fn/keywords` joined to
   `:seon.fn.file/root "src"` asserts that exactly
   `seon.schema/predicate-functions-in` and
   `seon.schema/with-predicate-functions` name the key under `src/`. Plus a
   non-vacuity assertion that the derivation has callers, so an empty graph
   cannot report health. No text search anywhere.

## Measured results

Program graph on default after adoption (the checker's exact query):

```clojure
:src-named   #{"seon.schema/predicate-functions-in"
               "seon.schema/with-predicate-functions"}
:callers-of-predicate-functions-in
  ("seon.call-preparation/argument-validators" "seon.fn/add-contract-facts"
   "seon.fn/backfill-contract-facts!" "seon.schema/canonical-schema-rows"
   "seon.schema/direct-references" "seon.schema/function-arities-in"
   "seon.schema/predicate-functions-with" "seon.schema/projection-without-schema"
   "seon.schema/render-contract-observation" "seon.schema/validate-one-contract!"
   "seon.sci.eval/declared-row" "seon.sci.eval/definition-row")
```

Before the change the same query answered 18 first-party functions.

The falsification, on default against the real cold projection and
`:seon.turn/closed-tx`'s declaration:

```clojure
{:cold-has-key?      false
 :derived            {}
 :bare-read          "seon.schema/compilable-form refused predicate-functions
                      at []: expected a map, got nil."
 :through-derivation :compiled}
```

`(seon.schema/predicate-functions-in {})` → `{}` on the live JVM.

In-process runs (default pid 74930, daemon thread,
`:seon.test/remaining-ms 100000`, `seon.test`'s own loader):

| run | result |
|---|---|
| `a-projection-with-no-bound-predicates-compiles-every-declared-shape` (first) | 12 pass / 1 fail — MY TEST's fault: it removed the alphabetically first schema key, which other declarations reference. Fixed by deriving a removable key from `:seon.schema.projection/reverse-schema-dependencies`. |
| same, after deriving a removable key from `reverse-schema-dependencies` | 12 pass / 1 fail — still population-specific: removing an existing key revalidates its own definition against a registry that no longer holds it. Reworked to add `:seon.schema-test/cold-removable` and remove that. |
| same, final | **13 pass / 0 fail / 0 error** |
| `one-derivation-owns-the-projection-predicate-bindings` | 0 pass / 2 fail against a fixture base realized BEFORE adoption — it answered the pre-change set of 18. See the verification boundary. |

## Verification boundary

- The src edits are LOADED in default (both new Vars resolve; program
  reconciliation, loaded definitions, SCI acquisition and JVM instrumentation
  all reported success) but the adoption's source commit was REFUSED TWICE
  with `Source changed while incremental publication was being analyzed` —
  another lane wrote to the tree during the build both times. Default kept
  commit `6aaa99ca-…` and stayed healthy; it was never stopped or reforked.
- `seon.test-support/database-base` in that JVM was realized before this
  lane's adoption, so the canonical fixture the in-process runs read carries
  the PRE-CHANGE program rows. That is why the checker fails in-process and
  passes against default's live graph. The base was not rebuilt (poison rule).
  A cold worker builds it from HEAD, so the cold gate is the proof for that
  test.
- `seon.fn-test`, `seon.sci.eval-test`, `seon.call-preparation-test` and
  `seon.instrument-test` were NOT run in-process by this lane. Gate request:
  `tmp/orchestrator/gate-requests/predicate-functions.txt`.

## No issue filed

The class is closed by construction: after this change a bare read cannot
appear anywhere under `src/` without the drift checker naming the function
that added it, and `compilable-form` still refuses `nil` loudly rather than
reading absence as "no predicates".
