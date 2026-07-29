---
type: research
status: active
tags: [research, agent, runtime, performance]
---

# Ollama-parallel sustained load — 2026-07-29

## Deliverable

The one deliverable is a source-stable 10-agent, 10-minute sustained table for
the project Ollama provider. The table will report turns/minute, p50/p95 whole
turn latency, and p50/p95 inferred thinking tokens. Chunk 1 selected and
calibrated the harness; the sustained row remains the next bounded chunk.

## Dependency ledger

| dependency or mechanism | selected source | use in this drive |
|---|---|---|
| Ollama | server `0.32.1`; `OLLAMA_NUM_PARALLEL=8` observed in server startup | OpenAI-compatible streaming provider at `127.0.0.1:11434` |
| model | `qwen3.5:35b-a3b-coding-nvfp4` | project local coding model |
| real-agent drive idioms | `tmp/f4-drives/scripts/f4_common.clj`, SHA-256 `7cf1612aef1bb16d9336446f6b7dc1ff6bccf50dbb23b369894806dfd3a250a2`; [f4-drives-2026-07-29.md](f4-drives-2026-07-29.md) | agent creation, listener-first waits, run census, source digest, exact-root cleanup |
| selected driver | `tmp/load-testing/scripts/load_test.clj`, SHA-256 `193d4e456038c41ca0fc59ee7fa290613fe7a0e88e2f193a50e155822be63a8a` | one flow per real agent, isolated file database, ephemeral web server, phase timing |
| recording proxy | `tmp/load-testing/scripts/timing_proxy.py`, SHA-256 `7dac6f8817a3f477e06e1c276ac91ec986a4190e4218d86595367c614448241f` | concurrent streaming pass-through, provider latency/usage/timing rows, 8,192-token cap |
| provider summarizer | `tmp/load-testing/scripts/summarize_proxy.py`, SHA-256 `d97d48a5c50ffaee24763784ba32efb10aaffabd32a0814635564aafbbfd3ac4` | monotonic provider concurrency and thinking-token distributions |
| launcher | `tmp/load-testing/scripts/run.sh`, SHA-256 `72ebb570bbdc8af25d063bf02fdbd1d368902a21f715905d7e75a7c8a7ef3744` | owns proxy readiness/cleanup and calls the driver |

The selected harness uses only
`tmp/load-testing/runtime/<run-id>` for its file database and binds the web
server to port `0`. It does not open or reset the default cluster, and it never
uses port `7994`.

## Chunk 1 calibration

Command:

```bash
bash tmp/load-testing/scripts/run.sh \
  calibration-p8-2a-120s-r2-20260729 2:120
```

The two-minute offer window completed its in-flight turns in 140.629 seconds.
All 9 turns were correct, all 9 provider requests ended with `stop`, provider
errors were zero, and maximum observed provider concurrency was 2. The `src/`
digest was identical before and after:
`d58ae2c423f1d89d1d1001e5bdf6c79b5c11bc52ae9770e3c9cc077f1a87127b`.

| agents | offered time | completed turns | turns/min | turn latency p50 | turn latency p95 | thinking tokens p50 | thinking tokens p95 |
|---:|---:|---:|---:|---:|---:|---:|---:|
| 2 | 120 s | 9 | 3.84 | 23.771 s | 76.407 s | 581 | 3,918 |

Turn latency is trigger-to-terminal wall time from the Seon driver. Thinking
tokens are the proxy's conservative inference: Ollama's total completion tokens
minus visible streamed token chunks. Ollama separated reasoning from visible
content in every response, but its compatible usage row did not report a
separate reasoning-token count. The inference and its per-request
`token_chunk_delta` remain in the raw evidence rather than being presented as
an exact provider counter.

Raw evidence:

- `tmp/load-testing/evidence/calibration-p8-2a-120s-r2-20260729/run.edn`
- `tmp/load-testing/evidence/calibration-p8-2a-120s-r2-20260729/p00-a02.edn`
- `tmp/load-testing/evidence/calibration-p8-2a-120s-r2-20260729/provider-timing.jsonl`
- `tmp/load-testing/evidence/calibration-p8-2a-120s-r2-20260729/provider-summary.json`

An earlier calibration with the same shape completed 15/15 correct turns but
was rejected by the harness because commit `335764cd2` changed three `src/`
files during the run. The reported Clojure exception was therefore the
intentional source-stability fence, not a parser or load-driver failure.

## Chunk 2 detached launch

The 10-agent sustained drive launched at `2026-07-29T06:02:42-04:00` from Git
HEAD `4d53ff17bcb7f8ac4a060beef02ab2aa97b4bcac`. Per-user `launchd` owns the two
outer processes so they survive the bounded lane:

| process | launchd label | PID | durable output |
|---|---|---:|---|
| Ollama provider | `seon.loadtest.ollama-20260729` | 60634 | `tmp/load-testing/evidence/ollama-p8-10a-600s-20260729/ollama-server.log` |
| 10-agent drive | `seon.loadtest.ollama-p8-10a-600s-20260729` | 60657 | `tmp/load-testing/evidence/ollama-p8-10a-600s-20260729/drive.out` |

`run.sh` owns its recording proxy as PID 60676 and will stop it through the
existing exit trap. The drive's PID files are
`tmp/load-testing/evidence/ollama-p8-10a-600s-20260729/{drive,ollama-server}.pid`.
Its database is isolated under
`tmp/load-testing/runtime/ollama-p8-10a-600s-20260729`; the web server selected
ephemeral port 61565. The existing default listener on port 7994 was observed
as PID 66709 and was not contacted or changed.

At the approximately 30-second production check, both outer jobs were
`running`, the proxy was listening, the isolated runtime contained 518 regular
files, and the timing log held 14 `request-sent`, 5 `upstream-headers`, and 4
`done` events across all ten named agents. This is launch evidence only, not a
partial performance row.

An initial `nohup` attempt was reaped with the lane's tool-owned process group
before it created a database or provider timing log. The `launchd` jobs above
are the first actual sustained-drive launch.

## Next chunk

Resume after approximately `2026-07-29T06:13:42-04:00`. Verify that the drive
job has exited and inspect:

```bash
launchctl print \
  gui/$(id -u)/seon.loadtest.ollama-p8-10a-600s-20260729
tail -80 \
  tmp/load-testing/evidence/ollama-p8-10a-600s-20260729/drive.out
```

Accept the row only when `run.edn` says `:load-testing/source-stable? true`,
all provider finish reasons are `stop`, and provider errors are zero. Replace
the pending deliverable statement above with the 10-agent row, recording the
raw evidence root and any honest failures. Then add the descriptor row,
`OLLAMA_NUM_PARALLEL=8`, thinking verdict, and health-check curl to the top of
[local-provider-2026-07-28.md](local-provider-2026-07-28.md), preserving any
already-present uncommitted provider setup work.
