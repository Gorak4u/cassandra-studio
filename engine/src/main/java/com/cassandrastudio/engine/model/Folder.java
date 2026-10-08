package com.cassandrastudio.engine.model;

/** A node in the connection tree, e.g. Customer > Environment > Cluster (CON-2). */
public record Folder(String id, String parentId, String name, int position) {}
