package com.monitoring.checkerworker.checks;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.naming.CommunicationException;
import javax.naming.Context;
import javax.naming.NameNotFoundException;
import javax.naming.NamingEnumeration;
import javax.naming.NamingException;
import javax.naming.ServiceUnavailableException;
import javax.naming.directory.Attribute;
import javax.naming.directory.Attributes;
import javax.naming.directory.DirContext;
import javax.naming.directory.InitialDirContext;
import java.util.ArrayList;
import java.util.Hashtable;
import java.util.List;

/**
 * DNS-запросы через стандартный JNDI-провайдер JDK: внешняя библиотека не нужна.
 * По умолчанию используются DNS-серверы системы; checker.dns.server (например, 1.1.1.1)
 * задаёт конкретный, чтобы результат не зависел от настроек хоста.
 *
 * Таймаут: JNDI удваивает паузу при каждой повторной попытке, поэтому при значениях по
 * умолчанию (2 с и 2 повтора) запрос к молчащему серверу длится до 2 + 4 + 8 секунд.
 */
@Component
public class JndiDnsResolver implements DnsResolver {

    private final Hashtable<String, String> environment = new Hashtable<>();

    public JndiDnsResolver(@Value("${checker.dns.server:}") String server,
                           @Value("${checker.dns.timeout-ms:2000}") int timeoutMs,
                           @Value("${checker.dns.retries:2}") int retries) {
        environment.put(Context.INITIAL_CONTEXT_FACTORY, "com.sun.jndi.dns.DnsContextFactory");
        // "dns:" без адреса означает DNS-серверы, настроенные в системе
        environment.put(Context.PROVIDER_URL, server.isBlank() ? "dns:" : "dns://" + server.trim());
        environment.put("com.sun.jndi.dns.timeout.initial", String.valueOf(timeoutMs));
        environment.put("com.sun.jndi.dns.timeout.retries", String.valueOf(retries));
    }

    @Override
    public List<String> lookup(String host, String recordType) {
        DirContext context = null;
        try {
            context = new InitialDirContext(environment);
            Attributes attributes = context.getAttributes(host, new String[]{recordType});
            Attribute attribute = attributes.get(recordType);
            if (attribute == null) {
                return List.of();
            }
            List<String> values = new ArrayList<>();
            NamingEnumeration<?> all = attribute.getAll();
            while (all.hasMore()) {
                values.add(String.valueOf(all.next()));
            }
            return values;
        } catch (NameNotFoundException e) {
            throw new DnsLookupException(DnsLookupException.Kind.NX_DOMAIN, "NXDOMAIN");
        } catch (CommunicationException | ServiceUnavailableException e) {
            throw new DnsLookupException(DnsLookupException.Kind.UNAVAILABLE, e.getMessage());
        } catch (NamingException e) {
            throw new DnsLookupException(DnsLookupException.Kind.OTHER, e.getMessage());
        } finally {
            if (context != null) {
                try {
                    context.close();
                } catch (NamingException ignored) {
                    // закрытие контекста ни на что не влияет
                }
            }
        }
    }
}
