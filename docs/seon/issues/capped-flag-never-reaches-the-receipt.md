---
type: issue
status: open
tags: [issue, sci, context, database]
---

# `:seon.sci.admit/capped?` is computed and structurally dropped

The evaluation envelope computes `capped?`, but
`:seon.cluster.loop/evaluation` (`resources/seon/schema/loop.edn:30`)
is `:closed true` without that key, so the fact never reaches the
receipt: an agent whose 189-row result silently became 64 rows is told
nothing (verified live 2026-08-01; the caps investigation reproduced
identical 6,483-char output for every row count from 189 to 100,000).

This is half of the owner's caps complaint (the other half is the cap
levels themselves). Resolution rides the caps/blob implementation wave:
with `:seon.cluster.eval/result-size` (true serialized size) on the
receipt, `capped?` becomes DERIVED (result-size > stored projection),
not a stored flag — and the transcript prints the loud CAPPED line from
it. Owning report:
`research/admission-caps-and-blob-fallback-2026-08-01.md`.

Acceptance: a capped result's receipt lets the renderer derive and
print "N of M shown" without any stored boolean; regression covers the
class.
