---
type: research
status: active
tags: [research, agent, toolkit, web, provider]
---

# `my.web` implementation evidence — 2026-08-03

## Authorities read in full

Before probing or designing, this lane read these named authorities end to end:

- [agent tools design](agent-tools-design-2026-08-03.md), including the Hato
  `1.0.0` and jsoup `1.22.2` package decisions and their admission falsifiers;
- [agent tools quarry](agent-tools-quarry-2026-08-03.md), including the
  `my.web` rebirth verdict and the fetch-before-search ordering; and
- [Serper live probe](serper-probe-2026-08-03.md), including the observed
  request headers, endpoint, response keys, organic row fields, and credits
  field.

The landed implementation patterns were also read in full:
`src/my/fs.clj` for the declared capability entry and `src/seon/blob.clj` for
binary publication and bounded chunk reads.

## Dependency ledger

| dependency or owner | selected revision | evidence and use |
|---|---|---|
| OpenJDK | `26.0.1` | `src/seon/ai.clj:800-950` is the current first-party synchronous `java.net.http` owner. The comparison script uses the same `HttpClient.send` plus `BodyHandlers/ofInputStream`. |
| Hato | `8c80539c7fce9fa92320fa711d9c22ff78e7d3dd` (`1.0.0`) | `reference-code/hato/src/hato/client.clj:220-342` shows that redirects, request timeout, streaming, and synchronous send delegate directly to JDK `HttpClient`. It remains reference-only after the comparison below. |
| jsoup | `ac28afe6e5bf96d39fd17c3e0a797a7585e1958c` (`1.22.2`) | `reference-code/jsoup/src/main/java/org/jsoup/Jsoup.java:250-287` parses a caller-owned byte stream with charset detection; `Document.java:188-211` and `Element.java:1540-1577` produce ordinary title/text strings. No DOM object crosses the handler. |
| data.json | `2.5.1` | Existing production dependency and codec used by `src/seon/ai.clj`; search uses string wire keys and projects them immediately to namespaced rows. |
| effect owner | current branch | `src/seon/effect.clj` opens one receipt before dispatch, runs the declared handler on `:io`, and settles terminal EDN once. Search credits therefore remain queryable in the receipt's settled result rather than creating a second write path. |
| blob owner | current branch | `src/seon/blob.clj` publishes streamed binary by digest and returns bounded chunks for byte-exact verification. |

The exact Hato and jsoup source pins are now repository submodules under
`reference-code/`; no package behavior is inferred from an artifact alone.

## Live Serper pricing, terms, and wire probe

One paid probe used the owner's `SERPER_API_KEY` without printing or storing
the key. It sent:

```edn
{:endpoint "https://google.serper.dev/search"
 :headers ["X-API-KEY" "Content-Type: application/json"]
 :body {"q" "site:serper.dev pricing terms API credits" "num" 5}}
```

The response consumed exactly one credit and carried:

```edn
{:top-level-keys ["credits" "organic" "searchParameters"]
 :credits 1
 :organic-count 3
 :organic-row-keys ["position" "title" "link" "snippet"]}
```

The first row was Serper's pricing page and the second its terms page. The
current official pricing is a top-up model: 50k credits cost $50
($1.00/1k), 500k cost $375 ($0.75/1k), 2.5M cost $1,250 ($0.50/1k), and
12.5M cost $3,750 ($0.30/1k); credits are valid for six months. Credits are
deducted for successful responses, and new queries stop at zero balance.
The terms retain a full-refund condition only within seven calendar days of
the first purchase and below 20% credit use. Evidence:
[Serper pricing and FAQ](https://serper.dev/) and
[Serper terms](https://serper.dev/terms).

The raw response remains project-local under
`tmp/my-web/serper-live-2026-08-03.json`; the durable record above contains
the wire shape without copying volatile search prose into source.

## Transport falsifiers and decision

The recurring comparison source is
[`web-transport-falsifiers-2026-08-03.clj`](scripts/web-transport-falsifiers-2026-08-03.clj).
It drives both candidates against the same local server with event latches,
not sleeps. Observed on OpenJDK `26.0.1`:

| falsifier | JDK client | Hato `1.0.0` |
|---|---:|---:|
| redirect policy `NEVER` | status `302`, location visible, original URI visible | same |
| streamed first chunk before suffix release | yes | yes |
| no-headers timeout requested at 100 ms | `HttpTimeoutException`, 107 ms | `HttpTimeoutException`, 110 ms |
| stalled body after headers, timeout requested at 500 ms | body read failed with `IOException`, 510 ms | body read failed with `IOException`, 502 ms |

Both transports satisfy the named falsifiers because Hato delegates them to
the same JDK implementation. Neither records a redirect chain or supplies the
per-hop private-address decision; `my.web` must keep redirects disabled and
perform the bounded hop reduce itself either way.

**Decision: retain the current JDK client.** Hato adds a Ring-shaped request
map and middleware stack but no behavior the protected handler needs. The JDK
client already owns Seon's provider HTTP path, exposes the exact final URI,
headers, stream, interruption, and timeout behavior directly, and leaves the
bounded redirect/data policy in one first-party owner. Hato remains
reference-only; adding it to production would create more surface without a
measured gain.

## Contract decisions

- Public capability API: exactly `my.web/fetch` and `my.web/search`, each
  declaring `:seon.workload :io` and its one protected `seon.web.jvm` handler.
- Fetch accepts one absolute HTTP(S) URL and `:get` or `:head`. The handler
  resolves and checks every hop, follows redirects itself with a declared
  depth ceiling, records every hop, preserves final URL/status/content type,
  and keeps raw body bytes as the authority.
- A body at or below the measured inline threshold returns exact octet values.
  A larger body streams through `seon.blob`; both arms carry the same SHA-256
  and byte count, and the response ceiling applies to both declared-length and
  chunked bodies.
- HTML extraction is a separate jsoup projection over the already-captured
  bytes. It emits only title/text strings; failure to extract never changes or
  hides the raw body descriptor.
- Search reads one config-derived provider descriptor: endpoint, credential
  variable name, and result-projection symbol. The protected handler knows no
  provider name. The configured projection converts the observed organic row
  shape to `title`, `link`, optional `snippet`, and `position` rows.
- Search always stores the raw JSON response by digest. Its `credits` value is
  present in the returned result and therefore in the generic effect receipt's
  settled result EDN; no handler-side transaction or second receipt mechanism
  exists.

The retained measured defaults are 30 seconds for the genuinely remote-call
backstop, 2,000,000 response bytes from the quarry's web heap ceiling, five
redirect hops, 20 search rows, and 8,192 inline bytes from the current
filesystem/blob crossover evidence. Each is one config fact and no fallback
literal lives in the handler.

## Verification boundary

The implementation namespaces load successfully with jsoup `1.22.2` on the
classpath. The focused test run reached the two public-entry metadata/schema
falsifiers (13 passing assertions), then failed during every file-database
fixture before a web handler ran. The concurrent program-indexing change
attempted to transact `[:seon.cluster.agent/id
:seon.config/agent-overlay]` as one value of `:seon.fn/keywords`; the installed
attribute accepts a keyword, not a vector. The exact refusing boundary is
`seon.fn/index!`'s declarations phase, called by
`seon.test-support/populate-database!`.

Per the lane stop rule, handler falsifiers and the through-`effect/request!`
receipt proof remain pending behind that external indexing boundary. The
direct transport comparison and paid Serper probe above are complete; no
second paid query was attempted.

## Tool/render feedback

- Hato's Ring-shaped request/response maps add surface but no measured web
  behavior over the JDK client for this capability; keeping it reference-only
  makes the protected handler easier to inspect.
- `seon.blob/put-binary!` left its explicit staging file behind when a bounded
  producer failed after staging began. The web spill falsifier exposed that
  cleanup seam; the implementation deletes only that exact staging path on
  exceptional exit.
- The focused runner emitted roughly 23 MB of repeated transaction stack data
  for four fixtures sharing the same indexing refusal. That output is noisy
  enough to obscure the first causal value; recurring test reporting should
  preserve the complete artifact while presenting one grouped cause in the
  human-facing summary.
