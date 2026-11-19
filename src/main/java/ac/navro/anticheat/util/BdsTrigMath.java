package ac.navro.anticheat.util;


public final class BdsTrigMath {
    private static final int TABLE_BITS=16,TABLE_SIZE=1<<TABLE_BITS,TABLE_MASK=TABLE_SIZE-1,COS_OFFSET=TABLE_SIZE/4;
    private static final float RADIANS_TO_INDEX=10430.3779296875F;
    private static final float[] SIN_TABLE=new float[TABLE_SIZE];
    static { for(int index=0;index<TABLE_SIZE;index++) SIN_TABLE[index]=ClientFloatMath.sinf((float)index/RADIANS_TO_INDEX); }
    private BdsTrigMath(){}
    public static float sin(float radians){return SIN_TABLE[((int)(radians*RADIANS_TO_INDEX))&TABLE_MASK];}
    public static float cos(float radians){return SIN_TABLE[((int)(radians*RADIANS_TO_INDEX+COS_OFFSET))&TABLE_MASK];}
}
