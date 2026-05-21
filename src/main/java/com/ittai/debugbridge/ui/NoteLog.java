package com.ittai.debugbridge.ui;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

public final class NoteLog {

    public record Note(long seq, long timestampMs, String kind, String text) {}

    private static final int CAPACITY = 500;

    private final LinkedList<Note> notes = new LinkedList<>();
    private final AtomicLong seq = new AtomicLong();

    public synchronized Note add(String kind, String text) {
        Note n = new Note(seq.incrementAndGet(), System.currentTimeMillis(),
            kind == null || kind.isBlank() ? "thought" : kind, text);
        notes.addLast(n);
        while (notes.size() > CAPACITY) notes.removeFirst();
        return n;
    }

    public synchronized List<Note> snapshot(int limit) {
        int n = Math.min(limit, notes.size());
        List<Note> out = new ArrayList<>(n);
        for (int i = notes.size() - n; i < notes.size(); i++) {
            out.add(notes.get(i));
        }
        return out;
    }

    public synchronized int size() { return notes.size(); }

    public synchronized void clear() {
        notes.clear();
        seq.set(0);
    }
}
