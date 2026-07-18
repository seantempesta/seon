---
type: issue
status: resolved
severity: blocker
tags: [issue, agent, web]
---

# Agents run returned before final turn settled

## Problem

`POST /agents/run` returned as soon as the agent became idle. A lifecycle
function can close the run before the enclosing eval and turn finish recording,
so the response could observe a final `:running` turn, omit its terminal eval
facts, and classify otherwise valid model-attempt evidence as malformed.

## Resolution

The existing polling owner now requires both the derived idle agent state and
terminal `:done`, `:error`, or `:interrupted` status for every turn opened by
the request's runs. The final response is still derived once from the following
immutable database value.

## Evidence

The focused web regression proves a running turn delays settlement and native
terminal turn statuses admit it. The repeated public agent drive and response
evidence are recorded in the database-authority roadmap.
