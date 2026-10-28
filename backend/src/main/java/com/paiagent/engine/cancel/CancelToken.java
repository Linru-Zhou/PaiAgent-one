package com.paiagent.engine.cancel;

import java.util.HashSet;
import java.util.Set;

/**
 * 一次执行的取消标记，同时记录"此刻正在替这次执行干活的线程"，取消时去中断它们。
 *
 * <p>为什么要自己管线程：节点是在线程池里跑的，取消请求来自另一个 HTTP 线程，只有拿到干活的线程才能打断
 * 正在 {@code sleep}、等 HTTP 响应或轮询供应商的节点。{@link #enter()} / {@link #exit()} 夹住节点的执行区间，
 * {@link #cancel()} 在同一把锁里中断区间内的线程，所以<b>不可能在节点结束、线程回到池里之后才中断到别的任务</b>：
 * 要么在区间内被中断（{@link #exit()} 会清掉中断标志），要么压根不会被中断。
 */
public final class CancelToken {

    private volatile boolean cancelled;
    private final Set<Thread> working = new HashSet<>();
    private final Set<Thread> interrupted = new HashSet<>();

    public boolean isCancelled() {
        return cancelled;
    }

    /** 当前线程开始为这次执行干活。取消已经发生时，立刻给自己打上中断标志，让节点尽早退出。 */
    public synchronized void enter() {
        Thread self = Thread.currentThread();
        working.add(self);
        if (cancelled) {
            interrupted.add(self);
            self.interrupt();
        }
    }

    /** 当前线程不再为这次执行干活；如果它是被取消中断的，清掉中断标志，不让它带着标志回到线程池。 */
    public synchronized void exit() {
        Thread self = Thread.currentThread();
        working.remove(self);
        if (interrupted.remove(self)) {
            Thread.interrupted();
        }
    }

    /** 当前线程在这段区间里是否被取消中断过（节点因此失败时，据此判断是"被取消"而不是"自己出错"）。 */
    public synchronized boolean interruptedByCancel() {
        return interrupted.contains(Thread.currentThread());
    }

    /** @return 第一次取消返回 true，重复取消返回 false（仍会再次中断还在干活的线程） */
    public synchronized boolean cancel() {
        boolean first = !cancelled;
        cancelled = true;
        for (Thread thread : working) {
            interrupted.add(thread);
            thread.interrupt();
        }
        return first;
    }
}
