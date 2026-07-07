---
type: research
status: completed
tags: [research, agent]
---

# Tool-readiness assessment — SWE-bench dev-pass GO/NO-GO (2026-07-06)

Assessment of whether the tooling lane's new agent tools (structural edit
verbs, parity sweep, background jobs, toolbelt exposure) are complete and
working, and whether it is worth running the live SWE-bench Seon dev pass now.
Read-only + exercise; no `src/` edits, no harness edits, benchmark NOT run.

## TL;DR — verdict

**GO-WITH-CAVEAT.** The tools are real, fail-loud, exposed in agent context,
and already baked into the current `seon-runtime-arm64` overlay. The single
remaining blocker is a **one-line repoint in the eval lane's OWN harness**:
`swebench_arm.py` still pins `OVERLAY_VOLUME = "seon-runtime-slice3"` (the
pre-verbs 2026-07-05 overlay) — it must point at `seon-runtime-arm64` (the
2026-07-06 overlay that actually contains the verbs). No image rebuild is
needed. Do this repoint and the dev pass measures the fixed capability; skip
it and the pass re-measures the exact slice-3 shell-only-editing failure.

- **Edit verb: EXISTS, fail-loud, verifiable envelope.** `seon.agent.fs/replace!`
  (+ `insert!`) fail loud on 0 matches (`::not-found`) and on >1 matches
  (`::ambiguous` with line-numbered candidates), never guessing. Proven live.
- **Fabrication surface: STILL OPEN.** Raw `(js/require "fs")` is ungated and
  the always-on system-text STILL advertises `(js/require "node:fs")`
  (`ctx.cljs:1088`). Mitigated (not closed) by A5's committed positive teaching
  of `fs/replace!` + `#code` heredoc + candidates in the same block. A finding,
  not a hard blocker (the T4 canary showed a real agent discovers + uses the
  verbs from context alone).
- **Toolbelt exposure: DONE.** `config/system.edn` `:seon.eval/home-requires`
  carries `fs`/`shell`/`web`/`search` — committed and present in the image.

## Verb inventory (as committed at HEAD, present in `seon-runtime-arm64`)

All map-in / map-out, instrumented, `:seon.agent.*/ok?` discriminator, never
throw (semantic failures are values). "Repo-work" surface an agent can call:

### `seon.agent.fs` — the edit surface (`fs.cljs`)

| Verb | Signature (key args) | Envelope highlights |
|---|---|---|
| `grants` | `[]` | `allowed-roots` / `read-only?` / `locked?` |
| `configure!` | `{allowed-roots read-only?}` | resets grant (no-op if `SEON_FS_LOCK`) |
| `read-file` | `{path from-line? max-lines? encoding?}` | `content`/`file-sha`, paged w/ honest `total-lines` |
| `view` | `{path from-line? max-lines? encoding?}` | **line-numbered** `content` (`N⇥line`) + `file-sha` (the edit-aiming read; default 100 lines) |
| `write-file` | `{path content encoding?}` | full overwrite; `content` = string OR `#code` block |
| `replace!` | `{path find replace expected-count?/all? near? file-sha? encoding?}` | **anchored structural edit** — see below |
| `insert!` | `{path content after-line?/before-line? encoding?}` | exactly-one-anchor line insert; range-after + excerpt |
| `edit-file` | `{path from-line/to-line/content OR old-string/new-string}` | LEGACY (retirement pending; T4 used it 0/24) |
| `list-dir` | `{path}` | `entries` (no recursion) |
| `walk-dir` | `{path match-ext? glob? skip-hidden? sort? max-results?}` | recursive; `glob` (`*.py`, `src/**/*.cljs`), `sort :name/:mtime`, honest `total-found`/`truncated?`/`hint` |
| `stat` / `file-exists?` / `home-dir` | `{path}` / … | size/mtime/dir?/file? |

`replace!` — the SWE-bench-relevant primitive. `find`/`replace` accept a plain
string OR a `#code/<lang> <<SENTINEL … SENTINEL` heredoc (raw foreign source,
zero escaping). Success envelope (`:seon.agent.fs/anchored-response`, `ok? true`):
`file-sha` (new), `range-after`, `lines-added`, `lines-removed`,
`normalizations?`, and a `±3`-line line-numbered `excerpt` of the result — a
"Done" claim is falsifiable from the excerpt + sha with no re-read. Failure
(`ok? false`): `:seon.error/message` + `:seon.error/data` carrying
`::reason` (`::not-found`/`::ambiguous`) and `::candidates` (every occurrence
with a line range + preview). A `file-sha` mismatch → `ok? false` carrying the
ACTUAL on-disk sha (optimistic fence against a stale edit).

### `seon.agent.shell` — process + background jobs (`shell.cljs`)

| Verb | Signature | Notes |
|---|---|---|
| `grants` | `[]` | `SEON_SHELL` grant |
| `run` | `{cmd args? cwd? stdin? timeout-ms?}` | fg; default 30 s → `timed-out?`; full `out`/`err` (no verb token cap, ~2 MB RAM ceiling + honest `truncated?`); pass a large `timeout-ms` for a slow test/build |
| `py-run` | `{source?/cmd args? cwd? timeout-ms?}` | Python specialization of `run` |
| `run-bg!` | `{cmd args? cwd? stdin?}` | returns `job-id` + `state :running`; volatile job table (dies with pod) |
| `list-jobs` / `job-status` | `[]` / `{job-id}` | state/runtime/exit + honest out/err **token** sizes; pytest output auto-**parsed** into `:seon.agent.testrun/result` on exit |
| `job-output` | `{job-id stream? since?}` | full-so-far or incremental via `::since` char cursor; ordinary eval value (no cap) |
| `job-stop!` | `{job-id}` | SIGTERM, idempotent |

### `seon.agent.search` — literal search (`search.cljs`)

`grep` `{pattern path? glob? full? context-lines? multiline? max-results?}` —
`context-lines` (0–10, rg `-C`), `multiline?` (rg `-U --multiline-dotall`),
line numbers on every emitted line, context lines flagged. `grep-graph` — regex
over DB text attrs. (Plus `my.blob` put/text for durable content.)

## Exercise evidence (live)

### 1. The deterministic cascade — the heart of `replace!` (JVM, `match.cljc`)

`seon.agent.fs.match/decide` is the EXACT pure matcher `replace!` drives (the
verb is a thin `readFileSync → decide → writeFileSync` shell). Driven directly
via `clojure -M:test` on a 2-`return 1` Python fixture:

```
1. UNIQUE  → :action :apply  :stage ::exact  :range-after [1 2]
             new-content "def foo():\n    return 42\n\ndef bar():\n    return 1\n"
2. ZERO    → :action :fail   :reason ::not-found
             "text not found — re-read the file and copy the EXACT text…"
3. MULTI   → :action :fail   :reason ::ambiguous
             "found 2 exact occurrence(s), expected 1. Disambiguate with ::near…"
             :candidates [{:range [2 2] :preview "…"} {:range [5 5] :preview "…"}]
4. ::all?  → :action :apply  :ranges [[2 2] [5 5]]  (changes both)
5. ::near [4 5] → :action :apply  :stage ::exact-near  :ranges [[5 5]]  (disambiguated)
```

Fail-loud on both 0 and >1 confirmed; `::all?` legitimizes multi; `::near`
disambiguates. This is the swe-agent `str_replace_editor` contract, met.

### 2. The full fs.cljs verbs against real temp files (hermetic CLJS suite)

`bin/test-cljs` (fresh isolated `:node-test` JVM, latest source): **1129 tests
/ 5072 assertions, 0 errors, 7 failures — all 7 in `ctx_test.cljs`**
(eval-row glyph neutralizer, the A8 value-representation unit that the spec
records as "impl in flight"). The edit/tool suites are GREEN:
`replace!-unique-match-writes-the-landing`, `replace!-ambiguous-refuses-with-candidates-and-no-write`,
`replace!-near-window-disambiguates`, `replace!-expected-count-changes-all`,
`replace!-all?-changes-every-occurrence`, `replace!-sha-guard-fences-a-stale-edit`,
`view-numbers-lines-and-stamps-a-sha`, `walk-dir-glob/mtime/truncation`, plus
the shell/search suites — all pass, exercising the production verbs end-to-end
against real files on disk.

## The three load-bearing answers

1. **Structural edit verb, fail-loud, inspectable envelope? — YES.** `replace!`
   (+ `insert!`). Fails loud on 0 (`::not-found`) and >1 (`::ambiguous` +
   candidates), never guesses; `::all?`/`::expected-count`/`::near` cover the
   change-all and disambiguate cases; `file-sha` fences stale edits. Envelope
   is a `:seon.agent.fs/ok?`-discriminated map — a "Done" is falsifiable from
   the returned `excerpt` + `file-sha`. Proven live (cascade + hermetic suite).

2. **Raw `(js/require "fs")` fabrication surface addressed? — NO (open),
   mitigated.** Raw node `fs` is ungated and returns no envelope, and the
   always-on system-text at `src/seon/agent/ctx.cljs:1088` STILL invites it
   ("`(js/require "node:fs")` and any installed Node module"). No gated
   wrapper, no lint. A5 (`24648a7c`, committed) added POSITIVE counter-teaching
   in the same always-on block — `fs/replace!`, the `#code` heredoc, and
   "anchored edits never guess → CANDIDATES" — so the better path is now taught
   unconditionally. The fabrication-repro (`evals/runs/2026-07-06-fabrication-repro/`)
   is a REMINDER of the risk: that agent used raw `writeFileSync` — but it ran
   against the OLD `seon-runtime-slice3` overlay, which did NOT expose the fs
   toolbelt. T4 root-caused fabrication to our own docstring `;;=>` echoes (the
   A8 context fix, in flight), not a tool gap. **Verdict: finding, not a
   blocker** — but worth a follow-up (drop or de-emphasize the raw-fs line in
   the always-on block once the arm64 overlay's verb teaching is live).

3. **Verbs exposed in agent context? — YES.** `config/system.edn`
   `:seon.eval/home-requires` lists `[seon.agent.fs :as fs]`,
   `[seon.agent.shell :as shell]`, `[seon.agent.web :as web]`,
   `[seon.agent.search :as search]` — committed (`ec4bd5fa`) and present in the
   image-build commit's config (`git show 52e25b6f:config/system.edn` → 10
   matches). These render as compact cards; A5 proved fresh-mint discovery via
   `ctx-preview`. Grammar (`#code` + candidates recovery) is taught in the
   always-on system-text.

## The prerequisite chain to a live dev pass

1. **Docker image rebuild — NOT needed.** The multi-arch build `52e25b6f`
   (2026-07-06) already extracted `seon-runtime-arm64` (733.6 MB,
   `sha256:a69721a0b899…`). A5 (`24648a7c`), A6 (`4af04a73`), the `#code`
   heredoc (`4ed0f793`), the toolbelt-exposing config, and the
   "cluster pods honor caller-set fs grant env" fix (`eb2c012d`) are ALL
   ancestors of `52e25b6f` — verified via `git merge-base --is-ancestor`. The
   overlay's `/opt/seon` = that commit's `src/`, so the verbs are baked in. No
   src commits landed after the image build except one docs handoff (`8246d0a1`).

2. **Overlay repoint — REQUIRED (the blocker; eval lane's own code).**
   `src-inspect-ai/src/seon_inspect/swebench_arm.py` still pins
   `OVERLAY_VOLUME = "seon-runtime-slice3"` — the 2026-07-05 overlay built
   BEFORE A5/A6, so it has shell-only editing (exactly the slice-3 failure).
   The multiarch README (`evals/runs/2026-07-06-multiarch-build/README.md`
   lines 135-138) explicitly flags the repoint `seon-runtime-slice3 →
   seon-runtime-arm64` as an unfinished follow-up. This is a one-line change
   (plus the pinned-digest note) in the eval lane's own harness. Both volumes
   exist locally (`docker volume ls`).

3. **Compose fs-write grant — DONE.** `swebench_arm.py` emits
   `SEON_SHELL=1`, `SEON_FS_ROOT=/testbed`, `SEON_FS_READ_ONLY=0`, and its
   system message STATES the write grant ("your file verbs are rooted at
   /testbed with write access… edit repository files under /testbed") — the
   "every scorer check must be in context" contract. Committed + asserted by
   `tests/test_swebench_arm.py:82-83`. The entrypoint honors these env
   overrides over its read-only default (`eb2c012d`, in the overlay).

**The one gap that would make the pass measure a tool defect, not capability:**
running with the un-repointed `seon-runtime-slice3` overlay. Then `replace!`
isn't in the toolbelt, the write grant is `/opt/seon` read-only, and the agent
falls back to shell / raw `fs` — re-measuring the slice-3 shell-only failure.
Repoint first.

## Complexity artifacts / smells found (surface, don't fix)

- **Branch is RED (7 failures) on `feature/agent-ctx`** — all in
  `ctx_test.cljs` (`eval-row-repl-faithful-stream`,
  `eval-row-shows-captured-print-output`,
  `eval-row-neutralizes-fake-claims-keeps-real-results`). These belong to the
  A8 value-representation/`⟹`-glyph consolidation the spec marks "impl in
  flight (af08)". Not the edit toolkit — but the suite is not green, and A8
  (the fabrication context fix) is exactly the unit that should precede a
  measured dev pass. **Tooling lane** should land A8 to green before the A/B.
- **Two edit primitives coexist** — `edit-file` (legacy line-range/exact) and
  `replace!`/`insert!` (anchored). The spec records the owner decision to
  retire `edit-file`; T4 used it 0/24. Left as-is pending that call.
- **Raw-fs invite in always-on context** (`ctx.cljs:1088`) coexists with the
  new verb teaching — see answer #2; recommend de-emphasizing post-repoint.

## Evidence base

- `src/seon/agent/fs.cljs` (verbs) · `src/seon/agent/fs/match.cljc` (cascade) ·
  `src/seon/agent/shell.cljs` · `src/seon/agent/search.cljs`
- `config/system.edn` `:seon.eval/home-requires` (toolbelt) ·
  `src/seon/agent/ctx.cljs:1082-1090` (always-on system-text)
- `docker/Dockerfile` + `docker/seon-entrypoint` (overlay + env defaults) ·
  `src-inspect-ai/src/seon_inspect/swebench_arm.py` (OVERLAY_VOLUME + compose env)
- `evals/runs/2026-07-06-multiarch-build/README.md` (overlay extraction +
  repoint follow-up) · `evals/runs/2026-07-06-fabrication-repro/README.md`
  (raw-fs bypass) · `evals/runs/2026-07-05-slice3-composition/README.md`
  (the shell-only-editing failure this fixes) · `evals/runs/2026-07-05-pre-slice4-debt/`
  (the /testbed fs-grant wiring)
- Live: `clojure -M:test` cascade probe (above); `bin/test-cljs`
  1129/5072/0/7 (`tmp/test-cljs-20260706-200854-*.log`).
