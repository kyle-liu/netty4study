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
package io.netty.util;

import java.util.IdentityHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;

/**
 * Default {@link AttributeMap} implementation which use simple synchronization to keep the memory overhead
 * as low as possible.
 */
public class DefaultAttributeMap implements AttributeMap {

    /**
     * 基于反射的实用工具，可以对指定类的指定 volatile 字段进行原子更新。
     * 该类用于原子数据结构，该结构中同一节点的几个引用字段都独立受原子更新控制
     * AtomicReferenceFieldUpdater.newUpdater参数：
     * 第一个：属性所属类的class
     * 第二个:属性类的calss
     * 第三个:属性名
     */
    @SuppressWarnings("rawtypes")
    private static final AtomicReferenceFieldUpdater<DefaultAttributeMap, Map> updater =
            AtomicReferenceFieldUpdater.newUpdater(DefaultAttributeMap.class, Map.class, "map");

    // Initialize lazily to reduce memory consumption; updated by AtomicReferenceFieldUpdater above.
    @SuppressWarnings("UnusedDeclaration")
    private volatile Map<AttributeKey<?>, Attribute<?>> map;

    @Override
    public <T> Attribute<T> attr(AttributeKey<T> key) {
        Map<AttributeKey<?>, Attribute<?>> map = this.map;
        if (map == null) {
            // Not using ConcurrentHashMap due to high memory consumption.
            /**
             * IdentityHashMap:允许key值可以重复的map。在IdentityHashMap中，
             * 判断两个键值k1和 k2相等的条件是 k1 == k2 。
             * 在正常的Map 实现（如 HashMap）中，
             * 当且仅当满足下列条件时才认为两个键 k1 和 k2 相等(不仅比较引用还比较属性值)：
             * (k1==null ? k2==null : e1.equals(e2))。
             */
            map = new IdentityHashMap<AttributeKey<?>, Attribute<?>>(2);
            /**原子更新map的属性值：
             * 如果当前值 == 预期值，则以原子方式将此更新器管理的给定对象的字段设置为给定的更新值
             * 在这里：
             * 如果当前对象的成员属性map等于null，那么给这个成员属性this.map 赋为新创建的对象map
             * 否则给局部变量赋值map = this.map
             */
            if (!updater.compareAndSet(this, null, map)) {
                map = this.map;
            }
        }


        synchronized (map) {
            @SuppressWarnings("unchecked")
            Attribute<T> attr = (Attribute<T>) map.get(key);
            if (attr == null) {
                attr = new DefaultAttribute<T>(map, key);
                map.put(key, attr);
            }
            return attr;
        }
    }

    private static final class DefaultAttribute<T> extends AtomicReference<T> implements Attribute<T> {

        private static final long serialVersionUID = -2661411462200283011L;

        private final Map<AttributeKey<?>, Attribute<?>> map;
        private final AttributeKey<T> key;

        DefaultAttribute(Map<AttributeKey<?>, Attribute<?>> map, AttributeKey<T> key) {
            this.map = map;
            this.key = key;
        }

        @Override
        public AttributeKey<T> key() {
            return key;
        }

        @Override
        public T setIfAbsent(T value) {
            while (!compareAndSet(null, value)) {
                T old = get();
                if (old != null) {
                    return old;
                }
            }
            return null;
        }

        @Override
        public T getAndRemove() {
            T oldValue = getAndSet(null);
            remove0();
            return oldValue;
        }

        @Override
        public void remove() {
            set(null);
            remove0();
        }

        private void remove0() {
            synchronized (map) {
                map.remove(key);
            }
        }
    }
}
