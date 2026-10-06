package android.util;
import java.util.ArrayList;
import java.util.List;
public final class Log {
    public static final List<String> messages=new ArrayList<>();
    public static int i(String tag,String value) { messages.add(value); return 0; }
    public static int w(String tag,String value) { messages.add(value); return 0; }
}
