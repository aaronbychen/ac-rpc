package com.ac.acrpc.server.tcp;

import com.ac.acrpc.exception.RpcException;
import com.ac.acrpc.model.RpcRequest;
import com.ac.acrpc.protocol.ProtocolConstant;
import com.ac.acrpc.protocol.ProtocolMessage;
import com.ac.acrpc.protocol.ProtocolMessageEncoder;
import com.ac.acrpc.protocol.ProtocolMessageSerializerEnum;
import com.ac.acrpc.protocol.ProtocolMessageTypeEnum;
import io.vertx.core.buffer.Buffer;

import java.io.IOException;

/**
 * Request framing shared by the client transports.
 */
final class Frames {

    private Frames() {
    }

    static Buffer encodeRequest(RpcRequest request, long requestId, String serializer) throws IOException {
        ProtocolMessageSerializerEnum serializerEnum = ProtocolMessageSerializerEnum.getEnumByValue(serializer);
        if (serializerEnum == null) {
            throw new RpcException("Unknown serializer: " + serializer);
        }
        ProtocolMessage.Header header = new ProtocolMessage.Header();
        header.setMagic(ProtocolConstant.PROTOCOL_MAGIC);
        header.setVersion(ProtocolConstant.PROTOCOL_VERSION);
        header.setSerializer((byte) serializerEnum.getKey());
        header.setType((byte) ProtocolMessageTypeEnum.REQUEST.getKey());
        header.setRequestId(requestId);
        return ProtocolMessageEncoder.encode(new ProtocolMessage<>(header, request));
    }
}
