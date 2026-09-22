---
type: reference
status: running log; Fable monitors while the owner is away (2026-09-21 evening); Codex/root owns fixes
created: 2026-09-21
tags: [agent-platform, monitor, b1b, cut-1]
---

# Fable monitor notes

Each entry: what was observed (file:line or command), why it matters, the smallest
correct fix. Documentation only; no source, test, config, platform, suite or lane
operations by Fable. The default JVM is never operated from here.

## 18:40 local — after B1b integration (`171388062`) and default replacement

**Observed.** `default` was replaced through the new operator: reset at 00:29:31Z
destroyed `data/store` (358 MB), three boots followed (00:31:23Z, 00:32:46Z,
00:33:26Z; `data/clusters/default/logs/seon.log:609-623`), the live JVM is pid 56288
with `:seon.boot/missing-layers []` and `ready-ms 5468`. All eight drills recorded
green (`landing/b1b-results-2026-09-21.txt`, elapsed 51–166 s; bounds 120–360 s,
about 2–3× measured). Good.

**1. Blocker class: a retired schema attribute with 178 live writers.**
`:seon.error/kind` was retired from `resources/seon/schemas/seon.error.edn` on
2026-09-19 (`bc8152438`, "Retire owned error kinds… under D12"), but 178 sites in 30
`src/` files still construct error values carrying it (`rg '\{:seon.error/kind' src`).
In memory that is harmless; when such a value is COMMITTED as a fact the writer
refuses: `seon.log:624` `Bad entity attribute :seon.error/kind at [:db/add 37294
:seon.error/kind :seon.cluster.reply/no-forms]`, ten occurrences at 20:04Z on the old
JVM and one at 00:33:38Z on the new one. The old default hid it because its schema
predated the retirement; the reset exposed it. This is the rule restored to the root
today ("a schema resource and its loaded consumer land in one publication or not at
all") violated two days ago. Smallest fix: the B3 commit-4 slice (README §4 step 1.5,
"kind/class sites") converts the writers that reach `transact!` first — the reply
no-forms path (`src/seon/cluster/reply.clj:323-352`, read at `turn.clj:3171,4282`) and
the fault committer — and the remaining in-memory constructors in the same cut. Do
NOT re-declare the attribute to silence it. Verify with the error count on `default`
after the next boot (`grep -c "Bad entity attribute" data/clusters/default/logs/seon.log`).

**2. The platform tier cannot run.** `landing/b1b-results-2026-09-21.txt` §platform-guard:
`verify-platform-tier-carries-no-destructive-drill!` refuses because ten tests in
`seon.cluster.source-test`, `source-lineage-test` and `source-evidence-test` reach
`seon.test-support/populate-published-operator-root!` (deletes a path). Their platform
membership is NAMESPACE-level (`test/seon/cluster/source_test.clj:1`,
`source_evidence_test.clj:1`). Open since 2026-09-16 as a blocker
(`docs/seon/issues/platform-flow-census-reaches-root-cleanup-through-scheduler.md`).
Until it is resolved, "cut checkpoint = `bin/test --platform`" is not executable, so
cut 1 has no gate. Smallest fix: drop the namespace-level `:seon.test/platform` from
those three publication namespaces (they are destructive-root tests and belong to the
bulk tier under an isolated root) and declare platform per-test only where a test does
not reach the helper; the B1b `cold-start` drill stays platform. This is the
orchestrator's decision, not a lane's; it is test metadata, no mechanism changes.

**3. `Exception in thread ""`: `seon.error/diagnostic refused observation at
[:seon.error/at]: expected the required key :seon.error/at`** (`seon.log:322,422,470,539`,
old JVM). A constructor called without `:seon.error/at` throws on an unnamed thread,
which is a fault escaping the fault committer. B3 step 1.1 (constructor supplies `at`)
is the fix; note that it is already observed in production, not hypothetical.

**4. Tooling.** (a) `bin/test-check`'s 5,000 ms request bound fired during B1b (landing
note); no issue filed yet — file one or fix the bound owner. (b) Twelve `bb -m
seon.dev.mcp` bridge processes are alive from sessions started 12:36–18:01; each is a
child of the desktop app, so not orphans, but every one started before `171388062`
serves the OLD `script/seon/dev/mcp.clj` and reports `{"seon.dev.mcp/failure":"request",
"seon.dev.mcp/error":null}` against the new JVM. Each Claude session needs its bridge
restarted (Fable did its own). (c) `data/clusters/default/logs/seon.log:629` shows
`Testing seon.id-test` inside default's log: an in-process test run was executed on
`default`; fine as iteration, but it means default's JVM has loaded test namespaces.

**Next for root.** Finding 1 first (it corrupts every error commit on `default`), then 2
(no checkpoint gate without it). Then README §4 steps 1.1–1.3 in order.

## 18:45 local — default's root agent is PARKED (platform failure, highest priority)

Read through the re-dialed bridge (`seon.problems/problems` on `default`, read-only,
24 ms; the 2.4 MB envelope is blob `fe4e96f1583aaf11e95c3fc75bda1178fdfa354de8a16d6b307449c2c01d8b9e`).
The chain on pid 56288, all within sixteen seconds of boot:

| t (Z) | Event | Seam |
|---|---|---|
| 00:33:34 | root agent's first ordinary turn `c7d30b1fa12a` replies with prose only (the Juniper "largest" message; a PAID provider call) | `seon.cluster.reply` no-forms |
| 00:33:38 | settling that evaluation is REFUSED: `Bad entity attribute :seon.error/kind at [:db/add 37294 :seon.error/kind :seon.cluster.reply/no-forms]` | `seon.turn/receipt-settle-batch-call` → `seon.db/transact!` (finding 1 above) |
| 00:33:39–46 | three system-turn refusals "A generated context read depends on the agent's own turn-taking" | `src/seon/turn.clj:2093` (`::generated-read-depends-on-turns`) |
| 00:33:50 | "Agent root with no open turn had 3 consecutive turn writes refused (bound 3); the turn proc is parked and will not re-fire" | `src/seon/turn.clj:5159`, `offer-write-refusal-fault!` `:5189` |

No open turn remains (`(not [?t :seon.turn/closed-tx _])` → empty), so this is a parked
proc, not a dangling turn. The agent loop on `default` is dead until the proc is
re-armed, and it will die again on the next no-forms reply while finding 1 stands.
Also observed: `seon.db/pull` itself returns `{:seon.error/kind :seon.db/invalid-read …}`
for an unknown attribute, so the retired key is constructed by `seon.db` too; and the
refused-write error fact captured 2,383,701 bytes of `:seon.instrument/actual`
(`seon.error/data-size`), which is the B3 "error payload durability" defect in the wild.

**Smallest correct sequence for root:** (1) convert the writers that reach
`transact!` off `:seon.error/kind` (reply/no-forms first) — B3 commit 4, pulled forward;
(2) one regression: a no-forms reply settles as a fact on a fresh store; (3) after it
lands, `bin/seon stop default; bin/seon start` (orchestrator) and seed one message; the
proof is a settled evaluation with `:seon.cluster.eval/error` and NO refusal in
`seon.log`. Whether the three generated-read refusals were caused by the failed settle
is NOT established; record what the system turn's read evidence named before blaming
`turn.clj:2093`.

**Hook publication was re-enabled** in `1a434cc48` (`.claude/seon-hook.edn`
`:enabled true`) after one explicit `init --dev default`. B1 §2d's condition also asked
for the measured `adopt-noncore ≤ 700 ms` row and one observed live hook adoption;
neither is recorded. Record the next real hook event's adoption time in the landing
note, or disable again if an edit costs the old 60–150 s.

## 21:15 local — Fable orchestrates; kind-cut lane launched; platform tier unblocked

Owner handed orchestration to Fable (Codex lanes implement through `bin/codex-agent`).
Actions: (1) `efc3f4e39` removes the namespace-level `:seon.test/platform` declaration
from `seon.cluster.source-test`, `source-lineage-test` and `source-evidence-test`
(every test there reaches the destructive published-root fixture; the runner guard
refused the whole tier). `bin/test --platform` is running as the cut-1 checkpoint
(`tmp/platform-gate-2026-09-21.log`). (2) Lane `kind-cut` (gpt-6-astra, medium)
implements B3 commit 4 in full: delete every `:seon.error/kind` and `:seon.error/class
true` site (730 lines), convert the 167 propagation guards, one class regression
(no-forms reply settles as a fact; fails before, passes after), landing note
`landing/lane-b3-kind-cut-2026-09-21.md`. Default is restarted by the orchestrator
after it lands. (3) A vigilance check every 20 minutes reviews each lane commit as a
principal engineer would and resumes a drifting lane with the exact defect.

## 22:20 local — platform tier GREEN; kind-cut in slice A

`bin/test --platform` at `b4b35d79e`: 81 executed, 606 assertions, 0 failures, 0 errors
(`tmp/platform-gate-2026-09-21c.log`). The chain: `efc3f4e39` moved the three
destructive publication namespaces out of the tier; lane `gate-widening` (`dbb1bc79b`,
`b4b35d79e`) fixed `widening-path?` so a bare graph root never widens (three-case
regression) and declared the selection lifecycle test's bound from three measurements
(6,336 / 5,067 / 4,903 ms → 8,000 ms with reason). Cut 1 now has a checkpoint gate.
Lane `kind-cut` committed the fails-before regression (`aba6d445e`) and is converting
the settle path (turn, reply, cluster/agent dirty); RESTART NEEDED follows slice A.

## 2026-09-22 00:05 local — kind-cut landed (bc70a82e3); `stop` leaves a zombie JVM

Lane `kind-cut` reports zero `:seon.error/kind` / `:seon.error/class true` matches,
`;; debt:` 167 → 7 (each named), HEAD loads, 21 commits, 192 files, +4,195/−1,818.
Its green proof is blocked by (a) a stale published fixture base (schema members were
added; `bin/test --prepare-head-base` is running) and (b) a Datahike logging failure in
effect tests (to be read from the note). An Opus read-only review of the whole diff is
running; its report lands at `docs/research/agent-platform/kind-cut-diff-review-2026-09-22.md`.

**B1b defect found on the first real restart.** `bin/seon stop default` stopped the
instance (`:seon.operator/stopped? true`) but the JVM stayed alive at `@(promise)` with
no advertisement and a closed prepl; `bin/seon start` then refused with "An exact-root
JVM is alive but its endpoint is unavailable" (`script/seon/operator.clj:278` routes
start to `connected!` whenever `selected-processes` is non-empty). Smallest fix: in
`seon.cluster.boot/request!` `:stop`, when `running-instances` is empty after the stop,
the JVM exits after replying (the launch form's promise is the only thing keeping it
alive; `src/seon/cluster/boot.clj:380-392`), and one drill asserts that `stop` of the
sole instance ends the process and a following `start` launches cold. Recovery used:
`bin/seon down` then `bin/seon start`.

## 2026-09-22 02:40 local — kind-cut review verdict; publication-from-zero defect; loop parks on boot

- Opus read-only review of `aba6d445e~1..bc70a82e3`
  ([report](kind-cut-diff-review-2026-09-22.md), `0a03fbafd`): KEEP with fixes. Clean:
  no renamed discriminator, general predicates died with their callers, debt 167 → 7,
  contracts added not removed. Blockers: replacement guards are hand-written SUBSETS of
  the producer's declared union — `src/my/program.clj` (27 sites) reads 2 of ~60
  `:seon.db/error-result` alternatives; `src/seon/agent.clj:135` omits the config
  refusal member so a refusal flows into `ai/settings` as data. Unproved: only
  `seon.cluster.reply-test` recorded green; the effect blocker is a 320 s no-progress
  hang at `request-commits-before-io-dispatch-and-settles-once`, not a logging failure.
- Publication from zero refuses at `src/seon/print.cljc:647`: the lane put a
  `:malli/schema` on a `defmulti`; the signature analyzer derives no arglists for it
  (`:analyzer-disagreement`), so `bin/test --prepare-head-base` fails and no cold gate
  can run. Incremental adoption did not exercise the path. Lane resumed with the fix
  and a from-zero scratch-root proof.
- The turn proc parks eleven seconds after EVERY fresh boot (pid 15000, 08:13:02Z →
  `seon.log:23174`), independent of the kind cut: three "generated context read
  depends on the agent's own turn-taking" refusals. Lane `turn-parks-on-boot` (astra
  medium) is diagnosing with live probes. Four `seon.cluster.wake/deliver!` contract
  refusals (`expected a string, got a keyword :seon.agent/route`) and one core-fault
  channel overflow are in the same brief.
- B1b: `stop` of the sole instance leaves a zombie JVM that refuses `start` (recorded
  above); fix queued for a lane after the current three land.

## 2026-09-22 02:55 local — loop no longer parks; hook publication PAUSED; two B1b client defects

- `turn-parks-on-boot` landed (`0e0e8b6ba`, `feafab1b3`): `:seon.turn/rule` was wrongly
  marked `:seon.wake/context-inert`, and `seon.fn`'s diagnostic named
  `:seon.cluster.eval/source` instead of `:seon.fn/source`, so five error-observation
  reads looked like turn activity; `wake/deliver!` now admits the keyword listener
  keys it actually receives. Default restarted (pid 21908): 75 s after boot the parked
  count is unchanged at 2 (both pre-fix lines). First boot today whose loop stayed
  alive. The opening regression is still blocked by the stale fixture base.
- Hook publication paused (`.claude/seon-hook.edn` `:current-source {:enabled false}`,
  rule 15): with three lanes editing `src`, lane `base-export-timeout`'s uncommitted
  `:seon.config.operator/export-bound-ms 600000` in `config/default.edn` was adopted
  before its schema declaration and default's config apply refused it
  (`seon.log` 08:50:58Z). Re-enable at the coordinated restart after the three lanes land.
- B1b client defects: (1) `bin/seon down` fails to read its own reply:
  `java.lang.RuntimeException: No dispatch macro for: '` — the `:down` reply carries a
  `#'var` (or similar reader-tagged value) that `edn/read-string` cannot read
  (`script/seon/operator.clj:113`); the reply must be data. (2) `stop` of the sole
  instance leaves a zombie JVM (recorded 00:05). Both go to one lane once a slot frees.
- Addendum 03:00: the pause is now actually in effect (`a76867fe1` recorded it before
  the edit applied). `bin/seon status` at pid 21908 throws inside the JVM
  (`count not supported on this type: Keyword` in `seon.cluster.boot/request!`
  `:status`, `src/seon/cluster/boot.clj:369-374`): with lane edits to `db.clj`,
  `config.clj`, `fn.clj` half-adopted, no conclusion about the code at HEAD; re-check
  at the coordinated restart.

## 2026-09-22 03:45 local — store growth diagnosed (248 GB); export bound landed; RESET queued

- `store-growth` (`d368f217d`, landing `landing/lane-store-growth-2026-09-22.md`):
  248.40 GiB in 899,520 konserve files; 245.11 GiB is Datahike commit/index ancestry
  retained under the epoch GC cutoff; 296 MiB unreachable; blobs 0.74 GiB, all owned.
  The writer: a feedback loop — every transaction made `wake/deliver!` refuse its
  keyword key, recording the fault was itself a transaction (22,537 refusals in 24,538
  transactions). Root fix is `0e0e8b6ba` (turn-parks-on-boot); regression
  `test/seon/store_growth_test.clj` proves one listened write = one commit, no fault
  write. The ~10 MB of rewritten 6.3 MB persistent-set index leaves per commit is
  A2's retention/currency seam (README §4 cut 2), not a lane's fix. **RESET NEEDED**
  to reclaim the disk; the orchestrator resets after `kind-cut` lands (dirty src
  edits would otherwise load at boot).
- `base-export-timeout` (`64c31cf75`): one export request ran 484 s under the 300 s
  boot bound; now each operation carries its declared bound (`export-bound-ms 600000`
  as a config fact with its reason) and the export emits progress; child preparation
  measured 179.9 s. That number is B4's motivation (fixture on the open store), not a
  constant to keep.
- Fixture preparation still fails against pid 21908, whose config apply was refused by
  the half-landed key and whose projection state is missing; it will pass after the
  reset. Astra hit "model at capacity" once; the lane was resumed on Sol.

## 2026-09-22 04:20 local — coordinated reset done; kind-cut complete; checkpoint running

- `kind-cut` finished through `3f1b50ba9`: review blockers fixed (producer guards),
  print bound measured, and the effect hang fixed in the Datahike fork (a non-map in
  the diagnostic evidence made the writer's error path throw and strand the caller's
  callback; fork commit `6dd49e5e`, pushed to `seantempesta/datahike`, pin bumped).
- `bin/seon reset --force`: store 248 G → 87 M; default pid 28358 ready in 161.8 s
  from zero; hook publication resumed (`.claude/seon-hook.edn`); fixture base prepared
  in 136 s (`tmp/prepare-head-base-2026-09-22e.log`). Cold gate running
  (`tmp/cold-gate-2026-09-22.log`). Lane `operator-client-defects` (sol low) on the two
  B1b client defects.

## 2026-09-22 04:40 local — LIVE PROOF: the agent loop runs and no-forms replies settle

On the fresh default (pid 28358, reset store) the root agent ran real provider turns
on the Juniper messages: eight errored evaluations are durable facts
(`seon.problems/errored-receipts`), including three "Your reply had no form; only
comments/prose" settlements (turns `2bda1f1428b3`, `c7d30b1fa12a`, `add1da07b0d6`),
plus ordinary agent mistakes ("Unable to resolve symbol", "cannot read uninstalled
attribute :example/amount"). No new refusal in `seon.log` after the reset (the two
lines at 08:50:58Z are the pre-reset half-landed config key); the parked count is
unchanged. The class the kind cut exists for is closed live.

Watch: the store grew 87 MB → 796 MB in the first five minutes with hook publication
on and one lane editing; that is A2's retention/index-leaf seam (each publication
rewrites 6.3 MB leaves), not a loop. Re-measure at the next check; pause publication
again if it passes a few GB.

Cold gate at `09e8ba533` refused selection: "requested external inputs differ from the
published database" with no differing key named; lane `gate-input-mismatch` (sol
medium) owns the diagnosis, the fix at the owner, and the evidence-naming refusal.

## 2026-09-22 05:05 local — gate input authority fixed; publication paused for store growth

- `gate-input-mismatch` (`42ecbacf3`, `59012e386`): publication hashed only its 283
  indexed program files while selection hashed all 302 declared inputs; the 19
  nonindexed test fixtures/probes were the difference. Both now derive from one
  inventory and a refusal prints every differing path with both digests. Two
  pre-existing reds remain in its 18-test run: one test at 8.8 s over its 5 s bound
  and an incomplete declared-reference error facet — queued for the next lane.
- Store: 87 MB → 796 MB (5 min) → 7.4 GB (15 min) with hook publication on and one
  lane editing: ~150 MB of rewritten index leaves per adoption (A2 retention/currency,
  README §4 cut 2). Hook publication PAUSED again; the orchestrator adopts at
  checkpoints with `bin/seon init --dev default`. The disk-filling class is no longer
  a loop, but it is not closed until A2 lands.
- Next: re-prepare the fixture base (publication now records the complete input map),
  then the cold gate at HEAD.

## 2026-09-22 05:30 local — default DOWN on purpose: settle writes reference a missing evaluation

Since 10:19:52Z every write on default failed with `Nothing found for entity id
[:seon.cluster.eval/id "…"]` (8 occurrences, a new eval id each time, the branch head
`6ab25303…` never advancing; `seon.log:23322-23331`). Each retry is an agent turn and
therefore a provider call, so the orchestrator ran `bin/seon down` to stop the spend.
Lane `settle-missing-eval` (sol medium) reproduces on a scratch root with a virtual
reply, names the seam (lookup ref before the row is written / a kind-cut change on the
settle batch / mid-turn adoption) and fixes the owner. Base preparation is also
blocked until `operator-client-defects` lands (its dirty schema resource makes the
publication guard refuse: "Snapshot resources differ from the hosting JVM's source
tree"). Default is restarted after both land.

## 2026-09-22 06:10 local — two more owners fixed; checkpoint chain running

- `operator-client-defects` (`acd0d73e5`): sole-instance `stop`/`down` flushes its reply
  and exits 0; the client awaits `ProcessHandle.onExit`; every boot reply is validated
  as plain EDN at the producer; `:seon.operator/process-exit?` added to the reply
  schema and the B1b command table; two drills; run `88ca15dd3638` 10/122/0/0. Rule
  slip: the lane used a temporary worktree (lanes never create worktrees); the next
  specs say so explicitly again.
- `settle-missing-eval` (`e02604e44`, `d9609b1a0`): `seon.db/stamp-receipt` put the
  evaluation lookup ref in transaction METADATA, which Datahike resolves before the
  transaction data that creates the receipt (introduced by `e23b8105a`); provenance now
  follows receipt creation (`src/seon/db.clj:3370`); regression
  `settlement_receipt_provenance_test` fails-before (`825dd0e49dda`) / passes-after
  (`601a4a37ef39`). The lane also reports `seon.cluster.turn-test`/`seon.turn-test` at
  HEAD as broadly red (94 failures / 35 errors, "unrelated shared-HEAD breakage") and a
  separate Malli defect at 10:25:52Z — both for the cold gate to name.
- Chain running: `bin/seon start` → `--prepare-head-base` → `bin/test` bare
  (`tmp/cold-gate-2026-09-22c.log`).

## 2026-09-22 06:45 local — platform tier executes again; three reds to a lane

Default pid 38968 (7.3 s warm boot) on the settle fix; no new `entity-id/missing`;
store stable at 7.5 GB with publication paused. Fixture base prepared in 34 s.
`789eb63b0` (`fixture-observations`) declared twelve truthful fixture observations so
selection admits the bulk tier (platform 83, bulk 1807, not reached 110). The platform
tier then ran 83 and is RED on three (`tmp/cold-gate-2026-09-22d.log`):
`declaration-population-test:105` (the unhanded-projection refusal lost
`:seon.schema/expected-value`, suspect the kind cut's schema.clj conversions) and two
`test-support-test` durations at 5.3 s / 5.7 s over the 5 s bound. Lane
`platform-reds` (sol medium) owns all three; the cold gate reruns after it lands; the
1.1 constructor lane launches on a green checkpoint.

## 2026-09-22 08:05 local — first full cold gate on the branch: platform GREEN, bulk 514 red

`platform-reds` (`c1d059abb`, `732866ead`, `67a0e940b`): the schema refusal carries its
declared members again; fixture acquisition re-identified 266 retained commit records
instead of the branch heads it opens (now ~1.0 s, no bound relaxed). Cold gate
`tmp/cold-gate-2026-09-22e.log` at `67a0e940b`: platform 83 green; bulk 1,807 executed
in 2,762 s; **514 distinct failing tests** (916 FAIL, 323 ERROR lines), of which 145
are duration failures over the 5 s bound. Largest groups by test: `schema_audit_test`
(38, permissive graph positions lacking justification), `run6_db_test`/`string.clj`
query-contract (37), `db_test:643` public reads preserving an upstream database error
(13, the subset-guard class the kind-cut review named), `issue_settlement_test` (8),
`instrument.clj:766` receipt transitions (10). An Opus read-only triage groups all of
them by root-cause class with owner seams and a lane plan
(`docs/research/agent-platform/cold-gate-triage-2026-09-22.md`). No cut-1 step 1.1
lane launches until the classes in B3's files are down. Store 7.6 GB, publication
paused, default pid 38968.

## 2026-09-22 08:20 local — back on the plan (owner)

Owner: follow the ruled order, not the full-suite reds; delete first, write the
replacement, develop in the REPL, then test; no bulk tier mid-cut. Status against
README §4: 1.3b (B1b) done; B3 commit 4 (kind/class) done; 1.1, 1.2, 1.3, 1.4, 1.4b,
1.5 (hook, search) not started. The three triage lanes were stopped: `ce83344ce`
(symbols in program fixtures) and `370fe907a` (turn dependency inputs, continuation
wait evidence) landed coherent; `error-facets`' partial edit is shelved at
`tmp/error-facets-partial-2026-09-22.patch` (instrument.clj:774 idea).
Launched: `constructor-slice` (astra low) = step 1.1, alone in src (275 sites; the
`at` input change is atomic). Resumed beside it: `string-test-symbols` (test files
only, told to leave `diagnostic-*` lines alone). Queued, in this order, one lane at a
time in src: `error-facets` and `turn-shapes` from their shelved state (owner: finish
partial side work), then 1.2 digest (sol low), 1.3 wrappers (astra low), 1.4
projection + ambient transport deletion (astra low), 1.4b shape family (sol low), 1.5
hook + `seon.search` (sol low). Gate policy from here: `bin/test --platform` at step
landings; the bulk tier once at the end of cut 1. The loop prompt was rewritten to
say exactly this.

## 2026-09-22 08:50 local — ruling for 1.1: the constructor's Throwable input is `:seon.error/throwable`

`constructor-slice` probed that `:seon.error/cause` is declared `:seon.db/ref` (the
error-chain ref), so a Throwable under that key makes the promised `:seon.error/base`
output invalid. Resolved with names the schema already declares, not a new decision:
the constructor input is `[:seon.error/throwable {:optional true} :seon.error/throwable]`;
the Throwable is consumed into `:seon.error/frame` and `:seon.error/exception-class`
and never carried in the returned base (B3 §2a's store row lists frame/exception-class,
never the object); `:seon.error/cause` stays the stored ref. lane-b3 §2a is corrected
by the lane in the constructor commit.

## 2026-09-22 09:40 local — ruling: errors are explicit named schemas; the facet layer retires

Owner: errors are data with explicit named schemas and validations; a function declares
the explicit union it can return; callers branch on that union or a member; the program
graph answers which functions can error, with what, how it propagates and who fixes it.
Recorded in README §7. Consequence for the queued `error-facets` resume: do NOT replace
the four hand-written unions with a registry-derived complete schema; each producer
declares its explicit union (or is typed on the base if it is an error-handling owner);
the wrapper check at `instrument.clj:774` validates against the arity's declared union,
never against base shape; rename `facet` → declared error schema.

## 2026-09-22 10:30 local — step 1.1 landed

`constructor-slice` (`20ee7864b`, `e569532dd`, `9bf22ecd9`, evidence `92a97636c`): one
constructor `seon.error.refusal/diagnostic` (`at`/`layer`/`operation`/`message?`/
`throwable?`), facade deleted, every `diagnostic-*` key retired with its callers (scan
empty across src/script/bin/test), HEAD loads per commit, live probe 4.6 in 167 ms, four
regressions green. Its broader focused runs are red; those reds are judged by README §6's
three questions, not chased. Opus read-only diff review running
(`constructor-slice-diff-review-2026-09-22.md`); platform tier running
(`tmp/platform-gate-2026-09-22-after-1.1.log`). Next lane launched: the error-schema
lane (resumed `error-facets` under the explicit-union ruling: each producer declares its
exact union, wrapper validates against the arity's union, `facet` renamed out of
src/test/resources). Then `turn-shapes` (hang), then step 1.2.

## 2026-09-22 11:00 local — ruling: A2 c1+c2 pulled into cut 1 (parallel track 1.3c)

Owner: more parallelism. Wave A = error-schema lane (instrument/error/admit/kernel),
1.2 digest (program.cljc, `var-row`, three schema resources, selection reader), A2 c1+c2
(db.clj, store.clj, registry, GC). Wave B = 1.3 wrappers, 1.5 search deletion,
turn-shapes. Wave C = 1.4 alone. Wave D = 1.4b, then RESET batch 1. SCI candidates
(branch + fork) isolate agent-level work after D1 (cut 3); the platform's own plumbing
cannot be hosted in a fork, so cut 1 isolates by file ownership and snapshots.
Platform tier after 1.1: 83 executed, 3 failures in one test (`env_test.clj:349,367,380`
expects the retired nested `:seon.error/data` path) — `constructor-slice` resumed to
fix the expectation or hand a wrapper defect to the error-schema lane by name.
