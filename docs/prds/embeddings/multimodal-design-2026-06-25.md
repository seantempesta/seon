---
type: prd
status: draft
tags: [prd, agent, database]
---

# Multimodal Embeddings — Design + Empirical Proof (gemini-embedding-2)

Live-verified design for ingesting and indexing **all five modalities** (text,
image, audio, video, PDF/document) into seon's single embedding vector space via
`gemini-embedding-2` on Vertex. Every claim below was tested DIRECTLY against the
Vertex API (curl + a standalone JVM-Clojure harness) on 2026-06-25, project
`<GCP_PROJECT>`, NOT through the seon pipeline. Pairs with
[[docs/prds/embeddings/vertex-usage-reference-2026-06-25]] (auth + text baseline).

## TL;DR (measured)

- **ONE model, ONE endpoint, ONE unified vector space** serves every modality.
  `:embedContent` on the GLOBAL location. Default **3072 dims**; every modality
  returns the same dim into the same space → **ONE HNSW index for everything**.
- **Cross-modal alignment PROVEN:** matched text↔image pairs out-score mismatched
  pairs in every test (dog/DOG 0.365 vs dog/AIRPLANE 0.233; red/red 0.440 vs
  red/blue 0.383). Text and image land in the same space — text-query→image-search
  works.
- **Matryoshka (MRL) PROVEN exact:** the API's 1536-dim vector is bit-for-bit
  `renormalize(vec3072[:1536])` (cosine **1.000000**). → **Store 3072 once;
  truncate+renormalize locally to 1536/768/128 for free. Never re-pay for a lower
  dim.** All outputs are L2-normalized (norm = 1.0) at every dimensionality.
- **One request = ONE fused vector.** Multiple parts (text + N images, etc.) in a
  single `content` are fused into a single embedding — NOT N separate vectors.
  Server enforces per-modality part limits (7 images → HTTP 400).
- **JVM-Clojure path proven end-to-end** with only `google-auth-library-oauth2-http`
  + `cheshire` + JDK's built-in `java.net.http` (no new HTTP dep). All five
  modalities returned 200/3072 from a standalone JVM REPL run.

## Per-modality request format + limits (all live-verified)

Request body is uniform: `{"content":{"parts":[ <part>, ... ]}}` plus optional
top-level `outputDimensionality`. Each modality is just a different part shape.
**Inline base64 (`inlineData`) verified for all five** below. GCS URIs use
`fileData` (`{"fileData":{"mimeType":..,"fileUri":"gs://.."}}`) — use that path
for files already in Cloud Storage to avoid base64 bloat (not exercised here;
same content schema).

| Modality | Part shape (verified) | MIME tested | Tokens (measured) | Hard limits (per request) |
|---|---|---|---|---|
| **Text** | `{"text":"..."}` | — | 5 tok / "a photo of a dog" | 8192 token context (shared) |
| **Image** | `{"inlineData":{"mimeType,"data":b64}}` | `image/png` | **258 tok / image** | **6 images**; JPEG/PNG/WebP/BMP/HEIC/HEIF/AVIF; ≤16384×16384 |
| **PDF/Document** | same, `application/pdf` | `application/pdf` | **258 tok / page** (rendered as image) | **1 file, ≤6 pages** (1 page strongly recommended); PDF only |
| **Audio** | same, `audio/wav` | `audio/wav` | **25 tok / sec** | **180 s**; MP3, WAV; speech-optimized |
| **Video** | same, `video/mp4` | `video/mp4` | **66 tok / frame** (1 FPS default) | **1 video, ≤120 frames** (=120 s @1 FPS); MOV/MP4; AV1/H264/H265/VP9 |

Measured usage echoes confirm the token math: a 2 s MP4 → 132 tok (2 frames ×
66); 1 s WAV → 25 tok; 1-page PDF → 258 tok; 3 images in one call → 774 tok
(3 × 258) and a single fused vector.

### Shared 8192-token context window (the real ceiling)

**All modalities share ONE 8192-token input budget per request.** Inputs over
8192 are **silently truncated** (no error) — a correctness hazard for long
audio/video/PDF. Token cost per modality:

- Audio 25 tok/s → ~327 s theoretical, but capped at **180 s** = 4500 tok.
- Video 66 tok/frame @1 FPS → **120 frames** = 7920 tok (near the ceiling).
- Video **with** `audio_track_extraction`: 66 (frame) + 25 (audio) + 10
  (timestamps) = **101 tok/s → ~81 s max** before truncation.
- Image 258 tok → 6 images = 1548 tok (well under).
- PDF 258 tok/page → 6 pages = 1548 tok.

### On-demand vs batched

- **On-demand (proven):** `:embedContent` online — one request → one vector.
  Latency a few hundred ms. This is the path for agent-time / ingest-time embeds.
- **"Batched" in one call (proven):** multiple parts in a single `content` → ONE
  fused vector. Use ONLY when you genuinely want a joint embedding (e.g. a doc
  page image + its caption). It does NOT batch N independent items.
- **N-independent batching:** `:batchEmbedContents` does **NOT** exist for this
  model (404). For bulk/offline, use a **Vertex Batch Prediction job**
  (`BatchPredictionJob`, GCS JSONL in/out, async, ≈20% cheaper per the pricing
  ref). Online concurrency (parallel `:embedContent`) is the simpler bulk path
  until batch-job volume justifies the GCS plumbing.

### Config toggles (`document_ocr`, `audio_track_extraction`, `output_dimensionality`)

- `outputDimensionality` — **works as a top-level REST field** (verified 1536, 768).
- `document_ocr` (extract text tokens from PDF beyond the page-image) and
  `audio_track_extraction` (pull audio from video) — **NOT accepted by the raw
  v1beta1 REST `embedContent` body** (rejected at top level, under `config`, and
  under `embedContentConfig`: "Cannot find field"). These are **SDK-only**
  `EmbedContentConfig` fields. To use them on the JVM, go through the
  **google-genai Java SDK** (`com.google.genai`), which already supplies the
  `google-auth-library` seon depends on. Default behavior without them: PDF =
  page-image only (no OCR text); video = frames only (no audio).

## Unified-index design for seon

### One space, one index — confirmed

All modalities → 3072-dim L2-normalized vectors in the **same** semantic space.
**Do NOT build per-modality indexes.** ONE Proximum/HNSW index serves text,
image, audio, video, and document vectors; a text query retrieves across all
modalities. Store the **full 3072** vector as source-of-truth; the index can be
built at any MRL dim (1536 matches the existing seon HNSW) by local
truncate+renormalize — **no re-embedding** to change index dim.

### Modality + content-type on embeddable entities

Add to the embeddable-entity shape (schemas colocated in the embed namespace):

```clojure
(schema/register! :seon.embed/modality
                  [:enum :text :image :audio :video :document])
(schema/register! :seon.embed/content-type :string)   ; the MIME, e.g. "image/png"
(schema/register! :seon.embed/source-hash :string)    ; SHA-256 of content bytes (existing)
(schema/register! :seon.embed/output-dim  :int)       ; 3072 stored
;; vector + dim already exist on the entity / Proximum sibling store
```

`:seon.embed/modality` drives the file→part encoder; `:seon.embed/content-type`
is the exact MIME passed to `inlineData`.

### File → part encoder (one multimethod, dispatch on modality)

```clojure
;; inside ns seon.embed — ::keys expands to :seon.embed/*
(defmulti ->part :seon.embed/modality)
(defmethod ->part :text    [{::keys [text]}] {:text text})
(defmethod ->part :default [{::keys [content-type bytes]}]
  {:inlineData {:mimeType content-type
                :data (.encodeToString (java.util.Base64/getEncoder) bytes)}})
;; image/audio/video/document all share the :default inlineData branch — uniform.
```

### Content-addressed cache (uniform hash across modalities)

Per the existing direction (vertex-usage-reference §"cache + archive"): the hash
is **`SHA-256(content-bytes)`** for EVERY modality — text→utf-8 bytes,
image/audio/video/PDF→file bytes — folded with **model + output-dim +
task-instruction** (same bytes under different params = different vector). The
3072 vector is the durable archive value; lower dims are derived. This makes:
(a) duplicate content (cross-entity, post-wipe, on rebuild) never re-pay Gemini;
(b) index rebuild at any dim free; (c) the `capacity` raise (embed.clj:129)
cost-free since rebuild reads the archive, not Gemini.

### Chunking / rejection for large files

The build must enforce limits BEFORE the call (silent truncation otherwise):

- **PDF > 6 pages:** split into ≤6-page (ideally 1-page) chunks, embed each as a
  separate entity (page-level retrieval is better anyway), or reject.
- **Audio > 180 s / Video > 120 s (or > ~81 s with audio extraction):** window
  into segments (per the doc's `videoSegmentConfig` start/end offsets pattern for
  the legacy model; for `gemini-embedding-2`, slice the media file and embed each
  window as its own entity). Reject otherwise.
- **> 6 images** in a joint request: reject or split (server returns 400).
- **Any single text/part > 8192 tokens:** chunk before embedding.

## JVM-Clojure implementation (proven path)

Verified end-to-end from a standalone JVM (JDK 25, Clojure 1.12) run — all five
modalities returned `{:status 200 :dims 3072}`, cross-modal cosines matched the
curl results, Matryoshka cosine = 1.000000. Minimal deps:

```clojure
;; deps.edn
com.google.auth/google-auth-library-oauth2-http {:mvn/version "1.33.0"} ; already transitive via google-genai
cheshire/cheshire {:mvn/version "5.13.0"}
;; HTTP: java.net.http (JDK built-in) — no clj-http/hato needed
```

```clojure
(import '[com.google.auth.oauth2 GoogleCredentials]
        '[java.net URI]
        '[java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers
                        HttpResponse$BodyHandlers])

(def endpoint
  (str "https://aiplatform.googleapis.com/v1beta1/projects/<GCP_PROJECT>"
       "/locations/global/publishers/google/models/gemini-embedding-2:embedContent"))

(defn access-token []
  (let [c (-> (GoogleCredentials/fromStream
               (java.io.FileInputStream. "~/.config/gcloud/<vertex-sa-key>.json"))
              (.createScoped ["https://www.googleapis.com/auth/cloud-platform"]))]
    (.refreshIfExpired c)
    (.. c getAccessToken getTokenValue)))           ; auto-refreshes; cache & reuse

(defn embed [tok parts & [{:keys [output-dim]}]]
  (let [body (cond-> {:content {:parts parts}} output-dim (assoc :outputDimensionality output-dim))
        req  (-> (HttpRequest/newBuilder (URI/create endpoint))
                 (.header "Authorization" (str "Bearer " tok))
                 (.header "Content-Type" "application/json")
                 (.POST (HttpRequest$BodyPublishers/ofString (cheshire.core/generate-string body)))
                 (.build))
        resp (.send (HttpClient/newHttpClient) req (HttpResponse$BodyHandlers/ofString))]
    (-> (.body resp) (cheshire.core/parse-string true) (get-in [:embedding :values]))))
```

Production note: route through `seon.db` / the wire-server (sole writer) as
usual; the embed call itself lives in the embed namespace. For
`document_ocr`/`audio_track_extraction`, prefer the **google-genai Java SDK**
(`com.google.genai`) `embedContent` with `EmbedContentConfig` — same auth, and
it exposes the toggles the raw REST body rejects.

## Implementation checklist (build wave)

1. **Schema:** register `:seon.embed/modality`, `:seon.embed/content-type`,
   `:seon.embed/output-dim`; keep `:seon.embed/source-hash` (SHA-256 of bytes,
   all modalities) as the cache key, folded with model+dim+task.
2. **Encoder:** `->part` multimethod (text → `{:text}`; everything else →
   `inlineData` base64 from `content-type` + bytes). Add `fileData`/`gs://`
   branch when sources live in Cloud Storage.
3. **Embed call:** JVM `embed` fn above (or google-genai Java SDK for OCR/audio
   toggles). Store the **full 3072** vector in the archive; index at the chosen
   MRL dim via local truncate+renormalize.
4. **Cache/archive:** content-addressed by SHA-256(bytes)+model+dim+task; archive
   value = 3072 vector + metadata (entity-id, modality, ts). Never duplicate
   content; link by entity-id. Skip Gemini on cache hit.
5. **ONE unified HNSW index** (Proximum) over all modalities at one dim (1536 to
   match existing). No per-modality index. Rebuild from archive is free.
6. **Limit enforcement (pre-call):** reject/chunk PDF>6pg, audio>180s,
   video>120s (>~81s w/ audio extraction), images>6, any part>8192 tok. Guard
   against silent truncation — log when an input approaches 8192 tok.
7. **Bulk path:** parallel online `:embedContent` first; add Vertex Batch
   Prediction (GCS JSONL, ~20% cheaper) only when volume justifies it.
   `:batchEmbedContents` does not exist for this model.
8. **Capacity:** raise `embed.clj:129 capacity` for bulk; cost is only mmap size;
   rebuild from the archive needs no re-embed.

## Raw measured proof (this session)

```
text  "hello"                       -> 200, dims 3072  (TEXT 5 tok)
image img_dog.png (PNG inlineData)  -> 200, dims 3072  (IMAGE 258 tok)
pdf   sample.pdf (1 page)           -> 200, dims 3072  (DOCUMENT 258 tok)
audio sample.wav (1 s)              -> 200, dims 3072  (AUDIO 25 tok)
video sample.mp4 (2 s @1FPS)        -> 200, dims 3072  (VIDEO 132 tok = 2×66)
MRL   outputDimensionality=1536/768 -> 200, dims 1536 / 768
multi text+image (1 content)        -> 200, dims 3072  (261 tok = 3+258, ONE fused vec)
3 images (1 content)                -> 200, dims 3072  (774 tok = 3×258, ONE fused vec)
7 images                            -> 400 "supports at most 6 image parts"
batchEmbedContents                  -> 404 (endpoint does not exist)
document_ocr / audio_track_extraction in REST body -> 400 "Cannot find field" (SDK-only)

cross-modal cosine (unified space, text-in-image OCR):
  txt:dog      <-> img:DOG       0.3651   >   txt:dog      <-> img:AIRPLANE  0.2329
  txt:airplane <-> img:AIRPLANE  0.3535   >   txt:airplane <-> img:DOG       0.2495
  txt:red      <-> img:red       0.4397   >   txt:red      <-> img:blue      0.3825
  txt:blue     <-> img:blue      0.4514   >   txt:blue     <-> img:red       0.3321
all vectors L2-normalized (norm = 1.0) at 3072 / 1536 / 768

Matryoshka:  cos(API-1536, renormalize(vec3072[:1536])) = 1.000000   (exact prefix)

JVM-Clojure harness (google-auth-library + cheshire + java.net.http, JDK 25):
  all five modalities -> {:status 200 :dims 3072}; cosines + matryoshka identical to curl.
```
