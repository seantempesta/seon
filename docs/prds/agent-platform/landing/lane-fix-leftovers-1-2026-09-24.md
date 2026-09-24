---
type: landing
lane: fix-leftovers-1
status: landed-installed
created: 2026-09-24
---

# Lane fix-leftovers-1 (2026-09-24)

This lane landed three pieces: the item-2 pieces from fix-schema-one-verdict,
the storage refusal wording, and the conversion of the contracts-compile
caller left by 9bba9e000. All three are installed in default (pid 70372).

## Broken or unproven first

- **Installing a code change and a resource change in the wrong order broke
  default's test requests for about five minutes.**
  - I applied `internal.cljc`, `seon.flow.edn` and the tests in one shell
    write. The save gate then went red on foreign `my.*` reds ("Cannot
    interpret … Host-bound declaration … must change through the loaded
    source files", and a fixture refusal on `:seon.agent/branch`).
  - The gate still loaded the host-bound `internal.cljc` ("red after
    adoption … only where the files compile"). It did not adopt the
    `seon.flow.edn` rows.
  - For that window, `bin/test-check default --ns
    seon.schema.agent-nil-contract-test` was refused with
    `schema/register! :seon.flow/callback-result: [:or :nil …]`.
  - I reverted `internal.cljc` in the working tree and published again, and
    default recovered (384 pass). I re-applied the change only after the
    stored `:seon.flow/callback-result` row showed the `:nil`-free form. That
    row was adopted by another lane's accepted publication.
  - Class of defect: a host-bound load can run ahead of the rows it depends
    on when the gate is red. The publication owner should decide whether a
    red gate may load any host-bound file.
- **A test in `seon.fn-test` redefines `seon.fn.analyzer/analyze` JVM-wide.**
  `indexing-uses-a-prebuilt-manifest-without-analysis` (`test/seon/fn_test.clj:1022`)
  wraps `with-redefs` around it. One of my publications ran while that test
  was running and was refused with `"analysis must not run"`. This breaks the
  "no with-redefs over default's Vars" law. It is foreign; I did not file an
  issue.
- **`seon.flow-test` is red (pass 187 / fail 6 / error 2, run
  f1518a6d45f4).** The failures are occurrence counts
  (`core-fault-signatures-bound-durable-and-stderr-output`), a 21.7 s
  channel timeout (`fault-committer-own-error-channel-reaches-the-last-resort`)
  and a missing cluster config row (`turn-evaluation-completion-…`). None of
  them touches `:seon.flow/callback-result`. The respelling accepts exactly
  the same values as before in each of the four function slots. I could not
  get a HEAD baseline without a second JVM, so "foreign" is inferred, not
  measured.
- **datahike.clj:131: an `[:or :nil X]` attribute stored as a plain string
  is still reachable after item 1.**
  - Item 1 refuses a *top-level named* nil-union.
  - `[:and [:or :nil :string]]`, `[:or :string [:maybe :int]]` and
    `[:or :string [:or :nil :int]]` are all still stored as
    `:db.type/string`. The storage fold's `:or` branch drops its children's
    `::refusal`.
  - Filed: `docs/seon/issues/a-stored-or-attribute-drops-its-childrens-nil-refusal.md`.

## Changes

1. **e86043f81.** This lands `tmp/fix-schema-one-verdict/`
   `item2-internal.patch`, `flow-respelling.patch` and `item2-tests.py`
   unchanged.
   - `assert-non-nilable-value-schema!` uses `nilable-union?`.
   - `:seon.flow/callback-result` drops `:nil`. The four callbacks that may
     return nil now say `[:maybe :seon.flow/callback-result]`.
2. **147c59aec.** The fallback refusal in `malli->datahike-attr-in` now reads:
   "A stored attribute needs a storable Datahike value type, and this Malli
   form has none. Register a concrete storable shape." The ex-data is
   unchanged.
3. **7cb40d2e2.** `contracts_compile_test.clj`:
   - `namespace-contexts` runs one `analyzer/analyze ::sources` over each
     text up to the end of its `ns` form, keyed by canonical path. It then
     calls `seon.fn/namespace-context` on each namespace definition entry.
   - The analysis writes a private temporary clj-kondo cache, which is
     deleted afterwards. Without that, the reconstructed historical `seon.db`
     and `seon.render.web` texts would enter the shared cache under the real
     namespace names, and a later analysis would read the decoys.
   - `declared-contracts` takes the context as an argument.
   - The historical breaks are now refused earlier, at
     `schema/canonical-definition`'s armed return contract
     (`:seon.schema/definition`, "must be a parseable, EDN-readable Malli
     form"), before any compile. The finding now says "the indexer refuses to
     store it: <message>" instead of "does not read back as EDN". The
     regression asserts that wording.

## Evidence (default, pid 70372)

**Item 1, live:**

| Measure | Before | After |
|---|---|---|
| `build-projection` over packaged + `[:or :nil :string]` | built, 426 ms | refused `:seon.schema/nilable-value-schema`, 85 ms |
| packaged `build-projection` | 425 ms | 283–465 ms |

- The packaged `build-projection` range is load noise. The machine was
  running other lanes' gates, 100+ s each.
- `bin/test-check`:
  - `--ns seon.schema.admission-gate-test`: 53 pass (runs ff4220ad2ee7 and
    e2369653e03b).
  - `--ns seon.schema.agent-nil-contract-test`: 384 pass, 8.1 s (run
    db90326f5b2d). The bound is per test; this is one namespace run under
    load.
  - `--test seon.schema-test/a-nilable-value-schema-gets-one-verdict-at-build-and-replacement`:
    5 pass, 11.8 s cold (run 25ad65e6c2ca).

**Item 2, live:** `malli->datahike-attr-in` on `:any` returns the new
message.

**Item 3:**

- The hook's save gate accepted the change: 6/6 pass, 4.1 s (run
  fe38e2becaba). `--ns seon.contracts-compile-test` then reused that result
  (run 6037f8ea84bf).
- Equivalence: analyzing only the `ns` prefixes gave contexts equal to
  analyzing the whole files, for all 442 `src`/`test` files.

Item 3 timings under load:

| Step | Whole files | `ns` prefixes |
|---|---|---|
| Analysis | 6.2–6.5 s | 449 ms |
| Test `every-declared-contract-compiles-…` | 7.6–8.4 s, over its 5 s bound | inside the 4.1 s gate run |

The rest of the whole-tree test after analysis: reading and qualifying
2020 contracts takes 477 ms, the projection 272 ms, and compiling 31 ms.

## Size

| Commit | Lines | Area |
|---|---|---|
| e86043f81 | +12 −9 | src/resources |
| e86043f81 | +11 | tests |
| 147c59aec | +1 −1 | src |
| 7cb40d2e2 | +71 −25 | test |

In 7cb40d2e2, most of the added lines are two docstrings and the private-cache
lifetime.

Released paths: `resources/seon/schemas/seon.flow.edn`,
`src/seon/schema/internal.cljc`, `src/seon/schema/datahike.clj`,
`test/seon/schema/admission_gate_test.clj`, `test/seon/schema_test.clj`,
`test/seon/contracts_compile_test.clj`, this note, and the new issue.
