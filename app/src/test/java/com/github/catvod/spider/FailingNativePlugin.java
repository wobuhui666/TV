package com.github.catvod.spider;

public final class FailingNativePlugin {
    public static void load() {
        throw new UnsatisfiedLinkError("bad ELF magic: 3c3f786d");
    }
}
