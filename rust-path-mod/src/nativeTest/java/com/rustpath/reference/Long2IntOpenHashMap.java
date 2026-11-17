package com.rustpath.reference;
final class Long2IntOpenHashMap { private final java.util.HashMap<Long,Integer> m=new java.util.HashMap<>(); private int missing;
void defaultReturnValue(int v){missing=v;}int get(long k){return m.getOrDefault(k,missing);}void put(long k,int v){m.put(k,v);}}
