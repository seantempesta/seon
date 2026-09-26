---
type: issue
status: open
severity: friction
created: 2026-09-26
tags: [issue, test, selection, merge]
---

# An empty `:named` test selection answers `passed? false` without a reason or a record

## Evidence (default at a1b01c92a, a disposable isolated branch)

- `seon.test/run` with `{:seon.test/policy :named :seon.test/changed [wbprobe.core/callee]}` on a branch where
  `seon.fn/gate-sets` of `callee` answers `[wbprobe.core/caller-check]`. The test is a `:core` row analyzed from a
  `src` root.
- The answer: `run f1d4… / executed 0 / reused 0 / pass 0 / fail 0 / error 0 / passed? false`, with
  `:seon.test/results []`, no refusal member, and no `:seon.test.run/id` entity on the recording connection.
- The cause is the `:named` eligibility filter (`src/seon/test.clj` ~916-930). A test is kept only when it is
  agent-authored, lives under a `test` root, or is named as an identity. The reaching test is dropped, and nothing
  names the drop.
- Downstream, `seon.cluster.source/accept-merge!` refused with "The named run was not recorded on the tested
  branch", which reads as a recording defect (write-back regression, red since S5). 5151db0cc makes
  `prepare-merge!` refuse by name first; the runner still answers silently.

## Why it is a defect

A request whose changed identities reach tests that the filter then removes is missing coverage. The law is that
missing coverage refuses by name; `passed? false` with nothing else is silence dressed as a verdict.

## Owner and acceptance

The owner is `seon.test` selection. Acceptance: such a request answers a declared selection error. It names each
reached test it dropped and the rule that dropped it, or records the run with those members as excluded
dispositions. One regression: a changed function reached only by an ineligible test answers that named error.

A simplification to weigh at the same time: the eligibility cases (agent-authored, a test root, named) might
reduce to one rule. A test is a member when its program row reaches the change and its body is executable in this
world (SCI-interpretable, or a loaded or loadable Var). Location and authorship would then stop being proxies for
executability.
