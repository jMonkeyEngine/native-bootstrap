package com.jme3.nativebootstrap.os;

import org.junit.jupiter.api.Test;
import com.jme3.nativebootstrap.common.OperatingSystem;
import java.util.Locale;
import static org.junit.jupiter.api.Assertions.*;

class OperatingSystemsTest {
    @Test
    void recognizesDesktopLabelsIncludingDarwin() {
        assertEquals(OperatingSystem.WINDOWS, classify("Windows 11"));
        assertEquals(OperatingSystem.WINDOWS, classify("Windows 10"));
        assertEquals(OperatingSystem.MACOS, classify("Mac OS X"));
        assertEquals(OperatingSystem.MACOS, classify("macOS"));
        assertEquals(OperatingSystem.MACOS, classify("Darwin"));
        assertEquals(OperatingSystem.LINUX, classify("Linux"));
    }

    @Test
    void detectReadsCurrentPropertiesAndRestoresTheRuntime() {
        String[] keys = { "os.name", "java.runtime.name", "java.vm.name" };
        String[] saved = new String[keys.length];
        for (int i = 0; i < keys.length; i++) saved[i] = System.getProperty(keys[i]);
        try {
            System.setProperty("java.runtime.name", "OpenJDK Runtime Environment");
            System.setProperty("java.vm.name", "OpenJDK 64-Bit Server VM");
            for (String label : new String[] { "Mac OS X", "Darwin", "Windows 11", "Linux" }) {
                System.setProperty("os.name", label);
                assertEquals(classify(label), OperatingSystems.detect(), label);
            }
        } finally {
            for (int i = 0; i < keys.length; i++) {
                if (saved[i] == null) System.clearProperty(keys[i]);
                else System.setProperty(keys[i], saved[i]);
            }
        }
    }

    @Test
    void detectsAndroidBeforeLinux() {
        assertEquals(OperatingSystem.ANDROID, OperatingSystems.classify("Linux", "Android Runtime", "ART"));
        assertEquals(OperatingSystem.ANDROID, OperatingSystems.classify("Linux", "", "Dalvik"));
        assertEquals(OperatingSystem.ANDROID, classify("Android"));
    }

    @Test
    void recognizesOtherExplicitPlatformsWithoutGuessingLinux() {
        assertEquals(OperatingSystem.IOS, classify("iOS"));
        assertEquals(OperatingSystem.IOS, classify("iPhone OS"));
        assertEquals(OperatingSystem.FREEBSD, classify("FreeBSD"));
        assertEquals(OperatingSystem.OPENBSD, classify("OpenBSD"));
        assertEquals(OperatingSystem.NETBSD, classify("NetBSD"));
        assertEquals(OperatingSystem.SOLARIS, classify("SunOS"));
        assertEquals(OperatingSystem.UNKNOWN, classify("Plan 9"));
        assertEquals(OperatingSystem.UNKNOWN, classify(""));
        assertEquals(OperatingSystem.UNKNOWN, OperatingSystems.classify(null, null, null));
    }

    @Test
    void classificationIgnoresDefaultLocaleAndWhitespace() {
        Locale previous = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));
            assertEquals(OperatingSystem.WINDOWS, classify("  WINDOWS 11  "));
            assertEquals(OperatingSystem.MACOS, classify(" DARWIN "));
        } finally {
            Locale.setDefault(previous);
        }
    }

    private OperatingSystem classify(String name) {
        return OperatingSystems.classify(name, "", "");
    }
}
