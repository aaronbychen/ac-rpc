package com.ac.acrpc.serializer;

import java.io.IOException;

/**
 * 序列化器接口
 *
 * @author <a href="https://github.com/aaronbychen">Aaron Chen</a>
 */
public interface Serializer {

    /**
     * 序列化
     *
     * @param object
     * @param <T>
     * @return
     * @throws IOException
     */
    <T> byte[] serialize(T object) throws IOException;

    /**
     * 反序列化
     *
     * @param bytes
     * @param tClass
     * @param <T>
     * @return
     * @throws IOException
     */
    <T> T deserialize(byte[] bytes, Class<T> tClass) throws IOException;
}
