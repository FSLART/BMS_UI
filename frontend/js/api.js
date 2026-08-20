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
