export function passwordIssue(password: string): string[] {
    const issue: string[] = [];
    if (password.length < 6 || password.length > 15)
        issue.push('От 6 до 15 символов');
    if (!/[a-z]/.test(password)) issue.push('Строчная буква a–z');
    if (!/[A-Z]/.test(password)) issue.push('Заглавная буква A–Z');
    if (!/[0-9]/.test(password)) issue.push('Цифра 0–9');
    if (![...password].some((c) => '!@#$%^&*()_+-=[]{};:,.?'.includes(c)))
        issue.push('Спецсимвол !@#$%^&*()_+-=[]{};:,.?');
    if (
        [...password].some(
            (c) =>
                !'abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789!@#$%^&*()_+-=[]{};:,.?'.includes(
                    c,
                ),
        )
    )
        issue.push('Только латинские буквы, цифры и разрешённые спецсимволы');
    return issue;
}
