package java.lang.invoke;
/** Compile-time only stub so ECJ can emit invokedynamic for lambdas against android.jar. Never packaged. */
public final class LambdaMetafactory {
    public static final int FLAG_SERIALIZABLE = 1;
    public static final int FLAG_MARKERS = 2;
    public static final int FLAG_BRIDGES = 4;
    private LambdaMetafactory() {}
    public static CallSite metafactory(MethodHandles.Lookup caller, String invokedName, MethodType invokedType,
            MethodType samMethodType, MethodHandle implMethod, MethodType instantiatedMethodType)
            throws LambdaConversionException { throw new LambdaConversionException(); }
    public static CallSite altMetafactory(MethodHandles.Lookup caller, String invokedName, MethodType invokedType, Object... args)
            throws LambdaConversionException { throw new LambdaConversionException(); }
}
