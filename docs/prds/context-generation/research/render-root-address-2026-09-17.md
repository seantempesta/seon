---
type: research
status: active
tags: [render, fixtures, write-admission, node-id]
---

# The render-coverage reds were a refused seed write, not a missing root

2026-09-17. Lane: render root address. Cluster `default`, pid 95853, all
evidence in-process through `mcp__seon__eval_clj` (jvm mode); no test JVM was
launched.

## What the reds actually were

`seon.render-coverage-test` batch 55 (20 F + 3 E, 3 F, 1 E) read as a
value-renderer defect: every HTML and AI render returned
`{:seon.error/kind :seon.render.value/missing-root-identity … :seon.agent/id nil}`.
It is not a render defect. `seed-entities!`'s single `db/transact!` was
REFUSED, so nothing was seeded and every render was handed `{}`:

```
seon.db/transact! refused transaction data at [3 :seon.schema.admission/source]:
expected the required key :seon.schema.admission/source with either :core or :agent
```

`{:seon.fn/sym "my.fs/read"}` is a map keyed by an identity attribute, so
`seon.db/write-error` validates it against the `:seon.fn` entity schema, where
`:seon.schema.admission/source` is required
(`resources/seon/schemas/seon.fn.edn:92`). One inadmissible map refuses the
WHOLE transaction. Removing it exposed a second: the turn row lacked its
required `:seon.turn/opened-tx`. And a third, once both were fixed — the effect
rows reference the turn by lookup ref, which Datahike resolves against the
database the transaction STARTS from, so a turn minted in the same transaction
is `:entity-id/missing`.

The fixture never looked at its transaction report, so all three refusals read
as behaviour. This is the fifth-plus sighting of the class already recorded in
`test/seon/issue_generate_test.clj:35`; the fixture now asserts every report.
`my.fs/read` is already a declaration in the canonical population
(`:seon.schema.admission/source :core`), so the row is deleted, not repaired.

Probe evidence, in `default`, with `seon.test-support/with-database`:

| probe | before | after |
|---|---|---|
| `(db/pull d '[*] [:seon.effect/id "effect-pending"])` | `{}` | full receipt |
| `(db/q '[:find [?e ...] :where [?e :seon.effect/id]] d)` | `[]` | 3 |

The adjacent finding
(`docs/seon/issues/an-open-map-keyed-only-by-universal-attributes-shadows-every-entity.md`)
is NOT this: selection was never reached, because the value was `{}`.

## The production hole that IS real

The assignment's test — can any production path reach `node-id` with no root —
is yes, proven in the live JVM:

```clojure
(#'seon.render/producer-argument
 {:seon.db/db d :seon.agent/id "probe-agent"
  :seon.render/output :seon.render/html
  :seon.render.call/id [:seon.render/html [:seon.ai.attempt/id "a1"] 1]
  :seon.render/value {:seon.ai.attempt/id "a1" :seon.ai.attempt/model "m"}})
;; keys: :seon.agent/id :seon.ai.attempt/id :seon.ai.attempt/model
;;       :seon.db/db :seon.render.data/total :seon.render/profile :seon.render/value
;; -> (value/node-id … []) => :seon.render.value/missing-root-identity
```

`seon.render/producer-argument` (`src/seon/render.clj:196`) dissociates
`:seon.render.call/id` before invoking a DECLARED producer, and the page's walk
request (`src/seon/render/web.clj:2977`) supplies no `:seon.render.value/root`.
So a declared producer that delegates its own value to the floor —
`seon.ai/attempt-html` (`src/seon/ai.clj:119`), whose contract promises
`:seon.render/hiccup` — returns a refusal where its own contract requires
Hiccup. `seon.render.transcript:427` already patches a call id in locally for
exactly this reason, and `seon.render.web/unit-id:347` patches a root in.

`node-id` now derives the missing address from the value's own installed
`:db.unique/identity` attribute (`src/seon/render/value.clj`, `identity-address`),
the same authority `seon.render/target-profile` (`src/seon/render.clj:110`)
already uses for requery identity. There is deliberately NO digest-of-value
fallback: `surface-id` (`src/seon/render/block.clj:61`) requires the
address-to-DOM-id map to be INJECTIVE, and two distinct anonymous roots holding
equal values would morph over each other under a content digest. A value with
no identity has no address and still refuses — both existing regressions
(`seon.render.value-test/anonymous-roots-refuse-instead-of-colliding`,
`seon.mcp-test`) are preserved by construction and verified green.

## A stale reachability claim

The deftest's last clause walked from the agent at distance 2 and asserted an
`:seon.effect/run` unit. The agent's declared `:seon.render/units`
(`resources/seon/schemas/seon.agent.edn:5`) are plan, issues, inbox, settings,
notes, namespace, runtime and steward errors — no turn and no effect — so that
unit cannot exist. Verified live: the agent walk returns 8 units, none of them
an effect; the turn walk returns only its root. The clause now walks from the
receipt, where the declared pair is genuinely selected under the walk's own
request shape. NOTHING declares effect receipts as units of anything today —
recorded here rather than invented in a test.

## Out of scope, reported not fixed

`seon.render-coverage-test/a-refused-render-producer-contributes-a-stable-typed-unknown`
still ERRORS with one uncaught contract violation, and the hunk is in
`src/seon/render.clj`, which this lane does not own:

```clojure
;; src/seon/render.clj:995-1004  invocation-unknown
    (unknown (cond-> {:seon.render.unknown/reason reason
                      :seon.render.unknown/producer selected
                      :seon.error/value (:seon.sci.admit/value result)}
```

A producer stopped by `time-limit` has NO value, so this passes
`:seon.error/value nil` into `seon.render/unknown`, whose
`:seon.render/unknown-request` requires a map — and the boundary that exists to
make a refusal total throws instead. AGENTS.md §3: absent = no key, never
stored nil. The one-line fix is to move `:seon.error/value` into the `cond->`
under `(:seon.sci.admit/value result)`. Not applied here.

## In-process results (cluster `default`, no test JVM)

Reloaded through `seon.test`'s own loader before every run
(`(#'seon.test/with-test-loader #(require 'ns :reload))`) — without it the
runner served stale test code and reported the identical pre-fix tally three
times.

| deftest | before | after |
|---|---|---|
| `effect-receipts-render-state-from-attribute-presence` | 12 pass / 20 fail / 3 error | **39 / 0 / 0** |
| `a-producer-that-delegates-its-own-value-is-never-re-entered` | 2 / 3 / 0 | **7 / 0 / 0** |
| `a-refused-render-producer-contributes-a-stable-typed-unknown` | 10 / 0 / 1 | **12 / 0 / 1** (the render.clj hunk above) |
| `seon.render.value-test/anonymous-roots-refuse-instead-of-colliding` | — | **3 / 0 / 0** |
| `seon.render.value-test/caller-supplied-block-ids-are-stable-and-distinct` | — | **3 / 0 / 0** |

`clj-kondo` on both edited files: 0 errors, 4 pre-existing warnings. Adoption on
`default` was not attempted (it is still refused for an unrelated in-flight
schema edit); the source change was proven by `(require … :reload)` in the live
JVM. The orchestrator's batched cold gate remains the proof.
