---
type: landing
lane: fix-merge-basis-opus
created: 2026-09-26
status: committed, unadopted; RESET NEEDED; the stranded write-back is blocked by the merged test's own source (below)
---
# fix-merge-basis: merge and write-back bases are the touched rows' digests

What exists: `seon.cluster.source/prepare-merge!` → `accept-merge!` → `write-back!`, entered by root through
`my.program`. The basis was the head's Datahike t (accept: `:datahike/expected-basis-t`; write-back: head
commit = merge commit), so any write (root's own recorded run) staled both. Now each operation compares the
definition digests of exactly the declarations it touches (owner ruling: differing digests = changed).

## Broken or unverified first

- **D (the stranded milestone write-back) cannot complete as merged.** Proven on the real merge (scratch copy of
  the new `write-back!`, JVM, copy of the file under `tmp/fix-merge-basis/root`, 421 ms): every basis check now
  passes although the cluster head moved many commits since the accept, but the candidate file does not
  analyze: the merged test calls `my.plan/current` fully qualified, and `test/my/plan_test.clj`'s `ns` form does
  not require `my.plan` (clj-kondo `:unresolved-namespace` at the appended line 611). The SCI world had
  `my.plan` loaded, so the merge gate passed. Write-back now refuses this as `:not-analyzable` naming the
  finding (it threw before). Merge carries only function and test rows, so no REPL entrance can add the
  require. Options for root, below.
- **Default died of OutOfMemoryError at ~16:10Z** (not this lane: no form of mine had reached it;
  `data/clusters/default/logs/seon.log` ends in a test run, heap dump `data/heap/java_pid19859.hprof`, 17 GB).
  Root restarted it (pid 27723).
- **Nothing here ran as tests** (default runs archive 6a8e46ec, older than the commit). Regressions written, not
  run; root runs the list below after adoption.
- **Accept waited ~6 s on collection.** Cause, from the code: `seon.test/run` hands `collect-released!` to the
  work launcher after every request (7c7edcbc7); the sweep takes Datahike's exclusive reachability gate
  (`reference-code/datahike/src/datahike/gc_guard.cljc:244` `acquire-sweep-permit!`), and `delete-branch!` needs
  a `:roster` permit (`versioning.cljc:293-298`), which queues behind it. `my.program/accept-merge!` retired the
  tested branch synchronously, so in root's one-form prepare+accept it waited for the prepare's own sweep. Fixed
  on the accept path only; **every roster publication still waits** (MCP `branch` create/retire, the next
  prepare's `branch!`) while a sweep runs, and the sweep now runs after every test request and takes ~6 s on
  default. That class belongs to the maintenance/registry owner (inv-lifecycle-storage-opus).
- `seon.source.edn` was free (no ledger holder, clean tree) and is the only schema of `:seon.source/accepted`;
  I edited it in this commit rather than stall. Root: record or reject.
- C's renderer producer is not in my files: `src/seon/error.clj` `refusal-data` (~:1037-1072) turns every error
  carrying `:seon.error/operation` and `:seon.error/offending` into a schema problem ("expected the declared
  requirement (nil)"), and `refusal-text` (~:1115) appends "No docstring example". Recorded in the existing
  issue `agent-repl-error-output-diverges-from-clojure-main-for-every-ordinary-mistake.md` (new row). My
  refusals now carry both digests as expected/offending and their docstrings an example, which only masks it here.

## What changed (commit below)

- `prepare-merge!` answers `:seon.source/expected-digests` — the digest the head held for each merged
  declaration (`:seon.program/absent` when absent) — instead of `:datahike/expected-basis-t`.
- `accept-merge!` prepends `[:db.fn/call declarations-held-tx expected]` (the writer decides, as
  `registry/head-guard-tx` does for publication): a moved merged row refuses inside the writer before any datom
  lands, naming each row with `:seon.error/expected` (prepared digest) and `:seon.error/offending` (current);
  Seon's `write-refusal` returns that value verbatim. Answers a plain `:seon.source/accepted`
  `{:seon.program/identities :seon.source/base-commit-id :seon.source/commit-id :seon.test.run/id}` — no TxReport.
- `write-back!` takes that value, reads the before/after commits with `d/commit-as-db`, refuses `:row-changed`
  only when a written declaration's digest on the head differs from the merge commit's (both digests named),
  `:commit-absent`, and `:not-analyzable` (analyzer findings) instead of throwing.
- `my.program/accept-merge!` unlinks the tested branch as detached io work through the environment's work
  launcher (failure → `fault/fault!`; a closed launcher unlinks inline). `my.program/context` carries
  `:seon.env/environment`.

**Deleted:** the head-t fence (`:datahike/expected-basis-t` on the proposal, the accept transaction and both
schemas), write-back's head-commit comparison (`:head-moved`), accept's second lineage walk
(`program/changed-identities basis tested`) and its `(set? delta)` guards, write-back's `changed-identities`
walk over the report.

Size: src+schemas +182/−65 (source.clj +133/−52 incl. the moved write-back docstring; my/program.clj +33/−8;
schemas +15/−5); tests +37/−23. Over the ~100-line guide: new logic is the digest reader (13), the writer guard
(18), the write-back wrapper (12) and the detached retirement (25); the rest is docstrings and contracts.
Smaller alternative: drop the detached retirement (−30) and leave accept waiting until the maintenance owner
stops holding the roster gate for seconds.

## REPL evidence (default pid 27723, JVM, throwaway ns `fmb.scratch` = unarmed copies of the new functions)

- `held-digests` of `#{merged test, absent fn}` on the live head: 1.9 ms →
  `{[:seon.test/sym my.plan-test/current-refuses-an-unknown-agent-by-name] "a618c951…", [:seon.fn/sym nowhere/declared] :seon.program/absent}`.
- `d/with head [[:db.fn/call declarations-held-tx held]]` → 1 datom (the tx only); with one digest altered →
  thrown, and `seon.error.refusal/refusal` reads `{:message "The destination changed [:seon.fn/sym nowhere/declared]
  after the merge was prepared; prepare it again." :member :seon.source/expected-digests :expected {… "aaaa…"}
  :offending {… :seon.program/absent}}`.
- The stranded merge located read-only: merge commit `6ab7ec42-8488-522d-b524-51856c163010` (t 536871314),
  parents `6ab7ec38-bee7-5781-9bb7-c118831a2698` (`:cluster-default`, before), `6ab7caab…` (candidate
  `:dogfood-repl-opus-12`), `6ab7ec3c…` (`:merge-fb958c29143f`).
- `write-back!` of that merge onto the file copy: 421 ms → `:not-analyzable`, finding `Unresolved namespace
  my.plan` at 611:18; a random commit id → `:commit-absent`; copy unchanged.
- `seon.contracts-compile-test/check` over source.clj and my/program.clj against the checkout: 0 findings, 447 ms.

## Root: adopt, then run

RESET NEEDED (adopt the commit). Then:

```
bin/test-check default --ns seon.cluster.merge-test
bin/test-check default --ns seon.cluster.write-back-test
bin/test-check default --test seon.cluster.host-test/root-reads-a-branch-agents-proposal-and-only-roots-custody-accepts
bin/test-check default --ns seon.cluster.source-test        ;; after applying tmp/fix-merge-basis/source_test.patch
bin/test-check default --test seon.contracts-compile-test/every-declared-contract-compiles-against-the-packaged-projection
bin/test-check default --test seon.schema.edn-test/every-packaged-schema-resource-reads-as-edn
```

Patches (not my files): `tmp/fix-merge-basis/source_test.patch` (the old stale-basis assertion becomes "the
same proposal now merges"), `tmp/fix-merge-basis/repl-skill.patch` (`.agents/skills/repl/SKILL.md` said
write-back is not built and cited dead line numbers).

Regressions (class, wanted behaviour): merge-test "another row's write never stales it" (an unrelated head
write between prepare and accept) and "a head change to a merged row refuses by name with both digests";
write-back-test: an unrelated write between accept and write-back still writes, and a later change to the
written row refuses `:row-changed` with both digests.

## D: finishing the stranded write-back — three options (recommendation marked)

The merge is on the cluster; the file lacks the test, and its namespace does not require `my.plan`.
An accepted value is plain data naming two commits, so write-back can take any "after" commit whose test digest
the head still holds, with `before` = the commit before the merge (the test absent there, so it is an addition,
and `plan_test.clj`'s recorded digest still matches the disk bytes — verified above).

1. **(recommended)** Root redefines the test so it needs no require, then writes it back. Proven: the file copy
   with this exact form appended analyzes cleanly (23 test rows, 413 ms). Root, MCP `eval_clj` mode `sci`, no
   branch, after adopting this commit — run 1:
   ```clojure
   (in-ns 'my.plan-test)
   (clojure.test/deftest current-refuses-an-unknown-agent-by-name
     (let [refusal ((requiring-resolve 'my.plan/current) {:seon.db/db (seon.db/db) :seon.agent/id "an-agent-that-does-not-exist"})]
       (clojure.test/is (= "an-agent-that-does-not-exist" (:my.plan/missing-agent-id refusal)))
       (clojure.test/is (= :seon.agent/id (:my.plan/refused-member refusal)))))
   ```
   run 2 (rows settle at the end of run 1, so the commit is read here; later unrelated writes no longer matter):
   ```clojure
   (def accepted {:seon.program/identities #{[:seon.test/sym 'my.plan-test/current-refuses-an-unknown-agent-by-name]}
                  :seon.source/base-commit-id #uuid "6ab7ec38-bee7-5781-9bb7-c118831a2698"
                  :seon.source/commit-id (seon.db/commit-id (seon.db/db))})
   (my.program/write-back! {:seon.source/accepted accepted :seon.fn/root "/Users/sean/src/seon"})
   ```
   Expected `{:seon.source/changed-paths ["test/my/plan_test.clj"] … :seon.source/added [[:seon.test/sym …]]}`;
   then commit the file and publish. Sacrifice: the test bends to the file's requires.
2. Root hand-adds `[my.plan]` to the `ns` require and the test to the file, commits, publishes. Minutes; the
   manual path the milestone exists to retire.
3. Merge namespace rows' requires and let write-back splice the `ns` form (a REPL-authored declaration naming an
   unrequired namespace then writes back whole). Hours, cross-owner (merge delta families, `seon.ns` rows,
   write-back splice); a new decision for the owner.

Commit: see `git log -1 -- src/seon/cluster/source.clj`. Released paths: all six files, plus seon.source.edn.
