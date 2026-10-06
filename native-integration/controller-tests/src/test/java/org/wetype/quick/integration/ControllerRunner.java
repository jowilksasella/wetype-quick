package org.wetype.quick.integration;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Comparator;
import org.junit.Test;
import org.junit.runner.*;
import org.junit.runner.notification.*;
public final class ControllerRunner {
    public static void main(String[] args) throws Exception {
        JUnitCore core = new JUnitCore();
        core.addListener(new RunListener() {
            public void testStarted(Description d) { System.out.println("START " + d.getDisplayName()); }
            public void testFailure(Failure f) { System.out.println("FAIL " + f.getDescription()); System.out.println(f.getTrace()); }
            public void testFinished(Description d) { System.out.println("END " + d.getDisplayName()); }
        });
        int count = 0, failures = 0, ignored = 0;
        Method[] methods = ControllerTest.class.getDeclaredMethods();
        Arrays.sort(methods, new Comparator<Method>() { public int compare(Method a, Method b) { return a.getName().compareTo(b.getName()); }});
        for (Method m : methods) if (m.getAnnotation(Test.class) != null && (args.length == 0 || m.getName().equals(args[0]))) {
            Result r = core.run(Request.method(ControllerTest.class, m.getName()));
            count += r.getRunCount(); failures += r.getFailureCount(); ignored += r.getIgnoreCount();
        }
        System.out.println("RESULT tests="+count+" failures="+failures+" ignored="+ignored);
        System.exit(count > 0 && failures == 0 ? 0 : 1);
    }
}
