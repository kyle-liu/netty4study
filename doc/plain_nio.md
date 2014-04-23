# JDK NIO DEMO解析


```Java  

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.Date;
import java.util.Iterator;
import java.util.Set;

/**
 * User: kyle
 * Date: 14-1-3
 * Time: PM1:11
 */
public class PlainNioEchoServer {

    public void  server(int port) throws IOException {
        System.out.println("Listening for connections on port :" + port);

        //1.创建服务端通道对象
        ServerSocketChannel  serverSocketChannel = ServerSocketChannel.open();
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
            try{
                //6.选择器阻塞等待监听serverSocketChannel在第5步上注册的事件
                selector.select();

            }
            catch (IOException ex) {
                ex.printStackTrace();
                break;
            }

             //7.当有事件发生时，
            Set readyKeys =  selector.selectedKeys();
            Iterator iterator =  readyKeys.iterator();
            while (iterator.hasNext()) {
                SelectionKey key  = (SelectionKey)iterator.next();
                iterator.remove();
                try{
                    //是否可以接受连接请求
                    if(key.isAcceptable()) {
                        //得到当前发生事件的Channel对象，此处是ServerSocketChannel
                        ServerSocketChannel server=(ServerSocketChannel)key.channel();
                        //接受客户端的连接请求，并产生对应的客户端SocketChannel对象
                        SocketChannel client = server.accept();
                        System.out.println("Accepted connection from " +client);
                        client.configureBlocking(false);
                        //在Selctor上注册client SocketChannel感兴趣的读和写事件
                        client.register(selector,
                                SelectionKey.OP_WRITE | SelectionKey.OP_READ, ByteBuffer.allocate(100));



                    }
                    //是否已经准备好读取从客户端传递过来的上行数据
                    if(key.isReadable()){
                        SocketChannel client  = (SocketChannel)key.channel();
                        ByteBuffer output = (ByteBuffer)key.attachment();
                        client.read(output);

                    }
                    //是否已经准备好往客户端传递下行数据
                    if(key.isWritable()) {
                        SocketChannel client =  (SocketChannel)key.channel();
                        ByteBuffer output = (ByteBuffer)key.attachment();

                        output.flip();
                        client.write(output);
                        output.compact();
                    }


                }
                catch (IOException ex){
                    key.cancel();
                    try{
                        key.channel().close();
                    }
                    catch (IOException cex) {

                    }

                }
            }

        }

    }

    public static void main(String[] args) throws IOException {
        new PlainNioEchoServer().serve(9000);

    }


}

```
