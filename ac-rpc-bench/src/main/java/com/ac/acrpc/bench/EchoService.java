package com.ac.acrpc.bench;

/**
 * Benchmark service: returns the payload unchanged.
 */
public interface EchoService {

    byte[] echo(byte[] payload);
}
