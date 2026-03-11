---
type: prd
status: draft
tags: [prd]
---
# Pure Clojure Data Transport — Bypass Datastar's Signal Encoding

## Status

- [x] Phase 0: Research (this document)
- [x] Phase 1: FormData + keyword names (zero JS) — implemented, `seon.web.reactive.encoding` deleted
- [ ] Phase 2: Scittle + transit client (rich data)
- [ ] Phase 3: Agent browser execution

## The Insight

Every encoding scheme (camelCase, tilde, dot-notation) is a hack. They exist because
we tried to use Datastar's signal system as the data transport. But our architecture
is simple: **agents write Clojure functions, the UI calls those functions with args,
results render as HTML.** This is a remote function call, not a reactive SPA.

**Split the concerns:**

- **Datastar handles:** SSE connection, fragment morphing, DOM reactivity
- **Plain HTML + optional CLJS handles:** Form data collection, POST to server

## Revised Architecture

### Data Flow (Phase 1 — No JS Required)

```
User fills form
    |
    v
<form> with name=":seon.health/exercise" inputs
    |
    v
data-on-submit="@post('/ns/seon.health.workout/add-workout!', {contentType:'form'})"
    |
    v
Datastar's @post collects FormData from closest <form>
    |  (native browser behavior, no signal encoding)
    v
POST /ns/seon.health.workout/add-workout!
Content-Type: application/x-www-form-urlencoded
Body: %3Aseon.health%2Fexercise=Pull-up&%3Aseon.health%2Fsets=3
    |
    v
Server middleware: parse form params, keyword-ify names starting with ":"
    |
    v
{:seon.health/exercise "Pull-up" :seon.health/sets "3"}
    |
    v
Malli coerce (string "3" -> int 3 via schema)
    |
    v
Call (add-workout! {:seon.health/exercise "Pull-up" :seon.health/sets 3})
    |
    v
SSE pushes re-rendered HTML fragment (Datastar morphs it in)
```

### Data Flow (Phase 2 — Scittle + Transit)

```
User fills form
    |
    v
Scittle collects form fields, builds Clojure map with qualified keywords
    |
    v
Transit-encode: {:seon.health/exercise "Pull-up" :seon.health/sets 3}
    |  (keywords, sets, dates all preserved perfectly)
    v
@post('/ns/seon.health.workout/add-workout!', {
  contentType: 'json',
  headers: {'Content-Type': 'application/transit+json'},
  payload: transitEncodedString
})
    |
    v
Server: transit-decode -> exact Clojure map -> Malli validate -> call fn
```

## Key Research Findings

### 1. Datastar's @post Supports `contentType: 'form'`

**Source: `reference-code/datastar/library/src/plugins/actions/fetch.ts` lines 131-183**

When `contentType: 'form'`, Datastar:

1. Finds the closest `<form>` element (or uses `selector` option)
2. Calls `new FormData(formEl)` -- standard browser API
3. Validates the form (`checkValidity()`)
4. Sends as `application/x-www-form-urlencoded` (or `multipart/form-data`)

**This completely bypasses the signal store.** No signal encoding, no JSON, no
camelCase. The `name` attributes on form inputs ARE the keys. We control those names.

### 2. Datastar's @post Supports `payload` Option

**Source: fetch.ts line 122-123**

```typescript
payload = payload !== undefined ? payload : filtered({ include, exclude })
```

If you pass `payload`, it skips signal collection entirely and uses your object as
the POST body. Combined with Scittle (Phase 2), we can build an arbitrary JS object
(transit-encoded) and pass it directly.

### 3. data-on-click Can Call Arbitrary JS

Datastar expressions are evaluated as sandboxed JavaScript. You can call any global
function:

```html
<button data-on-click="myGlobalFunction(el, $someSignal)">
```

Built-in variables: `el` (current element), `evt` (browser event), `$signalName`
(signal access). This means Scittle functions exposed on `window` can be called
from Datastar attributes.

### 4. Custom Action Plugins via `action()`

```javascript
import { action } from 'datastar'
action({
  name: 'seonPost',
  apply: async (ctx, url) => {
    // collect form data, transit-encode, POST
  }
})
// Usage: data-on-click="@seonPost('/ns/seon.health.workout/add-workout!')"
```

This lets us register `@seonPost` as a native Datastar action that uses our
transit encoding instead of signal serialization.

### 5. SSE Push Is Already Clean

Server pushes HTML fragments via `datastar-patch-elements`. Datastar morphs them
into the DOM. **No signal encoding involved.** This path is already perfect and
needs zero changes.

### 6. Scittle + Transit Works in Browser

Confirmed: Scittle (SCI interpreter) provides `cognitect.transit` namespace via
`scittle.transit.js` plugin. Transit round-trip preserves keywords, sets, dates,
UUIDs perfectly.

```clojure
;; In browser via Scittle:
(ns seon.browser (:require [cognitect.transit :as t]))
(def w (t/writer :json))
(t/write w {:seon.health/exercise "Pull-up" :tags #{:strength}})
;; => transit JSON string with keywords + sets preserved
```

No build step. Include via `<script>` tags. ~400KB (acceptable for personal app).

## What Stays in Datastar vs What Moves

| Concern | Datastar | HTML/CLJS |
|---------|----------|-----------|
| SSE connection | Yes | - |
| HTML fragment morphing | Yes | - |
| `data-show`, `data-text` | Yes (simple reactive display) | - |
| `data-on-click` event handling | Yes (dispatches to our code) | - |
| Form field two-way binding | Maybe (see below) | - |
| Form data collection for POST | **No** | FormData (Phase 1) or Scittle (Phase 2) |
| Signal encoding/decoding | **Eliminated** | Not needed |
| Data type preservation | N/A | Transit (Phase 2) |

### What About `data-bind`?

Three options, in order of simplicity:

**Option A (Phase 1): Skip data-bind entirely.** Use plain HTML `name` attributes.
No two-way binding needed for form submission. Forms work like normal HTML forms.
The server re-renders and pushes updated HTML via SSE after mutation.

**Option B: data-bind for UX, FormData for submission.** Keep `data-bind` for
reactive display (e.g., showing character count as you type). But when submitting,
collect via FormData from `name` attributes, ignoring signals entirely.

**Option C (Phase 2): Scittle manages form state.** A Clojure atom in the browser
holds form state. Scittle event handlers update it on input. On submit, transit-encode
the atom value and POST it.

**Recommendation: Start with Option A.** It requires zero client-side code. If we
need reactive field behavior later, add Option B or C incrementally.

## The Form Field -> Keyword Mapping

This is the key design question. How does the renderer tell the system which field
maps to which keyword?

### Phase 1: Keywords as HTML `name` Attributes

The renderer outputs the qualified keyword as the input's `name`:

```clojure
;; In hiccup (what the renderer produces):
[:input {:type "text"
         :name ":seon.health/exercise"
         :value current-value}]
```

Server-side middleware:

```clojure
(defn keywordize-form-params [params]
  (into {}
    (map (fn [[k v]]
           (if (str/starts-with? k ":")
             [(keyword (subs k 1)) v]  ; ":seon.health/exercise" -> :seon.health/exercise
             [(keyword k) v]))
         params)))
```

The `:field` hiccup marker in the render pipeline would produce:

```clojure
;; Input:
[:field :seon.health/exercise {:type "text"}]

;; Output:
[:input {:type "text"
         :name ":seon.health/exercise"
         :placeholder "exercise"
         :id "seon-health--exercise"}]
```

The `:on:click` hiccup marker would produce:

```clojure
;; Input:
[:on:click :seon.health.workout/add-workout!]

;; Output (Phase 1 - form submit):
{:data-on-click "@post('/ns/seon.health.workout/add-workout!', {contentType:'form'})"}
```

### Phase 2: Scittle Collects and Transit-Encodes

With Scittle, the `:on:click` marker produces:

```clojure
{:data-on-click "@seonPost('/ns/seon.health.workout/add-workout!')"}
```

The `@seonPost` action (registered via Datastar's `action()` API):

1. Finds the closest `<form>`
2. Reads all `name` attributes and values
3. Builds a Clojure map: `{:seon.health/exercise "Pull-up" :seon.health/sets "3"}`
4. Optionally coerces types using schema info embedded as `data-seon-schema` attribute
5. Transit-encodes the map
6. Calls `@post` with `payload` set to the transit string and `Content-Type: application/transit+json`

### Why Keywords in `name` Attributes Work

- HTML `name` attribute accepts any string (URL-encoded in form submission)
- `:seon.health/exercise` URL-encodes to `%3Aseon.health%2Fexercise`
- Server decodes URL params, gets back `:seon.health/exercise` as a string
- Middleware converts strings starting with `:` to keywords
- No ambiguity, no encoding hacks, no information loss

## Renderer Changes

### Current `:field` Marker (Tilde Encoding)

```clojure
[:field :seon.health/exercise {:type "text"}]
;; Produces: data-bind="seon~health/exercise" data-signals with tilde keys
```

### New `:field` Marker (Phase 1)

```clojure
[:field :seon.health/exercise {:type "text"}]
;; Produces: <input name=":seon.health/exercise" type="text" />
;; No data-bind, no data-signals, no encoding
```

### Current `:on:click` Marker

```clojure
[:on:click :seon.health.workout/add-workout!]
;; Produces: data-on-click="@post('/ns/seon.health.workout/add-workout!')"
;; Sends JSON-encoded signals in POST body
```

### New `:on:click` Marker (Phase 1)

```clojure
[:on:click :seon.health.workout/add-workout!]
;; Produces: data-on-click="@post('/ns/seon.health.workout/add-workout!', {contentType:'form'})"
;; Sends FormData from closest <form>, using name= attributes as keys
```

### SSE Push (Unchanged)

```clojure
;; Server sends HTML fragment:
event: datastar-patch-elements
data: elements <div id="workout-table">...updated HTML...</div>

;; Datastar morphs it into the DOM. No signal encoding anywhere.
```

## Implementation Phases

### Phase 1: FormData + Keyword Names (Zero JS, Minimum Viable) — DONE

**What was built:**

- Form fields use qualified keywords as `name` attrs (e.g. `name=":seon.health/exercise"`)
- POST: `@post(url, {contentType:'form'})` sends FormData, server parses keyword names
- GET: `GET /ns/:namespace/:function?key=value` returns EDN
- Shared `resolve-and-call` for both GET and POST with Malli coercion + validation
- `seon.web.reactive.encoding` namespace deleted (tilde encoding removed)
- No `data-signals` initialization for form fields

**What we keep from Datastar:** SSE, `@post` (with `contentType:'form'`),
`data-on-click`, `data-show`/`data-text` for display-only reactivity,
`datastar-patch-elements` for HTML morphing.

### Phase 2: Scittle + Transit Client (Rich Data)

**Browser-side:**

- Include `scittle.js` + `scittle.transit.js` in base page
- Create `seon.browser` namespace with `call-fn` helper
- Register `@seonPost` custom Datastar action via `action()` API
- `@seonPost` collects FormData, builds keyword map, transit-encodes, POSTs

**Server-side:**

- Add `com.cognitect/transit-clj` dependency
- Ring middleware: detect `application/transit+json`, decode with transit-clj
- Response encoding: support `Accept: application/transit+json`

**Benefits over Phase 1:**

- Preserves types (sets, dates, UUIDs) without string coercion
- Enables rich data in both directions (not just form fields)
- Unlocks Phase 3 (browser eval)

### Phase 3: Agent Browser Execution

- Scittle's SCI engine evaluates Clojure forms sent from server
- Server sends forms via `datastar-execute-script` SSE event
- Results transit-encoded and POSTed back
- Enables: DOM inspection, live debugging, UI test assertions from agents

## Verified Datastar Source Findings

All findings from `reference-code/datastar/library/src/`:

### @post with contentType: 'form' (fetch.ts:131-183)

When `contentType: 'form'`:

1. `el.closest('form')` finds the form element
2. `new FormData(formEl)` collects all named fields
3. `formEl.checkValidity()` validates HTML5 constraints
4. Sends as `application/x-www-form-urlencoded` by default
5. **Completely bypasses signal store** -- no signal encoding at all

### @post with payload option (fetch.ts:120-130)

```typescript
payload = payload !== undefined ? payload : filtered({ include, exclude })
const body = JSON.stringify(payload)
```

If `payload` is provided, `filtered()` (signal collection) is never called.
The payload is JSON.stringify'd and sent as the body. For Phase 2, our Scittle
code can build the payload object and pass it directly.

### action() plugin registration (engine.ts:77-79)

```typescript
export const action = <T>(plugin: ActionPlugin<T>): void => {
  actionPlugins.set(plugin.name, plugin)
}
```

Simple registration. The `apply` function receives `(ctx, ...args)` where
`ctx` has `el`, `evt`, `signals`, `error`, `cleanups`. Custom actions are
called with `@actionName(args)` syntax in expressions.

### Expression evaluation (engine.ts genRx)

Expressions in `data-on-*` attributes are evaluated as JavaScript via
`Function()`. `$signalName` is rewritten to signal store access. But any
global JS function is callable: `data-on-click="myFunction(el)"` works.

## Edge Cases

### URL encoding of keyword names

`:seon.health/exercise` as a form field name URL-encodes to
`%3Aseon.health%2Fexercise=value`. Standard URL decoding recovers the
original string. Ring's `wrap-params` middleware handles this automatically.

### Malli string coercion

HTML forms always send strings. Server must coerce:

- `"3"` -> `3` (if schema says `:int`)
- `"true"` -> `true` (if schema says `:boolean`)
- `"2024-01-15"` -> `#inst "2024-01-15"` (if schema says `:time/local-date`)

Malli has built-in coercion transformers (`mt/string-transformer`) for this.
Phase 2 (transit) eliminates this need since types are preserved on the wire.

### Multiple values (checkboxes, multi-select)

FormData supports multiple values for the same name. Server middleware needs
to handle this -- if schema says `[:vector :keyword]`, collect all values
for that name into a vector.

### File uploads

FormData supports file uploads natively. If a form has `enctype="multipart/form-data"`,
Datastar sends as multipart. This works out of the box with the `contentType: 'form'` path.

### No data-bind means no reactive preview

Phase 1 sacrifices reactive form preview (showing computed values as you type).
This is acceptable for most forms. If needed, add `data-bind` for specific
display-only elements while still using FormData for submission.

## Dependencies

### Phase 1 (None -- Uses Existing Stack)

- Ring `wrap-params` (already present)
- Malli string transformer (already in deps)

### Phase 2

```clojure
;; deps.edn
com.cognitect/transit-clj {:mvn/version "1.0.333"}
```

Browser (download to `resources/public/js/`):

```
scittle.js          (~400KB)
scittle.transit.js  (transit plugin)
```

## Trade-offs

### What We Gain

- **Zero encoding hacks** -- qualified keywords travel as `name` attributes (Phase 1) or transit (Phase 2)
- **Delete ~180 lines** of encoding/decoding code
- **Standard HTML forms** -- works without JS, progressive enhancement
- **Browser FormData API** -- battle-tested, handles edge cases (files, multi-value, validation)
- **No signal store pollution** -- form data never enters Datastar's signal tree
- **Malli coercion reuse** -- same coercion used for API endpoints and form submissions
- **Path to rich data** -- Phase 2 adds transit without changing the architecture

### What We Lose

- **Reactive form previews** (Phase 1) -- no data-bind means no live preview as you type
- **Signal-driven UI** -- can't use `$fieldName` in Datastar expressions for form values
- **Two content types** -- server must handle both `form-urlencoded` (Phase 1) and `transit+json` (Phase 2)

### What We Keep

- Datastar for SSE, HTML morphing, event handling, display reactivity
- Server-rendered HTML as primary UI pattern
- Current URL structure (`/ns/:ns`, `/ns/:ns/:fn`)
- All existing SSE push behavior

## Migration Path

Phase 1 is a **swap**: replace signal encoding with FormData. The `:field` and
`:on:click` hiccup markers change their output, but the render pipeline structure
stays the same. Forms that currently use `data-bind` + `data-signals` switch to
`name` attributes + `contentType:'form'`.

Phase 2 is **additive**: Scittle + transit adds capability without changing Phase 1.
Forms can opt into transit encoding by using `@seonPost` instead of `@post`.

Phase 3 is **new capability**: agent browser eval is purely additive.

## Previous Research (Preserved)

### Datastar Signal Store Architecture

Signal store is a deep reactive Proxy tree. Dots in keys create nesting. JSON value
form (`data-signals='...'`) preserves keys as-is (no camelCase). Attribute form
(`data-signals-foo`) applies camelCase. POST body is `JSON.stringify(nested signals)`.

Full details in git history of this file (commit before this rewrite).

### Transit Libraries

| Library | Version | Platform | Size |
|---------|---------|----------|------|
| `com.cognitect/transit-clj` | 1.0.333 | JVM | N/A |
| `transit-js` | 0.8.874 | npm | ~10KB gzipped |
| `com.cognitect/transit-cljs` | 0.8.280 | CLJS (wraps transit-js) | N/A |

All implement Transit 0.8 spec. Wire-compatible. Considered finished software.

### Scittle

SCI-based ClojureScript interpreter. ~400KB. Zero build step. Has
`scittle.transit.js` plugin providing `cognitect.transit` namespace.
Supports browser eval of Clojure forms sent from server.
