---
type: landing
status: G1 landed; G2 fork committed and pushed, pin pending restart; G3/G4 in progress
created: 2026-09-23
---
# A1-1 (G1) and A1-8b (G2, G3/G4): arming builds once, validation fails as data

**What Malli and the wrapper already do here.** Malli's `-instrument-f`
(`reference-code/malli/src/malli/core.cljc:2202-2220`) compiles the input/output/guard
validators once per wrapper and raises every compile or resolution failure as
`-exception` `{:type ::invalid-schema|::invalid-ref … :data …}` (`core.cljc:203`); Seon's
`apply!` built that wrapper only to check it and discarded it, and `arm-var!`'s
`boot-wrapper` delay built it a second time at the Var's first call. REPL (pid 94821,
namespace `tmp.a1arm.probe`): `@#'seon.id/valid?` armed 1,224 ns vs bare 178 ns per call.

**Smallest composition.** Keep the one `compiled-wrapper` result and hand it to `arm-var!`
(the delay is deleted); a failed build becomes that Var's existing `registration-error`
value instead of a `throw`, so the rest of the batch arms. In the fork, the validator call
inside `-instrument-f` is wrapped so a throwing validator is a Malli report
(`::invalid-schema-at-call`), never the function's error.

## G1 — arming compiles each contract once; a bad contract refuses alone

Braids removed: "check that it compiles" and "install the wrapper" were two constructions
of the same object (in `apply!` and `restore!`, then again in the delay); one bad contract
aborted the whole batch (a first-failure `throw`), so its failure braided every later Var's
arming state. Each part now has one role: `compiled-wrapper` builds; `arm-var!` installs
the object it is given; `apply!` maps pending Vars to either an installation or a refusal.

Cost: arm time is one construction per pending Var (was two); call time loses the delay
deref. Nothing per call was added.

Changed: `src/seon/instrument.clj` (`arm-var!` takes `boot-wrapper`, a callable, in place
of the bootstrap projection; `apply!` keeps over pending Vars and returns the first
registration error, naming any further refused Vars in its message, with the compile
failure's whole `:seon.error/chain` via `seon.error.refusal/diagnostic`; `restore!` compiles
the replacement set once and installs those wrappers). `test/seon/instrument_test.clj`:
`registration-failure-names-the-var-and-authored-contract` asserted the retired
first-failure throw (a retired assumption) and is replaced by
`one-uncompilable-contract-refuses-alone-and-the-rest-arm`.

Callers: `seon.cluster` boot (`cluster.clj:2396`) and adoption (`arming-refused?`,
`cluster.clj:2409`) already refuse loudly on `:seon.instrument/registration-observation`;
`seon.test.arm` (`arm.clj:190`) throws on it. Routing that value through
`seon.fault/fault!` is the callers' step (cluster.clj, not this lane's file).

### Evidence (default pid 5070, booted from the checkout with this WIP; session `a1-arming-3`)

```clojure
;; throwaway Vars in tmp.a1arm.probe; projection = packaged declaration projection
;; plus a map registry {broken [:=> [:cat [:ref :tmp.a1arm/absent]] :int], healthy (m/schema [:=> [:cat :int] :int])}
(seon.instrument/apply! {:seon.config/on-core-error :panic :seon.schema/projection projection
                         :seon.instrument/changed-identities #{[:seon.fn/sym broken] [:seon.fn/sym healthy]}})
```

Result: 1.63 ms; `:seon.error/member` and `:seon.instrument/fn` = the broken Var,
`:seon.error/expected` = the authored contract, `:seon.error/offending` = `:tmp.a1arm/absent`,
`:seon.error/chain` 1 link, validates against the packaged `:seon.instrument/registration-error`;
the broken Var kept its previous root; the healthy Var armed, returned 3 for 3 and refused
`"x"` with member `:seon.fn.arity/input`. The four touched contracts (`arm-var!`, `apply!`,
`restore!`, `compiled-wrapper`) compile against the packaged projection; boot armed 1,855 Vars.

Armed-call probe (`@#'seon.id/valid?`, 200,000 calls after 20,000 warm-up): before, pid
63253, 1,350 ns armed / 187 ns bare; after, pid 94821, 1,224 / 1,197 ns armed vs 178 / 171 ns
bare. No slowdown (different JVMs, same probe form). A1's ≤ 2× target is not this slice's.

### Verification limit

`bin/test-check default --policy incremental --changed seon.instrument/apply! --changed
seon.instrument/arm-var! --changed seon.instrument/restore! --test
seon.instrument-test/one-uncompilable-contract-refuses-alone-and-the-rest-arm` refused in
19.9 s wall before running a member: "Program acquisition found a namespace binding cycle"
(`seon.fault → seon.flow → seon.fault`, since `ac3d8b2fd`: `fault.clj` requires
`[seon.flow :as-alias flow]` and the indexer records it as a load dependency). Already
reported in `lane-leak-fix-2-2026-09-23.md` §Blocker. The regression itself is therefore
unrun through `seon.test/run`; the REPL probe above exercises the same `apply!` path.
The 19.9 s is the refusal plus request setup; the refusal itself is the defect named there.
