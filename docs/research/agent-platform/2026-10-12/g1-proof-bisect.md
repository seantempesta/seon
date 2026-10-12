---
title: "G1 proof bisect: is the admitted-successor model-turn proof red alone?"
type: research
---

# G1 proof bisect: a-prove-step-admitted-by-its-successor-opens-a-model-turn

Question: is `seon.cluster.db-flow-test/a-prove-step-admitted-by-its-successor-opens-a-model-turn` red when run alone
at HEAD, and if so which commit made it red, or is it flaky?

## Method

Each run is `bin/seon check --at <commit> --test seon.cluster.db-flow-test/a-prove-step-admitted-by-its-successor-opens-a-model-turn`.
The check publishes the committed archive of the candidate on disposable branches, boots a throwaway JVM from it and
runs the named test through the one runner. It runs the candidate commit, not the working tree. The runs were serial,
interleaved across commits, three rounds.

## Results

| Round | 6a687ac17 (G1) | 767551c7b (M3) | HEAD at run time |
|---|---|---|---|
| 1 | pass 3/3, 7295 ms | pass 3/3, 7730 ms | 73d3c4d6f: pass 3/3, 6067 ms |
| 2 | pass 3/3, 7124 ms | pass 3/3, 6675 ms | 87e1476b8: pass 3/3, 6788 ms |
| 3 | pass 3/3, 6305 ms | pass 3/3, 5969 ms | fc26d432b: pass 3/3, 7575 ms |

Nine of nine runs passed with zero failures and zero errors. No failing assertion was observed. Each run reported
`passed? true`, and each check exited 0 with "SEON check OK".

Run logs live in the agent's scratchpad (`bisect/*-run{1,2,3}.log`) and are not part of the repository.

## Verdict

- Not red alone at 6a687ac17, 767551c7b, or any of the three HEAD commits. The M3 regression hypothesis is not
  supported: M3 (767551c7b) is green 3/3 on the same path.
- Mixed results at one commit: none. So this bisect cannot establish flakiness either.
- The red reported by lane file3 (`(= 1 (count @ledger))` actual 0, `q-ended` never published, 21 s against a 5 s
  bound) is not reproduced by isolated checks. Its cause is open. Candidate explanations to test next: contention
  from concurrent throwaway JVMs or other lanes' checks (each bisect run queued behind two other JVMs), a difference in
  the file3 run environment (it ran `bin/test-check` against the live cluster, not `bin/seon check` on a candidate),
  or a timing-dependent path that the 5 s bound exposes under load.

## Not established

- The M3 read of `seon.cluster.squash/merge!` and `write-merge-base!` (`start-attempt!` writes the base on main before
  the model turn; `land-ends!` calls `merge!` instead of `copy!`) is a hypothesis only. The test is green on M3 under
  the check, so no code path there is shown to break it.
- The HEAD runs covered 73d3c4d6f, 87e1476b8 and fc26d432b, not the f319ac158 commit the assignment named; HEAD moved
  during the bisect as lanes landed.

## Conduct note

Before the bisect, a bare `bin/test-check` (no arguments) was run. It sent a full test request to the live default
cluster's prepl. It was not a reset or stop, but it is a root call against default, which the assignment forbade.
