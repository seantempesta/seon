# embed a list of texts on my root's agent a2: emb(texts) -> np.array
import subprocess, json, numpy as np, os
SP=os.path.dirname(os.path.abspath(__file__))
def emb(texts, agent='a2'):
    open(SP+'/to-embed.edn','w').write('['+' '.join(json.dumps(t) for t in texts)+']')
    out=subprocess.run([SP+'/ev.sh',SP+'/embedfile.clj',agent,'600'],capture_output=True,text=True).stdout
    assert ':value' in out, out[:2000]
    V=np.array([[float(x) for x in l.strip()[1:-1].split()] for l in open(SP+'/embedded.edn')],dtype=np.float32)
    assert len(V)==len(texts)
    return V
