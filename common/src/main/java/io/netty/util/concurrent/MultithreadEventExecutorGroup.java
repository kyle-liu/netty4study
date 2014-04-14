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

import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Set;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Abstract base class for {@link EventExecutorGroup} implementations that handles their tasks with multiple threads at
 * the same time.
 */

/**
 * 核心的多线程task处理执行引擎
 */
public abstract class MultithreadEventExecutorGroup extends AbstractEventExecutorGroup {

    private final EventExecutor[] children;  //event_loop数组，真正处理task的线程

    //主要用于辅助在从children数组中取出一个event_executor时计算children数组的索引，本质是一个轮询算法
    private final AtomicInteger childIndex = new AtomicInteger();
    private final AtomicInteger terminatedChildren = new AtomicInteger();  //TODO:还弄清楚这个成员变量的含义
    private final Promise<?> terminationFuture = new DefaultPromise(GlobalEventExecutor.INSTANCE);  //TODO:还弄清楚这个成员变量的含义

    /**
     * Create a new instance.
     *
     * @param nThreads          the number of threads that will be used by this instance.
     * @param threadFactory     the ThreadFactory to use, or {@code null} if the default should be used.
     * @param args              arguments which will passed to each {@link #newChild(ThreadFactory, Object...)} call
     */
    protected MultithreadEventExecutorGroup(int nThreads, ThreadFactory threadFactory, Object... args) {
        //参数检查
        if (nThreads <= 0) {
            throw new IllegalArgumentException(String.format("nThreads: %d (expected: > 0)", nThreads));
        }

        if (threadFactory == null) {
            threadFactory = newDefaultThreadFactory();
        }


        //初始化EventExecutor数组，每一个数组元素代表的是SingleThreadEventExecutor实例，数组长度为传入的参数
        children = new SingleThreadEventExecutor[nThreads];


        for (int i = 0; i < nThreads; i ++) {
            //success主要用来标示children的每一个元素即SingleThreadEventExecutor的子类实例是否创建成功
            boolean success = false;
            try {
                //newChild是一个抽象方法，交给子类实现，我们一般使用Nio，那么此处实现应该看类NioEventLoopGroup
                children[i] = newChild(threadFactory, args);
                //子线程全部初始化完成后，success标示为成功
                success = true;
            } catch (Exception e) {
                // TODO: Think about if this is a good exception type
                throw new IllegalStateException("failed to create a child event loop", e);
            } finally {
                //如果在初始化children数组的过程中出现任何异常，那么success还是等于false
                //初始化children数组的过程中有出现异常,则把创建好的线程
                if (!success) {
                    //把已经初始化好的线程shutdown
                    for (int j = 0; j < i; j ++) {
                        children[j].shutdownGracefully();
                    }


                    for (int j = 0; j < i; j ++) {
                        EventExecutor e = children[j];
                        try {
                            while (!e.isTerminated()) {
                                e.awaitTermination(Integer.MAX_VALUE, TimeUnit.SECONDS);
                            }
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                            break;
                        }
                    }
                }
            }
        }

        //todo：下面这两段还没完全看懂
        //创建一个event_loop终止时的一个监听器terminationListener
        final FutureListener<Object> terminationListener = new FutureListener<Object>() {
            @Override
            public void operationComplete(Future<Object> future) throws Exception {
                //每次终止时terminatedChildren进行+1，并对比终止的线程数与初始化时线程数的长度
                if (terminatedChildren.incrementAndGet() == children.length) {
                    //当终止的线程数与初始化时线程数的长度相时，调用DefaultPromise(GlobalEventExecutor.INSTANCE)的setSuccess(null)方法
                    //要弄清楚setSuccess(null)这个方法的含义
                    terminationFuture.setSuccess(null);
                }
            }
        };

        //将上面的terminationListener添加到children数组中的每一个event_loop执行器里面
        for (EventExecutor e: children) {
            e.terminationFuture().addListener(terminationListener);
        }
    }



    protected ThreadFactory newDefaultThreadFactory() {
        return new DefaultThreadFactory(getClass());
    }


    /**
     *重点方法，group中每调用一次next()方法就会从EventExecutor组children数组
     * 中挑选出一个EventExecutor来处理ServerSocket accpet的client socket,处理过程有：
     * 当ServerSocket accpet链接后，将产生的对应的Socket注册到next()方法取出的事件循环即EventExecutor的子类。
     * 取出的算法为：
     * 以轮询的方式Math.abs(childIndex.getAndIncrement() % children.length)算出children数组的一个索引
     */
    @Override
    public EventExecutor next() {
        return children[Math.abs(childIndex.getAndIncrement() % children.length)];
    }

    @Override
    public Iterator<EventExecutor> iterator() {
        return children().iterator();
    }

    /**
     * Return the number of {@link EventExecutor} this implementation uses. This number is the maps
     * 1:1 to the threads it use.
     */
    public final int executorCount() {
        return children.length;
    }

    /**
     * Return a safe-copy of all of the children of this group.
     */
    protected Set<EventExecutor> children() {
        Set<EventExecutor> children = Collections.newSetFromMap(new LinkedHashMap<EventExecutor, Boolean>());
        Collections.addAll(children, this.children);
        return children;
    }

    /**
     * Create a new EventExecutor which will later then accessible via the {@link #next()}  method. This method will be
     * called for each thread that will serve this {@link MultithreadEventExecutorGroup}.
     *
     */
    protected abstract EventExecutor newChild(
            ThreadFactory threadFactory, Object... args) throws Exception;

    @Override
    public Future<?> shutdownGracefully(long quietPeriod, long timeout, TimeUnit unit) {
        for (EventExecutor l: children) {
            l.shutdownGracefully(quietPeriod, timeout, unit);
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
        for (EventExecutor l: children) {
            l.shutdown();
        }
    }

    @Override
    public boolean isShuttingDown() {
        for (EventExecutor l: children) {
            if (!l.isShuttingDown()) {
                return false;
            }
        }
        return true;
    }

    @Override
    public boolean isShutdown() {
        for (EventExecutor l: children) {
            if (!l.isShutdown()) {
                return false;
            }
        }
        return true;
    }

    @Override
    public boolean isTerminated() {
        for (EventExecutor l: children) {
            if (!l.isTerminated()) {
                return false;
            }
        }
        return true;
    }

    @Override
    public boolean awaitTermination(long timeout, TimeUnit unit)
            throws InterruptedException {
        long deadline = System.nanoTime() + unit.toNanos(timeout);
        loop: for (EventExecutor l: children) {
            for (;;) {
                long timeLeft = deadline - System.nanoTime();
                if (timeLeft <= 0) {
                    break loop;
                }
                if (l.awaitTermination(timeLeft, TimeUnit.NANOSECONDS)) {
                    break;
                }
            }
        }
        return isTerminated();
    }


}


