# 源代码阅读笔记之ByteBuf

##简介
ByteBuf是一个可以随机且顺序访问的0个字节或者更多八位字节。
这个接口为一个或者更多的原始类型byte[]数组和NIO的 ByteBuffer听过了一个统一的抽象操作。


**创建一个buffer**  
当你想创建一个buffer时，它推荐你使用Unpooled的帮助方法来创建一个buffer，而不是调用其实现类的构造函数

**ByteBuf的逻辑结构**  
ByteBuf提供了两个指针来支持顺序的read或者write操作。下图展示了两个指针是如何把一个buffer分段成三个部分的： 

      +-------------------+------------------+------------------+
      | discardable bytes |  readable bytes  |  writable bytes  |
      |                   |     (CONTENT)    |                  |
      +-------------------+------------------+------------------+
      |                   |                  |                  |
      0      <=      readerIndex   <=   writerIndex    <=    capacity
      
 
* readerIndex前面是已经读过的数据，这些数据可以废弃掉  
* 从readIndex到writeIndex之间的数据是可读数据  
* 从writerIndex开始，为可写区域

**随机访问**  
```java
    ByteBuf buffer = ...;
    for (int i = 0; i &lt; buffer.capacity(); i ++) {
      byte b = buffer.getByte(i);
      System.out.println((char) b);
    }
 ```
