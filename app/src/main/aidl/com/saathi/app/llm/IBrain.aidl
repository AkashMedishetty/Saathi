package com.saathi.app.llm;

// The on-device models live in the ":brain" process; the app (and its accessibility service) talks to them here.
interface IBrain {
    String state();
    void load();
    void unload();
    String generate(String system, String user);
    String chat(String key, String system, String user);
    int turns();
    void endChat();
    long lastGenMs();
    boolean fastReady();
    String fast(String system, String user);
    String vision(in byte[] jpeg, String prompt);
    String visionInfo();
    void unloadVision();
}
