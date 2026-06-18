package net.spartanb312.grunteon.obfuscator.util;

import sun.misc.Unsafe;

import java.lang.invoke.MethodHandles;
import java.lang.reflect.Field;

@SuppressWarnings({"removal", "unused", "SameParameterValue", "DuplicatedCode", "SpellCheckingInspection"})
public class ImplLookupGetter {
    public static MethodHandles.Lookup getLookup() {
        try {
            final Field unsafeField = Unsafe.class.getDeclaredField("theUnsafe");
            unsafeField.setAccessible(true);
            final Unsafe unsafe = (Unsafe) unsafeField.get(null);

            final Field implLookupField = MethodHandles.Lookup.class.getDeclaredField("IMPL_LOOKUP");
            final Object staticBase = unsafe.staticFieldBase(implLookupField);
            final long staticOffset = unsafe.staticFieldOffset(implLookupField);
            return (MethodHandles.Lookup) unsafe.getObject(staticBase, staticOffset);
        } catch (Throwable t) {
            switch (t) {
                case RuntimeException re -> throw re;
                case Error e -> throw e;
                default -> throw new RuntimeException(t);
            }
        }
    }
}
