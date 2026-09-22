---
type: issue
status: open
severity: blocking
created: 2026-09-23
tags: [issue, publication, boot, nuke, platform]
---

# Gitlink pins refuse every from-zero publication

## Problem

`seon.test.cache/gitlink-digests` returns the raw 40-character Git commit id of
each submodule since `678009fcd` (`src/seon/test/cache.clj:243`, previously
`(sha-256 (.getBytes pin "UTF-8"))`). `seon.cluster.source/capture-paths`
(`src/seon/cluster/source.clj:110-121`) uses that pin as the digest of a directory
input, and `seon.fn/index!` writes it as `:seon.fn.file/digest`
(`src/seon/fn.clj:3383-3389`), declared `[:string {:min 64 :max 64}]`. Every
from-zero publication therefore refuses:

    Program indexing transaction was refused. seon.db/transact! refused transaction
    data at [47940 :seon.fn.file/digest]: expected a string, got a string.
    :seon.db/offending "0fb349c414e717800be775ba9cb77c95a9eb700d"   ; reference-code/babashka pin

Observed: `bin/seon --root <scratch> nuke --force` from HEAD `535ce45cf`, both
attempts, 2026-09-23T21:29–21:30Z (log
`tmp/orchestrator/nuke-is-total-drill-2026-09-23.log`). A synthetic commit
`f40378571` = HEAD `cd95c73f5` with only that line restored reached readiness in
74.5 s from zero. The rendered refusal text ("expected a string, got a string")
names neither the bound nor the length: ugly output.

Consequence: `bin/seon reset --force` / `nuke --force` of `default` cannot reach
readiness from any HEAD at or after `678009fcd`; `default` survives only because
it was restored from `bc8a1fa68` before that commit.

## Fix owner

`src/seon/test/cache.clj` (the realities / 1.3d lane). Either hash the pin again or
declare the directory-input digest separately from the file digest; the declared
64-character file digest is the producer's schema and stays.
