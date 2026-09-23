---
type: reference
status: gap note; read-only probes on default, no source edited
created: 2026-09-23
tags: [agent-platform, malli, instrument, errors, validation]
---

# Validation fails as data: what A1/B3 already cover, what is missing (2026-09-23)

Lane validation-as-data (Opus 5.5), research only. The owner asked (2026-09-23) that
"instrumentation checks the inputs and outputs", and that every failure inside
validation come back as a declared, flat `:seon.error` value rather than an unrelated
exception. Later additions: REPL input from an agent or a human is an **agent mistake**
and is never escalated to a panic. There is no handler per failure mode. The budget is
about 100 added src lines. The plan already specifies most of this work in
[A1](../../prds/agent-platform/plan/lane-a1-projection-carried.md) and
[B3](../../prds/agent-platform/plan/lane-b3-errors-tasks-dials.md). This note lists the
gaps and gives one paragraph per gap for the orchestrator to fold into A1 or B3.

Pins: Malli fork `reference-code/malli` at `8725a8cb`. The A1-8 and A1-8b fork commits
are **not landed** (`rg refuse src/malli/core.cljc` finds nothing, and `error.cljc` has
no `:vector`/`:fn` entries). Seon is at `afb44fc77` plus the dirty tree. Probes ran in
namespace `tmp.vad.probe` on `default` (pid 63253), MCP session `vad-probe`, JVM mode,
read-only. They call the original of `seon.instrument/compiled-wrapper` directly and
redefine no Var in `default`.

## What Malli already does (use it; do not rebuild it)

| Need | Malli function (file:line) | Used by Seon today? |
|---|---|---|
| One shape for every schema-machinery failure | `-exception`/`-fail!` → `(ex-info (str type) {:type type :message type :data data})` (`core.cljc:203-207`) | Partly: `registration-cause-data` digs `:data` (`instrument.clj:525-535`). The `:type` discriminator is never read. |
| A schema form that does not compile | `-lookup!` → `::invalid-schema {:schema :form}` (`core.cljc:329-333`) | Caught only in `apply!` (`instrument.clj:928-946`). |
| A ref that does not resolve | `-ref-schema` → `::invalid-ref {:ref}` eagerly (`core.cljc:1964-1971`). The failure is deferred to first validation for `{:lazy true}` refs and inside property registries (`::allow-invalid-refs`, `:314`). | Not handled at call time. |
| Validators and explainers compiled once per Schema | `-cached`, `validator`, `explainer` (`core.cljc:2626-2648`) | No: `m/explain` runs twice per refusal (`instrument.clj:287`, `:609`). |
| Violation kinds as report data | `-instrument-f` → `(report ::invalid-input/-output/-guard/-arity {…:schema})` (`core.cljc:2202-2220`) | Yes (`:report`, `instrument.clj:670-676`). |
| Per-problem leaf evidence | explanation `:errors` of `{:path :in :schema :value}` (`miu/-error`; `core.cljc:2656`) | Yes. Seon also keeps the whole value (G5). |
| Human text | `error-message`, `humanize`, `default-errors` (`error.cljc:44-172`, `:288-306`, `:374`) | A1-8 already plans the overlay. Not repeated here. |
| Formatting each failure type | `malli.dev.pretty` `v/-format` per `::m/…` type (`dev/pretty.cljc:50-160`) | No. It is a reference for which `:data` members each type carries. |
| Bounded check of a non-countable seq | `:every` (`:bounded`, `::coll-check-limit` 101; `core.cljc:736-742`, `:1490-1495`) | No. `:sequential` walks the whole seq. |
| A bounded offending value | **None.** `me/-error-value` fills a sequential up to the failing index (`error.cljc:189-193`, `:227-232`), so a bad element at index 10⁶ produces 10⁶ fill slots. | The bound belongs to the floor's bounded writer (error-floor E1). |
| Telling a broken predicate from a bad value | **None, by design.** `-safe-pred` returns false for a predicate that throws (`core.cljc:209`, `:1780`). The `:fn` explainer keeps only `(:type (ex-data e))` (`:1781-1788`). | `[:fn pos?]` on a string is meant to be "invalid". A thrown predicate is therefore NOT a signal (G2). |

Also already in Seon: while a refusal is being built, the report binds
`*compiling-contract*` true (`instrument.clj:672`). Every host-armed Var called inside
that extent then runs its original with no validation (`arm-var!` `:781-782`), so
building a refusal never validates through the contracts it might be reporting as
broken.

## Probes (today, file:line)

| # | Failure mode | Form (abridged) | Today | ms |
|---|---|---|---|---|
| 0 | Bad input, healthy validator | `((arm [:=> [:cat :int] :int] inc) "x")` | Declared refusal: `:seon.error/{at,layer,operation,member,expected,offending,problems}`, a correct message | 2.08 |
| F2 | Contract that does not compile | `(arm [:=> [:cat :tmp.vad/undeclared] :int] inc)` | Raw `ExceptionInfo ":malli.core/invalid-schema"`, keys `[:data :message :type]`, no `:seon.error/*` (`instrument.clj:656`, `m/schema`) | 0.13 |
| F3 | Ref that does not resolve | `[:=> [:cat [:ref :tmp.vad/nope]] :int]` | Raw `":malli.core/invalid-ref"`, same shape | 0.08 |
| F4 | Refusal schema missing from the registry | projection without `:seon.instrument/refusal-result` and without the `:compiled` holder | Raw `"Missing schema declaration :seon.instrument/refusal-result."` from `schema/projection-validator` (`schema.clj:3934-3946`), raised while the wrapper is built (`instrument.clj:658`) | 0.35 |
| F4b | Same registry change, `:compiled` holder kept | `assoc` of a new registry onto the live projection | **Builds, and refuses correctly**: `::refusal-validator` comes from the old holder (`schema.clj:399-418`). The cache is keyed by the holder, not by the registry it describes (a point-in-time mirror). | 0.38 |
| F5 | Input predicate throws | `[:fn tmp.vad.probe/boom?]`, where `boom?` always throws | Declared refusal "expected the declared predicate, got an integer 7. Fix: the declared schema". The throw is swallowed by `-safe-pred` (`core.cljc:209`), and the fix text is not useful. | 0.67 |
| F7 | Output predicate throws | same predicate on the output | "refused return value … got an integer 8" | 0.35 |
| F10 | Offending value whose `toString` throws | `reify` with a throwing `toString` | Declared refusal; `project-observation` coped | 0.59 |
| F6 | Huge value | `(conj (vec (range 1e6)) "x")` against `[:vector :int]` | Declared refusal, correct path `[1000000]`. `:seon.error/offending` **holds the whole 10⁶-element vector** (`instrument.clj:380`). `m/explain` 52.7 ms, total 74.0 ms, of which two explains ≈ 2 × 52.7. The healthy validator takes 4.75 ms. | 74.0 |
| — | Happy-path cost | 1,000 calls of the armed `count` wrapper | 0.17 µs per call armed vs 0.03 µs bare | — |
| F8 | Stale predicate callable | the projection's 89 predicate bindings vs the loaded Vars | All 89 are **Vars** (`clojure.lang.Var`), so they always call the current root. A stale callable is not a failure mode. The morning's "must be a print node" (`seon.print.edn:50`) was an OUTPUT refusal from a working validator, and its cause is not verified here. | 0.38 |
| F9 | Infinite lazy argument | `(range)` against `[:sequential :int]` | **Not run**: the host validator would spin a thread in `default` that cannot be interrupted. From source, the `:sequential` validator reduces over the whole seq (`core.cljc:1469-1520`, no `:bounded`). | — |

Every probe was sub-second. The largest was the F6 eval at 215 ms wall: it builds a
10⁶-element vector and explains it twice.

## The three rules that collapse the table

The wrapper already has the data needed to classify every row. It is Malli's own
discriminators:

1. **An exception whose `ex-data` `:type` is a `malli.core/*` keyword means the
   validator is broken. That is a core fault.** Malli raises every compile and
   resolution failure this way (`core.cljc:203`). This covers F2, F3, and a lazy
   `::invalid-ref` at call time. F4 is a Seon `ex-info` from `projection-validator`,
   and it joins this rule once that function throws Malli's shape or a declared
   registration error.
2. **The report key names who is wrong.** `::invalid-input` and `::invalid-arity` mean
   the caller is wrong: an agent mistake, returned as a declared error and never sent
   to the core-fault route. `::invalid-output` and `::invalid-guard` mean the function
   broke its own contract. On a host Var (`arm-var!`) that is a core fault. On an
   interpreted function (`wrap-interpreted`) it is the authoring agent's mistake. The
   member is already in the refusal as `:seon.error/member`
   (`instrument.clj:381-382`), so no new marker is needed.
3. **An exception thrown while building the refusal is a core fault.** It carries
   Malli's report data reduced to the base members that need no computation:
   `:seon.error/at`, `:seon.error/layer`, `:seon.error/operation`, `:seon.error/member`
   (from the report key), `:seon.error/expected` (`m/form` of the schema), a bounded
   `:seon.error/offending`, and the construction failure's chain. `m/form` is a
   `delay` on a compiled schema and cannot fail.

A predicate that throws is not a fourth rule. Malli defines it as "invalid"
(`core.cljc:209`), so F5 and F7 remain refusals under rule 2. The message should name
the predicate symbol instead of saying "the declared predicate". That text is A1-8's
overlay.

## Coverage and gaps

| Owner's failure mode | Covered by | Gap |
|---|---|---|
| Projection missing or broken at call time | A1-2: the wrapper becomes "a pure function of (retained contract, original, policy) and needs no projection at call time". A1-3 and A1-12: an absent projection "refuses by name". | None at call time. The per-call `supplied-projection` compile (`instrument.clj:785-812`) is A1-2's deletion. |
| Schema that does not compile | A1-1: the wrapper reads the retained contract `(mr/schema registry sym)`, so the call never compiles. | **G1**: how arming refuses, and that one Var's refusal fails alone. There is also a duplicate compile at arm time. |
| Ref that does not resolve | A1-1, for eager refs. | **G2**: lazy refs and property-registry refs raise `::invalid-ref` inside validation at call time. |
| Error schema unavailable | A1-8b (a refusing `:report`) makes the refusal the value. | **G3/G4**: when building the refusal throws, or its own schema is missing, nothing states what the wrapper returns. |
| Bad REPL input returned as a declared error, never a panic | B3 §2a constructs the value. AGENTS.md error policy. | **G6**: record mode commits *every* refusal as a fault, input refusals included (`instrument.clj:681-689`). No plan text draws the line between agent mistake and core fault at the wrapper. |
| Huge, lazy or cyclic value | B3 §2a "result" row: the live handle names the offending value. | **G5**: the thrown `ex-data` still holds the whole value. Two explains per refusal. **G7**: infinite seqs. Clojure values cannot be cyclic without a reference type, and the validator does not deref one. |

## Paragraphs to add

**G1 → A1, append to row A1-1 ("Build / convert").**
> Arming builds each Var's wrapper once and installs that same object. Today
> `apply!` compiles to check (`instrument.clj:928-946`), discards the result, and
> `arm-var!`'s `boot-wrapper` delay compiles again at first call (`:773-778`). Deleting
> the delay also means a call never meets an uncompiled contract. A construction
> failure is Malli's `-exception` (`core.cljc:203`: `{:type ::invalid-schema|::invalid-ref|… :data …}`),
> and an absent retained contract is `(mr/schema registry sym)` = nil. Either one
> becomes that Var's `:seon.instrument/registration-error`: operation
> `seon.instrument/apply!`, member the Var symbol, expected the authored contract,
> offending Malli's `:data` (`:schema`, `:form`, or `:ref`), plus the failure's
> `:seon.error/chain`. The other Vars still arm. The broken Var keeps its previous
> root, which is never silently unarmed, and `apply!` returns every refusal as data
> through its declared `[:or :seon.instrument/applied :seon.instrument/registration-error]`.
> This is a core fault: the caller (boot or adoption) routes it through
> `seon.fault/fault!` and panics under `:panic`. This replaces the first-failure `throw`
> (`:946`), which left every later Var on its old wrapper. Regression: a projection
> with one uncompilable contract arms every other Var, and `apply!` returns a
> registration error naming the Var and the unresolvable key that validates against
> the packaged `:seon.instrument/registration-error`.

**G2 → A1, new sentence in A1-8b.**
> The same fork commit catches exceptions raised by the compiled validator, not by
> `f`, inside `-instrument-f` (`core.cljc:2211-2218`). It reports them as
> `(report ::invalid-schema-at-call {:arm :input|:output|:guard :schema schema
> :exception e})` in Malli's own report idiom, so a lazy `::invalid-ref`
> (`core.cljc:1968-1971`) or a property-registry ref (`:314`) reaching validation
> becomes a report. Exceptions from `f` pass through unchanged. The wrapper turns this
> report key into a core fault (rule 1). The cost is a `try` around the validator call,
> which costs nothing on the non-throwing JVM path. Probe the C1 nanoseconds after it
> lands. Fork test: `[:ref {:lazy true} ::absent]` raises the report, `f` is not called,
> and a throwing `f` is rethrown unchanged.

**G3 → A1, A1-8b's `:refuse` paragraph.**
> `:refuse` builds the refusal inside one `try` (already under
> `*compiling-contract*`, `instrument.clj:672`, so no host contract validates during
> construction). If construction throws, the refusal is the base built from Malli's
> report data alone. Its members are `:seon.error/at`, `:seon.error/layer`
> `:seon.instrument/invocation`, `:seon.error/operation` fn-name, `:seon.error/member`
> from the report key, `:seon.error/expected` `(m/form schema)`, and
> `:seon.error/offending` bounded (G5). The construction failure goes in
> `:seon.error/chain`. This value is a core fault (rule 3), and the original violation
> kind survives in `:seon.error/member`. There is no handler per sub-step: explain,
> shape fingerprint (removed by A1-13) and observation projection all fail into this one
> catch.

**G4 → A1, same place.**
> The minimal refusal is always valid by construction: it contains only core values
> (Date, keyword, symbol, string) for the required members of `:seon.error/base`.
> `reject!`'s `refusal?` check (`instrument.clj:658-665`) applies only to the full
> refusal. When `refusal?` refuses it, or the refusal schema is absent at arming (F4),
> the wrapper throws the minimal refusal with `m/explain` of the rejected refusal in its
> chain, never "Instrumentation constructed an invalid refusal." with the invalid value
> as data. The shape is not hand-listed: the regression derives the required members
> from the packaged `:seon.error/base` (`m/entries`, non-optional) and asserts that the
> minimal value validates against it.

**G5 → B3 §2a, "construct" row, and A1-8b.**
> The wrapper's refusal carries Malli's per-problem evidence: `:in` and the leaf
> `:value` of each `:errors` entry (`core.cljc:2656`). Each leaf is bounded by the
> floor's bounded writer (error-floor E1: `*print-length*` and `*print-level*`, capped
> characters). `:seon.error/offending` never holds the whole checked value, and the
> whole value survives only as B3's live `result/e<id>` handle. `me/error-value` is
> not the bound (`error.cljc:189-193` fills to the failing index). Probe F6: today
> `:seon.error/offending` holds a 10⁶-element vector (`instrument.clj:380`). One
> `m/explainer` explanation, from Malli's per-Schema cache (`core.cljc:2642-2648`), is
> computed in `boundary-refusal` and handed to `violation`, which deletes the second
> `m/explain` (`:287` vs `:609`; 52.7 ms each on F6). Regression: the F6 input produces
> a refusal whose `pr-str` is under the floor bound and whose problem path is
> `[1000000]`, in under 70 ms.

**G6 → B3 §2a, the `:panic`/`:record` paragraph.**
> The wrapper records nothing. An `::invalid-input` or `::invalid-arity` refusal is an
> agent mistake. It is thrown, or returned under A1-8b, to the caller as a declared
> `:seon.error` value, and the evaluation boundary (REPL, MCP, SCI evaluation) returns it
> as the evaluation's answer. It never reaches `seon.fault/fault!`, and `:panic` never
> applies to it, so the session always survives. `::invalid-output` or `::invalid-guard`
> on a host Var, rule 1 (validator broken) and rule 3 (refusal construction failed) are
> core faults. The boundary that catches them calls `fault!` once and still returns the
> declared value to a REPL caller. The classification reads the refusal's existing
> `:seon.error/member` and `:seon.error/layer` and nothing else. This deletes the
> record-mode `commit-fault!` branch in `compiled-wrapper` (`instrument.clj:678-689`),
> which today commits input refusals as core faults and returns the refusal as the
> function's value. Regressions: (a) under `:record`, an SCI evaluation that calls an
> armed host function with junk returns the declared input refusal and commits no fault;
> (b) a host function whose output its contract refuses records one fault and the
> evaluation still returns the refusal.

**G7 → A1, §4 or the contract-authoring rules.**
> A host contract whose argument may be a non-countable seq declares `:every`, which
> Malli bounds at `::coll-check-limit` (101; `core.cljc:736-742`), not `:sequential`.
> `:sequential` reduces over the whole seq and cannot terminate on `(range)`, and host
> validation is not interruptible (AGENTS.md, "Bounded, event-driven execution"). This
> is an authoring rule plus one census query over contracts. It adds no wrapper code.

## Corrections found in passing

- B3 §2a says "the arming already exempts an arity declared as base (`::base?`,
  `instrument.clj:800`)". `rg '::base\?' src` finds nothing. That claim is stale and
  should be deleted.
- F4b: `projection-cache-value` answers from the `:seon.schema.projection/compiled`
  holder even after the projection's registry is replaced by `assoc`
  (`schema.clj:389-418`). A1's "second holder" row (A1 §1) already targets this holder.
  No separate issue is filed.

## Size

G1 −8 (delay and throw) +12. G2 is about 6 lines in the Malli fork and 2 in Seon.
G3/G4 +15. G5 −10 (second explain) +4 (uses E1's writer). G6 −12 (record branch) +3.
G7 adds no code. Net src is about +2 overall, and every slice adds under 20 lines. No
new namespace, handler, cache, or exemption. The floor needs no arming exemption for
anything called while a refusal is built, because that extent already runs unvalidated
(`instrument.clj:672`, `:781-782`).

## Collisions

`src/seon/instrument.clj` and `src/seon/error/refusal.clj` are held by error-floor
(ledger `tmp/orchestrator/file-ownership.md`). A1-8b and G2 edit the Malli fork, and
its row is held by shrink-schema-a according to the stale ledger row; confirm before
launch. `schema.clj` is free. G5 depends on error-floor E1's bounded writer.

## Owner decisions

1. **G1: a Var whose contract fails to compile.** (a, recommended) It keeps its
   previous root, and `apply!` returns the refusal as a core fault. The rest arms,
   nothing new is built, and the wrapper stays at the old contract until fixed. (b) It
   is armed with a wrapper that refuses every call with the registration error. That is
   louder at the call, costs about 8 lines, and makes every caller fail. (c) Keep
   today's first-failure throw. Nothing to build, but one bad contract leaves every
   later Var unarmed or stale.
2. **A1-8b returning the refusal as the function's value.** Returning a refusal map
   from a host function hands its callers a value their contracts do not declare.
   (a, recommended) Return it only at the evaluation boundary and throw the same
   declared value everywhere else. That keeps the owner's "REPL always returns
   something" and keeps host callers honest. (b) Return it everywhere, as A1-8b is
   written. Simplest fork change, but every caller's union has to admit the refusal.
   (c) Always throw. Nothing to change, and the evaluation boundary converts the
   throw.
