package com.cassandrastudio.engine.net;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.cassandrastudio.engine.model.ConnectionConfig.ProxyType;
import com.cassandrastudio.engine.net.NetworkSettings.ProxyMode;
import com.cassandrastudio.engine.util.Json;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ProxyResolverTest {
    private static final URI GITHUB = URI.create("https://api.github.com/repos/x/y/releases/latest");

    private static NetworkSettings settings(ProxyMode mode, String host, Integer port, String user, List<String> noProxy) {
        return new NetworkSettings(false, true, mode, host, port, user, noProxy, false, null, false);
    }

    private static ProxyResolver resolver(NetworkSettings s, Map<String, String> env, Map<String, String> props) {
        return new ProxyResolver(s, "pw", env, props::get);
    }

    @Test
    void manualProxyWithNoProxyListAndLoopbackAlwaysDirect() {
        ProxyResolver r = resolver(settings(ProxyMode.MANUAL, "proxy.corp", 8080, "alice", List.of(".internal, 10.0.0.0/8", "db1")),
                Map.of(), Map.of());
        Optional<ProxyEndpoint> p = r.forUri(GITHUB);
        assertThat(p).isPresent();
        assertThat(p.get().type()).isEqualTo(ProxyType.HTTP);
        assertThat(p.get().host()).isEqualTo("proxy.corp");
        assertThat(p.get().port()).isEqualTo(8080);
        assertThat(p.get().username()).isEqualTo("alice");
        assertThat(p.get().password()).isEqualTo("pw");
        assertThat(p.get().toString()).doesNotContain("pw");
        assertThat(r.forUri(URI.create("http://node.internal:7071/metrics"))).isEmpty();
        assertThat(r.forUri(URI.create("http://10.231.42.11:7071/metrics"))).isEmpty();
        assertThat(r.forUri(URI.create("http://db1:7071/"))).isEmpty();
        assertThat(r.forUri(URI.create("http://127.0.0.1:18765/api"))).isEmpty();
        assertThat(r.forUri(URI.create("http://localhost/"))).isEmpty();
        assertThat(r.forUri(URI.create("http://[::1]:80/"))).isEmpty();
    }

    @Test
    void noneModeIsDirectEvenWithEnvironmentProxy() {
        ProxyResolver r = resolver(settings(ProxyMode.NONE, null, null, null, List.of()), Map.of("HTTPS_PROXY", "http://p:1"), Map.of());
        assertThat(r.forUri(GITHUB)).isEmpty();
    }

    @Test
    void systemModeReadsEnvironmentThenJavaProperties() {
        NetworkSettings sys = settings(ProxyMode.SYSTEM, null, null, null, List.of());
        ProxyResolver env = resolver(sys, Map.of("https_proxy", "http://bob:s%40cret@envproxy:3128", "NO_PROXY", "github.com,.corp"), Map.of());
        assertThat(env.forUri(URI.create("https://example.org/"))).get()
                .satisfies(p -> {
                    assertThat(p.host()).isEqualTo("envproxy");
                    assertThat(p.port()).isEqualTo(3128);
                    assertThat(p.username()).isEqualTo("bob");
                    assertThat(p.password()).isEqualTo("s@cret");
                });
        assertThat(env.forUri(GITHUB)).as("api.github.com is a sub-domain of github.com").isEmpty();
        assertThat(env.forUri(URI.create("https://wiki.corp/"))).isEmpty();
        assertThat(env.describeSystem()).contains("envproxy:3128").contains("HTTPS_PROXY").doesNotContain("cret");

        ProxyResolver props = resolver(sys, Map.of(), Map.of("https.proxyHost", "jproxy", "https.proxyPort", "8443",
                "http.nonProxyHosts", "*.lan|10.*"));
        assertThat(props.forUri(GITHUB)).get().extracting(ProxyEndpoint::host, ProxyEndpoint::port).containsExactly("jproxy", 8443);
        assertThat(props.forUri(URI.create("https://nas.lan/"))).isEmpty();
        assertThat(props.forUri(URI.create("https://10.1.2.3/"))).isEmpty();

        assertThat(resolver(sys, Map.of(), Map.of()).forUri(GITHUB)).isEmpty();
        assertThat(resolver(sys, Map.of(), Map.of()).describeSystem()).startsWith("none");
    }

    @Test
    void noProxyMatching() {
        assertThat(ProxyResolver.bypass(List.of("*"), "anything")).isTrue();
        assertThat(ProxyResolver.bypass(List.of("corp.com"), "corp.com")).isTrue();
        assertThat(ProxyResolver.bypass(List.of("corp.com"), "a.b.corp.com")).isTrue();
        assertThat(ProxyResolver.bypass(List.of("corp.com"), "notcorp.com")).isFalse();
        assertThat(ProxyResolver.bypass(List.of("*.corp.com"), "x.corp.com")).isTrue();
        assertThat(ProxyResolver.bypass(List.of(".corp.com"), "corp.com")).isTrue();
        assertThat(ProxyResolver.bypass(List.of("192.168.1.0/24"), "192.168.1.77")).isTrue();
        assertThat(ProxyResolver.bypass(List.of("192.168.1.0/24"), "192.168.2.1")).isFalse();
        assertThat(ProxyResolver.bypass(List.of("10.*"), "10.9.8.7")).isTrue();
        assertThat(ProxyResolver.bypass(List.of("host:8080"), "HOST")).isTrue();
        assertThat(ProxyResolver.bypass(List.of(), "host")).isFalse();
    }

    @Test
    void parsesProxyUrls() {
        assertThat(ProxyEndpoint.parse("proxy:3128")).extracting(ProxyEndpoint::type, ProxyEndpoint::host, ProxyEndpoint::port)
                .containsExactly(ProxyType.HTTP, "proxy", 3128);
        assertThat(ProxyEndpoint.parse("socks5h://s:1081").type()).isEqualTo(ProxyType.SOCKS5);
        assertThat(ProxyEndpoint.parse("http://proxy").port()).isEqualTo(80);
        assertThatThrownBy(() -> ProxyEndpoint.parse("ftp://x:1")).hasMessageContaining("unsupported");
        assertThat(ProxyEndpoint.redact("http://u:secret@h:1")).isEqualTo("http://u:***@h:1");
    }

    @Test
    void settingsDefaultsValidationAndJson() {
        NetworkSettings d = Json.read("{}", NetworkSettings.class);
        assertThat(d).isEqualTo(NetworkSettings.DEFAULTS);
        assertThat(d.checkForUpdates()).isTrue();
        assertThat(d.updateCheckAllowed()).isTrue();
        assertThat(d.proxyMode()).isEqualTo(ProxyMode.SYSTEM);
        assertThat(Json.read("{\"offline\":true}", NetworkSettings.class).updateCheckAllowed()).isFalse();

        NetworkSettings bad = settings(ProxyMode.MANUAL, null, 0, null, List.of("ok.com", "bad entry!"));
        assertThat(bad.noProxy()).containsExactly("ok.com", "bad", "entry!");
        assertThat(bad.problems()).anyMatch(p -> p.contains("Proxy host is required"))
                .anyMatch(p -> p.contains("port")).anyMatch(p -> p.contains("entry!"));
        NetworkSettings good = settings(ProxyMode.MANUAL, "proxy.corp", 8080, null, List.of("A.COM , 10.0.0.0/8"));
        assertThat(good.problems()).isEmpty();
        assertThat(good.noProxy()).containsExactly("a.com", "10.0.0.0/8");
        assertThat(Json.read(Json.write(good), NetworkSettings.class)).isEqualTo(good);
    }
}
