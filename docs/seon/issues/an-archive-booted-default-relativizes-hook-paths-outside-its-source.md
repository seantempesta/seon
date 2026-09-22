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
