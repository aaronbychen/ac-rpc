package com.ac.acrpc.bench;

public class EchoServiceImpl implements EchoService {

    @Override
    public byte[] echo(byte[] payload) {
        return payload;
    }
}
