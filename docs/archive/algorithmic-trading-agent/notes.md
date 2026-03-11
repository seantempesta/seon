---
type: prd
status: abandoned
tags: [prd, archive, trading, agent]
---

# Notes: Algorithmic Trading Agent

Capture gotchas, learnings, and things that surprised you during research and implementation.

---

## Research Notes

### 2025-12-19: Starting Research Phase

Starting the research phase with three parallel tracks:
1. Normalization approaches
2. REPL recording for training data
3. Strategy DSL design

Key insight from planning: The "frozen present" model is elegant - the agent always thinks it's "today", we just control what "today" means.

---

## Gotchas

*Things that tripped us up or surprised us*

---

## Learnings

*Insights gained during research and implementation*

---

## Questions for Later

*Things we noticed but deferred*

- How to handle options expiration dates in the frozen time model?
- Should the agent be able to request specific historical dates?
- How to handle weekends/holidays when stepping through time?

---

## External Resources

*Useful links discovered during research*

- [QuantConnect Documentation](https://www.quantconnect.com/docs)
- [Zipline Documentation](https://zipline.ml4trading.io/)
- [TradingView Pine Script Manual](https://www.tradingview.com/pine-script-docs/en/v5/)
