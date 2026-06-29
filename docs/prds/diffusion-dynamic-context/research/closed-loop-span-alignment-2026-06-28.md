---
type: research
status: active
tags: [research, diffusion, agent, flow]
---

# Closed-loop span alignment — one coordinate system, end to end

> The eval-renoise loop only fixes errors if the parser's char spans and the worker's
> canvas positions live in the SAME char-coordinate basis. The short-circuit (clamp /
> infill) path is proven; the renoise path desyncs because the demo parses
> `partial_text` (joint, `skip_special_tokens=True`) while the worker maps spans against
> `offset_map`, which is built from `canvas_text` (piecewise, `skip_special_tokens=False`).
> This note pins the canonical basis, cites every decode, gives the corrected
> orchestration, and encodes the fix in `scratchpad/closed_loop.py`.

## TL;DR

- **There is exactly ONE canonical string in the loop:** `canvas_text` =
  `build_offset_map(tkz, canvas_ids)[0]` — the PIECEWISE per-token decode with
  `skip_special_tokens=False` (`diffgemma_common.py:202`). `offset_map`'s
  `[pos, char_start, char_end]` ranges index THIS string and nothing else.
- **The oracle MUST parse `canvas_text`, never `partial_text`.** `parse-forms`
  `:span [s e]` are absolute 0-based char offsets into the EXACT string it is handed
  (`internal.cljc:676` + `:595`), VERIFIED live: `"(def mean [[v] ...)"` (19 chars) →
  `span [0,19]`; a second form starting at char 22 → `span [22,35]`. So if the oracle
  parses `canvas_text`, its spans are already in the `offset_map` basis and
  `span_to_positions(offset_map, span)` (`diffgemma_common.py:211`) maps them to the
  right canvas positions.
- **`partial_text` is a DIFFERENT basis on two independent axes** (joint vs piecewise
  decode, and special tokens dropped vs kept). Parsing it and feeding the span to
  `span_to_positions(offset_map, …)` desyncs every position — the exact bug flagged in
  `eval-renoise-worker-build-2026-06-28.md` §7.
- **Corrected loop:** `denoise_to_step` → parse `canvas_text` (local bb oracle) →
  `:error-kind` + char `:span` (in `canvas_text` basis) → `resume_renoise` with those
  spans → the worker rebuilds `offset_map` from `seed_canvas` (== `argmax_per_position`
  == `canvas_ids`), which is byte-identical, so the spans still align → renoise the
  flagged positions. No transform between parse and map.
- **Two real residual mismatches, both already the right call:** (1) `span_to_positions`
  uses OVERLAP (`cs < e and ce > s`), which is the CORRECT tolerance for char-span vs
  BPE-boundary — it frees at most one boundary token per span edge (harmless). (2) The
  ONE latent desync to guard: `parse-forms` runs `strip-code-fences` FIRST
  (`internal.cljc:595`); if `canvas_text` ever contains a fence line it shifts all later
  spans out of the `offset_map` basis. Mitigation below.
- **Two bugs found in `gpu_worker.oracle.patch` (B)** — the `span_to_positions` call has
  its args reversed AND passes the whole errors list where a single span is expected.
  Corrected form below + encoded in the demo.

## The basis of every field the worker returns (cited)

`denoise_to_step` (`gpu_worker.py:476-526`) returns four text-ish fields from the same
partial canvas `comp = out.sequences[0][nprompt:]` (`:502`), `canvas_ids =
[int(x) for x in comp.tolist()]` (`:503`):

| Field | Built by | Decode | Char basis |
|---|---|---|---|
| `partial_text` (`:512`) | `tkz.decode(comp, skip_special_tokens=True)` | **JOINT**, specials **DROPPED** | its own — NOT `offset_map` |
| `canvas_text` (`:513`) | `build_offset_map(tkz, canvas_ids)[0]` (`:504`) | **PIECEWISE** per token, specials **KEPT** | **== `offset_map`** |
| `argmax_per_position` (`:514`) | `canvas_ids` | the raw token ids (no decode) | the SEED for `resume_renoise` |
| `offset_map` (`:515`) | `build_offset_map(...)[1]` | `[[pos, char_start, char_end], …]` over `canvas_text` | the canonical basis |

`build_offset_map` (`diffgemma_common.py:184-208`) is the definition of the basis: it
loops every position, decodes ONE token at a time
`piece = tkz.decode([tid], skip_special_tokens=False)` (`:202`), and sets
`cs = cursor; ce = cursor + len(piece); cursor = ce` (`:203-207`). So `canvas_text` is
the **concatenation of per-token pieces** and the char ranges are cumulative over that
concatenation. `skip_special_tokens=False` is load-bearing and explicit (`:194-197`
docstring): special tokens occupy canvas positions AND char space; dropping them shifts
every downstream range.

### Why `partial_text` desyncs (two independent axes)

1. **Joint vs piecewise.** `tkz.decode([a,b,c])` ≠ `decode([a]) + decode([b]) +
   decode([c])` in general — SentencePiece/BPE merges and the `▁` leading-space rule are
   resolved JOINTLY in `partial_text` but PER-TOKEN in `canvas_text`. Spaces appear/
   disappear at token seams, so identical content has different char offsets.
2. **Specials dropped vs kept.** `partial_text` has `skip_special_tokens=True`; every
   special token present in `canvas_text` (e.g. `<bos>`, `<end_of_turn>`, pads) is ABSENT
   in `partial_text`, shifting every char after the first special token.

Either axis alone breaks the mapping; together they guarantee it. A span computed on
`partial_text` and handed to `span_to_positions(offset_map, span)` indexes the wrong
positions. This is the desync.

## The parser's span basis (cited + live-verified)

`seon.repl.internal/parse-forms` (`internal.cljc:561-677`):

- First line of the body: `(let [text (strip-code-fences text)]` (`:595`). **All spans
  are relative to the post-strip string.**
- A read-failure entry records `:span [offset recovery]` (`:676`) — absolute 0-based char
  offsets into `text`, with `:error-kind` from `classify-read-error` (`:468-513`:
  `:eof` / `:unmatched-delimiter` / `:odd-map` / `:bad-metadata` / `:invalid-token` /
  `:read`).
- `bin/oracle-server` → `seon.repl.internal/parse-forms`, flattening to
  `{forms, tier:"parse", errors:[{error-kind, span, source}]}` (string keys at the wire),
  byte-identical to the Node `:worker-validator` bundle.

**Live proof** (via `bin/oracle-server`, this session):

```
in : (def mean [[v] ...)              ; 19 chars, one stray ]
out: errors:[{error-kind:"unmatched-delimiter", span:[0,19], source:"(def mean [[v] ...)"}]

in : (defn ok [x] (+ x 1))\n(broken (+ 1     ; first form 21 chars, \n at 21, 2nd form @22
out: forms:1, errors:[{error-kind:"eof", span:[22,35], source:"(broken (+ 1 "}]
```

So the parser's span coordinate system IS "char offset into the exact string passed,
post-fence-strip." Feed it `canvas_text` and the spans are in the `offset_map` basis with
ZERO conversion.

## The one canonical coordinate system, end to end

```
denoise_to_step (GPU)
  comp = out.sequences[0][nprompt:]                    # gpu_worker.py:502
  canvas_ids = comp.tolist()                           # :503  -- THE seed identity
  canvas_text, offset_map = build_offset_map(tkz, canvas_ids)   # :504  -- THE basis
         │
         │  (parse the SAME string offset_map indexes)
         ▼
oracle.parse(canvas_text)                              # bb seon.repl.internal/parse-forms
  → errors:[{error-kind, span:[s,e], source}]          # span in canvas_text == offset_map basis
         │
         │  spans = [e["span"] for e in errors]
         ▼
resume_renoise (GPU)   seed_canvas = argmax_per_position (== canvas_ids)
  seed_text, seed_offset = build_offset_map(tkz, seed_ids)      # :548  -- REBUILDS the SAME basis
  good_clamp, bad = good_clamp_for_renoise(seed_offset, seed_ids, spans)  # :549
         → span_to_positions(seed_offset, span) OVERLAP per span          # common.py:222
         → clamp every GOOD position, free the BAD span positions, re-denoise
```

The invariant that makes it sound: `resume_renoise` does NOT trust a caller-supplied
`offset_map`; it RECONSTRUCTS it from `seed_canvas` via `build_offset_map(tkz, seed_ids)`
(`:548`). Because `seed_canvas == argmax_per_position == canvas_ids` and
`build_offset_map` is a deterministic pure function of the ids, `seed_offset` is
byte-identical to the `denoise_to_step` `offset_map`. So a span computed against
`canvas_text` in step 1 lands in the identical basis in step 2. **One basis, derived the
same way at both ends, never serialized across the gap.** (The serialized `offset_map`
returned by `denoise_to_step` is for the pod/observer; the renoise math re-derives it.)

## Residual mismatches — flagged, with the call

1. **BPE boundary vs char span → OVERLAP is correct (keep it).** `span_to_positions`
   (`common.py:211-222`) selects every position whose `[cs,ce)` OVERLAPS `[s,e)`
   (`cs < e and ce > s`, `:222`), NOT containment. Parser spans and token boundaries do
   not align, so overlap is the right tolerance: a span ending mid-token frees that whole
   token. Worst case it frees ONE extra boundary token on each edge of the span (e.g. an
   adjacent paren) — harmless to re-noise, and necessary (a half-token can't be renoised).
   This is the intended granularity (`eval-renoise-worker-build` §7 nuance 6). No change.

2. **`strip-code-fences` is the ONE latent desync — guard it.** `parse-forms` strips
   markdown fence LINES before computing spans (`internal.cljc:595` + `:106-111`). If
   `canvas_text` ever contains a line matching `^[ \t]*(```|~~~)(lang)?[ \t]*$`, the strip
   removes it and shifts every subsequent span OUT of the `offset_map` basis (which was
   built over the UN-stripped `canvas_text`). For a "reply with ONLY the code" prompt the
   canvas is fence-free and special tokens are not fence-shaped, so in practice this is a
   no-op — but it is a real latent off-by-N. **The call:** keep the canonical basis the
   UN-stripped `canvas_text`, and ensure the oracle does not strip when operating on it.
   Two clean options (pick one when wiring P3):
   - (a) cheapest/now: rely on fence-free code canvases; ASSERT
     `canvas_text == strip-code-fences(canvas_text)` in the demo (it is, for code
     prompts) so a regression is loud, OR
   - (b) robust: add a no-fence-strip parse entry the oracle uses for canvas text (the
     fence tolerance exists for human/LLM chat replies, not for a token canvas the worker
     already owns byte-for-byte). Do NOT instead rebuild `offset_map` over the stripped
     text — that splits the basis derivation between the two ends and reintroduces drift.

   **DONE (2026-06-28) — option (b) is BUILT + locally proven (no GPU).** The ONE fn was
   extended, not forked: `parse-forms` now takes `{:strip-fences? bool}` (default true), and
   the oracles expose a no-strip path:

   - `seon.repl.internal/parse-forms` (`internal.cljc:594`) — `[text & [{:keys
     [strip-fences?] :or {strip-fences? true}}]]`; `false` skips `strip-code-fences` so spans
     stay absolute into the EXACT input.
   - `bin/oracle-server` — new `op:"parse-raw"` (and a per-request `"strip-fences":false`
     override on any parse op) → `validate code false`.
   - `seon.worker_validator.cljs` — mirrored: `validate`/`validate-json` take `strip-fences?`;
     `serve!` accepts the object framing (`{op:"parse-raw"}` / `{strip-fences:false}`) AND the
     historical bare-string line. Byte-identical to the bb output.

   **Local proof** (same fenced input ` ```clojure\n(def mean [[v] ...)\n``` `, both runtimes):

   ```
   default parse  → span [1,21]    ; relative to the STRIPPED string (fence line removed)
   parse-raw      → span [11,34]   ; ABSOLUTE into the raw fenced string (form @ char 11,
                                   ;   right after "```clojure\n") == canvas_text/offset_map basis
   strip-fences:false → span [11,34]
   ```

   `bin/test-parser` green (19 tests / 238 assertions). **Closed-loop driver:** parse
   `canvas_text` with `op:"parse-raw"` (NOT the default `parse`) so the returned `:span`
   offsets index the raw `canvas_text` the `offset_map` was built over.

3. **Piecewise fidelity (MUST MEASURE once live).** Because the oracle parses the
   PIECEWISE `canvas_text` (not the human-readable joint `partial_text`), per-token `▁`/
   space artifacts could make the parser flag structure that a joint decode would not (or
   miss one). The parse is structural (paren balance, read-error kinds) and largely robust
   to space placement, but confirm on the first live run: parse BOTH `canvas_text` and
   `partial_text`, and check the error set is materially the same modulo whitespace. If
   piecewise introduces spurious read errors, that is a fidelity issue to characterize —
   NOT a reason to parse `partial_text` (which would reintroduce the desync). The basis
   stays `canvas_text`; the artifact, if any, gets normalized at the token level, never by
   switching strings.

## Bugs in `gpu_worker.oracle.patch` (B)

The hand-applied co-location patch parses the RIGHT string (`oracle.parse(canvas_text)`)
— good — but the very next line is wrong twice:

```python
info["renoise_positions"] = span_to_positions(pr["errors"], offset_map)   # WRONG
```

- **Args reversed.** The signature is `span_to_positions(offset_map, span)`
  (`common.py:211`), not `(span, offset_map)`.
- **A list where a single span is expected.** `pr["errors"]` is a LIST of error maps;
  `span_to_positions` wants ONE `[s,e]`. The error maps aren't even bare spans — the span
  is `e["span"]`.

Corrected (union over all flagged spans, mirroring `good_clamp_for_renoise`):

```python
oracle = _oracle()
if oracle is not None:
    pr = oracle.parse(canvas_text)                 # parse THE basis string (correct)
    spans = [e["span"] for e in pr.get("errors", [])]
    bad = sorted({p for sp in spans
                    for p in span_to_positions(offset_map, sp)})
    info["oracle"] = "local-bb"
    info["parse"] = pr                             # {forms, tier, errors:[{error-kind,span,source}]}
    info["renoise_spans"] = spans                  # feed straight to resume_renoise
    info["renoise_positions"] = bad                # for reporting / single-call loops
```

`renoise_spans` (char spans, canvas_text basis) is what `resume_renoise` consumes
directly; it re-derives positions itself, so the worker never has to trust
`renoise_positions` (that field is diagnostic only).

## Minimal change to the closed loop (`scratchpad/closed_loop.py`)

Encoded in `scratchpad/closed_loop.py` (gitignored; owner runs it against the live
endpoint). The shape:

1. `denoise_to_step` via the RunPod API (`client.py:api`) → `canvas_text`,
   `argmax_per_position`, `offset_map`.
2. **Parse `canvas_text`** with the local bb oracle (`oracle_shim.Oracle` →
   `bin/oracle-server`) — NOT `partial_text`. Assert
   `canvas_text == strip-equivalent` (fence-free) so the basis guard is loud.
3. `spans = [e["span"] for e in parse["errors"]]`. If empty → already parse-clean, stop.
4. `resume_renoise` with `seed_canvas=argmax_per_position`, `renoise_spans=spans`.
5. Re-parse the returned `canvas_text`; report `errors_before` vs `errors_after` +
   `good_held`. THAT is the closed-loop proof (span-targeted renoise reduces parse
   errors).

The single load-bearing line — the whole point of this note — is step 2 parsing
`canvas_text` and step 4 feeding those spans straight through, because
`resume_renoise` rebuilds the identical `offset_map` from the same `seed_canvas`.

## Entry points (depth)

- `tmp/flash-diffgemma/gpu_worker.py:476-604` — `denoise_to_step` / `resume_renoise`.
- `tmp/flash-diffgemma/diffgemma_common.py:184-248` — `build_offset_map` /
  `span_to_positions` / `good_clamp_for_renoise` (the basis + the overlap map + the dial).
- `src/seon/repl/internal.cljc:561-677` — `parse-forms` (`:595` strip, `:676` span).
- `tmp/flash-diffgemma/oracle_shim.py` + `bin/oracle-server` — the co-located parse tier.
- `tmp/flash-diffgemma/gpu_worker.oracle.patch` — the co-location wiring (fix its (B)).
- [[eval-renoise-worker-build-2026-06-28]] §7 — the original flag this note resolves.
</content>
</invoke>
