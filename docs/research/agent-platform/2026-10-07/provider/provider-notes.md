# Provider lane notes (2026-10-07)

## Changes
- `:seon.config.ai/stop` ships absent (`config/default.edn`). The dial stays: it is a per-agent request dial with its own
  tests (`stop-is-effective-per-agent-and-recorded-with-the-attempt`); only the shipped value cut replies.
- `:seon.config.ai/no-provider` is `:boolean` (was `[:= true]`). An agent's settings `{:seon.config.ai/no-provider false}`
  override the cluster's true through `seon.ai/settings` (agent over cluster); turn.clj/transcript readers use truthiness or
  `get` with the overlay, so false is honoured.
- Model rows from https://api-docs.deepseek.com/quick_start/pricing/ (fetched 2026-10-07): flash window 1048576, max output
  393216; v4-pro 0.66/0.022/1.98 off-peak, status :active; the three daily off-peak window entities deleted (cannot represent
  weekday-only peak). `deepseek-v4-flash` remains accepted by the provider as a deprecated name.
- top-p no longer `inert-when-thinking` (provider honours it only in thinking mode, so it must be sent when thinking).
- prompt-token-budget 1000000 -> 983040 (window 1048576 minus the 65536 completion budget).

## Real call (JVM mode, `seon.ai/complete`, deepseek-flash, prompt "Reply with the single word: ok")
734 ms, text "ok", finish "stop", prompt_tokens 11, completion_tokens 1, cache miss 11. The running default JVM predates the
schema change, so the lift itself is proven by `an-agent-lifts-no-provider-for-itself-only`.

## Design check
Held against real code. Awkward: the window schema/attributes (`deepseek-off-peak-windows`, window entity attrs in
`seon.ai.model.edn`, `ai.clj:118,130`) are now unused by shipped data; deletion is in files outside this slice.
Task files (`resources/seon/tasks/seon.ai.edn`) belong to persist-tasks; the three tasks should be closed there.

## Stop sequence (owner correction) and its visibility
- Shipped stop is `["user=>"]`, the marker loop-pass uses to introduce each REPL result.
- Probe (JVM, `seon.ai/complete`, stop ["user=>"], prompt "Write exactly: A user=> B", 3 completion tokens):
  text "A ", finish-reason "stop", usage only. DeepSeek reports finish_reason "stop" for a stop-sequence cut, the same as a
  natural stop, and names no matching sequence. The cut is not detectable from the response; the only sign is that the request
  carried a stop. UNFILED TASK (turn.clj is loop-pass's file; task EDN is persist-tasks'): the turn's evaluation reply should
  say "reply was requested with stop [\"user=>\"]; finish stop may mean cut at it" when a stop is in the attempt's settings.
- Pre-existing red: `seon.ai-test/captured-run4-replies-stop-before-fabricated-responses` (reader "Unmatched delimiter: )",
  task `seon.ai/...captured-run4`); the commit named only the green tests. It still replays with the literal old marker.
