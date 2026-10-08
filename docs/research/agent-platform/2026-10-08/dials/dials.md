---
type: research
created: 2026-10-08
tags: [memory, configuration, dials]
---

# Dials: hardcoded limits on a 16 GB machine

Lane `dials-16gb`, 2026-10-08. Owner ruling: every size, count, byte budget, heap cap, timeout and threshold is a
configuration dial whose shipped default fits a 16 GB machine running 10-20 agents beside the user's apps; nothing
derives from the host's RAM. Root's sizing logic (owner-approved): the OS, browser and editor keep 6-8 GB, so Seon
gets about 6 GB RSS: a 4 GiB heap plus metaspace, threads and native memory.

Existing dial seam: `resources/seon/schemas/seon.config*.edn` declares each dial (`:seon.config/dial true`, optional
`:seon.config/default`); `config/default.edn` is the shipped document; the operator reads its own dials from that
document or a `--config` manifest before any JVM exists (`seon.operator/operator-boot-bound-ms` and siblings).

## Broken first

- **A launch-option-only `deps.edn` change cannot pass `bin/lane commit`.** `seon.cluster.source/dependency-digests`
  hashes the whole file, so removing `-XX:MaxRAMPercentage` from `:dev`/`:test` is refused RESTART NEEDED, even right
  after a restart, because default only runs committed bytes. Task
  `seon.cluster.source/a-launch-option-change-in-deps-edn-refuses-every-check`. Root committed the `deps.edn` hunk in `9f43acf5e`
  with a restart. It is cosmetic for operator launches: an explicit `-Xmx` sets
  `MaxHeapSize`, so the percentage flags are ignored (proved below).
- **A `test/` deftest calling a new `seon.operator` var is refused as unresolved**: the rehearsal analyzes `script/`
  from the running JVM's source directory. Task
  `seon.cluster.source/a-candidate-script-namespace-is-analyzed-as-the-running-jvms-copy`. The operator test
  `seon.operator-test/a-launched-jvm-carries-the-configured-heap-cap` landed after default restarted onto `c62a1d6b1`.
- **1,489-1,891 platform threads** at the twenty-pass peak, every run below: the mixed-pool burst of MEM-02
  (`seon.flow/blocking-konserve-io-bursts-the-mixed-pool-to-five-hundred-threads`). It, not the heap, drives RSS above
  the heap.
- **RSS right after start is 4.3-5.1 GB with a 4 GiB heap** (2.4 GB with 2 GiB): boot's publication fills the heap
  before G1 shrinks it; 2.6-3.4 GB after the burst. The 6 GB envelope holds in these samples but with little margin at boot.
- **Default's boot ready went from 20.0 s to 41.9 s** on root's restart onto `9f43acf5e` (operator start logs `operator-start-20261008T153514.133.edn` and `operator-start-20261008T160224.221.edn`). It was not the class fill: 40.1 s and 40.7 s, both done before the old JVM stopped, with a class-cache hit at launch. Boot-to-ready grew 21.8 s and adoption grew from 3.5 s to 15.3 s; the log has no finer phases. Unmeasured hypothesis: the heap fell from 11 GB to 4 GiB, so G1 collects during publication; a GC log on the next restart would settle it.
- The committed memory probe read `seon.sci.eval/base-context-cache`, deleted by PB-02; the line is removed.

## Landed: `c62a1d6b1`

- `:seon.config.operator/heap-max-bytes` (declared in `seon.config.operator.edn`, shipped 4294967296 in
  `config/default.edn`), read by `seon.operator/operator-heap-max-bytes`; `seon.operator/launch-argv` puts
  `-J-Xmx<bytes>` on every start, nuke and check (all go through `launch-child!`).
- `:seon.config.db/store-cache-size` (declared default 8192): `seon.cluster.boot` passes the configured value through
  `seon.cluster/acquire-root-store!` to `seon.cluster.store/open-store!`; Datahike sizes the shared node LRU at the
  store's first connection (`reference-code/datahike/src/datahike/connector.cljc:304`).
- `:seon.config.cache/max-bytes` stays 512 MB, now justified as an eighth of the heap cap.
- Proof: check run `aeec6307a0f1`, 5 tests, 24 pass, 0 fail (store and config tests); clj-kondo 0 errors.

## Measurement (throwaway roots, never default)

Probe `docs/research/agent-platform/2026-10-08/memory/probe.clj`, phases `:snapshot`, `:add 0 10`, `:snapshot`,
`:inspect`, `:concurrent 100` (10 MCP SCI + 10 model passes released at one latch, loopback provider fixture),
`:snapshot`. Full-GC heap readings; RSS from `ps`.

| Launch | Heap max | Full-GC heap, 0 / 10 / 30 agents | 20-pass peak heap | Allocated | Wall | GC pauses (n, total, max) | RSS after start / after burst |
|---|---:|---|---:|---:|---:|---|---|
| HEAD `c62a1d6b1`, operator `-Xmx4294967296` | 4.29 GB | 377 / 394 / 359 MB | 1.371 GB | 10.5 GB | 3,597 ms | 15, 125 ms, 15.9 ms | 5.10 / 3.44 GB |
| `513cc7799`, `JAVA_TOOL_OPTIONS=-Xmx4g` | 4.29 GB | 380 / 393 / 369 MB | 1.401 GB | 10.0 GB | 3,138 ms | 14, 72 ms, 15.3 ms | 4.34 / 2.65 GB |
| `513cc7799`, `-Xmx2g` | 2.15 GB | 379 / 395 / 362 MB | 1.060 GB | 8.3 GB | 4,022 ms | 18, 104 ms, 18.4 ms | 2.44 / 2.20 GB |
| agent-memory lane, `MaxRAMPercentage=8` of 128 GB | 11.0 GB | 364 MB (0 agents) | 3.867 GB | 32.3 GB | 4,045 ms | 21, 91 ms, 13.3 ms | 7.09 GB after burst |

The earlier 3.87 GB peak was heap slack, not need: under a 4 GiB cap the same twenty passes peak at 1.37-1.40 GB, and
under 2 GiB at 1.06 GB with 25 % longer wall time. All 20 passes completed with no `isError` and 10 provider requests
in every run. Launched input arguments on HEAD end in `-Xmx4294967296` after the two percentage flags; the MXBean
maximum is 4294967296.

Sizing the three dials:

- **Heap 4 GiB.** Live heap 0.36-0.39 GB at 0-30 agents (idle agents ~1.2-1.5 MB each, agent-memory lane); 0.9-1.5 GB
  live on long-running default (storage audit; `bin/seon status` 1.52 GB today, mostly soft-referenced Datahike
  nodes); a twenty-pass burst adds ~1 GB. 4 GiB leaves 2.5x headroom over the measured peak and fits root's 6 GB RSS
  envelope (3.4 GB RSS after the burst). 2 GiB also passed this probe, so a smaller machine can set the dial lower.
- **`seon.cache` 512 MB.** Weighed 130-169 MB on a fresh store with 30 agents and 346-402 MB on default after hours,
  with no eviction; weights over-estimate retained bytes about 2x (65 MB retained for 120 MB weighed, agent-memory).
- **Datahike node cache 8,192 nodes.** Measured on the 4 GiB root: 8,192 nodes, 6,766 leaves, 513,788 datoms, 33,598
  distinct strings (14.1 MB): about 4.7 KB per node, ~39 MB in all (estimate: 40 B per datom plus strings and arrays).
  A byte-weighted dial would need the fork's LRU to weigh nodes (storage audit decision B option 3).
- **Proposed, not decided (owner decision B):** `-XX:SoftRefLRUPolicyMSPerMB` as a dial
  (`:seon.config.operator/soft-ref-ms-per-mb`) to cap soft-referenced node retention, which is the largest live-heap
  owner on default.

## Census

Method: one rewrite-clj walk over `src/` and `script/` listing every numeric literal of magnitude 3 or more outside
comments, strings and docstrings (593 rows), then dropping unit conversions (1000, 1e6, 60000, ...), digest and id
lengths, generator sizes, bit masks, HTTP status codes and test fixtures; `deps.edn` and `config/default.edn` read by
hand. The scratch script is outside the repository; the rows below are its classified result.

### Policy numbers to convert (ledger-ready rows)

Each row: `file:line` value → proposed dial and 16 GB default. Proof for every row: the dial is declared with its
default, the consumer reads it from configuration (boot's compiled config or the operator's bootstrap read), and one
test asserts a configured value reaches the consumer. Timeouts keep their current values (root).

| # | Where | Value | Dial (new unless noted) | Default | Held by |
|---|---|---:|---|---:|---|
| D1 | `deps.edn` `:dev`, `:test` `MaxRAMPercentage` 12.5 / 8 | % | delete; `:seon.config.operator/heap-max-bytes` (landed) | 4 GiB | this lane, blocked by the deps task |
| D2 | `deps.edn` `:measure` `-Xmx6g` | 6 GB | offline tool alias; keep, or read the heap dial | 6 GB | free |
| D3 | `src/seon/cluster/store.clj` `:branching-factor 128`, `:diff-buf-size 32` | 128 / 32 | creation-only index settings; `:seon.config.db/branching-factor`, `/diff-buffer-size` | 128 / 32 | free |
| D4 | `src/seon/db.clj:1443,1446` projection / value LRU `24` / `4` | count | fold into `seon.cache` kinds (owner: one central cache) | — | led-cacheb |
| D5 | `src/seon/call_preparation.clj:384` snapshot LRU `24` | count | fold into `seon.cache` | — | free |
| D6 | `src/seon/sci/eval.clj:3105` program-identity LRU `256` | count | fold into `seon.cache` | — | led-pb02b |
| D7 | `src/seon/test.clj:416` LRU threshold `4` | count | fold into `seon.cache` | — | free |
| D8 | `src/seon/test.clj:288` default test time limit `5000` | ms | `:seon.config.test/time-limit-ms` | 5000 | free |
| D9 | `src/seon/test.clj:2159` `batch-limit` 64 | members | `:seon.config.test/batch-limit` | 64 | free |
| D10 | `src/seon/turn/batch.clj:794,799,926`, `src/seon/turn/world.clj:60` fallback `30000` | ms | read existing `:seon.config.eval/time-limit-ms` instead of the literal | 30000 | free |
| D11 | `src/seon/cluster/process.clj:111,113` subprocess cleanup / grace | 10000 / 1000 ms | `:seon.config.shell/cleanup-ms`, existing `termination-grace-ms` | 10000 / 1000 | free |
| D12 | `src/seon/test/cache.clj:20` git inventory bound | 30000 ms | `:seon.config.test/inventory-bound-ms` | 30000 | free |
| D13 | `script/seon/operator.clj:469,474` runtime probe / dependency fill bounds | 10000 / 300000 ms | `:seon.config.operator/runtime-probe-ms`, `/dependency-fill-ms` | same | this lane (operator.clj) |
| D14 | `script/seon/operator.clj` `command!` bounds 30000, 120000, 300000 (lines ~818-1500) | ms | `:seon.config.operator/git-command-ms`, `/archive-ms`, `/deps-prep-ms` | same | this lane |
| D15 | `script/seon/operator.clj:382` `program-classes-kept` 3, `:622` 24 h staging age | count / ms | `:seon.config.operator/program-classes-kept`, `/staging-max-age-ms` | 3 / 86400000 | this lane |
| D16 | `src/seon/fn/analyzer.clj:399` 24 h analysis cache age | ms | `:seon.config.maintenance/analysis-cache-max-age-ms` | 86400000 | free |
| D17 | `src/seon/cluster/source.clj:836,838` `max-datoms 10000` | datoms | `:seon.config.db/merge-base-max-datoms` | 10000 | free |
| D18 | `src/seon/db.clj:4072` `stored-check-limit` 200 | entities | `:seon.config.db/stored-check-limit` | 200 | led-cacheb (db.clj) |
| D19 | `src/seon/profile.clj:52` `slow-ms` 1000 | ms | owner's 1 s bound; `:seon.config.profile/slow-ms` | 1000 | free |
| D20 | `src/seon/profile.clj:439,443` explained-calls 10000, hot-limit 20 | count | `:seon.config.profile/explained-calls`, `/hot-limit` | same | free |
| D21 | `src/seon/batch.clj:117` `repeated-calls` 100 | calls | `:seon.config.profile/repeated-calls` | 100 | free |
| D22 | `src/seon/profile/measurement.clj:37` `min-calls` 20 | calls | `:seon.config.profile/min-calls` | 20 | free |
| D23 | `src/seon/health.clj:58,59,590,595` half-life 24 h, severity 8, deadline 900 ms | — | `:seon.config.health/*` | same | free |
| D24 | `src/seon/render/web.clj:218-221,241,245,2031` page limits 40/40/4000/4000/200/200, deadline 50 ms | — | `:seon.config.web/*` (some already `seon.config.web`) | same | free |
| D25 | `src/seon/render/web.clj:3641,3647` port floor/ceiling 7700/8000 | port | `:seon.config.web/port-floor`, `/port-ceiling` | same | free |
| D26 | `src/seon/program.cljc:570,571` `:limit 200`, `:max-result-weight 10000000` | rows / weight | `:seon.config.render/*` | same | free |
| D27 | `src/my/program.clj:883,906` search limit 8, largest 20 | rows | display counts: value-printer budget, or `:seon.config.render/*` | same | free |
| D28 | `src/seon/eval/drive.clj:18,378` run cap 6, 240000 ms per run | — | `:seon.config.run/*` | same | free |
| D29 | `src/seon/shell/jvm.clj:243` descendant sample 50 ms | ms | `:seon.config.shell/descendant-sample-ms` | 50 | free |
| D30 | `src/seon/cluster/prompt.clj:104` calibration window 10 | samples | `:seon.config.ai/calibration-samples` | 10 | free |
| D31 | `config/default.edn` `:seon.config.maintenance/min-usable-bytes` 50 GiB | bytes | exists; 50 GiB free disk is a lot on a 256 GB laptop: owner to confirm | 50 GiB? | free |
| D32 | Datahike `:search-cache-size` (dead dial, storage audit row 16) | — | none; note for the fork | — | — |
| D33 | `-XX:SoftRefLRUPolicyMSPerMB` (JVM default 1000) | ms/MB | `:seon.config.operator/soft-ref-ms-per-mb`, owner decision B | proposed 100 | this lane |

Display-count literals (`src/seon/render/value.clj:86,195,196,541-547`, `src/seon/render/var.clj:53`,
`src/seon/render/schema.clj:20`, `src/seon/health.clj:642`, `src/seon/error.clj:1843`, `src/seon/print.cljc:424`,
`src/seon/schema.clj:3818,3822`, `src/seon/test/accretion.clj:111`, `src/seon/error/refusal.clj:489`,
`src/seon/fn.clj:47`) are presentation bounds: under the printer ruling they belong to the value printer's one budget
(`seon.print/*budget*`, the printer-rules lane), not to separate dials. They are listed here so that lane converts or
deletes them.

### Algorithmic constants (stay in code)

Unit conversions; digest and id lengths (`seon.id`, 8/12/64); bit masks and hash primes (`render/web.clj:3671,3673`);
HTTP status codes; JSON-RPC codes; histogram bucket count `profile.clj:57` (40) and its bound 39; `AtomicLongArray`
slot counts; arity and tuple-shape checks (`(= 3 (count form))`); I/O buffer sizes (`shell/jvm.clj:25`,
`web/jvm.clj:29`, `fs/jvm.clj:28`, 64 KiB); StringBuilder initial capacities; `seon.cache` weight estimators
(`* 64 (count ...)`, `schema.clj:1244-1247`, `program.cljc:342`, `schema/edn.clj:399`, `cache.clj:119`): calibrated
per-entry estimates, not policy; fingerprint and cache-format versions (`schema.clj:895`, `operator.clj:466`);
`ai/tokens.cljc:47,51` (shipped prior, data); `measurement.clj:52` (bucket floor, measured); `repl.clj:107` right
margin; `my/program.clj:866` snippet width (printer lane); `maintenance.clj:472` projection constant.

## Files the Aero lane will touch for these dials

`config/default.edn` (heap, cache and node-cache defaults move under `#profile`), `config/development.edn`,
`config/test.edn`, `script/seon/operator.clj` (`shipped-default-decisions` and the four `operator-*` readers become
one Aero read), `src/seon/config.clj` (the JVM-side document read), `src/seon/cache.clj` and
`src/seon/cluster/store.clj` (`declared-max-bytes`, `declared-store-cache-size`: the two schema-default reads made
before configuration exists), and `deps.edn` (the `aero` coordinate; D1).

## Design check

The design held: the operator already had a bootstrap read for its own bounds, so the heap became one more dial on
that path and one extracted pure function (`launch-argv`). Awkward: the store's node-cache size must exist before any
configuration is compiled for stores opened outside boot, so it reads the declared default from the schema resource,
the same pattern `seon.cache/declared-max-bytes` uses; two copies of that read now exist and the Aero lane should make
it one. The two check gaps above block the cleanest landing; neither is patched around.
