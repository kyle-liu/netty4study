/*
 * Copyright 2013 The Netty Project
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

package io.netty.util;

import java.util.IdentityHashMap;
import java.util.Map;

/**
 * Light-weight object pool based on a thread-local stack.
 *
 * @param <T> the type of the pooled object
 */
public abstract class Recycler<T> {

    private final ThreadLocal<Stack<T>> threadLocal = new ThreadLocal<Stack<T>>() {
        @Override
        protected Stack<T> initialValue() {
            return new Stack<T>(Recycler.this, Thread.currentThread());
        }
    };

    public final T get() {
        Stack<T> stack = threadLocal.get();
        T o = stack.pop();
        if (o == null) {
            o = newObject(stack);
        }
        return o;
    }

    public final boolean recycle(T o, Handle handle) {
        @SuppressWarnings("unchecked")
        Stack<T> stack = (Stack<T>) handle;
        if (stack.parent != this) {
            return false;
        }

        if (Thread.currentThread() != stack.thread) {
            return false;
        }

        stack.push(o);
        return true;
    }

    protected abstract T newObject(Handle handle);

    public interface Handle {
    }

    static final class Stack<T> implements Handle {

        private static final int INITIAL_CAPACITY = 256;

        final Recycler<T> parent;
        final Thread thread;
        private T[] elements;  //存放元素的数组
        private int size;  //elements数组中已被赋值的元素中最后一个元素的索引+1即下一个待填元素的索引
        //该Map主要用来检查Stack中是否有重复元素
        private final Map<T, Boolean> map = new IdentityHashMap<T, Boolean>(INITIAL_CAPACITY);

        @SuppressWarnings({"unchecked", "SuspiciousArrayCast"})
        Stack(Recycler<T> parent, Thread thread) {
            this.parent = parent;
            this.thread = thread;
            //初始化数组
            elements = newArray(INITIAL_CAPACITY);
        }


        //弹出元素
        T pop() {
            int size = this.size;

            //检查elements中是否有元素，没有元素则直接返回null
            if (size == 0) {
                return null;
            }

            //索引-1
            size--;
            //从数组中取出元素
            T ret = elements[size];
            //将当前位置置为null
            elements[size] = null;
            //从map中删除该元素
            map.remove(ret);
            this.size = size;
            return ret;
        }


        //往Stack中压入元素
        void push(T o) {

            /**
             * 使用map.put方法，当put的key-value已存在时，put返回的值不为null
             * 这里检查添加已存在的值时，thow异常
             */
            if (map.put(o, Boolean.TRUE) != null) {
                throw new IllegalStateException("recycled already");
            }

            int size = this.size;
            /**
             * 检查elements数组容器中已被赋值元素的最大个数，如果发现elements容器已经被填满
             * 则把elements数组扩容，扩容的长度是：size*2
             * 然后再把旧的elements元素复制到新的数组中去
             */
            if (size == elements.length) {
                T[] newElements = newArray(size << 1);
                System.arraycopy(elements, 0, newElements, 0, size);
                elements = newElements;
            }

            //把新添加的元素赋值到elements数组
            elements[size] = o;

            //索引+1
            this.size = size + 1;
        }

        @SuppressWarnings({"unchecked", "SuspiciousArrayCast"})
        private static <T> T[] newArray(int length) {
            return (T[]) new Object[length];
        }
    }
}
