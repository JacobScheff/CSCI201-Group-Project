import React, { createContext, useContext, useEffect, useRef, useState } from 'react';
import { Link, NavLink, Navigate, Route, Routes, useLocation, useNavigate, useParams } from 'react-router-dom';
import { ArrowRight, ArrowLeft, Plus, LayoutGrid, Sparkles, History, LogOut, Play, Pencil, Trash2, Search, X, Check, Copy, Clock3, Users, Trophy, Zap, ChevronRight, ImagePlus, CircleHelp, BookOpen, Radio, LoaderCircle, CheckCircle2, Triangle, Diamond, Circle, Square, Flag } from 'lucide-react';
import { api, getSession, saveSession } from './api';

const Context = createContext(null);
const useApp = () => useContext(Context);
const shapes = [Triangle, Diamond, Circle, Square];
const colors = ['coral', 'blue', 'gold', 'green'];
const date = value => new Date(value).toLocaleDateString(undefined, { month: 'short', day: 'numeric', year: 'numeric' });
function Spinner() { return <div className="loading" role="status"><LoaderCircle className="spin" size={24} /><span>Getting things ready…</span></div>; }
function ErrorBox({ error }) { return error ? <div className="error-box" role="alert"><CircleHelp size={18} />{error.message || error}</div> : null; }
function Button({ children, busy, className = '', ...props }) { return <button className={`button ${className}`} disabled={busy || props.disabled} {...props}>{busy ? <LoaderCircle size={17} className="spin" /> : null}{children}</button>; }
function Modal({ title, children, close, wide }) {
  const ref = useRef(null);
  useEffect(() => {
    const before = document.activeElement;
    ref.current?.focus();
    const handler = e => {
      if (e.key === 'Escape') close();
      if (e.key === 'Tab') {
        const items = ref.current?.querySelectorAll('button,a,input,select,textarea,[tabindex="0"]');
        if (!items?.length) return;
        const first = items[0], last = items[items.length - 1];
        if (e.shiftKey && document.activeElement === first) { e.preventDefault(); last.focus(); }
        else if (!e.shiftKey && document.activeElement === last) { e.preventDefault(); first.focus(); }
      }
    };
    document.addEventListener('keydown', handler);
    return () => { document.removeEventListener('keydown', handler); before?.focus(); };
  }, [close]);
  return <div className="modal-backdrop" onClick={e => { if (e.target === e.currentTarget) close(); }}><section className={`modal ${wide ? 'wide' : ''}`} role="dialog" aria-modal="true" aria-label={title} tabIndex={-1} ref={ref}><div className="modal-head"><h2>{title}</h2><button className="icon-button" onClick={close} aria-label="Close dialog"><X size={20} /></button></div>{children}</section></div>;
}
function useData(path) {
  const [state, setState] = useState({ loading: true, data: null, error: null });
  const [version, setVersion] = useState(0);
  useEffect(() => {
    let active = true;
    setState(s => ({ ...s, loading: true, error: null }));
    api(path).then(data => { if (active) setState({ loading: false, data, error: null }); }).catch(error => { if (active) setState({ loading: false, data: null, error }); });
    return () => { active = false; };
  }, [path, version]);
  return { ...state, reload: () => setVersion(v => v + 1) };
}
function Logo() { return <Link to="/" className="logo" aria-label="Rally home"><span className="logo-symbol"><Zap size={23} fill="currentColor" /></span>rally<span className="logo-dot">.</span></Link>; }
function Shell({ children }) {
  const { session, signOut } = useApp();
  const location = useLocation();
  const isGame = location.pathname.startsWith('/game/');
  if (isGame) return children;
  if (!session) return <div className="public-shell"><header className="public-header"><Logo /><nav><NavLink to="/join">Join a game</NavLink><Link to="/login" className="button secondary small">Sign in <ArrowRight size={16} /></Link></nav></header>{children}<footer className="public-footer"><span>A little knowledge. A lot of good times.</span><span>Made for curious minds <Sparkles size={14} /></span></footer></div>;
  return <div className="app-shell"><aside className="sidebar"><Logo /><div className="workspace-label">YOUR WORKSPACE</div><nav className="side-nav"><NavLink to="/" end><LayoutGrid size={19} />My quizzes</NavLink><NavLink to="/examples"><Sparkles size={19} />Explore templates</NavLink><NavLink to="/history"><History size={19} />Game history</NavLink></nav><div className="sidebar-bottom"><div className="sidebar-note"><span className="note-shape">✳</span><strong>Better together.</strong><p>Good questions bring<br />great people together.</p><Link to="/join">Join a game <ArrowRight size={15} /></Link></div><div className="profile"><span className="avatar">{session.user.username[0].toUpperCase()}</span><div><strong>{session.user.username}</strong><small>Personal workspace</small></div><button onClick={signOut} className="icon-button" aria-label="Sign out"><LogOut size={17} /></button></div></div></aside><div className="workspace"><header className="topbar"><span className="topbar-label"><span className="status-dot" />Let’s get everyone playing</span><Link to="/join" className="button secondary small"><Radio size={16} />Join a game</Link></header><main className="main-content">{children}</main><footer className="workspace-footer">Made for a little friendly competition.<span>RALLY / PLAY TOGETHER</span></footer></div></div>;
}
export default function App() {
  const [session, setSession] = useState(getSession);
  const [toast, setToast] = useState('');
  const navigate = useNavigate();
  useEffect(() => { if (!toast) return; const timeout = setTimeout(() => setToast(''), 4500); return () => clearTimeout(timeout); }, [toast]);
  useEffect(() => {
    if (session) api('/auth/me').catch(error => { if (error.status === 401) { saveSession(null); setSession(null); } });
  }, []);
  const signIn = value => { saveSession(value); setSession(value); navigate('/'); };
  const signOut = () => { saveSession(null); setSession(null); navigate('/join'); };
  const protect = element => session ? element : <Navigate to="/login" replace />;
  return <Context.Provider value={{ session, signIn, signOut, notify: setToast }}><Shell><Routes>
    <Route path="/" element={session ? <Dashboard /> : <Join />} />
    <Route path="/join" element={<Join />} />
    <Route path="/login" element={session ? <Navigate to="/" replace /> : <Auth />} />
    <Route path="/register" element={session ? <Navigate to="/" replace /> : <Auth register />} />
    <Route path="/examples" element={protect(<Examples />)} />
    <Route path="/quizzes/new" element={protect(<Editor />)} />
    <Route path="/quizzes/:id/edit" element={protect(<Editor />)} />
    <Route path="/history" element={protect(<GameHistory />)} />
    <Route path="/history/:id" element={protect(<HistoryDetail />)} />
    <Route path="/game/:code/:role" element={<LiveGame />} />
    <Route path="*" element={<div className="empty-state"><CircleHelp size={32} /><h1>That page wandered off.</h1><Link to="/" className="button">Back to Rally</Link></div>} />
  </Routes></Shell>{toast && <div className="toast" role="status"><CheckCircle2 size={18} />{toast}<button className="icon-button" onClick={() => setToast('')} aria-label="Dismiss notification"><X size={16} /></button></div>}</Context.Provider>;
}
function Join() {
  const [code, setCode] = useState('');
  const [name, setName] = useState('');
  const navigate = useNavigate();
  const { session } = useApp();
  const submit = e => {
    e.preventDefault();
    sessionStorage.setItem(`rally.join.${code}`, JSON.stringify({ displayName: name.trim() }));
    navigate(`/game/${code}/player`);
  };
  return <main className="join-page"><div className="join-copy"><span className="eyebrow"><span className="status-dot" />THINK FAST. PLAY TOGETHER.</span><h1>A little friendly<br /><span>competition.</span></h1><p>Big questions. Quick thinking. Good company.<br className="desktop-br" /> Gather your people and make a game of it.</p><div className="join-form-card"><div className="inline-title"><span className="tiny-icon"><Play size={18} fill="currentColor" /></span><h2>Let’s get you in.</h2></div><form onSubmit={submit}><label>Game code<input autoComplete="off" aria-label="Game code" inputMode="numeric" pattern="[0-9]{6}" maxLength={6} placeholder="Enter 6-digit code" value={code} onChange={e => setCode(e.target.value.replace(/\D/g, '').slice(0, 6))} required /></label><label>Your display name<input autoComplete="nickname" placeholder={session?.user.username || 'What should we call you?'} value={name} onChange={e => setName(e.target.value)} maxLength={20} minLength={1} required /></label><Button type="submit" className="full">Join the fun <ArrowRight size={18} /></Button></form><small>No account needed. Just your game face.</small></div><p className="host-invite">Got a quiz in you? <Link to={session ? '/' : '/register'}>Create & host a game <ArrowRight size={14} /></Link></p></div><div className="join-art" aria-hidden="true"><span className="art-spark spark-one">✳</span><span className="art-spark spark-two">✦</span><span className="art-ring" /><div className="floating-pill"><span className="status-dot" />The whole room is in.</div><div className="sample-question"><div className="sample-meta"><span>THE CURIOUS MINDS CLUB</span><span><Clock3 size={13} /> 20s</span></div><span className="sample-number">QUESTION 01</span><h2>Ready to surprise<br />yourself?</h2><div className="sample-answers">{['Absolutely.', 'Let’s find out.', 'Born ready.', 'All of the above.'].map((text, i) => { const Shape = shapes[i]; return <div className={`sample-answer ${colors[i]}`} key={text}><Shape size={19} fill="currentColor" /><span>{text}</span>{i === 3 && <Check size={16} />}</div>; })}</div></div><div className="score-sticker"><Trophy size={27} /><div><strong>Good times win.</strong><small>Bragging rights included.</small></div></div><div className="art-caption"><span>01 — GATHER</span><span>02 — PLAY</span><span>03 — CELEBRATE</span></div></div></main>;
}
function Auth({ register = false }) {
  const { signIn } = useApp();
  const [error, setError] = useState(null);
  const [busy, setBusy] = useState(false);
  const submit = async e => {
    e.preventDefault(); setBusy(true); setError(null);
    const fields = Object.fromEntries(new FormData(e.currentTarget));
    try { signIn(await api(`/auth/${register ? 'register' : 'login'}`, { method: 'POST', body: fields })); }
    catch (error) { setError(error); } finally { setBusy(false); }
  };
  return <main className="auth-page"><div className="auth-aside"><span className="eyebrow">GOOD QUESTIONS START HERE</span><h1>{register ? <>Bring your<br />curiosity.</> : <>Welcome<br />back, quizmaster.</>}</h1><p>Create something worth playing.<br />Then bring everyone together.</p><div className="auth-graphic"><Sparkles size={100} strokeWidth={1.2} /><span>There’s a quiz in all of us.</span></div></div><section className="auth-card"><span className="eyebrow">YOUR NEXT GREAT GAME</span><h2>{register ? 'Make yourself at home.' : 'Let’s play again.'}</h2><p>{register ? 'A free account. Endless friendly rivalries.' : 'Sign in to your Rally workspace.'}</p><ErrorBox error={error} /><form onSubmit={submit} key={register ? 'register' : 'login'}>{register ? <><label>Username<input name="username" autoComplete="username" placeholder="curious_mind" pattern="[a-zA-Z0-9_]{3,30}" title="3–30 letters, numbers, or underscores" required /></label><label>Email address<input type="email" name="email" autoComplete="email" maxLength={255} placeholder="you@example.com" required /></label></> : <label>Email or username<input name="identifier" autoComplete="username" placeholder="you@example.com" required /></label>}<label>Password<input name="password" type="password" autoComplete={register ? 'new-password' : 'current-password'} placeholder={register ? 'At least 8 characters' : 'Your password'} minLength={register ? 8 : 1} maxLength={72} required /></label><Button type="submit" busy={busy} className="full">{register ? 'Create my account' : 'Sign in'}<ArrowRight size={18} /></Button></form><div className="auth-switch">{register ? 'Already part of the club?' : 'New around here?'} <Link to={register ? '/login' : '/register'} onClick={() => setError(null)}>{register ? 'Sign in' : 'Create an account'}</Link></div><Link className="muted-link" to="/join">Just here to play? Join as a guest <ArrowRight size={14} /></Link></section></main>;
}
function PageHeading({ eyebrow, title, description, children }) { return <div className="page-heading"><div><span className="eyebrow">{eyebrow}</span><h1>{title}</h1>{description && <p>{description}</p>}</div>{children}</div>; }
function QuizArt({ index = 0 }) { return <div className={`quiz-art art-${index % 4}`} aria-hidden="true"><span className="quiz-art-ring" /><span className="quiz-art-symbol">{['✳', '?', '✦', '↗'][index % 4]}</span><div className="mini-shapes"><Triangle size={18} /><Diamond size={17} /><Circle size={17} /><Square size={17} /></div><span className="art-label">LET CURIOSITY WIN</span></div>; }
function QuizCard({ quiz, index, onHost, onDelete, example, onPreview, onClone, busy }) {
  return <article className="quiz-card"><QuizArt index={index} /><div className="quiz-card-body"><span className="tag">{example ? 'READY TO MAKE YOURS' : 'PRIVATE QUIZ'}</span><h3>{quiz.title}</h3><p>{quiz.description || 'A little friendly competition, made by you.'}</p><div className="quiz-meta"><span><BookOpen size={14} />{quiz.questions.length} questions</span><span><Clock3 size={14} />{Math.ceil(quiz.questions.reduce((n, q) => n + q.timeLimitSec + 4, 0) / 60)} min</span></div><div className="quiz-actions">{example ? <><Button className="secondary small" onClick={() => onPreview(quiz)}>Preview</Button><Button className="small" onClick={() => onClone(quiz)} busy={busy}>Use template <ArrowRight size={14} /></Button></> : <><Button className="small" onClick={() => onHost(quiz)} busy={busy}><Play size={14} fill="currentColor" />Host game</Button><Link to={`/quizzes/${quiz.id}/edit`} className="icon-button" aria-label={`Edit ${quiz.title}`}><Pencil size={16} /></Link><button className="icon-button delete" onClick={() => onDelete(quiz)} aria-label={`Delete ${quiz.title}`}><Trash2 size={16} /></button></>}</div></div></article>;
}
function Dashboard() {
  const quizzes = useData('/quizzes');
  const past = useData('/history');
  const { session, notify } = useApp();
  const [search, setSearch] = useState('');
  const [deleting, setDeleting] = useState(null);
  const [busy, setBusy] = useState(null);
  const [error, setError] = useState(null);
  const navigate = useNavigate();
  const host = async quiz => {
    setBusy(quiz.id); setError(null);
    try { const game = await api('/games', { method: 'POST', body: { quizId: quiz.id } }); navigate(`/game/${game.code}/host`); }
    catch (error) { setError(error); } finally { setBusy(null); }
  };
  const remove = async () => {
    setBusy('delete');
    try { await api(`/quizzes/${deleting.id}`, { method: 'DELETE' }); setDeleting(null); quizzes.reload(); notify('Quiz deleted.'); }
    catch (error) { setError(error); setDeleting(null); } finally { setBusy(null); }
  };
  const filtered = (quizzes.data || []).filter(q => `${q.title} ${q.description}`.toLowerCase().includes(search.toLowerCase()));
  return <><PageHeading eyebrow="YOUR PERSONAL PLAYGROUND" title={`Hey ${session.user.username}, let’s rally.`} description="A good question is all it takes to bring people together."><Link className="button" to="/quizzes/new"><Plus size={18} />Create a quiz</Link></PageHeading><section className="dashboard-hero"><div><span className="eyebrow">SMALL QUESTIONS. BIG ENERGY.</span><h2>Make room for<br />a little competition.</h2><p>Turn what you know into everyone’s next favorite game.</p><Link to="/examples" className="button light">Find your first spark <ArrowRight size={17} /></Link></div><div className="hero-art" aria-hidden="true"><span className="hero-asterisk">✳</span><span className="hero-diamond" /><div className="hero-card"><span><Zap size={15} fill="currentColor" /> BIG QUIZ ENERGY</span><strong>Ready.<br />Set.<br /><i>Rally.</i></strong><div><Triangle size={19} /><Diamond size={19} /><Circle size={19} /><Square size={19} /></div></div><span className="hero-circle">Let’s<br />play ↗</span></div></section><div className="stat-row"><div><span className="stat-icon lavender"><BookOpen size={20} /></span><div><strong>{quizzes.data?.length ?? '—'}</strong><span>Quizzes in your collection</span></div></div><div><span className="stat-icon peach"><Flag size={20} /></span><div><strong>{past.data?.filter(g => g.role === 'HOST').length ?? '—'}</strong><span>Games you’ve hosted</span></div></div><div><span className="stat-icon mint"><Trophy size={20} /></span><div><strong>{past.data?.filter(g => g.role === 'PLAYER').length ?? '—'}</strong><span>Games you’ve played</span></div></div></div><div className="section-heading"><div><h2>My quizzes <span className="count">{quizzes.data?.length || 0}</span></h2><p>Your ideas. Your rules. Your next great game.</p></div><label className="search-field"><Search size={17} /><input type="search" placeholder="Find a quiz…" aria-label="Search your quizzes" value={search} onChange={e => setSearch(e.target.value)} /></label></div><ErrorBox error={error || quizzes.error || past.error} />{quizzes.error && <Button className="secondary small" onClick={quizzes.reload}>Try again</Button>}{quizzes.loading ? <Spinner /> : <div className="quiz-grid">{filtered.map((quiz, i) => <QuizCard key={quiz.id} quiz={quiz} index={i} onHost={host} onDelete={setDeleting} busy={busy === quiz.id} />)}{!search && <Link to="/quizzes/new" className="create-card"><span><Plus size={28} /></span><h3>A fresh idea starts here.</h3><p>Create a quiz from scratch.<br />Make it completely yours.</p><strong>Create a quiz <ArrowRight size={15} /></strong></Link>}{search && !filtered.length && <div className="empty-state"><Search size={28} /><h3>No quizzes match “{search}”</h3><p>Try another title or keyword.</p></div>}</div>}<section className="template-banner"><span className="template-banner-icon"><Sparkles size={29} /></span><div><h3>Skip the blank page.</h3><p>Start with a ready-to-play template, then add your own twist.</p></div><Link to="/examples" className="button secondary small">Explore templates <ArrowRight size={16} /></Link></section>{deleting && <Modal title="Delete this quiz?" close={() => setDeleting(null)}><p>“{deleting.title}” will be removed from your collection. Your past game results will stay available.</p><div className="modal-actions"><Button className="secondary" onClick={() => setDeleting(null)}>Keep quiz</Button><Button className="danger" busy={busy === 'delete'} onClick={remove}>Delete quiz</Button></div></Modal>}</>;
}
function Examples() {
  const examples = useData('/examples');
  const [preview, setPreview] = useState(null);
  const [busy, setBusy] = useState(null);
  const [error, setError] = useState(null);
  const navigate = useNavigate();
  const { notify } = useApp();
  const clone = async quiz => {
    setBusy(quiz.id); setError(null);
    try { const copy = await api(`/examples/${quiz.id}/clone`, { method: 'POST' }); notify('Your own copy is ready. Make it yours!'); navigate(`/quizzes/${copy.id}/edit`); }
    catch (error) { setError(error); } finally { setBusy(null); }
  };
  return <><PageHeading eyebrow="A LITTLE INSPIRATION" title="Start with a spark." description="Ready-to-play quizzes for curious minds. Copy one and make it your own." /><ErrorBox error={error || examples.error} />{examples.loading ? <Spinner /> : <div className="quiz-grid">{examples.data?.map((quiz, i) => <QuizCard key={quiz.id} quiz={quiz} index={i} example onPreview={setPreview} onClone={clone} busy={busy === quiz.id} />)}</div>}<div className="info-note"><BookOpen size={18} /><p>Every template becomes a private copy in your workspace. Edit as much as you like.</p></div>{preview && <Modal title={preview.title} wide close={() => setPreview(null)}><p>{preview.description}</p><div className="preview-questions">{preview.questions.map((q, i) => <div key={q.id} className="preview-question"><span className="tag">QUESTION {i + 1} · {q.type === 'MULTI' ? 'SELECT ALL' : 'SINGLE CHOICE'} · {q.timeLimitSec}s</span><h3>{q.text}</h3><div className="preview-options">{q.options.map(o => <div key={o.id} className={o.correct ? 'correct' : ''}>{o.text}{o.correct && <Check size={15} />}</div>)}</div></div>)}</div><Button className="full" busy={busy === preview.id} onClick={() => clone(preview)}>Use this template <ArrowRight size={17} /></Button></Modal>}</>;
}
const freshQuestion = () => ({ text: '', type: 'SINGLE', timeLimitSec: 20, imageUrl: null, options: [{ text: '', correct: true }, { text: '', correct: false }, { text: '', correct: false }, { text: '', correct: false }] });
function Editor() {
  const { id } = useParams();
  const [quiz, setQuiz] = useState({ title: '', description: '', questions: [freshQuestion()] });
  const [active, setActive] = useState(0);
  const [loading, setLoading] = useState(!!id);
  const [busy, setBusy] = useState(false);
  const [uploading, setUploading] = useState(false);
  const [error, setError] = useState(null);
  const { notify } = useApp();
  const navigate = useNavigate();
  const fileRef = useRef(null);
  useEffect(() => {
    let alive = true;
    if (id) { setLoading(true); api(`/quizzes/${id}`).then(q => { if (alive) { setQuiz(q); setLoading(false); } }).catch(e => { if (alive) { setError(e); setLoading(false); } }); }
    return () => { alive = false; };
  }, [id]);
  const question = quiz.questions[active];
  const change = patch => setQuiz(q => ({ ...q, questions: q.questions.map((question, i) => i === active ? { ...question, ...patch } : question) }));
  const changeOption = (index, patch) => change({ options: question.options.map((o, i) => i === index ? { ...o, ...patch } : o) });
  const mark = index => change({ options: question.options.map((o, i) => ({ ...o, correct: question.type === 'SINGLE' ? i === index : i === index ? !o.correct : o.correct })) });
  const add = () => { setQuiz(q => ({ ...q, questions: [...q.questions, freshQuestion()] })); setActive(quiz.questions.length); };
  const remove = () => { setQuiz(q => ({ ...q, questions: q.questions.filter((_, i) => i !== active) })); setActive(Math.max(0, active - 1)); };
  const save = async e => {
    e.preventDefault(); setBusy(true); setError(null);
    try {
      for (let i = 0; i < quiz.questions.length; i++) {
        const q = quiz.questions[i];
        if (!q.text.trim() || q.options.some(o => !o.text.trim()) || !q.options.some(o => o.correct)) { setActive(i); throw new Error(`Question ${i + 1} needs a question, all answer choices, and a correct answer.`); }
      }
      await api(id ? `/quizzes/${id}` : '/quizzes', { method: id ? 'PUT' : 'POST', body: quiz });
      notify('Quiz saved. Your next great game is ready.'); navigate('/');
    } catch (error) { setError(error); } finally { setBusy(false); }
  };
  const upload = async e => {
    const file = e.target.files?.[0];
    if (!file) return;
    const target = active;
    setUploading(true); setError(null);
    try { if (file.size > 5 * 1024 * 1024) throw new Error('Choose an image up to 5 MB.'); const form = new FormData(); form.append('file', file); const result = await api('/images', { method: 'POST', body: form }); setQuiz(q => ({ ...q, questions: q.questions.map((item, i) => i === target ? { ...item, imageUrl: result.url } : item) })); }
    catch (error) { setError(error); } finally { setUploading(false); e.target.value = ''; }
  };
  if (loading) return <Spinner />;
  return <><Link to="/" className="back-link"><ArrowLeft size={16} />My quizzes</Link><form onSubmit={save}><PageHeading eyebrow="THE QUESTION WORKSHOP" title={id ? 'Make it even better.' : 'What’s your big question?'} description="A great game starts with a little curiosity."><Button type="submit" busy={busy} disabled={uploading}><Check size={17} />Save quiz</Button></PageHeading><ErrorBox error={error} /><div className="quiz-details panel"><label>Quiz title<input value={quiz.title} onChange={e => setQuiz({ ...quiz, title: e.target.value })} placeholder="Give your quiz a name worth remembering" maxLength={100} required /></label><label>Description <span className="optional">optional</span><input value={quiz.description} onChange={e => setQuiz({ ...quiz, description: e.target.value })} placeholder="Set the scene for your players" maxLength={2000} /></label></div><div className="editor-layout"><aside className="question-list panel"><div className="section-heading"><h3>Questions</h3><span className="count">{quiz.questions.length}</span></div>{quiz.questions.map((q, i) => <button type="button" className={`question-tab ${active === i ? 'active' : ''}`} key={i} onClick={() => setActive(i)}><span>{String(i + 1).padStart(2, '0')}</span><strong>{q.text || 'Your next question'}</strong><ChevronRight size={15} /></button>)}<Button type="button" className="secondary full small" onClick={add} disabled={quiz.questions.length >= 50 || uploading}><Plus size={16} />Add question</Button></aside><section className="question-editor panel"><div className="question-editor-heading"><span className="eyebrow">QUESTION {String(active + 1).padStart(2, '0')}</span><button type="button" className="icon-button delete" onClick={remove} disabled={quiz.questions.length === 1 || uploading} aria-label="Remove question"><Trash2 size={17} /></button></div><div className="editor-settings"><label>Question type<select value={question.type === 'SINGLE' && question.options.length === 2 && question.options[0].text === 'True' && question.options[1].text === 'False' ? 'TRUE_FALSE' : question.type} onChange={e => { const type = e.target.value; change(type === 'TRUE_FALSE' ? { type: 'SINGLE', options: [{ text: 'True', correct: true }, { text: 'False', correct: false }] } : { type, options: question.options.map((o, i) => ({ ...o, correct: type === 'SINGLE' ? i === 0 : o.correct })) }); }}><option value="SINGLE">Single choice</option><option value="TRUE_FALSE">True or false</option><option value="MULTI">Select all that apply</option></select></label><label>Time to answer<select value={question.timeLimitSec} onChange={e => change({ timeLimitSec: Number(e.target.value) })}>{[5, 10, 15, 20, 30, 45, 60, 90, 120].map(t => <option key={t} value={t}>{t} seconds</option>)}</select></label></div><label>Your question<textarea rows={3} value={question.text} onChange={e => change({ text: e.target.value })} maxLength={300} placeholder="What do you want to ask?" required /></label><div className="image-upload">{question.imageUrl ? <><img src={question.imageUrl} alt="Question attachment" /><Button type="button" className="secondary small" onClick={() => change({ imageUrl: null })}><X size={15} />Remove image</Button></> : <button type="button" onClick={() => fileRef.current.click()} disabled={uploading}><ImagePlus size={22} /><strong>{uploading ? 'Uploading…' : 'Add a little visual context'}</strong><small>PNG, JPG, GIF, or WebP · up to 5 MB</small></button>}<input hidden type="file" ref={fileRef} accept="image/png,image/jpeg,image/gif,image/webp" onChange={upload} /></div><div className="section-heading"><h3>Answer choices</h3><small>Mark the {question.type === 'MULTI' ? 'correct answers' : 'correct answer'}.</small></div><div className="editor-options">{question.options.map((o, i) => { const Shape = shapes[i]; return <div key={i} className={`editor-option ${colors[i]}`}><Shape size={18} fill="currentColor" /><input value={o.text} onChange={e => changeOption(i, { text: e.target.value })} aria-label={`Answer choice ${i + 1}`} placeholder={`Answer ${i + 1}`} maxLength={100} required /><button type="button" className={`correct-toggle ${o.correct ? 'checked' : ''}`} aria-label={`Mark answer ${i + 1} as correct`} aria-pressed={o.correct} onClick={() => mark(i)}>{o.correct && <Check size={16} />}</button>{question.options.length > 2 && <button type="button" className="remove-option" aria-label={`Remove answer ${i + 1}`} onClick={() => change({ options: question.options.filter((_, j) => i !== j) })}><X size={14} /></button>}</div>; })}</div>{question.options.length < 4 && <button type="button" className="text-button" onClick={() => change({ options: [...question.options, { text: '', correct: false }] })}><Plus size={15} />Add answer choice</button>}<div className="info-note compact"><Zap size={16} /><p>Correct answers earn 500–1,000 points. Faster answers score higher.</p></div></section></div></form></>;
}
function Board({ players = [], me, final = false }) {
  return <div className={`leaderboard ${final ? 'final-board' : ''}`}>{players.slice(0, final ? 5 : 5).map(p => <div className={`leaderboard-row ${me?.id === p.id ? 'is-me' : ''}`} key={p.id || p.rank}><span className={`rank rank-${p.rank}`}>{p.rank <= 3 ? <Trophy size={17} /> : p.rank}</span><strong>{p.name}{me?.id === p.id && <small> YOU</small>}</strong><span className="board-score">{p.score.toLocaleString()}<small>pts</small></span></div>)}{!players.length && <p className="muted">The leaderboard is waiting for its first players.</p>}</div>;
}
function GameHistory() {
  const { data, loading, error, reload } = useData('/history');
  return <><PageHeading eyebrow="THE GOOD TIMES, SAVED" title="Every game has a story." description="Revisit your questions, your scores, and those last-second victories." /><ErrorBox error={error} />{error && <Button onClick={reload} className="secondary">Try again</Button>}{loading ? <Spinner /> : data?.length ? <div className="history-list">{data.map(game => <Link className="history-card panel" key={game.id} to={`/history/${game.id}`}><span className={`stat-icon ${game.role === 'HOST' ? 'lavender' : 'mint'}`}>{game.role === 'HOST' ? <Radio size={23} /> : <Trophy size={23} />}</span><div><span className="tag">{game.role === 'HOST' ? 'YOU HOSTED' : 'YOU PLAYED'} · {game.status === 'COMPLETED' ? 'COMPLETED' : 'ENDED EARLY'}</span><h3>{game.title}</h3><p>{date(game.endedAt)}</p></div><ArrowRight size={19} /></Link>)}</div> : <div className="empty-state panel"><History size={38} /><h2>Your story starts with a game.</h2><p>Host a quiz or join a friend. Your results will find a home here.</p><Link to="/" className="button">Find your next game <ArrowRight size={16} /></Link></div>}</>;
}
function HistoryDetail() {
  const { id } = useParams();
  const { data, loading, error } = useData(`/history/${id}`);
  if (loading) return <Spinner />;
  if (error) return <ErrorBox error={error} />;
  return <><Link to="/history" className="back-link"><ArrowLeft size={16} />Game history</Link><PageHeading eyebrow={`${data.role === 'HOST' ? 'YOU HOSTED' : 'YOU PLAYED'} · ${data.status === 'COMPLETED' ? 'COMPLETED' : 'ENDED EARLY'}`} title={data.quiz.title} description={date(data.endedAt)} /><div className="history-detail"><section><h2 className="section-title">The questions, revisited.</h2>{data.quiz.questions.map((q, i) => { const answer = data.answers.find(a => a.questionIndex === i); return <article className="preview-question panel" key={i}><span className="tag">QUESTION {i + 1}{data.role === 'PLAYER' ? ` · ${answer?.points || 0} POINTS` : ''}</span><h3>{q.text}</h3>{q.imageUrl && <img className="history-image" src={q.imageUrl} alt="Question attachment" />}<div className="preview-options">{q.options.map(o => <div key={o.id} className={o.correct ? 'correct' : ''}><span>{o.text}{answer?.optionIds.includes(o.id) && <small> · YOUR CHOICE</small>}</span>{o.correct && <Check size={16} />}</div>)}</div>{data.role === 'PLAYER' && !answer?.optionIds.length && <small className="muted">No answer submitted.</small>}</article>; })}</section><aside className="panel"><span className="eyebrow">FINAL STANDINGS</span><h2>The leaderboard.</h2><Board players={data.leaderboard} final /></aside></div></>;
}
function LiveGame() {
  const { code, role } = useParams();
  const { session, notify } = useApp();
  const [state, setState] = useState(null);
  const [error, setError] = useState(null);
  const [connected, setConnected] = useState(false);
  const [retry, setRetry] = useState(0);
  const [selection, setSelection] = useState([]);
  const [sending, setSending] = useState(false);
  const [leaving, setLeaving] = useState(false);
  const [now, setNow] = useState(Date.now());
  const socket = useRef(null);
  const offset = useRef(0);
  const navigate = useNavigate();
  const isHost = role === 'host';
  useEffect(() => {
    let cancelled = false;
    setError(null); setState(null); setConnected(false);
    let credentials;
    try { credentials = JSON.parse(sessionStorage.getItem(`rally.join.${code}`)); } catch {}
    if (!isHost && !credentials) { navigate('/join', { replace: true }); return; }
    if (isHost && !session) { navigate('/login', { replace: true }); return; }
    const ws = new WebSocket(`${location.protocol === 'https:' ? 'wss:' : 'ws:'}//${location.host}/ws/game/${code}`);
    socket.current = ws;
    ws.onopen = () => { if (cancelled) { ws.close(); return; } setConnected(true); ws.send(JSON.stringify(isHost ? { type: 'HOST_CONNECT', token: session.token } : { type: 'JOIN', displayName: credentials.displayName, token: session?.token })); };
    ws.onmessage = event => {
      if (cancelled) return;
      const message = JSON.parse(event.data);
      if (message.type === 'ERROR') { setError(message.message); setSending(false); return; }
      if (message.state) { offset.current = message.state.serverNow - Date.now(); setState(message.state); setSending(false); }
    };
    ws.onerror = () => { if (!cancelled) setError('Unable to connect to the game. Check that the server is running.'); };
    ws.onclose = () => { if (!cancelled) setConnected(false); };
    const heartbeat = setInterval(() => { if (ws.readyState === WebSocket.OPEN) ws.send(JSON.stringify({ type: 'PING' })); }, 15000);
    return () => { cancelled = true; clearInterval(heartbeat); if (ws.readyState === WebSocket.OPEN) ws.close(); else if (ws.readyState === WebSocket.CONNECTING) ws.onopen = () => ws.close(); };
  }, [code, role, retry]);
  useEffect(() => { setSelection([]); setSending(false); setError(null); }, [state?.questionIndex]);
  useEffect(() => { const interval = setInterval(() => setNow(Date.now()), 100); return () => clearInterval(interval); }, []);
  const send = (type, extra = {}) => {
    if (socket.current?.readyState !== WebSocket.OPEN) { setError('Your connection was lost. Rejoin to continue.'); return; }
    setError(null); socket.current.send(JSON.stringify({ type, questionIndex: state?.questionIndex, ...extra }));
  };
  const answer = () => { setSending(true); send('ANSWER', { optionIds: selection }); };
  const choose = id => { if (state.me?.submitted || sending || seconds === 0) return; setSelection(old => state.question.type === 'MULTI' ? old.includes(id) ? old.filter(x => x !== id) : [...old, id] : [id]); };
  const seconds = Math.max(0, Math.ceil(((state?.deadline || 0) - now - offset.current) / 1000));
  const terminal = ['COMPLETED', 'TERMINATED'].includes(state?.status);
  const exit = () => navigate(session ? '/' : '/join');
  const copy = async () => { try { await navigator.clipboard.writeText(code); notify('Game code copied. Send it to your people!'); } catch { notify(`Your game code is ${code}.`); } };
  return <div className="game-shell"><header className="game-header"><div className="game-wordmark"><Zap size={24} fill="currentColor" />rally.</div><div className="game-header-meta"><span className={`status-dot ${connected ? '' : 'offline'}`} />{connected ? (isHost ? 'HOST SCREEN' : 'PLAYER SCREEN') : 'DISCONNECTED'}<span className="game-code-mini">{code}</span></div><button className="button secondary small" onClick={() => terminal ? exit() : setLeaving(true)}>{terminal ? 'Back to workspace' : 'Leave game'}<LogOut size={15} /></button></header><main className="game-main"><ErrorBox error={error} />{!connected && state && !terminal && <div className="connection-notice"><p>{isHost ? 'Your host connection was lost. The game will end for everyone.' : session ? 'Your connection was lost. Rejoin to restore your score.' : 'Your connection was lost. Rejoining as a guest starts you at zero.'}</p>{!isHost && <Button className="secondary" onClick={() => setRetry(r => r + 1)}>Rejoin game</Button>}</div>}{!state ? <div className="game-connect"><span className="eyebrow">THE FUN IS JUST A MOMENT AWAY</span><h1>{error ? 'Let’s try that again.' : 'Finding your people…'}</h1>{!error && <Spinner />}<Link to="/join" className="button secondary">Back to join <ArrowLeft size={16} /></Link></div> : state.status === 'LOBBY' ? <><div className="lobby-heading"><span className="eyebrow">{isHost ? 'GOOD COMPANY. GREAT QUESTIONS.' : 'YOU’RE IN. MAKE YOURSELF AT HOME.'}</span><h1>{isHost ? 'Bring everyone together.' : `Hey ${state.me?.name || 'there'}, you’re in!`}</h1><p>{isHost ? 'Open Rally on your device and enter this game code.' : 'The host will kick things off in a moment.'}</p><button className="code-display" onClick={copy} aria-label={`Copy game code ${code}`}><span>{code.slice(0, 3)} {code.slice(3)}</span><Copy size={22} /></button><div className="lobby-quiz"><BookOpen size={16} />{state.title}<span>·</span>{state.questionCount} questions</div></div><section className="lobby-players panel"><div className="section-heading"><h3><Users size={19} />The room is coming together</h3><span className="tag">{state.leaderboard.length} / 20 PLAYERS</span></div>{state.leaderboard.length ? <div className="player-chips">{state.leaderboard.map((p, i) => <div className={`player-chip chip-${i % 4}`} key={p.id}><span>{p.name[0].toUpperCase()}</span>{p.name}{state.me?.id === p.id && <small>you</small>}</div>)}</div> : <div className="waiting"><span className="waiting-dots"><i /><i /><i /></span>Your first players are on their way.</div>}</section><div className="lobby-bottom">{isHost ? <><Button onClick={() => send('START')} disabled={!state.leaderboard.some(p => p.connected) || !connected} className="large"><Play size={19} fill="currentColor" />Let’s play</Button><p>You can start when at least one player joins. Latecomers can jump in too.</p></> : <><span className="waiting-dots"><i /><i /><i /></span><p>Waiting for the host to start the game.</p></>}</div></> : terminal ? <><div className="final-heading"><span className="eyebrow">{state.status === 'COMPLETED' ? 'THAT’S A WRAP' : 'THE GAME HAS ENDED'}</span><h1>{state.status === 'COMPLETED' ? 'Good times. Great minds.' : 'Until the next rally.'}</h1><p>{state.reason || 'Every question counts. These players made them count.'}</p></div><div className="podium">{[1, 0, 2].map(position => { const p = state.leaderboard[position]; return p ? <div className={`podium-place place-${position + 1}`} key={p.id}><span className="podium-avatar">{position === 0 ? <Trophy size={30} /> : p.name[0].toUpperCase()}</span><strong>{p.name}</strong><span>{p.score.toLocaleString()} pts</span><div className="podium-block">{position + 1}<Sparkles size={position === 0 ? 34 : 22} /></div></div> : <div key={position} />; })}</div><div className="final-results panel"><Board players={state.leaderboard} me={state.me} final />{state.me && <div className="your-result">You finished <strong>#{state.me.rank}</strong> with <strong>{state.me.score.toLocaleString()} points.</strong></div>}{!state.historySaved && <p className="muted">Your results are still saving. Check game history in a moment.</p>}<div className="final-actions">{session && state.historyId && <Link to={`/history/${state.historyId}`} className="button secondary">See the full story <History size={17} /></Link>}<Button onClick={exit}>Ready for another? <ArrowRight size={17} /></Button></div></div></> : <><div className="question-topline"><span className="eyebrow">QUESTION {state.questionIndex + 1} OF {state.questionCount} <span> / </span>{state.question.type === 'MULTI' ? 'SELECT ALL THAT APPLY' : 'SINGLE CHOICE'}</span><span className={`timer ${seconds <= 5 && state.status === 'QUESTION' ? 'urgent' : ''}`}><Clock3 size={19} />{state.status === 'QUESTION' ? `${seconds}s` : 'Answers are in'}</span></div><div className="timer-track"><div style={{ width: state.status === 'QUESTION' ? `${Math.min(100, seconds / state.question.timeLimitSec * 100)}%` : '0%' }} /></div><div className="live-question"><h1>{state.question.text}</h1>{state.question.imageUrl && <img src={state.question.imageUrl} alt="Question illustration" />}</div><div className="live-answers">{state.question.options.map((option, i) => { const Shape = shapes[i]; const revealed = state.status === 'REVEAL'; const correct = state.correctOptionIds?.includes(option.id); const chosen = selection.includes(option.id) || state.me?.selectedOptionIds.includes(option.id); return <button key={option.id} className={`live-answer ${colors[i]} ${chosen ? 'selected' : ''} ${revealed ? correct ? 'answer-correct' : 'answer-wrong' : ''}`} disabled={isHost || revealed || state.me?.submitted || sending || seconds === 0} onClick={() => choose(option.id)}><Shape size={28} fill="currentColor" /><span>{option.text}</span>{(revealed && correct) || chosen ? <CheckCircle2 size={23} /> : <span className="answer-letter">{String.fromCharCode(65 + i)}</span>}</button>; })}</div>{state.status === 'REVEAL' ? <div className="reveal-layout"><section className="reveal-summary"><span className="eyebrow">A LITTLE MOMENT OF TRUTH</span><h2>{isHost ? 'Here’s how things stand.' : state.me.points > 0 ? 'Nicely done!' : 'The next one is yours.'}</h2>{!isHost && <strong className="points-earned">+{state.me.points || 0}<small> points this round</small></strong>}<p>Next question starts automatically in a moment.</p>{isHost && <Button onClick={() => send('NEXT')}>{state.questionIndex + 1 === state.questionCount ? 'See final results' : 'Next question'}<ArrowRight size={17} /></Button>}</section><section className="panel round-board"><Board players={state.leaderboard} me={state.me} /></section></div> : <div className="answer-footer">{isHost ? <><span><Users size={18} /><strong>{state.answerCount}</strong> / {state.leaderboard.length} answers in</span><Button className="secondary" onClick={() => send('NEXT')}>Reveal answers <ArrowRight size={17} /></Button></> : state.me?.submitted ? <div className="answer-locked"><CheckCircle2 size={22} /><strong>Locked in. Fingers crossed.</strong><span>Let’s see what everyone else thinks.</span></div> : <><span>{state.question.type === 'MULTI' ? 'Choose every correct answer, then lock them in.' : 'Pick your answer, then lock it in.'}</span><Button onClick={answer} busy={sending} disabled={!selection.length || seconds === 0 || !connected}>Lock in {state.question.type === 'MULTI' ? `${selection.length || ''} answers` : 'answer'}<Check size={18} /></Button></>}</div>}{isHost && <button className="text-button end-game" onClick={() => setLeaving(true)}>End game early</button>}</>}</main><footer className="game-footer"><span>A LITTLE FRIENDLY COMPETITION.</span>{state?.me && <span>{state.me.name} · {state.me.score.toLocaleString()} points</span>}<span>RALLY / PLAY TOGETHER</span></footer>{leaving && <Modal title={isHost ? 'End the game for everyone?' : 'Leave this game?'} close={() => setLeaving(false)}><p>{isHost ? 'Leaving ends this game for every player. Current results will be saved to game history.' : session ? 'Your score will be kept if you rejoin this active game.' : 'Guest scores reset when you leave. Rejoining starts you at zero.'}</p><div className="modal-actions"><Button className="secondary" onClick={() => setLeaving(false)}>Keep playing</Button><Button className="danger" onClick={() => { if (isHost) { send('END'); setLeaving(false); } else exit(); }}>{isHost ? 'End game' : 'Leave game'}</Button></div></Modal>}</div>;
}
