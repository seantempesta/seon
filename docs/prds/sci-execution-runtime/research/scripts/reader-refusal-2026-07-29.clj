(require '[sci.core :as sci])
(let [ctx (sci/init {})
      refuse (fn [tag] (fn [_] (throw (ex-info (str "refused tag " tag) {:tag tag}))))
      try-read (fn [label opts text]
                 (println label
                   (try (pr-str (sci/parse-next ctx (sci/reader text) opts))
                     (catch Throwable e (str "REFUSED: " (ex-message e))))))]
  ;; baseline: empty readers map
  (try-read "inst/{} " {:readers {}} "#inst \"2020-01-01\"")
  (try-read "uuid/{} " {:readers {}} "#uuid \"00000000-0000-0000-0000-000000000000\"")
  ;; the candidate mechanism: a TOTAL refusing function
  (try-read "inst/fn " {:readers refuse} "#inst \"2020-01-01\"")
  (try-read "uuid/fn " {:readers refuse} "#uuid \"00000000-0000-0000-0000-000000000000\"")
  (try-read "foo/fn  " {:readers refuse} "#foo/bar 1")
  (try-read "plain/fn" {:readers refuse} "(+ 1 2)")
  ;; read-eval as an explicit parameter
  (try-read "readeval-default" {:readers refuse} "#=(* 2 21)")
  (try-read "readeval-explicit" {:readers refuse :read-eval (fn [_] (throw (ex-info "refused #=" {})))} "#=(* 2 21)")
  ;; cursor-based spans: line/column before and after
  (let [text "  ; c\n(def x [1 {:a 2}])\n42\n"
        r (sci/source-reader text)]
    (loop [n 0]
      (when (< n 3)
        (let [before [(sci/get-line-number r) (sci/get-column-number r)]
              [form src] (sci/parse-next+string ctx r {:eof ::eof})
              after [(sci/get-line-number r) (sci/get-column-number r)]]
          (when-not (= ::eof form)
            (println "span" (pr-str form) "before" before "after" after "buf" (pr-str src))
            (recur (inc n))))))))
