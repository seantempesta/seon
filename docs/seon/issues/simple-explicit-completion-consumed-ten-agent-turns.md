---
type: issue
status: open
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

## Acceptance

- Compare the exact root and ordinary-agent database-derived context for this
  request; do not add an output rewrite or special-case the literal prompt.
- Identify why the ordinary agent inferred repository research while root
  executed the requested form directly.
- A fresh ordinary agent completes an explicit one-form request in one turn,
  while a genuinely open-ended task still plans and uses tools normally.
- Preserve raw provider replies, eval evidence, and the one agent loop.
