// NODE_PATH must include Playwright. Run from the checkout after adoption.
const { chromium } = require('playwright');
const fs = require('node:fs/promises');
const assert = require('node:assert/strict');

(async () => {
  const browser = await chromium.launch({ headless: true, channel: 'chrome' });
  try {
    await fs.mkdir('tmp/debug-product', { recursive: true });
    for (const width of [1440, 700]) {
      const page = await browser.newPage({ viewport: { width, height: 900 } });
      page.setDefaultTimeout(30000);
      const errors = [];
      page.on('pageerror', error => errors.push(error.message));
      const response = await page.goto('http://127.0.0.1:7994/ns/my.agents.juniper/debug');
      assert.equal(response.status(), 200);
      await page.locator('[data-session-loaded]').waitFor({ state: 'attached' });
      const coloured = await page.locator('[data-emission-bytes]').allTextContents();
      assert.ok(coloured.length > 0);
      const selected = await page.locator('[data-session-loaded]').getAttribute('data-session-loaded');
      await page.screenshot({ path: `tmp/debug-product/${process.argv[2] || 'a-final'}-${width}.png`, fullPage: true });
      await page.screenshot({ path: `tmp/debug-product/${process.argv[2] || 'a-final'}-${width}-end.png` });
      const layout = await page.evaluate(() => ({
        viewport: innerWidth, documentWidth: document.documentElement.scrollWidth,
        scrollTop: scrollY,
        overflow: [...document.querySelectorAll('.seon-session-page *')]
          .filter(el => el.getBoundingClientRect().width && el.scrollWidth > el.clientWidth + 1
            && getComputedStyle(el).display !== 'inline')
          .map(el => ({ tag: el.tagName, class: el.className }))
      }));
      assert.equal(layout.documentWidth, width);
      assert.ok(layout.scrollTop > 0);
      assert.deepEqual(layout.overflow, []);
      await page.evaluate(() => window.scrollTo(0, 0));
      await page.screenshot({ path: `tmp/debug-product/${process.argv[2] || 'a-final'}-${width}-top.png` });
      await page.getByRole('link', { name: 'As the model saw it', exact: true }).click();
      const raw = page.locator('[data-prompt-bytes]');
      await raw.waitFor();
      assert.equal(await raw.textContent(), coloured.join(''));
      assert.equal(Number(await raw.getAttribute('data-prompt-bytes')), Buffer.byteLength(coloured.join('')));
      assert.equal(await page.locator('.seon-agent-routes').count(), 0);
      assert.equal(await page.locator('.seon-session-message').getAttribute('open'), null);
      assert.match(await page.locator('.seon-session-state').textContent(), /default/);
      await page.locator('.seon-session-message summary').click();
      await page.getByPlaceholder('message agent juniper …').fill('unsent browser verification');
      assert.equal(await page.getByPlaceholder('message agent juniper …').inputValue(), 'unsent browser verification');
      await page.locator('.seon-session-message summary').click();
      await page.locator('.seon-session-record summary').click();
      await page.locator('.seon-session-record .seon-walk-unit').first().waitFor({ state: 'attached' });
      assert.deepEqual(errors, []);
      console.log(JSON.stringify({ selected, emissions: coloured.length,
        bytes: Buffer.byteLength(coloured.join('')), ...layout }));
      await page.goto('http://127.0.0.1:7994/ns/my.agents.juniper');
      await page.locator('.seon-session-header').waitFor();
      await page.screenshot({ path: `tmp/debug-product/${process.argv[2] || 'a-final'}-agent-${width}.png`, fullPage: true });
      await page.screenshot({ path: `tmp/debug-product/${process.argv[2] || 'a-final'}-agent-${width}-top.png` });
      assert.match(await page.locator('h1').first().textContent(), /juniper/);
      const blocks = page.locator('.seon-rank-layout > .seon-walk-unit');
      assert.equal(await blocks.first().getAttribute('data-walk-path'), '[:seon.agent/plan]');
      assert.match(await blocks.nth(1).textContent(), /Runtime/);
      assert.equal(await blocks.nth(2).getAttribute('data-walk-path'), '[:seon.message/_inbox]');
      assert.equal(await page.getByText('show everything', { exact: true }).count(), 0);
      const mainLayout = await page.evaluate(() => ({
        documentWidth: document.documentElement.scrollWidth,
        innerScrollers: [...document.querySelectorAll('.seon-namespace-page *')]
          .filter(el => ['auto', 'scroll'].includes(getComputedStyle(el).overflowY)
            && el.scrollHeight > el.clientHeight + 1).map(el => el.className)
      }));
      assert.equal(mainLayout.documentWidth, width);
      assert.deepEqual(mainLayout.innerScrollers, []);
      await page.locator('.seon-turn-history summary').click();
      assert.equal(await page.evaluate(() => document.documentElement.scrollWidth), width);
      console.log(JSON.stringify({ page: 'agent', width, ...mainLayout }));
      await page.close();
    }
  } finally { await browser.close(); }
})().catch(error => { console.error(error); process.exitCode = 1; });
