const json = async (res) => {
  if (!res.ok) throw new Error(`${res.status} ${res.statusText}`);
  return res.json();
};

export const api = {
  options: () => fetch('/api/options').then(json),
  scan: (kind, backend) =>
    fetch(`/api/scan/${kind}${backend ? `?backend=${encodeURIComponent(backend)}` : ''}`).then(json),
  connect: (type, config, demo_fallback) =>
    fetch('/api/connect', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ type, config, demo_fallback }),
    }).then(json),
  disconnect: () => fetch('/api/disconnect', { method: 'POST' }).then(json),

  /** Send a declared CAN command. The backend re-checks every guard. */
  command: (id, on) =>
    fetch('/api/command', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ id, on }),
    })
      .then(json)
      .catch((e) => ({ ok: false, error: String(e.message || e) })),

  /**
   * Send a file the user picked. Raw body, not multipart: the backend then
   * needs no python-multipart, which matters for an app that ships offline.
   *
   * `kind` is 'dbc' or 'elf'. Both validate before storing, so what comes back
   * is either a usable path or the reason the file was refused:
   *   dbc -> { ok, path, name, messages }
   *   elf -> { ok, path, name, symbol, address, size, extras, extras_total }
   */
  upload: (kind, file) =>
    fetch(`/api/${kind}/upload?name=${encodeURIComponent(file.name)}`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/octet-stream' },
      body: file,
    })
      .then(json)
      .catch((e) => ({ ok: false, error: String(e.message || e) })),
};

/** WebSocket with auto-reconnect. `onState` gets the parsed BmsState. */
export function openStateSocket(onState) {
  let sock = null;
  let retry = 0;

  const connect = () => {
    sock = new WebSocket(`ws://${location.host}/ws`);
    sock.onmessage = (ev) => {
      try { onState(JSON.parse(ev.data)); } catch (e) { console.error('bad frame', e); }
    };
    sock.onopen = () => { retry = 0; };
    sock.onclose = () => {
      retry = Math.min(retry + 1, 10);
      setTimeout(connect, 250 * retry);
    };
    sock.onerror = () => sock.close();
  };

  connect();
  return () => sock && sock.close();
}
