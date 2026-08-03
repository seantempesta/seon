---
type: issue
status: open
severity: friction
tags: [issue, database, testing]
---

# `:keep-history? false` is not a creation-seam toggle

## Problem

Ruling #40 made `keep-history` a per-cluster dial so scratch and eval
clusters could run history-off for store economy. Implementing it for
the 2026-08-02 eval run found the dial is not sufficient: **live
`d/history` calls prevent booting a non-temporal database**, so a
history-off store fails where those readers run. The run therefore
used history-on and paid the full store cost
([[eval-samples-cost-42mb-of-store-each]]).

There are two independent mechanism gaps:

1. **Reader gap.** `seon.reconcile/plan` unconditionally calls `d/history` to
   recover each identity's first assertion transaction
   (`src/seon/reconcile.cljc:115-134,304-354`). Config boot calls that plan
   through `seon.config/apply!` (`src/seon/config.clj:239-254`), so Datahike's
   explicit "history is only allowed on temporal indexed databases" refusal
   (`reference-code/datahike/src/datahike/api/impl.cljc:185-194`) blocks boot.
2. **Creation/branch gap.** `:keep-history?` controls whether a database value
   has temporal EAVT/AEVT/AVET roots
   (`reference-code/datahike/src/datahike/db.cljc:897-957`). Seon's clusters
   are branches copied from one published commit; Datahike `branch!` copies the
   stored database record and changes only `[:config :branch]`
   (`reference-code/datahike/src/datahike/versioning.cljc:268-289`). Therefore
   every branch inherits the ancestor's temporal representation. Passing a
   different connection config cannot turn one copied branch history-off.

The complete first-party production reader census is small:

- `src/seon/reconcile.cljc:115-134` — unguarded and boot-critical, described
  above;
- `src/seon/schema.clj:528-593` — catches absence and deliberately fails closed
  to agent-authored admission provenance;
- `src/seon/cluster/registry.clj:300-316` — catches absence and marks only
  current blob digests, which is correct when old datoms were not retained; and
- `src/seon/cluster/store.clj:119-128` — a Malli generator over fresh private
  memory databases, not a read of the cluster being booted.

There are no other production `d/as-of` or `d/since` call sites. Their matches
are tests and the same database-value generator.

The retained measurement script
`docs/prds/sci-execution-runtime/research/scripts/store-options-before-after-2026-08-02.clj`
proves the Datahike representation itself works history-off and quantifies the
economy: at the same 3,600-datom fused/diff workload, 99 objects / 709,478 bytes
became 60 objects / 362,844 bytes (48.9 % fewer bytes). The blocker is Seon's
boot and branch semantics, not Datahike's ability to create a non-temporal
database.

## Mechanism options

1. **Isolated history-off operator root for scratch/eval (recommended near-term).**
   Thread the creation decision into ancestor publication for that private root,
   and make the reconciliation provenance reader state its honest non-temporal
   fallback. Guarantee: every branch in that physical store has one consistent
   representation and eval storage falls roughly in half in this probe. Cost:
   the root owns its own published ancestor/store and cannot fork a history-on
   commit from the shared root. This matches the existing requirement that eval
   writes use private operator roots, but is root-scoped rather than a mixed
   policy inside one store.
2. **Maintained Datahike branch-conversion commit.** Add a branch operation that
   constructs a new commit from the source's current indices while omitting all
   temporal roots and recording `:keep-history? false`. Guarantee: true
   per-branch policy in one physical store. Cost/risk: permanent fork semantics,
   a new commit identity/ancestry rule, GC and reopen proof, and no existing
   implementation. Merely editing the copied record would leave the source
   commit id naming different content and is inadmissible.
3. **Keep the shared store history-on.** Continue selective
   `:seon.db/no-history?` attributes and cutoff GC. Guarantee: existing branch,
   provenance, and debug behavior stays intact. Cost: does not deliver ruling
   #40's eval/scratch temporal-index economy.

The isolated-root path is the only current mechanism that avoids a fork change.
It still requires a deliberate provenance fallback before a Seon boot is
possible; do not expose `:keep-history? false` as a success-shaped cluster dial
until that live boot is proven.

## Acceptance

Find every `d/history` (and `as-of`/`since`) reader in first-party
source and decide, per reader, whether it is essential to a
history-off cluster: readers that exist for debug archaeology should
degrade honestly (state that history is absent) rather than refuse the
boot; readers genuinely required by grading or restore stay, and their
requirement is the argument for keeping history on wherever they run.
Then the dial works as ruled: a scratch/eval cluster boots history-off
and reports what it gives up. A regression boots one cluster each way
and proves both serve their intended readers.

## Current disposition 2026-08-02

**Storage benefit is proven; a same-store per-cluster toggle is theory.** No
production edit belongs in the store-creation seam alone. The shortest safe
delivery is a private history-off operator root plus the reconciliation reader
fix and a real boot proof. A mixed history-on/history-off shared store requires
the explicit Datahike conversion design above and must not be improvised in
Seon's branch wrapper.

## Night implementation evidence

The pinned-fork and physical-replay result is now recorded in
[[per-cluster-history-2026-08-02]]. Datahike's record is branch-local, but every
supported branch/fork operation inherits it; there is no history-representation
conversion. Commit `918d33623` lands creation, persisted-policy reopen,
mismatch refusal, descendant-branch inheritance, and the non-temporal
reconciliation fallback. Commit `6ce45b4eb` exposes application of the same
already-compiled config value at the post-connection seam.

The real archived-episode replay measured 10,513,866 B history-on growth versus
6,533,688 B history-off growth: **3,980,178 B / 37.856% saved** with identical
current domain datoms. An isolated history-off root published `current-src` and
opened the cluster branch. READY proof is presently blocked by the protected
schema lane's unrelated `:seon.ai/usage` agent-contract `:any`; this issue stays
open until the exact protected boot/config/database-view handoff in the research
report lands and both live shapes complete.

## Authorized integration attempt 2026-08-03

The six-part integration reached the protected live boundary:

- `:seon.config.db/keep-history?` is declared beside the other config leaves
  and the shipped default explicitly retains history
  (`resources/seon/schema.edn:592-593`, `config/default.edn:1-4`).
- `start!` compiles the selected manifest once before the tower, passes the
  effective boolean into process-root store acquisition, applies the same
  compiled value after the branch opens, and refuses a conflicting policy on
  an already-held store (`src/seon/cluster.clj:220-253,1301-1347,1420-1430`).
- The one temporal database-view owner refuses a non-temporal database with
  `:seon.db/non-temporal-database` before Datahike is called
  (`src/seon/db.clj:629-650`). Schema admission and blob collection now use
  that owner instead of catching direct `d/history` exceptions
  (`src/seon/schema.clj:528-535`,
  `src/seon/cluster/registry.clj:301-319`).
- The config roster gate passed 11 tests / 48 assertions. The database, store,
  schema-admission, and registry gates passed every production assertion after
  one overstrong synthetic expectation was removed; the corrected admission
  focus passed 7 tests / 32 assertions. The boot plus config-application gate
  ran 31 tests / 142 assertions with one error at the live history-off boundary.

The remaining blocker is reproduced, not inferred. A real history-off tower
opens the non-temporal main and cluster branches and applies its config row,
then `cluster-ctx` refuses `:seon.ai/usage` because its shipped declaration is
still `[:map-of :string :any]` (`resources/seon/schema.edn:172`). Without
temporal first-assertion history, schema admission deliberately classifies the
row as agent-authored (`src/seon/schema.clj:528-535`), and the protected
contract gate rejects `:any` for agent-authored schemas
(`src/seon/schema/internal.cljc:59-89`). This is the same blocker previously
reported as cleared; the history-off boot falsifier shows it remains live on
the non-temporal path.

Exact remainder: settle the non-temporal source-row admission evidence at its
protected owner without weakening agent-authored contracts, then rerun the
history-off READY → real DeepSeek turn → rendered context → flat temporal
error proof and the paired history-on `history` / `as-of` / `since` proof.
Until then the issue remains open and no live-proof acceptance is claimed.
