import { describe, it, expect, vi } from 'vitest';
import { api, saveSession, getSession } from './api';

const response = (status, body) => ({ ok: status >= 200 && status < 300, status, json: async () => body });

describe('api helper', () => {
  it('UI-01: sends the saved token as a bearer header, and no header when signed out', async () => {
    const fetch = vi.fn(async () => response(200, []));
    vi.stubGlobal('fetch', fetch);

    saveSession({ token: 'abc.def.ghi', user: { id: 1, username: 'tommy' } });
    await api('/quizzes');
    expect(fetch).toHaveBeenLastCalledWith('/api/quizzes', expect.objectContaining({
      headers: expect.objectContaining({ Authorization: 'Bearer abc.def.ghi' }),
    }));

    saveSession(null);
    expect(getSession()).toBeNull();
    await api('/examples');
    expect(fetch.mock.calls.at(-1)[1].headers.Authorization).toBeUndefined();
  });

  it('UI-01: sends object bodies as JSON', async () => {
    const fetch = vi.fn(async () => response(200, { ok: true }));
    vi.stubGlobal('fetch', fetch);
    await api('/auth/login', { method: 'POST', body: { identifier: 'tommy', password: 'secret123' } });
    const [, options] = fetch.mock.calls[0];
    expect(options.headers['Content-Type']).toBe('application/json');
    expect(JSON.parse(options.body)).toEqual({ identifier: 'tommy', password: 'secret123' });
  });

  it('UI-02: surfaces the server message and status on errors', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => response(409, { code: 'CONFLICT', message: 'That username or email is already in use.' })));
    await expect(api('/auth/register', { method: 'POST', body: {} })).rejects.toMatchObject({
      message: 'That username or email is already in use.',
      status: 409,
    });
  });

  it('UI-02: explains when the server cannot be reached', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => { throw new TypeError('Failed to fetch'); }));
    await expect(api('/quizzes')).rejects.toThrow('Unable to reach Rally. Check your connection and try again.');
  });
});
