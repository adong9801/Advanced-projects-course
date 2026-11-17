package com.rustpath.reference;
public final class  V5World {
 public interface Source {short flags(int x,int y,int z);}
 public interface Corners {boolean squeezes(int x,int y,int z,int dx,int dz);}
 private final Source source;private final Corners corners;
 public V5World(Source s,Corners c){source=s;corners=c;}
 short flags(int x,int y,int z){return source.flags(x,y,z);}
 boolean squeezes(int x,int y,int z,int dx,int dz){return corners.squeezes(x,y,z,dx,dz);}
 final Bytes classes=new Bytes();final Floats penalties=new Floats();
 static class Bytes {java.util.HashMap<Long,Byte> m=new java.util.HashMap<>();byte get(long k){return m.getOrDefault(k,(byte)0);}void put(long k,byte v){m.put(k,v);}}
 static class Floats {java.util.HashMap<Long,Float> m=new java.util.HashMap<>();float get(long k){return m.getOrDefault(k,Float.NaN);}void put(long k,float v){m.put(k,v);}}
 static long key(int x,int y,int z){long px=((long)x+33554432L)&0x3ffffffL,py=((long)y+2048L)&0xfffL,pz=((long)z+33554432L)&0x3ffffffL;return (px<<38)|(py<<26)|pz;}
}
