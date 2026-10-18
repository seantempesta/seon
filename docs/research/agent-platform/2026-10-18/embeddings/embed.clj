(require '[clojure.data.json :as json] 'clojure.edn)
(import '(java.net.http HttpClient HttpRequest HttpResponse$BodyHandlers HttpRequest$BodyPublishers)
        '(java.net URI) '(java.nio ByteBuffer ByteOrder) '(java.util Base64))
(def client (HttpClient/newHttpClient))
(defn embed [texts task dim]
  (let [req (-> (HttpRequest/newBuilder (URI. "http://127.0.0.1:8701/v1/embed"))
                (.header "content-type" "application/json")
                (.POST (HttpRequest$BodyPublishers/ofString (json/write-str {:texts texts :task task :dim dim :encoding "f32"})))
                .build)
        body (json/read-str (.body (.send client req (HttpResponse$BodyHandlers/ofString))) :key-fn keyword)
        fb (.asFloatBuffer (doto (ByteBuffer/wrap (.decode (Base64/getDecoder) ^String (:vectors body))) (.order ByteOrder/LITTLE_ENDIAN)))]
    {:ms (:ms body) :vectors (vec (for [i (range (:n body))] (let [a (float-array dim)] (.get (.position fb (int (* i dim))) a) (vec a))))}))
