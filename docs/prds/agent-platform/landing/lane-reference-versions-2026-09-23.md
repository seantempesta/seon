---
type: landing
status: committed; runtime verification pending orchestrator restart
date: 2026-09-23
lane: reference-versions
---

# Reference checkout versions

Seon's operator launches the JVM with `clojure -M:dev:test` (`script/seon/operator.clj:530`), so the `:dev:test` resolved tree and paths determine its dependency sources. The corresponding reference checkout is the source for each local-root coordinate; Maven and Git coordinates use their resolved release or commit. This change moves two gitlinks; it adds no runtime mechanism and removes no code. Resolution is O(dependency tree); the ordinary runtime classpath is unchanged except for the nested `babashka/fs` checkout described below.

## Broken and remaining

- **RESET NEEDED:** moving `reference-code/babashka` to the installed Babashka binary's tag changed its nested `fs` gitlink from `3fdcbcb` to `8262efa`. I synchronized the clean nested checkout because `babashka/fs` is a `:local/root` JVM dependency. The running default has not been restarted or tested against those bytes. The orchestrator must restart from accepted HEAD, verify the nested checkout and rerun an installed focused test.
- `reference-code/http-kit` has untracked `.cpcache/`. Its checkout and gitlink already match `f56bbea`, so I left that library untouched under the local-change stop rule. The cache is not part of the commit.
- The standalone `clj-kondo` binary is `v2026.07.24`, while Seon's JVM loads the local-root source at `57252e0` (two commits after that tag). Its gitlink matches the loaded source; changing it to the binary tag would change Seon's JVM code.
- `bin/seon status` (0.20 s) reports default pid 90471, health `:observed`, 16 error signatures, 34 errored receipts, one failed run and one failed test unknown. Its source root is the older `ce73846828a5cc32798ef630b5a574f777646f30` archive with hook publication off. These commands prove checkout resolution, not that installed process. No source or test file was changed; an installed request against that default cannot prove the changed checkout until restart.

## Resolution and pins

At **2026-09-23 22:22 UTC**, `clojure -Stree -M:dev:test` (0.53 s) and `clojure -Spath -M:dev:test` (0.02 s) resolved the checkout. The same commands after the pin changes took 0.523 s and 0.023 s, exit 0. The tree shows ClojureScript **1.11.132** transitively under `thheller/shadow-cljs 2.27.4`, reached from `core.async.flow-monitor`; it is not named in top-level `deps.edn`. `core.async` **1.10.874-alpha3** wins over older transitive candidates. The complete captured outputs are in disposable `tmp/reference-versions/{stree,spath}.txt`.

| Library | Runs: coordinate and resolved version | Checkout before → after | Gitlink commit | Notes |
|---|---|---|---|---|
| babashka | binary `bb v1.12.212`; outside JVM classpath | `0fb349c` → `12872c1` (`v1.12.212`) | `cfb0d890e` | Nested `fs` now `8262efa`; RESET NEEDED. |
| clj-kondo | `clj-kondo/clj-kondo :local/root`, `57252e0` | `57252e0` → same | existing | Native binary is `v2026.07.24` (`794a508`); JVM source wins. |
| clj-reload | reference only, not on `:dev:test` classpath | `61c6fa7` → same | existing | Keep for source research; retire pin if no reader needs it. |
| clojurescript | transitive `org.clojure/clojurescript :mvn/version 1.11.132` | `946d75f` → `26dea31` (`r1.11.132`) | `dcc662661` | Tag fetched from upstream. |
| core.async | `org.clojure/core.async :mvn/version 1.10.874-alpha3` | `dc35f3e` → same (`v1.10.874-alpha3`) | existing | Older transitives lose to direct version. |
| core.async.flow-monitor | `io.github.clojure/core.async.flow-monitor :local/root`, `fbff842` | `fbff842` → same | existing | Test alias only; supplies transitive ClojureScript through shadow-cljs. |
| datahike | `org.replikativ/datahike :local/root`, `c79cd03` | `c79cd03` → same | existing | Local-root gitlink matches checkout. |
| datastar-clojure | `dev.data-star.clojure/{sdk,http-kit} :local/root`, `1cef624` | `1cef624` → same (`v1.0.0-RC7`) | existing | Two modules in one checkout. |
| edamame | `borkdude/edamame :local/root`, `63373df` | `63373df` → same | existing | Transitive `1.4.32` and `1.6.42` lose to local root. |
| editscript | `juji/editscript :local/root`, `b493ccf` | `b493ccf` → same | existing | Local-root gitlink matches checkout. |
| http-kit | `http-kit/http-kit :local/root`, `f56bbea` | `f56bbea` → same | existing | Untracked `.cpcache/`; no movement. Transitive `2.7.0` and `2.9.0-beta2` lose. |
| hyperlith | reference only, not on `:dev:test` classpath | `b08a8e8` → same | existing | Keep for source research; retire pin if no reader needs it. |
| kaocha | reference only, not on `:dev:test` classpath | `8846f91` → same | existing | Test runner is Seon's own; retire pin if no reader needs it. |
| konserve | `org.replikativ/konserve :git/sha 5b39fdd` | `5b39fdd` → same | existing | `.gitlibs` source path confirms exact commit. |
| langchain4clj | reference only, not on `:dev:test` classpath | `889f9e6` → same | existing | Keep for source research; retire pin if no reader needs it. |
| rewrite-clj | `rewrite-clj/rewrite-clj :local/root`, `60782e5` | `60782e5` → same | existing | Local-root gitlink matches checkout. |
| sci | `org.babashka/sci :local/root`, `fcbd886` | `fcbd886` → same | existing | Transitive `0.15.56` loses to local root. |
| superv.async | `org.replikativ/superv.async :git/sha 501b429` | `501b429` → same | existing | Datahike/konserve transitives select direct Git pin. |

All listed checkouts initially matched their outer gitlinks. Before movement, their working trees were clean except for http-kit's untracked cache; tracked branches with upstreams (Datahike, Editscript, Konserve, Superv.async) had no `@{u}..` commits. Detached checkouts had no upstream; neither moved checkout had tracked modifications. The Babashka tag already existed locally; the ClojureScript tag was fetched in 2.82 s. The two checkouts each took 0.03 s; the gitlink commits each took 0.04 s. The nested `fs` checkout took 0.02 s. Final `git submodule status -- reference-code` took 0.315 s, exit 0, with no `+`, `-`, or `U`; nested `git -C reference-code/babashka submodule status -- fs` took 0.07 s and confirmed `8262efa`; `git diff --check HEAD~2..HEAD` took 0.013 s, exit 0. The final `bb --version` and `clj-kondo --version` commands took 0.012 s and 0.006 s. No Seon repo push was made.

## Summary

Two outer references now match the versions actually resolved: Babashka `v1.12.212` and transitive ClojureScript `r1.11.132`. The other 16 were already aligned with the JVM classpath, Git pins, or are explicitly reference only. Babashka's nested `fs` checkout followed its release gitlink, so the orchestrator must restart and verify the default JVM. The http-kit cache and all unrelated shared-tree edits remain untouched.
