---
type: issue
status: resolved
severity: friction
created: 2026-09-17
tags: [effect, contract, capability, class/p1]
---

# The effect door validates a one-argument request against a two-argument owner

## Problem

`seon.effect/accepts-request?` (`src/seon/effect.clj:178`) decides whether a
capability request satisfies its owner:

```clojure
(schema/function-accepts-in? projection owner-sym [request])
```

`function-accepts-in?` validates the **complete declared input contract**
(`src/seon/schema.clj:3093` says so in its own docstring). Every capability
owner takes TWO arguments — the request and the effective config the executor
hands it:

```clojure
;; src/seon/web/jvm.clj:291
[:=> [:cat :my.web/fetch-request :seon.config/effective] …]
;; src/seon/fs/jvm.clj:483
[:=> [:cat :my.fs/read-request :seon.config/effective] …]
```

So the check compares a ONE-element argument vector against a TWO-argument
contract and can only ever answer false. Every request then takes the
`:seon.effect/invalid-request` branch — "The capability request does not
satisfy its owner contract" (`src/seon/effect.clj:720`).

## Measured, live (2026-09-17, default pid 74930)

```clojure
(let [p (seon.db/carried-projection (seon.db/db c))]
  {:one-arg (schema/function-accepts-in? p 'seon.web.jvm/fetch [request])
   :two-arg (schema/function-accepts-in? p 'seon.web.jvm/fetch [request effective])
   :fs-one  (schema/function-accepts-in? p 'seon.fs.jvm/read [{:my.fs/path "/tmp/x"}])})
;; => {:one-arg false, :two-arg true, :fs-one false}
```

## How it surfaced

`seon.web.jvm-test/public-fetch-settles-text-and-binary-body-representations`
and `…/public-search-settles-one-receipt-with-provider-credits` (batch 75).
Their fixture used to be refused earlier — it wrote a config ROW that write
admission rejects — so the door was never reached. Once the fixture-write
sweep made that seed honest, the request reached the door and the door refused
it. The tests did not regress; they started arriving.

This is the project's named class from the other side: a check that had never
run reads as health.

## Fix shape

Either the door validates only the argument the caller supplies — the REQUEST,
against the owner's first declared input — or it supplies the effective config
it is about to hand the handler and validates the complete call. The second is
the one that keeps `function-accepts-in?`'s "complete contract" meaning
honest.

Introduced with the projection-driven request writer, `0e15593aa` ("Record
capability requests as facts and edits as write-back provenance").
Not repaired by the fixture-write lane: this is the effect owner's slice.

---

## Refuted — the door asks the OWNER, and every owner accepts (2026-09-16)

The measurement above asks about `seon.web.jvm/fetch` and `seon.fs.jvm/read`.
Those are HANDLERS. The door never names a handler: `request*` computes
`owner-sym` from the Var the caller passed
(`seon.effect/owner-symbol`, `src/seon/effect.clj:164`), and a capability owner
passes its own Var — `(effect/request! #'form! request)`,
`src/my/edit.clj:59`. Every owner takes exactly one argument, the request, so
`[request]` IS the complete declared input contract for the symbol the door
actually asks about.

Measured live, `default` pid 88182, jvm mode, explicit custody:

```clojure
;; the same question, asked of the owner instead of the handler
{:owner-arity-1   {my.web/fetch true, my.fs/read true, my.edit/exact! true}
 :handler-arity-1 {seon.web.jvm/fetch false, seon.fs.jvm/read false}}
```

And for the whole population — the ten symbols carrying
`:seon.fn/capability-fn`, each handed a request GENERATED from its own declared
input schema — the door answers **true for all ten**: `my.edit/form!`,
`my.edit/exact!`, `my.edit/lines!`, `my.fs/read`, `my.fs/write!`,
`my.fs/glob`, `my.fs/stat`, `my.web/fetch`, `my.web/search`,
`my.shell/run!`.

That also answers the question the report raises about the effect-facts
trials: `my.edit/exact!` succeeded through the ordinary door because the door
asked `my.edit/exact!`, a one-argument owner. There is no second path and no
twin.

**Attribution is wrong twice.** The call site is not from `0e15593aa` — that
commit's diff touches neither `accepts-request?` nor `owner-sym`;
`git log -S "function-accepts-in? projection owner-sym" -- src/seon/effect.clj`
names `b80f78a7c` ("Carry schema projections through reads, admission and
transaction reports", 2026-09-15). And the line it wrote is correct.

**The web tests' red therefore has another cause**, still open for that lane.
The effect-facts lane met the same shape one batch earlier: a fixture that
seeds an agent and a turn but no compiled config row leaves the branch with no
`:seon.config.fs/*` (or provider) dials, and the resulting failure is
unclassified — see
[filesystem dials](filesystem-dials-absent-throws-a-bare-nullpointerexception.md).
Check what `config/effective` answers on that fixture's branch before
suspecting the door.

**Kept, not closed empty.** The class the report names is real even though
this instance is not: a door that asks the wrong contract refuses everything,
and a door that asks nothing admits everything, and neither shows up in one
capability's own test. `seon.effect-test/every-capability-owner-accepts-its-
own-request-at-the-door` now derives the owner population from
`:seon.fn/capability-fn` facts, cross-checks it against the
`:seon.effect/capability` markers, and generates each request from that owner's
own declared input schema — so a capability declared tomorrow is covered on
the day it declares, with no list. It also refuses to pass by examining
nothing. Green in process: 12 assertions, 0 failures.

## REFUTED 2026-09-17 — the probe asked the wrong symbol

The orchestrator's A/B refuted this: the door asks the OWNER VAR, and every
capability passes. The probe above queried the program graph for
`seon.web.jvm/fetch` — a PRIVATE `defn-` whose declared contract is the
two-argument handler — while `accepts-request?` resolves the capability
through `my.web/fetch`'s `:seon.effect/capability`, whose declared input IS
the one-argument request. The false/true pair measured above is real, but it
measures a symbol the door never asks about.

The two `seon.web.jvm-test` errors belong to the fixture class after all: the
seed leaves the branch without compiled fs dials, and `seon.fs.jvm` throws a
bare NPE reading them. Repaired with the rest of that namespace's seeds.

This note stays as the record of a wrong attribution, so the same probe is not
repeated. Naming it beats deleting it: the lesson is that a program-graph
question about a capability must ask the capability's DECLARED owner, not the
handler var behind it.

## ROOT CAUSE, measured on the batch-85 published base (2026-09-17)

Third attribution, and this one is measured on the exact store the red ran
against (`target/test-published-bases/f1bb1c8…/base`, copied with `cp -Rp`,
reidentified, read in default pid 88182).

The door is right and the request is right. What was wrong is the PROJECTION
the fixture's database carried.

`accepts-request?` asks `(db/carried-projection database)` first
(`src/seon/effect.clj:180`). A branch connection opened straight off a store
(`seon.test-support/with-published-file-database` →
`seon.cluster.store/open-branch!` → `d/connect`) carries no projection, so
`db/db`'s fallback hands it the thread's projection
(`src/seon/db.clj:199`) — in a `bin/test` worker that is the PACKAGED
projection `seon.test.arm/packaged-test-projection` builds from
`schema/declaration-projection`, which carries schema forms and **no**
`:seon.schema.projection/function-contracts` key at all
(`src/seon/schema.clj:1068`). `function-arities-in` then answers `[]` for every
symbol, `function-accepts-in?` answers false, and the door refuses every
capability request alike with `:seon.effect/invalid-request`.

Measured on that base, `:current-src`:

```clojure
{:carried-is-packaged true
 :packaged-contracts  0      ; function contracts in the carried projection
 :derived-contracts   1118   ; (schema/projection-from-database db)
 :accepts-under-carried false
 :accepts-under-derived true}   ; my.web/fetch and my.web/search both
```

And on a fresh branch of that base, under the same packaged handed projection,
with the fixture's own seeds applied:

```clojure
{:before-carry false :after-carry-fetch true :after-carry-search true
 :timeout-dial 1000}
```

The canonical in-memory base already carries its own program for exactly this
reason (`test/seon/test_support.clj:398`: "The worker's bootstrap projection
has schema forms, but no populated function contracts"). The file-backed
fixture did not, so `seon.effect-test/every-capability-owner-accepts-its-own-
request-at-the-door` passed (canonical base) while the two `seon.web.jvm-test`
public-capability tests errored (file base).

Repaired in `test/seon/web/jvm-test`'s `with-file-database`, which now derives
`schema/projection-from-database`, carries it with
`db/carry-connection-projection-state!` and runs the body under
`schema/call-with-projection-state`, and both tests now assert the door's own
verdict on the exact request they send.

**The class is not fully dead.** Any fixture on
`seon.test-support/with-published-file-database` that crosses the effect door
has the same hole (`test/seon/shell/jvm_test.clj`,
`test/seon/background_blob_test.clj` are the other two callers). The carry
arguably belongs IN `with-published-file-database` itself — a branch of a
published base always has a program to derive — but that file is held by
another lane; left for its owner.
