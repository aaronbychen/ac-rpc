package com.ac.acrpc.server.tcp;

public class BlockingTcpClientTest extends RpcClientContractTest {

    @Override
    protected RpcClient newClient() {
        return new BlockingTcpClient(1000, 2, 10_000, BlockingTcpClient.WriteLock.REENTRANT);
    }
}
