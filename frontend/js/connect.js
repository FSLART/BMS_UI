import { api } from './api.js';

/**
 * Transport catalogue. `scan` names the backend scanner; `fields` render to the
 * right of each row and are what gets POSTed as the connection config.
 */
const TRANSPORTS = [
  {
    id: 'can',
    name: 'CAN Probe',
    scan: 'can',
    required: ['channel'],
    // One field per line: the channel names are long and the order reads as the
    // decisions actually happen -- interface first, then the channel it exposes,
    // then the bitrate.
    stack: true,
    fields: [
      // Changing the backend changes what a "channel" even is, so it rescans.
      { k: 'backend', label: 'Interface', type: 'select', from: 'can_backends', def: 'slcan', rescans: true },
      { k: 'channel', label: 'Canal', type: 'select', fromScan: true },
      { k: 'bitrate', label: 'Bitrate', type: 'select', from: 'can_bitrates', def: 500000,
        fmt: (v) => `${v / 1000} kbit/s` },
      { k: 'dbc', label: 'Ficheiro DBC', type: 'file', accept: '.dbc',
        placeholder: 'Opcional: usa uma DBC diferente da do Git' },
    ],
  },
  {
    id: 'wifi',
    name: 'WiFi',
    scan: 'wifi',
    required: ['host'],
    fields: [
      { k: 'ssid', label: 'Rede', type: 'select', fromScan: true },
      { k: 'protocol', label: 'Protocolo', type: 'select', values: ['tcp', 'udp', 'mqtt'], def: 'tcp' },
      { k: 'host', label: 'Host / IP', type: 'text', def: '192.168.4.1' },
      { k: 'port', label: 'Porta', type: 'text', def: '3333' },
    ],
  },
  {
    id: 'ble',
    name: 'Bluetooth LE',
    scan: 'ble',
    required: ['address'],
    fields: [
      { k: 'address', label: 'Dispositivo', type: 'select', fromScan: true, wide: true },
      { k: 'service', label: 'Service UUID', type: 'text', placeholder: '0000ffe0-...' },
      { k: 'characteristic', label: 'Char UUID', type: 'text', placeholder: '0000ffe1-...' },
    ],
  },
  {
    id: 'serial',
    name: 'USB Serial (UART)',
    scan: 'serial',
    required: ['port'],
    fields: [
      { k: 'port', label: 'Porta', type: 'select', fromScan: true },
      { k: 'baud', label: 'Baudrate', type: 'select', from: 'uart_bauds', def: 115200 },
      { k: 'parity', label: 'Paridade', type: 'select', values: ['N', 'E', 'O'], def: 'N' },
      { k: 'stopbits', label: 'Stop bits', type: 'select', values: ['1', '2'], def: '1' },
    ],
  },
];

export class ConnectScreen {
  constructor(root, { onStatus }) {
    this.root = root;
    this.onStatus = onStatus;
    this.options = {};
    this.selected = null;
    this.config = {};        // per-transport config values
    this.scanCache = {};
    // Off by default: the app should not quietly show fake numbers unless the
    // user asks for them.
    this.demo = false;
    this.els = {};
    this._debounce = null;
  }

  async mount() {
    try { this.options = await api.options(); } catch { this.options = {}; }
    // Backend decides what may be picked; absent list = allow everything.
    this.selectable = this.options.selectable_transports || TRANSPORTS.map((t) => t.id);
    this.root.innerHTML = '';
    for (const t of TRANSPORTS) this.root.appendChild(this._row(t));
    this._wireDemoToggle();
  }

  isAvailable(id) {
    return this.selectable.includes(id);
  }

  // -- DOM -----------------------------------------------------------------

  _row(t) {
    const available = this.isAvailable(t.id);
    const row = document.createElement('div');
    row.className = `transport${available ? '' : ' unavailable'}`;
    row.dataset.id = t.id;
    if (!available) {
      row.setAttribute('aria-disabled', 'true');
      row.title = `${t.name} ainda não está disponível`;
    }

    const sel = document.createElement('div');
    sel.className = 'selector';

    const id = document.createElement('div');
    id.className = 'transport-id';
    id.innerHTML = `<span class="transport-name">${t.name}</span>`;

    const cfg = document.createElement('div');
    cfg.className = `transport-config${t.stack ? ' stack' : ''}`;
    this.config[t.id] = {};

    for (const f of t.fields) cfg.appendChild(this._field(t, f));

    if (t.scan) {
      const hint = document.createElement('div');
      hint.className = 'hint';
      hint.dataset.role = 'hint';
      cfg.appendChild(hint);
    }

    row.append(sel, id, cfg);
    this.els[t.id] = { row, cfg };

    // Clicking anywhere on the row (but not on a field) selects + auto-connects.
    if (available) {
      row.addEventListener('click', (ev) => {
        if (ev.target.closest('.field') || ev.target.closest('.btn-scan')) return;
        this.select(t.id);
      });
    }

    return row;
  }

  _field(t, f) {
    const wrap = document.createElement('div');
    wrap.className = `field${f.wide ? ' wide' : ''}`;
    const label = document.createElement('label');
    label.textContent = f.label;
    wrap.appendChild(label);

    if (f.type === 'file') {
      wrap.appendChild(this._filePicker(t, f));
      return wrap;
    }

    let input;
    if (f.type === 'select') {
      input = document.createElement('select');
      const values = f.values || this.options[f.from] || [];
      if (f.fromScan) {
        input.innerHTML = '<option value="">a procurar…</option>';
        input.dataset.scanTarget = '1';
      } else {
        for (const v of values) {
          const o = document.createElement('option');
          o.value = v;
          o.textContent = f.fmt ? f.fmt(v) : v;
          input.appendChild(o);
        }
      }
    } else {
      input = document.createElement('input');
      input.type = 'text';
      input.spellcheck = false;
      if (f.placeholder) input.placeholder = f.placeholder;
    }

    if (f.def !== undefined) {
      input.value = f.def;
      this.config[t.id][f.k] = f.def;
    }
    input.dataset.key = f.k;

    input.addEventListener('change', async () => {
      this.config[t.id][f.k] = input.value;
      // A backend switch invalidates the channel list before anything else.
      if (f.rescans && t.scan) await this._scan(t, true);
      if (this.selected === t.id) this._reconnectSoon();
    });
    input.addEventListener('input', () => {
      this.config[t.id][f.k] = input.value;
    });

    // Scannable selects get a rescan button next to them.
    if (f.fromScan) {
      const rowEl = document.createElement('div');
      rowEl.className = 'field-row';
      const btn = document.createElement('button');
      btn.className = 'btn-scan';
      btn.textContent = '⟳';
      btn.title = 'Procurar de novo';
      btn.addEventListener('click', (ev) => {
        ev.stopPropagation();
        this.scanCache[this._cacheKey(t)] = null;
        this._scan(t, true);
      });
      rowEl.append(input, btn);
      wrap.appendChild(rowEl);
    } else {
      wrap.appendChild(input);
    }

    return wrap;
  }

  /**
   * Pick a .dbc from the machine.
   *
   * The browser never exposes a real filesystem path, so the bytes are sent to
   * the backend, which parses them to prove it is a DBC and keeps the file.
   * What lands in the config is the path the backend wrote, which is what the
   * decoder can actually open.
   */
  _filePicker(t, f) {
    const box = document.createElement('div');
    box.className = 'file-picker';

    const input = document.createElement('input');
    input.type = 'file';
    input.accept = f.accept || '';
    input.hidden = true;

    const btn = document.createElement('button');
    btn.type = 'button';
    btn.className = 'btn-file';
    btn.textContent = 'Escolher…';

    const name = document.createElement('span');
    name.className = 'file-name empty';
    name.textContent = f.placeholder || 'Nenhum ficheiro escolhido';

    const clear = document.createElement('button');
    clear.type = 'button';
    clear.className = 'btn-file-clear';
    clear.textContent = '✕';
    clear.title = 'Voltar às DBCs do carro';
    clear.hidden = true;

    const reset = () => {
      input.value = '';
      delete this.config[t.id][f.k];
      name.className = 'file-name empty';
      name.textContent = f.placeholder || 'Nenhum ficheiro escolhido';
      clear.hidden = true;
    };

    btn.addEventListener('click', (ev) => { ev.stopPropagation(); input.click(); });
    clear.addEventListener('click', (ev) => {
      ev.stopPropagation();
      reset();
      if (this.selected === t.id) this._reconnectSoon();
    });

    input.addEventListener('change', async () => {
      const file = input.files && input.files[0];
      if (!file) return reset();

      name.className = 'file-name';
      name.textContent = `${file.name} — a validar…`;
      clear.hidden = false;

      const res = await api.uploadDbc(file);
      if (!res.ok) {
        name.className = 'file-name err';
        name.textContent = res.error || 'Falhou';
        delete this.config[t.id][f.k];
        return;
      }
      name.className = 'file-name ok';
      name.textContent = `${res.name} — ${res.messages} mensagens`;
      this.config[t.id][f.k] = res.path;
      if (this.selected === t.id) this._reconnectSoon();
    });

    box.append(btn, name, clear, input);
    return box;
  }

  _wireDemoToggle() {
    const toggle = document.getElementById('demo-toggle');
    const sw = document.getElementById('demo-switch');
    toggle.addEventListener('click', () => {
      this.demo = !this.demo;
      sw.classList.toggle('on', this.demo);
      if (this.selected) this._reconnectSoon();
    });
  }

  // -- behaviour -----------------------------------------------------------

  async select(id) {
    if (this.selected === id || !this.isAvailable(id)) return;
    this.selected = id;
    for (const [key, el] of Object.entries(this.els)) {
      el.row.classList.toggle('selected', key === id);
      if (key !== id) el.row.classList.remove('busy', 'live', 'error');
    }

    const t = TRANSPORTS.find((x) => x.id === id);
    // A BLE scan takes ~5 s; if the user picks another transport meanwhile,
    // this stale call must not connect over their newer choice.
    if (t.scan) await this._scan(t);
    if (this.selected !== id) return;
    this._connect();
  }

  /** CAN channels depend on the chosen backend, so it keys the scan and cache. */
  _scanBackend(t) {
    return t.id === 'can' ? this.config[t.id].backend : undefined;
  }

  _cacheKey(t) {
    const backend = this._scanBackend(t);
    return backend ? `${t.scan}:${backend}` : t.scan;
  }

  async _scan(t, force = false) {
    const { cfg } = this.els[t.id];
    const hint = cfg.querySelector('[data-role="hint"]');
    const select = cfg.querySelector('[data-scan-target]');
    if (!select) return;

    const key = this._cacheKey(t);
    if (!force && this.scanCache[key]) {
      this._fillScan(t, select, hint, this.scanCache[key]);
      return;
    }

    select.innerHTML = '<option value="">a procurar…</option>';
    if (hint) { hint.className = 'hint'; hint.textContent = t.scan === 'ble' ? 'Scan BLE demora ~5 s…' : ''; }

    let res;
    try { res = await api.scan(t.scan, this._scanBackend(t)); } catch (e) { res = { items: [], hint: String(e) }; }
    this.scanCache[key] = res;
    this._fillScan(t, select, hint, res);
  }

  _fillScan(t, select, hint, res) {
    const key = select.dataset.key;
    select.innerHTML = '';
    if (!res.items.length) {
      select.innerHTML = '<option value="">— nada encontrado —</option>';
      this.config[t.id][key] = '';
    } else {
      for (const it of res.items) {
        const o = document.createElement('option');
        o.value = it.value;
        o.textContent = it.label;
        if (it.detail) o.title = it.detail;
        select.appendChild(o);
      }
      // Auto-pick the first device so selecting the circle is enough to connect.
      select.value = res.items[0].value;
      this.config[t.id][key] = res.items[0].value;
      if (t.id === 'ble') {
        this.config[t.id].name = res.items[0].label;
      }
    }
    if (hint) {
      hint.className = 'hint';
      hint.textContent = res.hint || '';
    }
  }

  _reconnectSoon() {
    clearTimeout(this._debounce);
    this._debounce = setTimeout(() => this._connect(), 450);
  }

  async _connect() {
    const id = this.selected;
    if (!id) return;
    const t = TRANSPORTS.find((x) => x.id === id);
    const { row, cfg } = this.els[id];
    const hint = cfg.querySelector('[data-role="hint"]');

    const missing = t.required.filter((k) => !this.config[id][k]);
    if (missing.length) {
      row.classList.remove('busy', 'live');
      row.classList.add('error');
      if (hint) { hint.className = 'hint err'; hint.textContent = `Falta selecionar: ${missing.join(', ')}`; }
      this.onStatus({ kind: 'error', text: `${t.name}: sem dispositivo selecionado` });
      return;
    }

    row.classList.remove('error', 'live');
    row.classList.add('busy');
    this.onStatus({ kind: 'busy', text: `A ligar via ${t.name}…` });

    let res;
    try {
      res = await api.connect(id, this.config[id], this.demo);
    } catch (e) {
      res = { ok: false, error: String(e) };
    }

    if (this.selected !== id) return;   // superseded while the request was in flight

    if (!res.ok) {
      row.classList.remove('busy');
      row.classList.add('error');
      if (hint) { hint.className = 'hint err'; hint.textContent = res.error; }
      this.onStatus({ kind: 'error', text: res.error });
    }
    // Success is not declared here — we wait for the WebSocket to report LIVE.
  }

  /** Called from main.js on every state frame. */
  applyLinkState(link) {
    if (!this.selected) return;
    const { row } = this.els[this.selected] || {};
    if (!row) return;
    // Never paint a row live off another transport's link.
    if (link.type !== 'none' && link.type !== this.selected) return;
    row.classList.toggle('busy', link.status === 'connecting' || link.status === 'handshaking');
    row.classList.toggle('live', link.status === 'live');
    row.classList.toggle('error', link.status === 'error');
  }

  reset() {
    for (const el of Object.values(this.els)) {
      el.row.classList.remove('selected', 'busy', 'live', 'error');
    }
    this.selected = null;
    this.onStatus({ kind: 'idle', text: 'À espera de seleção' });
  }
}
