---
type: research
status: draft
tags: [research, agent]
---

# my.files — best out-of-the-box implementation (research)

Research for the agent toolkit catalog tool **my.files**: read / write / list /
walk / stat files behind a default-deny allowlist, on the shared `:seon.path/*`
shape. Question asked: wrap a library, thin-wrap the existing seon floor, build
fresh, or hybrid — and what is the idiomatic, composable, map-in/map-out agent
surface.

## TL;DR

- **Verdict: `thin-wrap-existing-seon`.** The capability already exists, fully
  built and live-proven, as `seon.agent.fs` (+ `seon.agent.fs.internal`):
  default-deny allowlist, `..`-traversal resolution, paged reads, recursive
  walk, stat, `SEON_FS_*` env grant + lock. **Do NOT add an npm fs library** and
  **do NOT build fresh.** Build `my.files` as the thin, editable `:toolkit-seed`
  wrapper the catalog specifies; keep the node:fs syscalls + the allowlist on the
  `:core-seed` floor.
- **No npm fs library earns its place here.** `fs-extra` sells copy/move/ensureDir
  (we need none) and promise APIs (we must stay SYNC — see below). `graceful-fs`
  sells EMFILE queueing under heavy concurrent I/O (irrelevant to one agent doing
  occasional reads). `globby`/`fast-glob` are async-first and would duplicate the
  reach of the `my.search`/`@vscode/ripgrep` floor. Native `node:fs` sync builtins
  already cover 100% of the surface with zero deps.
- **Sync is a hard constraint, not a preference.** `fs/internal.cljs` (lines
  24-29) documents it: the eval loop's auto-await fires only on a form's OUTERMOST
  value, so a Promise-returning fs op binds `r` to a Promise and
  `(:seon.path/abs r)` returns nil → wrong branch in a `let`. This single fact
  eliminates every promise-first library (fs-extra, globby, fast-glob,
  node:fs/promises) for the floor. `my.files` verbs must stay **sync** (unlike
  `my.search`/`my.shell`, which are `^:async` because they're called as a top-level
  form).
- **babashka.fs is API-DESIGN inspiration only** (it is JVM-only, `java.nio`). Take
  its verb VOCABULARY (`exists?`/`directory?`/`regular-file?`/`list-dir`/`walk`/
  `glob`/`read-all-lines`/`home`), not its positional + `Path`-returning calling
  convention. Seon stays map-in / map-out returning `:seon.path/located` maps.
- **The real work is the backbone reshape, not the I/O.** Rename to `my.files`,
  rekey requests on the shared `:seon.path/abs`, turn `list-dir`/`walk-dir`'s bare
  filename strings into `:seon.items/items` of `:seon.path/located`, and swap the
  plain `:seon.agent.fs/error` STRING for the shared `:seon.error/*` MAP. That is
  what makes `grep → read` / `list → stat` thread with no rekey — the catalog's
  headline composability fix.

## What already exists (read first)

| Surface | File | What it is |
|---|---|---|
| Public verbs | `src/seon/agent/fs.cljs` | `grants`, `configure!`, `read-file` (paged), `write-file`, `list-dir`, `stat`, `file-exists?`, `home-dir`, `walk-dir`. Map-in/map-out, sync, never-throws, default-deny. |
| Plumbing | `src/seon/agent/fs/internal.cljs` | allowlist (`!config`, env-bootstrap from `SEON_FS_ROOT`/`SEON_FS_READ_ONLY`/`SEON_FS_LOCK`), `out-of-scope?` / `under-root?` / `resolve-abs` (node:path `..` resolution), `page-lines`, `walk-dir-recursive!`. |
| Allowlist consumer | `src/seon/agent/search.cljs` + `.../search/internal.cljs` | `my.search`'s floor — already gates every search root through `fs/stat`, so search and read agree on reach. THE EXEMPLAR npm wrapper shape. |
| Error map | `src/seon/error.cljs` | `seon.error/->map` → `{:seon.error/message :seon.error/data :seon.error/raw …}` — the shared error shape the RESULT backbone wants. |

The floor is solid and live-proven. The deliberate choices to respect: **sync**
(eval-await constraint), the **allowlist as a soft LLM-accident boundary** (not a
security boundary), and the stubbed **`:wasi` branch** for the future WASI
convergence (any change must keep both `:node` and `:wasi` arms).

## Options compared

### Option A — node:fs sync builtins (the current floor). RECOMMENDED basis.

- **Coverage:** `readFileSync`/`writeFileSync`/`readdirSync`/`statSync` + a
  hand-rolled `walk-dir-recursive!` already cover read/write/list/stat/walk 100%.
- **Deps:** zero. Already a dependency of everything.
- **Sync:** native — matches the hard eval-await constraint.
- **Node 24 (live-probed `node v24.2.0`): `fs.globSync`, `fs.opendirSync`,
  `fs.cpSync`, and `readdirSync(dir,{recursive:true,withFileTypes:true})` are all
  present.** So even glob + recursive-walk are now builtins.
- **Gotcha that VINDICATES the hand-rolled walk:** `readdirSync` with
  `recursive:true` AND `withFileTypes:true` combined has a long-standing bug
  (entries missing / missing fields — nodejs/node #48858, #51773). The current
  walk avoids it by stat-ing each entry as it descends, and it needs that stat
  anyway for `dir?`/`file?` + skip-hidden + the truncation cap. Keep it.
- **Verdict:** the right substrate. The only enrichment worth making is on
  `list-dir`: switch its single `readdirSync(path)` to
  `readdirSync(path,{withFileTypes:true})` so the entry TYPE (`dir?`/`file?`)
  comes back in ONE syscall instead of N follow-up stats in the wrapper.

### Option B — fs-extra. REJECT.

- Adds `copy`/`move`/`remove`/`ensureDir`/`emptyDir`/`outputFile`/`readJson` +
  promise support, layered on `graceful-fs` + `jsonfile` + `universalify`.
- We need NONE of those verbs (the agent loop is read/list/walk/stat/write). Sync
  variants exist (`copySync` etc.), but the value proposition is conveniences we
  don't use plus a promise API we can't use.
- Adds a transitive dep tree to gate + audit for a `:core-seed` floor, buying
  nothing the catalog asks for. **Reject** — revisit only if/when the agent grows a
  real copy/move/ensure-parent-dirs need (then adopt the specific `*Sync` verb,
  not the whole lib).

### Option C — graceful-fs. REJECT (for now).

- Drop-in `node:fs` replacement that QUEUES operations on `EMFILE`/`EAGAIN`
  (too-many-open-FDs) — its single reason to exist. Same API, sync variants.
- Real value only under HIGH concurrent FD pressure (build tools, servers,
  serverless fan-out). A single agent doing occasional sync reads never approaches
  the FD ceiling.
- It IS a clean one-line floor swap (`(:require ["graceful-fs" :as fs])`) if FD
  exhaustion ever shows up in a live drive — note it as a contingency, don't adopt
  speculatively. **Reject for now.**

### Option D — globby / fast-glob. REJECT.

- Async-first glob (`globby` is promise-only; `fast-glob.sync` exists but the
  project is built around the async path).
- Glob/content-reach is ALREADY the `my.search` floor's job (`@vscode/ripgrep`,
  which also does filename globbing). A glob lib here would duplicate that reach
  and split "where do I find files" across two tools. `walk-dir`'s `match-ext` +
  Node 24 `globSync` cover the rare in-`my.files` glob need. **Reject.**

### Inspiration — babashka.fs (NOT an implementation option; JVM-only).

`babashka.fs` wraps `java.nio.file` and CANNOT run in CLJS-on-Node. Borrow its
NAMING + the predicate ergonomics, map onto node:fs sync:

| babashka.fs | maps to | my.files |
|---|---|---|
| `exists?` | `statSync` ok? | `file-exists?` (bare boolean) |
| `directory?` / `regular-file?` | `stat.isDirectory/isFile` | `:my.files/dir?` / `:my.files/file?` on stat + located items |
| `list-dir` | `readdirSync` | `list-dir` → ITEMS of located |
| `walk` / `glob` | recursive readdir | `walk-dir` (+ `match-ext`) |
| `read-all-lines` / `read-all-string` | `readFileSync` + paging | `read-file` (paged) |
| `home` | `process.env.HOME` | `home-dir` |
| `absolutize` / `canonicalize` | `path.resolve` | internal `resolve-abs` |
| `copy` / `move` / `delete` | — | OUT of scope (not in the catalog surface) |

The lesson taken: a clean, discoverable predicate + verb vocabulary. The lesson
left behind: positional args returning opaque `Path` objects — seon threads
namespaced maps.

## Recommendation: thin-wrap-existing-seon

Build `my.files` as the catalog's thin `:toolkit-seed` wrapper over the unchanged
`:core-seed` `seon.agent.fs` floor. The wrapper's whole job is **shape
translation onto the four backbone shapes** (PATH / REF / ITEMS / RESULT); the
syscalls + allowlist stay on the floor.

Two small, justified floor touch-ups (still `:core-seed`, done once):

1. **`list-dir` → `readdirSync(path,{withFileTypes:true})`** so the floor emits
   per-entry type (the wrapper must not do N stats). `walk-dir` already stats each
   entry during descent — have it carry `dir?`/`file?` out instead of discarding
   them. This is "the floor owns syscalls"; the wrapper stays pure data-reshape.
2. **Errors through `seon.error/->map`** with a classified `:seon.error/kind`
   (scope-denied / read-only → `:user-input`; caught node error e.g. `ENOENT` →
   `:io`/`:not-found`), replacing the bare `:seon.agent.fs/error` string. Keep a
   human message at `:seon.error/message` so a string read still works.

Why not the alternatives, in one line each: **wrap-lib** — no npm fs lib fits the
sync constraint or adds a verb we use; **build-fresh** — the allowlist + `..`
resolution + paging + walk are exactly the subtle code you don't want to rewrite,
and they're live-proven; **hybrid** — there's nothing to hybridize with (no lib in
the mix).

## Proposed agent-facing API (map-in / map-out, sync, never-throws)

Backbone shapes the wrapper REFERENCES (registered on the floor, per the catalog
§"composability backbone"):

```clojure
:seon.path/abs      [:string {:min 1}]      ; an absolute path — the threading key
:seon.path/line     :int
:seon.path/preview  :string
:seon.path/located  [:map [:seon.path/abs :seon.path/abs]
                          [:seon.path/line    {:optional true} :seon.path/line]
                          [:seon.path/preview {:optional true} :seon.path/preview]]
:seon.result/ok?    :boolean                 ; the shared discriminator shape
:seon.items/items   [:vector :map]           ; each item self-describing
:seon.items/count   :int
:seon.items/truncated? :boolean
;; :seon.error/* already on the floor (seon.error / seon.db)
```

The threading trick: every request keys on `:seon.path/abs`, and a
`:seon.path/located` IS a map carrying `:seon.path/abs` (+ optional line/preview).
So a grep match or a listing entry feeds `read-file`/`stat` DIRECTLY — extra keys
are ignored, no rekey. `:my.files/ok?` is registered AS `:seon.result/ok?`
(keeps the data-ns on the keyword, references the shared shape).

```clojure
(ns my.files) ; :toolkit-seed — renders full every turn, agent-editable

;; --- grant introspection / mutation (delegates to the floor) ---
(defn grants [_]
  #_"-> {:my.files/ok? true
         :seon.path/roots [:seon.path/abs …]   ; rekeyed so my.search reuses them
         :my.files/read-only? <bool> :my.files/locked? <bool>}")

(defn configure! [m]
  #_"{:seon.path/roots [abs…] :my.files/read-only? <bool>} -> grants-shape
     (no-op error envelope when SEON_FS_LOCK)")

;; --- read / write (request keys on :seon.path/abs) ---
(defn read-file
  #_"{:seon.path/abs <p>                       ; ACCEPTS a :seon.path/located as-is
      :my.files/from-line <int> :my.files/max-lines <int> :my.files/encoding <s>}
     -> {:my.files/ok? true :seon.path/abs <p> :my.files/content <s>
         :my.files/from-line <int> :my.files/lines-returned <int>
         :my.files/total-lines <int>}                       ; honest paging totals
      | {:my.files/ok? false :seon.error/message <s> :seon.error/data {…}}"
  [m] )

(defn write-file
  #_"{:seon.path/abs <p> :my.files/content <s>}
     -> {:my.files/ok? true :seon.path/abs <p>} | error envelope"
  [m] )

;; --- list / walk (RETURN :seon.items/items of :seon.path/located) ---
(defn list-dir
  #_"{:seon.path/abs <dir>}
     -> {:my.files/ok? true
         :seon.items/items [{:seon.path/abs <child> :my.files/name <s>
                             :my.files/dir? <b> :my.files/file? <b>} …]
         :seon.items/count <int> :seon.items/truncated? false}"
  [m] )

(defn walk-dir
  #_"{:seon.path/abs <dir> :my.files/match-ext \".md\"
      :my.files/skip-hidden <b> :my.files/max-results <int>}
     -> {:my.files/ok? true
         :seon.items/items [{:seon.path/abs <file> :my.files/dir? false
                             :my.files/file? true} …]
         :seon.items/count <int> :seon.items/truncated? <b>}"
  [m] )

;; --- stat / predicate / home ---
(defn stat
  #_"{:seon.path/abs <p>}
     -> {:my.files/ok? true :seon.path/abs <p> :my.files/size <int>
         :my.files/dir? <b> :my.files/file? <b> :my.files/mtime <js/Date>}
      | error envelope"
  [m] )

(defn file-exists?  #_"{:seon.path/abs <p>} -> bare boolean (predicate ergonomics)" [m] )
(defn home-dir      #_"{} -> the user's home as a bare :seon.path/abs string" [_] )
```

### The worked chain that now threads with zero rekey

```clojure
;; grep -> read, the core move (the documented manual rekey DELETES):
(->> (search/grep {:seon.search/pattern "defn \\^:async"}) ; -> {ok? items[located]}
     :seon.items/items                                      ; vector of :seon.path/located
     (filter #(str/ends-with? (:seon.path/abs %) ".cljs"))  ; items are maps
     (map files/read-file)                                  ; located feeds read-file
     (map :my.files/content))

;; list -> stat threads identically:
(->> (files/list-dir {:seon.path/abs "/Users/me/work"})
     :seon.items/items
     (filter :my.files/file?)
     (map files/stat))                                      ; located feeds stat
```

## Composability check (PATH / REF / ITEMS / RESULT)

- **PATH** — every request keys on `:seon.path/abs`; `list-dir`/`walk-dir` entries
  ARE `:seon.path/located`. This is the hinge the catalog calls the single biggest
  composability defect today (`:seon.agent.search/path` vs `:seon.agent.fs/path`,
  and the bare-string listings). `my.files` is the half that fixes it on the read
  side; `my.search` fixes the search side; they meet at `:seon.path/abs`.
- **REF** — n/a (files address by path, not by db lookup-ref); a persisted
  finding later carries a `:my.kb/source-path` derived from `:seon.path/abs`.
- **ITEMS** — `list-dir`/`walk-dir` adopt `:seon.items/items` + `:count` +
  `:truncated?`, each item a self-describing located map (was `[:vector :string]`
  — the one place files broke the chain).
- **RESULT** — `:my.files/ok?` references `:seon.result/ok?`; failure returns the
  shared `:seon.error/*` map (with `:seon.error/kind`) instead of a plain string,
  so the agent can branch "fix my args" vs "report it."

## Gotchas

1. **SYNC is load-bearing.** Do not "modernize" to `node:fs/promises` or any
   async lib. The eval auto-await only resolves a form's outermost value; a
   Promise inside a `let` silently mis-branches. `my.files` verbs stay sync.
2. **`readdirSync({recursive:true, withFileTypes:true})` is buggy** (nodejs/node
   #48858, #51773) — never combine those two flags. The per-entry-stat walk is
   correct and keeps `skip-hidden` + the truncation cap, which `globSync` would
   not give in one pass.
3. **The allowlist is a soft boundary, not security.** It catches LLM
   `..`-traversal / out-of-scope accidents; isolation comes from the process +
   wire boundary. Don't oversell it. The wrapper CANNOT disable it — the gate is
   `:core-seed` floor; `my.files` can reshape results but not relax reach.
4. **`SEON_FS_LOCK`** makes `configure!` a no-op error — surface
   `:my.files/locked? true`, don't pretend the grant changed.
5. **`mtime` is `js/Date` typed `:any`** (the floor notes `:inst` varies across
   CLJS reader registries) — carry it through as-is, don't coerce.
6. **Keep the `:wasi` arm.** Both public verbs and any floor change must keep the
   stubbed `:wasi` branch (`int/wasi-pending`) so the future WASI-preopens
   convergence has a seam.
7. **`my.files` renders FULL every turn for every agent** (shared-collective
   `:toolkit-seed`) — keep it thin (~2k tok budget). All the bulk (syscalls,
   allowlist, walk recursion, paging) stays on the un-rendered floor.
8. **Don't duplicate reach in `my.search`.** Glob/content discovery is ripgrep's
   job; resist adding a glob lib to `my.files`.

## Sources

- Existing floor: `src/seon/agent/fs.cljs`, `src/seon/agent/fs/internal.cljs`,
  `src/seon/agent/search.cljs`, `src/seon/agent/search/internal.cljs`,
  `src/seon/error.cljs` (read in full).
- Catalog spec + backbone shapes: `docs/prds/agent-fsm/toolkit-catalog.md`
  (§"my.files", §"composability backbone").
- Live Node probe (`node v24.2.0`): `fs.globSync`, `fs.opendirSync`, `fs.cpSync`,
  `readdirSync({recursive,withFileTypes})` all present.
- fs-extra README / changelog; npm-compare fs-extra vs graceful-fs vs node:fs —
  fs-extra = copy/remove/ensure + promises over graceful-fs; graceful-fs =
  EMFILE/EAGAIN queueing; native fs = simple ops.
  <https://github.com/jprichardson/node-fs-extra>,
  <https://npm-compare.com/fs,fs-extra,graceful-fs,memfs>
- Node fs docs + the recursive+withFileTypes bug:
  <https://nodejs.org/api/fs.html>,
  <https://github.com/nodejs/node/issues/48858>,
  <https://github.com/nodejs/node/issues/51773>
- babashka.fs API (design inspiration only; JVM/java.nio):
  <https://github.com/babashka/fs>
</content>
</invoke>
