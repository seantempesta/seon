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

## Evidence 2026-09-26 (default root, `tmp/orchestrator/start-head-*.txt`, `:seon.boot/layers`)

| Move to | Class cache | total | pre-boot (JVM + require) | ready | store→branch | adopt |
|---|---|---|---|---|---|---|
| 6ccf12a5a | hit | 28.7 s | 6.3 s | 13.3 s | 10.2 s | 6.4 s |
| 5bed888d0 (SCI pin bump) | miss | 56.5 s | 15.6 s | 30.3 s | 26.1 s | 9.9 s |
| 61d207c3e (`:jvm-opts` edit, analyzer edits) | miss | 52.2 s | 12.3 s | 30.5 s | 27.3 s | 6.7 s |

- store→branch at 61d207c3e is a full republication: the analysis cache rewrote
  all 466 first-party namespace entries at 15:49:18–20Z and `current-src` took
  three transactions (15:49:27.9, :30.1, :37.7Z, the last storing the new
  `:seon.source/analyzer-digest`) inside the boot window (15:49:10.8–:38.0Z).
  `src/seon/fn/analyzer.clj` and `src/seon/cluster/source.clj` changed, both in
  analyzer reach, so `full-source-refresh!` classifies `:all`
  (`src/seon/cluster.clj:1963`). Kept by root ruling; the cost is ~27 s per
  analyzer-reach commit (lint ≈8 s, analysis ≈8 s, three transactions ≈10 s).
- A class-cache miss costs 6–9 s before `start!` begins. Nothing fills the
  cache on a restart: the operator reports the fill command and never runs it
  (`dependency-classes`), so every restart after a dependency change misses
  until someone runs `clojure -T:dev-cache ensure-cache` (111 s cold build).
  61d207c3e also missed because the key digested `deps.edn`'s bytes, including
  a `:jvm-opts` HeapDumpPath change (c2b757cb3); the key now omits launch
  options (`dependency-declarations`), and a start fills a miss before the old
  JVM stops (`filled-classes!`, root ruling option A).
- Adoption after readiness (6.4–9.9 s: `development-source-refresh!` 5.2 s,
  `adopt-rows!` 2.8 s) still re-adopts what the fresh JVM loaded.

## Acceptance

A move after one committed definition change completes in seconds with every
phase over 1 s justified: a class-cache hit from committed inputs, a
sub-second one-file publication and resume comparison, and an adoption that
does not reload namespaces the fresh JVM loaded from the same bytes.

## 2026-09-26 (perf-boot-opus)
Boot now names the published commit as the JVM's loaded program when every published file it loaded has the
published bytes (`seon.cluster/name-loaded-program!`), and `development-source-refresh!` then only writes the
cluster's rows and record: no `require :reload`, no re-arming (3.9 s at the a34078273 move, 11.6 s at e405f51be).
The store→branch layer's cost is the caller re-lint of task `ff74379b6565` (seon.fn).
