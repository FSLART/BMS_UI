/**
 * In-app console.
 *
 * Mirrors what the terminal shows: python-can's vendor-driver probe messages
 * plus the app's own connection events. Polls only while open, so a closed
 * console costs nothing.
 */

const LEVEL_CLASS = {
  DEBUG: 'lv-debug',
  INFO: 'lv-info',
  WARNING: 'lv-warn',
  ERROR: 'lv-error',
  CRITICAL: 'lv-error',
};

const POLL_MS = 1000;

export class LogConsole {
  constructor() {
    this.open = false;
    this.seq = 0;
    this.timer = null;
    this.unread = 0;
    this.worstUnread = null;
    this._build();
  }

  _build() {
    this.panel = document.createElement('div');
    this.panel.className = 'log-console';
    this.panel.setAttribute('role', 'dialog');
    this.panel.setAttribute('aria-label', 'Consola');
    this.panel.innerHTML = `
      <div class="log-head">
        <span class="log-title">Consola</span>
        <span class="log-count" data-role="count"></span>
        <button class="log-btn" data-role="pick" title="Clicar no modelo devolve as coordenadas da âncora">âncora</button>
        <button class="log-btn" data-role="clear" title="Limpar">limpar</button>
        <button class="log-btn" data-role="close" title="Fechar (Esc)">✕</button>
      </div>
      <div class="log-body" data-role="body"></div>`;
    document.body.appendChild(this.panel);

    this.body = this.panel.querySelector('[data-role="body"]');
    this.countEl = this.panel.querySelector('[data-role="count"]');

    this.panel.querySelector('[data-role="close"]').addEventListener('click', () => this.hide());
    this.panel.querySelector('[data-role="clear"]').addEventListener('click', async () => {
      await fetch('/api/logs/clear', { method: 'POST' });
      this.body.innerHTML = '';
      this._updateCount();
    });

    this.pickBtn = this.panel.querySelector('[data-role="pick"]');
    this.pickBtn.addEventListener('click', () => this.onTogglePicker?.());

    // Clicks inside must not reach the outside-click handler below.
    this.panel.addEventListener('mousedown', (ev) => ev.stopPropagation());

    document.addEventListener('keydown', (ev) => {
      if (ev.key === 'Escape' && this.open) this.hide();
    });
    document.addEventListener('mousedown', () => { if (this.open) this.hide(); });
  }

  /** Wire every console button on the page (one per screen header). */
  attachButtons() {
    for (const btn of document.querySelectorAll('[data-console-toggle]')) {
      btn.addEventListener('mousedown', (ev) => {
        ev.stopPropagation();       // otherwise the outside-click closes it again
        ev.preventDefault();
        this.toggle();
      });
    }
    this.badges = [...document.querySelectorAll('[data-console-badge]')];
  }

  toggle() { this.open ? this.hide() : this.show(); }

  /** Reflect picker state and let the caller drive it. */
  setPicking(on) {
    this.picking = on;
    this.pickBtn.classList.toggle('on', on);
  }

  /** Append a line locally, without waiting for the backend log to echo it. */
  note(message, level = 'INFO') {
    this.body.appendChild(this._line({ ts: Date.now() / 1000, level, logger: 'ui', message }));
    this.body.scrollTop = this.body.scrollHeight;
    this._updateCount();
  }

  show() {
    this.open = true;
    this.panel.classList.add('shown');
    this.unread = 0;
    this.worstUnread = null;
    this._paintBadges();
    this._poll().then(() => { this.body.scrollTop = this.body.scrollHeight; });
    this.timer = setInterval(() => this._poll(), POLL_MS);
  }

  hide() {
    this.open = false;
    this.panel.classList.remove('shown');
    clearInterval(this.timer);
    this.timer = null;
  }

  /** Background tick so the badge shows activity even while closed. */
  startBadgePolling() {
    setInterval(() => { if (!this.open) this._poll(); }, 3000);
    this._poll();
  }

  async _poll() {
    let data;
    try {
      data = await fetch(`/api/logs?since=${this.seq}`).then((r) => r.json());
    } catch {
      return;
    }
    if (!data.entries.length) return;
    this.seq = data.seq;

    // Always render, open or not. Polling while closed must not consume the
    // history — otherwise opening the console shows an empty panel.
    const atBottom = this.body.scrollHeight - this.body.scrollTop - this.body.clientHeight < 30;
    for (const e of data.entries) this.body.appendChild(this._line(e));
    while (this.body.childElementCount > 800) this.body.firstElementChild.remove();
    if (this.open && atBottom) this.body.scrollTop = this.body.scrollHeight;
    this._updateCount();

    if (!this.open) {
      this.unread += data.entries.length;
      for (const e of data.entries) {
        if (e.level === 'ERROR' || e.level === 'CRITICAL') this.worstUnread = 'error';
        else if (e.level === 'WARNING' && this.worstUnread !== 'error') this.worstUnread = 'warn';
      }
      this._paintBadges();
    }
  }

  _line(e) {
    const el = document.createElement('div');
    el.className = `log-line ${LEVEL_CLASS[e.level] || 'lv-info'}`;
    const t = new Date(e.ts * 1000);
    const hh = String(t.getHours()).padStart(2, '0');
    const mm = String(t.getMinutes()).padStart(2, '0');
    const ss = String(t.getSeconds()).padStart(2, '0');
    el.innerHTML = `<span class="log-ts">${hh}:${mm}:${ss}</span>` +
                   `<span class="log-src">${e.logger}</span>` +
                   `<span class="log-msg"></span>`;
    el.querySelector('.log-msg').textContent = e.message;   // never inject markup
    return el;
  }

  _updateCount() {
    this.countEl.textContent = `${this.body.childElementCount} linhas`;
  }

  _paintBadges() {
    for (const b of this.badges || []) {
      b.textContent = this.unread > 99 ? '99+' : String(this.unread);
      b.classList.toggle('shown', this.unread > 0);
      b.classList.toggle('warn', this.worstUnread === 'warn');
      b.classList.toggle('error', this.worstUnread === 'error');
    }
  }
}
