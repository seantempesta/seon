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

(defn- theme-var-css [theme]
  (case theme
    "dark"
    "color-scheme:dark;--base-950:#0d0d0c;--base-900:#1a1918;--base-850:#252422;--base-800:#302e2b;--base-700:#3d3a36;--text-50:#faf9f7;--text-100:#ece9e3;--text-200:#d4d0c8;--text-300:#b0aa9e;--text-400:#8a8478;--text-500:#6b665d;--signal:#f0b429;--signal-fg:#0d0d0c;--success:#34d399;--error:#f87171;--warning:#fbbf24;--info:#60a5fa;--eval:#c084fc;"
    "light"
    "color-scheme:light;--base-950:#f7f4ee;--base-900:#fffdf9;--base-850:#f1ece3;--base-800:#e4ddd1;--base-700:#d3cabb;--text-50:#17140f;--text-100:#221e18;--text-200:#3a352d;--text-300:#5c564b;--text-400:#7d766a;--text-500:#a29a8c;--signal:#b7791f;--signal-fg:#fffdf9;--success:#0f8a5f;--error:#c53030;--warning:#b7791f;--info:#2563eb;--eval:#7c3aed;"
    ""))

(def ^:private visual-audit-js-body
  (str
   "var runVisualAudit=function(root,o){\n"
   "var defs=[],dk=!!o.dark,G=window.getComputedStyle,sq=String.fromCharCode(39),M=Math,"
   "Q=function(r,s,n){return[].slice.call(r.querySelectorAll(s),0,n||999);},"
   "T=function(e){return String(e&&e.tagName||'').toLowerCase();},"
   "S=function(v){return String(v||'').trim();},"
   "R=function(e){return e.getBoundingClientRect();},"
   "D=function(e){if(!e)return 'el';if(e.id)return '#'+e.id;var c=typeof e.className==='string'&&S(e.className)?'.'+S(e.className).split(/\\s+/).slice(0,2).join('.'):'';return T(e)+c;},"
   "P=function(s){s=S(s).toLowerCase();if(!s||s==='none'||s==='transparent')return null;if(s==='white')return[255,255,255,1];if(s==='black')return[0,0,0,1];if(s.indexOf('rgb')===0){var m=s.match(/[\\d.]+/g);if(m&&m.length>=3)return[+m[0],+m[1],+m[2],m[3]===undefined?1:+m[3]];}if(s[0]==='#'){var h=s.slice(1);if(h.length===3)h=h[0]+h[0]+h[1]+h[1]+h[2]+h[2];if(h.length===6){var n=parseInt(h,16);if(!isNaN(n))return[(n>>16)&255,(n>>8)&255,n&255,1];}}return null;},"
   "W=function(c){return !!(c&&c[0]>=245&&c[1]>=245&&c[2]>=245&&c[3]>=0.85);},"
   "L=function(c){var f=function(v){v/=255;return v<=0.03928?v/12.92:M.pow((v+0.055)/1.055,2.4);};return 0.2126*f(c[0])+0.7152*f(c[1])+0.0722*f(c[2]);},"
   "CR=function(a,b){var x=L(a),y=L(b);return(M.max(x,y)+0.05)/(M.min(x,y)+0.05);},"
   "BL=function(f,b){var a=f[3];return a>=0.99?f:[f[0]*a+b[0]*(1-a),f[1]*a+b[1]*(1-a),f[2]*a+b[2]*(1-a),1];},"
   "BG=function(e){var fb=dk?[13,13,12,1]:[247,244,238,1];if(T(e)==='text'&&e.ownerSVGElement){var er=R(e),cx=(er.left+er.right)/2,cy=(er.top+er.bottom)/2,rs=e.ownerSVGElement.querySelectorAll('rect');for(var j=rs.length-1;j>=0;j--){var rr=R(rs[j]);if(cx>=rr.left&&cx<=rr.right&&cy>=rr.top&&cy<=rr.bottom&&rr.width>=20&&rr.height>=12){var rf=P(G?G(rs[j]).fill:rs[j].getAttribute('fill'));if(rf&&rf[3]>=0.85)return rf;}}}for(var u=e;u&&u.nodeType===1;u=u.parentElement){var bg=P(G?G(u).backgroundColor:null);if(bg&&bg[3]>=0.85)return bg;}return fb;},"
   "reR=/^-?\\d+\\/\\d+$/,reIR=/(?:^|[\\s,])-?\\d+\\/\\d+/,reNI=/\\b(NaN|Infinity)\\b/i,nA='cx cy x y x1 y1 x2 y2 width height r rx ry'.split(' ');\n"
   "Q(root,'svg',20).forEach(function(svg,idx){var sr=R(svg);if(sr.width<40||sr.height<40)return;var badA=0,lc=0,zb=0,nb=0,cp=0,wc=false,tx=[];\n"
   "if(dk&&sr.width>=120&&sr.height>=60&&W(P(G?G(svg).backgroundColor:null)))wc=true;\n"
   "Q(svg,'circle,rect,line,polyline,polygon,path,ellipse,text').forEach(function(p){if(p.closest&&p.closest('defs,clipPath,marker'))return;var pt=T(p);\n"
   "nA.forEach(function(a){var v=p.getAttribute(a);if(v!==null){v=S(v);if(reR.test(v)||reNI.test(v))badA++;}});\n"
   "if(pt==='path'){var dv=S(p.getAttribute('d'));if(!dv||reIR.test(dv)||reNI.test(dv))badA++;}\n"
   "if(pt==='polyline'||pt==='polygon'){var pv=S(p.getAttribute('points'));if(reIR.test(pv)||reNI.test(pv))badA++;}\n"
   "var pr=R(p),bgR=(pt==='rect'&&pr.width>=sr.width*0.85&&pr.height>=sr.height*0.85);\n"
   "if(!bgR)nb++;if(pt!=='text'&&pr.width===0&&pr.height===0)zb++;\n"
   "if((pt==='circle'||pt==='rect')&&!bgR&&sr.width>=100&&pr.left<=sr.left+6&&(pt==='circle'||pr.width<sr.width*0.5))lc++;\n"
   "if(dk&&pt==='rect'&&pr.width>=120&&pr.height>=60&&W(P(G?G(p).fill:null)||P(p.getAttribute('fill'))))wc=true;\n"
   "if((pr.width>0||pr.height>0)&&(pr.left<sr.left-3||pr.right>sr.right+3||pr.top<sr.top-3||pr.bottom>sr.bottom+3))cp++;\n"
   "if(pt==='text'&&tx.length<40){var ts=S(p.textContent);if(ts&&pr.width>1&&pr.height>1)tx.push({t:ts,r:pr});}});\n"
   "var sp='SVG #'+(idx+1);"
   "if(badA>0)defs.push(sp+' has '+badA+' invalid numeric/path attr(s)');"
   "if(sr.width>=60&&sr.height>=60&&nb===0)defs.push(sp+' is empty ('+num(sr.width)+'x'+num(sr.height)+'px with 0 primitives)');"
   "if(lc>=3)defs.push(sp+' has '+lc+' shapes collapsed on left edge');"
   "if(zb>=3)defs.push(sp+' has '+zb+' collapsed 0x0 primitive(s)');"
   "if(cp>0)defs.push(sp+' has '+cp+' element(s) clipped outside viewBox');"
   "if(wc)defs.push(sp+' paints bright white card in dark theme');\n"
   "var ov=0,pA='',pB='';for(var a=0;a<tx.length;a++)for(var b=a+1;b<tx.length;b++){var ra=tx[a].r,rb=tx[b].r,ix=M.max(0,M.min(ra.right-1,rb.right-1)-M.max(ra.left+1,rb.left+1)),iy=M.max(0,M.min(ra.bottom-1,rb.bottom-1)-M.max(ra.top+1,rb.top+1)),ma=M.min(ra.width*ra.height,rb.width*rb.height);if(ma>0&&ix*iy>ma*0.2){ov++;if(!pA){pA=tx[a].t.slice(0,18);pB=tx[b].t.slice(0,18);}}}\n"
   "if(ov>0)defs.push(sp+' has '+ov+' overlapping <text> label pair(s) (e.g. '+sq+pA+sq+' / '+sq+pB+sq+')');});\n"
   "if(dk){var wc2=0;Q(root,'div,section,article,table,pre,card',80).forEach(function(e){if(wc2>=3)return;var r=R(e);if(r.width>=140&&r.height>=60&&W(P(G?G(e).backgroundColor:null))){wc2++;defs.push('Element '+D(e)+' paints bright white surface in dark theme');}});}\n"
   "var tc=0,lc2=0;Q(root,'h1,h2,h3,p,span,td,th,a,button,text',180).forEach(function(e){if(tc>=60||lc2>=3)return;var tg=T(e),txt='';\n"
   "if(tg==='text'){txt=S(e.textContent);}else{for(var n=e.firstChild;n;n=n.nextSibling)if(n.nodeType===3)txt+=n.nodeValue;txt=S(txt);}\n"
   "if(!txt||!/[A-Za-z0-9]/.test(txt))return;var tr=R(e);if(tr.width<4||tr.height<4)return;\n"
   "var cs=G?G(e):null;if(!cs||cs.display==='none'||cs.visibility==='hidden'||parseFloat(cs.opacity)<0.5||parseFloat(cs.fontSize)<10)return;\n"
   "tc++;var fg=P(tg==='text'?(cs.fill||cs.color):cs.color);if(!fg||fg[3]<=0.05)return;var bg=BG(e),cr=CR(BL(fg,bg),bg);\n"
   "var hd=/^(h[123]|text)$/.test(tg),mu=/\\b(muted|tiny|link-muted|tree-exp-spacer|crumb-sep)\\b/.test(S(e.className))||!!(e.closest&&e.closest('.muted,.prev-peek'));\n"
   "if(cr<(hd?3.0:(mu?2.5:2.8))){lc2++;defs.push('Low contrast ('+cr.toFixed(1)+':1) on '+D(e)+' ('+sq+txt.slice(0,22)+sq+')');}});\n"
   "if(o.fullPage){if(!document.getElementById('seon-app'))defs.push('Missing #seon-app root (page may still be booting)');"
   "if(o.docOv)defs.push('Document horizontal overflow ('+o.dsw+'px > '+o.vw+'px)');"
   "var bl=document.querySelector('#seon-header .brand-link');if(bl&&o.vw>=760){var br=R(bl);if(br.width<44||bl.scrollWidth>bl.clientWidth+1)defs.push('Header brand clipped ('+num(br.width)+'px)');}"
   "var lp=document.getElementById('cv-live-prose');if(lp&&lp.scrollHeight>lp.clientHeight+4)defs.push('Streaming prose clipped ('+lp.clientHeight+'px < '+lp.scrollHeight+'px)');"
   "Q(document,'.root-canvas,.canvas-surface,.status-live-card',12).forEach(function(e){var cc=G?G(e):null,r=R(e);if(cc&&cc.display!=='none'&&r.height===0&&(S(e.textContent)||e.querySelector('svg,img')))defs.push('Crushed zero-height '+D(e));});"
   "}else{if(o.ovX)defs.push('Horizontal overflow at '+o.stageW+'px (scrollWidth='+o.sw+'px)');"
   "if(o.htmlLen>0&&(o.rw===0||o.rh===0))defs.push('Zero-size rendered root ('+o.rw+'x'+o.rh+'px)');"
   "var cc2=0;Q(root,'h1,h2,h3,.card,.canvas-surface,.status-live-card',20).forEach(function(e){if(cc2>=2)return;var r=R(e),cc=G?G(e):null;if(r.width>0&&e.clientWidth>0&&e.scrollWidth>e.clientWidth+4&&(!cc||(cc.overflowX!=='auto'&&cc.overflowX!=='scroll'))){cc2++;defs.push('Clipped '+D(e)+' ('+e.scrollWidth+'px > '+e.clientWidth+'px)');}});"
   "}return defs;};\n"))

(defn measure-boxes-js
  "Builds a safe JS IIFE for `seon-browser` measuring viewport geometry, rendered boxes, and unified visual quality defects."
  {:malli/schema [:function [:=> [:cat] :string] [:=> [:cat :any] :string]]
   :cost/ticks 500000 :cost/bytes 65536}
  ([] (measure-boxes-js nil))
  ([selector-or-opts]
   (let [sel (cond
               (and (string? selector-or-opts) (not (str/blank? selector-or-opts))) (str/trim selector-or-opts)
               (and (map? selector-or-opts) (string? (:selector selector-or-opts)) (not (str/blank? (:selector selector-or-opts)))) (str/trim (:selector selector-or-opts))
               :else default-measure-selector)
         max-els (max 1 (min 1000 (safe-int (when (map? selector-or-opts) (:max-elements selector-or-opts)) 250)))]
     (str
      "(function(){try{var sel=" (json/write-str sel) ",maxEls=" max-els ";"
      "var num=function(v){return(typeof v==='number'&&isFinite(v))?Math.round(v):0;};"
      "var de=document.documentElement,b=document.body,vw=num(window.innerWidth),vh=num(window.innerHeight);"
      "var dsw=num(de?de.scrollWidth:(b?b.scrollWidth:0)),dsh=num(de?de.scrollHeight:(b?b.scrollHeight:0)),docOv=(vw>0&&dsw>vw+1);"
      "var els=[].slice.call(document.querySelectorAll(sel),0,maxEls).map(function(el){var r=el.getBoundingClientRect(),w=num(r.width),h=num(r.height),"
      "area=Math.round(Math.max(0,(isFinite(r.width)?r.width:0))*Math.max(0,(isFinite(r.height)?r.height:0))),"
      "sw=num(el.scrollWidth),sh=num(el.scrollHeight),cw=num(el.clientWidth),ch=num(el.clientHeight),cs=window.getComputedStyle?window.getComputedStyle(el):null,"
      "oy=cs?String(cs.overflowY||''):'',ox=cs?String(cs.overflowX||''):'',hw=el.getAttribute('data-min-width');"
      "if((hw===null||hw==='')&&el.querySelector){var c=el.querySelector('[data-min-width]');if(c)hw=c.getAttribute('data-min-width');}"
      "var hwNum=(hw!==null&&hw!==''&&/^\\s*\\d+/.test(String(hw)))?parseInt(String(hw),10):null,id=el.id||null,ent=el.getAttribute('data-entity')||null,"
      "cfn=el.getAttribute('data-chosen-fn')||null,cls=(typeof el.className==='string'&&el.className.trim())?el.className.trim().split(/\\s+/).slice(0,2).join('.'):'',"
      "desc=id?('#'+id):(String(el.tagName||'el').toLowerCase()+(cls?'.'+cls:'')+(ent?'[data-entity='+ent+']':''));"
      "return{'selector':desc,'id':id,'entity':ent,'chosen-fn':cfn,'hint-min-w':hwNum,'x':num(r.left),'y':num(r.top),'w':w,'h':h,'scroll-w':sw,'scroll-h':sh,'client-w':cw,'client-h':ch,"
      "'overflow-x?':(sw>cw+1),'overflow-y?':(sh>ch+1),'scrollable-y?':(oy==='auto'||oy==='scroll'||oy==='overlay'),'scrollable-x?':(ox==='auto'||ox==='scroll'||ox==='overlay'),'area':area};});"
      "var app=document.getElementById('seon-app'),dark=!(app&&app.getAttribute('data-theme')==='light')&&(!de||de.getAttribute('data-theme')!=='light');"
      visual-audit-js-body
      "var defs=runVisualAudit(document,{fullPage:true,dark:dark,vw:vw,dsw:dsw,docOv:docOv});"
      "return{'viewport':{'innerWidth':vw,'innerHeight':vh,'docScrollW':dsw,'docScrollH':dsh,'overflowX':docOv},'elements':els,'visual-defects':defs};"
      "}catch(e){return{'error':String(e)};}})()"))))

(defn measure-html-snippet-js
  "Builds an off-screen hidden measurement probe JS snippet inside `#seon-app` with `finally` cleanup."
  {:malli/schema [:=> [:cat [:maybe :string] pos-int?] :string]
   :cost/ticks 500000 :cost/bytes 65536}
  [html-str slot-w]
  (let [w (max 32 (min 10000 (safe-int slot-w 480)))]
    (str
     "(function(){var wrap=null;try{var slotW=" w ",html=" (json/write-str (str (or html-str ""))) ";"
     "var num=function(v){return(typeof v==='number'&&isFinite(v))?Math.round(v):0;};"
     "var host=document.getElementById('seon-app')||document.body||document.documentElement;if(!host)return{'error':'no host element'};"
     "wrap=document.createElement('div');wrap.setAttribute('data-seon-measure-probe','1');"
     "wrap.style.cssText='position:fixed;left:-20000px;top:0;visibility:hidden;pointer-events:none;box-sizing:border-box;width:'+slotW+'px;max-width:'+slotW+'px;min-width:0;overflow:visible;contain:layout style;z-index:-1;';"
     "wrap.innerHTML=html;host.appendChild(wrap);void wrap.offsetHeight;"
     "var t=(wrap.children&&wrap.children.length===1)?wrap.firstElementChild:wrap,r=t.getBoundingClientRect(),rw=num(r.width),rh=num(r.height),"
     "wsw=num(wrap.scrollWidth),wsh=num(wrap.scrollHeight),tsw=num(t.scrollWidth),tsh=num(t.scrollHeight),tcw=num(t.clientWidth),tch=num(t.clientHeight),"
     "wrapOvX=(wsw>slotW+1),sw=wrapOvX?Math.max(tsw,wsw,rw):Math.max(tsw,rw),sh=Math.max(tsh,wsh,rh),cw=(tcw>0)?tcw:rw,ch=(tch>0)?tch:rh,"
     "ovX=(wrapOvX||(rw>slotW+1)||(tcw>0&&tsw>tcw+1)),ovY=(tch>0&&tsh>tch+1),hw=t.getAttribute?t.getAttribute('data-min-width'):null,"
     "hwNum=(hw!==null&&hw!==''&&/^\\s*\\d+/.test(String(hw)))?parseInt(String(hw),10):null,"
     "area=Math.round(Math.max(0,(isFinite(r.width)?r.width:0))*Math.max(0,(isFinite(r.height)?r.height:0)));"
     "return{'slot-w':slotW,'w':rw,'h':rh,'area':area,'scroll-w':sw,'scroll-h':sh,'client-w':cw,'client-h':ch,'overflow-x?':ovX,'overflow-y?':ovY,'hint-min-w':hwNum};"
     "}catch(e){return{'error':String(e)};}finally{try{if(wrap&&wrap.parentNode)wrap.parentNode.removeChild(wrap);}catch(_){}}})()")))

(defn stage-hiccup-js
  "Builds a safe JS snippet that mounts `#seon-preview-stage` at `width` (optional dark/light `data-theme`), runs unified `runVisualAudit`, and returns geometry + `:visual-defects`."
  {:malli/schema [:function [:=> [:cat [:maybe :string] pos-int?] :string] [:=> [:cat [:maybe :string] pos-int? [:maybe :any]] :string]]
   :cost/ticks 500000 :cost/bytes 65536}
  ([html-str width] (stage-hiccup-js html-str width nil))
  ([html-str width theme]
   (let [w (max 120 (min 4096 (safe-int width 640)))
         t (normalize-theme theme)]
     (str
      "(function(){try{window.__seonPreviewBusy=true;var stageW=" w ",html=" (json/write-str (str (or html-str ""))) ",theme=" (if t (json/write-str t) "null") ";"
      "var num=function(v){return(typeof v==='number'&&isFinite(v))?Math.round(v):0;},b=document.body||document.documentElement;if(!b)return{'error':'no document body'};"
      "var old=document.getElementById('seon-preview-stage');if(old&&old.parentNode)old.parentNode.removeChild(old);"
      "var st=document.createElement('div');st.id='seon-preview-stage';if(theme)st.setAttribute('data-theme',theme);"
      "st.style.cssText=" (json/write-str (str (theme-var-css t) "position:fixed;inset:0;z-index:2147483647;background:var(--base-950,#0d0d0c);color:var(--text-200,#d4d0c8);overflow:auto;margin:0;padding:0;box-sizing:border-box;")) ";"
      "var fr=document.createElement('div');fr.id='seon-preview-frame';fr.style.cssText='width:'+stageW+'px;max-width:'+stageW+'px;min-width:0;box-sizing:border-box;margin:0;padding:0;';"
      "fr.innerHTML=html;st.appendChild(fr);if(b.firstChild)b.insertBefore(st,b.firstChild);else b.appendChild(st);void fr.offsetHeight;"
      "var t=(fr.children&&fr.children.length===1)?fr.firstElementChild:fr,r=t.getBoundingClientRect(),rw=num(r.width),rh=num(r.height),"
      "fsw=num(fr.scrollWidth),fsh=num(fr.scrollHeight),tsw=num(t.scrollWidth),tsh=num(t.scrollHeight),tcw=num(t.clientWidth),tch=num(t.clientHeight),"
      "frOvX=(fsw>stageW+1),sw=frOvX?Math.max(tsw,fsw,rw):Math.max(tsw,rw),sh=Math.max(tsh,fsh,rh),"
      "ovX=(frOvX||(rw>stageW+1)||(tcw>0&&tsw>tcw+1)),ovY=(tch>0&&tsh>tch+1),"
      "area=Math.round(Math.max(0,(isFinite(r.width)?r.width:0))*Math.max(0,(isFinite(r.height)?r.height:0))),"
      "app=document.getElementById('seon-app'),de=document.documentElement,"
      "dark=(theme==='light')?false:((theme==='dark')?true:(!(app&&app.getAttribute('data-theme')==='light')&&(!de||de.getAttribute('data-theme')!=='light')));"
      visual-audit-js-body
      "var defs=runVisualAudit(fr,{fullPage:false,dark:dark,stageW:stageW,sw:sw,ovX:ovX,rw:rw,rh:rh,htmlLen:html.trim().length});"
      "return{'slot-w':stageW,'w':rw,'h':rh,'area':area,'scroll-w':sw,'scroll-h':sh,'client-w':(tcw>0?tcw:rw),'client-h':(tch>0?tch:rh),'overflow-x?':ovX,'overflow-y?':ovY,'visual-defects':defs};"
      "}catch(e){return{'error':String(e)};}})()"))))

(defn clear-stage-js
  "Builds a safe JS snippet that removes `#seon-preview-stage` cleanly and clears `__seonPreviewBusy`."
  {:malli/schema [:=> [:cat] :string]
   :cost/ticks 50000 :cost/bytes 4096}
  []
  (str "(function(){try{"
       "var el=document.getElementById('seon-preview-stage');"
       "var had=!!el;"
       "if(el&&el.parentNode){el.parentNode.removeChild(el);}"
       "setTimeout(function(){window.__seonPreviewBusy=false;},350);"
       "return {'cleared':true,'existed':had};"
       "}catch(e){return {'cleared':false,'error':String(e)};}})()"))

(defn- lk-int [m fb & ks]
  (max 0 (safe-int (apply lookup m ks) fb)))

(defn normalize-snippet-measurement
  "Normalizes raw `eval_js` output from `measure-html-snippet-js` or `stage-hiccup-js`
   into `{:ok? :w :h :area :scroll-w :scroll-h :overflow-x? :visual-defects :visual-ok? ...}`."
  {:malli/schema [:=> [:cat :any]
                  [:map [:ok? :boolean] [:w :int] [:h :int] [:area :int]
                   [:scroll-w :int] [:scroll-h :int] [:overflow-x? :boolean]
                   [:visual-defects [:vector :string]] [:visual-ok? :boolean]]]
   :cost/ticks 500000 :cost/bytes 32768}
  [raw-value]
  (let [m (parse-raw-map raw-value)
        err (lookup m :error)]
    (if err
      {:ok? false :error (str err) :w 0 :h 0 :area 0 :scroll-w 0 :scroll-h 0
       :overflow-x? false :visual-defects [] :visual-ok? false}
      (let [w (lk-int m 0 :w :width) h (lk-int m 0 :h :height)
            sw (lk-int m w :scroll-w :scrollW :scrollWidth) sh (lk-int m h :scroll-h :scrollH :scrollHeight)
            cw (lk-int m w :client-w :clientW :clientWidth) ch (lk-int m h :client-h :clientH :clientHeight)
            slot-w (lk-int m cw :slot-w :slotW) area (lk-int m (* w h) :area)
            raw-ovx (lookup m :overflow-x? :overflowX)
            ov-x? (if (boolean? raw-ovx) raw-ovx (or (and (pos? slot-w) (> sw (inc slot-w))) (and (pos? cw) (> sw (inc cw)))))
            raw-ovy (lookup m :overflow-y? :overflowY)
            ov-y? (if (boolean? raw-ovy) raw-ovy (and (pos? ch) (> sh (inc ch))))
            hint (parse-px-hint (lookup m :hint-min-w :hintMinW))
            defects (vec (keep nonblank (let [ds (lookup m :visual-defects :visualDefects :defects)] (when (sequential? ds) ds))))]
        (cond-> {:ok? true :w w :h h :area area :scroll-w sw :scroll-h sh :client-w cw :client-h ch
                 :slot-w slot-w :overflow-x? ov-x? :overflow-y? ov-y? :visual-defects defects :visual-ok? (and (not ov-x?) (empty? defects))}
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
        w (lk-int vp 0 :innerWidth :w :width :inner-width :viewportW)
        h (lk-int vp 0 :innerHeight :h :height :inner-height :viewportH)
        dsw (lk-int vp w :docScrollW :doc-scroll-w :scrollW :scrollWidth)
        dsh (lk-int vp h :docScrollH :doc-scroll-h :scrollH :scrollHeight)
        raw-ov (lookup vp :overflowX :overflow-x? :doc-overflow-x)]
    {:w w :h h :doc-scroll-w dsw :doc-scroll-h dsh :overflow-x? (if (boolean? raw-ov) raw-ov (and (pos? w) (> dsw (inc w))))}))

(defn- normalize-element [el]
  (when (map? el)
    (let [x (safe-int (lookup el :x :left) 0) y (safe-int (lookup el :y :top) 0)
          w (lk-int el 0 :w :width) h (lk-int el 0 :h :height)
          sw (lk-int el w :scroll-w :scrollW :scrollWidth) sh (lk-int el h :scroll-h :scrollH :scrollHeight)
          cw (lk-int el w :client-w :clientW :clientWidth) ch (lk-int el h :client-h :clientH :clientHeight)
          area (lk-int el (* w h) :area)
          raw-ovx (lookup el :overflow-x? :overflowX) raw-ovy (lookup el :overflow-y? :overflowY)
          id (nonblank (lookup el :id))
          ent (when-let [v (lookup el :entity :data-entity)] (nonblank (str v)))
          cfn (parse-renderer-sym (lookup el :chosen-fn :chosenFn :data-chosen-fn))
          hint (parse-px-hint (lookup el :hint-min-w :hintMinW :data-min-width))]
      {:selector (or (nonblank (lookup el :selector)) (when id (str "#" id)) (when cfn (str "[data-chosen-fn=" cfn "]")) "element")
       :id id :entity ent :chosen-fn cfn :hint-min-w hint :x x :y y :w w :h h :scroll-w sw :scroll-h sh :client-w cw :client-h ch :area area
       :overflow-x? (if (boolean? raw-ovx) raw-ovx (and (pos? cw) (> sw (inc cw))))
       :overflow-y? (if (boolean? raw-ovy) raw-ovy (and (pos? ch) (> sh (inc ch))))
       :scrollable-y? (boolean (lookup el :scrollable-y? :scrollableY))
       :scrollable-x? (boolean (lookup el :scrollable-x? :scrollableX))})))

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
  "Pure analyzer over raw `measure-boxes-js` output comparing `:data-min-width` hints against measured boxes."
  {:malli/schema [:=> [:cat :any]
                  [:map [:ok? :boolean]
                   [:viewport [:map [:w :int] [:h :int] [:doc-scroll-w :int] [:doc-scroll-h :int] [:overflow-x? :boolean]]]
                   [:doc-overflow-x :boolean] [:clipped-items [:vector :map]] [:scrollable-items [:vector :map]]
                   [:by-renderer [:map-of :symbol [:map [:count :int] [:max-w :int] [:max-h :int] [:max-area :int]
                                                   [:any-overflow-x? :boolean] [:hint-min-w [:maybe :int]] [:suggested-min-w :int]]]]
                   [:summary :string]]]
   :cost/ticks 5000000 :cost/bytes 524288}
  [raw-measurement]
  (let [raw (parse-raw-map raw-measurement) err (lookup raw :error)]
    (if err
      {:ok? false :error (str err) :viewport {:w 0 :h 0 :doc-scroll-w 0 :doc-scroll-h 0 :overflow-x? false}
       :doc-overflow-x false :clipped-items [] :scrollable-items [] :by-renderer {} :element-count 0 :elements []
       :summary (str "Measurement error: " err)}
      (let [vp (normalize-viewport raw)
            els (vec (keep normalize-element (let [xs (or (lookup raw :elements :items) [])] (if (sequential? xs) xs []))))
            defects (vec (keep nonblank (let [ds (lookup raw :visual-defects :visualDefects :defects)] (when (sequential? ds) ds))))
            clipped (filterv #(or (:overflow-x? %) (and (:overflow-y? %) (not (:scrollable-y? %)))) els)
            scrollable (filterv #(and (:overflow-y? %) (:scrollable-y? %)) els)
            by-r (reduce-kv (fn [a r g] (assoc a r (summarize-renderer-group g))) {} (group-by :chosen-fn (filter :chosen-fn els)))
            doc-ov? (boolean (:overflow-x? vp))
            ok? (and (not doc-ov?) (empty? clipped) (empty? defects))
            summary (str (:w vp) "x" (:h vp) " " (cond (or doc-ov? (seq clipped)) "OVERFLOW" (seq defects) "DEFECTS" :else "ok")
                         (when doc-ov? (str " · doc-scroll-w=" (:doc-scroll-w vp)))
                         " · " (count els) " boxes · " (count clipped) " clipped · " (count scrollable) " scrollable · " (count by-r) " renderers"
                         (when (seq defects) (str " · DEFECTS(" (count defects) "): " (str/join "; " (take 4 defects)))))]
        {:ok? ok? :viewport vp :doc-overflow-x doc-ov? :clipped-items clipped :scrollable-items scrollable
         :visual-defects defects :by-renderer by-r :element-count (count els) :elements els :summary summary}))))

(defn calibrate-hints
  "Produces per-renderer pixel calibration recommendations from `analyze-pixel-fit`."
  {:malli/schema [:=> [:cat [:maybe :map]]
                  [:map-of :symbol [:map [:measured-w :int] [:measured-h :int] [:measured-area :int]
                                    [:hint-min-w [:maybe :int]] [:suggested-min-w :int] [:delta-w :int] [:overflow-x? :boolean]]]]
   :cost/ticks 2000000 :cost/bytes 131072}
  [analysis]
  (reduce-kv
   (fn [acc rsym st]
     (if (and (symbol? rsym) (map? st))
       (let [mw (lk-int st 0 :max-w) mh (lk-int st 0 :max-h) ma (lk-int st (* mw mh) :max-area)
             hint (parse-px-hint (:hint-min-w st)) ov? (boolean (:any-overflow-x? st))
             sug (lk-int st (if (and (not ov?) hint (pos? hint) (>= mw hint)) hint mw) :suggested-min-w)]
         (assoc acc rsym {:measured-w mw :measured-h mh :measured-area ma :hint-min-w hint
                          :suggested-min-w sug :delta-w (- sug (long (or hint 0))) :overflow-x? ov?}))
       acc))
   {} (or (when (and (map? analysis) (map? (:by-renderer analysis))) (:by-renderer analysis)) {})))

(defn aggregate-frame-defects
  "Aggregates width-tagged visual defects across preview frames into `{:visual-ok? :visual-defects}`."
  {:malli/schema [:=> {:examples [[[[{:width 360 :dom {:overflow-x? false :visual-defects []}}]]
                                   {:visual-ok? true :visual-defects []}]
                                  [[[{:width 360 :dom {:overflow-x? false :visual-defects ["SVG #1 is empty"]}}]]
                                   {:visual-ok? false :visual-defects ["[360px] SVG #1 is empty"]}]
                                  [[[{:width 360 :dom {:overflow-x? true :visual-defects []}}]]
                                   {:visual-ok? false :visual-defects ["[360px] Horizontal overflow at 360px"]}]]}
                  [:cat [:maybe [:sequential :map]]]
                  [:map [:visual-ok? :boolean] [:visual-defects [:vector :string]]]]
   :cost/ticks 500000 :cost/bytes 131072}
  [frames]
  (let [defs (->> (when (sequential? frames) (filter map? frames))
                  (mapcat (fn [f]
                            (let [w (max 0 (safe-int (or (:seon.media/width f) (:width f)) 0))
                                  dom (let [d (or (:seon.media/dom f) (:dom f))] (if (map? d) d {}))
                                  ds (vec (keep nonblank (let [v (or (:visual-defects dom) (:visual-defects f))] (when (sequential? v) v))))
                                  ov? (boolean (or (:overflow-x? dom) (:overflowX dom)))
                                  all (cond-> ds (and ov? (not (some #(str/includes? (str/lower-case %) "overflow") ds)))
                                              (conj (str "Horizontal overflow at " w "px")))]
                              (map #(str "[" w "px] " %) all))))
                  distinct vec)]
    {:visual-ok? (empty? defs) :visual-defects defs}))
