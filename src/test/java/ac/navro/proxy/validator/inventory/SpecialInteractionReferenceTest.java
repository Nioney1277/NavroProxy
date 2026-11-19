package ac.navro.proxy.validator.inventory;

import ac.navro.proxy.compensated.CompensatedInventory;
import ac.navro.proxy.compensated.world.base.CompensatedWorld;
import ac.navro.proxy.data.block.*;
import ac.navro.proxy.state.WirePlayerState;
import org.cloudburstmc.math.vector.Vector3i;
import org.cloudburstmc.nbt.*;
import org.cloudburstmc.protocol.bedrock.data.definitions.SimpleItemDefinition;
import org.cloudburstmc.protocol.bedrock.data.inventory.ItemData;
import org.junit.jupiter.api.Test;
import java.io.*;
import java.util.*;
import java.util.zip.GZIPInputStream;
import static org.junit.jupiter.api.Assertions.*;

class SpecialInteractionReferenceTest {
    static NbtMap tag(int kind){var t=NbtMap.builder();return switch(kind){
        case 0->null;case 1->t.build();case 2->t.putByte("hasBook",(byte)0).build();case 3->t.putByte("hasBook",(byte)1).build();case 4->t.putShort("hasBook",(short)256).build();case 5->t.putLong("hasBook",1L<<32).build();case 6->t.putFloat("hasBook",0.5f).build();case 7->t.putDouble("hasBook",Double.NaN).build();
        case 8->t.putString("hasBook","true").build();case 9->t.putCompound("hasBook",NbtMap.EMPTY).build();case 10->t.putList("hasBook",NbtType.STRING,List.of()).build();case 11->t.putCompound("book",NbtMap.EMPTY).build();case 12->t.putString("book","book").build();case 13->t.putInt("hasBook",0).putCompound("book",NbtMap.EMPTY).build();case 14->t.putString("hasBook","true").putCompound("book",NbtMap.EMPTY).build();case 15->t.putByte("hasBook",(byte)1).putString("book","not a compound").build();
        case 16->t.putString("vault_state","AcTiVe").build();case 17->t.putString("vault_state","inactive").putString("state","active").build();case 18->t.putString("vault_state","").putString("state","ACTIVE").build();case 19->t.putString("state","").build();case 20->t.putInt("vault_state",123).putCompound("state",NbtMap.EMPTY).build();case 21->t.putFloat("vault_state",Float.NaN).build();case 22->t.putCompound("vault_state",NbtMap.EMPTY).putString("state","active").build();case 23->t.putString("vault_state","").putCompound("state",NbtMap.EMPTY).build();case 24->t.putString("vault_state","active").putCompound("state",NbtMap.EMPTY).build();
        case 25->t.putInt("hasBook",65536).build();case 26->t.putLong("hasBook",Long.MIN_VALUE).build();case 27->t.putDouble("hasBook",Double.POSITIVE_INFINITY).build();case 28->t.putInt("vault_state",0).putString("state","active").build();case 29->t.putShort("state",(short)42).build();case 30->t.putByteArray("hasBook",new byte[]{1}).build();case 31->t.putByteArray("vault_state",new byte[]{1}).build();case 32->t.putByteArray("state",new byte[]{1}).build();case 33->t.putString("vault_state"," ACTIVE ").build();case 34->t.putInt("hasBook",0).putList("book",NbtType.STRING,List.of()).build();case 35->t.putCompound("book",NbtMap.builder().putInt("pages",5).build()).build();case 36->t.putString("hasBook","").build();default->throw new AssertionError();};}
    @Test void actualOriginalHelpersMatchGetterOrderNbtReadsResultsAndExceptionClasses()throws Exception {
        int accepted=0,rejected=0,errors=0,reads=0;
        try(var in=new DataInputStream(new GZIPInputStream(getClass().getResourceAsStream("/navro-special-interactions.bin.gz")))) {
            assertEquals(0x4e535031,in.readInt());int cases=in.readInt();assertEquals(16384,cases);
            for(int i=0;i<cases;i++) {
                int mode=in.readInt(),block=in.readInt(),meta=in.readInt(),kind=in.readInt();boolean failure=in.readBoolean(),nullHeld=in.readBoolean(),nullBlock=in.readBoolean(),nullPos=in.readBoolean();var pos=Vector3i.from(in.readInt(),in.readInt(),in.readInt());int rawFace=in.readInt();Integer face=rawFace<0?null:rawFace;float y=in.readFloat();int id=in.readInt(),damage=in.readInt(),count=in.readInt(),mapped=in.readInt();boolean empty=in.readBoolean();String name=in.readBoolean()?in.readUTF():null;int runtime=in.readInt();
                var held=ItemData.builder().definition(new SimpleItemDefinition(name,runtime,false)).damage(damage).count(count).build();var events=new ArrayList<String>();var tag=tag(kind);
                var w=new CompensatedWorld(){@Override public NbtMap getBlockEntityTag(int x,int y,int z){events.add("tag:"+x+","+y+","+z);if(failure)throw new IllegalStateException("block entity read failed");return tag;}};
                var c=new ItemTransactionValidator.UseItemOnContext(){
                    public int nativeItemId(ItemData item){assertSame(held,item);events.add("id");return id;}public int nativeItemDamage(ItemData item){assertSame(held,item);events.add("damage");return damage;}
                    public int nativeBlockId(ItemData item){assertSame(held,item);events.add("mapped");return mapped;}public boolean nativeIsNull(ItemData item){assertSame(held,item);events.add("empty");return empty;}
                    public boolean hasTag(ItemData item,String tagName){events.add("namespace");return ItemTransactionValidator.UseItemOnContext.super.hasTag(item,tagName);}
                };
                var p=new WirePlayerState();var v=new ItemTransactionValidator(p,new CompensatedInventory(p));var state=nullBlock?null:NetworkBlockState.legacy(BlockStateEncoding.fullId(block,meta));var position=nullPos?null:pos;boolean result=false;String error="";
                try{result=switch(mode){case 0->v.handleUseItemOn(w,position,state,nullHeld?null:held,face,c);case 1->v.lecternHasBook(w,position);case 2->v.isVaultActive(w,position,state);case 3->ItemTransactionValidator.isProperHit(state,face,y);default->throw new AssertionError();};}catch(RuntimeException e){error=e.getClass().getSimpleName();}
                String label="original special interaction case="+i+" mode="+mode;assertEquals(in.readBoolean(),result,label);assertEquals(in.readUTF(),error,label);int size=in.readInt();var expected=new ArrayList<String>();for(int e=0;e<size;e++)expected.add(in.readUTF());assertEquals(expected,events,label+" getter order");
                if(!error.isEmpty())errors++;else if(result)accepted++;else rejected++;reads+=events.stream().filter(e->e.startsWith("tag:")).count();assertEquals(count,held.getCount());assertEquals(damage,held.getDamage());assertEquals(name,held.getDefinition().getIdentifier());
            }
            assertEquals(-1,in.read());
        }
        assertTrue(accepted>1000);assertTrue(rejected>1000);assertTrue(errors>500);assertTrue(reads>5000);
    }
}
