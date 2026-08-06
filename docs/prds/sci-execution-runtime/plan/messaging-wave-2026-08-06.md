---
type: prd
status: active
tags: [prd, agent, runtime, repl]
---

# Messaging implementation wave — lane spec (2026-08-06)

Every ruling is in hand. Authorities each lane READS END TO END before
editing (state that you did): the two "Rulings 2026-08-06" batches plus
the "Ruling 2026-08-06 (owner, next session)" spelling block in
[README.md](README.md), and the three research reports:
[result-identity-archaeology-2026-08-06.md](../research/result-identity-archaeology-2026-08-06.md),
[result-symbol-resolution-2026-08-06.md](../research/result-symbol-resolution-2026-08-06.md),
[result-handle-clojure-idiom-2026-08-06.md](../research/result-handle-clojure-idiom-2026-08-06.md)
— together the dependency ledger (pins + seams + acceptance matrices).

## The ruled substance (compressed; the README blocks are authoritative)

- `(my.message/send to value)` and `(my.run/complete value)` take ONE
  value of any admitted shape; the vector idiom carries mixed content.
  No variadic forms, no reply strings.
- EXPLICIT addressing only. `message/reply` and the answering-us
  terminator machinery are DELETED; the chain bound survives as a pure
  backstop.
- `(my.run/wait send-value)` parks until the next message from that
  agent addressed to us OR the close of the run its message triggered
  (custody edge — completion-as-safety-net). A vector of sends wakes on
  ANY. A BARE `(my.run/wait)` is REFUSED — done means `complete`.
- Mid-turn messages interleave honestly at their arrival ordinal; no
  faked check-messages forms.
- Result handles: `result/eid-N` symbol face (family `message/eid-N`,
  `error/eid-N`); one parsed reader event detects literal executable
  handles; preparation on the turn's `:io` pulls receipts from the
  form's pinned database value, loads blobs transparently, transiently
  interns the STORED RESULT VALUE into the fresh turn fork. Quote stays
  quote. Missing eid binds a flat absent-at-basis value. Zero SCI fork
  changes. Every eval's rendered face ends with the one sanctioned
  trailing comment ` ; result/eid-<receipt-eid>`.
- The prompt line is a DERIVED awaiting-you nag over committed wait
  facts, gone when the reply commits.
- Background work: wait accepts the durable effect-receipt ref; its
  completed handle is `result/<receipt-eid>` (same family).
- Ids: a durable row's id IS its Datahike eid (archaeology verdict);
  run/message/error pre-commit UUIDs are API shape to DELETE where the
  lane meets them, per the id-policy ruling.

## Lanes (file-disjoint; launch order below)

### Lane M1 — value contracts (`my.run`, `my.message`, schemas)

Owns: `src/my/run.clj`, `src/my/message.clj`, their schema declarations
under `resources/seon/schemas/`, their tests. Replace the string-only
contracts with one-value forms. `wait` takes the send's return value or
a vector of them (bare call → flat refusal naming `complete`).
OPEN QUESTION to report (not decide): does `my.message/decline`
survive explicit addressing, or is it assignment machinery the
one-value send now covers?

### Lane M2 — result-handle mechanism (reader + eval + loop seam)

Owns: `src/seon/sci/reader.cljc`, `src/seon/sci/eval.clj`, the
preparation seam in `src/seon/cluster/loop.clj` (the
`db-before-evaluation` capture → `submit-evaluation!!` window ONLY),
tests. Implement the idiom report's §"Recommended implementation
boundary" exactly; retain its acceptance matrix (positions, basis,
storage, workload, no-handle benchmark).

### Lane M3 — loop terminal path + wake derivation (AFTER M2 lands its

loop seam)

Owns: `src/seon/cluster/loop.clj` (terminal path), `src/seon/cluster/
message.clj`, `src/seon/cluster/work.clj`, wake derivation, tests.
Delete reply synthesis + answering-us; commit wait facts at settlement;
wake on awaited-agent message or answering-run close (custody edge,
never inference); vector wakes on any; honest mid-turn interleave
ordinals.

### Lane M4 — faces (transcript + prompt line)

Owns: `src/seon/render/transcript.clj` and the prompt-line render site,
tests. Trailing handle comment appended from the receipt eid the entry
already carries (never a blob read); honest mid-turn message interleave
at arrival ordinal; the awaiting-you nag derived per turn from wait
facts.

### Lane M5 — teaching (`(help)` + bootstrap)

Owns: `src/seon/bootstrap.clj` and the help surface, tests. Teach: one
value + vector idiom; wait-takes-what-you-await; done means complete;
the handle family and the one sanctioned trailing comment;
answer-your-waiters-first beside ruling #52's errors-first beat.

## Ordering and bars

M1, M2, M4, M5 launch in parallel (disjoint). M3 launches when M2's
loop seam is committed. Zero new reds is the per-lane bar until the
green bare gate; foreign breakage blocks verification, never a lane's
own coherent path-limited commit. Old tests pinning deleted paths are
deleted in the same commit; replacements assert the surviving
mechanism. Database data is DISPOSABLE (owner 2026-08-06): reset and
wipe scratch clusters freely; never write migration code.

## Feedback (standing)

Every lane reports ugly/cryptic rendered output it meets, names the
shape and surface, and files or updates one issue note before
returning.
