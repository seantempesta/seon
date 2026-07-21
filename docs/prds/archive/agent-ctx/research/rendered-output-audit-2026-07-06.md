---
type: research
status: active
tags: [research, agent]
---

# Rendered-output audit across decay levels (Unit A7, 2026-07-06)

What the agent ACTUALLY SEES, per verb and value shape, at each transcript
decay age. Live audit on the default pod against a dedicated disposable agent
(`hlh-2607061447`, NOT root). Every sample below is VERBATIM from the running
pod (captured to `tmp/a7-capture.edn`); nothing is paraphrased.

## TL;DR

The layered bounding is mostly sound and HONEST — real keys survive, markers
are loud, the `result/<id>` handle resolves live, opaque handles (DB / JS /
datom) project to compact markers instead of index dumps or mojibake, and the
age-decay wiring is byte-faithful end-to-end (proven: an eval aged to offset 6
renders identically to `format-eval-row` at the 200 cap). But the audit found
**several real defects**, three of them headline:

1. **`shell/run`'s `out-blob`/`err-blob` recovery handles are ELIDED from the
   rendered envelope** (render-ai's 8-key map bound drops them as "+2 more
   keys"), so the A6.6 "envelope names its recovery handle" contract is
   defeated in the exact over-cap case it was built for. The blob hash is not
   even in the stored `:seon.eval/result-edn`.
2. **A pruned `result/<id>` handle still renders as if live.** After the stash
   passes `result-vars-cap` (200), the transcript row is byte-identical and
   still advertises `; result/<id>`, but that handle now resolves to the
   graceful-miss string. The render marks a dead handle only for PRIOR-SESSION
   evals, never for within-session pruning.
3. **`my.blob/text` returns mojibake on a binary blob** (`ok? true`, `content`
   = raw PNG bytes as latin1) despite the blob carrying `:my.blob/media :png`.
   It should refuse with an honest not-text envelope.

Plus: the decay schedule's two upper levels (16384 / 1500) are near-identical
in practice because render-ai pre-bounds stored bodies far below 1500 chars;
the drill hint teaches recovery but not durability promotion; nested clips can
truncate their own recovery guidance; and units are mixed within one row
(`(200 of 3919)` chars vs "50 of 979 tokens").

## Method + environment

- Dedicated agent `hlh-2607061447` (seeded default ctx: real 3-level
  `::result-decay` `[{0 16384}{2 1500}{5 200}]`, `::tiers []` empty,
  `::turns-retained` default 8, `escape-clipping? = true`).
- Each verb/value was driven through the REAL pipeline
  (`seon.eval/eval-batch!` under `db/with-agent`) so the stored
  `:seon.eval/result-edn`, the `result/<id>` stash, and the eval entity are
  all genuine. Rows were rendered with `seon.agent.ctx/format-eval-row` at
  `:seon.render/result-body-cap` ∈ {16384, 1500, 200} — this is byte-exactly
  what `transcript.cljs` threads per offset (verified — see "Aging proof").
- Grants live: `SEON_SHELL=1`, `SEON_WEB=1`, `SEON_FS_ROOT=<repo>`,
  `SEON_FS_READ_ONLY=1` (so fs writes return the read-only refusal envelope).

### Caveats (honesty)

- **Tier eviction is INERT by default.** `::tiers` is `[]` on a seeded agent,
  so `clip-events-by-tiers` renders ALL events (the `turns-retained` window is
  tier-DRIVEN — nothing evicts without a tier). The "tier eviction" decay
  dimension could not be exercised from default config; it is dead code path
  until a manifest sets `::tiers`. Config shown above.
- **Shared-pod instability.** The default pod is shared with two peers (A6
  shell/search, web-search) hot-reloading src. During the audit the pod
  crashed on peer reloads **three times** (`SEON-CORE-FAULT ... reading
  'call'` once; `No protocol method IDeref.-deref defined for type null`
  twice — both at reload, from swapped-under-a-running-call peer code, not
  from the audit). Restarting a dead pod disrupts no one; results were
  streamed to `tmp/a7-capture.edn` to survive crashes. Flagging as a real
  stability signal: the single-threaded pod has no reload-time isolation, so a
  peer's mid-save intermediate state takes down every agent.
- Audit left a disposable agent + run + turns + demo plan + blobs in the
  default store (no reset permitted). Harmless; not cleaned.

## The decay mechanism as-found

Output shrinks at four stations; the READ-time decay (this unit's subject) is
only the last:

1. **Store-time (render-ai, `render/value.cljs`).** The stored
   `:seon.eval/result-edn` is ALREADY a depth/breadth-bounded skeleton
   (max-depth 3, max-keys 8, max-items 8, max-string 80) OR the whole value
   verbatim when its pr-str ≤ 1500 chars (`value-verbatim-cap`). Then a
   final `clip-result-body` backstop at store-edn-cap (16384 chars).
2. **Read-time decay (`format-eval-row` + `result-body-cap`).** The transcript
   picks the cap by the eval's AGE: `decay-cap-for-offset` → 16384 (offset
   0-1) / 1500 (offset 2-4) / 200 (offset 5+). VERIFIED live.
3. `cap-result-body` → `clip-or-full`: `limit` is treated as **CHARS**
   (`(<= (count s) limit)`), then cut via `clip-str` at the char→token
   equivalent. So the effective decay caps are **16384 / 1500 / 200 CHARS**.

### Finding D1 — the two upper decay levels rarely differ

Because store-time render-ai bounds bodies far below 1500 chars, the 16384 and
1500 caps produce IDENTICAL rows for essentially every real value. Measured
stored `result-edn` lengths:

| value | stored edn chars | 16384 vs 1500 | vs 200 |
|---|---|---|---|
| `(vec (range 12))` | 27 | same | same |
| `(vec (range 250))` verbatim | 891 | same | clips |
| `(vec (range 400))` | 177 (`+392 more`) | same | same |
| wide-map (40 keys) | 340 | same | clips |
| deep-nest (8 deep) | 169 (verbatim, depth-cap not hit) | same | same |
| long-string (30k chars) | 223 (`⟨7500 tokens⟩`) | same | clips its own hint |
| vec-of-60-maps | 508 | same | clips |
| lazy `(range 100000)` | 179 (`+99992 more`) | same | same |
| opaque JS object | 157 | same | same |
| opaque `#datahike/DB` | 172 | same | same |
| grep-graph (12 rows) | 2580 | **differ** | clips hard |
| shell over-cap envelope | 593 | same | clips |

Only a deliberately pathological homogeneous structure (8 rows × 7 sub-80-char
string fields → 3471–3919 stored chars) exercises all three levels distinctly.
So the 16384-vs-1500 distinction is nearly dead weight; the load-bearing decay
step is the **200-char stub at offset 5+**. Not necessarily a bug — but the
schedule's design intent ("start at 16384, shrink") assumes bodies can reach
16384, which render-ai prevents.

## Aging proof (offset → cap → render is byte-faithful)

Built a run + 6 linked turns, anchored a 3471-char eval in turn 0, drove noop
turns to push it to offset 6, and rendered the REAL `transcript-block`. The
anchor's row in the live block carried the exact 200-cap signature
(`…⟨⚠ TRUNCATED at 50 of 867 tokens…⟩` + `result/<id> holds it whole`),
identical to `format-eval-row(anchor, 200)`. `decay-cap-for-offset` returns
16384/1500/200 for offsets 0/2/6. The composition is sound.

## Raw eval values — verbatim per level

### The star exhibit: large homogeneous map (stored 3471 chars)

Offset 0/1 (cap 16384) — full skeleton, all 8 rows, `result/<id>` handle:

```
;=> [{:f0 "v-0-0-xxxx…(45 x's)" ; result/gwl-2607061500
    :f1 "v-0-1-xxxx…"
    … all 8 maps × 7 fields verbatim …
    :f6 "v-7-6-xxxx…"}]
```

Offset 2-4 (cap 1500) — clipped mid-value with the guiding marker + handle
`(1500 of 3471)`:

```
    :f3 "v-3-3 …⟨⚠ TRUNCATED at 375 of 867 tokens — the DISPLAY is clipped, the live value is COMPLETE⟩
; Never summarize or quote beyond the shown 375 tokens — bind and process the value with code: result/gwl-2607061500 holds it whole; (count …), subs, get-in/filter, or paged take/drop. …
```

Offset 5+ (cap 200) — clipped to ~4 keys, `(200 of 3471)` handle:

```
;=> [{:f0 "v-0-0-xxxx…" ; result/gwl-2607061500 (200 of 3471)
    :f1 "v-0-1-xxxx…"
    :f2 "v-0-2-xxxx…"
    :f3 "v-0-3-x …⟨⚠ TRUNCATED at 50 of 867 tokens — the DISPLAY is clipped, the live value is COMPLETE⟩
; Never summarize or quote beyond the shown 50 tokens — bind and process the value with code: result/gwl-2607061500 holds it whole; …
```

Handle proof: `(count result/gwl-2607061500)` → `8` (LIVE, resolves whole).

Findings on this row: shape-preserving (get-in paths stay valid down to the
clip), honest LOUD marker, handle present AND usable. **Two smells:** (a) the
`;=>` handle marker `(1500 of 3471)` / `(200 of 3471)` is unlabeled CHARS while
the inline marker says "375 of 867 tokens" / "50 of 867 tokens" — MIXED UNITS
in one row; (b) the clip cuts mid-string, so the shown body is **invalid EDN**
(unterminated string) — deliberate per design (marker says don't parse
beyond), but it breaks the "renders as re-readable EDN" property that
render-ai's structural bounding otherwise preserves.

### Small / opaque / error shapes (verbatim, cap-invariant unless noted)

| shape | rendered @16384 | @200 |
|---|---|---|
| `(vec (range 12))` | `;=> [0 1 … 11] ; result/rjF` | identical |
| deep-nest (8 deep) | full verbatim `{:x {:x …}}` (verbatim-cap beats depth-cap) | identical |
| lazy `(range 100000)` | `;=> (0 1 … +99992 more) ; result/hCS` + `‹partial view of seq 100000 items›` hint | identical |
| opaque JS | `;=> #‹js/Object #js {:a 1, :b 2}› ; result/mIe` + partial hint | identical |
| opaque `@*conn*` | `;=> #‹datahike/DB max-tx=… max-eid=…› ; result/Lag` + partial hint | identical |
| `(throw (ex-info "boom demo" …))` | `;=> ✗ boom demo` + `errors are values …` — NO handle | identical |
| `(do :ok nil)` | `;=> nil ; result/tbO` | identical |

Good: opaque handles are compact markers (not `#datahike/DB {…index blob…}`
nor `[object Object]`), errors carry no fake handle, partial-view hints name
the live var. Minor: `nil` still gets a `result/<id>` handle (harmless noise).

### Finding D2 — nested clip truncates its OWN recovery hint

A 30k-char string is stored render-ai-bounded to `⟨7500 tokens⟩` (223 chars).
At cap 200 it is clipped AGAIN, and the second cut lands inside the FIRST
clip's drill hint:

```
;=> "lorem lorem … lorem l…"⟨7500 tokens⟩ ; result/jWG-2607061508 (200 of 223)
; ‹partial view› — the COMPLETE value is result/jWG-2607061508  (get-in result/jWG-2607061508 […]) · fil …⟨⚠ TRUNCATED at 50 of 55 tokens …⟩
```

The recovery guidance `· filter · count · take/drop` is cut mid-word ("fil").
Two stacked truncation markers on one already-small value reads as garbage.

## Verb envelopes — verbatim + judgment

### fs (stable surface)

`fs/view` (windowed, `from-line 20 max-lines 6`) — CLEAN:

```
;=> {:seon.agent.fs/ok? true, …/content "20\tline 20 …\n…25\tline 25 … MARKER",
     …/from-line 20, …/lines-returned 6, …/total-lines 300,
     …/file-sha "f2f685bb…"} ; result/Hzc
```

Honest paging (lines-returned 6 of total-lines 300), line-numbered content,
handle present. **Recovery USABLE** — re-viewing with `from-line 250
max-lines 2` returned the tail window live. Minor: `file-sha` on every READ is
mild noise (it exists for edit-safety, not reads).

`fs/view` (no window) — **poor default:** defaults to 100 lines, but the whole
envelope then exceeds 1500 chars, so render-ai collapses `content` to
`"  1\tline 1 …\n  2\tline 2 …\n  3\tline 3 …"⟨878 tokens⟩` — the agent sees ~1.5
lines and must deref `result/<id>` or re-window. `fs/read-file` (3-line
window) rendered content FULLY (small envelope ≤1500). `fs/list-dir` clean
(`entries` vector verbatim).

`fs/replace!` under read-only → clean errors-as-value refusal:

```
;=> {:seon.agent.fs/ok? false, …/path "…", :seon.error/message
     "filesystem is read-only (:seon.agent.fs/read-only? true)"} ; result/tGT
```

(Also captured the instrument-input error render when called with the OLD
`:old-string`/`:new-string` keys — it legibly shows expected schema, `got nil`,
`reason missing required key`, and `hint did you mean :seon.agent.fs/find?`.
Note for A5/docs: `replace!` takes `:find`/`:replace`, not old/new-string.)

### grep-graph (stable) — rich + honest, but hint truncated

`grep-graph {pattern "transact!"}` stored 2580 chars:

```
;=> {…/ok? true ; result/kFb
  …/match-count 91  …/ns-count 35  …/returned 12
  …/by-ns [{…/ns "seon.db" …/count 12 …/member "seon.db/installed-schema" …/target :seon.fn …/line-text "…"} … 8 rows shown …
    … +4 more each {…shared keyset…}]
  …/truncated? true
  …/hint "91 graph matches in 35 namespaces — showing the 12 densest. Narrow :seon.agent.…"⟨46 tokens⟩}
; ‹partial view of map 7 keys› — the COMPLETE value is result/kFb …
```

Excellent structured output with honest totals and a shared-keyset elision.
**Smell:** the actionable `hint` (how to narrow) is itself truncated to 80
chars (`⟨46 tokens⟩`) by render-ai's max-string — the guidance is cut exactly
where it would tell the agent what to do. Handle `result/kFb` resolves live
(`(:seon.agent.search/match-count result/kFb)` → `91`). At offset 5 the whole
2580-char result clips to 200 chars; the handle survives.

### my.blob (stable)

`put!` → `{:my.blob/ok? true, :my.blob/hash "c4685…", :my.blob/tokens 2660}`
(clean projection). `text` (paged `from-line 41 max-lines 5`) — CLEAN with
honest totals (`lines-returned 5`, `total-lines 250`, `tokens 2660`), content
verbatim. `get` (full) collapses content to `"report line 1 …"⟨2660 tokens⟩`
with partial hint → same "must deref/page" pattern as fs/view-full. All good;
recovery paths (page via `text`, or `result/<id>`) usable.

### my.plan (stable) — clean

`plan!` → `{:my.plan/ok? true, :my.plan/root "odq-…", :my.plan/ids {:root "odq-…", "s1" "jFG-…"}}`.
Compact, no noise.

### shell/run (IN-FLIGHT — A6, version at 2026-07-06 ~19:00) — HEADLINE defect

Over-cap run (6000 lines). Stored envelope (593 chars, ALL levels):

```
;=> {…/ok? true ; result/kKF
  …/err ""
  …/out "stdout line 1 …\nstdout line 2 …\nstdout li…"⟨2048 tokens⟩
  …/exit 0
  …/out-tokens 56723
  …/timed-out? false
  …/truncated? true
  …/hint "preview clipped — full stdout ~56723 tok / stderr ~0 tok, shown up to 2048 tok …"⟨66 tokens⟩
  … +2 more keys}
; ‹partial view of map 10 keys› — the COMPLETE value is result/kKF …
```

The envelope has 10 keys; render-ai shows 8 and elides **"+2 more keys"**.
Live key dump confirms the elided two are **`:seon.agent.shell/out-blob`** and
`:seon.agent.shell/err-tokens`. So the A6.6 recovery handle — the blob hash
that holds the full 56723-token stream — is INVISIBLE in the rendered context
(and absent from the stored `result-edn` string entirely). The agent sees
`truncated? true` + a truncated hint + the `result/<id>` handle, but not the
named `out-blob`. It can only reach the full stream by derefing `result/<id>`
and pulling `:out-blob` itself — which defeats "the envelope names its
recovery handle." The `hint` is also truncated (`⟨66 tokens⟩`). At offset 5
the row clips to 50 tokens mid-`:exit`.

### web/fetch (IN-FLIGHT — web-search lane; version at 2026-07-06 ~19:03)

`fetch {url "https://example.com"}` (small page, 37 tokens) — whole 958-char
envelope renders VERBATIM at 16384 (≤1500), blob-hash visible:

```
;=> {…/status 200, …/url "https://example.com", …/content-type "text/html",
     …/preview "This domain is for use in documentation examples…",
     …/total-tokens 37, …/ok? true, …/links [{…/href … …/label "Learn more"}],
     …/truncated? false, …/extractor :readability,
     …/blob-hash "f37a0133…", …/preview-tokens 37,
     …/hint "extracted only ~37 tokens — the page may be script-rendered…",
     …/title "Example Domain", …/final-url "https://example.com"} ; result/Bmt
```

`blob-hash` resolves via `my.blob/text` paging (verified). Clean and honest
for a small page. At offset 5 → clips to 50 tokens mid-`:preview` with
`(200 of 958)`. **Latent risk (same class as shell):** the fetch envelope has
14 keys; a LARGE page pushes the envelope past 1500 chars → render-ai bounds
to 8 keys → `blob-hash` (key #12 by insertion here) would be at risk of
elision exactly when the page is big enough to need it. Could not reproduce
with a large real URL under the flaky shared pod; flagging analytically.

### Binary / media blob (orchestrator addendum)

Tiny real 1×1 PNG → `my.blob/put! {:content <bytes> :media :png}`:

- **put** → `{:my.blob/ok? true, :my.blob/hash "2407…", :my.blob/tokens 17}` —
  clean projection, NO raw bytes/base64 in the envelope. (The agent's own
  source echo does contain the byte string, but that's the echoed form, not
  the response.)
- **stat** → `{…/ok? true, …/hash "2407…", …/exists? true, …/tokens 17,
  …/at #inst "…", …/media :png}` — clean, media recorded, no bytes. GOOD.
- **text** → **DEFECT:** `{:my.blob/ok? true, :my.blob/tokens 17,
  :my.blob/content "‹M-^I›PNG\r\n^Z\n…"}` — returns the raw PNG bytes as
  latin1 **mojibake** with `ok? true`, ignoring `:media :png`. Should return
  an honest not-text refusal envelope. Decay renders the mojibake like any
  string (no special handling).

## Recovery-handle usability summary

| handle | mechanism | usable? |
|---|---|---|
| `result/<id>` (live, this process) | globalThis stash | YES — `(count result/…)` → real value |
| `result/<id>` after prune (>200 vars) | graceful-miss | **row still shows it; resolves to "isn't live … re-run its form"** (Finding H1) |
| `result/<id>` PRIOR session | handle DROPPED from row | correct (resume marker explains once) |
| blob hash (my.blob/`text` paging) | content-addressed | YES — pages with honest totals |
| shell `out-blob` | A6.6 | **hash elided from render** (Finding S1) |
| web `blob-hash` | fetch | YES for small pages; at-risk on large (14-key envelope) |
| fs re-view `from-line` | disk paging | YES — re-window returns the tail |

### Finding H1 — pruned handle rendered as live (verbatim contrast)

Same value, before and after driving >200 subsequent evals (cap
`SEON_EVAL_RESULT_VARS_CAP` = 200, `live-result-count` measured = 200):

```
; LIVE row:
(vector :sentinel-value 12345 :keep-me)
;=> [:sentinel-value 12345 :keep-me] ; result/WSa-2607061506
;   resolve → {:ok? true, :value [:sentinel-value 12345 :keep-me]}

; AFTER PRUNE (byte-identical row):
(vector :sentinel-value 12345 :keep-me)
;=> [:sentinel-value 12345 :keep-me] ; result/WSa-2607061506
;   resolve → {:ok? true, :value "result/WSa-2607061506 isn't live (a prior
;              session, or pruned past the last 200 results) — re-run its
;              form to recompute it. …"}
```

`format-eval-row` gates the handle only on `prior?` (previous process), never
on stash liveness, so within one long session a row keeps advertising a dead
handle. Mitigated by the graceful miss (no crash; small values still show
their `result-edn` inline). But for a CLIPPED large value in a long session,
the pruned handle means the full value is unrecoverable except by re-running —
which ties directly to Finding P1.

### Finding P1 — drill hint teaches recovery, not durability

`render/value.cljs` `bounded-view` (~l.412-425) emits:

```
; ‹partial view of <type N>› — the COMPLETE value is result/<id>  (get-in result/<id> […]) · filter · count · take/drop
```

It teaches transient recovery (`result/<id>` + navigation) only. For a big
value the agent wants to KEEP across turns/prune, there is no promotion idiom.
Recommended addition:

```
… — the COMPLETE value is result/<id> · keep: (my.blob/put! result/<id>) · (get-in result/<id> […]) · filter · count · take/drop
```

## Findings table

| # | site | shape-preserved? | honest marker? | handle present+usable? | garbage? | sev |
|---|---|---|---|---|---|---|
| S1 | shell out-blob elided by 8-key bound | n/a | partial (hint truncated) | **NO — named handle invisible** | recovery handle hidden | HIGH |
| H1 | pruned `result/<id>` shown as live | yes | **no dead-handle mark** | resolves to miss string | dishonest live-looking handle | HIGH |
| B1 | `my.blob/text` mojibake on binary | n/a | **no — ok? true** | n/a | raw bytes as text | HIGH |
| D2 | nested clip cuts own recovery hint | yes | double marker | yes | guidance cut mid-word | MED |
| G1 | grep-graph `hint` truncated to 80ch | yes | yes | yes | actionable hint cut | MED |
| U1 | mixed units in one row (`(200 of 3919)` chars vs "tokens") | yes | yes | yes | confusing | MED |
| W1 | web `blob-hash` at-risk of elision on large pages | n/a | yes (small) | yes (small) | latent (same class as S1) | MED |
| P1 | drill hint lacks my.blob/put! promotion | yes | yes | recovery only | — | MED |
| V1 | fs/view (no window) collapses content to ~80ch | yes | yes (`⟨878 tokens⟩`) | deref/re-window | poor default | LOW |
| D1 | 16384 & 1500 decay levels ~identical in practice | yes | yes | yes | dead-weight level | LOW |
| E1 | mid-string clip → invalid EDN body | keys valid, string not | yes (marker) | yes | not re-readable | LOW |
| N1 | `nil` value carries a `result/<id>` handle | yes | yes | trivially | minor noise | LOW |
| T1 | tier eviction inert (`::tiers []` default) | n/a | n/a | n/a | dead path in default cfg | INFO |

## Prioritized fix list

1. **S1 (HIGH) — surface shell `out-blob`/`err-blob` above the render bound.**
   render-ai's 8-key map bound silently drops the recovery handle A6.6 added.
   Options: (a) render-ai keeps `*-blob`/recovery keys pinned when eliding
   (a "keep these keys" hint on the envelope), or (b) `shell/run` folds the
   blob hash into the `hint`/`truncated?` line (already shown) so it survives
   regardless of key count. Preferred: (b) — put the blob hash in the same
   string as `truncated? true`. Re-check web/fetch (W1) under the same fix.
2. **B1 (HIGH) — `my.blob/text` must refuse non-text media.** When
   `:my.blob/media` is a known-binary kind (or the content fails a UTF-8/text
   sniff), return `{:my.blob/ok? false :seon.error/message "blob is binary
   (:media :png) — not text; use get/stat"}`. No mojibake with `ok? true`.
3. **H1 (HIGH) — mark or drop a pruned handle.** Either gate the `; result/<id>`
   handle on stash liveness (not just `prior?`), or append a `(pruned — re-run)`
   note when the id is past the session cap. Ties to P1: a clipped body whose
   handle is pruned is unrecoverable.
4. **P1 (MED) — add durability promotion to the drill hint** (text above). One
   line in `render/value.cljs bounded-view`.
5. **G1 (MED) — exempt `hint`/guidance string fields from the 80-char
   max-string cap** (or raise it for `*/hint` keys) so actionable guidance is
   never cut. Same root as D2.
6. **U1 (MED) — unify units in the eval row.** The `(N of M)` handle marker
   should either be labeled or match the inline marker's unit (tokens). Pick
   ONE unit per the Token Reporting rule (tokens) and label it.
7. **D2/E1 (LOW) — don't stack a size-clip on an already-render-ai-bounded
   body,** and consider clipping at a structural boundary so the shown body
   stays valid EDN (or at least never cuts inside the recovery guidance).
8. **V1 (LOW) — make `fs/view` default to a real window** (or render its
   `content` past the 80-char string cap like `read-file`), so the no-arg view
   isn't near-useless.
9. **D1/T1 (INFO) — reconcile the decay schedule with render-ai reality.**
   Either the middle level (1500) is redundant given store-time bounding, or
   the intent was for bodies to reach 16384 (they don't). And `::tiers` is a
   built-but-unwired eviction path — decide to wire it or note it dormant.

## Complexity artifacts found

- `::tiers` / `clip-events-by-tiers` (transcript.cljs) is a full eviction
  mechanism that is INERT in every default/seeded agent (`::tiers []`). Either
  a manifest should exercise it or it should be documented as dormant. (T1)
- The decay schedule attr is named `::token-cap` and CP-5 comments say
  "tokens", but the value is consumed as CHARS by `clip-or-full` (and its
  defaults 16384/1500/200 mirror the CHAR `store-edn-cap`). Naming vs behavior
  mismatch. (U1)
- Two independent clip machineries stack on one value: render-ai's structural
  bound (store) + `clip_or_full`'s raw char cut (read). The second can cut the
  first's markers (D2). One-clip-in-place would be cleaner but the two serve
  different masters (store safety vs display age) — flag, don't rip.

## Evidence

- Verbatim capture stream: `tmp/a7-capture.edn` (this session).
- Mechanism source: `seon.agent.ctx.transcript` (decay schedule +
  `decay-cap-for-offset` + `clip-events-by-tiers`), `seon.agent.ctx`
  (`format-eval-row`, `cap-result-body`, `clip-or-full`), `seon.render.value`
  (`render-ai`, `bounded-view` drill hint), `seon.eval` (`render-result-edn`,
  `store-edn-cap`, `result-vars-cap` prune), `seon.config` (render caps).
