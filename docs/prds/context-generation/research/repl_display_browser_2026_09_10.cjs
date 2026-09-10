// Run with Playwright available on NODE_PATH; write disposable images under tmp/.
const { chromium } = require('playwright');
const fs = require('node:fs/promises');
const assert = require('node:assert/strict');

(async () => {
  const browser = await chromium.launch({ headless: true, channel: 'chrome' });
  try {
    await fs.mkdir('tmp/repl-display-browser', { recursive: true });
    const page = await browser.newPage({ viewport: { width: 1100, height: 900 } });
    page.setDefaultTimeout(30000);
    for (const agent of ['juniper', 'root']) {
      const response = await page.goto(`http://127.0.0.1:7994/ns/my.agents.${agent}/debug`);
      assert.equal(response.status(), 200);
      await page.setViewportSize({ width: 500, height: 900 });
      // Cytoscape owns ResizeObserver and debounces its resize by 100 ms.
      // Await the observable canvas size under the page's declared backstop.
      await page.waitForFunction(() => {
        const hosts = [...document.querySelectorAll('[data-graph-canvas]')];
        return hosts.length > 0 && hosts.every(host => {
          const canvases = [...host.querySelectorAll('canvas')];
          return canvases.length > 0 && canvases.every(canvas =>
            canvas.getBoundingClientRect().width <= host.clientWidth + 1);
        });
      });
      await page.getByRole('heading', { name: 'Context now', exact: true }).waitFor();
      const details = page.locator('.seon-debug-provider-prompt');
      assert.equal(await details.getAttribute('open'), null);
      assert.ok(await page.locator('.seon-eval-renderer').count());
      assert.ok(await page.locator('.seon-eval-entry ul li').count());
      const measure = async () => page.evaluate(() => ({
        viewport: document.documentElement.clientWidth,
        documentWidth: document.documentElement.scrollWidth,
        preCount: document.querySelectorAll('.seon-debug pre').length,
        overflowing: [...document.querySelectorAll('.seon-debug *')]
          .filter(el => el.getBoundingClientRect().right > innerWidth + 1
            && el.getBoundingClientRect().height > 0)
          .slice(-20).map(el => ({ tag: el.tagName, class: el.className,
            width: el.getBoundingClientRect().width, text: el.textContent.slice(0, 80) })),
        failures: [...document.querySelectorAll('.seon-debug pre')].flatMap(pre => {
          const style = getComputedStyle(pre);
          return style.whiteSpace === 'pre-wrap' && style.overflowWrap === 'anywhere'
            && style.wordBreak === 'normal' && pre.scrollWidth <= pre.clientWidth + 1
            ? [] : [{ class: pre.className, width: pre.clientWidth, scroll: pre.scrollWidth,
                      whiteSpace: style.whiteSpace, overflowWrap: style.overflowWrap,
                      wordBreak: style.wordBreak }];
        })
      }));
      await page.screenshot({ path: `tmp/repl-display-browser/${agent}.png` });
      await details.locator(':scope > summary').click();
      const measured = await measure();
      console.log(JSON.stringify({ agent, ...measured }));
      assert.ok(measured.preCount > 0);
      assert.equal(measured.documentWidth, measured.viewport);
      assert.deepEqual(measured.failures, []);
      await page.screenshot({ path: `tmp/repl-display-browser/${agent}-comparison.png` });
      await page.setViewportSize({ width: 1100, height: 900 });
    }
  } finally {
    await browser.close();
  }
})().catch(error => { console.error(error); process.exitCode = 1; });
