package com.ac.acrpc.bench;

import java.util.concurrent.atomic.LongAdder;

public class EchoServiceImpl implements EchoService {

    /** Calls served by this process, logged by the provider so fault experiments can see who gets traffic. */
    static final LongAdder SERVED = new LongAdder();

    @Override
    public byte[] echo(byte[] payload) {
        SERVED.increment();
        return payload;
    }
}