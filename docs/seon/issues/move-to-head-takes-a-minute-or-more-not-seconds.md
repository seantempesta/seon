---
type: issue
status: open
severity: friction
created: 2026-09-23
tags: [issue, operator, boot, publication, seconds-not-minutes]
---

# Move to HEAD takes a minute or more, not seconds

## Problem

`bin/seon start --head` (`script/seon/operator.clj` `move-to-head!`) keeps the
store and every cache, but on a scratch root it measured 54–185 s against the
nuke's 149 s on the same root (lane move-to-head, 2026-09-23, load 13–18 on 18
cores; logs `tmp/mth-evidence/`). Its own phases are about 3 s; the rest
belongs to other owners:

| Phase | Measured | Owner | Proportional to / cause |
|---|---|---|---|
| archive + census + classpath (operator) | 2.1–3.9 s; 9.7–12.4 s once per divergent pin | this command | 162 MB committed tree; a pin whose checkout diverges is archived and prepared (datahike `compile-java`) once per root |
| JVM start to boot entry | 33–52 s | `dev_cache.clj`, `reference-code/babashka-process` | no dependency class cache can be built from committed inputs ([babashka-process AOT patch](vendored-babashka-process-carries-a-local-aot-patch.md)); first-party namespaces compile from source by design |
| resume comparison `changed-source-paths` | 1.7–1.9 s with 0–1 changed | `src/seon/cluster/boot.clj`, `source.clj` | one `pull` per path over ~714 inputs; one query would do |
| boot publication of the changed files | 7.0 s / 6.5 s for one file; ~80 s for 8–9 central files | publication (`source.clj`, `cluster.clj`) | [a-three-file-changed-path-publication-takes-a-minute](a-three-file-changed-path-publication-takes-a-minute.md) |
| `default` adopting that publication | 2.1–23 s (`fn/index!` 12.8 s, `transact!` 9.8 s, 4,995 pulls 7.0 s) | `cluster.clj` `development-source-refresh!` | re-indexes the diff into the cluster branch and reloads namespaces a JVM launched from those very files already loaded |

## Acceptance

A move after one committed definition change completes in seconds with every
phase over 1 s justified: a class-cache hit from committed inputs, a
sub-second one-file publication and resume comparison, and an adoption that
does not reload namespaces the fresh JVM loaded from the same bytes.
