package dev.fumaz.infuse.injector;

import dev.fumaz.infuse.annotation.Inject;
import dev.fumaz.infuse.annotation.PostConstruct;
import dev.fumaz.infuse.annotation.PostInject;
import dev.fumaz.infuse.annotation.PreDestroy;
import dev.fumaz.infuse.bind.Binding;
import dev.fumaz.infuse.context.Context;
import dev.fumaz.infuse.module.Module;
import dev.fumaz.infuse.provider.InstanceProvider;
import dev.fumaz.infuse.provider.Provider;
import dev.fumaz.infuse.provider.SingletonProvider;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.lang.annotation.Annotation;
import java.lang.annotation.ElementType;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.*;
import java.util.logging.Logger;
import java.util.stream.Collectors;

public class InfuseInjector implements Injector {

    private final @Nullable Injector parent;
    private final @NotNull List<Module> modules;
    private final @NotNull Map<Class<?>, Object> cache;

    public InfuseInjector(@Nullable Injector parent, @NotNull List<Module> modules) {
        this.parent = parent;
        this.modules = modules;
        this.cache = new HashMap<>();

        modules.forEach(Module::configure);

        getOwnBindings().forEach(binding -> {
            if (!(binding.getProvider() instanceof SingletonProvider<?>)) {
                return;
            }

            SingletonProvider<?> provider = (SingletonProvider<?>) binding.getProvider();

            if (!provider.isEager()) {
                return;
            }

            try {
                provider.provideWithoutInjecting(new Context<>(getClass(), this, this, ElementType.FIELD, "eager", new Annotation[0]));
            } catch (Exception e) {
                System.err.println("Failed to eagerly initialize " + binding.getType().getName());
                e.printStackTrace();
            }
        });

        getOwnBindings().forEach(binding -> {
            if (!(binding.getProvider() instanceof SingletonProvider<?>)) {
                return;
            }

            SingletonProvider<?> provider = (SingletonProvider<?>) binding.getProvider();

            if (!provider.isEager()) {
                return;
            }

            try {
                injectVariables(provider.provideWithoutInjecting(new Context<>(getClass(), this, this, ElementType.FIELD, "eager", new Annotation[0])));
            } catch (Exception e) {
                System.err.println("Failed to eagerly inject variables in " + binding.getType().getName());
                e.printStackTrace();
            }
        });

        getOwnBindings().forEach(binding -> {
            if (!(binding.getProvider() instanceof SingletonProvider<?>)) {
                return;
            }

            SingletonProvider<?> provider = (SingletonProvider<?>) binding.getProvider();

            if (!provider.isEager()) {
                return;
            }

            try {
                injectMethods(provider.provideWithoutInjecting(new Context<>(getClass(), this, this, ElementType.FIELD, "eager", new Annotation[0])));
            } catch (Exception e) {
                System.err.println("Failed to eagerly inject methods in " + binding.getType().getName());
                e.printStackTrace();
            }
        });

        getOwnBindings().forEach(binding -> {
            if (!(binding.getProvider() instanceof InstanceProvider<?>)) {
                return;
            }

            InstanceProvider<?> provider = (InstanceProvider<?>) binding.getProvider();

            try {
                provider.provide(new Context<>(getClass(), this, this, ElementType.FIELD, "eager", new Annotation[0]));
            } catch (Exception e) {
                System.err.println("Failed to eagerly initialize " + binding.getType().getName());
                e.printStackTrace();
            }
        });
    }

    public void inject(@NotNull Object object) {
        injectVariables(object);
        injectMethods(object);
    }

    @Override
    public <T> T provide(@NotNull Class<T> type, @NotNull Context<?> context) {
        cache.put(context.getObject().getClass(), context.getObject());

        if (cache.containsKey(type)) {
            return (T) cache.get(type);
        }

        Binding<T> binding = getBindingOrNull(type);

        if (binding != null) {
            T t = binding.getProvider().provide(context);
            cache.remove(context.getObject().getClass());

            return t;
        }

        T t = construct(type);
        cache.remove(context.getObject().getClass());

        return t;
    }

    @Override
    public <T> @Nullable T provide(@NotNull Class<T> type, @NotNull Object calling) {
        cache.put(calling.getClass(), calling);

        if (cache.containsKey(type)) {
            return (T) cache.get(type);
        }

        Binding<T> binding = getBindingOrNull(type);

        if (binding != null) {
            T t = binding.getProvider().provide(this, calling);
            cache.remove(calling.getClass());

            return t;
        }

        T t = construct(type);
        cache.remove(calling.getClass());

        return t;
    }

    @Override
    public <T> T construct(@NotNull Class<T> type, @NotNull Object... args) {
        Constructor<T> constructor = findSuitableConstructor(type, args);

        if (constructor == null) {
            throw new RuntimeException("No suitable constructor found for " + type.getName());
        }

        constructor.setAccessible(true);

        try {
            T t = constructor.newInstance(getConstructorArguments(constructor, args));

            for (Method method : t.getClass().getDeclaredMethods()) {
                if (method.isAnnotationPresent(PostConstruct.class)) {
                    method.setAccessible(true);
                    method.invoke(t);
                }
            }

            inject(t);

            return t;
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    public <T> T constructWithoutInjecting(@NotNull Class<T> type, @NotNull Object... args) {
        Constructor<T> constructor = findSuitableConstructor(type, args);

        if (constructor == null) {
            throw new RuntimeException("No injectable constructor found for " + type.getName());
        }

        constructor.setAccessible(true);

        try {
            T t = constructor.newInstance(getConstructorArguments(constructor, args));

            for (Method method : t.getClass().getDeclaredMethods()) {
                if (method.isAnnotationPresent(PostConstruct.class)) {
                    method.setAccessible(true);
                    method.invoke(t);
                }
            }

            return t;
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void destroy() {
        getBindings().forEach(binding -> {
            for (Method method : getAllMethods(binding.getType())) {
                try {
                    if (!method.isAnnotationPresent(PreDestroy.class)) {
                        continue;
                    }

                    method.setAccessible(true);
                    method.invoke(binding.getProvider().provide(new Context<>(binding.getType(), this, this, ElementType.METHOD, method.getName(), method.getAnnotations())));
                } catch (InvocationTargetException | IllegalAccessException e) {
                    e.printStackTrace();
                } catch (Exception ignored) {
                }
            }
        });
    }

    @Override
    public <T> @Nullable Provider<T> getProvider(@NotNull Class<T> type) {
        return getBindingOrThrow(type).getProvider();
    }

    @Override
    public @Nullable Injector getParent() {
        return parent;
    }

    @Override
    public @NotNull Injector child(@NotNull List<Module> modules) {
        return new InfuseInjector(this, modules);
    }

    @Override
    public @NotNull List<Module> getModules() {
        List<Module> modules = new ArrayList<>();

        if (parent != null) {
            modules.addAll(parent.getModules());
        }

        modules.addAll(this.modules);

        return modules;
    }

    @Override
    public @NotNull List<Binding<?>> getBindings() {
        List<Binding<?>> bindings = new ArrayList<>();

        bindings.add(new Binding<>(Injector.class, new InstanceProvider<>(this)));
        bindings.add(new Binding<>(Logger.class, (context) -> Logger.getLogger(context.getType().getSimpleName())));

        for (Module module : getModules()) {
            for (Binding<?> binding : module.getBindings()) {
                bindings.removeIf(binding::equals);
                bindings.add(binding);
            }
        }

        return bindings;
    }

    @Override
    public @NotNull <T> List<Binding<? extends T>> getBindings(Class<T> type) {
        return getBindings().stream()
                .filter(binding -> type.isAssignableFrom(binding.getType()))
                .map(binding -> (Binding<? extends T>) binding)
                .collect(Collectors.toList());
    }

    private List<Binding<?>> getOwnBindings() {
        List<Binding<?>> bindings = new ArrayList<>();

        for (Module module : modules) {
            for (Binding<?> binding : module.getBindings()) {
                bindings.removeIf(binding::equals);
                bindings.add(binding);
            }
        }

        return bindings;
    }

    public <T> @NotNull Binding<T> getBindingOrThrow(@NotNull Class<T> type) {
        return (Binding<T>) getBindings().stream()
                .filter(binding -> binding.getType().isAssignableFrom(type) || type.isAssignableFrom(binding.getType()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("No binding found for type " + type));
    }

    public <T> @Nullable Binding<T> getBindingOrNull(@NotNull Class<T> type) {
        return (Binding<T>) getBindings().stream()
                .filter(binding -> binding.getType().isAssignableFrom(type) || type.isAssignableFrom(binding.getType()))
                .findFirst()
                .orElse(null);
    }

    private <T> @Nullable Constructor<T> findInjectableConstructor(@NotNull Class<T> type) {
        Constructor<T> injectableConstructor = null;

        for (Constructor<?> constructor : type.getDeclaredConstructors()) {
            if (constructor.isAnnotationPresent(Inject.class)) {
                if (injectableConstructor != null && injectableConstructor.isAnnotationPresent(Inject.class)) {
                    throw new IllegalArgumentException("Multiple injectable constructors found for type " + type);
                }

                injectableConstructor = (Constructor<T>) constructor;
            }

            if (constructor.getParameterCount() == 0 && injectableConstructor == null) {
                injectableConstructor = (Constructor<T>) constructor;
            }
        }

        return injectableConstructor;
    }

    public <T> Constructor<T> findSuitableConstructor(Class<T> clazz, Object... args) {
        Constructor<?>[] constructors = clazz.getDeclaredConstructors();
        Constructor<T> bestMatch = null;
        int bestMatchScore = Integer.MAX_VALUE;

        for (Constructor<?> constructor : constructors) {
            Class<?>[] parameterTypes = constructor.getParameterTypes();

            if (parameterTypes.length != args.length) {
                continue;
            }

            int matchScore = 0;
            boolean suitable = true;

            for (int i = 0; i < args.length; i++) {
                Class<?> expectedType = parameterTypes[i];

                if (args[i] == null) {
                    matchScore += 0;
                } else {
                    Class<?> actualType = args[i].getClass();

                    if (expectedType.isAssignableFrom(actualType)) {
                        int distance = getClassDistance(expectedType, actualType);
                        if (distance != -1) {
                            matchScore += distance;
                        } else {
                            suitable = false;
                            break;
                        }
                    } else {
                        suitable = false;
                        break;
                    }
                }
            }

            if (suitable && matchScore < bestMatchScore) {
                bestMatch = (Constructor<T>) constructor;
                bestMatchScore = matchScore;
            }
        }

        return bestMatch;
    }

    private int getClassDistance(Class<?> expected, Class<?> actual) {
        if (expected.equals(actual)) {
            return 0;
        } else if (!expected.isAssignableFrom(actual)) {
            return -1;
        }

        int distance = 0;
        while (actual != null && !actual.equals(expected)) {
            actual = actual.getSuperclass();
            distance++;
        }

        return distance;
    }

    private @NotNull Object[] getConstructorArguments(@NotNull Constructor<?> constructor, Object... provided) {
        Object[] args = new Object[constructor.getParameterCount()];

        for (int i = 0; i < args.length; i++) {
            Class<?> type = constructor.getParameterTypes()[i];
            Annotation[] annotations = constructor.getParameterAnnotations()[i];

            if (provided.length == 0 || provided.length <= i || constructor.getParameters()[i].isAnnotationPresent(Inject.class)) {
                args[i] = provide(type, new Context<>(constructor.getDeclaringClass(), this, this, ElementType.CONSTRUCTOR, constructor.getParameters()[i].getName(), annotations));
            } else {
                args[i] = provided[i];
            }
        }

        return args;
    }

    private <T> List<Field> getAllFields(Class<T> type) {
        List<Field> fields = new ArrayList<>(Arrays.asList(type.getDeclaredFields()));

        if (type.getSuperclass() != null) {
            fields.addAll(getAllFields(type.getSuperclass()));
        }

        return fields;
    }

    private <T> List<Method> getAllMethods(Class<T> type) {
        List<Method> methods = new ArrayList<>(Arrays.asList(type.getDeclaredMethods()));

        if (type.getSuperclass() != null) {
            methods.addAll(getAllMethods(type.getSuperclass()));
        }

        return methods;
    }

    private void injectVariables(Object object) {
        List<Binding<?>> bindings = getBindings();

        for (Field field : getAllFields(object.getClass())) {
            if (field.isAnnotationPresent(Inject.class)) {
                field.setAccessible(true);

                try {
                    field.set(object, provide(field.getType(), new Context<>(object.getClass(), object, this, ElementType.FIELD, field.getName(), field.getAnnotations())));
                } catch (IllegalAccessException e) {
                    throw new RuntimeException(e);
                }
            }
        }
    }

    private void injectMethods(Object object) {
        for (Method method : getAllMethods(object.getClass())) {
            if (method.isAnnotationPresent(PostInject.class)) {
                method.setAccessible(true);

                try {
                    method.invoke(object);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }
        }
    }

}
