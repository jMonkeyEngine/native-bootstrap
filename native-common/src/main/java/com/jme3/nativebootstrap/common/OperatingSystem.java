package com.jme3.nativebootstrap.common;

/** Operating system identifiers independent of detection or native loading. */
public enum OperatingSystem {
    /** Microsoft Windows. */
    WINDOWS,
    /** Apple macOS, including the Darwin alias. */
    MACOS,
    /** Desktop or server Linux. */
    LINUX,
    /** Android, which also reports a Linux kernel. */
    ANDROID,
    /** Apple iOS. */
    IOS,
    /** FreeBSD. */
    FREEBSD,
    /** OpenBSD. */
    OPENBSD,
    /** NetBSD. */
    NETBSD,
    /** Solaris or SunOS. */
    SOLARIS,
    /** Unrecognized or unavailable system information. */
    UNKNOWN
}
