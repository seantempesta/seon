---
type: research
status: draft
tags: [research, agent]
---

# Tool research — `my.shell` (run a command, capture {exit, out, err} with a timeout)

## TL;DR

- **Recommendation: build the new `seon.agent.shell` floor on the Node builtin
  `node:child_process.execFile` (Promisified to ALWAYS-resolve), copying the
  proven `seon.agent.search.internal` exec wrapper byte-for-byte in shape, and
  reusing `seon.agent.fs/stat` as the cwd gate. Do NOT add `execa`.**
  Verdict in the catalog's terms: **hybrid** — the floor is genuinely new
  (`my.shell` is the one missing surface), but it is NOT rolled from scratch:
  it thin-wraps the existing `seon.agent.fs` allowlist gate and clones the
  existing `execFile` pattern. No npm dependency is added.
- `execa` (the obvious "out-of-the-box" candidate) LOSES here for three concrete
  reasons specific to this codebase: (1) it has been **ESM-only since v6** (latest
  is v9), and the pod is a shadow-cljs CommonJS Node bundle that loads npm via
  `js/require` / `:require` — a pure-ESM dep forces dynamic `import()` interop,
  exactly the build fragility seon avoids; (2) it **throws/rejects on non-zero
  exit by default**, fighting the errors-are-values contract (you'd set
  `reject:false` everywhere); (3) it is a new dependency for something the builtin
  does natively and the codebase **already wraps, proven-good**, in
  `seon.agent.search.internal`. "One unified pattern / don't be a dumbass" points
  straight at reusing that wrapper.
- `babashka.process` cannot run here (JVM/bb only, no JVM in the pod) — but its
  **data-map API design is the muse**: map-in (`:dir` `:in` `:out` `:err` `:env`
  `:timeout`), map-out with `:exit` **always present**, result is plain data you
  destructure. We imitate the design, not the code.
- **One spec refinement (the only real design change):** in the catalog the
  `ok? true` branch implies "exit 0," which would push a non-zero exit onto the
  `ok? false` (error) branch. That is wrong for a shell tool — **a non-zero exit
  IS a legitimate answer** (a formatter found issues, a test failed, `git diff`
  found changes). Redefine `ok?` as **"the process RAN and we have exit/out/err"**
  (any exit code), and reserve `ok? false` for **"could not run at all"** (cwd
  denied, `SEON_SHELL` ungranted, binary-not-found/spawn `ENOENT`, internal
  error). This mirrors `seon.agent.search` exactly, where rg exit 1 (no matches)
  is SUCCESS, not an error — and keeps `:seon.shell/exit` a top-level int the
  agent threads directly.

## Capability

Run a real command — a formatter, a one-off `node`/`python` script, a `git`
query — and get `{exit out err}` back as DATA, with a timeout, never throwing.
Argv-based (no `sh -c` injection surface). cwd gated by the fs allowlist; whole
capability gated by a `SEON_SHELL` host grant (default-deny, same posture as
`SEON_FS_*`). Soft boundary against LLM accidents, not a security boundary.

## What seon already has (the exemplar to clone)

`seon.agent.search.internal` (`src/seon/agent/search/internal.cljs`) is THE
npm-wrapper exemplar and it ALREADY uses `node:child_process.execFile` exactly
the way `my.shell` needs:

```clojure
(.execFile cp bin (into-array args)
           #js {:timeout     timeout-ms        ; SIGTERM after this long
                :maxBuffer   max-output-bytes   ; output cap → partial still returned
                :windowsHide true}
           (fn [err stdout stderr]
             (resolve #js {:err err :stdout stdout :stderr stderr})))
```

…wrapped in a `js/Promise.` that **always resolves** (never rejects). The
caller (`seon.agent.search/grep`) then classifies the `err` object:

- `(.-killed err)` ⇒ timed out (execFile killed the child via SIGTERM).
- `(= "ENOENT" (.-code err))` ⇒ binary failed to spawn.
- `(= "ERR_CHILD_PROCESS_STDIO_MAXBUFFER" (.-code err))` ⇒ output cap hit; the
  partial stdout is STILL parsed (`truncated? true`).
- `(= 1 (.-code err))` ⇒ rg's "no matches" — treated as SUCCESS, not an error.
- otherwise ⇒ a real failure envelope.

The cwd/roots gate is delegated, never reimplemented: `gate-path` calls
`seon.agent.fs/stat` and turns an out-of-scope path into an `ok? false`
envelope. `my.shell` reuses this verbatim for `:seon.shell/cwd`.

So `my.shell`'s floor is "`search.internal` with the rg-specific parsing swapped
for generic exit/out/err classification, and `roots` → a single gated `cwd`."
That is the strongest possible argument against a new library: the codebase's own
best-in-class wrapper is the template.

## Options compared

### 1. `node:child_process.execFile` (Promisified) — RECOMMENDED

`execFile(cmd, args, opts, cb)` — no shell, argv only (no injection surface),
PATH-resolves `cmd` on POSIX (macOS/Linux — seon's target). The callback gives
`(error, stdout, stderr)`:

- On exit 0: `error` is `null`; `stdout`/`stderr` are the buffered output.
- On non-zero exit: `error.code` is the **numeric exit code**; output still
  delivered.
- On timeout: execFile sends `killSignal` (default `SIGTERM`) →
  `error.killed === true`, `error.signal === 'SIGTERM'`, `error.code === null`;
  whatever was buffered so far IS delivered (honest partial output).
- On output overflow: `error.code === 'ERR_CHILD_PROCESS_STDIO_MAXBUFFER'`;
  output truncated to `maxBuffer`.
- On binary-not-found: `error.code === 'ENOENT'`.

Pros: zero deps, CJS-native (no ESM headache), the exact wrapper already lives in
the repo, full control over the never-reject envelope, honest `timed-out?` /
truncation. Cons: a touch more classification code than execa's `reject:false`
(but that code already exists in `search.internal` and is small); cross-platform
Windows quirks (irrelevant — seon is macOS/Linux); **stdin needs one extra line**
(see gotchas).

### 2. `execa` (npm) — REJECTED for this codebase

`execa('cmd', [args], {reject:false, timeout, killSignal, maxBuffer, input, cwd,
windowsHide})` returns `{exitCode, stdout, stderr, timedOut, signal, failed}`.
The API shape is genuinely nice — `timedOut` is a first-class field, `reject:false`
gives an errors-as-values mode, `input` feeds stdin directly. But:

- **ESM-only since v6 (latest v9).** The pod is a shadow-cljs CommonJS bundle
  (`js/require`, `:require ["pkg"]`). A pure-ESM dep is not a plain `require` — it
  needs `await import('execa')` interop and a build that tolerates ESM-in-CJS.
  That is precisely the kind of bundler fragility seon has been bitten by before
  (cf. the WASM build-nonreproducible note). Staying on execa v5 (last CJS) to
  dodge this means shipping an EOL major — worse than the builtin.
- **Throws by default** (`reject:true`); you must remember `reject:false` on every
  call or it breaks the never-throw contract.
- **A new dependency** for a capability the builtin covers and the repo already
  wraps. Violates "don't roll a parallel version of something that exists."

execa would only win if we needed `.pipe()` chains, template-string command
parsing, cross-platform shell ergonomics, or `which`-style resolution — none of
which an argv-only, never-throw, single-command capability needs.

### 3. `babashka.process` — CANNOT RUN (JVM/bb only); imitate the DESIGN

No JVM in the pod, so this is design inspiration only. The good ideas worth
imitating (and we already do):

- **Data-map in, data-map out.** `(sh {:dir d :in stdin :timeout ms} "cmd" arg)`
  → `{:exit n :out s :err s}`. Plain data you destructure — no object state.
- **`:exit` is ALWAYS in the result** (you check it yourself; `sh` only throws on
  launch failure unless `:continue`). This is the direct precedent for the spec
  refinement above: exit/out/err is the universal contract, returned regardless
  of exit code.
- **`:dir` (cwd), `:in` (stdin), `:timeout`, `:env`** — the exact knob set the
  catalog already lists (`:seon.shell/cwd` `:seon.shell/stdin`
  `:seon.shell/timeout-ms`).
- `process` (async, returns immediately, deref for exit) vs `sh`/`shell`
  (blocking, captures). `my.shell/run` is the `sh`-flavor, async-resolved.

## Recommended agent-facing API (map-in / map-out, threadable)

One verb, `^:async run`, mirroring `seon.agent.search/grep`. All keys
`:seon.shell/*`; the THREADING shape is `:seon.path/abs` (the shared PATH
backbone), so a `:seon.path/located` from `grep`/`list-dir` feeds `cwd` with no
rekey, and `:seon.shell/out` threads into transforms → `db/transact!` → another
run's stdin.

```clojure
;; Request — argv only, never a shell string.
(schema/register! :seon.shell/cmd        [:string {:min 1}])  ; argv[0], PATH-resolved
(schema/register! :seon.shell/args       [:vector :string])   ; argv[1..]
(schema/register! :seon.shell/cwd        :seon.path/abs)       ; gated by seon.agent.fs/stat
(schema/register! :seon.shell/stdin      :string)             ; fed to the child's stdin
(schema/register! :seon.shell/timeout-ms :int)                ; default 30000

(schema/register! :seon.shell/run-request
  [:map
   [:seon.shell/cmd        :seon.shell/cmd]
   [:seon.shell/args       {:optional true} :seon.shell/args]
   [:seon.shell/cwd        {:optional true} :seon.shell/cwd]
   [:seon.shell/stdin      {:optional true} :seon.shell/stdin]
   [:seon.shell/timeout-ms {:optional true} :seon.shell/timeout-ms]])

;; Result — exit/out/err is the universal shell contract; always present when it RAN.
(schema/register! :seon.shell/ok?        :boolean)   ; "the process RAN", NOT "exit 0"
(schema/register! :seon.shell/exit       :int)       ; the real exit code (may be non-zero)
(schema/register! :seon.shell/out        :string)
(schema/register! :seon.shell/err        :string)
(schema/register! :seon.shell/timed-out? :boolean)
(schema/register! :seon.shell/truncated? :boolean)   ; maxBuffer cap hit (honest)

(schema/register! :seon.shell/run-response
  [:or
   ;; RAN — exit/out/err is the answer, whatever the exit code.
   [:map [:seon.shell/ok?        [:= true]]
         [:seon.shell/exit       :seon.shell/exit]
         [:seon.shell/out        :seon.shell/out]
         [:seon.shell/err        :seon.shell/err]
         [:seon.shell/timed-out? :seon.shell/timed-out?]
         [:seon.shell/truncated? {:optional true} :seon.shell/truncated?]]
   ;; COULD-NOT-RUN — gate/spawn failure (cwd denied, SEON_SHELL ungranted,
   ;; ENOENT, internal error). Shared error map, never a bare string.
   [:map [:seon.shell/ok?     [:= false]]
         [:seon.error/message :string]
         [:seon.error/data    {:optional true} :map]]])

(defn ^:async run
  "Run a command (argv, no shell, no injection surface). ALWAYS resolves.
   ok? = the process executed (read :seon.shell/exit for success/failure — a
   non-zero exit is a legitimate answer, not an ok?-false). SIGTERM at
   timeout-ms (default 30s, then timed-out? true), bounded maxBuffer
   (truncated? true), windowsHide. cwd is gated by the seon.agent.fs allowlist;
   the whole verb is default-deny until the host grants SEON_SHELL."
  {:malli/schema [:=> [:cat :seon.shell/run-request] :seon.shell/run-response]}
  [m] ...)
```

### The composability move

```clojure
;; grep/list a dir → use a hit's abs path as cwd → run → thread out into the DB
(let [{:keys [:seon.shell/ok? :seon.shell/exit :seon.shell/out]}
      (await (shell/run {:seon.shell/cmd "git"
                         :seon.shell/args ["status" "--porcelain"]
                         :seon.shell/cwd "/Users/me/src/proj"}))]   ; :seon.path/abs in
  (when (and ok? (zero? exit))
    (->> (str/split-lines out)                                       ; out → items
         (map parse-porcelain-line)
         (hash-map :seon.db/tx-data)
         db/transact!)))                                            ; → {:seon.db/ok? …}
```

`:seon.shell/cwd` references `:seon.path/abs`, so a `:seon.path/located` from
`my.search/grep` or `my.files/list-dir` threads in with no reshape;
`:seon.shell/out`/`err` are plain strings that split → transform → persist.

## Gotchas / decisions

- **`ok?` ≠ exit 0 (the headline refinement).** Treat a non-zero exit as SUCCESS
  with a non-zero `:seon.shell/exit` (exactly like rg-exit-1-is-no-matches in
  `search`). Only "could not run" is `ok? false`. Otherwise the agent has to
  fish the exit code out of an error string — breaking the universal contract.
- **stdin needs one extra line with `execFile`.** `execFile`/`exec` do NOT accept
  an `input` option (only the `*Sync` variants do). `execFile` returns the
  `ChildProcess`, so feed stdin by writing to it:
  `(let [child (.execFile cp …)] (when stdin (doto (.-stdin child) (.write stdin) (.end))))`.
  If large/streaming stdin ever matters, switch the floor to `spawn` + manual
  chunk collection (more control over partial output and a self-enforced
  maxBuffer) — but `execFile` + `child.stdin` is enough for the spec and keeps
  the search-exemplar shape.
- **Timeout exit code.** A SIGTERM-killed child has `error.code === null`. Report
  `:seon.shell/timed-out? true` as the authoritative flag and set
  `:seon.shell/exit` to a deterministic sentinel — recommend **143** (128 +
  SIGTERM(15), the POSIX-shell convention) or 124 (GNU `timeout`). The agent keys
  off `timed-out?`, not the sentinel.
- **SIGTERM may not reap a process tree.** A child that forks grandchildren can
  survive SIGTERM. Argv-only (no `sh -c`) makes deep trees rare; if it becomes a
  problem, `spawn` with `detached:true` + `process.kill(-pid)` kills the group,
  and escalate SIGTERM→SIGKILL after a grace window. Out of scope for v1; note it.
- **maxBuffer overflow code string** is `ERR_CHILD_PROCESS_STDIO_MAXBUFFER`
  (match `search.internal`); partial output is still delivered → set
  `truncated? true`. Pick a cap in the floor (search uses 8 MiB) and keep it
  `windowsHide true`.
- **PATH resolution.** `execFile` PATH-resolves `cmd` on POSIX (no absolute path
  needed for `git`/`node`/`python`). Binary-not-found surfaces as `ENOENT` →
  `ok? false` "command not found". Windows resolution is flakier — irrelevant for
  seon's target.
- **Default-deny via `SEON_SHELL`.** Mirror `SEON_FS_*`: with no grant, every
  `run` returns `ok? false` pointing the agent at the host grant. cwd additionally
  gated through `seon.agent.fs/stat` (reuse, never reimplement).
- **Errors are values, the map not a string.** The `ok? false` branch uses
  `:seon.error/message` (+ optional `:seon.error/data` carrying
  `:seon.error/kind`), per the RESULT backbone — not the bare string the older
  `fs`/`search` floors used.

## Sources

- `src/seon/agent/search/internal.cljs` — the in-repo `execFile` exemplar
  (timeout, maxBuffer, windowsHide, always-resolve Promise, error classification).
- `src/seon/agent/search.cljs` — the wrapper contract (map-in/map-out,
  errors-are-values, exit-1-is-success precedent for the `ok?` refinement).
- `src/seon/agent/fs.cljs` (`stat`, `grants`, `configure!`, default-deny,
  `out-of-scope?`) — the cwd allowlist gate `my.shell` reuses.
- `docs/prds/agent-fsm/toolkit-catalog.md` §`my.shell` + the four shared shapes
  (PATH / REF / ITEMS / RESULT) — the spec being refined.
- Node.js `child_process` docs (knowledge, Jan-2026 cutoff): `execFile` callback
  `(error, stdout, stderr)`; `error.code`/`error.killed`/`error.signal`;
  `ERR_CHILD_PROCESS_STDIO_MAXBUFFER`; `input` only on `*Sync`.
- `execa` (knowledge): ESM-only since v6 (latest v9), `reject` default true,
  `{timedOut, exitCode, signal}` result, `input`/`reject:false` options.
- `babashka.process` (knowledge): `process`/`sh`/`shell` data-map API —
  `:dir`/`:in`/`:out`/`:err`/`:env`/`:timeout` in, `:exit`/`:out`/`:err` out,
  `:exit` always present.
- NOTE: the project Gemini CLI (`agy`) returned no output / timed out in this
  session; the comparison rests on the in-repo exemplar + model knowledge.
