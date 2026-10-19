import { useState, type FormEvent } from 'react';
import { passwordIssue } from './password';

export function AuthForm({
    busy,
    submit,
}: {
    busy: boolean;
    submit: (
        mode: 'login' | 'register',
        email: string,
        password: string,
    ) => void;
}) {
    const [mode, setMode] = useState<'login' | 'register'>('login');
    const [email, setEmail] = useState('');
    const [password, setPassword] = useState('');
    const [showIssue, setShowIssue] = useState(false);
    const issue = passwordIssue(password);
    function send(event: FormEvent) {
        event.preventDefault();
        setShowIssue(true);
        if (issue.length === 0) submit(mode, email.trim(), password);
    }
    return (
        <section className="card auth-card" aria-labelledby="auth-title">
            <div className="eyebrow">ВАШ СЛЕДУЮЩИЙ ХОД</div>
            <h1 id="auth-title">
                {mode === 'login' ? 'Рады видеть вас' : 'Начнём знакомство'}
            </h1>
            <p className="muted">
                Один соперник. Девять клеток. Пять секунд на ход.
            </p>
            <div className="switch" aria-label="Выбор действия">
                <button
                    type="button"
                    aria-pressed={mode === 'login'}
                    onClick={() => {
                        setMode('login');
                        setShowIssue(false);
                    }}
                    disabled={busy}
                >
                    Вход
                </button>
                <button
                    type="button"
                    aria-pressed={mode === 'register'}
                    onClick={() => {
                        setMode('register');
                        setShowIssue(false);
                    }}
                    disabled={busy}
                >
                    Регистрация
                </button>
            </div>
            <form onSubmit={send}>
                <label htmlFor="email">Email</label>
                <input
                    id="email"
                    type="email"
                    autoComplete="username"
                    required
                    maxLength={254}
                    value={email}
                    onChange={(e) => setEmail(e.target.value)}
                    disabled={busy}
                />
                <label htmlFor="password">Пароль</label>
                <input
                    id="password"
                    type="password"
                    autoComplete={
                        mode === 'login' ? 'current-password' : 'new-password'
                    }
                    required
                    aria-describedby="password-help"
                    value={password}
                    onChange={(e) => setPassword(e.target.value)}
                    disabled={busy}
                />
                <p id="password-help" className="hint">
                    6–15 символов: a–z, A–Z, цифра и спецсимвол.
                </p>
                {showIssue && issue.length > 0 && (
                    <ul className="validation" role="alert">
                        {issue.map((item) => (
                            <li key={item}>{item}</li>
                        ))}
                    </ul>
                )}
                <button className="primary wide" disabled={busy}>
                    {busy
                        ? 'Проверяем…'
                        : mode === 'login'
                          ? 'Войти'
                          : 'Создать аккаунт'}
                </button>
            </form>
            <p className="hint">
                Только PvP · На одном аккаунте — одна активная сессия
            </p>
        </section>
    );
}
