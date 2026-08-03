---
type: research
status: active
tags: [research, provider, web]
---

# Serper live probe — 2026-08-03

## Result

The owner's `SERPER_API_KEY` (40 characters, in `.env` per
`.env.example`) is live and valid. One probe query was executed on
2026-08-03 against the documented endpoint and consumed exactly one
credit.

## Verified wire facts (primary evidence, not vendor docs)

- Request: `POST https://google.serper.dev/search`, headers
  `X-API-KEY: <key>` and `Content-Type: application/json`, body
  `{"q": "<query>", "num": 3}`.
- Response top-level keys observed: `searchParameters`, `organic`,
  `credits`.
- `credits` reported `1` for the probe — per-response credit
  accounting is explicit in the payload.
- `organic` rows carry `title` and `link` (plus snippet fields not
  projected in the probe); results were real Google SERP rows
  (rewrite-clj query returned the clj-commons repository, cljdoc, and
  the user guide as the top three).

## What remains owed at implementation (my.web/search)

- Current pricing/terms re-verification (owner ruling: the
  implementing lane's first step; the 2026-07-06 figures — 2,500 free
  queries then ~$0.30–1.00/1k — are planning-vintage).
- The full response-row projection into the search-result schema
  (snippet, position, sitelinks, knowledge panels) from a saved raw
  response, not from memory.
- Provider stays a config fact behind one schema
  (`SERPER_API_KEY` is the credential variable name), never a
  provider-shaped public API. Brave remains the independent-index
  fallback.
