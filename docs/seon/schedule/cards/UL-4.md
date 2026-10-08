---
title: "UL-4: the EDN symbol test uses the EDN reader"
type: reference
status: ready
created: 2026-10-08
---

# UL-4: The EDN symbol test uses the EDN reader

Ledger row UL-4 (group E). AU-Q9 answered (owner 2026-10-08): the EDN reader, with a **named catch exception**.
Source: `reinvention/reinvention-opus.md` item 3. Model: Sonnet.

`seon.program/edn-symbol-text?` (private, `src/seon/program.cljc:85-128`, 44 lines) re-implements EdnReader's symbol
pattern by hand. `clojure.edn/read-string` agrees on all 14,724 interned Var names and every documented edge case
(`a//`, `a/b/`, `/x`, `a/1`, `+1`, `nil`, `a:`, `a::b`, `#a`), 0.32 µs against 0.21 µs per name (opus probe).

## Files

- `src/seon/program.cljc`
- `test/seon/program_test.clj`

## Function changed

`edn-symbol-text?` `[s]`, contract unchanged `[:=> [:cat :string] :boolean]`, body:

```clojure
#?(:clj
   (try
     (let [v (edn/read-string s)] (and (symbol? v) (= s (str v))))
     ;; The one named exception to the catch law (owner, AU-Q9): the EDN reader answers non-symbol text by
     ;; throwing, and this total predicate's answer for it is false.
     (catch RuntimeException _ false))
   :cljs (let [v (cljs.reader/read-string s)] (and (symbol? v) (= s (str v)))))
```

(`clojure.edn` is already required under `#?(:clj …)`, `program.cljc:12`; check the cljs reader require exists or
leave the `:cljs` branch calling the same shape through `cljs.reader`, adding its require.) Delete the hand-written
body and its docstring's grammar description; keep "## Edge cases".

## Proof

New `seon.program-test/edn-symbol-text-agrees-with-the-edn-reader` (a table): `"a"` `"a/b"` `"a//"` true;
`"a/b/"` `"/x"` `"a/1"` `"+1"` `"nil"` `"a:"` `"a::b"` `"#a"` `""` `"a b"` false; and every interned Var name in
`seon.program` and `clojure.core` answers true. Existing `edn-round-trip-symbol?` tests stay green.

`--test seon.program-test`

## Cost

Expected: 0.1 µs more per edge name (measured), negligible.

## Commit

`bin/lane commit <lane> -m "UL-4: the EDN symbol test uses the EDN reader (named catch exception)" --test seon.program-test src/seon/program.cljc test/seon/program_test.clj`

## Dependencies

None at this commit (`program.cljc` is unclaimed; led-nk1 holds `my/program.clj`, not this file). PB-07b1 and DIAL-8
edit the same file: claim in turn.
