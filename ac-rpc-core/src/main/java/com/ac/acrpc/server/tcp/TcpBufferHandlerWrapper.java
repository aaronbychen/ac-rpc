package com.ac.acrpc.server.tcp;

import com.ac.acrpc.protocol.ProtocolConstant;
import io.vertx.core.Handler;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.parsetools.RecordParser;

/**
 * TCP buffer handler wrapper
 * Decorator that wraps the original handler with a RecordParser
 */
public class TcpBufferHandlerWrapper implements Handler<Buffer> {

    /**
     * Parser that handles partial and sticky packets
     */
    private final RecordParser recordParser;

    public TcpBufferHandlerWrapper(Handler<Buffer> bufferHandler) {
        recordParser = initRecordParser(bufferHandler);
    }

    @Override
    public void handle(Buffer buffer) {
        recordParser.handle(buffer);
    }

    /**
     * Initialize the parser
     *
     * @param bufferHandler
     * @return
     */
    private RecordParser initRecordParser(Handler<Buffer> bufferHandler) {
        RecordParser parser = RecordParser.newFixed(ProtocolConstant.MESSAGE_HEADER_LENGTH);

        parser.setOutput(new Handler<Buffer>() {
            int size = -1;
            // One complete read (header + body)
            Buffer resultBuffer = Buffer.buffer();

            @Override
            public void handle(Buffer buffer) {
                // 1. Read the header first
                if (-1 == size) {
                    // Read body length
                    size = buffer.getInt(13);
                    parser.fixedSizeMode(size);
                    // Append header to result
                    resultBuffer.appendBuffer(buffer);
                } else {
                    // 2. Then read the body
                    // Append body to result
                    resultBuffer.appendBuffer(buffer);
                    // Full message assembled, handle it
                    bufferHandler.handle(resultBuffer);
                    // Reset for the next message
                    parser.fixedSizeMode(ProtocolConstant.MESSAGE_HEADER_LENGTH);
                    size = -1;
                    resultBuffer = Buffer.buffer();
                }
            }
        });

        return parser;
    }
}
