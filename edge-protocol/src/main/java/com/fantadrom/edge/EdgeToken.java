/* SPDX-License-Identifier: LGPL-3.0-or-later
 * Sec-MS-GEC v2 algorithm ported from rany2/edge-tts drm.py, revision recorded in NOTICE.
 * Copyright (c) 2025-2026 rany <rany@riseup.net>; Java adaptation (c) 2026 JJReader contributors.
 * This library is free software under LGPL v3 or later, WITHOUT ANY WARRANTY.
 * See LICENSE and COPYING in this module. No Python runtime is included.
 */
package com.fantadrom.edge;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

public final class EdgeToken {
    private EdgeToken() {}
    /** Public protocol identifier, not an Azure subscription credential. */
    public static final String CLIENT = "6A5AA1D4EAFF4E9FB37E23D68491D6F4";
    private static int[] words(long... v) { int[] r = new int[v.length]; for (int i=0;i<v.length;i++) r[i]=(int)v[i]; return r; }
    private static final int[] INITIAL = words(0x6A09E667L,0xBB67AE85L,0x3C6EF372L,0xA54FF53AL,0x510E527FL,0x9B05688CL,0x1F83D9ABL,0x5BE0CD19L);
    private static final int[] STATE = words(0xFD9FBEA2L,0x91243CE7L,0xBE499094L,0xCD2E1E0AL,0x6C006F49L,0x04BD35ABL,0xFEB0A7E4L,0x03EFFBD2L);
    private static final int[] SCHEDULE = words(0xEB24BF9EL,0x1558691FL,0xA0C09EE2L,0x0CFA32BAL,0xA622EE8CL,0xD1DD9491L,0xC0DD25A0L,0xF9E70AB6L,0xF0C31573L,0x7058CE4EL,0x7188E606L,0x00E5AB33L,0xD258B060L,0x72766564L);
    private static final int[] K = words(0x428A2F98L,0x71374491L,0xB5C0FBCFL,0xE9B5DBA5L,0x3956C25BL,0x59F111F1L,0x923F82A4L,0xAB1C5ED5L,0xD807AA98L,0x12835B01L,0x243185BEL,0x550C7DC3L,0x72BE5D74L,0x80DEB1FEL,0x9BDC06A7L,0xC19BF174L,0xE49B69C1L,0xEFBE4786L,0x0FC19DC6L,0x240CA1CCL,0x2DE92C6FL,0x4A7484AAL,0x5CB0A9DCL,0x76F988DAL,0x983E5152L,0xA831C66DL,0xB00327C8L,0xBF597FC7L,0xC6E00BF3L,0xD5A79147L,0x06CA6351L,0x14292967L,0x27B70A85L,0x2E1B2138L,0x4D2C6DFCL,0x53380D13L,0x650A7354L,0x766A0ABBL,0x81C2C92EL,0x92722C85L,0xA2BFE8A1L,0xA81A664BL,0xC24B8B70L,0xC76C51A3L,0xD192E819L,0xD6990624L,0xF40E3585L,0x106AA070L,0x19A4C116L,0x1E376C08L,0x2748774CL,0x34B0BCB5L,0x391C0CB3L,0x4ED8AA4AL,0x5B9CCA4FL,0x682E6FF3L,0x748F82EEL,0x78A5636FL,0x84C87814L,0x8CC70208L,0x90BEFFFAL,0xA4506CEBL,0xBEF9A3F7L,0xC67178F2L);
    private static final int[] COEFF = words(0xE82E91B1L,0x44DE24C6L,0x3BEB65BCL,0xC89C5933L,0x8D1EC211L,0x314EF5D5L,0xE92491B1L,0xF0A5B9D5L,0x06064CF3L,0xDA79FA60L,0xBED20560L,0x89F9C9F8L,0x35FA38C6L,0xCB49E3C6L);
    private static final int[] OFFSETS = words(0xB32700D0L,0x88DFF51DL,0x29514038L,0xB328CBDFL,0xD405B4D5L,0x26E55F3CL,0x607801C6L,0x82B2C1FBL,0xFA51A3DAL,0x207CC464L,0x3C3C74F3L,0xD2D14B61L,0xBB04A2C1L,0xC5626167L);
    private static int s0(int x) { return Integer.rotateRight(x,7)^Integer.rotateRight(x,18)^(x>>>3); }
    private static int s1(int x) { return Integer.rotateRight(x,17)^Integer.rotateRight(x,19)^(x>>>10); }
    private static void expand(int[] w,int from) { for(int i=from;i<64;i++) w[i]=s1(w[i-2])+w[i-7]+s0(w[i-15])+w[i-16]; }
    private static int[] rounds(int[] state,int[] w,int from) {
        int a=state[0],b=state[1],c=state[2],d=state[3],e=state[4],f=state[5],g=state[6],h=state[7];
        for(int i=from;i<64;i++) {
            int t1=h+(Integer.rotateRight(e,6)^Integer.rotateRight(e,11)^Integer.rotateRight(e,25))+(g^(e&(f^g)))+(from==14 && i==14 ? (int)0xC9DC06A7L:K[i])+w[i];
            int t2=(Integer.rotateRight(a,2)^Integer.rotateRight(a,13)^Integer.rotateRight(a,22))+((a&b)|(c&(a|b)));
            h=g;g=f;f=e;e=d+t1;d=c;c=b;b=a;a=t1+t2;
        }
        return new int[]{a,b,c,d,e,f,g,h};
    }
    private static byte[] digest(byte[] data) {
        int[] w=new int[64];
        w[14]=((data[0]&255)<<16)|((data[1]&255)<<8)|(data[2]&255);
        w[15]=ByteBuffer.wrap(data,3,4).getInt();
        for(int i=16;i<30;i++) {
            int v=SCHEDULE[i-16];
            if(i-2>=14) v+=s1(w[i-2])^(i==16?0x000B92C0:0);
            if(i-7>=14) v+=w[i-7];
            if(i-15>=14) v+=s0(w[i-15])^(i==29?0x059C0B80:0);
            w[i]=v;
        }
        w[30]=s1(w[28])+w[23]+s0(w[15])+w[14]+(int)0xC9DC06A7L-K[14];
        expand(w,31);
        int[] state=rounds(STATE,w,14);
        for(int i=0;i<8;i++) state[i]+=INITIAL[i];
        int length=57+data.length;
        int zeros=Math.floorMod(55-length,64);
        ByteBuffer padded=ByteBuffer.allocate(data.length-7+1+zeros+8);
        padded.put(data,7,data.length-7).put((byte)0x80);
        padded.position(padded.position()+zeros);padded.putLong(length*8L);
        padded.flip();
        while(padded.hasRemaining()) {
            for(int i=0;i<16;i++) w[i]=padded.getInt();
            expand(w,16);int[] result=rounds(state,w,0);
            for(int i=0;i<8;i++) state[i]+=result[i];
        }
        ByteBuffer result=ByteBuffer.allocate(32);for(int n:state) result.putInt(n);return result.array();
    }
    private static byte[] cs64(byte[] data,byte[] digest) {
        ByteBuffer key=ByteBuffer.wrap(digest).order(ByteOrder.LITTLE_ENDIAN);
        int ka=key.getInt()|1,kb=key.getInt()|1;
        int fa=ka*(int)0x82893933L,fb=kb*(int)0x82893933L;
        int count=((data.length+1+7)/8)*2;
        int[] terms=Arrays.copyOf(COEFF,14+count),offsets=Arrays.copyOf(OFFSETS,14+count);
        for(int i=0;i<14;i++) terms[i]*=i%2==0?fa:fb;
        ByteBuffer padded=ByteBuffer.allocate(count*4).order(ByteOrder.LITTLE_ENDIAN);
        padded.put((byte)0).put(data);padded.rewind();
        for(int i=0;i<count;i++) {
            int word=padded.getInt();
            terms[14+i]=word*(i%2==0?ka:kb)+(i==0?(int)0xF2F90B1AL*fa:0);
            offsets[14+i]=Integer.rotateLeft(word,16)*(i%2==0?(int)0xEF0569FBL:(int)0xC31713DBL)+(i==0?0x0B1A0000:0);
        }
        int swapAcc=0,swapLast=0,state=0,acc=0;
        for(int i=0;i<terms.length;i+=2) {
            int x=terms[i],y=terms[i+1];
            int q=Integer.rotateLeft(offsets[i]+x,16)*0x76F50F34;
            swapAcc+=Integer.rotateLeft(q,16)*(int)0xC3EFEA97L+q;
            q=Integer.rotateLeft(offsets[i+1]+y,16)*0x1881CE3C;
            swapLast=Integer.rotateLeft(q,16)*0x35BD1EC9+q;swapAcc+=swapLast;
            state=state*ka+x;
            state=Integer.rotateLeft(state,16)*(int)0xCF98B111L;
            state=Integer.rotateLeft(state,16)*(int)0x87085B9FL;
            state=Integer.rotateLeft(state,16)*0x12CEB96D;
            state=Integer.rotateLeft(state,16)*0x257E1D83;acc+=state;
            int t=state*kb+y;
            t=Integer.rotateLeft(t,16)*(int)0xA27416F5L;
            t=Integer.rotateLeft(t,16)*(int)0xD38396FFL;
            state=Integer.rotateLeft(Integer.rotateLeft(t,16)*0x7C932B89,16)*(int)0xBFA49F69L;acc+=state;
        }
        return ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putInt(swapLast^state).putInt(swapAcc^acc).array();
    }
    private static String hex(byte[] bytes) { StringBuilder s=new StringBuilder();for(byte b:bytes) s.append(String.format(java.util.Locale.ROOT,"%02X",b));return s.toString(); }
    public static String generate(long epochSeconds) {
        long seconds=epochSeconds+11644473600L;seconds-=Math.floorMod(seconds,300);
        byte[] data=(Long.toString(seconds*10000000L)+CLIENT).getBytes(StandardCharsets.US_ASCII);
        byte[] digest=digest(data);return hex(cs64(data,digest))+"-"+hex(digest);
    }
}
