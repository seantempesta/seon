---
type: landing
status: committed-unverified
lane: fix-datahike-fused-root-pin
created: 2026-09-24
issue: docs/seon/issues/a-committed-database-holds-its-fused-roots-only-softly.md
---

# Lane fix-datahike-fused-root-pin: landing (2026-09-24)

## Broken / unverified

- **Nothing has run yet.** Under the LOAD RULE this lane started no JVM and ran no tests.
  The fix and its regression are committed in the fork but have not been executed. Root
  runs the commands below.
- **The seon gitlink has not been bumped and the fork has not been pushed.** Root owns both.
- **Default keeps the soft-root exposure until it adopts the new fork revision.** Hook
  publication is paused, and Datahike is a dependency, not program rows. Root decides how
  default picks it up (restart from the checkout after the gitlink bump). RESET NEEDED is not
  required: a restart is enough.
- **Correction to the issue:** `force-branch!` (`versioning.cljc:385-400`) does not remove
  the fused roots from its pending writes. It writes each root as its own object, so its
  flushed value was never exposed. The fix pins that value too, which is harmless. Only
  `commit!` (`writing.cljc` `fused-root-addresses`) actually leaves roots unwritten. The
  issue text should say so when root closes it.
- **No CHANGELOG entry.** The fork's `CHANGELOG.md` is outside this lane's ownership. If the
  fix goes upstream, add a notable-fixes entry.

## Change (fork commit `fa0cc1a7`, parent `620721d6`)

`reference-code/datahike/src/datahike/writing.cljc`, fused-root block of `db->stored`:
- A new `pin-root` reads `di/-root-node` from each flushed set.
- It installs that node back with `di/-seed-root!`, the same seam `stored->db`'s `attach`
  uses on restore.
- It returns the node for `:eavt-root` … `:temporal-avet-root`.
- This runs only when `fuse?` is true (flush, `:fuse-index-roots?`, persistent-set index).
- Size: +19/−6 source lines, mostly the explanatory comment (7 lines of logic).

New regression, `reference-code/datahike/test/datahike/test/fusion_root_pin_test.clj`
(70 lines). Setup: a fused-root file store with history. For each of the six indexes of
`@conn` after `commit!`, and of a `db-with` value flushed by `force-branch!`, the test:
1. clears the root if it is held through a `java.lang.ref.Reference`, which is exactly what
   the collector can do; a strong root cannot be cleared this way;
2. reads through a `with-storage` copy bound to a fresh `create-storage`, so the node cache
   is empty;
3. asserts the datom counts match.

It also asserts that `commit!`'s roots are absent from the store, which is the premise of
the bug. Before the fix, the commit block should raise `:node-not-found`, as
`tmp/diagnose-node-not-found/soft-root-clear.clj` did on default's live value. After it, the
reads should succeed.

## Evidence (source, persistent-sorted-set 0.4.137, `javap -c`)

- `Settings.readReference` returns a non-`Reference` object as-is, so a seeded node is held
  strongly.
- `PersistentSortedSet.store()` wraps `_root` with `makeReference` only when `_address` is
  null, i.e. on first flush. A flushed set returns early, so a later flush never re-softens
  a seeded root.
- `root()` writes `_root` only after `restore`. That cannot happen while a strong root is
  present.
- `cons`/`disjoin`/`replace` write `_root` on new sets, not on the flushed value.
- `-flush` returns the same set it stored (`persistent_set.cljc:219-221`), so the seeded
  set is the one the returned db holds.
- `detach` (`with-storage`) copies `_root` by reference. The stored record's detached index
  therefore also holds the node, which the record already holds as `:eavt-root`. No extra
  retention results.
- Concurrency: the seed replaces a reference to the node with that same node, so a
  concurrent reader sees an equivalent root either way.
- Other writers of fused records: none. `-create-database*` (`writing.cljc:~660-700`) does
  not fuse and writes its roots.
- Lint: `clj-kondo` reports no new warnings. The remaining warnings in `writing.cljc` are
  pre-existing, and the unresolved `d/` vars in the test are the stale API-macro cache.

## Measurements

None. Nothing was executed (LOAD RULE). Expected cost: one field write per index per
fused commit, and one strongly held root node per live db value. That node is already
reachable from the commit record's `:eavt-root` while the record is cached.

## Root runs (serially, quiet window)

1. Fork regression plus the existing fusion suite:
   `cd /Users/sean/src/seon/reference-code/datahike && clojure -M:test -m kaocha.runner clj-pss --focus datahike.test.fusion-root-pin-test --focus datahike.test.fusion-test`
2. Optional red check, confirming the regression catches the bug: run the same command with
   `src/datahike/writing.cljc` at `620721d6` in a `git archive` snapshot of the fork. Expect
   the `commit!` block to fail with `{:type :node-not-found}`.
3. After the gitlink bump and default restart, re-run the diagnosis probe with a pinned-root
   check. `(class (.-_root (:avet @(seon.cluster.boot/connection "default"))))` should be a
   node class, not `java.lang.ref.SoftReference`.

## Commits

- Fork: `fa0cc1a7` "Pin fused index roots strongly in the flushed live db value".
- Seon: this landing note only (`git commit --only`).

Released paths: `reference-code/datahike/src/datahike/writing.cljc`,
`reference-code/datahike/test/datahike/test/fusion_root_pin_test.clj`, and this note.
