package com.ittai.debugbridge.eval;

import java.util.List;

public sealed interface Expr {
    record IntLit(long value) implements Expr {}
    record LongLit(long value) implements Expr {}
    record DoubleLit(double value) implements Expr {}
    record FloatLit(float value) implements Expr {}
    record StringLit(String value) implements Expr {}
    record CharLit(char value) implements Expr {}
    record BoolLit(boolean value) implements Expr {}
    record NullLit() implements Expr {}
    record ThisExpr() implements Expr {}
    record Ident(String name) implements Expr {}
    record Member(Expr target, String name) implements Expr {}
    record Call(Expr target, String name, List<Expr> args) implements Expr {}
    record Index(Expr target, Expr index) implements Expr {}
    record BinOp(String op, Expr left, Expr right) implements Expr {}
    record UnaryOp(String op, Expr operand) implements Expr {}
}
