package com.ac.acrpc.exception;

import org.junit.Test;

import java.io.IOException;
import java.lang.reflect.Method;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class RemoteServiceExceptionTest {

    interface Api {
        void plain();

        void declares() throws IOException;
    }

    @Test
    public void rebuildsUncheckedExceptions() throws Exception {
        Throwable t = RemoteServiceException.rebuild(IllegalArgumentException.class.getName(), "bad", method("plain"));
        assertTrue(t instanceof IllegalArgumentException);
        assertEquals("bad", t.getMessage());
    }

    @Test
    public void rebuildsDeclaredCheckedExceptions() throws Exception {
        Throwable t = RemoteServiceException.rebuild(IOException.class.getName(), "disk", method("declares"));
        assertTrue(t instanceof IOException);
    }

    @Test
    public void wrapsUndeclaredCheckedExceptions() throws Exception {
        Throwable t = RemoteServiceException.rebuild(IOException.class.getName(), "disk", method("plain"));
        assertTrue(t instanceof RemoteServiceException);
        assertEquals(IOException.class.getName(), ((RemoteServiceException) t).getRemoteType());
    }

    @Test
    public void wrapsUnknownTypes() throws Exception {
        Throwable t = RemoteServiceException.rebuild("com.example.NotOnClasspath", "x", method("plain"));
        assertTrue(t instanceof RemoteServiceException);
    }

    private static Method method(String name) throws NoSuchMethodException {
        return Api.class.getMethod(name);
    }
}
