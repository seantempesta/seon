---
type: landing
status: writer fixed; disk reclamation pending orchestrator reset
created: 2026-09-22
tags: [agent-platform, store, datahike, gc, incident]
---

# Development store growth

**RESET NEEDED.** Every form sent to `default` was declared and implemented as
read-only. This assignment never stopped, reset, restarted, reconfigured, or
signalled PID 21908. The orchestrator must reset `default` after the fix lands
to reclaim the retained 248 GiB and load the corrected wake contract.

## Established cause

The immediate writer was the armed contract on
`seon.cluster.wake/deliver!`. Datahike calls the registered wake listener after
every committed transaction. `route!` registers the opaque keyword
`:seon.agent/route`, but `deliver!` declared that argument as
`:seon.agent/id`, whose installed schema requires a string. The armed wrapper
therefore refused before `offer!`. The core fault recorder committed that
refusal as an error occurrence; that commit invoked the same listener and
produced another refusal. A single ordinary commit could consequently create
work proportional to the error feedback chain instead of one listener
delivery.

The storage amplifier is Datahike's copy-on-write persistent-set index plus
the registry's default epoch retention. Each feedback commit wrote new index
paths. `seon.cluster.registry/collect!` defaults to `(java.util.Date. 0)`, so
Datahike `reachable-in-branch` follows every parent commit newer than the
epoch and retains every superseded index path. The result was work and retained
bytes proportional to all historical commits, rather than the three live branch
heads and a declared snapshot window.

The trigger is fixed at its owner in `0e0e8b6ba`: `deliver!` now accepts the
existing `:seon.cluster.wake/key`. No identifier conversion, cap, cleanup job,
or second delivery mechanism was added. The exact armed delivery regression in
that commit changes from one pass, one failure and two errors with the old
contract to four passes, zero failures and zero errors with the corrected owner.
The before fault names the keyword `:seon.agent/route` and the expected string.
See [the owner landing evidence](lane-turn-parks-on-boot-2026-09-22.md).

The retention seam still needs the A2 declared-history decision. An epoch GC
cannot reclaim commit ancestry by design; an explicit current cutoff can.
Choosing how many snapshots must remain addressable is cross-owner work already
assigned to A2's reachable-only copy/GC design. This lane does not invent that
retention period. The writer repair stops the measured catastrophic feedback;
the orchestrator reset reclaims this incident's existing files.

## Physical and logical inventory

The filesystem census found 899,520 Konserve `.ksv` files. Their logical file
bytes total 264,828,656,943 B (246.64 GiB); `du` reported 260,469,236 KiB
(248.40 GiB) allocated. The raw owner-probe envelope and exact form are in
`tmp/lane-store-growth/key_inventory.edn`.

| Logical key class | Keys | Bytes | Ownership |
|---|---:|---:|---|
| UUID | 875,002 | 263,923,133,107 | Datahike commit records and persistent-set index objects |
| 64-hex string | 24,309 | 794,367,077 | Seon content-addressed blobs |
| keyword | 209 | 111,156,759 | Datahike branch/head and store metadata keys |
| **Total** | **899,520** | **264,828,656,943** | |

| Physical size class | Files | Bytes |
|---|---:|---:|
| under 4 KiB | 9 | 8,160 |
| 4–64 KiB | 353,669 | 17,698,750,751 |
| 64 KiB–1 MiB | 496,333 | 97,114,899,503 |
| 1–16 MiB | 49,509 | 150,014,998,529 |

The ten largest keys are all Datahike
`org.replikativ.persistent_sorted_set.Leaf` values. They are index nodes, not
Seon blobs or application facts.

| Key | Bytes | Last write (UTC) |
|---|---:|---|
| `6ab216cf-78a1-49a3-9ee4-227825204f21` | 6,351,488 | 05:49:03.235 |
| `6ab21529-ec32-48a0-992e-bcc40ec36061` | 6,348,263 | 05:42:01.794 |
| `6ab2135e-b136-4376-a4f1-71b25d5d2294` | 6,348,263 | 05:34:22.197 |
| `6ab2135d-63bd-45f8-8ff0-699545add2d6` | 6,341,815 | 05:34:21.361 |
| `6ab216ce-7f64-4382-a6e6-3cb977abdaa9` | 6,341,815 | 05:49:02.052 |
| `6ab21528-a4ef-4247-911e-b5281cbabedb` | 6,341,815 | 05:42:00.982 |
| `6ab21ab4-8658-4d9f-a63b-311e39d1333c` | 6,340,311 | 06:05:40.851 |
| `6ab21c22-cf3b-4ddc-b7a6-a2556015b301` | 6,340,311 | 06:11:46.351 |
| `6ab21dcc-cda4-4866-9a27-8cf45bd7c985` | 6,340,311 | 06:18:52.383 |
| `6ab2182d-e40c-4444-9352-26faac90c233` | 6,337,087 | 05:54:53.877 |

The inventory form used the live connection's Konserve store without writes:

```clojure
(let [connection (seon.cluster.boot/connection "default")
      store (:store @connection)
      entries (konserve.core/keys store {:sync? true})]
  ;; For each entry, derive its FileStore name with
  ;; konserve.impl.defaults/key->store-key, measure java.io.File/length,
  ;; classify UUID / 64-hex string / keyword, then get only the ten largest
  ;; values to identify their concrete owner class.
  ...)
```

## Reachability: live heads, retained history, and garbage

The store had exactly three branches at basis 536895450:
`:cluster-default`, `:current-src`, and `:db`. Two read-only dry runs exercised
the existing `seon.cluster.registry/dry-run!` owner and did not sweep files.
The live half-landed schema made the instrumented private helper refuse, so the
probe explicitly called Malli's original `referenced-blobs` under `with-redefs`.
That bypass is confined to the read-only inventory.

```clojure
(let [store (:seon.store/store
             (get @seon.operator.runtime/running-instances "default"))
      referenced-blobs
      (malli.instrument/-f->original
       @#'seon.cluster.registry/referenced-blobs)]
  (with-redefs [seon.cluster.registry/referenced-blobs referenced-blobs]
    (#'seon.cluster.registry/dry-run! store cutoff {})))
```

| Mark policy | Retained files | Candidate files | Candidate bytes | Mark time |
|---|---:|---:|---:|---:|
| epoch cutoff, current default | 898,622 | 898 | 310,443,521 | 256,764 ms |
| current-time cutoff | 27,981 | 871,539 | 263,499,824,856 | 171,090 ms |

Subtracting the two inventories identifies 870,641 files and
263,189,381,335 B (245.11 GiB) retained solely because the epoch cutoff walks
old commit ancestry. Only 898 files / 310,443,521 B (296.06 MiB) were
unreachable garbage even under epoch retention. Current branch roots, temporal
roots, metadata, and referenced blobs account for the remaining
1,328,832,087 B (1.24 GiB). Because `:keep-history? true`, that last number
contains both current and temporal index roots; this probe does not claim a
byte split between those two owners. Exact envelopes are
`tmp/lane-store-growth/reachability_epoch.edn` and
`tmp/lane-store-growth/reachability_now.edn`.

The pinned dependency seam is Datahike
`006e634ae955c186619adb5f3868cca29d8c97fb`,
`reference-code/datahike/src/datahike/gc.cljc:22-70`. Its
`reachable-in-branch` always marks each selected commit's current indexes and,
because this store has `:keep-history? true`, its temporal indexes. It follows
parents only while the commit timestamp is after `remove-before`.
`datahike.gc-guard` supplies the separate writer safe point for sweeping.
Konserve's pinned Git revision is recorded in `deps.edn`; the live classpath
resolved that exact checkout for the key inventory.

## Writer correlation

The database contains 24,538 transaction instants since reset. Their UTC hour
distribution is:

| Hour | Transactions |
|---|---:|
| 00 | 37 |
| 02 | 4 |
| 03 | 13 |
| 04 | 48 |
| 05 | 6,764 |
| 06 | 9,353 |
| 07 | 6,832 |
| 08 | 1,487 |

Four identities for the same `seon.cluster.wake/deliver!` refusal recorded
10,372 + 39 + 12,108 + 18 = **22,537 occurrences**, from 05:17:21Z through
08:12:23Z. Every message says that `wake.clj:560` supplied keyword
`:seon.agent/route` where the armed contract expected a string. A related
overflow fact recorded another 1,748 dropped core faults. These counts and
timestamps align with the transaction burst. The agent was parked; publication
volume and tests do not explain thousands of transactions per hour. The exact
pull and per-hour form/result are in `tmp/lane-store-growth/errors.edn`.

Candidate datom counts distinguish current facts from Datahike's temporal
history:

| Attribute | Live datoms | History datoms |
|---|---:|---:|
| `:db/txInstant` | 24,538 | 24,538 |
| `:seon.error/id` | 10 | 10 |
| `:seon.error.occurrence/id` | 10 | 10 |
| `:seon.error.occurrence/count` | 10 | 48,578 |
| `:seon.error/data-blob` | 24,287 | 24,287 |
| `:seon.error.occurrence/data-blob` | 7 | 48,567 |
| `:seon.instrument/actual` | 9 | 45,075 |
| `:seon.fn/sym` | 4,454 | 4,476 |
| `:seon.schema.shape/fingerprint` | 5,571 | 5,571 |
| `:seon.source/digest` / `:seon.source/built-at` | 1 / 1 | 1 / 1 |
| `:seon.test.run/id` / `:seon.test.member/symbol` | 1 / 1 | 1 / 1 |
| `:seon.cluster.eval/id` / `:seon.eval/shown` | 9 / 9 | 9 / 9 |

The counting form was `(count (datahike.api/datoms database :aevt attr))`
and the same call against `(datahike.api/history database)` for each listed
attribute. The complete form and values are in
`tmp/lane-store-growth/datom_inventory.edn`.

All 24,309 content-addressed blob keys have a current owner: 24,287 are
`:seon.error/data-blob`, 21 are MCP artifacts, and one is a turn reply blob.
There are no unreferenced logical blob keys. The blob rows consume
794,367,077 B (0.74 GiB), so they are a secondary live-row leak rather than the
245.11 GiB cause. Only seven current occurrences reference their data blob;
the 24,287 surviving blob entities expose a separate error-recording retention
defect. The largest blob is a 4,417,397 B MCP artifact; the largest error blob
is 242,210 B. This incident does not attribute Datahike's 263.19 GB index
history to a single large error payload.

## Regression and verification boundary

`seon.store-growth-test/one-listened-write-produces-one-commit-and-no-fault-write`
uses the canonical database fixture, installed schema, real listener, and armed
production contracts. It measures the FileStore before and after one listened
message transaction and asserts the declared behavior: exactly one basis-t
increment, both wake deliveries, and no fault. After the owner fix, focused
`bin/test-fast --paths test/seon/store_growth_test.clj --
seon.store-growth-test` acquired projection and armed 1,683 of 1,683 registered
contracts. Run `c6d53827cd5b` executed one test / six assertions with zero
failures and zero errors. The store changed from 366,319,620 B to
367,676,719 B: delta 1,357,099 B for exactly one declared commit.

The identical delivery class already has the exact fail-before/pass-after
comparison in commit `0e0e8b6ba` and its landing note: with the old
`:seon.agent/id` contract, mailbox and render waits time out and the fault
contains the string-versus-keyword refusal; with the fixed
`:seon.cluster.wake/key` contract, all four assertions pass. This new regression
adds the storage declaration: the listened operation produces no error write.

An attempted repeat of the old-contract test in isolated worktree
`tmp/store-growth-before-wt` armed 1,683 contracts but its private result store
had no published `current-src`; snapshot admission refused before test
execution. `tmp/lane-store-growth/regression-before.log` is retained as a
boundary, not reported as a failed regression. The orchestrator owns published
base preparation. No cold gate or platform proof is claimed.

The running default's config apply and ordinary MCP status path were degraded
by a foreign half-landed config key whose schema had not been adopted. Plain
Datahike reads on `(seon.cluster.boot/connection "default")` remained available
and supplied every live measurement above. Hook publication was paused, so no
claim is made that PID 21908 adopted this or any concurrent edit.

The two pre-existing dirty documentation files, every pre-existing untracked
file, and kind-cut's protected source paths were preserved. No source path held
dirty by kind-cut was edited. The diagnostic worktree was based on HEAD and
linked only the existing dependency checkouts. All owned test JVMs exited; the
default JVM was never signalled.

Implementation owner commit: `0e0e8b6ba`.
This lane adds the store-specific regression and this measured incident record.
**RESET NEEDED.**
