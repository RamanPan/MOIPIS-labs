const $ = (id) => document.getElementById(id);
let library;

function cytoscapeLibrary() {
  if (globalThis.cytoscape) return Promise.resolve(globalThis.cytoscape);
  if (!library) library = new Promise((resolve, reject) => {
    const script = document.createElement('script');
    const timeout = setTimeout(() => reject(new Error('Истекло время загрузки графа.')), 10000);
    script.src = '/webjars/cytoscape/3.34.1/dist/cytoscape.min.js';
    script.onload = () => { clearTimeout(timeout); globalThis.cytoscape ? resolve(globalThis.cytoscape) : reject(new Error('Библиотека недоступна.')); };
    script.onerror = () => { clearTimeout(timeout); reject(new Error('Не удалось загрузить библиотеку графа.')); };
    document.head.append(script);
  });
  return library;
}

function item(tag, text, className = '') {
  const element = document.createElement(tag);
  element.textContent = text;
  element.className = className;
  return element;
}

export class ProcessGraph {
  constructor() {
    this.instance = null;
    this.version = 0;
    this.fingerprint = '';
    $('graph-fit').addEventListener('click', () => this.instance?.fit(undefined, 35));
    for (const [id, factor] of [['graph-zoom-in', 1.25], ['graph-zoom-out', .8]]) {
      $(id).addEventListener('click', () => {
        if (this.instance) this.instance.zoom({ level: Math.max(.1, Math.min(4, this.instance.zoom() * factor)),
          renderedPosition: { x: this.instance.width() / 2, y: this.instance.height() / 2 } });
      });
    }
    this.resize = new ResizeObserver(() => this.instance?.resize());
    this.resize.observe($('process-graph'));
  }

  destroy() {
    ++this.version;
    this.instance?.destroy();
    this.instance = null;
    this.fingerprint = '';
  }

  suspend() { ++this.version; }

  unavailable(message) {
    this.destroy();
    $('process-graph').hidden = true;
    $('graph-message').textContent = message;
    $('graph-message').hidden = false;
    $('graph-state-list').replaceChildren();
    $('graph-transition-list').replaceChildren();
    $('graph-counts').textContent = 'Схема недоступна';
    this.tools(false);
  }

  tools(enabled) { for (const id of ['graph-fit', 'graph-zoom-in', 'graph-zoom-out']) $(id).disabled = !enabled; }

  select(state, process) {
    const attributes = [state.initial ? 'начальное' : '', state.final ? 'конечное' : '',
      state.id === process.currentStateId ? 'текущее' : ''].filter(Boolean);
    $('graph-selection').textContent = (state.title || state.id) + (attributes.length ? ' · ' + attributes.join(', ') : '')
      + '. Выбор состояния не выполняет переход.';
  }

  async render(process, history = []) {
    const version = ++this.version;
    const visited = new Set();
    for (const entry of history) {
      if (entry.operation === 'CREATE' && entry.details?.initialState) visited.add(entry.details.initialState);
      if (entry.operation === 'ACTION') {
        if (entry.details?.oldState) visited.add(entry.details.oldState);
        if (entry.details?.newState) visited.add(entry.details.newState);
      }
    }
    const states = new Map(process.states.map(state => [state.id, state]));
    $('graph-counts').textContent = 'Состояний: ' + process.states.length + ' · Переходов: ' + process.transitions.length;
    $('process-graph').setAttribute('aria-label', 'Граф процесса: ' + process.states.length + ' состояний, ' + process.transitions.length + ' переходов');
    $('graph-state-list').replaceChildren(...process.states.map(state => {
      const button = item('button', state.title || state.id, 'button button-small graph-state-button');
      button.type = 'button'; button.dataset.stateId = state.id;
      button.setAttribute('aria-pressed', String(state.id === process.currentStateId));
      button.addEventListener('click', () => this.select(state, process));
      return button;
    }));
    $('graph-transition-list').replaceChildren(...process.transitions.map(transition => item('li',
      (states.get(transition.from)?.title || transition.from) + ' → ' + (transition.actionTitle || transition.action)
      + ' → ' + (states.get(transition.to)?.title || transition.to))));
    $('graph-selection').textContent = 'Выберите состояние, чтобы посмотреть информацию. Переход выполняется только действием.';
    $('graph-message').textContent = 'Подготовка графа…';
    $('graph-message').hidden = false;
    this.tools(false);
    if (!process.states.length) {
      $('graph-message').textContent = 'В шаблоне нет состояний.';
      $('process-graph').hidden = true;
      return;
    }
    try {
      const cytoscape = await cytoscapeLibrary();
      if (version !== this.version) return;
      $('process-graph').hidden = false;
      const fingerprint = JSON.stringify([process.templateId, process.states, process.transitions]);
      if (this.fingerprint !== fingerprint || !this.instance) {
        this.instance?.destroy();
        const columns = Math.ceil(Math.sqrt(process.states.length));
        this.instance = cytoscape({
          container: $('process-graph'), minZoom: .1, maxZoom: 4, wheelSensitivity: .2,
          autoungrabify: true, boxSelectionEnabled: false,
          elements: [
            ...process.states.map((state, index) => ({ data: { id: 'state:' + state.id, stateId: state.id, label: state.title || state.id },
              position: { x: index % columns * 230, y: Math.floor(index / columns) * 170 },
              classes: [state.initial ? 'initial' : '', state.final ? 'terminal' : ''].filter(Boolean).join(' ') })),
            ...process.transitions.map((transition, index) => ({ data: { id: 'transition:' + index,
              source: 'state:' + transition.from, target: 'state:' + transition.to,
              label: transition.actionTitle || transition.action, actionId: transition.action } }))
          ],
          style: [
            { selector: 'node', style: { shape: 'round-rectangle', width: 155, height: 62,
              'background-color': '#f4f5fa', 'border-color': '#cdd2e1', 'border-width': 1.5,
              label: 'data(label)', color: '#48516a', 'font-family': 'Segoe UI, sans-serif', 'font-size': 12,
              'text-wrap': 'wrap', 'text-max-width': '137px', 'text-valign': 'center', 'text-halign': 'center' } },
            { selector: 'edge', style: { width: 1.5, 'line-color': '#bcc3d4', 'target-arrow-color': '#a4adc2',
              'target-arrow-shape': 'triangle', 'curve-style': 'bezier', 'control-point-step-size': 55,
              'loop-direction': '-45deg', 'loop-sweep': '70deg', label: 'data(label)', 'font-size': 9,
              'font-family': 'Segoe UI, sans-serif', color: '#737d92', 'text-wrap': 'wrap', 'text-max-width': '125px',
              'text-background-color': '#ffffff', 'text-background-opacity': .9, 'text-background-padding': '3px',
              'text-rotation': 'autorotate' } },
            { selector: 'node.initial', style: { 'border-style': 'dashed', 'border-width': 3, 'border-color': '#8d78cf' } },
            { selector: 'node.terminal', style: { 'border-width': 5, 'border-color': '#4e927c' } },
            { selector: 'node.visited', style: { 'background-color': '#eaf7f1', 'border-color': '#8cc5ae', color: '#336e59' } },
            { selector: 'node.current', style: { 'background-color': '#6c4ce5', 'border-color': '#482ab4', color: '#ffffff', 'border-width': 3 } },
            { selector: 'node.current.terminal', style: { 'border-width': 6, 'border-color': '#46a57b' } },
            { selector: 'node:selected', style: { 'overlay-opacity': .12, 'overlay-color': '#6c4ce5', 'overlay-padding': 5 } }
          ],
          layout: { name: 'breadthfirst', directed: true, animate: false, fit: true, padding: 35,
            roots: process.states.filter(state => state.initial).map(state => 'state:' + state.id),
            boundingBox: { x1: 0, y1: 0, w: $('process-graph').clientHeight - 70, h: $('process-graph').clientWidth - 70 },
            transform: (node, position) => ({ x: position.y, y: position.x }),
            spacingFactor: 1.1, avoidOverlap: true, nodeDimensionsIncludeLabels: true }
        });
        this.instance.on('tap', 'node', event => {
          const currentProcess = this.process;
          const state = currentProcess.states.find(entry => entry.id === event.target.data('stateId'));
          if (state) this.select(state, currentProcess);
        });
        this.fingerprint = fingerprint;
      }
      this.process = process;
      this.instance.batch(() => {
        this.instance.nodes().forEach(element => {
          element.toggleClass('visited', visited.has(element.data('stateId')));
          element.toggleClass('current', element.data('stateId') === process.currentStateId);
        });
      });
      this.instance.resize();
      $('graph-message').hidden = true;
      this.tools(true);
    } catch {
      if (version !== this.version) return;
      this.instance?.destroy(); this.instance = null;
      $('process-graph').hidden = true;
      $('graph-message').textContent = 'Визуальный граф недоступен. Все состояния и переходы показаны ниже; действия доступны в своей панели.';
      $('graph-message').hidden = false;
      $('graph-fallback').open = true;
    }
  }
}
