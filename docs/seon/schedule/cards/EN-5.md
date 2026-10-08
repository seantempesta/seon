---
title: "Card EN-5: Tests need no attempt agent"
type: reference
status: active
created: 2026-10-08
---

# EN-5 Tests need no attempt agent

Ledger row EN-5 (S6-02b, group E). Design: `redo/entrants.md` §3 "No attempt turn". Lane: Sonnet. Cited at HEAD
`e7f120672`.

## What exists
An effect-reaching member gets a throwaway agent and an open turn: `attempt-writers` (`src/seon/test.clj:179-182`),
`attempt-request-context` (`:184-214`, one transaction, "350-440 ms on a member branch"), the
`:seon.test/effect-attempt?` flag (`bounded-result` `:235`, `:257-264`; `member-result` `:2017`, `:2087`), and the
per-request reach walk `(runner/tests-reaching database (vec ordered) 'seon.effect/request*)` (`:2428-2434`).

## Change
- The fold binds the member's request context (EN-4's member world): `seon.effect/*request-context*` is bound by the
  fold's eval stage from the member world (`:seon.turn/id` = the member's eval request, ordinal 0, the member's
  connection, ctx, cluster, caps, `:seon.effect/counter (atom -1)`), the map `attempt-request-context` builds today
  minus the agent and turn writes. Put the binding in `member-result`'s body thunk (where `with-bindings` is today,
  `:264`).
- DELETE `attempt-writers`, `attempt-request-context`, `:seon.test/effect-attempt?` (contract keys and call sites), the
  `effect-reach` walk and its argument to `member-result`.
- `seon.effect/request*` (`src/seon/effect.clj:735`): a request with `:seon.effect/background?` while
  `*request-context*` names a test member refuses before its receipt opens with a new variant
  `:seon.effect/background-in-test` (`seon.effect.edn`: `[:and :seon.error/base [:map [:seon.effect/capability :qualified-symbol]]]`,
  added to `request*`'s returned error union). "Names a test member": the request context carries
  `:seon.test/member? true` (set by the member binding above).

## Proof
- `seon.test-test/a-test-effect-needs-no-attempt-agent` (new): a test whose body calls a stub capability and pulls its
  own receipt; it passes; the receipt's opening transaction carries the member's eval request (EN-1); no agent whose id
  starts with `"attempt-"` exists on the member branch (the body asserts it).
- `seon.test-test/a-background-effect-in-a-test-is-refused-by-name` (new): the body's background request answers
  `:seon.error/type :seon.effect/background-in-test`.
Convert `test/seon/test/effect_attempt_test.clj` to these (delete its attempt-agent assertions).

## Files / commit
`src/seon/test.clj`, `src/seon/effect.clj`, `resources/seon/schemas/seon.effect.edn`, `resources/seon/schemas/seon.test.edn` (the flag), `test/seon/test_test.clj`, `test/seon/test/effect_attempt_test.clj`.
`bin/lane commit <lane> -m "EN-5: test effects use the member's own request context; no attempt agent" --test seon.test-test --test seon.test.effect-attempt-test --test seon.effect-test src/seon/test.clj src/seon/effect.clj resources/seon/schemas/seon.effect.edn resources/seon/schemas/seon.test.edn test/seon/test_test.clj test/seon/test/effect_attempt_test.clj`

## Cost
Expected: an effect-reaching member saves one commit (350-440 ms by the code comment) and the request saves the
reverse reach walk (18 ms over 2,365 tests).

## Dependencies
EN-4. Reset: none.
