---
title: "UL-7: Form and query strings decode with ring-codec"
type: reference
status: ready
created: 2026-10-08
---

# UL-7: Form and query strings decode with ring-codec

Ledger row UL-7 (group D). Source: `reinvention/reinvention-opus.md` item 6. Model: Sonnet. Answered by AGENTS.md ("use
the library"); no owner decision.

`seon.render.web/form-pairs` (`src/seon/render/web.clj:157-166`) hand-splits `k=v&k=v` with `URLDecoder`.
`ring.util.codec/form-decode-map` (ring-codec 1.3.0, `ring/util/codec.clj:169`, on the base classpath through
`metosin/reitit-ring`, `deps.edn:118`) does the same per the WHATWG urlencoded rules and always returns a map. One
difference: a repeated key becomes a vector of its values (`assoc-conj`), where ours kept the last.

## Files

- `src/seon/render/web.clj`
- `test/seon/render/web_test.clj`

## Functions

- Delete `seon.render.web/form-pairs` (`web.clj:157-166`).
- Change `query-params` (`web.clj:168-170`): `(codec/form-decode-map (or (:query-string request) ""))`.
- Change `decode-form` (`web.clj:2937-2945`): last line `(codec/form-decode-map encoded)`.
- `ns`: add `[ring.util.codec :as codec]`; drop `URLDecoder` from the `java.net` import (`:89`) if no other use
  remains (check with `rg URLDecoder src/seon/render/web.clj`).
- Every reader of a decoded param that needs one string: read the callers of `query-params` and `decode-form` (`rg -n
  "query-params|decode-form" src/seon/render/web.clj`); where a caller passes the value on as a string, take
  `(let [v (get params k)] (if (vector? v) (peek v) v))` only if a repeated key can reach it; otherwise leave it.
  Report the callers you checked in the commit message.

## Proof

New `seon.render.web-test/form-and-query-strings-decode-as-ring-does` (a table, data only):

```clojure
(deftest form-and-query-strings-decode-as-ring-does
  (are [encoded expected] (= expected (#'web/query-params {:query-string encoded}))
    "a=1&b=%20x"     {"a" "1" "b" " x"}
    "k=v%3D%3D&&z="  {"k" "v==" "z" ""}
    "x=a+b"          {"x" "a b"}
    "flag"           {"flag" ""}
    ""               {}
    "a=1&a=2"        {"a" ["1" "2"]}))
```

and the existing form-post tests in `seon.render.web-test` stay green.

`--test seon.render.web-test`

## Cost

Expected: linear in the string, as before; −10 lines.

## Commit

`bin/lane commit <lane> -m "UL-7: form and query strings decode with ring.util.codec/form-decode-map" --test seon.render.web-test src/seon/render/web.clj test/seon/render/web_test.clj`

## Dependencies

None; no lane holds these files at `3f69939e4`. ring-codec arrives transitively; an explicit `deps.edn` coordinate waits
on task `seon.cluster.source/a-launch-option-change-in-deps-edn-refuses-every-check` and is not needed for this card.
