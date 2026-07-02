---
type: research
status: draft
tags: [research, agent]
---

# Tool research — `my.search` (grep/ripgrep over the repo)

## TL;DR

- **Recommendation: thin-wrap-existing-seon.** The floor already exists and is
  the right design: `seon.agent.search` (+ `.internal`) shells out to the
  ripgrep binary bundled by `@vscode/ripgrep` via `node:child_process/execFile`
  with `--json`, parses the JSON-lines event stream, gates roots through the
  `seon.agent.fs` allowlist, and returns a never-throw envelope. **No better
  Node.js option exists** — `@vscode/ripgrep` IS the de-facto standard (it is
  what VS Code itself uses), and pure-JS "ripgrep wrapper" npm libs are either
  thin shell-outs (same engine, worse parsing control) or abandoned. Pure-JS
  file-walk + regex (globby/fdir + `fs`) is materially slower and re-implements
  gitignore semantics rg gives for free.
- **The work is NOT a new engine. It is a RESHAPE + rename** to the catalog's
  composability backbone: a match becomes a `:seon.path/located`, the envelope
  uses `:seon.items/*`, and the error string becomes the shared `:seon.error/*`
  map. Do this **in place** in the existing floor parser/envelope (no `*-v2`),
  then expose a thin editable `my.search/grep` wrapper.
- **One high-value addition:** `:my.search/fixed?` → `-F/--fixed-strings`, so
  agents can pass a literal string and stop hitting the #1 footgun the current
  docstring warns about (un-escaped regex metachars `( ) [ ] { } .`).

## What already exists (the floor)

`src/seon/agent/search.cljs` + `src/seon/agent/search/internal.cljs` — the
project's self-described **EXEMPLAR npm-package wrapper**:

- npm boundary: `(.-rgPath (js/require "@vscode/ripgrep"))`, existence-checked
  lazily (`rg-path`), spawned via `cp/execFile` with argv (never `sh -c`).
- Caps: `:timeout 10000` (SIGTERM), `:maxBuffer (* 8 1024 1024)`,
  `:windowsHide true`, per-match line clip at 500 chars.
- Allowlist gate: `gate-path` delegates to `seon.agent.fs/stat` — search and
  read agree on reach; default roots = `seon.agent.fs/allowed-roots`.
- `--json` parser (`parse-match-line`): one event line → a match map or nil for
  begin/end/summary, unparsable cut-off lines, and non-UTF8 `bytes` events.
- Errors-as-values: every path RESOLVES to an envelope; exit-code handling is
  already correct (0 = matches, 1 = no-match SUCCESS, 2 = bad regex, timeout,
  ENOENT, maxBuffer-truncation → `truncated? true`).

Installed: `@vscode/ripgrep@1.18.0` (this build ships the rg binaries **in the
package** — its README states "no postinstall step and no runtime network
access"; `lib/index.js` resolves `rgPath` per `process.platform`/`arch`). So the
engine is fully reproducible and offline-safe. The bundled rg is used (NOT the
host's `/opt/homebrew/bin/rg`), which is the correct choice for determinism.

Tests: `test/seon/agent/search_test.cljs` exists (will need key renames when the
response reshapes).

Current response shape (the defect the catalog targets):

```clojure
{:seon.agent.search/ok? true
 :seon.agent.search/matches
 [{:seon.agent.search/path "<abs>" :seon.agent.search/line-number 83
   :seon.agent.search/line-text "(defn ^:async grep"} ...]
 :seon.agent.search/match-count 1 :seon.agent.search/truncated? false}
;; failure: {:seon.agent.search/ok? false :seon.agent.search/error "<string>"
;;          :seon.agent.search/raw-error "<string>"}
```

A hit's path key is `:seon.agent.search/path`, but `seon.agent.fs/read-file`
wants `:seon.agent.fs/path` — so a match cannot feed read-file without a manual
rekey (the docstring literally shows `"<:seon.agent.search/path of the hit>"`).
That rekey IS the friction the backbone removes.

## Options compared

### A. Keep `@vscode/ripgrep` + `execFile --json` (current) — RECOMMENDED

- **Pros:** the real ripgrep engine (fastest content search there is: parallel
  traversal, SIMD, gitignore-aware); `--json` gives structured, unambiguous
  events (no fragile line-splitting of `path:line:text` with colons-in-paths);
  binary bundled, no host dependency; argv spawn = no shell-injection surface;
  Microsoft-maintained, used by VS Code at scale. Already wrapped + gated here.
- **Cons:** spawns a process per call (~single-digit-ms overhead — irrelevant
  for an interactive agent tool, not a hot loop); `--json` stream must be parsed
  (already solved). No in-process API.
- **Verdict:** this is the out-of-the-box best. Keep it.

### B. Pure-JS "ripgrep wrapper" npm libs (`ripgrep-js`, `node-ripgrep`, the old `vscode-ripgrep`)

- `vscode-ripgrep` — the OLD name; superseded by `@vscode/ripgrep`. Don't use.
- `ripgrep-js` / `node-ripgrep` — thin wrappers that ALSO shell out to rg but
  return a pre-parsed array. You inherit their flag surface and parse decisions
  (often the human `path:line:col:text` format, which is colon-ambiguous), lose
  control over caps/timeout/gate, and they are sparsely maintained (multi-year
  gaps). Wrapping a wrapper to then re-gate + re-cap is strictly worse than
  driving the binary directly, which we already do.
- **Verdict:** no advantage over A; rejected.

### C. Pure-JS file-walk + regex (`fast-glob`/`globby`/`fdir` + `node:fs` line scan)

- **Pros:** zero native binary, pure JS, in-process.
- **Cons:** must re-implement `.gitignore`/`.ignore` semantics, hidden-file
  rules, binary-file detection, encoding handling, and multiline — all of which
  rg does correctly for free; and it is markedly slower on a real repo (no
  parallel traversal, no SIMD literal search). `fdir`/`fast-glob` are great at
  finding FILES but do not do content matching. Building a competent content
  grepper in JS is exactly the "roll our own" the task says to avoid.
- **Verdict:** rejected; only relevant if a no-native-deps constraint ever
  appears (it does not — the binary is already bundled and offline-safe).

## The rg `--json` event schema (verified live, rg 15.1.0)

Stream of newline-delimited JSON objects, one per event:

- `{"type":"begin","data":{"path":{"text":"<rel-or-abs path>"}}}`
- `{"type":"match","data":{ "path":{"text":...}, "lines":{"text":"<line>\n"},
  "line_number":<int>, "absolute_offset":<int>,
  "submatches":[{"match":{"text":"<hit>"},"start":<byte>,"end":<byte>}, ...]}}`
- `{"type":"end","data":{"path":..., "stats":{...}}}`
- `{"type":"summary","data":{"elapsed_total":..., "stats":{...}}}`

Key facts:

- **Column is FREE in JSON mode.** `submatches[0].start` is the **0-based BYTE
  offset within the line** of the first hit (`end` is the byte after it). You do
  NOT need `--column` (that flag only affects the human format). Caveat: it is a
  byte offset, so on non-ASCII lines byte ≠ codepoint column.
- **Non-UTF8 paths/lines** arrive as `path.bytes` / `lines.bytes` (base64)
  instead of `.text`. Current parser correctly skips these (text repos only).
- **Multiline:** without `-U`, each match event is one line and `line_number`
  is that line. With `-U/--multiline`, `lines.text` can hold a multi-line block
  and `line_number` is the START line. Keep multiline OFF by default — it keeps
  `:seon.path/located` a clean single-line shape.
- **One match event can carry multiple `submatches`** (several hits on a line).

## Best flag set for an agent tool

Current (good): `--json --no-config [-i] [--glob G] --regexp PAT -- ROOTS...`.
Recommended additions/notes:

- Keep `--no-config` (ignore host `RIPGREP_CONFIG_PATH` → reproducible).
- `-F/--fixed-strings` when `:my.search/fixed?` — pass the pattern literally,
  killing the regex-escaping footgun. (Mutually exclusive with treating it as a
  regex; pick by the flag.)
- Optional `-./--hidden` behind `:my.search/hidden?` — current default skips
  dotfiles/`.git`; expose opt-in rather than changing the default.
- Optional `-t/--type clojure` behind a future `:my.search/type` (cheaper +
  clearer than `--glob "*.clj*"` for language filters).
- Optional `-A/-B/-C` context behind `:my.search/context` — DEFER: it adds
  `context` event lines and complicates the single-line `located` shape; only
  add if agents ask. Per-file `-m/--max-count` similarly DEFER (global
  `:my.search/max-results` clip already exists and is enough).
- Do NOT add `--column` (redundant with `submatches`).
- Keep argv-only `execFile` (no `sh -c`) and `--` before roots (the existing
  injection-safe posture).

## Recommendation + composable map-in/map-out API

`wrap_or_build = thin-wrap-existing-seon` (with a small in-place reshape of the
floor parser/envelope — NOT a parallel namespace).

### Backbone shapes to register (protected `seon.*` substrate, FORMALIZE)

```clojure
;; PATH — owned by seon.path (the files↔search↔shell hinge)
(schema/register! :seon.path/abs     [:string {:min 1}])  ; absolute path
(schema/register! :seon.path/line    :int)                ; 1-based line
(schema/register! :seon.path/col     :int)                ; OPTIONAL 0-based byte col (submatch start)
(schema/register! :seon.path/preview :string)             ; the matching line / snippet
(schema/register! :seon.path/located
  [:map
   [:seon.path/abs     :seon.path/abs]
   [:seon.path/line    {:optional true} :seon.path/line]
   [:seon.path/col     {:optional true} :seon.path/col]
   [:seon.path/preview {:optional true} :seon.path/preview]])

;; ITEMS — self-describing collection mixin (referenced, never re-inlined)
(schema/register! :seon.items/items      [:vector :map])
(schema/register! :seon.items/count      :int)
(schema/register! :seon.items/truncated? :boolean)

;; RESULT — shared discriminator; :seon.error/* ALREADY exists on the floor (seon.db)
(schema/register! :seon.result/ok? :boolean)
;; reuse seon.db's error map: {:seon.error/message "<guiding>"
;;   :seon.error/data {:seon.error/kind :user-input|:core-bug} :seon.error/raw "<rg detail>"}
```

(`:seon.error/*` is confirmed registered in `seon.db`; `:seon.result/ok?` is new.)

### `my.search` public surface (one verb)

```clojure
;; ---- request: tool-specific payload is my.search/*, threaded scope is seon.path/* ----
(schema/register! :my.search/pattern           [:string {:min 1}])         ; regex (rg) by default
(schema/register! :my.search/paths             [:vector :seon.path/abs])   ; default = fs allowed roots
(schema/register! :my.search/glob              :string)                    ; e.g. "*.cljs"
(schema/register! :my.search/max-results       :int)                       ; default 100
(schema/register! :my.search/case-insensitive? :boolean)
(schema/register! :my.search/fixed?            :boolean)                    ; -F literal, skips regex-escape footgun
(schema/register! :my.search/hidden?           :boolean)                   ; -. include dotfiles

(schema/register! :my.search/grep-request
  [:map
   [:my.search/pattern                                :my.search/pattern]
   [:my.search/paths             {:optional true}     :my.search/paths]
   [:my.search/glob              {:optional true}     :my.search/glob]
   [:my.search/max-results       {:optional true}     :my.search/max-results]
   [:my.search/case-insensitive? {:optional true}     :my.search/case-insensitive?]
   [:my.search/fixed?            {:optional true}     :my.search/fixed?]
   [:my.search/hidden?           {:optional true}     :my.search/hidden?]])

;; ---- response: backbone-shaped, errors-as-values ----
(schema/register! :my.search/grep-response
  [:or
   [:map  ; success — items are :seon.path/located, thread straight into my.files
    [:my.search/ok?          [:= true]]
    [:seon.items/items       [:vector :seon.path/located]]
    [:seon.items/count       :seon.items/count]
    [:seon.items/truncated?  :seon.items/truncated?]]
   [:map  ; failure — shared error map, never throws/rejects
    [:my.search/ok?          [:= false]]
    [:seon.error/message     :string]
    [:seon.error/data        {:optional true} :map]   ; {:seon.error/kind :user-input|:core-bug}
    [:seon.error/raw         {:optional true} :any]]])

(defn ^:async grep
  "Search file CONTENTS under the my.files allowed roots (ripgrep). ALWAYS
   resolves to a :my.search/grep-response (never rejects; errors are values).
   :my.search/pattern is a regex unless :my.search/fixed? is true. No matches
   is SUCCESS with an empty :seon.items/items. Each hit is a :seon.path/located
   that feeds my.files/read-file with zero rekey."
  {:malli/schema [:=> [:cat :my.search/grep-request] :my.search/grep-response]}
  [req] ...)
```

A success item:

```clojure
{:seon.path/abs     "/abs/path/to/file.cljs"   ; absolute + allowlisted
 :seon.path/line    83                          ; 1-based (rg line_number)
 :seon.path/col     1                           ; OPTIONAL submatch start (byte offset)
 :seon.path/preview "(defn ^:async grep"}       ; trimmed, clipped at 500 chars
```

### Threading (the move every chain is a variant of) — no rekey anywhere

```clojure
(->> (my.search/grep {:my.search/pattern "defn \\^:async"}) ; -> {ok? items[located]}
     :seon.items/items                                       ; vector of :seon.path/located
     (filter #(str/ends-with? (:seon.path/abs %) ".cljs"))   ; items are maps → filterable
     (map my.files/read-file)                                ; located feeds read-file directly
     ...)
```

`my.files/read-file` accepts a `:seon.path/located` (reads `:seon.path/abs`, and
can use `:seon.path/line` as `from-line`). The current docstring's manual
`"<:seon.agent.search/path of the hit>"` rekey DELETES.

### Where the reshape lives (no second code path)

Do the reshape **in place** in the existing floor:

- `internal/parse-match-line` → emit `:seon.path/located` (abs/line/col/preview)
  instead of the `:seon.agent.search/path|line-number|line-text` keys.
- `internal/success-from` + `ok-empty` → `:seon.items/items|count|truncated?`.
- `internal/fail` → the `:seon.error/*` map (carry `:seon.error/kind
  :user-input` for bad-regex/denied-path, `:core-bug` for the unexpected-catch
  branch) instead of `:seon.agent.search/error|raw-error` strings.
- `my.search/grep` = thin editable wrapper that maps `:my.search/*` request keys
  onto the floor call, owns the editable defaults (default roots via my.files,
  default max-results, fixed?/hidden? flag assembly), and returns the floor's
  already-backbone-shaped response. Update `search_test.cljs` keys in the same
  patch.

## Gotchas

- **Column is a BYTE offset, not a char column** (`submatches[].start`). Fine for
  ASCII source; document the caveat for non-ASCII lines. Mark `:seon.path/col`
  optional so the located shape stays valid even if you choose not to surface it.
- **Non-UTF8 hits are silently dropped** (`bytes` events skipped). Acceptable for
  a text-repo agent; note it so nobody debugs "missing" binary-file matches.
- **`pattern` is a regex by default** — the historical footgun. `:my.search/fixed?`
  (`-F`) is the cure; keep the docstring warning for the regex path.
- **Multiline off by default** — keeps `:seon.path/located` single-line. Only add
  `-U` if a real need appears; it changes `lines.text`/`line_number` semantics.
- **gitignore is relative to the search ROOT** (existing, documented): repo-root
  search skips `node_modules`/`out`/`tmp`, but an explicitly-granted dir is fully
  searchable when passed as a root. `.git`/dotfiles need `:my.search/hidden?`.
- **Exit codes:** 0 matches, 1 = no-match SUCCESS (don't treat as error), 2 =
  real error (bad regex/IO). Already handled — preserve when reshaping.
- **maxBuffer truncation** (`ERR_CHILD_PROCESS_STDIO_MAXBUFFER`) → parse the
  partial stdout and set `:seon.items/truncated? true` (existing behavior). Keep.
- **Use the BUNDLED rgPath**, never the host `rg` — reproducibility. The 1.18.0
  package here bundles binaries (no postinstall/network), so a broken install
  surfaces as `rg-path` → nil → a guiding envelope, not a crash.
- **Process-per-call** overhead is negligible for an interactive tool; do NOT
  build a daemon/persistent-rg optimization (premature).
- **Caps are tunable, not load-bearing for security** — this is a soft boundary
  against LLM accidents (the real isolation is the process + the fs allowlist).

## Sources

- Live verification on this machine: `rg --json` output schema (rg 15.1.0), the
  `submatches`/`absolute_offset`/`line_number` fields, exit-code behavior.
- `node_modules/@vscode/ripgrep@1.18.0` README — `rgPath` resolution, "no
  postinstall step and no runtime network access" (binaries bundled).
- ripgrep man page / GUIDE.md (the JSON format is documented under
  `--json`: begin/match/end/summary event types; `submatches` byte offsets).
- The existing exemplar: `src/seon/agent/search.cljs` +
  `src/seon/agent/search/internal.cljs`.
- `docs/prds/agent-fsm/toolkit-catalog.md` — §"composability backbone" (PATH /
  REF / ITEMS / RESULT) and the `my.search` FORMALIZE entry.
- `seon.db` (`src/seon/db.cljs:146-151`) — the existing `:seon.error/*` map this
  reuses; `:seon.result/ok?` is the one net-new backbone registration.
- Gemini CLI (`agy`) was attempted but returned no output in this sandbox (no
  network/auth); findings above rest on primary sources (live rg + installed
  package + repo code), not model recall.
