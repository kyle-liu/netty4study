package io.netty.example.planNio;

import java.nio.channels.SelectionKey;

/**
 * User: canhun@taobao.com
 * Date: 14-4-8
 * Time: PM5:47
 */
public class SelectionKeyTest {

    public static void main(String[] args) {
        int interestSet = SelectionKey.OP_READ | SelectionKey.OP_WRITE;
        System.out.println("SelectionKey.OP_READ: " +SelectionKey.OP_READ);
        System.out.println("SelectionKey.OP_WRITE: " +SelectionKey.OP_WRITE);
        System.out.println("SelectionKey.OP_CONNECT: " +SelectionKey.OP_CONNECT);

        boolean isRead = (interestSet & SelectionKey.OP_READ) == SelectionKey.OP_READ;
        System.out.println(interestSet);



    }
}
