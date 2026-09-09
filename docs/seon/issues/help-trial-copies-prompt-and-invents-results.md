---
type: issue
status: open
severity: friction
tags: [issue, wave/agent-context, wave/unreadable-reply]
---

# The help trial copies the prompt and invents results

The one authorized 2026-09-09 trial on configured `deepseek-v4-flash`
passed 7/7 comprehension cues but only 3/5 structural checks. The reply
copied `my.agents.juniper=>` and supplied an invented `#:seon.repl` value,
including customers and amounts not present in the fixture. Its query
walked every datom instead of restricting to the requested order dataset.

[Exact request, reply, prices, usage and query scores](../../prds/context-generation/research/help_trial_2026_09_09.edn)
and [the executable harness](../../prds/context-generation/research/help_trial_2026_09_09.clj)
make the observation reproducible. The actual prompt contained two
turn-backstop notices; this run is not claimed as clean-fixture evidence.
The harness now refuses extra fixture messages before admitting another
call. It made no retry, and the fixed §18a help text remains unchanged.

Acceptance: a subsequent explicitly recorded tuning step produces only
thinking comments and executable forms, reads the real order data, and
waits for actual results. No fabricated result or prompt marker is accepted.
