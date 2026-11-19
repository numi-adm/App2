package com.rooo.dashboard;

public class NativeBridge {
    static {
        System.loadLibrary("roblox_dash");
    }

    public static native void initDb(String dbPath);
    public static native String getAccountsJson();
    public static native int getAccountCount();
    public static native int addAccount(String username, String password, String cookie);
    public static native int importText(String text);
    public static native int importCsv(String csvText);
    public static native String exportCsv(int[] indices);
    public static native int deleteIndices(int[] indices);
    public static native int storeCookieFromHeader(int idx, String username, String rawCookieHeader, String note);
    public static native void updateStatus(int idx, String status, String note);
    public static native int setUsername(int idx, String username);
    public static native String getAccountField(int idx, String field);
    public static native String buildLoginJs(int idx);
    public static native String classifyPage(int idx, String url, String pageText, boolean hasArkose, String rawCookies);
}
