package com.cassandrastudio.engine.jobs;

/** The body of a job. Return a result (or null); throw to fail. */
@FunctionalInterface
public interface JobTask {
    Object run(JobContext ctx) throws Exception;
}
