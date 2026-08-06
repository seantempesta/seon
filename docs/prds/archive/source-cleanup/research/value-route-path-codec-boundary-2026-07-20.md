---
type: research
status: complete
tags: [research, web, rendering, architecture]
---

# Value-route path codec boundary (2026-07-20)

## Decision

The Stage 1.5 value route carries one URL-query `path` value whose decoded
text is the canonical `pr-str` of an EDN vector. The initial closed segment
grammar is exactly:

- `nil`;
- `true` or `false`;
- a finite ClojureScript number other than negative zero, provided the value
  survives the canonical EDN and existing Transit round trips exactly;
- a string;
- a keyword; or
- a symbol.

There are no tagged elements in the initial grammar. UUIDs, instants, queues,
characters, ratios, big integers, JavaScript literals, collections, maps,
sets, records, host objects, and renderer marker maps are rejected. A future
tagged scalar is an additive grammar decision only after the route supplies
an explicit local reader and an HTTP-plus-Transit identity test. It must not
use `cljs.reader/register-tag-parser!`, a token registry, or an alternate
codec.

The containing value determines how a number is interpreted during descent.
For a map, any admitted numeric segment is the exact map key. For a vector,
the segment must additionally be a non-negative safe integer within its
count. Sequential and set displays are pageable views and never acquire
positional child paths. This type-directed rule preserves negative and
fractional numeric map keys without pretending they are vector indexes.

The path text must equal `(pr-str decoded-path)` byte-for-character after URL
decoding. This rejects alternate numeric spellings, whitespace, commas,
comments, metadata, discards, and other non-canonical EDN even when the reader
would produce the same value. Percent-escape spelling is transport syntax,
not a second canonical data representation: the server accepts any WHATWG
query encoding that decodes to the canonical EDN text, while the maintained
UI constructs requests with `URLSearchParams`. The configured encoded-byte
cap is measured on the one raw query value before decoding.

## Dependency ledger

| Dependency or mechanism | Selected revision and source | Contract consumed |
|---|---|---|
| Projected-key ruling | [[projected-map-key-drill-boundary-2026-07-20]] (`ddf2b5c2`) | A visible key is drillable only when its retained display value is the original scalar and both codec legs preserve lookup equality. `:seon.render.value/non-drillable-key-indexes` prevents projected keys from ever constructing a request. |
| Value-route ruling | [[value-route-authorization-boundary-2026-07-20]] (`7b6e2243`) | `GET /agent/{id}/value` accepts exactly one eval/entity selector, optional `path`, and optional `offset`; all syntax and budget refusals happen before database lookup or child IPC. |
| ClojureScript EDN reader | `org.clojure/clojurescript` `1.12.145`; vendored ClojureScript `946d75f3483c0c8e784e6668bff2c71a25619a77`; `reference-code/clojurescript/src/main/cljs/cljs/reader.cljs:125-210`, `reference-code/clojurescript/src/main/clojure/cljs/vendor/clojure/tools/reader/edn.clj:380-440`, and `reference-code/clojurescript/src/test/cljs/cljs/reader_test.cljs:19-67,90-106` | `cljs.reader/read-string` reads one form only and merges a mutable global default tag table. The route therefore uses the underlying pushback reader and `cljs.tools.reader.edn/read` directly, with no readers/default, then reads a second time for EOF. |
| ClojureScript printer | same revision; `reference-code/clojurescript/src/main/cljs/cljs/core.cljs:10450-10480` | `pr-str` emits canonical readable strings, keyword/symbol forms, `##NaN`/`##Inf`, and explicitly preserves `-0.0`. Canonical reprinting catches alternate EDN spellings; the scalar predicate still rejects non-finite and negative-zero values. |
| Transit CLJS | `com.cognitect/transit-cljs` `0.8.280`, vendored `3d8a2c49ff1911fd7adfacce2776c3a6b8cc1fce`; `reference-code/transit-cljs/src/cognitect/transit.cljs:212-295` | The existing execution writer already preserves nil, booleans, finite ordinary numbers, strings, keywords, and symbols. The route adds no writer or handler registry. |
| Transit JS | vendored `9f58010235062287d8dc42bdcbe12f4526065f46`; `reference-code/transit-js/src/com/cognitect/transit/impl/writer.js:121-145,476` | JSON marshalling supports special numbers but `JSON.stringify` collapses negative zero to zero. This independently requires rejecting negative zero before IPC. |
| Existing execution codec | `src/seon/execution.cljs:163-198`; `src/seon/db/protocol.cljc:111-174` | `encode-message`/`decode-message` remain the only Transit crossing. `ordinary-wire-value?` is broader than the route grammar and is not path admission. |
| Existing HTTP adapter | `src/seon/web/router.cljs:71-91`; `src/seon/web/serve.cljs:190-218`; `test/seon/web/router_test.cljs:54-73` | The adapter already retains both the WHATWG Request and raw Ring `:query-string`. The handler parses the WHATWG URL once and does not add another general query parser. |
| Drill budgets | [[value-drill-budget-config-boundary-2026-07-20]] (`3d5943db`) | Raw encoded bytes, decoded segment count, and checked offset work are independent configured bounds. Syntax rejection precedes every lookup, descent, realization, or child send. |

## Exact parser seam

The parser belongs in the existing value-route owner,
`src/seon/web/serve.cljs`, beside the one value handler. It is one pure private
function over the WHATWG Request plus resolved effective limits. It returns
either the closed namespaced drill request or the existing closed
`:seon/error` user-input value; it performs no database read, authorization,
child send, descent, or sampling.

The parser uses one `js/URL` and iterates `URLSearchParams.entries`, rather
than calling `.get`. Iteration preserves repeated fields, including aliases
such as `path` and `%70ath` that decode to the same name. It rejects unknown
names and requires each recognized name at most once; exactly one of `eval`
and `entity` is then required. `path` defaults to the canonical text `[]` and
`offset` defaults to `0`.

Before decoding the path for EDN, the parser identifies the single raw query
component from the request URL and measures its serialized byte length
against `:seon.config.render/value-max-path-bytes`. The raw scan is only query
framing: split on `&`, split each component on its first `=`, require every
percent sign to begin two hexadecimal digits, and correlate the component with
the decoded `URLSearchParams` entry. It never decodes the value with a
home-grown codec; the WHATWG `URLSearchParams` value is the authoritative
decoded string. Malformed percent encoding and ambiguous raw framing are
`400` refusals.

For EDN, use a `cljs.tools.reader.reader-types/string-push-back-reader` and
call `cljs.tools.reader.edn/read` with a private EOF sentinel, `:readers {}`,
and no `:default`. Read once for the candidate and once again. The second read
must return the identical EOF sentinel. This is necessary because
`cljs.reader/read-string` deliberately returns only the first form, so a
check such as `(reader/read-string "[] :trailing")` is not a complete-input
parser. Direct `cljs.tools.reader.edn/read` also avoids invoking the mutable
global tag table that `cljs.reader/read` merges into every options map.

After reading, admission is conjunctive:

1. the result is a vector;
2. the second read reached EOF;
3. the decoded string equals `(pr-str result)` exactly;
4. every element satisfies the closed scalar predicate;
5. the segment count is within the resolved maximum; and
6. every admitted element passes the codec identity law below.

The focused pure parser tests belong in `test/seon/web/serve_test.cljs`, where
the request boundary and handler spies already live. `test/seon/route_test.cljs`
adds only the seeded `GET /agent/{id}/value` route contract, and
`test/seon/web/router_test.cljs` adds only dispatch/middleware integration.
Neither route namespace owns EDN parsing. No new `seon.web.value`, query
library, token table, or renderer-local codec is warranted.

## Codec identity law

For each admitted segment `x`, the following must all be true:

```text
canonical = pr-str(x)
x-http    = strict-edn-read(canonical)
x-wire    = Transit-decode(Transit-encode(x-http))

canonical = pr-str(x-http)
x = x-http
x-http = x-wire
```

Equality here is ClojureScript lookup equality, with one stronger numeric
check: neither `x-http` nor `x-wire` may satisfy
`(js/Object.is value -0)`. The parser need not Transit-round-trip every
request. The closed type predicate plus the actual-codec table test proves
this invariant once; execution frames then use the existing codec normally.

Canonical reprinting makes aliases unrepresentable. `+1`, `01`, `0x1`,
`1r1`, `1.0`, `1e0`, and an unsafe integer spelling that the JavaScript
reader rounds to a different printed number are rejected. A finite number
whose actual ClojureScript value does print and read identically is admitted,
including negative or fractional values as map keys. Vector descent still
requires a non-negative safe integer.

Non-finite values are always rejected even though printer and Transit have
spellings for them. `NaN` lacks reflexive lookup equality, and infinities do
not justify widening an address grammar. Both `-0` and `-0.0` are rejected:
the former canonicalizes to ordinary zero in the reader, while the latter can
survive EDN but collapses through Transit JSON. Ordinary positive zero is
admitted.

## Duplicate, trailing, and malformed behavior

All of these produce the same bounded `400` user-input class before work:

- a duplicate selector, `path`, or `offset`, even when one name is
  percent-encoded differently;
- both selectors, neither selector, or any unknown query name;
- an absent value for a required selector;
- an outer form other than a vector;
- a second EDN form, trailing comment, comma, or whitespace, because the
  decoded string is not the exact canonical print;
- an unknown or otherwise tagged literal;
- a collection, marker map, character, UUID, instant, or host literal in the
  vector;
- `##NaN`, `##Inf`, `##-Inf`, negative zero, or a non-canonical/rounded number;
- malformed percent encoding, excessive raw encoded bytes, or excessive
  decoded segments; and
- a non-canonical, negative, or unsafe offset, or checked total-work crossing.

The response may distinguish fields in a bounded error message for repair,
but refusal observability is identical: zero database reads, authorization
queries, host invocations, child sends, path descents, and collection touches.

## Shortest falsifiers

1. Table-test canonical successes for `[]`, nil, both booleans, positive and
   negative finite numeric map keys, ordinary zero, Unicode strings,
   namespaced and unqualified keywords, and namespaced and unqualified
   symbols. Assert parse then `pr-str` returns the identical decoded text.
2. For every successful scalar, pass the complete request through the actual
   `execution/encode-message` and `decode-message` functions and assert lookup
   equality. Use each decoded segment against a source map and assert the
   original child is returned.
3. Reject `+1`, `01`, radix integers, ratios, `1.0` when its decoded value
   prints `1`, exponent aliases, rounded unsafe-integer spellings, comments,
   commas, leading/trailing whitespace, metadata, discards, and `[] :tail`.
4. Reject `##NaN`, both infinities, `-0`, and `-0.0`. Assert ordinary `0`
   succeeds and that the negative-zero cases reach neither Transit encoding
   nor lookup.
5. Reject vectors containing a nested vector/list/map/set, character, UUID,
   instant, `#js` value, renderer marker, or unknown tag. Install a hostile
   global `cljs.reader` tag parser and prove the strict parser neither invokes
   it nor admits its result.
6. Send `path=[]&path=[]`, `path=[]&%70ath=[]`, duplicate selectors, both/no
   selectors, and an unknown parameter. Assert one bounded `400` and all
   database/host/realization spies remain zero.
7. Put a large percent-escaped path over the encoded-byte cap whose decoded
   value would be tiny. Assert refusal occurs before EDN read. Put a canonical
   path over the segment cap and assert refusal occurs before selector lookup.
8. Descend with `-2` and `1.5` through a map and prove exact key lookup. Submit
   the same segments against a vector and prove bounded refusal; `0` and the
   last in-range safe integer succeed, while negative, fractional, unsafe, and
   out-of-range indexes do not touch an element.
9. Build the maintained UI URL twice from the same path through
   `URLSearchParams`; assert identical query bytes. Replace percent-escape
   hex case or `%20`/`+` transport spelling while preserving the decoded
   canonical EDN and prove the same path value results without creating a
   second data canonicalization rule.
10. At the handler boundary, run every syntax/budget refusal with spies for
    database acquisition/query, child host send, result lookup, descent, and
    realization. Every counter must remain exactly zero.

## Handoff

Implement this only after the projection drillability and configured budget
contracts freeze. The child receives the already-decoded ordinary path and
repeats the closed scalar/segment/index/work checks; it does not parse HTTP or
trust a route-produced limit. The route handler owns URL/EDN syntax and parent
zero-work refusal. The renderer owns whether a visible key may form a path.
These are three enforcement points over one data contract, not three codecs.
