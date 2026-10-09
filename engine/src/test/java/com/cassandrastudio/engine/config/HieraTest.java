package com.cassandrastudio.engine.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Expected values from a small control repo laid out like the estate's, and from the real one when present. */
class HieraTest {
    @TempDir
    Path dir;

    static void write(Path root, String rel, String text) throws IOException {
        Path p = root.resolve(rel);
        Files.createDirectories(p.getParent());
        Files.writeString(p, text);
    }

    /** A cut-down copy of the estate's layout: hierarchy, profile lookups, template. */
    static Path miniRepo(Path root) throws IOException {
        write(root, "hiera.yaml", """
                ---
                version: 5
                defaults:
                  datadir: data
                  data_hash: yaml_data
                hierarchy:
                  - name: 'Secrets'
                    lookup_key: eyaml_lookup_key
                    path: 'secrets/%{trusted.certname}.eyaml'
                    options: &o
                      pkcs7_private_key: /x
                  - name: 'Node'
                    path: 'nodes/%{trusted.certname}.yaml'
                  - name: 'Cluster in DC'
                    path: 'customers/%{trusted.extensions.pp_project}/%{trusted.extensions.pp_environment}/products/%{trusted.extensions.pp_product}/clusters/%{trusted.extensions.pp_cluster}/%{trusted.extensions.pp_datacenter}.yaml'
                  - name: 'Cluster'
                    path: 'customers/%{facts.customer}/%{facts.customer_environment}/products/%{facts.product}/clusters/%{facts.cluster_id}.yaml'
                  - name: 'Role'
                    path: 'products/%{trusted.extensions.pp_product}/roles/%{trusted.extensions.pp_role}.yaml'
                  - name: 'Common'
                    path: 'common.yaml'
                """);
        write(root, "data/common.yaml", """
                profile_cassandra_pfpt::num_tokens: 256
                profile_cassandra_pfpt::compaction_throughput_mb_per_sec: 32
                """);
        write(root, "data/customers/acme/prod/products/cassandra/clusters/core.yaml", """
                profile_cassandra_pfpt::cluster_name: 'acme-prod-core'
                profile_cassandra_pfpt::num_tokens: 16
                profile_cassandra_pfpt::seeds: ['10.0.0.2', '10.0.0.1']
                profile_cassandra_pfpt::cassandra_yaml_extra_options:
                  auto_snapshot_ttl: '30d'
                  concurrent_reads: 99
                """);
        write(root, "data/customers/acme/prod/products/cassandra/clusters/core/dc_west.yaml", """
                profile_cassandra_pfpt::compaction_throughput_mb_per_sec: 128
                """);
        write(root, "data/nodes/west1.example.yaml", """
                profile_cassandra_pfpt::read_request_timeout_in_ms: 10000
                """);
        write(root, "data/products/cassandra/roles/cassandra_seed.yaml", """
                profile_cassandra_pfpt::auto_snapshot: true
                profile_cassandra_pfpt::hinted_handoff_enabled: false
                """);
        write(root, "site-modules/profile_cassandra_pfpt/manifests/init.pp", """
                class profile_cassandra_pfpt {
                  $cluster_name = lookup('profile_cassandra_pfpt::cluster_name', { 'default_value' => 'pfpt-cassandra-cluster' })
                  $partitioner = lookup('profile_cassandra_pfpt::partitioner', { 'default_value' => 'org.apache.cassandra.dht.Murmur3Partitioner'})
                  $num_tokens = lookup('profile_cassandra_pfpt::num_tokens', { 'default_value' => 256 })
                  $compaction_throughput_mb_per_sec = lookup('profile_cassandra_pfpt::compaction_throughput_mb_per_sec', { 'default_value' => undef })
                  $read_request_timeout_in_ms = lookup('profile_cassandra_pfpt::read_request_timeout_in_ms', { 'default_value' => undef })
                  $seeds_from_hiera = lookup('profile_cassandra_pfpt::seeds', { 'default_value' => [] })
                  $auto_snapshot = lookup('profile_cassandra_pfpt::auto_snapshot', { 'default_value' => undef })
                  $hinted_handoff_enabled = lookup('profile_cassandra_pfpt::hinted_handoff_enabled', { 'default_value' => true })
                  $data_dir = lookup('profile_cassandra_pfpt::data_dir', { 'default_value' => '/var/lib/cassandra/data' })
                  $cassandra_yaml_extra_options = lookup('profile_cassandra_pfpt::cassandra_yaml_extra_options', { 'default_value' => {} })
                  class { 'cassandra_pfpt':
                    cluster_name                     => $cluster_name,
                    seeds                            => $seeds_from_hiera,
                    keystore_password                => $keystore_password,
                  }
                }
                """);
        write(root, "site-modules/cassandra_pfpt/templates/cassandra.yaml.erb", """
                <%- managed_keys = ['cluster_name'] -%>
                cluster_name: '<%= @cluster_name %>'
                partitioner: <%= @partitioner %>
                <% if @hinted_handoff_enabled -%>
                hinted_handoff_enabled: <%= @hinted_handoff_enabled %>
                <% end -%>
                <% if @num_tokens -%>
                num_tokens: <%= @num_tokens %>
                <% end -%>
                data_file_directories:
                    - <%= @data_dir %>
                seed_provider:
                    - class_name: org.apache.cassandra.locator.SimpleSeedProvider
                      parameters:
                          - seeds: "<%= @seeds.join(',') %>"
                <% if @compaction_throughput_mb_per_sec -%>
                <% if @cassandra_uses_typed_units -%>
                compaction_throughput: <%= @compaction_throughput_mb_per_sec %>MiB/s
                <% else -%>
                compaction_throughput_mb_per_sec: <%= @compaction_throughput_mb_per_sec %>
                <% end -%>
                <% end -%>
                <% if @read_request_timeout_in_ms -%>
                read_request_timeout_in_ms: <%= @read_request_timeout_in_ms %>
                <% end -%>
                <% unless @auto_snapshot.nil? -%>
                auto_snapshot: <%= @auto_snapshot %>
                <% end -%>
                server_encryption_options:
                  keystore_password: <%= @keystore_password.unwrap %>
                """);
        return root;
    }

    static Map<String, String> facts(String dc, String certname, String role) {
        Map<String, String> f = new java.util.HashMap<>(Map.of("customer", "acme", "environment", "prod",
                "product", "cassandra", "cluster", "core", "datacenter", dc));
        if (certname != null) f.put("certname", certname);
        if (role != null) f.put("role", role);
        return f;
    }

    @Test
    void expectedValuesFollowTheHierarchy() throws IOException {
        Hiera h = Hiera.load(miniRepo(dir));
        Map<String, Hiera.Expected> east = h.expected(Hiera.variablesFor(facts("dc_east", null, null)));
        assertThat(east.get("cluster_name").value()).isEqualTo("acme-prod-core");
        assertThat(east.get("cluster_name").source()).contains("clusters/core.yaml");
        assertThat(east.get("num_tokens").value()).isEqualTo("16");
        assertThat(east.get("compaction_throughput").value()).isEqualTo("32MiB/s");
        assertThat(east.get("compaction_throughput").source()).startsWith("common.yaml");
        assertThat(east.get("partitioner").value()).isEqualTo("Murmur3Partitioner");
        assertThat(east.get("partitioner").source()).startsWith("module default");
        assertThat(east.get("hinted_handoff_enabled").value()).isEqualTo("true");
        assertThat(east.get("data_file_directories").value()).isEqualTo("[/var/lib/cassandra/data]");
        assertThat(east.get("seed_provider.seeds").value()).isEqualTo("[10.0.0.1, 10.0.0.2]");
        assertThat(east.get("auto_snapshot_ttl").value()).isEqualTo("30d");
        assertThat(east.get("concurrent_reads").value()).isEqualTo("99");
        assertThat(east).doesNotContainKey("read_request_timeout").doesNotContainKey("auto_snapshot")
                .doesNotContainKey("server_encryption_options.keystore_password");

        // a DC layer and a node layer override, the role layer adds, a false in an "if" block is not rendered
        Map<String, Hiera.Expected> west = h.expected(Hiera.variablesFor(facts("dc_west", "west1.example", "cassandra_seed")));
        assertThat(west.get("compaction_throughput").value()).isEqualTo("128MiB/s");
        assertThat(west.get("read_request_timeout").value()).isEqualTo("10s");
        assertThat(west.get("auto_snapshot").value()).isEqualTo("true");
        assertThat(west).doesNotContainKey("hinted_handoff_enabled");
        assertThat(h.layers(Hiera.variablesFor(facts("dc_west", "west1.example", "cassandra_seed")))).containsExactly(
                "nodes/west1.example.yaml", "customers/acme/prod/products/cassandra/clusters/core/dc_west.yaml",
                "customers/acme/prod/products/cassandra/clusters/core.yaml", "products/cassandra/roles/cassandra_seed.yaml",
                "common.yaml");
    }

    @Test
    void factValuesAndErrors() throws IOException {
        Hiera h = Hiera.load(miniRepo(dir));
        Map<String, List<String>> v = h.factValues();
        assertThat(v.get("customer")).containsExactly("acme");
        assertThat(v.get("cluster")).containsExactly("core");
        assertThat(v.get("datacenter")).containsExactly("dc_west");
        assertThat(v.get("role")).containsExactly("cassandra_seed");
        assertThat(v.get("certname")).containsExactly("west1.example");
        assertThat(h.variables()).contains("trusted.certname", "facts.cluster_id").doesNotContain("x");
        // traversal through a fact value is refused
        assertThat(h.layers(Hiera.variablesFor(Map.of("certname", "../common")))).containsExactly("common.yaml");
        assertThatThrownBy(() -> Hiera.load(dir.resolve("missing"))).hasMessageContaining("not found");
        Files.createDirectories(dir.resolve("empty"));
        assertThatThrownBy(() -> Hiera.load(dir.resolve("empty"))).hasMessageContaining("No hiera.yaml");
    }

    @Test
    void theEstateControlRepo() {
        Path repo = Path.of(ConfigService.DEFAULT_REPO);
        assumeTrue(Files.isDirectory(repo), "control repo checkout not present");
        Hiera h = Hiera.load(repo);
        Map<String, Hiera.Expected> e = h.expected(Hiera.variablesFor(facts("dc_east", null, "cassandra_seed")));
        assertThat(e.get("cluster_name").value()).isEqualTo("acme-prod-core");
        assertThat(e.get("num_tokens").value()).isEqualTo("16");
        assertThat(e.get("endpoint_snitch").value()).isEqualTo("GossipingPropertyFileSnitch");
        assertThat(e.get("disk_failure_policy").value()).isEqualTo("stop");
        assertThat(h.factValues().get("customer")).contains("acme", "amex", "globex");
    }
}
