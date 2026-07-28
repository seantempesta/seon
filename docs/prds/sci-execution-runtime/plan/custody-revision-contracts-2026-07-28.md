---
type: prd
status: active
tags: [prd, agent, runtime]
---

# Custody revision — contract package (2026-07-28, F0(c)+(d) merged)

One sealed revision of the run model closing both REPL-proven
violations from
[../research/trigger-conservation-2026-07-28.md](../research/trigger-conservation-2026-07-28.md)
and executing the deletion slice from
[../research/zombie-constructibility-2026-07-28.md](../research/zombie-constructibility-2026-07-28.md).
The two audits corroborated each other's constructions independently;
their docs carry the interleavings and probe scripts. This package
states only the contract deltas — the research docs are the evidence
authority. `plan/README.md` remains the only ordering (this is F0(c)+(d)
of THE PROGRAM).

## The law

**CUSTODY PRECEDES WORK.** A pass may act on a run only under custody it
verified or acquired in that same pass. `:close` already behaves this
way; `:resume` and `:call` do not — that asymmetry is the root cause of
both violations (the unheld-resume livelock and the lease-lapsed
duplicate paid call).

**Custody IS presence.** `:seon.agent.run/process` present = held;
absent = unheld. Claiming asserts it via CAS-on-absence; recovery
retracts it after stamping every running receipt of that custody
`interrupted-at`. There is no epoch and no lease: the zombie audit
proved every scenario the epoch guarded is unrepresentable by the
surviving fences, and the flock + single writer make a competing
claimant impossible.

## Deletions (exact — the zombie doc §deletion-slice enumerates
file:line)

- Schema: `:seon.cluster.run/claim-epoch`, `:seon.cluster.run/lease-until`,
  `:seon.cluster.eval/claim-epoch`, and the terminal-request epoch field.
- Functions with zero or refusal-only production callers: `claimed?`,
  `expired?`, `heartbeat-tx`, `heartbeat-call`.
- Refusal arms: `::stale-epoch`, `::lease-expired`,
  `::stale-receipt-epoch`, `::lease-live`.
- The three 60-second lease constants in `loop.cljc` and the epoch prose
  in `error.clj`.
- Receipt identity shrinks to `(run, ordinal)` — re-execution
  unrepresentable, strictly stronger than the epoch it replaces.
- ~76 test references across 5 suites revise to the presence model —
  every behavioral assertion is KEPT and re-expressed; deleting an
  assertion (rather than re-grounding it) requires a named reason in
  the commit.

## Revisions

1. **`:resume` claims before folding.** The pass CASes
   `::process` onto the unheld run (absence → this process) in the same
   transaction boundary that admits the resume; a lost CAS is a quiet
   skip (another pass owns it), never an error fact. The disposition's
   terminal transaction then finds the holder present — the livelock
   becomes unrepresentable rather than caught.
2. **`:call` verifies custody in-pass.** `next-work` derives `:call`
   only for runs this process holds (presence + process match, read at
   the pass basis); the paid call can no longer race a custody it never
   checked.
3. **Takeover = recovery, one shape.** Claiming a run whose holder is a
   dead process (start-instant mismatch or absent) first stamps that
   custody's running receipts `interrupted-at`, then retracts/asserts
   `::process` — one `:db.fn/call` transition so the intermediate state
   never exists. This is the boot recovery path generalized; boot stays
   the caller.
4. **`recover-tx` hardening (the zombie doc's one soft spot):** the
   settled-receipt guard moves inside the transaction
   (`:db.fn/call` reads the receipt at tx time) so a stale-basis
   recovery can never stamp `interrupted-at` onto a settled receipt —
   one line, unreachable today, unrepresentable tomorrow.
5. **The nil-`:in` guard** (`loop.cljc:666-669`, the conservation doc's
   mis-consumption class-mate): `message/trigger`'s result is checked
   before use; a nil trigger is the one sealed refusal, never a
   wildcard query input. (Root-cause issue
   `a-nil-query-input-matches-anything-so-prompt-cannot-refuse.md`
   stays open for the query-layer fix; this seals the consumer.)

## Kept fences (the survivors, now the whole story)

- `::process` custody presence (incident: an unplanned run mid-call is
  otherwise indistinguishable from one whose holder died — the loop
  would retry a paid call).
- `::not-the-holder` as the ONE loud custody refusal.
- Settle-once presence fences (terminal facts CAS from absence) and
  recover CAS-on-absence.
- The one-open-run agent pointer fence.

F1 pins exactly these; nothing else survives to pin.

## Sealed-suite deltas

- The two audit probes become recurring regressions: unheld-resume
  (P1 — resume claims, disposition commits, run closes; no error-fact
  storm) and lease-lapse (P2 — rewake of a held run derives no second
  `:call` for another process; with leases deleted the scenario is
  re-expressed as custody-mismatch, and the assertion is zero duplicate
  provider dispatches across the interleaving).
- The state-machine generative property extends its transition alphabet
  with claim/takeover under the presence model (fixed seed, per-trial
  databases, as the suite already does).
- `bin/test` green from the 407/1580/0 baseline (+ concurrent lanes'
  additions); the ~76 revised references land in the same commits as
  the code they re-ground.

## Sequencing constraint

`src/seon/cluster/loop.cljc` and the prompt/turn suites are owned by
the in-flight context-blocks implementation lane. This package
DISPATCHES AFTER that lane returns — same-file ownership is never
split. `run.cljc`/schema deletions do not start earlier either: the
revision is one coherent wave, not two half-states.

## Implemented (2026-07-28, one wave — `435b343ac`)

Landed exactly as sealed, one commit for the whole cut plus its ~76
re-grounded test references, one follow-up for the issue closures
(`100159309`).

- Deletions: all three schema attributes + the terminal-request epoch
  field; `claimed?`/`expired?`/`heartbeat-tx`/`heartbeat-call`; the
  four refusal arms; the three 60-second constants; the `error.clj`
  epoch prose; receipt identity now `(pr-str [id ordinal])`.
- Revisions 1-5: `:resume` claims before folding (lost CAS = quiet
  skip, never an error fact); `:call` custody is `next-work`'s
  presence+process match with nothing left to lapse; `claim-call`'s
  dead-holder arm IS recovery (stamp running receipts, swap custody,
  one `:db.fn/call`); `recover-tx` became `recover-call` — the
  settled-receipt guard reads at transaction time, and the boot caller
  (`recover-runs!`) now passes only run ids; the prompt's
  `::no-trigger`/`::missing-input` throws are caught at the loop's one
  `:call` site and recorded as flat error values (the standing-law
  violation the context-blocks lane flagged).
- Probe evidence: the two audit probes ran RED before the cut
  (livelock chain `::not-the-holder` → `::receipt-exists`; `:call`
  re-derived after `::lease-expired`; order-B stamped a settled
  receipt). After: `tmp/custody-revision-green-probe.clj` — P1 run
  closes and derives nothing, P2 one freeze + custody mismatch derives
  no work for another process, order-B receipt byte-untouched. The old
  probes no longer compile: their requests are unrepresentable.
- Recurring regressions: `run_test`
  (takeover-stamps/settle-refuses/`::receipt-exists`-forever,
  `recovery-cannot-stamp-a-settled-receipt`, recovery idempotence, and
  the state-machine property's claim/takeover alphabet under presence);
  `turn_test` (`a-recovered-unheld-planned-run-completes-without-error-facts`,
  `a-held-runs-paid-call-is-never-duplicated`,
  `a-prompt-refusal-is-a-recorded-error-value-never-a-throw`).
- Issues: `a-turns-model-work-can-outlive-its-own-run-lease` and
  `my-run-error-values-omit-their-kind` resolved → archive;
  `a-nil-query-input-matches-anything-so-prompt-cannot-refuse` stays
  open for the query-layer fix, consumer sealed.
- Gate: full `bin/test` green (the baseline's one failure was the
  my.run canary firing as designed; one transient `schema.edn-test`
  failure during the mid-wave gate was a concurrent lane's in-flight
  edit and re-runs green).
