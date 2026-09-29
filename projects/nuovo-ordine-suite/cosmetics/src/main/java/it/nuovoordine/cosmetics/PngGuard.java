package it.nuovoordine.cosmetics;
import java.io.*;
import java.nio.ByteBuffer;
import javax.imageio.ImageIO;
public final class PngGuard {
    public static byte[] validate(byte[] data,boolean skin)throws IOException{
        if(data.length==0)return data;
        if(data.length>32768||data.length<33)throw new IOException("PNG size");
        ByteBuffer b=ByteBuffer.wrap(data);
        if(b.getLong()!=0x89504e470d0a1a0aL||b.getInt()!=13||b.getInt()!=0x49484452)throw new IOException("PNG signature");
        int w=b.getInt(),h=b.getInt();if(w!=64||h!=(skin?64:32))throw new IOException("PNG dimensions");
        var image=ImageIO.read(new ByteArrayInputStream(data));if(image==null||image.getWidth()!=w||image.getHeight()!=h)throw new IOException("Invalid PNG");
        var out=new ByteArrayOutputStream();ImageIO.write(image,"PNG",out);if(out.size()>32768)throw new IOException("PNG size");return out.toByteArray();
    }
}
