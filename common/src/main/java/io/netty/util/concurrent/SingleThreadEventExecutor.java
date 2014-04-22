/*
 * Copyright 2012 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package io.netty.util.concurrent;

import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.PriorityQueue;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

/**
 * Abstract base class for {@link EventExecutor}'s that execute all its submitted tasks in a single thread.
 *
 */

/**
 * 其本质实际就是把一个Thread+task进行封装
 */
public abstract class SingleThreadEventExecutor extends AbstractEventExecutor {

    private static final InternalLogger logger =
            InternalLoggerFactory.getInstance(SingleThreadEventExecutor.class);

    /**
     * SingleThreadEventExecutor封装的状态：
     * ST_NOT_STARTED ：还没有开始
     * ST_STARTED：已经开始
     * ST_SHUTTING_DOWN：正在shutdown
     * ST_SHUTDOWN:已经shutdown
     * ST_TERMINATED:已停止
     */
    private static final int ST_NOT_STARTED = 1;
    private static final int ST_STARTED = 2;
    private static final int ST_SHUTTING_DOWN = 3;
    private static final int ST_SHUTDOWN = 4;
    private static final int ST_TERMINATED = 5;

    private static final Runnable WAKEUP_TASK = new Runnable() {
        @Override
        public void run() {
            // Do nothing.
        }
    };

    private final EventExecutorGroup parent;   //当前SingleThreadEventExecutor所属的group
    /**
     * 任务队列，在构造函数中调用newTaskQueue()方法进行初始化：
     * 在SingleThreadEventExecutor的默认初始化是LinkedBlockingQueue<Runnable>
     * 在NioEventLoop中默认初始化的是ConcurrentLinkedQueue<Runnable>
     */
    private final Queue<Runnable> taskQueue;
    final Queue<ScheduledFutureTask<?>> delayedTaskQueue = new PriorityQueue<ScheduledFutureTask<?>>();

    private final Thread thread;   //真正执行task的线程

    private final Semaphore threadLock = new Semaphore(0);
    private final Set<Runnable> shutdownHooks = new LinkedHashSet<Runnable>();
    /**
     * 标示在往任务队列中添加任务时，是否唤醒阻塞在select()上的线程
     * false: 表示唤醒
     * true: 标示不唤醒
     * 为什么需要这个变量？
     * 因为当通过eventloop的execute方法往任务队列添加任务时，就立马该eventloop的线程，该线程会执行
     * eventloop的run方法，该方法基本的逻辑是:
     * 任务队列如果有任务，就执行selectNow()，这是一个非阻塞方法，
     * 而任务队列如果没有任务，就执行被包装后的select()方法，该方法是一个阻塞方法
     * 所以这里在往eventloop中添加任务时，eventloop上的线程可能处于阻塞状态，那么被添加的任务可能会被
     * 延迟执行，所以这里才需要唤醒
     */
    private final boolean addTaskWakesUp;

    private long lastExecutionTime;
    private final Object stateLock = new Object();  //更改state时使用的锁对象
    private volatile int state = ST_NOT_STARTED;  //当前EventExecutor的状态，默认值为ST_NOT_STARTED(还未开始)
    private volatile long gracefulShutdownQuietPeriod;
    private volatile long gracefulShutdownTimeout;
    private long gracefulShutdownStartTime;

    private final Promise<?> terminationFuture = new DefaultPromise<Void>(GlobalEventExecutor.INSTANCE);

    /**
     * Create a new instance
     *
     * @param parent         the {@link EventExecutorGroup} which is the parent of this instance and belongs to it
     * @param threadFactory  the {@link ThreadFactory} which will be used for the used {@link Thread}
     * @param addTaskWakesUp {@code true} if and only if invocation of {@link #addTask(Runnable)} will wake up the
     *                       executor thread
     */
    protected SingleThreadEventExecutor(
            EventExecutorGroup parent, ThreadFactory threadFactory, boolean addTaskWakesUp) {

        if (threadFactory == null) {
            throw new NullPointerException("threadFactory");
        }

        this.parent = parent;
        this.addTaskWakesUp = addTaskWakesUp;


        //使用threadFactory创建线程
        thread = threadFactory.newThread(new Runnable() {
            @Override
            public void run() {
                boolean success = false;
                updateLastExecutionTime();
                try {
                    /**
                     * 线程封装的任务实际是SingleThreadEventExecutor的run()方法,
                     * 该方法在SingleThreadEventExecutor中是一个抽象方法，留给子类去实现
                     */
                    SingleThreadEventExecutor.this.run();
                    success = true;
                } catch (Throwable t) {
                    logger.warn("Unexpected exception from an event executor: ", t);
                } finally {

                    //todo:下面的代码还没有看懂
                    if (state < ST_SHUTTING_DOWN) {
                        state = ST_SHUTTING_DOWN;
                    }

                    // Check if confirmShutdown() was called at the end of the loop.
                    if (success && gracefulShutdownStartTime == 0) {
                        logger.error(
                                "Buggy " + EventExecutor.class.getSimpleName() + " implementation; " +
                                        SingleThreadEventExecutor.class.getSimpleName() + ".confirmShutdown() must be called " +
                                        "before run() implementation terminates.");
                    }

                    try {
                        // Run all remaining tasks and shutdown hooks.
                        for (; ; ) {
                            if (confirmShutdown()) {
                                break;
                            }
                        }
                    } finally {
                        try {
                            cleanup();
                        } finally {
                            synchronized (stateLock) {
                                state = ST_TERMINATED;
                            }
                            threadLock.release();
                            if (!taskQueue.isEmpty()) {
                                logger.warn(
                                        "An event executor terminated with " +
                                                "non-empty task queue (" + taskQueue.size() + ')');
                            }

                            terminationFuture.setSuccess(null);
                        }
                    }
                }
            }
        });

        //初始化taskQueue，该方法在SingleThreadEventExecutor的默认实现返回一个LinkedBlockingQueue<Runnable>
        //在NioEventLoop的实现返回的是ConcurrentLinkedQueue<Runnable>
        taskQueue = newTaskQueue();
    }

    /**
     * Create a new {@link Queue} which will holds the tasks to execute. This default implementation will return a
     * {@link LinkedBlockingQueue} but if your sub-class of {@link SingleThreadEventExecutor} will not do any blocking
     * calls on the this {@link Queue} it may make sense to {@code @Override} this and return some more performant
     * implementation that does not support blocking operations at all.
     */
    protected Queue<Runnable> newTaskQueue() {
        return new LinkedBlockingQueue<Runnable>();
    }

    @Override
    public EventExecutorGroup parent() {
        return parent;
    }

    /**
     * Interrupt the current running {@link Thread}.
     */
    protected void interruptThread() {
        thread.interrupt();
    }

    /**
     * @see {@link Queue#poll()}
     */
    protected Runnable pollTask() {
        assert inEventLoop();
        for (; ; ) {
            Runnable task = taskQueue.poll();
            if (task == WAKEUP_TASK) {
                continue;
            }
            return task;
        }
    }

    /**
     * Take the next {@link Runnable} from the task queue and so will block if no task is currently present.
     * <p>
     * Be aware that this method will throw an {@link UnsupportedOperationException} if the task queue, which was
     * created via {@link #newTaskQueue()}, does not implement {@link BlockingQueue}.
     * </p>
     *
     * @return {@code null} if the executor thread has been interrupted or waken up.
     */
    protected Runnable takeTask() {
        assert inEventLoop();
        if (!(taskQueue instanceof BlockingQueue)) {
            throw new UnsupportedOperationException();
        }

        BlockingQueue<Runnable> taskQueue = (BlockingQueue<Runnable>) this.taskQueue;
        for (; ; ) {
            ScheduledFutureTask<?> delayedTask = delayedTaskQueue.peek();
            if (delayedTask == null) {
                Runnable task = null;
                try {
                    task = taskQueue.take();
                    if (task == WAKEUP_TASK) {
                        task = null;
                    }
                } catch (InterruptedException e) {
                    // Ignore
                }
                return task;
            } else {
                long delayNanos = delayedTask.delayNanos();
                Runnable task = null;
                if (delayNanos > 0) {
                    try {
                        task = taskQueue.poll(delayNanos, TimeUnit.NANOSECONDS);
                    } catch (InterruptedException e) {
                        return null;
                    }
                }
                if (task == null) {
                    // We need to fetch the delayed tasks now as otherwise there may be a chance that
                    // delayed tasks are never executed if there is always one task in the taskQueue.
                    // This is for example true for the read task of OIO Transport
                    // See https://github.com/netty/netty/issues/1614
                    fetchFromDelayedQueue();
                    task = taskQueue.poll();
                }

                if (task != null) {
                    return task;
                }
            }
        }
    }

    private void fetchFromDelayedQueue() {
        long nanoTime = 0L;
        for (; ; ) {
            ScheduledFutureTask<?> delayedTask = delayedTaskQueue.peek();
            if (delayedTask == null) {
                break;
            }

            if (nanoTime == 0L) {
                nanoTime = ScheduledFutureTask.nanoTime();
            }

            if (delayedTask.deadlineNanos() <= nanoTime) {
                delayedTaskQueue.remove();
                taskQueue.add(delayedTask);
            } else {
                break;
            }
        }
    }

    /**
     * @see {@link Queue#peek()}
     */
    protected Runnable peekTask() {
        assert inEventLoop();
        return taskQueue.peek();
    }

    /**
     * @see {@link Queue#isEmpty()}
     */
    protected boolean hasTasks() {
        assert inEventLoop();
        return !taskQueue.isEmpty();
    }

    /**
     * Return the number of tasks that are pending for processing.
     * <p/>
     * <strong>Be aware that this operation may be expensive as it depends on the internal implementation of the
     * SingleThreadEventExecutor. So use it was care!</strong>
     */
    public final int pendingTasks() {
        return taskQueue.size();
    }

    /**
     * Add a task to the task queue, or throws a {@link RejectedExecutionException} if this instance was shutdown
     * before.
     */
    //添加任务到任务队列
    protected void addTask(Runnable task) {
        //参数检查
        if (task == null) {
            throw new NullPointerException("task");
        }
        //判断
        if (isShutdown()) {
            reject();
        }
        taskQueue.add(task);
    }

    /**
     * @see {@link Queue#remove(Object)}
     */
    //从任务队列中删除给定任务
    protected boolean removeTask(Runnable task) {
        if (task == null) {
            throw new NullPointerException("task");
        }
        return taskQueue.remove(task);
    }

    /**
     * Poll all tasks from the task queue and run them via {@link Runnable#run()} method.
     *
     * @return {@code true} if and only if at least one task was run
     */
    protected boolean runAllTasks() {
        fetchFromDelayedQueue();
        Runnable task = pollTask();
        if (task == null) {
            return false;
        }

        for (; ; ) {
            try {
                task.run();
            } catch (Throwable t) {
                logger.warn("A task raised an exception.", t);
            }

            task = pollTask();
            if (task == null) {
                lastExecutionTime = ScheduledFutureTask.nanoTime();
                return true;
            }
        }
    }

    /**
     * Poll all tasks from the task queue and run them via {@link Runnable#run()} method.  This method stops running
     * the tasks in the task queue and returns if it ran longer than {@code timeoutNanos}.
     */
    protected boolean runAllTasks(long timeoutNanos) {
        fetchFromDelayedQueue();
        Runnable task = pollTask();
        if (task == null) {
            return false;
        }

        //
        final long deadline = ScheduledFutureTask.nanoTime() + timeoutNanos;
        long runTasks = 0;
        long lastExecutionTime;
        for (; ; ) {
            try {
                task.run();
            } catch (Throwable t) {
                logger.warn("A task raised an exception.", t);
            }

            runTasks++;

            // Check timeout every 64 tasks because nanoTime() is relatively expensive.
            // XXX: Hard-coded value - will make it configurable if it is really a problem.
            if ((runTasks & 0x3F) == 0) {
                lastExecutionTime = ScheduledFutureTask.nanoTime();
                if (lastExecutionTime >= deadline) {
                    break;
                }
            }

            task = pollTask();
            if (task == null) {
                lastExecutionTime = ScheduledFutureTask.nanoTime();
                break;
            }
        }

        this.lastExecutionTime = lastExecutionTime;
        return true;
    }

    /**
     * Returns the amount of time left until the scheduled task with the closest dead line is executed.
     */
    protected long delayNanos(long currentTimeNanos) {
        ScheduledFutureTask<?> delayedTask = delayedTaskQueue.peek();
        if (delayedTask == null) {
            return SCHEDULE_PURGE_INTERVAL;
        }

        return delayedTask.delayNanos(currentTimeNanos);
    }

    /**
     * Updates the internal timestamp that tells when a submitted task was executed most recently.
     * {@link #runAllTasks()} and {@link #runAllTasks(long)} updates this timestamp automatically, and thus there's
     * usually no need to call this method.  However, if you take the tasks manually using {@link #takeTask()} or
     * {@link #pollTask()}, you have to call this method at the end of task execution loop for accurate quiet period
     * checks.
     */
    protected void updateLastExecutionTime() {
        lastExecutionTime = ScheduledFutureTask.nanoTime();
    }

    /**
     *
     */
    protected abstract void run();

    /**
     * Do nothing, sub-classes may override
     */
    protected void cleanup() {
        // NOOP
    }

    protected void wakeup(boolean inEventLoop) {
        if (!inEventLoop || state == ST_SHUTTING_DOWN) {
            taskQueue.add(WAKEUP_TASK);
        }
    }


    //检查当前runtime的线程对象，是否是eventloop
    @Override
    public boolean inEventLoop(Thread thread) {
        return thread == this.thread;
    }

    /**
     * Add a {@link Runnable} which will be executed on shutdown of this instance
     */
    public void addShutdownHook(final Runnable task) {
        if (inEventLoop()) {
            shutdownHooks.add(task);
        } else {
            execute(new Runnable() {
                @Override
                public void run() {
                    shutdownHooks.add(task);
                }
            });
        }
    }

    /**
     * Remove a previous added {@link Runnable} as a shutdown hook
     */
    public void removeShutdownHook(final Runnable task) {
        if (inEventLoop()) {
            shutdownHooks.remove(task);
        } else {
            execute(new Runnable() {
                @Override
                public void run() {
                    shutdownHooks.remove(task);
                }
            });
        }
    }

    private boolean runShutdownHooks() {
        boolean ran = false;
        // Note shutdown hooks can add / remove shutdown hooks.
        while (!shutdownHooks.isEmpty()) {
            List<Runnable> copy = new ArrayList<Runnable>(shutdownHooks);
            shutdownHooks.clear();
            for (Runnable task : copy) {
                try {
                    task.run();
                } catch (Throwable t) {
                    logger.warn("Shutdown hook raised an exception.", t);
                } finally {
                    ran = true;
                }
            }
        }

        if (ran) {
            lastExecutionTime = ScheduledFutureTask.nanoTime();
        }

        return ran;
    }

    @Override
    public Future<?> shutdownGracefully(long quietPeriod, long timeout, TimeUnit unit) {
        if (quietPeriod < 0) {
            throw new IllegalArgumentException("quietPeriod: " + quietPeriod + " (expected >= 0)");
        }
        if (timeout < quietPeriod) {
            throw new IllegalArgumentException(
                    "timeout: " + timeout + " (expected >= quietPeriod (" + quietPeriod + "))");
        }
        if (unit == null) {
            throw new NullPointerException("unit");
        }

        if (isShuttingDown()) {
            return terminationFuture();
        }

        boolean inEventLoop = inEventLoop();
        boolean wakeup = true;

        synchronized (stateLock) {
            if (isShuttingDown()) {
                return terminationFuture();
            }

            gracefulShutdownQuietPeriod = unit.toNanos(quietPeriod);
            gracefulShutdownTimeout = unit.toNanos(timeout);

            if (inEventLoop) {
                assert state == ST_STARTED;
                state = ST_SHUTTING_DOWN;
            } else {
                switch (state) {
                    case ST_NOT_STARTED:
                        state = ST_SHUTTING_DOWN;
                        thread.start();
                        break;
                    case ST_STARTED:
                        state = ST_SHUTTING_DOWN;
                        break;
                    default:
                        wakeup = false;
                }
            }
        }

        if (wakeup) {
            wakeup(inEventLoop);
        }

        return terminationFuture();
    }

    @Override
    public Future<?> terminationFuture() {
        return terminationFuture;
    }

    @Override
    @Deprecated
    public void shutdown() {
        if (isShutdown()) {
            return;
        }

        boolean inEventLoop = inEventLoop();
        boolean wakeup = true;

        synchronized (stateLock) {
            if (isShutdown()) {
                return;
            }

            if (inEventLoop) {
                assert state == ST_STARTED || state == ST_SHUTTING_DOWN;
                state = ST_SHUTDOWN;
            } else {
                switch (state) {
                    case ST_NOT_STARTED:
                        state = ST_SHUTDOWN;
                        thread.start();
                        break;
                    case ST_STARTED:
                    case ST_SHUTTING_DOWN:
                        state = ST_SHUTDOWN;
                        break;
                    default:
                        wakeup = false;
                }
            }
        }

        if (wakeup) {
            wakeup(inEventLoop);
        }
    }

    @Override
    public boolean isShuttingDown() {
        return state >= ST_SHUTTING_DOWN;
    }

    @Override
    public boolean isShutdown() {
        return state >= ST_SHUTDOWN;
    }

    @Override
    public boolean isTerminated() {
        return state == ST_TERMINATED;
    }

    /**
     * Confirm that the shutdown if the instance should be done now!
     */
    protected boolean confirmShutdown() {
        if (!isShuttingDown()) {
            return false;
        }

        if (!inEventLoop()) {
            throw new IllegalStateException("must be invoked from an event loop");
        }

        cancelDelayedTasks();

        if (gracefulShutdownStartTime == 0) {
            gracefulShutdownStartTime = ScheduledFutureTask.nanoTime();
        }

        if (runAllTasks() || runShutdownHooks()) {
            if (isShutdown()) {
                // Executor shut down - no new tasks anymore.
                return true;
            }

            // There were tasks in the queue. Wait a little bit more until no tasks are queued for the quiet period.
            wakeup(true);
            return false;
        }

        final long nanoTime = ScheduledFutureTask.nanoTime();

        if (isShutdown() || nanoTime - gracefulShutdownStartTime > gracefulShutdownTimeout) {
            return true;
        }

        if (nanoTime - lastExecutionTime <= gracefulShutdownQuietPeriod) {
            // Check if any tasks were added to the queue every 100ms.
            // TODO: Change the behavior of takeTask() so that it returns on timeout.
            wakeup(true);
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                // Ignore
            }

            return false;
        }

        // No tasks were added for last quiet period - hopefully safe to shut down.
        // (Hopefully because we really cannot make a guarantee that there will be no execute() calls by a user.)
        return true;
    }

    private void cancelDelayedTasks() {
        if (delayedTaskQueue.isEmpty()) {
            return;
        }

        final ScheduledFutureTask<?>[] delayedTasks =
                delayedTaskQueue.toArray(new ScheduledFutureTask<?>[delayedTaskQueue.size()]);

        for (ScheduledFutureTask<?> task : delayedTasks) {
            task.cancel(false);
        }

        delayedTaskQueue.clear();
    }

    @Override
    public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
        if (unit == null) {
            throw new NullPointerException("unit");
        }

        if (inEventLoop()) {
            throw new IllegalStateException("cannot await termination of the current thread");
        }

        if (threadLock.tryAcquire(timeout, unit)) {
            threadLock.release();
        }

        return isTerminated();
    }


    //执行task的方法
    @Override
    public void execute(Runnable task) {
        //检查task参数是否为null，如果为null，则抛出异常
        if (task == null) {
            throw new NullPointerException("task");
        }

        /**
         * 检查当前runtime的线程对象是否是group从child数组通过next()方法取出的eventloop即SingleThreadEventExecutor封装的thread对象
         * todo:这样做的目的：是为了保证netty中的任务都是交给netty创建的event_loop来执行
         */
        boolean inEventLoop = inEventLoop();
        if (inEventLoop) {
            //如果当前runtime的线程对象是否是group从child数组通过next()方法取出的eventloop，那么直接把该任务放入任务队列
            addTask(task);
        } else {
            //否则，启动当前这个eventloop，并把这个任务放入任务队列
            //启动线程
            startThread();
            //添加参数中的任务到任务队列
            addTask(task);
            /**
             * 1.判断当前线程是否已经停止
             * 2.如果已经停止则从任务队列中删除该任务
             * 如果线程已经停止并且删除任务成功，则抛出异常：
             *  throw new RejectedExecutionException("event executor terminated");
             */
            if (isShutdown() && removeTask(task)) {
                reject();
            }
        }

        /**
         * 标示在往任务队列中添加任务时，是否唤醒阻塞在select()上的线程
         * false: 表示唤醒
         * true: 标示不唤醒
         * 为什么需要这个变量？
         * 因为当通过eventloop的execute方法往任务队列添加任务时，就立马该eventloop的线程，该线程会执行
         * eventloop的run方法，该方法基本的逻辑是:
         * 任务队列如果有任务，就执行selectNow()，这是一个非阻塞方法，
         * 而任务队列如果没有任务，就执行被包装后的select()方法，该方法是一个阻塞方法
         * 所以这里在往eventloop中添加任务时，eventloop上的线程可能处于阻塞状态，那么被添加的任务可能会被
         * 延迟执行，所以这里才需要唤醒
         */
        if (!addTaskWakesUp) {
            wakeup(inEventLoop);
        }
    }

    protected static void reject() {
        throw new RejectedExecutionException("event executor terminated");
    }

    // ScheduledExecutorService implementation

    private static final long SCHEDULE_PURGE_INTERVAL = TimeUnit.SECONDS.toNanos(1);

    @Override
    public ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) {
        if (command == null) {
            throw new NullPointerException("command");
        }
        if (unit == null) {
            throw new NullPointerException("unit");
        }
        if (delay < 0) {
            throw new IllegalArgumentException(
                    String.format("delay: %d (expected: >= 0)", delay));
        }
        return schedule(new ScheduledFutureTask<Void>(
                this, delayedTaskQueue, command, null, ScheduledFutureTask.deadlineNanos(unit.toNanos(delay))));
    }

    @Override
    public <V> ScheduledFuture<V> schedule(Callable<V> callable, long delay, TimeUnit unit) {
        if (callable == null) {
            throw new NullPointerException("callable");
        }
        if (unit == null) {
            throw new NullPointerException("unit");
        }
        if (delay < 0) {
            throw new IllegalArgumentException(
                    String.format("delay: %d (expected: >= 0)", delay));
        }
        return schedule(new ScheduledFutureTask<V>(
                this, delayedTaskQueue, callable, ScheduledFutureTask.deadlineNanos(unit.toNanos(delay))));
    }

    @Override
    public ScheduledFuture<?> scheduleAtFixedRate(Runnable command, long initialDelay, long period, TimeUnit unit) {
        if (command == null) {
            throw new NullPointerException("command");
        }
        if (unit == null) {
            throw new NullPointerException("unit");
        }
        if (initialDelay < 0) {
            throw new IllegalArgumentException(
                    String.format("initialDelay: %d (expected: >= 0)", initialDelay));
        }
        if (period <= 0) {
            throw new IllegalArgumentException(
                    String.format("period: %d (expected: > 0)", period));
        }

        return schedule(new ScheduledFutureTask<Void>(
                this, delayedTaskQueue, Executors.<Void>callable(command, null),
                ScheduledFutureTask.deadlineNanos(unit.toNanos(initialDelay)), unit.toNanos(period)));
    }

    @Override
    public ScheduledFuture<?> scheduleWithFixedDelay(Runnable command, long initialDelay, long delay, TimeUnit unit) {
        if (command == null) {
            throw new NullPointerException("command");
        }
        if (unit == null) {
            throw new NullPointerException("unit");
        }
        if (initialDelay < 0) {
            throw new IllegalArgumentException(
                    String.format("initialDelay: %d (expected: >= 0)", initialDelay));
        }
        if (delay <= 0) {
            throw new IllegalArgumentException(
                    String.format("delay: %d (expected: > 0)", delay));
        }

        return schedule(new ScheduledFutureTask<Void>(
                this, delayedTaskQueue, Executors.<Void>callable(command, null),
                ScheduledFutureTask.deadlineNanos(unit.toNanos(initialDelay)), -unit.toNanos(delay)));
    }

    private <V> ScheduledFuture<V> schedule(final ScheduledFutureTask<V> task) {
        if (task == null) {
            throw new NullPointerException("task");
        }

        if (inEventLoop()) {
            delayedTaskQueue.add(task);
        } else {
            execute(new Runnable() {
                @Override
                public void run() {
                    delayedTaskQueue.add(task);
                }
            });
        }

        return task;
    }


    //启动线程
    private void startThread() {
        //先给stateLock加锁
        synchronized (stateLock) {
            if (state == ST_NOT_STARTED) {
                //将当前状态更改为ST_STARTED(已开始)
                state = ST_STARTED;

                //todo:这段代码还没有看懂
                delayedTaskQueue.add(new ScheduledFutureTask<Void>(
                        this, delayedTaskQueue, Executors.<Void>callable(new PurgeTask(), null),
                        ScheduledFutureTask.deadlineNanos(SCHEDULE_PURGE_INTERVAL), -SCHEDULE_PURGE_INTERVAL));
                thread.start();
            }
        }
    }

    private final class PurgeTask implements Runnable {
        @Override
        public void run() {
            Iterator<ScheduledFutureTask<?>> i = delayedTaskQueue.iterator();
            while (i.hasNext()) {
                ScheduledFutureTask<?> task = i.next();
                if (task.isCancelled()) {
                    i.remove();
                }
            }
        }
    }
}
