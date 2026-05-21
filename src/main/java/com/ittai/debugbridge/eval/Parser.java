package com.ittai.debugbridge.eval;

import java.util.ArrayList;
import java.util.List;

public final class Parser {

    private final List<Token> tokens;
    private int p = 0;

    private Parser(List<Token> tokens) {
        this.tokens = tokens;
    }

    public static Expr parse(String src) {
        Parser parser = new Parser(Lexer.tokenize(src));
        Expr e = parser.expression();
        parser.expect(Token.Kind.EOF, "end of input");
        return e;
    }

    private Expr expression() { return or(); }

    private Expr or() {
        Expr e = and();
        while (peek().kind() == Token.Kind.OR) { advance(); e = new Expr.BinOp("||", e, and()); }
        return e;
    }

    private Expr and() {
        Expr e = equality();
        while (peek().kind() == Token.Kind.AND) { advance(); e = new Expr.BinOp("&&", e, equality()); }
        return e;
    }

    private Expr equality() {
        Expr e = relational();
        while (peek().kind() == Token.Kind.EQ || peek().kind() == Token.Kind.NEQ) {
            String op = advance().text();
            e = new Expr.BinOp(op, e, relational());
        }
        return e;
    }

    private Expr relational() {
        Expr e = additive();
        while (peek().kind() == Token.Kind.LT || peek().kind() == Token.Kind.GT
            || peek().kind() == Token.Kind.LE || peek().kind() == Token.Kind.GE) {
            String op = advance().text();
            e = new Expr.BinOp(op, e, additive());
        }
        return e;
    }

    private Expr additive() {
        Expr e = multiplicative();
        while (peek().kind() == Token.Kind.PLUS || peek().kind() == Token.Kind.MINUS) {
            String op = advance().text();
            e = new Expr.BinOp(op, e, multiplicative());
        }
        return e;
    }

    private Expr multiplicative() {
        Expr e = unary();
        while (peek().kind() == Token.Kind.STAR || peek().kind() == Token.Kind.SLASH
            || peek().kind() == Token.Kind.PERCENT) {
            String op = advance().text();
            e = new Expr.BinOp(op, e, unary());
        }
        return e;
    }

    private Expr unary() {
        Token t = peek();
        if (t.kind() == Token.Kind.NOT || t.kind() == Token.Kind.MINUS || t.kind() == Token.Kind.PLUS) {
            String op = advance().text();
            return new Expr.UnaryOp(op, unary());
        }
        return postfix();
    }

    private Expr postfix() {
        Expr e = primary();
        while (true) {
            Token t = peek();
            if (t.kind() == Token.Kind.DOT) {
                advance();
                Token name = expect(Token.Kind.IDENT, "identifier after '.'");
                if (peek().kind() == Token.Kind.LPAREN) {
                    advance();
                    List<Expr> args = argList();
                    expect(Token.Kind.RPAREN, "')'");
                    e = new Expr.Call(e, name.text(), args);
                } else {
                    e = new Expr.Member(e, name.text());
                }
            } else if (t.kind() == Token.Kind.LBRACK) {
                advance();
                Expr idx = expression();
                expect(Token.Kind.RBRACK, "']'");
                e = new Expr.Index(e, idx);
            } else {
                return e;
            }
        }
    }

    private Expr primary() {
        Token t = advance();
        return switch (t.kind()) {
            case INT -> new Expr.IntLit(Long.parseLong(t.text().replace("L", "").replace("l", "")));
            case LONG -> new Expr.LongLit(Long.parseLong(t.text().substring(0, t.text().length() - 1)));
            case DOUBLE -> new Expr.DoubleLit(Double.parseDouble(stripSuffix(t.text(), "dD")));
            case FLOAT -> new Expr.FloatLit(Float.parseFloat(stripSuffix(t.text(), "fF")));
            case STRING -> new Expr.StringLit(t.text());
            case CHAR -> new Expr.CharLit(t.text().charAt(0));
            case TRUE -> new Expr.BoolLit(true);
            case FALSE -> new Expr.BoolLit(false);
            case NULL -> new Expr.NullLit();
            case THIS -> new Expr.ThisExpr();
            case IDENT -> {
                if (peek().kind() == Token.Kind.LPAREN) {
                    advance();
                    List<Expr> args = argList();
                    expect(Token.Kind.RPAREN, "')'");
                    yield new Expr.Call(null, t.text(), args);
                }
                yield new Expr.Ident(t.text());
            }
            case LPAREN -> {
                Expr e = expression();
                expect(Token.Kind.RPAREN, "')'");
                yield e;
            }
            default -> throw error(t, "expected expression, got " + t.kind());
        };
    }

    private List<Expr> argList() {
        List<Expr> out = new ArrayList<>();
        if (peek().kind() == Token.Kind.RPAREN) return out;
        out.add(expression());
        while (peek().kind() == Token.Kind.COMMA) { advance(); out.add(expression()); }
        return out;
    }

    private static String stripSuffix(String s, String suffixes) {
        char last = s.charAt(s.length() - 1);
        if (suffixes.indexOf(last) >= 0) return s.substring(0, s.length() - 1);
        return s;
    }

    private Token peek() { return tokens.get(p); }
    private Token advance() { return tokens.get(p++); }

    private Token expect(Token.Kind kind, String what) {
        Token t = peek();
        if (t.kind() != kind) throw error(t, "expected " + what);
        return advance();
    }

    private static IllegalArgumentException error(Token t, String msg) {
        return new IllegalArgumentException("parse error at pos " + t.pos() + ": " + msg);
    }
}
