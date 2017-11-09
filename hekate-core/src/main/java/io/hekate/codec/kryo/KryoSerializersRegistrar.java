package io.hekate.codec.kryo;

import com.esotericsoftware.kryo.Kryo;
import de.javakaffee.kryoserializers.ArraysAsListSerializer;
import de.javakaffee.kryoserializers.GregorianCalendarSerializer;
import de.javakaffee.kryoserializers.JdkProxySerializer;
import de.javakaffee.kryoserializers.SynchronizedCollectionsSerializer;
import de.javakaffee.kryoserializers.UnmodifiableCollectionsSerializer;
import java.lang.reflect.InvocationHandler;
import java.util.Arrays;
import java.util.GregorianCalendar;

/**
 * Utility class that automatically registers {@code de.javakaffee:kryo-serializers} if they are available on the classpath.
 */
final class KryoSerializersRegistrar {
    private static final boolean SUPPORTED;

    private static final Class<?> ARRAYS_AS_LIST_CLASS = Arrays.asList(1, 2).getClass();

    static {
        boolean supported = true;

        try {
            Class.forName("de.javakaffee.kryoserializers.ArraysAsListSerializer").newInstance();
        } catch (Throwable t) {
            supported = false;
        }

        SUPPORTED = supported;
    }

    private KryoSerializersRegistrar() {
        // No-op.
    }

    public static void register(Kryo kryo) {
        if (SUPPORTED) {
            kryo.register(ARRAYS_AS_LIST_CLASS, new ArraysAsListSerializer());
            kryo.register(GregorianCalendar.class, new GregorianCalendarSerializer());
            kryo.register(InvocationHandler.class, new JdkProxySerializer());
            kryo.register(GregorianCalendar.class, new GregorianCalendarSerializer());
            kryo.register(InvocationHandler.class, new JdkProxySerializer());

            UnmodifiableCollectionsSerializer.registerSerializers(kryo);

            SynchronizedCollectionsSerializer.registerSerializers(kryo);
        }
    }

    public static boolean isSupported() {
        return SUPPORTED;
    }
}
