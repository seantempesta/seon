---
type: landing
lane: rc-m3-error-identity-opus
date: 2026-09-26
status: two slices committed (8b4a10a44 installed and proven; 3cf5ff927); three patches for root; RESET NEEDED
model error: M3 — an error's location or compiled form was treated as its producer contract
---

# rc-m3: the producer's declared identity

## Design

- **What existed.**
  - `seon.instrument/expected-shape` ran on every input, output and guard refusal (instrument.clj:542 at 373bfaf50). It did `schema/canonical-definition (m/form compiled)`, which converts the *compiled* expected schema back into durable names.
  - A contract compiled from evaluated `defn` metadata holds predicate roots: `[:fn clojure.core/ifn?]` evaluates to the function object, which carries no name. Boot arms every host Var from `(mi/-schema var)` against `declaration-projection (packaged-forms)`, which has 0 function contracts (cluster.clj:2467). In those wrappers the inversion threw, and the refusal became "the refusal could not be built".
  - Rendering took `:seon.error/operation` to be the function that returned the error (`render/error-alternatives`, render.clj:415).
- **The one owner.** The producer.
  - Malli reports which arity schema refused (`:schema`, malli core.cljc:2229-2240 and :2305).
  - The wrapper learns at arming which declared arity each schema object is.
  - The thrower attaches the declared error schema to the value's metadata (`:seon.error/declared-schema`). That convention already exists in instrument, fn, fault, boot and schedule.
- **Decision sites deleted.**
  - `expected-shape` (8 lines).
  - `render/error-alternatives` (9 lines) and its operation-based branch.
  - In the patch: the inverse direction of `canonical-definition` (Var and identity lookup, 17 lines net), whose only production caller was `expected-shape`.
- **Evidence.**
  - The live reproduction, before the change.
  - The scratch-namespace proof, after.
  - Regressions in `test/seon/schema/predicate_metadata_refusal_test.clj` and `test/seon/render/entity_pairs_test.clj`.

## Broken first

1. **At pid 48787, 45 armed host Vars cannot build a refusal.** Any invalid call to one of them answers "…: the refusal could not be built." instead of naming the argument. Examples: `seon.schema/call-with-forms`, `seon.db/datoms`, `seon.db/pull*`, and 17 contracts that use `clojure.core/ifn?`.
   - Reproduced read-only: `(seon.schema/call-with-forms {} 42)`. The chain reads "A durable Malli definition contains an unnamed callable".
   - `seon.fn/identity-names` itself now refuses readably, because development adoption re-armed it from the registry.
   - Fixed in 8b4a10a44. It takes effect only after default loads that commit: **RESET NEEDED** (or `start --head`).
2. **Agent contracts cannot name a predicate at all.** This is outside M3, so it is reported, not fixed.
   - `{:malli/schema [:=> [:cat [:fn clojure.core/ifn?] :int] :any]}` is refused by `accretion/data-contract!`, because metadata evaluates to a function (E7).
   - The quoted whole `'[...]` form is refused by fn.clj:790 raw canonicalization: `(quote [...])` is not a Malli form.
   - `[:fn 'clojure.core/ifn?]` is refused by `schema/assert-complete-contract!` (schema.clj:1540) and `internal.cljc:374` "unproved predicate purity". Nothing in src ever supplies `:seon.schema/pure-predicate-symbols`, so every agent predicate is refused.
   - Under today's ruling ("a restriction must name the correctness failure it prevents"), that purity refusal should go. It spans schema.clj, internal.cljc and the projection fingerprint plumbing (about 20 places). **Root decides**; I will do the schema.clj half on request.
3. **`(do (defn f {:malli/schema …} …) (f 1 2))` leaves an unadmitted Var, and the throw hides the refusal.** When the call throws, admission never runs. The Var stays live in the branch's SCI context, uncontracted and unrecorded; a later `(f 1 2)` runs the unarmed body. This belongs to M1/M4 (sci/eval.clj admission after evaluation), not this lane.
4. **The prose for an unnamed predicate is weak** (error.clj explain-problem): "expected the declared predicate … Fix: the declared schema". The caller frame of an interpreted call reads `sci.lang.Var (lang.cljc:265)`. Both are readable but thin; neither is filed.

## Slice 1 — 8b4a10a44 (instrument.clj, render.clj, three tests)

- **Instrument.** The refusal's `:seon.error/expected-shape` is `digest 64 [function, arity-ordinal, check]`.
  - The ordinal is the index of Malli's reported arity schema among `m/-function-schema-arities compiled` (`identical?`), fixed at arming.
  - Refusals never canonicalize again.
  - The minimal fallback's message now names its cause.
  - The caps registration refusal carries `:seon.instrument/registration-error` on its metadata.
- **Render.** An error selects its renderer as follows:
  1. its producer-declared schema (metadata);
  2. otherwise, when it is the root call's value, the alternative the call's function declares it returns;
  3. otherwise the base pair.

  `:seon.error/operation` selects nothing.
- **Identity semantics change.** An unchanged contract gives the same `expected-shape` across JVMs. A changed contract keeps the same shape for the same function, arm and ordinal. The contract's content identity is the function's definition digest. The error id already includes the operation.
- **Lines:** src +38/−35; tests +79/−57.

### Evidence (live JVM 48787, JVM mode; the scratch namespace was removed afterwards)

HEAD's `instrument.clj` and `render.clj` were each loaded into a throwaway namespace and called with the live projection. Scratch copies are unarmed. Every output below was checked explicitly against `:seon.instrument/contract-error`.

| probe | result |
|---|---|
| `compiled-wrapper` `[:=> [:cat [:fn clojure.core/ifn?] :int] :any]`, call `(1 2)` | "rc-m3/probe refused argument 0 (0-based) at []: expected the declared predicate, got an integer 1…"; valid contract-error; shape = digest [probe 0 :input]; declared `:seon.instrument/contract-error` |
| `:function` with two arities, 2-arg call | shape = digest [probe 1 :input] |
| output arm `[:fn clojure.core/ifn?]` | valid; digest [probe 0 :output] |
| `wrap-interpreted` spec-edn `"[:=> [:cat [:fn clojure.core/ifn?] :int] :any]"` | `(w inc 2)` → 3; `(w 1 2)` refuses readably, declared identity on the metadata |
| `declared-producers`, ambiguous-error value | plain → `seon.error/render-ai` (base); declared elided-error metadata → `seon.error/elision-prose`; foreign operation → base |
| Malli arity identity (`-instrument` report `:schema` vs arities) | `[:invalid-input 0] [:invalid-input 1] [:invalid-output 1]` |

- Scratch load: 89 ms (instrument) and 75 ms (render).
- A refusal still takes about 2 ms (unchanged).
- **Static checks.**
  - `seon.contracts-compile-test/check` over the absolute instrument.clj and render.clj paths → `[]`, 90 ms.
  - `bin/test-check default --test seon.contracts-compile-test/every-declared-contract-compiles-against-the-packaged-projection` → pass 2, 481 ms (reused).
- **Agent path before the change** (MCP SCI, branch rc-m3, retired): `(/ 1 0)` → "Execution error (ArithmeticException) at (REPL:1:1).\nDivide by zero", with no finding line. `(m3-inc "a")` → "user/m3-inc refused x at []: expected an integer (:int), got a string \"a\"…".

## Slice 1, installed proof (the JVM restarted onto 8b4a10a44 at 17:24Z)

- `(seon.schema/call-with-forms {} 42)` → "seon.schema/call-with-forms refused f at []: expected the declared predicate, got an integer 42…", 1.4 ms. Declared identity: `:seon.instrument/contract-error`.
- `bin/test-check default`:
  - `--ns seon.schema.predicate-metadata-refusal-test` → pass 17 (3.1 s; the test bodies run 2).
  - `entity-pairs-test/declared-identity-and-producer-lookups-name-their-owners` → pass 10 (1.7 s).
  - `instrument-test/an-invalid-refusal-retains-its-evidence-at-the-kernel-boundary` → pass 4 (1.9 s).
  - `instrument-test/named-generators-survive-compilation-in-refusal-evidence` → pass 5 (1.9 s).
- All four exceed one second end to end. That time is the request and fork overhead of `test-check`; no body exceeds its 5 s bound.

## Slice 2 — 3cf5ff927: producers declare what settlement guessed (E6, task 1bf47dae6f04; root's follow-up)

- **What changed.**
  - A scripted sweep (`tmp/rc_m3_error_identity_opus/declared_identity_sweep.clj`, rewrite-clj under bb, about 3 s) visited every construction site that sets an error schema's distinguishing member.
  - It wrapped each in `(vary-meta … assoc :seon.error/declared-schema <schema>)`.
  - The member-to-schema table and its priority are exactly turn.clj's three discriminators, so each producer now declares the schema those tables chose.
- **Sites: 86.**

  | file | sites |
  |---|---|
  | db | 16 |
  | ai | 17 |
  | config | 18 |
  | render | 12 |
  | render/walk | 5 |
  | render/transcript | 5 |
  | cluster/prompt | 3 |
  | schema | 6 |
  | fn | 1 |
  | turn | 2 |

- **Hand residue (3).**
  - Render's typed unknown declares on `unknown`'s result, not on the request maps handed to it.
  - `request-profile`'s threaded relabel.
  - `db/write-observation`'s threaded `assoc`.
- **Lines:** +200/−163, including a 34-line test. The wraps are line-neutral.
- **Checks.**
  - clj-kondo shows no new findings in any of the ten files (compared against HEAD).
  - Contracts compile over the ten files → `[]` (282 ms).
- **Behaviour change:** where a producer relabels another error (for example, prompt's `assoc … :seon.cluster.prompt/error-agent-id`), the relabelling producer's schema now wins. The old table would pick an earlier member. Both schemas validate the value.
- **Not converted.** turn.clj:4667 (`failure` detection over ten `seon.ai` members) and ai.clj:1369 (`contains?` over four members) still detect failure from members. They choose no schema, so they are left to the M3 follow-up.
- **Regression:** `test/seon/error/declared_identity_test.clj`. It needs 3cf5ff927 installed.
- **Deletion → patch 3.** `cluster/reply.clj` builds `:seon.cluster.reply/no-forms-error`, and that file is not this lane's. Deleting the discriminators without it would record a no-forms settlement with a nil schema, which panics recording. The deletion (turn.clj −42 lines) therefore ships in one patch with reply.clj's two wraps.

## Patches for root (files this lane does not hold)

1. **`tmp/rc-m3-error-identity-opus/canonical-definition-one-direction.patch`** (schema.clj, fn.clj, contracts_compile_test, schema_test, instrument_test; +17/−34).
   - `canonical-definition` becomes one-directional, `[definition]`: authored source as data. Callables are refused and never inverted.
   - Its only production callers are fn.clj:565 and fn.clj:790, which pass `{}` over raw source, so their behaviour is unchanged.
   - The patch must land in one commit, because changing the arity alone breaks fn.clj loading.
   - Proof: contracts compile over the patched files → `[]`; clj-kondo shows no new findings.
2. **`tmp/rc-m3-error-identity-opus/analysis-error-extends-base.patch`** (seon.fn.edn, fn.clj; E5).
   - `:seon.fn/dependency-analysis-missing-error` becomes `[:and … :seon.error/base [:map …]]`.
   - `unresolved-macro-refusal` builds its value through `error.refusal/diagnostic`.
   - Proof, over the patched checkout population: a produced value validates; `extends-schema? … :seon.error/base` → true; contracts compile → `[]`.
   - Once applied, turn.clj:3525's docstring reason, and `phase`'s rerouting of this refusal, are M4's to delete.
   - Both patches apply together (checked again on 3cf5ff927).
3. **`tmp/rc-m3-error-identity-opus/settlement-reads-declared-identity.patch`** (cluster/reply.clj +2 wraps; turn.clj −42 lines).
   - `refusal-terminal-data`, `settle-batch-refusal!` and `record-attempt!` read only the producer's declaration.
   - Apply the whole patch in one commit, after 3cf5ff927 (checked: it applies).

## E6 carrier (agreed with rc-m4 through root)

- **The carrier is `(:seon.error/declared-schema (meta value))`**, attached by the producer to the ex-data or the error value.
  - `failure-value`'s `existing` branch and `error.refusal/refusal` preserve it, because `assoc` and `update` keep metadata.
  - `(merge {…} value)` would drop it; no settlement path does that today.
- `seon.error/prepare` refuses `:seon.error/base` as a declaration, so there is no fallback: a producer missing the carrier fails loudly at recording. That is a producer defect.

## What root must run

- **RESET NEEDED**, or `start --head`, to load 3cf5ff927 and any patches applied.
- Then `bin/test-check default --ns seon.error.declared-identity-test`, `seon.turn-test`, and `seon.render.entity-pairs-test`.
- The following already passed on the installed 8b4a10a44:
  - `bin/test-check default --ns seon.schema.predicate-metadata-refusal-test`
  - `--test seon.render.entity-pairs-test/declared-identity-and-producer-lookups-name-their-owners`
  - `--test seon.instrument-test/an-invalid-refusal-retains-its-evidence-at-the-kernel-boundary`
  - `--test seon.instrument-test/named-generators-survive-compilation-in-refusal-evidence`
- Live re-probe: `(seon.schema/call-with-forms {} 42)` must print "refused …", never "could not be built".
- After the patches: `seon.fn-test` (the dependency-analysis case at fn_test.clj:3111), `seon.schema-test/canonical-definition-keeps-admitted-predicate-symbols`, and the contracts compile test.

Tasks: 407264e2b69d, 7095e91b3f42 (same cause: the refusal was not over-bound, it failed to build) and 15a0784f82d5 are closed by 8b4a10a44 once installed.
