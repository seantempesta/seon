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
- Correction 11:10: A2's c1 is read-evidence currency (turn context; edits `turn.clj`) and
  c2 is the codec behind the §6.2 comparator proof; neither is the adoption cost. Track
  1.3c is A2's storage retention (c10 GC sweep, c12 keep-history key, the epoch-cutoff
  retention decision, `:db/noHistory` on churn attributes), then §6.2 → f1 → c2 for 1.4.
  README §4 row corrected. The 1.2 digest lane is launched (sol low).

## 2026-09-22 11:40 — constructor follow-up landed; A2 retention launched; error-schema lane re-briefed

- `constructor-slice` follow-up landed: `2add67c85` (four retired nested-operation assertions → flat), evidence `fd0fdb7bc`; env tests 5/73/0. Slot freed.
- Opus review of 1.1 (`e20346415`): keep with fixes. F1/F2/F6/F9 dropped non-duplicate causes (dead `cause` params); F3 overloads `:seon.error/source`; F4 nests canonical members under `:seon.error/data` with an overwriting merge; F5 deleted the only coverage of fourteen surviving render functions. Ruled as an application of "errors are explicit named schemas" (README §7 new row): F3–F5 to the error-schema lane; F1/F2/F6–F14 to a constructor-repair lane after the rename commit lands.
- `error-facets` stopped itself on the recorded-identity question; ruled option 2 (the one declared schema name in the signature; no migration). Resumed (astra medium) with: rename commit FIRST because its hunks share `seon.program.edn`/`program.cljc`/`seon.ns.edn`/`schema.clj` with the digest lane.
- `definition-digest` stopped and resumed (sol low) with the shared-file rule: never commit a file carrying the other lane's hunks; wait for the rename commit.
- Launched `a2-storage-retention` (astra medium): f4 dry-run sweep → c10 → declared retention cutoff (proposal to §7) → `:db/noHistory` on churn attributes (RESET) → f8/c12 → §6.2 comparator proof → f1 → c2. Goal: an adoption grows the store by its datoms; hook publication re-enabled when measured cheap.
- Lesson: I resumed a lane with `&` and a redirect; stopped and relaunched bare (panel rule).

## 2026-09-22 12:05 — 1.2 landed; concurrency ruled by files not count; B1 publication and D1 candidates pulled forward

- Step 1.2 LANDED: `f27b96c19` (declaration constructors, required schemas, stored B4 reader, regression), evidence `3f58fd991`; rename prerequisite `6d84f27fa`. RESET NEEDED (batch 1). Its focused runs could not publish the canonical fixture: the published base's cached projection still names retired `seon.error/facet-counts-agree?` — the base is stale after the rename; B4 c1 (fixture on the open store) dissolves that class, launched now.
- Owner ruling (question tool): five lanes + reorder. README §4 and AGENTS.md now say concurrency is bounded by disjoint files, the two-JVM slot and four probers, five the working ceiling; a shared file is committed only by the lane whose hunks are alone in it. New rows 1.2b (B1 commits 2–10, incremental publication, after 1.2) and 1.4c (D1 candidate acquisition, after 1.4, merge orchestrator-manual until cut 3).
- Launching now: `b4-fixture-open-store` (sol low), `search-deletion` (sol low), `constructor-repair` (astra low, review F1/F2/F6–F14). Running: `a2-storage-retention`, `error-facets`.
- 12:30 `bin/test --prepare-head-base` refused twice: "Snapshot resources differ from the hosting JVM's source tree; align the declared inputs before publication" — the HEAD snapshot's schema resources differ from the working tree the hosting JVM loaded, because five lanes hold dirty resources. The published base therefore stays at the pre-rename commit and still names `seon.error/facet-counts-agree?`; lanes prove at the REPL and in process and record this as a foreign limit. Not chased: B4 c1 (fixture on the open store, running) deletes the published-base mechanism. Retained roots reaped (no holders). Log: scratchpad `prepare-head-base.log`.

## 2026-09-22 13:05 — A2 retention landed (c10, retention, f8/c12); §6.2 failed, codec retained; tests-as-agents ruled; Opus data pack launched

- `a2-storage-retention` landed four commits (`1cc00a4a5` c10, `289c9b587` retention declaration, `907b231fe` f8/c12, `9d4fa01a2` evidence); forks pushed. Synthetic proof: 1,750,551 → 501,131 bytes, 9 → 4 keys, sweep 43.9 ms. Full adoption measurement blocked by a foreign test referencing retired `error/properties` (error-schema lane's tree). Ratified the retention declaration (README §7 new row). §6.2 comparator proof FAILED → option 1, codec retained; 1.4 removes the projection binding by argument instead (README §7 row updated). No-history on fault `count`/`last-at` → error-schema lane.
- Owner ruling: tests run as agents run — branch fixture, forked SCI context, custody injection, on the shared JVM, lane edits reaching the branch through the index function; B4 c1+c2 into cut 1 as 1.3d (`51b4ba78b`). Opus data pack requested: `docs/research/agent-platform/tests-as-agents-data-pack-2026-09-22.md`; the orchestrator adapts B4/B2 §2a/D1 from it.
- `bin/test --prepare-head-base` refused (dirty resources vs HEAD snapshot); not chased.
- Running: error-facets, b4-fixture-open-store, search-deletion, constructor-repair. Next launch: 1.2b (B1 commits 2–10) once search-deletion and constructor-repair free `cluster.clj`.

## 2026-09-22 14:10 — data pack + astra review in; track 1.3d rewritten; four lanes reported

- Data pack `docs/research/agent-platform/tests-as-agents-data-pack-2026-09-22.md` (Opus) and astra review `2b1f1911d`: every lifecycle primitive is an installed Seon function; B4's own execution lifecycle is the accretion; B2 §2a steps 1–3 are unimplemented (the crux); `publish!` hard-codes `:current-src`; `refresh-source!` implies a process-wide reload (`cluster.clj:1984`). README §4 row 1.3d rewritten to six commits, agent seam first (`ffb1c4872`); §7 gained the pending shared-reload decision (three options; owner asked what "admitted work" means — answered: an evaluation or test mid-flight on a fork sharing the JVM's compiled Vars).
- `error-facets` landed `a86e93e21` (declared outputs validated; named identity through recording) + `ec04dd436`; still running.
- `constructor-repair` partial: `59e9642b1` (operator dispositions, dead reply args) + `1a19fbef7`; remainder prepared, uncommitted behind shared files.
- `search-deletion` complete in tree, uncommitted behind shared files (`cluster.clj`, `seon.db.edn`, `seon.effect.edn`, `seon.error.edn`).
- `b4-fixture-open-store`: branch fixture works (41.64 ms first, 34.88 ms p50 later) but the legacy runner hands no execution connection; ruled option 3 per the new order (test callers last) and shelved to `tmp/b4-c1-partial-2026-09-22.patch` with a landing note; no custody bridge in the runner (would be a test-side copy).
- Working tree loads (`seon.effect`, `seon.cluster`, `seon.schema.admission`). Commit sequencing once error-facets lands: search-deletion first (its files may carry the repair's coherent hunks; HEAD must load), then the repair remainder.

## 2026-09-22 16:00 — tree clean; search deletion and constructor repair landed; model ruled; RESET batch 1 started

- `search-deletion` landed `434c01f4c` (+`19a11478e`): 943 lines out, `seon.search` gone with its proc, env member and schema; `tokens`/`similar-identities` moved to admission.
- `constructor-repair` landed `252e6e5bd`, `a2aac9105`, `7fc62edbc` (+ earlier `59e9642b1`/`1a19fbef7`): review F1/F2/F6–F12/F14 closed; F8 phase declared at both producers; 58 in-process assertions.
- Owner rulings written to `docs/seon/architecture/clusters-branches-contexts.md` and README §7 "One JVM, many realities" (`0d2aba52b`, `62ce90903`): program = rows on a branch; default's program rows and loaded namespaces derive from the files; other branches interpret their differences; agent mode = branch attribute; tests = one-body isolated agents; merge = program rows, gate + named accept; FS lanes on one candidate branch of default; reload = `require :reload` of changed namespaces + dependents; retirement = unlink then GC. Three Opus data packs in flight (program rows, host-bound rows, merge/write-back); track 1.3d's commits re-derived from them before commit 1 launches.
- Tree clean except the two foreign docs files; no lanes running. `bin/seon reset --force` started for RESET batch 1 (definition digest, error identity). Then: prepare the fixture base from HEAD, `bin/test --platform` once.

## 2026-09-22 18:40 — wave A of the reprioritised cut 1 launched; four lanes

- From-zero reset REFUSED the population transaction ("Bad entity attribute :seon.db/process"): a declaration retired while the indexer still writes it (`fn.clj:2743,2853,3423,3455`; readers `reconcile.cljc:135,437`). My failure as orchestrator: five lanes in schema resources, commits accepted on "HEAD loads". Rule now in the loop, memory and plan: a schema-resource commit is accepted only with a scratch-root from-zero boot; plan row 1.3e makes the schema writer refuse a retirement while `:seon.fn/writes`/`references` name the attribute. Lane `boot-process-attribute` (astra low) restoring the declaration at its owner.
- Four data packs landed (program rows `aef…`, host-bound rows, merge/write-back, reload into the REPL). Reload verdict: correct reloader (`require :reload` in topological order from stored `:seon.ns/requires`, explicit unmap), worse envelope (manifest/seal/snapshot, whole-Var re-arm, a bb process per hook event).
- README §4 reprioritised (`5a1c2fa00`): the agent half and the file half of one mechanism in parallel waves; AGENTS.md gained "One JVM, many realities — the branch system" (`20e4ad941`); vocabulary grounded rows + targets (`a87c9991f`).
- Launched: `publication-envelope` (1.2b, astra low: B1 rows 2/6/7/8 + measurements incl. the `seon.id` closure compile cost), `schema-retirement-refusal` (1.3e, astra low), `realities-commit-1` (1.3d c1, astra medium, owner-confirmed: custody function, overridden by digest, affected via gate-sets, named refusal, host-bound by form head; six-part proof on two branches). Loop recreated (`4b8c0f07`).
- `default` (pid 28922) is up but unpopulated; reset again after the boot repair lands and my scratch boot passes.
- 19:10 CORRECTION: `:seon.db/process` was never deleted (`seon.db.edn:245` still declares it). The from-zero refusal came from the rename commit `6d84f27fa`: in `src/seon/schema/datahike.clj` the outer `facets` → `properties` rename shadowed the inner `(m/properties root)` binding, so standalone stored attributes were dropped from the installed schema. Fixed at the owner (`6ec971b07`, two lines) with a regression and the from-zero population measured (2,203.9 ms). Only a from-zero boot could catch it; the rule stands.
- Second retire-without-convert from the search deletion: `config/default.edn:540-544` names the deleted supplier `seon.search/supplied-handle`; scratch boot failed on configuration after schema population. `search-deletion` resumed to delete the row, sweep every remaining reference, and prove a scratch-root boot.
- 1.3e: the refusal owner already exists — `removed-definition-error` (`db.clj:3889`) checks write/schema edges at the final transaction; the defect is the producer: `:seon.fn/writes` records a literal attribute write only while the attribute is declared. Ruled option 2, fix the producer in `fn.clj`; no keyword heuristic. Lane resumed.
- One-lifecycle design written (`lane-realities-one-lifecycle.md`, `d002f0759`); astra review `one-lifecycle-review` running. Lanes: publication-envelope, realities-commit-1, schema-retirement-refusal, search-deletion, one-lifecycle-review.
- 19:40 Astra review of the one-lifecycle design (`one-lifecycle-astra-review-2026-09-22.md`): revise before commit 2 — `acquire-context!` exists (refactor, not new); `force-branch!` cannot advance an open live connection → accept through the prepared writer; the save-time gate is not installed; three-way comparison is the one missing piece. Design §2/§3 rewritten to the review's 14-row table; commit 2 = acquisition only (option 1, recommended; orchestrator's call under the owner's delegation while away). Opus stays on reports until the alias resolves to 5.5 (checked: `claude-opus-5[1m]`).
- 20:05 `search-deletion` correction landed `62487dbc3` (+`b3b8ccada`): dangling supplier row gone, `rg seon.search` empty. Its scratch boot then failed on a THIRD from-zero refusal of the day: `my.agents.root` lacks the required `:seon.program/definition-digest` — the root seed (`seon.cluster/seed-root-agent!`, `boot.clj:50`) writes declaration rows without the constructor 1.2 routed the digest through. Same class as the other two: a writer bypassing the canonical owner, invisible on a warm JVM. Lane `root-seed-digest` (astra low) fixing at the owner with a scratch boot to HEALTHY as the proof. `default` stays unpopulated until it lands.
- 20:50 From-zero chain, all one class (writers off the canonical path, invisible on a warm JVM): (1) shadowed binding in the rename → fixed `6ec971b07`; (2) config row naming the deleted search supplier → fixed `62487dbc3`; (3) root seed hand-builds its namespace row → routed through the source-reader/constructor path (uncommitted); (4) `program/declaration-row` hashes reader metadata before dropping it (digest instability) and `bootstrap/seed-tx` alters the namespace through a raw map → ownership extended to the lane; (5) the root turn then panics in `extends-schema?` — diagnosis in flight. Scratch boot readiness measured **95,992 ms** from zero: evidence for the plan's ten-second rule and for 1.2b/A2's adoption measurements (boot = publication + index of the whole tree). `default` (pid 28922) remains unpopulated; reset only after a HEALTHY scratch boot.
- 1.2b partial: `60b94954a`, `dcc15e5b3` (+`f8bf669e4`); rows 2/7 held on `fn.clj` (commit 1), row 6 aggregates held on the B4 files (commit 4). `seon.id` closure compile 3,042 ms → per-declaration reload proposal (README §4 wave A; owner pinged).
- 1.3e committed on side branch `codex/schema-retirement-refusal` (`f6b175e6d`) pending `fn.clj`; cherry-pick after commit 1.
- 21:20 **Track 1.3d commit 1 LANDED** `39a337013` (+`3ef739fbc`): a context interprets rows whose definition digest differs from the JVM's loaded commit plus their affected callers (the reverse walk now returns the executable closure as well as the tests; `gate-sets` is its test projection); `evaluate` uses `call-with-custody`; the silent JVM fallback is a named refusal; host-bound by form head refuses an override. Proof: 21 passes with a before-change control; closure 3/4,440 for one changed leaf; acquisition 1,658 ms (flagged for 1.4: projection reconstruction). Carried 1.3e's proven hunks (`f6b175e6d`) in `fn.clj`/`db.clj`/`seon.program.edn`. This is the mechanism by which an agent experiences the edit it just made on its branch.
- 21:45 `root-seed-digest` landed `6cd8d86f5` (+`472e3eaf6`): the root namespace is constructed from its `ns` form through the canonical source path; `declaration-row` digests the stored value (reader metadata dropped first); `bootstrap/seed-tx`'s raw map gone; 13 assertions. Scratch readiness 95,992 / 90,648 ms from zero. Last from-zero blocker named with a 44 ms reproduction: `a86e93e21` kept the legacy five-argument recorder arity, so the root turn's first error reaches `validate-declaration!` with no `:seon.error/declared-schema` and `extends-schema?` panics on nil. `error-facets` resumed to convert every caller and retire the arity — never a nil-tolerant helper. Running: error-facets, publication-envelope (rows 2/7), schema-retirement-refusal (remainder).
- 22:05 **1.3e LANDED** `c94e88a40` (+`094888006`): literal write facts survive a schema retirement; `removed-definition-error` names the writers with one declared member (nested copy removed); side branch deleted. `error-facets` stopped on two findings the ruling already decides — `:seon.turn.loop/phase-failed-error` does not extend `:seon.error/base`; terminal callers lack declaration custody — ruled option 1: fix the declaration, give producers custody of their declared schema, convert the turn call sites for this only; scratch boot to HEALTHY is the proof. Every lane's `bin/test-fast` now refuses recording ("requires a published current-src"): the B4 seam commit 4 replaces; lanes prove in process.
- 22:25 Recorder conversion landed `373820ffd` (+`505aada7e`): every turn recorder call carries its declared error name; the phase error extends base; root's first turn closed and recorded its declared schema on a scratch boot (readiness 101,576 ms). Next and last-known boot failure: publication then fails loading `seon.cluster.source-evidence-test` — a test of the manifest/snapshot machinery 1.2b is deleting (`cfa76f4dd` landed row 2: analyzed file rows published without manifests); that lane's own scratch boot meets it; README §6 question 1 applies (deleted machinery → the test leaves). Then: my scratch boot → reset `default` → base → platform tier.
- 22:45 **1.2b LANDED** (`60b94954a`, `dcc15e5b3`, `cfa76f4dd`, `975af0a60`, +`e945067b4`): manifests gone, inputs captured once, reload boundaries classified, arming identities produced; scratch reset succeeded. Leaf (docstring) publication still **5,527 ms** — the B4-held seams (row 6 aggregates, `test.clj`/`runner.clj`/`fast.clj` readers) remain; compile-cost distribution recorded (CSV). Tree clean; no lanes. Orchestrator scratch boot from zero running as the day's schema-change acceptance; then reset `default`, base, platform tier.
- 23:05 **Orchestrator scratch boot from zero at `e945067b4`: HEALTHY** (`:health :observed`, `:missing-layers []`, readiness **103,142 ms**, one agent). The day's schema changes (digest required, error identity, program keys, retention) are accepted on that proof. Resetting `default` now (RESET batch 1), then the fixture base, the platform tier, and commit 2 (spec at `tmp/orchestrator/realities-commit-2-spec.txt`). Readiness from zero is ~100 s: publication and indexing of the whole tree — the number 1.2b's remaining rows, A2's adoption measurement and the ten-second rule are aimed at.
- 23:20 **`default` RESET from zero at `394b58f09`: HEALTHY** (pid 51528, readiness 111,444 ms, store 146 MB; RESET batch 1 done: digest, error identity, program keys, retention declaration, no-history pending on the fault attributes). Fixture base from HEAD preparing; platform tier next. **Commit 2 launched** (`realities-commit-2`, astra medium; spec `tmp/orchestrator/realities-commit-2-spec.txt`): `:seon.agent/branch`, the existing `acquire-context!` refactored to allocate a branch only on an isolation request, one release that unlinks owned branches, `fork-cluster-ctx` as the one production fork, a branch member on the eval tool, one `my.*` read; six proofs incl. a from-zero boot.
- 23:35 Fixture base published from HEAD `394b58f09` (`483dab0ca…`, 13.9 s) — first success today. **Platform tier: 0 failures, 2 errors** (74 s), both `seon.test.selection-test`: hand-built declaration rows refused for the now-required digest — README §6 question 2, fix the fixture through `program-fn-row`; lane `selection-test-digest` (sol low). Retained run root reaped. Commit 2 running.
- 23:50 `selection-test-digest` landed `a55f0faf5`: selection fixtures built through the canonical declaration constructor; both platform errors pass. Platform tier at this HEAD: 0 failures, 0 errors of the landing class. Lane's `bin/test-fast` still could not record (isolated root without published current-src — the launcher's own base handling, a B4 seam for commit 4); proven in the shared-head run. Only `realities-commit-2` running.

## 2026-09-23 00:20 — bottleneck named: the orchestrator serialized behind commit 2; five lanes now

- Owner: "what is gating parallel progress?" Answer: nothing in the dependency graph — only commit 4 (runner on the handle) and the publish-to-held-candidate half of commit 3 need commit 2. I had serialized after the shared-file tangle. Launched four file-disjoint lanes beside `realities-commit-2`: `indexer-facts` (partition + host-bound body half; `fn.clj` analyzer region, `program.cljc`, resources), `hook-one-request` (1.5 first half; `bin/seon-hook`, `operator.clj` hook helpers), `wrappers-changed-identities` (1.3; `instrument.clj`), `three-way-comparison` (one pure function; `program.cljc` with the shared-file rule vs indexer-facts).
- Remaining structural limits: (a) the test launcher's recording refusal on lane roots (B4 seam, commit 4) — lanes prove in process; (b) hot files: `fn.clj` (analyzer / reverse-walk / manifest regions) and `program.cljc` — region ownership + the shared-file commit rule; (c) scratch boots are ~100 s JVMs outside the two-JVM test slot — rule: at most two lanes whose slice needs a scratch boot run concurrently (indexer-facts, wrappers today); (d) my review bandwidth — astra reviews at design points, Opus reads.
- Next slots as lanes land: A2 c3/c4/c5 (db.clj deletions, sol low), no-history on the fault attributes (error.edn), the write-back spec (Opus research), commit 3's candidate half and commit 4 after commit 2.
- 01:00 Owner: "there are no slot limits; we are only limited by dependencies and resources (same-file edits)". AGENTS.md and README §4 rewritten (`3357133c5`): no lane count, no test-JVM slot, no prober cap, no scratch-boot cap; the loop's opening paragraph and FILL step launch every ready file-disjoint step. Filled: `a2-db-deletions` (c3/c4/c5 + c13 probe, `db.clj` regions), `a2-registry-blob` (c9/c11), `fault-no-history` (two `seon.error.edn` entries, RESET NEEDED) beside `realities-commit-2`, `indexer-facts` (option 1: the analyzer exports clj-kondo's Java-reference facts), `wrappers-changed-identities`, `three-way-comparison`, `hook-one-request` (option 1: the adoption result carries the reloaded namespaces as data). Opus write-back data pack in flight. Unlaunched and why: 1.4 (must run alone, after A2 c2-by-argument and 1.3); commit 3's candidate half and commit 4 (need commit 2); B1 c3 (`fn.clj`, held by indexer-facts); B4 c3 launchers (commit 4).
- 01:30 **Commit 2 LANDED** `1ada78050` (+`4aeaab1d7`): live/isolated handles through `acquire-context!`, `:seon.agent/branch`, release unlinks, `fork-cluster-ctx` the one fork, eval-tool branch member; 24 assertions; scratch readiness 105,826 ms, root's first turn completed — but three errored receipts and an unknown turn ping: lane `commit-2-receipt-errors` bisecting against `008e4ae1e` on scratch roots. RESET NEEDED for `default` (`seon.agent.edn`). **Commit 4 launched** (`realities-commit-4`, astra medium): fixture = branch through the entrance; `seon.test/run` one request on the handle; launchers/slot/workers deleted; eight proofs incl. the reaching-set wall time for leaf and core changes. Owner rulings recorded (`30851360d`): per-declaration reload on Clojure's Var-indirection semantics (no library, nothing invented); hooks re-enable on my judgement once measured. Owner on 1.4: front-load the research so the sweep is mechanical — Opus per-site table in flight (`projection-as-a-read-site-table-2026-09-23.md`), astra review next, then a sol sweep alone.
- Write-back data pack landed (`write-back-data-pack-2026-09-23.md`): a declaration's `:seon.fn/source` is byte-identical to its file span and the file digest matches `:seon.fn.file/digest` (file-base check is free); `seon.edit` is the lossless splice owner with insert-before/after by named anchor; per-file incremental indexing exists (`:seon.fn/changed-paths` → `publish!`, 4,416.8 ms for one non-core file); write-back covers functions and tests (spanned), NOT namespaces (0 spanned) or schema keys (no file, no span); the only ns→path mapper omits the `-`→`_` munge. Smallest closure: a staged-checkout owner, a per-file splice composer, an ns→path deriver, the reanalysis equivalence check — composition otherwise. Feeds commit 6.
- 01:50 `a2-registry-blob` LANDED `24ea81bf5` (+`2aafbb96c`): registry pre-reads deleted (Datahike's typed refusals translated once; exclusivity before `force-branch!` kept), `commit-present?` gone where `commit-as-db` decides, one stream-unwrapping helper in `blob.clj`. `three-way-comparison` done and proven (property test, two-branch regression, 4,436 identities in 15.7 ms), uncommitted behind `indexer-facts`' hunks in `program.cljc`/`program_test.clj` — resumes to commit when that lands. Commit 4 stopped correctly at the entrance boundary (`2cf063b75`): `my.program/supplied-context` lacks the held store and context-state an isolated acquisition needs — an agent-seam change, not a test-side substitute; lane `entrance-supplier` (astra low) carries them through the supplied-context path; commit 4 resumes after it.
- 02:05 1.4 front-loaded per the owner ("so a drunk monkey could do this"): Opus site table `projection-as-a-read-site-table-2026-09-23.md` — the ambient transport is four private dynamic Vars in `seon.schema` (`:892-896`), two binders, three readers; **72 sites in 24 src files: 58 mechanical, 9 contract changes, 5 decisions**; 509 test occurrences of `handed-projection` in 86 files convert to `(db/carried-projection (db/db connection))`; `load-projection` does not exist yet (A1-3 creates it from `projection-from-rows`); decoder-by-argument is one line at `db.clj:1236-1237`/`:1257` with the `vary-meta` stamp applied once at `transact-call`; a probably-dead wrap at `db.clj:1288-1298`. Astra review `projection-sweep-review` writes the sweep spec `lane-projection-as-a-read-sweep.md` (verifies rows, decides the five, contracts for the nine, `load-projection`, commit order, proof, when the tree is clear). Then a sol lane executes it alone in `src/`, with the test conversion as a parallel lane if mechanical.
- 02:40 `commit-2-receipt-errors` (`b635b5c63`): the three errored receipts PREDATE commit 2 (present at parent `3357133c5`) and are the root agent's own first-turn evaluation mistakes — prose with an unmatched delimiter (`:seon.sci.reader/unreadable-error`), a `defn` with a literal `...` body (`:seon.sci.kernel/error`), a bad call shape — recorded as flat values exactly as ruled ("agent mistakes are flat values"). Not a platform defect; no fix. Two things to carry: (a) `bin/seon status`'s `:problem-counts` conflates agent evaluation errors with platform faults — HEALTHY should read platform layers and proc pings, not receipts (issue class `cluster-status-renders-unknown-observations…`, B1's tool); (b) every scratch boot fires root's first turn against the real provider — three paid evaluations per boot; scratch boots should seed root with the virtual provider (B2's virtual replies) — noted for the boot owner, not launched. The "unknown turn ping" coincided with database work during the turn; no defect proven.
- **1.3 wrappers LANDED** `e0577a6fb` (+`1a1ff4dbf`): `instrument/apply!` re-arms only the changed identities (empty set → none; retired → unwrapped; other wrapper identities unchanged); cold complete arming retained; the per-call scan deletion held for its callers (note). `a2-db-deletions` resumed with c3/c4/c13 rulings (diff resource retires with its renderer; a pull budget refusal is its own named error schema, not an elision).
- 03:00 **1.4 sweep spec landed** `402ae9b02` (`lane-projection-as-a-read-sweep.md`, astra): 72 calls / 24 files verified with 13 corrections (the census omitted `call-with-forms`/`registered-schemas` dependencies and two-arg readers; the test census is 511 in 86 files); the five decisions settled from the laws (executors carry their connection/context; effect keeps request/custody binding and drops only the projection binding; instrumentation drops its ambient final arm; admission takes an explicit optional projection member); five commits (loader + constructors + decoder + identity regression; nine contract rows + five decided rows; the 58 mechanical rows scripted; ~560 test occurrences scripted; owner/fallback deletion). One prerequisite it could not authorize blindly: ordered declarations reconstruct the projection mid-transaction and the report stamps db-after with the ENTERING projection — a carried read would lose new declarations. Ruled option 2 (complete the writer producer first): lane `projection-writer-producer` (astra medium) carries the candidate through `row-tx`, publishes the post-write projection only on commit, stamps db-before/after correctly; proof = A then B-referring-to-A in one transaction, abort on an invalid later declaration. The sweep itself launches on sol, alone in `src/`, after that lands and the tree drains; the test conversion as a parallel scripted lane.
- 03:30 **Hook as one request LANDED** `324d41507` (1.5 first half): one synchronous prepl publication request with structured terminal results; queue/worker/result files deleted; 15 tests / 111 assertions; a refused publication preserves the adoption record. Measured per request: **217 ms no-change / 6,257 ms leaf / 24,799 ms core (`seon.id`)**. Hooks stay PAUSED: 6 s per leaf save with several lanes saving would saturate the JVM; the owner delegated re-enable to my judgement "once measured" — the measurement says not yet. Launched `reload-per-declaration` (astra low): the ruled Clojure-semantics reload set (a `defn` edit reloads its own namespace; only macro/protocol/type/inline declarations reload dependents) with the phase breakdown of the leaf request, so the remaining seconds are named. 
- Writer prerequisite of 1.4 re-ruled by the owner ("can't we just memoize the function? use Clojure concepts"): the projection is a memoized function of the database value through `clojure.core.cache` (Datahike's own dependency and schema-cache seam) keyed by `:cache-context`; no writer stamp, no fork change, no single-flight (`2b3f4f728`). `projection-writer-producer` resumed on it. A2 c3 landed `f1e55a824`.
- 04:10 **Audit landed** (`dependency-already-does-it-audit-2026-09-23.md`, Opus): 26 rows — 7 DELETE / 9 REPLACE / 10 KEEP, ≈366 lines + the `:seon.operator.lock/*` schema family; the four shapes: a value stamped onto db metadata and re-stamped on derived values (ten `vary-meta` sites in seven namespaces), a hand cache beside `core.cache.wrapped` that Datahike already requires, a `ReentrantLock` beside the writer's expected-head guard, identity computed by hand (`basis-t`/`max-tx`, SHA-256 of a gitlink). Nine rows already scheduled; eleven NEW → README §4 row 1.3f (`6b627304f`). Launched `publication-lock-deletion` (rows 11+16, astra low) and `oversight-owning-instance` (row 10, sol low); rows 2/9 ride the memoized-projection slice, row 17 rides commit 4, rows 7/8/15 wait for `schema.clj`/render files. AGENTS.md "No stamps" (`73a1f728d`). A2 c13 landed `b6fe3ffa1` (proven missing-query refusal delegated to Datahike). `reload-per-declaration` ruled: narrow on existing facts now, unknown widens, persist `:inline?`/constant `def` facts after `indexer-facts` frees the analyzer. Running: a2-db-deletions, entrance-supplier, indexer-facts, projection-writer-producer, reload-per-declaration, publication-lock-deletion, oversight-owning-instance.
