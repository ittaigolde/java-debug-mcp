package com.ittai.debugbridge.eval;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class Lexer {

    private static final Map<String, Token.Kind> KEYWORDS = Map.of(
        "true", Token.Kind.TRUE,
        "false", Token.Kind.FALSE,
        "null", Token.Kind.NULL,
        "this", Token.Kind.THIS
    );

    private final String src;
    private int i = 0;

    public Lexer(String src) {
        this.src = src;
    }

    public static List<Token> tokenize(String src) {
        return new Lexer(src).all();
    }

    public List<Token> all() {
        List<Token> out = new ArrayList<>();
        while (true) {
            Token t = next();
            out.add(t);
            if (t.kind() == Token.Kind.EOF) return out;
        }
    }

    private Token next() {
        skipWhitespace();
        if (i >= src.length()) return new Token(Token.Kind.EOF, "", i);
        int start = i;
        char c = src.charAt(i);

        if (Character.isJavaIdentifierStart(c)) return readIdent(start);
        if (Character.isDigit(c)) return readNumber(start);
        if (c == '"') return readString(start);
        if (c == '\'') return readChar(start);
        return readOperator(start);
    }

    private Token readIdent(int start) {
        while (i < src.length() && Character.isJavaIdentifierPart(src.charAt(i))) i++;
        String text = src.substring(start, i);
        Token.Kind kind = KEYWORDS.getOrDefault(text, Token.Kind.IDENT);
        return new Token(kind, text, start);
    }

    private Token readNumber(int start) {
        boolean isDouble = false;
        boolean isFloat = false;
        boolean isLong = false;
        while (i < src.length() && Character.isDigit(src.charAt(i))) i++;
        if (i < src.length() && src.charAt(i) == '.') {
            isDouble = true;
            i++;
            while (i < src.length() && Character.isDigit(src.charAt(i))) i++;
        }
        if (i < src.length()) {
            char suf = src.charAt(i);
            if (suf == 'L' || suf == 'l') { isLong = true; i++; }
            else if (suf == 'f' || suf == 'F') { isFloat = true; isDouble = false; i++; }
            else if (suf == 'd' || suf == 'D') { isDouble = true; i++; }
        }
        String text = src.substring(start, i);
        Token.Kind kind = isFloat ? Token.Kind.FLOAT
            : isDouble ? Token.Kind.DOUBLE
            : isLong ? Token.Kind.LONG
            : Token.Kind.INT;
        return new Token(kind, text, start);
    }

    private Token readString(int start) {
        i++; // consume opening "
        StringBuilder sb = new StringBuilder();
        while (i < src.length() && src.charAt(i) != '"') {
            char c = src.charAt(i);
            if (c == '\\' && i + 1 < src.length()) {
                char esc = src.charAt(i + 1);
                sb.append(switch (esc) {
                    case 'n' -> '\n';
                    case 't' -> '\t';
                    case 'r' -> '\r';
                    case '\\' -> '\\';
                    case '"' -> '"';
                    case '\'' -> '\'';
                    case '0' -> '\0';
                    default -> esc;
                });
                i += 2;
            } else {
                sb.append(c);
                i++;
            }
        }
        if (i >= src.length()) throw lexerError(start, "unterminated string literal");
        i++; // consume closing "
        return new Token(Token.Kind.STRING, sb.toString(), start);
    }

    private Token readChar(int start) {
        i++;
        if (i >= src.length()) throw lexerError(start, "unterminated char literal");
        char value;
        if (src.charAt(i) == '\\' && i + 1 < src.length()) {
            char esc = src.charAt(i + 1);
            value = switch (esc) {
                case 'n' -> '\n';
                case 't' -> '\t';
                case 'r' -> '\r';
                case '\\' -> '\\';
                case '"' -> '"';
                case '\'' -> '\'';
                case '0' -> '\0';
                default -> esc;
            };
            i += 2;
        } else {
            value = src.charAt(i);
            i++;
        }
        if (i >= src.length() || src.charAt(i) != '\'') throw lexerError(start, "unterminated char literal");
        i++;
        return new Token(Token.Kind.CHAR, String.valueOf(value), start);
    }

    private Token readOperator(int start) {
        char c = src.charAt(i++);
        char next = i < src.length() ? src.charAt(i) : 0;
        switch (c) {
            case '.': return new Token(Token.Kind.DOT, ".", start);
            case ',': return new Token(Token.Kind.COMMA, ",", start);
            case '(': return new Token(Token.Kind.LPAREN, "(", start);
            case ')': return new Token(Token.Kind.RPAREN, ")", start);
            case '[': return new Token(Token.Kind.LBRACK, "[", start);
            case ']': return new Token(Token.Kind.RBRACK, "]", start);
            case '+': return new Token(Token.Kind.PLUS, "+", start);
            case '-': return new Token(Token.Kind.MINUS, "-", start);
            case '*': return new Token(Token.Kind.STAR, "*", start);
            case '/': return new Token(Token.Kind.SLASH, "/", start);
            case '%': return new Token(Token.Kind.PERCENT, "%", start);
            case '=':
                if (next == '=') { i++; return new Token(Token.Kind.EQ, "==", start); }
                throw lexerError(start, "unexpected '='; assignment is not supported");
            case '!':
                if (next == '=') { i++; return new Token(Token.Kind.NEQ, "!=", start); }
                return new Token(Token.Kind.NOT, "!", start);
            case '<':
                if (next == '=') { i++; return new Token(Token.Kind.LE, "<=", start); }
                return new Token(Token.Kind.LT, "<", start);
            case '>':
                if (next == '=') { i++; return new Token(Token.Kind.GE, ">=", start); }
                return new Token(Token.Kind.GT, ">", start);
            case '&':
                if (next == '&') { i++; return new Token(Token.Kind.AND, "&&", start); }
                throw lexerError(start, "single '&' not supported; use '&&'");
            case '|':
                if (next == '|') { i++; return new Token(Token.Kind.OR, "||", start); }
                throw lexerError(start, "single '|' not supported; use '||'");
            case '?':
                throw lexerError(start, "ternary '?' is not supported");
            default:
                throw lexerError(start, "unexpected character: " + c);
        }
    }

    private void skipWhitespace() {
        while (i < src.length() && Character.isWhitespace(src.charAt(i))) i++;
    }

    private static RuntimeException lexerError(int pos, String msg) {
        return new IllegalArgumentException("lex error at " + pos + ": " + msg);
    }
}
