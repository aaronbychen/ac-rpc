package com.ac.acrpc.protocol;

/**
 * 协议常量
 *
 * @author <a href="https://github.com/aaronbychen">Aaron Chen</a>
 */
public interface ProtocolConstant {

    /**
     * 消息头长度
     */
    int MESSAGE_HEADER_LENGTH = 17;

    /**
     * 协议魔数
     */
    byte PROTOCOL_MAGIC = 0x1;

    /**
     * 协议版本号
     */
    byte PROTOCOL_VERSION = 0x1;
}
