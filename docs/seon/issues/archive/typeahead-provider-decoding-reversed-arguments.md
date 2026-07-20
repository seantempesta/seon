---
type: issue
status: resolved
tags: [issue, agent, cljs, database]
severity: friction
---

# Typeahead provider decoding reversed arguments

## Problem

The typeahead context renderer passed the stored provider value as the
attribute argument to `seon.db/decode-edn-value` and passed
`:seon.ai/agent-provider` as the value. A stored `:typeahead` override therefore
resolved as `:seon.ai/agent-provider`, and the provider-specific prompt block
vanished.

## Evidence

The focused context test supplied an ordinary `:typeahead` value through the
same acquired pull-member response used by the child and received an empty
prompt. Reading `prompt-provider` showed that `some->` had reversed the
two-argument decoder call.

## Owner

`seon.agent.ctx.typeahead-steps/prompt-provider` owns provider selection from
already-acquired ordinary database data.

## Resolution

Commit `0bfe95449e184305cc36e56760aaf86e4aa9ef85` names the stored value and calls
`decode-edn-value` with the attribute first and value second. The fixture now
uses the ordinary keyword value returned by the current protocol.

## Verification

The focused subagents, typeahead-steps, web capability, and context gate passes
25 tests and 82 assertions with no failures, errors, or compile warnings.
