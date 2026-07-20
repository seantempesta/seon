---
type: research
status: complete
tags: [research, health]
---

# Mechanical detector sweep (2026-07-20)

Detection-lane run of every mechanical detector in the repo, executed
2026-07-20 13:39–14:05 EDT on branch `codex/runtime-reliability-refactor`.
Caveat: two implementation lanes were concurrently editing
`src/seon/warn.cljs`, `src/seon/agent/ctx/warnings.cljs`, `src/seon/eval.cljs`,
and some `my.*`/handler files; findings in those files reflect the committed
tree at run time (all four kondo-error files were git-clean when inspected)
but may churn.

## 1. bin/lint (clj-kondo + splint) over src/ script/ test/

Method: `bin/lint src script test` (clj-kondo `--cache false`; splint 1.22.0
with `.splint.edn`). clj-kondo: **4 errors, 880 warnings** (7.9s). splint:
**1687 style warnings** across 344 files (2.9s).

### clj-kondo ERROR level (all 4)

| Location | Error | Assessment |
|---|---|---|
| `src/seon/config.cljs:1378` | Unresolved namespace `clojure.string` | Real: `clojure.string/lower-case` used without a `:require`; works only because clojure.string is loaded elsewhere. Add the require. |
| `src/seon/eval.cljs:69` | Conflicting alias for `seon.instrument` | Real: `[seon.instrument :as instrument]` required twice (lines 64 and 69) in the same ns form. File is in concurrent-lane churn; committed state has the duplicate. |
| `src/seon/instrument.cljc:14` | Invalid require: no libs specified | The `(:require #?@(:cljs [...]) ...)` splice leaves kondo seeing an empty require branch. Verify the `:clj` branch; likely a kondo reader-conditional limitation — if so, add an inline ignore rather than leaving a standing error. |
| `test/seon/db/remote_contract_test.clj:257` | Unresolved symbol `authority` | False positive: `with-authority` macro binding. Needs `:lint-as`/`:hooks` config for the macro so real unresolved symbols stay visible. |

### clj-kondo warnings by class (880 total)

| Count | Class |
|---|---|
| 761 | shadowed-var (top shadows: `resolve` 79, `key` 87 clj+cljs, `name` 82, `hash` 31, `namespace` 37, `identity` 38, `ns-name` 26, plus self-ns shadows in tests) |
| 23 | unused-namespace |
| 30 | unused-binding (individual bindings, mostly 1–2 each) |
| 14 | unused-referred-var (incl. 13× `testing` referred but unused) |
| 12 | redundant-let |
| 2 | redundant-do |
| 2 | `run!` refer conflict |

No warning class other than shadowed-var exceeds ~30 hits. Shadowed-var is
overwhelmingly a style-noise class here (core fn names reused as locals,
e.g. `key`/`name`/`namespace` in destructuring); if it will never be acted
on, consider demoting it in config instead of carrying 761 standing warnings
that bury the ~80 actionable unused-* ones.

### Suppressed / config-disabled rules (.clj-kondo/config.edn)

- `:missing-else-branch` off ("too noisy for our style")
- `:redundant-ignore` off (splint disable comments in use)
- `:docstring-blank?`, `:docstring-no-summary`,
  `:docstring-leading-trailing-whitespace` off (superseded by
  `seon.dev.docstring`)
- `:unresolved-symbol` excludes `await` (CLJS special form)
- `:refer-all` and `:clojure-lsp/unused-public-var` demoted to info
- skip-files: `target/`, `reference-code/`, `.cpcache/`

### splint by rule (1687 total; >5 hits shown)

| Count | Rule | Note |
|---|---|---|
| 459 | metrics/fn-length | >30 body lines; concentrated in `src/my/blob.cljs`, `src/my/plan/internal.cljs`, `script/seon/dev/artifact.clj`, `src/seon/eval.cljs` |
| 203 | performance/assoc-many | |
| 167 | lint/catch-throwable | CLJS `(catch :default)` style mostly; verify before mass-fixing |
| 147 | performance/dot-equals | |
| 130 | style/apply-str | |
| 70 | style/eq-zero | auto-fixable |
| 70 | lint/warn-on-reflection | CLJ namespaces without `*warn-on-reflection*` |
| 59 | metrics/parameter-count | >5 positional params |
| 46 | performance/get-keyword | |
| 42 | lint/identical-branches | worth eyeballing — identical if/cond branches can be real bugs |
| 41 | lint/locking-object | |
| 26 | style/tostring | |
| 22 | style/prefer-clj-string; 22 performance/single-literal-merge | |
| 18 | performance/into-transducer | |
| 14 | style/eq-true | |
| 13 | style/redundant-let; 13 lint/into-literal; 13 lint/fn-wrapper | |
| 12 | lint/if-same-truthy | |
| 9 | style/not-some-pred; 8 style/prefer-condp; 7 lint/let-when; 7 lint/if-nil-else; 6 style/when-not-call; 6 style/new-object | |

Full log retained at scratchpad `lint-full.log` for this session; rerun is
cheap (~11s total).

## 2. Docstring checker (seon.dev.docstring)

Method: `scan` over all `.clj/.cljc/.cljs` in src/ + script/ via
`clojure -Sdeps '{:deps {rewrite-clj/rewrite-clj {:mvn/version "1.2.51"}}}'
-M:writer`. Note: the checker's own dependency (rewrite-clj) is not on the
`:writer` classpath — it only loads under `:cljs` extra-deps — so there is
currently no one-command way to run it JVM-side without the ad-hoc Sdeps.

Headline: **156 files, 1060 public fns, 63 findings** (raw; the "~560 known"
figure is stale or referred to a different corpus/rule set).

| Rule | Count (raw) | Note |
|---|---|---|
| no-terminal-punctuation | 35 | line 1 missing `.`/`?`/`!` |
| reserved-glyph-literal | 19 raw / 11 distinct fns | docstring carries a reserved runtime result-grammar glyph; `format-eval-row` alone is reported 9× (one finding per glyph occurrence — dedupe bug in the checker or the glyph appears 9 times) |
| missing-docstring | 9 | |

Agent-facing offenders (docstrings render into agent context, so these rank
first):

- reserved-glyph-literal in toolkit/agent-verb fns:
  `src/my/kb.cljs:345` (`recall`), `src/seon/agent/fs.cljs:306,332`
  (`grants`, `read-file`), `src/seon/agent/search.cljs:153,288`
  (`grep`, `grep-graph`), `src/seon/agent/shell.cljs:217,303,336`
  (`run`, `py-run`, `run-bg!`), `src/seon/agent/web.cljs:236,370`
  (`fetch`, `search`), `src/seon/agent/ctx.cljs:512` (`format-eval-row`),
  `src/seon/agent/ctx/transcript.cljs:483` (`coalesced->renderable`).
- missing-docstring on agent-adjacent publics:
  `src/seon/agent/ctx/menu.cljs:538` (`acquire-function-menu`),
  `src/seon/db.cljs:800,1197,1370,1373` (`cas-assert`,
  `resolve-transaction-branch-head!`, `malli->datahike-schema`,
  `tx-meta-datahike-schema`).
- no-terminal-punctuation cluster: 10 fns in
  `src/seon/agent/ctx/transcript.cljs` (119, 137, 154, 313, 336, 383, 412,
  483, 616, 1353) — one file sweep fixes them; 17 in `src/seon/embed.clj`
  (dev-tool side, lower priority).

## 3. Markdown (seon.dev.markdown over docs/)

Method: `validate-file` on every `docs/**/*.md` with vault-root `docs`.
**559 findings, 0 error-severity.** Every file has frontmatter with valid
`type` and `status` — no structural frontmatter violations.

Structural subset (requested focus):

- **wikilink-target-exists: 28 warnings.** Roughly half are genuine broken
  links (memory-file names referenced from docs, e.g.
  `[[feedback_test_behavior_not_exact_strings]]`,
  `[[reference_mlx_metal_cache_limit]]` in
  `docs/prds/repl-autosuggest/root-cause-fixes-2026-07-13.md:225` — the
  active lane's anchor doc; `[[seon.agent.loop]]`;
  `[[ctx-install-canvas-symbol-roundtrip]]` ×2 in
  `docs/prds/agent-ctx/feels-stateful-remaining-work-spec.md`). The other
  half are false positives: EDN/Datalog snippets like `[[?e :seon.fn/source]]`
  or `[[nm _tx]]` inside prose being parsed as wikilinks — a linter
  limitation worth a fence-awareness fix.
- **valid-tags: 96 warnings** — but the dominant "invalid" tags are
  `diffusion` (16), `context` (16), `gym` (14), `ui` (13), `config` (9),
  `render` (4), `runtime` (3): real vocabulary the hand-maintained allowed
  set in `seon.dev.markdown` never learned. This is a hand-maintained-list
  smell (standing owner rule: computed rules, not literal name sets); the
  fix is in the linter's tag authority, not 96 doc edits.
- heading structure: 2 heading-increment; ~24 single-h1 warnings, nearly all
  in `research/` transcripts that embed pasted reports (benign there).

Style-only remainder (not in scope): list-style 145, blanks-around-headings
123, no-bare-urls 100 (info), blanks-around-fences 51, trailing-whitespace 7.

## 4. Comment-debt sweep

Method: `rg -in "TODO|FIXME|HACK\b|XXX|KLUDGE|WORKAROUND|TEMPORARY|for now|remove when|delete when"` across src/ script/ bin/ test/. 61 raw hits;
**zero TODO/FIXME/XXX/KLUDGE markers exist in the maintained tree.** Almost
all hits are the English word "temporary" as a local binding (atomic-move
temp files) or test names — benign.

Actionable-with-context:

| Location | Text | Class |
|---|---|---|
| `bin/fix-bootstrap-macros:2,16` | "post-build workaround for a shadow-cljs ... fix pending; this script is the workaround" | Actionable: a declared standing workaround script for a shadow-cljs bootstrap-macro bug; tracks an upstream fix. Should have an issue note with the upstream link and a delete-when condition. |
| `src/seon/agent/message.cljs:49` and `src/seon/client.cljs:1072` | "one human for now" | Actionable-known: single-user assumption on THE user entity, seeded at boot; fine today, is the marker for the multi-user boundary. |
| `src/seon/client.cljs:544` | "for now this is the simplest 'process stays open' contract" | Benign-documented decision. |
| `src/my/plan/internal.cljs:1818` | "that workaround is now deleted" | Benign (historical note that a workaround was removed). |

## 5. Compiler surface

- Shadow compile (fresh `tmp/test-cljs-latest.log`, 2026-07-20 13:39): build
  completed "613 files, 102 compiled, **2 warnings**" — exactly the two known
  `:infer-warning`s in
  `reference-code/datahike/src/datahike/index/persistent_set.cljc:265` and
  `reference-code/datahike/src/datahike/db/transaction.cljc:136`. **No other
  compiler warnings.**
- JVM reflection: latest writer log
  (`logs/operator/writer/e17c2021-*.log`, 12:26 today) shows **21 reflection
  warnings, all in `src/seon/db/transport/uds.clj`** — the NIO selector
  event loop (lines 266–274, 439, 495, 1046–1065, 1194–1199:
  `selectedKeys`/`interestOps`/`attachment`/`isValid`/`isReadable`/
  `isWritable`/`wakeup`/`cancel`/`close`). This is the hot dispatch path of
  the UDS transport; type hints (`Selector`, `SelectionKey`,
  `ServerSocketChannel`) would remove per-event reflective calls. Also the
  one concrete beneficiary of splint's 70 `lint/warn-on-reflection` hits.

## 6. Dependency pin drift (reference-code/ vs deps.edn)

Checked konserve, proximum, superv.async, partial-cps, shadow-cljs, aero,
rewrite-clj, clojurescript, core.async, datahike (`:local/root`, cannot
drift). Mismatches only:

| Dep | deps.edn pin | Submodule checkout | Verdict |
|---|---|---|---|
| konserve | `:git/sha b5c99bc0…` | `df6818d4` (origin/sync-only); pin is **not an ancestor** of the checkout; checkout is 17 commits past tag `seon-pin-2026-06-22` | **Real drift**: agents reading `reference-code/konserve` see different source than the build resolves. Either advance the pin or reset the submodule to the pin. |
| rewrite-clj | mvn 1.2.51 | `v1.2.51-5-g60782e5` | Minor: 5 commits past the pinned release. |
| core.async | mvn 1.10.870-alpha2 (`:cljs` alias) | `v1.9.829-alpha2-10` describe | Tag-naming makes ordering unclear; flag for a look — checkout appears to be a different line than the mvn pin. |
| clojurescript | mvn 1.12.145 | grafted shallow master, no tag reachable | Cannot verify from the checkout; not necessarily drift. |
| datalog-parser | (transitive) | **submodule not initialized** (`-08a32d8f`) | Grounding gap: `reference-code/datalog-parser` is empty in this checkout. |

Exact matches (no drift): proximum `9846d3e7`, superv.async `3e6ed755`,
partial-cps `1e119b03`, shadow-cljs `c98bf60f`, aero 1.1.6.

## 7. Stale cross-references

Method: rg over src/ script/ bin/ for today's deletions.

- `dev/nrepl`, `dev.nrepl`, `storage-shootout`, `integrant`, `db/*conn*`:
  **zero hits** — clean.
- `:seon.eval/record-error`: one hit, `src/seon/warn.cljs:757`, in a comment
  ("a stamped `:seon.eval/record-error` beside a bare eval …") describing a
  deleted guard class. warn.cljs is in active concurrent-lane churn; if the
  comment survives the lanes' edits it is a stale reference to fix.
- `install-configuration-context!`: **not stale** — defined at
  `src/seon/db.cljs:702` (`^:no-doc`) with a live caller at
  `src/seon/client.cljs:2190`.

## Top 20 actionable, ranked

1. `reference-code/konserve` pin drift — checkout not the pinned SHA
   (source-grounding integrity; agents read the wrong source).
2. `src/seon/db/transport/uds.clj` — 21 reflection warnings on the NIO
   selector hot loop; add type hints + `*warn-on-reflection*`.
3. `src/seon/config.cljs:1378` — missing `clojure.string` require
   (kondo ERROR).
4. `src/seon/eval.cljs:64+69` — duplicate `seon.instrument` require
   (kondo ERROR; coordinate with the lane editing eval.cljs).
5. Reserved-glyph-literal docstrings in the agent toolkit (11 fns:
   my.kb/recall, agent/fs, agent/search, agent/shell, agent/web,
   agent/ctx) — these render into agent context and can teach agents to
   emit the reserved glyph.
6. `reference-code/datalog-parser` submodule uninitialized — grounding gap.
7. `seon.dev.markdown` allowed-tag set is a hand-maintained literal list
   missing live vocabulary (diffusion/context/gym/ui/config…) — 96 warnings
   are linter debt, not doc debt; violates the computed-rule standing rule.
8. ~14 genuinely broken wikilinks in docs (incl. the active lane's anchor
   `root-cause-fixes-2026-07-13.md:225`); plus linter false-positives on
   EDN-in-prose worth a parser fix.
9. `bin/fix-bootstrap-macros` — undocumented-lifetime workaround script for
   a shadow-cljs bug; needs an issue note with a delete-when condition.
10. Docstring checker not runnable JVM-side without ad-hoc Sdeps
    (rewrite-clj absent from `:writer`); also emits duplicate findings per
    glyph occurrence (format-eval-row ×9).
11. `src/seon/instrument.cljc:14` — kondo invalid-require on the
    reader-conditional splice; fix or annotate so the ERROR channel stays
    clean.
12. `test/seon/db/remote_contract_test.clj:257` — add `:lint-as`/hook for
    `with-authority` so unresolved-symbol stays trustworthy.
13. splint `lint/identical-branches` (42) — the one splint class likely to
    hide real bugs; eyeball pass warranted.
14. Missing docstrings on agent-adjacent publics (`seon.db/cas-assert`,
    `resolve-transaction-branch-head!`, `malli->datahike-schema`,
    `tx-meta-datahike-schema`, `agent/ctx/menu/acquire-function-menu`).
15. `src/seon/agent/ctx/transcript.cljs` — 10 no-terminal-punctuation
    docstrings in one agent-facing file; single sweep.
16. clj-kondo shadowed-var at 761 standing warnings buries the ~80
    actionable unused-* warnings; decide enforce-or-demote.
17. `src/seon/warn.cljs:757` stale `:seon.eval/record-error` comment
    (verify after lane churn settles).
18. unused-namespace (23) + unused-referred-var (14) + unused-binding (~30)
    — mechanical cleanup batch.
19. core.async submodule/mvn-pin line mismatch — verify which line
    `reference-code/core.async` should track.
20. splint metrics/fn-length 459 — not individually actionable, but the
    density map (my/blob.cljs, my/plan/internal.cljs, eval.cljs,
    script/seon/dev/artifact.clj) marks the refactor-candidate files.
