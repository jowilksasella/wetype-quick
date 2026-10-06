package com.tencent.wxhld.info;
/** Authored test double for the pinned ABI; no original implementation. Never package in the app. */
public class Candidate {
    public String text, comment, jsonIdDesc;
    public byte[] id;
    public int id_len;
    public Candidate(int len) { id_len = len; id = new byte[len]; }
}
