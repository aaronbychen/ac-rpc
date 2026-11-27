package com.ac.acrpc.bench;

import java.lang.management.ManagementFactory;

/**
 * Process-level memory, including native memory such as thread stacks that heap metrics miss.
 */
final class ProcessMemory {

    private static final com.sun.management.OperatingSystemMXBean OS =
            (com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();

    private ProcessMemory() {
    }

    /** Committed virtual memory of this process (private bytes on Windows), in MB. */
    static long committedMb() {
        return OS.getCommittedVirtualMemorySize() / (1024 * 1024);
    }
}
