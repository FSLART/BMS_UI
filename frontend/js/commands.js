/**
 * Sending CAN frames to the BMS.
 *
 * This is the only part of the app that reaches out to the vehicle. Everything
 * else observes; this acts. The design follows from that:
 *
 * - a command is two clicks, never one, and the second is in a dialog that
 *   names the frame about to go out
 * - the buttons are dead unless the link is live and out of demo, so there is
 *   no moment where pressing one might or might not have done something
 * - the backend re-checks all of it; these are for the operator, not security
 */

import { api } from './api.js';

const $ = (id) => document.getElementById(id);

export class Commands {
  constructor(root, { only = null } = {}) {
    this.root = root;
    // Restrict this panel to a subset. The charging page shows only the
    // charging command; the overview shows the lot.
    this.only = only;
    this.commands = [];
    this.enabled = false;
    this.pending = null;
    this.els = {};
    this._wireDialog();
  }

  _wireDialog() {
    const box = $('cmd-confirm');
    if (!box) return;
    $('cmd-cancel').addEventListener('click', () => this._close());
    $('cmd-send').addEventListener('click', () => this._confirm());
    // Clicking the backdrop cancels; clicking the dialog itself does not.
    box.addEventListener('click', (ev) => { if (ev.target === box) this._close(); });
    document.addEventListener('keydown', (ev) => {
      if (ev.key === 'Escape' && !box.hidden) this._close();
    });
  }

  /** Rebuild the buttons for a car. Called once per car, not per frame. */
  setCommands(commands) {
    const list = (commands || []).filter((c) => !this.only || this.only.includes(c.id));
    const sig = list.map((c) => c.id).join(',');
    if (sig === this._sig) return;
    this._sig = sig;
    this.commands = list;
    this.root.innerHTML = '';
    this.els = {};

    if (!this.commands.length) {
      this.root.innerHTML = '<div class="empty-note">Este carro não declara comandos.</div>';
      return;
    }

    for (const cmd of this.commands) {
      const row = document.createElement('div');
      row.className = `cmd${cmd.danger ? ' danger' : ''}`;
      row.innerHTML = `
        <span class="cmd-label">${cmd.label}</span>
        <span class="cmd-buttons">
          <button class="cmd-btn" data-on="1">ligar</button>
          <button class="cmd-btn off" data-on="0">desligar</button>
        </span>`;
      row.title = cmd.detail || '';
      row.querySelectorAll('.cmd-btn').forEach((b) => {
        b.addEventListener('click', () => this._ask(cmd, b.dataset.on === '1'));
      });
      this.root.appendChild(row);
      this.els[cmd.id] = row;
    }
    this._applyEnabled();
  }

  /**
   * Live and not simulated. Anything else and the buttons go dead: a command
   * fired at a demo pack looks identical to one fired at a real one, and that
   * is precisely the confusion worth designing out.
   */
  setLink(state) {
    const live = state.link.status === 'live';
    const next = live && !state.link.demo && !state.stale;
    if (next === this.enabled) return;
    this.enabled = next;
    this._applyEnabled();
  }

  _applyEnabled() {
    const why = this.enabled ? '' : 'Só com ligação ativa ao BMS, fora do modo demo.';
    for (const row of Object.values(this.els)) {
      row.classList.toggle('off-line', !this.enabled);
      row.querySelectorAll('.cmd-btn').forEach((b) => { b.disabled = !this.enabled; });
      if (why) row.title = why;
    }
  }

  _ask(cmd, on) {
    if (!this.enabled) return;
    this.pending = { cmd, on };
    $('cmd-title').textContent = `${cmd.label} — ${on ? 'ligar' : 'desligar'}`;
    $('cmd-detail').textContent = cmd.detail || '';
    $('cmd-frame').textContent = `${cmd.id} = ${on ? 'ON' : 'OFF'}`;
    $('cmd-danger').hidden = !cmd.danger;
    $('cmd-confirm').hidden = false;
    $('cmd-send').classList.toggle('danger', !!cmd.danger);
    $('cmd-cancel').focus();
  }

  _close() {
    this.pending = null;
    $('cmd-confirm').hidden = true;
  }

  async _confirm() {
    if (!this.pending) return;
    const { cmd, on } = this.pending;
    const btn = $('cmd-send');
    btn.disabled = true;
    btn.textContent = 'a enviar…';
    const res = await api.command(cmd.id, on);
    btn.disabled = false;
    btn.textContent = 'Enviar';
    if (!res.ok) {
      $('cmd-frame').textContent = res.error || 'falhou';
      $('cmd-frame').classList.add('err');
      return;
    }
    $('cmd-frame').classList.remove('err');
    this._close();
  }
}
