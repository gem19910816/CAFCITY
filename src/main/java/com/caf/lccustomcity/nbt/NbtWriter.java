package com.caf.lccustomcity.nbt;

import java.io.*;
import java.util.*;

/** Lossless transport through Lost Cities string metadata (JSON numbers lose NBT types). */
public final class NbtWriter {
    private NbtWriter() {}
    public static String encode(Map<String, Object> root) {
        try {
            var bytes = new ByteArrayOutputStream();
            var out = new DataOutputStream(bytes);
            out.writeByte(10); out.writeUTF(""); payload(out, root);
            return Base64.getEncoder().encodeToString(bytes.toByteArray());
        } catch (IOException e) { throw new UncheckedIOException(e); }
    }
    private static int type(Object v) {
        if (v instanceof Byte) return 1;
        if (v instanceof Short) return 2;
        if (v instanceof Integer) return 3;
        if (v instanceof Long) return 4;
        if (v instanceof Float) return 5;
        if (v instanceof Double) return 6;
        if (v instanceof byte[]) return 7;
        if (v instanceof String) return 8;
        if (v instanceof List<?>) return 9;
        if (v instanceof Map<?, ?>) return 10;
        if (v instanceof int[]) return 11;
        if (v instanceof long[]) return 12;
        throw new IllegalArgumentException("Unsupported NBT value: " + v);
    }
    private static void payload(DataOutputStream out, Object v) throws IOException {
        switch (type(v)) {
            case 1 -> out.writeByte((Byte)v);
            case 2 -> out.writeShort((Short)v);
            case 3 -> out.writeInt((Integer)v);
            case 4 -> out.writeLong((Long)v);
            case 5 -> out.writeFloat((Float)v);
            case 6 -> out.writeDouble((Double)v);
            case 7 -> { byte[] a=(byte[])v; out.writeInt(a.length); out.write(a); }
            case 8 -> out.writeUTF((String)v);
            case 9 -> {
                List<?> a=(List<?>)v; int t=a.isEmpty()?0:type(a.getFirst());
                out.writeByte(t); out.writeInt(a.size());
                for (Object x:a) { if(type(x)!=t) throw new IOException("Mixed NBT list"); payload(out,x); }
            }
            case 10 -> {
                for (var e:((Map<?,?>)v).entrySet()) {
                    out.writeByte(type(e.getValue())); out.writeUTF((String)e.getKey()); payload(out,e.getValue());
                }
                out.writeByte(0);
            }
            case 11 -> { int[] a=(int[])v; out.writeInt(a.length); for(int x:a)out.writeInt(x); }
            case 12 -> { long[] a=(long[])v; out.writeInt(a.length); for(long x:a)out.writeLong(x); }
        }
    }
}
