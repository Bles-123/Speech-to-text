const $ = (id) => document.getElementById(id);
const API = '/api/transcriptions';
const TARGET_RATE = 16000;
const LANG_NAMES = { auto: 'Auto', hi: 'Hindi', en: 'English' };

/* ------------------------------------------------------------------ recording */
let ws, ctx, stream, node, recording = false;

function setStatus(msg, isError = false) {
  const s = $('status');
  s.textContent = msg;
  s.classList.toggle('error', isError);
}

function appendSegment(text) {
  const live = $('live');
  const ph = live.querySelector('.placeholder');
  if (ph) ph.remove();
  const span = document.createElement('span');
  span.className = 'seg';
  span.textContent = (live.textContent ? ' ' : '') + text;
  live.appendChild(span);
  live.scrollTop = live.scrollHeight;
}

function downsampleToInt16(float32, inRate) {
  const ratio = inRate / TARGET_RATE;
  const outLen = Math.floor(float32.length / ratio);
  const out = new Int16Array(outLen);
  for (let i = 0; i < outLen; i++) {
    const start = Math.floor(i * ratio);
    const end = Math.min(Math.floor((i + 1) * ratio), float32.length);
    let sum = 0;
    for (let j = start; j < end; j++) sum += float32[j];
    const v = Math.max(-1, Math.min(1, sum / Math.max(1, end - start)));
    out[i] = v < 0 ? v * 0x8000 : v * 0x7fff;
  }
  return out;
}

async function startRecording() {
  try {
    stream = await navigator.mediaDevices.getUserMedia({
      audio: { channelCount: 1, echoCancellation: true, noiseSuppression: true },
    });
  } catch (e) {
    setStatus('Microphone blocked. Allow microphone access in your browser and try again.', true);
    return;
  }

  $('live').innerHTML = '';
  setStatus('Connecting…');
  const proto = location.protocol === 'https:' ? 'wss' : 'ws';
  ws = new WebSocket(`${proto}://${location.host}/ws/transcribe`);
  ws.binaryType = 'arraybuffer';

  ws.onopen = async () => {
    ws.send(JSON.stringify({ type: 'start', language: $('lang').value, title: $('title').value.trim() }));
    ctx = new AudioContext();
    await ctx.audioWorklet.addModule('/pcm-worklet.js');
    const src = ctx.createMediaStreamSource(stream);
    node = new AudioWorkletNode(ctx, 'pcm-processor');
    node.port.onmessage = (e) => {
      const f32 = e.data;
      // level meter
      let peak = 0;
      for (let i = 0; i < f32.length; i += 16) peak = Math.max(peak, Math.abs(f32[i]));
      $('level').style.width = Math.min(100, peak * 160) + '%';
      if (ws.readyState === WebSocket.OPEN) ws.send(downsampleToInt16(f32, ctx.sampleRate).buffer);
    };
    const mute = ctx.createGain();
    mute.gain.value = 0;
    src.connect(node).connect(mute).connect(ctx.destination);

    recording = true;
    $('recBtn').textContent = 'Stop and save';
    $('recBtn').classList.add('active');
    setStatus('Listening… speak naturally, pause between sentences.');
  };

  ws.onmessage = (e) => {
    const m = JSON.parse(e.data);
    if (m.type === 'partial') appendSegment(m.text);
    else if (m.type === 'error') setStatus(m.message, true);
    else if (m.type === 'final') {
      setStatus(m.id ? `Saved as #${m.id}.` : 'No speech detected, nothing saved.');
      $('recBtn').disabled = false;
      loadHistory();
    }
  };
  ws.onclose = () => { if (recording) stopRecording(false); $('recBtn').disabled = false; };
  ws.onerror = () => setStatus('Could not reach the server.', true);
}

function stopRecording(notifyServer = true) {
  recording = false;
  if (node) node.disconnect();
  if (ctx) ctx.close();
  if (stream) stream.getTracks().forEach((t) => t.stop());
  $('level').style.width = '0';
  $('recBtn').textContent = 'Start recording';
  $('recBtn').classList.remove('active');
  if (notifyServer && ws && ws.readyState === WebSocket.OPEN) {
    $('recBtn').disabled = true;
    setStatus('Finishing transcription…');
    ws.send(JSON.stringify({ type: 'stop' }));
  }
}

$('recBtn').addEventListener('click', () => (recording ? stopRecording() : startRecording()));

/* ------------------------------------------------------------------ history (CRUD) */
let page = 0, totalPages = 0, query = '', editingId = null;

async function api(url, opts = {}) {
  const res = await fetch(url, { headers: { 'Content-Type': 'application/json' }, ...opts });
  if (!res.ok) {
    let msg = res.statusText;
    try { msg = (await res.json()).message || msg; } catch (_) {}
    throw new Error(msg);
  }
  return res.status === 204 ? null : res.json();
}

async function loadHistory() {
  const data = await api(`${API}?page=${page}&size=8&q=${encodeURIComponent(query)}`);
  totalPages = data.totalPages;
  if (page > 0 && data.content.length === 0) { page = Math.max(0, totalPages - 1); return loadHistory(); }

  const rows = $('rows');
  rows.innerHTML = '';
  data.content.forEach((t) => {
    const tr = document.createElement('tr');
    tr.append(
      cell('title', t.title),
      cell('text', t.text, true),
      cell('', LANG_NAMES[t.language] || t.language || ''),
      cell('', new Date(t.createdAt).toLocaleString()),
    );
    const act = document.createElement('td');
    act.className = 'actions';
    act.append(
      button('Edit', 'secondary', () => openDialog(t)),
      button('Delete', 'secondary danger', () => remove(t)),
    );
    tr.append(act);
    rows.append(tr);
  });
  $('empty').hidden = data.totalElements > 0;
  $('pageInfo').textContent = totalPages ? `Page ${page + 1} of ${totalPages}` : '';
  $('prev').disabled = page === 0;
  $('next').disabled = page + 1 >= totalPages;
}

function cell(cls, text, clamp) {
  const td = document.createElement('td');
  td.className = cls;
  td.lang = 'hi';
  if (clamp) { const d = document.createElement('div'); d.textContent = text; td.append(d); }
  else td.textContent = text;
  return td;
}
function button(label, cls, fn) {
  const b = document.createElement('button');
  b.type = 'button'; b.className = cls; b.textContent = label;
  b.addEventListener('click', fn);
  return b;
}

function openDialog(t) {
  editingId = t ? t.id : null;
  $('dlgTitle').textContent = t ? 'Edit transcription' : 'Add note';
  $('fTitle').value = t ? t.title : '';
  $('fText').value = t ? t.text : '';
  $('fLang').value = t && t.language ? t.language : 'auto';
  $('fMeta').textContent = t ? `Created ${new Date(t.createdAt).toLocaleString()}` + (t.durationSeconds != null ? ` · ${t.durationSeconds}s of audio` : '') : '';
  $('dlg').showModal();
}

$('form').addEventListener('submit', async (e) => {
  e.preventDefault();
  const body = JSON.stringify({ title: $('fTitle').value.trim(), text: $('fText').value.trim(), language: $('fLang').value });
  try {
    if (editingId) await api(`${API}/${editingId}`, { method: 'PUT', body });
    else await api(API, { method: 'POST', body });
    $('dlg').close();
    loadHistory();
  } catch (err) { alert('Could not save: ' + err.message); }
});
$('cancel').addEventListener('click', () => $('dlg').close());
$('newBtn').addEventListener('click', () => openDialog(null));

async function remove(t) {
  if (!confirm(`Delete "${t.title}"? This cannot be undone.`)) return;
  try { await api(`${API}/${t.id}`, { method: 'DELETE' }); loadHistory(); }
  catch (err) { alert('Could not delete: ' + err.message); }
}

let timer;
$('search').addEventListener('input', (e) => {
  clearTimeout(timer);
  timer = setTimeout(() => { query = e.target.value.trim(); page = 0; loadHistory(); }, 250);
});
$('prev').addEventListener('click', () => { page--; loadHistory(); });
$('next').addEventListener('click', () => { page++; loadHistory(); });

loadHistory();
