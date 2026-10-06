package android.os;
import java.util.ArrayList;
import java.util.List;
public class Handler {
    public static final List<Runnable> queued=new ArrayList<>();
    public Handler(Looper looper) { if(looper==null) throw new AssertionError(); }
    public void removeCallbacks(Runnable task) { while(queued.remove(task)) {} }
    public boolean postDelayed(Runnable task,long millis) {
        if(millis!=300) throw new AssertionError("Unexpected retry window");
        queued.add(task); return true;
    }
    public static void drain() {
        List<Runnable> tasks=new ArrayList<>(queued); queued.clear();
        for(Runnable task:tasks) task.run();
    }
}
