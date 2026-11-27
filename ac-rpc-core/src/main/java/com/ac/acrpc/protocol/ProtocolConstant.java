package com.ac.acrpc.protocol;

/**
 * Protocol constants
 */
public interface ProtocolConstant {

    /**
     * Message header length
     */
    int MESSAGE_HEADER_LENGTH = 17;

    /**
     * Protocol magic number
     */
    byte PROTOCOL_MAGIC = 0x1;

    /**
     * Protocol version
     */
    byte PROTOCOL_VERSION = 0x1;
}
