package com.monitoring.checkerworker.checks;

import com.monitoring.checkerworker.checks.DnsLookupException.Kind;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DnsCheckerTest {

    private final FakeResolver resolver = new FakeResolver();
    private final DnsChecker checker = new DnsChecker(resolver);

    // ---------- домен без типа: достаточно адреса A или AAAA ----------

    @Test
    void domainWithARecordIsUp() {
        resolver.answer("example.com", "A", "93.184.216.34");

        CheckResult result = checker.check("example.com");

        assertThat(result.status()).isEqualTo(CheckResult.Status.UP);
        assertThat(result.responseTimeMs()).isNotNull();
    }

    @Test
    void domainWithOnlyIpv6IsUp() {
        resolver.answer("example.com", "AAAA", "2606:2800:220:1:248:1893:25c8:1946");

        assertThat(checker.check("example.com").status()).isEqualTo(CheckResult.Status.UP);
    }

    @Test
    void domainWithoutAddressesIsDown() {
        CheckResult result = checker.check("example.com");

        assertThat(result.status()).isEqualTo(CheckResult.Status.DOWN);
        assertThat(result.errorMessage()).contains("Нет записей типа A/AAAA");
    }

    @Test
    void nonexistentDomainIsDownWithoutAskingForIpv6() {
        resolver.fail("nope.example", "A", new DnsLookupException(Kind.NX_DOMAIN, "NXDOMAIN"));

        CheckResult result = checker.check("nope.example");

        assertThat(result.status()).isEqualTo(CheckResult.Status.DOWN);
        assertThat(result.errorMessage()).contains("NXDOMAIN");
        assertThat(resolver.queries).containsExactly("A");
    }

    @Test
    void failedIpv4QueryDoesNotMatterIfIpv6Answers() {
        resolver.fail("example.com", "A", new DnsLookupException(Kind.UNAVAILABLE, "timeout"));
        resolver.answer("example.com", "AAAA", "2606:2800:220:1::1");

        assertThat(checker.check("example.com").status()).isEqualTo(CheckResult.Status.UP);
    }

    @Test
    void failedIpv6QueryDoesNotMatterIfIpv4Answers() {
        resolver.answer("example.com", "A", "93.184.216.34");
        resolver.fail("example.com", "AAAA", new DnsLookupException(Kind.UNAVAILABLE, "timeout"));

        assertThat(checker.check("example.com").status()).isEqualTo(CheckResult.Status.UP);
    }

    @Test
    void unreachableDnsServerIsDown() {
        resolver.fail("example.com", "A", new DnsLookupException(Kind.UNAVAILABLE, "no response"));
        resolver.fail("example.com", "AAAA", new DnsLookupException(Kind.UNAVAILABLE, "no response"));

        CheckResult result = checker.check("example.com");

        assertThat(result.status()).isEqualTo(CheckResult.Status.DOWN);
        assertThat(result.errorMessage()).contains("DNS-сервер не отвечает");
    }

    // ---------- конкретный тип и ожидаемое значение ----------

    @Test
    void recordTypeWithoutExpectedValueRequiresAnyRecord() {
        resolver.answer("example.com", "MX", "10 mail.example.com.");

        assertThat(checker.check("example.com MX").status()).isEqualTo(CheckResult.Status.UP);
        assertThat(checker.check("example.com NS").errorMessage()).contains("Нет записей типа NS");
    }

    @Test
    void trailingSpacesAfterTypeMeanNoExpectedValue() {
        // Пробелы на концах не превращаются в "пустое ожидаемое значение"
        resolver.answer("example.com", "A", "93.184.216.34");

        assertThat(checker.check("example.com A   ").status()).isEqualTo(CheckResult.Status.UP);
    }

    @Test
    void mxExpectedServerIsComparedIgnoringPriorityCaseAndTrailingDot() {
        resolver.answer("example.com", "MX", "10 Mail.Example.COM.", "20 backup.example.com.");

        assertThat(checker.check("example.com MX mail.example.com").status()).isEqualTo(CheckResult.Status.UP);
        assertThat(checker.check("example.com MX 20 backup.example.com").status()).isEqualTo(CheckResult.Status.UP);
    }

    @Test
    void mismatchedValueIsDownAndShowsWhatWasReceived() {
        resolver.answer("example.com", "A", "10.0.0.1", "10.0.0.2");

        CheckResult result = checker.check("example.com A 93.184.216.34");

        assertThat(result.status()).isEqualTo(CheckResult.Status.DOWN);
        assertThat(result.errorMessage())
            .contains("93.184.216.34")
            .contains("10.0.0.1, 10.0.0.2");
    }

    @Test
    void ipv6AddressesAreComparedAsNumbersNotText() {
        resolver.answer("example.com", "AAAA", "2606:2800:220:1:248:1893:25c8:1946");

        CheckResult result = checker.check("example.com AAAA 2606:2800:0220:0001:0248:1893:25C8:1946");

        assertThat(result.status()).isEqualTo(CheckResult.Status.UP);
    }

    @Test
    void compressedIpv6FormIsEqualToFullForm() {
        resolver.answer("example.com", "AAAA", "2606:2800:220:1:0:0:0:1");

        assertThat(checker.check("example.com AAAA 2606:2800:220:1::1").status())
            .isEqualTo(CheckResult.Status.UP);
    }

    @Test
    void invalidAddressAsExpectedValueNeverMatches() {
        resolver.answer("example.com", "A", "93.184.216.34");

        assertThat(checker.check("example.com A 999.1.1.1").status()).isEqualTo(CheckResult.Status.DOWN);
    }

    @Test
    void malformedIpv6ExpectedValueNeverMatches() {
        resolver.answer("example.com", "AAAA", "2606:2800:220:1::1");

        // Эти строки выглядят как адреса, но ими не являются: не должны ни совпасть, ни вызвать DNS-запрос
        for (String malformed : new String[]{":::", "1:2:3", "1::2::3", "12345::1", "g::1"}) {
            CheckResult result = checker.check("example.com AAAA " + malformed);
            assertThat(result.status()).as(malformed).isEqualTo(CheckResult.Status.DOWN);
        }
    }

    @Test
    void cnameIsComparedIgnoringCaseAndTrailingDot() {
        resolver.answer("www.example.com", "CNAME", "Target.Example.Net.");

        assertThat(checker.check("www.example.com CNAME target.example.net").status())
            .isEqualTo(CheckResult.Status.UP);
    }

    @Test
    void txtExpectedTextMayBeContainedInRecordIgnoringCaseAndQuotes() {
        resolver.answer("example.com", "TXT", "\"v=spf1 include:_spf.google.com ~all\"");

        assertThat(checker.check("example.com TXT include:_SPF.google.com").status())
            .isEqualTo(CheckResult.Status.UP);
        assertThat(checker.check("example.com TXT v=spf1 include:_spf.google.com").status())
            .isEqualTo(CheckResult.Status.UP);
        assertThat(checker.check("example.com TXT include:mailgun.org").status())
            .isEqualTo(CheckResult.Status.DOWN);
    }

    @Test
    void txtWithoutQuotesIsAlsoMatched() {
        resolver.answer("example.com", "TXT", "google-site-verification=abc123");

        assertThat(checker.check("example.com TXT google-site-verification=abc123").status())
            .isEqualTo(CheckResult.Status.UP);
    }

    @Test
    void txtRecordSplitIntoSeveralStringsIsJoinedWithoutSeparator() {
        // Длинная запись приходит как "часть1" "часть2": по RFC строки склеиваются без пробела
        resolver.answer("example.com", "TXT", "\"v=DKIM1; k=rsa; p=AAAA\" \"BBBB\"");

        assertThat(checker.check("example.com TXT p=AAAABBBB").status()).isEqualTo(CheckResult.Status.UP);
    }

    @Test
    void longListOfRecordsIsShortenedInMessage() {
        resolver.answer("example.com", "NS", "ns1.a.net.", "ns2.a.net.", "ns3.a.net.", "ns4.a.net.",
            "ns5.a.net.", "ns6.a.net.", "ns7.a.net.");

        CheckResult result = checker.check("example.com NS other.net");

        assertThat(result.errorMessage()).contains("всего 7");
    }

    // ---------- разбор цели ----------

    @Test
    void hostIsNormalizedToLowerCaseWithoutTrailingDot() {
        resolver.answer("example.com", "A", "93.184.216.34");

        assertThat(checker.check("  Example.COM.  ").status()).isEqualTo(CheckResult.Status.UP);
        assertThat(resolver.queries).contains("A");
    }

    @Test
    void unsupportedRecordTypeIsReportedInsteadOfThrowing() {
        CheckResult result = checker.check("example.com SRV");

        assertThat(result.status()).isEqualTo(CheckResult.Status.DOWN);
        assertThat(result.errorMessage()).contains("Некорректная цель DNS-проверки");
    }

    @Test
    void invalidHostIsReportedWithoutAskingDns() {
        // Чекер не полагается на то, что в базу попало только проверенное API
        for (String bad : new String[]{"-bad.example.com", "a..example.com", "exa$mple.com", "http://example.com"}) {
            CheckResult result = checker.check(bad);
            assertThat(result.status()).as(bad).isEqualTo(CheckResult.Status.DOWN);
            assertThat(result.errorMessage()).as(bad).contains("Некорректная цель DNS-проверки");
        }
        assertThat(resolver.queries).isEmpty();
    }

    @Test
    void emptyTargetIsReportedInsteadOfThrowing() {
        assertThat(checker.check("   ").errorMessage()).contains("Некорректная цель DNS-проверки");
    }

    /** Подмена сети: ответы и сбои задаются заранее, запросы запоминаются. */
    static class FakeResolver implements DnsResolver {
        private final Map<String, List<String>> answers = new HashMap<>();
        private final Map<String, DnsLookupException> failures = new HashMap<>();
        final List<String> queries = new ArrayList<>();

        void answer(String host, String type, String... values) {
            answers.put(host + "|" + type, List.of(values));
        }

        void fail(String host, String type, DnsLookupException failure) {
            failures.put(host + "|" + type, failure);
        }

        @Override
        public List<String> lookup(String host, String recordType) {
            queries.add(recordType);
            String key = host + "|" + recordType;
            if (failures.containsKey(key)) {
                throw failures.get(key);
            }
            return answers.getOrDefault(key, List.of());
        }
    }
}
