---
type: research
status: proposed
date: 2026-10-08
lane: design-cost
---

# Focused instrumentation: validate where defects enter, not everywhere a value passes (design-cost, 2026-10-08)

**What exists.** `seon.instrument/arm-var!` wraps every contracted host Var once per adoption: an outer function that
times the call into the Var's `seon.profile` cell, around `seon.instrument/compiled-wrapper`, which is Malli's
`m/-instrument` with the scope fixed at `#{:input :output :guard}` (plus `seon.call-preparation/preparing` when the
contract declares a supplied default). Validators are compiled once at arming (`-instrument-f` in our Malli fork
`418df441`; registry schemas share one cached validator since fork `a89ebef0`; a passing `:cat` check allocates
nothing since `43c6dca8`). A named predicate in a schema (`[:fn seon.db/database-value?]`) resolves through
`seon.schema/runtime-predicate` to the **Var**, so each schema check calls the predicate's own armed wrapper.
**Data flow:** call → profile timer → arity + input validator → guard → body → output validator → value; on a refusal,
`m/explain` builds the error. **Principal approach:** keep every contract compiled and every wrapper's profile counter
(the justified counters), and spend validation where a defect can enter: entrant boundaries, the checked writer,
values crossing a namespace or agent boundary, agent-authored functions. Values already validated upstream are
handed on as values whose schema is a class check.

Owner input (2026-10-08): "We own the malli specs so we can control how the checks happen and we can make them cheap."
So the options below include schema-level and Malli-fork-level changes, each priced.

## 1. Measurements (default, 2026-10-08)

| Measure | Value |
|---|---|
| Armed `seon.db/database-value?` per call | 0.176 µs; its original 0.0064 µs; an `instance?` check 0.008 µs (27× for the wrapper) |
| Armed `seon.db/schema-database` per call | 0.47 µs vs 0.06 µs original |
| `:seon.db/database-value` validator (projection registry) | 0.15 µs: it calls the armed predicate |
| One-clause `seon.db/q` | 47.7 µs armed; 34.6 µs with the outer wrapper removed (inner calls still armed); `d/q` 2.2 µs |
| `seon.db/q`'s guard `query-call-valid?` | 2.9 µs unarmed (it parses the query, which `q` parses again) |
| `seon.db/q` returning 5,816 rows | 4.9 ms armed, 5.0 ms outer unarmed: output `:seon.schema/value` is `:any`, so the cost is Seon's decode, not Malli |
| One `:ping` eval request (quiet) | 94 ms; 12-14 k armed calls over 461 distinct functions |
| Of the `:ping`'s armed calls (one noisier window, 40 k calls over 509 functions) | private functions 61% of calls (279 functions); one-argument boolean predicates 24% (46 predicates: `seon.program/source-family?` 3,503, `seon.db/database-value?` 2,219, `connection-object?` 633, `transaction-report-datom?` 560, `seon.env/environment?` 438) |
| Old `my.program/breaks` on a namespace | 1,812,010 `database-value?` calls in one call (≈0.3 s of 5.9 s at today's per-call cost) |
| Health top | `seon.cache/cache?` 6.4 M calls (1.0 s), `seon.schema/pull-selector?` 306,701 calls (1.47 s, 4.8 µs each: a walk) |

**What this says.** At 0.15-0.5 µs per armed call, a `:ping`'s 13 k armed calls cost about 2-6 ms of 94 ms; the
earlier Opus measurement put armed validation under 3% of JVM CPU after fork `43c6dca8`
(`docs/research/agent-platform/landing/contract-cost-opus-2026-09-26.md`). **Focusing instrumentation alone will not
make ordinary work fast**; the call *counts* are produced by whole-program algorithms
(`whole-program-per-call.md`): 1.8 M predicate calls in `breaks` were a per-function walk, not a costly check. The
validation that does cost is of four kinds:

1. **Nested armed predicates.** Every schema check of a database value, connection, environment or context is itself an
   armed call with input, output and a profile timer. A quarter of all armed calls, and they crowd the profile and
   health ranking (`cache?` flagged untested-hot at 6.4 M calls).
2. **Guards that redo the callee's work.** `query-call-valid?` parses the query `q` parses next (3 µs of a 13 µs outer
   wrapper).
3. **Collection schemas re-walked at every layer.** `transaction-report-datom?` 21 k calls for 157 datoms per `defn`
   (each datom checked ~130 times by nested helpers, PB-10); `pull-selector?` walks a selector per call;
   `declaration-content-key`'s output schema had to be loosened because walking 30 k datoms cost 148 ms.
4. **Output then input of the same value.** f's output is g's input under the same named schema, validated twice
   at each internal hop.

## 2. Which checks catch real defects

- **Catch:** entrant boundaries (MCP arguments, an agent's SCI call into `my.*` or any host function, a test body);
  the checked writer (the authority: it refuses a bad fact before it is durable); a value crossing a namespace
  boundary (the producer and consumer have different owners); agent-authored functions (`wrap-interpreted`); refusal
  paths (`m/explain` only runs there).
- **Re-validate:** an identity predicate used as a schema (the class is the whole check, and the predicate's own
  contract `[:=> [:cat :any] :boolean]` has a vacuous input and a trivially satisfied output); a private helper's input
  that its same-namespace caller just validated or constructed; the next layer's re-check of a collection the writer
  already checked; guards that repeat the body's own parse.

## 3. What AGENTS.md requires, plainly

"Contract every admitted function, private ones included" requires that a complete contract **exists**, compiles
against the projection, and has reaching tests; generative checks (`malli.generator/check`) exercise it. That is
compatible with every option below. But "Keep entry policy separate from merge" also says **"Arm every valid contract:
invalid inputs prevent entry, invalid outputs refuse."** As written, that forces full input-and-output validation on
every call of every private host function in production. Options I1-I3 and I6 stay within it (they make the checks
cheap or remove checks that are vacuous). Option I4 does not: it needs the owner to amend that sentence.

## 4. Options (simplest viable first; recommended marked)

| Option | Level | Guarantee | Price | Sacrifice |
|---|---|---|---|---|
| **I1. Predicates in schemas call the original** (recommended) | Seon: `seon.schema/runtime-predicate` returns a function that reads the Var's root at call time and calls `malli.instrument/-f->original` of it, so redefinition still takes effect | unchanged: the predicate's contract is vacuous (`:any` in, boolean out of a total predicate) | one function; ~24% of a `:ping`'s armed calls leave the wrapper; a database schema check 0.15 → ~0.01 µs | schema-internal predicate calls no longer count in the predicate's profile cell (direct calls still do) |
| **I2. Identity schemas are class checks** (recommended, with I1) | schema EDN + Seon registry: an `:seon.schema/identity-only` schema (already a declared property, e.g. `:seon.db/database-value`) compiles to Malli's stock `m/-simple-schema` with an `instance?`/`satisfies?` predicate; no fork | never walks; same accepted set as the predicate | one slice in `seon.schema` (registry composition) + the ~65 named predicates' schemas checked to be identity-only; ~0.008 µs per check | identity schemas need a host class (fine: they describe host objects) |
| **I3. Contract-declared scope; trivially true arms elided** (recommended) | Seon: `compiled-wrapper` reads `:scope` from the contract's properties (Malli's `malli.instrument` already passes per-function `:scope`; Seon hardcodes it). Fork: `-instrument-f` skips an arm whose schema is `:any`/`[:* :any]` | identical refusals: an elided arm can never refuse | fork ~10 lines + test; Seon ~5 lines; per call: one validator call fewer for every `[:cat :any]` input or `:any` output | none |
| I4. Boundary arming: full scope at boundaries, profile-only for private host functions in ordinary execution, full scope inside test members | Seon: compile both wrappers at arming; the wrapper picks by the calling world, which `seon.instrument/calling-environment` already reads (whether it marks a test member is to verify) | every value crossing a namespace, agent, storage or test boundary is checked; an internal defect is caught one hop later (at the namespace's public output or the writer) or by the reaching tests | ~2 compiled wrappers per Var (2,100 Vars); one world read per call (~20 ns); removes validation from ~61% of armed calls | **contradicts "Arm every valid contract"**; error locality inside a namespace is one hop worse in production |
| I5. Validate once per value per eval request | Malli fork: a schema property (e.g. `:seon.schema/walks true`) compiles a validator that first consults an identity set bound at the entrant (`IdentityHashMap`, conveyed with `bound-fn*`) | a walked collection is walked once per request; immutable values only (host objects are identity schemas anyway, I2) | fork ~40 lines; a binding at the entrant; memory for one set per request; per hit about the cost of a small validator, so it pays only for walkers | a second, dynamic validation path in Malli; only measured walkers should carry the property |
| **I6. Typed hand-off at the producer** (recommended where a producer validates a large value: the writer) | Seon: the producer validates once and returns a value whose schema is an identity check (PB-10's `:seon.db/checked-report`); internal helpers declare that schema | stronger than I5: only the validator constructs the type | one slice per producer, inside PB-10 for the writer | a record type per handed value; needs the owner's word that a record is not a "bespoke object" (it is a map with a class) |
| I7. Drop guards that repeat the body's parse | Seon: `seon.db/q`'s guard checks become the body's own refusal (it already returns `:seon.db/invalid-read-error`) | same refusals, from the body, as data | one slice in `seon.db`; ~3 µs per `q` | the refusal is built by the body instead of the wrapper (one owner instead of two) |
| I8. Disarm production | — | — | — | rejected by the owner (2026-09-26) and here: contracts at boundaries catch real defects |

**Recommendation.** I1 + I2 + I3 now (no owner ruling needed, no guarantee lost), I6 inside PB-10, I7 with the read
path slice. Measure again; take I4 only if the remaining armed share of an ordinary pass is still material after
`whole-program-per-call.md`'s slices, and only with the owner's amendment. I5 last, for named walkers only.

**Expected effect.** On a `:ping`: ~25% fewer armed calls (I1), a few ms of 94 ms; the larger value is a clean
profile (the health ranking stops naming identity predicates). On a `defn`: PB-10's 14 MB writer validation (I6). The
pass costs themselves fall through the algorithmic slices, not these.

## 5. Slices (about 15 minutes each; measured-cost proofs count work)

1. **F1 — Schema predicates call originals** (`seon.schema/runtime-predicate`). Proof:
   `seon.schema-test/a-schema-predicate-calls-the-unarmed-predicate` — validating `:seon.db/database-value` grows
   `seon.db/database-value?`'s cell by zero calls, and a redefined predicate is still seen. Parent: 2,219 calls per
   `:ping`.
2. **F2 — Contract scope honored; trivially true arms elided** (Malli fork `-instrument-f`, `seon.instrument/compiled-wrapper`).
   Proof: a fork test (an `:any` input arm calls no validator) and `seon.instrument-test/a-contract-declares-its-scope`.
3. **F3 — Identity-only schemas compile to class checks** (`seon.schema` registry). Proof: validating each
   `:seon.schema/identity-only` schema calls no armed function; equality of accepted sets over the generators.
4. **F4 — The writer hands a checked report** (within PB-10). Proof: PB-10's `a-commit-validates-its-report-once`
   (`transaction-report-datom?` calls = report datoms).
5. **F5 — `seon.db/q` refuses a malformed call in its body**, guard removed. Proof: the existing refusal tests for
   malformed queries stay green; one-clause `q` armed overhead measured before/after.
6. **F6 (owner ruling first) — Boundary arming** (I4). Proof: a private function's bad output is refused inside a
   test member and at the namespace's public output in an ordinary pass.

## 6. Open owner questions

1. Does "Arm every valid contract" mean every call of every private host function is validated in production? If
   yes, I4 is off the table and the remaining cost is accepted; if it can read "arm every contract, validate at
   boundaries in production and everywhere in tests", I4 applies.
2. I1: may a predicate called from inside a schema skip its own (vacuous) contract and profile cell?
3. I6: is a record type for a validated value (a map with a class) acceptable under "avoid bespoke objects"?
4. I5: worth a dynamic validation path in our Malli fork at all, given the measured cost of a small check is about
   that of the memo lookup? Recommendation: no, unless a named walker remains after I6.
