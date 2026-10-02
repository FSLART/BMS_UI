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
    // No bus picker: which bus this is gets worked out from the traffic, not
    // asked. Charger frames on the wire mean the pack is on the handcart;
    // their absence means it is in the car. The one thing that cannot be
    // inferred is the bitrate, because at the wrong speed nothing arrives at
    // all -- hence the hint on that field.
    fields: [
      // Changing the backend changes what a "channel" even is, so it rescans.
      { k: 'backend', label: 'Interface', type: 'select', from: 'can_backends', def: 'slcan', rescans: true },
      { k: 'channel', label: 'Canal', type: 'select', fromScan: true },
      // 1 Mbit por omissão: é o barramento do carro, onde o acumulador passa a
      // maior parte do tempo.
      { k: 'bitrate', label: 'Bitrate', type: 'select', from: 'can_bitrates', def: 1000000,
        fmt: (v) => (v >= 1e6 ? `${v / 1e6} Mbit/s` : `${v / 1000} kbit/s`),
        hintFromCar: 'buses' },
      { k: 'dbc', label: 'Ficheiro DBC', type: 'file', accept: '.dbc', upload: 'dbc',
        placeholder: 'Opcional: usa uma DBC diferente da do Git',
        summary: (r) => `${r.name} — ${r.messages} mensagens` },
    ],
  },
  {
    id: 'wifi',
    name: 'WiFi',
    scan: 'wifi',
    // Sem .elf não há ligação: o servidor GDB só fala em endereços, e quem
    // sabe onde está o live_debug é o firmware que foi gravado.
    required: ['host', 'elf'],
    // Uma linha por campo, como no CAN: o nome do .elf é comprido e o resto
    // lê-se pela ordem das decisões — onde está, e o que lá ler.
    stack: true,
    fields: [
      { k: 'ssid', label: 'Rede', type: 'select', fromScan: true },
      // O Black Magic no ESP32 levanta a própria rede em 192.168.4.1 e serve
      // o protocolo do GDB em 2345.
      { k: 'host', label: 'Host / IP', type: 'text', def: '192.168.4.1' },
      { k: 'port', label: 'Porta', type: 'text', def: '2345' },
      { k: 'elf', label: 'Firmware (.elf)', type: 'file', accept: '.elf', upload: 'elf',
        wide: true,
        placeholder: 'Obrigatório: o .elf que está gravado no AMS',
        clearTitle: 'Esquecer este .elf',
        summary: (r) => `${r.name} — ${r.symbol} em ${r.address}, ${r.size} B` },
      // O firmware refresca a struct uma vez por segundo. Pedir mais do que
      // o dobro só gasta rádio a reler a mesma fotografia.
      { k: 'hz', label: 'Leituras/s', type: 'select', values: [1, 2, 4, 8], def: 2,
        fmt: (v) => `${v} Hz` },
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
      if (f.fromCar) {
        // Options that belong to the chosen car, not to the app: the buses are
        // a property of that car's wiring.
        for (const item of (this.car && this.car[f.fromCar]) || []) {
          const o = document.createElement('option');
          o.value = item.id;
          o.textContent = item.name;
          o.title = item.detail || '';
          input.appendChild(o);
        }
      } else if (f.fromScan) {
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
    } else if (f.fromCar && input.options && input.options.length) {
      this.config[t.id][f.k] = input.value;
    }
    input.dataset.key = f.k;

    input.addEventListener('change', async () => {
      this.config[t.id][f.k] = input.value;
      // O nome acompanha o endereço escolhido; sem isto ficava o do primeiro
      // da lista, e a consola dizia "ACU_V3" com a ligação feita ao BMS.
      if (t.id === 'ble' && f.fromScan) {
        this.config[t.id].name = input.selectedOptions[0]?.textContent || '';
      }
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

    if (f.hintFromCar) {
      const hint = this._bitrateHint(f);
      if (hint) {
        const note = document.createElement('span');
        note.className = 'field-note';
        note.textContent = hint;
        wrap.appendChild(note);
      }
    }

    return wrap;
  }

  /**
   * Which bus each speed belongs to.
   *
   * The only thing about the two buses the app cannot work out for itself: at
   * the wrong bitrate nothing arrives, so there is no traffic to infer from.
   * Said once, under the field, instead of as a mode to choose.
   */
  _bitrateHint(f) {
    const buses = (this.car && this.car[f.hintFromCar]) || [];
    if (!buses.length) return '';
    const speed = (b) => (b >= 1e6 ? `${b / 1e6} Mbit` : `${b / 1000}k`);
    return buses.map((b) => `${speed(b.bitrate)} = ${b.name.toLowerCase()}`).join(' · ');
  }

  /** The chosen car, so car-specific options (its buses) can be offered. */
  async setCar(car) {
    this.car = car;
    await this.mount();
  }

  /**
   * Pick a file from the machine — hoje uma .dbc ou o .elf do firmware.
   *
   * The browser never exposes a real filesystem path, so the bytes are sent to
   * the backend, which validates them and keeps the file. What lands in the
   * config is the path the backend wrote, which is what the transport can
   * actually open. `f.upload` diz qual dos dois validadores corre, e
   * `f.summary` escreve o que se mostra quando ele aceita.
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
    clear.title = f.clearTitle || 'Voltar às DBCs do carro';
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

      const res = await api.upload(f.upload || 'dbc', file);
      if (!res.ok) {
        name.className = 'file-name err';
        name.textContent = res.error || 'Falhou';
        delete this.config[t.id][f.k];
        return;
      }
      name.className = 'file-name ok';
      name.textContent = f.summary ? f.summary(res) : res.name;
      this.config[t.id][f.k] = res.path;
      if (this.selected === t.id) this._reconnectSoon();
    });

    box.append(btn, name, clear, input);
    return box;
  }

  _wireDemoToggle() {
    const toggle = document.getElementById('demo-toggle');
    const sw = document.getElementById('demo-switch');
    // mount() runs again whenever the car changes, and the switch lives outside
    // the rows it rebuilds. Wiring twice made every click toggle twice, which
    // looks exactly like the switch not working.
    if (toggle.dataset.wired) return;
    toggle.dataset.wired = '1';
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
    // O RN4871 do carro e hardware fixo: fica sempre em primeiro e escolhido,
    // mesmo que o scan nao o tenha visto -- o backend insiste ate o encontrar.
    const fixed = t.id === 'ble' && this.car && this.car.ble_address;
    if (fixed) {
      const mac = this.car.ble_address.toUpperCase();
      res = { ...res, items: [
        { value: this.car.ble_address, label: `BMS · RN4871 (${mac})`, detail: 'Fixo no perfil do carro' },
        ...res.items.filter((it) => it.value.toUpperCase() !== mac),
      ] };
    }
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

    // Em modo demo nao se abre nada: o simulador nao precisa de porta, de
    // dispositivo nem de host. Exigir a selecao aqui impedia o pedido de sair
    // sequer -- e num portatil sem adaptador BLE, ou sem nada emparelhado, a
    // lista vem vazia e nao havia forma nenhuma de percorrer o fluxo.
    const missing = this.demo ? [] : t.required.filter((k) => !this.config[id][k]);
    if (missing.length) {
      row.classList.remove('busy', 'live');
      row.classList.add('error');
      // Pelo rótulo e não pela chave: "elf" não diz nada a ninguém, "Firmware
      // (.elf)" é exatamente o campo que está por preencher no ecrã.
      const labels = missing.map((k) => (t.fields.find((f) => f.k === k) || {}).label || k);
      if (hint) { hint.className = 'hint err'; hint.textContent = `Falta preencher: ${labels.join(', ')}`; }
      this.onStatus({ kind: 'error', text: `${t.name}: falta ${labels.join(', ')}` });
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
