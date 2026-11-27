package com.ac.acrpc.server.tcp;

public class VertxTcpClientTest extends RpcClientContractTest {

    @Override
    protected RpcClient newClient() {
        return new VertxTcpClient(1000, 2, 10_000);
    }
}
