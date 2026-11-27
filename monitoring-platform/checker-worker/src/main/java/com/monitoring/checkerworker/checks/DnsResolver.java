package com.monitoring.checkerworker.checks;

import java.util.List;

/**
 * Запрос DNS-записей одного типа. Вынесен в интерфейс, чтобы логику проверки
 * можно было тестировать без сети.
 */
public interface DnsResolver {

    /**
     * @return значения записей в текстовом виде (пусто, если домен есть, а записей этого типа нет)
     * @throws DnsLookupException если запрос не удался
     */
    List<String> lookup(String host, String recordType);
}
