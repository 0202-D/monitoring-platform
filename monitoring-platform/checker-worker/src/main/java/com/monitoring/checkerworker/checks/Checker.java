package com.monitoring.checkerworker.checks;

/**
 * Стратегия проверки одного типа монитора (HTTP/SSL/DNS). Реализации
 * выполняются на virtual thread — им разрешено блокироваться на
 * сетевом I/O без опасения исчерпать платформенный пул потоков
 * (у виртуальных потоков своя, дешёвая модель блокировки).
 */
public interface Checker {

    CheckResult check(String targetUrl);
}
