---
type: issue
status: open
severity: platform
created: 2026-09-25
tags: [issue, boot, nuke, clj-kondo, dependency-analysis]
---

# Boot from zero cannot fill its own dependency analysis

`bin/seon nuke --force` (2026-09-25 22:0xZ, 3 min 58 s) deleted the clj-kondo dependency cache with the other derived caches,
then every boot attempt — HEAD and every older program it fell back to — refused "Cannot classify host binding: clj-kondo
holds no analysis of babashka.fs, babashka.process, cheshire.core, clj-kondo.core…", leaving no JVM. The only refill path is
`(seon.fn.analyzer/analyze-dependencies!)` at a running REPL, which cannot exist before boot publishes the program.
Root recovered by running it from the shell: `clojure -M:dev:test -e "(require 'seon.fn.analyzer) (prn (seon.fn.analyzer/analyze-dependencies!))"`
(1,796 files, 41 s), then booting. A second from-zero defect (call reach reading `:seon.fn/sym` on a store without the function
family, 01f5d0020) was fixed in 58e341cf3. Owner fix: boot (and nuke's rebuild) fills a missing dependency analysis before
program analysis, or nuke retains it as a classpath-keyed cache; regression = boot on an empty cache and empty store.
Fresh from-zero boot after both fixes: ready-ms 32,980.
