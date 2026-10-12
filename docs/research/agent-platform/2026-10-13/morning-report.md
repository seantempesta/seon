---
title: "Morning report, 2026-10-13: the first unattended two-step chain, and what it taught"
type: research
status: complete; root's summary of the night of 2026-10-12/13 for the owner
basis: HEAD 1fc9c3c13 and later; evidence in docs/research/agent-platform/2026-10-12/run-*.md
---

# Morning report, 2026-10-13

## Headline

**Run 15b ran the first fully unattended two-step chain** (Prove → Implement) from one `:seon.task/execution :running`
fact: zero hand acts, zero outside wakes, 66 s from launch to Seon's second git commit (`89be66ffc` test, `6488ef441`
code). **But it shipped a wrong function**: the Prove agent wrote one wrong table row, the Implement agent saw it was
wrong, did not send it back, and bent the code to it; the gate merged it. HEAD was repaired (`e47d07984`, with a property
law that would have caught it) before any restart, and `(help)` now tells agents to send back a contradicting proof and to
write laws (`1fc9c3c13`). The mechanism works; the proofs are now the weak point.

## The goals I steered by (from your messages)

1. Chains run unattended (assign → work → prove → merge → next step forks main), hand acts → 0.
2. The agent's context is useful by construction: one resolver over all text, a reference shows its own content.
3. Tasks get better as work proceeds: `my.task/refine!`, written back to task files, merged down a chain.
4. Then: agents mining and fixing Seon itself.

## Runs (all real DeepSeek runs; plan and evidence in hybrid-chains.md f2422dcd8)

| Run | What changed before it | Result |
|---|---|---|
| 9 | new context + help | 32 of 40 replies were tool-call markup |
| 10 | one native `eval` tool (b16b3daba), help rewrite | markup 0/40; first defn call 17; 2 of 3 proofs |
| 11 | nearest-local error hints, SCI self-require fix | typo loops fixed; still capped on a 200-line function |
| 12 | chain data, chain section, M7+M10 | first two-step chain: Prove merged + published; Implement green but merge refused by a red already on main |
| 13 | that red fixed | Prove found its product already on main and could not end → `:satisfied` rule added |
| 14 | `:satisfied` | **first two-step chain completed**; publication needed one outside wake (cache bug, fixed) |
| 15 | fresh subject | Prove's deftest refused at write-back (ns-level marker digest mismatch, fixed) |
| 15b | fixes above | **first fully unattended two-step chain** — wrong function shipped (see headline) |

## Decisions I made for you to review (simplest option, per your rules)

- **Q1 publication:** merged files go to the checkout, committed with `git commit --only` (you deferred).
- **Interface:** DeepSeek gets one native `eval` tool whose code enters the same REPL path (hybrid D1).
- **Chain data:** `:seon.task/instructions` back; a step's task is `merge` down `:children`; one launch fact.
- **Prove done:** a Prove step whose product is already on main is `:satisfied` and meets its successor's needs.
- **Relaunch:** a capped/stalled step with work relaunches from its ended commit, up to 2 times (dial).
- **New namespace files:** `test/` if deftest-only or `-test`, else `src/`.
- **F29 parked:** no catch added for a publication throw nobody has observed since its cause was fixed.
- **Chain subjects:** small pure functions with a gap observed at the REPL; the Prove step may create a new table test.
- **Run 16 held:** chain 3 (`seon.fs/intermediate-paths`, 4bafcecd3) touches tests that are red only when interpreted;
  an Opus lane is fixing that class first so the gate isn't stopped by reds the change didn't cause.

## What still needs you

1. **A review step before merge?** Run 15b shows the gate is only as good as the proofs. Options: (a) rely on the new help
   lines (send back contradicting proofs; write laws) and measure; (b) a cheap reviewer model judges each Prove product
   against the intent before it merges; (c) root reviews Prove products by hand for now. I'd start with (a) plus Haiku
   cold reads of every Prove product in runs, and add (b) if a second wrong proof ships.
2. **Interpreted-only reds:** many tests that pass compiled fail when the gate runs them interpreted on a branch. They
   block chains on any subject they reach. Being classified now (host-bound tests should run compiled).

## Other fixes tonight (all with red proofs)

Merge request machinery deleted (e8d0a41c9, −306); `:seon.task/kind` retired (4fded61c0); duplicate task fields deleted
(8d14faeee); task write-back and three-way adoption (7c87dfb8e, d50eb5cec); web view port race fixed (505fabdf8);
printer never cuts inside short forms (8fae17370); printed values shown once (ea4f02883); references cited once
(f155c1494); test-world refusal fence removed (2230f5e6f); stale merge base fixed (214c61330); health cost 389 → 161 ms
(cd673e383); branch file fixtures 48 → 30 s, 31 → 1.4 GB (af0a94471).
