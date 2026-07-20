package com.wzz.game_console.util;

import com.wzz.game_console.ModMain;
import net.minecraft.resources.ResourceLocation;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Constructor;
import java.util.Arrays;
import java.util.List;

public class ResourceUtil {

    private static final ResourceLocationFactory FACTORY;

    static {
        ResourceLocationFactory factory1;
        MethodHandles.Lookup lookup = MethodHandles.lookup();
        try {
            MethodHandle fromNamespaceAndPath = lookup.findStatic(ResourceLocation.class, "fromNamespaceAndPath",
                    MethodType.methodType(ResourceLocation.class, String.class, String.class));
            MethodHandle parse = lookup.findStatic(ResourceLocation.class, "parse",
                    MethodType.methodType(ResourceLocation.class, String.class));
            factory1 = new HighVersionFactory(fromNamespaceAndPath, parse);
        } catch (Exception e) {
            try {
                MethodHandle tryWithResources = lookup.findStatic(ResourceLocation.class, "tryWithResources",
                        MethodType.methodType(ResourceLocation.class, String.class, String.class));
                factory1 = new LowVersionFactory(tryWithResources, LowVersionFactory.MethodType.TRY_WITH_RESOURCES);
            } catch (Exception e1) {
                try {
                    MethodHandle valueOf = lookup.findStatic(ResourceLocation.class, "valueOf",
                            MethodType.methodType(ResourceLocation.class, String.class, String.class));
                    factory1 = new LowVersionFactory(valueOf, LowVersionFactory.MethodType.VALUE_OF);
                } catch (Exception e2) {
                    try {
                        MethodHandle of = tryFindStaticMethod(lookup, ResourceLocation.class, MethodType.methodType(ResourceLocation.class,
                                String.class, String.class), Arrays.asList("of", "m_135822_"));
                        factory1 = new LowVersionFactory(of, LowVersionFactory.MethodType.OF);
                    } catch (Exception e3) {
                        try {
                            Constructor<ResourceLocation> constructor = ResourceLocation.class.getDeclaredConstructor(String.class, String.class);
                            constructor.setAccessible(true);
                            factory1 = new LowVersionFactory(constructor, LowVersionFactory.MethodType.CONSTRUCTOR);
                        } catch (Exception e4) {
                            throw new RuntimeException("无法找到可用的 ResourceLocation 创建方法", e4);
                        }
                    }
                }
            }
        }
        FACTORY = factory1;
    }

    private interface ResourceLocationFactory {
        ResourceLocation create(String namespace, String path);
        ResourceLocation parse(String path);
    }

    private static class HighVersionFactory implements ResourceLocationFactory {
        private final MethodHandle fromNamespaceAndPath;
        private final MethodHandle parse;

        public HighVersionFactory(MethodHandle fromNamespaceAndPath, MethodHandle parse) {
            this.fromNamespaceAndPath = fromNamespaceAndPath;
            this.parse = parse;
        }

        @Override
        public ResourceLocation create(String namespace, String path) {
            try {
                return (ResourceLocation) fromNamespaceAndPath.invokeExact(namespace, path);
            } catch (Throwable e) {
                throw new RuntimeException("高版本创建 ResourceLocation 失败", e);
            }
        }

        @Override
        public ResourceLocation parse(String path) {
            try {
                return (ResourceLocation) parse.invokeExact(path);
            } catch (Throwable e) {
                throw new RuntimeException("高版本解析 ResourceLocation 失败", e);
            }
        }
    }

    private static class LowVersionFactory implements ResourceLocationFactory {
        private final Object method;
        private final MethodType methodType;

        enum MethodType {
            TRY_WITH_RESOURCES, VALUE_OF, OF, CONSTRUCTOR
        }

        public LowVersionFactory(MethodHandle methodHandle, MethodType methodType) {
            this.method = methodHandle;
            this.methodType = methodType;
        }

        public LowVersionFactory(Constructor<ResourceLocation> constructor, MethodType methodType) {
            this.method = constructor;
            this.methodType = methodType;
        }

        @SuppressWarnings("unchecked")
        @Override
        public ResourceLocation create(String namespace, String path) {
            try {
                return switch (methodType) {
                    case TRY_WITH_RESOURCES, VALUE_OF, OF ->
                            (ResourceLocation) ((MethodHandle) method).invokeExact(namespace, path);
                    case CONSTRUCTOR -> ((Constructor<ResourceLocation>) method).newInstance(namespace, path);
                };
            } catch (Throwable e) {
                throw new RuntimeException("低版本创建 ResourceLocation 失败", e);
            }
        }

        @Override
        public ResourceLocation parse(String path) {
            int colonIndex = path.indexOf(':');
            if (colonIndex == -1) {
                return create(ModMain.MODID, path);
            } else {
                String namespace = path.substring(0, colonIndex);
                String localPath = path.substring(colonIndex + 1);
                return create(namespace, localPath);
            }
        }
    }

    private static MethodHandle tryFindStaticMethod(MethodHandles.Lookup lookup,
                                                    Class<ResourceLocation> clazz,
                                                    MethodType methodType,
                                                    List<String> methodNames) {
        for (String methodName : methodNames) {
            try {
                return lookup.findStatic(clazz, methodName, methodType);
            } catch (NoSuchMethodException | IllegalAccessException ignored) {
            }
        }
        return null;
    }

    /**
     * 创建ResourceLocation的通用方法
     */
    private static ResourceLocation createResourceLocation(String namespace, String path) {
        return FACTORY.create(namespace, path);
    }

    /**
     * 解析ResourceLocation的通用方法
     */
    private static ResourceLocation parseResourceLocation(String path) {
        return FACTORY.parse(path);
    }

    public static ResourceLocation createInstance(String path) {
        return createResourceLocation(ModMain.MODID, path);
    }

    public static String createStringInstance(String path) {
        return ModMain.MODID + ":" + path;
    }

    public static ResourceLocation createInstance(String name, String path) {
        return createResourceLocation(name, path);
    }

    public static ResourceLocation createInstanceNoNamespace(String path) {
        return parseResourceLocation(path);
    }

    public static ResourceLocation createMinecraftInstance(String path) {
        return createResourceLocation("minecraft", path);
    }

    public static ResourceLocation createTextureInstance(String path) {
        return createResourceLocation(ModMain.MODID, "textures/" + path);
    }

    public static ResourceLocation createInstanceWithColon(String all) {
        String namespace;
        String path;
        if (all.contains(":")) {
            int colonIndex = all.indexOf(":");
            namespace = all.substring(0, colonIndex);
            path = all.substring(colonIndex + 1);
        } else {
            namespace = ModMain.MODID;
            path = all;
        }
        return createInstance(namespace, path);
    }

    /**
     * 自动解析 namespace:path
     * 若无冒号，自动补 modid
     */
    public static ResourceLocation create(String id) {
        if (id.contains(":")) {
            return createInstanceWithColon(id);
        }
        return createInstance(ModMain.MODID, id);
    }
}
