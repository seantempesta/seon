---
name: serialize-heavy-runs
description: Root admits heavy runs (bin/prove, full checks) a few at a time by load; never a fan-out to every session at once
metadata:
  node_type: memory
  type: feedback
  originSessionId: 21e07b71-4cb1-4e53-a41e-5be4db8f99e1
  modified: 2026-10-23T05:05:59.461Z
---

Root schedules heavy runs (prove, check, build of a changed tree). Up to three at once while `uptime` load stays under ~8 (the machine has 18 cores); never tell every session to "run bin/prove once" at the same moment.

**Why:** 2026-10-23 morning I told ~9 sessions to merge main and prove; load hit 31–62 and the owner said "tell them not to run bin/prove until it isn't a shitshow". That afternoon, with load ~2.5 and one run at a time, the owner said "The machine is basically idle so we really could be doing more". The cold prove is now ~60 s, so a few at once is cheap.

**How to apply:** before sending a batch of instructions, count the heavy runs they start, admit at most three, and name the order for the rest. Check `uptime` before admitting more. The app pauses cross-session messages after 10 per user message, so send the batch in one go, the most important first. Related: [[prefer-background-agents]], [[kill-only-verified-pids]].
