---
type: issue
status: resolved
severity: blocker
tags: [issue, reader, prompt, grammar, provider, live-test, design]
created: 2026-09-15
---

# The model writes `#:seon.repl{…}` responses every other turn (30 of 103 evaluations in run 4)

## Observed

Run 2: 9 fabricated responses. Run 4 (help now says "Do not write
responses yourself"): 30 error evaluations "You wrote a response. Only the
REPL writes responses; send forms and wait." The model itself: "I keep
slipping into writing responses", "I slipped again", "the reader is in a
stuck state". Half its turns produced nothing.

## Why (the grammar teaches it)

Every emission in the context is `readline · form · response` with no
separator between the form and the response; the prompt ends at a bare
readline. Completing "form, then response" is the strongest pattern in
the text. Prose rules cannot beat 60 examples.

## Wanted (general, not a hack)

The REPL owns the response token. Two mechanisms, both general:
1. Provider stop sequence: the attempt sends `stop: ["#:seon.repl"]` (a
   `:seon.config.ai/*` dial, overlaid per agent), so a reply is cut at the
   first fabricated response and the forms before it evaluate normally.
   The reader keeps its §18b guard for providers without stop support.
   This is how a real REPL behaves: input ends where the prompt's response
   begins.
2. Make the boundary visible in the grammar without a second grammar: the
   response line begins with the REPL's marker that the model has never
   been asked to write (already `#:seon.repl`), and the emission after a
   provider turn's forms is a system emission — with the change-only
   re-read marker landing, the model should see its own turn end at the
   form.
Regression: the trial harness must report zero fabricated responses on the
run-4 replies replayed through the reader with stop applied; the live
number is measured on run 5.

## Run4-blockers implementation — 2026-09-15

The existing stop schema already declares a vector of strings, per-agent
overlay and provider wire metadata. The existing target builder sends it
as `stop`, and the attempt records the selected effective settings. The
missing piece was the shipped default, which was explicitly absent; it is
now `["#:seon.repl"]`.

The canonical armed regression uses real agent/turn creation and
`record-attempt!` to verify both the default and a per-agent override in
the provider body and persisted attempt settings. Seven exact captured
replies replay through the unchanged reader and real SCI evaluation:
13 forms, zero fabricated-response errors after provider stop is applied.
This is deterministic replay evidence, not a paid run-5 measurement.

Final canonical fast gate: 106 tests / 699 assertions. Final isolated gate:
106 tests / 704 assertions. Both include the reader, reply, AI, REPL grammar
and help-trial namespaces, with zero failures and errors.
