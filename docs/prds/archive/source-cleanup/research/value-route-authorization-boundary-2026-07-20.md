---
type: research
status: complete
tags: [research, web, architecture, rendering]
---

# Value-route authorization boundary

## Decision

Stage 1.5 adds one database-seeded, read-only core route:
`GET /agent/{id}/value`. It presents the same bounded ordinary-data value
projection for two directly translated producers without inventing a shared
value authority:

- `eval=<eval-id>` selects a live eval value owned by the path agent. The
  parent authorizes from an acquired immutable database value, then asks that
  agent's execution child to read its own `result/<id>` slot and return only
  the eager `seon.render.value/render-html-data` projection.
- `entity=<positive-eid>` selects database data for `/data`. The parent reads
  and samples it from the same acquired immutable database value. A database
  or entity handle never crosses into the child.

Exactly one selector is required. Both, neither, duplicate parameters,
unknown parameters, malformed values, and over-limit requests are `400`. The
entity form uses `/agent/root/value` for `/data`: `root` is the route namespace,
not invented entity ownership. The path agent must exist, but entity reads do
not acquire an agent ownership relation.

The eval query is:

```clojure
[:find ?eval .
 :in $ ?eval-id ?agent-id
 :where
 [?eval :seon.eval/id ?eval-id]
 [?eval :seon.eval/agent ?agent]
 [?agent :seon.agent/id ?agent-id]]

```

No match returns the same `404` for a missing eval and an eval owned by another
agent. This avoids disclosing eval existence and, critically, occurs before
any execution-host invocation.

## Request and response contract

The two request forms are:

```text
/agent/{id}/value?eval=<eval-id>&path=<url-encoded-edn-vector>&offset=<decimal>
/agent/root/value?entity=<positive-decimal-eid>&path=<url-encoded-edn-vector>&offset=<decimal>

```

`path` defaults to `[]`. `offset` defaults to `0` and accepts only a canonical
base-10 non-negative integer. A path element is either a non-negative integer
vector index or an ordinary scalar map key: nil, boolean, number, string,
keyword, symbol, and any explicitly registered EDN/Transit scalar such as a
UUID. Collections, host objects, marker maps, and projected display keys are
not path elements. Sequences and sets are pageable collections, not
index-addressable path branches.

The handler parses the WHATWG request URL, as the existing debug handler does,
rather than adding a second query parser to the Ring adapter. Parsing must
reject duplicate fields, trailing EDN input, excessive encoded bytes, and
numeric overflow before database lookup or child IPC. Page size is not a
request parameter; it is the resolved `value-max-items` configuration.

The successful response is the ordinary `render-html-data` projection of the
selected slice. It is rendered through the existing value panel and Datastar
whole-element morph path, not a new feed or state machine. Responses carry
`Cache-Control: no-store` and no cross-origin resource-sharing header because
the projection may contain sensitive application data.

## Authorization, admission, and capabilities

The route has no `:seon.route/same-origin` middleware. That middleware is the
existing cross-site request forgery defense for state-changing POST requests;
this route is GET and performs no write. The server's default loopback bind and
browser same-origin policy remain the read boundary. The route also has no
loopback-operator middleware: that gate identifies lifecycle/operator doors
and would incorrectly reject ordinary proxied or embedded product UI reads.

The route does not widen `/agent/{id}/call`, register its sampler as
agent-authored code, or depend on `:seon.client/autonomous?`. Both default and
retained branch clients need ordinary product reads. It is not globally
admission-gated: parent-owned entity reads and honest unavailable projections
must remain renderable when execution admission is unavailable. The eval
branch handles child availability at its producer boundary.

## Bounds that must freeze first

The current configuration owns value depth, keys, items, string length, and
shape-sample bounds. It does not own maximum path segments, encoded path bytes,
or total realized items. Reusing depth would arbitrarily prevent repeated
drill-down; reusing page size would make every nonzero page invalid. Therefore
the route cannot claim an exact bounded-work contract until the configuration
owner closes [[projected-map-keys-are-not-drill-paths]] and
[[value-drill-has-no-total-work-bounds]].

The frozen contract needs separate configured maxima for path segments,
encoded request bytes, and total realized items. Numeric defaults remain a
configuration decision; this audit does not invent them. Parent and child must
independently enforce the same resolved values. With page size `n`, the child
may realize only:

```clojure
(take (inc n) (drop offset collection))

```

and only when `offset + n` is within the configured total-work budget. The
extra item is the existing honest tail sentinel. An over-limit request is
rejected before lookup, descent, or realization.

## Projected-key contradiction

`seon.render.value` currently claims every retained map key remains a valid
drill path, while `map-key-projection` replaces long strings, long names,
collection keys, and opaque keys with bounded display markers. A display
replacement is not the original key and cannot address the original child.
Until the projection carries separately proven drillability data, the UI must
omit drill controls for projected keys. It must never serialize the displayed
marker as a path. Ordinary retained scalar keys remain drillable.

## Honest retired-child semantics

`seon.eval/lookup-result` already returns a value when an eval row exists but
its bounded child-local slot was evicted or belonged to a prior process. The
eval route preserves that result: HTTP `200` renders the bounded error
projection and offers recomputation from the authorized eval row's recorded
source. It does not reconstruct, persist, or imply possession of the prior
value. A missing or unauthorized eval remains `404`; a distinct transport or
core availability failure is a bounded `503` error value.

## Dependency and path ownership

Implementation order is:

1. Freeze the child sample request/result union, drillability representation,
   and the missing configuration maxima.
2. The execution/eval owner implements child-local lookup, bounded descent and
   paging, ordinary projection, and host transport.
3. The route owner adds the seeded route, strict parser, authorization join,
   response headers, and parent-owned entity branch against that frozen
   contract.
4. The UI owner connects eval cards and `/data`, deleting the raw `pr-str` and
   every superseded drill path.
5. Integration proves both producers, authorization refusal, and real child
   retirement before Stage 1.5 graduates.

The route owner must not edit the active execution/eval unit before its shape
handoff. Conversely, the child transport must not acquire database authority
or authorize itself from caller-supplied agent data. Route datoms remain the
single product-route authority; `/data` is migrated from the temporary static
supplement rather than copied into another registry.

## Crossing hazards

- Route data currently carries one middleware keyword. Admission must not be
  smuggled into the value handler or encoded as an ad hoc second middleware
  chain.
- A large offset against a lazy sequence is linear work before a bounded page;
  parent-only validation is insufficient because child IPC is also a trust
  boundary.
- Entity sampling must use the immutable database value acquired for the
  request. A second ambient acquisition can observe a different basis
  transaction and make selection and projection disagree.
- Returning a Datahike database/entity value or a live child value violates
  the maintained eager ordinary Transit boundary even if Bun could clone it.
- Projected keys, sequence positions, and set iteration must not masquerade as
  stable `get-in` paths.
- HTTP success does not mean the old child value survived. The projection must
  retain the existing prior-session/eviction meaning and recomputation source.

## Shortest falsifiers

### Pure and focused

- A parser table covers both/neither selectors, duplicates, unknown fields,
  malformed or trailing EDN, unsupported path segments, excessive path bytes
  and segments, negative/noncanonical/overflow offsets, and total-budget
  crossing.
- A projection test proves an ordinary scalar key produces a path while a
  projected long, collection, or opaque key produces no drill request.
- A lazy counter proves page zero and a nonzero page touch no more than
  `offset + n + 1` elements; a rejected request touches zero.

### Server-side

- With an execution-host spy, request agent A's eval through agent B's route;
  assert `404` and zero host invocations. Repeat with a nonexistent eval and
  assert the same outward result.
- With database and host spies, select an entity and prove selection and
  projection use one immutable database value while the host is never called.
- Send GET requests with absent and hostile `Origin` headers. Both follow the
  same authorization result, emit `Cache-Control: no-store`, and emit no
  cross-origin resource-sharing header.
- Create a large live eval, request offsets zero and nonzero, and assert eager,
  bounded, ordinary projections with stable paths.
- Retire the real owning child, repeat the authorized request, and assert the
  prior-session/eviction projection plus recorded-source recomputation
  affordance. A mocked lookup miss is not sufficient.

### Browser

- On `/agent/{id}`, expand a deeper-than-bound eval node and page its elided
  tail; the correct subtree morphs without a new feed, page reload, or console
  error.
- On `/data`, drill an entity attribute through the same presentation and
  observe no execution-child request.
- Confirm projected keys have labels but no drill control, and an unavailable
  retired-child result is visibly an error rather than stale data.
