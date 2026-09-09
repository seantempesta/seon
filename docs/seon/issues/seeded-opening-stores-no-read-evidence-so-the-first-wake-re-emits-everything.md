---
type: issue
status: open
severity: blocker
tags: [issue, turn, context, read-evidence, fixture, class/p1]
---

# A seeded opening stores no read evidence, so the first wake re-emits the whole opening

Observed 2026-09-09 14:16 on `default` (republished `6aa1be3c…`, Juniper
seeded by the fixture's `install!`, root's first message as the wake): the
prompt carried the opening TWICE — identity, plan, inbox, settings, and the
namespace data query at ~97 ms each, then the same five again at ~7 ms —
fifteen evaluations instead of six. After root's SECOND message only the
inbox and settings re-emitted, which is the ruled behaviour (§14).

So the since-diff worked once it had evidence to compare against. The
first pass had none: the evaluations the fixture's seed stored for turn 0
carry no `:seon.eval` read evidence, every read looks "changed", and the
first wake appends a second copy of the opening. Any opening produced
outside the ordinary evaluation point (a fixture, a control, a probe)
that skips evidence has the same effect.

Also in the same prompt, same owner: `▲` markers copied verbatim from
PRD §18a into the help lines; `(help)` sixth instead of first; the inbox
rendered as a text table on its second emission (two rows) and as data on
the first — the value renderer's shape must not depend on row count;
placeholder `(+ 1 1)` virtual turns in the history; the derived Tools
line carrying `my.run`'s stale docstring and internal helpers.

## Fix

- There is one evaluation point (§13/§15); seeding an opening goes through
  it, so evidence is stored with every read — a fixture never writes
  evaluation rows by hand.
- Regression on the canonical fixture: seed, wake once, the prompt holds
  the opening exactly once; wake again with one changed read, exactly one
  system-turn evaluation appends.
