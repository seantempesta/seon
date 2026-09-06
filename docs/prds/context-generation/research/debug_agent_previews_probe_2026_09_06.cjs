// Read-only browser proof against the seeded Juniper debug page.
const {chromium} = require('playwright');
const assert = require('node:assert/strict');
const fs = require('node:fs');

(async () => {
  const browser = await chromium.launch({
    executablePath: '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome',
    headless: true, timeout: 15000,
  });
  try {
    const page = await browser.newPage({viewport: {width: 1440, height: 1100}});
    page.setDefaultTimeout(20000);
    const errors = [];
    page.on('pageerror', error => errors.push(error.message));
    const started = Date.now();
    await page.goto(process.argv[2], {waitUntil: 'domcontentloaded'});
    await page.locator('.seon-debug-selected-previews .seon-agent-identity-entry').waitFor();
    const selected = page.locator('.seon-debug-selected-previews');
    const selectedText = await selected.innerText();
    for (const text of ['seon.db/pull', 'juniper', 'my.agents.juniper', 'lab-run-inspection']) {
      assert(selectedText.includes(text), `identity preview must include ${text}`);
    }
    assert.equal(await selected.locator(':scope > .seon-debug-projection-column').count(), 2);
    assert.equal(await selected.locator('.seon-debug-selected-renderer').count(), 0,
      'renderer metadata must stay outside the output pair');
    assert.equal(await page.locator('#debug-selection .seon-debug-selected-renderer').count(), 2,
      'both selected renderers must remain inspectable outside the output pair');
    const found = page.locator('.seon-debug-found-values');
    await found.waitFor();
    const foundText = await found.innerText();
    for (const text of ['Make my plan and messages useful context',
                        'Agent root said to juniper',
                        'Please make your current plan and the messages',
                        'Show Sean which function renders each block']) {
      assert(foundText.includes(text), `found values must preview ${text}`);
    }
    const rows = found.locator('.seon-debug-found-value');
    const rowCount = await rows.count();
    assert(rowCount > 0, 'found values must exist');
    for (const row of await rows.all()) {
      assert.equal(await row.locator('.seon-debug-projection-column').count(), 2);
    }
    const rootMessage = rows.filter({hasText:
      'Please make your current plan and the messages you receive easy to understand together.'});
    assert.equal(await rootMessage.count(), 1, 'the first root message must identify one row');
    const rootMessageAI = rootMessage.locator('.seon-debug-projection-column').nth(0);
    const rootMessageHTML = rootMessage.locator('.seon-debug-projection-column').nth(1);
    assert((await rootMessageAI.innerText()).includes(
      'Agent root said to juniper: Please make your current plan and the messages'),
    'the existing exact AI message sentence must remain visible');
    assert.equal(await rootMessageHTML.locator('.seon-message-meta').count(), 1,
      'HTML must expose one message metadata header');
    const messageMeta = await rootMessageHTML.locator('.seon-message-meta').innerText();
    for (const text of ['Agent root', '→', 'Agent juniper', '2026-09-06T19:35:00Z']) {
      assert(messageMeta.includes(text), `message metadata must include ${text}`);
    }
    assert(!messageMeta.includes('#inst'), 'human metadata must omit Clojure literal syntax');
    assert.equal(await rootMessageHTML.locator('.seon-message-content').innerText(),
      'Please make your current plan and the messages you receive easy to understand together. ' +
      'Start by inspecting the data connected to your agent entity.',
    'HTML authored content must be separate and free of attribution prefixes');
    const plan = rows.filter({hasText: 'Make my plan and messages useful context'}).filter({
      has: page.locator('.my-plan-item'),
    }).first();
    assert.equal(await plan.locator('.my-plan-id').innerText(), '"juniper/understand-context"');
    assert((await plan.locator('.my-plan-expected').innerText()).startsWith('Expected:'),
      'the plan criterion must retain its explicit label');
    const screenshotDirectory = 'tmp/agent-preview-baseline';
    fs.mkdirSync(screenshotDirectory, {recursive: true});
    await plan.screenshot({path: `${screenshotDirectory}/juniper-plan.png`});
    await rootMessage.screenshot({path: `${screenshotDirectory}/juniper-root-message.png`});
    assert(!(await page.locator('#debug-selection').innerText()).includes('No applicable renderer.'));
    const alternatives = page.locator('.seon-debug-alternative-renderers');
    if (await alternatives.count()) {
      assert.equal(await alternatives.getAttribute('open'), null);
      await alternatives.locator(':scope > summary').click();
      assert.notEqual(await alternatives.getAttribute('open'), null);
      assert((await alternatives.locator('.seon-debug-candidate').count()) > 0);
      assert(!(await alternatives.innerText()).includes('rejected'));
    }
    const readyMs = Date.now() - started;
    if (process.argv[3]) await page.screenshot({path: process.argv[3], fullPage: false});
    assert.deepEqual(errors, []);
    console.log(JSON.stringify({url: page.url(), readyMs, rowCount, pairedIdentity: true,
      planAndMessagesVisible: true, messageHTMLSeparated: true,
      screenshots: [`${screenshotDirectory}/juniper-plan.png`,
                    `${screenshotDirectory}/juniper-root-message.png`],
      applicableAlternativesOnly: true, errors}));
  } finally {
    await browser.close();
  }
})().catch(error => {console.error(error); process.exitCode = 1;});
