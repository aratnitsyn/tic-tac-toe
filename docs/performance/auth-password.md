# Локальный benchmark Argon2id

Дата: 18 октября 2026, локальный macOS стенд. Измерен Spring Security
Argon2PasswordEncoder с Bouncy Castle: 3 прогревочных хеширования, затем
20 последовательных измерений hash через System.nanoTime. Проверка matches
выполняется вне измеряемого интервала. Пароль генерируется в памяти и не выводится.

```text
Argon2id benchmark: memoryKiB=19456 iterations=2 parallelism=1 samples=20
Platform={java.version=25.0.4.1, os.name=Mac OS X, os.arch=aarch64} CPUs=10
JVMHeapMax=536870912 JVMArgs=[-Xmx512m]
Hash milliseconds: p50=21.910084 p95=25.90425 max=26.357042
```

Heap тестового JVM ограничен `-Xmx512m`; JVM видит 10 CPU, affinity/cgroup
лимиты на локальном macOS не заданы. Это локальная исходная точка, а не
benchmark целевого Auth pod и не доказательство нагрузки 100 000.
Параметры соответствуют минимуму 19 MiB / 2 / 1 из
[OWASP Password Storage](https://cheatsheetseries.owasp.org/cheatsheets/Password_Storage_Cheat_Sheet.html).
После выбора ресурсов Kubernetes повторите измерение в Auth pod и добавьте
конкурентные login, rate limits, DB pool и нагрузку интроспекции.

Воспроизведение из корня с JDK 25:

```sh
services/auth/gradlew -p services/auth test --tests '*PasswordBenchmarkTest' --rerun-tasks --no-daemon
```

Результат — system-out в services/auth/build/test-results/test/
TEST-com.github.aratnitsyn.tictactoe.auth.account.PasswordBenchmarkTest.xml.
Численные SLO из proposal не используются как критерий успешности этого теста.
