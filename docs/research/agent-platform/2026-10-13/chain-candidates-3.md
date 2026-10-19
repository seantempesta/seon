# Chain subject candidates, round 3

Scope: small pure functions in src/seon/** (print, id, render.hiccup, render.block), excluding the
owner-listed exclusions. Probes ran in JVM mode on the host JVM (`eval_clj`, mode jvm). No edits.

Honest result: this round found ONE candidate with a demonstrated gap inside the reach limit, and it is
weak. The other rows are recorded as checked and rejected, so they are not chain subjects. Five rows
were requested; five are listed, ranked, with the status of each.

## Reach measurement caveat

`my.program/tests-reaching` in JVM mode answers the same shape for every subject here: the
`:seon.program/gating` vector is empty and `:seon.program/unknown` says `:outside-graph`. So its
count cannot be used as reach. Reach below is a static count: deftest files and references under
`test/` (grep `-rl` and `-rn`). Treat it as an upper bound on reach, not an exact count.

## Ranked rows

| Rank | Symbol | Docstring line (promise) | Wrong answer (JVM form, actual) | Expected | Reach (static) | Contract | Status |
|---|---|---|---|---|---|---|---|
| 1 | `seon.print/print-number?` | "True for a number with a stock Clojure printed form." | `(seon.print/print-number? (java.util.concurrent.atomic.AtomicLong. 5))` returns `true`. Its `pr-str` is `"5"`, which reads back as a Long, so the printed form does not round-trip to the same value type. | `false` for a Number whose printed form is not its value (AtomicLong, LongAdder), or the docstring narrows to "a number the stock reader reads back as itself". | 1 test file, 1 reference | yes (`:malli/schema`, `:=> [:cat any] :boolean`, exemption `:polymorphic-boundary`) | Gap demonstrated, weak: the printed text is readable, so the wrong answer is a type change, not a broken literal. Body is `(number? value)`. |
| 2 | `seon.id/id` | "Equal values have equal ids whatever their map or set order; a vector and a list differ." | `(= [1 2] '(1 2))` is `true`, but `(= (seon.id/id [1 2]) (seon.id/id '(1 2)))` is `false`. | "Equal values have equal ids" is false for `=`. The docstring carves out vector vs list, so the body matches its own text; the contradiction is in the docstring's first clause. | 21 files, 50 references | yes (`:malli/schema`, function arities) | Fails the reach limit (<=3). Rejected as a chain subject. Recorded as a docstring contradiction only. |
| 3 | `seon.print/path-step` | "The member one path step names in `parent`, or `missing`." | Probed: `(path-step #{:a :b} [:seon.render.data/nth 0] :missing)` gives `:b`; `(path-step [1 2 3] -1 :missing)` gives `:missing`; `(path-step {:a nil} :a :missing)` gives `nil` (absent-vs-nil is correct). No wrong answer found. | n/a | 1 file | yes | No gap found. Rejected. |
| 4 | `seon.print/string-literal` | "`s` as a readable string literal whose newlines stay line breaks." | Round-trip `(read-string (string-literal s))` equals `s` for nine tricky strings (tab, bell, CRLF, backslash, quote, Unicode, NUL, form feed, backspace). The newline stays a raw line break as documented. No wrong answer found. | n/a | 2 files, 3 references | yes | No gap found. Rejected. |
| 5 | `seon.render.hiccup/class-value` (private) | "Shorthand classes first, then the attribute's, blanks dropped. Returns nil when nothing survives." | Probed: `(["a"] :foo/bar)` gives `"a bar"` (namespace dropped by `attribute-name`, by design); `([] ["x" nil :y/z])` gives `"x z"`; `([] "   ")` gives `nil`; `(["" "k"] nil)` gives `"k"`. Every case matches the docstring. | n/a | 1 file, 3 references | none checked | No gap found. Rejected. Private, so it would need the `#'` accessor. |

## Also checked and rejected

- `seon.render.hiccup/escape`: escapes `& < > " '` as the docstring says; reach 33 files. Out.
- `seon.id/valid?`: no wrong answer found; reach 29 files. Out.
- `seon.id/symbol-in`: the one-letter lead is enforced only by its schema under instrumentation; a bare call with `\1` returns an unreadable symbol. Reach 4 files. Out (gap exists only through instrumentation, which is a contract matter, not a body gap).
- `seon.print/print-char?`: `(char? value)` is vacuously true, because every char has a stock printed form. No gap. Reach 1 file.
- `seon.render.hiccup/style-value` (private): `{:fontSize "12px" "--Ab" "1"}` gives `"--Ab: 1; font-size: 12px"`, matching the sorted, hyphenated output described for the style map. No gap.

## Next step for the owner

Only `seon.print/print-number?` has a demonstrated gap within reach, and its gap is a type round-trip
question, not a broken literal. Before filing it as a chain subject, decide whether the owner wants the
docstring narrowed (cheap) or the body to check the round-trip (a real design choice). A stronger
subject needs a different pool: the candidate namespaces here are already well covered by tests.
