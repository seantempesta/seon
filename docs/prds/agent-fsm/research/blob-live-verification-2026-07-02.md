---
type: research
status: active
tags: [research, agent]
---

# `my.blob` live verification — uncoached DeepSeek drive (2026-07-02)

## Verdict: **WORKS-WITH-FRICTION**

A real DeepSeek child agent, given a large paste and an uncoached natural-language
task ("keep this so you don't bloat your context; tell me lines 120-130 and the
total size"), **chose `my.blob` immediately and unprompted** — first eval of the
turn was `(require '[my.blob :as blob])`, second was `my.blob/put!`. No other
storage mechanism (a `my.kb.*` entity, a raw reply dump) was ever attempted. That
is the headline pass.

But the naive path — pasting the whole document as one string literal into one
`put!` call — **breaks on real-size content**: the agent's own eval-emission
budget truncates the form before it parses, so it never gets one canonical blob.
It self-heals by manually chunking into **5 separate content-addressed blobs**,
which works functionally (all 5 puts succeeded, content is retrievable) but
**destroys the single-source-of-truth "honest totals" guarantee** — the agent
had to hand-compute a total line/event count from chunk arithmetic instead of
reading an authoritative `:my.blob/total-lines`, and got it wrong, **twice,
consistently, across both turns** (off-by-one on "line N" vs "event-000N", and
an arithmetic total-count error). On the second turn, in a later message, it
correctly used `blob/text` with `:from-line`/`:max-lines` against two of its
stored chunk hashes to retrieve a different slice — genuine paged retrieval,
not just re-reading the still-in-context original paste.

## Pre-check — card wiring (PASS)

`(seon.agent.inspect/ctx-preview {:seon.agent/id "pbg-2607021121"})` renders
`my.blob` with full schema + fn signatures + docstrings (NOT name-only):

```
;;; ┌─ namespace my.blob ─
(register! ::at :inst)
... [10 register! lines] ...

; fns (body elided):
(defn get "Full blob content by hash (sync) — for CODE, not for your reply." ...)
(defn put! "Persist `::content` content-addressed; record its projection." ...)
(defn stat "The blob's DB projection — exists?, tokens, media, at; no disk touched." ...)
(defn text "A paged line window of a blob (sync) — honest totals, never the lot." ...)
;;; └─ end namespace my.blob ─
```

`my.blob` is also aliased as `blob` in the child's own home namespace
`my.agent.<id>`'s `:require` block, alongside `message`/`todo`/`kb`/`data`/`ui`/
`tile` — full parity with the rest of the toolkit. No wiring defect; drive
proceeded.

## Server-side output-discipline checks (all PASS)

Run directly against the live pod (not through the driven agent), using a
self-generated 301-line / 298-event synthetic diagnostic log
(`24493` bytes, sha-256 `51f96522…2488a7`):

- **Idempotent content addressing**: `put!` called twice on byte-identical
  content returned the **same hash** both times; `(count (?e :my.blob/hash
  "51f965…"))` = **1** row. No duplicate datom.
- **`text` default cap**: no paging args → `{:my.blob/from-line 1
  :my.blob/lines-returned 100 :my.blob/total-lines 301}` — the advertised
  100-line default cap, with an honest total.
- **`text` paging accuracy**: `{:hash … :from-line 120 :max-lines 11}` returned
  exactly lines 120–130 of the source file, byte-for-byte matched against the
  file directly (`sed -n '120,130p'`).
- **Error envelope**: `(blob/text {:my.blob/hash "nonexistent-hash-xyz"})` →
  `{:my.blob/ok? false :my.blob/error "not a sha-256 hex hash: … — use the
  :my.blob/hash a put!/stat returned"}` — guiding, not a raw throw.

## Drive evidence — turn 1 (verbatim eval sources, from `seon.eval`)

Child `pbg-2607021121`, spawned via `(seon.db/with-agent "root" (fn []
(seon.agent/start! {:seon.agent/purpose "blob-verification test child A"})))`.
Message sent as the human (`origin :human`) containing the full 301-line log
plus: *"Keep this document so you can consult it later without bloating your
context; then tell me what's on lines 120-130 and how big the whole thing is."*
`my.blob`/`blob` was never named.

**Eval 1** (ok): `(require '[my.blob :as blob])`

**Eval 2** (FAILED): `(my.blob/put! {:my.blob/content "=== SYSTEM DIAGNOSTIC
LOG — cluster-7 ===\n...` — the ENTIRE pasted document inline. Error:

```
READ ERROR — this form did not parse, so it DEFINED NOTHING.
Unexpected EOF while reading string. at line 125, col 50: ...
This form was likely TRUNCATED because it was too large to emit — it ran past
your output budget and ended mid-form, so it DEFINED NOTHING and sent NOTHING.
Don't try to re-emit the whole thing: STORE the long content as data (a
my.kb.* entity or a :seon.items envelope), then send a SHORT pointer...
```

**Eval 3** (FAILED — hallucinated fn from misreading its own truncated plan):
`(without the header/footer instructions)` → `my.agent.pbg…/without is not
defined`.

**Eval 4** (ok): re-required `my.blob`.

**Eval 5** (ok, but content is a FRAGMENT, not the whole doc): `(my.blob/put!
{:my.blob/content "=== SYSTEM DIAGNOSTIC LOG …"})` → `{:my.blob/ok? true
:my.blob/hash "4a36f4f7…" :my.blob/tokens 1977}` — **1977 tokens**, i.e. ~100
lines, not the full ~5973-token document.

**Eval 6** (FAILED, same truncation) attempting the next chunk in one shot.

**Eval 7** (ok): `(blob/text {:hash "4a36f4f7…" :max-lines 5})` — the agent
verified its own stored chunk by reading it back.

**Evals 8–17** (interleaved ok/fail): the agent hand-chunked the remaining
document into ~1000-token pieces, retrying truncated attempts, eventually
landing **5 total blobs** covering the whole log:

| hash (prefix) | events covered | tokens |
|---|---|---|
| `4a36f4f7…` | 0003–0100 | 1977 |
| `2852b812…` | 0101–0150 | 1000 |
| `733aa550…` | 0151–0200 | (not directly observed, inferred from reply) |
| `a1c19cf0…` | 0201–0250 | 1000 |
| `6f6f388d…` | 0251–0300 | 1002 |

**Final reply** (`message/user`, verbatim):

```
**Lines 120-130** (events 0120–0130):
| Event | Timestamp | Level | Service | Latency | Status |
| 0120 | 10:00:00 | INFO | auth-gateway | 360ms | ok |
...
| 0130 | 10:10:10 | ERROR | inventory-db | 390ms | **degraded** |

**Size of the whole log:**
- **300 events** (event-0003 through event-0300), plus 2 header lines
- **302 total lines**
- The log has been stored across 5 blobs so you can query it without
  bloating context: [5 hashes listed]
```

**Accuracy check against the real source file** (`sed -n`, `wc -l`, python
split — all agree, total = 301 lines, 298 events, event-N is always at line
N+1):

- **WRONG**: agent's "lines 120-130" table is actually **events 0120-0130**,
  which live at file lines **121-131**, not 120-130. `(my.blob/text {:hash
  "51f965…" :from-line 120 :max-lines 11})` called server-side by the verifier
  proves true line 120 = event-0119, true line 130 = event-0129 — a
  **consistent off-by-one** between "line number" and "event number."
- **WRONG**: "300 events" — actual event count is **298** (0003..0300
  inclusive = 298, not 300).
- **WRONG**: "302 total lines" — actual is **301** (verified both by `wc -l`
  contract and by `my.blob/text`'s own `:total-lines` field on the canonical
  single-blob copy the verifier stored separately).
- Notably, **no `blob/text` call in turn 1 targeted the 120–130 range at
  all** — the agent answered from the raw paste still sitting in its own
  turn context, not from retrieval. The "consult later without bloating
  context" framing was honored for STORAGE but not exercised for RETRIEVAL
  in this turn.

## Drive evidence — turn 2 (separate later message, cross-turn recall)

Second human message, later turn, still uncoached: *"what's on lines 250-260 of
that document, and remind me of the exact total line count again?"*

This time the agent's evals show real retrieval:

```
(blob/text {:my.blob/hash "de2efca0…" :my.blob/from-line 50 :my.blob/max-lines 1})
=> {... :my.blob/content "…event-0250…" :my.blob/from-line 50 :my.blob/total-lines 50}

(blob/text {:my.blob/hash "6f6f388d…" :my.blob/from-line 1 :my.blob/max-lines 10})
=> {... :my.blob/content "…event-0251…event-0260…" :my.blob/total-lines 50}
```

It correctly identified which of its 5 stored chunks held the requested range
and paged into each — genuine use of the retrieval API, not a context re-read.
Final reply repeated the **same systematic errors**: "events 0250-0260" (真实
line range is 250-260 → events 0249-0259) and "302 lines" (still wrong, still
298 events + fabricated arithmetic).

**Context caveat**: `ctx-preview` on this child showed the ORIGINAL 24KB pasted
message text is still rendered verbatim in the transcript at the second turn
(found byte-for-byte, offset ~92809 in a 144KB prompt) — so this was not a
"forced" retrieval in the sense that the raw text was unavailable; the agent
chose to go to blob anyway. That is still a meaningful, positive signal about
tool-choice, but it also means the transcript renderer does **not** currently
prevent the exact context-bloat `my.blob` exists to avoid — a large human paste
sits in the prompt on every subsequent turn regardless of whether it was also
blobbed. That is a separate, real finding about `seon.agent.ctx.transcript`,
outside `my.blob`'s own scope.

## Root-cause analysis

1. **`put!`'s contract assumes the whole content fits in one eval-emitted
   string literal.** For a real "long document" (this one was ~24KB / ~6000
   tokens — well inside `my.blob`'s own paging design, e.g. under
   `text`'s 100-line default cap by 3x) the DRIVING AGENT's own per-eval
   output/emission budget is the actual bottleneck, not `my.blob`. Observed
   truncation points: first attempt died around line 125 (~11.5KB emitted),
   second attempt died around line 20 of a smaller remainder (~2KB emitted) —
   so the safe ceiling for one `put!` call, for this model/turn, is roughly
   **~100 lines / ~2000 tokens of literal content**, well under a full
   document.
2. **No supported chunk-and-assemble path exists.** The generic truncation
   guidance ("STORE the long content as data … then send a SHORT pointer")
   does not mention `my.blob`, does not suggest a multi-call append pattern,
   and does not warn the agent that fragmenting into N independent `put!`
   calls produces N unrelated hashes rather than one canonical blob. The
   agent had no better option than ad hoc chunking, and it chunked
   correctly (functionally) but lost the very thing `my.blob/text`'s
   "honest totals, never the lot" docstring promises: **the total is only
   honest per-chunk once the document is fragmented** — there is no
   aggregate `stat` across a set of related hashes.
3. **Fragmentation is the direct cause of both accuracy bugs.** Had the
   whole document landed in one blob, the agent could read `:my.blob/total-lines
   301` straight off `blob/stat`/`blob/text` and never touch arithmetic. Because
   it didn't, it back-computed a total from "last event number − first event
   number," an error-prone shortcut, and got it wrong both turns.
4. **The off-by-one (line N ≠ event-000N) is a task/domain quirk** (my
   synthetic document's events start at 0003, and there's a 3-line
   title/blank header) — not a `my.blob` defect per se, but it's exactly
   the kind of error a genuinely-paged read (`from-line`/`lines-returned`)
   would have prevented if the agent had trusted the tool's own line
   numbering instead of eyeballing embedded event numbers.

## Suggested fixes (not applied — verification only, no `src/` edits made)

- **`my.blob/put!` docstring**: state the practical single-call size ceiling
  explicitly (e.g. "~100 lines / ~2K tokens of literal content per call — for
  larger content, see `put-append!`") so an agent knows to plan chunking
  *before* it burns a truncated eval.
- **Add a concat/append primitive** (`my.blob/put-append!` or similar) so
  multiple `put!`-sized calls assemble into ONE canonical hash + ONE honest
  `:total-lines`, instead of N independent content-addressed rows. This is
  the single highest-leverage fix — it would have prevented both accuracy
  bugs in this drive.
- **Truncation-guidance text** (wherever the generic "form too large to emit"
  message lives) should name `my.blob`/the append pattern specifically when
  the truncated form was itself a `put!` call, rather than the generic
  "store as a `my.kb.*` entity" hint that doesn't apply to already-in-flight
  blob work.
- Separately (not `my.blob`'s scope): `seon.agent.ctx.transcript` renders a
  large human paste verbatim on every later turn, undermining the
  context-bloat motivation for blobbing it in the first place — worth a
  follow-up look at whether transcript rendering should elide/pointerize a
  human message once its content has been blobbed.

## Method notes

- Three children were minted during the session (`epj-2607021121`,
  `KrZ-2607021121`, `pbg-2607021121`) — the first two were orphaned by
  `start!`'s async-await pitfall (evaluating `(db/with-agent "root" (fn []
  (start! …)))` alone returns an unresolved `js/Promise` in this raw
  shadow-cljs REPL, unlike the agent-eval path where `maybe-await-value`
  auto-awaits). All three were `seon.agent.lifecycle/terminate`d at the end
  of the session; only `pbg-2607021121` was ever messaged/driven.
- Mid-drive, the live pod restarted (visible in `logs/pod.log`: `agent
  roster {:resumed […] :minted []}`) — caused by other concurrent activity
  in the shared multi-agent tree, not by this verification. The wire-server
  store is durable; all prior evals/messages/blobs for `pbg-2607021121`
  survived the restart intact and the drive continued without data loss.
- Cluster was **not** reset by this verification.

## Files / entities referenced

- `src/seon/agent/inspect.cljs` — `ctx-preview` (pre-check render)
- `my.blob` namespace (agent-authored, indexed in boot build + `home-requires`)
- Child agent `pbg-2607021121` (terminated) — evals queried via
  `[:seon.eval/source :seon.eval/ok? :seon.eval/error :seon.eval/result-edn]`
  pulls on `[?e :seon.eval/agent ?a]` where `?a` is the child's entity id
- Synthetic test document: `/private/tmp/claude-501/-Users-sean-src-seon/07b38944-aecd-4aaf-b54c-bd8cc73619b9/scratchpad/diag-log.txt`
  (301 lines, sha-256 `51f96522678afe55f7ea6cea1a570ae1170d3445477f665fd96463462e2488a7`)
