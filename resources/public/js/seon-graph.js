import { attribute } from './datastar.js'

const MODEL_SELECTOR = 'script[type="application/json"][data-graph-model]'
const CANVAS_SELECTOR = '[data-graph-canvas][data-ignore-morph]'
const DETAIL_SELECTOR = '[data-graph-detail]'

const own = (value, key) => Object.prototype.hasOwnProperty.call(value, key)

const requiredString = (value, member) => {
  if (typeof value !== 'string' || value.length === 0) {
    throw new Error(`${member} must be a non-empty string`)
  }
  return value
}

const readModel = (script) => {
  const model = JSON.parse(script.textContent)
  const elements = model?.elements
  if (!elements || !Array.isArray(elements.nodes) || !Array.isArray(elements.edges)) {
    throw new Error('elements.nodes and elements.edges must be arrays')
  }

  const nodes = elements.nodes.map(({ data } = {}) => ({
    data: {
      id: requiredString(data?.id, 'node data.id'),
      label: requiredString(data?.label, 'node data.label'),
      href: requiredString(data?.href, 'node data.href'),
    },
  }))
  const edges = elements.edges.map(({ data } = {}) => ({
    data: {
      id: requiredString(data?.id, 'edge data.id'),
      source: requiredString(data?.source, 'edge data.source'),
      target: requiredString(data?.target, 'edge data.target'),
      attribute: requiredString(data?.attribute, 'edge data.attribute'),
    },
  }))
  const selected = requiredString(model['seon.graph/selected'], 'seon.graph/selected')
  if (!own(model, 'seon.graph/snapshot')) {
    throw new Error('seon.graph/snapshot is required')
  }

  const nodeIds = new Set(nodes.map(({ data }) => data.id))
  const elementIds = new Set()
  for (const { data } of [...nodes, ...edges]) {
    if (elementIds.has(data.id)) {
      throw new Error(`duplicate element id ${data.id}`)
    }
    elementIds.add(data.id)
  }
  if (!nodeIds.has(selected)) {
    throw new Error('seon.graph/selected must identify a returned node')
  }
  for (const { data } of edges) {
    if (!nodeIds.has(data.source) || !nodeIds.has(data.target)) {
      throw new Error(`edge ${data.id} must reference returned nodes`)
    }
  }

  return { nodes, edges, selected }
}

const positionsFor = (model, canvas) => {
  const incoming = new Set()
  const outgoing = new Set()
  for (const { data } of model.edges) {
    if (data.source === model.selected) outgoing.add(data.target)
    if (data.target === model.selected) incoming.add(data.source)
  }

  const groups = { incoming: [], outgoing: [], both: [], other: [] }
  for (const { data } of model.nodes) {
    if (data.id === model.selected) continue
    const isIncoming = incoming.has(data.id)
    const isOutgoing = outgoing.has(data.id)
    const group = isIncoming && isOutgoing
      ? 'both'
      : isIncoming
        ? 'incoming'
        : isOutgoing
          ? 'outgoing'
          : 'other'
    groups[group].push(data.id)
  }
  for (const ids of Object.values(groups)) ids.sort()

  const horizontal = Math.max(140, Math.min(280, canvas.clientWidth * 0.32))
  const position = new Map([[model.selected, { x: 0, y: 0 }]])
  const place = (ids, x, yOffset = 0) => {
    ids.forEach((id, index) => {
      position.set(id, {
        x,
        y: yOffset + (index - (ids.length - 1) / 2) * 88,
      })
    })
  }
  place(groups.incoming, -horizontal)
  place(groups.outgoing, horizontal)
  place(groups.both, 0, 140)
  place(groups.other, 0, -140)
  return position
}

const graphStyle = (section) => {
  const css = getComputedStyle(section)
  const color = (name) => {
    const value = css.getPropertyValue(name).trim()
    if (!value) throw new Error(`CSS color ${name} is unavailable`)
    return value
  }
  const inspection = color('--color-inspection')
  const base = color('--color-base-800')
  const text = color('--color-text-100')

  return [
    {
      selector: 'node',
      style: {
        'background-color': base,
        'border-color': inspection,
        'border-width': 1,
        color: text,
        label: 'data(label)',
        'font-size': 11,
        'text-wrap': 'wrap',
        'text-max-width': 120,
        'text-valign': 'bottom',
        'text-margin-y': 7,
      },
    },
    {
      selector: 'edge',
      style: {
        'curve-style': 'bezier',
        'line-color': inspection,
        'target-arrow-color': inspection,
        'target-arrow-shape': 'triangle',
        color: text,
        label: 'data(attribute)',
        'font-size': 9,
        'text-background-color': base,
        'text-background-opacity': 0.9,
        'text-background-padding': 2,
        width: 1.5,
      },
    },
    {
      selector: ':selected',
      style: {
        'background-color': inspection,
        'border-color': text,
        'line-color': text,
        'target-arrow-color': text,
      },
    },
  ]
}

const reconcile = (cy, model, canvas, firstModel) => {
  const nodes = new Map(model.nodes.map((node) => [node.data.id, node]))
  const edges = new Map(model.edges.map((edge) => [edge.data.id, edge]))
  const positions = positionsFor(model, canvas)
  const pan = cy.pan()
  const zoom = cy.zoom()
  const selectedIds = cy.elements().filter((element) => element.selected()).map((element) => element.id())

  cy.batch(() => {
    cy.edges().forEach((edge) => {
      const next = edges.get(edge.id())
      if (!next || edge.source().id() !== next.data.source || edge.target().id() !== next.data.target) {
        edge.remove()
      }
    })
    cy.nodes().forEach((node) => {
      if (!nodes.has(node.id())) node.remove()
    })

    for (const node of model.nodes) {
      const current = cy.getElementById(node.data.id)
      if (current.empty()) {
        cy.add({ ...node, position: positions.get(node.data.id) })
      } else {
        current.data('label', node.data.label)
        current.data('href', node.data.href)
      }
    }
    for (const edge of model.edges) {
      const current = cy.getElementById(edge.data.id)
      if (current.empty()) {
        cy.add(edge)
      } else {
        current.data('attribute', edge.data.attribute)
      }
    }

    cy.elements().unselect()
    let restored = false
    for (const id of selectedIds) {
      const element = cy.getElementById(id)
      if (element.nonempty()) {
        element.select()
        restored = true
      }
    }
    if (!restored) cy.getElementById(model.selected).select()
  })

  if (firstModel && cy.elements().nonempty()) {
    cy.fit(cy.elements(), 32)
  } else {
    cy.viewport({ pan, zoom })
  }
}

const edgeDescription = (edge) => {
  const data = edge.data()
  return `${data.source} —[${data.attribute}]→ ${data.target}`
}

attribute({
  name: 'seon-graph',
  requirement: { key: 'denied', value: 'denied' },
  apply({ el }) {
    const canvas = el.querySelector(CANVAS_SELECTOR)
    const script = el.querySelector(MODEL_SELECTOR)
    const detail = el.querySelector(DETAIL_SELECTOR)
    const show = (message, unavailable = false) => {
      const currentDetail = el.querySelector(DETAIL_SELECTOR) || detail
      if (currentDetail) {
        currentDetail.setAttribute('role', 'status')
        currentDetail.setAttribute('aria-live', 'polite')
        currentDetail.textContent = message
      }
      if (canvas) canvas.hidden = unavailable
    }

    if (!canvas || !script || !detail) {
      show('Graph unavailable: required graph markup is missing.', true)
      return
    }
    if (typeof globalThis.cytoscape !== 'function') {
      show('Graph unavailable: Cytoscape did not load.', true)
      return
    }

    let cy
    try {
      cy = globalThis.cytoscape({
        container: canvas,
        elements: [],
        layout: { name: 'preset' },
        style: graphStyle(el),
        minZoom: 0.2,
        maxZoom: 3,
      })
    } catch (error) {
      show(`Graph unavailable: ${error.message}`, true)
      return
    }

    let firstModel = true
    const update = () => {
      try {
        const model = readModel(script)
        reconcile(cy, model, canvas, firstModel)
        firstModel = false
        const selectedEdge = cy.edges().filter((edge) => edge.selected()).first()
        show(selectedEdge.nonempty()
          ? edgeDescription(selectedEdge)
          : 'Select a reference assertion for details.')
      } catch (error) {
        show(`Graph unavailable: ${error.message}`, true)
      }
    }
    const onNodeTap = (event) => {
      const href = event.target.data('href')
      if (href) window.location.assign(href)
    }
    const onEdgeTap = (event) => {
      show(edgeDescription(event.target))
    }

    cy.on('tap.seonGraph', 'node', onNodeTap)
    cy.on('tap.seonGraph', 'edge', onEdgeTap)
    const observer = new MutationObserver(update)
    observer.observe(script, { childList: true, characterData: true, subtree: true })
    update()

    return () => {
      observer.disconnect()
      cy.off('tap.seonGraph', 'node', onNodeTap)
      cy.off('tap.seonGraph', 'edge', onEdgeTap)
      cy.destroy()
    }
  },
})
