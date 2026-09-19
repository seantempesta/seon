---
type: research
status: complete (diagnosis handed to the publication-repair owner; one surviving defect filed)
created: 2026-09-19
tags: [research, operator, publication, adoption, datahike, malli, instrumentation]
---

# Adoption silence on default, 2026-09-19: what the silent phase was

Handed to this session by the parallel session at ~16:43Z; the parallel
session then took the repair (`seon.fn/index!` derived its reference model
from zero persisted canonical schema declarations; note
`publication-projection-repair-2026-09-19.md`). This note records what was
measured here so the repair's verification covers it.

## The failure as observed

`bin/seon init --dev default` (three client runs: 42258, 45646, 56561 via the
edit hook, and 59084 by this session) exits 1 with
`:seon.fresh-operator/prepl-response-silent` 30,000 ms after the progress event
`program population compiled: 11191 entities, 23734 identities, 39441 keyword
facts`. Command totals 74.7 s, 59.2 s, 61.3 s, 54.4 s. Neither branch advanced:
`current-src` head `6aac85dd-…` with its latest transaction at
2026-09-18T00:29:17Z; default had no `:seon.source/commit-id` (queries in the
working edge's 2026-09-19 block). `data/operator/root-lifecycle.lock` was empty
between runs; no JVM thread carried a Datahike, Konserve or `seon.db` frame at
rest (`jcmd 41822 Thread.print`, 771 lines, 0 such frames).

## What the JVM was doing during the silence (stack samples every 8 s, run 59084)

| Sample | Wall clock | Thread | Where |
|---|---|---|---|
| 1–3 | 10:48:05–10:48:21 | `Clojure Connection … 97` | compiling the index transaction under armed wrappers: `seon.schema.datahike/resolve-datahike-form-in` reached through `seon.instrument/arm-var!` (`instrument.clj:834`) with `Var.pushThreadBindings` on every call; 27.5 s CPU by sample 4 |
| 4 | 10:48:29 | same | still compiling |
| 7 | 10:48:54 | same | parked in `seon.db/transact-call` (`db.clj:4116`) on Datahike's promise; the writer thread `async-mixed-23` running Seon's final validator `write-owned-values-error/owners-of` (`db.clj:3539`) over `datahike.db/-datoms` slices |
| after | 10:49:15 | — | no Datahike frames; the client had exited at 10:48:53 |

So the silent phase is the population commit: one `db/transact!` of 74,366
operations (`seon.fn/commit-index-phase!`, `fn.clj:2547`) whose only progress
event is emitted AFTER the transaction returns, while the transaction itself
spends more than 30 s applying under the whole-entity validator (the
2026-09-18 writer note measured 26.0 s applying, 11.4 s of it the validator,
7.3 s committing, on an idle machine; load average was 6.4 today).

## The same publication run to completion without a client

`(seon.cluster/refresh-source! "/Users/sean/src/seon/data/clusters" [] "default")`
in a JVM future with `*out*` captured (`tmp/publish-probe-2026-09-19.edn`):
**393,718 ms**, then refused
`Source changed during adoption through the one retry` (offense commit
`6aaebe65-…`, change phase `:adoption`). The population transaction DID land
on default's branch during this run (`:seon.fn/sym` rows 4,580 → 4,588;
latest transaction 16:52:47Z). The client-driven runs never got that far
because `report-source-progress!` (`cluster.clj:93`) aborts the publication
when the prepl `PrintWriter` has recorded an error, by design ("a departed
observer must not leave queued publications doing work").

Where the 393 s went: at 196 s elapsed the future's thread had 127.8 s CPU
inside `seon.cluster/acquire-development!` (`cluster.clj:2386`) →
`seon.config/defaults` → `compile-settings` → `admit-initialization-rows`
(`config.clj:277`) → `seon.schema/valid-candidate-value?` (`schema.clj:3474`)
→ `malli.core/validator` → nested `:or`/`:tuple`/`:ref` validators →
`-identify-ref-schema` (`reference-code/malli/src/malli/core.cljc:1949`)
→ `mr/-schemas` → `seon.schema/candidate-registry` `-schemas`
(`schema.clj:1147`), which `merge`s Malli's defaults with all 3,208 forms on
every call. At 298 s it had 193 s CPU and had moved on to
`development-source-refresh!` (`cluster.clj:2476`).

## Why that is a symptom, not a second cause

The same admission measured afterwards, once the JVM was idle:

| Measurement | ms |
|---|---|
| fresh `clojure -M` JVM, `seon.config/default-population` (17 rows, 96 attribute values) | 67.2 (second call 41.4) |
| live default JVM, `seon.config/default-population` | 282.4 |
| live default JVM, `admit-initialization` with the database projection's 3,210 forms | 30.3 |
| slowest single attribute in either | 1.35 (`:seon.call-preparation/schema`) |

Scripts: `tmp/probe/config_validate_timing.clj`, results
`tmp/probe/config-validate-timing-baseline.edn`, `tmp/probe/live-config-timing.edn`.
The minutes-long compile happened only while adoption held a reference model
with no persisted canonical schema declarations (the parallel session's
finding): every `:ref` then resolved through the fallback path and Malli's
cycle check re-merged the registry per ref. The repair owner's verification
must therefore include the adoption's own wall-clock, not only the
publication's.

## What survives the repair (filed)

1. **A publication aborted by a departed observer leaves no record.** The
   abort is by design (`cluster.clj:93`), but its outcome reaches no fault
   fact, no operator log line and no result file: the four failed runs' only
   evidence is the client's timeout. Issue:
   `docs/seon/issues/an-aborted-publication-leaves-no-record.md`.
2. **The silence bound spans a phase with no progress event.** The population
   commit and the development acquisition both run longer than
   `:seon.config.operator/event-silence-backstop-ms` (30,000) with no event
   inside them. A bound firing must name what never arrived; today it names
   the last event before the phase. The repair should emit an event when the
   population transaction is submitted and when acquisition starts, so the
   bound measures the writer, not the whole phase. Same issue.
3. **Latent, unmeasured as a cause:** `candidate-registry` `-schemas` merges
   on every call, and `valid-candidate-value?` builds a fresh registry reify
   per call, so Malli's validator cache never hits across calls
   (`schema.clj:1147`, `:3472-3474`). Cheap now (30–280 ms), pathological
   whenever a form graph is large or degenerate. Owner (2026-09-19): "we own
   malli, read the details in reference-code and reuse any internal states we
   need" — the fork's `-identify-ref-schema` carries a TODO saying `-schemas`
   is the wrong scope key (`core.cljc:1944-1948`). Not changed here; recorded
   for the schema lane.

## Boundary

No production file, schema or test was edited by this session for this
diagnosis. Publication runs by this session: one `bin/seon init --dev default`
(59084) and one captured JVM future; both overlapped the parallel session's
retries and the edit hook's queued publication of this session's own markdown
edits (`tmp/source-publications/85a63e1d-….edn`), which is why the captured
run ended in the source-change refusal.
