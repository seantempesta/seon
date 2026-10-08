---
title: "Card CS-03a Assemble the model's messages"
type: reference
status: active
created: 2026-10-08
---

# CS-03a Assemble the model's messages

Ledger row CS-03a (group D). Spec: agent-loop.md §3.4 "Wire formats": one pure adapter
`seon.ai/messages [wrapping prefix exchanges block] → messages`; format A `:transcript`: the prefix as the system
message, one user message holding the transcript with the block at its end. Lane: Sonnet.

## Files
`src/seon/ai.clj`, `resources/seon/schemas/seon.ai.edn`, `src/seon/turn.clj` (the call site, three lines),
`test/seon/ai_test.clj`.

## Functions
- ADD `seon.ai/messages`
  `{:malli/schema [:=> [:cat :seon.ai/wrapping :string [:vector :string] :string] :seon.ai/messages]}`
  `[wrapping prefix exchanges block]`; one `case` branch per format; CS-03a writes `:transcript` only:
  `[{"role" "system" "content" prefix} {"role" "user" "content" (str/join "\n\n" (conj exchanges block))}]`.
  An empty prefix omits the system message. Pure: no database, no printing (the strings arrive printed).
- CHANGE `seon.ai/request-body` (`src/seon/ai.clj:561-600`): `"messages"` becomes
  `(messages (:seon.ai/wrapping request :transcript) system (:seon.ai/exchanges request []) prompt)`; the request's
  `:seon.ai/prompt` is the block. Contract unchanged except `:seon.ai/request` gains two optional keys.
- CHANGE the model call site in `seon.turn` (`src/seon/turn.clj:4646`): pass `:seon.ai/exchanges` (S4-04's
  `(mapv seon.render.transcript/exchange-text exchanges)`) and the block as `:seon.ai/prompt` instead of one joined
  string.

## Schema (`seon.ai.edn`)
```edn
:seon.ai/wrapping [:enum :transcript]          ; CS-03b adds :chat :prefix and the dial
:seon.ai/exchanges [:vector :string]
:seon.ai/messages [:vector [:map ["role" [:enum "system" "user" "assistant"]] ["content" :string]]]
```
and `[:seon.ai/exchanges {:optional true} :seon.ai/exchanges]`, `[:seon.ai/wrapping {:optional true} :seon.ai/wrapping]`
in `:seon.ai/request`.

## Data
```clojure
(seon.ai/messages :transcript "HELP" ["(+ 1 2)\n3"] "{:proofs {:green 1}}")
;; => [{"role" "system" "content" "HELP"}
;;     {"role" "user" "content" "(+ 1 2)\n3\n\n{:proofs {:green 1}}"}]
```

## Proof
`seon.ai-test/transcript-ends-with-the-block-and-keeps-its-prefix` (new, pure table): empty history, one and three
exchanges, empty prefix. Assert the last message's content ends with the block, the system message `=` the prefix,
and adding an exchange leaves every earlier byte of the user content unchanged (prefix caching holds).

## Other tests reaching it
`seon.ai-test` (all `request-body` deftests), `seon.cluster.turn-test`.

## Cost
L: proportional to the transcript's characters (one `str/join`).

## Deleted
The inline two-message vector in `request-body`.

## Commit
`bin/lane commit <lane> -m "CS-03a: assemble the model's messages" --test seon.ai-test --test seon.cluster.turn-test src/seon/ai.clj resources/seon/schemas/seon.ai.edn src/seon/turn.clj test/seon/ai_test.clj`

## Dependencies
CS-02 (the block renders), S4-04 (exchange texts held).

## Reset
None.
