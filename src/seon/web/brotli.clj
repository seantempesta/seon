(ns seon.web.brotli
  "Brotli compression utilities for streaming SSE connections.

  Ported from hyperlith/impl/brotli.clj

  Key insight: Streaming compression over the SSE connection lifetime
  achieves 90-100x compression by maintaining compressor state across writes.
  This is far more efficient than per-message compression.

  Main API for SSE integration:
  - [[->write-profile]] - Creates SDK-compatible write profile for seon.web.sse"
  (:require
   [clojure.java.io :as io]
   [clojure.math :as m])
  (:import (com.aayushatharva.brotli4j Brotli4jLoader)
           (com.aayushatharva.brotli4j.encoder Encoder Encoder$Parameters
                                               Encoder$Mode BrotliOutputStream)
           (com.aayushatharva.brotli4j.decoder Decoder BrotliInputStream)
           (java.io ByteArrayOutputStream IOException OutputStreamWriter)
           (java.nio.charset StandardCharsets)))

;; Ensure brotli native library is loaded on namespace init.
#_:clj-kondo/ignore
(defonce ensure-br
  (Brotli4jLoader/ensureAvailability))

(defn window-size->kb
  "Convert brotli window size parameter to KB.
  Window size is (2^window-size - 16) bytes."
  [window-size]
  (/ (- (m/pow 2 window-size) 16) 1000))

(defn encoder-params
  "Create encoder parameters for brotli compression.

  Options:
  - :quality    - Compression quality 0-11 (default: 5)
  - :window-size - LZ77 window size 10-24 (default: 24)
                   Larger = better compression but more memory

  Mode is always TEXT (optimized for UTF-8 text)."
  [{:keys [quality window-size]}]
  (doto (Encoder$Parameters/new)
    (.setMode Encoder$Mode/TEXT)
    ;; LZ77 window size (0, 10-24) (default: 24)
    ;; window size is (pow(2, NUM) - 16)
    (.setWindow (or window-size 24))
    (.setQuality (or quality 5))))

(defn compress
  "Compress data in one shot (not streaming).

  Data can be a string or byte array.
  Returns compressed byte array.

  For SSE, use compress-stream instead for streaming compression."
  [data & {:as opts}]
  (-> (if (string? data) (String/.getBytes data "UTF-8") ^byte/1 data)
      (Encoder/compress (encoder-params opts))))

(defn byte-array-out-stream
  "Create a new ByteArrayOutputStream for streaming compression."
  ^ByteArrayOutputStream []
  (ByteArrayOutputStream/new))

(defn compress-out-stream
  "Create a BrotliOutputStream for streaming compression.

  out-stream - ByteArrayOutputStream to write compressed data to
  opts       - Encoder options (see encoder-params)

  Returns BrotliOutputStream that maintains compression state across writes."
  ^BrotliOutputStream
  [^ByteArrayOutputStream out-stream & {:as opts}]
  (BrotliOutputStream/new out-stream (encoder-params opts)
                          ;; TODO: Default buffer size for brotli library, needs to be tuned.
                          16384))

(defn compress-stream
  "Compress a chunk of data in a streaming context.

  out    - ByteArrayOutputStream that accumulates compressed output
  br     - BrotliOutputStream that maintains compression state
  data   - String data to compress

  Returns: Compressed byte array for this chunk (out is reset after reading)

  This is the key function for SSE compression - it maintains state across
  multiple calls, allowing the compressor to learn patterns and achieve
  90-100x compression over the connection lifetime."
  [^ByteArrayOutputStream out ^BrotliOutputStream br data]
  (doto br
    (.write (String/.getBytes ^String data "UTF-8"))
    (.flush))
  (let [result (.toByteArray out)]
    (.reset out)
    result))

(defn decompress
  "Decompress data in one shot (not streaming).

  data - Compressed byte array

  Returns decompressed string."
  [data]
  (let [decompressed (Decoder/decompress data)]
    (String/new (.getDecompressedData decompressed))))

(defn decompress-stream
  "Decompress data from a stream, handling incomplete streams gracefully.

  data - Compressed byte array or string

  Returns decompressed string. IOException is caught to allow decompressing
  of incomplete streams (useful for testing/debugging)."
  [data]
  (with-open [in  (-> (if (string? data) (String/.getBytes data "UTF-8") data)
                      io/input-stream
                      (BrotliInputStream/new))
              out (ByteArrayOutputStream/new)]
    (.enableEagerOutput in)
    (try ;; Allows decompressing of incomplete streams
      (loop [b (.read in)]
        (when (> b -1)
          (.write out b)
          (recur (.read in))))
      (catch IOException _))
    (str out)))

;;; ---------------------------------------------------------------------------
;;; SDK-Compatible Write Profile for SSE
;;; ---------------------------------------------------------------------------

(defn ->brotli-output-stream
  "Create a BrotliOutputStream wrapping a ByteArrayOutputStream.

  opts - Encoder options (see encoder-params):
    :quality     - Compression quality 0-11 (default: 5)
    :window-size - LZ77 window size 10-24 (default: 24)"
  ^BrotliOutputStream
  [^ByteArrayOutputStream baos & {:as opts}]
  (BrotliOutputStream/new baos (encoder-params opts) 16384))

(defn ->write-profile
  "Create an SSE write profile using Brotli compression.

  This returns a map compatible with seon.web.sse/render-handler's
  :write-profile option. The compression state is maintained across
  all writes, achieving 90-100x compression over connection lifetime.

  Options (passed to encoder):
  - :quality     - Compression quality 0-11 (default: 5)
  - :window-size - LZ77 window size 10-24 (default: 24)

  Example:
    (sse/render-handler render-fn :write-profile (brotli/->write-profile))

  Note: Uses raw namespaced keywords to avoid circular dependency
  (sse lazy-requires brotli for auto-negotiation)."
  [& {:as opts}]
  {;; Use raw keywords to avoid circular dep with seon.web.sse
   :seon.web.sse/wrap-output-stream
   (fn [^ByteArrayOutputStream baos]
     (-> baos
         (->brotli-output-stream opts)
         (OutputStreamWriter. StandardCharsets/UTF_8)))

   :seon.web.sse/content-encoding "br"})

(comment
  ;; Test basic compression/decompression
  (decompress (compress "hellohellohello"))
  ;; => "hellohellohello"

  ;; Test streaming compression (what SSE will use)
  (let [out (byte-array-out-stream)
        br (compress-out-stream out)]
    ;; First chunk
    (compress-stream out br "Hello ")
    ;; Second chunk
    (compress-stream out br "World!")
    ;; The compressor maintains state across both calls
    )

  ;; Test write profile
  (->write-profile)
  ;; => {:seon.web.sse/wrap-output-stream #fn, :seon.web.sse/content-encoding "br"}
  )
