---
title: Checkpoint branches, who reads them, retirement
date: 2026-10-16
status: evidence
type: research
---

# Checkpoint branches: what they are, who reads them, retirement (read-only dig)

Date: 2026-10-16. Read-only. No edits except this file. Not committed.

## 1. What creates them, when, and what the suffix is

- Creator: `seon.test/clean-request-handle` (src/seon/test.clj, around lines 2212-2275). Only creation site in src/ or script/.
- Name: `(keyword (str "checkpoint-" cluster "-" (id/digest 24 [base cluster (sort effective) process])))`.
  The suffix is a 24-char digest of (source commit id `base`, cluster, sorted effective config, process id). It is NOT a git sha.
  `git log 9c08fba0b` fails (unknown revision), which confirms it is not a git commit.
- When: lazily, on the first test-world request (`my.test/check`, `my.test/run`, merge test runs) in a JVM whose inputs (base, config, process) have no complete checkpoint yet. Requests with equal inputs share it.
- Content: boot's facts for the loaded program (program rows, config, cluster record). Written on a private `checkpoint-preparation-<id>` branch, then named from that complete commit. The name `checkpoint-*` only ever holds a complete preparation.
- Git history: the design came in 2230f5e6f ("checkpoint is named only once complete") and 41f2f0b53 ("content-named checkpoint ... S2c"). Earlier variants are in the log.

## 2. What reads it afterwards, and retirement

- Read: only `checkpoint-head` (src/seon/test.clj, around 1973), which takes the branch head when its source record names `base`. Then `store/open-overlay!` per request; the request branch is created `:seon.cluster.registry/from head`. So it is a shared in-process test-world cache. Nothing restores from it.
- Not used by `start --head`, reset, nuke or publication. `grep -rn checkpoint script/ bin/` finds nothing. The rollback of `start --head` is not a checkpoint.
- Retirement rule exists: `seon.cluster.registry/retire-unclaimed!` (src/seon/cluster/registry.clj, ~line 621), docstring: "a checkpoint ... does not survive a restart." Boot calls it before acquiring any branch. A checkpoint is not in the claims (`:db`, cluster branches, open agent rows), so boot retires it. The test.clj docstring says the same: "Boot retires every checkpoint (`registry/retire-unclaimed!`), as the name holds the process."
- Count kept: one per (base, cluster, effective config, process) per live JVM. Multiple configs in one process can accumulate. Nothing else caps them. Old ones from earlier boots are retired at boot.
- Observed: the store task resources/seon/tasks/seon.cluster.store.edn (line 10) records 40 dead checkpoint heads from earlier boots (412-426 dead heads total, 2026-09-28 evidence), so retirement at boot did not always remove them then. Current live store has exactly one.
- Also: test.clj line ~2229 says it is retired at boot, but the current checkpoint was created by the running JVM, so it is correctly present now. The 108k index addresses it retains are the working set of the live test world (it is read by every request in this JVM), not stale history.

## 3. Every branch in the live store now (MCP eval_clj jvm, default cluster, read-only)

`(sort (map str (datahike.api/branches (seon.cluster.boot/connection "default"))))`: 24 branches.

| Kind | Count | Branches |
|---|---|---|
| agent (`:agent-*`) | 13 | 17a1c8e99f60, 3a066fcba799, 4aa84ceffa01, 5018cd0c90c4, 53961383f6c5, 862174c0ee20, aab26ec91f9c, b18b804683b9, b286e7d2ad0d, b8308e560809, be25833245ad, bf5a1346cbea, ce1225684ec2 |
| attempt (`:attempt-*`) | 2 | 8761215c4dab, ba03314dbf54 |
| chain (`:chain-*`) | 2 | chain-10d1fd5956a9, chain-e439df9798c6 |
| base of chain (`:base/chain-*`) | 1 | base/chain-e439df9798c6 |
| merge (`:merge-*`) | 2 | merge-66345c83c2b9, merge-cf5543b6f7a2 |
| checkpoint (`:checkpoint-*`) | 1 | checkpoint-default-9c08fba0b116f62c8ee59e32 |
| main/cluster/db | 3 | :db (main), :cluster-default, :current-src |

Totals: 13 + 2 + 2 + 1 + 2 + 1 + 3 = 24 (`:cluster-default`, `:current-src`, `:db`). Kind of `:chain-*` vs `:attempt-*` and `:merge-*` is by name prefix; not verified against their entity rows. Attempt and agent branches are "ended" candidates under keep-ended-branches (config.clj line ~972, default true per 31d2e4c95), so they stay as history.

## 4. Is the checkpoint obsolete?

- The code's intent: a checkpoint is a per-process cache, retired at boot. It is not history and no fact claims it. It is live, correct, and the working set of this JVM's test requests. So it is not obsolete for this JVM.
- What is obsolete: checkpoints from earlier processes or configs. Boot's `retire-unclaimed!` is the existing rule. The 108k addresses of the current one are the cost of storing its program rows under Datahike node sharing, not a sign of stale history.
- Cheap-storage implication: the checkpoint is rebuildable at any boot (it is a function of base + config + process). Its index addresses could be dropped at collection if no request holds it, or stored compactly. A design option, not decided here.
- Owner-relevant: `:checkpoint-default-*` retention is "one per process," so collection keeps it alive only while this JVM runs. If collection runs while the JVM is up, the checkpoint must stay (it backs the open overlays). Retirement by age would need a rule for live requests.
