---
type: research
status: active
tags: [research, agent, flow]
---

# Transcript tx-report clamp — handoff for the transcript agent

## TL;DR

In the live transcript, an eval that calls `seon.db/transact!` renders its
ENTIRE raw datahike tx-report instead of its meaningful value. On a fresh
seed this single row is ~1,400 of ~3,267 transcript chars (~43%) — pure
noise the agent never wrote. The value should be clamped/summarized to
`{:seon.db/ok? true}` (or an `eval-render-cap`-style summary) at the
value-line in `format-eval-row` (your lane), exactly like other large
values are clamped. Do NOT change `seon.db/transact!`'s return shape — the
raw report is a deliberate escape hatch; only the transcript RENDER of it
should be clamped.

## WHAT (the bug)

`seon.db/transact!` returns, on success:

```clojure
{:seon.db/ok?       true
 :seon.db/tx-report <raw datahike report>}
```

The eval log stores that whole map as `:seon.eval/result-edn`. The
transcript then renders the whole map verbatim. The raw datahike report
carries everything: full `:tx-data` (every datom, including internal
datoms the agent never wrote — schema installs, tx-meta, etc.),
`:db-before` / `:db-after` snapshots, a `:db/commitId` uuid, and a
request-id. For a one-line `transact!` of a couple attrs the agent sees a
~1,400-char wall of machine internals where the only thing it cares about
is "it committed."

This is NOT a cap problem. `eval-render-cap` is 1,500 and
`core-eval-render-cap` is 50,000 (`src/seon/ctx.cljs` ~323–348). The
tx-report dump at ~1,400 chars sits UNDER both caps, so the existing clip
never fires. The value is faithfully (and uselessly) rendered whole.

## EVIDENCE

Other evals in the same transcript render as `=> []` (and similar small
values) — proof the renderer is just faithfully echoing
`:seon.eval/result-edn` and that the transact! value alone is the
offender because nothing summarizes it. The raw report only shows up
where `transact!` is the form; every non-transact eval is tight.

Confirm in the live pod (agent EMZ-2606181326, HTTP 7890) by reading the
fresh-seed transcript and locating the `transact!` row — it is the only
multi-hundred-char `=>` body on a fresh seed.

## WHERE (your lane)

`format-eval-row` in `src/seon/ctx.cljs` (~503–617). The success
value-line is built in the `ok?` branch (~578–599):

```clojure
ok?
(let [raw     (str (or res "nil"))      ; res = :seon.eval/result-edn
      full    (count raw)
      clipped? (> full limit)
      v       (cap-result-body raw limit eid)
      handle  ...
      lines   (str/split-lines v)]
  (str "=> " (first lines) handle ...))
```

`res` is the stored `:seon.eval/result-edn` — the full
`{:seon.db/ok? true :seon.db/tx-report …}` map. There is no summarization
step before `(str (or res "nil"))`, so the whole map flows into the `=>`
line.

The transact! return contract is in `src/seon/db.cljs` ~285–319 (success
envelope `{:seon.db/ok? true :seon.db/tx-report <raw datahike report>}`)
— useful to read so the summary matches the real shape, but DO NOT edit
db.cljs; the raw report is an intentional escape hatch for callers who
want it.

## FIX SHAPE

Summarize the transact! result value at the transcript render boundary,
before it becomes the `=>` body — same spirit as how large values are
clamped, just keyed on the recognizable transact-envelope shape:

- Detect the transact success envelope: a map with `:seon.db/ok?` and
  `:seon.db/tx-report`.
- Render it as the meaningful value only — `{:seon.db/ok? true}` — or a
  one-line summary (e.g. `{:seon.db/ok? true, :seon.db/datoms <n>}` using
  the count of the report's `:tx-data`) if you want the agent to see how
  many datoms landed. Drop `:db-before` / `:db-after` / `:db/commitId` /
  request-id and the raw datom vector entirely.
- A failure envelope (`{:seon.db/ok? false :seon.db/error …}`) already
  renders small and is the information the agent needs — leave it.

Keep it a pure render-time transform (no stored attr, no change to what
the eval log persists), consistent with reactive-context: the transcript
is a derived view. Apply it where `res` is turned into the value body so
it composes with the existing cap/handle logic.

## BOUNDARY / NON-GOALS

- This is wholly inside the transcript render lane
  (`format-eval-row`) — your code. No change to `transact!`, the eval
  log, or message storage.
- Do not raise/lower the caps; the cap is not the issue.
