// Run against the owned dev cluster; edit the identity heading after READY.
// Uses the same open page throughout and fails if observing the edit needs navigation.
const { chromium } = require('playwright');
const assert = require('node:assert/strict');

(async () => {
  const browser = await chromium.launch({
    executablePath: '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome',
    headless: true,
  });
  let page;
  try {
    page = await browser.newPage({ viewport: { width: 1440, height: 1000 } });
    const errors = [];
    page.on('pageerror', error => errors.push(error.message));
    await page.goto(process.argv[2], { waitUntil: 'domcontentloaded', timeout: 30000 });
    const heading = page.locator('.seon-agent-identity-entry .seon-kicker').first();
    await heading.waitFor({ timeout: 30000 });
    const before = await heading.innerText();
    const expected = process.argv[4] || 'Agent identity';
    let navigations = 0;
    page.on('framenavigated', frame => { if (frame === page.mainFrame()) navigations++; });
    console.log(JSON.stringify({ ready: true, before, expected, url: page.url() }));
    await page.waitForFunction(expected =>
      document.querySelector('.seon-agent-identity-entry .seon-kicker')?.textContent === expected,
    expected, { timeout: 120000 });
    assert.notEqual(before, expected, 'proof must observe an actual change');
    assert.equal(navigations, 0, 'the existing document must update without navigation');
    assert.deepEqual(errors, []);
    await page.screenshot({ path: process.argv[3], fullPage: false });
    console.log(JSON.stringify({ updated: await heading.innerText(), navigations, errors }));
  } catch (error) {
    if (page) {
      await page.screenshot({ path: process.argv[3], fullPage: false });
      console.error(JSON.stringify({ failed: true, url: page.url(), body: await page.locator('body').innerText() }));
    }
    throw error;
  } finally {
    await browser.close();
  }
})().catch(error => { console.error(error); process.exitCode = 1; });
