---
type: issue
status: open
severity: friction
tags: [issue, toolkit, render]
---

# `my.web/fetch` returns plain UTF-8 HTML as a vector of integers

## Problem

A 36-byte HTML body came back as `:my.web.body/octet-values` — thirty-six
separate integers — where the sibling capability `my.fs/read` decodes a valid
UTF-8 window to `:my.fs/text` and only falls back to bytes when the decode
genuinely fails. An agent fetching a web page, which is the ordinary case,
receives numbers it must reassemble, and the rendered face costs roughly an
order of magnitude more than the text it encodes.

Two capabilities in the same toolkit disagree about how to hand back bytes
that ARE text, and web's answer is the wrong one: `my.fs/read` already
demonstrates the right shape (strict UTF-8 decode, honest refusal or byte
fallback when it is not text).

## Evidence

Tool-repairs lane, 2026-08-08, cluster `x` in isolated operator root
`tmp/repairs-check`, driven as a real run through the full path (sci eval →
effect door → `:io` → receipt) with the tool-exercise harness. The complete
settled effect result:

```clojure
#:my.web{:url "http://127.0.0.1:17331/small",
         :final-url "http://127.0.0.1:17331/small",
         :status 200, :redirects [],
         :body #:my.web.body{:bytes 36,
                             :digest "b9fbb50a…a849",
                             :octet-values
                             [60 104 116 109 108 62 60 98 111 100 121 62 104
                              101 108 108 111 32 115 101 111 110 60 47 98 111
                              100 121 62 60 47 104 116 109 108 62]}}
```

Those integers are `<html><body>hello seon</body></html>`.

## Expected

A body that decodes as strict UTF-8 comes back as text, the way
`my.fs/read`'s window does, with the byte projection reserved for content
that genuinely is not text. The digest and byte count stay either way.

## Acceptance

- A `my.web/fetch` of an HTML page returns the body as text, proven from a
  real capability receipt.
- A fetch of genuinely binary content still returns bytes, and the refusal
  or fallback says which it chose.
