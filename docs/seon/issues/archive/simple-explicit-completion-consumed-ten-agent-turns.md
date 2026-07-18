---
type: issue
status: resolved
severity: high
tags: [issue, agent, pod]
---

# Simple explicit completion consumed ten turns

## Evidence

On 2026-07-18, the real browser created ordinary agent
`big-camels-change` and sent:

```text
Execute (complete "ordinary browser agent works").
```

The Bun execution child and Datastar feed remained responsive, but the agent
treated the exact one-form request as a repository research task. It inspected
grants, namespaces, repository files, skills, and database plans across roughly
ten turns before finally returning `ordinary browser agent works`. The root
agent had completed equally explicit requests in one turn during the same
browser journey. The ordinary agent was marked terminated through the existing
root lifecycle function after the excessive work was observed; the terminal
reply and termination were both visible through the live feed.

This is not a database, browser, feed, process, or eval failure. It is a costly
agent-context or model-control failure that makes simple work unpredictable and
can multiply provider cost and wall time.

## Resolution

The exact database evidence isolated two related first-turn failures. Root's
prompt already contained successful executable transcript examples. Fresh
ordinary-agent prompts did not. One fresh agent inferred repository research;
a second understood the requested work but emitted all three correct forms as
semicolon-prefixed comments, then closed `:no-forms` after three turns. Raw
provider reply capture proved the parser and eval path behaved correctly: the
model had produced comments, not executable forms.

The existing system text now includes one two-line syntax example: an ordinary
comment followed by an executable form. No parser rewrite, output rewrite,
special-case request, second loop, or provider-specific path was added. After
live config reconciliation, two new ordinary agents independently completed in
one turn. `cuddly-webs-work` ran three forms and closed `:completed` in 10.72
seconds; `social-jars-double` ran the exact requested `(complete ...)` form and
closed `:completed` in 9.24 seconds. Raw prompt, reply, eval, and provider
evidence remained intact. Focused config proof passes 22 tests/94 assertions.
