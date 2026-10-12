package com.pegasus.pegasustcgapi.job;

/**
 * One instance at a time for a named background job.
 *
 * <p>Scheduled methods fire on every replica. Some jobs tolerate that; the ones
 * that sweep a table and act on what they find do not — every replica reads the
 * same rows and races the others for them. Wrapping the sweep in this hands the
 * work to one instance and lets the rest go back to sleep.
 */
public interface JobLock {

    /**
     * @return true when this instance held the lock and ran the task, false when
     *         another instance is already running it
     */
    boolean runExclusively(String name, Runnable task);
}
