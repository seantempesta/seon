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
      ...(Number.isInteger(data?.level) ? { level: data.level } : {}),
      // A walk tree node: its hover text, the groups on its path, and whether it is a group or a requery.
      ...(typeof data?.full === 'string' ? { full: data.full } : {}),
      ...(Array.isArray(data?.groups) ? { groups: data.groups } : {}),
      ...(data?.group === true ? { group: true, expanded: data?.expanded === true } : {}),
      ...(data?.more === true ? { more: true } : {}),
    },
  }))
  const edges = elements.edges.map(({ data } = {}) => ({
    data: {
      id: requiredString(data?.id, 'edge data.id'),
      source: requiredString(data?.source, 'edge data.source'),
      target: requiredString(data?.target, 'edge data.target'),
      attribute: requiredString(data?.attribute, 'edge data.attribute'),
      reverse: data?.reverse === true,
      ...(typeof data?.kind === 'string' ? { kind: data.kind } : {}),
      ...(typeof data?.label === 'string' ? { label: data.label } : {}),
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

  // A walk model's nodes carry their level; the page declares one colour per level.
  const levels = [0, 1, 2, 3].flatMap((level) => {
    const value = css.getPropertyValue(`--seon-graph-level-${level}`).trim()
    // A walk's rings are unlabelled; the selected node names itself.
    return value ? [{ selector: `node[level = ${level}]`, style: { 'background-color': value } }] : []
  })
  // The walk tree reads left to right: each label beside its node, one hop per column.
  const tree = [
    { selector: 'node[display]', style: { label: 'data(display)', 'text-valign': 'center', 'text-halign': 'right', 'text-margin-x': 6, 'text-wrap': 'none', 'font-size': 12, width: 14, height: 14 } },
    // An outline edge drops from its parent's dot and turns into the child's row, clear of every label.
    { selector: 'edge[kind]', style: { 'curve-style': 'taxi', 'taxi-direction': 'vertical', 'taxi-turn': '100%', 'taxi-turn-min-distance': 4 } },
    { selector: 'node[?group]', style: { shape: 'round-rectangle', width: 18, height: 12, 'font-weight': 'bold' } },
    { selector: 'node[?more]', style: { shape: 'ellipse', width: 8, height: 8, 'font-style': 'italic' } },
    { selector: '.seon-hidden', style: { display: 'none' } },
    { selector: 'edge[kind = "component"]', style: { width: 3.5, 'source-arrow-shape': 'diamond', 'source-arrow-color': inspection } },
    { selector: 'edge[kind = "optional"]', style: { 'line-style': 'dotted' } },
    { selector: 'edge[kind = "attachment"]', style: { 'line-style': 'dashed' } },
    { selector: 'edge[kind = "reverse"]', style: { 'line-style': 'dashed', opacity: 0.4 } },
    { selector: 'edge[kind = "value"]', style: { 'line-style': 'dotted', opacity: 0.4 } },
  ]

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
        label: '',
        'font-size': 9,
        'text-background-color': base,
        'text-background-opacity': 0.9,
        'text-background-padding': 2,
        width: 1.5,
      },
    },
    ...levels,
    { selector: 'edge[?reverse]', style: { 'line-style': 'dashed' } },
    ...tree,
    {
      selector: ':selected',
      style: {
        'background-color': inspection,
        'border-color': text,
        'line-color': text,
        'target-arrow-color': text,
      },
    },
    { selector: 'node[level]:selected', style: { 'z-index': 10 } },
    {
      selector: 'edge:selected',
      style: {
        label: 'data(attribute)',
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
        if (edge.data.label) current.data('label', edge.data.label)
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

  const walk = model.nodes.every(({ data }) => Number.isInteger(data.level))
  if (model.nodes.every(({ data }) => Array.isArray(data.groups))) {
    if (firstModel) cy.nodes('[?expanded]').forEach((node) => node.data('open', true))
    layoutTree(cy, model.selected)
  } else if (firstModel && cy.elements().nonempty()) {
    cy.layout({
      name: 'concentric',
      fit: true,
      padding: 32,
      animate: false,
      // A walk model is one ring per level around its root; labels would push the rings apart.
      nodeDimensionsIncludeLabels: !walk,
      minNodeSpacing: walk ? 6 : 10,
      concentric: (node) => node.id() === model.selected ? 9 : (walk ? 8 - node.data('level') : 0),
      levelWidth: () => 1,
    }).run()
  } else {
    cy.viewport({ pan, zoom })
  }
}

// A walk tree node shows once every group on its path is open. The shown part is an outline: one row per node in
// depth-first order from the root (children in model order, which is graph order), one column per hop, so no two
// labels share a row.
const layoutTree = (cy, selected) => {
  const open = new Set(cy.nodes('[?open]').map((node) => node.id()))
  cy.batch(() => {
    cy.nodes().forEach((node) => node.toggleClass('seon-hidden', !(node.data('groups') || []).every((g) => open.has(g))))
  })
  const shown = cy.nodes().not('.seon-hidden')
  const rank = new Map(cy.nodes().map((node, index) => [node.id(), index]))
  const position = new Map()
  let row = 0
  // A child's row reads `attribute  label`: the edge that reached it names the relationship, so no edge label is drawn.
  const visit = (node, depth, via) => {
    position.set(node.id(), { x: depth * 200, y: row++ * 30 })
    node.data('display', via ? `${via}  ${node.data('label')}` : node.data('label'))
    node.connectedEdges().map((edge) => [edge, edge.source().same(node) ? edge.target() : edge.source()])
      .filter(([, next]) => !next.hasClass('seon-hidden') && !position.has(next.id()))
      .sort(([, a], [, b]) => rank.get(a.id()) - rank.get(b.id()))
      .forEach(([edge, next]) => { if (!position.has(next.id())) visit(next, depth + 1, edge.data('label')) })
  }
  visit(cy.getElementById(selected), 0)
  shown.forEach((node) => { if (!position.has(node.id())) position.set(node.id(), { x: 0, y: row++ * 30 }) })
  shown.layout({ name: 'preset', positions: (node) => position.get(node.id()), fit: false, animate: false }).run()
  cy.fit(shown, 24)
  if (cy.zoom() < 0.8) { cy.zoom(0.8); cy.pan({ x: 24, y: 24 }) }
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
          : 'Select a node or a reference assertion for details.')
      } catch (error) {
        show(`Graph unavailable: ${error.message}`, true)
      }
    }
    const onNodeTap = (event) => {
      if (event.target.data('group')) {
        event.target.data('open', !event.target.data('open'))
        layoutTree(cy, readModel(script).selected)
        show(event.target.data('full'))
        return
      }
      const href = event.target.data('href')
      const block = href.startsWith('#') && document.getElementById(href.slice(1))
      if (block) {
        block.open = true
        block.scrollIntoView({ block: 'start' })
        show(event.target.data('label'))
      } else if (href) window.location.assign(href)
    }
    const onEdgeTap = (event) => {
      show(edgeDescription(event.target))
    }

    cy.on('tap', 'node', onNodeTap)
    cy.on('tap', 'edge', onEdgeTap)
    const onNodeOver = (event) => show(event.target.data('full') || event.target.data('label'))
    cy.on('mouseover', 'node', onNodeOver)
    const observer = new MutationObserver(update)
    observer.observe(script, { childList: true, characterData: true, subtree: true })
    update()

    return () => {
      observer.disconnect()
      cy.off('tap', 'node', onNodeTap)
      cy.off('tap', 'edge', onEdgeTap)
      cy.off('mouseover', 'node', onNodeOver)
      cy.destroy()
    }
  },
})
