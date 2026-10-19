---
type: issue
status: fixed (lane runtime-fix3, item 4)
severity: defect
tags: [issue, sci, runtime, tests]
---

# The runtime's macro-key and defonce proofs survive mutations of their mechanism

Found by lane review3-opus, 2026-10-20, HEAD `06614f6a8`; evidence in
[runtime-review3-opus](../../research/agent-platform/2026-10-20/runtime-review3-opus.md) (step 3).

Each mutation is one edit of `src/seon/sci/eval.clj` in a dangling commit, run with the proofs that claim it:

| Mutation | Proofs run | Result |
|---|---|---|
| `bind-analysed!`'s analysis key drops the macro's reached changes (`[macro digest]` instead of `[macro digest (reaching macro)]`) | `seon.sci.eval-test/a-branch-changed-value-a-macro-reads-is-expanded-by-its-callers`, `an-agent-changed-value-a-macro-reads-is-expanded-by-its-callers` | **green** |
| `runtime-rows!` gives a `defonce` a value row (`defonce-source?` bypassed) | `seon.sci.eval-test/a-changed-runtime-keeps-the-process-launch-observations` | **green** |

The first mutation is a real defect the proofs miss: two branches changing `seon.profile/buckets` to 3 and then to 5
(one connection, two commits, `seon.sci.eval/base-ctx` of each) answer `(seon.profile/bucket 5)` = 2 and 3 on HEAD,
and 2 and **2** under the mutation (the second branch is served the first's analysis of `seon.profile/bucket`). The
row's assertion "main's analysis is not the branch's" cannot see it: main runs `bucket` compiled and never reads the
analysed cache. Proof to add: the two-branch row above in `a-branch-changed-value-a-macro-reads-is-expanded-by-its-callers`.

The second: the row's change (`seon.print/literal`) reaches neither launch observation, so no rule of the binder is
exercised; and an SCI `defonce` of a Var the context already binds is a no-op. Either the `defonce` exclusion is
redundant (delete it) or a row must change a definition the `defonce`'s initializer reaches; decide by that row.

## Fix (lane runtime-fix3, 2026-10-20)

Both rows now hold their mechanism, each mutation re-run on HEAD `00430af64`:

- `seon.sci.eval-test/a-branch-changed-value-a-macro-reads-is-expanded-by-its-callers` gains the second branch
  (`buckets` 3 then 5, `(seon.profile/bucket 5)` = 3 on it): green unmutated, red under mg (answers 2, the first
  branch's analysis).
- `seon.sci.eval-test/a-changed-runtime-keeps-the-process-launch-observations` gains a branch changing
  `seon.cluster.source/path-digests`, which `seon.cluster/launched-source-digests`' initializer calls: the value stays
  identical under the mutation too (SCI's `defonce` of a bound Var is a no-op), so the row also asserts on the data
  the rule decides: no runtime row names a launch observation. Green unmutated, red under mc.
