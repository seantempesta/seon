// Read-only browser evidence: root overflow and the native defaults disclosure.
const { chromium } = require('playwright');
(async () => {
  const browser = await chromium.launch({ headless: true, channel: 'chrome' });
  try {
    const page = await browser.newPage();
    for (const width of [1440, 700]) {
      await page.setViewportSize({ width, height: 900 });
      await page.goto('http://127.0.0.1:7994/');
      console.log(JSON.stringify(await page.evaluate(() => ({
        width: innerWidth, document: document.documentElement.scrollWidth,
        overflow: [...document.querySelectorAll('main *')]
          .filter(el => el.getBoundingClientRect().right > innerWidth).slice(0, 12)
          .map(el => ({ tag: el.tagName, class: el.className, width: el.clientWidth,
            right: el.getBoundingClientRect().right }))
      }))));
      await page.goto('http://127.0.0.1:7994/ns/my.agents.juniper');
      const details = page.locator('.seon-settings-defaults');
      await details.locator('summary').click();
      await page.waitForFunction(() => document.querySelector('.seon-settings-defaults')?.open);
      await page.locator('.seon-agent-settings').scrollIntoViewIfNeeded();
      await page.screenshot({ path: `tmp/html-views/settings-expanded-${width}.png` });
      if (!await details.evaluate(el => el.open)) throw Error('Defaults closed during capture');
      console.log(JSON.stringify({ width, defaults: await details.innerText() }));
    }
  } finally { await browser.close(); }
})().catch(error => { console.error(error); process.exitCode = 1; });
