package ac.navro.proxy.prediction.live;
import ac.navro.proxy.prediction.engine.data.VectorType;
import ac.navro.proxy.collision.CollisionShapeEntry;


public final class NativeIds {
    public static final class BlockID {
        public static final int AIR = 0;
        public static final int BED_BLOCK = 26;
        public static final int BLUE_ICE = 266;
        public static final int BUBBLE_COLUMN = 415;
        public static final int CAVE_VINES = 577;
        public static final int CAVE_VINES_BODY_WITH_BERRIES = 630;
        public static final int CAVE_VINES_HEAD_WITH_BERRIES = 631;
        public static final int COBWEB = 30;
        public static final int FROSTED_ICE = 207;
        public static final int HONEY_BLOCK = 475;
        public static final int ICE = 79;
        public static final int LADDER = 65;
        public static final int PACKED_ICE = 174;
        public static final int POWDER_SNOW = 561;
        public static final int SCAFFOLDING = 420;
        public static final int SLIME_BLOCK = 165;
        public static final int SOUL_SAND = 88;
        public static final int STILL_WATER = 9;
        public static final int SWEET_BERRY_BUSH = 462;
        public static final int TWISTING_VINES = 542;
        public static final int VINE = 106;
        public static final int VINES = 106;
        public static final int WATER = 8;
        public static final int WEEPING_VINES = 486;
    }
    public static final class Effect {
        public static final int JUMP_BOOST = 8;
        public static final int LEVITATION = 24;
        public static final int SLOW_FALLING = 27;
        public static final int WEAVING = 33;
    }
    public static final class Enchantment {
        public static final int ID_SOUL_SPEED = 36;
        public static final int ID_SWIFT_SNEAK = 37;
        public static final int ID_TRIDENT_RIPTIDE = 30;
        public static final int ID_WATER_WALKER = 7;
        public static Integer getEnchantment(int id){return id;}
    }
    public static final class ItemID {
        public static final int LEATHER_BOOTS = 301;
        public static final int TRIDENT = 455;
    }
}
