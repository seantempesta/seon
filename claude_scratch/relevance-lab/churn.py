# Churn of the shown set from turn to turn, with hysteresis (enter at >= 0.6, leave below 0.4,
# order by entry) against a plain cut at 0.6, and the bytes a diff costs against a full re-render.
import pickle, numpy as np, collections
exec(open('replay2.py').read().split("CONFIGS =")[0])
res = pickle.load(open('replay-results.pkl', 'rb'))
def bytes_of(i): return tokens_of(i) * 4
for label in ('q1 plan', 'q3 decayed, half-life 1', 'q4 plan + q3 (hl 1)'):
    for mode in ('plain cut 0.6', 'hysteresis 0.6/0.4'):
        moves = []; diff_b = []; full_b = []; sizes = []
        for (lab, rid), hist in res.items():
            if lab != label: continue
            shown = []   # in entry order
            for ch in hist:
                if mode.startswith('plain'):
                    new = [i for i, p in sorted(ch.items(), key=lambda kv: -kv[1]) if p >= 0.6]
                else:
                    keep = [i for i in shown if ch.get(i, 0) >= 0.4]
                    new = keep + [i for i, p in sorted(ch.items(), key=lambda kv: -kv[1]) if p >= 0.6 and i not in keep]
                entered = [i for i in new if i not in shown]; left = [i for i in shown if i not in new]
                moved = 0 if mode.startswith('hyst') else sum(1 for a, b in zip([i for i in shown if i in new], [i for i in new if i in shown]) if a != b)
                moves.append(len(entered) + len(left) + moved)
                diff_b.append(sum(bytes_of(i) for i in entered) + 24 * (len(left) + moved))
                full_b.append(sum(bytes_of(i) for i in new)); sizes.append(len(new))
                shown = new
        print(f'{label:24s} {mode:20s} shown {np.mean(sizes):4.1f}/turn  units changed {np.mean(moves):4.1f}/turn  diff {np.mean(diff_b):5.0f} B/turn vs re-render {np.mean(full_b):5.0f} B/turn')
