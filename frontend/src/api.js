const backendOrigin = (import.meta.env.VITE_BACKEND_ORIGIN || '').trim().replace(/\/+$/, '');

export function imageSource(path) {
  return path.startsWith('/images/') ? `${backendOrigin}${path}` : path;
}

export function gameSocketUrl(code) {
  const url = new URL(`/ws/game/${code}`, backendOrigin || window.location.origin);
  url.protocol = url.protocol === 'https:' ? 'wss:' : 'ws:';
  return url.href;
}

export function getSession() {
  try { return JSON.parse(sessionStorage.getItem('rally.session')); } catch { return null; }
}
export function saveSession(value) {
  if (value) sessionStorage.setItem('rally.session', JSON.stringify(value));
  else sessionStorage.removeItem('rally.session');
}
export async function api(path, options = {}) {
  const token = getSession()?.token;
  const headers = { ...(token ? { Authorization: `Bearer ${token}` } : {}), ...options.headers };
  let body = options.body;
  if (body && !(body instanceof FormData)) { headers['Content-Type'] = 'application/json'; body = JSON.stringify(body); }
  let response;
  try { response = await fetch(`${backendOrigin}/api${path}`, { ...options, headers, body }); }
  catch { throw new Error('Unable to reach Rally. Check your connection and try again.'); }
  const result = await response.json().catch(() => ({}));
  if (!response.ok) { const error = new Error(result.message || 'Something went wrong. Please try again.'); error.status = response.status; throw error; }
  return result;
}
