import brut.androlib.smali.SmaliBuilder;
import com.android.tools.smali.dexlib2.Opcodes;
import com.android.tools.smali.dexlib2.writer.builder.DexBuilder;
import com.android.tools.smali.dexlib2.writer.io.FileDataStore;
import java.io.File;
import java.util.*;
public class AssembleDex {
 static void list(File p,List<File> out){File[] f=p.listFiles();if(f==null)throw new IllegalArgumentException(p.toString());for(File x:f){if(x.isDirectory())list(x,out);else if(x.getName().endsWith(".smali"))out.add(x);}}
 public static void main(String[]a)throws Exception{List<File> files=new ArrayList<File>();list(new File(a[0]),files);Collections.sort(files);DexBuilder dex=new DexBuilder(new Opcodes(24));SmaliBuilder b=new SmaliBuilder(24);for(File f:files)if(!b.buildFile(f,dex))throw new IllegalStateException("Failed: "+f);FileDataStore store=new FileDataStore(new File(a[1]));dex.writeTo(store);System.out.println("Assembled "+files.size()+" classes");}
}
