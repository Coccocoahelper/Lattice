package net.h2020.bots.net;

/**
 * FIFO of packets with a delivery deadline (System.nanoTime based). Deadlines are forced to be non-decreasing so the
 * stream keeps TCP ordering even with per-packet jitter. Allocation-free ring buffer; single-threaded.
 */
public final class LatencyQueue<T> {
    private Object[] items = new Object[32];
    private long[] due = new long[32];
    private int head, size;
    private long lastDue;

    public void add(T item, long dueNanos) {
        if (dueNanos < lastDue) dueNanos = lastDue;
        lastDue = dueNanos;
        if (size == items.length) grow();
        int idx = (head + size) & (items.length - 1);
        items[idx] = item;
        due[idx] = dueNanos;
        size++;
    }

    /** @return the next item whose deadline has passed, or null. */
    @SuppressWarnings("unchecked")
    public T pollDue(long now) {
        if (size == 0 || due[head] - now > 0) return null;
        T t = (T) items[head];
        items[head] = null;
        head = (head + 1) & (items.length - 1);
        size--;
        return t;
    }

    public int size() { return size; }

    public void clear() {
        for (int i = 0; i < items.length; i++) items[i] = null;
        head = 0;
        size = 0;
    }

    private void grow() {
        int n = items.length << 1;
        Object[] ni = new Object[n];
        long[] nd = new long[n];
        for (int i = 0; i < size; i++) {
            int idx = (head + i) & (items.length - 1);
            ni[i] = items[idx];
            nd[i] = due[idx];
        }
        items = ni;
        due = nd;
        head = 0;
    }
}
