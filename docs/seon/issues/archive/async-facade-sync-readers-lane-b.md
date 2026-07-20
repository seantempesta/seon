---
type: issue
status: resolved
severity: defect
tags: [issue, database, agent]
---

# Sync-facade readers outside seon.warn consumed Promises as data

## Symptom

After `seon.db` became the async authority, callers written against the
removed synchronous facade treated returned Promises as values
(async-facade PRD, evidence in the 2026-07-20 cleanup audit). In lane B's
scope: the transcript's message renderers showed Promise labels
(`handlers/message.cljs` `resolve-ref`'s `(or (db/pull …) ref)` always took
the truthy Promise branch), `my.canvas/pinned` and
`seon.agent.web.internal/fresh-projection` read Promises with `contains?`
/ `seq`, `my.skills/skill-block` queried synchronously, and
`seon.eval/lookup-result`'s miss path read `db/entity` synchronously. Six
`seon.eval` fns plus `seon.render/renderable-inst` and
`seon.agent.testrun/latest-run` were caller-less superseded code.

## Resolution (2026-07-20, lane B)

- `6bed4b12` — reachability-gate deletions (rg-verified zero callers at
  HEAD): `seon.eval` `ns-rows-in-db?`, `synthesized-ns-head`,
  `reconstitute-ns-source`, `persisted-require-edges`,
  `persisted-require-targets`, `core-boot-fn-syms`;
  `seon.render/renderable-inst`; `seon.agent.testrun/latest-run` +
  `::latest-response`. Stale cross-references pruned in the same commit.
- `aadc8f33` — `seon.handlers.message` renderers made pure over
  acquisition-nested identity attrs; `resolve-ref` deleted. Live proof:
  `render-ai` over a nested-pull node renders
  `"[user] async-facade label proof 3"`; `render-html` hiccup carries the
  resolved label and contains no `Promise` text.
- `b3c15696` + follow-up hardening — `lookup-result`, `skill-block`,
  `pinned`, `fresh-projection` are `^:async` with awaited reads and
  errors-as-values; tests updated to the Promise contract.

## Latent bugs surfaced by the live proof (fixed in the hardening commit)

1. `my.canvas/pinned` passed `db/decode-edn-value` its arguments in
   (value, attr) order; the signature is `[attr value]`. Pre-existing —
   preserved by the first migration pass, caught by malli
   invalid-input during the live pinned round-trip, fixed with `some->>`.
2. Error detection by bare `(:seon.error/message x)` key presence
   false-positives on `installed-schema` maps: they carry
   `:seon.error/message` as an attribute IDENT key (value = its schema
   entry map). Guards must use the facade's own contract,
   `(string? (:seon.error/message x))`.

Live proof of the fix: transact a canvas pin for root, then
`(my.canvas/pinned {:seon.db/db dbv :seon.agent/id "root"})` resolved
`{:my.canvas/content [:div "async-facade lane B pinned round-trip proof"]}`
on the default cluster; pin and proof message retracted afterwards.
