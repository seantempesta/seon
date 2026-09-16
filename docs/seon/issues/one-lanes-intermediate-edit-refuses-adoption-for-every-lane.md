---
type: issue
status: open
severity: friction
created: 2026-09-16
tags: [publication, edit-hook, lanes, adoption]
---

# One lane's intermediate edit refuses adoption for every lane

## Problem

The edit hook publishes the whole working tree's analysis on every admitted
edit, and `bin/seon init --dev default` adopts against the whole tree. A
lane's coherent-but-unfinished state therefore becomes every other lane's
refusal. Twice on 2026-09-16: reach-closure's two-file edit (a 1-arity
`seon.problems/problems` call committed ~90 minutes after the call site)
refused every publication with an arity finding; the shapes-dissolution
lane's in-flight `program.cljc` + schema EDNs made default's loaded program
throw "A program identity attribute declares no row schema", refusing every
adoption and every in-process `seon.test/run` ("Test provenance
unavailable") for all lanes.

`bin/test --paths` already solved this for the gate: snapshot HEAD and
overlay only the named paths. Publication and development adoption have no
equivalent, so the development environment is only as coherent as the least
finished lane.

## Candidates

1. Publication overlays only the publishing lane's changed paths on HEAD
   (the `--paths` semantic) for the static analysis and the program rows it
   upserts; a lane's other dirty files are not in its snapshot.
2. The hook refuses to publish an edit whose whole-tree analysis fails
   ONLY in files the editor did not touch, and says so, instead of
   publishing a state that refuses everyone.
3. Adoption keeps the last coherent program when a publication is refused
   and reports "adoption held at commit X because of <finding>", so
   in-process runs keep working against the last good program.

Related: `concurrent-publications-serialize-past-the-hook-bound`.
