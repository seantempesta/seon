---
type: issue
status: open
severity: friction
created: 2026-09-23
tags: [issue, publication, nuke, hook]
---

# An archive-booted default relativizes hook paths outside its source

## Problem

The nuke (`bin/seon nuke --force`, `script/seon/operator.clj` `nuke!`) builds
`default` from a `git archive` of HEAD under `<root>/data/source/<sha>` so that no
in-flight hunk can break it (plan §7). That JVM's `seon.fs/source-directory` is the
archive, so `seon.cluster/refresh-source!` (3-arity, the hook's call in
`bin/seon-hook` `publish-source-paths`) relativizes a lane's repository path against
it. Observed on pid 43581 (booted from `tmp/nuke-source-bc8a1fa68`):

    (seon.fs/relative-path (seon.fs/source-directory) "/Users/sean/src/seon/src/seon/cluster.clj")
    ;; => "../../src/seon/cluster.clj"

Such a path is not an input path, so (reading `full-source-refresh!`,
`src/seon/cluster.clj:1857-1885`; not executed) the publication selects nothing and
returns the unchanged head: the lane's edit is silently not published. A path
outside the publication directory should refuse by name at `refresh-source!`.

Until then, an archive-booted `default` rejoins the files with an ordinary
`bin/seon stop && bin/seon start` from the repository (a resume on the same store),
once HEAD's from-zero defect
([gitlink pins](gitlink-pins-refuse-every-from-zero-publication.md)) no longer
blocks the working-tree publication.

## 2026-09-23: `bin/seon start --head` states it; the refusal is the holder's hunk

`bin/seon start --head` (lane move-to-head, `script/seon/operator.clj`
`move-to-head!`) runs `default` from `<root>/data/source/<sha>` and says so: its
result and `bin/seon status` carry `:seon.operator/source-root`,
`:seon.operator/hook-publication :off` and the reason, and the CLI prints
`HOOK-PUBLICATION off: ...` on stderr. Re-observed in a scratch JVM booted from
`08a3227df`: `(seon.fs/relative-path (seon.fs/source-directory)
"/Users/sean/src/seon/src/seon/await.clj")` answers
`"../../../../../src/seon/await.clj"`.

Exact change for the holder of `src/seon/cluster.clj`, in `refresh-source!`'s
4-arity before `acquire-root-store!` (probed in that JVM: a working-tree path
answers true, an archive path and a relative path false):

```clojure
outside (filterv #(let [relative (fs/relative-path directory %)]
                    (or (= ".." relative) (str/starts-with? relative "../")))
                 changed-paths)
_ (when (seq outside)
    (refused! (str "Changed paths lie outside this JVM's source directory " directory
                   "; its program is a committed archive and the working tree is not"
                   " published into it. `bin/seon down && bin/seon start` rejoins the files.")
              {:seon.fn/root directory :seon.source/changed-paths outside}))
```
