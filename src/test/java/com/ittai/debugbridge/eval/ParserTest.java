package com.ittai.debugbridge.eval;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ParserTest {

    @Test
    void supportedExpressionsParse() {
        for (String expr : new String[]{
            "1 + 2",
            "a.b.c",
            "obj.foo()",
            "obj.foo(1, x, \"hello\")",
            "com.example.Foo.bar(1)",
            "this.counter + 1",
            "arr[3]",
            "arr[i + 1].name",
            "users.size() > 0",
            "x == 1 || y < 2 && !z",
            "(a + b) * c",
            "true",
            "null",
            "'a'",
            "1.5 * 2",
            "1L + 2",
            "1.5f + 0.5"
        }) {
            assertDoesNotThrow(() -> Parser.parse(expr), "should parse: " + expr);
        }
    }

    @Test
    void unsupportedSyntaxRejected() {
        for (String expr : new String[]{
            "x ? a : b",
            "x = 5",
            "x & y",
            "x | y",
            "new Foo()",
            "(int) x",
            "x instanceof Foo",
            "() -> x"
        }) {
            assertThrows(IllegalArgumentException.class, () -> Parser.parse(expr),
                "should reject: " + expr);
        }
    }
}
