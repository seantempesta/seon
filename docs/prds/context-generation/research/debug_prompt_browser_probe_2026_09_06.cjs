// Read-only live prompt comparison: no model call or synthetic capture.
const {chromium}=require('playwright');
const assert=require('node:assert/strict');
(async()=>{
 const browser=await chromium.launch({executablePath:'/Applications/Google Chrome.app/Contents/MacOS/Google Chrome',headless:true,timeout:15000});
 try {
  const page=await browser.newPage(); page.setDefaultTimeout(30000);
  const errors=[]; page.on('pageerror',e=>errors.push(e.message));
  await page.goto(process.argv[2],{waitUntil:'domcontentloaded'});
  await page.getByText('agent prompt comparison',{exact:true}).click();
  try { await page.waitForFunction(()=>document.querySelector('#debug-ai-root')?.innerText.includes('prompt comparison')); } catch (error) { console.error(JSON.stringify({url:page.url(),text:(await page.locator('body').innerText()).slice(0,6000)})); throw error; }
  const panes=await page.locator('#debug-ai-root .seon-debug-prompt-pane').allTextContents();
  assert.equal(panes.length,2);
  assert(panes[0].includes('historical captured prompt'));
  assert(panes[1].includes('newly computed prospective prompt'));
  const preview=await page.locator('#debug-ai-root .seon-debug-prompt-pane').nth(1).locator('pre').textContent();
  assert(preview.trim().length>0,'live prospective prompt must be nonempty');
  assert(!panes[1].includes('database basis unknown'));
  assert.deepEqual(errors,[]);
  console.log(JSON.stringify({panes:panes.map(x=>x.slice(0,650)),prospectiveCharacters:preview.length,errors}));
 } finally {await browser.close();}
})().catch(e=>{console.error(e);process.exitCode=1;});
