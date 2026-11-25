(ns seon.fork.ui
  "Extracted child namespace of `seon.fork.helpers`."
  (:require [clojure.string :as str]
            [datahike.api :as d]
            [seon.code :as code]
            [seon.ctx :as ctx]
            [seon.fork.calls :as fcalls :refer [*agent-ctx*]]
            [seon.schema :as schema]
            [seon.store :as store]
            [seon.repl :as repl]
            [seon.fork.container :as fcontainer]
            [seon.cite :as cite]
            [seon.code.io :as cio]
            [seon.vector :as vec]
            [seon.vector.flow :as vflow]))

(defn ^:seon.flow/edge render-preview!
  "Renders Hiccup or a `:seon.ui/hiccup` renderer across display widths (default `[360 640 1100]`, `:dark`) and caches browser boxes in `seon.render.fit`."
  {:seon.repl/export true
   :malli/schema [:function [:=> [:cat :any] :seon.media/frames] [:=> [:cat :any :map] :seon.media/frames]]
   :cost/ticks 45000000}
  ([x] (render-preview! x nil))
  ([x opts]
   (let [o (if (map? opts) opts {}) rr requiring-resolve
         c (or (:conn o) (:conn *agent-ctx*))
         has-in? (or (contains? o :input) (contains? o :data) (contains? o :args))
         vsym (cond (qualified-symbol? x) x (var? x) (symbol x))
         co (merge {:widths [360 640 1100] :theme :dark} (select-keys o [:widths :theme :height :quality :max-width :label-prefix :timeout-ms :settle-ms :binary-path]))
         tgt (or (:render-fn o) (select-keys o [:port :url :direct? :browser-ch]))
         drv (when (and vsym (not has-in?))
               ((rr (quote seon.browser.preview/render-var-preview-frames!)) tgt (when c @c) vsym (merge co (select-keys o [:no-cache? :direct?]))))
         cap (or drv
                 (let [raw (if (or (fn? x) vsym)
                             (let [f (cond (fn? x) x (var? x) @x :else @(rr x))]
                               (cond (contains? o :args) (apply f (:args o)) (contains? o :input) (f (:input o)) (contains? o :data) (f (:data o)) :else (f)))
                             x)
                       nd (if (and (map? raw) (contains? raw :seon.ui/hiccup)) (:seon.ui/hiccup raw) raw)
                       html (if (string? nd) nd (str ((rr (quote seon.render/hiccup->html)) nd)))]
                   ((rr (quote seon.browser.preview/capture-html-frames!)) tgt html co o)))]
     (if-not (and (:ok? cap) (seq (:frames cap)))
       (with-meta {:ok? false :error (or (:error cap) :render-preview-failed) :persisted? false
                   :seon.media/frames [] :summary (str "Preview failed: " (or (:message cap) (:error cap) :render-preview-failed))}
                  {:seon/schema :seon.media/frames})
       (let [imgs (mapv (rr (quote seon.render.media/normalize-preview-frame)) (:frames cap))
             persist? (and (true? (:persist? o)) c (contains? (:schema @c) :seon.media/id))
             _ (when persist? (let [r (d/transact c {:tx-data imgs})] (when (:conn *agent-ctx*) (ctx/refresh-active! (:db-after r) (:tx-data r)))))
             base ((rr (quote seon.render.media/compact-preview-result)) (if persist? imgs (mapv #(dissoc % :seon.media/id) imgs)))]
         (cond-> (assoc base :ok? true :persisted? (boolean persist?))
           (:source drv) (assoc :source (:source drv) :var vsym)
           (integer? (:tokens drv)) (assoc :tokens (:tokens drv))
           (boolean? (:has-examples? drv)) (assoc :has-examples? (:has-examples? drv))
           (sequential? (:siblings drv)) (assoc :siblings (:siblings drv))
           (and (not persist?) (some? (:cached? drv))) (assoc :cached? (:cached? drv))))))))

(defn ^:seon.flow/edge ui-patch!
  "1-turn UI edit + derived preview shortcut returning `{:ok? :edit :preview :dom}`."
  {:seon.repl/export true
   :malli/schema [:function [:=> [:cat :any :any] :map] [:=> [:cat :any :any :any] :map] [:=> [:cat :any :any :any :map] :map]]
   :cost/ticks 60000000}
  ([target patch-or-defn] (ui-patch! target patch-or-defn nil {}))
  ([target old-str new-str] (if (and (vector? old-str) (map? new-str)) (ui-patch! target old-str nil new-str) (ui-patch! target old-str new-str {})))
  ([target old-or-defn new-str opts]
   (let [o (if (map? opts) opts {}) rr requiring-resolve
         sym (if (string? target) (symbol target) target)
         edit-res (cond (some? new-str) ((rr (quote seon.code.edit/edit-fn!)) sym old-or-defn new-str)
                        (vector? old-or-defn) ((rr (quote seon.code.edit/edit-fn!)) sym old-or-defn)
                        :else ((rr (quote seon.code.edit/add-fn!)) (if (namespace sym) (symbol (namespace sym)) sym) old-or-defn))]
     (if-not (:ok? edit-res)
       {:ok? false :edit edit-res :preview nil :dom nil}
       (let [eff-db (when-let [c (or (:conn o) (:conn *agent-ctx*))] @c)
             vsym (or (:var edit-res) (when (qualified-symbol? sym) sym))
             prev ((rr (quote seon.browser.preview/eval-renderer-preview)) eff-db vsym (assoc o :no-cache? true))
             want-b? (and (:ok? prev) (not= false (:browser? o)) (or (true? (:browser? o)) (fn? (:render-fn o)) (:browser-ch o) (:port o) (:url o)))
             fr (when want-b? ((rr (quote seon.browser.preview/render-var-preview-frames!)) (or (:render-fn o) (select-keys o [:port :url :direct? :browser-ch]))
                               eff-db vsym (merge {:widths [360 640] :theme :dark :no-cache? true} o)))
             dom (if (and (:ok? fr) (seq (:frames fr))) (mapv #(select-keys % [:label :width :height :dom]) (:frames fr))
                     (when (:ok? prev) ((rr (quote seon.render.fit/measure-hiccup)) (:hiccup prev))))]
         {:ok? (boolean (:ok? prev)) :edit edit-res
          :preview (select-keys prev [:ok? :var :source :has-examples? :tokens :input-schemas :siblings :input-hash :html :hiccup :error :message])
          :dom dom})))))

(defn ^:seon.flow/edge measure-renderers!
  "Measures all `:seon.ui/hiccup` renderers for `schema-key` in WKWebView and records browser boxes into `seon.render.fit`."
  {:seon.repl/export true
   :malli/schema [:function [:=> [:cat :qualified-keyword] :map] [:=> [:cat :qualified-keyword [:maybe :map]] :map]]
   :cost/ticks 60000000}
  ([schema-key] (measure-renderers! schema-key nil))
  ([schema-key opts]
   (let [o (if (map? opts) opts {}) c (or (:conn o) (:conn *agent-ctx*))]
     ((requiring-resolve (quote seon.browser.preview/measure-schema-renderers!))
      (or (:render-fn o) (select-keys o [:port :url :direct? :browser-ch])) (when c @c) schema-key o))))
