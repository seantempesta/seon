---
type: issue
status: open
severity: blocker
tags: [issue, database, testing]
---

# Claim the store flock's fcntl close hazard with a recurring falsifier

## Problem

The defect is FIXED (`31e38e12d`), but nothing that runs again can catch its
return. Java's `FileLock` is `fcntl`, and `close(2)` drops **every** lock the
process holds on a file the moment **any** descriptor to that file closes. So
the obvious implementation of a same-process refusal — open a second channel,
catch `OverlappingFileLockException`, close the channel — silently unlocks the
store at the OS level while `FileLock.isValid` still reports `true`. A foreign
JVM then opens the same store: two writers, one store, which is the exact
O2/L6 loss (40 of 40 returned commits vanished with no error) the flock exists
to prevent.

The sealed suite cannot see this. `one-holder-per-store-in-one-process`
(`test/seon/cluster/store_test.clj:101-115`) performs the refusal and then only
reopens **in the same process**, which succeeds either way, and
`the-flock-fences-across-processes` (`:172-214`) never performs an in-process
refusal while the child holds. The two halves are each covered; their
interaction — the one that breaks — is not. It was found by hand and proven by
a lane probe, which by the repo's own rule counts as NOT COVERED.

## Evidence

Live falsification against the pre-fix implementation (parent holds the store,
refuses its own second open, then a real child JVM tries the same lock file):

```text
child BEFORE in-process refusal => REFUSED
in-process second open => :seon.cluster.store/held-elsewhere
child AFTER in-process refusal => ACQUIRED     <-- the fence is gone
```

After the fix (`src/seon/cluster/store.clj`, `held-flocks` answers this
process's own holdings before a second descriptor is ever opened), the same
probe reports `REFUSED` both times.

The probe, so the sealed test does not have to be rediscovered. Child:

```clojure
(let [path (first *command-line-args*)
      ch (FileChannel/open (.toPath (io/file path))
                           (into-array OpenOption [StandardOpenOption/CREATE
                                                   StandardOpenOption/WRITE]))
      l (try (.tryLock ch) (catch OverlappingFileLockException _ :overlap))]
  (println "CHILD-RESULT" (if (or (nil? l) (= :overlap l)) "REFUSED" "ACQUIRED"))
  (when (instance? java.nio.channels.FileLock l) (.release l))
  (.close ch))
```

Parent: `open-store!` the directory, spawn the child once (expect `REFUSED`),
attempt a second `open-store!` in-process (expect the `::held-elsewhere`
refusal), spawn the child again — it must **still** report `REFUSED`. The child
JVM is launched exactly as `the-flock-fences-across-processes` already launches
`seon.cluster.store-child`, so the suite already owns the mechanism.

## Owner

`test/seon/cluster/store_test.clj` — the sealed acceptance. The implementation
lane could not add the falsifier because the file is byte-sealed, so the
contract author owns admitting it. The mechanism under test is
`seon.cluster.store/acquire-flock!`.

## Acceptance

A test in the sealed suite that fails against an `acquire-flock!` which answers
`OverlappingFileLockException` by closing the second channel, and passes
against the current implementation. Behavioral, not prose: the assertion is
that a foreign process is refused **after** this process has refused itself,
not that any particular table or exception exists.
