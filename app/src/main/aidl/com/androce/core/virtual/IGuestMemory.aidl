package com.androce.core.virtual;

interface IGuestMemory {
    boolean ping();
    /** Copy memscan binary into guest files dir (host pushes after connect). */
    boolean installMemscan(in byte[] binary);
    String getRegionsLines();
    byte[] readBytes(long address, int length);
    long[] scanPattern(in byte[] pattern, byte wildcard, in String regionPairs, int maxResults);
    /** Batch read for refine / post-scan: lines "idx:hex" */
    String readBatchLines(in long[] addresses, int length);
    /** Keep addresses whose bytes still match pattern (runs in guest, same logic as rooted refine). */
    long[] refinePattern(in long[] addresses, in byte[] pattern, byte wildcard);
    /** Same as refinePattern; lines are "address:hexbytes" */
    String refinePatternLines(in long[] addresses, in byte[] pattern, byte wildcard);
    boolean writeBytes(long address, in byte[] data);
}
