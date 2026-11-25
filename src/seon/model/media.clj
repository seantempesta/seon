(ns seon.model.media
  "Extracted child namespace of `seon.model.wire`."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [datahike.api :as d]))

(defn edn-tag-val
  {:malli/schema [:=> [:cat :symbol :any] :any], :cost/ticks 200000}
  [tag v]
  (case tag
    datahike/DB (with-meta (if (map? v) v {:value v}) {:seon.print/kind :datahike/db})
    datahike/AsOfDB (with-meta (if (map? v) v {:value v}) {:seon.print/kind :datahike/as-of-db})
    datahike/HistoricalDB (with-meta (if (map? v) v {:value v}) {:seon.print/kind :datahike/historical-db})
    datahike/Connection (with-meta (if (vector? v) {:store-id (first v) :branch (second v) :writer (nth v 2 nil)} {:value v})
                                   {:seon.print/kind :datahike/conn})
    (if (instance? clojure.lang.IObj v)
      (with-meta v {:seon.print/tag tag})
      {:seon.print/tag tag :value v})))

(defn safe-read-eval-edn
  {:malli/schema [:=> [:cat :seon.host/fn :string] :map]}
  [tag-fn val-str]
  (try {:ok? true :val (edn/read-string {:default tag-fn} val-str)}
       (catch Throwable ex {:ok? false :error (or (.getMessage ex) (str ex))})))

(defn- media-frame-map? [x]
  (and (map? x)
       (or (contains? x :seon.media/base64)
           (and (contains? x :seon.media/width) (contains? x :seon.media/height))
           (and (contains? x :seon.media/id) (contains? x :seon.media/label)))))

(defn dom-overflow-x?
  {:malli/schema [:=> [:cat :any] :boolean], :cost/ticks 200000}
  [dom]
  (boolean (when (map? dom) (or (:overflow-x? dom) (:overflowX dom)))))

(defn- format-media-frame-line [m]
  (let [w (if (number? (:seon.media/width m)) (max 0 (long (:seon.media/width m))) 0)
        h (if (number? (:seon.media/height m)) (max 0 (long (:seon.media/height m))) 0)
        lbl (or (when (and (string? (:seon.media/label m)) (not (str/blank? (:seon.media/label m))))
                  (str/trim (:seon.media/label m)))
                (str w "px"))
        ov-x? (dom-overflow-x? (:seon.media/dom m))
        id (:seon.media/id m)]
    (str "Image frame [" lbl "]: " w "×" h "px · overflow-x=" ov-x?
         (when id (str " · [:seon.media/id " (pr-str id) "]")))))

(defn format-media-eval-value
  {:malli/schema [:=> [:cat :any :any] [:maybe :string]], :cost/ticks 1000000}
  [parsed sch]
  (cond
    (and (map? parsed)
         (or (= :seon.media/frames sch)
             (and (contains? parsed :seon.media/frames) (sequential? (:seon.media/frames parsed)))))
    (let [fs (:seon.media/frames parsed)
          sum (when (and (string? (:summary parsed)) (not (str/blank? (:summary parsed))))
                (:summary parsed))
          lines (keep #(when (map? %) (format-media-frame-line %)) fs)]
      (if (or sum (seq lines))
        (str/join "\n" (concat (when sum [sum]) lines))
        "Image frames: 0"))

    (and (map? parsed) (or (= :seon.media/image sch) (media-frame-map? parsed)))
    (format-media-frame-line parsed)

    (and (sequential? parsed) (seq parsed) (every? media-frame-map? parsed))
    (str/join "\n" (map format-media-frame-line parsed))

    :else nil))

(defn strip-raw-base64-text
  {:malli/schema [:=> [:cat [:maybe :string]] [:maybe :string]], :cost/ticks 500000}
  [^String s]
  (if (and (string? s) (str/includes? s ":seon.media/base64"))
    (str/replace s #":seon\.media/base64\s+\"[^\"]*\"" ":seon.media/base64 \"<omitted>\"")
    s))

(defn- valid-db? [db]
  (and db (instance? datahike.db.interface.ISearch db) (map? (:schema db))))

(defn- pull-media-in-dbs [dbs ref]
  (let [pat '[:seon.media/id :seon.media/mime :seon.media/base64 :seon.media/width
              :seon.media/height :seon.media/label :seon.media/dom]
        try-db (fn [db]
                 (let [dh (:schema db)
                       ent (cond
                             (integer? ref) (d/pull db pat ref)
                             (and (map? ref) (integer? (:db/id ref))) (d/pull db pat (:db/id ref))
                             (and (vector? ref) (= 2 (count ref)) (= :seon.media/id (first ref)) (dh :seon.media/id))
                             (when-let [e (:e (first (d/datoms db :avet :seon.media/id (second ref))))]
                               (d/pull db pat e))
                             (and (uuid? ref) (dh :seon.media/id))
                             (when-let [e (:e (first (d/datoms db :avet :seon.media/id ref)))]
                               (d/pull db pat e))
                             :else nil)]
                   (when (and (map? ent) (not (str/blank? (str (:seon.media/base64 ent)))))
                     ent)))]
    (some try-db dbs)))

(defn- normalize-media-image [m]
  (when (and (map? m) (not (str/blank? (str (:seon.media/base64 m)))))
    (let [w (if (number? (:seon.media/width m)) (max 0 (long (:seon.media/width m))) 0)
          h (if (number? (:seon.media/height m)) (max 0 (long (:seon.media/height m))) 0)
          mime (or (when (and (string? (:seon.media/mime m)) (not (str/blank? (:seon.media/mime m))))
                     (str/trim (:seon.media/mime m)))
                   "image/jpeg")
          lbl (or (when (and (string? (:seon.media/label m)) (not (str/blank? (:seon.media/label m))))
                    (str/trim (:seon.media/label m)))
                  (str w "px"))]
      (cond-> {:mime mime
               :base64 (str (:seon.media/base64 m))
               :label lbl
               :width w
               :height h
               :dom (if (map? (:seon.media/dom m)) (:seon.media/dom m) {})}
        (:seon.media/id m) (assoc :id (:seon.media/id m))))))

(defn- eval-entity-image-refs [dbs c]
  (let [ev-id (or (:eval-id c) (:seon.agent.eval/id c))
        hdl (:handle c)
        cid (:id c)
        code (:code c)]
    (or (some (fn [db]
                (let [dh (:schema db)]
                  (when (dh :seon.agent.eval/images)
                    (cond
                      (and (uuid? ev-id) (dh :seon.agent.eval/id))
                      (when-let [e (:e (first (d/datoms db :avet :seon.agent.eval/id ev-id)))]
                        (seq (:seon.agent.eval/images (d/pull db '[:seon.agent.eval/images] e))))

                      (and (qualified-symbol? hdl) (dh :seon.agent.eval/handle))
                      (some (fn [dt]
                              (let [p (d/pull db '[:seon.agent.eval/images :seon.agent.eval/call-id :seon.agent.eval/code] (:e dt))]
                                (when (and (seq (:seon.agent.eval/images p))
                                           (or (nil? cid) (= cid (:seon.agent.eval/call-id p)) (= code (:seon.agent.eval/code p))))
                                  (:seon.agent.eval/images p))))
                            (reverse (vec (d/datoms db :avet :seon.agent.eval/handle hdl))))
                      :else nil))))
              dbs)
        [])))

(defn- collect-media-candidates-from-val [v]
  (let [parsed (cond
                 (string? v)
                 (if (and (< (count v) 2000000)
                          (or (str/includes? v ":seon.media/")
                              (str/includes? v "seon.media")))
                   (let [r (safe-read-eval-edn edn-tag-val v)]
                     (if (:ok? r) (:val r) v))
                   nil)
                 (coll? v) v
                 :else nil)
        from-tree (when (coll? parsed)
                    (keep (fn [node]
                            (cond
                              (and (vector? node) (= 2 (count node)) (= :seon.media/id (first node)) (uuid? (second node)))
                              node
                              (and (map? node) (not (str/blank? (str (:seon.media/base64 node)))))
                              node
                              (and (map? node) (uuid? (:seon.media/id node)))
                              [:seon.media/id (:seon.media/id node)]
                              :else nil))
                          (tree-seq coll? seq parsed)))
        from-re (when (and (string? v) (str/includes? v ":seon.media/id"))
                  (map (fn [[_ us]] [:seon.media/id (java.util.UUID/fromString us)])
                       (re-seq #":seon\.media/id\s+#uuid\s+\"([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})\"" v)))]
    (concat from-tree from-re)))

(defn extract-recent-eval-images
  "Looks at the latest completed turn in `history` (via `:seon.agent.eval/images`, `:images`,
   or `:seon.media/id` / inline media maps in `:value`), resolves up to `max-images` (default 4)
   `:seon.media/image` entities from `eval-db` (and optional `fallback-dbs`), and returns
   `[{:mime :base64 :label :width :height :dom ...}]`. Older turns automatically age out."
  {:malli/schema [:function
                  [:=> [:cat [:maybe :seon.host/db] :any] [:vector :map]]
                  [:=> [:cat [:maybe :seon.host/db] :any :int] [:vector :map]]
                  [:=> [:cat [:maybe :seon.host/db] :any :int :any] [:vector :map]]]
   :cost/ticks 10000000}
  ([eval-db history] (extract-recent-eval-images eval-db history 4 nil))
  ([eval-db history max-images] (extract-recent-eval-images eval-db history max-images nil))
  ([eval-db history max-images fallback-dbs]
   (let [cap (max 0 (long (or max-images 4)))
         last-turn (when (sequential? history) (last history))
         calls (when (map? last-turn) (:calls last-turn))]
     (if (or (zero? cap) (empty? calls))
       []
       (let [dbs (vec (distinct (filter valid-db? (cons eval-db (when (sequential? fallback-dbs) fallback-dbs)))))
             raw-refs (mapcat
                       (fn [c]
                         (when (map? c)
                           (concat (when (coll? (:seon.agent.eval/images c)) (:seon.agent.eval/images c))
                                   (when (coll? (:images c)) (:images c))
                                   (eval-entity-image-refs dbs c)
                                   (collect-media-candidates-from-val (:value c)))))
                       calls)
             resolve-one (fn [x]
                           (cond
                             (and (map? x) (not (str/blank? (str (:seon.media/base64 x)))))
                             (normalize-media-image x)

                             (and (map? x) (uuid? (:seon.media/id x)))
                             (normalize-media-image (pull-media-in-dbs dbs [:seon.media/id (:seon.media/id x)]))

                             :else
                             (normalize-media-image (pull-media-in-dbs dbs x))))]
         (loop [xs raw-refs seen #{} acc []]
           (if (or (empty? xs) (>= (count acc) cap))
             acc
             (if-let [img (resolve-one (first xs))]
               (let [dk (or (:id img) [(:label img) (:width img) (:height img) (hash (:base64 img))])]
                 (if (contains? seen dk)
                   (recur (rest xs) seen acc)
                   (recur (rest xs) (conj seen dk) (conj acc img))))
               (recur (rest xs) seen acc)))))))))
