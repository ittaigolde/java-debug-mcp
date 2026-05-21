'use strict';

const state = {
  attached: false,
  vm: null,
  threads: [],
  breakpoints: [],
  events: [],
  notes: [],
  gate: { armed: false, buffered: false },
  focusedThread: null,
  focusedFrames: null,
};

const $ = (id) => document.getElementById(id);

function setStatus(text, cls) {
  $('status-text').textContent = text;
  const dot = $('status-dot');
  dot.classList.remove('attached', 'detached', 'error');
  if (cls) dot.classList.add(cls);
}

function fmtTime(ms) {
  if (!ms) return '';
  const d = new Date(ms);
  const hh = String(d.getHours()).padStart(2, '0');
  const mm = String(d.getMinutes()).padStart(2, '0');
  const ss = String(d.getSeconds()).padStart(2, '0');
  const mss = String(d.getMilliseconds()).padStart(3, '0');
  return `${hh}:${mm}:${ss}.${mss}`;
}

function renderGate() {
  const banner = $('gate-banner');
  const label = $('gate-label');
  banner.classList.remove('gate-idle', 'gate-armed', 'gate-buffered', 'gate-acked');
  if (state.gate.armed) {
    banner.classList.add('gate-armed');
    label.innerHTML = '<strong>claude is waiting</strong> — click Start to proceed';
  } else if (state.gate.buffered) {
    banner.classList.add('gate-buffered');
    label.textContent = '✓ ready buffered — claude will pick up on next sync point';
  } else {
    banner.classList.add('gate-idle');
    label.innerHTML = 'click <strong>Start</strong> to release the next claude checkpoint';
  }
}

async function clickStart() {
  try {
    await fetch('/api/ready', { method: 'POST' });
    const banner = $('gate-banner');
    banner.classList.remove('gate-idle', 'gate-armed', 'gate-buffered');
    banner.classList.add('gate-acked');
    $('gate-label').textContent = '✓ ready';
  } catch (e) {
    $('gate-label').textContent = 'failed: ' + e.message;
  }
}

function renderHeader() {
  if (state.attached) {
    setStatus('attached', 'attached');
    $('vm-info').textContent = `${state.vm?.vm_name || ''} ${state.vm?.jvm_version || ''} (${state.vm?.host}:${state.vm?.port})`;
  } else {
    setStatus('detached', 'detached');
    $('vm-info').textContent = 'no JVM attached';
  }
  $('event-count').textContent = `${state.events.length} events`;
}

function renderThreads() {
  const ul = $('threads-list');
  ul.innerHTML = '';
  const sys = ['Reference Handler', 'Finalizer', 'Signal Dispatcher', 'Attach Listener',
               'Notification Thread', 'Common-Cleaner'];
  const sorted = [...state.threads].sort((a, b) => {
    if (a.suspended !== b.suspended) return a.suspended ? -1 : 1;
    const aSys = sys.includes(a.name), bSys = sys.includes(b.name);
    if (aSys !== bSys) return aSys ? 1 : -1;
    return a.name.localeCompare(b.name);
  });
  for (const t of sorted) {
    const li = document.createElement('li');
    const isSys = sys.includes(t.name);
    li.classList.add('clickable');
    if (t.suspended) li.classList.add('suspended');
    else if (isSys) li.classList.add('system');
    else li.classList.add('running');
    if (state.focusedThread === t.id) li.classList.add('selected');
    li.dataset.threadId = t.id;
    li.innerHTML = `<strong>${escape(t.name)}</strong>` +
      `<div class="muted">${escape(t.status)}${t.suspended ? ' · paused' : ''}</div>`;
    li.onclick = () => focusThread(t.id);
    ul.appendChild(li);
  }
}

function renderBreakpoints() {
  const ul = $('breakpoints-list');
  ul.innerHTML = '';
  if (state.breakpoints.length === 0) {
    const li = document.createElement('li');
    li.className = 'muted';
    li.textContent = '(none)';
    ul.appendChild(li);
    return;
  }
  for (const bp of state.breakpoints) {
    const li = document.createElement('li');
    let label = bp.id || '?';
    if (bp.kind === 'line') label += ` ${bp.class}:${bp.line}`;
    else if (bp.kind === 'exception') label += ` exception`;
    else label += ` ${bp.kind}`;
    if (bp.field) label += ` ${bp.field}`;
    li.innerHTML = `<span class="tag bp">${escape(bp.kind)}</span> ${escape(label)}`;
    ul.appendChild(li);
  }
}

function renderNotes() {
  const ul = $('notes-list');
  ul.innerHTML = '';
  if (state.notes.length === 0) {
    const li = document.createElement('li');
    li.className = 'note-empty';
    li.textContent = 'no notes yet — claude will narrate here';
    ul.appendChild(li);
    return;
  }
  for (const n of state.notes.slice(-50)) {
    const li = document.createElement('li');
    const kind = (n.kind || 'thought').toLowerCase();
    li.classList.add('kind-' + (['plan','waiting','finding','decision','thought'].includes(kind) ? kind : 'thought'));
    li.innerHTML = `<span class="timestamp">${fmtTime(n.ts_ms)}</span>` +
      `<span class="note-kind">${escape(kind)}</span>` +
      `<span class="note-text">${escape(n.text)}</span>`;
    ul.appendChild(li);
  }
  ul.scrollTop = ul.scrollHeight;
}

function renderEvents() {
  const ul = $('events-list');
  ul.innerHTML = '';
  const recent = state.events.slice(-50).reverse();
  for (const ev of recent) {
    const li = document.createElement('li');
    const tagClass = ev.kind === 'exception' ? 'exception'
      : ev.kind === 'step_completed' ? 'step'
      : 'event';
    let label = ev.kind;
    if (ev.location) label += ` @ ${ev.location}`;
    if (ev.thread_name) label += ` [${ev.thread_name}]`;
    if (ev.exception_type) label += ` (${ev.exception_type})`;
    li.innerHTML = `<span class="timestamp">${fmtTime(ev.ts_ms)}</span>` +
      `<span class="tag ${tagClass}">${escape(ev.kind)}</span> ${escape(stripKind(label, ev.kind))}`;
    li.classList.add('clickable');
    li.onclick = () => { if (ev.thread_id) focusThread(ev.thread_id); };
    ul.appendChild(li);
  }
}

function stripKind(label, kind) {
  return label.startsWith(kind) ? label.slice(kind.length).trimStart() : label;
}

async function focusThread(threadId) {
  state.focusedThread = threadId;
  renderThreads();
  const t = state.threads.find(t => t.id === threadId);
  $('focus-title').textContent = t ? t.name : threadId;
  $('focus-sub').textContent = t ? `${t.status}${t.suspended ? ' · paused' : ''}` : '';
  try {
    const r = await fetch(`/api/frames?thread=${encodeURIComponent(threadId)}`);
    const j = await r.json();
    state.focusedFrames = j;
    renderFrames(j);
    if (j.frames && j.frames.length > 0) {
      renderLocals(j.locals);
      const top = j.frames[0];
      if (top.class && top.line > 0) loadSource(top.class, top.source_name || null, top.line);
    } else {
      $('source-view').textContent = j.note || '(not suspended)';
      $('locals-view').textContent = '';
    }
  } catch (e) {
    $('frames-list').innerHTML = `<li class="muted">failed: ${escape(e.message)}</li>`;
  }
}

function renderFrames(data) {
  const ol = $('frames-list');
  ol.innerHTML = '';
  if (!data.frames || data.frames.length === 0) {
    ol.innerHTML = `<li class="muted">${escape(data.note || 'no frames')}</li>`;
    return;
  }
  for (const f of data.frames) {
    const li = document.createElement('li');
    li.classList.add('clickable');
    li.textContent = `${f.index}. ${f.class}.${f.method}  ${f.source_name || ''}:${f.line}`;
    li.onclick = () => {
      if (f.class && f.line > 0) loadSource(f.class, f.source_name || null, f.line);
    };
    ol.appendChild(li);
  }
}

function renderLocals(data) {
  if (!data) { $('locals-view').textContent = ''; return; }
  const lines = [];
  if (data.this) lines.push(`this = ${formatValue(data.this)}`);
  if (data.locals) {
    for (const [name, info] of Object.entries(data.locals)) {
      lines.push(`${name} (${info.type}) = ${formatValue(info.value)}`);
    }
  }
  if (data.warning) lines.push(`// ${data.warning}`);
  $('locals-view').textContent = lines.join('\n') || '(empty)';
}

function formatValue(v) {
  if (v === null || v === undefined) return 'null';
  if (typeof v === 'object') {
    if (v.object_id) return `${v.object_id} ${v.summary || v.type || ''}`;
    return JSON.stringify(v);
  }
  return JSON.stringify(v);
}

async function loadSource(classFqn, sourceName, line) {
  $('source-title').textContent = sourceName ? `${sourceName} : ${line}` : `${classFqn} : ${line}`;
  const params = new URLSearchParams({ class: classFqn, line: String(line) });
  if (sourceName) params.set('source', sourceName);
  try {
    const r = await fetch('/api/source?' + params.toString());
    const j = await r.json();
    const view = $('source-view');
    if (!j.available) {
      view.textContent = (j.message || 'source not available') +
        '\n(set sourcepath via -Ddebug-bridge.sourcepath=... or set_sourcepath tool)';
      return;
    }
    view.innerHTML = '';
    for (const ln of j.lines) {
      const div = document.createElement('span');
      div.className = 'line' + (ln.number === j.hit_line ? ' hit' : '');
      const n = document.createElement('span');
      n.className = 'line-num';
      n.textContent = String(ln.number);
      div.appendChild(n);
      div.appendChild(document.createTextNode(ln.text || ''));
      div.appendChild(document.createTextNode('\n'));
      view.appendChild(div);
    }
    const hit = view.querySelector('.line.hit');
    if (hit) hit.scrollIntoView({ block: 'center' });
  } catch (e) {
    $('source-view').textContent = 'fetch failed: ' + e.message;
  }
}

function escape(s) {
  return String(s ?? '').replace(/[&<>"']/g, c => ({
    '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;'
  }[c]));
}

function applySnapshot(snap) {
  state.attached = snap.status?.attached || false;
  state.vm = snap.status;
  state.threads = snap.threads || [];
  state.breakpoints = snap.breakpoints || [];
  state.events = snap.recent_events || [];
  state.notes = snap.notes || [];
  if (snap.gate) state.gate = snap.gate;
  renderHeader();
  renderThreads();
  renderBreakpoints();
  renderEvents();
  renderNotes();
  renderGate();
  if (!state.focusedThread && state.events.length > 0) {
    const last = [...state.events].reverse().find(e => e.thread_id);
    if (last) focusThread(last.thread_id);
  }
}

function applyGateState(g) {
  state.gate = g;
  renderGate();
}

function applyEvent(ev) {
  state.events.push(ev);
  if (state.events.length > 200) state.events = state.events.slice(-200);
  renderEvents();
  $('event-count').textContent = `${state.events.length} events`;
  // Auto-focus on breakpoint hits / exceptions.
  if (ev.thread_id && (ev.kind === 'breakpoint_hit' || ev.kind === 'exception' || ev.kind === 'step_completed')) {
    refreshState().then(() => focusThread(ev.thread_id));
  } else {
    refreshState();
  }
}

async function refreshState() {
  try {
    const r = await fetch('/api/state');
    const j = await r.json();
    const savedFocus = state.focusedThread;
    applySnapshot(j);
    state.focusedThread = savedFocus;
    renderThreads();
  } catch (e) {
    setStatus('error: ' + e.message, 'error');
  }
}

function applyNote(n) {
  state.notes.push(n);
  if (state.notes.length > 200) state.notes = state.notes.slice(-200);
  renderNotes();
}

function connect() {
  const es = new EventSource('/api/events');
  es.addEventListener('snapshot', (e) => applySnapshot(JSON.parse(e.data)));
  es.addEventListener('debug_event', (e) => applyEvent(JSON.parse(e.data)));
  es.addEventListener('tool_call', (e) => refreshState());
  es.addEventListener('note', (e) => applyNote(JSON.parse(e.data)));
  es.addEventListener('gate_state', (e) => applyGateState(JSON.parse(e.data)));
  es.onerror = () => setStatus('disconnected — retrying', 'error');
  es.onopen = () => setStatus('attached', state.attached ? 'attached' : 'detached');
}

document.getElementById('gate-button').addEventListener('click', clickStart);

connect();
