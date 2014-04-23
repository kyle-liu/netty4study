/**
 * User: canhun@taobao.com
 * Date: 14-4-23
 * Time: PM1:55
 */

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.Iterator;
import java.util.Set;

/**
 * User: kyle
 * Date: 14-1-3
 * Time: PM1:11
 * 该demo主要用于我学习java 原生提供的nio api.通过一个基本的小demo来理解nio api的基本功能，从而加深netty对nio api的抽象和封装
 */
public class PlainNioEchoServer {

    public void server(int port) throws IOException {
        System.out.println("Listening for connections on port :" + port);

        //1.创建服务端通道对象
        ServerSocketChannel serverSocketChannel = ServerSocketChannel.open();
        //2.创建服务端网络通信对象
        ServerSocket ss = serverSocketChannel.socket();

        //3.创建并绑定端口
        InetSocketAddress address = new InetSocketAddress(port);
        ss.bind(address);

        serverSocketChannel.configureBlocking(false);

        //4.构建选择器
        Selector selector = Selector.open();

        /**
         *5.在Selector上注册服务端通道对象,对于serverSocketChannel来说，因为它主要是监听端口，
         *  等待client来连接，所以只会产生OP_ACCEPT事件
         *
         **/
        SelectionKey selectionKey = serverSocketChannel.register(selector, SelectionKey.OP_ACCEPT);

        while (true) {
            try {
                /**
                 * 6.Selector阻塞等待监听serverSocketChannel在第5步上注册的事件,在没有执行下面代码之前，
                 *  现在这个selector只监控OP_ACCPET事件
                 */
                selector.select();

            } catch (IOException ex) {
                ex.printStackTrace();
                break;
            }

            //7.当有事件发生时，从selector中找到selectedKeys
            Set readyKeys = selector.selectedKeys();
            Iterator iterator = readyKeys.iterator();
            //8.处理selector收集到的事件selectedKey集合
            while (iterator.hasNext()) {
                SelectionKey key = (SelectionKey) iterator.next();
                iterator.remove();
                try {
                    //是否是监听到的是OP_ACCEPT事件
                    if (key.isAcceptable()) {

                        /**
                         * 8.1
                         * 得到当前发生事件的Channel对象，因为只有ServerSocketChannel在selector中注册了OP_ACCEPT事件
                         * 所以这里key对应的channel应该是ServerSocketChannel类型的
                         */
                        ServerSocketChannel server = (ServerSocketChannel) key.channel();
                        //8.2 接受客户端的连接请求，并产生对应的客户端SocketChannel对象
                        SocketChannel client = server.accept();
                        System.out.println("Accepted connection from " + client);
                        client.configureBlocking(false);
                        /**
                         * 8.3
                         * 在Selector上注册client SocketChannel感兴趣的读和写事件
                         * 因为SocketChannel会向client read和write消息，所以这里会向selector注册OP_WRITE和OP_READ事件
                         * PS:Server监听OP_ACCEPT事件的selector和Server  accpet后产生的SocketChannel监听OP_WRITE、OP_READ
                         * 事件的selector可以是相同的selector(本demo)，也可以是不同的selector(netty)
                         */
                        client.register(selector,
                                SelectionKey.OP_WRITE | SelectionKey.OP_READ, ByteBuffer.allocate(100));

                    }
                    //是否是监听到的OP_READ事件
                    if (key.isReadable()) {
                        SocketChannel client = (SocketChannel) key.channel();
                        ByteBuffer output = (ByteBuffer) key.attachment();
                        client.read(output);

                    }
                    //是否是监听到的OP_WRITE事件
                    if (key.isWritable()) {
                        SocketChannel client = (SocketChannel) key.channel();
                        ByteBuffer output = (ByteBuffer) key.attachment();

                        output.flip();
                        client.write(output);
                        output.compact();
                    }


                } catch (IOException ex) {
                    key.cancel();
                    try {
                        key.channel().close();
                    } catch (IOException cex) {

                    }

                }
            }

        }

    }

    public static void main(String[] args) throws IOException {
        new PlainNioEchoServer().server(9000);

    }


}


