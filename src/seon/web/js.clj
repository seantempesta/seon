(ns seon.web.js
  "Lightweight progressive browser controllers (sticky tail-scroll, debounced viewport sync,
   and native popover=hint + CSS Anchor Positioning hovercard controller). Zero CDN dependencies.")

(def sticky-scroll-js
  "Targeted Datastar tail-scroll controller (WeakMap state, datastar-fetch hook, batched rAF read/write, zero MutationObserver)."
  "(()=>{const A='.stage-scroll,.ws-timeline,.live-drawer-body,.live-reasoning,.live-prose,.stream-dock',V='.stage-scroll,.ws-timeline',S='.live-drawer-body,.live-reasoning,.live-prose,.stream-dock',P={capture:true,passive:true},st=new WeakMap(),rs=new WeakSet(),on=(t,f,o=P)=>document.addEventListener(t,f,o);let raf=0,mk=0,ty=null,te=null;const dbg=(e)=>e.classList.contains('stage-scroll')&&!!e.querySelector('.debug-wrap'),ck=(e)=>{if(e.classList.contains('stage-scroll')){const v=e.querySelector('#seon-view-unit');return v?(v.dataset.agent||'')+':'+(v.dataset.viewMode||''):'';}return(e.dataset.agent||'')+':'+(e.id||'');},gs=(e)=>{let s=st.get(e);if(!s){s={pin:true,top:e.scrollTop,max:0,pu:0,uu:0,ctx:ck(e)};st.set(e,s);}return s;},ro=typeof ResizeObserver==='function'?new ResizeObserver(()=>sched(2)):null,snap=()=>{raf=0;const m=mk;mk=0;const els=document.querySelectorAll(m===1?V:m===2?S:A),now=performance.now(),wr=[];for(const e of els){if(ro&&(e.classList.contains('live-drawer-body')||e.classList.contains('live-reasoning')||e.classList.contains('live-prose'))&&!rs.has(e)){rs.add(e);ro.observe(e);}const s=gs(e),c=ck(e);if(dbg(e)){if(s.ctx!==c){s.ctx=c;s.pin=false;wr.push([e,s,0,0]);}continue;}if(s.ctx!==c){s.ctx=c;s.pin=true;s.uu=0;}const mx=Math.max(0,e.scrollHeight-e.clientHeight);if(s.pin&&mx>0&&Math.abs(mx-e.scrollTop)>1)wr.push([e,s,mx,mx]);else s.max=mx;}for(const[e,s,t,mx]of wr){s.pu=now+140;s.top=t;s.max=mx;e.scrollTop=t;}},sched=(m)=>{mk|=m;if(!raf)raf=requestAnimationFrame(snap);},ft=(n)=>n&&n.closest?n.closest(A):null,fs=(n)=>{let e=ft(n);while(e){if(e.scrollHeight>e.clientHeight+1)return e;e=ft(e.parentElement);}return ft(n);};on('wheel',(e)=>{const el=fs(e.target);if(!el)return;const s=gs(el);if(e.deltaY<0){s.pin=false;s.uu=performance.now()+250;}else if(e.deltaY>0)s.uu=0;});on('touchstart',(e)=>{if(e.touches&&e.touches.length===1){ty=e.touches[0].clientY;te=fs(e.target);}});on('touchmove',(e)=>{if(te&&ty!==null&&e.touches&&e.touches.length===1){const dy=e.touches[0].clientY-ty;if(dy>4){const s=gs(te);s.pin=false;s.uu=performance.now()+250;}else if(dy<-4)gs(te).uu=0;}});on('keydown',(e)=>{if(e.key==='ArrowUp'||e.key==='PageUp'||e.key==='Home'){const t=e.target&&e.target.tagName;if(t==='INPUT'||t==='TEXTAREA')return;const el=fs(e.target)||document.getElementById('seon-main');if(el){const s=gs(el);s.pin=false;s.uu=performance.now()+250;}}});on('scroll',(e)=>{const el=e.target;if(!el||!el.matches||!el.matches(A)||dbg(el))return;const s=gs(el),top=el.scrollTop,mx=Math.max(0,el.scrollHeight-el.clientHeight),d=mx-top,now=performance.now();if(top<s.top-0.5)s.pin=false;else if(now>=s.pu){if(d<=24&&now>=s.uu)s.pin=true;else if(Math.abs(mx-s.max)<=1&&d>24)s.pin=false;}s.top=top;s.max=mx;});on('toggle',(e)=>{const d=e.target;if(!d||d.tagName!=='DETAILS'||!d.open)return;const el=ft(d.parentElement);if(el&&!dbg(el)&&el.scrollHeight-el.clientHeight-el.scrollTop>24)gs(el).pin=false;});on('submit',()=>{document.querySelectorAll(A).forEach((el)=>{if(!dbg(el)){const s=gs(el);s.pin=true;s.uu=0;}});sched(3);},true);on('datastar-fetch',(e)=>{const d=e&&e.detail;if(!d||d.type!=='datastar-patch-elements')return;const r=d.argsRaw&&d.argsRaw.elements;if(typeof r==='string'){let m=0;if(r.includes('seon-view-unit')||r.includes('ws-left-unit'))m|=1;if(r.includes('seon-stream-unit'))m|=2;if(m)sched(m);}else sched(3);},false);on('click',(e)=>{const b=e.target&&e.target.closest&&e.target.closest('.stream-phase-pill,.thinking-toggle');if(b&&!b.classList.contains('is-close')){document.querySelectorAll(S).forEach((el)=>{const s=gs(el);s.pin=true;s.uu=0;});sched(2);}});window.addEventListener('resize',()=>sched(3),{passive:true});window.addEventListener('DOMContentLoaded',()=>sched(3),{once:true});})();")

(def viewport-sync-js
  "Debounced (180 ms) browser viewport sync posting `(seon.web/set-viewport! w h)` on initial load and resize."
  "(()=>{let t=0,lw=0,lh=0,ac=null;const send=()=>{const w=Math.max(200,Math.min(10000,Math.round(window.innerWidth||0))),h=Math.max(200,Math.min(10000,Math.round(window.innerHeight||0)));if(w===lw&&h===lh)return;lw=w;lh=h;if(ac)ac.abort();ac=typeof AbortController==='function'?new AbortController():null;fetch('/call?form='+encodeURIComponent('(seon.web/set-viewport! '+w+' '+h+')'),ac?{method:'POST',signal:ac.signal}:{method:'POST'}).catch((e)=>{if(!e||e.name!=='AbortError'){}});},sched=()=>{clearTimeout(t);t=setTimeout(send,180);};window.addEventListener('resize',sched,{passive:true});if(document.readyState==='loading')window.addEventListener('DOMContentLoaded',send,{once:true});else send();})();")

(def hovercard-js
  "Progressive hovercard controller: native popover=hint + interestfor, 180ms hover show / 140ms hoverable leave grace (WCAG 1.4.13), keyboard focus without focus theft (WCAG 3.2.1), Escape/click dismiss, Map cache (0ms repeat hovers), CSS Anchor Positioning with getBoundingClientRect fallback."
  (str "(()=>{"
       "const cache=new Map();"
       "let hc=null,trig=null,showT=0,hideT=0,ac=null,setAria=false;"
       "const card=()=>hc||(hc=document.getElementById('seon-hovercard'));"
       "const hasAnchor=()=>typeof CSS!=='undefined'&&CSS.supports&&CSS.supports('position-area','block-end');"
       "const clearT=()=>{clearTimeout(showT);clearTimeout(hideT);showT=hideT=0;};"
       "const hide=()=>{"
       "clearT();if(ac){ac.abort();ac=null;}"
       "const p=card();"
       "if(trig){if(setAria){trig.removeAttribute('aria-describedby');setAria=false;}if(hasAnchor())trig.style.anchorName='';}"
       "trig=null;"
       "if(p){try{p.hidePopover();}catch(_){p.removeAttribute('data-open');}}"
       "};"
       "const place=(el,p)=>{"
       "if(hasAnchor()){el.style.anchorName='--seon-hc';p.style.positionAnchor='--seon-hc';p.style.position='';p.style.left='';p.style.top='';return;}"
       "const r=el.getBoundingClientRect(),w=Math.min(360,window.innerWidth-24);"
       "const x=Math.max(12,Math.min(window.innerWidth-w-12,r.left));"
       "const y=r.bottom+6+180>window.innerHeight?Math.max(12,r.top-186):r.bottom+6;"
       "p.style.position='fixed';p.style.left=x+'px';p.style.top=y+'px';"
       "};"
       "const show=(el)=>{"
       "const ref=el&&el.getAttribute('data-preview-ref'),p=card();"
       "if(!ref||!p)return;"
       "if(trig&&trig!==el){if(setAria){trig.removeAttribute('aria-describedby');setAria=false;}if(hasAnchor())trig.style.anchorName='';}"
       "trig=el;"
       "if(!el.hasAttribute('aria-describedby')){el.setAttribute('aria-describedby','seon-hovercard');setAria=true;}"
       "const render=(html)=>{if(trig!==el)return;p.innerHTML=html;place(el,p);try{p.showPopover();}catch(_){p.setAttribute('data-open','1');}};"
       "if(cache.has(ref)){render(cache.get(ref));return;}"
       "if(ac)ac.abort();ac=typeof AbortController==='function'?new AbortController():null;"
       "fetch('/preview?ref='+encodeURIComponent(ref),ac?{signal:ac.signal}:{}).then((r)=>r.ok?r.text():'').then((html)=>{"
       "if(!html)return;if(cache.size>128)cache.clear();cache.set(ref,html);render(html);"
       "}).catch(()=>{});"
       "};"
       "const findTrig=(n)=>n&&n.closest&&!n.closest('#seon-hovercard')?n.closest('[data-preview-ref]'):null;"
       "document.addEventListener('pointerover',(e)=>{const el=findTrig(e.target);if(el){clearTimeout(hideT);if(trig===el)return;clearTimeout(showT);showT=setTimeout(()=>show(el),180);}else if(e.target&&e.target.closest&&e.target.closest('#seon-hovercard')){clearTimeout(hideT);}},true);"
       "document.addEventListener('pointerout',(e)=>{if(findTrig(e.target)||(e.target&&e.target.closest&&e.target.closest('#seon-hovercard'))){clearTimeout(showT);clearTimeout(hideT);hideT=setTimeout(hide,140);}},true);"
       "document.addEventListener('focusin',(e)=>{const el=findTrig(e.target);if(el){clearT();show(el);}},true);"
       "document.addEventListener('focusout',(e)=>{if(findTrig(e.target)){clearT();hideT=setTimeout(hide,120);}},true);"
       "document.addEventListener('keydown',(e)=>{if(e.key==='Escape')hide();},true);"
       "document.addEventListener('click',(e)=>{if(findTrig(e.target))hide();},true);"
       "document.addEventListener('datastar-fetch',(e)=>{const d=e&&e.detail;if(d&&d.type==='datastar-patch-elements'&&typeof(d.argsRaw&&d.argsRaw.elements)==='string'&&d.argsRaw.elements.includes('seon-view-unit'))cache.clear();});"
       "})();"))
