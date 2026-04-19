---
type: research
status: active
tags: [research, web, prd]
---

# Datastar Event DSL: GET vs POST, Form Handling, and Security

## Status: Research Complete (2026-03-14)

Research into how Seon's transform layer converts agent-friendly hiccup attributes into Datastar directives, and how to evolve the DSL for the unified namespace flow.

---

## Current System Overview

### What Exists Today

Three files form the browser interaction pipeline:

1. **`transform.clj`** -- Pure hiccup tree walker. Converts `:on:click`, `:on:submit:form`, `:field` to Datastar `data-on:*` attributes and `name` attributes. No side effects, no state.

2. **`actions.clj`** -- Function resolver. Given a namespace symbol and function symbol, returns the var if it exists under `seon.*`. Security gate: only namespaces starting with `seon.` are allowed.

3. **`ns/routes.clj`** -- HTTP handlers. `function-call-handler` handles POST, `function-get-handler` handles GET. Both call `resolve-and-call` which resolves the function via `actions.clj`, coerces input via Malli, injects `*ctx*`/`*conn*` dynamic vars, and calls the function.

### Current DSL (transform.clj)

Agents write clean hiccup with three custom attributes:

| Agent Writes | Transform Produces |
|---|---|
| `{:on:click :fn-name!}` | `{:data-on:click "@post('/ns/seon.foo/fn-name!')"}` |
| `{:on:submit :fn-name!}` | `{:data-on:submit "@post('/ns/seon.foo/fn-name!')"}` |
| `{:on:click:form :fn-name!}` | `{:data-on:click "@post('/ns/seon.foo/fn-name!', {contentType:'form'})"}` |
| `{:on:submit:form :fn-name!}` | `{:data-on:submit "@post('/ns/seon.foo/fn-name!', {contentType:'form'})"}` |
| `{:field ::weight}` | `{:name ":seon.foo/weight"}` |

Key observation: **everything is currently `@post`**. There is no mechanism to emit `@get`. The `:form` suffix controls `contentType` but not HTTP method.

### Current Routing (routes.clj)

Both POST and GET routes exist on the same URL pattern:

```
POST /ns/:namespace/:function  -> function-call-handler (mutations)
GET  /ns/:namespace/:function  -> function-get-handler  (reads)
```

The GET handler parses qualified keyword params from the query string and returns EDN. The POST handler parses form body data and returns JSON `{"success":true}`. Both share `resolve-and-call` for dispatch.

---

## How Datastar Handles GET vs POST

### Client-Side Behavior (fetch.ts)

Datastar's fetch plugin creates five HTTP method actions: `@get`, `@post`, `@put`, `@patch`, `@delete`. They share identical machinery except:

1. **GET sends signals as query params**, others send as body. For `contentType:'json'` (default), GET puts a `datastar` query param with JSON-encoded signals. For `contentType:'form'`, GET appends FormData as query params.

2. **GET defaults `openWhenHidden: false`** -- the SSE stream closes when the tab is hidden. POST/PUT/PATCH/DELETE default to `true` -- mutations complete even if tab is hidden.

3. **Server-side ReadSignals** uses request method to decide where to look for data: GET reads from `?datastar` query param, others read from request body.

### Datastar SDK Helpers (Clojure)

The official Clojure SDK provides `sse-get`, `sse-post`, `sse-put`, `sse-patch`, `sse-delete` -- thin string builders:

```clojure
(d*/sse-get "/endpoint")
;; => "@get('/endpoint')"

(d*/sse-post "/endpoint" "{contentType:'form'}")
;; => "@post('/endpoint', {contentType:'form'})"
```

### What Datastar Considers Idiomatic

Looking at the SDK examples, there is no strong convention around GET-for-reads vs POST-for-mutations. The examples use `@get` for almost everything (even operations that mutate server state), because Datastar's primary contract is SSE streaming, not REST semantics. The `form` example explicitly demonstrates both `form-get` and `form-post` with `contentType:'form'`.

**Key insight:** Datastar does not care about REST semantics. It cares about SSE streaming. The HTTP method only affects where signals/form data are placed (query vs body) and the `openWhenHidden` default.

---

## Security Analysis

### Current Security Gate

`actions.clj` checks `(str/starts-with? (str ns-sym) "seon.")` -- only functions in `seon.*` namespaces can be called from the browser. This prevents calling `clojure.core`, `java.lang`, etc.

### Can Rendered HTML Call ANY seon.* Function?

**Yes.** Any public function in any `seon.*` namespace can be called if the agent writes its name in an `:on:click` attribute. The function name appears in the URL: `POST /ns/seon.anything/any-public-fn!`.

### Is This a Problem?

For Seon's architecture, **no -- but it needs refinement**. The current model is:

1. Only `seon.*` namespaces are callable (external code blocked)
2. The namespace must be `require`-able (dead namespaces fail gracefully)
3. The function must exist and be public
4. Input is validated via the function's Malli schema

What is missing:

- **No allowlist per namespace.** A namespace cannot declare "only these functions are browser-callable." Every public function is exposed.
- **No method restriction.** A read-only function can be called via POST, a mutation can be called via GET.

### Proposed Security Model

Functions should opt-in to browser callability via metadata:

```clojure
(defn start-workout!
  {:malli/schema [...]
   :seon/browser true}  ;; callable from browser
  [{::keys [ctx]}]
  ...)
```

Without `:seon/browser true`, the function is only callable from REPL, agent code, or `seon/send!`. This is analogous to `:seon/subscribe true` for broadcast routing.

**For the initial implementation**, the current `seon.*` namespace check is sufficient. The `:seon/browser` metadata can be added later as a tightening refinement.

---

## Proposed DSL Evolution

### Design Goals

1. **Agents should not think about HTTP.** The DSL decides GET vs POST based on function naming conventions.
2. **Mutations use POST.** Functions ending in `!` are mutations. POST keeps the SSE stream alive if the tab is hidden.
3. **Reads use GET.** Functions without `!` are queries. GET naturally cancels when the tab is hidden.
4. **Form submission is explicit.** The `:form` suffix stays -- it changes `contentType`, not HTTP method.
5. **Override is available but rare.** Agents can force a specific method if needed.

### The DSL

#### Basic Events: Method Inferred from Function Name

```clojure
;; Agent writes:
[:button {:on:click :start-workout!} "Start"]
;; Transform produces (! suffix -> POST):
[:button {:data-on:click "@post('/ns/seon.health.workout/start-workout!')"} "Start"]

;; Agent writes:
[:button {:on:click :total-volume} "Show Volume"]
;; Transform produces (no ! -> GET):
[:button {:data-on:click "@get('/ns/seon.health.workout/total-volume')"} "Show Volume"]
```

**Rule:** If the function name (keyword value) ends in `!`, emit `@post`. Otherwise, emit `@get`.

#### Form Submission: `:form` Suffix

```clojure
;; Agent writes (form with mutation):
[:form {:on:submit:form :log-set!}
  [:input {:field ::exercise-name}]
  [:input {:field ::weight :type "number"}]
  [:button {:type "submit"} "Log"]]
;; Transform produces:
[:form {:data-on:submit "@post('/ns/seon.health.workout/log-set!', {contentType:'form'})"}
  [:input {:name ":seon.health.workout/exercise-name"}]
  [:input {:name ":seon.health.workout/weight" :type "number"}]
  [:button {:type "submit"} "Log"]]

;; Agent writes (form with read -- rare but valid, e.g. search):
[:form {:on:submit:form :search-history}
  [:input {:field ::query}]
  [:button {:type "submit"} "Search"]]
;; Transform produces (no ! -> GET, form data goes as query params):
[:form {:data-on:submit "@get('/ns/seon.health.workout/search-history', {contentType:'form'})"}
  [:input {:name ":seon.health.workout/query"}]
  [:button {:type "submit"} "Search"]]
```

#### Explicit Method Override

For rare cases where the convention is wrong:

```clojure
;; Force GET even though name has !
[:button {:on:click [:get :refresh!]} "Refresh"]
;; Transform produces:
[:button {:data-on:click "@get('/ns/seon.foo/refresh!')"} "Refresh"]

;; Force POST even though name lacks !
[:button {:on:click [:post :expensive-query]} "Run"]
;; Transform produces:
[:button {:data-on:click "@post('/ns/seon.foo/expensive-query')"} "Run"]
```

**Rule:** If the value is a vector `[method fn-name]`, use the explicit method. If it is a keyword, infer from `!` suffix.

#### Cross-Namespace Calls

```clojure
;; Call a function in a different namespace
[:button {:on:click :seon.trading/refresh-positions!} "Refresh Trading"]
;; Transform produces:
[:button {:data-on:click "@post('/ns/seon.trading/refresh-positions!')"} "Refresh Trading"]
```

**Rule:** If the keyword is qualified (has a namespace), use that namespace in the URL. If unqualified, use the current namespace from the transform context.

#### Passing Arguments via Data Attributes

For cases where a button needs to pass a value (e.g., which item to delete):

```clojure
[:button {:on:click :remove-item!
          :data-item-id (str id)} "Delete"]
```

This already works today -- Datastar signals include `data-*` attributes. The server receives them as part of the signals JSON. No DSL change needed.

#### Field Attribute (Unchanged)

```clojure
[:input {:field ::weight :type "number" :step "2.5"}]
;; Transform produces:
[:input {:name ":seon.health.workout/weight" :type "number" :step "2.5"}]
```

No change from current behavior. The `::` keyword expands to the current namespace, which is correct.

### Summary Table

| Agent Writes | Inferred Method | Transform Output |
|---|---|---|
| `{:on:click :fn!}` | POST | `@post('/ns/NS/fn!')` |
| `{:on:click :fn}` | GET | `@get('/ns/NS/fn')` |
| `{:on:click:form :fn!}` | POST | `@post('/ns/NS/fn!', {contentType:'form'})` |
| `{:on:click:form :fn}` | GET | `@get('/ns/NS/fn', {contentType:'form'})` |
| `{:on:click [:post :fn]}` | POST (explicit) | `@post('/ns/NS/fn')` |
| `{:on:click [:get :fn!]}` | GET (explicit) | `@get('/ns/NS/fn!')` |
| `{:on:click :other.ns/fn!}` | POST | `@post('/ns/other.ns/fn!')` |
| `{:field ::key}` | (n/a) | `{:name ":ns/key"}` |

---

## Implementation Plan

### Changes to transform.clj

1. **Add method inference.** In `transform-event-attr`, check if the function name ends in `!`. Emit `@post` for mutations, `@get` for reads.

2. **Support vector values.** If the value is `[:get :fn-name]` or `[:post :fn-name]`, use the explicit method.

3. **Support qualified keywords.** If the keyword has a namespace (e.g., `:seon.trading/refresh!`), use that namespace in the URL instead of the `ns-sym` parameter.

4. **No changes to `:form` handling.** The `:form` suffix continues to add `{contentType:'form'}`. It is orthogonal to HTTP method.

### Changes to routes.clj

1. **GET handler needs form parsing.** When Datastar sends `contentType:'form'` via GET, form fields arrive as query params. The current `function-get-handler` already parses `parse-keyword-params` from the query string, so this should work. Verify that the keyword parsing handles URL-encoded form field names correctly.

2. **POST handler should return SSE, not JSON.** Currently `function-call-handler` returns `{"success":true}` -- but Datastar expects SSE responses (or at minimum text/html). The handler needs to return SSE patches. This is a separate concern from the DSL but worth noting.

### Changes to actions.clj

No changes needed. The security gate (`seon.*` namespace check) applies regardless of HTTP method.

### Migration Path

The change is backward-compatible. Currently all events emit `@post`. After the change:

- Functions ending in `!` still emit `@post` (no behavior change)
- Functions without `!` switch from `@post` to `@get` (behavior change, but these are reads and GET is correct)

All existing demo code uses `!`-suffixed function names for interactive elements, so there is zero breakage.

---

## Questions Resolved

### 1. Can rendered HTML call ANY function in seon?

Yes, any public function in any `seon.*` namespace. The security boundary is namespace-prefix-based (`seon.*` only), not per-function. This is adequate for now; opt-in `:seon/browser true` metadata can tighten it later.

### 2. Do we want explicit GET vs POST control?

Yes, but inferred by convention (function name `!` suffix), not by forcing agents to think about HTTP. Explicit override via `[:get :fn]` / `[:post :fn]` vector syntax is available for rare edge cases.

### 3. What is the current transform DSL and how should we evolve it?

The current DSL has three primitives: `:on:EVENT`, `:on:EVENT:form`, and `:field`. These are sufficient. The evolution is:

- Add method inference (`!` -> POST, no `!` -> GET)
- Add vector override syntax
- Add qualified keyword support for cross-namespace calls
- Keep `:form` suffix unchanged
- Keep `:field` unchanged

The DSL is intentionally minimal. Agents write domain logic; the system handles HTTP.
