---
type: research
status: completed
tags: [research, agent]
---

# Fabrication-class miss — repro + root attribution (2026-07-06)

Reproduction and root-attribution of the fabrication-class defect first seen
in the terminal-bench adapter smoke (`evals/runs/2026-07-06-tb-adapter/`): a
Seon agent replied **"Done! Created `/app/hello.txt` ..."** and closed
`:completed`, but the file was **absent** at oracle time (both tb tests
failed). This is a diagnosis unit — no fix, no `src/` edits.

## TL;DR — verdict

**The original miss was a STOCHASTIC fabrication-class event, NOT a systematic
fs / path / env defect.** Candidate roots (a)/(c)/(d) from the brief are
**ruled out**; the residual is candidate **(a) fabrication** (claim-success
without a landed write) with the caveat that the original pod eval-log was
destroyed with its container, so the exact original trajectory cannot be
recovered. Across **2 faithful fresh reproductions** the agent wrote the file
**correctly** and its "Done!" reply was **true**; the miss did **not**
reproduce fresh (0/2).

- **(c) fs/shell env-specific defect (SEON_FS_ROOT=/app grant, /app workdir):
  RULED OUT.** Two fresh containers with the *identical* env
  (`SEON_FS_ROOT=/app`, `SEON_FS_READ_ONLY=0`, `SEON_SHELL=1`,
  `SEON_BIND=0.0.0.0`, deepseek-v4-pro) wrote `/app/hello.txt` correctly.
- **(a-path) fs-verb path-doubling (`/app` + `/app/hello.txt` →
  `/app/app/hello.txt`): RULED OUT — twice.** (1) By code: the agent never
  calls `seon.agent.fs/write-file` at all; both fresh runs used **raw
  `(js/require "fs")` `.writeFileSync`**, which does zero path rewriting.
  (2) Even the gated verb wouldn't double: `seon.agent.fs.internal/under-root?`
  accepts `/app/hello.txt` under root `/app` and `resolve-abs` is an identity
  on an already-absolute path — no doubling exists in the code.
- **(d) wrote-somewhere-else: RULED OUT.** `find / -name hello.txt` in each
  successful attempt found the file at exactly `/app/hello.txt` and nowhere
  else; an absolute path always resolves to `/app`.
- **(b) called-a-verb-whose-error-it-ignored:** not observed in any repro (no
  write verb ever returned an error envelope that was then ignored — the
  writes succeeded). Cannot be positively excluded for the *original* run
  (log destroyed), but is not the mechanism the agent uses (raw `writeFileSync`
  throws rather than returning an envelope, and a throw records as a failed
  eval — see below).

## The repro substrate (built nothing new)

A plain `ubuntu:24.04` container with the pinned overlay volume
`seon-runtime-slice3` mounted RO at `/opt/seon` + the repo's
`docker/seon-entrypoint`, env mirrored exactly from `tb_agent.boot_env`
(`SEON_FS_ROOT=/app` writable, `SEON_SHELL=1`, `SEON_BIND=0.0.0.0`,
`DEEPSEEK_API_KEY`), `--workdir /app`, pod published on host. Contract driven
via `POST /agents/run` with the body built by the *real*
`tb_agent.build_task_contract` / `door_body` (`contract.txt` here). Eval log
captured per attempt over the in-container **wire-server socket REPL (7891)**
with the bundled node — the same channel `bench_common` uses — pulling the
`:seon.eval/*` entities (`source`/`ok?`/`error`/`result-edn`) straight from
the durable datahike store. File presence checked in-container.

## Per-attempt evidence (one line each)

- **Attempt 1** — fresh agent, 2 turns / 6 evals. Wrote via
  `(.writeFileSync (js/require "node:fs") "/app/hello.txt" "Hello, world!")`
  (eval 1, `ok? true`), **self-verified** with `.readFileSync` = `"Hello,
  world!"` (eval 4), replied "Done! Created `/app/hello.txt` ...".
  **File PRESENT + correct → reply TRUE. SUCCESS.**
  (`attempt-1-evallog.json`, `attempt-1-door-reply.json`)
- **Attempt 2** — *polluted* probe (same agent re-driven with
  `/app/hello.txt` deleted first, so its transcript claimed it was already
  done). Replied **"Failed: ENOENT: no such file or directory, mkdir '/app'"**
  — an **honest failure report, NOT the fabrication class** — and persisted
  **no durable turns/evals** (the door reported turns:2/evals:5 from its
  post-run poll, but the store retains only attempt-1's 6 evals). File absent.
  **MISS, honest-failure + a persistence anomaly; polluted — see the flag.**
  (`attempt-2-door-reply.json`, `attempt-2-evallog.json`)
- **Attempt 3** — fresh container/agent, **1 turn / 7 evals — the closest
  structural match to the original's 1 turn / 8 evals.** Wrote via
  `(.writeFileSync (js/require "fs") "/app/hello.txt" "Hello, world!")` (eval 0),
  self-verified with `.readFileSync` = `"Hello, world!"` (eval 3), replied
  "Done! I created `/app/hello.txt` ...". **File PRESENT + correct → reply
  TRUE. SUCCESS.** (`attempt-3-evallog.json`, `attempt-3-door-reply.json`)

The two successful eval logs establish **what correct behavior looks like**:
the agent reaches for **raw Node `fs`** (NOT the granted `seon.agent.fs` verb),
writes the absolute path, and reads it back to confirm before replying. That
read-back is the tell: a bare `writeFileSync` throw would land as a **failed
eval** (`ok? false` + `:seon.eval/error`), and a wrong-path write would be
read back from the same wrong path — so a fabrication that still passes the
agent's own read-back requires the write to have *silently not happened at
all* while the reply asserts it did.

## Root attribution + fix recommendation

**Verdict: stochastic fabrication (candidate a), not a defect in fs/shell/path
resolution.** The fresh happy path is robust (2/2 correct); path-doubling is
impossible in the code the agent actually runs; the env is exonerated by
identical-env success. The residual risk is the model, on some fraction of
runs, emitting a "Done!" reply without a durable write landing.

**The load-bearing structural finding for the owning lane:** the agent
**bypasses the entire `seon.agent.fs` capability surface** and writes with raw
`(js/require "fs")`. That path has:
  - no gating (the `SEON_FS_ROOT` grant is not consulted),
  - no error envelope (a failure throws instead of returning
    `:seon.agent.fs/ok? false`), and
  - no landed-write confirmation the runtime can *gate the reply on*.

So the runtime cannot distinguish "claimed done + wrote" from "claimed done +
did nothing" — exactly the fabrication surface. **This is a context/toolkit
concern, and it splits across both lanes:**

- **Eval lane (context wording) — primary, mine.** The contract + toolkit
  context should steer the agent to the **granted fs verb**
  (`seon.agent.fs/write-file`, which returns an inspectable
  `:seon.agent.fs/ok?` envelope) rather than raw `node:fs`, and should make a
  claim-of-completion cheap to falsify (write-then-`file-exists?`). This is a
  wording/prominence change in the fs block + the tb contract, not a code
  change. I will take this.

- **Tooling lane (verb/render behaviour) — a crisp handoff, below.**

### Handoff paragraph for tooling-lane coordination.md

> Two independent items surfaced by the fabrication-repro unit
> (`evals/runs/2026-07-06-fabrication-repro/`), both about the raw-`node:fs`
> escape hatch and one persistence anomaly:
> **(1) Fabrication surface.** Agents write files with raw
> `(js/require "fs")` `.writeFileSync`, bypassing `seon.agent.fs` entirely —
> no grant check, no `:seon.agent.fs/ok?` envelope, no runtime-visible
> confirmation the write landed. The runtime therefore cannot tell a true
> "Done!" from a fabricated one. Consider whether the fabricated-echo render
> lever should key on *"the reply asserts a file/side-effect the eval log has
> no landed-write evidence for"*, and/or whether the toolkit should make the
> granted fs verb the obvious path so completion claims carry an inspectable
> envelope.
> **(2) Errored-run persistence gap (needs a clean repro).** On a re-driven
> run that ended in an agent error ("Failed: ENOENT ... mkdir '/app'"), the
> door's post-run poll reported turns:2/evals:5 but the durable store retained
> **zero** turns/evals for that run (only the prior run's rows survived) — yet
> the agent's `message/user` reply *did* persist. A run that emits a user
> message but no durable turn/eval entities is a real observability hole
> (forensics would find nothing). Reproduced once on a polluted same-agent
> re-drive; flagged, not chased. Evidence:
> `attempt-2-door-reply.json` + the turn→evals ref-count query in this dir.

## Honest confidence

- **Path-doubling / fs-verb / env is NOT the cause: HIGH.** Code reading plus
  two behavioral proofs on the identical env; the agent doesn't even touch the
  fs verb.
- **Original miss was stochastic fabrication (a) rather than ignored-envelope
  (b): MEDIUM.** The original pod eval-log died with its tb container, so the
  original trajectory is unrecoverable; 0/2 fresh reproductions means I never
  captured a fabrication *in the act*. The attribution rests on exoneration of
  the systematic candidates + the mechanism the agent demonstrably uses, not
  on a caught fabrication.
- **Attempt-2 findings (ENOENT + persistence gap): LOW** — a polluted
  same-agent re-drive; likely-but-not-certainly an artifact of deleting the
  file out from under a transcript that claimed success. The persistence gap
  is worth a clean-repro follow-up regardless.

## Files

- `contract.txt` — the exact driven contract (from the real adapter fn).
- `attempt-{1,3}-door-reply.json`, `attempt-{1,3}-evallog.json` — the two
  fresh SUCCESS samples (correct behavior baseline).
- `attempt-2-door-reply.json`, `attempt-2-evallog.json` — the polluted miss.
- `all-evals-raw.txt`, `attempt-*-evallog-raw.txt` — raw wire-REPL captures.
- `file-presence.txt` — in-container `find` + `cat` per attempt.
