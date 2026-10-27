# Prototype measure: a real page's HTML as hiccup, printed three ways; tokens at 3.2 chars/token
# (seon.config.ai/chars-per-token-prior). Not the implementation: an estimate of the design's sizes.
import sys, re, json
from html.parser import HTMLParser
VOID={'meta','link','input','br','img','hr'}
class P(HTMLParser):
    def __init__(s): super().__init__(); s.root=['root',{},[]]; s.st=[s.root]
    def handle_starttag(s,t,a):
        n=[t,dict(a),[]]; s.st[-1][2].append(n)
        if t not in VOID: s.st.append(n)
    def handle_endtag(s,t):
        for i in range(len(s.st)-1,0,-1):
            if s.st[i][0]==t: del s.st[i:]; break
    def handle_data(s,d):
        if d.strip() or (s.st[-1][0] in ('pre','code')): s.st[-1][2].append(d)
html=open(sys.argv[1]).read()
p=P(); p.feed(html)
def find(n,pred):
    if isinstance(n,str): return None
    if pred(n): return n
    for c in n[2]:
        r=find(c,pred)
        if r: return r
body=find(p.root,lambda n:n[0]=='body')
app=find(body,lambda n:n[1].get('id')=='app')
tok=lambda s: len(s)/3.2
def esc(s): return json.dumps(s,ensure_ascii=False)
# naive: every attribute
def naive(n):
    if isinstance(n,str): return esc(n)
    attrs=' '.join(f':{k} {esc(v or "")}' for k,v in n[1].items())
    return '['+':'+n[0]+(' {'+attrs+'}' if attrs else '')+''.join(' '+naive(c) for c in n[2])+']'
# compact: semantic class names (theme map once), component marker, no data-*/on/aria/style, no layout-only wrappers
theme={}
def sem(cls,tag):
    if not cls: return ''
    if cls.startswith('seon-') or ' ' not in cls and '-' not in cls: return '.'+cls.split()[0]
    if cls not in theme: theme[cls]=f'{tag}{len(theme)}'
    return '.'+theme[cls]
COMP={'c-':'chat-part','t-':'tile','s-':'side','p-head':'head','p-input':'agent-input','d-':'sent-request','eval-':'evaluation'}
def marker(i):
    for k,v in COMP.items():
        if i and i.startswith(k): return v
def compact(n, drop_theme=False):
    if isinstance(n,str): return esc(n.strip() if not n.strip()=='' else n)
    kids=[c for c in n[2] if not (isinstance(c,str) and not c.strip())]
    a=n[1]; i=a.get('id')
    # a wrapper with no meaning: a div/span with one child, no text, no id, no component
    if n[0] in ('div','span') and len(kids)==1 and not isinstance(kids[0],str) and not marker(i):
        return compact(kids[0],drop_theme)
    tag=n[0]+('' if drop_theme else sem(a.get('class',''),n[0]))
    m=marker(i)
    keep={k:v for k,v in a.items() if k in ('href','open','title') }
    head=':'+tag+(f' {{:render {m} :key {esc(i)}}}' if m else '')+(' {'+' '.join(f':{k} {esc(v or "")}' for k,v in keep.items())+'}' if keep else '')
    return '['+head+''.join(' '+compact(c,drop_theme) for c in kids)+']'
def part(id_): return find(app,lambda n:n[1].get('id')==id_)
out={}
out['html_chars']=len(html); out['html_tokens']=round(tok(html))
for name,node in [('page',app),('chat c-rows',part('c-rows')),('tiles p-tiles',part('p-tiles')),('sidebar c-side',part('c-side')),('head p-head',part('p-head'))]:
    if node is None: continue
    theme.clear()
    nv=naive(node); cp=compact(node); th=json.dumps(theme)
    out[name]={'naive_hiccup_tokens':round(tok(nv)),'compact_tokens':round(tok(cp)),'theme_map_tokens':round(tok(th)),'theme_entries':len(theme)}
    if name=='page': open(sys.argv[2],'w').write(cp); open(sys.argv[2]+'.theme.json','w').write(th)
# one new eval: the last chat part
rows=part('c-rows'); last=[c for c in rows[2] if not isinstance(c,str)][-1]
theme.clear(); out['one new chat part (diff)']=round(tok(compact(last)))
print(json.dumps(out,indent=1))
