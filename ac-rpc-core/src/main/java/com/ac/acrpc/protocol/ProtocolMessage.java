package com.ac.acrpc.protocol;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Protocol message structure
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
public class ProtocolMessage<T> {

    /**
     * Message header
     */
    private Header header;

    /**
     * Message body (request or response object)
     */
    private T body;

    /**
     * Protocol message header
     */
    @Data
    public static class Header {

        /**
         * Magic number, for validation
         */
        private byte magic;

        /**
         * Version
         */
        private byte version;

        /**
         * Serializer
         */
        private byte serializer;

        /**
         * Message type (request / response)
         */
        private byte type;

        /**
         * Status
         */
        private byte status;

        /**
         * Request ID
         */
        private long requestId;

        /**
         * Body length
         */
        private int bodyLength;
    }

}
