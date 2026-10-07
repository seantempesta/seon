import sys; sys.path.insert(0,'.')
import clj
root='/Users/sean/src/seon'
tot=0; bad=0; nd=0
for p in clj.src_files(root):
    P=clj.load(p)
    tot+=len(P.lines)
    d=list(clj.top_defs(P)); nd+=len(d)
    # coverage check: last form end line vs file length
    if P.forms and P.forms[-1].l1 < len(P.lines)-30: bad+=1; print('short', p, P.forms[-1].l1, len(P.lines))
print(tot, nd, bad)
