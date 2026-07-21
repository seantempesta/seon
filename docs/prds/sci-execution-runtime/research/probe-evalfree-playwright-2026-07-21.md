---
type: research
status: active
tags: [research, agent]
---

# WP-B executable probe report: eval-free Playwright host

## Verdict

**PASS, with one required generic extension and one concurrency requirement.**

An eval-free Bun package host can drive `playwright-core` through structured operations. The successful prototype used no `eval`, `Function`, or host-authored callback code from the client.

WP-B should specify these operations:

1. `package-call`
   - Module identifier
   - Export/property path
   - Transit-safe arguments
   - Returns ordinary data or a held-object handle

2. `handle-call`
   - Handle ID
   - Method name and arguments
   - Awaits promises inside the host
   - Returns ordinary data or another handle

3. `handle-dispose`
   - Releases the object and retracts its live entry

4. `handle-describe`
   - Describes one handle or the bounded handle census

5. **Required extension: `handle-subscribe`**
   - Target handle, event name, bounded queue options
   - Installs a generic host-owned listener
   - Returns a subscription handle
   - Event objects such as `Dialog` and `Response` become ordinary handles
   - Polling uses ordinary `handle-call(subscription, "poll")`
   - Disposal uses ordinary `handle-dispose`

No separate `poll-events` or `unsubscribe` operation is necessary if a subscription is itself a held object.

WP-B must also mandate that multiple protocol sessions in one package host:

- share the same handle table;
- may execute concurrently, while retaining one active invocation per session; and
- can call event-payload handles while another session is blocked in a Playwright action.

This is necessary for dialogs and waiter-plus-trigger patterns.

## Prototype

Files are isolated under the authorized probe directory:

- [host.mjs](/Users/sean/src/seon/tmp/playwright-evalfree-probe/host.mjs)
- [probe.mjs](/Users/sean/src/seon/tmp/playwright-evalfree-probe/probe.mjs)
- [package.json](/Users/sean/src/seon/tmp/playwright-evalfree-probe/package.json)

Environment:

- Bun 1.3.14
- `playwright-core` 1.61.1
- Playwright Chromium revision 1228
- JSON-lines stdin/stdout transport
- Concurrent request dispatch with one active invocation per named session
- Bounded event queue: 64 events in the prototype

No `src/` or documentation files were changed, and no commit was made. Existing unrelated shared-tree changes under `src/seon/` were left untouched.

Reproduction command:

```bash
cd /Users/sean/src/seon/tmp/playwright-evalfree-probe
~/.bun/bin/bun probe.mjs
```

## Goal-path proof

Every browser step used only module-call, handle-call, disposal, description, and automatic data-or-handle result conversion:

1. `playwright-core` → `chromium.launch`
2. Browser handle → `newPage`
3. Page handle → `goto(data:...)`
4. Page handle → `locator("input[name=name]")`
5. Locator handle → `fill("Ada")`
6. Page handle → `locator("button")`
7. Locator handle → `click`
8. Page handle → confirmation locator
9. Locator handle → `textContent`
10. Page handle → `screenshot`

Observed result:

```text
browser channel:       playwright/browser
page channel:          playwright/page
input channel:         playwright/locator
confirmation text:     Confirmed: Ada
screenshot bytes:      8,095
base64 characters:     10,796
```

Promises were awaited inside the host. Only handles, strings, numbers, maps, and base64 data crossed the transport.

## Method chaining

Both variants worked.

### Intermediate handles

```text
page.locator(selector) -> playwright/locator handle
locator.click()         -> ordinary result
```

This is sufficient for Playwright and preserves locator identity for reuse, description, and explicit disposal.

### Declarative chain

A single `handle-call` also accepted:

```json
[
  {"method": "locator", "args": ["button"]},
  {"method": "click", "args": []}
]
```

The host awaited every segment. A chained fill and click produced:

```text
Confirmed: Grace
```

### Contract recommendation

WP-B should **mandate handle-returning intermediate calls**. They are the complete semantic mechanism and are required when an intermediate object must be reused or retained.

A declarative method chain should be an optional `handle-call` batching form, not a replacement for intermediate handles. It reduces round trips but cannot represent every object-lifetime pattern. This matches the program anchor’s permitted “batched op, never eval-in-the-host” escape hatch.

The current W6 text gives `package-call` an export/method path but describes `handle-call` as one method name. That asymmetry is workable but should be explicit. See W6 §2.1, lines 247–260.

## Awaited operations and events

### Plain awaited calls

These worked without any event extension:

| Pattern | Result |
|---|---|
| `waitForSelector("#later")` | Passed in 86.65 ms; returned `playwright/element-handle` |
| `waitForNavigation()` | Passed when armed on one session and triggered on another |
| `waitForResponse("**/wait-response")` | Passed; returned `playwright/response` |

A waiter and its triggering action cannot generally run sequentially. `waitForNavigation` and `waitForResponse` needed two concurrent sessions sharing the page handle.

### Listener-style operations

A generic call cannot pass a function to:

```javascript
page.on("dialog", callback)
page.on("response", callback)
```

This is the actual missing capability in W6’s four-operation set.

The prototype added:

```text
subscribe(page-handle, "response") -> subscription handle
handle-call(subscription, "poll")  -> buffered events
dispose(subscription)              -> remove listener
```

A response event returned a `playwright/response` handle. Calling `url()` on it returned:

```text
http://127.0.0.1:<ephemeral-port>/event
```

### Dialog proof

End-to-end observed flow:

1. Subscribe to `dialog`.
2. Start a click on session `trigger`.
3. Host-local generic listener buffers the dialog as a handle.
4. Session `events` polls the subscription.
5. `dialog.message()` returns `probe-dialog`.
6. `dialog.accept()` runs through ordinary handle-call.
7. The original click settles.

Observed click-to-accepted-settlement time:

```text
54.62 ms
```

This proves a buffered event handle is sufficient; arbitrary callbacks or evaluation are unnecessary. It also proves that a single serial session would deadlock this flow.

### Production event shape

WP-B should strengthen the prototype queue to include:

- bounded capacity from configuration;
- monotonically increasing event sequence;
- poll cursor;
- dropped-event count and oldest retained sequence;
- event name and timestamp;
- ordinary projected arguments or tagged handles;
- automatic subscription cleanup when its target is disposed;
- explicit subscription disposal.

A cursor-based read is preferable to destructive polling because request retry must not silently lose events.

## Failure evidence

All requested failures returned structured error objects, and the Bun host survived.

| Failure | Structured result |
|---|---|
| Unreachable endpoint | `kind: Error`, `goto: net::ERR_CONNECTION_REFUSED at http://127.0.0.1:65534/unreachable` |
| Missing selector | `kind: TimeoutError`, `waitForSelector: Timeout 120ms exceeded` |
| Browser killed during a 30-second waiter | `kind: Error`, `Target page, context or browser has been closed` |
| Disposed handle called again | `kind: UnknownHandleError`, `unknown handle: h17` |

The browser-kill drill sent `SIGKILL` to the second browser’s direct process while `waitForSelector` was pending. The waiting request rejected as structured data. A subsequent `describe` call on the original page succeeded, proving the package host remained alive.

This distinguishes browser-child failure from package-host failure. If the Bun package host itself dies, the client must synthesize the structured error and generation-staleness result because the dead process cannot send one. That agrees with W6 §5’s hostile-host gate.

## Disposal proof

Observed:

```json
{"disposed": "h17"}
```

A following method call returned:

```json
{
  "kind": "UnknownHandleError",
  "message": "unknown handle: h17",
  "op": "handle_call",
  "handle": "h17"
}
```

## Measured overhead

The benchmark performed 80 sequential `page.title()` calls in each mode:

| Mode | Total | Per call |
|---|---:|---:|
| Eval-free host over JSON lines | 17.84 ms | 0.223 ms |
| Direct Playwright | 15.65 ms | 0.196 ms |
| Measured added overhead | 2.19 ms | **0.027 ms** |

Across successful repeats, measured transport addition ranged approximately **0.001–0.047 ms per call**. Treat this as local-process rough evidence, not a UDS/Transit production benchmark. Browser protocol work dominates these small calls; batching remains useful for longer chains and future transport overhead.

## W6 assumptions requiring amendment

### 1. The fixed operation set lacks listener semantics

W6 §2.1 lists package-call, handle-call, dispose, and describe, but Playwright listener APIs require host-local callback installation. Add the generic subscription operation described above.

The extension remains eval-free and can remain structurally inside the handle table: a subscription is a held object containing its target, listener, and bounded event ring.

### 2. One-active-invocation semantics need shared concurrent sessions

W6 §2.1 says one invocation is active per session. That remains valid, but WP-B must explicitly say all sessions in a package-host process share handles and dispatch concurrently. Dialog handling and waiter-plus-trigger flows depend on it.

### 3. Known Playwright channels need explicit classification

The real runtime constructor names were internal names such as `_Page`, `_Locator`, `_Response`, `Browser2`, and `Dialog2`. Naively deriving a producer channel from `constructor.name` produced incorrect channels.

WP-B must have a package/channel classifier or adoption registry that deliberately maps known Playwright objects to:

```text
playwright/browser
playwright/page
playwright/locator
playwright/response
playwright/dialog
playwright/element-handle
```

This strengthens W6 §4’s statement that known channel types become typed handles.

### 4. Arguments eventually need recursive handle references

The prototype only needed ordinary arguments, but full Playwright coverage includes methods whose options contain other live objects, such as locators. WP-B should allow tagged handle references recursively inside argument data and resolve them against the same host/generation before invocation.

“Only ordinary data crosses” remains true: the wire carries a handle reference, never the live object.

### 5. Not every Playwright API is compatible with the eval-free boundary

Common browser automation—locators, actions, navigation, screenshots, waiters, dialogs, and response events—is viable.

APIs that intrinsically accept authored executable functions or source, including arbitrary `page.evaluate`, predicate callbacks, and some routing forms, cannot be exposed as unrestricted generic calls while preserving the “never evaluate agent-authored code” rule in W6 §2.1. They require audited declarative capability operations or must remain unavailable.

This does not block the tested browser-driving goal, but WP-B should state the boundary rather than implying every exported Playwright method is admissible.

## Gate conclusion

WP-B can proceed with an eval-free package host.

The minimum complete contract is:

```text
package-call
handle-call
handle-dispose
handle-describe
handle-subscribe -> subscription handle
```

with automatic data-or-handle conversion, recursive handle references in arguments, bounded cursor-addressed event buffers, explicit known-channel adoption, and concurrent shared-handle sessions.

No general evaluator and no remotely supplied callback function are required.