package ac.navro.proxy.util;

import org.cloudburstmc.nbt.NbtMap;
import org.cloudburstmc.nbt.NbtUtils;
import org.junit.jupiter.api.Test;
import java.io.*;
import java.util.zip.GZIPInputStream;
import static org.junit.jupiter.api.Assertions.*;

class NbtNumberTest {
    @Test void gettersMatchNativeNumericCoercionIncludingFullWidthShortAndMalformedTagErrors() throws Exception {
        try(var in=new DataInputStream(new GZIPInputStream(getClass().getResourceAsStream("/native-nbt-numbers.bin.gz")))) {
            assertEquals(0x4E4E4231,in.readInt());int count=in.readInt();assertEquals(20,count);
            for(int i=0;i<count;i++) {
                byte[] bytes=in.readNBytes(in.readInt());NbtMap tag;
                try(var reader=NbtUtils.createReader(new ByteArrayInputStream(bytes))){tag=(NbtMap)reader.readTag();}
                if(in.readBoolean())assertEquals(in.readInt(),NbtNumber.getInt(tag,"value"),"int case="+i);
                else assertEquals(in.readUTF(),assertThrows(RuntimeException.class,()->NbtNumber.getInt(tag,"value")).getClass().getSimpleName());
                if(in.readBoolean())assertEquals(in.readInt(),NbtNumber.getShort(tag,"value"),"short case="+i);
                else assertEquals(in.readUTF(),assertThrows(RuntimeException.class,()->NbtNumber.getShort(tag,"value")).getClass().getSimpleName());
            }
            assertEquals(-1,in.read());
        }
    }
}
