import React from 'react';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, act, fireEvent, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, useLocation } from 'react-router-dom';
import App from './App.jsx';
import { FakeWebSocket } from './test/FakeWebSocket.js';

const USER = { id: 1, username: 'tommy', email: 'tommy@usc.edu' };
const response = (status, body) => ({ ok: status >= 200 && status < 300, status, json: async () => body });

function Location() {
  return <div data-testid="location">{useLocation().pathname}</div>;
}

/** Renders the whole app at a path. routes maps "METHOD /api/path" to [status, body]. */
function renderAt(path, { signedIn = false, routes = {} } = {}) {
  if (signedIn) sessionStorage.setItem('rally.session', JSON.stringify({ token: 'test-token', user: USER }));
  const fetch = vi.fn(async (url, options = {}) => {
    const key = `${options.method || 'GET'} ${url}`;
    if (routes[key]) return response(...routes[key]);
    if (key === 'GET /api/auth/me') return response(200, USER);
    return response(200, []);
  });
  vi.stubGlobal('fetch', fetch);
  vi.stubGlobal('WebSocket', FakeWebSocket);
  const view = render(<MemoryRouter initialEntries={[path]}><App /><Location /></MemoryRouter>);
  return { ...view, fetch, user: userEvent.setup() };
}

const location = () => screen.getByTestId('location').textContent;

function gameState(overrides = {}) {
  return {
    code: '123456', title: 'Test quiz', status: 'QUESTION', reason: '', host: false,
    questionIndex: 0, questionCount: 3, serverNow: Date.now(), deadline: Date.now() + 20000,
    leaderboard: [{ id: 'p1', name: 'Traveler', score: 0, rank: 1, connected: true }],
    answerCount: 0, historyId: null, historySaved: false,
    question: {
      text: 'Which planet has the most prominent rings?', type: 'SINGLE', imageUrl: null, timeLimitSec: 20,
      options: [{ id: 11, text: 'Saturn' }, { id: 12, text: 'Mars' }, { id: 13, text: 'Venus' }, { id: 14, text: 'Mercury' }],
    },
    me: { id: 'p1', name: 'Traveler', score: 0, rank: 1, submitted: false, selectedOptionIds: [], points: null },
    ...overrides,
  };
}

/** Opens the player screen for game 123456 and connects its socket. */
function joinGame() {
  sessionStorage.setItem('rally.join.123456', JSON.stringify({ displayName: 'Traveler' }));
  const view = renderAt('/game/123456/player');
  const socket = FakeWebSocket.latest();
  act(() => socket.open());
  const serverSends = message => act(() => socket.receive(message));
  return { ...view, socket, serverSends };
}

beforeEach(() => { FakeWebSocket.instances = []; });

describe('accounts and routing', () => {
  it('AUTH-14: signing out clears the session and returns to the join page', async () => {
    const { user } = renderAt('/', { signedIn: true });
    await user.click(await screen.findByRole('button', { name: 'Sign out' }));
    expect(sessionStorage.getItem('rally.session')).toBeNull();
    expect(location()).toBe('/join');
  });

  it('UI-05: a signed-out visitor who opens /history is sent to sign in', () => {
    renderAt('/history');
    expect(location()).toBe('/login');
  });

  it('UI-04: a wrong password shows the server message and stays on the sign-in page', async () => {
    const { user } = renderAt('/login', {
      routes: { 'POST /api/auth/login': [401, { code: 'BAD_CREDENTIALS', message: 'The email, username, or password is incorrect.' }] },
    });
    await user.type(screen.getByLabelText('Email or username'), 'tommy');
    await user.type(screen.getByLabelText('Password'), 'wrong-password');
    await user.click(screen.getByRole('button', { name: /Sign in/ }));
    expect(await screen.findByRole('alert')).toHaveTextContent('The email, username, or password is incorrect.');
    expect(location()).toBe('/login');
    expect(sessionStorage.getItem('rally.session')).toBeNull();
  });

  it('UI-03: the join form keeps only six digits and opens the player screen', async () => {
    const { user } = renderAt('/join');
    const code = screen.getByLabelText('Game code');
    await user.type(code, '12a3b45678');
    expect(code).toHaveValue('123456');
    await user.type(screen.getByLabelText('Your display name'), 'Traveler');
    await user.click(screen.getByRole('button', { name: /Join the fun/ }));

    expect(location()).toBe('/game/123456/player');
    expect(JSON.parse(sessionStorage.getItem('rally.join.123456'))).toEqual({ displayName: 'Traveler' });
    expect(FakeWebSocket.latest().url).toMatch(/\/ws\/game\/123456$/);
  });
});

describe('quiz editor', () => {
  it('QUIZ-14: saving without a correct answer shows a message and sends nothing', async () => {
    const { user, fetch } = renderAt('/quizzes/new', { signedIn: true });
    await user.type(screen.getByLabelText('Quiz title'), 'CSCI 201 Review');
    await user.type(screen.getByLabelText('Your question'), 'Which collections implement List?');
    for (let i = 1; i <= 4; i++) await user.type(screen.getByLabelText(`Answer choice ${i}`), `Choice ${i}`);
    await user.selectOptions(screen.getByLabelText('Question type'), 'MULTI');
    await user.click(screen.getByRole('button', { name: 'Mark answer 1 as correct' }));
    await user.click(screen.getByRole('button', { name: /Save quiz/ }));

    expect(await screen.findByRole('alert')).toHaveTextContent('Question 1 needs a question, all answer choices, and a correct answer.');
    expect(fetch.mock.calls.some(([url, options]) => url === '/api/quizzes' && options?.method === 'POST')).toBe(false);
  });

  it('IMG-10: an image over 5 MB is rejected before uploading', async () => {
    const { container, fetch } = renderAt('/quizzes/new', { signedIn: true });
    const input = container.querySelector('input[type="file"]');
    const big = new File(['x'], 'big.png', { type: 'image/png' });
    Object.defineProperty(big, 'size', { value: 6 * 1024 * 1024 });
    fireEvent.change(input, { target: { files: [big] } });

    expect(await screen.findByRole('alert')).toHaveTextContent('Choose an image up to 5 MB.');
    expect(fetch.mock.calls.some(([url]) => url === '/api/images')).toBe(false);
  });
});

describe('live game player screen', () => {
  it('sends JOIN with the saved display name when the socket opens', () => {
    const { socket } = joinGame();
    expect(socket.sent[0]).toEqual({ type: 'JOIN', displayName: 'Traveler' });
  });

  it('UI-06: a SINGLE answer is sent and then shown as locked in', async () => {
    const { socket, serverSends, user } = joinGame();
    serverSends({ type: 'QUESTION', state: gameState() });

    await user.click(screen.getByRole('button', { name: /Saturn/ }));
    await user.click(screen.getByRole('button', { name: /Lock in answer/ }));
    expect(socket.sent.at(-1)).toEqual({ type: 'ANSWER', questionIndex: 0, optionIds: [11] });

    serverSends({ type: 'ANSWER_ACK', accepted: true, state: gameState({ me: { ...gameState().me, submitted: true, selectedOptionIds: [11] } }) });
    expect(screen.getByText('Locked in. Fingers crossed.')).toBeInTheDocument();
  });

  it('UI-07: MULTI answers toggle on and off before being sent', async () => {
    const { socket, serverSends, user } = joinGame();
    const multi = gameState({ question: { ...gameState().question, type: 'MULTI', text: 'Which are mammals?',
      options: [{ id: 21, text: 'Dolphin' }, { id: 22, text: 'Bat' }, { id: 23, text: 'Shark' }, { id: 24, text: 'Penguin' }] } });
    serverSends({ type: 'QUESTION', state: multi });

    await user.click(screen.getByRole('button', { name: /Dolphin/ }));
    await user.click(screen.getByRole('button', { name: /Bat/ }));
    await user.click(screen.getByRole('button', { name: /Dolphin/ }));
    await user.click(screen.getByRole('button', { name: /Lock in 1 answers/ }));
    expect(socket.sent.at(-1)).toEqual({ type: 'ANSWER', questionIndex: 0, optionIds: [22] });
  });

  it('UI-08: the reveal highlights the correct answer and shows the points earned', () => {
    const { serverSends } = joinGame();
    serverSends({ type: 'REVEAL', state: gameState({
      status: 'REVEAL', correctOptionIds: [11],
      me: { id: 'p1', name: 'Traveler', score: 750, rank: 1, submitted: true, selectedOptionIds: [11], points: 750 },
    }) });
    expect(screen.getByText('+750')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /Saturn/ })).toHaveClass('answer-correct');
    expect(screen.getByRole('button', { name: /Mars/ })).toHaveClass('answer-wrong');
  });

  it('UI-09: a server ERROR message is shown to the player', () => {
    const { serverSends } = joinGame();
    serverSends({ type: 'ERROR', code: 'NAME_TAKEN', message: 'That display name is already in this game.' });
    expect(screen.getByRole('alert')).toHaveTextContent('That display name is already in this game.');
  });

  it('TIME-07: the countdown uses the server clock, not the browser clock', async () => {
    const { serverSends } = joinGame();
    const serverNow = Date.now() - 3000; // the browser clock runs 3 seconds fast
    serverSends({ type: 'QUESTION', state: gameState({ serverNow, deadline: serverNow + 10000 }) });
    // After the first 100 ms clock tick the countdown reads 10 s; a browser-clock bug would show 7 s.
    expect(await screen.findByText('10s')).toBeInTheDocument();
  });

  it('RANK-07: the final screen shows a top-3 podium, top-5 standings, and the player\'s own rank', () => {
    const { container, serverSends } = joinGame();
    const leaderboard = Array.from({ length: 7 }, (_, i) => ({ id: `p${i + 1}`, name: `Player ${i + 1}`, score: 700 - i * 100, rank: i + 1, connected: true }));
    serverSends({ type: 'GAME_OVER', state: gameState({
      status: 'COMPLETED', leaderboard, historySaved: true,
      me: { id: 'p7', name: 'Player 7', score: 100, rank: 7, submitted: false, selectedOptionIds: [], points: 0 },
    }) });
    expect(container.querySelectorAll('.podium-place')).toHaveLength(3);
    expect(container.querySelectorAll('.final-results .leaderboard-row')).toHaveLength(5);
    expect(container.querySelector('.your-result')).toHaveTextContent('You finished #7 with 100 points.');
  });

  it('DISC-08: a dropped connection offers to rejoin and warns guests about their score', async () => {
    const { socket, serverSends } = joinGame();
    serverSends({ type: 'QUESTION', state: gameState() });
    act(() => socket.drop());
    expect(screen.getByRole('button', { name: /Rejoin game/ })).toBeInTheDocument();
    expect(screen.getByText(/Rejoining as a guest starts you at zero/)).toBeInTheDocument();
  });
});
