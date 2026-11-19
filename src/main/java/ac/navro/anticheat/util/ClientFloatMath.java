package ac.navro.anticheat.util;


public final class ClientFloatMath {
    private static final double HALF_PI=1.5707963267948966,TWO_OVER_PI=0.6366197723675814,LARGE_REDUCTION_SCALE=3.4061215800865545e-19;
    private static final double C1=-0.49999999725108224,C2=0.041666623324344516,C3=-0.001388676379437604,C4=2.4390450703564542e-05;
    private static final double S1=-0.16666654943701084,S2=0.008332178146138854,S3=-0.00019517298981385725;
    private static final int[] FOUR_OVER_PI={0xa2,0xa2f9,0xa2f983,0xa2f9836e,0xf9836e4e,0x836e4e44,0x6e4e4415,0x4e441529,0x441529fc,0x1529fc27,0x29fc2757,0xfc2757d1,0x2757d1f5,0x57d1f534,0xd1f534dd,0xf534ddc0,0x34ddc0db,0xddc0db62,0xc0db6295,0xdb629599,0x6295993c,0x95993c43,0x993c4390,0x3c439041};
    private ClientFloatMath(){}
    public static float sinf(float value){return trig(value,false);} public static float cosf(float value){return trig(value,true);}
    private static float trig(float value,boolean cosine){
        int bits=Float.floatToRawIntBits(value),top=(bits>>>20)&0x7ff; double x=value;
        if(top<0x3f4){if(top<0x398)return cosine?1F:value;return polynomial(x,x*x,false,cosine);}
        final double reduced; final int quadrant,signQuadrant;
        if(top<0x42f){double scaled=x*TWO_OVER_PI;quadrant=(int)Math.copySign(Math.floor(Math.abs(scaled)+.5D),scaled);reduced=Math.fma(-quadrant,HALF_PI,x);signQuadrant=quadrant;}
        else if(top<0x7f8){int index=(bits>>>26)&15,shift=(bits>>>23)&7;long mantissa=Integer.toUnsignedLong(((bits&0xffffff)|0x800000)<<shift);long high=mantissa*Integer.toUnsignedLong(FOUR_OVER_PI[index]),middle=mantissa*Integer.toUnsignedLong(FOUR_OVER_PI[index+4]),low=mantissa*Integer.toUnsignedLong(FOUR_OVER_PI[index+8]);long product=(low>>>32|high<<32)+middle;quadrant=(int)((product+(1L<<61))>>>62);long remainder=product-((long)quadrant<<62);reduced=(double)remainder*LARGE_REDUCTION_SCALE;signQuadrant=quadrant+(bits>>>31);}
        else return value-value;
        int phase=signQuadrant&3;double signed=phase==1||phase==2?-reduced:reduced;boolean cosinePolynomial=((quadrant^(cosine?1:0))&1)!=0;return polynomial(signed,reduced*reduced,(signQuadrant&2)!=0,cosinePolynomial);
    }
    private static float polynomial(double x,double squared,boolean negativeCosine,boolean cosine){
        if(!cosine){double cubed=x*squared,tail=Math.fma(squared,S3,S2),fifth=cubed*squared,leading=Math.fma(cubed,S1,x);return (float)Math.fma(fifth,tail,leading);}
        double c0=negativeCosine?-1:1,c1=negativeCosine?-C1:C1,c2=negativeCosine?-C2:C2,c3=negativeCosine?-C3:C3,c4=negativeCosine?-C4:C4;double fourth=squared*squared,tail=Math.fma(squared,c4,c3),leading=Math.fma(squared,c1,c0),sixth=squared*fourth,combined=Math.fma(fourth,c2,leading);return (float)Math.fma(sixth,tail,combined);
    }
}
