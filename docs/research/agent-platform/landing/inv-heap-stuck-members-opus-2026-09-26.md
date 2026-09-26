---
type: landing
lane: inv-heap-stuck-members-opus
created: 2026-09-26
status: "cause named and reproduced; admission overlap fixed (unadopted); gate recursion routed"
---
# Landing: inv-heap-stuck-members-opus (2026-09-26)

RESET NEEDED: default (pid 13262) runs the 61d207c3e archive with hook
publication off. The fix is not loaded until root runs `bin/seon start --head`.

## Broken first

1. **Still broken, not in my files: the install gate recurses.** One
   `bin/test-check default --ns seon.cluster.host-test` still drives default to
   GC thrash (10.2–10.5 GB of 10.24 GB, 15:53–15:54Z). My reproduction did this;
   the JVM survived and fell back to 1.8 GB live by 15:58. Root should not run
   host-test on default until the owners fix it. Routing:
   - `src/seon/fn.clj:1786` `gate-set-in` follows `:seon.fn/references`. A quoted
     symbol in a test (`test/seon/cluster/host_test.clj:305`) selects that test
     into the gate for the function. Today's ruling: call reach, never symbol mentions.
   - `src/seon/turn.clj:3291` → `src/seon/sci/eval.clj:4623` `run-candidate-test!`
     runs a compiled host test inside an agent's install gate, under an SCI arm that
     cannot interrupt it.
   - Class issue: `an-agent-definition-gate-runs-the-host-test-that-quotes-its-symbol-nested-in-its-own-fixture.md`
     (third observation added).
2. My killing `pkill -f` matched my own background command and killed the
   `test-check` client at 15:52:59. The request kept running in the JVM. I have no
   client-side tally for this run.
3. Unverified: the regression and the changed `a-stopped-requests…` test have not
   run. Default cannot load the edit. The static contract check does not apply,
   because no schema changed; clj-kondo reports 0 errors.

## Defect 1: the OOM retainer (reproduced)

The retainer is a chain of nested fixture worlds. There is no leaked
cache. The live `datahike.datom.Datom` count went from 3.77 M to 9.40 M to 20.3 M
in 40 s. `[B` went from 215 MB to 1.19 GB, and `Object[]` from 192 to 694 MB.
Thread dumps at 15:52:23 showed 24 virtual threads at
`gate-function-install → run-candidate-test! → with-host-world → hosted-run! →
evaluate-source! await`. Each holds a `support/with-database` world. The full
table and the path are in
`docs/seon/issues/default-ran-out-of-heap-while-a-timed-out-test-member-stayed-live.md`.
Raw data: `tmp/inv-heap-stuck-members-opus/` (`histo-baseline.txt`,
`watch-host/`, `threads-{1,2,3,4}*.json`, `histo-after.txt`).
I did not run registry-test or mcp-test afterwards: the cause was already
named, and another request was running on default at 15:58
(thread 1068 in `bounded-result`).

## Defect 2: the member that never exits

The missing terminal event is the hosted run's `:seon.turn/closed-tx`. The
member's `hosted-run!` awaits it for up to 600 s
(`:seon.config.agent/turn-completion-backstop-ms`, `src/seon/cluster/agent.clj:793`).
That run's install gate is running the same test again, one level deeper.
The live thread retains its own fixture world and every nested world beneath it.
It was recorded unfinished at 15:53:31 and exited at 15:57:07.

Across requests (fixed, `src/seon/test.clj`): `admit-run` held only the live
body's own symbol, so every other request built a world beside it. Now:
- `admit-run` refuses with `:seon.test/member-unfinished` (naming the live test)
  while any body admitted on the cluster since JVM start has `completed-tx`
  without `terminated-tx`. The writer decides this.
- `run` builds its request world (`clean-request-handle`) only when the first
  admitted batch runs a member, so a refused request builds none.
- REPL proof on real data (JVM mode, read-only): the new query answered
  `[…/a-hosted-agent-proposes-a-merge-and-root-receives-it-on-the-cluster]` as-of
  tx 536871272 and `[]` at head, in 3.5 ms.

Regression:
`seon.test.one-request-test/no-body-of-a-cluster-starts-while-an-unexited-member-holds-its-world`
(renamed from `an-unexited-member-is-never-started-again-until-its-exit-is-recorded`).
A request for the live test and a request for another test are both refused,
with nothing written. Both pass after the exit.
`a-stopped-requests-unstarted-member-is-unknown-and-an-identical-request-reruns-it`
now awaits the recorded exit of B's old body before its second request.

Net lines: src +36/−29, test +31/−14.

## Root runs (after `bin/seon start --head`)

```
bin/test-check default --test seon.contracts-compile-test/every-declared-contract-compiles-against-the-packaged-projection
bin/test-check default --ns seon.test.one-request-test --include-long
bin/test-check default --ns seon.test-test
```
