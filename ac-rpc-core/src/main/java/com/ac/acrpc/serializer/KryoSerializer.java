package com.ac.acrpc.serializer;

import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.io.Input;
import com.esotericsoftware.kryo.io.Output;
import com.esotericsoftware.kryo.util.Pool;

/**
 * Kryo serializer
 */
public class KryoSerializer implements Serializer {

    /**
     * Kryo is not thread-safe. A bounded pool is used rather than a ThreadLocal: with virtual
     * threads every request may run on a fresh thread, and a ThreadLocal would build a new
     * Kryo instance per call.
     */
    private static final Pool<Kryo> KRYO_POOL = new Pool<Kryo>(true, false, 256) {
        @Override
        protected Kryo create() {
            Kryo kryo = new Kryo();
            // Don't require class registration (may have security implications)
            kryo.setRegistrationRequired(false);
            return kryo;
        }
    };

    @Override
    public <T> byte[] serialize(T obj) {
        Kryo kryo = KRYO_POOL.obtain();
        try (Output output = new Output(256, -1)) {
            kryo.writeObject(output, obj);
            return output.toBytes();
        } finally {
            KRYO_POOL.free(kryo);
        }
    }

    @Override
    public <T> T deserialize(byte[] bytes, Class<T> classType) {
        Kryo kryo = KRYO_POOL.obtain();
        try (Input input = new Input(bytes)) {
            return kryo.readObject(input, classType);
        } finally {
            KRYO_POOL.free(kryo);
        }
    }
}
