---
type: issue
status: open
severity: blocker
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
