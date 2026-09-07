// Inspect the live Juniper context UI; save the actual browser output for review.
// Usage: node this-file.cjs URL OUTPUT_PREFIX
const {chromium} = require('playwright');
const fs = require('node:fs/promises');

(async () => {
  const browser = await chromium.launch({
    executablePath: '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome',
    headless: true,
  });
  try {
    const page = await browser.newPage({viewport: {width: 1440, height: 1100}});
    const errors = [];
    page.on('pageerror', error => errors.push(error.message));
    const response = await page.goto(process.argv[2], {waitUntil: 'domcontentloaded', timeout: 30000});
    await page.locator('.seon-agent-identity-entry').first().waitFor({timeout: 15000});
    await page.getByText('Context selection', {exact: true}).waitFor({timeout: 15000});
    let readinessError;
    try {
      await page.getByRole('button', {name: 'Lock preview into context', exact: true})
        .waitFor({timeout: 30000});
    } catch (error) { readinessError = error.message; }
    const text = await page.locator('body').innerText();
    await fs.writeFile(`${process.argv[3]}.txt`, text);
    await page.screenshot({path: `${process.argv[3]}.png`, fullPage: false});
    console.log(JSON.stringify({status: response.status(), url: page.url(), errors,
      textFile: `${process.argv[3]}.txt`, screenshot: `${process.argv[3]}.png`,
      forms: await page.locator('form').count(), buttons: await page.getByRole('button').allTextContents(),
      readinessError}));
    if (readinessError || errors.length) process.exitCode = 1;
  } finally {
    await browser.close();
  }
})().catch(error => {console.error(error); process.exitCode = 1;});
