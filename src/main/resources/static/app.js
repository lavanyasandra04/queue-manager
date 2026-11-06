/* Queue Manager frontend (vanilla JS, no build step) */
const $ = (s, r = document) => r.querySelector(s);
const esc = (v) => String(v).replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));

const state = {
  token: localStorage.getItem('qm_token'),
  username: localStorage.getItem('qm_user'),
  queues: [],
  activeId: null,
  tab: 'queue',
  authMode: 'login',
  charts: {},
  timer: null,
};

// ---------- helpers ----------
function toast(msg, isError = false) {
  const t = $('#toast');
  t.textContent = msg;
  t.className = 'show' + (isError ? ' error' : '');
  clearTimeout(toast.t);
  toast.t = setTimeout(() => (t.className = ''), 2600);
}

async function api(path, { method = 'GET', body } = {}) {
  const res = await fetch('/api' + path, {
    method,
    headers: { 'Content-Type': 'application/json', ...(state.token ? { Authorization: 'Bearer ' + state.token } : {}) },
    body: body ? JSON.stringify(body) : undefined,
  });
  const data = await res.json().catch(() => ({}));
  if (res.status === 401 && state.token) {
    logout();
    throw new Error(data.error || 'Session expired');
  }
  if (!res.ok) throw new Error(data.error || 'Something went wrong');
  return data;
}

function fmtDuration(ms) {
  if (ms == null) return '–';
  const s = Math.max(0, Math.round(ms / 1000));
  if (s < 60) return s + 's';
  const m = Math.floor(s / 60);
  if (m < 60) return m + 'm ' + String(s % 60).padStart(2, '0') + 's';
  return Math.floor(m / 60) + 'h ' + (m % 60) + 'm';
}
const fmtTime = (t) => new Date(t).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
const stubNo = (n) => '#' + String(n).padStart(3, '0');

// ---------- auth ----------
function logout() {
  localStorage.removeItem('qm_token');
  localStorage.removeItem('qm_user');
  Object.assign(state, { token: null, username: null, queues: [], activeId: null });
  clearInterval(state.timer);
  destroyCharts();
  render();
}

function renderAuth() {
  const login = state.authMode === 'login';
  $('#app').innerHTML = `
    <div class="auth-wrap">
      <section class="auth-hero">
        <div class="brand"><span class="brand-mark">Q</span> Queue Manager</div>
        <div>
          <h1>Keep every line moving.</h1>
          <p>Create a queue, hand out tokens, call the next person and see how long people really wait.</p>
        </div>
        <div class="stub-demo" aria-hidden="true">
          <span class="stub" style="background:var(--serving);color:#3a2a00;border-color:#a87a10">#001</span>
          <span class="stub">#002</span><span class="stub">#003</span><span class="stub">#004</span>
        </div>
      </section>
      <section class="auth-panel">
        <form class="auth-card" id="auth-form" novalidate>
          <h2>${login ? 'Log in' : 'Create your account'}</h2>
          <p class="sub">${login ? 'Welcome back, manager.' : 'Set up a manager account to start creating queues.'}</p>
          <div class="field"><label for="u">Username</label><input id="u" type="text" autocomplete="username" required /></div>
          <div class="field"><label for="p">Password</label><input id="p" type="password" autocomplete="${login ? 'current-password' : 'new-password'}" required /></div>
          <div class="form-error" id="auth-error" role="alert"></div>
          <button class="btn btn-primary" style="width:100%" type="submit">${login ? 'Log in' : 'Create account'}</button>
          <p class="switch">${login ? 'New here?' : 'Already have an account?'}
            <button type="button" id="switch">${login ? 'Create an account' : 'Log in'}</button></p>
        </form>
      </section>
    </div>`;
  $('#switch').onclick = () => { state.authMode = login ? 'register' : 'login'; renderAuth(); };
  $('#auth-form').onsubmit = async (e) => {
    e.preventDefault();
    try {
      const data = await api(login ? '/auth/login' : '/auth/register', {
        method: 'POST',
        body: { username: $('#u').value, password: $('#p').value },
      });
      state.token = data.token;
      state.username = data.username;
      localStorage.setItem('qm_token', data.token);
      localStorage.setItem('qm_user', data.username);
      render();
    } catch (err) {
      $('#auth-error').textContent = err.message;
    }
  };
}

// ---------- shell ----------
function render() {
  if (!state.token) return renderAuth();
  $('#app').innerHTML = `
    <header class="topbar">
      <div class="brand"><span class="brand-mark">Q</span> Queue Manager</div>
      <div class="who"><span>${esc(state.username)}</span><button class="btn" id="logout">Log out</button></div>
    </header>
    <div class="shell">
      <aside class="sidebar">
        <h3>New queue</h3>
        <form class="new-queue" id="new-queue">
          <input type="text" id="qname" placeholder="e.g. Billing desk" maxlength="60" aria-label="Queue name" />
          <button class="btn btn-primary" type="submit">Create</button>
        </form>
        <h3>Your queues</h3>
        <ul class="queue-list" id="queue-list"></ul>
      </aside>
      <main class="main" id="main"></main>
    </div>`;
  $('#logout').onclick = logout;
  $('#new-queue').onsubmit = async (e) => {
    e.preventDefault();
    try {
      const q = await api('/queues', { method: 'POST', body: { name: $('#qname').value } });
      $('#qname').value = '';
      toast('Queue created');
      await loadQueues();
      selectQueue(q.id);
    } catch (err) { toast(err.message, true); }
  };
  clearInterval(state.timer);
  state.timer = setInterval(() => { refreshQueues(); refreshMain(); }, 4000);
  loadQueues().then(() => {
    if (!state.activeId && state.queues.length) selectQueue(state.queues[0].id);
    else renderMain();
  });
}

async function loadQueues() {
  try { state.queues = await api('/queues'); } catch (err) { toast(err.message, true); }
  renderQueueList();
}
const refreshQueues = () => api('/queues').then((q) => { state.queues = q; renderQueueList(); }).catch(() => {});

function renderQueueList() {
  const ul = $('#queue-list');
  if (!ul) return;
  ul.innerHTML = state.queues.length
    ? state.queues.map((q) => `
        <li><button class="queue-item" data-id="${q.id}" aria-current="${q.id === state.activeId}">
          <span>${esc(q.name)}</span><span class="count ${q.waiting ? '' : 'zero'}" title="Waiting">${q.waiting}</span>
        </button></li>`).join('')
    : '<li style="color:var(--muted);font-size:14px">No queues yet.</li>';
  ul.querySelectorAll('.queue-item').forEach((b) => (b.onclick = () => selectQueue(Number(b.dataset.id))));
}

function selectQueue(id) {
  state.activeId = id;
  renderQueueList();
  renderMain();
}

function destroyCharts() {
  Object.values(state.charts).forEach((c) => c.destroy());
  state.charts = {};
}

function renderMain() {
  destroyCharts();
  const main = $('#main');
  if (!main) return;
  const q = state.queues.find((x) => x.id === state.activeId);
  if (!q) {
    main.innerHTML = `<div class="empty"><strong>Create your first queue</strong>Name it in the box on the left, then add people to it.</div>`;
    return;
  }
  main.innerHTML = `
    <div class="main-head">
      <h1>${esc(q.name)}</h1>
      <div style="display:flex;gap:8px;align-items:center">
        <div class="tabs" role="tablist">
          <button class="tab" role="tab" data-tab="queue" aria-selected="${state.tab === 'queue'}">Queue</button>
          <button class="tab" role="tab" data-tab="dashboard" aria-selected="${state.tab === 'dashboard'}">Dashboard</button>
        </div>
        <button class="btn btn-ghost btn-danger" id="del-queue">Delete</button>
      </div>
    </div>
    <div id="view"></div>`;
  main.querySelectorAll('.tab').forEach((t) => (t.onclick = () => { state.tab = t.dataset.tab; renderMain(); }));
  $('#del-queue').onclick = async () => {
    if (!confirm(`Delete "${q.name}" and all its tokens? This can't be undone.`)) return;
    try {
      await api('/queues/' + q.id, { method: 'DELETE' });
      toast('Queue deleted');
      state.activeId = null;
      await loadQueues();
      if (state.queues.length) selectQueue(state.queues[0].id); else renderMain();
    } catch (err) { toast(err.message, true); }
  };

  if (state.tab === 'queue') {
    $('#view').innerHTML = `
      <form class="add-form" id="add-form">
        <input type="text" id="pname" placeholder="Person's name" maxlength="60" aria-label="Person's name" />
        <button class="btn btn-primary" type="submit">Add to queue</button>
      </form>
      <div id="live"></div>`;
    $('#add-form').onsubmit = async (e) => {
      e.preventDefault();
      const input = $('#pname');
      try {
        const t = await api(`/queues/${q.id}/tokens`, { method: 'POST', body: { name: input.value } });
        input.value = '';
        toast(`Token ${stubNo(t.number)} created for ${t.name}`);
        refreshMain(); refreshQueues();
      } catch (err) { toast(err.message, true); }
      input.focus();
    };
  } else {
    $('#view').innerHTML = `
      <div class="stats" id="stats"></div>
      <div class="charts">
        <div class="panel wide"><h2>Queue length over time <span class="hint">people waiting after each join / call / cancel</span></h2><div class="chart-box"><canvas id="c-trend"></canvas></div></div>
        <div class="panel"><h2>Wait time per token <span class="hint">minutes, latest 20 called</span></h2><div class="chart-box"><canvas id="c-wait"></canvas></div></div>
        <div class="panel"><h2>Arrivals per hour <span class="hint">last 12 hours</span></h2><div class="chart-box"><canvas id="c-arr"></canvas></div></div>
      </div>`;
  }
  refreshMain();
}

async function refreshMain() {
  if (!state.activeId) return;
  try {
    if (state.tab === 'queue') await refreshQueueView();
    else await refreshDashboard();
  } catch (err) { /* transient; next poll retries */ }
}

// ---------- queue view ----------
async function action(path, opts, okMsg) {
  try {
    await api(path, opts);
    if (okMsg) toast(okMsg);
  } catch (err) { toast(err.message, true); }
  refreshMain(); refreshQueues();
}

async function refreshQueueView() {
  const live = $('#live');
  if (!live) return;
  const d = await api('/queues/' + state.activeId);
  if (!$('#live')) return;
  const now = d.now;

  const serving = d.serving.length
    ? d.serving.map((t) => `
        <div class="serving-card">
          <span class="stub gold">${stubNo(t.number)}</span>
          <div class="who"><small>Now serving</small><b>${esc(t.name)}</b></div>
          <span class="waited" style="color:#6b5416">In service ${fmtDuration(now - t.started_at)}</span>
          <button class="btn" data-complete="${t.id}">Mark complete</button>
        </div>`).join('')
    : `<div class="serving-card idle"><div class="who"><b>Nobody is being served</b><small>Call the next person when you're ready.</small></div></div>`;

  const rows = d.waiting.map((t, i) => `
    <li class="token-row ${i === 0 ? 'next-up' : ''}">
      <span class="pos">${i + 1}</span>
      <span class="stub">${stubNo(t.number)}</span>
      <span class="name">${esc(t.name)}</span>
      <span class="waited">Waiting ${fmtDuration(now - t.created_at)}</span>
      <span class="actions">
        <button class="btn btn-icon" data-move="up" data-id="${t.id}" ${i === 0 ? 'disabled' : ''} aria-label="Move ${esc(t.name)} up" title="Move up">↑</button>
        <button class="btn btn-icon" data-move="down" data-id="${t.id}" ${i === d.waiting.length - 1 ? 'disabled' : ''} aria-label="Move ${esc(t.name)} down" title="Move down">↓</button>
        <button class="btn btn-icon btn-danger" data-cancel="${t.id}" aria-label="Cancel ${esc(t.name)}" title="Cancel token">✕</button>
      </span>
    </li>`).join('');

  const hist = d.history.map((t) => `
    <div class="history-row">
      <span class="stub dim" style="height:32px;font-size:14px;min-width:50px">${stubNo(t.number)}</span>
      <span>${esc(t.name)}</span>
      <span class="tag ${t.status}">${t.status === 'served' ? 'Served' : 'Cancelled'}</span>
      <span>${fmtTime(t.completed_at || t.cancelled_at)}</span>
    </div>`).join('');

  live.innerHTML = `
    ${serving}
    <section class="panel">
      <h2>Waiting (${d.waiting.length})
        <span class="callbar"><button class="btn btn-primary" id="serve" ${d.waiting.length ? '' : 'disabled'}>Call next person</button></span>
      </h2>
      ${d.waiting.length
        ? `<ul class="token-list">${rows}</ul>`
        : `<div class="empty" style="padding:22px"><strong>The queue is empty</strong>Add a person above to create their token.</div>`}
    </section>
    ${d.history.length ? `<section class="panel"><h2>Recently finished <span class="hint">latest 10</span></h2>${hist}</section>` : ''}`;

  $('#serve').onclick = () => action(`/queues/${state.activeId}/serve`, { method: 'POST' }, 'Next person called');
  live.querySelectorAll('[data-move]').forEach((b) => (b.onclick = () => action(`/tokens/${b.dataset.id}/move`, { method: 'POST', body: { direction: b.dataset.move } })));
  live.querySelectorAll('[data-cancel]').forEach((b) => (b.onclick = () => {
    if (confirm('Cancel this token?')) action(`/tokens/${b.dataset.cancel}/cancel`, { method: 'POST' }, 'Token cancelled');
  }));
  live.querySelectorAll('[data-complete]').forEach((b) => (b.onclick = () => action(`/tokens/${b.dataset.complete}/complete`, { method: 'POST' }, 'Marked complete')));
}

// ---------- dashboard ----------
function upsertChart(id, config) {
  const existing = state.charts[id];
  if (existing) {
    existing.data = config.data;
    existing.update('none');
    return;
  }
  const el = document.getElementById(id);
  if (!el || typeof Chart === 'undefined') return;
  state.charts[id] = new Chart(el, config);
}

async function refreshDashboard() {
  const a = await api(`/queues/${state.activeId}/analytics`);
  const stats = $('#stats');
  if (!stats) return;
  const cards = [
    ['Waiting now', a.totals.waiting],
    ['Being served', a.totals.serving],
    ['Served', a.totals.served],
    ['Cancelled', a.totals.cancelled],
    ['Average wait', fmtDuration(a.avgWaitMs)],
    ['Longest wait', fmtDuration(a.maxWaitMs)],
    ['Longest wait right now', fmtDuration(a.longestCurrentWaitMs || null)],
    ['Average service time', fmtDuration(a.avgServiceMs)],
  ];
  stats.innerHTML = cards.map(([l, v]) => `<div class="stat"><div class="v">${v}</div><div class="l">${l}</div></div>`).join('');

  const grid = { color: '#e6ebe8' };
  const base = { responsive: true, maintainAspectRatio: false, plugins: { legend: { display: false } } };
  const line = '#0f6b5c';

  upsertChart('c-trend', {
    type: 'line',
    data: {
      labels: a.lengthTrend.map((p) => fmtTime(p.t)),
      datasets: [{ data: a.lengthTrend.map((p) => p.length), borderColor: line, backgroundColor: '#0f6b5c22', fill: true, stepped: true, pointRadius: 2, borderWidth: 2 }],
    },
    options: { ...base, scales: { y: { beginAtZero: true, ticks: { precision: 0 }, grid }, x: { grid: { display: false }, ticks: { maxTicksLimit: 8 } } } },
  });
  upsertChart('c-wait', {
    type: 'bar',
    data: {
      labels: a.waitPerToken.map((p) => stubNo(p.number)),
      datasets: [{ data: a.waitPerToken.map((p) => +(p.waitMs / 60000).toFixed(2)), backgroundColor: '#f2b33d', borderRadius: 4 }],
    },
    options: { ...base, scales: { y: { beginAtZero: true, title: { display: true, text: 'minutes' }, grid }, x: { grid: { display: false } } } },
  });
  upsertChart('c-arr', {
    type: 'bar',
    data: {
      labels: a.arrivals.map((p) => new Date(p.t).toLocaleTimeString([], { hour: 'numeric' })),
      datasets: [{ data: a.arrivals.map((p) => p.count), backgroundColor: line, borderRadius: 4 }],
    },
    options: { ...base, scales: { y: { beginAtZero: true, ticks: { precision: 0 }, grid }, x: { grid: { display: false } } } },
  });
}

render();
