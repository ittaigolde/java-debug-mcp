package com.ittai.debugbridge.eval;

public record Token(Kind kind, String text, int pos) {
    public enum Kind {
        IDENT, INT, LONG, DOUBLE, FLOAT, STRING, CHAR,
        TRUE, FALSE, NULL, THIS,
        DOT, COMMA, LPAREN, RPAREN, LBRACK, RBRACK,
        PLUS, MINUS, STAR, SLASH, PERCENT,
        EQ, NEQ, LT, GT, LE, GE,
        AND, OR, NOT,
        EOF
    }
}
