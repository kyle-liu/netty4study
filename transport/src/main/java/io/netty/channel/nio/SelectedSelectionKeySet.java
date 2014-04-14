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

package io.netty.channel.nio;

import java.nio.channels.SelectionKey;
import java.util.AbstractSet;
import java.util.Iterator;

final class SelectedSelectionKeySet extends AbstractSet<SelectionKey> {

    private SelectionKey[] keysA;
    private int keysASize;
    private SelectionKey[] keysB;
    private int keysBSize;
    private boolean isA = true;

    SelectedSelectionKeySet() {
        //keysA初始化为一个1024的SelectionKey数组，并克隆给keysB
        keysA = new SelectionKey[1024];
        keysB = keysA.clone();
    }

    @Override
    public boolean add(SelectionKey o) {
        //参数检查
        if (o == null) {
            return false;
        }

        //判断当前使用的SelectionKey[]是否是keyA
        if (isA) {
            int size = keysASize;
            //将新增的SelectionKey元素o赋值给数组keysA指定下标元素
            keysA[size ++] = o;
            //给keysASize赋值为
            keysASize = size;
            //新增元素时，如果新增元素的个数等于keysA的长度，则扩容keysA数组
            if (size == keysA.length) {
                doubleCapacityA();
            }
        }
        //对keysB进行同样的操作
        else {
            int size = keysBSize;
            keysB[size ++] = o;
            keysBSize = size;
            if (size == keysB.length) {
                doubleCapacityB();
            }
        }

        return true;
    }


    private void doubleCapacityA() {
        SelectionKey[] newKeysA = new SelectionKey[keysA.length << 1];
        System.arraycopy(keysA, 0, newKeysA, 0, keysASize);
        keysA = newKeysA;
    }

    private void doubleCapacityB() {
        SelectionKey[] newKeysB = new SelectionKey[keysB.length << 1];
        System.arraycopy(keysB, 0, newKeysB, 0, keysBSize);
        keysB = newKeysB;
    }


    //todo: think about 如何优化的
    SelectionKey[] flip() {
        //如果当前使用的是keysA
        if (isA) {
            //先设置当前使用的isA为false
            isA = false;
            keysA[keysASize] = null;
            keysBSize = 0;
            return keysA;
        } else {
            isA = true;
            keysB[keysBSize] = null;
            keysASize = 0;
            return keysB;
        }
    }

    @Override
    public int size() {
        if (isA) {
            return keysASize;
        } else {
            return keysBSize;
        }
    }

    @Override
    public boolean remove(Object o) {
        return false;
    }

    @Override
    public boolean contains(Object o) {
        return false;
    }

    @Override
    public Iterator<SelectionKey> iterator() {
        throw new UnsupportedOperationException();
    }
}
