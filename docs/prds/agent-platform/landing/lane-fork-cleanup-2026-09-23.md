---
type: landing
status: S11 landed (http-kit rebased, live in default since its 08:52 restart); S2 dev_cache fix landed, the submodule/deps.edn switch to upstream babashka-process handed to the deps.edn holder
created: 2026-09-23
lane: fork-cleanup (Opus 5.5)
spec: docs/research/agent-platform/fork-audit-others-2026-09-23.md (5a7d7fe7a), slices S11 and S2
---

# Lane fork-cleanup: rebase http-kit, and fix the compile order that our babashka-process fork worked around

**What the system and its dependencies already do.** Clojure's `ns` macro adds a lib
to `*loaded-libs*` when its `ns` form finishes, before the rest of the file runs
(`clojure/core.clj`, the `commute @#'*loaded-libs*` in `ns`). `compile` compiles every
namespace a load reaches. So compiling in load-START order is already safe for a
namespace whose tail requires an extension that requires it back. `dev_cache.clj`
recorded each load when it RETURNED (post-order), which compiled
`babashka.process.pprint` before `babashka.process`. The fork's `*compile-files*` guard
existed to hide that. REPL-level form: a shell JVM running dev-cache's own
`discovery-form`, `run-child!` and `compile-form` against a scratch archive of upstream
43bdd65 (`tmp`-free: scratchpad `order-probe.clj`):
- parent `dev_cache.clj` → rows `[cache-probe.uses-pprint babashka.process.pprint babashka.process]` → compile exit 1, `Cyclic load dependency: [ /babashka/process/pprint ]->/babashka/process->[ /babashka/process/pprint ]` (684 ms)
- this change → `[cache-probe.uses-pprint babashka.process babashka.process.pprint]` → compiled (704 ms)

For http-kit, upstream v2.9.0-beta4 (07bd93b, 2026-07-31, which includes the 2026-07-30
`[sec]` server series) still has no server pending-write accessor:
`git grep toWrites v2.9.0-beta4 -- src` shows only the unbounded `LinkedList` in
`ServerAtta.java:7` and its users in `HttpServer.java:489-583`. So our one commit is
still needed; upstream does not cover it.

**The smallest change.** S2: move the `swap!` before `original-load#` (one line) and
pass the root lib to `discovery-form` as an argument, so the regression drives the
same form. S11: rebase the one commit onto the release. No new mechanism.

## S11 — http-kit

- Fork commit **f56bbea** "Expose per-channel pending write state" (238a85c rebased onto
  v2.9.0-beta4 07bd93b), pushed as the new branch `seon-pending-write-state-beta4` on
  github.com/seantempesta/http-kit. The old branch `seon-pending-write-state` (238a85c)
  is kept, so older Seon commits can still fetch their pin.
- Conflicts resolved (3 files):
  - `HttpServer.closeKey`: upstream now declares `att` after closing the channel and
    passes WebSocket `closeStatus`/`closeReason`. We keep our early `att` read, so
    `pendingWritesClosed()` runs before the close, and wrap upstream's WebSocket-aware
    `clientClose` in our `try/finally` that completes the drain future.
  - `doWrite`: upstream's new `requestInProgress` branch kept, with our
    drained-future capture and deferred close.
  - `AsyncChannel`: upstream's `isResponseStarted` plus our `writeState`.
  - `HttpKitServerTestSuite`: upstream's classes plus `WriteStateTest`.
- Why the release tag and not master 7f8c701: the lead asked for upstream's current
  release. Master is 2 commits past it (#615 duplicate-header normalisation, #618 the HTTP
  QUERY method). Neither touches the write path. A later rebase is trivial.
- Build products: the checkout's `target/classes` (tools.deps `:deps/prep-lib :ensure`)
  was rebuilt with `lein javac` in a scratchpad clone, then swapped in (the old one was
  moved to the scratchpad as `http-kit-classes-old-238a85c`).
- **Live.** Default restarted at 08:52:12 local ("schedule: restart on the durability
  deps", 3e4b9afcb, the orchestrator's restart, not this lane's). It loaded the new
  classes. MCP JVM probe (5 ms):
  `AsyncChannel#isResponseStarted` true, `#writeState` true,
  `HttpServer#savePendingInput` true, `ServerAtta#pendingWritesDrained` true,
  `WsAtta.closeStatus` true, code source
  `file:/Users/sean/src/seon/reference-code/http-kit/target/classes/`,
  `org.httpkit.server/write-state` resolves.
  - Race note: the class swap's `mv` finished at 08:52:13, one second after that JVM
    started. The probe shows the beta4 classes are loaded. The web server loads http-kit
    about 27 s into boot, after the swap.

## S2 — dev_cache.clj

- `dev_cache.clj` `discovery-form`: records each load when it starts; takes `root-lib`
  (`run-build!` passes `'seon.artifact`); now has a docstring and a contract (compiled
  against the packaged projection: `build-projection` over 3,373 packaged forms plus
  this contract, 214 ms, ok).
- Regression `seon.dev.dependency-cache-test/discovery-compiles-a-namespace-before-the-extension-it-tail-requires`
  (`:seon.test/platform`, because it compiles in two child JVMs, as the class-cache
  build does). It writes `cache-probe.uses-pprint` (loads clojure.pprint) and
  `cache-probe.pprint-root` (requires it and `babashka.process`), runs dev-cache's own
  discovery and compile forms and `validate!`, and asserts `babashka.process` precedes
  `babashka.process.pprint`.
  - The order assertion holds under the fork too: the fork's guard only acts while
    compiling, not during discovery.
  - The compile half proves the cycle is gone only once the classpath carries upstream
    process.
- Handed off, not edited (held files): the babashka-process submodule, `.gitmodules`
  and `deps.edn` (see the report).

## Swallowing catches in dev_cache.clj (left alone, listed per the rules)

These are not touched by this slice, and each returns nil/false on any Throwable:
- `read-manifest`
- `valid-cache`
- `selected-cache`
- `live-process-reference?`
- `read-live-process-references!` (its read)

`admit!`'s two catches handle declared cases. They are a separate defect class for the
dev-cache owner.

## Verification

- http-kit's own suite in a scratchpad clone of f56bbea: `./scripts/javac with-test && lein test :ci` →
  137 tests, 7,256 assertions, 0 failures, 0 errors. JUnit
  `org.httpkit.server.WriteStateTest org.httpkit.HttpKitServerTestSuite` → OK (73 tests).
- http-kit live in default: see S11 (probe of the loaded classes).
- Seon web delivery test through `write-package!` on default (new http-kit classes loaded):
  `bin/test-check default --test seon.render.web-test/accepted-socket-writes-have-a-loud-drain-backstop`
  → run efd2c09db33c, 1,558 ms, 3 pass / 1 fail. The 3 passes are: the drain backstop
  refuses with `:seon.await/future`, carries backstop 20, and closes the SSE generator.
  The fail is `web_test.clj:108`, which expects the member at
  `[:seon.error/data :seon.error/member]`. `seon.await/diagnostic` (`await.clj:40-43`,
  since a86e93e21) moves it to `:seon.await/requested-member`. That is a retired
  assumption in the test, not http-kit: the test replaces `http/send!` and
  `http/write-state` with `with-redefs` stubs. Out of this lane's files; reported to the lead.
- Adoption of this lane's files: refused twice with "Source changed during development
  adoption.". The changed paths were other lanes' dirty `src/seon/sci/eval.clj` and
  `test/seon/cluster/store_test.clj` (3.6 s, then 1.0 s; both over 1 s:
  `refresh-source!` publishes the whole dirty tree before refusing; the adoption owner's
  defect, not this lane's).
- S2 on default's JVM, in the throwaway namespace `fork-cleanup.dev-cache-probe`
  (removed after). The source is this `dev_cache.clj` with its `ns` renamed, and the body
  is the regression's, run through MCP eval (default pid 94821):
  - order `[cache-probe.pprint-root cache-probe.uses-pprint babashka.process babashka.process.pprint]`
  - both children exit 0, and `validate!` passes
  - discovery 844 ms, compile 721 ms
- S2 falsification against upstream 43bdd65 (shell JVM, dev-cache's own functions): see
  the top of this note. The parent file's order cycles; this change's order compiles.
- **Not run through `bin/test-check`:** the committed regression itself. That needs
  adoption of `dev_cache.clj` and the test, blocked as above. It is `:seon.test/platform`,
  so it also runs in `bin/test --platform` on the orchestrator's cadence.
- Full class-cache build (`clojure -T:dev-cache refresh`) not run. `target/` is empty
  (no cache exists today), and the digest keys on `deps.edn` bytes, which the deps.edn
  holder is about to change. Building now would be work thrown away at their edit. The
  first `ensure-cache` after the switch to upstream is the end-to-end proof.

RESET NEEDED: no.

## Timings

| operation | wall | why > 1 s |
|---|---|---|
| http-kit `scripts/javac with-test` (scratch clone) | 11 s | javac of all main + test Java plus `lein classpath` resolution; a dependency's own build, once per rebase, not a Seon operation (>10 s: dependency build tooling, not Seon's; no Seon issue) |
| http-kit `lein test :ci` | 55 s | the dependency's whole suite (137 tests, 7,256 assertions, real sockets and timeouts); allowed dependency test, run once |
| http-kit JUnit `WriteStateTest` + `HttpKitServerTestSuite` | 26 s | thread-pool and timer tests inside upstream's suite (ThreadPool3 alone 1.3 s); run once |
| `lein javac` for `target/classes` | 2 s | JVM start of leiningen plus javac |
| `git push` of the fork branch | 2 s | network round trip |
| dev-cache order probe (shell JVM, 2 × discovery + 2 × compile children) | 3.5 s | five JVM starts, about 0.5–0.7 s each; a platform-tier probe of AOT in a fresh JVM, which default cannot do without recompiling its own loaded namespaces |
| jcmd class census of default | 0.4 s | — |
| `bin/seon init --dev default --changed` (refused, other lanes' dirty files) ×2 | 3.6 s, 1.0 s | `seon.cluster/refresh-source!` 10.3 s inclusive ×3 and `call-with-projection` ×61 before the refusal: work proportional to the whole dirty tree, spent before it refuses. The adoption owner's defect |
| S2 regression body on default (throwaway ns) | 1.6 s | two child JVM starts (discovery 844 ms, compile 721 ms); AOT needs a fresh JVM |
| `bin/test-check --test …drain-backstop` | 1.6 s | 1,558 ms, one member on a branch of default; its setup, not the body |
