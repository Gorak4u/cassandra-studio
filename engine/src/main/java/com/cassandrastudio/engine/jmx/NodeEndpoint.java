package com.cassandrastudio.engine.jmx;

/**
 * One Cassandra node as seen by the driver: where to reach it for JMX, SSH or
 * jmx_exporter. {@code address} is the node's broadcast RPC address (what
 * ClusterService reports); SSH and direct JMX connect to it.
 */
public record NodeEndpoint(String hostId, String address, String datacenter, String rack, String version) {}
