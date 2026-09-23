---
type: landing
status: committed; integration pending orchestrator start
date: 2026-09-23
lane: pinned-deps
---

# Pinned reference sources

The checkout classpath resolves `deps.edn` `:local/root` paths, while a `start --head` archive resolves recorded gitlinks. The two paths now select the same dependency commits; the class-cache entry checks checkout pins before use. This removes startup-mode-dependent Malli and process source selection. The check is one `git submodule status -- reference-code` call, O(number of submodules); an archive uses its recorded pins.

## Broken and remaining

- The running default is still the older `ce73846828a5cc32798ef630b5a574f777646f30` archive. `bin/seon status` and MCP `runtime_status` reported pid 48902, health `:observed`, 16 error signatures, 34 errored receipts, one failed run and one failed test unknown. Its pinned Malli is 8725a8cbd and process is e83ec5c. The current JVM is **not proof of these edits**.
- **RESET NEEDED:** the orchestrator must use `bin/seon start --head` after accepting these commits, then verify the new archive's pins, class cache, arming and focused installed tests. The lane did not stop, reset, restart or adopt default.
- The archive class build below took 51.38 s, over the ordinary 10 s target. It is a one-time full dependency AOT build of 373 namespaces; the existing cache-build cost issue remains the owner for reducing it. Peak heap was not measured after the child JVM exited; the resulting cache uses 95 MiB on disk.

## Decisions and changes

- **Clojure:** `reference-code/clojure` gitlink and checkout moved from b18d3adc (pre-1.13 alpha) to tag `clojure-1.12.5`, commit 3bc2b3e91. `core.clj:7204-7225` reads the generated `clojure/version.properties` string; this source does not contain a literal version. The tag's `pom.xml:8` says `1.12.5`, and the selected Clojure runtime returned `1.12.5` for `(clojure-version)`. `deps.edn` declares `org.clojure/clojure` 1.12.5.
- **Malli:** returned checkout to the existing 8725a8cbd gitlink. `git -C reference-code/malli branch -r --contains 56394c54` returned `fork/seon-ref-scope`, so the newer commit remains available. At `malli.core/-instrument`, 56394c54 introduces `::invalid-schema-at-call` for a throwing validator. Committed `src/seon/instrument.clj` handles arity/input/output/guard reports but has no branch for that report; the other lane's *uncommitted* `src/seon/instrument.clj` and `test/seon/instrument_test.clj` explicitly add that branch and its regression. Thus advancing the pin now would change HEAD behavior before its consumer lands.
- **Process:** switched gitlink e83ec5c to upstream `babashka/process` 43bdd65 and `.gitmodules` URL to upstream. `deps.edn` describes upstream; `dev_cache.clj` binds `*compile-files*` while requiring discovered namespaces in load-start order. `test/seon/render/web_test.clj` reads the existing `:seon.await/requested-member` field rather than the retired nested field. The dependency-cache compile-order regression already in `test/seon/dev/dependency_cache_test.clj` exercises the relevant two-JVM cycle.
- **Pin guard:** `dev_cache.clj` checks `git submodule status` before `refresh!` and `ensure-cache`, including cache hits. Git's leading `+`, `-` and `U` status is a refusal; a nonzero Git exit and a 10 s timeout are also refusals. An archive has no `.git` and uses its recorded `dependency-pins.txt`. No new registry or pin parser was added.

Commits: `186957285` (pins, upstream process compile order, web assertion and initial guard); `309a3a74e` (bounded subprocess for the guard). The first guard used `clojure.java.shell/sh`: its probe returned `:pins-match` but left a Clojure process alive beyond 35 s. The bounded `ProcessBuilder` version returned and exited in 0.65 s. No fork commit was created or pushed.

## Evidence and limits

| Probe | Observed wall time and result |
|---|---|
| `bin/seon status` | 0.01 s; old archive, hook publication off |
| MCP `runtime_status` | 0.5 s; default alive, `:observed` |
| `git submodule status` after commits | 0.23 s; no `+`, `-` or `U`; process 43bdd65, Clojure 3bc2b3e91, Malli 8725a8cbd |
| `git archive` snapshot of commit `186957285` plus its exact 21 recorded gitlinks | 0.67 s; clean first-party HEAD sources, submodule symlinks to matching checkouts, shared `.cpcache` linked |
| `clojure -T:dev-cache refresh` in that snapshot | 51.38 s rebuild lock; exit 0, 373 dependency namespaces, cache digest `8a224af108de0fdbe29bbe2b277641d0cc2f50d776c18da5d550713e0e10de48`; retained under `tmp/pinned-deps-proof-186957285` |
| Current checkout pin guard | 0.65 s; `:pins-match` and process exit 0 |
| Negative pin guard in a disposable Git repository with an uninitialized Malli gitlink | 0.81 s; refused `-8725a8cbd… reference-code/malli` with Git exit 0 |
| `git diff --check` on the two commits | under 0.01 s; clean |

The archive build used the initial guard, which skips archives. Commit `309a3a74e` changes only the checkout subprocess implementation; its positive and negative probes above cover that change. A build from the live default was neither attempted nor claimed. `bin/test-check default` currently selects the older archive and cannot prove these committed files; the orchestrator must run the focused dependency-cache and web tests after its `start --head`, then the platform cache-order regression in its platform cadence.

After that start, the new archive should resolve process 43bdd65 and Malli 8725a8cbd from `data/source/pins`, while a checkout start resolves the same commits by `:local/root`. Clojure itself runs from the `org.clojure/clojure` 1.12.5 JAR, and the vendored reference checkout is the matching 1.12.5 tag. Recheck the installed source URLs after the orchestrator's start; no live adoption is asserted here.

Released owned paths: `.gitmodules`, `deps.edn`, `dev_cache.clj`, `reference-code/babashka-process`, `reference-code/clojure`, `reference-code/malli`, `test/seon/render/web_test.clj`. Source diff: `dev_cache.clj` +30/-3 lines; test diff: `web_test.clj` +1/-3; two gitlinks moved. The guard itself is 19 added source lines including its two call sites and TimeUnit import. Other dirty paths in the shared tree were untouched.
