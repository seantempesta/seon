---
type: issue
status: open
severity: friction
tags: [issue, operator, tooling]
---

# Source publication hides a failed dependency-cache subprocess

Observed 2026-09-09 while publishing the context-blocks scratch cluster.
The first `bin/seon --root tmp/context-blocks-root init --dev context-blocks`
attempt returned `Clj-kondo cache is locked by other thread or process.`
The next returned only:

```clojure
#:seon.dev.clj-kondo{:status :unavailable,
                   :reason "Dependency analysis did not populate its cache"}
```

`script/seon/dev/clj_kondo.clj:107` attaches the subprocess result to an
exception, but its outer catch returns only the exception message. The
operator cannot show the exit status or subprocess output needed to tell
cache contention from another failure. The path-isolated Clojure tests
loaded this same source and passed under armed contracts.

The instructed serial dependency-cache rebuild was observed at 425 seconds
elapsed, 354.30 seconds CPU, still active. It was ended by its owning lane
and retried with parallel analysis and a 300-second subprocess bound.

Preserve the failed subprocess's exit status and diagnostic output in the
existing cache result. Verify the reported cause before changing cache
coordination; this observation does not establish which process held the
first lock. No default cluster lifecycle action was taken.

The repair command in AGENTS.md exposed a separate cause of wasted work:
`clojure -Spath -M:test` includes `.` (`deps.edn:135`), and clj-kondo's
`sources-from-dir` traverses it with `file-seq`
(`reference-code/clj-kondo/src/clj_kondo/impl/core.clj:337`). The parallel
retry was also ended. Using the normal publication classpath
(`clojure -Spath`, with explicit dependency directories and jars) completed
successfully. AGENTS.md now teaches that command and names the test-root
exception; the original publication subprocess diagnostic still needs repair.

## Slice 3 observation — 2026-09-22

A scratch snapshot linked `.clj-kondo/.cache` to the existing checkout
cache. Operator preflight refused after 10.405 s with the same generic
message. The exact cause here is now verified: `cache-contents` returned
zero files through the symlink, while `babashka.fs/glob` on its real path
found **3,508** transit files. Its default traversal does not follow that
root link. The dependency subprocess's result was again hidden by the
outer catch. The lane removed only its scratch symlink and let the normal
operator populate that root's own clj-kondo cache. No shared cache files
were deleted and no production cache code changed in the publication slice.
