---
type: research
status: probes NOT run on default (blocked by the lane rule, see Probes); boot, heap, cache and health measured from the operator log and bin/seon status
basis: default restarted onto ac55beffa (from a92ba5b94), pid 65678, boot log operator-start-20261020T035604.657.edn; previous boot operator-start-20261019T183159.425.edn (a92ba5b94); last measured probes 1cb582f0d (2026-10-17, by hand); baseline sentinel-baseline.md (e9bcf36d2, 2026-10-12)
---

# Sentinel at ac55beffa, 2026-10-20

## For root

- The sentinel probes were NOT run, so no row can be named over its baseline. `seon.sentinel/run` evaluates every probe as an SCI-mode MCP request as agent root on default (`eval-probe` hardcodes `"sci"`, script/seon/sentinel.clj). AGENTS.md "How to work" 4 forbids a Haiku lane from making SCI requests as root on default, and JVM mode is root's (the health pattern "JVM mode is root's" is recorded 12 times on `runtime-review`), and a JVM-mode figure does not compare with an SCI baseline. Root runs it once: `(seon.sentinel/run {:running-commit "ac55beffa"})` in default's JVM, then compares `:summary` with `sentinel-baseline.md` row by row.
- Boot ready-ms is 43,467 against 19,109 for the previous boot, a 2.27x increase. The cause is most likely the class-cache removal in ef8161ff8, which is an ancestor of ac55beffa and not of a92ba5b94. Its size is not explained by ef8161ff8's own estimate (see Boot). Verify on an idle machine before attributing it.
- Health is `:faulted` with no regressions and no replaced roots. The faulted state's top clue is an open fault on `seon.schema.datahike/malli->datahike-attr-in` (task `4cae87e6cadf`). The other top problems are agent errors from closed-p4a, closed-p7, runtime-review and cold-verdict (listed under Health).

## Probes

Not run. Last measured probe figures, for context only and not this commit: `1cb582f0d` (2026-10-17, hand-measured through two requests because the script's `eval-probe` read a stale reply shape). Warm `(+ 1 2)` median 55.5 ms against the baseline's 38.6 ms (1.44x), warm settle 37.4 ms against 27.4 ms (1.36x). Those are not this commit's figures and are not flagged here.

Rows that the next run must compare: warm median ms, warm settle ms, warm calls, warm reads, fresh median ms, fresh custody ms, fresh settle ms, fresh calls, fresh alloc MB, defn ms, defn calls, defn settle ms. Flag any row over its baseline by more than 10 percent with its ratio.

## Boot

From `data/clusters/default/logs/operator-start-*.edn`. Layer times are milliseconds since the boot's process start.

| Layer | Previous a92ba5b94 (10-19 18:31) | Now ac55beffa (10-20 03:56) | Now minus previous |
|---|---|---|---|
| prepl-server | 123 | 85 | -38 |
| store/store | 172 | 123 | -49 |
| source/current | 7,949 | 27,557 | +19,608 |
| store/branch, cluster-connection, source/commit-id | 8,226 | 27,787 | +19,561 |
| boot/config-result | 10,390 | 36,138 | +25,748 |
| sci.eval/ctx, flow/work-launcher | 11,332 | 37,039 | +25,707 |
| flow/graph | 19,099 | 43,462 | +24,363 |
| render.web/served (ready) | 19,108 | 43,467 | +24,359 |

Operator phases, ms: source 3,032 then 2,594; archive 2,370 then 1,992; share 33 then 27; classpath 629 then 575; down 855 then 1,161; launch 26,026 then 50,681; ready 19,109 then 43,467 (2.27x); total 32,087 then 67,500.

Layer increments (time between consecutive layers): source/current 7,777 then 27,434 (the bulk of the rise); config-result 2,164 then 8,351; flow/graph 7,767 then 6,423.

Why it more than doubled, best evidence:

1. The previous boot ran on a92ba5b94, which predates ef8161ff8 (`git merge-base --is-ancestor ef8161ff8 a92ba5b94` is false; the reverse holds for ac55beffa). That boot loaded the program from its compiled class cache. This boot has no cache: ef8161ff8 deleted `launch-form`'s compile staging and the 2100 stamp, so `seon.source/current` compiles all 125 program namespaces from source. The `source/current` layer is where that happens, and it is the layer that grew most.
2. The ef8161ff8 message estimated the cost at about 1-2 s per launch: 4,735-6,238 ms from classes against 6,755-7,122 ms from source for 124 namespaces, measured in a throwaway JVM. That estimate covers only the namespace load. It does not explain a 19.6 s rise in the layer. The same commit's boot timings (cached 33,179 to 46,572 ready-ms; from source 36,060 to 37,531) were taken under concurrent lane load, so they cannot separate the cache cost from load either.
3. Program size is not the cause. src grew from 121 files (a92ba5b94) to 125 (ac55beffa), with 53 files changed (+2,081 / -1,786 lines).
4. Load during the boot window is not recorded. At 03:57 local, after the boot, `uptime` showed load averages 2.36, 3.04 and 3.98, and `fseventsd` ran at 103 percent CPU. That sample is after the boot, not during it, so it is not evidence of the cause.

The config-result layer (+25,748 cumulative, 8,351 incremental against 2,164) is not explained by the cache change. It runs after source publication, so it may be a consequence of the same slow source load, or the 61 commits between a92ba5b94 and ac55beffa may have added config work. Not verified.

Untested: one boot on an idle machine from the same source root, and a timed load of the 125 namespaces from source and from classes in one throwaway JVM. Either would confirm or refute the cache as the main cost.

## Heap and cache

From `bin/seon status` (pid 65678).

- Heap: live 1,152,385,024 bytes (1.07 GiB), used 1,989,148,672 (1.85 GiB), max 4,294,967,296 (4 GiB). Live heap is 27 percent of the cap; used is 46 percent.
- Platform threads 39; open turns 0.
- `seon.cache`: weighed 414,846,363 bytes of a 512,000,000 byte budget (81.0 percent).

## Health

`bin/seon status`: default cluster state `:faulted` at basis t 536871630, commit id `6ad683d7`. Counts: agent-error 7 signatures, errored-receipt 63, malformed-contract 4, open-fault 7, unowned-namespace 2. Regressions 0, replaced roots 0, profile observed 7.

Top problems (score, subject, what it says):

1. 218, agent-error, closed-p4a: "Unable to resolve symbol" (19 occurrences), values include `convert!`, `runtime-rows!`, `runtime/bind`, `seon.db/transcat!`.
2. 172, agent-error, closed-p7: "Unable to resolve var" (5), values include `seon.cluster/declaration-adoption-tx`, `seon.sci.eval/reach-index`.
3. 163, agent-error, runtime-review: "JVM mode is root's" (12), an agent ran read-only host queries as root.
4. 155, open-fault, `seon.schema.datahike/malli->datahike-attr-in`: "The attribute has no registered schema." (3 occurrences), task `4cae87e6cadf`.
5. 149, agent-error, cold-verdict: "Not admitted to the program" (19), refusals whose messages name bad contracts, a malformed schema and disagreeing source arities.

These are the state's clues; they are not measured regressions. No producer is named as a root cause here.

## Findings for root

- ef8161ff8's boot estimate of 1-2 s is off by roughly tenfold for the source/current layer on this machine. The cost belongs to the class-cache deletion and needs a timed boot, not an estimate. No issue file was created, because the brief allowed one docs file. Root should file it under `docs/seon/issues/` if it stays open.
- The open fault on `seon.schema.datahike/malli->datahike-attr-in` is task `4cae87e6cadf`; it was already recorded and is not new.

## Verification limits

- No probe was run, so the performance sentinel has no verdict for this commit.
- Boot attribution is by the layer timings and commit ancestry only; no timed rerun was made.
- Heap and cache figures are single samples from the status call, not a series.
