package com.rustpath.reference;
public final class V5Flags {
public static final short PASSABLE=1,SOLID=2,PASSABLE_FLY=4,BLOCKING_WALL=8,FLUID=16,SLAB_BOTTOM=32,SLAB_TOP=64,FENCE_LIKE=128,STAIRS_BOTTOM=256,CARPET_LIKE=512,CLIMBABLE=1024,AIR=5,UNLOADED=10;
public static boolean has(int f,int b){return (f&b)!=0;}public static boolean passable(int f){return (f&(PASSABLE|CARPET_LIKE))!=0;}}
