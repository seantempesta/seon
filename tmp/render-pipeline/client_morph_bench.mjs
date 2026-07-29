// Real-browser Datastar morph experiment.
//
// Run through the repository's Node REPL (which supplies Playwright):
//   await import('./tmp/render-pipeline/client_morph_bench.mjs')
//
// The reported interval begins inside Datastar's DOMParser.parseFromString
// call and ends in the MutationObserver microtask after its synchronous
// ID-aware morph. Localhost transport is deliberately excluded.

import { chromium } from 'playwright'
import http from 'node:http'
import { readFile } from 'node:fs/promises'

const datastar = await readFile('resources/public/js/datastar.js')

const eventRow = (index, token) =>
  index % 2 === 0
    ? `<div id="event-${index}" class="py-1 flex"><div class="seon-bubble max-w-[78%] min-w-0 rounded px-2.5 py-1.5 mr-auto bg-base-900 border border-base-800"><div class="flex items-baseline gap-2 flex-wrap"><span class="text-xs font-mono font-semibold">agent-a</span></div><div class="markdown mt-0.5 min-w-0">a reply with &lt;angle&gt; &amp; ampersand content, number ${index} ${
        index === 0 ? `<span id="active-token">${token}</span>` : ''
      }</div></div></div>`
    : `<div id="event-${index}" class="agent-activity flex items-baseline gap-1.5 px-2 py-1 text-xs min-w-0"><span class="font-medium text-text-400 truncate">ran my.agents.agent-a/step-${index}</span><span class="font-mono text-text-600 shrink-0">12ms</span><span class="font-mono shrink-0 text-success">done</span></div>`

const transcript = (eventCount, token) =>
  `<section id="surface-transcript" class="seon-transcript">${Array.from(
    { length: eventCount },
    (_, index) => eventRow(index, token),
  ).join('')}</section>`

const pageContent = (eventCount, token) =>
  `<main id="app-view"><header id="surface-header"><span>◆</span><span>seon</span><span>3 agents</span></header><section id="surface-canvas"><div id="card-0">card 0</div><div id="card-1">card 1</div></section><section id="surface-problems"><span>no problems</span></section>${transcript(
    eventCount,
    token,
  )}</main>`

const documentHtml = (eventCount) =>
  `<!doctype html><html><head><script type="module" src="/datastar.js"></script></head><body>${pageContent(
    eventCount,
    'initial',
  )}<div style="display:none" data-init="@get('/feed', {retryMaxCount: Infinity, openWhenHidden: false})"></div></body></html>`

let feedResponse
let feedReadyResolve
let feedReady = new Promise((resolve) => {
  feedReadyResolve = resolve
})

const server = http.createServer((request, response) => {
  const url = new URL(request.url, 'http://127.0.0.1')
  if (url.pathname === '/datastar.js') {
    response.writeHead(200, { 'content-type': 'text/javascript' })
    response.end(datastar)
    return
  }
  if (url.pathname === '/feed') {
    response.writeHead(200, {
      'cache-control': 'no-cache',
      'content-type': 'text/event-stream',
      connection: 'keep-alive',
    })
    response.flushHeaders()
    feedResponse = response
    feedReadyResolve()
    request.on('close', () => {
      if (feedResponse === response) feedResponse = undefined
    })
    return
  }
  const eventCount = Number(url.searchParams.get('events') || 250)
  response.writeHead(200, { 'content-type': 'text/html; charset=utf-8' })
  response.end(documentHtml(eventCount))
})

await new Promise((resolve) =>
  server.listen(0, '127.0.0.1', resolve),
)
const address = server.address()
const origin = `http://127.0.0.1:${address.port}`

const browser = await chromium.launch({
  headless: true,
  executablePath:
    '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome',
})
const page = await browser.newPage()

const percentile = (sorted, proportion) =>
  sorted[Math.min(sorted.length - 1, Math.floor(proportion * sorted.length))]

const summarize = (samples) => {
  const sorted = [...samples].sort((a, b) => a - b)
  return {
    trials: sorted.length,
    p50Ms: percentile(sorted, 0.5),
    p95Ms: percentile(sorted, 0.95),
    p99Ms: percentile(sorted, 0.99),
    maxMs: sorted.at(-1),
  }
}

const installProbe = async () => {
  await page.evaluate(() => {
    window.__morphSamples = []
    window.__morphStarted = undefined
    const original = DOMParser.prototype.parseFromString
    DOMParser.prototype.parseFromString = function (...args) {
      window.__morphStarted = performance.now()
      return original.apply(this, args)
    }
    new MutationObserver(() => {
      if (window.__morphStarted !== undefined) {
        window.__morphSamples.push(
          performance.now() - window.__morphStarted,
        )
        window.__morphStarted = undefined
      }
    }).observe(document.getElementById('app-view'), {
      attributes: true,
      characterData: true,
      childList: true,
      subtree: true,
    })
  })
}

const patchEvent = (html) =>
  `event: datastar-patch-elements\ndata: elements ${html}\n\n`

const runCase = async ({ eventCount, mode, trials }) => {
  feedReady = new Promise((resolve) => {
    feedReadyResolve = resolve
  })
  await page.goto(`${origin}/?events=${eventCount}`)
  await feedReady
  await installProbe()
  for (let index = 0; index < trials; index += 1) {
    const token = `token-${mode}-${index}`
    const html =
      mode === 'page'
        ? pageContent(eventCount, token)
        : mode === 'block'
          ? transcript(eventCount, token)
          : `<span id="active-token">${token}</span>`
    const expected = index + 1
    feedResponse.write(patchEvent(html))
    await page.waitForFunction(
      (count) => window.__morphSamples.length >= count,
      expected,
      { timeout: 10000 },
    )
  }
  const samples = await page.evaluate(() => window.__morphSamples)
  const html =
    mode === 'page'
      ? pageContent(eventCount, 'measured')
      : mode === 'block'
        ? transcript(eventCount, 'measured')
        : '<span id="active-token">measured</span>'
  return {
    events: eventCount,
    target: mode,
    patchBytes: Buffer.byteLength(patchEvent(html)),
    ...summarize(samples),
  }
}

const cases = [
  { eventCount: 250, mode: 'page', trials: 200 },
  { eventCount: 250, mode: 'block', trials: 200 },
  { eventCount: 250, mode: 'leaf', trials: 200 },
  { eventCount: 1000, mode: 'block', trials: 150 },
  { eventCount: 1000, mode: 'leaf', trials: 150 },
  { eventCount: 2500, mode: 'block', trials: 100 },
  { eventCount: 2500, mode: 'leaf', trials: 100 },
  { eventCount: 5000, mode: 'block', trials: 60 },
  { eventCount: 5000, mode: 'leaf', trials: 60 },
]

const results = []
try {
  for (const benchmarkCase of cases) {
    results.push(await runCase(benchmarkCase))
  }
  console.log(
    JSON.stringify(
      {
        environment: {
          browser: await browser.version(),
          datastarBanner: datastar.toString('utf8', 0, 27),
        },
        results,
      },
      null,
      2,
    ),
  )
} finally {
  await page.close()
  await browser.close()
  await new Promise((resolve) => server.close(resolve))
}
