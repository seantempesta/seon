---
type: issue
status: open
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
