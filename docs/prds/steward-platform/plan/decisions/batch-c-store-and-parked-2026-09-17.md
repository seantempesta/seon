---
type: decision
status: open (owner decision 3 + decision 10's parked items)
created: 2026-09-17
tags: [decision, steward, store, gc, datahike, konserve, vocabulary]
---

# Batch C — store reclamation, the parked identity items, and vocabulary

Read-only research lane on `steward-platform`. Three `mcp__seon__eval_clj`
jvm-mode probes against `default` (pid 53320, wall clock `2026-09-16T16:49:37Z`
in the JVM's own log line), plus shell measurement of `data/store`. No
transaction, no collection, no `bin/seon` state change, no test JVM.

Owner's standing instruction for this page, verbatim: *"tied directly to the
optimal interfaces like reading internal datahike state to make better
decisions not creating another abstract and duplicated index"*. Every signal
proposed below is a number Datahike or konserve already computes for its own
purposes.

---

## Part 1 — Store growth and reclamation

### 1.0 Background facts, verified before repeating

| claim | verified at | verdict |
|---|---|---|
| 102 MB → 5.8 GB in ~8 h under gates and lanes | `unsettled.md:804` (reset #6, store 101 MB, 18:10Z) → `:851` (5.4 GB, 01:10Z) → `:859` (RESET #7, "store 110 MB (from 5.8 GB)", 01:55Z) | **confirmed** |
| a write storm took it to 21 GB in ~20 min | `unsettled.md:770` — "default's store 2.5 → 21 GB in ~20 min with near-zero commits"; cause = the Datahike writer throwing continuously in `seon.turn/row-tx` inside the turn loop's `[:db.fn/call retain-transaction]` | **confirmed**, and it is *not* ordinary growth |
| growth is uncollected copy-on-write churn, not commit volume | `store-footprint-2026-09-16.md` "Composition": 3,000-file uniform sample → **2,800 `pss/leaf` (93.3%)**; and `write-latency-vs-store-size-2026-09-17.md` "Cause": commit is O(delta), not O(store) | **confirmed** |
| recording delta fix: 157,981 → 0 datoms per unchanged re-record | `unsettled.md:805` (`f272b9e6e`), proven cold at `:817` — batch 79 platform GREEN on the same HEAD with store 1.1 → 1.1 GB | **confirmed** |
| eight resets today, each < 2 min | reset #5 `:770`, #6 `:804`, #7 `:859`, #8 `:884`; wipe-forced refork #4 `:747`. The < 2 min figure is the `bin/seon reset --force` step only — reset #7 at `:859` is followed by Juniper reseed and `init --dev`, and adoption converged at `:860`, ~10 min later | **confirmed for the reset; the usable boundary is ~10 min, not 2** |

Two earlier notes disagree, and the dependency settles it — see §1.2.

### 1.1 (i) What internal state already tells us how much is reclaimable

Three tiers, cheapest first. Nothing here is a Seon counter.

**Tier 1 — free, a single directory stream.**
`konserve.filestore/count-konserve-keys`
(`reference-code/konserve/src/konserve/filestore.clj:221-227`) counts entries
ending `.ksv` via `list-files` (`:210-219`), one `Files/newDirectoryStream`, no
header reads. konserve already calls it on every `store-exists?`
(`:230-239`). This is the **total object count**.

Byte total on the same walk: `physical-filestore-inventory`
(`src/seon/cluster/registry.clj`, the `Files/newDirectoryStream` reduce feeding
`:files`/`:bytes`/`:candidate-files`/`:candidate-bytes`) — Seon's, but it is a
`stat` loop over the same directory, not an index.

**Tier 2 — O(live datoms), in memory.**
`datahike.db/metrics` (`reference-code/datahike/src/datahike/db.cljc:1009-1032`)
returns `:count` from `di/-count` on eavt plus per-attribute and per-entity
counts by walking `di/-seq`. Exposed as `datahike.api/metrics`
(`reference-code/datahike/src/datahike/api/specification.cljc:1200-1210`).
This is the **live datom population**, from which live leaf count is arithmetic:
`datoms / branching-factor` per index, six indexes with `keep-history? true`.

**Tier 3 — exact, O(reachable nodes).**
The mark inside `gc-storage!` is itself the answer. `reachable-in-branch`
(`reference-code/datahike/src/datahike/gc.cljc:22-81`) walks each roster branch
and `-mark`s eavt/aevt/avet (and the three temporal indexes when
`:keep-history?`, `:66-69`); the union across branches is `native-reachable`
(`:146-150`). `reachable` minus the store's keys is exactly the garbage.

Seon already harvests that number without deleting anything: `dry-run!`
(`src/seon/cluster/registry.clj`, the `:datahike.gc/batch-issued` callback that
snapshots `physical-filestore-inventory` and then throws at the delete barrier)
returns `:seon.cluster.registry/retained-files`,
`:seon.cluster.registry/file-bytes`, `:seon.cluster.registry/candidate-files`,
`:seon.cluster.registry/candidate-bytes`,
`:seon.cluster.registry/mark-duration-ms` and
`:seon.cluster.registry/branches`. **This is the reclaimable figure, computed by
the dependency's own mark, with its own cost already measured.**

**The commit chain.** `datahike.api/commit-id` and
`datahike.api/parent-commit-ids` (`reference-code/datahike/src/datahike/api/impl.cljc:371-375`)
read the commit graph directly; `gc.cljc:24` and `:37` read
`[branch :meta :datahike/commit-id]` and `:datahike/parents` off the branch
record. Measured live (probe 3):

```
cluster-default  6aaac88f-f119-5768-bfbb-4e3d5c38da17  parent 6aaac88a-796a-555d-a959-1b0bd3dd47ee
current-src      6aaac88d-91bc-56c9-a7aa-482eaf8a188f  parent 6aaac84f-56cc-53f0-b22c-8f9321d38542
db               6aaac541-4121-5e0a-ad44-088ffd3ce73d  (no parents — genesis)
```

**The roster is live state, and it moves.** Probe 2 found FOUR branches:
`:db` (0 datoms), `:current-src` (545,650), `:cluster-default` (441,662), and
`:building-source-53320-1789576625367-18bde079-1544-4075-9811-f8ee6d2b71d2`
(**430,846 datoms**). By probe 3, minutes later, that fourth branch was gone —
it is the publication scratch branch `seon.cluster.source` creates per complete
refresh, and it was in flight. Consequence for any collector: **a mark that runs
during a publication roots a 430k-datom scratch branch's entire ancestry**, and
the signal must not read that as "nothing is reclaimable".

### 1.2 (ii) What `gc-storage!` guarantees, and the correction

Guarantees, from the fork's own docstring
(`reference-code/datahike/src/datahike/gc.cljc:83-117`):

1. Every branch in the roster is whitelisted and **its head is always retained**
   (`:84-87`).
2. Snapshots on those branches **before `remove-before`** are erased; the
   default is `Date. 0`, i.e. *no erasure* (`:118`).
3. The sweep deletes an unreachable object only if it was written before the
   store's **safe point**, not before `now` (`:89-106`). The safe point is `now`
   when nothing is in flight and the start of the oldest in-flight
   values-then-pointer sequence otherwise
   (`reference-code/datahike/src/datahike/gc_guard.cljc:1-44`). This is what
   makes concurrent collection correct, and it is automatic: `cutoff` is
   computed at `gc.cljc:136-137`, never supplied by the caller.
4. `remove-before` is **mark-side policy** (how much history stays reachable —
   collect MORE); the safe point is **sweep-side** (collect LESS). The docstring
   says so explicitly at `:103-106`.
5. Reachability can be extended by the caller:
   `:datahike.gc/reachable-extension` (`:151-161`), which is how Seon keeps its
   content-addressed blobs alive.

**Cost model.** The mark is a full walk of every reachable node on every roster
branch (`gc.cljc:146-150`; `start-background-gc!`'s docstring states it at
`:179-181`: "Its cost is a walk of all reachable nodes per cycle"). The sweep is
`konserve.gc/sweep!` (`reference-code/konserve/src/konserve/gc.cljc:8-51`): one
`k/keys` over the whole store (`:22`), a filter on `whitelist` membership and
`last-write` vs the cutoff (`:23-30`), then batched `multi-dissoc` or per-key
`dissoc` (`:36-49`). So: **O(reachable nodes) read + O(total keys) metadata read
+ O(garbage) deletes.** Seon already reports the mark half as
`:seon.cluster.registry/mark-duration-ms`.

**CORRECTION — the two existing notes disagree and the source settles it.**
`store-footprint-2026-09-16.md` ("What a reset or GC would reclaim") says
*"`gc-storage!` whitelists the branch roster and marks from each head
(`gc.cljc:22-77`), so every superseded leaf qualifies"* and concludes ~67 GB is
collectable by a plain call. **That is wrong.** `reachable-in-branch` does not
stop at the head: it computes
`in-range? (> (get-time (or updated-at created-at)) (get-time after-date))`
(`gc.cljc:41-42`) and recurs into `parents` whenever `in-range?`
(`gc.cljc:72`). With the default `remove-before` = `Date. 0`, `in-range?` is
true for every commit, so the walk marks **the complete ancestry of every
branch** — and a superseded leaf is reachable from the commit that superseded
it. The plain form therefore reclaims only objects outside every history:
deleted-branch debris and torn-write orphans.

`storage-gc-runs-without-a-cutoff-so-it-reclaims-almost-nothing.md` has this
right, with a retained reproduction
(`docs/prds/sci-execution-runtime/research/scripts/store-options-before-after-2026-08-02.clj`):
plain `gc-storage` swept **0** objects (90 → 90); a cutoff at measurement time
swept **86** (90 → 4, 7,847,585 → 372,335 bytes, 95.3%).

**So `remove-before` is the load-bearing decision, not an optimisation.** That
is precisely "the signal the reclamation keys on".

Corroborating live number: the peer's accidental real collection with a 24 h
cutoff (`write-latency-vs-store-size-2026-09-17.md`, "Reclamation is wired")
took `data/store` from **107,072 keys / 12,043 MB to 93,202 keys / 10,301 MB**
and was still running — ≥ 1.7 GB / 13,900 keys, *with* a cutoff.

### 1.3 (iii) Can a live, multi-branch store be swept safely, and what `before` is safe

**Concurrency: yes, and this is the dependency's design, not a risk we take.**
The safe point (`gc_guard.cljc:1-44`) exists exactly for the window in which a
commit's values are on disk and the head has not yet moved. `commit!` and
`datahike.versioning/branch!` both take the guard (`gc_guard.cljc:19-22`), so
the in-flight publication scratch branch §1.1 caught is covered. The one
precondition is stated at `gc.cljc:108-117`: **run it where the writers are.**
Seon satisfies this: the live config reports `:writer :self` (probe 2) and
`seon.cluster.registry/collect!`'s docstring already records it — *"it runs
where the writers are, which is this JVM (`gc.cljc:105-115`)"*. `gc-storage!`
logs `:datahike/gc-without-local-writer` when that is false
(`gc.cljc:139-142`).

**Multiple branches: yes.** The mark is a union over the roster
(`gc.cljc:144-150`). `start-background-gc!`'s docstring says it explicitly
(`:177-178`): *"it works with MULTIPLE BRANCHES"*. The single-branch restriction
belongs to the **other** collector — `datahike.online-gc`, the freed-address
tracker (`gc.cljc:176-181`: "prefer the online GC's freed-tracking for
single-branch bulk imports"). The line in
`shared-store-grows-during-render-source-work.md` — *"Datahike's online GC …
is safe only for a single-branch database"* — is true of `online-gc` and has
been read as if it applied to `gc-storage!`. It does not.

**What `before` instant is safe.** `remove-before` risks exactly one thing: a
commit id that something will later *name*. Two namers exist in this tree:

- `datahike.api/commit-as-db` (`impl.cljc:379-384`);
- Seon's cluster fork, `d/force-branch! source-db branch #{source-commit}` in
  `src/seon/cluster/registry.clj` (the `::source-absent` refusal immediately
  above it proves the requirement: *"the source commit … is unavailable"*).

Branch heads are retained unconditionally (`gc.cljc:86-87`), so the only
exposure is a *non-head* commit named by a stored fact — the published
`:seon.source/commit-id` a new cluster forks from.

**Therefore the safe `remove-before` is DERIVED, not tuned:** the creation
instant of the oldest commit id that a live fact still names. Concretely, the
minimum over (a) the published source artifact's `:seon.source/commit-id` and
(b) each cluster row's recorded source commit, resolved to an instant through
the commit record's `:datahike/created-at` (`gc.cljc:38-39`). Absent any such
fact the answer is the branch heads alone, which are already safe. This obeys
"derive state; do not remember it" and the acceptance criterion already written
into the GC issue: *"The cutoff is derived from a stated retention position …
not a tuned constant."*

### 1.4 (iv) The actual store layout, measured now

Shell, `/Users/sean/src/seon/data/store`, 2026-09-16:

```
du -sh data/store                                    305M   (301M ~15 min earlier)
ls -1 data/store | wc -l                            1,881
find data/store -maxdepth 1 -name '*.ksv' | wc -l   1,881   (every entry is a .ksv)
find data/store -maxdepth 1 -type d                  data/store only — ZERO subdirectories
```

Size distribution over all 1,881 files (`stat -f '%z'`):

```
n = 1,881   total 0.29 GB   avg 164.0 KB
<10K  11      10-100K 1,063      100K-1M 773      >1M 34
```

A **single flat directory**; konserve fsyncs that base directory once per blob
(`reference-code/konserve/src/konserve/filestore.clj`, `sync-base`), and the
peer's APFS probe established that fsync does not get more expensive as the
directory grows (`write-latency-vs-store-size-2026-09-17.md`).

Through the connection (probes 2 and 3, `default` pid 53320 = reset #8's pid,
`unsettled.md:884`):

```
konserve keys        3,359  →  3,497   (two probes minutes apart)
branch roster        :db, :current-src, :cluster-default,
                     :building-source-53320-…  (in flight; gone by probe 3)
datoms               db 0 | current-src 545,650 | cluster-default 441,662
                     | building-source 430,846
max-tx               cluster-default 536,871,005
index                datahike.index/persistent-set
branching-factor     4096
keep-history?        true          writer :self
store-id             224560ae-b0ba-3d5c-b6db-6d7415ad66e7
```

**Live growth rate, measured across instruments that count the same predicate**
(`.ksv` in the flat dir — `count-konserve-keys` `filestore.clj:225` vs my
`find`): 1,881 → 3,359 → 3,497 over roughly 15 minutes ≈ **100 keys/min ≈ 16
MB/min ≈ 1 GB/h** at the measured 164 KB average, under gates plus lanes.
Consistent with the ledger's independent readings — ~1.4 GB/h at
`unsettled.md:744` and ~0.5 GB/h with one gate and one lane at `:755`.

Amplification, arithmetic from the measured config: at branching-factor 4096 one
changed datom rewrites a whole leaf, across six indexes with `keep-history?
true`. `unsettled.md:805` states the empirical conversion: *"store cost tracks
datom count ×3–10 KB index amplification."*

### 1.5 Options for the SIGNAL that triggers reclamation

Every option keys on a number the dependency already computes. None adds a
counter, a cache, or an index.

**Option A (recommended) — the unreachable-key ratio, with the denominator
supplied by the previous collection itself.**

- *Numerator:* `konserve.filestore/count-konserve-keys`
  (`filestore.clj:221-227`) — one directory stream, already konserve's own
  health probe.
- *Denominator:* `:seon.cluster.registry/retained-files`, which
  `physical-filestore-inventory` already computes and `dry-run!` already
  returns. **One change makes it available: the real collection path returns the
  same inventory map the dry run returns instead of `(count swept)`** — today
  `collect!` discards it (`src/seon/cluster/registry.clj`, the non-dry-run arm:
  `(count @(d/gc-storage …))`). That is deleting a projection, not adding a
  mechanism.
- *Where it lives:* the maintenance-facts owner landed last night —
  `seon.maintenance/last-collection` derives "last collected / reclaimed"
  with a typed never-collected answer (`unsettled.md:772`, `67487fa4d`
  `2f1a416a9`). The denominator has a home already.
- *Trigger:* `root/maintenance/footprint` (`src/seon/schedule.clj:46-50`,
  `"0 2 * * *"`) already samples the footprint and does nothing with it. Give it
  the comparison: when `count-konserve-keys` exceeds the last collection's
  retained-file count by a declared multiple, call `seon.operator/collect!` with
  the derived `remove-before` of §1.3. Keep `root/maintenance/compact`
  (`schedule.clj:66-70`, `"0 3 * * 0"`) as the floor.
- *Absence behaviour* (AGENTS.md's recurring failure class): no prior collection
  means **no denominator**, and the honest report is "never collected" — which
  `seon.maintenance/last-collection` already types — whose correct response is
  to run one collection to establish it. Silence is never "fine".
- **Guarantee:** the store never exceeds a declared multiple of its own last
  measured live size. Both numbers are the dependency's.
- **Cost:** the mark is O(reachable nodes) and holds the reachability permit;
  at the measured 1.4 M live datoms across three branches this is thousands of
  leaves, and the duration is already reported as
  `:seon.cluster.registry/mark-duration-ms`, so the bound is observable from
  the first run. One condition on an existing schedule row, one declared dial,
  one return-value widening.
- **What we give up:** nothing measured. The ratio is a *lagging* signal — it
  cannot catch a 21 GB/20 min write storm, and it should not try to: that class
  is already closed at its own seam by the write-refusal bound
  (`unsettled.md:770`, `:784`, `f86ec57ed`).

**Option B — `start-background-gc!` behind Seon's one collector.**
`reference-code/datahike/src/datahike/gc.cljc:171-215`: a `go-loop` with
`:interval-ms` (default 300000) and `:history-window-ms`, explicitly safe
concurrently with the writer (`:183-187`). It must **not** be called directly:
`seon.cluster.registry/collect!` extends reachability with schema-discovered
blob digests (`blob-digest-attributes` / `branch-blobs` /
`referenced-blobs`), and bypassing it can sweep live Seon blobs — the
constraint the GC issue already records.

- **Guarantee:** garbage never accumulates for longer than one interval.
- **Cost:** a full reachable-node walk every cycle regardless of whether
  anything changed — the exact "a check that costs more than it saves" shape —
  and `:history-window-ms` is a **duration constant**, i.e. the tuned number
  §2.3 forbids, where Option A's cutoff is derived from named commits.
- **What we give up:** the derived cutoff, and the ability to say what event the
  collection responded to.

**Option C — reset-as-reclamation; keep today's behaviour.**
`bin/seon reset --force` plus Juniper reseed plus `init --dev` (AGENTS.md §6).
Measured: reset #7 took the store 5.8 GB → 110 MB (`unsettled.md:859`) and
adoption converged at `:860`; reset #8 → 102 MB (`:884`) with adoption converged
at `:885`.

- **Guarantee:** the store returns to its post-publication floor exactly, every
  time; zero new code.
- **Cost:** a quiet boundary the whole session must wait for, ~10 min end to end
  (not the 2 min the reset step alone takes), and it happened **eight times
  today**. It also destroys every agent fact, so the day's turns are not a
  durable record.
- **What we give up:** the store as a durable substrate across a working day —
  which is the thing the platform is supposed to be.

### 1.6 Recommendation

**Option A, with Option C retained as the manual escape and the weekly cron as
the floor.**

The reasoning is the owner's own instruction. konserve already counts its keys
for `store-exists?`; Datahike's mark already computes exactly what is
reachable; Seon's collector already turns that mark into a retained/candidate
file inventory and already has a maintenance-fact owner to store it in. The only
work is to **stop throwing that inventory away** and to point an existing
schedule row at the comparison. No new counter, no mirror, no second authority.

Two things must land with it, both grounded above:

1. **The cutoff is derived**, per §1.3 — the oldest commit instant any live fact
   names. A `Date.`-now cutoff is valid only for a private benchmark, as the GC
   issue's disposition already states.
2. **The two stale claims get corrected** in the same beat: the "marks from each
   head" verdict in `store-footprint-2026-09-16.md` (refuted by `gc.cljc:41-42`
   and `:72`) and the "safe only for a single-branch database" line in
   `shared-store-grows-during-render-source-work.md` (that is `online-gc`, not
   `gc-storage!`, per `gc.cljc:176-181`). Both currently read as *absence of a
   problem* where there is one.

**First measurement to take, before any code:** one `collect!` dry run on
`default` — `{:seon.operator.collect/dry-run? true}` — which deletes nothing and
returns `candidate-files`, `candidate-bytes`, `retained-files` and
`mark-duration-ms`. That single call establishes the live ratio, the reclaimable
bytes, and the mark's real cost at 3,500 keys, and it is the honest
denominator for the declared multiple. It was deliberately not run here
(read-only mandate; it takes the store's reachability permit).

**One defect to file regardless of the decision** (found in the peer's note, not
by me): `gc-storage` silently ignores unknown option keys, so a caller passing
`{:dry-run? true}` — not in the `:datahike.gc/*` family (`gc.cljc:152-164`) —
gets a **real collection**. That already happened once on `default`
(`write-latency-vs-store-size-2026-09-17.md`). A maintenance entry point that
reads an unrecognised safety flag as consent is the absence-of-signal class.

---

## Part 2 — Decision 10's parked items

### (a) R1 — identity strings → symbols

**Today.** `resources/seon/schemas/seon.fn.edn:150-153` declares:

```clojure
:sym [:string {:min 1, :seon.db/identity true, :seon.search/index :symbol,
               :seon.program/row-schema :seon.fn/fn,
               :seon.program/source-attribute :seon.fn/source …}]
```

The schema **already says the value is a symbol** (`:seon.search/index :symbol`)
while storing a string. That tag is a hand-maintained mirror of the value's real
type — derive-or-die's target.

**The cost, in real code.** `src/seon/fn.clj` constructs the correct symbol and
immediately throws it away, three times in fifteen lines:

```clojure
;; src/seon/fn.clj:325-326
(str (symbol (str (::analyzer/to usage)) (str (::analyzer/name usage))))
```

Same shape at `src/seon/fn.clj:318-319`, `:331-332`, `:374`, `:777`, and
`src/seon/program.cljc:1080`. Every Datalog query or lookup ref that starts from
a real symbol must `str` it first; AGENTS.md §2.2's own example passes a string
(`(seon.fn/tests-reaching (seon.db/db) "seon.turn/open-tx")`).

**What the dependency offers.** `:db.type/symbol` is a first-class Datahike
value type (`reference-code/datahike/src/datahike/schema.cljc:31`, member of
`:db.type/value` at `:48`). Seon's bridge **already maps to it**:
`src/seon/schema/datahike.clj:66-67` maps `:symbol` and `:qualified-symbol` →
`:db.type/symbol`, and `:112` infers it from a symbol literal. It is not
speculative in this tree either: `resources/seon/schemas/seon.fn.binding.edn:3`
already declares `:symbol :symbol`.

**Recommendation: switch, at a refork boundary, keeping the key name.** The
key's *meaning* ("this declaration's qualified name") does not change, only its
representation, and database data is disposable by ruling — the refork that
carries it is already happening eight times a day. Delete the `(str (symbol …))`
sites and the `:seon.search/index :symbol` mirror in the same commit.

**The one thing to probe first, unproven here:** `:db.type/symbol` combined with
`:db.unique/identity`. `seon.fn.binding/symbol` is a precedent for the *type*,
not for the *unique identity*, and the AVET index must order symbols in
`persistent_set`. One live probe — install a symbol-valued identity attribute on
a scratch cluster and resolve a lookup ref — settles it. Do not commit the
schema change before that probe.

### (b) Call-arities: tuple vs interned family

**Today.** `resources/seon/schemas/seon.fn.edn:37-48`:
`:call-arities [:set … [:tuple [:string {:min 1}] [:int {:min 0}]]]`, whose
docstring (`:41-47`) records the probed constraint verbatim:

> the callee travels as its identity value rather than as a ref because Datahike
> stores tuple members verbatim — it resolves neither a lookup ref nor a tempid
> inside a tuple (probed 2026-09-16), so a `[ref long]` tuple would store an
> unresolvable vector.

**What the dependency offers.** `:db/tupleType`, `:db/tupleTypes` and
`:db/tupleAttrs` are recognised schema attributes
(`reference-code/datahike/src/datahike/schema.cljc:65`, `:78`, `:167-171`,
`:185`); `:db.type/tuple` is `vector?` (`:33`). `:db/tupleTypes` takes value
types, so the first member can become `:db.type/symbol` for free once (a) lands
— **(a) and (b) compose; they are not alternatives.**

**Recommendation: keep the tuple.** Three grounded reasons.

1. The interned alternative is refuted by measurement, not taste. One entity per
   (caller, callee, arity) is ~3 datoms where the tuple is 1, and
   `unsettled.md:805` gives the conversion: *"store cost tracks datom count
   ×3–10 KB index amplification"* at branching-factor 4096 (measured live,
   §1.4). Part 1's entire problem is datom count. An interned family multiplies
   the hot edge set by three on the storage axis we are trying to shrink.
2. The join cannot dangle. AGENTS.md's POPULATION INVARIANT — *"every name the
   SCI context can resolve has a program row"* — plus the docstring's own claim
   that this set's population *is* the `:seon.fn/calls` edge set (`:46-47`)
   makes the string-to-identity join total by construction. A ref would buy
   referential integrity that is already guaranteed elsewhere.
3. §2.5: an interned family is a second mechanism for a relationship
   `:seon.fn/calls` already owns; `:call-arities` explicitly only *refines* it.

### (c) A `seon.commit` entity vs Datahike's own commit log

**The proposal.** `docs/prds/steward-platform/research/complex-issues-as-schema-spec-2026-09-16.md:304`
and `:457` — a *"`seon.commit` entity keyed by the full 40-hex sha at index time
(one `git rev-parse`)"* carrying `abbrev`, `at`, `subject`, replacing "§1.8 16
commits as strings" (`:383`).

**Argue from the dependency — and the first move is to refuse the framing.**
These are **git** commits. Datahike's `:datahike/commit-id` is a UUID naming a
*database value* — measured live in §1.1: `cluster-default`
`6aaac88f-f119-5768-bfbb-4e3d5c38da17` with parent
`6aaac88a-796a-555d-a959-1b0bd3dd47ee`, read through `datahike.api/commit-id`
and `parent-commit-ids` (`impl.cljc:371-375`). Datahike's commit log knows
nothing about git shas, so it is **not a substitute** and the item is not
actually a choice between two mechanisms.

What the dependency *does* answer is the shape question. Datahike models "an
immutable identified point with parents" as a **record in the store**, read at
`[branch :meta :datahike/commit-id]` with `:datahike/parents`
(`gc.cljc:24`, `:37`) — not as an entity in the database, and carrying nothing
derivable.

**Recommendation: do not mint the entity for metadata.** `abbrev`, `at` and
`subject` are all `git show` output; an entity holding them is a cache of git,
which is derive-or-die's named target ("a hand-maintained mirror of derivable
state … stale within a day"). Store the **full sha as a value** on whatever
references it (the issue finding, the publication — Seon already does exactly
this with `:seon.source/commit-id`) and derive the rest from git at render time.
Sixteen commits as strings is not the "convoluted reconstruction" §2.2 says to
fix.

**When to revisit:** the moment a query needs to *join on* commits (e.g. "which
issues cite commits touching this namespace"). Then declare the entity keyed by
the full sha, with git remaining the authority for its attributes, and let the
join be the justification — not the abbreviation.

### (d) Retention removal

**Measured, not assumed.** Grepping `retention` across `config/*.edn` and
`resources/seon/schemas/` returns exactly one hit:
`resources/seon/schemas/seon.db.edn:2` — `:append-only-after`, *"Schema property
naming the assignment attribute whose history activates nonempty membership
retention at database admission"*. That is membership retention at write
admission, **not** storage retention. There is **no `:seon.config.*retention*`
family in the tree today.**

The per-minute `blob-retention` sweep is likewise gone: the root maintenance
portfolio at `src/seon/schedule.clj:45-70` now lists exactly five rows —
`footprint` (`"0 2 * * *"`), `reap-dead-roots` (`"15 2 * * *"`),
`rotate-logs` (`"30 2 * * *"`), `process-census` (`"5 * * * *"`) and
`compact` (`"0 3 * * 0"`). `store-footprint-2026-09-16.md` recorded
`blob-retention` at `"* * * * *"` in the same portfolio on 2026-09-16, and the
GC issue's re-observation records it being removed
(`blob-retention-sweep-starves-every-roster-writer.md`). **The removal has
landed.**

**What Datahike already gives instead.** `config/default.edn:4` sets
`:seon.config.db/keep-history? true`, so the temporal indexes hold every
retracted datom and **fact** retention needs no dial at all. What genuinely
needs a decision is **snapshot** retention — how far back the commit graph stays
reachable — and that is `remove-before` and nothing else (`gc.cljc:103-106`).

**Recommendation: removal is complete; do not reintroduce a Seon-side retention
dial.** Let `:keep-history?` own fact retention and the derived `remove-before`
of §1.3 own snapshot retention. Two dials collapse to one, in the dependency's
own vocabulary. If a Seon dial appears in a future design, it is a mirror of
`remove-before` and should be refused on sight.

### (e) Cold page slice 2 — location and what remains

**Where it lives:** the landing is
`docs/prds/context-generation/research/cold-page-kills-2026-09-16.md`; the issue
is `docs/seon/issues/the-first-debug-page-after-an-adoption-takes-eighteen-seconds.md`
(the cold-page follow-up section at `:11-25`); the approved plan is
`docs/prds/context-generation/research/cold-page-plan-2026-09-15.md`, tracked as
row G1 in `docs/prds/steward-platform/plan/README.md:108`.

**What remains** (stated, not re-decided):

- **Slice 1 landed** at commit `0dd6bc0aa`; **its orchestrator gate is still
  pending** (issue `:21`). Live retained calls 11 → 11; the armed canonical page
  regression passed 12 / 0 / 0.
- **Slice 2 stopped at the requested design gate.** The database-aware
  dependency prototype returned a stale `{:db/id 39410}` after entity deletion
  where uncached execution returned `nil`; equality false; reproduced in 24 ms.
  The original Vars were restored and **the fork is unchanged**. Grounding:
  `reference-code/datahike/src/datahike/pull_api.cljc` (`compile-pull-plan`,
  `pull-spec-attribute-dependencies`, `pull-attr` near `:372`) makes `:db/id`
  depend on entity-datom existence, while
  `reference-code/datahike/src/datahike/query.cljc`
  (`advance-query-cache-context` near `:2568`, `source-context-unchanged?` near
  `:2960`) compares revisions of *modified stored attributes* — so deleting the
  last unselected attribute changes existence without changing either selected
  attribute's revision.
- **Three priced options are already written** in the landing's table:
  constrain narrowing (4–8 h, recommended interim), extend the dependency's
  existence evidence (1–2 days, cross-owner), or land slice 1 only (1–2 h).
- **The two-minute cold-page target remains unproven**; the root measurement
  still included an adoption and cost **0.667470 s cold / 0.139521 s warm**
  (issue `:20-21`).

Nothing here is blocked on decision 10; it is blocked on the owner choosing one
of those three, which is a separate call.

---

## Part 3 — Vocabulary

Scanned: the last 150 lines of
`docs/prds/steward-platform/plan/unsettled.md` (`:737-886`) and
`docs/prds/steward-platform/plan/overnight-report-2026-09-17.md`.

The judgement applied is AGENTS.md §3's law: use Clojure's name, else the
integration seam's name, else coin once and record it. A **coordination word**
with no system referent is legitimate in a ledger and never in code, a schema,
a docstring or an architecture document.

| term | seen at | real mechanism, or coordination word | use instead |
|---|---|---|---|
| **prober** / "Probers: … = 4 (cap)" | `unsettled.md:632`, `:826`, `:841`, `:871` | **Coordination word.** Its nearest real referent is the count of concurrent sessions calling into `default`'s JVM — mechanically, connections accepted by `seon.cluster/mcp-io-prepl` (`src/seon/cluster.clj:480`, registered as the socket server's `:accept` at `:3437`) | "concurrent sessions on `default`"; when the cap is meant, "io-prepl connections into `default`". Never in code |
| **batch N** | `unsettled.md:814`, `:830`, `:835`, `:863` | **Real, via three parts:** a `bin/test` invocation over a namespace set, requested in `tmp/orchestrator/gate-requests/<lane>.txt` (the path `bin/_test-slot:22-28` names in its refusal), identified durably by its run root (`run.4M1WSK`, `:857`) and its recorded result facts (`seon.test.runner/commit-results!`) | ledger: keep "batch N". Docs/code: "a `bin/test` invocation"; cite the run root |
| **slot** / "in slot" / "slot free" | `unsettled.md:813`, `:821`, `:838` | **Real and correctly named.** `bin/_test-slot:14-17`: `tmp/test-slots/<n>` directories, atomic `mkdir`, `SEON_TEST_SLOTS` default 2, `SEON_TEST_SLOT_WAIT_SECONDS` bound | **keep** — it is the mechanism's own name |
| **gate line** | *not found* in `unsettled.md:737-886` | — | what exists is the **gate request** (the file under `tmp/orchestrator/gate-requests/`) and the **gate** (a `bin/test` invocation). Use those |
| **sweep** | three different referents: `:813`/`:838` (status check), `:815`/`:832` ("sweep pass 4" = an edit pass), and `konserve.gc/sweep!` (`reference-code/konserve/src/konserve/gc.cljc:8`) | **One of the three is a dependency function.** Overloading a dependency's verb is exactly §3's failure mode | reserve **sweep** for `konserve.gc/sweep!`; say "status check" for the periodic entry and "edit pass" for the mechanical pass |
| **heartbeat** | `unsettled.md:861`, `:872`, `:880`; AGENTS.md §1.5 "Commits are the heartbeat" | **Coordination word**, two senses: the periodic ledger entry, and "a lane's progress is its commits" | "periodic status entry"; and "a lane's progress is its `git commit` sequence". Never in code |
| **landing** / **landing note** | `unsettled.md:815`, `:823`, `:832`; AGENTS.md §1.10 | **Coordination word** over two real referents: a dated file under `docs/prds/*/research/` and the `git commit`s it names | ledger: keep. Docs: "the research note at `<path>`" and "commit `<sha>`" |
| **reclamation signal** | `unsettled.md:744`, `:822`, `:838`; report decision 3 | **Coordination word today.** After Part 1 it has a referent: `konserve.filestore/count-konserve-keys` compared against the previous collection's `:seon.cluster.registry/retained-files` | **"the unreachable-key ratio"** — grounded on both sides |
| **churn** | `unsettled.md:788`, `:822`, `:838` | **Real, and better named by the dependency:** copy-on-write persistent-sorted-set leaves retained because nothing collects them (`store-footprint-2026-09-16.md`: 93.3% `pss/leaf`) | "uncollected copy-on-write index nodes" |
| **write storm** | `unsettled.md:770` | **Real mechanism, vivid name.** An unbounded retry of a refused write in the turn loop's `[:db.fn/call retain-transaction]`, now bounded by `:seon.config.agent/write-refusal-bound` (`:784`) | "unbounded write retry". The dial is the durable name |
| **cold** / "proven cold" | `unsettled.md:814`, `:840`, `:863` | **Real distinction, already named by AGENTS.md §5:** the isolated gate's proof (a worker JVM loading the published base) vs an in-process iteration result | ledger: keep. Docs/code: "proven by the gate" / "in-process only" |
| **red** / **green** | throughout `:737-886` | **Real:** `clojure.test` failure and error counts in the runner's tally | ledger: keep. Docstrings: "failing tests", "the recorded verdict" |
| **class** | `:783`, `:796`, `:810`; issue tags `class/p1`, `class/n14` | **Real and load-bearing** — AGENTS.md §5 "one regression per class"; it is a query tag on issue frontmatter | **keep** |
| **wave** | issue frontmatter tag `wave/store-perf` in both store issues | **Coordination word**, but used as a frontmatter **query tag**, which is a legitimate home | keep in frontmatter; never in code or schema |
| **lane** | AGENTS.md §7, `unsettled.md` throughout | **Real and defined with its command:** one `bin/codex-agent run <name>` process, or one Agent-tool subagent | **keep** |
| **working edge** | AGENTS.md §8; `unsettled.md` itself | **Real** — it names a specific file, `docs/prds/steward-platform/plan/unsettled.md` | **keep** |
| **reset #N** | `:770`, `:804`, `:859`, `:884` | **Real** — `bin/seon reset --force` plus Juniper reseed plus `init --dev` (AGENTS.md §6) | **keep**; state the three steps when the boundary cost matters (§1.5 Option C) |
| **prepl NNNNN** ("prepl 56009") | `:859`, `:884` | **Real** — the io-prepl's TCP port (`seon.cluster/mcp-io-prepl`, `src/seon/cluster.clj:480`) | **keep** |

**One standing observation.** The three genuinely unmoored words — *prober*,
*heartbeat*, *reclamation signal* — are all **counts of things nobody measures**.
That is the same shape as the recurring failure class: a word stands in for a
number, the number is never derived, and the absence reads as health. Each row
above pairs the word with the number that would falsify it.
