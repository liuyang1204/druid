package liuyang.druid;

import static org.junit.Assert.assertEquals;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.util.Map;

import org.junit.Test;

public class DruidFunctionTest extends DruidTestBase {

    @Test
    public void testFunctionDef() throws IOException {
        Map<String, Object> result = runner.run("def addOne(target) {"
                + " return target + 1;"
                + " } "
                + " def subtractTwo(target) {"
                + " return target - 2;"
                + " }"
                + " var a; "
                + " a = 1;"
                + " a = addOne(a); "
                + " a = addOne(a); "
                + " a = subtractTwo(a + 3);");
        assertEquals(new Integer(4), result.get("a"));
    }

    @Test
    public void testFunctionWithExtend() throws IOException {
        Map<String, Object> result = runner.run("def addOne(target) {"
                + " return target + 1;"
                + " } "
                + " def subtractTwo(target) {"
                + " return target - 2;"
                + " }"
                + " var a, b; "
                + " a = 1;"
                + " b <- subtractTwo(addOne(a)*2);"
                + " a = addOne(a); "
                + " a = addOne(a); "
                + " a = subtractTwo(a + 3);");
        assertEquals(new Integer(4), result.get("a"));
        assertEquals(new Integer(8), result.get("b"));
    }

    @Test
    public void testBuildInFunction() throws IOException {
        ByteArrayOutputStream outContent = new ByteArrayOutputStream();
        System.setOut(new PrintStream(outContent));
        try {
            Map<String, Object> result = runner.run("def addOne(target) {"
                    + " var a, b, c; "
                    + " a = 1;"
                    + " print(target + 1);"
                    + " return target + 1;"
                    + " } "
                    + " def subtractTwo(target) {"
                    + " return target - 2;"
                    + " }"
                    + " var a, b; "
                    + " a = 1;"
                    + " b <- subtractTwo(addOne(a)*2);"
                    + " a = addOne(a); "
                    + " a = addOne(a); "
                    + " a = subtractTwo(a + 3);"
                    + " print('abc' + 'def');");
            assertEquals("223345abcdef", outContent.toString());
            assertEquals(new Integer(4), result.get("a"));
            assertEquals(new Integer(8), result.get("b"));
        } finally {
            System.setOut(null);
        }
    }
}
