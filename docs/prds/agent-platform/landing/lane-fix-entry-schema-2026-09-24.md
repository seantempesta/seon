---
type: landing
lane: fix-entry-schema
status: landed-unadopted
created: 2026-09-24
report: docs/research/agent-platform/three-entry-defects-2026-09-24.md §1
---

# Lane fix-entry-schema — `:nil` in agent contracts, `contract-error!` identity (2026-09-24)

What exists: `seon.schema.internal/assert-complete-schema!` walks each agent-authored
contract with `m/walk` and refuses undefined slots and nilable positions through
`contract-error!`. The fix edits that walk in place. `:nil` is Malli's exact singleton
(`-nil-schema`, `reference-code/malli/src/malli/core.cljc:811`, registered at `:2950`,
malli 8725a8cbd), so it leaves the undefined set. `[:or … :nil …]` now gets the rules
`[:maybe X]` already has, because both accept the same values.

## Broken or unproven first

- **Not installed in default.** Hook publication is off, and default runs the committed
  archive `data/source/ce73846828…`. `bin/test-check default --ns
  seon.schema.agent-nil-contract-test` answered "no eligible tests". All the proof below
  comes from a `git archive HEAD` snapshot plus this diff, run in a disposable JVM with
  the checkout's `clojure -Spath -A:test` classpath. Contracts were armed through the
  installed owner `seon.test.arm/initialize-contracts!` (1837 Vars). That JVM ran plain
  `clojure.test`, not `seon.test/run`. **Adoption into default is still pending.** It
  needs root to restart from the checkout or adopt, then rerun the named test.
- **Item (3) is out of my ownership, so it is not fixed.** `seon.schema/malli-form?`
  (`src/seon/schema.clj:1414-1448`) returns the same `false` for an EDN read failure and
  for Malli's parse refusal. `[:= nil]` fails because Malli reads a nil second element
  as the properties slot (`malli/core.cljc:2563-2568`). The message *"must be a
  parseable, EDN-readable Malli form"* comes from
  `resources/seon/schemas/seon.schema.edn:7`. Both files belong to other owners. The
  durable record is the research report §1. The smallest fix is to append "nil in the
  properties slot: write `[:= {} nil]` or `:nil`" to that `:error/message`.
- **Deviation from the report.** `contract-error!` identity is
  `[:or :keyword :qualified-symbol]`, not `:qualified-keyword`. The live projection
  registers the unqualified key `:inst`, so narrowing the keyword side would refuse an
  existing caller.
- **Consequence to know.** On agent re-entry, 111 packaged core contracts with a bare
  `[:or :nil X]` return are now refused as `:seon.schema/nilable-return`. That is the
  same kind of refusal `[:maybe X]` already got, by design. Before the fix, the same
  contracts crashed the helper.

## Change

- `src/seon/schema/internal.cljc`: `undefined-types` is now `#{:any :some}`. Adds
  `nilable-union?` (9 lines, contracted). The `:maybe` branch now tests
  `(nilable-union? schema)`. The map-value message names both forms. `contract-error!`'s
  identity now takes `[:or :keyword :qualified-symbol]`. Net +10 source lines.
- `test/seon/schema/agent_nil_contract_test.clj` (new, 88 lines) is the class regression
  and has four checks:
  - `add-note!`'s contract, entered verbatim by an agent, is admitted.
  - `:nil` is admitted in an argument union and as a bare return.
  - `[:or :nil X]` and `[:maybe X]` get the same refusal kind in the bare-return and
    map-value positions.
  - `:any` refuses as `:seon.schema/undefined-contract`, naming the function symbol.
  Separately, every loaded, EDN-round-trippable `seon.*` contract that mentions `:nil`
  (234 of them) is either admitted or refused with a declared `:seon.schema/error`. None
  is refused for `:nil`, and none is refused through the helper's own contract.

## Evidence (snapshot JVM, armed)

| Probe | HEAD | HEAD + diff |
|---|---|---|
| `add-note!` contract re-entered as agent | helper crash, "contract-error! refused identity … expected a keyword, got a symbol", 2.5–2.9 ms | `:admitted`, 44–59 ms |
| Same contract with `[:maybe :my.note/about]` (like-for-like admitted path) | 55–64 ms | 49–51 ms (no regression) |
| Regression test body | 508 failures of 758 assertions, 1282 ms | 368/368 pass, 1413 ms |
| Unarmed test body (JVM warm after require) | 239 failures | pass, 5167 ms including a 4262 ms cold first `build-projection` |

Live JVM before the fix, on the database projection (`projection-from-database`): the
`add-note!` re-entry fails with the helper crash in 5.5 ms. Its hidden
`:seon.error/offending` is `:seon.schema/undefined-contract … uses :nil`, which matches
the report.

Timings: arming takes 4.3–5.2 s. That is snapshot-JVM acquisition, not ordinary work.
The first `build-projection` in a fresh JVM takes 4.26 s; the warm one takes 92 ms, and
257 ms in the live JVM. The test body is inside the 5 s bound when warm. When it is cold
in a fresh test JVM, the first projection build dominates it. The live JVM is warm.

Lint: `clj-kondo` shows 0 new findings. The 8 warnings in `internal.cljc` already exist
on HEAD.

## Commits

See `git log -- src/seon/schema/internal.cljc` (lane fix-entry-schema). Released paths:
`src/seon/schema/internal.cljc`, `test/seon/schema/agent_nil_contract_test.clj`, and this
note.
