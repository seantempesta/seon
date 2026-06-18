---
type: research
status: active
tags: [research, agent, reference]
---

# Gemini Embeddings for Code Retrieval — code-grounded (Java SDK, 2026-06-18)

> **MODEL DECISION (2026-06-18, proven live): use `gemini-embedding-2`, NOT
> `gemini-embedding-001`.** Per the official docs
> (<https://ai.google.dev/gemini-api/docs/embeddings>), `gemini-embedding-2`
> is the current GA model — multimodal, **8,192-token input** (vs 001's
> 2,048, which makes per-fn chunking unnecessary for our corpus) — but it
> **does NOT support `task_type`**; bake task instructions into the prompt
> text instead. Verified live in `tmp/embed-spike/`: v2 with a query prefix
> ("Retrieve the Clojure function whose source best implements this
> request:\n<q>") + raw source as the document gave 3/3 correct top-1 with
> clean margins (~0.45–0.50 hit vs ~0.69+ next) — on par with 001 +
> `CODE_RETRIEVAL_QUERY`. Everything else below (Java SDK call shape, dim
> 1536, L2-normalize, response accessors, Maven coord) is unchanged — swap
> the model id and drop `taskType` in favor of the prompt prefix.

Grounded in the REAL vendored source of the **official unified Google Gen AI
Java SDK** (`com.google.genai:google-genai`) at
`reference-code/java-genai/`. Every claim cites `file:line` against the
checked-out source and its `examples/` module. This is the DECIDED path:
Seon embeds Clojure function source on the **JVM wire-server** (co-located
with the JVM-only Proximum HNSW index) and calls this Java SDK from Clojure.

A Node SDK (`@google/genai`) also exists and an earlier draft of this doc was
grounded in it; we embed on the JVM, so this rewrite is the source of truth.
The universal MODEL facts (model id, dim, taskType pairing, token cap) are
SDK-language-agnostic and are carried over verbatim in "Universal model
facts" below.

## TL;DR — recommendation for Seon

- **SDK / Maven coordinate:** `com.google.genai:google-genai` (groupId
  `com.google.genai`, artifactId `google-genai`). The vendored tree is
  version `1.60.0-SNAPSHOT` (`pom.xml` `<version>1.60.0-SNAPSHOT</version>`);
  pin to the latest published GA release in deps.edn — do NOT depend on the
  `-SNAPSHOT`.
- **Client init (Gemini Developer API key, NOT Vertex):**
  `Client.builder().apiKey(key).build()` — confirmed no project/location/Vertex
  needed (`Client.java:100-106`, `README.md:43-50`). Defaults to the Gemini
  Developer API backend. The SDK also auto-reads `GOOGLE_API_KEY` then
  `GEMINI_API_KEY` from the environment if you call `new Client()` with no
  args (`ApiClient.java:826-845, 860-870`). We pass the key explicitly.
- **Model:** `gemini-embedding-001` (universal fact carried from prior
  research; the Java examples' `Constants.EMBEDDING_MODEL_NAME` happens to be
  `"gemini-embedding-2"`, `examples/.../Constants.java:61` — see the note in
  "Universal model facts"). The model id is a free-form `String` arg
  everywhere in this SDK.
- **Two call patterns** (same method, different arg type):
  - **Bulk / index time:** `client.models.embedContent(model, List<String>, config)`
    — sends ONE HTTP request for all texts; embeddings come back in input
    order (`Models.java:7751-7772`, `EmbedContentResponse.java:41-43`).
  - **Single / query time:** `client.models.embedContent(model, String, config)`
    — convenience wrapper that calls the `List<String>` path with a
    one-element list (`Models.java:7714-7716`).
- **taskType pairing:** `RETRIEVAL_DOCUMENT` at index time,
  `CODE_RETRIEVAL_QUERY` at query time — set via
  `EmbedContentConfig.builder().taskType("...")` (free `String`,
  `EmbedContentConfig.java:34-35, 102-103`). `CODE_RETRIEVAL_QUERY` validity
  is an API contract, NOT enforced by the SDK — confirm live.
- **Reduced dims (Matryoshka):** `EmbedContentConfig.builder().outputDimensionality(1536)`
  (`EmbedContentConfig.java:46-47, 140-141`). When you reduce dims you must
  L2-normalize the vector yourself (universal fact).
- **Vector extraction:** `response.embeddings().get().get(i).values().get()`
  → `List<Float>` per input (`EmbedContentResponse.java:42-43`,
  `ContentEmbedding.java:36-37`).
- **Gotchas:** `Client` is a heavyweight object holding an HTTP client — build
  ONCE as a long-lived singleton on the wire-server (see Q8). Truncation flag
  is `statistics().truncated()` but it is "Gemini Enterprise Agent Platform
  only" (likely absent on the plain Developer-API path — see Q6). Normalize
  reduced-dim vectors before cosine/HNSW.

---

## Q1 — Client init for a Gemini API key (no Vertex)

`Client.Builder.apiKey(String)` sets the Gemini Developer API key; `build()`
constructs the client. No project/location/credentials are required.

`Client.java:100-106`:

```java
/** Sets the API key for Gemini API. */
@CanIgnoreReturnValue
public Builder apiKey(String apiKey) {
  checkNotNull(apiKey, "apiKey cannot be null");
  this.apiKey = Optional.of(apiKey);
  return this;
}
```

`README.md:43-50` (canonical idiom):

```java
import com.google.genai.Client;

// Use Builder class for instantiation. Explicitly set the API key to use Gemini
// Developer backend.
Client client = Client.builder().apiKey("your-api-key").build();
```

The default backend is the Gemini Developer API; Vertex is opt-in only (via
`.vertexAI(true)` / `.project()` / `.location()`, `Client.java:108-162`, or
the `GOOGLE_GENAI_USE_VERTEXAI` env var). The `EmbedContent` example confirms:
"The client by default uses the Gemini Developer API. It gets the API key from
the environment variable `GOOGLE_API_KEY`."
(`examples/.../EmbedContent.java:60-68`).

**Env-var auto-pickup:** if you construct `new Client()` with no args, the SDK
reads `GOOGLE_API_KEY` first, then `GEMINI_API_KEY` as a legacy fallback
(`GOOGLE_API_KEY` wins if both set, with a warning)
(`ApiClient.java:826-845`, env mapping `ApiClient.java:860-870`). Seon's env
var is `GEMINI_API_KEY`, so either rename to `GOOGLE_API_KEY` for auto-pickup
OR (preferred) read `GEMINI_API_KEY` in Clojure and pass it to `.apiKey(...)`
explicitly — explicit is unambiguous and avoids the legacy-var warning.

`build()` (`Client.java:86-98`) just forwards the optionals to the private
constructor; an `IllegalArgumentException` is thrown only if you set BOTH an
API key AND project/location (`Client.java` constructor javadoc, line ~212).
A plain `.apiKey(key).build()` does not trip that.

## Q2 — Bulk embed: `embedContent(model, List<String>, config)`

`Models.java:7751-7772`:

```java
public EmbedContentResponse embedContent(
    String model, List<String> texts, EmbedContentConfig config) {
  List<Content> contents = new ArrayList<>();
  for (String text : texts) {
    contents.add(Content.fromParts(Part.fromText(text)));
  }
  Content content = null;
  if (!contents.isEmpty()) {
    content = contents.get(0);
  }
  boolean isVertexEmbedContentModel =
      this.apiClient.vertexAI() && Transformers.tIsVertexEmbedContentModel(model);
  if (isVertexEmbedContentModel && contents.size() > 1) {
    throw new IllegalArgumentException(
        "The embedContent API for this model only supports one content at a time.");
  }
  EmbeddingApiType apiType =
      isVertexEmbedContentModel
          ? new EmbeddingApiType("EMBED_CONTENT")
          : new EmbeddingApiType("PREDICT");
  return privateEmbedContent(model, contents, content, apiType, config);
}
```

**One HTTP request for the whole list.** `privateEmbedContent`
(`Models.java:5967-6000`) builds a single `EmbedContentParametersPrivate`
carrying ALL `contents` and makes exactly ONE call —
`this.apiClient.request("post", builtRequest.path(), builtRequest.body(), ...)`
(`Models.java:5995-5998`). No per-item looping at the HTTP layer.

**Result alignment:** embeddings come back in the SAME order as the inputs.
`EmbedContentResponse.embeddings()` is documented "The embeddings for each
request, **in the same order as provided in the batch request**"
(`EmbedContentResponse.java:41-43`). So `embeddings().get().get(i)` is the
vector for `texts.get(i)`.

**Max items / tokens:** the SDK source imposes NO explicit cap on list size
or token count for the Developer-API (`PREDICT`) path — the only size guard
is the Vertex-only `contents.size() > 1` check above, which does NOT apply to
us. Per-input the model's 2048-token cap (universal fact) still governs; the
total batch size is an API-side limit, not visible in the SDK code. Treat
batch size as tunable and confirm live (start with modest batches, e.g.
~100 texts/request, and back off on errors).

This is Seon's index-time path: embed a batch of function-source strings in
one call.

## Q3 — Single embed: `embedContent(model, String, config)`

Query-time path. It is a thin wrapper over the list overload
(`Models.java:7714-7716`):

```java
public EmbedContentResponse embedContent(String model, String text, EmbedContentConfig config) {
  return embedContent(model, ImmutableList.of(text), config);
}
```

So the response shape is identical; pull the single vector with
`response.embeddings().get().get(0).values().get()`.

`examples/.../EmbedContent.java:76-77`:

```java
EmbedContentResponse response =
    client.models.embedContent(modelId, "why is the sky blue?", null);
```

(`config` may be `null` when you want no taskType/dim overrides.)

## Q4 — `EmbedContentConfig` builder (taskType + outputDimensionality)

`EmbedContentConfig.java` is an AutoValue with these builder setters:

- `taskType(String)` — `EmbedContentConfig.java:34-35` (field, `Optional<String>`),
  `:102-103` (setter). Free string, NOT an enum.
- `outputDimensionality(Integer)` — `:46-47` (field), `:140-141` (setter).
  Docstring: "Reduced dimension for the output embedding. If set, excessive
  values in the output embedding are truncated from the end. Supported by
  newer models since 2024 only."
- `title(String)` — `:38-39`. "Only applicable when TaskType is
  `RETRIEVAL_DOCUMENT`."
- `autoTruncate(boolean)` — `:58-59` ("Gemini Enterprise Agent Platform only").
- `mimeType(String)` — `:50-51` ("Gemini Enterprise Agent Platform only").
- `documentOcr` / `audioTrackExtraction` — `:65-66`, `:72-73` (Enterprise only).
- `httpOptions(HttpOptions)` — `:76-77` (per-call timeout etc.).

Index-time config:

```java
EmbedContentConfig docCfg = EmbedContentConfig.builder()
    .taskType("RETRIEVAL_DOCUMENT")
    .outputDimensionality(1536)
    .build();
```

Query-time config:

```java
EmbedContentConfig queryCfg = EmbedContentConfig.builder()
    .taskType("CODE_RETRIEVAL_QUERY")
    .outputDimensionality(1536)
    .build();
```

Vendored example with config (`examples/.../EmbedContentWithConfig.java:76-81`):

```java
EmbedContentConfig config = EmbedContentConfig.builder().outputDimensionality(10).build();

EmbedContentResponse response =
    client.models.embedContent(
        modelId, ImmutableList.of("why is the sky blue?", "What is your age?"), config);
```

Note the example bulk-embeds two strings with one `outputDimensionality`
config — exactly Seon's index-time shape.

## Q5 — Response: getting the `List<Float>` per input

Accessor chain, single + bulk:

- `EmbedContentResponse.embeddings()` → `Optional<List<ContentEmbedding>>`
  (`EmbedContentResponse.java:42-43`).
- `ContentEmbedding.values()` → `Optional<List<Float>>`
  (`ContentEmbedding.java:36-37`): "A list of floats representing an embedding."

Single (query):

```java
List<Float> vec = response.embeddings().get().get(0).values().get();
```

Bulk (index), aligned to input order:

```java
List<ContentEmbedding> embs = response.embeddings().get();
for (int i = 0; i < embs.size(); i++) {
  List<Float> vec = embs.get(i).values().get();   // vector for texts.get(i)
}
```

Everything is `Optional`, so `.isPresent()`-guard or `.get()` per AutoValue
convention. There is no `float[]` accessor — convert `List<Float>` to
`float[]` for Proximum yourself.

## Q6 — Truncation detection (2048-token cap)

`ContentEmbedding.statistics()` → `Optional<ContentEmbeddingStatistics>`
(`ContentEmbedding.java:43-44`), and:

`ContentEmbeddingStatistics.java:33-42`:

```java
/** ... If the input text was truncated due to having a length
 * longer than the allowed maximum input. */
@JsonProperty("truncated")
public abstract Optional<Boolean> truncated();

/** ... Number of tokens of the input text. */
@JsonProperty("tokenCount")
public abstract Optional<Float> tokenCount();
```

**GOTCHA / flag:** both `truncated` and `tokenCount` are documented "Gemini
Enterprise Agent Platform only" — i.e. they may be ABSENT on the plain
Gemini Developer API (AI Studio key) response. Do NOT rely on
`statistics().truncated()` on the Developer-API path; treat it as best-effort.
Enforce the 2048-token cap defensively on OUR side (pre-truncate / pre-count
function source before embedding). Confirm live whether the Developer API
populates `statistics` at all.

## Q7 — Maven coordinate for deps.edn `:writer`

From `pom.xml` (top of file):

```xml
<groupId>com.google.genai</groupId>
<artifactId>google-genai</artifactId>
<version>1.60.0-SNAPSHOT</version><!-- vendored tree; pin a GA release in deps.edn -->
```

deps.edn (`:writer` alias) — pin to the latest published GA (NOT the
`-SNAPSHOT` of the vendored checkout):

```clojure
com.google.genai/google-genai {:mvn/version "1.59.0"}  ;; verify latest on Maven Central
```

Transitive deps it pulls (from `pom.xml`): Guava `33.4.0-jre`, Jackson,
`google-auth-library-oauth2-http`, Apache `httpclient`, `auto-value-annotations`,
`api-common` — all standard; Maven resolves them.

## Q8 — Threading / reuse (long-lived wire-server)

`Client` holds an `ApiClient` wrapping an Apache `HttpClient` plus the service
facades (`models`, `batches`, … — `Client.java:61-72`). It is a heavyweight,
connection-pool-backed object, NOT a per-request throwaway. The SDK source
contains no per-call mutable state on the embed path; `privateEmbedContent`
builds a fresh request object each call (`Models.java:5973-5999`). The README
and examples always build ONE client and reuse it.

**Recommendation:** build the `Client` ONCE at wire-server startup and hold it
as a long-lived singleton (an Integrant component / a `defonce`). The SDK
ships no explicit "thread-safe" guarantee in the source comments, but the
design (immutable config, pooled HTTP client, stateless call methods) is the
standard reuse-one-client model used by every example. Per-call overrides
(timeout) go through `EmbedContentConfig.httpOptions(HttpOptions.builder().timeout(ms))`
(`HttpOptions.java:50-52, 185-186`), not by rebuilding the client.

A batch-embeddings ASYNC job also exists for huge corpora:
`client.batches.createEmbeddings(model, EmbeddingsBatchJobSource, CreateEmbeddingsBatchJobConfig)`
→ `BatchJob` (`Batches.java:2743-2751`), Developer-API-only (throws
`UnsupportedOperationException` under Vertex). This is an aside — Seon's
primary path is the synchronous `List<String>` overload. Reach for the batch
job only if the function corpus grows to a scale where sync batching is too
slow.

---

## Clojure interop notes (wire-server)

The wire-server is the JVM, co-located with the Proximum HNSW index. Build the
client once, embed batches at index time, single texts at query time.

```clojure
(ns seon.embed
  (:import [com.google.genai Client]
           [com.google.genai.types EmbedContentConfig EmbedContentResponse]))

;; ---- long-lived singleton client (Q8) ----
(defonce ^Client client
  (-> (Client/builder)
      (.apiKey (System/getenv "GEMINI_API_KEY"))   ;; AIza... Developer-API key (Q1)
      (.build)))

(def ^String embedding-model "gemini-embedding-001")  ;; universal fact
(def out-dim 1536)                                     ;; Matryoshka reduced dim

(defn- config ^EmbedContentConfig [task-type]
  (-> (EmbedContentConfig/builder)
      (.taskType task-type)              ;; free String (Q4)
      (.outputDimensionality (int out-dim))
      (.build)))

(defn- ->float-array
  "ContentEmbedding values() -> Optional<List<Float>> -> float[] (Q5)."
  ^floats [content-embedding]
  (let [^java.util.List vals (-> content-embedding .values .get)
        arr (float-array (.size vals))]
    (dotimes [i (.size vals)]
      (aset arr i (float (.get vals i))))
    arr))

(defn- l2-normalize!
  "Required for reduced-dim Matryoshka vectors before cosine/HNSW (universal)."
  ^floats [^floats v]
  (let [n (Math/sqrt (areduce v i acc 0.0 (+ acc (* (aget v i) (aget v i)))))]
    (when (pos? n)
      (dotimes [i (alength v)] (aset v i (float (/ (aget v i) n)))))
    v))

;; ---- INDEX TIME: one HTTP request for a batch, order-aligned (Q2) ----
(defn embed-docs
  "texts: seq of function-source strings. Returns vec of normalized float[],
   aligned to input order."
  [texts]
  (let [jtexts (java.util.ArrayList. ^java.util.Collection (vec texts))
        ^EmbedContentResponse resp (.embedContent (.models client)
                                                   embedding-model
                                                   jtexts
                                                   (config "RETRIEVAL_DOCUMENT"))
        ^java.util.List embs (-> resp .embeddings .get)]
    (mapv (fn [i] (l2-normalize! (->float-array (.get embs i))))
          (range (.size embs)))))

;; ---- QUERY TIME: single text (Q3) ----
(defn embed-query
  [^String text]
  (let [^EmbedContentResponse resp (.embedContent (.models client)
                                                   embedding-model
                                                   text
                                                   (config "CODE_RETRIEVAL_QUERY"))]
    (l2-normalize! (->float-array (-> resp .embeddings .get (.get 0))))))
```

Notes:
- `(.models client)` — `models` is a public field on `Client`
  (`Client.java:63`); Clojure field access works as `(.models client)`.
- The `List<String>` overload accepts any `java.util.List`; an `ArrayList`
  built from a Clojure vector works (the example uses Guava `ImmutableList`,
  which is also fine but adds a Guava-interop import).
- L2-normalize ONLY matters because we reduce dims to 1536; the full native
  dimension is returned pre-normalized, but reduced Matryoshka slices are not.

## Universal model facts (carried from prior research, SDK-agnostic)

These are model/API contract facts, independent of SDK language. Verify the
starred items live against the Developer API.

- **Model:** `gemini-embedding-001` — GA embedding model on the Gemini
  Developer API (AI Studio key) path. (The Java examples default
  `Constants.EMBEDDING_MODEL_NAME = "gemini-embedding-2"`,
  `examples/.../Constants.java:61` — a newer/preview id; use
  `gemini-embedding-001` as the known-GA choice and confirm `-2` availability
  separately. The model id is a free `String` arg, so swapping is trivial.)
- **Dimensionality:** native ~3072; **reduce to 1536** via
  `outputDimensionality` (Matryoshka). **Reduced dims MUST be L2-normalized
  client-side** before cosine similarity / HNSW insertion. Native (un-reduced)
  output is already normalized.
- **taskType pairing for code retrieval:**
  - index time → `RETRIEVAL_DOCUMENT`
  - query time → `CODE_RETRIEVAL_QUERY` *(★ taskType is a free string at the
    SDK layer — `EmbedContentConfig.java:34-35` — so `CODE_RETRIEVAL_QUERY`
    validity is an API contract to confirm live; if rejected, fall back to
    `RETRIEVAL_QUERY`.)*
- **Input cap:** ~**2048 tokens** per input. Pre-truncate/pre-count function
  source on our side (the SDK truncation flag is Enterprise-only — Q6).
- **Distance:** cosine similarity (hence L2-normalize → dot product in HNSW).

## Sources (all in `reference-code/java-genai/`)

- `pom.xml` — Maven coordinate + version + transitive deps (Q7).
- `src/main/java/com/google/genai/Client.java:61-179, 195-239` — Builder,
  `apiKey()`, service fields, env-var construction (Q1, Q8).
- `src/main/java/com/google/genai/ApiClient.java:826-845, 860-894` —
  `GOOGLE_API_KEY` / `GEMINI_API_KEY` env resolution + precedence (Q1).
- `src/main/java/com/google/genai/Models.java:7706-7772` — `embedContent`
  overloads (String / Content / List<String>) (Q2, Q3);
  `:5967-6000` — `privateEmbedContent` (single HTTP request) (Q2).
- `src/main/java/com/google/genai/types/EmbedContentConfig.java:34-77, 102-141`
  — builder fields incl. `taskType`, `outputDimensionality` (Q4).
- `src/main/java/com/google/genai/types/EmbedContentResponse.java:41-43` —
  `embeddings()`, order guarantee (Q2, Q5).
- `src/main/java/com/google/genai/types/ContentEmbedding.java:36-44` —
  `values()` (`List<Float>`), `statistics()` (Q5, Q6).
- `src/main/java/com/google/genai/types/ContentEmbeddingStatistics.java:33-42`
  — `truncated()`, `tokenCount()` (Enterprise-only) (Q6).
- `src/main/java/com/google/genai/types/HttpOptions.java:50-52, 185-186` —
  per-call `timeout` (Q8).
- `src/main/java/com/google/genai/Batches.java:2743-2751` — async
  `createEmbeddings` batch job (Developer-API only) (Q8 aside).
- `examples/src/main/java/com/google/genai/examples/EmbedContent.java:60-77` —
  single-text example + Developer-API default note (Q1, Q3).
- `examples/src/main/java/com/google/genai/examples/EmbedContentWithConfig.java:76-81`
  — bulk `List<String>` + `outputDimensionality` config example (Q2, Q4).
- `examples/src/main/java/com/google/genai/examples/Constants.java:61` —
  `EMBEDDING_MODEL_NAME` (model-id note).
- `README.md:43-50, 81-118` — client-init idiom + env-var auto-pickup (Q1).
