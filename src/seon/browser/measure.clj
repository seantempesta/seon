(ns seon.browser.measure
  "Pure pixel-exact DOM box measurement JS builders, off-screen snippet probes, multi-width
   Hiccup preview stage scripts, pixel-fit analyzer, and `:data-min-width` hint calibrator.
   Zero I/O, zero state, and zero `seon.*` dependencies so both `:browser` and renderers
   can consume it cleanly."
  (:require [clojure.data.json :as json]
            [clojure.string :as str]))

(def default-measure-selector
  "Default CSS selector covering rendered entity cards/chips and primary Seon layout chrome."
  "[data-chosen-fn], .surface-entity-item, #seon-view-unit, #seon-header, #seon-sidebar")

(defn- finite-double [x fallback]
  (if (number? x)
    (let [d (double x)]
      (if (and (== d d) (not (Double/isInfinite d))) d (double fallback)))
    (double fallback)))

(defn- safe-int [x fallback]
  (cond
    (integer? x) (long x)
    (number? x) (let [d (finite-double x fallback)]
                  (if (and (>= d -9.0e18) (<= d 9.0e18))
                    (long (Math/round d))
                    (long fallback)))
    (string? x) (if-let [m (re-find #"^-?\d+" (str/trim x))]
                  (try (Long/parseLong m) (catch NumberFormatException _ (long fallback)))
                  (long fallback))
    :else (long fallback)))

(defn- parse-px-hint [v]
  (cond
    (integer? v) (when (>= (long v) 0) (min 10000 (long v)))
    (number? v) (let [d (finite-double v -1.0)]
                  (when (>= d 0.0) (min 10000 (long (Math/round d)))))
    (string? v) (when-let [m (re-find #"^\d+" (str/trim v))]
                  (try (min 10000 (Long/parseLong m))
                       (catch NumberFormatException _ nil)))
    :else nil))

(defn- nonblank [x]
  (when (and (string? x) (not (str/blank? x)))
    (str/trim x)))

(defn- parse-renderer-sym [x]
  (cond
    (symbol? x) x
    (string? x)
    (let [s (str/trim x)]
      (when (and (not (str/blank? s))
                 (not= s "nil")
                 (not= s "null")
                 (not= s "undefined"))
        (if (str/includes? s "/")
          (let [[ns-s nm-s] (str/split s #"/" 2)]
            (when (and (not (str/blank? ns-s)) (not (str/blank? nm-s)))
              (symbol ns-s nm-s)))
          (symbol s))))
    :else nil))

(defn- lookup [m & ks]
  (when (map? m)
    (loop [xs ks]
      (when (seq xs)
        (let [k (first xs)]
          (cond
            (contains? m k) (get m k)
            (and (keyword? k) (contains? m (name k))) (get m (name k))
            :else (recur (rest xs))))))))

(defn- parse-raw-map [x]
  (cond
    (map? x) x
    (string? x)
    (let [s (str/trim x)]
      (if (str/blank? s)
        {:error "empty json measurement"}
        (try
          (let [v (json/read-str s :key-fn keyword)]
            (if (map? v) v {:error "json measurement is not an object"}))
          (catch Exception ex
            {:error (str "invalid json measurement: " (.getMessage ex))}))))
    (nil? x) {:error "nil measurement"}
    :else {:error (str "unsupported measurement type: " (type x))}))

(defn- normalize-theme [theme]
  (let [s (cond (keyword? theme) (name theme)
                (string? theme) (str/trim (str/lower-case theme))
                :else nil)]
    (when (#{"dark" "light"} s) s)))

(defn measure-boxes-js
  "Builds a safe, self-contained JS IIFE for `seon-browser` `eval_js` measuring viewport
   geometry and exact rendered boxes (`getBoundingClientRect`, scroll/client overflow,
   computed `overflowY`, `data-chosen-fn`, `data-entity`, `data-min-width`, and `area`)."
  {:malli/schema [:function
                  [:=> [:cat] :string]
                  [:=> [:cat :any] :string]]
   :cost/ticks 500000 :cost/bytes 65536}
  ([] (measure-boxes-js nil))
  ([selector-or-opts]
   (let [sel (cond
               (and (string? selector-or-opts) (not (str/blank? selector-or-opts)))
               (str/trim selector-or-opts)
               (and (map? selector-or-opts) (string? (:selector selector-or-opts))
                    (not (str/blank? (:selector selector-or-opts))))
               (str/trim (:selector selector-or-opts))
               :else default-measure-selector)
         max-els (max 1 (min 1000 (safe-int (when (map? selector-or-opts)
                                              (:max-elements selector-or-opts))
                                            250)))
         sel-js (json/write-str sel)]
     (str
      "(function(){try{"
      "var sel=" sel-js ",maxEls=" max-els ";"
      "var num=function(v){return (typeof v==='number'&&isFinite(v))?Math.round(v):0;};"
      "var de=document.documentElement,b=document.body;"
      "var vw=num(window.innerWidth),vh=num(window.innerHeight);"
      "var dsw=num(de?de.scrollWidth:(b?b.scrollWidth:0));"
      "var dsh=num(de?de.scrollHeight:(b?b.scrollHeight:0));"
      "var docOv=(vw>0&&dsw>vw+1);"
      "var list=Array.prototype.slice.call(document.querySelectorAll(sel),0,maxEls);"
      "var els=list.map(function(el){"
      "var r=el.getBoundingClientRect();"
      "var w=num(r.width),h=num(r.height);"
      "var area=Math.round(Math.max(0,(isFinite(r.width)?r.width:0))*Math.max(0,(isFinite(r.height)?r.height:0)));"
      "var sw=num(el.scrollWidth),sh=num(el.scrollHeight);"
      "var cw=num(el.clientWidth),ch=num(el.clientHeight);"
      "var cs=window.getComputedStyle?window.getComputedStyle(el):null;"
      "var oy=cs?String(cs.overflowY||''):'';"
      "var ox=cs?String(cs.overflowX||''):'';"
      "var scrY=(oy==='auto'||oy==='scroll'||oy==='overlay');"
      "var scrX=(ox==='auto'||ox==='scroll'||ox==='overlay');"
      "var hw=el.getAttribute('data-min-width');"
      "if((hw===null||hw==='')&&el.querySelector){var cwEl=el.querySelector('[data-min-width]');if(cwEl)hw=cwEl.getAttribute('data-min-width');}"
      "var hwNum=(hw!==null&&hw!==''&&/^\\s*\\d+/.test(String(hw)))?parseInt(String(hw),10):null;"
      "var id=el.id||null;"
      "var ent=el.getAttribute('data-entity')||null;"
      "var cfn=el.getAttribute('data-chosen-fn')||null;"
      "var cls=(typeof el.className==='string'&&el.className.trim())?el.className.trim().split(/\\s+/).slice(0,2).join('.'):'';"
      "var desc=id?('#'+id):(String(el.tagName||'el').toLowerCase()+(cls?'.'+cls:'')+(ent?'[data-entity='+ent+']':''));"
      "return {"
      "'selector':desc,'id':id,'entity':ent,'chosen-fn':cfn,'hint-min-w':hwNum,"
      "'x':num(r.left),'y':num(r.top),'w':w,'h':h,"
      "'scroll-w':sw,'scroll-h':sh,'client-w':cw,'client-h':ch,"
      "'overflow-x?':(sw>cw+1),'overflow-y?':(sh>ch+1),"
      "'scrollable-y?':scrY,'scrollable-x?':scrX,'area':area};});"
      "return {'viewport':{'innerWidth':vw,'innerHeight':vh,'docScrollW':dsw,'docScrollH':dsh,'overflowX':docOv},"
      "'elements':els};"
      "}catch(e){return {'error':String(e)};}})()"))))

(defn measure-html-snippet-js
  "Builds a safe off-screen hidden measurement probe JS snippet that mounts a temporary
   container of exact width `slot-w` inheriting page CSS inside `#seon-app`, measures exact
   rendered box dimensions & overflow, and removes the temporary node cleanly in `finally`."
  {:malli/schema [:=> [:cat [:maybe :string] pos-int?] :string]
   :cost/ticks 500000 :cost/bytes 65536}
  [html-str slot-w]
  (let [w (max 32 (min 10000 (safe-int slot-w 480)))
        html-js (json/write-str (str (or html-str "")))]
    (str
     "(function(){var wrap=null;try{"
     "var slotW=" w ",html=" html-js ";"
     "var num=function(v){return (typeof v==='number'&&isFinite(v))?Math.round(v):0;};"
     "var host=document.getElementById('seon-app')||document.body||document.documentElement;"
     "if(!host){return {'error':'no host element'};}"
     "wrap=document.createElement('div');"
     "wrap.setAttribute('data-seon-measure-probe','1');"
     "wrap.style.cssText='position:fixed;left:-20000px;top:0;visibility:hidden;pointer-events:none;'"
     "+'box-sizing:border-box;width:'+slotW+'px;max-width:'+slotW+'px;min-width:0;'"
     "+'overflow:visible;contain:layout style;z-index:-1;';"
     "wrap.innerHTML=html;"
     "host.appendChild(wrap);"
     "void wrap.offsetHeight;"
     "var t=(wrap.children&&wrap.children.length===1)?wrap.firstElementChild:wrap;"
     "var r=t.getBoundingClientRect();"
     "var rw=num(r.width),rh=num(r.height);"
     "var wsw=num(wrap.scrollWidth),wsh=num(wrap.scrollHeight);"
     "var tsw=num(t.scrollWidth),tsh=num(t.scrollHeight);"
     "var tcw=num(t.clientWidth),tch=num(t.clientHeight);"
     "var wrapOvX=(wsw>slotW+1);"
     "var sw=wrapOvX?Math.max(tsw,wsw,rw):Math.max(tsw,rw);"
     "var sh=Math.max(tsh,wsh,rh);"
     "var cw=(tcw>0)?tcw:rw,ch=(tch>0)?tch:rh;"
     "var ovX=(wrapOvX||(rw>slotW+1)||(tcw>0&&tsw>tcw+1));"
     "var ovY=(tch>0&&tsh>tch+1);"
     "var hw=t.getAttribute?t.getAttribute('data-min-width'):null;"
     "var hwNum=(hw!==null&&hw!==''&&/^\\s*\\d+/.test(String(hw)))?parseInt(String(hw),10):null;"
     "var area=Math.round(Math.max(0,(isFinite(r.width)?r.width:0))*Math.max(0,(isFinite(r.height)?r.height:0)));"
     "return {'slot-w':slotW,'w':rw,'h':rh,'area':area,'scroll-w':sw,'scroll-h':sh,"
     "'client-w':cw,'client-h':ch,'overflow-x?':ovX,'overflow-y?':ovY,'hint-min-w':hwNum};"
     "}catch(e){return {'error':String(e)};}"
     "finally{try{if(wrap&&wrap.parentNode){wrap.parentNode.removeChild(wrap);}}catch(_){}}})()")))

(defn stage-hiccup-js
  "Builds a safe JS snippet that mounts a full-viewport clean preview stage `#seon-preview-stage`
   at the top of `document.body` (inheriting `#seon-css`, sized to `width`, optional dark/light
   `data-theme`) and returns exact rendered `{:w :h :scroll-w :scroll-h :overflow-x? :overflow-y?}`."
  {:malli/schema [:function
                  [:=> [:cat [:maybe :string] pos-int?] :string]
                  [:=> [:cat [:maybe :string] pos-int? [:maybe :any]] :string]]
   :cost/ticks 500000 :cost/bytes 65536}
  ([html-str width] (stage-hiccup-js html-str width nil))
  ([html-str width theme]
   (let [w (max 120 (min 4096 (safe-int width 640)))
         html-js (json/write-str (str (or html-str "")))
         t (normalize-theme theme)
         theme-js (if t (json/write-str t) "null")]
     (str
      "(function(){try{"
      "var stageW=" w ",html=" html-js ",theme=" theme-js ";"
      "var num=function(v){return (typeof v==='number'&&isFinite(v))?Math.round(v):0;};"
      "var b=document.body||document.documentElement;"
      "if(!b){return {'error':'no document body'};}"
      "var old=document.getElementById('seon-preview-stage');"
      "if(old&&old.parentNode){old.parentNode.removeChild(old);}"
      "var st=document.createElement('div');"
      "st.id='seon-preview-stage';"
      "if(theme){st.setAttribute('data-theme',theme);}"
      "st.style.cssText='position:fixed;inset:0;z-index:2147483647;'"
      "+'background:var(--base-950,#0d0d0c);color:var(--text-200,#d4d0c8);'"
      "+'overflow:auto;margin:0;padding:0;box-sizing:border-box;';"
      "var fr=document.createElement('div');"
      "fr.id='seon-preview-frame';"
      "fr.style.cssText='width:'+stageW+'px;max-width:'+stageW+'px;min-width:0;'"
      "+'box-sizing:border-box;margin:0;padding:0;';"
      "fr.innerHTML=html;"
      "st.appendChild(fr);"
      "if(b.firstChild){b.insertBefore(st,b.firstChild);}else{b.appendChild(st);}"
      "void fr.offsetHeight;"
      "var t=(fr.children&&fr.children.length===1)?fr.firstElementChild:fr;"
      "var r=t.getBoundingClientRect();"
      "var rw=num(r.width),rh=num(r.height);"
      "var fsw=num(fr.scrollWidth),fsh=num(fr.scrollHeight);"
      "var tsw=num(t.scrollWidth),tsh=num(t.scrollHeight);"
      "var tcw=num(t.clientWidth),tch=num(t.clientHeight);"
      "var frOvX=(fsw>stageW+1);"
      "var sw=frOvX?Math.max(tsw,fsw,rw):Math.max(tsw,rw);"
      "var sh=Math.max(tsh,fsh,rh);"
      "var ovX=(frOvX||(rw>stageW+1)||(tcw>0&&tsw>tcw+1));"
      "var ovY=(tch>0&&tsh>tch+1);"
      "var area=Math.round(Math.max(0,(isFinite(r.width)?r.width:0))*Math.max(0,(isFinite(r.height)?r.height:0)));"
      "return {'slot-w':stageW,'w':rw,'h':rh,'area':area,'scroll-w':sw,'scroll-h':sh,"
      "'client-w':(tcw>0?tcw:rw),'client-h':(tch>0?tch:rh),'overflow-x?':ovX,'overflow-y?':ovY};"
      "}catch(e){return {'error':String(e)};}})()"))))

(defn clear-stage-js
  "Builds a safe JS snippet that removes `#seon-preview-stage` cleanly."
  {:malli/schema [:=> [:cat] :string]
   :cost/ticks 50000 :cost/bytes 4096}
  []
  (str "(function(){try{"
       "var el=document.getElementById('seon-preview-stage');"
       "var had=!!el;"
       "if(el&&el.parentNode){el.parentNode.removeChild(el);}"
       "return {'cleared':true,'existed':had};"
       "}catch(e){return {'cleared':false,'error':String(e)};}})()"))

(defn normalize-snippet-measurement
  "Normalizes raw `eval_js` output from `measure-html-snippet-js` or `stage-hiccup-js`
   into `{:ok? :w :h :area :scroll-w :scroll-h :overflow-x? ...}`."
  {:malli/schema [:=> [:cat :any]
                  [:map [:ok? :boolean] [:w :int] [:h :int] [:area :int]
                   [:scroll-w :int] [:scroll-h :int] [:overflow-x? :boolean]]]
   :cost/ticks 500000 :cost/bytes 32768}
  [raw-value]
  (let [m (parse-raw-map raw-value)
        err (lookup m :error)]
    (if err
      {:ok? false :error (str err) :w 0 :h 0 :area 0
       :scroll-w 0 :scroll-h 0 :overflow-x? false}
      (let [w (max 0 (safe-int (lookup m :w :width) 0))
            h (max 0 (safe-int (lookup m :h :height) 0))
            sw (max 0 (safe-int (lookup m :scroll-w :scrollW :scrollWidth) w))
            sh (max 0 (safe-int (lookup m :scroll-h :scrollH :scrollHeight) h))
            cw (max 0 (safe-int (lookup m :client-w :clientW :clientWidth) w))
            ch (max 0 (safe-int (lookup m :client-h :clientH :clientHeight) h))
            slot-w (max 0 (safe-int (lookup m :slot-w :slotW) cw))
            area (max 0 (safe-int (lookup m :area) (* w h)))
            raw-ovx (lookup m :overflow-x? :overflowX)
            ov-x? (if (boolean? raw-ovx) raw-ovx
                      (or (and (pos? slot-w) (> sw (inc slot-w)))
                          (and (pos? cw) (> sw (inc cw)))))
            raw-ovy (lookup m :overflow-y? :overflowY)
            ov-y? (if (boolean? raw-ovy) raw-ovy
                      (and (pos? ch) (> sh (inc ch))))
            hint (parse-px-hint (lookup m :hint-min-w :hintMinW))]
        (cond-> {:ok? true :w w :h h :area area
                 :scroll-w sw :scroll-h sh :client-w cw :client-h ch
                 :slot-w slot-w :overflow-x? ov-x? :overflow-y? ov-y?}
          (some? hint) (assoc :hint-min-w hint))))))

(defn normalize-image-frame
  "Builds a canonical `:seon.media/image` map with `{:seon/schema :seon.media/image}` metadata."
  {:malli/schema [:=> [:cat [:maybe :map] [:maybe :string] [:maybe :int] [:maybe :map]] :map]
   :cost/ticks 200000 :cost/bytes 32768}
  [snap label width dom]
  (let [s (if (map? snap) snap {})
        d (cond (map? dom) dom (map? (:dom s)) (:dom s) :else {})
        w (max 0 (safe-int (or width (:width s) (:w d)) 0))
        h (max 0 (safe-int (or (:height s) (:h d)) 0))
        mime (or (nonblank (:mime s)) "image/jpeg")
        b64 (str (or (:base64 s) ""))
        lbl (or (nonblank label) (str w "px"))]
    (with-meta
      {:seon.media/mime mime
       :seon.media/base64 b64
       :seon.media/width w
       :seon.media/height h
       :seon.media/label lbl
       :seon.media/dom d}
      {:seon/schema :seon.media/image})))

(defn- normalize-viewport [raw]
  (let [vp (if (map? (lookup raw :viewport)) (lookup raw :viewport) raw)
        w (max 0 (safe-int (lookup vp :innerWidth :w :width :inner-width :viewportW) 0))
        h (max 0 (safe-int (lookup vp :innerHeight :h :height :inner-height :viewportH) 0))
        dsw (max 0 (safe-int (lookup vp :docScrollW :doc-scroll-w :scrollW :scrollWidth) w))
        dsh (max 0 (safe-int (lookup vp :docScrollH :doc-scroll-h :scrollH :scrollHeight) h))
        raw-ov (lookup vp :overflowX :overflow-x? :doc-overflow-x)
        ov? (if (boolean? raw-ov) raw-ov (and (pos? w) (> dsw (inc w))))]
    {:w w :h h :doc-scroll-w dsw :doc-scroll-h dsh :overflow-x? ov?}))

(defn- normalize-element [el]
  (when (map? el)
    (let [x (safe-int (lookup el :x :left) 0)
          y (safe-int (lookup el :y :top) 0)
          w (max 0 (safe-int (lookup el :w :width) 0))
          h (max 0 (safe-int (lookup el :h :height) 0))
          sw (max 0 (safe-int (lookup el :scroll-w :scrollW :scrollWidth) w))
          sh (max 0 (safe-int (lookup el :scroll-h :scrollH :scrollHeight) h))
          cw (max 0 (safe-int (lookup el :client-w :clientW :clientWidth) w))
          ch (max 0 (safe-int (lookup el :client-h :clientH :clientHeight) h))
          area (max 0 (safe-int (lookup el :area) (* w h)))
          raw-ovx (lookup el :overflow-x? :overflowX)
          ov-x? (if (boolean? raw-ovx) raw-ovx (and (pos? cw) (> sw (inc cw))))
          raw-ovy (lookup el :overflow-y? :overflowY)
          ov-y? (if (boolean? raw-ovy) raw-ovy (and (pos? ch) (> sh (inc ch))))
          scr-y? (boolean (lookup el :scrollable-y? :scrollableY))
          scr-x? (boolean (lookup el :scrollable-x? :scrollableX))
          id (nonblank (lookup el :id))
          ent (let [v (lookup el :entity :data-entity)]
                (when (some? v) (nonblank (str v))))
          cfn (parse-renderer-sym (lookup el :chosen-fn :chosenFn :data-chosen-fn))
          hint (parse-px-hint (lookup el :hint-min-w :hintMinW :data-min-width))
          sel (or (nonblank (lookup el :selector))
                  (when id (str "#" id))
                  (when cfn (str "[data-chosen-fn=" cfn "]"))
                  "element")]
      {:selector sel :id id :entity ent :chosen-fn cfn :hint-min-w hint
       :x x :y y :w w :h h
       :scroll-w sw :scroll-h sh :client-w cw :client-h ch :area area
       :overflow-x? ov-x? :overflow-y? ov-y?
       :scrollable-y? scr-y? :scrollable-x? scr-x?})))

(defn- summarize-renderer-group [els]
  (let [cnt (count els)
        max-w (reduce max 0 (map :w els))
        max-h (reduce max 0 (map :h els))
        max-area (reduce max 0 (map :area els))
        any-ov-x? (boolean (some :overflow-x? els))
        max-needed-w (reduce max 0 (map #(if (:overflow-x? %) (max (:w %) (:scroll-w %)) (:w %)) els))
        hints (keep :hint-min-w els)
        hint-min-w (when (seq hints) (reduce max hints))
        suggested (long (cond
                          any-ov-x? (max max-needed-w (long (or hint-min-w 0)))
                          (and hint-min-w (pos? max-w) (< max-w (long hint-min-w))) max-w
                          (and hint-min-w (pos? (long hint-min-w))) (long hint-min-w)
                          :else max-needed-w))]
    {:count cnt
     :max-w max-w
     :max-h max-h
     :max-area max-area
     :any-overflow-x? any-ov-x?
     :hint-min-w hint-min-w
     :suggested-min-w suggested}))

(defn analyze-pixel-fit
  "Pure analyzer over raw `measure-boxes-js` output. Compares static `:data-min-width` hints
   against measured WKWebView boxes, separating unintentional clipping from intentional scrolling."
  {:malli/schema [:=> [:cat :any]
                  [:map
                   [:ok? :boolean]
                   [:viewport [:map [:w :int] [:h :int] [:doc-scroll-w :int] [:doc-scroll-h :int] [:overflow-x? :boolean]]]
                   [:doc-overflow-x :boolean]
                   [:clipped-items [:vector :map]]
                   [:scrollable-items [:vector :map]]
                   [:by-renderer [:map-of :symbol [:map [:count :int] [:max-w :int] [:max-h :int] [:max-area :int]
                                                   [:any-overflow-x? :boolean] [:hint-min-w [:maybe :int]]
                                                   [:suggested-min-w :int]]]]
                   [:summary :string]]]
   :cost/ticks 5000000 :cost/bytes 524288}
  [raw-measurement]
  (let [raw (parse-raw-map raw-measurement)
        err (lookup raw :error)]
    (if err
      {:ok? false
       :error (str err)
       :viewport {:w 0 :h 0 :doc-scroll-w 0 :doc-scroll-h 0 :overflow-x? false}
       :doc-overflow-x false
       :clipped-items []
       :scrollable-items []
       :by-renderer {}
       :element-count 0
       :elements []
       :summary (str "Measurement error: " err)}
      (let [vp (normalize-viewport raw)
            raw-els (let [xs (or (lookup raw :elements :items) [])]
                      (if (sequential? xs) xs []))
            els (vec (keep normalize-element raw-els))
            clipped (filterv #(or (:overflow-x? %)
                                  (and (:overflow-y? %) (not (:scrollable-y? %))))
                             els)
            scrollable (filterv #(and (:overflow-y? %) (:scrollable-y? %)) els)
            by-r (reduce-kv (fn [acc rsym grp]
                              (assoc acc rsym (summarize-renderer-group grp)))
                            {}
                            (group-by :chosen-fn (filter :chosen-fn els)))
            doc-ov? (boolean (:overflow-x? vp))
            ok? (and (not doc-ov?) (empty? clipped))
            summary (str (:w vp) "x" (:h vp) " " (if ok? "ok" "OVERFLOW")
                         (when doc-ov? (str " · doc-scroll-w=" (:doc-scroll-w vp)))
                         " · " (count els) " boxes"
                         " · " (count clipped) " clipped"
                         " · " (count scrollable) " scrollable"
                         " · " (count by-r) " renderers")]
        {:ok? ok?
         :viewport vp
         :doc-overflow-x doc-ov?
         :clipped-items clipped
         :scrollable-items scrollable
         :by-renderer by-r
         :element-count (count els)
         :elements els
         :summary summary}))))

(defn calibrate-hints
  "Produces per-renderer pixel calibration recommendations `{renderer-sym {:measured-w :measured-h
   :measured-area :hint-min-w :suggested-min-w :delta-w :overflow-x?}}` from `analyze-pixel-fit`."
  {:malli/schema [:=> [:cat [:maybe :map]]
                  [:map-of :symbol [:map [:measured-w :int] [:measured-h :int] [:measured-area :int]
                                    [:hint-min-w [:maybe :int]] [:suggested-min-w :int]
                                    [:delta-w :int] [:overflow-x? :boolean]]]]
   :cost/ticks 2000000 :cost/bytes 131072}
  [analysis]
  (let [by-r (when (and (map? analysis) (map? (:by-renderer analysis)))
               (:by-renderer analysis))]
    (reduce-kv
     (fn [acc rsym stats]
       (if (and (symbol? rsym) (map? stats))
         (let [mw (max 0 (safe-int (:max-w stats) 0))
               mh (max 0 (safe-int (:max-h stats) 0))
               ma (max 0 (safe-int (:max-area stats) (* mw mh)))
               hint (parse-px-hint (:hint-min-w stats))
               ov? (boolean (:any-overflow-x? stats))
               sug (max 0 (safe-int (:suggested-min-w stats)
                                    (if (and (not ov?) hint (pos? hint) (>= mw hint)) hint mw)))
               delta (- sug (long (or hint 0)))]
           (assoc acc rsym
                  {:measured-w mw
                   :measured-h mh
                   :measured-area ma
                   :hint-min-w hint
                   :suggested-min-w sug
                   :delta-w delta
                   :overflow-x? ov?}))
         acc))
     {}
     (or by-r {}))))
